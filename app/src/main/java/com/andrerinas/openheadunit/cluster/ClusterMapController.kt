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
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.Surface
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog
import com.headqlink.unit.media.QonectaMediaSessionManager
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Manages the secondary instrument-cluster display (`Display 2` on the Leapmotor C10,
 * `1920x720`, right-hand `720x720` square) for both Android Auto (`Channel.ID_CLUSTER_VID`) and
 * Apple CarPlay (`SCREEN_TYPE_ALT` / stream `111`).
 *
 * Capabilities:
 * 1. Binds to Leapmotor's `com.leapmotor.autosdk.server.IAutoSdkService`:
 *    - Transaction `6` (`setDashboardMapLoadingVisibility(boolean)`) to hide the C10's default
 *      cluster cover and reveal our cluster activity immediately upon opening Qonecta Unit.
 *    - Transaction `14` (`sendNaviInfo(String json)`) + `com.leapmotor.autonavi.action.SMARTDOCKER`
 *      broadcast so native C10 HUD/cluster widgets also receive turn-by-turn maneuvers.
 * 2. Multi-page Cluster UI (cycled with a double-press of the steering wheel Play/Pause button):
 *    - [ClusterPage.MAP]: Live navigation map + top Turn-by-Turn maneuver pill + automatic
 *      5-second Now Playing popup card whenever the song changes.
 *    - [ClusterPage.MUSIC]: Pinned Now Playing card with album art, title, artist, album, and
 *      live progress bar.
 *    - [ClusterPage.EXTENDED]: Qonecta+ Extendido C10 telemetry (SoC %, EV Range km, Power kW,
 *      Speed km/h, Drive Mode, Cabin/Exterior Temp °C) + live projection diagnostics.
 */
object ClusterMapController {

    enum class Mode {
        ANDROID_AUTO,
        CARPLAY,
    }

    enum class ClusterPage {
        MAP,
        MUSIC,
        EXTENDED,
        NATIVE_C10,
    }

    enum class ConnectionPhase {
        WELCOME,
        CONNECTING,
        PROJECTING,
    }

    data class ClusterSessionInfo(
        val phase: ConnectionPhase = ConnectionPhase.WELCOME,
        val deviceName: String = "",
        val protocolLabel: String = "Android Auto / CarPlay",
        val resolutionLabel: String = "1280×720",
        val codecLabel: String = "H.264",
        val fps: Int = 30,
        val connectedSinceElapsedMs: Long = 0L,
    )

    data class ClusterMediaState(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val appName: String = "ANDROID AUTO",
        val albumArt: Bitmap? = null,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val isPlaying: Boolean = false,
        val updatedAtElapsedMs: Long = 0L,
    ) {
        val hasTrack: Boolean
            get() = title.isNotBlank()
    }

    data class ClusterNavState(
        val isNavigating: Boolean = false,
        val distanceMeters: Int? = null,
        val timeSeconds: Int? = null,
        val road: String = "",
        val actionText: String = "",
        val turnSide: Int? = null,
        val nextEventType: Int = 0,
        val maneuverType: Int? = null,
        val totalDistanceMeters: Int? = null,
        val totalTimeSeconds: Long? = null,
        val estimatedArrival: String? = null,
    )

    const val LEAPMOTOR_CLUSTER_DISPLAY_ID = 2
    internal const val EXTRA_MODE = "com.andrerinas.openheadunit.cluster.EXTRA_MODE"

    private const val AUTOSDK_PACKAGE = "com.leapmotor.autosdk.server"
    private const val AUTOSDK_ACTION = "com.leapmotor.autosdk.server.action.AUTOSDK_SERVICE"
    private const val AUTOSDK_DESCRIPTOR = "com.leapmotor.autosdk.server.IAutoSdkService"
    private const val TRANSACTION_SET_DASHBOARD_MAP_LOADING_VISIBILITY = 6
    private const val TRANSACTION_SEND_NAVI_INFO = 14

