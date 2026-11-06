package com.headqlink.unit.extended

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendedLayoutTest {

    /** El C10: ventana inmersiva de 1920x882, AA negociado a 1920x1080 con margin_height 198. */
    private val c10Full = AaMargins(0, 99, 0, 99)

    private fun c10(dpi: Int = 200, viewW: Int = 1920, percent: Int = 30) = ExtendedLayout.compute(
        ExtendedLayout.Input(viewW = viewW, negW = 1920, negH = 1080, full = c10Full, dpi = dpi, percent = percent),
    )

    @Test
    fun `C10 a 200 dpi panel de 576 y AA de 1344`() {
        val r = c10()
        assertTrue(r.available)
        assertEquals(576, r.panelPx)
        assertEquals(1344, r.aaVideoW)
        assertEquals(576, r.extraR)
        assertEquals(1920, r.uiW)
        assertEquals(882, r.uiH)
        // max(800 dp a 200 dpi = 1000, 1,15 · 882 = 1014,3 -> 1015)
        assertEquals(1015, r.minAaW)
        assertEquals(AaMargins(0, 99, 576, 99), r.extended)
        assertEquals(AaMargins(0, 99, 576, 99), r.margins(extendedMode = true))
        assertEquals("L0 T99 R576 B99", r.extended.toString())
    }

    @Test
    fun `C10 a 320 dpi el panel baja a 320`() {
        val r = c10(dpi = 320)
        assertTrue(r.available)
        assertEquals(1600, r.minAaW)
        assertEquals(320, r.panelPx)
        assertEquals(1600, r.aaVideoW)
        assertEquals(320, r.extraR)
        assertEquals(AaMargins(0, 99, 320, 99), r.extended)
    }

    @Test
    fun `ventana de 2560 panel de 768 y extraR sigue en 576`() {
        // 2560x1176: AA sigue en 1920x1080 con 198 de margen vertical (escala 4/3)
        val r = c10(viewW = 2560)
        assertTrue(r.available)
        assertEquals(768, r.panelPx)
        assertEquals(1344, r.aaVideoW)
        assertEquals(576, r.extraR)
        assertEquals(AaMargins(0, 99, 576, 99), r.extended)
    }

    @Test
    fun `1024x600 sin sitio a 200 dpi y al minimo a 160 dpi`() {
        // AUTO negocia 1280x720 en una pantalla de 1024x600: márgenes simétricos de 128 y 60
        val full = AaMargins(128, 60, 128, 60)
        fun at(dpi: Int) = ExtendedLayout.compute(
            ExtendedLayout.Input(viewW = 1024, negW = 1280, negH = 720, full = full, dpi = dpi),
        )

        val narrow = at(200)
        assertFalse(narrow.available)
        assertEquals(0, narrow.panelPx)
        assertEquals(0, narrow.extraR)
        assertEquals(1024, narrow.aaVideoW)
        assertEquals(307, narrow.wantedPanelPx)
        assertEquals(full, narrow.extended)
        assertEquals(full, narrow.margins(extendedMode = true))

        val minimum = at(160)
        assertTrue(minimum.available)
        assertEquals(800, minimum.minAaW)
        assertEquals(224, minimum.panelPx)
        assertEquals(800, minimum.aaVideoW)
        assertEquals(224, minimum.extraR)
        assertEquals(AaMargins(128, 60, 352, 60), minimum.extended)
    }

    @Test
    fun `el porcentaje se acota entre 20 y 40`() {
        assertEquals(20, ExtendedLayout.clampPercent(5))
        assertEquals(25, ExtendedLayout.clampPercent(25))
        assertEquals(40, ExtendedLayout.clampPercent(90))

        val low = c10(percent = 10)
        assertEquals(384, low.panelPx)
        assertEquals(1536, low.aaVideoW)
        assertEquals(384, low.extraR)

        val high = c10(percent = 60)
        assertEquals(768, high.panelPx)
        assertEquals(1152, high.aaVideoW)
        assertEquals(768, high.extraR)
    }

    @Test
    fun `en Completa los margenes son los simetricos`() {
        val r = c10()
        assertEquals(c10Full, r.full)
        assertEquals(c10Full, r.margins(extendedMode = false))
        assertEquals(r.full.left + r.full.right, 0)
        assertEquals(r.full.top, r.full.bottom)

        val odd = ExtendedLayout.compute(
            ExtendedLayout.Input(viewW = 1920, negW = 1920, negH = 1080, full = AaMargins(0, 98, 0, 99), dpi = 200),
        )
        assertEquals(AaMargins(0, 98, 0, 99), odd.margins(extendedMode = false))
        assertEquals(AaMargins(0, 98, 576, 99), odd.margins(extendedMode = true))
    }

    @Test
    fun `sin medidas no hay Extendido`() {
        val r = c10(viewW = 0)
        assertFalse(r.available)
        assertEquals(0, r.panelPx)
        assertEquals(c10Full, r.margins(extendedMode = true))
    }

    @Test
    fun `modo Pantalla Partida C10 respeta minAaW de 1015 y da 905 de panel en 1920 y 1206 en 2560`() {
        val r1920 = ExtendedLayout.compute(
            ExtendedLayout.Input(
                viewW = 1920,
                negW = 1920,
                negH = 1080,
                full = c10Full,
                dpi = 200,
                splitMode = true,
            ),
        )
        assertTrue(r1920.available)
        assertEquals(1015, r1920.minAaW)
        assertEquals(905, r1920.panelPx)
        assertEquals(1015, r1920.aaVideoW)
        assertEquals(905, r1920.extraR)
        assertEquals(AaMargins(0, 99, 905, 99), r1920.extended)

        val r2560 = ExtendedLayout.compute(
            ExtendedLayout.Input(
                viewW = 2560,
                negW = 1920,
                negH = 1080,
                full = c10Full,
                dpi = 200,
                splitMode = true,
            ),
        )
        assertTrue(r2560.available)
        assertEquals(1206, r2560.panelPx)
        assertEquals(1015, r2560.aaVideoW)
        assertEquals(905, r2560.extraR)
        assertEquals(AaMargins(0, 99, 905, 99), r2560.extended)
    }
}
