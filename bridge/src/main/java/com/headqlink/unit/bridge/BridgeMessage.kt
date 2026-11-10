package com.headqlink.unit.bridge

import org.json.JSONArray
import org.json.JSONObject

/** Un punto de la ruta: destino o parada (normalmente un cargador). */
data class NavPoint(
    val lat: Double,
    val lon: Double,
    val name: String = "",
) {
    val isValid: Boolean
        get() = lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    fun toJson(): JSONObject = JSONObject()
        .put("lat", lat)
        .put("lon", lon)
        .put("name", name)

    companion object {
        fun fromJson(o: JSONObject): NavPoint = NavPoint(
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
            name = o.optString("name", ""),
        )
    }
}

/**
 * Mensajes del puente. Viajan como una línea JSON cada uno (`{"t":"tipo", ...}\n`).
 */
sealed class BridgeMessage {

    /** Móvil → coche, primer mensaje: con [token] si ya está emparejado, o con [pairCode] para emparejar. */
    data class Hello(
        val version: Int,
        val deviceName: String,
        val appVersion: String,
        val token: String? = null,
        val pairCode: String? = null,
    ) : BridgeMessage()

    /** Coche → móvil: aceptado. [token] es el que el móvil debe guardar para las próximas veces. */
    data class Welcome(
        val version: Int,
        val carName: String,
        val token: String,
        val newlyPaired: Boolean,
        val carAppVersion: String = "",
        val carVersionCode: Int = 0,
    ) : BridgeMessage()

    /** Coche → móvil: rechazado, y el coche cierra la conexión. */
    data class Denied(val reason: String) : BridgeMessage() {
        companion object {
            const val BAD_CODE = "bad_code"
            const val NOT_PAIRING = "not_pairing"
            const val BAD_TOKEN = "bad_token"
            const val BAD_VERSION = "bad_version"
            const val BAD_HELLO = "bad_hello"
        }
    }

    /** Coche → móvil: abre Google Maps hacia [dest] pasando por [via] (en orden). */
    data class Navigate(
        val id: String,
        val dest: NavPoint,
        val via: List<NavPoint> = emptyList(),
    ) : BridgeMessage()

    /** Móvil → coche: resultado de [Navigate]. */
    data class NavResult(
        val id: String,
        val ok: Boolean,
        val error: String? = null,
        val viaSent: Int = 0,
    ) : BridgeMessage() {
        companion object {
            const val ERR_NO_MAPS = "no_maps"
            const val ERR_BACKGROUND_BLOCKED = "background_blocked"
            const val ERR_NEEDS_TAP = "needs_tap"
            const val ERR_BAD_REQUEST = "bad_request"
        }
    }

    /**
     * Coche → móvil: solicita una petición HTTP/HTTPS a través de los datos móviles del teléfono
     * (Servidor de Datos Bluetooth/TCP para el planificador y consultas de Qonecta Unit sin gastar datos del coche).
     */
    data class HttpFetch(
        val id: String,
        val url: String,
        val method: String = "GET",
        val body: String? = null,
        val contentType: String? = null,
        val timeoutMs: Int = 12_000,
    ) : BridgeMessage()

    /** Móvil → coche: respuesta de [HttpFetch]. */
    data class HttpResult(
        val id: String,
        val status: Int,
        val body: String = "",
        val error: String? = null,
    ) : BridgeMessage()

    /** Coche ↔ móvil: sincronización en vivo de telemetría C10, puertas/maletero, presiones TPMS y viajes. */
    data class TelemetrySync(
        val payloadJson: String,
    ) : BridgeMessage()

    /** Móvil → coche: solicita los últimos registros de diagnóstico (ohu.log + logcat) sin usar pendrive. */
    data class LogsRequest(
        val id: String,
        val maxLines: Int = 800,
    ) : BridgeMessage()

    /** Coche → móvil: devuelve el archivo de registros recopilado en Qonecta Unit. */
    data class LogsResult(
        val id: String,
        val fileName: String,
        val logText: String,
    ) : BridgeMessage()

    /** Móvil → coche: solicita enviar e instalar una actualización APK de Qonecta Unit en 1 toque. */
    data class ApkPushStart(
        val id: String,
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
        val versionName: String,
        val versionCode: Int,
        val httpUrls: List<String> = emptyList(),
    ) : BridgeMessage()

