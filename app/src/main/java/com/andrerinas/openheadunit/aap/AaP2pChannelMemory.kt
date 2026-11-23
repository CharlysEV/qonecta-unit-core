package com.andrerinas.openheadunit.aap

import android.content.Context

/**
 * Persistent memory and 5 GHz non-DFS channel selector to accelerate Wi-Fi Direct (Native AA)
 * bring-up across Android 10+ automotive head units.
 *
 * Strategy:
 * - Avoids slow driver band scans and DFS channels (52–144) that stall or drop connections.
 * - If the head unit is associated to a 5 GHz non-DFS Wi-Fi network, aligns P2P to the same
 *   channel (zero radio hopping).
 * - Otherwise tries the last confirmed 5 GHz non-DFS channel on this vehicle, then 5180 MHz
 *   (ch 36, UNII-1), then 5745 MHz (ch 149, UNII-3), and finally falls back to generic 5 GHz band.
 * - Any remembered channel that fails createGroup() is immediately forgotten.
 */
object AaP2pChannelMemory {

    private const val PREFS = "qu_aa_p2p_channel_memory"
    private const val KEY_FREQ_MHZ = "confirmed_freq_mhz"

    /** Primary 20 MHz 5 GHz non-DFS channels (UNII-1 + UNII-3), widely accepted by Android & iOS. */
    val NON_DFS_5GHZ_FREQS: Set<Int> = setOf(
        5180, 5200, 5220, 5240, // ch 36, 40, 44, 48
        5745, 5765, 5785, 5805, 5825, // ch 149, 153, 157, 161, 165
    )

    fun isValidNonDfs5Ghz(freqMhz: Int): Boolean = freqMhz in NON_DFS_5GHZ_FREQS

    fun channelNumberFor(freqMhz: Int): Int = when {
        freqMhz in 2412..2472 && (freqMhz - 2412) % 5 == 0 -> (freqMhz - 2407) / 5
        freqMhz == 2484 -> 14
        freqMhz in 5180..5885 -> (freqMhz - 5000) / 5
        else -> 0
    }

    fun formatBandAndChannel(freqMhz: Int): String {
        if (freqMhz <= 0) return ""
        val band = if (freqMhz >= 4900) "5 GHz" else "2,4 GHz"
        val ch = channelNumberFor(freqMhz)
        return if (ch > 0) "$band · ch $ch ($freqMhz MHz)" else "$band ($freqMhz MHz)"
    }

    fun chooseFrequencyMhz(
        stationAlignedMhz: Int,
        rememberedMhz: Int,
        force24: Boolean,
        retryCount: Int,
        recreateCount: Int,
    ): Int {
        if (force24 || recreateCount >= 2) return 0
        if (retryCount == 0) {
            if (isValidNonDfs5Ghz(stationAlignedMhz)) return stationAlignedMhz
            if (isValidNonDfs5Ghz(rememberedMhz)) return rememberedMhz
            return 5180 // Channel 36 (UNII-1, non-DFS)
        }
        if (retryCount == 1) {
            return if (rememberedMhz == 5745) 5180 else 5745
        }
        return 0
    }

    fun readRemembered(context: Context): Int {
        return runCatching {
            val f = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_FREQ_MHZ, 0)
            if (isValidNonDfs5Ghz(f)) f else 0
        }.getOrDefault(0)
    }

    fun remember(context: Context, freqMhz: Int) {
        if (!isValidNonDfs5Ghz(freqMhz)) return
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_FREQ_MHZ, freqMhz).apply()
        }
    }

    fun forget(context: Context) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_FREQ_MHZ).apply()
        }
    }
}
