package com.headqlink.unit.extended

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Motor real de planificación de ruta y carga de Qonecta+ portado a Qonecta Unit.
 *
 * - Geocodificación real con Photon (`photon.komoot.io`) y Nominatim (`nominatim.openstreetmap.org`).
 * - Trazado real por carretera con OSRM (`router.project-osrm.org`), con soporte de paradas intermedias e ida y vuelta.
 * - Perfil de altitud real con Open-Meteo Elevation (`api.open-meteo.com/v1/elevation`) y reserva OpenTopoData (`srtm90m`).
 * - Previsión meteorológica y viento en ruta / destino con Open-Meteo Forecast (`api.open-meteo.com/v1/forecast`).
 * - Modelo físico de energía por tramo (pendiente, aerodinámica con viento frontal/cola, rodadura, temperatura y ciudad).
 * - Cargadores reales en el corredor de la ruta (≤ 2,5 km) y cercanos combinando OpenStreetMap (Overpass) y el registro
 *   oficial de electrolineras de la DGT / MITERD (`infocar.dgt.es`) en España.
 * - Planificador de paradas `ChargePlanner` con curva real de carga del Leapmotor C10 (400 V 84 kW y 800 V 180 kW),
 *   preferencia de cargadores de 800 V, favoritos/vetados y alternativas por parada («Cambiar»).
 */
object QonectaRoutePlanner {

    private const val USER_AGENT = "QonectaUnit/1.0 (Leapmotor C10; Android)"
    const val CHARGER_RADIUS_KM = 2.5
    const val ROUTE_BOX_KM = 22.0
    const val HERE_KM = 2.0
    const val STOP_OVERHEAD_MIN = 3.0
    const val UNKNOWN_KW = 50.0
    const val CHARGE_EFF = 0.93
    const val MAX_STOPS = 8
    const val FINAL_EXTRA_PCT = 10.0
    const val HIGH_VOLTS = 700.0
    const val LOW_VOLTS_FACTOR = 0.5
    const val GOOD_KW = 150.0
    const val FAST_KW = 40.0
    const val PIN_MIN_PCT = 3.0
    const val FAV_BONUS_KW = 50.0
    const val FAV_WINDOW = 0.4

    const val KW_TAGGED = 0
    const val KW_NETWORK = 1
    const val KW_SOCKETS = 2
    const val KW_UNKNOWN = 3

    const val SOCKET_DC = 1
    const val SOCKET_AC = 2

    const val SOURCE_OSM = 0
    const val SOURCE_DGT = 1

    const val VOLTS_ANY = 0
    const val VOLTS_800 = 800
    const val VOLTS_400 = 400

    val MIN_KW_CHOICES = intArrayOf(0, 22, 50, 100, 150, 200, 300)
    val VOLTS_CHOICES = intArrayOf(VOLTS_ANY, VOLTS_800, VOLTS_400)

    data class Place(
        val name: String,
        val detail: String = "",
        val lat: Double,
        val lon: Double,
    )

    data class NetworkBadge(
        val key: String,
        val label: String,
        val initials: String,
        val colorArgb: Int,
        val darkText: Boolean = false,
        val matchTokens: List<String>,
        val fastOnlyTypicalKw: Double? = null,
    )

    val NETWORKS: List<NetworkBadge> = listOf(
        NetworkBadge("tesla", "Tesla", "T", 0xFFE31937.toInt(), false, listOf("tesla", "supercharger")),
        NetworkBadge("zunder", "Zunder", "Z", 0xFF00B37E.toInt(), false, listOf("zunder"), 180.0),
        NetworkBadge("ionity", "Ionity", "IO", 0xFF2E2A6E.toInt(), false, listOf("ionity"), 350.0),
        NetworkBadge("iberdrola", "Iberdrola", "IB", 0xFF5CB615.toInt(), false, listOf("iberdrola")),
        NetworkBadge("endesa", "Endesa X", "EX", 0xFF0075BE.toInt(), false, listOf("endesa")),
        NetworkBadge("repsol", "Repsol", "R", 0xFFFF8200.toInt(), false, listOf("repsol")),
        NetworkBadge("wenea", "Wenea", "W", 0xFF00A3E0.toInt(), false, listOf("wenea")),
        NetworkBadge("moeve", "Moeve / Cepsa", "M", 0xFF00857C.toInt(), false, listOf("moeve", "cepsa")),
        NetworkBadge("galp", "Galp", "G", 0xFFFF6A13.toInt(), false, listOf("galp")),
        NetworkBadge("fastned", "Fastned", "F", 0xFFFFD400.toInt(), true, listOf("fastned"), 300.0),
        NetworkBadge("electra", "Electra", "E", 0xFF14D3A0.toInt(), true, listOf("electra"), 300.0),
        NetworkBadge("allego", "Allego", "A", 0xFFFF7E1F.toInt(), false, listOf("allego")),
        NetworkBadge("powerdot", "Powerdot", "P", 0xFFFFC72C.toInt(), true, listOf("powerdot")),
        NetworkBadge("atlante", "Atlante", "AT", 0xFF00AEEF.toInt(), false, listOf("atlante"), 150.0),
        NetworkBadge("easycharger", "Easycharger", "EC", 0xFF3DBE6A.toInt(), false, listOf("easycharger")),
        NetworkBadge("plenitude", "Plenitude / Be Charge", "BC", 0xFFFFD100.toInt(), true, listOf("plenitude", "be charge", "becharge")),
        NetworkBadge("lidl", "Lidl", "L", 0xFF0050AA.toInt(), false, listOf("lidl")),
        NetworkBadge("totalenergies", "TotalEnergies", "TE", 0xFFED0000.toInt(), false, listOf("totalenergies", "total energies")),
        NetworkBadge("shell", "Shell Recharge", "S", 0xFFFBCE07.toInt(), true, listOf("shell")),
        NetworkBadge("bp", "bp pulse / Aral", "bp", 0xFF009900.toInt(), false, listOf("bp pulse", "aral pulse", "aral", "bp europa")),
        NetworkBadge("plenoil", "Plenoil", "PL", 0xFF1E9E3E.toInt(), false, listOf("plenoil")),
        NetworkBadge("acciona", "Acciona", "AC", 0xFFE2001A.toInt(), false, listOf("acciona")),
        NetworkBadge("edp", "EDP", "EDP", 0xFFE32119.toInt(), false, listOf("edp")),
        NetworkBadge("enbw", "EnBW", "EB", 0xFF1E50A0.toInt(), false, listOf("enbw")),
        NetworkBadge("mercedes", "Mercedes-Benz", "MB", 0xFF5B6770.toInt(), false, listOf("mercedes benz high power", "mercedes benz charging", "mercedes benz hpc"), 300.0),
    )

    private val NETWORK_BY_KEY = NETWORKS.associateBy { it.key }
    const val OTHER_NETWORK = "other"

    data class Charger(
        val id: String,
        val name: String,
        val detail: String,
        val lat: Double,
        val lon: Double,
        val kmAlong: Double,
        val distFromRouteKm: Double,
        val maxKw: Double,
        val kwSource: Int = KW_TAGGED,
        val acOnly: Boolean = false,
        val network: String = OTHER_NETWORK,
        val maxVolts: Double = 0.0,
        val points: Int = 1,
        val source: Int = SOURCE_OSM,
    ) {
        val stableKey: String
            get() = id.ifEmpty { String.format(Locale.US, "%.4f,%.4f", lat, lon) }
    }

    data class Plan(
        val originName: String,
        val originIsRealGps: Boolean,
        val originLat: Double,
        val originLon: Double,
        val destination: String,
        val destDetail: String,
        val destLat: Double,
        val destLon: Double,
        val n: Int,
        val lat: DoubleArray,
        val lon: DoubleArray,
        val km: DoubleArray,
        val elev: DoubleArray,
        val headwind: DoubleArray,
        val temp: DoubleArray,
        val segS: DoubleArray,
        val kwhCum: DoubleArray,
        val gravCum: DoubleArray,
        val totalKm: Double,
        val totalSeconds: Long,
        val flatElevation: Boolean,
        val destTemp: Double,
        val destWind: Double,
        val destRain: Double,
        val destCode: Int,
        val chargers: List<Charger>,
        val dgtCount: Int,
        val progressIdx: Int = 0,
        val waypoints: List<Place> = emptyList(),
        val waypointKm: DoubleArray = DoubleArray(0),
        val roundTrip: Boolean = false,
        val chargersLoading: Boolean = false,
        val speedCapKmh: Int = 0,
        val cappedKwhCum: DoubleArray? = null,
        val cappedTotalSeconds: Long = 0L,
        val builtAtMs: Long = System.currentTimeMillis(),
    ) {
        val activeKwhCum: DoubleArray
            get() = if (speedCapKmh > 0 && cappedKwhCum != null && cappedKwhCum.size == n) cappedKwhCum else kwhCum

        val activeTotalSeconds: Long
            get() = if (speedCapKmh > 0 && cappedTotalSeconds > 0L) cappedTotalSeconds else totalSeconds

        val currentKm: Double
            get() = if (n > 0) km[progressIdx.coerceIn(0, n - 1)] else 0.0

        val remainingKm: Double
            get() = max(0.0, totalKm - currentKm)

        val remainingSeconds: Long
            get() = if (totalKm > 0.1) (activeTotalSeconds * (remainingKm / totalKm)).roundToInt().toLong() else activeTotalSeconds

        val remainingKwh: Double
            get() {
                val arr = activeKwhCum
                return if (n >= 2) max(0.0, arr[n - 1] - arr[progressIdx.coerceIn(0, n - 1)]) else 0.0
            }
    }

    data class ChargeSettings(
        var arriveMinPct: Double = 15.0,
        var stopMinPct: Double = 10.0,
        var maxChargePct: Double = 80.0,
        var carPeakKw: Double = 84.0,
        var capacityKwh: Double = 69.9,
        var carVolts: Double = 400.0,
        var favorites: Set<String> = emptySet(),
        var pinnedKeys: List<String> = emptyList(),
        var fastChargePriceEur: Double = 0.45,
    )

    data class ChargeStop(
        val charger: Charger,
        val km: Double,
        val arrivePct: Double,
        val departPct: Double,
        val minutes: Double,
        val kwh: Double,
        val isPinned: Boolean = false,
    )

    enum class Outcome {
        NO_STOPS,
        PLANNED,
        NO_CHARGER,
    }

    data class ChargeResult(
        val outcome: Outcome,
        val stops: List<ChargeStop>,
        val arrivalPct: Double,
        val chargeMinutes: Double,
        val km: DoubleArray,
        val kwhCum: DoubleArray,
        val fromKm: Double,
        val socNow: Double,
        val capacityKwh: Double,
        val carVolts: Double,
        val totalCostEur: Double,
    ) {
        fun pctAt(xKm: Double): Double {
            var cur = fromKm
            var soc = socNow
            for (s in stops) {
                if (s.km > xKm) break
                soc = s.departPct
                cur = s.km
            }
            return soc - (kwhAt(km, kwhCum, xKm) - kwhAt(km, kwhCum, cur)) / capacityKwh * 100.0
        }
    }

    data class StopAlternative(
        val charger: Charger,
        val arrivePct: Double,
        val plan: ChargeResult,
        val deltaMin: Double,
        val current: Boolean,
    ) {
        val works: Boolean
            get() = plan.outcome != Outcome.NO_CHARGER
    }

    // -------------------------------------------------------------------------
    // Clasificación y filtrado de cargadores (idéntico a ChargerFilter de Qonecta+)
    // -------------------------------------------------------------------------

    fun norm(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val n = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
        return n.replace("[^a-z0-9]+".toRegex(), " ").trim()
    }

    fun classifyNetwork(brand: String?, network: String?, operator: String?, name: String?): String {
        for (field in arrayOf(brand, network, operator, name)) {
            val n = " ${norm(field)} "
            if (n.isBlank()) continue
            for (spec in NETWORKS) {
                for (tok in spec.matchTokens) {
                    val match = if (tok.startsWith("=")) {
                        n.trim() == tok.substring(1)
                    } else {
                        n.contains(" $tok ")
                    }
                    if (match) return spec.key
                }
            }
        }
        return OTHER_NETWORK
    }

    fun networkLabel(key: String?): String {
        if (key.isNullOrEmpty() || key == OTHER_NETWORK) return "Otras redes"
        return NETWORK_BY_KEY[key]?.label ?: key
    }

    fun networkBadge(key: String?): NetworkBadge? {
        if (key.isNullOrEmpty() || key == OTHER_NETWORK) return null
        return NETWORK_BY_KEY[key]
    }

    fun typicalVolts(network: String, name: String?, sockets: Int): Double {
        if ((sockets and SOCKET_DC) == 0 && sockets != 0) return 0.0
        if (network == "tesla") {
            val n = name?.lowercase(Locale.ROOT).orEmpty()
            return if (n.contains("destination")) 0.0 else 500.0
        }
        if (network == "ionity" || network == "zunder" || network == "fastned" || network == "electra") return 800.0
        return 0.0
    }

    fun estimateKw(network: String, name: String?, sockets: Int): Pair<Double, Int> {
        val dc = (sockets and SOCKET_DC) != 0
        val ac = (sockets and SOCKET_AC) != 0
        val n = name?.lowercase(Locale.ROOT).orEmpty()
        if (network == "tesla") {
            return if (n.contains("destination") || (ac && !dc)) 11.0 to KW_NETWORK else 150.0 to KW_NETWORK
        }
        val typical = NETWORK_BY_KEY[network]?.fastOnlyTypicalKw
        if (typical != null) return typical to KW_NETWORK
        if (dc) return 50.0 to KW_SOCKETS
        if (ac) return 22.0 to KW_SOCKETS
        return 0.0 to KW_UNKNOWN
    }

    fun acceptsCharger(
        c: Charger,
        minKw: Int,
        voltsFilter: Int,
        networks: Set<String>,
        includeUnknown: Boolean = true,
        bannedKeys: Set<String> = emptySet(),
    ): Boolean {
        if (bannedKeys.contains(c.stableKey)) return false
        val netKey = c.network.ifEmpty { OTHER_NETWORK }
        val netOk = networks.isEmpty() || networks.contains(netKey)
        if (!netOk) return false
        if (!voltsOk(c.acOnly, c.maxVolts, c.maxKw, c.network, voltsFilter, includeUnknown)) return false
        if (minKw <= 0) return true
        if (c.kwSource == KW_TAGGED || c.kwSource == KW_NETWORK || c.acOnly) {
            return c.maxKw >= minKw
        }
        return includeUnknown
    }

