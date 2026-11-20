package com.andrerinas.openheadunit.cluster

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.Display
import android.view.Surface
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog
import java.lang.ref.WeakReference

/**
 * Lightweight, strictly event-driven controller for the secondary instrument-cluster display
 * (`Display 2` on the Leapmotor C10, `1920×720`, right-hand clear map window).
 *
 * Core safety guarantees:
 * - Strictly opt-in (`settings.clusterMapEnabled` defaults to `false`).
 * - Never opens Display 2 in idle/standby before a real Android Auto or CarPlay stream starts.
 * - Zero `1 Hz` polling timers: song-change popup uses a single one-shot 5-second hide timer, and
 *   the turn-by-turn pill updates strictly on incoming navigation events.
 * - Zero OEM vehicle-telemetry Binders, zero navigation broadcasts, and zero
 *   steering-wheel key hijacking. Only calls `IAutoSdkService` Transaction 6
 *   (`setDashboardMapLoadingVisibility`) while projecting and unbinds immediately on stop.
 */
object ClusterMapController {

    enum class Mode {
        ANDROID_AUTO,
        CARPLAY,
    }

    data class ClusterMediaState(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val appName: String = "ANDROID AUTO",
        val albumArt: Bitmap? = null,
    ) {
        val hasTrack: Boolean
            get() = title.isNotBlank()
    }

    data class ClusterNavState(
        val isNavigating: Boolean = false,
        val distanceMeters: Int? = null,
        val road: String = "",
        val actionText: String = "",
        val turnSide: Int? = null,
        val nextEventType: Int = 0,
        val totalDistanceMeters: Int? = null,
        val estimatedArrival: String? = null,
    )

    const val LEAPMOTOR_CLUSTER_DISPLAY_ID = 2
    internal const val EXTRA_MODE = "com.andrerinas.openheadunit.cluster.EXTRA_MODE"
    const val MEDIA_POPUP_DURATION_MS = 5_000L

    private const val AUTOSDK_PACKAGE = "com.leapmotor.autosdk.server"
    private const val AUTOSDK_ACTION = "com.leapmotor.autosdk.server.action.AUTOSDK_SERVICE"
    private const val AUTOSDK_DESCRIPTOR = "com.leapmotor.autosdk.server.IAutoSdkService"
    private const val TRANSACTION_SET_DASHBOARD_MAP_LOADING_VISIBILITY = 6
    private const val UNBIND_DELAY_MS = 250L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var currentMode: Mode? = null
        private set

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var mediaState: ClusterMediaState = ClusterMediaState()
        private set

    @Volatile
    var navState: ClusterNavState = ClusterNavState()
        private set

    @Volatile
    var mediaPopupVisible: Boolean = false
        private set

    @Volatile
    private var lastTrackSignature: String = ""

    @Volatile
    private var currentSurface: Surface? = null

    @Volatile
    private var carPlaySurfaceListener: ((Surface?) -> Unit)? = null

    private var activityRef: WeakReference<ClusterMapActivity>? = null

    @Volatile
    private var autoSdkBinder: IBinder? = null

    @Volatile
    private var autoSdkBound: Boolean = false

    @Volatile
    private var boundAppContext: Context? = null

    @Volatile
    private var lastLoadingVisibilitySent: Boolean? = null

    private val hideMediaPopupRunnable = Runnable {
        if (mediaPopupVisible) {
            mediaPopupVisible = false
            notifyUiChanged()
        }
    }

    private val unbindAutoSdkRunnable = Runnable {
        if (!isActive) {
            unbindAutoSdkNow()
        }
    }

    private val autoSdkConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            autoSdkBinder = service
            lastLoadingVisibilitySent = null
            AppLog.i("[ClusterMap] Connected to IAutoSdkService ($name)")
            if (isActive) {
                setDashboardMapLoadingVisibility(loadingVisible = false)
            } else {
                setDashboardMapLoadingVisibility(loadingVisible = true)
                mainHandler.postDelayed(unbindAutoSdkRunnable, UNBIND_DELAY_MS)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            autoSdkBinder = null
            autoSdkBound = false
            lastLoadingVisibilitySent = null
            AppLog.i("[ClusterMap] Disconnected from IAutoSdkService ($name)")
        }

