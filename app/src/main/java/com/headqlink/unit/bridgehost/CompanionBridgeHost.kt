package com.headqlink.unit.bridgehost

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.utils.AppLog
import com.headqlink.unit.bridge.BridgeConstants
import com.headqlink.unit.bridge.BridgeMessage
import com.headqlink.unit.bridge.BridgeServerSession
import com.headqlink.unit.bridge.LineChannel
import com.headqlink.unit.bridge.MapsRoute
import com.headqlink.unit.bridge.NavPoint
import com.headqlink.unit.bridge.PairingRegistry
import com.headqlink.unit.bridge.TokenStore
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Lado del COCHE del puente con Qonecta Unit Companion (el móvil).
 *
 * Escucha a la vez por TCP [BridgeConstants.TCP_PORT] (red Wi-Fi de Android Auto inalámbrico) y por Bluetooth
 * RFCOMM (sirve también con Android Auto por cable). Solo acepta móviles emparejados con un código de 6 cifras.
 *
 * El planificador llama a [sendRoute] y el Companion abre Google Maps en el móvil; Android Auto lo proyecta.
 */
object CompanionBridgeHost {

    sealed class SendResult {
        data class Sent(val device: String, val transport: String, val viaSent: Int, val reachesDestination: Boolean) : SendResult()
        data class NeedsTap(val device: String) : SendResult()
        data class Failed(val message: String) : SendResult()
        object NotConnected : SendResult()
    }

    data class Status(
        val connectedDevices: List<Pair<String, String>>, // nombre, vía
        val pairedCount: Int,
        val pairingCode: String?,
        val tcpListening: Boolean,
        val bluetoothListening: Boolean,
        val bluetoothProblem: String?,
    ) {
        val isConnected: Boolean get() = connectedDevices.isNotEmpty()
    }

    private const val PREFS = "companion_bridge"
    private const val KEY_TOKENS = "tokens"
    private const val KEY_BT_DATA_BRIDGE = "bt_data_bridge_enabled"
    private const val CAR_NAME = "Qonecta Unit · Leapmotor C10"

    private val main = Handler(Looper.getMainLooper())
    private val sessions = CopyOnWriteArrayList<BridgeServerSession>()
    private val listeners = CopyOnWriteArrayList<(Status) -> Unit>()
    private val syncListeners = CopyOnWriteArrayList<() -> Unit>()
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private val pingExecutor = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var started = false
    @Volatile private var tcpListening = false
    @Volatile private var btListening = false
    @Volatile private var btProblem: String? = null
    @Volatile private var btEnabled = true
    @Volatile private var activeBtServer: BluetoothServerSocket? = null
    private val btWakeLock = Object()
    private lateinit var appContext: Context
    private lateinit var registry: PairingRegistry

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        btEnabled = runCatching { com.andrerinas.openheadunit.utils.Settings(appContext).btCompanionEnabled }.getOrDefault(true)
        runCatching { com.headqlink.unit.extended.LeapVehicleDataSource.start(appContext) }
        registry = PairingRegistry(PrefsTokenStore(appContext))
        thread(name = "companion-tcp", isDaemon = true) { tcpLoop() }
        thread(name = "companion-rfcomm", isDaemon = true) { bluetoothLoop() }
        pingExecutor.scheduleWithFixedDelay({ pingAll() }, BridgeConstants.PING_INTERVAL_MS, BridgeConstants.PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        // Con el móvil conectado, Qonecta Unit manda: el estado en vivo del coche al Companion cada 5 s.
        pingExecutor.scheduleWithFixedDelay({ runCatching { pushTelemetry() } }, 5_000L, 5_000L, TimeUnit.MILLISECONDS)
        AppLog.i("[Companion] Puente arrancado (TCP ${BridgeConstants.TCP_PORT} + Bluetooth RFCOMM enabled=$btEnabled)")
    }

    /** Activa o pausa la escucha Bluetooth RFCOMM en caliente cuando el usuario marca/desmarca la opción. */
    fun setBluetoothEnabled(context: Context, enabled: Boolean) {
        start(context)
        btEnabled = enabled
        if (!enabled) {
            btListening = false
            btProblem = "Desactivado por el usuario"
            runCatching { activeBtServer?.close() }
            activeBtServer = null
            sessions.filter { it.transport == "Bluetooth" }.forEach {
                it.channel.close()
                sessions.remove(it)
            }
            notifyListeners()
        } else {
            btProblem = null
            wakeNow()
            notifyListeners()
        }
    }

