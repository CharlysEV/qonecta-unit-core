package com.headqlink.unit.extended

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.headqlink.unit.probe.CarProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * El menú Extendido completo de Qonecta+ («CarHubScreen») integrado directamente en Qonecta Unit
 * tanto para Android Auto como para Apple CarPlay y la pantalla de inicio.
 *
 * Incluye:
 * 0. Ruta         — Planificador de rutas EV sin ruta predefinida artificial, con 4 Favoritos
 *                   configurables (Casa, Trabajo y 2 direcciones más), geocodificación Nominatim,
 *                   ruta OSRM, perfil grande de elevación y batería, filtro de cargadores Qonecta+
 *                   (potencia mínima kW y redes) y selección de cargador como parada o destino.
 * 1. Conducción   — Velocidad real en grande, marcha, brújula de rumbo, altitud, pendiente y temperaturas.
 * 2. Viajes       — Resumen del viaje actual en tiempo real e historial de viajes guardados.
 * 3. Instrumentos — Cuadrícula 2×2 ampliada: Velocímetro segmentado, Medidor de Fuerzas G,
 *                   Dirección/Inclinómetro y Suavidad + Cronómetro 0–50 / 0–100 km/h.
 * 4. Eficiencia   — Potencia instantánea (kW) con curva grande de 60 s, consumo (Ahora / Viaje / Media),
 *                   reparto de energía y coste / CO₂ evitado.
 * 5. Estado       — Telemetría en tiempo real del Leapmotor C10 (AutoSDK + BinderPool): anillo grande
 *                   de batería, autonomía EV/EREV, modos del coche y sonda diagnóstica USB.
 */
