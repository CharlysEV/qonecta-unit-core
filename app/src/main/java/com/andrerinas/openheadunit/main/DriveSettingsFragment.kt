package com.andrerinas.openheadunit.main

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.cluster.ClusterMapController
import com.google.android.material.appbar.MaterialToolbar
import com.headqlink.unit.files.CarFiles

class DriveSettingsFragment : Fragment(R.layout.fragment_drive_settings) {
    private val carFilesPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            Toast.makeText(requireContext(), getString(R.string.qu_files_picker_tip), Toast.LENGTH_LONG).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            requireActivity().finish()
        }
        mapOf(
            R.id.da_connection_setup to R.id.connectionSetupFragment,
            R.id.da_display to R.id.settingsFragment,
            R.id.da_permissions to R.id.permissionsFragment,
            R.id.da_diagnostics to R.id.diagnosticsFragment,
            R.id.da_about to R.id.aboutFragment,
        ).forEach { (button, destination) ->
            view.findViewById<View>(button)?.setOnClickListener { findNavController().navigate(destination) }
        }
        view.findViewById<View>(R.id.da_cluster_display)?.setOnClickListener {
            val settings = App.provide(requireContext()).settings
            val next = !settings.clusterMapEnabled
            settings.clusterMapEnabled = next
            if (!next) {
                ClusterMapController.stop(context = requireContext())
            }
            refreshClusterCard(view)
            Toast.makeText(
                requireContext(),
                getString(if (next) R.string.qu_cluster_toast_on else R.string.qu_cluster_toast_off),
                Toast.LENGTH_SHORT,
            ).show()
        }
        view.findViewById<View>(R.id.da_car_files)?.setOnClickListener {
            openCarFiles()
        }
        refreshClusterCard(view)
    }

    override fun onResume() {
        super.onResume()
        view?.let { refreshClusterCard(it) }
    }

    private fun refreshClusterCard(view: View) {
        val enabled = App.provide(requireContext()).settings.clusterMapEnabled
        view.findViewById<TextView>(R.id.da_cluster_display_hint)?.text =
            getString(if (enabled) R.string.qu_cluster_hint_on else R.string.qu_cluster_hint_off)
        view.findViewById<TextView>(R.id.da_cluster_display_badge)?.apply {
            text = if (enabled) "ON" else "OFF"
            setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (enabled) R.color.hql_accent else R.color.hql_text_dim,
                )
            )
        }
    }

    private fun openCarFiles() {
        val result = CarFiles.open(requireActivity()) { pickerIntent ->
            runCatching { carFilesPickerLauncher.launch(pickerIntent) }.isSuccess
        }
        if (result == CarFiles.OpenResult.UNAVAILABLE) {
            Toast.makeText(requireContext(), getString(R.string.qu_files_none), Toast.LENGTH_LONG).show()
        }
    }
}