    /** Coche → móvil: listo para recibir el APK (por TCP directo, RFCOMM o ya cacheado). */
    data class ApkPushReady(
        val id: String,
        val tcpPort: Int,
        val rfcommAvailable: Boolean,
        val alreadyCached: Boolean = false,
    ) : BridgeMessage()

    /** Coche → móvil: progreso en vivo y resultado del envío / lanzamiento del instalador en el coche. */
    data class ApkPushStatus(
        val id: String,
        val stage: String,
        val progressPct: Int,
        val message: String,
        val done: Boolean = false,
        val ok: Boolean = false,
    ) : BridgeMessage()

    object Ping : BridgeMessage()
    object Pong : BridgeMessage()

    /** Tipo desconocido (de una versión más nueva): se ignora sin cortar la conexión. */
    data class Unknown(val type: String) : BridgeMessage()

    fun encode(): String = BridgeCodec.encode(this)
}

object BridgeCodec {

    fun encode(msg: BridgeMessage): String {
        val o = JSONObject()
        when (msg) {
            is BridgeMessage.Hello -> {
                o.put("t", "hello")
                o.put("v", msg.version)
                o.put("device", msg.deviceName)
                o.put("appVersion", msg.appVersion)
                msg.token?.let { o.put("token", it) }
                msg.pairCode?.let { o.put("pairCode", it) }
            }
            is BridgeMessage.Welcome -> {
                o.put("t", "welcome")
                o.put("v", msg.version)
                o.put("car", msg.carName)
                o.put("token", msg.token)
                o.put("newlyPaired", msg.newlyPaired)
                if (msg.carAppVersion.isNotBlank()) o.put("carAppVersion", msg.carAppVersion)
                if (msg.carVersionCode > 0) o.put("carVersionCode", msg.carVersionCode)
            }
            is BridgeMessage.Denied -> {
                o.put("t", "denied")
                o.put("reason", msg.reason)
            }
            is BridgeMessage.Navigate -> {
                o.put("t", "navigate")
                o.put("id", msg.id)
                o.put("dest", msg.dest.toJson())
                val arr = JSONArray()
                msg.via.forEach { arr.put(it.toJson()) }
                o.put("via", arr)
            }
            is BridgeMessage.NavResult -> {
                o.put("t", "navResult")
                o.put("id", msg.id)
                o.put("ok", msg.ok)
                msg.error?.let { o.put("error", it) }
                o.put("viaSent", msg.viaSent)
            }
            is BridgeMessage.HttpFetch -> {
                o.put("t", "httpFetch")
                o.put("id", msg.id)
                o.put("url", msg.url)
                o.put("method", msg.method)
                msg.body?.let { o.put("body", it) }
                msg.contentType?.let { o.put("contentType", it) }
                o.put("timeoutMs", msg.timeoutMs)
            }
            is BridgeMessage.HttpResult -> {
                o.put("t", "httpResult")
                o.put("id", msg.id)
                o.put("status", msg.status)
                o.put("body", msg.body)
                msg.error?.let { o.put("error", it) }
            }
            is BridgeMessage.TelemetrySync -> {
                o.put("t", "telemetrySync")
                o.put("payload", msg.payloadJson)
            }
            is BridgeMessage.LogsRequest -> {
                o.put("t", "logsRequest")
                o.put("id", msg.id)
                o.put("maxLines", msg.maxLines)
            }
            is BridgeMessage.LogsResult -> {
                o.put("t", "logsResult")
                o.put("id", msg.id)
                o.put("fileName", msg.fileName)
                o.put("logText", msg.logText)
            }
            is BridgeMessage.ApkPushStart -> {
                o.put("t", "apkPushStart")
                o.put("id", msg.id)
                o.put("fileName", msg.fileName)
                o.put("sizeBytes", msg.sizeBytes)
                o.put("sha256", msg.sha256)
                o.put("versionName", msg.versionName)
                o.put("versionCode", msg.versionCode)
                val urlsArr = JSONArray()
                msg.httpUrls.forEach { urlsArr.put(it) }
                o.put("httpUrls", urlsArr)
            }
            is BridgeMessage.ApkPushReady -> {
                o.put("t", "apkPushReady")
                o.put("id", msg.id)
                o.put("tcpPort", msg.tcpPort)
                o.put("rfcommAvailable", msg.rfcommAvailable)
                o.put("alreadyCached", msg.alreadyCached)
            }
            is BridgeMessage.ApkPushStatus -> {
                o.put("t", "apkPushStatus")
                o.put("id", msg.id)
                o.put("stage", msg.stage)
                o.put("progressPct", msg.progressPct)
                o.put("message", msg.message)
                o.put("done", msg.done)
                o.put("ok", msg.ok)
            }
            BridgeMessage.Ping -> o.put("t", "ping")
            BridgeMessage.Pong -> o.put("t", "pong")
            is BridgeMessage.Unknown -> o.put("t", msg.type)
        }
        return o.toString()
    }

