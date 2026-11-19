package com.andrerinas.openheadunit.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Intentionally inert in Qonecta Unit Core: background Bluetooth auto-start is disabled
 * and unregistered from AndroidManifest.xml so the app never wakes on boot or idle ACL events.
 */
class AutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // No-op in Core mode.
    }
}
