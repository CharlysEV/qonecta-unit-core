package com.andrerinas.openheadunit.cluster

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
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
import android.widget.TextView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lightweight secondary-display Activity rendered on the Leapmotor C10 instrument cluster
 * (`Display 2`, `1920×720`, right-hand clear window `x = 1380..1920`, `y = 135..606`).
 *
 * Strictly event-driven — zero periodic `1 Hz` timers and zero view reallocations:
 * - Full hardware-decoded map (`SurfaceView`) for Android Auto (`1280×720`) or CarPlay (`720×720`).
 * - Top discrete Turn-by-Turn maneuver pill (shown only when actively navigating).
 * - Bottom discrete Now Playing card (shown for 5 seconds only when the track changes).
 */
class ClusterMapActivity : Activity(), SurfaceHolder.Callback {

    private var surfaceView: SurfaceView? = null
    private var overlaySlot: FrameLayout? = null

    // Top discrete Turn-by-Turn maneuver pill
    private var navPill: LinearLayout? = null
    private var navArrowText: TextView? = null
    private var navDistanceText: TextView? = null
    private var navRoadText: TextView? = null
    private var navEtaText: TextView? = null

    // Bottom discrete 5-second Now Playing card (shown only on track change)
    private var mediaPopupBanner: LinearLayout? = null
    private var popupArtImage: ImageView? = null
    private var popupFallbackIcon: TextView? = null
    private var popupAppBadge: TextView? = null
    private var popupTitleText: TextView? = null
    private var popupArtistText: TextView? = null

