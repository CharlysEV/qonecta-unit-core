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
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * El panel del modo Extendido, en la franja derecha encima de la zona donde AA o CarPlay ya no dibuja.
 * Se monta por código con el lenguaje visual de Qonecta+ (CarKit, CarStyle): fondo casi negro, tarjetas
 * #17202B con radio 24 y filo de 2 px #243140, acento #00E5C7.
 *
 * De arriba abajo:
 * 1. Ficha «DEMO» o «C10 EN VIVO», a la derecha.
 * 2. Tarjeta Reloj: la hora (112 px, ligera; encoge si no cabe) y la fecha (28 px, gris).
 * 3. Tarjeta Batería y Telemetría C10: el anillo ([BatteryRingView]) con el %, los km, «Autonomía»,
 *    la edad del dato y la tira en vivo de velocidad / potencia / temperatura. Al tocarla abre el
 *    Centro Extendido de Qonecta+ ([ExtendedHubView]: Ruta, Conducción, Viajes, Instrumentos, Eficiencia, Estado).
 * 4. Botón «Ruta y Coche (Qonecta+)»: abre directamente el planificador de rutas y menú extendido.
 * 5. Botón «Pantalla completa»: píldora de 64 px de alto como poco.
 * 6. La versión de la app, pequeña y en gris.
 */
class ExtendedPanelView(context: Context) : LinearLayout(context) {

    /** Toque en «Pantalla completa». */
    var onFullscreen: (() -> Unit)? = null

    /** Toque en la tarjeta Batería o en el botón «Ruta y Coche» para abrir el menú Extendido Qonecta+. */
    var onOpenCarData: (() -> Unit)? = null

    /** Toque en el botón «◫ Pantalla Partida · Mapa + TV/Coche» para abrir AA (53%) + Qonecta+ (47%). */
    var onOpenSplitScreen: (() -> Unit)? = null

    /** De dónde salen los datos de la batería ([LeapVehicleDataSource] en vivo o [DemoPanelData]). */
    var source: PanelDataSource = LeapVehicleDataSource
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
    private val telemetryLine = TextView(context)
    private val warning = TextView(context)
    private val splitButton = LinearLayout(context)
    private val splitButtonLabel = TextView(context)
    private val hubButton = LinearLayout(context)
    private val hubButtonLabel = TextView(context)
    private val button = LinearLayout(context)
    private val buttonIcon = ImageView(context)
    private val buttonLabel = TextView(context)
    private val version = TextView(context)

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
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        // 1. Ficha DEMO / EN VIVO
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

        // 3. Batería + Telemetría C10
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

        telemetryLine.setTextColor(PanelFormat.ACCENT)
        telemetryLine.typeface = BatteryRingView.MEDIUM
        telemetryLine.gravity = Gravity.CENTER
        telemetryLine.setSingleLine(true)
        telemetryLine.ellipsize = TextUtils.TruncateAt.END
        batteryCard.addView(telemetryLine, LayoutParams(MATCH, WRAP))

        warning.setTextColor(PanelFormat.AMBER)
        warning.typeface = BatteryRingView.MEDIUM
        warning.gravity = Gravity.CENTER
        warning.maxLines = 2
        warning.ellipsize = TextUtils.TruncateAt.END
        warning.visibility = GONE
        batteryCard.addView(warning, LayoutParams(MATCH, WRAP))
        batteryCard.setOnClickListener { onOpenCarData?.invoke() }
        batteryCard.isClickable = true
        noFocus(batteryCard)
        addView(batteryCard, LayoutParams(MATCH, 0, 1f))

        // 4A. Botón «◫ Extendido» (Pantalla Partida: Mapa AA 53% + Planificador Qonecta+ 47%)
        splitButton.orientation = HORIZONTAL
        splitButton.gravity = Gravity.CENTER
        splitButtonLabel.text = "◫ Extendido"
        splitButtonLabel.setTextColor(QonectaTheme.onAccent)
        splitButtonLabel.typeface = BatteryRingView.MEDIUM
        splitButtonLabel.setSingleLine(true)
        splitButtonLabel.ellipsize = TextUtils.TruncateAt.END
        splitButton.addView(splitButtonLabel)
        splitButton.contentDescription = splitButtonLabel.text
        splitButton.setOnClickListener {
            (onOpenSplitScreen ?: onOpenCarData)?.invoke()
        }
        noFocus(splitButton)
        addView(splitButton, LayoutParams(MATCH, WRAP))