    /** Despierta de inmediato el hilo RFCOMM si estaba esperando reintento o reconexión. */
    fun wakeNow() {
        synchronized(btWakeLock) {
            btWakeLock.notifyAll()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Estado y emparejamiento (para Ajustes)
    // ------------------------------------------------------------------------------------------

    fun status(): Status = Status(
        connectedDevices = sessions.map { it.deviceName.ifBlank { "Móvil" } to it.transport },
        pairedCount = if (::registry.isInitialized) registry.pairedDevices().size else 0,
        pairingCode = if (::registry.isInitialized) registry.currentCode() else null,
        tcpListening = tcpListening,
        bluetoothListening = btListening,
        bluetoothProblem = btProblem,
    )

    val isConnected: Boolean get() = sessions.isNotEmpty()

    var useBluetoothDataBridge: Boolean
        get() = if (::appContext.isInitialized) {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BT_DATA_BRIDGE, true)
        } else true
        set(value) {
            if (::appContext.isInitialized) {
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BT_DATA_BRIDGE, value).apply()
            }
            notifyListeners()
        }

    /**
     * Realiza una petición HTTP/HTTPS a través del Servidor de Datos Bluetooth/TCP del móvil Companion.
     * Devuelve el cuerpo si la respuesta fue 2xx, o `null` si no hay móvil conectado o falló.
     */
    fun httpFetch(
        url: String,
        method: String = "GET",
        body: String? = null,
        contentType: String? = null,
        timeoutMs: Int = 12_000,
    ): String? {
        val active = sessions.lastOrNull { !it.channel.isClosed } ?: return null
        val res = active.httpFetch(url, method, body, contentType, timeoutMs) ?: return null
        if (res.status in 200..299 && res.body.isNotEmpty()) {
            return res.body
        }
        return null
    }

    fun startPairing(context: Context): String {
        start(context)
        val code = registry.startPairing()
        notifyListeners()
        return code
    }

    fun cancelPairing() {
        if (::registry.isInitialized) registry.cancelPairing()
        notifyListeners()
    }

    fun forgetAllPhones() {
        if (!::registry.isInitialized) return
        registry.forgetAll()
        sessions.forEach { it.channel.close() }
        notifyListeners()
    }

    fun addListener(l: (Status) -> Unit) {
        listeners.add(l)
        main.post { l(status()) }
    }

    fun removeListener(l: (Status) -> Unit) {
        listeners.remove(l)
    }

    fun addSyncListener(l: () -> Unit) {
        syncListeners.add(l)
    }

    fun removeSyncListener(l: () -> Unit) {
        syncListeners.remove(l)
    }

    fun pushImmediateSync() {
        pingExecutor.execute { runCatching { pushTelemetry(forceIncludePrefsAndTrips = true) } }
    }

    private fun notifyListeners() {
        val s = status()
        main.post { listeners.forEach { it(s) } }
    }

    // ------------------------------------------------------------------------------------------
    // Enviar la ruta al móvil
    // ------------------------------------------------------------------------------------------