    private var mode: ClusterMapController.Mode = ClusterMapController.Mode.ANDROID_AUTO

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

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
        }
        val sv = SurfaceView(this)
        sv.holder.addCallback(this)
        container.addView(sv, buildSurfaceLayoutParams(mode, sv))

        val squareSize = resolveClusterSquareSize()
        // Calibrated to Leapmotor C10 Display 2 (1920×720):
        // - x = 1380..1920 (540px = 75% of 720px) is the clear right-hand map window.
        // - y = 0..135 (~19%) is the top telltale mask; y = 606..720 (~15%) is the bottom status bar.
        val slotWidth = (squareSize * 0.75f).roundToInt()
        val topSafeMargin = (squareSize * 0.19f).roundToInt()
        val bottomSafeMargin = (squareSize * 0.15f).roundToInt()
        val slot = FrameLayout(this).apply { clipChildren = false }
        buildDiscreteOverlays(slot, slotWidth)
        container.addView(
            slot,
            FrameLayout.LayoutParams(slotWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.END).apply {
                topMargin = topSafeMargin
                bottomMargin = bottomSafeMargin
            },
        )

        setContentView(container)

        surfaceView = sv
        overlaySlot = slot
        updateSurfacePosition()
        refreshOverlayUi()

        AppLog.i("[ClusterMapActivity] Created on displayId=${currentDisplayId()} (mode=$mode, square=$squareSize)")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyModeLayout(parseMode(intent))
        refreshOverlayUi()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (!ClusterMapController.isActive) {
            finish()
            return
        }
        refreshOverlayUi()
    }

    override fun onDestroy() {
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
                ClusterMapController.Mode.ANDROID_AUTO -> shiftX
                ClusterMapController.Mode.CARPLAY -> 80f + shiftX
            }
            sv.translationY = shiftY
        }
        overlaySlot?.translationX = shiftX
        overlaySlot?.translationY = shiftY
    }

    internal fun refreshOverlayUi() {
        val settings = runCatching { App.provide(applicationContext).settings }.getOrNull()
        val turnOverlayEnabled = settings?.clusterTurnCardOverlay != false
        val nav = ClusterMapController.navState
        val media = ClusterMapController.mediaState

        val showNavPill = ClusterMapController.shouldShowTurnPill(mode, turnOverlayEnabled, nav.isNavigating)
        navPill?.visibility = if (showNavPill) View.VISIBLE else View.GONE
        if (showNavPill) {
            navArrowText?.text = resolveTurnSymbol(nav.nextEventType, nav.turnSide)
            navDistanceText?.text = formatNavDistance(nav.distanceMeters)
            navRoadText?.text = nav.road.takeIf { it.isNotBlank() && it != "—" } ?: nav.actionText
            val etaParts = mutableListOf<String>()
            nav.estimatedArrival?.takeIf { it.isNotBlank() }?.let { etaParts.add(it) }
            nav.totalDistanceMeters?.takeIf { it > 0 }?.let { etaParts.add(formatNavDistance(it)) }
            val etaStr = etaParts.joinToString(" · ")
            navEtaText?.text = etaStr
            navEtaText?.visibility = if (etaStr.isNotBlank()) View.VISIBLE else View.GONE
        }

        val showMediaPopup = ClusterMapController.mediaPopupVisible && media.hasTrack
        mediaPopupBanner?.visibility = if (showMediaPopup) View.VISIBLE else View.GONE
        if (showMediaPopup) {
            val protoTag = if (mode == ClusterMapController.Mode.CARPLAY) "APPLE CARPLAY" else media.appName.uppercase(Locale.ROOT)
            popupAppBadge?.text = protoTag
            if (media.albumArt != null && !media.albumArt.isRecycled) {
                popupArtImage?.setImageBitmap(media.albumArt)
                popupArtImage?.visibility = View.VISIBLE
                popupFallbackIcon?.visibility = View.GONE
            } else {
                popupArtImage?.setImageDrawable(null)
                popupArtImage?.visibility = View.GONE
                popupFallbackIcon?.visibility = View.VISIBLE
            }
            popupTitleText?.text = media.title
            popupArtistText?.text = media.artist
            popupArtistText?.visibility = if (media.artist.isNotBlank()) View.VISIBLE else View.GONE
        }
    }

    private fun buildDiscreteOverlays(slot: FrameLayout, slotWidth: Int) {
        val accent = Color.parseColor("#00E5C7")

        // 1. Top discrete Turn-by-Turn maneuver pill
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (slotWidth * 0.035f).roundToInt().coerceAtLeast(14)
            val vPad = (slotWidth * 0.022f).roundToInt().coerceAtLeast(10)
            setPadding(hPad, vPad, hPad, vPad)
            background = roundedBg(Color.parseColor("#E6101822"), 22f, 2, accent)
            visibility = View.GONE
        }
        val arrowBoxSize = (slotWidth * 0.115f).roundToInt().coerceAtLeast(46)
        val nArrow = TextView(this).apply {
            text = "↑"
            setTextColor(Color.parseColor("#0B0F14"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, slotWidth * 0.064f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(arrowBoxSize, arrowBoxSize).apply {
                rightMargin = (slotWidth * 0.030f).roundToInt()
            }
            background = roundedBg(accent, 16f, 0, 0)
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
            text = ""
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, slotWidth * 0.048f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
        }
        val nEta = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#9BAAB9"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, slotWidth * 0.032f)
            maxLines = 1
        }
        nTopRow.addView(nDist)
        nTopRow.addView(nEta)
        val nRoad = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#F2F6FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, slotWidth * 0.038f)
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
                val m = (slotWidth * 0.030f).roundToInt()
                setMargins(m, (slotWidth * 0.014f).roundToInt(), m, 0)
            }
        )
        navPill = nav
        navArrowText = nArrow
        navDistanceText = nDist
        navRoadText = nRoad
        navEtaText = nEta

        // 2. Bottom discrete 5-second Now Playing card (never blocks map center or top turn card)
        val popup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (slotWidth * 0.034f).roundToInt().coerceAtLeast(14)
            val vPad = (slotWidth * 0.024f).roundToInt().coerceAtLeast(10)
            setPadding(hPad, vPad, hPad, vPad)
            background = roundedBg(Color.parseColor("#EC101822"), 22f, 2, accent)
            visibility = View.GONE
        }
        val artSize = (slotWidth * 0.145f).roundToInt().coerceAtLeast(56)
        val artBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(artSize, artSize).apply {
                rightMargin = (slotWidth * 0.030f).roundToInt()
            }
            background = roundedBg(Color.parseColor("#1B2836"), 14f, 1, Color.parseColor("#2A3B4E"))
            clipToOutline = true
        }
        val pFallback = TextView(this).apply {
            text = "♫"
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, slotWidth * 0.068f)
            gravity = Gravity.CENTER
        }
        val pArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        artBox.addView(pFallback, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        artBox.addView(pArt, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val pCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pApp = TextView(this).apply {
            text = "ANDROID AUTO"
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, slotWidth * 0.025f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
            maxLines = 1
        }
        val pTitle = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#F2F6FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, slotWidth * 0.042f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val pArtist = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#B6C5D6"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, slotWidth * 0.032f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        pCol.addView(pApp)
        pCol.addView(pTitle)
        pCol.addView(pArtist)
        popup.addView(artBox)
        popup.addView(pCol)
        slot.addView(
            popup,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                val m = (slotWidth * 0.030f).roundToInt()
                setMargins(m, 0, m, (slotWidth * 0.016f).roundToInt())
            }
        )
        mediaPopupBanner = popup
        popupArtImage = pArt
        popupFallbackIcon = pFallback
        popupAppBadge = pApp
        popupTitleText = pTitle
        popupArtistText = pArtist
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
                FrameLayout.LayoutParams(streamWidth, squareSize, Gravity.TOP or Gravity.END)
            }
            ClusterMapController.Mode.CARPLAY -> {
                sv.translationX = 80f + shiftX
                sv.translationY = shiftY
                FrameLayout.LayoutParams(squareSize, squareSize, Gravity.TOP or Gravity.END)
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

    private fun roundedBg(fillColor: Int, radiusPx: Float, strokeWidthPx: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radiusPx
            if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
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
