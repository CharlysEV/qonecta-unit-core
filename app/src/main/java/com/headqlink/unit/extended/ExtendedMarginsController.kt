package com.headqlink.unit.extended

import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.decoder.VideoDecoder
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig

/**
 * Manda a Android Auto los márgenes del modo (Extendido o Pantalla completa) y comprueba que los
 * aplica. Es un object de proceso para que sobreviva a los relanzamientos de la actividad: la
 * sesión de AA, y los márgenes que ya tiene, siguen aunque la actividad se rehaga.
 *
 * Portado de Qonecta+ (AaPassthroughSource.java: sendMargins, hookMarginReply y marginsForNewAa),
 * que es lo que funciona en el C10. Las decisiones son de [MarginsSync]; aquí van el envío, la
 * confirmación, los temporizadores y el log.
 * - Sin TransportStarted los márgenes quedan pendientes. Con transporte se mandan con la primera
 *   imagen de cada conexión de AA (antes, AA a veces los ignora) y después en cuanto cambian.
 * - Confirmación: UpdateUiConfigReply en [MARGIN_ACK_MS]; si no llega, se repiten hasta
 *   [MARGIN_TRIES] veces.
 * - Red de seguridad: onFirstFrameListener es de un solo disparo y la actividad puede no estar
 *   mirando. Mientras haya conexión y falte la imagen, cada segundo se mira
 *   framesRenderedThisSession, que vuelve a 0 en cada handshake.
 * - Solo margins (UpdateUiConfigRequest), nunca content_insets en caliente: en el clúster tiraba
 *   la conexión.
 *
 * Todo corre en el hilo principal; lo que llega de otros hilos (primera imagen, respuesta de AA)
 * salta a él.
 */
object ExtendedMarginsController {
    /** Espera de la confirmación de AA antes de repetir (Qonecta+, MARGIN_ACK_MS). */
    const val MARGIN_ACK_MS = 2500L

    /** Envíos como mucho sin confirmación (Qonecta+, MARGIN_TRIES). */
    const val MARGIN_TRIES = 4

    /**
     * En Pantalla completa no se manda nada al conectar: AA se queda con los márgenes del
     * ServiceDiscovery, como en DiAuto. Los simétricos solo se mandan al volver de Extendido en la
     * misma sesión (o si cambia la superficie). Si en el coche aparece una franja negra o el
     * desfase de 99 px, se pone a true.
     */
    const val SEND_IN_FULLSCREEN = false

    private const val SAFETY_TICK_MS = 1000L

    /** Quien enseña el modo (ExtendedUi): se entera de cuándo AA confirma unos márgenes. */
    interface Listener {
        fun onMarginsConfirmed(margins: AaMargins)
    }

    /** Uno solo: el de la actividad viva. Al destruirse, lo quita solo si sigue siendo el suyo. */
    var listener: Listener? = null

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val sync = MarginsSync(SEND_IN_FULLSCREEN, MARGIN_TRIES)
    private var comm: CommManager? = null
    private var decoder: VideoDecoder? = null
    private var hooked: CommManager? = null
    private var label = ""
    private var lastLog = ""
    private var ticking = false

    val status: MarginsSync.Status
        get() = sync.status

    /** El CommManager y el decodificador del proceso (siempre los mismos). */
    fun bind(commManager: CommManager, videoDecoder: VideoDecoder) {
        comm = commManager
        decoder = videoDecoder
        hookReply(commManager)
    }

    /**
     * Se quieren estos márgenes. [label] describe el modo para el log («extendido: panel 576 px ·
     * AA 1344» o «completa») y [reason], quién lo pide.
     */
    fun want(margins: AaMargins, label: String, reason: String) = onMain {
        this.label = label
        execute(sync.want(margins, link()), reason)
    }

    /** Conexión de AA lista (TransportStarted): sus márgenes, con su primera imagen. */
    fun onTransportStarted() = onMain { execute(sync.update(link()), "transporte") }

    fun onDisconnected() = onMain {
        sync.onDisconnected()
        log("desconexión")
    }

    /** Primera imagen de AA (los dos onFirstFrameListener de la actividad). Desde cualquier hilo. */
    fun onFirstFrame() = onMain { execute(sync.update(link()), "primera imagen") }