    /** Manda destino + paradas al móvil conectado más reciente. [onResult] se llama en el hilo principal. */
    fun sendRoute(dest: NavPoint, via: List<NavPoint>, onResult: (SendResult) -> Unit) {
        val session = sessions.lastOrNull()
        if (session == null) {
            main.post { onResult(SendResult.NotConnected) }
            return
        }
        sendExecutor.execute {
            val built = MapsRoute.build(dest, via)
            val reply = session.navigate(dest, via)
            val result = when {
                reply == null -> SendResult.Failed("El móvil no contestó. Comprueba que Qonecta Unit Companion sigue abierto.")
                reply.ok -> SendResult.Sent(session.deviceName.ifBlank { "tu móvil" }, session.transport, reply.viaSent, built.reachesDestination)
                reply.error == BridgeMessage.NavResult.ERR_NEEDS_TAP -> SendResult.NeedsTap(session.deviceName.ifBlank { "tu móvil" })
                reply.error == BridgeMessage.NavResult.ERR_NO_MAPS -> SendResult.Failed("El móvil no tiene Google Maps.")
                else -> SendResult.Failed("El móvil no pudo abrir la ruta (${reply.error}).")
            }
            AppLog.i("[Companion] Ruta a ${dest.name} con ${via.size} paradas -> $result")
            main.post { onResult(result) }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Servidores
    // ------------------------------------------------------------------------------------------

    private fun tcpLoop() {
        while (true) {
            try {
                ServerSocket().use { server ->
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(BridgeConstants.TCP_PORT))
                    tcpListening = true
                    notifyListeners()
                    while (true) {
                        val s = server.accept()
                        thread(isDaemon = true, name = "companion-tcp-conn") { serveTcp(s) }
                    }
                }
            } catch (e: Exception) {
                tcpListening = false
                AppLog.w("[Companion] Servidor TCP caído: ${e.message}; reintento en 10 s")
                notifyListeners()
                Thread.sleep(10_000L)
            }
        }
    }

    private fun serveTcp(s: Socket) {
        runCatching {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = BridgeConstants.HANDSHAKE_TIMEOUT_MS
        }
        val channel = LineChannel(s.getInputStream(), s.getOutputStream()) { runCatching { s.close() } }
        serve(channel, "Wi-Fi") { runCatching { s.soTimeout = BridgeConstants.READ_TIMEOUT_MS } }
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothLoop() {
        while (true) {
            if (!btEnabled) {
                btListening = false
                btProblem = "Desactivado por el usuario"
                notifyListeners()
                synchronized(btWakeLock) {
                    runCatching { btWakeLock.wait(30_000L) }
                }
                continue
            }
            var server: BluetoothServerSocket? = null
            try {
                val missingPerm = Build.VERSION.SDK_INT >= 31 &&
                    appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
                when {
                    adapter == null -> throw IllegalStateException("Este equipo no tiene Bluetooth")
                    missingPerm -> throw IllegalStateException("Falta el permiso de Bluetooth")
                    !adapter.isEnabled -> throw IllegalStateException("Bluetooth apagado")
                }
                server = adapter!!.listenUsingRfcommWithServiceRecord(BridgeConstants.RFCOMM_NAME, BridgeConstants.RFCOMM_UUID)
                activeBtServer = server
                btListening = true
                btProblem = null
                notifyListeners()
                while (btEnabled) {
                    val sock = server.accept()
                    thread(isDaemon = true, name = "companion-rfcomm-conn") {
                        val channel = LineChannel(sock.inputStream, sock.outputStream) { runCatching { sock.close() } }
                        // RFCOMM no tiene tiempo de espera: si el móvil no saluda, se corta a los 10 s.
                        val watchdog = thread(isDaemon = true) {
                            try {
                                Thread.sleep(BridgeConstants.HANDSHAKE_TIMEOUT_MS.toLong())
                                if (sessions.none { it.channel === channel }) channel.close()
                            } catch (_: InterruptedException) {
                            }
                        }
                        serve(channel, "Bluetooth") { watchdog.interrupt() }
                    }
                }
            } catch (e: Exception) {
                runCatching { server?.close() }
                if (activeBtServer === server) activeBtServer = null
                btListening = false
                if (!btEnabled) {
                    btProblem = "Desactivado por el usuario"
                    notifyListeners()
                    continue
                }
                if (btProblem != e.message) {
                    btProblem = e.message
                    AppLog.w("[Companion] Bluetooth RFCOMM no disponible: ${e.message}; reintento en 8 s")
                    notifyListeners()
                }
                synchronized(btWakeLock) {
                    runCatching { btWakeLock.wait(8_000L) }
                }
            }
        }
    }

    private fun serve(channel: LineChannel, transport: String, afterHandshake: () -> Unit) {
        val session = BridgeServerSession(
            channel = channel,
            transport = transport,
            auth = registry,
            carName = CAR_NAME,
            carAppVersion = com.andrerinas.openheadunit.BuildConfig.VERSION_NAME,
            carVersionCode = com.andrerinas.openheadunit.BuildConfig.VERSION_CODE,
            logsProvider = { maxLines -> collectUnitLogs(maxLines) },
            telemetryHandler = { msg -> onCompanionSyncReceived(msg.payloadJson) },
            apkPushHandler = { offer, sendReply -> handleApkPush(offer, sendReply) },
        )
        val ok = runCatching { session.handshake() }.getOrDefault(false)
        if (!ok) {
            channel.close()
            notifyListeners()
            return
        }
        afterHandshake()
        // Un mismo móvil reconectando: se cierra la sesión vieja.
        sessions.filter { it.deviceName == session.deviceName }.forEach { it.channel.close(); sessions.remove(it) }
        sessions.add(session)
        AppLog.i("[Companion] Móvil conectado: ${session.deviceName} por $transport")
        notifyListeners()
        pingExecutor.execute { runCatching { pushTelemetry() } }
        session.runLoop()
        sessions.remove(session)
        AppLog.i("[Companion] Móvil desconectado: ${session.deviceName} ($transport)")
        notifyListeners()
    }

    private fun collectUnitLogs(maxLines: Int): Pair<String, String> {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        val now = java.util.Date()
        val fileName = "qonecta-unit-logs-${fmt.format(now)}.txt"
        val sb = StringBuilder()
        sb.appendLine("============================================================")
        sb.appendLine("QONECTA UNIT · REGISTRO REMOTO DE DIAGNÓSTICO (SIN PENDRIVE)")
        sb.appendLine("Fecha: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(now)}")
        sb.appendLine("Paquete: ${appContext.packageName}")
        sb.appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("============================================================")
        sb.appendLine()

        // 1. Archivo de registro persistente de AppLog (ohu.log)
        runCatching {
            val logFile = AppLog.currentLogFile ?: AppLog.lastLogFile
            if (logFile != null && logFile.isFile && logFile.canRead()) {
                sb.appendLine("--- ARCHIVO DE REGISTRO QONECTA UNIT (${logFile.name}, ${logFile.length()} bytes) ---")
                val lines = logFile.readLines()
                val tail: List<String> = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
                for (line in tail) {
                    sb.appendLine(line)
                }
                sb.appendLine()
            }
        }

        // 2. Logcat reciente del proceso
        runCatching {
            val logcatLines = maxLines.coerceIn(150, 1000)
            val proc = ProcessBuilder("logcat", "-d", "-v", "time", "-t", logcatLines.toString())
                .redirectErrorStream(true)
                .start()
            sb.appendLine("--- LOGCAT RECIENTE ($logcatLines líneas) ---")
            proc.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.appendLine(line)
                }
            }
            proc.waitFor(3, TimeUnit.SECONDS)
        }.onFailure { e ->
            sb.appendLine("[Logcat adicional no disponible: ${e.message}]")
        }

        // 3. Inventario ligero Anti-OTA y Sonda de Cámaras (sin volcar archivos XML enteros ni DEX por Bluetooth)
        runCatching {
            sb.appendLine()
            sb.appendLine(com.headqlink.unit.extended.OtaSelfUpdater.buildDiagnosticAntiOtaSection(appContext))
        }

        return fileName to sb.toString()
    }

