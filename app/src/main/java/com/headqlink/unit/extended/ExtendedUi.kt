package com.headqlink.unit.extended

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.decoder.VideoDecoder
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.andrerinas.openheadunit.utils.Settings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Qonecta Unit: el modo Extendido en la pantalla de proyección. AA a la izquierda y nuestro panel en
 * la franja derecha, o AA a pantalla completa con un botón flotante para volver.
 *
 * Lo crea AapProjectionActivity y reúne todo lo nuestro, para que la actividad solo lleve ganchos.
 * La vista de proyección y la capa táctil siguen a pantalla completa: el panel es una vista opaca
 * encima de la franja donde AA ya no dibuja porque se le han mandado márgenes con ese hueco a la
 * derecha ([ExtendedLayout], [ExtendedMarginsController]).
 *
 * - Pasar a Extendido: se guarda el ajuste, el panel sale enseguida (tapa la franja mientras AA se
 *   rehace) y se piden los márgenes.
 * - Pasar a Pantalla completa: se guarda el ajuste, se piden los simétricos y el panel se quita
 *   cuando AA los confirma, o al cabo de 1 s como mucho, para que no asome el margen negro.
 * - Saltos puntuales, nunca animados (AA se rehace con cada cambio) y con rebote de 1,5 s.
 * - En PiP no se enseña nada nuestro ni se tocan los márgenes. Con espejo HUD, o si no hay sitio
 *   para el panel, no hay Extendido.
 */
