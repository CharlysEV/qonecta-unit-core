package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class AudioCodecKind { AAC_LC, OPUS, LPCM }

data class AudioFormat(
    val codec: AudioCodecKind,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val audioType: String = "media",
)

/**
 * Binds the RTP data and RTCP control UDP ports for one CarPlay audio stream.
 *
 * Wire layout follows LIVI `livi_audio_stream`: one RTP packet per datagram, a 12-byte header,
 * ciphertext, a 16-byte tag, then an 8-byte little-endian nonce. The header's last eight bytes
 * (timestamp + SSRC) are the AEAD associated data.
 *
 * For music (`streamType == 102`), enables a bounded 180 ms sequence reorder buffer paired with
 * standard AirPlay RTCP Retransmit Requests (`0x80 0xD5`) and Responses (`0x80 0xD6`) so short
 * 2–4 packet Wi-Fi drops are healed transparently without audible skips.
 */
class AudioStream(
    private val key: ByteArray,
    private val streamType: Int = -1,
    private val onDiagnostic: (String) -> Unit = {},
    initialPeerControlAddress: InetSocketAddress? = null,
) : Closeable {
    interface Listener {
        fun onStarted(firstSample: Int) {}
        fun onRtp(rtp: ByteArray, sample: Int) {}
        fun onPacket(
            wire: ByteArray,
            rtp: ByteArray?,
            sample: Int?,
            error: Throwable?,
        ) {}
    }

    private data class DecodedAudioPacket(
        val wire: ByteArray,
        val rtp: ByteArray,
        val sample: Int,
        val seq: Int,
    )

    private val closed = AtomicBoolean(false)
    private val receivedPackets = AtomicInteger()
    private val decryptedPackets = AtomicInteger()
    private val authenticationFailures = AtomicInteger()
    private val retransmitRequested = AtomicInteger()
    private val retransmitRecovered = AtomicInteger()
    private val retransmitExpired = AtomicInteger()
    private val peerControlAddress = AtomicReference<InetSocketAddress?>(initialPeerControlAddress)
    private val reorderLock = Any()
    private val reorderBuffer = if (streamType == STREAM_TYPE_MEDIA) AudioReorderBuffer<DecodedAudioPacket>() else null

    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var dataThread: Thread? = null
    private var controlThread: Thread? = null
    private var started = false

    fun listen(listener: Listener): Pair<Int, Int> {
        val data = bindAnyPort(timeoutMs = if (reorderBuffer != null) REORDER_POLL_TIMEOUT_MS else 0)
        val control = bindAnyPort(timeoutMs = 0)
        dataSocket = data
        controlSocket = control
        dataThread = Thread({ runData(data, listener) }, "airplay-audio-rx").apply {
            isDaemon = true
            start()
        }
        controlThread = Thread({ runControl(control, listener) }, "airplay-rtcp-rx").apply {
            isDaemon = true
            start()
        }
        return data.localPort to control.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dataSocket?.close()
        controlSocket?.close()
        dataThread?.interrupt()
        controlThread?.interrupt()
    }

    private fun runData(socket: DatagramSocket, listener: Listener) {
        raiseAudioThreadPriority()
        val stats = StreamReceiveStats("audio type=$streamType", onDiagnostic)
        val buffer = ByteArray(DATAGRAM_BYTES)
        try {
            while (!closed.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    stats.reading()
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    drainExpiredReordered(listener)
                    continue
                } catch (_: Exception) {
                    if (closed.get()) return else continue
                }
                val seq = if (packet.length >= 12) readU16Be(buffer, 2) else null
                stats.received(packet.length, seq)
                val wire = packet.data.copyOf(packet.length)
                handleWirePacket(wire, listener, isRetransmit = false)
                stats.processed()
            }
        } finally {
            stats.flush(ended = true)
            val req = retransmitRequested.get()
            val rec = retransmitRecovered.get()
            val exp = retransmitExpired.get()
            if (req > 0 || rec > 0 || exp > 0) {
                onDiagnostic("AudioStream: retransmit type=$streamType requested=$req recovered=$rec expired=$exp")
            }
        }
    }

    private fun runControl(socket: DatagramSocket, listener: Listener) {
        raiseAudioThreadPriority()
        val buffer = ByteArray(DATAGRAM_BYTES)
        while (!closed.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                if (closed.get()) return else continue
            }
            val sender = packet.socketAddress as? InetSocketAddress
            if (sender != null && sender.port > 0) {
                peerControlAddress.set(sender)
            }
            val retransmitWire = extractRetransmitRtp(buffer, packet.length)
            if (retransmitWire != null) {
                handleWirePacket(retransmitWire, listener, isRetransmit = true)
            }
        }
    }

    private fun handleWirePacket(wire: ByteArray, listener: Listener, isRetransmit: Boolean) {
        val packetNumber = receivedPackets.incrementAndGet()
        if (wire.size < RTP_HEADER_LEN + TAIL_LEN) {
            if (packetNumber == 1) {
                android.util.Log.w(
                    TAG,
                    "audio stream type=$streamType short packet bytes=${wire.size}",
                )
            }
            listener.onPacket(
                wire,
                null,
                null,
                IOException("audio packet shorter than RTP header plus tail"),
            )
            return
        }

        val aad = wire.copyOfRange(4, RTP_HEADER_LEN)
        val sealedEnd = wire.size - NONCE_LEN
        val sealed = wire.copyOfRange(RTP_HEADER_LEN, sealedEnd)
        val shortNonce = wire.copyOfRange(sealedEnd, wire.size)
        val nonce = ByteArray(12).also { shortNonce.copyInto(it, 4) }
        val seq = readU16Be(wire, 2)
        val sample = readU32Be(wire, 4)

        val payload = try {
            AirPlayCrypto.chachaOpen(key, nonce, sealed, aad)
        } catch (error: Exception) {
            val failureNumber = authenticationFailures.incrementAndGet()
            if (failureNumber == 1) {
                android.util.Log.w(
                    TAG,
                    "audio stream type=$streamType first decrypt failure wire=${wire.toHexString()}",
                    error,
                )
            }
            listener.onPacket(wire, null, sample, error)
            return
        }
        val rtp = wire.copyOf(RTP_HEADER_LEN) + payload
        val decryptedNumber = decryptedPackets.incrementAndGet()
        if (decryptedNumber <= FIRST_PACKET_LOG_COUNT) {
            android.util.Log.i(
                TAG,
                "audio stream type=$streamType packet=$decryptedNumber sample=$sample " +
                    "wireBytes=${wire.size} payloadBytes=${payload.size} " +
                    "payloadHead=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
            )
        } else if (decryptedNumber % PACKET_LOG_INTERVAL == 0) {
            android.util.Log.i(
                TAG,
                "audio stream type=$streamType decrypted=$decryptedNumber " +
                    "authFailures=${authenticationFailures.get()} " +
                    "retransReq=${retransmitRequested.get()} retransRx=${retransmitRecovered.get()}",
            )
        }

        val decoded = DecodedAudioPacket(wire, rtp, sample, seq)
        val reorder = reorderBuffer
        if (reorder == null) {
            deliverPacket(decoded, listener)
            return
        }

        val res = synchronized(reorderLock) {
            reorder.insert(seq, decoded, System.nanoTime() / 1_000_000L, isRetransmit = isRetransmit)
        }
        if (res.recoveredFromRetransmit) retransmitRecovered.incrementAndGet()
        if (res.expiredMissing > 0) retransmitExpired.addAndGet(res.expiredMissing)
        res.retransmit?.let { sendRetransmitRequest(it.firstSeq, it.count) }
        for (item in res.ready) {
            deliverPacket(item, listener)
        }
    }

    private fun drainExpiredReordered(listener: Listener) {
        val reorder = reorderBuffer ?: return
        val res = synchronized(reorderLock) {
            reorder.drainExpired(System.nanoTime() / 1_000_000L)
        }
        if (res.expiredMissing > 0) retransmitExpired.addAndGet(res.expiredMissing)
        res.retransmit?.let { sendRetransmitRequest(it.firstSeq, it.count) }
        for (item in res.ready) {
            deliverPacket(item, listener)
        }
    }

    private fun deliverPacket(decoded: DecodedAudioPacket, listener: Listener) {
        synchronized(reorderLock) {
            listener.onPacket(decoded.wire, decoded.rtp, decoded.sample, null)
            if (!started) {
                started = true
                listener.onStarted(decoded.sample)
            }
            listener.onRtp(decoded.rtp, decoded.sample)
        }
    }

    private fun sendRetransmitRequest(firstSeq: Int, count: Int) {
        val socket = controlSocket ?: return
        val peer = peerControlAddress.get() ?: return
        if (closed.get() || count <= 0) return
        val packetBytes = buildRetransmitRequestPacket(firstSeq, count)
        runCatching {
            socket.send(DatagramPacket(packetBytes, packetBytes.size, peer))
            retransmitRequested.addAndGet(count)
        }
    }

    private fun bindAnyPort(timeoutMs: Int): DatagramSocket {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        runCatching { socket.receiveBufferSize = SOCKET_RCVBUF_BYTES }
        runCatching { socket.trafficClass = DSCP_EF_AUDIO }
        if (timeoutMs > 0) {
            runCatching { socket.soTimeout = timeoutMs }
        }
        socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        return socket
    }

    private fun raiseAudioThreadPriority() {
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        }
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        private const val TAG = "xcertplay-usb"
        private const val STREAM_TYPE_MEDIA = 102
        private const val DATAGRAM_BYTES = 4_096
        private const val SOCKET_RCVBUF_BYTES = 1_048_576
        private const val DSCP_EF_AUDIO = 0xB8
        private const val REORDER_POLL_TIMEOUT_MS = 25
        private const val RTP_HEADER_LEN = 12
        private const val TAG_LEN = 16
        private const val NONCE_LEN = 8
        private const val TAIL_LEN = TAG_LEN + NONCE_LEN
        private const val FIRST_PACKET_LOG_COUNT = 3
        private const val PACKET_LOG_INTERVAL = 100
        private const val RTCP_PT_RETRANSMIT_REQUEST = 0x55 // 0xD5 with marker bit
        private const val RTCP_PT_RETRANSMIT_RESPONSE = 0x56 // 0xD6 with marker bit

        /** Builds an 8-byte AirPlay RTCP Retransmit Request (`0x80 0xD5`). */
        fun buildRetransmitRequestPacket(firstSeq: Int, count: Int): ByteArray = byteArrayOf(
            0x80.toByte(),
            (0x80 or RTCP_PT_RETRANSMIT_REQUEST).toByte(),
            0x00,
            0x01,
            ((firstSeq ushr 8) and 0xFF).toByte(),
            (firstSeq and 0xFF).toByte(),
            ((count ushr 8) and 0xFF).toByte(),
            (count and 0xFF).toByte(),
        )

        /** Extracts the wrapped encrypted RTP packet from an AirPlay RTCP `0x80 0xD6` response. */
        fun extractRetransmitRtp(buffer: ByteArray, length: Int): ByteArray? {
            if (length < 4 + RTP_HEADER_LEN + TAIL_LEN) return null
            val pt = buffer[1].toInt() and 0x7F
            if (pt != RTCP_PT_RETRANSMIT_RESPONSE) return null
            return buffer.copyOfRange(4, length)
        }
    }
}

