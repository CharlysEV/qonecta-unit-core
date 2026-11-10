package com.andrerinas.openheadunit.cluster

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.headqlink.unit.extended.LeapVehicleDataSource
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Secondary display activity rendered on the Leapmotor C10 instrument cluster (`Display 2`,
 * `1920x720`).
 *
 * Uses `FLAG_NOT_FOCUSABLE` and `FLAG_NOT_TOUCHABLE` so it never steals focus or touch events from
 * the primary center screen (`Display 0`), and aligns both the map viewport and all rich overlays
 * into the right-hand `720x720` square of the C10 instrument cluster:
 * - Welcome / Connecting standby card when opening Qonecta Unit
 * - Live Turn-by-Turn maneuver pill over the map
 * - Automatic 5-second Now Playing card popup on track change + pinned Music page
 * - Qonecta+ Extendido C10 live telemetry + session diagnostics page (cycled with 2 clicks on the
 *   steering wheel Play/Pause button)
 */
class ClusterMapActivity : Activity(), SurfaceHolder.Callback {

    private var rootLayout: FrameLayout? = null
    private var surfaceView: SurfaceView? = null
    private var overlaySlot: FrameLayout? = null

    // 1. Welcome / Connecting Card
    private var welcomeCard: LinearLayout? = null
    private var welcomeBadge: TextView? = null
    private var welcomeTitle: TextView? = null
    private var welcomeSubtitle: TextView? = null
    private var welcomeTelemetryPill: TextView? = null

    // 2. Top Turn-by-Turn Navigation Pill (over Map)
    private var navPill: LinearLayout? = null
    private var navArrowText: TextView? = null
    private var navDistanceText: TextView? = null
    private var navRoadText: TextView? = null
    private var navEtaText: TextView? = null

    // 3. Bottom Page Toast / Pill on Map
    private var mapFooterBadge: TextView? = null

    // 4. Now Playing Music Card (pinned on ClusterPage.MUSIC) + Compact 5s Song Change Popup (over Map)
    private var musicCard: LinearLayout? = null
    private var musicHeaderBadge: TextView? = null
    private var musicArtImage: ImageView? = null
    private var musicFallbackIcon: TextView? = null
    private var musicTitleText: TextView? = null
    private var musicArtistText: TextView? = null
    private var musicAlbumText: TextView? = null
    private var musicProgressBar: ProgressBar? = null
    private var musicTimeText: TextView? = null
    private var musicFooterHint: TextView? = null

    private var mediaPopupBanner: LinearLayout? = null
    private var popupArtImage: ImageView? = null
    private var popupFallbackIcon: TextView? = null
    private var popupAppBadge: TextView? = null
    private var popupTitleText: TextView? = null
    private var popupArtistText: TextView? = null
    private var popupAlbumText: TextView? = null

    // 5. Qonecta+ Extendido C10 Telemetry + Session Diagnostics Card (ClusterPage.EXTENDED)
    private var extendedCard: LinearLayout? = null
    private var extSocValue: TextView? = null
    private var extRangeValue: TextView? = null
    private var extPowerValue: TextView? = null
    private var extSpeedValue: TextView? = null
    private var extModeValue: TextView? = null
    private var extTempValue: TextView? = null
    private var extSessionSummary: TextView? = null

    private var mode: ClusterMapController.Mode = ClusterMapController.Mode.ANDROID_AUTO
    private val uiHandler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            refreshOverlayUi()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        hideSystemBars()

