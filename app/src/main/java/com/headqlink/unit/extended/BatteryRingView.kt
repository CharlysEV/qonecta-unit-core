package com.headqlink.unit.extended

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * El anillo de batería del panel, portado de Qonecta+ (CarKit.ring y la tarjeta Batería de CarStatusTab):
 * pista en #1E2935 y un arco de 270° que empieza en 135° (abierto por abajo), con trazo de 26 px y extremos
 * redondeados, en el color del nivel. Dentro, el % en grande (100 px, letra ligera) y los km debajo (40 px,
 * en gris); en el hueco de abajo, «Autonomía» (20 px, tenue) y la edad del dato (23 px, en acento o ámbar).
 *
 * Medidas en px del C10 multiplicadas por [scale] (k = alto del panel / 882). Si no cabe, el anillo encoge y
 * lo de dentro con él. Los Paint y el RectF se crean una vez: en onDraw no se crea nada (los textos llegan
 * ya formateados en [setData]).
 */
class BatteryRingView(context: Context) : View(context) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = PanelFormat.SURFACE_TOP
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val big = textPaint(LIGHT, PanelFormat.TEXT)
    private val bigUnit = textPaint(MEDIUM, PanelFormat.DIM)
    private val km = textPaint(MEDIUM, PanelFormat.DIM)
    private val kmUnit = textPaint(MEDIUM, PanelFormat.DIM)
    private val label = textPaint(MEDIUM, PanelFormat.FAINT).apply { textAlign = Paint.Align.CENTER }
    private val age = textPaint(MEDIUM, PanelFormat.ACCENT).apply { textAlign = Paint.Align.CENTER }
    private val oval = RectF()

    private var fraction = 0f
    private var pctText = PanelFormat.NONE
    private var kmText = PanelFormat.NONE
    private var rangeLabel = ""
    private var ageText = ""

    /** k = alto del panel / 882. */
    var scale = 1f
        set(value) {
            if (value > 0f && value != field) {
                field = value
                invalidate()
            }
        }

    init {
        // Sin foco ni toques propios: los toques los recoge la tarjeta
        isFocusable = false
        isFocusableInTouchMode = false
    }

    /**
     * Lo que se pinta. Solo repinta si algo cambia (el panel lo llama cada segundo).
     * @param soc % de batería (NaN: «—» y el anillo vacío)
     * @param rangeKm autonomía en km (NaN: «—»)
     */
    fun setData(soc: Double, rangeKm: Double, rangeLabel: String, ageText: String, ageColor: Int) {
        val f = PanelFormat.ringFraction(soc)
        val col = PanelFormat.levelColor(soc)
        val p = PanelFormat.number(soc)
        val k = PanelFormat.number(rangeKm)
        if (f == fraction && col == arc.color && p == pctText && k == kmText && rangeLabel == this.rangeLabel &&
            ageText == this.ageText && ageColor == age.color
        ) {
            return
        }
        fraction = f
        arc.color = col
        pctText = p
        kmText = k
        this.rangeLabel = rangeLabel
        this.ageText = ageText
        age.color = ageColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val k = scale
        val stroke = STROKE * k
        // Lo de debajo del anillo: «Autonomía» y la edad, como en Qonecta+ (cy + rad + 2 y cy + rad + 44)
        val below = (AGE_DY + AGE_DESCENT) * k
        val rad = min(min(w * 0.34f, RADIUS * k), (h - stroke - below) / 2f)
        if (rad <= stroke) return
        // El bloque entero (anillo, autonomía y edad) centrado en el alto disponible
        val blockH = stroke + 2f * rad + below
        val top = max(0f, (h - blockH) / 2f)
        val cx = w / 2f
        val cy = top + stroke / 2f + rad
        oval.set(cx - rad, cy - rad, cx + rad, cy + rad)

        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        canvas.drawArc(oval, START, SWEEP, false, track)
        if (fraction > 0.002f) canvas.drawArc(oval, START, SWEEP * fraction, false, arc)

        // Dentro del anillo, a escala del propio anillo (si encoge, lo de dentro también)
        val s = rad / RADIUS
        number(canvas, pctText, "%", cx, cy + 22f * s, 100f, s, big, bigUnit)
        number(canvas, kmText, "km", cx, cy + 82f * s, 40f, s, km, kmUnit)

        label.textSize = 20f * k
        canvas.drawText(rangeLabel, cx, cy + rad + LABEL_DY * k, label)
        age.textSize = 23f * k
        canvas.drawText(ageText, cx, cy + rad + AGE_DY * k, age)
    }

    /**
     * Cifra grande con su unidad pequeña detrás, en la misma línea base y centradas en x (CarKit.number):
     * la unidad mide 0,36 de la cifra, entre 20 y 36 px del C10. size son px del C10; s, la escala.
     */
    private fun number(canvas: Canvas, value: String, unit: String, x: Float, y: Float, size: Float, s: Float, vp: Paint, up: Paint) {
        vp.textSize = size * s
        up.textSize = (size * 0.36f).coerceIn(20f, 36f) * s
        val vw = vp.measureText(value)
        val gap = size * 0.12f * s
        val uw = up.measureText(unit)
        val x0 = x - (vw + gap + uw) / 2f
        canvas.drawText(value, x0, y, vp)
        canvas.drawText(unit, x0 + vw + gap, y, up)
    }

    companion object {
        /** Medidas en px del C10. */
        const val RADIUS = 146f
        const val STROKE = 26f
        const val START = 135f
        const val SWEEP = 270f
        const val LABEL_DY = 2f
        const val AGE_DY = 44f
        const val AGE_DESCENT = 8f

        val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        private fun textPaint(tf: Typeface, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            this.color = color
            textAlign = Paint.Align.LEFT
        }
    }
}
