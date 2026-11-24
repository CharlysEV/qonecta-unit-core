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
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SystemUI
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.headqlink.unit.extended.ConnectionRingView
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.LeapCarPlayBootstrap
import com.shilapi.xcertplay.LeapCarPlayPreferences
import com.shilapi.xcertplay.LeapCarPlaySessionService
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Pantalla de inicio única y unificada de Qonecta Unit CORE:
 * Android Auto y Apple CarPlay con 100 % de continuidad visual en la misma pantalla,
 * cabecera fija, selector simétrico de protocolo, barra de transporte unificada,
 * acelerador Wi-Fi Direct 5 GHz con reinicio en 1 toque y dock unificado de teléfonos.
 */
class HomeFragment : Fragment() {

    private enum class Protocol { ANDROID_AUTO, CARPLAY }

    private val app get() = App.provide(requireContext())
    private var activeProtocol: Protocol = Protocol.ANDROID_AUTO
    private var activeDialog: Dialog? = null

    private var manualConnectingActive = false
    private var connectingStartElapsedMs = 0L
    private var connectingTargetName = ""
    private var connectingProtocol = "Android Auto"
    private var connectingTransportLabel = ""
    private var connectingTickJob: Job? = null
    private var carPlayLaunchJob: Job? = null
    private var pendingCarPlayWireless = false
    private var usbReceiverRegistered = false

