package com.headqlink.unit.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Pastilla flotante discreta («← Volver a Qonecta») sobre apps del sistema como DocumentsUI o
 * Ajustes Bluetooth en Android Automotive / LeapOS, donde el sistema no ofrece botón físico Atrás.
 *
 * Totalmente opcional: solo se muestra si el usuario concedió `SYSTEM_ALERT_WINDOW` y se cierra
 * automáticamente en cuanto cualquier Activity de Qonecta vuelve a primer plano.
 */
object CarReturnPillOverlay {

    private const val AUTO_DISMISS_MS = 180_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var pillView: View? = null

    private val autoDismissRunnable = Runnable { dismiss() }

    fun canShow(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun requestPermissionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    fun showIfPermitted(context: Context, returnActivityClass: Class<out Activity>) {
        if (!canShow(context)) return
        val appCtx = context.applicationContext
        val showAction = Runnable { showOnMain(appCtx, returnActivityClass) }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showAction.run()
        } else {
            mainHandler.post(showAction)
        }
    }

    fun dismiss() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dismissOnMain()
        } else {
            mainHandler.post { dismissOnMain() }
        }
    }

    private fun showOnMain(appCtx: Context, returnActivityClass: Class<out Activity>) {
        dismissOnMain()
        val manager = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val d = appCtx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).roundToInt()

        val pill = TextView(appCtx).apply {
            text = "← Volver a Qonecta"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(0xE60D1724.toInt())
                setStroke(dp(2).coerceAtLeast(2), 0xFF00E5C7.toInt())
            }
            elevation = dp(10).toFloat()
            setOnClickListener {
                dismiss()
                val back = Intent(appCtx, returnActivityClass).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                }
                runCatching { appCtx.startActivity(back) }
            }
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(20)
            y = dp(14)
        }

        if (runCatching { manager.addView(pill, lp) }.isSuccess) {
            wm = manager
            pillView = pill
            mainHandler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)
        }
    }

    private fun dismissOnMain() {
        val v = pillView
        val manager = wm
        pillView = null
        wm = null
        if (v != null && manager != null) {
            runCatching { manager.removeViewImmediate(v) }
                .onFailure { runCatching { manager.removeView(v) } }
        }
    }
}
