package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayCrypto
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Built-in loopback iPhone CarPlay simulator for testing Qonecta Unit on a PC/emulator or car
 * head unit without requiring a physical iPhone.
 *
 * Executes the real CarPlay AirPlay 2 + MFiSAP protocol over 127.0.0.1:
 * 1. Loads the offline MFi certificate (`certificate.p7b`) & private key (`identity.pk8`).
 * 2. Performs RTSP `GET /info`, `POST /auth-setup` (with full X25519 + MFiSAP ECDSA verification),
 *    `SETUP /session`, `SETUP /stream` (type 110), and `RECORD`.
 * 3. Encodes a live 30fps CarPlay Dashboard via Android's H.264 `MediaCodec`, encrypts every frame
 *    with ChaCha20-Poly1305 (`AirPlayCrypto.chachaSeal`), and streams it into `CarPlayMediaEngine`
 *    -> `ScreenStream` -> `AndroidMediaSink` (`TextureView`).
 */
class CarPlayLoopbackSimulator(
    private val context: Context,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    private val listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    private val reportStatus: (CarPlayStatus) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var screenSocket: Socket? = null
    private var activeSession: AirPlaySession? = null
    private var simThread: Thread? = null

    fun start() {
        if (closed.get()) return
        simThread = Thread(::runSimulation, "carplay-iphone-simulator").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { screenSocket?.close() }
        runCatching { clientSocket?.close() }
        runCatching { activeSession?.close() }
        runCatching { serverSocket?.close() }
        simThread?.interrupt()
    }

    private fun runSimulation() {
        try {
            reportStatus(CarPlayStatus.DiscoveringMfi)
            LeapCarPlayBootstrap.ensure(context)
            val mfiDir = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
            val mfiClient = LocalMfiAuthenticationClient.load(mfiDir)
            reportStatus(CarPlayStatus.MfiReady)

            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also {
                serverSocket = it
            }
            val acceptThread = Thread({
                runCatching {
                    val peer = server.accept()
                    val session = AirPlaySession(
                        socket = peer,
                        config = airPlayConfig,
                        identity = identity,
                        pairings = pairings,
                        mfi = mfiClient,
                        listener = listener,
                        media = media,
                    )
                    activeSession = session
                    session.start()
                }
            }, "carplay-sim-accept").apply {
                isDaemon = true
                start()
            }

            reportStatus(CarPlayStatus.ConnectingControl)
            val sock = Socket("127.0.0.1", server.localPort).also {
                it.soTimeout = 10_000
                clientSocket = it
            }
            val input = sock.getInputStream()
            val output = sock.getOutputStream()

            // Step 1: GET /info
            sendRtsp(output, "GET", "/info", cseq = 1)
            val infoResp = readRtspResponse(input)
            require(infoResp.status == 200) { "GET /info failed: ${infoResp.status}" }

            // Step 2: POST /auth-setup (MFiSAP X25519 + ECDSA verification)
            reportStatus(CarPlayStatus.Pairing)
            val iphoneKeyPair = AirPlayCrypto.x25519Generate()
            val authBody = ByteArray(33).apply {
                this[0] = 0x01
                iphoneKeyPair.publicKey.copyInto(this, 1)
            }
            sendRtsp(
                output = output,
                method = "POST",
                uri = "/auth-setup",
                cseq = 2,
                contentType = "application/octet-stream",
                body = authBody,
            )
            val authResp = readRtspResponse(input)
            require(authResp.status == 200) { "POST /auth-setup failed: ${authResp.status}" }
            val mfiSubject = verifyMfiSapResponse(
                iphoneKeyPair.privateKey,
                iphoneKeyPair.publicKey,
                authResp.body,
            )
            listener.onDebugLog("Simulador iPhone: MFiSAP verificado ($mfiSubject)")

            // Step 3: SETUP /session
            val sessionSetupBody = BplistCodec.encode(
                linkedMapOf(
                    "name" to "iPhone 16 Pro (Simulado)",
                    "deviceID" to "AA:BB:CC:DD:EE:FF",
                    "macAddress" to "11:22:33:44:55:66",
                    "model" to "iPhone16,2",
                    "sourceVersion" to "750.3.1",
                )
            )
            sendRtsp(
                output = output,
                method = "SETUP",
                uri = "rtsp://127.0.0.1/session",
                cseq = 3,
                contentType = "application/x-apple-binary-plist",
                body = sessionSetupBody,
            )
            val setupSessionResp = readRtspResponse(input)
            require(setupSessionResp.status == 200) { "SETUP /session failed: ${setupSessionResp.status}" }

            // Step 4: SETUP /stream (type 110 main screen)
            val connectionId = 0x1122334455667788L
            val streamSetupBody = BplistCodec.encode(
                linkedMapOf(
                    "streams" to listOf(
                        linkedMapOf(
                            "type" to 110,
                            "streamConnectionID" to connectionId,
                        )
                    )
                )
            )
            sendRtsp(
                output = output,
                method = "SETUP",
                uri = "rtsp://127.0.0.1/stream",
                cseq = 4,
                contentType = "application/x-apple-binary-plist",
                body = streamSetupBody,
            )
            val setupStreamResp = readRtspResponse(input)
            require(setupStreamResp.status == 200) { "SETUP /stream failed: ${setupStreamResp.status}" }
            val setupStreamPlist = BplistCodec.decode(setupStreamResp.body) as Map<*, *>
            val streams = setupStreamPlist["streams"] as List<*>
            val firstStream = streams.first() as Map<*, *>
            val dataPort = (firstStream["dataPort"] as Number).toInt()

            // Step 5: RECORD
            sendRtsp(output = output, method = "RECORD", uri = "rtsp://127.0.0.1/session", cseq = 5)
            val recordResp = readRtspResponse(input)
            require(recordResp.status == 200) { "RECORD failed: ${recordResp.status}" }

            reportStatus(CarPlayStatus.WirelessActive)
            acceptThread.join(1_000)

            // Step 6: Stream H.264 CarPlay Dashboard to 127.0.0.1:dataPort
            val connectionIdUnsigned = java.lang.Long.toUnsignedString(connectionId)
            val outputKey = AirPlayCrypto.hkdfSha512(
                ByteArray(32),
                "DataStream-Salt$connectionIdUnsigned".toByteArray(Charsets.US_ASCII),
                "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
                32,
            )
            streamSimulatedCarPlayVideo(
                port = dataPort,
                outputKey = outputKey,
                width = airPlayConfig.main.widthPixels.coerceAtLeast(800),
                height = airPlayConfig.main.heightPixels.coerceAtLeast(480),
                mfiSubject = mfiSubject,
            )
        } catch (error: Throwable) {
            if (!closed.get()) {
                Log.e(TAG, "Simulated iPhone CarPlay session error", error)
                reportStatus(CarPlayStatus.Failed("Simulador: ${error.message ?: error.javaClass.simpleName}"))
            }
        }
    }

    private fun verifyMfiSapResponse(
        iphonePrivateKey: ByteArray,
        iphonePub: ByteArray,
        body: ByteArray,
    ): String {
        val accPub = body.copyOfRange(0, 32)
        val certLen = ByteBuffer.wrap(body, 32, 4).int
        val respCertBytes = body.copyOfRange(36, 36 + certLen)
        val sigLen = ByteBuffer.wrap(body, 36 + certLen, 4).int
        val encryptedSig = body.copyOfRange(40 + certLen, 40 + certLen + sigLen)

        val shared = AirPlayCrypto.x25519Shared(iphonePrivateKey, accPub)
        val aesKey = MessageDigest.getInstance("SHA-1").apply {
            update("AES-KEY".toByteArray(Charsets.US_ASCII))
            update(shared)
        }.digest().copyOf(16)
        val aesIv = MessageDigest.getInstance("SHA-1").apply {
            update("AES-IV".toByteArray(Charsets.US_ASCII))
            update(shared)
        }.digest().copyOf(16)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
        val rawSig = cipher.doFinal(encryptedSig)

        val challengeDigest = MessageDigest.getInstance("SHA-256").apply {
            update(accPub)
            update(iphonePub)
        }.digest()

        val x509 = CertificateFactory.getInstance("X.509")
            .generateCertificates(respCertBytes.inputStream())
            .first() as X509Certificate
        val r = BigInteger(1, rawSig.copyOfRange(0, 32))
        val s = BigInteger(1, rawSig.copyOfRange(32, 64))
        val derSig = DERSequence(arrayOf(ASN1Integer(r), ASN1Integer(s))).encoded

        val verifier = Signature.getInstance("NONEwithECDSA")
        verifier.initVerify(x509.publicKey)
        verifier.update(challengeDigest)
        require(verifier.verify(derSig)) { "MFiSAP ECDSA signature verification failed" }
        return x509.subjectX500Principal.name.substringAfter("CN=").substringBefore(",")
    }

    private data class ParsedRtspResponse(val status: Int, val body: ByteArray)

    private fun readRtspResponse(inp: java.io.InputStream): ParsedRtspResponse {
        val headerBuf = ByteArrayOutputStream()
        var state = 0
        while (state < 4) {
            val b = inp.read()
            if (b < 0) error("Unexpected EOF reading RTSP response headers")
            headerBuf.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
        }
        val headerStr = headerBuf.toByteArray().toString(Charsets.US_ASCII)
        val lines = headerStr.split("\r\n").filter { it.isNotEmpty() }
        val status = lines.first().split(" ")[1].toInt()
        val contentLength = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.toInt() ?: 0
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = inp.read(body, read, contentLength - read)
            if (n < 0) error("Unexpected EOF reading RTSP body")
            read += n
        }
        return ParsedRtspResponse(status, body)
    }

    private fun streamSimulatedCarPlayVideo(
        port: Int,
        outputKey: ByteArray,
        width: Int,
        height: Int,
        mfiSubject: String,
    ) {
        val sock = Socket("127.0.0.1", port).also {
            it.tcpNoDelay = true
            screenSocket = it
        }
        val out = sock.getOutputStream()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val inputSurface = try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()
            surface
        } catch (error: Throwable) {
            runCatching { encoder.release() }
            throw error
        }

        val info = MediaCodec.BufferInfo()
        var frameCounter = 0L
        var tick = 0
        var configSent = false
        try {
            while (!closed.get()) {
                val canvas = inputSurface.lockCanvas(null)
                try {
                    drawSimulatedCarPlayDashboard(canvas, width, height, tick, mfiSubject)
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }
                tick++

                while (!closed.get()) {
                    val outIndex = encoder.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFormat = encoder.outputFormat
                            val sps = extractNaluWithoutStartCode(outFormat.getByteBuffer("csd-0"))
                            val pps = extractNaluWithoutStartCode(outFormat.getByteBuffer("csd-1"))
                            if (sps.isNotEmpty() && pps.isNotEmpty()) {
                                val avcC = buildAvcRecord(sps, pps)
                                writeScreenPacket(out, opcode = 1, body = avcC)
                                configSent = true
                            }
                        }
                        outIndex >= 0 -> {
                            val encodedBuffer = encoder.getOutputBuffer(outIndex)
                            if (encodedBuffer != null && info.size > 0 &&
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                                configSent
                            ) {
                                encodedBuffer.position(info.offset)
                                encodedBuffer.limit(info.offset + info.size)
                                val annexB = ByteArray(info.size)
                                encodedBuffer.get(annexB)
                                val header = ByteArray(128)
                                val sealedSize = annexB.size + 16
                                writeU32Le(header, 0, sealedSize)
                                header[4] = 0 // OP_VIDEO_FRAME
                                val sealed = AirPlayCrypto.chachaSeal(
                                    outputKey,
                                    AirPlayCrypto.nonce64(frameCounter++),
                                    annexB,
                                    header,
                                )
                                out.write(header)
                                out.write(sealed)
                                out.flush()
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                        }
                        else -> break
                    }
                }
                Thread.sleep(33)
            }
        } finally {
            runCatching { inputSurface.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    private fun drawSimulatedCarPlayDashboard(
        canvas: Canvas,
        width: Int,
        height: Int,
        tick: Int,
        mfiSubject: String,
    ) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                Color.rgb(14, 21, 38),
                Color.rgb(24, 38, 66),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val dockWidth = 104f
        val dockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 18, 22, 32)
        }
        canvas.drawRoundRect(
            RectF(16f, 16f, 16f + dockWidth, height - 16f),
            28f, 28f, dockPaint,
        )

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        canvas.drawText(timeText, 16f + dockWidth / 2f, 64f, textPaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 210, 160)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("5G · 100%", 16f + dockWidth / 2f, 92f, subPaint)

        // App icons on CarPlay Dock
        val dockApps = listOf(
            Triple("Mapas", Color.rgb(46, 134, 222), 160f),
            Triple("Música", Color.rgb(250, 58, 92), 255f),
            Triple("Teléfono", Color.rgb(40, 205, 65), 350f),
            Triple("Qonecta", Color.rgb(166, 200, 255), height - 105f),
        )
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val iconLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(210, 220, 238)
            textSize = 13f
            textAlign = Paint.Align.CENTER
        }
        for ((name, color, cy) in dockApps) {
            iconPaint.color = color
            val left = 16f + (dockWidth - 58f) / 2f
            canvas.drawRoundRect(RectF(left, cy, left + 58f, cy + 58f), 16f, 16f, iconPaint)
            canvas.drawText(name, 16f + dockWidth / 2f, cy + 76f, iconLabelPaint)
        }

        // Main Map Card (Left)
        val mapLeft = 140f
        val mapRight = width * 0.58f
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 25, 36, 56)
        }
        canvas.drawRoundRect(RectF(mapLeft, 20f, mapRight, height - 20f), 28f, 28f, cardPaint)

        // Animated route line inside Map Card
        val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(10, 132, 255)
            strokeWidth = 10f
            style = Paint.Style.STROKE
        }
        val mapCenterX = (mapLeft + mapRight) / 2f
        val mapCenterY = height * 0.58f
        canvas.drawLine(mapLeft + 40f, height - 60f, mapCenterX, mapCenterY, routePaint)
        canvas.drawLine(mapCenterX, mapCenterY, mapRight - 50f, 150f, routePaint)

        val pulseRadius = 14f + (tick % 30) * 0.6f
        val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 10, 132, 255)
        }
        canvas.drawCircle(mapCenterX, mapCenterY, pulseRadius, pulsePaint)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        canvas.drawCircle(mapCenterX, mapCenterY, 8f, dotPaint)

        // Navigation banner at top of Map Card
        val navBannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(28, 110, 72)
        }
        canvas.drawRoundRect(RectF(mapLeft + 20f, 38f, mapRight - 20f, 134f), 20f, 20f, navBannerPaint)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(205, 220, 240)
            textSize = 18f
        }
        canvas.drawText("350 m · Gira a la derecha", mapLeft + 40f, 78f, titlePaint)
        canvas.drawText("Leapmotor C10 · Ruta activa CarPlay", mapLeft + 40f, 112f, bodyPaint)

        // Right Column: Top Card (MFi & Protocol Status)
        val rightLeft = mapRight + 20f
        val rightRight = width - 20f
        val midY = height * 0.50f
        canvas.drawRoundRect(RectF(rightLeft, 20f, rightRight, midY - 10f), 28f, 28f, cardPaint)

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 205, 65)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        canvas.drawText("✓ CARPLAY SIMULADO EN VIVO (H.264)", rightLeft + 24f, 62f, badgePaint)
        canvas.drawText("iPhone 16 Pro · AirPlay 2", rightLeft + 24f, 98f, titlePaint)
        bodyPaint.textSize = 16f
        canvas.drawText("MFiSAP ECDSA: Verificado OK", rightLeft + 24f, 132f, bodyPaint)
        canvas.drawText("Cert: ${mfiSubject.take(24)}", rightLeft + 24f, 160f, bodyPaint)
        canvas.drawText("Cifrado: ChaCha20-Poly1305 (${width}x$height)", rightLeft + 24f, 188f, bodyPaint)

        // Right Column: Bottom Card (Now Playing)
        canvas.drawRoundRect(RectF(rightLeft, midY + 10f, rightRight, height - 20f), 28f, 28f, cardPaint)
        val musicHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(250, 58, 92)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        canvas.drawText("AHORA SONANDO · APPLE MUSIC", rightLeft + 24f, midY + 52f, musicHeaderPaint)
        canvas.drawText("Qonecta Unit Community Edition", rightLeft + 24f, midY + 90f, titlePaint)
        canvas.drawText("Sin bloqueo de VIN · Sin servidor externo", rightLeft + 24f, midY + 122f, bodyPaint)

        // Animated progress bar
        val barLeft = rightLeft + 24f
        val barRight = rightRight - 24f
        val barY = height - 56f
        val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(55, 72, 100)
        }
        val barFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(166, 200, 255)
        }
        canvas.drawRoundRect(RectF(barLeft, barY, barRight, barY + 10f), 5f, 5f, barBgPaint)
        val progress = ((tick % 180) / 180f).coerceIn(0.08f, 1f)
        canvas.drawRoundRect(
            RectF(barLeft, barY, barLeft + (barRight - barLeft) * progress, barY + 10f),
            5f, 5f, barFgPaint,
        )
    }

    private fun extractNaluWithoutStartCode(buf: ByteBuffer?): ByteArray {
        if (buf == null) return ByteArray(0)
        val dup = buf.duplicate()
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        return when {
            bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
                bytes[2] == 0.toByte() && bytes[3] == 1.toByte() ->
                bytes.copyOfRange(4, bytes.size)
            bytes.size >= 3 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
                bytes[2] == 1.toByte() ->
                bytes.copyOfRange(3, bytes.size)
            else -> bytes
        }
    }

    private fun buildAvcRecord(sps: ByteArray, pps: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(1) // configurationVersion
        out.write(sps.getOrElse(1) { 0x42 }.toInt() and 0xff)
        out.write(sps.getOrElse(2) { 0x00 }.toInt() and 0xff)
        out.write(sps.getOrElse(3) { 0x1f }.toInt() and 0xff)
        out.write(0xff) // lengthSizeMinusOne = 3
        out.write(0xe1) // numOfSequenceParameterSets = 1
        out.write((sps.size shr 8) and 0xff)
        out.write(sps.size and 0xff)
        out.write(sps)
        out.write(1) // numOfPictureParameterSets = 1
        out.write((pps.size shr 8) and 0xff)
        out.write(pps.size and 0xff)
        out.write(pps)
        return out.toByteArray()
    }

    private fun writeScreenPacket(out: java.io.OutputStream, opcode: Int, body: ByteArray) {
        val header = ByteArray(128)
        writeU32Le(header, 0, body.size)
        header[4] = opcode.toByte()
        out.write(header)
        out.write(body)
        out.flush()
    }

    private fun writeU32Le(target: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(target, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    private fun sendRtsp(
        output: java.io.OutputStream,
        method: String,
        uri: String,
        cseq: Int,
        contentType: String? = null,
        body: ByteArray = ByteArray(0),
    ) {
        val headers = buildString {
            append("$method $uri RTSP/1.0\r\n")
            append("CSeq: $cseq\r\n")
            if (contentType != null) {
                append("Content-Type: $contentType\r\n")
            }
            if (body.isNotEmpty()) {
                append("Content-Length: ${body.size}\r\n")
            }
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) {
            output.write(body)
        }
        output.flush()
    }

    companion object {
        private const val TAG = "CarPlaySimulator"
        const val EXTRA_SIMULATE_IPHONE = "simulate_iphone"
    }
}
