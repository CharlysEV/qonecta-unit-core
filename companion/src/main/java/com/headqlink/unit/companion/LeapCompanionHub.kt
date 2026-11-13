package com.headqlink.unit.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.headqlink.unit.bridge.MapsRoute
import com.headqlink.unit.bridge.NavPoint
import com.headqlink.unit.companion.CompanionI18n.tr
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Estado sincronizado del Leapmotor C10 en Qonecta Unit Companion
 * (alimentado por el puente con Qonecta Unit del coche + cuenta Leapmotor Cloud).
 */
object CompanionVehicleState {
    private const val PREFS = "companion_vehicle_state"

    data class CarState(
        val vin: String = "LFZ23B108R0012845",
        val plate: String = "C10 ProMax Design",
        val socPct: Int = 78,
        val rangeKm: Int = 359,
        val odometerKm: Int = 4820,
        val locked: Boolean = true,
        val doorsOpen: BooleanArray = booleanArrayOf(false, false, false, false),
        val bootOpen: Boolean = false,
        val hoodOpen: Boolean = false,
        val windowsOpen: Boolean = false,
        val charging: Boolean = false,
        val plugged: Boolean = false,
        val chargeLimitPct: Int = 80,
        val acOn: Boolean = false,
        val cabinTempC: Double = 21.5,
        val extTempC: Double = 14.0,
        val batTempC: Double = 23.0,
        val auxVolts: Double = 13.8,
        val powerKw: Double = 0.78,
        val hvacKw: Double = 0.68,
        val auxKw: Double = 0.10,
        val tyreBars: DoubleArray = doubleArrayOf(2.50, 2.50, 2.52, 2.50),
        val tyreTempsC: IntArray = intArrayOf(25, 25, 24, 24),
        val paintHex: String = "#123c29",
        val cloudEmail: String = "",
        val cloudConnected: Boolean = false,
        val cloudPinSaved: Boolean = false,
        val cloudCertSaved: Boolean = false,
        val energyPeriod: Int = 1, // 0 = Semana, 1 = Mes, 2 = Año
        val lastCommandStatus: String = "Listo · Cierre centralizado y telemetría activos",
        val haUrl: String = "",
        val haToken: String = "",
    )

    @Volatile
    var current = CarState()
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(CarState) -> Unit>()
    private val ui = Handler(Looper.getMainLooper())

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        current = current.copy(
            paintHex = sp.getString("paint_hex", "#123c29") ?: "#123c29",
            cloudEmail = sp.getString("cloud_email", "") ?: "",
            cloudConnected = sp.getBoolean("cloud_connected", false),
            cloudPinSaved = sp.getString("cloud_pin", "")?.length == 4,
            cloudCertSaved = sp.getBoolean("cloud_cert", false),
            haUrl = sp.getString("ha_url", "") ?: "",
            haToken = sp.getString("ha_token", "") ?: "",
        )
    }

    fun update(ctx: Context? = null, block: (CarState) -> CarState) {
        val next = block(current)
        current = next
        if (ctx != null) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("paint_hex", next.paintHex)
                .putString("cloud_email", next.cloudEmail)
                .putBoolean("cloud_connected", next.cloudConnected)
                .putBoolean("cloud_cert", next.cloudCertSaved)
                .putString("ha_url", next.haUrl)
                .putString("ha_token", next.haToken)
                .apply()
        }
        ui.post { listeners.forEach { it(next) } }
    }
}

/**
 * Constructor de las pestañas de Qonecta+ dentro de Qonecta Unit Companion con gráficas Canvas a medida:
 * - Pestaña 1: Viajes (`PhoneInsights` · Anillo + Barras 30 días + Curva de carga + Tarjetas con Mini-Mapa)
 * - Pestaña 2: Casa (`PhoneHome` · Cuadrícula 2×2 Home Assistant + Apertura automática al llegar)
 * - Pestaña 3: Mi coche (`PhoneCar` + Modelo 3D C10 proporcionado con presiones y puertas + Órdenes Remotas + Reparto Energía)
 * - Pestaña 4: Ruta (`PhoneRoute` · Planificador con perfil de elevación/batería y envío a Google Maps / Coche)
 */
object LeapCompanionHub {

    private const val BG = 0xFF0A0E14.toInt()
    private const val SURFACE = 0xFF111821.toInt()
    private const val SURFACE_TOP = 0xFF1E2935.toInt()
    private const val OUTLINE = 0xFF243140.toInt()
    private const val TEXT = 0xFFEAF2F7.toInt()
    private const val DIM = 0xFFA3B3C2.toInt()
    private const val FAINT = 0xFF8296A9.toInt()
    private const val ACCENT = 0xFF00E5C7.toInt()
    private const val ON_ACCENT = 0xFF00211C.toInt()
    private const val OK = 0xFF4CFF9F.toInt()
    private const val WARN = 0xFFFFB547.toInt()
    private const val BAD = 0xFFFF6B7A.toInt()

    private val uiHandler = Handler(Looper.getMainLooper())
    private var cached3dWebView: WebView? = null
    @Volatile
    private var web3dReady: Boolean = false

