package com.headqlink.unit.extended

import android.content.Context
import kotlin.math.roundToLong

/**
 * Datos inventados para las capturas (DEMO): nunca usa la red y siempre devuelve demo = true, así que el
 * panel enseña la ficha DEMO. Las edades son fijas respecto a la hora de lectura («hace 3 min» no avanza),
 * para que las capturas salgan siempre iguales. El reloj del panel sí es el de verdad.
 *
 * Estados (extra qu_demo_estado en debug, ver ExtendedUi.applyIntent):
 * - ok: 78 % · 359 km · hace 3 min.
 * - viejo: lo mismo, hace 2 h (la edad en ámbar).
 * - bajo: 6 % · 28 km · hace 3 min (el anillo en rojo).
 * - sin_cuenta: sin dato, «Sin cuenta de Leapmotor».
 * - caducada: 78 % · 359 km · hace 3 h, con el aviso de sesión caducada.
 * - error: 78 % · 359 km · hace 8 min, con «reintento en 2 min».
 *
 * Los km siguen la regla de la DEMO de Qonecta+ (DemoMode): % × 4,6, redondeado.
 */
class DemoPanelData(state: String) : PanelDataSource {

    /** El estado pedido; uno desconocido se queda en ok. */
    val state: String = state.trim().lowercase().takeIf { it in STATES } ?: STATE_OK

    override fun start(context: Context) = Unit

    override fun stop() = Unit

    override fun read(nowMs: Long): PanelCarData = when (state) {
        STATE_OLD -> withData(PanelCarData.Kind.OK, SOC_OK, nowMs - OLD_AGE_MS, nowMs)
        STATE_LOW -> withData(PanelCarData.Kind.OK, SOC_LOW, nowMs - FRESH_AGE_MS, nowMs)
        STATE_NO_ACCOUNT -> PanelCarData(PanelCarData.Kind.NO_ACCOUNT, demo = true)
        STATE_EXPIRED -> withData(PanelCarData.Kind.EXPIRED, SOC_OK, nowMs - EXPIRED_AGE_MS, nowMs)
        STATE_ERROR -> withData(PanelCarData.Kind.ERROR, SOC_OK, nowMs - ERROR_AGE_MS, nowMs)
            .copy(nextTryMs = nowMs + ERROR_RETRY_MS)
        else -> withData(PanelCarData.Kind.OK, SOC_OK, nowMs - FRESH_AGE_MS, nowMs)
    }

    private fun withData(kind: PanelCarData.Kind, soc: Double, dataTimeMs: Long, nowMs: Long) = PanelCarData(
        kind = kind,
        socPct = soc,
        rangeKm = rangeFor(soc),
        dataTimeMs = minOf(dataTimeMs, nowMs),
        demo = true,
    )

    override fun toString(): String = "DemoPanelData($state)"

    companion object {
        const val STATE_OK = "ok"
        const val STATE_OLD = "viejo"
        const val STATE_LOW = "bajo"
        const val STATE_NO_ACCOUNT = "sin_cuenta"
        const val STATE_EXPIRED = "caducada"
        const val STATE_ERROR = "error"

        /** Los estados, en el orden de las capturas del plan. */
        val STATES = listOf(STATE_OK, STATE_OLD, STATE_LOW, STATE_NO_ACCOUNT, STATE_EXPIRED, STATE_ERROR)

        const val SOC_OK = 78.0
        const val SOC_LOW = 6.0

        /** km por cada % en la DEMO de Qonecta+ (DemoMode: soc × 4,6). */
        const val KM_PER_PCT = 4.6

        const val FRESH_AGE_MS = 3 * 60_000L
        const val OLD_AGE_MS = 2 * 3_600_000L
        const val EXPIRED_AGE_MS = 3 * 3_600_000L
        const val ERROR_AGE_MS = 8 * 60_000L
        const val ERROR_RETRY_MS = 2 * 60_000L

        /** Autonomía de la DEMO: % × 4,6, redondeada a km enteros (78 % → 359 km, 6 % → 28 km). */
        fun rangeFor(soc: Double): Double = (soc * KM_PER_PCT).roundToLong().toDouble()
    }
}
