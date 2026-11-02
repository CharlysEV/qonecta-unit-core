package com.headqlink.unit.extended

/**
 * Máquina de estados de los márgenes que se le mandan a Android Auto (pura: la prueban los tests
 * del PC). Lleva los márgenes que se quieren, la generación del transporte, la secuencia y los
 * intentos del envío en curso y si AA lo confirmó; en cada paso dice si toca enviar, esperar o
 * nada. El envío, los temporizadores y el log los hace ExtendedMarginsController.
 *
 * Reglas, portadas de Qonecta+ (AaPassthroughSource.sendMargins y marginsForNewAa):
 * - Sin TransportStarted, o sin la primera imagen de esa conexión de AA, los márgenes quedan
 *   pendientes: los que llegan antes de que AA dé imagen, a veces no los aplica.
 * - Cada conexión de AA (generación) arranca con los márgenes del ServiceDiscovery, los simétricos
 *   (baseline). Con la primera imagen se mandan los que se quieren, salvo que sean justo esos
 *   (Pantalla completa) y [sendInFullscreen] esté a false: entonces no se manda nada, como DiAuto.
 * - En una misma generación no se repiten unos márgenes ya enviados (en curso, confirmados o
 *   abandonados): AA se rehace entero con cada cambio.
 * - Sin confirmación (UpdateUiConfigReply) a tiempo, se repiten; tras [maxTries] envíos, se rinde.
 */
class MarginsSync(
    private val sendInFullscreen: Boolean,
    private val maxTries: Int,
) {
    /** Lo que se sabe del transporte de AA en este momento. */
    data class Link(
        /** CommManager.transportGeneration: sube con cada conexión de AA. */
        val generation: Int,
        /** CommManager en TransportStarted (solo entonces se puede mandar). */
        val transportStarted: Boolean,
        /** Ya hubo imagen en esta conexión (primer frame o framesRenderedThisSession > 0). */
        val videoSeen: Boolean,
        /** Márgenes con los que arranca AA en esta conexión: los simétricos del ServiceDiscovery. */
        val baseline: AaMargins?,
    )

    sealed class Decision {
        /** Nada que hacer. */
        object None : Decision()

        /** Pendiente: falta el transporte o la primera imagen. */
        object Wait : Decision()

        /** Mandar estos márgenes y esperar la confirmación del envío [seq]. */
        data class Send(val margins: AaMargins, val seq: Int, val attempt: Int) : Decision()

        /** [maxTries] envíos sin confirmación: se deja. */
        data class GiveUp(val margins: AaMargins) : Decision()
    }

    enum class Status {
        /** No se quiere nada todavía. */
        IDLE,
        PENDING_TRANSPORT,
        PENDING_VIDEO,
        /** AA ya tiene los que se quieren desde el ServiceDiscovery: no se manda nada. */
        AS_NEGOTIATED,
        SENT,
        CONFIRMED,
        GAVE_UP,
    }

    /** Los márgenes que se quieren (null hasta la primera petición). */
    var desired: AaMargins? = null
        private set

    /** La generación a la que se refiere lo de abajo. */
    var generation = NO_GENERATION
        private set

    /** Lo último que se mandó en esta generación (null: nada). */
    var sent: AaMargins? = null
        private set

    /** Intento del envío en curso: 0 el primero. */
    var attempt = 0
        private set

    private var transportUp = false
    private var videoSeen = false
    private var baseline: AaMargins? = null
    private var confirmed = false
    private var gaveUp = false
    private var seq = 0

    val status: Status
        get() = when {
            desired == null -> Status.IDLE
            !transportUp -> Status.PENDING_TRANSPORT
            !videoSeen -> Status.PENDING_VIDEO
            sent == null -> Status.AS_NEGOTIATED
            confirmed -> Status.CONFIRMED
            gaveUp -> Status.GAVE_UP
            else -> Status.SENT
        }

    /** Se quieren estos márgenes (cambio de modo, de medidas o de superficie). */
    fun want(margins: AaMargins, link: Link): Decision {
        desired = margins
        return update(link)
    }

    /** Vuelve a mirar con lo que se sabe ahora (transporte, primera imagen, red de seguridad). */
    fun update(link: Link): Decision {
        if (!link.transportStarted) {
            onDisconnected()
            return if (desired == null) Decision.None else Decision.Wait
        }
        if (link.generation != generation) {
            // Conexión nueva de AA: arranca con los del ServiceDiscovery y no ha visto nada nuestro
            generation = link.generation
            baseline = link.baseline
            videoSeen = false
            sent = null
            confirmed = false
            gaveUp = false
            attempt = 0
            seq++
        }
        transportUp = true
        if (link.videoSeen) videoSeen = true
        val want = desired ?: return Decision.None
        if (!videoSeen) return Decision.Wait
        if (want == sent) return Decision.None
        if (sent == null && !sendInFullscreen && want == baseline) return Decision.None
        return startSend(want)
    }

    /** AA respondió (UpdateUiConfigReply). true si confirma el envío en curso. */
    fun onAck(): Boolean {
        if (sent == null || confirmed || !transportUp) return false
        confirmed = true
        gaveUp = false
        return true
    }

    /** Venció la espera de la confirmación del envío [seq]. */
    fun onTimeout(seq: Int, link: Link): Decision {
        if (seq != this.seq) return Decision.None
        if (!link.transportStarted || link.generation != generation) return update(link)
        val margins = sent ?: return Decision.None
        if (confirmed || gaveUp) return Decision.None
        if (attempt + 1 >= maxTries) {
            gaveUp = true
            return Decision.GiveUp(margins)
        }
        attempt++
        this.seq++
        return Decision.Send(margins, this.seq, attempt)
    }

    /** Sin transporte: lo que estuviera esperando confirmación ya no cuenta. */
    fun onDisconnected() {
        if (!transportUp) return
        transportUp = false
        seq++
    }

    private fun startSend(margins: AaMargins): Decision {
        sent = margins
        confirmed = false
        gaveUp = false
        attempt = 0
        seq++
        return Decision.Send(margins, seq, 0)
    }

    companion object {
        const val NO_GENERATION = -1
    }
}