    private fun onCompanionSyncReceived(payloadJson: String) {
        if (!::appContext.isInitialized) return
        runCatching {
            val json = JSONObject(payloadJson)
            val changed = com.headqlink.unit.extended.LeapVehicleDataSource.applyCompanionSync(appContext, json)
            if (changed) {
                AppLog.i("[Companion] Sincronización recibida desde el móvil (tarifas / viajes ocultos o eliminados)")
                main.post { syncListeners.forEach { runCatching { it() } } }
                pushImmediateSync()
            }
        }
    }

    /**
     * Estado en vivo del coche para Companion + sincronización bidireccional de viajes, privacidad y tarifas:
     * batería, autonomía, velocidad, potencia, clima, viajes (incluyendo ocultos/eliminados) y precios de energía/combustible.
     */
    private fun pushTelemetry(forceIncludePrefsAndTrips: Boolean = false) {
        if (sessions.isEmpty()) return
        val snap = com.headqlink.unit.extended.LeapVehicleDataSource.snapshot()
        val hasRealCarTelemetry = snap.connected && !snap.isDemo
        if (!hasRealCarTelemetry && !forceIncludePrefsAndTrips) {
            // Aun sin CAN en vivo (por ejemplo en casa o recién arrancado), sincronizamos viajes y tarifas con Companion
        }
        fun num(v: Double): Any = if (v.isNaN() || v.isInfinite()) org.json.JSONObject.NULL else v
        val tripsArr = org.json.JSONArray()
        val deletedArr = org.json.JSONArray()
        val hiddenArr = org.json.JSONArray()
        var pricingObj = org.json.JSONObject()
        var variantId = "promax_design_299_my26"
        var isReev = snap.isReev
        if (::appContext.isInitialized) {
            runCatching {
                val variant = com.headqlink.unit.extended.LeapVehicleDataSource.getSelectedVariant(appContext)
                variantId = variant.id
                isReev = variant.isReev || snap.isReev
                val pricing = com.headqlink.unit.extended.LeapVehicleDataSource.getEnergyPricing(appContext)
                pricingObj = org.json.JSONObject()
                    .put("homeElecEurKwh", pricing.homeElecEurKwh)
                    .put("dcFastElecEurKwh", pricing.dcFastElecEurKwh)
                    .put("gasolineEurLiter", pricing.gasolineEurLiter)
                    .put("dieselEurLiter", pricing.dieselEurLiter)
                    .put("tariffMode", pricing.tariffMode)
                    .put("isReevMode", isReev)
                    .put("updatedAtMs", pricing.updatedAtMs)
                com.headqlink.unit.extended.LeapVehicleDataSource.getDeletedTripIds(appContext).forEach { deletedArr.put(it) }
                com.headqlink.unit.extended.LeapVehicleDataSource.getHiddenTripIds(appContext).forEach { hiddenArr.put(it) }
                val recent = com.headqlink.unit.extended.LeapVehicleDataSource.loadRecentTrips(appContext, includeHidden = true).take(25)
                for (t in recent) {
                    tripsArr.put(
                        org.json.JSONObject()
                            .put("startTimeMs", t.startTimeMs)
                            .put("durationMin", t.durationMin)
                            .put("distanceKm", num(t.distanceKm))
                            .put("energyKwh", num(t.energyKwh))
                            .put("avgKwh100", num(t.avgKwh100))
                            .put("startSoc", num(t.startSoc))
                            .put("endSoc", num(t.endSoc))
                            .put("avgSpeedKmh", num(t.avgSpeedKmh))
                            .put("maxSpeedKmh", num(t.maxSpeedKmh))
                            .put("originName", t.originName)
                            .put("destinationName", t.destinationName)
                            .put("costEur", num(t.costEur))
                            .put("hidden", t.hidden)
                            .put("fuelLitersUsed", num(t.fuelLitersUsed)),
                    )
                }
            }
        }
        var unitConnObj = org.json.JSONObject()
        if (::appContext.isInitialized) {
            runCatching {
                val us = com.andrerinas.openheadunit.utils.Settings(appContext)
                val aaUp = runCatching { com.andrerinas.openheadunit.App.provide(appContext).commManager.isConnected }.getOrDefault(false)
                val aaWireless = runCatching { com.andrerinas.openheadunit.App.provide(appContext).commManager.isWirelessSession }.getOrDefault(false)
                unitConnObj = org.json.JSONObject()
                    .put("btCompanion", us.btCompanionEnabled)
                    .put("wifi5g", us.wifi5gEnabled)
                    .put("hotspot", us.hotspotModeEnabled)
                    .put("usb", us.usbModeEnabled)
                    .put("aaConnected", aaUp)
                    .put("aaWireless", aaWireless)
            }
        }
        val o = org.json.JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("variantId", variantId)
            .put("isReev", isReev)
            .put("unitConn", unitConnObj)
            .put("pricing", pricingObj)
            .put("deletedTripIds", deletedArr)
            .put("hiddenTripIds", hiddenArr)
            .put("trips", tripsArr)
        if (hasRealCarTelemetry) {
            o.put("soc", num(snap.socPct))
                .put("rangeKm", num(snap.effectiveRangeKm))
                .put("fuelPct", num(snap.fuelPct))
                .put("fuelRangeKm", num(snap.fuelRangeKm))
                .put("speedKmh", num(snap.effectiveSpeedKmh))
                .put("powerKw", num(snap.powerKw))
                .put("hvacKw", num(snap.hvacPowerKw))
                .put("auxKw", num(snap.auxPowerKw))
                .put("acOn", snap.acOn)
                .put("cabinC", num(snap.tempLeftC))
                .put("extC", num(snap.tempExteriorC))
                .put("charging", snap.chargingStatus in 1..2)
                .put("gear", snap.gearLabel())
                .put("tripKm", snap.tripDistanceKm)
                .put("tripKwh", snap.tripEnergyKwh)
                .put("avgKwh100", num(snap.avgConsumptionKwh100))
                .put("tripDurationSec", snap.tripDurationSec)
                .put("maxSpeedKmh", num(snap.maxSpeedKmh))
        }
        val json = o.toString()
        sessions.forEach { s -> if (!s.sendTelemetry(json)) s.channel.close() }
    }

