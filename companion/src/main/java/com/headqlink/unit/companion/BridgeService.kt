package com.headqlink.unit.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.headqlink.unit.bridge.BridgeClientSession
import com.headqlink.unit.bridge.BridgeConstants
import com.headqlink.unit.bridge.BridgeMessage
import kotlin.concurrent.thread

/**
 * Servicio en primer plano que busca el coche y mantiene la conexión con Qonecta Unit.
 * Lo arranca la pantalla del Companion o, solo, el sistema cuando el coche aparece por Bluetooth
 * ([CarPresenceService]).
 */
class BridgeService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_PAIR = "pair"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_CODE = "code"
        private const val CHANNEL = "conexion_coche"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) = launch(ctx, Intent(ctx, BridgeService::class.java).setAction(ACTION_START))

        fun pair(ctx: Context, code: String) =
            launch(ctx, Intent(ctx, BridgeService::class.java).setAction(ACTION_PAIR).putExtra(EXTRA_CODE, code))

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_STOP)) }
        }

        private fun launch(ctx: Context, i: Intent) {
            runCatching { ctx.startForegroundService(i) }
                .onFailure { e -> BridgeState.update { it.copy(detail = "Android no dejó arrancar el servicio: ${e.message}") } }
        }
    }

    private lateinit var prefs: CompanionPrefs
    private lateinit var link: CarLink

    @Volatile private var running = false
    @Volatile private var pendingPairCode: String? = null
    @Volatile private var session: BridgeClientSession? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = CompanionPrefs(this)
        link = CarLink(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Conexión con el coche", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (!goForeground("Buscando el coche…")) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAIR -> {
                pendingPairCode = intent.getStringExtra(EXTRA_CODE)?.trim()
                prefs.enabled = true
                // Si ya había una conexión, se corta para emparejar de nuevo; si estaba esperando, se despierta.
                session?.channel?.close()
                worker?.interrupt()
            }
        }
        if (!prefs.isPaired && pendingPairCode == null) {
            BridgeState.update { it.copy(phase = BridgeState.Phase.STOPPED, detail = "Empareja con el código que muestra el coche.") }
            shutdown()
            return START_NOT_STICKY
        }
        if (worker?.isAlive != true) {
            running = true
            worker = thread(name = "qonecta-bridge", isDaemon = true) { loop() }
        }
        return START_STICKY
    }

    private fun loop() {
        var backoffMs = 4_000L
        while (running) {
            val code = pendingPairCode
            if (code == null && !prefs.isPaired) break
            BridgeState.update {
                it.copy(
                    phase = if (code != null) BridgeState.Phase.PAIRING else BridgeState.Phase.SEARCHING,
                    transport = "",
                    detail = if (code != null) "Buscando el coche para emparejar…" else "Buscando el coche (Wi-Fi de Android Auto o Bluetooth)…",
                )
            }
            notifyText(if (code != null) "Emparejando con el coche…" else "Buscando el coche…")

            val opened = link.open(prefs.carBtAddress)
            if (opened == null) {
                if (code != null && prefs.carBtAddress.isNullOrBlank()) {
                    BridgeState.update {
                        it.copy(detail = "No encuentro el coche por Wi-Fi. Elige tu coche por Bluetooth (paso 1) o conecta Android Auto inalámbrico.")
                    }
                }
                sleep(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                continue
            }
            backoffMs = 4_000L
            BridgeState.update { it.copy(phase = BridgeState.Phase.CONNECTING, transport = opened.transport, detail = "Conectando por ${opened.transport}…") }

            val client = BridgeClientSession(opened.channel, opened.transport)
            val watchdog = thread(isDaemon = true) {
                sleep(BridgeConstants.HANDSHAKE_TIMEOUT_MS.toLong())
                if (session !== client) opened.channel.close()
            }
            val reply = runCatching {
                client.handshake(
                    BridgeMessage.Hello(
                        version = BridgeConstants.PROTOCOL_VERSION,
                        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                        appVersion = appVersion(),
                        token = if (code == null) prefs.token else null,
                        pairCode = code,
                    )
                )
            }.getOrNull()

            when (reply) {
                is BridgeMessage.Welcome -> {
                    session = client
                    watchdog.interrupt()
                    prefs.token = reply.token
                    prefs.carName = reply.carName
                    pendingPairCode = null
                    val car = reply.carName.ifBlank { "el coche" }
                    BridgeState.update {
                        it.copy(
                            phase = BridgeState.Phase.CONNECTED,
                            transport = opened.transport,
                            carName = car,
                            detail = if (reply.newlyPaired) "¡Emparejado con $car! Ya puedes mandar rutas desde el planificador." else "Conectado. Las rutas del planificador se abrirán en Google Maps.",
                        )
                    }
                    notifyText("Conectado con $car por ${opened.transport} · Datos y Rutas BT activos")
                    val pinger = thread(isDaemon = true) { pingLoop(client) }
                    client.runLoop(
                        handler = { nav -> MapsLauncher.handle(this, nav) },
                        httpHandler = { req -> executeHttpFetch(req) },
                    )
                    pinger.interrupt()
                    session = null
                    BridgeState.update { it.copy(phase = BridgeState.Phase.SEARCHING, transport = "", detail = "Se cortó la conexión con el coche. Reintentando…") }
                }
                is BridgeMessage.Denied -> {
                    opened.channel.close()
                    val msg = when (reply.reason) {
                        BridgeMessage.Denied.BAD_CODE -> "Código incorrecto o caducado. Pide uno nuevo en el coche."
                        BridgeMessage.Denied.NOT_PAIRING -> "El coche no está esperando emparejar. Pulsa «Emparejar móvil» en el coche."
                        BridgeMessage.Denied.BAD_TOKEN -> "El coche ya no reconoce este móvil. Vuelve a emparejar."
                        BridgeMessage.Denied.BAD_VERSION -> "Versiones distintas: actualiza Qonecta Unit y el Companion."
                        else -> "El coche rechazó la conexión (${reply.reason})."
                    }
                    if (code != null) pendingPairCode = null
                    if (reply.reason == BridgeMessage.Denied.BAD_TOKEN) prefs.token = null
                    BridgeState.update { it.copy(phase = BridgeState.Phase.STOPPED, transport = "", detail = msg) }
                    if (!prefs.isPaired) break
                    sleep(15_000L)
                }
                else -> {
                    opened.channel.close()
                    sleep(backoffMs)
                }
            }
        }
        if (running) shutdown()
    }

    private fun pingLoop(client: BridgeClientSession) {
        try {
            while (!client.channel.isClosed) {
                Thread.sleep(BridgeConstants.PING_INTERVAL_MS)
                if (System.currentTimeMillis() - client.lastSeenMs > BridgeConstants.READ_TIMEOUT_MS) {
                    client.channel.close()
                    return
                }
                if (!client.ping()) return
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun shutdown() {
        running = false
        session?.channel?.close()
        session = null
        if (BridgeState.current.phase != BridgeState.Phase.STOPPED) {
            BridgeState.update { it.copy(phase = BridgeState.Phase.STOPPED, transport = "") }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        session?.channel?.close()
        super.onDestroy()
    }

    private fun goForeground(text: String): Boolean = runCatching {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }.onFailure { e ->
        BridgeState.update { it.copy(phase = BridgeState.Phase.STOPPED, detail = "Android no dejó arrancar la conexión en segundo plano: ${e.message}") }
    }.isSuccess

    private fun notifyText(text: String) {
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(text)) }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_route)
            .setContentTitle("Qonecta Unit Companion")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun appVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }.getOrDefault("")

    private fun executeHttpFetch(req: BridgeMessage.HttpFetch): BridgeMessage.HttpResult {
        val urlStr = req.url.trim()
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            return BridgeMessage.HttpResult(req.id, status = 400, error = "invalid_url")
        }
        return try {
            val u = java.net.URL(urlStr)
            val timeout = req.timeoutMs.coerceIn(3_000, 30_000)
            val conn = (u.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = req.method.uppercase().ifBlank { "GET" }
                connectTimeout = timeout
                readTimeout = timeout
                setRequestProperty("User-Agent", "QonectaUnit/1.0 (Leapmotor C10; Android Companion Bridge)")
                setRequestProperty("Accept", "application/json, text/plain, */*")
                if (!req.contentType.isNullOrBlank()) {
                    setRequestProperty("Content-Type", req.contentType)
                }
                if (!req.body.isNullOrEmpty() && requestMethod != "GET") {
                    doOutput = true
                }
            }
            if (!req.body.isNullOrEmpty() && conn.doOutput) {
                conn.outputStream.use { out ->
                    out.write(req.body!!.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            // Límite seguro de 1.5 MB para transporte rápido por Bluetooth RFCOMM / TCP
            val text = stream?.bufferedReader(java.nio.charset.StandardCharsets.UTF_8)?.use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var read: Int
                while (reader.read(buf).also { read = it } != -1) {
                    sb.append(buf, 0, read)
                    if (sb.length >= 1_500_000) break
                }
                sb.toString()
            }.orEmpty()
            val host = u.host.orEmpty()
            BridgeState.update {
                it.copy(
                    dataRequestsServed = it.dataRequestsServed + 1,
                    lastDataHost = host,
                )
            }
            BridgeMessage.HttpResult(
                id = req.id,
                status = code,
                body = text,
                error = if (code in 200..299) null else "HTTP $code",
            )
        } catch (e: Exception) {
            BridgeMessage.HttpResult(
                id = req.id,
                status = 0,
                body = "",
                error = e.message ?: e::class.java.simpleName,
            )
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
