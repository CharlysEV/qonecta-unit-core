package com.headqlink.unit.bridge

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sesión del lado del COCHE con un móvil ya conectado (por TCP o Bluetooth).
 * [handshake] y [runLoop] bloquean: se llaman desde el hilo de esa conexión.
 */
class BridgeServerSession(
    val channel: LineChannel,
    val transport: String,
    private val auth: BridgeAuthenticator,
    private val carName: String,
) {
    @Volatile
    var deviceName: String = ""
        private set

    @Volatile
    var lastSeenMs: Long = System.currentTimeMillis()
        private set

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val pendingHttp = ConcurrentHashMap<String, PendingHttp>()

    private class Pending {
        val latch = CountDownLatch(1)
        @Volatile var result: BridgeMessage.NavResult? = null
    }

    private class PendingHttp {
        val latch = CountDownLatch(1)
        @Volatile var result: BridgeMessage.HttpResult? = null
    }

    /** Lee el `hello` del móvil y lo acepta o rechaza. Devuelve `true` si queda emparejado y conectado. */
    @Throws(IOException::class)
    fun handshake(): Boolean {
        val line = channel.readLine() ?: return false
        val hello = runCatching { BridgeCodec.decode(line) }.getOrNull() as? BridgeMessage.Hello
        if (hello == null) {
            deny(BridgeMessage.Denied.BAD_HELLO)
            return false
        }
        if (hello.version != BridgeConstants.PROTOCOL_VERSION) {
            deny(BridgeMessage.Denied.BAD_VERSION)
            return false
        }
        deviceName = hello.deviceName
        val token = hello.token
        val code = hello.pairCode
        return when {
            token != null && auth.isValidToken(token) -> {
                channel.send(BridgeMessage.Welcome(BridgeConstants.PROTOCOL_VERSION, carName, token, newlyPaired = false))
                true
            }
            code != null -> {
                if (!auth.isPairing()) {
                    deny(BridgeMessage.Denied.NOT_PAIRING)
                    false
                } else {
                    val newToken = auth.consumePairCode(code, hello.deviceName)
                    if (newToken == null) {
                        deny(BridgeMessage.Denied.BAD_CODE)
                        false
                    } else {
                        channel.send(BridgeMessage.Welcome(BridgeConstants.PROTOCOL_VERSION, carName, newToken, newlyPaired = true))
                        true
                    }
                }
            }
            else -> {
                deny(BridgeMessage.Denied.BAD_TOKEN)
                false
            }
        }
    }

    private fun deny(reason: String) {
        runCatching { channel.send(BridgeMessage.Denied(reason)) }
        channel.close()
    }

    /** Atiende los mensajes del móvil hasta que se corte la conexión. */
    fun runLoop() {
        try {
            while (!channel.isClosed) {
                val line = channel.readLine() ?: break
                lastSeenMs = System.currentTimeMillis()
                when (val msg = runCatching { BridgeCodec.decode(line) }.getOrNull() ?: continue) {
                    is BridgeMessage.NavResult -> pending.remove(msg.id)?.let {
                        it.result = msg
                        it.latch.countDown()
                    }
                    is BridgeMessage.HttpResult -> pendingHttp.remove(msg.id)?.let {
                        it.result = msg
                        it.latch.countDown()
                    }
                    BridgeMessage.Ping -> channel.send(BridgeMessage.Pong)
                    else -> Unit
                }
            }
        } catch (_: IOException) {
        } finally {
            channel.close()
            pending.values.forEach { it.latch.countDown() }
            pending.clear()
            pendingHttp.values.forEach { it.latch.countDown() }
            pendingHttp.clear()
        }
    }

    fun ping(): Boolean = runCatching { channel.send(BridgeMessage.Ping) }.isSuccess

    /**
     * Pide al móvil que abra Google Maps. Bloquea hasta la respuesta o [timeoutMs].
     * @return la respuesta, o `null` si no llegó (conexión caída o sin respuesta a tiempo).
     */
    fun navigate(dest: NavPoint, via: List<NavPoint>, timeoutMs: Long = BridgeConstants.NAV_RESULT_TIMEOUT_MS): BridgeMessage.NavResult? {
        val id = "n" + nextId.getAndIncrement()
        val p = Pending()
        pending[id] = p
        val sent = runCatching { channel.send(BridgeMessage.Navigate(id, dest, via)) }.isSuccess
        if (!sent) {
            pending.remove(id)
            channel.close()
            return null
        }
        p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        pending.remove(id)
        return p.result
    }

    /**
     * Pide al móvil que realice una consulta HTTP/HTTPS con sus datos móviles y devuelva el cuerpo.
     */
    fun httpFetch(
        url: String,
        method: String = "GET",
        body: String? = null,
        contentType: String? = null,
        timeoutMs: Int = 12_000,
    ): BridgeMessage.HttpResult? {
        val id = "h" + nextId.getAndIncrement()
        val p = PendingHttp()
        pendingHttp[id] = p
        val sent = runCatching {
            channel.send(BridgeMessage.HttpFetch(id, url, method, body, contentType, timeoutMs))
        }.isSuccess
        if (!sent) {
            pendingHttp.remove(id)
            channel.close()
            return null
        }
        p.latch.await((timeoutMs + 3_000).toLong(), TimeUnit.MILLISECONDS)
        pendingHttp.remove(id)
        return p.result
    }
}

