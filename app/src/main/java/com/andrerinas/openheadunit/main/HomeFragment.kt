package com.andrerinas.openheadunit.main

import android.Manifest
import android.app.Dialog
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.WirelessScopePolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.Settings
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.headqlink.unit.extended.ConnectionRingView
import com.headqlink.unit.extended.ExtendedHubView
import com.headqlink.unit.extended.LeapVehicleDataSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Pantalla principal de espera y conexión de Qonecta Unit (inspirada en el flujo visual con
 * contador circular en vivo "1 s, 2 s, 3 s...", estado por pasos y tarjetas de Conexiones Recientes).
 */
class HomeFragment : Fragment() {
    private val app get() = App.provide(requireContext())
    private var activeDialog: Dialog? = null
    private var isBtCompanionDialogOpen = false
    private var lastCompanionConnectedState = false
    private var companionStatusListener: ((com.headqlink.unit.bridgehost.CompanionBridgeHost.Status) -> Unit)? = null

    private var manualConnectingActive = false
    private var connectingStartElapsedMs = 0L
    private var connectingTargetName = ""
    private var connectingProtocol = "Android Auto"
    private var connectingTransportLabel = "🔗 Conectando sin cables (5 GHz)"
    private var carPlayLaunchJob: Job? = null
    private var lastDetectedUsbApk: com.headqlink.unit.extended.OtaSelfUpdater.DetectedApk? = null
    private var lastUsbScanElapsedMs = 0L