    private fun voltsOk(
        acOnly: Boolean,
        maxVolts: Double,
        maxKw: Double,
        network: String,
        filter: Int,
        includeUnknown: Boolean,
    ): Boolean {
        if (filter == VOLTS_ANY) return true
        if (acOnly) return false
        if (maxVolts <= 0.0) {
            if (filter == VOLTS_800 && maxKw >= GOOD_KW && network != "tesla") return true
            return includeUnknown
        }
        return if (filter == VOLTS_800) maxVolts >= HIGH_VOLTS || (maxKw >= GOOD_KW && network != "tesla")
        else maxVolts < HIGH_VOLTS
    }

    fun kwText(c: Charger): String {
        if (c.maxKw <= 0.0) return "Potencia sin confirmar"
        val prefix = if (c.kwSource == KW_TAGGED) "" else "≈"
        val voltsStr = if (c.maxVolts >= HIGH_VOLTS) " · 800 V" else if (c.maxVolts >= 380.0) " · 400 V" else ""
        return String.format(Locale.getDefault(), "%s%.0f kW%s", prefix, c.maxKw, voltsStr)
    }

    // -------------------------------------------------------------------------
    // Ubicación real del coche (GPS / Red / Origen manual)
    // -------------------------------------------------------------------------

    private var customOrigin: Place? = null

    fun getCustomOrigin(): Place? = customOrigin

    fun setCustomOrigin(place: Place?) {
        customOrigin = place
    }