/**
 * Sesión del lado del MÓVIL. [handler] decide qué hacer con cada orden de navegación y devuelve el resultado.
 */
class BridgeClientSession(
    val channel: LineChannel,
    val transport: String,
) {
    /** Última vez que llegó algo del coche (para detectar un Bluetooth muerto, que no tiene tiempo de espera). */
    @Volatile
    var lastSeenMs: Long = System.currentTimeMillis()
        private set

    /** Envía el `hello` y espera la respuesta del coche: [BridgeMessage.Welcome] o [BridgeMessage.Denied]. */
    @Throws(IOException::class)
    fun handshake(hello: BridgeMessage.Hello): BridgeMessage {
        channel.send(hello)
        val line = channel.readLine() ?: throw IOException("El coche cerró la conexión")
        lastSeenMs = System.currentTimeMillis()
        return when (val msg = BridgeCodec.decode(line)) {
            is BridgeMessage.Welcome, is BridgeMessage.Denied -> msg
            else -> throw IOException("Respuesta inesperada del coche: ${msg::class.simpleName}")
        }
    }

    fun runLoop(handler: (BridgeMessage.Navigate) -> BridgeMessage.NavResult) {
        runLoop(handler, null)
    }

    fun runLoop(
        handler: (BridgeMessage.Navigate) -> BridgeMessage.NavResult,
        httpHandler: ((BridgeMessage.HttpFetch) -> BridgeMessage.HttpResult)?,
    ) {
        try {
            while (!channel.isClosed) {
                val line = channel.readLine() ?: break
                lastSeenMs = System.currentTimeMillis()
                when (val msg = runCatching { BridgeCodec.decode(line) }.getOrNull() ?: continue) {
                    is BridgeMessage.Navigate -> {
                        val result = runCatching { handler(msg) }.getOrElse {
                            BridgeMessage.NavResult(msg.id, ok = false, error = BridgeMessage.NavResult.ERR_BAD_REQUEST)
                        }
                        channel.send(result)
                    }
                    is BridgeMessage.HttpFetch -> {
                        val h = httpHandler
                        if (h == null) {
                            channel.send(BridgeMessage.HttpResult(msg.id, status = 501, error = "unsupported"))
                        } else {
                            Thread({
                                val res = runCatching { h(msg) }.getOrElse { e ->
                                    BridgeMessage.HttpResult(msg.id, status = 0, error = e.message ?: "error")
                                }
                                runCatching { channel.send(res) }
                            }, "BridgeClient-Http-${msg.id}").apply { isDaemon = true }.start()
                        }
                    }
                    BridgeMessage.Ping -> channel.send(BridgeMessage.Pong)
                    else -> Unit
                }
            }
        } catch (_: IOException) {
        } finally {
            channel.close()
        }
    }

    fun ping(): Boolean = runCatching { channel.send(BridgeMessage.Ping) }.isSuccess
}
