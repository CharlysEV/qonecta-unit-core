package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AudioReorderBuffer
import com.shilapi.xcertplay.airplay.AudioStream
import com.shilapi.xcertplay.media.MediaAudioBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayAudioStabilizationTest {

    @Test
    fun mediaAudioBufferUses700msDefaultAndHeadroomForWirelessBursts() {
        assertEquals(700, MediaAudioBuffer.DEFAULT_MILLIS)
        assertEquals(listOf(500, 700, 1000), MediaAudioBuffer.presets)
        // Legacy 300 ms users migrate cleanly to 700 ms default.
        assertEquals(700, MediaAudioBuffer.sanitize(300))
        assertEquals(1000, MediaAudioBuffer.sanitize(1000))

        // 44.1 kHz stereo 16-bit PCM = 176,400 bytes/s -> 700 ms start + 700 ms headroom = 1400 ms track
        val plan = MediaAudioBuffer.plan("media", 44_100, 2, 4_096, 700)
        assertEquals((176_400L * 700 / 1000).toInt(), plan.startBytes)
        assertEquals((176_400L * 1400 / 1000).toInt(), plan.trackBufferBytes)
    }

    @Test
    fun audioReorderBufferDeliversInOrderImmediatelyAndHealsGapWithRetransmit() {
        val buf = AudioReorderBuffer<String>(maxHoldMs = 180L, retryAfterMs = 65L)

        val r0 = buf.insert(10, "p10", nowMs = 1_000L)
        assertEquals(listOf("p10"), r0.ready)
        assertNull(r0.retransmit)

        // Packet 11 & 12 lost; 13 & 14 arrive -> hold and request firstSeq=11 count=2.
        val r3 = buf.insert(13, "p13", nowMs = 1_023L)
        assertTrue(r3.ready.isEmpty())
        assertEquals(AudioReorderBuffer.RetransmitRequest(11, 2), r3.retransmit)

        val r4 = buf.insert(14, "p14", nowMs = 1_046L)
        assertTrue(r4.ready.isEmpty())
        assertNull(r4.retransmit) // already requested 11..12 within retryAfterMs

        // Duplicate 10 ignored cleanly
        assertTrue(buf.insert(10, "dup10", nowMs = 1_050L).ready.isEmpty())

        // Retransmit recovers p12 first (buffered, still waiting on p11), then p11 completes the sequence.
        val r2Rec = buf.insert(12, "p12", nowMs = 1_070L, isRetransmit = true)
        assertTrue(r2Rec.ready.isEmpty())
        assertTrue(r2Rec.recoveredFromRetransmit)

        val r1Rec = buf.insert(11, "p11", nowMs = 1_075L, isRetransmit = true)
        assertEquals(listOf("p11", "p12", "p13", "p14"), r1Rec.ready)
        assertTrue(r1Rec.recoveredFromRetransmit)
    }

    @Test
    fun audioReorderBufferHandles16BitWrapAndBoundedExpiry() {
        val buf = AudioReorderBuffer<String>(maxHoldMs = 180L, retryAfterMs = 65L)
        assertEquals(listOf("a"), buf.insert(65534, "a", nowMs = 100L).ready)

        // Skip 65535 & 0, receive 1 across wrap boundary
        val gap = buf.insert(1, "d", nowMs = 120L)
        assertTrue(gap.ready.isEmpty())
        assertEquals(AudioReorderBuffer.RetransmitRequest(65535, 2), gap.retransmit)

        // Retries once after retryAfterMs
        val retry = buf.drainExpired(nowMs = 190L)
        assertTrue(retry.ready.isEmpty())
        assertEquals(AudioReorderBuffer.RetransmitRequest(65535, 2), retry.retransmit)

        // Bounded expiry at 180 ms: releases buffered packet without stalling forever
        val expired = buf.drainExpired(nowMs = 305L)
        assertEquals(listOf("d"), expired.ready)
        assertEquals(2, expired.expiredMissing)
        assertFalse(expired.recoveredFromRetransmit)
    }

    @Test
    fun buildsAndParsesAirPlayRtcpRetransmitPackets() {
        val req = AudioStream.buildRetransmitRequestPacket(firstSeq = 0x1234, count = 3)
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0xD5.toByte(), 0x00, 0x01, 0x12, 0x34, 0x00, 0x03),
            req,
        )

        val wrappedRtp = ByteArray(4 + 12 + 16 + 8 + 10) { idx -> idx.toByte() }
        wrappedRtp[0] = 0x80.toByte()
        wrappedRtp[1] = 0xD6.toByte()
        val unwrapped = AudioStream.extractRetransmitRtp(wrappedRtp, wrappedRtp.size)
        assertNotNull(unwrapped)
        assertEquals(wrappedRtp.size - 4, unwrapped!!.size)

        // Wrong PT or short packet rejected cleanly
        val wrongPt = wrappedRtp.copyOf().apply { this[1] = 0xD5.toByte() }
        assertNull(AudioStream.extractRetransmitRtp(wrongPt, wrongPt.size))
        assertNull(AudioStream.extractRetransmitRtp(wrappedRtp, 20))
    }
}