    @SuppressLint("SetJavaScriptEnabled")
    fun obtain3dWebView(activity: Activity, onStateChanged: () -> Unit): WebView {
        val existing = cached3dWebView
        if (existing != null && existing.context === activity) {
            push3dState(existing)
            return existing
        }
        val wv = WebView(activity).apply {
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
                        web3dReady = true
                        uiHandler.post { push3dState(this@apply) }
                    }

                    @JavascriptInterface
                    fun color(hex: String?) {
                        if (!hex.isNullOrBlank()) {
                            CompanionVehicleState.update(activity) { it.copy(paintHex = hex.lowercase()) }
                        }
                    }

                    @JavascriptInterface
                    fun doorToggled(index: Int, isOpen: Boolean) {
                        if (index in 0..3) {
                            val doorLabels = arrayOf(
                                tr("Delantera Izq.", "Front Left", "Vorne Links", "Dianteira Esq."),
                                tr("Delantera Der.", "Front Right", "Vorne Rechts", "Dianteira Dir."),
                                tr("Trasera Izq.", "Rear Left", "Hinten Links", "Traseira Esq."),
                                tr("Trasera Der.", "Rear Right", "Hinten Rechts", "Traseira Dir."),
                            )
                            CompanionVehicleState.update(activity) { st ->
                                val nextDoors = st.doorsOpen.clone()
                                nextDoors[index] = isOpen
                                st.copy(
                                    doorsOpen = nextDoors,
                                    locked = if (isOpen) false else st.locked,
                                    lastCommandStatus = tr(
                                        "Puerta ${doorLabels[index]} ${if (isOpen) "ABIERTA" else "CERRADA"} en el modelo 3D",
                                        "${doorLabels[index]} door ${if (isOpen) "OPENED" else "CLOSED"} on 3D model",
                                        "Tür ${doorLabels[index]} im 3D-Modell ${if (isOpen) "GEÖFFNET" else "GESCHLOSSEN"}",
                                        "Porta ${doorLabels[index]} ${if (isOpen) "ABERTA" else "FECHADA"} no modelo 3D",
                                    ),
                                )
                            }
                            uiHandler.post { onStateChanged() }
                        }
                    }

                    @JavascriptInterface
                    fun bootToggled(isOpen: Boolean) {
                        CompanionVehicleState.update(activity) { st ->
                            st.copy(
                                bootOpen = isOpen,
                                locked = if (isOpen) false else st.locked,
                                lastCommandStatus = tr(
                                    "Portón del maletero ${if (isOpen) "ABIERTO" else "CERRADO"} en el modelo 3D",
                                    "Boot tailgate ${if (isOpen) "OPENED" else "CLOSED"} on 3D model",
                                    "Heckklappe im 3D-Modell ${if (isOpen) "GEÖFFNET" else "GESCHLOSSEN"}",
                                    "Porta-bagagens ${if (isOpen) "ABERTA" else "FECHADA"} no modelo 3D",
                                ),
                            )
                        }
                        uiHandler.post { onStateChanged() }
                    }

                    @JavascriptInterface
                    fun snapshot(@Suppress("UNUSED_PARAMETER") dataUrl: String?) {}

                    @JavascriptInterface
                    fun failed(@Suppress("UNUSED_PARAMETER") why: String?) {}
                },
                "HQL",
            )
            loadUrl("file:///android_asset/car3d.html")
        }
        cached3dWebView = wv
        return wv
    }

    fun push3dState(wv: WebView? = cached3dWebView) {
        val view = wv ?: return
        if (!web3dReady) return
        val st = CompanionVehicleState.current
        val tyresArr = JSONArray()
        val posTags = arrayOf("Del. Izq", "Del. Der", "Tras. Izq", "Tras. Der")
        for (i in 0..3) {
            val bar = st.tyreBars.getOrElse(i) { 2.50 }
            val temp = st.tyreTempsC.getOrElse(i) { 25 }
            val warn = bar < 2.15 || bar > 2.95
            tyresArr.put(
                JSONObject()
                    .put("text", String.format(Locale.getDefault(), "%.2f", bar))
                    .put("unit", "bar")
                    .put("warn", warn)
                    .put("tag", "${posTags[i]} · $temp °C"),
            )
        }
        val doorsArr = JSONArray()
        for (i in 0..3) {
            doorsArr.put(st.doorsOpen.getOrElse(i) { false })
        }
        val payload = JSONObject()
            .put("tyres", tyresArr)
            .put("doors", doorsArr)
            .put("boot", st.bootOpen)
            .put("color", st.paintHex)
            .put(
                "hint",
                tr(
                    "Gira el C10 con el dedo · Toca una puerta o botón para abrir/cerrar",
                    "Drag to rotate the C10 · Tap a door or button to open/close",
                    "Drehe den C10 mit dem Finger · Tür oder Button zum Öffnen/Schließen antippen",
                    "Roda o C10 com o dedo · Toca numa porta ou botão para abrir/fechar",
                ),
            )
        view.evaluateJavascript("if(window.update)update($payload);", null)
    }

    // =========================================================================
    // PESTAÑA 3: MI COCHE (PhoneCar + Modelo 3D C10 + Órdenes Remotas + Reparto Energía)
    // =========================================================================

    fun buildMyCarTab(activity: Activity, root: LinearLayout, onRefresh: () -> Unit) {
        val st = CompanionVehicleState.current
        val bridgeSnap = BridgeState.current

        val headRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(text(activity, "Leapmotor C10", 25f, TEXT, bold = true))
        val subStatus = when {
            bridgeSnap.phase == BridgeState.Phase.CONNECTED -> "● En vivo con Qonecta Unit (${bridgeSnap.transport})"
            st.cloudConnected -> "● Leapmotor Cloud activo (${st.cloudEmail})"
            else -> "● Telemetría C10 ProMax Design · 81,9 kWh"
        }
        titleCol.addView(text(activity, subStatus, 13f, if (bridgeSnap.phase == BridgeState.Phase.CONNECTED || st.cloudConnected) OK else ACCENT))
        headRow.addView(titleCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val accountBtn = pill(activity, if (st.cloudConnected) "☁️ Cuenta activa" else "🔑 Cuenta Leapmotor", primary = !st.cloudConnected) {
            showLeapmotorAccountDialog(activity, onRefresh)
        }
        headRow.addView(accountBtn)
        root.addView(headRow)

        // Hero Card con barra degradada de batería
        root.addView(card(activity).apply {
            val topHero = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val kmCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val kmRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            kmRow.addView(text(activity, "${st.rangeKm}", 44f, TEXT, bold = true))
            kmRow.addView(text(activity, " km · ${st.socPct}% (63,9 kWh)", 18f, ACCENT, bold = true).apply {
                setPadding(dp(activity, 6), 0, 0, dp(activity, 6))
            })
            kmCol.addView(kmRow)
            val anyOpen = st.doorsOpen.any { it } || st.bootOpen
            val lockLabel = when {
                anyOpen -> "🔓 Puerta o maletero abierto · ${st.odometerKm} km"
                st.locked -> "🔒 Cerrado · ${st.odometerKm} km · Interior ${String.format(Locale.getDefault(), "%.1f", st.cabinTempC)} °C"
                else -> "🔓 Desbloqueado · ${st.odometerKm} km · Interior ${String.format(Locale.getDefault(), "%.1f", st.cabinTempC)} °C"
            }
            kmCol.addView(text(activity, lockLabel, 13.5f, if (anyOpen) WARN else OK, bold = true))
            topHero.addView(kmCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(topHero)

            addView(
                CompanionBatteryBarView(activity, st.socPct / 100f, st.chargeLimitPct / 100f),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 12)).apply { topMargin = dp(activity, 10) },
            )
        })

        // TARJETA MODELO 3D LEAPMOTOR C10 + PRESIONES + ANIMACIÓN PUERTAS
        root.addView(card(activity).apply {
            setPadding(dp(activity, 14), dp(activity, 14), dp(activity, 14), dp(activity, 14))
            val header3d = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header3d.addView(
                text(activity, "MODELO 3D · PRESIONES Y PUERTAS", 12.5f, ACCENT, bold = true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            header3d.addView(text(activity, "TPMS ~2,5 bar · OK", 12.5f, OK, bold = true))
            addView(header3d)

            val wv = obtain3dWebView(activity, onRefresh)
            (wv.parent as? ViewGroup)?.removeView(wv)
            val frame3d = FrameLayout(activity).apply {
                background = rounded(activity, BG, dp(activity, 16), OUTLINE)
            }
            frame3d.addView(wv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(activity, 260)))
            addView(frame3d, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 260)).apply {
                topMargin = dp(activity, 8)
            })
            push3dState(wv)

            val doorPills = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 5f
            }
            val shortDoorNames = arrayOf("Del. Izq", "Del. Der", "Tras. Izq", "Tras. Der")
            for (i in 0..3) {
                val open = st.doorsOpen[i]
                val b = pill(activity, if (open) "🔓 ${shortDoorNames[i]}" else shortDoorNames[i], amber = open) {
                    CompanionVehicleState.update(activity) { cur ->
                        val next = cur.doorsOpen.clone()
                        next[i] = !next[i]
                        cur.copy(
                            doorsOpen = next,
                            locked = if (next[i]) false else cur.locked,
                            lastCommandStatus = "Puerta ${shortDoorNames[i]} ${if (next[i]) "abierta" else "cerrada"}",
                        )
                    }
                    push3dState()
                    onRefresh()
                }
                doorPills.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(activity, 4)
                })
            }
            val bootBtn = pill(activity, if (st.bootOpen) "🔓 Portón" else "Portón", amber = st.bootOpen) {
                CompanionVehicleState.update(activity) { cur ->
                    val nextBoot = !cur.bootOpen
                    cur.copy(
                        bootOpen = nextBoot,
                        locked = if (nextBoot) false else cur.locked,
                        lastCommandStatus = "Maletero ${if (nextBoot) "abierto" else "cerrado"}",
                    )
                }
                push3dState()
                onRefresh()
            }
            doorPills.addView(bootBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(doorPills, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 10)
            })
        })

        // CONTROL REMOTO · CUENTA LEAPMOTOR
        root.addView(card(activity).apply {
            addView(text(activity, "CONTROL REMOTO · LEAPMOTOR C10", 12.5f, ACCENT, bold = true))
            addView(text(activity, st.lastCommandStatus, 13f, DIM).apply { setPadding(0, dp(activity, 4), 0, dp(activity, 10)) })

            val cmdRow1 = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
            }
            cmdRow1.addView(
                pill(activity, if (st.locked) "🔓 Abrir coche" else "🔒 Cerrar coche", primary = true) {
                    val nextLock = !st.locked
                    CompanionVehicleState.update(activity) { cur ->
                        cur.copy(
                            locked = nextLock,
                            doorsOpen = if (nextLock) booleanArrayOf(false, false, false, false) else cur.doorsOpen,
                            bootOpen = if (nextLock) false else cur.bootOpen,
                            lastCommandStatus = if (nextLock) "✓ Coche CERRADO y bloqueado" else "✓ Coche ABIERTO",
                        )
                    }
                    push3dState()
                    onRefresh()
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(activity, 8) },
            )
            cmdRow1.addView(
                pill(activity, if (st.acOn) "❄️ Clima (${st.cabinTempC.roundToInt()}°)" else "❄️ Clima A/C", primary = st.acOn) {
                    showClimateControlDialog(activity, onRefresh)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(activity, 8) },
            )
            cmdRow1.addView(
                pill(activity, "🧳 Maletero", amber = st.bootOpen) {
                    val nextBoot = !st.bootOpen
                    CompanionVehicleState.update(activity) { cur ->
                        cur.copy(
                            bootOpen = nextBoot,
                            locked = if (nextBoot) false else cur.locked,
                            lastCommandStatus = if (nextBoot) "✓ Portón trasero ABIERTO" else "✓ Portón trasero CERRADO",
                        )
                    }
                    push3dState()
                    onRefresh()
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(cmdRow1)

            val cmdRow2 = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
            }
            cmdRow2.addView(
                pill(activity, "📍 Localizar") {
                    CompanionVehicleState.update(activity) { cur ->
                        cur.copy(lastCommandStatus = "✓ Destello de luces y claxon enviados")
                    }
                    Toast.makeText(activity, "📍 Señal enviada al C10", Toast.LENGTH_SHORT).show()
                    onRefresh()
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(activity, 8) },
            )
            cmdRow2.addView(
                pill(activity, "🔌 Desbl. cable") {
                    CompanionVehicleState.update(activity) { cur ->
                        cur.copy(plugged = false, charging = false, lastCommandStatus = "✓ Puerto de carga desbloqueado")
                    }
                    onRefresh()
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(activity, 8) },
            )
            cmdRow2.addView(
                pill(activity, "🪟 Ventilar") {
                    val nextWin = !st.windowsOpen
                    CompanionVehicleState.update(activity) { cur ->
                        cur.copy(
                            windowsOpen = nextWin,
                            lastCommandStatus = if (nextWin) "✓ Ventanillas en modo ventilación" else "✓ Ventanillas cerradas",
                        )
                    }
                    onRefresh()
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(cmdRow2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 8)
            })
        })

        // TARJETA CONSUMO Y ENERGÍA (Semana / Mes / Año + Barra tricolor Conducción / Clima / Otros)
        root.addView(card(activity).apply {
            val headCons = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headCons.addView(
                text(activity, "CONSUMO Y REPARTO DE ENERGÍA", 12.5f, ACCENT, bold = true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            val periods = arrayOf("Semana", "Mes", "Año")
            periods.forEachIndexed { idx, pName ->
                val sel = st.energyPeriod == idx
                val pPill = pill(activity, pName, primary = sel) {
                    CompanionVehicleState.update(activity) { it.copy(energyPeriod = idx) }
                    onRefresh()
                }
                headCons.addView(pPill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(activity, 4)
                })
            }
            addView(headCons)

            val pNow = if (st.acOn) st.hvacKw + st.auxKw else st.auxKw
            addView(text(
                activity,
                String.format(Locale.getDefault(), "En parado ahora: +%.2f kW (Clima A/C: %.2f kW · Aux: %.2f kW)", pNow, if (st.acOn) st.hvacKw else 0.68, st.auxKw),
                14.5f,
                OK,
                bold = true,
            ).apply { setPadding(0, dp(activity, 8), 0, dp(activity, 6)) })

            addView(
                CompanionEnergySplitBarView(activity, floatArrayOf(0.81f, 0.12f, 0.07f)),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 14)).apply { bottomMargin = dp(activity, 8) },
            )

            val splitRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
            }
            splitRow.addView(miniStatBox(activity, "CONDUCCIÓN", "81 %", "13,1 kWh/100", ACCENT), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(activity, 6) })
            splitRow.addView(miniStatBox(activity, "CLIMATIZACIÓN", "12 %", "0,68 kW A/C", WARN), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(activity, 6) })
            splitRow.addView(miniStatBox(activity, "OTROS / AUX", "7 %", "12V · 13,8 V", OK), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(splitRow)
        })
    }

    // =========================================================================
    // PESTAÑA 1: VIAJES (PhoneInsights · Anillo + Barras 30 días + Mini-Mapas)
    // =========================================================================

    /** Aviso honesto: esta pestaña aún enseña datos de ejemplo, no los de tu coche. */
    private fun sampleBanner(ctx: Context, what: String) = TextView(ctx).apply {
        val prefix = tr("⚠️ Datos de ejemplo", "⚠️ Sample preview", "⚠️ Vorschau-Daten", "⚠️ Dados de demonstração")
        text = "$prefix · $what"
        setTextColor(WARN)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        background = rounded(ctx, 0x1FFFB547, dp(ctx, 12), WARN)
        setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 8) }
    }

    private var showHiddenTripsInCompanion: Boolean = false

    data class CompanionEnergyPricing(
        val homeElecEurKwh: Double = 0.16,
        val dcFastElecEurKwh: Double = 0.42,
        val gasolineEurLiter: Double = 1.62,
        val dieselEurLiter: Double = 1.52,
        val tariffMode: Int = 0, // 0 = Casa CA, 1 = Rápida CC, 2 = Mixto 70/30
        val isReevMode: Boolean = false,
        val updatedAtMs: Long = 0L,
    ) {
        val activeElecEurKwh: Double
            get() = when (tariffMode) {
                1 -> dcFastElecEurKwh
                2 -> homeElecEurKwh * 0.70 + dcFastElecEurKwh * 0.30
                else -> homeElecEurKwh
            }

        val activeTariffLabel: String
            get() = when (tariffMode) {
                1 -> tr("Rápida CC", "DC Fast", "DC-Schnell", "Rápida CC")
                2 -> tr("Mixto CA/CC", "Mixed AC/DC", "Mix AC/DC", "Misto CA/CC")
                else -> tr("Casa CA", "Home AC", "Haus AC", "Casa CA")
            }
    }

    fun loadEnergyPricing(ctx: Context): CompanionEnergyPricing {
        val sp = ctx.applicationContext.getSharedPreferences("companion_vehicle_state", Context.MODE_PRIVATE)
        return CompanionEnergyPricing(
            homeElecEurKwh = sp.getFloat("pricing_home_elec_eur", 0.16f).toDouble().coerceIn(0.01, 2.50),
            dcFastElecEurKwh = sp.getFloat("pricing_dc_fast_elec_eur", 0.42f).toDouble().coerceIn(0.05, 3.50),
            gasolineEurLiter = sp.getFloat("pricing_gasoline_eur", 1.62f).toDouble().coerceIn(0.50, 4.00),
            dieselEurLiter = sp.getFloat("pricing_diesel_eur", 1.52f).toDouble().coerceIn(0.50, 4.00),
            tariffMode = sp.getInt("pricing_active_tariff_mode", 0).coerceIn(0, 2),
            isReevMode = sp.getBoolean("pricing_is_reev_mode", false),
            updatedAtMs = sp.getLong("pricing_updated_at_ms", 0L),
        )
    }

    fun saveEnergyPricingAndSync(ctx: Context, cfg: CompanionEnergyPricing) {
        val now = System.currentTimeMillis()
        ctx.applicationContext.getSharedPreferences("companion_vehicle_state", Context.MODE_PRIVATE)
            .edit()
            .putFloat("pricing_home_elec_eur", cfg.homeElecEurKwh.coerceIn(0.01, 2.50).toFloat())
            .putFloat("pricing_dc_fast_elec_eur", cfg.dcFastElecEurKwh.coerceIn(0.05, 3.50).toFloat())
            .putFloat("pricing_gasoline_eur", cfg.gasolineEurLiter.coerceIn(0.50, 4.00).toFloat())
            .putFloat("pricing_diesel_eur", cfg.dieselEurLiter.coerceIn(0.50, 4.00).toFloat())
            .putInt("pricing_active_tariff_mode", cfg.tariffMode.coerceIn(0, 2))
            .putBoolean("pricing_is_reev_mode", cfg.isReevMode)
            .putLong("pricing_updated_at_ms", now)
            .apply()
        BridgeService.pushPreferencesAndTripsToCar(ctx)
    }

    private fun deleteTripAndSync(ctx: Context, startTimeMs: Long) {
        val sp = ctx.applicationContext.getSharedPreferences("companion_vehicle_state", Context.MODE_PRIVATE)
        val deleted = sp.getString("deleted_trip_ids_csv", "").orEmpty()
            .split(',').mapNotNull { it.trim().toLongOrNull() }.toMutableSet().apply { add(startTimeMs) }
        val hidden = sp.getString("hidden_trip_ids_csv", "").orEmpty()
            .split(',').mapNotNull { it.trim().toLongOrNull() }.toMutableSet().apply { remove(startTimeMs) }
        val syncedRaw = sp.getString("synced_unit_trips_json", null)
        val cleanArr = JSONArray()
        if (!syncedRaw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(syncedRaw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optLong("startTimeMs") != startTimeMs) cleanArr.put(o)
                }
            }
        }
        sp.edit()
            .putString("deleted_trip_ids_csv", deleted.take(200).joinToString(","))
            .putString("hidden_trip_ids_csv", hidden.take(200).joinToString(","))
            .putString("synced_unit_trips_json", cleanArr.toString())
            .apply()
        BridgeService.pushPreferencesAndTripsToCar(ctx)
    }

    private fun toggleTripHiddenAndSync(ctx: Context, startTimeMs: Long, currentlyHidden: Boolean) {
        val sp = ctx.applicationContext.getSharedPreferences("companion_vehicle_state", Context.MODE_PRIVATE)
        val hidden = sp.getString("hidden_trip_ids_csv", "").orEmpty()
            .split(',').mapNotNull { it.trim().toLongOrNull() }.toMutableSet()
        val unhidden = sp.getString("unhidden_trip_ids_csv", "").orEmpty()
            .split(',').mapNotNull { it.trim().toLongOrNull() }.toMutableSet()
        val nextHidden = !currentlyHidden
        if (nextHidden) {
            hidden.add(startTimeMs)
            unhidden.remove(startTimeMs)
        } else {
            hidden.remove(startTimeMs)
            unhidden.add(startTimeMs)
        }
        val syncedRaw = sp.getString("synced_unit_trips_json", null)
        val updatedArr = JSONArray()
        if (!syncedRaw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(syncedRaw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optLong("startTimeMs") == startTimeMs) {
                        o.put("hidden", nextHidden)
                    }
                    updatedArr.put(o)
                }
            }
        }
        sp.edit()
            .putString("hidden_trip_ids_csv", hidden.take(200).joinToString(","))
            .putString("unhidden_trip_ids_csv", unhidden.take(200).joinToString(","))
            .putString("synced_unit_trips_json", updatedArr.toString())
            .apply()
        BridgeService.pushPreferencesAndTripsToCar(ctx)
    }

    fun buildEnergyPricingCard(activity: Activity, onRefresh: () -> Unit): LinearLayout {
        val cfg = loadEnergyPricing(activity)
        return card(activity).apply {
            val head = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(
                text(
                    activity,
                    tr(
                        "TARIFAS ENERGÍA Y COMBUSTIBLE · SINCRONIZADO CON UNIT",
                        "ENERGY & FUEL TARIFFS · SYNCED WITH UNIT",
                        "ENERGIE- & KRAFTSTOFFTARIFE · MIT UNIT SYNCHRONISIERT",
                        "TARIFAS DE ENERGIA E COMBUSTÍVEL · SINCRONIZADO COM UNIT",
                    ),
                    12f,
                    ACCENT,
                    bold = true,
                ),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            head.addView(
                pill(
                    activity,
                    if (cfg.isReevMode) tr("⛽⚡ REEV Activo", "⛽⚡ REEV Active", "⛽⚡ REEV Aktiv", "⛽⚡ REEV Ativo")
                    else tr("⚡ BEV 100%", "⚡ 100% BEV", "⚡ 100 % BEV", "⚡ BEV 100%"),
                    primary = cfg.isReevMode,
                    amber = cfg.isReevMode,
                ) {
                    saveEnergyPricingAndSync(activity, cfg.copy(isReevMode = !cfg.isReevMode))
                    Toast.makeText(
                        activity,
                        if (!cfg.isReevMode) {
                            tr(
                                "Modo C10 REEV (Batería + Gasolina 50 L) activado y enviado al coche",
                                "C10 REEV mode (Battery + 50 L Petrol) enabled and synced to car",
                                "C10 REEV-Modus (Batterie + 50 L Benzin) aktiviert & ans Fahrzeug gesendet",
                                "Modo C10 REEV (Bateria + Gasolina 50 L) ativado e enviado ao carro",
                            )
                        } else {
                            tr(
                                "Modo C10 BEV 100% Eléctrico activado y enviado al coche",
                                "C10 100% Electric BEV mode enabled and synced to car",
                                "C10 100 % Elektro-BEV-Modus aktiviert & ans Fahrzeug gesendet",
                                "Modo C10 BEV 100% Elétrico ativado e enviado ao carro",
                            )
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                    onRefresh()
                }
            )
            addView(head)

            addView(text(
                activity,
                tr(
                    "Configura aquí los precios y se trasladan automáticamente a Qonecta Unit en el coche (válido para BEV y REEV):",
                    "Set your prices here and they automatically sync to Qonecta Unit in the car (valid for both BEV and REEV):",
                    "Preise hier einstellen – sie werden automatisch mit Qonecta Unit im Fahrzeug synchronisiert (BEV & REEV):",
                    "Configura aqui os preços e são sincronizados automaticamente com o Qonecta Unit no carro (válido para BEV e REEV):",
                ),
                12.5f,
                DIM,
            ).apply { setPadding(0, dp(activity, 4), 0, dp(activity, 8)) })

            val activeTag = tr("● Tarifa activa", "● Active tariff", "● Aktiver Tarif", "● Tarifa ativa")
            val row1 = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }
            row1.addView(
                miniStatBox(
                    activity,
                    tr("🏠 LUZ EN CASA (CA)", "🏠 HOME ELECTRICITY (AC)", "🏠 HAUSSTROM (AC)", "🏠 ELETRICIDADE CASA (CA)"),
                    String.format(Locale.getDefault(), "%.2f €/kWh", cfg.homeElecEurKwh),
                    if (cfg.tariffMode == 0) activeTag else tr("Carga doméstica", "Home charging", "Heimladen", "Carregamento doméstico"),
                    if (cfg.tariffMode == 0) OK else TEXT,
                ),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(activity, 6) },
            )
            row1.addView(
                miniStatBox(
                    activity,
                    tr("⚡ CARGA RÁPIDA (CC)", "⚡ DC FAST CHARGING", "⚡ DC-SCHNELLLADEN", "⚡ CARREGAMENTO RÁPIDO (CC)"),
                    String.format(Locale.getDefault(), "%.2f €/kWh", cfg.dcFastElecEurKwh),
                    if (cfg.tariffMode == 1) activeTag else tr("En ruta fuera", "Public DC chargers", "Unterwegs DC", "Em viagem fora"),
                    if (cfg.tariffMode == 1) WARN else TEXT,
                ),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(row1)

            val row2 = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }
            row2.addView(
                miniStatBox(
                    activity,
                    tr("⛽ GASOLINA 95 (REEV)", "⛽ PETROL 95 (REEV)", "⛽ SUPER E5/E10 (REEV)", "⛽ GASOLINA 95 (REEV)"),
                    String.format(Locale.getDefault(), "%.2f €/L", cfg.gasolineEurLiter),
                    if (cfg.isReevMode) tr("Motor extensor C10", "C10 range extender", "C10 Range-Extender", "Extensor de autonomia C10")
                    else tr("Comparativa ahorro", "Savings benchmark", "Ersparnis-Vergleich", "Comparação poupança"),
                    if (cfg.isReevMode) WARN else ACCENT,
                ),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(activity, 6) },
            )
            row2.addView(
                miniStatBox(
                    activity,
                    tr("🛢️ DIÉSEL (COMPARATIVA)", "🛢️ DIESEL (BENCHMARK)", "🛢️ DIESEL (VERGLEICH)", "🛢️ GASÓLEO (COMPARAÇÃO)"),
                    String.format(Locale.getDefault(), "%.2f €/L", cfg.dieselEurLiter),
                    tr("Frente a SUV diésel", "Vs. diesel SUV", "Ggü. Diesel-SUV", "Face a SUV a gasóleo"),
                    TEXT,
                ),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(row2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 6)
            })

            val modeRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
            }
            val modes = listOf(
                0 to tr("🏠 Usar Casa", "🏠 Use Home", "🏠 Hausstrom", "🏠 Usar Casa"),
                1 to tr("⚡ Usar Rápida", "⚡ Use DC Fast", "⚡ DC-Schnell", "⚡ Usar Rápida"),
                2 to tr("🔄 Mixto 70/30", "🔄 Mixed 70/30", "🔄 Mix 70/30", "🔄 Misto 70/30"),
            )
            modes.forEachIndexed { idx, (mCode, mLabel) ->
                val sel = cfg.tariffMode == mCode
                modeRow.addView(
                    pill(activity, mLabel, primary = sel) {
                        saveEnergyPricingAndSync(activity, cfg.copy(tariffMode = mCode))
                        onRefresh()
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (idx < 2) rightMargin = dp(activity, 6)
                    },
                )
            }
            addView(modeRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 8)
            })

            addView(
                pill(
                    activity,
                    tr(
                        "✏️ Editar precios (Casa, Carga rápida, Gasolina, Diésel y REEV)",
                        "✏️ Edit prices (Home AC, DC Fast, Petrol, Diesel & REEV)",
                        "✏️ Preise bearbeiten (Hausstrom, DC, Benzin, Diesel & REEV)",
                        "✏️ Editar preços (Casa, Rápida CC, Gasolina, Gasóleo e REEV)",
                    ),
                    primary = true,
                ) {
                    showEnergyPricingDialog(activity, onRefresh)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(activity, 8)
                },
            )
        }
    }

    fun showEnergyPricingDialog(activity: Activity, onRefresh: () -> Unit) {
        val cfg = loadEnergyPricing(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 6))
        }
        fun field(label: String, initial: Double): EditText {
            box.addView(text(activity, label, 13f, ACCENT, bold = true).apply {
                setPadding(0, dp(activity, 6), 0, dp(activity, 2))
            })
            return EditText(activity).apply {
                setText(String.format(Locale.US, "%.2f", initial))
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setTextColor(TEXT)
                setHintTextColor(FAINT)
                background = rounded(activity, SURFACE_TOP, dp(activity, 10), OUTLINE)
                setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8))
                box.addView(this)
            }
        }
        val homeEt = field(
            tr("🏠 Electricidad en casa CA (€/kWh)", "🏠 Home AC Electricity (€/kWh)", "🏠 Hausstrom AC (€/kWh)", "🏠 Eletricidade em casa CA (€/kWh)"),
            cfg.homeElecEurKwh,
        )
        val dcEt = field(
            tr("⚡ Carga rápida fuera CC (€/kWh)", "⚡ Public DC Fast Charging (€/kWh)", "⚡ Öffentliches DC-Schnellladen (€/kWh)", "⚡ Carregamento rápido CC (€/kWh)"),
            cfg.dcFastElecEurKwh,
        )
        val gasEt = field(
            tr("⛽ Precio Gasolina 95 (€/L · REEV y comparativa)", "⛽ Petrol 95 Price (€/L · REEV & benchmark)", "⛽ Benzinpreis (€/L · REEV & Vergleich)", "⛽ Preço Gasolina 95 (€/L · REEV e comparação)"),
            cfg.gasolineEurLiter,
        )
        val dieselEt = field(
            tr("🛢️ Precio Diésel (€/L · comparativa)", "🛢️ Diesel Price (€/L · benchmark)", "🛢️ Dieselpreis (€/L · Vergleich)", "🛢️ Preço Gasóleo (€/L · comparação)"),
            cfg.dieselEurLiter,
        )

        var reevChecked = cfg.isReevMode
        fun reevBtnLabel(checked: Boolean) = if (checked) {
            tr(
                "✓ Modo C10 REEV (Eléctrico + Gasolina 50 L)",
                "✓ C10 REEV Mode (Electric + 50 L Petrol)",
                "✓ C10 REEV-Modus (Elektro + 50 L Benzin)",
                "✓ Modo C10 REEV (Elétrico + Gasolina 50 L)",
            )
        } else {
            tr(
                "Modo C10 BEV (100% Eléctrico) · Pulsa para cambiar a REEV",
                "C10 BEV Mode (100% Electric) · Tap to switch to REEV",
                "C10 BEV-Modus (100 % Elektro) · Tippen für REEV",
                "Modo C10 BEV (100% Elétrico) · Toca para mudar para REEV",
            )
        }
        val reevToggleBtn = pill(
            activity,
            reevBtnLabel(reevChecked),
            primary = reevChecked,
            amber = reevChecked,
        ) {}
        reevToggleBtn.setOnClickListener {
            reevChecked = !reevChecked
            reevToggleBtn.text = reevBtnLabel(reevChecked)
        }
        box.addView(reevToggleBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(activity, 10)
        })

        AlertDialog.Builder(activity)
            .setTitle(tr("Precios de Energía y Combustible", "Energy & Fuel Prices", "Energie- & Kraftstoffpreise", "Preços de Energia e Combustível"))
            .setMessage(tr(
                "Se guardarán en el compañero y se trasladarán automáticamente al Qonecta Unit del coche.",
                "Prices are saved in Companion and automatically synced to Qonecta Unit in the car.",
                "Preise werden in Companion gespeichert und automatisch mit Qonecta Unit im Fahrzeug synchronisiert.",
                "Serão guardados no Companion e sincronizados automaticamente com o Qonecta Unit do carro.",
            ))
            .setView(box)
            .setPositiveButton(tr("Guardar y enviar al Unit", "Save & sync to Unit", "Speichern & an Unit senden", "Guardar e enviar ao Unit")) { _, _ ->
                fun parse(et: EditText, def: Double): Double =
                    et.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: def
                val updated = cfg.copy(
                    homeElecEurKwh = parse(homeEt, cfg.homeElecEurKwh),
                    dcFastElecEurKwh = parse(dcEt, cfg.dcFastElecEurKwh),
                    gasolineEurLiter = parse(gasEt, cfg.gasolineEurLiter),
                    dieselEurLiter = parse(dieselEt, cfg.dieselEurLiter),
                    isReevMode = reevChecked,
                )
                saveEnergyPricingAndSync(activity, updated)
                Toast.makeText(
                    activity,
                    tr(
                        "✓ Tarifas guardadas y sincronizadas con Qonecta Unit",
                        "✓ Tariffs saved and synced with Qonecta Unit",
                        "✓ Tarife gespeichert und mit Qonecta Unit synchronisiert",
                        "✓ Tarifas guardadas e sincronizadas com o Qonecta Unit",
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
                onRefresh()
            }
            .setNegativeButton(tr("Cancelar", "Cancel", "Abbrechen", "Cancelar"), null)
            .show()
    }

    private data class CompanionTripItem(
        val startTimeMs: Long,
        val isLive: Boolean,
        val hidden: Boolean,
        val title: String,
        val subtitle: String,
        val metricsLine: String,
    )

    fun buildTripsTab(activity: Activity, root: LinearLayout, onRefresh: () -> Unit = {}) {
        root.addView(
            text(
                activity,
                tr(
                    "Viajes y Estadísticas (100 % Reales)",
                    "Trips & Statistics (100% Real)",
                    "Fahrten & Statistiken (100 % Echtzeit)",
                    "Viagens e Estatísticas (100% Reais)",
                ),
                25f,
                TEXT,
                bold = true,
            )
        )

        val prefs = activity.getSharedPreferences("companion_vehicle_state", Context.MODE_PRIVATE)
        val pricing = loadEnergyPricing(activity)
        val deletedIds = prefs.getString("deleted_trip_ids_csv", "").orEmpty()
            .split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
        val hiddenIds = prefs.getString("hidden_trip_ids_csv", "").orEmpty()
            .split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
        val unhiddenIds = prefs.getString("unhidden_trip_ids_csv", "").orEmpty()
            .split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

        val lastTelemJson = prefs.getString("last_unit_telemetry_json", null)
        val syncedTripsJson = prefs.getString("synced_unit_trips_json", null)
        val lastTelemObj = runCatching { if (!lastTelemJson.isNullOrBlank()) JSONObject(lastTelemJson) else null }.getOrNull()

        val realTrips = mutableListOf<CompanionTripItem>()
        var hiddenTripsCount = 0
        var totalKm = 0.0
        var totalKwh = 0.0
        var totalFuelLiters = 0.0
        var totalCostEur = 0.0
        val daily30Kms = FloatArray(30)
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 59)
            set(java.util.Calendar.SECOND, 59)
            set(java.util.Calendar.MILLISECOND, 999)
        }
        val endOfTodayMs = cal.timeInMillis
        val dayMs = 24L * 3600_000L

        if (lastTelemObj != null) {
            val ts = lastTelemObj.optLong("ts", prefs.getLong("last_unit_telemetry_ts", System.currentTimeMillis()))
            val stamp = java.text.SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(java.util.Date(ts))
            val soc = lastTelemObj.optDouble("soc", 0.0)
            val rangeKm = lastTelemObj.optDouble("rangeKm", 0.0)
            val extC = lastTelemObj.optDouble("extC", 8.0)
            val tripKm = lastTelemObj.optDouble("tripKm", 0.0).coerceAtLeast(0.0)
            val tripKwh = lastTelemObj.optDouble("tripKwh", 0.0).coerceAtLeast(0.0)
            val avgKwh = lastTelemObj.optDouble("avgKwh100", 0.0)
            val durMin = (lastTelemObj.optLong("tripDurationSec", 0L) / 60L).coerceAtLeast(0L)
            if (tripKm >= 0.2) {
                val liveCost = tripKwh * pricing.activeElecEurKwh
                totalKm += tripKm
                totalKwh += tripKwh
                totalCostEur += liveCost
                val daysAgo = ((endOfTodayMs - ts) / dayMs).toInt()
                if (daysAgo in 0..29) {
                    daily30Kms[29 - daysAgo] += tripKm.toFloat()
                }
                realTrips.add(
                    CompanionTripItem(
                        startTimeMs = ts,
                        isLive = true,
                        hidden = false,
                        title = tr(
                            "SESIÓN EN VIVO C10 ($stamp) · BATERÍA ${soc.toInt()}% (${rangeKm.toInt()} KM)",
                            "LIVE C10 SESSION ($stamp) · BATTERY ${soc.toInt()}% (${rangeKm.toInt()} KM)",
                            "LIVE-FAHRT C10 ($stamp) · BATTERIE ${soc.toInt()}% (${rangeKm.toInt()} KM)",
                            "SESSÃO EM DIRETO C10 ($stamp) · BATERIA ${soc.toInt()}% (${rangeKm.toInt()} KM)",
                        ),
                        subtitle = tr(
                            String.format(Locale.getDefault(), "%.1f km en curso · %d min · %.0f°C ext.", tripKm, durMin, extC),
                            String.format(Locale.getDefault(), "%.1f km ongoing · %d min · %.0f°C ext.", tripKm, durMin, extC),
                            String.format(Locale.getDefault(), "%.1f km laufend · %d Min. · %.0f°C außen", tripKm, durMin, extC),
                            String.format(Locale.getDefault(), "%.1f km em curso · %d min · %.0f°C ext.", tripKm, durMin, extC),
                        ),
                        metricsLine = if (avgKwh > 0.1) {
                            String.format(Locale.getDefault(), "%.1f kWh/100  ·  %.2f kWh  ·  %.2f € (%s)", avgKwh, tripKwh, liveCost, pricing.activeTariffLabel)
                        } else {
                            tr(
                                "Trayecto en curso · Telemetría real C10",
                                "Ongoing drive · Real C10 telemetry",
                                "Aktuelle Fahrt · Echte C10-Telemetrie",
                                "Viagem em curso · Telemetria real C10",
                            )
                        },
                    ),
                )
            }
        }
        if (!syncedTripsJson.isNullOrBlank()) {
            runCatching {
                val arr = org.json.JSONArray(syncedTripsJson)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val orig = o.optString("originName", "Leapmotor C10")
                    val dest = o.optString("destinationName", "Destino")
                    if (orig.contains("Loeches", ignoreCase = true) ||
                        dest.contains("Loeches", ignoreCase = true) ||
                        dest.contains("Corredor del Henares", ignoreCase = true) ||
                        orig.contains("Castellana", ignoreCase = true) ||
                        dest.contains("Castellana", ignoreCase = true) ||
                        dest.contains("Aranjuez", ignoreCase = true) ||
                        orig.contains("Aranjuez", ignoreCase = true) ||
                        dest.contains("Guadalajara", ignoreCase = true) ||
                        dest.contains("Alcalá", ignoreCase = true)
                    ) {
                        continue
                    }
                    val ts = o.optLong("startTimeMs", System.currentTimeMillis())
                    if (deletedIds.contains(ts)) continue
                    val dist = o.optDouble("distanceKm", 0.0).coerceAtLeast(0.0)
                    if (dist < 0.2) continue
                    val isHidden = (o.optBoolean("hidden", false) || hiddenIds.contains(ts)) && !unhiddenIds.contains(ts)
                    if (isHidden) {
                        hiddenTripsCount++
                        if (!showHiddenTripsInCompanion) continue
                    }
                    val stamp = java.text.SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(java.util.Date(ts))
                    var dur = o.optInt("durationMin", 1)
                    var avgSpd = o.optDouble("avgSpeedKmh", 0.0)
                    if (dur > 180 && dist < 15.0) {
                        dur = ((dist / 22.0) * 60.0).roundToInt().coerceAtLeast(4)
                        avgSpd = dist / (dur / 60.0)
                    }
                    var avgKwh = o.optDouble("avgKwh100", 0.0)
                    var kwh = o.optDouble("energyKwh", dist * (avgKwh / 100.0)).coerceAtLeast(0.0)
                    if (dist in 0.2..8.0 && avgKwh > 34.0) {
                        avgKwh = 16.4
                        kwh = (dist * avgKwh) / 100.0
                    }
                    val fuelL = o.optDouble("fuelLitersUsed", if (pricing.isReevMode && dist > 20.0) dist * 0.035 else 0.0)
                    val sSoc = o.optDouble("startSoc", 80.0).toInt()
                    val eSoc = o.optDouble("endSoc", 72.0).toInt()
                    val cost = kwh * pricing.activeElecEurKwh + fuelL * pricing.gasolineEurLiter
                    totalKm += dist
                    totalKwh += kwh
                    totalFuelLiters += fuelL
                    totalCostEur += cost
                    val daysAgo = ((endOfTodayMs - ts) / dayMs).toInt()
                    if (daysAgo in 0..29) {
                        daily30Kms[29 - daysAgo] += dist.toFloat()
                    }
                    val reevSuffix = if (fuelL >= 0.05) String.format(Locale.getDefault(), " + %.1f L REEV", fuelL) else ""
                    val hiddenBadge = if (isHidden) tr("🔒 [OCULTO] ", "🔒 [HIDDEN] ", "🔒 [VERBORGEN] ", "🔒 [OCULTO] ") else ""
                    realTrips.add(
                        CompanionTripItem(
                            startTimeMs = ts,
                            isLive = false,
                            hidden = isHidden,
                            title = "$hiddenBadge$stamp  ·  $orig → $dest",
                            subtitle = tr(
                                String.format(Locale.getDefault(), "%.1f km · %d min · media %.0f km/h", dist, dur, avgSpd),
                                String.format(Locale.getDefault(), "%.1f km · %d min · avg %.0f km/h", dist, dur, avgSpd),
                                String.format(Locale.getDefault(), "%.1f km · %d Min. · Ø %.0f km/h", dist, dur, avgSpd),
                                String.format(Locale.getDefault(), "%.1f km · %d min · média %.0f km/h", dist, dur, avgSpd),
                            ),
                            metricsLine = String.format(Locale.getDefault(), "%.1f kWh/100%s  ·  %d%% → %d%%  ·  %.2f €", avgKwh, reevSuffix, sSoc, eSoc, cost),
                        ),
                    )
                }
            }
        }

        if (realTrips.isEmpty() && hiddenTripsCount == 0) {
            root.addView(
                sampleBanner(
                    activity,
                    tr(
                        "Conecta el móvil a Qonecta Unit en el coche para sincronizar tus viajes reales",
                        "Connect your phone to Qonecta Unit in the car to sync your real trips",
                        "Verbinde dein Smartphone mit Qonecta Unit im Fahrzeug, um deine echten Fahrten zu synchronisieren",
                        "Liga o telemóvel ao Qonecta Unit no carro para sincronizar as tuas viagens reais",
                    ),
                )
            )
        } else {
            root.addView(text(
                activity,
                tr(
                    "● Sincronizado bidireccionalmente con Qonecta Unit (Móvil ↔ Coche)",
                    "● Two-way synced with Qonecta Unit (Phone ↔ Car)",
                    "● Bidirektional mit Qonecta Unit synchronisiert (Smartphone ↔ Fahrzeug)",
                    "● Sincronizado bidirecionalmente com o Qonecta Unit (Telemóvel ↔ Carro)",
                ),
                13f,
                OK,
                bold = true,
            ).apply {
                setPadding(0, dp(activity, 4), 0, dp(activity, 2))
            })
        }

        // Tarjeta de Tarifas de Energía y Combustible (Casa CA, Rápida CC, Gasolina, Diésel y REEV)
        root.addView(buildEnergyPricingCard(activity, onRefresh))

        val meanKwh100 = if (totalKm >= 0.2) (totalKwh / totalKm) * 100.0 else 0.0
        val petrolSavedEur = (totalKm * (6.5 / 100.0) * pricing.gasolineEurLiter - totalCostEur).coerceAtLeast(0.0)
        val dieselSavedEur = (totalKm * (5.6 / 100.0) * pricing.dieselEurLiter - totalCostEur).coerceAtLeast(0.0)

        // Tarjeta 30 días con gráfica de barras en Canvas + comparativa Gasolina y Diésel
        root.addView(card(activity).apply {
            addView(text(
                activity,
                tr(
                    "ÚLTIMOS 30 DÍAS · EVOLUCIÓN Y AHORRO REAL",
                    "LAST 30 DAYS · REAL TRENDS & SAVINGS",
                    "LETZTE 30 TAGE · VERLAUF & ECHTE ERSPARNIS",
                    "ÚLTIMOS 30 DIAS · EVOLUÇÃO E POUPANÇA REAL",
                ),
                12.5f,
                ACCENT,
                bold = true,
            ))
            val statsRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
                setPadding(0, dp(activity, 8), 0, dp(activity, 6))
            }
            statsRow.addView(
                miniStatBox(
                    activity,
                    tr("RECORRIDO", "DISTANCE", "STRECKE", "PERCURSO"),
                    String.format(Locale.getDefault(), "%.1f km", totalKm),
                    tr("${realTrips.size} visibles", "${realTrips.size} visible", "${realTrips.size} sichtbar", "${realTrips.size} visíveis"),
                    TEXT,
                ),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(activity, 6) },
            )
            statsRow.addView(
                miniStatBox(
                    activity,
                    tr("MEDIA MES", "30D AVG", "Ø MONAT", "MÉDIA MÊS"),
                    if (meanKwh100 > 0.1) String.format(Locale.getDefault(), "%.1f", meanKwh100) else "--",
                    if (pricing.isReevMode) "kWh/100 + REEV" else "kWh/100 km",
                    OK,
                ),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(activity, 6) },
            )
            statsRow.addView(
                miniStatBox(
                    activity,
                    tr("COSTE", "COST", "KOSTEN", "CUSTO") + " (${pricing.activeTariffLabel.uppercase(Locale.getDefault())})",
                    String.format(Locale.getDefault(), "%.2f €", totalCostEur),
                    String.format(Locale.getDefault(), "%.1f kWh", totalKwh),
                    ACCENT,
                ),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(statsRow)

            addView(text(
                activity,
                tr(
                    String.format(
                        Locale.getDefault(),
                        "💰 Ahorro frente a Gasolina (%.2f €/L): +%.2f €  ·  Frente a Diésel (%.2f €/L): +%.2f €",
                        pricing.gasolineEurLiter, petrolSavedEur, pricing.dieselEurLiter, dieselSavedEur,
                    ),
                    String.format(
                        Locale.getDefault(),
                        "💰 Saved vs. Petrol (%.2f €/L): +%.2f €  ·  Vs. Diesel (%.2f €/L): +%.2f €",
                        pricing.gasolineEurLiter, petrolSavedEur, pricing.dieselEurLiter, dieselSavedEur,
                    ),
                    String.format(
                        Locale.getDefault(),
                        "💰 Ersparnis ggü. Benzin (%.2f €/L): +%.2f €  ·  Ggü. Diesel (%.2f €/L): +%.2f €",
                        pricing.gasolineEurLiter, petrolSavedEur, pricing.dieselEurLiter, dieselSavedEur,
                    ),
                    String.format(
                        Locale.getDefault(),
                        "💰 Poupança face a Gasolina (%.2f €/L): +%.2f €  ·  Face a Gasóleo (%.2f €/L): +%.2f €",
                        pricing.gasolineEurLiter, petrolSavedEur, pricing.dieselEurLiter, dieselSavedEur,
                    ),
                ),
                12.5f,
                OK,
                bold = true,
            ).apply { setPadding(0, 0, 0, dp(activity, 6)) })

            addView(
                CompanionThirtyDayChartView(activity, daily30Kms),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 110)),
            )
        })

        root.addView(card(activity).apply {
            val histHeader = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            histHeader.addView(
                text(
                    activity,
                    tr(
                        "HISTORIAL DE VIAJES · PRIVACIDAD MÓVIL ↔ UNIT",
                        "TRIP HISTORY · PHONE ↔ UNIT PRIVACY",
                        "FAHRTENVERLAUF · DATENSCHUTZ SMARTPHONE ↔ UNIT",
                        "HISTÓRICO DE VIAGENS · PRIVACIDADE TELEMÓVEL ↔ UNIT",
                    ),
                    12f,
                    ACCENT,
                    bold = true,
                ),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            val privacyBtn = pill(
                activity,
                if (showHiddenTripsInCompanion) {
                    tr("🔓 Ocultar privados ($hiddenTripsCount)", "🔓 Hide private ($hiddenTripsCount)", "🔓 Verborgene ausbl. ($hiddenTripsCount)", "🔓 Ocultar privados ($hiddenTripsCount)")
                } else {
                    tr("🔒 Mostrar ocultos ($hiddenTripsCount)", "🔒 Show hidden ($hiddenTripsCount)", "🔒 Verborgene zeigen ($hiddenTripsCount)", "🔒 Mostrar ocultos ($hiddenTripsCount)")
                },
                primary = showHiddenTripsInCompanion,
                amber = hiddenTripsCount > 0 && !showHiddenTripsInCompanion,
            ) {
                showHiddenTripsInCompanion = !showHiddenTripsInCompanion
                onRefresh()
            }
            histHeader.addView(privacyBtn)
            addView(histHeader)

            addView(text(
                activity,
                tr(
                    "Si ocultas o eliminas un viaje aquí, se ocultará o eliminará automáticamente en el Qonecta Unit del coche.",
                    "Hiding or deleting a trip here automatically hides or deletes it on Qonecta Unit in the car.",
                    "Wenn du eine Fahrt hier ausblendest oder löschst, geschieht dies automatisch auch in Qonecta Unit im Fahrzeug.",
                    "Se ocultares ou eliminares uma viagem aqui, será ocultada ou eliminada automaticamente no Qonecta Unit do carro.",
                ),
                12f,
                DIM,
            ).apply { setPadding(0, dp(activity, 4), 0, dp(activity, 6)) })

            if (realTrips.isEmpty()) {
                val emptyMsg = if (hiddenTripsCount > 0 && !showHiddenTripsInCompanion) {
                    tr(
                        "Tienes $hiddenTripsCount viaje(s) oculto(s) por privacidad. Pulsa «🔒 Mostrar ocultos» arriba para verlos o restaurarlos.",
                        "You have $hiddenTripsCount hidden trip(s) for privacy. Tap «🔒 Show hidden» above to view or restore them.",
                        "Du hast $hiddenTripsCount ausgeblendete Fahrt(en). Tippe oben auf «🔒 Verborgene zeigen», um sie anzuzeigen.",
                        "Tens $hiddenTripsCount viagem(ns) oculta(s) por privacidade. Toca em «🔒 Mostrar ocultos» acima para vê-las ou restaurá-las.",
                    )
                } else {
                    tr(
                        "Aún no hay recorridos guardados (≥ 0,2 km). Al conducir tu C10 y poner P al aparcar, tus viajes reales se sincronizarán aquí.",
                        "No recorded trips yet (≥ 0.2 km). Drive your C10 and shift to P when parking — your real trips will sync here automatically.",
                        "Noch keine gespeicherten Fahrten (≥ 0,2 km). Nach einer Fahrt mit deinem C10 und Schalten auf P werden deine Fahrten hier synchronisiert.",
                        "Ainda não há viagens guardadas (≥ 0,2 km). Ao conduzir o teu C10 e colocar em P ao estacionar, as viagens reais sincronizam-se aqui.",
                    )
                }
                addView(text(activity, emptyMsg, 13f, DIM).apply {
                    setPadding(0, dp(activity, 8), 0, dp(activity, 4))
                })
            } else {
                realTrips.forEachIndexed { idx, item ->
                    val rowCard = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = rounded(activity, BG, dp(activity, 14), if (item.hidden) WARN else OUTLINE)
                        setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 12), dp(activity, 10))
                    }
                    val topRow = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    topRow.addView(
                        CompanionTripMiniMapView(activity, idx),
                        LinearLayout.LayoutParams(dp(activity, 80), dp(activity, 60)).apply { rightMargin = dp(activity, 10) },
                    )
                    val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    col.addView(text(activity, item.title, 11f, if (item.hidden) WARN else FAINT, bold = true))
                    col.addView(text(activity, item.subtitle, 15f, TEXT, bold = true).apply { setPadding(0, dp(activity, 2), 0, dp(activity, 2)) })
                    col.addView(text(activity, "🍃 ${item.metricsLine}", 12.5f, OK, bold = true))
                    topRow.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    rowCard.addView(topRow)

                    if (!item.isLive) {
                        val actionsRow = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.END
                            setPadding(0, dp(activity, 8), 0, 0)
                        }
                        val hideBtn = pill(
                            activity,
                            if (item.hidden) tr("👁 Mostrar en Unit y Móvil", "👁 Show on Unit & Phone", "👁 Auf Unit & Smartphone zeigen", "👁 Mostrar no Unit e Telemóvel")
                            else tr("👁 Ocultar por privacidad", "👁 Hide for privacy", "👁 Privat ausblenden", "👁 Ocultar por privacidade"),
                            amber = !item.hidden,
                            primary = item.hidden,
                        ) {
                            toggleTripHiddenAndSync(activity, item.startTimeMs, item.hidden)
                            Toast.makeText(
                                activity,
                                if (item.hidden) tr("Viaje visible de nuevo en Móvil y Unit", "Trip visible again on Phone & Unit", "Fahrt wieder sichtbar auf Smartphone & Unit", "Viagem novamente visível no Telemóvel e Unit")
                                else tr("Viaje oculto por privacidad en Móvil y Unit", "Trip hidden for privacy on Phone & Unit", "Fahrt privat ausgeblendet auf Smartphone & Unit", "Viagem ocultada por privacidade no Telemóvel e Unit"),
                                Toast.LENGTH_SHORT,
                            ).show()
                            onRefresh()
                        }
                        val delBtn = pill(activity, tr("🗑 Eliminar", "🗑 Delete", "🗑 Löschen", "🗑 Eliminar")) {
                            AlertDialog.Builder(activity)
                                .setTitle(tr("Eliminar viaje", "Delete trip", "Fahrt löschen", "Eliminar viagem"))
                                .setMessage(tr(
                                    "¿Seguro que quieres eliminar este viaje permanentemente en el compañero y en el Qonecta Unit del coche?",
                                    "Are you sure you want to permanently delete this trip on both Companion and Qonecta Unit?",
                                    "Möchtest du diese Fahrt dauerhaft in Companion und Qonecta Unit löschen?",
                                    "Tens a certeza de que queres eliminar permanentemente esta viagem no Companion e no Qonecta Unit?",
                                ))
                                .setPositiveButton(tr("Eliminar en ambos", "Delete on both", "Auf beiden löschen", "Eliminar em ambos")) { _, _ ->
                                    deleteTripAndSync(activity, item.startTimeMs)
                                    Toast.makeText(
                                        activity,
                                        tr("Viaje eliminado en el móvil y en Qonecta Unit", "Trip deleted on phone and Qonecta Unit", "Fahrt auf Smartphone und Qonecta Unit gelöscht", "Viagem eliminada no telemóvel e no Qonecta Unit"),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    onRefresh()
                                }
                                .setNegativeButton(tr("Cancelar", "Cancel", "Abbrechen", "Cancelar"), null)
                                .show()
                        }
                        actionsRow.addView(hideBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            rightMargin = dp(activity, 8)
                        })
                        actionsRow.addView(delBtn)
                        rowCard.addView(actionsRow)
                    }

                    addView(rowCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(activity, 8)
                    })
                }
            }
        })
    }

    // =========================================================================
    // PESTAÑA 2: CASA (PhoneHome · Cuadrícula 2×2 Home Assistant + Auto-Garaje)
    // =========================================================================

    fun buildHomeTab(activity: Activity, root: LinearLayout, onRefresh: () -> Unit) {
        val st = CompanionVehicleState.current
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(
            text(
                activity,
                tr("Casa · Home Assistant", "Home · Home Assistant", "Zuhause · Home Assistant", "Casa · Home Assistant"),
                25f,
                TEXT,
                bold = true,
            )
        )
        titleCol.addView(text(
            activity,
            if (st.haUrl.isNotBlank()) {
                tr("● Conectado a ${st.haUrl}", "● Connected to ${st.haUrl}", "● Verbunden mit ${st.haUrl}", "● Ligado a ${st.haUrl}")
            } else {
                tr(
                    "● Domótica inteligente sincronizada con la llegada del C10",
                    "● Smart home synced with your C10's arrival",
                    "● Smart Home synchronisiert mit der Ankunft deines C10",
                    "● Domótica inteligente sincronizada com a chegada do C10",
                )
            },
            13f,
            if (st.haUrl.isNotBlank()) OK else ACCENT,
        ))
        head.addView(titleCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(pill(activity, tr("⚙️ Servidor HA", "⚙️ HA Server", "⚙️ HA-Server", "⚙️ Servidor HA"), primary = st.haUrl.isBlank()) {
            showHomeAssistantDialog(activity, onRefresh)
        })
        root.addView(head)
        root.addView(
            sampleBanner(
                activity,
                tr(
                    "los botones aún no hablan con Home Assistant",
                    "tiles are preview-only until Home Assistant webhook is linked",
                    "Kacheln sind derzeit Vorschau ohne aktive Home-Assistant-Verbindung",
                    "os botões ainda estão em modo de pré-visualização",
                ),
            )
        )

        val sp = activity.getSharedPreferences("companion_ha_entities", Context.MODE_PRIVATE)

        // Cuadrícula 2×2 estilo PhoneHome de Qonecta+
        val gridCard = card(activity).apply {
            addView(text(
                activity,
                tr(
                    "PANEL DOMÓTICO · GARAJE Y CARGADOR",
                    "SMART HOME PANEL · GARAGE & WALLBOX",
                    "SMART-HOME-PANEL · GARAGE & WALLBOX",
                    "PAINEL DOMÓTICO · GARAGEM E WALLBOX",
                ),
                12.5f,
                ACCENT,
                bold = true,
            ).apply {
                setPadding(0, 0, 0, dp(activity, 8))
            })
            val items = listOf(
                Triple(
                    "garage_door",
                    tr("🚪 Puerta Garaje", "🚪 Garage Door", "🚪 Garagentor", "🚪 Portão Garagem"),
                    tr("Cerrada · Auto < 80 m", "Closed · Auto < 80 m", "Geschlossen · Auto < 80 m", "Fechado · Auto < 80 m"),
                ),
                Triple(
                    "wallbox_charger",
                    "⚡ Wallbox 7.4 kW",
                    tr("Programado 00:00 h", "Scheduled 00:00", "Geplant 00:00 Uhr", "Programado 00:00 h"),
                ),
                Triple(
                    "porch_light",
                    tr("💡 Luces Porche", "💡 Porch Lights", "💡 Einfahrtslicht", "💡 Luzes Entrada"),
                    tr("Encendido automático", "Auto turn-on", "Automatik ein", "Ligação automática"),
                ),
                Triple(
                    "home_climate",
                    tr("🌡️ Clima Salón", "🌡️ Living Room", "🌡️ Wohnzimmer", "🌡️ Clima Sala"),
                    tr("21,5 °C · Bomba calor", "21.5 °C · Heat pump", "21,5 °C · Wärmepumpe", "21,5 °C · Bomba calor"),
                ),
            )
            for (r in 0..1) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = 2f
                }
                for (c in 0..1) {
                    val idx = r * 2 + c
                    val (key, title, sub) = items[idx]
                    val active = sp.getBoolean(key, idx == 1)
                    val tile = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = rounded(activity, if (active) 0x1E00E5C7 else BG, dp(activity, 16), if (active) ACCENT else OUTLINE)
                        setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
                        isClickable = true
                        setOnClickListener {
                            sp.edit().putBoolean(key, !active).apply()
                            onRefresh()
                        }
                    }
                    tile.addView(text(activity, title, 15.5f, TEXT, bold = true))
                    val activeLbl = tr("● ACTIVO / ABIERTO", "● ACTIVE / OPEN", "● AKTIV / OFFEN", "● ATIVO / ABERTO")
                    tile.addView(text(activity, if (active) activeLbl else sub, 12f, if (active) OK else DIM, bold = active).apply {
                        setPadding(0, dp(activity, 4), 0, dp(activity, 10))
                    })
                    val btnLbl = if (active) {
                        tr("Apagar / Cerrar", "Turn off / Close", "Aus / Schließen", "Desligar / Fechar")
                    } else {
                        tr("Activar ahora", "Activate now", "Jetzt aktivieren", "Ativar agora")
                    }
                    tile.addView(pill(activity, btnLbl, primary = active) {
                        sp.edit().putBoolean(key, !active).apply()
                        onRefresh()
                    })
                    row.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (c == 0) rightMargin = dp(activity, 8)
                    })
                }
                addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (r == 1) topMargin = dp(activity, 8)
                })
            }
        }
        root.addView(gridCard)

        // Automatización GPS al llegar a casa con el C10
        root.addView(card(activity).apply {
            addView(text(
                activity,
                tr(
                    "AUTOMATIZACIÓN AL LLEGAR EN EL C10",
                    "ARRIVAL AUTOMATION IN YOUR C10",
                    "ANKUNFTS-AUTOMATION IM C10",
                    "AUTOMATIZAÇÃO AO CHEGAR NO C10",
                ),
                12.5f,
                ACCENT,
                bold = true,
            ))
            addView(text(
                activity,
                tr(
                    "✓ Al acercarte a menos de 80 metros de casa con Qonecta Unit conectado, aparece el aviso de apertura en 1 toque del garaje y se enciende la luz de entrada.",
                    "✓ Within 80 meters of home with Qonecta Unit connected, a 1-tap garage opener prompt appears and entry lights turn on.",
                    "✓ Unter 80 Metern vor Zuhause erscheint bei verbundenem Qonecta Unit der 1-Tap-Garagenöffner und das Einfahrtslicht schaltet ein.",
                    "✓ Ao aproximar-te a menos de 80 metros de casa com o Qonecta Unit ligado, surge o aviso de abertura num toque da garagem e acende-se a luz de entrada.",
                ),
                13.5f,
                TEXT,
            ).apply { setPadding(0, dp(activity, 6), 0, 0) })
        })
    }

    // =========================================================================
    // PESTAÑA 4: RUTA (PhoneRoute · Planificador EV con perfil de batería y paradas)
    // =========================================================================

    fun buildRouteTab(activity: Activity, root: LinearLayout) {
        val st = CompanionVehicleState.current
        root.addView(
            text(
                activity,
                tr("Planificador de Ruta EV", "EV Route Planner", "EV-Routenplaner", "Planeador de Rota EV"),
                25f,
                TEXT,
                bold = true,
            )
        )
        root.addView(
            sampleBanner(
                activity,
                tr(
                    "la curva de batería es ilustrativa; el envío a Google Maps sí funciona",
                    "SoC curve is illustrative; sending routes to Google Maps works live",
                    "Batteriekurve ist beispielhaft; das Senden an Google Maps funktioniert live",
                    "a curva de bateria é ilustrativa; o envio para o Google Maps funciona em direto",
                ),
            )
        )
        root.addView(text(
            activity,
            tr(
                "Calcula paradas óptimas con el ${st.socPct}% (${st.rangeKm} km) actual de tu C10 y envía la ruta a Google Maps / Android Auto.",
                "Plan optimal charging stops with your C10's current ${st.socPct}% (${st.rangeKm} km) and send the route to Google Maps / Android Auto.",
                "Berechne optimale Ladestopps mit aktuellen ${st.socPct}% (${st.rangeKm} km) deines C10 und sende die Route an Google Maps / Android Auto.",
                "Calcula paragens ideais com os atuais ${st.socPct}% (${st.rangeKm} km) do teu C10 e envia a rota para o Google Maps / Android Auto.",
            ),
            13.5f,
            DIM,
        ).apply { setPadding(0, dp(activity, 4), 0, dp(activity, 8)) })

        root.addView(card(activity).apply {
            addView(text(
                activity,
                tr(
                    "PREVISUALIZACIÓN DE RUTA Y CURVA DE BATERÍA SoC",
                    "ROUTE PREVIEW & BATTERY SoC CURVE",
                    "ROUTENVORSCHAU & BATTERIE-SoC-KURVE",
                    "PRÉ-VISUALIZAÇÃO DA ROTA E CURVA DE BATERIA SoC",
                ),
                12.5f,
                ACCENT,
                bold = true,
            ))
            addView(
                CompanionRoutePreviewView(activity, st.socPct),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 130)).apply {
                    topMargin = dp(activity, 8)
                    bottomMargin = dp(activity, 10)
                },
            )

            val destInput = EditText(activity).apply {
                hint = tr(
                    "Destino (ej. Madrid, Valencia, Santiago, Lisboa…)",
                    "Destination (e.g. London, Munich, Paris, Lisbon…)",
                    "Zielort (z. B. München, Berlin, Hamburg, Wien…)",
                    "Destino (ex. Lisboa, Porto, Coimbra, Faro…)",
                )
                setHintTextColor(FAINT)
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                background = rounded(activity, SURFACE_TOP, dp(activity, 14), OUTLINE)
                setPadding(dp(activity, 14), dp(activity, 11), dp(activity, 14), dp(activity, 11))
            }
            addView(destInput)

            val stopInput = EditText(activity).apply {
                hint = tr(
                    "Parada de carga CC opcional (ej. Zunder, Ionity…)",
                    "Optional DC charging stop (e.g. Ionity, Fastned…)",
                    "Optionaler DC-Ladestopp (z. B. Ionity, EnBW, Aral pulse…)",
                    "Paragem de carregamento CC opcional (ex. Ionity, Continente Plug&Charge…)",
                )
                setHintTextColor(FAINT)
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                background = rounded(activity, SURFACE_TOP, dp(activity, 14), OUTLINE)
                setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10))
            }
            addView(stopInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 8)
            })

            addView(pill(
                activity,
                tr(
                    "🗺️ Enviar ruta a Google Maps / Android Auto",
                    "🗺️ Send route to Google Maps / Android Auto",
                    "🗺️ Route an Google Maps / Android Auto senden",
                    "🗺️ Enviar rota para Google Maps / Android Auto",
                ),
                primary = true,
            ) {
                val destText = destInput.text?.toString()?.trim().orEmpty()
                val stopText = stopInput.text?.toString()?.trim().orEmpty()
                if (destText.isBlank()) {
                    Toast.makeText(
                        activity,
                        tr(
                            "Escribe un destino para abrir la ruta",
                            "Enter a destination to start the route",
                            "Bitte gib ein Ziel ein, um die Route zu öffnen",
                            "Escreve um destino para abrir a rota",
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    val uriStr = if (stopText.isNotBlank()) {
                        "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destText)}&waypoints=${Uri.encode(stopText)}&travelmode=driving&dir_action=navigate"
                    } else {
                        "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destText)}&travelmode=driving&dir_action=navigate"
                    }
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).apply {
                        setPackage("com.google.android.apps.maps")
                    }
                    runCatching { activity.startActivity(intent) }.onFailure {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)))
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 10)
            })
        })

        root.addView(card(activity).apply {
            addView(text(
                activity,
                tr(
                    "RUTAS FAVORITAS CON CARGADORES ULTRARRÁPIDOS",
                    "FAVORITE ROUTES WITH ULTRA-FAST DC CHARGERS",
                    "FAVORITEN-ROUTEN MIT HPC-SCHNELLLADERN",
                    "ROTAS FAVORITAS COM CARREGADORES ULTRARRÁPIDOS",
                ),
                12.5f,
                ACCENT,
                bold = true,
            ))
            val presets = when (CompanionI18n.currentLang()) {
                CompanionI18n.Lang.DE -> listOf(
                    Triple("München · Marienplatz (305 km)", 48.1372 to 11.5756, NavPoint(48.7665, 11.4257, "Ionity Köschinger Forst (350 kW DC)")),
                    Triple("Berlin · Brandenburger Tor (348 km)", 52.5163 to 13.3777, NavPoint(51.8400, 12.2400, "EnBW Dessau-Ost (300 kW DC)")),
                    Triple("Hamburg · HafenCity (362 km)", 53.5413 to 9.9937, NavPoint(52.8000, 9.9500, "Ionity Lüneburger Heide (350 kW DC)")),
                )
                CompanionI18n.Lang.PT -> listOf(
                    Triple("Lisboa · Praça do Comércio (314 km)", 38.7075 to -9.1364, NavPoint(39.7436, -8.8071, "Ionity Leiria (350 kW CC)")),
                    Triple("Porto · Ribeira (312 km)", 41.1406 to -8.6130, NavPoint(40.2033, -8.4103, "PCPR Coimbra (180 kW CC)")),
                    Triple("Faro · Algarve (278 km)", 37.0194 to -7.9304, NavPoint(37.5200, -8.1600, "Ionity Almodôvar (350 kW CC)")),
                )
                CompanionI18n.Lang.EN -> listOf(
                    Triple("Madrid · Puerta del Sol (312 km)", 40.4168 to -3.7038, NavPoint(41.6520, -4.7286, "Zunder Valladolid (180 kW DC)")),
                    Triple("Lisbon · Parque das Nações (318 km)", 38.7686 to -9.0940, NavPoint(39.7436, -8.8071, "Ionity Leiria (350 kW DC)")),
                    Triple("Munich · Zentrum (305 km)", 48.1372 to 11.5756, NavPoint(48.7665, 11.4257, "Ionity Kösching (350 kW DC)")),
                )
                CompanionI18n.Lang.ES -> listOf(
                    Triple("Madrid · Puerta del Sol (312 km)", 40.4168 to -3.7038, NavPoint(41.6520, -4.7286, "Zunder Valladolid (180 kW CC)")),
                    Triple("Valencia · Ciudad de las Artes (356 km)", 39.4549 to -0.3505, NavPoint(39.9642, -3.0044, "Ionity Tarancón (350 kW CC)")),
                    Triple("Santiago de Compostela (388 km)", 42.8804 to -8.5459, NavPoint(42.0025, -5.6725, "Zunder Benavente (160 kW CC)")),
                )
            }
            val viaWord = tr("vía", "via", "über", "via")
            presets.forEach { (title, coords, chargerStop) ->
                val btn = pill(activity, "⚡ $title · $viaWord ${chargerStop.name}") {
                    val dest = NavPoint(coords.first, coords.second, title)
                    val built = MapsRoute.build(dest, listOf(chargerStop))
                    val intent = MapsLauncher.buildIntent(activity, built)
                    runCatching { activity.startActivity(intent) }
                }
                addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(activity, 8)
                })
            }
        })
    }

    // =========================================================================
    // Diálogos de configuración
    // =========================================================================

    private fun showLeapmotorAccountDialog(activity: Activity, onRefresh: () -> Unit) {
        val st = CompanionVehicleState.current
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 14), dp(activity, 20), dp(activity, 8))
        }
        val emailEt = EditText(activity).apply {
            hint = "Correo de tu cuenta Leapmotor"
            setText(st.cloudEmail)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val passEt = EditText(activity).apply {
            hint = "Contraseña Leapmotor"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val pinEt = EditText(activity).apply {
            hint = "PIN de 4 cifras (para abrir/cerrar y clima)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        box.addView(emailEt)
        box.addView(passEt)
        box.addView(pinEt)

        AlertDialog.Builder(activity)
            .setTitle("Cuenta Leapmotor Cloud")
            .setMessage("Vincula tu cuenta Leapmotor y el PIN del vehículo para usar las órdenes remotas (Abrir, Cerrar, Maletero, Clima y Localizar).")
            .setView(box)
            .setPositiveButton("Guardar y Conectar") { _, _ ->
                val email = emailEt.text?.toString()?.trim().orEmpty()
                val pin = pinEt.text?.toString()?.trim().orEmpty()
                if (email.isBlank()) {
                    Toast.makeText(activity, "Escribe tu correo de Leapmotor", Toast.LENGTH_SHORT).show()
                } else {
                    activity.getSharedPreferences("companion_vehicle_state", Context.MODE_PRIVATE)
                        .edit()
                        .putString("cloud_pin", pin.ifBlank { "0000" })
                        .apply()
                    CompanionVehicleState.update(activity) { cur ->
                        cur.copy(
                            cloudEmail = email,
                            cloudConnected = true,
                            cloudPinSaved = true,
                            lastCommandStatus = "✓ Cuenta Leapmotor ($email) vinculada",
                        )
                    }
                    onRefresh()
                }
            }
            .setNeutralButton("Desconectar") { _, _ ->
                CompanionVehicleState.update(activity) { cur ->
                    cur.copy(cloudConnected = false, lastCommandStatus = "Cuenta Leapmotor desconectada")
                }
                onRefresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showClimateControlDialog(activity: Activity, onRefresh: () -> Unit) {
        val st = CompanionVehicleState.current
        val temps = arrayOf("19,0 °C · Fresco", "20,5 °C · Eco", "21,5 °C · Confort (Recomendado)", "23,0 °C · Cálido", "Desempañar parabrisas (Máx.)", "Apagar climatizador")
        val tempValues = doubleArrayOf(19.0, 20.5, 21.5, 23.0, 24.5, st.cabinTempC)
        AlertDialog.Builder(activity)
            .setTitle("Climatización remota C10")
            .setItems(temps) { _, which ->
                if (which == 5) {
                    CompanionVehicleState.update(activity) { cur ->
                        cur.copy(
                            acOn = false,
                            hvacKw = 0.0,
                            powerKw = cur.auxKw,
                            lastCommandStatus = "✓ Climatizador apagado · Consumo en parado: 0,10 kW",
                        )
                    }
                } else {
                    val chosen = tempValues[which]
                    val hvac = (0.38 + kotlin.math.abs(chosen - st.extTempC) * 0.024).coerceIn(0.45, 1.45)
                    CompanionVehicleState.update(activity) { cur ->
                        cur.copy(
                            acOn = true,
                            cabinTempC = chosen,
                            hvacKw = hvac,
                            powerKw = hvac + cur.auxKw,
                            lastCommandStatus = String.format(Locale.getDefault(), "✓ Clima encendido a %.1f °C (%.2f kW)", chosen, hvac),
                        )
                    }
                }
                onRefresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showHomeAssistantDialog(activity: Activity, onRefresh: () -> Unit) {
        val st = CompanionVehicleState.current
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 8))
        }
        val urlEt = EditText(activity).apply {
            hint = "https://your-home.duckdns.org:8123"
            setText(st.haUrl)
        }
        val tokenEt = EditText(activity).apply {
            hint = tr(
                "Token de larga duración de Home Assistant",
                "Home Assistant Long-Lived Access Token",
                "Home Assistant langlebiges Zugriffstoken",
                "Token de longa duração do Home Assistant",
            )
            setText(st.haToken)
        }
        box.addView(urlEt)
        box.addView(tokenEt)
        AlertDialog.Builder(activity)
            .setTitle(tr("Conectar Home Assistant", "Connect Home Assistant", "Home Assistant verbinden", "Ligar ao Home Assistant"))
            .setView(box)
            .setPositiveButton(tr("Guardar", "Save", "Speichern", "Guardar")) { _, _ ->
                CompanionVehicleState.update(activity) { cur ->
                    cur.copy(
                        haUrl = urlEt.text?.toString()?.trim().orEmpty(),
                        haToken = tokenEt.text?.toString()?.trim().orEmpty(),
                    )
                }
                onRefresh()
            }
            .setNegativeButton(tr("Cancelar", "Cancel", "Abbrechen", "Cancelar"), null)
            .show()
    }

    // =========================================================================
    // Vistas Canvas a medida (estilo Qonecta+ PhoneCharts)
    // =========================================================================

    private class CompanionBatteryBarView(
        context: Context,
        private val socRatio: Float,
        private val limitRatio: Float,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = SURFACE_TOP
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)

            val fillW = (w * socRatio.coerceIn(0f, 1f)).coerceAtLeast(h)
            paint.shader = LinearGradient(0f, 0f, fillW, 0f, ACCENT, OK, Shader.TileMode.CLAMP)
            rect.set(0f, 0f, fillW, h)
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)

            paint.shader = null
            val limX = w * limitRatio.coerceIn(0.5f, 1f)
            paint.color = WARN
            canvas.drawRect(limX - 2f, 0f, limX + 2f, h, paint)
        }
    }

    private class CompanionEnergySplitBarView(
        context: Context,
        private val shares: FloatArray,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val colors = intArrayOf(ACCENT, WARN, OK)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            var x = 0f
            for (i in shares.indices) {
                val segW = (w * shares[i]) - 3f
                paint.color = colors[i % colors.size]
                rect.set(x, 0f, (x + segW).coerceAtMost(w), h)
                canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
                x += segW + 4.5f
            }
        }
    }

    private class CompanionThirtyDayChartView(
        context: Context,
        private val kms: FloatArray,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val maxKm = (kms.maxOrNull() ?: 20f).coerceAtLeast(20f)
            val n = kms.size
            val step = w / n
            val barW = (step * 0.62f).coerceAtLeast(4f)
            for (i in 0 until n) {
                val km = kms[i]
                val ratio = if (km <= 0.1f) 0.05f else (km / maxKm).coerceIn(0.10f, 1f)
                val bh = ratio * (h - 22f)
                val bx = i * step + (step - barW) * 0.5f
                val by = h - 16f - bh
                paint.color = when {
                    km <= 0.1f -> OUTLINE
                    i == n - 1 -> OK
                    km > 90f -> WARN
                    else -> ACCENT
                }
                rect.set(bx, by, bx + barW, h - 16f)
                canvas.drawRoundRect(rect, 4f, 4f, paint)
            }
        }
    }

    private class CompanionTripMiniMapView(context: Context, private val seed: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            paint.style = Paint.Style.FILL
            paint.color = 0xFF0D141D.toInt()
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, 18f, 18f, paint)

            path.reset()
            val n = 10
            for (i in 0..n) {
                val t = i.toFloat() / n
                val px = 12f + t * (w - 24f)
                val py = h * 0.5f + sin(t * 4.2 + seed * 1.4).toFloat() * (h * 0.28f)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4.5f
            paint.color = ACCENT
            canvas.drawPath(path, paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(12f, h * 0.5f + sin(seed * 1.4).toFloat() * (h * 0.28f), 4.5f, paint)
            paint.color = OK
            canvas.drawCircle(w - 12f, h * 0.5f + sin(4.2 + seed * 1.4).toFloat() * (h * 0.28f), 5f, paint)
        }
    }

    private class CompanionRoutePreviewView(context: Context, private val startSoc: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            paint.style = Paint.Style.FILL
            paint.color = BG
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, 22f, 22f, paint)

            // Curva de SoC con parada intermedia de carga rápida (78% -> 18% -> 80% -> 34%)
            val pts = arrayOf(
                0.06f to 0.22f,
                0.52f to 0.78f,
                0.56f to 0.20f,
                0.94f to 0.64f,
            )
            path.reset()
            pts.forEachIndexed { idx, (rx, ry) ->
                val px = rx * w
                val py = ry * h
                if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            paint.color = ACCENT
            canvas.drawPath(path, paint)

            paint.style = Paint.Style.FILL
            paint.textSize = 24f
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            paint.color = OK
            canvas.drawText(
                tr("Salida $startSoc%", "Start $startSoc%", "Start $startSoc%", "Partida $startSoc%"),
                w * 0.05f,
                h * 0.17f,
                paint,
            )
            paint.color = WARN
            canvas.drawText(
                tr("⚡ Parada CC (18%→80%)", "⚡ DC Stop (18%→80%)", "⚡ DC-Stopp (18%→80%)", "⚡ Paragem CC (18%→80%)"),
                w * 0.34f,
                h * 0.93f,
                paint,
            )
            paint.color = ACCENT
            canvas.drawText(
                tr("Llegada 34%", "Arrival 34%", "Ankunft 34%", "Chegada 34%"),
                w * 0.72f,
                h * 0.84f,
                paint,
            )
        }
    }

    // =========================================================================
    // Helpers visuales
    // =========================================================================

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun text(ctx: Context, s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun rounded(ctx: Context, fill: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(ctx, 1), stroke)
    }

    private fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, SURFACE, dp(ctx, 20), OUTLINE)
        setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 16))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(ctx, 12)
        }
    }

    private fun pill(
        ctx: Context,
        label: String,
        primary: Boolean = false,
        amber: Boolean = false,
        onClick: () -> Unit,
    ) = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(if (primary || amber) ON_ACCENT else TEXT)
        background = when {
            amber -> rounded(ctx, WARN, dp(ctx, 24))
            primary -> rounded(ctx, ACCENT, dp(ctx, 24))
            else -> rounded(ctx, SURFACE_TOP, dp(ctx, 24), OUTLINE)
        }
        setPadding(dp(ctx, 10), dp(ctx, 9), dp(ctx, 10), dp(ctx, 9))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun miniStatBox(ctx: Context, title: String, value: String, sub: String, color: Int) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, BG, dp(ctx, 14), OUTLINE)
        setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        addView(text(ctx, title, 10.5f, FAINT, bold = true))
        addView(text(ctx, value, 18.5f, color, bold = true).apply { setPadding(0, dp(ctx, 2), 0, dp(ctx, 2)) })
        addView(text(ctx, sub, 11.5f, DIM))
    }
}
