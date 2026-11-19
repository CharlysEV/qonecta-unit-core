package com.andrerinas.openheadunit.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Intentionally inert in Qonecta Unit Core: background Wi-Fi auto-start is disabled.
 */
class WifiAutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // No-op in Core mode.
    }

    companion object {
        fun checkAndStart(context: Context) {
            // No-op in Core mode.
        }
    }
}
