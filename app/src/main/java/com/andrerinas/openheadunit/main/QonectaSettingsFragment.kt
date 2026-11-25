package com.andrerinas.openheadunit.main

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.MediaKeyRoutingPolicy
import com.andrerinas.openheadunit.aap.PlaybackFocusPolicy
import com.andrerinas.openheadunit.aap.SoftApState
import com.andrerinas.openheadunit.cluster.ClusterMapController
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.DiagnosticExportStore
import com.andrerinas.openheadunit.utils.DiagnosticReport
import com.andrerinas.openheadunit.utils.LocaleHelper
import com.andrerinas.openheadunit.utils.QrCodeGenerator
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SettingsBackupManager
import com.andrerinas.openheadunit.utils.SoftApStateReader
import com.andrerinas.openheadunit.utils.SystemUI
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.headqlink.unit.files.CarFiles
import com.headqlink.unit.files.CarReturnPillOverlay
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.CarPlayLoopbackSimulator
import com.shilapi.xcertplay.LeapCarPlayPreferences
import com.shilapi.xcertplay.airplay.CarPlaySize
import com.shilapi.xcertplay.media.MediaAudioBuffer
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pantalla unificada de Ajustes de Qonecta Unit CORE en 2 paneles (estilo Cockpit Qonecta).
 * Unifica Android Auto y Apple CarPlay con guardado automático instantáneo, cero pantallas
 * heredadas azules/planas y cero opciones muertas.
 */
class QonectaSettingsFragment : Fragment() {

    private data class CategorySpec(val id: Int, val labelRes: Int, val iconRes: Int)

    private val categories = listOf(
        CategorySpec(TAB_ESSENTIALS, R.string.qu_set_tab_essentials, R.drawable.ic_directions_car),
        CategorySpec(TAB_CONNECTION, R.string.qu_set_tab_connection, R.drawable.ic_network_wifi_white),
        CategorySpec(TAB_DISPLAY, R.string.qu_set_tab_display, R.drawable.ic_da_display),
        CategorySpec(TAB_AUDIO, R.string.qu_set_tab_audio, R.drawable.ic_settings_quick),
        CategorySpec(TAB_ADVANCED, R.string.qu_set_tab_advanced, R.drawable.ic_da_diagnostics),
        CategorySpec(TAB_ABOUT, R.string.qu_set_tab_about, R.drawable.ic_da_about),
    )

    private val settings get() = App.provide(requireContext()).settings
    private var activeTab = TAB_ESSENTIALS
    private var expertExpanded = false
    private var exportingDiag = false
    private var savedBadgeJob: Job? = null

    private var savedBadge: TextView? = null
    private var sideRailScroll: View? = null
    private var sideRailContainer: LinearLayout? = null
    private var railDivider: View? = null
    private var topTabsScroll: View? = null
    private var topTabsContainer: LinearLayout? = null
    private var contentScroll: ScrollView? = null
    private var contentContainer: LinearLayout? = null

