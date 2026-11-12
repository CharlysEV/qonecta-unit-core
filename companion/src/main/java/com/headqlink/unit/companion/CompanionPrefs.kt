package com.headqlink.unit.companion

import android.content.Context

/** Lo que el Companion recuerda del coche emparejado. */
class CompanionPrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("companion", Context.MODE_PRIVATE)

    var carBtAddress: String?
        get() = p.getString("car_bt", null)
        set(v) = p.edit().putString("car_bt", v).apply()

    var carBtName: String?
        get() = p.getString("car_bt_name", null)
        set(v) = p.edit().putString("car_bt_name", v).apply()

    var token: String?
        get() = p.getString("token", null)
        set(v) = p.edit().putString("token", v).apply()

    var carName: String?
        get() = p.getString("car_name", null)
        set(v) = p.edit().putString("car_name", v).apply()

    /** El usuario quiere que el Companion busque el coche (se apaga con "Olvidar coche"). */
    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    var lastRouteSummary: String?
        get() = p.getString("last_route", null)
        set(v) = p.edit().putString("last_route", v).apply()

    var githubRepo: String
        get() = p.getString("github_repo", "CharlysEV/qonecta-unit")?.takeIf { it.isNotBlank() } ?: "CharlysEV/qonecta-unit"
        set(v) = p.edit().putString("github_repo", v.trim().ifBlank { "CharlysEV/qonecta-unit" }).apply()

    var githubCompanionRepo: String
        get() = p.getString("github_companion_repo", "CharlysEV/qonecta-unit-companion")?.takeIf { it.isNotBlank() } ?: "CharlysEV/qonecta-unit-companion"
        set(v) = p.edit().putString("github_companion_repo", v.trim().ifBlank { "CharlysEV/qonecta-unit-companion" }).apply()

    var githubToken: String
        get() = p.getString("github_token", "") ?: ""
        set(v) = p.edit().putString("github_token", v.trim()).apply()

    var customDnsHost: String
        get() = p.getString("custom_dns_host", "") ?: ""
        set(v) = p.edit().putString("custom_dns_host", v.trim()).apply()

    var expertMode: Boolean
        get() = p.getBoolean("expert_mode", false)
        set(v) = p.edit().putBoolean("expert_mode", v).apply()

    var coldInstallExpanded: Boolean
        get() = p.getBoolean("cold_install_expanded", false)
        set(v) = p.edit().putBoolean("cold_install_expanded", v).apply()

    /** Casilla independiente en el móvil: conectar por Bluetooth RFCOMM (viajes, tarifas, rutas y datos sin Wi-Fi). */
    var useBluetooth: Boolean
        get() = p.getBoolean("use_bluetooth", true)
        set(v) = p.edit().putBoolean("use_bluetooth", v).apply()

    /** Casilla independiente en el móvil: usar enlace Wi-Fi rápido cuando haya Android Auto o Hotspot. Desmárcala para Solo Bluetooth puro. */
    var useWifi: Boolean
        get() = p.getBoolean("use_wifi", true)
        set(v) = p.edit().putBoolean("use_wifi", v).apply()

    var tutorialStep: Int
        get() = p.getInt("install_tutorial_step", 0).coerceIn(0, 4)
        set(v) = p.edit().putInt("install_tutorial_step", v.coerceIn(0, 4)).apply()

    var usbExportDoneAtMs: Long
        get() = p.getLong("usb_export_done_at_ms", 0L)
        set(v) = p.edit().putLong("usb_export_done_at_ms", v).apply()

    val isPaired: Boolean
        get() = !token.isNullOrBlank()

    fun forgetCar() {
        p.edit().remove("token").remove("car_name").remove("car_bt").remove("car_bt_name").apply()
    }
}