    /** @throws org.json.JSONException si la línea no es un mensaje válido. */
    fun decode(line: String): BridgeMessage {
        val o = JSONObject(line)
        return when (val t = o.getString("t")) {
            "hello" -> BridgeMessage.Hello(
                version = o.getInt("v"),
                deviceName = o.optString("device", ""),
                appVersion = o.optString("appVersion", ""),
                token = o.optString("token", "").ifBlank { null },
                pairCode = o.optString("pairCode", "").ifBlank { null },
            )
            "welcome" -> BridgeMessage.Welcome(
                version = o.getInt("v"),
                carName = o.optString("car", ""),
                token = o.getString("token"),
                newlyPaired = o.optBoolean("newlyPaired", false),
                carAppVersion = o.optString("carAppVersion", ""),
                carVersionCode = o.optInt("carVersionCode", 0),
            )
            "denied" -> BridgeMessage.Denied(o.optString("reason", ""))
            "navigate" -> {
                val viaArr = o.optJSONArray("via") ?: JSONArray()
                BridgeMessage.Navigate(
                    id = o.getString("id"),
                    dest = NavPoint.fromJson(o.getJSONObject("dest")),
                    via = (0 until viaArr.length()).map { NavPoint.fromJson(viaArr.getJSONObject(it)) },
                )
            }
            "navResult" -> BridgeMessage.NavResult(
                id = o.getString("id"),
                ok = o.getBoolean("ok"),
                error = o.optString("error", "").ifBlank { null },
                viaSent = o.optInt("viaSent", 0),
            )
            "httpFetch" -> BridgeMessage.HttpFetch(
                id = o.getString("id"),
                url = o.getString("url"),
                method = o.optString("method", "GET"),
                body = o.optString("body", "").ifBlank { null },
                contentType = o.optString("contentType", "").ifBlank { null },
                timeoutMs = o.optInt("timeoutMs", 12_000),
            )
            "httpResult" -> BridgeMessage.HttpResult(
                id = o.getString("id"),
                status = o.optInt("status", 0),
                body = o.optString("body", ""),
                error = o.optString("error", "").ifBlank { null },
            )
            "telemetrySync" -> BridgeMessage.TelemetrySync(
                payloadJson = o.optString("payload", "{}"),
            )
            "logsRequest" -> BridgeMessage.LogsRequest(
                id = o.getString("id"),
                maxLines = o.optInt("maxLines", 800),
            )
            "logsResult" -> BridgeMessage.LogsResult(
                id = o.getString("id"),
                fileName = o.optString("fileName", "qonecta-unit-logs.txt"),
                logText = o.optString("logText", ""),
            )
            "apkPushStart" -> {
                val urlsArr = o.optJSONArray("httpUrls") ?: JSONArray()
                BridgeMessage.ApkPushStart(
                    id = o.getString("id"),
                    fileName = o.optString("fileName", "QonectaUnit.apk"),
                    sizeBytes = o.optLong("sizeBytes", 0L),
                    sha256 = o.optString("sha256", ""),
                    versionName = o.optString("versionName", ""),
                    versionCode = o.optInt("versionCode", 0),
                    httpUrls = (0 until urlsArr.length()).mapNotNull { urlsArr.optString(it, "").takeIf { s -> s.isNotBlank() } },
                )
            }
            "apkPushReady" -> BridgeMessage.ApkPushReady(
                id = o.getString("id"),
                tcpPort = o.optInt("tcpPort", 0),
                rfcommAvailable = o.optBoolean("rfcommAvailable", false),
                alreadyCached = o.optBoolean("alreadyCached", false),
            )
            "apkPushStatus" -> BridgeMessage.ApkPushStatus(
                id = o.getString("id"),
                stage = o.optString("stage", ""),
                progressPct = o.optInt("progressPct", 0),
                message = o.optString("message", ""),
                done = o.optBoolean("done", false),
                ok = o.optBoolean("ok", false),
            )
            "ping" -> BridgeMessage.Ping
            "pong" -> BridgeMessage.Pong
            else -> BridgeMessage.Unknown(t)
        }
    }
}
