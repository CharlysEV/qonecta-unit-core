package com.headqlink.unit.extended

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * El panel del modo Extendido, en la franja derecha encima de la zona donde AA ya no dibuja. Se monta por
 * código con el lenguaje visual de Qonecta+ (CarKit, CarStyle): fondo casi negro, tarjetas #17202B con radio
 * 24 y filo de 2 px #243140, acento #00E5C7.
 *
 * De arriba abajo:
 * 1. Ficha «DEMO» en ámbar, a la derecha. Solo con datos inventados (PanelCarData.demo).
 * 2. Tarjeta Reloj: la hora (112 px, ligera; encoge si no cabe, p. ej. con AM/PM) y la fecha (28 px, gris).
 * 3. Tarjeta Batería: el anillo ([BatteryRingView]) con el %, los km, «Autonomía» y la edad del dato. Sin
 *    dato, un estado vacío con título, explicación y, si hay algo que hacer en la cuenta, «Toca para abrir
 *    Datos del coche». Con dato en EXPIRED, SERVER_KEY o ERROR, un aviso en ámbar debajo del anillo.
 * 4. Botón «Pantalla completa»: píldora de 64 px de alto como poco (el mínimo táctil es 48).
 *
 * Medidas en px del C10 multiplicadas por k = alto/882 (se recalcula al medir), para que se vea igual en la
 * unidad, a 2560 y en el emulador. Un tic de 1 s, solo con el panel visible y en una ventana, repinta la
 * hora y el «hace X» y relee la fuente (sin red: devuelve la última foto). Ningún hijo es enfocable, así
 * que las teclas siguen yendo a la capa táctil de la actividad.
 */
class ExtendedPanelView(context: Context) : LinearLayout(context) {

    /** Toque en «Pantalla completa». */
    var onFullscreen: (() -> Unit)? = null

    /** Toque en la tarjeta Batería con algo que hacer en la cuenta (NO_ACCOUNT, EXPIRED, SERVER_KEY, OFF). */
    var onOpenCarData: (() -> Unit)? = null

    /** De dónde salen los datos de la batería (DEMO, o la nube desde el bloque C). */
    var source: PanelDataSource = NoAccountPanelData
        set(value) {
            if (value === field) return
            field = value
            if (attached) refresh()
        }

    private val chip = TextView(context)
    private val clockCard = LinearLayout(context)
    private val hour = AppCompatTextView(context)
    private val date = TextView(context)
    private val batteryCard = LinearLayout(context)
    private val batteryTitle = TextView(context)
    private val batteryBody = FrameLayout(context)
    private val ring = BatteryRingView(context)
    private val empty = LinearLayout(context)
    private val emptyTitle = TextView(context)
    private val emptyText = TextView(context)
    private val emptyHint = TextView(context)
    private val warning = TextView(context)
    private val button = LinearLayout(context)
    private val buttonIcon = ImageView(context)
    private val buttonLabel = TextView(context)

