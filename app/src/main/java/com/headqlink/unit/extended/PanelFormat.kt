package com.headqlink.unit.extended

import android.content.Context
import android.content.res.Resources
import android.text.format.DateUtils
import com.andrerinas.openheadunit.R
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Formatos y colores del panel. Todo es puro (lo prueban los tests del PC) salvo las tres funciones del
 * final, que leen textos de recursos o el formato de hora del sistema.
 *
 * Los colores son los de la paleta «Eléctrico» (hql_colors.xml) como enteros: el panel los usa por código
 * y así las pruebas no necesitan recursos.
 */
object PanelFormat {
    // Paleta (hql_colors.xml)
    const val BG = 0xFF0A0E14.toInt()
    const val SURFACE = 0xFF111821.toInt()
    const val SURFACE_HI = 0xFF17202B.toInt()
    const val SURFACE_TOP = 0xFF1E2935.toInt()
    const val OUTLINE = 0xFF243140.toInt()
    const val EDGE = 0xFF2E4256.toInt()
    const val TEXT = 0xFFEAF2F7.toInt()
    const val DIM = 0xFFA3B3C2.toInt()
    const val FAINT = 0xFF8296A9.toInt()
    const val MUTED = 0xFF5B6B7C.toInt()
    const val ACCENT = 0xFF00E5C7.toInt()
    const val AMBER = 0xFFFFB547.toInt()
    const val RED = 0xFFFF6B7A.toInt()

    /** Por debajo de este %, el nivel en ámbar (Qonecta+, CarStatusTab.levelColor). */
    const val LOW_PCT = 20.0

    /** Por debajo de este %, en rojo. */
    const val CRITICAL_PCT = 8.0

    /** Dato viejo: a partir de 15 min la edad va en ámbar (el coche duerme y la nube da lo último que supo). */
    const val OLD_MS = 15 * 60_000L

    /** Menos que esto: «ahora». */
    const val NOW_MS = 10_000L

    /**
     * Un dato «del futuro» hasta este margen se toma como de ahora (relojes del coche y de la unidad algo
     * desacompasados); más allá, la edad no es fiable y se enseña «—».
     */
    const val FUTURE_TOLERANCE_MS = 60_000L

    /** Sin valor. */
    const val NONE = "—"

    /** Color del nivel de batería: acento desde el 20 %, ámbar desde el 8 %, rojo por debajo; tenue sin dato. */
    fun levelColor(pct: Double): Int = when {
        pct.isNaN() -> MUTED
        pct >= LOW_PCT -> ACCENT
        pct >= CRITICAL_PCT -> AMBER
        else -> RED
    }

    /** Color de la edad del dato: ámbar a partir de 15 min; tenue si no se sabe. */
    fun ageColor(ageMs: Long?): Int = when {
        ageMs == null -> FAINT
        ageMs >= OLD_MS -> AMBER
        else -> ACCENT
    }

    /**
     * Edad del dato en ms, o null si no hay hora del dato o cae en el futuro más allá del margen (reloj de
     * la unidad mal puesto: mejor «—» que una edad inventada).
     */
    fun ageMs(dataTimeMs: Long, nowMs: Long): Long? {
        if (dataTimeMs <= 0L) return null
        val age = nowMs - dataTimeMs
        if (age < -FUTURE_TOLERANCE_MS) return null
        return max(0L, age)
    }

    /** Unidad de la antigüedad. */
    enum class AgeUnit { NOW, SECONDS, MINUTES, HOURS, DAYS }

    /** Antigüedad redondeada hacia abajo: «ahora», «hace 40 s», «hace 3 min», «hace 2 h», «hace 1 d». */
    data class Age(val unit: AgeUnit, val value: Long)

    /** Como CarCloud.ago de Qonecta+, con «ahora» por debajo de 10 s y días a partir de 48 h. */
    fun age(ageMs: Long): Age {
        val ms = max(0L, ageMs)
        if (ms < NOW_MS) return Age(AgeUnit.NOW, 0)
        val s = ms / 1000
        if (s < 60) return Age(AgeUnit.SECONDS, s)
        val m = s / 60
        if (m < 60) return Age(AgeUnit.MINUTES, m)
        val h = m / 60
        if (h < 48) return Age(AgeUnit.HOURS, h)
        return Age(AgeUnit.DAYS, h / 24)
    }

    /** Cifra entera (78, 359) o «—» si es NaN o infinita. */
    fun number(v: Double, locale: Locale = Locale.getDefault()): String {
        if (v.isNaN() || v.isInfinite()) return NONE
        return String.format(locale, "%d", v.roundToLong())
    }

    /** Minutos que faltan para el reintento, redondeados y al menos 1 (Qonecta+, CarStatusTab.problem). */
    fun retryMinutes(nextTryMs: Long, nowMs: Long): Long =
        max(1L, ((nextTryMs - nowMs) / 60_000.0).roundToLong())

    /** Fracción del anillo (0-1) para el %; 0 sin dato. */
    fun ringFraction(pct: Double): Float =
        if (pct.isNaN()) 0f else (pct / 100.0).coerceIn(0.0, 1.0).toFloat()

    // ------------------------------------------------------------------ con Android

    /** «hace 3 min» en el idioma de la app; «—» si la edad no se sabe. */
    fun ageText(res: Resources, ageMs: Long?): String {
        if (ageMs == null) return NONE
        val a = age(ageMs)
        return when (a.unit) {
            AgeUnit.NOW -> res.getString(R.string.qu_ago_now)
            AgeUnit.SECONDS -> res.getString(R.string.qu_ago_s, a.value)
            AgeUnit.MINUTES -> res.getString(R.string.qu_ago_min, a.value)
            AgeUnit.HOURS -> res.getString(R.string.qu_ago_h, a.value)
            AgeUnit.DAYS -> res.getString(R.string.qu_ago_d, a.value)
        }
    }

    /** La hora con el formato del sistema (respeta las 24 h). */
    fun timeText(context: Context, nowMs: Long): String =
        android.text.format.DateFormat.getTimeFormat(context).format(Date(nowMs))

    /** «domingo, 2 de noviembre»: día de la semana y fecha, sin año. */
    fun dateText(context: Context, nowMs: Long): String = DateUtils.formatDateTime(
        context,
        nowMs,
        DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR,
    )
}
