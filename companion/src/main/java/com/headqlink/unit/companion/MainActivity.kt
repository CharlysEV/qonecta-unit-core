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
import com.headqlink.unit.companion.CompanionI18n.tr

/**
 * Pantalla principal de Qonecta Companion (ES / EN / DE / PT):
 * estado de la conexión con el coche, emparejamiento en 2 pasos, modo tutorial sincronizado,
 * actualización push en 1 toque y selector de idioma instantáneo.
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
    private var activeTab: Int = 0
    private var activeTutorialDialog: InstallTutorialDialog? = null
    private val stateListener: (BridgeState.Snapshot) -> Unit = { s ->
        // Quién manda: conectado a Qonecta Unit → el coche; desconectado → la cuenta Leapmotor.
        com.headqlink.unit.companion.cloud.CompanionCloud.setConnectedToUnit(s.phase == BridgeState.Phase.CONNECTED)
        render()
    }
    private val cloudListener: (com.headqlink.unit.companion.cloud.CompanionCloud.Snapshot) -> Unit = { snap ->
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
            0 to ("🔗" to tr("Conexión", "Connect", "Verbindung", "Ligação")),
            1 to ("📊" to tr("Viajes", "Trips", "Fahrten", "Viagens")),
            2 to ("🏠" to tr("Casa", "Home", "Zuhause", "Casa")),
            3 to ("🚗" to tr("Coche", "Car", "Fahrzeug", "Carro")),
            4 to ("🗺️" to tr("Ruta", "Route", "Route", "Rota")),
        )
        tabs.forEach { (idx, pair) ->
            val selected = activeTab == idx
            val tabBtn = TextView(this).apply {
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
        val appVer = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "0.1.19"
        val isExpert = prefs.expertMode
        val lg = CompanionI18n.currentLang()
        val localCarApk = GitHubReleaseUpdater.findBestLocalCarApk(this, appVer)
        val apkName = localCarApk?.name ?: "QonectaUnit_${appVer}-unit.apk"
        val (localVerName, localVerCode) = if (localCarApk != null) {
            GitHubReleaseUpdater.extractApkVersionMeta(this, localCarApk)
        } else {
            "$appVer-unit" to 0
        }
        val bestIp = GitHubReleaseUpdater.findBestHotspotOrLocalIp() ?: "192.168.43.1"

        // --- Cabecera limpia + mención por CharlysEV + Selector Idioma + Café/PayPal + Modo Experto ---
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("Qonecta Companion", 20.5f, TEXT, bold = true))
                addView(text("v$appVer · ${tr("por", "by", "von", "por")} CharlysEV · Leapmotor C10", 12f, FAINT))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        val langBtn = TextView(this).apply {
            text = "${lg.flag} ${lg.badge}"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(ACCENT)
            background = rounded(SURFACE_TOP, dp(20), ACCENT)
            setPadding(dp(9), dp(7), dp(9), dp(7))
            isClickable = true
            setOnClickListener { showLanguagePickerDialog() }
        }
        headerRow.addView(langBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(5)
        })

        val supportHeaderBtn = TextView(this).apply {
            text = "☕"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(WARN)
            background = rounded(SURFACE_TOP, dp(20), WARN)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            isClickable = true
            setOnClickListener { showSupportDialog() }
        }
        headerRow.addView(supportHeaderBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(5)
        })

        val expertBtn = TextView(this).apply {
            text = if (isExpert) "🛠️ ON" else "🛠️"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (isExpert) ON_ACCENT else DIM)
            background = if (isExpert) rounded(WARN, dp(20)) else rounded(SURFACE_TOP, dp(20), OUTLINE)
            setPadding(dp(10), dp(7), dp(10), dp(7))
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
                addView(text(
                    tr(
                        "🎓 ¿Primera vez instalando en tu Leapmotor C10?",
                        "🎓 First time installing on your Leapmotor C10?",
                        "🎓 Erstinstallation auf Ihrem Leapmotor C10?",
                        "🎓 Primeira vez a instalar no seu Leapmotor C10?",
                    ),
                    17f, ACCENT, bold = true,
                ))
                addView(text(
                    tr(
                        "Sigue el Modo Tutorial paso a paso y sincronizado en vivo para instalar Qonecta Unit en la pantalla del C10 y vincularlo a la primera.",
                        "Follow the live step-by-step Tutorial Mode to install Qonecta Unit on your C10 screen and pair it on the first try.",
                        "Folgen Sie dem synchronisierten Schritt-für-Schritt-Tutorial, um Qonecta Unit auf dem C10-Bildschirm zu installieren und sofort zu koppeln.",
                        "Siga o Modo Tutorial passo a passo sincronizado em tempo real para instalar o Qonecta Unit no ecrã do C10 e emparelhá-lo à primeira.",
                    ),
                    13.5f, TEXT,
                ).apply { setPadding(0, dp(4), 0, dp(10)) })
                addView(pill(
                    tr(
                        "🎓 Abrir Modo Tutorial paso a paso (C10)",
                        "🎓 Open step-by-step Tutorial Mode (C10)",
                        "🎓 Schritt-für-Schritt-Tutorial öffnen (C10)",
                        "🎓 Abrir Modo Tutorial passo a passo (C10)",
                    ),
                    primary = true,
                ) {
                    openTutorialDialog()
                })
            })
        }

        // ======================================================================================
        // TARJETA 1 · ESTADO DE CONEXIÓN Y OPCIONES MARCABLES (SOLO BLUETOOTH / WI-FI)
        // ======================================================================================
        root.addView(card().apply {
            val defaultCarLabel = tr("tu Leapmotor C10", "your Leapmotor C10", "Ihrem Leapmotor C10", "o seu Leapmotor C10")
            val carDisplay = s.carName.ifBlank { prefs.carName ?: prefs.carBtName ?: defaultCarLabel }
            val (statusTitle, statusColor) = when (s.phase) {
                BridgeState.Phase.CONNECTED -> tr(
                    "🟢 Conectado con $carDisplay",
                    "🟢 Connected to $carDisplay",
                    "🟢 Verbunden mit $carDisplay",
                    "🟢 Ligado a $carDisplay",
                ) to OK
                BridgeState.Phase.CONNECTING -> tr(
                    "🔵 Conectando con $carDisplay…",
                    "🔵 Connecting to $carDisplay…",
                    "🔵 Verbinde mit $carDisplay…",
                    "🔵 A ligar a $carDisplay…",
                ) to ACCENT
                BridgeState.Phase.PAIRING -> tr(
                    "🔵 Emparejando con el coche…",
                    "🔵 Pairing with the car…",
                    "🔵 Kopple mit dem Fahrzeug…",
                    "🔵 A emparelhar com o carro…",
                ) to ACCENT
                BridgeState.Phase.SEARCHING -> tr(
                    "🟡 Buscando el coche…",
                    "🟡 Searching for the car…",
                    "🟡 Suche nach dem Fahrzeug…",
                    "🟡 À procura do carro…",
                ) to WARN
                BridgeState.Phase.STOPPED -> (if (prefs.isPaired) {
                    tr("⚪ En espera", "⚪ Standby", "⚪ Bereitschaft", "⚪ Em espera")
                } else {
                    tr(
                        "⚪ Pendiente de vincular por primera vez",
                        "⚪ Waiting for first-time pairing",
                        "⚪ Bereit für die erstmalige Kopplung",
                        "⚪ Pendente de emparelhamento inicial",
                    )
                }) to FAINT
            }
            addView(text(statusTitle, 19f, statusColor, bold = true))

            val subInfo = buildString {
                if (s.transport.isNotBlank()) {
                    append(tr("Vía ${s.transport}", "Via ${s.transport}", "Über ${s.transport}", "Via ${s.transport}"))
                }
                if (prefs.useBluetooth && !prefs.useWifi) {
                    if (isNotEmpty()) append("  ·  ")
                    append(tr(
                        "Modo Solo Bluetooth (sin Wi-Fi)",
                        "Bluetooth-Only Mode (no Wi-Fi)",
                        "Nur-Bluetooth-Modus (ohne WLAN)",
                        "Modo Só Bluetooth (sem Wi-Fi)",
                    ))
                }
                if (s.carAppVersion.isNotBlank()) {
                    if (isNotEmpty()) append("  ·  ")
                    append(tr(
                        "Coche en v${s.carAppVersion}",
                        "Car on v${s.carAppVersion}",
                        "Fahrzeug auf v${s.carAppVersion}",
                        "Carro na v${s.carAppVersion}",
                    ))
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
                    append(tr("🔄 Coche sincronizado: ", "🔄 Car synced: ", "🔄 Fahrzeug synchron: ", "🔄 Carro sincronizado: "))
                    append(if (unitTel.unitBtCompanion) "☑ BT" else "☐ BT")
                    append(" · ")
                    append(
                        if (unitTel.unitWifi5g) "☑ Wi-Fi 5G"
                        else tr("☐ Wi-Fi 5G apagado", "☐ 5G Wi-Fi off", "☐ 5G-WLAN aus", "☐ Wi-Fi 5G desligado")
                    )
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
            transportBox.addView(text(
                tr(
                    "Opciones de conexión (marca o desmarca libremente):",
                    "Connection options (check or uncheck freely):",
                    "Verbindungsoptionen (frei ein-/ausschaltbar):",
                    "Opções de ligação (marque ou desmarque livremente):",
                ),
                13f, FAINT, bold = true,
            ))

            transportBox.addView(
                buildTransportToggleRow(
                    checked = prefs.useBluetooth,
                    title = tr(
                        "🔵 Bluetooth (Datos, Viajes, Tarifas y Rutas)",
                        "🔵 Bluetooth (Data, Trips, Tariffs & Routes)",
                        "🔵 Bluetooth (Daten, Fahrten, Tarife & Routen)",
                        "🔵 Bluetooth (Dados, Viagens, Tarifas e Rotas)",
                    ),
                    subtitle = if (prefs.useBluetooth && !prefs.useWifi) {
                        tr(
                            "Activo en solitario · Cero Wi-Fi 5 GHz y cero cortes de radio",
                            "Active solo · Zero 5 GHz Wi-Fi & zero FM/DAB radio dropouts",
                            "Allein aktiv · Kein 5-GHz-WLAN & null FM/DAB-Radioaussetzer",
                            "Ativo isoladamente · Zero Wi-Fi 5 GHz e zero cortes de rádio",
                        )
                    } else {
                        tr(
                            "Conecta con Qonecta Unit usando solo el Bluetooth del coche",
                            "Connects to Qonecta Unit using only the car's Bluetooth",
                            "Verbindet sich mit Qonecta Unit rein über das Fahrzeug-Bluetooth",
                            "Liga ao Qonecta Unit usando apenas o Bluetooth do carro",
                        )
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
                    title = tr(
                        "📡 Wi-Fi rápido (Android Auto 5 GHz / Hotspot)",
                        "📡 Fast Wi-Fi (Android Auto 5 GHz / Hotspot)",
                        "📡 Schnelles WLAN (Android Auto 5 GHz / Hotspot)",
                        "📡 Wi-Fi rápido (Android Auto 5 GHz / Hotspot)",
                    ),
                    subtitle = if (!prefs.useWifi) {
                        tr(
                            "Desmarcado: nunca buscará redes Wi-Fi, todo irá limpio por Bluetooth",
                            "Unchecked: never scans Wi-Fi networks, everything runs cleanly over Bluetooth",
                            "Deaktiviert: sucht nie nach WLAN-Netzen, alles läuft sauber über Bluetooth",
                            "Desmarcado: nunca procura redes Wi-Fi, tudo funciona limpo por Bluetooth",
                        )
                    } else {
                        tr(
                            "Aprovecha el Wi-Fi cuando ya llevas Android Auto o Hotspot activo",
                            "Uses Wi-Fi whenever Android Auto or your Hotspot is already active",
                            "Nutzt WLAN, wenn Android Auto oder Ihr Hotspot bereits aktiv ist",
                            "Aproveita o Wi-Fi quando já tem Android Auto ou Hotspot ativo",
                        )
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
                        if (chosenBt != null) {
                            tr(
                                "🚗 BT del C10: $chosenBt",
                                "🚗 C10 BT: $chosenBt",
                                "🚗 C10-BT: $chosenBt",
                                "🚗 BT do C10: $chosenBt",
                            )
                        } else {
                            tr(
                                "🚗 BT del C10: Automático (elige tu coche si tarda)",
                                "🚗 C10 BT: Automatic (pick your car if slow)",
                                "🚗 C10-BT: Automatisch (Fahrzeug wählen, falls langsam)",
                                "🚗 BT do C10: Automático (escolha o carro se demorar)",
                            )
                        },
                        13f,
                        if (chosenBt != null) OK else WARN,
                        bold = true,
                    ),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                val pickSmallBtn = TextView(this@MainActivity).apply {
                    text = if (chosenBt != null) {
                        tr("Cambiar BT", "Change BT", "BT ändern", "Mudar BT")
                    } else {
                        tr("Elegir BT", "Pick BT", "BT wählen", "Escolher BT")
                    }
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
                permBox.addView(text(
                    tr(
                        "⚠️ Activa estos permisos para que funcione todo automático:",
                        "⚠️ Grant these permissions so everything runs automatically:",
                        "⚠️ Aktivieren Sie diese Berechtigungen für den Automatikbetrieb:",
                        "⚠️ Ative estas permissões para que tudo funcione automaticamente:",
                    ),
                    13.5f, WARN, bold = true,
                ))
                if (missingBt) {
                    permBox.addView(permRow(
                        tr(
                            "Bluetooth (Dispositivos cercanos)",
                            "Bluetooth (Nearby devices)",
                            "Bluetooth (Geräte in der Nähe)",
                            "Bluetooth (Dispositivos próximos)",
                        ),
                        false,
                    ) {
                        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_PERMS)
                    })
                }
                if (missingNotif) {
                    permBox.addView(permRow(
                        tr("Notificaciones", "Notifications", "Benachrichtigungen", "Notificações"),
                        false,
                    ) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERMS)
                    })
                }
                if (missingOverlay) {
                    permBox.addView(permRow(
                        tr(
                            "Abrir Google Maps automáticamente",
                            "Launch Google Maps automatically",
                            "Google Maps automatisch öffnen",
                            "Abrir o Google Maps automaticamente",
                        ),
                        false,
                    ) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    })
                }
                if (missingBattery) {
                    permBox.addView(permRow(
                        tr(
                            "Sin ahorro de batería",
                            "Unrestricted battery",
                            "Keine Akku-Optimierung",
                            "Sem poupança de bateria",
                        ),
                        false,
                    ) {
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
                stepBox.addView(text(
                    tr(
                        "Paso 1 · Elige el Bluetooth de tu coche",
                        "Step 1 · Select your car's Bluetooth",
                        "Schritt 1 · Fahrzeug-Bluetooth auswählen",
                        "Passo 1 · Escolha o Bluetooth do seu carro",
                    ),
                    15f, TEXT, bold = true,
                ))
                stepBox.addView(text(
                    if (chosenBt != null) {
                        tr(
                            "✓ Seleccionado: $chosenBt",
                            "✓ Selected: $chosenBt",
                            "✓ Ausgewählt: $chosenBt",
                            "✓ Selecionado: $chosenBt",
                        )
                    } else {
                        tr(
                            "Toca abajo y selecciona el manos libres Bluetooth de tu coche.",
                            "Tap below and select your car's hands-free Bluetooth.",
                            "Tippen Sie unten und wählen Sie die Bluetooth-Freisprecheinrichtung Ihres Fahrzeugs.",
                            "Toque abaixo e selecione o mãos-livres Bluetooth do seu carro.",
                        )
                    },
                    13.5f, if (chosenBt != null) OK else DIM,
                ).apply { setPadding(0, dp(4), 0, dp(8)) })
                stepBox.addView(row(
                    pill(
                        if (chosenBt != null) {
                            tr("Cambiar Bluetooth", "Change Bluetooth", "Bluetooth ändern", "Mudar Bluetooth")
                        } else {
                            tr("1. Elegir coche", "1. Pick car", "1. Fahrzeug wählen", "1. Escolher carro")
                        },
                        primary = chosenBt == null,
                    ) { pickBondedDevice() },
                    pill(tr("Buscar cercano", "Scan nearby", "In der Nähe suchen", "Procurar próximo")) { associateCar() },
                ))

                stepBox.addView(text(
                    tr(
                        "Paso 2 · Código de 6 cifras del coche",
                        "Step 2 · 6-digit code from the car",
                        "Schritt 2 · 6-stelliger Code vom Fahrzeug",
                        "Passo 2 · Código de 6 dígitos do carro",
                    ),
                    15f, TEXT, bold = true,
                ).apply {
                    setPadding(0, dp(14), 0, dp(4))
                })
                stepBox.addView(text(
                    tr(
                        "En Qonecta Unit del coche entra en Ajustes Extendidos → Qonecta Companion → «Emparejar móvil» y escribe el número:",
                        "In Qonecta Unit on the car open Extended Settings → Qonecta Companion → 'Pair phone' and enter the code:",
                        "Öffnen Sie in Qonecta Unit im Fahrzeug Erweiterte Einstellungen → Qonecta Companion → „Smartphone koppeln“ und geben Sie den Code ein:",
                        "No Qonecta Unit do carro aceda a Definições Avançadas → Qonecta Companion → «Emparelhar telemóvel» e introduza o código:",
                    ),
                    13.5f, DIM,
                ).apply { setPadding(0, 0, 0, dp(8)) })

                val codeInput = buildPairCodeInput()
                stepBox.addView(codeInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))
                stepBox.addView(pill(
                    tr(
                        "🔗 Vincular con mi coche",
                        "🔗 Pair with my car",
                        "🔗 Mit meinem Fahrzeug koppeln",
                        "🔗 Emparelhar com o meu carro",
                    ),
                    primary = true,
                ) {
                    val code = codeInput.text?.toString()?.trim().orEmpty()
                    if (code.length != 6) {
                        toast(tr(
                            "Escribe las 6 cifras que muestra la pantalla del coche",
                            "Enter the 6-digit code shown on the car screen",
                            "Geben Sie den 6-stelligen Code vom Fahrzeugbildschirm ein",
                            "Introduza os 6 dígitos apresentados no ecrã do carro",
                        ))
                    } else {
                        draftPairCode = ""
                        BridgeService.pair(this@MainActivity, code)
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(10) })

                addView(stepBox)
            } else {
                addView(row(
                    pill(
                        if (s.phase == BridgeState.Phase.STOPPED) {
                            tr("Conectar ahora", "Connect now", "Jetzt verbinden", "Ligar agora")
                        } else {
                            tr("Reconectar", "Reconnect", "Neu verbinden", "Religar")
                        },
                        primary = s.phase != BridgeState.Phase.CONNECTED,
                    ) {
                        if (!prefs.useBluetooth && !prefs.useWifi) {
                            prefs.useBluetooth = true
                        }
                        prefs.enabled = true
                        BridgeService.reconnectNow(this@MainActivity)
                        render()
                    },
                    pill(tr("Pausar", "Pause", "Pausieren", "Pausar")) {
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
            addView(text(
                tr(
                    "⚡ Actualizar Coche en 1 Toque",
                    "⚡ 1-Tap Car Update",
                    "⚡ 1-Tipp-Fahrzeug-Update",
                    "⚡ Atualizar Carro num Toque",
                ),
                19f, ACCENT, bold = true,
            ))
            addView(text(
                tr(
                    "Envía la nueva versión directamente del móvil al coche y abre el instalador en la pantalla del C10 sin pendrives ni navegador.",
                    "Pushes the new release straight from your phone to the car and opens the installer on the C10 screen with no USB drive or browser.",
                    "Sendet die neue Version direkt vom Smartphone ans Fahrzeug und öffnet den Installer auf dem C10-Bildschirm ohne USB-Stick oder Browser.",
                    "Envia a nova versão diretamente do telemóvel para o carro e abre o instalador no ecrã do C10 sem pendrive nem navegador.",
                ),
                14f, DIM,
            ).apply { setPadding(0, dp(4), 0, dp(10)) })

            val summaryBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(BG, dp(14), OUTLINE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }

            if (localCarApk != null) {
                val sizeMb = String.format(java.util.Locale.US, "%.1f MB", localCarApk.length() / (1024.0 * 1024.0))
                summaryBox.addView(text(
                    tr(
                        "📦 Lista en tu móvil: v$localVerName ($sizeMb)",
                        "📦 Ready on your phone: v$localVerName ($sizeMb)",
                        "📦 Bereit auf dem Smartphone: v$localVerName ($sizeMb)",
                        "📦 Pronta no telemóvel: v$localVerName ($sizeMb)",
                    ),
                    14.5f, TEXT, bold = true,
                ))
                val cmpTextAndColor: Pair<String, Int> = when {
                    s.phase != BridgeState.Phase.CONNECTED ->
                        tr(
                            "Conecta con el coche arriba para enviarla en 1 toque.",
                            "Connect to the car above to push it in 1 tap.",
                            "Oben mit dem Fahrzeug verbinden, um sie mit 1 Tipp zu senden.",
                            "Ligue-se ao carro acima para enviá-la num toque.",
                        ) to FAINT
                    s.carVersionCode == 0 ->
                        tr(
                            "⚠️ Tu coche tiene una versión anterior a v0.1.17. Instálala por última vez con el Tutorial de abajo y las próximas ya serán en 1 toque.",
                            "⚠️ Your car runs a version older than v0.1.17. Install once more via the Tutorial below; all future updates will be 1-tap.",
                            "⚠️ Ihr Fahrzeug nutzt eine Version älter als v0.1.17. Einmalig über das Tutorial unten installieren – alle künftigen Updates gehen per 1-Tipp.",
                            "⚠️ O seu carro tem uma versão anterior à v0.1.17. Instale uma última vez pelo Tutorial abaixo e as próximas serão num só toque.",
                        ) to WARN
                    localVerCode > s.carVersionCode ->
                        tr(
                            "✨ ¡Actualización disponible! Tu coche tiene v${s.carAppVersion} → subirá a v$localVerName.",
                            "✨ Update available! Your car runs v${s.carAppVersion} → will upgrade to v$localVerName.",
                            "✨ Update verfügbar! Fahrzeug hat v${s.carAppVersion} → wird auf v$localVerName aktualisiert.",
                            "✨ Atualização disponível! O seu carro tem v${s.carAppVersion} → passará para v$localVerName.",
                        ) to OK
                    localVerCode == s.carVersionCode && localVerCode > 0 ->
                        tr(
                            "✓ Tu coche ya está al día (v${s.carAppVersion}). Puedes reinstalarla si quieres.",
                            "✓ Your car is already up to date (v${s.carAppVersion}). You can reinstall if needed.",
                            "✓ Ihr Fahrzeug ist bereits aktuell (v${s.carAppVersion}). Bei Bedarf erneut installierbar.",
                            "✓ O seu carro já está atualizado (v${s.carAppVersion}). Pode reinstalar se quiser.",
                        ) to OK
                    else ->
                        tr(
                            "Coche conectado en v${s.carAppVersion.ifBlank { "actual" }}.",
                            "Car connected on v${s.carAppVersion.ifBlank { "current" }}.",
                            "Fahrzeug verbunden auf v${s.carAppVersion.ifBlank { "aktuell" }}.",
                            "Carro ligado na v${s.carAppVersion.ifBlank { "atual" }}.",
                        ) to DIM
                }
                summaryBox.addView(text(cmpTextAndColor.first, 13.5f, cmpTextAndColor.second).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            } else {
                summaryBox.addView(text(
                    tr(
                        "⚠️ Aún no hay ningún APK descargado en el móvil. Pulsa «☁️ Buscar nueva versión en Internet».",
                        "⚠️ No car APK downloaded on this phone yet. Tap '☁️ Check for new release online'.",
                        "⚠️ Noch keine Fahrzeug-APK auf dem Smartphone. Tippen Sie auf „☁️ Online nach neuer Version suchen“.",
                        "⚠️ Ainda não há nenhum APK transferido no telemóvel. Toque em «☁️ Procurar nova versão na Internet».",
                    ),
                    14f, WARN,
                ))
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
                s.otaInProgress -> tr(
                    "⏳ Enviando actualización al coche…",
                    "⏳ Sending update to car…",
                    "⏳ Sende Update ans Fahrzeug…",
                    "⏳ A enviar atualização para o carro…",
                )
                localCarApk != null -> tr(
                    "⚡ Actualizar coche ahora (v$localVerName)",
                    "⚡ Update car now (v$localVerName)",
                    "⚡ Fahrzeug jetzt aktualisieren (v$localVerName)",
                    "⚡ Atualizar carro agora (v$localVerName)",
                )
                else -> tr(
                    "⚡ Actualizar coche ahora",
                    "⚡ Update car now",
                    "⚡ Fahrzeug jetzt aktualisieren",
                    "⚡ Atualizar carro agora",
                )
            }
            addView(pill(pushBtnLabel, primary = true) {
                if (s.otaInProgress) {
                    toast(tr(
                        "Ya se está enviando la actualización al coche…",
                        "Update is already being sent to the car…",
                        "Update wird bereits ans Fahrzeug gesendet…",
                        "A atualização já está a ser enviada para o carro…",
                    ))
                    return@pill
                }
                val target = GitHubReleaseUpdater.findBestLocalCarApk(this@MainActivity, appVer)
                if (target == null) {
                    toast(tr(
                        "Primero descarga la última versión con el botón de abajo",
                        "Download the latest version first using the button below",
                        "Laden Sie zuerst die neueste Version über die Schaltfläche unten herunter",
                        "Transfira primeiro a versão mais recente com o botão abaixo",
                    ))
                } else {
                    BridgeService.pushApkUpdateToCar(this@MainActivity, target)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })

            val cloudCheckBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(pill(
                tr(
                    "☁️ Buscar nueva versión en Internet",
                    "☁️ Check for new release online",
                    "☁️ Online nach neuer Version suchen",
                    "☁️ Procurar nova versão na Internet",
                )
            ) {
                cloudCheckBox.removeAllViews()
                val checkingTv = text(
                    tr(
                        "⏳ Buscando nuevas versiones en GitHub…",
                        "⏳ Checking GitHub Releases…",
                        "⏳ Suche auf GitHub Releases…",
                        "⏳ A procurar novas versões no GitHub…",
                    ),
                    13.5f, ACCENT,
                ).apply {
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
                        val errTxt = if (errors.isNotEmpty()) errors.joinToString("\n") else tr(
                            "No se encontraron APKs publicados.",
                            "No published APKs found.",
                            "Keine veröffentlichten APKs gefunden.",
                            "Não foram encontrados APKs publicados.",
                        )
                        cloudCheckBox.addView(text("⚠️ $errTxt", 13.5f, WARN).apply { setPadding(0, dp(8), 0, 0) })
                        return@fetchDualReleases
                    }

                    if (newestCar != null) {
                        val dlStatusTv = text(
                            tr(
                                "⬇️ Descargando ${newestCar.assetName} (${newestCar.tagName})…",
                                "⬇️ Downloading ${newestCar.assetName} (${newestCar.tagName})…",
                                "⬇️ Lade ${newestCar.assetName} (${newestCar.tagName}) herunter…",
                                "⬇️ A transferir ${newestCar.assetName} (${newestCar.tagName})…",
                            ),
                            13.5f, ACCENT,
                        ).apply {
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
                                    dlStatusTv.text = tr(
                                        "✅ Descargada ${file.name}. ¡Ya puedes pulsar «⚡ Actualizar coche ahora»!",
                                        "✅ Downloaded ${file.name}. You can now tap '⚡ Update car now'!",
                                        "✅ ${file.name} heruntergeladen. Sie können jetzt auf „⚡ Fahrzeug jetzt aktualisieren“ tippen!",
                                        "✅ ${file.name} transferido. Já pode tocar em «⚡ Atualizar carro agora»!",
                                    )
                                    dlStatusTv.setTextColor(OK)
                                    render()
                                }.onFailure { err ->
                                    dlStatusTv.text = tr(
                                        "⚠️ No se pudo descargar: ${err.message}",
                                        "⚠️ Download failed: ${err.message}",
                                        "⚠️ Download fehlgeschlagen: ${err.message}",
                                        "⚠️ Falha ao transferir: ${err.message}",
                                    )
                                    dlStatusTv.setTextColor(BAD)
                                }
                            },
                        )
                    }

                    if (newestComp != null) {
                        cloudCheckBox.addView(pill(
                            tr(
                                "📲 Instalar también Companion ${newestComp.tagName} en este móvil",
                                "📲 Also install Companion ${newestComp.tagName} on this phone",
                                "📲 Companion ${newestComp.tagName} auch auf diesem Smartphone installieren",
                                "📲 Instalar também o Companion ${newestComp.tagName} neste telemóvel",
                            )
                        ) {
                            toast(tr(
                                "Descargando ${newestComp.assetName}…",
                                "Downloading ${newestComp.assetName}…",
                                "Lade ${newestComp.assetName} herunter…",
                                "A transferir ${newestComp.assetName}…",
                            ))
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
                text(
                    tr(
                        "🎓 Tutorial instalar Qonecta Unit en el C10",
                        "🎓 Tutorial: Install Qonecta Unit on C10",
                        "🎓 Tutorial: Qonecta Unit auf dem C10 installieren",
                        "🎓 Tutorial: Instalar Qonecta Unit no C10",
                    ),
                    17f, TEXT, bold = true,
                ),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            headerToggle.addView(text(
                if (expanded) {
                    tr("▲ Ocultar", "▲ Hide", "▲ Ausblenden", "▲ Ocultar")
                } else {
                    tr("▼ Pasos rápidos", "▼ Quick steps", "▼ Kurzschritte", "▼ Passos rápidos")
                },
                13f, ACCENT, bold = true,
            ))
            addView(headerToggle)

            addView(text(
                tr(
                    "Asistente paso a paso y sincronizado en tiempo real (detecta tu APK, el USB, el Hotspot y te avisa en directo cuando Vivaldi del C10 abre el portal):",
                    "Real-time synced step-by-step wizard (detects your APK, USB export, Hotspot, and alerts you live the instant the C10's Vivaldi browser opens the portal):",
                    "Echtzeit-synchronisierter Assistent (erkennt APK, USB, Hotspot und meldet sofort live, sobald der Vivaldi-Browser im C10 das Portal öffnet):",
                    "Assistente passo a passo sincronizado em tempo real (deteta o APK, a pendrive USB, o Hotspot e avisa em direto assim que o Vivaldi do C10 abre o portal):",
                ),
                13.5f, DIM,
            ).apply { setPadding(0, dp(6), 0, dp(10)) })

            addView(pill(
                tr(
                    "🎓 Abrir Modo Tutorial interactivo (5 pasos en vivo)",
                    "🎓 Open interactive Tutorial Mode (5 live steps)",
                    "🎓 Interaktives Tutorial öffnen (5 Live-Schritte)",
                    "🎓 Abrir Modo Tutorial interativo (5 passos em direto)",
                ),
                primary = true,
            ) {
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
                    tr(
                        "Resumen rápido sin asistente:\n" +
                            "1️⃣ Guarda el APK en un Pendrive USB-C y enchúfalo al C10.\n" +
                            "2️⃣ Enciende el Hotspot del móvil y conecta el Wi-Fi del C10 (sin Proxy ni DNS).\n" +
                            "3️⃣ En Vivaldi del coche abre:  http://$bestIp:8788/portal\n" +
                            "4️⃣ Toca el Botón Amarillo «ABRIR ARCHIVOS DEL COCHE» y pulsa las dos flechas ⤢ de la esquina del APK.",
                        "Quick summary without wizard:\n" +
                            "1️⃣ Save the APK onto a USB-C drive and plug it into the C10.\n" +
                            "2️⃣ Turn on your phone's Hotspot and connect the C10 Wi-Fi (no Proxy or DNS needed).\n" +
                            "3️⃣ In the car's Vivaldi browser open:  http://$bestIp:8788/portal\n" +
                            "4️⃣ Tap the Yellow Button 'OPEN CAR FILES' and tap the two diagonal arrows ⤢ on the APK card.",
                        "Kurzanleitung ohne Assistent:\n" +
                            "1️⃣ APK auf einem USB-C-Stick speichern und im C10 einstecken.\n" +
                            "2️⃣ Smartphone-Hotspot einschalten und C10-WLAN verbinden (kein Proxy/DNS nötig).\n" +
                            "3️⃣ Im Vivaldi-Browser des Fahrzeugs öffnen:  http://$bestIp:8788/portal\n" +
                            "4️⃣ Gelbe Taste „DATEIEN DES FAHRZEUGS ÖFFNEN“ tippen und auf die zwei Pfeile ⤢ der APK-Karte tippen.",
                        "Resumo rápido sem assistente:\n" +
                            "1️⃣ Guarde o APK numa Pendrive USB-C e ligue-a ao C10.\n" +
                            "2️⃣ Ligue o Hotspot do telemóvel e ligue o Wi-Fi do C10 (sem Proxy nem DNS).\n" +
                            "3️⃣ No navegador Vivaldi do carro abra:  http://$bestIp:8788/portal\n" +
                            "4️⃣ Toque no Botão Amarelo «ABRIR FICHEIROS DO CARRO» e toque nas duas setas ⤢ do canto do APK.",
                    ),
                    13.5f, TEXT,
                ))
                addView(stepsBox)

                val coldStatusTv = text(
                    if (GitHubReleaseUpdater.isHosting) {
                        tr(
                            "🟢 Servidor web listo en http://$bestIp:8788/portal ($apkName)",
                            "🟢 Web server ready at http://$bestIp:8788/portal ($apkName)",
                            "🟢 Webserver bereit unter http://$bestIp:8788/portal ($apkName)",
                            "🟢 Servidor web pronto em http://$bestIp:8788/portal ($apkName)",
                        )
                    } else {
                        tr(
                            "⚪ Pulsa «🔄 Actualizar IP» tras encender el Hotspot",
                            "⚪ Tap '🔄 Refresh IP' after turning on Hotspot",
                            "⚪ Nach dem Einschalten des Hotspots auf „🔄 IP aktualisieren“ tippen",
                            "⚪ Toque em «🔄 Atualizar IP» após ligar o Hotspot",
                        )
                    },
                    13.5f,
                    if (GitHubReleaseUpdater.isHosting) OK else WARN,
                    bold = true,
                ).apply { setPadding(0, dp(10), 0, dp(8)) }
                addView(coldStatusTv)

                addView(pill(
                    tr(
                        "💾 1. Guardar APK en Pendrive USB ($apkName)",
                        "💾 1. Save APK to USB Drive ($apkName)",
                        "💾 1. APK auf USB-Stick speichern ($apkName)",
                        "💾 1. Guardar APK na Pendrive USB ($apkName)",
                    ),
                    primary = false,
                ) {
                    val target = GitHubReleaseUpdater.findBestLocalCarApk(this@MainActivity, appVer)
                    if (target == null) {
                        toast(tr(
                            "Primero pulsa «☁️ Buscar nueva versión en Internet» arriba",
                            "First tap '☁️ Check for new release online' above",
                            "Tippen Sie zuerst oben auf „☁️ Online nach neuer Version suchen“",
                            "Toque primeiro em «☁️ Procurar nova versão na Internet» acima",
                        ))
                    } else {
                        exportApkToUsbDrive(target)
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                addView(row(
                    pill(tr("📶 2. Encender Hotspot", "📶 2. Turn on Hotspot", "📶 2. Hotspot starten", "📶 2. Ligar Hotspot")) { openTetherSettings() },
                    pill(tr("🔄 3. Actualizar IP", "🔄 3. Refresh IP", "🔄 3. IP aktualisieren", "🔄 3. Atualizar IP")) {
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
        // TARJETA 4 · APOYAR EL PROYECTO (CAFÉ KO-FI Y PAYPAL)
        // ======================================================================================
        root.addView(card().apply {
            addView(text(
                tr(
                    "☕ Apoyar el proyecto · por CharlysEV",
                    "☕ Support the project · by CharlysEV",
                    "☕ Projekt unterstützen · von CharlysEV",
                    "☕ Apoiar o projeto · por CharlysEV",
                ),
                17f, WARN, bold = true,
            ))
            addView(text(
                tr(
                    "Qonecta Unit y Qonecta Companion son proyectos creados para la comunidad Leapmotor. Si te hacen más cómodos tus viajes, puedes invitar a un café o apoyar su desarrollo:",
                    "Qonecta Unit and Qonecta Companion are open community tools built for Leapmotor owners. If they make your drives better, you can buy a coffee or support development:",
                    "Qonecta Unit und Qonecta Companion wurden für die Leapmotor-Community entwickelt. Wenn sie Ihre Fahrten angenehmer machen, können Sie gerne einen Kaffee spendieren:",
                    "O Qonecta Unit e o Qonecta Companion são projetos criados para a comunidade Leapmotor. Se tornam as suas viagens mais cómodas, pode oferecer um café ou apoiar o desenvolvimento:",
                ),
                13.5f, DIM,
            ).apply { setPadding(0, dp(4), 0, dp(12)) })
            addView(row(
                pill(tr("☕ Invitar a un café", "☕ Buy me a coffee", "☕ Kaffee spendieren", "☕ Oferecer um café"), primary = true) {
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

        root.addView(text(
            tr(
                "🛠️ MODO EXPERTO · AJUSTES AVANZADOS",
                "🛠️ EXPERT MODE · ADVANCED SETTINGS",
                "🛠️ EXPERTENMODUS · ERWEITERTE EINSTELLUNGEN",
                "🛠️ MODO ESPECIALISTA · DEFINIÇÕES AVANÇADAS",
            ),
            14f, WARN, bold = true,
        ).apply {
            setPadding(dp(4), dp(18), dp(4), dp(2))
        })

        // --- Experto 1: Re-emparejamiento manual Bluetooth + Código de 6 cifras + Olvidar coche ---
        root.addView(card().apply {
            addView(text(
                tr(
                    "🔑 Gestión Manual de Vínculo y Bluetooth",
                    "🔑 Manual Pairing & Bluetooth Management",
                    "🔑 Manuelle Kopplungs- & Bluetooth-Verwaltung",
                    "🔑 Gestão Manual de Emparelhamento e Bluetooth",
                ),
                17.5f, TEXT, bold = true,
            ))
            val car = prefs.carBtName ?: prefs.carBtAddress
            addView(text(
                if (car != null) {
                    tr(
                        "Bluetooth asociado: $car (${prefs.carBtAddress ?: "—"})",
                        "Associated Bluetooth: $car (${prefs.carBtAddress ?: "—"})",
                        "Verknüpftes Bluetooth: $car (${prefs.carBtAddress ?: "—"})",
                        "Bluetooth associado: $car (${prefs.carBtAddress ?: "—"})",
                    )
                } else {
                    tr(
                        "Ningún Bluetooth seleccionado manualmente.",
                        "No Bluetooth device manually selected.",
                        "Kein Bluetooth-Gerät manuell ausgewählt.",
                        "Nenhum Bluetooth selecionado manualmente.",
                    )
                },
                14f, if (car != null) OK else DIM,
            ).apply { setPadding(0, dp(4), 0, dp(10)) })
            addView(row(
                pill(tr("Lista Bluetooth", "Bluetooth list", "Bluetooth-Liste", "Lista Bluetooth")) { pickBondedDevice() },
                pill(tr("Asociar CDM", "Associate CDM", "CDM verknüpfen", "Associar CDM")) { associateCar() },
            ))

            addView(text(
                tr(
                    "Forzar nuevo emparejamiento con código de 6 cifras:",
                    "Force re-pairing with a new 6-digit code:",
                    "Neue Kopplung mit 6-stelligem Code erzwingen:",
                    "Forçar novo emparelhamento com código de 6 dígitos:",
                ),
                13.5f, DIM,
            ).apply {
                setPadding(0, dp(12), 0, dp(6))
            })
            val codeInput = buildPairCodeInput()
            addView(codeInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
            addView(row(
                pill(tr("Volver a emparejar", "Re-pair", "Neu koppeln", "Voltar a emparelhar"), primary = true) {
                    val code = codeInput.text?.toString()?.trim().orEmpty()
                    if (code.length != 6) {
                        toast(tr(
                            "Escribe las 6 cifras del coche",
                            "Enter the 6 digits from the car",
                            "Geben Sie den 6-stelligen Fahrzeugcode ein",
                            "Introduza os 6 dígitos do carro",
                        ))
                    } else {
                        draftPairCode = ""
                        BridgeService.pair(this@MainActivity, code)
                    }
                },
                pill(tr("Olvidar coche", "Forget car", "Fahrzeug entfernen", "Esquecer carro")) { confirmForget() },
            ).apply { setPadding(0, dp(10), 0, 0) })
        })

        // --- Experto 2: Servidor Portal Cautivo (:8788 / :8080), PAC y DNS Personalizado ---
        root.addView(card().apply {
            val activeIpsDesc = GitHubReleaseUpdater.describeActiveNetworkIps()
            addView(text(
                tr(
                    "📡 Servidor HTTP / Portal Cautivo / DNS Manual",
                    "📡 HTTP Server / Captive Portal / Manual DNS",
                    "📡 HTTP-Server / Captive Portal / Manuelles DNS",
                    "📡 Servidor HTTP / Portal Cativo / DNS Manual",
                ),
                17.5f, TEXT, bold = true,
            ))
            addView(text(
                tr(
                    "Interfaces activas: $activeIpsDesc\n" +
                        "• Portal Web: http://$bestIp:8788/portal  (secundario :8080)\n" +
                        "• Descarga directa APK: http://$bestIp:8788/download/com.tencent.qqmusiccar.apk\n" +
                        "• PAC Proxy: http://$bestIp:8788/proxy.pac",
                    "Active interfaces: $activeIpsDesc\n" +
                        "• Web Portal: http://$bestIp:8788/portal  (secondary :8080)\n" +
                        "• Direct APK download: http://$bestIp:8788/download/com.tencent.qqmusiccar.apk\n" +
                        "• PAC Proxy: http://$bestIp:8788/proxy.pac",
                    "Aktive Schnittstellen: $activeIpsDesc\n" +
                        "• Webportal: http://$bestIp:8788/portal  (sekundär :8080)\n" +
                        "• Direkter APK-Download: http://$bestIp:8788/download/com.tencent.qqmusiccar.apk\n" +
                        "• PAC-Proxy: http://$bestIp:8788/proxy.pac",
                    "Interfaces ativas: $activeIpsDesc\n" +
                        "• Portal Web: http://$bestIp:8788/portal  (secundário :8080)\n" +
                        "• Transferência direta APK: http://$bestIp:8788/download/com.tencent.qqmusiccar.apk\n" +
                        "• PAC Proxy: http://$bestIp:8788/proxy.pac",
                ),
                13f, DIM,
            ).apply { setPadding(0, dp(6), 0, dp(8)) })

            val srvLogTv = text(
                GitHubReleaseUpdater.lastEventLog.ifBlank {
                    if (GitHubReleaseUpdater.isHosting) {
                        tr(
                            "🟢 Servidor escuchando en 0.0.0.0:8788 y :8080",
                            "🟢 Server listening on 0.0.0.0:8788 & :8080",
                            "🟢 Server lauscht auf 0.0.0.0:8788 & :8080",
                            "🟢 Servidor à escuta em 0.0.0.0:8788 e :8080",
                        )
                    } else {
                        tr("⚪ Servidor detenido", "⚪ Server stopped", "⚪ Server gestoppt", "⚪ Servidor parado")
                    }
                },
                13f, if (GitHubReleaseUpdater.isHosting) OK else FAINT,
            ).apply { setPadding(0, 0, 0, dp(8)) }
            addView(srvLogTv)

            addView(row(
                pill(tr("Reiniciar :8788", "Restart :8788", "Neustart :8788", "Reiniciar :8788"), primary = true) {
                    val target = GitHubReleaseUpdater.findBestLocalCarApk(this@MainActivity, appVer)
                    if (target == null) {
                        toast(tr("Sin APK local", "No local APK", "Keine lokale APK", "Sem APK local"))
                    } else {
                        GitHubReleaseUpdater.startHostingApkForCar(this@MainActivity, target) { st ->
                            srvLogTv.text = st
                            srvLogTv.setTextColor(OK)
                        }
                    }
                },
                pill(tr("Detener :8788", "Stop :8788", "Stopp :8788", "Parar :8788")) {
                    GitHubReleaseUpdater.stopHostingApk()
                    render()
                },
            ))

            addView(text(
                tr(
                    "🌐 DNS Personalizado Manual (NextDNS / DNS Privado):",
                    "🌐 Manual Custom DNS (NextDNS / Private DNS):",
                    "🌐 Manuelles eigenes DNS (NextDNS / Privates DNS):",
                    "🌐 DNS Personalizado Manual (NextDNS / DNS Privado):",
                ),
                14f, ACCENT, bold = true,
            ).apply {
                setPadding(0, dp(14), 0, dp(4))
            })
            val dnsInput = EditText(this@MainActivity).apply {
                hint = tr("Ej: xxxx.dns.nextdns.io o IP DNS", "e.g. xxxx.dns.nextdns.io or DNS IP", "z. B. xxxx.dns.nextdns.io oder DNS-IP", "Ex.: xxxx.dns.nextdns.io ou IP DNS")
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
                pill(tr("📋 Copiar y Abrir DNS", "📋 Copy & Open DNS", "📋 Kopieren & DNS öffnen", "📋 Copiar e Abrir DNS")) {
                    val v = dnsInput.text?.toString()?.trim().orEmpty()
                    prefs.customDnsHost = v
                    if (v.isNotEmpty()) {
                        val cm = getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("DNS", v))
                        toast("DNS: $v")
                    }
                    openPrivateDnsSettings()
                },
                pill(tr("📶 Ajustes Wi-Fi", "📶 Wi-Fi Settings", "📶 WLAN-Einstellungen", "📶 Definições Wi-Fi")) {
                    val v = dnsInput.text?.toString()?.trim().orEmpty()
                    prefs.customDnsHost = v
                    runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                },
            ).apply { setPadding(0, dp(8), 0, 0) })
        })

        // --- Experto 3: Repositorios GitHub Editables + Token PAT + Selector de Releases ---
        root.addView(card().apply {
            addView(text(
                tr(
                    "🐙 GitHub Releases Avanzado y Token PAT",
                    "🐙 Advanced GitHub Releases & PAT Token",
                    "🐙 Erweiterte GitHub Releases & PAT-Token",
                    "🐙 GitHub Releases Avançado e Token PAT",
                ),
                17.5f, TEXT, bold = true,
            ))
            addView(text(
                tr(
                    "Configura repositorios personalizados o tu Token PAT si tus repositorios son privados:",
                    "Configure custom repositories or a GitHub PAT token for private forks:",
                    "Benutzerdefinierte Repositories oder ein PAT-Token für private Forks konfigurieren:",
                    "Configure repositórios personalizados ou o seu Token PAT para repositórios privados:",
                ),
                13.5f, DIM,
            ).apply {
                setPadding(0, dp(4), 0, dp(8))
            })

            addView(text(
                tr(
                    "Repositorio Qonecta Unit (Coche):",
                    "Qonecta Unit Repository (Car):",
                    "Qonecta Unit Repository (Fahrzeug):",
                    "Repositório Qonecta Unit (Carro):",
                ),
                12f, FAINT, bold = true,
            ))
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

            addView(text(
                tr(
                    "Repositorio Qonecta Companion (Móvil):",
                    "Qonecta Companion Repository (Phone):",
                    "Qonecta Companion Repository (Smartphone):",
                    "Repositório Qonecta Companion (Telemóvel):",
                ),
                12f, FAINT, bold = true,
            ).apply { setPadding(0, dp(8), 0, 0) })
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

            addView(text(
                tr(
                    "Token PAT de GitHub (opcional para repos privados):",
                    "GitHub PAT Token (optional for private repos):",
                    "GitHub-PAT-Token (optional für private Repos):",
                    "Token PAT do GitHub (opcional para repos privados):",
                ),
                12f, FAINT, bold = true,
            ).apply { setPadding(0, dp(8), 0, 0) })
            val tokenInput = EditText(this@MainActivity).apply {
                hint = "ghp_… / github_pat_…"
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

            val ghStatusTv = text(
                tr(
                    "Pulsa para listar todas las versiones disponibles.",
                    "Tap below to list all available releases.",
                    "Unten tippen, um alle verfügbaren Releases aufzulisten.",
                    "Toque abaixo para listar todas as versões disponíveis.",
                ),
                13.5f, FAINT,
            ).apply {
                setPadding(0, dp(8), 0, dp(8))
            }
            addView(ghStatusTv)

            val assetsContainer = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(assetsContainer)

            addView(pill(
                tr(
                    "💾 Guardar ajustes y listar Releases",
                    "💾 Save settings & list Releases",
                    "💾 Speichern & Releases auflisten",
                    "💾 Guardar definições e listar Releases",
                ),
                primary = true,
            ) {
                val unitRepo = GitHubReleaseUpdater.normalizeRepo(repoInput.text?.toString().orEmpty(), GitHubReleaseUpdater.DEFAULT_UNIT_REPO)
                val compRepo = GitHubReleaseUpdater.normalizeRepo(companionRepoInput.text?.toString().orEmpty(), GitHubReleaseUpdater.DEFAULT_COMPANION_REPO)
                val pat = tokenInput.text?.toString()?.trim().orEmpty()
                prefs.githubRepo = unitRepo
                prefs.githubCompanionRepo = compRepo
                prefs.githubToken = pat
                ghStatusTv.text = "⏳ $unitRepo · $compRepo…"
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
                            pill(tr("📲 En móvil", "📲 On phone", "📲 Smartphone", "📲 No telemóvel"), primary = asset.isCompanionApk) {
                                GitHubReleaseUpdater.downloadAsset(
                                    this@MainActivity, asset, pat,
                                    onProgress = { msg -> ghStatusTv.text = msg },
                                    onDone = { dl ->
                                        dl.onSuccess { f -> GitHubReleaseUpdater.installApkOnPhone(this@MainActivity, f) { st -> ghStatusTv.text = st } }
                                            .onFailure { e -> ghStatusTv.text = "⚠️ ${e.message}" }
                                    },
                                )
                            },
                            pill(tr("⚡ Push al coche", "⚡ Push to car", "⚡ Push ans Auto", "⚡ Push ao carro"), primary = asset.isCarApk) {
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
            addView(text(
                tr(
                    "🌐 Puente de Datos BT, Permisos y Registros",
                    "🌐 BT Data Bridge, Permissions & Logs",
                    "🌐 BT-Datenbrücke, Berechtigungen & Logs",
                    "🌐 Ponte de Dados BT, Permissões e Registos",
                ),
                17.5f, TEXT, bold = true,
            ))
            val lastHostSuffix = if (s.lastDataHost.isNotBlank()) {
                tr(" (último host: ${s.lastDataHost})", " (last host: ${s.lastDataHost})", " (letzter Host: ${s.lastDataHost})", " (último host: ${s.lastDataHost})")
            } else ""
            addView(text(
                tr(
                    "Peticiones HTTP servidas al Planificador del coche por ${s.transport.ifBlank { "Bluetooth" }}: ${s.dataRequestsServed}$lastHostSuffix",
                    "HTTP requests served to the car's Route Planner over ${s.transport.ifBlank { "Bluetooth" }}: ${s.dataRequestsServed}$lastHostSuffix",
                    "Bediente HTTP-Anfragen für den Routenplaner über ${s.transport.ifBlank { "Bluetooth" }}: ${s.dataRequestsServed}$lastHostSuffix",
                    "Pedidos HTTP servidos ao Planeador do carro por ${s.transport.ifBlank { "Bluetooth" }}: ${s.dataRequestsServed}$lastHostSuffix",
                ),
                13.5f, if (s.dataRequestsServed > 0) OK else DIM,
            ).apply { setPadding(0, dp(4), 0, dp(10)) })

            addView(row(
                pill("🔵 Tethering BT / Hotspot") { openTetherSettings() },
                pill(tr("🗺️ Probar Maps", "🗺️ Test Maps", "🗺️ Maps testen", "🗺️ Testar Maps")) { testMaps() },
            ))

            val logsStatusTv = text(
                tr(
                    "Extracción remota de ohu.log + logcat sin pendrive:",
                    "Remote extraction of ohu.log + logcat without USB drive:",
                    "Fernabruf von ohu.log + logcat ohne USB-Stick:",
                    "Extração remota de ohu.log + logcat sem pendrive:",
                ),
                13.5f, DIM,
            ).apply {
                setPadding(0, dp(12), 0, dp(6))
            }
            addView(logsStatusTv)
            addView(pill(
                tr(
                    "📥 Recoger registros (Logs) del coche ahora",
                    "📥 Fetch car logs now",
                    "📥 Fahrzeug-Protokolle (Logs) jetzt abrufen",
                    "📥 Recolher registos (Logs) do carro agora",
                ),
                primary = true,
            ) {
                logsStatusTv.text = tr(
                    "⏳ Solicitando registros a Qonecta Unit…",
                    "⏳ Requesting logs from Qonecta Unit…",
                    "⏳ Fordere Protokolle von Qonecta Unit an…",
                    "⏳ A solicitar registos ao Qonecta Unit…",
                )
                logsStatusTv.setTextColor(ACCENT)
                BridgeService.requestUnitLogs(this@MainActivity, maxLines = 800) { res ->
                    res.onSuccess { (file, msg) ->
                        val kb = (file.length() / 1024L).coerceAtLeast(1L)
                        logsStatusTv.text = "✓ ${msg.fileName} ($kb KB)"
                        logsStatusTv.setTextColor(OK)
                        showUnitLogsDialog(file, msg.logText)
                    }.onFailure { err ->
                        logsStatusTv.text = "⚠️ ${err.message}"
                        logsStatusTv.setTextColor(BAD)
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

            addView(text(
                tr(
                    "Estado detallado de permisos:",
                    "Detailed permission status:",
                    "Detaillierter Berechtigungsstatus:",
                    "Estado detalhado das permissões:",
                ),
                13.5f, FAINT, bold = true,
            ).apply { setPadding(0, dp(12), 0, dp(4)) })
            if (Build.VERSION.SDK_INT >= 33) {
                addView(permRow(
                    tr("Notificaciones", "Notifications", "Benachrichtigungen", "Notificações"),
                    has(Manifest.permission.POST_NOTIFICATIONS),
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERMS)
                })
            }
            if (Build.VERSION.SDK_INT >= 31) {
                addView(permRow(
                    tr(
                        "Dispositivos cercanos (Bluetooth)",
                        "Nearby devices (Bluetooth)",
                        "Geräte in der Nähe (Bluetooth)",
                        "Dispositivos próximos (Bluetooth)",
                    ),
                    has(Manifest.permission.BLUETOOTH_CONNECT),
                ) {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_PERMS)
                })
            }
            addView(permRow(
                tr(
                    "Mostrar sobre otras apps (abrir Maps solo)",
                    "Display over other apps (auto-launch Maps)",
                    "Über anderen Apps einblenden (Maps auto-starten)",
                    "Sobrepor a outras apps (abrir Maps sozinho)",
                ),
                Settings.canDrawOverlays(this@MainActivity),
            ) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            })
            val pm = getSystemService(PowerManager::class.java)
            addView(permRow(
                tr(
                    "Sin restricción de batería",
                    "Unrestricted battery usage",
                    "Uneingeschränkte Akkunutzung",
                    "Sem restrição de bateria",
                ),
                pm?.isIgnoringBatteryOptimizations(packageName) == true,
            ) {
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
    // Selector de Idioma Instantáneo (ES / EN / DE / PT + Automático)
    // ------------------------------------------------------------------------------------------

    private fun showLanguagePickerDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, dp(20), OUTLINE)
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        box.addView(text("🌐 Idioma · Language · Sprache", 18.5f, ACCENT, bold = true))
        box.addView(text(
            tr(
                "Elige el idioma de Qonecta Companion, del Modo Tutorial y del portal web de instalación del coche:",
                "Choose the language for Qonecta Companion, Tutorial Mode, and the car's web installation portal:",
                "Wählen Sie die Sprache für Qonecta Companion, den Tutorial-Modus und das Installationsportal im Fahrzeug:",
                "Escolha o idioma do Qonecta Companion, do Modo Tutorial e do portal web de instalação do carro:",
            ),
            13.5f, DIM,
        ).apply { setPadding(0, dp(6), 0, dp(12)) })

        val sys = CompanionI18n.systemLang()
        val isAuto = CompanionI18n.isAutoMode()
        val cur = CompanionI18n.currentLang()

        data class LangOption(val code: String, val label: String, val active: Boolean)
        val options = listOf(
            LangOption(
                "",
                "🌐 ${tr("Automático (sistema)", "Automatic (system)", "Automatisch (System)", "Automático (sistema)")} · ${sys.flag} ${sys.nativeName}",
                isAuto,
            ),
            LangOption("es", "🇪🇸 Español", !isAuto && cur == CompanionI18n.Lang.ES),
            LangOption("en", "🇬🇧 English", !isAuto && cur == CompanionI18n.Lang.EN),
            LangOption("de", "🇩🇪 Deutsch", !isAuto && cur == CompanionI18n.Lang.DE),
            LangOption("pt", "🇵🇹 Português", !isAuto && cur == CompanionI18n.Lang.PT),
        )

        var dialogRef: AlertDialog? = null
        options.forEachIndexed { idx, opt ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (opt.active) rounded(SURFACE_TOP, dp(14), ACCENT) else rounded(BG, dp(14), OUTLINE)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (idx > 0) topMargin = dp(8)
                }
                isClickable = true
                setOnClickListener {
                    prefs.appLang = opt.code
                    com.headqlink.unit.companion.cloud.CompanionCloud.refreshMeta()
                    MyCarTab.push3d()
                    LeapCompanionHub.push3dState()
                    if (BridgeState.current.phase == BridgeState.Phase.STOPPED && !prefs.useBluetooth && !prefs.useWifi) {
                        BridgeState.update {
                            it.copy(
                                detail = tr(
                                    "Opciones desmarcadas. Marca Bluetooth o Wi-Fi cuando quieras conectar.",
                                    "Options unchecked. Check Bluetooth or Wi-Fi whenever you want to connect.",
                                    "Optionen deaktiviert. Aktivieren Sie Bluetooth oder WLAN zum Verbinden.",
                                    "Opções desmarcadas. Marque Bluetooth ou Wi-Fi quando quiser ligar.",
                                )
                            )
                        }
                    }
                    dialogRef?.dismiss()
                    render()
                    activeTutorialDialog?.refresh()
                    toast(
                        tr(
                            "🇪🇸 Idioma configurado en Español",
                            "🇬🇧 Language set to English",
                            "🇩🇪 Sprache auf Deutsch eingestellt",
                            "🇵🇹 Idioma definido para Português",
                        )
                    )
                }
            }
            item.addView(
                text(opt.label, 15f, if (opt.active) ACCENT else TEXT, bold = true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (opt.active) {
                item.addView(text("✓", 17f, ACCENT, bold = true))
            }
            box.addView(item)
        }

        val dlg = AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton(tr("Cerrar", "Close", "Schließen", "Fechar"), null)
            .create()
        dialogRef = dlg
        dlg.window?.setBackgroundDrawable(rounded(SURFACE, dp(20), OUTLINE))
        dlg.show()
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
                    .onFailure {
                        toast(tr(
                            "No se pudo abrir el selector: ${it.message}",
                            "Could not open chooser: ${it.message}",
                            "Auswahl konnte nicht geöffnet werden: ${it.message}",
                            "Não foi possível abrir o seletor: ${it.message}",
                        ))
                    }
            }

            override fun onFailure(error: CharSequence?) {
                val extra = error?.let { ": $it" } ?: ""
                toast(tr(
                    "No se encontró el coche$extra. Prueba con «Elegir coche».",
                    "Car not found$extra. Try 'Pick car' instead.",
                    "Fahrzeug nicht gefunden$extra. Bitte über die gekoppelte Liste wählen.",
                    "Carro não encontrado$extra. Experimente «Escolher carro».",
                ))
            }
        }
        runCatching { cdm.associate(request, callback, null) }
            .onFailure {
                toast(tr(
                    "Abriendo lista de dispositivos Bluetooth emparejados…",
                    "Opening paired Bluetooth devices list…",
                    "Öffne Liste gekoppelter Bluetooth-Geräte…",
                    "A abrir lista de dispositivos Bluetooth emparelhados…",
                ))
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
                Toast.makeText(
                    this,
                    tr(
                        "Copiando ${src.name} al USB / carpeta…",
                        "Copying ${src.name} to USB / folder…",
                        "Kopiere ${src.name} auf USB / Ordner…",
                        "A copiar ${src.name} para a pendrive USB / pasta…",
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
                kotlin.concurrent.thread(name = "usb-apk-export", isDaemon = true) {
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            java.io.FileInputStream(src).use { input ->
                                input.copyTo(out, bufferSize = 64 * 1024)
                            }
                            out.flush()
                        } ?: error(tr(
                            "No se pudo abrir el destino",
                            "Could not open destination",
                            "Ziel konnte nicht geöffnet werden",
                            "Não foi possível abrir o destino",
                        ))
                    }.onSuccess {
                        prefs.usbExportDoneAtMs = System.currentTimeMillis()
                        runOnUiThread {
                            toast(tr(
                                "✅ ${src.name} guardado correctamente en el USB / carpeta",
                                "✅ ${src.name} saved successfully to USB / folder",
                                "✅ ${src.name} erfolgreich auf USB / Ordner gespeichert",
                                "✅ ${src.name} guardado com sucesso na pendrive USB / pasta",
                            ))
                            render()
                            activeTutorialDialog?.refresh()
                        }
                    }.onFailure { e ->
                        runOnUiThread {
                            toast(tr(
                                "⚠️ Error al guardar APK: ${e.message}",
                                "⚠️ Error saving APK: ${e.message}",
                                "⚠️ Fehler beim Speichern der APK: ${e.message}",
                                "⚠️ Erro ao guardar APK: ${e.message}",
                            ))
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
            toast(tr(
                "No llegó la dirección Bluetooth del coche",
                "Did not receive car Bluetooth address",
                "Keine Bluetooth-Adresse vom Fahrzeug erhalten",
                "Não foi recebido o endereço Bluetooth do carro",
            ))
            return
        }
        saveCar(address, name)
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { getSystemService(CompanionDeviceManager::class.java)?.startObservingDevicePresence(address) }
        }
        toast(tr(
            "Coche asociado: ${name ?: address}",
            "Car associated: ${name ?: address}",
            "Fahrzeug verknüpft: ${name ?: address}",
            "Carro associado: ${name ?: address}",
        ))
    }

    private fun pickBondedDevice() {
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_PERMS)
            toast(tr(
                "Da permiso de Dispositivos cercanos y vuelve a pulsar",
                "Grant Nearby devices permission and tap again",
                "Bitte Berechtigung „Geräte in der Nähe“ erteilen und erneut tippen",
                "Conceda permissão de Dispositivos próximos e toque novamente",
            ))
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val bonded = runCatching { adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
        if (bonded.isEmpty()) {
            toast(tr(
                "No hay dispositivos Bluetooth emparejados en el móvil",
                "No paired Bluetooth devices found on this phone",
                "Keine gekoppelten Bluetooth-Geräte auf diesem Smartphone gefunden",
                "Não há dispositivos Bluetooth emparelhados neste telemóvel",
            ))
            return
        }
        val unnamed = tr("Sin nombre", "Unnamed", "Unbenannt", "Sem nome")
        val labels = bonded.map { d -> "${runCatching { d.name }.getOrNull() ?: unnamed}\n${d.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(tr("¿Cuál es tu coche?", "Which one is your car?", "Welches Gerät ist Ihr Fahrzeug?", "Qual é o seu carro?"))
            .setItems(labels) { _, i ->
                val d = bonded[i]
                saveCar(d.address, runCatching { d.name }.getOrNull())
            }
            .setNegativeButton(tr("Cancelar", "Cancel", "Abbrechen", "Cancelar"), null)
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
                    detail = tr(
                        "Opciones desmarcadas. Marca Bluetooth o Wi-Fi cuando quieras conectar.",
                        "Options unchecked. Check Bluetooth or Wi-Fi whenever you want to connect.",
                        "Optionen deaktiviert. Aktivieren Sie Bluetooth oder WLAN zum Verbinden.",
                        "Opções desmarcadas. Marque Bluetooth ou Wi-Fi quando quiser ligar.",
                    ),
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
            onOpenLanguagePicker = { showLanguagePickerDialog() },
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
        box.addView(text(
            tr(
                "☕ Apoyar Qonecta · por CharlysEV",
                "☕ Support Qonecta · by CharlysEV",
                "☕ Qonecta unterstützen · von CharlysEV",
                "☕ Apoiar o Qonecta · por CharlysEV",
            ),
            19f, WARN, bold = true,
        ))
        box.addView(text(
            tr(
                "Qonecta Unit y Qonecta Companion se desarrollan de forma abierta e independiente para que disfrutes al máximo tu Leapmotor C10 sin suscripciones ni bloqueos.\n\n" +
                    "Si te gusta el proyecto y quieres invitar a un café o echar una mano:",
                "Qonecta Unit and Qonecta Companion are built openly and independently so you can get the most out of your Leapmotor C10 with zero subscriptions or lock-ins.\n\n" +
                    "If you enjoy the project and want to buy a coffee or support development:",
                "Qonecta Unit und Qonecta Companion werden offen und unabhängig entwickelt, damit Sie Ihren Leapmotor C10 ohne Abos oder Sperren voll ausschöpfen können.\n\n" +
                    "Wenn Ihnen das Projekt gefällt und Sie einen Kaffee spendieren möchten:",
                "O Qonecta Unit e o Qonecta Companion são desenvolvidos de forma aberta e independente para aproveitar ao máximo o seu Leapmotor C10 sem subscrições nem bloqueios.\n\n" +
                    "Se gosta do projeto e quer oferecer um café ou apoiar o desenvolvimento:",
            ),
            14f, TEXT,
        ).apply { setPadding(0, dp(8), 0, dp(14)) })

        box.addView(pill(
            tr(
                "☕ Invitar a un café en Ko-fi",
                "☕ Buy me a coffee on Ko-fi",
                "☕ Auf Ko-fi einen Kaffee spendieren",
                "☕ Oferecer um café no Ko-fi",
            ),
            primary = true,
        ) {
            openExternalUrl("https://ko-fi.com/charlysev")
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        box.addView(pill(
            tr(
                "💙 Apoyar por PayPal",
                "💙 Support via PayPal",
                "💙 Über PayPal unterstützen",
                "💙 Apoiar via PayPal",
            )
        ) {
            openExternalUrl("https://www.paypal.me/CharlysEV")
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })

        box.addView(pill("🐙 GitHub · Qonecta Unit & Companion") {
            openExternalUrl("https://github.com/CharlysEV/qonecta-unit")
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })

        val dlg = AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton(tr("Cerrar", "Close", "Schließen", "Fechar"), null)
            .create()
        dlg.window?.setBackgroundDrawable(rounded(SURFACE, dp(20), OUTLINE))
        dlg.show()
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            toast("URL: $url")
        }
    }

    private fun testMaps() {
        val dest = NavPoint(42.880447, -8.545916, "Catedral de Santiago")
        val via = listOf(NavPoint(41.656060, -4.723680, tr("Parada de prueba (Valladolid)", "Test stop (Valladolid)", "Teststopp (Valladolid)", "Paragem de teste (Valladolid)")))
        val built = MapsRoute.build(dest, via)
        val intent = MapsLauncher.buildIntent(this, built)
        runCatching { startActivity(intent) }.onFailure {
            toast(tr(
                "No hay Google Maps instalado",
                "Google Maps is not installed",
                "Google Maps ist nicht installiert",
                "O Google Maps não está instalado",
            ))
        }
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
            toast(tr(
                "No se pudo abrir el selector de archivos: ${e.message}",
                "Could not open file picker: ${e.message}",
                "Dateiauswahl konnte nicht geöffnet werden: ${e.message}",
                "Não foi possível abrir o seletor de ficheiros: ${e.message}",
            ))
        }
    }

    private fun confirmForget() {
        AlertDialog.Builder(this)
            .setTitle(tr("¿Olvidar el coche?", "Forget paired car?", "Gekoppeltes Fahrzeug entfernen?", "Esquecer o carro?"))
            .setMessage(tr(
                "Tendrás que volver a emparejar con un código nuevo.",
                "You will need to pair again using a new 6-digit code.",
                "Sie müssen das Fahrzeug anschließend mit einem neuen 6-stelligen Code koppeln.",
                "Terá de emparelhar novamente com um novo código de 6 dígitos.",
            ))
            .setPositiveButton(tr("Olvidar", "Forget", "Entfernen", "Esquecer")) { _, _ ->
                prefs.forgetCar()
                BridgeService.stop(this)
                BridgeState.update { BridgeState.Snapshot() }
                render()
            }
            .setNegativeButton(tr("Cancelar", "Cancel", "Abbrechen", "Cancelar"), null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    // ------------------------------------------------------------------------------------------
    // Piezas de interfaz
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
        if (!granted) addView(pill(tr("Dar", "Grant", "Erteilen", "Conceder")) { onFix() })
    }

    private fun showUnitLogsDialog(file: java.io.File, logText: String) {
        val preview = if (logText.length > 6000) {
            tr(
                "… (mostrando últimos 6.000 caracteres de ${logText.length})\n\n",
                "… (showing last 6,000 characters of ${logText.length})\n\n",
                "… (zeige die letzten 6.000 Zeichen von ${logText.length})\n\n",
                "… (a mostrar os últimos 6.000 caracteres de ${logText.length})\n\n",
            ) + logText.takeLast(6000)
        } else {
            logText
        }
        AlertDialog.Builder(this)
            .setTitle("📋 ${file.name}")
            .setMessage(preview)
            .setPositiveButton(tr("📤 Compartir registros", "📤 Share logs", "📤 Protokolle teilen", "📤 Partilhar registos")) { _, _ ->
                shareUnitLogText(file, logText)
            }
            .setNeutralButton(tr("📋 Copiar", "📋 Copy", "📋 Kopieren", "📋 Copiar")) { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText(file.name, logText))
                toast(tr(
                    "Registros copiados al portapapeles (${file.name})",
                    "Logs copied to clipboard (${file.name})",
                    "Protokolle in die Zwischenablage kopiert (${file.name})",
                    "Registos copiados para a área de transferência (${file.name})",
                ))
            }
            .setNegativeButton(tr("Cerrar", "Close", "Schließen", "Fechar"), null)
            .show()
    }

    private fun shareUnitLogText(file: java.io.File, logText: String) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Qonecta Unit Logs (${file.name})")
                putExtra(Intent.EXTRA_TEXT, logText)
            }
            startActivity(Intent.createChooser(send, "Qonecta Unit Logs"))
        }.onFailure { e ->
            toast(tr(
                "No se pudo compartir: ${e.message}",
                "Could not share: ${e.message}",
                "Teilen fehlgeschlagen: ${e.message}",
                "Não foi possível partilhar: ${e.message}",
            ))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