class ExtendedUi(
    private val activity: Activity,
    private val container: FrameLayout,
    private val settings: Settings,
    private val commManager: CommManager,
    private val videoDecoder: VideoDecoder,
) : ExtendedMarginsController.Listener {

    private val handler = Handler(Looper.getMainLooper())

    /** El panel de la franja derecha: reloj y fecha, tarjeta Batería y botón «Pantalla completa». */
    private var panel: ExtendedPanelView? = null

    /** De dónde saca el panel la batería: DEMO o, mientras no exista la nube (bloque C), «Sin cuenta». */
    private var source: PanelDataSource = NoAccountPanelData

    /** La proyección está en primer plano (onStart sin onStop): la fuente está arrancada. */
    private var sourceStarted = false
    private var fab: ImageView? = null
    private var loadingOverlay: View? = null
    private var geometry: ExtendedLayout.Result? = null
    private var attached = false
    private var lastFits: Boolean? = null

    /** El modo que eligió el usuario (Settings.extendedMode). */
    var extended = true
        private set

    /** Pasando a Pantalla completa: el panel sigue puesto hasta que AA confirma (o 1 s). */
    private var hidingPanel = false
    private var lastToggleMs = 0L
    private var inPiP = false
    private var fabUntilMs = 0L
    private var ownGesture = false
    private var layoutPending = false

    /** Saliendo de PiP: el ancho que tenía el contenedor en PiP (0 si no). */
    private var pipExitWidth = 0

    // DEMO (bloque D): los extras del intent mandan sobre el ajuste, solo en compilaciones debug.
    private var demoOverride: Boolean? = null

    /** Estado de la DEMO: ok, viejo, bajo, sin_cuenta, caducada o error (DEMO_STATES). */
    var demoState = DEMO_OK
        private set

    /** Panel con datos inventados: el ajuste qu-panel-demo, o el intent de depuración. */
    val demo: Boolean
        get() = demoOverride ?: settings.panelDemo

    /** Hay sitio para el panel y no hay espejo HUD. */
    private val fits: Boolean
        get() = geometry?.available == true && !settings.hudMirroring

    /** AA está (o va) en Extendido: es el modo elegido y cabe. */
    val active: Boolean
        get() = extended && fits

    /** Se puede conmutar ahora (para la entrada del menú de la actividad). */
    val canToggle: Boolean
        get() = fits && !pip()

    // ------------------------------------------------------------------ ciclo de vida

    /** Monta el panel y el botón flotante encima de la capa de carga, y aplica el modo guardado. */
    fun attach(intent: Intent?) {
        if (attached) return
        attached = true
        extended = settings.extendedMode
        loadingOverlay = container.findViewById(R.id.loading_overlay)

        val p = createPanel()
        p.isClickable = true
        p.isFocusable = false
        p.isFocusableInTouchMode = false
        p.visibility = View.GONE
        container.addView(p, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        panel = p
        updatePanel()

        val f = createFab()
        f.visibility = View.GONE
        container.addView(f, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.END))
        fab = f
        // Por encima de la capa de carga, que la actividad acaba de subir con bringToFront
        p.bringToFront()
        f.bringToFront()

        ExtendedMarginsController.bind(commManager, videoDecoder)
        ExtendedMarginsController.listener = this
        container.addOnLayoutChangeListener(layoutListener)

        AppLog.i("[QU] arranque en ${modeName(extended)}")
        applyIntent(intent)
        recompute("arranque")
        if (!extended) showFab()
    }

    fun detach() {
        if (!attached) return
        attached = false
        handler.removeCallbacksAndMessages(null)
        container.removeOnLayoutChangeListener(layoutListener)
        stopSource()
        // En un relanzamiento (singleTask) la actividad nueva ya puso el suyo: solo se quita el propio
        if (ExtendedMarginsController.listener === this) ExtendedMarginsController.listener = null
    }

    /**
     * La proyección pasa a primer plano: arranca la fuente del panel (DEMO o «Sin cuenta»: sin red).
     * BLOQUE C: con CloudPanelData, aquí se pide la nube (salvo en DEMO o en Pantalla completa).
     */
    fun onStart() {
        updatePanel()
        if (!sourceStarted) {
            sourceStarted = true
            source.start(activity.applicationContext)
        }
    }

    /** Suelta la fuente del panel. BLOQUE C: aquí se suelta la nube. */
    fun onStop() {
        stopSource()
    }

    private fun stopSource() {
        if (!sourceStarted) return
        sourceStarted = false
        source.stop()
    }

    /** Al volver (por ejemplo de Ajustes) se releen el modo y la DEMO. */
    fun onResume() {
        val stored = settings.extendedMode
        if (stored != extended) setMode(stored, "ajustes")
        updatePanel()
        recompute("vuelta al primer plano")
    }

    fun onPiP(on: Boolean) {
        inPiP = on
        handler.removeCallbacks(pipExitRunnable)
        if (on) {
            pipExitWidth = 0
            stopHiding()
            applyViews()
        } else {
            // El aviso llega con el contenedor aún del tamaño de PiP: recalcular ya mandaría a AA los
            // márgenes de Pantalla completa (no cabe el panel) y luego otra vez los del Extendido. Se
            // espera a que el contenedor recupere su tamaño (layoutListener), o 1 s como mucho.
            pipExitWidth = max(container.width, 1)
            handler.postDelayed(pipExitRunnable, PIP_EXIT_MAX_MS)
            applyViews()
        }
    }

    private val pipExitRunnable = Runnable {
        pipExitWidth = 0
        recompute("fin de PiP")
    }

    // ------------------------------------------------------------------ geometría y márgenes

    /**
     * Recalcula la geometría con las medidas de ahora (contenedor y HeadUnitScreenConfig) y pide los
     * márgenes del modo. También es la ruta del desajuste de superficie: si no, se mandarían los
     * simétricos y se perdería el hueco del panel.
     */
    fun recompute(reason: String) {
        if (!attached) return
        if (pip()) {
            // En PiP no se tocan los márgenes: solo se esconde lo nuestro
            applyViews()
            return
        }
        if (pipExitWidth > 0) {
            // Saliendo de PiP: hasta que el contenedor cambie de tamaño, nada de márgenes
            if (container.width == pipExitWidth) {
                applyViews()
                return
            }
            pipExitWidth = 0
            handler.removeCallbacks(pipExitRunnable)
        }
        val g = computeGeometry() ?: return
        geometry = g
        logFits(g)
        applyViews()
        pushMargins(reason)
    }

    private fun computeGeometry(): ExtendedLayout.Result? {
        // Antes del primer layout, la zona usable de HeadUnitScreenConfig (el contenedor sin barras)
        val viewW = if (container.width > 0) {
            container.width - container.paddingLeft - container.paddingRight
        } else {
            HeadUnitScreenConfig.getUsableWidth()
        }
        if (viewW <= 0) return null
        return ExtendedLayout.compute(
            ExtendedLayout.Input(
                viewW = viewW,
                negW = HeadUnitScreenConfig.getNegotiatedWidth(),
                negH = HeadUnitScreenConfig.getNegotiatedHeight(),
                full = AaMargins(
                    HeadUnitScreenConfig.getLeftMargin(),
                    HeadUnitScreenConfig.getTopMargin(),
                    HeadUnitScreenConfig.getRightMargin(),
                    HeadUnitScreenConfig.getBottomMargin(),
                ),
                dpi = HeadUnitScreenConfig.getDensityDpi(),
                percent = settings.extendedPanelPercent,
            ),
        )
    }

    private fun pushMargins(reason: String) {
        val g = geometry ?: return
        if (pip()) return
        val on = active
        val label = if (on) "extendido: panel ${g.panelPx} px · AA ${g.aaVideoW}" else "completa"
        ExtendedMarginsController.want(g.margins(on), label, reason)
    }

    /** Avisa en el log cuando el Extendido deja de caber (o vuelve). */
    private fun logFits(g: ExtendedLayout.Result) {
        val now = fits
        if (now == lastFits) return
        lastFits = now
        when {
            settings.hudMirroring -> AppLog.i("[QU] sin Extendido: espejo HUD activado")
            !g.available -> AppLog.w(
                "[QU] sin Extendido: no cabe el panel (saldría de ${g.wantedPanelPx} px y AA necesita " +
                    "${g.minAaW} de ${g.uiW} px a ${HeadUnitScreenConfig.getDensityDpi()} dpi); " +
                    "se queda en Pantalla completa",
            )
            else -> AppLog.i(
                "[QU] Extendido disponible: panel ${g.panelPx} px · AA ${g.aaVideoW} de ${g.uiW} px " +
                    "(mínimo ${g.minAaW} a ${HeadUnitScreenConfig.getDensityDpi()} dpi)",
            )
        }
    }

    private val layoutRecompute = Runnable {
        layoutPending = false
        recompute("layout")
    }

    // Al cambiar el contenedor (barras, ventana, PiP) se recalcula, fuera de la pasada de layout
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (!layoutPending) {
            layoutPending = true
            handler.post(layoutRecompute)
        }
    }

    // ------------------------------------------------------------------ conmutador

    /** Toque en el botón del panel, en el flotante o en el menú: con rebote de 1,5 s. */
    fun requestMode(on: Boolean, reason: String) {
        val now = SystemClock.uptimeMillis()
        if (lastToggleMs != 0L && now - lastToggleMs < TOGGLE_DEBOUNCE_MS) {
            AppLog.i("[QU] pulsación ignorada (menos de $TOGGLE_DEBOUNCE_MS ms desde la anterior): $reason")
            return
        }
        lastToggleMs = now
        setMode(on, reason)
    }

    /** Cambia de modo y lo recuerda (sin rebote: ajustes, intent de depuración). */
    fun setMode(on: Boolean, reason: String) {
        if (settings.extendedMode != on) settings.extendedMode = on
        if (on == extended) {
            recompute(reason)
            return
        }
        extended = on
        AppLog.i("[QU] modo ${modeName(on)} ($reason)")
        if (on) {
            // El panel enseguida: tapa la franja mientras AA se rehace
            stopHiding()
            hideFab()
            applyViews()
            pushMargins(reason)
        } else {
            hidingPanel = panel?.visibility == View.VISIBLE
            pushMargins(reason)
            if (hidingPanel) {
                if (ExtendedMarginsController.status == MarginsSync.Status.SENT) {
                    // Hasta que AA confirme, o 1 s como mucho (el retardo se ajusta tras medirlo en el coche)
                    handler.removeCallbacks(hidePanelRunnable)
                    handler.postDelayed(hidePanelRunnable, PANEL_HIDE_MAX_MS)
                } else {
                    // No se ha mandado nada (sin transporte o sin imagen): AA no se rehace
                    hidingPanel = false
                }
            }
            applyViews()
            showFab()
        }
    }

    override fun onMarginsConfirmed(margins: AaMargins) {
        if (hidingPanel && !extended) finishHiding()
    }

    private val hidePanelRunnable = Runnable {
        if (hidingPanel) {
            AppLog.i("[QU] panel fuera sin esperar más la confirmación ($PANEL_HIDE_MAX_MS ms)")
            finishHiding()
        }
    }

    private fun finishHiding() {
        hidingPanel = false
        handler.removeCallbacks(hidePanelRunnable)
        applyViews()
    }

    private fun stopHiding() {
        hidingPanel = false
        handler.removeCallbacks(hidePanelRunnable)
    }

    // ------------------------------------------------------------------ toques

    /** En Extendido, la x topada en el borde de AA: un arrastre que entra en el panel no sale del contenido. */
    fun clampX(x: Int): Int {
        val g = geometry ?: return x
        return if (active) min(x, g.aaVideoW - 1) else x
    }

    /** Un toque en AA: en Pantalla completa el botón flotante vuelve 6 s. */
    fun onAaTouch() {
        if (!extended && fits && !pip()) showFab()
    }

    /**
     * API < 19: DiAuto manda a AA todos los toques desde dispatchTouchEvent, también los que caen en el
     * panel. ¿Este gesto empezó en el panel o en el botón flotante? Entonces no va a AA.
     */
    fun isOwnGesture(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) ownGesture = hits(panel, ev) || hits(fab, ev)
        val own = ownGesture
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            ownGesture = false
        }
        return own
    }

    private fun hits(v: View?, ev: MotionEvent): Boolean {
        if (v == null || v.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] && ev.rawX < loc[0] + v.width && ev.rawY >= loc[1] && ev.rawY < loc[1] + v.height
    }

    // ------------------------------------------------------------------ DEMO (bloque D)

    /**
     * Extras de AapProjectionActivity, solo en compilaciones debug (la actividad está exportada: en
     * release cualquier app podría enseñar datos inventados). Se leen en onCreate y en onNewIntent.
     * - qu_demo (boolean): DEMO sí o no. Sin él, manda el ajuste qu-panel-demo.
     * - qu_demo_estado: ok, viejo, bajo, sin_cuenta, caducada o error (implica DEMO si no hay qu_demo).
     * - qu_modo: extendido o completa (como el conmutador: se recuerda).
     */
    fun applyIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        val stateExtra = intent.getStringExtra(EXTRA_DEMO_STATE)
        val override = when {
            intent.hasExtra(EXTRA_DEMO) -> intent.getBooleanExtra(EXTRA_DEMO, false)
            stateExtra != null -> true
            else -> null
        }
        val state = demoStateOf(stateExtra)
        if (override != demoOverride || state != demoState) {
            demoOverride = override
            demoState = state
            val what = when (override) {
                null -> "la del ajuste (${if (settings.panelDemo) "sí" else "no"})"
                true -> "sí"
                false -> "no"
            }
            AppLog.i("[QU] DEMO por intent (debug): $what · estado $state")
            updatePanel()
        }
        when (val mode = intent.getStringExtra(EXTRA_MODE)) {
            null -> Unit
            MODE_EXTENDED -> setMode(true, "intent qu_modo")
            MODE_FULLSCREEN -> setMode(false, "intent qu_modo")
            else -> AppLog.w("[QU] qu_modo desconocido: $mode (extendido o completa)")
        }
        // Que una recreación de la actividad con este mismo intent no vuelva a imponer el modo
        intent.removeExtra(EXTRA_MODE)
    }

    private fun demoStateOf(raw: String?): String {
        if (raw == null) return DEMO_OK
        val s = raw.trim().lowercase()
        if (s in DEMO_STATES) return s
        AppLog.w("[QU] qu_demo_estado desconocido: $raw (${DEMO_STATES.joinToString("|")}); uso $DEMO_OK")
        return DEMO_OK
    }

    // ------------------------------------------------------------------ vistas

    /**
     * El panel (reloj y fecha, tarjeta Batería y botón «Pantalla completa»), con el botón conectado a
     * requestMode(false, …). La fuente de datos la pone [updatePanel].
     */
    private fun createPanel(): ExtendedPanelView = ExtendedPanelView(activity).apply {
        onFullscreen = { requestMode(false, "botón del panel") }
        onOpenCarData = { openCarData() }
    }

    /**
     * Elige la fuente del panel: [DemoPanelData] con el estado [demoState] si hay DEMO; si no, mientras no
     * exista la nube (bloque C, CloudPanelData), [NoAccountPanelData]. Si cambia y la proyección está en
     * primer plano, se para la vieja y se arranca la nueva.
     */
    private fun updatePanel() {
        val current = source
        val wanted: PanelDataSource = if (demo) {
            if (current is DemoPanelData && current.state == demoState) current else DemoPanelData(demoState)
        } else {
            NoAccountPanelData
        }
        if (wanted !== current) {
            AppLog.i("[QU] panel: fuente $wanted")
            if (sourceStarted) {
                current.stop()
                wanted.start(activity.applicationContext)
            }
            source = wanted
        }
        panel?.source = wanted
    }

    /**
     * Toque en la tarjeta Batería con algo que hacer en la cuenta (sin cuenta, caducada, clave nueva o
     * apagada). BLOQUE C: abrir CarCloudActivity (la pantalla de «Datos del coche»). Hasta entonces, solo
     * queda en el log.
     */
    private fun openCarData() {
        AppLog.i("[QU] tarjeta Batería tocada: «Datos del coche» llegará con la nube (bloque C)")
    }

    /** Botón flotante «Extendido» de la Pantalla completa: 56 px escalados, arriba a la derecha, alfa 0,6. */
    private fun createFab(): ImageView = ImageView(activity).apply {
        setImageDrawable(AppCompatResources.getDrawable(activity, R.drawable.hql_ic_split))
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(activity, R.color.hql_surface_hi))
            setStroke(2, ContextCompat.getColor(activity, R.color.hql_outline))
        }
        alpha = FAB_ALPHA
        contentDescription = activity.getString(R.string.qu_extended)
        setOnClickListener { requestMode(true, "botón flotante") }
        // Clicable sin ser enfocable: las teclas siguen yendo a la capa táctil
        isFocusable = false
        isFocusableInTouchMode = false
    }

    private fun applyViews() {
        val g = geometry
        val pipNow = pip()
        panel?.let { p ->
            val show = !pipNow && g != null && g.panelPx > 0 && (active || hidingPanel)
            if (show && g != null) {
                val lp = p.layoutParams as FrameLayout.LayoutParams
                if (lp.width != g.panelPx) {
                    lp.width = g.panelPx
                    p.layoutParams = lp
                }
            }
            setVisible(p, show)
        }
        // La capa de carga en la zona de AA: «Android Auto se está iniciando» centrado y el panel a la vista
        loadingOverlay?.let { ov ->
            val right = if (!pipNow && active && g != null) g.panelPx else 0
            val lp = ov.layoutParams as? FrameLayout.LayoutParams
            if (lp != null && lp.rightMargin != right) {
                lp.rightMargin = right
                ov.layoutParams = lp
            }
        }
        applyFab()
    }

    private fun applyFab() {
        val f = fab ?: return
        val show = !pip() && !extended && fits && SystemClock.uptimeMillis() < fabUntilMs
        if (show) sizeFab(f)
        setVisible(f, show)
    }

    private fun sizeFab(f: ImageView) {
        val k = scale()
        val size = max(1, (FAB_PX * k).roundToInt())
        val margin = (FAB_MARGIN_PX * k).roundToInt()
        val lp = f.layoutParams as FrameLayout.LayoutParams
        if (lp.width != size || lp.topMargin != margin || lp.rightMargin != margin) {
            lp.width = size
            lp.height = size
            lp.topMargin = margin
            lp.rightMargin = margin
            f.layoutParams = lp
            val pad = (FAB_PAD_PX * k).roundToInt()
            f.setPadding(pad, pad, pad, pad)
        }
    }

    private fun showFab() {
        fabUntilMs = SystemClock.uptimeMillis() + FAB_SHOW_MS
        handler.removeCallbacks(fabHideRunnable)
        handler.postDelayed(fabHideRunnable, FAB_SHOW_MS)
        applyFab()
    }

    private fun hideFab() {
        fabUntilMs = 0L
        handler.removeCallbacks(fabHideRunnable)
        applyFab()
    }

    private val fabHideRunnable = Runnable {
        fabUntilMs = 0L
        applyFab()
    }

    private fun setVisible(v: View, show: Boolean) {
        val vis = if (show) View.VISIBLE else View.GONE
        if (v.visibility != vis) v.visibility = vis
    }

    /** k = alto/882: las medidas son px de la pantalla del C10, como en Qonecta+. */
    private fun scale(): Float {
        val inner = container.height - container.paddingTop - container.paddingBottom
        val h = if (container.height > 0) inner else HeadUnitScreenConfig.getUsableHeight()
        return if (h > 0) h / C10_HEIGHT else 1f
    }

    private fun pip(): Boolean =
        inPiP || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode)

    private fun modeName(on: Boolean) = if (on) "Extendido" else "Pantalla completa"

    companion object {
        const val EXTRA_DEMO = "qu_demo"
        const val EXTRA_DEMO_STATE = "qu_demo_estado"
        const val EXTRA_MODE = "qu_modo"
        const val MODE_EXTENDED = "extendido"
        const val MODE_FULLSCREEN = "completa"
        const val DEMO_OK = DemoPanelData.STATE_OK

        /** Estados de la DEMO (los pinta DemoPanelData). */
        val DEMO_STATES = DemoPanelData.STATES

        const val TOGGLE_DEBOUNCE_MS = 1500L
        const val PANEL_HIDE_MAX_MS = 1000L
        const val PIP_EXIT_MAX_MS = 1000L
        const val FAB_SHOW_MS = 6000L
        const val FAB_PX = 56
        const val FAB_MARGIN_PX = 16
        const val FAB_PAD_PX = 14
        const val FAB_ALPHA = 0.6f

        /** Alto de referencia, px de la pantalla del C10: k = alto/882. */
        const val C10_HEIGHT = 882f
    }
}

