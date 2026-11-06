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
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private val pingExecutor = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var started = false
    @Volatile private var tcpListening = false
    @Volatile private var btListening = false
    @Volatile private var btProblem: String? = null
    private lateinit var appContext: Context
    private lateinit var registry: PairingRegistry

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        registry = PairingRegistry(PrefsTokenStore(appContext))
        thread(name = "companion-tcp", isDaemon = true) { tcpLoop() }
        thread(name = "companion-rfcomm", isDaemon = true) { bluetoothLoop() }
        pingExecutor.scheduleWithFixedDelay({ pingAll() }, BridgeConstants.PING_INTERVAL_MS, BridgeConstants.PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        AppLog.i("[Companion] Puente arrancado (TCP ${BridgeConstants.TCP_PORT} + Bluetooth RFCOMM)")
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
                btListening = true
                btProblem = null
                notifyListeners()
                while (true) {
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
                btListening = false
                if (btProblem != e.message) {
                    btProblem = e.message
                    AppLog.w("[Companion] Bluetooth RFCOMM no disponible: ${e.message}; reintento en 15 s")
                    notifyListeners()
                }
                Thread.sleep(15_000L)
            }
        }
    }

    private fun serve(channel: LineChannel, transport: String, afterHandshake: () -> Unit) {
        val session = BridgeServerSession(channel, transport, registry, CAR_NAME)
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
        session.runLoop()
        sessions.remove(session)
        AppLog.i("[Companion] Móvil desconectado: ${session.deviceName} ($transport)")
        notifyListeners()
    }

    private fun pingAll() {
        val now = System.currentTimeMillis()
        sessions.forEach { s ->
            if (now - s.lastSeenMs > BridgeConstants.READ_TIMEOUT_MS || !s.ping()) {
                s.channel.close()
            }
        }
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
