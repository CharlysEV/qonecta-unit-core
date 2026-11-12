package com.andrerinas.openheadunit.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import android.os.UserManager
import android.os.Build

class AutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val targetMacs = Settings.getAutoStartBtMacs(context)

        val isLocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                      !(context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked

        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            AppLog.i("BT Device connected: ${device?.name} (${device?.address})")
            val settingsObj = runCatching { if (!isLocked) Settings(context) else null }.getOrNull()
            if (settingsObj?.btCompanionEnabled != false) {
                runCatching {
                    com.headqlink.unit.bridgehost.CompanionBridgeHost.start(context.applicationContext)
                    com.headqlink.unit.bridgehost.CompanionBridgeHost.wakeNow()
                    com.headqlink.unit.bridgehost.CompanionBridgeHost.pushImmediateSync()
                }
            }

            if (settingsObj != null && !settingsObj.wifi5gEnabled && !settingsObj.hotspotModeEnabled) {
                AppLog.i("AutoStartReceiver: Wireless options are unchecked (Bluetooth Companion / USB only) — never forcing Wi-Fi 5 GHz.")
                return
            }

            val effectiveWifiMode = if (settingsObj != null) {
                com.andrerinas.openheadunit.aap.WirelessScopePolicy.effectiveMode(
                    settingsObj.wifiConnectionMode,
                    settingsObj.connectionModes
                )
            } else 3
            if (effectiveWifiMode == 0) {
                AppLog.i("AutoStartReceiver: Effective wireless mode is OFF — skipping wireless auto-start.")
                return
            }

            // [FIX] Don't trigger auto-start if we are already connected!
            if (!isLocked && com.andrerinas.openheadunit.App.provide(context).commManager.isConnected) {
                AppLog.d("AutoStartReceiver: Already connected to Android Auto. Ignoring BT event.")
                return
            }

            // Only auto-start AapService / MainActivity on Bluetooth connect if the user explicitly configured a target BT MAC!
            val matchesTarget = device != null && targetMacs.isNotEmpty() && targetMacs.contains(device.address)
            if (matchesTarget && device != null) {
                AppLog.i("MATCH! Starting AapService via Bluetooth Auto-start for ${device.name} (${device.address})...")

                val serviceIntent = Intent(context, AapService::class.java).setAction(AapService.ACTION_BT_AUTO_START)
                var serviceStarted = true
                try {
                    androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    serviceStarted = false
                    AppLog.e("Failed to start AapService from background: ${e.message}")
                }

                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "Bluetooth auto-start")
                }
                try {
                    context.startActivity(launchIntent)
                } catch (e: Exception) {
                    AppLog.w("Could not start UI from background (expected on Android 10+): ${e.message}")
                }

                if (!serviceStarted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    showLaunchNotification(context, launchIntent, device.name ?: device.address)
                }
            }
        }
    }

    private fun showLaunchNotification(context: Context, launchIntent: Intent, deviceLabel: String) {
        try {
            val pendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, App.bootStartChannel)
                .setSmallIcon(R.drawable.ic_stat_aa)
                .setContentTitle(context.getString(R.string.auto_start_bt_label))
                .setContentText(context.getString(R.string.wifi_autostart_content, deviceLabel))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setFullScreenIntent(pendingIntent, true)
                .setContentIntent(pendingIntent)
                .build()
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(BT_AUTO_START_NOTIFICATION_ID, notification)
            AppLog.i("AutoStartReceiver: Triggered FullScreenIntent notification.")
        } catch (e: Exception) {
            AppLog.e("AutoStartReceiver: Could not post launch notification: ${e.message}")
        }
    }

    private companion object {
        const val BT_AUTO_START_NOTIFICATION_ID = 98
    }
}