    private const val MEDIA_POPUP_DURATION_MS = 5_000L
    private const val DOUBLE_CLICK_MIN_MS = 55L
    private const val DOUBLE_CLICK_MAX_MS = 460L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var currentMode: Mode? = null
        private set

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var hasVideoStream: Boolean = false
        private set

    @Volatile
    var currentPage: ClusterPage = ClusterPage.MAP
        private set

    @Volatile
    var sessionInfo: ClusterSessionInfo = ClusterSessionInfo()
        private set

    @Volatile
    var mediaState: ClusterMediaState = ClusterMediaState()
        private set

    @Volatile
    var navState: ClusterNavState = ClusterNavState()
        private set

    @Volatile
    var mediaPopupUntilElapsedMs: Long = 0L
        private set

    @Volatile
    private var lastTrackSignature: String = ""

    @Volatile
    private var lastPlayPausePressElapsedMs: Long = 0L

    @Volatile
    private var lastPlayPauseDownTime: Long = -1L

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
    private var lastLoadingVisibilitySent: Boolean? = null

    private val autoSdkConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            autoSdkBinder = service
            lastLoadingVisibilitySent = null
            AppLog.i("[ClusterMap] Connected to IAutoSdkService ($name)")
            if (isActive) {
                setDashboardMapLoadingVisibility(loadingVisible = (currentPage == ClusterPage.NATIVE_C10))
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

    private fun ensureAutoSdkBound(context: Context) {
        if (autoSdkBinder?.isBinderAlive == true) return
        val appContext = context.applicationContext
        try {
            val intent = Intent(AUTOSDK_ACTION).setPackage(AUTOSDK_PACKAGE)
            val bound = appContext.bindService(intent, autoSdkConnection, Context.BIND_AUTO_CREATE)
            autoSdkBound = bound
            if (bound) {
                AppLog.d("[ClusterMap] Binding to $AUTOSDK_ACTION ($AUTOSDK_PACKAGE)")
            }
        } catch (t: Throwable) {
            AppLog.d("[ClusterMap] Could not bind IAutoSdkService: ${t.message}")
        }
    }

    /**
     * Calls `IAutoSdkService` Transaction 6 (`setDashboardMapLoadingVisibility(boolean)`):
     * - `loadingVisible = false` (`0`): hides the C10 cluster loading cover and shows our cluster UI.
     * - `loadingVisible = true` (`1`): restores the C10 cluster default cover when stopped.
     */
    fun setDashboardMapLoadingVisibility(loadingVisible: Boolean) {
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

    /**
     * Finds the instrument-cluster display (`Display 2` on the Leapmotor C10, or any non-default
     * secondary display).
     */
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

    /**
     * Returns true when the cluster display is a non-Presentation system display (such as
     * `Display 2` on the Leapmotor C10) that requires an Activity launched via
     * `ActivityOptions.setLaunchDisplayId(...)` instead of a `Presentation` dialog.
     */
    fun requiresClusterActivity(context: Context): Boolean {
        val display = findClusterDisplay(context) ?: return false
        val presentationDisplays =
            (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .orEmpty()
        return presentationDisplays.none { it.displayId == display.displayId }
    }

    /**
     * Immediately opens the cluster activity on `Display 2` in Welcome/Standby mode when the user
     * opens Qonecta Unit on the Leapmotor C10, matching and improving upon LeapLink's instant
     * cluster wake-up.
     */
    fun showWelcomeIfAvailable(context: Context, modeHint: Mode = Mode.ANDROID_AUTO) {
        val appContext = context.applicationContext
        ensureMediaControlBridge(appContext)
        QonectaMediaSessionManager.publishIdleWelcome(appContext)
        val settings = App.provide(appContext).settings
        if (!settings.clusterMapEnabled) return
        val display = findClusterDisplay(appContext) ?: return
        ensureAutoSdkBound(appContext)
        setDashboardMapLoadingVisibility(loadingVisible = false)
        clearNativeLeapmotorNav(appContext)
        if (!isActive) {
            currentMode = modeHint
            isActive = true
            hasVideoStream = false
            sessionInfo = ClusterSessionInfo(
                phase = ConnectionPhase.WELCOME,
                protocolLabel = if (modeHint == Mode.CARPLAY) "Apple CarPlay" else "Android Auto",
            )
        }
        mainHandler.post {
            val existingActivity = activityRef?.get()
            if (existingActivity != null && !existingActivity.isFinishing && !existingActivity.isDestroyed) {
                existingActivity.refreshOverlayUi()
            } else {
                launchActivityOnDisplay(appContext, display.displayId, currentMode ?: modeHint)
            }
        }
    }

    fun notifyConnecting(context: Context, deviceName: String, protocolLabel: String, mode: Mode = Mode.ANDROID_AUTO) {
        val appContext = context.applicationContext
        ensureMediaControlBridge(appContext)
        QonectaMediaSessionManager.publishConnecting(
            context = appContext,
            modeLabel = protocolLabel,
            detail = deviceName.ifBlank { "Conectando con el teléfono…" },
        )
        val settings = App.provide(appContext).settings
        if (!settings.clusterMapEnabled) return
        val display = findClusterDisplay(appContext) ?: return
        currentMode = mode
        isActive = true
        ensureAutoSdkBound(appContext)
        setDashboardMapLoadingVisibility(loadingVisible = false)
        clearNativeLeapmotorNav(appContext)
        sessionInfo = sessionInfo.copy(
            phase = if (hasVideoStream) ConnectionPhase.PROJECTING else ConnectionPhase.CONNECTING,
            deviceName = deviceName.ifBlank { "Teléfono" },
            protocolLabel = protocolLabel,
        )
        mainHandler.post {
            val existingActivity = activityRef?.get()
            if (existingActivity != null && !existingActivity.isFinishing && !existingActivity.isDestroyed) {
                existingActivity.refreshOverlayUi()
            } else {
                launchActivityOnDisplay(appContext, display.displayId, mode)
            }
        }
    }

    fun updateSessionStreamInfo(
        deviceName: String? = null,
        protocolLabel: String? = null,
        resolutionLabel: String? = null,
        codecLabel: String? = null,
        fps: Int? = null,
    ) {
        val cur = sessionInfo
        sessionInfo = cur.copy(
            deviceName = deviceName ?: cur.deviceName,
            protocolLabel = protocolLabel ?: cur.protocolLabel,
            resolutionLabel = resolutionLabel ?: cur.resolutionLabel,
            codecLabel = codecLabel ?: cur.codecLabel,
            fps = fps ?: cur.fps,
        )
        notifyUiChanged()
    }

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
        val isWireless = runCatching { App.provide(appContext).commManager.isWirelessSession }.getOrDefault(true)
        val phoneName = settings.autoStartBluetoothDeviceName.ifBlank { "Android" }
        ensureMediaControlBridge(appContext)
        QonectaMediaSessionManager.publishSessionActive(
            context = appContext,
            modeLabel = if (isWireless) "Android Auto Inalámbrico" else "Android Auto USB",
            subtitle = phoneName,
        )
        currentMode = Mode.ANDROID_AUTO
        isActive = true
        hasVideoStream = true
        sessionInfo = ClusterSessionInfo(
            phase = ConnectionPhase.PROJECTING,
            deviceName = phoneName,
            protocolLabel = if (isWireless) "Android Auto Inalámbrico" else "Android Auto USB",
            resolutionLabel = "1280×720 (720×720)",
            codecLabel = "H.264",
            fps = 30,
            connectedSinceElapsedMs = SystemClock.elapsedRealtime(),
        )
        ensureAutoSdkBound(appContext)
        setDashboardMapLoadingVisibility(loadingVisible = false)
        clearNativeLeapmotorNav(appContext)
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
        val phoneName = runCatching {
            com.shilapi.xcertplay.LeapCarPlayPreferences.phoneName(appContext)
        }.getOrDefault("iPhone")
        val wireless = runCatching {
            com.shilapi.xcertplay.AirPlayPersistence.loadWirelessEnabled(appContext)
        }.getOrDefault(true)
        val hevc = runCatching {
            com.shilapi.xcertplay.AirPlayPersistence.loadHevcEnabled(appContext)
        }.getOrDefault(true)
        val fpsVal = runCatching {
            com.shilapi.xcertplay.AirPlayPersistence.loadFps(appContext)
        }.getOrDefault(30)
        ensureMediaControlBridge(appContext)
        QonectaMediaSessionManager.publishSessionActive(
            context = appContext,
            modeLabel = if (wireless) "Apple CarPlay Inalámbrico" else "Apple CarPlay USB",
            subtitle = phoneName,
        )
        currentMode = Mode.CARPLAY
        carPlaySurfaceListener = surfaceListener
        isActive = true
        hasVideoStream = true
        sessionInfo = ClusterSessionInfo(
            phase = ConnectionPhase.PROJECTING,
            deviceName = phoneName,
            protocolLabel = if (wireless) "Apple CarPlay Inalámbrico" else "Apple CarPlay USB",
            resolutionLabel = "720×720",
            codecLabel = if (hevc) "HEVC (H.265)" else "H.264",
            fps = fpsVal,
            connectedSinceElapsedMs = SystemClock.elapsedRealtime(),
        )
        ensureAutoSdkBound(appContext)
        setDashboardMapLoadingVisibility(loadingVisible = false)
        clearNativeLeapmotorNav(appContext)
        mainHandler.post {
            val existingActivity = activityRef?.get()
            if (existingActivity != null && !existingActivity.isFinishing && !existingActivity.isDestroyed) {
                existingActivity.applyModeLayout(Mode.CARPLAY)
                existingActivity.refreshOverlayUi()
                currentSurface?.takeIf { it.isValid }?.let { surface ->
                    surfaceListener.invoke(surface)
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
        isActive = false
        hasVideoStream = false
        currentMode = null
        currentSurface = null
        currentPage = ClusterPage.MAP
        mediaPopupUntilElapsedMs = 0L
        lastTrackSignature = ""
        navState = ClusterNavState(isNavigating = false)
        context?.let { clearNativeLeapmotorNav(it.applicationContext) }

        setDashboardMapLoadingVisibility(loadingVisible = true)

        if (previousMode == Mode.ANDROID_AUTO && context != null) {
            runCatching {
                App.provide(context.applicationContext).clusterVideoDecoder.setSurface(null)
            }
        }
        if (previousMode == Mode.CARPLAY) {
            runCatching {
                carPlaySurfaceListener?.invoke(null)
            }
            carPlaySurfaceListener = null
        }

        mainHandler.post {
            activityRef?.get()?.let { activity ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    AppLog.i("[ClusterMap] Finishing ClusterMapActivity (mode=$previousMode)")
                    activity.finish()
                }
            }
            activityRef = null
        }
    }

    /**
     * Detects a double-press (`2 toques` within ~460 ms) on the steering wheel Play/Pause button
     * (`KEYCODE_MEDIA_PLAY_PAUSE`, `KEYCODE_HEADSETHOOK`, `KEYCODE_MEDIA_PLAY`, `KEYCODE_MEDIA_PAUSE`)
     * and cycles the C10 instrument cluster view (`MAP -> MUSIC -> EXTENDED -> MAP`).
     *
     * @return `true` if this press completed a double-click and cycled the cluster page.
     */
    fun onSteeringPlayPausePressed(context: Context? = null, downTime: Long? = null): Boolean {
        if (!isActive) return false
        if (downTime != null && downTime > 0L && downTime == lastPlayPauseDownTime) {
            return false
        }
        val now = SystemClock.elapsedRealtime()
        val delta = now - lastPlayPausePressElapsedMs
        if (delta < DOUBLE_CLICK_MIN_MS) {
            return false
        }
        if (downTime != null && downTime > 0L) {
            lastPlayPauseDownTime = downTime
        }
        if (delta <= DOUBLE_CLICK_MAX_MS) {
            lastPlayPausePressElapsedMs = 0L
            cyclePage(context)
            return true
        }
        lastPlayPausePressElapsedMs = now
        return false
    }

    fun cyclePage(context: Context? = null) {
        val next = when (currentPage) {
            ClusterPage.MAP -> ClusterPage.MUSIC
            ClusterPage.MUSIC -> ClusterPage.EXTENDED
            ClusterPage.EXTENDED -> ClusterPage.NATIVE_C10
            ClusterPage.NATIVE_C10 -> ClusterPage.MAP
        }
        setPage(next, context)
    }

    fun setPage(next: ClusterPage, context: Context? = null) {
        currentPage = next
        if (next != ClusterPage.MAP) {
            mediaPopupUntilElapsedMs = 0L
        }
        // When NATIVE_C10 is selected, restore the native C10 right-hand cluster widgets
        // (native Trip Computer "Desde el inicio / Desde la última carga", native Media card,
        // and native C10 green turn-by-turn arrow). Otherwise show our ClusterMapActivity.
        setDashboardMapLoadingVisibility(loadingVisible = (next == ClusterPage.NATIVE_C10))
        AppLog.i("[ClusterMap] Cluster page -> $next")
        if (next != ClusterPage.NATIVE_C10 && context != null && activityRef?.get() == null) {
            showWelcomeIfAvailable(context, currentMode ?: Mode.ANDROID_AUTO)
        } else {
            notifyUiChanged()
        }
    }

    fun updateMedia(
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        appName: String? = null,
        albumArt: Bitmap? = null,
        keepExistingArtIfNull: Boolean = true,
        positionMs: Long? = null,
        durationMs: Long? = null,
        isPlaying: Boolean? = null,
    ) {
        val now = SystemClock.elapsedRealtime()
        val cur = mediaState
        val newTitle = (title ?: cur.title).trim()
        val newArtist = (artist ?: cur.artist).trim()
        val newAlbum = (album ?: cur.album).trim()
        val newApp = (appName ?: cur.appName).trim().ifBlank { "ANDROID AUTO" }
        val newArt = if (albumArt != null || !keepExistingArtIfNull) albumArt else cur.albumArt
        val newPos = positionMs ?: cur.positionMs
        val newDur = durationMs ?: cur.durationMs
        val newPlaying = isPlaying ?: cur.isPlaying

        val sig = "$newTitle|$newArtist"
        val trackChanged = newTitle.isNotBlank() && sig != lastTrackSignature
        val elapsedSinceLastUpdate = if (cur.isPlaying && cur.updatedAtElapsedMs > 0L) {
            (now - cur.updatedAtElapsedMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val effectivePos = when {
            positionMs != null -> positionMs
            trackChanged -> 0L
            else -> cur.positionMs + elapsedSinceLastUpdate
        }
        val effectivePlaying = isPlaying ?: if (trackChanged) true else cur.isPlaying

        if (trackChanged) {
            lastTrackSignature = sig
            // Show 5-second temporary popup on the cluster map when the song changes!
            if (currentPage == ClusterPage.MAP) {
                mediaPopupUntilElapsedMs = now + MEDIA_POPUP_DURATION_MS
                AppLog.i("[ClusterMap] Track changed -> '$newTitle' by '$newArtist' (5s cluster popup)")
            }
        }

        mediaState = ClusterMediaState(
            title = newTitle,
            artist = newArtist,
            album = newAlbum,
            appName = newApp,
            albumArt = newArt,
            positionMs = effectivePos,
            durationMs = newDur,
            isPlaying = effectivePlaying,
            updatedAtElapsedMs = now,
        )
        App.instance?.let { ctx ->
            QonectaMediaSessionManager.updateNowPlaying(
                context = ctx,
                title = newTitle.ifBlank { "Qonecta Unit" },
                artist = newArtist.ifBlank { newApp },
                album = newAlbum.ifBlank { newApp },
                appName = newApp,
                cover = newArt,
                playing = effectivePlaying,
                durationMs = newDur,
                positionMs = effectivePos,
            )
        }
        notifyUiChanged()
    }

    private fun ensureMediaControlBridge(appContext: Context) {
        QonectaMediaSessionManager.ensureInitialized(appContext)
        QonectaMediaSessionManager.setMediaControlListener(object : QonectaMediaSessionManager.MediaControlListener {
            override fun onPlay() = dispatchMediaKey(appContext, KeyEvent.KEYCODE_MEDIA_PLAY)
            override fun onPause() = dispatchMediaKey(appContext, KeyEvent.KEYCODE_MEDIA_PAUSE)
            override fun onPlayPause() = dispatchMediaKey(appContext, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            override fun onSkipToNext() = dispatchMediaKey(appContext, KeyEvent.KEYCODE_MEDIA_NEXT)
            override fun onSkipToPrevious() = dispatchMediaKey(appContext, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            override fun onStop() = dispatchMediaKey(appContext, KeyEvent.KEYCODE_MEDIA_STOP)
        })
    }

    fun dispatchMediaKey(context: Context, keyCode: Int) {
        runCatching {
            val comm = App.provide(context.applicationContext).commManager
            if (comm.isConnected) {
                comm.sendKey(keyCode, true, null, "ClusterMediaWidget")
                comm.sendKey(keyCode, false, null, "ClusterMediaWidget")
                AppLog.i("[ClusterMap] Dispatched media keyCode=$keyCode to Android Auto")
            }
        }
    }

    fun triggerVoiceAssistant(context: Context): Boolean {
        return runCatching {
            val comm = App.provide(context.applicationContext).commManager
            if (comm.isConnected) {
                comm.sendToggleVoiceAssistant()
                AppLog.i("[ClusterMap] Triggered Google Assistant via sendToggleVoiceAssistant()")
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    fun updateNavigation(context: Context, state: ClusterNavState) {
        navState = state
        notifyUiChanged()
        sendNativeLeapmotorNav(context.applicationContext, state)
    }

    fun clearNavigation(context: Context) {
        if (!navState.isNavigating) return
        navState = ClusterNavState(isNavigating = false)
        notifyUiChanged()
        clearNativeLeapmotorNav(context.applicationContext)
    }

    private fun sendNativeLeapmotorNav(appContext: Context, state: ClusterNavState) {
        if (!state.isNavigating) {
            clearNativeLeapmotorNav(appContext)
            return
        }
        ensureAutoSdkBound(appContext)
        val distM = (state.distanceMeters ?: 0).coerceAtLeast(0)
        val (distNum, distUnit, distDesc) = formatDistanceParts(distM)
        val totalDistM = (state.totalDistanceMeters ?: distM).coerceAtLeast(0)
        val (totNum, totUnit, totDesc) = formatDistanceParts(totalDistM)
        val totalTimeSec = (state.totalTimeSeconds ?: state.timeSeconds?.toLong() ?: 0L).coerceAtLeast(0L).toInt()
        val timeDesc = formatDurationMinutes(totalTimeSec)
        val turnId = mapToLeapmotorTurnId(state.maneuverType, state.nextEventType, state.turnSide)
        val roadName = state.road.takeIf { it != "—" } ?: state.actionText

        // 1. Transaction 14 on IAutoSdkService
        autoSdkBinder?.takeIf { it.isBinderAlive }?.let { binder ->
            val json = JSONObject().apply {
                put("distance", distM)
                put("distanceDescription", distDesc)
                put("distanceNumDescription", distNum)
                put("distanceUnitDescription", distUnit)
                put("turnstate", turnId)
                put("road", roadName)
                put("time", totalTimeSec)
                put("timeDescription", timeDesc)
                put("totaldistance", totalDistM)
                put("totalDistanceDescription", totDesc)
                put("totalDistanceNumDescription", totNum)
                put("totalDistanceUnitDescription", totUnit)
                put("nextturnstate", -1)
                put("nextdistance", -1)
                put("nextDistanceDescription", "")
                put("nextDistanceNumDescription", "")
                put("nextDistanceUnitDescription", "")
            }.toString()
            transactAutoSdkString(binder, TRANSACTION_SEND_NAVI_INFO, json)
        }

        // 2. SMARTDOCKER broadcast (com.leapmotor.autonavi.action.SMARTDOCKER)
        runCatching {
            val dataInner = JSONObject().apply {
                put("isNavi", true)
                put("turnId", turnId)
                put("nextCrossTurnId", -1)
                put("segmentRemainDist", distDesc)
                put("nextRouteName", roadName)
                put("nextCrossToSegmentDist", "")
                put("nextCrossRouteName", "")
                put("totalDist", totDesc)
                put("totalTime", timeDesc)
                put("arriveTime", state.estimatedArrival.orEmpty())
            }
            val eventObj = JSONObject().apply {
                put("name", "EVENT_NAVI_INFO")
                put("data", dataInner)
            }
            val msgJson = JSONObject().apply {
                put("api", "onEvent")
                put("origin", "com.leapmotor.adapter.navi.group")
                put("data", eventObj)
            }.toString()
            val intent = Intent("com.leapmotor.autonavi.action.SMARTDOCKER").apply {
                addFlags(0x15000000)
                putExtra("message", msgJson)
                putExtra("type", "response")
                putExtra("result", 0)
                putExtra("errorMessage", "")
            }
            appContext.sendBroadcast(intent)
        }
    }

    private fun clearNativeLeapmotorNav(appContext: Context) {
        autoSdkBinder?.takeIf { it.isBinderAlive }?.let { binder ->
            val json = JSONObject().apply {
                put("distance", -1)
                put("distanceDescription", "")
                put("distanceNumDescription", "")
                put("distanceUnitDescription", "")
                put("turnstate", -1)
                put("road", "")
                put("time", -1)
                put("timeDescription", "")
                put("totaldistance", -1)
                put("totalDistanceDescription", "")
                put("totalDistanceNumDescription", "")
                put("totalDistanceUnitDescription", "")
                put("nextturnstate", -1)
                put("nextdistance", -1)
                put("nextDistanceDescription", "")
                put("nextDistanceNumDescription", "")
                put("nextDistanceUnitDescription", "")
            }.toString()
            transactAutoSdkString(binder, TRANSACTION_SEND_NAVI_INFO, json)
        }
        runCatching {
            val dataInner = JSONObject().apply {
                put("isNavi", false)
                put("turnId", -1)
                put("nextCrossTurnId", -1)
                put("segmentRemainDist", "")
                put("nextRouteName", "")
                put("nextCrossToSegmentDist", "")
                put("nextCrossRouteName", "")
                put("totalDist", "")
                put("totalTime", "")
                put("arriveTime", "")
            }
            val eventObj = JSONObject().apply {
                put("name", "EVENT_NAVI_INFO")
                put("data", dataInner)
            }
            val msgJson = JSONObject().apply {
                put("api", "onEvent")
                put("origin", "com.leapmotor.adapter.navi.group")
                put("data", eventObj)
            }.toString()
            val intent = Intent("com.leapmotor.autonavi.action.SMARTDOCKER").apply {
                addFlags(0x15000000)
                putExtra("message", msgJson)
                putExtra("type", "response")
                putExtra("result", 0)
                putExtra("errorMessage", "")
            }
            appContext.sendBroadcast(intent)
        }
    }

    private fun transactAutoSdkString(binder: IBinder, transactionCode: Int, payload: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(AUTOSDK_DESCRIPTOR)
            data.writeString(payload)
            if (binder.transact(transactionCode, data, reply, 0)) {
                reply.readException()
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { reply.recycle() }
            runCatching { data.recycle() }
        }
    }

    private fun formatDistanceParts(meters: Int): Triple<String, String, String> {
        return if (meters >= 1000) {
            val km = meters / 1000.0
            val num = String.format(Locale.US, "%.1f", km)
            Triple(num, "km", "${num}km")
        } else {
            val num = meters.coerceAtLeast(0).toString()
            Triple(num, "m", "${num}m")
        }
    }

    private fun formatDurationMinutes(seconds: Int): String {
        if (seconds <= 0) return ""
        val min = (seconds / 60).coerceAtLeast(1)
        return if (min >= 60) {
            val h = min / 60
            val rem = min % 60
            "${h}h ${rem}m"
        } else {
            "$min min"
        }
    }

    val isAutoSdkConnected: Boolean
        get() = autoSdkBinder?.isBinderAlive == true

    fun refreshSurfaceLayout() {
        mainHandler.post {
            activityRef?.get()?.let { activity ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.updateSurfacePosition()
                    runCatching {
                        val app = App.provide(activity.applicationContext)
                        app.commManager.sendClusterUpdateUiConfigRequest(app.settings)
                    }
                }
            }
        }
    }

    private fun mapToLeapmotorTurnId(maneuverType: Int?, nextEventType: Int, turnSide: Int?): Int {
        val left = turnSide == 1
        return when (nextEventType) {
            3 -> if (left) 4 else 5 // SLIGHT_TURN
            4 -> if (left) 2 else 3 // TURN
            5 -> if (left) 6 else 7 // SHARP_TURN
            6 -> if (left) 8 else 9 // U_TURN
            7, 8, 9, 10 -> if (left) 4 else 5 // ON/OFF RAMP / FORK / MERGE
            11, 12, 13 -> 12        // ROUNDABOUT
            16 -> 15                // DESTINATION
            else -> 1               // STRAIGHT
        }
    }

    private fun notifyUiChanged() {
        mainHandler.post {
            activityRef?.get()?.let { activity ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.refreshOverlayUi()
                }
            }
        }
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
        ensureAutoSdkBound(context)
        setDashboardMapLoadingVisibility(loadingVisible = (currentPage == ClusterPage.NATIVE_C10))
        if (!hasVideoStream) return
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
                runCatching {
                    carPlaySurfaceListener?.invoke(null)
                }
            }
            null -> Unit
        }
    }

    private fun bindAndroidAutoSurface(appContext: Context, surface: Surface) {
        runCatching {
            val component = App.provide(appContext)
            component.clusterVideoDecoder.setSurface(surface)
            component.commManager.requestClusterKeyframe()
            setDashboardMapLoadingVisibility(loadingVisible = (currentPage == ClusterPage.NATIVE_C10))
            AppLog.i("[ClusterMap] Bound Android Auto cluster surface and requested keyframe")
        }.onFailure { e ->
            AppLog.e("[ClusterMap] Failed to bind Android Auto cluster surface: ${e.message}", e)
        }
    }

    private fun launchActivityOnDisplay(appContext: Context, displayId: Int, mode: Mode) {
        try {
            val intent = Intent(appContext, ClusterMapActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_MODE, mode.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                appContext.startActivity(intent, options.toBundle())
            } else {
                appContext.startActivity(intent)
            }
            AppLog.i("[ClusterMap] Launched ClusterMapActivity on displayId=$displayId (mode=$mode)")
        } catch (e: Exception) {
            AppLog.e("[ClusterMap] Failed to launch ClusterMapActivity on displayId=$displayId: ${e.message}", e)
        }
    }
}
