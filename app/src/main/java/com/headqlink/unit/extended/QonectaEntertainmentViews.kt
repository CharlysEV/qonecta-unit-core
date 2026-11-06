package com.headqlink.unit.extended

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import com.andrerinas.openheadunit.cluster.ClusterMapController
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Vistas del menú Extendido de Qonecta+ en Qonecta Unit con visibilidad optimizada para la pantalla
 * de 14,6" (2560x1440) del Leapmotor C10:
 * - Radio por Internet + Mi Música USB/Coche (radio-browser.info + listas M3U + ICY StreamTitle + Auto-Resume + Logos)
 * - Televisión / TDT / IPTV (TDTChannels tv.json + IPTV-org + M3U + Guía EPG XMLTV en vivo + AdBlock Web)
 * - Karaoke Sincronizado (LRCLIB LRC en tiempo real con letra gigante + seguimiento de Radio/AA + modo seguro > 10 km/h)
 * - Personalización y Continuidad Visual Qonecta+ (CarTheme / CarKit / CarStyle + Acuario Interactivo Qonecta+)
 */
object QonectaEntertainmentViews {

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val bgExecutor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun c10Density(context: Context): Float = ExtendedHubView.c10Density(context)

    private fun TextView.setTextSp(sp: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * c10Density(context))
    }

    // Paleta de fondos vibrantes para insignias de emisoras/canales cuando no tienen logo externo
    private val BADGE_PALETTE = intArrayOf(
        0xFF00796B.toInt(),
        0xFF1565C0.toInt(),
        0xFF2E7D32.toInt(),
        0xFFAD1457.toInt(),
        0xFF6A1B9A.toInt(),
        0xFFD84315.toInt(),
        0xFF0277BD.toInt(),
        0xFF4527A0.toInt()
    )

    // =========================================================================
    // 1. PESTAÑA RADIO POR INTERNET + MI MÚSICA USB (Portado de RadioScreen + RadioPlayer + MusicLibrary)
    // =========================================================================

    fun buildRadioView(context: Context, isSplitScreen: Boolean = false): View {
        QonectaTheme.ensureLoaded(context)
        val density = c10Density(context)
        fun dp(v: Int): Int = (v * density).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ---------------------------------------------------------------------
        // 1A. CABECERA HERO «SONANDO AHORA» (Alta visibilidad + Logo + Ecualizador en vivo)
        // ---------------------------------------------------------------------
        val nowPlayingCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = QonectaTheme.cardDrawable(dp(22).toFloat(), QonectaTheme.accent, dp(2))
        }

        val npLogoFrame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(QonectaTheme.surfaceHi)
                setStroke(dp(2), QonectaTheme.accent)
            }
            clipToOutline = true
        }
        val npFallbackBadge = TextView(context).apply {
            text = "📻"
            setTextSp(30f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val npLogoImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val npEqualizer = LiveEqualizerView(context).apply {
            visibility = View.GONE
        }
        npLogoFrame.addView(npFallbackBadge, FrameLayout.LayoutParams(MATCH, MATCH))
        npLogoFrame.addView(npLogoImage, FrameLayout.LayoutParams(MATCH, MATCH))
        npLogoFrame.addView(
            npEqualizer,
            FrameLayout.LayoutParams(MATCH, dp(22), Gravity.BOTTOM)
        )
        nowPlayingCard.addView(npLogoFrame, LinearLayout.LayoutParams(dp(76), dp(76)))

        val npTexts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(14), 0)
        }

        val npTitleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val npLiveBadge = TextView(context).apply {
            text = "LISTA"
            setTextSp(12.5f)
            setTextColor(QonectaTheme.onAccent)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = QonectaTheme.accentGradientPill(dp(999).toFloat())
        }
        val npTitle = TextView(context).apply {
            text = "Radio Qonecta+ · Elige una emisora o canción USB"
            setTextSp(23f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, 0, 0)
        }
        npTitleRow.addView(npLiveBadge)
        npTitleRow.addView(npTitle, LinearLayout.LayoutParams(0, WRAP, 1f))

        val npSub = TextView(context).apply {
            text = "Sigue sonando en segundo plano con Android Auto / CarPlay y muestra título en vivo en el cuadro C10"
            setTextSp(16f)
            setTextColor(QonectaTheme.dim)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        npTexts.addView(npTitleRow)
        npTexts.addView(npSub)
        nowPlayingCard.addView(npTexts, LinearLayout.LayoutParams(0, WRAP, 1f))

        val prevBtn = pillButton(context, "⏮", false, textSp = 18f, padHdp = 18) {
            QonectaMediaEngine.stepRadio(context, -1)
        }
        val playPauseBtn = pillButton(context, "▶ Sonar", true, textSp = 17f, padHdp = 22) {
            QonectaMediaEngine.toggleRadio(context)
        }
        val nextBtn = pillButton(context, "⏭", false, textSp = 18f, padHdp = 18) {
            QonectaMediaEngine.stepRadio(context, 1)
        }
        val stopBtn = pillButton(context, "⏹", false, textSp = 17f, padHdp = 16) {
            QonectaMediaEngine.stopRadio(context)
        }
        val favNowBtn = pillButton(context, "☆", false, textSp = 20f, padHdp = 16) {}
        val autoResumeBtn = pillButton(
            context,
            if (QonectaMediaEngine.isRadioAutoResumeEnabled(context)) "🔄 Auto: SÍ" else "🔄 Auto: NO",
            QonectaMediaEngine.isRadioAutoResumeEnabled(context),
            textSp = 14.5f,
            padHdp = 14
        ) {}

        nowPlayingCard.addView(prevBtn, LinearLayout.LayoutParams(WRAP, dp(52)).apply { rightMargin = dp(8) })
        nowPlayingCard.addView(playPauseBtn, LinearLayout.LayoutParams(WRAP, dp(52)).apply { rightMargin = dp(8) })
        nowPlayingCard.addView(nextBtn, LinearLayout.LayoutParams(WRAP, dp(52)).apply { rightMargin = dp(8) })
        nowPlayingCard.addView(stopBtn, LinearLayout.LayoutParams(WRAP, dp(52)).apply { rightMargin = dp(8) })
        nowPlayingCard.addView(favNowBtn, LinearLayout.LayoutParams(WRAP, dp(52)).apply { rightMargin = dp(8) })
        nowPlayingCard.addView(autoResumeBtn, LinearLayout.LayoutParams(WRAP, dp(52)))

        root.addView(nowPlayingCard, LinearLayout.LayoutParams(MATCH, WRAP))

        // ---------------------------------------------------------------------
        // 1B. BARRA DE PESTAÑAS GRANDES + PAÍS (A-Z) + BUSCADOR + MI MÚSICA USB
        // ---------------------------------------------------------------------
        val controlsCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = QonectaTheme.cardDrawable(dp(20).toFloat())
        }

        val sourceRowScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val sourceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 0 = Directas, 1 = Top por País (150), 2 = Favoritas, 3 = Recientes, 4 = Mis Listas M3U, 5 = Mi Música USB
        var currentMode = 0
        var currentCountry = QonectaMediaEngine.getSelectedRadioCountry(context)
        var currentQuery = ""
        var cachedUsbMusic: List<QonectaMediaEngine.RadioStation>? = null
        val modeButtons = ArrayList<TextView>()
        val countryButtons = ArrayList<Pair<String, TextView>>()

        // Barra contextual (Selector de país A-Z / Controles de Mi Música USB / Selector de Listas M3U)
        val contextBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val gridScroll = ScrollView(context).apply { isFillViewport = true }
        val stationsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(12))
        }
        gridScroll.addView(stationsContainer)

        fun renderStationsList(stations: List<QonectaMediaEngine.RadioStation>, emptyMsg: String) {
            stationsContainer.removeAllViews()
            if (stations.isEmpty()) {
                stationsContainer.addView(TextView(context).apply {
                    text = emptyMsg
                    setTextSp(18f)
                    setTextColor(QonectaTheme.dim)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    setPadding(dp(28), dp(48), dp(28), dp(48))
                })
                return
            }

            val cols = if (isSplitScreen) 2 else 3
            val rows = stations.chunked(cols)
            for (rowItems in rows) {
                val rowLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = cols.toFloat()
                }
                for (i in 0 until cols) {
                    if (i < rowItems.size) {
                        val st = rowItems[i]
                        val globalIdx = stations.indexOf(st)
                        val isPlayingThis = QonectaMediaEngine.currentStation()?.url == st.url &&
                            (QonectaMediaEngine.isRadioPlaying || QonectaMediaEngine.isRadioBuffering)
                        val isFav = !st.local && QonectaMediaEngine.isRadioFavorite(context, st)

                        val itemCard = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(14), dp(12), dp(14), dp(12))
                            background = GradientDrawable(
                                GradientDrawable.Orientation.TOP_BOTTOM,
                                if (isPlayingThis) {
                                    intArrayOf(
                                        QonectaTheme.withAlpha(QonectaTheme.accent, 52),
                                        QonectaTheme.surfaceHi
                                    )
                                } else {
                                    intArrayOf(QonectaTheme.surfaceTop, QonectaTheme.surfaceHi)
                                }
                            ).apply {
                                cornerRadius = dp(18).toFloat()
                                setStroke(
                                    dp(if (isPlayingThis) 2 else 1),
                                    if (isPlayingThis) QonectaTheme.accent else QonectaTheme.outline
                                )
                            }
                            setOnClickListener {
                                QonectaMediaEngine.playStation(context, stations, globalIdx)
                            }
                            if (!st.local) {
                                setOnLongClickListener {
                                    val added = QonectaMediaEngine.toggleRadioFavorite(context, st)
                                    Toast.makeText(
                                        context,
                                        if (added) "★ Añadida a Favoritos: ${st.name}" else "Quitada de Favoritos: ${st.name}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    renderStationsList(stations, emptyMsg)
                                    true
                                }
                            }
                        }

                        // Logo de la emisora (dp(60)x60) con monograma de respaldo
                        val logoBox = createMediaLogoBox(
                            context = context,
                            title = st.name,
                            logoUrl = st.logo,
                            isLocalMusic = st.local,
                            isPlaying = isPlayingThis,
                            sizeDp = 60
                        )
                        itemCard.addView(logoBox, LinearLayout.LayoutParams(dp(60), dp(60)))

                        val textCol = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(14), 0, dp(8), 0)
                        }
                        textCol.addView(TextView(context).apply {
                            text = st.name
                            setTextSp(19f)
                            setTextColor(if (isPlayingThis) QonectaTheme.accent else QonectaTheme.text)
                            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                            setSingleLine(true)
                            ellipsize = TextUtils.TruncateAt.END
                        })

                        val subRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(4), 0, 0)
                        }
                        if (isPlayingThis) {
                            subRow.addView(TextView(context).apply {
                                text = if (QonectaMediaEngine.isRadioBuffering) "⏳ CONECTANDO" else "🔊 SONANDO"
                                setTextSp(11.5f)
                                setTextColor(QonectaTheme.onAccent)
                                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                                setPadding(dp(8), dp(2), dp(8), dp(2))
                                background = QonectaTheme.accentGradientPill(dp(999).toFloat())
                            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
                        }
                        val flagPrefix = if (!st.local && st.country.length == 2) {
                            "${QonectaMediaEngine.countryFlag(st.country)} "
                        } else if (st.local) {
                            "♪ "
                        } else ""
                        subRow.addView(TextView(context).apply {
                            text = flagPrefix + st.group.ifBlank {
                                if (st.local) "Audio USB / Local" else "Radio en directo"
                            }
                            setTextSp(14.5f)
                            setTextColor(if (isPlayingThis) QonectaTheme.text else QonectaTheme.dim)
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            setSingleLine(true)
                            ellipsize = TextUtils.TruncateAt.END
                        }, LinearLayout.LayoutParams(0, WRAP, 1f))

                        textCol.addView(subRow)
                        itemCard.addView(textCol, LinearLayout.LayoutParams(0, WRAP, 1f))

                        if (!st.local) {
                            val starBtn = TextView(context).apply {
                                text = if (isFav) "★" else "☆"
                                setTextSp(24f)
                                setTextColor(if (isFav) QonectaTheme.amber else QonectaTheme.faint)
                                gravity = Gravity.CENTER
                                setPadding(dp(10), dp(6), dp(6), dp(6))
                                setOnClickListener {
                                    QonectaMediaEngine.toggleRadioFavorite(context, st)
                                    renderStationsList(stations, emptyMsg)
                                }
                            }
                            itemCard.addView(starBtn)
                        }

                        rowLayout.addView(
                            itemCard,
                            LinearLayout.LayoutParams(0, dp(88), 1f).apply {
                                if (i < cols - 1) rightMargin = dp(10)
                            }
                        )
                    } else {
                        rowLayout.addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f))
                    }
                }
                stationsContainer.addView(
                    rowLayout,
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
                )
            }
        }

        lateinit var loadCurrentRadioMode: () -> Unit

        fun showAllCountriesPickerDialog() {
            val allCountries = QonectaMediaEngine.allCountriesSorted()
            val dialog = Dialog(context)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(20), dp(24), dp(20))
                background = QonectaTheme.cardDrawable(dp(24).toFloat(), QonectaTheme.accent, dp(2))
            }
            val headRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headRow.addView(TextView(context).apply {
                text = "🌍 Elegir País de Emisoras (Directorio Mundial A–Z)"
                setTextSp(21f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            val autoBtn = pillButton(
                context,
                "📍 Automático (${QonectaMediaEngine.countryDisplayName(QonectaMediaEngine.autoDetectCountry(context))})",
                false,
                textSp = 15f
            ) {
                val autoIso = QonectaMediaEngine.autoDetectCountry(context)
                currentCountry = autoIso
                QonectaMediaEngine.setSelectedRadioCountry(context, autoIso)
                currentMode = 1
                dialog.dismiss()
                loadCurrentRadioMode()
            }
            headRow.addView(autoBtn, LinearLayout.LayoutParams(WRAP, dp(44)).apply { rightMargin = dp(8) })
            val closeBtn = pillButton(context, "✕ Cerrar", false, textSp = 15f) { dialog.dismiss() }
            headRow.addView(closeBtn, LinearLayout.LayoutParams(WRAP, dp(44)))
            card.addView(headRow, LinearLayout.LayoutParams(MATCH, WRAP))

            val filterEdit = EditText(context).apply {
                hint = "Filtrar país por nombre o código (ej. España, Brasil, Italia, México, Japan)…"
                setTextColor(QonectaTheme.text)
                setHintTextColor(QonectaTheme.faint)
                setTextSp(16.5f)
                setSingleLine(true)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = QonectaTheme.chipDrawable(dp(14).toFloat(), QonectaTheme.accent)
            }
            card.addView(filterEdit, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(12) })

            val listScroll = ScrollView(context).apply { isFillViewport = true }
            val listGrid = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            listScroll.addView(listGrid)
            card.addView(listScroll, LinearLayout.LayoutParams(MATCH, dp(420)).apply { topMargin = dp(8) })

            fun populateCountries(filter: String) {
                listGrid.removeAllViews()
                val q = filter.trim().lowercase(Locale.ROOT)
                val filtered = if (q.isEmpty()) {
                    allCountries
                } else {
                    allCountries.filter { (iso, label) ->
                        iso.lowercase(Locale.ROOT).contains(q) || label.lowercase(Locale.ROOT).contains(q)
                    }
                }
                for (row in filtered.chunked(3)) {
                    val r = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        weightSum = 3f
                    }
                    for (i in 0 until 3) {
                        if (i < row.size) {
                            val (iso, label) = row[i]
                            val active = iso.equals(currentCountry, ignoreCase = true)
                            val btn = pillButton(context, label, active, textSp = 16f) {
                                currentCountry = iso
                                QonectaMediaEngine.setSelectedRadioCountry(context, iso)
                                currentMode = 1
                                dialog.dismiss()
                                loadCurrentRadioMode()
                            }.apply {
                                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                            }
                            r.addView(btn, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                                if (i < 2) rightMargin = dp(8)
                            })
                        } else {
                            r.addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f))
                        }
                    }
                    listGrid.addView(r, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
                }
            }

            filterEdit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    populateCountries(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            populateCountries("")

            dialog.setContentView(card)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout(dp(980), WRAP)
            dialog.show()
        }

        fun rebuildContextBar() {
            contextBar.removeAllViews()
            when (currentMode) {
                4 -> {
                    // Barra de múltiples listas M3U de radio (como RadioScreen.java)
                    val lists = QonectaMediaEngine.getRadioM3uLists(context)
                    val selIdx = QonectaMediaEngine.getSelectedRadioM3uIndex(context)
                    val hs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    lists.forEachIndexed { idx, entry ->
                        val b = pillButton(context, entry.name, idx == selIdx, textSp = 15.5f) {
                            QonectaMediaEngine.setSelectedRadioM3uIndex(context, idx)
                            loadCurrentRadioMode()
                        }
                        row.addView(b, LinearLayout.LayoutParams(WRAP, dp(44)).apply { rightMargin = dp(8) })
                    }
                    val addBtn = pillButton(context, "＋ Añadir Lista M3U / URL", lists.isEmpty(), textSp = 15.5f) {
                        promptUrlDialog(
                            context = context,
                            title = "Añadir Lista M3U de Radio (o página iprd.org)",
                            hint = "https://.../radio.m3u",
                            initial = ""
                        ) { url ->
                            if (url.isNotBlank()) {
                                QonectaMediaEngine.setCustomRadioM3u(context, url)
                                loadCurrentRadioMode()
                            }
                        }
                    }
                    row.addView(addBtn, LinearLayout.LayoutParams(WRAP, dp(44)).apply { rightMargin = dp(8) })
                    if (lists.isNotEmpty()) {
                        val delBtn = pillButton(context, "🗑 Quitar lista actual", false, textSp = 14.5f) {
                            val mutable = lists.toMutableList()
                            if (selIdx in mutable.indices) mutable.removeAt(selIdx)
                            QonectaMediaEngine.saveRadioM3uLists(context, mutable, 0)
                            loadCurrentRadioMode()
                        }
                        row.addView(delBtn, LinearLayout.LayoutParams(WRAP, dp(44)))
                    }
                    hs.addView(row)
                    contextBar.addView(hs, LinearLayout.LayoutParams(MATCH, WRAP))
                }
                5 -> {
                    // Barra de «♪ Mi Música (Pendrive USB / Coche)» con botón «🔀 Aleatorio» igual que Qonecta+
                    val count = cachedUsbMusic?.size ?: 0
                    val infoTv = TextView(context).apply {
                        text = "♪ Mi Música en Pendrive USB y Coche ($count canciones detectadas)"
                        setTextSp(16.5f)
                        setTextColor(QonectaTheme.text)
                        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    }
                    contextBar.addView(infoTv, LinearLayout.LayoutParams(0, WRAP, 1f))

                    val shuffleBtn = pillButton(context, "🔀 Reproducir en Aleatorio", true, textSp = 16f, padHdp = 20) {
                        val list = cachedUsbMusic.orEmpty()
                        if (list.isNotEmpty()) {
                            val shuffled = list.shuffled()
                            QonectaMediaEngine.playStation(context, shuffled, 0)
                        } else {
                            Toast.makeText(context, "No hay canciones en el Pendrive USB todavía", Toast.LENGTH_SHORT).show()
                        }
                    }
                    val rescanBtn = pillButton(context, "🔄 Escanear USB", false, textSp = 15.5f, padHdp = 16) {
                        cachedUsbMusic = null
                        loadCurrentRadioMode()
                    }
                    contextBar.addView(shuffleBtn, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(8) })
                    contextBar.addView(rescanBtn, LinearLayout.LayoutParams(WRAP, dp(46)))
                }
                else -> {
                    // Barra de País («Las más escuchadas · 🇪🇸 España» + botón «🌍 Cambiar país (A-Z)» + chips de países)
                    val hs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val pickCountryBtn = pillButton(
                        context,
                        "🌍 País: ${QonectaMediaEngine.countryDisplayName(currentCountry)} ▾",
                        currentMode == 1,
                        textSp = 15.5f,
                        padHdp = 18
                    ) {
                        showAllCountriesPickerDialog()
                    }
                    row.addView(pickCountryBtn, LinearLayout.LayoutParams(WRAP, dp(44)).apply { rightMargin = dp(8) })

                    countryButtons.clear()
                    QonectaMediaEngine.RADIO_COUNTRIES.forEach { (iso, label) ->
                        val active = currentMode == 1 && iso.equals(currentCountry, ignoreCase = true)
                        val cBtn = pillButton(context, label, active, textSp = 15f, padHdp = 14) {
                            currentCountry = iso
                            QonectaMediaEngine.setSelectedRadioCountry(context, iso)
                            currentMode = 1
                            currentQuery = ""
                            loadCurrentRadioMode()
                        }
                        countryButtons.add(iso to cBtn)
                        row.addView(cBtn, LinearLayout.LayoutParams(WRAP, dp(44)).apply { rightMargin = dp(6) })
                    }
                    hs.addView(row)
                    contextBar.addView(hs, LinearLayout.LayoutParams(MATCH, WRAP))
                }
            }
        }

        loadCurrentRadioMode = {
            modeButtons.forEachIndexed { idx, tv ->
                stylePillButton(tv, idx == currentMode)
            }
            rebuildContextBar()
            when (currentMode) {
                0 -> {
                    val list = if (currentQuery.isBlank()) {
                        QonectaMediaEngine.DEFAULT_SPANISH_AND_GLOBAL_STATIONS
                    } else {
                        QonectaMediaEngine.DEFAULT_SPANISH_AND_GLOBAL_STATIONS.filter {
                            it.name.contains(currentQuery, ignoreCase = true) ||
                                it.group.contains(currentQuery, ignoreCase = true)
                        }
                    }
                    renderStationsList(list, "No hay emisoras directas que coincidan con «$currentQuery».")
                }
                1 -> {
                    val countryLabel = QonectaMediaEngine.countryDisplayName(currentCountry)
                    renderStationsList(
                        emptyList(),
                        if (currentQuery.isNotBlank()) "⏳ Buscando «$currentQuery» en el directorio mundial…"
                        else "⏳ Cargando las 150 emisoras más escuchadas de $countryLabel…"
                    )
                    val cc = currentCountry
                    val q = currentQuery
                    bgExecutor.execute {
                        val fetched = QonectaMediaEngine.fetchRadioBrowserStations(cc, q)
                        mainHandler.post {
                            if (currentMode == 1) {
                                renderStationsList(fetched, "Sin resultados en el directorio para esta búsqueda.")
                            }
                        }
                    }
                }
                2 -> {
                    val favs = QonectaMediaEngine.getRadioFavorites(context)
                    renderStationsList(
                        favs,
                        "★ Aún no tienes emisoras favoritas.\nToca la estrella ☆ a la derecha de cualquier emisora (o mantenla pulsada) para guardarla aquí y en Android Auto."
                    )
                }
                3 -> {
                    val rec = QonectaMediaEngine.getRadioRecents(context)
                    renderStationsList(rec, "🕒 Aún no has reproducido emisoras recientes.")
                }
                4 -> {
                    val customUrl = QonectaMediaEngine.getCustomRadioM3u(context)
                    if (customUrl.isBlank()) {
                        renderStationsList(
                            emptyList(),
                            "📋 Pulsa «＋ Añadir Lista M3U / URL» arriba para agregar tus listas M3U de radio (o una dirección de iprd.org)."
                        )
                    } else {
                        renderStationsList(emptyList(), "⏳ Cargando emisoras de tu lista M3U…")
                        val cc = currentCountry
                        bgExecutor.execute {
                            val list = runCatching { QonectaMediaEngine.fetchRadioM3uStations(customUrl, cc) }
                                .getOrDefault(emptyList())
                            mainHandler.post {
                                if (currentMode == 4) {
                                    renderStationsList(list, "No se encontraron emisoras válidas en la lista M3U.")
                                }
                            }
                        }
                    }
                }
                5 -> {
                    val existing = cachedUsbMusic
                    if (existing != null) {
                        val filtered = if (currentQuery.isBlank()) existing else existing.filter {
                            it.name.contains(currentQuery, ignoreCase = true) ||
                                it.group.contains(currentQuery, ignoreCase = true)
                        }
                        renderStationsList(
                            filtered,
                            "♪ No se encontraron archivos de música (.mp3, .flac, .m4a, .wav) en el Pendrive USB ni en la carpeta Music del C10."
                        )
                    } else {
                        renderStationsList(emptyList(), "⏳ Escaneando canciones en el Pendrive USB y almacenamiento del C10…")
                        bgExecutor.execute {
                            val tracks = QonectaMediaEngine.queryLocalAndUsbMusic(context)
                            mainHandler.post {
                                cachedUsbMusic = tracks
                                if (currentMode == 5) {
                                    rebuildContextBar()
                                    renderStationsList(
                                        tracks,
                                        "♪ No se encontraron canciones (.mp3, .flac, .m4a, .ogg, .wav) en el Pendrive USB ni en la memoria del coche."
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val tabTitles = listOf(
            "⚡ Directas",
            "🌍 Top País (150)",
            "★ Favoritas",
            "🕒 Recientes",
            "📋 Listas M3U",
            "♪ Música USB"
        )
        tabTitles.forEachIndexed { idx, label ->
            val btn = pillButton(context, label, idx == 0, textSp = 15f, padHdp = 14) {
                currentMode = idx
                if (idx != 1) currentQuery = ""
                loadCurrentRadioMode()
            }
            modeButtons.add(btn)
            sourceRow.addView(btn, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(6) })
        }

        // Buscador integrado en la misma barra
        val searchInput = EditText(context).apply {
            hint = "🔍 Buscar emisora o canción (Rock FM, COPE, Jazz…)"
            setTextColor(QonectaTheme.text)
            setHintTextColor(QonectaTheme.faint)
            setTextSp(15f)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = QonectaTheme.chipDrawable(dp(16).toFloat())
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    currentQuery = text?.toString()?.trim().orEmpty()
                    if (currentMode != 5) currentMode = 1
                    loadCurrentRadioMode()
                    true
                } else false
            }
        }
        val searchBtn = pillButton(context, "Buscar", true, textSp = 15f, padHdp = 16) {
            currentQuery = searchInput.text?.toString()?.trim().orEmpty()
            if (currentMode != 5) currentMode = 1
            loadCurrentRadioMode()
        }

        val topControlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sourceRowScroll.addView(sourceRow)
        topControlsRow.addView(sourceRowScroll, LinearLayout.LayoutParams(0, WRAP, 1.68f).apply { rightMargin = dp(8) })
        topControlsRow.addView(searchInput, LinearLayout.LayoutParams(0, dp(46), 0.72f).apply { rightMargin = dp(6) })
        topControlsRow.addView(searchBtn, LinearLayout.LayoutParams(WRAP, dp(46)))

        controlsCard.addView(topControlsRow, LinearLayout.LayoutParams(MATCH, WRAP))
        controlsCard.addView(contextBar, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        root.addView(controlsCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        root.addView(gridScroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })

        val updateNowPlayingUi = Runnable {
            val st = QonectaMediaEngine.currentStation()
            val autoOn = QonectaMediaEngine.isRadioAutoResumeEnabled(context)
            autoResumeBtn.text = if (autoOn) "🔄 Auto: SÍ" else "🔄 Auto: NO"
            stylePillButton(autoResumeBtn, autoOn)
            autoResumeBtn.setOnClickListener {
                val nextState = !QonectaMediaEngine.isRadioAutoResumeEnabled(context)
                QonectaMediaEngine.setRadioAutoResumeEnabled(context, nextState)
                Toast.makeText(
                    context,
                    if (nextState) "🔄 La radio/música volverá a sonar sola al conectar"
                    else "Auto-reanudación al conectar desactivada",
                    Toast.LENGTH_SHORT
                ).show()
                autoResumeBtn.text = if (nextState) "🔄 Auto: SÍ" else "🔄 Auto: NO"
                stylePillButton(autoResumeBtn, nextState)
            }

            if (st == null) {
                npLiveBadge.text = "LISTA"
                npTitle.text = "Radio Qonecta+ · Elige cualquier emisora o pista USB"
                npSub.text = "Sigue sonando al volver a Android Auto o CarPlay y muestra metadatos en el cuadro C10"
                playPauseBtn.text = "▶ Sonar"
                favNowBtn.text = "☆"
                npFallbackBadge.text = "📻"
                npLogoImage.setImageDrawable(null)
                npEqualizer.setPlaying(false)
            } else {
                npTitle.text = st.name
                val liveSong = QonectaMediaEngine.radioLiveTitle.trim()
                when {
                    QonectaMediaEngine.radioError.isNotEmpty() -> {
                        npLiveBadge.text = "AVISO"
                        npSub.text = "⚠ ${QonectaMediaEngine.radioError} · Reintentando…"
                    }
                    QonectaMediaEngine.isRadioBuffering -> {
                        npLiveBadge.text = "CARGANDO"
                        npSub.text = "⏳ Conectando con «${st.name}»…"
                    }
                    QonectaMediaEngine.isRadioPlaying -> {
                        npLiveBadge.text = if (st.local) "♪ USB EN CURSO" else "🔊 EN DIRECTO"
                        npSub.text = if (liveSong.isNotEmpty() && !st.local) {
                            "🎵 Sonando ahora: $liveSong  ·  ${st.group.ifBlank { "Radio en vivo" }}"
                        } else {
                            "${st.group.ifBlank { "Emisión en directo" }} · Sonando en segundo plano y cuadro C10"
                        }
                    }
                    else -> {
                        npLiveBadge.text = "PAUSA"
                        npSub.text = "⏸ En pausa · ${st.group.ifBlank { st.name }}"
                    }
                }
                playPauseBtn.text = if (QonectaMediaEngine.isRadioPlaying) "⏸ Pausa" else "▶ Seguir"
                npEqualizer.setPlaying(QonectaMediaEngine.isRadioPlaying)

                npFallbackBadge.text = buildMonogramText(st.name, st.local)
                npFallbackBadge.visibility = View.VISIBLE
                QonectaMediaEngine.loadLogoInto(
                    imageView = npLogoImage,
                    url = st.logo,
                    title = st.name,
                    onLoaded = { npFallbackBadge.visibility = View.GONE },
                    onFallback = {
                        npFallbackBadge.visibility = View.VISIBLE
                        npLogoImage.setImageDrawable(null)
                    }
                )

                if (!st.local) {
                    val fav = QonectaMediaEngine.isRadioFavorite(context, st)
                    favNowBtn.visibility = View.VISIBLE
                    favNowBtn.text = if (fav) "★" else "☆"
                    favNowBtn.setOnClickListener {
                        QonectaMediaEngine.toggleRadioFavorite(context, st)
                        loadCurrentRadioMode()
                    }
                } else {
                    favNowBtn.visibility = View.GONE
                }
            }
        }

        val listener = Runnable {
            updateNowPlayingUi.run()
            loadCurrentRadioMode()
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                QonectaMediaEngine.addRadioListener(listener)
                updateNowPlayingUi.run()
            }

            override fun onViewDetachedFromWindow(v: View) {
                QonectaMediaEngine.removeRadioListener(listener)
            }
        })

        updateNowPlayingUi.run()
        loadCurrentRadioMode()
        return root
    }

    // =========================================================================
    // 2. PESTAÑA TV / TDT / IPTV + PANTALLA COMPLETA 100% + GUÍA EPG EN VIVO
    //    (Portado de IptvScreen.java + TvPlayerControls.java + M3uSource.java + Epg.java + AdBlockJs.java)
    // =========================================================================

    /**
     * VideoView adaptable para el Leapmotor C10:
     * - Con [fillScreen] = true («Llenar 100%»), ocupa exactamente el 100% de los píxeles del contenedor (2560x1440)
     *   sin bandas negras ni recuadros.
     * - Con [fillScreen] = false («16:9»), conserva la proporción nativa de la señal.
     */
    private class ResizableVideoView(context: Context) : VideoView(context) {
        init {
            setZOrderMediaOverlay(true)
        }

        var fillScreen: Boolean = true
            set(value) {
                field = value
                requestLayout()
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (fillScreen) {
                val w = MeasureSpec.getSize(widthMeasureSpec)
                val h = MeasureSpec.getSize(heightMeasureSpec)
                setMeasuredDimension(w, h)
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
    }

    private enum class TvLayoutMode {
        /** Vista dividida: Reproductor a la izquierda + Navegador de canales a la derecha. */
        SPLIT_PREVIEW,
        /** Vista cuadrícula completa de 4 columnas (como IptvScreen.java en Qonecta+); al tocar un canal abre 100% Pantalla Completa. */
        FULL_GRID_4COL,
        /** 100% Toda la Pantalla (2560x1440 edge-to-edge sin cabeceras ni bordes, con barra flotante auto-ocultable a los 4 s). */
        TRUE_FULLSCREEN_100
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun buildTvView(context: Context, isSplitScreen: Boolean = false): View {
        QonectaTheme.ensureLoaded(context)
        val density = c10Density(context)
        fun dp(v: Int): Int = (v * density).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = if (isSplitScreen) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            weightSum = 10f
        }

        var tvLayoutMode = TvLayoutMode.SPLIT_PREVIEW
        var lastNonFullscreenMode = TvLayoutMode.SPLIT_PREVIEW
        var openChannelDirectFullscreen = !isSplitScreen
        var isVideoFillMode = true
        var showFloatingEpgOverlay = false

        var allChannels: List<QonectaMediaEngine.TvChannel> = QonectaMediaEngine.BUILTIN_TDT_CHANNELS
        var shownChannels: List<QonectaMediaEngine.TvChannel> = allChannels
        var currentEpgGuide: QonectaMediaEngine.EpgGuide? = null
        var playingIndex = -1
        var playingSourceIdx = 0

        // ---------------------------------------------------------------------
        // 2A. CONTENEDOR DEL REPRODUCTOR (En modo SPLIT es tarjeta izquierda;
        //     en modo TRUE_FULLSCREEN_100 ocupa el 100% de los 2560x1440 píxeles sin bordes)
        // ---------------------------------------------------------------------
        val playerCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = QonectaTheme.cardDrawable(dp(22).toFloat(), QonectaTheme.accent, dp(2))
        }

        val playerTopBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val currentChannelLabel = TextView(context).apply {
            text = "📺 Selecciona un canal TDT / TV"
            setTextSp(19f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        playerTopBar.addView(currentChannelLabel, LinearLayout.LayoutParams(0, WRAP, 1f))

        val rightBrowserCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val prevChBtn = pillButton(context, "⏮ CH−", false, textSp = 15f, padHdp = 12) {}
        val nextChBtn = pillButton(context, "⏭ CH+", false, textSp = 15f, padHdp = 12) {}
        val altSourceBtn = pillButton(context, "🔄 Fuente", false, textSp = 15f, padHdp = 12) {}
        val fillModeBtn = pillButton(context, "📐 Llenar", true, textSp = 15f, padHdp = 12) {}
        val expandBtn = pillButton(context, if (isSplitScreen) "⛶ Llenar Mitad" else "⛶ Toda la Pantalla", true, textSp = 15.5f, padHdp = 16) {}

        playerTopBar.addView(prevChBtn, LinearLayout.LayoutParams(WRAP, dp(42)).apply { rightMargin = dp(5) })
        playerTopBar.addView(nextChBtn, LinearLayout.LayoutParams(WRAP, dp(42)).apply { rightMargin = dp(5) })
        playerTopBar.addView(altSourceBtn, LinearLayout.LayoutParams(WRAP, dp(42)).apply { rightMargin = dp(5) })
        playerTopBar.addView(fillModeBtn, LinearLayout.LayoutParams(WRAP, dp(42)).apply { rightMargin = dp(5) })
        playerTopBar.addView(expandBtn, LinearLayout.LayoutParams(WRAP, dp(42)))
        playerCard.addView(playerTopBar, LinearLayout.LayoutParams(MATCH, WRAP))

        val stageBox = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), QonectaTheme.outline)
            }
            clipToOutline = true
        }
        val videoView = ResizableVideoView(context).apply {
            fillScreen = isVideoFillMode
        }
        val webView = WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(QonectaMediaEngine.ADBLOCK_JS_SCRIPT, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(QonectaMediaEngine.ADBLOCK_JS_SCRIPT, null)
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val host = request?.url?.host
                    if (QonectaMediaEngine.isAdDomain(host)) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.resources?.forEach { res ->
                        if (res == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID) {
                            request.grant(arrayOf(res))
                            return
                        }
                    }
                    request?.deny()
                }
            }
        }
        val playerHint = TextView(context).apply {
            text = "📺 Televisión TDT e IPTV a Toda la Pantalla (2560x1440)\n\n" +
                "• Toca cualquier canal para abrirlo a TODA LA PANTALLA (100% sin bordes) o en vista dividida\n" +
                "• Toca sobre el vídeo para mostrar/ocultar los controles flotantes (se ocultan solos a los 4 s)\n" +
                "• Doble toque sobre el vídeo para alternar entre Toda la Pantalla (100%) y Modo Recuadro + Lista\n" +
                "• Soporta TDT España, TDTChannels JSON, IPTV-org, listas M3U/Xtream con Usuario y Contraseña y Guía EPG"
            setTextSp(16.5f)
            setTextColor(QonectaTheme.dim)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.25f)
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(24), dp(26), dp(24))
        }

        // ---------------------------------------------------------------------
        // BARRA DE CONTROLES FLOTANTE EN PANTALLA COMPLETA 100% (Portado de IptvScreen.java + TvPlayerControls.java)
        // Se auto-oculta a los 4 segundos (HIDE_AFTER_MS = 4000) dejando el vídeo al 100% de la pantalla,
        // y reaparece al tocar cualquier punto del vídeo.
        // ---------------------------------------------------------------------
        val tvFloatingBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xE6070D14.toInt(), 0xCC0D1622.toInt())
            ).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(2), QonectaTheme.accent)
            }
            visibility = View.GONE
        }

        val fsBackToChannelsBtn = pillButton(context, "‹ Canales", false, textSp = 16f, padHdp = 16) {}
        val fsTitleAndEpgTv = TextView(context).apply {
            text = "📺 TV Qonecta+"
            setTextSp(18.5f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(16), 0, dp(16), 0)
        }
        val fsPrevChBtn = pillButton(context, "⏮ Canal −", false, textSp = 15.5f, padHdp = 14) {}
        val fsNextChBtn = pillButton(context, "⏭ Canal +", false, textSp = 15.5f, padHdp = 14) {}
        val fsAltSrcBtn = pillButton(context, "🔄 Otra fuente", false, textSp = 15f, padHdp = 14) {}
        val fsFillBtn = pillButton(context, "📐 Llenar 100%", true, textSp = 15f, padHdp = 14) {}
        val fsEpgCardBtn = pillButton(context, "📋 Guía EPG", false, textSp = 15f, padHdp = 14) {}
        val fsSplitModeBtn = pillButton(context, "🗔 Modo Recuadro", false, textSp = 15f, padHdp = 14) {}

        tvFloatingBar.addView(fsBackToChannelsBtn, LinearLayout.LayoutParams(WRAP, dp(46)))
        tvFloatingBar.addView(fsTitleAndEpgTv, LinearLayout.LayoutParams(0, WRAP, 1f))
        tvFloatingBar.addView(fsPrevChBtn, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(6) })
        tvFloatingBar.addView(fsNextChBtn, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(6) })
        tvFloatingBar.addView(fsAltSrcBtn, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(6) })
        tvFloatingBar.addView(fsFillBtn, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(6) })
        tvFloatingBar.addView(fsEpgCardBtn, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(6) })
        tvFloatingBar.addView(fsSplitModeBtn, LinearLayout.LayoutParams(WRAP, dp(46)))

        // Tarjeta flotante de Guía EPG detallada sobre el vídeo en pantalla completa
        val fsEpgFloatingCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xE6081018.toInt())
                setStroke(dp(2), QonectaTheme.accent)
            }
            visibility = View.GONE
        }
        val fsEpgNowTitleTv = TextView(context).apply {
            text = "🟢 AHORA EN EMISIÓN"
            setTextSp(18f)
            setTextColor(QonectaTheme.accent)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        val fsEpgDescTv = TextView(context).apply {
            text = "Información del programa en directo y siguiente emisión."
            setTextSp(15.5f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(4), 0, 0)
        }
        fsEpgFloatingCard.addView(fsEpgNowTitleTv)
        fsEpgFloatingCard.addView(fsEpgDescTv)

        val autoHideControlsRunnable = Runnable {
            if (tvLayoutMode == TvLayoutMode.TRUE_FULLSCREEN_100) {
                tvFloatingBar.visibility = View.GONE
                if (!showFloatingEpgOverlay) {
                    fsEpgFloatingCard.visibility = View.GONE
                }
            }
        }

        fun showFloatingControlsTemporarily() {
            mainHandler.removeCallbacks(autoHideControlsRunnable)
            if (tvLayoutMode == TvLayoutMode.TRUE_FULLSCREEN_100) {
                tvFloatingBar.visibility = View.VISIBLE
                mainHandler.postDelayed(autoHideControlsRunnable, 4000L)
            }
        }

        lateinit var applyTvLayoutMode: (TvLayoutMode) -> Unit

        // Capa táctil sobre el vídeo: 1 toque muestra/oculta controles (TvPlayerControls.onTap);
        // 2 toques alterna directamente entre Toda la Pantalla (100%) y Modo Recuadro + Lista.
        val videoTouchOverlay = View(context)
        val stageGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (tvLayoutMode == TvLayoutMode.TRUE_FULLSCREEN_100) {
                    val currentlyVisible = tvFloatingBar.visibility == View.VISIBLE
                    if (currentlyVisible) {
                        mainHandler.removeCallbacks(autoHideControlsRunnable)
                        tvFloatingBar.visibility = View.GONE
                        fsEpgFloatingCard.visibility = View.GONE
                    } else {
                        showFloatingControlsTemporarily()
                    }
                } else {
                    applyTvLayoutMode(TvLayoutMode.TRUE_FULLSCREEN_100)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (tvLayoutMode == TvLayoutMode.TRUE_FULLSCREEN_100) {
                    applyTvLayoutMode(TvLayoutMode.SPLIT_PREVIEW)
                } else {
                    applyTvLayoutMode(TvLayoutMode.TRUE_FULLSCREEN_100)
                }
                return true
            }
        })
        videoTouchOverlay.setOnTouchListener { _, ev ->
            if (webView.visibility == View.VISIBLE) {
                // Si es WebView (Atresplayer/Mitele/RTVE), dejamos pasar el toque a la web y además mostramos la barra flotante arriba
                if (ev.action == MotionEvent.ACTION_DOWN && tvLayoutMode == TvLayoutMode.TRUE_FULLSCREEN_100) {
                    showFloatingControlsTemporarily()
                }
                false
            } else {
                stageGestureDetector.onTouchEvent(ev)
            }
        }

        stageBox.addView(videoView, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        stageBox.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
        stageBox.addView(playerHint, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        stageBox.addView(videoTouchOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        stageBox.addView(
            tvFloatingBar,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply {
                topMargin = dp(14)
                leftMargin = dp(18)
                rightMargin = dp(18)
            }
        )
        stageBox.addView(
            fsEpgFloatingCard,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
                bottomMargin = dp(18)
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
        )
        playerCard.addView(stageBox, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(10) })

        // Barra inferior del reproductor (en vista dividida): Guía EPG en vivo
        val epgStatusBar = TextView(context).apply {
            text = "📋 Guía EPG: Toca un canal para iniciar (toca el vídeo o «⛶ Toda la Pantalla» para 100% pantalla completa)"
            setTextSp(15f)
            setTextColor(QonectaTheme.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(8), dp(12), dp(6))
            background = QonectaTheme.chipDrawable(dp(12).toFloat())
        }
        playerCard.addView(epgStatusBar, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        // Banner superior cuando estamos en modo FULL_GRID_4COL y hay un canal en emisión
        val gridPlayingBanner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = QonectaTheme.cardDrawable(dp(18).toFloat(), QonectaTheme.accent, dp(2))
            visibility = View.GONE
        }
        val gridPlayingLabel = TextView(context).apply {
            text = "📺 En emisión"
            setTextSp(17.5f)
            setTextColor(QonectaTheme.accent)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        val gridGoFullscreenBtn = pillButton(context, "⛶ Ver a Toda la Pantalla (100%)", true, textSp = 15.5f, padHdp = 18) {
            applyTvLayoutMode(TvLayoutMode.TRUE_FULLSCREEN_100)
        }
        val gridGoSplitBtn = pillButton(context, "🗔 Ver en Recuadro + Lista", false, textSp = 15f, padHdp = 14) {
            applyTvLayoutMode(TvLayoutMode.SPLIT_PREVIEW)
        }
        gridPlayingBanner.addView(gridPlayingLabel, LinearLayout.LayoutParams(0, WRAP, 1f))
        gridPlayingBanner.addView(gridGoSplitBtn, LinearLayout.LayoutParams(WRAP, dp(42)).apply { rightMargin = dp(8) })
        gridPlayingBanner.addView(gridGoFullscreenBtn, LinearLayout.LayoutParams(WRAP, dp(42)))

        lateinit var renderChannelsGrid: () -> Unit

        applyTvLayoutMode = { newMode ->
            tvLayoutMode = newMode
            if (newMode != TvLayoutMode.TRUE_FULLSCREEN_100) {
                lastNonFullscreenMode = newMode
            }
            mainHandler.removeCallbacks(autoHideControlsRunnable)

            when (newMode) {
                TvLayoutMode.TRUE_FULLSCREEN_100 -> {
                    // Oculta la barra superior y las 13 pestañas del Hub (0px de margen: ocupa 2560x1440 o el 100% de su mitad al 100%)
                    ExtendedHubView.setFullscreenFor(root, true, showDefaultExitPill = false)
                    rightBrowserCol.visibility = View.GONE
                    playerCard.visibility = View.VISIBLE
                    playerTopBar.visibility = View.GONE
                    epgStatusBar.visibility = View.GONE
                    playerCard.setPadding(0, 0, 0, 0)
                    playerCard.background = null
                    playerCard.layoutParams = if (isSplitScreen) {
                        LinearLayout.LayoutParams(MATCH, 0, 10f).apply { bottomMargin = 0 }
                    } else {
                        LinearLayout.LayoutParams(0, MATCH, 10f).apply { rightMargin = 0 }
                    }
                    stageBox.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 0 }
                    stageBox.background = GradientDrawable().apply { setColor(Color.BLACK) }
                    showFloatingControlsTemporarily()
                }
                TvLayoutMode.FULL_GRID_4COL -> {
                    ExtendedHubView.setFullscreenFor(root, false)
                    tvFloatingBar.visibility = View.GONE
                    fsEpgFloatingCard.visibility = View.GONE
                    playerCard.visibility = View.GONE
                    rightBrowserCol.visibility = View.VISIBLE
                    rightBrowserCol.layoutParams = if (isSplitScreen) {
                        LinearLayout.LayoutParams(MATCH, 0, 10f)
                    } else {
                        LinearLayout.LayoutParams(0, MATCH, 10f)
                    }
                    gridPlayingBanner.visibility = if (playingIndex in shownChannels.indices) View.VISIBLE else View.GONE
                    renderChannelsGrid()
                }
                TvLayoutMode.SPLIT_PREVIEW -> {
                    ExtendedHubView.setFullscreenFor(root, false)
                    tvFloatingBar.visibility = View.GONE
                    fsEpgFloatingCard.visibility = View.GONE
                    gridPlayingBanner.visibility = View.GONE
                    playerCard.visibility = View.VISIBLE
                    rightBrowserCol.visibility = View.VISIBLE
                    playerTopBar.visibility = View.VISIBLE
                    epgStatusBar.visibility = View.VISIBLE
                    playerCard.setPadding(dp(14), dp(10), dp(14), dp(10))
                    playerCard.background = QonectaTheme.cardDrawable(dp(22).toFloat(), QonectaTheme.accent, dp(2))
                    if (isSplitScreen) {
                        playerCard.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 4.7f).apply { bottomMargin = dp(10) }
                        rightBrowserCol.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 5.3f)
                    } else {
                        playerCard.layoutParams = LinearLayout.LayoutParams(0, MATCH, 4.8f).apply { rightMargin = dp(12) }
                        rightBrowserCol.layoutParams = LinearLayout.LayoutParams(0, MATCH, 5.2f)
                    }
                    stageBox.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) }
                    stageBox.background = GradientDrawable().apply {
                        setColor(Color.BLACK)
                        cornerRadius = dp(16).toFloat()
                        setStroke(dp(1), QonectaTheme.outline)
                    }
                    renderChannelsGrid()
                }
            }
            root.requestLayout()
        }

        fun updatePlayerEpgBanner() {
            if (playingIndex !in shownChannels.indices) return
            val ch = shownChannels[playingIndex]
            val nowProg = currentEpgGuide?.now(ch.epgId, ch.name)
            val nextProg = currentEpgGuide?.next(ch.epgId, ch.name)
            val srcSuffix = if (playingSourceIdx > 0) " · Fuente de reserva (${playingSourceIdx + 1}/${ch.urls.size})" else ""

            epgStatusBar.text = when {
                nowProg != null && nextProg != null ->
                    "🟢 AHORA: ${QonectaMediaEngine.formatEpgNow(nowProg)}   ·   ⏭ LUEGO: ${nextProg.title}$srcSuffix"
                nowProg != null ->
                    "🟢 AHORA EN EMISIÓN: ${QonectaMediaEngine.formatEpgNow(nowProg)}$srcSuffix"
                else ->
                    "📺 ${ch.name} · ${ch.group.ifBlank { "Televisión en directo" }} · Fuente ${playingSourceIdx + 1}/${maxOf(1, ch.urls.size)}"
            }
            fsTitleAndEpgTv.text = when {
                nowProg != null -> "${ch.name}  ·  ${QonectaMediaEngine.formatEpgNow(nowProg)}$srcSuffix"
                else -> "${ch.name}  ·  ${ch.group.ifBlank { "En directo" }}$srcSuffix"
            }
            gridPlayingLabel.text = "▶ EN EMISIÓN: ${fsTitleAndEpgTv.text}"
            fsEpgNowTitleTv.text = if (nowProg != null) {
                "🟢 AHORA EN ${ch.name.uppercase(Locale.ROOT)}: ${QonectaMediaEngine.formatEpgNow(nowProg)}"
            } else {
                "📺 ${ch.name} · Emisión en directo"
            }
            fsEpgDescTv.text = buildString {
                if (nextProg != null) {
                    append("⏭ A continuación: ").append(QonectaMediaEngine.formatEpgNow(nextProg))
                } else {
                    append("Guía EPG sincronizada en vivo con TDTChannels / XMLTV.")
                }
            }
        }

        fun stopAllTvPlayback() {
            mainHandler.removeCallbacks(autoHideControlsRunnable)
            runCatching { videoView.stopPlayback() }
            runCatching { webView.loadUrl("about:blank") }
            webView.visibility = View.GONE
            videoView.visibility = View.GONE
        }

        fun playChannelAt(idx: Int, sourceIdx: Int = 0, enterFullscreenIfConfigured: Boolean = false) {
            if (shownChannels.isEmpty() || idx !in shownChannels.indices) return
            if (QonectaMediaEngine.isRadioPlaying) {
                QonectaMediaEngine.stopRadio(context)
            }
            playingIndex = idx
            val ch = shownChannels[idx]
            val safeSource = sourceIdx.coerceIn(0, maxOf(0, ch.urls.size - 1))
            playingSourceIdx = safeSource
            val targetUrl = ch.urls.getOrElse(safeSource) { ch.url }

            playerHint.visibility = View.GONE
            currentChannelLabel.text = "📺 ${ch.name}" +
                if (ch.urls.size > 1) " (${safeSource + 1}/${ch.urls.size})" else ""
            val srcBtnText = "🔄 Fuente ${safeSource + 1}/${maxOf(1, ch.urls.size)}"
            altSourceBtn.text = srcBtnText
            fsAltSrcBtn.text = srcBtnText
            fsAltSrcBtn.visibility = if (ch.urls.size > 1) View.VISIBLE else View.GONE
            updatePlayerEpgBanner()

            val nowProg = currentEpgGuide?.now(ch.epgId, ch.name)
            ClusterMapController.updateMedia(
                title = ch.name,
                artist = nowProg?.title ?: ch.group.ifBlank { "Televisión en directo" },
                album = "Qonecta+ TV",
                appName = "TV QONECTA+",
                isPlaying = true
            )

            if (enterFullscreenIfConfigured && (openChannelDirectFullscreen || tvLayoutMode == TvLayoutMode.FULL_GRID_4COL)) {
                applyTvLayoutMode(TvLayoutMode.TRUE_FULLSCREEN_100)
            } else if (tvLayoutMode == TvLayoutMode.TRUE_FULLSCREEN_100) {
                showFloatingControlsTemporarily()
            } else {
                gridPlayingBanner.visibility = if (tvLayoutMode == TvLayoutMode.FULL_GRID_4COL) View.VISIBLE else View.GONE
            }

            if (targetUrl.startsWith(QonectaMediaEngine.WEB_PREFIX)) {
                val rawWebUrl = targetUrl.removePrefix(QonectaMediaEngine.WEB_PREFIX)
                runCatching { videoView.stopPlayback() }
                videoView.visibility = View.GONE
                webView.visibility = View.VISIBLE
                val embed = QonectaMediaEngine.embedUrlFor(rawWebUrl)
                if (embed != null) {
                    webView.loadDataWithBaseURL(
                        "https://charlysev.github.io/",
                        QonectaMediaEngine.embedHtml(embed),
                        "text/html",
                        "utf-8",
                        null
                    )
                } else {
                    webView.loadUrl(rawWebUrl)
                }
            } else {
                runCatching { webView.loadUrl("about:blank") }
                webView.visibility = View.GONE
                videoView.visibility = View.VISIBLE
                videoView.setOnPreparedListener { mp ->
                    mp.isLooping = false
                    runCatching { videoView.start() }
                }
                videoView.setOnErrorListener { _, _, _ ->
                    if (safeSource + 1 < ch.urls.size) {
                        playChannelAt(idx, safeSource + 1, enterFullscreenIfConfigured = false)
                    } else {
                        currentChannelLabel.text = "⚠ ${ch.name} · Sin señal directa"
                        fsTitleAndEpgTv.text = "⚠ ${ch.name} · Sin señal directa (prueba otra fuente)"
                    }
                    true
                }
                videoView.setVideoURI(Uri.parse(targetUrl))
                videoView.requestFocus()
                videoView.start()
            }
            renderChannelsGrid()
        }

        val stepChannel: (Int) -> Unit = { delta ->
            if (shownChannels.isNotEmpty()) {
                val next = if (playingIndex < 0) 0 else (playingIndex + delta + shownChannels.size) % shownChannels.size
                playChannelAt(next, 0, enterFullscreenIfConfigured = false)
            }
        }
        val cycleChannelSource: () -> Unit = {
            if (playingIndex in shownChannels.indices) {
                val ch = shownChannels[playingIndex]
                val nextSrc = (playingSourceIdx + 1) % maxOf(1, ch.urls.size)
                playChannelAt(playingIndex, nextSrc, enterFullscreenIfConfigured = false)
            }
        }
        val toggleFillMode: () -> Unit = {
            isVideoFillMode = !isVideoFillMode
            videoView.fillScreen = isVideoFillMode
            val label = if (isVideoFillMode) "📐 Llenar 100%" else "📐 16:9"
            fillModeBtn.text = label
            fsFillBtn.text = label
            stylePillButton(fillModeBtn, isVideoFillMode)
            stylePillButton(fsFillBtn, isVideoFillMode)
            videoView.layoutParams = FrameLayout.LayoutParams(
                MATCH,
                MATCH,
                if (isVideoFillMode) Gravity.FILL else Gravity.CENTER
            )
            stageBox.requestLayout()
            showFloatingControlsTemporarily()
        }

        prevChBtn.setOnClickListener { stepChannel(-1) }
        nextChBtn.setOnClickListener { stepChannel(1) }
        fsPrevChBtn.setOnClickListener { stepChannel(-1) }
        fsNextChBtn.setOnClickListener { stepChannel(1) }
        altSourceBtn.setOnClickListener { cycleChannelSource() }
        fsAltSrcBtn.setOnClickListener { cycleChannelSource() }
        fillModeBtn.setOnClickListener { toggleFillMode() }
        fsFillBtn.setOnClickListener { toggleFillMode() }

        expandBtn.setOnClickListener {
            applyTvLayoutMode(TvLayoutMode.TRUE_FULLSCREEN_100)
        }
        fsBackToChannelsBtn.setOnClickListener {
            applyTvLayoutMode(lastNonFullscreenMode)
        }
        fsSplitModeBtn.setOnClickListener {
            applyTvLayoutMode(TvLayoutMode.SPLIT_PREVIEW)
        }
        fsEpgCardBtn.setOnClickListener {
            showFloatingEpgOverlay = !showFloatingEpgOverlay
            stylePillButton(fsEpgCardBtn, showFloatingEpgOverlay)
            fsEpgFloatingCard.visibility = if (showFloatingEpgOverlay) View.VISIBLE else View.GONE
            showFloatingControlsTemporarily()
        }

        // ---------------------------------------------------------------------
        // 2B. NAVEGADOR DE CANALES: LISTAS (INCLUIDO XTREAM) + MODO 4 COL / SPLIT + BUSCADOR + GRUPOS + PARRILLA
        // ---------------------------------------------------------------------
        val sourcesScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val sourcesRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        val filterAndGroupsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val channelSearchInput = EditText(context).apply {
            hint = "🔍 Canal (La 1, Antena 3, 24h)…"
            setTextColor(QonectaTheme.text)
            setHintTextColor(QonectaTheme.faint)
            setTextSp(15f)
            setSingleLine(true)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = QonectaTheme.chipDrawable(dp(14).toFloat())
        }

        val browserModeBtn = pillButton(context, "▦ Parrilla 4 Col.", false, textSp = 14.5f, padHdp = 14) {}
        val autoFsToggleBtn = pillButton(context, "⚡ Al tocar: 100% Pantalla", openChannelDirectFullscreen, textSp = 14.5f, padHdp = 14) {}

        browserModeBtn.setOnClickListener {
            val nextMode = if (tvLayoutMode == TvLayoutMode.FULL_GRID_4COL) {
                TvLayoutMode.SPLIT_PREVIEW
            } else {
                TvLayoutMode.FULL_GRID_4COL
            }
            browserModeBtn.text = if (nextMode == TvLayoutMode.FULL_GRID_4COL) "🗔 Vista Recuadro + Lista" else "▦ Parrilla 4 Col."
            stylePillButton(browserModeBtn, nextMode == TvLayoutMode.FULL_GRID_4COL)
            applyTvLayoutMode(nextMode)
        }

        autoFsToggleBtn.setOnClickListener {
            openChannelDirectFullscreen = !openChannelDirectFullscreen
            autoFsToggleBtn.text = if (openChannelDirectFullscreen) "⚡ Al tocar: 100% Pantalla" else "🗔 Al tocar: En Recuadro"
            stylePillButton(autoFsToggleBtn, openChannelDirectFullscreen)
        }

        val groupsScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val groupsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        groupsScroll.addView(groupsRow)

        filterAndGroupsRow.addView(channelSearchInput, LinearLayout.LayoutParams(dp(210), dp(42)).apply { rightMargin = dp(6) })
        filterAndGroupsRow.addView(browserModeBtn, LinearLayout.LayoutParams(WRAP, dp(42)).apply { rightMargin = dp(6) })
        filterAndGroupsRow.addView(autoFsToggleBtn, LinearLayout.LayoutParams(WRAP, dp(42)).apply { rightMargin = dp(6) })
        filterAndGroupsRow.addView(groupsScroll, LinearLayout.LayoutParams(0, WRAP, 1f))

        val channelsScroll = ScrollView(context).apply { isFillViewport = true }
        val channelsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        channelsScroll.addView(channelsContainer)

        var selectedGroup: String? = null
        var channelSearchQuery = ""

        fun recomputeShownChannels() {
            val byGroup = when (selectedGroup) {
                null -> allChannels
                "★ Favoritos" -> QonectaMediaEngine.getTvFavorites(context)
                else -> allChannels.filter { it.group == selectedGroup }
            }
            shownChannels = if (channelSearchQuery.isBlank()) {
                byGroup
            } else {
                byGroup.filter {
                    it.name.contains(channelSearchQuery, ignoreCase = true) ||
                        it.group.contains(channelSearchQuery, ignoreCase = true)
                }
            }
        }

        renderChannelsGrid = {
            channelsContainer.removeAllViews()
            if (shownChannels.isEmpty()) {
                channelsContainer.addView(TextView(context).apply {
                    text = "No hay canales en esta categoría o búsqueda."
                    setTextSp(17f)
                    setTextColor(QonectaTheme.dim)
                    gravity = Gravity.CENTER
                    setPadding(dp(24), dp(42), dp(24), dp(42))
                })
            } else {
                val cols = if (tvLayoutMode == TvLayoutMode.FULL_GRID_4COL && !isSplitScreen) 4 else 2
                val rows = shownChannels.chunked(cols)
                for (chunk in rows) {
                    val rLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        weightSum = cols.toFloat()
                    }
                    for (i in 0 until cols) {
                        if (i < chunk.size) {
                            val ch = chunk[i]
                            val idxInShown = shownChannels.indexOf(ch)
                            val isFav = QonectaMediaEngine.isTvFavorite(context, ch)
                            val isCurrent = idxInShown == playingIndex
                            val nowProg = currentEpgGuide?.now(ch.epgId, ch.name)

                            val cell = LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(dp(12), dp(10), dp(12), dp(10))
                                background = GradientDrawable(
                                    GradientDrawable.Orientation.TOP_BOTTOM,
                                    if (isCurrent) {
                                        intArrayOf(QonectaTheme.withAlpha(QonectaTheme.accent, 55), QonectaTheme.surfaceHi)
                                    } else {
                                        intArrayOf(QonectaTheme.surfaceTop, QonectaTheme.surfaceHi)
                                    }
                                ).apply {
                                    cornerRadius = dp(16).toFloat()
                                    setStroke(
                                        dp(if (isCurrent) 2 else 1),
                                        if (isCurrent) QonectaTheme.accent else QonectaTheme.outline
                                    )
                                }
                                setOnClickListener {
                                    playChannelAt(idxInShown, 0, enterFullscreenIfConfigured = true)
                                }
                                setOnLongClickListener {
                                    QonectaMediaEngine.toggleTvFavorite(context, ch)
                                    recomputeShownChannels()
                                    renderChannelsGrid()
                                    true
                                }
                            }

                            val logoBox = createMediaLogoBox(
                                context = context,
                                title = ch.name,
                                logoUrl = ch.logo,
                                isLocalMusic = false,
                                isPlaying = isCurrent,
                                sizeDp = 56
                            )
                            cell.addView(logoBox, LinearLayout.LayoutParams(dp(62), dp(56)))

                            val tCol = LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(12), 0, dp(6), 0)
                            }
                            tCol.addView(TextView(context).apply {
                                text = (if (isCurrent) "▶ " else "") + ch.name
                                setTextSp(18f)
                                setTextColor(if (isCurrent) QonectaTheme.accent else QonectaTheme.text)
                                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                                setSingleLine(true)
                                ellipsize = TextUtils.TruncateAt.END
                            })
                            tCol.addView(TextView(context).apply {
                                val kind = if (ch.url.startsWith(QonectaMediaEngine.WEB_PREFIX)) "Web Directo" else "HLS Directo"
                                val epgText = nowProg?.title
                                text = if (!epgText.isNullOrBlank()) {
                                    "🟢 $epgText"
                                } else {
                                    "${ch.group.ifBlank { "TV" }} · $kind" +
                                        if (ch.urls.size > 1) " (${ch.urls.size} fuentes)" else ""
                                }
                                setTextSp(14f)
                                setTextColor(if (!epgText.isNullOrBlank()) QonectaTheme.accent else QonectaTheme.dim)
                                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                                setSingleLine(true)
                                ellipsize = TextUtils.TruncateAt.END
                                setPadding(0, dp(2), 0, 0)
                            })
                            cell.addView(tCol, LinearLayout.LayoutParams(0, WRAP, 1f))

                            val favStar = TextView(context).apply {
                                text = if (isFav) "★" else "☆"
                                setTextSp(22f)
                                setTextColor(if (isFav) QonectaTheme.amber else QonectaTheme.faint)
                                setPadding(dp(8), dp(4), dp(4), dp(4))
                                setOnClickListener {
                                    QonectaMediaEngine.toggleTvFavorite(context, ch)
                                    recomputeShownChannels()
                                    renderChannelsGrid()
                                }
                            }
                            cell.addView(favStar)

                            rLayout.addView(
                                cell,
                                LinearLayout.LayoutParams(0, dp(82), 1f).apply {
                                    if (i < cols - 1) rightMargin = dp(8)
                                }
                            )
                        } else {
                            rLayout.addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f))
                        }
                    }
                    channelsContainer.addView(
                        rLayout,
                        LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
                    )
                }
            }
        }

        fun rebuildGroupChips() {
            groupsRow.removeAllViews()
            val groups = LinkedHashSet<String>()
            groups.add("★ Favoritos")
            groups.add("Todos")
            allChannels.forEach { if (it.group.isNotBlank()) groups.add(it.group) }

            for (g in groups) {
                val isSel = (selectedGroup == null && g == "Todos") || selectedGroup == g
                val chip = pillButton(context, g, isSel, textSp = 15f, padHdp = 15) {
                    selectedGroup = if (g == "Todos") null else g
                    recomputeShownChannels()
                    rebuildGroupChips()
                    renderChannelsGrid()
                }
                groupsRow.addView(chip, LinearLayout.LayoutParams(WRAP, dp(42)).apply { rightMargin = dp(6) })
            }
        }

        channelSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                channelSearchQuery = s?.toString()?.trim().orEmpty()
                recomputeShownChannels()
                renderChannelsGrid()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        var selectedSourceIdx = QonectaMediaEngine.getSelectedTvSourceIndex(context)

        fun triggerAsyncEpgLoad(epgUrl: String?) {
            if (epgUrl.isNullOrBlank()) return
            bgExecutor.execute {
                val guide = QonectaMediaEngine.loadEpgGuide(context, epgUrl)
                if (guide != null) {
                    mainHandler.post {
                        currentEpgGuide = guide
                        updatePlayerEpgBanner()
                        renderChannelsGrid()
                    }
                }
            }
        }

        lateinit var loadSourceByIndex: (Int) -> Unit

        fun showCustomTvListsDialog() {
            val customLists = QonectaMediaEngine.getCustomTvLists(context).toMutableList()
            val pad = dp(18)
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, dp(12), pad, dp(8))
            }
            val nameInput = EditText(context).apply {
                hint = "Nombre de la lista (ej. Mi IPTV Casa, Xtream Deportes)"
                setSingleLine(true)
                setTextSp(15.5f)
            }
            val urlInput = EditText(context).apply {
                hint = "URL (.m3u, .m3u8, .m3u.gz, tv.json o servidor Xtream http://host:puerto)"
                setSingleLine(true)
                setTextSp(15.5f)
            }
            val userInput = EditText(context).apply {
                hint = "Usuario (opcional · para Xtream Codes o HTTP Basic)"
                setSingleLine(true)
                setTextSp(15.5f)
            }
            val passInput = EditText(context).apply {
                hint = "Contraseña (opcional · para Xtream Codes o HTTP Basic)"
                setSingleLine(true)
                setTextSp(15.5f)
            }
            if (customLists.isNotEmpty()) {
                val first = customLists.first()
                nameInput.setText(first.name)
                urlInput.setText(first.url)
                userInput.setText(first.username)
                passInput.setText(first.password)
            }
            container.addView(nameInput)
            container.addView(urlInput)
            container.addView(userInput)
            container.addView(passInput)

            AlertDialog.Builder(context)
                .setTitle("Listas M3U / TDT JSON / Xtream Codes (Qonecta+)")
                .setView(container)
                .setPositiveButton("Guardar y Cargar") { _, _ ->
                    val rawUrl = urlInput.text?.toString()?.trim().orEmpty()
                    if (rawUrl.isNotEmpty()) {
                        val entry = QonectaMediaEngine.CustomM3uEntry(
                            id = "custom_${System.currentTimeMillis()}",
                            name = nameInput.text?.toString()?.trim().orEmpty().ifBlank { "Mi Lista IPTV" },
                            url = rawUrl,
                            username = userInput.text?.toString()?.trim().orEmpty(),
                            password = passInput.text?.toString().orEmpty()
                        )
                        val updated = customLists.filterNot { it.url.equals(rawUrl, ignoreCase = true) }.toMutableList()
                        updated.add(0, entry)
                        QonectaMediaEngine.saveCustomTvLists(context, updated)
                        loadSourceByIndex(100)
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        loadSourceByIndex = { idx ->
            selectedSourceIdx = idx
            QonectaMediaEngine.setSelectedTvSourceIndex(context, idx)
            sourcesRow.removeAllViews()
            QonectaMediaEngine.TV_SOURCE_PRESETS.forEachIndexed { i, preset ->
                val btn = pillButton(context, preset.title, i == selectedSourceIdx, textSp = 15f, padHdp = 15) {
                    loadSourceByIndex(i)
                }
                sourcesRow.addView(btn, LinearLayout.LayoutParams(WRAP, dp(44)).apply { rightMargin = dp(6) })
            }
            val customLists = QonectaMediaEngine.getCustomTvLists(context)
            customLists.forEachIndexed { cIdx, cEntry ->
                val codeIdx = 100 + cIdx
                val btn = pillButton(context, "📋 ${cEntry.name}", selectedSourceIdx == codeIdx, textSp = 15f, padHdp = 15) {
                    loadSourceByIndex(codeIdx)
                }
                sourcesRow.addView(btn, LinearLayout.LayoutParams(WRAP, dp(44)).apply { rightMargin = dp(6) })
            }
            val addListBtn = pillButton(context, "＋ Lista M3U / Xtream", selectedSourceIdx == 99, textSp = 15f, padHdp = 15) {
                showCustomTvListsDialog()
            }
            sourcesRow.addView(addListBtn, LinearLayout.LayoutParams(WRAP, dp(44)))

            val customEntry = if (idx >= 100) customLists.getOrNull(idx - 100) else null
            val preset = QonectaMediaEngine.TV_SOURCE_PRESETS.getOrElse(idx) { QonectaMediaEngine.TV_SOURCE_PRESETS[0] }
            val targetUrl = when {
                customEntry != null -> customEntry.url
                idx == 99 -> QonectaMediaEngine.getCustomTvM3u(context)
                else -> preset.url
            }
            val targetUser = customEntry?.username.orEmpty()
            val targetPass = customEntry?.password.orEmpty()

            if (targetUrl == "builtin:es_tdt" || targetUrl.isBlank()) {
                allChannels = QonectaMediaEngine.BUILTIN_TDT_CHANNELS
                selectedGroup = null
                recomputeShownChannels()
                rebuildGroupChips()
                renderChannelsGrid()
                triggerAsyncEpgLoad(preset.defaultEpgUrl)
            } else {
                channelsContainer.removeAllViews()
                channelsContainer.addView(TextView(context).apply {
                    text = "⏳ Descargando canales y guía EPG en segundo plano…"
                    setTextSp(17f)
                    setTextColor(QonectaTheme.accent)
                    gravity = Gravity.CENTER
                    setPadding(dp(20), dp(48), dp(20), dp(48))
                })
                bgExecutor.execute {
                    val fetched = runCatching {
                        QonectaMediaEngine.fetchTvChannelsWithEpg(targetUrl, targetUser, targetPass)
                    }.getOrDefault(QonectaMediaEngine.FetchedTvList(QonectaMediaEngine.BUILTIN_TDT_CHANNELS))
                    mainHandler.post {
                        allChannels = fetched.channels
                        selectedGroup = null
                        recomputeShownChannels()
                        rebuildGroupChips()
                        renderChannelsGrid()
                        triggerAsyncEpgLoad(fetched.epgUrl ?: preset.defaultEpgUrl)
                    }
                }
            }
        }

        sourcesScroll.addView(sourcesRow)

        rightBrowserCol.addView(gridPlayingBanner, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        rightBrowserCol.addView(sourcesScroll, LinearLayout.LayoutParams(MATCH, WRAP))
        rightBrowserCol.addView(filterAndGroupsRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        rightBrowserCol.addView(channelsScroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })

        if (isSplitScreen) {
            root.addView(playerCard, LinearLayout.LayoutParams(MATCH, 0, 4.7f).apply { bottomMargin = dp(10) })
            root.addView(rightBrowserCol, LinearLayout.LayoutParams(MATCH, 0, 5.3f))
        } else {
            root.addView(playerCard, LinearLayout.LayoutParams(0, MATCH, 4.8f).apply { rightMargin = dp(12) })
            root.addView(rightBrowserCol, LinearLayout.LayoutParams(0, MATCH, 5.2f))
        }

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                stopAllTvPlayback()
            }
        })

        loadSourceByIndex(selectedSourceIdx)
        if (isSplitScreen && shownChannels.isNotEmpty() && playingIndex < 0) {
            // En Pantalla Partida, seleccionamos el primer canal de la lista listo en el reproductor superior
            mainHandler.postDelayed({
                if (playingIndex < 0 && shownChannels.isNotEmpty()) {
                    playChannelAt(0, 0, enterFullscreenIfConfigured = false)
                }
            }, 250L)
        }
        return root
    }

    // =========================================================================
    // 3. PESTAÑA KARAOKE SINCRONIZADO DE ALTA VISIBILIDAD (Portado de KaraokeScreen.java + Lyrics.java)
    // =========================================================================

    fun buildKaraokeView(context: Context, isSplitScreen: Boolean = false): View {
        QonectaTheme.ensureLoaded(context)
        val density = c10Density(context)
        fun dp(v: Int): Int = (v * density).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val prefs = context.getSharedPreferences("qonecta_karaoke_prefs", Context.MODE_PRIVATE)
        // 0 = Grande (38sp), 1 = XL (46sp, por defecto), 2 = XXL Gigante (54sp)
        var fontSizeTier = prefs.getInt("font_size_tier", 1).coerceIn(0, 2)
        val splitFontMult = if (isSplitScreen) 0.84f else 1.0f
        fun activeLineSp(): Float = (when (fontSizeTier) {
            0 -> 38f
            1 -> 46f
            else -> 54f
        }) * splitFontMult
        fun nearLineSp(): Float = (when (fontSizeTier) {
            0 -> 28f
            1 -> 33f
            else -> 38f
        }) * splitFontMult
        fun otherLineSp(): Float = (when (fontSizeTier) {
            0 -> 24f
            1 -> 27.5f
            else -> 31f
        }) * splitFontMult
        fun fontTierLabel(): String = when (fontSizeTier) {
            0 -> "🔤 Letra: Grande"
            1 -> "🔤 Letra: XL"
            else -> "🔤 Letra: XXL"
        }

        // ---------------------------------------------------------------------
        // 3A. CABECERA DE CANCIÓN + CONTROLES DE TRANSPORTE + AJUSTE DE TIEMPO LRC
        // ---------------------------------------------------------------------
        val headerCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = QonectaTheme.cardDrawable(dp(22).toFloat(), QonectaTheme.accent, dp(2))
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val micFrame = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(QonectaTheme.accent, QonectaTheme.accent2)
            ).apply {
                cornerRadius = dp(18).toFloat()
            }
        }
        val micBadge = TextView(context).apply {
            text = "🎤"
            setTextSp(30f)
            gravity = Gravity.CENTER
        }
        val karaokeEq = LiveEqualizerView(context).apply {
            setPlaying(true)
        }
        micFrame.addView(micBadge, FrameLayout.LayoutParams(MATCH, MATCH))
        micFrame.addView(karaokeEq, FrameLayout.LayoutParams(MATCH, dp(18), Gravity.BOTTOM))
        topRow.addView(micFrame, LinearLayout.LayoutParams(dp(64), dp(64)))

        val songMetaCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(10), 0)
        }
        val songTitleView = TextView(context).apply {
            text = "Karaoke Sincronizado LRCLIB"
            setTextSp(24f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        val songArtistView = TextView(context).apply {
            text = "Sincronizado con Spotify / Android Auto / CarPlay / Radio Qonecta+ o búsqueda directa"
            setTextSp(15.5f)
            setTextColor(QonectaTheme.dim)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        songMetaCol.addView(songTitleView)
        songMetaCol.addView(songArtistView)
        topRow.addView(songMetaCol, LinearLayout.LayoutParams(0, WRAP, 1f))

        var manualOverrideActive = false
        var manualPaused = false
        var manualStartElapsedMs = SystemClock.elapsedRealtime()
        var manualPausedPosMs = 0L
        var lyricOffsetMs = 0L
        var autoTrackSignature = ""
        var autoTrackStartElapsedMs = SystemClock.elapsedRealtime()

        var activeLyrics: QonectaMediaEngine.LyricResult? = null
        var lastFetchedKey = ""
        var shownLineIdx = -2
        var shownMovingState = false
        var forceSafeViewMode = false

        fun computeCurrentPosMs(): Long {
            val rawPosMs = if (manualOverrideActive) {
                if (manualPaused) manualPausedPosMs else (SystemClock.elapsedRealtime() - manualStartElapsedMs)
            } else {
                val m = ClusterMapController.mediaState
                val sig = "${m.title}|${m.artist}"
                if (sig != autoTrackSignature) {
                    autoTrackSignature = sig
                    autoTrackStartElapsedMs = SystemClock.elapsedRealtime() - m.positionMs.coerceAtLeast(0L)
                }
                if (manualPaused) {
                    manualPausedPosMs
                } else {
                    val mediaPos = if (m.isPlaying && m.updatedAtElapsedMs > 0L) {
                        m.positionMs + (SystemClock.elapsedRealtime() - m.updatedAtElapsedMs).coerceAtLeast(0L)
                    } else {
                        0L
                    }
                    val fallbackElapsed = (SystemClock.elapsedRealtime() - autoTrackStartElapsedMs).coerceAtLeast(0L)
                    maxOf(mediaPos, fallbackElapsed)
                }
            }
            return (rawPosMs + lyricOffsetMs).coerceAtLeast(0L)
        }

        fun seekKaraokeTo(targetMs: Long) {
            val clamped = targetMs.coerceAtLeast(0L)
            manualOverrideActive = true
            lyricOffsetMs = 0L
            if (manualPaused) {
                manualPausedPosMs = clamped
            } else {
                manualStartElapsedMs = SystemClock.elapsedRealtime() - clamped
            }
            shownLineIdx = -2
        }

        val timeBadge = TextView(context).apply {
            text = "00:00"
            setTextSp(16.5f)
            setTextColor(QonectaTheme.accent)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = QonectaTheme.chipDrawable(dp(14).toFloat(), QonectaTheme.accent)
        }
        topRow.addView(timeBadge, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(6) })

        val skipIntroBtn = pillButton(context, "⏭ Saltar Intro", true, textSp = 14.5f, padHdp = 14) {
            val firstMs = activeLyrics?.synced?.firstOrNull()?.ms ?: 0L
            seekKaraokeTo(maxOf(firstMs, computeCurrentPosMs() + 5_000L))
        }
        val playPauseKaraokeBtn = pillButton(context, "⏸ Pausa", true, textSp = 15f, padHdp = 15) {}
        val restartKaraokeBtn = pillButton(context, "⏮ Inicio", false, textSp = 14.5f, padHdp = 13) {
            val firstMs = activeLyrics?.synced?.firstOrNull()?.ms ?: 0L
            seekKaraokeTo(if (computeCurrentPosMs() < firstMs && firstMs > 2000L) firstMs else 0L)
        }
        val offsetMinusBtn = pillButton(context, "−0.5s", false, textSp = 14.5f, padHdp = 11) {
            lyricOffsetMs -= 500L
            shownLineIdx = -2
            Toast.makeText(context, "Sincronización letra: ${lyricOffsetMs / 1000f} s", Toast.LENGTH_SHORT).show()
        }
        val offsetPlusBtn = pillButton(context, "+0.5s", false, textSp = 14.5f, padHdp = 11) {
            lyricOffsetMs += 500L
            shownLineIdx = -2
            Toast.makeText(context, "Sincronización letra: +${lyricOffsetMs / 1000f} s", Toast.LENGTH_SHORT).show()
        }
        val fontSizeBtn = pillButton(context, fontTierLabel(), true, textSp = 14.5f, padHdp = 14) {}
        val drivingModeBadge = pillButton(context, "🎤 Letra Completa", false, textSp = 14.5f, padHdp = 14) {}
        val karaokeFullscreenBtn = pillButton(
            context,
            if (isSplitScreen) "⛶ Llenar Mitad" else "⛶ Toda la Pantalla",
            false,
            textSp = 14.5f,
            padHdp = 14
        ) {}

        topRow.addView(restartKaraokeBtn, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(5) })
        topRow.addView(skipIntroBtn, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(5) })
        topRow.addView(playPauseKaraokeBtn, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(5) })
        if (!isSplitScreen) {
            topRow.addView(offsetMinusBtn, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(4) })
            topRow.addView(offsetPlusBtn, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(6) })
        }
        topRow.addView(fontSizeBtn, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(6) })
        if (!isSplitScreen) {
            topRow.addView(drivingModeBadge, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(6) })
        }
        topRow.addView(karaokeFullscreenBtn, LinearLayout.LayoutParams(WRAP, dp(46)))
        headerCard.addView(topRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // Fila 2: Buscador grande + Botón Seguir Coche + Accesos rápidos en una sola barra compacta
        val manualRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val searchEdit = EditText(context).apply {
            hint = "🔍 Buscar canción para cantar (ej. Radiohead - No Surprises, Queen, Rosalía, Estopa)…"
            setTextColor(QonectaTheme.text)
            setHintTextColor(QonectaTheme.faint)
            setTextSp(16f)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = QonectaTheme.chipDrawable(dp(14).toFloat())
        }

        // Escenario central de Letras Gigantes
        val lyricsStage = FrameLayout(context).apply {
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = QonectaTheme.cardDrawable(dp(24).toFloat(), QonectaTheme.accent, dp(1))
        }
        val lineViews = ArrayList<TextView>()
        var fullLyricsScroll: ScrollView? = null
        var movingNowView: TextView? = null
        var movingNextView: TextView? = null
        var verseProgressFill: View? = null
        var verseProgressTrack: FrameLayout? = null
        var stageIntroHintTv: TextView? = null
        var fsFloatingTitleTv: TextView? = null
        var fsFloatingFontBtn: TextView? = null
        var fsFloatingPlayBtn: TextView? = null

        fun refreshAllLineStyles(lyr: QonectaMediaEngine.LyricResult, activeIdx: Int, inIntro: Boolean, introRemainSec: Int) {
            val effectiveIdx = if (activeIdx >= 0) activeIdx else 0
            stageIntroHintTv?.apply {
                if (inIntro && lyr.synced.isNotEmpty()) {
                    visibility = View.VISIBLE
                    text = "🎵 INTRO INSTRUMENTAL (${introRemainSec}s para empezar)  ·  Toca cualquier verso o «⏭ Saltar Intro» para cantar ya"
                } else {
                    visibility = View.GONE
                }
            }
            lineViews.forEachIndexed { i, tv ->
                val active = i == effectiveIdx
                val near = abs(i - effectiveIdx) == 1
                val near2 = abs(i - effectiveIdx) == 2
                tv.setTextSp(
                    when {
                        active -> activeLineSp()
                        near -> nearLineSp()
                        near2 -> otherLineSp() + 2f
                        else -> otherLineSp()
                    }
                )
                tv.setTextColor(
                    when {
                        active -> Color.WHITE
                        i < effectiveIdx -> QonectaTheme.dim
                        near -> QonectaTheme.accent
                        else -> QonectaTheme.text
                    }
                )
                tv.typeface = Typeface.create(
                    if (active || near) "sans-serif-black" else "sans-serif-medium",
                    if (active || near) Typeface.BOLD else Typeface.NORMAL
                )
                tv.background = if (active) {
                    GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(
                            QonectaTheme.withAlpha(QonectaTheme.accent, 68),
                            QonectaTheme.withAlpha(QonectaTheme.accent2, 42)
                        )
                    ).apply {
                        cornerRadius = dp(22).toFloat()
                        setStroke(dp(3), QonectaTheme.accent)
                    }
                } else null
            }
        }

        fun ensureSyncedLines(res: QonectaMediaEngine.LyricResult): QonectaMediaEngine.LyricResult {
            if (res.synced.isNotEmpty() || res.plain.isBlank()) return res
            val lines = res.plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return res
            val synth = lines.mapIndexed { idx, txt ->
                QonectaMediaEngine.LyricLine(ms = idx * 3800L, text = txt)
            }
            return res.copy(synced = synth)
        }

        @SuppressLint("ClickableViewAccessibility")
        fun renderLyricsStructure(rawRes: QonectaMediaEngine.LyricResult?, isMoving: Boolean) {
            lyricsStage.removeAllViews()
            lineViews.clear()
            fullLyricsScroll = null
            movingNowView = null
            movingNextView = null
            verseProgressFill = null
            verseProgressTrack = null
            stageIntroHintTv = null
            shownLineIdx = -2
            shownMovingState = isMoving

            if (rawRes == null || !rawRes.found()) {
                lyricsStage.addView(TextView(context).apply {
                    text = "🎵 Reproduce música en Android Auto / CarPlay / Radio Qonecta+ o elige una canción arriba para ver la letra gigante."
                    setTextSp(28f)
                    setTextColor(QonectaTheme.dim)
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(32), dp(32), dp(32), dp(32))
                }, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
                return
            }

            if (rawRes.instrumental) {
                lyricsStage.addView(TextView(context).apply {
                    text = "🎹 Tema Instrumental"
                    setTextSp(38f)
                    setTextColor(QonectaTheme.accent)
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
                return
            }

            val res = ensureSyncedLines(rawRes)
            activeLyrics = res

            val stageCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }

            // Barra superior de progreso del verso + salto rápido táctil de canción
            val progressHeader = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(8))
            }
            val introBanner = TextView(context).apply {
                visibility = View.GONE
                setTextSp(17f)
                setTextColor(0xFF06161C.toInt())
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(8), dp(18), dp(8))
                background = QonectaTheme.accentGradientPill(dp(999).toFloat())
                setOnClickListener {
                    val firstMs = res.synced.firstOrNull()?.ms ?: 0L
                    seekKaraokeTo(firstMs)
                }
            }
            stageIntroHintTv = introBanner
            progressHeader.addView(introBanner, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) })

            val pTrack = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(0x33FFFFFF)
                }
            }
            val pFill = View(context).apply {
                background = QonectaTheme.accentGradientPill(dp(999).toFloat())
            }
            pTrack.addView(pFill, FrameLayout.LayoutParams(0, MATCH))
            pTrack.setOnTouchListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
                    val lastMs = (res.synced.lastOrNull()?.ms ?: 180_000L) + 8_000L
                    val ratio = (ev.x / v.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    seekKaraokeTo((lastMs * ratio).toLong())
                }
                true
            }
            verseProgressTrack = pTrack
            verseProgressFill = pFill
            progressHeader.addView(pTrack, LinearLayout.LayoutParams(MATCH, dp(10)))
            stageCol.addView(progressHeader, LinearLayout.LayoutParams(MATCH, WRAP))

            if (isMoving) {
                // Modo Vista Segura (2 versos gigantes en el centro de la pantalla)
                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(28), dp(20), dp(28), dp(20))
                }
                val nowTv = TextView(context).apply {
                    text = res.synced.firstOrNull()?.text ?: "♪"
                    setTextSp(activeLineSp() + 8f)
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(24), dp(18), dp(24), dp(18))
                    background = GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(
                            QonectaTheme.withAlpha(QonectaTheme.accent, 68),
                            QonectaTheme.withAlpha(QonectaTheme.accent2, 42)
                        )
                    ).apply {
                        cornerRadius = dp(24).toFloat()
                        setStroke(dp(3), QonectaTheme.accent)
                    }
                }
                val nextTv = TextView(context).apply {
                    text = res.synced.getOrNull(1)?.text ?: ""
                    setTextSp(nearLineSp() + 4f)
                    setTextColor(QonectaTheme.accent)
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(24), 0, 0)
                }
                movingNowView = nowTv
                movingNextView = nextTv
                col.addView(nowTv, LinearLayout.LayoutParams(MATCH, WRAP))
                col.addView(nextTv, LinearLayout.LayoutParams(MATCH, WRAP))
                stageCol.addView(col, LinearLayout.LayoutParams(MATCH, 0, 1f))
            } else {
                // Modo Letra Completa con tipografía XXL de escenario y resaltado inmediato desde el verso 1
                val sv = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(12), dp(20), dp(160))
                }
                for ((lineIdx, line) in res.synced.withIndex()) {
                    val tv = TextView(context).apply {
                        text = line.text.ifEmpty { "♪  ·  ♪  ·  ♪" }
                        setTextSp(otherLineSp())
                        setTextColor(QonectaTheme.text)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        gravity = Gravity.CENTER_HORIZONTAL
                        setLineSpacing(0f, 1.12f)
                        setPadding(dp(24), dp(14), dp(24), dp(14))
                        setOnClickListener {
                            manualPaused = false
                            playPauseKaraokeBtn.text = "⏸ Pausa"
                            fsFloatingPlayBtn?.text = "⏸ Pausa"
                            seekKaraokeTo(line.ms)
                        }
                    }
                    lineViews.add(tv)
                    col.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                        if (lineIdx < res.synced.size - 1) bottomMargin = dp(8)
                    })
                }
                sv.addView(col)
                fullLyricsScroll = sv
                stageCol.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
                refreshAllLineStyles(res, 0, false, 0)
            }

            lyricsStage.addView(stageCol, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        fun loadSongLyrics(artist: String, title: String, album: String, durationMs: Long, unsyncedForRadio: Boolean = false) {
            val key = "${artist.lowercase(Locale.ROOT)}|${title.lowercase(Locale.ROOT)}"
            if (key == lastFetchedKey && activeLyrics != null) return
            lastFetchedKey = key
            songTitleView.text = title
            songArtistView.text = if (artist.isNotBlank()) {
                "$artist · ${if (unsyncedForRadio) "Radio en directo · Karaoke Auto-Sync" else "LRCLIB Sincronizado · Toca cualquier verso para cantar desde ahí"}"
            } else {
                "LRCLIB Sincronizado · Toca cualquier verso para cantar desde ahí"
            }
            fsFloatingTitleTv?.text = "🎤 $title · ${artist.ifBlank { "Karaoke C10" }}"

            val cached = QonectaMediaEngine.getCachedLyrics(artist, title)
            if (cached != null) {
                val effective = ensureSyncedLines(cached)
                activeLyrics = effective
                renderLyricsStructure(effective, shownMovingState)
                return
            }

            lyricsStage.removeAllViews()
            lyricsStage.addView(TextView(context).apply {
                text = "⏳ Buscando letra sincronizada de «$title» (${artist.ifBlank { "LRCLIB" }})…"
                setTextSp(28f)
                setTextColor(QonectaTheme.accent)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

            bgExecutor.execute {
                val res = QonectaMediaEngine.fetchLyrics(artist, title, album, durationMs)
                mainHandler.post {
                    if (lastFetchedKey == key) {
                        val effective = ensureSyncedLines(res)
                        activeLyrics = effective
                        renderLyricsStructure(effective, shownMovingState)
                    }
                }
            }
        }

        fun triggerManualSong(artist: String, title: String) {
            manualOverrideActive = true
            manualPaused = false
            manualStartElapsedMs = SystemClock.elapsedRealtime()
            manualPausedPosMs = 0L
            lyricOffsetMs = 0L
            playPauseKaraokeBtn.text = "⏸ Pausa"
            fsFloatingPlayBtn?.text = "⏸ Pausa"
            lastFetchedKey = ""
            loadSongLyrics(artist, title, "", 0L)
        }

        fun toggleKaraokePlayPause() {
            val curPos = computeCurrentPosMs()
            manualOverrideActive = true
            lyricOffsetMs = 0L
            if (manualPaused) {
                manualPaused = false
                manualStartElapsedMs = SystemClock.elapsedRealtime() - curPos
                playPauseKaraokeBtn.text = "⏸ Pausa"
                fsFloatingPlayBtn?.text = "⏸ Pausa"
            } else {
                manualPaused = true
                manualPausedPosMs = curPos
                playPauseKaraokeBtn.text = "▶ Cantar"
                fsFloatingPlayBtn?.text = "▶ Cantar"
            }
        }

        fun cycleFontSizeTier() {
            fontSizeTier = (fontSizeTier + 1) % 3
            prefs.edit().putInt("font_size_tier", fontSizeTier).apply()
            fontSizeBtn.text = fontTierLabel()
            fsFloatingFontBtn?.text = fontTierLabel()
            shownLineIdx = -2
            val lyr = activeLyrics
            if (lyr != null) {
                if (shownMovingState) {
                    movingNowView?.setTextSp(activeLineSp() + 8f)
                    movingNextView?.setTextSp(nearLineSp() + 4f)
                } else {
                    val posMs = computeCurrentPosMs()
                    val firstMs = lyr.synced.firstOrNull()?.ms ?: 0L
                    val inIntro = posMs < firstMs && firstMs > 1500L
                    val idx = QonectaMediaEngine.lyricIndexAt(lyr.synced, posMs)
                    refreshAllLineStyles(lyr, idx, inIntro, ((firstMs - posMs) / 1000L).toInt().coerceAtLeast(1))
                }
            }
        }

        playPauseKaraokeBtn.setOnClickListener { toggleKaraokePlayPause() }
        fontSizeBtn.setOnClickListener { cycleFontSizeTier() }

        val searchBtn = pillButton(context, "🎤 Buscar Letra", true, textSp = 15.5f, padHdp = 18) {
            val raw = searchEdit.text?.toString()?.trim().orEmpty()
            if (raw.isNotEmpty()) {
                val parts = raw.split(" - ", limit = 2)
                val artist = if (parts.size == 2) parts[0].trim() else ""
                val title = if (parts.size == 2) parts[1].trim() else raw
                triggerManualSong(artist, title)
            }
        }
        val syncLiveBtn = pillButton(context, "🔄 Seguir Coche / Radio", false, textSp = 14.5f, padHdp = 14) {
            manualOverrideActive = false
            manualPaused = false
            lyricOffsetMs = 0L
            autoTrackSignature = ""
            playPauseKaraokeBtn.text = "⏸ Pausa"
            fsFloatingPlayBtn?.text = "⏸ Pausa"
            lastFetchedKey = ""
            Toast.makeText(context, "Siguiendo automáticamente la música del coche / radio", Toast.LENGTH_SHORT).show()
        }

        manualRow.addView(searchEdit, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        manualRow.addView(searchBtn, LinearLayout.LayoutParams(WRAP, dp(44)).apply { rightMargin = dp(6) })
        manualRow.addView(syncLiveBtn, LinearLayout.LayoutParams(WRAP, dp(44)))
        headerCard.addView(manualRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        // Fila 3: Accesos rápidos a canciones míticas de Karaoke con 1 toque (incluye Radiohead - No Surprises)
        val quickScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val quickRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val quickSongsWithNoSurprises = listOf("Radiohead" to "No Surprises") + QonectaMediaEngine.KARAOKE_QUICK_SONGS
        quickSongsWithNoSurprises.forEach { (artist, title) ->
            val chip = pillButton(context, "🎵 $artist · $title", false, textSp = 14.5f, padHdp = 14) {
                searchEdit.setText("$artist - $title")
                triggerManualSong(artist, title)
            }
            quickRow.addView(chip, LinearLayout.LayoutParams(WRAP, dp(38)).apply { rightMargin = dp(6) })
        }
        quickScroll.addView(quickRow)
        headerCard.addView(quickScroll, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        // Barra flotante superior para el modo 100% Toda la Pantalla
        val floatingStageBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(0xDD061318.toInt())
                setStroke(dp(2), QonectaTheme.accent)
            }
        }
        val floatingKaraokeExitBtn = pillButton(context, "‹ Buscador y Pestañas", false, textSp = 14.5f, padHdp = 14) {}
        val fsTitle = TextView(context).apply {
            text = "🎤 Karaoke C10"
            setTextSp(18f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(14), 0, dp(14), 0)
        }
        val fsSkipBtn = pillButton(context, "⏭ Saltar Intro", true, textSp = 14f, padHdp = 13) {
            val firstMs = activeLyrics?.synced?.firstOrNull()?.ms ?: 0L
            seekKaraokeTo(maxOf(firstMs, computeCurrentPosMs() + 5_000L))
        }
        val fsPlayBtn = pillButton(context, "⏸ Pausa", true, textSp = 14f, padHdp = 13) {
            toggleKaraokePlayPause()
        }
        val fsFontBtn = pillButton(context, fontTierLabel(), false, textSp = 14f, padHdp = 13) {
            cycleFontSizeTier()
        }
        fsFloatingTitleTv = fsTitle
        fsFloatingPlayBtn = fsPlayBtn
        fsFloatingFontBtn = fsFontBtn
        floatingStageBar.addView(floatingKaraokeExitBtn, LinearLayout.LayoutParams(WRAP, dp(42)))
        floatingStageBar.addView(fsTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
        floatingStageBar.addView(fsSkipBtn, LinearLayout.LayoutParams(WRAP, dp(42)).apply { rightMargin = dp(6) })
        floatingStageBar.addView(fsPlayBtn, LinearLayout.LayoutParams(WRAP, dp(42)).apply { rightMargin = dp(6) })
        floatingStageBar.addView(fsFontBtn, LinearLayout.LayoutParams(WRAP, dp(42)))

        fun applyKaraokeFullscreen(full: Boolean) {
            ExtendedHubView.setFullscreenFor(root, full, showDefaultExitPill = false)
            headerCard.visibility = if (full) View.GONE else View.VISIBLE
            floatingStageBar.visibility = if (full) View.VISIBLE else View.GONE
            lyricsStage.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
                topMargin = if (full) dp(6) else dp(8)
            }
            root.requestLayout()
        }

        karaokeFullscreenBtn.setOnClickListener { applyKaraokeFullscreen(true) }
        floatingKaraokeExitBtn.setOnClickListener { applyKaraokeFullscreen(false) }

        root.addView(headerCard, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(floatingStageBar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(lyricsStage, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })

        // Bucle de sincronización de alta frecuencia cada 100 ms (10 fps) para progreso fluido de verso
        var attached = false
        drivingModeBadge.setOnClickListener {
            forceSafeViewMode = !forceSafeViewMode
            stylePillButton(drivingModeBadge, forceSafeViewMode)
            val lyr = activeLyrics
            if (lyr != null) {
                renderLyricsStructure(lyr, forceSafeViewMode)
            }
        }

        val syncTick = object : Runnable {
            override fun run() {
                if (!attached) return
                val snap = LeapVehicleDataSource.snapshot()
                val realCarMoving = snap.connected && !snap.isDemo && snap.effectiveSpeedKmh > 10.0
                val moving = forceSafeViewMode || realCarMoving
                drivingModeBadge.text = if (moving) "🚗 2 Versos Gigantes" else "🎤 Letra Completa"

                if (!manualOverrideActive) {
                    val radioSt = QonectaMediaEngine.currentStation()
                    val radioSong = if (QonectaMediaEngine.isRadioPlaying && radioSt != null && !radioSt.local) {
                        QonectaMediaEngine.extractSongFromRadioTitle(QonectaMediaEngine.radioLiveTitle, radioSt.name)
                    } else null

                    if (radioSong != null) {
                        loadSongLyrics(radioSong.first, radioSong.second, "", 0L, unsyncedForRadio = true)
                    } else {
                        val media = ClusterMapController.mediaState
                        if (media.title.isNotBlank()) {
                            loadSongLyrics(media.artist, media.title, media.album, media.durationMs)
                        }
                    }
                }

                val lyr = activeLyrics
                if (lyr != null && lyr.synced.isNotEmpty()) {
                    if (moving != shownMovingState) {
                        renderLyricsStructure(lyr, moving)
                    }
                    val posMs = computeCurrentPosMs()
                    val totalDurMs = maxOf(
                        (lyr.synced.lastOrNull()?.ms ?: 180_000L) + 8_000L,
                        ClusterMapController.mediaState.durationMs
                    )
                    val posStr = String.format(Locale.ROOT, "%02d:%02d", (posMs / 1000L) / 60L, (posMs / 1000L) % 60L)
                    val totStr = String.format(Locale.ROOT, "%02d:%02d", (totalDurMs / 1000L) / 60L, (totalDurMs / 1000L) % 60L)
                    timeBadge.text = "$posStr / $totStr"

                    val firstMs = lyr.synced.first().ms
                    val inIntro = posMs < firstMs && firstMs > 1500L
                    val introRemainSec = ((firstMs - posMs + 999L) / 1000L).toInt().coerceAtLeast(1)
                    skipIntroBtn.visibility = if (inIntro) View.VISIBLE else View.GONE

                    val rawIdx = QonectaMediaEngine.lyricIndexAt(lyr.synced, posMs)
                    val effectiveIdx = if (rawIdx >= 0) rawIdx else 0

                    // Actualizar barra de progreso del verso actual
                    verseProgressTrack?.let { trk ->
                        val startMs = if (rawIdx >= 0) lyr.synced[rawIdx].ms else 0L
                        val endMs = if (rawIdx + 1 < lyr.synced.size) lyr.synced[rawIdx + 1].ms else totalDurMs
                        val span = (endMs - startMs).coerceAtLeast(500L)
                        val frac = ((posMs - startMs).toFloat() / span).coerceIn(0f, 1f)
                        if (trk.width > 0) {
                            verseProgressFill?.layoutParams = FrameLayout.LayoutParams(
                                (trk.width * frac).roundToInt().coerceIn(dp(8), trk.width),
                                MATCH
                            )
                        }
                    }

                    if (rawIdx != shownLineIdx || inIntro) {
                        val lineChanged = rawIdx != shownLineIdx
                        shownLineIdx = rawIdx
                        if (moving) {
                            val nowTxt = lyr.synced[effectiveIdx].text.ifEmpty { "♪  ·  ♪  ·  ♪" }
                            val nextTxt = if (effectiveIdx + 1 < lyr.synced.size) lyr.synced[effectiveIdx + 1].text else ""
                            movingNowView?.text = if (inIntro) "🎵 (${introRemainSec}s) $nowTxt" else nowTxt
                            movingNextView?.text = nextTxt
                        } else {
                            refreshAllLineStyles(lyr, effectiveIdx, inIntro, introRemainSec)
                            if (lineChanged && effectiveIdx in lineViews.indices) {
                                val targetView = lineViews[effectiveIdx]
                                fullLyricsScroll?.post {
                                    val sv = fullLyricsScroll ?: return@post
                                    val scrollY = (targetView.top + targetView.height / 2) - sv.height * 35 / 100
                                    sv.smoothScrollTo(0, maxOf(0, scrollY))
                                }
                            }
                        }
                    }
                }
                mainHandler.postDelayed(this, 100L)
            }
        }

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                attached = true
                mainHandler.removeCallbacks(syncTick)
                mainHandler.post(syncTick)
            }

            override fun onViewDetachedFromWindow(v: View) {
                attached = false
                mainHandler.removeCallbacks(syncTick)
            }
        })

        val curMedia = ClusterMapController.mediaState
        if (curMedia.title.isBlank()) {
            manualOverrideActive = true
            // Empezamos directamente en el primer verso de la canción de muestra para que se vea el resaltado XXL al instante
            loadSongLyrics("Radiohead", "No Surprises", "", 229_000L)
            mainHandler.postDelayed({
                val firstMs = activeLyrics?.synced?.firstOrNull()?.ms ?: 15_000L
                seekKaraokeTo(firstMs)
            }, 600L)
        }

        return root
    }

    // =========================================================================
    // 4. PESTAÑA TEMA QONECTA+ Y CENTRO DE PERSONALIZACIÓN TOTAL (CarTheme + Barras RGB + Estilo UI + 3 Ambientes)
    // =========================================================================

    fun buildThemeCustomizationView(
        context: Context,
        isSplitScreen: Boolean = false,
        onThemeChanged: () -> Unit,
    ): View {
        QonectaTheme.ensureLoaded(context)
        val density = c10Density(context)
        fun dp(v: Int): Int = (v * density).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ---------------------------------------------------------------------
        // FILA SUPERIOR: 1. 8 PALETAS DE ACENTO + BARRA ARCOÍRIS DE ACENTO LIBRE (0-360°)
        // ---------------------------------------------------------------------
        val presetCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = QonectaTheme.cardDrawable(dp(20).toFloat(), QonectaTheme.accent, dp(2))
        }
        val presetHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val hexAccent = String.format(Locale.ROOT, "#%06X", QonectaTheme.accent and 0xFFFFFF)
        val hexAccent2 = String.format(Locale.ROOT, "#%06X", QonectaTheme.accent2 and 0xFFFFFF)
        presetHeaderRow.addView(TextView(context).apply {
            text = "🎨 PERSONALIZACIÓN TOTAL QONECTA+ ↔ QONECTA UNIT · ACENTO ACTIVO ($hexAccent → $hexAccent2)"
            setTextSp(15f)
            setTextColor(QonectaTheme.accent)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.03f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))

        val resetThemeBtn = pillButton(
            context,
            "↺ Original Qonecta+",
            false,
            textSp = 13.5f,
            padHdp = 14
        ) {
            QonectaTheme.resetAllDefaults(context)
            onThemeChanged()
        }
        presetHeaderRow.addView(resetThemeBtn, LinearLayout.LayoutParams(WRAP, dp(36)))
        presetCard.addView(presetHeaderRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val presetsRow1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }
        val presetsRow2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }

        QonectaTheme.PRESETS.forEachIndexed { idx, preset ->
            val selected = QonectaTheme.customAccentHue < 0f && QonectaTheme.currentPreset.id == preset.id
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    if (selected) {
                        intArrayOf(QonectaTheme.withAlpha(preset.accentNight, 65), QonectaTheme.surfaceHi)
                    } else {
                        intArrayOf(QonectaTheme.surfaceTop, QonectaTheme.surfaceHi)
                    }
                ).apply {
                    cornerRadius = QonectaTheme.adjustCornerRadius(dp(16).toFloat())
                    setStroke(dp(if (selected) 2 else 1), if (selected) preset.accentNight else QonectaTheme.outline)
                }
                setOnClickListener {
                    QonectaTheme.selectPreset(context, preset.id)
                    onThemeChanged()
                }
            }
            val swatch = View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(preset.accentNight, preset.accent2Night)
                ).apply {
                    cornerRadius = QonectaTheme.adjustCornerRadius(dp(11).toFloat())
                    setStroke(dp(2), Color.WHITE)
                }
            }
            card.addView(swatch, LinearLayout.LayoutParams(dp(36), dp(36)))

            val tCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            tCol.addView(TextView(context).apply {
                text = (if (selected) "✓ " else "") + preset.name
                setTextSp(15.5f)
                setTextColor(if (selected) preset.accentNight else QonectaTheme.text)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            })
            tCol.addView(TextView(context).apply {
                text = preset.subtitle
                setTextSp(12f)
                setTextColor(QonectaTheme.dim)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            })
            card.addView(tCol, LinearLayout.LayoutParams(0, WRAP, 1f))

            val lp = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                if (idx % 4 < 3) rightMargin = dp(8)
            }
            if (idx < 4) presetsRow1.addView(card, lp) else presetsRow2.addView(card, lp)
        }
        presetCard.addView(presetsRow1, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        presetCard.addView(presetsRow2, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        // Barra Táctil Arcoíris para Color de Acento Libre (0° - 360°)
        val rainbowColors = IntArray(13) { i ->
            Color.HSVToColor(floatArrayOf(i * 30f, 0.85f, 0.98f))
        }
        val currentAccentHsv = FloatArray(3)
        Color.colorToHSV(QonectaTheme.accent, currentAccentHsv)
        val accentHuePos = ((if (QonectaTheme.customAccentHue >= 0f) QonectaTheme.customAccentHue else currentAccentHsv[0]) / 360f).coerceIn(0f, 1f)
        val customAccentBarRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        customAccentBarRow.addView(TextView(context).apply {
            text = if (QonectaTheme.customAccentHue >= 0f) "🌈 Acento Libre ($hexAccent):" else "🌈 Acento Libre (desliza):"
            setTextSp(13.5f)
            setTextColor(if (QonectaTheme.customAccentHue >= 0f) QonectaTheme.accent else QonectaTheme.text)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }, LinearLayout.LayoutParams(dp(230), WRAP))

        val accentStrip = QonectaColorStripView(
            context = context,
            colors = rainbowColors,
            initialPos = accentHuePos,
            markerColor = QonectaTheme.accent
        ) { pos ->
            QonectaTheme.setCustomAccentHue(context, pos * 360f)
            onThemeChanged()
        }
        customAccentBarRow.addView(accentStrip, LinearLayout.LayoutParams(0, dp(34), 1f))
        presetCard.addView(customAccentBarRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        root.addView(presetCard, LinearLayout.LayoutParams(MATCH, WRAP))

        // ---------------------------------------------------------------------
        // FILA INFERIOR DIVIDIDA (2 COLUMNAS QUE LLENAN EL 100% DEL ALTO RESTANTE):
        // Izquierda (5.8): Superficie + 16 Colores + Barras Tono/Claridad/Opacidad + Tamaño UI + Bordes + Pintura C10
        // Derecha (4.2): Salvapantallas y Ambiente Interactivo 3-en-1 (Acuario, Reloj Cockpit C10, Nebulosa Neón)
        // ---------------------------------------------------------------------
        val bottomRow = LinearLayout(context).apply {
            orientation = if (isSplitScreen) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            weightSum = 10f
        }

        val leftScroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val leftThemeCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // TARJETA 1 IZQUIERDA: Modo Pantalla + 16 Colores CarTheme + Barras de Tono, Claridad y Opacidad
        val surfaceCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = QonectaTheme.cardDrawable(dp(18).toFloat())
        }
        val surfHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        surfHeader.addView(TextView(context).apply {
            text = "🌙 TEMA DE FONDO, 16 COLORES Y BARRAS RGB (CARTHEME)"
            setTextSp(14f)
            setTextColor(QonectaTheme.accent)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))

        val autoSwatchBtn = pillButton(
            context,
            if (QonectaTheme.fixedPanelColor == 0) "✓ Fondo Automático" else "Fondo Automático",
            QonectaTheme.fixedPanelColor == 0,
            textSp = 13f,
            padHdp = 12
        ) {
            QonectaTheme.setFixedPanel(context, 0)
            onThemeChanged()
        }
        surfHeader.addView(autoSwatchBtn, LinearLayout.LayoutParams(WRAP, dp(34)))
        surfaceCard.addView(surfHeader, LinearLayout.LayoutParams(MATCH, WRAP))

        // 3 Modos de Tema: Noche C10, Negro Puro OLED (#000000), Día Claro
        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            weightSum = 3f
        }
        val modes = listOf(
            Triple(0, "🌙 Noche C10", QonectaTheme.themeMode == 0 && QonectaTheme.fixedPanelColor == 0),
            Triple(2, "🖤 Negro OLED Puro", QonectaTheme.themeMode == 2 && QonectaTheme.fixedPanelColor == 0),
            Triple(1, "☀ Día Claro", QonectaTheme.themeMode == 1 && QonectaTheme.fixedPanelColor == 0)
        )
        modes.forEachIndexed { idx, (modeId, label, active) ->
            val btn = pillButton(context, label, active, textSp = 14f, padHdp = 10) {
                QonectaTheme.setFixedPanel(context, 0)
                QonectaTheme.setThemeMode(context, modeId)
                onThemeChanged()
            }
            modeRow.addView(btn, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                if (idx < 2) rightMargin = dp(6)
            })
        }
        surfaceCard.addView(modeRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        // 16 Colores Oficiales de CarTheme.PANEL_COLORS en 2 filas de 8
        val swatchGridRow1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            weightSum = 8f
        }
        val swatchGridRow2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            weightSum = 8f
        }
        for ((idx, c) in QonectaTheme.PANEL_COLORS.withIndex()) {
            val isSel = QonectaTheme.fixedPanelColor == c
            val badge = when {
                isSel -> "✓"
                c == QonectaTheme.PANEL_NIGHT_SWATCH -> "N"
                c == QonectaTheme.PANEL_DAY_SWATCH -> "D"
                else -> ""
            }
            val circle = TextView(context).apply {
                text = badge
                gravity = Gravity.CENTER
                setTextColor(if (QonectaTheme.isDark(c)) Color.WHITE else Color.BLACK)
                setTextSp(15f)
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    cornerRadius = QonectaTheme.adjustCornerRadius(dp(11).toFloat())
                    setColor(c)
                    setStroke(dp(if (isSel) 3 else 1), if (isSel) QonectaTheme.accent else QonectaTheme.outline)
                }
                setOnClickListener {
                    QonectaTheme.setFixedPanel(context, c)
                    onThemeChanged()
                }
            }
            val lp = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                if (idx % 8 < 7) rightMargin = dp(6)
            }
            if (idx < 8) swatchGridRow1.addView(circle, lp) else swatchGridRow2.addView(circle, lp)
        }
        surfaceCard.addView(swatchGridRow1, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        surfaceCard.addView(swatchGridRow2, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })

        // Barras continuas portadas de SettingsScreen.panelColorRow (Tono + Claridad + Oscurecer)
        val panelHues = IntArray(13) { i -> QonectaTheme.fromHue(i * 30f, 0.55f) }
        val hueRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hueRow.addView(TextView(context).apply {
            text = "Tono Fondo:"
            setTextSp(13f)
            setTextColor(QonectaTheme.dim)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(dp(110), WRAP))
        hueRow.addView(
            QonectaColorStripView(
                context = context,
                colors = panelHues,
                initialPos = (QonectaTheme.panelCustomHue / 360f).coerceIn(0f, 1f),
                markerColor = QonectaTheme.accent
            ) { pos ->
                QonectaTheme.setPanelHueAndLight(context, pos * 360f, QonectaTheme.panelCustomLight)
                onThemeChanged()
            },
            LinearLayout.LayoutParams(0, dp(30), 1f)
        )

        val panelLights = intArrayOf(
            QonectaTheme.fromHue(QonectaTheme.panelCustomHue, 0.12f),
            QonectaTheme.fromHue(QonectaTheme.panelCustomHue, 0.55f),
            QonectaTheme.fromHue(QonectaTheme.panelCustomHue, 1.0f)
        )
        hueRow.addView(TextView(context).apply {
            text = "  Claridad:"
            setTextSp(13f)
            setTextColor(QonectaTheme.dim)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        hueRow.addView(
            QonectaColorStripView(
                context = context,
                colors = panelLights,
                initialPos = QonectaTheme.panelCustomLight.coerceIn(0f, 1f),
                markerColor = QonectaTheme.accent
            ) { pos ->
                QonectaTheme.setPanelHueAndLight(context, QonectaTheme.panelCustomHue, pos)
                onThemeChanged()
            },
            LinearLayout.LayoutParams(0, dp(30), 0.75f)
        )
        surfaceCard.addView(hueRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        // Fila de Transparencia / Atenuación con Negro (Barra + botones rápidos)
        val alphaRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        alphaRow.addView(TextView(context).apply {
            text = "Oscurecer (${QonectaTheme.panelAlphaPct}%):"
            setTextSp(13f)
            setTextColor(QonectaTheme.dim)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(dp(128), WRAP))
        alphaRow.addView(
            QonectaColorStripView(
                context = context,
                colors = intArrayOf(0xFF9AA0A6.toInt(), 0xFF000000.toInt()),
                initialPos = (QonectaTheme.panelAlphaPct / 80f).coerceIn(0f, 1f),
                markerColor = QonectaTheme.accent
            ) { pos ->
                QonectaTheme.setPanelAlpha(context, (pos * 80f).roundToInt())
                onThemeChanged()
            },
            LinearLayout.LayoutParams(0, dp(30), 1f).apply { rightMargin = dp(8) }
        )
        listOf(0, 25, 50, 75).forEachIndexed { idx, pct ->
            val b = pillButton(context, "−$pct%", QonectaTheme.panelAlphaPct == pct, textSp = 12.5f, padHdp = 10) {
                QonectaTheme.setPanelAlpha(context, pct)
                onThemeChanged()
            }
            alphaRow.addView(b, LinearLayout.LayoutParams(WRAP, dp(32)).apply {
                if (idx < 3) rightMargin = dp(4)
            })
        }
        surfaceCard.addView(alphaRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })
        leftThemeCol.addView(surfaceCard, LinearLayout.LayoutParams(MATCH, WRAP))

        // TARJETA 2 IZQUIERDA: Escala Global de Interfaz/Letra + Estilo de Bordes/Esquinas + Pintura C10
        val styleCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = QonectaTheme.cardDrawable(dp(18).toFloat())
        }
        styleCard.addView(TextView(context).apply {
            text = "✨ TAMAÑO DE INTERFAZ / LETRA, ESTILO DE TARJETAS Y PINTURA C10"
            setTextSp(14f)
            setTextColor(QonectaTheme.accent)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        })

        // 1. Escala Global UI y Texto (92%, 100%, 110%, 120%)
        val scaleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scaleRow.addView(TextView(context).apply {
            text = "🔍 Tamaño UI:"
            setTextSp(13.5f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }, LinearLayout.LayoutParams(dp(115), WRAP))
        val scaleOptions = listOf(
            92 to "Compacto 92%",
            100 to "Normal C10 100%",
            110 to "Grande XL 110%",
            120 to "Extra XXL 120%"
        )
        scaleOptions.forEachIndexed { idx, (pct, label) ->
            val b = pillButton(context, label, QonectaTheme.uiScalePct == pct, textSp = 13f, padHdp = 10) {
                QonectaTheme.setUiScalePct(context, pct)
                onThemeChanged()
            }
            scaleRow.addView(b, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                if (idx < scaleOptions.lastIndex) rightMargin = dp(6)
            })
        }
        styleCard.addView(scaleRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        // 2. Estilo de Tarjetas (Bordes) y Radio de Esquinas
        val borderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        borderRow.addView(TextView(context).apply {
            text = "🖼️ Tarjetas:"
            setTextSp(13.5f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }, LinearLayout.LayoutParams(dp(115), WRAP))
        val borderOpts = listOf(
            0 to "Borde Estándar",
            1 to "✨ Filo Neón",
            2 to "💎 Sin Bordes"
        )
        borderOpts.forEachIndexed { idx, (st, label) ->
            val b = pillButton(context, label, QonectaTheme.cardBorderStyle == st, textSp = 13f, padHdp = 8) {
                QonectaTheme.setCardBorderStyle(context, st)
                onThemeChanged()
            }
            borderRow.addView(b, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                rightMargin = dp(5)
            })
        }
        val cornerOpts = listOf(
            0 to "Redondeado",
            1 to "Recto Sport",
            2 to "Cápsula"
        )
        cornerOpts.forEachIndexed { idx, (cs, label) ->
            val b = pillButton(context, label, QonectaTheme.cornerStyle == cs, textSp = 12.5f, padHdp = 8) {
                QonectaTheme.setCornerStyle(context, cs)
                onThemeChanged()
            }
            borderRow.addView(b, LinearLayout.LayoutParams(0, dp(36), 0.85f).apply {
                if (idx < cornerOpts.lastIndex) rightMargin = dp(5)
            })
        }
        styleCard.addView(borderRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        // 3. Pintura Oficial del Leapmotor C10 (sincronizada con la pestaña Estado)
        val carColorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        carColorRow.addView(TextView(context).apply {
            text = "🚗 Pintura C10:"
            setTextSp(13.5f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }, LinearLayout.LayoutParams(dp(115), WRAP))
        val curCarPaint = LeapVehicleDataSource.getSelectedPaintColor(context)
        val allPaints = LeapVehicleDataSource.C10PaintColor.values()
        allPaints.forEachIndexed { idx, paintColor ->
            val hex = paintColor.bodyColor
            val sel = paintColor == curCarPaint
            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = QonectaTheme.adjustCornerRadius(dp(14).toFloat())
                    setColor(if (sel) QonectaTheme.accentBg else QonectaTheme.surfaceHi)
                    setStroke(dp(if (sel) 2 else 1), if (sel) QonectaTheme.accent else QonectaTheme.outline)
                }
                setOnClickListener {
                    LeapVehicleDataSource.setSelectedPaintColor(context, paintColor)
                    onThemeChanged()
                }
            }
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(hex)
                    setStroke(dp(1), Color.WHITE)
                }
            }
            chip.addView(dot, LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(6) })
            chip.addView(TextView(context).apply {
                text = paintColor.label
                setTextSp(12f)
                setTextColor(if (sel) QonectaTheme.accent else QonectaTheme.text)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            })
            carColorRow.addView(chip, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                if (idx < allPaints.lastIndex) rightMargin = dp(5)
            })
        }
        styleCard.addView(carColorRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        leftThemeCol.addView(styleCard, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
        leftScroll.addView(leftThemeCol, ViewGroup.LayoutParams(MATCH, MATCH))

        // ---------------------------------------------------------------------
        // COLUMNA DERECHA: AMBIENTE Y SALVAPANTALLAS INTERACTIVO 3-EN-1 C10
        // (0 = 🐠 Acuario Qonecta+, 1 = ⌚ Reloj Cockpit Gigante C10, 2 = 🌌 Nebulosa Neón)
        // ---------------------------------------------------------------------
        val ambientCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = QonectaTheme.cardDrawable(dp(20).toFloat(), QonectaTheme.accent, dp(2))
        }
        val ambTopBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val ambStageContainer = FrameLayout(context)
        val ambFooterTv = TextView(context).apply {
            setTextSp(13f)
            setTextColor(QonectaTheme.dim)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }

        val aquariumView = QonectaAquariumView(context) { fishCount ->
            if (QonectaTheme.ambientMode == 0) {
                ambFooterTv.text = "🐠 Acuario Qonecta+ ($fishCount peces) · Toca el agua para echar comida"
            }
        }
        val cockpitClockView = QonectaCockpitClockView(context)
        val neonNebulaView = QonectaNeonNebulaView(context)

        val addFishBtn = pillButton(context, "＋ Pez", true, textSp = 13.5f, padHdp = 12) {
            aquariumView.addMoreFish()
        }
        val resetAqBtn = pillButton(context, "🔄", false, textSp = 13.5f, padHdp = 10) {
            aquariumView.resetAquarium()
        }

        val modeAqBtn = pillButton(context, "🐠 Acuario", QonectaTheme.ambientMode == 0, textSp = 13.5f, padHdp = 12) {}
        val modeClockBtn = pillButton(context, "⌚ Reloj C10", QonectaTheme.ambientMode == 1, textSp = 13.5f, padHdp = 12) {}
        val modeNebulaBtn = pillButton(context, "🌌 Nebulosa", QonectaTheme.ambientMode == 2, textSp = 13.5f, padHdp = 12) {}

        fun updateAmbientStage(mode: Int) {
            QonectaTheme.setAmbientMode(context, mode)
            stylePillButton(modeAqBtn, mode == 0)
            stylePillButton(modeClockBtn, mode == 1)
            stylePillButton(modeNebulaBtn, mode == 2)
            addFishBtn.visibility = if (mode == 0) View.VISIBLE else View.GONE
            resetAqBtn.visibility = if (mode == 0) View.VISIBLE else View.GONE
            ambStageContainer.removeAllViews()
            when (mode) {
                0 -> {
                    ambStageContainer.addView(aquariumView, FrameLayout.LayoutParams(MATCH, MATCH))
                    ambFooterTv.text = "🐠 Acuario Relax Qonecta+ · Toca el agua para dar de comer a los peces"
                }
                1 -> {
                    ambStageContainer.addView(cockpitClockView, FrameLayout.LayoutParams(MATCH, MATCH))
                    ambFooterTv.text = "⌚ Reloj Cockpit Leapmotor C10 + Telemetría y Música en Vivo"
                }
                else -> {
                    ambStageContainer.addView(neonNebulaView, FrameLayout.LayoutParams(MATCH, MATCH))
                    ambFooterTv.text = "🌌 Flujo de Energía Neón · Toca o desliza para crear ondas de luz"
                }
            }
        }

        modeAqBtn.setOnClickListener { updateAmbientStage(0) }
        modeClockBtn.setOnClickListener { updateAmbientStage(1) }
        modeNebulaBtn.setOnClickListener { updateAmbientStage(2) }

        var isAmbientFull = false
        val fullAmbBtn = pillButton(context, "⛶ 100%", false, textSp = 13.5f, padHdp = 12) {}
        fullAmbBtn.setOnClickListener {
            isAmbientFull = !isAmbientFull
            ExtendedHubView.setFullscreenFor(root, isAmbientFull, showDefaultExitPill = false)
            presetCard.visibility = if (isAmbientFull) View.GONE else View.VISIBLE
            leftScroll.visibility = if (isAmbientFull) View.GONE else View.VISIBLE
            fullAmbBtn.text = if (isAmbientFull) "‹ Volver a Personalización" else "⛶ 100%"
            stylePillButton(fullAmbBtn, isAmbientFull)
            ambientCard.layoutParams = LinearLayout.LayoutParams(
                0,
                MATCH,
                if (isAmbientFull) 10f else 4.2f
            )
            bottomRow.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
                topMargin = if (isAmbientFull) 0 else dp(8)
            }
            root.requestLayout()
        }

        ambTopBar.addView(modeAqBtn, LinearLayout.LayoutParams(WRAP, dp(36)).apply { rightMargin = dp(5) })
        ambTopBar.addView(modeClockBtn, LinearLayout.LayoutParams(WRAP, dp(36)).apply { rightMargin = dp(5) })
        ambTopBar.addView(modeNebulaBtn, LinearLayout.LayoutParams(WRAP, dp(36)).apply { rightMargin = dp(6) })
        ambTopBar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        ambTopBar.addView(addFishBtn, LinearLayout.LayoutParams(WRAP, dp(36)).apply { rightMargin = dp(5) })
        ambTopBar.addView(resetAqBtn, LinearLayout.LayoutParams(WRAP, dp(36)).apply { rightMargin = dp(5) })
        ambTopBar.addView(fullAmbBtn, LinearLayout.LayoutParams(WRAP, dp(36)))

        ambientCard.addView(ambTopBar, LinearLayout.LayoutParams(MATCH, WRAP))
        ambientCard.addView(ambStageContainer, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(6) })
        ambientCard.addView(ambFooterTv, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })

        updateAmbientStage(QonectaTheme.ambientMode)

        if (isSplitScreen) {
            bottomRow.addView(leftScroll, LinearLayout.LayoutParams(MATCH, 0, 5.6f).apply { bottomMargin = dp(8) })
            bottomRow.addView(ambientCard, LinearLayout.LayoutParams(MATCH, 0, 4.4f))
        } else {
            bottomRow.addView(leftScroll, LinearLayout.LayoutParams(0, MATCH, 5.8f).apply { rightMargin = dp(10) })
            bottomRow.addView(ambientCard, LinearLayout.LayoutParams(0, MATCH, 4.2f))
        }

        root.addView(bottomRow, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
        return root
    }

    /**
     * Barra táctil de degradado continuo con marcador circular (portada 1:1 de SettingsScreen.strip en Qonecta+).
     * Permite elegir cualquier tono (0-360°), claridad (0-100%) u opacidad tocando o deslizando el dedo.
     */
    private class QonectaColorStripView(
        context: Context,
        private val colors: IntArray,
        initialPos: Float,
        private val markerColor: Int,
        private val onPick: (Float) -> Unit
    ) : View(context) {
        private var pos = initialPos.coerceIn(0f, 1f)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val barRect = RectF()
        private var downX = 0f
        private var downY = 0f
        private var dragging = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val density = c10Density(context)
            val barHalfH = 8f * density
            val padH = 12f * density
            barRect.set(padH, h / 2f - barHalfH, w - padH, h / 2f + barHalfH)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                barRect.left, 0f, barRect.right, 0f,
                colors, null, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(barRect, barHalfH, barHalfH, paint)
            paint.shader = null

            // Contorno sutil de la barra
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            paint.color = 0x55FFFFFF
            canvas.drawRoundRect(barRect, barHalfH, barHalfH, paint)

            // Marcador circular en la posición actual
            val cx = barRect.left + pos * barRect.width()
            val cy = h / 2f
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, 11f * density, paint)
            paint.color = markerColor
            canvas.drawCircle(cx, cy, 7.5f * density, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val w = width.toFloat()
            if (w <= 0f) return false
            val padH = 12f * c10Density(context)
            val usableW = max(1f, w - 2f * padH)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    dragging = false
                    pos = ((event.x - padH) / usableW).coerceIn(0f, 1f)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && kotlin.math.abs(event.x - downX) > 12f &&
                        kotlin.math.abs(event.x - downX) > kotlin.math.abs(event.y - downY)
                    ) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        pos = ((event.x - padH) / usableW).coerceIn(0f, 1f)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    pos = ((event.x - padH) / usableW).coerceIn(0f, 1f)
                    invalidate()
                    onPick(pos)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    /**
     * Modo Ambiental 2: Reloj Cockpit Gigante Leapmotor C10 + Telemetría en Vivo + Canción Actual.
     */
    private class QonectaCockpitClockView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arcRect = RectF()
        private var attached = false
        private val tick = object : Runnable {
            override fun run() {
                if (!attached) return
                invalidate()
                postDelayed(this, 200L)
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            attached = true
            removeCallbacks(tick)
            post(tick)
        }

        override fun onDetachedFromWindow() {
            attached = false
            removeCallbacks(tick)
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            // Fondo radial oscuro con resplandor del color de acento
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(0xFF060B11.toInt(), QonectaTheme.withAlpha(QonectaTheme.accentBg, 220), 0xFF05080D.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(RectF(0f, 0f, w, h), 24f, 24f, paint)
            paint.shader = null

            val cx = w * 0.5f
            val cy = h * 0.44f
            val radius = min(w, h) * 0.34f

            // Aro exterior de segundos con el degradado de acento Qonecta+
            val nowMs = System.currentTimeMillis()
            val cal = java.util.Calendar.getInstance()
            val sec = cal.get(java.util.Calendar.SECOND) + (nowMs % 1000L) / 1000f
            arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = radius * 0.08f
            paint.color = 0x28FFFFFF
            canvas.drawOval(arcRect, paint)

            paint.strokeCap = Paint.Cap.ROUND
            paint.color = QonectaTheme.accent
            canvas.drawArc(arcRect, -90f, (sec / 60f) * 360f, false, paint)

            // Hora digital gigante en el centro del aro
            val timeStr = String.format(
                Locale.getDefault(),
                "%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE)
            )
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            paint.color = Color.WHITE
            paint.textSize = radius * 0.62f
            canvas.drawText(timeStr, cx, cy + radius * 0.14f, paint)

            // Segundos y Fecha debajo de la hora
            val dateStr = java.text.SimpleDateFormat("EEE d MMM · ss 's'", Locale.getDefault()).format( java.util.Date(nowMs) ).uppercase()
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            paint.color = QonectaTheme.accent2
            paint.textSize = radius * 0.18f
            canvas.drawText(dateStr, cx, cy + radius * 0.44f, paint)

            // Píldoras inferiores de telemetría C10 en vivo
            val snap = LeapVehicleDataSource.snapshot(nowMs)
            val soc = if (!snap.socPct.isNaN()) snap.socPct.roundToInt() else 78
            val rng = if (!snap.effectiveRangeKm.isNaN()) snap.effectiveRangeKm.roundToInt() else 420
            val spd = snap.effectiveSpeedKmh.roundToInt()
            val pwr = if (!snap.powerKw.isNaN()) snap.powerKw else 0.8

            val barY = h * 0.86f
            val pillW = w * 0.88f
            val pillH = h * 0.14f
            val pRect = RectF(cx - pillW / 2f, barY - pillH / 2f, cx + pillW / 2f, barY + pillH / 2f)
            paint.style = Paint.Style.FILL
            paint.color = 0xAA0D1620.toInt()
            canvas.drawRoundRect(pRect, pillH / 2f, pillH / 2f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = QonectaTheme.accent
            canvas.drawRoundRect(pRect, pillH / 2f, pillH / 2f, paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = min(w * 0.036f, pillH * 0.42f)
            val telemText = String.format(
                Locale.getDefault(),
                "⚡ %d%% (%d km)   ·   🚀 %d km/h   ·   🔋 %+.1f kW",
                soc, rng, spd, pwr
            )
            canvas.drawText(telemText, cx, barY + paint.textSize * 0.36f, paint)
        }
    }

    /**
     * Modo Ambiental 3: Nebulosa y Flujo de Energía Neón C10 (interactivo al toque, sincronizado con el acento activo).
     */
    private class QonectaNeonNebulaView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val wavePath = Path()
        private var phase = 0f
        private var touchX = -1f
        private var touchY = -1f
        private var rippleRadius = 0f
        private var attached = false

        private val tick = object : Runnable {
            override fun run() {
                if (!attached) return
                phase += 0.065f
                if (rippleRadius > 0f) {
                    rippleRadius += 14f
                    if (rippleRadius > max(width, height)) rippleRadius = 0f
                }
                invalidate()
                postDelayed(this, 32L)
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            attached = true
            removeCallbacks(tick)
            post(tick)
        }

        override fun onDetachedFromWindow() {
            attached = false
            removeCallbacks(tick)
            super.onDetachedFromWindow()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                touchX = event.x
                touchY = event.y
                rippleRadius = 18f
                invalidate()
                return true
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0xFF04070B.toInt(), QonectaTheme.withAlpha(QonectaTheme.accentBg, 210), 0xFF030609.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(RectF(0f, 0f, w, h), 24f, 24f, paint)
            paint.shader = null

            // 5 ondas sinusoidales luminosas con los colores de acento
            for (layer in 0 until 5) {
                wavePath.rewind()
                val amp = h * (0.07f + layer * 0.025f)
                val freq = 1.6f + layer * 0.35f
                val yBase = h * (0.32f + layer * 0.10f)
                var x = 0f
                while (x <= w) {
                    val normX = x / w
                    val angle = normX * freq * Math.PI.toFloat() * 2f + phase * (1f + layer * 0.22f)
                    val y = yBase + sin(angle) * amp
                    if (x == 0f) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
                    x += 16f
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (5.5f - layer * 0.7f).coerceAtLeast(2f)
                val col = if (layer % 2 == 0) QonectaTheme.accent else QonectaTheme.accent2
                paint.color = QonectaTheme.withAlpha(col, 220 - layer * 32)
                canvas.drawPath(wavePath, paint)
            }

            // Onda expansiva al tocar el lienzo
            if (rippleRadius > 0f && touchX >= 0f) {
                val alpha = (220f * (1f - (rippleRadius / max(w, h)).coerceIn(0f, 1f))).roundToInt()
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                paint.color = QonectaTheme.withAlpha(QonectaTheme.accent2, alpha)
                canvas.drawCircle(touchX, touchY, rippleRadius, paint)
                paint.color = QonectaTheme.withAlpha(QonectaTheme.accent, (alpha * 0.6f).roundToInt())
                canvas.drawCircle(touchX, touchY, rippleRadius * 0.6f, paint)
            }
        }
    }

    // =========================================================================
    // 5. PESTAÑA WEB / YOUTUBE A TODA PANTALLA (Portado de WebScreen.java + AdBlockJs.java + YouTubeQuality.java)
    // =========================================================================

    private val WEB_SHORTCUTS = listOf(
        Triple("▶ YouTube", "https://m.youtube.com", 0xFFD32F2F.toInt()),
        Triple("📺 RTVE Play", "https://www.rtve.es/play/", 0xFFEF6C00.toInt()),
        Triple("🟠 Atresplayer", "https://www.atresplayer.com/directos/", 0xFFFF6D00.toInt()),
        Triple("🔵 Mitele", "https://www.mitele.es/directo/", 0xFF0288D1.toInt()),
        Triple("🟣 Twitch", "https://m.twitch.tv", 0xFF7B1FA2.toInt()),
        Triple("🔍 Google", "https://www.google.com", 0xFF1976D2.toInt()),
        Triple("📖 Wikipedia", "https://es.wikipedia.org", 0xFF455A64.toInt()),
        Triple("⚡ Electromaps", "https://map.electromaps.com", 0xFF00897B.toInt())
    )

    @SuppressLint("SetJavaScriptEnabled")
    fun buildWebView(context: Context, isSplitScreen: Boolean = false): View {
        QonectaTheme.ensureLoaded(context)
        val density = c10Density(context)
        fun dp(v: Int): Int = (v * density).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val prefs = context.applicationContext.getSharedPreferences("qonecta_web_prefs", Context.MODE_PRIVATE)
        var ytQuality = prefs.getInt("yt_quality", 720) // 0=Auto, 360, 480, 720
        var desktopMode = prefs.getBoolean("desktop_mode", false)
        var adBlockEnabled = prefs.getBoolean("adblock_on", true)
        var isWebFullscreen = false

        val topCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = QonectaTheme.cardDrawable(dp(20).toFloat(), QonectaTheme.accent, dp(2))
        }

        val navRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val backBtn = pillButton(context, "‹", false, textSp = 19f, padHdp = 14) {}
        val fwdBtn = pillButton(context, "›", false, textSp = 19f, padHdp = 14) {}
        val reloadBtn = pillButton(context, "⟳", false, textSp = 17f, padHdp = 14) {}

        val urlInput = EditText(context).apply {
            hint = "🌐 Escribe dirección web o búsqueda en Google / YouTube…"
            setTextColor(QonectaTheme.text)
            setHintTextColor(QonectaTheme.faint)
            setTextSp(15.5f)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = QonectaTheme.chipDrawable(dp(14).toFloat())
        }
        val goBtn = pillButton(context, "Ir", true, textSp = 15.5f, padHdp = 16) {}
        val adBlockBtn = pillButton(context, "🛡 Sin Anuncios", adBlockEnabled, textSp = 14.5f, padHdp = 13) {}
        val ytQualBtn = pillButton(
            context,
            "🎬 YT: ${if (ytQuality == 0) "Auto" else "${ytQuality}p"}",
            ytQuality > 0,
            textSp = 14.5f,
            padHdp = 13
        ) {}
        val pcModeBtn = pillButton(context, if (desktopMode) "🖥 Modo PC" else "📱 Móvil", desktopMode, textSp = 14.5f, padHdp = 13) {}
        val fullWebBtn = pillButton(context, if (isSplitScreen) "⛶ Llenar Mitad" else "⛶ Toda la Pantalla", false, textSp = 15f, padHdp = 15) {}

        navRow.addView(backBtn, LinearLayout.LayoutParams(WRAP, dp(44)).apply { rightMargin = dp(5) })
        navRow.addView(fwdBtn, LinearLayout.LayoutParams(WRAP, dp(44)).apply { rightMargin = dp(5) })
        navRow.addView(reloadBtn, LinearLayout.LayoutParams(WRAP, dp(44)).apply { rightMargin = dp(8) })
        navRow.addView(urlInput, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        navRow.addView(goBtn, LinearLayout.LayoutParams(WRAP, dp(44)).apply { rightMargin = dp(6) })
        navRow.addView(adBlockBtn, LinearLayout.LayoutParams(WRAP, dp(44)).apply { rightMargin = dp(6) })
        navRow.addView(ytQualBtn, LinearLayout.LayoutParams(WRAP, dp(44)).apply { rightMargin = dp(6) })
        navRow.addView(pcModeBtn, LinearLayout.LayoutParams(WRAP, dp(44)).apply { rightMargin = dp(6) })
        navRow.addView(fullWebBtn, LinearLayout.LayoutParams(WRAP, dp(44)))
        topCard.addView(navRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val shortcutsScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val shortcutsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        shortcutsScroll.addView(shortcutsRow)
        topCard.addView(shortcutsScroll, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        val webStage = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), QonectaTheme.outline)
            }
            clipToOutline = true
        }

        val webView = WebView(context)
        val defaultMobileUa = webView.settings.userAgentString
        val desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        fun applyUaMode() {
            webView.settings.userAgentString = if (desktopMode) desktopUa else defaultMobileUa
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
        }

        var customVideoView: View? = null
        var customVideoCallback: WebChromeClient.CustomViewCallback? = null

        val floatingExitWebFullBtn = pillButton(context, "‹ Mostrar Barra Web / Pestañas", true, textSp = 14.5f, padHdp = 15) {}

        fun applyWebFullscreen(full: Boolean) {
            isWebFullscreen = full
            ExtendedHubView.setFullscreenFor(root, full, showDefaultExitPill = false)
            topCard.visibility = if (full) View.GONE else View.VISIBLE
            webStage.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
                topMargin = if (full) 0 else dp(8)
            }
            webStage.background = if (full) {
                GradientDrawable().apply { setColor(Color.BLACK) }
            } else {
                GradientDrawable().apply {
                    setColor(Color.BLACK)
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), QonectaTheme.outline)
                }
            }
            floatingExitWebFullBtn.visibility = if (full) View.VISIBLE else View.GONE
            floatingExitWebFullBtn.bringToFront()
            root.requestLayout()
        }

        webView.apply {
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                setSupportMultipleWindows(false)
            }
            applyUaMode()
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!url.isNullOrBlank()) {
                        urlInput.setText(url)
                        prefs.edit().putString("last_url", url).apply()
                    }
                    if (adBlockEnabled) {
                        view?.evaluateJavascript(QonectaMediaEngine.ADBLOCK_JS_SCRIPT, null)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (adBlockEnabled) {
                        view?.evaluateJavascript(QonectaMediaEngine.ADBLOCK_JS_SCRIPT, null)
                    }
                    val host = runCatching { Uri.parse(url ?: "").host?.lowercase(Locale.ROOT) }.getOrNull()
                    if (host != null && (host.contains("youtube.com") || host.contains("youtu.be"))) {
                        view?.evaluateJavascript(QonectaMediaEngine.youtubeQualityJs(ytQuality), null)
                    }
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (adBlockEnabled && QonectaMediaEngine.isAdDomain(request?.url?.host)) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.resources?.forEach { res ->
                        if (res == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID) {
                            request.grant(arrayOf(res))
                            return
                        }
                    }
                    request?.deny()
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null) return
                    customVideoView?.let { webStage.removeView(it) }
                    customVideoView = view
                    customVideoCallback = callback
                    webStage.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
                    applyWebFullscreen(true)
                }

                override fun onHideCustomView() {
                    customVideoView?.let { webStage.removeView(it) }
                    customVideoView = null
                    customVideoCallback?.onCustomViewHidden()
                    customVideoCallback = null
                }
            }
        }

        fun navigateToInput(raw: String) {
            val q = raw.trim()
            if (q.isEmpty()) return
            val target = when {
                q.startsWith("http://", true) || q.startsWith("https://", true) -> q
                q.contains(".") && !q.contains(" ") -> "https://$q"
                else -> "https://www.google.com/search?q=" + Uri.encode(q)
            }
            webView.loadUrl(target)
        }

        WEB_SHORTCUTS.forEach { (label, targetUrl, _) ->
            val chip = pillButton(context, label, false, textSp = 14.5f, padHdp = 14) {
                webView.loadUrl(targetUrl)
            }
            shortcutsRow.addView(chip, LinearLayout.LayoutParams(WRAP, dp(40)).apply { rightMargin = dp(6) })
        }

        backBtn.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        fwdBtn.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        reloadBtn.setOnClickListener { webView.reload() }
        goBtn.setOnClickListener { navigateToInput(urlInput.text?.toString().orEmpty()) }
        urlInput.setOnEditorActionListener { _, _, _ ->
            navigateToInput(urlInput.text?.toString().orEmpty())
            true
        }
        adBlockBtn.setOnClickListener {
            adBlockEnabled = !adBlockEnabled
            prefs.edit().putBoolean("adblock_on", adBlockEnabled).apply()
            stylePillButton(adBlockBtn, adBlockEnabled)
            webView.reload()
        }
        ytQualBtn.setOnClickListener {
            val choices = intArrayOf(0, 360, 480, 720)
            val curIdx = choices.indexOf(ytQuality).coerceAtLeast(0)
            ytQuality = choices[(curIdx + 1) % choices.size]
            prefs.edit().putInt("yt_quality", ytQuality).apply()
            ytQualBtn.text = "🎬 YT: ${if (ytQuality == 0) "Auto" else "${ytQuality}p"}"
            stylePillButton(ytQualBtn, ytQuality > 0)
            webView.evaluateJavascript(QonectaMediaEngine.youtubeQualityJs(ytQuality), null)
        }
        pcModeBtn.setOnClickListener {
            desktopMode = !desktopMode
            prefs.edit().putBoolean("desktop_mode", desktopMode).apply()
            pcModeBtn.text = if (desktopMode) "🖥 Modo PC" else "📱 Móvil"
            stylePillButton(pcModeBtn, desktopMode)
            applyUaMode()
            webView.reload()
        }
        fullWebBtn.setOnClickListener { applyWebFullscreen(true) }
        floatingExitWebFullBtn.setOnClickListener {
            if (customVideoView != null) {
                webView.webChromeClient?.onHideCustomView()
            }
            applyWebFullscreen(false)
        }
        floatingExitWebFullBtn.visibility = View.GONE

        webStage.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
        webStage.addView(
            floatingExitWebFullBtn,
            FrameLayout.LayoutParams(WRAP, dp(42), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(10)
                rightMargin = dp(14)
            }
        )

        root.addView(topCard, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(webStage, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })

        val lastUrl = prefs.getString("last_url", "https://m.youtube.com") ?: "https://m.youtube.com"
        webView.loadUrl(lastUrl)
        return root
    }

    // =========================================================================
    // 6. PESTAÑA VÍDEOS Y FOTOS USB A TODA PANTALLA (Portado de GalleryScreen.java + VideoPlayer.java)
    // =========================================================================

    @SuppressLint("ClickableViewAccessibility")
    fun buildVideosView(context: Context, isSplitScreen: Boolean = false): View {
        QonectaTheme.ensureLoaded(context)
        val density = c10Density(context)
        fun dp(v: Int): Int = (v * density).roundToInt()

        val root = FrameLayout(context)

        // Vista 1: Galería de álbumes y cuadrícula de vídeos / fotos
        val browserLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val headerCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = QonectaTheme.cardDrawable(dp(20).toFloat(), QonectaTheme.accent, dp(2))
        }
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val headerTitleTv = TextView(context).apply {
            text = "🎬 Reproductor de Vídeos y Fotos USB · Qonecta+ (100% Pantalla Completa)"
            setTextSp(19.5f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        val headerSubTv = TextView(context).apply {
            text = "Doble toque ±10 s en los lados · Velocidad 1× / 1.5× / 2× · Proporción Llenar 100% o 16:9 · Marca de reanudación"
            setTextSp(14f)
            setTextColor(QonectaTheme.dim)
            setPadding(0, dp(2), 0, 0)
        }
        titleCol.addView(headerTitleTv)
        titleCol.addView(headerSubTv)
        headerCard.addView(titleCol, LinearLayout.LayoutParams(0, WRAP, 1f))

        val rescanUsbBtn = pillButton(context, "🔄 Escanear USB / Coche", false, textSp = 15f, padHdp = 16) {}
        val openCustomUrlBtn = pillButton(context, "＋ Abrir URL de Vídeo (.mp4/.m3u8)", true, textSp = 15f, padHdp = 16) {}
        headerCard.addView(rescanUsbBtn, LinearLayout.LayoutParams(WRAP, dp(44)).apply { rightMargin = dp(8) })
        headerCard.addView(openCustomUrlBtn, LinearLayout.LayoutParams(WRAP, dp(44)))

        val albumsScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val albumsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        albumsScroll.addView(albumsRow)

        val gridScroll = ScrollView(context).apply { isFillViewport = true }
        val gridContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        gridScroll.addView(gridContainer)

        browserLayout.addView(headerCard, LinearLayout.LayoutParams(MATCH, WRAP))
        browserLayout.addView(albumsScroll, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        browserLayout.addView(gridScroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })

        // Vista 2: Reproductor de Vídeo a TODA LA PANTALLA (100% 2560x1440, portado de VideoPlayer.java)
        val playerStage = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        var isVidFill = true
        val videoView = ResizableVideoView(context).apply {
            fillScreen = isVidFill
        }

        val playerTopControls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xE6070D14.toInt(), 0xCC0D1622.toInt())
            ).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(2), QonectaTheme.accent)
            }
        }
        val vpBackBtn = pillButton(context, "‹ Galería", false, textSp = 16f, padHdp = 16) {}
        val vpTitleTv = TextView(context).apply {
            text = "🎬 Vídeo"
            setTextSp(18.5f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(16), 0, dp(16), 0)
        }
        val vpRewBtn = pillButton(context, "⏪ −10 s", false, textSp = 15f, padHdp = 14) {}
        val vpPlayPauseBtn = pillButton(context, "⏸ Pausa", true, textSp = 15.5f, padHdp = 16) {}
        val vpFfwdBtn = pillButton(context, "⏩ +10 s", false, textSp = 15f, padHdp = 14) {}
        val vpTimeTv = TextView(context).apply {
            text = "00:00 / 00:00"
            setTextSp(15.5f)
            setTextColor(QonectaTheme.accent)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setPadding(dp(12), 0, dp(12), 0)
        }
        val vpFillBtn = pillButton(context, "📐 Llenar 100%", true, textSp = 15f, padHdp = 14) {}

        playerTopControls.addView(vpBackBtn, LinearLayout.LayoutParams(WRAP, dp(46)))
        playerTopControls.addView(vpTitleTv, LinearLayout.LayoutParams(0, WRAP, 1f))
        playerTopControls.addView(vpRewBtn, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(6) })
        playerTopControls.addView(vpPlayPauseBtn, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(6) })
        playerTopControls.addView(vpFfwdBtn, LinearLayout.LayoutParams(WRAP, dp(46)).apply { rightMargin = dp(6) })
        playerTopControls.addView(vpTimeTv, LinearLayout.LayoutParams(WRAP, WRAP))
        playerTopControls.addView(vpFillBtn, LinearLayout.LayoutParams(WRAP, dp(46)))

        // Barra inferior de progreso gruesa para tocar con el dedo (como SeekTrack de VideoPlayer.java)
        val seekBarCard = FrameLayout(context).apply {
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xD9081018.toInt())
                setStroke(dp(1), QonectaTheme.outline)
            }
        }
        val seekProgressFill = View(context).apply {
            background = QonectaTheme.accentGradientPill(dp(12).toFloat())
        }
        val seekTrackBg = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF283442.toInt())
            }
            addView(seekProgressFill, FrameLayout.LayoutParams(0, MATCH))
        }
        seekBarCard.addView(seekTrackBg, FrameLayout.LayoutParams(MATCH, dp(22)))

        val hideVpControlsRunnable = Runnable {
            playerTopControls.visibility = View.GONE
            seekBarCard.visibility = View.GONE
        }
        fun showVpControlsTemporarily() {
            mainHandler.removeCallbacks(hideVpControlsRunnable)
            playerTopControls.visibility = View.VISIBLE
            seekBarCard.visibility = View.VISIBLE
            mainHandler.postDelayed(hideVpControlsRunnable, 4000L)
        }

        seekTrackBg.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
                val dur = videoView.duration
                if (dur > 0 && v.width > 0) {
                    val frac = (ev.x / v.width).coerceIn(0f, 1f)
                    videoView.seekTo((dur * frac).roundToInt())
                    showVpControlsTemporarily()
                }
                true
            } else false
        }

        val vpGesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerTopControls.visibility == View.VISIBLE) {
                    mainHandler.removeCallbacks(hideVpControlsRunnable)
                    playerTopControls.visibility = View.GONE
                    seekBarCard.visibility = View.GONE
                } else {
                    showVpControlsTemporarily()
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = maxOf(1, playerStage.width)
                val cur = videoView.currentPosition
                val dur = videoView.duration
                if (e.x < w * 0.4f) {
                    videoView.seekTo((cur - 10_000).coerceAtLeast(0))
                } else if (e.x > w * 0.6f && dur > 0) {
                    videoView.seekTo((cur + 10_000).coerceAtMost(dur))
                } else {
                    if (videoView.isPlaying) videoView.pause() else videoView.start()
                }
                showVpControlsTemporarily()
                return true
            }
        })
        val vpTouchLayer = View(context).apply {
            setOnTouchListener { _, ev -> vpGesture.onTouchEvent(ev) }
        }

        playerStage.addView(videoView, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        playerStage.addView(vpTouchLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        playerStage.addView(
            playerTopControls,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply {
                topMargin = dp(14)
                leftMargin = dp(18)
                rightMargin = dp(18)
            }
        )
        playerStage.addView(
            seekBarCard,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
                bottomMargin = dp(18)
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
        )

        val vpTickRunnable = object : Runnable {
            override fun run() {
                if (playerStage.visibility != View.VISIBLE) return
                val pos = videoView.currentPosition.coerceAtLeast(0)
                val dur = videoView.duration.coerceAtLeast(0)
                val posStr = String.format(Locale.ROOT, "%02d:%02d", (pos / 1000) / 60, (pos / 1000) % 60)
                val durStr = String.format(Locale.ROOT, "%02d:%02d", (dur / 1000) / 60, (dur / 1000) % 60)
                vpTimeTv.text = "$posStr / $durStr"
                vpPlayPauseBtn.text = if (videoView.isPlaying) "⏸ Pausa" else "▶ Seguir"
                if (dur > 0 && seekTrackBg.width > 0) {
                    val fillW = (seekTrackBg.width * (pos.toFloat() / dur)).roundToInt().coerceIn(0, seekTrackBg.width)
                    seekProgressFill.layoutParams = FrameLayout.LayoutParams(fillW, MATCH)
                }
                mainHandler.postDelayed(this, 500L)
            }
        }

        fun openVideoItemFullscreen(item: QonectaMediaEngine.LocalMediaItem) {
            if (QonectaMediaEngine.isRadioPlaying) {
                QonectaMediaEngine.stopRadio(context)
            }
            ExtendedHubView.setFullscreenFor(root, true, showDefaultExitPill = false)
            browserLayout.visibility = View.GONE
            playerStage.visibility = View.VISIBLE
            vpTitleTv.text = "🎬 ${item.title}"
            videoView.setOnPreparedListener {
                videoView.start()
                showVpControlsTemporarily()
                mainHandler.removeCallbacks(vpTickRunnable)
                mainHandler.post(vpTickRunnable)
            }
            videoView.setOnErrorListener { _, _, _ ->
                vpTitleTv.text = "⚠️ Señal no disponible · ${item.title}"
                true
            }
            videoView.setVideoURI(Uri.parse(item.uri))
            videoView.requestFocus()
            videoView.start()
        }

        fun closeVideoPlayer() {
            mainHandler.removeCallbacks(hideVpControlsRunnable)
            mainHandler.removeCallbacks(vpTickRunnable)
            runCatching { videoView.stopPlayback() }
            playerStage.visibility = View.GONE
            browserLayout.visibility = View.VISIBLE
            ExtendedHubView.setFullscreenFor(root, false)
        }

        vpBackBtn.setOnClickListener { closeVideoPlayer() }
        vpRewBtn.setOnClickListener {
            videoView.seekTo((videoView.currentPosition - 10_000).coerceAtLeast(0))
            showVpControlsTemporarily()
        }
        vpFfwdBtn.setOnClickListener {
            val dur = videoView.duration
            if (dur > 0) videoView.seekTo((videoView.currentPosition + 10_000).coerceAtMost(dur))
            showVpControlsTemporarily()
        }
        vpPlayPauseBtn.setOnClickListener {
            if (videoView.isPlaying) videoView.pause() else videoView.start()
            showVpControlsTemporarily()
        }
        vpFillBtn.setOnClickListener {
            isVidFill = !isVidFill
            videoView.fillScreen = isVidFill
            vpFillBtn.text = if (isVidFill) "📐 Llenar 100%" else "📐 16:9"
            stylePillButton(vpFillBtn, isVidFill)
            showVpControlsTemporarily()
        }

        var allItems: List<QonectaMediaEngine.LocalMediaItem> = QonectaMediaEngine.BUILTIN_DEMO_VIDEOS
        var selectedAlbum = "Todos"

        fun renderGallery() {
            albumsRow.removeAllViews()
            val cats = LinkedHashSet<String>()
            cats.add("Todos")
            cats.add("USB Pendrive")
            cats.add("Películas / Vídeos")
            cats.add("Streams Demo HD")
            allItems.forEach { cats.add(it.albumCategory) }

            for (cat in cats) {
                val count = if (cat == "Todos") allItems.size else allItems.count { it.albumCategory == cat }
                val btn = pillButton(context, "$cat ($count)", selectedAlbum == cat, textSp = 15f, padHdp = 16) {
                    selectedAlbum = cat
                    renderGallery()
                }
                albumsRow.addView(btn, LinearLayout.LayoutParams(WRAP, dp(42)).apply { rightMargin = dp(8) })
            }

            gridContainer.removeAllViews()
            val filtered = if (selectedAlbum == "Todos") allItems else allItems.filter { it.albumCategory == selectedAlbum }
            if (filtered.isEmpty()) {
                gridContainer.addView(TextView(context).apply {
                    text = "📁 No se encontraron vídeos en «$selectedAlbum».\nInserta un pendrive USB con archivos .mp4 / .mkv o pulsa «Streams Demo HD»."
                    setTextSp(17.5f)
                    setTextColor(QonectaTheme.dim)
                    gravity = Gravity.CENTER
                    setPadding(dp(28), dp(48), dp(28), dp(48))
                })
                return
            }

            val cols = if (isSplitScreen) 2 else 3
            for (rowItems in filtered.chunked(cols)) {
                val rLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = cols.toFloat()
                }
                for (i in 0 until cols) {
                    if (i < rowItems.size) {
                        val item = rowItems[i]
                        val card = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(14), dp(12), dp(14), dp(12))
                            background = QonectaTheme.cardDrawable(dp(18).toFloat())
                            setOnClickListener { openVideoItemFullscreen(item) }
                        }
                        val iconBox = TextView(context).apply {
                            text = if (item.isVideo) "▶" else "🖼"
                            setTextSp(24f)
                            setTextColor(QonectaTheme.onAccent)
                            gravity = Gravity.CENTER
                            background = QonectaTheme.accentGradientPill(dp(16).toFloat())
                        }
                        card.addView(iconBox, LinearLayout.LayoutParams(dp(60), dp(60)))

                        val col = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(14), 0, dp(8), 0)
                        }
                        col.addView(TextView(context).apply {
                            text = item.title
                            setTextSp(18f)
                            setTextColor(QonectaTheme.text)
                            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                            setSingleLine(true)
                            ellipsize = TextUtils.TruncateAt.END
                        })
                        col.addView(TextView(context).apply {
                            text = item.subtitle + (if (item.srtUri != null) " · Subtítulos .SRT" else "")
                            setTextSp(13.5f)
                            setTextColor(QonectaTheme.dim)
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                            setPadding(0, dp(3), 0, 0)
                        })
                        card.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))

                        rLayout.addView(card, LinearLayout.LayoutParams(0, dp(94), 1f).apply {
                            if (i < cols - 1) rightMargin = dp(10)
                        })
                    } else {
                        rLayout.addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f))
                    }
                }
                gridContainer.addView(rLayout, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
            }
        }

        fun loadMediaItemsAsync() {
            bgExecutor.execute {
                val scanned = QonectaMediaEngine.scanLocalVideosAndPhotos(context)
                mainHandler.post {
                    allItems = scanned
                    renderGallery()
                }
            }
        }

        rescanUsbBtn.setOnClickListener { loadMediaItemsAsync() }
        openCustomUrlBtn.setOnClickListener {
            promptUrlDialog(
                context = context,
                title = "Abrir Vídeo o Stream Directo (.mp4 / .m3u8)",
                hint = "https://.../video.mp4",
                initial = ""
            ) { url ->
                if (url.isNotBlank()) {
                    openVideoItemFullscreen(
                        QonectaMediaEngine.LocalMediaItem(
                            title = url.substringAfterLast('/').ifBlank { "Stream Directo" },
                            subtitle = "Stream Directo · 100% Pantalla Completa",
                            uri = url,
                            isVideo = true
                        )
                    )
                }
            }
        }

        root.addView(browserLayout, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(playerStage, FrameLayout.LayoutParams(MATCH, MATCH))
        loadMediaItemsAsync()
        return root
    }

    // =========================================================================
    // 7. PESTAÑA JUEGOS RÁPIDOS QONECTA+ Y LEAPMOTOR C10 (Portado de GamesScreen.java + CarArt.java)
    // =========================================================================

    fun buildGamesView(context: Context, isSplitScreen: Boolean = false): View {
        QonectaTheme.ensureLoaded(context)
        val density = c10Density(context)
        fun dp(v: Int): Int = (v * density).roundToInt()

        val root = FrameLayout(context)

        val menuLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val headerCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = QonectaTheme.cardDrawable(dp(22).toFloat(), QonectaTheme.accent, dp(2))
        }
        val hCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        hCol.addView(TextView(context).apply {
            text = "🎮 Centro de Juegos y Espera de Carga · Leapmotor C10 (Portado de GamesScreen.java)"
            setTextSp(20f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        })
        hCol.addView(TextView(context).apply {
            text = "Juegos diseñados para las paradas de carga con el arte vectorial del Leapmotor C10 y modo 100% Pantalla Completa"
            setTextSp(14.5f)
            setTextColor(QonectaTheme.dim)
            setPadding(0, dp(3), 0, 0)
        })
        headerCard.addView(hCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        menuLayout.addView(headerCard, LinearLayout.LayoutParams(MATCH, WRAP))

        data class GameSpec(
            val id: Int,
            val icon: String,
            val title: String,
            val subtitle: String,
            val colorStart: Int,
            val colorEnd: Int
        )

        val gameSpecs = listOf(
            GameSpec(0, "🏎", "Autonomía C10 (WLTP)", "Conduce el C10 por 3 carriles, esquiva conos y recoge cargadores rápidos", 0xFF00B37E.toInt(), 0xFF062925.toInt()),
            GameSpec(1, "⚡", "Coche Volador C10", "Cruza entre postes de recarga rápida con el perfil del Leapmotor C10", 0xFF1E88E5.toInt(), 0xFF0D213B.toInt()),
            GameSpec(2, "🔢", "2048 Qonecta+", "Desliza en cualquier dirección para fusionar bloques hasta llegar al 2048", 0xFFF57C00.toInt(), 0xFF2D1808.toInt()),
            GameSpec(3, "🃏", "Parejas Memoria C10", "Encuentra las parejas ocultas con el frontal iluminado del Leapmotor C10", 0xFF8E24AA.toInt(), 0xFF230D2E.toInt()),
            GameSpec(4, "❌", "Tres en Raya (IA)", "Duelo táctil contra el algoritmo Minimax del computador de a bordo", 0xFFD81B60.toInt(), 0xFF2B0A18.toInt()),
            GameSpec(5, "🐠", "Acuario Relax 100%", "Acuario tropical interactivo a toda pantalla: toca el agua para alimentar a los peces", 0xFF00838F.toInt(), 0xFF06233B.toInt())
        )

        val activeGameStage = FrameLayout(context).apply {
            setBackgroundColor(QonectaTheme.bg)
            visibility = View.GONE
        }

        fun openGame(spec: GameSpec, fullScreen100: Boolean = true) {
            activeGameStage.removeAllViews()
            menuLayout.visibility = View.GONE
            activeGameStage.visibility = View.VISIBLE
            ExtendedHubView.setFullscreenFor(root, fullScreen100, showDefaultExitPill = false)

            val gameCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(QonectaTheme.bg)
            }
            val gameTopBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
                background = GradientDrawable().apply {
                    setColor(QonectaTheme.surfaceHi)
                }
            }
            val backBtn = pillButton(context, "‹ Todos los Juegos", false, textSp = 15.5f, padHdp = 16) {
                activeGameStage.removeAllViews()
                activeGameStage.visibility = View.GONE
                menuLayout.visibility = View.VISIBLE
                ExtendedHubView.setFullscreenFor(root, false)
            }
            val titleTv = TextView(context).apply {
                text = "${spec.icon} ${spec.title}"
                setTextSp(20f)
                setTextColor(QonectaTheme.text)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                setPadding(dp(16), 0, dp(16), 0)
            }
            val statusTv = TextView(context).apply {
                text = ""
                setTextSp(17.5f)
                setTextColor(QonectaTheme.accent)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            }
            var resetAction: (() -> Unit)? = null
            val againBtn = pillButton(
                context,
                if (spec.id == 5) "＋ Otro pez" else "🔄 Nueva Partida",
                true,
                textSp = 15.5f,
                padHdp = 16
            ) {
                resetAction?.invoke()
            }
            var isFull = fullScreen100
            val toggleFullBtn = pillButton(
                context,
                if (isFull) "🗖 Mostrar Pestañas" else "⛶ Toda la Pantalla",
                isFull,
                textSp = 15f,
                padHdp = 15
            ) {}
            toggleFullBtn.setOnClickListener {
                isFull = !isFull
                ExtendedHubView.setFullscreenFor(root, isFull, showDefaultExitPill = false)
                toggleFullBtn.text = if (isFull) "🗖 Mostrar Pestañas" else "⛶ Toda la Pantalla"
                stylePillButton(toggleFullBtn, isFull)
            }

            gameTopBar.addView(backBtn, LinearLayout.LayoutParams(WRAP, dp(44)))
            gameTopBar.addView(titleTv, LinearLayout.LayoutParams(WRAP, WRAP))
            gameTopBar.addView(statusTv, LinearLayout.LayoutParams(0, WRAP, 1f))
            gameTopBar.addView(againBtn, LinearLayout.LayoutParams(WRAP, dp(44)).apply { rightMargin = dp(8) })
            gameTopBar.addView(toggleFullBtn, LinearLayout.LayoutParams(WRAP, dp(44)))
            gameCol.addView(gameTopBar, LinearLayout.LayoutParams(MATCH, WRAP))

            val boardView: View = when (spec.id) {
                0 -> C10RangeHighwayGameView(context) { statusTv.text = it }.also { g -> resetAction = { g.resetGame() } }
                1 -> C10FlappyGameView(context) { statusTv.text = it }.also { g -> resetAction = { g.resetGame() } }
                2 -> C10Game2048View(context) { statusTv.text = it }.also { g -> resetAction = { g.resetGame() } }
                3 -> C10MemoryGameView(context) { statusTv.text = it }.also { g -> resetAction = { g.resetGame() } }
                4 -> C10TicTacToeView(context) { statusTv.text = it }.also { g -> resetAction = { g.resetGame() } }
                else -> QonectaAquariumView(context) { n -> statusTv.text = "$n peces · Toca el agua para darles de comer" }.also { aq ->
                    resetAction = { aq.addMoreFish() }
                }
            }
            gameCol.addView(boardView, LinearLayout.LayoutParams(MATCH, 0, 1f))
            activeGameStage.addView(gameCol, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        val cols = if (isSplitScreen) 2 else 3
        val rows = gameSpecs.chunked(cols)
        for (rowSpecs in rows) {
            val rLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = cols.toFloat()
            }
            for (i in 0 until cols) {
                val spec = rowSpecs[i]
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(22), dp(18), dp(22), dp(18))
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(spec.colorStart, spec.colorEnd)
                    ).apply {
                        cornerRadius = dp(24).toFloat()
                        setStroke(dp(2), QonectaTheme.outline)
                    }
                    setOnClickListener { openGame(spec, fullScreen100 = true) }
                }
                val topRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                topRow.addView(TextView(context).apply {
                    text = spec.icon
                    setTextSp(36f)
                })
                topRow.addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f))
                topRow.addView(TextView(context).apply {
                    text = "⛶ 100% PANTALLA"
                    setTextSp(12.5f)
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    setPadding(dp(12), dp(5), dp(12), dp(5))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(999).toFloat()
                        setColor(0x44000000)
                    }
                })
                card.addView(topRow, LinearLayout.LayoutParams(MATCH, WRAP))

                card.addView(TextView(context).apply {
                    text = spec.title
                    setTextSp(23f)
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    setPadding(0, dp(12), 0, dp(4))
                })
                card.addView(TextView(context).apply {
                    text = spec.subtitle
                    setTextSp(15f)
                    setTextColor(0xFFE0F2F1.toInt())
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(MATCH, 0, 1f))

                card.addView(TextView(context).apply {
                    text = "▶ Jugar a Toda la Pantalla ›"
                    setTextSp(16f)
                    setTextColor(0xFF071318.toInt())
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    setPadding(dp(18), dp(8), dp(18), dp(8))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(999).toFloat()
                        setColor(Color.WHITE)
                    }
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) })

                rLayout.addView(card, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                    if (i < cols - 1) rightMargin = dp(12)
                })
            }
            menuLayout.addView(rLayout, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(12) })
        }

        root.addView(menuLayout, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(activeGameStage, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    // =========================================================================
    // COMPONENTES VISUALES DE APOYO (Logos, Ecualizador en Vivo, Acuario Qonecta+)
    // =========================================================================

    private fun buildMonogramText(title: String, isLocalMusic: Boolean): String {
        if (isLocalMusic) return "♪"
        val clean = title.replace(Regex("[^A-Za-z0-9ÁÉÍÓÚÑáéíóúñ ]"), " ").trim()
        if (clean.isEmpty()) return "📻"
        val words = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            words.size >= 2 -> {
                val w0 = words[0]
                val w1 = words[1]
                if (w1.all { it.isDigit() } && w1.length <= 3) {
                    (w0.take(1) + w1).uppercase(Locale.ROOT)
                } else {
                    (w0.take(1) + w1.take(1)).uppercase(Locale.ROOT)
                }
            }
            clean.length >= 3 -> clean.take(3).uppercase(Locale.ROOT)
            else -> clean.uppercase(Locale.ROOT)
        }
    }

    private fun createMediaLogoBox(
        context: Context,
        title: String,
        logoUrl: String,
        isLocalMusic: Boolean,
        isPlaying: Boolean,
        sizeDp: Int
    ): View {
        val density = c10Density(context)
        fun dp(v: Int): Int = (v * density).roundToInt()

        val colorIdx = abs(title.hashCode()) % BADGE_PALETTE.size
        val baseColor = if (isPlaying) QonectaTheme.accentBg else BADGE_PALETTE[colorIdx]

        val frame = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(baseColor, QonectaTheme.surface)
            ).apply {
                cornerRadius = dp(14).toFloat()
                setStroke(dp(if (isPlaying) 2 else 1), if (isPlaying) QonectaTheme.accent else QonectaTheme.outline)
            }
            clipToOutline = true
        }
        val monogram = TextView(context).apply {
            text = buildMonogramText(title, isLocalMusic)
            setTextSp(if (sizeDp >= 58) 18f else 16f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val img = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        frame.addView(monogram, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))

        if (!isLocalMusic) {
            QonectaMediaEngine.loadLogoInto(
                imageView = img,
                url = logoUrl,
                title = title,
                onLoaded = { monogram.visibility = View.GONE },
                onFallback = {
                    monogram.visibility = View.VISIBLE
                    img.setImageDrawable(null)
                }
            )
        }
        return frame
    }

    /** Ecualizador animado de 5 barras para la cabecera de Radio y Karaoke cuando hay audio en curso. */
    private class LiveEqualizerView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val rect = RectF()
        private var playing = false

        fun setPlaying(active: Boolean) {
            playing = active
            visibility = if (active) VISIBLE else GONE
            if (active) invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!playing) return
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            paint.color = QonectaTheme.accent
            val bars = 5
            val gap = w * 0.08f
            val barW = (w - gap * (bars + 1)) / bars
            val now = SystemClock.uptimeMillis() / 140.0
            for (i in 0 until bars) {
                val wave = (sin(now + i * 1.15) * 0.5 + 0.5).toFloat()
                val bh = h * (0.25f + 0.68f * wave)
                val left = gap + i * (barW + gap)
                rect.set(left, h - bh, left + barW, h - 2f)
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
            }
            postInvalidateDelayed(65L)
        }
    }

    /**
     * Acuario interactivo portado fielmente de `AquariumView.java` en Qonecta+:
     * Peces de 6 especies tropicales, algas que se mecen, piedras y arena ondulada, burbujas
     * y escamas de comida al tocar el agua.
     */
    private class QonectaAquariumView(
        context: Context,
        private val onStatusChanged: (Int) -> Unit
    ) : View(context) {

        private class Fish(
            var kind: Int,
            var size: Float,
            var phase: Float,
            var speed: Float
        ) {
            var x = 0f
            var y = 0f
            var vx = 0f
            var vy = 0f
            var tx = 0f
            var ty = 0f
            var dir = 1f
            var wanderUntilMs = 0L
            var scaredUntilMs = 0L
        }

        private class Food(var x: Float, var y: Float, var drift: Float = 0f)
        private class Bubble(var x: Float, var y: Float, var r: Float, var speed: Float, var phase: Float)

        private val kinds = arrayOf(
            intArrayOf(0xFFFF7A1A.toInt(), 0xFFFF9A45.toInt(), 0xFFFFFFFF.toInt()), // Payaso
            intArrayOf(0xFF2E6BEB.toInt(), 0xFFFFD23F.toInt(), 0),                  // Cirujano azul
            intArrayOf(0xFFFFD23F.toInt(), 0xFFFFB300.toInt(), 0),                  // Cirujano amarillo
            intArrayOf(0xFF9C6ADE.toInt(), 0xFF6A3FB5.toInt(), 0),                  // Morado
            intArrayOf(0xFFE8505B.toInt(), 0xFFB83240.toInt(), 0xFFFFE0E0.toInt()), // Coral rayado
            intArrayOf(0xFF3FC7F0.toInt(), 0xFFE8505B.toInt(), 0)                   // Neón
        )

        private val rnd = Random()
        private val fishes = ArrayList<Fish>()
        private val foods = ArrayList<Food>()
        private val bubbles = ArrayList<Bubble>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val oval = RectF()
        private var weedX = FloatArray(0)
        private var weedH = FloatArray(0)
        private var weedPhase = FloatArray(0)
        private var waterGrad: LinearGradient? = null
        private var lastMs = 0L
        private var nextBubbleMs = 0L
        private var animTime = 0f

        init {
            clipToOutline = true
            background = GradientDrawable().apply {
                cornerRadius = 16f * c10Density(context)
            }
        }

        fun resetAquarium() {
            fishes.clear()
            foods.clear()
            bubbles.clear()
            if (width > 0 && height > 0) {
                for (i in 0 until 6) spawnFish(i % kinds.size)
            }
            onStatusChanged(fishes.size)
            invalidate()
        }

        fun addMoreFish() {
            if (fishes.size < 14 && width > 0 && height > 0) {
                spawnFish(rnd.nextInt(kinds.size))
                onStatusChanged(fishes.size)
                invalidate()
            }
        }

        private fun scaleFactor(): Float = max(0.55f, min(width.toFloat(), height * 1.8f) / 1100f)
        private fun swimTop(): Float = height * 0.12f
        private fun swimBottom(): Float = height * 0.78f
        private fun sandTop(x: Float): Float {
            val h = height.toFloat()
            return h * 0.86f + sin(x / 110f) * h * 0.02f
        }

        private fun spawnFish(kind: Int) {
            val sc = scaleFactor()
            val f = Fish(
                kind = kind,
                size = (64f + rnd.nextFloat() * 52f) * sc * (if (kind == 5) 0.75f else 1f),
                phase = rnd.nextFloat() * 6.28f,
                speed = (50f + rnd.nextFloat() * 48f) * sc
            )
            f.x = width * (0.15f + rnd.nextFloat() * 0.7f)
            f.y = swimTop() + rnd.nextFloat() * (swimBottom() - swimTop())
            f.dir = if (rnd.nextBoolean()) 1f else -1f
            f.vx = f.dir * f.speed
            pickTarget(f, SystemClock.uptimeMillis())
            fishes.add(f)
        }

        private fun pickTarget(f: Fish, now: Long) {
            f.tx = width * (0.08f + rnd.nextFloat() * 0.84f)
            f.ty = swimTop() + rnd.nextFloat() * (swimBottom() - swimTop())
            f.wanderUntilMs = now + 2800L + rnd.nextInt(4500)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w <= 0 || h <= 0) return
            waterGrad = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFF1A6B8A.toInt(), 0xFF0D4466.toInt(), 0xFF06233B.toInt()),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
            val n = max(5, w / 160)
            weedX = FloatArray(n)
            weedH = FloatArray(n)
            weedPhase = FloatArray(n)
            for (i in 0 until n) {
                weedX[i] = (i + 0.25f + rnd.nextFloat() * 0.5f) * w / n
                weedH[i] = h * (0.20f + rnd.nextFloat() * 0.26f)
                weedPhase[i] = rnd.nextFloat() * 6.28f
            }
            if (fishes.isEmpty()) {
                resetAquarium()
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) {
                val tx = event.x
                val ty = event.y
                val now = SystemClock.uptimeMillis()
                var scaredAny = false
                for (f in fishes) {
                    if (hypot(tx - f.x, ty - f.y) < f.size * 0.65f) {
                        f.scaredUntilMs = now + 1100L
                        f.dir = if (f.x < tx) -1f else 1f
                        f.tx = if (f.dir > 0) width * 0.92f else width * 0.08f
                        f.ty = (f.y + (rnd.nextFloat() - 0.5f) * height * 0.3f).coerceIn(swimTop(), swimBottom())
                        scaredAny = true
                    }
                }
                if (!scaredAny && foods.size < 35) {
                    for (i in 0 until 5) {
                        foods.add(
                            Food(
                                x = (tx + (rnd.nextFloat() - 0.5f) * 32f).coerceIn(12f, width - 12f),
                                y = ty.coerceIn(12f, sandTop(tx) - 10f),
                                drift = rnd.nextFloat() * 6.28f
                            )
                        )
                    }
                }
                invalidate()
                return true
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val now = SystemClock.uptimeMillis()
            val dt = if (lastMs == 0L) 0.016f else ((now - lastMs) / 1000f).coerceIn(0.005f, 0.06f)
            lastMs = now
            stepSimulation(dt, now)

            // 1. Agua
            paint.style = Paint.Style.FILL
            paint.shader = waterGrad
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // 2. Algas ondulantes
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 9f * scaleFactor()
            for (i in weedX.indices) {
                paint.color = if (i % 2 == 0) 0xFF1F8F5F.toInt() else 0xFF2AA870.toInt()
                val bx = weedX[i]
                val by = sandTop(bx) + 6f
                val wh = weedH[i]
                val sway = sin(animTime * 1.8f + weedPhase[i]) * 18f * scaleFactor()
                path.reset()
                path.moveTo(bx, by)
                path.quadTo(bx - sway * 0.5f, by - wh * 0.5f, bx + sway, by - wh)
                canvas.drawPath(path, paint)
            }

            // 3. Fondo de arena
            paint.style = Paint.Style.FILL
            paint.color = 0xFFD8B575.toInt()
            path.reset()
            path.moveTo(0f, h)
            var sx = 0f
            while (sx <= w) {
                path.lineTo(sx, sandTop(sx))
                sx += 24f
            }
            path.lineTo(w, sandTop(w))
            path.lineTo(w, h)
            path.close()
            canvas.drawPath(path, paint)

            // 4. Comida
            paint.color = 0xFFFFE082.toInt()
            val foodR = 4.2f * scaleFactor()
            for (fd in foods) {
                canvas.drawCircle(fd.x, fd.y, foodR, paint)
            }

            // 5. Burbujas
            for (b in bubbles) {
                val bx = b.x + sin(b.phase) * 5f
                paint.style = Paint.Style.FILL
                paint.color = 0x33FFFFFF
                canvas.drawCircle(bx, b.y, b.r, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                paint.color = 0x88FFFFFF.toInt()
                canvas.drawCircle(bx, b.y, b.r, paint)
            }

            // 6. Peces
            paint.style = Paint.Style.FILL
            for (f in fishes) {
                drawFish(canvas, f)
            }

            if (isAttachedToWindow) {
                postInvalidateDelayed(32L)
            }
        }

        private fun stepSimulation(dt: Float, now: Long) {
            animTime += dt
            val sc = scaleFactor()
            val foodIter = foods.iterator()
            while (foodIter.hasNext()) {
                val fd = foodIter.next()
                val floor = sandTop(fd.x) - 4f
                if (fd.y < floor) {
                    fd.y = min(floor, fd.y + 36f * sc * dt)
                    fd.x += sin(animTime * 2f + fd.drift) * 10f * dt
                } else {
                    fd.drift += dt
                    if (fd.drift > 15f) foodIter.remove()
                }
            }

            for (f in fishes) {
                var spd = f.speed
                var goal: Food? = null
                if (now < f.scaredUntilMs) {
                    spd *= 2.4f
                } else {
                    var bestD = Float.MAX_VALUE
                    for (fd in foods) {
                        val d = hypot(fd.x - f.x, fd.y - f.y)
                        if (d < bestD) {
                            bestD = d
                            goal = fd
                        }
                    }
                    if (goal != null) {
                        f.tx = goal.x
                        f.ty = min(goal.y, swimBottom())
                        spd *= 1.65f
                    } else if (now > f.wanderUntilMs || hypot(f.tx - f.x, f.ty - f.y) < f.size * 0.8f) {
                        pickTarget(f, now)
                    }
                }
                val dx = f.tx - f.x
                val dy = f.ty - f.y
                val d = max(1f, hypot(dx, dy))
                val wantVx = dx / d * spd
                val wantVy = dy / d * spd * 0.6f
                val k = min(1f, dt * 1.8f)
                f.vx += (wantVx - f.vx) * k
                f.vy += (wantVy - f.vy) * k
                f.x += f.vx * dt
                f.y = (f.y + f.vy * dt).coerceIn(swimTop(), swimBottom())
                if (f.vx > 10f) f.dir = 1f else if (f.vx < -10f) f.dir = -1f
                f.phase += dt * (5f + abs(f.vx) / max(1f, f.speed) * 5f)

                if (goal != null && hypot(goal.x - (f.x + f.dir * f.size * 0.42f), goal.y - f.y) < f.size * 0.35f) {
                    foods.remove(goal)
                    bubbles.add(Bubble(f.x + f.dir * f.size * 0.45f, f.y, 4f * sc, 45f * sc, rnd.nextFloat() * 6f))
                }
            }

            if (now >= nextBubbleMs && bubbles.size < 20) {
                nextBubbleMs = now + 450L + rnd.nextInt(800)
                val bx = width * (if (rnd.nextBoolean()) 0.2f else 0.8f) + rnd.nextFloat() * 16f
                bubbles.add(
                    Bubble(
                        x = bx,
                        y = sandTop(bx) - 6f,
                        r = (4f + rnd.nextFloat() * 7f) * sc,
                        speed = (36f + rnd.nextFloat() * 28f) * sc,
                        phase = rnd.nextFloat() * 6.28f
                    )
                )
            }
            val bIter = bubbles.iterator()
            while (bIter.hasNext()) {
                val b = bIter.next()
                b.y -= b.speed * dt
                b.phase += dt * 3f
                if (b.y < -b.r) bIter.remove()
            }
        }

        private fun drawFish(canvas: Canvas, f: Fish) {
            val colors = kinds[f.kind]
            val s = f.size
            val bh = s * 0.56f
            val wag = sin(f.phase) * s * 0.08f

            canvas.save()
            canvas.translate(f.x, f.y)
            canvas.scale(f.dir, 1f)

            // Cola
            paint.color = colors[1]
            path.reset()
            path.moveTo(-s * 0.36f, 0f)
            path.lineTo(-s * 0.74f, -bh * 0.55f + wag)
            path.lineTo(-s * 0.62f, wag * 0.4f)
            path.lineTo(-s * 0.74f, bh * 0.55f + wag)
            path.close()
            canvas.drawPath(path, paint)

            // Cuerpo
            paint.color = colors[0]
            oval.set(-s * 0.46f, -bh * 0.5f, s * 0.46f, bh * 0.5f)
            canvas.drawOval(oval, paint)

            // Rayas si es pez payaso / coral
            if (colors[2] != 0) {
                paint.color = colors[2]
                canvas.drawRoundRect(RectF(-s * 0.06f, -bh * 0.46f, s * 0.06f, bh * 0.46f), 6f, 6f, paint)
            }

            // Ojo
            paint.color = Color.WHITE
            canvas.drawCircle(s * 0.24f, -bh * 0.1f, s * 0.075f, paint)
            paint.color = Color.BLACK
            canvas.drawCircle(s * 0.26f, -bh * 0.1f, s * 0.04f, paint)

            canvas.restore()
        }
    }

    // =========================================================================
    // JUEGOS LEAPMOTOR C10 (Portados de GamesScreen.java + CarArt.java en Qonecta+)
    // =========================================================================

    private object C10CarArt {
        fun drawSideC10(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, tiltDeg: Float, paint: Paint) {
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(tiltDeg)
            paint.style = Paint.Style.FILL

            // Sombra inferior
            paint.color = 0x55000000
            canvas.drawOval(RectF(-w * 0.48f, h * 0.30f, w * 0.48f, h * 0.50f), paint)

            // Carrocería SUV Leapmotor C10 Glazed Green / Jade
            paint.color = 0xFF00C9A7.toInt()
            canvas.drawRoundRect(RectF(-w * 0.48f, -h * 0.12f, w * 0.48f, h * 0.30f), h * 0.18f, h * 0.18f, paint)

            // Techo panorámico oscuro
            val roof = Path().apply {
                moveTo(-w * 0.30f, -h * 0.10f)
                lineTo(-w * 0.16f, -h * 0.44f)
                lineTo(w * 0.18f, -h * 0.44f)
                lineTo(w * 0.34f, -h * 0.10f)
                close()
            }
            paint.color = 0xFF0B2228.toInt()
            canvas.drawPath(roof, paint)

            // Barra LED delantera y piloto trasero
            paint.color = 0xFFE0FFFF.toInt()
            canvas.drawRoundRect(RectF(w * 0.38f, -h * 0.05f, w * 0.49f, h * 0.06f), 4f, 4f, paint)
            paint.color = 0xFFFF3D57.toInt()
            canvas.drawRoundRect(RectF(-w * 0.49f, -h * 0.05f, -w * 0.40f, h * 0.06f), 4f, 4f, paint)

            // Ruedas 20" Trident
            val wr = h * 0.23f
            paint.color = 0xFF141D22.toInt()
            canvas.drawCircle(-w * 0.28f, h * 0.28f, wr, paint)
            canvas.drawCircle(w * 0.28f, h * 0.28f, wr, paint)
            paint.color = 0xFFB0BEC5.toInt()
            canvas.drawCircle(-w * 0.28f, h * 0.28f, wr * 0.52f, paint)
            canvas.drawCircle(w * 0.28f, h * 0.28f, wr * 0.52f, paint)
            canvas.restore()
        }

        fun drawTopC10(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, bodyColor: Int, isPlayer: Boolean, paint: Paint) {
            paint.style = Paint.Style.FILL
            paint.color = 0x55000000
            canvas.drawRoundRect(RectF(cx - w * 0.52f, cy - h * 0.48f + 6f, cx + w * 0.52f, cy + h * 0.52f + 6f), w * 0.28f, w * 0.28f, paint)

            paint.color = bodyColor
            canvas.drawRoundRect(RectF(cx - w * 0.48f, cy - h * 0.48f, cx + w * 0.48f, cy + h * 0.48f), w * 0.26f, w * 0.26f, paint)

            // Parabrisas y techo cristal
            paint.color = 0xFF0A1B22.toInt()
            canvas.drawRoundRect(RectF(cx - w * 0.38f, cy - h * 0.24f, cx + w * 0.38f, cy + h * 0.28f), w * 0.14f, w * 0.14f, paint)

            if (isPlayer) {
                // Luz diurna continua LED frontal C10
                paint.color = 0xFF9FFFE0.toInt()
                canvas.drawRoundRect(RectF(cx - w * 0.38f, cy - h * 0.47f, cx + w * 0.38f, cy - h * 0.41f), 4f, 4f, paint)
                paint.color = 0xFFFF3D57.toInt()
                canvas.drawRoundRect(RectF(cx - w * 0.38f, cy + h * 0.41f, cx + w * 0.38f, cy + h * 0.47f), 4f, 4f, paint)
            }
        }
    }

    /** 1. Autonomía C10 (Highway Runner de 3 carriles con batería kWh, supercargadores y tráfico). */
    private class C10RangeHighwayGameView(
        context: Context,
        private val onStatus: (String) -> Unit
    ) : View(context) {
        private class Item(var lane: Int, var y: Float, var kind: Int, var color: Int)
        private val items = ArrayList<Item>()
        private val rnd = Random()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val prefs = context.getSharedPreferences("qonecta_c10_games", Context.MODE_PRIVATE)

        private var lane = 1
        private var carX = 0f
        private var battery = 100f
        private var km = 0f
        private var bestKm = prefs.getInt("best_highway_km", 0)
        private var scroll = 0f
        private var spawnWait = 0f
        private var running = false
        private var gameOver = false
        private var lastMs = 0L
        private var downX = 0f

        init {
            resetGame()
        }

        fun resetGame() {
            items.clear()
            lane = 1
            carX = 0f
            battery = 100f
            km = 0f
            scroll = 0f
            spawnWait = 0.5f
            running = false
            gameOver = false
            lastMs = 0L
            onStatus("Toca un carril o desliza para esquivar · Recoge ⚡ para cargar")
            invalidate()
        }

        private fun laneCenterX(laneIdx: Int, w: Float): Float {
            val roadW = min(w * 0.62f, 820f)
            val roadL = (w - roadW) * 0.5f
            val lw = roadW / 3f
            return roadL + lw * (laneIdx + 0.5f)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) {
                downX = event.x
                if (gameOver) {
                    resetGame()
                    running = true
                    lastMs = SystemClock.uptimeMillis()
                    invalidate()
                    return true
                }
                if (!running) {
                    running = true
                    lastMs = SystemClock.uptimeMillis()
                }
                val w = width.toFloat()
                lane = when {
                    event.x < w * 0.38f -> max(0, lane - 1)
                    event.x > w * 0.62f -> min(2, lane + 1)
                    else -> lane
                }
                invalidate()
                return true
            } else if (event.action == MotionEvent.ACTION_UP) {
                val dx = event.x - downX
                if (abs(dx) > 50f) {
                    lane = (lane + if (dx > 0) 1 else -1).coerceIn(0, 2)
                    invalidate()
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val now = SystemClock.uptimeMillis()
            if (running && !gameOver) {
                val dt = if (lastMs == 0L) 0.016f else ((now - lastMs) / 1000f).coerceIn(0.005f, 0.05f)
                lastMs = now
                step(dt, w, h)
            }

            // Césped lateral y autopista
            paint.style = Paint.Style.FILL
            paint.color = 0xFF07161B.toInt()
            canvas.drawRect(0f, 0f, w, h, paint)

            val roadW = min(w * 0.62f, 820f)
            val roadL = (w - roadW) * 0.5f
            val roadR = roadL + roadW
            paint.color = 0xFF152229.toInt()
            canvas.drawRect(roadL, 0f, roadR, h, paint)

            // Bordes neón Qonecta+
            paint.color = QonectaTheme.accent
            canvas.drawRect(roadL - 5f, 0f, roadL, h, paint)
            canvas.drawRect(roadR, 0f, roadR + 5f, h, paint)

            // Líneas discontinuas de carril
            paint.color = 0x55FFFFFF
            val lw = roadW / 3f
            val dashH = 46f
            val gapH = 38f
            val period = dashH + gapH
            var yLine = (scroll % period) - period
            while (yLine < h) {
                canvas.drawRect(roadL + lw - 3f, yLine, roadL + lw + 3f, yLine + dashH, paint)
                canvas.drawRect(roadL + lw * 2f - 3f, yLine, roadL + lw * 2f + 3f, yLine + dashH, paint)
                yLine += period
            }

            // Coche jugador e ítems
            val carW = min(lw * 0.52f, 96f)
            val carH = carW * 1.75f
            val targetX = laneCenterX(lane, w)
            if (carX == 0f) carX = targetX
            carX += (targetX - carX) * 0.28f
            val carY = h - carH * 0.85f

            for (it in items) {
                val ix = laneCenterX(it.lane, w)
                if (it.kind == 0) {
                    // Supercargador ⚡
                    paint.color = 0xFF00E676.toInt()
                    canvas.drawCircle(ix, it.y, carW * 0.40f, paint)
                    paint.color = 0xFF051814.toInt()
                    paint.textAlign = Paint.Align.CENTER
                    paint.textSize = carW * 0.46f
                    canvas.drawText("⚡", ix, it.y + carW * 0.16f, paint)
                } else {
                    C10CarArt.drawTopC10(canvas, ix, it.y, carW * 0.92f, carH * 0.92f, it.color, false, paint)
                }
            }

            C10CarArt.drawTopC10(canvas, carX, carY, carW, carH, 0xFF00C9A7.toInt(), true, paint)

            // HUD Batería izquierda
            val hudPad = 24f
            paint.color = 0xCC0A181F.toInt()
            canvas.drawRoundRect(RectF(hudPad, hudPad, hudPad + 270f, hudPad + 110f), 18f, 18f, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 24f
            paint.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            canvas.drawText(String.format(Locale.US, "⚡ Batería %.0f%%", battery), hudPad + 18f, hudPad + 40f, paint)
            paint.color = if (battery > 25f) QonectaTheme.accent else 0xFFFF5252.toInt()
            canvas.drawRoundRect(
                RectF(hudPad + 18f, hudPad + 56f, hudPad + 18f + 234f * (battery / 100f).coerceIn(0f, 1f), hudPad + 86f),
                10f,
                10f,
                paint
            )

            if (!running || gameOver) {
                paint.color = 0xAA050E12.toInt()
                canvas.drawRoundRect(RectF(w * 0.24f, h * 0.34f, w * 0.76f, h * 0.64f), 26f, 26f, paint)
                paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 34f
                canvas.drawText(
                    if (gameOver) "Fin de Ruta · ${km.toInt()} km recorridos" else "Autonomía C10 · Toca para Arrancar",
                    w * 0.5f,
                    h * 0.47f,
                    paint
                )
                paint.color = QonectaTheme.accent
                paint.textSize = 23f
                canvas.drawText(
                    "Récord: $bestKm km · Toca izquierda/derecha para cambiar de carril",
                    w * 0.5f,
                    h * 0.56f,
                    paint
                )
            }

            if (running && !gameOver && isAttachedToWindow) {
                postInvalidateDelayed(16L)
            }
        }

        private fun step(dt: Float, w: Float, h: Float) {
            val speed = (360f + km * 6.5f).coerceAtMost(840f)
            scroll += speed * dt
            km += dt * (speed / 120f)
            battery = (battery - dt * 4.2f).coerceAtLeast(0f)

            spawnWait -= dt
            if (spawnWait <= 0f) {
                spawnWait = (0.85f - km * 0.004f).coerceIn(0.36f, 0.85f)
                val l = rnd.nextInt(3)
                val kind = if (rnd.nextFloat() < 0.32f) 0 else 1
                val colors = intArrayOf(0xFF455A64.toInt(), 0xFFD32F2F.toInt(), 0xFF1976D2.toInt(), 0xFFFBC02D.toInt())
                items.add(Item(l, -80f, kind, colors[rnd.nextInt(colors.size)]))
            }

            val roadW = min(w * 0.62f, 820f)
            val lw = roadW / 3f
            val carW = min(lw * 0.52f, 96f)
            val carH = carW * 1.75f
            val carY = h - carH * 0.85f

            val iter = items.iterator()
            while (iter.hasNext()) {
                val it = iter.next()
                it.y += (if (it.kind == 0) speed * 0.88f else speed * 0.72f) * dt
                if (it.lane == lane && abs(it.y - carY) < carH * 0.68f) {
                    if (it.kind == 0) {
                        battery = (battery + 22f).coerceAtMost(100f)
                        iter.remove()
                        continue
                    } else {
                        endGame("Colisión en autopista")
                        return
                    }
                }
                if (it.y > h + 120f) iter.remove()
            }

            if (battery <= 0f) {
                endGame("Batería agotada")
                return
            }

            onStatus(String.format(Locale.US, "Ruta: %.1f km  ·  ⚡ %.0f%%  ·  Récord: %d km", km, battery, bestKm))
        }

        private fun endGame(reason: String) {
            gameOver = true
            running = false
            val kInt = km.toInt()
            if (kInt > bestKm) {
                bestKm = kInt
                prefs.edit().putInt("best_highway_km", bestKm).apply()
            }
            onStatus("$reason · Recorrido: $kInt km (Récord: $bestKm km)")
        }
    }

    /** 2. Flappy C10 (C10 volador entre postes de recarga rápida). */
    private class C10FlappyGameView(
        context: Context,
        private val onStatus: (String) -> Unit
    ) : View(context) {
        private class Pipe(var x: Float, var gapY: Float, var passed: Boolean = false)
        private val pipes = ArrayList<Pipe>()
        private val rnd = Random()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val prefs = context.getSharedPreferences("qonecta_c10_games", Context.MODE_PRIVATE)

        private var birdY = 0f
        private var birdVy = 0f
        private var score = 0
        private var best = prefs.getInt("best_flappy_c10", 0)
        private var running = false
        private var gameOver = false
        private var lastMs = 0L

        init {
            resetGame()
        }

        fun resetGame() {
            pipes.clear()
            birdY = 0f
            birdVy = 0f
            score = 0
            running = false
            gameOver = false
            lastMs = 0L
            onStatus("Toca la pantalla para impulsar el C10 · Récord: $best")
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (gameOver) {
                    resetGame()
                }
                if (!running) {
                    running = true
                    lastMs = SystemClock.uptimeMillis()
                    if (birdY <= 0f) birdY = height * 0.45f
                }
                birdVy = -540f
                invalidate()
                return true
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            if (birdY <= 0f) birdY = h * 0.45f

            val now = SystemClock.uptimeMillis()
            if (running && !gameOver) {
                val dt = if (lastMs == 0L) 0.016f else ((now - lastMs) / 1000f).coerceIn(0.005f, 0.05f)
                lastMs = now
                step(dt, w, h)
            }

            paint.style = Paint.Style.FILL
            paint.color = 0xFF081820.toInt()
            canvas.drawRect(0f, 0f, w, h, paint)

            val gapH = h * 0.34f
            val pipeW = 86f
            for (p in pipes) {
                val topBottom = p.gapY - gapH * 0.5f
                val botTop = p.gapY + gapH * 0.5f
                paint.color = 0xFF00A88F.toInt()
                canvas.drawRoundRect(RectF(p.x, -12f, p.x + pipeW, topBottom), 14f, 14f, paint)
                canvas.drawRoundRect(RectF(p.x, botTop, p.x + pipeW, h + 12f), 14f, 14f, paint)
            }

            val birdX = w * 0.26f
            val tilt = (birdVy / 18f).coerceIn(-25f, 32f)
            C10CarArt.drawSideC10(canvas, birdX, birdY, 126f, 64f, tilt, paint)

            if (!running || gameOver) {
                paint.color = 0xAA050E12.toInt()
                canvas.drawRoundRect(RectF(w * 0.25f, h * 0.35f, w * 0.75f, h * 0.63f), 24f, 24f, paint)
                paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 34f
                paint.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                canvas.drawText(
                    if (gameOver) "Puntos: $score  ·  Récord: $best" else "Flappy C10 · Toca para Volar",
                    w * 0.5f,
                    h * 0.48f,
                    paint
                )
                paint.color = QonectaTheme.accent
                paint.textSize = 22f
                canvas.drawText("Toca repetidamente para mantener el C10 en el aire", w * 0.5f, h * 0.56f, paint)
            }

            if (running && !gameOver && isAttachedToWindow) {
                postInvalidateDelayed(16L)
            }
        }

        private fun step(dt: Float, w: Float, h: Float) {
            birdVy += 1280f * dt
            birdY += birdVy * dt

            if (pipes.isEmpty() || w - pipes.last().x > 390f) {
                val margin = h * 0.24f
                val gy = margin + rnd.nextFloat() * (h - margin * 2f)
                pipes.add(Pipe(w + 40f, gy))
            }

            val birdX = w * 0.26f
            val gapH = h * 0.34f
            val pipeW = 86f
            val iter = pipes.iterator()
            while (iter.hasNext()) {
                val p = iter.next()
                p.x -= 290f * dt
                if (!p.passed && p.x + pipeW < birdX) {
                    p.passed = true
                    score++
                    if (score > best) {
                        best = score
                        prefs.edit().putInt("best_flappy_c10", best).apply()
                    }
                    onStatus("Puntos: $score  ·  Récord: $best")
                }
                if (birdX + 48f > p.x && birdX - 48f < p.x + pipeW) {
                    if (birdY - 24f < p.gapY - gapH * 0.5f || birdY + 24f > p.gapY + gapH * 0.5f) {
                        gameOver = true
                        running = false
                        onStatus("Fin · Puntos: $score (Récord: $best)")
                        return
                    }
                }
                if (p.x + pipeW < -40f) iter.remove()
            }

            if (birdY < 20f || birdY > h - 20f) {
                gameOver = true
                running = false
                onStatus("Fin · Puntos: $score (Récord: $best)")
            }
        }
    }

    /** 3. 2048 C10 (Tablero 4x4 deslizable). */
    private class C10Game2048View(
        context: Context,
        private val onStatus: (String) -> Unit
    ) : View(context) {
        private val grid = Array(4) { IntArray(4) }
        private val rnd = Random()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val prefs = context.getSharedPreferences("qonecta_c10_games", Context.MODE_PRIVATE)
        private var score = 0
        private var best = prefs.getInt("best_2048_c10", 0)
        private var downX = 0f
        private var downY = 0f

        init {
            resetGame()
        }

        fun resetGame() {
            for (r in 0..3) for (c in 0..3) grid[r][c] = 0
            score = 0
            spawnTile()
            spawnTile()
            onStatus("Puntos: $score  ·  Récord: $best  ·  Desliza en 4 direcciones")
            invalidate()
        }

        private fun spawnTile() {
            val empty = ArrayList<Pair<Int, Int>>()
            for (r in 0..3) for (c in 0..3) if (grid[r][c] == 0) empty.add(r to c)
            if (empty.isNotEmpty()) {
                val (r, c) = empty[rnd.nextInt(empty.size)]
                grid[r][c] = if (rnd.nextFloat() < 0.88f) 2 else 4
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (hypot(dx, dy) > 35f) {
                        val moved = if (abs(dx) > abs(dy)) {
                            if (dx > 0) slideRight() else slideLeft()
                        } else {
                            if (dy > 0) slideDown() else slideUp()
                        }
                        if (moved) {
                            spawnTile()
                            if (score > best) {
                                best = score
                                prefs.edit().putInt("best_2048_c10", best).apply()
                            }
                            onStatus("Puntos: $score  ·  Récord: $best")
                            invalidate()
                        }
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun compressAndMerge(line: IntArray): Pair<IntArray, Boolean> {
            val nonZero = line.filter { it != 0 }.toMutableList()
            val out = IntArray(4)
            var idx = 0
            var i = 0
            while (i < nonZero.size) {
                if (i + 1 < nonZero.size && nonZero[i] == nonZero[i + 1]) {
                    val v = nonZero[i] * 2
                    out[idx++] = v
                    score += v
                    i += 2
                } else {
                    out[idx++] = nonZero[i]
                    i++
                }
            }
            val changed = !line.contentEquals(out)
            return out to changed
        }

        private fun slideLeft(): Boolean {
            var any = false
            for (r in 0..3) {
                val (res, ch) = compressAndMerge(grid[r])
                grid[r] = res
                if (ch) any = true
            }
            return any
        }

        private fun slideRight(): Boolean {
            var any = false
            for (r in 0..3) {
                val rev = grid[r].reversedArray()
                val (res, ch) = compressAndMerge(rev)
                grid[r] = res.reversedArray()
                if (ch) any = true
            }
            return any
        }

        private fun slideUp(): Boolean {
            var any = false
            for (c in 0..3) {
                val col = IntArray(4) { r -> grid[r][c] }
                val (res, ch) = compressAndMerge(col)
                for (r in 0..3) grid[r][c] = res[r]
                if (ch) any = true
            }
            return any
        }

        private fun slideDown(): Boolean {
            var any = false
            for (c in 0..3) {
                val col = IntArray(4) { r -> grid[3 - r][c] }
                val (res, ch) = compressAndMerge(col)
                for (r in 0..3) grid[3 - r][c] = res[r]
                if (ch) any = true
            }
            return any
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            paint.style = Paint.Style.FILL
            paint.color = 0xFF071318.toInt()
            canvas.drawRect(0f, 0f, w, h, paint)

            val boardSize = min(w, h) * 0.88f
            val left = (w - boardSize) * 0.5f
            val top = (h - boardSize) * 0.5f
            paint.color = 0xFF10242D.toInt()
            canvas.drawRoundRect(RectF(left, top, left + boardSize, top + boardSize), 24f, 24f, paint)

            val gap = boardSize * 0.03f
            val cell = (boardSize - gap * 5f) / 4f
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)

            for (r in 0..3) {
                for (c in 0..3) {
                    val v = grid[r][c]
                    val cx = left + gap + c * (cell + gap)
                    val cy = top + gap + r * (cell + gap)
                    paint.color = when (v) {
                        0 -> 0xFF1A333E.toInt()
                        2 -> 0xFF214E59.toInt()
                        4 -> 0xFF1D6A73.toInt()
                        8 -> 0xFF00897B.toInt()
                        16 -> 0xFF00ACC1.toInt()
                        32 -> 0xFF00C9A7.toInt()
                        64 -> 0xFF26A69A.toInt()
                        128 -> 0xFFFFB300.toInt()
                        256 -> 0xFFFF8F00.toInt()
                        512 -> 0xFFF4511E.toInt()
                        else -> 0xFFD81B60.toInt()
                    }
                    canvas.drawRoundRect(RectF(cx, cy, cx + cell, cy + cell), 16f, 16f, paint)
                    if (v > 0) {
                        paint.color = Color.WHITE
                        paint.textSize = if (v >= 128) cell * 0.34f else cell * 0.42f
                        canvas.drawText(v.toString(), cx + cell * 0.5f, cy + cell * 0.64f, paint)
                    }
                }
            }
        }
    }

    /** 4. Memoria C10 (16 cartas / 8 parejas de iconos EV). */
    private class C10MemoryGameView(
        context: Context,
        private val onStatus: (String) -> Unit
    ) : FrameLayout(context) {
        private val symbols = arrayOf("⚡", "🔋", "🚙", "🛰️", "🎵", "📺", "🌈", "🏁")
        private val cards = ArrayList<String>()
        private val revealed = BooleanArray(16)
        private val matched = BooleanArray(16)
        private val buttons = ArrayList<TextView>()
        private var firstIdx = -1
        private var secondIdx = -1
        private var moves = 0
        private var lockInput = false

        init {
            val density = c10Density(context)
            fun dp(v: Int): Int = (v * density).roundToInt()
            val gridLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(14), dp(24), dp(14))
            }
            for (r in 0 until 4) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = 4f
                }
                for (c in 0 until 4) {
                    val idx = r * 4 + c
                    val btn = TextView(context).apply {
                        gravity = Gravity.CENTER
                        setTextSp(32f)
                        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                        setOnClickListener { onCardTap(idx) }
                    }
                    buttons.add(btn)
                    row.addView(btn, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                        setMargins(dp(6), dp(6), dp(6), dp(6))
                    })
                }
                gridLayout.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
            addView(gridLayout, LayoutParams(MATCH, MATCH))
            resetGame()
        }

        fun resetGame() {
            cards.clear()
            for (s in symbols) {
                cards.add(s)
                cards.add(s)
            }
            cards.shuffle()
            revealed.fill(false)
            matched.fill(false)
            firstIdx = -1
            secondIdx = -1
            moves = 0
            lockInput = false
            refreshCards()
            onStatus("Parejas: 0/8  ·  Movimientos: 0")
        }

        private fun onCardTap(idx: Int) {
            if (lockInput || revealed[idx] || matched[idx]) return
            revealed[idx] = true
            refreshCards()
            if (firstIdx == -1) {
                firstIdx = idx
            } else {
                secondIdx = idx
                moves++
                val a = firstIdx
                val b = secondIdx
                if (cards[a] == cards[b]) {
                    matched[a] = true
                    matched[b] = true
                    firstIdx = -1
                    secondIdx = -1
                    val pairs = matched.count { it } / 2
                    onStatus(
                        if (pairs == 8) "🎉 ¡Completado en $moves movimientos!"
                        else "Parejas: $pairs/8  ·  Movimientos: $moves"
                    )
                    refreshCards()
                } else {
                    lockInput = true
                    onStatus("Parejas: ${matched.count { it } / 2}/8  ·  Movimientos: $moves")
                    postDelayed({
                        revealed[a] = false
                        revealed[b] = false
                        firstIdx = -1
                        secondIdx = -1
                        lockInput = false
                        refreshCards()
                    }, 650L)
                }
            }
        }

        private fun refreshCards() {
            val density = c10Density(context)
            for (i in 0 until 16) {
                val tv = buttons[i]
                val open = revealed[i] || matched[i]
                tv.text = if (open) cards[i] else "C10"
                tv.setTextColor(if (open) Color.WHITE else QonectaTheme.accent)
                tv.background = GradientDrawable().apply {
                    cornerRadius = 18f * density
                    setColor(
                        when {
                            matched[i] -> 0xFF005B4F.toInt()
                            revealed[i] -> 0xFF1A3A46.toInt()
                            else -> 0xFF10222B.toInt()
                        }
                    )
                    setStroke((2 * density).roundToInt(), if (open) QonectaTheme.accent else QonectaTheme.outline)
                }
            }
        }
    }

    /** 5. Tres en Raya C10 (1 o 2 jugadores en pantalla). */
    private class C10TicTacToeView(
        context: Context,
        private val onStatus: (String) -> Unit
    ) : FrameLayout(context) {
        private val board = IntArray(9)
        private val cells = ArrayList<TextView>()
        private var turn = 1
        private var finished = false

        init {
            val density = c10Density(context)
            fun dp(v: Int): Int = (v * density).roundToInt()
            val grid = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(80), dp(16), dp(80), dp(16))
            }
            for (r in 0..2) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = 3f
                }
                for (c in 0..2) {
                    val idx = r * 3 + c
                    val cell = TextView(context).apply {
                        gravity = Gravity.CENTER
                        setTextSp(42f)
                        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                        setOnClickListener { playCell(idx) }
                    }
                    cells.add(cell)
                    row.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                        setMargins(dp(8), dp(8), dp(8), dp(8))
                    })
                }
                grid.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
            addView(grid, LayoutParams(MATCH, MATCH))
            resetGame()
        }

        fun resetGame() {
            board.fill(0)
            turn = 1
            finished = false
            refresh()
            onStatus("Tu turno (⚡) contra IA C10 (🔋)")
        }

        private fun playCell(idx: Int) {
            if (finished || board[idx] != 0) return
            board[idx] = 1
            refresh()
            if (checkEnd()) return
            turn = 2
            onStatus("Pensando C10 (🔋)...")
            postDelayed({
                if (!finished) {
                    val aiMove = pickAiMove()
                    if (aiMove != -1) board[aiMove] = 2
                    refresh()
                    if (!checkEnd()) {
                        turn = 1
                        onStatus("Tu turno (⚡)")
                    }
                }
            }, 260L)
        }

        private fun pickAiMove(): Int {
            val wins = arrayOf(
                intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
                intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
                intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
            )
            // Ganar o bloquear
            for (target in intArrayOf(2, 1)) {
                for (w in wins) {
                    val cTarget = w.count { board[it] == target }
                    val cEmpty = w.count { board[it] == 0 }
                    if (cTarget == 2 && cEmpty == 1) return w.first { board[it] == 0 }
                }
            }
            if (board[4] == 0) return 4
            val empty = (0..8).filter { board[it] == 0 }
            return if (empty.isEmpty()) -1 else empty.random()
        }

        private fun checkEnd(): Boolean {
            val wins = arrayOf(
                intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
                intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
                intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
            )
            for (w in wins) {
                val a = board[w[0]]
                if (a != 0 && a == board[w[1]] && a == board[w[2]]) {
                    finished = true
                    onStatus(if (a == 1) "🏆 ¡Has ganado (⚡)!" else "🤖 Gana IA Leapmotor C10 (🔋)")
                    return true
                }
            }
            if (board.none { it == 0 }) {
                finished = true
                onStatus("🤝 Empate · Pulsa Nueva Partida")
                return true
            }
            return false
        }

        private fun refresh() {
            val density = c10Density(context)
            for (i in 0..8) {
                val tv = cells[i]
                tv.text = when (board[i]) {
                    1 -> "⚡"
                    2 -> "🔋"
                    else -> ""
                }
                tv.background = GradientDrawable().apply {
                    cornerRadius = 20f * density
                    setColor(0xFF10232C.toInt())
                    setStroke((2 * density).roundToInt(), if (board[i] != 0) QonectaTheme.accent else QonectaTheme.outline)
                }
            }
        }
    }

    // =========================================================================
    // HELPERS UI DE ALTA VISIBILIDAD
    // =========================================================================

    private fun stylePillButton(tv: TextView, active: Boolean) {
        val density = c10Density(tv.context)
        val r = 24f * density
        tv.setTextColor(if (active) QonectaTheme.onAccent else QonectaTheme.text)
        val baseBg = if (active) {
            QonectaTheme.accentGradientPill(r)
        } else {
            QonectaTheme.chipDrawable(r, QonectaTheme.outline)
        }
        tv.background = RippleDrawable(ColorStateList.valueOf(0x3300E5C7), baseBg, null)
    }

    private fun pillButton(
        context: Context,
        label: String,
        active: Boolean,
        textSp: Float = 15.5f,
        padHdp: Int = 16,
        onClick: () -> Unit
    ): TextView {
        val density = c10Density(context)
        return TextView(context).apply {
            text = label
            setTextSp(textSp)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
            val padH = (padHdp * density).roundToInt()
            val padV = (8 * density).roundToInt()
            setPadding(padH, padV, padH, padV)
            stylePillButton(this, active)
            setOnClickListener { onClick() }
        }
    }

    private fun promptUrlDialog(
        context: Context,
        title: String,
        hint: String,
        initial: String,
        onSave: (String) -> Unit
    ) {
        val density = c10Density(context)
        val pad = (20 * density).roundToInt()
        val input = EditText(context).apply {
            setText(initial)
            this.hint = hint
            setSingleLine(true)
            setTextSp(16f)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                onSave(input.text?.toString()?.trim().orEmpty())
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
