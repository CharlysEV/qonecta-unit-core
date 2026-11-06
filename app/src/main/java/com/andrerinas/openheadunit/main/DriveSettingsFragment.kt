package com.andrerinas.openheadunit.main

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.cluster.ClusterMapController
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.headqlink.unit.bridge.NavPoint
import com.headqlink.unit.bridgehost.CompanionBridgeHost
import com.headqlink.unit.extended.ExtendedHubView
import com.headqlink.unit.extended.OtaSelfUpdater
import com.headqlink.unit.extended.QonectaTheme

class DriveSettingsFragment : Fragment(R.layout.fragment_drive_settings) {
    private var activeDialog: Dialog? = null

    private val apkFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            OtaSelfUpdater.installFromContentUri(requireContext(), uri)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        QonectaTheme.ensureLoaded(requireContext())
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            requireActivity().finish()
        }
        mapOf(
            R.id.da_connection_setup to R.id.connectionSetupFragment,
            R.id.da_automation to R.id.autoStartFragment,
            R.id.da_display to R.id.settingsFragment,
            R.id.da_permissions to R.id.permissionsFragment,
            R.id.da_diagnostics to R.id.diagnosticsFragment,
            R.id.da_about to R.id.aboutFragment
        ).forEach { (button, destination) ->
            view.findViewById<View>(button).setOnClickListener { findNavController().navigate(destination) }
        }

        val openOtaUpdater = {
            activeDialog?.dismiss()
            activeDialog = OtaSelfUpdater.showDialog(requireContext()) {
                launchSystemApkPicker()
            }
        }
        view.findViewById<View>(R.id.da_ota_updater)?.setOnClickListener { openOtaUpdater() }
        view.findViewById<MaterialButton>(R.id.da_ota_updater_btn)?.setOnClickListener { openOtaUpdater() }

        setupCompanionCard(view)

        val settings = App.provide(requireContext()).settings
        view.findViewById<MaterialButton>(R.id.da_ext_mode_full)?.setOnClickListener {
            settings.extendedMode = true
            settings.extendedPanelPercent = 30
            updateExtendedButtons(view)
        }
        view.findViewById<MaterialButton>(R.id.da_ext_mode_compact)?.setOnClickListener {
            settings.extendedMode = true
            settings.extendedPanelPercent = 22
            updateExtendedButtons(view)
        }
        view.findViewById<MaterialButton>(R.id.da_ext_mode_off)?.setOnClickListener {
            settings.extendedMode = false
            updateExtendedButtons(view)
        }
        view.findViewById<MaterialButton>(R.id.da_ext_open_hub)?.setOnClickListener {
            activeDialog?.dismiss()
            activeDialog = ExtendedHubView.showDialog(requireContext()).also { dlg ->
                dlg.setOnDismissListener { updateExtendedButtons(view) }
            }
        }
        view.findViewById<MaterialButton>(R.id.da_cluster_shift_left)?.setOnClickListener {
            settings.clusterMarkerX = (settings.clusterMarkerX - 1).coerceIn(-5, 5)
            ClusterMapController.refreshSurfaceLayout()
            updateExtendedButtons(view)
        }
        view.findViewById<MaterialButton>(R.id.da_cluster_shift_down)?.setOnClickListener {
            settings.clusterMarkerY = (settings.clusterMarkerY + 1).coerceIn(-5, 5)
            ClusterMapController.refreshSurfaceLayout()
            updateExtendedButtons(view)
        }
        view.findViewById<MaterialButton>(R.id.da_cluster_shift_reset)?.setOnClickListener {
            settings.clusterMarkerX = 0
            settings.clusterMarkerY = 0
            ClusterMapController.refreshSurfaceLayout()
            updateExtendedButtons(view)
        }
        view.findViewById<MaterialButton>(R.id.da_cluster_shift_up)?.setOnClickListener {
            settings.clusterMarkerY = (settings.clusterMarkerY - 1).coerceIn(-5, 5)
            ClusterMapController.refreshSurfaceLayout()
            updateExtendedButtons(view)
        }
        view.findViewById<MaterialButton>(R.id.da_cluster_shift_right)?.setOnClickListener {
            settings.clusterMarkerX = (settings.clusterMarkerX + 1).coerceIn(-5, 5)
            ClusterMapController.refreshSurfaceLayout()
            updateExtendedButtons(view)
        }
        updateExtendedButtons(view)
    }

    private fun updateExtendedButtons(view: View) {
        val settings = App.provide(requireContext()).settings
        val accent = QonectaTheme.accent
        val onAccent = QonectaTheme.onAccent
        val surfaceTop = QonectaTheme.surfaceTop
        val outline = QonectaTheme.outline
        val textMain = QonectaTheme.text

        val fullBtn = view.findViewById<MaterialButton>(R.id.da_ext_mode_full)
        val compactBtn = view.findViewById<MaterialButton>(R.id.da_ext_mode_compact)
        val offBtn = view.findViewById<MaterialButton>(R.id.da_ext_mode_off)
        val openHubBtn = view.findViewById<MaterialButton>(R.id.da_ext_open_hub)

        val isFull = settings.extendedMode && settings.extendedPanelPercent >= 28
        val isCompact = settings.extendedMode && settings.extendedPanelPercent < 28
        val isOff = !settings.extendedMode

        fun styleChoice(btn: MaterialButton?, active: Boolean) {
            btn ?: return
            if (active) {
                btn.backgroundTintList = ColorStateList.valueOf(accent)
                btn.setTextColor(onAccent)
                btn.strokeWidth = 0
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(surfaceTop)
                btn.setTextColor(textMain)
                btn.strokeColor = ColorStateList.valueOf(outline)
                btn.strokeWidth = (2 * resources.displayMetrics.density).toInt()
            }
        }

        styleChoice(fullBtn, isFull)
        styleChoice(compactBtn, isCompact)
        styleChoice(offBtn, isOff)

        openHubBtn?.apply {
            backgroundTintList = ColorStateList.valueOf(surfaceTop)
            setTextColor(accent)
            strokeColor = ColorStateList.valueOf(accent)
        }

        view.findViewById<MaterialButton>(R.id.da_ota_updater_btn)?.apply {
            backgroundTintList = ColorStateList.valueOf(surfaceTop)
            setTextColor(accent)
            strokeColor = ColorStateList.valueOf(accent)
        }

        val shiftLeftBtn = view.findViewById<MaterialButton>(R.id.da_cluster_shift_left)
        val shiftDownBtn = view.findViewById<MaterialButton>(R.id.da_cluster_shift_down)
        val shiftResetBtn = view.findViewById<MaterialButton>(R.id.da_cluster_shift_reset)
        val shiftUpBtn = view.findViewById<MaterialButton>(R.id.da_cluster_shift_up)
        val shiftRightBtn = view.findViewById<MaterialButton>(R.id.da_cluster_shift_right)
        val shiftX = settings.clusterMarkerX * 40
        val shiftY = settings.clusterMarkerY * 25
        shiftResetBtn?.text = when {
            shiftX == 0 && shiftY == 0 -> "Centrado C10"
            else -> {
                val xPart = if (shiftX == 0) "" else if (shiftX > 0) "+${shiftX}X" else "${shiftX}X"
                val yPart = if (shiftY == 0) "" else if (shiftY > 0) "+${shiftY}Y▼" else "${shiftY}Y▲"
                listOf(xPart, yPart).filter { it.isNotEmpty() }.joinToString(" · ")
            }
        }
        styleChoice(shiftLeftBtn, settings.clusterMarkerX < 0)
        styleChoice(shiftDownBtn, settings.clusterMarkerY > 0)
        styleChoice(shiftResetBtn, settings.clusterMarkerX == 0 && settings.clusterMarkerY == 0)
        styleChoice(shiftUpBtn, settings.clusterMarkerY < 0)
        styleChoice(shiftRightBtn, settings.clusterMarkerX > 0)
    }

    private fun launchSystemApkPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"),
                )
            }
            apkFilePickerLauncher.launch(intent)
        } catch (_: Exception) {
        }
    }

    override fun onPause() {
        activeDialog?.dismiss()
        activeDialog = null
        super.onPause()
    }

    // ------------------------------------------------------------------------------------------
    // Qonecta Unit Companion (puente con el móvil)
    // ------------------------------------------------------------------------------------------

    private var companionListener: ((CompanionBridgeHost.Status) -> Unit)? = null

    private fun setupCompanionCard(view: View) {
        val statusTv = view.findViewById<TextView>(R.id.da_companion_status) ?: return
        CompanionBridgeHost.start(requireContext())
        val listener: (CompanionBridgeHost.Status) -> Unit = { s ->
            statusTv.text = describeCompanion(s)
            statusTv.setTextColor(if (s.isConnected) QonectaTheme.green else QonectaTheme.text)
        }
        companionListener = listener
        CompanionBridgeHost.addListener(listener)

        view.findViewById<MaterialButton>(R.id.da_companion_pair)?.setOnClickListener { showPairDialog() }
        view.findViewById<MaterialButton>(R.id.da_companion_test)?.setOnClickListener {
            val dest = NavPoint(40.416775, -3.703790, "Puerta del Sol, Madrid (prueba)")
            val via = listOf(NavPoint(40.453054, -3.688344, "Parada de prueba (Bernabéu)"))
            CompanionBridgeHost.sendRoute(dest, via) { r ->
                val msg = when (r) {
                    is CompanionBridgeHost.SendResult.Sent -> "✓ Ruta de prueba enviada a ${r.device} por ${r.transport} (${r.viaSent} parada). Mira Google Maps en Android Auto."
                    is CompanionBridgeHost.SendResult.NeedsTap -> "Ruta enviada: toca la notificación en ${r.device}. Para que se abra sola, da a Companion el permiso «Mostrar sobre otras apps»."
                    is CompanionBridgeHost.SendResult.Failed -> "⚠ ${r.message}"
                    CompanionBridgeHost.SendResult.NotConnected -> "⚠ Ningún móvil conectado. Abre Qonecta Unit Companion en el móvil."
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        }
        view.findViewById<MaterialButton>(R.id.da_companion_forget)?.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("¿Olvidar todos los móviles?")
                .setMessage("Tendrás que volver a emparejar el móvil con un código nuevo.")
                .setPositiveButton("Olvidar") { _, _ -> CompanionBridgeHost.forgetAllPhones() }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun describeCompanion(s: CompanionBridgeHost.Status): String {
        val conn = when {
            s.isConnected -> "● Conectado: " + s.connectedDevices.joinToString(", ") { (name, via) -> "$name por $via" }
            s.pairedCount > 0 -> "○ ${s.pairedCount} móvil(es) emparejado(s), ahora sin conexión"
            else -> "○ Sin móvil emparejado"
        }
        val listening = buildList {
            add(if (s.tcpListening) "Wi-Fi ✓" else "Wi-Fi ✗")
            add(if (s.bluetoothListening) "Bluetooth ✓" else "Bluetooth ✗${s.bluetoothProblem?.let { " ($it)" } ?: ""}")
        }.joinToString(" · ")
        return "$conn\nEscuchando: $listening"
    }

    private fun showPairDialog() {
        val ctx = requireContext()
        val code = CompanionBridgeHost.startPairing(ctx)
        val pairedBefore = CompanionBridgeHost.status().pairedCount
        val codeTv = TextView(ctx).apply {
            text = code.chunked(3).joinToString(" ")
            textSize = 64f
            setTextColor(QonectaTheme.accent)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            letterSpacing = 0.15f
        }
        val hintTv = TextView(ctx).apply {
            text = "En el móvil: abre Qonecta Unit Companion → paso 2 y escribe este código.\nCaduca en 5 minutos."
            textSize = 18f
            setTextColor(QonectaTheme.text)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
            addView(codeTv)
            addView(hintTv)
        }
        var listener: ((CompanionBridgeHost.Status) -> Unit)? = null
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Emparejar Qonecta Unit Companion")
            .setView(box)
            .setNegativeButton("Cerrar", null)
            .create()
        listener = { s ->
            if (s.pairedCount > pairedBefore) {
                codeTv.text = "✓"
                hintTv.text = "¡Móvil emparejado! Ya puedes mandar rutas desde el planificador."
                codeTv.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, 2_500)
            } else if (s.pairingCode == null) {
                hintTv.text = "El código caducó. Cierra y vuelve a pulsar «Emparejar móvil»."
            }
        }
        dialog.setOnDismissListener {
            listener?.let { CompanionBridgeHost.removeListener(it) }
            CompanionBridgeHost.cancelPairing()
        }
        CompanionBridgeHost.addListener(listener)
        dialog.show()
        activeDialog = dialog
    }

    override fun onDestroyView() {
        companionListener?.let { CompanionBridgeHost.removeListener(it) }
        companionListener = null
        super.onDestroyView()
    }
}
