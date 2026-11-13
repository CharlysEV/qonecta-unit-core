package com.headqlink.unit.companion

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Lo último que ha mandado Qonecta Unit por el puente (TelemetrySync): con el móvil conectado al coche, esta es la
 * fuente de verdad de batería, autonomía, potencia y clima. Al desconectar se descarta y manda la cuenta Leapmotor.
 */
object UnitTelemetry {

    data class Data(
        val receivedAtMs: Long,
        val socPct: Double,
        val rangeKm: Double,
        val fuelPct: Double = Double.NaN,
        val fuelRangeKm: Double = Double.NaN,
        val isReev: Boolean = false,
        val speedKmh: Double,
        val powerKw: Double,
        val hvacKw: Double,
        val auxKw: Double,
        val acOn: Boolean,
        val cabinC: Double,
        val extC: Double,
        val charging: Boolean,
        val gear: String,
        val tripKm: Double,
        val tripKwh: Double,
        val avgKwh100: Double,
        val unitBtCompanion: Boolean = true,
        val unitWifi5g: Boolean = false,
        val unitHotspot: Boolean = false,
        val unitUsb: Boolean = true,
        val unitAaConnected: Boolean = false,
        val hasUnitConnInfo: Boolean = false,
    )

    @Volatile
    var current: Data? = null
        private set

