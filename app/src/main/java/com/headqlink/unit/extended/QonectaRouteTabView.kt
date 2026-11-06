package com.headqlink.unit.extended

import android.app.AlertDialog
import android.content.Context
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
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.headqlink.unit.bridge.NavPoint
import com.headqlink.unit.bridgehost.CompanionBridgeHost
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pestaña «Ruta» de Qonecta+ («RouteTab.java») integrada en Qonecta Unit con datos 100 % reales
 * (OSRM, Open-Meteo Elevation & Forecast, OpenStreetMap Overpass + DGT España y `ChargePlanner`),
 * y envío directo de destino + paradas de carga a Google Maps del móvil mediante `CompanionBridgeHost`.
 */
object QonectaRouteTabState {
    var currentPlan: QonectaRoutePlanner.Plan? = null
    var lastDestinationPlace: QonectaRoutePlanner.Place? = null
    var viaPlaces: MutableList<QonectaRoutePlanner.Place> = mutableListOf()
    var roundTrip: Boolean = false
    var routeLoading: Boolean = false
    var statusMessage: String = ""
    var socAdjustDelta: Int = 0
    val pinnedChargerKeys: LinkedHashSet<String> = LinkedHashSet()
    val favoriteChargerKeys: LinkedHashSet<String> = LinkedHashSet()
    val bannedChargerKeys: LinkedHashSet<String> = LinkedHashSet()
}

