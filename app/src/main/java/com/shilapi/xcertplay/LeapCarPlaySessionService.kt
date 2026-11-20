package com.shilapi.xcertplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.andrerinas.openheadunit.R

/** Keeps an explicitly started connection alive when another car app is in the foreground. */
class LeapCarPlaySessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CarPlayBackgroundSession.stop()
            runCatching { stopService(Intent(this, com.shilapi.xcertplay.network.CarPlayVpnService::class.java)) }
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "CarPlay connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, CarPlayHostActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LeapCarPlaySessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_leapcarplay_notification)
            .setContentTitle("Qonecta Unit · CarPlay")
            .setContentText("CarPlay connection running")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) {
            val fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            try {
                startForeground(NOTIFICATION_ID, notification, fgType)
            } catch (_: Exception) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        CarPlayBackgroundSession.stop()
        runCatching { stopService(Intent(this, com.shilapi.xcertplay.network.CarPlayVpnService::class.java)) }
        stopSelf()
    }
    override fun onDestroy() {
        CarPlayBackgroundSession.stop()
        runCatching { stopService(Intent(this, com.shilapi.xcertplay.network.CarPlayVpnService::class.java)) }
        super.onDestroy()
    }
    companion object {
        const val ACTION_STOP = "com.signxer.leapcarplay.DISCONNECT"
        private const val CHANNEL = "leapcarplay_connection"
        const val NOTIFICATION_ID = 2001
    }
}