    private val listeners = CopyOnWriteArraySet<(Data?) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun onSync(json: String, context: android.content.Context? = null) {
        android.util.Log.i("UnitTelemetry", "SYNC desde Qonecta Unit: $json")
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        fun d(k: String) = if (o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)
        val connObj = o.optJSONObject("unitConn")
        val data = Data(
            receivedAtMs = System.currentTimeMillis(),
            socPct = d("soc"),
            rangeKm = d("rangeKm"),
            fuelPct = d("fuelPct"),
            fuelRangeKm = d("fuelRangeKm"),
            isReev = o.optBoolean("isReev", false),
            speedKmh = d("speedKmh"),
            powerKw = d("powerKw"),
            hvacKw = d("hvacKw"),
            auxKw = d("auxKw"),
            acOn = o.optBoolean("acOn", false),
            cabinC = d("cabinC"),
            extC = d("extC"),
            charging = o.optBoolean("charging", false),
            gear = o.optString("gear", ""),
            tripKm = d("tripKm"),
            tripKwh = d("tripKwh"),
            avgKwh100 = d("avgKwh100"),
            unitBtCompanion = connObj?.optBoolean("btCompanion", true) ?: true,
            unitWifi5g = connObj?.optBoolean("wifi5g", false) ?: false,
            unitHotspot = connObj?.optBoolean("hotspot", false) ?: false,
            unitUsb = connObj?.optBoolean("usb", true) ?: true,
            unitAaConnected = connObj?.optBoolean("aaConnected", false) ?: false,
            hasUnitConnInfo = connObj != null,
        )
        current = data
        val liveSpeed = if (!data.speedKmh.isNaN()) "${data.speedKmh.toInt()} km/h" else "0 km/h"
        val liveGear = data.gear.ifBlank { "P" }
        CompanionVehicleState.update(context) { cur ->
            cur.copy(
                socPct = if (!data.socPct.isNaN() && data.socPct in 0.0..100.0) data.socPct.toInt() else cur.socPct,
                rangeKm = if (!data.rangeKm.isNaN() && data.rangeKm >= 0.0) data.rangeKm.toInt() else cur.rangeKm,
                powerKw = if (!data.powerKw.isNaN()) data.powerKw else cur.powerKw,
                hvacKw = if (!data.hvacKw.isNaN()) data.hvacKw else cur.hvacKw,
                auxKw = if (!data.auxKw.isNaN()) data.auxKw else cur.auxKw,
                acOn = data.acOn,
                cabinTempC = if (!data.cabinC.isNaN()) data.cabinC else cur.cabinTempC,
                extTempC = if (!data.extC.isNaN()) data.extC else cur.extTempC,
                charging = data.charging,
                lastCommandStatus = CompanionI18n.tr(
                    "● En vivo desde Qonecta Unit ($liveGear · $liveSpeed)",
                    "● Live from Qonecta Unit ($liveGear · $liveSpeed)",
                    "● Live von Qonecta Unit ($liveGear · $liveSpeed)",
                    "● Em direto do Qonecta Unit ($liveGear · $liveSpeed)",
                ),
            )
        }
        if (context != null) {
            runCatching {
                val prefs = context.applicationContext.getSharedPreferences("companion_vehicle_state", android.content.Context.MODE_PRIVATE)
                val editor = prefs.edit()
                    .putString("last_unit_telemetry_json", json)
                    .putLong("last_unit_telemetry_ts", data.receivedAtMs)

                // Sincronizar viajes eliminados y ocultos con el coche (unión de eliminados y ocultos)
                val localDeleted = prefs.getString("deleted_trip_ids_csv", "").orEmpty()
                    .split(',').mapNotNull { it.trim().toLongOrNull() }.toMutableSet()
                o.optJSONArray("deletedTripIds")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val id = arr.optLong(i, 0L)
                        if (id > 0L) localDeleted.add(id)
                    }
                }
                editor.putString("deleted_trip_ids_csv", localDeleted.take(200).joinToString(","))

                val localHidden = prefs.getString("hidden_trip_ids_csv", "").orEmpty()
                    .split(',').mapNotNull { it.trim().toLongOrNull() }.toMutableSet()
                val unhiddenLocal = prefs.getString("unhidden_trip_ids_csv", "").orEmpty()
                    .split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
                o.optJSONArray("hiddenTripIds")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val id = arr.optLong(i, 0L)
                        if (id > 0L && !localDeleted.contains(id) && !unhiddenLocal.contains(id)) {
                            localHidden.add(id)
                        }
                    }
                }
                editor.putString("hidden_trip_ids_csv", localHidden.take(200).joinToString(","))

                // Sincronizar tarifas de energía (casa CA, rápida CC) y combustible (gasolina, diésel, REEV)
                o.optJSONObject("pricing")?.let { p ->
                    val remoteTs = p.optLong("updatedAtMs", 0L)
                    val localTs = prefs.getLong("pricing_updated_at_ms", 0L)
                    if (remoteTs > localTs) {
                        editor.putFloat("pricing_home_elec_eur", p.optDouble("homeElecEurKwh", 0.16).toFloat())
                            .putFloat("pricing_dc_fast_elec_eur", p.optDouble("dcFastElecEurKwh", 0.42).toFloat())
                            .putFloat("pricing_gasoline_eur", p.optDouble("gasolineEurLiter", 1.62).toFloat())
                            .putFloat("pricing_diesel_eur", p.optDouble("dieselEurLiter", 1.52).toFloat())
                            .putInt("pricing_active_tariff_mode", p.optInt("tariffMode", 0))
                            .putBoolean("pricing_is_reev_mode", p.optBoolean("isReevMode", o.optBoolean("isReev", false)))
                            .putLong("pricing_updated_at_ms", remoteTs)
                    }
                }

                val tripsArr = o.optJSONArray("trips")
                if (tripsArr != null) {
                    val cleanTrips = org.json.JSONArray()
                    for (i in 0 until tripsArr.length()) {
                        val tr = tripsArr.optJSONObject(i) ?: continue
                        val tId = tr.optLong("startTimeMs", 0L)
                        if (localDeleted.contains(tId)) continue
                        val h = (tr.optBoolean("hidden", false) || localHidden.contains(tId)) && !unhiddenLocal.contains(tId)
                        tr.put("hidden", h)
                        cleanTrips.put(tr)
                    }
                    editor.putString("synced_unit_trips_json", cleanTrips.toString())
                }
                editor.apply()
            }
        }
        main.post { listeners.forEach { it(data) } }
    }

    fun clear() {
        current = null
        main.post { listeners.forEach { it(null) } }
    }

    fun addListener(l: (Data?) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (Data?) -> Unit) {
        listeners.remove(l)
    }
}
