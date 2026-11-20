package com.andrerinas.openheadunit.main

import android.Manifest
import android.app.Dialog
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.cluster.ClusterMapController
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.UsbDeviceCompat
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.headqlink.unit.extended.ConnectionRingView
import com.headqlink.unit.files.CarFiles
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Pantalla principal limpia de Qonecta Unit CORE:
 * Exclusivamente Android Auto y Apple CarPlay (sin sistema Extendido ni servicios en segundo plano).
 * Totalmente localizada en los idiomas soportados (ES · EN · PT · IT · DE y resto de idiomas Android).
 */
class HomeFragment : Fragment() {
    private val app get() = App.provide(requireContext())
    private var activeDialog: Dialog? = null

    private var manualConnectingActive = false
    private var connectingStartElapsedMs = 0L
    private var connectingTargetName = ""
    private var connectingProtocol = "Android Auto"
    private var connectingTransportLabel = ""
    private var connectingTickJob: Job? = null
    private var carPlayLaunchJob: Job? = null
    private var usbReceiverRegistered = false

    private val usbStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            view?.let {
                updateConnectionModeButtons(it)
                refreshHeroState(it)
            }
        }
    }

    private val carFilesPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data?.data != null) {
            Toast.makeText(requireContext(), getString(R.string.qu_files_picker_tip), Toast.LENGTH_LONG).show()
        }
    }

    private val wirelessPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val btOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val p2pOk = hasWirelessP2pPermission()
        if (btOk && p2pOk) {
            view?.let { rebuildRecentDevicesRow(it) }
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_START_WIRELESS
            })
            startPrimaryConnection()
        } else if (!btOk) {
            showBluetoothHelp(R.string.bt_permission_denied)
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.core_toast_location_p2p),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun hasWirelessP2pPermission(): Boolean {
        val ctx = requireContext()
        val hasFine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNearby = ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            return hasNearby || hasFine
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) hasFine else true
    }

    private fun missingWirelessPermissions(): Array<String> {
        val ctx = requireContext()
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.NEARBY_WIFI_DEVICES
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return needed.toTypedArray()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        view.findViewById<TextView>(R.id.home_version_badge)?.apply {
            text = "v${BuildConfig.VERSION_NAME} · AA + CarPlay"
            setOnClickListener {
                activeDialog?.dismiss()
                activeDialog = SupportDonationsDialog.show(requireContext())
            }
        }

        view.findViewById<View>(R.id.home_support_button)?.setOnClickListener {
            activeDialog?.dismiss()
            activeDialog = SupportDonationsDialog.show(requireContext())
        }

        view.findViewById<View>(R.id.wifi_button)?.setOnClickListener {
            if (app.commManager.isConnected) {
                openProjection()
            } else {
                val missing = if (app.settings.wifi5gEnabled || app.settings.hotspotModeEnabled) {
                    missingWirelessPermissions()
                } else emptyArray()
                if (missing.isNotEmpty()) {
                    wirelessPermissions.launch(missing)
                } else {
                    startPrimaryConnection()
                }
            }
        }

        view.findViewById<View>(R.id.connection_ring_view)?.setOnClickListener {
            if (app.commManager.isConnected) {
                openProjection()
            } else if (!manualConnectingActive) {
                if (findConnectedUsbPhone() != null) {
                    handleUsbConnectTap(view)
                } else {
                    val missing = if (app.settings.wifi5gEnabled || app.settings.hotspotModeEnabled) {
                        missingWirelessPermissions()
                    } else emptyArray()
                    if (missing.isNotEmpty()) {
                        wirelessPermissions.launch(missing)
                    } else {
                        startPrimaryConnection()
                    }
                }
            }
        }

        view.findViewById<View>(R.id.hero_cancel_button)?.setOnClickListener {
            cancelCurrentConnectionAttempt(view)
        }

        view.findViewById<View>(R.id.carplay_button)?.setOnClickListener {
            launchCarPlay(view, "iPhone (CarPlay)")
        }

        view.findViewById<View>(R.id.usb_button)?.setOnClickListener {
            handleUsbConnectTap(view)
        }

        // Modo 1: Wi-Fi 5 GHz (Android Auto inalámbrico)
        view.findViewById<View>(R.id.mode_btn_wifi_direct)?.setOnClickListener {
            val next = !app.settings.wifi5gEnabled
            app.settings.wifi5gEnabled = next
            if (next) {
                stopCarPlayForAndroidAuto()
                app.settings.nativeApTransport = 0
                ensureCarWifiEnabled()
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_START_WIRELESS
                })
            } else {
                if (app.commManager.isConnected && app.commManager.isWirelessSession) {
                    app.commManager.disconnect(sendByeBye = true, isUserExit = true)
                }
                stopConnectingUi()
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_WIRELESS
                })
            }
            updateConnectionModeButtons(view)
            refreshHeroState(view)
        }

        // Modo 2: Hotspot Móvil
        view.findViewById<View>(R.id.mode_btn_hotspot)?.setOnClickListener {
            val next = !app.settings.hotspotModeEnabled
            app.settings.hotspotModeEnabled = next
            if (next) {
                stopCarPlayForAndroidAuto()
                ensureCarWifiEnabled()
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_START_WIRELESS_SCAN
                })
            } else {
                if (app.commManager.isConnected && app.commManager.isWirelessSession) {
                    app.commManager.disconnect(sendByeBye = true, isUserExit = true)
                }
                stopConnectingUi()
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_WIRELESS
                })
            }
            updateConnectionModeButtons(view)
            refreshHeroState(view)
        }

        // Modo 3: Cable USB
        view.findViewById<View>(R.id.mode_btn_usb)?.setOnClickListener {
            val next = !app.settings.usbModeEnabled
            app.settings.usbModeEnabled = next
            app.settings.listenForUsbDevices = next
            com.andrerinas.openheadunit.utils.Settings.setUsbAttachedActivityEnabled(requireContext(), next)
            if (next) {
                if (!app.commManager.isConnected) {
                    ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_CHECK_USB
                    })
                }
            } else if (app.commManager.isConnected && !app.commManager.isWirelessSession) {
                app.commManager.disconnect(sendByeBye = true, isUserExit = true)
            }
            updateConnectionModeButtons(view)
            refreshHeroState(view)
        }

        view.findViewById<View>(R.id.wifi_conflict_action)?.setOnClickListener {
            val enabledDirectly = ensureCarWifiEnabled()
            if (!enabledDirectly) {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) }
                    .onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) } }
            }
            updateConnectionModeButtons(view)
        }

        view.findViewById<View>(R.id.cluster_toggle_button)?.setOnClickListener {
            val next = !app.settings.clusterMapEnabled
            app.settings.clusterMapEnabled = next
            if (!next) {
                ClusterMapController.stop(context = requireContext())
            }
            updateClusterToggleButton(view)
            Toast.makeText(
                requireContext(),
                getString(if (next) R.string.qu_cluster_toast_on else R.string.qu_cluster_toast_off),
                Toast.LENGTH_SHORT,
            ).show()
        }

        view.findViewById<View>(R.id.files_button)?.setOnClickListener {
            val result = CarFiles.open(requireActivity()) { pickerIntent ->
                runCatching { carFilesPickerLauncher.launch(pickerIntent) }.isSuccess
            }
            if (result == CarFiles.OpenResult.UNAVAILABLE) {
                Toast.makeText(requireContext(), getString(R.string.qu_files_none), Toast.LENGTH_LONG).show()
            }
        }

        view.findViewById<View>(R.id.settings_button)?.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        // Salida limpia: detiene por completo AA y CarPlay para no dejar nada vivo en segundo plano.
        view.findViewById<View>(R.id.exit_button)?.setOnClickListener {
            stopConnectingUi()
            stopCarPlayForAndroidAuto()
            runCatching { ClusterMapController.stop(context = requireContext()) }
            runCatching { app.commManager.disconnect(sendByeBye = true, isUserExit = true) }
            runCatching {
                requireContext().startService(Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                })
            }
            requireActivity().finishAffinity()
        }

        updateClusterToggleButton(view)
        updateConnectionModeButtons(view)
        rebuildRecentDevicesRow(view)
        refreshHeroState(view)

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
                    AapService.wirelessStatusText.collect {
                        refreshHeroState(view)
                    }
                }
                launch {
                    app.commManager.connectionState.collect { state ->
                        when (state) {
                            is CommManager.ConnectionState.TransportStarted -> {
                                stopConnectingUi()
                                refreshHeroState(view)
                            }
                            is CommManager.ConnectionState.Disconnected -> {
                                refreshHeroState(view)
                            }
                            else -> {
                                if (!manualConnectingActive) {
                                    val fallbackName = app.settings.autoStartBluetoothDeviceName
                                        .takeIf { it.isNotBlank() } ?: getString(R.string.core_your_phone)
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
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!usbReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            runCatching {
                ContextCompat.registerReceiver(
                    requireContext(),
                    usbStateReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                usbReceiverRegistered = true
            }
        }
    }

    override fun onStop() {
        if (usbReceiverRegistered) {
            runCatching { requireContext().unregisterReceiver(usbStateReceiver) }
            usbReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        com.andrerinas.openheadunit.utils.Settings.setUsbAttachedActivityEnabled(
            requireContext(),
            app.settings.usbModeEnabled,
        )
        view?.let {
            updateClusterToggleButton(it)
            updateConnectionModeButtons(it)
            rebuildRecentDevicesRow(it)
            refreshHeroState(it)
        }
    }

    private fun findConnectedUsbPhone(): Pair<UsbDevice, Boolean>? {
        val usbManager = context?.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val devices = runCatching { usbManager.deviceList.values }.getOrDefault(emptyList())
        devices.firstOrNull { UsbDeviceCompat.isInAccessoryMode(it) }?.let { return it to false }
        devices.firstOrNull { UsbDeviceCompat.isAndroidDevice(it) }?.let { return it to false }
        devices.firstOrNull { UsbDeviceCompat.isAppleDevice(it) }?.let { return it to true }
        return null
    }

    private fun shortUsbDeviceLabel(device: UsbDevice, isApple: Boolean): String {
        if (isApple) return "iPhone"
        if (UsbDeviceCompat.isInAccessoryMode(device)) return "Android Auto"
        val label = listOfNotNull(
            device.manufacturerName?.trim()?.takeIf { it.isNotBlank() },
            device.productName?.trim()?.takeIf { it.isNotBlank() },
        ).joinToString(" ").trim()
        return label.takeIf { it.isNotBlank() }?.take(22) ?: getString(R.string.core_usb_android_fallback)
    }

    private fun handleUsbConnectTap(view: View) {
        if (app.commManager.isConnected && !app.commManager.isWirelessSession) {
            openProjection()
            return
        }
        if (app.commManager.isConnected && app.commManager.isWirelessSession) {
            showUsbSwitchDialog(getString(R.string.core_btn_usb_cable))
            return
        }
        app.settings.usbModeEnabled = true
        app.settings.listenForUsbDevices = true
        com.andrerinas.openheadunit.utils.Settings.setUsbAttachedActivityEnabled(requireContext(), true)
        updateConnectionModeButtons(view)

        val usbPhone = findConnectedUsbPhone()
        if (usbPhone != null && usbPhone.second) {
            launchCarPlay(view, "iPhone (USB)", usbDevice = usbPhone.first)
            return
        }

        stopCarPlayForAndroidAuto()
        if (usbPhone == null) {
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_CHECK_USB
            })
            Toast.makeText(
                requireContext(),
                getString(R.string.core_toast_plug_usb),
                Toast.LENGTH_LONG,
            ).show()
            refreshHeroState(view)
            return
        }

        val label = shortUsbDeviceLabel(usbPhone.first, isApple = false)
        beginConnectingUi(
            view = view,
            targetName = label,
            protocol = "Android Auto",
            transportLabel = getString(R.string.core_transport_connecting_usb),
        )
        ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
            action = AapService.ACTION_CHECK_USB
        })
    }

    private fun updateClusterToggleButton(view: View) {
        val btn = view.findViewById<MaterialButton>(R.id.cluster_toggle_button) ?: return
        val enabled = app.settings.clusterMapEnabled
        btn.text = getString(if (enabled) R.string.qu_cluster_short_on else R.string.qu_cluster_short_off)
        btn.backgroundTintList = ColorStateList.valueOf(if (enabled) 0xFF10282C.toInt() else 0xFF17202C.toInt())
        btn.strokeColor = ColorStateList.valueOf(if (enabled) ACCENT else 0xFF243140.toInt())
        btn.setTextColor(if (enabled) ACCENT else 0xFFEAF2F7.toInt())
    }

    private fun stopCarPlayForAndroidAuto() {
        val ctx = context ?: return
        runCatching { com.shilapi.xcertplay.CarPlayBackgroundSession.stop() }
        runCatching { ctx.stopService(Intent(ctx, com.shilapi.xcertplay.LeapCarPlaySessionService::class.java)) }
        runCatching { ctx.stopService(Intent(ctx, com.shilapi.xcertplay.network.CarPlayVpnService::class.java)) }
    }

    private fun launchCarPlay(
        view: View,
        targetName: String,
        usbDevice: UsbDevice? = null,
    ) {
        stopConnectingUi()
        runCatching {
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_STOP_WIRELESS
            })
        }
        beginConnectingUi(
            view = view,
            targetName = targetName,
            protocol = "CarPlay",
            transportLabel = getString(
                if (usbDevice != null) R.string.core_transport_opening_cp_usb else R.string.core_transport_opening_cp
            ),
        )
        carPlayLaunchJob?.cancel()
        carPlayLaunchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(350L)
            if (isAdded) {
                stopConnectingUi()
                val intent = if (usbDevice != null) {
                    Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply {
                        setClass(requireContext(), com.shilapi.xcertplay.CarPlayHostActivity::class.java)
                        putExtra(UsbManager.EXTRA_DEVICE, usbDevice)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                } else {
                    Intent(requireContext(), com.shilapi.xcertplay.LeapCarPlayActivity::class.java)
                }
                startActivity(intent)
            }
        }
    }

    private fun currentTransportConnectingLabel(): String {
        val s = app.settings
        return when {
            s.wifi5gEnabled -> getString(R.string.core_transport_connecting_wifi)
            s.hotspotModeEnabled -> getString(R.string.core_transport_connecting_hotspot)
            else -> getString(R.string.core_transport_connecting_usb)
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

        connectingTickJob?.cancel()
        connectingTickJob = viewLifecycleOwner.lifecycleScope.launch {
            while (manualConnectingActive && isAdded) {
                refreshHeroState(view)
                delay(1000L)
            }
        }
    }

    private fun stopConnectingUi() {
        manualConnectingActive = false
        connectingTickJob?.cancel()
        connectingTickJob = null
        carPlayLaunchJob?.cancel()
        carPlayLaunchJob = null
    }

    private fun cancelCurrentConnectionAttempt(view: View) {
        stopConnectingUi()
        runCatching {
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_STOP_WIRELESS
            })
        }
        refreshHeroState(view)
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

        val s = app.settings
        val wirelessAaEnabled = s.wifi5gEnabled || s.hotspotModeEnabled
        pill?.setTextColor(ACCENT)
        badge?.setTextColor(0xFFEAF2F7.toInt())

        when {
            app.commManager.isConnected -> {
                val devName = s.autoStartBluetoothDeviceName.takeIf { it.isNotBlank() } ?: getString(R.string.core_your_phone)
                pill?.setTextColor(OK_GREEN)
                pill?.text = getString(R.string.core_status_connected)
                badge?.text = if (app.commManager.isWirelessSession) {
                    if (s.hotspotModeEnabled) getString(R.string.core_badge_hotspot_connected)
                    else getString(R.string.core_badge_wifi5g_connected)
                } else {
                    getString(R.string.core_badge_usb_connected)
                }
                title?.text = getString(R.string.core_hero_title_connected, devName)
                sub?.text = getString(R.string.core_hero_sub_connected)
                wifiBtn?.visibility = View.VISIBLE
                wifiBtn?.text = getString(R.string.da_open)
                cpBtn?.visibility = View.VISIBLE
                usbBtn?.visibility = View.GONE
                cancelBtn?.visibility = View.GONE

                ring?.state = ConnectionRingView.State.CONNECTED
                ring?.protocolTitle = "Android Auto"
                ring?.subtitleText = getString(R.string.core_ring_projecting)
            }
            manualConnectingActive -> {
                val elapsedSec = ((SystemClock.elapsedRealtime() - connectingStartElapsedMs) / 1000L).toInt().coerceAtLeast(0)
                if (elapsedSec > 35) {
                    stopConnectingUi()
                    refreshHeroState(view)
                    return
                }
                pill?.text = getString(R.string.core_status_connecting)
                val liveStatus = AapService.wirelessStatusText.value
                badge?.text = connectingTransportLabel
                title?.text = getString(R.string.core_hero_title_connecting, connectingTargetName)
                sub?.text = liveStatus ?: getString(R.string.core_hero_sub_connecting)
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
                val credsText = getString(R.string.core_live_creds_sent)
                val btNegText = getString(R.string.core_live_bt_negotiating)
                ring?.subtitleText = when {
                    liveStatus != null && liveStatus == credsText -> getString(R.string.core_ring_opening_session)
                    liveStatus != null && liveStatus == btNegText -> getString(R.string.core_ring_negotiating_wifi)
                    elapsedSec < 3 -> getString(R.string.core_ring_negotiating)
                    elapsedSec < 8 -> getString(R.string.core_ring_waiting_phone)
                    else -> getString(R.string.core_ring_opening_session)
                }
            }
            else -> {
                val usbPhone = findConnectedUsbPhone()
                pill?.text = getString(R.string.core_status_ready)
                val liveStatus = AapService.wirelessStatusText.value
                badge?.setTextColor(if (usbPhone != null) ACCENT else 0xFFEAF2F7.toInt())
                badge?.text = when {
                    usbPhone != null -> getString(R.string.core_badge_usb_detected, shortUsbDeviceLabel(usbPhone.first, usbPhone.second))
                    s.wifi5gEnabled -> getString(R.string.core_badge_wifi5g_ready)
                    s.hotspotModeEnabled -> getString(R.string.core_badge_hotspot_ready)
                    s.usbModeEnabled -> getString(R.string.core_badge_usb_ready)
                    else -> getString(R.string.core_badge_select_mode)
                }
                title?.text = getString(R.string.core_hero_title_idle)
                sub?.text = when {
                    usbPhone != null && usbPhone.second -> getString(R.string.core_hero_sub_usb_iphone)
                    usbPhone != null -> getString(R.string.core_hero_sub_usb_android, shortUsbDeviceLabel(usbPhone.first, false))
                    s.wifi5gEnabled && !liveStatus.isNullOrBlank() -> liveStatus
                    else -> getString(R.string.core_hero_sub_idle)
                }
                wifiBtn?.visibility = View.VISIBLE
                wifiBtn?.text = getString(
                    if (wirelessAaEnabled) R.string.core_btn_connect_aa else R.string.core_btn_connect_usb_primary
                )
                cpBtn?.visibility = View.VISIBLE
                val showUsbBtn = (s.usbModeEnabled && wirelessAaEnabled) || usbPhone != null
                usbBtn?.visibility = if (showUsbBtn) View.VISIBLE else View.GONE
                if (usbPhone != null) {
                    usbBtn?.text = getString(if (usbPhone.second) R.string.core_btn_usb_carplay else R.string.core_btn_usb_connect)
                    usbBtn?.backgroundTintList = ColorStateList.valueOf(0xFF10282C.toInt())
                    usbBtn?.strokeColor = ColorStateList.valueOf(ACCENT)
                    usbBtn?.setTextColor(ACCENT)
                } else {
                    usbBtn?.text = getString(R.string.core_btn_usb_cable)
                    usbBtn?.backgroundTintList = ColorStateList.valueOf(0xFF1E2935.toInt())
                    usbBtn?.strokeColor = ColorStateList.valueOf(0xFF2E4256.toInt())
                    usbBtn?.setTextColor(0xFFEAF2F7.toInt())
                }
                cancelBtn?.visibility = View.GONE

                ring?.state = ConnectionRingView.State.IDLE
                ring?.protocolTitle = when {
                    usbPhone != null -> getString(if (usbPhone.second) R.string.core_btn_usb_carplay else R.string.core_btn_usb_cable)
                    s.wifi5gEnabled -> "Wi-Fi 5 GHz"
                    s.hotspotModeEnabled -> getString(R.string.core_mode_chip_hotspot).removePrefix("📲 ")
                    s.usbModeEnabled -> getString(R.string.core_btn_usb_cable)
                    else -> getString(R.string.core_ring_standby)
                }
                ring?.subtitleText = getString(
                    if (usbPhone != null) R.string.core_ring_usb_ready else R.string.core_ring_tap_connect
                )
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

        val savedName = app.settings.autoStartBluetoothDeviceName.takeIf { it.isNotBlank() }

        if (bonded.isNotEmpty()) {
            bonded.take(3).forEachIndexed { index, dev ->
                val name = runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: dev.address
                val isTarget = name == savedName || (savedName == null && index == 0)
                val subtitle = if (name.contains("iPhone", ignoreCase = true)) {
                    getString(R.string.core_paired_sub_cp)
                } else {
                    getString(R.string.core_paired_sub_aa)
                }
                container.addView(
                    createRecentDeviceCard(
                        icon = if (name.contains("iPhone", ignoreCase = true)) "🍎" else "📱",
                        title = name,
                        subtitle = subtitle,
                        highlighted = isTarget,
                        onClick = { connectToBondedDevice(view, dev, name) },
                    )
                )
            }
        } else {
            container.addView(
                createRecentDeviceCard(
                    icon = "📱",
                    title = savedName ?: getString(R.string.core_paired_fallback_android),
                    subtitle = getString(R.string.core_paired_fallback_aa_sub),
                    highlighted = true,
                    onClick = { startPrimaryConnection() },
                )
            )
            container.addView(
                createRecentDeviceCard(
                    icon = "🍎",
                    title = "iPhone (CarPlay)",
                    subtitle = getString(R.string.core_paired_fallback_cp_sub),
                    highlighted = false,
                    onClick = { launchCarPlay(view, "iPhone (CarPlay)") },
                )
            )
        }

        container.addView(
            createRecentDeviceCard(
                icon = "＋",
                title = getString(R.string.core_paired_add_title),
                subtitle = getString(R.string.core_paired_add_sub),
                highlighted = false,
                lastCard = true,
                onClick = { openBluetoothSettings() },
            )
        )
    }

    private fun createRecentDeviceCard(
        icon: String,
        title: String,
        subtitle: String,
        highlighted: Boolean,
        lastCard: Boolean = false,
        onClick: () -> Unit,
    ): View {
        val ctx = requireContext()

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
                    if (highlighted) ACCENT else 0xFF243140.toInt(),
                )
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (!lastCard) marginEnd = dp(12)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val iconBox = TextView(ctx).apply {
            text = icon
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
                setTextColor(if (highlighted) ACCENT else 0xFF93A4B7.toInt())
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        card.addView(textCol)
        return card
    }

    private fun startPrimaryConnection() {
        val root = view ?: return
        stopCarPlayForAndroidAuto()
        val s = app.settings
        val wirelessAaEnabled = s.wifi5gEnabled || s.hotspotModeEnabled

        if (!wirelessAaEnabled) {
            if (s.usbModeEnabled) {
                handleUsbConnectTap(root)
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.core_toast_enable_mode_first),
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }

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
        val target = preferred?.name?.takeIf { it.isNotBlank() }
            ?: savedName.takeIf { it.isNotBlank() }
            ?: getString(R.string.core_your_android_phone)

        beginConnectingUi(
            view = root,
            targetName = target,
            protocol = "Android Auto",
            transportLabel = currentTransportConnectingLabel(),
        )
        startWirelessScanOrDevice(preferred, target)
    }

    private fun connectToBondedDevice(root: View, device: BluetoothDevice, devName: String) {
        app.settings.autoStartBluetoothDeviceName = devName
        if (devName.contains("iPhone", ignoreCase = true)) {
            launchCarPlay(root, devName)
            return
        }

        val missing = missingWirelessPermissions()
        if (missing.isNotEmpty()) {
            wirelessPermissions.launch(missing)
            return
        }

        stopCarPlayForAndroidAuto()
        val s = app.settings
        if (!s.wifi5gEnabled && !s.hotspotModeEnabled) {
            s.wifi5gEnabled = true
            s.nativeApTransport = 0
            updateConnectionModeButtons(root)
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
        if (device != null && !device.address.isNullOrBlank()) {
            val mac = device.address
            if (mac !in s.autoStartBluetoothDeviceMacs) {
                s.autoStartBluetoothDeviceMacs = s.autoStartBluetoothDeviceMacs + mac
            }
            if (mac in s.autoStartBluetoothDeviceMacsRemoved) {
                s.autoStartBluetoothDeviceMacsRemoved = s.autoStartBluetoothDeviceMacsRemoved - mac
            }
        }
        if (!s.wifi5gEnabled && !s.hotspotModeEnabled) return
        if (s.wifi5gEnabled && s.nativeApTransport != 0) {
            s.nativeApTransport = 0
        }
        ensureCarWifiEnabled()
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

    private fun ensureCarWifiEnabled(): Boolean {
        val wm = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        if (wm.isWifiEnabled) return true
        @Suppress("DEPRECATION")
        return runCatching { wm.setWifiEnabled(true) && wm.isWifiEnabled }.getOrDefault(false)
    }

    private fun updateConnectionModeButtons(view: View) {
        val s = app.settings
        val wifi5gOn = s.wifi5gEnabled
        val hotspotOn = s.hotspotModeEnabled
        val usbOn = s.usbModeEnabled

        val wm = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiEnabled = wm?.isWifiEnabled == true
        view.findViewById<View>(R.id.wifi_conflict_banner)?.visibility =
            if ((wifi5gOn || hotspotOn) && !wifiEnabled) View.VISIBLE else View.GONE

        val surfaceTop = 0xFF17202C.toInt()
        val textDim = 0xFFAEC0D2.toInt()
        val outline = 0xFF243140.toInt()

        fun styleCheckBtn(btn: MaterialButton?, checked: Boolean) {
            btn ?: return
            if (checked) {
                btn.backgroundTintList = ColorStateList.valueOf(ACCENT)
                btn.setTextColor(ON_ACCENT)
                btn.strokeColor = ColorStateList.valueOf(ACCENT)
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(surfaceTop)
                btn.setTextColor(textDim)
                btn.strokeColor = ColorStateList.valueOf(outline)
            }
        }

        view.findViewById<MaterialButton>(R.id.mode_btn_wifi_direct)?.let {
            it.text = getString(if (wifi5gOn) R.string.core_mode_wifi_on else R.string.core_mode_wifi_off)
            styleCheckBtn(it, wifi5gOn)
        }
        view.findViewById<MaterialButton>(R.id.mode_btn_hotspot)?.let {
            it.text = getString(if (hotspotOn) R.string.core_mode_hotspot_on else R.string.core_mode_hotspot_off)
            styleCheckBtn(it, hotspotOn)
        }
        view.findViewById<MaterialButton>(R.id.mode_btn_usb)?.let {
            it.text = getString(if (usbOn) R.string.core_mode_usb_on else R.string.core_mode_usb_off)
            styleCheckBtn(it, usbOn)
        }

        val activeList = buildList {
            if (wifi5gOn) add(getString(R.string.core_mode_chip_wifi))
            if (hotspotOn) add(getString(R.string.core_mode_chip_hotspot))
            if (usbOn) add(getString(R.string.core_mode_chip_usb))
        }
        view.findViewById<TextView>(R.id.mode_active_explanation)?.text = if (activeList.isEmpty()) {
            getString(R.string.core_modes_none)
        } else {
            getString(R.string.core_modes_active, activeList.joinToString("  ·  "))
        }
    }

    private fun showUsbSwitchDialog(deviceName: String) {
        activeDialog?.dismiss()
        activeDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(getString(R.string.core_usb_switch_title, deviceName))
            .setMessage(getString(R.string.core_usb_switch_message))
            .setPositiveButton(getString(R.string.core_usb_switch_use_usb)) { _, _ ->
                AapService.usbSwitchPrompt.value = null
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_SWITCH_TO_USB
                })
            }
            .setNegativeButton(getString(R.string.core_usb_switch_keep_wifi)) { _, _ ->
                AapService.usbSwitchPrompt.value = null
            }
            .setOnCancelListener {
                AapService.usbSwitchPrompt.value = null
            }
            .show()
    }

    private fun openProjection() {
        startActivity(AapProjectionActivity.intent(requireContext()).apply {
            putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
        })
    }

    private fun showBluetoothHelp(message: Int) {
        activeDialog?.dismiss()
        activeDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.da_pair)
            .setMessage(message)
            .setPositiveButton(R.string.da_system_settings) { _, _ -> openBluetoothSettings() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }

    override fun onPause() {
        stopConnectingUi()
        activeDialog?.dismiss()
        activeDialog = null
        super.onPause()
    }

    companion object {
        private const val ACCENT = 0xFF00E5C7.toInt()
        private const val ON_ACCENT = 0xFF06131A.toInt()
        private const val OK_GREEN = 0xFF3AD29F.toInt()

        var forceSelfModeLaunch = false
        fun resetAutoStart() { forceSelfModeLaunch = false }
    }
}
