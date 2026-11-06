package com.headqlink.unit.bridge

import java.net.URLEncoder
import java.util.Locale

/**
 * Construye el enlace que abre Google Maps navegando, con el mismo formato que Qonecta+ (`NavApps`):
 * - Sin paradas: `google.navigation:q=lat,lon&mode=d`.
 * - Con paradas: `https://www.google.com/maps/dir/?api=1&destination=…&travelmode=driving&dir_action=navigate&waypoints=a|b|c`.
 *
 * Google Maps solo acepta [BridgeConstants.MAX_WAYPOINTS] paradas en un enlace. Si hay más, igual que Qonecta+, se
 * navega hasta la siguiente parada que no cabe (la ruta continúa después volviendo a mandarla desde el coche).
 */
object MapsRoute {

    data class Built(
        val uri: String,
        /** Paradas intermedias que van en el enlace. */
        val waypoints: List<NavPoint>,
        /** Punto final del enlace (el destino, o una parada si no cabían todas). */
        val target: NavPoint,
        /** `true` si el enlace llega hasta el destino real. */
        val reachesDestination: Boolean,
    )

    fun build(dest: NavPoint, via: List<NavPoint>, maxWaypoints: Int = BridgeConstants.MAX_WAYPOINTS): Built {
        require(dest.isValid) { "Destino no válido: $dest" }
        val ordered = via.filter { it.isValid } + dest
        val used = ordered.take(maxWaypoints + 1)
        val target = used.last()
        val waypoints = used.dropLast(1)
        val uri = if (waypoints.isEmpty()) {
            String.format(Locale.US, "google.navigation:q=%.6f,%.6f&mode=d", target.lat, target.lon)
        } else {
            val wp = waypoints.joinToString("|") { String.format(Locale.US, "%.6f,%.6f", it.lat, it.lon) }
            String.format(
                Locale.US,
                "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f&travelmode=driving&dir_action=navigate",
                target.lat,
                target.lon,
            ) + "&waypoints=" + URLEncoder.encode(wp, "UTF-8")
        }
        return Built(uri, waypoints, target, reachesDestination = target == dest)
    }
}
