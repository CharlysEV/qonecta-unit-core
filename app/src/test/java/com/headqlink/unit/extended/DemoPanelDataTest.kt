package com.headqlink.unit.extended

import com.headqlink.unit.extended.PanelCarData.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoPanelDataTest {

    private val now = 1_800_000_000_000L

    private fun read(state: String, at: Long = now) = DemoPanelData(state).read(at)

    @Test
    fun `todos los estados son DEMO`() {
        for (state in DemoPanelData.STATES) {
            assertTrue("$state debe ir con demo = true", read(state).demo)
        }
    }

    @Test
    fun `ok 78 por ciento 359 km hace 3 min`() {
        val d = read("ok")
        assertEquals(Kind.OK, d.kind)
        assertEquals(78.0, d.socPct, 0.0)
        assertEquals(359.0, d.rangeKm, 0.0)
        assertEquals(now - 3 * MIN, d.dataTimeMs)
        assertEquals(0L, d.nextTryMs)
        assertTrue(d.hasData)
        assertEquals(PanelFormat.ACCENT, PanelFormat.levelColor(d.socPct))
        assertEquals(PanelFormat.ACCENT, PanelFormat.ageColor(PanelFormat.ageMs(d.dataTimeMs, now)))
    }

    @Test
    fun `viejo los mismos datos hace 2 h con la edad en ambar`() {
        val d = read("viejo")
        assertEquals(Kind.OK, d.kind)
        assertEquals(78.0, d.socPct, 0.0)
        assertEquals(359.0, d.rangeKm, 0.0)
        val age = PanelFormat.ageMs(d.dataTimeMs, now)
        assertEquals(2 * HOUR, age)
        assertEquals(PanelFormat.Age(PanelFormat.AgeUnit.HOURS, 2), PanelFormat.age(age!!))
        assertEquals(PanelFormat.AMBER, PanelFormat.ageColor(age))
    }

    @Test
    fun `bajo 6 por ciento 28 km con el anillo en rojo`() {
        val d = read("bajo")
        assertEquals(Kind.OK, d.kind)
        assertEquals(6.0, d.socPct, 0.0)
        assertEquals(28.0, d.rangeKm, 0.0)
        assertEquals(PanelFormat.RED, PanelFormat.levelColor(d.socPct))
    }

    @Test
    fun `sin cuenta no tiene dato`() {
        val d = read("sin_cuenta")
        assertEquals(Kind.NO_ACCOUNT, d.kind)
        assertFalse(d.hasData)
        assertTrue(d.socPct.isNaN())
        assertTrue(d.rangeKm.isNaN())
        assertEquals(0L, d.dataTimeMs)
        assertTrue(d.kind in PanelCarData.ACCOUNT_KINDS)
    }

    @Test
    fun `caducada con dato y aviso`() {
        val d = read("caducada")
        assertEquals(Kind.EXPIRED, d.kind)
        assertTrue(d.hasData)
        assertEquals(78.0, d.socPct, 0.0)
        assertEquals(359.0, d.rangeKm, 0.0)
        assertEquals(now - 3 * HOUR, d.dataTimeMs)
        assertTrue(d.kind in PanelCarData.WARNING_KINDS)
        assertTrue(d.kind in PanelCarData.ACCOUNT_KINDS)
    }

    @Test
    fun `error con dato y reintento en 2 min`() {
        val d = read("error")
        assertEquals(Kind.ERROR, d.kind)
        assertTrue(d.hasData)
        assertEquals(78.0, d.socPct, 0.0)
        assertEquals(now - 8 * MIN, d.dataTimeMs)
        assertEquals(2L, PanelFormat.retryMinutes(d.nextTryMs, now))
        assertTrue(d.kind in PanelCarData.WARNING_KINDS)
        assertFalse(d.kind in PanelCarData.ACCOUNT_KINDS)
    }

    @Test
    fun `las edades son fijas para que las capturas salgan iguales`() {
        for (state in DemoPanelData.STATES) {
            val a = read(state, now)
            val b = read(state, now + 7 * MIN)
            assertEquals(state, PanelFormat.ageMs(a.dataTimeMs, now), PanelFormat.ageMs(b.dataTimeMs, now + 7 * MIN))
            assertEquals(state, a.copy(dataTimeMs = 0, nextTryMs = 0), b.copy(dataTimeMs = 0, nextTryMs = 0))
        }
        assertEquals(2L, PanelFormat.retryMinutes(read("error", now + HOUR).nextTryMs, now + HOUR))
    }

    @Test
    fun `km por la regla de la DEMO de Qonecta+`() {
        assertEquals(359.0, DemoPanelData.rangeFor(78.0), 0.0)
        assertEquals(28.0, DemoPanelData.rangeFor(6.0), 0.0)
        assertEquals(460.0, DemoPanelData.rangeFor(100.0), 0.0)
        assertEquals(0.0, DemoPanelData.rangeFor(0.0), 0.0)
    }

    @Test
    fun `estado desconocido o con mayusculas`() {
        assertEquals("ok", DemoPanelData("raro").state)
        assertEquals("ok", DemoPanelData("").state)
        assertEquals("bajo", DemoPanelData(" BAJO ").state)
        assertEquals(Kind.OK, read("raro").kind)
        assertEquals(78.0, read("raro").socPct, 0.0)
    }

    @Test
    fun `los seis estados del plan`() {
        assertEquals(listOf("ok", "viejo", "bajo", "sin_cuenta", "caducada", "error"), DemoPanelData.STATES)
    }

    @Test
    fun `sin cuenta mientras no haya nube`() {
        val d = NoAccountPanelData.read(now)
        assertEquals(Kind.NO_ACCOUNT, d.kind)
        assertFalse(d.demo)
        assertFalse(d.hasData)
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 3_600_000L
    }
}