    private val usbStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            view?.let { refreshAll(it) }
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
            if (activeProtocol == Protocol.CARPLAY) {
                startCarPlayConnection(wireless = true)
            } else {
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_START_WIRELESS
                })
                startPrimaryAaConnection()
            }
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
        activeProtocol = if (app.settings.lastHomeProtocol == "carplay") Protocol.CARPLAY else Protocol.ANDROID_AUTO
        syncSharedHotspotCredentials()

        view.findViewById<TextView>(R.id.home_version_badge)?.apply {
            text = "v${BuildConfig.VERSION_NAME} · AA + CarPlay"
            setOnClickListener {
                activeDialog?.dismiss()
                activeDialog = SupportDonationsDialog.show(requireContext())
            }
        }

        // Fixed header buttons
        view.findViewById<View>(R.id.car_bar_toggle_button)?.setOnClickListener {
            val s = app.settings
            val currentlyVisible = s.fullscreenMode == Settings.FullscreenMode.NONE
            val nextMode = if (currentlyVisible) Settings.FullscreenMode.IMMERSIVE else Settings.FullscreenMode.NONE
            s.fullscreenMode = nextMode
            val hideBars = nextMode != Settings.FullscreenMode.NONE
            AirPlayPersistence.saveHideTopBar(requireContext(), hideBars)
            AirPlayPersistence.saveHideBottomBar(requireContext(), hideBars)
            val rootView = requireActivity().findViewById<View>(R.id.main_content) ?: view
            SystemUI.apply(requireActivity().window, rootView, nextMode)
            updateHeaderControls(view)
            Toast.makeText(
                requireContext(),
                getString(if (hideBars) R.string.qu_home_bar_toast_off else R.string.qu_home_bar_toast_on),
                Toast.LENGTH_SHORT,
            ).show()
        }

        view.findViewById<View>(R.id.cluster_toggle_button)?.setOnClickListener {
            val next = !app.settings.clusterMapEnabled
            app.settings.clusterMapEnabled = next
            if (!next) {
                ClusterMapController.stop(context = requireContext())
            }
            updateHeaderControls(view)
            Toast.makeText(
                requireContext(),
                getString(if (next) R.string.qu_cluster_toast_on else R.string.qu_cluster_toast_off),
                Toast.LENGTH_SHORT,
            ).show()
        }

        view.findViewById<View>(R.id.home_coffee_button)?.setOnClickListener {
            activeDialog?.dismiss()
            activeDialog = SupportDonationsDialog.show(requireContext())
        }

        view.findViewById<View>(R.id.settings_button)?.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

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

        // Symmetric Protocol Switcher (100 % continuity inside the same Hero Card!)
        view.findViewById<View>(R.id.proto_tab_aa)?.setOnClickListener {
            selectProtocol(Protocol.ANDROID_AUTO, view)
        }
        view.findViewById<View>(R.id.proto_tab_cp)?.setOnClickListener {
            selectProtocol(Protocol.CARPLAY, view)
        }

        // Primary CTA button (adapts to AA vs CarPlay)
        view.findViewById<View>(R.id.wifi_button)?.setOnClickListener {
            onPrimaryConnectTap(view)
        }

        // Connection Ring tap
        view.findViewById<View>(R.id.connection_ring_view)?.setOnClickListener {
            if (activeProtocol == Protocol.CARPLAY) {
                when {
                    CarPlayBackgroundSession.hasSession() -> openCarPlayHost()
                    findConnectedUsbPhone()?.second == true -> startCarPlayConnection(wireless = false, usbDevice = findConnectedUsbPhone()?.first)
                    else -> onPrimaryConnectTap(view)
                }
            } else {
                if (app.commManager.isConnected) {
                    openAaProjection()
                } else if (!manualConnectingActive) {
                    val usb = findConnectedUsbPhone()
                    if (usb != null && !usb.second) handleUsbConnectTap(view) else onPrimaryConnectTap(view)
                }
            }
        }

        // Contextual USB button
        view.findViewById<View>(R.id.usb_button)?.setOnClickListener {
            if (activeProtocol == Protocol.CARPLAY) {
                val appleUsb = findConnectedUsbPhone()?.takeIf { it.second }?.first
                startCarPlayConnection(wireless = false, usbDevice = appleUsb)
            } else {
                handleUsbConnectTap(view)
            }
        }

        // Cancel / Disconnect button
        view.findViewById<View>(R.id.hero_cancel_button)?.setOnClickListener {
            if (activeProtocol == Protocol.CARPLAY && CarPlayBackgroundSession.hasSession()) {
                CarPlayBackgroundSession.stop {
                    activity?.runOnUiThread {
                        if (isAdded && getView() != null) {
                            stopConnectingUi()
                            refreshAll(requireView())
                        }
                    }
                }
            } else if (app.commManager.isConnected) {
                app.commManager.disconnect(sendByeBye = true, isUserExit = true)
                refreshAll(view)
            } else {
                cancelCurrentConnectionAttempt(view)
            }
        }

        // 1-tap 5 GHz Wi-Fi Direct reset pill
        view.findViewById<View>(R.id.hero_reset_wifi_btn)?.setOnClickListener {
            if (activeProtocol == Protocol.CARPLAY) {
                confirmCarPlayWifiReset()
            } else {
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_RESTART_WIFI_DIRECT
                })
                Toast.makeText(requireContext(), getString(R.string.qu_home_reset_wifi_toast), Toast.LENGTH_SHORT).show()
            }
        }

        // Unified Transport Selector (single source of truth for BOTH AA & CarPlay!)
        view.findViewById<View>(R.id.mode_btn_wifi_direct)?.setOnClickListener {
            applyUnifiedTransport(TransportChoice.WIFI_P2P, view)
        }
        view.findViewById<View>(R.id.mode_btn_hotspot)?.setOnClickListener {
            applyUnifiedTransport(TransportChoice.CAR_HOTSPOT, view)
        }
        view.findViewById<View>(R.id.mode_btn_usb)?.setOnClickListener {
            applyUnifiedTransport(TransportChoice.USB_ONLY, view)
        }

        // Warning banner CTA
        view.findViewById<View>(R.id.wifi_conflict_action)?.setOnClickListener {
            if (isCarHotspotSelected() && CarHotspotStatus.isEnabled(requireContext()) == false) {
                openCarHotspotSettings()
            } else {
                val enabledDirectly = ensureCarWifiEnabled()
                if (!enabledDirectly) {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) }
                        .onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) } }
                }
            }
            refreshAll(view)
        }

        refreshAll(view)

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
                        if (isAdded) refreshHeroState(view)
                    }
                }
                launch {
                    AapService.wifiDirectChannelInfo.collect {
                        if (isAdded) refreshHeroState(view)
                    }
                }
                launch {
                    AapService.wifiCoexistenceWarning.collect {
                        if (isAdded) updateConnectionModeButtons(view)
                    }
                }
                launch {
                    app.commManager.connectionState.collect { state ->
                        when (state) {
                            is CommManager.ConnectionState.TransportStarted -> {
                                stopConnectingUi()
                                activeProtocol = Protocol.ANDROID_AUTO
                                refreshAll(view)
                            }
                            is CommManager.ConnectionState.Disconnected -> {
                                refreshAll(view)
                            }
                            else -> {
                                if (!manualConnectingActive && activeProtocol == Protocol.ANDROID_AUTO) {
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
        val actIntent = activity?.intent
        if (actIntent != null) {
            when (actIntent.getStringExtra("extra_home_protocol")) {
                "carplay" -> {
                    activeProtocol = Protocol.CARPLAY
                    app.settings.lastHomeProtocol = "carplay"
                    actIntent.removeExtra("extra_home_protocol")
                }
                "aa" -> {
                    activeProtocol = Protocol.ANDROID_AUTO
                    app.settings.lastHomeProtocol = "aa"
                    actIntent.removeExtra("extra_home_protocol")
                }
            }
            if (actIntent.getBooleanExtra("extra_carplay_recovery", false)) {
                actIntent.removeExtra("extra_carplay_recovery")
                confirmCarPlayWifiReset()
            }
        }

        // If a CarPlay session is active in background, keep ring ticking smoothly
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.active && !manualConnectingActive) {
            view?.let { v ->
                val name = LeapCarPlayPreferences.phoneName(requireContext()).ifBlank { "iPhone" }
                beginConnectingUi(v, name, "Apple CarPlay", getString(R.string.core_transport_opening_cp))
            }
        }

        Settings.setUsbAttachedActivityEnabled(requireContext(), app.settings.usbModeEnabled)
        syncSharedHotspotCredentials()
        view?.let { refreshAll(it) }
    }

    private enum class TransportChoice { WIFI_P2P, CAR_HOTSPOT, USB_ONLY }

    private fun currentTransportChoice(): TransportChoice {
        val s = app.settings
        return when {
            activeProtocol == Protocol.CARPLAY -> {
                when {
                    !AirPlayPersistence.loadWirelessEnabled(requireContext()) -> TransportChoice.USB_ONLY
                    AirPlayPersistence.loadWirelessHotspotMode(requireContext()) == WirelessHotspotMode.MANUAL -> TransportChoice.CAR_HOTSPOT
                    else -> TransportChoice.WIFI_P2P
                }
            }
            s.wifi5gEnabled && s.nativeApTransport == 0 -> TransportChoice.WIFI_P2P
            s.hotspotModeEnabled || (s.wifiConnectionMode == 3 && s.nativeApTransport == 1) -> TransportChoice.CAR_HOTSPOT
            else -> TransportChoice.USB_ONLY
        }
    }

    private fun isCarHotspotSelected(): Boolean = currentTransportChoice() == TransportChoice.CAR_HOTSPOT

    private fun applyUnifiedTransport(choice: TransportChoice, view: View) {
        val ctx = requireContext()
        val s = app.settings
        when (choice) {
            TransportChoice.WIFI_P2P -> {
                s.nativeApTransport = 0
                s.wifiConnectionMode = 3
                s.wifi5gEnabled = true
                s.hotspotModeEnabled = false
                s.usbModeEnabled = true
                s.listenForUsbDevices = true
                Settings.setUsbAttachedActivityEnabled(ctx, true)
                AirPlayPersistence.saveWirelessHotspotMode(ctx, WirelessHotspotMode.WIFI_P2P)
                AirPlayPersistence.saveWirelessEnabled(ctx, true)
                if (activeProtocol == Protocol.ANDROID_AUTO) {
                    stopCarPlayForAndroidAuto()
                    ensureCarWifiEnabled()
                    ContextCompat.startForegroundService(ctx, Intent(ctx, AapService::class.java).apply {
                        action = AapService.ACTION_START_WIRELESS
                    })
                }
                refreshAll(view)
            }
            TransportChoice.CAR_HOTSPOT -> {
                syncSharedHotspotCredentials()
                val ssid = sharedHotspotSsid()
                val pass = sharedHotspotPassword()
                if (ManualHotspotValidation.validate(ssid, pass) != null) {
                    showHotspotCredentialsDialog(ssid, pass) { newSsid, newPass ->
                        saveSharedHotspotCredentials(newSsid, newPass)
                        commitCarHotspotMode(view)
                    }
                } else {
                    commitCarHotspotMode(view)
                }
            }
            TransportChoice.USB_ONLY -> {
                s.wifi5gEnabled = false
                s.hotspotModeEnabled = false
                s.wifiConnectionMode = 0
                s.usbModeEnabled = true
                s.listenForUsbDevices = true
                Settings.setUsbAttachedActivityEnabled(ctx, true)
                AirPlayPersistence.saveWirelessEnabled(ctx, false)
                if (app.commManager.isConnected && app.commManager.isWirelessSession) {
                    app.commManager.disconnect(sendByeBye = true, isUserExit = true)
                }
                stopConnectingUi()
                if (AapService.isServiceRunning()) {
                    runCatching {
                        ctx.startService(Intent(ctx, AapService::class.java).apply {
                            action = AapService.ACTION_STOP_WIRELESS
                        })
                    }
                }
                refreshAll(view)
            }
        }
    }

    private fun commitCarHotspotMode(view: View) {
        val ctx = requireContext()
        val s = app.settings
        s.nativeApTransport = 1
        s.wifiConnectionMode = 3
        s.wifi5gEnabled = false
        s.hotspotModeEnabled = true
        s.usbModeEnabled = true
        s.listenForUsbDevices = true
        Settings.setUsbAttachedActivityEnabled(ctx, true)
        AirPlayPersistence.saveWirelessHotspotMode(ctx, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveWirelessEnabled(ctx, true)
        if (activeProtocol == Protocol.ANDROID_AUTO) {
            stopCarPlayForAndroidAuto()
            ContextCompat.startForegroundService(ctx, Intent(ctx, AapService::class.java).apply {
                action = AapService.ACTION_START_WIRELESS
            })
        }
        refreshAll(view)
    }

    private fun syncSharedHotspotCredentials() {
        val ctx = context ?: return
        val s = app.settings
        val aaSsid = s.hotspotSsid.trim()
        val aaPass = s.hotspotPassword
        val cpSsid = AirPlayPersistence.loadManualHotspotSsid(ctx).trim()
        val cpPass = AirPlayPersistence.loadManualHotspotPassphrase(ctx)
        if (aaSsid.isNotEmpty() && cpSsid.isEmpty()) {
            saveSharedHotspotCredentials(aaSsid, aaPass)
        } else if (cpSsid.isNotEmpty() && aaSsid.isEmpty()) {
            s.hotspotSsid = cpSsid
            s.hotspotPassword = cpPass
        }
    }

    private fun sharedHotspotSsid(): String =
        app.settings.hotspotSsid.trim().ifEmpty { AirPlayPersistence.loadManualHotspotSsid(requireContext()).trim() }

    private fun sharedHotspotPassword(): String =
        app.settings.hotspotPassword.ifEmpty { AirPlayPersistence.loadManualHotspotPassphrase(requireContext()) }

    private fun saveSharedHotspotCredentials(ssid: String, password: String) {
        val ctx = requireContext()
        app.settings.hotspotSsid = ssid
        app.settings.hotspotPassword = password
        AirPlayPersistence.saveManualHotspotSsid(ctx, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(ctx, password)
        AirPlayPersistence.saveManualHotspotSecurity(ctx, ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(ctx, ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(ctx, 0)
    }

    private fun showHotspotCredentialsDialog(
        currentSsid: String,
        currentPassword: String,
        onSaved: (String, String) -> Unit,
    ) {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(8))
        }
        box.addView(TextView(ctx).apply {
            text = getString(R.string.cp_hotspot_desc)
            textSize = 15f
            setTextColor(TEXT_DIM)
            setPadding(0, 0, 0, dp(12))
        })
        val ssidInput = EditText(ctx).apply {
            hint = getString(R.string.setup_hotspot_name)
            setText(currentSsid)
            setSingleLine()
            setTextColor(TEXT_MAIN)
            setHintTextColor(TEXT_FAINT)
        }
        val passInput = EditText(ctx).apply {
            hint = getString(R.string.setup_hotspot_password)
            setText(currentPassword)
            setSingleLine()
            setTextColor(TEXT_MAIN)
            setHintTextColor(TEXT_FAINT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val showPass = CheckBox(ctx).apply {
            text = getString(R.string.setup_show_password)
            setTextColor(TEXT_DIM)
            setOnCheckedChangeListener { _, checked ->
                passInput.transformationMethod =
                    if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                passInput.setSelection(passInput.text.length)
            }
        }
        box.addView(ssidInput)
        box.addView(passInput)
        box.addView(showPass)

        activeDialog?.dismiss()
        val dlg = MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
            .setTitle(R.string.setup_car_hotspot_details)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.cp_btn_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ssid = ssidInput.text.toString().trim()
                val pass = passInput.text.toString()
                val err = ManualHotspotValidation.validate(ssid, pass)
                if (err != null) {
                    Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dlg.dismiss()
                onSaved(ssid, pass)
            }
        }
        activeDialog = dlg
        dlg.show()
    }

    private fun selectProtocol(protocol: Protocol, view: View) {
        if (activeProtocol == protocol) return
        activeProtocol = protocol
        app.settings.lastHomeProtocol = if (protocol == Protocol.CARPLAY) "carplay" else "aa"
        refreshAll(view)
    }

    private fun refreshAll(view: View) {
        updateHeaderControls(view)
        updateProtocolTabs(view)
        updateConnectionModeButtons(view)
        rebuildRecentDevicesRow(view)
        refreshHeroState(view)
    }

    private fun updateHeaderControls(view: View) {
        // Hardware readiness dots (BT · Wi-Fi · USB)
        val btAdapter = BluetoothHelper.getBluetoothAdapter(requireContext())
        val btReady = runCatching { btAdapter?.isEnabled == true }.getOrDefault(false)
        val wm = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiReady = wm?.isWifiEnabled == true
        val usbPhone = findConnectedUsbPhone()

        view.findViewById<TextView>(R.id.hw_dot_bt)?.setTextColor(if (btReady) OK_GREEN else TEXT_FAINT)
        view.findViewById<TextView>(R.id.hw_dot_wifi)?.setTextColor(if (wifiReady) OK_GREEN else TEXT_FAINT)
        view.findViewById<TextView>(R.id.hw_dot_usb)?.apply {
            text = if (usbPhone != null) "● USB (${shortUsbDeviceLabel(usbPhone.first, usbPhone.second)})" else "● USB"
            setTextColor(if (usbPhone != null) ACCENT else TEXT_FAINT)
        }

        // Car bar quick toggle
        val barsVisible = app.settings.fullscreenMode == Settings.FullscreenMode.NONE
        view.findViewById<MaterialButton>(R.id.car_bar_toggle_button)?.apply {
            text = getString(if (barsVisible) R.string.qu_home_bar_on else R.string.qu_home_bar_off)
            backgroundTintList = ColorStateList.valueOf(if (barsVisible) ACCENT_DARK_BG else SURFACE_HI)
            strokeColor = ColorStateList.valueOf(if (barsVisible) ACCENT else OUTLINE_STRONG)
            setTextColor(if (barsVisible) ACCENT else TEXT_MAIN)
        }

        // Cluster button: show ONLY when a 2nd display exists OR user enabled it in Settings
        val dm = requireContext().getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val hasSecondaryDisplay = runCatching { (dm?.displays?.size ?: 1) > 1 }.getOrDefault(false)
        val clusterEnabled = app.settings.clusterMapEnabled
        view.findViewById<MaterialButton>(R.id.cluster_toggle_button)?.apply {
            visibility = if (hasSecondaryDisplay || clusterEnabled) View.VISIBLE else View.GONE
            text = getString(if (clusterEnabled) R.string.qu_cluster_short_on else R.string.qu_cluster_short_off)
            backgroundTintList = ColorStateList.valueOf(if (clusterEnabled) ACCENT_DARK_BG else SURFACE_HI)
            strokeColor = ColorStateList.valueOf(if (clusterEnabled) ACCENT else OUTLINE_STRONG)
            setTextColor(if (clusterEnabled) ACCENT else TEXT_MAIN)
        }
    }

    private fun updateProtocolTabs(view: View) {
        val aaTab = view.findViewById<MaterialButton>(R.id.proto_tab_aa)
        val cpTab = view.findViewById<MaterialButton>(R.id.proto_tab_cp)
        val isAa = activeProtocol == Protocol.ANDROID_AUTO

        fun styleTab(btn: MaterialButton?, active: Boolean, tintIcon: Boolean) {
            btn ?: return
            btn.backgroundTintList = ColorStateList.valueOf(if (active) ACCENT_DARK_BG else Color.TRANSPARENT)
            btn.strokeColor = ColorStateList.valueOf(if (active) ACCENT else Color.TRANSPARENT)
            btn.setTextColor(if (active) ACCENT else TEXT_DIM)
            if (tintIcon) {
                btn.iconTint = ColorStateList.valueOf(if (active) ACCENT else TEXT_DIM)
            }
        }
        styleTab(aaTab, isAa, tintIcon = true)
        styleTab(cpTab, !isAa, tintIcon = false)
    }

    private fun onPrimaryConnectTap(view: View) {
        if (activeProtocol == Protocol.CARPLAY) {
            if (CarPlayBackgroundSession.hasSession()) {
                openCarPlayHost()
                return
            }
            val wireless = currentTransportChoice() != TransportChoice.USB_ONLY
            if (wireless) {
                val missing = missingWirelessPermissions()
                if (missing.isNotEmpty()) {
                    wirelessPermissions.launch(missing)
                    return
                }
            }
            startCarPlayConnection(wireless = wireless)
        } else {
            if (app.commManager.isConnected) {
                openAaProjection()
                return
            }
            val wireless = currentTransportChoice() != TransportChoice.USB_ONLY
            if (wireless) {
                val missing = missingWirelessPermissions()
                if (missing.isNotEmpty()) {
                    wirelessPermissions.launch(missing)
                    return
                }
            }
            startPrimaryAaConnection()
        }
    }

    private fun startCarPlayConnection(
        wireless: Boolean,
        usbDevice: UsbDevice? = null,
    ) {
        val ctx = requireContext()
        val root = view ?: return
        val bootErr = runCatching { LeapCarPlayBootstrap.ensure(ctx) }.exceptionOrNull()
        if (bootErr != null) {
            Toast.makeText(ctx, getString(R.string.cp_setup_error), Toast.LENGTH_LONG).show()
            return
        }

        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(ctx) == WirelessHotspotMode.MANUAL &&
            CarHotspotStatus.isEnabled(ctx) == false
        ) {
            activeDialog?.dismiss()
            activeDialog = MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
                .setTitle(R.string.cp_dlg_hotspot_off_title)
                .setMessage(getString(R.string.cp_dlg_hotspot_off_msg, sharedHotspotSsid()))
                .setPositiveButton(R.string.cp_dlg_hotspot_open_settings) { _, _ -> openCarHotspotSettings() }
                .setNeutralButton(R.string.cp_dlg_hotspot_connect_anyway) { _, _ -> launchCarPlayHostNow(root, wireless = true) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        if (wireless && LeapCarPlayPreferences.phoneAddress(ctx) == null) {
            pendingCarPlayWireless = true
            showCarPlayPhonePicker()
            return
        }

        launchCarPlayHostNow(root, wireless = wireless, usbDevice = usbDevice)
    }

    private fun launchCarPlayHostNow(
        root: View,
        wireless: Boolean,
        usbDevice: UsbDevice? = null,
    ) {
        val ctx = requireContext()
        val targetName = if (usbDevice != null) {
            "iPhone (USB)"
        } else {
            LeapCarPlayPreferences.phoneName(ctx).ifBlank { "iPhone" }
        }
        stopConnectingUi()
        beginConnectingUi(
            view = root,
            targetName = targetName,
            protocol = "Apple CarPlay",
            transportLabel = getString(if (usbDevice != null || !wireless) R.string.core_transport_opening_cp_usb else R.string.core_transport_opening_cp),
        )

        carPlayLaunchJob?.cancel()
        carPlayLaunchJob = viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AapService.releaseWirelessForHandoff(1200L)
            }
            if (!isAdded) return@launch
            AirPlayPersistence.saveWirelessEnabled(ctx, wireless)
            val openHost = {
                if (isAdded) {
                    stopConnectingUi()
                    openCarPlayHost(usbDevice)
                }
            }
            if (CarPlayBackgroundSession.hasSession()) {
                CarPlayBackgroundSession.stop { activity?.runOnUiThread { openHost() } }
            } else {
                openHost()
            }
        }
    }

    private fun openCarPlayHost(usbDevice: UsbDevice? = null) {
        val ctx = requireContext()
        val intent = if (usbDevice != null) {
            Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply {
                setClass(ctx, CarPlayHostActivity::class.java)
                putExtra(UsbManager.EXTRA_DEVICE, usbDevice)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        } else {
            Intent(ctx, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
    }

    private fun showCarPlayPhonePicker() {
        val ctx = requireContext()
        val missing = missingWirelessPermissions()
        if (missing.isNotEmpty()) {
            wirelessPermissions.launch(missing)
            return
        }
        val adapter = BluetoothHelper.getBluetoothAdapter(ctx)
        if (adapter == null || !adapter.isEnabled) {
            activeDialog?.dismiss()
            activeDialog = MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
                .setTitle(R.string.cp_dlg_bt_on_title)
                .setMessage(R.string.cp_dlg_bt_on_msg)
                .setPositiveButton(R.string.cp_dlg_open_bt) { _, _ -> openBluetoothSettings() }
                .setNegativeButton(R.string.cp_btn_later, null)
                .show()
            return
        }
        val savedAddr = LeapCarPlayPreferences.phoneAddress(ctx)
        val devices = loadBondedDevicesSorted(forCarPlay = true, savedCpAddr = savedAddr)
        if (devices.isEmpty()) {
            activeDialog?.dismiss()
            activeDialog = MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
                .setTitle(R.string.cp_dlg_pair_title)
                .setMessage(R.string.cp_dlg_pair_msg)
                .setPositiveButton(R.string.cp_dlg_open_bt) { _, _ -> openBluetoothSettings() }
                .setNegativeButton(R.string.cp_btn_got_it, null)
                .show()
            return
        }
        val items = devices.mapIndexed { idx, dev ->
            PhonePickerDialog.PhoneItem(
                name = runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "iPhone",
                address = dev.address,
                highlighted = if (savedAddr != null) dev.address == savedAddr else idx == 0,
                device = dev,
            )
        }
        activeDialog?.dismiss()
        activeDialog = PhonePickerDialog.show(
            context = ctx,
            title = getString(R.string.cp_picker_title),
            subtitle = getString(R.string.cp_picker_sub),
            items = items,
            pairButtonText = getString(R.string.cp_picker_pair),
            cancelButtonText = getString(R.string.cp_btn_cancel),
            onSelect = { index, _ ->
                val dev = devices[index]
                LeapCarPlayPreferences.savePhone(ctx, dev.address, runCatching { dev.name }.getOrNull() ?: "iPhone")
                val shouldStart = pendingCarPlayWireless
                pendingCarPlayWireless = false
                view?.let { refreshAll(it) }
                if (shouldStart) view?.let { launchCarPlayHostNow(it, wireless = true) }
            },
            onPairNew = { openBluetoothSettings() },
            onCancel = { pendingCarPlayWireless = false },
        )
    }

    private fun confirmCarPlayWifiReset() {
        val ctx = requireContext()
        activeDialog?.dismiss()
        activeDialog = MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
            .setTitle(R.string.cp_dlg_reset_confirm_title)
            .setMessage(R.string.cp_dlg_reset_confirm_msg)
            .setPositiveButton(R.string.cp_dlg_reset_confirm_ok) { _, _ ->
                CarPlayBackgroundSession.stop {
                    activity?.runOnUiThread {
                        if (isAdded) resetCarPlayP2pGroup()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun resetCarPlayP2pGroup() {
        val ctx = requireContext()
        val manager = ctx.getSystemService(WifiP2pManager::class.java)
        if (manager == null) {
            Toast.makeText(ctx, getString(R.string.cp_toast_no_p2p), Toast.LENGTH_LONG).show()
            return
        }
        val channel = manager.initialize(ctx, ctx.mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) {
                    channel.close()
                    startCarPlayConnection(wireless = true)
                    return@requestGroupInfo
                }
                manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        channel.close()
                        Toast.makeText(ctx, getString(R.string.qu_home_reset_wifi_toast), Toast.LENGTH_SHORT).show()
                        startCarPlayConnection(wireless = true)
                    }
                    override fun onFailure(reason: Int) {
                        channel.close()
                        Toast.makeText(ctx, getString(R.string.cp_toast_p2p_fail), Toast.LENGTH_LONG).show()
                    }
                })
            }
        } catch (_: SecurityException) {
            channel.close()
            Toast.makeText(ctx, getString(R.string.core_toast_location_p2p), Toast.LENGTH_LONG).show()
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
            openAaProjection()
            return
        }
        if (app.commManager.isConnected && app.commManager.isWirelessSession) {
            showUsbSwitchDialog(getString(R.string.core_btn_usb_cable))
            return
        }
        app.settings.usbModeEnabled = true
        app.settings.listenForUsbDevices = true
        Settings.setUsbAttachedActivityEnabled(requireContext(), true)

        val usbPhone = findConnectedUsbPhone()
        if (usbPhone != null && usbPhone.second) {
            activeProtocol = Protocol.CARPLAY
            startCarPlayConnection(wireless = false, usbDevice = usbPhone.first)
            return
        }

        stopCarPlayForAndroidAuto()
        if (usbPhone == null) {
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_CHECK_USB
            })
            Toast.makeText(requireContext(), getString(R.string.core_toast_plug_usb), Toast.LENGTH_LONG).show()
            refreshAll(view)
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

    private fun stopCarPlayForAndroidAuto() {
        val ctx = context ?: return
        runCatching { CarPlayBackgroundSession.stop() }
        runCatching { ctx.stopService(Intent(ctx, LeapCarPlaySessionService::class.java)) }
        runCatching { ctx.stopService(Intent(ctx, CarPlayVpnService::class.java)) }
    }

    private fun currentTransportConnectingLabel(): String = when (currentTransportChoice()) {
        TransportChoice.WIFI_P2P -> getString(R.string.core_transport_connecting_wifi)
        TransportChoice.CAR_HOTSPOT -> getString(R.string.core_transport_connecting_hotspot)
        TransportChoice.USB_ONLY -> getString(R.string.core_transport_connecting_usb)
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
        if (AapService.isServiceRunning()) {
            runCatching {
                requireContext().startService(Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_WIRELESS
                })
            }
        }
        refreshHeroState(view)
    }

    private fun refreshHeroState(view: View) {
        val pill = view.findViewById<TextView>(R.id.hero_status_pill)
        val badge = view.findViewById<TextView>(R.id.hero_transport_badge)
        val resetWifiBtn = view.findViewById<View>(R.id.hero_reset_wifi_btn)
        val title = view.findViewById<TextView>(R.id.connection_status)
        val sub = view.findViewById<TextView>(R.id.hero_subtitle_text)
        val primaryBtn = view.findViewById<MaterialButton>(R.id.wifi_button)
        val usbBtn = view.findViewById<MaterialButton>(R.id.usb_button)
        val cancelBtn = view.findViewById<MaterialButton>(R.id.hero_cancel_button)
        val ring = view.findViewById<ConnectionRingView>(R.id.connection_ring_view)

        val choice = currentTransportChoice()
        val usbPhone = findConnectedUsbPhone()
        val channelInfo = AapService.wifiDirectChannelInfo.value

        resetWifiBtn?.visibility = if (choice == TransportChoice.WIFI_P2P && !manualConnectingActive) View.VISIBLE else View.GONE

        if (activeProtocol == Protocol.CARPLAY) {
            val cpRunning = CarPlayBackgroundSession.hasSession()
            val cpActive = CarPlayBackgroundSession.active
            val savedAddr = LeapCarPlayPreferences.phoneAddress(requireContext())
            val savedName = if (savedAddr != null) LeapCarPlayPreferences.phoneName(requireContext()) else null
            val appleUsb = usbPhone?.takeIf { it.second }?.first

            primaryBtn?.setIconResource(R.drawable.ic_carplay)
            primaryBtn?.iconTint = null

            when {
                cpActive -> {
                    pill?.setTextColor(OK_GREEN)
                    pill?.text = getString(R.string.core_status_connected)
                    badge?.setTextColor(OK_GREEN)
                    badge?.text = when (choice) {
                        TransportChoice.CAR_HOTSPOT -> getString(R.string.cp_badge_hotspot_active)
                        TransportChoice.WIFI_P2P -> getString(R.string.cp_badge_p2p_active)
                        TransportChoice.USB_ONLY -> getString(R.string.cp_badge_usb_active)
                    }
                    title?.text = if (savedName != null) getString(R.string.cp_hero_title_connected_to, savedName)
                        else getString(R.string.cp_hero_title_connected)
                    sub?.text = getString(R.string.cp_hero_sub_connected)
                    primaryBtn?.visibility = View.VISIBLE
                    primaryBtn?.text = getString(R.string.cp_btn_open)
                    usbBtn?.visibility = View.GONE
                    cancelBtn?.visibility = View.VISIBLE
                    cancelBtn?.text = getString(R.string.cp_btn_disconnect)

                    ring?.state = ConnectionRingView.State.CONNECTED
                    ring?.protocolTitle = "Apple CarPlay"
                    ring?.subtitleText = getString(R.string.core_ring_projecting)
                }
                cpRunning || manualConnectingActive -> {
                    val elapsed = ((SystemClock.elapsedRealtime() - connectingStartElapsedMs) / 1000L).toInt().coerceAtLeast(0)
                    pill?.setTextColor(ACCENT)
                    pill?.text = getString(R.string.core_status_connecting)
                    badge?.setTextColor(ACCENT)
                    badge?.text = when (choice) {
                        TransportChoice.CAR_HOTSPOT -> getString(R.string.cp_badge_conn_hotspot)
                        TransportChoice.WIFI_P2P -> getString(R.string.cp_badge_conn_p2p)
                        TransportChoice.USB_ONLY -> getString(R.string.cp_badge_conn_usb)
                    }
                    title?.text = getString(R.string.cp_hero_title_connecting, savedName ?: "iPhone")
                    sub?.text = getString(R.string.cp_hero_sub_connecting)
                    primaryBtn?.visibility = if (cpRunning) View.VISIBLE else View.GONE
                    primaryBtn?.text = getString(R.string.cp_btn_open)
                    usbBtn?.visibility = View.GONE
                    cancelBtn?.visibility = View.VISIBLE
                    cancelBtn?.text = getString(R.string.core_btn_cancel_conn)

                    ring?.state = ConnectionRingView.State.CONNECTING
                    ring?.elapsedSeconds = elapsed
                    ring?.activeStep = if (elapsed < 3) 1 else if (elapsed < 7) 2 else 3
                    ring?.protocolTitle = "Apple CarPlay"
                    ring?.subtitleText = getString(if (elapsed < 4) R.string.core_ring_negotiating else R.string.cp_ring_waiting_iphone)
                }
                else -> {
                    pill?.setTextColor(ACCENT)
                    pill?.text = getString(R.string.core_status_ready)
                    badge?.setTextColor(if (appleUsb != null) ACCENT else TEXT_MAIN)
                    badge?.text = when {
                        appleUsb != null -> getString(R.string.cp_badge_usb_detected)
                        choice == TransportChoice.CAR_HOTSPOT -> getString(R.string.cp_badge_hotspot)
                        choice == TransportChoice.USB_ONLY -> getString(R.string.core_badge_usb_ready)
                        else -> getString(R.string.cp_badge_p2p)
                    }
                    title?.text = if (savedName != null) getString(R.string.cp_hero_title_ready_for, savedName)
                        else getString(R.string.cp_hero_title_idle)
                    sub?.text = when {
                        appleUsb != null -> getString(R.string.cp_hero_sub_usb)
                        savedName != null -> getString(R.string.cp_hero_sub_ready_named)
                        else -> getString(R.string.cp_hero_sub_idle)
                    }
                    primaryBtn?.visibility = View.VISIBLE
                    primaryBtn?.text = getString(if (choice == TransportChoice.USB_ONLY) R.string.core_btn_usb_carplay else R.string.cp_btn_connect)
                    val showUsb = choice != TransportChoice.USB_ONLY
                    usbBtn?.visibility = if (showUsb) View.VISIBLE else View.GONE
                    styleSecondaryUsbButton(usbBtn, detected = appleUsb != null, isApple = true)
                    cancelBtn?.visibility = View.GONE

                    ring?.state = ConnectionRingView.State.IDLE
                    ring?.protocolTitle = if (appleUsb != null) getString(R.string.core_btn_usb_carplay) else "Apple CarPlay"
                    ring?.subtitleText = getString(if (appleUsb != null) R.string.cp_ring_iphone_ready else R.string.core_ring_tap_connect)
                }
            }
            return
        }

        // ANDROID AUTO HERO STATE
        val s = app.settings
        primaryBtn?.setIconResource(R.drawable.ic_android_auto)
        primaryBtn?.iconTint = ColorStateList.valueOf(ON_ACCENT)

        when {
            app.commManager.isConnected -> {
                val devName = s.autoStartBluetoothDeviceName.takeIf { it.isNotBlank() } ?: getString(R.string.core_your_phone)
                pill?.setTextColor(OK_GREEN)
                pill?.text = getString(R.string.core_status_connected)
                badge?.setTextColor(OK_GREEN)
                badge?.text = if (app.commManager.isWirelessSession) {
                    if (choice == TransportChoice.CAR_HOTSPOT) getString(R.string.core_badge_hotspot_connected)
                    else channelInfo ?: getString(R.string.core_badge_wifi5g_connected)
                } else {
                    getString(R.string.core_badge_usb_connected)
                }
                title?.text = getString(R.string.core_hero_title_connected, devName)
                sub?.text = getString(R.string.core_hero_sub_connected)
                primaryBtn?.visibility = View.VISIBLE
                primaryBtn?.text = getString(R.string.da_open)
                usbBtn?.visibility = View.GONE
                cancelBtn?.visibility = View.VISIBLE
                cancelBtn?.text = getString(R.string.cp_btn_disconnect)

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
                pill?.setTextColor(ACCENT)
                pill?.text = getString(R.string.core_status_connecting)
                val liveStatus = AapService.wirelessStatusText.value
                badge?.setTextColor(ACCENT)
                badge?.text = channelInfo ?: connectingTransportLabel
                title?.text = getString(R.string.core_hero_title_connecting, connectingTargetName)
                sub?.text = liveStatus ?: getString(R.string.core_hero_sub_connecting)
                primaryBtn?.visibility = View.GONE
                usbBtn?.visibility = View.GONE
                cancelBtn?.visibility = View.VISIBLE
                cancelBtn?.text = getString(R.string.core_btn_cancel_conn)

                ring?.state = ConnectionRingView.State.CONNECTING
                ring?.elapsedSeconds = elapsedSec
                ring?.protocolTitle = connectingProtocol
                ring?.activeStep = if (elapsedSec < 3) 1 else if (elapsedSec < 7) 2 else 3
                val credsText = getString(R.string.core_live_creds_sent)
                val btNegText = getString(R.string.core_live_bt_negotiating)
                ring?.subtitleText = when {
                    liveStatus == credsText -> getString(R.string.core_ring_opening_session)
                    liveStatus == btNegText -> getString(R.string.core_ring_negotiating_wifi)
                    elapsedSec < 3 -> getString(R.string.core_ring_negotiating)
                    elapsedSec < 8 -> getString(R.string.core_ring_waiting_phone)
                    else -> getString(R.string.core_ring_opening_session)
                }
            }
            else -> {
                val liveStatus = AapService.wirelessStatusText.value
                pill?.setTextColor(ACCENT)
                pill?.text = getString(R.string.core_status_ready)
                badge?.setTextColor(if (usbPhone != null) ACCENT else TEXT_MAIN)
                badge?.text = when {
                    usbPhone != null -> getString(R.string.core_badge_usb_detected, shortUsbDeviceLabel(usbPhone.first, usbPhone.second))
                    choice == TransportChoice.WIFI_P2P && !channelInfo.isNullOrBlank() -> "5 GHz · $channelInfo"
                    choice == TransportChoice.WIFI_P2P -> getString(R.string.core_badge_wifi5g_ready)
                    choice == TransportChoice.CAR_HOTSPOT -> getString(R.string.core_badge_hotspot_ready)
                    else -> getString(R.string.core_badge_usb_ready)
                }
                title?.text = getString(R.string.core_hero_title_idle)
                sub?.text = when {
                    usbPhone != null && usbPhone.second -> getString(R.string.core_hero_sub_usb_iphone)
                    usbPhone != null -> getString(R.string.core_hero_sub_usb_android, shortUsbDeviceLabel(usbPhone.first, false))
                    choice == TransportChoice.WIFI_P2P && !liveStatus.isNullOrBlank() -> liveStatus
                    else -> getString(R.string.core_hero_sub_idle)
                }
                primaryBtn?.visibility = View.VISIBLE
                primaryBtn?.text = getString(
                    if (choice == TransportChoice.USB_ONLY) R.string.core_btn_connect_usb_primary else R.string.core_btn_connect_aa
                )
                // Contextual USB button: show ONLY if a USB phone is physically attached (or if wireless is on and USB detected)
                usbBtn?.visibility = if (usbPhone != null && choice != TransportChoice.USB_ONLY) View.VISIBLE else View.GONE
                if (usbPhone != null) {
                    styleSecondaryUsbButton(usbBtn, detected = true, isApple = usbPhone.second)
                }
                cancelBtn?.visibility = View.GONE

                ring?.state = ConnectionRingView.State.IDLE
                ring?.protocolTitle = when {
                    usbPhone != null -> getString(if (usbPhone.second) R.string.core_btn_usb_carplay else R.string.core_btn_usb_cable)
                    choice == TransportChoice.WIFI_P2P -> "Android Auto"
                    choice == TransportChoice.CAR_HOTSPOT -> getString(R.string.qu_home_mode_hotspot)
                    else -> getString(R.string.core_btn_usb_cable)
                }
                ring?.subtitleText = getString(
                    if (usbPhone != null) R.string.core_ring_usb_ready else R.string.core_ring_tap_connect
                )
            }
        }
    }

    private fun styleSecondaryUsbButton(btn: MaterialButton?, detected: Boolean, isApple: Boolean) {
        btn ?: return
        if (detected) {
            btn.text = getString(if (isApple) R.string.core_btn_usb_carplay else R.string.core_btn_usb_connect)
            btn.backgroundTintList = ColorStateList.valueOf(ACCENT_DARK_BG)
            btn.strokeColor = ColorStateList.valueOf(ACCENT)
            btn.setTextColor(ACCENT)
        } else {
            btn.text = getString(R.string.core_btn_usb_cable)
            btn.backgroundTintList = ColorStateList.valueOf(SURFACE_TOP)
            btn.strokeColor = ColorStateList.valueOf(OUTLINE_STRONG)
            btn.setTextColor(TEXT_MAIN)
        }
    }

    private fun updateConnectionModeButtons(view: View) {
        val choice = currentTransportChoice()
        val wm = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiEnabled = wm?.isWifiEnabled == true
        val coexWarning = AapService.wifiCoexistenceWarning.value
        val carHotspotOff = choice == TransportChoice.CAR_HOTSPOT && CarHotspotStatus.isEnabled(requireContext()) == false

        val banner = view.findViewById<View>(R.id.wifi_conflict_banner)
        val bannerText = view.findViewById<TextView>(R.id.wifi_conflict_text)
        val bannerAction = view.findViewById<MaterialButton>(R.id.wifi_conflict_action)
        when {
            choice == TransportChoice.WIFI_P2P && !wifiEnabled -> {
                banner?.visibility = View.VISIBLE
                bannerText?.text = getString(R.string.core_wifi_off_banner)
                bannerAction?.visibility = View.VISIBLE
                bannerAction?.text = getString(R.string.core_wifi_turn_on)
            }
            carHotspotOff -> {
                banner?.visibility = View.VISIBLE
                bannerText?.text = getString(R.string.cp_hotspot_off_banner, sharedHotspotSsid())
                bannerAction?.visibility = View.VISIBLE
                bannerAction?.text = getString(R.string.cp_hotspot_open_btn)
            }
            choice == TransportChoice.WIFI_P2P && !coexWarning.isNullOrBlank() -> {
                banner?.visibility = View.VISIBLE
                bannerText?.text = getString(R.string.qu_home_coex_warn)
                bannerAction?.visibility = View.GONE
            }
            else -> {
                banner?.visibility = View.GONE
            }
        }

        fun styleTransportPill(btn: MaterialButton?, active: Boolean, label: String) {
            btn ?: return
            btn.text = if (active) "●  $label" else label
            if (active) {
                btn.backgroundTintList = ColorStateList.valueOf(ACCENT_DARK_BG)
                btn.setTextColor(ACCENT)
                btn.strokeColor = ColorStateList.valueOf(ACCENT)
                btn.strokeWidth = dp(2)
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(SURFACE_HI)
                btn.setTextColor(TEXT_DIM)
                btn.strokeColor = ColorStateList.valueOf(OUTLINE)
                btn.strokeWidth = dp(1)
            }
        }

        styleTransportPill(
            view.findViewById(R.id.mode_btn_wifi_direct),
            choice == TransportChoice.WIFI_P2P,
            getString(R.string.qu_home_mode_p2p),
        )
        val hotspotTitle = if (choice == TransportChoice.CAR_HOTSPOT && sharedHotspotSsid().isNotBlank()) {
            "${getString(R.string.qu_home_mode_hotspot)} · ${sharedHotspotSsid().take(12)}"
        } else {
            getString(R.string.qu_home_mode_hotspot)
        }
        styleTransportPill(
            view.findViewById(R.id.mode_btn_hotspot),
            choice == TransportChoice.CAR_HOTSPOT,
            hotspotTitle,
        )
        styleTransportPill(
            view.findViewById(R.id.mode_btn_usb),
            choice == TransportChoice.USB_ONLY,
            getString(R.string.qu_home_mode_usb_only),
        )

        val explanation = when (choice) {
            TransportChoice.WIFI_P2P -> "${getString(R.string.qu_home_mode_p2p)} · ${getString(R.string.qu_home_mode_p2p_sub)} · ${getString(R.string.qu_home_usb_always_ready)}"
            TransportChoice.CAR_HOTSPOT -> "${getString(R.string.qu_home_mode_hotspot)} (${sharedHotspotSsid().ifBlank { "—" }}) · ${getString(R.string.qu_home_usb_always_ready)}"
            TransportChoice.USB_ONLY -> "${getString(R.string.qu_home_mode_usb_only)} · ${getString(R.string.qu_home_mode_usb_only_sub)}"
        }
        view.findViewById<TextView>(R.id.mode_active_explanation)?.text = explanation
    }

    private fun loadBondedDevicesSorted(forCarPlay: Boolean, savedCpAddr: String?): List<BluetoothDevice> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        return runCatching {
            val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
            if (adapter != null && adapter.isEnabled) {
                val list = adapter.bondedDevices.orEmpty()
                if (forCarPlay) {
                    list.sortedWith(
                        compareByDescending<BluetoothDevice> { savedCpAddr != null && it.address == savedCpAddr }
                            .thenByDescending { (it.name ?: "").contains("iPhone", ignoreCase = true) }
                            .thenBy { it.name ?: it.address }
                    )
                } else {
                    val savedAa = app.settings.autoStartBluetoothDeviceName
                    list.sortedWith(
                        compareByDescending<BluetoothDevice> { savedAa.isNotBlank() && it.name == savedAa }
                            .thenBy { (it.name ?: "").contains("iPhone", ignoreCase = true) }
                            .thenBy { it.name ?: it.address }
                    )
                }
            } else emptyList()
        }.getOrDefault(emptyList())
    }

    private fun rebuildRecentDevicesRow(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.recent_devices_container) ?: return
        container.removeAllViews()
        val isVertical = container.orientation == LinearLayout.VERTICAL
        val isCp = activeProtocol == Protocol.CARPLAY

        view.findViewById<TextView>(R.id.paired_section_header)?.text =
            getString(if (isCp) R.string.cp_paired_header else R.string.core_paired_header)

        val savedAaName = app.settings.autoStartBluetoothDeviceName.takeIf { it.isNotBlank() }
        val savedCpAddr = LeapCarPlayPreferences.phoneAddress(requireContext())
        val bonded = loadBondedDevicesSorted(forCarPlay = isCp, savedCpAddr = savedCpAddr)

        if (bonded.isNotEmpty()) {
            bonded.take(3).forEachIndexed { index, dev ->
                val name = runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: dev.address
                val looksIphone = name.contains("iPhone", ignoreCase = true) || name.contains("Apple", ignoreCase = true)
                val targetIsCp = isCp || looksIphone
                val highlighted = if (isCp) {
                    if (savedCpAddr != null) dev.address == savedCpAddr else index == 0
                } else {
                    name == savedAaName || (savedAaName == null && index == 0)
                }
                val subtitle = if (targetIsCp) {
                    getString(if (highlighted && isCp) R.string.cp_paired_selected else R.string.cp_paired_iphone)
                } else {
                    getString(R.string.core_paired_sub_aa)
                }
                container.addView(
                    createRecentDeviceCard(
                        iconRes = if (targetIsCp) R.drawable.ic_carplay else R.drawable.ic_android_auto,
                        tintIcon = !targetIsCp,
                        title = name,
                        subtitle = subtitle,
                        highlighted = highlighted,
                        vertical = isVertical,
                        onClick = {
                            if (targetIsCp) {
                                activeProtocol = Protocol.CARPLAY
                                app.settings.lastHomeProtocol = "carplay"
                                LeapCarPlayPreferences.savePhone(requireContext(), dev.address, name)
                                refreshAll(view)
                                startCarPlayConnection(wireless = currentTransportChoice() != TransportChoice.USB_ONLY)
                            } else {
                                activeProtocol = Protocol.ANDROID_AUTO
                                app.settings.lastHomeProtocol = "aa"
                                connectToBondedAndroid(view, dev, name)
                            }
                        },
                    )
                )
            }
        } else {
            if (isCp) {
                val savedCpName = savedCpAddr?.let { LeapCarPlayPreferences.phoneName(requireContext()) }
                container.addView(
                    createRecentDeviceCard(
                        iconRes = R.drawable.ic_carplay,
                        tintIcon = false,
                        title = savedCpName ?: getString(R.string.cp_paired_choose),
                        subtitle = getString(R.string.cp_paired_choose_sub),
                        highlighted = true,
                        vertical = isVertical,
                        onClick = { showCarPlayPhonePicker() },
                    )
                )
                container.addView(
                    createRecentDeviceCard(
                        iconRes = R.drawable.ic_usb_white,
                        tintIcon = true,
                        title = getString(R.string.cp_paired_usb_title),
                        subtitle = getString(R.string.cp_paired_usb_sub),
                        highlighted = findConnectedUsbPhone()?.second == true,
                        vertical = isVertical,
                        onClick = { startCarPlayConnection(wireless = false, usbDevice = findConnectedUsbPhone()?.takeIf { it.second }?.first) },
                    )
                )
            } else {
                container.addView(
                    createRecentDeviceCard(
                        iconRes = R.drawable.ic_android_auto,
                        tintIcon = true,
                        title = savedAaName ?: getString(R.string.core_paired_fallback_android),
                        subtitle = getString(R.string.core_paired_fallback_aa_sub),
                        highlighted = true,
                        vertical = isVertical,
                        onClick = { onPrimaryConnectTap(view) },
                    )
                )
                container.addView(
                    createRecentDeviceCard(
                        iconRes = R.drawable.ic_carplay,
                        tintIcon = false,
                        title = "iPhone (CarPlay)",
                        subtitle = getString(R.string.core_paired_fallback_cp_sub),
                        highlighted = false,
                        vertical = isVertical,
                        onClick = { selectProtocol(Protocol.CARPLAY, view) },
                    )
                )
            }
        }

        container.addView(
            createRecentDeviceCard(
                iconRes = null,
                tintIcon = true,
                title = getString(if (isCp) R.string.cp_paired_add_title else R.string.core_paired_add_title),
                subtitle = getString(if (isCp) R.string.cp_paired_add_sub else R.string.core_paired_add_sub),
                highlighted = false,
                lastCard = true,
                vertical = isVertical,
                onClick = { if (isCp && bonded.isNotEmpty()) showCarPlayPhonePicker() else openBluetoothSettings() },
            )
        )
    }

    private fun createRecentDeviceCard(
        iconRes: Int?,
        tintIcon: Boolean,
        title: String,
        subtitle: String,
        highlighted: Boolean,
        lastCard: Boolean = false,
        vertical: Boolean = false,
        onClick: () -> Unit,
    ): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(if (highlighted) ACCENT_DARK_BG else CARD_BG)
                setStroke(
                    dp(if (highlighted) 2 else 1),
                    if (highlighted) ACCENT else OUTLINE,
                )
            }
            layoutParams = if (vertical) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (!lastCard) bottomMargin = dp(10)
                }
            } else {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (!lastCard) marginEnd = dp(12)
                }
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val iconContainerBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(if (highlighted) 0x2900E5C7 else 0xFF1A2534.toInt())
        }
        val iconView: View = if (iconRes != null) {
            ImageView(ctx).apply {
                setImageResource(iconRes)
                if (tintIcon) {
                    imageTintList = ColorStateList.valueOf(if (highlighted) ACCENT else TEXT_MAIN)
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(11), dp(11), dp(11), dp(11))
                background = iconContainerBg
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) }
            }
        } else {
            TextView(ctx).apply {
                text = "+"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(ACCENT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = iconContainerBg
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) }
            }
        }
        card.addView(iconView)

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(
            TextView(ctx).apply {
                text = title
                textSize = 18.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(TEXT_MAIN)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        textCol.addView(
            TextView(ctx).apply {
                text = subtitle
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (highlighted) ACCENT else TEXT_DIM)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        card.addView(textCol)
        return card
    }

    private fun startPrimaryAaConnection() {
        val root = view ?: return
        stopCarPlayForAndroidAuto()
        val choice = currentTransportChoice()
        if (choice == TransportChoice.USB_ONLY) {
            handleUsbConnectTap(root)
            return
        }

        val bonded = loadBondedDevicesSorted(forCarPlay = false, savedCpAddr = null)
        val savedName = app.settings.autoStartBluetoothDeviceName
        val preferred = bonded.firstOrNull { (it.name ?: "") == savedName }
            ?: bonded.firstOrNull { !(it.name ?: "").contains("iPhone", ignoreCase = true) }
            ?: bonded.firstOrNull()
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

    private fun connectToBondedAndroid(root: View, device: BluetoothDevice, devName: String) {
        app.settings.autoStartBluetoothDeviceName = devName
        val missing = missingWirelessPermissions()
        if (missing.isNotEmpty()) {
            wirelessPermissions.launch(missing)
            return
        }
        stopCarPlayForAndroidAuto()
        if (currentTransportChoice() == TransportChoice.USB_ONLY) {
            applyUnifiedTransport(TransportChoice.WIFI_P2P, root)
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
        val choice = currentTransportChoice()
        if (choice == TransportChoice.USB_ONLY) return
        if (choice == TransportChoice.WIFI_P2P) {
            s.nativeApTransport = 0
            s.wifiConnectionMode = 3
            ensureCarWifiEnabled()
        } else {
            s.nativeApTransport = 1
            s.wifiConnectionMode = 3
        }
        if (device != null && !device.address.isNullOrBlank()) {
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_NATIVE_AA_POKE
                putExtra(AapService.EXTRA_MAC, device.address)
            })
        } else {
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_START_WIRELESS
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

    private fun openCarHotspotSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }
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
            .setOnCancelListener { AapService.usbSwitchPrompt.value = null }
            .show()
    }

    private fun openAaProjection() {
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    override fun onPause() {
        stopConnectingUi()
        activeDialog?.dismiss()
        activeDialog = null
        super.onPause()
    }

    companion object {
        private const val ACCENT = 0xFF00E5C7.toInt()
        private const val ACCENT_DARK_BG = 0xFF0E2C30.toInt()
        private const val ON_ACCENT = 0xFF04150E.toInt()
        private const val OK_GREEN = 0xFF3AD29F.toInt()
        private const val CARD_BG = 0xFF101824.toInt()
        private const val SURFACE_HI = 0xFF15202E.toInt()
        private const val SURFACE_TOP = 0xFF1A2736.toInt()
        private const val OUTLINE = 0xFF223246.toInt()
        private const val OUTLINE_STRONG = 0xFF2E4259.toInt()
        private const val TEXT_MAIN = 0xFFEAF2F7.toInt()
        private const val TEXT_DIM = 0xFF9EB3C7.toInt()
        private const val TEXT_FAINT = 0xFF70859B.toInt()

        var forceSelfModeLaunch = false
        fun resetAutoStart() { forceSelfModeLaunch = false }
    }
}
