package com.headqlink.unit.companion.cloud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors

/**
 * La cuenta Leapmotor en Qonecta Unit Companion (cliente portado de Qonecta+: LeapApi, LeapTls, CarCloudSession…).
 *
 * Reglas de quién manda:
 * - **Lectura del estado del coche**: con Companion conectado a Qonecta Unit, manda Unit (datos en vivo del coche) y
 *   la nube NO se sondea. Al desconectarse, la nube toma el relevo (cada 2 min mientras la app está abierta).
 * - **Órdenes remotas** (cerrar, abrir, maletero, clima, localizar, cable): siempre por la cuenta Leapmotor, estés o
 *   no conectado a Unit, para poder cerrar el coche nada más bajarte.
 */
object CompanionCloud {

    enum class State { NO_CERT, NO_ACCOUNT, NEEDS_VEHICLE, WAITING, OK, ERROR, EXPIRED, SERVER_KEY, PAUSED_BY_UNIT }

    data class Vehicle(val vin: String, val carType: String, val nickName: String) {
        val label: String
            get() = (nickName.ifBlank { carType.ifBlank { "Leapmotor" } }) + " · …" + vin.takeLast(4)
    }

    /** Lo que la pantalla necesita del último estado leído de la nube (sin VIN ni posición). */
    data class CarData(
        val socPct: Double,
        val rangeKm: Double,
        val odometerKm: Double,
        val interiorTempC: Double,
        val locked: Boolean?,
        val doorsOpen: List<Boolean?>,
        val bootOpen: Boolean?,
        val tyreBars: List<Double>,
        val tyreWarn: List<Boolean>,
        val charging: Boolean,
        val pluggedIn: Boolean,
        val chargePowerKw: Double,
        val carTimeMs: Long,
    )

    data class Snapshot(
        val state: State = State.NO_CERT,
        val data: CarData? = null,
        val fetchedAtMs: Long = 0L,
        val message: String = "",
        val maskedEmail: String = "",
        val hasPin: Boolean = false,
        /** Huella y pin de una clave de servidor desconocida, para preguntar al usuario. */
        val serverKeyFingerprint: String = "",
        val serverKeyPin: String = "",
    )

