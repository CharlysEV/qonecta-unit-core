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
import com.headqlink.unit.companion.CompanionI18n.tr
import java.io.File
import java.util.Locale

/**
 * Modo Tutorial interactivo y sincronizado en tiempo real (ES / EN / DE / PT) para instalar y vincular
 * `Qonecta Unit` en el Leapmotor C10 paso a paso desde cero.
 */
class InstallTutorialDialog(
    private val activity: Activity,
    private val prefs: CompanionPrefs,
    private val onExportUsb: (File) -> Unit,
    private val onOpenHotspot: () -> Unit,
    private val onPickBluetooth: () -> Unit,
    private val onAssociateBluetooth: () -> Unit,
    private val onOpenLanguagePicker: (() -> Unit)? = null,
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

    private lateinit var topBar: LinearLayout
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

        // Barra superior fija con título, selector de idioma y botón Cerrar
        topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(SURFACE)
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }
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

    /** Llamado desde `MainActivity` cuando vuelve del selector USB, Bluetooth o cambio de idioma. */
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

    private fun renderTopBar() {
        topBar.removeAllViews()
        topBar.addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    label(
                        tr(
                            "🎓 Modo Tutorial · Leapmotor C10",
                            "🎓 Tutorial Mode · Leapmotor C10",
                            "🎓 Tutorial-Modus · Leapmotor C10",
                            "🎓 Modo Tutorial · Leapmotor C10",
                        ),
                        17.5f,
                        TEXT,
                        bold = true,
                    )
                )
                addView(
                    label(
                        tr(
                            "Instalación guiada y sincronizada en vivo",
                            "Live-synced step-by-step installation guide",
                            "Live-synchronisierte Schritt-für-Schritt-Installation",
                            "Instalação guiada e sincronizada em direto",
                        ),
                        12f,
                        ACCENT,
                    )
                )
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (onOpenLanguagePicker != null) {
            val lg = CompanionI18n.currentLang()
            val langBtn = pill("${lg.flag} ${lg.badge}", primary = false) {
                onOpenLanguagePicker.invoke()
            }.apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            }
            topBar.addView(
                langBtn,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(6)
                },
            )
        }
        val closeBtn = pill(
            tr("✕ Cerrar", "✕ Close", "✕ Schließen", "✕ Fechar"),
            primary = false,
        ) { dismiss() }.apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }
        topBar.addView(closeBtn)
    }

    private fun renderStepHeader() {
        stepHeaderRow.removeAllViews()
        val titles = listOf(
            "1. APK",
            "2. USB",
            "3. Hotspot",
            "4. Portal",
            tr("5. Vincular", "5. Pair", "5. Koppeln", "5. Emparelhar"),
        )
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
                pill(tr("◀ Anterior", "◀ Back", "◀ Zurück", "◀ Anterior"), primary = false) { setStep(currentStep - 1) },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) },
            )
        }
        val isLast = currentStep == TOTAL_STEPS - 1
        val nextLabel = if (isLast) {
            tr("✅ Finalizar Tutorial", "✅ Finish Tutorial", "✅ Tutorial beenden", "✅ Concluir Tutorial")
        } else {
            tr("Siguiente paso ▶", "Next step ▶", "Nächster Schritt ▶", "Passo seguinte ▶")
        }
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

        renderTopBar()
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
        card1.addView(
            label(
                tr(
                    "Paso 1 de 5 · Preparar Vivaldi y la app del coche",
                    "Step 1 of 5 · Prepare Vivaldi & the car app",
                    "Schritt 1 von 5 · Vivaldi & Fahrzeug-App vorbereiten",
                    "Passo 1 de 5 · Preparar o Vivaldi e a app do carro",
                ),
                19f,
                ACCENT,
                bold = true,
            )
        )
        card1.addView(
            label(
                tr(
                    "Solo necesitas hacer este tutorial la primera vez. Cuando Qonecta Unit ya esté instalada en tu C10, ¡las siguientes actualizaciones se envían en 1 toque sin pendrive!",
                    "You only need this tutorial the very first time. Once Qonecta Unit is installed on your C10, all future updates are sent in 1 tap with no USB drive!",
                    "Du brauchst dieses Tutorial nur beim allerersten Mal. Sobald Qonecta Unit auf deinem C10 installiert ist, laufen alle künftigen Updates per 1-Tap ohne USB-Stick!",
                    "Só precisas de fazer este tutorial na primeira vez. Quando o Qonecta Unit já estiver instalado no teu C10, as atualizações seguintes são enviadas num toque sem pen USB!",
                ),
                13.5f,
                DIM,
            ).apply { setPadding(0, dp(6), 0, dp(12)) }
        )

        // Bloque A: Vivaldi en el coche
        val vivaldiBox = infoBox(SURFACE_TOP, OUTLINE)
        vivaldiBox.addView(
            label(
                tr(
                    "🅰️ En la pantalla de tu Leapmotor C10:",
                    "🅰️ On your Leapmotor C10 screen:",
                    "🅰️ Auf dem Display deines Leapmotor C10:",
                    "🅰️ No ecrã do teu Leapmotor C10:",
                ),
                15f,
                TEXT,
                bold = true,
            )
        )
        vivaldiBox.addView(
            label(
                tr(
                    "Abre la tienda oficial «LeapAppStore» del coche y descarga el navegador gratuito **Vivaldi** (si ya lo tienes instalado, perfecto).",
                    "Open the car's official «LeapAppStore» and download the free **Vivaldi** browser (if it's already installed, you're all set).",
                    "Öffne im Fahrzeug den offiziellen «LeapAppStore» und lade den kostenlosen **Vivaldi**-Browser herunter (falls bereits installiert, perfekt).",
                    "Abre a loja oficial «LeapAppStore» do carro e transfere o navegador gratuito **Vivaldi** (se já o tiveres instalado, perfeito).",
                ),
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
            apkBox.addView(
                label(
                    tr(
                        "🟢 ¡Detectado! APK lista en tu móvil",
                        "🟢 Detected! Car APK is ready on your phone",
                        "🟢 Erkannt! Fahrzeug-APK ist auf deinem Smartphone bereit",
                        "🟢 Detetado! APK pronta no teu telemóvel",
                    ),
                    15.5f,
                    OK,
                    bold = true,
                )
            )
            apkBox.addView(
                label(
                    tr(
                        "Archivo preparado: ${localApk.name} (v$verName · $sizeMb)",
                        "Ready file: ${localApk.name} (v$verName · $sizeMb)",
                        "Bereite Datei: ${localApk.name} (v$verName · $sizeMb)",
                        "Ficheiro preparado: ${localApk.name} (v$verName · $sizeMb)",
                    ),
                    13.5f,
                    TEXT,
                ).apply { setPadding(0, dp(4), 0, 0) }
            )
        } else {
            apkBox.addView(
                label(
                    tr(
                        "🟡 Falta descargar Qonecta Unit en tu móvil",
                        "🟡 Qonecta Unit needs to be downloaded to your phone",
                        "🟡 Qonecta Unit muss noch aufs Smartphone geladen werden",
                        "🟡 Falta transferir o Qonecta Unit para o teu telemóvel",
                    ),
                    15.5f,
                    WARN,
                    bold = true,
                )
            )
            apkBox.addView(
                label(
                    tr(
                        "Pulsa el botón de abajo para descargar automáticamente la última versión oficial.",
                        "Tap the button below to automatically download the latest official release.",
                        "Tippe unten auf den Button, um automatisch die neueste offizielle Version herunterzuladen.",
                        "Toca no botão abaixo para transferir automaticamente a última versão oficial.",
                    ),
                    13.5f,
                    TEXT,
                ).apply { setPadding(0, dp(4), 0, 0) }
            )
        }
        card1.addView(apkBox)

        val dlStatusTv = label(step1DownloadMsg, 13.5f, ACCENT, bold = true).apply {
            visibility = if (step1DownloadMsg.isNotBlank()) View.VISIBLE else View.GONE
            setPadding(0, dp(10), 0, dp(4))
        }
        card1.addView(dlStatusTv)

        val dlLabel = if (localApk != null) {
            tr(
                "☁️ Comprobar si hay versión más nueva en Internet",
                "☁️ Check online for a newer release",
                "☁️ Online auf neuere Version prüfen",
                "☁️ Verificar se há versão mais recente online",
            )
        } else {
            tr(
                "☁️ Descargar Qonecta Unit ahora",
                "☁️ Download Qonecta Unit now",
                "☁️ Qonecta Unit jetzt herunterladen",
                "☁️ Transferir o Qonecta Unit agora",
            )
        }
        card1.addView(
            pill(
                if (downloadingInStep1) tr("⏳ Descargando…", "⏳ Downloading…", "⏳ Lädt herunter…", "⏳ A transferir…") else dlLabel,
                primary = localApk == null,
            ) {
                if (downloadingInStep1) return@pill
                downloadingInStep1 = true
                step1DownloadMsg = tr(
                    "⏳ Buscando la última versión en GitHub…",
                    "⏳ Checking GitHub for the latest release…",
                    "⏳ Suche auf GitHub nach der neuesten Version…",
                    "⏳ A procurar a versão mais recente no GitHub…",
                )
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
                            tr(
                                "✓ Ya tienes la versión lista en tu móvil.",
                                "✓ You already have the APK ready on your phone.",
                                "✓ Die APK ist bereits auf deinem Smartphone bereit.",
                                "✓ Já tens a versão pronta no teu telemóvel.",
                            )
                        } else {
                            tr(
                                "⚠️ No se encontró el APK en línea. Revisa tu conexión a Internet.",
                                "⚠️ Could not find the APK online. Check your internet connection.",
                                "⚠️ APK online nicht gefunden. Prüfe deine Internetverbindung.",
                                "⚠️ Não foi possível encontrar o APK online. Verifica a ligação à Internet.",
                            )
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
                                step1DownloadMsg = tr(
                                    "✅ Descargada ${file.name}. ¡Pulsa «Siguiente paso ▶»!",
                                    "✅ Downloaded ${file.name}. Tap «Next step ▶»!",
                                    "✅ ${file.name} heruntergeladen. Tippe auf «Nächster Schritt ▶»!",
                                    "✅ Transferido ${file.name}. Toca em «Passo seguinte ▶»!",
                                )
                                renderStep()
                            }.onFailure { err ->
                                step1DownloadMsg = tr(
                                    "⚠️ Error al descargar: ${err.message}",
                                    "⚠️ Download failed: ${err.message}",
                                    "⚠️ Fehler beim Herunterladen: ${err.message}",
                                    "⚠️ Erro ao transferir: ${err.message}",
                                )
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
        card2.addView(
            label(
                tr(
                    "Paso 2 de 5 · Guardar en Pendrive USB-C",
                    "Step 2 of 5 · Save to USB-C Flash Drive",
                    "Schritt 2 von 5 · Auf USB-C-Stick speichern",
                    "Passo 2 de 5 · Guardar na Pen USB-C",
                ),
                19f,
                ACCENT,
                bold = true,
            )
        )
        card2.addView(
            label(
                tr(
                    "1️⃣ Enchufa un Pendrive USB-C (o adaptador USB) en el puerto de carga de tu móvil.\n" +
                        "2️⃣ Pulsa el botón de abajo y elige tu Pendrive USB a la izquierda para guardar el archivo.\n" +
                        "3️⃣ Cuando termine, sácalo del móvil y enchúfalo en el **puerto USB de datos bajo el reposabrazos** de tu Leapmotor C10.",
                    "1️⃣ Plug a USB-C flash drive (or USB adapter) into your phone's charging port.\n" +
                        "2️⃣ Tap the button below and select your USB drive on the left to save the file.\n" +
                        "3️⃣ When finished, unplug it from your phone and plug it into the **USB data port under the center armrest** of your Leapmotor C10.",
                    "1️⃣ Stecke einen USB-C-Stick (oder Adapter) in den Ladeanschluss deines Smartphones.\n" +
                        "2️⃣ Tippe unten auf den Button und wähle links deinen USB-Stick zum Speichern aus.\n" +
                        "3️⃣ Ziehe ihn danach ab und stecke ihn in den **USB-Datenanschluss unter der Mittelarmlehne** deines Leapmotor C10.",
                    "1️⃣ Liga uma Pen USB-C (ou adaptador USB) à porta de carregamento do teu telemóvel.\n" +
                        "2️⃣ Toca no botão abaixo e escolhe a tua Pen USB à esquerda para guardar o ficheiro.\n" +
                        "3️⃣ Quando terminar, retira-a do telemóvel e liga-a à **porta USB de dados por baixo do apoio de braço** do teu Leapmotor C10.",
                ),
                14f,
                TEXT,
            ).apply { setPadding(0, dp(8), 0, dp(12)) }
        )

        val usbDone = prefs.usbExportDoneAtMs > 0L
        val statusBox = infoBox(if (usbDone) OK_BG else SURFACE_TOP, if (usbDone) OK else OUTLINE)
        if (usbDone) {
            statusBox.addView(
                label(
                    tr(
                        "🟢 ¡APK guardada en el Pendrive USB!",
                        "🟢 APK saved to USB drive!",
                        "🟢 APK auf USB-Stick gespeichert!",
                        "🟢 APK guardada na Pen USB!",
                    ),
                    15.5f,
                    OK,
                    bold = true,
                )
            )
            statusBox.addView(
                label(
                    tr(
                        "Enchufa ahora el pendrive en el puerto USB del coche (debajo del reposabrazos central) y pasa al Paso 3.",
                        "Now plug the USB drive into the car's USB port (under the center armrest) and proceed to Step 3.",
                        "Stecke den USB-Stick jetzt in den USB-Anschluss im Fahrzeug (unter der Mittelarmlehne) und gehe zu Schritt 3.",
                        "Liga agora a pen USB à porta USB do carro (por baixo do apoio de braço central) e avança para o Passo 3.",
                    ),
                    13.5f,
                    TEXT,
                ).apply { setPadding(0, dp(4), 0, 0) }
            )
        } else {
            statusBox.addView(
                label(
                    tr(
                        "💾 Pendiente de copiar al Pendrive USB",
                        "💾 Waiting to copy to USB drive",
                        "💾 Noch nicht auf USB-Stick kopiert",
                        "💾 Pendente de copiar para a Pen USB",
                    ),
                    15f,
                    WARN,
                    bold = true,
                )
            )
            statusBox.addView(
                label(
                    tr(
                        "Al pulsar el botón se abrirá el selector de archivos de Android: toca las 3 rayas ☰ arriba a la izquierda, selecciona tu USB y pulsa «Guardar».",
                        "Tapping the button opens Android's file picker: tap the ☰ menu at the top-left, choose your USB drive, and tap «Save».",
                        "Beim Tippen öffnet sich die Android-Dateiauswahl: Tippe oben links auf ☰, wähle deinen USB-Stick und tippe auf «Speichern».",
                        "Ao tocar no botão abrir-se-á o seletor de ficheiros do Android: toca nas 3 linhas ☰ no canto superior esquerdo, escolhe a tua USB e toca em «Guardar».",
                    ),
                    13.5f,
                    DIM,
                ).apply { setPadding(0, dp(4), 0, 0) }
            )
        }
        card2.addView(statusBox)

        val localApk = GitHubReleaseUpdater.findBestLocalCarApk(activity)
        card2.addView(
            pill(
                textLabel = if (localApk != null) {
                    tr(
                        "💾 Guardar ${localApk.name} en Pendrive USB",
                        "💾 Save ${localApk.name} to USB Drive",
                        "💾 ${localApk.name} auf USB-Stick speichern",
                        "💾 Guardar ${localApk.name} na Pen USB",
                    )
                } else {
                    tr(
                        "⚠️ Vuelve al Paso 1 para descargar el APK",
                        "⚠️ Go back to Step 1 to download the APK",
                        "⚠️ Gehe zurück zu Schritt 1 zum APK-Download",
                        "⚠️ Volta ao Passo 1 para transferir o APK",
                    )
                },
                primary = !usbDone,
            ) {
                val target = GitHubReleaseUpdater.findBestLocalCarApk(activity)
                if (target == null) {
                    toast(
                        tr(
                            "Primero descarga el APK en el Paso 1",
                            "Download the APK in Step 1 first",
                            "Lade zuerst in Schritt 1 die APK herunter",
                            "Transfere primeiro o APK no Passo 1",
                        )
                    )
                    setStep(0)
                } else {
                    onExportUsb(target)
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) },
        )

        if (!usbDone) {
            card2.addView(
                pill(
                    tr(
                        "✓ Ya metí el APK en el pendrive desde mi PC → Marcar listo",
                        "✓ I already copied the APK onto the USB from my PC → Mark ready",
                        "✓ APK bereits vom PC auf den USB-Stick kopiert → Als bereit markieren",
                        "✓ Já copiei o APK para a pen USB no PC → Marcar pronto",
                    ),
                    primary = false,
                ) {
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
        card3.addView(
            label(
                tr(
                    "Paso 3 de 5 · Conectar Wi-Fi del C10 a tu Hotspot",
                    "Step 3 of 5 · Connect C10 Wi-Fi to your Hotspot",
                    "Schritt 3 von 5 · C10-WLAN mit Hotspot verbinden",
                    "Passo 3 de 5 · Ligar o Wi-Fi do C10 ao teu Hotspot",
                ),
                19f,
                ACCENT,
                bold = true,
            )
        )
        card3.addView(
            label(
                tr(
                    "1️⃣ Pulsa abajo «📶 Abrir Punto de Acceso (Hotspot)» y enciende «Compartir conexión / Punto de acceso Wi-Fi» en tu móvil.\n\n" +
                        "2️⃣ En la pantalla del Leapmotor C10, abre el **Wi-Fi del coche** y conéctalo al Hotspot de tu móvil normalmente.\n\n" +
                        "✨ **Limpio 100%**: NO hace falta tocar Proxy ni DNS en el coche.",
                    "1️⃣ Tap «📶 Open Wi-Fi Hotspot Settings» below and turn ON «Mobile Hotspot / Tethering» on your phone.\n\n" +
                        "2️⃣ On your Leapmotor C10 screen, open **Car Wi-Fi** and connect it normally to your phone's Hotspot.\n\n" +
                        "✨ **100% Clean**: NO Proxy or DNS changes needed on the car.",
                    "1️⃣ Tippe unten auf «📶 Hotspot-Einstellungen öffnen» und schalte «Mobiler Hotspot / Tethering» auf deinem Smartphone EIN.\n\n" +
                        "2️⃣ Öffne auf dem Leapmotor C10 das **Fahrzeug-WLAN** und verbinde es ganz normal mit deinem Smartphone-Hotspot.\n\n" +
                        "✨ **100 % Sauber**: KEINE Proxy- oder DNS-Änderungen im Fahrzeug nötig.",
                    "1️⃣ Toca abaixo em «📶 Abrir Ponto de Acesso (Hotspot)» e ativa «Ponto de acesso Wi-Fi / Partilha» no teu telemóvel.\n\n" +
                        "2️⃣ No ecrã do Leapmotor C10, abre o **Wi-Fi do carro** e liga-o normalmente ao Hotspot do teu telemóvel.\n\n" +
                        "✨ **100% Limpo**: NÃO é preciso alterar Proxy nem DNS no carro.",
                ),
                14f,
                TEXT,
            ).apply { setPadding(0, dp(8), 0, dp(12)) }
        )

        val liveBox = infoBox(SURFACE_TOP, ACCENT)
        liveBox.addView(
            label(
                tr(
                    "📡 Estado en tiempo real de tu Hotspot y Portal:",
                    "📡 Live Hotspot & Portal Server status:",
                    "📡 Live-Status von Hotspot & Portal-Server:",
                    "📡 Estado em tempo real do Hotspot e Portal:",
                ),
                13.5f,
                ACCENT,
                bold = true,
            )
        )
        val statusTv = label("", 14.5f, TEXT, bold = true).apply {
            setPadding(0, dp(6), 0, 0)
        }
        liveHotspotStatusTv = statusTv
        liveBox.addView(statusTv)
        card3.addView(liveBox)

        card3.addView(
            pill(
                tr(
                    "📶 Abrir Punto de Acceso (Hotspot)",
                    "📶 Open Wi-Fi Hotspot Settings",
                    "📶 Hotspot-Einstellungen öffnen",
                    "📶 Abrir Ponto de Acesso (Hotspot)",
                ),
                primary = true,
            ) {
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
        card4.addView(
            label(
                tr(
                    "Paso 4 de 5 · Abrir Portal en Vivaldi e Instalar",
                    "Step 4 of 5 · Open Portal in Vivaldi & Install",
                    "Schritt 4 von 5 · Portal in Vivaldi öffnen & installieren",
                    "Passo 4 de 5 · Abrir Portal no Vivaldi e Instalar",
                ),
                19f,
                ACCENT,
                bold = true,
            )
        )
        card4.addView(
            label(
                tr(
                    "Abre **Vivaldi** en la pantalla de tu Leapmotor C10 y escribe esta dirección exacta en la barra superior:",
                    "Open **Vivaldi** on your Leapmotor C10 screen and type this exact address in the top bar:",
                    "Öffne **Vivaldi** auf dem Display deines Leapmotor C10 und gib genau diese Adresse oben ein:",
                    "Abre o **Vivaldi** no ecrã do teu Leapmotor C10 e escreve este endereço exato na barra superior:",
                ),
                14f,
                TEXT,
            ).apply { setPadding(0, dp(6), 0, dp(10)) }
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
            pill(
                tr(
                    "📋 Copiar dirección ($portalUrl)",
                    "📋 Copy address ($portalUrl)",
                    "📋 Adresse kopieren ($portalUrl)",
                    "📋 Copiar endereço ($portalUrl)",
                ),
                primary = false,
            ) {
                val cm = activity.getSystemService(ClipboardManager::class.java)
                val cur = livePortalUrlTv?.text?.toString() ?: portalUrl
                cm?.setPrimaryClip(ClipData.newPlainText("Portal Qonecta", cur))
                toast(tr("Dirección copiada: $cur", "Address copied: $cur", "Adresse kopiert: $cur", "Endereço copiado: $cur"))
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
            label(
                tr(
                    "🔑 El truco clave cuando se abra «Archivos» en el coche:",
                    "🔑 The key trick once «Files» opens on the car:",
                    "🔑 Der entscheidende Trick in «Dateien» im Fahrzeug:",
                    "🔑 O truque essencial quando abrir «Ficheiros» no carro:",
                ),
                15.5f,
                TEXT,
                bold = true,
            ).apply { setPadding(0, dp(14), 0, dp(6)) }
        )
        card4.addView(
            label(
                tr(
                    "1️⃣ En la página que carga Vivaldi, toca el **Botón Amarillo «📁 ABRIR ARCHIVOS DEL COCHE»**.\n" +
                        "2️⃣ En «Archivos», toca tu **Unidad USB** en la columna izquierda.\n" +
                        "3️⃣ Verás el icono de `QonectaUnit_...apk`. **NO toques el centro del icono**; toca las **2 flechas pequeñas `⤢` de la esquina** como en este dibujo:",
                    "1️⃣ On the page loaded in Vivaldi, tap the **Yellow Button «📁 OPEN CAR FILES»**.\n" +
                        "2️⃣ Inside «Files», tap your **USB Drive** in the left column.\n" +
                        "3️⃣ You will see `QonectaUnit_...apk`. **DO NOT tap the center of the icon**; tap the **2 tiny diagonal arrows `⤢` in the corner** as shown below:",
                    "1️⃣ Tippe auf der Seite in Vivaldi auf den **Gelben Button «📁 FAHRZEUG-DATEIEN ÖFFNEN»**.\n" +
                        "2️⃣ Wähle links in «Dateien» deinen **USB-Stick** aus.\n" +
                        "3️⃣ Du siehst `QonectaUnit_...apk`. **NICHT auf die Mitte tippen**; tippe auf die **2 kleinen Pfeile `⤢` in der Ecke**, genau wie hier gezeigt:",
                    "1️⃣ Na página carregada no Vivaldi, toca no **Botão Amarelo «📁 ABRIR FICHEIROS DO CARRO»**.\n" +
                        "2️⃣ Em «Ficheiros», toca na tua **Unidade USB** na coluna esquerda.\n" +
                        "3️⃣ Verás o ícone `QonectaUnit_...apk`. **NÃO toques no centro do ícone**; toca nas **2 setas pequenas `⤢` no canto**, como neste esquema:",
                ),
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
            label(
                tr(
                    "Vista simulada del archivo en «Archivos» del C10:",
                    "Simulated file tile inside C10 «Files»:",
                    "Vorschau der Dateikachel in «Dateien» auf dem C10:",
                    "Vista simulada do ficheiro em «Ficheiros» do C10:",
                ),
                12.5f,
                FAINT,
                bold = true,
            ).apply { setPadding(0, 0, 0, dp(10)) }
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
        centerCol.addView(
            label(
                tr("❌ NO tocar centro", "❌ DO NOT tap center", "❌ NICHT Mitte tippen", "❌ NÃO tocar no centro"),
                11f,
                BAD,
                bold = true,
            ).apply { gravity = Gravity.CENTER }
        )
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
        calloutCol.addView(
            label(
                tr(
                    "👈 ¡TOCA EL «⤢» VERDE!",
                    "👈 TAP THE GREEN «⤢»!",
                    "👈 GRÜNES «⤢» ANTIPPEN!",
                    "👈 TOCA NO «⤢» VERDE!",
                ),
                14.5f,
                OK,
                bold = true,
            )
        )
        calloutCol.addView(
            label(
                tr(
                    "Ese botón pequeño de vista previa en la esquina abre directamente el **Instalador oficial del sistema** en el C10.\n\nPulsa «Instalar / Actualizar» y ¡listo!",
                    "That small corner preview button directly opens the **official Android System Installer** on the C10.\n\nTap «Install / Update» and you're done!",
                    "Dieses kleine Vorschau-Symbol in der Ecke öffnet direkt den **offiziellen System-Installer** auf dem C10.\n\nTippe auf «Installieren / Aktualisieren» – fertig!",
                    "Esse pequeno botão de pré-visualização no canto abre diretamente o **Instalador oficial do sistema** no C10.\n\nToca em «Instalar / Atualizar» e pronto!",
                ),
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
        card5.addView(
            label(
                tr(
                    "Paso 5 de 5 · Vincular y Sincronizar con tu C10",
                    "Step 5 of 5 · Pair & Live Sync with your C10",
                    "Schritt 5 von 5 · Mit deinem C10 koppeln & synchronisieren",
                    "Passo 5 de 5 · Emparelhar e Sincronizar com o teu C10",
                ),
                19f,
                ACCENT,
                bold = true,
            )
        )

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
                    tr(
                        "1️⃣ Abre **Qonecta Unit** en tu Leapmotor C10.\n" +
                            "2️⃣ Marca las opciones que quieras usar (por ejemplo **☑ Bluetooth** solo, sin Wi-Fi 5 GHz, para que nunca toque tu radio ni tu Wi-Fi).\n" +
                            "3️⃣ En la pantalla del coche pulsa **«🔑 Vincular Companion»** arriba y escribe aquí el código de 6 cifras:",
                        "1️⃣ Open **Qonecta Unit** on your Leapmotor C10.\n" +
                            "2️⃣ Check the modes you want to use (for example **☑ Bluetooth** only, without 5 GHz Wi-Fi, so it never touches your FM/DAB radio or Wi-Fi).\n" +
                            "3️⃣ On the car screen tap **«🔑 Pair Companion»** at the top and enter the 6-digit code below:",
                        "1️⃣ Öffne **Qonecta Unit** auf deinem Leapmotor C10.\n" +
                            "2️⃣ Wähle die gewünschten Verbindungsarten (z. B. nur **☑ Bluetooth** ohne 5-GHz-WLAN, damit Radio & WLAN unberührt bleiben).\n" +
                            "3️⃣ Tippe auf dem Fahrzeugdisplay oben auf **«🔑 Companion koppeln»** und gib den 6-stelligen Code hier ein:",
                        "1️⃣ Abre o **Qonecta Unit** no teu Leapmotor C10.\n" +
                            "2️⃣ Marca as opções que pretendes usar (por exemplo apenas **☑ Bluetooth**, sem Wi-Fi 5 GHz, para nunca interferir no rádio nem no Wi-Fi).\n" +
                            "3️⃣ No ecrã do carro toca em **«🔑 Vincular Companion»** no topo e introduz aqui o código de 6 dígitos:",
                    ),
                    13.5f,
                    TEXT,
                ).apply { setPadding(0, dp(12), 0, dp(10)) }
            )

            val chosenBt = prefs.carBtName ?: prefs.carBtAddress
            card5.addView(
                label(
                    if (chosenBt != null) {
                        tr(
                            "🔵 Bluetooth seleccionado: $chosenBt",
                            "🔵 Selected Bluetooth: $chosenBt",
                            "🔵 Ausgewähltes Bluetooth: $chosenBt",
                            "🔵 Bluetooth selecionado: $chosenBt",
                        )
                    } else {
                        tr(
                            "🔵 Paso A · Selecciona el manos libres Bluetooth de tu C10:",
                            "🔵 Step A · Select your C10's hands-free Bluetooth:",
                            "🔵 Schritt A · Wähle das Freisprech-Bluetooth deines C10:",
                            "🔵 Passo A · Seleciona o Bluetooth mãos-livres do teu C10:",
                        )
                    },
                    14f,
                    if (chosenBt != null) OK else WARN,
                    bold = true,
                ).apply { setPadding(0, 0, 0, dp(6)) }
            )

            val btBtns = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            btBtns.addView(
                pill(
                    if (chosenBt != null) tr("Cambiar BT", "Change BT", "BT ändern", "Alterar BT")
                    else tr("1. Elegir BT del C10", "1. Pick C10 BT", "1. C10-BT wählen", "1. Escolher BT do C10"),
                    primary = chosenBt == null,
                ) {
                    onPickBluetooth()
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            btBtns.addView(
                pill(tr("Buscar cercano", "Scan nearby", "In der Nähe suchen", "Procurar próximo"), primary = false) {
                    onAssociateBluetooth()
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) },
            )
            card5.addView(btBtns)

            card5.addView(
                label(
                    tr(
                        "🔑 Paso B · Código de 6 cifras que muestra Qonecta Unit:",
                        "🔑 Step B · 6-digit code shown on Qonecta Unit:",
                        "🔑 Schritt B · 6-stelliger Code auf Qonecta Unit:",
                        "🔑 Passo B · Código de 6 dígitos mostrado no Qonecta Unit:",
                    ),
                    14f,
                    TEXT,
                    bold = true,
                ).apply { setPadding(0, dp(14), 0, dp(6)) }
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
                pill(
                    tr(
                        "🔗 Vincular y sincronizar ahora",
                        "🔗 Pair & sync now",
                        "🔗 Jetzt koppeln & synchronisieren",
                        "🔗 Emparelhar e sincronizar agora",
                    ),
                    primary = true,
                ) {
                    val code = pinInput.text?.toString()?.trim().orEmpty()
                    if (code.length != 6) {
                        toast(
                            tr(
                                "Escribe el código de 6 cifras que aparece en Qonecta Unit",
                                "Enter the 6-digit code shown on Qonecta Unit",
                                "Gib den 6-stelligen Code auf Qonecta Unit ein",
                                "Introduz o código de 6 dígitos apresentado no Qonecta Unit",
                            )
                        )
                    } else {
                        draftPin = ""
                        BridgeService.pair(activity, code)
                        toast(
                            tr(
                                "Conectando con tu Leapmotor C10…",
                                "Connecting to your Leapmotor C10…",
                                "Verbinde mit deinem Leapmotor C10…",
                                "A ligar ao teu Leapmotor C10…",
                            )
                        )
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) },
            )
        }

        updateLiveIndicatorsOnly()
        contentContainer.addView(card5)
    }

    // ------------------------------------------------------------------------------------------
    // ACTUALIZACIÓN EN VIVO DE SENSORES (HOTSPOT, PORTAL HTTP Y PUENTE UNIT <-> COMPANION)
    // ------------------------------------------------------------------------------------------
    private fun updateLiveIndicatorsOnly() {
        ensureHostingStarted()
        val dedicatedHotspotIp = GitHubReleaseUpdater.findDedicatedHotspotIp()
        val bestIp = dedicatedHotspotIp ?: GitHubReleaseUpdater.findBestHotspotOrLocalIp() ?: "192.168.43.1"

        // Paso 3: Hotspot en vivo
        liveHotspotStatusTv?.let { tv ->
            if (dedicatedHotspotIp != null && GitHubReleaseUpdater.isHosting) {
                tv.text = tr(
                    "🟢 ¡Hotspot activo detectado ($dedicatedHotspotIp)!\nServidor :8788 listo. Conecta el Wi-Fi del C10 a tu móvil y pulsa «Siguiente paso ▶».",
                    "🟢 Active Hotspot detected ($dedicatedHotspotIp)!\nServer :8788 ready. Connect C10 Wi-Fi to your phone and tap «Next step ▶».",
                    "🟢 Aktiver Hotspot erkannt ($dedicatedHotspotIp)!\nServer :8788 bereit. Verbinde das C10-WLAN mit deinem Smartphone und tippe auf «Nächster Schritt ▶».",
                    "🟢 Hotspot ativo detetado ($dedicatedHotspotIp)!\nServidor :8788 pronto. Liga o Wi-Fi do C10 ao teu telemóvel e toca em «Passo seguinte ▶».",
                )
                tv.setTextColor(OK)
            } else if (GitHubReleaseUpdater.isHosting) {
                tv.text = tr(
                    "🟡 Servidor :8788 listo (IP actual: $bestIp).\nEnciende el «Punto de acceso / Hotspot» de tu móvil para crear la red Wi-Fi del coche.",
                    "🟡 Server :8788 ready (current IP: $bestIp).\nTurn ON your phone's «Wi-Fi Hotspot» to create the Wi-Fi network for the car.",
                    "🟡 Server :8788 bereit (aktuelle IP: $bestIp).\nSchalte den «WLAN-Hotspot» auf deinem Smartphone ein.",
                    "🟡 Servidor :8788 pronto (IP atual: $bestIp).\nLiga o «Ponto de acesso / Hotspot» do teu telemóvel para criar a rede Wi-Fi para o carro.",
                )
                tv.setTextColor(WARN)
            } else {
                tv.text = tr(
                    "⚪ Esperando APK y Punto de acceso (Hotspot)…",
                    "⚪ Waiting for APK and Wi-Fi Hotspot…",
                    "⚪ Warte auf APK und WLAN-Hotspot…",
                    "⚪ A aguardar APK e Ponto de Acesso (Hotspot)…",
                )
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
                val clientIp = GitHubReleaseUpdater.portalOpenedClientIp.ifBlank {
                    tr("tu C10", "your C10", "dein C10", "o teu C10")
                }
                box?.background = rounded(OK_BG, dp(14), OK)
                tv.setTextColor(OK)
                tv.text = tr(
                    "🟢 ¡SINCRONIZADO EN VIVO! Tu Leapmotor C10 ($clientIp) acaba de abrir el Portal en $clientApp.\n" +
                        "Ahora toca el Botón Amarillo «ABRIR ARCHIVOS DEL COCHE» en la pantalla del C10 y pulsa las 2 flechas ⤢.",
                    "🟢 LIVE SYNCED! Your Leapmotor C10 ($clientIp) just opened the Portal in $clientApp.\n" +
                        "Now tap the Yellow Button «OPEN CAR FILES» on the C10 screen and tap the 2 tiny arrows ⤢.",
                    "🟢 LIVE SYNCHRONISIERT! Dein Leapmotor C10 ($clientIp) hat soeben das Portal in $clientApp geöffnet.\n" +
                        "Tippe jetzt auf dem C10 auf den Gelben Button «FAHRZEUG-DATEIEN ÖFFNEN» und dann auf die 2 Pfeile ⤢.",
                    "🟢 SINCRONIZADO EM DIRETO! O teu Leapmotor C10 ($clientIp) acabou de abrir o Portal no $clientApp.\n" +
                        "Agora toca no Botão Amarelo «ABRIR FICHEIROS DO CARRO» no ecrã do C10 e toca nas 2 setas ⤢.",
                )
            } else {
                box?.background = rounded(SURFACE_TOP, dp(14), OUTLINE)
                tv.setTextColor(WARN)
                tv.text = tr(
                    "📡 Radar en vivo: esperando a que escribas http://$bestIp:8788/portal en Vivaldi del coche…\n" +
                        "(En cuanto el C10 entre, este recuadro se pondrá en verde automáticamente).",
                    "📡 Live Radar: waiting for you to open http://$bestIp:8788/portal in Vivaldi on the car…\n" +
                        "(As soon as your C10 connects, this box turns green automatically).",
                    "📡 Live-Radar: Warte darauf, dass du http://$bestIp:8788/portal in Vivaldi im Fahrzeug öffnest…\n" +
                        "(Sobald dein C10 die Seite öffnet, wird dieses Feld automatisch grün).",
                    "📡 Radar em direto: à espera que abras http://$bestIp:8788/portal no Vivaldi do carro…\n" +
                        "(Assim que o C10 entrar, esta caixa ficará verde automaticamente).",
                )
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
                    val carName = s.carName.ifBlank {
                        prefs.carName ?: tr("tu Leapmotor C10", "your Leapmotor C10", "deinem Leapmotor C10", "o teu Leapmotor C10")
                    }
                    box.addView(
                        label(
                            tr(
                                "🎉 ¡C10 conectado y sincronizado en vivo!",
                                "🎉 C10 connected and live-synced!",
                                "🎉 C10 verbunden und live synchronisiert!",
                                "🎉 C10 ligado e sincronizado em direto!",
                            ),
                            17f,
                            OK,
                            bold = true,
                        )
                    )
                    val verSuffix = if (s.carAppVersion.isNotBlank()) " · Qonecta Unit v${s.carAppVersion}" else ""
                    val trp = s.transport.ifBlank { "Bluetooth" }
                    box.addView(
                        label(
                            tr(
                                "Conectado con $carName vía $trp$verSuffix",
                                "Connected to $carName via $trp$verSuffix",
                                "Verbunden mit $carName über $trp$verSuffix",
                                "Ligado a $carName via $trp$verSuffix",
                            ),
                            14f,
                            TEXT,
                            bold = true,
                        ).apply { setPadding(0, dp(4), 0, 0) }
                    )
                    if (t != null && t.hasUnitConnInfo) {
                        val prefix = tr(
                            "🚗 Casillas activas ahora en el coche: ",
                            "🚗 Active checkboxes on the car right now: ",
                            "🚗 Aktive Optionen im Fahrzeug: ",
                            "🚗 Opções ativas agora no carro: ",
                        )
                        val unitSummary = buildString {
                            append(prefix)
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
                            tr(
                                "✨ A partir de hoy ya puedes actualizar Qonecta Unit en 1 toque sin pendrive ni Vivaldi.",
                                "✨ From now on you can update Qonecta Unit in 1 tap — no USB drive or Vivaldi needed.",
                                "✨ Ab sofort kannst du Qonecta Unit mit 1 Tippen aktualisieren – ohne USB-Stick oder Vivaldi.",
                                "✨ A partir de agora já podes atualizar o Qonecta Unit num toque — sem pen USB nem Vivaldi.",
                            ),
                            13f,
                            DIM,
                        ).apply { setPadding(0, dp(6), 0, 0) }
                    )
                }
                BridgeState.Phase.PAIRING, BridgeState.Phase.CONNECTING -> {
                    box.background = rounded(SURFACE_TOP, dp(14), ACCENT)
                    box.setPadding(dp(12), dp(10), dp(12), dp(10))
                    val fallback = tr(
                        "Conectando con Qonecta Unit…",
                        "Connecting to Qonecta Unit…",
                        "Verbinde mit Qonecta Unit…",
                        "A ligar ao Qonecta Unit…",
                    )
                    box.addView(label("🔵 ${s.detail.ifBlank { fallback }}", 14f, ACCENT, bold = true))
                }
                else -> {
                    box.background = rounded(SURFACE_TOP, dp(14), OUTLINE)
                    box.setPadding(dp(12), dp(10), dp(12), dp(10))
                    val msg = s.detail.ifBlank {
                        tr(
                            "Esperando vinculación con Qonecta Unit en tu C10…",
                            "Waiting to pair with Qonecta Unit on your C10…",
                            "Warte auf Kopplung mit Qonecta Unit auf deinem C10…",
                            "A aguardar emparelhamento com o Qonecta Unit no teu C10…",
                        )
                    }
                    box.addView(
                        label(
                            tr(
                                "🟡 Estado actual: $msg",
                                "🟡 Current status: $msg",
                                "🟡 Aktueller Status: $msg",
                                "🟡 Estado atual: $msg",
                            ),
                            13.5f,
                            WARN,
                        )
                    )
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
