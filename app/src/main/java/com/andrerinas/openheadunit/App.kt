package com.andrerinas.openheadunit

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.multidex.MultiDex
import com.andrerinas.openheadunit.aap.AapNavigation
import com.andrerinas.openheadunit.main.BackgroundNotification
import com.andrerinas.openheadunit.ssl.ConscryptInitializer
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppThemeManager
import com.andrerinas.openheadunit.utils.Settings

/**
 * Qonecta Unit Core Application.
 *
 * Intentionally minimal: never opens network sockets, Bluetooth servers, or OEM Binders
 * during Application.onCreate(). Everything runs strictly on demand when the user opens
 * the app and starts Android Auto or Apple CarPlay.
 */
class App : Application() {

    private val component: AppComponent by lazy {
        AppComponent(this)
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)

        if (ConscryptInitializer.isNeededForTls12()) {
            ConscryptInitializer.initialize()
        }

        if (isUserUnlocked()) {
            val settings = Settings(this)
            AppLog.init(settings, this)

            // Core safe mode: keep all boot/background auto-start flags disabled.
            Settings.syncAutoStartOnBootToDeviceStorage(this, false)
            Settings.syncAutoStartOnUsbToDeviceStorage(this, false)
            Settings.syncAutoStartOnWifiToDeviceStorage(this, false)
            Settings.syncAutoStartWifiSsidToDeviceStorage(this, "")
            Settings.syncAutoStartBtMacsToDeviceStorage(this, emptySet())

            AppThemeManager.reapply(this, settings)
        } else {
            AppLog.init(null, this)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(defaultChannel, "Headunit Service", NotificationManager.IMPORTANCE_LOW)
            serviceChannel.description = "Persistent service notification"
            serviceChannel.setShowBadge(false)
            component.notificationManager.createNotificationChannel(serviceChannel)

            val mediaChannel = NotificationChannel(BackgroundNotification.mediaChannel, "Media Playback", NotificationManager.IMPORTANCE_LOW)
            mediaChannel.setSound(null, null)
            mediaChannel.setShowBadge(false)
            component.notificationManager.createNotificationChannel(mediaChannel)

            AapNavigation.createNotificationChannel(this)

            val bootChannel = NotificationChannel(bootStartChannel, "Projection Launch", NotificationManager.IMPORTANCE_HIGH)
            bootChannel.description = "Projection launch notification"
            bootChannel.setShowBadge(false)
            component.notificationManager.createNotificationChannel(bootChannel)
        }

        ContextCompat.registerReceiver(this, AapBroadcastReceiver(), AapBroadcastReceiver.filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) { topActivityRef = java.lang.ref.WeakReference(activity) }
            override fun onActivityResumed(activity: android.app.Activity) {
                topActivityRef = java.lang.ref.WeakReference(activity)
                com.headqlink.unit.files.CarReturnPillOverlay.dismiss()
            }
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) { if (topActivityRef?.get() === activity) topActivityRef = null }
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) { if (topActivityRef?.get() === activity) topActivityRef = null }
        })
    }

    private fun isUserUnlocked(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = getSystemService(Context.USER_SERVICE) as UserManager
            userManager.isUserUnlocked
        } else {
            true
        }
    }

    companion object {
        const val defaultChannel = "headunit_service_v2"
        const val bootStartChannel = "headunit_boot_start"
        val appStartTime = SystemClock.elapsedRealtime()
        var appThemeManager: AppThemeManager? = null
        var isPiPActive = false

        @Volatile
        private var topActivityRef: java.lang.ref.WeakReference<android.app.Activity>? = null

        val topActivity: android.app.Activity?
            get() = topActivityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

        @Volatile
        var instance: App? = null
            private set

        fun get(context: Context): App {
            return context.applicationContext as App
        }

        fun provide(context: Context): AppComponent {
            return get(context).component
        }
    }
}
