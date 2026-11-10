package com.headqlink.unit.bridge

import java.util.UUID

/**
 * Constantes compartidas entre Qonecta Unit (coche) y Qonecta Unit Companion (móvil).
 *
 * El coche hace de servidor por dos vías a la vez:
 * - TCP [TCP_PORT] en la red Wi-Fi que crea el coche para Android Auto inalámbrico.
 * - Bluetooth RFCOMM [RFCOMM_UUID], que sirve también con Android Auto por cable USB.
 * El móvil se conecta como cliente por la vía que encuentre.
 */
object BridgeConstants {
    const val PROTOCOL_VERSION = 1
    const val TCP_PORT = 5299
    const val APK_PUSH_TCP_PORT = 5300
    const val RFCOMM_NAME = "Qonecta Unit Companion"
    const val APK_PUSH_RFCOMM_NAME = "Qonecta Unit OTA"
    val RFCOMM_UUID: UUID = UUID.fromString("6f3c2a1e-9d4b-4c8a-b5e2-7a1d0c9e4f51")
    val APK_PUSH_RFCOMM_UUID: UUID = UUID.fromString("6f3c2a1e-9d4b-4c8a-b5e2-7a1d0c9e4f52")

    const val COMPANION_PACKAGE = "com.headqlink.unit.companion"
    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    /** Google Maps acepta como mucho estas paradas intermedias en un enlace (igual que Qonecta+). */
    const val MAX_WAYPOINTS = 3

    const val PAIR_CODE_LENGTH = 6
    const val PAIR_CODE_VALIDITY_MS = 5 * 60_000L

    const val PING_INTERVAL_MS = 20_000L
    const val READ_TIMEOUT_MS = 60_000
    const val HANDSHAKE_TIMEOUT_MS = 10_000
    const val NAV_RESULT_TIMEOUT_MS = 10_000L

    /** Una línea JSON nunca debería pasar de esto; protege de basura en el socket. */
    const val MAX_LINE_BYTES = 256 * 1024
}