class QonectaRouteTabView(
    context: Context,
    private val isSplitScreen: Boolean,
    private val onRequestRefresh: () -> Unit,
) : FrameLayout(context) {

    private val prefs = context.getSharedPreferences("qonecta_extended_prefs", Context.MODE_PRIVATE)
    private val uiHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val density: Float = context.resources.displayMetrics.density * 1.30f

    private var chargerMinKw: Int = prefs.getInt("charger_min_kw", 0)
    private var chargerVolts: Int = prefs.getInt("charger_volts_filter", QonectaRoutePlanner.VOLTS_ANY)
    private var chargerIncludeUnknown: Boolean = prefs.getBoolean("charger_include_unknown", true)
    private val selectedNetworks: LinkedHashSet<String> = LinkedHashSet<String>().apply {
        prefs.getString("charger_selected_networks", "").orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { add(it) }
    }
    private var arriveMinPct: Double = prefs.getInt("plan_arrive_min_pct", 15).toDouble()
    private var stopMinPct: Double = prefs.getInt("plan_stop_min_pct", 10).toDouble()
    private var maxChargePct: Double = prefs.getInt("plan_max_charge_pct", 80).toDouble()
    private var fastPriceEur: Double = prefs.getFloat("plan_fast_price_eur", 0.45f).toDouble()

    init {
        QonectaRouteTabState.favoriteChargerKeys.addAll(
            prefs.getString("fav_charger_keys", "").orEmpty().split("|").filter { it.isNotBlank() },
        )
        QonectaRouteTabState.bannedChargerKeys.addAll(
            prefs.getString("ban_charger_keys", "").orEmpty().split("|").filter { it.isNotBlank() },
        )
        buildUi()
    }

    private fun dp(v: Int): Int = (v * density).roundToInt()

    private fun TextView.setTextSp(sp: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * density)
    }

    private fun currentSocAndReal(): Pair<Double, Boolean> {
        val snap = LeapVehicleDataSource.snapshot()
        val isReal = snap.connected && snap.hasBatteryData && !snap.isDemo && !snap.socPct.isNaN()
        val base = if (!snap.socPct.isNaN()) snap.socPct else 78.0
        val adjusted = (base + QonectaRouteTabState.socAdjustDelta).coerceIn(5.0, 100.0)
        return adjusted to isReal
    }

    private fun currentChargeSettings(): QonectaRoutePlanner.ChargeSettings {
        val variant = LeapVehicleDataSource.getSelectedVariant(context)
        return QonectaRoutePlanner.ChargeSettings(
            arriveMinPct = arriveMinPct,
            stopMinPct = stopMinPct,
            maxChargePct = maxChargePct,
            carPeakKw = variant.peakDcChargeKw.toDouble(),
            capacityKwh = variant.batteryUsableKwh,
            carVolts = variant.architectureVolts.toDouble(),
            favorites = QonectaRouteTabState.favoriteChargerKeys.toSet(),
            pinnedKeys = QonectaRouteTabState.pinnedChargerKeys.toList(),
            fastChargePriceEur = fastPriceEur,
        )
    }

    private fun filterChargers(all: List<QonectaRoutePlanner.Charger>): List<QonectaRoutePlanner.Charger> {
        return all.filter { c ->
            QonectaRoutePlanner.acceptsCharger(
                c = c,
                minKw = chargerMinKw,
                voltsFilter = chargerVolts,
                networks = selectedNetworks,
                includeUnknown = chargerIncludeUnknown,
                bannedKeys = QonectaRouteTabState.bannedChargerKeys,
            )
        }
    }

    private fun buildUi() {
        removeAllViews()
        val (socNow, isRealSoc) = currentSocAndReal()
        val plan = QonectaRouteTabState.currentPlan
        val settings = currentChargeSettings()
        val filteredChargers = if (plan != null) filterChargers(plan.chargers) else emptyList()
        val chargeResult = if (plan != null) {
            QonectaRoutePlanner.planChargingStops(plan, socNow, filteredChargers, settings)
        } else {
            null
        }

        if (isSplitScreen) {
            // Pantalla partida (idéntico a narrowLayout de RouteTab.java):
            // Arriba la tarjeta de Ruta + Perfil; debajo Batería al llegar y Cargadores en ruta lado a lado
            val scroll = ScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
            }
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val routeCard = buildRouteMainCard(plan, chargeResult, socNow, compact = true)
            col.addView(routeCard, LinearLayout.LayoutParams(MATCH, dp(330)))

            val bottomRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }
            val batCard = buildBatteryArrivalCard(plan, chargeResult, socNow, isRealSoc, compact = true)
            val chCard = buildChargersColumnCard(plan, chargeResult, filteredChargers, compact = true)
            bottomRow.addView(batCard, LinearLayout.LayoutParams(0, dp(340), 0.88f).apply { rightMargin = dp(8) })
            bottomRow.addView(chCard, LinearLayout.LayoutParams(0, dp(340), 1.12f))
            col.addView(bottomRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
            scroll.addView(col)
            addView(scroll, LayoutParams(MATCH, MATCH))
        } else {
            // Pantalla completa (idéntico a RouteTab.java wide layout: 1.95f izquierda + 1.0f derecha)
            val rootRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2.95f
            }
            val leftRouteCard = buildRouteMainCard(plan, chargeResult, socNow, compact = false)
            val rightCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val batCard = buildBatteryArrivalCard(plan, chargeResult, socNow, isRealSoc, compact = false)
            val wxCard = buildWeatherCard(plan)
            val chCard = buildChargersColumnCard(plan, chargeResult, filteredChargers, compact = false)

            rightCol.addView(batCard, LinearLayout.LayoutParams(MATCH, dp(192)).apply { bottomMargin = dp(10) })
            rightCol.addView(wxCard, LinearLayout.LayoutParams(MATCH, dp(106)).apply { bottomMargin = dp(10) })
            rightCol.addView(chCard, LinearLayout.LayoutParams(MATCH, 0, 1f))

            rootRow.addView(leftRouteCard, LinearLayout.LayoutParams(0, MATCH, 1.95f).apply { rightMargin = dp(12) })
            rootRow.addView(rightCol, LinearLayout.LayoutParams(0, MATCH, 1.0f))
            addView(rootRow, LayoutParams(MATCH, MATCH))
        }
    }

    // =========================================================================
    // TARJETA PRINCIPAL IZQUIERDA (routeCard de RouteTab.java)
    // =========================================================================

    private fun buildRouteMainCard(
        plan: QonectaRoutePlanner.Plan?,
        chargeResult: QonectaRoutePlanner.ChargeResult?,
        socNow: Double,
        compact: Boolean,
    ): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = if (compact) dp(12) else dp(18)
            setPadding(pad, pad, pad, pad)
            background = cardDrawable()
        }

        // Barra superior de píldoras de acción (Desde, Buscar destino, Casa, Trabajo, Guardados, Guiar con Google Maps, Terminar)
        val actionsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val (originPlace, isRealGps) = QonectaRoutePlanner.resolveOrigin(context)
        val originShort = if (QonectaRoutePlanner.getCustomOrigin() != null) {
            "📍 Desde: ${originPlace.name.take(16)}"
        } else if (isRealGps) {
            "🛰️ Desde: GPS"
        } else {
            "📍 Desde: ${originPlace.name.substringBefore("(").trim().take(14)}"
        }
        val originPill = pillButton(originShort, primary = false, small = compact) {
            showPlaceSearchDialog(isOrigin = true)
        }
        actionsRow.addView(originPill)

        val searchPill = pillButton("🔍 Buscar destino", primary = plan == null, small = compact) {
            showPlaceSearchDialog(isOrigin = false)
        }
        actionsRow.addView(searchPill, spacedParams())

        val favs = loadFavorites()
        favs.take(2).forEach { slot ->
            val pill = pillButton("${slot.icon} ${slot.title}", primary = false, small = compact) {
                if (slot.address.isNotBlank()) {
                    startRouteToFavorite(slot)
                } else {
                    showFavoriteEditDialog(slot)
                }
            }
            pill.setOnLongClickListener {
                showFavoriteEditDialog(slot)
                true
            }
            actionsRow.addView(pill, spacedParams())
        }

        val savedPill = pillButton("★ Favoritos", primary = false, small = compact) {
            showAllFavoritesDialog()
        }
        actionsRow.addView(savedPill, spacedParams())

        if (plan != null) {
            val roundPill = pillButton(
                if (QonectaRouteTabState.roundTrip) "⇄ Ida y vuelta: Sí" else "⇄ Ida y vuelta",
                primary = QonectaRouteTabState.roundTrip,
                small = compact,
            ) {
                QonectaRouteTabState.roundTrip = !QonectaRouteTabState.roundTrip
                QonectaRouteTabState.lastDestinationPlace?.let { recalculateRoute(it) }
            }
            actionsRow.addView(roundPill, spacedParams())

            val goMapsPill = pillButton("Guiar con Google Maps ↗", primary = true, small = compact) {
                sendPlanToPhoneMaps(plan, chargeResult)
            }
            actionsRow.addView(goMapsPill, spacedParams())

            val clearPill = pillButton("✕ Terminar ruta", primary = false, small = compact) {
                QonectaRouteTabState.currentPlan = null
                QonectaRouteTabState.lastDestinationPlace = null
                QonectaRouteTabState.viaPlaces.clear()
                QonectaRouteTabState.pinnedChargerKeys.clear()
                QonectaRouteTabState.statusMessage = ""
                onRequestRefresh()
            }
            actionsRow.addView(clearPill, spacedParams())
        }

        actionsScroll.addView(actionsRow)
        card.addView(actionsScroll, LinearLayout.LayoutParams(MATCH, WRAP))

        if (plan == null) {
            // Estado vacío de RouteTab.java (ilustración de carretera + chincheta + ¿A dónde vamos? + CTA grande)
            val emptyCanvas = RouteEmptyStateCanvas(
                context = context,
                loading = QonectaRouteTabState.routeLoading,
                statusText = QonectaRouteTabState.statusMessage,
                originSummary = "${originPlace.name} · ${originPlace.detail}",
                compact = compact,
                onSearchClicked = { showPlaceSearchDialog(isOrigin = false) },
            )
            card.addView(emptyCanvas, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
            return card
        }

        // Cabecera del destino (Chincheta + DESTINO + Nombre grande + Tiempo a la derecha en modo compacto)
        val destHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(if (compact) 6 else 10), 0, dp(if (compact) 4 else 8))
        }
        val destTextCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (!compact) {
            destTextCol.addView(
                TextView(context).apply {
                    val viaSuffix = if (plan.roundTrip) " · IDA Y VUELTA ⇄" else ""
                    text = "📍 DESTINO EN RUTA$viaSuffix · DESDE ${plan.originName.uppercase(Locale.getDefault())}"
                    setTextSp(11.5f)
                    setTextColor(QonectaTheme.faint)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    letterSpacing = 0.08f
                },
            )
        }
        destTextCol.addView(
            TextView(context).apply {
                val suffix = if (plan.roundTrip) " ⇄" else ""
                text = if (compact) "📍 ${plan.destination}$suffix" else "${plan.destination}$suffix"
                setTextSp(if (compact) 18f else 25f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        destHeader.addView(destTextCol, LinearLayout.LayoutParams(0, WRAP, 1f))

        if (compact && !plan.destTemp.isNaN()) {
            destHeader.addView(
                TextView(context).apply {
                    text = "${QonectaRoutePlanner.weatherEmoji(plan.destCode)} ${plan.destTemp.roundToInt()} °C · ${QonectaRoutePlanner.weatherText(plan.destCode)}"
                    setTextSp(13.5f)
                    setTextColor(QonectaTheme.dim)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                },
            )
        }
        card.addView(destHeader, LinearLayout.LayoutParams(MATCH, WRAP))

        // 4 Fichas de Qonecta+ (Faltan, Llegada, Necesitas, Consumo previsto)
        val remKm = plan.remainingKm
        val chargeSec = ((chargeResult?.chargeMinutes ?: 0.0) * 60.0).roundToInt().toLong()
        val totalRemSec = plan.remainingSeconds + chargeSec
        val etaStr = SimpleDateFormat("HH:mm", Locale.getDefault())
            .format(Date(System.currentTimeMillis() + totalRemSec * 1000L))
        val durStr = formatDuration(totalRemSec)
        val remKwh = plan.remainingKwh
        val per100 = if (remKm > 0.5) remKwh / remKm * 100.0 else Double.NaN

        val chipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }
        chipsRow.addView(
            buildMetricChip("FALTAN", String.format(Locale.getDefault(), "%.0f", remKm), "km", compact),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) },
        )
        chipsRow.addView(
            buildMetricChip("LLEGADA", etaStr, if (compact) "" else durStr, compact),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) },
        )
        chipsRow.addView(
            buildMetricChip("NECESITAS", "~" + String.format(Locale.getDefault(), "%.1f", remKwh), "kWh", compact),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) },
        )
        chipsRow.addView(
            buildMetricChip(
                "CONSUMO PREVISTO",
                if (per100.isNaN()) "—" else String.format(Locale.getDefault(), "%.1f", per100),
                if (compact) "" else "kWh/100",
                compact,
            ),
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        card.addView(chipsRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // Explicación de las cuestas y origen de los datos (explain() de RouteTab.java)
        if (!compact) {
            val explainRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(4))
            }
            val hillsText = buildHillsExplanation(plan)
            explainRow.addView(
                TextView(context).apply {
                    text = hillsText
                    setTextSp(12.5f)
                    setTextColor(QonectaTheme.dim)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, WRAP, 1f),
            )
            val srcText = if (plan.dgtCount > 0) {
                "Ruta OSRM · DGT (${plan.dgtCount}) + OSM (${plan.chargers.size})"
            } else {
                "Ruta OSRM · OpenStreetMap (${plan.chargers.size} cargadores)"
            }
            explainRow.addView(
                TextView(context).apply {
                    text = srcText
                    setTextSp(12f)
                    setTextColor(QonectaTheme.accent)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                },
            )
            card.addView(explainRow, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        // Lienzo gráfico completo de Qonecta+: Perfil por pendiente + curva SoC + cargadores + viento + 12 barras kWh/100 + leyenda
        val profileCanvas = QonectaRouteProfileCanvas(
            context = context,
            plan = plan,
            chargeResult = chargeResult,
            filteredChargers = filterChargers(plan.chargers),
            socNow = socNow,
            capacityKwh = currentChargeSettings().capacityKwh,
            isReev = LeapVehicleDataSource.getSelectedVariant(context).isReev,
            compact = compact,
        )
        card.addView(profileCanvas, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(4) })

        return card
    }

    private fun buildMetricChip(label: String, value: String, unit: String, compact: Boolean): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pH = if (compact) dp(8) else dp(12)
            val pV = if (compact) dp(6) else dp(9)
            setPadding(pH, pV, pH, pV)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(QonectaTheme.surfaceTop)
                setStroke(dp(1), QonectaTheme.outline)
            }
            addView(
                TextView(context).apply {
                    text = label
                    setTextSp(if (compact) 10f else 11f)
                    setTextColor(QonectaTheme.faint)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            addView(
                TextView(context).apply {
                    text = if (unit.isNotEmpty()) "$value $unit" else value
                    setTextSp(if (compact) 15.5f else 20f)
                    setTextColor(QonectaTheme.text)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                },
            )
        }
    }

    private fun buildHillsExplanation(plan: QonectaRoutePlanner.Plan): String {
        if (plan.flatElevation) return "Altitud estimada en llano (servicio de relieve sin respuesta)"
        var up = 0.0
        var down = 0.0
        for (i in (plan.progressIdx + 1) until plan.n) {
            val d = plan.elev[i] - plan.elev[i - 1]
            if (d > 0) up += d else down -= d
        }
        val hillsKwh = plan.gravCum[plan.n - 1] - plan.gravCum[plan.progressIdx.coerceIn(0, plan.n - 1)]
        return if (up < 15.0 && down < 15.0) {
            "Ruta prácticamente llana por delante"
        } else if (hillsKwh >= 0) {
            String.format(Locale.getDefault(), "Subidas +%.0f m · bajadas −%.0f m (las cuestas suman +%.1f kWh)", up, down, abs(hillsKwh))
        } else {
            String.format(Locale.getDefault(), "Subidas +%.0f m · bajadas −%.0f m (recuperas −%.1f kWh bajando)", up, down, abs(hillsKwh))
        }
    }

    // =========================================================================
    // TARJETA DERECHA 1: BATERÍA AL LLEGAR (paintBattery de RouteTab.java)
    // =========================================================================

    private fun buildBatteryArrivalCard(
        plan: QonectaRoutePlanner.Plan?,
        chargeResult: QonectaRoutePlanner.ChargeResult?,
        socNow: Double,
        isRealSoc: Boolean,
        compact: Boolean,
    ): View {
        val card = FrameLayout(context).apply {
            val pad = if (compact) dp(10) else dp(14)
            setPadding(pad, pad, pad, pad)
            background = cardDrawable()
        }

        val variant = LeapVehicleDataSource.getSelectedVariant(context)
        val capKwh = variant.batteryUsableKwh
        val rawArrivalPct = if (plan != null) {
            socNow - (plan.remainingKwh / capKwh * 100.0)
        } else {
            Double.NaN
        }
        val effectiveArrivalPct = when {
            plan == null -> Double.NaN
            chargeResult != null && chargeResult.outcome == QonectaRoutePlanner.Outcome.PLANNED -> chargeResult.arrivalPct
            variant.isReev && !rawArrivalPct.isNaN() -> max(min(socNow, 20.0), rawArrivalPct)
            else -> rawArrivalPct
        }

        val canvasView = QonectaArrivalBatteryCanvas(
            context = context,
            socNow = socNow,
            arrivalPct = effectiveArrivalPct,
            rawArrivalPct = rawArrivalPct,
            capKwh = capKwh,
            isRealSoc = isRealSoc,
            isReev = variant.isReev,
            chargeResult = chargeResult,
            compact = compact,
        )
        card.addView(canvasView, LayoutParams(MATCH, MATCH))

        // Controles −5 / +5 abajo a la derecha (idéntico a socControls de RouteTab.java)
        val ctlRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minusBtn = pillButton("−5 %", primary = false, small = true) {
            QonectaRouteTabState.socAdjustDelta = (QonectaRouteTabState.socAdjustDelta - 5).coerceIn(-80, 80)
            LeapVehicleDataSource.adjustManualSoc(context, -5)
            buildUi()
        }
        val plusBtn = pillButton("+5 %", primary = false, small = true) {
            QonectaRouteTabState.socAdjustDelta = (QonectaRouteTabState.socAdjustDelta + 5).coerceIn(-80, 80)
            LeapVehicleDataSource.adjustManualSoc(context, +5)
            buildUi()
        }
        ctlRow.addView(minusBtn)
        ctlRow.addView(plusBtn, spacedParams(dp(6)))
        card.addView(
            ctlRow,
            LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END),
        )
        return card
    }

    // =========================================================================
    // TARJETA DERECHA 2: TIEMPO AL LLEGAR (paintWeather de RouteTab.java)
    // =========================================================================

    private fun buildWeatherCard(plan: QonectaRoutePlanner.Plan?): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = cardDrawable()
        }
        card.addView(
            TextView(context).apply {
                text = "TIEMPO AL LLEGAR"
                setTextSp(11f)
                setTextColor(QonectaTheme.faint)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.08f
            },
        )
        if (plan == null || plan.destTemp.isNaN()) {
            card.addView(
                TextView(context).apply {
                    text = "—  Elige un destino para ver el tiempo previsto a la hora de llegada"
                    setTextSp(14.5f)
                    setTextColor(QonectaTheme.dim)
                    setPadding(0, dp(14), 0, 0)
                },
            )
            return card
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        row.addView(
            TextView(context).apply {
                text = QonectaRoutePlanner.weatherEmoji(plan.destCode)
                setTextSp(30f)
                setPadding(0, 0, dp(12), 0)
            },
        )
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val tempRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        tempRow.addView(
            TextView(context).apply {
                text = String.format(Locale.getDefault(), "%.0f °C", plan.destTemp)
                setTextSp(22f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
        )
        tempRow.addView(
            TextView(context).apply {
                text = "  ·  ${QonectaRoutePlanner.weatherText(plan.destCode)}"
                setTextSp(15f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        col.addView(tempRow)
        col.addView(
            TextView(context).apply {
                text = String.format(
                    Locale.getDefault(),
                    "Viento %.0f km/h · Precipitación %.1f mm",
                    plan.destWind,
                    plan.destRain,
                )
                setTextSp(12.5f)
                setTextColor(QonectaTheme.dim)
            },
        )
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(row)
        return card
    }

    // =========================================================================
    // TARJETA DERECHA 3: CARGADORES EN RUTA Y PLAN DE CARGA (fillChargers / addPlan de RouteTab.java)
    // =========================================================================

    private fun buildChargersColumnCard(
        plan: QonectaRoutePlanner.Plan?,
        chargeResult: QonectaRoutePlanner.ChargeResult?,
        filteredChargers: List<QonectaRoutePlanner.Charger>,
        compact: Boolean,
    ): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = if (compact) dp(10) else dp(14)
            setPadding(pad, pad, pad, pad)
            background = cardDrawable()
        }

        // Cabecera: Título + Botón «Ver todos»
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(context).apply {
                text = "CARGADORES EN RUTA"
                setTextSp(11f)
                setTextColor(QonectaTheme.faint)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.08f
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        if (plan != null && plan.chargers.isNotEmpty()) {
            val allBtn = TextView(context).apply {
                text = "Ver lista (${filteredChargers.size}/${plan.chargers.size}) ↗"
                setTextSp(11.5f)
                setTextColor(QonectaTheme.accent)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setOnClickListener { showFullRouteChargersDialog(plan, chargeResult) }
            }
            titleRow.addView(allBtn)
        }
        card.addView(titleRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // Barra de filtro: Filtrar + Cerca de mí + Resumen del filtro activo
        val filterRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val filterBtn = pillButton("⚙ Filtrar", primary = false, small = true) {
            showChargerFilterDialog(plan)
        }
        val nearbyBtn = pillButton("📍 Cerca de mí", primary = false, small = true) {
            showNearbyChargersDialog()
        }
        val summaryText = buildFilterSummaryText()
        val summaryTv = TextView(context).apply {
            text = summaryText
            setTextSp(12f)
            setTextColor(if (summaryText == "Todos los cargadores") QonectaTheme.faint else QonectaTheme.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, 0, 0)
        }
        filterRow.addView(filterBtn)
        filterRow.addView(nearbyBtn, spacedParams(dp(6)))
        filterRow.addView(summaryTv, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(filterRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val listScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
        }
        val listCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (plan == null) {
            listCol.addView(
                TextView(context).apply {
                    text = "Busca un destino o elige Casa / Trabajo para calcular las paradas óptimas de carga y ver los cargadores de la ruta.\n\nTambién puedes tocar «📍 Cerca de mí» para ver las electrolineras reales a tu alrededor ahora mismo."
                    setTextSp(13.5f)
                    setTextColor(QonectaTheme.dim)
                    setPadding(0, dp(8), 0, dp(8))
                },
            )
            listScroll.addView(listCol)
            card.addView(listScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            return card
        }

        // 1. Sección PLAN DE CARGA (idéntico a addPlan() de RouteTab.java)
        if (chargeResult != null) {
            when {
                chargeResult.outcome == QonectaRoutePlanner.Outcome.NO_STOPS -> {
                    listCol.addView(
                        TextView(context).apply {
                            text = String.format(
                                Locale.getDefault(),
                                "✓ Llegas sin parar con un %.0f %% de batería (margen mínimo configurado: %.0f %%).",
                                max(0.0, chargeResult.arrivalPct),
                                arriveMinPct,
                            )
                            setTextSp(13.5f)
                            setTextColor(QonectaTheme.green)
                            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                            setPadding(0, dp(4), 0, dp(8))
                        },
                    )
                }
                chargeResult.outcome == QonectaRoutePlanner.Outcome.NO_CHARGER && chargeResult.stops.isEmpty() -> {
                    listCol.addView(
                        TextView(context).apply {
                            text = "⚠ Con este filtro no hay ningún cargador alcanzable para llegar al destino. Prueba a abrir «Filtrar» y activar más redes o menor potencia."
                            setTextSp(13.5f)
                            setTextColor(QonectaTheme.amber)
                            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                            setPadding(0, dp(4), 0, dp(8))
                        },
                    )
                }
                else -> {
                    listCol.addView(
                        TextView(context).apply {
                            text = "PLAN DE CARGA"
                            setTextSp(11.5f)
                            setTextColor(QonectaTheme.accent)
                            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                            letterSpacing = 0.08f
                            setPadding(0, dp(4), 0, dp(2))
                        },
                    )
                    val durStr = formatDuration((chargeResult.chargeMinutes * 60.0).roundToInt().toLong())
                    val costStr = if (chargeResult.totalCostEur >= 0.5) {
                        String.format(Locale.getDefault(), " · ≈ %.0f €", chargeResult.totalCostEur)
                    } else {
                        ""
                    }
                    val summaryLine = if (chargeResult.outcome == QonectaRoutePlanner.Outcome.NO_CHARGER) {
                        val lastKm = chargeResult.stops.lastOrNull()?.km ?: 0.0
                        String.format(
                            Locale.getDefault(),
                            "%d paradas (%s) · falta cargador tras el km %.0f",
                            chargeResult.stops.size,
                            durStr,
                            lastKm,
                        )
                    } else {
                        val stopsWord = if (chargeResult.stops.size == 1) "1 parada" else "${chargeResult.stops.size} paradas"
                        String.format(
                            Locale.getDefault(),
                            "%s · %s de carga · llegas con %.0f %%%s",
                            stopsWord,
                            durStr,
                            max(0.0, chargeResult.arrivalPct),
                            costStr,
                        )
                    }
                    listCol.addView(
                        TextView(context).apply {
                            text = summaryLine
                            setTextSp(14f)
                            setTextColor(
                                if (chargeResult.outcome == QonectaRoutePlanner.Outcome.PLANNED) QonectaTheme.text else QonectaTheme.amber,
                            )
                            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                            setPadding(0, 0, 0, dp(6))
                        },
                    )

                    chargeResult.stops.forEachIndexed { idx, stop ->
                        listCol.addView(
                            buildPlannedStopRow(idx, stop, plan, chargeResult, filteredChargers),
                            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) },
                        )
                    }

                    // Botón primario de Qonecta+: «Ir con las paradas» -> envía destino + hasta 3 paradas al móvil
                    val goWithStopsBtn = pillButton(
                        text = if (chargeResult.stops.size == 1) {
                            "Ir con la parada en Google Maps ↗"
                        } else {
                            "Ir con las ${min(3, chargeResult.stops.size)} paradas en Google Maps ↗"
                        },
                        primary = true,
                        small = false,
                    ) {
                        sendPlanToPhoneMaps(plan, chargeResult)
                    }
                    listCol.addView(
                        goWithStopsBtn,
                        LinearLayout.LayoutParams(WRAP, WRAP).apply {
                            topMargin = dp(2)
                            bottomMargin = dp(10)
                        },
                    )
                }
            }
        }

        // 2. Sección OTROS CARGADORES EN RUTA
        val inPlanKeys = chargeResult?.stops?.map { it.charger.stableKey }?.toSet().orEmpty()
        val otherChargers = filteredChargers.filter { !inPlanKeys.contains(it.stableKey) && it.kmAlong >= plan.currentKm - 0.5 }

        listCol.addView(
            TextView(context).apply {
                text = if (chargeResult != null && chargeResult.stops.isNotEmpty()) {
                    "OTROS CARGADORES EN RUTA (${otherChargers.size})"
                } else {
                    "CARGADORES EN EL CORREDOR DE LA RUTA (${otherChargers.size})"
                }
                setTextSp(11f)
                setTextColor(QonectaTheme.faint)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.08f
                setPadding(0, dp(4), 0, dp(6))
            },
        )

        if (otherChargers.isEmpty()) {
            listCol.addView(
                TextView(context).apply {
                    text = if (plan.chargers.isNotEmpty()) {
                        "Hay ${plan.chargers.size} cargadores en la ruta, pero el filtro actual los oculta."
                    } else {
                        "Buscando cargadores junto a la carretera en OpenStreetMap / DGT…"
                    }
                    setTextSp(13f)
                    setTextColor(QonectaTheme.dim)
                    setPadding(0, dp(4), 0, dp(8))
                },
            )
            if (plan.chargers.isNotEmpty()) {
                listCol.addView(
                    pillButton("Quitar filtros de cargadores", primary = false, small = true) {
                        chargerMinKw = 0
                        chargerVolts = QonectaRoutePlanner.VOLTS_ANY
                        selectedNetworks.clear()
                        saveFilterPrefs()
                        buildUi()
                    },
                    LinearLayout.LayoutParams(WRAP, WRAP),
                )
            }
        } else {
            otherChargers.take(15).forEach { ch ->
                listCol.addView(
                    buildOtherChargerRow(ch, plan.currentKm),
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) },
                )
            }
        }

        listScroll.addView(listCol)
        card.addView(listScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return card
    }

    private fun buildPlannedStopRow(
        idx: Int,
        stop: QonectaRoutePlanner.ChargeStop,
        plan: QonectaRoutePlanner.Plan,
        chargeResult: QonectaRoutePlanner.ChargeResult,
        filteredChargers: List<QonectaRoutePlanner.Charger>,
    ): View {
        val c = stop.charger
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(QonectaTheme.surfaceTop)
                setStroke(dp(1), QonectaTheme.accent)
            }
        }
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(ChargerDotView(context, c.maxKw, c.network), LinearLayout.LayoutParams(dp(38), dp(38)))

        val txtCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        val pinPrefix = if (stop.isPinned) "📌 " else ""
        txtCol.addView(
            TextView(context).apply {
                text = "${idx + 1}. $pinPrefix${c.name}"
                setTextSp(14.5f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        val lowVolts = QonectaRoutePlanner.lowVolts(c, chargeResult.carVolts)
        val stopDur = formatDuration((stop.minutes * 60.0).roundToInt().toLong())
        val lowNote = if (lowVolts) " · 400 V (carga reducida)" else ""
        txtCol.addView(
            TextView(context).apply {
                text = String.format(
                    Locale.getDefault(),
                    "km %.0f · %.0f %% → %.0f %% (%s) · %s%s",
                    stop.km,
                    stop.arrivePct,
                    stop.departPct,
                    stopDur,
                    QonectaRoutePlanner.kwText(c),
                    lowNote,
                )
                setTextSp(12.5f)
                setTextColor(if (lowVolts) QonectaTheme.amber else QonectaTheme.accent)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 2
            },
        )
        topRow.addView(txtCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(topRow)

        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val changeBtn = pillButton("Cambiar", primary = false, small = true) {
            showStopAlternativesDialog(idx, plan, chargeResult, filteredChargers)
        }
        actionsRow.addView(changeBtn)

        val favBtn = buildFavoriteChargerButton(c)
        actionsRow.addView(favBtn, spacedParams(dp(6)))

        val goBtn = pillButton("Ir ↗", primary = false, small = true) {
            sendSinglePointToPhoneMaps(c.lat, c.lon, c.name)
        }
        actionsRow.addView(goBtn, spacedParams(dp(6)))
        box.addView(actionsRow)
        return box
    }

    private fun buildOtherChargerRow(c: QonectaRoutePlanner.Charger, hereKm: Double): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(QonectaTheme.surfaceTop)
                setStroke(dp(1), QonectaTheme.outline)
            }
        }
        row.addView(ChargerDotView(context, c.maxKw, c.network), LinearLayout.LayoutParams(dp(36), dp(36)))

        val txtCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        txtCol.addView(
            TextView(context).apply {
                text = c.name
                setTextSp(13.5f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        val remKm = max(0.0, c.kmAlong - hereKm)
        val detailSuffix = if (c.detail.isNotBlank()) " · ${c.detail}" else ""
        txtCol.addView(
            TextView(context).apply {
                text = String.format(
                    Locale.getDefault(),
                    "km %.0f (a %.0f km) · %s%s",
                    c.kmAlong,
                    remKm,
                    QonectaRoutePlanner.kwText(c),
                    detailSuffix,
                )
                setTextSp(11.5f)
                setTextColor(QonectaTheme.dim)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        row.addView(txtCol, LinearLayout.LayoutParams(0, WRAP, 1f))

        val isPinned = QonectaRouteTabState.pinnedChargerKeys.contains(c.stableKey)
        val pinBtn = pillButton(if (isPinned) "📌 Fijada" else "📌 Parar", primary = isPinned, small = true) {
            if (isPinned) {
                QonectaRouteTabState.pinnedChargerKeys.remove(c.stableKey)
            } else {
                QonectaRouteTabState.pinnedChargerKeys.add(c.stableKey)
            }
            buildUi()
        }
        row.addView(pinBtn)

        val goBtn = pillButton("Ir ↗", primary = false, small = true) {
            sendSinglePointToPhoneMaps(c.lat, c.lon, c.name)
        }
        row.addView(goBtn, spacedParams(dp(6)))
        return row
    }

    private fun buildFavoriteChargerButton(c: QonectaRoutePlanner.Charger): View {
        val isFav = QonectaRouteTabState.favoriteChargerKeys.contains(c.stableKey)
        return pillButton(if (isFav) "★ Fav" else "☆", primary = isFav, small = true) {
            if (isFav) {
                QonectaRouteTabState.favoriteChargerKeys.remove(c.stableKey)
            } else {
                QonectaRouteTabState.favoriteChargerKeys.add(c.stableKey)
                QonectaRouteTabState.bannedChargerKeys.remove(c.stableKey)
            }
            saveChargerMarks()
            buildUi()
        }
    }

    private fun saveChargerMarks() {
        prefs.edit()
            .putString("fav_charger_keys", QonectaRouteTabState.favoriteChargerKeys.joinToString("|"))
            .putString("ban_charger_keys", QonectaRouteTabState.bannedChargerKeys.joinToString("|"))
            .apply()
    }

    // =========================================================================
    // ENVÍO REAL DE LA RUTA Y PARADAS A GOOGLE MAPS DEL MÓVIL (CompanionBridgeHost)
    // =========================================================================

    private fun sendPlanToPhoneMaps(
        plan: QonectaRoutePlanner.Plan,
        chargeResult: QonectaRoutePlanner.ChargeResult?,
    ) {
        val destPoint = NavPoint(plan.destLat, plan.destLon, plan.destination)
        val viaPoints = mutableListOf<NavPoint>()
        // Incluimos primero paradas intermedias manuales si las hay y las paradas de carga del plan (hasta 3 en total, límite de Google Maps)
        if (chargeResult != null && chargeResult.stops.isNotEmpty()) {
            for (st in chargeResult.stops) {
                if (st.km > plan.currentKm + QonectaRoutePlanner.HERE_KM && viaPoints.size < 3) {
                    viaPoints.add(NavPoint(st.charger.lat, st.charger.lon, st.charger.name))
                }
            }
        }
        CompanionBridgeHost.sendRoute(destPoint, viaPoints) { result ->
            val stopsDesc = if (viaPoints.isEmpty()) "directa" else "con ${viaPoints.size} parada(s) de carga"
            val msg = when (result) {
                is CompanionBridgeHost.SendResult.Sent ->
                    "✓ Ruta $stopsDesc a «${plan.destination}» enviada a ${result.device} (${result.transport}). Abriendo Google Maps en Android Auto."
                is CompanionBridgeHost.SendResult.NeedsTap ->
                    "Ruta enviada a ${result.device}. Toca la notificación en el móvil para abrirla en Google Maps."
                is CompanionBridgeHost.SendResult.Failed ->
                    "⚠ ${result.message}"
                CompanionBridgeHost.SendResult.NotConnected ->
                    "⚠ Tu móvil no está conectado a Qonecta Unit Companion. Abre la app Companion en tu móvil o empareja desde Ajustes y Menú Extendido."
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun sendSinglePointToPhoneMaps(lat: Double, lon: Double, label: String) {
        val dest = NavPoint(lat, lon, label)
        CompanionBridgeHost.sendRoute(dest, emptyList()) { result ->
            val msg = when (result) {
                is CompanionBridgeHost.SendResult.Sent ->
                    "✓ Navegación a «$label» enviada a ${result.device} (${result.transport})."
                is CompanionBridgeHost.SendResult.NeedsTap ->
                    "Navegación enviada a ${result.device}. Toca la notificación en el móvil."
                is CompanionBridgeHost.SendResult.Failed ->
                    "⚠ ${result.message}"
                CompanionBridgeHost.SendResult.NotConnected ->
                    "⚠ Tu móvil no está conectado a Qonecta Unit Companion."
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    // =========================================================================
    // MODALES: CAMBIAR PARADA, BUSCADOR DE DESTINO, FILTRO, CERCA DE MÍ, LISTA COMPLETA
    // =========================================================================

    private fun showStopAlternativesDialog(
        stopIdx: Int,
        plan: QonectaRoutePlanner.Plan,
        chargeResult: QonectaRoutePlanner.ChargeResult,
        filteredChargers: List<QonectaRoutePlanner.Charger>,
    ) {
        val stop = chargeResult.stops.getOrNull(stopIdx) ?: return
        val settings = currentChargeSettings()
        val alts = QonectaRoutePlanner.stopAlternatives(chargeResult, stopIdx, filteredChargers, settings)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            setBackgroundColor(QonectaTheme.surface)
        }
        val dialog = AlertDialog.Builder(context).setView(container).create()

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(context).apply {
                text = "Cambiar parada ${stopIdx + 1} · ${stop.charger.name}"
                setTextSp(20f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        header.addView(
            pillButton("Cerrar", primary = true, small = true) { dialog.dismiss() },
        )
        container.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        container.addView(
            TextView(context).apply {
                text = String.format(
                    Locale.getDefault(),
                    "Parada actual en el km %.0f (llegas con %.0f %%). Elige otro cargador de ese tramo para fijarlo en esta ruta:",
                    stop.km,
                    stop.arrivePct,
                )
                setTextSp(14f)
                setTextColor(QonectaTheme.dim)
                setPadding(0, dp(6), 0, dp(10))
            },
        )

        if (stop.isPinned) {
            container.addView(
                pillButton("Quitar mi elección manual y dejar que el plan elija", primary = false, small = true) {
                    QonectaRouteTabState.pinnedChargerKeys.remove(stop.charger.stableKey)
                    dialog.dismiss()
                    buildUi()
                },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(10) },
            )
        }

        val scroll = ScrollView(context)
        val listCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        if (alts.isEmpty()) {
            listCol.addView(
                TextView(context).apply {
                    text = "No hay otros cargadores alcanzables en este tramo con el filtro actual."
                    setTextSp(15f)
                    setTextColor(QonectaTheme.amber)
                },
            )
        } else {
            for (alt in alts) {
                val c = alt.charger
                val item = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(if (alt.current) QonectaTheme.withAlpha(QonectaTheme.accent, 0x1A) else QonectaTheme.surfaceTop)
                        setStroke(dp(1), if (alt.current) QonectaTheme.accent else QonectaTheme.outline)
                    }
                }
                item.addView(ChargerDotView(context, c.maxKw, c.network), LinearLayout.LayoutParams(dp(40), dp(40)))
                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), 0, dp(8), 0)
                }
                col.addView(
                    TextView(context).apply {
                        text = c.name
                        setTextSp(15.5f)
                        setTextColor(QonectaTheme.text)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    },
                )
                col.addView(
                    TextView(context).apply {
                        text = String.format(
                            Locale.getDefault(),
                            "km %.0f · llegas con %.0f %% · %s",
                            c.kmAlong,
                            alt.arrivePct,
                            QonectaRoutePlanner.kwText(c),
                        )
                        setTextSp(13f)
                        setTextColor(if (alt.arrivePct < stopMinPct) QonectaTheme.amber else QonectaTheme.dim)
                    },
                )
                if (!alt.current) {
                    val dMin = alt.deltaMin.roundToInt()
                    val deltaStr = when {
                        !alt.works -> "Con esta parada no llegas al destino"
                        dMin == 0 -> "Mismo tiempo total de carga"
                        dMin > 0 -> "+$dMin min de carga frente al plan actual"
                        else -> "$dMin min de carga (más rápido)"
                    }
                    col.addView(
                        TextView(context).apply {
                            text = deltaStr
                            setTextSp(12.5f)
                            setTextColor(if (!alt.works) QonectaTheme.red else if (dMin > 1) QonectaTheme.amber else QonectaTheme.green)
                        },
                    )
                }
                item.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                if (alt.current) {
                    item.addView(
                        TextView(context).apply {
                            text = "En el plan"
                            setTextSp(13.5f)
                            setTextColor(QonectaTheme.accent)
                            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        },
                    )
                } else if (alt.works) {
                    item.addView(
                        pillButton("Usar esta", primary = true, small = true) {
                            QonectaRouteTabState.pinnedChargerKeys.remove(stop.charger.stableKey)
                            QonectaRouteTabState.pinnedChargerKeys.add(c.stableKey)
                            dialog.dismiss()
                            buildUi()
                        },
                    )
                }
                listCol.addView(item, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
            }
        }
        scroll.addView(listCol)
        container.addView(scroll, LinearLayout.LayoutParams(MATCH, dp(360)))
        dialog.show()
    }

    private fun showPlaceSearchDialog(isOrigin: Boolean) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            setBackgroundColor(QonectaTheme.surface)
        }
        val dialog = AlertDialog.Builder(context).setView(container).create()

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(
            TextView(context).apply {
                text = if (isOrigin) "📍 Elegir punto de salida" else "🔍 Buscar destino (Photon / OpenStreetMap)"
                setTextSp(20f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        if (isOrigin && QonectaRoutePlanner.getCustomOrigin() != null) {
            topRow.addView(
                pillButton("🛰️ Usar GPS del coche", primary = true, small = true) {
                    QonectaRoutePlanner.setCustomOrigin(null)
                    dialog.dismiss()
                    QonectaRouteTabState.lastDestinationPlace?.let { recalculateRoute(it) } ?: buildUi()
                },
                spacedParams(dp(8)),
            )
        }
        topRow.addView(pillButton("Cerrar", primary = false, small = true) { dialog.dismiss() })
        container.addView(topRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val input = EditText(context).apply {
            hint = if (isOrigin) "Ciudad o dirección de salida (ej. Sevilla, Valencia, Calle Alcalá…)"
            else "Dirección, ciudad o lugar (ej. Barcelona, Puerto Banús, Burgos…)"
            setTextColor(QonectaTheme.text)
            setHintTextColor(QonectaTheme.faint)
            setTextSp(16.5f)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(QonectaTheme.surfaceTop)
                setStroke(dp(2), QonectaTheme.accent)
            }
        }
        val resultsCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val statusTv = TextView(context).apply {
            text = "Escribe una ciudad, calle o punto de interés y pulsa «Buscar»."
            setTextSp(13.5f)
            setTextColor(QonectaTheme.dim)
            setPadding(0, 0, 0, dp(8))
        }

        val doSearch = {
            val q = input.text?.toString()?.trim().orEmpty()
            if (q.length >= 2) {
                statusTv.text = "Buscando «$q» en Photon y OpenStreetMap…"
                statusTv.setTextColor(QonectaTheme.accent)
                val (orig, _) = QonectaRoutePlanner.resolveOrigin(context)
                bgExecutor.execute {
                    val found = QonectaRoutePlanner.searchPlaces(q, orig.lat, orig.lon, limit = 7)
                    uiHandler.post {
                        resultsCol.removeAllViews()
                        if (found.isEmpty()) {
                            statusTv.text = "No se encontraron resultados para «$q»."
                            statusTv.setTextColor(QonectaTheme.amber)
                        } else {
                            statusTv.text = "Selecciona un lugar (${found.size} resultados):"
                            statusTv.setTextColor(QonectaTheme.dim)
                            found.forEach { place ->
                                val item = LinearLayout(context).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                    setPadding(dp(14), dp(10), dp(14), dp(10))
                                    background = GradientDrawable().apply {
                                        cornerRadius = dp(12).toFloat()
                                        setColor(QonectaTheme.surfaceTop)
                                        setStroke(dp(1), QonectaTheme.outline)
                                    }
                                    setOnClickListener {
                                        dialog.dismiss()
                                        if (isOrigin) {
                                            QonectaRoutePlanner.setCustomOrigin(place)
                                            QonectaRouteTabState.lastDestinationPlace?.let { recalculateRoute(it) } ?: buildUi()
                                        } else {
                                            recalculateRoute(place)
                                        }
                                    }
                                }
                                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                                col.addView(
                                    TextView(context).apply {
                                        text = "📍 ${place.name}"
                                        setTextSp(15.5f)
                                        setTextColor(QonectaTheme.text)
                                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                                    },
                                )
                                if (place.detail.isNotBlank()) {
                                    col.addView(
                                        TextView(context).apply {
                                            text = place.detail
                                            setTextSp(12.5f)
                                            setTextColor(QonectaTheme.dim)
                                        },
                                    )
                                }
                                item.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                                item.addView(
                                    TextView(context).apply {
                                        text = if (isOrigin) "Salir de aquí" else "Calcular ruta ⚡"
                                        setTextSp(13.5f)
                                        setTextColor(QonectaTheme.accent)
                                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                                    },
                                )
                                resultsCol.addView(item, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) })
                            }
                        }
                    }
                }
            }
        }

        val searchBtn = pillButton("Buscar", primary = true, small = false) { doSearch() }
        input.setOnEditorActionListener { _, _, _ ->
            doSearch()
            true
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(10) })
        inputRow.addView(searchBtn, LinearLayout.LayoutParams(WRAP, dp(48)))
        container.addView(inputRow, LinearLayout.LayoutParams(MATCH, WRAP))
        container.addView(statusTv)

        val scroll = ScrollView(context).apply { addView(resultsCol) }
        container.addView(scroll, LinearLayout.LayoutParams(MATCH, dp(290)))
        dialog.show()
    }

    private fun recalculateRoute(destPlace: QonectaRoutePlanner.Place) {
        QonectaRouteTabState.lastDestinationPlace = destPlace
        QonectaRouteTabState.routeLoading = true
        QonectaRouteTabState.currentPlan = null
        QonectaRouteTabState.pinnedChargerKeys.clear()
        QonectaRouteTabState.statusMessage = "Calculando trazado OSRM, altitud Open-Meteo, viento y cargadores hacia ${destPlace.name}…"
        buildUi()

        val appCtx = context.applicationContext
        val vias = QonectaRouteTabState.viaPlaces.toList()
        val round = QonectaRouteTabState.roundTrip
        bgExecutor.execute {
            try {
                val plan = QonectaRoutePlanner.buildRoutePlan(
                    context = appCtx,
                    destinationQueryOrPlace = destPlace,
                    viaPlaces = vias,
                    roundTrip = round,
                    onChargersUpdated = { updatedPlan ->
                        uiHandler.post {
                            if (QonectaRouteTabState.currentPlan?.destination == updatedPlan.destination) {
                                QonectaRouteTabState.currentPlan = updatedPlan
                                onRequestRefresh()
                            }
                        }
                    },
                )
                uiHandler.post {
                    QonectaRouteTabState.routeLoading = false
                    QonectaRouteTabState.statusMessage = ""
                    QonectaRouteTabState.currentPlan = plan
                    onRequestRefresh()
                }
            } catch (e: Exception) {
                uiHandler.post {
                    QonectaRouteTabState.routeLoading = false
                    QonectaRouteTabState.statusMessage = "⚠ No se pudo calcular la ruta: ${e.message ?: "revisa la conexión a Internet"}"
                    onRequestRefresh()
                }
            }
        }
    }

    private fun showChargerFilterDialog(plan: QonectaRoutePlanner.Plan?) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            setBackgroundColor(QonectaTheme.surface)
        }
        val dialog = AlertDialog.Builder(context).setView(container).create()

        var activeTab = 0 // 0 = Cargadores, 1 = Plan de carga
        val bodyScroll = ScrollView(context)
        val bodyCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        bodyScroll.addView(bodyCol)

        val tabsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun rebuildDialogBody() {
            tabsRow.removeAllViews()
            val tab0 = pillButton("⚡ Filtro de Cargadores", primary = activeTab == 0, small = true) {
                activeTab = 0
                rebuildDialogBody()
            }
            val tab1 = pillButton("🔋 Ajustes del Plan de Carga", primary = activeTab == 1, small = true) {
                activeTab = 1
                rebuildDialogBody()
            }
            tabsRow.addView(tab0)
            tabsRow.addView(tab1, spacedParams(dp(8)))
            tabsRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            tabsRow.addView(pillButton("Cerrar", primary = true, small = true) { dialog.dismiss() })

            bodyCol.removeAllViews()
            if (activeTab == 0) {
                // Potencia mínima
                bodyCol.addView(dialogSectionTitle("POTENCIA MÍNIMA DEL CARGADOR"))
                val kwRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                for (kw in QonectaRoutePlanner.MIN_KW_CHOICES) {
                    val label = if (kw == 0) "Cualquiera" else "≥ $kw kW"
                    val btn = pillButton(label, primary = chargerMinKw == kw, small = true) {
                        chargerMinKw = kw
                        saveFilterPrefs()
                        rebuildDialogBody()
                        buildUi()
                    }
                    kwRow.addView(btn, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
                }
                val kwScroll = HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(kwRow)
                }
                bodyCol.addView(kwScroll, LinearLayout.LayoutParams(MATCH, WRAP))

                // Voltaje
                bodyCol.addView(dialogSectionTitle("VOLTAJE EN CONTINUA (ARQUITECTURA C10)"))
                val vRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                for (v in QonectaRoutePlanner.VOLTS_CHOICES) {
                    val label = when (v) {
                        QonectaRoutePlanner.VOLTS_800 -> "800 V (≥ 150 kW / 800 V)"
                        QonectaRoutePlanner.VOLTS_400 -> "400–500 V"
                        else -> "Cualquiera"
                    }
                    val btn = pillButton(label, primary = chargerVolts == v, small = true) {
                        chargerVolts = v
                        saveFilterPrefs()
                        rebuildDialogBody()
                        buildUi()
                    }
                    vRow.addView(btn, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
                }
                bodyCol.addView(vRow, LinearLayout.LayoutParams(MATCH, WRAP))

                // Redes
                bodyCol.addView(dialogSectionTitle("REDES DE CARGA (NINGUNA MARCADA = TODAS)"))
                val allNetsRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, dp(8))
                }
                allNetsRow.addView(
                    pillButton("Todas las redes", primary = selectedNetworks.isEmpty(), small = true) {
                        selectedNetworks.clear()
                        saveFilterPrefs()
                        rebuildDialogBody()
                        buildUi()
                    },
                )
                bodyCol.addView(allNetsRow)

                val routeCounts = HashMap<String, Int>()
                plan?.chargers?.forEach { c ->
                    val k = c.network.ifEmpty { QonectaRoutePlanner.OTHER_NETWORK }
                    routeCounts[k] = (routeCounts[k] ?: 0) + 1
                }

                QonectaRoutePlanner.NETWORKS.chunked(4).forEach { rowNets ->
                    val r = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        weightSum = 4f
                    }
                    rowNets.forEach { net ->
                        val active = selectedNetworks.contains(net.key)
                        val count = routeCounts[net.key] ?: 0
                        val countSuffix = if (count > 0) " ($count)" else ""
                        val btn = pillButton("${net.initials} · ${net.label}$countSuffix", primary = active, small = true) {
                            if (!selectedNetworks.remove(net.key)) selectedNetworks.add(net.key)
                            saveFilterPrefs()
                            rebuildDialogBody()
                            buildUi()
                        }
                        r.addView(btn, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6); bottomMargin = dp(6) })
                    }
                    bodyCol.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
                }
            } else {
                // Pestaña Plan de Carga
                bodyCol.addView(dialogSectionTitle("BATERÍA MÍNIMA AL LLEGAR AL DESTINO"))
                val arrRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                for (pct in listOf(10, 15, 20, 25, 30)) {
                    arrRow.addView(
                        pillButton("$pct %", primary = arriveMinPct.roundToInt() == pct, small = true) {
                            arriveMinPct = pct.toDouble()
                            prefs.edit().putInt("plan_arrive_min_pct", pct).apply()
                            rebuildDialogBody()
                            buildUi()
                        },
                        LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) },
                    )
                }
                bodyCol.addView(arrRow)

                bodyCol.addView(dialogSectionTitle("BATERÍA MÍNIMA AL LLEGAR A CADA CARGADOR"))
                val stopRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                for (pct in listOf(5, 10, 15, 20)) {
                    stopRow.addView(
                        pillButton("$pct %", primary = stopMinPct.roundToInt() == pct, small = true) {
                            stopMinPct = pct.toDouble()
                            prefs.edit().putInt("plan_stop_min_pct", pct).apply()
                            rebuildDialogBody()
                            buildUi()
                        },
                        LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) },
                    )
                }
                bodyCol.addView(stopRow)

                bodyCol.addView(dialogSectionTitle("CARGAR EN PARADA HASTA COMO MUCHO"))
                val maxRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                for (pct in listOf(70, 75, 80, 85, 90)) {
                    maxRow.addView(
                        pillButton("$pct %", primary = maxChargePct.roundToInt() == pct, small = true) {
                            maxChargePct = pct.toDouble()
                            prefs.edit().putInt("plan_max_charge_pct", pct).apply()
                            rebuildDialogBody()
                            buildUi()
                        },
                        LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) },
                    )
                }
                bodyCol.addView(maxRow)
            }
        }

        rebuildDialogBody()
        container.addView(tabsRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
        container.addView(bodyScroll, LinearLayout.LayoutParams(MATCH, dp(360)))
        dialog.show()
    }

    private fun showNearbyChargersDialog() {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            setBackgroundColor(QonectaTheme.surface)
        }
        val dialog = AlertDialog.Builder(context).setView(container).create()
        var radiusKm = 15
        var minKw = 50
        val (origin, _) = QonectaRoutePlanner.resolveOrigin(context)

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(context).apply {
            text = "📍 Cargadores cerca de ${origin.name.take(24)}"
            setTextSp(19f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        topRow.addView(titleTv, LinearLayout.LayoutParams(0, WRAP, 1f))
        topRow.addView(pillButton("Cerrar", primary = true, small = true) { dialog.dismiss() })
        container.addView(topRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val filterBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val listCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val statusTv = TextView(context).apply {
            text = "Buscando electrolineras reales en un radio de $radiusKm km…"
            setTextSp(13.5f)
            setTextColor(QonectaTheme.accent)
            setPadding(0, 0, 0, dp(6))
        }

        fun loadNearby() {
            filterBar.removeAllViews()
            for (r in listOf(5, 15, 30, 50)) {
                filterBar.addView(
                    pillButton("$r km", primary = radiusKm == r, small = true) {
                        radiusKm = r
                        loadNearby()
                    },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) },
                )
            }
            for (k in listOf(0, 50, 100, 150)) {
                val label = if (k == 0) "Todos kW" else "≥$k kW"
                filterBar.addView(
                    pillButton(label, primary = minKw == k, small = true) {
                        minKw = k
                        loadNearby()
                    },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) },
                )
            }
            statusTv.text = "Consultando DGT y OpenStreetMap en $radiusKm km…"
            listCol.removeAllViews()
            val appCtx = context.applicationContext
            bgExecutor.execute {
                val all = QonectaRoutePlanner.findNearbyChargers(appCtx, origin.lat, origin.lon, radiusKm.toDouble(), fetchOverpass = true)
                    .filter { minKw == 0 || (it.maxKw >= minKw && !it.acOnly) }
                uiHandler.post {
                    statusTv.text = "${all.size} cargadores encontrados a menos de $radiusKm km:"
                    statusTv.setTextColor(QonectaTheme.dim)
                    listCol.removeAllViews()
                    all.take(40).forEach { c ->
                        listCol.addView(
                            buildOtherChargerRow(c, 0.0),
                            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) },
                        )
                    }
                }
            }
        }

        container.addView(filterBar, LinearLayout.LayoutParams(MATCH, WRAP))
        container.addView(statusTv)
        container.addView(ScrollView(context).apply { addView(listCol) }, LinearLayout.LayoutParams(MATCH, dp(330)))
        loadNearby()
        dialog.show()
    }

    private fun showFullRouteChargersDialog(
        plan: QonectaRoutePlanner.Plan,
        chargeResult: QonectaRoutePlanner.ChargeResult?,
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            setBackgroundColor(QonectaTheme.surface)
        }
        val dialog = AlertDialog.Builder(context).setView(container).create()
        var showAll = false

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(context).apply {
            text = "Cargadores en la ruta a ${plan.destination}"
            setTextSp(19f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        topRow.addView(titleTv, LinearLayout.LayoutParams(0, WRAP, 1f))
        topRow.addView(pillButton("Cerrar", primary = true, small = true) { dialog.dismiss() })
        container.addView(topRow)

        val toggleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val listCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun rebuildList() {
            toggleRow.removeAllViews()
            toggleRow.addView(
                pillButton("Con mi filtro", primary = !showAll, small = true) {
                    showAll = false
                    rebuildList()
                },
            )
            toggleRow.addView(
                pillButton("Todos (${plan.chargers.size})", primary = showAll, small = true) {
                    showAll = true
                    rebuildList()
                },
                spacedParams(dp(8)),
            )
            listCol.removeAllViews()
            val items = if (showAll) plan.chargers else filterChargers(plan.chargers)
            items.forEach { c ->
                listCol.addView(
                    buildOtherChargerRow(c, plan.currentKm),
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) },
                )
            }
        }
        rebuildList()
        container.addView(toggleRow)
        container.addView(ScrollView(context).apply { addView(listCol) }, LinearLayout.LayoutParams(MATCH, dp(350)))
        dialog.show()
    }

    // =========================================================================
    // FAVORITOS (Casa, Trabajo y 2 más)
    // =========================================================================

    private data class FavSlot(
        val key: String,
        val icon: String,
        val title: String,
        val fixedTitle: Boolean,
        val address: String,
        val lat: Double,
        val lon: Double,
    )

    private fun loadFavorites(): List<FavSlot> {
        return listOf(
            FavSlot(
                "home", "🏠", "Casa", true,
                prefs.getString("fav_addr_home", "").orEmpty(),
                prefs.getFloat("fav_lat_home", Float.NaN).toDouble(),
                prefs.getFloat("fav_lon_home", Float.NaN).toDouble(),
            ),
            FavSlot(
                "work", "💼", "Trabajo", true,
                prefs.getString("fav_addr_work", "").orEmpty(),
                prefs.getFloat("fav_lat_work", Float.NaN).toDouble(),
                prefs.getFloat("fav_lon_work", Float.NaN).toDouble(),
            ),
            FavSlot(
                "fav1", "📍", prefs.getString("fav_title_fav1", "Favorito 1").orEmpty().ifBlank { "Favorito 1" }, false,
                prefs.getString("fav_addr_fav1", "").orEmpty(),
                prefs.getFloat("fav_lat_fav1", Float.NaN).toDouble(),
                prefs.getFloat("fav_lon_fav1", Float.NaN).toDouble(),
            ),
            FavSlot(
                "fav2", "⭐", prefs.getString("fav_title_fav2", "Favorito 2").orEmpty().ifBlank { "Favorito 2" }, false,
                prefs.getString("fav_addr_fav2", "").orEmpty(),
                prefs.getFloat("fav_lat_fav2", Float.NaN).toDouble(),
                prefs.getFloat("fav_lon_fav2", Float.NaN).toDouble(),
            ),
        )
    }

    private fun startRouteToFavorite(slot: FavSlot) {
        if (!slot.lat.isNaN() && !slot.lon.isNaN()) {
            recalculateRoute(QonectaRoutePlanner.Place(slot.title, slot.address, slot.lat, slot.lon))
            return
        }
        val appCtx = context.applicationContext
        QonectaRouteTabState.routeLoading = true
        QonectaRouteTabState.statusMessage = "Buscando coordenadas de ${slot.title} (${slot.address})…"
        buildUi()
        bgExecutor.execute {
            val found = QonectaRoutePlanner.searchPlaces(slot.address, limit = 1).firstOrNull()
            uiHandler.post {
                if (found != null) {
                    prefs.edit()
                        .putFloat("fav_lat_${slot.key}", found.lat.toFloat())
                        .putFloat("fav_lon_${slot.key}", found.lon.toFloat())
                        .apply()
                    recalculateRoute(found.copy(name = "${slot.title} (${found.name})"))
                } else {
                    QonectaRouteTabState.routeLoading = false
                    QonectaRouteTabState.statusMessage = "No se encontró «${slot.address}»."
                    buildUi()
                }
            }
        }
    }

    private fun showFavoriteEditDialog(slot: FavSlot) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            setBackgroundColor(QonectaTheme.surface)
        }
        val dialog = AlertDialog.Builder(context).setView(container).create()
        container.addView(
            TextView(context).apply {
                text = "${slot.icon} Configurar ${slot.title}"
                setTextSp(19f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
        )
        var titleEdit: EditText? = null
        if (!slot.fixedTitle) {
            titleEdit = EditText(context).apply {
                setText(slot.title)
                hint = "Nombre del favorito"
                setTextColor(QonectaTheme.text)
                setHintTextColor(QonectaTheme.faint)
                setTextSp(15.5f)
            }
            container.addView(titleEdit, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        val addrEdit = EditText(context).apply {
            setText(slot.address)
            hint = "Dirección o ciudad (ej. Av. de América 12, Madrid)"
            setTextColor(QonectaTheme.text)
            setHintTextColor(QonectaTheme.faint)
            setTextSp(15.5f)
        }
        container.addView(addrEdit, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        row.addView(pillButton("Cancelar", primary = false, small = true) { dialog.dismiss() })
        row.addView(
            pillButton("Guardar e Ir ⚡", primary = true, small = true) {
                val addr = addrEdit.text?.toString()?.trim().orEmpty()
                val newTitle = titleEdit?.text?.toString()?.trim().orEmpty().ifBlank { slot.title }
                if (addr.isNotEmpty()) {
                    val ed = prefs.edit().putString("fav_addr_${slot.key}", addr)
                        .remove("fav_lat_${slot.key}")
                        .remove("fav_lon_${slot.key}")
                    if (!slot.fixedTitle) ed.putString("fav_title_${slot.key}", newTitle)
                    ed.apply()
                    dialog.dismiss()
                    startRouteToFavorite(slot.copy(title = newTitle, address = addr, lat = Double.NaN, lon = Double.NaN))
                }
            },
            spacedParams(dp(8)),
        )
        container.addView(row)
        dialog.show()
    }

    private fun showAllFavoritesDialog() {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            setBackgroundColor(QonectaTheme.surface)
        }
        val dialog = AlertDialog.Builder(context).setView(container).create()
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            TextView(context).apply {
                text = "★ Destinos Guardados (Casa, Trabajo y Favoritos)"
                setTextSp(19f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        top.addView(pillButton("Cerrar", primary = true, small = true) { dialog.dismiss() })
        container.addView(top, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })

        loadFavorites().forEach { slot ->
            val r = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(QonectaTheme.surfaceTop)
                    setStroke(dp(1), QonectaTheme.outline)
                }
            }
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(
                TextView(context).apply {
                    text = "${slot.icon} ${slot.title}"
                    setTextSp(15.5f)
                    setTextColor(QonectaTheme.text)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                },
            )
            col.addView(
                TextView(context).apply {
                    text = slot.address.ifBlank { "Sin configurar" }
                    setTextSp(13f)
                    setTextColor(if (slot.address.isNotBlank()) QonectaTheme.accent else QonectaTheme.dim)
                },
            )
            r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (slot.address.isNotBlank()) {
                r.addView(
                    pillButton("Ir ⚡", primary = true, small = true) {
                        dialog.dismiss()
                        startRouteToFavorite(slot)
                    },
                )
            }
            r.addView(
                pillButton("✎ Editar", primary = false, small = true) {
                    dialog.dismiss()
                    showFavoriteEditDialog(slot)
                },
                spacedParams(dp(6)),
            )
            container.addView(r, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }
        dialog.show()
    }

    // =========================================================================
    // HELPERS VISUALES Y FORMATEO
    // =========================================================================

    private fun saveFilterPrefs() {
        prefs.edit()
            .putInt("charger_min_kw", chargerMinKw)
            .putInt("charger_volts_filter", chargerVolts)
            .putString("charger_selected_networks", selectedNetworks.joinToString(","))
            .apply()
    }

    private fun buildFilterSummaryText(): String {
        val parts = mutableListOf<String>()
        if (chargerMinKw > 0) parts.add("≥ $chargerMinKw kW")
        if (chargerVolts == QonectaRoutePlanner.VOLTS_800) parts.add("800 V")
        else if (chargerVolts == QonectaRoutePlanner.VOLTS_400) parts.add("400 V")
        if (selectedNetworks.isNotEmpty()) {
            parts.add(selectedNetworks.joinToString(", ") { QonectaRoutePlanner.networkLabel(it) })
        }
        return if (parts.isEmpty()) "Todos los cargadores" else parts.joinToString(" · ")
    }

    private fun dialogSectionTitle(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSp(11.5f)
        setTextColor(QonectaTheme.faint)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.08f
        setPadding(0, dp(12), 0, dp(6))
    }

    private fun cardDrawable(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(QonectaTheme.surface)
        setStroke(dp(1), QonectaTheme.outline)
    }

    private fun pillButton(
        text: String,
        primary: Boolean,
        small: Boolean = false,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        this.text = text
        setTextSp(if (small) 12.5f else 14f)
        setTextColor(if (primary) QonectaTheme.onAccent else QonectaTheme.text)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        val padH = if (small) dp(12) else dp(16)
        val padV = if (small) dp(6) else dp(8)
        setPadding(padH, padV, padH, padV)
        val bg = if (primary) {
            QonectaTheme.accentGradientPill(dp(999).toFloat())
        } else {
            GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(QonectaTheme.surfaceTop)
                setStroke(dp(1), QonectaTheme.outline)
            }
        }
        background = RippleDrawable(ColorStateList.valueOf(0x3300E5C7), bg, null)
        setOnClickListener { onClick() }
    }

    private fun spacedParams(marginPx: Int = dp(8)): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = marginPx }

    private fun formatDuration(totalSec: Long): String {
        val mins = max(1L, (totalSec + 30L) / 60L)
        val h = mins / 60L
        val m = mins % 60L
        return if (h > 0L) "${h}h ${m}min" else "$m min"
    }

    // =========================================================================
    // VISTAS PERSONALIZADAS DE QONECTA+ (ChargerDot, Perfil por Pendiente, Anillo Batería, Empty State)
    // =========================================================================

    /**
     * Distintivo circular de cada cargador (`ChargerDot` de `RouteTab.java`):
     * Círculo con el color e iniciales de la red y punto de potencia en la esquina inferior derecha.
     */
    private class ChargerDotView(
        context: Context,
        private val kw: Double,
        private val networkKey: String?,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val badge = QonectaRoutePlanner.networkBadge(networkKey)
        private val powerColor = when {
            kw >= 150.0 -> QonectaTheme.green
            kw >= 50.0 -> QonectaTheme.accent
            else -> QonectaTheme.dim
        }

        override fun onDraw(canvas: Canvas) {
            val r = min(width, height) / 2f
            val cx = width / 2f
            val cy = height / 2f
            if (badge == null) {
                paint.style = Paint.Style.FILL
                paint.color = QonectaTheme.withAlpha(powerColor, 0x26)
                canvas.drawCircle(cx, cy, r * 0.92f, paint)
                paint.color = powerColor
                paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                paint.textSize = r * 0.95f
                paint.textAlign = Paint.Align.CENTER
                val fm = paint.fontMetrics
                canvas.drawText("⚡", cx, cy - (fm.ascent + fm.descent) / 2f, paint)
                return
            }
            paint.style = Paint.Style.FILL
            paint.color = badge.colorArgb
            canvas.drawCircle(cx, cy, r * 0.92f, paint)
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            paint.textSize = r * (if (badge.initials.length > 2) 0.62f else if (badge.initials.length > 1) 0.76f else 0.95f)
            paint.textAlign = Paint.Align.CENTER
            paint.color = if (badge.darkText) 0xFF15181C.toInt() else Color.WHITE
            val fm = paint.fontMetrics
            canvas.drawText(badge.initials, cx, cy - (fm.ascent + fm.descent) / 2f, paint)

            // Punto de potencia abajo a la derecha
            paint.color = QonectaTheme.surface
            canvas.drawCircle(cx + r * 0.66f, cy + r * 0.66f, r * 0.34f, paint)
            paint.color = powerColor
            canvas.drawCircle(cx + r * 0.66f, cy + r * 0.66f, r * 0.24f, paint)
        }
    }

    /**
     * Anillo de «Batería al llegar» (`paintBattery` de `RouteTab.java`).
     */
    private class QonectaArrivalBatteryCanvas(
        context: Context,
        private val socNow: Double,
        private val arrivalPct: Double,
        private val rawArrivalPct: Double,
        private val capKwh: Double,
        private val isRealSoc: Boolean,
        private val isReev: Boolean,
        private val chargeResult: QonectaRoutePlanner.ChargeResult?,
        private val compact: Boolean,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringRect = RectF()
        private val d = context.resources.displayMetrics.density * 1.30f

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 10f || h <= 10f) return

            // Título de la tarjeta
            paint.style = Paint.Style.FILL
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            paint.textSize = 11f * d
            paint.color = QonectaTheme.faint
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText("BATERÍA AL LLEGAR", 0f, 13f * d, paint)

            val rad = if (compact) min(46f * d, (h - 28f * d) / 2f) else min(64f * d, (h - 24f * d) / 2f)
            val cx = rad + 6f * d
            val cy = 20f * d + (h - 20f * d) / 2f
            ringRect.set(cx - rad, cy - rad, cx + rad, cy + rad)

            val col = when {
                arrivalPct.isNaN() -> QonectaTheme.dim
                arrivalPct >= 20.0 -> QonectaTheme.accent
                arrivalPct >= 8.0 -> QonectaTheme.amber
                else -> QonectaTheme.red
            }

            // Arco de fondo 270°
            val strokeW = (if (compact) 10f else 13f) * d
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeW
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = QonectaTheme.surfaceTop
            canvas.drawArc(ringRect, 135f, 270f, false, paint)

            // Arco de progreso
            if (!arrivalPct.isNaN() && arrivalPct > 0.0) {
                val sweep = (arrivalPct.coerceIn(0.0, 100.0) / 100.0 * 270.0).toFloat()
                paint.color = col
                canvas.drawArc(ringRect, 135f, sweep, false, paint)
            }

            // Valor dentro del aro
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            if (arrivalPct.isNaN()) {
                paint.textSize = (if (compact) 24f else 32f) * d
                paint.color = QonectaTheme.dim
                canvas.drawText("—", cx, cy + 8f * d, paint)
            } else {
                paint.textSize = (if (compact) 23f else 32f) * d
                paint.color = col
                canvas.drawText(String.format(Locale.getDefault(), "%.0f%%", max(0.0, arrivalPct)), cx, cy + 6f * d, paint)
                if (!compact && capKwh > 0.0) {
                    val arrKwh = max(0.0, arrivalPct) / 100.0 * capKwh
                    paint.textSize = 11.5f * d
                    paint.color = QonectaTheme.dim
                    canvas.drawText(String.format(Locale.getDefault(), "%.1f kWh", arrKwh), cx, cy + 24f * d, paint)
                }
            }
            paint.textSize = (if (compact) 9.5f else 11f) * d
            paint.color = QonectaTheme.faint
            canvas.drawText("AL LLEGAR", cx, cy + rad - 2f * d, paint)

            // Columna derecha: AHORA + % actual + kWh + Estado
            val tx = cx + rad + (if (compact) 14f else 22f) * d
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 11f * d
            paint.color = if (isRealSoc) QonectaTheme.accent else QonectaTheme.faint
            val srcLabel = if (isRealSoc) "AHORA · REAL C10" else "AHORA · INDICADO"
            canvas.drawText(srcLabel, tx, 34f * d, paint)

            val nowKwh = socNow / 100.0 * capKwh
            paint.textSize = (if (compact) 24f else 30f) * d
            paint.color = QonectaTheme.text
            val socStr = String.format(Locale.getDefault(), "%.0f %%", socNow)
            canvas.drawText(socStr, tx, 64f * d, paint)

            val socW = paint.measureText(socStr)
            paint.textSize = 13f * d
            paint.color = QonectaTheme.dim
            canvas.drawText(String.format(Locale.getDefault(), " (%.1f kWh)", nowKwh), tx + socW + 2f * d, 62f * d, paint)

            val (statusMsg, statusCol) = when {
                arrivalPct.isNaN() -> "Elige un destino para calcular la llegada" to QonectaTheme.dim
                isReev && rawArrivalPct < 20.0 -> {
                    val liters = (20.0 - rawArrivalPct) / 100.0 * capKwh / 3.1
                    String.format(Locale.getDefault(), "Generador REEV: ~%.1f L gasolina", max(0.1, liters)) to QonectaTheme.amber
                }
                chargeResult != null && chargeResult.outcome == QonectaRoutePlanner.Outcome.PLANNED -> {
                    val mins = chargeResult.chargeMinutes.roundToInt()
                    "Con $mins min de carga (${chargeResult.stops.size} par.)" to QonectaTheme.accent
                }
                arrivalPct < 0.0 -> "No llegas sin parar a cargar" to QonectaTheme.red
                arrivalPct < 15.0 -> "Llegas justo de batería (< 15 %)" to QonectaTheme.amber
                else -> "Llegas sin parar con buen margen" to QonectaTheme.green
            }
            paint.textSize = (if (compact) 11.5f else 13f) * d
            paint.color = statusCol
            val maxTextW = max(40f, w - tx - 4f * d)
            val ellipsized = TextUtils.ellipsize(
                statusMsg,
                android.text.TextPaint(paint),
                maxTextW,
                TextUtils.TruncateAt.END,
            ).toString()
            canvas.drawText(ellipsized, tx, 86f * d, paint)
        }
    }

    /**
     * Lienzo completo del perfil de ruta de Qonecta+ (`profile` + `wind` + `segments` + `legend` de `RouteTab.java`).
     */
    private class QonectaRouteProfileCanvas(
        context: Context,
        private val plan: QonectaRoutePlanner.Plan,
        private val chargeResult: QonectaRoutePlanner.ChargeResult?,
        private val filteredChargers: List<QonectaRoutePlanner.Charger>,
        private val socNow: Double,
        private val capacityKwh: Double,
        private val isReev: Boolean,
        private val compact: Boolean,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = context.resources.displayMetrics.density * 1.30f
        private val chartRect = RectF()
        private val segRect = RectF()
        private val tmpRect = RectF()
        private val socPath = Path()
        private val dashEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)

        // Bajada fuerte, bajada, llano, subida, subida fuerte (idéntico a SLOPE_COLORS de RouteTab.java)
        private val slopeColors = intArrayOf(
            0xFF3DDC84.toInt(),
            0xFF2FA86A.toInt(),
            0xFF5E7A96.toInt(),
            QonectaTheme.amber,
            QonectaTheme.red,
        )
        private val slopePaths = Array(5) { Path() }
        private val ridgePaths = Array(5) { Path() }
        private val slopeFills = arrayOfNulls<LinearGradient>(5)
        private var minE = 0f
        private var maxE = 1000f
        private var builtW = -1f
        private var builtH = -1f

        private fun xKm(km: Double, c: RectF): Float {
            return (c.left + (km / max(0.1, plan.totalKm)) * c.width()).toFloat()
        }

        private fun yElev(e: Double, c: RectF): Float {
            return (c.bottom - (e - minE) / max(1f, maxE - minE) * c.height()).toFloat()
        }

        private fun ensureSlopePaths(c: RectF) {
            if (abs(c.width() - builtW) < 1f && abs(c.height() - builtH) < 1f) return
            builtW = c.width()
            builtH = c.height()

            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            for (e in plan.elev) {
                lo = min(lo, e)
                hi = max(hi, e)
            }
            val span = max(60.0, hi - lo)
            minE = max(0.0, lo - span * 0.08).toFloat()
            maxE = (hi + span * 0.14).toFloat()

            for (p in slopePaths) p.rewind()
            for (p in ridgePaths) p.rewind()

            val windowKm = max(0.6, plan.totalKm / 60.0)
            val bucket = IntArray(plan.n)
            for (i in 1 until plan.n) {
                bucket[i] = computeSlopeBucket(i, windowKm)
            }
            val minRun = plan.totalKm / 90.0
            var from = 1
            for (i in 2..plan.n) {
                if (i < plan.n && bucket[i] == bucket[from]) continue
                if (from > 1 && plan.km[i - 1] - plan.km[from - 1] < minRun) {
                    for (k in from until i) bucket[k] = bucket[from - 1]
                }
                from = i
            }
            var run = -1
            var runStart = 0
            for (i in 1..plan.n) {
                val b = if (i < plan.n) bucket[i] else -2
                if (b != run) {
                    if (run >= 0) addSlopeRun(slopePaths[run], ridgePaths[run], runStart, i - 1, c)
                    run = b
                    runStart = i - 1
                }
            }
            for (b in slopeFills.indices) {
                val topAlpha = if (b == 2) 0x6B else 0x9E
                slopeFills[b] = LinearGradient(
                    0f,
                    c.top,
                    0f,
                    c.bottom,
                    QonectaTheme.withAlpha(slopeColors[b], topAlpha),
                    QonectaTheme.withAlpha(slopeColors[b], 0x0A),
                    Shader.TileMode.CLAMP,
                )
            }
        }

        private fun computeSlopeBucket(i: Int, windowKm: Double): Int {
            var a = i - 1
            var b = i
            while (a > 0 && plan.km[i] - plan.km[a] < windowKm / 2.0) a--
            while (b < plan.n - 1 && plan.km[b] - plan.km[i] < windowKm / 2.0) b++
            val dkm = plan.km[b] - plan.km[a]
            val grade = if (dkm > 0.0) (plan.elev[b] - plan.elev[a]) / (dkm * 1000.0) * 100.0 else 0.0
            return when {
                grade <= -1.0 -> 0
                grade <= -0.25 -> 1
                grade < 0.25 -> 2
                grade < 1.0 -> 3
                else -> 4
            }
        }

        private fun addSlopeRun(area: Path, ridge: Path, from: Int, to: Int, c: RectF) {
            area.moveTo(xKm(plan.km[from], c), c.bottom)
            ridge.moveTo(xKm(plan.km[from], c), yElev(plan.elev[from], c))
            for (k in from..to) {
                val x = xKm(plan.km[k], c)
                val y = yElev(plan.elev[k], c)
                area.lineTo(x, y)
                if (k > from) ridge.lineTo(x, y)
            }
            area.lineTo(xKm(plan.km[to], c), c.bottom)
            area.close()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 40f || h <= 60f || plan.n < 2) return

            val leftAxisW = 46f * d
            val bottomReserved = if (compact) 34f * d else 118f * d
            chartRect.set(leftAxisW, 14f * d, w - 6f * d, max(50f * d, h - bottomReserved))
            ensureSlopePaths(chartRect)

            // 1. Rejilla horizontal y cotas de altitud (m)
            paint.style = Paint.Style.FILL
            paint.color = QonectaTheme.outline
            for (k in 0..2) {
                val y = chartRect.top + chartRect.height() * k / 2f
                canvas.drawRect(chartRect.left, y - 0.5f, chartRect.right, y + 0.5f, paint)
            }
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            paint.textSize = 11f * d
            paint.color = QonectaTheme.faint
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(Locale.getDefault(), "%.0f m", maxE), chartRect.left - 6f * d, chartRect.top + 8f * d, paint)
            canvas.drawText(String.format(Locale.getDefault(), "%.0f m", minE), chartRect.left - 6f * d, chartRect.bottom, paint)

            // 2. Áreas y crestas coloreadas por pendiente
            paint.color = Color.WHITE
            for (b in slopePaths.indices) {
                paint.shader = slopeFills[b]
                canvas.drawPath(slopePaths[b], paint)
            }
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * d
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            for (b in ridgePaths.indices) {
                paint.color = if (b == 2) 0xFF9FB4C8.toInt() else slopeColors[b]
                canvas.drawPath(ridgePaths[b], paint)
            }
            paint.style = Paint.Style.FILL

            // 3. Marcadores de cargadores sobre el eje inferior del gráfico
            val plannedSet = chargeResult?.stops?.map { it.charger.stableKey }?.toSet().orEmpty()
            for (ch in filteredChargers) {
                val x = xKm(ch.kmAlong, chartRect)
                val cy = chartRect.bottom - 10f * d
                if (plannedSet.contains(ch.stableKey)) {
                    paint.color = QonectaTheme.withAlpha(QonectaTheme.accent, 0x60)
                    canvas.drawCircle(x, cy, 12f * d, paint)
                }
                paint.color = QonectaTheme.surfaceTop
                canvas.drawCircle(x, cy, 8f * d, paint)
                paint.color = if (ch.maxKw >= 100.0) QonectaTheme.green else QonectaTheme.accent
                canvas.drawCircle(x, cy, 4.5f * d, paint)
            }

            // 4. Curva de Batería Prevista a lo largo de la ruta (sube verticalmente en cada parada de carga)
            socPath.rewind()
            var lastSoc = socNow
            val planned = chargeResult != null && chargeResult.stops.isNotEmpty()
            var stopIdx = 0
            val prog = plan.progressIdx.coerceIn(0, plan.n - 1)
            for (i in prog until plan.n) {
                var sp = socNow - (plan.kwhCum[i] - plan.kwhCum[prog]) / max(10.0, capacityKwh) * 100.0
                if (isReev) sp = max(min(socNow, 20.0), sp)
                if (planned && chargeResult != null) {
                    while (stopIdx < chargeResult.stops.size && chargeResult.stops[stopIdx].km <= plan.km[i]) {
                        val st = chargeResult.stops[stopIdx++]
                        val xs = xKm(st.km, chartRect)
                        val yArr = (chartRect.bottom - st.arrivePct.coerceIn(0.0, 100.0) / 100.0 * chartRect.height()).toFloat()
                        val yDep = (chartRect.bottom - st.departPct.coerceIn(0.0, 100.0) / 100.0 * chartRect.height()).toFloat()
                        socPath.lineTo(xs, yArr)
                        socPath.lineTo(xs, yDep)
                    }
                    sp = chargeResult.pctAt(plan.km[i])
                }
                lastSoc = sp
                val x = xKm(plan.km[i], chartRect)
                val y = (chartRect.bottom - sp.coerceIn(0.0, 100.0) / 100.0 * chartRect.height()).toFloat()
                if (i == prog) socPath.moveTo(x, y) else socPath.lineTo(x, y)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.8f * d
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = QonectaTheme.accent
            canvas.drawPath(socPath, paint)
            paint.style = Paint.Style.FILL

            // Etiqueta de % al final de la curva SoC
            val ly = (chartRect.bottom - lastSoc.coerceIn(0.0, 100.0) / 100.0 * chartRect.height()).toFloat()
            val endLabel = String.format(Locale.getDefault(), "%.0f %%", max(0.0, lastSoc))
            paint.textSize = 12f * d
            val tw = paint.measureText(endLabel) + 12f * d
            tmpRect.set(chartRect.right - tw, max(chartRect.top, ly - 24f * d), chartRect.right, max(chartRect.top + 20f * d, ly - 4f * d))
            paint.color = QonectaTheme.surfaceTop
            canvas.drawRoundRect(tmpRect, 6f * d, 6f * d, paint)
            paint.color = if (lastSoc >= 20.0) QonectaTheme.accent else if (lastSoc >= 8.0) QonectaTheme.amber else QonectaTheme.red
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(endLabel, tmpRect.centerX(), tmpRect.bottom - 5f * d, paint)

            // Punto «Estás aquí» al inicio
            val xp = xKm(plan.km[prog], chartRect)
            val yp = yElev(plan.elev[prog], chartRect)
            paint.color = QonectaTheme.withAlpha(QonectaTheme.accent, 0x4D)
            canvas.drawCircle(xp, yp, 9f * d, paint)
            paint.color = QonectaTheme.accent
            canvas.drawCircle(xp, yp, 5f * d, paint)

            // Escala de km bajo el eje
            val stepKm = when {
                plan.totalKm > 300 -> 100.0
                plan.totalKm > 120 -> 50.0
                plan.totalKm > 40 -> 10.0
                else -> 5.0
            }
            paint.textSize = 10.5f * d
            paint.color = QonectaTheme.faint
            val unitLeft = chartRect.right - paint.measureText("km") - 8f * d
            var k = 0.0
            while (k <= plan.totalKm + 0.1) {
                val x = xKm(k, chartRect)
                val lbl = String.format(Locale.getDefault(), "%.0f", k)
                if (k > 0 && x + paint.measureText(lbl) / 2f > unitLeft) break
                paint.textAlign = if (k == 0.0) Paint.Align.LEFT else Paint.Align.CENTER
                canvas.drawText(lbl, x, chartRect.bottom + 14f * d, paint)
                k += stepKm
            }
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("km", chartRect.right, chartRect.bottom + 14f * d, paint)

            // 5. Franja de Viento por tramos (wy)
            val wy = chartRect.bottom + 20f * d
            for (i in 1 until plan.n) {
                val hw = plan.headwind[i]
                paint.color = when {
                    hw > 15.0 -> QonectaTheme.red
                    hw > 5.0 -> QonectaTheme.amber
                    hw < -5.0 -> 0xFF3B82F6.toInt()
                    else -> QonectaTheme.surfaceTop
                }
                canvas.drawRect(xKm(plan.km[i - 1], chartRect), wy, xKm(plan.km[i], chartRect) + 1f, wy + 5.5f * d, paint)
            }
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 10f * d
            paint.color = QonectaTheme.faint
            canvas.drawText("Viento", 0f, wy + 5.5f * d, paint)

            if (compact) return

            // 6. 12 Barras de consumo por tramo (segments() de RouteTab.java)
            val segCount = 12
            segRect.set(chartRect.left, wy + 14f * d, chartRect.right, h - 22f * d)
            val segLenKm = max(0.1, plan.totalKm / segCount)
            val vals = DoubleArray(segCount)
            var maxVal = 28.0
            for (j in 0 until segCount) {
                val a = QonectaRoutePlanner.kwhAt(plan.km, plan.kwhCum, j * segLenKm)
                val b = QonectaRoutePlanner.kwhAt(plan.km, plan.kwhCum, (j + 1) * segLenKm)
                vals[j] = (b - a) / segLenKm * 100.0
                maxVal = max(maxVal, vals[j])
            }
            // Línea media de referencia
            val avgKwh100 = if (plan.totalKm > 0.5) plan.kwhCum[plan.n - 1] / plan.totalKm * 100.0 else 16.8
            val yAvg = (segRect.bottom - (avgKwh100 / maxVal) * (segRect.height() - 12f * d)).toFloat()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * d
            paint.color = QonectaTheme.withAlpha(QonectaTheme.text, 0x88)
            paint.pathEffect = dashEffect
            canvas.drawLine(segRect.left, yAvg, segRect.right, yAvg, paint)
            paint.pathEffect = null
            paint.style = Paint.Style.FILL

            for (j in 0 until segCount) {
                val barH = (max(0.0, vals[j]) / maxVal * (segRect.height() - 14f * d)).toFloat()
                val x0 = xKm(j * segLenKm, segRect) + 2f * d
                val x1 = xKm((j + 1) * segLenKm, segRect) - 2f * d
                val col = when {
                    vals[j] < 15.0 -> QonectaTheme.green
                    vals[j] < 21.0 -> QonectaTheme.accent
                    vals[j] < 27.0 -> QonectaTheme.amber
                    else -> QonectaTheme.red
                }
                paint.color = QonectaTheme.withAlpha(col, 0xD8)
                tmpRect.set(x0, segRect.bottom - max(3f * d, barH), max(x0 + 2f, x1), segRect.bottom)
                canvas.drawRoundRect(tmpRect, 3.5f * d, 3.5f * d, paint)
                if (x1 - x0 >= 18f * d) {
                    paint.textAlign = Paint.Align.CENTER
                    paint.textSize = 10f * d
                    paint.color = QonectaTheme.dim
                    canvas.drawText(String.format(Locale.getDefault(), "%.0f", vals[j]), (x0 + x1) / 2f, tmpRect.top - 3f * d, paint)
                }
            }
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 10f * d
            paint.color = QonectaTheme.faint
            canvas.drawText("kWh/100", 0f, segRect.bottom - 4f * d, paint)

            // 7. Leyenda inferior (Subida, Bajada, Viento en contra, Viento a favor, Batería prevista)
            var lx = 0f
            val lyLegend = h - 3f * d
            lx = drawSwatch(canvas, lx, lyLegend, QonectaTheme.red, "Subida")
            lx = drawSwatch(canvas, lx, lyLegend, QonectaTheme.green, "Bajada")
            lx = drawSwatch(canvas, lx, lyLegend, QonectaTheme.amber, "Viento en contra")
            lx = drawSwatch(canvas, lx, lyLegend, 0xFF3B82F6.toInt(), "Viento a favor")
            drawSwatch(canvas, lx, lyLegend, QonectaTheme.accent, "Batería prevista (%)")
        }

        private fun drawSwatch(canvas: Canvas, x: Float, y: Float, color: Int, text: String): Float {
            paint.color = color
            tmpRect.set(x, y - 9f * d, x + 9f * d, y)
            canvas.drawRoundRect(tmpRect, 2.5f * d, 2.5f * d, paint)
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 10.5f * d
            paint.color = QonectaTheme.faint
            canvas.drawText(text, x + 13f * d, y - 1f * d, paint)
            return x + 13f * d + paint.measureText(text) + 14f * d
        }
    }

    /**
     * Ilustración del estado sin ruta de `RouteTab.java` (`paintEmpty`: carretera con línea discontinua + chincheta + CTA).
     */
    private class RouteEmptyStateCanvas(
        context: Context,
        private val loading: Boolean,
        private val statusText: String,
        private val originSummary: String,
        private val compact: Boolean,
        private val onSearchClicked: () -> Unit,
    ) : FrameLayout(context) {
        private val d = context.resources.displayMetrics.density * 1.30f

        init {
            setWillNotDraw(false)
            val cta = TextView(context).apply {
                text = if (loading) "⏳ Calculando ruta…" else "🔍 Buscar destino"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (compact) 15f else 18f) * d)
                setTextColor(QonectaTheme.onAccent)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding((28 * d).roundToInt(), (12 * d).roundToInt(), (28 * d).roundToInt(), (12 * d).roundToInt())
                background = QonectaTheme.accentGradientPill(999f)
                setOnClickListener { if (!loading) onSearchClicked() }
            }
            addView(
                cta,
                LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = (if (compact) 14 * d else 28 * d).roundToInt()
                },
            )
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val roadPath = Path()
        private val dash = DashPathEffect(floatArrayOf(12f, 12f), 0f)

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 20f || h <= 20f) return
            val cx = w / 2f
            val top = if (compact) 16f * d else max(24f * d, (h - 240f * d) / 2f)

            // Carretera curva con guiones hasta la chincheta
            roadPath.rewind()
            roadPath.moveTo(cx - 110f * d, top + (if (compact) 110f else 150f) * d)
            roadPath.cubicTo(
                cx - 30f * d,
                top + (if (compact) 105f else 140f) * d,
                cx - 10f * d,
                top + 45f * d,
                cx + 55f * d,
                top + 38f * d,
            )
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = (if (compact) 18f else 24f) * d
            paint.color = QonectaTheme.surfaceTop
            canvas.drawPath(roadPath, paint)

            paint.strokeWidth = 2.5f * d
            paint.color = QonectaTheme.dim
            paint.pathEffect = dash
            canvas.drawPath(roadPath, paint)
            paint.pathEffect = null
            paint.style = Paint.Style.FILL

            // Chincheta en el extremo de la carretera
            paint.color = QonectaTheme.accent
            canvas.drawCircle(cx + 55f * d, top + 24f * d, (if (compact) 12f else 16f) * d, paint)
            paint.color = QonectaTheme.surface
            canvas.drawCircle(cx + 55f * d, top + 24f * d, (if (compact) 5f else 6.5f) * d, paint)

            // Título «¿A dónde vamos?»
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            paint.textSize = (if (compact) 20f else 26f) * d
            paint.color = QonectaTheme.text
            val titleY = top + (if (compact) 142f else 192f) * d
            canvas.drawText(if (loading) "Calculando tu ruta…" else "¿A dónde vamos?", cx, titleY, paint)

            val sub = statusText.ifBlank {
                "Salida actual: $originSummary · Perfil de altitud Open-Meteo, viento, DGT + OpenStreetMap y paradas C10"
            }
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textSize = (if (compact) 12f else 14f) * d
            paint.color = if (statusText.startsWith("⚠")) QonectaTheme.amber else QonectaTheme.dim
            val maxW = max(100f, w - 48f * d)
            val ellipsized = TextUtils.ellipsize(sub, android.text.TextPaint(paint), maxW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(ellipsized, cx, titleY + (if (compact) 22f else 28f) * d, paint)
        }
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
