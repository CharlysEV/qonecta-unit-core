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

    val isPaired: Boolean
        get() = !token.isNullOrBlank()

    fun forgetCar() {
        p.edit().remove("token").remove("car_name").remove("car_bt").remove("car_bt_name").apply()
    }
}
