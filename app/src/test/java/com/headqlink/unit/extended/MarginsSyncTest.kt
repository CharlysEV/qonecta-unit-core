package com.headqlink.unit.extended

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarginsSyncTest {

    /** Los del ServiceDiscovery en el C10 (simétricos) y los del Extendido. */
    private val full = AaMargins(0, 99, 0, 99)
    private val ext = AaMargins(0, 99, 576, 99)

    private fun sync(sendInFullscreen: Boolean = false) = MarginsSync(sendInFullscreen, maxTries = 4)

    private fun down(gen: Int = 1) = MarginsSync.Link(gen, transportStarted = false, videoSeen = false, baseline = null)

    private fun up(gen: Int, video: Boolean, baseline: AaMargins = full) =
        MarginsSync.Link(gen, transportStarted = true, videoSeen = video, baseline = baseline)

    private fun sent(d: MarginsSync.Decision): MarginsSync.Decision.Send {
        assertTrue("se esperaba un envío y salió $d", d is MarginsSync.Decision.Send)
        return d as MarginsSync.Decision.Send
    }

    @Test
    fun `sin transporte los margenes quedan pendientes`() {
        val s = sync()
        assertEquals(MarginsSync.Decision.Wait, s.want(ext, down()))
        assertEquals(MarginsSync.Status.PENDING_TRANSPORT, s.status)
        assertEquals(ext, s.desired)
        assertNull(s.sent)
        // Sin envío en curso, una respuesta suelta de AA no confirma nada
        assertFalse(s.onAck())
    }

    @Test
    fun `se envian con el primer frame y no antes`() {
        val s = sync()
        s.want(ext, down())
        assertEquals(MarginsSync.Decision.Wait, s.update(up(1, video = false)))
        assertEquals(MarginsSync.Status.PENDING_VIDEO, s.status)

        val d = sent(s.update(up(1, video = true)))
        assertEquals(ext, d.margins)
        assertEquals(0, d.attempt)
        assertEquals(MarginsSync.Status.SENT, s.status)

        assertTrue(s.onAck())
        assertEquals(MarginsSync.Status.CONFIRMED, s.status)
    }

    @Test
    fun `no se repiten dentro de la misma generacion`() {
        val s = sync()
        s.want(ext, down())
        sent(s.update(up(1, video = true)))
        // En curso: ni otra petición igual ni el primer frame (o la red de seguridad) los repiten
        assertEquals(MarginsSync.Decision.None, s.want(ext, up(1, video = true)))
        assertEquals(MarginsSync.Decision.None, s.update(up(1, video = true)))
        assertTrue(s.onAck())
        // Confirmados: tampoco (p. ej. la actividad se rehace y vuelve a pedirlos)
        assertEquals(MarginsSync.Decision.None, s.want(ext, up(1, video = true)))
        assertEquals(MarginsSync.Status.CONFIRMED, s.status)
    }

    @Test
    fun `se reenvian con una generacion nueva`() {
        val s = sync()
        s.want(ext, down())
        sent(s.update(up(1, video = true)))
        assertTrue(s.onAck())

        // AA se desconecta y vuelve: otra generación, que arranca con los del ServiceDiscovery
        assertEquals(MarginsSync.Decision.Wait, s.update(down(1)))
        assertEquals(MarginsSync.Status.PENDING_TRANSPORT, s.status)
        assertEquals(MarginsSync.Decision.Wait, s.update(up(2, video = false)))
        assertEquals(MarginsSync.Status.PENDING_VIDEO, s.status)
        val d = sent(s.update(up(2, video = true)))
        assertEquals(ext, d.margins)
        assertEquals(2, s.generation)
    }

    @Test
    fun `tras 4 intentos se rinde`() {
        val s = sync()
        s.want(ext, down())
        var d = sent(s.update(up(1, video = true)))
        for (attempt in 1..3) {
            d = sent(s.onTimeout(d.seq, up(1, video = true)))
            assertEquals(attempt, d.attempt)
            assertEquals(ext, d.margins)
        }
        val last = s.onTimeout(d.seq, up(1, video = true))
        assertEquals(MarginsSync.Decision.GiveUp(ext), last)
        assertEquals(MarginsSync.Status.GAVE_UP, s.status)

        // Rendido: ni el temporizador ni otra petición igual vuelven a mandarlos en esta generación
        assertEquals(MarginsSync.Decision.None, s.onTimeout(d.seq, up(1, video = true)))
        assertEquals(MarginsSync.Decision.None, s.want(ext, up(1, video = true)))
        // Una respuesta tardía sí cuenta
        assertTrue(s.onAck())
        assertEquals(MarginsSync.Status.CONFIRMED, s.status)
    }

    @Test
    fun `en Completa no se envia nada al conectar`() {
        val s = sync()
        assertEquals(MarginsSync.Decision.Wait, s.want(full, down()))
        assertEquals(MarginsSync.Decision.Wait, s.update(up(1, video = false)))
        assertEquals(MarginsSync.Decision.None, s.update(up(1, video = true)))
        assertEquals(MarginsSync.Status.AS_NEGOTIATED, s.status)
        assertNull(s.sent)
        // Tampoco con una generación nueva
        s.update(down(1))
        assertEquals(MarginsSync.Decision.None, s.update(up(2, video = true)))
        assertEquals(MarginsSync.Status.AS_NEGOTIATED, s.status)
    }

    @Test
    fun `al volver de Extendido en la misma sesion se mandan los simetricos`() {
        val s = sync()
        s.want(full, down())
        s.update(up(1, video = true))
        assertEquals(ext, sent(s.want(ext, up(1, video = true))).margins)
        assertTrue(s.onAck())
        assertEquals(full, sent(s.want(full, up(1, video = true))).margins)
        assertTrue(s.onAck())
        assertEquals(MarginsSync.Status.CONFIRMED, s.status)
        assertEquals(MarginsSync.Decision.None, s.want(full, up(1, video = true)))
    }

    @Test
    fun `con SEND_IN_FULLSCREEN tambien se mandan en Completa al conectar`() {
        val s = sync(sendInFullscreen = true)
        s.want(full, down())
        assertEquals(full, sent(s.update(up(1, video = true))).margins)
    }

    @Test
    fun `un cambio de superficie en Completa si se manda`() {
        val s = sync()
        s.want(full, down())
        s.update(up(1, video = true))
        val corrected = AaMargins(0, 97, 0, 97)
        assertEquals(corrected, sent(s.want(corrected, up(1, video = true))).margins)
    }

    @Test
    fun `un temporizador viejo no repite`() {
        val s = sync()
        s.want(full, down())
        s.update(up(1, video = true))
        val first = sent(s.want(ext, up(1, video = true)))
        val second = sent(s.want(full, up(1, video = true)))
        assertEquals(MarginsSync.Decision.None, s.onTimeout(first.seq, up(1, video = true)))
        assertEquals(full, sent(s.onTimeout(second.seq, up(1, video = true))).margins)
    }

    @Test
    fun `la desconexion anula los reintentos`() {
        val s = sync()
        s.want(ext, down())
        val d = sent(s.update(up(1, video = true)))
        s.onDisconnected()
        assertEquals(MarginsSync.Status.PENDING_TRANSPORT, s.status)
        // El temporizador de aquel envío ya no cuenta, y una respuesta suelta tampoco confirma nada
        assertEquals(MarginsSync.Decision.None, s.onTimeout(d.seq, down(1)))
        assertFalse(s.onAck())
    }
}
