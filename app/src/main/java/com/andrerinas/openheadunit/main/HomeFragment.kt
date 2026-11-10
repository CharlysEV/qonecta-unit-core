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
            view?.let { rebuildRecentDevicesRow(it) }
            startPrimaryWirelessConnection()
        } else {
            showBluetoothHelp(R.string.bt_permission_denied)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        LeapVehicleDataSource.start(requireContext().applicationContext)

        view.findViewById<View>(R.id.wifi_button)?.setOnClickListener {
            if (app.commManager.isConnected) {
                openProjection()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                startPrimaryWirelessConnection()
            }
        }

        view.findViewById<View>(R.id.connection_ring_view)?.setOnClickListener {
            if (app.commManager.isConnected) {
                openProjection()
            } else if (!manualConnectingActive) {
                startPrimaryWirelessConnection()
            }
        }

        view.findViewById<View>(R.id.hero_cancel_button)?.setOnClickListener {
            cancelCurrentConnectionAttempt(view)
        }

        view.findViewById<View>(R.id.usb_button)?.setOnClickListener {
            if (app.settings.wifiConnectionMode == 4 && !app.commManager.isConnected) {
                showBluetoothCompanionDialog(view)
                return@setOnClickListener
            }
            if (app.commManager.isConnected && !app.commManager.isWirelessSession) {
                openProjection()
            } else if (app.commManager.isConnected && app.commManager.isWirelessSession) {
                showUsbSwitchDialog("Dispositivo USB")
            } else {
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
                    app.settings.wifiConnectionMode = 0
                    updateConnectionModeButtons(view)
                    beginConnectingUi(
                        view = view,
                        targetName = "móvil por Cable USB",
                        protocol = "Android Auto",
                        transportLabel = "🔗 Conectando por cable",
                    )
                    ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_SWITCH_TO_USB
                    })
                }
            }
        }

        view.findViewById<View>(R.id.carplay_button)?.setOnClickListener {
            beginConnectingUi(
                view = view,
                targetName = "iPhone (CarPlay)",
                protocol = "CarPlay",
                transportLabel = if (app.settings.wifiConnectionMode == 0) "🔗 Conectando por cable" else "🔗 Conectando sin cables",
            )
            carPlayLaunchJob?.cancel()
            carPlayLaunchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(2800L)
                if (manualConnectingActive && isAdded) {
                    startActivity(Intent(requireContext(), com.shilapi.xcertplay.LeapCarPlayActivity::class.java))
                }
            }
        }

        view.findViewById<View>(R.id.mode_btn_wifi_direct)?.setOnClickListener {
            app.settings.wifiConnectionMode = 3
            app.settings.nativeApTransport = 0
            if (!WirelessScopePolicy.allowsWireless(app.settings.connectionModes)) {
                app.settings.connectionModes = app.settings.connectionModes + Settings.ConnectionMode.WIFI
            }
            ensureCarWifiEnabled()
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_START_WIRELESS
            })
            updateConnectionModeButtons(view)
            rebuildRecentDevicesRow(view)
        }

        view.findViewById<View>(R.id.mode_btn_hotspot)?.setOnClickListener {
            app.settings.wifiConnectionMode = 1
            if (!WirelessScopePolicy.allowsWireless(app.settings.connectionModes)) {
                app.settings.connectionModes = app.settings.connectionModes + Settings.ConnectionMode.WIFI
            }
            ensureCarWifiEnabled()
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_START_WIRELESS_SCAN
            })
            updateConnectionModeButtons(view)
            rebuildRecentDevicesRow(view)
        }

        view.findViewById<View>(R.id.mode_btn_usb)?.setOnClickListener {
            if (app.commManager.isConnected && app.commManager.isWirelessSession) {
                showUsbSwitchDialog("Modo Cable USB")
            } else {
                app.settings.wifiConnectionMode = 0
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_SWITCH_TO_USB
                })
                updateConnectionModeButtons(view)
                rebuildRecentDevicesRow(view)
            }
        }

        view.findViewById<View>(R.id.mode_btn_bt_companion)?.setOnClickListener {
            app.settings.wifiConnectionMode = 4
            com.headqlink.unit.bridgehost.CompanionBridgeHost.start(requireContext())
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_SWITCH_TO_USB
            })
            updateConnectionModeButtons(view)
            refreshHeroState(view)
            rebuildRecentDevicesRow(view)
            showBluetoothCompanionDialog(view)
        }

        view.findViewById<View>(R.id.extended_mode_toggle_button)?.setOnClickListener {
            cycleExtendedMode(view)
        }

        view.findViewById<View>(R.id.extended_hub_button)?.setOnClickListener {
            activeDialog?.dismiss()
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
            "C10 EDITION · v${com.andrerinas.openheadunit.BuildConfig.VERSION_NAME}"

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
                                "LEAPMOTOR C10 EN VIVO  ·  BATERÍA ${snap.socPct.roundToInt()}% (${snap.effectiveRangeKm.roundToInt()} KM)  ·  MODO ${snap.driveModeLabel().uppercase()}"
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
        return when (app.settings.wifiConnectionMode) {
            0 -> "🔗 Conectando por cable"
            4 -> "🔵 Modo 4 · Solo Bluetooth Companion"
            1, 2 -> "🔗 Conectando por Hotspot"
            else -> "🔗 Conectando por Wi-Fi 5 GHz"
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
            com.headqlink.unit.bridgehost.CompanionBridgeHost.start(requireContext())
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
        val cpBtn = view.findViewById<MaterialButton>(R.id.carplay_button)
        val usbBtn = view.findViewById<MaterialButton>(R.id.usb_button)
        val cancelBtn = view.findViewById<MaterialButton>(R.id.hero_cancel_button)
        val ring = view.findViewById<ConnectionRingView>(R.id.connection_ring_view)

        val accent = com.headqlink.unit.extended.QonectaTheme.accent
        val okGreen = 0xFF3AD29F.toInt()
        pill?.setTextColor(accent)

        val btStatus = com.headqlink.unit.bridgehost.CompanionBridgeHost.status()
        val aclConnectedPair = firstConnectedBondedPhone()
        val isBtOnlyMode = app.settings.wifiConnectionMode == 4
        val btConnectedInMode4 = isBtOnlyMode && (btStatus.isConnected || aclConnectedPair != null)

        if (btConnectedInMode4 && manualConnectingActive && connectingProtocol == "Solo Bluetooth") {
            manualConnectingActive = false
        }

        when {
            app.commManager.isConnected -> {
                val devName = app.settings.autoStartBluetoothDeviceName?.takeIf { it.isNotBlank() } ?: "tu móvil"
                pill?.setTextColor(okGreen)
                pill?.text = "●  CONECTADO"
                badge?.text = if (app.commManager.isWirelessSession) "🔗 Enlace 5 GHz activo" else "🔗 Enlace USB activo"
                title?.text = "Conectado a $devName"
                sub?.text = "Sesión de proyección activa. Toca «Abrir proyección» para volver a la pantalla completa."
                wifiBtn?.visibility = View.VISIBLE
                wifiBtn?.text = getString(R.string.da_open)
                cpBtn?.visibility = View.VISIBLE
                usbBtn?.visibility = View.GONE
                cancelBtn?.visibility = View.GONE

                ring?.state = ConnectionRingView.State.CONNECTED
                ring?.protocolTitle = "Android Auto"
                ring?.subtitleText = "En proyección"
            }
            btConnectedInMode4 && !manualConnectingActive -> {
                val btDevName = btStatus.connectedDevices.firstOrNull()?.first
                    ?: aclConnectedPair?.second
                    ?: app.settings.autoStartBluetoothDeviceName?.takeIf { it.isNotBlank() }
                    ?: "tu móvil"
                pill?.setTextColor(okGreen)
                pill?.text = "●  CONECTADO POR BLUETOOTH"
                badge?.text = if (btStatus.isConnected) {
                    "🔵 Modo 4 · Companion Bluetooth Activo (Sin Wi-Fi)"
                } else {
                    "🔵 Modo 4 · Bluetooth del coche conectado"
                }
                title?.text = "Conectado a $btDevName"
                sub?.text = if (btStatus.isConnected) {
                    "Sincronizando viajes, tarifas, telemetría C10, rutas a Google Maps y datos por Bluetooth. Toca «Proyectar Android Auto» si quieres pantalla completa."
                } else {
                    "Enlace Bluetooth con $btDevName activo. Abre Qonecta Unit Companion en el móvil para sincronizar viajes y tarifas."
                }
                wifiBtn?.visibility = View.VISIBLE
                wifiBtn?.text = "✨ Proyectar Android Auto (5 GHz)"
                cpBtn?.visibility = View.VISIBLE
                usbBtn?.visibility = View.VISIBLE
                usbBtn?.text = "🔵 Ajustes Bluetooth"
                cancelBtn?.visibility = View.GONE

                ring?.state = ConnectionRingView.State.CONNECTED
                ring?.protocolTitle = "Solo Bluetooth"
                ring?.subtitleText = if (btStatus.isConnected) "Companion activo" else "BT conectado"
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
                    "Enlazando Bluetooth del coche y Qonecta Companion con $connectingTargetName…"
                } else {
                    "Mantén el móvil desbloqueado. Suele tardar unos segundos."
                }
                wifiBtn?.visibility = View.GONE
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
                    pill?.text = "●  BLUETOOTH COMPANION ACTIVO"
                    badge?.text = when (app.settings.wifiConnectionMode) {
                        0 -> "🔌 Modo 3 · Cable USB  ·  🔵 Companion BT conectado"
                        1, 2 -> "📲 Modo 2 · Hotspot Móvil  ·  🔵 Companion BT conectado"
                        else -> "📡 Modo 1 · Wi-Fi 5 GHz  ·  🔵 Companion BT conectado"
                    }
                    title?.text = "$companionDev listo (BT)"
                    sub?.text = "Viajes, tarifas y rutas conectados por Bluetooth. Toca «Conectar sin cables» o tu móvil abajo para iniciar Android Auto."
                } else {
                    pill?.text = "●  LISTO"
                    badge?.text = when (app.settings.wifiConnectionMode) {
                        0 -> "🔌 Modo 3 · Proyección por Cable USB"
                        4 -> "🔵 Modo 4 · Solo Bluetooth Companion"
                        1, 2 -> "📲 Modo 2 · Hotspot del Móvil + Android Auto"
                        else -> "📡 Modo 1 · Android Auto Inalámbrico 5 GHz"
                    }
                    title?.text = "Conecta tu móvil"
                    sub?.text = "Elige el modo de conexión abajo o toca directamente tu teléfono vinculado para iniciar."
                }
                wifiBtn?.visibility = View.VISIBLE
                wifiBtn?.text = "✨ Conectar sin cables"
                cpBtn?.visibility = View.VISIBLE
                usbBtn?.visibility = View.VISIBLE
                usbBtn?.text = if (isBtOnlyMode) "🔵 Ajustes Bluetooth" else "🔌 Conectar por USB"
                cancelBtn?.visibility = View.GONE

                ring?.state = if (companionDev != null) ConnectionRingView.State.CONNECTED else ConnectionRingView.State.IDLE
                ring?.protocolTitle = when (app.settings.wifiConnectionMode) {
                    0 -> "Cable USB"
                    4 -> "Solo Bluetooth"
                    1, 2 -> "Hotspot Móvil"
                    else -> "Wi-Fi 5 GHz"
                }
                ring?.subtitleText = if (companionDev != null) "BT activo · Toca AA" else "Toca para conectar"
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

        val savedName = app.settings.autoStartBluetoothDeviceName?.takeIf { it.isNotBlank() }
        val btStatus = com.headqlink.unit.bridgehost.CompanionBridgeHost.status()
        val isBtOnly = app.settings.wifiConnectionMode == 4

        if (bonded.isNotEmpty()) {
            bonded.take(3).forEachIndexed { index, dev ->
                val name = runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: dev.address
                val aclConnected = isBtDeviceAclConnected(dev)
                val isTarget = (manualConnectingActive && connectingTargetName == name) ||
                    (!manualConnectingActive && (name == savedName || index == 0))
                val protocolTag = when {
                    name.contains("iPhone", ignoreCase = true) ->
                        if (aclConnected) "● CarPlay · Bluetooth conectado" else "● CarPlay · Toca para conectar"
                    isBtOnly && (btStatus.isConnected || aclConnected) ->
                        "● Conectado por Solo Bluetooth ✓"
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
            val defaultPhoneName = savedName ?: "Móvil Android (Sin cables)"
            container.addView(
                createRecentDeviceCard(
                    title = defaultPhoneName,
                    subtitle = "● Android Auto · Wi-Fi 5 GHz + Bluetooth",
                    highlighted = !manualConnectingActive || connectingProtocol == "Android Auto",
                    onClick = {
                        beginConnectingUi(
                            view = view,
                            targetName = defaultPhoneName,
                            protocol = "Android Auto",
                            transportLabel = currentTransportConnectingLabel(),
                        )
                        startWirelessScanOrDevice(null, defaultPhoneName)
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

    private fun startPrimaryWirelessConnection() {
        val root = view ?: return
        // Al pulsar "Conectar sin cables" o "Proyectar Android Auto", salimos siempre de Modo USB (0) o Solo BT (4) a Wi-Fi 5 GHz (3)
        if (app.settings.wifiConnectionMode == 0 || app.settings.wifiConnectionMode == 4) {
            app.settings.wifiConnectionMode = 3
        }
        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
        val bonded = runCatching {
            if (adapter != null && adapter.isEnabled) {
                adapter.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())

        val savedName = app.settings.autoStartBluetoothDeviceName
        val preferred = bonded.firstOrNull { (it.name ?: "") == savedName } ?: bonded.firstOrNull()

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
            val target = savedName?.takeIf { it.isNotBlank() } ?: "tu móvil Android"
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
                transportLabel = if (app.settings.wifiConnectionMode == 0) "🔗 Conectando por cable" else "🔗 Conectando sin cables",
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

        // Si el usuario está en Modo 4 (Solo Bluetooth), conectamos el enlace Bluetooth sin forzar Wi-Fi Direct
        if (app.settings.wifiConnectionMode == 4) {
            val btConnected = com.headqlink.unit.bridgehost.CompanionBridgeHost.isConnected || isBtDeviceAclConnected(device)
            if (btConnected) {
                manualConnectingActive = false
                updateConnectionModeButtons(root)
                refreshHeroState(root)
                rebuildRecentDevicesRow(root)
                android.widget.Toast.makeText(
                    requireContext(),
                    "🔵 Conectado por Solo Bluetooth a $devName. Toca «Proyectar Android Auto» arriba si quieres pantalla completa.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } else {
                beginConnectingUi(
                    view = root,
                    targetName = devName,
                    protocol = "Solo Bluetooth",
                    transportLabel = "🔵 Modo 4 · Conectando Bluetooth",
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
        app.settings.autoStartBluetoothDeviceName = devName
        if (app.settings.wifiConnectionMode == 0 || app.settings.wifiConnectionMode == 4) {
            app.settings.wifiConnectionMode = 3
        }
        ensureCarWifiEnabled()
        if (!WirelessScopePolicy.allowsWireless(app.settings.connectionModes)) {
            app.settings.connectionModes = app.settings.connectionModes + Settings.ConnectionMode.WIFI
        }
        com.andrerinas.openheadunit.cluster.ClusterMapController.notifyConnecting(
            requireContext(),
            devName,
            "Android Auto Inalámbrico",
            com.andrerinas.openheadunit.cluster.ClusterMapController.Mode.ANDROID_AUTO,
        )
        if (app.settings.wifiConnectionMode == 1 || device == null) {
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
        val mode = s.wifiConnectionMode
        val wm = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val wifiEnabled = wm?.isWifiEnabled == true
        val wifiBanner = view.findViewById<View>(R.id.wifi_conflict_banner)
        val needsWifi = (mode == 3 || mode == 1 || mode == 2)
        wifiBanner?.visibility = if (needsWifi && !wifiEnabled) View.VISIBLE else View.GONE

        val themeAccent = com.headqlink.unit.extended.QonectaTheme.accent
        val themeOnAccent = com.headqlink.unit.extended.QonectaTheme.onAccent
        val surfaceTop = 0xFF17202C.toInt()
        val textNormal = 0xFFEAF2F7.toInt()
        val outline = 0xFF243140.toInt()
        val okGreen = 0xFF3AD29F.toInt()

        fun styleModeBtn(btn: MaterialButton?, active: Boolean, activeColor: Int = themeAccent, activeText: Int = themeOnAccent) {
            btn ?: return
            if (active) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(activeColor)
                btn.setTextColor(activeText)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(activeColor)
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(surfaceTop)
                btn.setTextColor(textNormal)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(outline)
            }
        }

        val isWifiDirect = mode == 3
        val isHotspot = mode == 1 || mode == 2
        val isUsb = mode == 0
        val isBtOnly = mode == 4

        styleModeBtn(view.findViewById(R.id.mode_btn_wifi_direct), isWifiDirect)
        styleModeBtn(view.findViewById(R.id.mode_btn_hotspot), isHotspot)
        styleModeBtn(view.findViewById(R.id.mode_btn_usb), isUsb)

        val btStatus = com.headqlink.unit.bridgehost.CompanionBridgeHost.status()
        val btConnected = btStatus.isConnected
        val btDataOn = com.headqlink.unit.bridgehost.CompanionBridgeHost.useBluetoothDataBridge
        val btBtn = view.findViewById<MaterialButton>(R.id.mode_btn_bt_companion)
        if (btConnected) {
            val devName = btStatus.connectedDevices.firstOrNull()?.first?.take(12) ?: "Móvil"
            btBtn?.text = "🔵 4. BT: $devName\nViajes, Tarifas y Rutas"
            styleModeBtn(btBtn, true, activeColor = okGreen, activeText = 0xFF06131A.toInt())
        } else {
            btBtn?.text = if (btStatus.pairedCount > 0) "🔵 4. BT (En espera)\nViajes, Tarifas y Rutas" else "🔵 4. BT Companion\nViajes, Tarifas y Rutas"
            styleModeBtn(btBtn, isBtOnly)
        }

        val btSuffix = if (btConnected) {
            " · Companion BT conectado (${if (btDataOn) "Datos BT ACTIVO" else "Rutas Maps"})"
        } else {
            " · Companion BT en espera"
        }

        val expView = view.findViewById<TextView>(R.id.mode_active_explanation)
        expView?.text = when {
            isWifiDirect -> "📡 Modo 1 activo (Wi-Fi Direct 5 GHz): proyecta Android Auto sin cables entre el coche y el móvil$btSuffix."
            isHotspot -> "📲 Modo 2 activo (Hotspot del Móvil): usa el Punto de Acceso Wi-Fi del móvil para dar internet al coche y Android Auto$btSuffix."
            isBtOnly -> "🔵 Modo 4 activo (Solo Bluetooth Companion): sin Wi-Fi Direct; sincroniza viajes, tarifas, rutas y datos por Bluetooth$btSuffix."
            else -> "🔌 Modo 3 activo (Cable USB): proyección directa por cable USB con carga simultánea del móvil$btSuffix."
        }
    }

    private fun showUsbSwitchDialog(deviceName: String) {
        activeDialog?.dismiss()
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
            text = "Para usar la conexión por cable sin interferencias, ¿quieres cerrar la conexión Wi-Fi actual y cambiar directamente a Cable USB?"
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
            text = "🔌 Cerrar Wi-Fi y usar Cable USB"
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
                app.settings.wifiConnectionMode = 0
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
        val ctx = requireContext()
        com.headqlink.unit.bridgehost.CompanionBridgeHost.start(ctx)
        val st = com.headqlink.unit.bridgehost.CompanionBridgeHost.status()
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
            text = "🔵 4. Bluetooth Companion (Datos + Viajes + Rutas)"
            textSize = 27f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xFFEAF2F7.toInt())
            setPadding(0, 0, 0, dp(10))
        })

        val aclPair = firstConnectedBondedPhone()
        val devSummary = when {
            st.isConnected -> "● COMPANION CONECTADO: " + st.connectedDevices.joinToString { "${it.first} (${it.second})" }
            aclPair != null -> "● BLUETOOTH DEL COCHE CONECTADO: ${aclPair.second} (Abre Companion en el móvil para sincronizar)"
            st.pairedCount > 0 -> "● En espera de reconexión automática (${st.pairedCount} móvil emparejado)"
            else -> "● Sin emparejar aún — Pulsa «Generar código (6 cifras)» e introdúcelo en la app Companion del móvil"
        }
        val anyBtOk = st.isConnected || aclPair != null

        root.addView(TextView(ctx).apply {
            text = devSummary
            textSize = 21f
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

        val bullets = "• Servidor de Datos Bluetooth (Planificador e Internet): ${if (dataEnabled) "ACTIVO ✓" else "DESACTIVADO"}\n" +
            "• Sincronización de Viajes, Privacidad y Tarifas (Casa, Rápida, Gasolina y Diésel): ACTIVO ✓\n" +
            "• Envío de rutas con paradas de carga a Google Maps del móvil: ACTIVO ✓\n\n" +
            "Nota: Qonecta Unit Companion es para móviles Android. En iPhone (iOS) puedes compartir datos por Punto de Acceso Personal (Hotspot o Bluetooth PAN)."

        root.addView(TextView(ctx).apply {
            text = bullets
            textSize = 18.5f
            setTextColor(0xFFD2DFEC.toInt())
            setLineSpacing(0f, 1.22f)
            setPadding(0, 0, 0, dp(20))
        })

        val dialog = MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
            .setView(root)
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

    private fun openProjection() {
        startActivity(AapProjectionActivity.intent(requireContext()).apply {
            putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
        })
    }

    private fun showBluetoothHelp(message: Int) {
        activeDialog?.dismiss()
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
        super.onPause()
    }

    companion object {
        var forceSelfModeLaunch = false
        fun resetAutoStart() { forceSelfModeLaunch = false }
    }
}