    @Volatile
    var current = Snapshot()
        private set

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "companion-cloud").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(Snapshot) -> Unit>()

    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var connectedToUnit = false

    @Volatile
    private var polling = false
    private var pollGeneration = 0
    private const val POLL_MS = 120_000L
    private const val POLL_RETRY_MS = 300_000L

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        CarCloud.setListener { refreshMeta() }
        refreshMeta()
    }

    fun addListener(l: (Snapshot) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (Snapshot) -> Unit) {
        listeners.remove(l)
    }

    private fun publish(s: Snapshot) {
        current = s
        main.post { listeners.forEach { it(s) } }
    }

    private fun store(): CarCloudStore? = appCtx?.let { CarCloudStore(it) }

    /** Recalcula el estado según lo guardado (certificado, sesión, coche, PIN), sin red. */
    fun refreshMeta() {
        val st = store() ?: return
        val base = current
        val saved = runCatching { st.session() }.getOrNull()
        val state = when {
            !st.hasIdentity() -> State.NO_CERT
            !st.hasSession() || saved?.session == null -> State.NO_ACCOUNT
            st.reloginReason() != null -> State.EXPIRED
            saved.vin.isNullOrEmpty() -> State.NEEDS_VEHICLE
            connectedToUnit -> State.PAUSED_BY_UNIT
            base.state in setOf(State.OK, State.ERROR, State.SERVER_KEY) -> base.state
            else -> State.WAITING
        }
        publish(
            base.copy(
                state = state,
                maskedEmail = st.maskedEmail(),
                hasPin = st.hasPin(),
                message = when (state) {
                    State.NO_CERT -> "Importa el certificado de cliente (.crt + .key o .p12) de Qonecta+."
                    State.NO_ACCOUNT -> "Entra con tu correo y contraseña de Leapmotor."
                    State.EXPIRED -> "La sesión de Leapmotor caducó: vuelve a entrar."
                    State.NEEDS_VEHICLE -> "Elige tu coche."
                    State.PAUSED_BY_UNIT -> "Conectado a Qonecta Unit: los datos vienen del coche. Las órdenes siguen yendo por tu cuenta."
                    else -> base.message
                },
            ),
        )
    }

    // ------------------------------------------------------------------ quién manda

    /** Lo llama la app al conectar o desconectar del coche. */
    fun setConnectedToUnit(connected: Boolean) {
        if (connectedToUnit == connected) return
        connectedToUnit = connected
        refreshMeta()
        if (!connected && polling) {
            // Al bajarse del coche, la cuenta toma el relevo ya (lectura inmediata).
            restartPoll()
        }
    }

    val unitIsMaster: Boolean
        get() = connectedToUnit

    // ------------------------------------------------------------------ sondeo (solo sin Unit)

    fun startPolling() {
        if (polling) return
        polling = true
        restartPoll()
    }

    fun stopPolling() {
        polling = false
        synchronized(this) { pollGeneration++ }
    }

    private fun restartPoll() {
        val gen = synchronized(this) { ++pollGeneration }
        Thread({ pollLoop(gen) }, "companion-cloud-poll").apply { isDaemon = true }.start()
    }

    private fun pollLoop(gen: Int) {
        while (polling && gen == pollGeneration) {
            var wait = POLL_MS
            val st = store()
            val ready = st != null && st.hasIdentity() && st.hasSession() && st.reloginReason() == null
            if (ready && !connectedToUnit && current.state != State.SERVER_KEY) {
                wait = if (readOnce()) POLL_MS else POLL_RETRY_MS
            }
            val until = SystemClock.elapsedRealtime() + wait
            while (polling && gen == pollGeneration && SystemClock.elapsedRealtime() < until) {
                Thread.sleep(1_000)
            }
        }
    }

    /** «Leer ahora» (también al desconectar de Unit). */
    fun readNow(done: ((Boolean) -> Unit)? = null) {
        io.execute {
            val ok = readOnce()
            done?.let { cb -> main.post { cb(ok) } }
        }
    }

    @Synchronized
    private fun readOnce(): Boolean {
        val ctx = appCtx ?: return false
        if (connectedToUnit) return false
        return try {
            val s = CarCloudSession.readStatus(ctx)
            CarCloud.publish(s)
            val data = CarData(
                socPct = s.socBest(),
                rangeKm = s.evRangeKm(),
                odometerKm = s.odometerKm,
                interiorTempC = s.interiorTempC,
                locked = s.locked,
                doorsOpen = (0..3).map { s.doorOpen(it) },
                bootOpen = s.bootOpen,
                tyreBars = (0..3).map { s.tyreBar(it) },
                tyreWarn = (0..3).map { s.tyreWarning(it) },
                charging = s.charging(),
                pluggedIn = s.pluggedIn(),
                chargePowerKw = s.powerKw(),
                carTimeMs = s.carTimeMs,
            )
            publish(current.copy(state = if (connectedToUnit) State.PAUSED_BY_UNIT else State.OK, data = data, fetchedAtMs = System.currentTimeMillis(), message = ""))
            true
        } catch (e: Exception) {
            handleError(e)
            false
        }
    }

    private fun handleError(e: Exception) {
        when (e) {
            is LeapHttps.ServerKeyChangedException -> publish(
                current.copy(
                    state = State.SERVER_KEY,
                    serverKeyFingerprint = e.detail.fingerprint,
                    serverKeyPin = e.detail.pin,
                    message = "El servidor de Leapmotor presenta una clave nueva (huella ${e.detail.fingerprint}). Acéptala solo si te fías.",
                ),
            )
            is LeapApi.SessionExpiredException -> publish(current.copy(state = State.EXPIRED, message = "La sesión de Leapmotor caducó: vuelve a entrar."))
            is CarCloudSession.NotConfiguredException -> refreshMeta()
            else -> publish(current.copy(state = State.ERROR, message = describe(e)))
        }
    }

    fun describe(e: Throwable): String = when (e) {
        is LeapApi.SessionExpiredException -> "Sesión caducada: vuelve a entrar."
        is CarCloudSession.NotConfiguredException -> "Falta configurar: ${e.message}"
        is LeapApi.ApiException -> e.serverMessage.ifBlank { "Error de la nube de Leapmotor" }
        is java.security.GeneralSecurityException -> "Certificado o clave no válidos (${e.javaClass.simpleName})"
        is java.io.IOException -> "Sin conexión con la nube de Leapmotor"
        else -> e.javaClass.simpleName
    }

    // ------------------------------------------------------------------ certificado

    sealed class ImportResult {
        object Ok : ImportResult()
        object NeedsPassword : ImportResult()
        object WrongPassword : ImportResult()
        data class Failed(val why: String) : ImportResult()
    }

    /** files: (nombre, bytes) elegidos en el selector (el .crt y el .key juntos, o un .p12/.pfx). */
    fun importCertificate(files: List<Pair<String, ByteArray>>, password: CharArray?, done: (ImportResult) -> Unit) {
        io.execute {
            val result = try {
                val picked = files.map { LeapTls.Picked(it.first, it.second) }
                val id = LeapTls.parse(picked, password)
                store()?.saveIdentity(id) ?: throw IllegalStateException("sin contexto")
                CarCloud.settingsChanged()
                ImportResult.Ok
            } catch (e: LeapTls.ImportException) {
                when (e.kind) {
                    LeapTls.ImportException.Kind.NEEDS_PASSWORD -> ImportResult.NeedsPassword
                    LeapTls.ImportException.Kind.WRONG_PASSWORD -> ImportResult.WrongPassword
                    LeapTls.ImportException.Kind.NO_KEY -> ImportResult.Failed("Falta la clave privada (.key). Elige el .crt y el .key a la vez.")
                    LeapTls.ImportException.Kind.NO_CERT -> ImportResult.Failed("Falta el certificado (.crt). Elige el .crt y el .key a la vez.")
                    LeapTls.ImportException.Kind.MISMATCH -> ImportResult.Failed("El certificado y la clave no son pareja.")
                    LeapTls.ImportException.Kind.TOO_BIG -> ImportResult.Failed("El fichero es demasiado grande para ser un certificado.")
                    else -> ImportResult.Failed("Formato no reconocido (${e.kind}).")
                }
            } catch (e: Exception) {
                ImportResult.Failed(describe(e))
            } finally {
                password?.fill('\u0000')
            }
            main.post {
                refreshMeta()
                done(result)
            }
        }
    }

    // ------------------------------------------------------------------ cuenta

    fun login(email: String, password: String, done: (Result<List<Vehicle>>) -> Unit) {
        val ctx = appCtx ?: return
        io.execute {
            val r = runCatching {
                CarCloudSession.login(ctx, email, password).map { Vehicle(it.vin, it.carType ?: "", it.nickName ?: "") }
            }
            r.exceptionOrNull()?.let { if (it is Exception) handleError(it) }
            main.post {
                refreshMeta()
                done(r)
                if (r.isSuccess) readNow()
            }
        }
    }

    fun vehicles(done: (Result<List<Vehicle>>) -> Unit) {
        val ctx = appCtx ?: return
        io.execute {
            val r = runCatching { CarCloudSession.vehicles(ctx).map { Vehicle(it.vin, it.carType ?: "", it.nickName ?: "") } }
            main.post { done(r) }
        }
    }

    fun chooseVehicle(v: Vehicle, done: (Result<Unit>) -> Unit) {
        val ctx = appCtx ?: return
        io.execute {
            val r = runCatching {
                val match = CarCloudSession.vehicles(ctx).firstOrNull { it.vin == v.vin }
                    ?: throw IllegalStateException("coche no encontrado")
                CarCloudSession.chooseVehicle(ctx, match)
            }
            main.post {
                refreshMeta()
                done(r)
                if (r.isSuccess) readNow()
            }
        }
    }

    fun savePin(pin: String): Boolean {
        val st = store() ?: return false
        if (!pin.matches(Regex("[0-9]{4,12}"))) return false
        return runCatching { st.savePin(pin) }.isSuccess.also { refreshMeta() }
    }

    fun acceptServerKey(done: () -> Unit) {
        val pin = current.serverKeyPin
        if (pin.isNotBlank()) store()?.acceptPin(pin)
        publish(current.copy(state = State.WAITING, serverKeyPin = "", serverKeyFingerprint = ""))
        CarCloud.settingsChanged()
        readNow { done() }
    }

    fun logout() {
        val ctx = appCtx ?: return
        io.execute {
            CarCloudSession.logout(ctx)
            main.post {
                publish(current.copy(data = null, fetchedAtMs = 0))
                refreshMeta()
            }
        }
    }

    // ------------------------------------------------------------------ órdenes (siempre por la cuenta)

    enum class Order { LOCK, UNLOCK, TRUNK_OPEN, TRUNK_CLOSE, CLIMATE_ON, CLIMATE_OFF, DEFROST, FIND, CABLE_UNLOCK }

    fun send(order: Order, tempC: Int = CarCommand.TEMP_DEFAULT, done: (Result<Boolean>) -> Unit) {
        val ctx = appCtx ?: return
        io.execute {
            val cmd = CarCommand(CarCommand.Kind.valueOf(order.name), tempC)
            val r = runCatching { CarCloudSession.command(ctx, cmd) }
            r.exceptionOrNull()?.let { if (it is LeapHttps.ServerKeyChangedException || it is LeapApi.SessionExpiredException) handleError(it as Exception) }
            main.post {
                done(r)
                // Tras una orden confirmada, una lectura para ver el resultado (solo si no manda Unit).
                if (r.getOrNull() == true && !connectedToUnit) main.postDelayed({ readNow() }, 4_000)
            }
        }
    }

    fun orderLabel(order: Order, tempC: Int = CarCommand.TEMP_DEFAULT): String =
        CarCommand(CarCommand.Kind.valueOf(order.name), tempC).label()
}
