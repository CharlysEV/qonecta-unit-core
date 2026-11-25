package com.andrerinas.openheadunit.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioManager
import android.os.Build
import java.util.ArrayDeque

/** Small, shareable report. Never exports saved credentials or raw protocol payloads. */
object DiagnosticReport {
    fun build(context: Context): String {
        val settings = Settings(context)
        val ssid = settings.hotspotSsid
        val password = settings.hotspotPassword
        val lines = ArrayDeque<String>()
        fun accept(line: String) {
            DiagnosticReportRedactor.redact(line, ssid, password)?.let {
                if (lines.size == 6000) lines.removeFirst()
                lines.addLast(it)
            }
        }
        val history = DiagnosticJournal.snapshot()
        val source = if (settings.logSource == Settings.LogSource.APPLOG_FILE)
            AppLog.currentLogFile ?: AppLog.lastLogFile else null
        if (history.isNotEmpty()) {
            history.forEach(::accept)
        } else if (source?.isFile == true) {
            source.useLines { it.forEach(::accept) }
        } else {
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "OPENHU:V", "*:S")
                .redirectErrorStream(true).start()
            try {
                process.inputStream.bufferedReader().useLines { it.forEach(::accept) }
                check(process.waitFor() == 0) { "Could not read the app logs" }
            } finally { process.destroy() }
        }
        return buildString {
            appendLine("DiAuto ${context.packageManager.getPackageInfo(context.packageName, 0).versionName} · diagnostic report")
            appendLine("Android=${Build.VERSION.RELEASE}; API=${Build.VERSION.SDK_INT}")
            appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}; board=${Build.BOARD}; build=${Build.DISPLAY}")
            appendLine("Wireless mode=${settings.wifiConnectionMode}; native transport=${settings.nativeApTransport}")
            appendLine("Hotspot auto-enable=${settings.autoEnableHotspot}; hotspot state=${SoftApStateReader.read(context)}")
            appendLine("Hotspot name saved=${settings.hotspotSsid.isNotBlank()}; password saved=${settings.hotspotPassword.isNotEmpty()}")
            appendLine("Logging level=${settings.exporterLogLevel}; source=${settings.logSource}")
            appendLine("Saved credentials, network addresses and protocol payloads are omitted.")
            appendLine("--- Hotspot and network interfaces at report time (read-only) ---")
            HotspotNetworkProbe.report(context).forEach { appendLine(it) }
            appendLine("--- Audio routing & Bluetooth state snapshot (read-only) ---")
            audioAndBtSnapshot(context).forEach { appendLine(it) }
            appendLine("--- Recent app logs (up to 6000 lines) ---")
            if (lines.isEmpty()) appendLine("No recent app logs available. Reproduce the issue and save a new report.")
            lines.forEach { appendLine(it) }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun audioAndBtSnapshot(context: Context): List<String> {
        val out = ArrayList<String>(4)
        runCatching {
            val am = context.getSystemService(AudioManager::class.java)
            if (am == null) {
                out.add("AudioManager: unavailable")
            } else {
                val musicVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val musicMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val voiceVol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                val voiceMax = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                out.add(
                    "Audio: mode=${am.mode}; musicActive=${am.isMusicActive}; " +
                        "speaker=${am.isSpeakerphoneOn}; btSco=${am.isBluetoothScoOn}; btA2dp=${am.isBluetoothA2dpOn}; " +
                        "musicVol=$musicVol/$musicMax; voiceVol=$voiceVol/$voiceMax"
                )
            }
        }.onFailure { err ->
            out.add("AudioManager snapshot skipped: ${err.javaClass.simpleName}")
        }
        runCatching {
            val bm = context.getSystemService(BluetoothManager::class.java)
            val adapter = bm?.adapter
            if (adapter == null) {
                out.add("Bluetooth: adapter unavailable")
            } else {
                val enabled = runCatching { adapter.isEnabled }.getOrNull()
                val state = runCatching { adapter.state }.getOrNull()
                val hfp = runCatching { adapter.getProfileConnectionState(BluetoothProfile.HEADSET) }.getOrNull()
                val a2dp = runCatching { adapter.getProfileConnectionState(BluetoothProfile.A2DP) }.getOrNull()
                out.add("Bluetooth: enabled=$enabled; state=$state; hfpProfile=$hfp; a2dpProfile=$a2dp")
            }
        }.onFailure { err ->
            out.add("Bluetooth snapshot skipped: ${err.javaClass.simpleName}")
        }
        return out
    }
}
