package com.headqlink.unit.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.headqlink.unit.bridge.BridgeMessage
import com.headqlink.unit.bridge.MapsRoute
import com.headqlink.unit.bridge.NavPoint

/**
 * Pantalla del Companion: estado de la conexión con el coche, emparejamiento en 2 pasos y permisos.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_ASSOCIATE = 10
        const val REQ_PERMS = 11
        const val REQ_SAVE_APK_USB = 12

        const val BG = 0xFF0A0E14.toInt()
        const val SURFACE = 0xFF111821.toInt()
        const val SURFACE_TOP = 0xFF1E2935.toInt()
        const val OUTLINE = 0xFF243140.toInt()
        const val TEXT = 0xFFEAF2F7.toInt()
        const val DIM = 0xFFA3B3C2.toInt()
        const val FAINT = 0xFF8296A9.toInt()
        const val ACCENT = 0xFF00E5C7.toInt()
        const val ON_ACCENT = 0xFF00211C.toInt()
        const val OK = 0xFF4CFF9F.toInt()
        const val WARN = 0xFFFFB547.toInt()
        const val BAD = 0xFFFF6B7A.toInt()
    }

    private lateinit var prefs: CompanionPrefs
    private lateinit var root: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var scrollView: ScrollView
    private var pendingUsbExportApk: java.io.File? = null
    private var draftPairCode: String = ""
    private var activeTab: Int = 0 // Pestaña principal simplificada de Conexión y Actualización en 1 toque
    private var activeTutorialDialog: InstallTutorialDialog? = null
    private val stateListener: (BridgeState.Snapshot) -> Unit = { s ->
        // Quién manda: conectado a Qonecta Unit → el coche; desconectado → la cuenta Leapmotor.
        com.headqlink.unit.companion.cloud.CompanionCloud.setConnectedToUnit(s.phase == BridgeState.Phase.CONNECTED)
        render()
    }
    private val cloudListener: (com.headqlink.unit.companion.cloud.CompanionCloud.Snapshot) -> Unit = { snap ->
        // La Ruta y los textos generales usan la batería real de la cuenta (no la de ejemplo).
        snap.data?.let { d ->
            if (!d.socPct.isNaN() && !d.rangeKm.isNaN()) {
                CompanionVehicleState.update { it.copy(socPct = d.socPct.toInt(), rangeKm = d.rangeKm.toInt()) }
            }
        }
        if (activeTab == 3) render()
    }
    private val unitListener: (UnitTelemetry.Data?) -> Unit = {
        if (activeTab in setOf(0, 1, 3, 4)) render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = CompanionPrefs(this)
        CompanionVehicleState.load(this)
        com.headqlink.unit.companion.cloud.CompanionCloud.init(this)
        if (!prefs.isPaired) {
            activeTab = 0
        }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        scrollView = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
        val scroll = scrollView
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll.addView(root)
        outer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 5f
            setBackgroundColor(SURFACE)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        outer.addView(bottomBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // Android 15 dibuja la app bajo la barra de estado y la de gestos: se deja su hueco arriba y abajo.
        outer.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, 0)
            @Suppress("DEPRECATION")
            bottomBar.setPadding(dp(6), dp(6), dp(6), dp(6) + insets.systemWindowInsetBottom)
            insets
        }
        setContentView(outer)
    }

    override fun onResume() {
        super.onResume()
        BridgeState.addListener(stateListener)
        com.headqlink.unit.companion.cloud.CompanionCloud.addListener(cloudListener)
        UnitTelemetry.addListener(unitListener)
        com.headqlink.unit.companion.cloud.CompanionCloud.startPolling()
        if (prefs.isPaired && prefs.enabled && BridgeState.current.phase == BridgeState.Phase.STOPPED) {
            BridgeService.start(this)
        }
        if (!GitHubReleaseUpdater.isHosting) {
            GitHubReleaseUpdater.findBestLocalCarApk(this)?.let { apk ->
                GitHubReleaseUpdater.startHostingApkForCar(this, apk) { render() }
            }
        }
        render()
    }

    override fun onPause() {
        BridgeState.removeListener(stateListener)
        com.headqlink.unit.companion.cloud.CompanionCloud.removeListener(cloudListener)
        UnitTelemetry.removeListener(unitListener)
        com.headqlink.unit.companion.cloud.CompanionCloud.stopPolling()
        super.onPause()
    }

    // ------------------------------------------------------------------------------------------
    // Pantalla
    // ------------------------------------------------------------------------------------------

    private fun renderBottomNav() {
        bottomBar.removeAllViews()
        val tabs = listOf(
            0 to ("🔗" to "Conexión"),
            1 to ("📊" to "Viajes"),
            2 to ("🏠" to "Casa"),
            3 to ("🚗" to "Coche"),
            4 to ("🗺️" to "Ruta"),
        )
        tabs.forEach { (idx, pair) ->
            val selected = activeTab == idx
            val tabBtn = TextView(this).apply {
                // Icono arriba y nombre abajo, en una sola línea que se encoge si el texto del móvil es grande.
                text = "${pair.first}\n${pair.second}"
                gravity = Gravity.CENTER
                maxLines = 2
                setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1, TypedValue.COMPLEX_UNIT_SP)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(if (selected) ON_ACCENT else DIM)
                background = if (selected) rounded(ACCENT, dp(16)) else rounded(Color.TRANSPARENT, dp(16))
                setPadding(dp(2), dp(6), dp(2), dp(6))
                isClickable = true
                setOnClickListener {
                    if (activeTab != idx) {
                        activeTab = idx
                        render()
                        scrollView.scrollTo(0, 0)
                    }
                }
            }
            bottomBar.addView(tabBtn, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                leftMargin = dp(2)
                rightMargin = dp(2)
            })
        }
    }

    private fun render() {
        renderBottomNav()
        root.removeAllViews()
        when (activeTab) {
            1 -> {
                LeapCompanionHub.buildTripsTab(this, root) { render() }
                return
            }
            2 -> {
                LeapCompanionHub.buildHomeTab(this, root) { render() }
                return
            }
            3 -> {
                MyCarTab.build(this, root) { render() }
                return
            }
            4 -> {
                LeapCompanionHub.buildRouteTab(this, root)
                return
            }
        }
        val s = BridgeState.current
        val appVer = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "0.1.18"
        val isExpert = prefs.expertMode
        val localCarApk = GitHubReleaseUpdater.findBestLocalCarApk(this, appVer)
        val apkName = localCarApk?.name ?: "QonectaUnit_${appVer}-unit.apk"
        val (localVerName, localVerCode) = if (localCarApk != null) {
            GitHubReleaseUpdater.extractApkVersionMeta(this, localCarApk)
        } else {
            "$appVer-unit" to 0
        }
        val bestIp = GitHubReleaseUpdater.findBestHotspotOrLocalIp() ?: "192.168.43.1"

        // --- Cabecera limpia + mención por CharlysEV + Café/PayPal + Interruptor Modo Experto ---
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("Qonecta Companion", 22f, TEXT, bold = true))
                addView(text("v$appVer · por CharlysEV · Leapmotor C10", 12.5f, FAINT))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val supportHeaderBtn = TextView(this).apply {
            text = "☕ Apoyar"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(WARN)
            background = rounded(SURFACE_TOP, dp(20), WARN)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            isClickable = true
            setOnClickListener { showSupportDialog() }
        }
        headerRow.addView(supportHeaderBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(6)
        })
        val expertBtn = TextView(this).apply {
            text = if (isExpert) "🛠️ Experto: ON" else "🛠️ Experto"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (isExpert) ON_ACCENT else DIM)
            background = if (isExpert) rounded(WARN, dp(20)) else rounded(SURFACE_TOP, dp(20), OUTLINE)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            isClickable = true
            setOnClickListener {
                prefs.expertMode = !prefs.expertMode
                render()
            }
        }
        headerRow.addView(expertBtn)
        root.addView(headerRow)

        // Si aún no está vinculado, mostramos arriba del todo el acceso directo al Modo Tutorial guiado del C10
        if (!prefs.isPaired) {
            root.addView(card().apply {
                background = rounded(0xFF0E2229.toInt(), dp(20), ACCENT)
                addView(text("🎓 ¿Primera vez instalando en tu Leapmotor C10?", 17f, ACCENT, bold = true))
                addView(text(
                    "Sigue el Modo Tutorial paso a paso y sincronizado en vivo para instalar Qonecta Unit en la pantalla del C10 y vincularlo a la primera.",
                    13.5f, TEXT,
                ).apply { setPadding(0, dp(4), 0, dp(10)) })
                addView(pill("🎓 Abrir Modo Tutorial paso a paso (C10)", primary = true) {
                    openTutorialDialog()
                })
            })
        }

        // ======================================================================================
        // TARJETA 1 · ESTADO DE CONEXIÓN Y OPCIONES MARCABLES (SOLO BLUETOOTH / WI-FI)
        // ======================================================================================
        root.addView(card().apply {
            val carDisplay = s.carName.ifBlank { prefs.carName ?: prefs.carBtName ?: "tu Leapmotor C10" }
            val (statusTitle, statusColor) = when (s.phase) {
                BridgeState.Phase.CONNECTED -> "🟢 Conectado con $carDisplay" to OK
                BridgeState.Phase.CONNECTING -> "🔵 Conectando con $carDisplay…" to ACCENT
                BridgeState.Phase.PAIRING -> "🔵 Emparejando con el coche…" to ACCENT
                BridgeState.Phase.SEARCHING -> "🟡 Buscando el coche…" to WARN
                BridgeState.Phase.STOPPED -> (if (prefs.isPaired) "⚪ En espera" else "⚪ Pendiente de vincular por primera vez") to FAINT
            }
            addView(text(statusTitle, 19f, statusColor, bold = true))

            val subInfo = buildString {
                if (s.transport.isNotBlank()) append("Vía ${s.transport}")
                if (prefs.useBluetooth && !prefs.useWifi) {
                    if (isNotEmpty()) append("  ·  ")
                    append("Modo Solo Bluetooth (sin Wi-Fi)")
                }
                if (s.carAppVersion.isNotBlank()) {
                    if (isNotEmpty()) append("  ·  ")
                    append("Coche en v${s.carAppVersion}")
                }
            }
            if (subInfo.isNotBlank()) {
                addView(text(subInfo, 14f, ACCENT, bold = true).apply { setPadding(0, dp(3), 0, 0) })
            }
            if (s.detail.isNotBlank()) {
                addView(text(s.detail, 14f, DIM).apply { setPadding(0, dp(6), 0, 0) })
            }

            // Sincronización en tiempo real de las casillas marcadas en la pantalla del C10 (Qonecta Unit)
            val unitTel = UnitTelemetry.current
            if (s.phase == BridgeState.Phase.CONNECTED && unitTel != null && unitTel.hasUnitConnInfo) {
                val carSyncText = buildString {
                    append("🔄 Coche sincronizado: ")
                    append(if (unitTel.unitBtCompanion) "☑ BT" else "☐ BT")
                    append(" · ")
                    append(if (unitTel.unitWifi5g) "☑ Wi-Fi 5G" else "☐ Wi-Fi 5G apagado")
                    append(" · ")
                    append(if (unitTel.unitHotspot) "☑ Hotspot" else "☐ Hotspot")
                    append(" · ")
                    append(if (unitTel.unitUsb) "☑ USB" else "☐ USB")
                }
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = rounded(0xFF0D2B1E.toInt(), dp(12), OK)
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(8)
                        }
                        addView(text(carSyncText, 12.5f, OK, bold = true))
                    }
                )
            }

            // --- Casillas independientes para marcar y desmarcar Bluetooth y Wi-Fi ---
            val transportBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(BG, dp(14), OUTLINE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                }
            }
            transportBox.addView(text("Opciones de conexión (marca o desmarca libremente):", 13f, FAINT, bold = true))

            transportBox.addView(
                buildTransportToggleRow(
                    checked = prefs.useBluetooth,
                    title = "🔵 Bluetooth (Datos, Viajes, Tarifas y Rutas)",
                    subtitle = if (prefs.useBluetooth && !prefs.useWifi) {
                        "Activo en solitario · Cero Wi-Fi 5 GHz y cero cortes de radio"
                    } else {
                        "Conecta con Qonecta Unit usando solo el Bluetooth del coche"
                    },
                ) {
                    prefs.useBluetooth = !prefs.useBluetooth
                    applyTransportToggleChange()
                }.apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
                }
            )

            transportBox.addView(
                buildTransportToggleRow(
                    checked = prefs.useWifi,
                    title = "📡 Wi-Fi rápido (Android Auto 5 GHz / Hotspot)",
                    subtitle = if (!prefs.useWifi) {
                        "Desmarcado: nunca buscará redes Wi-Fi, todo irá limpio por Bluetooth"
                    } else {
                        "Aprovecha el Wi-Fi cuando ya llevas Android Auto o Hotspot activo"
                    },
                ) {
                    prefs.useWifi = !prefs.useWifi
                    applyTransportToggleChange()
                }.apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
                }
            )

            // Selector directo del Bluetooth del coche siempre a mano cuando Bluetooth está marcado
            if (prefs.useBluetooth && prefs.isPaired) {
                val chosenBt = prefs.carBtName ?: prefs.carBtAddress
                val btPickerRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(10), dp(4), dp(2))
                }
                btPickerRow.addView(
                    text(
                        if (chosenBt != null) "🚗 BT del C10: $chosenBt" else "🚗 BT del C10: Automático (elige tu coche si tarda)",
                        13f,
                        if (chosenBt != null) OK else WARN,
                        bold = true,
                    ),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                val pickSmallBtn = TextView(this@MainActivity).apply {
                    text = if (chosenBt != null) "Cambiar BT" else "Elegir BT"
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(if (chosenBt == null) ON_ACCENT else TEXT)
                    background = if (chosenBt == null) rounded(ACCENT, dp(16)) else rounded(SURFACE_TOP, dp(16), OUTLINE)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    isClickable = true
                    setOnClickListener { pickBondedDevice() }
                }
                btPickerRow.addView(pickSmallBtn)
                transportBox.addView(btPickerRow)
            }
            addView(transportBox)

            // Comprobación silenciosa de permisos esenciales: solo molesta si falta alguno
            val missingNotif = Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)
            val missingBt = Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)
            val missingOverlay = !Settings.canDrawOverlays(this@MainActivity)
            val pm = getSystemService(PowerManager::class.java)
            val missingBattery = pm?.isIgnoringBatteryOptimizations(packageName) != true

            if (missingBt || missingNotif || missingOverlay || missingBattery) {
                val permBox = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(BG, dp(14), OUTLINE)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(10)
                    }
                }
                permBox.addView(text("⚠️ Activa estos permisos para que funcione todo automático:", 13.5f, WARN, bold = true))
                if (missingBt) {
                    permBox.addView(permRow("Bluetooth (Dispositivos cercanos)", false) {
                        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_PERMS)
                    })
                }
                if (missingNotif) {
                    permBox.addView(permRow("Notificaciones", false) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERMS)
                    })
                }
                if (missingOverlay) {
                    permBox.addView(permRow("Abrir Google Maps automáticamente", false) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    })
                }
                if (missingBattery) {
                    permBox.addView(permRow("Sin ahorro de batería", false) {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                        }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    })
                }
                addView(permBox)
            }

            if (!prefs.isPaired) {
                // Asistente limpio de primera vez (solo aparece cuando aún no está vinculado)
                val stepBox = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(BG, dp(14), OUTLINE)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(12)
                    }
                }
                val chosenBt = prefs.carBtName ?: prefs.carBtAddress
                stepBox.addView(text("Paso 1 · Elige el Bluetooth de tu coche", 15f, TEXT, bold = true))
                stepBox.addView(text(
                    if (chosenBt != null) "✓ Seleccionado: $chosenBt" else "Toca abajo y selecciona el manos libres Bluetooth de tu coche.",
                    13.5f, if (chosenBt != null) OK else DIM,
                ).apply { setPadding(0, dp(4), 0, dp(8)) })
                stepBox.addView(row(
                    pill(if (chosenBt != null) "Cambiar Bluetooth" else "1. Elegir coche", primary = chosenBt == null) { pickBondedDevice() },
                    pill("Buscar cercano") { associateCar() },
                ))

                stepBox.addView(text("Paso 2 · Código de 6 cifras del coche", 15f, TEXT, bold = true).apply {
                    setPadding(0, dp(14), 0, dp(4))
                })
                stepBox.addView(text(
                    "En Qonecta Unit del coche entra en Ajustes Extendidos → Qonecta Companion → «Emparejar móvil» y escribe el número:",
                    13.5f, DIM,
                ).apply { setPadding(0, 0, 0, dp(8)) })

                val codeInput = buildPairCodeInput()
                stepBox.addView(codeInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))
                stepBox.addView(pill("🔗 Vincular con mi coche", primary = true) {
                    val code = codeInput.text?.toString()?.trim().orEmpty()
                    if (code.length != 6) {
                        toast("Escribe las 6 cifras que muestra la pantalla del coche")
                    } else {
                        draftPairCode = ""
                        BridgeService.pair(this@MainActivity, code)
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(10) })

                addView(stepBox)
            } else {
                addView(row(
                    pill(if (s.phase == BridgeState.Phase.STOPPED) "Conectar ahora" else "Reconectar", primary = s.phase != BridgeState.Phase.CONNECTED) {
                        if (!prefs.useBluetooth && !prefs.useWifi) {
                            prefs.useBluetooth = true
                        }
                        prefs.enabled = true
                        BridgeService.reconnectNow(this@MainActivity)
                        render()
                    },
                    pill("Pausar") {
                        prefs.enabled = false
                        BridgeService.stop(this@MainActivity)
                        render()
                    },
                ).apply { setPadding(0, dp(12), 0, 0) })
            }
        })

        // ======================================================================================
        // TARJETA 2 · ACTUALIZACIÓN PUSH EN 1 TOQUE (CERO USB, CERO VIVALDI, CERO PROXY)
        // ======================================================================================
        root.addView(card().apply {
            addView(text("⚡ Actualizar Coche en 1 Toque", 19f, ACCENT, bold = true))
            addView(text(
                "Envía la nueva versión directamente del móvil al coche y abre el instalador en la pantalla del C10 sin pendrives ni navegador.",
                14f, DIM,
            ).apply { setPadding(0, dp(4), 0, dp(10)) })

            val summaryBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(BG, dp(14), OUTLINE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }

            if (localCarApk != null) {
                val sizeMb = String.format(java.util.Locale.US, "%.1f MB", localCarApk.length() / (1024.0 * 1024.0))
                summaryBox.addView(text("📦 Lista en tu móvil: v$localVerName ($sizeMb)", 14.5f, TEXT, bold = true))
                val cmpTextAndColor: Pair<String, Int> = when {
                    s.phase != BridgeState.Phase.CONNECTED ->
                        "Conecta con el coche arriba para enviarla en 1 toque." to FAINT
                    s.carVersionCode == 0 ->
                        "⚠️ Tu coche tiene una versión anterior a v0.1.17. Instálala por última vez con «🆘 Instalar desde cero» abajo y las próximas ya serán en 1 toque." to WARN
                    localVerCode > s.carVersionCode ->
                        "✨ ¡Actualización disponible! Tu coche tiene v${s.carAppVersion} → subirá a v$localVerName." to OK
                    localVerCode == s.carVersionCode && localVerCode > 0 ->
                        "✓ Tu coche ya está al día (v${s.carAppVersion}). Puedes reinstalarla si quieres." to OK
                    else ->
                        "Coche conectado en v${s.carAppVersion.ifBlank { "actual" }}." to DIM
                }
                summaryBox.addView(text(cmpTextAndColor.first, 13.5f, cmpTextAndColor.second).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            } else {
                summaryBox.addView(text("⚠️ Aún no hay ningún APK descargado en el móvil. Pulsa «☁️ Buscar nueva versión en Internet».", 14f, WARN))
            }
            addView(summaryBox)

            val otaFeedbackTv = text(
                s.otaStatusMessage,
                14f,
                when (s.otaLastOk) {
                    true -> OK
                    false -> WARN
                    null -> ACCENT
                },
                bold = true,
            ).apply {
                visibility = if (s.otaStatusMessage.isNotBlank()) View.VISIBLE else View.GONE
                setPadding(0, dp(10), 0, dp(4))
            }
            addView(otaFeedbackTv)

            val pushBtnLabel = when {
                s.otaInProgress -> "⏳ Enviando actualización al coche…"
                localCarApk != null -> "⚡ Actualizar coche ahora (v$localVerName)"
                else -> "⚡ Actualizar coche ahora"
            }
            addView(pill(pushBtnLabel, primary = true) {
                if (s.otaInProgress) {
                    toast("Ya se está enviando la actualización al coche…")
                    return@pill
                }
                val target = GitHubReleaseUpdater.findBestLocalCarApk(this@MainActivity, appVer)
                if (target == null) {
                    toast("Primero descarga la última versión con el botón de abajo")
                } else {
                    BridgeService.pushApkUpdateToCar(this@MainActivity, target)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })

            val cloudCheckBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(pill("☁️ Buscar nueva versión en Internet") {
                cloudCheckBox.removeAllViews()
                val checkingTv = text("⏳ Buscando nuevas versiones en GitHub…", 13.5f, ACCENT).apply {
                    setPadding(0, dp(8), 0, 0)
                }
                cloudCheckBox.addView(checkingTv)
                GitHubReleaseUpdater.fetchDualReleases(
                    unitRepo = prefs.githubRepo,
                    companionRepo = prefs.githubCompanionRepo,
                    githubToken = prefs.githubToken,
                ) { statuses, assets ->
                    cloudCheckBox.removeAllViews()
                    val newestCar = assets.firstOrNull { it.isCarApk }
                    val newestComp = assets.firstOrNull { it.isCompanionApk }
                    val errors = statuses.mapNotNull { st -> st.errorMessage?.let { "${st.projectLabel}: $it" } }

                    if (newestCar == null && newestComp == null) {
                        val errTxt = if (errors.isNotEmpty()) errors.joinToString("\n") else "No se encontraron APKs publicados."
                        cloudCheckBox.addView(text("⚠️ $errTxt", 13.5f, WARN).apply { setPadding(0, dp(8), 0, 0) })
                        return@fetchDualReleases
                    }

                    if (newestCar != null) {
                        val dlStatusTv = text("⬇️ Descargando ${newestCar.assetName} (${newestCar.tagName})…", 13.5f, ACCENT).apply {
                            setPadding(0, dp(8), 0, 0)
                        }
                        cloudCheckBox.addView(dlStatusTv)
                        GitHubReleaseUpdater.downloadAsset(
                            this@MainActivity,
                            newestCar,
                            githubToken = prefs.githubToken,
                            onProgress = { msg -> dlStatusTv.text = msg },
                            onDone = { res ->
                                res.onSuccess { file ->
                                    GitHubReleaseUpdater.startHostingApkForCar(this@MainActivity, file) {}
                                    dlStatusTv.text = "✅ Descargada ${file.name}. ¡Ya puedes pulsar «⚡ Actualizar coche ahora»!"
                                    dlStatusTv.setTextColor(OK)
                                    render()
                                }.onFailure { err ->
                                    dlStatusTv.text = "⚠️ No se pudo descargar: ${err.message}"
                                    dlStatusTv.setTextColor(BAD)
                                }
                            },
                        )
                    }

                    if (newestComp != null) {
                        cloudCheckBox.addView(pill("📲 Instalar también Companion ${newestComp.tagName} en este móvil") {
                            toast("Descargando ${newestComp.assetName}…")
                            GitHubReleaseUpdater.downloadAsset(
                                this@MainActivity,
                                newestComp,
                                githubToken = prefs.githubToken,
                                onProgress = {},
                                onDone = { res ->
                                    res.onSuccess { f ->
                                        GitHubReleaseUpdater.installApkOnPhone(this@MainActivity, f) { st -> toast(st) }
                                    }.onFailure { e -> toast("Error: ${e.message}") }
                                },
                            )
                        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(8)
                        })
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            addView(cloudCheckBox)
        })

        // ======================================================================================
        // TARJETA 3 · MODO TUTORIAL INTERACTIVO C10 + INSTALACIÓN DESDE CERO
        // ======================================================================================
        root.addView(card().apply {
            val expanded = prefs.coldInstallExpanded
            val headerToggle = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener {
                    prefs.coldInstallExpanded = !expanded
                    render()
                }
            }
            headerToggle.addView(
                text("🎓 Tutorial instalar Qonecta Unit en el C10", 17f, TEXT, bold = true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            headerToggle.addView(text(if (expanded) "▲ Ocultar" else "▼ Pasos rápidos", 13f, ACCENT, bold = true))
            addView(headerToggle)

            addView(text(
                "Asistente paso a paso y sincronizado en tiempo real (detecta tu APK, el USB, el Hotspot y te avisa en directo cuando Vivaldi del C10 abre el portal):",
                13.5f, DIM,
            ).apply { setPadding(0, dp(6), 0, dp(10)) })

            addView(pill("🎓 Abrir Modo Tutorial interactivo (5 pasos en vivo)", primary = true) {
                openTutorialDialog()
            })

            if (expanded) {
                val stepsBox = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(BG, dp(14), OUTLINE)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(12)
                    }
                }
                stepsBox.addView(text(
                    "Resumen rápido sin asistente:\n" +
                        "1️⃣ Guarda el APK en un Pendrive USB-C y enchúfalo al C10.\n" +
                        "2️⃣ Enciende el Hotspot del móvil y conecta el Wi-Fi del C10 (sin Proxy ni DNS).\n" +
                        "3️⃣ En Vivaldi del coche abre:  http://$bestIp:8788/portal\n" +
                        "4️⃣ Toca el Botón Amarillo «ABRIR ARCHIVOS DEL COCHE» y pulsa las dos flechas ⤢ de la esquina del APK.",
                    13.5f, TEXT,
                ))
                addView(stepsBox)

                val coldStatusTv = text(
                    if (GitHubReleaseUpdater.isHosting) "🟢 Servidor web listo en http://$bestIp:8788/portal ($apkName)"
                    else "⚪ Pulsa «🔄 Actualizar IP» tras encender el Hotspot",
                    13.5f,
                    if (GitHubReleaseUpdater.isHosting) OK else WARN,
                    bold = true,
                ).apply { setPadding(0, dp(10), 0, dp(8)) }
                addView(coldStatusTv)

                addView(pill("💾 1. Guardar APK en Pendrive USB ($apkName)", primary = false) {
                    val target = GitHubReleaseUpdater.findBestLocalCarApk(this@MainActivity, appVer)
                    if (target == null) {
                        toast("Primero pulsa «☁️ Buscar nueva versión en Internet» arriba")
                    } else {
                        exportApkToUsbDrive(target)
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                addView(row(
                    pill("📶 2. Encender Hotspot") { openTetherSettings() },
                    pill("🔄 3. Actualizar IP") {
                        val target = GitHubReleaseUpdater.findBestLocalCarApk(this@MainActivity, appVer)
                        if (target != null) {
                            GitHubReleaseUpdater.startHostingApkForCar(this@MainActivity, target) { render() }
                        } else {
                            render()
                        }
                    },
                ).apply { setPadding(0, dp(8), 0, 0) })
            }
        })

        // ======================================================================================
        // TARJETA 4 · APOYAR EL PROYECTO (CAFÉ KO-FI Y PAYPAL · IGUAL QUE EN QONECTA+)
        // ======================================================================================
        root.addView(card().apply {
            addView(text("☕ Apoyar el proyecto · por CharlysEV", 17f, WARN, bold = true))
            addView(text(
                "Qonecta Unit y Qonecta Companion son proyectos creados para la comunidad Leapmotor. Si te hacen más cómodos tus viajes, puedes invitar a un café o apoyar su desarrollo:",
                13.5f, DIM,
            ).apply { setPadding(0, dp(4), 0, dp(12)) })
            addView(row(
                pill("☕ Invitar a un café", primary = true) {
                    openExternalUrl("https://ko-fi.com/charlysev")
                },
                pill("💙 PayPal") {
                    openExternalUrl("https://www.paypal.me/CharlysEV")
                },
            ))
        })

        // ======================================================================================
        // MODO EXPERTO · OCULTO POR DEFECTO, COMPLETO PARA QUIEN QUIERA TOCAR
        // ======================================================================================
        if (!isExpert) {
            return
        }

        root.addView(text("🛠️ MODO EXPERTO · AJUSTES AVANZADOS", 14f, WARN, bold = true).apply {
            setPadding(dp(4), dp(18), dp(4), dp(2))
        })

        // --- Experto 1: Re-emparejamiento manual Bluetooth + Código de 6 cifras + Olvidar coche ---
        root.addView(card().apply {
            addView(text("🔑 Gestión Manual de Vínculo y Bluetooth", 17.5f, TEXT, bold = true))
            val car = prefs.carBtName ?: prefs.carBtAddress
            addView(text(
                if (car != null) "Bluetooth asociado: $car (${prefs.carBtAddress ?: "—"})" else "Ningún Bluetooth seleccionado manualmente.",
                14f, if (car != null) OK else DIM,
            ).apply { setPadding(0, dp(4), 0, dp(10)) })
            addView(row(
                pill("Lista Bluetooth") { pickBondedDevice() },
                pill("Asociar CDM") { associateCar() },
            ))

            addView(text("Forzar nuevo emparejamiento con código de 6 cifras:", 13.5f, DIM).apply {
                setPadding(0, dp(12), 0, dp(6))
            })
            val codeInput = buildPairCodeInput()
            addView(codeInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
            addView(row(
                pill("Volver a emparejar", primary = true) {
                    val code = codeInput.text?.toString()?.trim().orEmpty()
                    if (code.length != 6) {
                        toast("Escribe las 6 cifras del coche")
                    } else {
                        draftPairCode = ""
                        BridgeService.pair(this@MainActivity, code)
                    }
                },
                pill("Olvidar coche") { confirmForget() },
            ).apply { setPadding(0, dp(10), 0, 0) })
        })

        // --- Experto 2: Servidor Portal Cautivo (:8788 / :8080), PAC y DNS Personalizado ---
        root.addView(card().apply {
            val activeIpsDesc = GitHubReleaseUpdater.describeActiveNetworkIps()
            addView(text("📡 Servidor HTTP / Portal Cautivo / DNS Manual", 17.5f, TEXT, bold = true))
            addView(text(
                "Interfaces activas: $activeIpsDesc\n" +
                    "• Portal Web: http://$bestIp:8788/portal  (secundario :8080)\n" +
                    "• Descarga directa APK: http://$bestIp:8788/download/com.tencent.qqmusiccar.apk\n" +
                    "• PAC Proxy: http://$bestIp:8788/proxy.pac",
                13f, DIM,
            ).apply { setPadding(0, dp(6), 0, dp(8)) })

            val srvLogTv = text(
                GitHubReleaseUpdater.lastEventLog.ifBlank {
                    if (GitHubReleaseUpdater.isHosting) "🟢 Servidor escuchando en 0.0.0.0:8788 y :8080" else "⚪ Servidor detenido"
                },
                13f, if (GitHubReleaseUpdater.isHosting) OK else FAINT,
            ).apply { setPadding(0, 0, 0, dp(8)) }
            addView(srvLogTv)

            addView(row(
                pill("Reiniciar :8788", primary = true) {
                    val target = GitHubReleaseUpdater.findBestLocalCarApk(this@MainActivity, appVer)
                    if (target == null) {
                        toast("Sin APK local")
                    } else {
                        GitHubReleaseUpdater.startHostingApkForCar(this@MainActivity, target) { st ->
                            srvLogTv.text = st
                            srvLogTv.setTextColor(OK)
                        }
                    }
                },
                pill("Detener :8788") {
                    GitHubReleaseUpdater.stopHostingApk()
                    render()
                },
            ))

            // DNS Personalizado Manual (en Modo Experto)
            addView(text("🌐 DNS Personalizado Manual (NextDNS / DNS Privado):", 14f, ACCENT, bold = true).apply {
                setPadding(0, dp(14), 0, dp(4))
            })
            val dnsInput = EditText(this@MainActivity).apply {
                hint = "Ej: xxxx.dns.nextdns.io o IP DNS"
                setHintTextColor(FAINT)
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setSingleLine(true)
                setText(prefs.customDnsHost)
                background = rounded(SURFACE_TOP, dp(12), OUTLINE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            addView(dnsInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(row(
                pill("📋 Copiar y Abrir DNS") {
                    val v = dnsInput.text?.toString()?.trim().orEmpty()
                    prefs.customDnsHost = v
                    if (v.isNotEmpty()) {
                        val cm = getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("DNS", v))
                        toast("DNS copiado: $v")
                    }
                    openPrivateDnsSettings()
                },
                pill("📶 Ajustes Wi-Fi") {
                    val v = dnsInput.text?.toString()?.trim().orEmpty()
                    prefs.customDnsHost = v
                    runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                },
            ).apply { setPadding(0, dp(8), 0, 0) })
        })

        // --- Experto 3: Repositorios GitHub Editables + Token PAT + Selector de Releases ---
        root.addView(card().apply {
            addView(text("🐙 GitHub Releases Avanzado y Token PAT", 17.5f, TEXT, bold = true))
            addView(text("Configura repositorios personalizados o tu Token PAT si tus repositorios son privados:", 13.5f, DIM).apply {
                setPadding(0, dp(4), 0, dp(8))
            })

            addView(text("Repositorio Qonecta Unit (Coche):", 12f, FAINT, bold = true))
            val repoInput = EditText(this@MainActivity).apply {
                hint = GitHubReleaseUpdater.DEFAULT_UNIT_REPO
                setHintTextColor(FAINT)
                setText(prefs.githubRepo)
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setSingleLine(true)
                background = rounded(SURFACE_TOP, dp(12), OUTLINE)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            addView(repoInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(4) })

            addView(text("Repositorio Qonecta Companion (Móvil):", 12f, FAINT, bold = true).apply { setPadding(0, dp(8), 0, 0) })
            val companionRepoInput = EditText(this@MainActivity).apply {
                hint = GitHubReleaseUpdater.DEFAULT_COMPANION_REPO
                setHintTextColor(FAINT)
                setText(prefs.githubCompanionRepo)
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setSingleLine(true)
                background = rounded(SURFACE_TOP, dp(12), OUTLINE)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            addView(companionRepoInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(4) })

            addView(text("Token PAT de GitHub (opcional para repos privados):", 12f, FAINT, bold = true).apply { setPadding(0, dp(8), 0, 0) })
            val tokenInput = EditText(this@MainActivity).apply {
                hint = "ghp_… o github_pat_…"
                setHintTextColor(FAINT)
                setText(prefs.githubToken)
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                background = rounded(SURFACE_TOP, dp(12), OUTLINE)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            addView(tokenInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(4) })

            val ghStatusTv = text("Pulsa para listar todas las versiones disponibles.", 13.5f, FAINT).apply {
                setPadding(0, dp(8), 0, dp(8))
            }
            addView(ghStatusTv)

            val assetsContainer = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(assetsContainer)

            addView(pill("💾 Guardar ajustes y listar Releases", primary = true) {
                val unitRepo = GitHubReleaseUpdater.normalizeRepo(repoInput.text?.toString().orEmpty(), GitHubReleaseUpdater.DEFAULT_UNIT_REPO)
                val compRepo = GitHubReleaseUpdater.normalizeRepo(companionRepoInput.text?.toString().orEmpty(), GitHubReleaseUpdater.DEFAULT_COMPANION_REPO)
                val pat = tokenInput.text?.toString()?.trim().orEmpty()
                prefs.githubRepo = unitRepo
                prefs.githubCompanionRepo = compRepo
                prefs.githubToken = pat
                ghStatusTv.text = "⏳ Consultando $unitRepo y $compRepo…"
                ghStatusTv.setTextColor(ACCENT)
                assetsContainer.removeAllViews()

                GitHubReleaseUpdater.fetchDualReleases(unitRepo, compRepo, pat) { statuses, list ->
                    ghStatusTv.text = statuses.joinToString("\n") { st ->
                        if (st.errorMessage != null) "${st.projectLabel}: ⚠️ ${st.errorMessage}"
                        else "${st.projectLabel}: ✓ ${st.assets.size} APK(s)"
                    }
                    ghStatusTv.setTextColor(if (list.isNotEmpty()) OK else WARN)

                    list.forEach { asset ->
                        val itemBox = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            background = rounded(BG, dp(14), OUTLINE)
                            setPadding(dp(12), dp(10), dp(12), dp(10))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                topMargin = dp(8)
                            }
                        }
                        itemBox.addView(text("${asset.projectLabel} · ${asset.assetName}", 14.5f, TEXT, bold = true))
                        itemBox.addView(text("${asset.tagName} (${asset.publishedAt}) · ${String.format(java.util.Locale.US, "%.1f MB", asset.sizeMb)}", 12.5f, DIM).apply {
                            setPadding(0, dp(2), 0, dp(8))
                        })
                        itemBox.addView(row(
                            pill("📲 En móvil", primary = asset.isCompanionApk) {
                                GitHubReleaseUpdater.downloadAsset(
                                    this@MainActivity, asset, pat,
                                    onProgress = { msg -> ghStatusTv.text = msg },
                                    onDone = { dl ->
                                        dl.onSuccess { f -> GitHubReleaseUpdater.installApkOnPhone(this@MainActivity, f) { st -> ghStatusTv.text = st } }
                                            .onFailure { e -> ghStatusTv.text = "⚠️ ${e.message}" }
                                    },
                                )
                            },
                            pill("⚡ Push al coche", primary = asset.isCarApk) {
                                GitHubReleaseUpdater.downloadAsset(
                                    this@MainActivity, asset, pat,
                                    onProgress = { msg -> ghStatusTv.text = msg },
                                    onDone = { dl ->
                                        dl.onSuccess { f ->
                                            GitHubReleaseUpdater.startHostingApkForCar(this@MainActivity, f) {}
                                            BridgeService.pushApkUpdateToCar(this@MainActivity, f)
                                            render()
                                        }.onFailure { e -> ghStatusTv.text = "⚠️ ${e.message}" }
                                    },
                                )
                            },
                        ))
                        assetsContainer.addView(itemBox)
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        })

        // --- Experto 4: Puente de Datos Bluetooth, Permisos completos, Logs sin pendrive y Pruebas ---
        root.addView(card().apply {
            addView(text("🌐 Puente de Datos BT, Permisos y Registros", 17.5f, TEXT, bold = true))
            addView(text(
                "Peticiones HTTP servidas al Planificador del coche por ${s.transport.ifBlank { "Bluetooth" }}: ${s.dataRequestsServed}" +
                    (if (s.lastDataHost.isNotBlank()) " (último host: ${s.lastDataHost})" else ""),
                13.5f, if (s.dataRequestsServed > 0) OK else DIM,
            ).apply { setPadding(0, dp(4), 0, dp(10)) })

            addView(row(
                pill("🔵 Tethering BT / Hotspot") { openTetherSettings() },
                pill("🗺️ Probar Maps") { testMaps() },
            ))

            val logsStatusTv = text("Extracción remota de ohu.log + logcat sin pendrive:", 13.5f, DIM).apply {
                setPadding(0, dp(12), 0, dp(6))
            }
            addView(logsStatusTv)
            addView(pill("📥 Recoger registros (Logs) del coche ahora", primary = true) {
                logsStatusTv.text = "⏳ Solicitando registros a Qonecta Unit…"
                logsStatusTv.setTextColor(ACCENT)
                BridgeService.requestUnitLogs(this@MainActivity, maxLines = 800) { res ->
                    res.onSuccess { (file, msg) ->
                        val kb = (file.length() / 1024L).coerceAtLeast(1L)
                        logsStatusTv.text = "✓ Recibido: ${msg.fileName} ($kb KB)"
                        logsStatusTv.setTextColor(OK)
                        showUnitLogsDialog(file, msg.logText)
                    }.onFailure { err ->
                        logsStatusTv.text = "⚠️ ${err.message}"
                        logsStatusTv.setTextColor(BAD)
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

            addView(text("Estado detallado de permisos:", 13.5f, FAINT, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) })
            if (Build.VERSION.SDK_INT >= 33) {
                addView(permRow("Notificaciones", has(Manifest.permission.POST_NOTIFICATIONS)) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERMS)
                })
            }
            if (Build.VERSION.SDK_INT >= 31) {
                addView(permRow("Dispositivos cercanos (Bluetooth)", has(Manifest.permission.BLUETOOTH_CONNECT)) {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_PERMS)
                })
            }
            addView(permRow("Mostrar sobre otras apps (abrir Maps solo)", Settings.canDrawOverlays(this@MainActivity)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            })
            val pm = getSystemService(PowerManager::class.java)
            addView(permRow("Sin restricción de batería", pm?.isIgnoringBatteryOptimizations(packageName) == true) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            })
        })
    }

    private fun buildPairCodeInput(): EditText = EditText(this).apply {
        hint = "000000"
        setHintTextColor(FAINT)
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.28f
        gravity = Gravity.CENTER
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(6))
        background = rounded(SURFACE_TOP, dp(14), OUTLINE)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        if (draftPairCode.isNotEmpty()) {
            setText(draftPairCode)
            setSelection(draftPairCode.length)
        }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                draftPairCode = s?.toString().orEmpty()
            }
        })
    }

    // ------------------------------------------------------------------------------------------
    // Acciones
    // ------------------------------------------------------------------------------------------

    private fun associateCar() {
        val cdm = getSystemService(CompanionDeviceManager::class.java)
        if (cdm == null) {
            pickBondedDevice()
            return
        }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            @Deprecated("Sigue llamándose en API 33+ desde onAssociationPending")
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                runCatching { startIntentSenderForResult(chooserLauncher, REQ_ASSOCIATE, null, 0, 0, 0) }
                    .onFailure { toast("No se pudo abrir el selector: ${it.message}") }
            }

            override fun onFailure(error: CharSequence?) {
                toast("No se encontró el coche${error?.let { ": $it" } ?: ""}. Prueba con «Lista de emparejados».")
            }
        }
        runCatching { cdm.associate(request, callback, null) }
            .onFailure {
                toast("Este móvil no permite asociar: uso la lista de emparejados")
                pickBondedDevice()
            }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MyCarTab.REQ_CERT) {
            if (resultCode == RESULT_OK) MyCarTab.onCertPicked(this, data) { render() }
            return
        }
        if (requestCode == REQ_SAVE_APK_USB) {
            val src = pendingUsbExportApk
            val uri = data?.data
            if (resultCode == RESULT_OK && src != null && uri != null) {
                Toast.makeText(this, "Copiando ${src.name} al USB / carpeta…", Toast.LENGTH_SHORT).show()
                kotlin.concurrent.thread(name = "usb-apk-export", isDaemon = true) {
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            java.io.FileInputStream(src).use { input ->
                                input.copyTo(out, bufferSize = 64 * 1024)
                            }
                            out.flush()
                        } ?: error("No se pudo abrir el destino")
                    }.onSuccess {
                        prefs.usbExportDoneAtMs = System.currentTimeMillis()
                        runOnUiThread {
                            toast("✅ ${src.name} guardado correctamente en el USB / carpeta")
                            render()
                            activeTutorialDialog?.refresh()
                        }
                    }.onFailure { e ->
                        runOnUiThread {
                            toast("⚠️ Error al guardar APK: ${e.message}")
                        }
                    }
                }
            }
            return
        }
        if (requestCode != REQ_ASSOCIATE || resultCode != RESULT_OK || data == null) return
        var address: String? = null
        var name: String? = null
        if (Build.VERSION.SDK_INT >= 33) {
            val info: AssociationInfo? = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            address = info?.deviceMacAddress?.toString()?.uppercase()
            name = info?.displayName?.toString()
        }
        val device: BluetoothDevice? = data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
        if (address == null) address = device?.address
        if (name == null) name = runCatching { device?.name }.getOrNull()
        if (address == null) {
            toast("No llegó la dirección del coche")
            return
        }
        saveCar(address, name)
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { getSystemService(CompanionDeviceManager::class.java)?.startObservingDevicePresence(address) }
        }
        toast("Coche asociado: ${name ?: address}")
    }

    private fun pickBondedDevice() {
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_PERMS)
            toast("Da permiso de Dispositivos cercanos y vuelve a pulsar")
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val bonded = runCatching { adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
        if (bonded.isEmpty()) {
            toast("No hay dispositivos Bluetooth emparejados")
            return
        }
        val labels = bonded.map { d -> "${runCatching { d.name }.getOrNull() ?: "Sin nombre"}\n${d.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("¿Cuál es tu coche?")
            .setItems(labels) { _, i ->
                val d = bonded[i]
                saveCar(d.address, runCatching { d.name }.getOrNull())
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun saveCar(address: String, name: String?) {
        prefs.carBtAddress = address
        prefs.carBtName = name
        render()
        activeTutorialDialog?.refresh()
        if (prefs.isPaired && prefs.enabled) BridgeService.reconnectNow(this)
    }

    private fun applyTransportToggleChange() {
        if (!prefs.useBluetooth && !prefs.useWifi) {
            BridgeService.stop(this)
            BridgeState.update {
                it.copy(
                    phase = BridgeState.Phase.STOPPED,
                    transport = "",
                    detail = "Opciones desmarcadas. Marca Bluetooth o Wi-Fi cuando quieras conectar.",
                )
            }
        } else if (prefs.isPaired && prefs.enabled) {
            BridgeService.reconnectNow(this)
        }
        render()
    }

    private fun buildTransportToggleRow(
        checked: Boolean,
        title: String,
        subtitle: String,
        onToggle: () -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = if (checked) rounded(SURFACE_TOP, dp(14), ACCENT) else rounded(SURFACE, dp(14), OUTLINE)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        isClickable = true
        setOnClickListener { onToggle() }

        val checkBadge = TextView(this@MainActivity).apply {
            text = if (checked) "☑" else "☐"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (checked) ACCENT else FAINT)
            setPadding(0, 0, dp(10), 0)
        }
        addView(checkBadge)

        val col = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
        }
        col.addView(text(title, 14.5f, if (checked) TEXT else DIM, bold = true))
        col.addView(text(subtitle, 12f, if (checked) ACCENT else FAINT).apply {
            setPadding(0, dp(2), 0, 0)
        })
        addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun openTutorialDialog() {
        activeTutorialDialog?.dismiss()
        val dlg = InstallTutorialDialog(
            activity = this,
            prefs = prefs,
            onExportUsb = { apk -> exportApkToUsbDrive(apk) },
            onOpenHotspot = { openTetherSettings() },
            onPickBluetooth = { pickBondedDevice() },
            onAssociateBluetooth = { associateCar() },
        )
        dlg.setOnDismissListener {
            if (activeTutorialDialog === dlg) activeTutorialDialog = null
            render()
        }
        activeTutorialDialog = dlg
        dlg.show()
    }

    private fun showSupportDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, dp(20), OUTLINE)
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        box.addView(text("☕ Apoyar Qonecta · por CharlysEV", 19f, WARN, bold = true))
        box.addView(text(
            "Qonecta Unit, Qonecta Companion y Qonecta+ se desarrollan de forma independiente para que disfrutes al máximo tu Leapmotor C10 sin suscripciones ni bloqueos.\n\n" +
                "Si te gusta el proyecto y quieres invitar a un café o echar una mano:",
            14f, TEXT,
        ).apply { setPadding(0, dp(8), 0, dp(14)) })

        box.addView(pill("☕ Invitar a un café en Ko-fi", primary = true) {
            openExternalUrl("https://ko-fi.com/charlysev")
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        box.addView(pill("💙 Apoyar por PayPal") {
            openExternalUrl("https://www.paypal.me/CharlysEV")
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })

        box.addView(pill("🐙 Ver proyecto en GitHub (CharlysEV)") {
            openExternalUrl("https://github.com/CharlysEV/qonecta")
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })

        val dlg = AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("Cerrar", null)
            .create()
        dlg.window?.setBackgroundDrawable(rounded(SURFACE, dp(20), OUTLINE))
        dlg.show()
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            toast("Enlace: $url")
        }
    }

    private fun testMaps() {
        val dest = NavPoint(42.880447, -8.545916, "Catedral de Santiago")
        val via = listOf(NavPoint(41.656060, -4.723680, "Parada de prueba (Valladolid)"))
        val built = MapsRoute.build(dest, via)
        val intent = MapsLauncher.buildIntent(this, built)
        runCatching { startActivity(intent) }.onFailure { toast("No hay Google Maps instalado") }
    }

    private fun openTetherSettings() {
        val tetherIntent = Intent().apply {
            setClassName("com.android.settings", "com.android.settings.TetherSettings")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(tetherIntent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        }
    }

    private fun openPrivateDnsSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun exportApkToUsbDrive(apkFile: java.io.File) {
        pendingUsbExportApk = apkFile
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_TITLE, apkFile.name)
        }
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_SAVE_APK_USB)
        }.onFailure { e ->
            toast("No se pudo abrir el selector de archivos: ${e.message}")
        }
    }

    private fun confirmForget() {
        AlertDialog.Builder(this)
            .setTitle("¿Olvidar el coche?")
            .setMessage("Tendrás que volver a emparejar con un código nuevo.")
            .setPositiveButton("Olvidar") { _, _ ->
                prefs.forgetCar()
                BridgeService.stop(this)
                BridgeState.update { BridgeState.Snapshot() }
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    // ------------------------------------------------------------------------------------------
    // Piezas de interfaz (mismos colores que Qonecta+)
    // ------------------------------------------------------------------------------------------

    private fun has(perm: String) = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, dp(20), OUTLINE)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        }
    }

    private fun pill(label: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(if (primary) ON_ACCENT else TEXT)
        background = if (primary) rounded(ACCENT, dp(26)) else rounded(SURFACE_TOP, dp(26), OUTLINE)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(10)
            })
        }
    }

    private fun permRow(label: String, granted: Boolean, onFix: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(text((if (granted) "✓ " else "✗ ") + label, 15f, if (granted) OK else BAD),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!granted) addView(pill("Dar") { onFix() })
    }

    private fun showUnitLogsDialog(file: java.io.File, logText: String) {
        val preview = if (logText.length > 6000) {
            "… (mostrando últimos 6.000 caracteres de ${logText.length})\n\n" + logText.takeLast(6000)
        } else {
            logText
        }
        AlertDialog.Builder(this)
            .setTitle("📋 ${file.name}")
            .setMessage(preview)
            .setPositiveButton("📤 Compartir registros") { _, _ ->
                shareUnitLogText(file, logText)
            }
            .setNeutralButton("📋 Copiar al portapapeles") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText(file.name, logText))
                toast("Registros copiados al portapapeles (${file.name})")
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun shareUnitLogText(file: java.io.File, logText: String) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Registros Qonecta Unit (${file.name})")
                putExtra(Intent.EXTRA_TEXT, logText)
            }
            startActivity(Intent.createChooser(send, "Compartir registros de Qonecta Unit"))
        }.onFailure { e ->
            toast("No se pudo compartir: ${e.message}")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
