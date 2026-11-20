// SPDX-License-Identifier: AGPL-3.0-only
// UI visual language unified with Qonecta Unit Core (Android Auto + Apple CarPlay).
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.cluster.ClusterMapController
import com.andrerinas.openheadunit.connection.UsbDeviceCompat
import com.headqlink.unit.extended.ConnectionRingView
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla unificada de Apple CarPlay para Qonecta Unit Core.
 * Comparte el mismo lenguaje visual oscuro + turquesa (#00E5C7), anillo de estado interactivo
 * y tarjetas de dispositivos vinculados que la pantalla principal de Android Auto.
 */
class LeapCarPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var setupError: String? = null

    private var statusPill: TextView? = null
    private var transportBadge: TextView? = null
    private var heroTitle: TextView? = null
    private var heroSubtitle: TextView? = null
    private var connectButton: Button? = null
    private var usbButton: Button? = null
    private var disconnectButton: Button? = null
    private var clusterToggleButton: Button? = null
    private var connectionRing: ConnectionRingView? = null

    private var connectingStartElapsedMs = 0L
    private var pendingWireless = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var exportButton: Button? = null
    private var usbReceiverRegistered = false

    private val usbStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (page == "home") refreshStatus()
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }

    // Solo actualiza cada segundo mientras una sesión CarPlay está conectando (nunca en reposo).
    private val connectingTick = object : Runnable {
        override fun run() {
            refreshStatus()
            if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.active) {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone()
        else permissionHelp(
            "Dispositivos cercanos",
            "Concede el permiso de Dispositivos cercanos para conectar Qonecta Unit con tu iPhone vinculado."
        )
    }

    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    override fun attachBaseContext(newBase: Context) {
        val scale = com.andrerinas.openheadunit.utils.Settings(newBase).uiScaleHomePercent / 100.0f
        if (scale != 1.0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val cfg = Configuration(newBase.resources.configuration)
            val metrics = newBase.resources.displayMetrics
            cfg.densityDpi = (metrics.densityDpi * scale).toInt()
            val ctx = newBase.createConfigurationContext(cfg)
            super.attachBaseContext(ctx)
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setupError = runCatching { LeapCarPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
            "No se pudo completar la configuración local de CarPlay. Reinstala el APK de Qonecta Unit Core."
        }
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        render()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") {
                    page = "home"
                    render()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (!usbReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            runCatching {
                ContextCompat.registerReceiver(this, usbStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                usbReceiverRegistered = true
            }
        }
    }

    override fun onStop() {
        if (usbReceiverRegistered) {
            runCatching { unregisterReceiver(usbStateReceiver) }
            usbReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        page = intent.getStringExtra("page") ?: "home"
        render()
        handleWirelessRecovery()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(connectingTick)
        if (!initialLaunch && page == "home") render() else refreshStatus()
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.active) {
            if (connectingStartElapsedMs == 0L) connectingStartElapsedMs = SystemClock.elapsedRealtime()
            handler.postDelayed(connectingTick, 1000L)
        }
        if (initialLaunch) {
            initialLaunch = false
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                LeapCarPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null
            ) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
    }

    override fun onPause() {
        handler.removeCallbacks(connectingTick)
        super.onPause()
    }

    private fun render() {
        statusPill = null
        transportBadge = null
        heroTitle = null
        heroSubtitle = null
        connectButton = null
        usbButton = null
        disconnectButton = null
        clusterToggleButton = null
        connectionRing = null

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(30), dp(18), dp(30), dp(18))
        }
        scroll.addView(content)

        // Cabecera unificada con HomeFragment
        content.addView(buildHeader())

        when (page) {
            "settings" -> settings(content)
            "about" -> about(content)
            else -> home(content)
        }
        setContentView(scroll)
        refreshStatus()
    }

    private fun buildHeader(): View {
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }

        header.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_carplay)
                contentDescription = "Apple CarPlay"
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams(dp(52), dp(52))
        )

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            }
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            label("Qonecta Unit Core", 32, TEXT, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        titleRow.addView(
            pillLabel("Apple CarPlay · v${version()}", ACCENT, 0x40000000).apply {
                textSize = 14.5f
                letterSpacing = 0.03f
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
            }
        )
        titleCol.addView(titleRow)

        val subText = when (page) {
            "settings" -> "Ajustes de Apple CarPlay · Pantalla, vídeo y enlace inalámbrico"
            "about" -> "Información y licencias de Qonecta Unit Core · CarPlay"
            else -> "Proyección CarPlay inalámbrica y por Cable USB"
        }
        titleCol.addView(
            label(subText, 15, MUTED, bold = false).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
        )
        header.addView(titleCol)

        if (page == "home") {
            val coreSettings = com.andrerinas.openheadunit.utils.Settings(this)
            clusterToggleButton = headerPillButton(
                title = getString(if (coreSettings.clusterMapEnabled) R.string.qu_cluster_short_on else R.string.qu_cluster_short_off),
                highlighted = coreSettings.clusterMapEnabled,
            ) {
                val next = !coreSettings.clusterMapEnabled
                coreSettings.clusterMapEnabled = next
                if (!next) {
                    runCatching { ClusterMapController.stop(context = this) }
                }
                updateClusterButtonStyle(coreSettings.clusterMapEnabled)
                Toast.makeText(
                    this,
                    getString(if (next) R.string.qu_cluster_toast_on else R.string.qu_cluster_toast_off),
                    Toast.LENGTH_SHORT
                ).show()
            }
            header.addView(
                clusterToggleButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply {
                    marginEnd = dp(10)
                }
            )

            header.addView(
                headerPillButton("Ajustes", highlighted = false) {
                    page = "settings"
                    render()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply {
                    marginEnd = dp(10)
                }
            )

            header.addView(
                headerPillButton("Android Auto", highlighted = true) {
                    if (CarPlayBackgroundSession.hasSession()) {
                        CarPlayBackgroundSession.stop { runOnUiThread { finish() } }
                    } else {
                        finish()
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52))
            )
        } else {
            header.addView(
                headerPillButton("←  Volver a CarPlay", highlighted = true) {
                    page = "home"
                    render()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52))
            )
        }

        return header
    }

    private fun updateClusterButtonStyle(enabled: Boolean) {
        val btn = clusterToggleButton ?: return
        btn.text = getString(if (enabled) R.string.qu_cluster_short_on else R.string.qu_cluster_short_off)
        btn.setTextColor(if (enabled) ACCENT else TEXT)
        btn.background = rippleRounded(
            fill = if (enabled) ACCENT_DARK_BG else SURFACE_TOP,
            stroke = if (enabled) ACCENT else BORDER,
            radiusDp = 18,
            strokeDp = 2
        )
    }

    private fun home(content: LinearLayout) {
        val wide = resources.configuration.screenWidthDp >= 720
        val carHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL

        // Avisos contextuales discretos (solo si aplican)
        if (setupError != null) {
            content.addView(warningBanner(setupError!!, null, null))
        } else if (carHotspotOff()) {
            content.addView(
                warningBanner(
                    "⚠ El Hotspot del coche «${AirPlayPersistence.loadManualHotspotSsid(this)}» está apagado. Enciéndelo antes de conectar.",
                    "Abrir Hotspot"
                ) { openCarWifiSettings() }
            )
        } else if (carHotspotMode) {
            val wifiMgr = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            if (wifiMgr?.isWifiEnabled == true) {
                content.addView(
                    warningBanner(
                        "⚠ Consejo C10 (Modo Hotspot): apaga el Wi-Fi cliente en la barra superior del coche y deja solo la Zona Wi-Fi para evitar cortes.",
                        null,
                        null
                    )
                )
            }
        }

        // Tarjeta Hero principal (llena la pantalla en formato panorámico 16:9 igual que Android Auto)
        val heroCard = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(20), dp(24), dp(20))
            background = rounded(CARD_BG, CARD_BORDER, radiusDp = 26, strokeDp = 2)
        }

        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Fila de píldoras de estado y transporte
        val pillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusPill = pillLabel("●  LISTO", ACCENT, 0x40000000).apply {
            textSize = 16.5f
            letterSpacing = 0.05f
        }
        transportBadge = pillLabel("📡 Wi-Fi Direct 5 GHz", TEXT, 0x40000000).apply {
            textSize = 16.5f
        }
        pillsRow.addView(statusPill)
        pillsRow.addView(
            transportBadge,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            }
        )
        leftCol.addView(pillsRow)

        heroTitle = label("Conecta tu iPhone", if (wide) 42 else 34, TEXT, bold = true).apply {
            setPadding(0, dp(10), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        leftCol.addView(heroTitle)

        heroSubtitle = label(
            "Mantén Bluetooth y Wi-Fi activos en tu iPhone, o conéctalo por Cable USB.",
            18,
            MUTED
        ).apply {
            setPadding(0, dp(4), 0, 0)
        }
        leftCol.addView(heroSubtitle)

        // Fila de botones principales (Turquesa alto contraste + USB + Desconectar)
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }

        connectButton = primaryPillButton("Conectar CarPlay") {
            if (CarPlayBackgroundSession.hasSession()) openProjection()
            else connect(true)
        }
        actionsRow.addView(
            connectButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(66)).apply {
                marginEnd = dp(12)
            }
        )

        usbButton = secondaryPillButton("🔌 Cable USB") {
            connect(false)
        }
        actionsRow.addView(
            usbButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(66)).apply {
                marginEnd = dp(12)
            }
        )

        disconnectButton = dangerPillButton("✕ Desconectar") {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop {
                runOnUiThread {
                    connectingStartElapsedMs = 0L
                    refreshStatus()
                }
            }
        }.apply { visibility = View.GONE }
        actionsRow.addView(
            disconnectButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(66))
        )
        leftCol.addView(actionsRow)

        // Selector limpio de enlace inalámbrico (espejo de los modos de HomeFragment)
        leftCol.addView(
            label("MODO INALÁMBRICO PARA CARPLAY:", 14, ACCENT, bold = true).apply {
                letterSpacing = 0.04f
                setPadding(0, dp(18), 0, dp(8))
            }
        )

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val wifiDirectCard = modeToggleButton(
            title = if (!carHotspotMode) "☑ 📡 Wi-Fi Direct 5 GHz\nRed directa automática" else "☐ 📡 Wi-Fi Direct 5 GHz\nRed directa automática",
            selected = !carHotspotMode
        ) {
            if (carHotspotMode) applyWirelessLink(WirelessHotspotMode.WIFI_P2P)
        }
        val hotspotCard = modeToggleButton(
            title = if (carHotspotMode) "☑ 📲 Hotspot del coche\n${storedSsid().ifBlank { "Configurar red" }}" else "☐ 📲 Hotspot del coche\nAlternativa sin Wi-Fi Direct",
            selected = carHotspotMode
        ) {
            if (!carHotspotMode) {
                if (hotspotError(storedSsid(), storedPassword()) == null) {
                    applyWirelessLink(WirelessHotspotMode.MANUAL)
                } else {
                    askHotspotCredentials { ssid, password ->
                        saveHotspotCredentials(ssid, password)
                        applyWirelessLink(WirelessHotspotMode.MANUAL)
                    }
                }
            }
        }
        modeRow.addView(
            wifiDirectCard,
            LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd = dp(10) }
        )
        modeRow.addView(
            hotspotCard,
            LinearLayout.LayoutParams(0, dp(72), 1f)
        )
        leftCol.addView(modeRow)

        val activeModeSummary = if (carHotspotMode) {
            "Activo: 📲 Hotspot «${storedSsid()}»  ·  🔌 Cable USB siempre disponible"
        } else {
            "Activo: 📡 Wi-Fi Direct 5 GHz  ·  🔌 Cable USB siempre disponible"
        }
        leftCol.addView(
            label(activeModeSummary, 15, TEXT, bold = true).apply {
                setPadding(0, dp(8), 0, 0)
            }
        )

        // Anillo interactivo de estado (idéntico al de Android Auto para simetría total)
        val ringHolder = FrameLayout(this)
        connectionRing = ConnectionRingView(this).apply {
            protocolTitle = "Apple CarPlay"
            subtitleText = "Toca para conectar"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                when {
                    CarPlayBackgroundSession.hasSession() -> openProjection()
                    findConnectedAppleUsb() != null -> connect(false)
                    else -> connect(true)
                }
            }
        }
        ringHolder.addView(
            connectionRing,
            FrameLayout.LayoutParams(dp(270), dp(270), Gravity.CENTER)
        )

        if (wide) {
            heroCard.addView(leftCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.45f))
            heroCard.addView(ringHolder, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.62f))
        } else {
            heroCard.addView(leftCol, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            heroCard.addView(ringHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
        }

        content.addView(
            heroCard,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(14)
            }
        )

        // Fila inferior: iPhones / dispositivos Bluetooth vinculados (llena el 100% de la pantalla sin huecos vacíos)
        content.addView(buildBondedIphonesSection())
    }

    private fun buildBondedIphonesSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
        }

        section.addView(
            label("IPHONES VINCULADOS (TOCA TU IPHONE PARA CONECTAR)", 15, ACCENT, bold = true).apply {
                letterSpacing = 0.06f
            }
        )

        val cardsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        val savedAddr = LeapCarPlayPreferences.phoneAddress(this)
        val bonded = loadBondedDevicesSortedForCarPlay(savedAddr)

        if (bonded.isNotEmpty()) {
            bonded.take(3).forEachIndexed { index, dev ->
                val name = runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: dev.address
                val isSelected = if (savedAddr != null) dev.address == savedAddr else index == 0
                val isIphone = name.contains("iPhone", ignoreCase = true) || name.contains("Apple", ignoreCase = true)
                val sub = when {
                    isSelected -> "🍎 iPhone seleccionado · Toca para conectar"
                    isIphone -> "🍎 Apple CarPlay · Toca para conectar"
                    else -> "📱 Bluetooth vinculado · Usar en CarPlay"
                }
                cardsRow.addView(
                    deviceCard(
                        icon = if (isIphone || isSelected) "🍎" else "📱",
                        title = name,
                        subtitle = sub,
                        highlighted = isSelected,
                    ) {
                        LeapCarPlayPreferences.savePhone(this, dev.address, name)
                        render()
                        connect(true)
                    }
                )
            }
        } else {
            val savedName = LeapCarPlayPreferences.phoneAddress(this)?.let { LeapCarPlayPreferences.phoneName(this) }
            cardsRow.addView(
                deviceCard(
                    icon = "🍎",
                    title = savedName ?: "Elegir iPhone vinculado",
                    subtitle = "Bluetooth + Wi-Fi para CarPlay inalámbrico",
                    highlighted = savedName != null,
                ) {
                    choosePhone()
                }
            )
            cardsRow.addView(
                deviceCard(
                    icon = "🔌",
                    title = "Conectar por Cable USB",
                    subtitle = "Enchufa el iPhone al puerto de datos",
                    highlighted = findConnectedAppleUsb() != null,
                ) {
                    connect(false)
                }
            )
        }

        cardsRow.addView(
            deviceCard(
                icon = "＋",
                title = "Elegir / Vincular iPhone",
                subtitle = "Selector o ajustes Bluetooth del coche",
                highlighted = false,
                lastCard = true,
            ) {
                choosePhone()
            }
        )

        section.addView(cardsRow)
        return section
    }

    private fun loadBondedDevicesSortedForCarPlay(savedAddr: String?): List<BluetoothDevice> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        return runCatching {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter != null && adapter.isEnabled) {
                adapter.bondedDevices.orEmpty().sortedWith(
                    compareByDescending<BluetoothDevice> { savedAddr != null && it.address == savedAddr }
                        .thenByDescending { (it.name ?: "").contains("iPhone", ignoreCase = true) }
                        .thenBy { it.name ?: it.address }
                )
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun findConnectedAppleUsb(): UsbDevice? {
        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        return runCatching {
            usbManager.deviceList.values.firstOrNull { UsbDeviceCompat.isAppleDevice(it) }
        }.getOrNull()
    }

    private fun deviceCard(
        icon: String,
        title: String,
        subtitle: String,
        highlighted: Boolean,
        lastCard: Boolean = false,
        onClick: () -> Unit,
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rippleRounded(
                fill = if (highlighted) ACCENT_DARK_BG else CARD_BG,
                stroke = if (highlighted) ACCENT else BORDER,
                radiusDp = 20,
                strokeDp = if (highlighted) 2 else 1
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (!lastCard) marginEnd = dp(12)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val iconBox = TextView(this).apply {
            text = icon
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(if (highlighted) 0x3300E5C7 else 0xFF1A2636.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                marginEnd = dp(14)
            }
        }
        card.addView(iconBox)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(
            TextView(this).apply {
                text = title
                textSize = 20f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(TEXT)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        textCol.addView(
            TextView(this).apply {
                text = subtitle
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(if (highlighted) ACCENT else MUTED)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        card.addView(textCol)
        return card
    }

    private fun warningBanner(message: String, actionTitle: String?, onAction: (() -> Unit)?): View {
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(0xFF1A1E24.toInt(), WARNING, radiusDp = 18, strokeDp = 1)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        }
        banner.addView(
            label(message, 15, WARNING, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (actionTitle != null) marginEnd = dp(12)
            }
        )
        if (actionTitle != null && onAction != null) {
            banner.addView(
                Button(this).apply {
                    text = actionTitle
                    isAllCaps = false
                    textSize = 14.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(ON_ACCENT)
                    background = rippleRounded(WARNING, WARNING, radiusDp = 12, strokeDp = 1)
                    setPadding(dp(16), 0, dp(16), 0)
                    stateListAnimator = null
                    setOnClickListener { onAction() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42))
            )
        }
        return banner
    }

    private fun settings(content: LinearLayout) {
        content.addView(space(14))
        content.addView(
            label(
                "Los cambios de tamaño, resolución, búfer de audio y frecuencia de imagen reconectan CarPlay si hay sesión activa. El resto se aplica en la próxima conexión.",
                16,
                MUTED
            ).apply { setPadding(0, 0, 0, dp(18)) }
        )

        section(content, "Conexión automática e iPhone") { card ->
            toggle(
                card,
                "Conectar al abrir CarPlay",
                "Usa el último modo de conexión y el iPhone seleccionado.",
                LeapCarPlayPreferences.autoConnect(this)
            ) { LeapCarPlayPreferences.saveAutoConnect(this, it) }
            val currentPhone = LeapCarPlayPreferences.phoneAddress(this)?.let { LeapCarPlayPreferences.phoneName(this) } ?: "Sin elegir"
            card.addView(
                settingsButton("Elegir iPhone · $currentPhone") { choosePhone() },
                matchButton(10, 58)
            )
        }

        section(content, "Enlace inalámbrico") { card -> wirelessLinkControls(card) }

        section(content, "Pantalla y rendimiento") { card ->
            carPlaySizeControl(card)
            choice(
                card,
                "Resolución",
                listOf("Nativa (máxima nitidez)", "80 % · carga media", "60 % · carga ligera"),
                listOf(10, 8, 6).indexOf(AirPlayPersistence.loadDisplayScaleTenths(this)).coerceAtLeast(0)
            ) { AirPlayPersistence.saveDisplayScaleTenths(this, listOf(10, 8, 6)[it]) }

            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            choice(
                card,
                "Búfer de música",
                listOf("300 ms · por defecto", "500 ms · equilibrado", "1000 ms · máxima estabilidad"),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)
            ) { AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it]) }

            choice(
                card,
                "Frecuencia de imagen",
                listOf("30 fps · menor consumo", "60 fps · movimiento fluido"),
                if (AirPlayPersistence.loadFps(this) == 60) 1 else 0
            ) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }

            toggle(
                card,
                "Vídeo eficiente (HEVC)",
                "Déjalo desactivado (H.264) para máxima compatibilidad en la pantalla del coche.",
                AirPlayPersistence.loadHevcEnabled(this)
            ) { AirPlayPersistence.saveHevcEnabled(this, it) }

            toggle(
                card,
                "Volante a la derecha",
                "Coloca la barra de controles de CarPlay en el lado derecho.",
                AirPlayPersistence.loadRightHandDrive(this)
            ) { AirPlayPersistence.saveRightHandDrive(this, it) }

            toggle(
                card,
                "Pantalla completa",
                "Oculta las barras del sistema mientras CarPlay está abierto.",
                AirPlayPersistence.loadHideTopBar(this) && AirPlayPersistence.loadHideBottomBar(this)
            ) {
                AirPlayPersistence.saveHideTopBar(this, it)
                AirPlayPersistence.saveHideBottomBar(this, it)
            }
        }

        if (com.shilapi.xcertplay.hud.BydOutputSettings.available(this)) {
            section(content, "Navegación en cuadro BYD") { card ->
                toggle(
                    card,
                    "Indicaciones en HUD y cuadro",
                    "Muestra flechas de navegación y calles en pantallas BYD compatibles.",
                    com.shilapi.xcertplay.hud.BydOutputSettings.enabled(this)
                ) { com.shilapi.xcertplay.hud.BydOutputSettings.setEnabled(this, it) }
            }
        }

        section(content, "Permisos y ayuda de conexión") { card ->
            card.addView(
                label(
                    "«Dispositivos cercanos» conecta con tu iPhone. En modo USB, Android puede pedir autorización para un túnel VPN local necesario para el enlace de datos con el iPhone.",
                    15,
                    MUTED
                )
            )
            card.addView(
                settingsButton("Permisos de la aplicación") {
                    openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                },
                matchButton(14, 58)
            )
            card.addView(
                settingsButton("Ajustes de Bluetooth del coche") {
                    openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                },
                matchButton(10, 58)
            )
            card.addView(
                settingsButton("Ayuda y reinicio de Wi-Fi CarPlay") { wirelessHelp() },
                matchButton(10, 58)
            )
        }

        section(content, "Diagnóstico, pruebas y acerca de") { card ->
            card.addView(
                settingsButton("Simular iPhone (Demo de vídeo)") {
                    startActivity(
                        Intent(this, CarPlayHostActivity::class.java)
                            .putExtra(CarPlayLoopbackSimulator.EXTRA_SIMULATE_IPHONE, true)
                    )
                },
                matchButton(0, 58)
            )
            exportButton = settingsButton(
                if (exportInProgress) "Guardando informe…" else "Guardar informe de diagnóstico"
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportDiagnostics()
                else chooseReportDestination()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(10, 58))
            card.addView(
                settingsButton("Acerca de Qonecta Unit · CarPlay") {
                    page = "about"
                    render()
                },
                matchButton(10, 58)
            )
            val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "Los informes se guardan en Descargas/LeapCarPlay. "
            } else {
                "Elige dónde guardar tu informe. "
            }
            card.addView(
                label(
                    destination + "No se envía nada automáticamente y se excluyen credenciales privadas.",
                    14,
                    MUTED
                ).apply { setPadding(0, dp(10), 0, 0) }
            )
        }
    }

    private fun about(content: LinearLayout) {
        content.addView(space(14))
        section(content, "Qonecta Unit Core · CarPlay  ·  v${version()}") { card ->
            card.addView(
                label(
                    "Receptor independiente de Apple CarPlay integrado en Qonecta Unit Core para pantallas Android automotrices. Funciona por Cable USB o de forma inalámbrica con autenticación local, sin dongle externo ni cuenta adicional.\n\nNo es un producto certificado por Apple Inc.",
                    16,
                    TEXT
                )
            )
        }
        section(content, "Software libre y avisos") { card ->
            card.addView(
                label(
                    "Motor receptor basado en xcertplay (licencia GPL-3.0) e integración adaptada para Qonecta Unit Core (AGPL-3.0).\n\nIncluye AndroidX, Bouncy Castle, JmDNS y SLF4J.\n\nCarPlay y el icono de CarPlay son marcas registradas de Apple Inc.",
                    15,
                    MUTED
                )
            )
        }
    }

    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false

    private fun carHotspotOffDialog() {
        darkDialog()
            .setTitle("El Hotspot del coche está apagado")
            .setMessage("CarPlay está configurado para conectarse mediante el Hotspot «${AirPlayPersistence.loadManualHotspotSsid(this)}». Enciéndelo en los ajustes del coche y vuelve a conectar.")
            .setPositiveButton("Abrir ajustes del coche") { _, _ -> openCarWifiSettings() }
            .setNeutralButton("Conectar igualmente") { _, _ -> connect(true) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun wirelessLinkControls(parent: LinearLayout) {
        val carHotspot = AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL
        val options = arrayOf("Wi-Fi Direct 5 GHz (recomendado)", "Hotspot del coche")
        val control = settingsButton("Tipo de enlace · ${options[if (carHotspot) 1 else 0]}") {}
        control.setOnClickListener {
            var selection = if (carHotspot) 1 else 0
            darkDialog()
                .setTitle("Enlace inalámbrico de CarPlay")
                .setSingleChoiceItems(options, selection) { _, index -> selection = index }
                .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) "Aplicar y reconectar" else "Guardar") { _, _ ->
                    when {
                        (selection == 1) == carHotspot -> Unit
                        selection == 0 -> applyWirelessLink(WirelessHotspotMode.WIFI_P2P)
                        hotspotError(storedSsid(), storedPassword()) == null -> applyWirelessLink(WirelessHotspotMode.MANUAL)
                        else -> askHotspotCredentials { ssid, password ->
                            saveHotspotCredentials(ssid, password)
                            applyWirelessLink(WirelessHotspotMode.MANUAL)
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        parent.addView(control, matchButton(0, 58))
        parent.addView(space(10))
        if (!carHotspot) {
            parent.addView(
                label("Qonecta Unit crea su propia red Wi-Fi Direct 5 GHz para conectar el iPhone automáticamente.", 14, MUTED)
                    .apply { setPadding(0, 0, 0, dp(12)) }
            )
            return
        }
        val ssid = storedSsid()
        val password = storedPassword()
        parent.addView(
            settingsButton("Nombre del Hotspot · $ssid") {
                textInput("Nombre del Hotspot del coche", ssid, secret = false) { value ->
                    hotspotError(value, password)?.let { toast(it); return@textInput }
                    saveHotspotCredentials(value, password)
                    render()
                }
            },
            matchButton(0, 58)
        )
        parent.addView(space(10))
        parent.addView(
            settingsButton("Contraseña del Hotspot · ${if (password.isEmpty()) "ninguna" else "•".repeat(8)}") {
                textInput("Contraseña del Hotspot del coche", password, secret = true) { value ->
                    hotspotError(ssid, value)?.let { toast(it); return@textInput }
                    saveHotspotCredentials(ssid, value)
                    render()
                }
            },
            matchButton(0, 58)
        )
        parent.addView(
            label(
                "Activa la Zona Wi-Fi en los ajustes del coche e introduce aquí el mismo nombre y contraseña. El iPhone se unirá a esa red para proyectar CarPlay.",
                14,
                MUTED
            ).apply { setPadding(0, dp(8), 0, dp(12)) }
        )
    }

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.validate(ssid, password)

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(
            this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password)
        )
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun askHotspotCredentials(done: (String, String) -> Unit) {
        textInput("Nombre del Hotspot del coche", storedSsid(), secret = false) { ssid ->
            textInput("Contraseña del Hotspot del coche", storedPassword(), secret = true) { password ->
                val error = hotspotError(ssid, password)
                if (error != null) toast(error) else done(ssid, password)
            }
        }
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        if (CarPlayBackgroundSession.hasSession()) connect(true)
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            setTextColor(TEXT)
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        darkDialog()
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Guardar") { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        val spanishLabels = sizes.map { size ->
            when (size) {
                com.shilapi.xcertplay.airplay.CarPlaySize.LARGE -> "Grande"
                com.shilapi.xcertplay.airplay.CarPlaySize.MEDIUM -> "Mediano (recomendado)"
                com.shilapi.xcertplay.airplay.CarPlaySize.SMALL -> "Pequeño (más espacio)"
            }
        }
        choice(parent, "Tamaño de iconos y texto", spanishLabels, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(
            label("Ajusta el tamaño de los iconos de CarPlay en la pantalla del coche.", 14, MUTED)
                .apply { setPadding(0, 0, 0, dp(14)) }
        )
    }

    private fun connect(wireless: Boolean) {
        if (setupError != null) {
            toast(setupError!!)
            return
        }
        if (wireless && carHotspotOff()) {
            carHotspotOffDialog()
            return
        }
        if (wireless && LeapCarPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true
            choosePhone()
            return
        }
        val preferences = getSharedPreferences("leapcarplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("notification_asked", false)
        ) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            // Detiene Android Auto inalámbrico antes de iniciar CarPlay para evitar colisión Wi-Fi/P2P
            runCatching {
                startService(Intent(this, com.andrerinas.openheadunit.aap.AapService::class.java).apply {
                    action = com.andrerinas.openheadunit.aap.AapService.ACTION_STOP_WIRELESS
                })
            }
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            connectingStartElapsedMs = SystemClock.elapsedRealtime()
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) {
            CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        } else {
            open()
        }
    }

    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            darkDialog()
                .setTitle("Enciende el Bluetooth")
                .setMessage("Activa el Bluetooth del coche y vincula primero tu iPhone.")
                .setPositiveButton("Abrir Bluetooth") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("Más tarde", null)
                .show()
            return
        }
        val savedAddr = LeapCarPlayPreferences.phoneAddress(this)
        val devices = loadBondedDevicesSortedForCarPlay(savedAddr)
        if (devices.isEmpty()) {
            darkDialog()
                .setTitle("Vincula tu iPhone")
                .setMessage("En tu iPhone abre Ajustes → Bluetooth y vincúlalo con el coche. Después vuelve aquí y toca Conectar CarPlay.")
                .setPositiveButton("Abrir Bluetooth") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("Entendido", null)
                .show()
            return
        }
        val items = devices.mapIndexed { idx, device ->
            val name = device.name ?: "iPhone"
            com.andrerinas.openheadunit.main.PhonePickerDialog.PhoneItem(
                name = name,
                address = device.address,
                highlighted = if (savedAddr != null) device.address == savedAddr else idx == 0,
                device = device,
            )
        }
        com.andrerinas.openheadunit.main.PhonePickerDialog.show(
            context = this,
            title = "Elige tu iPhone para CarPlay",
            subtitle = "Toca tu iPhone vinculado por Bluetooth para conectar por Wi-Fi",
            items = items,
            pairButtonText = "Vincular otro iPhone",
            cancelButtonText = "Cancelar",
            onSelect = { index, _ ->
                val device = devices[index]
                LeapCarPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless
                pendingWireless = false
                render()
                if (start) connect(true)
            },
            onPairNew = { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            onCancel = { pendingWireless = false },
        )
    }

    private fun wirelessHelp() {
        darkDialog()
            .setTitle("Ayuda de conexión inalámbrica")
            .setMessage("Vincula tu iPhone al Bluetooth del coche, mantén el Wi-Fi activado y permite CarPlay cuando el iPhone pregunte.\n\nSi una app anterior dejó una red Wi-Fi Direct bloqueada, pulsa «Reiniciar Wi-Fi CarPlay» abajo. El Wi-Fi normal del coche no se desactiva.")
            .setPositiveButton("Entendido", null)
            .setNeutralButton("Reiniciar Wi-Fi CarPlay") { _, _ -> confirmWirelessReset() }
            .show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"
        render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        darkDialog()
            .setTitle("¿Reiniciar Wi-Fi CarPlay?")
            .setMessage("Esto cierra cualquier grupo Wi-Fi Direct previo que haya quedado bloqueado y vuelve a intentar la conexión.")
            .setPositiveButton("Reiniciar y conectar") { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) {
            toast("Esta unidad no admite Wi-Fi Direct.")
            return
        }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) {
                    channel.close()
                    connect(true)
                    return@requestGroupInfo
                }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> {
                                        channel.close()
                                        if (!isFinishing && !isDestroyed) connect(true)
                                    }
                                    SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.close()
                                        toast("Wi-Fi Direct sigue ocupado. Cierra otras apps de proyección e inténtalo de nuevo.")
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }

                    override fun onFailure(reason: Int) {
                        channel.close()
                        toast("No se pudo reiniciar Wi-Fi Direct. Cierra otras apps de proyección e inténtalo de nuevo.")
                    }
                })
            }
        } catch (_: SecurityException) {
            channel.close()
            permissionHelp(
                "Permisos inalámbricos",
                "Concede Dispositivos cercanos (y Ubicación en versiones anteriores de Android) para reiniciar Wi-Fi CarPlay."
            )
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        val active = CarPlayBackgroundSession.active
        val appleUsb = findConnectedAppleUsb()
        val carHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL
        val savedAddr = LeapCarPlayPreferences.phoneAddress(this)
        val savedPhoneName = if (savedAddr != null) LeapCarPlayPreferences.phoneName(this) else null

        val pill = statusPill
        val badge = transportBadge
        val title = heroTitle
        val sub = heroSubtitle
        val ring = connectionRing

        when {
            setupError != null -> {
                pill?.setTextColor(WARNING)
                pill?.text = "●  ATENCIÓN"
                badge?.setTextColor(TEXT)
                badge?.text = "⚠ Revisar instalación"
                title?.text = "Configuración pendiente"
                sub?.text = setupError
                ring?.state = ConnectionRingView.State.IDLE
                ring?.protocolTitle = "Apple CarPlay"
                ring?.subtitleText = "Revisar aviso"
            }
            active -> {
                pill?.setTextColor(OK_GREEN)
                pill?.text = "●  CONECTADO"
                badge?.setTextColor(OK_GREEN)
                badge?.text = if (AirPlayPersistence.loadWirelessEnabled(this)) {
                    if (carHotspotMode) "📲 Hotspot CarPlay activo" else "📡 Wi-Fi Direct activo"
                } else {
                    "🔌 CarPlay USB activo"
                }
                title?.text = if (savedPhoneName != null) "Conectado a $savedPhoneName" else "CarPlay conectado"
                sub?.text = "Sesión activa. Toca «Abrir CarPlay» para volver a pantalla completa."
                ring?.state = ConnectionRingView.State.CONNECTED
                ring?.protocolTitle = "Apple CarPlay"
                ring?.subtitleText = "En proyección"
            }
            running -> {
                if (connectingStartElapsedMs == 0L) connectingStartElapsedMs = SystemClock.elapsedRealtime()
                val elapsed = ((SystemClock.elapsedRealtime() - connectingStartElapsedMs) / 1000L).toInt().coerceAtLeast(0)
                pill?.setTextColor(ACCENT)
                pill?.text = "●  CONECTANDO"
                badge?.setTextColor(ACCENT)
                badge?.text = if (AirPlayPersistence.loadWirelessEnabled(this)) {
                    if (carHotspotMode) "📲 Conectando por Hotspot" else "📡 Conectando por Wi-Fi Direct"
                } else {
                    "🔌 Conectando iPhone por USB"
                }
                title?.text = "Conectando a ${savedPhoneName ?: "tu iPhone"}…"
                sub?.text = "Mantén el iPhone desbloqueado y acepta CarPlay si aparece el aviso."
                ring?.state = ConnectionRingView.State.CONNECTING
                ring?.elapsedSeconds = elapsed
                ring?.activeStep = when {
                    elapsed < 3 -> 1
                    elapsed < 7 -> 2
                    else -> 3
                }
                ring?.protocolTitle = "Apple CarPlay"
                ring?.subtitleText = if (elapsed < 4) "Negociando enlace" else "Esperando al iPhone"
            }
            else -> {
                connectingStartElapsedMs = 0L
                pill?.setTextColor(ACCENT)
                pill?.text = "●  LISTO"
                if (appleUsb != null) {
                    badge?.setTextColor(ACCENT)
                    badge?.text = "🔌 iPhone USB detectado"
                } else {
                    badge?.setTextColor(TEXT)
                    badge?.text = if (carHotspotMode) "📲 Hotspot del coche" else "📡 Wi-Fi Direct 5 GHz"
                }
                title?.text = if (savedPhoneName != null) "Listo para $savedPhoneName" else "Conecta tu iPhone"
                sub?.text = when {
                    appleUsb != null ->
                        "iPhone detectado en el puerto USB. Toca «CarPlay USB» para iniciar al instante."
                    savedPhoneName != null ->
                        "Toca «Conectar CarPlay», usa el Cable USB o elige otro iPhone abajo."
                    else ->
                        "Vincula tu iPhone por Bluetooth con el coche o enchúfalo al puerto USB de datos."
                }
                ring?.state = ConnectionRingView.State.IDLE
                ring?.protocolTitle = if (appleUsb != null) "CarPlay USB" else "Apple CarPlay"
                ring?.subtitleText = if (appleUsb != null) "iPhone USB listo" else "Toca para conectar"
            }
        }

        connectButton?.text = if (running) "Abrir CarPlay" else "Conectar CarPlay"
        connectButton?.isEnabled = setupError == null

        usbButton?.let { btn ->
            if (appleUsb != null) {
                btn.text = "🔌 CarPlay USB"
                btn.setTextColor(ACCENT)
                btn.background = rippleRounded(ACCENT_DARK_BG, ACCENT, radiusDp = 33, strokeDp = 2)
            } else {
                btn.text = "🔌 Cable USB"
                btn.setTextColor(TEXT)
                btn.background = rippleRounded(SURFACE_TOP, EDGE, radiusDp = 33, strokeDp = 2)
            }
            btn.visibility = if (running) View.GONE else View.VISIBLE
        }

        disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
        disconnectButton?.isEnabled = true
    }

    private fun reportFileName() =
        "QonectaCarPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        runCatching { export.launch(reportFileName()) }.onFailure {
            toast(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    "No se pudo abrir el selector de destino. Intenta guardar de nuevo en Descargas."
                else "Esta unidad no dispone de selector de archivos para guardar el informe."
            )
        }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = "Guardando informe…" }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = buildString {
                    appendLine("Qonecta Unit Core · CarPlay ${version()} · diagnostic report")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("Authentication: local experimental beta identity; no remote fallback")
                    appendLine("Saved video preference: ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("Saved resolution preference: ${AirPlayPersistence.loadDisplayScaleTenths(appContext) * 10}%")
                    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- Last display negotiation ---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    for (name in SessionLogFile.REPORT_NAMES) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                if (uri != null) DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DiagnosticExportStore.saveToDownloads(appContext.contentResolver, fileName, report)
                } else error("A save location is required")
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = "Guardar informe de diagnóstico" }
                if (result.isSuccess) {
                    darkDialog()
                        .setTitle("Informe guardado")
                        .setMessage(if (uri == null) "Descargas/LeapCarPlay/$fileName" else "El informe se guardó en la ubicación elegida.")
                        .setPositiveButton("Listo", null)
                        .show()
                } else {
                    darkDialog()
                        .setTitle("No se pudo guardar el informe")
                        .setMessage("Comprueba el espacio disponible o elige otra carpeta.")
                        .setPositiveButton("Elegir carpeta") { _, _ -> chooseReportDestination() }
                        .setNegativeButton("Cerrar", null)
                        .show()
                }
            }
        }, "leapcarplay-export").start()
    }

    private fun permissionHelp(title: String, body: String) {
        darkDialog()
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("Ajustes de la app") { _, _ ->
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Más tarde", null)
            .show()
    }

    private fun openSystem(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            toast("Abre este ajuste desde la app de Ajustes del coche.")
        }
    }

    private fun darkDialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun version() = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.2.8-core"
    }.getOrDefault("0.2.8-core")

    private fun section(parent: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD_BG, CARD_BORDER, radiusDp = 22, strokeDp = 2)
            setPadding(dp(24), dp(22), dp(24), dp(22))
        }
        card.addView(label(title, 21, ACCENT, bold = true).apply { setPadding(0, 0, 0, dp(14)) })
        build(card)
        parent.addView(
            card,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
        )
    }

    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, save: (Boolean) -> Unit) {
        val line = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val textCol = column().apply {
            addView(label(title, 18, TEXT, bold = true))
            addView(label(description, 14, MUTED).apply { setPadding(0, dp(4), dp(16), 0) })
        }
        line.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(
            Switch(this).apply {
                contentDescription = title
                isChecked = value
                minHeight = dp(52)
                thumbTintList = ColorStateList.valueOf(if (value) ACCENT else 0xFFD0DCE8.toInt())
                trackTintList = ColorStateList.valueOf(if (value) 0x6600E5C7 else 0xFF2B394A.toInt())
                setOnCheckedChangeListener { _, checked ->
                    thumbTintList = ColorStateList.valueOf(if (checked) ACCENT else 0xFFD0DCE8.toInt())
                    trackTintList = ColorStateList.valueOf(if (checked) 0x6600E5C7 else 0xFF2B394A.toInt())
                    save(checked)
                }
            }
        )
        parent.addView(line)
    }

    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, save: (Int) -> Unit) {
        var selection = current.coerceIn(0, options.lastIndex)
        val btn = settingsButton("$title · ${options[selection]}") {}
        btn.setOnClickListener {
            var pendingSelection = selection
            darkDialog()
                .setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) "Aplicar y reconectar" else "Guardar") { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        btn.text = "$title · ${options[selection]}"
                        if (CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        parent.addView(btn, matchButton(0, 58))
        parent.addView(space(10))
    }

    private fun headerPillButton(title: String, highlighted: Boolean, click: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(if (highlighted) ACCENT else TEXT)
        background = rippleRounded(
            fill = if (highlighted) ACCENT_DARK_BG else SURFACE_TOP,
            stroke = if (highlighted) ACCENT else BORDER,
            radiusDp = 18,
            strokeDp = 2
        )
        setPadding(dp(18), 0, dp(18), 0)
        stateListAnimator = null
        setOnClickListener { click() }
    }

    private fun primaryPillButton(title: String, click: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 20f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(ON_ACCENT)
        background = rippleRounded(ACCENT, ACCENT, radiusDp = 33, strokeDp = 1)
        setPadding(dp(28), 0, dp(28), 0)
        minHeight = dp(66)
        stateListAnimator = null
        setOnClickListener { click() }
    }

    private fun secondaryPillButton(title: String, click: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 19f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(TEXT)
        background = rippleRounded(SURFACE_TOP, EDGE, radiusDp = 33, strokeDp = 2)
        setPadding(dp(22), 0, dp(22), 0)
        minHeight = dp(66)
        stateListAnimator = null
        setOnClickListener { click() }
    }

    private fun dangerPillButton(title: String, click: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 18.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(ERROR_RED)
        background = rippleRounded(ERROR_BG, ERROR_RED, radiusDp = 33, strokeDp = 2)
        setPadding(dp(22), 0, dp(22), 0)
        minHeight = dp(66)
        stateListAnimator = null
        setOnClickListener { click() }
    }

    private fun modeToggleButton(title: String, selected: Boolean, click: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 15.5f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(if (selected) ON_ACCENT else 0xFFAEC0D2.toInt())
        background = rippleRounded(
            fill = if (selected) ACCENT else SURFACE_TOP,
            stroke = if (selected) ACCENT else BORDER,
            radiusDp = 18,
            strokeDp = 2
        )
        setPadding(dp(12), dp(6), dp(12), dp(6))
        stateListAnimator = null
        setOnClickListener { click() }
    }

    private fun settingsButton(title: String, click: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 17.5f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(TEXT)
        background = rippleRounded(SURFACE_TOP, EDGE, radiusDp = 16, strokeDp = 1)
        setPadding(dp(20), 0, dp(20), 0)
        minHeight = dp(56)
        stateListAnimator = null
        setOnClickListener { click() }
    }

    private fun pillLabel(value: String, textColor: Int, bgColor: Int) = TextView(this).apply {
        text = value
        setTextColor(textColor)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dp(20).toFloat()
        }
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun rounded(color: Int, stroke: Int, radiusDp: Int = 20, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(strokeDp), stroke)
    }

    private fun rippleRounded(fill: Int, stroke: Int, radiusDp: Int, strokeDp: Int) = RippleDrawable(
        ColorStateList.valueOf(0x3300E5C7),
        rounded(fill, stroke, radiusDp, strokeDp),
        null
    )

    private fun matchButton(top: Int = 0, height: Int = 60) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)).apply { topMargin = dp(top) }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BG = 0xFF0A0E14.toInt()
        private const val CARD_BG = 0xFF121B26.toInt()
        private const val CARD_BORDER = 0xFF263547.toInt()
        private const val SURFACE_TOP = 0xFF1E2935.toInt()
        private const val BORDER = 0xFF243140.toInt()
        private const val EDGE = 0xFF2E4256.toInt()
        private const val ACCENT = 0xFF00E5C7.toInt()
        private const val ACCENT_DARK_BG = 0xFF10282C.toInt()
        private const val ON_ACCENT = 0xFF06131A.toInt()
        private const val TEXT = 0xFFEAF2F7.toInt()
        private const val MUTED = 0xFF98ACC0.toInt()
        private const val OK_GREEN = 0xFF3AD29F.toInt()
        private const val WARNING = 0xFFFFB547.toInt()
        private const val ERROR_RED = 0xFFFF6B7A.toInt()
        private const val ERROR_BG = 0xFF3A1A22.toInt()
    }
}
