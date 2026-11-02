package com.headqlink.unit.extended

import com.headqlink.unit.extended.PanelFormat.Age
import com.headqlink.unit.extended.PanelFormat.AgeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class PanelFormatTest {

    // ------------------------------------------------------------------ color del nivel

    @Test
    fun `nivel en acento desde el 20 por ciento`() {
        assertEquals(PanelFormat.ACCENT, PanelFormat.levelColor(100.0))
        assertEquals(PanelFormat.ACCENT, PanelFormat.levelColor(78.0))
        assertEquals(PanelFormat.ACCENT, PanelFormat.levelColor(20.0))
    }

    @Test
    fun `nivel en ambar del 8 al 20 por ciento`() {
        assertEquals(PanelFormat.AMBER, PanelFormat.levelColor(19.9))
        assertEquals(PanelFormat.AMBER, PanelFormat.levelColor(12.0))
        assertEquals(PanelFormat.AMBER, PanelFormat.levelColor(8.0))
    }

    @Test
    fun `nivel en rojo por debajo del 8 por ciento`() {
        assertEquals(PanelFormat.RED, PanelFormat.levelColor(7.9))
        assertEquals(PanelFormat.RED, PanelFormat.levelColor(6.0))
        assertEquals(PanelFormat.RED, PanelFormat.levelColor(0.0))
    }

    @Test
    fun `nivel sin dato en tenue`() {
        assertEquals(PanelFormat.MUTED, PanelFormat.levelColor(Double.NaN))
    }

    // ------------------------------------------------------------------ color de la edad

    @Test
    fun `edad en acento por debajo de 15 min y en ambar a partir de 15 min`() {
        assertEquals(PanelFormat.ACCENT, PanelFormat.ageColor(0L))
        assertEquals(PanelFormat.ACCENT, PanelFormat.ageColor(3 * MIN))
        assertEquals(PanelFormat.ACCENT, PanelFormat.ageColor(15 * MIN - 1))
        assertEquals(PanelFormat.AMBER, PanelFormat.ageColor(15 * MIN))
        assertEquals(PanelFormat.AMBER, PanelFormat.ageColor(2 * HOUR))
    }

    @Test
    fun `edad desconocida en tenue`() {
        assertEquals(PanelFormat.FAINT, PanelFormat.ageColor(null))
    }

    // ------------------------------------------------------------------ antigüedad

    @Test
    fun `ahora por debajo de 10 s`() {
        assertEquals(Age(AgeUnit.NOW, 0), PanelFormat.age(0L))
        assertEquals(Age(AgeUnit.NOW, 0), PanelFormat.age(9_999L))
        assertEquals(Age(AgeUnit.NOW, 0), PanelFormat.age(-5_000L))
    }

    @Test
    fun `segundos minutos horas y dias`() {
        assertEquals(Age(AgeUnit.SECONDS, 10), PanelFormat.age(10_000L))
        assertEquals(Age(AgeUnit.SECONDS, 40), PanelFormat.age(40_500L))
        assertEquals(Age(AgeUnit.SECONDS, 59), PanelFormat.age(59_999L))
        assertEquals(Age(AgeUnit.MINUTES, 1), PanelFormat.age(MIN))
        assertEquals(Age(AgeUnit.MINUTES, 3), PanelFormat.age(3 * MIN + 59_000L))
        assertEquals(Age(AgeUnit.MINUTES, 59), PanelFormat.age(HOUR - 1))
        assertEquals(Age(AgeUnit.HOURS, 1), PanelFormat.age(HOUR))
        assertEquals(Age(AgeUnit.HOURS, 2), PanelFormat.age(2 * HOUR))
        assertEquals(Age(AgeUnit.HOURS, 47), PanelFormat.age(48 * HOUR - 1))
        assertEquals(Age(AgeUnit.DAYS, 2), PanelFormat.age(48 * HOUR))
        assertEquals(Age(AgeUnit.DAYS, 3), PanelFormat.age(80 * HOUR))
    }

    @Test
    fun `edad del dato desde su hora`() {
        val now = 1_800_000_000_000L
        assertEquals(3 * MIN, PanelFormat.ageMs(now - 3 * MIN, now))
        assertEquals(0L, PanelFormat.ageMs(now, now))
        // Un poco en el futuro (relojes desacompasados): de ahora
        assertEquals(0L, PanelFormat.ageMs(now + 30_000L, now))
        assertEquals(0L, PanelFormat.ageMs(now + PanelFormat.FUTURE_TOLERANCE_MS, now))
    }

    @Test
    fun `sin hora del dato o muy en el futuro la edad no se sabe`() {
        val now = 1_800_000_000_000L
        assertNull(PanelFormat.ageMs(0L, now))
        assertNull(PanelFormat.ageMs(-1L, now))
        assertNull(PanelFormat.ageMs(now + PanelFormat.FUTURE_TOLERANCE_MS + 1, now))
        assertNull(PanelFormat.ageMs(now + 2 * HOUR, now))
    }

    // ------------------------------------------------------------------ cifras

    @Test
    fun `cifras enteras y raya si no hay valor`() {
        assertEquals("78", PanelFormat.number(78.0, Locale.ROOT))
        assertEquals("359", PanelFormat.number(358.8, Locale.ROOT))
        assertEquals("28", PanelFormat.number(27.6, Locale.ROOT))
        assertEquals("0", PanelFormat.number(0.0, Locale.ROOT))
        assertEquals("—", PanelFormat.number(Double.NaN, Locale.ROOT))
        assertEquals("—", PanelFormat.number(Double.POSITIVE_INFINITY, Locale.ROOT))
        assertEquals(PanelFormat.NONE, PanelFormat.number(Double.NaN))
    }

    @Test
    fun `fraccion del anillo acotada`() {
        assertEquals(0.78f, PanelFormat.ringFraction(78.0), 1e-6f)
        assertEquals(0f, PanelFormat.ringFraction(Double.NaN), 0f)
        assertEquals(0f, PanelFormat.ringFraction(-3.0), 0f)
        assertEquals(1f, PanelFormat.ringFraction(104.0), 0f)
    }

    @Test
    fun `minutos hasta el reintento redondeados y al menos 1`() {
        val now = 1_800_000_000_000L
        assertEquals(2L, PanelFormat.retryMinutes(now + 2 * MIN, now))
        assertEquals(5L, PanelFormat.retryMinutes(now + 4 * MIN + 40_000L, now))
        assertEquals(1L, PanelFormat.retryMinutes(now + 10_000L, now))
        assertEquals(1L, PanelFormat.retryMinutes(now - MIN, now))
        assertEquals(1L, PanelFormat.retryMinutes(0L, now))
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 3_600_000L
    }
}
