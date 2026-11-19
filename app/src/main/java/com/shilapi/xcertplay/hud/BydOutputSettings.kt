package com.shilapi.xcertplay.hud

import android.content.Context

/**
 * One user switch for BYD navigation output. On the tested car the windshield HUD mirrors what the
 * instrument cluster receives, so separate HUD/cluster switches cannot behave independently.
 */
object BydOutputSettings {
    private const val PREFS = "leapcarplay_byd_outputs"
    private const val KEY_ENABLED = "navigation_enabled"

    fun enabled(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

    fun setEnabled(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") enabled: Boolean) = Unit

    /** Disabled in Qonecta Unit Core (non-BYD head units such as Leapmotor C10). */
    fun available(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
