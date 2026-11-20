package com.andrerinas.openheadunit.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Intentionally inert in Qonecta Unit Core: boot auto-start is disabled and unregistered
 * from AndroidManifest.xml so the app never wakes during car boot or crash recovery.
 */
class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // No-op in Core mode.
    }

    companion object {
        const val EXTRA_BOOT_START = "com.andrerinas.openheadunit.EXTRA_BOOT_START"
    }
}
