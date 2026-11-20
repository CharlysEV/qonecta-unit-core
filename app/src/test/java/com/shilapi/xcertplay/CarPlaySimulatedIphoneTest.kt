package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayCrypto
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.RtspMessage
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end PC simulation of an iPhone connecting to Qonecta Unit's CarPlay receiver:
 * 1. Loads the real bundled offline-mfi assets (`certificate.p7b` + `identity.pk8`).
 * 2. Simulates an iPhone performing RTSP `/info`, `/auth-setup` (MFiSAP X25519 + AES-128-CTR + ECDSA),
 *    session `SETUP`, screen stream `110` `SETUP`, and `RECORD` over a real TCP socket.
 */
class CarPlaySimulatedIphoneTest {

    private fun resolveAssetsOfflineMfiDir(): File {
        val candidates = listOf(
            File("src/main/assets/offline-mfi"),
            File("app/src/main/assets/offline-mfi"),
            File("d:/unit/app/src/main/assets/offline-mfi")
        )
        return candidates.firstOrNull { File(it, "certificate.p7b").isFile && File(it, "identity.pk8").isFile }
            ?: error("Could not find offline-mfi assets directory in ${candidates.map { it.absolutePath }}")
    }

    @Test
    fun bundledOfflineMfiAssetsLoadAndPassIphoneMfiSapVerification() {
        val mfiDir = resolveAssetsOfflineMfiDir()
        val mfiClient = LocalMfiAuthenticationClient.load(mfiDir)

        val certBytes = mfiClient.readCertificate()
        assertTrue("Certificate bytes must not be empty", certBytes.isNotEmpty())

        val x509 = CertificateFactory.getInstance("X.509")
            .generateCertificates(certBytes.inputStream())
            .first() as X509Certificate

        println("=== Bundled MFi Certificate Loaded ===")
        println("Subject: ${x509.subjectX500Principal.name}")
        println("Issuer : ${x509.issuerX500Principal.name}")
        println("Serial : ${x509.serialNumber.toString(16)}")
        println("Protocol Major: ${mfiClient.protocolMajor()}")

        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val deviceInfoRef = AtomicReference<AirPlayDeviceInfo>()
        val sessionActiveLatch = CountDownLatch(1)
        val screenStreamRequested = AtomicBoolean(false)

        val acceptThread = Thread {
            val peer = serverSocket.accept()
            val session = AirPlaySession(
                socket = peer,
                config = AirPlayConfig(
                    deviceName = "Qonecta Unit C10",
                    deviceId = "02:11:22:33:44:55",
                    btMac = "AA:BB:CC:11:22:33",
                    sourceVersion = "366.0",
                    main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720, fps = 60),
                ),
                identity = AirPlayIdentity.generate(),
                pairings = PairingStore(),
                mfi = mfiClient,
                listener = object : AirPlaySessionListener {
                    override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
                        deviceInfoRef.set(info)
                    }

                    override fun onSessionActive(session: AirPlaySession) {
                        sessionActiveLatch.countDown()
                    }
                },
                media = object : AirPlayMediaHandler {
                    override fun onScreen(
                        session: AirPlaySession,
                        type: Int,
                        stream: Map<String, Any?>
                    ): Int {
                        if (type == 110) {
                            screenStreamRequested.set(true)
                        }
                        return 49155
                    }
                }
            )
            session.start()
        }.apply { isDaemon = true; start() }

        Socket("127.0.0.1", serverSocket.localPort).use { iphoneSocket ->
            iphoneSocket.soTimeout = 5000
            val out = iphoneSocket.getOutputStream()
            val inp = iphoneSocket.getInputStream()

            // Step 1: Simulated iPhone requests GET /info
            val infoReq = "GET /info RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: AirPlay/745.8.1\r\n\r\n"
            out.write(infoReq.toByteArray(Charsets.US_ASCII))
            out.flush()

            val infoResp = readRtspResponse(inp)
            assertEquals(200, infoResp.status)
            @Suppress("UNCHECKED_CAST")
            val infoMap = BplistCodec.decode(infoResp.body) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val displays = infoMap["displays"] as List<Map<String, Any?>>
            assertEquals(1280L, displays.first()["widthPixels"])
            assertEquals(720L, displays.first()["heightPixels"])
            println("Step 1 OK: /info returned display 1280x720@60fps and audio formats")

            // Step 2: Simulated iPhone sends POST /auth-setup (MFiSAP v1 + 32-byte X25519 pubkey)
            val iphoneKeyPair = AirPlayCrypto.x25519Generate()
            val authBody = ByteArray(33).apply {
                this[0] = 0x01
                iphoneKeyPair.publicKey.copyInto(this, 1)
            }
            val authHead = "POST /auth-setup RTSP/1.0\r\n" +
                "CSeq: 2\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: ${authBody.size}\r\n\r\n"
            out.write(authHead.toByteArray(Charsets.US_ASCII) + authBody)
            out.flush()

            val authResp = readRtspResponse(inp)
            assertEquals(200, authResp.status)
            val respBytes = authResp.body
            assertTrue("MFi response must contain key + cert + sig", respBytes.size > 40)

            val accessoryPubKey = respBytes.copyOfRange(0, 32)
            val certLen = ByteBuffer.wrap(respBytes, 32, 4).int
            val returnedCert = respBytes.copyOfRange(36, 36 + certLen)
            assertArrayEquals(certBytes, returnedCert)
            val sigLen = ByteBuffer.wrap(respBytes, 36 + certLen, 4).int
            val encryptedSig = respBytes.copyOfRange(40 + certLen, 40 + certLen + sigLen)
            assertEquals(64, encryptedSig.size)

            // Simulated iPhone derives X25519 shared secret and decrypts AES-128-CTR signature
            val shared = AirPlayCrypto.x25519Shared(iphoneKeyPair.privateKey, accessoryPubKey)
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
                update(accessoryPubKey)
                update(iphoneKeyPair.publicKey)
            }.digest()

            val derSig = DERSequence(
                arrayOf(
                    ASN1Integer(BigInteger(1, rawSig.copyOfRange(0, 32))),
                    ASN1Integer(BigInteger(1, rawSig.copyOfRange(32, 64))),
                )
            ).encoded
            val verifier = Signature.getInstance("NONEwithECDSA")
            verifier.initVerify(x509.publicKey)
            verifier.update(challengeDigest)
            assertTrue("iPhone ECDSA verification of MFi challenge must pass", verifier.verify(derSig))
            println("Step 2 OK: /auth-setup MFiSAP challenge signed and verified by simulated iPhone!")

            // Step 3: Simulated iPhone sends initial SETUP with iPhone metadata
            val setup1Body = BplistCodec.encode(
                linkedMapOf(
                    "name" to "iPhone 16 Pro (Simulado)",
                    "deviceID" to "AA:BB:CC:DD:EE:FF",
                    "macAddress" to "11:22:33:44:55:66",
                    "model" to "iPhone16,2",
                    "osVersion" to "18.2",
                    "sourceVersion" to "745.8.1",
                    "timingPort" to 55123L,
                )
            )
            val setup1Head = "SETUP rtsp://127.0.0.1/session RTSP/1.0\r\n" +
                "CSeq: 3\r\n" +
                "Content-Type: application/x-apple-binary-plist\r\n" +
                "Content-Length: ${setup1Body.size}\r\n\r\n"
            out.write(setup1Head.toByteArray(Charsets.US_ASCII) + setup1Body)
            out.flush()

            val setup1Resp = readRtspResponse(inp)
            assertEquals(200, setup1Resp.status)
            assertNotNull(deviceInfoRef.get())
            assertEquals("iPhone16,2", deviceInfoRef.get()?.model)
            println("Step 3 OK: Session SETUP completed for ${deviceInfoRef.get()?.name} (${deviceInfoRef.get()?.model})")

            // Step 4: Simulated iPhone sends stream SETUP for Screen Stream (type 110)
            val setup2Body = BplistCodec.encode(
                linkedMapOf(
                    "streams" to listOf(
                        linkedMapOf(
                            "type" to 110L,
                            "streamConnectionID" to 123456789L,
                        )
                    )
                )
            )
            val setup2Head = "SETUP rtsp://127.0.0.1/stream RTSP/1.0\r\n" +
                "CSeq: 4\r\n" +
                "Content-Type: application/x-apple-binary-plist\r\n" +
                "Content-Length: ${setup2Body.size}\r\n\r\n"
            out.write(setup2Head.toByteArray(Charsets.US_ASCII) + setup2Body)
            out.flush()

            val setup2Resp = readRtspResponse(inp)
            assertEquals(200, setup2Resp.status)
            assertTrue("Screen stream 110 must be activated", screenStreamRequested.get())
            @Suppress("UNCHECKED_CAST")
            val setup2Map = BplistCodec.decode(setup2Resp.body) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val respStreams = setup2Map["streams"] as List<Map<String, Any?>>
            assertEquals(49155L, respStreams.first()["dataPort"])
            println("Step 4 OK: Screen stream 110 negotiated on dataPort=${respStreams.first()["dataPort"]}")

            // Step 5: Simulated iPhone sends RECORD to start projection
            val recordReq = "RECORD rtsp://127.0.0.1/session RTSP/1.0\r\nCSeq: 5\r\n\r\n"
            out.write(recordReq.toByteArray(Charsets.US_ASCII))
            out.flush()

            val recordResp = readRtspResponse(inp)
            assertEquals(200, recordResp.status)
            assertTrue("Session must become active on RECORD", sessionActiveLatch.await(2, TimeUnit.SECONDS))
            println("Step 5 OK: RECORD accepted and CarPlay session is ACTIVE!")
        }

        serverSocket.close()
        acceptThread.join(1000)
    }

    private data class ParsedRtspResponse(val status: Int, val body: ByteArray)

    private fun readRtspResponse(inp: java.io.InputStream): ParsedRtspResponse {
        val headerBuf = java.io.ByteArrayOutputStream()
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
}
