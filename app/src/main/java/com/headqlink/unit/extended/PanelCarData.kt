package com.headqlink.unit.extended

import android.content.Context

/**
 * Lo que pinta la tarjeta Batería del panel: una foto inmutable, sin Android, para que el panel no dependa
 * de la nube (el bloque B funciona antes que el C) y las pruebas del PC la puedan construir.
 *
 * Los estados son los de Qonecta+ (CarCloud.State). Puede haber dato también fuera de OK: tras un error,
 * con la sesión caducada o con la clave del servidor cambiada se enseña el último dato con un aviso.
 */
data class PanelCarData(
    val kind: Kind,
    /** % de batería; NaN si no hay dato. */
    val socPct: Double = Double.NaN,
    /** Autonomía eléctrica, km; NaN si no hay dato. */
    val rangeKm: Double = Double.NaN,
    /** Hora (de pared) del dato del coche; 0 si no hay. */
    val dataTimeMs: Long = 0L,
    /** Hora (de pared) del próximo intento tras un error; 0 si no hay. */
    val nextTryMs: Long = 0L,
    /** Datos inventados (DEMO): el panel enseña siempre la ficha DEMO. */
    val demo: Boolean = false,
) {
    /** Por qué hay o no hay datos (los mismos estados que CarCloud.State de Qonecta+). */
    enum class Kind {
        /** Dato leído bien. */
        OK,

        /** Leyendo por primera vez. */
        WAITING,

        /** Sin certificado, sin sesión o sin coche elegido. */
        NO_ACCOUNT,

        /** Configurado pero desactivado. */
        OFF,

        /** El último intento falló (red, la nube…): se reintenta más tarde; puede haber dato de antes. */
        ERROR,

        /** La sesión caducó: hay que volver a entrar. */
        EXPIRED,

        /** El servidor presenta otra clave: hay que revisarla. */
        SERVER_KEY,

        /** Android 5 o anterior (el Keystore con AES-GCM es de Android 6). */
        UNSUPPORTED,
    }

    /** Hay algo que enseñar en el anillo. */
    val hasData: Boolean
        get() = !socPct.isNaN() || !rangeKm.isNaN()

    companion object {
        /** Estados en los que tocar la tarjeta abre «Datos del coche»: hay algo que hacer en la cuenta. */
        val ACCOUNT_KINDS = setOf(Kind.NO_ACCOUNT, Kind.EXPIRED, Kind.SERVER_KEY, Kind.OFF)

        /** Estados en los que, con dato, se enseña un aviso en ámbar debajo del anillo. */
        val WARNING_KINDS = setOf(Kind.EXPIRED, Kind.SERVER_KEY, Kind.ERROR)
    }
}

/**
 * De dónde saca el panel sus datos: la DEMO ([DemoPanelData]) o, desde el bloque C, la nube de Leapmotor
 * (CloudPanelData). start y stop los llama ExtendedUi con onStart y onStop de la proyección; read lo llama
 * el panel en su tic de 1 s y debe ser barato (sin red ni disco: devuelve la última foto).
 */
interface PanelDataSource {
    fun start(context: Context)

    fun stop()

    fun read(nowMs: Long): PanelCarData
}

/**
 * Mientras no exista la nube (bloque C): siempre «Sin cuenta de Leapmotor», sin red. Es lo que verá el
 * usuario fuera de la DEMO hasta que llegue CloudPanelData.
 */
object NoAccountPanelData : PanelDataSource {
    private val DATA = PanelCarData(PanelCarData.Kind.NO_ACCOUNT)

    override fun start(context: Context) = Unit

    override fun stop() = Unit

    override fun read(nowMs: Long): PanelCarData = DATA

    override fun toString(): String = "NoAccountPanelData"
}
