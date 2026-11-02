package com.headqlink.unit.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarProbeTest {

    @Test
    fun `nivel de proteccion sin banderas`() {
        assertEquals("normal", CarProbe.protectionName(0))
        assertEquals("dangerous", CarProbe.protectionName(1))
        assertEquals("signature", CarProbe.protectionName(2))
        assertEquals("signatureOrSystem", CarProbe.protectionName(3))
        assertEquals("internal", CarProbe.protectionName(4))
    }

    @Test
    fun `nivel de proteccion con banderas`() {
        assertEquals("signature + privileged", CarProbe.protectionName(2 or 0x10))
        assertEquals("signature + privileged + preinstalled", CarProbe.protectionName(2 or 0x10 or 0x400))
        assertEquals("normal + appop", CarProbe.protectionName(0x40))
    }

    @Test
    fun `solo se conecta a servicios de Leapmotor`() {
        assertTrue(CarProbe.TARGETS.all { it.pkg.startsWith("com.leapmotor.") && it.action.startsWith(it.pkg) })
    }
}