        mode = parseMode(intent)
        ClusterMapController.onActivityCreated(this)
        LeapVehicleDataSource.start(applicationContext)

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
        }
        val sv = SurfaceView(this)
        sv.holder.addCallback(this)
        container.addView(sv, buildSurfaceLayoutParams(mode, sv))

        val squareSize = resolveClusterSquareSize()
        // On Leapmotor C10 Display 2 (1920x720):
        // - x = 1150..1380 is the translucent ADAS fade gradient
        // - x = 1380..1920 (540px wide, centered at x=1650) is the clear right-hand map window
        // - y = 0..135 is the native top telltale mask + grey gradient
        // - y = 606..720 is the native bottom status bar ("READY Sport 15°C 16:16")
        val slotWidth = (squareSize * 0.75f).roundToInt()
        val topSafeMargin = (squareSize * 0.19f).roundToInt()
        val bottomSafeMargin = (squareSize * 0.15f).roundToInt()
        val slot = FrameLayout(this).apply {
            clipChildren = false
        }
        buildClusterOverlays(slot, slotWidth)
        container.addView(
            slot,
            FrameLayout.LayoutParams(slotWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.END).apply {
                topMargin = topSafeMargin
                bottomMargin = bottomSafeMargin
            },
        )

        setContentView(container)

        rootLayout = container
        surfaceView = sv
        overlaySlot = slot
        updateSurfacePosition()

        refreshOverlayUi()
        AppLog.i("[ClusterMapActivity] Created on displayId=${currentDisplayId()} (mode=$mode, square=$squareSize)")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newMode = parseMode(intent)
        applyModeLayout(newMode)
        refreshOverlayUi()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (!ClusterMapController.isActive) {
            finish()
            return
        }
        uiHandler.removeCallbacks(tickRunnable)
        uiHandler.post(tickRunnable)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(tickRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(tickRunnable)
        surfaceView?.holder?.removeCallback(this)
        ClusterMapController.onActivityDestroyed(this)
        super.onDestroy()
    }

    internal fun applyModeLayout(newMode: ClusterMapController.Mode) {
        mode = newMode
        val sv = surfaceView ?: return
        sv.layoutParams = buildSurfaceLayoutParams(newMode, sv)
        updateSurfacePosition()
        sv.requestLayout()
    }

    internal fun updateSurfacePosition() {
        val settings = runCatching { App.provide(applicationContext).settings }.getOrNull()
        val shiftX = (settings?.clusterMarkerX ?: 0) * 40f
        val shiftY = (settings?.clusterMarkerY ?: 0) * 25f
        surfaceView?.let { sv ->
            sv.translationX = when (mode) {
                // Full 1280x720 frame aligned to Gravity.END spans screenX = 640..1920;
                // UiConfig content_insets centers the car marker and route at screenX = 1660,
                // and shiftX also moves the SurfaceView immediately in real time.
                ClusterMapController.Mode.ANDROID_AUTO -> shiftX
                // Apple CarPlay alt stream (111) is 720x720 (screenX = 1200..1920, center 1560).
                // Shifting +80px right moves CarPlay's center to screenX = 1640 (middle of the clear 1380..1920 window).
                ClusterMapController.Mode.CARPLAY -> 80f + shiftX
            }
            sv.translationY = shiftY
        }
        overlaySlot?.translationX = shiftX
        overlaySlot?.translationY = shiftY
    }

    private fun resolveClusterSquareSize(): Int {
        val size = Point(1920, 720)
        runCatching {
            val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }
            @Suppress("DEPRECATION")
            d?.getRealSize(size)
        }
        val displayW = size.x.coerceAtLeast(720)
        val displayH = size.y.coerceAtLeast(480)
        return minOf(displayW, displayH)
    }

    private fun buildSurfaceLayoutParams(
        targetMode: ClusterMapController.Mode,
        sv: SurfaceView,
    ): FrameLayout.LayoutParams {
        val squareSize = resolveClusterSquareSize()
        val settings = runCatching { App.provide(applicationContext).settings }.getOrNull()
        val shiftX = (settings?.clusterMarkerX ?: 0) * 40f
        val shiftY = (settings?.clusterMarkerY ?: 0) * 25f
        return when (targetMode) {
            ClusterMapController.Mode.ANDROID_AUTO -> {
                val streamWidth = (squareSize * 16) / 9
                sv.translationX = shiftX
                sv.translationY = shiftY
                FrameLayout.LayoutParams(
                    streamWidth,
                    squareSize,
                    Gravity.TOP or Gravity.END,
                )
            }
            ClusterMapController.Mode.CARPLAY -> {
                sv.translationX = 80f + shiftX
                sv.translationY = shiftY
                FrameLayout.LayoutParams(
                    squareSize,
                    squareSize,
                    Gravity.TOP or Gravity.END,
                )
            }
        }
    }

    private fun buildClusterOverlays(slot: FrameLayout, squareSize: Int) {
        val pad = (squareSize * 0.045f).roundToInt().coerceAtLeast(24)

        // 1. Welcome / Connecting Card
        val welcome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            background = roundedBg(Color.parseColor("#F20D131A"), 28f, 2, Color.parseColor("#2A3B4E"))
        }
        val wBadge = TextView(this).apply {
            text = "QONECTA UNIT  ·  LEAPMOTOR C10"
            setTextColor(Color.parseColor("#00E5C7"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.024f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
        }
        val wIcon = ImageView(this).apply {
            setImageResource(R.drawable.hql_logo)
            val iconSz = (squareSize * 0.12f).roundToInt()
            layoutParams = LinearLayout.LayoutParams(iconSz, iconSz).apply {
                topMargin = (squareSize * 0.03f).roundToInt()
                bottomMargin = (squareSize * 0.025f).roundToInt()
            }
        }
        val wTitle = TextView(this).apply {
            text = "Bienvenido a Qonecta Unit"
            setTextColor(Color.parseColor("#F2F6FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.048f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val wSub = TextView(this).apply {
            text = "Conecta tu móvil por Wi-Fi o USB para proyectar Mapa, Música y Telemetría C10"
            setTextColor(Color.parseColor("#9BAAB9"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.028f)
            gravity = Gravity.CENTER
            setPadding(0, (squareSize * 0.018f).roundToInt(), 0, (squareSize * 0.03f).roundToInt())
        }
        val wTelemetry = TextView(this).apply {
            text = "LEAPMOTOR C10  ·  LISTO"
            setTextColor(Color.parseColor("#00E5C7"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.026f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(
                (squareSize * 0.035f).roundToInt(),
                (squareSize * 0.016f).roundToInt(),
                (squareSize * 0.035f).roundToInt(),
                (squareSize * 0.016f).roundToInt(),
            )
            background = roundedBg(Color.parseColor("#182532"), 999f, 1, Color.parseColor("#00E5C7"))
        }
        welcome.addView(wBadge)
        welcome.addView(wIcon)
        welcome.addView(wTitle)
        welcome.addView(wSub)
        welcome.addView(wTelemetry)
        slot.addView(
            welcome,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ).apply {
                setMargins(pad, pad, pad, pad)
            }
        )
        welcomeCard = welcome
        welcomeBadge = wBadge
        welcomeTitle = wTitle
        welcomeSubtitle = wSub
        welcomeTelemetryPill = wTelemetry

        // 2. Top Turn-by-Turn Navigation Pill (over Map)
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (squareSize * 0.032f).roundToInt()
            val vPad = (squareSize * 0.020f).roundToInt()
            setPadding(hPad, vPad, hPad, vPad)
            background = roundedBg(Color.parseColor("#E8101822"), 24f, 2, Color.parseColor("#00E5C7"))
            visibility = View.GONE
        }
        val nArrow = TextView(this).apply {
            text = "↑"
            setTextColor(Color.parseColor("#0B0F14"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.052f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val box = (squareSize * 0.095f).roundToInt()
            layoutParams = LinearLayout.LayoutParams(box, box).apply {
                rightMargin = (squareSize * 0.025f).roundToInt()
            }
            background = roundedBg(Color.parseColor("#00E5C7"), 18f, 0, 0)
        }
        val nTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nDist = TextView(this).apply {
            text = "200 m"
            setTextColor(Color.parseColor("#00E5C7"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.038f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nEta = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#9BAAB9"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.025f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        nTopRow.addView(nDist)
        nTopRow.addView(nEta)
        val nRoad = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#F2F6FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.031f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        nTextCol.addView(nTopRow)
        nTextCol.addView(nRoad)
        nav.addView(nArrow)
        nav.addView(nTextCol)
        slot.addView(
            nav,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply {
                val m = (squareSize * 0.025f).roundToInt()
                setMargins(m, (squareSize * 0.01f).roundToInt(), m, 0)
            }
        )
        navPill = nav
        navArrowText = nArrow
        navDistanceText = nDist
        navRoadText = nRoad
        navEtaText = nEta

        // 3. Map Footer Subtle Badge
        val mapBadge = TextView(this).apply {
            text = "Qonecta Unit · 2 toques Play volante = Música / Extendido"
            setTextColor(Color.parseColor("#CC9BAAB9"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.020f)
            gravity = Gravity.CENTER
            val hp = (squareSize * 0.025f).roundToInt()
            val vp = (squareSize * 0.010f).roundToInt()
            setPadding(hp, vp, hp, vp)
            background = roundedBg(Color.parseColor("#B80D131A"), 999f, 1, Color.parseColor("#332A3B4E"))
            visibility = View.GONE
        }
        slot.addView(
            mapBadge,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                bottomMargin = (squareSize * 0.015f).roundToInt()
            }
        )
        mapFooterBadge = mapBadge

        // 3b. Compact 5-second Song Change Popup Card over the Map (does not hide the map or Turn-by-Turn card)
        val popupCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hp = (squareSize * 0.032f).roundToInt()
            val vp = (squareSize * 0.024f).roundToInt()
            setPadding(hp, vp, hp, vp)
            background = roundedBg(Color.parseColor("#F2121B24"), 24f, 2, Color.parseColor("#00E5C7"))
            visibility = View.GONE
        }
        val popupArtSz = (squareSize * 0.175f).roundToInt()
        val popupArtBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(popupArtSz, popupArtSz).apply {
                rightMargin = (squareSize * 0.030f).roundToInt()
            }
            background = roundedBg(Color.parseColor("#1B2836"), 18f, 1, Color.parseColor("#2A3B4E"))
            clipToOutline = true
        }
        val pFallback = TextView(this).apply {
            text = "♫"
            setTextColor(Color.parseColor("#00E5C7"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.075f)
            gravity = Gravity.CENTER
        }
        val pArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        popupArtBox.addView(pFallback, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        popupArtBox.addView(pArt, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val popupTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pApp = TextView(this).apply {
            text = "SPOTIFY"
            setTextColor(Color.parseColor("#00E5C7"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.020f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
            maxLines = 1
        }
        val pTitle = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#F2F6FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.034f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (squareSize * 0.004f).roundToInt(), 0, 0)
        }
        val pArtist = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#B6C5D6"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.026f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (squareSize * 0.004f).roundToInt(), 0, 0)
        }
        val pAlbum = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#7E91A6"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.021f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (squareSize * 0.002f).roundToInt(), 0, 0)
        }
        popupTextCol.addView(pApp)
        popupTextCol.addView(pTitle)
        popupTextCol.addView(pArtist)
        popupTextCol.addView(pAlbum)
        popupCard.addView(popupArtBox)
        popupCard.addView(popupTextCol)
        slot.addView(
            popupCard,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL,
            ).apply {
                val m = (squareSize * 0.035f).roundToInt()
                setMargins(m, (squareSize * 0.08f).roundToInt(), m, 0)
            }
        )
        mediaPopupBanner = popupCard
        popupArtImage = pArt
        popupFallbackIcon = pFallback
        popupAppBadge = pApp
        popupTitleText = pTitle
        popupArtistText = pArtist
        popupAlbumText = pAlbum

        // 4. Now Playing Music Card (pinned on ClusterPage.MUSIC)
        val music = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            background = roundedBg(Color.parseColor("#F00E151D"), 28f, 2, Color.parseColor("#00E5C7"))
            visibility = View.GONE
        }
        val mHeader = TextView(this).apply {
            text = "🎵  REPRODUCIENDO AHORA  ·  ANDROID AUTO"
            setTextColor(Color.parseColor("#00E5C7"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.023f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.10f
            gravity = Gravity.CENTER
        }
        val artBoxSize = (squareSize * 0.34f).roundToInt()
        val artFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(artBoxSize, artBoxSize).apply {
                topMargin = (squareSize * 0.028f).roundToInt()
                bottomMargin = (squareSize * 0.028f).roundToInt()
            }
            background = roundedBg(Color.parseColor("#182430"), 24f, 2, Color.parseColor("#2A3B4E"))
            clipToOutline = true
        }
        val mFallback = TextView(this).apply {
            text = "♫"
            setTextColor(Color.parseColor("#00E5C7"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.12f)
            gravity = Gravity.CENTER
        }
        val mArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        artFrame.addView(mFallback, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        artFrame.addView(mArt, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val mTitle = TextView(this).apply {
            text = "Sin reproducción activa"
            setTextColor(Color.parseColor("#F2F6FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.042f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val mArtist = TextView(this).apply {
            text = "Inicia Spotify, YouTube Music o tu reproductor favorito"
            setTextColor(Color.parseColor("#A7B6C7"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.030f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (squareSize * 0.010f).roundToInt(), 0, 0)
        }
        val mAlbum = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#6E8196"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.024f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (squareSize * 0.006f).roundToInt(), 0, (squareSize * 0.020f).roundToInt())
        }
        val mProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5C7"))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#253545"))
            layoutParams = LinearLayout.LayoutParams(
                (squareSize * 0.72f).roundToInt(),
                (squareSize * 0.014f).roundToInt(),
            )
        }
        val mTime = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#8CA0B3"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.022f)
            gravity = Gravity.CENTER
            setPadding(0, (squareSize * 0.010f).roundToInt(), 0, (squareSize * 0.016f).roundToInt())
        }
        val mHint = TextView(this).apply {
            text = "Pantalla 2/3 · Doble clic en Play del volante para Telemetría C10"
            setTextColor(Color.parseColor("#7E91A6"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.020f)
            gravity = Gravity.CENTER
        }
        music.addView(mHeader)
        music.addView(artFrame)
        music.addView(mTitle)
        music.addView(mArtist)
        music.addView(mAlbum)
        music.addView(mProgress)
        music.addView(mTime)
        music.addView(mHint)
        slot.addView(
            music,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ).apply {
                setMargins(pad, pad, pad, pad)
            }
        )
        musicCard = music
        musicHeaderBadge = mHeader
        musicArtImage = mArt
        musicFallbackIcon = mFallback
        musicTitleText = mTitle
        musicArtistText = mArtist
        musicAlbumText = mAlbum
        musicProgressBar = mProgress
        musicTimeText = mTime
        musicFooterHint = mHint

        // 5. Qonecta+ Extendido C10 Telemetry + Session Diagnostics Card (ClusterPage.EXTENDED)
        val ext = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            background = roundedBg(Color.parseColor("#F40D131A"), 28f, 2, Color.parseColor("#00E5C7"))
            visibility = View.GONE
        }
        val extHeader = TextView(this).apply {
            text = "⚡  QONECTA+ EXTENDIDO  ·  TELEMETRÍA C10 EN VIVO"
            setTextColor(Color.parseColor("#00E5C7"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.023f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.10f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (squareSize * 0.022f).roundToInt())
        }
        ext.addView(extHeader)

        fun metricCell(labelStr: String): Pair<LinearLayout, TextView> {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val cp = (squareSize * 0.018f).roundToInt()
                setPadding(cp, cp, cp, cp)
                background = roundedBg(Color.parseColor("#16212D"), 18f, 1, Color.parseColor("#26384B"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    val m = (squareSize * 0.008f).roundToInt()
                    setMargins(m, m, m, m)
                }
            }
            val valTv = TextView(this).apply {
                text = "—"
                setTextColor(Color.parseColor("#F2F6FA"))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.038f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                maxLines = 1
            }
            val lblTv = TextView(this).apply {
                text = labelStr
                setTextColor(Color.parseColor("#8CA0B3"))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.020f)
                gravity = Gravity.CENTER
                maxLines = 1
            }
            cell.addView(valTv)
            cell.addView(lblTv)
            return cell to valTv
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (socCell, socTv) = metricCell("BATERÍA SOC")
        val (rngCell, rngTv) = metricCell("AUTONOMÍA EV")
        val (pwrCell, pwrTv) = metricCell("POTENCIA")
        row1.addView(socCell)
        row1.addView(rngCell)
        row1.addView(pwrCell)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (spdCell, spdTv) = metricCell("VELOCIDAD")
        val (modCell, modTv) = metricCell("MODO C10")
        val (tmpCell, tmpTv) = metricCell("CLIMA / EXT")
        row2.addView(spdCell)
        row2.addView(modCell)
        row2.addView(tmpCell)

        ext.addView(row1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        ext.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val sessionBox = TextView(this).apply {
            setTextColor(Color.parseColor("#C7D4E2"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.023f)
            setLineSpacing(4f, 1.15f)
            val sp = (squareSize * 0.024f).roundToInt()
            setPadding(sp, sp, sp, sp)
            background = roundedBg(Color.parseColor("#131C26"), 18f, 1, Color.parseColor("#243447"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply {
                val m = (squareSize * 0.012f).roundToInt()
                setMargins(m, m, m, m)
            }
        }
        ext.addView(sessionBox)

        val extFooter = TextView(this).apply {
            text = "Pantalla 3/4 · 2 toques Play volante → Cuadro Original C10 (4/4)"
            setTextColor(Color.parseColor("#00E5C7"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, squareSize * 0.020f)
            gravity = Gravity.CENTER
        }
        ext.addView(extFooter)

        slot.addView(
            ext,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ).apply {
                setMargins(pad, pad, pad, pad)
            }
        )
        extendedCard = ext
        extSocValue = socTv
        extRangeValue = rngTv
        extPowerValue = pwrTv
        extSpeedValue = spdTv
        extModeValue = modTv
        extTempValue = tmpTv
        extSessionSummary = sessionBox
    }

    internal fun refreshOverlayUi() {
        com.headqlink.unit.extended.QonectaTheme.ensureLoaded(this)
        val themeAccent = com.headqlink.unit.extended.QonectaTheme.accent
        welcomeTelemetryPill?.setTextColor(themeAccent)
        musicHeaderBadge?.setTextColor(themeAccent)
        musicFooterHint?.setTextColor(themeAccent)
        navArrowText?.setTextColor(themeAccent)
        navDistanceText?.setTextColor(themeAccent)
        mapFooterBadge?.setTextColor(themeAccent)
        val nowElapsed = SystemClock.elapsedRealtime()
        val hasVideo = ClusterMapController.hasVideoStream
        val page = ClusterMapController.currentPage
        val session = ClusterMapController.sessionInfo
        val media = ClusterMapController.mediaState
        val nav = ClusterMapController.navState
        val snap = LeapVehicleDataSource.snapshot(allowDemoFallback = true)

        // 1. Welcome / Connecting Card when video stream hasn't started yet and user is on MAP page
        val showWelcome = !hasVideo && page == ClusterMapController.ClusterPage.MAP
        welcomeCard?.visibility = if (showWelcome) View.VISIBLE else View.GONE
        if (showWelcome) {
            if (session.phase == ClusterMapController.ConnectionPhase.CONNECTING) {
                welcomeTitle?.text = "Conectando…"
                welcomeSubtitle?.text = "${session.deviceName}  ·  ${session.protocolLabel}"
            } else {
                welcomeTitle?.text = "Bienvenido a Qonecta Unit"
                welcomeSubtitle?.text =
                    "Conecta tu móvil por Wi-Fi o USB para ver el Mapa, Música y Telemetría en el cuadro"
            }
            val socStr = if (snap.hasBatteryData) "${snap.socPct.roundToInt()}%" else "—"
            val rngStr = if (!snap.effectiveRangeKm.isNaN()) "${snap.effectiveRangeKm.roundToInt()} km" else "—"
            val tmpStr = if (!snap.tempLeftC.isNaN()) String.format(Locale.US, "%.0f°C", snap.tempLeftC) else "21°C"
            welcomeTelemetryPill?.text =
                "⚡ BATERÍA $socStr ($rngStr)  ·  MODO ${snap.driveModeLabel().uppercase()}  ·  $tmpStr"
        }

        // 2. Check if 5-second Now Playing popup is active on MAP page (compact floating banner over map)
        val showMediaPopup =
            page == ClusterMapController.ClusterPage.MAP &&
                media.hasTrack &&
                nowElapsed < ClusterMapController.mediaPopupUntilElapsedMs

        mediaPopupBanner?.visibility = if (showMediaPopup) View.VISIBLE else View.GONE
        if (showMediaPopup) {
            val protoTag = if (mode == ClusterMapController.Mode.CARPLAY) "APPLE CARPLAY" else media.appName.uppercase()
            popupAppBadge?.setTextColor(themeAccent)
            popupAppBadge?.text = protoTag
            if (media.albumArt != null && !media.albumArt.isRecycled) {
                popupArtImage?.setImageBitmap(media.albumArt)
                popupArtImage?.visibility = View.VISIBLE
                popupFallbackIcon?.visibility = View.GONE
            } else {
                popupArtImage?.visibility = View.GONE
                popupFallbackIcon?.visibility = View.VISIBLE
            }
            popupTitleText?.text = media.title.ifBlank { "En reproducción" }
            popupArtistText?.text = media.artist
            popupArtistText?.visibility = if (media.artist.isNotBlank()) View.VISIBLE else View.GONE
            popupAlbumText?.text = media.album
            popupAlbumText?.visibility = if (media.album.isNotBlank()) View.VISIBLE else View.GONE
        }

        val showMusicCard = page == ClusterMapController.ClusterPage.MUSIC
        musicCard?.visibility = if (showMusicCard) View.VISIBLE else View.GONE
        if (showMusicCard) {
            val protoTag = if (mode == ClusterMapController.Mode.CARPLAY) "APPLE CARPLAY" else media.appName.uppercase()
            musicHeaderBadge?.text = "🎵  REPRODUCIENDO AHORA  ·  $protoTag"
            if (media.albumArt != null && !media.albumArt.isRecycled) {
                musicArtImage?.setImageBitmap(media.albumArt)
                musicArtImage?.visibility = View.VISIBLE
                musicFallbackIcon?.visibility = View.GONE
            } else {
                musicArtImage?.visibility = View.GONE
                musicFallbackIcon?.visibility = View.VISIBLE
            }
            musicTitleText?.text = media.title.ifBlank { "Sin título en reproducción" }
            musicArtistText?.text = media.artist.ifBlank {
                if (media.hasTrack) "Artista desconocido" else "Reproduce música desde tu móvil"
            }
            musicAlbumText?.text = media.album
            musicAlbumText?.visibility = if (media.album.isNotBlank()) View.VISIBLE else View.GONE

            val interpPosMs = if (media.isPlaying && media.updatedAtElapsedMs > 0L) {
                (media.positionMs + (nowElapsed - media.updatedAtElapsedMs)).coerceAtLeast(0L)
            } else {
                media.positionMs.coerceAtLeast(0L)
            }
            val durMs = media.durationMs.coerceAtLeast(0L)
            if (durMs > 0L) {
                val boundedPos = interpPosMs.coerceAtMost(durMs)
                musicProgressBar?.progress = ((boundedPos * 1000L) / durMs).toInt().coerceIn(0, 1000)
                musicProgressBar?.visibility = View.VISIBLE
                musicTimeText?.text = "${formatMmSs(boundedPos)}  /  ${formatMmSs(durMs)}"
                musicTimeText?.visibility = View.VISIBLE
            } else {
                musicProgressBar?.progress = if (media.isPlaying) 500 else 0
                musicTimeText?.text = if (media.isPlaying) "▶ En reproducción" else "⏸ En pausa"
            }
            musicFooterHint?.text = "Pantalla 2/4 (Música)  ·  2 toques Play volante → Extendido C10"
        }

        // 3. Top Turn-by-Turn Navigation Pill (remains visible even when the 5s compact song popup is shown!)
        val showNavPill =
            page == ClusterMapController.ClusterPage.MAP &&
                !showWelcome &&
                nav.isNavigating &&
                !(mode == ClusterMapController.Mode.ANDROID_AUTO && hasVideo) &&
                !ClusterMapController.isAutoSdkConnected
        navPill?.visibility = if (showNavPill) View.VISIBLE else View.GONE
        if (showNavPill) {
            navArrowText?.text = resolveTurnSymbol(nav.nextEventType, nav.turnSide)
            navDistanceText?.text = formatNavDistance(nav.distanceMeters)
            navRoadText?.text = nav.road.takeIf { it.isNotBlank() && it != "—" } ?: nav.actionText
            val etaParts = mutableListOf<String>()
            nav.estimatedArrival?.takeIf { it.isNotBlank() }?.let { etaParts.add("Llega $it") }
            nav.totalDistanceMeters?.takeIf { it > 0 }?.let { etaParts.add(formatNavDistance(it)) }
            navEtaText?.text = etaParts.joinToString(" · ")
        }

        // 4. Map Footer Badge (shown briefly for 12s when video starts so the map stays clean)
        val showMapFooter =
            page == ClusterMapController.ClusterPage.MAP &&
                hasVideo &&
                !showMediaPopup &&
                session.connectedSinceElapsedMs > 0L &&
                (nowElapsed - session.connectedSinceElapsedMs) < 12_000L
        mapFooterBadge?.visibility = if (showMapFooter) View.VISIBLE else View.GONE

        // 5. Qonecta+ Extendido C10 Telemetry + Session Diagnostics Page
        val showExtended = page == ClusterMapController.ClusterPage.EXTENDED
        extendedCard?.visibility = if (showExtended) View.VISIBLE else View.GONE
        if (showExtended) {
            extSocValue?.text = if (snap.hasBatteryData) "${snap.socPct.roundToInt()}%" else "—"
            extRangeValue?.text = if (!snap.effectiveRangeKm.isNaN()) "${snap.effectiveRangeKm.roundToInt()} km" else "—"
            extPowerValue?.text = if (!snap.powerKw.isNaN()) String.format(Locale.US, "%+.1f kW", snap.powerKw) else "0.0 kW"
            extSpeedValue?.text = "${snap.effectiveSpeedKmh.roundToInt()} km/h"
            extModeValue?.text = "${snap.gearLabel()} · ${snap.driveModeLabel()}"
            val cabT = if (!snap.tempLeftC.isNaN()) String.format(Locale.US, "%.0f°C", snap.tempLeftC) else "21°C"
            val extT = if (!snap.tempExteriorC.isNaN()) String.format(Locale.US, "%.0f°C", snap.tempExteriorC) else "19°C"
            extTempValue?.text = "$cabT / $extT"

            val sessionSec = if (session.connectedSinceElapsedMs > 0L) {
                ((nowElapsed - session.connectedSinceElapsedMs) / 1000L).coerceAtLeast(0L)
            } else {
                0L
            }
            val avgKwh = if (!snap.avgConsumptionKwh100.isNaN()) {
                String.format(Locale.US, "%.1f kWh/100km", snap.avgConsumptionKwh100)
            } else {
                "15.4 kWh/100km"
            }
            extSessionSummary?.text = buildString {
                appendLine("Dispositivo:  ${session.deviceName.ifBlank { "Conectado" }}")
                appendLine("Protocolo:    ${session.protocolLabel}")
                appendLine("Vídeo:        ${session.resolutionLabel}  ·  ${session.codecLabel} @ ${session.fps} fps")
                appendLine("Viaje C10:    ${String.format(Locale.US, "%.1f km", snap.tripDistanceKm)}  ·  Consumo: $avgKwh")
                append("Sesión:       ${formatDurationHhMmSs(sessionSec)}  ·  Qonecta Unit ${com.andrerinas.openheadunit.BuildConfig.VERSION_NAME}")
            }
        }
    }

    private fun resolveTurnSymbol(nextEventType: Int, turnSide: Int?): String {
        val left = turnSide == 1
        return when (nextEventType) {
            3 -> if (left) "↖" else "↗"
            4, 5 -> if (left) "↰" else "↱"
            6 -> "⮌"
            7, 8, 9, 10 -> if (left) "↖" else "↗"
            11, 12, 13 -> "⟳"
            16 -> "🏁"
            else -> "↑"
        }
    }

    private fun formatNavDistance(meters: Int?): String {
        if (meters == null || meters < 0) return ""
        return if (meters >= 1000) {
            String.format(Locale.US, "%.1f km", meters / 1000.0)
        } else {
            "$meters m"
        }
    }

    private fun formatMmSs(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val m = totalSec / 60L
        val s = totalSec % 60L
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun formatDurationHhMmSs(totalSec: Long): String {
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun roundedBg(fillColor: Int, radiusPx: Float, strokeWidthPx: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radiusPx
            if (strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val surface = holder.surface
        if (surface != null && surface.isValid) {
            AppLog.i("[ClusterMapActivity] Surface created on displayId=${currentDisplayId()} (mode=$mode)")
            ClusterMapController.onSurfaceAvailable(this, surface)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val surface = holder.surface
        if (surface != null && surface.isValid) {
            AppLog.i("[ClusterMapActivity] Surface changed ${width}x${height} on displayId=${currentDisplayId()} (mode=$mode)")
            ClusterMapController.onSurfaceAvailable(this, surface)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AppLog.i("[ClusterMapActivity] Surface destroyed on displayId=${currentDisplayId()} (mode=$mode)")
        holder.surface?.let { surface ->
            ClusterMapController.onSurfaceDestroyed(this, surface)
        }
    }

    private fun parseMode(intent: Intent?): ClusterMapController.Mode {
        val raw = intent?.getStringExtra(ClusterMapController.EXTRA_MODE)
        return runCatching {
            if (raw != null) ClusterMapController.Mode.valueOf(raw) else null
        }.getOrNull() ?: ClusterMapController.currentMode ?: ClusterMapController.Mode.ANDROID_AUTO
    }

    private fun currentDisplayId(): Int {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.displayId ?: -1
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay?.displayId ?: -1
            }
        }.getOrDefault(-1)
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window?.decorView?.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }
}
