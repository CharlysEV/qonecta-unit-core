package com.andrerinas.openheadunit.main

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.headqlink.unit.extended.ExtendedHubView
import com.headqlink.unit.extended.LeapVehicleDataSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The car's standby screen. Wireless pairing is the primary path; USB remains a fallback. */
class HomeFragment : Fragment() {
    private val app get() = App.provide(requireContext())
    private var activeDialog: Dialog? = null
    private val bluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) selectPhone()
        else showBluetoothHelp(R.string.bt_permission_denied)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        LeapVehicleDataSource.start(requireContext().applicationContext)

        view.findViewById<View>(R.id.wifi_button).setOnClickListener {
            if (app.commManager.isConnected) {
                openProjection()
            } else if (app.settings.wifiConnectionMode == 1) {
                // Modo 2: Hotspot del Móvil (LAN wlan0 sin Wi-Fi Direct p2p0 para evitar saltos)
                ensureCarWifiEnabled()
                if (!WirelessScopePolicy.allowsWireless(app.settings.connectionModes)) {
                    app.settings.connectionModes = app.settings.connectionModes + Settings.ConnectionMode.WIFI
                }
                (activity as? MainActivity)?.beginAutoConnect(
                    "hotspot wireless scan", MainActivity.ConnectionUiMode.PILL
                )
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_START_WIRELESS_SCAN
                })
                updateConnectionModeButtons(view)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                selectPhone()
            }
        }
        view.findViewById<View>(R.id.usb_button).setOnClickListener {
            if (app.commManager.isConnected && !app.commManager.isWirelessSession) {
                openProjection()
            } else if (app.commManager.isConnected && app.commManager.isWirelessSession) {
                showUsbSwitchDialog("Dispositivo USB")
            } else {
                val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as? UsbManager
                val hasAppleUsb = usbManager?.deviceList?.values?.any { it.vendorId == 0x05AC } == true
                if (hasAppleUsb) {
                    startActivity(Intent(requireContext(), com.shilapi.xcertplay.LeapCarPlayActivity::class.java))
                } else {
                    app.settings.wifiConnectionMode = 0
                    updateConnectionModeButtons(view)
                    ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_SWITCH_TO_USB
                    })
                    findNavController().navigate(R.id.action_homeFragment_to_usbListFragment)
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
            }
        }
        view.findViewById<View>(R.id.mode_btn_bt_companion)?.setOnClickListener {
            showBluetoothCompanionDialog(view)
        }
        view.findViewById<View>(R.id.carplay_button)?.setOnClickListener {
            startActivity(Intent(requireContext(), com.shilapi.xcertplay.LeapCarPlayActivity::class.java))
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
        view.findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        // Going back to the car does not tear down a connection or disable reconnect.
        view.findViewById<View>(R.id.exit_button).setOnClickListener { requireActivity().moveTaskToBack(true) }

        updateExtendedModeButton(view)
        updateConnectionModeButtons(view)

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
                        val status = when (state) {
                            is CommManager.ConnectionState.Disconnected -> R.string.da_ready
                            is CommManager.ConnectionState.TransportStarted -> R.string.da_connected
                            else -> R.string.da_connecting
                        }
                        view.findViewById<TextView>(R.id.connection_status).setText(status)
                        view.findViewById<MaterialButton>(R.id.wifi_button).text =
                            if (app.commManager.isConnected) getString(R.string.da_open) else "Android Auto"
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

                        val snap = LeapVehicleDataSource.snapshot(allowDemoFallback = false)
                        if (snap.hasBatteryData) {
                            subtitleView?.text =
                                "LEAPMOTOR C10 EN VIVO  ·  BATERÍA ${snap.socPct.roundToInt()}% (${snap.effectiveRangeKm.roundToInt()} KM)  ·  MODO ${snap.driveModeLabel().uppercase()}"
                        }

                        val pageLabel = when (com.andrerinas.openheadunit.cluster.ClusterMapController.currentPage) {
                            com.andrerinas.openheadunit.cluster.ClusterMapController.ClusterPage.MAP -> "Cuadro: Mapa"
                            com.andrerinas.openheadunit.cluster.ClusterMapController.ClusterPage.MUSIC -> "Cuadro: Música"
                            com.andrerinas.openheadunit.cluster.ClusterMapController.ClusterPage.EXTENDED -> "Cuadro: C10"
                        }
                        clusterBtn?.text = pageLabel
                        delay(1500L)
                    }
                }
            }
        }
        if (app.settings.autoStartOnBoot || app.settings.autoStartOnScreenOn) {
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java))
        }
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
            val label = if (s.extendedPanelPercent >= 28) "Extendido: Qonecta+ (30%)" else "Extendido: Compacto (${s.extendedPanelPercent}%)"
            btn.text = label
            btn.setTextColor(themeAccent)
            btn.strokeColor = android.content.res.ColorStateList.valueOf(themeAccent)
        } else {
            btn.text = "Extendido: Pantalla Completa"
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
        // Solo avisar si se eligió Wi-Fi Direct o Hotspot y el Wi-Fi del coche está APAGADO
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

        styleModeBtn(view.findViewById(R.id.mode_btn_wifi_direct), isWifiDirect)
        styleModeBtn(view.findViewById(R.id.mode_btn_hotspot), isHotspot)
        styleModeBtn(view.findViewById(R.id.mode_btn_usb), isUsb)

        val btStatus = com.headqlink.unit.bridgehost.CompanionBridgeHost.status()
        val btConnected = btStatus.isConnected
        val btDataOn = com.headqlink.unit.bridgehost.CompanionBridgeHost.useBluetoothDataBridge
        val btBtn = view.findViewById<MaterialButton>(R.id.mode_btn_bt_companion)
        if (btConnected) {
            val devName = btStatus.connectedDevices.firstOrNull()?.first?.take(14) ?: "Móvil"
            btBtn?.text = "🔵 4. BT: $devName"
            styleModeBtn(btBtn, true, activeColor = okGreen, activeText = 0xFF06131A.toInt())
        } else {
            btBtn?.text = if (btStatus.pairedCount > 0) "🔵 4. Datos BT (En espera)" else "🔵 4. Datos BT Companion"
            styleModeBtn(btBtn, false)
        }

        val btSuffix = if (btConnected) {
            " · Companion BT conectado (${if (btDataOn) "Servidor de datos BT ACTIVO" else "Solo rutas Maps"})"
        } else {
            " · Companion BT en espera"
        }

        val expView = view.findViewById<TextView>(R.id.mode_active_explanation)
        expView?.text = when {
            isWifiDirect -> "📡 Modo 1 activo (Wi-Fi Direct 5 GHz): enlace directo coche-móvil con Wi-Fi del coche encendido y sin doble escaneo$btSuffix."
            isHotspot -> "🌐 Modo 2 activo (Hotspot del Móvil): conecta el Wi-Fi del coche al Punto de Acceso del móvil para tener datos en TV/TDT y Android Auto sin canal Wi-Fi Direct (cero saltos)$btSuffix."
            else -> "🔌 Modo 3 activo (Cable USB): Wi-Fi Direct y servidores inalámbricos detenidos para máxima estabilidad por cable$btSuffix."
        }
    }

    private fun showUsbSwitchDialog(deviceName: String) {
        activeDialog?.dismiss()
        activeDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle("🔌 Cable USB detectado ($deviceName)")
            .setMessage(
                "Para usar la conexión por cable sin interferencias, ¿quieres cerrar la conexión Wi-Fi actual y cambiar a conexión por Cable USB?"
            )
            .setPositiveButton("🔌 Cerrar Wi-Fi y usar Cable USB") { _, _ ->
                AapService.usbSwitchPrompt.value = null
                app.settings.wifiConnectionMode = 0
                view?.let { updateConnectionModeButtons(it) }
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_SWITCH_TO_USB
                })
            }
            .setNegativeButton("📡 Seguir por Wi-Fi") { _, _ ->
                AapService.usbSwitchPrompt.value = null
            }
            .setOnCancelListener {
                AapService.usbSwitchPrompt.value = null
            }
            .show()
    }

    private fun showBluetoothCompanionDialog(rootView: View) {
        activeDialog?.dismiss()
        val ctx = requireContext()
        com.headqlink.unit.bridgehost.CompanionBridgeHost.start(ctx)
        val st = com.headqlink.unit.bridgehost.CompanionBridgeHost.status()
        val dataEnabled = com.headqlink.unit.bridgehost.CompanionBridgeHost.useBluetoothDataBridge
        val devSummary = if (st.isConnected) {
            "● Conectado: " + st.connectedDevices.joinToString { "${it.first} (${it.second})" }
        } else if (st.pairedCount > 0) {
            "● En espera de reconexión automática (${st.pairedCount} móvil emparejado)"
        } else {
            "● Sin emparejar aún"
        }
        val codeLine = st.pairingCode?.let { "\n🔑 Código de emparejamiento activo: $it" } ?: ""
        val msg = "$devSummary$codeLine\n\n" +
            "• Servidor de Datos Bluetooth (Planificador): ${if (dataEnabled) "ACTIVO ✓" else "DESACTIVADO"}\n" +
            "• Envío de rutas con paradas a Google Maps: ACTIVO ✓\n\n" +
            "Nota: Qonecta Unit Companion es para móviles Android. En iPhone (iOS) puedes compartir datos por Punto de Acceso Personal (Hotspot o Bluetooth PAN)."

        activeDialog = MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
            .setTitle("🔵 4. Bluetooth Companion (Datos + Rutas)")
            .setMessage(msg)
            .setPositiveButton(if (dataEnabled) "Desactivar Datos BT" else "Activar Datos BT") { _, _ ->
                com.headqlink.unit.bridgehost.CompanionBridgeHost.useBluetoothDataBridge = !dataEnabled
                updateConnectionModeButtons(rootView)
            }
            .setNeutralButton("🔑 Generar código (6 cifras)") { _, _ ->
                com.headqlink.unit.bridgehost.CompanionBridgeHost.startPairing(ctx)
                showBluetoothCompanionDialog(rootView)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun openProjection() {
        startActivity(AapProjectionActivity.intent(requireContext()).apply {
            putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
        })
    }

    private fun selectPhone() {
        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
        try {
            if (adapter == null || !adapter.isEnabled) {
                showBluetoothHelp(R.string.da_bluetooth_off)
                return
            }
            val devices = adapter.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }
            if (devices.isEmpty()) {
                showBluetoothHelp(R.string.da_no_paired)
                return
            }
            activeDialog?.dismiss()
            val items = devices.mapIndexed { idx, dev ->
                PhonePickerDialog.PhoneItem(
                    name = dev.name ?: getString(R.string.da_device),
                    address = dev.address,
                    highlighted = idx == 0,
                    device = dev,
                )
            }
            activeDialog = PhonePickerDialog.show(
                context = requireContext(),
                title = getString(R.string.da_connect),
                subtitle = "Toca el cuadro de tu móvil vinculado para iniciar Android Auto Inalámbrico",
                items = items,
                pairButtonText = getString(R.string.da_pair),
                cancelButtonText = getString(R.string.cancel),
                onSelect = { index, _ ->
                    val device = devices[index]
                    val devName = device.name ?: getString(R.string.da_device)
                    app.settings.autoStartBluetoothDeviceName = devName
                    app.settings.wifiConnectionMode = 3
                    if (!WirelessScopePolicy.allowsWireless(app.settings.connectionModes)) {
                        app.settings.connectionModes =
                            app.settings.connectionModes + Settings.ConnectionMode.WIFI
                    }
                    com.andrerinas.openheadunit.cluster.ClusterMapController.notifyConnecting(
                        requireContext(),
                        devName,
                        "Android Auto Inalámbrico",
                        com.andrerinas.openheadunit.cluster.ClusterMapController.Mode.ANDROID_AUTO,
                    )
                    (activity as? MainActivity)?.beginAutoConnect(
                        "manual native wireless", MainActivity.ConnectionUiMode.PILL)
                    ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_NATIVE_AA_POKE
                        putExtra(AapService.EXTRA_MAC, device.address)
                    })
                },
                onPairNew = { openBluetoothSettings() },
            )
        } catch (_: SecurityException) {
            showBluetoothHelp(R.string.bt_permission_denied)
        }
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
        // Retained for existing automation entry points; self projection has no standby control.
        var forceSelfModeLaunch = false
        fun resetAutoStart() { forceSelfModeLaunch = false }
    }
}
