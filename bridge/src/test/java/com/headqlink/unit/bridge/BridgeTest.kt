package com.headqlink.unit.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class BridgeTest {

    private class MemStore : TokenStore {
        var map: Map<String, String> = emptyMap()
        override fun loadTokens() = map
        override fun saveTokens(tokens: Map<String, String>) { map = tokens }
    }

    private val madrid = NavPoint(40.416775, -3.703790, "Madrid")
    private val c1 = NavPoint(40.9, -4.1, "Cargador 1")
    private val c2 = NavPoint(41.5, -4.7, "Cargador 2")
    private val c3 = NavPoint(42.0, -5.2, "Cargador 3")
    private val c4 = NavPoint(42.6, -5.6, "Cargador 4")

    // --- Mensajes ---------------------------------------------------------------------------

    @Test
    fun codec_roundTripsEveryMessage() {
        val msgs = listOf(
            BridgeMessage.Hello(1, "SM-S938B", "0.1.0", token = "abc"),
            BridgeMessage.Hello(1, "SM-S938B", "0.1.0", pairCode = "012345"),
            BridgeMessage.Welcome(1, "Leapmotor C10", "tok", newlyPaired = true),
            BridgeMessage.Denied(BridgeMessage.Denied.BAD_CODE),
            BridgeMessage.Navigate("n1", madrid, listOf(c1, c2)),
            BridgeMessage.NavResult("n1", ok = false, error = BridgeMessage.NavResult.ERR_NO_MAPS, viaSent = 2),
            BridgeMessage.HttpFetch("h1", "https://api.open-meteo.com/v1/elevation?latitude=40.4", "GET", timeoutMs = 8000),
            BridgeMessage.HttpResult("h1", status = 200, body = "{\"elevation\":[655.0]}"),
            BridgeMessage.Ping,
            BridgeMessage.Pong,
        )
        msgs.forEach { assertEquals(it, BridgeCodec.decode(it.encode())) }
    }

    @Test
    fun codec_unknownTypeIsTolerated() {
        assertEquals(BridgeMessage.Unknown("telemetry"), BridgeCodec.decode("{\"t\":\"telemetry\",\"soc\":78}"))
    }

    @Test
    fun codec_namesWithAccentsAndQuotesSurvive() {
        val p = NavPoint(43.0, -8.0, "Área de servicio \"O Pino\" · A Coruña")
        val back = BridgeCodec.decode(BridgeMessage.Navigate("x", p).encode()) as BridgeMessage.Navigate
        assertEquals(p.name, back.dest.name)
    }

    // --- Enlace de Google Maps -------------------------------------------------------------

    @Test
    fun maps_withoutStopsUsesGoogleNavigation() {
        val b = MapsRoute.build(madrid, emptyList())
        assertEquals("google.navigation:q=40.416775,-3.703790&mode=d", b.uri)
        assertTrue(b.reachesDestination)
        assertTrue(b.waypoints.isEmpty())
    }

    @Test
    fun maps_withStopsUsesDirUrlLikeQonectaPlus() {
        val b = MapsRoute.build(madrid, listOf(c1, c2))
        assertTrue(b.uri.startsWith("https://www.google.com/maps/dir/?api=1&destination=40.416775,-3.703790&travelmode=driving&dir_action=navigate&waypoints="))
        assertTrue(b.uri.endsWith("40.900000%2C-4.100000%7C41.500000%2C-4.700000"))
        assertEquals(listOf(c1, c2), b.waypoints)
        assertTrue(b.reachesDestination)
    }

    @Test
    fun maps_moreThanThreeStopsNavigatesToTheFourth() {
        val b = MapsRoute.build(madrid, listOf(c1, c2, c3, c4))
        assertEquals(listOf(c1, c2, c3), b.waypoints)
        assertEquals(c4, b.target)
        assertFalse(b.reachesDestination)
    }

    @Test
    fun maps_invalidStopsAreDropped() {
        val b = MapsRoute.build(madrid, listOf(NavPoint(0.0, 0.0), c1, NavPoint(95.0, 0.0)))
        assertEquals(listOf(c1), b.waypoints)
    }

    // --- Emparejamiento ---------------------------------------------------------------------

    @Test
    fun pairing_codeIsSingleUseAndGivesAPersistentToken() {
        val store = MemStore()
        val reg = PairingRegistry(store)
        val code = reg.startPairing()
        assertEquals(6, code.length)
        val token = reg.consumePairCode(code, "Mi móvil")
        assertNotNull(token)
        assertTrue(reg.isValidToken(token!!))
        assertEquals("Mi móvil", store.map[token])
        assertNull("El código no se puede reutilizar", reg.consumePairCode(code, "Otro"))
    }

    @Test
    fun pairing_wrongOrExpiredCodeIsRejected() {
        var now = 1_000L
        val reg = PairingRegistry(MemStore(), clock = { now })
        val code = reg.startPairing()
        assertNull(reg.consumePairCode(if (code == "000000") "111111" else "000000", "x"))
        now += BridgeConstants.PAIR_CODE_VALIDITY_MS + 1
        assertFalse(reg.isPairing())
        assertNull(reg.consumePairCode(code, "x"))
    }

    // --- Conversación completa por un socket real -----------------------------------------

    @Test
    fun endToEnd_pairThenNavigateThenReconnectWithToken() {
        val reg = PairingRegistry(MemStore())
        val server = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
        val serverSession = AtomicReference<BridgeServerSession?>()
        val accepted = java.util.concurrent.CountDownLatch(1)

        fun acceptOne() = thread(isDaemon = true) {
            val s = server.accept()
            val session = BridgeServerSession(LineChannel(s.getInputStream(), s.getOutputStream()) { s.close() }, "tcp", reg, "Leapmotor C10")
            if (session.handshake()) {
                serverSession.set(session)
                accepted.countDown()
                session.runLoop()
            } else {
                accepted.countDown()
            }
        }

        // 1) Emparejar con el código
        val code = reg.startPairing()
        acceptOne()
        val sock = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val client = BridgeClientSession(LineChannel(sock.getInputStream(), sock.getOutputStream()) { sock.close() }, "tcp")
        val welcome = client.handshake(BridgeMessage.Hello(1, "SM-S938B", "0.1.0", pairCode = code)) as BridgeMessage.Welcome
        assertTrue(welcome.newlyPaired)
        val received = AtomicReference<BridgeMessage.Navigate?>()
        thread(isDaemon = true) {
            client.runLoop { nav ->
                received.set(nav)
                BridgeMessage.NavResult(nav.id, ok = true, viaSent = nav.via.size)
            }
        }
        accepted.await()

        // 2) El coche manda la ruta y el móvil contesta
        val result = serverSession.get()!!.navigate(madrid, listOf(c1, c2), timeoutMs = 5_000)
        assertNotNull(result)
        assertTrue(result!!.ok)
        assertEquals(2, result.viaSent)
        assertEquals(madrid, received.get()!!.dest)
        client.channel.close()

        // 3) Reconectar más tarde solo con el token
        acceptOne()
        val sock2 = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val client2 = BridgeClientSession(LineChannel(sock2.getInputStream(), sock2.getOutputStream()) { sock2.close() }, "tcp")
        val again = client2.handshake(BridgeMessage.Hello(1, "SM-S938B", "0.1.0", token = welcome.token))
        assertTrue(again is BridgeMessage.Welcome && !again.newlyPaired)
        client2.channel.close()

        // 4) Un token inventado se rechaza
        acceptOne()
        val sock3 = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val client3 = BridgeClientSession(LineChannel(sock3.getInputStream(), sock3.getOutputStream()) { sock3.close() }, "tcp")
        val denied = client3.handshake(BridgeMessage.Hello(1, "Intruso", "0.1.0", token = "inventado"))
        assertEquals(BridgeMessage.Denied(BridgeMessage.Denied.BAD_TOKEN), denied)
        server.close()
    }

    @Test
    fun navigate_returnsNullWhenThePhoneNeverAnswers() {
        val reg = PairingRegistry(MemStore())
        val code = reg.startPairing()
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val sessionRef = AtomicReference<BridgeServerSession>()
        val ready = java.util.concurrent.CountDownLatch(1)
        thread(isDaemon = true) {
            val s = server.accept()
            val session = BridgeServerSession(LineChannel(s.getInputStream(), s.getOutputStream()) { s.close() }, "tcp", reg, "C10")
            session.handshake()
            sessionRef.set(session)
            ready.countDown()
            session.runLoop()
        }
        val sock = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val client = BridgeClientSession(LineChannel(sock.getInputStream(), sock.getOutputStream()) { sock.close() }, "tcp")
        client.handshake(BridgeMessage.Hello(1, "m", "0", pairCode = code))
        ready.await()
        // El móvil no lee: no hay respuesta
        assertNull(sessionRef.get().navigate(madrid, emptyList(), timeoutMs = 300))
        sock.close()
        server.close()
    }
}
