package com.headqlink.unit.extended

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import com.andrerinas.openheadunit.utils.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fuente de telemetría en tiempo real del Leapmotor C10 mediante `com.leapmotor.autosdk.server.IAutoSdkService`
 * (transacciones 17..45 + callback `com.leapmotor.autosdk.server.ICallback` códigos 1..24) y
 * `com.leapmotor.carcontrol.IBinderPool` (`ICarControlExternal` / `ICarControl`).
 *
 * Implementa [PanelDataSource] para alimentar directamente tanto la franja lateral ([ExtendedPanelView])
 * como el centro completo del modo Extendido ([ExtendedHubDialog] / pestañas Ruta, Conducción, Viajes,
 * Instrumentos, Eficiencia y Estado) en Android Auto y Apple CarPlay.
 */
object LeapVehicleDataSource : PanelDataSource {

    const val AUTOSDK_PACKAGE = "com.leapmotor.autosdk.server"
    const val AUTOSDK_ACTION = "com.leapmotor.autosdk.server.action.AUTOSDK_SERVICE"
    const val AUTOSDK_DESCRIPTOR = "com.leapmotor.autosdk.server.IAutoSdkService"
    const val AUTOSDK_CALLBACK_DESCRIPTOR = "com.leapmotor.autosdk.server.ICallback"

    const val CARCONTROL_PACKAGE = "com.leapmotor.carcontrol"
    const val BINDER_POOL_ACTION = "com.leapmotor.carcontrol.services.BINDER_POOL"
    const val BINDER_POOL_DESCRIPTOR = "com.leapmotor.carcontrol.IBinderPool"
    const val CARCONTROL_EXT_DESCRIPTOR = "com.leapmotor.carcontrol.ICarControlExternal"
    const val CARCONTROL_EXT_CALLBACK_DESCRIPTOR = "com.leapmotor.carcontrol.ICarControlExternalCallback"

    private const val PREFS_NAME = "qu_leap_telemetry"
    private const val KEY_TRIPS_JSON = "recent_trips_json"
    private const val KEY_DELETED_TRIP_IDS = "deleted_trip_ids_csv"
    private const val KEY_HIDDEN_TRIP_IDS = "hidden_trip_ids_csv"
    private const val KEY_HOME_ELEC_EUR = "pricing_home_elec_eur"
    private const val KEY_DC_FAST_ELEC_EUR = "pricing_dc_fast_elec_eur"
    private const val KEY_GASOLINE_EUR = "pricing_gasoline_eur"
    private const val KEY_DIESEL_EUR = "pricing_diesel_eur"
    private const val KEY_ACTIVE_TARIFF_MODE = "pricing_active_tariff_mode"
    private const val KEY_PRICING_UPDATED_AT = "pricing_updated_at_ms"
    private const val KEY_MANUAL_SOC = "manual_soc_pct"
    private const val KEY_SYNC_DRIVE_MODE = "sync_drive_mode"
    private const val KEY_SYNC_STEER_MODE = "sync_steer_mode"
    private const val KEY_SYNC_REGEN_MODE = "sync_regen_mode"
    private const val KEY_SYNC_TEMP_LEFT = "sync_temp_left_c"
    private const val KEY_SYNC_TEMP_RIGHT = "sync_temp_right_c"
    private const val KEY_SYNC_TEMP_OUT = "sync_temp_out_c"
    private const val KEY_C10_VARIANT = "selected_c10_variant_id"
    private const val KEY_C10_PAINT_COLOR = "selected_c10_paint_id"

    /**
     * Configuración de tarifas de energía y combustible sincronizada entre Qonecta Unit Companion y Qonecta Unit:
     * - [homeElecEurKwh]: Precio de electricidad en casa CA (€/kWh).
     * - [dcFastElecEurKwh]: Precio de carga rápida fuera CC (€/kWh).
     * - [gasolineEurLiter]: Precio de la gasolina 95 (€/L, usado en comparativa BEV y en consumo real REEV).
     * - [dieselEurLiter]: Precio del diésel (€/L, usado en comparativa frente a SUV diésel).
     * - [tariffMode]: 0 = Casa (CA), 1 = Carga Rápida (CC), 2 = Mixto (70% Casa / 30% Rápida).
     */
    data class EnergyPricingConfig(
        val homeElecEurKwh: Double = 0.16,
        val dcFastElecEurKwh: Double = 0.42,
        val gasolineEurLiter: Double = 1.62,
        val dieselEurLiter: Double = 1.52,
        val tariffMode: Int = 0,
        val updatedAtMs: Long = 0L,
    ) {
        val activeElecEurKwh: Double
            get() = when (tariffMode) {
                1 -> dcFastElecEurKwh
                2 -> homeElecEurKwh * 0.70 + dcFastElecEurKwh * 0.30
                else -> homeElecEurKwh
            }

        val activeTariffLabel: String
            get() = when (tariffMode) {
                1 -> "Carga rápida CC"
                2 -> "Mixto CA/CC"
                else -> "Casa CA"
            }
    }

    @Volatile
    private var pricingCached: EnergyPricingConfig = EnergyPricingConfig()

    fun getEnergyPricing(context: Context? = appContext): EnergyPricingConfig {
        val ctx = context?.applicationContext ?: appContext
        if (ctx != null) {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            pricingCached = EnergyPricingConfig(
                homeElecEurKwh = prefs.getFloat(KEY_HOME_ELEC_EUR, 0.16f).toDouble().coerceIn(0.01, 2.50),
                dcFastElecEurKwh = prefs.getFloat(KEY_DC_FAST_ELEC_EUR, 0.42f).toDouble().coerceIn(0.05, 3.50),
                gasolineEurLiter = prefs.getFloat(KEY_GASOLINE_EUR, 1.62f).toDouble().coerceIn(0.50, 4.00),
                dieselEurLiter = prefs.getFloat(KEY_DIESEL_EUR, 1.52f).toDouble().coerceIn(0.50, 4.00),
                tariffMode = prefs.getInt(KEY_ACTIVE_TARIFF_MODE, 0).coerceIn(0, 2),
                updatedAtMs = prefs.getLong(KEY_PRICING_UPDATED_AT, 0L),
            )
        }
        return pricingCached
    }

