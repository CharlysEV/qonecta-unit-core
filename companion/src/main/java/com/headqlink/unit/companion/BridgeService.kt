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
        private const val ACTION_RECONNECT = "reconnect"
        private const val ACTION_PAIR = "pair"
        private const val ACTION_STOP = "stop"
        private const val ACTION_FETCH_LOGS = "fetch_logs"
        private const val EXTRA_CODE = "code"
        private const val CHANNEL = "conexion_coche"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) = launch(ctx, Intent(ctx, BridgeService::class.java).setAction(ACTION_START))

        /** Reinicia en caliente el transporte activo (por ejemplo al desmarcar Wi-Fi para quedar en Solo Bluetooth). */
        fun reconnectNow(ctx: Context) = launch(ctx, Intent(ctx, BridgeService::class.java).setAction(ACTION_RECONNECT))

        fun pair(ctx: Context, code: String) =
            launch(ctx, Intent(ctx, BridgeService::class.java).setAction(ACTION_PAIR).putExtra(EXTRA_CODE, code))

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_STOP)) }
        }

        @Volatile
        private var activeClientSession: BridgeClientSession? = null

        @Volatile
        private var lastAutoLogFetchAtMs: Long = 0L

        /**
         * Envía inmediatamente al coche (Qonecta Unit) las tarifas configuradas en el móvil
         * (electricidad en casa CA, carga rápida CC, gasolina, diésel y modo REEV) así como los viajes
         * eliminados u ocultos por privacidad.
         */
        fun pushPreferencesAndTripsToCar(context: Context) {
            val appCtx = context.applicationContext
            thread(name = "qonecta-push-prefs", isDaemon = true) {
                val client = activeClientSession ?: return@thread
                if (client.channel.isClosed) return@thread
                runCatching {
                    val prefs = appCtx.getSharedPreferences("companion_vehicle_state", Context.MODE_PRIVATE)
                    val deletedArr = org.json.JSONArray()
                    prefs.getString("deleted_trip_ids_csv", "").orEmpty()
                        .split(',').mapNotNull { it.trim().toLongOrNull() }
                        .forEach { deletedArr.put(it) }
                    val hiddenArr = org.json.JSONArray()
                    prefs.getString("hidden_trip_ids_csv", "").orEmpty()
                        .split(',').mapNotNull { it.trim().toLongOrNull() }
                        .forEach { hiddenArr.put(it) }
                    val unhiddenArr = org.json.JSONArray()
                    prefs.getString("unhidden_trip_ids_csv", "").orEmpty()
                        .split(',').mapNotNull { it.trim().toLongOrNull() }
                        .forEach { unhiddenArr.put(it) }

                    val pricingObj = org.json.JSONObject()
                        .put("homeElecEurKwh", prefs.getFloat("pricing_home_elec_eur", 0.16f).toDouble())
                        .put("dcFastElecEurKwh", prefs.getFloat("pricing_dc_fast_elec_eur", 0.42f).toDouble())
                        .put("gasolineEurLiter", prefs.getFloat("pricing_gasoline_eur", 1.62f).toDouble())
                        .put("dieselEurLiter", prefs.getFloat("pricing_diesel_eur", 1.52f).toDouble())
                        .put("tariffMode", prefs.getInt("pricing_active_tariff_mode", 0))
                        .put("isReevMode", prefs.getBoolean("pricing_is_reev_mode", false))
                        .put("updatedAtMs", prefs.getLong("pricing_updated_at_ms", 0L))

                    val payload = org.json.JSONObject()
                        .put("ts", System.currentTimeMillis())
                        .put("deletedTripIds", deletedArr)
                        .put("hiddenTripIds", hiddenArr)
                        .put("unhiddenTripIds", unhiddenArr)
                        .put("replaceHiddenSet", true)
                        .put("pricing", pricingObj)
                    client.sendTelemetry(payload.toString())
                }
            }
        }

        /**
         * Solicita en segundo plano los registros de diagnóstico (`ohu.log` + `logcat`) de Qonecta Unit
         * a través del puente Bluetooth/Wi-Fi activo, sin necesidad de usar un pendrive USB.
         */
        fun requestUnitLogs(
            context: Context,
            maxLines: Int = 800,
            onResult: (Result<Pair<java.io.File, BridgeMessage.LogsResult>>) -> Unit,
        ) {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            thread(name = "qonecta-fetch-logs", isDaemon = true) {
                val client = activeClientSession
                if (client == null || client.channel.isClosed) {
                    mainHandler.post {
                        onResult(
                            Result.failure(
                                IllegalStateException("Qonecta Unit no está conectado ahora mismo. Conecta primero con el coche arriba.")
                            )
                        )
                    }
                    return@thread
                }
                val reply = runCatching { client.fetchLogs(maxLines = maxLines, timeoutMs = 20_000L) }.getOrNull()
                if (reply == null) {
                    mainHandler.post {
                        onResult(
                            Result.failure(
                                IllegalStateException("El coche no respondió a tiempo o tiene una versión anterior de Qonecta Unit.")
                            )
                        )
                    }
                    return@thread
                }
                val result = runCatching {
                    val dir = java.io.File(context.cacheDir, "unit_logs").apply { mkdirs() }
                    val safeName = reply.fileName.ifBlank { "qonecta-unit-logs.txt" }
                    val file = java.io.File(dir, safeName)
                    file.writeText(reply.logText, Charsets.UTF_8)
                    file to reply
                }
                mainHandler.post { onResult(result) }
            }
        }

        /**
         * Actualización Push de 1 toque desde Companion hacia Qonecta Unit en el coche:
         * 1. Envía la oferta [BridgeMessage.ApkPushStart] con tamaño, SHA-256 y URLs locales.
         * 2. Si el coche ya tiene el mismo SHA-256 en caché, abre `PackageInstaller` en 0 s.
         * 3. Si no, transfiere el binario por la vía más rápida disponible:
         *    - Vía 1: TCP directo (`Wi-Fi Direct / Hotspot`, ~35 MB/s, ~1.5 s).
         *    - Vía 2: HTTP Pull del coche al servidor `:8788` del móvil (si está en Hotspot).
         *    - Vía 3: Bluetooth RFCOMM binario de respaldo (~2-3 min sin Wi-Fi ni Hotspot).
         */
        fun pushApkUpdateToCar(context: Context, apkFile: java.io.File) {
            val appCtx = context.applicationContext
            if (BridgeState.current.otaInProgress) return
            val client = activeClientSession
            if (client == null || client.channel.isClosed) {
                BridgeState.update {
                    it.copy(
                        otaInProgress = false,
                        otaLastOk = false,
                        otaStatusMessage = "⚠️ El coche no está conectado todavía. Espera a que arriba ponga «● Conectado».",
                    )
                }
                return
            }
            if (!apkFile.isFile || !apkFile.canRead() || apkFile.length() <= 0L) {
                BridgeState.update {
                    it.copy(
                        otaInProgress = false,
                        otaLastOk = false,
                        otaStatusMessage = "⚠️ El archivo APK no se puede leer en el móvil.",
                    )
                }
                return
            }

            BridgeState.update {
                it.copy(
                    otaInProgress = true,
                    otaLastOk = null,
                    otaStatusMessage = "⏳ Preparando envío de ${apkFile.name} al coche…",
                )
            }

            thread(name = "qonecta-ota-push", isDaemon = true) {
                var otaId = ""
                try {
                    if (!GitHubReleaseUpdater.isHosting || GitHubReleaseUpdater.hostedApkFile?.absolutePath != apkFile.absolutePath) {
                        GitHubReleaseUpdater.startHostingApkForCar(appCtx, apkFile) {}
                        Thread.sleep(250L)
                    }

                    val sha256 = GitHubReleaseUpdater.sha256Hex(apkFile)
                    val (verName, verCode) = GitHubReleaseUpdater.extractApkVersionMeta(appCtx, apkFile)
                    val httpUrls = GitHubReleaseUpdater.listAllLocalHttpUrlsForHostedApk()
                    val totalBytes = apkFile.length()

                    val remoteReceiving = java.util.concurrent.atomic.AtomicBoolean(false)
                    val doneLatch = java.util.concurrent.CountDownLatch(1)

                    val (id, ready) = client.startApkPush(
                        fileName = apkFile.name,
                        sizeBytes = totalBytes,
                        sha256 = sha256,
                        versionName = verName,
                        versionCode = verCode,
                        httpUrls = httpUrls,
                        onStatus = { st ->
                            if (st.stage == "receiving" || st.stage == "verifying" || st.stage == "launching" || st.done) {
                                remoteReceiving.set(true)
                            }
                            BridgeState.update {
                                it.copy(
                                    otaInProgress = !st.done,
                                    otaStatusMessage = st.message.ifBlank { "Estado en el coche: ${st.stage} (${st.progressPct}%)" },
                                    otaLastOk = if (st.done) st.ok else null,
                                )
                            }
                            if (st.done) doneLatch.countDown()
                        },
                        timeoutMs = 10_000L,
                    )
                    otaId = id

                    if (ready == null) {
                        if (doneLatch.count > 0) {
                            BridgeState.update {
                                it.copy(
                                    otaInProgress = false,
                                    otaLastOk = false,
                                    otaStatusMessage = "⚠️ El coche tiene una versión anterior a v0.1.17 (aún sin recepción Push de 1 toque). Instálala esta última vez con la tarjeta «🆘 Instalar desde cero» de abajo.",
                                )
                            }
                        }
                        client.unregisterApkStatusListener(otaId)
                        return@thread
                    }

                    if (ready.alreadyCached) {
                        doneLatch.await(15, java.util.concurrent.TimeUnit.SECONDS)
                        client.unregisterApkStatusListener(otaId)
                        return@thread
                    }

                    val linkHelper = CarLink(appCtx)
                    // Vía 1: Intentar conexión TCP binaria directa hacia el coche (Wi-Fi Direct Android Auto / Hotspot)
                    val tcpSock = if (ready.tcpPort > 0) linkHelper.openOtaTcpSocket(ready.tcpPort) else null
                    if (tcpSock != null) {
                        tcpSock.use { sock ->
                            val out = sock.getOutputStream()
                            out.write("$otaId\n".toByteArray(Charsets.US_ASCII))
                            streamFileWithProgress(apkFile, out, "Wi-Fi Directo")
                            out.flush()
                            runCatching { sock.shutdownOutput() }
                            doneLatch.await(30, java.util.concurrent.TimeUnit.SECONDS)
                        }
                        client.unregisterApkStatusListener(otaId)
                        return@thread
                    }

                    // Vía 2: Esperar brevemente por si el coche está haciendo HTTP Pull desde nuestro servidor :8788
                    if (httpUrls.isNotEmpty()) {
                        var waited = 0L
                        while (waited < 3_600L && !remoteReceiving.get() && doneLatch.count > 0) {
                            Thread.sleep(250L)
                            waited += 250L
                        }
                        if (remoteReceiving.get()) {
                            doneLatch.await(150, java.util.concurrent.TimeUnit.SECONDS)
                            client.unregisterApkStatusListener(otaId)
                            return@thread
                        }
                    }

                    // Vía 3: Respaldo automático por Bluetooth RFCOMM binario (cero Wi-Fi, cero Hotspot)
                    val btAddr = CompanionPrefs(appCtx).carBtAddress
                    if (ready.rfcommAvailable && !btAddr.isNullOrBlank()) {
                        BridgeState.update {
                            it.copy(otaStatusMessage = "🔵 Sin Wi-Fi directa con el coche: enviando actualización por Bluetooth…")
                        }
                        val btSock = linkHelper.openOtaBluetoothSocket(btAddr)
                        if (btSock != null) {
                            btSock.use { sock ->
                                val out = sock.outputStream
                                out.write("$otaId\n".toByteArray(Charsets.US_ASCII))
                                streamFileWithProgress(apkFile, out, "Bluetooth")
                                out.flush()
                                // Mantener abierto el socket RFCOMM hasta que el coche confirme recepción completa
                                doneLatch.await(45, java.util.concurrent.TimeUnit.SECONDS)
                            }
                            client.unregisterApkStatusListener(otaId)
                            return@thread
                        }
                    }

                    if (!remoteReceiving.get() && doneLatch.count > 0) {
                        BridgeState.update {
                            it.copy(
                                otaInProgress = false,
                                otaLastOk = false,
                                otaStatusMessage = "⚠️ No se pudo abrir canal de datos Wi-Fi ni Bluetooth con el coche. Verifica que el Bluetooth del coche esté elegido o Android Auto inalámbrico conectado.",
                            )
                        }
                    }
                    client.unregisterApkStatusListener(otaId)
                } catch (e: Exception) {
                    if (otaId.isNotBlank()) client.unregisterApkStatusListener(otaId)
                    BridgeState.update {
                        it.copy(
                            otaInProgress = false,
                            otaLastOk = false,
                            otaStatusMessage = "⚠️ Error al enviar actualización al coche: ${e.message}",
                        )
                    }
                }
            }
        }

        private fun streamFileWithProgress(apkFile: java.io.File, out: java.io.OutputStream, channelLabel: String) {
            val total = apkFile.length().coerceAtLeast(1L)
            var sent = 0L
            var lastPct = -1
            var lastUiMs = 0L
            java.io.FileInputStream(apkFile).use { fis ->
                val buf = ByteArray(32 * 1024)
                var r: Int
                while (fis.read(buf).also { r = it } > 0) {
                    out.write(buf, 0, r)
                    sent += r
                    val pct = ((sent * 100L) / total).toInt().coerceIn(0, 99)
                    val now = System.currentTimeMillis()
                    if (pct >= lastPct + 5 && now - lastUiMs >= 250L) {
                        lastPct = pct
                        lastUiMs = now
                        val mbDone = String.format(java.util.Locale.US, "%.1f", sent / (1024.0 * 1024.0))
                        val mbTotal = String.format(java.util.Locale.US, "%.1f", total / (1024.0 * 1024.0))
                        BridgeState.update {
                            it.copy(
                                otaInProgress = true,
                                otaStatusMessage = "🚀 Enviando al coche por $channelLabel: $pct% ($mbDone / $mbTotal MB)…",
                            )
                        }
                    }
                }
            }
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
    @Volatile private var wakeUpRequested = false
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
        val currentCar = session?.let { prefs.carName ?: "el coche" }
        val fgText = if (currentCar != null) {
            "Conectado con $currentCar por ${session?.transport ?: "Bluetooth"} · Datos y Rutas BT activos"
        } else {
            "Buscando el coche…"
        }
        if (!goForeground(fgText)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> {
                if (session == null || session?.channel?.isClosed == true) {
                    wakeUpRequested = true
                    worker?.interrupt()
                }
            }
            ACTION_RECONNECT -> {
                prefs.enabled = true
                wakeUpRequested = true
                session?.channel?.close()
                worker?.interrupt()
            }
            ACTION_PAIR -> {
                pendingPairCode = intent.getStringExtra(EXTRA_CODE)?.trim()
                prefs.enabled = true
                wakeUpRequested = true
                // Si ya había una conexión, se corta para emparejar de nuevo; si estaba esperando, se despierta.
                session?.channel?.close()
                worker?.interrupt()
            }
            ACTION_FETCH_LOGS -> {
                android.util.Log.i("BridgeService", "Solicitando registros remotos a Qonecta Unit…")
                requestUnitLogs(this, maxLines = 800) { res ->
                    res.onSuccess { (file, msg) ->
                        runCatching {
                            val latest = java.io.File(cacheDir, "unit_logs/latest_unit_logs.txt")
                            file.copyTo(latest, overwrite = true)
                        }
                        android.util.Log.i("BridgeService", "Registros de Qonecta Unit guardados: ${file.absolutePath} (${msg.logText.length} chars)")
                    }.onFailure { err ->
                        android.util.Log.w("BridgeService", "Fallo al solicitar registros de Qonecta Unit: ${err.message}")
                    }
                }
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
        var backoffMs = 2_500L
        while (running) {
            if (wakeUpRequested) {
                wakeUpRequested = false
                backoffMs = 2_500L
            }
            val code = pendingPairCode
            if (code == null && !prefs.isPaired) break
            val useWifi = prefs.useWifi
            val useBt = prefs.useBluetooth
            if (code == null && !useWifi && !useBt) {
                BridgeState.update {
                    it.copy(
                        phase = BridgeState.Phase.STOPPED,
                        transport = "",
                        detail = "Marca Bluetooth o Wi-Fi en las opciones de conexión.",
                    )
                }
                shutdown()
                return
            }
            val searchDetail = when {
                code != null && !useWifi -> "Buscando el coche solo por Bluetooth para emparejar…"
                code != null -> "Buscando el coche para emparejar…"
                useBt && !useWifi -> "Modo Solo Bluetooth activo (sin Wi-Fi 5 GHz): buscando el coche…"
                !useBt && useWifi -> "Modo Solo Wi-Fi activo: buscando la red del coche…"
                else -> "Buscando el coche (Bluetooth o Wi-Fi)…"
            }
            BridgeState.update {
                it.copy(
                    phase = if (code != null) BridgeState.Phase.PAIRING else BridgeState.Phase.SEARCHING,
                    transport = "",
                    detail = searchDetail,
                )
            }
            notifyText(if (code != null) "Emparejando con el coche…" else "Buscando el coche…")

            val opened = link.open(
                carBtAddress = prefs.carBtAddress,
                useWifi = useWifi,
                useBluetooth = useBt || code != null,
            )
            if (opened == null) {
                if (!useWifi && useBt && prefs.carBtAddress.isNullOrBlank()) {
                    BridgeState.update {
                        it.copy(detail = "Modo Solo Bluetooth: elige el Bluetooth de tu C10 en «Conexión» o abre Qonecta Unit en el coche.")
                    }
                } else if (code != null && prefs.carBtAddress.isNullOrBlank()) {
                    BridgeState.update {
                        it.copy(detail = "Elige el Bluetooth de tu coche arriba o conecta el Wi-Fi del coche.")
                    }
                }
                sleep(backoffMs)
                backoffMs = (backoffMs + 1_500L).coerceAtMost(5_000L)
                continue
            }
            backoffMs = 2_500L
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
                    activeClientSession = client
                    watchdog.interrupt()
                    prefs.token = reply.token
                    prefs.carName = reply.carName
                    if (!opened.btAddress.isNullOrBlank() && prefs.carBtAddress.isNullOrBlank()) {
                        prefs.carBtAddress = opened.btAddress
                        if (!opened.btName.isNullOrBlank()) {
                            prefs.carBtName = opened.btName
                        }
                    }
                    pendingPairCode = null
                    val car = reply.carName.ifBlank { "el coche" }
                    BridgeState.update {
                        it.copy(
                            phase = BridgeState.Phase.CONNECTED,
                            transport = opened.transport,
                            carName = car,
                            carAppVersion = reply.carAppVersion,
                            carVersionCode = reply.carVersionCode,
                            detail = if (reply.newlyPaired) "¡Emparejado con $car! Ya puedes mandar rutas y actualizaciones en 1 toque." else "Conectado. Rutas, telemetría y actualizaciones en 1 toque activas.",
                        )
                    }
                    notifyText("Conectado con $car por ${opened.transport} · Datos y Rutas BT activos")
                    android.util.Log.i("BridgeService", "Conectado con $car por ${opened.transport}")
                    pushPreferencesAndTripsToCar(this)
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastAutoLogFetchAtMs > 180_000L) {
                        lastAutoLogFetchAtMs = nowMs
                        thread(name = "qonecta-auto-logs", isDaemon = true) {
                            sleep(2500L)
                            if (activeClientSession === client && !client.channel.isClosed) {
                                requestUnitLogs(this, maxLines = 400) { res ->
                                    res.onSuccess { (file, msg) ->
                                        runCatching {
                                            val latest = java.io.File(cacheDir, "unit_logs/latest_unit_logs.txt")
                                            file.copyTo(latest, overwrite = true)
                                        }
                                        android.util.Log.i("BridgeService", "Auto-sync logs de Qonecta Unit recibido: ${file.name} (${msg.logText.length} chars)")
                                    }.onFailure { err ->
                                        android.util.Log.w("BridgeService", "Auto-sync logs falló: ${err.message}")
                                    }
                                }
                            }
                        }
                    }
                    val pinger = thread(isDaemon = true) { pingLoop(client) }
                    client.runLoop(
                        handler = { nav -> MapsLauncher.handle(this, nav) },
                        httpHandler = { req -> executeHttpFetch(req) },
                        telemetryHandler = { t -> UnitTelemetry.onSync(t.payloadJson, this) },
                    )
                    pinger.interrupt()
                    if (activeClientSession === client) activeClientSession = null
                    session = null
                    UnitTelemetry.clear()
                    BridgeState.update {
                        it.copy(
                            phase = BridgeState.Phase.SEARCHING,
                            transport = "",
                            carAppVersion = "",
                            carVersionCode = 0,
                            otaInProgress = false,
                            detail = "Se cortó la conexión con el coche. Reintentando…",
                        )
                    }
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
        activeClientSession = null
        session = null
        if (BridgeState.current.phase != BridgeState.Phase.STOPPED) {
            BridgeState.update {
                it.copy(
                    phase = BridgeState.Phase.STOPPED,
                    transport = "",
                    carAppVersion = "",
                    carVersionCode = 0,
                    otaInProgress = false,
                )
            }
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