    private fun pingAll() {
        val now = System.currentTimeMillis()
        sessions.forEach { s ->
            if (now - s.lastSeenMs > BridgeConstants.READ_TIMEOUT_MS || !s.ping()) {
                s.channel.close()
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Actualización Push OTA en 1 toque desde Qonecta Companion (TCP Wi-Fi / HTTP / Bluetooth)
    // ------------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun handleApkPush(
        offer: BridgeMessage.ApkPushStart,
        sendReply: (BridgeMessage) -> Unit,
    ) {
        if (!::appContext.isInitialized || offer.sizeBytes <= 100_000L || offer.sizeBytes > 120L * 1024L * 1024L) {
            sendReply(
                BridgeMessage.ApkPushStatus(
                    id = offer.id,
                    stage = "error",
                    progressPct = 0,
                    message = "Tamaño de APK inválido (${offer.sizeBytes} bytes).",
                    done = true,
                    ok = false,
                )
            )
            return
        }

        val outDir = java.io.File(appContext.cacheDir, "ota_apks").apply { mkdirs() }
        val safeName = offer.fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "QonectaUnit_update.apk" }
        val finalFile = java.io.File(outDir, safeName)

        // 0. Si el coche YA tiene exactamente este mismo APK (mismo tamaño y SHA-256), abrir instalador en 0 s
        if (finalFile.isFile && finalFile.length() == offer.sizeBytes && offer.sha256.isNotBlank()) {
            val existingHash = runCatching { sha256Hex(finalFile) }.getOrNull()
            if (existingHash != null && existingHash.equals(offer.sha256, ignoreCase = true)) {
                sendReply(BridgeMessage.ApkPushReady(offer.id, tcpPort = 0, rfcommAvailable = false, alreadyCached = true))
                val (ok, msg) = com.headqlink.unit.extended.OtaSelfUpdater.launchReceivedOtaApk(appContext, finalFile)
                sendReply(BridgeMessage.ApkPushStatus(offer.id, if (ok) "installer" else "permission", 100, msg, done = true, ok = ok))
                return
            }
        }

        var tcpServer: ServerSocket? = null
        try {
            tcpServer = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(BridgeConstants.APK_PUSH_TCP_PORT))
                soTimeout = 30_000
            }
        } catch (_: Exception) {
            runCatching {
                tcpServer = ServerSocket(0).apply { soTimeout = 30_000 }
            }
        }

