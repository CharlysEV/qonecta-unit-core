package com.andrerinas.openheadunit.main

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.DiagnosticExportStore
import com.andrerinas.openheadunit.utils.DiagnosticReport
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.headqlink.unit.files.CarFiles
import com.headqlink.unit.probe.CarProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsFragment : Fragment(R.layout.fragment_diagnostics) {
    private var exporting = false
    private val destination = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) export(uri)
    }
    /** Lo último que vio la sonda del coche, para guardarlo donde se elija (el pen). */
    private var probeText: String? = null
    private val probeDestination = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = probeText
        if (uri != null && text != null) saveProbe(uri, text)
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { findNavController().popBackStack() }
        view.findViewById<View>(R.id.save_diagnostic_report).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 29) export() else chooseLocation()
        }
        view.findViewById<View>(R.id.choose_diagnostic_location).setOnClickListener { chooseLocation() }
        view.findViewById<View>(R.id.car_probe).setOnClickListener { runProbe() }
        view.findViewById<View>(R.id.car_files).setOnClickListener {
            if (!CarFiles.open(requireActivity())) Toast.makeText(requireContext(), getString(R.string.qu_files_none), Toast.LENGTH_LONG).show()
        }
    }
    private fun runProbe() {
        val button = view?.findViewById<MaterialButton>(R.id.car_probe) ?: return
        val result = view?.findViewById<TextView>(R.id.car_probe_result)
        button.isEnabled = false
        button.text = getString(R.string.qu_probe_running)
        val context = requireContext().applicationContext
        lifecycleScope.launch {
            val text = runCatching { CarProbe.run(context) }.getOrElse { "La sonda falló: $it" }
            probeText = text
            if (!isAdded || view == null) return@launch
            button.isEnabled = true
            button.text = getString(R.string.qu_probe)
            result?.apply { visibility = View.VISIBLE; this.text = text }
            runCatching {
                probeDestination.launch("QonectaUnit-sonda-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
            }.onFailure {
                Toast.makeText(requireContext(), getString(R.string.diagnostic_file_picker_unavailable), Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun saveProbe(uri: Uri, text: String) {
        val context = requireContext().applicationContext
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { DiagnosticExportStore.write(context.contentResolver, uri, text) }.isSuccess
            }
            if (!isAdded) return@launch
            Toast.makeText(requireContext(),
                getString(if (saved) R.string.qu_probe_saved else R.string.diagnostic_could_not_save_the_report),
                Toast.LENGTH_LONG).show()
        }
    }
    private fun fileName() = "DiAuto-report-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
    private fun chooseLocation() {
        runCatching { destination.launch(fileName()) }.onFailure {
            Toast.makeText(requireContext(), getString(R.string.diagnostic_file_picker_unavailable), Toast.LENGTH_LONG).show()
        }
    }
    private fun export(destination: Uri? = null) {
        if (exporting) return
        exporting = true
        val context = requireContext().applicationContext
        val fileName = fileName()
        val button = view?.findViewById<MaterialButton>(R.id.save_diagnostic_report)
        button?.apply { isEnabled = false; text = getString(R.string.diagnostic_saving_report) }
        view?.findViewById<View>(R.id.choose_diagnostic_location)?.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = DiagnosticReport.build(context)
                    if (destination != null) {
                        DiagnosticExportStore.write(context.contentResolver, destination, report)
                        destination
                    } else {
                        check(Build.VERSION.SDK_INT >= 29)
                        DiagnosticExportStore.saveToDownloads(context.contentResolver, fileName, report)
                    }
                }
            }
            exporting = false
            if (!isAdded || view == null) return@launch
            button?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
            view?.findViewById<View>(R.id.choose_diagnostic_location)?.isEnabled = true
            result.onSuccess { uri ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(getString(R.string.diagnostic_report_saved))
                    .setMessage(if (destination == null) "Downloads/DiAuto/$fileName" else getString(R.string.diagnostic_saved_to_your_selected_location))
                    .setPositiveButton(R.string.share) { _, _ ->
                        runCatching {
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = android.content.ClipData.newRawUri("Diagnostic report", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, getString(R.string.diagnostic_share_diagnostic_report)))
                        }.onFailure { Toast.makeText(requireContext(), getString(R.string.diagnostic_share_manually), Toast.LENGTH_LONG).show() }
                    }.setNegativeButton(getString(R.string.diagnostic_done), null).show()
            }.onFailure {
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(getString(R.string.diagnostic_could_not_save_the_report))
                    .setMessage(getString(R.string.diagnostic_save_failure_hint))
                    .setPositiveButton(getString(R.string.diagnostic_choose_location)) { _, _ -> chooseLocation() }
                    .setNegativeButton(R.string.close, null).show()
            }
        }
    }
}