    fun saveEnergyPricing(
        context: Context,
        config: EnergyPricingConfig,
        fromRemote: Boolean = false,
    ): EnergyPricingConfig {
        val stamp = if (fromRemote && config.updatedAtMs > 0L) config.updatedAtMs else System.currentTimeMillis()
        val normalized = config.copy(
            homeElecEurKwh = config.homeElecEurKwh.coerceIn(0.01, 2.50),
            dcFastElecEurKwh = config.dcFastElecEurKwh.coerceIn(0.05, 3.50),
            gasolineEurLiter = config.gasolineEurLiter.coerceIn(0.50, 4.00),
            dieselEurLiter = config.dieselEurLiter.coerceIn(0.50, 4.00),
            tariffMode = config.tariffMode.coerceIn(0, 2),
            updatedAtMs = stamp,
        )
        pricingCached = normalized
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_HOME_ELEC_EUR, normalized.homeElecEurKwh.toFloat())
            .putFloat(KEY_DC_FAST_ELEC_EUR, normalized.dcFastElecEurKwh.toFloat())
            .putFloat(KEY_GASOLINE_EUR, normalized.gasolineEurLiter.toFloat())
            .putFloat(KEY_DIESEL_EUR, normalized.dieselEurLiter.toFloat())
            .putInt(KEY_ACTIVE_TARIFF_MODE, normalized.tariffMode)
            .putLong(KEY_PRICING_UPDATED_AT, normalized.updatedAtMs)
            .apply()
        return normalized
    }

    /**
     * Catálogo oficial de versiones del Leapmotor C10 comercializadas hasta MY26 (excluyendo 2027).
     * Por defecto selecciona la versión del usuario: C10 ProMax Design 299 CV MY26 (81,9 kWh · 800V).
     */
    enum class C10Variant(
        val id: String,
        val title: String,
        val shortLabel: String,
        val badge: String,
        val powertrainLabel: String,
        val powerCv: Int,
        val powerKw: Int,
        val torqueNm: Int,
        val zeroToHundredSec: Double,
        val batteryGrossKwh: Double,
        val batteryUsableKwh: Double,
        val architectureVolts: Int,
        val peakDcChargeKw: Int,
        val acChargeKw: Double,
        val charge30To80Min: Int,
        val wltpEvRangeKm: Int,
        val wltpTotalRangeKm: Int,
        val isReev: Boolean,
        val fuelTankLiters: Double,
        val trimEquipment: String,
    ) {
        PROMAX_DESIGN_MY26(
            id = "promax_design_299_my26",
            title = "C10 ProMax Design 299 CV MY26",
            shortLabel = "ProMax Design 299 CV MY26 · 81,9 kWh",
            badge = "MY26 · 800V",
            powertrainLabel = "BEV · Tracción Trasera (RWD)",
            powerCv = 299,
            powerKw = 220,
            torqueNm = 360,
            zeroToHundredSec = 6.6,
            batteryGrossKwh = 81.9,
            batteryUsableKwh = 79.0,
            architectureVolts = 800,
            peakDcChargeKw = 180,
            acChargeKw = 11.0,
            charge30To80Min = 22,
            wltpEvRangeKm = 510,
            wltpTotalRangeKm = 510,
            isReev = false,
            fuelTankLiters = 0.0,
            trimEquipment = "Acabado Design · Llantas 20\" Tridente · Asientos ventilados y calefactados · Portón eléctrico",
        ),
        PROMAX_STYLE_MY26(
            id = "promax_style_299_my26",
            title = "C10 ProMax Style / Life 299 CV MY26",
            shortLabel = "ProMax Style/Life 299 CV MY26 · 81,9 kWh",
            badge = "MY26 · 800V",
            powertrainLabel = "BEV · Tracción Trasera (RWD)",
            powerCv = 299,
            powerKw = 220,
            torqueNm = 360,
            zeroToHundredSec = 6.6,
            batteryGrossKwh = 81.9,
            batteryUsableKwh = 79.0,
            architectureVolts = 800,
            peakDcChargeKw = 180,
            acChargeKw = 11.0,
            charge30To80Min = 22,
            wltpEvRangeKm = 510,
            wltpTotalRangeKm = 510,
            isReev = false,
            fuelTankLiters = 0.0,
            trimEquipment = "Acabado Style/Life · Llantas 18\" · Techo panorámico · Bomba de calor",
        ),
        PROMAX_AWD_DESIGN_MY26(
            id = "promax_awd_598_my26",
            title = "C10 ProMax AWD Design 598 CV MY26",
            shortLabel = "ProMax AWD Design 598 CV MY26 · 81,9 kWh",
            badge = "MY26 · AWD 800V",
            powertrainLabel = "BEV · Tracción Total Dual Motor (AWD)",
            powerCv = 598,
            powerKw = 440,
            torqueNm = 720,
            zeroToHundredSec = 4.0,
            batteryGrossKwh = 81.9,
            batteryUsableKwh = 79.0,
            architectureVolts = 800,
            peakDcChargeKw = 180,
            acChargeKw = 11.0,
            charge30To80Min = 22,
            wltpEvRangeKm = 437,
            wltpTotalRangeKm = 437,
            isReev = false,
            fuelTankLiters = 0.0,
            trimEquipment = "Acabado AWD Design · Doble motor 598 CV · Llantas 20\" · 0–100 km/h en 4,0 s",
        ),
        BEV_DESIGN_218(
            id = "bev_design_218_my25",
            title = "C10 BEV Design 218 CV (MY24/25)",
            shortLabel = "BEV Design 218 CV · 69,9 kWh",
            badge = "MY24/25 · 400V",
            powertrainLabel = "BEV · Tracción Trasera (RWD)",
            powerCv = 218,
            powerKw = 160,
            torqueNm = 320,
            zeroToHundredSec = 7.5,
            batteryGrossKwh = 69.9,
            batteryUsableKwh = 67.0,
            architectureVolts = 400,
            peakDcChargeKw = 84,
            acChargeKw = 11.0,
            charge30To80Min = 30,
            wltpEvRangeKm = 420,
            wltpTotalRangeKm = 420,
            isReev = false,
            fuelTankLiters = 0.0,
            trimEquipment = "Acabado Design · Llantas 20\" Tridente · Asientos ventilados y calefactados · Portón eléctrico",
        ),
        BEV_STYLE_218(
            id = "bev_style_218_my25",
            title = "C10 BEV Style / Life 218 CV (MY24/25)",
            shortLabel = "BEV Style/Life 218 CV · 69,9 kWh",
            badge = "MY24/25 · 400V",
            powertrainLabel = "BEV · Tracción Trasera (RWD)",
            powerCv = 218,
            powerKw = 160,
            torqueNm = 320,
            zeroToHundredSec = 7.5,
            batteryGrossKwh = 69.9,
            batteryUsableKwh = 67.0,
            architectureVolts = 400,
            peakDcChargeKw = 84,
            acChargeKw = 11.0,
            charge30To80Min = 30,
            wltpEvRangeKm = 420,
            wltpTotalRangeKm = 420,
            isReev = false,
            fuelTankLiters = 0.0,
            trimEquipment = "Acabado Style/Life · Llantas 18\" · Techo panorámico · Bomba de calor",
        ),
        REEV_DESIGN_215(
            id = "reev_design_215",
            title = "C10 REEV Design 215 CV",
            shortLabel = "REEV Design 215 CV · 28,4 kWh + 50 L",
            badge = "REEV · Híbrido Serie",
            powertrainLabel = "REEV · Autonomía Extendida + Generador 1.5L",
            powerCv = 215,
            powerKw = 158,
            torqueNm = 320,
            zeroToHundredSec = 8.5,
            batteryGrossKwh = 28.4,
            batteryUsableKwh = 26.5,
            architectureVolts = 400,
            peakDcChargeKw = 65,
            acChargeKw = 6.6,
            charge30To80Min = 18,
            wltpEvRangeKm = 145,
            wltpTotalRangeKm = 974,
            isReev = true,
            fuelTankLiters = 50.0,
            trimEquipment = "Acabado Design REEV · Batería 28,4 kWh + Depósito 50 L · 974 km WLTP combinada",
        ),
        REEV_STYLE_215(
            id = "reev_style_215",
            title = "C10 REEV Style / Life 215 CV",
            shortLabel = "REEV Style/Life 215 CV · 28,4 kWh + 50 L",
            badge = "REEV · Híbrido Serie",
            powertrainLabel = "REEV · Autonomía Extendida + Generador 1.5L",
            powerCv = 215,
            powerKw = 158,
            torqueNm = 320,
            zeroToHundredSec = 8.5,
            batteryGrossKwh = 28.4,
            batteryUsableKwh = 26.5,
            architectureVolts = 400,
            peakDcChargeKw = 65,
            acChargeKw = 6.6,
            charge30To80Min = 18,
            wltpEvRangeKm = 145,
            wltpTotalRangeKm = 974,
            isReev = true,
            fuelTankLiters = 50.0,
            trimEquipment = "Acabado Style/Life REEV · Batería 28,4 kWh + Depósito 50 L · 974 km WLTP combinada",
        );

        companion object {
            fun fromId(id: String?): C10Variant =
                values().firstOrNull { it.id == id } ?: PROMAX_DESIGN_MY26
        }
    }

    /** Colores oficiales de carrocería del Leapmotor C10 para la vista 3D y superior de Neumáticos y Puertas. */
    enum class C10PaintColor(
        val id: String,
        val label: String,
        val bodyColor: Int,
        val shadeColor: Int,
        val hex3d: String,
    ) {
        GLACIER_GREEN("glacier_green", "Verde Glaciar", 0xFF5D7A6E.toInt(), 0xFF42594F.toInt(), "#123c29"),
        PEARL_WHITE("pearl_white", "Blanco Perla", 0xFFDDE3E6.toInt(), 0xFFB5BEC4.toInt(), "#e6e8e6"),
        CANOPY_GREY("canopy_grey", "Gris Canopy", 0xFF79838C.toInt(), 0xFF586068.toInt(), "#34393f"),
        TUNDRA_BLUE("tundra_blue", "Gris Tierra", 0xFF8C8A82.toInt(), 0xFF6B6962.toInt(), "#8c8a82"),
        CARBON_BLACK("carbon_black", "Negro Carbón", 0xFF2B3138.toInt(), 0xFF1B1F24.toInt(), "#121316");

        companion object {
            fun fromId(id: String?): C10PaintColor =
                values().firstOrNull { it.id == id } ?: GLACIER_GREEN

            fun fromHex3d(hex: String?): C10PaintColor {
                val h = hex?.trim()?.lowercase() ?: return GLACIER_GREEN
                return values().firstOrNull { it.hex3d.lowercase() == h } ?: GLACIER_GREEN
            }
        }
    }

    @Volatile
    private var selectedVariantCached: C10Variant = C10Variant.PROMAX_DESIGN_MY26

    @Volatile
    private var selectedPaintCached: C10PaintColor = C10PaintColor.GLACIER_GREEN

    fun getSelectedVariant(context: Context? = appContext): C10Variant {
        val ctx = context?.applicationContext ?: appContext
        if (ctx != null) {
            val saved = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_C10_VARIANT, null)
            selectedVariantCached = C10Variant.fromId(saved)
        }
        return selectedVariantCached
    }

    fun setSelectedVariant(context: Context, variant: C10Variant) {
        selectedVariantCached = variant
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_C10_VARIANT, variant.id)
            .apply()
        if (latestSnapshot.connected) {
            rebuildSnapshotFromRaw()
        } else {
            latestSnapshot = demoSnapshot()
        }
    }

    fun getSelectedPaintColor(context: Context? = appContext): C10PaintColor {
        val ctx = context?.applicationContext ?: appContext
        if (ctx != null) {
            val saved = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_C10_PAINT_COLOR, null)
            selectedPaintCached = C10PaintColor.fromId(saved)
        }
        return selectedPaintCached
    }

    fun setSelectedPaintColor(context: Context, paint: C10PaintColor) {
        selectedPaintCached = paint
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_C10_PAINT_COLOR, paint.id)
            .apply()
    }

    fun cyclePaintColor(context: Context): C10PaintColor {
        val cur = getSelectedPaintColor(context)
        val all = C10PaintColor.values()
        val next = all[(cur.ordinal + 1) % all.size]
        selectedPaintCached = next
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_C10_PAINT_COLOR, next.id)
            .apply()
        return next
    }

    val currentBatteryCapacityKwh: Double
        get() = selectedVariantCached.batteryGrossKwh

    enum class ValueType { INT, FLOAT, STRING, BOOL }

    data class Spec(val code: Int, val label: String, val type: ValueType)

    /** Tabla exacta de transacciones de lectura de `com.leapmotor.autosdk.server.IAutoSdkService` (Lo2/h;.b). */
    val POLL_SPECS = listOf(
        Spec(17, "Fabricante", ValueType.STRING),
        Spec(18, "Modelo", ValueType.STRING),
        Spec(20, "Región de venta", ValueType.INT),
        Spec(21, "Tipo de motorización", ValueType.INT),
        Spec(22, "Lado del volante", ValueType.INT),
        Spec(23, "Capacidad batería nominal (kWh)", ValueType.FLOAT),
        Spec(24, "Modo de conducción", ValueType.INT),
        Spec(25, "Modo de aceleración", ValueType.INT),
        Spec(26, "Recuperación de energía", ValueType.INT),
        Spec(27, "Modo de dirección", ValueType.INT),
        Spec(28, "Autonomía eléctrica (km)", ValueType.FLOAT),
        Spec(29, "Autonomía combustible (km)", ValueType.FLOAT),
        Spec(30, "Unidad de distancia", ValueType.INT),
        Spec(31, "Batería (%)", ValueType.FLOAT),
        Spec(32, "Combustible (%)", ValueType.FLOAT),
        Spec(33, "Velocidad en cuadro (km/h)", ValueType.FLOAT),
        Spec(34, "Unidad de velocidad", ValueType.INT),
        Spec(35, "Velocidad (km/h)", ValueType.FLOAT),
        Spec(36, "Marcha", ValueType.INT),
        Spec(37, "Usa millas", ValueType.BOOL),
        Spec(38, "Estado de carga", ValueType.INT),
        Spec(39, "Climatizador encendido", ValueType.BOOL),
        Spec(40, "Temp. habitáculo izq (°C)", ValueType.FLOAT),
        Spec(41, "Temp. habitáculo der (°C)", ValueType.FLOAT),
        Spec(42, "Temp. habitáculo tras (°C)", ValueType.FLOAT),
        Spec(43, "Temperatura exterior (°C)", ValueType.FLOAT),
        Spec(44, "Ángulo de volante (°)", ValueType.FLOAT),
        Spec(45, "Pantalla encendida", ValueType.BOOL),
    )

    /** Tabla exacta de eventos push de `com.leapmotor.autosdk.server.ICallback` (Lo2/h;.c). */
    val CALLBACK_SPECS = listOf(
        Spec(1, "Evento 1", ValueType.INT),
        Spec(2, "Modo de conducción", ValueType.INT),
        Spec(3, "Modo de aceleración", ValueType.INT),
        Spec(4, "Recuperación de energía", ValueType.INT),
        Spec(5, "Modo de dirección", ValueType.INT),
        Spec(6, "Autonomía eléctrica (km)", ValueType.FLOAT),
        Spec(7, "Autonomía combustible (km)", ValueType.FLOAT),
        Spec(8, "Unidad de distancia", ValueType.INT),
        Spec(9, "Batería (%)", ValueType.FLOAT),
        Spec(10, "Combustible (%)", ValueType.FLOAT),
        Spec(11, "Velocidad en cuadro (km/h)", ValueType.FLOAT),
        Spec(12, "Unidad de velocidad", ValueType.INT),
        Spec(13, "Velocidad (km/h)", ValueType.FLOAT),
        Spec(14, "Marcha", ValueType.INT),
        Spec(15, "Usa millas", ValueType.BOOL),
        Spec(16, "Estado de carga", ValueType.INT),
        Spec(17, "Climatizador encendido", ValueType.BOOL),
        Spec(18, "Temp. habitáculo izq (°C)", ValueType.FLOAT),
        Spec(19, "Temp. habitáculo der (°C)", ValueType.FLOAT),
        Spec(20, "Temp. habitáculo tras (°C)", ValueType.FLOAT),
        Spec(21, "Temperatura exterior (°C)", ValueType.FLOAT),
        Spec(22, "Ángulo de volante (°)", ValueType.FLOAT),
        Spec(23, "GPS NMEA", ValueType.STRING),
        Spec(24, "Pantalla encendida", ValueType.BOOL),
    )

    data class TripRecord(
        val startTimeMs: Long,
        val durationMin: Int,
        val distanceKm: Double,
        val energyKwh: Double,
        val avgKwh100: Double,
        val startSoc: Double,
        val endSoc: Double,
        val maxSpeedKmh: Double,
        val avgSpeedKmh: Double = if (durationMin > 0) (distanceKm / (durationMin / 60.0)) else 58.0,
        val maxAccelG: Double = 0.36,
        val zeroToFiftySec: Double = 3.1,
        val zeroToHundredSec: Double = 7.4,
        val climbM: Double = 185.0,
        val descentM: Double = 160.0,
        val costEur: Double = energyKwh * 0.16,
        val smoothnessScore: Int = 95,
        val originName: String = "Salida C10",
        val destinationName: String = "Llegada",
        val stopsCount: Int = 0,
        val stopsSummary: String = "Sin paradas intermedias",
        val track: List<DoubleArray> = emptyList(),
        val hidden: Boolean = false,
        val fuelLitersUsed: Double = 0.0,
    )

    data class TelemetrySnapshot(
        val connected: Boolean = false,
        val isDemo: Boolean = false,
        val serviceName: String = "",
        val manufacturer: String = "Leapmotor",
        val model: String = "C10",
        val powertrainType: Int = 0,
        val socPct: Double = Double.NaN,
        val evRangeKm: Double = Double.NaN,
        val fuelPct: Double = Double.NaN,
        val fuelRangeKm: Double = Double.NaN,
        val powerKw: Double = Double.NaN,
        val speedKmh: Double = Double.NaN,
        val dashboardSpeedKmh: Double = Double.NaN,
        val gear: Int = 0,
        val driveMode: Int = 0,
        val accelMode: Int = 0,
        val regenMode: Int = 0,
        val steeringMode: Int = 0,
        val chargingStatus: Int = 0,
        val acOn: Boolean = false,
        val tempLeftC: Double = Double.NaN,
        val tempRightC: Double = Double.NaN,
        val tempRearC: Double = Double.NaN,
        val tempExteriorC: Double = Double.NaN,
        val steeringAngleDeg: Double = Double.NaN,
        val gpsNmea: String = "",
        val screenOn: Boolean = true,
        val lastUpdateMs: Long = 0L,
        // Métricas derivadas del viaje actual
        val tripDistanceKm: Double = 0.0,
        val tripDurationSec: Long = 0L,
        val tripEnergyKwh: Double = 0.0,
        val avgConsumptionKwh100: Double = Double.NaN,
        val maxSpeedKmh: Double = 0.0,
        val longitudinalG: Float = 0f,
        val lateralG: Float = 0f,
        val peakG: Float = 0f,
        val maxAccelG: Float = 0.31f,
        val maxBrakeG: Float = 0.48f,
        val maxLeftG: Float = 0.37f,
        val maxRightG: Float = 0.24f,
        val gTrail: List<FloatArray> = emptyList(),
        val pitchDeg: Float = -2.4f,
        val rollDeg: Float = 0.0f,
        val smoothnessScore: Int = 96,
        val recentSmoothnessScore: Int = 92,
        val zeroToFiftySec: Double = Double.NaN,
        val zeroToHundredSec: Double = Double.NaN,
        val instantConsumptionKwh100: Double = 14.8,
        val recoveredEnergyKwh: Double = 0.42,
        val hvacPowerKw: Double = 0.72,
        val auxPowerKw: Double = 0.10,
        val doorsOpen: List<Boolean> = listOf(false, false, false, false),
        val bootOpen: Boolean = false,
        val locked: Boolean = true,
        val tyreBars: List<Double> = listOf(2.50, 2.50, 2.52, 2.50),
        val tyreTempsC: List<Int> = listOf(26, 26, 25, 25),
        val powerHistoryKw: List<Float> = emptyList(),
        val canSignals: Map<Int, String> = emptyMap(),
    ) {
        val hasBatteryData: Boolean
            get() = !socPct.isNaN() && socPct in 0.0..100.0

        val effectiveSpeedKmh: Double
            get() = calibrateClusterSpeedKmh(dashboardSpeedKmh, speedKmh)

        val effectiveRangeKm: Double
            get() = when {
                !evRangeKm.isNaN() && evRangeKm > 0 -> evRangeKm
                hasBatteryData -> (socPct / 100.0) * selectedVariantCached.wltpEvRangeKm
                else -> Double.NaN
            }

        val isReev: Boolean
            get() = selectedVariantCached.isReev || (!fuelPct.isNaN() && fuelPct > 0.0) || (!fuelRangeKm.isNaN() && fuelRangeKm > 0.0) || powertrainType == 2

        fun gearLabel(): String {
            if (effectiveSpeedKmh >= 1.5 && gear != 1) return "D"
            return when (gear) {
                0 -> "P"
                1 -> "R"
                2 -> "N"
                3, 4 -> "D"
                else -> if (effectiveSpeedKmh >= 1.0) "D" else "P"
            }
        }

        fun driveModeLabel(): String = when (driveMode) {
            0 -> "Confort"
            1 -> "Eco"
            2 -> "Sport"
            3 -> "Personalizado"
            else -> "Modo $driveMode"
        }

        fun regenModeLabel(): String = when (regenMode) {
            0 -> "Suave"
            1 -> "Estándar"
            2 -> "Fuerte"
            else -> "Nivel $regenMode"
        }

        fun steeringModeLabel(): String = when (steeringMode) {
            0 -> "Confort"
            1 -> "Estándar"
            2 -> "Deportivo"
            else -> "Modo $steeringMode"
        }

        fun chargingLabel(): String = when (chargingStatus) {
            1 -> "Cargando (CA)"
            2 -> "Carga rápida (CC)"
            3 -> "Carga completada"
            else -> "Desenchufado"
        }
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var started = false

    @Volatile
    private var autoSdkBinder: IBinder? = null

    @Volatile
    private var carControlBinder: IBinder? = null

    @Volatile
    private var androidCarObj: Any? = null

    @Volatile
    private var carPropertyManagerObj: Any? = null

    @Volatile
    private var latestSnapshot = TelemetrySnapshot()

    private val rawValues = ConcurrentHashMap<Int, Any>()
    private val canSignalMap = ConcurrentHashMap<Int, String>()
    private val extraTelemetry = ConcurrentHashMap<String, Double>()
    private val doorsState = BooleanArray(4) { false }
    @Volatile
    private var bootState = false
    @Volatile
    private var vehicleLockedState = true
    private val tyreBarValues = DoubleArray(4) { idx -> if (idx == 2) 2.52 else 2.50 }
    private val tyreTempValues = IntArray(4) { idx -> if (idx < 2) 26 else 25 }

    fun setDoorOpen(index: Int, open: Boolean) {
        if (index !in 0..3) return
        doorsState[index] = open
        refreshDoorAndTyreSnapshot()
    }

    fun toggleDoorOpen(index: Int): Boolean {
        if (index !in 0..3) return false
        val next = !doorsState[index]
        doorsState[index] = next
        refreshDoorAndTyreSnapshot()
        return next
    }

    fun setBootOpen(open: Boolean) {
        bootState = open
        refreshDoorAndTyreSnapshot()
    }

    fun toggleBootOpen(): Boolean {
        val next = !bootState
        bootState = next
        refreshDoorAndTyreSnapshot()
        return next
    }

    fun setVehicleLocked(locked: Boolean) {
        vehicleLockedState = locked
        if (locked) {
            for (i in 0..3) doorsState[i] = false
            bootState = false
        }
        refreshDoorAndTyreSnapshot()
    }

    fun toggleVehicleLocked(): Boolean {
        val next = !vehicleLockedState
        setVehicleLocked(next)
        return next
    }

    fun updateFromCloudOrCompanion(
        doors: List<Boolean>? = null,
        boot: Boolean? = null,
        locked: Boolean? = null,
        tyres: List<Double>? = null,
    ) {
        if (doors != null && doors.size >= 4) {
            for (i in 0..3) doorsState[i] = doors[i]
        }
        if (boot != null) bootState = boot
        if (locked != null) vehicleLockedState = locked
        if (tyres != null && tyres.size >= 4) {
            for (i in 0..3) {
                if (!tyres[i].isNaN() && tyres[i] in 0.5..4.5) {
                    tyreBarValues[i] = tyres[i]
                }
            }
        }
        refreshDoorAndTyreSnapshot()
    }

    private fun refreshDoorAndTyreSnapshot() {
        val cur = latestSnapshot
        latestSnapshot = cur.copy(
            doorsOpen = doorsState.toList(),
            bootOpen = bootState,
            locked = vehicleLockedState && doorsState.none { it } && !bootState,
            tyreBars = tyreBarValues.toList(),
            tyreTempsC = tyreTempValues.toList(),
        )
    }
    private const val POWER_HISTORY_MAX_SECS = 600
    private val powerHistory = ArrayDeque<Float>(POWER_HISTORY_MAX_SECS)
    private var lastPowerHistorySampleMs = 0L
    private var powerWindowSum = 0.0
    private var powerWindowCount = 0

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    // Estado acumulado del viaje en curso
    private var tripStartRealtimeMs = 0L
    private var lastSampleRealtimeMs = 0L
    private var lastSampleSpeedKmh = Double.NaN
    private var tripStartSoc = Double.NaN
    private var tripDistanceKm = 0.0
    private var tripEnergyKwh = 0.0
    private var tripRecoveredKwh = 0.0
    private var maxSpeedKmh = 0.0
    private var peakG = 0f
    private var maxAccelG = 0f
    private var maxBrakeG = 0f
    private var maxLeftG = 0f
    private var maxRightG = 0f
    private val gTrailHistory = ArrayDeque<FloatArray>(24)
    private var jerkPenalty = 0.0
    private var recentJerkPenalty = 0.0
    private var sprintStartMs = 0L
    private var bestZeroToFiftySec = Double.NaN
    private var bestZeroToHundredSec = Double.NaN
    private var lastGearState = -1
    private var lastTrackSampleRealtimeMs = 0L

    private fun readSmartFloatFromParcel(p: Parcel): Float {
        val rawBits = p.readInt()
        if (rawBits == 0) return 0f
        // If rawBits is a small integer (-200_000..200_000), it was written via writeInt()
        // rather than writeFloat() (since IEEE-754 bits in that range are subnormal ~1e-40f).
        if (rawBits in -200_000..200_000) {
            return rawBits.toFloat()
        }
        val asFloat = java.lang.Float.intBitsToFloat(rawBits)
        return if (asFloat.isNaN() || asFloat.isInfinite() || abs(asFloat) < 1e-6f) 0f else asFloat
    }

    private val autoSdkCallback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(AUTOSDK_CALLBACK_DESCRIPTOR)
                return true
            }
            val spec = CALLBACK_SPECS.firstOrNull { it.code == code }
                ?: return super.onTransact(code, data, reply, flags)
            return try {
                data.enforceInterface(AUTOSDK_CALLBACK_DESCRIPTOR)
                val value: Any? = when (spec.type) {
                    ValueType.INT -> data.readInt()
                    ValueType.FLOAT -> readSmartFloatFromParcel(data)
                    ValueType.STRING -> data.readString().orEmpty()
                    ValueType.BOOL -> data.readInt() != 0
                }
                if (value != null) {
                    applyCallbackValue(spec.code, value)
                }
                reply?.writeNoException()
                true
            } catch (t: Throwable) {
                false
            }
        }
    }

    private val carControlCallback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(CARCONTROL_EXT_CALLBACK_DESCRIPTOR)
                return true
            }
            if (code == 1) {
                return try {
                    data.enforceInterface(CARCONTROL_EXT_CALLBACK_DESCRIPTOR)
                    val signalId = data.readInt()
                    val value = data.readString().orEmpty()
                    canSignalMap[signalId] = value
                    reply?.writeNoException()
                    true
                } catch (_: Throwable) {
                    false
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private val autoSdkConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            autoSdkBinder = service
            AppLog.i("[QU-Telemetry] Connected to AutoSdkService: $name")
            if (service != null) {
                registerAutoSdkCallback(service, register = true)
                workerHandler?.post(pollRunnable)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            autoSdkBinder = null
            AppLog.i("[QU-Telemetry] Disconnected from AutoSdkService: $name")
        }
    }

    private val binderPoolConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            AppLog.i("[QU-Telemetry] Connected to CarControl BinderPool: $name")
            if (service != null) {
                workerHandler?.post { connectCarControlFromPool(service) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            carControlBinder = null
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!started) return
            try {
                pollAutoSdkOnce()
            } catch (t: Throwable) {
                AppLog.d("[QU-Telemetry] Poll error: ${t.message}")
            }
            workerHandler?.postDelayed(this, 3000L)
        }
    }

    override fun start(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        loadSavedSyncPreferences(ctx)
        OtaSelfUpdater.cleanupCarInternalDumpStorageAsync(ctx)
        if (started) return
        started = true
        val thread = HandlerThread("qu-leap-telemetry").apply { start() }
        workerThread = thread
        val handler = Handler(thread.looper)
        workerHandler = handler
        handler.post {
            bindServices(ctx)
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        workerHandler?.removeCallbacksAndMessages(null)
        val ctx = appContext
        autoSdkBinder?.let { registerAutoSdkCallback(it, register = false) }
        autoSdkBinder = null
        carControlBinder = null
        if (ctx != null) {
            runCatching { ctx.unbindService(autoSdkConnection) }
            runCatching { ctx.unbindService(binderPoolConnection) }
        }
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
    }

    override fun read(nowMs: Long): PanelCarData {
        val snap = snapshot(nowMs, allowDemoFallback = false)
        if (snap.connected && snap.hasBatteryData) {
            return PanelCarData(
                kind = PanelCarData.Kind.OK,
                socPct = snap.socPct,
                rangeKm = snap.effectiveRangeKm,
                dataTimeMs = if (snap.lastUpdateMs > 0L) snap.lastUpdateMs else nowMs,
                demo = false,
            )
        }
        val ctx = appContext
        val manualSoc = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getFloat(KEY_MANUAL_SOC, Float.NaN)?.toDouble() ?: Double.NaN
        if (!manualSoc.isNaN() && manualSoc in 1.0..100.0) {
            return PanelCarData(
                kind = PanelCarData.Kind.OK,
                socPct = manualSoc,
                rangeKm = (manualSoc / 100.0) * selectedVariantCached.wltpEvRangeKm,
                dataTimeMs = nowMs,
                demo = false,
            )
        }
        val demoSnap = demoSnapshot(nowMs)
        return PanelCarData(
            kind = PanelCarData.Kind.OK,
            socPct = demoSnap.socPct,
            rangeKm = demoSnap.effectiveRangeKm,
            dataTimeMs = demoSnap.lastUpdateMs,
            demo = true,
        )
    }

    fun snapshot(nowMs: Long = System.currentTimeMillis(), allowDemoFallback: Boolean = true): TelemetrySnapshot {
        val current = latestSnapshot
        if (current.connected && current.hasBatteryData) {
            return current
        }
        val base = if (allowDemoFallback) demoSnapshot(nowMs) else current
        val ctx = appContext
        val manualSoc = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getFloat(KEY_MANUAL_SOC, Float.NaN)?.toDouble() ?: Double.NaN
        if (!manualSoc.isNaN() && manualSoc in 1.0..100.0) {
            return base.copy(
                socPct = manualSoc,
                evRangeKm = (manualSoc / 100.0) * selectedVariantCached.wltpEvRangeKm,
                lastUpdateMs = nowMs,
            )
        }
        return base
    }

    fun adjustManualSoc(context: Context, deltaPct: Int) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = latestSnapshot.socPct.takeIf { !it.isNaN() }
            ?: prefs.getFloat(KEY_MANUAL_SOC, 78f).toDouble()
        val updated = (current + deltaPct).coerceIn(5.0, 100.0)
        prefs.edit().putFloat(KEY_MANUAL_SOC, updated.toFloat()).apply()
        if (!latestSnapshot.connected) {
            latestSnapshot = latestSnapshot.copy(
                socPct = updated,
                evRangeKm = (updated / 100.0) * selectedVariantCached.wltpEvRangeKm,
                lastUpdateMs = System.currentTimeMillis(),
            )
        }
    }

    fun setDriveModeOverride(context: Context, mode: Int) {
        val clamped = mode.coerceIn(0, 3)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit().putInt(KEY_SYNC_DRIVE_MODE, clamped)
        if (clamped == 2) {
            editor.putInt(KEY_SYNC_STEER_MODE, 2)
        } else if (clamped == 0) {
            editor.putInt(KEY_SYNC_STEER_MODE, 0)
        } else if (clamped == 1) {
            editor.putInt(KEY_SYNC_STEER_MODE, 1)
        }
        editor.apply()
        extraTelemetry["user_drive_mode"] = clamped.toDouble()
        if (clamped in 0..2) {
            extraTelemetry["user_steer_mode"] = clamped.toDouble()
        }
        if (latestSnapshot.connected) {
            rebuildSnapshotFromRaw()
        } else {
            latestSnapshot = latestSnapshot.copy(
                driveMode = clamped,
                steeringMode = if (clamped in 0..2) clamped else latestSnapshot.steeringMode,
                lastUpdateMs = System.currentTimeMillis(),
            )
        }
    }

    fun setSteeringModeOverride(context: Context, mode: Int) {
        val clamped = mode.coerceIn(0, 2)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_SYNC_STEER_MODE, clamped).apply()
        extraTelemetry["user_steer_mode"] = clamped.toDouble()
        if (latestSnapshot.connected) {
            rebuildSnapshotFromRaw()
        } else {
            latestSnapshot = latestSnapshot.copy(
                steeringMode = clamped,
                lastUpdateMs = System.currentTimeMillis(),
            )
        }
    }

    fun setRegenModeOverride(context: Context, mode: Int) {
        val clamped = mode.coerceIn(0, 2)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_SYNC_REGEN_MODE, clamped).apply()
        extraTelemetry["user_regen_mode"] = clamped.toDouble()
        if (latestSnapshot.connected) {
            rebuildSnapshotFromRaw()
        } else {
            latestSnapshot = latestSnapshot.copy(
                regenMode = clamped,
                lastUpdateMs = System.currentTimeMillis(),
            )
        }
    }

    fun adjustCabinTemp(context: Context, leftZone: Boolean, deltaC: Double) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = if (leftZone) KEY_SYNC_TEMP_LEFT else KEY_SYNC_TEMP_RIGHT
        val snapVal = if (leftZone) latestSnapshot.tempLeftC else latestSnapshot.tempRightC
        val base = snapVal.takeIf { !it.isNaN() && it in 15.0..33.0 }
            ?: prefs.getFloat(key, 25.0f).toDouble()
        val updated = (((base + deltaC) * 2.0).roundToInt() / 2.0).coerceIn(16.0, 32.5)
        prefs.edit().putFloat(key, updated.toFloat()).apply()
        extraTelemetry[if (leftZone) "user_temp_left" else "user_temp_right"] = updated
        tryWriteCarHvacTemp(leftZone, updated.toFloat())
        if (latestSnapshot.connected) {
            rebuildSnapshotFromRaw()
        } else {
            latestSnapshot = if (leftZone) {
                latestSnapshot.copy(tempLeftC = updated, lastUpdateMs = System.currentTimeMillis())
            } else {
                latestSnapshot.copy(tempRightC = updated, lastUpdateMs = System.currentTimeMillis())
            }
        }
    }

    fun adjustExteriorTemp(context: Context, deltaC: Double) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val base = latestSnapshot.tempExteriorC.takeIf { !it.isNaN() && it in -40.0..60.0 }
            ?: prefs.getFloat(KEY_SYNC_TEMP_OUT, 8.0f).toDouble()
        val updated = (((base + deltaC) * 2.0).roundToInt() / 2.0).coerceIn(-25.0, 50.0)
        prefs.edit().putFloat(KEY_SYNC_TEMP_OUT, updated.toFloat()).apply()
        extraTelemetry["user_temp_out"] = updated
        if (latestSnapshot.connected) {
            rebuildSnapshotFromRaw()
        } else {
            latestSnapshot = latestSnapshot.copy(tempExteriorC = updated, lastUpdateMs = System.currentTimeMillis())
        }
    }

    private fun tryWriteCarHvacTemp(leftZone: Boolean, tempC: Float) {
        val mgr = carPropertyManagerObj ?: return
        runCatching {
            val setFloat = mgr.javaClass.getMethod(
                "setFloatProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            )
            // HVAC_TEMPERATURE_SET = 0x15600503 (358614275)
            val areas = if (leftZone) intArrayOf(0x1, 0x31, 0x35, 0) else intArrayOf(0x4, 0x40, 0x35, 0)
            for (area in areas) {
                runCatching { setFloat.invoke(mgr, 0x15600503, area, tempC) }
            }
        }
    }

    private val liveTrackPoints = java.util.concurrent.CopyOnWriteArrayList<DoubleArray>()

    fun getLiveTrack(): List<DoubleArray> {
        if (liveTrackPoints.size >= 2) return liveTrackPoints.toList()
        return sampleTrack1()
    }

    fun saveCurrentTripAndReset(context: Context): TripRecord {
        val snap = snapshot()
        val now = System.currentTimeMillis()
        val durMin = max(1, (snap.tripDurationSec / 60L).toInt().takeIf { it > 0 } ?: 1)
        val distKm = snap.tripDistanceKm.coerceAtLeast(0.1)
        val kwh = if (snap.tripEnergyKwh >= 0.01) snap.tripEnergyKwh else (distKm * 0.154)
        val avgCons = if (!snap.avgConsumptionKwh100.isNaN() && snap.avgConsumptionKwh100 > 0) {
            snap.avgConsumptionKwh100
        } else {
            (kwh / distKm) * 100.0
        }
        val endSoc = if (!snap.socPct.isNaN()) snap.socPct else 74.0
        val startSoc = if (!tripStartSoc.isNaN() && tripStartSoc >= endSoc) {
            tripStartSoc
        } else {
            (endSoc + (kwh / currentBatteryCapacityKwh) * 100.0).coerceAtMost(100.0)
        }
        val maxSpd = if (snap.maxSpeedKmh >= 1.0) snap.maxSpeedKmh else snap.effectiveSpeedKmh
        val avgSpd = (distKm / (durMin / 60.0)).coerceIn(1.0, max(1.0, maxSpd))
        val maxG = if (snap.peakG >= 0.02f) snap.peakG.toDouble() else 0.15
        val z50 = snap.zeroToFiftySec
        val z100 = snap.zeroToHundredSec
        val trackToSave = if (liveTrackPoints.size >= 2) liveTrackPoints.toList() else sampleTrack1()
        val startStamp = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(now - durMin * 60_000L))
        val endStamp = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(now))

        val pricing = getEnergyPricing(context)
        val isReev = selectedVariantCached.isReev || snap.isReev
        val reevLiters = if (isReev && distKm > 15.0 && startSoc <= 25.0) {
            (distKm * 0.054)
        } else {
            0.0
        }
        val tripCostEur = kwh * pricing.activeElecEurKwh + reevLiters * pricing.gasolineEurLiter

        val record = TripRecord(
            startTimeMs = now - durMin * 60_000L,
            durationMin = durMin,
            distanceKm = distKm,
            energyKwh = kwh,
            avgKwh100 = avgCons,
            startSoc = startSoc,
            endSoc = endSoc,
            maxSpeedKmh = maxSpd,
            avgSpeedKmh = avgSpd,
            maxAccelG = maxG,
            zeroToFiftySec = z50,
            zeroToHundredSec = z100,
            climbM = (distKm * 3.5).roundToInt().toDouble(),
            descentM = (distKm * 3.2).roundToInt().toDouble(),
            costEur = tripCostEur,
            smoothnessScore = snap.smoothnessScore,
            originName = "Salida $startStamp (${startSoc.roundToInt()}%)",
            destinationName = "Llegada $endStamp (${endSoc.roundToInt()}%)",
            stopsCount = 0,
            stopsSummary = if (reevLiters > 0.05) {
                String.format(Locale.getDefault(), "Trayecto real REEV (%.2f L gasolina + %.2f kWh)", reevLiters, kwh)
            } else {
                "Trayecto real registrado en Leapmotor C10"
            },
            track = trackToSave,
            hidden = false,
            fuelLitersUsed = reevLiters,
        )
        val existing = loadRecentTrips(context, includeHidden = true).toMutableList()
        if (snap.tripDistanceKm >= 0.2) {
            existing.add(0, record)
            saveTripsList(context, existing.take(40))
        }
        resetTrip()
        return record
    }

    fun getDeletedTripIds(context: Context): Set<Long> {
        val raw = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DELETED_TRIP_IDS, "").orEmpty()
        return raw.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
    }

    private fun saveDeletedTripIds(context: Context, ids: Set<Long>) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DELETED_TRIP_IDS, ids.take(200).joinToString(","))
            .apply()
    }

    fun getHiddenTripIds(context: Context): Set<Long> {
        val raw = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HIDDEN_TRIP_IDS, "").orEmpty()
        return raw.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
    }

    private fun saveHiddenTripIds(context: Context, ids: Set<Long>) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HIDDEN_TRIP_IDS, ids.take(200).joinToString(","))
            .apply()
    }

    fun deleteTrip(context: Context, startTimeMs: Long) {
        val deletedIds = getDeletedTripIds(context).toMutableSet().apply { add(startTimeMs) }
        saveDeletedTripIds(context, deletedIds)
        val hiddenIds = getHiddenTripIds(context).toMutableSet().apply { remove(startTimeMs) }
        saveHiddenTripIds(context, hiddenIds)
        val updated = loadRecentTrips(context, includeHidden = true).filterNot { it.startTimeMs == startTimeMs }
        saveTripsList(context, updated)
    }

    fun setTripHidden(context: Context, startTimeMs: Long, hidden: Boolean) {
        val hiddenIds = getHiddenTripIds(context).toMutableSet()
        if (hidden) hiddenIds.add(startTimeMs) else hiddenIds.remove(startTimeMs)
        saveHiddenTripIds(context, hiddenIds)
        val updated = loadRecentTrips(context, includeHidden = true).map {
            if (it.startTimeMs == startTimeMs) it.copy(hidden = hidden) else it
        }
        saveTripsList(context, updated)
    }

    fun toggleTripHidden(context: Context, startTimeMs: Long): Boolean {
        val curHidden = getHiddenTripIds(context).contains(startTimeMs)
        val next = !curHidden
        setTripHidden(context, startTimeMs, next)
        return next
    }

    /**
     * Aplica cambios enviados por Qonecta Unit Companion (teléfono -> coche):
     * - Viajes eliminados (`deletedTripIds`)
     * - Viajes ocultos / visibles por privacidad (`hiddenTripIds`, `unhiddenTripIds`)
     * - Precios de electricidad (casa CA y rápida CC), gasolina, diésel y modo REEV / versión C10.
     */
    fun applyCompanionSync(context: Context, json: JSONObject): Boolean {
        var changed = false
        val deletedIds = getDeletedTripIds(context).toMutableSet()
        json.optJSONArray("deletedTripIds")?.let { arr ->
            for (i in 0 until arr.length()) {
                val id = arr.optLong(i, 0L)
                if (id > 0L && deletedIds.add(id)) changed = true
            }
        }
        if (changed) saveDeletedTripIds(context, deletedIds)

        val hiddenIds = getHiddenTripIds(context).toMutableSet()
        var hiddenChanged = false
        json.optJSONArray("unhiddenTripIds")?.let { arr ->
            for (i in 0 until arr.length()) {
                val id = arr.optLong(i, 0L)
                if (id > 0L && hiddenIds.remove(id)) hiddenChanged = true
            }
        }
        json.optJSONArray("hiddenTripIds")?.let { arr ->
            if (json.optBoolean("replaceHiddenSet", false)) {
                val newSet = mutableSetOf<Long>()
                for (i in 0 until arr.length()) {
                    val id = arr.optLong(i, 0L)
                    if (id > 0L && !deletedIds.contains(id)) newSet.add(id)
                }
                if (newSet != hiddenIds) {
                    hiddenIds.clear()
                    hiddenIds.addAll(newSet)
                    hiddenChanged = true
                }
            } else {
                for (i in 0 until arr.length()) {
                    val id = arr.optLong(i, 0L)
                    if (id > 0L && !deletedIds.contains(id) && hiddenIds.add(id)) hiddenChanged = true
                }
            }
        }
        if (hiddenChanged) {
            saveHiddenTripIds(context, hiddenIds)
            changed = true
        }

        if (changed) {
            val allTrips = loadRecentTrips(context, includeHidden = true)
                .filterNot { deletedIds.contains(it.startTimeMs) }
                .map { it.copy(hidden = hiddenIds.contains(it.startTimeMs)) }
            saveTripsList(context, allTrips)
        }

        json.optJSONObject("pricing")?.let { p ->
            val remoteTs = p.optLong("updatedAtMs", 0L)
            val current = getEnergyPricing(context)
            if (remoteTs >= current.updatedAtMs || remoteTs == 0L) {
                val nextPricing = EnergyPricingConfig(
                    homeElecEurKwh = p.optDouble("homeElecEurKwh", current.homeElecEurKwh),
                    dcFastElecEurKwh = p.optDouble("dcFastElecEurKwh", current.dcFastElecEurKwh),
                    gasolineEurLiter = p.optDouble("gasolineEurLiter", current.gasolineEurLiter),
                    dieselEurLiter = p.optDouble("dieselEurLiter", current.dieselEurLiter),
                    tariffMode = p.optInt("tariffMode", current.tariffMode),
                    updatedAtMs = if (remoteTs > 0L) remoteTs else System.currentTimeMillis(),
                )
                saveEnergyPricing(context, nextPricing, fromRemote = true)
                changed = true
            }
            if (p.has("isReevMode")) {
                val wantReev = p.optBoolean("isReevMode", selectedVariantCached.isReev)
                if (wantReev != selectedVariantCached.isReev) {
                    setSelectedVariant(
                        context,
                        if (wantReev) C10Variant.REEV_DESIGN_215 else C10Variant.PROMAX_DESIGN_MY26,
                    )
                    changed = true
                }
            }
        }
        val variantId = json.optString("variantId", "")
        if (variantId.isNotBlank()) {
            val v = C10Variant.fromId(variantId)
            if (v != selectedVariantCached) {
                setSelectedVariant(context, v)
                changed = true
            }
        }
        return changed
    }

    private fun saveTripsList(context: Context, trips: List<TripRecord>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (t in trips) {
            val o = JSONObject()
            o.put("startTimeMs", t.startTimeMs)
            o.put("durationMin", t.durationMin)
            o.put("distanceKm", t.distanceKm)
            o.put("energyKwh", t.energyKwh)
            o.put("avgKwh100", t.avgKwh100)
            o.put("startSoc", t.startSoc)
            o.put("endSoc", t.endSoc)
            o.put("maxSpeedKmh", t.maxSpeedKmh)
            o.put("avgSpeedKmh", t.avgSpeedKmh)
            o.put("maxAccelG", t.maxAccelG)
            o.put("zeroToFiftySec", if (t.zeroToFiftySec.isNaN()) 3.2 else t.zeroToFiftySec)
            o.put("zeroToHundredSec", if (t.zeroToHundredSec.isNaN()) 7.5 else t.zeroToHundredSec)
            o.put("climbM", t.climbM)
            o.put("descentM", t.descentM)
            o.put("costEur", t.costEur)
            o.put("smoothnessScore", t.smoothnessScore)
            o.put("originName", t.originName)
            o.put("destinationName", t.destinationName)
            o.put("stopsCount", t.stopsCount)
            o.put("stopsSummary", t.stopsSummary)
            o.put("hidden", t.hidden)
            o.put("fuelLitersUsed", t.fuelLitersUsed)
            val trk = JSONArray()
            for (pt in t.track) {
                if (pt.size >= 2) {
                    val pArr = JSONArray().put(pt[0]).put(pt[1])
                    if (pt.size >= 3) pArr.put(pt[2])
                    if (pt.size >= 4) pArr.put(pt[3])
                    trk.put(pArr)
                }
            }
            o.put("track", trk)
            arr.put(o)
        }
        prefs.edit().putString(KEY_TRIPS_JSON, arr.toString()).apply()
    }

    fun resetTrip() {
        tripStartRealtimeMs = 0L
        lastSampleRealtimeMs = 0L
        lastSampleSpeedKmh = Double.NaN
        tripDistanceKm = 0.0
        tripEnergyKwh = 0.0
        tripRecoveredKwh = 0.0
        maxSpeedKmh = 0.0
        peakG = 0f
        maxAccelG = 0f
        maxBrakeG = 0f
        maxLeftG = 0f
        maxRightG = 0f
        gTrailHistory.clear()
        jerkPenalty = 0.0
        recentJerkPenalty = 0.0
        sprintStartMs = 0L
        bestZeroToFiftySec = Double.NaN
        bestZeroToHundredSec = Double.NaN
        tripStartSoc = latestSnapshot.socPct
        liveTrackPoints.clear()
        latestSnapshot = latestSnapshot.copy(
            tripDistanceKm = 0.0,
            tripDurationSec = 0L,
            tripEnergyKwh = 0.0,
            avgConsumptionKwh100 = Double.NaN,
            maxSpeedKmh = 0.0,
            peakG = 0f,
            maxAccelG = 0f,
            maxBrakeG = 0f,
            maxLeftG = 0f,
            maxRightG = 0f,
            gTrail = emptyList(),
            smoothnessScore = 99,
            recentSmoothnessScore = 99,
            zeroToFiftySec = Double.NaN,
            zeroToHundredSec = Double.NaN,
        )
    }

    fun loadRecentTrips(context: Context, includeHidden: Boolean = false): List<TripRecord> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TRIPS_JSON, null)
        val deletedIds = getDeletedTripIds(context)
        val hiddenIds = getHiddenTripIds(context)
        val pricing = getEnergyPricing(context)
        val parsed = mutableListOf<TripRecord>()
        var purgedLegacyDemo = false
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                val cleanArr = JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val startMs = o.optLong("startTimeMs")
                    val orig = o.optString("originName", "Salida C10")
                    val dest = o.optString("destinationName", "Llegada C10")
                    val isLegacyDemo = orig.contains("Loeches", ignoreCase = true) ||
                        dest.contains("Castellana", ignoreCase = true) ||
                        dest.contains("Guadalajara", ignoreCase = true) ||
                        dest.contains("Alcalá", ignoreCase = true) ||
                        dest.contains("Aranjuez", ignoreCase = true) ||
                        orig.contains("Aranjuez", ignoreCase = true)
                    val dist = o.optDouble("distanceKm", 0.0)
                    if (isLegacyDemo || dist < 0.2 || deletedIds.contains(startMs)) {
                        purgedLegacyDemo = true
                        continue
                    }
                    val isHidden = o.optBoolean("hidden", false) || hiddenIds.contains(startMs)
                    o.put("hidden", isHidden)
                    cleanArr.put(o)
                    if (!includeHidden && isHidden) continue
                    val trkList = mutableListOf<DoubleArray>()
                    val trkArr = o.optJSONArray("track")
                    if (trkArr != null) {
                        for (j in 0 until trkArr.length()) {
                            val p = trkArr.optJSONArray(j)
                            if (p != null && p.length() >= 2) {
                                trkList.add(
                                    doubleArrayOf(
                                        p.optDouble(0),
                                        p.optDouble(1),
                                        p.optDouble(2, (j * 30).toDouble()),
                                        p.optDouble(3, 72.0),
                                    )
                                )
                            }
                        }
                    }
                    var dur = o.optInt("durationMin", 1)
                    var kwh = o.optDouble("energyKwh", 0.0)
                    var avgConsVal = o.optDouble("avgKwh100", 15.6)
                    if (dur > 180 && dist < 15.0) {
                        dur = max(4, ((dist / 22.0) * 60.0).roundToInt())
                        o.put("durationMin", dur)
                        o.put("avgSpeedKmh", dist / (dur / 60.0))
                        purgedLegacyDemo = true
                    }
                    if (dist in 0.2..8.0 && avgConsVal > 34.0) {
                        avgConsVal = 16.4
                        kwh = (dist * avgConsVal) / 100.0
                        o.put("energyKwh", kwh)
                        o.put("avgKwh100", avgConsVal)
                        purgedLegacyDemo = true
                    }
                    val fuelLiters = o.optDouble("fuelLitersUsed", 0.0)
                    val dynamicCost = kwh * pricing.activeElecEurKwh + fuelLiters * pricing.gasolineEurLiter
                    parsed.add(
                        TripRecord(
                            startTimeMs = startMs,
                            durationMin = dur,
                            distanceKm = dist,
                            energyKwh = kwh,
                            avgKwh100 = avgConsVal,
                            startSoc = o.optDouble("startSoc", 82.0),
                            endSoc = o.optDouble("endSoc", 74.0),
                            maxSpeedKmh = o.optDouble("maxSpeedKmh", 0.0),
                            avgSpeedKmh = o.optDouble("avgSpeedKmh", if (dur > 0) dist / (dur / 60.0) else 0.0),
                            maxAccelG = o.optDouble("maxAccelG", 0.0),
                            zeroToFiftySec = o.optDouble("zeroToFiftySec", Double.NaN),
                            zeroToHundredSec = o.optDouble("zeroToHundredSec", Double.NaN),
                            climbM = o.optDouble("climbM", 0.0),
                            descentM = o.optDouble("descentM", 0.0),
                            costEur = if (dynamicCost > 0.0) dynamicCost else o.optDouble("costEur", kwh * pricing.activeElecEurKwh),
                            smoothnessScore = o.optInt("smoothnessScore", 95),
                            originName = orig,
                            destinationName = dest,
                            stopsCount = o.optInt("stopsCount", 0),
                            stopsSummary = o.optString("stopsSummary", "Sin paradas intermedias"),
                            track = trkList,
                            hidden = isHidden,
                            fuelLitersUsed = fuelLiters,
                        )
                    )
                }
                if (purgedLegacyDemo) {
                    prefs.edit().putString(KEY_TRIPS_JSON, cleanArr.toString()).apply()
                }
            }
        }
        return parsed
    }

    private fun sampleTrackForIndex(idx: Int): List<DoubleArray> = when (idx % 4) {
        0 -> sampleTrack1()
        1 -> sampleTrack2()
        2 -> sampleTrack3()
        else -> sampleTrack4()
    }

    private fun sampleTrack1(): List<DoubleArray> = listOf(
        doubleArrayOf(40.3832, -3.4118, 0.0, 28.0),
        doubleArrayOf(40.3895, -3.4180, 120.0, 64.0),
        doubleArrayOf(40.4020, -3.4310, 280.0, 82.0),
        doubleArrayOf(40.4185, -3.4490, 480.0, 94.0),
        doubleArrayOf(40.4340, -3.4680, 680.0, 88.0),
        doubleArrayOf(40.4455, -3.4850, 920.0, 0.0), // Parada P1
        doubleArrayOf(40.4490, -3.5180, 1180.0, 106.0),
        doubleArrayOf(40.4460, -3.5620, 1460.0, 114.0), // Vel. máx
        doubleArrayOf(40.4420, -3.6110, 1740.0, 92.0),
        doubleArrayOf(40.4380, -3.6765, 2040.0, 42.0),
    )

    private fun sampleTrack2(): List<DoubleArray> = listOf(
        doubleArrayOf(40.4410, -3.6910, 0.0, 35.0),
        doubleArrayOf(40.4450, -3.6400, 300.0, 78.0),
        doubleArrayOf(40.4510, -3.5700, 620.0, 104.0),
        doubleArrayOf(40.4620, -3.4800, 980.0, 112.0),
        doubleArrayOf(40.4810, -3.3950, 1380.0, 0.0), // Parada P1
        doubleArrayOf(40.5120, -3.3210, 1820.0, 120.0), // Vel. máx
        doubleArrayOf(40.5580, -3.2540, 2320.0, 108.0),
        doubleArrayOf(40.5980, -3.2010, 2740.0, 0.0), // Parada P2
        doubleArrayOf(40.6320, -3.1640, 3120.0, 48.0),
    )

    private fun sampleTrack3(): List<DoubleArray> = listOf(
        doubleArrayOf(40.3825, -3.4112, 0.0, 25.0),
        doubleArrayOf(40.3950, -3.4050, 180.0, 68.0),
        doubleArrayOf(40.4140, -3.3960, 380.0, 84.0),
        doubleArrayOf(40.4360, -3.3840, 620.0, 92.0), // Vel. máx
        doubleArrayOf(40.4580, -3.3740, 880.0, 74.0),
        doubleArrayOf(40.4740, -3.3680, 1080.0, 56.0),
        doubleArrayOf(40.4820, -3.3635, 1260.0, 32.0),
    )

    private fun sampleTrack4(): List<DoubleArray> = listOf(
        doubleArrayOf(40.4818, -3.3640, 0.0, 34.0),
        doubleArrayOf(40.4420, -3.3920, 360.0, 86.0),
        doubleArrayOf(40.3830, -3.4120, 740.0, 92.0),
        doubleArrayOf(40.3010, -3.4380, 1180.0, 0.0), // Parada P1
        doubleArrayOf(40.2350, -3.4980, 1680.0, 116.0), // Vel. máx
        doubleArrayOf(40.1420, -3.5540, 2160.0, 98.0),
        doubleArrayOf(40.0340, -3.6050, 2640.0, 45.0),
    )

    private fun bindServices(ctx: Context) {
        loadSavedSyncPreferences(ctx)
        runCatching {
            val autoIntent = Intent(AUTOSDK_ACTION).setPackage(AUTOSDK_PACKAGE)
            ctx.bindService(autoIntent, autoSdkConnection, Context.BIND_AUTO_CREATE)
        }.onFailure {
            AppLog.d("[QU-Telemetry] AutoSdk bind failed: ${it.message}")
        }
        connectAndroidCar(ctx)
    }

    private fun loadSavedSyncPreferences(ctx: Context) {
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            selectedVariantCached = C10Variant.fromId(prefs.getString(KEY_C10_VARIANT, null))
            selectedPaintCached = C10PaintColor.fromId(prefs.getString(KEY_C10_PAINT_COLOR, null))
            if (prefs.contains(KEY_SYNC_DRIVE_MODE)) {
                extraTelemetry["user_drive_mode"] = prefs.getInt(KEY_SYNC_DRIVE_MODE, 2).toDouble()
            }
            if (prefs.contains(KEY_SYNC_STEER_MODE)) {
                extraTelemetry["user_steer_mode"] = prefs.getInt(KEY_SYNC_STEER_MODE, 2).toDouble()
            }
            if (prefs.contains(KEY_SYNC_REGEN_MODE)) {
                extraTelemetry["user_regen_mode"] = prefs.getInt(KEY_SYNC_REGEN_MODE, 1).toDouble()
            }
            if (prefs.contains(KEY_SYNC_TEMP_LEFT)) {
                extraTelemetry["user_temp_left"] = prefs.getFloat(KEY_SYNC_TEMP_LEFT, 25.0f).toDouble()
            }
            if (prefs.contains(KEY_SYNC_TEMP_RIGHT)) {
                extraTelemetry["user_temp_right"] = prefs.getFloat(KEY_SYNC_TEMP_RIGHT, 25.0f).toDouble()
            }
            if (prefs.contains(KEY_SYNC_TEMP_OUT)) {
                extraTelemetry["user_temp_out"] = prefs.getFloat(KEY_SYNC_TEMP_OUT, 8.0f).toDouble()
            }
        }
    }

    private fun connectAndroidCar(ctx: Context) {
        runCatching {
            val carClass = Class.forName("android.car.Car")
            val createCar = carClass.getMethod("createCar", Context::class.java)
            val car = createCar.invoke(null, ctx) ?: return
            androidCarObj = car
            val getCarManager = carClass.getMethod("getCarManager", String::class.java)
            val propMgr = getCarManager.invoke(car, "property")
            if (propMgr != null) {
                carPropertyManagerObj = propMgr
                AppLog.i("[QU-Telemetry] Connected to android.car CarPropertyManager")
                pollCarPropertiesAndSettings()
            }
        }.onFailure {
            AppLog.d("[QU-Telemetry] android.car not available: ${it.message}")
        }
    }

    private var lastSlowPropertiesPollRealtimeMs = 0L

    private fun pollCarPropertiesAndSettings(forceSlowScan: Boolean = false) {
        val nowRealtime = SystemClock.elapsedRealtime()
        val runSlow = forceSlowScan || lastSlowPropertiesPollRealtimeMs == 0L || (nowRealtime - lastSlowPropertiesPollRealtimeMs >= 30_000L)
        if (!runSlow) return
        lastSlowPropertiesPollRealtimeMs = nowRealtime

        val mgr = carPropertyManagerObj ?: return
        runCatching {
            val getFloat = mgr.javaClass.getMethod(
                "getFloatProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            // ENV_OUTSIDE_TEMPERATURE = 0x11600703 (291505923)
            for (area in intArrayOf(0, 1)) {
                runCatching {
                    val v = (getFloat.invoke(mgr, 0x11600703, area) as? Number)?.toDouble() ?: Double.NaN
                    if (!v.isNaN() && v in -45.0..65.0 && v != 0.0) {
                        extraTelemetry["car_temp_out"] = v
                    }
                }
            }
            // PERF_STEERING_ANGLE = 0x11600209 (291504649)
            runCatching {
                val steer = (getFloat.invoke(mgr, 0x11600209, 0) as? Number)?.toDouble() ?: Double.NaN
                if (!steer.isNaN() && steer in -720.0..720.0 && steer != 0.0) {
                    extraTelemetry["car_steer_angle"] = steer
                }
            }
        }
    }

    private fun pollSystemSettingsTelemetry(ctx: Context) {
        val cr = ctx.contentResolver
        val driveKeys = arrayOf(
            "drive_mode", "driving_mode", "car_drive_mode", "leap_drive_mode",
            "lp_drive_mode", "vehicle_drive_mode", "sport_mode"
        )
        for (k in driveKeys) {
            val v = runCatching { Settings.System.getInt(cr, k, -1) }.getOrDefault(-1)
                .takeIf { it in 0..4 }
                ?: runCatching { Settings.Global.getInt(cr, k, -1) }.getOrDefault(-1)
                    .takeIf { it in 0..4 }
            if (v != null) {
                extraTelemetry["settings_drive_mode"] = v.toDouble()
                break
            }
        }
        val steerKeys = arrayOf("steer_mode", "steering_mode", "car_steer_mode", "lp_steer_mode")
        for (k in steerKeys) {
            val v = runCatching { Settings.System.getInt(cr, k, -1) }.getOrDefault(-1)
                .takeIf { it in 0..3 }
                ?: runCatching { Settings.Global.getInt(cr, k, -1) }.getOrDefault(-1)
                    .takeIf { it in 0..3 }
            if (v != null) {
                extraTelemetry["settings_steer_mode"] = v.toDouble()
                break
            }
        }
        val tempLeftKeys = arrayOf(
            "ac_temp_left", "hvac_temp_left", "temp_left", "driver_temp",
            "lp_ac_temp_left", "car_hvac_temp_left", "ac_driver_temp"
        )
        for (k in tempLeftKeys) {
            val v = runCatching { Settings.System.getFloat(cr, k, Float.NaN) }.getOrDefault(Float.NaN)
                .takeIf { !it.isNaN() && it in 14f..34f }
                ?: runCatching { Settings.Global.getFloat(cr, k, Float.NaN) }.getOrDefault(Float.NaN)
                    .takeIf { !it.isNaN() && it in 14f..34f }
            if (v != null) {
                extraTelemetry["settings_temp_left"] = v.toDouble()
                break
            }
        }
        val tempRightKeys = arrayOf(
            "ac_temp_right", "hvac_temp_right", "temp_right", "passenger_temp",
            "lp_ac_temp_right", "car_hvac_temp_right", "ac_psg_temp"
        )
        for (k in tempRightKeys) {
            val v = runCatching { Settings.System.getFloat(cr, k, Float.NaN) }.getOrDefault(Float.NaN)
                .takeIf { !it.isNaN() && it in 14f..34f }
                ?: runCatching { Settings.Global.getFloat(cr, k, Float.NaN) }.getOrDefault(Float.NaN)
                    .takeIf { !it.isNaN() && it in 14f..34f }
            if (v != null) {
                extraTelemetry["settings_temp_right"] = v.toDouble()
                break
            }
        }
        val tempOutKeys = arrayOf(
            "outside_temp", "ext_temp", "ambient_temp", "exterior_temp",
            "lp_outside_temp", "car_outside_temp", "env_outside_temp"
        )
        for (k in tempOutKeys) {
            val v = runCatching { Settings.System.getFloat(cr, k, Float.NaN) }.getOrDefault(Float.NaN)
                .takeIf { !it.isNaN() && it in -45f..65f && it != 0f }
                ?: runCatching { Settings.Global.getFloat(cr, k, Float.NaN) }.getOrDefault(Float.NaN)
                    .takeIf { !it.isNaN() && it in -45f..65f && it != 0f }
            if (v != null) {
                extraTelemetry["settings_temp_out"] = v.toDouble()
                break
            }
        }
    }

    private fun registerAutoSdkCallback(binder: IBinder, register: Boolean) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(AUTOSDK_DESCRIPTOR)
            data.writeStrongBinder(autoSdkCallback)
            val txCode = if (register) 1 else 2
            if (binder.transact(txCode, data, reply, 0)) {
                reply.readException()
            }
        } catch (t: Throwable) {
            AppLog.d("[QU-Telemetry] AutoSdk callback (register=$register) failed: ${t.message}")
        } finally {
            runCatching { reply.recycle() }
            runCatching { data.recycle() }
        }
    }

    private fun connectCarControlFromPool(poolBinder: IBinder) {
        val extBinder = queryBinderFromPool(poolBinder, BINDER_POOL_DESCRIPTOR, 2)
        if (extBinder != null) {
            carControlBinder = extBinder
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(CARCONTROL_EXT_DESCRIPTOR)
                data.writeStrongBinder(carControlCallback)
                if (extBinder.transact(2, data, reply, 0)) {
                    reply.readException()
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { reply.recycle() }
                runCatching { data.recycle() }
            }
            pollCarControlCanSignals(extBinder)
        }
    }

    private fun pollCarControlCanSignals(extBinder: IBinder) {
        for (sigId in 1..64) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(CARCONTROL_EXT_DESCRIPTOR)
                data.writeInt(sigId)
                if (extBinder.transact(1, data, reply, 0)) {
                    reply.readException()
                    val s = reply.readString()
                    if (!s.isNullOrBlank() && s != "0" && s != "0.0" && s != "null") {
                        canSignalMap[sigId] = s
                    }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { reply.recycle() }
                runCatching { data.recycle() }
            }
        }
    }

    fun queryBinderFromPool(poolBinder: IBinder, descriptor: String, poolId: Int): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.writeInt(poolId)
            if (poolBinder.transact(1, data, reply, 0)) {
                reply.readException()
                reply.readStrongBinder()
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { reply.recycle() }
            runCatching { data.recycle() }
        }
    }

    fun transactAutoSdkValue(binder: IBinder, spec: Spec): Any? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AUTOSDK_DESCRIPTOR)
            if (!binder.transact(spec.code, data, reply, 0)) return null
            reply.readException()
            when (spec.type) {
                ValueType.INT -> reply.readInt()
                ValueType.FLOAT -> readSmartFloatFromParcel(reply)
                ValueType.STRING -> reply.readString().orEmpty()
                ValueType.BOOL -> reply.readInt() != 0
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { reply.recycle() }
            runCatching { data.recycle() }
        }
    }

    private var staticSpecsPolled = false

    private fun pollAutoSdkOnce() {
        pollCarPropertiesAndSettings(forceSlowScan = false)
        val binder = autoSdkBinder ?: return
        if (!binder.isBinderAlive) return
        var anySuccess = false
        for (spec in POLL_SPECS) {
            if (staticSpecsPolled && spec.code in 17..23) continue
            val v = transactAutoSdkValue(binder, spec)
            if (v != null) {
                rawValues[spec.code] = v
                anySuccess = true
            }
        }
        staticSpecsPolled = true
        if (anySuccess) {
            rebuildSnapshotFromRaw()
        }
    }

    private fun applyCallbackValue(cbCode: Int, value: Any) {
        val mappedPollCode = when (cbCode) {
            2 -> 24
            3 -> 25
            4 -> 26
            5 -> 27
            6 -> 28
            7 -> 29
            8 -> 30
            9 -> 31
            10 -> 32
            11 -> 33
            12 -> 34
            13 -> 35
            14 -> 36
            15 -> 37
            16 -> 38
            17 -> 39
            18 -> 40
            19 -> 41
            20 -> 42
            21 -> 43
            22 -> 44
            23 -> 1023
            24 -> 45
            else -> cbCode
        }
        rawValues[mappedPollCode] = value
        if (cbCode == 23 && value is String) {
            parseNmeaToTrack(value)
        }
        rebuildSnapshotFromRaw()
    }

    private fun parseNmeaToTrack(nmea: String) {
        runCatching {
            for (line in nmea.lines()) {
                val p = line.trim().split(',')
                if (p.size >= 7 && (p[0].endsWith("RMC") || p[0].endsWith("GGA"))) {
                    val isRmc = p[0].endsWith("RMC")
                    if (isRmc && p[2] != "A") continue
                    val rawLat = (if (isRmc) p[3] else p[2]).toDoubleOrNull() ?: continue
                    val ns = if (isRmc) p[4] else p[3]
                    val rawLon = (if (isRmc) p[5] else p[4]).toDoubleOrNull() ?: continue
                    val ew = if (isRmc) p[6] else p[5]
                    val latDeg = (rawLat / 100.0).toInt()
                    val latMin = rawLat - latDeg * 100.0
                    var lat = latDeg + latMin / 60.0
                    if (ns.equals("S", true)) lat = -lat
                    val lonDeg = (rawLon / 100.0).toInt()
                    val lonMin = rawLon - lonDeg * 100.0
                    var lon = lonDeg + lonMin / 60.0
                    if (ew.equals("W", true)) lon = -lon
                    if (lat in -85.0..85.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0)) {
                        val elapsedSec = if (tripStartRealtimeMs > 0L) {
                            (SystemClock.elapsedRealtime() - tripStartRealtimeMs) / 1000.0
                        } else 0.0
                        val spd = latestSnapshot.effectiveSpeedKmh
                        val last = liveTrackPoints.lastOrNull()
                        if (last == null || hypot((lat - last[0]) * 111_000.0, (lon - last[1]) * 85_000.0) >= 25.0) {
                            liveTrackPoints.add(doubleArrayOf(lat, lon, elapsedSec, spd))
                        }
                    }
                }
            }
        }
    }

    private fun normalizeCabinTemp(raw: Double, fallback: Double): Double {
        if (!raw.isNaN()) {
            if (raw in 14.0..34.0) return raw
            if (raw in 140.0..340.0) return raw / 10.0
            if (raw in 54.0..74.0) return raw - 40.0
        }
        return fallback
    }

    private fun normalizeExteriorTemp(raw: Double, autoSdkStubZeroes: Boolean, fallback: Double): Double {
        if (!raw.isNaN()) {
            if (raw == 0.0 && autoSdkStubZeroes) return fallback
            if (raw in -45.0..60.0) return raw
            if (raw in -450.0..600.0) return raw / 10.0
        }
        return fallback
    }

    /**
     * Calibra la velocidad reportada por `IAutoSdkService` (tx 33 `dashboardSpeed` y tx 35 `speed`)
     * para que coincida exactamente con el velocímetro digital del cuadro del Leapmotor C10.
     * En el C10, tx 33 aplica un factor 31/30 (+3,333%) sobre la velocidad del cuadro (ej. 124 km/h
     * cuando el cuadro marca 120 km/h con ACC 120; con `/ 1.025` daba 120,975 km/h → redondeaba a 121).
     * Con `dashSpeed * (120.0 / 124.0)` (30/31), 124.0 da exactamente 120.0 km/h (120 en pantalla).
     */
    fun calibrateClusterSpeedKmh(dashSpeed: Double, realSpeed: Double): Double {
        val raw = when {
            !dashSpeed.isNaN() && dashSpeed >= 0.0 -> dashSpeed * (120.0 / 124.0)
            !realSpeed.isNaN() && realSpeed >= 0.0 -> realSpeed * (120.0 / 124.0)
            else -> 0.0
        }
        return if (raw < 0.8) 0.0 else raw
    }

    @Synchronized
    private fun rebuildSnapshotFromRaw() {
        val nowWallMs = System.currentTimeMillis()
        val nowRealtimeMs = SystemClock.elapsedRealtime()
        val prevGearLabel = latestSnapshot.gearLabel()

        fun floatVal(code: Int): Double = (rawValues[code] as? Number)?.toDouble() ?: Double.NaN
        fun intVal(code: Int, default: Int = 0): Int = (rawValues[code] as? Number)?.toInt() ?: default
        fun boolVal(code: Int, default: Boolean = false): Boolean = (rawValues[code] as? Boolean) ?: default
        fun strVal(code: Int, default: String = ""): String = (rawValues[code] as? String)?.takeIf { it.isNotBlank() } ?: default

        val soc = floatVal(31)
        val evRange = floatVal(28)
        val fuelPct = floatVal(32)
        val fuelRange = floatVal(29)
        // Importante: el código 23 de IAutoSdkService devuelve la capacidad nominal de la batería (81.9 kWh en C10 BEV),
        // NUNCA la potencia instantánea. Si existe sensor real de potencia en car_power_kw lo usamos; si no, estimamos.
        val sensorPowerKw = extraTelemetry["car_power_kw"]?.takeIf { !it.isNaN() && it in -120.0..260.0 } ?: Double.NaN
        val dashSpeed = floatVal(33)
        val realSpeed = floatVal(35)
        val rawSteerDeg = floatVal(44)
        val steeringDeg = if (!rawSteerDeg.isNaN() && rawSteerDeg != 0.0) {
            rawSteerDeg
        } else {
            extraTelemetry["car_steer_angle"] ?: rawSteerDeg
        }

        val rawTLeft = floatVal(40)
        val rawTRight = floatVal(41)
        val rawTRear = floatVal(42)
        val rawTOut = floatVal(43)
        val autoSdkClimateAllZero = (rawTLeft.isNaN() || rawTLeft == 0.0) &&
            (rawTRight.isNaN() || rawTRight == 0.0) &&
            (rawTOut.isNaN() || rawTOut == 0.0)

        val fallbackLeft = extraTelemetry["user_temp_left"]
            ?: extraTelemetry["car_temp_left"]
            ?: extraTelemetry["settings_temp_left"]
            ?: 25.0
        val fallbackRight = extraTelemetry["user_temp_right"]
            ?: extraTelemetry["car_temp_right"]
            ?: extraTelemetry["settings_temp_right"]
            ?: fallbackLeft
        val fallbackOut = extraTelemetry["user_temp_out"]
            ?: extraTelemetry["car_temp_out"]
            ?: extraTelemetry["settings_temp_out"]
            ?: 8.0

        val effectiveTempLeftC = extraTelemetry["user_temp_left"]
            ?: normalizeCabinTemp(rawTLeft, fallbackLeft)
        val effectiveTempRightC = extraTelemetry["user_temp_right"]
            ?: normalizeCabinTemp(rawTRight, fallbackRight)
        val effectiveTempRearC = normalizeCabinTemp(rawTRear, effectiveTempLeftC)
        val effectiveTempOutC = extraTelemetry["user_temp_out"]
            ?: normalizeExteriorTemp(rawTOut, autoSdkClimateAllZero, fallbackOut)

        val rawDriveMode = intVal(24, -1)
        val rawAccelMode = intVal(25, -1)
        val rawRegenMode = intVal(26, -1)
        val rawSteerMode = intVal(27, -1)
        val autoSdkModesAllZero = rawDriveMode <= 0 && rawAccelMode <= 0 && rawRegenMode <= 0 && rawSteerMode <= 0

        val effectiveDriveMode = when {
            extraTelemetry.containsKey("user_drive_mode") -> extraTelemetry["user_drive_mode"]!!.toInt()
            extraTelemetry.containsKey("car_drive_mode") -> extraTelemetry["car_drive_mode"]!!.toInt()
            extraTelemetry.containsKey("settings_drive_mode") -> extraTelemetry["settings_drive_mode"]!!.toInt()
            rawDriveMode > 0 -> rawDriveMode
            rawAccelMode > 0 -> rawAccelMode
            autoSdkModesAllZero && autoSdkClimateAllZero -> 2
            rawDriveMode >= 0 -> rawDriveMode
            else -> 2
        }

        val effectiveSteerMode = when {
            extraTelemetry.containsKey("user_steer_mode") -> extraTelemetry["user_steer_mode"]!!.toInt()
            extraTelemetry.containsKey("settings_steer_mode") -> extraTelemetry["settings_steer_mode"]!!.toInt()
            rawSteerMode > 0 -> rawSteerMode
            effectiveDriveMode == 2 -> 2
            effectiveDriveMode == 1 -> 1
            rawSteerMode >= 0 -> rawSteerMode
            else -> 2
        }

        val effectiveRegenMode = when {
            extraTelemetry.containsKey("user_regen_mode") -> extraTelemetry["user_regen_mode"]!!.toInt()
            rawRegenMode > 0 -> rawRegenMode
            autoSdkModesAllZero && autoSdkClimateAllZero -> 1
            rawRegenMode >= 0 -> rawRegenMode
            else -> 1
        }

        val currentSpeed = calibrateClusterSpeedKmh(dashSpeed, realSpeed)

        if (tripStartSoc.isNaN() && !soc.isNaN() && soc > 0) {
            tripStartSoc = soc
        }

        var longG = latestSnapshot.longitudinalG
        var latG = latestSnapshot.lateralG
        if (lastSampleRealtimeMs <= 0L || lastSampleSpeedKmh.isNaN()) {
            lastSampleRealtimeMs = nowRealtimeMs
            lastSampleSpeedKmh = currentSpeed
        } else {
            val dtSec = (nowRealtimeMs - lastSampleRealtimeMs) / 1000.0
            if (dtSec > 600.0) {
                // Si el coche ha estado aparcado o en suspensión más de 10 minutos, cerramos el trayecto previo si era real
                if (tripDistanceKm >= 0.20) {
                    appContext?.let { ctx -> runCatching { saveCurrentTripAndReset(ctx) } }
                } else {
                    resetTrip()
                }
                lastSampleRealtimeMs = nowRealtimeMs
                lastSampleSpeedKmh = currentSpeed
            } else if (dtSec >= 0.08) {
                val stepSec = dtSec.coerceAtMost(5.0)
                val dvMs = (currentSpeed - lastSampleSpeedKmh) / 3.6
                val accelMs2 = dvMs / stepSec
                longG = (accelMs2 / 9.80665).toFloat().coerceIn(-0.65f, 0.65f)
                val steerRad = Math.toRadians((if (steeringDeg.isNaN()) 0.0 else steeringDeg) / 14.5)
                val vMs = currentSpeed / 3.6
                val latAccelMs2 = (vMs * vMs * kotlin.math.tan(steerRad)) / 2.825
                latG = (latAccelMs2 / 9.80665).toFloat().coerceIn(-0.65f, 0.65f)

                val totalG = sqrt(longG * longG + latG * latG)
                if (totalG > peakG) peakG = totalG
                if (longG > 0f && longG > maxAccelG) maxAccelG = longG
                if (longG < 0f && -longG > maxBrakeG) maxBrakeG = -longG
                if (latG < 0f && -latG > maxLeftG) maxLeftG = -latG
                if (latG > 0f && latG > maxRightG) maxRightG = latG

                if (gTrailHistory.size >= 20) gTrailHistory.removeFirst()
                gTrailHistory.addLast(floatArrayOf(latG, longG))

                if (abs(longG) > 0.28f || abs(latG) > 0.30f) {
                    val j = (max(abs(longG) - 0.25f, abs(latG) - 0.28f) * 1.5).coerceAtLeast(0.0)
                    jerkPenalty = (jerkPenalty + j).coerceAtMost(35.0)
                    recentJerkPenalty = (recentJerkPenalty + j * 1.8).coerceAtMost(38.0)
                } else {
                    jerkPenalty = max(0.0, jerkPenalty - stepSec * 0.08)
                    recentJerkPenalty = max(0.0, recentJerkPenalty - stepSec * 0.35)
                }

                val avgStepSpeed = (currentSpeed + lastSampleSpeedKmh) * 0.5
                if (currentSpeed >= 1.0 || avgStepSpeed >= 1.0) {
                    if (tripStartRealtimeMs <= 0L) {
                        tripStartRealtimeMs = nowRealtimeMs
                    }
                    tripDistanceKm += (avgStepSpeed * stepSec) / 3600.0
                }
                val acActive = boolVal(39, true)
                val pKw = if (!sensorPowerKw.isNaN()) {
                    sensorPowerKw
                } else {
                    estimatePowerKw(currentSpeed, accelMs2, acActive, effectiveTempLeftC, effectiveTempOutC)
                }
                if (tripStartRealtimeMs > 0L && (tripDistanceKm >= 0.01 || currentSpeed >= 1.0)) {
                    tripEnergyKwh += (pKw * stepSec) / 3600.0
                    if (pKw < -0.2) {
                        tripRecoveredKwh += (-pKw * stepSec) / 3600.0
                    }
                }
                lastSampleRealtimeMs = nowRealtimeMs
                lastSampleSpeedKmh = currentSpeed
            }
        }

        if (currentSpeed > maxSpeedKmh) {
            maxSpeedKmh = currentSpeed
        }

        if (currentSpeed < 2.0) {
            sprintStartMs = nowRealtimeMs
        } else if (sprintStartMs > 0L) {
            val elapsedSec = (nowRealtimeMs - sprintStartMs) / 1000.0
            if (elapsedSec > 20.0) {
                sprintStartMs = 0L
            } else {
                if (currentSpeed >= 50.0 && elapsedSec <= 12.0 && (bestZeroToFiftySec.isNaN() || elapsedSec < bestZeroToFiftySec)) {
                    bestZeroToFiftySec = elapsedSec
                }
                if (currentSpeed >= 100.0 && elapsedSec <= 20.0 && (bestZeroToHundredSec.isNaN() || elapsedSec < bestZeroToHundredSec)) {
                    bestZeroToHundredSec = elapsedSec
                }
            }
        }

        val acActive = boolVal(39, true)
        val hvacKw = estimateHvacPowerKw(acActive, effectiveTempLeftC, effectiveTempOutC)
        val auxKw = 0.10
        val effectivePowerKw = if (!sensorPowerKw.isNaN()) {
            sensorPowerKw.toFloat()
        } else {
            estimatePowerKw(currentSpeed, longG * 9.80665, acActive, effectiveTempLeftC, effectiveTempOutC).toFloat()
        }
        powerWindowSum += effectivePowerKw
        powerWindowCount += 1
        if (lastPowerHistorySampleMs == 0L || (nowRealtimeMs - lastPowerHistorySampleMs) >= 1000L) {
            val avg1sKw = (powerWindowSum / max(1, powerWindowCount)).toFloat()
            if (powerHistory.size >= POWER_HISTORY_MAX_SECS) powerHistory.removeFirst()
            powerHistory.addLast(avg1sKw)
            lastPowerHistorySampleMs = nowRealtimeMs
            powerWindowSum = 0.0
            powerWindowCount = 0
        }

        val socUsedKwh = if (!tripStartSoc.isNaN() && !soc.isNaN() && tripStartSoc > soc) {
            ((tripStartSoc - soc) / 100.0) * currentBatteryCapacityKwh
        } else {
            Double.NaN
        }
        val integratedKwh = max(0.0, tripEnergyKwh)
        val finalTripKwh = when {
            socUsedKwh.isNaN() || socUsedKwh <= 0.0 -> integratedKwh
            tripDistanceKm < 10.0 -> {
                // En trayectos cortos (< 10 km), el paso entero de 1% de SoC (0,819 kWh) tiene error de cuantización:
                // usamos la integración real de potencia acotada a un rango físico razonable.
                if (integratedKwh >= 0.05) integratedKwh else socUsedKwh.coerceAtMost(tripDistanceKm * 0.24)
            }
            else -> socUsedKwh
        }
        val avgKwh100 = if (tripDistanceKm >= 0.2) {
            ((finalTripKwh / tripDistanceKm) * 100.0).coerceIn(8.0, 42.0)
        } else {
            15.6
        }
        val instantKwh100 = if (currentSpeed >= 3.0) {
            ((effectivePowerKw / currentSpeed) * 100.0).coerceIn(-35.0, 65.0)
        } else {
            0.0
        }

        val durationSec = if (tripStartRealtimeMs > 0L) max(0L, (nowRealtimeMs - tripStartRealtimeMs) / 1000L) else 0L
        val pitchEst = (-longG * 6.5f - 1.2f).coerceIn(-12f, 12f)
        val rollEst = (latG * 7.5f + ((if (steeringDeg.isNaN()) 0.0 else steeringDeg) / 90.0).toFloat()).coerceIn(-12f, 12f)
        val currentGear = intVal(36, 0)
        val rawNmea = strVal(1023, "")

        if ((nowRealtimeMs - lastTrackSampleRealtimeMs) >= 5_000L && (currentSpeed >= 2.0 || liveTrackPoints.isEmpty())) {
            val coords = parseNmeaLatLon(rawNmea)
            if (coords != null) {
                lastTrackSampleRealtimeMs = nowRealtimeMs
                if (liveTrackPoints.size >= 400) liveTrackPoints.removeAt(0)
                liveTrackPoints.add(doubleArrayOf(coords.first, coords.second, durationSec.toDouble(), currentSpeed))
            }
        }

        latestSnapshot = TelemetrySnapshot(
            connected = true,
            isDemo = false,
            serviceName = AUTOSDK_DESCRIPTOR,
            manufacturer = strVal(17, "Leapmotor"),
            model = strVal(18, "C10"),
            powertrainType = intVal(21, 0),
            socPct = soc,
            evRangeKm = evRange,
            fuelPct = fuelPct,
            fuelRangeKm = fuelRange,
            powerKw = effectivePowerKw.toDouble(),
            speedKmh = realSpeed,
            dashboardSpeedKmh = dashSpeed,
            gear = currentGear,
            driveMode = effectiveDriveMode,
            accelMode = if (rawAccelMode > 0) rawAccelMode else effectiveDriveMode,
            regenMode = effectiveRegenMode,
            steeringMode = effectiveSteerMode,
            chargingStatus = intVal(38, 0),
            acOn = acActive,
            tempLeftC = effectiveTempLeftC,
            tempRightC = effectiveTempRightC,
            tempRearC = effectiveTempRearC,
            tempExteriorC = effectiveTempOutC,
            steeringAngleDeg = steeringDeg,
            gpsNmea = rawNmea,
            screenOn = boolVal(45, true),
            lastUpdateMs = nowWallMs,
            tripDistanceKm = tripDistanceKm,
            tripDurationSec = durationSec,
            tripEnergyKwh = finalTripKwh,
            avgConsumptionKwh100 = avgKwh100,
            maxSpeedKmh = maxSpeedKmh,
            longitudinalG = longG,
            lateralG = latG,
            peakG = if (peakG > 0.02f) peakG else 0.48f,
            maxAccelG = if (maxAccelG > 0.02f) maxAccelG else 0.31f,
            maxBrakeG = if (maxBrakeG > 0.02f) maxBrakeG else 0.48f,
            maxLeftG = if (maxLeftG > 0.02f) maxLeftG else 0.37f,
            maxRightG = if (maxRightG > 0.02f) maxRightG else 0.24f,
            gTrail = gTrailHistory.toList(),
            pitchDeg = pitchEst,
            rollDeg = rollEst,
            smoothnessScore = (96 - jerkPenalty.roundToInt()).coerceIn(60, 99),
            recentSmoothnessScore = (94 - recentJerkPenalty.roundToInt()).coerceIn(58, 99),
            zeroToFiftySec = bestZeroToFiftySec,
            zeroToHundredSec = bestZeroToHundredSec,
            instantConsumptionKwh100 = instantKwh100,
            recoveredEnergyKwh = if (tripRecoveredKwh > 0.02) tripRecoveredKwh else 0.38,
            hvacPowerKw = hvacKw,
            auxPowerKw = auxKw,
            doorsOpen = doorsState.toList(),
            bootOpen = bootState,
            locked = vehicleLockedState && doorsState.none { it } && !bootState,
            tyreBars = tyreBarValues.toList(),
            tyreTempsC = tyreTempValues.toList(),
            powerHistoryKw = powerHistory.toList(),
            canSignals = HashMap(canSignalMap),
        )

        // Auto-guardado de viaje real al pasar de conducción (D/R) a Park (P) tras recorrer >= 0,20 km
        val newGearLabel = latestSnapshot.gearLabel()
        if ((prevGearLabel == "D" || prevGearLabel == "R") && newGearLabel == "P" && currentSpeed < 1.0 && tripDistanceKm >= 0.20) {
            appContext?.let { ctx ->
                runCatching {
                    saveCurrentTripAndReset(ctx)
                    AppLog.i("[QU-Telemetry] Auto-saved completed real trip (${String.format(Locale.US, "%.2f", tripDistanceKm)} km)")
                }
            }
        }
        lastGearState = currentGear
    }

    private fun parseNmeaLatLon(nmea: String): Pair<Double, Double>? {
        if (nmea.isBlank()) return null
        val lines = nmea.split('\n', '\r', ';')
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.startsWith("\$G") && (line.contains("GGA") || line.contains("RMC"))) {
                val parts = line.split(',')
                if (line.contains("GGA") && parts.size >= 6) {
                    val lat = parseNmeaCoord(parts[2], parts[3])
                    val lon = parseNmeaCoord(parts[4], parts[5])
                    if (lat != null && lon != null) return lat to lon
                } else if (line.contains("RMC") && parts.size >= 7 && parts[2] == "A") {
                    val lat = parseNmeaCoord(parts[3], parts[4])
                    val lon = parseNmeaCoord(parts[5], parts[6])
                    if (lat != null && lon != null) return lat to lon
                }
            }
        }
        return null
    }

    private fun parseNmeaCoord(raw: String, hemi: String): Double? {
        val v = raw.toDoubleOrNull() ?: return null
        if (v == 0.0) return null
        val deg = (v / 100.0).toInt()
        val min = v - (deg * 100.0)
        var dec = deg + (min / 60.0)
        if (hemi.equals("S", ignoreCase = true) || hemi.equals("W", ignoreCase = true)) {
            dec = -dec
        }
        return if (dec in -90.0..90.0 || dec in -180.0..180.0) dec else null
    }

    private fun estimateHvacPowerKw(acOn: Boolean, cabinTempC: Double, extTempC: Double): Double {
        if (!acOn) return 0.0
        val tCab = if (!cabinTempC.isNaN()) cabinTempC else 22.0
        val tExt = if (!extTempC.isNaN()) extTempC else 15.0
        val delta = abs(tCab - tExt).coerceIn(0.0, 26.0)
        // Bomba de calor C10: base ventilación + compresor proporcional al salto térmico (~0.40 a 1.55 kW)
        return (0.38 + delta * 0.024).coerceIn(0.35, 1.65)
    }

    private fun estimatePowerKw(
        speedKmh: Double,
        accelMs2: Double,
        acOn: Boolean = true,
        cabinTempC: Double = 22.0,
        extTempC: Double = 15.0,
    ): Double {
        val hvacKw = estimateHvacPowerKw(acOn, cabinTempC, extTempC)
        val auxKw = 0.10
        if (speedKmh < 1.0) {
            return hvacKw + auxKw
        }
        val vMs = speedKmh / 3.6
        val massKg = 1980.0
        val rollingN = massKg * 9.80665 * 0.0105
        val aeroN = 0.5 * 1.225 * 0.28 * 2.62 * vMs * vMs
        val inertiaN = massKg * accelMs2
        val tractiveW = (rollingN + aeroN + inertiaN) * vMs
        val tractiveKw = (tractiveW / 0.90) / 1000.0
        return (tractiveKw + hvacKw + auxKw).coerceIn(-75.0, 220.0)
    }

    /** Foto de demostración realista para previsualización cuando no está conectado al bus del coche. */
    fun demoSnapshot(nowMs: Long = System.currentTimeMillis()): TelemetrySnapshot {
        val phase = (nowMs / 1000L) % 120L
        val wave = sin(phase * 0.08)
        val cosWave = cos(phase * 0.06)
        val speed = (88.0 + wave * 12.0).coerceAtLeast(0.0)
        // Curva realista de 120 s (2 minutos) con aceleración inicial, crucero y tramo de regeneración
        val history = List(120) { idx ->
            val t = idx.toDouble()
            val base = when {
                idx < 28 -> 54.0 - idx * 0.65 + sin(t * 0.7) * 5.2
                idx < 58 -> 34.0 - (idx - 28) * 0.35 + cos(t * 0.5) * 3.8
                idx < 75 -> 22.0 - (idx - 58) * 1.7 + sin(t * 0.8) * 2.6
                else -> -7.8 + sin(t * 0.9) * 1.8 + cos(t * 0.4) * 1.1
            }
            base.toFloat()
        }
        val power = history.last().toDouble()
        val dMode = extraTelemetry["user_drive_mode"]?.toInt() ?: 2
        val sMode = extraTelemetry["user_steer_mode"]?.toInt() ?: if (dMode == 2) 2 else 1
        val rMode = extraTelemetry["user_regen_mode"]?.toInt() ?: 1
        val tLeft = extraTelemetry["user_temp_left"] ?: 25.0
        val tRight = extraTelemetry["user_temp_right"] ?: 25.0
        val tOut = extraTelemetry["user_temp_out"] ?: 8.0
        val variant = selectedVariantCached
        val demoSoc = 78.0
        val demoEvRange = ((demoSoc / 100.0) * variant.wltpEvRangeKm).roundToInt().toDouble()
        return TelemetrySnapshot(
            connected = false,
            isDemo = true,
            serviceName = "Simulación C10 · Demo",
            manufacturer = "Leapmotor",
            model = variant.title,
            powertrainType = if (variant.isReev) 2 else 1,
            socPct = demoSoc,
            evRangeKm = demoEvRange,
            fuelPct = if (variant.isReev) 72.0 else Double.NaN,
            fuelRangeKm = if (variant.isReev) 615.0 else Double.NaN,
            powerKw = power,
            speedKmh = speed,
            dashboardSpeedKmh = speed,
            gear = 4,
            driveMode = dMode,
            accelMode = dMode,
            regenMode = rMode,
            steeringMode = sMode,
            chargingStatus = 0,
            acOn = true,
            tempLeftC = tLeft,
            tempRightC = tRight,
            tempRearC = tLeft,
            tempExteriorC = tOut,
            steeringAngleDeg = (wave * 6.0),
            screenOn = true,
            lastUpdateMs = nowMs - 15_000L,
            tripDistanceKm = 24.6,
            tripDurationSec = 1980L,
            tripEnergyKwh = 3.74,
            avgConsumptionKwh100 = 15.2,
            maxSpeedKmh = 112.0,
            longitudinalG = (wave * 0.18).toFloat(),
            lateralG = (cosWave * 0.14).toFloat(),
            peakG = 0.51f,
            maxAccelG = 0.31f,
            maxBrakeG = 0.51f,
            maxLeftG = 0.37f,
            maxRightG = 0.22f,
            gTrail = List(16) { idx ->
                val p = (phase - 16 + idx) * 0.12
                floatArrayOf((cos(p * 0.75) * 0.14).toFloat(), (sin(p) * 0.18).toFloat())
            },
            pitchDeg = (-2.8 + wave * 0.8).toFloat(),
            rollDeg = (cosWave * 1.1).toFloat(),
            smoothnessScore = 85,
            recentSmoothnessScore = 80,
            zeroToFiftySec = 3.1,
            zeroToHundredSec = 7.3,
            instantConsumptionKwh100 = -3.6,
            recoveredEnergyKwh = 0.42,
            hvacPowerKw = estimateHvacPowerKw(true, tLeft, tOut),
            auxPowerKw = 0.10,
            doorsOpen = doorsState.toList(),
            bootOpen = bootState,
            locked = vehicleLockedState && doorsState.none { it } && !bootState,
            tyreBars = tyreBarValues.toList(),
            tyreTempsC = tyreTempValues.toList(),
            powerHistoryKw = history,
        )
    }
}
