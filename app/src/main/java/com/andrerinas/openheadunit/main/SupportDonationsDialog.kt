package com.andrerinas.openheadunit.main

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.andrerinas.openheadunit.utils.QrCodeGenerator
import kotlin.math.roundToInt

/**
 * Diálogo de apoyo al proyecto (Ko-fi + PayPal con códigos QR escaneables desde el móvil),
 * con el mismo espíritu y estética que Qonecta+. Funciona 100 % sin conexión en el coche.
 */
object SupportDonationsDialog {

    const val PAYPAL_URL = "https://www.paypal.me/CharlysEV"
    const val KOFI_URL = "https://ko-fi.com/charlysev"
    const val HUB_URL = "https://qonecta-hub.qonecta.workers.dev"

    private const val CARD_BG = 0xFF101924.toInt()
    private const val INNER_CARD_BG = 0xFF152231.toInt()
    private const val KOFI_AMBER = 0xFFFFB547.toInt()
    private const val PAYPAL_TEAL = 0xFF00E5C7.toInt()
    private const val TEXT_PRIMARY = 0xFFEAF2F7.toInt()
    private const val TEXT_BODY = 0xFFD2DFEC.toInt()
    private const val TEXT_MUTED = 0xFF93A4B7.toInt()

    fun show(context: Context): Dialog {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(22))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(CARD_BG)
                setStroke(dp(2), KOFI_AMBER)
            }
        }

        root.addView(TextView(context).apply {
            text = context.getString(com.andrerinas.openheadunit.R.string.core_donate_dialog_title)
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(KOFI_AMBER)
        })

        root.addView(TextView(context).apply {
            text = context.getString(com.andrerinas.openheadunit.R.string.core_donate_dialog_intro)
            textSize = 17.5f
            setTextColor(TEXT_BODY)
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(8), 0, dp(18))
        })

        val cardsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun buildQrCard(titleText: String, subText: String, url: String, badgeColor: Int): View {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(20).toFloat()
                    setColor(INNER_CARD_BG)
                    setStroke(dp(2), badgeColor)
                }
            }
            card.addView(TextView(context).apply {
                text = titleText
                textSize = 20.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(badgeColor)
                gravity = Gravity.CENTER
            })
            val qrBmp = runCatching {
                QrCodeGenerator.generateQrCode(url, dp(185))
            }.getOrNull()
            if (qrBmp != null) {
                card.addView(ImageView(context).apply {
                    setImageBitmap(qrBmp)
                    contentDescription = titleText
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(14).toFloat()
                        setColor(Color.WHITE)
                    }
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                }, LinearLayout.LayoutParams(dp(195), dp(195)).apply {
                    topMargin = dp(12)
                    bottomMargin = dp(12)
                })
            }
            card.addView(TextView(context).apply {
                text = subText
                textSize = 16.5f
                typeface = Typeface.create("monospace", Typeface.BOLD)
                setTextColor(TEXT_PRIMARY)
                gravity = Gravity.CENTER
            })
            return card
        }

        cardsRow.addView(
            buildQrCard(
                context.getString(com.andrerinas.openheadunit.R.string.core_donate_paypal_card),
                "paypal.me/CharlysEV",
                PAYPAL_URL,
                PAYPAL_TEAL
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) }
        )
        cardsRow.addView(
            buildQrCard(
                context.getString(com.andrerinas.openheadunit.R.string.core_donate_kofi_card),
                "ko-fi.com/charlysev",
                KOFI_URL,
                KOFI_AMBER
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) }
        )
        cardsRow.addView(
            buildQrCard(
                "Qonecta Hub",
                "qonecta-hub.qonecta.workers.dev",
                HUB_URL,
                0xFF4CFF9F.toInt()
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(
            cardsRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val scroll = android.widget.ScrollView(context).apply {
            isFillViewport = false
            addView(root)
        }
        val dialog = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setView(scroll)
            .create()

        val footerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        footerRow.addView(TextView(context).apply {
            text = context.getString(com.andrerinas.openheadunit.R.string.core_donate_footer)
            textSize = 16f
            setTextColor(TEXT_MUTED)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(12)
        })

        footerRow.addView(TextView(context).apply {
            text = context.getString(com.andrerinas.openheadunit.R.string.core_close)
            textSize = 18.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(TEXT_PRIMARY)
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(12), dp(26), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(0xFF1A2636.toInt())
                setStroke(dp(2), 0xFF2D3F54.toInt())
            }
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(54)))

        root.addView(
            footerRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.78f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return dialog
    }
}