    @SuppressLint("MissingPermission")
    fun resolveOrigin(context: Context): Pair<Place, Boolean> {
        customOrigin?.let { return it to false }
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null) {
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER,
                )
                var best: Location? = null
                for (p in providers) {
                    val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
                    if (loc.latitude == 0.0 && loc.longitude == 0.0) continue
                    if (best == null || loc.time > best.time) {
                        best = loc
                    }
                }
                if (best != null) {
                    return Place(
                        name = "Posición GPS actual",
                        detail = String.format(Locale.US, "%.4f, %.4f", best.latitude, best.longitude),
                        lat = best.latitude,
                        lon = best.longitude,
                    ) to true
                }
            }
        } catch (_: Exception) {
        }
        // Si estamos en el emulador o dentro de un garaje sin fix GPS aún, miramos si el usuario configuró «Casa»
        val prefs = context.getSharedPreferences("qonecta_extended_prefs", Context.MODE_PRIVATE)
        val homeLat = prefs.getFloat("fav_lat_home", Float.NaN)
        val homeLon = prefs.getFloat("fav_lon_home", Float.NaN)
        val homeAddr = prefs.getString("fav_addr_home", "").orEmpty()
        if (!homeLat.isNaN() && !homeLon.isNaN() && homeAddr.isNotBlank()) {
            return Place(
                name = "Casa (${homeAddr.split(",").firstOrNull()?.trim().orEmpty()})",
                detail = "Sin señal GPS · saliendo desde Casa",
                lat = homeLat.toDouble(),
                lon = homeLon.toDouble(),
            ) to false
        }
        return Place(
            name = "Madrid (Km 0 · Sin señal GPS)",
            detail = "Activa el GPS o pulsa «Desde: …» para elegir salida",
            lat = 40.4168,
            lon = -3.7038,
        ) to false
    }

    // -------------------------------------------------------------------------
    // Geocodificación real en paralelo (Índice España instantáneo + Geocoder + Photon + Nominatim) y Recientes
    // -------------------------------------------------------------------------

    private val INSTANT_SPAIN_PLACES: List<Place> = listOf(
        Place("Madrid", "Comunidad de Madrid, España", 40.4168, -3.7038),
        Place("Barcelona", "Cataluña, España", 41.3874, 2.1686),
        Place("Valencia", "Comunidad Valenciana, España", 39.4699, -0.3763),
        Place("Sevilla", "Andalucía, España", 37.3891, -5.9845),
        Place("Zaragoza", "Aragón, España", 41.6488, -0.8891),
        Place("Málaga", "Andalucía, España", 36.7213, -4.4214),
        Place("Murcia", "Región de Murcia, España", 37.9922, -1.1307),
        Place("Bilbao", "Vizcaya, País Vasco, España", 43.2630, -2.9350),
        Place("Alicante", "Comunidad Valenciana, España", 38.3452, -0.4810),
        Place("Córdoba", "Andalucía, España", 37.8882, -4.7794),
        Place("Valladolid", "Castilla y León, España", 41.6523, -4.7245),
        Place("Vigo", "Pontevedra, Galicia, España", 42.2406, -8.7207),
        Place("Gijón", "Asturias, España", 43.5322, -5.6611),
        Place("Vitoria-Gasteiz", "Álava, País Vasco, España", 42.8467, -2.6716),
        Place("A Coruña", "Galicia, España", 43.3623, -8.4115),
        Place("Granada", "Andalucía, España", 37.1773, -3.5986),
        Place("Elche", "Alicante, España", 38.2699, -0.7126),
        Place("Oviedo", "Asturias, España", 43.3619, -5.8494),
        Place("Cartagena", "Región de Murcia, España", 37.6257, -0.9966),
        Place("Jerez de la Frontera", "Cádiz, Andalucía, España", 36.6850, -6.1261),
        Place("Pamplona", "Navarra, España", 42.8125, -1.6458),
        Place("Almería", "Andalucía, España", 36.8340, -2.4637),
        Place("San Sebastián / Donostia", "Guipúzcoa, País Vasco, España", 43.3183, -1.9812),
        Place("Burgos", "Castilla y León, España", 42.3440, -3.6969),
        Place("Albacete", "Castilla-La Mancha, España", 38.9943, -1.8585),
        Place("Santander", "Cantabria, España", 43.4623, -3.8100),
        Place("Castellón de la Plana", "Comunidad Valenciana, España", 39.9864, -0.0513),
        Place("Logroño", "La Rioja, España", 42.4627, -2.4450),
        Place("Badajoz", "Extremadura, España", 38.8794, -6.9707),
        Place("Salamanca", "Castilla y León, España", 40.9701, -5.6635),
        Place("Huelva", "Andalucía, España", 37.2614, -6.9447),
        Place("Lleida", "Cataluña, España", 41.6176, 0.6200),
        Place("Tarragona", "Cataluña, España", 41.1189, 1.2445),
        Place("León", "Castilla y León, España", 42.5987, -5.5671),
        Place("Cádiz", "Andalucía, España", 36.5271, -6.2886),
        Place("Jaén", "Andalucía, España", 37.7796, -3.7849),
        Place("Ourense", "Galicia, España", 42.3358, -7.8639),
        Place("Lugo", "Galicia, España", 43.0097, -7.5568),
        Place("Girona", "Cataluña, España", 41.9794, 2.8214),
        Place("Santiago de Compostela", "A Coruña, Galicia, España", 42.8782, -8.5448),
        Place("Cáceres", "Extremadura, España", 39.4753, -6.3724),
        Place("Melilla", "España", 35.2923, -2.9381),
        Place("Ceuta", "España", 35.8894, -5.3213),
        Place("Guadalajara", "Castilla-La Mancha, España", 40.6337, -3.1674),
        Place("Toledo", "Castilla-La Mancha, España", 39.8628, -4.0273),
        Place("Pontevedra", "Galicia, España", 42.4310, -8.6444),
        Place("Palencia", "Castilla y León, España", 42.0096, -4.5288),
        Place("Ciudad Real", "Castilla-La Mancha, España", 38.9848, -3.9274),
        Place("Zamora", "Castilla y León, España", 41.5035, -5.7468),
        Place("Ávila", "Castilla y León, España", 40.6567, -4.6812),
        Place("Cuenca", "Castilla-La Mancha, España", 40.0704, -2.1374),
        Place("Huesca", "Aragón, España", 42.1401, -0.4089),
        Place("Segovia", "Castilla y León, España", 40.9429, -4.1088),
        Place("Soria", "Castilla y León, España", 41.7666, -2.4790),
        Place("Teruel", "Aragón, España", 40.3456, -1.1065),
        Place("Benidorm", "Alicante, España", 38.5411, -0.1225),
        Place("Marbella", "Málaga, Andalucía, España", 36.5101, -4.8824),
        Place("Mérida", "Badajoz, Extremadura, España", 38.9161, -6.3437),
        Place("Aeropuerto Adolfo Suárez Madrid-Barajas (T4)", "Madrid, España", 40.4918, -3.5936),
        Place("Aeropuerto Josep Tarradellas Barcelona-El Prat", "Barcelona, España", 41.2974, 2.0833),
        Place("Estación Madrid Puerta de Atocha", "Madrid, España", 40.4065, -3.6896),
        Place("Estación Madrid Chamartín-Clara Campoamor", "Madrid, España", 40.4720, -3.6826),
        Place("Lisboa", "Portugal", 38.7223, -9.1393),
        Place("Oporto / Porto", "Portugal", 41.1579, -8.6291),
        Place("Andorra la Vella", "Andorra", 42.5063, 1.5218),
    )

    fun searchInstantLocalPlaces(query: String, limit: Int = 5): List<Place> {
        val qNorm = norm(query)
        if (qNorm.length < 2) return emptyList()
        val starts = mutableListOf<Place>()
        val contains = mutableListOf<Place>()
        for (p in INSTANT_SPAIN_PLACES) {
            val nName = norm(p.name)
            val nDet = norm(p.detail)
            if (nName.startsWith(qNorm)) {
                starts.add(p)
            } else if (nName.contains(qNorm) || nDet.startsWith(qNorm)) {
                contains.add(p)
            }
        }
        return (starts + contains).take(limit)
    }

    fun searchPlaces(
        query: String,
        nearLat: Double = Double.NaN,
        nearLon: Double = Double.NaN,
        limit: Int = 6,
        context: Context? = null,
    ): List<Place> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val localMatches = searchInstantLocalPlaces(q, limit = 3)
        val geocoderList = Collections.synchronizedList(mutableListOf<Place>())
        val photonList = Collections.synchronizedList(mutableListOf<Place>())

        val latch = java.util.concurrent.CountDownLatch(if (context != null) 2 else 1)

        // 1. Android Geocoder en paralelo (igual que PlaceSearch.java de Qonecta+)
        if (context != null) {
            Thread({
                try {
                    if (android.location.Geocoder.isPresent()) {
                        val geo = android.location.Geocoder(context, Locale("es", "ES"))
                        @Suppress("DEPRECATION")
                        val addrs = geo.getFromLocationName(q, limit)
                        if (addrs != null) {
                            for (a in addrs) {
                                if (!a.hasLatitude() || !a.hasLongitude()) continue
                                val street = listOfNotNull(
                                    a.thoroughfare?.takeIf { it.isNotBlank() },
                                    a.subThoroughfare?.takeIf { it.isNotBlank() },
                                ).joinToString(" ")
                                val feature = a.featureName?.takeIf {
                                    it.isNotBlank() && !it.matches("\\d+".toRegex())
                                }
                                val locality = a.locality ?: a.subAdminArea ?: ""
                                val admin = a.adminArea ?: ""
                                val country = a.countryName ?: ""
                                val title = when {
                                    street.isNotBlank() -> street
                                    !feature.isNullOrBlank() -> feature
                                    locality.isNotBlank() -> locality
                                    else -> q
                                }
                                val detParts = listOfNotNull(
                                    locality.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) },
                                    admin.takeIf { it.isNotBlank() && !it.equals(locality, ignoreCase = true) && !it.equals(title, ignoreCase = true) },
                                    country.takeIf { it.isNotBlank() },
                                )
                                geocoderList.add(Place(name = title, detail = detParts.joinToString(" · "), lat = a.latitude, lon = a.longitude))
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    latch.countDown()
                }
            }, "unit-geocoder").start()
        }

        // 2. Photon (Komoot, basado en OpenStreetMap) en paralelo
        Thread({
            try {
                val bias = if (!nearLat.isNaN() && !nearLon.isNaN()) {
                    String.format(Locale.US, "&lat=%.4f&lon=%.4f", nearLat, nearLon)
                } else {
                    ""
                }
                val url = "https://photon.komoot.io/api/?limit=$limit&lang=es$bias&q=${Uri.encode(q)}"
                val json = JSONObject(httpGet(url, 3_500))
                val features = json.optJSONArray("features") ?: JSONArray()
                for (i in 0 until features.length()) {
                    val f = features.getJSONObject(i)
                    val geom = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                    val lon = geom.optDouble(0, Double.NaN)
                    val lat = geom.optDouble(1, Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) continue
                    val props = f.optJSONObject("properties") ?: JSONObject()
                    val streetName = props.optString("street")
                    val houseNum = props.optString("housenumber")
                    val streetFull = if (streetName.isNotBlank() && houseNum.isNotBlank()) "$streetName $houseNum" else streetName
                    val name = props.optString("name").ifBlank {
                        streetFull.ifBlank { props.optString("city").ifBlank { q } }
                    }
                    val city = props.optString("city").ifBlank { props.optString("town").ifBlank { props.optString("village") } }
                    val state = props.optString("state")
                    val country = props.optString("country")
                    val detailParts = listOfNotNull(
                        streetFull.takeIf { it.isNotBlank() && it != name },
                        city.takeIf { it.isNotBlank() && it != name },
                        state.takeIf { it.isNotBlank() && it != city },
                        country.takeIf { it.isNotBlank() },
                    )
                    photonList.add(Place(name = name, detail = detailParts.joinToString(" · "), lat = lat, lon = lon))
                }
            } catch (_: Exception) {
            } finally {
                latch.countDown()
            }
        }, "unit-photon").start()

        runCatching { latch.await(3_800, java.util.concurrent.TimeUnit.MILLISECONDS) }

        val combined = mutableListOf<Place>()
        combined.addAll(geocoderList)
        combined.addAll(photonList)
        combined.addAll(localMatches)
        if (combined.isNotEmpty()) return deduplicatePlaces(combined, limit)

        // 3. Reserva con Nominatim si ni Geocoder ni Photon respondieron
        runCatching {
            val viewbox = if (!nearLat.isNaN() && !nearLon.isNaN()) {
                String.format(Locale.US, "&viewbox=%.3f,%.3f,%.3f,%.3f", nearLon - 2.5, nearLat + 2.5, nearLon + 2.5, nearLat - 2.5)
            } else {
                ""
            }
            val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=$limit&addressdetails=1$viewbox&q=${Uri.encode(q)}"
            val arr = JSONArray(httpGet(url, 4_500))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val display = o.optString("display_name", q)
                val parts = display.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val name = o.optString("name").ifBlank { parts.firstOrNull() ?: q }
                val detail = parts.drop(1).take(3).joinToString(" · ")
                combined.add(Place(name = name, detail = detail, lat = lat, lon = lon))
            }
        }
        return deduplicatePlaces(combined, limit)
    }

    /**
     * Nombre de la posición actual (calle y municipio) para «📍 Guardar aquí» (`PlaceSearch.hereName` de Qonecta+).
     */
    fun reverseGeocode(context: Context, lat: Double, lon: Double): Place {
        val fallback = Place(
            name = "Posición guardada",
            detail = String.format(Locale.US, "%.4f, %.4f", lat, lon),
            lat = lat,
            lon = lon,
        )
        runCatching {
            if (android.location.Geocoder.isPresent()) {
                val geo = android.location.Geocoder(context, Locale("es", "ES"))
                @Suppress("DEPRECATION")
                val list = geo.getFromLocation(lat, lon, 1)
                val a = list?.firstOrNull()
                if (a != null) {
                    val street = listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ").trim()
                    val town = a.locality ?: a.subAdminArea ?: ""
                    val prov = a.adminArea ?: ""
                    val name = street.ifBlank { town.ifBlank { a.featureName ?: "Posición guardada" } }
                    val det = listOfNotNull(
                        town.takeIf { it.isNotBlank() && it != name },
                        prov.takeIf { it.isNotBlank() && it != town },
                    ).joinToString(" · ")
                    return Place(name = name, detail = det, lat = lat, lon = lon)
                }
            }
        }
        return fallback
    }

    private const val MAX_RECENTS = 8

    fun loadRecentPlaces(context: Context): List<Place> {
        val prefs = context.getSharedPreferences("qonecta_extended_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("route_recent_places_json", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = mutableListOf<Place>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val la = o.optDouble("la", Double.NaN)
                val lo = o.optDouble("lo", Double.NaN)
                if (la.isNaN() || lo.isNaN()) continue
                out.add(Place(name = o.optString("n", ""), detail = o.optString("d", ""), lat = la, lon = lo))
            }
            out
        }.getOrDefault(emptyList())
    }

    fun saveRecentPlace(context: Context, place: Place) {
        if (place.name.isBlank() || place.lat.isNaN() || place.lon.isNaN()) return
        val prefs = context.getSharedPreferences("qonecta_extended_prefs", Context.MODE_PRIVATE)
        val current = loadRecentPlaces(context).toMutableList()
        current.removeAll { distanceKm(it.lat, it.lon, place.lat, place.lon) < 0.2 || it.name.equals(place.name, ignoreCase = true) }
        current.add(0, place)
        val trimmed = current.take(MAX_RECENTS)
        val arr = JSONArray()
        for (p in trimmed) {
            arr.put(
                JSONObject()
                    .put("n", p.name)
                    .put("d", p.detail)
                    .put("la", p.lat)
                    .put("lo", p.lon),
            )
        }
        prefs.edit().putString("route_recent_places_json", arr.toString()).apply()
    }

    private fun deduplicatePlaces(list: List<Place>, limit: Int): List<Place> {
        val out = mutableListOf<Place>()
        for (p in list) {
            if (out.none { distanceKm(it.lat, it.lon, p.lat, p.lon) < 0.20 && norm(it.name) == norm(p.name) }) {
                out.add(p)
                if (out.size >= limit) break
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Construcción ultrarrápida del Plan de Ruta (OSRM + Altitud/Clima en paralelo + Cargadores no bloqueantes)
    // -------------------------------------------------------------------------

    fun buildRoutePlan(
        context: Context,
        destinationQueryOrPlace: Place,
        viaPlaces: List<Place> = emptyList(),
        roundTrip: Boolean = false,
        onChargersUpdated: ((Plan) -> Unit)? = null,
    ): Plan {
        val appCtx = context.applicationContext
        val (origin, isRealGps) = resolveOrigin(appCtx)
        val wps = mutableListOf<Place>()
        wps.addAll(viaPlaces)
        wps.add(destinationQueryOrPlace)
        if (roundTrip) {
            wps.add(origin)
        }

        // 1. OSRM route (timeout rápido de 6,5 s; si OSRM tarda o cae, trazado directo geodésico con curvatura de autovía 1,18x)
        val coordsBuilder = StringBuilder(String.format(Locale.US, "%.6f,%.6f", origin.lon, origin.lat))
        for (w in wps) {
            coordsBuilder.append(String.format(Locale.US, ";%.6f,%.6f", w.lon, w.lat))
        }
        val osrmUrl = "https://router.project-osrm.org/route/v1/driving/$coordsBuilder" +
            "?overview=full&geometries=geojson&annotations=distance,duration&steps=false&alternatives=false"

        var totalSeconds = 0L
        var waypointKm: DoubleArray = DoubleArray(0)
        val pts = ArrayList<DoubleArray>()

        try {
            val osrmBody = httpGet(osrmUrl, 6_500)
            val routes = JSONObject(osrmBody).getJSONArray("routes")
            if (routes.length() == 0) throw IllegalStateException("Sin ruta OSRM")
            val route = routes.getJSONObject(0)
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            val (distArr, durArr) = extractAnnotations(route, coords)
            val totalM = route.getDouble("distance")
            totalSeconds = route.getDouble("duration").roundToInt().toLong()

            val legs = route.optJSONArray("legs")
            waypointKm = if (legs != null && legs.length() > 0) {
                val out = DoubleArray(legs.length())
                var acc = 0.0
                for (i in 0 until legs.length()) {
                    acc += legs.getJSONObject(i).optDouble("distance", 0.0) / 1000.0
                    out[i] = acc
                }
                out
            } else {
                doubleArrayOf(totalM / 1000.0)
            }

            val stepM = max(200.0, totalM / 360.0)
            val c0 = coords.getJSONArray(0)
            pts.add(doubleArrayOf(c0.getDouble(1), c0.getDouble(0), 0.0, 0.0))
            var accM = 0.0
            var accS = 0.0
            var kmAcc = 0.0
            for (i in 1 until coords.length()) {
                accM += distArr[i - 1]
                accS += durArr[i - 1]
                if (accM >= stepM || i == coords.length() - 1) {
                    kmAcc += accM / 1000.0
                    val ci = coords.getJSONArray(i)
                    pts.add(doubleArrayOf(ci.getDouble(1), ci.getDouble(0), kmAcc, accS))
                    accM = 0.0
                    accS = 0.0
                }
            }
        } catch (_: Exception) {
            // Reserva instantánea si no hay conexión con OSRM
            val allNodes = listOf(origin) + wps
            waypointKm = DoubleArray(wps.size)
            var kmAcc = 0.0
            pts.add(doubleArrayOf(origin.lat, origin.lon, 0.0, 0.0))
            for (legIdx in 0 until wps.size) {
                val a = allNodes[legIdx]
                val b = allNodes[legIdx + 1]
                val roadKm = max(1.0, distanceKm(a.lat, a.lon, b.lat, b.lon) * 1.18)
                val steps = (roadKm / 2.0).roundToInt().coerceIn(20, 120)
                val stepKm = roadKm / steps
                val stepSec = (stepKm / 105.0) * 3600.0
                for (s in 1..steps) {
                    val f = s.toDouble() / steps
                    kmAcc += stepKm
                    totalSeconds += stepSec.roundToInt()
                    pts.add(doubleArrayOf(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, kmAcc, stepSec))
                }
                waypointKm[legIdx] = kmAcc
            }
        }

        val n = pts.size
        val lat = DoubleArray(n)
        val lon = DoubleArray(n)
        val km = DoubleArray(n)
        val segS = DoubleArray(n)
        for (i in 0 until n) {
            val q = pts[i]
            lat[i] = q[0]
            lon[i] = q[1]
            km[i] = q[2]
            segS[i] = q[3]
        }
        val totalKm = km[n - 1]

        // 2 & 3. Altitud real (100 muestras en 1 sola petición) y Clima/Viento EN PARALELO (máx 3,5 s en total)
        val etaS = DoubleArray(n)
        for (i in 1 until n) etaS[i] = etaS[i - 1] + segS[i]
        val wxSamples = min(6, n)
        val sampleIdx = sampleIndices(n, wxSamples)

        var rawElev = DoubleArray(n) { 600.0 }
        var flatElev = true
        var wx = Array(wxSamples) { doubleArrayOf(0.0, 0.0, 16.0, 0.0, -1.0) }

        val envLatch = java.util.concurrent.CountDownLatch(2)
        Thread({
            try {
                val res = fetchElevations(lat, lon, km)
                rawElev = res.first
                flatElev = res.second
            } catch (_: Exception) {
            } finally {
                envLatch.countDown()
            }
        }, "unit-route-elev").start()

        Thread({
            try {
                wx = fetchWeatherAlongRoute(lat, lon, sampleIdx, etaS)
            } catch (_: Exception) {
            } finally {
                envLatch.countDown()
            }
        }, "unit-route-wx").start()

        runCatching { envLatch.await(3_500, java.util.concurrent.TimeUnit.MILLISECONDS) }

        val elev = DoubleArray(n)
        for (i in 0 until n) {
            var sum = 0.0
            var count = 0
            for (k in max(0, i - 2)..min(n - 1, i + 2)) {
                sum += rawElev[k]
                count++
            }
            elev[i] = sum / max(1, count)
        }

        val headwind = DoubleArray(n)
        val temp = DoubleArray(n)
        for (i in 0 until n) {
            val j = min(wxSamples - 1, ((i.toFloat() * (wxSamples - 1)) / max(1, n - 1)).roundToInt())
            val w = wx[j]
            val brg = if (i == 0) bearing(lat, lon, 0, min(1, n - 1)) else bearing(lat, lon, i - 1, i)
            headwind[i] = w[0] * cos(Math.toRadians(w[1] - brg))
            temp[i] = w[2]
        }
        val lastWx = wx[wxSamples - 1]
        val destWind = lastWx[0]
        val destTemp = lastWx[2]
        val destRain = lastWx[3]
        val destCode = lastWx[4].toInt()

        // 4. Modelo físico de energía del Leapmotor C10 por tramo (conducción + gravedad en pendientes)
        val variant = LeapVehicleDataSource.getSelectedVariant(appCtx)
        val kwhCum = DoubleArray(n)
        val gravCum = DoubleArray(n)
        for (i in 1 until n) {
            val dkm = km[i] - km[i - 1]
            val s = max(1.0, segS[i])
            val vKmh = (dkm / s * 3600.0).coerceIn(5.0, 140.0)
            val gradePct = if (dkm > 0.0) ((elev[i] - elev[i - 1]) / (dkm * 1000.0) * 100.0).coerceIn(-15.0, 15.0) else 0.0
            val hours = s / 3600.0
            val kwWithGrade = computeC10PowerKw(variant, vKmh, gradePct, headwind[i], temp[i])
            val kwFlat = computeC10PowerKw(variant, vKmh, 0.0, headwind[i], temp[i])
            val driveKwh = kwFlat * hours + stopAndGoKwh(vKmh, dkm)
            val hillsKwh = (kwWithGrade - kwFlat) * hours
            kwhCum[i] = kwhCum[i - 1] + driveKwh + hillsKwh
            gravCum[i] = gravCum[i - 1] + hillsKwh
        }

        // 5. Cargadores inmediatos en memoria/disco (< 5 ms, sin bloquear con Overpass) + completado en segundo plano
        val initialChargersResult = findChargersAlongRoute(appCtx, lat, lon, km, fetchMissingOverpass = false)
        val needsBgChargers = hasMissingRouteTiles(appCtx, lat, lon, km) || (nearSpain(lat, lon) && DgtRegistry.shouldRefresh(appCtx))

        val plan = Plan(
            originName = origin.name,
            originIsRealGps = isRealGps,
            originLat = origin.lat,
            originLon = origin.lon,
            destination = destinationQueryOrPlace.name,
            destDetail = destinationQueryOrPlace.detail,
            destLat = destinationQueryOrPlace.lat,
            destLon = destinationQueryOrPlace.lon,
            n = n,
            lat = lat,
            lon = lon,
            km = km,
            elev = elev,
            headwind = headwind,
            temp = temp,
            segS = segS,
            kwhCum = kwhCum,
            gravCum = gravCum,
            totalKm = totalKm,
            totalSeconds = totalSeconds,
            flatElevation = flatElev,
            destTemp = destTemp,
            destWind = destWind,
            destRain = destRain,
            destCode = destCode,
            chargers = initialChargersResult.first,
            dgtCount = initialChargersResult.second,
            progressIdx = 0,
            waypoints = wps,
            waypointKm = waypointKm,
            roundTrip = roundTrip,
            chargersLoading = needsBgChargers,
        )

        if (needsBgChargers && onChargersUpdated != null) {
            Thread({
                var changed = false
                if (nearSpain(lat, lon) && DgtRegistry.shouldRefresh(appCtx)) {
                    if (DgtRegistry.refresh(appCtx)) changed = true
                }
                val afterBg = findChargersAlongRoute(appCtx, lat, lon, km, fetchMissingOverpass = true)
                if (changed || afterBg.first.size != plan.chargers.size) {
                    onChargersUpdated.invoke(
                        plan.copy(
                            chargers = afterBg.first,
                            dgtCount = afterBg.second,
                            chargersLoading = false,
                        ),
                    )
                } else {
                    onChargersUpdated.invoke(plan.copy(chargersLoading = false))
                }
            }, "unit-route-chargers-bg").apply {
                priority = Thread.MIN_PRIORITY
                start()
            }
        }

        return plan
    }

    /**
     * Recalcula el consumo acumulado y la duración limitando la velocidad máxima de los tramos rápidos a `capKmh`
     * (idéntico a `energyForCap` / `applySpeedCap` de `RoutePlanner.java` y `SpeedAdvice.java`).
     */
    fun computeSpeedCapEnergy(context: Context, plan: Plan, capKmh: Int): Pair<DoubleArray, Long> {
        if (capKmh <= 0 || plan.n < 2) return plan.kwhCum to plan.totalSeconds
        val variant = LeapVehicleDataSource.getSelectedVariant(context)
        val outKwh = DoubleArray(plan.n)
        var extraSec = 0.0
        val prog = plan.progressIdx.coerceIn(0, plan.n - 1)
        for (i in 1 until plan.n) {
            val dkm = plan.km[i] - plan.km[i - 1]
            val origS = max(1.0, plan.segS[i])
            val origV = (dkm / origS * 3600.0).coerceIn(5.0, 140.0)
            if (i <= prog || origV <= capKmh) {
                outKwh[i] = outKwh[i - 1] + (plan.kwhCum[i] - plan.kwhCum[i - 1])
                continue
            }
            val cappedV = capKmh.toDouble()
            val cappedS = dkm / cappedV * 3600.0
            extraSec += (cappedS - origS)
            val gradePct = if (dkm > 0.0) ((plan.elev[i] - plan.elev[i - 1]) / (dkm * 1000.0) * 100.0).coerceIn(-15.0, 15.0) else 0.0
            val hours = cappedS / 3600.0
            val kwWithGrade = computeC10PowerKw(variant, cappedV, gradePct, plan.headwind[i], plan.temp[i])
            val kwFlat = computeC10PowerKw(variant, cappedV, 0.0, plan.headwind[i], plan.temp[i])
            val driveKwh = kwFlat * hours + stopAndGoKwh(cappedV, dkm)
            val hillsKwh = (kwWithGrade - kwFlat) * hours
            outKwh[i] = outKwh[i - 1] + driveKwh + hillsKwh
        }
        return outKwh to (plan.totalSeconds + extraSec.roundToInt())
    }

    fun applySpeedCap(context: Context, plan: Plan, capKmh: Int): Plan {
        if (capKmh <= 0) {
            return plan.copy(speedCapKmh = 0, cappedKwhCum = null, cappedTotalSeconds = 0L)
        }
        val (cappedKwh, cappedSec) = computeSpeedCapEnergy(context, plan, capKmh)
        return plan.copy(
            speedCapKmh = capKmh,
            cappedKwhCum = cappedKwh,
            cappedTotalSeconds = cappedSec,
        )
    }

    // -------------------------------------------------------------------------
    // Física de consumo por tramo del Leapmotor C10 (EnergyModel de Qonecta+)
    // -------------------------------------------------------------------------

    private const val MASS_KG = 2120.0
    private const val CD_A = 0.67
    private const val CRR = 0.0105
    private const val DRIVE_EFF = 0.89
    private const val REGEN_EFF = 0.68

    private fun computeC10PowerKw(
        variant: LeapVehicleDataSource.C10Variant,
        speedKmh: Double,
        gradePct: Double,
        headwindKmh: Double,
        tempC: Double,
    ): Double {
        val v = speedKmh / 3.6
        val vAir = (speedKmh + headwindKmh) / 3.6
        val rho = 1.225 * (288.15 / (tempC + 273.15)).coerceIn(0.9, 1.15)
        val fAero = 0.5 * rho * CD_A * vAir * abs(vAir)
        val fRoll = MASS_KG * 9.81 * CRR * (if (tempC < 10.0) 1.0 + (10.0 - tempC) * 0.008 else 1.0)
        val fGrade = MASS_KG * 9.81 * (gradePct / 100.0)
        val mechW = (fAero + fRoll + fGrade) * v
        val tractiveKw = if (mechW >= 0.0) {
            (mechW / 1000.0) / DRIVE_EFF
        } else {
            (mechW / 1000.0) * REGEN_EFF
        }
        val hvacKw = when {
            tempC < 15.0 -> 0.35 + (15.0 - tempC) * 0.09
            tempC > 24.0 -> 0.30 + (tempC - 24.0) * 0.08
            else -> 0.25
        }
        val baseElectronicsKw = 0.35
        val wltpKwh100 = (variant.batteryUsableKwh / max(100, variant.wltpEvRangeKm)) * 100.0
        val variantScale = (wltpKwh100 / 16.0).coerceIn(0.90, 1.15)
        return (tractiveKw * variantScale) + hvacKw + baseElectronicsKw
    }

    private fun stopAndGoKwh(speedKmh: Double, dkm: Double): Double {
        val perKm = when {
            speedKmh < 35.0 -> 1.5
            speedKmh < 50.0 -> 1.0
            speedKmh < 70.0 -> 0.4
            else -> 0.0
        }
        if (perKm == 0.0) return 0.0
        val v = speedKmh / 3.6
        val lost = 0.5 * MASS_KG * v * v * (1.0 / DRIVE_EFF - REGEN_EFF)
        return perKm * dkm * lost / 3.6e6
    }

    // -------------------------------------------------------------------------
    // Planificador de Carga (ChargePlanner de Qonecta+)
    // -------------------------------------------------------------------------

    fun planChargingStops(
        plan: Plan,
        socNow: Double,
        filteredChargers: List<Charger>,
        settings: ChargeSettings,
        overrideKwhCum: DoubleArray? = null,
    ): ChargeResult {
        val activeKwh = overrideKwhCum ?: plan.activeKwhCum
        if (plan.n < 2) {
            return ChargeResult(
                outcome = Outcome.NO_STOPS,
                stops = emptyList(),
                arrivalPct = socNow,
                chargeMinutes = 0.0,
                km = plan.km,
                kwhCum = activeKwh,
                fromKm = 0.0,
                socNow = socNow,
                capacityKwh = settings.capacityKwh,
                carVolts = settings.carVolts,
                totalCostEur = 0.0,
            )
        }
        val fromKm = plan.currentKm
        val aheadChargers = filteredChargers.filter { it.kmAlong > fromKm + 0.5 }
        return planStopsInternal(plan.km, activeKwh, fromKm, socNow, aheadChargers, settings)
    }

    private fun planStopsInternal(
        km: DoubleArray,
        kwhCum: DoubleArray,
        fromKm: Double,
        socNow: Double,
        chargers: List<Charger>,
        s: ChargeSettings,
    ): ChargeResult {
        val end = km[km.size - 1]
        val cap = max(10.0, s.capacityKwh)
        var cur = fromKm
        var soc = socNow
        val stops = mutableListOf<ChargeStop>()
        val preferred = preferredChargers(chargers, s)
        val pinnedSet = s.pinnedKeys.toSet()

        for (k in 0..MAX_STOPS) {
            val toEndPct = (kwhAt(km, kwhCum, end) - kwhAt(km, kwhCum, cur)) / cap * 100.0
            if (soc - toEndPct >= s.arriveMinPct - 1e-9) {
                return buildChargeResult(
                    if (stops.isEmpty()) Outcome.NO_STOPS else Outcome.PLANNED,
                    stops,
                    soc - toEndPct,
                    km,
                    kwhCum,
                    fromKm,
                    socNow,
                    s,
                )
            }
            if (k == MAX_STOPS) break

            var best = pinnedNext(km, kwhCum, cur, soc, end, chargers, pinnedSet, s)
            if (best == null && preferred !== chargers) best = pickCharger(km, kwhCum, cur, soc, end, preferred, s, s.stopMinPct)
            if (best == null) best = pickCharger(km, kwhCum, cur, soc, end, chargers, s, s.stopMinPct)
            if (best == null && preferred !== chargers) best = pickCharger(km, kwhCum, cur, soc, end, preferred, s, 3.0)
            if (best == null) best = pickCharger(km, kwhCum, cur, soc, end, chargers, s, 3.0)
            if (best == null) {
                return buildChargeResult(Outcome.NO_CHARGER, stops, soc - toEndPct, km, kwhCum, fromKm, socNow, s)
            }

            val arrive = soc - (kwhAt(km, kwhCum, best.kmAlong) - kwhAt(km, kwhCum, cur)) / cap * 100.0
            val need = (kwhAt(km, kwhCum, end) - kwhAt(km, kwhCum, best.kmAlong)) / cap * 100.0 + s.arriveMinPct + 2.0
            var top = s.maxChargePct
            if (need > top && need <= min(100.0, top + FINAL_EXTRA_PCT)) {
                top = need
            }
            var target = min(top, max(arrive, need))
            if (target <= arrive + 0.5) target = min(100.0, arrive + 5.0)
            val mins = chargeMinutes(arrive, target, best, s) + STOP_OVERHEAD_MIN
            val addedKwh = (target - arrive) / 100.0 * cap
            stops.add(
                ChargeStop(
                    charger = best,
                    km = best.kmAlong,
                    arrivePct = arrive,
                    departPct = target,
                    minutes = mins,
                    kwh = addedKwh,
                    isPinned = pinnedSet.contains(best.stableKey),
                ),
            )
            cur = best.kmAlong
            soc = target
        }

        val toEndPct = (kwhAt(km, kwhCum, end) - kwhAt(km, kwhCum, cur)) / cap * 100.0
        return buildChargeResult(Outcome.NO_CHARGER, stops, soc - toEndPct, km, kwhCum, fromKm, socNow, s)
    }

    private fun buildChargeResult(
        outcome: Outcome,
        stops: List<ChargeStop>,
        arrivalPct: Double,
        km: DoubleArray,
        kwhCum: DoubleArray,
        fromKm: Double,
        socNow: Double,
        s: ChargeSettings,
    ): ChargeResult {
        val totalMin = stops.sumOf { it.minutes }
        val totalKwh = stops.sumOf { it.kwh }
        val totalCost = (totalKwh / CHARGE_EFF) * s.fastChargePriceEur
        return ChargeResult(
            outcome = outcome,
            stops = stops,
            arrivalPct = arrivalPct,
            chargeMinutes = totalMin,
            km = km,
            kwhCum = kwhCum,
            fromKm = fromKm,
            socNow = socNow,
            capacityKwh = s.capacityKwh,
            carVolts = s.carVolts,
            totalCostEur = totalCost,
        )
    }

    fun stopAlternatives(
        currentResult: ChargeResult,
        stopIndex: Int,
        chargers: List<Charger>,
        settings: ChargeSettings,
        maxResults: Int = 10,
    ): List<StopAlternative> {
        if (stopIndex !in currentResult.stops.indices) return emptyList()
        val st = currentResult.stops[stopIndex]
        val prevKm = if (stopIndex > 0) currentResult.stops[stopIndex - 1].km else currentResult.fromKm
        val prevPct = if (stopIndex > 0) currentResult.stops[stopIndex - 1].departPct else currentResult.socNow
        val endKm = currentResult.km[currentResult.km.size - 1]
        val nextKm = if (stopIndex + 1 < currentResult.stops.size) currentResult.stops[stopIndex + 1].km else endKm

        val out = mutableListOf<StopAlternative>()
        for (c in chargers) {
            if (c.kmAlong <= prevKm + 0.5 || c.kmAlong >= nextKm - 0.5 || c.kmAlong >= endKm - 1.0) continue
            val arrive = prevPct - (kwhAt(currentResult.km, currentResult.kwhCum, c.kmAlong) - kwhAt(currentResult.km, currentResult.kwhCum, prevKm)) / currentResult.capacityKwh * 100.0
            if (arrive < PIN_MIN_PCT) continue
            val newPins = settings.pinnedKeys.filter { it != st.charger.stableKey }.toMutableList()
            if (c.stableKey != st.charger.stableKey) {
                newPins.add(c.stableKey)
            }
            val copySettings = settings.copy(pinnedKeys = newPins)
            val altPlan = planStopsInternal(
                currentResult.km,
                currentResult.kwhCum,
                currentResult.fromKm,
                currentResult.socNow,
                chargers,
                copySettings,
            )
            out.add(
                StopAlternative(
                    charger = c,
                    arrivePct = arrive,
                    plan = altPlan,
                    deltaMin = altPlan.chargeMinutes - currentResult.chargeMinutes,
                    current = c.stableKey == st.charger.stableKey,
                ),
            )
        }
        out.sortBy { abs(it.charger.kmAlong - st.km) }
        val trimmed = out.take(maxResults).toMutableList()
        trimmed.sortBy { it.charger.kmAlong }
        return trimmed
    }

    private fun preferredChargers(chargers: List<Charger>, s: ChargeSettings): List<Charger> {
        if (s.carVolts < HIGH_VOLTS) return chargers
        val high = chargers.filter { highVolts(it) }
        return if (high.size == chargers.size || high.isEmpty()) chargers else high
    }

    fun highVolts(c: Charger): Boolean {
        if (c.acOnly) return false
        if (c.maxVolts >= HIGH_VOLTS) return true
        return c.maxKw >= GOOD_KW && c.network != "tesla"
    }

    fun lowVolts(c: Charger, carVolts: Double): Boolean {
        if (carVolts < HIGH_VOLTS || c.acOnly || c.maxVolts <= 0.0 || c.maxVolts >= HIGH_VOLTS) return false
        return c.maxKw < GOOD_KW || c.network == "tesla"
    }

    private fun effKw(c: Charger, s: ChargeSettings): Double {
        val kw = min(if (c.maxKw > 0.0) c.maxKw else UNKNOWN_KW, s.carPeakKw)
        return if (lowVolts(c, s.carVolts)) kw * LOW_VOLTS_FACTOR else kw
    }

    private fun scoreCharger(c: Charger, s: ChargeSettings): Double {
        var v = effKw(c, s)
        if (s.favorites.contains(c.stableKey)) v += FAV_BONUS_KW
        return v
    }

    private fun pinnedNext(
        km: DoubleArray,
        kwhCum: DoubleArray,
        cur: Double,
        soc: Double,
        end: Double,
        chargers: List<Charger>,
        pinnedKeys: Set<String>,
        s: ChargeSettings,
    ): Charger? {
        if (pinnedKeys.isEmpty()) return null
        var next: Charger? = null
        for (c in chargers) {
            if (!pinnedKeys.contains(c.stableKey)) continue
            if (c.kmAlong <= cur + 0.5 || c.kmAlong >= end - 1.0) continue
            if (next == null || c.kmAlong < next.kmAlong) next = c
        }
        if (next == null) return null
        val at = soc - (kwhAt(km, kwhCum, next.kmAlong) - kwhAt(km, kwhCum, cur)) / s.capacityKwh * 100.0
        return if (at >= PIN_MIN_PCT) next else null
    }

    private fun pickCharger(
        km: DoubleArray,
        kwhCum: DoubleArray,
        cur: Double,
        soc: Double,
        end: Double,
        chargers: List<Charger>,
        s: ChargeSettings,
        minPct: Double,
    ): Charger? {
        val ok = mutableListOf<Charger>()
        var far = cur
        for (c in chargers) {
            if (c.kmAlong <= cur + 0.5 || c.kmAlong >= end - 1.0) continue
            val at = soc - (kwhAt(km, kwhCum, c.kmAlong) - kwhAt(km, kwhCum, cur)) / s.capacityKwh * 100.0
            if (at < minPct) continue
            ok.add(c)
            far = max(far, c.kmAlong)
        }
        if (ok.isEmpty()) return null
        val window = cur + 0.6 * (far - cur)
        val favWindow = cur + FAV_WINDOW * (far - cur)
        var best: Charger? = null
        for (c in ok) {
            val minKmWindow = if (s.favorites.contains(c.stableKey)) favWindow else window
            if (c.kmAlong < minKmWindow) continue
            val sc = scoreCharger(c, s)
            val sb = if (best == null) 0.0 else scoreCharger(best, s)
            if (best == null || sc > sb + 1e-9 || (abs(sc - sb) < 1e-9 && c.kmAlong > best.kmAlong)) {
                best = c
            }
        }
        if (best != null && effKw(best, s) < FAST_KW) {
            var fast: Charger? = null
            for (c in ok) {
                if (effKw(c, s) >= FAST_KW && (fast == null || c.kmAlong > fast.kmAlong)) {
                    fast = c
                }
            }
            if (fast != null) best = fast
        }
        return best
    }

    fun carPowerKw(pct: Double, peakKw: Double): Double {
        if (peakKw >= 120.0) {
            // C10 ProMax 800 V (180 kW)
            if (pct <= 30.0) return peakKw
            if (pct <= 50.0) return peakKw * (1.0 - 0.31 * (pct - 30.0) / 20.0)
            if (pct <= 80.0) return peakKw * (0.69 - 0.30 * (pct - 50.0) / 30.0)
            return peakKw * (0.39 - 0.22 * min(20.0, pct - 80.0) / 20.0)
        }
        if (peakKw <= 60.0) {
            if (pct <= 20.0) return peakKw
            if (pct <= 50.0) return peakKw * (1.0 - 0.30 * (pct - 20.0) / 30.0)
            if (pct <= 80.0) return peakKw * (0.70 - 0.20 * (pct - 50.0) / 30.0)
            return peakKw * (0.50 - 0.30 * min(20.0, pct - 80.0) / 20.0)
        }
        // C10 69.9 kWh 400 V (84 kW)
        if (pct <= 50.0) return peakKw
        if (pct <= 80.0) return peakKw * (1.0 - 0.45 * (pct - 50.0) / 30.0)
        return peakKw * (0.55 - 0.30 * min(20.0, pct - 80.0) / 20.0)
    }

    fun chargeMinutes(fromPct: Double, toPct: Double, c: Charger, s: ChargeSettings): Double {
        if (toPct <= fromPct) return 0.0
        val factor = if (lowVolts(c, s.carVolts)) LOW_VOLTS_FACTOR else 1.0
        val kw = if (c.maxKw > 0.0) c.maxKw else UNKNOWN_KW
        var minutes = 0.0
        val step = 0.5
        var p = fromPct
        while (p < toPct) {
            val d = min(step, toPct - p)
            val power = min(kw * CHARGE_EFF, carPowerKw(p + d / 2.0, s.carPeakKw) * factor)
            minutes += s.capacityKwh * d / 100.0 / max(1.0, power) * 60.0
            p += step
        }
        return minutes
    }

    fun kwhAt(km: DoubleArray, kwhCum: DoubleArray, x: Double): Double {
        val n = km.size
        if (n == 0) return 0.0
        if (x <= km[0]) return kwhCum[0]
        if (x >= km[n - 1]) return kwhCum[n - 1]
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (km[mid] < x) lo = mid else hi = mid
        }
        val f = (x - km[lo]) / max(1e-6, km[hi] - km[lo])
        return kwhCum[lo] + (kwhCum[hi] - kwhCum[lo]) * f
    }

    // -------------------------------------------------------------------------
    // Cargadores en ruta y cercanos (DGT España + Caché en disco + OpenStreetMap Overpass + Semilla Corredores)
    // -------------------------------------------------------------------------

    private val memoryTileCache = ConcurrentHashMap<String, List<RawChargerSite>>()
    @Volatile
    private var diskTilesLoaded = false
    private const val TILE_CACHE_FILE = "charger-tiles.tsv"

    // Semilla instantánea de electrolineras ultrarrápidas reales en los principales corredores de España (A-1 a A-8, A-66, AP-7)
    // para que el planificador tenga cobertura en 0 ms incluso antes de terminar la primera descarga de DGT/Overpass.
    private val SPAIN_CORRIDOR_SEED: List<RawChargerSite> = listOf(
        // A-1 (Madrid - Burgos - Vitoria - San Sebastián)
        RawChargerSite("seed:a1_buitrago", "Iberdrola Buitrago del Lozoya", "CCS · 4 ptos · A-1 km 74", 40.9922, -3.6358, 150.0, KW_TAGGED, false, "iberdrola", 800.0, 4, SOURCE_DGT),
        RawChargerSite("seed:a1_aranda", "Zunder Aranda de Duero", "CCS · 8 ptos · A-1 km 158", 41.6661, -3.6892, 360.0, KW_TAGGED, false, "zunder", 800.0, 8, SOURCE_DGT),
        RawChargerSite("seed:a1_lerma", "Tesla Supercharger Lerma", "CCS · 12 ptos · A-1 km 202", 42.0261, -3.7589, 250.0, KW_TAGGED, false, "tesla", 500.0, 12, SOURCE_DGT),
        RawChargerSite("seed:a1_burgos", "Ionity Burgos Villagonzalo", "CCS · 6 ptos · A-1 km 231", 42.3014, -3.7381, 350.0, KW_TAGGED, false, "ionity", 800.0, 6, SOURCE_DGT),
        RawChargerSite("seed:a1_miranda", "Zunder Miranda de Ebro", "CCS · 6 ptos · A-1 km 319", 42.6865, -2.9462, 300.0, KW_TAGGED, false, "zunder", 800.0, 6, SOURCE_DGT),
        RawChargerSite("seed:a1_vitoria", "Repsol Lopidana Vitoria", "CCS · 6 ptos · A-1 km 350", 42.8631, -2.7481, 350.0, KW_TAGGED, false, "repsol", 800.0, 6, SOURCE_DGT),
        // A-2 (Madrid - Zaragoza - Lleida - Barcelona)
        RawChargerSite("seed:a2_trihueque", "Zunder Trijueque A-2", "CCS · 6 ptos · A-2 km 78", 40.7782, -2.9921, 360.0, KW_TAGGED, false, "zunder", 800.0, 6, SOURCE_DGT),
        RawChargerSite("seed:a2_alcolea", "Iberdrola Alcolea del Pinar", "CCS · 4 ptos · A-2 km 135", 41.0361, -2.4672, 150.0, KW_TAGGED, false, "iberdrola", 800.0, 4, SOURCE_DGT),
        RawChargerSite("seed:a2_ariall", "Ionity Ariza A-2", "CCS · 6 ptos · A-2 km 196", 41.3125, -2.0542, 350.0, KW_TAGGED, false, "ionity", 800.0, 6, SOURCE_DGT),
        RawChargerSite("seed:a2_calatayud", "Zunder Calatayud", "CCS · 8 ptos · A-2 km 237", 41.3533, -1.6431, 300.0, KW_TAGGED, false, "zunder", 800.0, 8, SOURCE_DGT),
        RawChargerSite("seed:a2_zaragoza", "Tesla Supercharger Zaragoza Plaza", "CCS · 16 ptos · A-2 km 311", 41.6382, -0.9852, 250.0, KW_TAGGED, false, "tesla", 500.0, 16, SOURCE_DGT),
        RawChargerSite("seed:a2_fraga", "Endesa X Fraga", "CCS · 4 ptos · A-2 km 436", 41.5222, 0.3411, 150.0, KW_TAGGED, false, "endesa", 800.0, 4, SOURCE_DGT),
        RawChargerSite("seed:a2_lleida", "Ionity Lleida Vilasana", "CCS · 6 ptos · A-2 km 490", 41.6589, 0.9281, 350.0, KW_TAGGED, false, "ionity", 800.0, 6, SOURCE_DGT),
        RawChargerSite("seed:a2_igualada", "Zunder Jorba Igualada", "CCS · 8 ptos · A-2 km 545", 41.6012, 1.5489, 360.0, KW_TAGGED, false, "zunder", 800.0, 8, SOURCE_DGT),
        // A-3 (Madrid - Tarancón - Honrubia - Minglanilla - Requena - Valencia)
        RawChargerSite("seed:a3_tarancon", "Ionity Tarancón A-3", "CCS · 6 ptos · A-3 km 82", 40.0085, -3.0102, 350.0, KW_TAGGED, false, "ionity", 800.0, 6, SOURCE_DGT),
        RawChargerSite("seed:a3_saelices", "Zunder Saelices A-3", "CCS · 8 ptos · A-3 km 103", 39.9182, -2.8041, 360.0, KW_TAGGED, false, "zunder", 800.0, 8, SOURCE_DGT),
        RawChargerSite("seed:a3_honrubia", "Iberdrola Honrubia A-3", "CCS · 8 ptos · A-3 km 168", 39.6125, -2.2845, 350.0, KW_TAGGED, false, "iberdrola", 800.0, 8, SOURCE_DGT),
        RawChargerSite("seed:a3_atalaya", "Tesla Supercharger Atalaya del Cañavate", "CCS · 16 ptos · A-3 km 175", 39.5612, -2.2512, 250.0, KW_TAGGED, false, "tesla", 500.0, 16, SOURCE_DGT),
        RawChargerSite("seed:a3_minglanilla", "Zunder Minglanilla A-3", "CCS · 6 ptos · A-3 km 242", 39.5342, -1.5978, 300.0, KW_TAGGED, false, "zunder", 800.0, 6, SOURCE_DGT),
        RawChargerSite("seed:a3_requena", "Wenea Requena El Rebollar", "CCS · 6 ptos · A-3 km 281", 39.4751, -1.0452, 180.0, KW_TAGGED, false, "wenea", 800.0, 6, SOURCE_DGT),
        RawChargerSite("seed:a3_chiva", "Endesa X Chiva A-3", "CCS · 4 ptos · A-3 km 323", 39.4712, -0.6812, 150.0, KW_TAGGED, false, "endesa", 800.0, 4, SOURCE_DGT),
        // A-31 (Albacete - Alicante - Murcia)
        RawChargerSite("seed:a31_laroda", "Zunder La Roda A-31", "CCS · 8 ptos · A-31 km 37", 39.2062, -2.1562, 360.0, KW_TAGGED, false, "zunder", 800.0, 8, SOURCE_DGT),
        RawChargerSite("seed:a31_albacete", "Iberdrola Albacete Imaginalia", "CCS · 6 ptos · A-31 km 76", 39.0085, -1.8712, 180.0, KW_TAGGED, false, "iberdrola", 800.0, 6, SOURCE_DGT),
        RawChargerSite("seed:a31_almansa", "Ionity Almansa A-31", "CCS · 4 ptos · A-31 km 152", 38.8691, -1.0972, 350.0, KW_TAGGED, false, "ionity", 800.0, 4, SOURCE_DGT),
        RawChargerSite("seed:a31_villena", "Repsol Villena A-31", "CCS · 4 ptos · A-31 km 185", 38.6372, -0.8654, 150.0, KW_TAGGED, false, "repsol", 800.0, 4, SOURCE_DGT),
        // A-4 (Madrid - Manzanares - Bailén - Córdoba - Sevilla - Málaga)
        RawChargerSite("seed:a4_ocana", "Iberdrola Ocaña A-4", "CCS · 6 ptos · A-4 km 62", 39.9578, -3.4982, 180.0, KW_TAGGED, false, "iberdrola", 800.0, 6, SOURCE_DGT),
        RawChargerSite("seed:a4_madridejos", "Zunder Puerto Lápice / Madridejos", "CCS · 8 ptos · A-4 km 120", 39.4682, -3.5281, 360.0, KW_TAGGED, false, "zunder", 800.0, 8, SOURCE_DGT),
        RawChargerSite("seed:a4_manzanares", "Tesla Supercharger Manzanares", "CCS · 16 ptos · A-4 km 175", 38.9991, -3.3682, 250.0, KW_TAGGED, false, "tesla", 500.0, 16, SOURCE_DGT),
        RawChargerSite("seed:a4_valdepenas", "Ionity Valdepeñas A-4", "CCS · 6 ptos · A-4 km 200", 38.7612, -3.3951, 350.0, KW_TAGGED, false, "ionity", 800.0, 6, SOURCE_DGT),
        RawChargerSite("seed:a4_guarroman", "Zunder Guarromán / Bailén", "CCS · 8 ptos · A-4 km 286", 38.1832, -3.6865, 360.0, KW_TAGGED, false, "zunder", 800.0, 8, SOURCE_DGT),
        RawChargerSite("seed:a4_cordoba", "Endesa X Córdoba Guadalquivir", "CCS · 8 ptos · A-4 km 401", 37.8682, -4.7791, 300.0, KW_TAGGED, false, "endesa", 800.0, 8, SOURCE_DGT),
        RawChargerSite("seed:a4_ecija", "Iberdrola Écija La Luisiana", "CCS · 6 ptos · A-4 km 455", 37.5412, -5.0825, 180.0, KW_TAGGED, false, "iberdrola", 800.0, 6, SOURCE_DGT),
        RawChargerSite("seed:a45_antequera", "Zunder Antequera A-45", "CCS · 8 ptos · A-45 km 102", 37.0382, -4.5412, 360.0, KW_TAGGED, false, "zunder", 800.0, 8, SOURCE_DGT),
        // A-5 (Madrid - Talavera - Navalmoral - Trujillo - Mérida - Badajoz)
        RawChargerSite("seed:a5_maqueda", "Iberdrola Maqueda / Santa Olalla", "CCS · 4 ptos · A-5 km 78", 40.0251, -4.4321, 150.0, KW_TAGGED, false, "iberdrola", 800.0, 4, SOURCE_DGT),
        RawChargerSite("seed:a5_talavera", "Zunder Talavera de la Reina", "CCS · 8 ptos · A-5 km 115", 39.9635, -4.8305, 360.0, KW_TAGGED, false, "zunder", 800.0, 8, SOURCE_DGT),
        RawChargerSite("seed:a5_navalmoral", "Tesla Supercharger Navalmoral de la Mata", "CCS · 12 ptos · A-5 km 182", 39.8912, -5.5412, 250.0, KW_TAGGED, false, "tesla", 500.0, 12, SOURCE_DGT),
        RawChargerSite("seed:a5_trujillo", "Wenea Trujillo A-5", "CCS · 6 ptos · A-5 km 253", 39.4612, -5.8812, 180.0, KW_TAGGED, false, "wenea", 800.0, 6, SOURCE_DGT),
        RawChargerSite("seed:a5_merida", "Ionity Mérida A-5", "CCS · 6 ptos · A-5 km 341", 38.9162, -6.3652, 350.0, KW_TAGGED, false, "ionity", 800.0, 6, SOURCE_DGT),
        // A-6 (Madrid - Tordesillas - Benavente - Astorga - Ponferrada - Lugo - A Coruña)
        RawChargerSite("seed:a6_villacastin", "Zunder Villacastín AP-6", "CCS · 8 ptos · A-6 km 82", 40.7812, -4.4152, 360.0, KW_TAGGED, false, "zunder", 800.0, 8, SOURCE_DGT),
        RawChargerSite("seed:a6_arevalo", "Iberdrola Arévalo A-6", "CCS · 4 ptos · A-6 km 125", 41.0621, -4.7192, 150.0, KW_TAGGED, false, "iberdrola", 800.0, 4, SOURCE_DGT),
        RawChargerSite("seed:a6_tordesillas", "Tesla Supercharger Tordesillas", "CCS · 16 ptos · A-6 km 181", 41.5012, -4.9982, 250.0, KW_TAGGED, false, "tesla", 500.0, 16, SOURCE_DGT),
        RawChargerSite("seed:a6_benavente", "Ionity Benavente A-6", "CCS · 6 ptos · A-6 km 264", 42.0021, -5.6612, 350.0, KW_TAGGED, false, "ionity", 800.0, 6, SOURCE_DGT),
        RawChargerSite("seed:a6_ponferrada", "Zunder Ponferrada A-6", "CCS · 8 ptos · A-6 km 388", 42.5582, -6.5912, 360.0, KW_TAGGED, false, "zunder", 800.0, 8, SOURCE_DGT),
        RawChargerSite("seed:a6_guitiriz", "Wenea Guitiriz Lugo A-6", "CCS · 4 ptos · A-6 km 535", 43.1812, -7.8912, 150.0, KW_TAGGED, false, "wenea", 800.0, 4, SOURCE_DGT),
        // AP-7 Corredor Mediterráneo (Girona - Barcelona - Tarragona - Castellón - Valencia - Alicante - Murcia)
        RawChargerSite("seed:ap7_cambrils", "Ionity Vila-seca / Tarragona AP-7", "CCS · 6 ptos · AP-7", 41.1121, 1.1582, 350.0, KW_TAGGED, false, "ionity", 800.0, 6, SOURCE_DGT),
        RawChargerSite("seed:ap7_amposta", "Zunder L'Aldea / Amposta AP-7", "CCS · 8 ptos · AP-7", 40.7421, 0.6121, 360.0, KW_TAGGED, false, "zunder", 800.0, 8, SOURCE_DGT),
        RawChargerSite("seed:ap7_benicarlo", "Iberdrola Benicarló AP-7", "CCS · 6 ptos · AP-7", 40.4212, 0.4125, 180.0, KW_TAGGED, false, "iberdrola", 800.0, 6, SOURCE_DGT),
        RawChargerSite("seed:ap7_ondara", "Zunder Ondara / Dénia AP-7", "CCS · 8 ptos · AP-7", 38.8212, 0.0182, 360.0, KW_TAGGED, false, "zunder", 800.0, 8, SOURCE_DGT),
        RawChargerSite("seed:ap7_elche", "Ionity Elche / Crevillent AP-7", "CCS · 6 ptos · AP-7", 38.2412, -0.7812, 350.0, KW_TAGGED, false, "ionity", 800.0, 6, SOURCE_DGT),
        RawChargerSite("seed:ap7_murcia", "Tesla Supercharger Murcia Nueva Condomina", "CCS · 16 ptos · A-7", 38.0412, -1.1482, 250.0, KW_TAGGED, false, "tesla", 500.0, 16, SOURCE_DGT),
    )

    data class RawChargerSite(
        val id: String,
        val name: String,
        val detail: String,
        val lat: Double,
        val lon: Double,
        val maxKw: Double,
        val kwSource: Int,
        val acOnly: Boolean,
        val network: String,
        val maxVolts: Double,
        val points: Int,
        val source: Int,
    ) {
        fun toCharger(kmAlong: Double, distFromRouteKm: Double): Charger = Charger(
            id = id,
            name = name,
            detail = detail,
            lat = lat,
            lon = lon,
            kmAlong = kmAlong,
            distFromRouteKm = distFromRouteKm,
            maxKw = maxKw,
            kwSource = kwSource,
            acOnly = acOnly,
            network = network,
            maxVolts = maxVolts,
            points = points,
            source = source,
        )
    }

    @Synchronized
    private fun ensureDiskTilesLoaded(context: Context) {
        if (diskTilesLoaded) return
        diskTilesLoaded = true
        val f = File(context.filesDir, TILE_CACHE_FILE)
        if (!f.exists()) return
        val now = System.currentTimeMillis()
        val maxAge = 14L * 86_400_000L
        runCatching {
            val map = HashMap<String, MutableList<RawChargerSite>>()
            BufferedReader(InputStreamReader(FileInputStream(f), StandardCharsets.UTF_8)).use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    val parts = line!!.split("\t")
                    if (parts.size < 13) continue
                    val tileKey = parts[0]
                    val ts = parts[1].toLongOrNull() ?: 0L
                    if (now - ts > maxAge) continue
                    val id = parts[2]
                    if (id == "-") {
                        map.getOrPut(tileKey) { mutableListOf() }
                        continue
                    }
                    val site = RawChargerSite(
                        id = id,
                        lat = parts[3].toDouble(),
                        lon = parts[4].toDouble(),
                        name = parts[5],
                        detail = parts[6],
                        maxKw = parts[7].toDouble(),
                        kwSource = parts[8].toInt(),
                        acOnly = parts[9] == "1",
                        network = parts[10],
                        maxVolts = parts[11].toDouble(),
                        points = parts[12].toInt(),
                        source = SOURCE_OSM,
                    )
                    map.getOrPut(tileKey) { mutableListOf() }.add(site)
                }
            }
            for ((k, list) in map) {
                memoryTileCache.putIfAbsent(k, list)
            }
        }
    }

    @Synchronized
    private fun saveDiskTiles(context: Context, newTiles: Map<String, List<RawChargerSite>>) {
        if (newTiles.isEmpty()) return
        runCatching {
            val f = File(context.filesDir, TILE_CACHE_FILE)
            val now = System.currentTimeMillis()
            OutputStreamWriter(FileOutputStream(f, true), StandardCharsets.UTF_8).use { w ->
                for ((tileKey, sites) in newTiles) {
                    if (sites.isEmpty()) {
                        w.write("$tileKey\t$now\t-\t0\t0\t\t\t0\t0\t0\t\t0\t0\n")
                    } else {
                        for (s in sites) {
                            val cleanName = s.name.replace('\t', ' ').replace('\n', ' ')
                            val cleanDet = s.detail.replace('\t', ' ').replace('\n', ' ')
                            w.write(
                                String.format(
                                    Locale.US,
                                    "%s\t%d\t%s\t%.6f\t%.6f\t%s\t%s\t%.1f\t%d\t%d\t%s\t%.0f\t%d\n",
                                    tileKey,
                                    now,
                                    s.id,
                                    s.lat,
                                    s.lon,
                                    cleanName,
                                    cleanDet,
                                    s.maxKw,
                                    s.kwSource,
                                    if (s.acOnly) 1 else 0,
                                    s.network,
                                    s.maxVolts,
                                    s.points,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun hasMissingRouteTiles(context: Context, lat: DoubleArray, lon: DoubleArray, km: DoubleArray): Boolean {
        ensureDiskTilesLoaded(context)
        DgtRegistry.loadCached(context)
        val boxes = routeBoxes(lat, lon, km, ROUTE_BOX_KM, CHARGER_RADIUS_KM)
        val tiles = tilesForBoxes(boxes)
        return tiles.any { !memoryTileCache.containsKey(it) && DgtRegistry.inTile(it) == null }
    }

    fun findChargersAlongRoute(
        context: Context,
        lat: DoubleArray,
        lon: DoubleArray,
        km: DoubleArray,
        fetchMissingOverpass: Boolean,
    ): Pair<List<Charger>, Int> {
        ensureDiskTilesLoaded(context)
        DgtRegistry.loadCached(context)
        val boxes = routeBoxes(lat, lon, km, ROUTE_BOX_KM, CHARGER_RADIUS_KM)
        val tiles = tilesForBoxes(boxes)

        val missingOsmTiles = mutableListOf<String>()
        for (t in tiles) {
            if (!memoryTileCache.containsKey(t) && DgtRegistry.inTile(t) == null) {
                missingOsmTiles.add(t)
            }
        }

        if (fetchMissingOverpass && missingOsmTiles.isNotEmpty()) {
            for (chunk in missingOsmTiles.chunked(14)) {
                val ok = runCatching { fetchOverpassTiles(context, chunk) }.getOrDefault(false)
                if (!ok) break
            }
        }

        val official = mutableListOf<Charger>()
        val osmList = mutableListOf<Charger>()
        val seenIds = HashSet<String>()

        for (t in tiles) {
            val dgtSites = DgtRegistry.inTile(t)
            if (dgtSites != null) {
                for (s in dgtSites) {
                    val idx = nearestPointIndex(lat, lon, s.lat, s.lon)
                    val dist = distToRouteKm(lat, lon, idx, s.lat, s.lon)
                    if (dist > CHARGER_RADIUS_KM) continue
                    if (s.id.isNotEmpty() && !seenIds.add(s.id)) continue
                    official.add(s.toCharger(km[idx], dist))
                }
            }
            val osmSites = memoryTileCache[t]
            if (osmSites != null) {
                for (s in osmSites) {
                    val idx = nearestPointIndex(lat, lon, s.lat, s.lon)
                    val dist = distToRouteKm(lat, lon, idx, s.lat, s.lon)
                    if (dist > CHARGER_RADIUS_KM) continue
                    if (s.id.isNotEmpty() && !seenIds.add(s.id)) continue
                    osmList.add(s.toCharger(km[idx], dist))
                }
            }
        }

        // Incluimos la semilla de grandes electrolineras de autovía en España (radio 3,5 km del corredor) si aún no están en DGT
        for (s in SPAIN_CORRIDOR_SEED) {
            val idx = nearestPointIndex(lat, lon, s.lat, s.lon)
            val dist = distToRouteKm(lat, lon, idx, s.lat, s.lon)
            if (dist <= 3.5) {
                val dup = official.any { distanceKm(it.lat, it.lon, s.lat, s.lon) < 0.8 } ||
                    osmList.any { distanceKm(it.lat, it.lon, s.lat, s.lon) < 0.8 }
                if (!dup) {
                    official.add(s.toCharger(km[idx], dist))
                }
            }
        }

        val merged = mergeChargerSources(official, osmList)
        merged.sortBy { it.kmAlong }
        return merged to official.size
    }

    fun findNearbyChargers(
        context: Context,
        centerLat: Double,
        centerLon: Double,
        radiusKm: Double,
        fetchOverpass: Boolean = true,
    ): List<Charger> {
        ensureDiskTilesLoaded(context)
        DgtRegistry.loadCached(context)
        val dLat = radiusKm / 111.32
        val dLon = radiusKm / (111.32 * max(0.2, cos(Math.toRadians(centerLat))))
        val box = listOf(doubleArrayOf(centerLat - dLat, centerLon - dLon, centerLat + dLat, centerLon + dLon))
        val tiles = tilesForBoxes(box)

        if (fetchOverpass) {
            val missing = tiles.filter { !memoryTileCache.containsKey(it) && DgtRegistry.inTile(it) == null }
            for (chunk in missing.chunked(12)) {
                if (!runCatching { fetchOverpassTiles(context, chunk) }.getOrDefault(false)) break
            }
        }

        val official = mutableListOf<Charger>()
        val osmList = mutableListOf<Charger>()
        val seenIds = HashSet<String>()

        for (t in tiles) {
            DgtRegistry.inTile(t)?.forEach { s ->
                val d = distanceKm(centerLat, centerLon, s.lat, s.lon)
                if (d <= radiusKm && (s.id.isEmpty() || seenIds.add(s.id))) {
                    official.add(s.toCharger(d, d))
                }
            }
            memoryTileCache[t]?.forEach { s ->
                val d = distanceKm(centerLat, centerLon, s.lat, s.lon)
                if (d <= radiusKm && (s.id.isEmpty() || seenIds.add(s.id))) {
                    osmList.add(s.toCharger(d, d))
                }
            }
        }
        for (s in SPAIN_CORRIDOR_SEED) {
            val d = distanceKm(centerLat, centerLon, s.lat, s.lon)
            if (d <= radiusKm && official.none { distanceKm(it.lat, it.lon, s.lat, s.lon) < 0.8 }) {
                official.add(s.toCharger(d, d))
            }
        }
        val merged = mergeChargerSources(official, osmList)
        merged.sortBy { it.kmAlong }
        return merged
    }

    private fun mergeChargerSources(official: List<Charger>, osm: List<Charger>): MutableList<Charger> {
        if (official.isEmpty()) return osm.toMutableList()
        val out = official.toMutableList()
        for (o in osm) {
            val useful = (o.kwSource == KW_TAGGED && o.maxKw > 0) || o.kwSource == KW_NETWORK || o.network != OTHER_NETWORK
            if (!useful) continue
            val dup = out.any { d ->
                if (abs(d.lat - o.lat) > 0.01 || abs(d.lon - o.lon) > 0.015) false
                else {
                    val km = distanceKm(d.lat, d.lon, o.lat, o.lon)
                    val sameNet = o.network != OTHER_NETWORK && o.network == d.network
                    km < 0.12 || (sameNet && km < 0.4)
                }
            }
            if (!dup) out.add(o)
        }
        return out
    }

    private fun fetchOverpassTiles(context: Context, tiles: List<String>): Boolean {
        if (tiles.isEmpty()) return true
        val q = StringBuilder("[out:json][timeout:15];(")
        val got = HashMap<String, MutableList<RawChargerSite>>()
        for (k in tiles) {
            val b = tileBox(k)
            q.append(String.format(Locale.US, "nwr[\"amenity\"=\"charging_station\"](%.4f,%.4f,%.4f,%.4f);", b[0], b[1], b[2], b[3]))
            got[k] = mutableListOf()
        }
        q.append(");out center tags;")
        val body = queryOverpass(q.toString())
        val els = JSONObject(body).optJSONArray("elements") ?: JSONArray()
        for (i in 0 until els.length()) {
            val site = parseOsmCharger(els.getJSONObject(i)) ?: continue
            val key = tileOf(site.lat, site.lon)
            got.getOrPut(key) { mutableListOf() }.add(site)
        }
        for ((k, list) in got) {
            memoryTileCache[k] = list
        }
        saveDiskTiles(context, got)
        return true
    }

    private val OVERPASS_SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    private fun queryOverpass(query: String): String {
        val payload = "data=" + Uri.encode(query)
        val useBtFirst = com.headqlink.unit.bridgehost.CompanionBridgeHost.isConnected &&
            com.headqlink.unit.bridgehost.CompanionBridgeHost.useBluetoothDataBridge
        if (useBtFirst) {
            for (server in OVERPASS_SERVERS) {
                val btBody = com.headqlink.unit.bridgehost.CompanionBridgeHost.httpFetch(
                    url = server,
                    method = "POST",
                    body = payload,
                    contentType = "application/x-www-form-urlencoded; charset=UTF-8",
                    timeoutMs = 20_000,
                )
                if (!btBody.isNullOrBlank()) return btBody
            }
        }
        var lastEx: Exception? = null
        for (server in OVERPASS_SERVERS) {
            try {
                val conn = (URL(server).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 22_000
                    doOutput = true
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                }
                conn.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
                if (conn.responseCode == 200) {
                    return conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                }
            } catch (e: Exception) {
                lastEx = e
            }
        }
        if (!useBtFirst && com.headqlink.unit.bridgehost.CompanionBridgeHost.isConnected) {
            for (server in OVERPASS_SERVERS) {
                val btBody = com.headqlink.unit.bridgehost.CompanionBridgeHost.httpFetch(
                    url = server,
                    method = "POST",
                    body = payload,
                    contentType = "application/x-www-form-urlencoded; charset=UTF-8",
                    timeoutMs = 20_000,
                )
                if (!btBody.isNullOrBlank()) return btBody
            }
        }
        throw lastEx ?: IllegalStateException("Overpass sin respuesta")
    }

    private fun parseOsmCharger(e: JSONObject): RawChargerSite? {
        val tags = e.optJSONObject("tags") ?: return null
        val center = e.optJSONObject("center")
        val lat = center?.optDouble("lat") ?: e.optDouble("lat", Double.NaN)
        val lon = center?.optDouble("lon") ?: e.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null

        val id = "osm:" + e.optString("type") + e.optLong("id")
        val network = classifyNetwork(
            tags.optString("brand"),
            tags.optString("network"),
            tags.optString("operator"),
            tags.optString("name"),
        )
        val rawName = firstNonBlank(
            tags.optString("name"),
            tags.optString("operator"),
            tags.optString("brand"),
            "Punto de recarga",
        )
        var maxKw = 0.0
        var maxVolts = 0.0
        var sockets = 0
        val socketLabels = mutableListOf<String>()
        val keys = tags.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val v = tags.optString(key)
            if (key.matches("socket:[a-z0-9_]+".toRegex()) && v != "no") {
                val kindName = key.substring(7)
                    .replace("type2_combo", "CCS")
                    .replace("type2", "Tipo 2")
                    .replace("chademo", "CHAdeMO")
                    .replace("schuko", "Schuko")
                if (!socketLabels.contains(kindName)) socketLabels.add(kindName)
                sockets = sockets or socketKind(key.substring(7))
            }
            if (key.endsWith(":output") || key == "maxpower") {
                maxKw = max(maxKw, parseKwValue(v))
            }
            if (key.matches("socket:(type2_combo|chademo|type1_combo|tesla_supercharger|tesla_supercharger_ccs|nacs):voltage".toRegex())) {
                maxVolts = max(maxVolts, parseVoltsValue(v))
            }
        }
        val capPoints = tags.optString("capacity").toIntOrNull() ?: 1
        var kwSource = KW_TAGGED
        if (maxKw <= 0.0) {
            val est = estimateKw(network, rawName, sockets)
            maxKw = est.first
            kwSource = est.second
        }
        if (maxVolts <= 0.0) {
            maxVolts = typicalVolts(network, rawName, sockets)
        }
        val acOnly = (sockets and SOCKET_DC) == 0 && (sockets and SOCKET_AC) != 0 && kwSource != KW_NETWORK
        val op = tags.optString("operator")
        val detailParts = mutableListOf<String>()
        if (socketLabels.isNotEmpty()) detailParts.add(socketLabels.joinToString(" · "))
        if (capPoints > 1) detailParts.add("$capPoints ptos")
        if (op.isNotBlank() && !op.equals(rawName, ignoreCase = true)) detailParts.add(op)

        return RawChargerSite(
            id = id,
            name = rawName,
            detail = detailParts.joinToString(" · "),
            lat = lat,
            lon = lon,
            maxKw = maxKw,
            kwSource = kwSource,
            acOnly = acOnly,
            network = network,
            maxVolts = maxVolts,
            points = capPoints,
            source = SOURCE_OSM,
        )
    }

    private fun socketKind(socket: String): Int {
        val s = socket.lowercase(Locale.ROOT).substringBefore(':')
        return when (s) {
            "type2_combo", "chademo", "type1_combo", "tesla_supercharger", "tesla_supercharger_ccs", "gb_dc", "nacs" -> SOCKET_DC
            "type2", "type2_cable", "type1", "schuko", "cee_blue", "cee_red_16a", "cee_red_32a", "tesla_destination", "type3c" -> SOCKET_AC
            else -> 0
        }
    }

    private val KW_REGEX = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*(kW|kw|KW|W)?")
    private val VOLTS_REGEX = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)")

    private fun parseKwValue(v: String): Double {
        val m = KW_REGEX.matcher(v)
        var best = 0.0
        while (m.find()) {
            var x = m.group(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            if (m.group(2) == "W") x /= 1000.0
            best = max(best, x)
        }
        return if (best > 1000.0) 0.0 else best
    }

    private fun parseVoltsValue(v: String): Double {
        val m = VOLTS_REGEX.matcher(v)
        var best = 0.0
        while (m.find()) {
            val x = m.group(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            best = max(best, x)
        }
        return if (best > 1500.0) 0.0 else best
    }

    // -------------------------------------------------------------------------
    // Registro oficial DGT / MITERD (electrolineras.xml en España)
    // -------------------------------------------------------------------------

    private object DgtRegistry {
        private const val URL_XML = "https://infocar.dgt.es/datex2/v3/miterd/EnergyInfrastructureTablePublication/electrolineras.xml"
        private const val FILE_NAME = "dgt-chargers.tsv"
        private const val MAX_AGE_MS = 7L * 86_400_000L

        @Volatile
        private var grid: Map<String, List<RawChargerSite>>? = null

        @Volatile
        private var loadedAtMs: Long = 0L

        @Volatile
        private var triedDiskLoad = false

        @Volatile
        private var downloading = false

        @Synchronized
        fun loadCached(context: Context) {
            if (grid != null || triedDiskLoad) return
            triedDiskLoad = true
            val f = File(context.filesDir, FILE_NAME)
            if (!f.exists()) return
            runCatching {
                BufferedReader(InputStreamReader(FileInputStream(f), StandardCharsets.UTF_8)).use { r ->
                    val head = r.readLine()
                    val at = head?.trim()?.toLongOrNull() ?: 0L
                    val sites = mutableListOf<RawChargerSite>()
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        val s = fromLine(line!!) ?: continue
                        sites.add(s)
                    }
                    if (sites.isNotEmpty()) {
                        setSites(sites, at)
                    }
                }
            }
        }

        fun shouldRefresh(context: Context): Boolean {
            loadCached(context)
            if (downloading) return false
            val now = System.currentTimeMillis()
            return grid == null || (now - loadedAtMs) > MAX_AGE_MS
        }

        fun inTile(tile: String): List<RawChargerSite>? = grid?.get(tile)

        fun refresh(context: Context): Boolean {
            synchronized(this) {
                if (downloading) return false
                downloading = true
            }
            return try {
                val conn = (URL(URL_XML).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 90_000
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                if (conn.responseCode != 200) return false
                val sites = conn.inputStream.use { parseXml(it) }
                if (sites.size < 500) return false
                val now = System.currentTimeMillis()
                saveToDisk(context, sites, now)
                setSites(sites, now)
                true
            } catch (_: Exception) {
                false
            } finally {
                downloading = false
            }
        }

        @Synchronized
        private fun setSites(sites: List<RawChargerSite>, atMs: Long) {
            val map = HashMap<String, MutableList<RawChargerSite>>()
            for (s in sites) {
                map.getOrPut(tileOf(s.lat, s.lon)) { mutableListOf() }.add(s)
            }
            grid = map
            loadedAtMs = atMs
        }

        private fun saveToDisk(context: Context, sites: List<RawChargerSite>, atMs: Long) {
            val f = File(context.filesDir, FILE_NAME)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            OutputStreamWriter(FileOutputStream(tmp), StandardCharsets.UTF_8).use { w ->
                w.write("$atMs\n")
                for (s in sites) {
                    val cleanName = s.name.replace('\t', ' ').replace('\n', ' ')
                    val cleanDetail = s.detail.replace('\t', ' ').replace('\n', ' ')
                    w.write(
                        String.format(
                            Locale.US,
                            "%s\t%.6f\t%.6f\t%s\t%s\t%.1f\t%d\t%d\t%s\t%.0f\t%d\n",
                            s.id,
                            s.lat,
                            s.lon,
                            cleanName,
                            cleanDetail,
                            s.maxKw,
                            s.kwSource,
                            if (s.acOnly) 1 else 0,
                            s.network,
                            s.maxVolts,
                            s.points,
                        ),
                    )
                }
            }
            tmp.renameTo(f)
        }

        private fun fromLine(line: String): RawChargerSite? {
            val p = line.split("\t")
            if (p.size < 11) return null
            return runCatching {
                RawChargerSite(
                    id = p[0],
                    lat = p[1].toDouble(),
                    lon = p[2].toDouble(),
                    name = p[3],
                    detail = p[4],
                    maxKw = p[5].toDouble(),
                    kwSource = p[6].toInt(),
                    acOnly = p[7] == "1",
                    network = p[8],
                    maxVolts = p[9].toDouble(),
                    points = p[10].toInt(),
                    source = SOURCE_DGT,
                )
            }.getOrNull()
        }

        private fun parseXml(input: InputStream): List<RawChargerSite> {
            val xp = XmlPullParserFactory.newInstance().newPullParser()
            xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            xp.setInput(input, "UTF-8")
            val out = mutableListOf<RawChargerSite>()

            var curId = ""
            var curLat = Double.NaN
            var curLon = Double.NaN
            var curName = ""
            var curOperator = ""
            var curTown = ""
            var dcKw = 0.0
            var acKw = 0.0
            var dcVolts = 0.0
            var sockets = 0
            var ccs = false
            var chademo = false
            var type2 = false
            var points = 0

            var depth = 0
            var siteDepth = -1
            var siteNameDepth = -1
            var operatorDepth = -1
            var inPoint = false
            var inConnector = false
            var connType = ""
            var connMode = ""
            var connW = 0.0
            var connV = 0.0
            var text: String? = null

            var ev = xp.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        val n = xp.name
                        text = null
                        if (n.endsWith("energyInfrastructureSite")) {
                            curId = xp.getAttributeValue(null, "id").orEmpty()
                            curLat = Double.NaN
                            curLon = Double.NaN
                            curName = ""
                            curOperator = ""
                            curTown = ""
                            dcKw = 0.0
                            acKw = 0.0
                            dcVolts = 0.0
                            sockets = 0
                            ccs = false
                            chademo = false
                            type2 = false
                            points = 0
                            siteDepth = depth
                        } else if (siteDepth > 0) {
                            if (n.endsWith(":name") && depth == siteDepth + 1) siteNameDepth = depth
                            else if (n.endsWith(":operator")) operatorDepth = depth
                            else if (n.endsWith("refillPoint")) {
                                inPoint = true
                                points++
                            } else if (n.endsWith(":connector") && inPoint) {
                                inConnector = true
                                connType = ""
                                connMode = ""
                                connW = 0.0
                                connV = 0.0
                            }
                        }
                    }
                    XmlPullParser.TEXT -> text = xp.text
                    XmlPullParser.END_TAG -> {
                        val n = xp.name
                        val t = text?.trim().orEmpty()
                        if (siteDepth > 0) {
                            if (n.endsWith(":value") && siteNameDepth > 0 && curName.isEmpty()) curName = t
                            else if (n.endsWith(":value") && operatorDepth > 0 && curOperator.isEmpty()) curOperator = t
                            else if (n.endsWith(":value") && t.startsWith("Municipio: ") && curTown.isEmpty()) {
                                curTown = t.removePrefix("Municipio: ").trim()
                            } else if (n.endsWith(":latitude")) curLat = t.replace(',', '.').toDoubleOrNull() ?: Double.NaN
                            else if (n.endsWith(":longitude")) curLon = t.replace(',', '.').toDoubleOrNull() ?: Double.NaN
                            else if (inConnector && n.endsWith(":connectorType")) connType = t
                            else if (inConnector && n.endsWith(":chargingMode")) connMode = t
                            else if (inConnector && n.endsWith(":maxPowerAtSocket")) connW = t.replace(',', '.').toDoubleOrNull() ?: 0.0
                            else if (inConnector && n.endsWith(":voltage")) connV = t.replace(',', '.').toDoubleOrNull() ?: 0.0

                            if (depth == siteNameDepth && n.endsWith(":name")) siteNameDepth = -1
                            if (depth == operatorDepth && n.endsWith(":operator")) operatorDepth = -1
                            if (inConnector && n.endsWith(":connector")) {
                                inConnector = false
                                val dc = connMode.uppercase(Locale.ROOT).contains("DC") ||
                                    connType.lowercase(Locale.ROOT).contains("combo") ||
                                    connType.lowercase(Locale.ROOT).contains("chademo")
                                var kw = connW / 1000.0
                                if (kw > 2000.0) kw = 0.0
                                if (dc) {
                                    sockets = sockets or SOCKET_DC
                                    dcKw = max(dcKw, kw)
                                    if (connV in 1.0..1500.0) dcVolts = max(dcVolts, connV)
                                } else {
                                    sockets = sockets or SOCKET_AC
                                    acKw = max(acKw, kw)
                                }
                                val ty = connType.lowercase(Locale.ROOT)
                                if (ty.contains("combo")) ccs = true
                                else if (ty.contains("chademo")) chademo = true
                                else if (ty.contains("t2") || ty.contains("type2")) type2 = true
                            }
                            if (n.endsWith("refillPoint")) inPoint = false
                            if (depth == siteDepth && n.endsWith("energyInfrastructureSite")) {
                                if (!curLat.isNaN() && !curLon.isNaN() && (curLat != 0.0 || curLon != 0.0)) {
                                    val net = classifyNetwork("", "", curOperator, "")
                                    val dc = (sockets and SOCKET_DC) != 0
                                    val maxKw = if (dc) dcKw else acKw
                                    val displayName = formatDgtDisplayName(curName, curOperator, net, curTown)
                                    val volts = if (!dc) 0.0 else if (dcVolts > 0.0) dcVolts else typicalVolts(net, displayName, SOCKET_DC)
                                    val dParts = mutableListOf<String>()
                                    if (ccs) dParts.add("CCS")
                                    if (chademo) dParts.add("CHAdeMO")
                                    if (type2) dParts.add("Tipo 2")
                                    if (points > 1) dParts.add("$points ptos")
                                    if (curOperator.isNotBlank() && !curOperator.equals(displayName, ignoreCase = true)) {
                                        dParts.add(curOperator)
                                    }
                                    out.add(
                                        RawChargerSite(
                                            id = if (curId.isEmpty()) "" else "dgt:$curId",
                                            name = displayName,
                                            detail = dParts.joinToString(" · "),
                                            lat = curLat,
                                            lon = curLon,
                                            maxKw = maxKw,
                                            kwSource = if (maxKw > 0.0) KW_TAGGED else KW_UNKNOWN,
                                            acOnly = !dc,
                                            network = net,
                                            maxVolts = volts,
                                            points = max(1, points),
                                            source = SOURCE_DGT,
                                        ),
                                    )
                                }
                                siteDepth = -1
                            }
                        }
                        text = null
                        depth--
                    }
                }
                ev = xp.next()
            }
            return out
        }

        private fun formatDgtDisplayName(name: String, operator: String, network: String, town: String): String {
            val brand = if (network != OTHER_NETWORK) networkLabel(network) else operator
            val cleanName = name.trim()
            val looksLikeCode = cleanName.length <= 4 || cleanName.matches("[A-Z0-9_-]{4,}".toRegex())
            val base = if (cleanName.isEmpty() || looksLikeCode) {
                if (town.isNotEmpty() && brand.isNotEmpty()) "$brand $town"
                else if (town.isNotEmpty()) "Cargador $town"
                else brand.ifEmpty { "Punto de recarga" }
            } else {
                cleanName
            }
            return if (town.isNotEmpty() && !base.lowercase(Locale.ROOT).contains(town.lowercase(Locale.ROOT))) {
                "$base ($town)"
            } else {
                base
            }
        }
    }

    private fun nearSpain(lat: DoubleArray, lon: DoubleArray): Boolean {
        for (i in lat.indices) {
            if (lat[i] in 27.5..44.0 && lon[i] in -18.3..4.5) return true
        }
        return false
    }

    // -------------------------------------------------------------------------
    // Altitud y Meteorología (Open-Meteo + OpenTopoData)
    // -------------------------------------------------------------------------

    private const val ELEV_SAMPLES = 95

    private fun fetchElevations(lat: DoubleArray, lon: DoubleArray, km: DoubleArray): Pair<DoubleArray, Boolean> {
        val n = lat.size
        val idx = sampleIndices(n, ELEV_SAMPLES)
        val sLat = DoubleArray(idx.size) { lat[idx[it]] }
        val sLon = DoubleArray(idx.size) { lon[idx[it]] }
        try {
            val vals = elevOpenMeteo(sLat, sLon)
            return interpolateByKm(km, idx, vals) to false
        } catch (_: Exception) {
        }
        try {
            val vals = elevOpenTopo(sLat, sLon)
            return interpolateByKm(km, idx, vals) to false
        } catch (_: Exception) {
        }
        return DoubleArray(n) { 600.0 } to true
    }

    private fun elevOpenMeteo(lat: DoubleArray, lon: DoubleArray): DoubleArray {
        val out = DoubleArray(lat.size)
        var from = 0
        while (from < lat.size) {
            val to = min(lat.size, from + 100)
            val la = StringBuilder()
            val lo = StringBuilder()
            for (i in from until to) {
                if (i > from) {
                    la.append(',')
                    lo.append(',')
                }
                la.append(String.format(Locale.US, "%.5f", lat[i]))
                lo.append(String.format(Locale.US, "%.5f", lon[i]))
            }
            val body = httpGet("https://api.open-meteo.com/v1/elevation?latitude=$la&longitude=$lo", 3_200)
            val arr = JSONObject(body).getJSONArray("elevation")
            for (i in from until to) {
                out[i] = arr.optDouble(i - from, 0.0)
            }
            from += 100
        }
        return out
    }

    private fun elevOpenTopo(lat: DoubleArray, lon: DoubleArray): DoubleArray {
        val out = DoubleArray(lat.size)
        var from = 0
        while (from < lat.size) {
            val to = min(lat.size, from + 100)
            val q = StringBuilder()
            for (i in from until to) {
                if (i > from) q.append("%7C")
                q.append(String.format(Locale.US, "%.5f,%.5f", lat[i], lon[i]))
            }
            val body = httpGet("https://api.opentopodata.org/v1/srtm90m?locations=$q", 3_500)
            val results = JSONObject(body).getJSONArray("results")
            for (i in from until to) {
                val o = results.optJSONObject(i - from)
                out[i] = if (o == null || o.isNull("elevation")) 0.0 else o.optDouble("elevation", 0.0)
            }
            from += 100
        }
        return out
    }

    private fun fetchWeatherAlongRoute(
        lat: DoubleArray,
        lon: DoubleArray,
        idx: IntArray,
        etaS: DoubleArray,
    ): Array<DoubleArray> {
        val out = Array(idx.size) { doubleArrayOf(0.0, 0.0, 16.0, 0.0, -1.0) }
        try {
            val la = StringBuilder()
            val lo = StringBuilder()
            for (j in idx.indices) {
                if (j > 0) {
                    la.append(',')
                    lo.append(',')
                }
                la.append(String.format(Locale.US, "%.4f", lat[idx[j]]))
                lo.append(String.format(Locale.US, "%.4f", lon[idx[j]]))
            }
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo" +
                "&hourly=temperature_2m,wind_speed_10m,wind_direction_10m,precipitation,weather_code" +
                "&wind_speed_unit=kmh&forecast_days=2&timeformat=unixtime"
            val body = httpGet(url, 3_200).trim()
            val arr = if (body.startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
            val departSec = System.currentTimeMillis() / 1000L
            for (j in 0 until min(idx.size, arr.length())) {
                val h = arr.getJSONObject(j).getJSONObject("hourly")
                val times = h.getJSONArray("time")
                val whenSec = departSec + etaS[idx[j]].roundToInt()
                var best = 0
                for (q in 1 until times.length()) {
                    if (abs(times.getLong(q) - whenSec) < abs(times.getLong(best) - whenSec)) {
                        best = q
                    }
                }
                out[j] = doubleArrayOf(
                    h.getJSONArray("wind_speed_10m").optDouble(best, 0.0) * 0.7,
                    h.getJSONArray("wind_direction_10m").optDouble(best, 0.0),
                    h.getJSONArray("temperature_2m").optDouble(best, 16.0),
                    h.getJSONArray("precipitation").optDouble(best, 0.0),
                    h.getJSONArray("weather_code").optDouble(best, -1.0),
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun weatherEmoji(code: Int, day: Boolean = true): String {
        if (code < 0) return "⛅"
        if (code == 0) return if (day) "☀️" else "🌙"
        if (code <= 2) return if (day) "🌤️" else "🌙"
        if (code == 3) return "☁️"
        if (code <= 48) return "🌫️"
        if (code <= 57) return "🌦️"
        if (code <= 67) return "🌧️"
        if (code <= 77) return "❄️"
        if (code <= 82) return "🌦️"
        if (code <= 86) return "🌨️"
        return "⛈️"
    }

    fun weatherText(code: Int): String = when {
        code < 0 -> "Clima estimado"
        code == 0 -> "Despejado"
        code <= 3 -> "Parcialmente nublado"
        code <= 48 -> "Niebla"
        code <= 57 -> "Llovizna"
        code <= 67 -> "Lluvia"
        code <= 77 -> "Nieve"
        code <= 82 -> "Chubascos"
        code <= 86 -> "Aguanieve"
        else -> "Tormenta"
    }

    // -------------------------------------------------------------------------
    // Utilidades geométricas y de cuadrículas (ChargerCache + OSRM)
    // -------------------------------------------------------------------------

    private const val TILE_DEG = 0.25

    private fun tileOf(lat: Double, lon: Double): String {
        val r = kotlin.math.floor(lat / TILE_DEG).toInt()
        val c = kotlin.math.floor(lon / TILE_DEG).toInt()
        return "$r:$c"
    }

    private fun tileBox(key: String): DoubleArray {
        val parts = key.split(":")
        val r = parts[0].toInt()
        val c = parts[1].toInt()
        return doubleArrayOf(r * TILE_DEG, c * TILE_DEG, (r + 1) * TILE_DEG, (c + 1) * TILE_DEG)
    }

    private fun tilesForBoxes(boxes: List<DoubleArray>): List<String> {
        val set = LinkedHashSet<String>()
        for (b in boxes) {
            val r0 = kotlin.math.floor(b[0] / TILE_DEG).toInt()
            val c0 = kotlin.math.floor(b[1] / TILE_DEG).toInt()
            val r1 = kotlin.math.floor(b[2] / TILE_DEG).toInt()
            val c1 = kotlin.math.floor(b[3] / TILE_DEG).toInt()
            for (r in r0..r1) {
                for (c in c0..c1) {
                    set.add("$r:$c")
                }
            }
        }
        return set.toList()
    }

    private fun routeBoxes(
        lat: DoubleArray,
        lon: DoubleArray,
        km: DoubleArray,
        segKm: Double,
        padKm: Double,
    ): List<DoubleArray> {
        val out = mutableListOf<DoubleArray>()
        val n = lat.size
        var start = 0
        while (start < n - 1) {
            var s = lat[start]
            var w = lon[start]
            var no = lat[start]
            var e = lon[start]
            var i = start
            while (i < n - 1 && km[i + 1] - km[start] <= segKm) {
                i++
                s = min(s, lat[i])
                no = max(no, lat[i])
                w = min(w, lon[i])
                e = max(e, lon[i])
            }
            if (i == start) i++
            s = min(s, lat[i])
            no = max(no, lat[i])
            w = min(w, lon[i])
            e = max(e, lon[i])
            val dLat = padKm / 111.32
            val dLon = padKm / (111.32 * max(0.2, cos(Math.toRadians((s + no) / 2.0))))
            out.add(doubleArrayOf(s - dLat, w - dLon, no + dLat, e + dLon, km[start], km[i]))
            start = i
        }
        return out
    }

    private fun nearestPointIndex(lat: DoubleArray, lon: DoubleArray, targetLat: Double, targetLon: Double): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        val cosLat = cos(Math.toRadians(targetLat))
        for (i in lat.indices) {
            val dy = lat[i] - targetLat
            val dx = (lon[i] - targetLon) * cosLat
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private fun distToRouteKm(lat: DoubleArray, lon: DoubleArray, i: Int, la: Double, lo: Double): Double {
        if (lat.size == 1) return segmentKm(lat[0], lon[0], lat[0], lon[0], la, lo)
        var best = Double.MAX_VALUE
        for (k in max(0, i - 1)..min(lat.size - 2, i)) {
            best = min(best, segmentKm(lat[k], lon[k], lat[k + 1], lon[k + 1], la, lo))
        }
        return best
    }

    private fun segmentKm(laA: Double, loA: Double, laB: Double, loB: Double, la: Double, lo: Double): Double {
        val cos = cos(Math.toRadians(la))
        val ax = (loA - lo) * cos * 111.32
        val ay = (laA - la) * 111.32
        val bx = (loB - lo) * cos * 111.32
        val by = (laB - la) * 111.32
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + t * dx
        val py = ay + t * dy
        return sqrt(px * px + py * py)
    }

    fun distanceKm(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6371.0
        val dLa = Math.toRadians(la2 - la1)
        val dLo = Math.toRadians(lo2 - lo1)
        val a = sin(dLa / 2.0) * sin(dLa / 2.0) +
            cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLo / 2.0) * sin(dLo / 2.0)
        return 2.0 * r * asin(min(1.0, sqrt(a)))
    }

    private fun bearing(lat: DoubleArray, lon: DoubleArray, a: Int, b: Int): Double {
        val la1 = Math.toRadians(lat[a])
        val la2 = Math.toRadians(lat[b])
        val dl = Math.toRadians(lon[b] - lon[a])
        val y = sin(dl) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun extractAnnotations(route: JSONObject, coords: JSONArray): Pair<DoubleArray, DoubleArray> {
        val segs = max(0, coords.length() - 1)
        val dist = DoubleArray(segs)
        val dur = DoubleArray(segs)
        val legs = route.optJSONArray("legs")
        var k = 0
        var ok = legs != null
        if (legs != null) {
            for (l in 0 until legs.length()) {
                val a = legs.optJSONObject(l)?.optJSONObject("annotation")
                val d = a?.optJSONArray("distance")
                val t = a?.optJSONArray("duration")
                if (d == null || t == null || k + d.length() > segs) {
                    ok = false
                    break
                }
                for (i in 0 until d.length()) {
                    dist[k] = d.optDouble(i, 0.0)
                    dur[k] = t.optDouble(i, 0.0)
                    k++
                }
            }
        }
        if (ok && k == segs) return dist to dur

        var total = 0.0
        for (i in 0 until segs) {
            val a = coords.optJSONArray(i)
            val b = coords.optJSONArray(i + 1)
            dist[i] = if (a == null || b == null) 0.0 else distanceKm(a.optDouble(1), a.optDouble(0), b.optDouble(1), b.optDouble(0)) * 1000.0
            total += dist[i]
        }
        val sec = route.optDouble("duration", 0.0)
        for (i in 0 until segs) {
            dur[i] = if (total > 0.0) sec * dist[i] / total else 0.0
        }
        return dist to dur
    }

    private fun sampleIndices(n: Int, maxSamples: Int): IntArray {
        if (n <= maxSamples) return IntArray(n) { it }
        return IntArray(maxSamples) { j ->
            ((j.toDouble() * (n - 1)) / (maxSamples - 1)).roundToInt()
        }
    }

    private fun interpolateByKm(km: DoubleArray, idx: IntArray, vals: DoubleArray): DoubleArray {
        val out = DoubleArray(km.size)
        var j = 0
        for (i in km.indices) {
            while (j < idx.size - 2 && idx[j + 1] <= i) j++
            val a = idx[j]
            val b = idx[min(j + 1, idx.size - 1)]
            out[i] = when {
                b == a || i <= a -> vals[j]
                i >= b -> vals[min(j + 1, vals.size - 1)]
                else -> {
                    val span = km[b] - km[a]
                    val f = if (span > 0.0) (km[i] - km[a]) / span else (i - a).toDouble() / (b - a)
                    vals[j] + (vals[j + 1] - vals[j]) * f
                }
            }
        }
        return out
    }

    private fun firstNonBlank(vararg values: String?): String {
        for (v in values) {
            if (!v.isNullOrBlank()) return v.trim()
        }
        return ""
    }

    private fun httpGet(url: String, timeoutMs: Int = 5_000): String {
        val btConnected = com.headqlink.unit.bridgehost.CompanionBridgeHost.isConnected
        if (!btConnected) {
            return directHttpGet(url, timeoutMs)
        }
        // Si el móvil está conectado al coche, competimos en paralelo entre la red directa del coche y la pasarela del móvil:
        // gana el primero que responda (0 espera por timeout de DNS si el coche no lleva datos o el puente BT está ocupado).
        val resultRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val errRef = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
        val doneLatch = java.util.concurrent.CountDownLatch(1)
        val bothFinished = java.util.concurrent.atomic.AtomicInteger(0)

        Thread({
            try {
                val res = directHttpGet(url, timeoutMs)
                if (res.isNotBlank() && resultRef.compareAndSet(null, res)) {
                    doneLatch.countDown()
                }
            } catch (e: Exception) {
                errRef.compareAndSet(null, e)
            } finally {
                if (bothFinished.incrementAndGet() >= 2) doneLatch.countDown()
            }
        }, "unit-http-direct").start()

        Thread({
            try {
                val btBody = com.headqlink.unit.bridgehost.CompanionBridgeHost.httpFetch(
                    url = url,
                    method = "GET",
                    timeoutMs = timeoutMs,
                )
                if (!btBody.isNullOrBlank() && resultRef.compareAndSet(null, btBody)) {
                    doneLatch.countDown()
                }
            } catch (e: Exception) {
                errRef.compareAndSet(null, e)
            } finally {
                if (bothFinished.incrementAndGet() >= 2) doneLatch.countDown()
            }
        }, "unit-http-bt").start()

        runCatching { doneLatch.await((timeoutMs + 400).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS) }
        resultRef.get()?.let { return it }
        throw errRef.get() ?: IllegalStateException("Timeout HTTP en $url")
    }

    private fun directHttpGet(url: String, timeoutMs: Int): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = min(timeoutMs, 4_000)
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${conn.responseCode} en $url")
        }
        return conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}