    private var k = 0f
    private var attached = false
    private var ticking = false
    private var lastKind: PanelCarData.Kind? = null
    private var lastDemo: Boolean? = null

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            scheduleTick()
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(PanelFormat.BG)
        // Nada del panel se queda el foco: las teclas siguen yendo a la capa táctil
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        // 1. Ficha DEMO
        chip.text = context.getString(R.string.qu_demo)
        chip.setTextColor(PanelFormat.BG)
        chip.typeface = Typeface.DEFAULT_BOLD
        chip.gravity = Gravity.CENTER
        chip.includeFontPadding = false
        chip.visibility = GONE
        addView(chip, LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END })

        // 2. Reloj
        clockCard.orientation = VERTICAL
        clockCard.gravity = Gravity.CENTER_HORIZONTAL
        hour.setTextColor(PanelFormat.TEXT)
        hour.typeface = BatteryRingView.LIGHT
        hour.gravity = Gravity.CENTER
        hour.includeFontPadding = false
        hour.maxLines = 1
        clockCard.addView(hour, LayoutParams(MATCH, WRAP))
        date.setTextColor(PanelFormat.DIM)
        date.gravity = Gravity.CENTER
        date.setSingleLine(true)
        date.ellipsize = TextUtils.TruncateAt.END
        clockCard.addView(date, LayoutParams(MATCH, WRAP))
        addView(clockCard, LayoutParams(MATCH, WRAP))

        // 3. Batería
        batteryCard.orientation = VERTICAL
        batteryTitle.text = context.getString(R.string.qu_battery).uppercase()
        batteryTitle.setTextColor(PanelFormat.FAINT)
        batteryTitle.typeface = BatteryRingView.MEDIUM
        batteryTitle.setSingleLine(true)
        batteryTitle.ellipsize = TextUtils.TruncateAt.END
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) batteryTitle.letterSpacing = 0.12f
        batteryCard.addView(batteryTitle, LayoutParams(MATCH, WRAP))

        batteryBody.addView(ring, FrameLayout.LayoutParams(MATCH, MATCH))
        empty.orientation = VERTICAL
        empty.gravity = Gravity.CENTER_HORIZONTAL
        emptyTitle.setTextColor(PanelFormat.TEXT)
        emptyTitle.typeface = BatteryRingView.MEDIUM
        emptyTitle.gravity = Gravity.CENTER
        empty.addView(emptyTitle, LayoutParams(MATCH, WRAP))
        emptyText.setTextColor(PanelFormat.DIM)
        emptyText.gravity = Gravity.CENTER
        emptyText.setLineSpacing(0f, 1.12f)
        empty.addView(emptyText, LayoutParams(MATCH, WRAP))
        emptyHint.text = context.getString(R.string.qu_open_car_data)
        emptyHint.setTextColor(PanelFormat.ACCENT)
        emptyHint.typeface = BatteryRingView.MEDIUM
        emptyHint.gravity = Gravity.CENTER
        empty.addView(emptyHint, LayoutParams(MATCH, WRAP))
        empty.visibility = GONE
        batteryBody.addView(empty, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        batteryCard.addView(batteryBody, LayoutParams(MATCH, 0, 1f))

        warning.setTextColor(PanelFormat.AMBER)
        warning.typeface = BatteryRingView.MEDIUM
        warning.gravity = Gravity.CENTER
        warning.maxLines = 3
        warning.ellipsize = TextUtils.TruncateAt.END
        warning.visibility = GONE
        batteryCard.addView(warning, LayoutParams(MATCH, WRAP))
        batteryCard.setOnClickListener { onOpenCarData?.invoke() }
        batteryCard.isClickable = false
        noFocus(batteryCard)
        addView(batteryCard, LayoutParams(MATCH, 0, 1f))

        // 4. Pantalla completa
        button.orientation = HORIZONTAL
        button.gravity = Gravity.CENTER
        buttonIcon.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.hql_ic_expand))
        buttonIcon.setColorFilter(PanelFormat.TEXT)
        button.addView(buttonIcon)
        buttonLabel.text = context.getString(R.string.qu_fullscreen)
        buttonLabel.setTextColor(PanelFormat.TEXT)
        buttonLabel.typeface = BatteryRingView.MEDIUM
        buttonLabel.setSingleLine(true)
        buttonLabel.ellipsize = TextUtils.TruncateAt.END
        button.addView(buttonLabel)
        button.contentDescription = buttonLabel.text
        button.setOnClickListener { onFullscreen?.invoke() }
        noFocus(button)
        addView(button, LayoutParams(MATCH, WRAP))

        applyScale(1f)
    }

    // ------------------------------------------------------------------ medidas

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // k antes de medir a los hijos: así el primer fotograma ya sale a escala
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && h > 0) {
            applyScale(h / ExtendedUi.C10_HEIGHT)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Aplica k = alto/882 a todas las medidas (px del C10). Solo si cambia. */
    private fun applyScale(scale: Float) {
        if (scale <= 0f || scale == k) return
        k = scale
        val pad = px(PAD)
        setPadding(pad, pad, pad, pad)

        // Ficha DEMO
        chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * k)
        chip.setPadding(px(14), px(6), px(14), px(6))
        chip.background = rounded(PanelFormat.AMBER, px(16), 0, 0)
        (chip.layoutParams as LayoutParams).bottomMargin = px(12)

        // Reloj: la hora encoge sola si no cabe (AM/PM, idiomas), entre 48 y 112 px
        clockCard.setPadding(px(24), px(18), px(24), px(22))
        clockCard.background = card(false)
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            hour,
            max(1, px(48)),
            max(2, px(112)),
            1,
            TypedValue.COMPLEX_UNIT_PX,
        )
        (hour.layoutParams as LayoutParams).height = px(HOUR_BOX)
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28f * k)
        (date.layoutParams as LayoutParams).topMargin = px(4)

        // Batería
        batteryCard.setPadding(px(24), px(20), px(24), px(22))
        batteryCard.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), card(true))
            addState(intArrayOf(), card(false))
        }
        (batteryCard.layoutParams as LayoutParams).topMargin = px(GAP)
        batteryTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f * k)
        (batteryBody.layoutParams as LayoutParams).topMargin = px(8)
        ring.scale = k
        emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f * k)
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f * k)
        (emptyText.layoutParams as LayoutParams).topMargin = px(14)
        emptyHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f * k)
        (emptyHint.layoutParams as LayoutParams).topMargin = px(24)
        empty.setPadding(px(8), 0, px(8), 0)
        warning.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f * k)
        (warning.layoutParams as LayoutParams).topMargin = px(8)

        // Pantalla completa: 64 px de alto como poco
        button.minimumHeight = px(BUTTON_H)
        button.setPadding(px(24), px(12), px(28), px(12))
        button.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(PanelFormat.EDGE, px(BUTTON_H / 2), px(2), PanelFormat.EDGE))
            addState(intArrayOf(), rounded(PanelFormat.SURFACE_TOP, px(BUTTON_H / 2), px(2), PanelFormat.EDGE))
        }
        (button.layoutParams as LayoutParams).topMargin = px(GAP)
        val icon = px(32)
        buttonIcon.layoutParams = LayoutParams(icon, icon)
        buttonLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24f * k)
        buttonLabel.layoutParams = LayoutParams(WRAP, WRAP).also { it.leftMargin = px(12) }
        requestLayout()
    }

    private fun px(v: Int): Int = max(1, (v * k).roundToInt())

    /** Tarjeta: superficie con filo (CarKit.card, sin degradados: colores planos para el vídeo). */
    private fun card(pressed: Boolean): Drawable =
        rounded(if (pressed) PanelFormat.SURFACE_TOP else PanelFormat.SURFACE_HI, px(RADIUS), px(2), PanelFormat.OUTLINE)

    private fun rounded(color: Int, radius: Int, stroke: Int, strokeColor: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    // ------------------------------------------------------------------ tic de 1 s

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        updateTicking()
    }

    override fun onDetachedFromWindow() {
        attached = false
        updateTicking()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateTicking()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateTicking()
    }

    /** El tic solo corre con el panel visible y adjunto a una ventana visible. */
    private fun updateTicking() {
        val should = attached && isShown && windowVisibility == VISIBLE
        if (should == ticking) return
        ticking = should
        removeCallbacks(tick)
        if (should) {
            refresh()
            scheduleTick()
        }
    }

    /** Al principio del segundo siguiente: la hora cambia justo cuando cambia el minuto. */
    private fun scheduleTick() {
        val now = System.currentTimeMillis()
        postDelayed(tick, TICK_MS - now % TICK_MS + 15L)
    }

    // ------------------------------------------------------------------ contenido

    /** Repinta la hora, la fecha y la tarjeta Batería con lo que tenga la fuente ahora. */
    fun refresh() {
        val now = System.currentTimeMillis()
        setText(hour, PanelFormat.timeText(context, now))
        setText(date, PanelFormat.dateText(context, now))
        render(source.read(now), now)
    }

    private fun render(data: PanelCarData, now: Long) {
        if (data.kind != lastKind || data.demo != lastDemo) {
            lastKind = data.kind
            lastDemo = data.demo
            AppLog.i("[QU] panel: ${data.kind}${if (data.demo) " (DEMO)" else ""}${if (data.hasData) " con dato" else ""}")
        }
        setVisible(chip, data.demo)
        val res = resources
        if (data.hasData) {
            setVisible(empty, false)
            setVisible(ring, true)
            val ageMs = PanelFormat.ageMs(data.dataTimeMs, now)
            ring.setData(
                data.socPct,
                data.rangeKm,
                res.getString(R.string.qu_range),
                PanelFormat.ageText(res, ageMs),
                PanelFormat.ageColor(ageMs),
            )
            val warn = when (data.kind) {
                PanelCarData.Kind.EXPIRED -> res.getString(R.string.qu_state_expired_title)
                PanelCarData.Kind.SERVER_KEY -> res.getString(R.string.qu_state_server_key_title)
                PanelCarData.Kind.ERROR ->
                    res.getString(R.string.qu_retry_in, PanelFormat.retryMinutes(data.nextTryMs, now))
                else -> null
            }
            if (warn != null) setText(warning, warn)
            setVisible(warning, warn != null)
        } else {
            setVisible(ring, false)
            setVisible(warning, false)
            setVisible(empty, true)
            val (title, text) = emptyTexts(data, now)
            setText(emptyTitle, title)
            setText(emptyText, text)
            setVisible(emptyText, text.isNotEmpty())
            setVisible(emptyHint, data.kind in PanelCarData.ACCOUNT_KINDS)
        }
        val tappable = data.kind in PanelCarData.ACCOUNT_KINDS
        if (batteryCard.isClickable != tappable) {
            batteryCard.isClickable = tappable
            noFocus(batteryCard)
        }
    }

    /** Título y explicación del estado vacío. */
    private fun emptyTexts(data: PanelCarData, now: Long): Pair<String, String> {
        val res = resources
        return when (data.kind) {
            PanelCarData.Kind.NO_ACCOUNT ->
                res.getString(R.string.qu_state_no_account_title) to res.getString(R.string.qu_state_no_account_text)
            PanelCarData.Kind.OFF ->
                res.getString(R.string.qu_state_off_title) to res.getString(R.string.qu_state_off_text)
            PanelCarData.Kind.EXPIRED ->
                res.getString(R.string.qu_state_expired_title) to res.getString(R.string.qu_state_expired_text)
            PanelCarData.Kind.SERVER_KEY ->
                res.getString(R.string.qu_state_server_key_title) to res.getString(R.string.qu_state_server_key_text)
            PanelCarData.Kind.UNSUPPORTED ->
                res.getString(R.string.qu_state_unsupported_title) to res.getString(R.string.qu_state_unsupported_text)
            PanelCarData.Kind.ERROR -> res.getString(R.string.qu_state_error_title) to
                res.getString(R.string.qu_state_error_text, PanelFormat.retryMinutes(data.nextTryMs, now))
            PanelCarData.Kind.WAITING, PanelCarData.Kind.OK -> res.getString(R.string.qu_state_waiting) to ""
        }
    }

    private fun setText(tv: TextView, s: String) {
        if (!TextUtils.equals(tv.text, s)) tv.text = s
    }

    private fun setVisible(v: View, show: Boolean) {
        val vis = if (show) VISIBLE else GONE
        if (v.visibility != vis) v.visibility = vis
    }

    /** Clicable sin ser enfocable (en API 26+ un clicable pasa a enfocable si no se dice lo contrario). */
    private fun noFocus(v: View) {
        v.isFocusable = false
        v.isFocusableInTouchMode = false
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Medidas en px del C10 (1920x882). */
        const val PAD = 24
        const val GAP = 18
        const val RADIUS = 24
        const val BUTTON_H = 64

        /** Alto de la caja de la hora: 112 px de letra sin el relleno de la fuente. */
        const val HOUR_BOX = 136

        const val TICK_MS = 1000L
    }
}
