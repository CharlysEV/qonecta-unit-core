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
    private val carAppVersion: String = "",
    private val carVersionCode: Int = 0,
    private val logsProvider: (Int) -> Pair<String, String> = { _ -> "qonecta-unit-logs.txt" to "" },
    private val telemetryHandler: ((BridgeMessage.TelemetrySync) -> Unit)? = null,
    private val apkPushHandler: ((BridgeMessage.ApkPushStart, (BridgeMessage) -> Unit) -> Unit)? = null,
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
                channel.send(
                    BridgeMessage.Welcome(
                        BridgeConstants.PROTOCOL_VERSION,
                        carName,
                        token,
                        newlyPaired = false,
                        carAppVersion = carAppVersion,
                        carVersionCode = carVersionCode,
                    )
                )
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
                        channel.send(
                            BridgeMessage.Welcome(
                                BridgeConstants.PROTOCOL_VERSION,
                                carName,
                                newToken,
                                newlyPaired = true,
                                carAppVersion = carAppVersion,
                                carVersionCode = carVersionCode,
                            )
                        )
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
                    is BridgeMessage.LogsRequest -> {
                        Thread({
                            val (fileName, logText) = runCatching {
                                logsProvider(msg.maxLines.coerceIn(100, 4000))
                            }.getOrElse { e ->
                                "qonecta-unit-logs-error.txt" to "Error al leer los registros de Qonecta Unit: ${e.message}"
                            }
                            runCatching {
                                channel.send(BridgeMessage.LogsResult(msg.id, fileName, logText))
                            }
                        }, "BridgeServer-Logs-${msg.id}").apply { isDaemon = true }.start()
                    }
                    is BridgeMessage.TelemetrySync -> runCatching { telemetryHandler?.invoke(msg) }
                    is BridgeMessage.ApkPushStart -> {
                        val h = apkPushHandler
                        if (h == null) {
                            runCatching {
                                channel.send(
                                    BridgeMessage.ApkPushStatus(
                                        id = msg.id,
                                        stage = "unsupported",
                                        progressPct = 0,
                                        message = "La versión instalada en el coche aún no soporta envío Push de 1 toque.",
                                        done = true,
                                        ok = false,
                                    )
                                )
                            }
                        } else {
                            Thread({
                                runCatching {
                                    h(msg) { replyMsg -> runCatching { channel.send(replyMsg) } }
                                }
                            }, "BridgeServer-ApkPush-${msg.id}").apply { isDaemon = true }.start()
                        }
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

    /** Manda al móvil el estado en vivo del coche (JSON). false si la conexión ya no vale. */
    fun sendTelemetry(json: String): Boolean = runCatching { channel.send(BridgeMessage.TelemetrySync(json)) }.isSuccess

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

    private val nextId = AtomicInteger(1)
    private val pendingLogs = ConcurrentHashMap<String, PendingLogs>()
    private val pendingApkReady = ConcurrentHashMap<String, PendingApkReady>()
    private val apkStatusListeners = ConcurrentHashMap<String, (BridgeMessage.ApkPushStatus) -> Unit>()

    private class PendingLogs {
        val latch = CountDownLatch(1)
        @Volatile var result: BridgeMessage.LogsResult? = null
    }

    private class PendingApkReady {
        val latch = CountDownLatch(1)
        @Volatile var result: BridgeMessage.ApkPushReady? = null
    }

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
        telemetryHandler: ((BridgeMessage.TelemetrySync) -> Unit)? = null,
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
                    is BridgeMessage.TelemetrySync -> runCatching { telemetryHandler?.invoke(msg) }
                    is BridgeMessage.LogsResult -> pendingLogs.remove(msg.id)?.let {
                        it.result = msg
                        it.latch.countDown()
                    }
                    is BridgeMessage.ApkPushReady -> pendingApkReady.remove(msg.id)?.let {
                        it.result = msg
                        it.latch.countDown()
                    }
                    is BridgeMessage.ApkPushStatus -> {
                        val cb = if (msg.done) apkStatusListeners.remove(msg.id) else apkStatusListeners[msg.id]
                        // Si el coche respondió directamente con error/unsupported antes de ApkPushReady, desbloquear latch
                        if (msg.done) {
                            pendingApkReady.remove(msg.id)?.latch?.countDown()
                        }
                        runCatching { cb?.invoke(msg) }
                    }
                    BridgeMessage.Ping -> channel.send(BridgeMessage.Pong)
                    else -> Unit
                }
            }
        } catch (_: IOException) {
        } finally {
            channel.close()
            pendingLogs.values.forEach { it.latch.countDown() }
            pendingLogs.clear()
            pendingApkReady.values.forEach { it.latch.countDown() }
            pendingApkReady.clear()
            apkStatusListeners.clear()
        }
    }

    /**
     * Inicia una actualización Push de 1 toque enviando [ApkPushStart] y registrando un listener de progreso.
     */
    fun startApkPush(
        fileName: String,
        sizeBytes: Long,
        sha256: String,
        versionName: String,
        versionCode: Int,
        httpUrls: List<String>,
        onStatus: (BridgeMessage.ApkPushStatus) -> Unit,
        timeoutMs: Long = 8_000L,
    ): Pair<String, BridgeMessage.ApkPushReady?> {
        val id = "ota" + nextId.getAndIncrement()
        val p = PendingApkReady()
        pendingApkReady[id] = p
        apkStatusListeners[id] = onStatus
        val sent = runCatching {
            channel.send(
                BridgeMessage.ApkPushStart(
                    id = id,
                    fileName = fileName,
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                    versionName = versionName,
                    versionCode = versionCode,
                    httpUrls = httpUrls,
                )
            )
        }.isSuccess
        if (!sent) {
            pendingApkReady.remove(id)
            apkStatusListeners.remove(id)
            return id to null
        }
        p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        pendingApkReady.remove(id)
        return id to p.result
    }

    fun unregisterApkStatusListener(id: String) {
        apkStatusListeners.remove(id)
    }

    /**
     * Solicita al coche (Qonecta Unit) el paquete de registros de diagnóstico y bloquea hasta recibirlo.
     */
    fun fetchLogs(maxLines: Int = 800, timeoutMs: Long = 20_000L): BridgeMessage.LogsResult? {
        val id = "log" + nextId.getAndIncrement()
        val p = PendingLogs()
        pendingLogs[id] = p
        val sent = runCatching { channel.send(BridgeMessage.LogsRequest(id, maxLines)) }.isSuccess
        if (!sent) {
            pendingLogs.remove(id)
            return null
        }
        p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        pendingLogs.remove(id)
        return p.result
    }

    fun ping(): Boolean = runCatching { channel.send(BridgeMessage.Ping) }.isSuccess

    /** Manda al coche un paquete JSON de sincronización (precios de energía/combustible, viajes eliminados u ocultos). */
    fun sendTelemetry(json: String): Boolean = runCatching { channel.send(BridgeMessage.TelemetrySync(json)) }.isSuccess
}