        var btServer: BluetoothServerSocket? = null
        runCatching {
            val hasBtPerm = Build.VERSION.SDK_INT < 31 ||
                appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            if (hasBtPerm && adapter?.isEnabled == true) {
                btServer = adapter.listenUsingRfcommWithServiceRecord(
                    BridgeConstants.APK_PUSH_RFCOMM_NAME,
                    BridgeConstants.APK_PUSH_RFCOMM_UUID,
                )
            }
        }

        val boundTcpPort = tcpServer?.localPort ?: 0
        sendReply(
            BridgeMessage.ApkPushReady(
                id = offer.id,
                tcpPort = boundTcpPort,
                rfcommAvailable = btServer != null,
                alreadyCached = false,
            )
        )

        val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
        val doneLatch = java.util.concurrent.CountDownLatch(1)

        fun finishWithStream(sourceLabel: String, rawInput: java.io.InputStream) {
            val tmpFile = java.io.File(outDir, "receiving_${offer.id}.apk.part")
            try {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                var received = 0L
                var lastPctSent = -1
                var lastUiMs = 0L
                java.io.FileOutputStream(tmpFile).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (received < offer.sizeBytes) {
                        val toRead = minOf(buf.size.toLong(), offer.sizeBytes - received).toInt()
                        val r = rawInput.read(buf, 0, toRead)
                        if (r <= 0) break
                        fos.write(buf, 0, r)
                        md.update(buf, 0, r)
                        received += r
                        val pct = ((received * 100L) / offer.sizeBytes).toInt().coerceIn(0, 99)
                        val now = System.currentTimeMillis()
                        if (pct >= lastPctSent + 8 && now - lastUiMs >= 250L) {
                            lastPctSent = pct
                            lastUiMs = now
                            val mbDone = String.format(java.util.Locale.US, "%.1f", received / (1024.0 * 1024.0))
                            val mbTotal = String.format(java.util.Locale.US, "%.1f", offer.sizeBytes / (1024.0 * 1024.0))
                            sendReply(
                                BridgeMessage.ApkPushStatus(
                                    id = offer.id,
                                    stage = "receiving",
                                    progressPct = pct,
                                    message = "🚀 Recibiendo en el coche por $sourceLabel: $pct% ($mbDone / $mbTotal MB)…",
                                    done = false,
                                    ok = false,
                                )
                            )
                        }
                    }
                    fos.flush()
                }
                if (received != offer.sizeBytes) {
                    tmpFile.delete()
                    sendReply(
                        BridgeMessage.ApkPushStatus(
                            id = offer.id,
                            stage = "error",
                            progressPct = 0,
                            message = "Transferencia incompleta ($received / ${offer.sizeBytes} bytes). Vuelve a intentarlo.",
                            done = true,
                            ok = false,
                        )
                    )
                    return
                }
                val digestHex = md.digest().joinToString("") { "%02x".format(it) }
                if (offer.sha256.isNotBlank() && !digestHex.equals(offer.sha256, ignoreCase = true)) {
                    tmpFile.delete()
                    sendReply(
                        BridgeMessage.ApkPushStatus(
                            id = offer.id,
                            stage = "error",
                            progressPct = 0,
                            message = "Verificación SHA-256 fallida en el coche. Vuelve a pulsar Actualizar.",
                            done = true,
                            ok = false,
                        )
                    )
                    return
                }
                if (finalFile.exists()) finalFile.delete()
                if (!tmpFile.renameTo(finalFile)) {
                    tmpFile.copyTo(finalFile, overwrite = true)
                    tmpFile.delete()
                }
                AppLog.i("[Companion] APK recibido al 100% por $sourceLabel (${finalFile.name}, $received bytes)")
                val (ok, msg) = com.headqlink.unit.extended.OtaSelfUpdater.launchReceivedOtaApk(appContext, finalFile)
                sendReply(
                    BridgeMessage.ApkPushStatus(
                        id = offer.id,
                        stage = if (ok) "installer" else "permission",
                        progressPct = 100,
                        message = msg,
                        done = true,
                        ok = ok,
                    )
                )
            } catch (e: Exception) {
                runCatching { tmpFile.delete() }
                sendReply(
                    BridgeMessage.ApkPushStatus(
                        id = offer.id,
                        stage = "error",
                        progressPct = 0,
                        message = "Error al recibir APK en el coche: ${e.message}",
                        done = true,
                        ok = false,
                    )
                )
            } finally {
                runCatching { tcpServer?.close() }
                runCatching { btServer?.close() }
                doneLatch.countDown()
            }
        }

        // Vía 1: TCP directo entrante desde el móvil (Wi-Fi Direct / Hotspot, ~35 MB/s)
        if (tcpServer != null) {
            thread(name = "ota-tcp-accept-${offer.id}", isDaemon = true) {
                runCatching {
                    val sock = tcpServer!!.accept()
                    sock.soTimeout = 45_000
                    sock.use { s ->
                        val inp = s.getInputStream()
                        val header = readHeaderLineAscii(inp)
                        if (header == offer.id && claimed.compareAndSet(false, true)) {
                            runCatching { btServer?.close() }
                            finishWithStream("Wi-Fi directo", inp)
                        }
                    }
                }
            }
        }

        // Vía 2: Si tras 1.8 s no entró TCP directo y hay URLs HTTP del móvil (Hotspot), el coche descarga por HTTP
        if (offer.httpUrls.isNotEmpty()) {
            thread(name = "ota-http-pull-${offer.id}", isDaemon = true) {
                runCatching {
                    Thread.sleep(1_800L)
                    if (claimed.get()) return@thread
                    for (urlStr in offer.httpUrls) {
                        if (claimed.get()) return@thread
                        val conn = openWifiOrDefaultHttp(urlStr) ?: continue
                        try {
                            if (conn.responseCode in 200..299 && claimed.compareAndSet(false, true)) {
                                runCatching { tcpServer?.close() }
                                runCatching { btServer?.close() }
                                conn.inputStream.use { inp ->
                                    finishWithStream("Wi-Fi Hotspot", inp)
                                }
                                return@thread
                            }
                        } finally {
                            runCatching { conn.disconnect() }
                        }
                    }
                }
            }
        }

        // Vía 3: Respaldo por Bluetooth RFCOMM (si no hay Wi-Fi entre móvil y coche)
        if (btServer != null) {
            thread(name = "ota-bt-accept-${offer.id}", isDaemon = true) {
                runCatching {
                    val sock = btServer!!.accept()
                    sock.use { s ->
                        val inp = s.inputStream
                        val header = readHeaderLineAscii(inp)
                        if (header == offer.id && claimed.compareAndSet(false, true)) {
                            runCatching { tcpServer?.close() }
                            finishWithStream("Bluetooth", inp)
                        }
                    }
                }
            }
        }

        // Tiempo máximo total (por Bluetooth puede tardar ~3 min para 42 MB)
        val finished = doneLatch.await(240, TimeUnit.SECONDS)
        runCatching { tcpServer?.close() }
        runCatching { btServer?.close() }
        if (!finished && claimed.compareAndSet(false, true)) {
            sendReply(
                BridgeMessage.ApkPushStatus(
                    id = offer.id,
                    stage = "timeout",
                    progressPct = 0,
                    message = "Tiempo agotado esperando la conexión de datos con el coche.",
                    done = true,
                    ok = false,
                )
            )
        }
    }

    private fun openWifiOrDefaultHttp(urlStr: String): java.net.HttpURLConnection? {
        val url = runCatching { java.net.URL(urlStr) }.getOrNull() ?: return null
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
        if (cm != null) {
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                    val conn = runCatching {
                        (net.openConnection(url) as java.net.HttpURLConnection).apply {
                            connectTimeout = 2_500
                            readTimeout = 45_000
                            instanceFollowRedirects = true
                        }
                    }.getOrNull() ?: continue
                    if (runCatching { conn.responseCode in 200..299 }.getOrDefault(false)) {
                        return conn
                    }
                    runCatching { conn.disconnect() }
                }
            }
        }
        return runCatching {
            (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 2_500
                readTimeout = 45_000
                instanceFollowRedirects = true
            }
        }.getOrNull()
    }

    private fun readHeaderLineAscii(inp: java.io.InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 64) {
            val b = inp.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString().trim()
    }

    private fun sha256Hex(file: java.io.File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            var r: Int
            while (fis.read(buf).also { r = it } != -1) {
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private class PrefsTokenStore(ctx: Context) : TokenStore {
        private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun loadTokens(): Map<String, String> = runCatching {
            val o = JSONObject(prefs.getString(KEY_TOKENS, "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.optString(it) }
        }.getOrDefault(emptyMap())

        override fun saveTokens(tokens: Map<String, String>) {
            val o = JSONObject()
            tokens.forEach { (k, v) -> o.put(k, v) }
            prefs.edit().putString(KEY_TOKENS, o.toString()).apply()
        }
    }
}