/** Maps the phone's negotiated audioFormat bits to a decode/render format. */
object AudioStreamCodec {
    fun fromFormatBits(bits: Long, payloadType: Int, audioType: String = "media"): AudioFormat {
        val isAacLc = (bits and (AAC_LC_44K_STEREO or AAC_LC_48K_STEREO)) != 0L
        val isOpus = (bits and OPUS_MONO) != 0L
        val pcm = PCM_FORMAT[bits]
        return when {
            isOpus -> AudioFormat(AudioCodecKind.OPUS, 48_000, 1, payloadType, audioType)
            isAacLc -> AudioFormat(
                AudioCodecKind.AAC_LC,
                if ((bits and AAC_LC_48K_STEREO) != 0L) 48_000 else 44_100,
                2,
                payloadType,
                audioType,
            )
            pcm != null -> AudioFormat(AudioCodecKind.LPCM, pcm.first, pcm.second, payloadType, audioType)
            else -> AudioFormat(AudioCodecKind.LPCM, 44_100, 2, payloadType, audioType)
        }
    }

    private const val AAC_LC_44K_STEREO = 0x400000L
    private const val AAC_LC_48K_STEREO = 0x800000L
    private const val OPUS_MONO = 0x10000000L or 0x20000000L or 0x40000000L

    private val PCM_FORMAT = mapOf(
        0x4L to (8_000 to 1),
        0x8L to (8_000 to 2),
        0x10L to (16_000 to 1),
        0x20L to (16_000 to 2),
        0x40L to (24_000 to 1),
        0x80L to (24_000 to 2),
        0x100L to (32_000 to 1),
        0x200L to (32_000 to 2),
        0x400L to (44_100 to 1),
        0x800L to (44_100 to 2),
        0x4000L to (48_000 to 1),
        0x8000L to (48_000 to 2),
    )
}
