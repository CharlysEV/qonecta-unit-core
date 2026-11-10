package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media

/**
 * The bytes of one microphone message, and the one sender that has to obey AAP message framing.
 *
 * A message with flag bit 0 set (0x0b) carries a 2-byte type at payload offset 0 (byte 4),
 * followed by an 8-byte microsecond timestamp at offset 2 (byte 6..13) and raw PCM at offset 10 (byte 14).
 */
object MicUplinkFrame {

    /** Channel, flags and the 2-byte length the encrypt step rewrites. Plaintext on the wire. */
    const val HEADER_SIZE = 4

    /** First byte the phone decrypts. [AapTransport.sendEncryptedMessage] encrypts from here. */
    const val TYPE_OFFSET = HEADER_SIZE

    const val TYPE_SIZE = 2

    const val TIMESTAMP_OFFSET = TYPE_OFFSET + TYPE_SIZE

    const val TIMESTAMP_SIZE = 8

    const val PCM_OFFSET = TIMESTAMP_OFFSET + TIMESTAMP_SIZE

    /** Complete, unfragmented, encrypted. Bit 0 is what obliges us to write a type. */
    const val FLAGS: Byte = 0x0b

    /** Standard 2048 mono 16-bit samples = 4096 bytes per chunk (128 ms at 16 kHz). */
    const val CHUNK_BYTES = 4096

    fun size(pcmLength: Int): Int = PCM_OFFSET + pcmLength

    fun build(
        timestampUs: Long,
        pcm: ByteArray,
        pcmOffset: Int,
        pcmLength: Int,
        into: ByteArray
    ): Int {
        val length = size(pcmLength)
        require(into.size >= length) { "buffer holds ${into.size}, frame needs $length" }

        into[0] = Channel.ID_MIC.toByte()
        into[1] = FLAGS
        into[2] = 0
        into[3] = 0

        val type = Media.MsgType.MEDIA_MESSAGE_DATA_VALUE
        into[TYPE_OFFSET] = (type shr 8).toByte()
        into[TYPE_OFFSET + 1] = (type and 0xFF).toByte()

        var remaining = timestampUs
        for (i in TIMESTAMP_SIZE - 1 downTo 0) {
            into[TIMESTAMP_OFFSET + i] = (remaining and 0xFF).toByte()
            remaining = remaining shr 8
        }

        System.arraycopy(pcm, pcmOffset, into, PCM_OFFSET, pcmLength)
        return length
    }
}