        override fun onNullBinding(name: ComponentName?) {
            autoSdkBinder = null
            autoSdkBound = false
        }
    }

    /** Pure helper: returns the normalized track signature used to detect real song changes. */
    fun trackSignature(title: String, artist: String): String =
        "${title.trim()}|${artist.trim()}"

    /** Pure helper: popup triggers ONLY on a non-blank title whose `title|artist` changed. */
    fun shouldTriggerMediaPopup(previousSignature: String, newTitle: String, newArtist: String): Boolean {
        val cleanTitle = newTitle.trim()
        if (cleanTitle.isEmpty()) return false
        return trackSignature(cleanTitle, newArtist) != previousSignature
    }

    /**
     * Pure helper: Android Auto's native `DISPLAY_TYPE_CLUSTER` stream already renders Google Maps'
     * own green turn-by-turn banner in the clear top window, so our custom top turn card MUST stay
     * hidden in Android Auto mode to avoid covering it.
     */
    fun shouldShowTurnPill(mode: Mode, turnOverlayEnabled: Boolean, isNavigating: Boolean): Boolean =
        mode != Mode.ANDROID_AUTO && turnOverlayEnabled && isNavigating

    fun findClusterDisplay(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        dm.getDisplay(LEAPMOTOR_CLUSTER_DISPLAY_ID)?.let { display ->
            if (display.displayId != Display.DEFAULT_DISPLAY) {
                return display
            }
        }
        return dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    fun hasClusterDisplay(context: Context): Boolean = findClusterDisplay(context) != null

    fun startForAndroidAuto(context: Context) {
        val appContext = context.applicationContext
        val settings = App.provide(appContext).settings
        if (!settings.clusterMapEnabled) {
            AppLog.d("[ClusterMap] Cluster map disabled in settings; skipping Android Auto cluster")
            return
        }
        val display = findClusterDisplay(appContext)
        if (display == null) {
            AppLog.d("[ClusterMap] No secondary cluster display found for Android Auto")
            return
        }
        mainHandler.removeCallbacks(unbindAutoSdkRunnable)
        currentMode = Mode.ANDROID_AUTO
        isActive = true
        ensureAutoSdkBound(appContext)
        setDashboardMapLoadingVisibility(loadingVisible = false)
        mainHandler.post {
            val existingActivity = activityRef?.get()
            if (existingActivity != null && !existingActivity.isFinishing && !existingActivity.isDestroyed) {
                existingActivity.applyModeLayout(Mode.ANDROID_AUTO)
                existingActivity.refreshOverlayUi()
                currentSurface?.takeIf { it.isValid }?.let { surface ->
                    bindAndroidAutoSurface(appContext, surface)
                }
            } else {
                launchActivityOnDisplay(appContext, display.displayId, Mode.ANDROID_AUTO)
            }
        }
    }

    fun startForCarPlay(context: Context, surfaceListener: (Surface?) -> Unit) {
        val appContext = context.applicationContext
        val settings = App.provide(appContext).settings
        if (!settings.clusterMapEnabled) {
            AppLog.d("[ClusterMap] Cluster map disabled in settings; skipping CarPlay cluster")
            return
        }
        val display = findClusterDisplay(appContext)
        if (display == null) {
            AppLog.d("[ClusterMap] No secondary cluster display found for CarPlay")
            return
        }
        mainHandler.removeCallbacks(unbindAutoSdkRunnable)
        currentMode = Mode.CARPLAY
        carPlaySurfaceListener = surfaceListener
        isActive = true
        ensureAutoSdkBound(appContext)
        setDashboardMapLoadingVisibility(loadingVisible = false)
        mainHandler.post {
            val existingActivity = activityRef?.get()
            if (existingActivity != null && !existingActivity.isFinishing && !existingActivity.isDestroyed) {
                existingActivity.applyModeLayout(Mode.CARPLAY)
                existingActivity.refreshOverlayUi()
                currentSurface?.takeIf { it.isValid }?.let { surface ->
                    surfaceListener(surface)
                }
            } else {
                launchActivityOnDisplay(appContext, display.displayId, Mode.CARPLAY)
            }
        }
    }

    fun stop(mode: Mode? = null, context: Context? = null) {
        if (mode != null && currentMode != null && currentMode != mode) {
            return
        }
        val previousMode = currentMode
        val wasActive = isActive || activityRef?.get() != null || autoSdkBound
        isActive = false
        currentMode = null
        currentSurface = null
        mediaPopupVisible = false
        lastTrackSignature = ""
        mediaState = ClusterMediaState()
        navState = ClusterNavState()
        mainHandler.removeCallbacks(hideMediaPopupRunnable)

        if (previousMode == Mode.ANDROID_AUTO && context != null) {
            runCatching {
                App.provide(context.applicationContext).clusterVideoDecoder.setSurface(null)
            }
        } else if (previousMode == Mode.CARPLAY) {
            val listener = carPlaySurfaceListener
            carPlaySurfaceListener = null
            runCatching { listener?.invoke(null) }
        } else {
            carPlaySurfaceListener = null
        }

        if (wasActive) {
            setDashboardMapLoadingVisibility(loadingVisible = true)
            mainHandler.removeCallbacks(unbindAutoSdkRunnable)
            mainHandler.postDelayed(unbindAutoSdkRunnable, UNBIND_DELAY_MS)
        }

        mainHandler.post {
            activityRef?.get()?.let { activity ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.finish()
                }
            }
            activityRef = null
        }
    }

    fun updateMedia(
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        appName: String? = null,
        albumArt: Bitmap? = null,
        keepExistingArtIfNull: Boolean = true,
    ) {
        if (!isActive) return
        val cur = mediaState
        val newTitle = (title ?: cur.title).trim()
        val newArtist = (artist ?: cur.artist).trim()
        val newAlbum = (album ?: cur.album).trim()
        val newApp = (appName ?: cur.appName).trim().ifBlank { "ANDROID AUTO" }
        val newArt = if (albumArt != null || !keepExistingArtIfNull) albumArt else cur.albumArt

        val trackChanged = shouldTriggerMediaPopup(lastTrackSignature, newTitle, newArtist)
        val artArrivedForPopup = mediaPopupVisible && newArt != cur.albumArt

        mediaState = ClusterMediaState(
            title = newTitle,
            artist = newArtist,
            album = newAlbum,
            appName = newApp,
            albumArt = newArt,
        )

        if (trackChanged) {
            lastTrackSignature = trackSignature(newTitle, newArtist)
            mediaPopupVisible = true
            mainHandler.removeCallbacks(hideMediaPopupRunnable)
            mainHandler.postDelayed(hideMediaPopupRunnable, MEDIA_POPUP_DURATION_MS)
            AppLog.i("[ClusterMap] Track changed -> '$newTitle' by '$newArtist' (5s popup)")
            notifyUiChanged()
        } else if (artArrivedForPopup) {
            notifyUiChanged()
        }
    }

    fun updateNavigation(state: ClusterNavState) {
        if (!isActive) return
        if (state == navState) return
        navState = state
        notifyUiChanged()
    }

    fun clearNavigation() {
        if (!navState.isNavigating) return
        navState = ClusterNavState(isNavigating = false)
        notifyUiChanged()
    }

    internal fun onActivityCreated(activity: ClusterMapActivity) {
        activityRef = WeakReference(activity)
    }

    internal fun onActivityDestroyed(activity: ClusterMapActivity) {
        if (activityRef?.get() === activity) {
            activityRef = null
        }
    }

    internal fun onSurfaceAvailable(context: Context, surface: Surface) {
        currentSurface = surface
        if (!isActive) return
        ensureAutoSdkBound(context)
        setDashboardMapLoadingVisibility(loadingVisible = false)
        when (currentMode) {
            Mode.ANDROID_AUTO -> bindAndroidAutoSurface(context.applicationContext, surface)
            Mode.CARPLAY -> carPlaySurfaceListener?.invoke(surface)
            null -> Unit
        }
    }

    internal fun onSurfaceDestroyed(context: Context, surface: Surface) {
        if (currentSurface === surface) {
            currentSurface = null
        }
        when (currentMode) {
            Mode.ANDROID_AUTO -> {
                runCatching {
                    App.provide(context.applicationContext).clusterVideoDecoder.setSurface(null)
                }
            }
            Mode.CARPLAY -> {
                runCatching { carPlaySurfaceListener?.invoke(null) }
            }
            null -> Unit
        }
    }

    private fun bindAndroidAutoSurface(appContext: Context, surface: Surface) {
        runCatching {
            val component = App.provide(appContext)
            component.clusterVideoDecoder.setSurface(surface)
            component.commManager.requestClusterKeyframe()
            setDashboardMapLoadingVisibility(loadingVisible = false)
            AppLog.i("[ClusterMap] Bound Android Auto cluster surface and requested keyframe")
        }.onFailure { e ->
            AppLog.e("[ClusterMap] Failed to bind Android Auto cluster surface: ${e.message}", e)
        }
    }

    private fun launchActivityOnDisplay(appContext: Context, displayId: Int, mode: Mode) {
        try {
            val intent = Intent(appContext, ClusterMapActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(EXTRA_MODE, mode.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                appContext.startActivity(intent, options.toBundle())
            } else {
                appContext.startActivity(intent)
            }
            AppLog.i("[ClusterMap] Launched ClusterMapActivity on displayId=$displayId (mode=$mode)")
        } catch (t: Throwable) {
            AppLog.e("[ClusterMap] Failed to launch ClusterMapActivity on displayId=$displayId: ${t.message}", t)
        }
    }

    private fun ensureAutoSdkBound(context: Context) {
        if (autoSdkBinder?.isBinderAlive == true || autoSdkBound) return
        val appContext = context.applicationContext
        try {
            val intent = Intent(AUTOSDK_ACTION).setPackage(AUTOSDK_PACKAGE)
            val bound = appContext.bindService(intent, autoSdkConnection, Context.BIND_AUTO_CREATE)
            autoSdkBound = bound
            if (bound) {
                boundAppContext = appContext
                AppLog.d("[ClusterMap] Binding to $AUTOSDK_ACTION ($AUTOSDK_PACKAGE)")
            }
        } catch (t: Throwable) {
            AppLog.d("[ClusterMap] Could not bind IAutoSdkService: ${t.message}")
        }
    }

    private fun unbindAutoSdkNow() {
        val ctx = boundAppContext
        val wasBound = autoSdkBound
        autoSdkBound = false
        autoSdkBinder = null
        boundAppContext = null
        lastLoadingVisibilitySent = null
        if (wasBound && ctx != null) {
            runCatching { ctx.unbindService(autoSdkConnection) }
        }
    }

    private fun setDashboardMapLoadingVisibility(loadingVisible: Boolean) {
        val binder = autoSdkBinder ?: return
        if (!binder.isBinderAlive) return
        if (lastLoadingVisibilitySent == loadingVisible) return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(AUTOSDK_DESCRIPTOR)
            data.writeInt(if (loadingVisible) 1 else 0)
            val ok = binder.transact(TRANSACTION_SET_DASHBOARD_MAP_LOADING_VISIBILITY, data, reply, 0)
            if (ok) {
                reply.readException()
                lastLoadingVisibilitySent = loadingVisible
                AppLog.i("[ClusterMap] setDashboardMapLoadingVisibility($loadingVisible) -> OK")
            }
        } catch (t: Throwable) {
            AppLog.w("[ClusterMap] setDashboardMapLoadingVisibility($loadingVisible) failed: ${t.message}")
        } finally {
            runCatching { reply.recycle() }
            runCatching { data.recycle() }
        }
    }

    private fun notifyUiChanged() {
        mainHandler.post {
            val act = activityRef?.get() ?: return@post
            if (!act.isFinishing && !act.isDestroyed) {
                act.refreshOverlayUi()
            }
        }
    }
}
