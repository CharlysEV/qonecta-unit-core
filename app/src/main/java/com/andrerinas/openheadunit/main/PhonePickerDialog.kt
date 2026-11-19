package com.andrerinas.openheadunit.main

import android.app.Dialog
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Selector de móviles en formato de grandes cuadros/tarjetas («a modo de cuadros con el nombre del
 * dispositivo en medio»), pensado para leerse de un vistazo en la pantalla 2560x1440 del Leapmotor C10
 * tanto en Android Auto como en Apple CarPlay.
 */
object PhonePickerDialog {

    private object Palette {
        const val BG = 0xFF0B1117.toInt()
        const val SURFACE = 0xFF111820.toInt()
        const val SURFACE_TOP = 0xFF17222D.toInt()
        const val SURFACE_HI = 0xFF15202B.toInt()
        const val OUTLINE = 0xFF263544.toInt()
        const val EDGE = 0xFF22303D.toInt()
        const val TEXT = 0xFFEAF2F7.toInt()
        const val DIM = 0xFFA2B6C5.toInt()
        const val MUTED = 0xFF7D93A5.toInt()
        const val FAINT = 0xFF688091.toInt()
        const val ACCENT = 0xFF00E5C7.toInt()
    }

    data class PhoneItem(
        val name: String,
        val address: String,
        val highlighted: Boolean = false,
        val device: BluetoothDevice? = null,
    )

    fun show(
        context: Context,
        title: String,
        subtitle: String,
        items: List<PhoneItem>,
        pairButtonText: String,
        cancelButtonText: String,
        onSelect: (Int, PhoneItem) -> Unit,
        onPairNew: () -> Unit,
        onCancel: (() -> Unit)? = null,
    ): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setOnCancelListener { onCancel?.invoke() }

        val dm = context.resources.displayMetrics
        val density = dm.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(28), dp(32), dp(28))
            background = GradientDrawable().apply {
                setColor(Palette.SURFACE)
                cornerRadius = dp(28).toFloat()
                setStroke(dp(2), Palette.OUTLINE)
            }
        }

        // Cabecera
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleView = TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(Palette.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val subView = TextView(context).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(Palette.DIM)
            setPadding(0, dp(4), 0, 0)
        }
        titleCol.addView(titleView)
        titleCol.addView(subView)
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val countBadge = TextView(context).apply {
            text = "${items.size} ${if (items.size == 1) "dispositivo" else "dispositivos"}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Palette.ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColor(0x1F00E5C7)
                cornerRadius = dp(999).toFloat()
                setStroke(dp(1), Palette.ACCENT)
            }
        }
        header.addView(countBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Cuadrícula de dispositivos (2 o 3 columnas según ancho de pantalla)
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val gridContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(22), 0, dp(22))
        }

        val cols = when {
            items.size == 1 -> 1
            items.size == 2 -> 2
            dm.widthPixels >= dp(1100) -> 3
            else -> 2
        }

        val rows = items.chunked(cols)
        rows.forEachIndexed { rowIndex, rowItems ->
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = cols.toFloat()
            }
            rowItems.forEachIndexed { colIndex, item ->
                val globalIndex = rowIndex * cols + colIndex
                val card = buildDeviceTile(context, item, dp = ::dp) {
                    dialog.dismiss()
                    onSelect(globalIndex, item)
                }
                val lp = LinearLayout.LayoutParams(0, dp(186), 1f).apply {
                    if (colIndex > 0) leftMargin = dp(18)
                }
                rowLayout.addView(card, lp)
            }
            // Relleno vacío si la última fila tiene menos columnas
            for (empty in rowItems.size until cols) {
                val spacer = View(context)
                val lp = LinearLayout.LayoutParams(0, dp(186), 1f).apply {
                    leftMargin = dp(18)
                }
                rowLayout.addView(spacer, lp)
            }
            val rowLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (rowIndex > 0) topMargin = dp(18)
            }
            gridContainer.addView(rowLayout, rowLp)
        }

        scroll.addView(gridContainer)
        val maxScrollHeight = (dm.heightPixels * 0.56f).roundToInt()
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                weight = 1f
            },
        )

        // Pie con botones grandes
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        val pairBtn = buildActionButton(
            context = context,
            text = pairButtonText,
            primary = false,
            accentStroke = true,
            dp = ::dp,
        ) {
            dialog.dismiss()
            onPairNew()
        }
        val cancelBtn = buildActionButton(
            context = context,
            text = cancelButtonText,
            primary = false,
            accentStroke = false,
            dp = ::dp,
        ) {
            dialog.dismiss()
            onCancel?.invoke()
        }

        footer.addView(
            pairBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(62)).apply {
                rightMargin = dp(16)
            },
        )
        footer.addView(
            cancelBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(62)),
        )
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog.setContentView(root)
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val targetWidth = min((dm.widthPixels * 0.86f).roundToInt(), dp(1560))
            val targetHeight = min((dm.heightPixels * 0.84f).roundToInt(), maxScrollHeight + dp(240))
            win.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        return dialog
    }

    private fun buildDeviceTile(
        context: Context,
        item: PhoneItem,
        dp: (Int) -> Int,
        onClick: () -> Unit,
    ): View {
        val strokeColor = if (item.highlighted) Palette.ACCENT else Palette.EDGE
        val strokeWidth = if (item.highlighted) dp(3) else dp(2)
        val bgColor = if (item.highlighted) 0xFF1A2836.toInt() else Palette.SURFACE_HI

        val baseBg = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dp(24).toFloat()
            setStroke(strokeWidth, strokeColor)
        }
        val ripple = RippleDrawable(
            ColorStateList.valueOf(0x3300E5C7),
            baseBg,
            null,
        )

        return FrameLayout(context).apply {
            background = ripple
            isClickable = true
            isFocusable = true
            setPadding(dp(22), dp(18), dp(22), dp(18))
            setOnClickListener { onClick() }

            // Fila superior: icono móvil + etiqueta de estado
            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val iconDot = TextView(context).apply {
                text = if (item.highlighted) "★ RECIENTE" else "● BLUETOOTH + WI-FI"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(if (item.highlighted) Palette.ACCENT else Palette.FAINT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.08f
            }
            topRow.addView(iconDot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                topRow,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ),
            )

            // Nombre del dispositivo grande y centrado en medio del cuadro
            val centerName = TextView(context).apply {
                text = item.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setTextColor(Palette.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(
                centerName,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                },
            )

            // Fila inferior: MAC a la izquierda y acción «Conectar →» a la derecha
            val bottomRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val macView = TextView(context).apply {
                text = item.address
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Palette.MUTED)
                typeface = Typeface.MONOSPACE
            }
            val connectHint = TextView(context).apply {
                text = "Conectar →"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Palette.ACCENT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            bottomRow.addView(macView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bottomRow.addView(connectHint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                bottomRow,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                ),
            )
        }
    }

    private fun buildActionButton(
        context: Context,
        text: String,
        primary: Boolean,
        accentStroke: Boolean,
        dp: (Int) -> Int,
        onClick: () -> Unit,
    ): TextView {
        val bg = GradientDrawable().apply {
            setColor(if (primary) Palette.ACCENT else Palette.SURFACE_TOP)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(2), if (accentStroke) Palette.ACCENT else Palette.EDGE)
        }
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(if (primary) Palette.BG else if (accentStroke) Palette.ACCENT else Palette.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(26), 0, dp(26), 0)
            background = RippleDrawable(ColorStateList.valueOf(0x3300E5C7), bg, null)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }
}
