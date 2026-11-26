package com.shilapi.xcertplay.airplay

/**
 * Bounded 16-bit RTP sequence reassembler for wireless CarPlay media audio (`type=102`).
 *
 * - In-order packets are delivered immediately with 0 ms added latency.
 * - When a short forward gap (`1..maxGap`) is observed, future packets are held for up to
 *   [maxHoldMs] while an AirPlay RTCP Retransmit Request (`0x80 0xD5`) recovers the missing
 *   packets from the iPhone. Because music playback already keeps 500–1000 ms prebuffered in
 *   `AudioTrack`, a 180 ms recovery window heals 2–4 packet Wi-Fi drops cleanly without underruns.
 * - Duplicates and late packets behind `expectedSeq` are discarded safely.
 */
internal class AudioReorderBuffer<T>(
    private val maxHoldMs: Long = DEFAULT_MAX_HOLD_MS,
    private val retryAfterMs: Long = DEFAULT_RETRY_AFTER_MS,
    private val maxGap: Int = DEFAULT_MAX_GAP,
    private val maxBuffered: Int = DEFAULT_MAX_BUFFERED,
) {
    data class RetransmitRequest(val firstSeq: Int, val count: Int)

    data class Result<T>(
        val ready: List<T>,
        val retransmit: RetransmitRequest? = null,
        val recoveredFromRetransmit: Boolean = false,
        val expiredMissing: Int = 0,
    )

    private var initialized = false
    private var expectedSeq = 0
    private var gapWaitSinceMs = 0L
    private var lastRetransmitSentMs = 0L
    private val pending = HashMap<Int, T>()
    private val requestedSeqs = HashSet<Int>()

    fun insert(seqRaw: Int, item: T, nowMs: Long, isRetransmit: Boolean = false): Result<T> {
        val seq = seqRaw and 0xFFFF
        if (!initialized) {
            initialized = true
            expectedSeq = (seq + 1) and 0xFFFF
            return Result(ready = listOf(item))
        }

        val delta = seqDiff(seq, expectedSeq)
        if (delta < 0) {
            // Late or duplicate packet already played/skipped.
            return Result(ready = emptyList())
        }

        if (delta == 0) {
            val out = ArrayList<T>(pending.size + 1)
            out.add(item)
            requestedSeqs.remove(seq)
            expectedSeq = (expectedSeq + 1) and 0xFFFF
            drainContiguous(out)
            if (pending.isEmpty()) {
                gapWaitSinceMs = 0L
                lastRetransmitSentMs = 0L
                requestedSeqs.clear()
            } else {
                // Still a later gap ahead; reset timer for the new head gap.
                gapWaitSinceMs = nowMs
                lastRetransmitSentMs = 0L
            }
            return Result(ready = out, recoveredFromRetransmit = isRetransmit)
        }

        if (delta > maxGap) {
            // Large discontinuity (seek / stream reset): flush buffered packets in order and resync.
            val out = flushAllInOrder()
            out.add(item)
            expectedSeq = (seq + 1) and 0xFFFF
            gapWaitSinceMs = 0L
            lastRetransmitSentMs = 0L
            requestedSeqs.clear()
            return Result(ready = out)
        }

        // Future packet within reorder window (1..maxGap): buffer it and ask for missing seqs.
        val isNewRecovery = isRetransmit && !pending.containsKey(seq)
        pending[seq] = item
        requestedSeqs.remove(seq)
        if (gapWaitSinceMs == 0L) gapWaitSinceMs = nowMs

        var expired = 0
        val out = ArrayList<T>()
        while (pending.size > maxBuffered) {
            expired += advanceToNextBuffered(out, nowMs)
        }

        val req = nextRetransmitRequest(nowMs)
        return Result(
            ready = out,
            retransmit = req,
            recoveredFromRetransmit = isNewRecovery,
            expiredMissing = expired,
        )
    }

    fun drainExpired(nowMs: Long): Result<T> {
        if (!initialized || pending.isEmpty()) return Result(ready = emptyList())
        if (gapWaitSinceMs > 0L && (nowMs - gapWaitSinceMs) >= maxHoldMs) {
            val out = ArrayList<T>()
            val expired = advanceToNextBuffered(out, nowMs)
            val req = nextRetransmitRequest(nowMs)
            return Result(ready = out, retransmit = req, expiredMissing = expired)
        }
        val req = nextRetransmitRequest(nowMs)
        return Result(ready = emptyList(), retransmit = req)
    }

    private fun drainContiguous(out: MutableList<T>) {
        while (true) {
            val next = pending.remove(expectedSeq) ?: break
            requestedSeqs.remove(expectedSeq)
            out.add(next)
            expectedSeq = (expectedSeq + 1) and 0xFFFF
        }
    }

    private fun advanceToNextBuffered(out: MutableList<T>, nowMs: Long): Int {
        if (pending.isEmpty()) return 0
        var minDelta = Int.MAX_VALUE
        var minSeq = expectedSeq
        for (k in pending.keys) {
            val d = seqDiff(k, expectedSeq)
            if (d in 0 until minDelta) {
                minDelta = d
                minSeq = k
            }
        }
        if (minDelta == Int.MAX_VALUE) {
            pending.clear()
            requestedSeqs.clear()
            gapWaitSinceMs = 0L
            lastRetransmitSentMs = 0L
            return 0
        }
        val skipped = minDelta
        for (i in 0 until skipped) {
            requestedSeqs.remove((expectedSeq + i) and 0xFFFF)
        }
        expectedSeq = minSeq
        drainContiguous(out)
        if (pending.isEmpty()) {
            gapWaitSinceMs = 0L
            lastRetransmitSentMs = 0L
            requestedSeqs.clear()
        } else {
            gapWaitSinceMs = nowMs
            lastRetransmitSentMs = 0L
        }
        return skipped
    }

    private fun flushAllInOrder(): MutableList<T> {
        if (pending.isEmpty()) return ArrayList()
        val ordered = pending.entries
            .sortedBy { seqDiff(it.key, expectedSeq) }
            .map { it.value }
            .toMutableList()
        pending.clear()
        return ordered
    }

    private fun nextRetransmitRequest(nowMs: Long): RetransmitRequest? {
        if (pending.isEmpty()) return null
        // Find contiguous missing range starting at expectedSeq up to highest buffered seq.
        var highestDelta = 0
        for (k in pending.keys) {
            val d = seqDiff(k, expectedSeq)
            if (d > highestDelta) highestDelta = d
        }
        if (highestDelta <= 0) return null

        var missingCount = 0
        var hasUnrequested = false
        for (offset in 0 until highestDelta) {
            val s = (expectedSeq + offset) and 0xFFFF
            if (pending.containsKey(s)) break
            missingCount++
            if (s !in requestedSeqs) hasUnrequested = true
        }
        if (missingCount <= 0) return null

        val shouldRetry = lastRetransmitSentMs > 0L && (nowMs - lastRetransmitSentMs) >= retryAfterMs
        if (!hasUnrequested && !shouldRetry) return null

        for (offset in 0 until missingCount) {
            requestedSeqs.add((expectedSeq + offset) and 0xFFFF)
        }
        lastRetransmitSentMs = nowMs
        return RetransmitRequest(firstSeq = expectedSeq, count = missingCount)
    }

    companion object {
        const val DEFAULT_MAX_HOLD_MS = 180L
        const val DEFAULT_RETRY_AFTER_MS = 65L
        const val DEFAULT_MAX_GAP = 16
        const val DEFAULT_MAX_BUFFERED = 32

        /** Signed 16-bit modular sequence difference (a - b) in [-32768, 32767]. */
        fun seqDiff(a: Int, b: Int): Int {
            var d = (a - b) and 0xFFFF
            if (d >= 0x8000) d -= 0x10000
            return d
        }
    }
}