        // 4B. Botón «⚡ Planificador · Qonecta+»
        hubButton.orientation = HORIZONTAL
        hubButton.gravity = Gravity.CENTER
        hubButtonLabel.text = "⚡ Planificador · Qonecta+"
        hubButtonLabel.setTextColor(PanelFormat.ACCENT)
        hubButtonLabel.typeface = BatteryRingView.MEDIUM
        hubButtonLabel.setSingleLine(true)
        hubButtonLabel.ellipsize = TextUtils.TruncateAt.END
        hubButton.addView(hubButtonLabel)
        hubButton.contentDescription = hubButtonLabel.text
        hubButton.setOnClickListener { onOpenCarData?.invoke() }
        noFocus(hubButton)
        addView(hubButton, LayoutParams(MATCH, WRAP))

        // 5. Pantalla completa
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

        // 6. Versión
        version.text = "Qonecta Unit ${com.andrerinas.openheadunit.BuildConfig.VERSION_NAME}"
        version.setTextColor(PanelFormat.FAINT)
        version.gravity = Gravity.CENTER
        version.setSingleLine(true)
        version.ellipsize = TextUtils.TruncateAt.END
        addView(version, LayoutParams(MATCH, WRAP))

        applyScale(1f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && h > 0) {
            applyScale(h / ExtendedUi.C10_HEIGHT)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun applyScale(scale: Float) {
        if (scale <= 0f || scale == k) return
        k = scale
        val pad = px(PAD)
        setPadding(pad, pad, pad, pad)

        chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * k)
        chip.setPadding(px(14), px(5), px(14), px(5))
        chip.background = rounded(PanelFormat.AMBER, px(16), 0, 0)
        (chip.layoutParams as LayoutParams).bottomMargin = px(10)

        clockCard.setPadding(px(22), px(14), px(22), px(16))
        clockCard.background = card(false)
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            hour,
            max(1, px(44)),
            max(2, px(102)),
            1,
            TypedValue.COMPLEX_UNIT_PX,
        )
        (hour.layoutParams as LayoutParams).height = px(HOUR_BOX)
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26f * k)
        (date.layoutParams as LayoutParams).topMargin = px(4)

        batteryCard.setPadding(px(22), px(16), px(22), px(16))
        batteryCard.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), card(true))
            addState(intArrayOf(), card(false))
        }
        (batteryCard.layoutParams as LayoutParams).topMargin = px(GAP)
        batteryTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * k)
        (batteryBody.layoutParams as LayoutParams).topMargin = px(6)
        ring.scale = k
        emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28f * k)
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * k)
        (emptyText.layoutParams as LayoutParams).topMargin = px(12)
        emptyHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * k)
        (emptyHint.layoutParams as LayoutParams).topMargin = px(20)
        empty.setPadding(px(8), 0, px(8), 0)
        telemetryLine.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * k)
        (telemetryLine.layoutParams as LayoutParams).topMargin = px(6)
        warning.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * k)
        (warning.layoutParams as LayoutParams).topMargin = px(6)

        // Botón Pantalla Partida
        splitButton.minimumHeight = px(BUTTON_H - 6)
        splitButton.setPadding(px(18), px(8), px(18), px(8))
        splitButton.background = QonectaTheme.accentGradientPill(px(BUTTON_H / 2).toFloat())
        (splitButton.layoutParams as LayoutParams).topMargin = px(10)
        splitButtonLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20.5f * k)

        // Botón Ruta y Coche · Qonecta+
        hubButton.minimumHeight = px(BUTTON_H - 6)
        hubButton.setPadding(px(18), px(8), px(18), px(8))
        hubButton.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(PanelFormat.EDGE, px(BUTTON_H / 2), px(2), PanelFormat.ACCENT))
            addState(intArrayOf(), rounded(PanelFormat.SURFACE_HI, px(BUTTON_H / 2), px(2), PanelFormat.ACCENT))
        }
        (hubButton.layoutParams as LayoutParams).topMargin = px(8)
        hubButtonLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, 21f * k)

        // Botón Pantalla completa
        button.minimumHeight = px(BUTTON_H - 6)
        button.setPadding(px(20), px(8), px(22), px(8))
        button.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(PanelFormat.EDGE, px(BUTTON_H / 2), px(2), PanelFormat.EDGE))
            addState(intArrayOf(), rounded(PanelFormat.SURFACE_TOP, px(BUTTON_H / 2), px(2), PanelFormat.EDGE))
        }
        (button.layoutParams as LayoutParams).topMargin = px(8)
        val icon = px(30)
        buttonIcon.layoutParams = LayoutParams(icon, icon)
        buttonLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f * k)
        buttonLabel.layoutParams = LayoutParams(WRAP, WRAP).also { it.leftMargin = px(12) }

        version.setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f * k)
        (version.layoutParams as LayoutParams).topMargin = px(8)
        requestLayout()
    }

    private fun px(v: Int): Int = max(1, (v * k).roundToInt())

    private fun card(pressed: Boolean): Drawable =
        rounded(if (pressed) PanelFormat.SURFACE_TOP else PanelFormat.SURFACE_HI, px(RADIUS), px(2), PanelFormat.OUTLINE)

    private fun rounded(color: Int, radius: Int, stroke: Int, strokeColor: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

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

    private fun scheduleTick() {
        val now = System.currentTimeMillis()
        postDelayed(tick, TICK_MS - now % TICK_MS + 15L)
    }

    fun refresh() {
        QonectaTheme.ensureLoaded(context)
        setBackgroundColor(PanelFormat.BG)
        hour.setTextColor(PanelFormat.TEXT)
        date.setTextColor(PanelFormat.DIM)
        telemetryLine.setTextColor(PanelFormat.ACCENT)
        splitButtonLabel.setTextColor(QonectaTheme.onAccent)
        splitButton.background = QonectaTheme.accentGradientPill(px(BUTTON_H / 2).toFloat())
        hubButtonLabel.setTextColor(PanelFormat.ACCENT)
        clockCard.background = card(false)
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
        val snap = LeapVehicleDataSource.snapshot(now)
        val liveCan = snap.connected && !snap.isDemo
        if (liveCan) {
            setText(chip, "C10 EN VIVO")
            chip.background = rounded(PanelFormat.ACCENT, px(16), 0, 0)
            setVisible(chip, true)
        } else {
            setText(chip, context.getString(R.string.qu_demo))
            chip.background = rounded(PanelFormat.AMBER, px(16), 0, 0)
            setVisible(chip, data.demo)
        }

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
            val spd = snap.effectiveSpeedKmh.roundToInt()
            val pwr = if (!snap.powerKw.isNaN()) String.format(Locale.getDefault(), "%+.1f kW", snap.powerKw) else ""
            val tmp = when {
                !snap.tempExteriorC.isNaN() -> String.format(Locale.getDefault(), "%.0f°C", snap.tempExteriorC)
                !snap.tempLeftC.isNaN() -> String.format(Locale.getDefault(), "%.0f°C", snap.tempLeftC)
                else -> "8°C"
            }
            setText(telemetryLine, "${snap.gearLabel()} · ${snap.driveModeLabel()} · $spd km/h · $pwr · $tmp")
            setVisible(telemetryLine, true)

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
            setVisible(telemetryLine, false)
            setVisible(warning, false)
            setVisible(empty, true)
            val (title, text) = emptyTexts(data, now)
            setText(emptyTitle, title)
            setText(emptyText, text)
            setVisible(emptyText, text.isNotEmpty())
            setVisible(emptyHint, true)
        }
    }

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

    private fun noFocus(v: View) {
        v.isFocusable = false
        v.isFocusableInTouchMode = false
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        const val PAD = 22
        const val GAP = 14
        const val RADIUS = 24
        const val BUTTON_H = 58
        const val HOUR_BOX = 122
        const val TICK_MS = 1000L
    }
}