    /**
     * La respuesta de AA a los márgenes, encadenada sin perder el callback que hubiera (como
     * hookMarginReply en Qonecta+). Sustituye al hook que solo escribía en el log, y conserva su línea.
     */
    fun hookReply(commManager: CommManager) {
        if (hooked === commManager) return
        hooked = commManager
        val previous = commManager.onUpdateUiConfigReplyReceived
        commManager.onUpdateUiConfigReplyReceived = {
            AppLog.i("[UI_DEBUG_FIX] UpdateUiConfig reply received. AA acknowledged new margins.")
            main.post { onReply() }
            previous?.invoke()
        }
    }

    private fun onReply() {
        if (!sync.onAck()) return
        val margins = sync.sent ?: return
        log("respuesta de AA")
        listener?.onMarginsConfirmed(margins)
    }

    private fun onTimeout(seq: Int) {
        execute(sync.onTimeout(seq, link()), "sin respuesta en $MARGIN_ACK_MS ms")
    }

    private fun execute(decision: MarginsSync.Decision, reason: String) {
        if (decision is MarginsSync.Decision.Send) {
            val m = decision.margins
            comm?.sendUpdateUiConfigRequest(m.left, m.top, m.right, m.bottom)
            val seq = decision.seq
            main.postDelayed({ onTimeout(seq) }, MARGIN_ACK_MS)
        }
        log(reason)
        scheduleTick()
    }

    /** Lo que se sabe ahora del transporte, leído en vivo del CommManager y del decodificador. */
    private fun link(): MarginsSync.Link {
        val cm = comm
        val started = cm?.connectionState?.value is CommManager.ConnectionState.TransportStarted
        val frames = decoder?.framesRenderedThisSession ?: 0L
        return MarginsSync.Link(
            generation = cm?.transportGeneration ?: MarginsSync.NO_GENERATION,
            transportStarted = started,
            videoSeen = started && frames > 0L,
            baseline = if (started) symmetricMargins() else null,
        )
    }

    /** Los del ServiceDiscovery: márgenes simétricos de HeadUnitScreenConfig. */
    private fun symmetricMargins() = AaMargins(
        HeadUnitScreenConfig.getLeftMargin(),
        HeadUnitScreenConfig.getTopMargin(),
        HeadUnitScreenConfig.getRightMargin(),
        HeadUnitScreenConfig.getBottomMargin(),
    )

    /** Red de seguridad: cada segundo mientras haya conexión y los márgenes esperen. */
    private val tick = Runnable {
        ticking = false
        execute(sync.update(link()), "red de seguridad")
    }

    private fun scheduleTick() {
        if (ticking) return
        val st = sync.status
        if (st != MarginsSync.Status.PENDING_TRANSPORT && st != MarginsSync.Status.PENDING_VIDEO) return
        // Sin conexión no hay nada que mirar: lo reanudan onTransportStarted y want
        if (comm?.isConnected != true) return
        ticking = true
        main.postDelayed(tick, SAFETY_TICK_MS)
    }

    /** Una línea por cambio: [QU] extendido: panel 576 px · AA 1344 · L0 T99 R576 B99 · estado [motivo]. */
    private fun log(reason: String) {
        val margins = sync.desired ?: return
        val st = sync.status
        val text = when (st) {
            MarginsSync.Status.IDLE -> "sin márgenes"
            MarginsSync.Status.PENDING_TRANSPORT -> "pendiente (sin transporte)"
            MarginsSync.Status.PENDING_VIDEO -> "pendiente (sin imagen)"
            MarginsSync.Status.AS_NEGOTIATED -> "los del ServiceDiscovery (no se envían)"
            MarginsSync.Status.SENT ->
                if (sync.attempt == 0) "enviado" else "enviado · intento ${sync.attempt + 1}"
            MarginsSync.Status.CONFIRMED -> "confirmado"
            MarginsSync.Status.GAVE_UP -> "sin confirmar tras $MARGIN_TRIES"
        }
        val key = "$label|$margins|$text"
        if (key == lastLog) return
        lastLog = key
        val line = "[QU] $label · $margins · $text [$reason]"
        if (st == MarginsSync.Status.GAVE_UP || (st == MarginsSync.Status.SENT && sync.attempt > 0)) {
            AppLog.w(line)
        } else {
            AppLog.i(line)
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }
}
