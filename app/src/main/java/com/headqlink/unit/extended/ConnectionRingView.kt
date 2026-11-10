package com.headqlink.unit.extended

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Contador circular animado e indicador visual de conexión para la pantalla de inicio de Qonecta Unit.
 * Inspirado en el diseño con aura radial, pasos de enlace y cronómetro en vivo ("1 s, 2 s, 3 s...")
 * durante la negociación de Android Auto / Apple CarPlay / USB.
 */
class ConnectionRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class State { IDLE, CONNECTING, CONNECTED }

    var state: State = State.IDLE
        set(value) {
            if (field != value) {
                field = value
                updateAnimationState()
                invalidate()
            }
        }

    var elapsedSeconds: Int = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var protocolTitle: String = "Android Auto"
        set(value) {
            field = value
            invalidate()
        }

    var subtitleText: String = "Esperando al móvil"
        set(value) {
            field = value
            invalidate()
        }

    var activeStep: Int = 1 // 1..3
        set(value) {
            field = value.coerceIn(1, 3)
            invalidate()
        }

    private var sweepAngleDeg: Float = 0f
    private var pulsePhase: Float = 0f

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweepAngleDeg = it.animatedValue as Float
            pulsePhase = (sweepAngleDeg / 360f)
            invalidate()
        }
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val trackRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val innerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val stepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val mainTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        color = Color.WHITE
    }
    private val protoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val arcRect = RectF()
    private val outerRect = RectF()
    private val stepRect = RectF()

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun updateAnimationState() {
        if (state == State.CONNECTING && isAttachedToWindow) {
            if (!animator.isStarted) animator.start()
        } else {
            if (animator.isStarted) animator.cancel()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimationState()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val accent = QonectaTheme.accent
        val r = Color.red(accent)
        val g = Color.green(accent)
        val b = Color.blue(accent)

        val cx = w * 0.5f
        val cy = h * 0.54f
        val maxR = min(w, h) * 0.40f

        // 1. Aura radial suave de fondo (estilo LeapLink verde/turquesa)
        val auraRadius = maxR * 1.38f
        glowPaint.shader = RadialGradient(
            cx,
            cy,
            auraRadius,
            intArrayOf(
                Color.argb(if (state == State.CONNECTING) 76 else 44, r, g, b),
                Color.argb(if (state == State.CONNECTING) 28 else 14, r, g, b),
                Color.argb(0, r, g, b),
            ),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, auraRadius, glowPaint)

        // 2. Indicadores superiores de pasos (•  •  ━━)
        val stepY = cy - maxR - dp(16f)
        val dotR = dp(3.5f)
        val barW = dp(20f)
        val gap = dp(8f)
        val totalStepW = dotR * 2 + gap + dotR * 2 + gap + barW
        var curX = cx - totalStepW / 2f

        for (i in 1..3) {
            val isActive = when (state) {
                State.IDLE -> i == 1
                State.CONNECTING -> i <= activeStep
                State.CONNECTED -> true
            }
            stepPaint.color = if (isActive) accent else Color.argb(70, 234, 242, 247)
            if (i < 3) {
                canvas.drawCircle(curX + dotR, stepY, dotR, stepPaint)
                curX += dotR * 2 + gap
            } else {
                stepRect.set(curX, stepY - dotR, curX + barW, stepY + dotR)
                canvas.drawRoundRect(stepRect, dotR, dotR, stepPaint)
            }
        }

        // 3. Anillo exterior fino decorativo
        outerRingPaint.strokeWidth = dp(1.5f)
        outerRingPaint.color = Color.argb(48, r, g, b)
        val outerR = maxR * 1.12f
        outerRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        canvas.drawOval(outerRect, outerRingPaint)

        // 4. Círculo interior oscuro translúcido
        innerFillPaint.color = Color.argb(190, 9, 20, 28)
        canvas.drawCircle(cx, cy, maxR, innerFillPaint)

        // 5. Pista del anillo principal
        trackRingPaint.strokeWidth = dp(5.5f)
        trackRingPaint.color = Color.argb(55, r, g, b)
        arcRect.set(cx - maxR, cy - maxR, cx + maxR, cy + maxR)
        canvas.drawOval(arcRect, trackRingPaint)

        // 6. Arco activo / giratorio
        arcPaint.strokeWidth = dp(6f)
        arcPaint.shader = LinearGradient(
            cx - maxR,
            cy - maxR,
            cx + maxR,
            cy + maxR,
            accent,
            Color.WHITE,
            Shader.TileMode.CLAMP,
        )
        when (state) {
            State.IDLE -> {
                canvas.drawArc(arcRect, -90f, 110f, false, arcPaint)
            }
            State.CONNECTING -> {
                canvas.drawArc(arcRect, sweepAngleDeg - 90f, 125f, false, arcPaint)
            }
            State.CONNECTED -> {
                canvas.drawArc(arcRect, 0f, 360f, false, arcPaint)
            }
        }

        // 7. Textos centrales (Contador de segundos / Estado)
        when (state) {
            State.CONNECTING -> {
                mainTextPaint.textSize = dp(36f)
                mainTextPaint.color = Color.WHITE
                canvas.drawText("${elapsedSeconds} s", cx, cy - dp(4f), mainTextPaint)

                protoTextPaint.textSize = dp(16f)
                protoTextPaint.color = accent
                canvas.drawText(protocolTitle, cx, cy + dp(20f), protoTextPaint)

                subTextPaint.textSize = dp(13.5f)
                subTextPaint.color = Color.argb(195, 210, 228, 238)
                canvas.drawText(subtitleText, cx, cy + dp(39f), subTextPaint)
            }
            State.CONNECTED -> {
                mainTextPaint.textSize = dp(30f)
                mainTextPaint.color = accent
                canvas.drawText("ACTIVO", cx, cy - dp(4f), mainTextPaint)

                protoTextPaint.textSize = dp(16f)
                protoTextPaint.color = Color.WHITE
                canvas.drawText(protocolTitle, cx, cy + dp(20f), protoTextPaint)

                subTextPaint.textSize = dp(13.5f)
                subTextPaint.color = Color.argb(195, 210, 228, 238)
                canvas.drawText("Toca para abrir", cx, cy + dp(39f), subTextPaint)
            }
            State.IDLE -> {
                mainTextPaint.textSize = dp(28f)
                mainTextPaint.color = Color.WHITE
                canvas.drawText("LISTO", cx, cy - dp(4f), mainTextPaint)

                protoTextPaint.textSize = dp(15.5f)
                protoTextPaint.color = accent
                canvas.drawText(protocolTitle, cx, cy + dp(20f), protoTextPaint)

                subTextPaint.textSize = dp(13.5f)
                subTextPaint.color = Color.argb(185, 175, 200, 215)
                canvas.drawText(subtitleText, cx, cy + dp(39f), subTextPaint)
            }
        }
    }
}