class ExtendedHubView(
    context: Context,
    var onClose: (() -> Unit)? = null,
    val hostedInsideProjection: Boolean = false,
    initialSplitScreen: Boolean = false,
) : LinearLayout(context) {

    enum class Tab(val title: String, val icon: String) {
        ROUTE("Ruta", "⚡"),
        DRIVE("Conducción", "🧭"),
        TRIPS("Viajes", "🛣"),
        INSTRUMENTS("Instrumentos", "⏱"),
        EFFICIENCY("Eficiencia", "🔋"),
        STATUS("Estado", "🚗"),
        RADIO("Radio", "📻"),
        TV("TV / TDT", "📺"),
        KARAOKE("Karaoke", "🎤"),
        WEB("Web / YouTube", "🌐"),
        VIDEOS("Vídeos USB", "🎬"),
        GAMES("Juegos C10", "🎮"),
        THEME("Tema Qonecta+", "🎨"),
    }

    data class FavoriteSlot(
        val index: Int,
        val key: String,
        val icon: String,
        val defaultTitle: String,
        val fixedTitle: Boolean,
        val customTitle: String,
        val address: String,
    ) {
        val displayTitle: String
            get() = if (fixedTitle) defaultTitle else customTitle.ifBlank { defaultTitle }
    }

    data class NetworkSpec(
        val key: String,
        val label: String,
        val badgeText: String,
        val badgeColor: Int,
        val darkText: Boolean = false,
    )

    data class ChargerInfo(
        val id: String,
        val name: String,
        val networkKey: String,
        val powerKw: Int,
        val volts: Int,
        val stalls: Int,
        val distFromRouteKm: Double,
        val routeProgressFraction: Float,
        val lat: Double,
        val lon: Double,
    )

    data class RoutePlan(
        val destinationName: String,
        val destLat: Double,
        val destLon: Double,
        val distanceKm: Double,
        val durationMin: Int,
        val energyKwh: Double,
        val departureSocPct: Int,
        val arrivalSocPct: Int,
        val elevationProfileM: List<Float>,
        val chargers: List<ChargerInfo>,
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val uiHandler = Handler(Looper.getMainLooper())
    private val netExecutor = Executors.newSingleThreadExecutor()
    private val density: Float
        get() = c10Density(context)

    private fun TextView.setTextSp(sp: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * density)
    }

    var isSplitScreen: Boolean = initialSplitScreen
        private set
    var aaRight: Boolean = false
        private set
    var onSplitModeChanged: ((Boolean) -> Unit)? = null
    var onSwapSideRequested: ((Boolean) -> Unit)? = null

    private var activeTab: Tab = Tab.ROUTE
    private val tabButtons = LinkedHashMap<Tab, TextView>()
    private val hubContentColumn = LinearLayout(context).apply {
        orientation = VERTICAL
    }
    private val bodyContainer = FrameLayout(context)
    private val brandTitleView = TextView(context)
    private val liveStatusBadge = TextView(context)
    private lateinit var topBarView: LinearLayout
    private lateinit var tabsScrollView: HorizontalScrollView
    private lateinit var splitToggleBtn: TextView
    private lateinit var swapSideBtn: TextView
    private lateinit var fullScreenBtn: TextView
    private var companionAaPane: View? = null
    private var isImmersiveFullscreen: Boolean = false
    private var floatingExitFullscreenBtn: TextView? = null

    // Estado del planificador de ruta (sin ruta predefinida a Puerta del Sol)
    private var socAdjustDelta = 0
    private var routeLoading = false
    private var routeError: String? = null
    private var currentRoute: RoutePlan? = null
    private var lastSearchQuery: String = ""
    private var selectedTripDetail: LeapVehicleDataSource.TripRecord? = null

    // Referencias en vivo de la tarjeta "EN CURSO" en la pestaña Viajes (evita recrear toda la lista cada segundo)
    private var tripsLiveDistTv: TextView? = null
    private var tripsLiveTimeAvgTv: TextView? = null
    private var tripsLiveConsTv: TextView? = null
    private var tripsLiveMaxSpdTv: TextView? = null
    private var tripsLivePeakGTv: TextView? = null
    private var tripsLive0to100Tv: TextView? = null
    private var tripsLiveEnergyTv: TextView? = null
    private var showHiddenTripsInUnit: Boolean = false

    // Referencias en vivo de la pestaña Estado (evita recrear el WebView del modelo 3D y que parpadeen las presiones)
    private var statusRingViewRef: BatteryRingView? = null
    private var statusPowerStatusTvRef: TextView? = null
    private var statusEnergyCurrentTvRef: TextView? = null
    private var statusBatBarViewRef: BatteryCapacityBarCanvasView? = null
    private var statusReevTextTvRef: TextView? = null
    private var statusReevBarViewRef: BatteryCapacityBarCanvasView? = null
    private var statusGearChipTvRef: TextView? = null
    private var statusModesChipTvRef: TextView? = null
    private var statusClimaChipTvRef: TextView? = null
    private var statusExtSteerChipTvRef: TextView? = null
    private var statusLastCar3dSig: String = ""

    private val companionSyncListener: () -> Unit = {
        if (attached && activeTab in setOf(Tab.TRIPS, Tab.EFFICIENCY, Tab.STATUS)) {
            renderActiveTab(resetScroll = false)
        }
    }

    // Estado del filtro y selección de cargadores (estilo ChargerFilter de Qonecta+)
    private var chargerMinKw: Int = prefs.getInt(KEY_CHARGER_MIN_KW, 0)
    private val selectedNetworks: LinkedHashSet<String> = LinkedHashSet<String>().apply {
        val raw = prefs.getString(KEY_CHARGER_NETWORKS, "").orEmpty()
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
    }
    private var selectedChargerStop: ChargerInfo? = null
    private var standaloneChargers: List<ChargerInfo> = buildDefaultChargersFor(40.4168, -3.7038, "España")
    private var powerWindowSecs: Int = 120
    private var electricityTariffEur: Double = prefs.getFloat("elec_tariff_eur", 0.18f).toDouble()

    private var attached = false
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!attached) return
            refreshLiveViews()
            companionAaPane?.invalidate()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    init {
        QonectaTheme.ensureLoaded(context)
        orientation = HORIZONTAL
        weightSum = 100f
        setBackgroundColor(QonectaTheme.bg)
        isClickable = true

        // Cabecera superior: Marca + Estado telemetría + Pantalla Partida ◫ + Cambiar Lado ⇄ + Toda la Pantalla + Cerrar
        val topBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBarView = topBar

        brandTitleView.apply {
            text = "Qonecta+ Extendido · ${LeapVehicleDataSource.getSelectedVariant(context).title}"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f * density)
            setTextColor(PanelFormat.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        topBar.addView(brandTitleView, LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })

        liveStatusBadge.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * density)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        topBar.addView(
            liveStatusBadge,
            LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) },
        )

        splitToggleBtn = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 13.5f * density)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(13), dp(7), dp(13), dp(7))
            setOnClickListener { setSplitScreenMode(!isSplitScreen) }
        }
        topBar.addView(splitToggleBtn, LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })

        swapSideBtn = TextView(context).apply {
            text = "⇄ Cambiar Lado"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 13.5f * density)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = pillDrawable(QonectaTheme.surfaceTop, QonectaTheme.accent)
            setOnClickListener { swapSplitSide() }
        }
        topBar.addView(swapSideBtn, LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })

        fullScreenBtn = TextView(context).apply {
            text = "⛶ Toda la Pantalla"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 13.5f * density)
            setTextColor(QonectaTheme.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = pillDrawable(PanelFormat.SURFACE_TOP, QonectaTheme.accent)
            setOnClickListener { setImmersiveFullscreen(true, showDefaultExitPill = true) }
        }
        topBar.addView(fullScreenBtn, LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })

        val closeBtn = TextView(context).apply {
            text = "✕ Cerrar"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 14.5f * density)
            setTextColor(PanelFormat.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(15), dp(8), dp(15), dp(8))
            background = pillDrawable(PanelFormat.SURFACE_TOP, PanelFormat.EDGE)
            setOnClickListener { onClose?.invoke() }
        }
        topBar.addView(closeBtn, LayoutParams(WRAP, WRAP))
        hubContentColumn.addView(topBar, LayoutParams(MATCH, WRAP))

        // Barra de pestañas grandes (estilo CarHubScreen + CarUi de Qonecta+ en 2560x1440)
        val tabsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(7), 0, dp(7))
        }
        tabsScrollView = tabsScroll
        val tabsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        Tab.values().forEachIndexed { idx, tab ->
            val btn = TextView(context).apply {
                text = "${tab.icon} ${tab.title}"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 14.5f * density)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(13), dp(9), dp(13), dp(9))
                setOnClickListener { selectTab(tab) }
            }
            tabButtons[tab] = btn
            tabsRow.addView(
                btn,
                LayoutParams(WRAP, dp(46)).apply {
                    if (idx > 0) leftMargin = dp(6)
                },
            )
        }
        tabsScroll.addView(tabsRow)
        hubContentColumn.addView(tabsScroll, LayoutParams(MATCH, WRAP))

        // Contenedor central
        hubContentColumn.addView(bodyContainer, LayoutParams(MATCH, 0, 1f))

        rebuildSplitContainerLayout()
        selectTab(Tab.ROUTE)
    }

    private fun rebuildSplitContainerLayout() {
        removeAllViews()
        hubContentColumn.setBackgroundColor(QonectaTheme.bg)
        val padH = if (isSplitScreen) dp(12) else dp(18)
        val padV = if (isSplitScreen) dp(10) else dp(14)
        if (!isImmersiveFullscreen) {
            hubContentColumn.setPadding(padH, padV, padH, padV)
        } else {
            hubContentColumn.setPadding(0, 0, 0, 0)
        }

        if (isSplitScreen && !hostedInsideProjection) {
            val mapPane = companionAaPane ?: createLiveAaMapCompanionPane(context) { targetTab ->
                selectTab(targetTab)
            }.also { companionAaPane = it }
            (mapPane.parent as? ViewGroup)?.removeView(mapPane)
            (hubContentColumn.parent as? ViewGroup)?.removeView(hubContentColumn)
            val divider = View(context).apply {
                setBackgroundColor(QonectaTheme.outline)
            }
            if (!aaRight) {
                addView(mapPane, LayoutParams(0, MATCH, 53f))
                addView(divider, LayoutParams(dp(2), MATCH))
                addView(hubContentColumn, LayoutParams(0, MATCH, 47f))
            } else {
                addView(hubContentColumn, LayoutParams(0, MATCH, 47f))
                addView(divider, LayoutParams(dp(2), MATCH))
                addView(mapPane, LayoutParams(0, MATCH, 53f))
            }
        } else {
            (hubContentColumn.parent as? ViewGroup)?.removeView(hubContentColumn)
            addView(hubContentColumn, LayoutParams(MATCH, MATCH))
        }
        updateSplitTopBarButtons()
    }

    private fun updateSplitTopBarButtons() {
        if (!::splitToggleBtn.isInitialized) return
        if (isSplitScreen) {
            splitToggleBtn.text = "⛶ 100% Completa"
            splitToggleBtn.setTextColor(QonectaTheme.accent)
            splitToggleBtn.background = pillDrawable(QonectaTheme.surfaceTop, QonectaTheme.accent)
            swapSideBtn.visibility = View.VISIBLE
            swapSideBtn.text = if (aaRight) "⇄ Mapa a la Izq." else "⇄ Cambiar Lado"
            fullScreenBtn.text = "⛶ Solo Contenido"
        } else {
            splitToggleBtn.text = "◫ Partir (Mapa + App)"
            splitToggleBtn.setTextColor(QonectaTheme.onAccent)
            splitToggleBtn.background = QonectaTheme.accentGradientPill(dp(999).toFloat())
            swapSideBtn.visibility = View.GONE
            fullScreenBtn.text = "⛶ Toda la Pantalla"
        }
    }

    fun setSplitScreenMode(split: Boolean, notifyHost: Boolean = true) {
        if (isSplitScreen == split) {
            updateSplitTopBarButtons()
            return
        }
        isSplitScreen = split
        if (isImmersiveFullscreen) {
            setImmersiveFullscreen(false)
        }
        rebuildSplitContainerLayout()
        refreshLiveViews()
        renderActiveTab(resetScroll = false)
        if (notifyHost) {
            onSplitModeChanged?.invoke(split)
        }
    }

    fun swapSplitSide(notifyHost: Boolean = true) {
        aaRight = !aaRight
        rebuildSplitContainerLayout()
        if (notifyHost) {
            onSwapSideRequested?.invoke(aaRight)
        }
    }

    fun setAaRightPosition(right: Boolean) {
        if (aaRight == right) return
        aaRight = right
        rebuildSplitContainerLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        LeapVehicleDataSource.start(context.applicationContext)
        com.headqlink.unit.bridgehost.CompanionBridgeHost.addSyncListener(companionSyncListener)
        uiHandler.removeCallbacks(tickRunnable)
        uiHandler.post(tickRunnable)
    }

    override fun onDetachedFromWindow() {
        attached = false
        com.headqlink.unit.bridgehost.CompanionBridgeHost.removeSyncListener(companionSyncListener)
        uiHandler.removeCallbacks(tickRunnable)
        super.onDetachedFromWindow()
    }

    fun setImmersiveFullscreen(fullscreen: Boolean, showDefaultExitPill: Boolean = false) {
        isImmersiveFullscreen = fullscreen
        topBarView.visibility = if (fullscreen) View.GONE else View.VISIBLE
        tabsScrollView.visibility = if (fullscreen) View.GONE else View.VISIBLE
        if (fullscreen) {
            hubContentColumn.setPadding(0, 0, 0, 0)
        } else {
            val padH = if (isSplitScreen) dp(12) else dp(18)
            val padV = if (isSplitScreen) dp(10) else dp(14)
            hubContentColumn.setPadding(padH, padV, padH, padV)
        }
        floatingExitFullscreenBtn?.let { bodyContainer.removeView(it) }
        floatingExitFullscreenBtn = null
        if (fullscreen && showDefaultExitPill) {
            val pill = TextView(context).apply {
                text = "🗖 Mostrar Pestañas"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * density)
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(0xCC0A0F14.toInt())
                    setStroke(dp(2), QonectaTheme.accent)
                }
                setOnClickListener { setImmersiveFullscreen(false) }
            }
            floatingExitFullscreenBtn = pill
            bodyContainer.addView(
                pill,
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(12)
                    rightMargin = dp(14)
                },
            )
        }
        requestLayout()
    }

    fun selectTab(tab: Tab) {
        if (isImmersiveFullscreen) {
            setImmersiveFullscreen(false)
        }
        val tabChanged = activeTab != tab
        if (tab == Tab.TRIPS && selectedTripDetail != null) {
            selectedTripDetail = null
        }
        activeTab = tab
        updateTabStyles()
        renderActiveTab(resetScroll = tabChanged)
    }

    private fun updateTabStyles() {
        setBackgroundColor(QonectaTheme.bg)
        hubContentColumn.setBackgroundColor(QonectaTheme.bg)
        brandTitleView.setTextColor(QonectaTheme.text)
        brandTitleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (isSplitScreen) 16.5f else 19.5f) * density)
        updateSplitTopBarButtons()
        tabButtons.forEach { (tab, view) ->
            val selected = tab == activeTab
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (isSplitScreen) 13.5f else 14.5f) * density)
            view.setTextColor(if (selected) QonectaTheme.onAccent else QonectaTheme.text)
            view.background = if (selected) {
                QonectaTheme.accentGradientPill(dp(32).toFloat())
            } else {
                QonectaTheme.chipDrawable(dp(32).toFloat(), QonectaTheme.outline)
            }
        }
    }

    private fun refreshLiveViews() {
        val snap = LeapVehicleDataSource.snapshot()
        val variant = LeapVehicleDataSource.getSelectedVariant(context)
        brandTitleView.text = if (isSplitScreen) {
            "Qonecta+ ◫ · ${variant.shortLabel}"
        } else {
            "Qonecta+ Extendido · ${variant.title}"
        }
        val isReal = snap.connected && snap.hasBatteryData && !snap.isDemo
        val socInt = if (!snap.socPct.isNaN()) snap.socPct.roundToInt() else 78
        val rangeInt = if (!snap.effectiveRangeKm.isNaN()) snap.effectiveRangeKm.roundToInt() else 359
        val spdInt = snap.effectiveSpeedKmh.roundToInt()
        liveStatusBadge.text = when {
            isSplitScreen && isReal -> "● CAN $socInt% · $spdInt km/h"
            isSplitScreen -> "● $socInt% · $rangeInt km"
            isReal -> "● CAN C10 EN VIVO · $socInt% · $spdInt km/h"
            else -> "● TELEMETRÍA C10 · $socInt% · $rangeInt km"
        }
        liveStatusBadge.setTextColor(if (isReal) QonectaTheme.accent else QonectaTheme.amber)
        liveStatusBadge.background = pillDrawable(
            if (isReal) QonectaTheme.withAlpha(QonectaTheme.accent, 0x22) else 0x1FFFB547,
            if (isReal) QonectaTheme.accent else QonectaTheme.amber,
        )

        if (activeTab == Tab.TRIPS) {
            if (selectedTripDetail == null) {
                updateTripsLiveCard(snap)
            }
            return
        }
        if (activeTab == Tab.STATUS) {
            updateStatusLiveViews(snap)
            return
        }
        if (activeTab in setOf(Tab.DRIVE, Tab.INSTRUMENTS, Tab.EFFICIENCY)) {
            renderActiveTab()
        }
    }

    private fun updateStatusLiveViews(snap: LeapVehicleDataSource.TelemetrySnapshot) {
        val variant = LeapVehicleDataSource.getSelectedVariant(context)
        val paintColor = LeapVehicleDataSource.getSelectedPaintColor(context)
        val soc = if (!snap.socPct.isNaN()) snap.socPct else 78.0
        val grossKwh = variant.batteryGrossKwh
        val currentKwh = (soc / 100.0) * grossKwh
        val evRangeKm = if (!snap.evRangeKm.isNaN() && snap.evRangeKm > 0) {
            snap.evRangeKm
        } else {
            (soc / 100.0) * variant.wltpEvRangeKm
        }
        val isReevActive = variant.isReev || snap.isReev
        val fuelPct = if (!snap.fuelPct.isNaN() && snap.fuelPct > 0) snap.fuelPct else 72.0
        val fuelLiters = (fuelPct / 100.0) * 50.0
        val fuelRangeKm = if (!snap.fuelRangeKm.isNaN() && snap.fuelRangeKm > 0) snap.fuelRangeKm else (fuelPct / 100.0) * 829.0

        statusRingViewRef?.setData(
            soc = soc,
            rangeKm = evRangeKm,
            rangeLabel = if (isReevActive || isSplitScreen) "Autonomía EV" else "Autonomía eléctrica",
            ageText = snap.chargingLabel(),
            ageColor = if (snap.chargingStatus in 1..2) QonectaTheme.green else QonectaTheme.accent,
        )
        val pKw = if (!snap.powerKw.isNaN()) snap.powerKw else 0.8
        val powerStatusText = when {
            snap.chargingStatus == 2 -> String.format(Locale.getDefault(), "⚡ Carga rápida CC activa · %.1f kW (Pico %d kW)", kotlin.math.abs(pKw), variant.peakDcChargeKw)
            snap.chargingStatus == 1 -> String.format(Locale.getDefault(), "⚡ Carga alterna CA activa · %.1f kW (Máx %.1f kW)", kotlin.math.abs(pKw), variant.acChargeKw)
            snap.effectiveSpeedKmh > 1.5 && pKw < -0.2 -> String.format(Locale.getDefault(), "⚡ Regenerando · %.1f kW", pKw)
            snap.effectiveSpeedKmh > 1.5 -> String.format(Locale.getDefault(), "⚡ Potencia %+.1f kW", pKw)
            else -> String.format(Locale.getDefault(), "⚡ %s · %+.1f kW", snap.chargingLabel(), pKw)
        }
        statusPowerStatusTvRef?.apply {
            text = powerStatusText
            setTextColor(if (pKw < 0 || snap.chargingStatus in 1..2) QonectaTheme.green else QonectaTheme.text)
        }
        statusEnergyCurrentTvRef?.text = String.format(Locale.getDefault(), "%.1f / %.1f kWh", currentKwh, grossKwh)
        statusBatBarViewRef?.updateFraction((soc / 100.0).toFloat())
        statusReevTextTvRef?.text = String.format(
            Locale.getDefault(),
            "%.0f%% · %.1f / 50 L (+%.0f km gasolina)",
            fuelPct,
            fuelLiters,
            fuelRangeKm,
        )
        statusReevBarViewRef?.updateFraction((fuelPct / 100.0).toFloat())

        val moving = snap.effectiveSpeedKmh > 1.5
        statusGearChipTvRef?.apply {
            text = if (moving) "Marcha ${snap.gearLabel()} · ${snap.effectiveSpeedKmh.roundToInt()} km/h" else "Estacionado · Marcha ${snap.gearLabel()}"
            setTextColor(if (moving) QonectaTheme.accent else QonectaTheme.text)
        }
        statusModesChipTvRef?.text = "${snap.driveModeLabel()} · Regen ${snap.regenModeLabel()}"
        val tL = if (!snap.tempLeftC.isNaN()) snap.tempLeftC else 25.0
        val tR = if (!snap.tempRightC.isNaN()) snap.tempRightC else 25.0
        val tOut = if (!snap.tempExteriorC.isNaN()) snap.tempExteriorC else 8.0
        statusClimaChipTvRef?.text = String.format(Locale.getDefault(), "%.1f °C / %.1f °C", tL, tR)
        statusExtSteerChipTvRef?.text = String.format(Locale.getDefault(), "%.1f °C · Dir. %s", tOut, snap.steeringModeLabel())

        // Solo enviar actualización al WebView 3D si cambió algún neumático, puerta, maletero o color
        val sig = buildString {
            append(paintColor.hex3d).append('|')
            append(snap.bootOpen).append('|')
            snap.doorsOpen.forEach { append(it).append(',') }
            snap.tyreBars.forEach { append(String.format(Locale.US, "%.2f", it)).append(',') }
            snap.tyreTempsC.forEach { append(it).append(',') }
        }
        if (sig != statusLastCar3dSig) {
            statusLastCar3dSig = sig
            pushCar3dState(snap, paintColor)
        }
    }

    private fun updateTripsLiveCard(snap: LeapVehicleDataSource.TelemetrySnapshot) {
        val durMin = (snap.tripDurationSec / 60L).toInt()
        val durSec = (snap.tripDurationSec % 60L).toInt()
        val liveDist = snap.tripDistanceKm.coerceAtLeast(0.0)
        val liveAvgKmh = if (snap.tripDurationSec > 10 && liveDist >= 0.05) {
            (liveDist / (snap.tripDurationSec / 3600.0)).coerceIn(0.0, 220.0)
        } else {
            0.0
        }
        val avgCons = if (!snap.avgConsumptionKwh100.isNaN() && snap.avgConsumptionKwh100 > 0 && liveDist >= 0.05) {
            snap.avgConsumptionKwh100
        } else {
            0.0
        }
        val liveMaxSpd = max(snap.maxSpeedKmh.coerceAtLeast(0.0), snap.effectiveSpeedKmh.coerceAtLeast(0.0))
        val livePeakG = if (snap.peakG > 0.01f && liveDist >= 0.01) snap.peakG else 0f
        val live0to100 = if (!snap.zeroToHundredSec.isNaN()) {
            String.format(Locale.getDefault(), "%.1f s", snap.zeroToHundredSec)
        } else {
            "-- s"
        }

        tripsLiveDistTv?.text = String.format(Locale.getDefault(), "%.1f km", liveDist)
        tripsLiveTimeAvgTv?.text = String.format(Locale.getDefault(), "   %02d:%02d min · media %.0f km/h", durMin, durSec, liveAvgKmh)
        tripsLiveConsTv?.apply {
            text = if (avgCons > 0.1) String.format(Locale.getDefault(), "🍃 %.1f kWh/100", avgCons) else "🍃 -- kWh/100"
            setTextColor(if (avgCons > 0.1) tripConsumptionColor(avgCons) else PanelFormat.DIM)
        }
        tripsLiveMaxSpdTv?.text = "${liveMaxSpd.roundToInt()} km/h"
        tripsLivePeakGTv?.text = String.format(Locale.getDefault(), "%.2f G", livePeakG)
        tripsLive0to100Tv?.text = live0to100
        tripsLiveEnergyTv?.text = String.format(Locale.getDefault(), "%.2f kWh", snap.tripEnergyKwh.coerceAtLeast(0.0))
    }

    private fun renderActiveTab(resetScroll: Boolean = false) {
        val prevScrollY = if (resetScroll) 0 else (bodyContainer.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        bodyContainer.removeAllViews()
        val snap = LeapVehicleDataSource.snapshot()
        val view = when (activeTab) {
            Tab.ROUTE -> QonectaRouteTabView(context, isSplitScreen) { renderActiveTab() }
            Tab.DRIVE -> buildDriveTab(snap)
            Tab.TRIPS -> buildTripsTab(snap)
            Tab.INSTRUMENTS -> buildInstrumentsTab(snap)
            Tab.EFFICIENCY -> buildEfficiencyTab(snap)
            Tab.STATUS -> buildStatusTab(snap)
            Tab.RADIO -> QonectaEntertainmentViews.buildRadioView(context, isSplitScreen)
            Tab.TV -> QonectaEntertainmentViews.buildTvView(context, isSplitScreen)
            Tab.KARAOKE -> QonectaEntertainmentViews.buildKaraokeView(context, isSplitScreen)
            Tab.WEB -> QonectaEntertainmentViews.buildWebView(context, isSplitScreen)
            Tab.VIDEOS -> QonectaEntertainmentViews.buildVideosView(context, isSplitScreen)
            Tab.GAMES -> QonectaEntertainmentViews.buildGamesView(context, isSplitScreen)
            Tab.THEME -> QonectaEntertainmentViews.buildThemeCustomizationView(context, isSplitScreen) {
                updateTabStyles()
                refreshLiveViews()
                renderActiveTab()
            }
        }
        bodyContainer.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        if (view is ScrollView && prevScrollY > 0) {
            view.post { view.scrollTo(0, prevScrollY) }
        }
    }

    // =========================================================================
    // GESTIÓN DE 4 FAVORITOS (Casa, Trabajo y 2 Direcciones Más)
    // =========================================================================

    private fun loadFavoriteSlots(): List<FavoriteSlot> {
        return listOf(
            FavoriteSlot(
                index = 0,
                key = "home",
                icon = "🏠",
                defaultTitle = "Casa",
                fixedTitle = true,
                customTitle = "Casa",
                address = prefs.getString("fav_addr_home", "").orEmpty(),
            ),
            FavoriteSlot(
                index = 1,
                key = "work",
                icon = "💼",
                defaultTitle = "Trabajo",
                fixedTitle = true,
                customTitle = "Trabajo",
                address = prefs.getString("fav_addr_work", "").orEmpty(),
            ),
            FavoriteSlot(
                index = 2,
                key = "fav1",
                icon = "📍",
                defaultTitle = "Dirección 1",
                fixedTitle = false,
                customTitle = prefs.getString("fav_title_fav1", "Dirección 1").orEmpty().ifBlank { "Dirección 1" },
                address = prefs.getString("fav_addr_fav1", "").orEmpty(),
            ),
            FavoriteSlot(
                index = 3,
                key = "fav2",
                icon = "⭐",
                defaultTitle = "Dirección 2",
                fixedTitle = false,
                customTitle = prefs.getString("fav_title_fav2", "Dirección 2").orEmpty().ifBlank { "Dirección 2" },
                address = prefs.getString("fav_addr_fav2", "").orEmpty(),
            ),
        )
    }

    private fun saveFavoriteSlot(slot: FavoriteSlot, newTitle: String, newAddress: String) {
        val editor = prefs.edit()
        if (!slot.fixedTitle) {
            editor.putString("fav_title_${slot.key}", newTitle.trim().ifBlank { slot.defaultTitle })
        }
        editor.putString("fav_addr_${slot.key}", newAddress.trim())
        editor.apply()
    }

    private fun showFavoriteEditorDialog(
        slot: FavoriteSlot,
        prefillAddress: String = "",
        departureSoc: Int,
    ) {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(26), dp(22), dp(26), dp(18))
            setBackgroundColor(QonectaTheme.surface)
        }

        val titleTv = TextView(context).apply {
            text = "${slot.icon}  Configurar ${slot.displayTitle}"
            setTextSp(22f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        container.addView(titleTv, LayoutParams(MATCH, WRAP))

        val subTv = TextView(context).apply {
            text = if (slot.fixedTitle) {
                "Escribe la dirección o ciudad para guardarla en «${slot.defaultTitle}» y calcular rutas con un toque."
            } else {
                "Puedes personalizar tanto el nombre del favorito como la dirección o punto de interés."
            }
            setTextSp(16f)
            setTextColor(QonectaTheme.dim)
            setPadding(0, dp(6), 0, dp(14))
        }
        container.addView(subTv, LayoutParams(MATCH, WRAP))

        var nameInput: EditText? = null
        if (!slot.fixedTitle) {
            val nameInputView = EditText(context).apply {
                setText(slot.customTitle.ifBlank { slot.defaultTitle })
                hint = "Nombre del favorito (ej. Gimnasio, Familia, Playa…)"
                setTextColor(QonectaTheme.text)
                setHintTextColor(QonectaTheme.faint)
                setTextSp(19f)
                setSingleLine(true)
                setPadding(dp(18), dp(12), dp(18), dp(12))
                background = roundedDrawable(QonectaTheme.surfaceTop, dp(14), dp(2), QonectaTheme.outline)
            }
            nameInput = nameInputView
            container.addView(
                nameInputView,
                LayoutParams(MATCH, dp(56)).apply { bottomMargin = dp(12) },
            )
        }

        val addrInput = EditText(context).apply {
            val initialAddr = slot.address.ifBlank { prefillAddress }
            setText(initialAddr)
            hint = "Dirección, calle, ciudad o lugar (ej. Av. de América 12, Madrid)"
            setTextColor(QonectaTheme.text)
            setHintTextColor(QonectaTheme.faint)
            setTextSp(19f)
            setSingleLine(true)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = roundedDrawable(QonectaTheme.surfaceTop, dp(14), dp(2), QonectaTheme.accent)
        }
        container.addView(addrInput, LayoutParams(MATCH, dp(56)))

        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()

        val btnRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, 0)
        }

        if (slot.address.isNotBlank()) {
            val deleteBtn = smallPillButton("🗑 Borrar") {
                saveFavoriteSlot(slot, slot.defaultTitle, "")
                dialog.dismiss()
                renderActiveTab()
            }
            btnRow.addView(deleteBtn, LayoutParams(WRAP, dp(48)).apply { rightMargin = dp(10) })
        }

        val cancelBtn = smallPillButton("Cancelar") {
            dialog.dismiss()
        }
        btnRow.addView(cancelBtn, LayoutParams(WRAP, dp(48)).apply { rightMargin = dp(10) })

        val saveOnlyBtn = actionButton("Guardar", primary = false) {
            val addr = addrInput.text?.toString()?.trim().orEmpty()
            val customName = nameInput?.text?.toString()?.trim().orEmpty().ifBlank { slot.defaultTitle }
            if (addr.isEmpty()) {
                Toast.makeText(context, "Escribe una dirección para guardar el favorito", Toast.LENGTH_SHORT).show()
                return@actionButton
            }
            saveFavoriteSlot(slot, customName, addr)
            dialog.dismiss()
            renderActiveTab()
        }
        btnRow.addView(saveOnlyBtn, LayoutParams(WRAP, dp(48)).apply { rightMargin = dp(10) })

        val saveAndGoBtn = actionButton("Guardar e Ir ⚡", primary = true) {
            val addr = addrInput.text?.toString()?.trim().orEmpty()
            val customName = nameInput?.text?.toString()?.trim().orEmpty().ifBlank { slot.defaultTitle }
            if (addr.isEmpty()) {
                Toast.makeText(context, "Escribe una dirección para calcular la ruta", Toast.LENGTH_SHORT).show()
                return@actionButton
            }
            saveFavoriteSlot(slot, customName, addr)
            lastSearchQuery = addr
            dialog.dismiss()
            calculateRouteAsync(addr, departureSoc)
        }
        btnRow.addView(saveAndGoBtn, LayoutParams(WRAP, dp(48)))

        container.addView(btnRow, LayoutParams(MATCH, WRAP))
        dialog.show()
    }

    private fun showAllFavoritesManagerDialog(departureSoc: Int, currentTypedQuery: String) {
        val slots = loadFavoriteSlots()
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(26), dp(22), dp(26), dp(20))
            setBackgroundColor(QonectaTheme.surface)
        }

        container.addView(TextView(context).apply {
            text = "⭐ Mis 4 Direcciones Favoritas (Casa, Trabajo y 2 más)"
            setTextSp(22f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        container.addView(TextView(context).apply {
            text = "Toca «Editar» en cualquiera de las 4 posiciones para guardar o cambiar su dirección."
            setTextSp(16f)
            setTextColor(QonectaTheme.dim)
            setPadding(0, dp(6), 0, dp(14))
        })

        val dialog = AlertDialog.Builder(context).setView(container).create()

        slots.forEach { slot ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(14))
                background = roundedDrawable(QonectaTheme.surfaceTop, dp(16), dp(1), QonectaTheme.outline)
            }
            val textCol = LinearLayout(context).apply { orientation = VERTICAL }
            textCol.addView(TextView(context).apply {
                text = "${slot.icon}  ${slot.displayTitle}"
                setTextSp(19f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            textCol.addView(TextView(context).apply {
                text = slot.address.ifBlank { "Sin dirección guardada todavía" }
                setTextSp(16f)
                setTextColor(if (slot.address.isNotBlank()) QonectaTheme.accent else QonectaTheme.dim)
            })
            row.addView(textCol, LayoutParams(0, WRAP, 1f))

            if (slot.address.isNotBlank()) {
                val goBtn = smallPillButton("Ir ⚡") {
                    dialog.dismiss()
                    lastSearchQuery = slot.address
                    calculateRouteAsync(slot.address, departureSoc)
                }
                row.addView(goBtn, LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(8) })
            }

            val editBtn = actionButton("✎ Editar", primary = slot.address.isBlank()) {
                dialog.dismiss()
                showFavoriteEditorDialog(slot, currentTypedQuery, departureSoc)
            }
            row.addView(editBtn, LayoutParams(WRAP, dp(46)))

            container.addView(row, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
        }

        val closeRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        closeRow.addView(smallPillButton("Cerrar") { dialog.dismiss() }, LayoutParams(WRAP, dp(48)))
        container.addView(closeRow, LayoutParams(MATCH, WRAP))

        dialog.show()
    }

    // =========================================================================
    // 1. PESTAÑA RUTA (Planificador EV + 4 Favoritos + Perfil Grande + Filtro Cargadores Qonecta+)
    // =========================================================================

    private fun buildRouteTab(snap: LeapVehicleDataSource.TelemetrySnapshot): View {
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
        }

        val baseSoc = if (!snap.socPct.isNaN()) snap.socPct.roundToInt() else 78
        val departureSoc = (baseSoc + socAdjustDelta).coerceIn(5, 100)

        val searchCard = cardLayout().apply {
            val headerRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val activeVariant = LeapVehicleDataSource.getSelectedVariant(context)
            headerRow.addView(
                sectionLabel("PLANIFICADOR DE RUTA INTELIGENTE · ${activeVariant.shortLabel.uppercase()}"),
                LayoutParams(0, WRAP, 1f),
            )

            val minusBtn = smallPillButton("−5 %") {
                socAdjustDelta = (socAdjustDelta - 5).coerceIn(-80, 80)
                LeapVehicleDataSource.adjustManualSoc(context, -5)
                recalcRouteSoc(departureSoc - 5)
                renderActiveTab()
            }
            val socLabel = TextView(context).apply {
                text = "Salida: $departureSoc %"
                setTextSp(19f)
                setTextColor(QonectaTheme.accent)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(16), 0, dp(16), 0)
            }
            val plusBtn = smallPillButton("+5 %") {
                socAdjustDelta = (socAdjustDelta + 5).coerceIn(-80, 80)
                LeapVehicleDataSource.adjustManualSoc(context, +5)
                recalcRouteSoc(departureSoc + 5)
                renderActiveTab()
            }
            headerRow.addView(minusBtn)
            headerRow.addView(socLabel)
            headerRow.addView(plusBtn)
            addView(headerRow, LayoutParams(MATCH, WRAP))

            val inputRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val input = EditText(context).apply {
                setText(lastSearchQuery)
                hint = "Escribe dirección, ciudad o elige Casa / Trabajo / Favoritos…"
                setTextColor(QonectaTheme.text)
                setHintTextColor(QonectaTheme.dim)
                setTextSp(21f)
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setPadding(dp(20), dp(14), dp(20), dp(14))
                background = roundedDrawable(QonectaTheme.surface, dp(18), dp(2), QonectaTheme.outline)
            }
            val calcBtn = actionButton(if (routeLoading) "Calculando…" else "Calcular ruta", primary = true) {
                val q = input.text?.toString()?.trim().orEmpty()
                if (q.isNotEmpty() && !routeLoading) {
                    lastSearchQuery = q
                    calculateRouteAsync(q, departureSoc)
                } else if (q.isEmpty()) {
                    Toast.makeText(context, "Escribe un destino o pulsa uno de tus 4 favoritos", Toast.LENGTH_SHORT).show()
                }
            }
            inputRow.addView(input, LayoutParams(0, dp(64), 1f).apply { rightMargin = dp(12) })
            inputRow.addView(calcBtn, LayoutParams(WRAP, dp(64)))

            if (currentRoute != null || lastSearchQuery.isNotBlank()) {
                val clearRouteBtn = smallPillButton("✕ Quitar ruta") {
                    currentRoute = null
                    selectedChargerStop = null
                    lastSearchQuery = ""
                    routeError = null
                    renderActiveTab()
                }
                inputRow.addView(
                    clearRouteBtn,
                    LayoutParams(WRAP, dp(64)).apply { leftMargin = dp(10) },
                )
            }
            addView(inputRow, LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })

            // Fila de 4 Favoritos configurables: Casa, Trabajo y 2 direcciones más (sin ciudades fijas)
            val favScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
            }
            val favRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val favSlots = loadFavoriteSlots()
            favSlots.forEachIndexed { i, slot ->
                val hasAddr = slot.address.isNotBlank()
                val shortAddr = if (hasAddr) {
                    slot.address.split(",").firstOrNull()?.trim().orEmpty().take(22)
                } else {
                    "Elegir"
                }
                val label = "${slot.icon} ${slot.displayTitle} · $shortAddr"
                val chip = TextView(context).apply {
                    text = label
                    setTextSp(18f)
                    setTextColor(if (hasAddr) QonectaTheme.text else QonectaTheme.accent)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(20), dp(10), dp(20), dp(10))
                    val strokeCol = if (hasAddr) QonectaTheme.accent else QonectaTheme.outline
                    background = RippleDrawable(
                        ColorStateList.valueOf(0x3300E5C7),
                        pillDrawable(QonectaTheme.surfaceTop, strokeCol),
                        null,
                    )
                    setOnClickListener {
                        val typedNow = input.text?.toString()?.trim().orEmpty()
                        if (hasAddr) {
                            lastSearchQuery = slot.address
                            calculateRouteAsync(slot.address, departureSoc)
                        } else {
                            showFavoriteEditorDialog(slot, typedNow, departureSoc)
                        }
                    }
                    setOnLongClickListener {
                        val typedNow = input.text?.toString()?.trim().orEmpty()
                        showFavoriteEditorDialog(slot, typedNow, departureSoc)
                        true
                    }
                }
                favRow.addView(
                    chip,
                    LayoutParams(WRAP, dp(52)).apply { if (i > 0) leftMargin = dp(10) },
                )
            }

            val manageFavBtn = smallPillButton("✎ Editar favoritos") {
                val typedNow = input.text?.toString()?.trim().orEmpty()
                showAllFavoritesManagerDialog(departureSoc, typedNow)
            }
            favRow.addView(
                manageFavBtn,
                LayoutParams(WRAP, dp(52)).apply { leftMargin = dp(10) },
            )

            favScroll.addView(favRow)
            addView(favScroll, LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
        }
        col.addView(searchCard, LayoutParams(MATCH, WRAP))

        routeError?.let { err ->
            val errView = TextView(context).apply {
                text = "⚠ $err"
                setTextSp(18f)
                setTextColor(PanelFormat.AMBER)
                setPadding(dp(10), dp(10), dp(10), dp(6))
            }
            col.addView(errView, LayoutParams(MATCH, WRAP))
        }

        val plan = currentRoute
        if (plan != null) {
            val stop = selectedChargerStop
            val effectiveArrivalSoc = if (stop != null) {
                (plan.arrivalSocPct + 45).coerceAtMost(92)
            } else {
                plan.arrivalSocPct
            }
            val hrs = plan.durationMin / 60
            val mins = plan.durationMin % 60
            val timeStr = if (hrs > 0) "${hrs}h ${mins}m" else "${mins} min"
            val arrivalColor = PanelFormat.levelColor(effectiveArrivalSoc.toDouble())

            val boxDist = metricBox(
                "DISTANCIA",
                String.format(Locale.getDefault(), "%.1f km", plan.distanceKm),
                if (stop != null) "Vía ${stop.name.take(18)}" else "Ruta óptima OSRM",
                PanelFormat.TEXT,
            )
            val boxTime = metricBox(
                "TIEMPO ESTIMADO",
                timeStr,
                if (stop != null) "+18 min parada carga" else "Tráfico fluido",
                PanelFormat.TEXT,
            )
            val boxEnergy = metricBox(
                "ENERGÍA RUTA",
                String.format(Locale.getDefault(), "%.1f kWh", plan.energyKwh),
                "Media 17,5 kWh/100",
                PanelFormat.ACCENT,
            )
            val boxSoc = metricBox(
                "BATERÍA LLEGADA",
                "$effectiveArrivalSoc %",
                when {
                    stop != null -> "Con parada en ${stop.powerKw} kW"
                    effectiveArrivalSoc >= 20 -> "Llegas sin parar"
                    else -> "Elige cargador abajo"
                },
                arrivalColor,
            )

            if (isSplitScreen) {
                val r1 = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    weightSum = 2f
                }
                r1.addView(boxDist, LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
                r1.addView(boxTime, LayoutParams(0, WRAP, 1f))
                val r2 = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    weightSum = 2f
                }
                r2.addView(boxEnergy, LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
                r2.addView(boxSoc, LayoutParams(0, WRAP, 1f))
                col.addView(r1, LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
                col.addView(r2, LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
            } else {
                val summaryRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    weightSum = 4f
                }
                summaryRow.addView(boxDist, LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(10) })
                summaryRow.addView(boxTime, LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(10) })
                summaryRow.addView(boxEnergy, LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(10) })
                summaryRow.addView(boxSoc, LayoutParams(0, WRAP, 1f))
                col.addView(summaryRow, LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
            }

            val elevCard = cardLayout().apply {
                val topRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                topRow.addView(
                    sectionLabel("PERFIL DE ALTITUD Y DESCARGA DE BATERÍA · ${plan.destinationName.uppercase()}"),
                    LayoutParams(0, WRAP, 1f),
                )
                val navLabel = if (stop != null) {
                    "Iniciar en Google Maps (con parada ⚡) ↗"
                } else {
                    "Iniciar en Google Maps ↗"
                }
                val navBtn = actionButton(navLabel, primary = true) {
                    launchExternalNavigation(plan.destLat, plan.destLon, plan.destinationName, stop)
                }
                topRow.addView(navBtn, LayoutParams(WRAP, dp(54)))
                addView(topRow, LayoutParams(MATCH, WRAP))

                val chart = ElevationProfileChartView(context, plan, stop, effectiveArrivalSoc)
                addView(chart, LayoutParams(MATCH, dp(260)).apply { topMargin = dp(12) })
            }
            col.addView(elevCard, LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
        }

        // Tarjeta de Cargadores Rápidos CC con Filtro Qonecta+ (Potencia kW + Redes) y Selección
        val chargersSource = plan?.chargers ?: standaloneChargers
        val filteredChargers = chargersSource.filter { ch ->
            val kwOk = ch.powerKw >= chargerMinKw
            val netOk = selectedNetworks.isEmpty() || selectedNetworks.contains(ch.networkKey)
            kwOk && netOk
        }

        val chargersCard = cardLayout().apply {
            val headerRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val filterDesc = buildChargerFilterSummary(filteredChargers.size, chargersSource.size)
            headerRow.addView(
                sectionLabel("CARGADORES RÁPIDOS CC · FILTRO Y ELECCIÓN QONECTA+ ($filterDesc)"),
                LayoutParams(0, WRAP, 1f),
            )
            if (selectedChargerStop != null) {
                val clearStopBtn = smallPillButton("✕ Quitar parada (${selectedChargerStop?.name?.take(16)})") {
                    selectedChargerStop = null
                    renderActiveTab()
                }
                headerRow.addView(clearStopBtn, LayoutParams(WRAP, dp(46)))
            }
            addView(headerRow, LayoutParams(MATCH, WRAP))

            // Fila 1 de Filtro: Potencia mínima (kW)
            val kwScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
            }
            val kwRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            kwRow.addView(TextView(context).apply {
                text = "Potencia:"
                setTextSp(17f)
                setTextColor(QonectaTheme.dim)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, 0, dp(10), 0)
            })
            val kwChoices = listOf(
                0 to "Todos",
                50 to "≥ 50 kW",
                100 to "≥ 100 kW",
                150 to "≥ 150 kW",
                250 to "≥ 250 kW",
            )
            kwChoices.forEachIndexed { idx, (kwVal, kwLabel) ->
                val active = chargerMinKw == kwVal
                val chip = filterChipButton(kwLabel, active) {
                    chargerMinKw = kwVal
                    prefs.edit().putInt(KEY_CHARGER_MIN_KW, kwVal).apply()
                    renderActiveTab()
                }
                kwRow.addView(
                    chip,
                    LayoutParams(WRAP, dp(46)).apply { if (idx > 0) leftMargin = dp(8) },
                )
            }
            kwScroll.addView(kwRow)
            addView(kwScroll, LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

            // Fila 2 de Filtro: Redes de carga (Zunder, Iberdrola, Ionity, Tesla, Endesa X, Repsol, Wenea, etc.)
            val netScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
            }
            val netRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            netRow.addView(TextView(context).apply {
                text = "Redes:"
                setTextSp(17f)
                setTextColor(QonectaTheme.dim)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, 0, dp(10), 0)
            })

            val allNetsActive = selectedNetworks.isEmpty()
            val allNetsChip = filterChipButton("Todas las redes", allNetsActive) {
                selectedNetworks.clear()
                saveSelectedNetworks()
                renderActiveTab()
            }
            netRow.addView(allNetsChip, LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(8) })

            NETWORK_SPECS.forEach { net ->
                val isSelected = selectedNetworks.contains(net.key)
                val chip = filterChipButton("${net.badgeText} · ${net.label}", isSelected, net.badgeColor) {
                    if (selectedNetworks.contains(net.key)) {
                        selectedNetworks.remove(net.key)
                    } else {
                        selectedNetworks.add(net.key)
                    }
                    saveSelectedNetworks()
                    renderActiveTab()
                }
                netRow.addView(chip, LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(8) })
            }
            netScroll.addView(netRow)
            addView(netScroll, LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

            // Lista de tarjetas de cargadores filtrados (elegibles como parada o navegación directa)
            if (filteredChargers.isEmpty()) {
                val emptyCh = TextView(context).apply {
                    text = "Ningún cargador coincide con el filtro actual (prueba a bajar los kW o activar «Todas las redes»)."
                    setTextSp(18f)
                    setTextColor(QonectaTheme.amber)
                    setPadding(dp(8), dp(14), dp(8), dp(6))
                }
                addView(emptyCh, LayoutParams(MATCH, WRAP))
            } else {
                val gridCol = LinearLayout(context).apply {
                    orientation = VERTICAL
                }
                val chCols = if (isSplitScreen) 2 else 3
                filteredChargers.chunked(chCols).forEachIndexed { rowIdx, rowItems ->
                    val row = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        weightSum = chCols.toFloat()
                    }
                    rowItems.forEachIndexed { idx, ch ->
                        val isStop = selectedChargerStop?.id == ch.id
                        val spec = NETWORK_SPECS.find { it.key == ch.networkKey }
                        val strokeColor = if (isStop) QonectaTheme.accent else QonectaTheme.outline
                        val strokeWidth = if (isStop) dp(3) else dp(1)

                        val chBox = LinearLayout(context).apply {
                            orientation = VERTICAL
                            setPadding(dp(18), dp(14), dp(18), dp(14))
                            background = roundedDrawable(QonectaTheme.surface, dp(18), strokeWidth, strokeColor)
                            isClickable = true
                            setOnClickListener {
                                selectedChargerStop = if (isStop) null else ch
                                renderActiveTab()
                            }
                        }

                        // Cabecera del cargador: Badge de color de la red + Nombre
                        val topChRow = LinearLayout(context).apply {
                            orientation = HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        val badgeView = TextView(context).apply {
                            text = spec?.badgeText ?: "⚡"
                            setTextSp(15f)
                            setTextColor(if (spec?.darkText == true) Color.BLACK else Color.WHITE)
                            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                            gravity = Gravity.CENTER
                            setPadding(dp(10), dp(4), dp(10), dp(4))
                            background = roundedDrawable(spec?.badgeColor ?: QonectaTheme.accent, dp(10), 0, 0)
                        }
                        topChRow.addView(badgeView, LayoutParams(WRAP, WRAP).apply { rightMargin = dp(10) })

                        val nameTv = TextView(context).apply {
                            text = ch.name
                            setTextSp(20f)
                            setTextColor(QonectaTheme.text)
                            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        }
                        topChRow.addView(nameTv, LayoutParams(0, WRAP, 1f))
                        chBox.addView(topChRow, LayoutParams(MATCH, WRAP))

                        // Detalles técnicos: kW, voltaje, puestos y distancia
                        val detailTv = TextView(context).apply {
                            text = "${ch.powerKw} kW CCS2 (${ch.volts} V) · ${ch.stalls} pto · a ${String.format(Locale.getDefault(), "%.1f", ch.distFromRouteKm)} km"
                            setTextSp(17f)
                            setTextColor(QonectaTheme.accent)
                            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                            setPadding(0, dp(6), 0, dp(10))
                        }
                        chBox.addView(detailTv, LayoutParams(MATCH, WRAP))

                        // Botones de acción del cargador: Elegir parada en ruta / Ir directo
                        val actRow = LinearLayout(context).apply {
                            orientation = HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        val chooseBtn = TextView(context).apply {
                            text = if (isStop) "✓ Parada elegida" else "⚡ Elegir parada"
                            setTextSp(16f)
                            setTextColor(if (isStop) QonectaTheme.onAccent else QonectaTheme.text)
                            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                            gravity = Gravity.CENTER
                            setPadding(dp(14), dp(8), dp(14), dp(8))
                            background = if (isStop) {
                                QonectaTheme.accentGradientPill(dp(14).toFloat())
                            } else {
                                pillDrawable(QonectaTheme.surfaceTop, QonectaTheme.outline)
                            }
                            setOnClickListener {
                                selectedChargerStop = if (isStop) null else ch
                                renderActiveTab()
                            }
                        }
                        val goDirectBtn = TextView(context).apply {
                            text = "Ir ↗"
                            setTextSp(16f)
                            setTextColor(QonectaTheme.accent)
                            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                            gravity = Gravity.CENTER
                            setPadding(dp(14), dp(8), dp(14), dp(8))
                            background = pillDrawable(QonectaTheme.surfaceTop, QonectaTheme.accent)
                            setOnClickListener {
                                Toast.makeText(
                                    context,
                                    "Este cargador es de ejemplo, no un cargador real: no te mando ahí. Los cargadores reales llegan con el nuevo planificador.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                        actRow.addView(chooseBtn, LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
                        actRow.addView(goDirectBtn, LayoutParams(WRAP, dp(42)))
                        chBox.addView(actRow, LayoutParams(MATCH, WRAP))

                        row.addView(
                            chBox,
                            LayoutParams(0, WRAP, 1f).apply { if (idx > 0) leftMargin = dp(10) },
                        )
                    }
                    gridCol.addView(
                        row,
                        LayoutParams(MATCH, WRAP).apply { if (rowIdx > 0) topMargin = dp(10) },
                    )
                }
                addView(gridCol, LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
            }
        }
        col.addView(chargersCard, LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })

        scroll.addView(col)
        return scroll
    }

    private fun saveSelectedNetworks() {
        prefs.edit().putString(KEY_CHARGER_NETWORKS, selectedNetworks.joinToString(",")).apply()
    }

    private fun buildChargerFilterSummary(shownCount: Int, totalCount: Int): String {
        val parts = mutableListOf<String>()
        parts.add("$shownCount de $totalCount")
        if (chargerMinKw > 0) parts.add("≥$chargerMinKw kW")
        if (selectedNetworks.isNotEmpty()) {
            val labels = selectedNetworks.mapNotNull { k -> NETWORK_SPECS.find { it.key == k }?.label }
            parts.add(labels.joinToString(", "))
        } else {
            parts.add("Todas las redes")
        }
        return parts.joinToString(" · ")
    }

    private fun filterChipButton(
        text: String,
        active: Boolean,
        badgeColor: Int? = null,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        this.text = text
        setTextSp(17f)
        setTextColor(if (active) QonectaTheme.onAccent else QonectaTheme.text)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(8), dp(18), dp(8))
        val bg = if (active) {
            QonectaTheme.accentGradientPill(dp(999).toFloat())
        } else {
            pillDrawable(QonectaTheme.surfaceTop, badgeColor ?: QonectaTheme.outline)
        }
        background = RippleDrawable(ColorStateList.valueOf(0x3300E5C7), bg, null)
        setOnClickListener { onClick() }
    }

    private fun recalcRouteSoc(newDepartureSoc: Int) {
        val dep = newDepartureSoc.coerceIn(5, 100)
        val plan = currentRoute ?: return
        val batKwh = LeapVehicleDataSource.getSelectedVariant(context).batteryGrossKwh
        val usedPct = ((plan.energyKwh / batKwh) * 100.0).roundToInt()
        currentRoute = plan.copy(
            departureSocPct = dep,
            arrivalSocPct = (dep - usedPct).coerceIn(0, 100),
        )
    }

    private fun calculateRouteAsync(query: String, departureSoc: Int) {
        routeLoading = true
        routeError = null
        selectedChargerStop = null
        renderActiveTab()

        val startLat = 40.4168
        val startLon = -3.7038

        netExecutor.execute {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val nomUrl = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=$encoded"
                val nomJson = httpGet(nomUrl)
                val arr = JSONArray(nomJson)
                if (arr.length() == 0) {
                    uiHandler.post {
                        routeLoading = false
                        routeError = "No se encontró «$query». Prueba con otra ciudad o dirección."
                        renderActiveTab()
                    }
                    return@execute
                }
                val place = arr.getJSONObject(0)
                val destLat = place.getDouble("lat")
                val destLon = place.getDouble("lon")
                val displayName = place.optString("display_name", query).split(",").take(2).joinToString(", ")

                val osrmUrl = String.format(
                    Locale.US,
                    "https://router.project-osrm.org/route/v1/driving/%.5f,%.5f;%.5f,%.5f?overview=full&geometries=geojson",
                    startLon,
                    startLat,
                    destLon,
                    destLat,
                )
                val osrmJson = JSONObject(httpGet(osrmUrl))
                val routes = osrmJson.getJSONArray("routes")
                val r0 = routes.getJSONObject(0)
                val distKm = r0.getDouble("distance") / 1000.0
                val durMin = (r0.getDouble("duration") / 60.0).roundToInt().coerceAtLeast(1)

                val coords = r0.getJSONObject("geometry").getJSONArray("coordinates")
                val samples = ArrayList<Float>(24)
                val totalPts = coords.length()
                for (i in 0 until 24) {
                    val idx = ((i.toFloat() / 23f) * (totalPts - 1)).roundToInt().coerceIn(0, totalPts - 1)
                    val pt = coords.getJSONArray(idx)
                    val lon = pt.getDouble(0)
                    val lat = pt.getDouble(1)
                    val baseAlt = 650f - ((lat - 40.4) * 140.0).toFloat() + (sin(lon * 8.0) * 85.0).toFloat()
                    samples.add(baseAlt.coerceIn(15f, 1850f))
                }

                val elevGainM = samples.zipWithNext { a, b -> max(0f, b - a) }.sum()
                val energyKwh = max(0.8, distKm * 0.175 + (elevGainM / 1000.0) * 1.8)
                val batKwh = LeapVehicleDataSource.getSelectedVariant(context).batteryGrossKwh
                val usedPct = ((energyKwh / batKwh) * 100.0).roundToInt()
                val arrivalSoc = (departureSoc - usedPct).coerceIn(0, 100)

                val chargers = buildDefaultChargersFor(destLat, destLon, displayName)

                val plan = RoutePlan(
                    destinationName = displayName,
                    destLat = destLat,
                    destLon = destLon,
                    distanceKm = distKm,
                    durationMin = durMin,
                    energyKwh = energyKwh,
                    departureSocPct = departureSoc,
                    arrivalSocPct = arrivalSoc,
                    elevationProfileM = samples,
                    chargers = chargers,
                )
                uiHandler.post {
                    routeLoading = false
                    currentRoute = plan
                    renderActiveTab()
                }
            } catch (_: Exception) {
                uiHandler.post {
                    routeLoading = false
                    val fallbackDist = 42.5
                    val energy = fallbackDist * 0.175
                    val batKwh = LeapVehicleDataSource.getSelectedVariant(context).batteryGrossKwh
                    val usedPct = ((energy / batKwh) * 100.0).roundToInt()
                    currentRoute = RoutePlan(
                        destinationName = query,
                        destLat = 40.4168,
                        destLon = -3.7038,
                        distanceKm = fallbackDist,
                        durationMin = 34,
                        energyKwh = energy,
                        departureSocPct = departureSoc,
                        arrivalSocPct = (departureSoc - usedPct).coerceIn(0, 100),
                        elevationProfileM = listOf(650f, 665f, 690f, 710f, 705f, 680f, 660f, 645f, 630f, 640f, 655f, 670f),
                        chargers = buildDefaultChargersFor(40.4168, -3.7038, query),
                    )
                    renderActiveTab()
                }
            }
        }
    }

    private fun buildDefaultChargersFor(destLat: Double, destLon: Double, areaLabel: String): List<ChargerInfo> {
        val shortArea = areaLabel.split(",").firstOrNull()?.trim().orEmpty().ifBlank { "Ruta" }
        return listOf(
            ChargerInfo(
                id = "zunder_300",
                name = "Zunder Ultra-Fast $shortArea",
                networkKey = "zunder",
                powerKw = 300,
                volts = 800,
                stalls = 8,
                distFromRouteKm = 0.8,
                routeProgressFraction = 0.45f,
                lat = destLat - 0.012,
                lon = destLon - 0.010,
            ),
            ChargerInfo(
                id = "ionity_350",
                name = "Ionity High Power $shortArea",
                networkKey = "ionity",
                powerKw = 350,
                volts = 800,
                stalls = 6,
                distFromRouteKm = 1.4,
                routeProgressFraction = 0.55f,
                lat = destLat - 0.008,
                lon = destLon + 0.009,
            ),
            ChargerInfo(
                id = "iberdrola_150",
                name = "Iberdrola CCS2 $shortArea",
                networkKey = "iberdrola",
                powerKw = 150,
                volts = 400,
                stalls = 4,
                distFromRouteKm = 1.9,
                routeProgressFraction = 0.62f,
                lat = destLat + 0.006,
                lon = destLon - 0.007,
            ),
            ChargerInfo(
                id = "tesla_250",
                name = "Tesla Supercharger V4 $shortArea",
                networkKey = "tesla",
                powerKw = 250,
                volts = 500,
                stalls = 12,
                distFromRouteKm = 2.2,
                routeProgressFraction = 0.50f,
                lat = destLat + 0.011,
                lon = destLon + 0.005,
            ),
            ChargerInfo(
                id = "endesa_150",
                name = "Endesa X Way HPC $shortArea",
                networkKey = "endesa",
                powerKw = 150,
                volts = 400,
                stalls = 4,
                distFromRouteKm = 2.6,
                routeProgressFraction = 0.72f,
                lat = destLat - 0.005,
                lon = destLon - 0.004,
            ),
            ChargerInfo(
                id = "wenea_180",
                name = "Wenea Supercarga $shortArea",
                networkKey = "wenea",
                powerKw = 180,
                volts = 800,
                stalls = 4,
                distFromRouteKm = 3.1,
                routeProgressFraction = 0.38f,
                lat = destLat + 0.004,
                lon = destLon + 0.012,
            ),
            ChargerInfo(
                id = "repsol_120",
                name = "Repsol Recarga Rápida $shortArea",
                networkKey = "repsol",
                powerKw = 120,
                volts = 400,
                stalls = 2,
                distFromRouteKm = 1.1,
                routeProgressFraction = 0.30f,
                lat = destLat - 0.003,
                lon = destLon + 0.004,
            ),
            ChargerInfo(
                id = "moeve_150",
                name = "Moeve / Cepsa Ultra $shortArea",
                networkKey = "moeve",
                powerKw = 150,
                volts = 800,
                stalls = 4,
                distFromRouteKm = 2.0,
                routeProgressFraction = 0.68f,
                lat = destLat + 0.009,
                lon = destLon - 0.009,
            ),
            ChargerInfo(
                id = "electra_300",
                name = "Electra Hub $shortArea",
                networkKey = "electra",
                powerKw = 300,
                volts = 800,
                stalls = 8,
                distFromRouteKm = 2.8,
                routeProgressFraction = 0.80f,
                lat = destLat - 0.009,
                lon = destLon - 0.011,
            ),
        )
    }

    /**
     * Manda la ruta a Google Maps del MÓVIL a través de Qonecta Unit Companion. Unit corre en el coche: un intent
     * local abriría (o no) un mapa en el coche y nunca llegaría a Android Auto, que es Maps del móvil proyectado.
     */
    private fun launchExternalNavigation(
        destLat: Double,
        destLon: Double,
        destName: String,
        stop: ChargerInfo? = null,
    ) {
        val dest = com.headqlink.unit.bridge.NavPoint(destLat, destLon, destName)
        if (!dest.isValid) {
            Toast.makeText(context, "Este destino no tiene coordenadas válidas.", Toast.LENGTH_LONG).show()
            return
        }
        // Los cargadores de esta pantalla todavía son de ejemplo (llegan los reales con el nuevo planificador):
        // no se mandan como parada para no llevarte a un sitio donde no hay nada.
        val stopNote = if (stop != null) "\nLa parada elegida es de ejemplo y no se ha añadido." else ""
        com.headqlink.unit.bridgehost.CompanionBridgeHost.sendRoute(dest, emptyList()) { result ->
            val msg = when (result) {
                is com.headqlink.unit.bridgehost.CompanionBridgeHost.SendResult.Sent ->
                    "✓ Ruta enviada a ${result.device} (${result.transport}). Google Maps la abre en Android Auto.$stopNote"
                is com.headqlink.unit.bridgehost.CompanionBridgeHost.SendResult.NeedsTap ->
                    "Ruta enviada a ${result.device}, pero toca la notificación en el móvil para abrirla (al Companion le falta el permiso «Mostrar sobre otras apps»).$stopNote"
                is com.headqlink.unit.bridgehost.CompanionBridgeHost.SendResult.Failed -> "⚠ ${result.message}"
                com.headqlink.unit.bridgehost.CompanionBridgeHost.SendResult.NotConnected ->
                    "⚠ Tu móvil no está conectado. Abre Qonecta Unit Companion en el móvil y empareja desde Ajustes y Menú Extendido → Qonecta Unit Companion."
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    // =========================================================================
    // 2. PESTAÑA CONDUCCIÓN (Velocidad Grande, Marcha, Brújula, Altitud y Temperaturas)
    // =========================================================================

    private fun buildDriveTab(snap: LeapVehicleDataSource.TelemetrySnapshot): View {
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            weightSum = 2.1f
        }

        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            weightSum = 2f
        }

        val speedCard = cardLayout().apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(16), dp(20), dp(16))
            val gearRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            listOf("P", "R", "N", "D").forEach { g ->
                val active = snap.gearLabel() == g
                val pill = TextView(context).apply {
                    text = g
                    setTextSp(22f)
                    setTextColor(if (active) PanelFormat.BG else PanelFormat.DIM)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(22), dp(6), dp(22), dp(6))
                    background = pillDrawable(
                        if (active) PanelFormat.ACCENT else PanelFormat.SURFACE,
                        if (active) PanelFormat.ACCENT else PanelFormat.EDGE,
                    )
                }
                gearRow.addView(pill, LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6); rightMargin = dp(6) })
            }
            addView(gearRow, LayoutParams(MATCH, WRAP))

            val speedValue = TextView(context).apply {
                text = snap.effectiveSpeedKmh.roundToInt().toString()
                setTextSp(96f)
                setTextColor(PanelFormat.TEXT)
                typeface = BatteryRingView.LIGHT
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            addView(speedValue, LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })

            val isSport = snap.driveMode == 2
            val modeBadgeColor = if (isSport) 0xFFFF6D00.toInt() else PanelFormat.ACCENT
            val unitLabel = TextView(context).apply {
                text = "km/h · Modo ${snap.driveModeLabel()}"
                setTextSp(22f)
                setTextColor(modeBadgeColor)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            addView(unitLabel, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })

            // Selector directo de Modo de Conducción C10
            val modeSelectorRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                weightSum = 4f
            }
            val modes = listOf(
                1 to "🌱 Eco",
                0 to "🛋️ Confort",
                2 to "🔥 Sport",
                3 to "⚙️ Personal",
            )
            modes.forEachIndexed { idx, (code, label) ->
                val active = snap.driveMode == code
                val activeFill = if (code == 2 && active) 0xFFFF6D00.toInt() else if (active) PanelFormat.ACCENT else PanelFormat.SURFACE
                val activeStroke = if (code == 2 && active) 0xFFFF6D00.toInt() else if (active) PanelFormat.ACCENT else PanelFormat.EDGE
                val activeText = if (active) (if (code == 2) Color.WHITE else PanelFormat.BG) else PanelFormat.TEXT
                val btn = TextView(context).apply {
                    text = label
                    setTextSp(16f)
                    setTextColor(activeText)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = pillDrawable(activeFill, activeStroke)
                    setOnClickListener {
                        LeapVehicleDataSource.setDriveModeOverride(context, code)
                        renderActiveTab(resetScroll = false)
                    }
                }
                modeSelectorRow.addView(
                    btn,
                    LayoutParams(0, WRAP, 1f).apply {
                        if (idx > 0) leftMargin = dp(4)
                        if (idx < 3) rightMargin = dp(4)
                    },
                )
            }
            addView(modeSelectorRow, LayoutParams(MATCH, WRAP))
        }

        val navCard = cardLayout().apply {
            setPadding(dp(20), dp(16), dp(20), dp(16))
            addView(sectionLabel("NAVEGACIÓN Y ENTORNO EN TIEMPO REAL"), LayoutParams(MATCH, WRAP))
            val plan = currentRoute
            val routeTitle = TextView(context).apply {
                text = if (plan != null) "🧭 Destino: ${plan.destinationName}" else "🧭 Conducción libre · Sin destino fijado"
                setTextSp(23f)
                setTextColor(PanelFormat.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(2))
            }
            val routeSub = TextView(context).apply {
                text = if (plan != null) {
                    "Quedan ${String.format(Locale.getDefault(), "%.1f km", plan.distanceKm)} · Llegada estimada con ${plan.arrivalSocPct}% de batería (${plan.durationMin} min)"
                } else {
                    "Elige Casa, Trabajo o un destino en la pestaña Ruta para calcular el SoC de llegada"
                }
                setTextSp(16f)
                setTextColor(PanelFormat.DIM)
            }
            addView(routeTitle, LayoutParams(MATCH, WRAP))
            addView(routeSub, LayoutParams(MATCH, WRAP))

            val envGrid = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 3f
            }
            val rangeTxt = if (!snap.effectiveRangeKm.isNaN()) "${snap.effectiveRangeKm.roundToInt()} km" else "-- km"
            val socTxt = if (snap.hasBatteryData) "SoC ${snap.socPct.roundToInt()}% · C10" else "Autonomía EV"
            envGrid.addView(
                largeDriveMetricBox("RUMBO GPS", "45° NE", "Brújula activa", PanelFormat.ACCENT),
                LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) },
            )
            envGrid.addView(
                largeDriveMetricBox("ALTITUD", "655 m", "Sobre nivel del mar", PanelFormat.TEXT),
                LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) },
            )
            envGrid.addView(
                largeDriveMetricBox("AUTONOMÍA EV", rangeTxt, socTxt, PanelFormat.ACCENT),
                LayoutParams(0, WRAP, 1f),
            )
            addView(envGrid, LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

            // Barra interactiva de Frenada Regenerativa
            val regenBar = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                weightSum = 4.2f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = roundedDrawable(PanelFormat.SURFACE, dp(14), dp(1), PanelFormat.EDGE)
            }
            val regenTitle = TextView(context).apply {
                text = "⚡ REGEN: ${snap.regenModeLabel().uppercase(Locale.getDefault())}"
                setTextSp(15f)
                setTextColor(PanelFormat.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            regenBar.addView(regenTitle, LayoutParams(0, WRAP, 1.5f))
            listOf(0 to "Suave", 1 to "Estándar", 2 to "Fuerte").forEach { (rCode, rName) ->
                val active = snap.regenMode == rCode
                val rPill = TextView(context).apply {
                    text = rName
                    setTextSp(15f)
                    setTextColor(if (active) PanelFormat.BG else PanelFormat.TEXT)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = pillDrawable(
                        if (active) PanelFormat.ACCENT else PanelFormat.BG,
                        if (active) PanelFormat.ACCENT else PanelFormat.EDGE,
                    )
                    setOnClickListener {
                        LeapVehicleDataSource.setRegenModeOverride(context, rCode)
                        renderActiveTab(resetScroll = false)
                    }
                }
                regenBar.addView(rPill, LayoutParams(0, WRAP, 0.9f).apply { leftMargin = dp(6) })
            }
            addView(regenBar, LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }

        val cabLVal = if (!snap.tempLeftC.isNaN() && snap.tempLeftC in 14.0..34.0) snap.tempLeftC else 25.0
        val cabRVal = if (!snap.tempRightC.isNaN() && snap.tempRightC in 14.0..34.0) snap.tempRightC else 25.0
        val extTVal = if (!snap.tempExteriorC.isNaN() && snap.tempExteriorC in -45.0..60.0) snap.tempExteriorC else 8.0
        val cabL = String.format(Locale.getDefault(), "%.1f °C", cabLVal)
        val cabR = String.format(Locale.getDefault(), "%.1f °C", cabRVal)
        val extT = String.format(Locale.getDefault(), "%.1f °C", extTVal)
        val steerStr = if (!snap.steeringAngleDeg.isNaN()) String.format(Locale.getDefault(), "%+.0f°", snap.steeringAngleDeg) else "+0°"

        val boxCabL = climateControlBox(
            title = "HABITÁCULO IZQ",
            value = cabL,
            subtitle = if (snap.acOn) "Climatizador AUTO · Conductor" else "Climatizador · Conductor",
            valueColor = PanelFormat.ACCENT,
            onMinus = {
                LeapVehicleDataSource.adjustCabinTemp(context, leftZone = true, deltaC = -0.5)
                renderActiveTab(resetScroll = false)
            },
            onPlus = {
                LeapVehicleDataSource.adjustCabinTemp(context, leftZone = true, deltaC = +0.5)
                renderActiveTab(resetScroll = false)
            },
        )
        val boxCabR = climateControlBox(
            title = "HABITÁCULO DER",
            value = cabR,
            subtitle = "Zona acompañante C10",
            valueColor = PanelFormat.TEXT,
            onMinus = {
                LeapVehicleDataSource.adjustCabinTemp(context, leftZone = false, deltaC = -0.5)
                renderActiveTab(resetScroll = false)
            },
            onPlus = {
                LeapVehicleDataSource.adjustCabinTemp(context, leftZone = false, deltaC = +0.5)
                renderActiveTab(resetScroll = false)
            },
        )
        val boxExtT = climateControlBox(
            title = "TEMP. EXTERIOR",
            value = extT,
            subtitle = "Sensor frontal C10",
            valueColor = 0xFF38BDF8.toInt(),
            onMinus = {
                LeapVehicleDataSource.adjustExteriorTemp(context, deltaC = -1.0)
                renderActiveTab(resetScroll = false)
            },
            onPlus = {
                LeapVehicleDataSource.adjustExteriorTemp(context, deltaC = +1.0)
                renderActiveTab(resetScroll = false)
            },
        )
        val boxSteer = steeringControlBox(
            angleStr = steerStr,
            steeringMode = snap.steeringMode,
            steeringLabel = snap.steeringModeLabel(),
        )

        if (isSplitScreen) {
            col.weightSum = 0f
            col.addView(speedCard, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
            col.addView(navCard, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
            val climRow1 = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 2f
            }
            climRow1.addView(boxCabL, LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
            climRow1.addView(boxCabR, LayoutParams(0, WRAP, 1f))
            val climRow2 = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 2f
            }
            climRow2.addView(boxExtT, LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
            climRow2.addView(boxSteer, LayoutParams(0, WRAP, 1f))
            col.addView(climRow1, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
            col.addView(climRow2, LayoutParams(MATCH, WRAP))
            scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        } else {
            topRow.addView(speedCard, LayoutParams(0, MATCH, 0.88f).apply { rightMargin = dp(12) })
            topRow.addView(navCard, LayoutParams(0, MATCH, 1.12f))
            col.addView(topRow, LayoutParams(MATCH, 0, 1.18f))

            val bottomRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 4f
            }
            bottomRow.addView(boxCabL, LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(10) })
            bottomRow.addView(boxCabR, LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(10) })
            bottomRow.addView(boxExtT, LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(10) })
            bottomRow.addView(boxSteer, LayoutParams(0, MATCH, 1f))
            col.addView(bottomRow, LayoutParams(MATCH, 0, 0.92f).apply { topMargin = dp(12) })
            scroll.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        return scroll
    }

    private fun largeDriveMetricBox(title: String, value: String, subtitle: String, valueColor: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedDrawable(PanelFormat.SURFACE, dp(14), dp(1), PanelFormat.EDGE)
            addView(TextView(context).apply {
                text = title
                setTextSp(14f)
                setTextColor(PanelFormat.DIM)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = value
                setTextSp(28f)
                setTextColor(valueColor)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, dp(2), 0, dp(2))
            })
            addView(TextView(context).apply {
                text = subtitle
                setTextSp(14f)
                setTextColor(PanelFormat.DIM)
            })
        }
    }

    private fun climateControlBox(
        title: String,
        value: String,
        subtitle: String,
        valueColor: Int,
        onMinus: () -> Unit,
        onPlus: () -> Unit,
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedDrawable(PanelFormat.SURFACE, dp(18), dp(1), PanelFormat.OUTLINE)
            addView(TextView(context).apply {
                text = title
                setTextSp(15f)
                setTextColor(PanelFormat.DIM)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.04f
            })
            addView(TextView(context).apply {
                text = value
                setTextSp(40f)
                setTextColor(valueColor)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(2))
            })
            addView(TextView(context).apply {
                text = subtitle
                setTextSp(15f)
                setTextColor(PanelFormat.DIM)
            })
            val adjustRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                weightSum = 2f
            }
            val minusBtn = TextView(context).apply {
                text = "− 0,5°"
                setTextSp(16f)
                setTextColor(PanelFormat.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                background = pillDrawable(PanelFormat.BG, PanelFormat.EDGE)
                setOnClickListener { onMinus() }
            }
            val plusBtn = TextView(context).apply {
                text = "+ 0,5°"
                setTextSp(16f)
                setTextColor(PanelFormat.ACCENT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                background = pillDrawable(PanelFormat.BG, PanelFormat.ACCENT)
                setOnClickListener { onPlus() }
            }
            adjustRow.addView(minusBtn, LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(5) })
            adjustRow.addView(plusBtn, LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(5) })
            addView(adjustRow, LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
    }

    private fun steeringControlBox(
        angleStr: String,
        steeringMode: Int,
        steeringLabel: String,
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedDrawable(PanelFormat.SURFACE, dp(18), dp(1), PanelFormat.OUTLINE)
            addView(TextView(context).apply {
                text = "ÁNGULO VOLANTE"
                setTextSp(15f)
                setTextColor(PanelFormat.DIM)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.04f
            })
            addView(TextView(context).apply {
                text = angleStr
                setTextSp(40f)
                setTextColor(PanelFormat.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(2))
            })
            addView(TextView(context).apply {
                text = "Dirección $steeringLabel"
                setTextSp(15f)
                setTextColor(if (steeringMode == 2) 0xFFFF6D00.toInt() else PanelFormat.ACCENT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            val steerRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                weightSum = 3f
            }
            listOf(0 to "Confort", 1 to "Estándar", 2 to "Sport").forEachIndexed { idx, (sCode, sName) ->
                val active = steeringMode == sCode
                val activeFill = if (sCode == 2 && active) 0xFFFF6D00.toInt() else if (active) PanelFormat.ACCENT else PanelFormat.BG
                val activeStroke = if (sCode == 2 && active) 0xFFFF6D00.toInt() else if (active) PanelFormat.ACCENT else PanelFormat.EDGE
                val activeTxt = if (active) (if (sCode == 2) Color.WHITE else PanelFormat.BG) else PanelFormat.TEXT
                val pill = TextView(context).apply {
                    text = sName
                    setTextSp(14f)
                    setTextColor(activeTxt)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    background = pillDrawable(activeFill, activeStroke)
                    setOnClickListener {
                        LeapVehicleDataSource.setSteeringModeOverride(context, sCode)
                        renderActiveTab(resetScroll = false)
                    }
                }
                steerRow.addView(
                    pill,
                    LayoutParams(0, WRAP, 1f).apply {
                        if (idx > 0) leftMargin = dp(3)
                        if (idx < 2) rightMargin = dp(3)
                    },
                )
            }
            addView(steerRow, LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
    }

    // =========================================================================
    // 3. PESTAÑA VIAJES (Diseño Qonecta+: En curso + Tarjetas con Mini-Mapa + 14 Días + Récords + Vista de Detalle con Mapa)
    // =========================================================================

    private fun buildTripsTab(snap: LeapVehicleDataSource.TelemetrySnapshot): View {
        val detail = selectedTripDetail
        if (detail != null) {
            return buildTripDetailView(detail)
        }

        val rootRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            weightSum = 3.0f
        }

        val allTripsIncludingHidden = LeapVehicleDataSource.loadRecentTrips(context, includeHidden = true)
        val hiddenCount = allTripsIncludingHidden.count { it.hidden }
        val trips = if (showHiddenTripsInUnit) allTripsIncludingHidden else allTripsIncludingHidden.filterNot { it.hidden }
        val pricing = LeapVehicleDataSource.getEnergyPricing(context)
        val longestIdx = trips.indices.maxByOrNull { trips[it].distanceKm } ?: -1
        val efficientIdx = trips.indices.filter { trips[it].distanceKm >= 5.0 }.minByOrNull { trips[it].avgKwh100 } ?: -1
        val fastestIdx = trips.indices.maxByOrNull { trips[it].maxSpeedKmh } ?: -1
        val climbIdx = trips.indices.maxByOrNull { trips[it].climbM } ?: -1

        // COLUMNA IZQUIERDA (Viaje en curso + Lista de viajes con mini-mapa de ruta)
        val leftScroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val leftCol = LinearLayout(context).apply {
            orientation = VERTICAL
        }

        // 1. Tarjeta VIAJE EN CURSO (estilo Qonecta+ "EN CURSO")
        val liveCard = cardLayout().apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            val header = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val liveTitleRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val greenDot = View(context).apply {
                background = pillDrawable(0xFF4CFF9F.toInt(), 0xFF4CFF9F.toInt())
            }
            liveTitleRow.addView(greenDot, LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(8) })
            liveTitleRow.addView(sectionLabel("EN CURSO · VIAJE ACTUAL C10"))
            header.addView(liveTitleRow, LayoutParams(0, WRAP, 1f))

            val privacyFilterBtn = smallPillButton(
                if (showHiddenTripsInUnit) "🔓 Ocultar privados ($hiddenCount)" else "🔒 Privacidad ($hiddenCount)",
            ) {
                showHiddenTripsInUnit = !showHiddenTripsInUnit
                renderActiveTab(resetScroll = false)
            }
            header.addView(privacyFilterBtn, LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })

            val tariffsBtn = smallPillButton("⚡⛽ Tarifas") {
                showUnitEnergyPricingDialog()
            }
            header.addView(tariffsBtn, LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })

            val viewLiveMapBtn = smallPillButton("🗺️ Ver mapa en vivo") {
                val durMin = max(1, (snap.tripDurationSec / 60L).toInt().takeIf { it > 0 } ?: 1)
                val distKm = snap.tripDistanceKm.coerceAtLeast(0.0)
                val kwh = snap.tripEnergyKwh.coerceAtLeast(0.0)
                val avgCons = if (!snap.avgConsumptionKwh100.isNaN() && snap.avgConsumptionKwh100 > 0 && distKm >= 0.05) snap.avgConsumptionKwh100 else 0.0
                val endSoc = if (!snap.socPct.isNaN()) snap.socPct else 78.0
                val maxSpd = max(snap.maxSpeedKmh.coerceAtLeast(0.0), snap.effectiveSpeedKmh.coerceAtLeast(0.0))
                val maxG = if (snap.peakG >= 0.01f && distKm >= 0.01) snap.peakG.toDouble() else 0.0
                selectedTripDetail = LeapVehicleDataSource.TripRecord(
                    startTimeMs = System.currentTimeMillis() - durMin * 60_000L,
                    durationMin = durMin,
                    distanceKm = distKm,
                    energyKwh = kwh,
                    avgKwh100 = avgCons,
                    startSoc = (endSoc + (kwh / 81.9) * 100.0).coerceAtMost(100.0),
                    endSoc = endSoc,
                    maxSpeedKmh = maxSpd,
                    avgSpeedKmh = if (snap.tripDurationSec > 10 && distKm >= 0.05) (distKm / (snap.tripDurationSec / 3600.0)).coerceIn(0.0, max(1.0, maxSpd)) else 0.0,
                    maxAccelG = maxG,
                    zeroToFiftySec = snap.zeroToFiftySec,
                    zeroToHundredSec = snap.zeroToHundredSec,
                    climbM = (distKm * 3.5).roundToInt().toDouble(),
                    descentM = (distKm * 3.2).roundToInt().toDouble(),
                    costEur = kwh * 0.18,
                    smoothnessScore = snap.smoothnessScore,
                    originName = "Posición de salida C10",
                    destinationName = "En curso (Ruta actual)",
                    stopsCount = 0,
                    stopsSummary = "En curso · Telemetría real C10",
                    track = LeapVehicleDataSource.getLiveTrack(),
                )
                renderActiveTab(resetScroll = true)
            }
            header.addView(viewLiveMapBtn, LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })

            val saveResetBtn = smallPillButton("💾 Guardar y reiniciar") {
                val saved = LeapVehicleDataSource.saveCurrentTripAndReset(context)
                com.headqlink.unit.bridgehost.CompanionBridgeHost.pushImmediateSync()
                Toast.makeText(
                    context,
                    String.format(Locale.getDefault(), "Viaje guardado (%.1f km · Vel. máx %.0f km/h)", saved.distanceKm, saved.maxSpeedKmh),
                    Toast.LENGTH_SHORT,
                ).show()
                renderActiveTab(resetScroll = false)
            }
            header.addView(saveResetBtn)
            addView(header, LayoutParams(MATCH, WRAP))

            val durMin = (snap.tripDurationSec / 60L).toInt()
            val durSec = (snap.tripDurationSec % 60L).toInt()
            val liveDist = snap.tripDistanceKm.coerceAtLeast(0.0)
            val liveAvgKmh = if (snap.tripDurationSec > 10 && liveDist >= 0.05) (liveDist / (snap.tripDurationSec / 3600.0)).coerceIn(0.0, 220.0) else 0.0
            val avgCons = if (!snap.avgConsumptionKwh100.isNaN() && snap.avgConsumptionKwh100 > 0 && liveDist >= 0.05) snap.avgConsumptionKwh100 else 0.0
            val liveMaxSpd = max(snap.maxSpeedKmh.coerceAtLeast(0.0), snap.effectiveSpeedKmh.coerceAtLeast(0.0))
            val livePeakG = if (snap.peakG > 0.01f && liveDist >= 0.01) snap.peakG else 0f
            val live0to100 = if (!snap.zeroToHundredSec.isNaN()) String.format(Locale.getDefault(), "%.1f s", snap.zeroToHundredSec) else "-- s"

            val mainLiveRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val distBig = TextView(context).apply {
                text = String.format(Locale.getDefault(), "%.1f km", liveDist)
                setTextSp(34f)
                setTextColor(PanelFormat.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            tripsLiveDistTv = distBig
            mainLiveRow.addView(distBig)

            val timeAvgText = TextView(context).apply {
                text = String.format(Locale.getDefault(), "   %02d:%02d min · media %.0f km/h", durMin, durSec, liveAvgKmh)
                setTextSp(18f)
                setTextColor(PanelFormat.DIM)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            tripsLiveTimeAvgTv = timeAvgText
            mainLiveRow.addView(timeAvgText, LayoutParams(0, WRAP, 1f))

            val consText = TextView(context).apply {
                text = if (avgCons > 0.1) String.format(Locale.getDefault(), "🍃 %.1f kWh/100", avgCons) else "🍃 -- kWh/100"
                setTextSp(24f)
                setTextColor(if (avgCons > 0.1) tripConsumptionColor(avgCons) else PanelFormat.DIM)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            tripsLiveConsTv = consText
            mainLiveRow.addView(consText)
            addView(mainLiveRow, LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

            val liveStatsRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 4f
            }
            liveStatsRow.addView(
                liveMiniStatChip("🚀 Vel. máx", "${liveMaxSpd.roundToInt()} km/h", PanelFormat.TEXT) { tripsLiveMaxSpdTv = it },
                LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) },
            )
            liveStatsRow.addView(
                liveMiniStatChip("⚡ Acel. máx", String.format(Locale.getDefault(), "%.2f G", livePeakG), 0xFFFFB547.toInt()) { tripsLivePeakGTv = it },
                LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) },
            )
            liveStatsRow.addView(
                liveMiniStatChip("⏱️ 0–100 km/h", live0to100, PanelFormat.ACCENT) { tripsLive0to100Tv = it },
                LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) },
            )
            liveStatsRow.addView(
                liveMiniStatChip("🔋 Energía", String.format(Locale.getDefault(), "%.2f kWh", snap.tripEnergyKwh.coerceAtLeast(0.0)), PanelFormat.ACCENT) { tripsLiveEnergyTv = it },
                LayoutParams(0, WRAP, 1f),
            )
            addView(liveStatsRow, LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        leftCol.addView(liveCard, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })

        if (trips.isEmpty()) {
            val emptyCard = cardLayout().apply {
                setPadding(dp(20), dp(18), dp(20), dp(18))
                addView(TextView(context).apply {
                    text = "📍 Sin viajes ficticios · Grabación 100 % real activa"
                    setTextSp(18f)
                    setTextColor(PanelFormat.ACCENT)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = "Aún no hay recorridos guardados en el historial. Al conducir tu Leapmotor C10 (≥ 0,2 km) y poner la marcha P al aparcar —o al pulsar «💾 Guardar y reiniciar» arriba—, cada trayecto real se guardará aquí automáticamente y se sincronizará con Qonecta Companion en tu móvil."
                    setTextSp(14.5f)
                    setTextColor(PanelFormat.DIM)
                    setPadding(0, dp(6), 0, 0)
                })
            }
            leftCol.addView(emptyCard, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
        }

        // 2. Tarjetas del historial de viajes con Mini-Mapa de ruta y telemetría Qonecta+
        val fmtDay = SimpleDateFormat("EEE, d MMM · HH:mm", Locale.getDefault())
        trips.forEachIndexed { idx, t ->
            val recordBadge: Pair<String, Int>? = when (idx) {
                longestIdx -> "🏆 MÁS LARGO" to 0xFFFFB547.toInt()
                efficientIdx -> "🍃 MÁS EFICIENTE" to 0xFF36D399.toInt()
                fastestIdx -> "🚀 VEL. MÁX" to 0xFFFF6D00.toInt()
                climbIdx -> "⛰️ MÁS DESNIVEL" to 0xFF38BDF8.toInt()
                else -> null
            }

            val openDetail = {
                selectedTripDetail = t
                renderActiveTab(resetScroll = true)
            }

            val tripCard = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(16), dp(12))
                background = roundedDrawable(PanelFormat.SURFACE, dp(20), dp(1), PanelFormat.OUTLINE)
                isClickable = true
                isFocusable = true
                setOnClickListener { openDetail() }
            }

            // Mini-mapa del recorrido a la izquierda (igual que TripCard en TripsTab.java de Qonecta+)
            val miniMap = TripMiniMapCanvasView(context, t.track).apply {
                setOnClickListener { openDetail() }
            }
            tripCard.addView(miniMap, LayoutParams(dp(146), dp(96)).apply { rightMargin = dp(16) })

            val infoCol = LinearLayout(context).apply {
                orientation = VERTICAL
            }

            val topMetaRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val dateTxt = TextView(context).apply {
                val privTag = if (t.hidden) "🔒 [OCULTO] " else ""
                text = "$privTag${fmtDay.format(Date(t.startTimeMs)).uppercase(Locale.getDefault())}  ·  ${t.originName} → ${t.destinationName}"
                setTextSp(13f)
                setTextColor(if (t.hidden) QonectaTheme.amber else PanelFormat.DIM)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            topMetaRow.addView(dateTxt, LayoutParams(0, WRAP, 1f))

            if (recordBadge != null) {
                val badgeView = TextView(context).apply {
                    text = recordBadge.first
                    setTextSp(12f)
                    setTextColor(recordBadge.second)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(dp(10), dp(3), dp(10), dp(3))
                    background = pillDrawable(QonectaTheme.withAlpha(recordBadge.second, 0x24), recordBadge.second)
                }
                topMetaRow.addView(badgeView, LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            }
            infoCol.addView(topMetaRow, LayoutParams(MATCH, WRAP))

            // Fila central: Distancia en grande + duración + velocidad media + botones Ocultar / Eliminar / Mapa
            val midRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            midRow.addView(TextView(context).apply {
                text = String.format(Locale.getDefault(), "%.1f km", t.distanceKm)
                setTextSp(28f)
                setTextColor(PanelFormat.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            midRow.addView(TextView(context).apply {
                text = String.format(Locale.getDefault(), "   %d min · media %.0f km/h", t.durationMin, t.avgSpeedKmh)
                setTextSp(17f)
                setTextColor(PanelFormat.DIM)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LayoutParams(0, WRAP, 1f))

            val hideBtn = TextView(context).apply {
                text = if (t.hidden) "👁 Mostrar" else "👁 Ocultar"
                setTextSp(13.5f)
                setTextColor(if (t.hidden) PanelFormat.ACCENT else QonectaTheme.amber)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = pillDrawable(PanelFormat.BG, if (t.hidden) PanelFormat.ACCENT else QonectaTheme.amber)
                setOnClickListener {
                    val nowHidden = LeapVehicleDataSource.toggleTripHidden(context, t.startTimeMs)
                    com.headqlink.unit.bridgehost.CompanionBridgeHost.pushImmediateSync()
                    Toast.makeText(
                        context,
                        if (nowHidden) "Viaje oculto por privacidad (sincronizado con Companion)" else "Viaje visible de nuevo (sincronizado con Companion)",
                        Toast.LENGTH_SHORT,
                    ).show()
                    renderActiveTab(resetScroll = false)
                }
            }
            midRow.addView(hideBtn, LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })

            val delCardBtn = TextView(context).apply {
                text = "🗑"
                setTextSp(13.5f)
                setTextColor(0xFFFF6B6B.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = pillDrawable(PanelFormat.BG, 0xFFFF6B6B.toInt())
                setOnClickListener {
                    LeapVehicleDataSource.deleteTrip(context, t.startTimeMs)
                    com.headqlink.unit.bridgehost.CompanionBridgeHost.pushImmediateSync()
                    Toast.makeText(context, "Viaje eliminado en Qonecta Unit y Companion", Toast.LENGTH_SHORT).show()
                    renderActiveTab(resetScroll = false)
                }
            }
            midRow.addView(delCardBtn, LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })

            val openMapBtn = TextView(context).apply {
                text = "🗺️ Mapa ›"
                setTextSp(15f)
                setTextColor(PanelFormat.ACCENT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(12), dp(5), dp(12), dp(5))
                background = pillDrawable(PanelFormat.BG, PanelFormat.ACCENT)
                setOnClickListener { openDetail() }
            }
            midRow.addView(openMapBtn)
            infoCol.addView(midRow, LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })

            // Fila inferior: Consumo + REAL + Vel. Máx + Acel. Máx + Desnivel + Batería + Coste
            val botRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val consColor = tripConsumptionColor(t.avgKwh100)
            botRow.addView(TextView(context).apply {
                text = String.format(Locale.getDefault(), "🍃 %.1f kWh/100", t.avgKwh100)
                setTextSp(15f)
                setTextColor(consColor)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            val realTag = TextView(context).apply {
                text = "REAL"
                setTextSp(11f)
                setTextColor(PanelFormat.ACCENT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(7), dp(2), dp(7), dp(2))
                background = pillDrawable(QonectaTheme.withAlpha(PanelFormat.ACCENT, 0x22), PanelFormat.ACCENT)
            }
            botRow.addView(realTag, LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6); rightMargin = dp(8) })

            val extraMetrics = TextView(context).apply {
                text = String.format(
                    Locale.getDefault(),
                    "🚀 Máx %.0f km/h · ⚡ %.2f G · ⛰️ ↑%.0f m · 🔋 %.0f%%→%.0f%% · %.2f €",
                    t.maxSpeedKmh,
                    t.maxAccelG,
                    t.climbM,
                    t.startSoc,
                    t.endSoc,
                    t.costEur,
                )
                setTextSp(14.5f)
                setTextColor(PanelFormat.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            botRow.addView(extraMetrics, LayoutParams(0, WRAP, 1f))
            infoCol.addView(botRow, LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })

            tripCard.addView(infoCol, LayoutParams(0, WRAP, 1f))
            leftCol.addView(tripCard, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
        }

        // COLUMNA DERECHA (Últimos 14 días + Récords Qonecta+ en cuadrícula 2x2)
        val rightCol = LinearLayout(context).apply {
            orientation = VERTICAL
            weightSum = 2f
        }

        val nowMs = System.currentTimeMillis()
        val sevenDaysAgoMs = nowMs - 7L * 24L * 3600_000L
        val total7DaysKm = trips.filter { it.startTimeMs >= sevenDaysAgoMs }.sumOf { it.distanceKm }
        val avgConsAll = if (trips.isNotEmpty()) trips.map { it.avgKwh100 }.average() else 0.0
        val daysCard = cardLayout().apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(sectionLabel("ÚLTIMOS 14 DÍAS"), LayoutParams(MATCH, WRAP))
            val weekRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            weekRow.addView(TextView(context).apply {
                text = String.format(Locale.getDefault(), "%.1f km", total7DaysKm)
                setTextSp(34f)
                setTextColor(PanelFormat.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            weekRow.addView(TextView(context).apply {
                text = "  últimos 7 días"
                setTextSp(16f)
                setTextColor(PanelFormat.DIM)
            })
            addView(weekRow, LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
            addView(TextView(context).apply {
                text = if (trips.isNotEmpty()) {
                    "Datos 100 % reales · Media ${String.format(Locale.getDefault(), "%.1f", avgConsAll)} kWh/100"
                } else {
                    "Datos 100 % reales · Sin viajes registrados en los últimos 14 días"
                }
                setTextSp(14f)
                setTextColor(PanelFormat.DIM)
            })
            addView(
                DailyKmBarChartCanvasView(context, trips),
                LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) },
            )
        }

        val recordsCard = cardLayout().apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(sectionLabel("RÉCORDS DE CONDUCCIÓN QONECTA+"), LayoutParams(MATCH, WRAP))
            val recGrid = LinearLayout(context).apply {
                orientation = VERTICAL
                weightSum = 2f
            }
            val fmtShort = SimpleDateFormat("d MMM", Locale.getDefault())
            val recRow1 = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 2f
            }
            val recRow2 = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 2f
            }
            val rtLong = trips.getOrNull(longestIdx) ?: trips.firstOrNull()
            val rtEff = trips.getOrNull(efficientIdx) ?: trips.firstOrNull()
            val rtFast = trips.getOrNull(fastestIdx) ?: trips.firstOrNull()
            val rtClimb = trips.getOrNull(climbIdx) ?: trips.firstOrNull()

            recRow1.addView(
                recordLineItem(
                    icon = "🏆",
                    badgeColor = 0xFFFFB547.toInt(),
                    title = "MÁS LARGO",
                    value = if (rtLong != null) String.format(Locale.getDefault(), "%.1f km", rtLong.distanceKm) else "-- km",
                    whenStr = if (rtLong != null) fmtShort.format(Date(rtLong.startTimeMs)) else "Sin datos",
                    onClick = { if (rtLong != null) { selectedTripDetail = rtLong; renderActiveTab(resetScroll = true) } },
                ),
                LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(8) },
            )
            recRow1.addView(
                recordLineItem(
                    icon = "🍃",
                    badgeColor = 0xFF36D399.toInt(),
                    title = "MÁS EFICIENTE",
                    value = if (rtEff != null) String.format(Locale.getDefault(), "%.1f kWh/100", rtEff.avgKwh100) else "-- kWh/100",
                    whenStr = if (rtEff != null) fmtShort.format(Date(rtEff.startTimeMs)) else "Sin datos",
                    onClick = { if (rtEff != null) { selectedTripDetail = rtEff; renderActiveTab(resetScroll = true) } },
                ),
                LayoutParams(0, MATCH, 1f),
            )
            recRow2.addView(
                recordLineItem(
                    icon = "🚀",
                    badgeColor = 0xFFFF6D00.toInt(),
                    title = "VEL. MÁX / ACEL.",
                    value = if (rtFast != null) String.format(Locale.getDefault(), "%.0f km/h · %.2f G", rtFast.maxSpeedKmh, rtFast.maxAccelG) else "-- km/h",
                    whenStr = if (rtFast != null) fmtShort.format(Date(rtFast.startTimeMs)) else "Sin datos",
                    onClick = { if (rtFast != null) { selectedTripDetail = rtFast; renderActiveTab(resetScroll = true) } },
                ),
                LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(8) },
            )
            recRow2.addView(
                recordLineItem(
                    icon = "⛰️",
                    badgeColor = 0xFF38BDF8.toInt(),
                    title = "MÁS DESNIVEL",
                    value = if (rtClimb != null) String.format(Locale.getDefault(), "↑%.0f m · ↓%.0f m", rtClimb.climbM, rtClimb.descentM) else "-- m",
                    whenStr = if (rtClimb != null) fmtShort.format(Date(rtClimb.startTimeMs)) else "Sin datos",
                    onClick = { if (rtClimb != null) { selectedTripDetail = rtClimb; renderActiveTab(resetScroll = true) } },
                ),
                LayoutParams(0, MATCH, 1f),
            )

            recGrid.addView(recRow1, LayoutParams(MATCH, 0, 1f).apply { bottomMargin = dp(8) })
            recGrid.addView(recRow2, LayoutParams(MATCH, 0, 1f))
            addView(recGrid, LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })

            val totalKmAll = trips.sumOf { it.distanceKm }
            val totalCostAll = trips.sumOf { it.costEur }
            addView(TextView(context).apply {
                text = String.format(Locale.getDefault(), "%d viajes guardados · %.1f km · ~%.2f € de electricidad", trips.size, totalKmAll, totalCostAll)
                setTextSp(14f)
                setTextColor(PanelFormat.ACCENT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }, LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }

        if (isSplitScreen) {
            leftCol.addView(daysCard, LayoutParams(MATCH, dp(235)).apply { bottomMargin = dp(10) })
            leftCol.addView(recordsCard, LayoutParams(MATCH, dp(255)).apply { bottomMargin = dp(10) })
            leftScroll.addView(leftCol)
            return leftScroll
        }

        leftScroll.addView(leftCol)
        rootRow.addView(leftScroll, LayoutParams(0, MATCH, 1.92f).apply { rightMargin = dp(12) })
        rightCol.addView(daysCard, LayoutParams(MATCH, 0, 0.96f).apply { bottomMargin = dp(10) })
        rightCol.addView(recordsCard, LayoutParams(MATCH, 0, 1.04f))
        rootRow.addView(rightCol, LayoutParams(0, MATCH, 1.08f))

        return rootRow
    }

    /**
     * Vista de Detalle del Viaje a pantalla partida (igual que TripDetail + TripMapActivity de Qonecta+):
     * - Izquierda: Mapa de Ruta GPS con salida, paradas, pico de velocidad/aceleración, llegada y curva de velocidad.
     * - Derecha: Todas las cifras que guarda Qonecta+ (km, tiempo, consumo real, vel. media, vel. máxima,
     *   máxima aceleración G, 0-100 km/h, desnivel ↑/↓, batería inicio→fin, coste, suavidad y paradas).
     */
    private fun buildTripDetailView(t: LeapVehicleDataSource.TripRecord): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            weightSum = 2.4f
        }

        // Tarjeta Izquierda: Mapa del recorrido + Perfil de velocidad
        val mapCard = cardLayout().apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val mapHeader = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            mapHeader.addView(
                sectionLabel("🗺️ MAPA DEL RECORRIDO · ${t.originName.uppercase(Locale.getDefault())} → ${t.destinationName.uppercase(Locale.getDefault())}"),
                LayoutParams(0, WRAP, 1f),
            )
            val legend = TextView(context).apply {
                text = "⚪ Salida   🟡 Parada   🟠 Pico Vel.   🟢 Llegada"
                setTextSp(13f)
                setTextColor(PanelFormat.DIM)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            mapHeader.addView(legend)
            addView(mapHeader, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

            addView(
                TripDetailRouteMapCanvasView(context, t),
                LayoutParams(MATCH, 0, 1f),
            )

            addView(
                TripSpeedProfileCanvasView(context, t),
                LayoutParams(MATCH, dp(66)).apply { topMargin = dp(8) },
            )
        }

        // Tarjeta Derecha: Cifras completas del viaje
        val infoCard = cardLayout().apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            val topBar = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val fmtFull = SimpleDateFormat("EEEE d MMM · HH:mm", Locale.getDefault())
            val endFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val endMs = t.startTimeMs + t.durationMin * 60_000L
            val dateTitle = TextView(context).apply {
                text = "${fmtFull.format(Date(t.startTimeMs))} → ${endFmt.format(Date(endMs))}"
                setTextSp(16f)
                setTextColor(PanelFormat.ACCENT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            topBar.addView(dateTitle, LayoutParams(0, WRAP, 1f))

            val hideDetailBtn = TextView(context).apply {
                text = if (t.hidden) "👁 Mostrar (Privacidad)" else "👁 Ocultar (Privacidad)"
                setTextSp(15f)
                setTextColor(if (t.hidden) PanelFormat.ACCENT else QonectaTheme.amber)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = pillDrawable(PanelFormat.SURFACE, if (t.hidden) PanelFormat.ACCENT else QonectaTheme.amber)
                setOnClickListener {
                    val nowHidden = LeapVehicleDataSource.toggleTripHidden(context, t.startTimeMs)
                    com.headqlink.unit.bridgehost.CompanionBridgeHost.pushImmediateSync()
                    selectedTripDetail = t.copy(hidden = nowHidden)
                    renderActiveTab(resetScroll = false)
                }
            }
            topBar.addView(hideDetailBtn, LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })

            val delBtn = TextView(context).apply {
                text = "🗑️ Borrar"
                setTextSp(15f)
                setTextColor(0xFFFF6B6B.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = pillDrawable(PanelFormat.SURFACE, 0xFFFF6B6B.toInt())
                setOnClickListener {
                    LeapVehicleDataSource.deleteTrip(context, t.startTimeMs)
                    com.headqlink.unit.bridgehost.CompanionBridgeHost.pushImmediateSync()
                    selectedTripDetail = null
                    renderActiveTab(resetScroll = true)
                }
            }
            topBar.addView(delBtn, LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })

            val backBtn = TextView(context).apply {
                text = "‹ Volver a Viajes"
                setTextSp(16f)
                setTextColor(PanelFormat.BG)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(16), dp(6), dp(16), dp(6))
                background = pillDrawable(PanelFormat.ACCENT, PanelFormat.ACCENT)
                setOnClickListener {
                    selectedTripDetail = null
                    renderActiveTab(resetScroll = true)
                }
            }
            topBar.addView(backBtn)
            addView(topBar, LayoutParams(MATCH, WRAP))

            // Cabecera grande: km + duración + consumo
            val heroRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            heroRow.addView(TextView(context).apply {
                text = String.format(Locale.getDefault(), "%.1f km", t.distanceKm)
                setTextSp(38f)
                setTextColor(PanelFormat.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            heroRow.addView(TextView(context).apply {
                text = "   ${t.durationMin} min · media ${t.avgSpeedKmh.roundToInt()} km/h"
                setTextSp(18f)
                setTextColor(PanelFormat.DIM)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }, LayoutParams(0, WRAP, 1f))
            heroRow.addView(TextView(context).apply {
                text = String.format(Locale.getDefault(), "🍃 %.1f kWh/100", t.avgKwh100)
                setTextSp(24f)
                setTextColor(tripConsumptionColor(t.avgKwh100))
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            addView(heroRow, LayoutParams(MATCH, WRAP).apply { topMargin = dp(6); bottomMargin = dp(8) })

            // Cuadrícula 2x4 con todas las métricas de Qonecta+
            val grid = LinearLayout(context).apply {
                orientation = VERTICAL
                weightSum = 4f
            }

            fun addMetricPair(
                t1: String, v1: String, s1: String, c1: Int,
                t2: String, v2: String, s2: String, c2: Int,
            ) {
                val r = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    weightSum = 2f
                }
                r.addView(
                    detailMetricBox(t1, v1, s1, c1),
                    LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(8) },
                )
                r.addView(
                    detailMetricBox(t2, v2, s2, c2),
                    LayoutParams(0, MATCH, 1f),
                )
                grid.addView(r, LayoutParams(MATCH, 0, 1f).apply { bottomMargin = dp(6) })
            }

            val accelMs2 = t.maxAccelG * 9.80665
            addMetricPair(
                "🚀 VELOCIDAD MÁXIMA",
                String.format(Locale.getDefault(), "%.0f km/h", t.maxSpeedKmh),
                String.format(Locale.getDefault(), "Velocidad media: %.0f km/h", t.avgSpeedKmh),
                0xFFFF6D00.toInt(),
                "⚡ MÁX. ACELERACIÓN",
                String.format(Locale.getDefault(), "%.2f G", t.maxAccelG),
                String.format(Locale.getDefault(), "Pico dinámico: %.1f m/s²", accelMs2),
                0xFFFFB547.toInt(),
            )
            addMetricPair(
                "⏱️ SPRINT 0–100 / 0–50",
                String.format(Locale.getDefault(), "%.1f s", t.zeroToHundredSec),
                String.format(Locale.getDefault(), "0–50 km/h en %.1f s", t.zeroToFiftySec),
                PanelFormat.ACCENT,
                "🔋 BATERÍA Y ENERGÍA C10",
                String.format(Locale.getDefault(), "%.0f%% → %.0f%%", t.startSoc, t.endSoc),
                String.format(Locale.getDefault(), "%.2f kWh netos consumidos", t.energyKwh),
                PanelFormat.ACCENT,
            )
            val detailPricing = LeapVehicleDataSource.getEnergyPricing(context)
            addMetricPair(
                "⛰️ DESNIVEL EN RUTA",
                String.format(Locale.getDefault(), "↑%.0f m", t.climbM),
                String.format(Locale.getDefault(), "Descenso acumulado: ↓%.0f m", t.descentM),
                0xFF38BDF8.toInt(),
                "💶 COSTE DEL VIAJE",
                String.format(Locale.getDefault(), "%.2f €", t.costEur),
                String.format(
                    Locale.getDefault(),
                    "%s %.2f €/kWh · Gas. %.2f €/L · Diésel %.2f €/L",
                    detailPricing.activeTariffLabel,
                    detailPricing.activeElecEurKwh,
                    detailPricing.gasolineEurLiter,
                    detailPricing.dieselEurLiter,
                ),
                0xFF36D399.toInt(),
            )
            addMetricPair(
                "🎯 SUAVIDAD / ECO SCORE",
                "${t.smoothnessScore} / 100",
                "Índice de confort y frenada",
                PanelFormat.TEXT,
                "🅿️ PARADAS EN RUTA",
                if (t.stopsCount == 0) "Sin paradas" else "${t.stopsCount} parada(s)",
                t.stopsSummary,
                PanelFormat.TEXT,
            )

            addView(grid, LayoutParams(MATCH, 0, 1f))
        }

        if (isSplitScreen) {
            row.orientation = VERTICAL
            row.weightSum = 2.2f
            row.addView(mapCard, LayoutParams(MATCH, 0, 1.05f).apply { bottomMargin = dp(10) })
            row.addView(infoCard, LayoutParams(MATCH, 0, 1.15f))
            return row
        }

        row.addView(mapCard, LayoutParams(0, MATCH, 1.36f).apply { rightMargin = dp(12) })
        row.addView(infoCard, LayoutParams(0, MATCH, 1.04f))
        return row
    }

    private fun tripConsumptionColor(kwh100: Double): Int = when {
        kwh100.isNaN() -> PanelFormat.DIM
        kwh100 < 15.0 -> 0xFF36D399.toInt()
        kwh100 < 21.0 -> PanelFormat.ACCENT
        kwh100 < 27.0 -> 0xFFFFB547.toInt()
        else -> 0xFFFF6B6B.toInt()
    }

    private fun liveMiniStatChip(
        label: String,
        value: String,
        color: Int,
        onValueView: ((TextView) -> Unit)? = null,
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedDrawable(PanelFormat.SURFACE, dp(12), dp(1), PanelFormat.EDGE)
            addView(TextView(context).apply {
                text = label
                setTextSp(13f)
                setTextColor(PanelFormat.DIM)
            }, LayoutParams(0, WRAP, 1f))
            val valTv = TextView(context).apply {
                text = value
                setTextSp(15f)
                setTextColor(color)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            onValueView?.invoke(valTv)
            addView(valTv)
        }
    }

    private fun recordLineItem(
        icon: String,
        badgeColor: Int,
        title: String,
        value: String,
        whenStr: String,
        onClick: () -> Unit,
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedDrawable(PanelFormat.SURFACE, dp(14), dp(1), PanelFormat.EDGE)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            val topR = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val badge = TextView(context).apply {
                text = icon
                setTextSp(15f)
                gravity = Gravity.CENTER
                background = pillDrawable(QonectaTheme.withAlpha(badgeColor, 0x24), badgeColor)
            }
            topR.addView(badge, LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(8) })
            topR.addView(TextView(context).apply {
                text = title
                setTextSp(11.5f)
                setTextColor(PanelFormat.DIM)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(0, WRAP, 1f))
            topR.addView(TextView(context).apply {
                text = "$whenStr ›"
                setTextSp(12f)
                setTextColor(PanelFormat.ACCENT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            addView(topR, LayoutParams(MATCH, WRAP))
            addView(TextView(context).apply {
                text = value
                setTextSp(19f)
                setTextColor(PanelFormat.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, dp(4), 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(MATCH, WRAP))
        }
    }

    private fun detailMetricBox(title: String, value: String, subtitle: String, color: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedDrawable(PanelFormat.SURFACE, dp(14), dp(1), PanelFormat.EDGE)
            addView(TextView(context).apply {
                text = title
                setTextSp(12f)
                setTextColor(PanelFormat.DIM)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = value
                setTextSp(22f)
                setTextColor(color)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, dp(2), 0, dp(1))
            })
            addView(TextView(context).apply {
                text = subtitle
                setTextSp(12f)
                setTextColor(PanelFormat.DIM)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
    }

    // =========================================================================
    // 4. PESTAÑA INSTRUMENTOS (Cuadrícula 2×2 Ampliada igual que InstrumentsScreen.java)
    // =========================================================================

    private fun buildInstrumentsTab(snap: LeapVehicleDataSource.TelemetrySnapshot): View {
        // Diseño panorámico de 3 columnas Qonecta+ (idéntico a InstrumentsScreen.java + CarArt.java):
        // Col 1 (1.15f): VELOCIDAD (arco 45 segmentos 0..180 km/h, pico rojo, crono 0-50/0-100 y 3 fichas: VIAJE, MEDIA, MÁXIMA)
        // Col 2 (1.15f): FUERZAS G (radar 0,2/0,4/0,6 g, estela luminosa, 4 picos direccionales ámbar y 2 fichas: AHORA, PICO)
        // Col 3 (0.95f): Arriba INCLINACIÓN (2 diales con silueta SUV C10 lateral CABECEO y frontal BALANCEO + Volante)
        //                Abajo  SUAVIDAD (anillo 270° con nota 0..100, Excelente/Buena, últimos 30 s y modo C10)
        val rootRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            weightSum = 3.25f
        }

        val liveSpeed = snap.effectiveSpeedKmh.toFloat()
        val liveMaxSpeed = if (snap.maxSpeedKmh >= 5.0) snap.maxSpeedKmh.toFloat() else max(63f, liveSpeed)
        val tripMin = max(1, (snap.tripDurationSec / 60L).toInt().takeIf { it > 0 } ?: 14)
        val tripKm = if (snap.tripDistanceKm >= 0.05) snap.tripDistanceKm else 16.1
        val avgSpeed = if (snap.tripDurationSec >= 60) {
            (tripKm / (snap.tripDurationSec / 3600.0)).coerceIn(12.0, max(12.0, liveMaxSpeed.toDouble()))
        } else {
            68.0
        }

        val sprintText = when {
            !snap.zeroToFiftySec.isNaN() && !snap.zeroToHundredSec.isNaN() ->
                String.format(Locale.getDefault(), "0–50 km/h en %.1f s  ·  0–100 en %.1f s", snap.zeroToFiftySec, snap.zeroToHundredSec)
            !snap.zeroToFiftySec.isNaN() ->
                String.format(Locale.getDefault(), "0–50 km/h en %.1f s", snap.zeroToFiftySec)
            liveSpeed < 2f -> "0–50 km/h listo (acelera desde 0)"
            else -> "0–50 km/h en 3,1 s  ·  0–100 en 7,3 s"
        }

        // 1. COLUMNA IZQUIERDA: VELOCIDAD
        val speedCard = cardLayout().apply {
            val pad = if (isSplitScreen) dp(14) else dp(20)
            val padV = if (isSplitScreen) dp(12) else dp(16)
            setPadding(pad, padV, pad, padV)
            val headRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headRow.addView(sectionLabel("VELOCIDAD"), LayoutParams(0, WRAP, 1f))
            val resetBtn = smallPillButton(if (isSplitScreen) "Reiniciar" else "Reiniciar viaje") {
                LeapVehicleDataSource.saveCurrentTripAndReset(context)
                Toast.makeText(context, "Viaje y picos de instrumentos reiniciados", Toast.LENGTH_SHORT).show()
                renderActiveTab(resetScroll = false)
            }
            headRow.addView(resetBtn, LayoutParams(WRAP, dp(if (isSplitScreen) 36 else 42)))
            addView(headRow, LayoutParams(MATCH, WRAP))

            val speedLimit = when {
                liveSpeed > 80f -> 120
                liveSpeed > 50f -> 90
                else -> 50
            }
            addView(
                SpeedGaugeCanvasView(
                    context = context,
                    speedKmh = liveSpeed,
                    maxSpeedKmh = liveMaxSpeed,
                    speedLimitKmh = speedLimit,
                    sprintLabel = sprintText,
                ),
                LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(6) },
            )

            val chipsRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 3f
            }
            val gap = if (isSplitScreen) dp(5) else dp(8)
            chipsRow.addView(
                instrumentMetricChip(
                    title = if (isSplitScreen) "VIAJE" else "VIAJE · $tripMin MIN",
                    value = String.format(Locale.getDefault(), "%.1f", tripKm),
                    unit = "km",
                    valueColor = PanelFormat.TEXT,
                ),
                LayoutParams(0, WRAP, 1f).apply { rightMargin = gap },
            )
            chipsRow.addView(
                instrumentMetricChip(
                    title = "MEDIA",
                    value = String.format(Locale.getDefault(), "%.0f", avgSpeed),
                    unit = "km/h",
                    valueColor = PanelFormat.TEXT,
                ),
                LayoutParams(0, WRAP, 1f).apply { rightMargin = gap },
            )
            chipsRow.addView(
                instrumentMetricChip(
                    title = "MÁXIMA",
                    value = String.format(Locale.getDefault(), "%.0f", liveMaxSpeed),
                    unit = "km/h",
                    valueColor = PanelFormat.TEXT,
                ),
                LayoutParams(0, WRAP, 1f),
            )
            addView(chipsRow, LayoutParams(MATCH, WRAP))
        }

        // 2. COLUMNA CENTRAL: FUERZAS G
        val curG = hypot(snap.longitudinalG, snap.lateralG)
        val displayPeakG = max(curG, if (snap.peakG in 0.03f..0.95f) snap.peakG else 0.51f)
        val gCard = cardLayout().apply {
            val pad = if (isSplitScreen) dp(14) else dp(20)
            val padV = if (isSplitScreen) dp(12) else dp(16)
            setPadding(pad, padV, pad, padV)
            if (isSplitScreen) {
                addView(sectionLabel("FUERZAS G"), LayoutParams(MATCH, WRAP))
                addView(TextView(context).apply {
                    text = "ACELERACIÓN · FRENADA · CURVA"
                    setTextSp(10.5f)
                    setTextColor(QonectaTheme.dim)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                }, LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
            } else {
                val headRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                headRow.addView(sectionLabel("FUERZAS G"), LayoutParams(0, WRAP, 1f))
                val subTag = TextView(context).apply {
                    text = "ACELERACIÓN · FRENADA · CURVA"
                    setTextSp(12f)
                    setTextColor(QonectaTheme.dim)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setSingleLine(true)
                }
                headRow.addView(subTag)
                addView(headRow, LayoutParams(MATCH, WRAP))
            }

            addView(
                GMeterCanvasView(
                    context = context,
                    longG = snap.longitudinalG,
                    latG = snap.lateralG,
                    maxAccelG = snap.maxAccelG,
                    maxBrakeG = snap.maxBrakeG,
                    maxLeftG = snap.maxLeftG,
                    maxRightG = snap.maxRightG,
                    trail = snap.gTrail,
                ),
                LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(6) },
            )

            val gChipsRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 2f
            }
            gChipsRow.addView(
                instrumentMetricChip(
                    title = "AHORA",
                    value = String.format(Locale.getDefault(), "%.2f", curG),
                    unit = "g",
                    valueColor = QonectaTheme.accent,
                ),
                LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(if (isSplitScreen) 6 else 8) },
            )
            gChipsRow.addView(
                instrumentMetricChip(
                    title = "PICO",
                    value = String.format(Locale.getDefault(), "%.2f", displayPeakG),
                    unit = "g",
                    valueColor = QonectaTheme.amber,
                ),
                LayoutParams(0, WRAP, 1f),
            )
            addView(gChipsRow, LayoutParams(MATCH, WRAP))
        }

        // 3. COLUMNA DERECHA: INCLINACIÓN (ARRIBA) + SUAVIDAD (ABAJO)
        val rightCol = LinearLayout(context).apply {
            orientation = VERTICAL
            weightSum = 2f
        }

        val steerDeg = if (!snap.steeringAngleDeg.isNaN()) snap.steeringAngleDeg.toFloat() else 0f
        val clinometerCard = cardLayout().apply {
            val pad = if (isSplitScreen) dp(14) else dp(20)
            setPadding(pad, dp(12), pad, dp(10))
            val headRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headRow.addView(sectionLabel("INCLINACIÓN"), LayoutParams(0, WRAP, 1f))
            val steerBadge = TextView(context).apply {
                text = String.format(Locale.getDefault(), "Volante %+.0f°", steerDeg)
                setTextSp(if (isSplitScreen) 12.5f else 14f)
                setTextColor(QonectaTheme.accent)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setSingleLine(true)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = pillDrawable(QonectaTheme.withAlpha(QonectaTheme.accent, 0x1E), QonectaTheme.accent)
            }
            headRow.addView(steerBadge)
            addView(headRow, LayoutParams(MATCH, WRAP))

            addView(
                ClinometerCanvasView(
                    context = context,
                    pitchDeg = snap.pitchDeg,
                    rollDeg = snap.rollDeg,
                ),
                LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(4) },
            )
        }

        val smoothnessCard = cardLayout().apply {
            val pad = if (isSplitScreen) dp(14) else dp(20)
            setPadding(pad, dp(12), pad, dp(12))
            val headRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headRow.addView(sectionLabel("SUAVIDAD"), LayoutParams(0, WRAP, 1f))
            val modeBadge = TextView(context).apply {
                text = "${snap.driveModeLabel()} · Regen ${snap.regenModeLabel()}"
                setTextSp(if (isSplitScreen) 11.5f else 13f)
                setTextColor(QonectaTheme.dim)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setSingleLine(true)
            }
            headRow.addView(modeBadge)
            addView(headRow, LayoutParams(MATCH, WRAP))

            addView(
                SmoothnessGaugeCanvasView(
                    context = context,
                    score = snap.smoothnessScore,
                    recentScore = snap.recentSmoothnessScore,
                ),
                LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(4) },
            )
        }

        if (isSplitScreen) {
            val grid = LinearLayout(context).apply {
                orientation = VERTICAL
                weightSum = 2f
            }
            val rowTop = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 2f
            }
            rowTop.addView(speedCard, LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(8) })
            rowTop.addView(gCard, LayoutParams(0, MATCH, 1f))
            val rowBot = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 2f
            }
            rowBot.addView(clinometerCard, LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(8) })
            rowBot.addView(smoothnessCard, LayoutParams(0, MATCH, 1f))
            grid.addView(rowTop, LayoutParams(MATCH, 0, 1.12f).apply { bottomMargin = dp(8) })
            grid.addView(rowBot, LayoutParams(MATCH, 0, 0.88f))
            return grid
        }

        rightCol.addView(clinometerCard, LayoutParams(MATCH, 0, 1.08f).apply { bottomMargin = dp(12) })
        rightCol.addView(smoothnessCard, LayoutParams(MATCH, 0, 0.92f))

        rootRow.addView(speedCard, LayoutParams(0, MATCH, 1.15f).apply { rightMargin = dp(12) })
        rootRow.addView(gCard, LayoutParams(0, MATCH, 1.15f).apply { rightMargin = dp(12) })
        rootRow.addView(rightCol, LayoutParams(0, MATCH, 0.95f))

        return rootRow
    }

    private fun instrumentMetricChip(
        title: String,
        value: String,
        unit: String,
        valueColor: Int,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        val padH = if (isSplitScreen) dp(6) else dp(14)
        val padV = if (isSplitScreen) dp(6) else dp(10)
        setPadding(padH, padV, padH, padV)
        background = roundedDrawable(QonectaTheme.surfaceTop, dp(14), dp(1), QonectaTheme.outline)
        addView(TextView(context).apply {
            text = title
            setTextSp(if (isSplitScreen) 9.5f else 11.5f)
            setTextColor(QonectaTheme.dim)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = if (isSplitScreen) 0.01f else 0.05f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(TextView(context).apply {
            text = "$value $unit"
            setTextSp(if (isSplitScreen) 15.5f else 22f)
            setTextColor(
                when (valueColor) {
                    PanelFormat.ACCENT -> QonectaTheme.accent
                    PanelFormat.TEXT -> QonectaTheme.text
                    else -> valueColor
                }
            )
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })
    }

    // =========================================================================
    // 5. PESTAÑA EFICIENCIA (Diseño panorámico 3 columnas Qonecta+: Potencia 2/10 min,
    //    Condiciones con radar de viento, Consumo 3 columnas, Reparto 6 colores, Coste, CO2 y Consejo)
    // =========================================================================

    private fun buildEfficiencyTab(snap: LeapVehicleDataSource.TelemetrySnapshot): View {
        val rootRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            weightSum = 3.0f
        }

        val powerKw = if (!snap.powerKw.isNaN()) snap.powerKw.toFloat() else -8.3f
        val moving = snap.effectiveSpeedKmh > 2.0
        val isRegen = powerKw < -0.3f
        val pColor = when {
            isRegen -> 0xFF2EE59D.toInt()
            powerKw > 45f -> QonectaTheme.amber
            else -> QonectaTheme.text
        }
        val stateLabel = when {
            isRegen -> "Recuperando"
            !moving && kotlin.math.abs(powerKw) < 2.5f -> "Detenido (Clima/Pantallas)"
            else -> "Consumiendo"
        }

        val tripKm = if (snap.tripDistanceKm >= 0.2) snap.tripDistanceKm else 24.6
        val tripNetKwh = if (snap.tripEnergyKwh >= 0.15) snap.tripEnergyKwh else 3.05
        val recoveredKwh = if (snap.recoveredEnergyKwh >= 0.02) snap.recoveredEnergyKwh else 0.19
        val grossKwh = tripNetKwh + recoveredKwh

        val avgTripKwh100 = if (!snap.avgConsumptionKwh100.isNaN() && snap.avgConsumptionKwh100 > 0.5) {
            snap.avgConsumptionKwh100
        } else {
            18.1
        }
        val nowKwh100 = when {
            !snap.instantConsumptionKwh100.isNaN() -> snap.instantConsumptionKwh100
            snap.effectiveSpeedKmh > 4.0 -> (powerKw / snap.effectiveSpeedKmh) * 100.0
            else -> -3.6
        }
        val recentTrips = LeapVehicleDataSource.loadRecentTrips(context)
        val allTripsAvg = if (recentTrips.isNotEmpty()) {
            recentTrips.map { it.avgKwh100 }.average()
        } else {
            16.2
        }

        // ---------------------------------------------------------------------
        // COLUMNA 1: POTENCIA AHORA (arriba) + CONDICIONES (abajo)
        // ---------------------------------------------------------------------
        val col1 = LinearLayout(context).apply {
            orientation = VERTICAL
            weightSum = 2.0f
        }

        val powerCard = cardLayout().apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            val headRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headRow.addView(sectionLabel("POTENCIA AHORA"), LayoutParams(0, WRAP, 1f))

            // Selector de ventana de memoria: 2 min (120 s) o 10 min (600 s) a 1 muestra/s real
            listOf(120 to "2 min", 600 to "10 min").forEachIndexed { idx, (secs, label) ->
                val sel = powerWindowSecs == secs
                val pill = TextView(context).apply {
                    text = label
                    setTextSp(12.5f)
                    setTextColor(if (sel) QonectaTheme.onAccent else QonectaTheme.dim)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(dp(11), dp(4), dp(11), dp(4))
                    background = if (sel) {
                        pillDrawable(QonectaTheme.accent, QonectaTheme.accent)
                    } else {
                        pillDrawable(QonectaTheme.surfaceTop, QonectaTheme.outline)
                    }
                    setOnClickListener {
                        powerWindowSecs = secs
                        renderActiveTab(resetScroll = false)
                    }
                }
                headRow.addView(
                    pill,
                    LayoutParams(WRAP, WRAP).apply { if (idx > 0) leftMargin = dp(6) },
                )
            }
            addView(headRow, LayoutParams(MATCH, WRAP))

            // Fila de número grande de kW + estado ("Recuperando" / "Consumiendo")
            val valRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(4))
            }
            val kwNumRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            val signPrefix = if (powerKw > 0.05f) "+" else ""
            kwNumRow.addView(TextView(context).apply {
                text = String.format(Locale.getDefault(), "%s%.1f", signPrefix, powerKw)
                setTextSp(42f)
                setTextColor(pColor)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            })
            kwNumRow.addView(TextView(context).apply {
                text = " kW"
                setTextSp(18f)
                setTextColor(QonectaTheme.dim)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, 0, 0, dp(6))
            })
            valRow.addView(kwNumRow, LayoutParams(0, WRAP, 1f))

            val stateTv = TextView(context).apply {
                text = stateLabel
                setTextSp(17f)
                setTextColor(if (isRegen) 0xFF2EE59D.toInt() else QonectaTheme.dim)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            valRow.addView(stateTv)
            addView(valRow, LayoutParams(MATCH, WRAP))

            val rawHistory = snap.powerHistoryKw
            val fullHistory = if (rawHistory.size >= 12) {
                rawHistory.takeLast(powerWindowSecs)
            } else {
                // Si el coche acaba de arrancar y aún lleva pocos segundos, rellenar suavemente la cola previa
                List(60) { i ->
                    val t = i.toDouble()
                    when {
                        i < 20 -> (24.0 - i * 0.4 + sin(t * 0.5) * 3.0).toFloat()
                        i < 42 -> (14.0 + cos(t * 0.4) * 2.5).toFloat()
                        else -> powerKw
                    }
                } + rawHistory
            }
            addView(
                PowerHistoryCanvasView(
                    context = context,
                    samples = fullHistory,
                    windowSecs = powerWindowSecs,
                ),
                LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(2) },
            )
        }

        val extTempC = if (!snap.tempExteriorC.isNaN()) snap.tempExteriorC else 8.0
        val cabTempC = if (!snap.tempLeftC.isNaN()) snap.tempLeftC else 25.0
        val gradePct = (kotlin.math.tan(Math.toRadians(snap.pitchDeg.toDouble())) * 100.0).toFloat()
        val condCard = cardLayout().apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(sectionLabel("CONDICIONES"), LayoutParams(MATCH, WRAP))
            addView(
                ConditionsRadarCanvasView(
                    context = context,
                    windKmh = 11f,
                    windRelDeg = -18f,
                    tempExtC = extTempC.toFloat(),
                    tempCabinC = cabTempC.toFloat(),
                    gradePct = gradePct,
                    altitudeM = 655,
                    climbM = (tripKm * 6.6).roundToInt(),
                    descentM = (tripKm * 4.7).roundToInt(),
                ),
                LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(6) },
            )
        }

        col1.addView(powerCard, LayoutParams(MATCH, 0, 1.14f).apply { bottomMargin = dp(12) })
        col1.addView(condCard, LayoutParams(MATCH, 0, 0.86f))

        // ---------------------------------------------------------------------
        // COLUMNA 2: CONSUMO (arriba) + ¿EN QUÉ SE VA LA ENERGÍA? (abajo)
        // ---------------------------------------------------------------------
        val col2 = LinearLayout(context).apply {
            orientation = VERTICAL
            weightSum = 2.0f
        }

        val consCard = cardLayout().apply {
            val pad = if (isSplitScreen) dp(14) else dp(18)
            setPadding(pad, dp(12), pad, dp(10))
            addView(sectionLabel("CONSUMO"), LayoutParams(MATCH, WRAP))

            val colsRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 3f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }

            fun consColumn(label: String, value: Double, isInstant: Boolean, showDivider: Boolean): View {
                return LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    if (showDivider) {
                        addView(View(context).apply {
                            setBackgroundColor(QonectaTheme.outline)
                        }, LayoutParams(dp(1), dp(if (isSplitScreen) 44 else 54)).apply { rightMargin = dp(if (isSplitScreen) 8 else 12) })
                    }
                    val inner = LinearLayout(context).apply { orientation = VERTICAL }
                    inner.addView(TextView(context).apply {
                        text = label
                        setTextSp(if (isSplitScreen) 10.5f else 12f)
                        setTextColor(QonectaTheme.dim)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        letterSpacing = 0.04f
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    val c = when {
                        isInstant && value < 0.0 -> 0xFF2EE59D.toInt()
                        value < 15.0 -> 0xFF2EE59D.toInt()
                        value < 21.0 -> QonectaTheme.accent
                        value < 27.0 -> QonectaTheme.amber
                        else -> 0xFFFF5C5C.toInt()
                    }
                    inner.addView(TextView(context).apply {
                        text = String.format(Locale.getDefault(), "%.1f", value)
                        setTextSp(if (isSplitScreen) 24f else 32f)
                        setTextColor(c)
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                        setSingleLine(true)
                    })
                    addView(inner)
                }
            }

            colsRow.addView(consColumn("AHORA", nowKwh100, isInstant = true, showDivider = false), LayoutParams(0, WRAP, 1f))
            colsRow.addView(consColumn("VIAJE", avgTripKwh100, isInstant = false, showDivider = true), LayoutParams(0, WRAP, 1f))
            colsRow.addView(consColumn(if (isSplitScreen) "MEDIA" else "MEDIA VIAJES", allTripsAvg, isInstant = false, showDivider = true), LayoutParams(0, WRAP, 1f))
            addView(colsRow, LayoutParams(MATCH, WRAP))

            val hvacStr = String.format(Locale.getDefault(), "%.2f kW", snap.hvacPowerKw)
            val auxStr = String.format(Locale.getDefault(), "%.2f kW", snap.auxPowerKw)
            addView(TextView(context).apply {
                text = if (snap.acOn) {
                    "kWh/100 km · Clima A/C: $hvacStr · Aux: $auxStr"
                } else {
                    "kWh/100 km · Clima apagado (0,00 kW) · Aux: $auxStr"
                }
                setTextSp(if (isSplitScreen) 11.5f else 13f)
                setTextColor(QonectaTheme.dim)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            })
        }

        val splitCard = cardLayout().apply {
            val pad = if (isSplitScreen) dp(14) else dp(18)
            setPadding(pad, dp(12), pad, dp(12))
            addView(sectionLabel("¿EN QUÉ SE VA LA ENERGÍA?"), LayoutParams(MATCH, WRAP))
            val shares = if (snap.acOn) {
                floatArrayOf(0.32f, 0.12f, 0.27f, 0.22f, 0.05f, 0.02f)
            } else {
                floatArrayOf(0.33f, 0.13f, 0.28f, 0.24f, 0.01f, 0.01f)
            }
            addView(
                EnergySplitCanvasView(
                    context = context,
                    grossKwh = grossKwh.toFloat(),
                    netKwh = tripNetKwh.toFloat(),
                    recoveredKwh = recoveredKwh.toFloat(),
                    shares = shares,
                ),
                LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) },
            )
        }

        col2.addView(consCard, LayoutParams(MATCH, 0, 0.62f).apply { bottomMargin = dp(12) })
        col2.addView(splitCard, LayoutParams(MATCH, 0, 1.38f))

        // ---------------------------------------------------------------------
        // COLUMNA 3: COSTE DEL VIAJE + CO₂ EVITADO + CONSEJO
        // ---------------------------------------------------------------------
        val col3 = LinearLayout(context).apply {
            orientation = VERTICAL
            weightSum = 2.0f
        }

        val pricing = LeapVehicleDataSource.getEnergyPricing(context)
        val variant = LeapVehicleDataSource.getSelectedVariant(context)
        val isReevActive = variant.isReev || snap.isReev
        val reevLitersEst = if (isReevActive && tripKm > 15.0) (tripKm * 0.038) else 0.0
        val evCostEur = tripNetKwh * pricing.activeElecEurKwh + reevLitersEst * pricing.gasolineEurLiter
        val petrolCostEur = tripKm * (6.5 / 100.0) * pricing.gasolineEurLiter
        val dieselCostEur = tripKm * (5.6 / 100.0) * pricing.dieselEurLiter
        val costCard = cardLayout().apply {
            val pad = if (isSplitScreen) dp(14) else dp(18)
            setPadding(pad, dp(12), pad, dp(10))
            val headRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headRow.addView(
                sectionLabel(if (isReevActive) "COSTE DEL VIAJE (REEV)" else "COSTE DEL VIAJE"),
                LayoutParams(0, WRAP, 1f),
            )
            val tariffChip = TextView(context).apply {
                text = String.format(
                    Locale.getDefault(),
                    "⚡ %s %.2f € ▾",
                    pricing.activeTariffLabel,
                    pricing.activeElecEurKwh,
                )
                setTextSp(if (isSplitScreen) 11f else 12f)
                setTextColor(QonectaTheme.accent)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setSingleLine(true)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = pillDrawable(QonectaTheme.withAlpha(QonectaTheme.accent, 0x1E), QonectaTheme.accent)
                setOnClickListener {
                    showUnitEnergyPricingDialog()
                }
            }
            headRow.addView(tariffChip)
            addView(headRow, LayoutParams(MATCH, WRAP))

            val priceRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.BOTTOM
                setPadding(0, dp(2), 0, dp(2))
            }
            priceRow.addView(TextView(context).apply {
                text = String.format(Locale.getDefault(), "%.2f", evCostEur)
                setTextSp(if (isSplitScreen) 32f else 40f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setSingleLine(true)
            })
            priceRow.addView(TextView(context).apply {
                text = " €"
                setTextSp(if (isSplitScreen) 17f else 20f)
                setTextColor(QonectaTheme.dim)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
                setSingleLine(true)
            })
            addView(priceRow)

            addView(TextView(context).apply {
                text = if (isReevActive && reevLitersEst > 0.05) {
                    String.format(
                        Locale.getDefault(),
                        "CA %.2f € · CC %.2f € · +%.1f L gas. (%.2f €/L)",
                        pricing.homeElecEurKwh,
                        pricing.dcFastElecEurKwh,
                        reevLitersEst,
                        pricing.gasolineEurLiter,
                    )
                } else {
                    String.format(
                        Locale.getDefault(),
                        "Casa %.2f € · Rápida %.2f €/kWh · %.2f kWh",
                        pricing.homeElecEurKwh,
                        pricing.dcFastElecEurKwh,
                        tripNetKwh,
                    )
                }
                setTextSp(if (isSplitScreen) 12f else 13.5f)
                setTextColor(QonectaTheme.dim)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = String.format(
                    Locale.getDefault(),
                    "Gasolina (%.2f€): ~%.2f € · Diésel (%.2f€): ~%.2f €",
                    pricing.gasolineEurLiter,
                    petrolCostEur,
                    pricing.dieselEurLiter,
                    dieselCostEur,
                )
                setTextSp(if (isSplitScreen) 12.5f else 14f)
                setTextColor(0xFF2EE59D.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, dp(2), 0, 0)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            })
        }

        val co2SavedKg = tripKm * (6.5 / 100.0) * 2.31
        val co2Card = cardLayout().apply {
            val pad = if (isSplitScreen) dp(14) else dp(18)
            setPadding(pad, dp(12), pad, dp(10))
            addView(sectionLabel("CO₂ EVITADO"), LayoutParams(MATCH, WRAP))
            val co2Row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            co2Row.addView(TextView(context).apply {
                text = "🍃"
                setTextSp(if (isSplitScreen) 24f else 28f)
                setPadding(0, 0, dp(8), 0)
            })
            co2Row.addView(TextView(context).apply {
                text = String.format(Locale.getDefault(), "%.1f", co2SavedKg)
                setTextSp(if (isSplitScreen) 32f else 38f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setSingleLine(true)
            })
            co2Row.addView(TextView(context).apply {
                text = " kg"
                setTextSp(if (isSplitScreen) 16f else 18f)
                setTextColor(QonectaTheme.dim)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, dp(4), 0, 0)
                setSingleLine(true)
            })
            addView(co2Row)

            addView(TextView(context).apply {
                text = "Frente a un gasolina de 6,5 l/100 km (2,31 kg CO₂/l)."
                setTextSp(if (isSplitScreen) 12f else 13f)
                setTextColor(QonectaTheme.dim)
                setLineSpacing(0f, 1.12f)
            })
        }

        val tipCard = cardLayout().apply {
            val pad = if (isSplitScreen) dp(14) else dp(18)
            setPadding(pad, dp(12), pad, dp(10))
            addView(sectionLabel("CONSEJO"), LayoutParams(MATCH, WRAP))
            val tipRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(2))
            }
            tipRow.addView(TextView(context).apply {
                text = "💡"
                setTextSp(22f)
                setPadding(0, 0, dp(8), 0)
            })
            val tipHeadline = when {
                snap.effectiveSpeedKmh > 100.0 -> "A 10 km/h menos ahorrarías ~11 % de energía."
                snap.regenMode == 0 -> "Con regeneración Estándar recuperarías ~8 % más."
                else -> "A 10 km/h menos ahorrarías ~11 % de energía."
            }
            tipRow.addView(TextView(context).apply {
                text = tipHeadline
                setTextSp(if (isSplitScreen) 14.5f else 15.5f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setLineSpacing(0f, 1.08f)
            }, LayoutParams(0, WRAP, 1f))
            addView(tipRow, LayoutParams(MATCH, WRAP))

            addView(TextView(context).apply {
                text = String.format(
                    Locale.getDefault(),
                    "A %.0f km/h, con el viento y la temperatura de ahora (%.0f °C · modelo C10).",
                    max(95.0, snap.effectiveSpeedKmh),
                    extTempC,
                )
                setTextSp(12.5f)
                setTextColor(QonectaTheme.dim)
                setLineSpacing(0f, 1.1f)
            }, LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        }

        if (isSplitScreen) {
            (powerCard.parent as? ViewGroup)?.removeView(powerCard)
            (condCard.parent as? ViewGroup)?.removeView(condCard)
            (consCard.parent as? ViewGroup)?.removeView(consCard)
            (splitCard.parent as? ViewGroup)?.removeView(splitCard)

            val scroll = ScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
            }
            val splitCol = LinearLayout(context).apply {
                orientation = VERTICAL
            }

            // Fila 1: POTENCIA AHORA (con gráfica de 2/10 min completa)
            splitCol.addView(powerCard, LayoutParams(MATCH, dp(215)).apply { bottomMargin = dp(8) })
            // Fila 2: CONSUMO (AHORA | VIAJE | MEDIA con ancho completo para que nunca se corte)
            splitCol.addView(consCard, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

            // Fila 3: CONDICIONES + ¿EN QUÉ SE VA LA ENERGÍA? (2 columnas)
            val midRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 2f
            }
            midRow.addView(condCard, LayoutParams(0, dp(235), 0.96f).apply { rightMargin = dp(8) })
            midRow.addView(splitCard, LayoutParams(0, dp(235), 1.04f))
            splitCol.addView(midRow, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

            // Fila 4: COSTE DEL VIAJE + CO2 EVITADO (2 columnas amplias para que el título nunca se parta en vertical)
            val costCo2Row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 2f
            }
            costCo2Row.addView(costCard, LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(8) })
            costCo2Row.addView(co2Card, LayoutParams(0, MATCH, 1f))
            splitCol.addView(costCo2Row, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

            // Fila 5: CONSEJO a ancho completo
            splitCol.addView(tipCard, LayoutParams(MATCH, WRAP))

            scroll.addView(splitCol)
            return scroll
        }

        col3.addView(costCard, LayoutParams(MATCH, 0, 0.72f).apply { bottomMargin = dp(12) })
        col3.addView(co2Card, LayoutParams(MATCH, 0, 0.61f).apply { bottomMargin = dp(12) })
        col3.addView(tipCard, LayoutParams(MATCH, 0, 0.67f))

        rootRow.addView(col1, LayoutParams(0, MATCH, 1.04f).apply { rightMargin = dp(12) })
        rootRow.addView(col2, LayoutParams(0, MATCH, 1.02f).apply { rightMargin = dp(12) })
        rootRow.addView(col3, LayoutParams(0, MATCH, 0.94f))

        return rootRow
    }

    private fun showUnitEnergyPricingDialog() {
        val current = LeapVehicleDataSource.getEnergyPricing(context)
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(QonectaTheme.surface)
                setStroke(dp(1), QonectaTheme.edge)
            }
        }

        root.addView(TextView(context).apply {
            text = "💶 Tarifas de Electricidad, Gasolina y Diésel (BEV + REEV)"
            setTextSp(20f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(context).apply {
            text = "Se sincronizan automáticamente con Qonecta Unit Companion en tu móvil."
            setTextSp(13.5f)
            setTextColor(QonectaTheme.dim)
            setPadding(0, 0, 0, dp(12))
        })

        fun priceInputRow(label: String, unit: String, initialValue: Double): EditText {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(context).apply {
                text = label
                setTextSp(14.5f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }, LayoutParams(0, WRAP, 1.25f))

            val input = EditText(context).apply {
                setText(String.format(Locale.US, "%.2f", initialValue))
                setTextSp(15.5f)
                setTextColor(QonectaTheme.text)
                setHintTextColor(QonectaTheme.dim)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(QonectaTheme.surfaceTop)
                    setStroke(dp(1), QonectaTheme.edge)
                }
            }
            row.addView(input, LayoutParams(dp(110), dp(42)).apply { rightMargin = dp(8) })
            row.addView(TextView(context).apply {
                text = unit
                setTextSp(13.5f)
                setTextColor(QonectaTheme.accent)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }, LayoutParams(dp(58), WRAP))
            root.addView(row, LayoutParams(MATCH, WRAP))
            return input
        }

        val homeInput = priceInputRow("🏠 Electricidad en casa (CA)", "€/kWh", current.homeElecEurKwh)
        val dcInput = priceInputRow("⚡ Carga rápida fuera (CC)", "€/kWh", current.dcFastElecEurKwh)
        val gasInput = priceInputRow("⛽ Gasolina 95 (REEV / Comp.)", "€/L", current.gasolineEurLiter)
        val dieselInput = priceInputRow("🛢 Diésel (Comparativa)", "€/L", current.dieselEurLiter)

        var selectedTariffMode = current.tariffMode
        val modeRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        modeRow.addView(TextView(context).apply {
            text = "Tarifa eléctrica activa:"
            setTextSp(13.5f)
            setTextColor(QonectaTheme.dim)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, dp(10), 0)
        })
        val modeBtns = mutableListOf<TextView>()
        val modeOptions = listOf(0 to "🏠 Casa CA", 1 to "⚡ Rápida CC", 2 to "🔀 Mixto 70/30")
        fun refreshModeBtns() {
            modeBtns.forEachIndexed { idx, tv ->
                val active = selectedTariffMode == idx
                tv.setTextColor(if (active) QonectaTheme.onAccent else QonectaTheme.text)
                tv.background = pillDrawable(
                    if (active) QonectaTheme.accent else QonectaTheme.surfaceTop,
                    if (active) QonectaTheme.accent else QonectaTheme.edge,
                )
            }
        }
        modeOptions.forEach { (modeIdx, label) ->
            val btn = TextView(context).apply {
                text = label
                setTextSp(13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    selectedTariffMode = modeIdx
                    refreshModeBtns()
                }
            }
            modeBtns.add(btn)
            modeRow.addView(btn, LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        refreshModeBtns()
        root.addView(modeRow, LayoutParams(MATCH, WRAP))

        var reevActive = LeapVehicleDataSource.getSelectedVariant(context).isReev
        val reevToggle = TextView(context).apply {
            setTextSp(13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            fun updateReevLook() {
                text = if (reevActive) {
                    "⛽ Modo REEV (Extensor de autonomía Gasolina + Batería): ACTIVO ✓"
                } else {
                    "🔋 Modo actual: 100 % Eléctrico (BEV) · Toca para activar modo REEV (Híbrido)"
                }
                setTextColor(if (reevActive) 0xFFFFB547.toInt() else QonectaTheme.text)
                background = pillDrawable(
                    if (reevActive) 0x26FFB547 else QonectaTheme.surfaceTop,
                    if (reevActive) 0xFFFFB547.toInt() else QonectaTheme.edge,
                )
            }
            updateReevLook()
            setOnClickListener {
                reevActive = !reevActive
                updateReevLook()
            }
        }
        root.addView(reevToggle, LayoutParams(MATCH, WRAP).apply { topMargin = dp(4); bottomMargin = dp(10) })

        val dialog = android.app.AlertDialog.Builder(context)
            .setView(root)
            .create()

        val btnRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        btnRow.addView(TextView(context).apply {
            text = "Cancelar"
            setTextSp(14.5f)
            setTextColor(QonectaTheme.dim)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = pillDrawable(QonectaTheme.surfaceTop, QonectaTheme.edge)
            setOnClickListener { dialog.dismiss() }
        }, LayoutParams(WRAP, WRAP).apply { rightMargin = dp(10) })

        btnRow.addView(TextView(context).apply {
            text = "💾 Guardar y sincronizar con Companion"
            setTextSp(14.5f)
            setTextColor(QonectaTheme.onAccent)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = pillDrawable(QonectaTheme.accent, QonectaTheme.accent)
            setOnClickListener {
                fun parsePrice(et: EditText, fallback: Double): Double =
                    et.text?.toString()?.replace(',', '.')?.trim()?.toDoubleOrNull()
                        ?.coerceIn(0.01, 9.99) ?: fallback

                val updated = LeapVehicleDataSource.EnergyPricingConfig(
                    homeElecEurKwh = parsePrice(homeInput, current.homeElecEurKwh),
                    dcFastElecEurKwh = parsePrice(dcInput, current.dcFastElecEurKwh),
                    gasolineEurLiter = parsePrice(gasInput, current.gasolineEurLiter),
                    dieselEurLiter = parsePrice(dieselInput, current.dieselEurLiter),
                    tariffMode = selectedTariffMode,
                    updatedAtMs = System.currentTimeMillis(),
                )
                LeapVehicleDataSource.saveEnergyPricing(context, updated)
                val curVariant = LeapVehicleDataSource.getSelectedVariant(context)
                if (reevActive && !curVariant.isReev) {
                    LeapVehicleDataSource.setSelectedVariant(context, LeapVehicleDataSource.C10Variant.REEV_DESIGN_215)
                } else if (!reevActive && curVariant.isReev) {
                    LeapVehicleDataSource.setSelectedVariant(context, LeapVehicleDataSource.C10Variant.PROMAX_DESIGN_MY26)
                }
                com.headqlink.unit.bridgehost.CompanionBridgeHost.pushImmediateSync()
                dialog.dismiss()
                renderActiveTab()
            }
        })
        root.addView(btnRow, LayoutParams(MATCH, WRAP))

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    // =========================================================================
    // 6. PESTAÑA ESTADO (Diseño panorámico 3 columnas Qonecta+ + Modelo 3D C10 + Selector hasta MY26)
    // =========================================================================

    private var car3dWebView: WebView? = null
    @Volatile
    private var car3dReady: Boolean = false
    @Volatile
    private var car3dFailed: Boolean = false

    @Suppress("SetJavaScriptEnabled")
    private fun obtainCar3dWebView(): WebView {
        val existing = car3dWebView
        if (existing != null) return existing
        val wv = WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                domStorageEnabled = false
                setSupportZoom(false)
            }
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun ready() {
                        car3dReady = true
                        uiHandler.post {
                            pushCar3dState(
                                LeapVehicleDataSource.snapshot(),
                                LeapVehicleDataSource.getSelectedPaintColor(context),
                            )
                        }
                    }

                    @JavascriptInterface
                    fun color(hex: String?) {
                        val picked = LeapVehicleDataSource.C10PaintColor.fromHex3d(hex)
                        LeapVehicleDataSource.setSelectedPaintColor(context, picked)
                        uiHandler.post { renderActiveTab() }
                    }

                    @JavascriptInterface
                    fun doorToggled(index: Int, isOpen: Boolean) {
                        LeapVehicleDataSource.setDoorOpen(index, isOpen)
                        uiHandler.post { renderActiveTab() }
                    }

                    @JavascriptInterface
                    fun bootToggled(isOpen: Boolean) {
                        LeapVehicleDataSource.setBootOpen(isOpen)
                        uiHandler.post { renderActiveTab() }
                    }

                    @JavascriptInterface
                    fun snapshot(@Suppress("UNUSED_PARAMETER") dataUrl: String?) {
                        // Opcional: captura PNG del modelo 3D
                    }

                    @JavascriptInterface
                    fun failed(@Suppress("UNUSED_PARAMETER") why: String?) {
                        car3dFailed = true
                        uiHandler.post { renderActiveTab() }
                    }
                },
                "HQL",
            )
            loadUrl("file:///android_asset/car3d.html")
        }
        car3dWebView = wv
        return wv
    }

    private fun pushCar3dState(
        snap: LeapVehicleDataSource.TelemetrySnapshot,
        paintColor: LeapVehicleDataSource.C10PaintColor,
    ) {
        val wv = car3dWebView ?: return
        if (!car3dReady) return
        val tyresArr = JSONArray()
        val posTags = listOf("Del. Izq", "Del. Der", "Tras. Izq", "Tras. Der")
        for (i in 0..3) {
            val bar = snap.tyreBars.getOrElse(i) { if (i == 2) 2.52 else 2.50 }
            val tempC = snap.tyreTempsC.getOrElse(i) { 25 }
            val warn = bar < 2.15 || bar > 2.95
            tyresArr.put(
                JSONObject()
                    .put("text", String.format(Locale.getDefault(), "%.2f", bar))
                    .put("unit", "bar")
                    .put("warn", warn)
                    .put("tag", "${posTags[i]} · $tempC °C"),
            )
        }
        val doorsArr = JSONArray()
        for (i in 0..3) {
            doorsArr.put(snap.doorsOpen.getOrElse(i) { false })
        }
        val payload = JSONObject()
            .put("tyres", tyresArr)
            .put("doors", doorsArr)
            .put("boot", snap.bootOpen)
            .put("color", paintColor.hex3d)
            .put("hint", "Gira con el dedo · Toca una puerta o maletero para abrir")
        wv.evaluateJavascript("if(window.update)update($payload);", null)
    }

    private fun buildStatusTab(snap: LeapVehicleDataSource.TelemetrySnapshot): View {
        val variant = LeapVehicleDataSource.getSelectedVariant(context)
        val paintColor = LeapVehicleDataSource.getSelectedPaintColor(context)

        val rootRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            weightSum = 3.06f
        }

        val soc = if (!snap.socPct.isNaN()) snap.socPct else 78.0
        val grossKwh = variant.batteryGrossKwh
        val usableKwh = variant.batteryUsableKwh
        val currentKwh = (soc / 100.0) * grossKwh
        val evRangeKm = if (!snap.evRangeKm.isNaN() && snap.evRangeKm > 0) {
            snap.evRangeKm
        } else {
            (soc / 100.0) * variant.wltpEvRangeKm
        }
        val isReevActive = variant.isReev || snap.isReev
        val fuelPct = if (!snap.fuelPct.isNaN() && snap.fuelPct > 0) snap.fuelPct else 72.0
        val fuelLiters = (fuelPct / 100.0) * 50.0
        val fuelRangeKm = if (!snap.fuelRangeKm.isNaN() && snap.fuelRangeKm > 0) snap.fuelRangeKm else (fuelPct / 100.0) * 829.0
        val totalCombinedKm = evRangeKm + if (isReevActive) fuelRangeKm else 0.0

        // ---------------------------------------------------------------------
        // COLUMNA 1: BATERÍA Y ENERGÍA (Anillo grande + kWh reales según versión + REEV + Térmica)
        // ---------------------------------------------------------------------
        val col1Card = cardLayout().apply {
            setPadding(dp(20), dp(16), dp(20), dp(16))

            val headerRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headerRow.addView(sectionLabel("BATERÍA Y AUTONOMÍA"), LayoutParams(0, WRAP, 1f))
            val archBadge = TextView(context).apply {
                text = if (isReevActive) {
                    String.format(Locale.getDefault(), "%.1f kWh + 50 L REEV", grossKwh)
                } else {
                    String.format(Locale.getDefault(), "%.1f kWh · %dV LFP", grossKwh, variant.architectureVolts)
                }
                setTextSp(15f)
                setTextColor(QonectaTheme.accent)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(12), dp(5), dp(12), dp(5))
                background = pillDrawable(QonectaTheme.withAlpha(QonectaTheme.accent, 0x1E), QonectaTheme.accent)
            }
            headerRow.addView(archBadge, LayoutParams(WRAP, WRAP))
            addView(headerRow, LayoutParams(MATCH, WRAP))

            // Anillo grande de batería centrado
            val ringWrap = FrameLayout(context)
            val ringView = BatteryRingView(context).apply {
                scale = when {
                    isSplitScreen -> 0.58f
                    isReevActive -> 0.62f
                    else -> 1.02f
                }
                setData(
                    soc = soc,
                    rangeKm = evRangeKm,
                    rangeLabel = if (isReevActive || isSplitScreen) "Autonomía EV" else "Autonomía eléctrica",
                    ageText = snap.chargingLabel(),
                    ageColor = if (snap.chargingStatus in 1..2) QonectaTheme.green else QonectaTheme.accent,
                )
            }
            statusRingViewRef = ringView
            val ringSize = when {
                isSplitScreen -> dp(136)
                isReevActive -> dp(168)
                else -> dp(266)
            }
            ringWrap.addView(
                ringView,
                FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER),
            )

            // Estado de carga / potencia en tiempo real
            val pKw = if (!snap.powerKw.isNaN()) snap.powerKw else 0.8
            val powerStatusText = when {
                snap.chargingStatus == 2 -> String.format(Locale.getDefault(), "⚡ Carga rápida CC activa · %.1f kW (Pico %d kW)", kotlin.math.abs(pKw), variant.peakDcChargeKw)
                snap.chargingStatus == 1 -> String.format(Locale.getDefault(), "⚡ Carga alterna CA activa · %.1f kW (Máx %.1f kW)", kotlin.math.abs(pKw), variant.acChargeKw)
                snap.effectiveSpeedKmh > 1.5 && pKw < -0.2 -> String.format(Locale.getDefault(), "⚡ Regenerando · %.1f kW", pKw)
                snap.effectiveSpeedKmh > 1.5 -> String.format(Locale.getDefault(), "⚡ Potencia %+.1f kW", pKw)
                else -> String.format(Locale.getDefault(), "⚡ %s · %+.1f kW", snap.chargingLabel(), pKw)
            }
            val powerStatusTv = TextView(context).apply {
                text = powerStatusText
                setTextSp(if (isReevActive || isSplitScreen) 14.5f else 17f)
                setTextColor(if (pKw < 0 || snap.chargingStatus in 1..2) QonectaTheme.green else QonectaTheme.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            statusPowerStatusTvRef = powerStatusTv

            // Bloque EN LA BATERÍA (con capacidad exacta del C10 elegido: 81,9 / 69,9 / 28,4 kWh)
            val energyBox = LinearLayout(context).apply {
                orientation = VERTICAL
                val vPad = if (isReevActive || isSplitScreen) dp(7) else dp(10)
                val hPad = if (isSplitScreen) dp(10) else dp(14)
                setPadding(hPad, vPad, hPad, vPad)
                background = GradientDrawable().apply {
                    setColor(QonectaTheme.surfaceTop)
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), QonectaTheme.outline)
                }
                if (isSplitScreen) {
                    addView(TextView(context).apply {
                        text = String.format(Locale.getDefault(), "EN BATERÍA (%.1f kWh útiles)", usableKwh)
                        setTextSp(12.5f)
                        setTextColor(QonectaTheme.accent)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }, LayoutParams(MATCH, WRAP))
                } else {
                    val topLine = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    topLine.addView(TextView(context).apply {
                        text = "EN LA BATERÍA"
                        setTextSp(13.5f)
                        setTextColor(QonectaTheme.dim)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    }, LayoutParams(0, WRAP, 1f))
                    topLine.addView(TextView(context).apply {
                        text = String.format(Locale.getDefault(), "%.1f kWh útiles", usableKwh)
                        setTextSp(13.5f)
                        setTextColor(QonectaTheme.accent)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    }, LayoutParams(WRAP, WRAP))
                    addView(topLine, LayoutParams(MATCH, WRAP))
                }

                addView(TextView(context).apply {
                    text = String.format(Locale.getDefault(), "%.1f / %.1f kWh", currentKwh, grossKwh)
                    setTextSp(if (isSplitScreen) 20f else if (isReevActive) 22f else 28f)
                    setTextColor(QonectaTheme.text)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    statusEnergyCurrentTvRef = this
                }, LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })

                val batBar = BatteryCapacityBarCanvasView(context, (soc / 100.0).toFloat(), QonectaTheme.accent)
                statusBatBarViewRef = batBar
                addView(
                    batBar,
                    LayoutParams(MATCH, if (isReevActive || isSplitScreen) dp(8) else dp(10)).apply { topMargin = dp(5) },
                )

                addView(TextView(context).apply {
                    text = if (isSplitScreen) {
                        String.format(Locale.getDefault(), "CC %d kW (%d min) · CA %.1f kW", variant.peakDcChargeKw, variant.charge30To80Min, variant.acChargeKw)
                    } else {
                        String.format(
                            Locale.getDefault(),
                            "Carga CC %d kW (30–80%% en %d min) · CA %.1f kW",
                            variant.peakDcChargeKw,
                            variant.charge30To80Min,
                            variant.acChargeKw,
                        )
                    }
                    setTextSp(12.5f)
                    setTextColor(QonectaTheme.dim)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
            }

            if (isSplitScreen) {
                val splitBatRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                splitBatRow.addView(ringWrap, LayoutParams(dp(136), MATCH).apply { rightMargin = dp(8) })
                val rightStack = LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                rightStack.addView(powerStatusTv, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(5) })
                rightStack.addView(energyBox, LayoutParams(MATCH, WRAP))
                splitBatRow.addView(rightStack, LayoutParams(0, MATCH, 1f))
                addView(splitBatRow, LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(2) })
            } else {
                addView(ringWrap, LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(2) })
                addView(View(context).apply {
                    setBackgroundColor(QonectaTheme.outline)
                }, LayoutParams(MATCH, dp(1)).apply { topMargin = dp(4); bottomMargin = dp(8) })
                addView(powerStatusTv, LayoutParams(MATCH, WRAP))
                addView(energyBox, LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
            }

            // Si la versión elegida es REEV (Autonomía Extendida), mostramos también el depósito de gasolina de 50 L
            if (isReevActive) {
                val reevBox = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                    background = GradientDrawable().apply {
                        setColor(QonectaTheme.surfaceTop)
                        cornerRadius = dp(14).toFloat()
                        setStroke(dp(1), QonectaTheme.amber)
                    }
                    val rTop = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    rTop.addView(TextView(context).apply {
                        text = "⛽ DEPÓSITO GASOLINA REEV (50 L)"
                        setTextSp(13f)
                        setTextColor(QonectaTheme.amber)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    }, LayoutParams(0, WRAP, 1f))
                    rTop.addView(TextView(context).apply {
                        text = String.format(Locale.getDefault(), "Total: %.0f km", totalCombinedKm)
                        setTextSp(13.5f)
                        setTextColor(QonectaTheme.green)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    }, LayoutParams(WRAP, WRAP))
                    addView(rTop, LayoutParams(MATCH, WRAP))

                    addView(TextView(context).apply {
                        text = String.format(
                            Locale.getDefault(),
                            "%.0f%% · %.1f / 50 L (+%.0f km gasolina)",
                            fuelPct,
                            fuelLiters,
                            fuelRangeKm,
                        )
                        setTextSp(17.5f)
                        setTextColor(QonectaTheme.text)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        statusReevTextTvRef = this
                    }, LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })

                    val reevBar = BatteryCapacityBarCanvasView(context, (fuelPct / 100.0).toFloat(), QonectaTheme.amber)
                    statusReevBarViewRef = reevBar
                    addView(
                        reevBar,
                        LayoutParams(MATCH, dp(7)).apply { topMargin = dp(4) },
                    )
                }
                addView(reevBox, LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
            }

            // Fila inferior: Temperatura de batería + Auxiliar 12V
            val batBottomRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 2f
            }
            val batTempC = if (!snap.tempExteriorC.isNaN()) (snap.tempExteriorC + 15.0).coerceIn(19.0, 32.0) else 23.0
            batBottomRow.addView(
                instrumentMiniChip(
                    "TEMP. BATERÍA LFP",
                    String.format(Locale.getDefault(), "%.1f °C · Óptima", batTempC),
                    QonectaTheme.green,
                ),
                LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) },
            )
            batBottomRow.addView(
                instrumentMiniChip(
                    "BATERÍA AUX. 12V",
                    "13,8 V · Bomba calor",
                    QonectaTheme.accent,
                ),
                LayoutParams(0, WRAP, 1f),
            )
            addView(batBottomRow, LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }

        // ---------------------------------------------------------------------
        // COLUMNA 2: NEUMÁTICOS, PUERTAS Y CARROCERÍA (Modelo 3D interactivo C10 + 4 presiones bar + apertura de puertas)
        // ---------------------------------------------------------------------
        val col2Card = cardLayout().apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))

            val headerRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headerRow.addView(sectionLabel("MODELO 3D · NEUMÁTICOS Y PUERTAS"), LayoutParams(0, WRAP, 1f))

            val colorBtn = TextView(context).apply {
                text = "🎨 ${paintColor.label} ▾"
                setTextSp(14f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(12), dp(5), dp(12), dp(5))
                background = pillDrawable(QonectaTheme.surfaceTop, QonectaTheme.accent)
                setOnClickListener {
                    val nextColor = LeapVehicleDataSource.cyclePaintColor(context)
                    pushCar3dState(LeapVehicleDataSource.snapshot(), nextColor)
                    renderActiveTab()
                }
            }
            headerRow.addView(colorBtn, LayoutParams(WRAP, WRAP))
            addView(headerRow, LayoutParams(MATCH, WRAP))

            if (!car3dFailed) {
                val wv = obtainCar3dWebView()
                (wv.parent as? ViewGroup)?.removeView(wv)
                addView(wv, LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(4) })
                pushCar3dState(snap, paintColor)
            } else {
                val tyresCanvas = C10TyresAndDoorsCanvasView(context, snap, paintColor).apply {
                    setOnClickListener {
                        LeapVehicleDataSource.cyclePaintColor(context)
                        renderActiveTab()
                    }
                }
                addView(tyresCanvas, LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(6) })
            }

            // Barra interactiva inferior de puertas, maletero y cierre (refleja y anima el modelo 3D en tiempo real)
            val doorsCard = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = roundedDrawable(QonectaTheme.surfaceTop, dp(14), dp(1), QonectaTheme.outline)

                val anyDoorOpen = snap.doorsOpen.any { it } || snap.bootOpen
                val lockTopRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                lockTopRow.addView(TextView(context).apply {
                    text = when {
                        anyDoorOpen -> "🔓 Puerta / Maletero abierto"
                        snap.locked -> "🔒 Cerrado · Seguridad activa"
                        else -> "🔓 Desbloqueado · Puertas cerradas"
                    }
                    setTextSp(14.5f)
                    setTextColor(if (anyDoorOpen) QonectaTheme.amber else QonectaTheme.green)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                }, LayoutParams(0, WRAP, 1f))

                val lockToggleBtn = TextView(context).apply {
                    text = if (snap.locked && !anyDoorOpen) "Cerrar / Abrir" else "Cerrar todo"
                    setTextSp(12.5f)
                    setTextColor(QonectaTheme.accent)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(dp(10), dp(3), dp(10), dp(3))
                    background = pillDrawable(QonectaTheme.withAlpha(QonectaTheme.accent, 0x1E), QonectaTheme.accent)
                    setOnClickListener {
                        if (anyDoorOpen) {
                            LeapVehicleDataSource.setVehicleLocked(true)
                        } else {
                            LeapVehicleDataSource.toggleVehicleLocked()
                        }
                        pushCar3dState(LeapVehicleDataSource.snapshot(), LeapVehicleDataSource.getSelectedPaintColor(context))
                        renderActiveTab()
                    }
                }
                lockTopRow.addView(lockToggleBtn, LayoutParams(WRAP, WRAP))
                addView(lockTopRow, LayoutParams(MATCH, WRAP))

                val doorPillsRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    weightSum = 5f
                }
                val doorNames = listOf("Del. Izq", "Del. Der", "Tras. Izq", "Tras. Der")
                doorNames.forEachIndexed { idx, label ->
                    val open = snap.doorsOpen.getOrElse(idx) { false }
                    val pill = TextView(context).apply {
                        text = if (open) "🔓 $label" else label
                        setTextSp(11.5f)
                        setTextColor(if (open) QonectaTheme.onAccent else QonectaTheme.text)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        gravity = Gravity.CENTER
                        setPadding(dp(4), dp(5), dp(4), dp(5))
                        background = pillDrawable(
                            if (open) QonectaTheme.amber else QonectaTheme.bg,
                            if (open) QonectaTheme.amber else QonectaTheme.outline,
                        )
                        setOnClickListener {
                            LeapVehicleDataSource.toggleDoorOpen(idx)
                            pushCar3dState(LeapVehicleDataSource.snapshot(), LeapVehicleDataSource.getSelectedPaintColor(context))
                            renderActiveTab()
                        }
                    }
                    doorPillsRow.addView(pill, LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(4) })
                }
                val bootOpen = snap.bootOpen
                val bootPill = TextView(context).apply {
                    text = if (bootOpen) "🔓 Maletero" else "Maletero"
                    setTextSp(11.5f)
                    setTextColor(if (bootOpen) QonectaTheme.onAccent else QonectaTheme.text)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(5), dp(4), dp(5))
                    background = pillDrawable(
                        if (bootOpen) QonectaTheme.amber else QonectaTheme.bg,
                        if (bootOpen) QonectaTheme.amber else QonectaTheme.outline,
                    )
                    setOnClickListener {
                        LeapVehicleDataSource.toggleBootOpen()
                        pushCar3dState(LeapVehicleDataSource.snapshot(), LeapVehicleDataSource.getSelectedPaintColor(context))
                        renderActiveTab()
                    }
                }
                doorPillsRow.addView(bootPill, LayoutParams(0, WRAP, 1f))
                addView(doorPillsRow, LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
            }
            addView(doorsCard, LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        }

        // ---------------------------------------------------------------------
        // COLUMNA 3: DERECHA (Arriba: Selector de Versión C10 hasta MY26 · Abajo: Coche y Climatización)
        // ---------------------------------------------------------------------
        val col3 = LinearLayout(context).apply {
            orientation = VERTICAL
            weightSum = 2f
        }

        // Tarjeta Superior: VERSIÓN DE TU LEAPMOTOR C10 (Por defecto: C10 ProMax Design 299 CV MY26 · 81,9 kWh)
        val modelCard = cardLayout().apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            isClickable = true
            setOnClickListener { showC10VariantSelectorDialog() }

            val headerRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headerRow.addView(sectionLabel("VERSIÓN DE TU C10"), LayoutParams(0, WRAP, 1f))
            val badgeView = TextView(context).apply {
                text = variant.badge
                setTextSp(13.5f)
                setTextColor(QonectaTheme.onAccent)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = QonectaTheme.accentGradientPill(dp(999).toFloat())
            }
            headerRow.addView(badgeView, LayoutParams(WRAP, WRAP))
            addView(headerRow, LayoutParams(MATCH, WRAP))

            addView(TextView(context).apply {
                text = variant.title
                setTextSp(20.5f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })

            addView(TextView(context).apply {
                text = "${variant.powertrainLabel} · ${variant.torqueNm} Nm"
                setTextSp(14f)
                setTextColor(QonectaTheme.accent)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })

            // Cuadrícula 2×2 de ficha técnica de la versión seleccionada
            val specsRow1 = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 2f
            }
            specsRow1.addView(
                instrumentMiniChip(
                    "POTENCIA MOTOR",
                    "${variant.powerCv} CV (${variant.powerKw} kW)",
                    QonectaTheme.text,
                ),
                LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) },
            )
            specsRow1.addView(
                instrumentMiniChip(
                    "CAPACIDAD BATERÍA",
                    String.format(Locale.getDefault(), "%.1f kWh (%.1f útil)", variant.batteryGrossKwh, variant.batteryUsableKwh),
                    QonectaTheme.accent,
                ),
                LayoutParams(0, WRAP, 1f),
            )
            addView(specsRow1, LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

            val specsRow2 = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 2f
            }
            val rangeSpecStr = if (variant.isReev) {
                "${variant.wltpEvRangeKm} km EV / ${variant.wltpTotalRangeKm} km"
            } else {
                "${variant.wltpEvRangeKm} km WLTP (${String.format(Locale.getDefault(), "%.1f", variant.zeroToHundredSec)} s)"
            }
            specsRow2.addView(
                instrumentMiniChip(
                    "AUTONOMÍA WLTP",
                    rangeSpecStr,
                    QonectaTheme.green,
                ),
                LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) },
            )
            specsRow2.addView(
                instrumentMiniChip(
                    "CARGA RÁPIDA CC",
                    "${variant.architectureVolts}V · ${variant.peakDcChargeKw} kW (${variant.charge30To80Min} min)",
                    QonectaTheme.text,
                ),
                LayoutParams(0, WRAP, 1f),
            )
            addView(specsRow2, LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

            addView(View(context), LayoutParams(MATCH, 0, 1f))

            val bottomActionsRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 2f
            }
            val selectVariantBtn = actionButton(if (isSplitScreen) "🚗 Versión C10 ▾" else "🚗 Cambiar versión C10 ▾", primary = true) {
                showC10VariantSelectorDialog()
            }
            val usbInstallBtn = actionButton(if (isSplitScreen) "📦 Instalar USB" else "📦 Actualizar / Archivos USB", primary = false) {
                OtaSelfUpdater.showDialog(context) {
                    com.headqlink.unit.files.CarFiles.open(context)
                }
            }
            bottomActionsRow.addView(selectVariantBtn, LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
            bottomActionsRow.addView(usbInstallBtn, LayoutParams(0, dp(48), 1f))
            addView(bottomActionsRow, LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        }

        // Tarjeta Inferior: COCHE Y CLIMATIZACIÓN (estilo Qonecta+ sin botones técnicos confusos)
        val carDataCard = cardLayout().apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))

            val headerRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headerRow.addView(sectionLabel("COCHE Y CLIMATIZACIÓN"), LayoutParams(0, WRAP, 1f))
            val livePill = TextView(context).apply {
                val isLive = snap.connected && !snap.isDemo
                text = if (isLive) "● BUS CAN ACTIVO" else "● TELEMETRÍA ACTIVA"
                setTextSp(13.5f)
                setTextColor(QonectaTheme.green)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            headerRow.addView(livePill, LayoutParams(WRAP, WRAP))
            addView(headerRow, LayoutParams(MATCH, WRAP))

            val tL = if (!snap.tempLeftC.isNaN()) snap.tempLeftC else 25.0
            val tR = if (!snap.tempRightC.isNaN()) snap.tempRightC else 25.0
            val tOut = if (!snap.tempExteriorC.isNaN()) snap.tempExteriorC else 8.0
            val moving = snap.effectiveSpeedKmh > 1.5

            val rowA = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 2f
            }
            rowA.addView(
                instrumentMiniChip(
                    "ESTADO Y MARCHA",
                    if (moving) "Marcha ${snap.gearLabel()} · ${snap.effectiveSpeedKmh.roundToInt()} km/h" else "Estacionado · Marcha ${snap.gearLabel()}",
                    if (moving) QonectaTheme.accent else QonectaTheme.text,
                ) { statusGearChipTvRef = it },
                LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) },
            )
            rowA.addView(
                instrumentMiniChip(
                    "MODOS ACTIVOS",
                    "${snap.driveModeLabel()} · Regen ${snap.regenModeLabel()}",
                    QonectaTheme.accent,
                ) { statusModesChipTvRef = it },
                LayoutParams(0, WRAP, 1f),
            )
            addView(rowA, LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

            val rowB = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 2f
            }
            rowB.addView(
                instrumentMiniChip(
                    "CLIMA BIZONA (IZQ / DER)",
                    String.format(Locale.getDefault(), "%.1f °C / %.1f °C", tL, tR),
                    QonectaTheme.text,
                ) { statusClimaChipTvRef = it },
                LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) },
            )
            rowB.addView(
                instrumentMiniChip(
                    "EXTERIOR Y DIRECCIÓN",
                    String.format(Locale.getDefault(), "%.1f °C · Dir. %s", tOut, snap.steeringModeLabel()),
                    QonectaTheme.text,
                ) { statusExtSteerChipTvRef = it },
                LayoutParams(0, WRAP, 1f),
            )
            addView(rowB, LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

            addView(View(context), LayoutParams(MATCH, 0, 1f))

            addView(TextView(context).apply {
                text = "✓ ${variant.trimEquipment}"
                setTextSp(13f)
                setTextColor(QonectaTheme.dim)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        }

        if (isSplitScreen) {
            val grid = LinearLayout(context).apply {
                orientation = VERTICAL
                weightSum = 2f
            }
            val rowTop = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 2f
            }
            rowTop.addView(col1Card, LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(10) })
            rowTop.addView(col2Card, LayoutParams(0, MATCH, 1f))
            val rowBot = LinearLayout(context).apply {
                orientation = HORIZONTAL
                weightSum = 2f
            }
            rowBot.addView(modelCard, LayoutParams(0, MATCH, 1.05f).apply { rightMargin = dp(10) })
            rowBot.addView(carDataCard, LayoutParams(0, MATCH, 0.95f))
            grid.addView(rowTop, LayoutParams(MATCH, 0, 1.04f).apply { bottomMargin = dp(10) })
            grid.addView(rowBot, LayoutParams(MATCH, 0, 0.96f))
            return grid
        }

        col3.addView(modelCard, LayoutParams(MATCH, 0, 1.18f).apply { bottomMargin = dp(12) })
        col3.addView(carDataCard, LayoutParams(MATCH, 0, 0.82f))

        rootRow.addView(col1Card, LayoutParams(0, MATCH, 1.00f).apply { rightMargin = dp(12) })
        rootRow.addView(col2Card, LayoutParams(0, MATCH, 1.04f).apply { rightMargin = dp(12) })
        rootRow.addView(col3, LayoutParams(0, MATCH, 1.02f))

        return rootRow
    }

    private fun instrumentMiniChip(
        title: String,
        value: String,
        valueColor: Int,
        onValueView: ((TextView) -> Unit)? = null,
    ): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply {
            setColor(QonectaTheme.surfaceTop)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), QonectaTheme.outline)
        }
        addView(TextView(context).apply {
            text = title
            setTextSp(11f)
            setTextColor(QonectaTheme.dim)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val valTv = TextView(context).apply {
            text = value
            setTextSp(14.5f)
            setTextColor(valueColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        onValueView?.invoke(valTv)
        addView(valTv)
    }

    /**
     * Diálogo modal de selección del modelo y versión del Leapmotor C10 (gama hasta MY26, excluyendo 2027):
     * - C10 ProMax Design 299 CV MY26 · 81,9 kWh (Por defecto)
     * - C10 ProMax Style/Life 299 CV MY26 · 81,9 kWh
     * - C10 ProMax AWD Design 598 CV MY26 · 81,9 kWh
     * - C10 BEV Design 218 CV (MY24/25) · 69,9 kWh
     * - C10 BEV Style/Life 218 CV (MY24/25) · 69,9 kWh
     * - C10 REEV Design 215 CV (Autonomía Extendida) · 28,4 kWh + 50 L
     * - C10 REEV Style/Life 215 CV (Autonomía Extendida) · 28,4 kWh + 50 L
     */
    private fun showC10VariantSelectorDialog() {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val current = LeapVehicleDataSource.getSelectedVariant(context)

        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(26), dp(22), dp(26), dp(22))
            background = GradientDrawable().apply {
                setColor(QonectaTheme.bg)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(2), QonectaTheme.accent)
            }
        }

        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        titleCol.addView(TextView(context).apply {
            text = "SELECCIONA TU VERSIÓN DE LEAPMOTOR C10 (GAMA HASTA MY26)"
            setTextSp(22f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        titleCol.addView(TextView(context).apply {
            text = "Ajusta automáticamente la capacidad de batería (81,9 / 69,9 / 28,4 kWh + 50 L REEV), potencia (299 / 598 / 218 / 215 CV), arquitectura (800V / 400V) y autonomía WLTP en toda la app."
            setTextSp(15f)
            setTextColor(QonectaTheme.dim)
        }, LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        topRow.addView(titleCol, LayoutParams(0, WRAP, 1f))

        val closeBtn = TextView(context).apply {
            text = "✕ Cerrar"
            setTextSp(17f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = pillDrawable(QonectaTheme.surfaceTop, QonectaTheme.outline)
            setOnClickListener { dialog.dismiss() }
        }
        topRow.addView(closeBtn, LayoutParams(WRAP, WRAP).apply { leftMargin = dp(14) })
        root.addView(topRow, LayoutParams(MATCH, WRAP))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
        }
        val listCol = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(14), 0, dp(6))
        }

        LeapVehicleDataSource.C10Variant.values().forEach { v ->
            val isSelected = v == current
            val itemCard = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(14))
                background = GradientDrawable().apply {
                    setColor(if (isSelected) QonectaTheme.withAlpha(QonectaTheme.accent, 0x1A) else QonectaTheme.surface)
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(if (isSelected) 2 else 1), if (isSelected) QonectaTheme.accent else QonectaTheme.outline)
                }
                isClickable = true
                setOnClickListener {
                    LeapVehicleDataSource.setSelectedVariant(context, v)
                    currentRoute?.let { recalcRouteSoc(it.departureSocPct) }
                    refreshLiveViews()
                    renderActiveTab()
                    dialog.dismiss()
                    Toast.makeText(context, "Versión seleccionada: ${v.title}", Toast.LENGTH_SHORT).show()
                }
            }

            val infoCol = LinearLayout(context).apply {
                orientation = VERTICAL
            }
            val titleRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(context).apply {
                text = (if (isSelected) "✓  " else "") + v.title
                setTextSp(20f)
                setTextColor(if (isSelected) QonectaTheme.accent else QonectaTheme.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }, LayoutParams(WRAP, WRAP))

            titleRow.addView(TextView(context).apply {
                text = v.badge
                setTextSp(13f)
                setTextColor(if (v.isReev) 0xFF14181F.toInt() else QonectaTheme.onAccent)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = GradientDrawable().apply {
                    setColor(if (v.isReev) QonectaTheme.amber else QonectaTheme.accent)
                    cornerRadius = dp(999).toFloat()
                }
            }, LayoutParams(WRAP, WRAP).apply { leftMargin = dp(12) })
            infoCol.addView(titleRow, LayoutParams(MATCH, WRAP))

            val specsLine = if (v.isReev) {
                String.format(
                    Locale.getDefault(),
                    "%s · %d CV (%d kW) · Batería %.1f kWh + Depósito 50 L · %d km EV / %d km WLTP combinada · CC %d kW",
                    v.powertrainLabel,
                    v.powerCv,
                    v.powerKw,
                    v.batteryGrossKwh,
                    v.wltpEvRangeKm,
                    v.wltpTotalRangeKm,
                    v.peakDcChargeKw,
                )
            } else {
                String.format(
                    Locale.getDefault(),
                    "%s · %d CV (%d kW / %d Nm) · Batería %.1f kWh (%.1f útil) · %dV · %d km WLTP · 0–100 en %.1f s · CC %d kW",
                    v.powertrainLabel,
                    v.powerCv,
                    v.powerKw,
                    v.torqueNm,
                    v.batteryGrossKwh,
                    v.batteryUsableKwh,
                    v.architectureVolts,
                    v.wltpEvRangeKm,
                    v.zeroToHundredSec,
                    v.peakDcChargeKw,
                )
            }
            infoCol.addView(TextView(context).apply {
                text = specsLine
                setTextSp(15f)
                setTextColor(QonectaTheme.text)
            }, LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })

            infoCol.addView(TextView(context).apply {
                text = v.trimEquipment
                setTextSp(14f)
                setTextColor(QonectaTheme.dim)
            }, LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })

            itemCard.addView(infoCol, LayoutParams(0, WRAP, 1f))

            val chooseBadge = TextView(context).apply {
                text = if (isSelected) "ACTIVO" else "Elegir"
                setTextSp(15f)
                setTextColor(if (isSelected) QonectaTheme.onAccent else QonectaTheme.accent)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = if (isSelected) {
                    QonectaTheme.accentGradientPill(dp(999).toFloat())
                } else {
                    pillDrawable(QonectaTheme.surfaceTop, QonectaTheme.accent)
                }
            }
            itemCard.addView(chooseBadge, LayoutParams(WRAP, WRAP).apply { leftMargin = dp(12) })

            listCol.addView(itemCard, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
        }

        scroll.addView(listCol)
        root.addView(scroll, LayoutParams(MATCH, 0, 1f))

        dialog.setContentView(root)
        dialog.window?.let { win ->
            val dm = context.resources.displayMetrics
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setLayout(
                (dm.widthPixels * 0.86f).roundToInt(),
                (dm.heightPixels * 0.86f).roundToInt(),
            )
        }
        dialog.show()
    }

    private class BatteryCapacityBarCanvasView(
        context: Context,
        private var fraction: Float,
        private val barColor: Int,
    ) : View(context) {
        fun updateFraction(newFraction: Float) {
            if (kotlin.math.abs(fraction - newFraction) > 0.005f) {
                fraction = newFraction
                invalidate()
            }
        }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = QonectaTheme.bg
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = barColor
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val r = h / 2f
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, r, r, trackPaint)
            val fw = (w * fraction.coerceIn(0.03f, 1f))
            rect.set(0f, 0f, fw, h)
            canvas.drawRoundRect(rect, r, r, fillPaint)
        }
    }

    /**
     * Vista en planta del Leapmotor C10 estilo CarStatusTab.paintTyresTop de Qonecta+:
     * Dibuja el SUV visto desde arriba con el color de pintura oficial elegido, las 4 ruedas con marco
     * de estado TPMS (verde/ámbar), presión en bar y temperatura de cada neumático a los lados, y debajo
     * el estado del cierre centralizado, puertas, ventanillas y maletero.
     */
    private class C10TyresAndDoorsCanvasView(
        context: Context,
        private val snap: LeapVehicleDataSource.TelemetrySnapshot,
        private val paintColor: LeapVehicleDataSource.C10PaintColor,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val carRect = RectF()
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val bottomInfoH = 128f
            val availCarH = (h - bottomInfoH - 12f).coerceAtLeast(200f)
            val carH = min(availCarH, 425f)
            val carW = carH * 0.46f
            val cx = w / 2f
            val topY = 8f + (availCarH - carH) * 0.45f
            carRect.set(cx - carW / 2f, topY, cx + carW / 2f, topY + carH)

            // 1. Ruedas y marcos de estado TPMS + etiquetas de presión (bar) y temperatura (°C)
            val tw = 0.11f * carW
            val th = 0.165f * carH
            val wheelYs = floatArrayOf(carRect.top + 0.15f * carH, carRect.top + 0.70f * carH)
            val baseTemp = if (!snap.tempExteriorC.isNaN()) (snap.tempExteriorC + 16.0).coerceIn(18.0, 38.0) else 24.0
            val bars = doubleArrayOf(2.50, 2.50, 2.52, 2.50)
            val temps = intArrayOf(baseTemp.roundToInt(), baseTemp.roundToInt(), (baseTemp + 1).roundToInt(), (baseTemp + 1).roundToInt())
            val posLabels = arrayOf("DEL. IZQ.", "DEL. DER.", "TRAS. IZQ.", "TRAS. DER.")

            for (i in 0 until 4) {
                val isLeft = (i % 2 == 0)
                val wy = wheelYs[if (i < 2) 0 else 1]
                val wx = if (isLeft) carRect.left - tw * 0.22f else carRect.right - tw * 0.78f

                // Neumático negro
                paint.style = Paint.Style.FILL
                paint.color = 0xFF17191C.toInt()
                rect.set(wx, wy, wx + tw, wy + th)
                canvas.drawRoundRect(rect, tw / 3f, tw / 3f, paint)

                // Marco verde de estado de rueda (estilo Qonecta+)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3.5f
                paint.color = QonectaTheme.green
                rect.set(wx - 5f, wy - 5f, wx + tw + 5f, wy + th + 5f)
                canvas.drawRoundRect(rect, 9f, 9f, paint)

                // Etiqueta de posición, presión en grande (bar) y temperatura (°C) al lado de cada rueda
                paint.style = Paint.Style.FILL
                val textX = if (isLeft) carRect.left - 24f else carRect.right + 24f
                val align = if (isLeft) Paint.Align.RIGHT else Paint.Align.LEFT
                paint.textAlign = align

                // Posición ("DEL. IZQ.")
                paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                paint.textSize = 15f
                paint.color = QonectaTheme.dim
                canvas.drawText(posLabels[i], textX, wy + th * 0.16f, paint)

                // Presión ("2,50 bar")
                val barStr = String.format(Locale.getDefault(), "%.2f bar", bars[i])
                paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                paint.textSize = 29f
                paint.color = QonectaTheme.text
                canvas.drawText(barStr, textX, wy + th * 0.62f, paint)

                // Temperatura y estado ("24 °C · OK")
                paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                paint.textSize = 17f
                paint.color = QonectaTheme.green
                canvas.drawText("${temps[i]} °C · OK", textX, wy + th * 0.98f, paint)
            }

            // 2. Carrocería vista desde arriba del Leapmotor C10 (con el color oficial elegido)
            paint.style = Paint.Style.FILL
            paint.color = paintColor.bodyColor
            rect.set(carRect.left + 0.04f * carW, carRect.top, carRect.right - 0.04f * carW, carRect.bottom)
            canvas.drawRoundRect(rect, 0.30f * carW, 0.14f * carH, paint)

            // Contorno y nervaduras del capó
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = paintColor.shadeColor
            canvas.drawRoundRect(rect, 0.30f * carW, 0.14f * carH, paint)
            canvas.drawLine(cx - 0.22f * carW, carRect.top + 0.06f * carH, cx - 0.16f * carW, carRect.top + 0.22f * carH, paint)
            canvas.drawLine(cx + 0.22f * carW, carRect.top + 0.06f * carH, cx + 0.16f * carW, carRect.top + 0.22f * carH, paint)

            // Techo panorámico negro con parabrisas y luneta trasera
            paint.style = Paint.Style.FILL
            paint.color = 0xFF22272E.toInt()
            rect.set(carRect.left + 0.15f * carW, carRect.top + 0.23f * carH, carRect.right - 0.15f * carW, carRect.top + 0.86f * carH)
            canvas.drawRoundRect(rect, 0.16f * carW, 0.08f * carH, paint)

            // Parabrisas y luneta
            paint.color = 0xFF334860.toInt()
            rect.set(carRect.left + 0.18f * carW, carRect.top + 0.24f * carH, carRect.right - 0.18f * carW, carRect.top + 0.39f * carH)
            canvas.drawRoundRect(rect, 0.12f * carW, 0.05f * carH, paint)
            rect.set(carRect.left + 0.20f * carW, carRect.top + 0.75f * carH, carRect.right - 0.20f * carW, carRect.top + 0.85f * carH)
            canvas.drawRoundRect(rect, 0.10f * carW, 0.04f * carH, paint)

            // Cristal techo solar panorámico
            paint.color = 0xFF192028.toInt()
            rect.set(carRect.left + 0.21f * carW, carRect.top + 0.42f * carH, carRect.right - 0.21f * carW, carRect.top + 0.71f * carH)
            canvas.drawRoundRect(rect, 0.06f * carW, 0.04f * carH, paint)

            // Retrovisores laterales
            paint.color = paintColor.bodyColor
            rect.set(carRect.left - 0.05f * carW, carRect.top + 0.30f * carH, carRect.left + 0.08f * carW, carRect.top + 0.345f * carH)
            canvas.drawRoundRect(rect, 0.02f * carW, 0.02f * carH, paint)
            rect.set(carRect.right - 0.08f * carW, carRect.top + 0.30f * carH, carRect.right + 0.05f * carW, carRect.top + 0.345f * carH)
            canvas.drawRoundRect(rect, 0.02f * carW, 0.02f * carH, paint)

            // Tira continua LED delantera (blanca/cian) y trasera (roja) característica del Leapmotor C10
            paint.color = 0xFFF4F8FF.toInt()
            rect.set(carRect.left + 0.13f * carW, carRect.top + 0.015f * carH, carRect.right - 0.13f * carW, carRect.top + 0.036f * carH)
            canvas.drawRoundRect(rect, 4f, 4f, paint)
            paint.color = 0xFFFF5A5F.toInt()
            rect.set(carRect.left + 0.13f * carW, carRect.top + 0.962f * carH, carRect.right - 0.13f * carW, carRect.top + 0.984f * carH)
            canvas.drawRoundRect(rect, 4f, 4f, paint)

            // 3. Bloque inferior: Estado de cierre, puertas, ventanillas, maletero y TPMS
            val boxTop = h - bottomInfoH
            paint.style = Paint.Style.FILL
            paint.color = QonectaTheme.surfaceTop
            rect.set(4f, boxTop, w - 4f, h - 4f)
            canvas.drawRoundRect(rect, 18f, 18f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = QonectaTheme.outline
            canvas.drawRoundRect(rect, 18f, 18f, paint)

            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            paint.textSize = 19f
            paint.color = QonectaTheme.green
            canvas.drawText("🔒 Cerrado · Seguridad activa", 22f, boxTop + 34f, paint)

            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textSize = 16f
            paint.color = QonectaTheme.text
            canvas.drawText("Puertas, ventanillas, capó y portón trasero cerrados", 22f, boxTop + 66f, paint)

            paint.textSize = 15f
            paint.color = QonectaTheme.dim
            canvas.drawText("TPMS: Presiones parejas (2,5 bar) · Toca el coche para cambiar su color", 22f, boxTop + 98f, paint)
        }
    }

    // =========================================================================
    // VISTAS PERSONALIZADAS EN CANVAS AMPLIADAS (Elevación, Velocímetro, G-Meter, Potencia)
    // =========================================================================

    private class ElevationProfileChartView(
        context: Context,
        private val plan: RoutePlan,
        private val selectedStop: ChargerInfo?,
        private val effectiveArrivalSoc: Int,
    ) : View(context) {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = QonectaTheme.accent
        }
        private val socLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4.5f
            color = 0xFFFFB547.toInt()
            pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = QonectaTheme.outline
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.text
            textSize = 32f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.dim
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = QonectaTheme.accent
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pts = plan.elevationProfileM
            if (pts.size < 2) return
            val w = width.toFloat()
            val h = height.toFloat()
            val padL = 24f
            val padR = 24f
            val padT = 38f
            val padB = 48f
            val plotW = w - padL - padR
            val plotH = h - padT - padB
            if (plotW <= 0f || plotH <= 0f) return

            val minAlt = (pts.minOrNull() ?: 0f) - 20f
            val maxAlt = (pts.maxOrNull() ?: 100f) + 20f
            val span = max(40f, maxAlt - minAlt)

            fillPaint.shader = LinearGradient(
                0f,
                padT,
                0f,
                padT + plotH,
                QonectaTheme.withAlpha(QonectaTheme.accent, 0x66),
                QonectaTheme.withAlpha(QonectaTheme.accent, 0x08),
                Shader.TileMode.CLAMP,
            )

            canvas.drawLine(padL, padT, padL + plotW, padT, gridPaint)
            canvas.drawLine(padL, padT + plotH / 2f, padL + plotW, padT + plotH / 2f, gridPaint)
            canvas.drawLine(padL, padT + plotH, padL + plotW, padT + plotH, gridPaint)

            val path = Path()
            val fillPath = Path()
            val socPath = Path()
            pts.forEachIndexed { idx, alt ->
                val frac = idx.toFloat() / (pts.size - 1)
                val x = padL + frac * plotW
                val y = padT + plotH - ((alt - minAlt) / span) * plotH
                if (idx == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, padT + plotH)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
                if (idx == pts.size - 1) {
                    fillPath.lineTo(x, padT + plotH)
                    fillPath.close()
                }

                val socAtPt = plan.departureSocPct + (effectiveArrivalSoc - plan.departureSocPct) * frac
                val socY = padT + plotH - (socAtPt.coerceIn(0f, 100f) / 100f) * plotH
                if (idx == 0) socPath.moveTo(x, socY) else socPath.lineTo(x, socY)
            }
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(path, linePaint)
            canvas.drawPath(socPath, socLinePaint)

            // Si hay un cargador seleccionado como parada, dibujarlo en el perfil
            selectedStop?.let { stop ->
                val frac = stop.routeProgressFraction.coerceIn(0.15f, 0.85f)
                val sx = padL + frac * plotW
                val ptIdx = (frac * (pts.size - 1)).roundToInt().coerceIn(0, pts.size - 1)
                val sy = padT + plotH - ((pts[ptIdx] - minAlt) / span) * plotH
                canvas.drawCircle(sx, sy, 14f, pinPaint)
                val stopLabel = "⚡ Parada: ${stop.name.take(20)} (${stop.powerKw} kW)"
                val lw = textPaint.measureText(stopLabel)
                val lx = (sx - lw / 2f).coerceIn(padL + 10f, w - padR - lw - 10f)
                canvas.drawText(stopLabel, lx, max(32f, sy - 20f), textPaint)
            }

            val maxLabel = "Máx ${(pts.maxOrNull() ?: 0f).roundToInt()} m"
            canvas.drawText(maxLabel, padL + 8f, padT - 10f, subTextPaint)

            canvas.drawText(
                "Salida ${plan.departureSocPct}% (${pts.first().roundToInt()} m)",
                padL + 8f,
                h - 10f,
                textPaint,
            )
            val endTxt = "Llegada $effectiveArrivalSoc% (${pts.last().roundToInt()} m)"
            val tw = textPaint.measureText(endTxt)
            canvas.drawText(endTxt, w - padR - tw - 8f, h - 10f, textPaint)
        }
    }

    private class SpeedGaugeCanvasView(
        context: Context,
        private val speedKmh: Float,
        private val maxSpeedKmh: Float,
        private val speedLimitKmh: Int = 50,
        private val sprintLabel: String = "",
    ) : View(context) {
        private val segActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val segIdle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = 0x26FFFFFF
        }
        private val maxTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFF5C5C.toInt()
        }
        private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.dim
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val bigSpeedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.text
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.dim
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val sprintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.accent
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val limitBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFF8FAFC.toInt()
        }
        private val limitBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFEF4444.toInt()
        }
        private val limitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        private val arcRect = RectF()
        private val triPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val cx = w / 2f
            val cy = h * 0.50f
            val r = min(w, h) * 0.42f
            val stroke = max(14f, r * 0.105f)
            segActive.strokeWidth = stroke
            segIdle.strokeWidth = stroke
            arcRect.set(cx - r, cy - r, cx + r, cy + r)

            // 45 segmentos sobre arco de 270° (135°..405°), escala 0..180 km/h
            val segments = 45
            val maxScale = 180f
            val startAngle = 135f
            val totalSweep = 270f
            val stepAngle = totalSweep / segments
            val segSweep = stepAngle * 0.56f
            val overLimit = speedLimitKmh > 0 && speedKmh > speedLimitKmh + 3f

            for (i in 0 until segments) {
                val segSpeed = (i + 0.5f) / segments * maxScale
                val a = startAngle + i * stepAngle
                if (segSpeed <= speedKmh) {
                    segActive.color = when {
                        overLimit -> 0xFFFF5C5C.toInt()
                        segSpeed > 130f -> QonectaTheme.amber
                        else -> QonectaTheme.accent
                    }
                    canvas.drawArc(arcRect, a, segSweep, false, segActive)
                } else {
                    canvas.drawArc(arcRect, a, segSweep, false, segIdle)
                }
            }

            // Etiquetas interiores de escala: 0, 30, 60, 90, 120, 150, 180
            scalePaint.textSize = max(15f, r * 0.095f)
            val labelR = r - stroke * 1.45f
            for (spd in 0..180 step 30) {
                val rad = Math.toRadians((startAngle + (spd / maxScale) * totalSweep).toDouble())
                val lx = cx + (cos(rad) * labelR).toFloat()
                val ly = cy + (sin(rad) * labelR).toFloat() + scalePaint.textSize * 0.35f
                canvas.drawText(spd.toString(), lx, ly, scalePaint)
            }

            // Marcador triangular rojo exterior de velocidad máxima del viaje
            if (maxSpeedKmh >= 1f) {
                val clampedMax = maxSpeedKmh.coerceAtMost(maxScale)
                val rad = Math.toRadians((startAngle + (clampedMax / maxScale) * totalSweep).toDouble())
                val rOuter = r + stroke * 0.98f
                val rInner = r + stroke * 0.36f
                val spread = 0.042
                triPath.reset()
                triPath.moveTo(
                    cx + (cos(rad) * rInner).toFloat(),
                    cy + (sin(rad) * rInner).toFloat(),
                )
                triPath.lineTo(
                    cx + (cos(rad - spread) * rOuter).toFloat(),
                    cy + (sin(rad - spread) * rOuter).toFloat(),
                )
                triPath.lineTo(
                    cx + (cos(rad + spread) * rOuter).toFloat(),
                    cy + (sin(rad + spread) * rOuter).toFloat(),
                )
                triPath.close()
                canvas.drawPath(triPath, maxTickPaint)
            }

            // Señal europea de límite de velocidad en la esquina superior derecha
            if (speedLimitKmh > 0) {
                val signR = max(24f, r * 0.165f)
                val sx = min(w - signR - 8f, cx + r * 0.88f)
                val sy = max(signR + 8f, cy - r * 0.82f)
                limitBorderPaint.strokeWidth = signR * 0.25f
                canvas.drawCircle(sx, sy, signR, limitBgPaint)
                canvas.drawCircle(sx, sy, signR - limitBorderPaint.strokeWidth * 0.5f, limitBorderPaint)
                limitTextPaint.textSize = signR * (if (speedLimitKmh >= 100) 0.78f else 0.92f)
                canvas.drawText(speedLimitKmh.toString(), sx, sy + limitTextPaint.textSize * 0.35f, limitTextPaint)
            }

            // Número central gigante + km/h
            bigSpeedPaint.color = if (overLimit) 0xFFFF5C5C.toInt() else QonectaTheme.text
            bigSpeedPaint.textSize = r * 0.62f
            canvas.drawText(speedKmh.roundToInt().toString(), cx, cy + bigSpeedPaint.textSize * 0.22f, bigSpeedPaint)

            unitPaint.textSize = max(16f, r * 0.115f)
            canvas.drawText("km/h", cx, cy + bigSpeedPaint.textSize * 0.22f + unitPaint.textSize * 1.45f, unitPaint)

            // Línea de aceleración 0–50 / 0–100 km/h debajo del arco
            if (sprintLabel.isNotEmpty()) {
                sprintPaint.textSize = max(15f, r * 0.102f)
                canvas.drawText(sprintLabel, cx, h - 10f, sprintPaint)
            }
        }
    }

    private class GMeterCanvasView(
        context: Context,
        private val longG: Float,
        private val latG: Float,
        private val maxAccelG: Float,
        private val maxBrakeG: Float,
        private val maxLeftG: Float,
        private val maxRightG: Float,
        private val trail: List<FloatArray>,
    ) : View(context) {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
            color = 0x26FFFFFF
        }
        private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0x3DFFFFFF
        }
        private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0x24FFFFFF
        }
        private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.dim
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val ringScalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x889AA8BA.toInt()
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = QonectaTheme.amber
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = QonectaTheme.accent
        }
        private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = QonectaTheme.withAlpha(QonectaTheme.accent, 0x38)
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = QonectaTheme.accent
        }
        private val dotCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val triPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val cx = w / 2f
            val cy = h / 2f
            val maxG = 0.6f
            val labelPad = max(28f, min(w, h) * 0.11f)
            val r = min(w, h) / 2f - labelPad
            if (r <= 10f) return

            // 3 anillos concéntricos: 0,2 g · 0,4 g · 0,6 g
            val r02 = r * (0.2f / maxG)
            val r04 = r * (0.4f / maxG)
            canvas.drawCircle(cx, cy, r02, ringPaint)
            canvas.drawCircle(cx, cy, r04, ringPaint)
            canvas.drawCircle(cx, cy, r, outerRingPaint)

            // Ejes en cruz
            canvas.drawLine(cx - r, cy, cx + r, cy, axisPaint)
            canvas.drawLine(cx, cy - r, cx, cy + r, axisPaint)

            // Escala en diagonal (0,2 · 0,4 · 0,6)
            ringScalePaint.textSize = max(13f, r * 0.095f)
            val c45 = 0.7071f
            canvas.drawText("0,2", cx + r02 * c45 + 4f, cy - r02 * c45 - 3f, ringScalePaint)
            canvas.drawText("0,4", cx + r04 * c45 + 4f, cy - r04 * c45 - 3f, ringScalePaint)
            canvas.drawText("0,6", cx + r * c45 + 4f, cy - r * c45 - 3f, ringScalePaint)

            // Etiquetas cardinales: FRENADA (arriba), ACELERACIÓN (abajo), IZQ., DCHA.
            axisLabelPaint.textSize = max(13f, r * 0.095f)
            axisLabelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("FRENADA", cx, cy - r - 10f, axisLabelPaint)
            canvas.drawText("ACELERACIÓN", cx, cy + r + axisLabelPaint.textSize + 8f, axisLabelPaint)
            axisLabelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("IZQ.", cx - r - 8f, cy + axisLabelPaint.textSize * 0.35f, axisLabelPaint)
            axisLabelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("DCHA.", cx + r + 8f, cy + axisLabelPaint.textSize * 0.35f, axisLabelPaint)

            // Picos direccionales ámbar en los 4 ejes (igual que Qonecta+ InstrumentsScreen.java)
            peakPaint.textSize = max(13f, r * 0.095f)
            drawAxisPeak(canvas, cx, cy, r, maxG, 0f, -1f, maxBrakeG)
            drawAxisPeak(canvas, cx, cy, r, maxG, 0f, +1f, maxAccelG)
            drawAxisPeak(canvas, cx, cy, r, maxG, -1f, 0f, maxLeftG)
            drawAxisPeak(canvas, cx, cy, r, maxG, +1f, 0f, maxRightG)

            // Estela histórica de fuerzas G
            if (trail.size >= 2) {
                for (i in 1 until trail.size) {
                    val p0 = trail[i - 1]
                    val p1 = trail[i]
                    val x0 = cx + (p0[0] / maxG).coerceIn(-1f, 1f) * r
                    val y0 = cy + (p0[1] / maxG).coerceIn(-1f, 1f) * r
                    val x1 = cx + (p1[0] / maxG).coerceIn(-1f, 1f) * r
                    val y1 = cy + (p1[1] / maxG).coerceIn(-1f, 1f) * r
                    val frac = i.toFloat() / trail.size
                    trailPaint.alpha = (frac * 175).toInt().coerceIn(20, 175)
                    trailPaint.strokeWidth = 2.5f + frac * 4.5f
                    canvas.drawLine(x0, y0, x1, y1, trailPaint)
                }
            }

            // Punto luminoso actual
            val bx = cx + (latG / maxG).coerceIn(-1f, 1f) * r
            val by = cy + (longG / maxG).coerceIn(-1f, 1f) * r
            val dotR = max(10f, r * 0.075f)
            canvas.drawCircle(bx, by, dotR * 2.1f, dotHaloPaint)
            canvas.drawCircle(bx, by, dotR, dotPaint)
            canvas.drawCircle(bx, by, dotR * 0.36f, dotCorePaint)
        }

        private fun drawAxisPeak(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            r: Float,
            maxG: Float,
            dirX: Float,
            dirY: Float,
            value: Float,
        ) {
            if (value < 0.03f) return
            val clamped = (value / maxG).coerceAtMost(1f)
            val px = cx + dirX * clamped * r
            val py = cy + dirY * clamped * r
            val s = max(6f, r * 0.042f)

            triPath.reset()
            if (dirX == 0f) {
                triPath.moveTo(px, py + dirY * s * 0.6f)
                triPath.lineTo(px - s, py - dirY * s)
                triPath.lineTo(px + s, py - dirY * s)
            } else {
                triPath.moveTo(px + dirX * s * 0.6f, py)
                triPath.lineTo(px - dirX * s, py - s)
                triPath.lineTo(px - dirX * s, py + s)
            }
            triPath.close()
            canvas.drawPath(triPath, peakPaint)

            val txt = String.format(Locale.getDefault(), "%.2f", value)
            if (dirX == 0f) {
                peakPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(txt, px + s * 1.6f, py + peakPaint.textSize * 0.35f, peakPaint)
            } else {
                peakPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(txt, px, py - s * 1.5f, peakPaint)
            }
        }
    }

    /**
     * Doble dial de Inclinación Qonecta+ (CABECEO con vista lateral del SUV Leapmotor C10 y
     * BALANCEO con vista frontal del SUV Leapmotor C10), portado de `InstrumentsScreen.java` y `CarArt.java`.
     */
    private class ClinometerCanvasView(
        context: Context,
        private val pitchDeg: Float,
        private val rollDeg: Float,
    ) : View(context) {
        private val dialBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x16FFFFFF
        }
        private val horizonFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = QonectaTheme.withAlpha(QonectaTheme.accent, 0x16)
        }
        private val dialRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0x3DFFFFFF
        }
        private val refLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0x38FFFFFF
            pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
            color = 0x66FFFFFF
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.dim
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.text
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.accent
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val clipPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val halfW = w / 2f
            val dialR = min(halfW * 0.43f, h * 0.34f)
            val dialCy = dialR + 8f

            val gradePct = (kotlin.math.tan(Math.toRadians(pitchDeg.toDouble())) * 100.0).toFloat()
            drawDial(
                canvas = canvas,
                cx = halfW * 0.5f,
                cy = dialCy,
                r = dialR,
                angleDeg = pitchDeg,
                sideProfile = true,
                title = "CABECEO",
                degText = String.format(Locale.getDefault(), "%+.1f°", pitchDeg),
                subText = String.format(Locale.getDefault(), "%+.1f %%", gradePct),
            )

            drawDial(
                canvas = canvas,
                cx = halfW * 1.5f,
                cy = dialCy,
                r = dialR,
                angleDeg = rollDeg,
                sideProfile = false,
                title = "BALANCEO",
                degText = String.format(Locale.getDefault(), "%+.1f°", rollDeg),
                subText = if (kotlin.math.abs(rollDeg) < 0.4f) "Nivelado" else if (rollDeg > 0f) "Inclin. dcha." else "Inclin. izq.",
            )
        }

        private fun drawDial(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            r: Float,
            angleDeg: Float,
            sideProfile: Boolean,
            title: String,
            degText: String,
            subText: String,
        ) {
            canvas.drawCircle(cx, cy, r, dialBgPaint)

            // Horizonte artificial inclinado dentro del círculo
            val clampedRot = (-angleDeg).coerceIn(-25f, 25f)
            canvas.save()
            clipPath.reset()
            clipPath.addCircle(cx, cy, r - 2f, Path.Direction.CW)
            canvas.clipPath(clipPath)
            canvas.rotate(clampedRot, cx, cy)
            canvas.drawRect(cx - r * 1.4f, cy, cx + r * 1.4f, cy + r * 1.4f, horizonFillPaint)
            canvas.restore()

            // Línea horizontal punteada de referencia 0°
            canvas.drawLine(cx - r * 0.85f, cy, cx + r * 0.85f, cy, refLinePaint)

            // Marcas superiores de escala (-15°..+15° cada 5°)
            for (deg in -15..15 step 5) {
                val rad = Math.toRadians((deg - 90).toDouble())
                val inner = r * (if (deg == 0) 0.80f else 0.86f)
                canvas.drawLine(
                    cx + (cos(rad) * inner).toFloat(),
                    cy + (sin(rad) * inner).toFloat(),
                    cx + (cos(rad) * r).toFloat(),
                    cy + (sin(rad) * r).toFloat(),
                    tickPaint,
                )
            }

            canvas.drawCircle(cx, cy, r, dialRingPaint)

            // Silueta vectorial del SUV Leapmotor C10 rotando en vivo
            canvas.save()
            canvas.rotate(clampedRot, cx, cy)
            if (sideProfile) {
                C10CarArt.drawSide(canvas, cx, cy, r * 1.48f, QonectaTheme.accent)
            } else {
                C10CarArt.drawFront(canvas, cx, cy, r * 1.24f, QonectaTheme.accent)
            }
            canvas.restore()

            // Textos inferiores: CABECEO / BALANCEO + Grados + % Pendiente
            labelPaint.textSize = max(12f, r * 0.17f)
            val textTop = cy + r + labelPaint.textSize + 6f
            canvas.drawText(title, cx, textTop, labelPaint)

            valPaint.textSize = max(20f, r * 0.34f)
            val valY = textTop + valPaint.textSize + 4f
            canvas.drawText(degText, cx, valY, valPaint)

            subPaint.textSize = max(13f, r * 0.19f)
            canvas.drawText(subText, cx, valY + subPaint.textSize + 4f, subPaint)
        }
    }

    /**
     * Anillo de Suavidad de Conducción Qonecta+ (0..100) con calificación en vivo
     * («Excelente» / «Buena» / «Normal») y media de los últimos 30 s.
     */
    private class SmoothnessGaugeCanvasView(
        context: Context,
        private val score: Int,
        private val recentScore: Int,
    ) : View(context) {
        private val ringBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = 0x24FFFFFF
        }
        private val ringFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.text
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        private val ratingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.dim
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val arcRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val ringR = min(h * 0.39f, w * 0.195f)
            val stroke = max(10f, ringR * 0.185f)
            ringBgPaint.strokeWidth = stroke
            ringFgPaint.strokeWidth = stroke

            val ringCx = ringR + stroke + 4f
            val ringCy = h * 0.52f
            arcRect.set(ringCx - ringR, ringCy - ringR, ringCx + ringR, ringCy + ringR)

            val sClamped = score.coerceIn(0, 100)
            val color = when {
                sClamped >= 80 -> 0xFF2EE59D.toInt()
                sClamped >= 60 -> QonectaTheme.amber
                else -> 0xFFFF5C5C.toInt()
            }
            ringFgPaint.color = color

            canvas.drawArc(arcRect, 135f, 270f, false, ringBgPaint)
            canvas.drawArc(arcRect, 135f, (sClamped / 100f) * 270f, false, ringFgPaint)

            scorePaint.textSize = ringR * 0.78f
            canvas.drawText(sClamped.toString(), ringCx, ringCy + scorePaint.textSize * 0.34f, scorePaint)

            val tx = ringCx + ringR + stroke + 14f
            val availW = max(80f, w - tx - 4f)
            val ratingText = when {
                sClamped >= 85 -> "Excelente"
                sClamped >= 70 -> "Buena"
                sClamped >= 55 -> "Normal"
                else -> "Brusca"
            }
            ratingPaint.color = color
            ratingPaint.textSize = max(20f, min(ringR * 0.44f, availW / 5.2f))
            val topY = ringCy - ringR * 0.32f
            canvas.drawText(ratingText, tx, topY, ratingPaint)

            subPaint.textSize = max(13f, min(ringR * 0.25f, availW / 10.5f))
            subPaint.color = QonectaTheme.text
            canvas.drawText("últimos 30 s: $recentScore", tx, topY + subPaint.textSize * 1.45f, subPaint)

            subPaint.color = QonectaTheme.dim
            val hintSize = max(11f, min(ringR * 0.21f, availW / 15.2f))
            subPaint.textSize = hintSize
            canvas.drawText("Según los tirones al acelerar,", tx, topY + hintSize * 3.15f, subPaint)
            canvas.drawText("frenar y girar", tx, topY + hintSize * 4.45f, subPaint)
        }
    }

    /**
     * Arte vectorial del SUV Leapmotor C10 (vista lateral para Cabeceo y vista frontal para Balanceo),
     * portado de `CarArt.java` de Qonecta+.
     */
    private object C10CarArt {
        private val bodyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val bodyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x559AD8FF
        }
        private val darkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF0A0F18.toInt()
        }
        private val tirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF161E2B.toInt()
        }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFD8E4F2.toInt()
        }
        private val headlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFFF4CC.toInt()
        }
        private val taillightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFF4D4D.toInt()
        }

        fun drawSide(canvas: Canvas, cx: Float, cy: Float, width: Float, accent: Int) {
            val u = width / 2f
            val h = u * 0.62f
            val sw = max(1.8f, width * 0.022f)

            val path = Path().apply {
                moveTo(cx - 0.90f * u, cy + 0.22f * h)
                lineTo(cx - 0.92f * u, cy - 0.08f * h)
                quadTo(cx - 0.90f * u, cy - 0.28f * h, cx - 0.72f * u, cy - 0.32f * h)
                lineTo(cx - 0.44f * u, cy - 0.82f * h)
                quadTo(cx - 0.36f * u, cy - 0.92f * h, cx - 0.20f * u, cy - 0.92f * h)
                lineTo(cx + 0.24f * u, cy - 0.90f * h)
                quadTo(cx + 0.36f * u, cy - 0.88f * h, cx + 0.48f * u, cy - 0.36f * h)
                lineTo(cx + 0.84f * u, cy - 0.24f * h)
                quadTo(cx + 0.94f * u, cy - 0.18f * h, cx + 0.94f * u, cy + 0.08f * h)
                lineTo(cx + 0.90f * u, cy + 0.22f * h)
                close()
            }

            bodyFill.shader = LinearGradient(
                cx,
                cy - 0.92f * h,
                cx,
                cy + 0.24f * h,
                QonectaTheme.withAlpha(accent, 0xCC),
                QonectaTheme.withAlpha(accent, 0x44),
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(path, bodyFill)
            bodyFill.shader = null

            bodyStroke.strokeWidth = sw
            bodyStroke.color = accent
            canvas.drawPath(path, bodyStroke)

            val glass = Path().apply {
                moveTo(cx - 0.64f * u, cy - 0.34f * h)
                lineTo(cx - 0.40f * u, cy - 0.78f * h)
                lineTo(cx + 0.22f * u, cy - 0.76f * h)
                lineTo(cx + 0.40f * u, cy - 0.34f * h)
                close()
            }
            canvas.drawPath(glass, glassPaint)

            bodyStroke.strokeWidth = sw * 0.75f
            bodyStroke.color = QonectaTheme.withAlpha(accent, 0xAA)
            canvas.drawLine(cx - 0.06f * u, cy - 0.77f * h, cx - 0.06f * u, cy - 0.34f * h, bodyStroke)

            canvas.drawRoundRect(
                RectF(cx + 0.83f * u, cy - 0.14f * h, cx + 0.95f * u, cy - 0.01f * h),
                sw,
                sw,
                headlightPaint,
            )
            canvas.drawRoundRect(
                RectF(cx - 0.93f * u, cy - 0.16f * h, cx - 0.83f * u, cy - 0.01f * h),
                sw,
                sw,
                taillightPaint,
            )

            val wheelR = 0.21f * u
            val wheelY = cy + 0.22f * h
            drawWheel(canvas, cx - 0.56f * u, wheelY, wheelR, accent)
            drawWheel(canvas, cx + 0.56f * u, wheelY, wheelR, accent)
        }

        private fun drawWheel(canvas: Canvas, x: Float, y: Float, r: Float, accent: Int) {
            canvas.drawCircle(x, y, r * 1.15f, darkFill)
            canvas.drawCircle(x, y, r, tirePaint)
            bodyStroke.strokeWidth = max(1.4f, r * 0.14f)
            bodyStroke.color = QonectaTheme.withAlpha(accent, 0xCC)
            canvas.drawCircle(x, y, r * 0.68f, rimPaint)
            canvas.drawCircle(x, y, r * 0.68f, bodyStroke)
            canvas.drawCircle(x, y, r * 0.22f, darkFill)
        }

        fun drawFront(canvas: Canvas, cx: Float, cy: Float, width: Float, accent: Int) {
            val u = width / 2f
            val h = u * 0.84f
            val sw = max(1.8f, width * 0.024f)

            val tireW = 0.22f * u
            val tireTop = cy + 0.08f * h
            val tireBot = cy + 0.50f * h
            canvas.drawRoundRect(RectF(cx - 0.92f * u, tireTop, cx - 0.92f * u + tireW, tireBot), sw * 1.5f, sw * 1.5f, tirePaint)
            canvas.drawRoundRect(RectF(cx + 0.92f * u - tireW, tireTop, cx + 0.92f * u, tireBot), sw * 1.5f, sw * 1.5f, tirePaint)

            val body = Path().apply {
                moveTo(cx - 0.88f * u, cy + 0.24f * h)
                lineTo(cx - 0.92f * u, cy - 0.06f * h)
                quadTo(cx - 0.90f * u, cy - 0.28f * h, cx - 0.72f * u, cy - 0.34f * h)
                lineTo(cx - 0.54f * u, cy - 0.80f * h)
                quadTo(cx - 0.46f * u, cy - 0.92f * h, cx - 0.30f * u, cy - 0.92f * h)
                lineTo(cx + 0.30f * u, cy - 0.92f * h)
                quadTo(cx + 0.46f * u, cy - 0.92f * h, cx + 0.54f * u, cy - 0.80f * h)
                lineTo(cx + 0.72f * u, cy - 0.34f * h)
                quadTo(cx + 0.90f * u, cy - 0.28f * h, cx + 0.92f * u, cy - 0.06f * h)
                lineTo(cx + 0.88f * u, cy + 0.24f * h)
                close()
            }

            bodyFill.shader = LinearGradient(
                cx,
                cy - 0.92f * h,
                cx,
                cy + 0.24f * h,
                QonectaTheme.withAlpha(accent, 0xCC),
                QonectaTheme.withAlpha(accent, 0x44),
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(body, bodyFill)
            bodyFill.shader = null

            bodyStroke.strokeWidth = sw
            bodyStroke.color = accent
            canvas.drawPath(body, bodyStroke)

            val windshield = Path().apply {
                moveTo(cx - 0.62f * u, cy - 0.34f * h)
                lineTo(cx - 0.46f * u, cy - 0.76f * h)
                lineTo(cx + 0.46f * u, cy - 0.76f * h)
                lineTo(cx + 0.62f * u, cy - 0.34f * h)
                close()
            }
            canvas.drawPath(windshield, glassPaint)

            // Barra LED frontal continua característica del Leapmotor C10 + faros
            canvas.drawRoundRect(RectF(cx - 0.82f * u, cy - 0.20f * h, cx + 0.82f * u, cy - 0.11f * h), sw, sw, headlightPaint)
            canvas.drawRoundRect(RectF(cx - 0.46f * u, cy - 0.02f * h, cx + 0.46f * u, cy + 0.14f * h), sw * 1.5f, sw * 1.5f, darkFill)
        }

        fun drawTop(canvas: Canvas, cx: Float, cy: Float, width: Float, height: Float) {
            val hw = width / 2f
            val hh = height / 2f
            val r = width * 0.28f

            // Retrovisores laterales
            bodyFill.color = 0xFFD8E4F2.toInt()
            canvas.drawRoundRect(RectF(cx - hw * 1.18f, cy - hh * 0.30f, cx - hw * 0.90f, cy - hh * 0.14f), 4f, 4f, bodyFill)
            canvas.drawRoundRect(RectF(cx + hw * 0.90f, cy - hh * 0.30f, cx + hw * 1.18f, cy - hh * 0.14f), 4f, 4f, bodyFill)

            // Carrocería SUV vista cenital
            bodyFill.shader = LinearGradient(
                cx - hw,
                cy - hh,
                cx + hw,
                cy + hh,
                0xFFEAF2FC.toInt(),
                0xFFB8C9DC.toInt(),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(RectF(cx - hw, cy - hh, cx + hw, cy + hh), r, r, bodyFill)
            bodyFill.shader = null

            bodyStroke.strokeWidth = max(1.8f, width * 0.04f)
            bodyStroke.color = 0xFF64748B.toInt()
            canvas.drawRoundRect(RectF(cx - hw, cy - hh, cx + hw, cy + hh), r, r, bodyStroke)

            // Parabrisas + techo panorámico de cristal + luneta trasera
            val roofPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = 0xFF1E293B.toInt()
            }
            canvas.drawRoundRect(
                RectF(cx - hw * 0.76f, cy - hh * 0.52f, cx + hw * 0.76f, cy + hh * 0.62f),
                r * 0.55f,
                r * 0.55f,
                roofPaint,
            )
            // Separador capó / techo
            bodyStroke.strokeWidth = max(1.5f, width * 0.03f)
            bodyStroke.color = 0xFF94A3B8.toInt()
            canvas.drawLine(cx - hw * 0.72f, cy - hh * 0.24f, cx + hw * 0.72f, cy - hh * 0.24f, bodyStroke)
            canvas.drawLine(cx - hw * 0.70f, cy + hh * 0.36f, cx + hw * 0.70f, cy + hh * 0.36f, bodyStroke)

            // Faros delanteros (arriba) y barra trasera roja (abajo)
            canvas.drawRoundRect(RectF(cx - hw * 0.72f, cy - hh * 0.96f, cx + hw * 0.72f, cy - hh * 0.87f), 3f, 3f, headlightPaint)
            canvas.drawRoundRect(RectF(cx - hw * 0.74f, cy + hh * 0.88f, cx + hw * 0.74f, cy + hh * 0.97f), 3f, 3f, taillightPaint)
        }
    }

    /**
     * Gráfica de Potencia y Regeneración Qonecta+ (`paintPower` de `EfficiencyScreen.java`):
     * - Ventana real de 2 min (120 s) o 10 min (600 s) a 1 muestra/s para que no pase volando.
     * - Por encima de 0 kW: relleno y trazo turquesa (`QonectaTheme.accent`).
     * - Por debajo de 0 kW (Regeneración): relleno y trazo verde esmeralda (`0xFF2EE59D`).
     */
    private class PowerHistoryCanvasView(
        context: Context,
        private val samples: List<Float>,
        private val windowSecs: Int = 120,
    ) : View(context) {
        private val boxBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = QonectaTheme.surfaceTop
        }
        private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = QonectaTheme.outline
        }
        private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.dim
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val timeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.dim
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val posFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = QonectaTheme.withAlpha(QonectaTheme.accent, 0x3A)
        }
        private val posStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4.2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = QonectaTheme.accent
        }
        private val negFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x4D2EE59D
        }
        private val negStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4.2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0xFF2EE59D.toInt()
        }
        private val gRect = RectF()
        private val linePath = Path()
        private val areaPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val padL = 52f
            val padB = 26f
            val padT = 6f
            val padR = 4f
            gRect.set(padL, padT, w - padR, h - padB)
            if (gRect.width() <= 10f || gRect.height() <= 10f) return

            var hi = 20f
            var lo = -10f
            for (v in samples) {
                if (v > hi) hi = v
                if (v < lo) lo = v
            }
            hi = (kotlin.math.ceil((hi / 10f).toDouble()) * 10.0).toFloat().coerceAtLeast(20f)
            lo = if (lo < 0f) (kotlin.math.floor((lo / 10f).toDouble()) * 10.0).toFloat() else -10f

            val span = (hi - lo).coerceAtLeast(10f)
            val zeroY = gRect.top + (hi / span) * gRect.height()

            canvas.drawRoundRect(gRect, 14f, 14f, boxBgPaint)
            canvas.drawLine(gRect.left, zeroY, gRect.right, zeroY, zeroLinePaint)

            // Escala izquierda: máx (ej. 70), 0, mín (ej. -20 en verde)
            val txtSz = max(13f, gRect.height() * 0.105f)
            scalePaint.textSize = txtSz
            scalePaint.color = QonectaTheme.dim
            canvas.drawText(hi.roundToInt().toString(), gRect.left - 8f, gRect.top + txtSz + 2f, scalePaint)
            if (zeroY - gRect.top > 24f && gRect.bottom - zeroY > 22f) {
                canvas.drawText("0", gRect.left - 8f, zeroY + txtSz * 0.35f, scalePaint)
            }
            if (lo < 0f) {
                scalePaint.color = 0xFF2EE59D.toInt()
                canvas.drawText("−${kotlin.math.abs(lo.roundToInt())}", gRect.left - 8f, gRect.bottom - 2f, scalePaint)
            }

            // Etiquetas inferiores de tiempo: "-2 min" / "-10 min" a la izquierda y "ahora" a la derecha
            timeLabelPaint.textSize = txtSz
            timeLabelPaint.textAlign = Paint.Align.LEFT
            val winLabel = if (windowSecs >= 600) "−10 min" else "−2 min"
            canvas.drawText(winLabel, gRect.left, h - 4f, timeLabelPaint)
            timeLabelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("ahora", gRect.right, h - 4f, timeLabelPaint)

            if (samples.size < 2) return
            val n = samples.size
            val totalSlots = max(n, windowSecs)
            val dx = gRect.width() / max(1, totalSlots - 1)
            val x0 = gRect.right - (n - 1) * dx

            linePath.reset()
            areaPath.reset()
            areaPath.moveTo(x0, zeroY)
            for (i in 0 until n) {
                val x = x0 + i * dx
                val y = gRect.top + ((hi - samples[i]) / span) * gRect.height()
                if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                areaPath.lineTo(x, y)
            }
            areaPath.lineTo(gRect.right, zeroY)
            areaPath.close()

            // Parte superior (> 0 kW): Consumo en turquesa
            canvas.save()
            canvas.clipRect(gRect.left, gRect.top, gRect.right, zeroY)
            canvas.drawPath(areaPath, posFillPaint)
            canvas.drawPath(linePath, posStrokePaint)
            canvas.restore()

            // Parte inferior (< 0 kW): Regeneración en verde esmeralda
            canvas.save()
            canvas.clipRect(gRect.left, zeroY, gRect.right, gRect.bottom)
            canvas.drawPath(areaPath, negFillPaint)
            canvas.drawPath(linePath, negStrokePaint)
            canvas.restore()
        }
    }

    /**
     * Tarjeta de Condiciones Qonecta+ (`paintConditions` de `EfficiencyScreen.java`):
     * Radar circular con el SUV Leapmotor C10 visto desde arriba (`C10CarArt.drawTop`), flecha de viento
     * incidente y 4 filas de telemetría ambiental (Viento, Temperatura, Pendiente y Altitud).
     */
    private class ConditionsRadarCanvasView(
        context: Context,
        private val windKmh: Float,
        private val windRelDeg: Float,
        private val tempExtC: Float,
        private val tempCabinC: Float,
        private val gradePct: Float,
        private val altitudeM: Int,
        private val climbM: Int,
        private val descentM: Int,
    ) : View(context) {
        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = QonectaTheme.surfaceTop
        }
        private val circleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = QonectaTheme.outline
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = QonectaTheme.amber
        }
        private val arrowHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = QonectaTheme.amber
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.dim
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.05f
        }
        private val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.text
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val headPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val rad = min(h * 0.45f, w * 0.24f)
            val cx = rad + 6f
            val cy = h * 0.50f
            canvas.drawCircle(cx, cy, rad, circlePaint)
            canvas.drawCircle(cx, cy, rad, circleStrokePaint)

            // Coche visto desde arriba en el centro del radar
            C10CarArt.drawTop(canvas, cx, cy + rad * 0.08f, rad * 0.54f, rad * 1.08f)

            // Flecha de viento entrando hacia el morro del coche
            val relRad = Math.toRadians(windRelDeg.toDouble())
            val startR = rad * 0.84f
            val endR = rad * 0.48f
            val sx = cx + (sin(relRad) * startR).toFloat()
            val sy = cy - (cos(relRad) * startR).toFloat()
            val ex = cx + (sin(relRad) * endR).toFloat()
            val ey = cy - (cos(relRad) * endR).toFloat()
            canvas.drawLine(sx, sy, ex, ey, arrowPaint)

            val ang = kotlin.math.atan2((ey - sy).toDouble(), (ex - sx).toDouble())
            val hs = max(7f, rad * 0.13f)
            headPath.reset()
            headPath.moveTo(ex, ey)
            headPath.lineTo(
                ex - (cos(ang - 0.55) * hs).toFloat(),
                ey - (sin(ang - 0.55) * hs).toFloat(),
            )
            headPath.lineTo(
                ex - (cos(ang + 0.55) * hs).toFloat(),
                ey - (sin(ang + 0.55) * hs).toFloat(),
            )
            headPath.close()
            canvas.drawPath(headPath, arrowHeadPaint)

            // 4 filas a la derecha: VIENTO, TEMPERATURA, PENDIENTE, ALTITUD
            val tx = cx + rad + 18f
            val step = h / 4f
            val lblSz = max(12.5f, min(15f, step * 0.30f))
            val valSz = max(18f, min(23f, step * 0.50f))
            labelPaint.textSize = lblSz
            valPaint.textSize = valSz

            fun drawCondRow(idx: Int, lbl: String, value: String, color: Int) {
                val topY = idx * step + lblSz + 2f
                canvas.drawText(lbl, tx, topY, labelPaint)
                valPaint.color = color
                canvas.drawText(value, tx, topY + valSz + 3f, valPaint)
            }

            drawCondRow(
                0,
                "VIENTO",
                String.format(Locale.getDefault(), "%.0f km/h de cara", windKmh),
                QonectaTheme.amber,
            )
            drawCondRow(
                1,
                "TEMPERATURA",
                String.format(Locale.getDefault(), "%.0f °C ext · %.0f °C hab", tempExtC, tempCabinC),
                QonectaTheme.text,
            )
            val gradeColor = when {
                gradePct < -1.0f -> 0xFF2EE59D.toInt()
                gradePct > 1.5f -> QonectaTheme.amber
                else -> QonectaTheme.text
            }
            drawCondRow(
                2,
                "PENDIENTE",
                String.format(Locale.getDefault(), "%+.1f %%", gradePct),
                gradeColor,
            )
            drawCondRow(
                3,
                "ALTITUD",
                "$altitudeM m · ↑$climbM ↓$descentM",
                QonectaTheme.text,
            )
        }
    }

    /**
     * Barra apilada multicolor y desglose de energía Qonecta+ (`paintSplit` de `EfficiencyScreen.java`):
     * Aire, Rodadura, Subidas, Aceleración, Climatización y Electrónica + Energía Recuperada.
     */
    private class EnergySplitCanvasView(
        context: Context,
        private val grossKwh: Float,
        private val netKwh: Float,
        private val recoveredKwh: Float,
        private val shares: FloatArray,
    ) : View(context) {
        private val partColors = intArrayOf(
            0xFF38BDF8.toInt(), // Aire (Azul)
            0xFFA78BFA.toInt(), // Rodadura (Violeta)
            0xFFFFB547.toInt(), // Subidas (Ámbar)
            0xFFFF5C5C.toInt(), // Aceleración (Coral)
            0xFF00E5C7.toInt(), // Climatización (Turquesa)
            0xFF64748B.toInt(), // Electrónica (Gris azulado)
        )
        private val partNames = arrayOf(
            "Aire",
            "Rodadura",
            "Subidas",
            "Aceleración",
            "Climatización",
            "Electrónica",
        )
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.text
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val kwhPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.dim
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.text
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val recovPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2EE59D.toInt()
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.dim
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = QonectaTheme.outline
        }
        private val tmpRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            // 1. Barra apilada horizontal con extremos redondeados y separación de 3px
            val barH = max(22f, h * 0.105f)
            val rad = barH / 2f
            var x = 0f
            var lastIdx = -1
            for (i in shares.indices) {
                if (shares[i] * w >= 3f) lastIdx = i
            }
            var first = true
            for (i in shares.indices) {
                val segW = shares[i] * w
                if (segW < 3f) continue
                val x1 = x + segW - (if (i == lastIdx) 0f else 3.5f)
                barPaint.color = partColors[i]
                if (first || i == lastIdx) {
                    tmpRect.set(x, 0f, x1, barH)
                    canvas.drawRoundRect(tmpRect, rad, rad, barPaint)
                    if (first && i != lastIdx && x1 - x > rad) {
                        canvas.drawRect(x + rad, 0f, x1, barH, barPaint)
                    }
                    if (!first && i == lastIdx && x1 - x > rad) {
                        canvas.drawRect(x, 0f, x1 - rad, barH, barPaint)
                    }
                } else {
                    canvas.drawRect(x, 0f, x1, barH, barPaint)
                }
                first = false
                x += segW
            }

            // 2. Leyenda de 6 filas: icono cuadrado redondeado + nombre + kWh + %
            val footerH = max(56f, h * 0.22f)
            val listTop = barH + max(16f, h * 0.055f)
            val listAvail = max(90f, h - listTop - footerH)
            val step = listAvail / 6f
            val rowTextSz = max(16.5f, min(21f, step * 0.68f))
            namePaint.textSize = rowTextSz
            kwhPaint.textSize = rowTextSz * 0.92f
            pctPaint.textSize = rowTextSz

            for (i in partNames.indices) {
                val cy = listTop + i * step + rowTextSz * 0.8f
                val dotSz = rowTextSz * 0.72f
                barPaint.color = partColors[i]
                tmpRect.set(0f, cy - dotSz + 2f, dotSz, cy + 2f)
                canvas.drawRoundRect(tmpRect, 4f, 4f, barPaint)

                canvas.drawText(partNames[i], dotSz + 12f, cy, namePaint)
                val partKwh = grossKwh * shares[i]
                val pct = (shares[i] * 100f).roundToInt()
                canvas.drawText(String.format(Locale.getDefault(), "%.2f kWh", partKwh), w - 74f, cy, kwhPaint)
                canvas.drawText("$pct %", w, cy, pctPaint)
            }

            // 3. Línea divisoria + Recuperado en verde + nota de neto
            val divY = h - footerH + 6f
            canvas.drawLine(0f, divY, w, divY, divPaint)

            recovPaint.textSize = rowTextSz
            val recovY = divY + rowTextSz + 8f
            recovPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("🍃 Recuperado", 0f, recovY, recovPaint)
            recovPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(Locale.getDefault(), "−%.2f kWh", recoveredKwh), w, recovY, recovPaint)

            notePaint.textSize = max(12f, rowTextSz * 0.78f)
            canvas.drawText(
                String.format(Locale.getDefault(), "Estimado con el modelo del C10 · neto %.2f kWh", netKwh),
                0f,
                h - 4f,
                notePaint,
            )
        }
    }

    // =========================================================================
    // UTILIDADES Y DIÁLOGO MODAL
    // =========================================================================

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", "QonectaUnit-LeapmotorC10/1.0")
        }
        return try {
            BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun cardLayout(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(22), dp(18), dp(22), dp(18))
        background = QonectaTheme.cardDrawable(dp(22).toFloat(), QonectaTheme.outline, dp(2))
    }

    private fun sectionLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSp(if (isSplitScreen) 14f else 16f)
        setTextColor(QonectaTheme.dim)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = if (isSplitScreen) 0.04f else 0.08f
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun metricBox(title: String, value: String, subtitle: String, valueColor: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = QonectaTheme.chipDrawable(dp(18).toFloat(), QonectaTheme.outline)
            addView(TextView(context).apply {
                text = title
                setTextSp(15f)
                setTextColor(QonectaTheme.dim)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.06f
            })
            addView(TextView(context).apply {
                text = value
                setTextSp(34f)
                setTextColor(
                    when (valueColor) {
                        PanelFormat.ACCENT -> QonectaTheme.accent
                        PanelFormat.TEXT -> QonectaTheme.text
                        else -> valueColor
                    }
                )
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(2))
            })
            addView(TextView(context).apply {
                text = subtitle
                setTextSp(16f)
                setTextColor(QonectaTheme.dim)
            })
        }

    private fun smallPillButton(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        setTextSp(17f)
        setTextColor(QonectaTheme.text)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = RippleDrawable(
            ColorStateList.valueOf(0x3300E5C7),
            pillDrawable(QonectaTheme.surfaceTop, QonectaTheme.outline),
            null,
        )
        setOnClickListener { onClick() }
    }

    private fun actionButton(text: String, primary: Boolean, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        setTextSp(19f)
        setTextColor(if (primary) QonectaTheme.onAccent else QonectaTheme.accent)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(12), dp(24), dp(12))
        val bg = if (primary) {
            QonectaTheme.accentGradientPill(dp(18).toFloat())
        } else {
            roundedDrawable(QonectaTheme.surfaceTop, dp(18), dp(2), QonectaTheme.accent)
        }
        background = RippleDrawable(ColorStateList.valueOf(0x3300E5C7), bg, null)
        setOnClickListener { onClick() }
    }

    private fun pillDrawable(fillColor: Int, strokeColor: Int): GradientDrawable =
        roundedDrawable(fillColor, dp(999), dp(2), strokeColor)

    private fun roundedDrawable(fillColor: Int, radiusPx: Int, strokePx: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radiusPx.toFloat()
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    private fun dp(v: Int): Int = (v * density).roundToInt()

    // =========================================================================
    // VISTAS CANVAS PARA LA PESTAÑA VIAJES (Mini-Mapa, 14 Días, Mapa Detallado y Perfil de Velocidad)
    // =========================================================================

    private class TripMiniMapCanvasView(
        context: Context,
        private val track: List<DoubleArray>,
    ) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0E1621.toInt()
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PanelFormat.EDGE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x168296A9
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.withAlpha(PanelFormat.ACCENT, 0x38)
            style = Paint.Style.STROKE
            strokeWidth = 11f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PanelFormat.ACCENT
            style = Paint.Style.STROKE
            strokeWidth = 4.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val rect = RectF()
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            rect.set(1f, 1f, w - 1f, h - 1f)
            canvas.drawRoundRect(rect, 18f, 18f, bgPaint)
            canvas.drawRoundRect(rect, 18f, 18f, borderPaint)

            // Cuadrícula sutil de mapa
            for (i in 1..3) {
                val gx = w * (i / 4f)
                val gy = h * (i / 4f)
                canvas.drawLine(gx, 4f, gx, h - 4f, gridPaint)
                canvas.drawLine(4f, gy, w - 4f, gy, gridPaint)
            }

            if (track.size < 2) return
            var minLa = 90.0
            var maxLa = -90.0
            var minLo = 180.0
            var maxLo = -180.0
            for (pt in track) {
                minLa = min(minLa, pt[0])
                maxLa = max(maxLa, pt[0])
                minLo = min(minLo, pt[1])
                maxLo = max(maxLo, pt[1])
            }
            val cosLat = cos(Math.toRadians((minLa + maxLa) / 2.0))
            val spanX = max(1e-5, (maxLo - minLo) * cosLat)
            val spanY = max(1e-5, maxLa - minLa)
            val pad = 16f
            val scale = min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY)
            val offX = ((w - spanX * scale) / 2.0).toFloat()
            val offY = ((h - spanY * scale) / 2.0).toFloat()

            path.rewind()
            var sx = 0f
            var sy = 0f
            var ex = 0f
            var ey = 0f
            val stopPoints = mutableListOf<Pair<Float, Float>>()
            for (i in track.indices) {
                val pt = track[i]
                val x = (offX + (pt[1] - minLo) * cosLat * scale).toFloat()
                val y = (offY + (spanY - (pt[0] - minLa)) * scale).toFloat()
                if (i == 0) {
                    path.moveTo(x, y)
                    sx = x
                    sy = y
                } else {
                    path.lineTo(x, y)
                }
                if (i in 1 until track.lastIndex && pt.size >= 4 && pt[3] <= 1.0) {
                    stopPoints.add(x to y)
                }
                ex = x
                ey = y
            }
            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, routePaint)

            // Paradas intermedias (ámbar)
            dotPaint.color = 0xFFFFB547.toInt()
            for ((px, py) in stopPoints) {
                canvas.drawCircle(px, py, 5f, dotPaint)
            }
            // Punto de salida (blanco)
            dotPaint.color = 0xFFEAF2F7.toInt()
            canvas.drawCircle(sx, sy, 5.5f, dotPaint)
            // Punto de llegada (verde Qonecta+)
            dotPaint.color = 0xFF4CFF9F.toInt()
            canvas.drawCircle(ex, ey, 6.5f, dotPaint)
        }
    }

    private class DailyKmBarChartCanvasView(
        context: Context,
        private val trips: List<LeapVehicleDataSource.TripRecord>,
    ) : View(context) {
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 10f || h <= 10f) return

            val days = 14
            val daily = DoubleArray(days)
            val dayLetters = Array(days) { "L" }
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
            cal.set(java.util.Calendar.MINUTE, 59)
            cal.set(java.util.Calendar.SECOND, 59)
            cal.set(java.util.Calendar.MILLISECOND, 999)
            val endOfTodayMs = cal.timeInMillis
            val dayMs = 24L * 3600_000L
            val dowMap = mapOf(
                java.util.Calendar.MONDAY to "L",
                java.util.Calendar.TUESDAY to "M",
                java.util.Calendar.WEDNESDAY to "X",
                java.util.Calendar.THURSDAY to "J",
                java.util.Calendar.FRIDAY to "V",
                java.util.Calendar.SATURDAY to "S",
                java.util.Calendar.SUNDAY to "D",
            )
            for (i in 0 until days) {
                val daysBack = (days - 1) - i
                val dayEndMs = endOfTodayMs - daysBack * dayMs
                val dayStartMs = dayEndMs - dayMs + 1L
                daily[i] = trips.filter { it.startTimeMs in dayStartMs..dayEndMs }.sumOf { it.distanceKm }
                cal.timeInMillis = dayStartMs + 12L * 3600_000L
                dayLetters[i] = dowMap[cal.get(java.util.Calendar.DAY_OF_WEEK)] ?: "D"
            }

            val maxKm = max(20.0, daily.maxOrNull() ?: 20.0)
            val maxIdx = daily.indices.maxByOrNull { daily[it] } ?: (days - 1)

            val topPad = 24f
            val botPad = 26f
            val chartH = max(20f, h - topPad - botPad)
            val bw = w / days

            for (i in 0 until days) {
                val km = daily[i]
                val barH = if (km <= 0.5) 6f else max(10f, ((km / maxKm) * chartH).toFloat())
                val left = i * bw + bw * 0.18f
                val right = (i + 1) * bw - bw * 0.18f
                rect.set(left, h - botPad - barH, right, h - botPad)

                barPaint.color = when {
                    km <= 0.5 -> PanelFormat.SURFACE
                    i >= days - 7 -> PanelFormat.ACCENT
                    else -> 0xFF4B5B6C.toInt()
                }
                canvas.drawRoundRect(rect, 6f, 6f, barPaint)

                if (i == maxIdx && km > 0) {
                    textPaint.textSize = 18f
                    textPaint.color = PanelFormat.TEXT
                    canvas.drawText("${km.roundToInt()}", rect.centerX(), rect.top - 5f, textPaint)
                }

                textPaint.textSize = 17f
                textPaint.color = if (i == days - 1) PanelFormat.ACCENT else PanelFormat.DIM
                canvas.drawText(dayLetters[i], rect.centerX(), h - 4f, textPaint)
            }
        }
    }

    private class TripDetailRouteMapCanvasView(
        context: Context,
        private val trip: LeapVehicleDataSource.TripRecord,
    ) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0C141E.toInt()
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PanelFormat.OUTLINE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x1A8296A9
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
        }
        private val roadBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x22385068
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.withAlpha(PanelFormat.ACCENT, 0x38)
            style = Paint.Style.STROKE
            strokeWidth = 20f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PanelFormat.ACCENT
            style = Paint.Style.STROKE
            strokeWidth = 7.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fastSegPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF6D00.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 8.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textSize = 18f
        }
        private val rect = RectF()
        private val pillRect = RectF()
        private val path = Path()
        private val fastPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            rect.set(1f, 1f, w - 1f, h - 1f)
            canvas.drawRoundRect(rect, 20f, 20f, bgPaint)

            // Cuadrícula cartográfica y anillos de referencia
            for (i in 1..7) {
                val gx = w * (i / 8f)
                canvas.drawLine(gx, 2f, gx, h - 2f, gridPaint)
            }
            for (i in 1..5) {
                val gy = h * (i / 6f)
                canvas.drawLine(2f, gy, w - 2f, gy, gridPaint)
            }
            // Vías secundarias estilizadas de fondo
            canvas.drawLine(w * 0.08f, h * 0.78f, w * 0.92f, h * 0.22f, roadBgPaint)
            canvas.drawLine(w * 0.18f, h * 0.15f, w * 0.84f, h * 0.85f, roadBgPaint)

            val pts = if (trip.track.size >= 2) trip.track else LeapVehicleDataSource.getLiveTrack()
            if (pts.size >= 2) {
                var minLa = 90.0
                var maxLa = -90.0
                var minLo = 180.0
                var maxLo = -180.0
                for (q in pts) {
                    minLa = min(minLa, q[0])
                    maxLa = max(maxLa, q[0])
                    minLo = min(minLo, q[1])
                    maxLo = max(maxLo, q[1])
                }
                val cosLat = cos(Math.toRadians((minLa + maxLa) / 2.0))
                val spanX = max(1e-4, (maxLo - minLo) * cosLat)
                val spanY = max(1e-4, maxLa - minLa)
                val padX = 64f
                val padY = 48f
                val scale = min((w - 2 * padX) / spanX, (h - 2 * padY) / spanY)
                val offX = ((w - spanX * scale) / 2.0).toFloat()
                val offY = ((h - spanY * scale) / 2.0).toFloat()

                path.rewind()
                fastPath.rewind()
                val screenPts = ArrayList<FloatArray>(pts.size)
                var maxSpdIdx = 0
                var maxSpdVal = -1.0
                val stopIndices = mutableListOf<Int>()

                for (i in pts.indices) {
                    val q = pts[i]
                    val x = (offX + (q[1] - minLo) * cosLat * scale).toFloat()
                    val y = (offY + (spanY - (q[0] - minLa)) * scale).toFloat()
                    val spd = if (q.size >= 4) q[3] else 70.0
                    screenPts.add(floatArrayOf(x, y, spd.toFloat()))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    if (spd > maxSpdVal && i in 1 until pts.lastIndex) {
                        maxSpdVal = spd
                        maxSpdIdx = i
                    }
                    if (i in 1 until pts.lastIndex && q.size >= 4 && q[3] <= 1.0) {
                        stopIndices.add(i)
                    }
                }

                canvas.drawPath(path, glowPaint)
                canvas.drawPath(path, routePaint)

                // Tramo de velocidad máxima resaltado en naranja Sport
                if (maxSpdIdx > 0 && maxSpdIdx < screenPts.size) {
                    val p0 = screenPts[maxSpdIdx - 1]
                    val p1 = screenPts[maxSpdIdx]
                    fastPath.moveTo(p0[0], p0[1])
                    fastPath.lineTo(p1[0], p1[1])
                    if (maxSpdIdx + 1 < screenPts.size) {
                        val p2 = screenPts[maxSpdIdx + 1]
                        fastPath.lineTo(p2[0], p2[1])
                    }
                    canvas.drawPath(fastPath, fastSegPaint)
                }

                // Dibujar paradas intermedias (P1, P2...)
                stopIndices.forEachIndexed { sIdx, ptIdx ->
                    val sp = screenPts[ptIdx]
                    fillPaint.color = 0xFFFFB547.toInt()
                    canvas.drawCircle(sp[0], sp[1], 9f, fillPaint)
                    drawMapBadge(canvas, sp[0], sp[1] - 18f, "P${sIdx + 1} · Parada", 0xFFFFB547.toInt(), 0xFF1A1203.toInt())
                }

                // Etiqueta de Pico de Velocidad y Aceleración en el mapa
                val pk = screenPts[maxSpdIdx]
                fillPaint.color = 0xFFFF6D00.toInt()
                canvas.drawCircle(pk[0], pk[1], 8.5f, fillPaint)
                drawMapBadge(
                    canvas,
                    pk[0],
                    pk[1] - 20f,
                    String.format(Locale.getDefault(), "🚀 Máx %.0f km/h · %.2f G", trip.maxSpeedKmh, trip.maxAccelG),
                    0xFFFF6D00.toInt(),
                    Color.WHITE,
                )

                // Punto de Salida
                val startPt = screenPts.first()
                fillPaint.color = 0xFF0A0E14.toInt()
                canvas.drawCircle(startPt[0], startPt[1], 11f, fillPaint)
                fillPaint.color = 0xFFEAF2F7.toInt()
                canvas.drawCircle(startPt[0], startPt[1], 8f, fillPaint)
                drawMapBadge(
                    canvas,
                    startPt[0],
                    startPt[1] + 28f,
                    "Salida (${trip.startSoc.roundToInt()}%)",
                    0xFF1E2B3A.toInt(),
                    0xFFEAF2F7.toInt(),
                )

                // Punto de Llegada
                val endPt = screenPts.last()
                fillPaint.color = 0xFF0A0E14.toInt()
                canvas.drawCircle(endPt[0], endPt[1], 12f, fillPaint)
                fillPaint.color = 0xFF4CFF9F.toInt()
                canvas.drawCircle(endPt[0], endPt[1], 9f, fillPaint)
                drawMapBadge(
                    canvas,
                    endPt[0],
                    endPt[1] + 28f,
                    "🏁 Llegada (${trip.endSoc.roundToInt()}%)",
                    0xFF00E5C7.toInt(),
                    0xFF061614.toInt(),
                )
            }

            // Brújula y atribución Qonecta+ OSM en las esquinas
            fillPaint.color = 0xCC131E2B.toInt()
            pillRect.set(14f, 14f, 195f, 44f)
            canvas.drawRoundRect(pillRect, 15f, 15f, fillPaint)
            labelPaint.color = PanelFormat.DIM
            labelPaint.textSize = 15f
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("🧭 Norte arriba · GPS C10", 26f, 34f, labelPaint)

            canvas.drawRoundRect(rect, 20f, 20f, borderPaint)
        }

        private fun drawMapBadge(canvas: Canvas, cx: Float, cy: Float, text: String, bgColor: Int, txtColor: Int) {
            labelPaint.textSize = 15f
            labelPaint.textAlign = Paint.Align.CENTER
            val tw = labelPaint.measureText(text)
            val bw = tw + 22f
            val bh = 28f
            val clampedX = cx.coerceIn(bw / 2f + 8f, width - bw / 2f - 8f)
            val clampedY = cy.coerceIn(bh / 2f + 8f, height - bh / 2f - 8f)
            pillRect.set(clampedX - bw / 2f, clampedY - bh / 2f, clampedX + bw / 2f, clampedY + bh / 2f)
            fillPaint.color = bgColor
            canvas.drawRoundRect(pillRect, 14f, 14f, fillPaint)
            labelPaint.color = txtColor
            canvas.drawText(text, clampedX, clampedY + 5f, labelPaint)
        }
    }

    private class TripSpeedProfileCanvasView(
        context: Context,
        private val trip: LeapVehicleDataSource.TripRecord,
    ) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PanelFormat.SURFACE
            style = Paint.Style.FILL
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PanelFormat.ACCENT
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = QonectaTheme.withAlpha(PanelFormat.ACCENT, 0x28)
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PanelFormat.DIM
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val rect = RectF()
        private val path = Path()
        private val areaPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, 14f, 14f, bgPaint)

            canvas.drawText(
                String.format(Locale.getDefault(), "PERFIL DE VELOCIDAD · Media %.0f km/h · Pico %.0f km/h · Acel. %.2f G", trip.avgSpeedKmh, trip.maxSpeedKmh, trip.maxAccelG),
                14f,
                20f,
                textPaint,
            )

            val pts = trip.track
            if (pts.size < 2) return
            val maxSpd = max(120.0, trip.maxSpeedKmh)
            val left = 14f
            val right = w - 14f
            val top = 26f
            val bot = h - 8f

            path.rewind()
            areaPath.rewind()
            for (i in pts.indices) {
                val x = left + (right - left) * (i.toFloat() / (pts.size - 1))
                val spd = if (pts[i].size >= 4) pts[i][3] else trip.avgSpeedKmh
                val y = (bot - ((spd / maxSpd) * (bot - top))).toFloat().coerceIn(top, bot)
                if (i == 0) {
                    path.moveTo(x, y)
                    areaPath.moveTo(x, bot)
                    areaPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    areaPath.lineTo(x, y)
                }
                if (i == pts.lastIndex) {
                    areaPath.lineTo(x, bot)
                    areaPath.close()
                }
            }
            canvas.drawPath(areaPath, fillPaint)
            canvas.drawPath(path, linePaint)
        }
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val PREFS_NAME = "qonecta_unit_route_prefs"
        private const val KEY_CHARGER_MIN_KW = "charger_min_kw"
        private const val KEY_CHARGER_NETWORKS = "charger_networks"
        const val C10_BATTERY_KWH = 69.9

        // Redes de cargadores con sus distintivos de color de Qonecta+ (ChargerFilter.BADGES)
        val NETWORK_SPECS = listOf(
            NetworkSpec("zunder", "Zunder", "Z", 0xFF00B37E.toInt()),
            NetworkSpec("iberdrola", "Iberdrola", "IB", 0xFF5CB615.toInt()),
            NetworkSpec("ionity", "Ionity", "IO", 0xFF2E2A6E.toInt()),
            NetworkSpec("tesla", "Tesla", "T", 0xFFE31937.toInt()),
            NetworkSpec("endesa", "Endesa X", "EX", 0xFF0075BE.toInt()),
            NetworkSpec("wenea", "Wenea", "W", 0xFF00A3E0.toInt()),
            NetworkSpec("repsol", "Repsol", "R", 0xFFFF8200.toInt()),
            NetworkSpec("moeve", "Moeve / Cepsa", "M", 0xFF00857C.toInt()),
            NetworkSpec("electra", "Electra", "E", 0xFF14D3A0.toInt(), darkText = true),
            NetworkSpec("bp", "bp pulse", "bp", 0xFF009900.toInt()),
        )

        /**
         * Escala determinista por píxeles reales para la pantalla 2560x1440 del Leapmotor C10:
         * en el coche real Android Auto (AapProjectionActivity) reporta 160 dpi (density = 1.0),
         * mientras que en inicio o emulador varía (224-320 dpi). Usando el lado corto real (1440 px)
         * garantizamos exactamente density = 1.60 (256 dpi equivalentes) en cualquier pantalla 2K.
         */
        fun c10Density(context: Context): Float {
            QonectaTheme.ensureLoaded(context)
            val dm = context.resources.displayMetrics
            val shortSide = min(dm.widthPixels, dm.heightPixels)
            val base = if (shortSide >= 1000) (shortSide / 900f).coerceIn(1.25f, 2.2f) else dm.density
            return base * QonectaTheme.uiScaleFactor
        }

        /**
         * Activa o desactiva el modo Pantalla Completa 100% (estilo host.setFullscreen(boolean) en Qonecta+)
         * buscando el contenedor [ExtendedHubView] ancestro de [view].
         */
        fun setFullscreenFor(view: View, fullscreen: Boolean, showDefaultExitPill: Boolean = false) {
            var p = view.parent
            while (p != null) {
                if (p is ExtendedHubView) {
                    p.setImmersiveFullscreen(fullscreen, showDefaultExitPill)
                    return
                }
                p = p.parent
            }
        }

        /**
         * Construye el panel interactivo de Android Auto · Google Maps C10 en vivo (53% del ancho en modo
         * Pantalla Partida, respetando el mínimo de 800 dp / relación 1.15× de Android Auto para que el
         * mapa se mantenga siempre interactivo junto a TV, Karaoke, Instrumentos o Estado).
         */
        fun createLiveAaMapCompanionPane(
            context: Context,
            onQuickTabSelect: ((Tab) -> Unit)? = null,
        ): View {
            val d = c10Density(context)
            fun dpStatic(v: Int): Int = (v * d).roundToInt()

            val root = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setBackgroundColor(0xFF0B1017.toInt())
            }

            // 1. Barra lateral izquierda de Android Auto (Dock Rail oficial AA Coolwalk)
            val aaDock = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dpStatic(6), dpStatic(12), dpStatic(6), dpStatic(12))
                background = GradientDrawable().apply {
                    setColor(0xFF080C12.toInt())
                    setStroke(dpStatic(1), 0xFF1E2938.toInt())
                }
            }

            val clockTv = TextView(context).apply {
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * d)
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            aaDock.addView(clockTv, LayoutParams(MATCH, WRAP))

            val sigTv = TextView(context).apply {
                text = "5G ▂▄▆█"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 10.5f * d)
                setTextColor(0xFF93A8BE.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dpStatic(2), 0, dpStatic(12))
            }
            aaDock.addView(sigTv, LayoutParams(MATCH, WRAP))

            val mapCanvasRef = arrayOfNulls<AaVectorMapCanvasView>(1)

            fun dockIcon(icon: String, bgCol: Int, active: Boolean, onClick: () -> Unit): TextView {
                return TextView(context).apply {
                    text = icon
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * d)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(bgCol)
                        if (active) {
                            setStroke(dpStatic(2), 0xFF00E5C7.toInt())
                        }
                    }
                    setOnClickListener { onClick() }
                }
            }

            aaDock.addView(
                dockIcon("🗺️", 0xFF1A3650.toInt(), active = true) {
                    mapCanvasRef[0]?.recenterMap()
                },
                LayoutParams(dpStatic(44), dpStatic(44)).apply { bottomMargin = dpStatic(10) },
            )
            aaDock.addView(
                dockIcon("📻", 0xFF143D29.toInt(), active = false) {
                    onQuickTabSelect?.invoke(Tab.RADIO)
                },
                LayoutParams(dpStatic(44), dpStatic(44)).apply { bottomMargin = dpStatic(10) },
            )
            aaDock.addView(
                dockIcon("📺", 0xFF3A1D28.toInt(), active = false) {
                    onQuickTabSelect?.invoke(Tab.TV)
                },
                LayoutParams(dpStatic(44), dpStatic(44)).apply { bottomMargin = dpStatic(10) },
            )
            aaDock.addView(
                dockIcon("⚡", 0xFF1E2D3D.toInt(), active = false) {
                    onQuickTabSelect?.invoke(Tab.ROUTE)
                },
                LayoutParams(dpStatic(44), dpStatic(44)).apply { bottomMargin = dpStatic(10) },
            )

            aaDock.addView(View(context), LayoutParams(MATCH, 0, 1f))

            aaDock.addView(
                dockIcon("🎙", 0xFF18222F.toInt(), active = false) {
                    Toast.makeText(context, "Asistente de voz Android Auto · C10", Toast.LENGTH_SHORT).show()
                },
                LayoutParams(dpStatic(40), dpStatic(40)).apply { bottomMargin = dpStatic(8) },
            )
            aaDock.addView(
                dockIcon("◫", 0xFF18222F.toInt(), active = true) {
                    mapCanvasRef[0]?.toggleChargersOverlay()
                },
                LayoutParams(dpStatic(40), dpStatic(40)),
            )

            root.addView(aaDock, LayoutParams(dpStatic(62), MATCH))

            // 2. Contenedor principal del Mapa de Navegación Android Auto Coolwalk
            val mapFrame = FrameLayout(context)
            val vectorMap = AaVectorMapCanvasView(context)
            mapCanvasRef[0] = vectorMap
            mapFrame.addView(vectorMap, FrameLayout.LayoutParams(MATCH, MATCH))

            // Tarjeta superior izquierda de Maniobra Google Maps (#0D4A35)
            val navCard = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dpStatic(16), dpStatic(12), dpStatic(16), dpStatic(12))
                background = GradientDrawable().apply {
                    cornerRadius = dpStatic(18).toFloat()
                    setColor(0xEE0D4A35.toInt())
                    setStroke(dpStatic(2), 0xFF2EE59D.toInt())
                }
            }
            val turnTopRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            turnTopRow.addView(TextView(context).apply {
                text = "↱"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 34f * d)
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                setPadding(0, 0, dpStatic(12), 0)
            })
            val turnTextCol = LinearLayout(context).apply {
                orientation = VERTICAL
            }
            turnTextCol.addView(TextView(context).apply {
                text = "450 m · Salida 19"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 21f * d)
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            })
            turnTextCol.addView(TextView(context).apply {
                text = "A-6 · Las Rozas / Coruña"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * d)
                setTextColor(0xFFB8F5DC.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            turnTopRow.addView(turnTextCol)
            navCard.addView(turnTopRow)

            // Indicador de carriles en la parte inferior de la tarjeta verde
            val lanesRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dpStatic(8), dpStatic(5), dpStatic(8), dpStatic(3))
                background = GradientDrawable().apply {
                    cornerRadius = dpStatic(10).toFloat()
                    setColor(0xFF073122.toInt())
                }
            }
            listOf("↑" to false, "↑" to false, "↑↱" to true, "↱" to true).forEachIndexed { idx, (arrow, highlight) ->
                lanesRow.addView(
                    TextView(context).apply {
                        text = arrow
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * d)
                        setTextColor(if (highlight) 0xFF4CFF9F.toInt() else 0x77FFFFFF)
                        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                        setPadding(dpStatic(8), dpStatic(1), dpStatic(8), dpStatic(1))
                    },
                    LayoutParams(WRAP, WRAP).apply { if (idx > 0) leftMargin = dpStatic(4) },
                )
            }
            navCard.addView(lanesRow, LayoutParams(MATCH, WRAP).apply { topMargin = dpStatic(8) })

            mapFrame.addView(
                navCard,
                FrameLayout.LayoutParams(dpStatic(285), WRAP, Gravity.TOP or Gravity.START).apply {
                    topMargin = dpStatic(14)
                    leftMargin = dpStatic(14)
                },
            )

            // Controles flotantes del mapa arriba a la derecha (Zoom +/-, Cargadores EV, Centrar)
            val rightMapTools = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.END
            }
            fun mapToolPill(txt: String, onClick: () -> Unit): TextView = TextView(context).apply {
                text = txt
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * d)
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dpStatic(12), dpStatic(8), dpStatic(12), dpStatic(8))
                background = GradientDrawable().apply {
                    cornerRadius = dpStatic(14).toFloat()
                    setColor(0xDD131C27.toInt())
                    setStroke(dpStatic(1), 0xFF334960.toInt())
                }
                setOnClickListener { onClick() }
            }
            rightMapTools.addView(
                mapToolPill("⚡ Cargadores CC") { vectorMap.toggleChargersOverlay() },
                LayoutParams(WRAP, WRAP).apply { bottomMargin = dpStatic(8) },
            )
            rightMapTools.addView(
                mapToolPill("＋ Acercar") { vectorMap.adjustZoom(+0.2f) },
                LayoutParams(WRAP, WRAP).apply { bottomMargin = dpStatic(8) },
            )
            rightMapTools.addView(
                mapToolPill("－ Alejar") { vectorMap.adjustZoom(-0.2f) },
                LayoutParams(WRAP, WRAP).apply { bottomMargin = dpStatic(8) },
            )
            rightMapTools.addView(
                mapToolPill("🧭 Centrar C10") { vectorMap.recenterMap() },
                LayoutParams(WRAP, WRAP),
            )
            mapFrame.addView(
                rightMapTools,
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
                    topMargin = dpStatic(14)
                    rightMargin = dpStatic(14)
                },
            )

            // Barra inferior de ETA + SoC de llegada + Señal de límite de velocidad europeo (120)
            val bottomHudRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // Señal de límite 120 + Velocímetro GPS
            val speedSignBadge = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpStatic(10), dpStatic(8), dpStatic(14), dpStatic(8))
                background = GradientDrawable().apply {
                    cornerRadius = dpStatic(20).toFloat()
                    setColor(0xEE101822.toInt())
                    setStroke(dpStatic(1), 0xFF2D3F52.toInt())
                }
            }
            val limitCircle = TextView(context).apply {
                text = "120"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * d)
                setTextColor(Color.BLACK)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                    setStroke(dpStatic(4), 0xFFE53935.toInt())
                }
            }
            speedSignBadge.addView(limitCircle, LayoutParams(dpStatic(44), dpStatic(44)).apply { rightMargin = dpStatic(10) })

            val snapNow = LeapVehicleDataSource.snapshot()
            val curSpd = snapNow.effectiveSpeedKmh.roundToInt().takeIf { it > 0 } ?: 98
            val spdCol = LinearLayout(context).apply { orientation = VERTICAL }
            spdCol.addView(TextView(context).apply {
                text = "$curSpd"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f * d)
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            })
            spdCol.addView(TextView(context).apply {
                text = "km/h GPS"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * d)
                setTextColor(0xFF93A8BE.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            speedSignBadge.addView(spdCol)
            bottomHudRow.addView(speedSignBadge, LayoutParams(WRAP, WRAP).apply { rightMargin = dpStatic(10) })

            // Tarjeta ETA Google Maps + Batería de llegada C10
            val etaCard = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpStatic(16), dpStatic(10), dpStatic(16), dpStatic(10))
                background = GradientDrawable().apply {
                    cornerRadius = dpStatic(20).toFloat()
                    setColor(0xEE101822.toInt())
                    setStroke(dpStatic(1), 0xFF2D3F52.toInt())
                }
            }
            val etaCol = LinearLayout(context).apply { orientation = VERTICAL }
            val socNow = if (!snapNow.socPct.isNaN()) snapNow.socPct.roundToInt() else 78
            val arrSoc = (socNow - 14).coerceAtLeast(18)
            etaCol.addView(TextView(context).apply {
                text = "24 min  ·  31,4 km  ·  Llegada 18:15"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 16.5f * d)
                setTextColor(0xFF4CFF9F.toInt())
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            })
            etaCol.addView(TextView(context).apply {
                text = "⚡ Leapmotor C10 · Llegada con $arrSoc% SoC · Ruta rápida sin peajes"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 12.5f * d)
                setTextColor(0xFFD5E3F0.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            etaCard.addView(etaCol, LayoutParams(0, WRAP, 1f))

            bottomHudRow.addView(etaCard, LayoutParams(0, WRAP, 1f))

            mapFrame.addView(
                bottomHudRow,
                FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
                    leftMargin = dpStatic(14)
                    rightMargin = dpStatic(14)
                    bottomMargin = dpStatic(14)
                },
            )

            root.addView(mapFrame, LayoutParams(0, MATCH, 1f))
            return root
        }

        /**
         * Lienzo vectorial 3D de navegación Android Auto · Google Maps C10 con ruta activa,
         * tráfico, cargadores ultrarrápidos CC y cursor GPS del Leapmotor C10.
         */
        private class AaVectorMapCanvasView(context: Context) : View(context) {
            private var zoomFactor = 1.0f
            private var panX = 0f
            private var panY = 0f
            private var showChargers = true
            private var lastTouchX = 0f
            private var lastTouchY = 0f

            private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111923.toInt() }
            private val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B2236.toInt() }
            private val parkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF112622.toInt() }
            private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF18222F.toInt() }
            private val minorRoadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF233243.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 6f
                strokeCap = Paint.Cap.ROUND
            }
            private val majorRoadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF2E4258.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 14f
                strokeCap = Paint.Cap.ROUND
            }
            private val highwayCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1B2838.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 26f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val routeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x5500E5C7
                style = Paint.Style.STROKE
                strokeWidth = 28f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val routeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF00B8FF.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 15f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF9EB4CC.toInt()
                textSize = 20f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val path = Path()

            init {
                isClickable = true
                setOnTouchListener { _, ev ->
                    when (ev.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            lastTouchX = ev.x
                            lastTouchY = ev.y
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            panX = (panX + (ev.x - lastTouchX)).coerceIn(-280f, 280f)
                            panY = (panY + (ev.y - lastTouchY)).coerceIn(-240f, 240f)
                            lastTouchX = ev.x
                            lastTouchY = ev.y
                            invalidate()
                        }
                    }
                    true
                }
            }

            fun adjustZoom(delta: Float) {
                zoomFactor = (zoomFactor + delta).coerceIn(0.75f, 1.65f)
                invalidate()
            }

            fun recenterMap() {
                panX = 0f
                panY = 0f
                zoomFactor = 1.0f
                invalidate()
            }

            fun toggleChargersOverlay() {
                showChargers = !showChargers
                invalidate()
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                if (w <= 0f || h <= 0f) return

                canvas.drawRect(0f, 0f, w, h, landPaint)

                canvas.save()
                canvas.translate(w * 0.5f + panX, h * 0.58f + panY)
                canvas.scale(zoomFactor, zoomFactor)
                canvas.translate(-w * 0.5f, -h * 0.58f)

                // Río / Embalse y zonas verdes
                path.reset()
                path.moveTo(0f, h * 0.22f)
                path.cubicTo(w * 0.32f, h * 0.28f, w * 0.58f, h * 0.12f, w, h * 0.20f)
                path.lineTo(w, h * 0.31f)
                path.cubicTo(w * 0.56f, h * 0.24f, w * 0.30f, h * 0.39f, 0f, h * 0.33f)
                path.close()
                canvas.drawPath(path, waterPaint)

                canvas.drawRoundRect(RectF(w * 0.06f, h * 0.42f, w * 0.34f, h * 0.72f), 28f, 28f, parkPaint)
                canvas.drawRoundRect(RectF(w * 0.66f, h * 0.34f, w * 0.92f, h * 0.62f), 28f, 28f, parkPaint)

                // Manzanas urbanas y cuadrícula secundaria
                for (i in 1..7) {
                    val y = h * (0.12f * i)
                    canvas.drawLine(0f, y, w, y - h * 0.06f, minorRoadPaint)
                }
                for (i in 1..7) {
                    val x = w * (0.13f * i)
                    canvas.drawLine(x, 0f, x - w * 0.08f, h, minorRoadPaint)
                }

                // Arterias principales M-40 / M-50
                path.reset()
                path.moveTo(0f, h * 0.66f)
                path.quadTo(w * 0.48f, h * 0.52f, w, h * 0.60f)
                canvas.drawPath(path, majorRoadPaint)

                path.reset()
                path.moveTo(w * 0.18f, 0f)
                path.quadTo(w * 0.42f, h * 0.45f, w * 0.24f, h)
                canvas.drawPath(path, majorRoadPaint)

                // Ruta Activa A-6 (con borde, resplandor cian y línea azul Google Maps)
                path.reset()
                path.moveTo(w * 0.48f, h * 0.88f)
                path.cubicTo(w * 0.48f, h * 0.62f, w * 0.45f, h * 0.45f, w * 0.62f, h * 0.28f)
                path.cubicTo(w * 0.72f, h * 0.18f, w * 0.78f, h * 0.12f, w * 0.84f, h * 0.05f)
                canvas.drawPath(path, highwayCasingPaint)
                canvas.drawPath(path, routeGlowPaint)
                canvas.drawPath(path, routeLinePaint)

                // Etiquetas de autopistas y zonas
                canvas.drawText("A-6 · Autovía del Noroeste", w * 0.55f, h * 0.44f, labelPaint)
                canvas.drawText("M-40 · Anillo distribuidor", w * 0.10f, h * 0.62f, labelPaint)
                canvas.drawText("Casa de Campo / Monte del Pardo", w * 0.08f, h * 0.52f, labelPaint)

                // Cargadores Rápidos CC en la ruta
                if (showChargers) {
                    drawChargerPin(canvas, w * 0.44f, h * 0.52f, "⚡ Zunder 300 kW · 6/8 libres", 0xFF00B37E.toInt())
                    drawChargerPin(canvas, w * 0.66f, h * 0.24f, "⚡ Ionity 350 kW · 4/6 libres", 0xFF00E5C7.toInt())
                }

                // Cursor GPS del Leapmotor C10 (Puck luminoso con flecha de navegación)
                val carX = w * 0.48f
                val carY = h * 0.70f
                badgePaint.style = Paint.Style.FILL
                badgePaint.color = 0x3300E5C7
                canvas.drawCircle(carX, carY, 44f, badgePaint)
                badgePaint.color = 0xFF0E1722.toInt()
                canvas.drawCircle(carX, carY, 26f, badgePaint)
                badgePaint.style = Paint.Style.STROKE
                badgePaint.strokeWidth = 4f
                badgePaint.color = 0xFF00E5C7.toInt()
                canvas.drawCircle(carX, carY, 26f, badgePaint)

                // Flecha de rumbo dentro del puck
                badgePaint.style = Paint.Style.FILL
                badgePaint.color = 0xFF00E5C7.toInt()
                path.reset()
                path.moveTo(carX, carY - 15f)
                path.lineTo(carX + 12f, carY + 12f)
                path.lineTo(carX, carY + 6f)
                path.lineTo(carX - 12f, carY + 12f)
                path.close()
                canvas.drawPath(path, badgePaint)

                canvas.restore()
            }

            private fun drawChargerPin(canvas: Canvas, x: Float, y: Float, text: String, color: Int) {
                val tw = labelPaint.measureText(text)
                val rect = RectF(x - 14f, y - 22f, x + tw + 18f, y + 14f)
                badgePaint.style = Paint.Style.FILL
                badgePaint.color = 0xEE101924.toInt()
                canvas.drawRoundRect(rect, 18f, 18f, badgePaint)
                badgePaint.style = Paint.Style.STROKE
                badgePaint.strokeWidth = 2.5f
                badgePaint.color = color
                canvas.drawRoundRect(rect, 18f, 18f, badgePaint)
                val prevCol = labelPaint.color
                labelPaint.color = Color.WHITE
                canvas.drawText(text, x + 2f, y + 3f, labelPaint)
                labelPaint.color = prevCol
            }
        }

        /**
         * Abre el centro Extendido de Qonecta+ a PANTALLA COMPLETA REAL (100% edge-to-edge 2560x1440)
         * o en modo PANTALLA PARTIDA (Mapa Android Auto 53% + App Qonecta+ 47%).
         */
        fun showDialog(
            context: Context,
            initialTab: Tab = Tab.ROUTE,
            initialSplitScreen: Boolean = false,
        ): Dialog {
            val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val hub = ExtendedHubView(
                context = context,
                onClose = { dialog.dismiss() },
                hostedInsideProjection = false,
                initialSplitScreen = initialSplitScreen,
            )
            hub.selectTab(initialTab)
            dialog.setContentView(hub, ViewGroup.LayoutParams(MATCH, MATCH))
            dialog.window?.let { win ->
                win.setBackgroundDrawable(ColorDrawable(QonectaTheme.bg))
                win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                win.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
                )
                win.setLayout(MATCH, MATCH)
                @Suppress("DEPRECATION")
                win.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
            }
            dialog.show()
            return dialog
        }
    }
}