    private val carFilesPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        CarReturnPillOverlay.dismiss()
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            Toast.makeText(requireContext(), getString(R.string.qu_files_picker_tip), Toast.LENGTH_LONG).show()
        }
    }

    private val openUsbTreeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        CarReturnPillOverlay.dismiss()
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) {
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            settings.usbDiagTreeUri = uri.toString()
            flashSaved()
            exportUnifiedDiagnosticsToTree(uri)
        }
    }

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        CarReturnPillOverlay.dismiss()
        if (isAdded) renderActiveTab(preserveScroll = true)
    }

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(SettingsBackupManager.MIME_TYPE)
    ) { uri ->
        CarReturnPillOverlay.dismiss()
        if (uri != null) exportBackupToUri(uri)
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        CarReturnPillOverlay.dismiss()
        if (uri != null) importBackupFromUri(uri)
    }

    private val createDiagDocLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        CarReturnPillOverlay.dismiss()
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) exportUnifiedDiagnostics(uri)
    }

    private val onboardingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isAdded) renderActiveTab()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeTab = savedInstanceState?.getInt(KEY_ACTIVE_TAB)
            ?: activity?.intent?.getIntExtra(SettingsActivity.EXTRA_INITIAL_TAB, TAB_ESSENTIALS)
            ?: TAB_ESSENTIALS
        expertExpanded = savedInstanceState?.getBoolean(KEY_EXPERT_EXPANDED) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ACTIVE_TAB, activeTab)
        outState.putBoolean(KEY_EXPERT_EXPANDED, expertExpanded)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_qonecta_settings, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        savedBadge = view.findViewById(R.id.qset_saved_badge)
        sideRailScroll = view.findViewById(R.id.qset_side_rail_scroll)
        sideRailContainer = view.findViewById(R.id.qset_side_rail_container)
        railDivider = view.findViewById(R.id.qset_rail_divider)
        topTabsScroll = view.findViewById(R.id.qset_top_tabs_scroll)
        topTabsContainer = view.findViewById(R.id.qset_top_tabs_container)
        contentScroll = view.findViewById(R.id.qset_content_scroll)
        contentContainer = view.findViewById(R.id.qset_content_container)

        view.findViewById<View>(R.id.qset_back_button)?.setOnClickListener {
            requireActivity().finish()
        }

        // Receive DPI return result cleanly
        findNavController().currentBackStackEntry
            ?.savedStateHandle
            ?.getLiveData<Int>(DpiSettingsFragment.KEY_DPI_RESULT)
            ?.observe(viewLifecycleOwner) {
                renderActiveTab()
            }

        configurePaneLayout()
        renderNavRail()
        renderActiveTab()

        // Keep live 5 GHz channel status updated while on Connection tab
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AapService.wifiDirectChannelInfo.collect {
                        if (activeTab == TAB_CONNECTION && isAdded) renderActiveTab(preserveScroll = true)
                    }
                }
                launch {
                    AapService.wifiCoexistenceWarning.collect {
                        if (activeTab == TAB_CONNECTION && isAdded) renderActiveTab(preserveScroll = true)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderActiveTab(preserveScroll = true)
    }

    private fun configurePaneLayout() {
        val wide = resources.configuration.screenWidthDp >= 720
        sideRailScroll?.visibility = if (wide) View.VISIBLE else View.GONE
        railDivider?.visibility = if (wide) View.VISIBLE else View.GONE
        topTabsScroll?.visibility = if (wide) View.GONE else View.VISIBLE
        if (!wide) {
            contentContainer?.setPadding(dp(18), dp(16), dp(18), dp(28))
        }
    }

    private fun renderNavRail() {
        val wide = resources.configuration.screenWidthDp >= 720
        if (wide) {
            val rail = sideRailContainer ?: return
            rail.removeAllViews()
            categories.forEach { cat ->
                val selected = cat.id == activeTab
                val item = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(13), dp(14), dp(13))
                    background = rippleRounded(
                        fill = if (selected) ACCENT_DARK_BG else Color.TRANSPARENT,
                        stroke = if (selected) ACCENT else Color.TRANSPARENT,
                        radiusDp = 16,
                        strokeDp = if (selected) 2 else 0,
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (activeTab != cat.id) {
                            activeTab = cat.id
                            renderNavRail()
                            renderActiveTab(preserveScroll = false)
                        }
                    }
                }
                item.addView(ImageView(requireContext()).apply {
                    setImageResource(cat.iconRes)
                    imageTintList = ColorStateList.valueOf(if (selected) ACCENT else TEXT_DIM)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
                item.addView(TextView(requireContext()).apply {
                    text = getString(cat.labelRes)
                    textSize = 16f
                    typeface = Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (selected) ACCENT else TEXT_MAIN)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                rail.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                })
            }
        } else {
            val strip = topTabsContainer ?: return
            strip.removeAllViews()
            categories.forEach { cat ->
                val selected = cat.id == activeTab
                val chip = TextView(requireContext()).apply {
                    text = getString(cat.labelRes)
                    textSize = 14.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(if (selected) ACCENT else TEXT_DIM)
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    background = rippleRounded(
                        fill = if (selected) ACCENT_DARK_BG else SURFACE_HI,
                        stroke = if (selected) ACCENT else OUTLINE,
                        radiusDp = 18,
                        strokeDp = if (selected) 2 else 1,
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (activeTab != cat.id) {
                            activeTab = cat.id
                            renderNavRail()
                            renderActiveTab(preserveScroll = false)
                        }
                    }
                }
                strip.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(8)
                })
            }
        }
    }

    private fun renderActiveTab(preserveScroll: Boolean = false) {
        val root = contentContainer ?: return
        val prevY = if (preserveScroll) contentScroll?.scrollY ?: 0 else 0
        root.removeAllViews()

        when (activeTab) {
            TAB_ESSENTIALS -> buildEssentialsTab(root)
            TAB_CONNECTION -> buildConnectionTab(root)
            TAB_DISPLAY -> buildDisplayTab(root)
            TAB_AUDIO -> buildAudioTab(root)
            TAB_ADVANCED -> buildAdvancedTab(root)
            TAB_ABOUT -> buildAboutTab(root)
        }

        contentScroll?.post { contentScroll?.scrollTo(0, if (preserveScroll) prevY else 0) }
    }

    private fun flashSaved(reconnectNeeded: Boolean = false) {
        settings.commit()
        val badge = savedBadge ?: return
        badge.text = if (reconnectNeeded) "✓ ${getString(R.string.qu_set_reconnect_hint)}" else "✓ ${getString(R.string.qu_set_saved_toast)}"
        badge.visibility = View.VISIBLE
        badge.alpha = 1f
        savedBadgeJob?.cancel()
        savedBadgeJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(if (reconnectNeeded) 2600L else 1400L)
            if (isAdded) {
                badge.animate().alpha(0f).setDuration(250).withEndAction {
                    badge.visibility = View.INVISIBLE
                }.start()
            }
        }
    }

    // -------------------------------------------------------------------------
    // TAB 0: ESSENTIALS
    // -------------------------------------------------------------------------
    private fun buildEssentialsTab(root: LinearLayout) {
        addCard(root, getString(R.string.qu_set_sec_bars)) { card ->
            val currentBarIdx = when (settings.fullscreenMode) {
                Settings.FullscreenMode.IMMERSIVE, Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH -> 0
                Settings.FullscreenMode.STATUS_ONLY -> 1
                Settings.FullscreenMode.NONE -> 2
            }
            addSegmentedRow(
                card,
                title = getString(R.string.qu_set_car_bars_title),
                subtitle = getString(R.string.qu_set_car_bars_desc),
                options = listOf(
                    getString(R.string.qu_set_bar_immersive),
                    getString(R.string.qu_set_bar_status),
                    getString(R.string.qu_set_bar_visible),
                ),
                selectedIndex = currentBarIdx,
            ) { idx ->
                val mode = when (idx) {
                    0 -> Settings.FullscreenMode.IMMERSIVE
                    1 -> Settings.FullscreenMode.STATUS_ONLY
                    else -> Settings.FullscreenMode.NONE
                }
                settings.fullscreenMode = mode
                val hideTop = mode != Settings.FullscreenMode.NONE
                val hideBottom = mode == Settings.FullscreenMode.IMMERSIVE || mode == Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH
                AirPlayPersistence.saveHideTopBar(requireContext(), hideTop)
                AirPlayPersistence.saveHideBottomBar(requireContext(), hideBottom)
                requireActivity().findViewById<View>(R.id.settings_nav_host)?.let { host ->
                    SystemUI.apply(requireActivity().window, host, mode)
                }
                flashSaved()
                renderActiveTab(preserveScroll = true)
            }

            addDivider(card)

            val nightModes = listOf(
                Settings.NightMode.AUTO,
                Settings.NightMode.DAY,
                Settings.NightMode.NIGHT,
                Settings.NightMode.CAR_SIGNAL,
                Settings.NightMode.LIGHT_SENSOR,
                Settings.NightMode.MANUAL_TIME,
            )
            val nightLabels = resources.getStringArray(R.array.night_mode)
            val currentNightName = nightLabels.getOrElse(settings.nightMode.value) { nightLabels[0] }
            addActionRow(
                card,
                title = getString(R.string.qu_set_night_mode_title),
                subtitle = getString(R.string.qu_set_night_mode_desc),
                valueText = currentNightName,
            ) {
                val labels = nightModes.map { mode -> nightLabels.getOrElse(mode.value) { mode.name } }.toTypedArray()
                val curIdx = nightModes.indexOf(settings.nightMode).coerceAtLeast(0)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.qu_set_night_mode_title)
                    .setSingleChoiceItems(labels, curIdx) { dlg, which ->
                        settings.nightMode = nightModes[which]
                        requireContext().sendBroadcast(
                            Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE).setPackage(requireContext().packageName)
                        )
                        flashSaved()
                        dlg.dismiss()
                        renderActiveTab(preserveScroll = true)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            addDivider(card)

            addSwitchRow(
                card,
                title = getString(R.string.qu_set_rhd_title),
                subtitle = getString(R.string.qu_set_rhd_desc),
                checked = settings.rightHandDrive,
            ) { checked ->
                settings.rightHandDrive = checked
                AirPlayPersistence.saveRightHandDrive(requireContext(), checked)
                flashSaved(reconnectNeeded = true)
            }
        }

        addCard(root, getString(R.string.qu_set_sec_general)) { card ->
            val locales = LocaleHelper.getAvailableLocales(requireContext())
            val curCode = settings.appLanguage
            val curLangDisplay = LocaleHelper.stringToLocale(curCode)?.let { LocaleHelper.getDisplayName(it) }
                ?: getString(R.string.system_default)
            addActionRow(
                card,
                title = getString(R.string.app_language),
                subtitle = getString(R.string.change_language),
                valueText = curLangDisplay,
            ) {
                val names = mutableListOf(getString(R.string.system_default))
                val codes = mutableListOf("")
                locales.forEach { loc ->
                    names += LocaleHelper.getDisplayName(loc)
                    codes += LocaleHelper.localeToString(loc)
                }
                val idx = codes.indexOf(curCode).coerceAtLeast(0)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_language)
                    .setSingleChoiceItems(names.toTypedArray(), idx) { dlg, which ->
                        val chosen = codes[which]
                        dlg.dismiss()
                        if (chosen != curCode) {
                            settings.appLanguage = chosen
                            settings.commit()
                            requireContext().sendBroadcast(
                                Intent(MainActivity.ACTION_RECREATE_MAIN).setPackage(requireContext().packageName)
                            )
                            requireActivity().recreate()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            addDivider(card)

            val startupKeys = listOf("last", "aa", "carplay")
            val startupLabels = arrayOf(
                getString(R.string.qu_startup_last),
                getString(R.string.qu_startup_aa),
                getString(R.string.qu_startup_carplay),
            )
            val curStartupIdx = startupKeys.indexOf(settings.homeStartupScreen).coerceAtLeast(0)
            addActionRow(
                card,
                title = getString(R.string.qu_startup_screen_title),
                subtitle = getString(R.string.qu_startup_screen_desc),
                valueText = startupLabels[curStartupIdx],
            ) {
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.qu_startup_screen_title)
                    .setSingleChoiceItems(startupLabels, curStartupIdx) { dlg, which ->
                        settings.homeStartupScreen = startupKeys[which]
                        flashSaved()
                        dlg.dismiss()
                        renderActiveTab(preserveScroll = true)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            addDivider(card)

            val curHomeScale = settings.uiScaleHomePercent
            val scaleIdx = when {
                curHomeScale <= 105 -> 0
                curHomeScale >= 128 -> 2
                else -> 1
            }
            addSegmentedRow(
                card,
                title = getString(R.string.qu_set_ui_scale_title),
                subtitle = getString(R.string.qu_set_ui_scale_desc),
                options = listOf(
                    getString(R.string.qu_set_scale_compact),
                    getString(R.string.qu_set_scale_standard),
                    getString(R.string.qu_set_scale_large),
                ),
                selectedIndex = scaleIdx,
            ) { idx ->
                val (homeP, setP) = when (idx) {
                    0 -> 100 to 110
                    2 -> 130 to 145
                    else -> 115 to 130
                }
                settings.uiScaleHomePercent = homeP
                settings.uiScaleSettingsPercent = setP
                settings.commit()
                requireContext().sendBroadcast(
                    Intent(MainActivity.ACTION_RECREATE_MAIN).setPackage(requireContext().packageName)
                )
                requireActivity().recreate()
            }

            addDivider(card)

            addActionRow(
                card,
                title = getString(R.string.qu_set_open_wizard),
                subtitle = getString(R.string.auto_optimize_desc),
                valueText = null,
            ) {
                onboardingLauncher.launch(Intent(requireContext(), OnboardingActivity::class.java))
            }
        }
    }

    // -------------------------------------------------------------------------
    // TAB 1: CONNECTION (UNIFIED AA + CARPLAY + 5 GHZ ACCELERATOR)
    // -------------------------------------------------------------------------
    private fun buildConnectionTab(root: LinearLayout) {
        val currentTransportIdx = when {
            settings.wifi5gEnabled && settings.nativeApTransport == 0 -> 0
            settings.hotspotModeEnabled || (settings.wifiConnectionMode == 3 && settings.nativeApTransport == 1) -> 1
            else -> 2
        }

        addCard(root, getString(R.string.qu_set_sec_wireless)) { card ->
            addSegmentedRow(
                card,
                title = getString(R.string.qu_home_transport_label),
                subtitle = getString(R.string.setup_saved_details_hint),
                options = listOf(
                    getString(R.string.qu_home_mode_p2p),
                    getString(R.string.qu_home_mode_hotspot),
                    getString(R.string.qu_home_mode_usb_only),
                ),
                selectedIndex = currentTransportIdx,
            ) { idx ->
                val ctx = requireContext()
                when (idx) {
                    0 -> {
                        settings.nativeApTransport = 0
                        settings.wifiConnectionMode = 3
                        settings.wifi5gEnabled = true
                        settings.hotspotModeEnabled = false
                        AirPlayPersistence.saveWirelessHotspotMode(ctx, WirelessHotspotMode.WIFI_P2P)
                        AirPlayPersistence.saveWirelessEnabled(ctx, true)
                        ContextCompat.startForegroundService(ctx, Intent(ctx, AapService::class.java).apply {
                            action = AapService.ACTION_START_WIRELESS
                        })
                    }
                    1 -> {
                        val ssid = sharedHotspotSsid()
                        val pass = sharedHotspotPassword()
                        if (ManualHotspotValidation.validate(ssid, pass) != null) {
                            showHotspotDialog(ssid, pass) { newSsid, newPass ->
                                saveSharedHotspot(newSsid, newPass)
                                activateCarHotspot()
                                flashSaved()
                                renderActiveTab(preserveScroll = true)
                            }
                            return@addSegmentedRow
                        }
                        activateCarHotspot()
                    }
                    else -> {
                        settings.wifi5gEnabled = false
                        settings.hotspotModeEnabled = false
                        settings.wifiConnectionMode = 0
                        settings.usbModeEnabled = true
                        settings.listenForUsbDevices = true
                        Settings.setUsbAttachedActivityEnabled(ctx, true)
                        AirPlayPersistence.saveWirelessEnabled(ctx, false)
                        if (AapService.isServiceRunning()) {
                            runCatching {
                                ctx.startService(Intent(ctx, AapService::class.java).apply {
                                    action = AapService.ACTION_STOP_WIRELESS
                                })
                            }
                        }
                    }
                }
                flashSaved()
                renderActiveTab(preserveScroll = true)
            }
        }

        if (currentTransportIdx == 0) {
            addCard(root, getString(R.string.qu_set_sec_5g_diag)) { card ->
                val ch = AapService.wifiDirectChannelInfo.value
                val coex = AapService.wifiCoexistenceWarning.value
                card.addView(TextView(requireContext()).apply {
                    text = if (!ch.isNullOrBlank()) "●  $ch" else getString(R.string.qu_set_channel_idle)
                    textSize = 15.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(if (!ch.isNullOrBlank()) OK_GREEN else TEXT_DIM)
                    setPadding(0, dp(4), 0, dp(8))
                })
                if (!coex.isNullOrBlank()) {
                    card.addView(TextView(requireContext()).apply {
                        text = "⚠  ${getString(R.string.qu_home_coex_warn)}"
                        textSize = 14f
                        setTextColor(WARNING)
                        setPadding(0, 0, 0, dp(10))
                    })
                }
                val btnRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
                btnRow.addView(
                    pillActionButton(getString(R.string.qu_set_reset_p2p_now), primary = true) {
                        ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_RESTART_WIFI_DIRECT
                        })
                        Toast.makeText(requireContext(), getString(R.string.qu_home_reset_wifi_toast), Toast.LENGTH_SHORT).show()
                    },
                    LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(10) },
                )
                btnRow.addView(
                    pillActionButton(getString(R.string.qu_set_open_wifi), primary = false) {
                        openSystemIntent(Intent(SystemSettings.ACTION_WIFI_SETTINGS))
                    },
                    LinearLayout.LayoutParams(0, dp(52), 1f),
                )
                card.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                })
            }
        }

        if (currentTransportIdx == 1) {
            addCard(root, getString(R.string.qu_set_sec_hotspot)) { card ->
                val ssid = sharedHotspotSsid()
                val apState = SoftApStateReader.read(requireContext())
                val apStatusText = when (apState) {
                    SoftApState.ENABLED -> getString(R.string.setup_hotspot_on)
                    SoftApState.NOT_ENABLED -> getString(R.string.setup_hotspot_off)
                    else -> getString(R.string.setup_hotspot_unknown)
                }
                card.addView(TextView(requireContext()).apply {
                    text = apStatusText
                    textSize = 14.5f
                    setTextColor(if (apState == SoftApState.ENABLED) OK_GREEN else WARNING)
                    setPadding(0, 0, 0, dp(10))
                })
                addActionRow(
                    card,
                    title = getString(R.string.qu_set_edit_hotspot),
                    subtitle = getString(R.string.cp_hotspot_desc),
                    valueText = if (ssid.isNotBlank()) getString(R.string.qu_set_hotspot_configured, ssid) else getString(R.string.qu_set_hotspot_not_set),
                ) {
                    showHotspotDialog(sharedHotspotSsid(), sharedHotspotPassword()) { newSsid, newPass ->
                        saveSharedHotspot(newSsid, newPass)
                        flashSaved()
                        renderActiveTab(preserveScroll = true)
                    }
                }
                addDivider(card)
                addSwitchRow(
                    card,
                    title = getString(R.string.setup_turn_hotspot_on_automatically),
                    subtitle = getString(R.string.setup_auto_hotspot_hint),
                    checked = settings.autoEnableHotspot,
                ) { checked ->
                    settings.autoEnableHotspot = checked
                    flashSaved()
                    if (checked && !AppPermissions.isWriteSettingsGranted(requireContext())) {
                        openSystemIntent(Intent(SystemSettings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${requireContext().packageName}")))
                    }
                }
                addDivider(card)
                addActionRow(
                    card,
                    title = getString(R.string.qu_set_open_hotspot_sys),
                    subtitle = null,
                    valueText = null,
                ) {
                    openSystemIntent(Intent("com.android.settings.WIFI_TETHER_SETTINGS"))
                }
            }
        }

        addCard(root, getString(R.string.qu_set_sec_bt_phones)) { card ->
            val bonded = loadBondedBtDevices()
            val savedCpAddr = LeapCarPlayPreferences.phoneAddress(requireContext())
            if (bonded.isEmpty()) {
                card.addView(TextView(requireContext()).apply {
                    text = getString(R.string.qu_set_no_bt_phones)
                    textSize = 15f
                    setTextColor(TEXT_DIM)
                    setPadding(0, dp(4), 0, dp(12))
                })
            } else {
                bonded.forEachIndexed { idx, dev ->
                    val name = runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: dev.address
                    val isCpSelected = savedCpAddr == dev.address
                    val row = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(10), 0, dp(10))
                    }
                    val infoCol = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = dp(10)
                        }
                    }
                    infoCol.addView(TextView(requireContext()).apply {
                        text = name
                        textSize = 16.5f
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setTextColor(TEXT_MAIN)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    infoCol.addView(TextView(requireContext()).apply {
                        text = dev.address
                        textSize = 12.5f
                        typeface = Typeface.MONOSPACE
                        setTextColor(TEXT_FAINT)
                    })
                    row.addView(infoCol)

                    // 1-tap BT WPP Wake for Android Auto (explicitly passes EXTRA_MAC!)
                    row.addView(
                        compactBadgeButton(getString(R.string.qu_set_wake_btn), highlighted = true) {
                            settings.autoStartBluetoothDeviceName = name
                            if (dev.address !in settings.autoStartBluetoothDeviceMacs) {
                                settings.autoStartBluetoothDeviceMacs = settings.autoStartBluetoothDeviceMacs + dev.address
                            }
                            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                                action = AapService.ACTION_NATIVE_AA_POKE
                                putExtra(AapService.EXTRA_MAC, dev.address)
                            })
                            Toast.makeText(requireContext(), getString(R.string.core_live_bt_waking, name), Toast.LENGTH_SHORT).show()
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { marginEnd = dp(8) },
                    )
                    row.addView(
                        compactBadgeButton(
                            if (isCpSelected) "✓ ${getString(R.string.qu_set_select_cp_btn)}" else getString(R.string.qu_set_select_cp_btn),
                            highlighted = isCpSelected,
                        ) {
                            LeapCarPlayPreferences.savePhone(requireContext(), dev.address, name)
                            flashSaved()
                            renderActiveTab(preserveScroll = true)
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)),
                    )
                    card.addView(row)
                    if (idx < bonded.lastIndex) addDivider(card)
                }
            }

            val btnRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            btnRow.addView(
                pillActionButton(getString(R.string.qu_set_open_bt), primary = false) {
                    openSystemIntent(Intent(SystemSettings.ACTION_BLUETOOTH_SETTINGS))
                },
                LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(10) },
            )
            btnRow.addView(
                pillActionButton(getString(R.string.qu_set_open_perms), primary = false) {
                    findNavController().navigate(R.id.permissionsFragment)
                },
                LinearLayout.LayoutParams(0, dp(50), 1f),
            )
            card.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
        }

        addCard(root, getString(R.string.qu_set_sec_usb_auto)) { card ->
            addSwitchRow(
                card,
                title = getString(R.string.qu_set_usb_listen_title),
                subtitle = getString(R.string.qu_set_usb_listen_desc),
                checked = settings.usbModeEnabled,
            ) { checked ->
                settings.usbModeEnabled = checked
                settings.listenForUsbDevices = checked
                Settings.setUsbAttachedActivityEnabled(requireContext(), checked)
                flashSaved()
            }
            addDivider(card)
            addSwitchRow(
                card,
                title = getString(R.string.qu_set_aa_auto_last_title),
                subtitle = getString(R.string.qu_set_aa_auto_last_desc),
                checked = settings.autoConnectLastSession,
            ) { checked ->
                settings.autoConnectLastSession = checked
                flashSaved()
            }
            addDivider(card)
            addSwitchRow(
                card,
                title = getString(R.string.qu_set_cp_auto_title),
                subtitle = getString(R.string.qu_set_cp_auto_desc),
                checked = LeapCarPlayPreferences.autoConnect(requireContext()),
            ) { checked ->
                LeapCarPlayPreferences.saveAutoConnect(requireContext(), checked)
                flashSaved()
            }
        }
    }

    // -------------------------------------------------------------------------
    // TAB 2: DISPLAY & VIDEO (UNIFIED AA + CARPLAY + CLUSTER)
    // -------------------------------------------------------------------------
    private fun buildDisplayTab(root: LinearLayout) {
        addCard(root, getString(R.string.qu_set_sec_aa_video)) { card ->
            val allRes = Settings.Resolution.allResolutions
            val curRes = Settings.Resolution.fromId(settings.resolutionId) ?: Settings.Resolution.AUTO
            addActionRow(
                card,
                title = getString(R.string.resolution),
                subtitle = null,
                valueText = curRes.resName,
            ) {
                val names = allRes.map { it.resName }.toTypedArray()
                val idx = allRes.indexOf(curRes).coerceAtLeast(0)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.resolution)
                    .setSingleChoiceItems(names, idx) { dlg, which ->
                        settings.resolutionId = allRes[which].id
                        flashSaved(reconnectNeeded = true)
                        dlg.dismiss()
                        renderActiveTab(preserveScroll = true)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            addDivider(card)

            val dpiVal = settings.dpiPixelDensity
            addActionRow(
                card,
                title = getString(R.string.qu_set_dpi_btn),
                subtitle = null,
                valueText = if (dpiVal == 0) "Auto" else "$dpiVal dpi",
            ) {
                findNavController().navigate(R.id.dpiSettingsFragment)
            }

            addDivider(card)

            addSegmentedRow(
                card,
                title = getString(R.string.fps_limit),
                subtitle = null,
                options = listOf("30 fps", "60 fps"),
                selectedIndex = if (settings.fpsLimit == 30) 0 else 1,
            ) { idx ->
                settings.fpsLimit = if (idx == 0) 30 else 60
                flashSaved(reconnectNeeded = true)
            }

            addDivider(card)

            val codecs = listOf("Auto", "H.264", "H.265")
            addSegmentedRow(
                card,
                title = getString(R.string.video_codec),
                subtitle = null,
                options = codecs,
                selectedIndex = codecs.indexOf(settings.videoCodec).coerceAtLeast(0),
            ) { idx ->
                settings.videoCodec = codecs[idx]
                flashSaved(reconnectNeeded = true)
            }

            addDivider(card)

            val viewModes = listOf(Settings.ViewMode.SURFACE, Settings.ViewMode.TEXTURE, Settings.ViewMode.GLES)
            addSegmentedRow(
                card,
                title = getString(R.string.view_mode),
                subtitle = null,
                options = listOf("Surface", "Texture", "OpenGL"),
                selectedIndex = viewModes.indexOf(settings.viewMode).coerceAtLeast(1),
            ) { idx ->
                settings.viewMode = viewModes[idx]
                LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(
                    Intent(QuickSettingsFragment.ACTION_SETTINGS_CHANGED)
                        .putExtra(QuickSettingsFragment.EXTRA_NEEDS_VIEW_RECREATE, true)
                )
                flashSaved()
            }

            addDivider(card)

            addSwitchRow(
                card,
                title = getString(R.string.pref_stretch_screen_title),
                subtitle = getString(R.string.pref_stretch_screen_summary),
                checked = settings.stretchToFill,
            ) { checked ->
                settings.stretchToFill = checked
                flashSaved()
            }

            addDivider(card)

            addActionRow(
                card,
                title = getString(R.string.qu_set_insets_btn),
                subtitle = null,
                valueText = getString(
                    R.string.qu_set_insets_summary,
                    settings.insetTop, settings.insetBottom, settings.insetLeft, settings.insetRight,
                ),
            ) {
                showCustomInsetsDialog()
            }
        }

        addCard(root, getString(R.string.qu_set_sec_cp_video)) { card ->
            val cpSizes = CarPlaySize.entries
            val curSize = CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(requireContext()))
            addSegmentedRow(
                card,
                title = getString(R.string.cp_opt_size),
                subtitle = getString(R.string.cp_opt_size_desc),
                options = listOf(
                    getString(R.string.cp_size_small),
                    getString(R.string.cp_size_medium),
                    getString(R.string.cp_size_large),
                ),
                selectedIndex = when (curSize) {
                    CarPlaySize.SMALL -> 0
                    CarPlaySize.MEDIUM -> 1
                    CarPlaySize.LARGE -> 2
                },
            ) { idx ->
                val chosen = when (idx) {
                    0 -> CarPlaySize.SMALL
                    1 -> CarPlaySize.MEDIUM
                    else -> CarPlaySize.LARGE
                }
                AirPlayPersistence.saveWidthPhysicalMm(requireContext(), chosen.widthMillimeters)
                flashSaved(reconnectNeeded = true)
            }

            addDivider(card)

            val scales = listOf(10, 8, 6)
            addSegmentedRow(
                card,
                title = getString(R.string.cp_opt_resolution),
                subtitle = null,
                options = listOf("100%", "80%", "60%"),
                selectedIndex = scales.indexOf(AirPlayPersistence.loadDisplayScaleTenths(requireContext())).coerceAtLeast(0),
            ) { idx ->
                AirPlayPersistence.saveDisplayScaleTenths(requireContext(), scales[idx])
                flashSaved(reconnectNeeded = true)
            }

            addDivider(card)

            addSegmentedRow(
                card,
                title = getString(R.string.cp_opt_fps),
                subtitle = null,
                options = listOf("30 fps", "60 fps"),
                selectedIndex = if (AirPlayPersistence.loadFps(requireContext()) == 60) 1 else 0,
            ) { idx ->
                AirPlayPersistence.saveFps(requireContext(), if (idx == 1) 60 else 30)
                flashSaved(reconnectNeeded = true)
            }

            addDivider(card)

            addSwitchRow(
                card,
                title = getString(R.string.cp_opt_hevc),
                subtitle = getString(R.string.cp_opt_hevc_desc),
                checked = AirPlayPersistence.loadHevcEnabled(requireContext()),
            ) { checked ->
                AirPlayPersistence.saveHevcEnabled(requireContext(), checked)
                flashSaved(reconnectNeeded = true)
            }
        }

        addCard(root, getString(R.string.qu_set_sec_cluster)) { card ->
            addSwitchRow(
                card,
                title = getString(R.string.qu_set_cluster_title),
                subtitle = getString(R.string.qu_set_cluster_desc),
                checked = settings.clusterMapEnabled,
            ) { checked ->
                settings.clusterMapEnabled = checked
                if (!checked) ClusterMapController.stop(context = requireContext())
                flashSaved()
                renderActiveTab(preserveScroll = true)
            }
            if (settings.clusterMapEnabled) {
                addDivider(card)
                addSwitchRow(
                    card,
                    title = getString(R.string.qu_cluster_short_on),
                    subtitle = getString(R.string.qu_cluster_hint_on),
                    checked = settings.clusterTurnCardOverlay,
                ) { checked ->
                    settings.clusterTurnCardOverlay = checked
                    flashSaved()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // TAB 3: AUDIO & CONTROLS
    // -------------------------------------------------------------------------
    private fun buildAudioTab(root: LinearLayout) {
        addCard(root, getString(R.string.qu_set_sec_audio_aa)) { card ->
            addSwitchRow(
                card,
                title = getString(R.string.da_bluetooth_music),
                subtitle = getString(R.string.da_bluetooth_music_hint),
                checked = settings.musicViaBluetooth,
            ) { checked ->
                settings.musicViaBluetooth = checked
                flashSaved(reconnectNeeded = true)
            }
            addDivider(card)
            addSwitchRow(
                card,
                title = getString(R.string.enable_audio_sink),
                subtitle = null,
                checked = settings.enableAudioSink,
            ) { checked ->
                settings.enableAudioSink = checked
                flashSaved(reconnectNeeded = true)
            }
            addDivider(card)
            val focusModes = listOf(
                PlaybackFocusPolicy.Mode.AUTO,
                PlaybackFocusPolicy.Mode.ALWAYS,
                PlaybackFocusPolicy.Mode.NEVER,
            )
            addSegmentedRow(
                card,
                title = getString(R.string.playback_focus_mode),
                subtitle = null,
                options = listOf("Auto", getString(R.string.playback_focus_mode_always), getString(R.string.playback_focus_mode_never)),
                selectedIndex = focusModes.indexOf(settings.playbackFocusMode).coerceAtLeast(0),
            ) { idx ->
                settings.playbackFocusMode = focusModes[idx]
                flashSaved(reconnectNeeded = true)
            }
            addDivider(card)
            addSwitchRow(
                card,
                title = getString(R.string.static_audio_focus),
                subtitle = null,
                checked = settings.staticAudioFocus,
            ) { checked ->
                settings.staticAudioFocus = checked
                flashSaved(reconnectNeeded = true)
            }
            addDivider(card)
            addSwitchRow(
                card,
                title = getString(R.string.separate_audio_streams),
                subtitle = null,
                checked = settings.separateAudioStreams,
            ) { checked ->
                settings.separateAudioStreams = checked
                flashSaved(reconnectNeeded = true)
            }
            addDivider(card)
            addSwitchRow(
                card,
                title = getString(R.string.use_aac_audio),
                subtitle = null,
                checked = settings.useAacAudio,
            ) { checked ->
                settings.useAacAudio = checked
                flashSaved(reconnectNeeded = true)
            }
            addDivider(card)
            addSwitchRow(
                card,
                title = getString(R.string.sync_media_session_aa_metadata),
                subtitle = null,
                checked = settings.syncMediaSessionWithAaMetadata,
            ) { checked ->
                settings.syncMediaSessionWithAaMetadata = checked
                flashSaved()
            }
            addDivider(card)
            addSwitchRow(
                card,
                title = getString(R.string.attach_hw_dsp_equalizer),
                subtitle = getString(R.string.attach_hw_dsp_equalizer_description),
                checked = settings.attachHwDspEqualizer,
            ) { checked ->
                settings.attachHwDspEqualizer = checked
                flashSaved(reconnectNeeded = true)
            }
            addDivider(card)
            val latencyValues = listOf(1, 2, 4, 8)
            addSegmentedRow(
                card,
                title = getString(R.string.audio_latency_multiplier),
                subtitle = "${settings.audioLatencyMultiplier}x",
                options = listOf("1x", "2x", "4x", "8x"),
                selectedIndex = latencyValues.indexOf(settings.audioLatencyMultiplier).let { if (it >= 0) it else 3 },
            ) { idx ->
                settings.audioLatencyMultiplier = latencyValues[idx]
                flashSaved(reconnectNeeded = true)
                renderActiveTab(preserveScroll = true)
            }
            addDivider(card)
            val queueValueText = if (settings.audioQueueCapacity == 0) {
                getString(R.string.audio_queue_unbounded)
            } else {
                getString(R.string.audio_queue_chunks, settings.audioQueueCapacity)
            }
            addActionRow(
                card,
                title = getString(R.string.audio_queue_capacity),
                subtitle = null,
                valueText = queueValueText,
            ) {
                val options = arrayOf(
                    getString(R.string.audio_queue_low),
                    getString(R.string.audio_queue_balanced),
                    getString(R.string.audio_queue_high),
                    getString(R.string.audio_queue_backlog),
                )
                val values = intArrayOf(10, 20, 50, 0)
                val currentIdx = values.indexOf(settings.audioQueueCapacity).coerceAtLeast(0)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.audio_queue_capacity)
                    .setSingleChoiceItems(options, currentIdx) { dlg, which ->
                        settings.audioQueueCapacity = values[which]
                        flashSaved(reconnectNeeded = true)
                        dlg.dismiss()
                        renderActiveTab(preserveScroll = true)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        addCard(root, getString(R.string.microphone_settings)) { card ->
            val rates = Settings.MicSampleRates
            addActionRow(
                card,
                title = getString(R.string.mic_sample_rate),
                subtitle = null,
                valueText = "${settings.micSampleRate} Hz",
            ) {
                val labels = rates.map { "$it Hz" }.toTypedArray()
                val curIdx = rates.indexOf(settings.micSampleRate).coerceAtLeast(0)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.mic_sample_rate)
                    .setSingleChoiceItems(labels, curIdx) { dlg, which ->
                        settings.micSampleRate = rates[which]
                        flashSaved(reconnectNeeded = true)
                        dlg.dismiss()
                        renderActiveTab(preserveScroll = true)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            addDivider(card)
            val micSources = resources.getStringArray(R.array.mic_input_sources)
            addActionRow(
                card,
                title = getString(R.string.mic_input_source),
                subtitle = null,
                valueText = micSources.getOrElse(settings.micInputSource) { getString(R.string.not_set) },
            ) {
                val curIdx = settings.micInputSource.coerceIn(0, (micSources.size - 1).coerceAtLeast(0))
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.mic_input_source)
                    .setSingleChoiceItems(micSources, curIdx) { dlg, which ->
                        settings.micInputSource = which
                        flashSaved(reconnectNeeded = true)
                        dlg.dismiss()
                        renderActiveTab(preserveScroll = true)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            addDivider(card)
            val aecAvailable = AcousticEchoCanceler.isAvailable()
            addSwitchRow(
                card,
                title = getString(R.string.mic_echo_canceler),
                subtitle = getString(if (aecAvailable) R.string.mic_echo_canceler_description else R.string.mic_feature_unsupported),
                checked = settings.micEchoCanceler,
                enabled = aecAvailable,
            ) { checked ->
                settings.micEchoCanceler = checked
                flashSaved(reconnectNeeded = true)
            }
            addDivider(card)
            val nsAvailable = NoiseSuppressor.isAvailable()
            addSwitchRow(
                card,
                title = getString(R.string.mic_noise_suppressor),
                subtitle = getString(if (nsAvailable) R.string.mic_noise_suppressor_description else R.string.mic_feature_unsupported),
                checked = settings.micNoiseSuppressor,
                enabled = nsAvailable,
            ) { checked ->
                settings.micNoiseSuppressor = checked
                flashSaved(reconnectNeeded = true)
            }
            addDivider(card)
            val agcAvailable = AutomaticGainControl.isAvailable()
            addSwitchRow(
                card,
                title = getString(R.string.mic_auto_gain_control),
                subtitle = getString(if (agcAvailable) R.string.mic_auto_gain_control_description else R.string.mic_feature_unsupported),
                checked = settings.micAutoGainControl,
                enabled = agcAvailable,
            ) { checked ->
                settings.micAutoGainControl = checked
                flashSaved(reconnectNeeded = true)
            }
        }

        addCard(root, getString(R.string.qu_set_sec_audio_cp)) { card ->
            val presets = MediaAudioBuffer.presets
            addSegmentedRow(
                card,
                title = getString(R.string.cp_opt_buffer),
                subtitle = null,
                options = listOf("300 ms", "500 ms", "1000 ms"),
                selectedIndex = presets.indexOf(AirPlayPersistence.loadMediaBufferMillis(requireContext())).coerceAtLeast(0),
            ) { idx ->
                AirPlayPersistence.saveMediaBufferMillis(requireContext(), presets[idx])
                flashSaved(reconnectNeeded = true)
            }
            addDivider(card)
            val mPct = ((settings.mediaVolumeOffset / 2) + 50) * 2
            val aPct = ((settings.assistantVolumeOffset / 2) + 50) * 2
            val nPct = ((settings.navigationVolumeOffset / 2) + 50) * 2
            addActionRow(
                card,
                title = getString(R.string.qu_set_offsets_btn),
                subtitle = null,
                valueText = getString(R.string.qu_set_offsets_summary, mPct, aPct, nPct),
            ) {
                showAudioOffsetsDialog()
            }
        }

        addCard(root, getString(R.string.qu_set_sec_controls)) { card ->
            val routingModes = listOf(
                MediaKeyRoutingPolicy.Mode.ALWAYS,
                MediaKeyRoutingPolicy.Mode.AUTO,
                MediaKeyRoutingPolicy.Mode.NEVER,
            )
            addSegmentedRow(
                card,
                title = getString(R.string.media_key_routing),
                subtitle = null,
                options = listOf(getString(R.string.playback_focus_mode_always), "Auto", getString(R.string.playback_focus_mode_never)),
                selectedIndex = routingModes.indexOf(settings.mediaKeyRouting).coerceAtLeast(0),
            ) { idx ->
                settings.mediaKeyRouting = routingModes[idx]
                flashSaved()
            }
            addDivider(card)
            addSwitchRow(
                card,
                title = getString(R.string.enable_rotary),
                subtitle = null,
                checked = settings.enableRotary,
            ) { checked ->
                settings.enableRotary = checked
                flashSaved(reconnectNeeded = true)
            }
            addDivider(card)
            addActionRow(
                card,
                title = getString(R.string.qu_set_keymap_btn),
                subtitle = getString(R.string.keymap_description),
                valueText = "${settings.keyCodes.size}",
            ) {
                findNavController().navigate(R.id.keymapFragment)
            }
        }
    }

    // -------------------------------------------------------------------------
    // TAB 4: ADVANCED & DIAGNOSTICS
    // -------------------------------------------------------------------------
    private fun buildAdvancedTab(root: LinearLayout) {
        addCard(root, getString(R.string.qu_set_sec_diag_tools)) { card ->
            card.addView(TextView(requireContext()).apply {
                text = getString(R.string.qu_set_diag_desc)
                textSize = 15f
                setTextColor(TEXT_DIM)
                setPadding(0, 0, 0, dp(14))
            })
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                pillActionButton(
                    if (exportingDiag) getString(R.string.diagnostic_saving_report) else "${getString(R.string.qu_set_export_diag)} (USB)",
                    primary = true,
                ) {
                    showUsbExportDialog()
                },
                LinearLayout.LayoutParams(0, dp(52), 1.3f).apply { marginEnd = dp(10) },
            )
            row.addView(
                pillActionButton(getString(R.string.qu_files), primary = false) {
                    CarReturnPillOverlay.showIfPermitted(requireContext(), requireActivity()::class.java)
                    val result = CarFiles.open(requireActivity()) { pickerIntent ->
                        runCatching { carFilesPickerLauncher.launch(pickerIntent) }.isSuccess
                    }
                    if (result == CarFiles.OpenResult.UNAVAILABLE) {
                        CarReturnPillOverlay.dismiss()
                        Toast.makeText(requireContext(), getString(R.string.qu_files_none), Toast.LENGTH_LONG).show()
                    }
                },
                LinearLayout.LayoutParams(0, dp(52), 1f),
            )
            card.addView(row)
            addDivider(card)
            val overlayGranted = CarReturnPillOverlay.canShow(requireContext())
            addActionRow(
                card,
                title = "Botón «← Volver a Qonecta» sobre el explorador",
                subtitle = "Muestra un botón flotante arriba a la derecha al abrir el explorador de archivos del coche",
                valueText = if (overlayGranted) "Activo ✓" else "Activar",
            ) {
                val intent = CarReturnPillOverlay.requestPermissionIntent(requireContext())
                if (overlayGranted) {
                    CarReturnPillOverlay.showIfPermitted(requireContext(), requireActivity()::class.java)
                }
                runCatching { overlayPermLauncher.launch(intent) }.onFailure {
                    CarReturnPillOverlay.dismiss()
                    Toast.makeText(requireContext(), R.string.diagnostic_file_picker_unavailable, Toast.LENGTH_LONG).show()
                }
            }
        }

        addCard(root, getString(R.string.qu_set_sec_backup)) { card ->
            addActionRow(
                card,
                title = getString(R.string.qu_set_backup_export),
                subtitle = null,
                valueText = ".json",
            ) {
                runCatching { exportBackupLauncher.launch(SettingsBackupManager.defaultFileName()) }
                    .onFailure { Toast.makeText(requireContext(), R.string.diagnostic_file_picker_unavailable, Toast.LENGTH_LONG).show() }
            }
            addDivider(card)
            addActionRow(
                card,
                title = getString(R.string.qu_set_backup_import),
                subtitle = null,
                valueText = null,
            ) {
                runCatching { importBackupLauncher.launch(SettingsBackupManager.IMPORT_MIME_TYPES) }
                    .onFailure { Toast.makeText(requireContext(), R.string.diagnostic_file_picker_unavailable, Toast.LENGTH_LONG).show() }
            }
            addDivider(card)
            card.addView(
                dangerActionButton(getString(R.string.qu_set_reset_all)) {
                    MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                        .setTitle(R.string.qu_set_reset_all)
                        .setMessage(R.string.qu_set_reset_confirm_msg)
                        .setPositiveButton(R.string.reset_settings) { _, _ ->
                            SettingsBackupManager.resetFromContext(requireContext())
                            flashSaved()
                            requireActivity().recreate()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) },
            )
        }

        addCard(root, getString(R.string.qu_set_sec_expert)) { card ->
            addActionRow(
                card,
                title = getString(if (expertExpanded) R.string.qu_set_hide_expert else R.string.qu_set_show_expert),
                subtitle = null,
                valueText = if (expertExpanded) "▲" else "▼",
            ) {
                expertExpanded = !expertExpanded
                renderActiveTab(preserveScroll = true)
            }

            if (expertExpanded) {
                addDivider(card)
                addSwitchRow(card, getString(R.string.gps_for_navigation), null, settings.useGpsForNavigation) {
                    settings.useGpsForNavigation = it; flashSaved()
                }
                addDivider(card)
                addSwitchRow(card, getString(R.string.show_navigation_notifications), null, settings.showNavigationNotifications) {
                    settings.showNavigationNotifications = it; flashSaved()
                }
                addDivider(card)
                addSwitchRow(card, getString(R.string.kill_on_disconnect), null, settings.killOnDisconnect) {
                    settings.killOnDisconnect = it; flashSaved()
                }
                addDivider(card)
                addSwitchRow(card, getString(R.string.show_fps_counter), null, settings.showFpsCounter) {
                    settings.showFpsCounter = it; flashSaved()
                }
                addDivider(card)
                addSwitchRow(card, getString(R.string.force_software_decoding), null, settings.forceSoftwareDecoding) {
                    settings.forceSoftwareDecoding = it; flashSaved(reconnectNeeded = true)
                }
                addDivider(card)
                addSwitchRow(card, getString(R.string.debug_video_low_latency), null, settings.debugVideoLowLatency) {
                    settings.debugVideoLowLatency = it; flashSaved(reconnectNeeded = true)
                }
                addDivider(card)
                addSwitchRow(card, getString(R.string.use_libusb), null, settings.useLibusb) {
                    settings.useLibusb = it; flashSaved(reconnectNeeded = true)
                }
                addDivider(card)
                addSwitchRow(
                    card,
                    getString(R.string.qu_set_p2p_24_title),
                    getString(R.string.qu_set_p2p_24_desc),
                    settings.debugForceP2pBand24,
                ) {
                    settings.debugForceP2pBand24 = it; flashSaved(reconnectNeeded = true)
                }
                addDivider(card)
                addSwitchRow(
                    card,
                    getString(R.string.qu_set_p2p_legacy_title),
                    getString(R.string.qu_set_p2p_legacy_desc),
                    settings.p2pLegacyFiveGhz,
                ) {
                    settings.p2pLegacyFiveGhz = it; flashSaved(reconnectNeeded = true)
                }
                addDivider(card)
                addSwitchRow(
                    card,
                    getString(R.string.qu_set_modern_wpp_title),
                    getString(R.string.qu_set_modern_wpp_desc),
                    settings.nativeWifiVersionExchange,
                ) {
                    settings.nativeWifiVersionExchange = it; flashSaved(reconnectNeeded = true)
                }
                addDivider(card)
                addActionRow(card, getString(R.string.qu_set_sim_iphone), null, "Demo") {
                    startActivity(
                        Intent(requireContext(), CarPlayHostActivity::class.java)
                            .putExtra(CarPlayLoopbackSimulator.EXTRA_SIMULATE_IPHONE, true)
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // TAB 5: ABOUT & SUPPORT (PAYPAL FIRST + KO-FI + QONECTA HUB QR)
    // -------------------------------------------------------------------------
    private fun buildAboutTab(root: LinearLayout) {
        addCard(root, getString(R.string.core_app_name)) { card ->
            val top = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(12))
            }
            top.addView(ImageView(requireContext()).apply {
                setImageResource(R.drawable.hql_logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(16) })

            val col = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(requireContext()).apply {
                text = "Qonecta Unit CORE  ·  v${BuildConfig.VERSION_NAME}"
                textSize = 20f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(TEXT_MAIN)
            })
            col.addView(TextView(requireContext()).apply {
                text = getString(R.string.core_home_subtitle)
                textSize = 14.5f
                setTextColor(ACCENT)
                setPadding(0, dp(3), 0, 0)
            })
            top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(top)

            addDivider(card)
            addActionRow(card, getString(R.string.qu_set_open_about), null, "AGPL-3.0") {
                findNavController().navigate(R.id.aboutFragment)
            }
        }

        addCard(root, getString(R.string.qu_set_sec_support)) { card ->
            card.addView(TextView(requireContext()).apply {
                text = getString(R.string.core_donate_dialog_intro)
                textSize = 15f
                setTextColor(TEXT_DIM)
                setLineSpacing(0f, 1.2f)
                setPadding(0, 0, 0, dp(16))
            })

            val qrRow = LinearLayout(requireContext()).apply {
                orientation = if (resources.configuration.screenWidthDp >= 720) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val wide = resources.configuration.screenWidthDp >= 720
            fun qrLp(last: Boolean) = if (wide) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (!last) marginEnd = dp(12)
                }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (!last) bottomMargin = dp(12)
                }
            }
            // PayPal FIRST, Ko-fi SECOND, Qonecta Hub THIRD
            qrRow.addView(
                buildInlineQrCard(
                    title = getString(R.string.core_donate_paypal_card),
                    sub = "paypal.me/CharlysEV",
                    url = SupportDonationsDialog.PAYPAL_URL,
                    accentColor = ACCENT,
                ),
                qrLp(last = false),
            )
            qrRow.addView(
                buildInlineQrCard(
                    title = getString(R.string.core_donate_kofi_card),
                    sub = "ko-fi.com/charlysev",
                    url = SupportDonationsDialog.KOFI_URL,
                    accentColor = WARNING,
                ),
                qrLp(last = false),
            )
            qrRow.addView(
                buildInlineQrCard(
                    title = getString(R.string.qu_set_hub_qr_title),
                    sub = "qonecta-hub.qonecta.workers.dev",
                    url = SupportDonationsDialog.HUB_URL,
                    accentColor = OK_GREEN,
                ),
                qrLp(last = true),
            )
            card.addView(qrRow)
        }
    }

    private fun buildInlineQrCard(title: String, sub: String, url: String, accentColor: Int): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(SURFACE_HI, accentColor, radiusDp = 18, strokeDp = 1)
        }
        box.addView(TextView(ctx).apply {
            text = title
            textSize = 15.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            maxLines = 2
        })
        val bmp = runCatching { QrCodeGenerator.generateQrCode(url, dp(148)) }.getOrNull()
        if (bmp != null) {
            box.addView(ImageView(ctx).apply {
                setImageBitmap(bmp)
                contentDescription = title
                background = rounded(Color.WHITE, Color.WHITE, radiusDp = 12, strokeDp = 0)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, LinearLayout.LayoutParams(dp(156), dp(156)).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            })
        }
        box.addView(TextView(ctx).apply {
            text = sub
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTextColor(TEXT_MAIN)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        return box
    }

    // -------------------------------------------------------------------------
    // DIALOGS & HELPERS
    // -------------------------------------------------------------------------
    private fun showCustomInsetsDialog() {
        val dlgView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_insets, null)
        val inputTop = dlgView.findViewById<EditText>(R.id.input_top)
        val inputBottom = dlgView.findViewById<EditText>(R.id.input_bottom)
        val inputLeft = dlgView.findViewById<EditText>(R.id.input_left)
        val inputRight = dlgView.findViewById<EditText>(R.id.input_right)

        inputTop.setText(settings.insetTop.toString())
        inputBottom.setText(settings.insetBottom.toString())
        inputLeft.setText(settings.insetLeft.toString())
        inputRight.setText(settings.insetRight.toString())

        fun bindStep(btnId: Int, input: EditText, delta: Int) {
            dlgView.findViewById<View>(btnId).setOnClickListener {
                val cur = input.text.toString().toIntOrNull() ?: 0
                input.setText((cur + delta).coerceAtLeast(0).toString())
            }
        }
        bindStep(R.id.btn_top_minus, inputTop, -10)
        bindStep(R.id.btn_top_plus, inputTop, 10)
        bindStep(R.id.btn_bottom_minus, inputBottom, -10)
        bindStep(R.id.btn_bottom_plus, inputBottom, 10)
        bindStep(R.id.btn_left_minus, inputLeft, -10)
        bindStep(R.id.btn_left_plus, inputLeft, 10)
        bindStep(R.id.btn_right_minus, inputRight, -10)
        bindStep(R.id.btn_right_plus, inputRight, 10)

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.qu_set_insets_btn)
            .setView(dlgView)
            .setPositiveButton(R.string.cp_btn_save) { _, _ ->
                settings.insetTop = (inputTop.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                settings.insetBottom = (inputBottom.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                settings.insetLeft = (inputLeft.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                settings.insetRight = (inputRight.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                flashSaved(reconnectNeeded = true)
                renderActiveTab(preserveScroll = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAudioOffsetsDialog() {
        val dlgView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_audio_offsets, null)
        val seekMedia = dlgView.findViewById<SeekBar>(R.id.seek_media)
        val seekAssistant = dlgView.findViewById<SeekBar>(R.id.seek_assistant)
        val seekNav = dlgView.findViewById<SeekBar>(R.id.seek_navigation)
        val txtMedia = dlgView.findViewById<TextView>(R.id.text_media_val)
        val txtAssistant = dlgView.findViewById<TextView>(R.id.text_assistant_val)
        val txtNav = dlgView.findViewById<TextView>(R.id.text_navigation_val)

        seekMedia.progress = ((settings.mediaVolumeOffset / 2) + 50).coerceIn(0, 100)
        seekAssistant.progress = ((settings.assistantVolumeOffset / 2) + 50).coerceIn(0, 100)
        seekNav.progress = ((settings.navigationVolumeOffset / 2) + 50).coerceIn(0, 100)

        fun updateLabels() {
            txtMedia.text = "${seekMedia.progress * 2}%"
            txtAssistant.text = "${seekAssistant.progress * 2}%"
            txtNav.text = "${seekNav.progress * 2}%"
        }
        updateLabels()
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = updateLabels()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        seekMedia.setOnSeekBarChangeListener(listener)
        seekAssistant.setOnSeekBarChangeListener(listener)
        seekNav.setOnSeekBarChangeListener(listener)

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.qu_set_offsets_btn)
            .setView(dlgView)
            .setPositiveButton(R.string.cp_btn_save) { _, _ ->
                settings.mediaVolumeOffset = (seekMedia.progress - 50) * 2
                settings.assistantVolumeOffset = (seekAssistant.progress - 50) * 2
                settings.navigationVolumeOffset = (seekNav.progress - 50) * 2
                flashSaved(reconnectNeeded = true)
                renderActiveTab(preserveScroll = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun activateCarHotspot() {
        val ctx = requireContext()
        settings.nativeApTransport = 1
        settings.wifiConnectionMode = 3
        settings.wifi5gEnabled = false
        settings.hotspotModeEnabled = true
        AirPlayPersistence.saveWirelessHotspotMode(ctx, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveWirelessEnabled(ctx, true)
        ContextCompat.startForegroundService(ctx, Intent(ctx, AapService::class.java).apply {
            action = AapService.ACTION_START_WIRELESS
        })
    }

    private fun sharedHotspotSsid(): String =
        settings.hotspotSsid.trim().ifEmpty { AirPlayPersistence.loadManualHotspotSsid(requireContext()).trim() }

    private fun sharedHotspotPassword(): String =
        settings.hotspotPassword.ifEmpty { AirPlayPersistence.loadManualHotspotPassphrase(requireContext()) }

    private fun saveSharedHotspot(ssid: String, pass: String) {
        val ctx = requireContext()
        settings.hotspotSsid = ssid
        settings.hotspotPassword = pass
        AirPlayPersistence.saveManualHotspotSsid(ctx, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(ctx, pass)
        AirPlayPersistence.saveManualHotspotSecurity(ctx, ManualHotspotValidation.securityFor(pass))
        AirPlayPersistence.saveManualHotspotBand(ctx, ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(ctx, 0)
    }

    private fun showHotspotDialog(curSsid: String, curPass: String, onSaved: (String, String) -> Unit) {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(8))
        }
        val ssidInput = EditText(ctx).apply {
            hint = getString(R.string.setup_hotspot_name)
            setText(curSsid)
            setSingleLine()
            setTextColor(TEXT_MAIN)
            setHintTextColor(TEXT_FAINT)
        }
        val passInput = EditText(ctx).apply {
            hint = getString(R.string.setup_hotspot_password)
            setText(curPass)
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

        val dlg = MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
            .setTitle(R.string.setup_car_hotspot_details)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.cp_btn_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
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
        dlg.show()
    }

    private fun diagFileName() = "QonectaUnit-core-report-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"

    private fun savedUsbTreeUriOrNull(): Uri? {
        val raw = settings.usbDiagTreeUri
        if (raw.isBlank()) return null
        val parsed = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        return parsed.takeIf { CarFiles.hasPersistedWriteAccess(requireContext(), it) }
    }

    private fun showUsbExportDialog() {
        val ctx = requireContext()
        val savedTree = savedUsbTreeUriOrNull()
        if (savedTree != null) {
            val folderLabel = CarFiles.friendlyTreeLabel(savedTree.toString())
            MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
                .setTitle("Exportar diagnóstico al pendrive USB")
                .setMessage("Carpeta USB guardada:\n$folderLabel\n\nSe guardará un informe .txt directo en el pendrive sin ocupar memoria interna del coche ni salir de Qonecta.")
                .setPositiveButton("💾 Guardar en USB") { _, _ -> exportUnifiedDiagnosticsToTree(savedTree) }
                .setNeutralButton("📂 Cambiar carpeta USB") { _, _ -> launchUsbTreePicker() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
                .setTitle("Exportar diagnóstico al pendrive USB")
                .setMessage("Elige una carpeta de tu pendrive USB una sola vez: las próximas exportaciones se guardarán directo con 1 toque sin tener que abrir ni cerrar el explorador del coche.")
                .setPositiveButton("📂 Elegir carpeta USB") { _, _ -> launchUsbTreePicker() }
                .setNeutralButton("📄 Guardar 1 archivo") { _, _ -> launchUsbDiagPicker() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun launchUsbTreePicker() {
        val ctx = requireContext()
        val intent = CarFiles.openUsbTreeIntent(ctx)
        CarReturnPillOverlay.showIfPermitted(ctx, requireActivity()::class.java)
        runCatching { openUsbTreeLauncher.launch(intent) }.onFailure {
            CarReturnPillOverlay.dismiss()
            launchUsbDiagPicker()
        }
    }

    private fun launchUsbDiagPicker() {
        val ctx = requireContext()
        val intent = CarFiles.createDocumentIntent(ctx, diagFileName(), "text/plain")
        CarReturnPillOverlay.showIfPermitted(ctx, requireActivity()::class.java)
        runCatching { createDiagDocLauncher.launch(intent) }.onFailure {
            CarReturnPillOverlay.dismiss()
            Toast.makeText(ctx, R.string.diagnostic_file_picker_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun buildUnifiedDiagnosticReport(appCtx: Context): String = buildString {
        append(DiagnosticReport.build(appCtx))
        appendLine()
        appendLine("--- Qonecta 5 GHz Accelerator ---")
        appendLine("Channel: ${AapService.wifiDirectChannelInfo.value ?: "idle"}")
        appendLine("Coexistence warning: ${AapService.wifiCoexistenceWarning.value ?: "none"}")
        for (name in com.shilapi.xcertplay.SessionLogFile.REPORT_NAMES) {
            val file = java.io.File(appCtx.filesDir, "logs/$name")
            if (file.isFile && file.length() > 0L) {
                appendLine()
                appendLine("--- CarPlay $name ---")
                file.useLines { lines ->
                    lines.forEach { line ->
                        com.shilapi.xcertplay.DiagnosticRedactor.redact(line)?.let { appendLine(it) }
                    }
                }
            }
        }
    }

    private fun exportUnifiedDiagnostics(destination: Uri) =
        writeUnifiedDiagnostics(fromTree = false) { destination }

    private fun exportUnifiedDiagnosticsToTree(treeUri: Uri) =
        writeUnifiedDiagnostics(fromTree = true) { appCtx ->
            CarFiles.createFileInTree(appCtx, treeUri, diagFileName(), "text/plain")
                ?: error("USB folder unavailable")
        }

    private fun writeUnifiedDiagnostics(fromTree: Boolean, resolveTargetUri: (Context) -> Uri) {
        if (exportingDiag) return
        exportingDiag = true
        renderActiveTab(preserveScroll = true)
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = buildUnifiedDiagnosticReport(appCtx)
                    val target = resolveTargetUri(appCtx)
                    DiagnosticExportStore.write(appCtx.contentResolver, target, report)
                    target
                }
            }
            exportingDiag = false
            if (!isAdded) return@launch
            renderActiveTab(preserveScroll = true)
            result.onSuccess { uri ->
                val msg = if (fromTree) {
                    "Guardado en la carpeta USB:\n${CarFiles.friendlyTreeLabel(settings.usbDiagTreeUri)}"
                } else {
                    getString(R.string.diagnostic_saved_to_your_selected_location)
                }
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.diagnostic_report_saved)
                    .setMessage(msg)
                    .setPositiveButton(R.string.share) { _, _ ->
                        runCatching {
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newRawUri("Diagnostic report", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, getString(R.string.diagnostic_share_diagnostic_report)))
                        }.onFailure { Toast.makeText(requireContext(), R.string.diagnostic_share_manually, Toast.LENGTH_LONG).show() }
                    }
                    .setNegativeButton(R.string.diagnostic_done, null)
                    .show()
            }.onFailure {
                val dlg = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.diagnostic_could_not_save_the_report)
                    .setMessage(
                        if (fromTree) "No se pudo escribir en la carpeta USB guardada (comprueba que el pendrive está enchufado o elige otra carpeta USB)."
                        else getString(R.string.diagnostic_save_failure_hint),
                    )
                    .setNegativeButton(R.string.close, null)
                if (fromTree) {
                    dlg.setPositiveButton("📂 Elegir carpeta USB") { _, _ -> launchUsbTreePicker() }
                    dlg.setNeutralButton("📄 Guardar 1 archivo") { _, _ -> launchUsbDiagPicker() }
                } else {
                    dlg.setPositiveButton(R.string.diagnostic_choose_location) { _, _ -> launchUsbDiagPicker() }
                }
                dlg.show()
            }
        }
    }

    private fun exportBackupToUri(uri: Uri) {
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { SettingsBackupManager.exportToUri(requireContext(), uri) }
            }
            if (!isAdded) return@launch
            Toast.makeText(
                requireContext(),
                if (res.isSuccess) R.string.settings_saved else R.string.diagnostic_could_not_save_the_report,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun importBackupFromUri(uri: Uri) {
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { SettingsBackupManager.importFromUri(requireContext(), uri) }
            }
            if (!isAdded) return@launch
            if (res.isSuccess) {
                flashSaved()
                requireActivity().recreate()
            } else {
                Toast.makeText(requireContext(), R.string.diagnostic_could_not_save_the_report, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadBondedBtDevices(): List<android.bluetooth.BluetoothDevice> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        return runCatching {
            val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
            if (adapter != null && adapter.isEnabled) {
                adapter.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }
            } else emptyList()
        }.getOrDefault(emptyList())
    }

    private fun openSystemIntent(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(SystemSettings.ACTION_SETTINGS)) }
        }
    }

    // -------------------------------------------------------------------------
    // PURE QONECTA COCKPIT UI PRIMITIVES (ZERO BLUE / ZERO FLAT ANDROID ROWS)
    // -------------------------------------------------------------------------
    private fun addCard(parent: LinearLayout, sectionTitle: String, body: (LinearLayout) -> Unit) {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = rounded(CARD_BG, OUTLINE, radiusDp = 22, strokeDp = 1)
        }
        card.addView(TextView(requireContext()).apply {
            text = sectionTitle.uppercase(Locale.getDefault())
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(ACCENT)
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(12))
        })
        body(card)
        parent.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
        })
    }

    private fun addDivider(parent: LinearLayout) {
        parent.addView(View(requireContext()).apply {
            setBackgroundColor(DIVIDER)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })
    }

    private fun addSwitchRow(
        parent: LinearLayout,
        title: String,
        subtitle: String?,
        checked: Boolean,
        enabled: Boolean = true,
        onChanged: (Boolean) -> Unit,
    ) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.55f
        }
        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(16)
            }
        }
        textCol.addView(TextView(ctx).apply {
            text = title
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(TEXT_MAIN)
        })
        if (!subtitle.isNullOrBlank()) {
            textCol.addView(TextView(ctx).apply {
                text = subtitle
                textSize = 13.5f
                setTextColor(TEXT_DIM)
                setPadding(0, dp(3), 0, 0)
            })
        }
        row.addView(textCol)

        val sw = SwitchCompat(ctx).apply {
            isChecked = checked
            isEnabled = enabled
            minHeight = dp(48)
            fun applyTint(on: Boolean) {
                thumbTintList = ColorStateList.valueOf(if (on) ACCENT else 0xFF8FA5BA.toInt())
                trackTintList = ColorStateList.valueOf(if (on) 0x5500E5C7 else 0xFF233243.toInt())
            }
            applyTint(checked)
            setOnCheckedChangeListener { _, isOn ->
                applyTint(isOn)
                onChanged(isOn)
            }
        }
        row.addView(sw)
        row.setOnClickListener { if (enabled) sw.toggle() }
        parent.addView(row)
    }

    private fun addSegmentedRow(
        parent: LinearLayout,
        title: String,
        subtitle: String?,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
    ) {
        val ctx = requireContext()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(TextView(ctx).apply {
            text = title
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(TEXT_MAIN)
        })
        if (!subtitle.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = subtitle
                textSize = 13.5f
                setTextColor(TEXT_DIM)
                setPadding(0, dp(3), 0, 0)
            })
        }
        val rail = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(RAIL_BG, OUTLINE, radiusDp = 16, strokeDp = 1)
        }
        options.forEachIndexed { idx, label ->
            val active = idx == selectedIndex
            val btn = TextView(ctx).apply {
                text = label
                textSize = 14.5f
                typeface = Typeface.create("sans-serif-medium", if (active) Typeface.BOLD else Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextColor(if (active) ACCENT else TEXT_DIM)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rippleRounded(
                    fill = if (active) ACCENT_DARK_BG else Color.TRANSPARENT,
                    stroke = if (active) ACCENT else Color.TRANSPARENT,
                    radiusDp = 12,
                    strokeDp = if (active) 2 else 0,
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (idx != selectedIndex) {
                        onSelect(idx)
                        renderActiveTab(preserveScroll = true)
                    }
                }
            }
            rail.addView(btn, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                if (idx > 0) marginStart = dp(4)
            })
        }
        col.addView(rail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        parent.addView(col)
    }

    private fun addActionRow(
        parent: LinearLayout,
        title: String,
        subtitle: String?,
        valueText: String?,
        onClick: () -> Unit,
    ) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
            background = rippleRounded(Color.TRANSPARENT, Color.TRANSPARENT, radiusDp = 12, strokeDp = 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
        }
        col.addView(TextView(ctx).apply {
            text = title
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(TEXT_MAIN)
        })
        if (!subtitle.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = subtitle
                textSize = 13.5f
                setTextColor(TEXT_DIM)
                setPadding(0, dp(3), 0, 0)
            })
        }
        row.addView(col)

        if (!valueText.isNullOrBlank()) {
            row.addView(TextView(ctx).apply {
                text = valueText
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(ACCENT)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = rounded(SURFACE_HI, OUTLINE_STRONG, radiusDp = 12, strokeDp = 1)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })
        }
        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ColorStateList.valueOf(TEXT_FAINT)
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        parent.addView(row)
    }

    private fun pillActionButton(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(requireContext()).apply {
            text = label
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(if (primary) ON_ACCENT else TEXT_MAIN)
            background = rippleRounded(
                fill = if (primary) ACCENT else SURFACE_HI,
                stroke = if (primary) ACCENT else OUTLINE_STRONG,
                radiusDp = 14,
                strokeDp = 1,
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun compactBadgeButton(label: String, highlighted: Boolean, onClick: () -> Unit): TextView =
        TextView(requireContext()).apply {
            text = label
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(if (highlighted) ACCENT else TEXT_DIM)
            background = rippleRounded(
                fill = if (highlighted) ACCENT_DARK_BG else SURFACE_HI,
                stroke = if (highlighted) ACCENT else OUTLINE_STRONG,
                radiusDp = 12,
                strokeDp = 1,
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun dangerActionButton(label: String, onClick: () -> Unit): TextView =
        TextView(requireContext()).apply {
            text = label
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(ERROR_RED)
            background = rippleRounded(ERROR_BG, ERROR_RED, radiusDp = 14, strokeDp = 1)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int, strokeDp: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
    }

    private fun rippleRounded(fill: Int, stroke: Int, radiusDp: Int, strokeDp: Int) = RippleDrawable(
        ColorStateList.valueOf(0x3300E5C7),
        rounded(fill, stroke, radiusDp, strokeDp),
        null,
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        const val TAB_ESSENTIALS = 0
        const val TAB_CONNECTION = 1
        const val TAB_DISPLAY = 2
        const val TAB_AUDIO = 3
        const val TAB_ADVANCED = 4
        const val TAB_ABOUT = 5

        private const val KEY_ACTIVE_TAB = "qset_active_tab"
        private const val KEY_EXPERT_EXPANDED = "qset_expert_expanded"

        private const val CARD_BG = 0xFF0F1722.toInt()
        private const val RAIL_BG = 0xFF080D14.toInt()
        private const val SURFACE_HI = 0xFF15202E.toInt()
        private const val OUTLINE = 0xFF223246.toInt()
        private const val OUTLINE_STRONG = 0xFF2E4259.toInt()
        private const val DIVIDER = 0xFF1A2535.toInt()
        private const val ACCENT = 0xFF00E5C7.toInt()
        private const val ACCENT_DARK_BG = 0xFF0E2C30.toInt()
        private const val ON_ACCENT = 0xFF04150E.toInt()
        private const val TEXT_MAIN = 0xFFEAF2F7.toInt()
        private const val TEXT_DIM = 0xFF9EB3C7.toInt()
        private const val TEXT_FAINT = 0xFF70859B.toInt()
        private const val OK_GREEN = 0xFF3AD29F.toInt()
        private const val WARNING = 0xFFFFB547.toInt()
        private const val ERROR_RED = 0xFFFF6B7A.toInt()
        private const val ERROR_BG = 0xFF3A1A22.toInt()
    }
}
