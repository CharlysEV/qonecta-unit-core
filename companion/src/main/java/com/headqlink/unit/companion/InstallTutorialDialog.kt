package com.headqlink.unit.companion

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/**
 * Modo Tutorial interactivo y sincronizado en tiempo real para instalar y vincular
 * `Qonecta Unit` en el Leapmotor C10 paso a paso desde cero.
 *
 * Detecta en vivo:
 *  - Paso 1: Si el APK de `Qonecta Unit` ya está descargado en el móvil (o lo descarga en 1 toque).
 *  - Paso 2: Confirmación automática al guardar el APK en el Pendrive USB-C.
 *  - Paso 3: Detección en tiempo real del Hotspot Wi-Fi encendido + servidor local `:8788`.
 *  - Paso 4: Radar en vivo que avisa en el milisegundo exacto en que Vivaldi del C10 abre `/portal`
 *            + esquema visual del botón de 2 flechas `⤢` en `Archivos`.
 *  - Paso 5: Emparejamiento Bluetooth/PIN integrado con celebración instantánea al conectar con `Qonecta Unit`
 *            y lectura en vivo de las casillas activas en la pantalla del coche (`unitConn`).
 */
class InstallTutorialDialog(
    private val activity: Activity,
    private val prefs: CompanionPrefs,
    private val onExportUsb: (File) -> Unit,
    private val onOpenHotspot: () -> Unit,
    private val onPickBluetooth: () -> Unit,
    private val onAssociateBluetooth: () -> Unit,
) : Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar) {

    companion object {
        private const val BG = 0xFF0A0E14.toInt()
        private const val SURFACE = 0xFF111821.toInt()
        private const val SURFACE_TOP = 0xFF1E2935.toInt()
        private const val OUTLINE = 0xFF243140.toInt()
        private const val TEXT = 0xFFEAF2F7.toInt()
        private const val DIM = 0xFFA3B3C2.toInt()
        private const val FAINT = 0xFF8296A9.toInt()
        private const val ACCENT = 0xFF00E5C7.toInt()
        private const val ON_ACCENT = 0xFF00211C.toInt()
        private const val OK = 0xFF4CFF9F.toInt()
        private const val OK_BG = 0xFF0D2B1E.toInt()
        private const val WARN = 0xFFFFB547.toInt()
        private const val WARN_BG = 0xFF2B2110.toInt()
        private const val BAD = 0xFFFF6B7A.toInt()
        private const val TOTAL_STEPS = 5
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentStep: Int = prefs.tutorialStep.coerceIn(0, TOTAL_STEPS - 1)
    private var draftPin: String = ""
    private var downloadingInStep1: Boolean = false
    private var step1DownloadMsg: String = ""

    private lateinit var stepHeaderRow: LinearLayout
    private lateinit var contentContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var bottomNavRow: LinearLayout

    // Referencias para refresco ligero en vivo sin perder el foco del teclado en Paso 5
    private var liveHotspotStatusTv: TextView? = null
    private var livePortalRadarBox: LinearLayout? = null
    private var livePortalRadarTv: TextView? = null
    private var livePortalUrlTv: TextView? = null
    private var liveStep5SyncBox: LinearLayout? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isShowing) return
            updateLiveIndicatorsOnly()
            handler.postDelayed(this, 1_500L)
        }
    }

    private val portalListener: () -> Unit = {
        if (isShowing) {
            updateLiveIndicatorsOnly()
            renderStepHeader()
        }
    }

    private val bridgeListener: (BridgeState.Snapshot) -> Unit = { snap ->
        if (isShowing) {
            if (currentStep == 4 && snap.phase == BridgeState.Phase.CONNECTED) {
                renderStep()
            } else {
                updateLiveIndicatorsOnly()
                renderStepHeader()
            }
        }
    }

    private val telemetryListener: (UnitTelemetry.Data?) -> Unit = {
        if (isShowing) {
            updateLiveIndicatorsOnly()
        }
    }

    init {
        window?.apply {
            statusBarColor = BG
            navigationBarColor = SURFACE
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // Barra superior fija con título y botón Cerrar
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(SURFACE)
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }
        topBar.addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("🎓 Modo Tutorial · Leapmotor C10", 18f, TEXT, bold = true))
                addView(label("Instalación guiada y sincronizada en vivo", 12.5f, ACCENT))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val closeBtn = pill("✕ Cerrar", primary = false) { dismiss() }.apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        topBar.addView(closeBtn)
        outer.addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Selector visual de pasos 1..5
        stepHeaderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = TOTAL_STEPS.toFloat()
            setBackgroundColor(SURFACE)
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        outer.addView(stepHeaderRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Cuerpo desplazable
        scrollView = ScrollView(activity).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
        contentContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        scrollView.addView(contentContainer)
        outer.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Barra inferior de navegación Anterior / Siguiente
        bottomNavRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(SURFACE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        outer.addView(bottomNavRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        outer.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, 0)
            @Suppress("DEPRECATION")
            bottomNavRow.setPadding(dp(14), dp(10), dp(14), dp(10) + insets.systemWindowInsetBottom)
            insets
        }

        setContentView(outer)

        setOnShowListener {
            ensureHostingStarted()
            GitHubReleaseUpdater.addPortalListener(portalListener)
            BridgeState.addListener(bridgeListener)
            UnitTelemetry.addListener(telemetryListener)
            handler.post(pollRunnable)
            renderStep()
        }

        setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            GitHubReleaseUpdater.removePortalListener(portalListener)
            BridgeState.removeListener(bridgeListener)
            UnitTelemetry.removeListener(telemetryListener)
        }
    }

    /** Llamado desde `MainActivity` cuando vuelve del selector USB o Bluetooth. */
    fun refresh() {
        if (!isShowing) return
        ensureHostingStarted()
        renderStep()
    }

    private fun ensureHostingStarted() {
        val apk = GitHubReleaseUpdater.findBestLocalCarApk(activity)
        if (apk != null && !GitHubReleaseUpdater.isHosting) {
            GitHubReleaseUpdater.startHostingApkForCar(activity, apk) {}
        }
    }

    private fun isStepDone(stepIdx: Int): Boolean {
        return when (stepIdx) {
            0 -> GitHubReleaseUpdater.findBestLocalCarApk(activity) != null
            1 -> prefs.usbExportDoneAtMs > 0L
            2 -> GitHubReleaseUpdater.findDedicatedHotspotIp() != null && GitHubReleaseUpdater.isHosting
            3 -> GitHubReleaseUpdater.portalOpenedByCarAtMs > 0L
            4 -> BridgeState.current.phase == BridgeState.Phase.CONNECTED
            else -> false
        }
    }

    private fun setStep(newStep: Int) {
        currentStep = newStep.coerceIn(0, TOTAL_STEPS - 1)
        prefs.tutorialStep = currentStep
        renderStep()
        scrollView.post { scrollView.scrollTo(0, 0) }
    }

    private fun renderStepHeader() {
        stepHeaderRow.removeAllViews()
        val titles = listOf("1. APK", "2. USB", "3. Hotspot", "4. Portal", "5. Vincular")
        for (i in 0 until TOTAL_STEPS) {
            val selected = i == currentStep
            val done = isStepDone(i)
            val badgeText = when {
                done && !selected -> "✓ ${titles[i]}"
                else -> titles[i]
            }
            val chip = TextView(activity).apply {
                text = badgeText
                gravity = Gravity.CENTER
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TypedValue.COMPLEX_UNIT_SP)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(
                    when {
                        selected -> ON_ACCENT
                        done -> OK
                        else -> DIM
                    }
                )
                background = when {
                    selected -> rounded(ACCENT, dp(14))
                    done -> rounded(OK_BG, dp(14), OK)
                    else -> rounded(SURFACE_TOP, dp(14), OUTLINE)
                }
                setPadding(dp(4), dp(8), dp(4), dp(8))
                isClickable = true
                setOnClickListener { setStep(i) }
            }
            stepHeaderRow.addView(
                chip,
                LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    if (i > 0) leftMargin = dp(5)
                },
            )
        }
    }

    private fun renderBottomNav() {
        bottomNavRow.removeAllViews()
        if (currentStep > 0) {
            bottomNavRow.addView(
                pill("◀ Anterior", primary = false) { setStep(currentStep - 1) },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) },
            )
        }
        val isLast = currentStep == TOTAL_STEPS - 1
        val nextLabel = if (isLast) "✅ Finalizar Tutorial" else "Siguiente paso ▶"
        bottomNavRow.addView(
            pill(nextLabel, primary = true) {
                if (isLast) dismiss() else setStep(currentStep + 1)
            },
            LinearLayout.LayoutParams(0, dp(48), if (currentStep > 0) 1.3f else 1f),
        )
    }

    private fun renderStep() {
        liveHotspotStatusTv = null
        livePortalRadarBox = null
        livePortalRadarTv = null
        livePortalUrlTv = null
        liveStep5SyncBox = null

        renderStepHeader()
        renderBottomNav()
        contentContainer.removeAllViews()

        when (currentStep) {
            0 -> buildStep1PrepareApk()
            1 -> buildStep2UsbDrive()
            2 -> buildStep3Hotspot()
            3 -> buildStep4VivaldiAndArrows()
            4 -> buildStep5PairAndSync()
        }
    }

    // ------------------------------------------------------------------------------------------
    // PASO 1 · VIVALDI EN EL C10 + APK EN EL MÓVIL
    // ------------------------------------------------------------------------------------------
    private fun buildStep1PrepareApk() {
        val card1 = card()
        card1.addView(label("Paso 1 de 5 · Preparar Vivaldi y la app del coche", 19f, ACCENT, bold = true))
        card1.addView(
            label(
                "Solo necesitas hacer este tutorial la primera vez. Cuando Qonecta Unit ya esté instalada en tu C10, ¡las siguientes actualizaciones se envían en 1 toque sin pendrive!",
                13.5f,
                DIM,
            ).apply { setPadding(0, dp(6), 0, dp(12)) }
        )

        // Bloque A: Vivaldi en el coche
        val vivaldiBox = infoBox(SURFACE_TOP, OUTLINE)
        vivaldiBox.addView(label("🅰️ En la pantalla de tu Leapmotor C10:", 15f, TEXT, bold = true))
        vivaldiBox.addView(
            label(
                "Abre la tienda oficial «LeapAppStore» del coche y descarga el navegador gratuito **Vivaldi** (si ya lo tienes instalado, perfecto).",
                13.5f,
                DIM,
            ).apply { setPadding(0, dp(4), 0, 0) }
        )
        card1.addView(vivaldiBox)

        // Bloque B: Detección automática del APK en el móvil
        val localApk = GitHubReleaseUpdater.findBestLocalCarApk(activity)
        val apkBox = infoBox(if (localApk != null) OK_BG else WARN_BG, if (localApk != null) OK else WARN).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        }
        if (localApk != null) {
            val (verName, _) = GitHubReleaseUpdater.extractApkVersionMeta(activity, localApk)
            val sizeMb = String.format(Locale.US, "%.1f MB", localApk.length() / (1024.0 * 1024.0))
            apkBox.addView(label("🟢 ¡Detectado! APK lista en tu móvil", 15.5f, OK, bold = true))
            apkBox.addView(
                label("Archivo preparado: ${localApk.name} (v$verName · $sizeMb)", 13.5f, TEXT).apply {
                    setPadding(0, dp(4), 0, 0)
                }
            )
        } else {
            apkBox.addView(label("🟡 Falta descargar Qonecta Unit en tu móvil", 15.5f, WARN, bold = true))
            apkBox.addView(
                label("Pulsa el botón de abajo para descargar automáticamente la última versión oficial.", 13.5f, TEXT).apply {
                    setPadding(0, dp(4), 0, 0)
                }
            )
        }
        card1.addView(apkBox)

        val dlStatusTv = label(step1DownloadMsg, 13.5f, ACCENT, bold = true).apply {
            visibility = if (step1DownloadMsg.isNotBlank()) View.VISIBLE else View.GONE
            setPadding(0, dp(10), 0, dp(4))
        }
        card1.addView(dlStatusTv)

        val dlLabel = if (localApk != null) "☁️ Comprobar si hay versión más nueva en Internet" else "☁️ Descargar Qonecta Unit ahora"
        card1.addView(
            pill(if (downloadingInStep1) "⏳ Descargando…" else dlLabel, primary = localApk == null) {
                if (downloadingInStep1) return@pill
                downloadingInStep1 = true
                step1DownloadMsg = "⏳ Buscando la última versión en GitHub…"
                renderStep()
                GitHubReleaseUpdater.fetchDualReleases(
                    unitRepo = prefs.githubRepo,
                    companionRepo = prefs.githubCompanionRepo,
                    githubToken = prefs.githubToken,
                ) { _, assets ->
                    val newestCar = assets.firstOrNull { it.isCarApk }
                    if (newestCar == null) {
                        downloadingInStep1 = false
                        step1DownloadMsg = if (GitHubReleaseUpdater.findBestLocalCarApk(activity) != null) {
                            "✓ Ya tienes la versión lista en tu móvil."
                        } else {
                            "⚠️ No se encontró el APK en línea. Revisa tu conexión a Internet."
                        }
                        renderStep()
                        return@fetchDualReleases
                    }
                    GitHubReleaseUpdater.downloadAsset(
                        context = activity,
                        asset = newestCar,
                        githubToken = prefs.githubToken,
                        onProgress = { msg ->
                            step1DownloadMsg = msg
                            dlStatusTv.visibility = View.VISIBLE
                            dlStatusTv.text = msg
                        },
                        onDone = { res ->
                            downloadingInStep1 = false
                            res.onSuccess { file ->
                                GitHubReleaseUpdater.startHostingApkForCar(activity, file) {}
                                step1DownloadMsg = "✅ Descargada ${file.name}. ¡Pulsa «Siguiente paso ▶»!"
                                renderStep()
                            }.onFailure { err ->
                                step1DownloadMsg = "⚠️ Error al descargar: ${err.message}"
                                renderStep()
                            }
                        },
                    )
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(12) },
        )

        contentContainer.addView(card1)
    }

    // ------------------------------------------------------------------------------------------
    // PASO 2 · GUARDAR EL APK EN EL PENDRIVE USB-C
    // ------------------------------------------------------------------------------------------
    private fun buildStep2UsbDrive() {
        val card2 = card()
        card2.addView(label("Paso 2 de 5 · Guardar en Pendrive USB-C", 19f, ACCENT, bold = true))
        card2.addView(
            label(
                "1️⃣ Enchufa un Pendrive USB-C (o adaptador USB) en el puerto de carga de tu móvil.\n" +
                    "2️⃣ Pulsa el botón de abajo y elige tu Pendrive USB a la izquierda para guardar el archivo.\n" +
                    "3️⃣ Cuando termine, sácalo del móvil y enchúfalo en el **puerto USB de datos bajo el reposabrazos** de tu Leapmotor C10.",
                14f,
                TEXT,
            ).apply { setPadding(0, dp(8), 0, dp(12)) }
        )

        val usbDone = prefs.usbExportDoneAtMs > 0L
        val statusBox = infoBox(if (usbDone) OK_BG else SURFACE_TOP, if (usbDone) OK else OUTLINE)
        if (usbDone) {
            statusBox.addView(label("🟢 ¡APK guardada en el Pendrive USB!", 15.5f, OK, bold = true))
            statusBox.addView(
                label(
                    "Enchufa ahora el pendrive en el puerto USB del coche (debajo del reposabrazos central) y pasa al Paso 3.",
                    13.5f,
                    TEXT,
                ).apply { setPadding(0, dp(4), 0, 0) }
            )
        } else {
            statusBox.addView(label("💾 Pendiente de copiar al Pendrive USB", 15f, WARN, bold = true))
            statusBox.addView(
                label(
                    "Al pulsar el botón se abrirá el selector de archivos de Android: toca las 3 rayas ☰ arriba a la izquierda, selecciona tu USB y pulsa «Guardar».",
                    13.5f,
                    DIM,
                ).apply { setPadding(0, dp(4), 0, 0) }
            )
        }
        card2.addView(statusBox)

        val localApk = GitHubReleaseUpdater.findBestLocalCarApk(activity)
        card2.addView(
            pill(
                textLabel = if (localApk != null) "💾 Guardar ${localApk.name} en Pendrive USB" else "⚠️ Vuelve al Paso 1 para descargar el APK",
                primary = !usbDone,
            ) {
                val target = GitHubReleaseUpdater.findBestLocalCarApk(activity)
                if (target == null) {
                    toast("Primero descarga el APK en el Paso 1")
                    setStep(0)
                } else {
                    onExportUsb(target)
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) },
        )

        if (!usbDone) {
            card2.addView(
                pill("✓ Ya metí el APK en el pendrive desde mi PC → Marcar listo", primary = false) {
                    prefs.usbExportDoneAtMs = System.currentTimeMillis()
                    setStep(2)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) },
            )
        }

        contentContainer.addView(card2)
    }

    // ------------------------------------------------------------------------------------------
    // PASO 3 · HOTSPOT DEL MÓVIL (DETECCIÓN EN VIVO)
    // ------------------------------------------------------------------------------------------
    private fun buildStep3Hotspot() {
        ensureHostingStarted()
        val card3 = card()
        card3.addView(label("Paso 3 de 5 · Conectar Wi-Fi del C10 a tu Hotspot", 19f, ACCENT, bold = true))
        card3.addView(
            label(
                "1️⃣ Pulsa abajo «📶 Abrir Punto de Acceso (Hotspot)» y enciende «Compartir conexión / Punto de acceso Wi-Fi» en tu móvil.\n\n" +
                    "2️⃣ En la pantalla del Leapmotor C10, abre el **Wi-Fi del coche** y conéctalo al Hotspot de tu móvil normalmente.\n\n" +
                    "✨ **Limpio 100%**: NO hace falta tocar Proxy ni DNS en el coche.",
                14f,
                TEXT,
            ).apply { setPadding(0, dp(8), 0, dp(12)) }
        )

        val liveBox = infoBox(SURFACE_TOP, ACCENT)
        liveBox.addView(label("📡 Estado en tiempo real de tu Hotspot y Portal:", 13.5f, ACCENT, bold = true))
        val statusTv = label("", 14.5f, TEXT, bold = true).apply {
            setPadding(0, dp(6), 0, 0)
        }
        liveHotspotStatusTv = statusTv
        liveBox.addView(statusTv)
        card3.addView(liveBox)

        card3.addView(
            pill("📶 Abrir Punto de Acceso (Hotspot)", primary = true) {
                onOpenHotspot()
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) },
        )

        updateLiveIndicatorsOnly()
        contentContainer.addView(card3)
    }

    // ------------------------------------------------------------------------------------------
    // PASO 4 · PORTAL EN VIVALDI + RADAR EN VIVO + ESQUEMA VISUAL DE LAS 2 FLECHAS ⤢
    // ------------------------------------------------------------------------------------------
    private fun buildStep4VivaldiAndArrows() {
        ensureHostingStarted()
        val bestIp = GitHubReleaseUpdater.findBestHotspotOrLocalIp() ?: "192.168.43.1"
        val portalUrl = "http://$bestIp:8788/portal"

        val card4 = card()
        card4.addView(label("Paso 4 de 5 · Abrir Portal en Vivaldi e Instalar", 19f, ACCENT, bold = true))
        card4.addView(
            label("Abre **Vivaldi** en la pantalla de tu Leapmotor C10 y escribe esta dirección exacta en la barra superior:", 14f, TEXT).apply {
                setPadding(0, dp(6), 0, dp(10))
            }
        )

        // Caja destacada con la URL exacta
        val urlBox = infoBox(BG, ACCENT).apply {
            gravity = Gravity.CENTER
        }
        val urlTv = label(portalUrl, 20f, ACCENT, bold = true).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        livePortalUrlTv = urlTv
        urlBox.addView(urlTv)
        urlBox.addView(
            pill("📋 Copiar dirección ($portalUrl)", primary = false) {
                val cm = activity.getSystemService(ClipboardManager::class.java)
                val cur = livePortalUrlTv?.text?.toString() ?: portalUrl
                cm?.setPrimaryClip(ClipData.newPlainText("Portal Qonecta", cur))
                toast("Dirección copiada: $cur")
            }.apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            },
        )
        card4.addView(urlBox)

        // Radar en tiempo real sincronizado con el servidor HTTP :8788
        val radarBox = infoBox(SURFACE_TOP, OUTLINE).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        }
        val radarTv = label("", 14f, WARN, bold = true)
        livePortalRadarBox = radarBox
        livePortalRadarTv = radarTv
        radarBox.addView(radarTv)
        card4.addView(radarBox)

        // Explicación + Esquema visual de las 2 flechas ⤢ en Archivos
        card4.addView(
            label("🔑 El truco clave cuando se abra «Archivos» en el coche:", 15.5f, TEXT, bold = true).apply {
                setPadding(0, dp(14), 0, dp(6))
            }
        )
        card4.addView(
            label(
                "1️⃣ En la página que carga Vivaldi, toca el **Botón Amarillo «📁 ABRIR ARCHIVOS DEL COCHE»**.\n" +
                    "2️⃣ En «Archivos», toca tu **Unidad USB** en la columna izquierda.\n" +
                    "3️⃣ Verás el icono de `QonectaUnit_...apk`. **NO toques el centro del icono**; toca las **2 flechas pequeñas `⤢` de la esquina** como en este dibujo:",
                13.5f,
                DIM,
            ).apply { setPadding(0, 0, 0, dp(10)) }
        )

        card4.addView(buildVisualArrowsDiagram())

        updateLiveIndicatorsOnly()
        contentContainer.addView(card4)
    }

    /** Construye un esquema visual claro de la tarjeta de archivo del C10 con el botón `⤢` resaltado. */
    private fun buildVisualArrowsDiagram(): View {
        val diagramOuter = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(BG, dp(16), OUTLINE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        diagramOuter.addView(
            label("Vista simulada del archivo en «Archivos» del C10:", 12.5f, FAINT, bold = true).apply {
                setPadding(0, 0, 0, dp(10))
            }
        )

        val tileRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Tarjeta simulada del APK en el explorador del C10
        val fileTile = FrameLayout(activity).apply {
            background = rounded(SURFACE_TOP, dp(14), OUTLINE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val centerCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(18), dp(12), dp(8))
        }
        centerCol.addView(label("📦", 30f, DIM).apply { gravity = Gravity.CENTER })
        centerCol.addView(label("QonectaUnit.apk", 12f, TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
        })
        centerCol.addView(label("❌ NO tocar centro", 11f, BAD, bold = true).apply { gravity = Gravity.CENTER })
        fileTile.addView(centerCol, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // Botón esquina superior derecha `⤢` iluminado
        val cornerArrows = TextView(activity).apply {
            text = "⤢"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ON_ACCENT)
            background = rounded(OK, dp(10))
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        fileTile.addView(
            cornerArrows,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END),
        )

        tileRow.addView(fileTile, LinearLayout.LayoutParams(dp(156), LinearLayout.LayoutParams.WRAP_CONTENT))

        val calloutCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        calloutCol.addView(label("👈 ¡TOCA EL «⤢» VERDE!", 14.5f, OK, bold = true))
        calloutCol.addView(
            label(
                "Ese botón pequeño de vista previa en la esquina abre directamente el **Instalador oficial del sistema** en el C10.\n\nPulsa «Instalar / Actualizar» y ¡listo!",
                12.5f,
                TEXT,
            ).apply { setPadding(0, dp(4), 0, 0) }
        )
        tileRow.addView(calloutCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        diagramOuter.addView(tileRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return diagramOuter
    }

    // ------------------------------------------------------------------------------------------
    // PASO 5 · VINCULAR Y SINCRONIZAR EN VIVO CON QONECTA UNIT
    // ------------------------------------------------------------------------------------------
    private fun buildStep5PairAndSync() {
        val s = BridgeState.current
        val card5 = card()
        card5.addView(label("Paso 5 de 5 · Vincular y Sincronizar con tu C10", 19f, ACCENT, bold = true))

        // Caja de estado en vivo (celebra automáticamente al conectar)
        val syncBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        }
        liveStep5SyncBox = syncBox
        card5.addView(syncBox)

        if (s.phase != BridgeState.Phase.CONNECTED) {
            card5.addView(
                label(
                    "1️⃣ Abre **Qonecta Unit** en tu Leapmotor C10.\n" +
                        "2️⃣ Marca las opciones que quieras usar (por ejemplo **☑ Bluetooth** solo, sin Wi-Fi 5 GHz, para que nunca toque tu radio ni tu Wi-Fi).\n" +
                        "3️⃣ En la pantalla del coche pulsa **«🔑 Vincular Companion»** arriba y escribe aquí el código de 6 cifras:",
                    13.5f,
                    TEXT,
                ).apply { setPadding(0, dp(12), 0, dp(10)) }
            )

            val chosenBt = prefs.carBtName ?: prefs.carBtAddress
            card5.addView(
                label(
                    if (chosenBt != null) "🔵 Bluetooth seleccionado: $chosenBt" else "🔵 Paso A · Selecciona el manos libres Bluetooth de tu C10:",
                    14f,
                    if (chosenBt != null) OK else WARN,
                    bold = true,
                ).apply { setPadding(0, 0, 0, dp(6)) }
            )

            val btBtns = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            btBtns.addView(
                pill(if (chosenBt != null) "Cambiar BT" else "1. Elegir BT del C10", primary = chosenBt == null) {
                    onPickBluetooth()
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            btBtns.addView(
                pill("Buscar cercano", primary = false) {
                    onAssociateBluetooth()
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) },
            )
            card5.addView(btBtns)

            card5.addView(
                label("🔑 Paso B · Código de 6 cifras que muestra Qonecta Unit:", 14f, TEXT, bold = true).apply {
                    setPadding(0, dp(14), 0, dp(6))
                }
            )
            val pinInput = EditText(activity).apply {
                hint = "000000"
                setHintTextColor(FAINT)
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.28f
                gravity = Gravity.CENTER
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(6))
                background = rounded(SURFACE_TOP, dp(14), OUTLINE)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                if (draftPin.isNotEmpty()) {
                    setText(draftPin)
                    setSelection(draftPin.length)
                }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(cs: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(cs: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(e: android.text.Editable?) {
                        draftPin = e?.toString().orEmpty()
                    }
                })
            }
            card5.addView(pinInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))

            card5.addView(
                pill("🔗 Vincular y sincronizar ahora", primary = true) {
                    val code = pinInput.text?.toString()?.trim().orEmpty()
                    if (code.length != 6) {
                        toast("Escribe el código de 6 cifras que aparece en Qonecta Unit")
                    } else {
                        draftPin = ""
                        BridgeService.pair(activity, code)
                        toast("Conectando con tu Leapmotor C10…")
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) },
            )
        }

        updateLiveIndicatorsOnly()
        contentContainer.addView(card5)
    }

    // ------------------------------------------------------------------------------------------
    // ACTUALIZACIÓN EN VIVO DE SENSORES (HOTSPOT, PORTAL HTTP Y PUENTE UNIT ↔ COMPANION)
    // ------------------------------------------------------------------------------------------
    private fun updateLiveIndicatorsOnly() {
        ensureHostingStarted()
        val dedicatedHotspotIp = GitHubReleaseUpdater.findDedicatedHotspotIp()
        val bestIp = dedicatedHotspotIp ?: GitHubReleaseUpdater.findBestHotspotOrLocalIp() ?: "192.168.43.1"

        // Paso 3: Hotspot en vivo
        liveHotspotStatusTv?.let { tv ->
            if (dedicatedHotspotIp != null && GitHubReleaseUpdater.isHosting) {
                tv.text = "🟢 ¡Hotspot activo detectado ($dedicatedHotspotIp)!\nServidor :8788 listo. Conecta el Wi-Fi del C10 a tu móvil y pulsa «Siguiente paso ▶»."
                tv.setTextColor(OK)
            } else if (GitHubReleaseUpdater.isHosting) {
                tv.text = "🟡 Servidor :8788 listo (IP actual: $bestIp).\nEnciende el «Punto de acceso / Hotspot» de tu móvil para crear la red Wi-Fi del coche."
                tv.setTextColor(WARN)
            } else {
                tv.text = "⚪ Esperando APK y Punto de acceso (Hotspot)…"
                tv.setTextColor(DIM)
            }
        }

        // Paso 4: URL y Radar en vivo del Portal en Vivaldi
        livePortalUrlTv?.text = "http://$bestIp:8788/portal"
        livePortalRadarTv?.let { tv ->
            val openedAt = GitHubReleaseUpdater.portalOpenedByCarAtMs
            val sentAt = GitHubReleaseUpdater.apkSentToCarAtMs
            val box = livePortalRadarBox
            if (openedAt > 0L || sentAt > 0L) {
                val clientApp = GitHubReleaseUpdater.portalOpenedClientApp.ifBlank { "Vivaldi" }
                val clientIp = GitHubReleaseUpdater.portalOpenedClientIp.ifBlank { "tu C10" }
                box?.background = rounded(OK_BG, dp(14), OK)
                tv.setTextColor(OK)
                tv.text = "🟢 ¡SINCRONIZADO EN VIVO! Tu Leapmotor C10 ($clientIp) acaba de abrir el Portal en $clientApp.\n" +
                    "Ahora toca el Botón Amarillo «ABRIR ARCHIVOS DEL COCHE» en la pantalla del C10 y pulsa las 2 flechas ⤢."
            } else {
                box?.background = rounded(SURFACE_TOP, dp(14), OUTLINE)
                tv.setTextColor(WARN)
                tv.text = "📡 Radar en vivo: esperando a que escribas http://$bestIp:8788/portal en Vivaldi del coche…\n" +
                    "(En cuanto el C10 entre, este recuadro se pondrá en verde automáticamente)."
            }
        }

        // Paso 5: Estado de sincronización en vivo Unit <-> Companion
        liveStep5SyncBox?.let { box ->
            box.removeAllViews()
            val s = BridgeState.current
            val t = UnitTelemetry.current
            when (s.phase) {
                BridgeState.Phase.CONNECTED -> {
                    box.background = rounded(OK_BG, dp(16), OK)
                    box.setPadding(dp(14), dp(14), dp(14), dp(14))
                    val carName = s.carName.ifBlank { prefs.carName ?: "tu Leapmotor C10" }
                    box.addView(label("🎉 ¡C10 conectado y sincronizado en vivo!", 17f, OK, bold = true))
                    box.addView(
                        label(
                            "Conectado con $carName vía ${s.transport.ifBlank { "Bluetooth" }}" +
                                (if (s.carAppVersion.isNotBlank()) " · Qonecta Unit v${s.carAppVersion}" else ""),
                            14f,
                            TEXT,
                            bold = true,
                        ).apply { setPadding(0, dp(4), 0, 0) }
                    )
                    if (t != null && t.hasUnitConnInfo) {
                        val unitSummary = buildString {
                            append("🚗 Casillas activas ahora en el coche: ")
                            append(if (t.unitBtCompanion) "☑ BT" else "☐ BT")
                            append(" · ")
                            append(if (t.unitWifi5g) "☑ Wi-Fi 5G" else "☐ Wi-Fi 5G")
                            append(" · ")
                            append(if (t.unitHotspot) "☑ Hotspot" else "☐ Hotspot")
                            append(" · ")
                            append(if (t.unitUsb) "☑ USB" else "☐ USB")
                        }
                        box.addView(label(unitSummary, 13f, ACCENT).apply { setPadding(0, dp(6), 0, 0) })
                    }
                    box.addView(
                        label(
                            "✨ A partir de hoy ya puedes actualizar Qonecta Unit en 1 toque sin pendrive ni Vivaldi.",
                            13f,
                            DIM,
                        ).apply { setPadding(0, dp(6), 0, 0) }
                    )
                }
                BridgeState.Phase.PAIRING, BridgeState.Phase.CONNECTING -> {
                    box.background = rounded(SURFACE_TOP, dp(14), ACCENT)
                    box.setPadding(dp(12), dp(10), dp(12), dp(10))
                    box.addView(label("🔵 ${s.detail.ifBlank { "Conectando con Qonecta Unit…" }}", 14f, ACCENT, bold = true))
                }
                else -> {
                    box.background = rounded(SURFACE_TOP, dp(14), OUTLINE)
                    box.setPadding(dp(12), dp(10), dp(12), dp(10))
                    val msg = s.detail.ifBlank { "Esperando vinculación con Qonecta Unit en tu C10…" }
                    box.addView(label("🟡 Estado actual: $msg", 13.5f, WARN))
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers visuales
    // ------------------------------------------------------------------------------------------
    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun card() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, dp(20), OUTLINE)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun infoBox(fill: Int, stroke: Int) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(fill, dp(14), stroke)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun label(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        // Permite negrita ligera sustituyendo **texto** de forma limpia sin markdown crudo
        text = s.replace("**", "")
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun pill(textLabel: String, primary: Boolean, onClick: () -> Unit) = TextView(activity).apply {
        text = textLabel
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(if (primary) ON_ACCENT else TEXT)
        background = if (primary) rounded(ACCENT, dp(24)) else rounded(SURFACE_TOP, dp(24), OUTLINE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
}