    private val apkFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            com.headqlink.unit.extended.OtaSelfUpdater.installFromContentUri(requireContext(), uri)
        }
    }

    private val bluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (app.settings.btCompanionEnabled) {
                com.headqlink.unit.bridgehost.CompanionBridgeHost.setBluetoothEnabled(requireContext(), true)
            }
            view?.let { rebuildRecentDevicesRow(it) }
            startPrimaryConnection()
        } else {
            showBluetoothHelp(R.string.bt_permission_denied)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        LeapVehicleDataSource.start(requireContext().applicationContext)
        com.headqlink.unit.bridgehost.CompanionBridgeHost.setBluetoothEnabled(
            requireContext().applicationContext,
            app.settings.btCompanionEnabled
        )

        val liveBridgeListener: (com.headqlink.unit.bridgehost.CompanionBridgeHost.Status) -> Unit = { st ->
            if (isAdded) {
                updateConnectionModeButtons(view)
                refreshHeroState(view)
                rebuildRecentDevicesRow(view)
                if (isBtCompanionDialogOpen && st.isConnected != lastCompanionConnectedState) {
                    lastCompanionConnectedState = st.isConnected
                    showBluetoothCompanionDialog(view)
                } else {
                    lastCompanionConnectedState = st.isConnected
                }
            }
        }
        companionStatusListener = liveBridgeListener
        com.headqlink.unit.bridgehost.CompanionBridgeHost.addListener(liveBridgeListener)

        view.findViewById<View>(R.id.home_support_button)?.setOnClickListener {
            showSupportCreditsDialog()
        }
        view.findViewById<View>(R.id.home_version_badge)?.setOnClickListener {
            showSupportCreditsDialog()
        }

        view.findViewById<View>(R.id.companion_pair_button)?.setOnClickListener {
            showBluetoothCompanionDialog(view)
        }

        view.findViewById<View>(R.id.wifi_button)?.setOnClickListener {
            if (app.commManager.isConnected) {
                openProjection()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                startPrimaryConnection()
            }
        }

        view.findViewById<View>(R.id.connection_ring_view)?.setOnClickListener {
            if (app.commManager.isConnected) {
                openProjection()
            } else if (!manualConnectingActive) {
                startPrimaryConnection()
            }
        }

        view.findViewById<View>(R.id.hero_cancel_button)?.setOnClickListener {
            cancelCurrentConnectionAttempt(view)
        }

        view.findViewById<View>(R.id.usb_button)?.setOnClickListener {
            if (app.commManager.isConnected && !app.commManager.isWirelessSession) {
                openProjection()
            } else if (app.commManager.isConnected && app.commManager.isWirelessSession) {
                showUsbSwitchDialog("Dispositivo USB")
            } else {
                app.settings.usbModeEnabled = true
                val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as? UsbManager
                val hasAppleUsb = usbManager?.deviceList?.values?.any { it.vendorId == 0x05AC } == true
                if (hasAppleUsb) {
                    beginConnectingUi(
                        view = view,
                        targetName = "iPhone por USB",
                        protocol = "CarPlay",
                        transportLabel = "🔗 Conectando por cable",
                    )
                    carPlayLaunchJob?.cancel()
                    carPlayLaunchJob = viewLifecycleOwner.lifecycleScope.launch {
                        delay(2200L)
                        if (manualConnectingActive && isAdded) {
                            startActivity(Intent(requireContext(), com.shilapi.xcertplay.LeapCarPlayActivity::class.java))
                        }
                    }
                } else {
                    updateConnectionModeButtons(view)
                    beginConnectingUi(
                        view = view,
                        targetName = "móvil por Cable USB",
                        protocol = "Android Auto",
                        transportLabel = "🔗 Conectando por cable",
                    )
                    ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_CHECK_USB
                    })
                }
            }
        }

        view.findViewById<View>(R.id.carplay_button)?.setOnClickListener {
            beginConnectingUi(
                view = view,
                targetName = "iPhone (CarPlay)",
                protocol = "CarPlay",
                transportLabel = if (!app.settings.wifi5gEnabled && !app.settings.hotspotModeEnabled) "🔗 Conectando por cable / Bluetooth" else "🔗 Conectando sin cables",
            )
            carPlayLaunchJob?.cancel()
            carPlayLaunchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(2800L)
                if (manualConnectingActive && isAdded) {
                    startActivity(Intent(requireContext(), com.shilapi.xcertplay.LeapCarPlayActivity::class.java))
                }
            }
        }

        // Casilla 1: Bluetooth Companion (independiente, nunca enciende Wi-Fi 5G)
        view.findViewById<View>(R.id.mode_btn_bt_companion)?.apply {
            setOnClickListener {
                val next = !app.settings.btCompanionEnabled
                app.settings.btCompanionEnabled = next
                com.headqlink.unit.bridgehost.CompanionBridgeHost.setBluetoothEnabled(requireContext(), next)
                if (!next && manualConnectingActive && connectingProtocol == "Solo Bluetooth") {
                    manualConnectingActive = false
                }
                updateConnectionModeButtons(view)
                refreshHeroState(view)
                rebuildRecentDevicesRow(view)
            }
            setOnLongClickListener {
                showBluetoothCompanionDialog(view)
                true
            }
        }

        // Casilla 2: Wi-Fi 5 GHz (Android Auto sin cables) — desmarcar apaga 5 GHz de inmediato
        view.findViewById<View>(R.id.mode_btn_wifi_direct)?.setOnClickListener {
            val next = !app.settings.wifi5gEnabled
            app.settings.wifi5gEnabled = next
            if (next) {
                app.settings.nativeApTransport = 0
                ensureCarWifiEnabled()
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_START_WIRELESS
                })
            } else {
                if (app.commManager.isConnected && app.commManager.isWirelessSession) {
                    app.commManager.disconnect(sendByeBye = true, isUserExit = true)
                }
                if (manualConnectingActive && connectingProtocol == "Android Auto") {
                    manualConnectingActive = false
                }
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_START_WIRELESS
                })
            }
            com.headqlink.unit.bridgehost.CompanionBridgeHost.pushImmediateSync()
            updateConnectionModeButtons(view)
            refreshHeroState(view)
            rebuildRecentDevicesRow(view)
        }

        // Casilla 3: Hotspot Móvil — desmarcar detiene búsqueda Wi-Fi de inmediato
        view.findViewById<View>(R.id.mode_btn_hotspot)?.setOnClickListener {
            val next = !app.settings.hotspotModeEnabled
            app.settings.hotspotModeEnabled = next
            if (next) {
                ensureCarWifiEnabled()
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_START_WIRELESS_SCAN
                })
            } else {
                if (app.commManager.isConnected && app.commManager.isWirelessSession) {
                    app.commManager.disconnect(sendByeBye = true, isUserExit = true)
                }
                if (manualConnectingActive && connectingProtocol == "Android Auto") {
                    manualConnectingActive = false
                }
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_START_WIRELESS
                })
            }
            com.headqlink.unit.bridgehost.CompanionBridgeHost.pushImmediateSync()
            updateConnectionModeButtons(view)
            refreshHeroState(view)
            rebuildRecentDevicesRow(view)
        }

        // Casilla 4: Cable USB — si se desmarca, el puerto USB solo carga batería sin lanzar Android Auto
        view.findViewById<View>(R.id.mode_btn_usb)?.setOnClickListener {
            val next = !app.settings.usbModeEnabled
            app.settings.usbModeEnabled = next
            if (next) {
                if (!app.commManager.isConnected) {
                    ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_CHECK_USB
                    })
                }
            } else {
                if (app.commManager.isConnected && !app.commManager.isWirelessSession) {
                    app.commManager.disconnect(sendByeBye = true, isUserExit = true)
                }
            }
            com.headqlink.unit.bridgehost.CompanionBridgeHost.pushImmediateSync()
            updateConnectionModeButtons(view)
            refreshHeroState(view)
            rebuildRecentDevicesRow(view)
        }

        view.findViewById<View>(R.id.extended_mode_toggle_button)?.setOnClickListener {
            cycleExtendedMode(view)
        }

        view.findViewById<View>(R.id.extended_hub_button)?.setOnClickListener {
            activeDialog?.dismiss()
            isBtCompanionDialogOpen = false
            activeDialog = ExtendedHubView.showDialog(requireContext())
        }

        view.findViewById<View>(R.id.cluster_page_button)?.setOnClickListener {
            com.andrerinas.openheadunit.cluster.ClusterMapController.cyclePage(requireContext())
        }

        view.findViewById<View>(R.id.wifi_conflict_action)?.setOnClickListener {
            val enabledDirectly = ensureCarWifiEnabled()
            if (!enabledDirectly) {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) }
                    .onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) } }
            }
            updateConnectionModeButtons(view)
        }

        view.findViewById<View>(R.id.settings_button)?.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        view.findViewById<View>(R.id.home_usb_updater_button)?.setOnClickListener {
            activeDialog?.dismiss()
            isBtCompanionDialogOpen = false
            activeDialog = com.headqlink.unit.extended.OtaSelfUpdater.showDialog(requireContext()) {
                val opened = com.headqlink.unit.files.CarFiles.open(requireContext())
                if (!opened) {
                    runCatching { apkFilePickerLauncher.launch(com.headqlink.unit.files.CarFiles.picker()) }
                }
            }
        }

        view.findViewById<View>(R.id.usb_apk_browse_files_btn)?.setOnClickListener {
            val opened = com.headqlink.unit.files.CarFiles.open(requireContext())
            if (!opened) {
                runCatching { apkFilePickerLauncher.launch(com.headqlink.unit.files.CarFiles.picker()) }
            }
        }

        view.findViewById<View>(R.id.usb_apk_install_now_btn)?.setOnClickListener {
            val candidate = lastDetectedUsbApk
            if (candidate != null && candidate.file.exists()) {
                com.headqlink.unit.extended.OtaSelfUpdater.installApkFile(requireContext(), candidate.file)
            } else {
                activeDialog?.dismiss()
                isBtCompanionDialogOpen = false
                activeDialog = com.headqlink.unit.extended.OtaSelfUpdater.showDialog(requireContext()) {
                    val opened = com.headqlink.unit.files.CarFiles.open(requireContext())
                    if (!opened) {
                        runCatching { apkFilePickerLauncher.launch(com.headqlink.unit.files.CarFiles.picker()) }
                    }
                }
            }
        }

        view.findViewById<View>(R.id.exit_button)?.setOnClickListener {
            requireActivity().moveTaskToBack(true)
        }

        updateExtendedModeButton(view)
        updateConnectionModeButtons(view)
        rebuildRecentDevicesRow(view)
        refreshHeroState(view)
        view.findViewById<TextView>(R.id.home_version_badge)?.text =
            "C10 · v${com.andrerinas.openheadunit.BuildConfig.VERSION_NAME} · por CharlysEV"

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AapService.usbSwitchPrompt.collect { prompt ->
                        if (prompt != null) {
                            showUsbSwitchDialog(prompt.deviceName)
                        }
                    }
                }
                launch {
                    app.commManager.connectionState.collect { state ->
                        when (state) {
                            is CommManager.ConnectionState.TransportStarted -> {
                                manualConnectingActive = false
                                refreshHeroState(view)
                            }
                            is CommManager.ConnectionState.Disconnected -> {
                                refreshHeroState(view)
                            }
                            else -> {
                                if (!manualConnectingActive) {
                                    val fallbackName = app.settings.autoStartBluetoothDeviceName
                                        ?.takeIf { it.isNotBlank() } ?: "tu móvil"
                                    beginConnectingUi(
                                        view = view,
                                        targetName = fallbackName,
                                        protocol = "Android Auto",
                                        transportLabel = currentTransportConnectingLabel(),
                                    )
                                } else {
                                    refreshHeroState(view)
                                }
                            }
                        }
                        updateConnectionModeButtons(view)
                    }
                }
                launch {
                    val subtitleView = view.findViewById<TextView>(R.id.home_c10_telemetry_subtitle)
                    val clusterBtn = view.findViewById<MaterialButton>(R.id.cluster_page_button)
                    while (true) {
                        com.headqlink.unit.extended.QonectaTheme.ensureLoaded(requireContext())
                        val themeAccent = com.headqlink.unit.extended.QonectaTheme.accent
                        val themeOnAccent = com.headqlink.unit.extended.QonectaTheme.onAccent
                        subtitleView?.setTextColor(themeAccent)
                        view.findViewById<MaterialButton>(R.id.wifi_button)?.apply {
                            backgroundTintList = android.content.res.ColorStateList.valueOf(themeAccent)
                            setTextColor(themeOnAccent)
                            iconTint = android.content.res.ColorStateList.valueOf(themeOnAccent)
                        }
                        view.findViewById<MaterialButton>(R.id.extended_hub_button)?.apply {
                            backgroundTintList = android.content.res.ColorStateList.valueOf(themeAccent)
                            setTextColor(themeOnAccent)
                        }
                        updateExtendedModeButton(view)
                        updateConnectionModeButtons(view)
                        refreshHeroState(view)

                        val snap = LeapVehicleDataSource.snapshot(allowDemoFallback = false)
                        if (snap.hasBatteryData) {
                            subtitleView?.text =
                                "LEAPMOTOR C10 EN VIVO  ·  BATERÍA ${snap.socPct.roundToInt()}% (${snap.effectiveRangeKm.roundToInt()} KM)  ·  MODO ${snap.driveModeLabel().uppercase()}  ·  POR CHARLYSEV"
                        }

                        val pageLabel = when (com.andrerinas.openheadunit.cluster.ClusterMapController.currentPage) {
                            com.andrerinas.openheadunit.cluster.ClusterMapController.ClusterPage.MAP -> "Cuadro: Mapa"
                            com.andrerinas.openheadunit.cluster.ClusterMapController.ClusterPage.MUSIC -> "Cuadro: Música"
                            com.andrerinas.openheadunit.cluster.ClusterMapController.ClusterPage.EXTENDED -> "Cuadro: Extendido"
                            com.andrerinas.openheadunit.cluster.ClusterMapController.ClusterPage.NATIVE_C10 -> "Cuadro: Original C10"
                        }
                        clusterBtn?.text = pageLabel

                        val nowElapsed = SystemClock.elapsedRealtime()
                        if (lastUsbScanElapsedMs == 0L || nowElapsed - lastUsbScanElapsedMs > 30000L) {
                            lastUsbScanElapsedMs = nowElapsed
                            launch(kotlinx.coroutines.Dispatchers.IO) {
                                val candidate = com.headqlink.unit.extended.OtaSelfUpdater.findBestUsbUpdateCandidate(requireContext().applicationContext)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    if (!isAdded) return@withContext
                                    lastDetectedUsbApk = candidate
                                    val usbBanner = view.findViewById<View>(R.id.usb_apk_update_banner)
                                    val usbText = view.findViewById<TextView>(R.id.usb_apk_update_text)
                                    val usbInstallBtn = view.findViewById<MaterialButton>(R.id.usb_apk_install_now_btn)
                                    val usbTopBtn = view.findViewById<MaterialButton>(R.id.home_usb_updater_button)
                                    if (candidate != null) {
                                        val isNewer = candidate.packageName == requireContext().packageName &&
                                            candidate.versionCode > com.andrerinas.openheadunit.BuildConfig.VERSION_CODE.toLong()
                                        usbBanner?.visibility = View.VISIBLE
                                        usbText?.text = if (isNewer) {
                                            "📦 ¡Nueva versión detectada en el pendrive USB! ${candidate.appLabel} v${candidate.versionName} (${String.format(java.util.Locale.getDefault(), "%.1f", candidate.sizeMb)} MB) · Lista para actualizar sin Vivaldi"
                                        } else {
                                            "🔌 Pendrive USB con ${candidate.file.name} (v${candidate.versionName}) · Puedes reinstalar sin Vivaldi o abrir Archivos del C10"
                                        }
                                        usbInstallBtn?.text = if (isNewer) "📲 Actualizar a v${candidate.versionName}" else "📲 Instalar desde USB"
                                        usbInstallBtn?.backgroundTintList = android.content.res.ColorStateList.valueOf(themeAccent)
                                        usbInstallBtn?.setTextColor(themeOnAccent)
                                        usbTopBtn?.strokeColor = android.content.res.ColorStateList.valueOf(themeAccent)
                                        usbTopBtn?.setTextColor(themeAccent)
                                    } else {
                                        usbBanner?.visibility = View.GONE
                                    }
                                }
                            }
                        }
                        delay(1000L)
                    }
                }
            }
        }
        if (app.settings.autoStartOnBoot || app.settings.autoStartOnScreenOn) {
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java))
        }
    }

    private fun currentTransportConnectingLabel(): String {
        val s = app.settings
        return when {
            s.wifi5gEnabled -> "🔗 Conectando por Wi-Fi 5 GHz"
            s.hotspotModeEnabled -> "🔗 Conectando por Hotspot Móvil"
            s.btCompanionEnabled -> "🔵 Conectando solo por Bluetooth Companion"
            else -> "🔗 Conectando por Cable USB"
        }
    }

    private fun beginConnectingUi(
        view: View,
        targetName: String,
        protocol: String,
        transportLabel: String,
    ) {
        manualConnectingActive = true
        connectingStartElapsedMs = SystemClock.elapsedRealtime()
        connectingTargetName = targetName
        connectingProtocol = protocol
        connectingTransportLabel = transportLabel
        refreshHeroState(view)
        rebuildRecentDevicesRow(view)
    }

    private fun cancelCurrentConnectionAttempt(view: View) {
        manualConnectingActive = false
        carPlayLaunchJob?.cancel()
        carPlayLaunchJob = null
        app.commManager.disconnect()
        refreshHeroState(view)
        rebuildRecentDevicesRow(view)
    }

    private fun isBtDeviceAclConnected(device: BluetoothDevice): Boolean {
        return runCatching {
            val m = device.javaClass.getMethod("isConnected")
            (m.invoke(device) as? Boolean) == true
        }.getOrDefault(false)
    }

    private fun firstConnectedBondedPhone(): Pair<BluetoothDevice, String>? {
        return runCatching {
            val adapter = BluetoothHelper.getBluetoothAdapter(requireContext()) ?: return null
            if (!adapter.isEnabled) return null
            val bonded = adapter.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }
            val connected = bonded.firstOrNull { isBtDeviceAclConnected(it) } ?: return null
            val name = runCatching { connected.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: connected.address
            connected to name
        }.getOrNull()
    }

    private fun tryConnectSystemBluetoothDevice(device: BluetoothDevice) {
        runCatching {
            if (app.settings.btCompanionEnabled) {
                com.headqlink.unit.bridgehost.CompanionBridgeHost.setBluetoothEnabled(requireContext(), true)
                com.headqlink.unit.bridgehost.CompanionBridgeHost.wakeNow()
            }
            val m = device.javaClass.getMethod("connect")
            m.isAccessible = true
            m.invoke(device)
        }
        runCatching {
            com.headqlink.unit.bridgehost.CompanionBridgeHost.pushImmediateSync()
        }
    }

    private fun refreshHeroState(view: View) {
        val pill = view.findViewById<TextView>(R.id.hero_status_pill)
        val badge = view.findViewById<TextView>(R.id.hero_transport_badge)
        val title = view.findViewById<TextView>(R.id.connection_status)
        val sub = view.findViewById<TextView>(R.id.hero_subtitle_text)
        val wifiBtn = view.findViewById<MaterialButton>(R.id.wifi_button)
        val companionBtn = view.findViewById<MaterialButton>(R.id.companion_pair_button)
        val cpBtn = view.findViewById<MaterialButton>(R.id.carplay_button)
        val usbBtn = view.findViewById<MaterialButton>(R.id.usb_button)
        val cancelBtn = view.findViewById<MaterialButton>(R.id.hero_cancel_button)
        val ring = view.findViewById<ConnectionRingView>(R.id.connection_ring_view)

        val s = app.settings
        val accent = com.headqlink.unit.extended.QonectaTheme.accent
        val okGreen = 0xFF3AD29F.toInt()
        pill?.setTextColor(accent)

        val btStatus = com.headqlink.unit.bridgehost.CompanionBridgeHost.status()
        val aclConnectedPair = firstConnectedBondedPhone()
        val wirelessAaEnabled = s.wifi5gEnabled || s.hotspotModeEnabled
        val isPureBtSelected = s.btCompanionEnabled && !wirelessAaEnabled
        val btConnectedPure = isPureBtSelected && (btStatus.isConnected || aclConnectedPair != null)

        if (btConnectedPure && manualConnectingActive && connectingProtocol == "Solo Bluetooth") {
            manualConnectingActive = false
        }

        // Actualizar etiqueta del botón Companion siempre en vivo
        companionBtn?.apply {
            when {
                btStatus.isConnected -> {
                    val via = btStatus.connectedDevices.firstOrNull()?.second ?: "BT"
                    text = "🔵 Companion ($via ✓)"
                    strokeColor = android.content.res.ColorStateList.valueOf(okGreen)
                    setTextColor(okGreen)
                }
                btStatus.pairedCount > 0 -> {
                    text = "🔵 Ajustes Companion"
                    strokeColor = android.content.res.ColorStateList.valueOf(accent)
                    setTextColor(accent)
                }
                else -> {
                    text = "🔑 Vincular Companion"
                    strokeColor = android.content.res.ColorStateList.valueOf(0xFFFFB547.toInt())
                    setTextColor(0xFFFFB547.toInt())
                }
            }
        }

        when {
            app.commManager.isConnected -> {
                val devName = s.autoStartBluetoothDeviceName.takeIf { it.isNotBlank() } ?: "tu móvil"
                pill?.setTextColor(okGreen)
                pill?.text = "●  CONECTADO"
                badge?.text = if (app.commManager.isWirelessSession) {
                    if (s.hotspotModeEnabled) "📲 Enlace Hotspot Wi-Fi activo" else "📡 Enlace Wi-Fi 5 GHz activo"
                } else {
                    "🔌 Enlace Cable USB activo"
                }
                title?.text = "Conectado a $devName"
                sub?.text = "Sesión de proyección activa. Toca «Abrir proyección» para volver a la pantalla completa."
                wifiBtn?.visibility = View.VISIBLE
                wifiBtn?.text = getString(R.string.da_open)
                companionBtn?.visibility = View.VISIBLE
                cpBtn?.visibility = View.VISIBLE
                usbBtn?.visibility = View.GONE
                cancelBtn?.visibility = View.GONE

                ring?.state = ConnectionRingView.State.CONNECTED
                ring?.protocolTitle = "Android Auto"
                ring?.subtitleText = "En proyección"
            }
            btConnectedPure && !manualConnectingActive -> {
                val btDevName = btStatus.connectedDevices.firstOrNull()?.first
                    ?: aclConnectedPair?.second
                    ?: s.autoStartBluetoothDeviceName.takeIf { it.isNotBlank() }
                    ?: "tu móvil"
                pill?.setTextColor(okGreen)
                pill?.text = "●  SOLO BLUETOOTH ACTIVO"
                badge?.text = if (btStatus.isConnected) {
                    "🔵 Companion Bluetooth conectado (Wi-Fi 5 GHz apagado)"
                } else {
                    "🔵 Bluetooth del coche conectado · Wi-Fi 5 GHz apagado"
                }
                title?.text = "Conectado a $btDevName"
                sub?.text = if (btStatus.isConnected) {
                    "Sincronizando viajes, tarifas, telemetría C10, rutas a Google Maps y datos por Bluetooth sin encender Wi-Fi 5 GHz."
                } else {
                    "Bluetooth enlazado con $btDevName sin Wi-Fi 5 GHz. Abre Qonecta Companion en el móvil para sincronizar."
                }
                wifiBtn?.visibility = View.VISIBLE
                wifiBtn?.text = if (btStatus.isConnected) "🔄 Sincronizar Bluetooth" else "🔵 Conectar Companion BT"
                companionBtn?.visibility = View.VISIBLE
                cpBtn?.visibility = View.VISIBLE
                usbBtn?.visibility = if (s.usbModeEnabled) View.VISIBLE else View.GONE
                usbBtn?.text = "Cable USB"
                cancelBtn?.visibility = View.GONE

                ring?.state = ConnectionRingView.State.CONNECTED
                ring?.protocolTitle = "Solo Bluetooth"
                ring?.subtitleText = if (btStatus.isConnected) "Sin Wi-Fi 5G ✓" else "BT conectado"
            }
            manualConnectingActive -> {
                val elapsedSec = ((SystemClock.elapsedRealtime() - connectingStartElapsedMs) / 1000L).toInt().coerceAtLeast(0)
                val timeoutSec = if (connectingProtocol == "Solo Bluetooth") 12 else 35
                if (elapsedSec > timeoutSec) {
                    manualConnectingActive = false
                    refreshHeroState(view)
                    return
                }
                pill?.text = "●  CONECTANDO"
                badge?.text = connectingTransportLabel
                title?.text = "Conectando a $connectingTargetName"
                sub?.text = if (connectingProtocol == "Solo Bluetooth") {
                    "Enlazando solo por Bluetooth con $connectingTargetName (sin activar Wi-Fi 5 GHz)…"
                } else {
                    "Mantén el móvil desbloqueado. Suele tardar unos segundos."
                }
                wifiBtn?.visibility = View.GONE
                companionBtn?.visibility = View.GONE
                cpBtn?.visibility = View.GONE
                usbBtn?.visibility = View.GONE
                cancelBtn?.visibility = View.VISIBLE

                ring?.state = ConnectionRingView.State.CONNECTING
                ring?.elapsedSeconds = elapsedSec
                ring?.protocolTitle = connectingProtocol
                ring?.activeStep = when {
                    elapsedSec < 3 -> 1
                    elapsedSec < 7 -> 2
                    else -> 3
                }
                ring?.subtitleText = when {
                    elapsedSec < 3 -> "Negociando enlace"
                    elapsedSec < 8 -> "Esperando al móvil"
                    else -> "Abriendo sesión"
                }
            }
            else -> {
                val companionDev = btStatus.connectedDevices.firstOrNull()?.first
                if (companionDev != null) {
                    pill?.setTextColor(okGreen)
                    pill?.text = "●  COMPANION SINCRONIZADO"
                    badge?.text = when {
                        s.wifi5gEnabled -> "📡 Wi-Fi 5 GHz listo  ·  🔵 Companion conectado"
                        s.hotspotModeEnabled -> "📲 Hotspot Móvil listo  ·  🔵 Companion conectado"
                        s.usbModeEnabled -> "🔌 Cable USB listo  ·  🔵 Companion conectado"
                        else -> "🔵 Solo Bluetooth Companion conectado"
                    }
                    title?.text = "$companionDev sincronizado"
                    sub?.text = if (wirelessAaEnabled) {
                        "Viajes, tarifas y rutas activos. Toca «Conectar Android Auto» o desmarca Wi-Fi 5 GHz si prefieres solo Bluetooth."
                    } else {
                        "Conectado por Bluetooth limpio (Wi-Fi 5 GHz desmarcado)."
                    }
                } else {
                    pill?.text = "●  LISTO"
                    badge?.text = when {
                        s.wifi5gEnabled -> "📡 Wi-Fi 5 GHz marcado" + if (s.btCompanionEnabled) " + 🔵 BT" else ""
                        s.hotspotModeEnabled -> "📲 Hotspot Móvil marcado" + if (s.btCompanionEnabled) " + 🔵 BT" else ""
                        s.btCompanionEnabled -> "🔵 Solo Bluetooth marcado (Sin Wi-Fi 5 GHz)"
                        s.usbModeEnabled -> "🔌 Solo Cable USB marcado (Sin Wi-Fi)"
                        else -> "⏸ Ninguna opción marcada — elige abajo qué activar"
                    }
                    title?.text = "Conecta tu móvil"
                    sub?.text = when {
                        isPureBtSelected -> "Wi-Fi 5 GHz está desmarcado: al conectar usarás únicamente Bluetooth limpio con Qonecta Companion."
                        wirelessAaEnabled -> "Marca o desmarca abajo las conexiones que quieras usar, o toca tu móvil vinculado."
                        s.usbModeEnabled -> "Conecta el cable USB para proyectar, o marca Bluetooth / Wi-Fi abajo."
                        else -> "Marca al menos una casilla abajo (por ejemplo ☑ Bluetooth o ☑ Wi-Fi 5 GHz)."
                    }
                }
                wifiBtn?.visibility = View.VISIBLE
                wifiBtn?.text = when {
                    wirelessAaEnabled -> "✨ Conectar Android Auto"
                    s.btCompanionEnabled && btStatus.pairedCount == 0 -> "🔑 Vincular Bluetooth (6 cifras)"
                    s.btCompanionEnabled -> "🔵 Conectar solo Bluetooth"
                    s.usbModeEnabled -> "🔌 Conectar por Cable USB"
                    else -> "☑ Elige una opción abajo"
                }
                companionBtn?.visibility = View.VISIBLE
                cpBtn?.visibility = View.VISIBLE
                usbBtn?.visibility = if (s.usbModeEnabled && (wirelessAaEnabled || s.btCompanionEnabled)) View.VISIBLE else View.GONE
                usbBtn?.text = "Cable USB"
                cancelBtn?.visibility = View.GONE

                ring?.state = if (companionDev != null) ConnectionRingView.State.CONNECTED else ConnectionRingView.State.IDLE
                ring?.protocolTitle = when {
                    s.wifi5gEnabled -> "Wi-Fi 5 GHz"
                    s.hotspotModeEnabled -> "Hotspot Móvil"
                    s.btCompanionEnabled -> "Solo Bluetooth"
                    s.usbModeEnabled -> "Cable USB"
                    else -> "En pausa"
                }
                ring?.subtitleText = if (companionDev != null) "Sincronizado ✓" else "Toca para conectar"
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun rebuildRecentDevicesRow(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.recent_devices_container) ?: return
        container.removeAllViews()

        val bonded = runCatching {
            val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
            if (adapter != null && adapter.isEnabled) {
                adapter.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())

        val s = app.settings
        val savedName = s.autoStartBluetoothDeviceName.takeIf { it.isNotBlank() }
        val btStatus = com.headqlink.unit.bridgehost.CompanionBridgeHost.status()
        val wirelessAaEnabled = s.wifi5gEnabled || s.hotspotModeEnabled
        val isPureBt = s.btCompanionEnabled && !wirelessAaEnabled

        if (bonded.isNotEmpty()) {
            bonded.take(3).forEachIndexed { index, dev ->
                val name = runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: dev.address
                val aclConnected = isBtDeviceAclConnected(dev)
                val isTarget = (manualConnectingActive && connectingTargetName == name) ||
                    (!manualConnectingActive && (name == savedName || index == 0))
                val protocolTag = when {
                    name.contains("iPhone", ignoreCase = true) ->
                        if (aclConnected) "● CarPlay · Bluetooth conectado" else "● CarPlay · Toca para conectar"
                    isPureBt && btStatus.isConnected ->
                        "● Solo Bluetooth · Companion conectado ✓"
                    isPureBt && aclConnected ->
                        "● Solo Bluetooth · Enlazado sin Wi-Fi 5G ✓"
                    isPureBt ->
                        "● Solo Bluetooth · Toca para conectar sin 5G"
                    btStatus.isConnected && index == 0 ->
                        "● Companion BT activo · Toca para Android Auto"
                    aclConnected ->
                        "● Bluetooth conectado · Toca para iniciar"
                    else ->
                        "● Vinculado · Toca para conectar"
                }
                container.addView(
                    createRecentDeviceCard(
                        title = name,
                        subtitle = protocolTag,
                        highlighted = isTarget,
                        onClick = { connectToBondedDevice(view, dev, name) },
                    )
                )
            }
        } else {
            val defaultPhoneName = savedName ?: if (isPureBt) "Móvil Android (Solo Bluetooth)" else "Móvil Android"
            container.addView(
                createRecentDeviceCard(
                    title = defaultPhoneName,
                    subtitle = if (isPureBt) "● Solo Bluetooth Companion (sin Wi-Fi 5 GHz)" else "● Android Auto + Bluetooth Companion",
                    highlighted = !manualConnectingActive || connectingProtocol == "Android Auto",
                    onClick = {
                        startPrimaryConnection()
                    },
                )
            )
            container.addView(
                createRecentDeviceCard(
                    title = "iPhone (CarPlay)",
                    subtitle = "● Apple CarPlay · Bluetooth / USB",
                    highlighted = manualConnectingActive && connectingProtocol == "CarPlay",
                    onClick = {
                        view.findViewById<View>(R.id.carplay_button)?.performClick()
                    },
                )
            )
        }

        // Tarjeta adicional para vincular un móvil nuevo o elegir de la lista Bluetooth
        container.addView(
            createRecentDeviceCard(
                title = "Vincular otro móvil",
                subtitle = "● Abrir ajustes Bluetooth del coche",
                highlighted = false,
                onClick = { openBluetoothSettings() },
            )
        )
    }

    private fun createRecentDeviceCard(
        title: String,
        subtitle: String,
        highlighted: Boolean,
        onClick: () -> Unit,
    ): View {
        val ctx = requireContext()
        val accent = com.headqlink.unit.extended.QonectaTheme.accent

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(if (highlighted) 0xFF10282C.toInt() else 0xFF121B26.toInt())
                setStroke(
                    dp(if (highlighted) 3 else 1),
                    if (highlighted) accent else 0xFF243140.toInt(),
                )
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val iconBox = TextView(ctx).apply {
            text = if (title.contains("Vincular", ignoreCase = true)) "＋" else "📱"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(if (highlighted) 0x3300E5B8 else 0xFF1A2636.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                marginEnd = dp(16)
            }
        }
        card.addView(iconBox)

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(
            TextView(ctx).apply {
                text = title
                textSize = 21f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(0xFFEAF2F7.toInt())
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        textCol.addView(
            TextView(ctx).apply {
                text = subtitle
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(if (highlighted) accent else 0xFF93A4B7.toInt())
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        card.addView(textCol)

        return card
    }

    /**
     * Respeta estrictamente las casillas marcadas por el usuario:
     * - Si Wi-Fi 5 GHz y Hotspot están desmarcados y Bluetooth está marcado -> conecta SOLO Bluetooth (cero 5 GHz).
     * - Si Wi-Fi 5 GHz o Hotspot están marcados -> conecta Android Auto inalámbrico.
     * - Si solo Cable USB está marcado -> comprueba el puerto USB.
     */
    private fun startPrimaryConnection() {
        val root = view ?: return
        val s = app.settings
        val wirelessAaEnabled = s.wifi5gEnabled || s.hotspotModeEnabled

        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
        val bonded = runCatching {
            if (adapter != null && adapter.isEnabled) {
                adapter.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())

        val savedName = s.autoStartBluetoothDeviceName
        val preferred = bonded.firstOrNull { (it.name ?: "") == savedName } ?: bonded.firstOrNull()

        if (!wirelessAaEnabled) {
            if (s.btCompanionEnabled) {
                com.headqlink.unit.bridgehost.CompanionBridgeHost.setBluetoothEnabled(requireContext(), true)
                com.headqlink.unit.bridgehost.CompanionBridgeHost.wakeNow()
                val btStatus = com.headqlink.unit.bridgehost.CompanionBridgeHost.status()
                if (btStatus.pairedCount == 0 && !btStatus.isConnected) {
                    com.headqlink.unit.bridgehost.CompanionBridgeHost.startPairing(requireContext())
                    showBluetoothCompanionDialog(root)
                    return
                }
                if (preferred != null) {
                    val devName = preferred.name ?: preferred.address
                    tryConnectSystemBluetoothDevice(preferred)
                    if (btStatus.isConnected) {
                        com.headqlink.unit.bridgehost.CompanionBridgeHost.pushImmediateSync()
                        android.widget.Toast.makeText(
                            requireContext(),
                            "🔵 Sincronización Bluetooth enviada a $devName (Wi-Fi 5 GHz apagado).",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        refreshHeroState(root)
                    } else {
                        beginConnectingUi(
                            view = root,
                            targetName = devName,
                            protocol = "Solo Bluetooth",
                            transportLabel = "🔵 Conectando solo por Bluetooth (sin 5 GHz)",
                        )
                    }
                } else {
                    showBluetoothCompanionDialog(root)
                }
                return
            }

            if (s.usbModeEnabled) {
                beginConnectingUi(
                    view = root,
                    targetName = "móvil por Cable USB",
                    protocol = "Android Auto",
                    transportLabel = "🔗 Conectando por cable",
                )
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_CHECK_USB
                })
                return
            }

            android.widget.Toast.makeText(
                requireContext(),
                "Marca primero una casilla de conexión abajo (por ejemplo ☑ Bluetooth o ☑ Wi-Fi 5 GHz).",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        if (preferred != null) {
            val devName = preferred.name ?: preferred.address
            tryConnectSystemBluetoothDevice(preferred)
            beginConnectingUi(
                view = root,
                targetName = devName,
                protocol = "Android Auto",
                transportLabel = currentTransportConnectingLabel(),
            )
            startWirelessScanOrDevice(preferred, devName)
        } else {
            val target = savedName.takeIf { it.isNotBlank() } ?: "tu móvil Android"
            beginConnectingUi(
                view = root,
                targetName = target,
                protocol = "Android Auto",
                transportLabel = currentTransportConnectingLabel(),
            )
            startWirelessScanOrDevice(null, target)
        }
    }

    private fun connectToBondedDevice(root: View, device: BluetoothDevice, devName: String) {
        app.settings.autoStartBluetoothDeviceName = devName
        tryConnectSystemBluetoothDevice(device)

        if (devName.contains("iPhone", ignoreCase = true)) {
            beginConnectingUi(
                view = root,
                targetName = devName,
                protocol = "CarPlay",
                transportLabel = if (!app.settings.wifi5gEnabled && !app.settings.hotspotModeEnabled) "🔗 Conectando por cable / Bluetooth" else "🔗 Conectando sin cables",
            )
            carPlayLaunchJob?.cancel()
            carPlayLaunchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(2800L)
                if (manualConnectingActive && isAdded) {
                    startActivity(Intent(requireContext(), com.shilapi.xcertplay.LeapCarPlayActivity::class.java))
                }
            }
            return
        }

        val s = app.settings
        val wirelessAaEnabled = s.wifi5gEnabled || s.hotspotModeEnabled

        // Si Wi-Fi 5 GHz y Hotspot están desmarcados, NUNCA encendemos 5 GHz: conectamos solo Bluetooth limpio
        if (!wirelessAaEnabled) {
            if (!s.btCompanionEnabled) {
                s.btCompanionEnabled = true
                com.headqlink.unit.bridgehost.CompanionBridgeHost.setBluetoothEnabled(requireContext(), true)
            }
            val btStatus = com.headqlink.unit.bridgehost.CompanionBridgeHost.status()
            if (btStatus.pairedCount == 0 && !btStatus.isConnected) {
                com.headqlink.unit.bridgehost.CompanionBridgeHost.startPairing(requireContext())
                updateConnectionModeButtons(root)
                refreshHeroState(root)
                rebuildRecentDevicesRow(root)
                showBluetoothCompanionDialog(root)
                return
            }
            val btConnected = btStatus.isConnected || isBtDeviceAclConnected(device)
            if (btConnected) {
                manualConnectingActive = false
                com.headqlink.unit.bridgehost.CompanionBridgeHost.pushImmediateSync()
                updateConnectionModeButtons(root)
                refreshHeroState(root)
                rebuildRecentDevicesRow(root)
                android.widget.Toast.makeText(
                    requireContext(),
                    "🔵 Conectado solo por Bluetooth a $devName (sin Wi-Fi 5 GHz).",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } else {
                beginConnectingUi(
                    view = root,
                    targetName = devName,
                    protocol = "Solo Bluetooth",
                    transportLabel = "🔵 Conectando solo por Bluetooth (sin 5 GHz)",
                )
            }
            return
        }

        beginConnectingUi(
            view = root,
            targetName = devName,
            protocol = "Android Auto",
            transportLabel = currentTransportConnectingLabel(),
        )
        startWirelessScanOrDevice(device, devName)
    }

    private fun startWirelessScanOrDevice(device: BluetoothDevice?, devName: String) {
        val s = app.settings
        s.autoStartBluetoothDeviceName = devName
        if (!s.wifi5gEnabled && !s.hotspotModeEnabled) return
        ensureCarWifiEnabled()
        com.andrerinas.openheadunit.cluster.ClusterMapController.notifyConnecting(
            requireContext(),
            devName,
            "Android Auto Inalámbrico",
            com.andrerinas.openheadunit.cluster.ClusterMapController.Mode.ANDROID_AUTO,
        )
        if (s.hotspotModeEnabled || device == null) {
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_START_WIRELESS_SCAN
            })
        } else {
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_NATIVE_AA_POKE
                putExtra(AapService.EXTRA_MAC, device.address)
            })
        }
        view?.let { updateConnectionModeButtons(it) }
    }

    private fun cycleExtendedMode(view: View) {
        val s = app.settings
        when {
            !s.extendedMode -> {
                s.extendedMode = true
                s.extendedPanelPercent = 30
            }
            s.extendedPanelPercent >= 28 -> {
                s.extendedMode = true
                s.extendedPanelPercent = 22
            }
            else -> {
                s.extendedMode = false
            }
        }
        updateExtendedModeButton(view)
    }

    private fun updateExtendedModeButton(view: View) {
        val btn = view.findViewById<MaterialButton>(R.id.extended_mode_toggle_button) ?: return
        val s = app.settings
        val themeAccent = com.headqlink.unit.extended.QonectaTheme.accent
        if (s.extendedMode) {
            val label = if (s.extendedPanelPercent >= 28) "Extendido" else "Extendido (${s.extendedPanelPercent}%)"
            btn.text = label
            btn.setTextColor(themeAccent)
            btn.strokeColor = android.content.res.ColorStateList.valueOf(themeAccent)
        } else {
            btn.text = "Pantalla Completa"
            btn.setTextColor(0xFF93A4B7.toInt())
            btn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF243140.toInt())
        }
    }

    private fun ensureCarWifiEnabled(): Boolean {
        val wm = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return false
        if (wm.isWifiEnabled) return true
        @Suppress("DEPRECATION")
        return runCatching { wm.setWifiEnabled(true) && wm.isWifiEnabled }.getOrDefault(false)
    }

    private fun updateConnectionModeButtons(view: View) {
        val s = app.settings
        val btOn = s.btCompanionEnabled
        val wifi5gOn = s.wifi5gEnabled
        val hotspotOn = s.hotspotModeEnabled
        val usbOn = s.usbModeEnabled

        val wm = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val wifiEnabled = wm?.isWifiEnabled == true
        val wifiBanner = view.findViewById<View>(R.id.wifi_conflict_banner)
        val needsWifi = wifi5gOn || hotspotOn
        wifiBanner?.visibility = if (needsWifi && !wifiEnabled) View.VISIBLE else View.GONE

        val themeAccent = com.headqlink.unit.extended.QonectaTheme.accent
        val themeOnAccent = com.headqlink.unit.extended.QonectaTheme.onAccent
        val surfaceTop = 0xFF17202C.toInt()
        val textDim = 0xFFAEC0D2.toInt()
        val outline = 0xFF243140.toInt()
        val okGreen = 0xFF3AD29F.toInt()

        fun styleCheckBtn(btn: MaterialButton?, checked: Boolean, activeColor: Int = themeAccent, activeText: Int = themeOnAccent) {
            btn ?: return
            if (checked) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(activeColor)
                btn.setTextColor(activeText)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(activeColor)
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(surfaceTop)
                btn.setTextColor(textDim)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(outline)
            }
        }

        val btStatus = com.headqlink.unit.bridgehost.CompanionBridgeHost.status()
        val btConnected = btStatus.isConnected
        val btDataOn = com.headqlink.unit.bridgehost.CompanionBridgeHost.useBluetoothDataBridge

        val btBtn = view.findViewById<MaterialButton>(R.id.mode_btn_bt_companion)
        if (btOn) {
            if (btConnected) {
                val devName = btStatus.connectedDevices.firstOrNull()?.first?.take(11) ?: "Móvil"
                btBtn?.text = "☑ 🔵 BT: $devName\nDatos y Viajes activo"
                styleCheckBtn(btBtn, true, activeColor = okGreen, activeText = 0xFF06131A.toInt())
            } else {
                btBtn?.text = if (btStatus.pairedCount > 0) "☑ 🔵 Bluetooth\nEn espera de móvil" else "☑ 🔵 Bluetooth\nToca «Vincular» arriba"
                styleCheckBtn(btBtn, true)
            }
        } else {
            btBtn?.text = "☐ 🔵 Bluetooth\nDesactivado"
            styleCheckBtn(btBtn, false)
        }

        val wifiBtn = view.findViewById<MaterialButton>(R.id.mode_btn_wifi_direct)
        wifiBtn?.text = if (wifi5gOn) "☑ 📡 Wi-Fi 5 GHz\nAndroid Auto sin cables" else "☐ 📡 Wi-Fi 5 GHz\nApagado (sin 5 GHz)"
        styleCheckBtn(wifiBtn, wifi5gOn)

        val hotspotBtn = view.findViewById<MaterialButton>(R.id.mode_btn_hotspot)
        hotspotBtn?.text = if (hotspotOn) "☑ 📲 Hotspot Móvil\nInternet al coche + AA" else "☐ 📲 Hotspot Móvil\nDesactivado"
        styleCheckBtn(hotspotBtn, hotspotOn)

        val usbBtn = view.findViewById<MaterialButton>(R.id.mode_btn_usb)
        usbBtn?.text = if (usbOn) "☑ 🔌 Cable USB\nAA / CarPlay + Carga" else "☐ 🔌 Cable USB\nSolo carga batería"
        styleCheckBtn(usbBtn, usbOn)

        val activeList = mutableListOf<String>()
        if (btOn) {
            activeList.add(
                if (btConnected) "🔵 Bluetooth Companion conectado (${if (btDataOn) "Datos BT ON" else "Rutas"})"
                else "🔵 Bluetooth Companion listo"
            )
        }
        if (wifi5gOn) activeList.add("📡 Wi-Fi 5 GHz (Android Auto)")
        if (hotspotOn) activeList.add("📲 Hotspot Móvil")
        if (usbOn) activeList.add("🔌 Cable USB")

        val expView = view.findViewById<TextView>(R.id.mode_active_explanation)
        expView?.text = when {
            btOn && !wifi5gOn && !hotspotOn ->
                "✅ Modo Solo Bluetooth limpio activo: Wi-Fi 5 GHz está totalmente apagado (${activeList.joinToString(" + ")})."
            activeList.isEmpty() ->
                "⏸ Todas las opciones desmarcadas: el coche no iniciará ninguna conexión automática hasta que marques una casilla."
            else ->
                "Activas ahora: ${activeList.joinToString("  ·  ")}. (Toca cualquier casilla para marcar o desmarcar)."
        }
    }

    private fun showUsbSwitchDialog(deviceName: String) {
        activeDialog?.dismiss()
        isBtCompanionDialogOpen = false
        val ctx = requireContext()
        val accent = com.headqlink.unit.extended.QonectaTheme.accent
        val onAccent = com.headqlink.unit.extended.QonectaTheme.onAccent

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(26), dp(32), dp(24))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(0xFF101924.toInt())
                setStroke(dp(2), accent)
            }
        }

        root.addView(TextView(ctx).apply {
            text = "🔌 Cable USB detectado ($deviceName)"
            textSize = 27f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xFFEAF2F7.toInt())
            setPadding(0, 0, 0, dp(10))
        })

        root.addView(TextView(ctx).apply {
            text = "Para usar la conexión por cable sin interferencias, ¿quieres desmarcar Wi-Fi y cambiar directamente a Cable USB?"
            textSize = 20.5f
            setTextColor(0xFFBFD0E0.toInt())
            setLineSpacing(0f, 1.18f)
            setPadding(0, 0, 0, dp(22))
        })

        val dialog = MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
            .setView(root)
            .setOnCancelListener {
                AapService.usbSwitchPrompt.value = null
            }
            .create()

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        val keepWifiBtn = TextView(ctx).apply {
            text = "📡 Seguir por Wi-Fi"
            textSize = 19.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xFFEAF2F7.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(0xFF1A2636.toInt())
                setStroke(dp(2), 0xFF2D3F54.toInt())
            }
            setOnClickListener {
                AapService.usbSwitchPrompt.value = null
                dialog.dismiss()
            }
        }
        btnRow.addView(keepWifiBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(62)).apply {
            marginEnd = dp(14)
        })

        val switchUsbBtn = TextView(ctx).apply {
            text = "🔌 Apagar Wi-Fi y usar Cable USB"
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(onAccent)
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(14), dp(26), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(accent)
            }
            setOnClickListener {
                AapService.usbSwitchPrompt.value = null
                dialog.dismiss()
                app.settings.wifi5gEnabled = false
                app.settings.hotspotModeEnabled = false
                app.settings.usbModeEnabled = true
                view?.let {
                    updateConnectionModeButtons(it)
                    beginConnectingUi(
                        view = it,
                        targetName = deviceName,
                        protocol = "Android Auto",
                        transportLabel = "🔗 Conectando por cable",
                    )
                }
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_SWITCH_TO_USB
                })
            }
        }
        btnRow.addView(switchUsbBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(62)))

        root.addView(btnRow)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.78f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        activeDialog = dialog
    }

    private fun showBluetoothCompanionDialog(rootView: View) {
        activeDialog?.dismiss()
        isBtCompanionDialogOpen = true
        val ctx = requireContext()
        if (!app.settings.btCompanionEnabled) {
            app.settings.btCompanionEnabled = true
        }
        com.headqlink.unit.bridgehost.CompanionBridgeHost.setBluetoothEnabled(ctx, true)
        val st = com.headqlink.unit.bridgehost.CompanionBridgeHost.status()
        lastCompanionConnectedState = st.isConnected
        val dataEnabled = com.headqlink.unit.bridgehost.CompanionBridgeHost.useBluetoothDataBridge
        val accent = com.headqlink.unit.extended.QonectaTheme.accent
        val onAccent = com.headqlink.unit.extended.QonectaTheme.onAccent

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(26), dp(32), dp(24))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(0xFF101924.toInt())
                setStroke(dp(2), accent)
            }
        }

        root.addView(TextView(ctx).apply {
            text = "🔵 Qonecta Companion · Enlace y Código Bluetooth"
            textSize = 27f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xFFEAF2F7.toInt())
            setPadding(0, 0, 0, dp(10))
        })

        val aclPair = firstConnectedBondedPhone()
        val devSummary = when {
            st.isConnected -> "● ¡COMPANION SINCRONIZADO!: " + st.connectedDevices.joinToString { "${it.first} (${it.second})" }
            aclPair != null -> "● BLUETOOTH DEL COCHE CONECTADO: ${aclPair.second} (Abre Companion en tu móvil)"
            st.pairedCount > 0 -> "● Listo para reconectar automáticamente (${st.pairedCount} móvil vinculado)"
            else -> "● Pulsa «Generar código (6 cifras)» e introdúcelo en Qonecta Companion (o en su Modo Tutorial)"
        }
        val anyBtOk = st.isConnected || aclPair != null

        root.addView(TextView(ctx).apply {
            text = devSummary
            textSize = 20.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (anyBtOk) 0xFF3AD29F.toInt() else accent)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(0xFF162434.toInt())
                setStroke(dp(1), if (anyBtOk) 0xFF3AD29F.toInt() else accent)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        st.pairingCode?.let { pin ->
            val pinBox = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(12))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(0xFF1A2918.toInt())
                    setStroke(dp(2), 0xFFFFB547.toInt())
                }
            }
            pinBox.addView(TextView(ctx).apply {
                text = "🔑 CÓDIGO DE EMPAREJAMIENTO (6 CIFRAS):"
                textSize = 19f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(0xFFFFB547.toInt())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            pinBox.addView(TextView(ctx).apply {
                text = pin.chunked(3).joinToString(" ")
                textSize = 38f
                typeface = Typeface.create("monospace", Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            root.addView(pinBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            })
        }

        val wifiOffNote = if (!app.settings.wifi5gEnabled && !app.settings.hotspotModeEnabled) {
            "✅ Wi-Fi 5 GHz desmarcado: este enlace funciona 100% por Bluetooth limpio sin encender redes Wi-Fi."
        } else {
            "💡 Consejo: si quieres usar SOLO Bluetooth sin Wi-Fi 5 GHz, desmarca la casilla «📡 Wi-Fi 5 GHz» en la pantalla principal."
        }

        val bullets = "$wifiOffNote\n" +
            "• Servidor de Datos Bluetooth (Planificador, Mapas, Tiempo y OTA): ${if (dataEnabled) "ACTIVO ✓" else "DESACTIVADO"}\n" +
            "• Sincronización en tiempo real de Viajes, Privacidad y Tarifas con el móvil: ACTIVO ✓\n" +
            "• Envío de rutas con paradas de carga a Google Maps del móvil: ACTIVO ✓"

        root.addView(TextView(ctx).apply {
            text = bullets
            textSize = 18f
            setTextColor(0xFFD2DFEC.toInt())
            setLineSpacing(0f, 1.22f)
            setPadding(0, 0, 0, dp(20))
        })

        val dialog = MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
            .setView(root)
            .setOnDismissListener { isBtCompanionDialogOpen = false }
            .create()

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val genPinBtn = TextView(ctx).apply {
            text = "🔑 Generar código (6 cifras)"
            textSize = 19f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xFFFFB547.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(14), dp(22), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(0xFF1F271B.toInt())
                setStroke(dp(2), 0xFFFFB547.toInt())
            }
            setOnClickListener {
                dialog.dismiss()
                com.headqlink.unit.bridgehost.CompanionBridgeHost.startPairing(ctx)
                showBluetoothCompanionDialog(rootView)
            }
        }
        btnRow.addView(genPinBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(62)).apply {
            marginEnd = dp(10)
        })

        val reconnectBtBtn = TextView(ctx).apply {
            text = "🔄 Reconectar BT"
            textSize = 19f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(accent)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(0xFF14242F.toInt())
                setStroke(dp(2), accent)
            }
            setOnClickListener {
                val adapter = BluetoothHelper.getBluetoothAdapter(ctx)
                val bonded = runCatching {
                    if (adapter != null && adapter.isEnabled) adapter.bondedDevices.orEmpty() else emptySet()
                }.getOrDefault(emptySet())
                bonded.forEach { dev -> tryConnectSystemBluetoothDevice(dev) }
                com.headqlink.unit.bridgehost.CompanionBridgeHost.wakeNow()
                com.headqlink.unit.bridgehost.CompanionBridgeHost.pushImmediateSync()
                updateConnectionModeButtons(rootView)
                refreshHeroState(rootView)
                rebuildRecentDevicesRow(rootView)
                dialog.dismiss()
                showBluetoothCompanionDialog(rootView)
            }
        }
        btnRow.addView(reconnectBtBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(62)))

        btnRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))

        val closeBtn = TextView(ctx).apply {
            text = "Cerrar"
            textSize = 19f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xFFEAF2F7.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(0xFF1A2636.toInt())
                setStroke(dp(2), 0xFF2D3F54.toInt())
            }
            setOnClickListener { dialog.dismiss() }
        }
        btnRow.addView(closeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(62)).apply {
            marginEnd = dp(12)
        })

        val toggleDataBtn = TextView(ctx).apply {
            text = if (dataEnabled) "Desactivar Datos BT" else "Activar Datos BT"
            textSize = 19.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(onAccent)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(accent)
            }
            setOnClickListener {
                com.headqlink.unit.bridgehost.CompanionBridgeHost.useBluetoothDataBridge = !dataEnabled
                updateConnectionModeButtons(rootView)
                dialog.dismiss()
            }
        }
        btnRow.addView(toggleDataBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(62)))

        root.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.84f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        activeDialog = dialog
    }

    /**
     * Diálogo de apoyo al proyecto (Ko-fi + PayPal con códigos QR escaneables desde el móvil),
     * idéntico en espíritu al de Qonecta+ y accesible desde el botón superior «☕ Apoyar».
     */
    private fun showSupportCreditsDialog() {
        activeDialog?.dismiss()
        isBtCompanionDialogOpen = false
        val ctx = requireContext()
        val accent = com.headqlink.unit.extended.QonectaTheme.accent
        val kofiUrl = "https://ko-fi.com/charlysev"
        val paypalUrl = "https://www.paypal.me/CharlysEV"

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(24), dp(30), dp(22))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(0xFF101924.toInt())
                setStroke(dp(2), 0xFFFFB547.toInt())
            }
        }

        root.addView(TextView(ctx).apply {
            text = "☕ Apoyar el proyecto · Qonecta Unit & Qonecta+"
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xFFFFB547.toInt())
        })

        root.addView(TextView(ctx).apply {
            text = "Desarrollado de forma independiente por CharlysEV para la comunidad Leapmotor C10.\n" +
                "Si Qonecta Unit y Qonecta Companion mejoran tus viajes diarios, puedes invitar a un café escaneando con tu móvil cualquiera de estos dos códigos QR (o directamente desde Qonecta Companion en el teléfono):"
            textSize = 18f
            setTextColor(0xFFD2DFEC.toInt())
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(8), 0, dp(18))
        })

        val cardsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun buildQrCard(titleText: String, subText: String, url: String, badgeColor: Int): View {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(20).toFloat()
                    setColor(0xFF152231.toInt())
                    setStroke(dp(2), badgeColor)
                }
            }
            card.addView(TextView(ctx).apply {
                text = titleText
                textSize = 21f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(badgeColor)
                gravity = Gravity.CENTER
            })
            val qrBmp = runCatching {
                com.andrerinas.openheadunit.utils.QrCodeGenerator.generateQrCode(url, dp(175))
            }.getOrNull()
            if (qrBmp != null) {
                card.addView(android.widget.ImageView(ctx).apply {
                    setImageBitmap(qrBmp)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.WHITE)
                    }
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }, LinearLayout.LayoutParams(dp(185), dp(185)).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(10)
                })
            }
            card.addView(TextView(ctx).apply {
                text = subText
                textSize = 16.5f
                typeface = Typeface.create("monospace", Typeface.BOLD)
                setTextColor(0xFFEAF2F7.toInt())
                gravity = Gravity.CENTER
            })
            return card
        }

        cardsRow.addView(
            buildQrCard("☕ Invitar a un café en Ko-fi", "ko-fi.com/charlysev", kofiUrl, 0xFFFFB547.toInt()),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(14) }
        )
        cardsRow.addView(
            buildQrCard("💙 Donar por PayPal", "paypal.me/CharlysEV", paypalUrl, accent),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(cardsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dialog = MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
            .setView(root)
            .create()

        val footerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        footerRow.addView(TextView(ctx).apply {
            text = "❤️ ¡Muchísimas gracias por apoyar el desarrollo libre para el Leapmotor C10! — github.com/CharlysEV/qonecta"
            textSize = 16.5f
            setTextColor(0xFF93A4B7.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        footerRow.addView(TextView(ctx).apply {
            text = "Cerrar"
            textSize = 19f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xFFEAF2F7.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(12), dp(26), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(0xFF1A2636.toInt())
                setStroke(dp(2), 0xFF2D3F54.toInt())
            }
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)))

        root.addView(footerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.80f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        activeDialog = dialog
    }

    private fun openProjection() {
        startActivity(AapProjectionActivity.intent(requireContext()).apply {
            putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
        })
    }

    private fun showBluetoothHelp(message: Int) {
        activeDialog?.dismiss()
        isBtCompanionDialogOpen = false
        activeDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.da_pair).setMessage(message)
            .setPositiveButton(R.string.da_system_settings) { _, _ -> openBluetoothSettings() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun openBluetoothSettings() {
        try { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
        catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }

    override fun onPause() {
        activeDialog?.dismiss()
        activeDialog = null
        isBtCompanionDialogOpen = false
        super.onPause()
    }

    override fun onDestroyView() {
        companionStatusListener?.let { com.headqlink.unit.bridgehost.CompanionBridgeHost.removeListener(it) }
        companionStatusListener = null
        super.onDestroyView()
    }

    companion object {
        var forceSelfModeLaunch = false
        fun resetAutoStart() { forceSelfModeLaunch = false }
    }
}
