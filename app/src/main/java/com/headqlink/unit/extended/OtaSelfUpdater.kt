package com.headqlink.unit.extended

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Centro Anti-OTA de Qonecta Unit para Leapmotor C10:
 * 1. Instalador y Auto-Actualizador autónomo por Pendrive USB y Wi-Fi/Hotspot (Independiente de Vivaldi).
 * 2. Explorador de Doble Panel del Sistema C10 (estilo MT Manager) + Extractor/Volcado de 1 clic a USB
 *    de la Tienda, PackageInstaller, Whitelists XML y paquetes del coche para preparar también el acceso MITM.
 */
object OtaSelfUpdater {

    private const val PREFS_NAME = "qonecta_ota_updater"
    private const val KEY_LAST_URL = "last_apk_url"
    private const val KEY_GH_REPO = "github_repo"
    private const val DEFAULT_GH_REPO = "CharlysEV/qonecta-unit"

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    enum class ModeTab(val title: String) {
        INSTALLER("📲 1. Instalador USB y Wi-Fi (Sin Vivaldi)"),
        MT_EXPLORER("🗂️ 2. Explorador Sistema C10 y Volcado USB (Estilo MT / Kit MITM)"),
    }

    data class DetectedApk(
        val file: File,
        val appLabel: String,
        val packageName: String,
        val versionName: String,
        val sizeMb: Double,
        val sourceTag: String,
    )

    fun showDialog(
        context: Context,
        initialTab: ModeTab = ModeTab.INSTALLER,
        onPickSystemFile: (() -> Unit)? = null,
    ): Dialog {
        QonectaTheme.ensureLoaded(context)
        ioExecutor.execute {
            val rescued = syncInternalDumpToUsbIfPresent(context)
            if (rescued > 0) {
                uiHandler.post {
                    Toast.makeText(
                        context,
                        "✓ $rescued archivos Anti-OTA sincronizados automáticamente al Pendrive USB",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        var activeTab = initialTab
        var currentLeftDir = File("/system/etc")
        if (!currentLeftDir.exists() || !currentLeftDir.canRead()) {
            currentLeftDir = File("/system")
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(QonectaTheme.bg)
                setStroke(dp(2), QonectaTheme.outline)
            }
        }

        // Cabecera superior
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleCol.addView(TextView(context).apply {
            text = "🛡️ Centro Anti-OTA Qonecta Unit · Instalador USB, Explorador MT y Kit MITM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        titleCol.addView(TextView(context).apply {
            text = "Instala APKs sin Vivaldi, explora el sistema de archivos interno del Leapmotor C10 y vuelca la whitelist/tienda al USB."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(QonectaTheme.dim)
            setPadding(0, dp(2), 0, 0)
        })
        headerRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val closeBtn = pillButton(context, "✕ Cerrar", QonectaTheme.surfaceTop, QonectaTheme.outline, QonectaTheme.text) {
            dialog.dismiss()
        }
        headerRow.addView(closeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        root.addView(headerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Barra de pestañas (Instalador vs Explorador MT + Volcado MITM) + Permisos
        val subBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tabBtnMap = LinkedHashMap<ModeTab, TextView>()
        val contentHost = FrameLayout(context)

        fun renderCurrentTab() {
            tabBtnMap.forEach { (tab, btn) ->
                val selected = tab == activeTab
                btn.setTextColor(if (selected) QonectaTheme.onAccent else QonectaTheme.text)
                btn.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(999).toFloat()
                    setColor(if (selected) QonectaTheme.accent else QonectaTheme.surfaceTop)
                    setStroke(dp(1), if (selected) QonectaTheme.accent else QonectaTheme.outline)
                }
            }
            contentHost.removeAllViews()
            val view = when (activeTab) {
                ModeTab.INSTALLER -> buildInstallerTab(context, prefs, dialog, onPickSystemFile)
                ModeTab.MT_EXPLORER -> buildMtExplorerTab(
                    context = context,
                    initialDir = currentLeftDir,
                    onDirChanged = { currentLeftDir = it },
                )
            }
            contentHost.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }

        ModeTab.values().forEachIndexed { idx, tab ->
            val b = TextView(context).apply {
                text = tab.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(8), dp(18), dp(8))
                setOnClickListener {
                    activeTab = tab
                    renderCurrentTab()
                }
            }
            tabBtnMap[tab] = b
            subBar.addView(
                b,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
                    if (idx > 0) leftMargin = dp(8)
                },
            )
        }

        val spacer = View(context)
        subBar.addView(spacer, LinearLayout.LayoutParams(0, dp(1), 1f))

        val canInstall = canRequestInstall(context)
        val permBtn = pillButton(
            context,
            if (canInstall) "✓ Permiso APK Activo" else "⚙️ Autorizar Instalar APK",
            QonectaTheme.surface,
            if (canInstall) QonectaTheme.accent else QonectaTheme.amber,
            if (canInstall) QonectaTheme.accent else QonectaTheme.amber,
        ) {
            openInstallPermissionSettings(context)
        }
        val usbPermBtn = pillButton(
            context,
            "🔌 Permiso Archivos/USB",
            QonectaTheme.surface,
            QonectaTheme.outline,
            QonectaTheme.text,
        ) {
            openAllFilesPermissionSettings(context)
        }
        subBar.addView(usbPermBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { rightMargin = dp(8) })
        subBar.addView(permBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))

        root.addView(
            subBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            },
        )

        root.addView(
            contentHost,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(10)
            },
        )

        renderCurrentTab()

        dialog.setContentView(root)
        val dm = context.resources.displayMetrics
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.88f).toInt())
        }
        dialog.show()
        return dialog
    }

    // =========================================================================
    // PESTAÑA 1: INSTALADOR USB Y WI-FI (SIN VIVALDI)
    // =========================================================================

    private fun buildInstallerTab(
        context: Context,
        prefs: android.content.SharedPreferences,
        dialog: Dialog,
        onPickSystemFile: (() -> Unit)?,
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val bodyRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        // COLUMNA IZQUIERDA: Pendrive USB y Almacenamiento del Coche
        val usbCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = roundedBox(QonectaTheme.surface, dp(18), dp(1), QonectaTheme.outline)
        }

        val usbHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        usbHeader.addView(TextView(context).apply {
            text = "🔌 1. INSTALAR DESDE PENDRIVE USB / MEMORIA C10"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(QonectaTheme.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val rescanBtn = pillButton(context, "🔄 Re-escanear USB", QonectaTheme.surfaceTop, QonectaTheme.accent, QonectaTheme.accent) {}
        usbHeader.addView(rescanBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        usbCard.addView(usbHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val usbSubTv = TextView(context).apply {
            text = "Buscando archivos .apk en pendrives USB conectados y memoria interna..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(QonectaTheme.dim)
            setPadding(0, dp(4), 0, dp(8))
        }
        usbCard.addView(usbSubTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val apkScroll = ScrollView(context).apply { isFillViewport = true }
        val apkListCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        apkScroll.addView(apkListCol)
        usbCard.addView(apkScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (onPickSystemFile != null) {
            val pickBtn = pillButton(
                context,
                "📂 Explorar Pendrive USB / Archivos del Sistema",
                QonectaTheme.surfaceTop,
                QonectaTheme.outline,
                QonectaTheme.text,
            ) {
                dialog.dismiss()
                onPickSystemFile.invoke()
            }
            usbCard.addView(
                pickBtn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) },
            )
        }

        fun runUsbScan() {
            usbSubTv.text = "Escaneando puertos USB del Leapmotor C10 y memoria interna..."
            apkListCol.removeAllViews()
            ioExecutor.execute {
                val found = scanForApks(context)
                uiHandler.post {
                    apkListCol.removeAllViews()
                    if (found.isEmpty()) {
                        usbSubTv.text = "No se detectaron archivos .apk directos. Conecta un pendrive USB con el .apk en la raíz o pulsa «Explorar Pendrive USB»."
                        val emptyHint = TextView(context).apply {
                            text = "💡 Consejo Anti-OTA:\nCopia «com.tencent.qqmusiccar_0.1.5-unit.apk» en la raíz de tu pendrive USB y conéctalo al puerto USB del C10. Qonecta Unit lo detectará aquí sin abrir Vivaldi."
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                            setTextColor(QonectaTheme.dim)
                            setPadding(dp(12), dp(12), dp(12), dp(12))
                            background = roundedBox(QonectaTheme.bg, dp(14), dp(1), QonectaTheme.outline)
                        }
                        apkListCol.addView(emptyHint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    } else {
                        usbSubTv.text = "Se encontraron ${found.size} archivo(s) .apk listos para instalar:"
                        found.forEachIndexed { idx, item ->
                            val row = LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(dp(12), dp(10), dp(12), dp(10))
                                background = roundedBox(QonectaTheme.bg, dp(14), dp(1), QonectaTheme.outline)
                            }
                            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                            col.addView(TextView(context).apply {
                                text = "${item.appLabel}  (v${item.versionName})"
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                                setTextColor(QonectaTheme.text)
                                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            })
                            col.addView(TextView(context).apply {
                                text = String.format(
                                    Locale.getDefault(),
                                    "%s · %.1f MB · %s",
                                    item.sourceTag,
                                    item.sizeMb,
                                    item.file.name,
                                )
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                                setTextColor(QonectaTheme.accent)
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            })
                            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })

                            val installBtn = pillButton(
                                context,
                                "📲 Instalar",
                                QonectaTheme.accent,
                                QonectaTheme.accent,
                                QonectaTheme.onAccent,
                            ) {
                                installApkFile(context, item.file)
                            }
                            row.addView(installBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
                            apkListCol.addView(
                                row,
                                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                    if (idx > 0) topMargin = dp(8)
                                },
                            )
                        }
                    }
                }
            }
        }
        rescanBtn.setOnClickListener { runUsbScan() }

        // COLUMNA DERECHA: Instalación por Red / Hotspot del Móvil (Sin Navegador)
        val netCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = roundedBox(QonectaTheme.surface, dp(18), dp(1), QonectaTheme.outline)
        }
        netCard.addView(TextView(context).apply {
            text = "🌐 2. DESCARGAR E INSTALAR POR WI-FI / HOTSPOT DEL MÓVIL"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(QonectaTheme.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        netCard.addView(TextView(context).apply {
            text = "Introduce un enlace directo al archivo .apk (por ejemplo desde un servidor HTTP en el Wi-Fi de tu móvil o una URL directa):"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(QonectaTheme.dim)
            setPadding(0, dp(4), 0, dp(10))
        })

        val urlInput = EditText(context).apply {
            hint = "http://192.168.43.1:8080/com.tencent.qqmusiccar_0.1.5-unit.apk"
            setHintTextColor(QonectaTheme.dim)
            setText(prefs.getString(KEY_LAST_URL, "").orEmpty())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(QonectaTheme.text)
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBox(QonectaTheme.bg, dp(14), dp(1), QonectaTheme.outline)
        }
        netCard.addView(urlInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))

        val netStatusTv = TextView(context).apply {
            text = "Listo para descargar directamente a la memoria interna de Qonecta Unit."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(QonectaTheme.dim)
            setPadding(0, dp(8), 0, dp(8))
        }
        netCard.addView(netStatusTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val downloadBtn = pillButton(
            context,
            "⬇️ Descargar APK e Instalar Ahora",
            QonectaTheme.accent,
            QonectaTheme.accent,
            QonectaTheme.onAccent,
        ) {
            val urlStr = urlInput.text?.toString()?.trim().orEmpty()
            if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
                netStatusTv.text = "⚠️ Introduce una dirección válida que empiece por http:// o https://"
                netStatusTv.setTextColor(QonectaTheme.amber)
                return@pillButton
            }
            prefs.edit().putString(KEY_LAST_URL, urlStr).apply()
            downloadAndInstallApk(context, urlStr, netStatusTv)
        }
        netCard.addView(downloadBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        // Bloque GitHub Releases
        val ghBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(QonectaTheme.bg, dp(14), dp(1), QonectaTheme.outline)
        }
        ghBox.addView(TextView(context).apply {
            text = "🐙 3. ACTUALIZAR DESDE GITHUB RELEASES"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(QonectaTheme.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        val ghRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val ghRepoInput = EditText(context).apply {
            hint = DEFAULT_GH_REPO
            setHintTextColor(QonectaTheme.dim)
            setText(prefs.getString(KEY_GH_REPO, DEFAULT_GH_REPO).orEmpty().ifBlank { DEFAULT_GH_REPO })
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(QonectaTheme.text)
            setSingleLine(true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBox(QonectaTheme.surface, dp(12), dp(1), QonectaTheme.outline)
        }
        ghRow.addView(ghRepoInput, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
        val ghBtn = pillButton(
            context,
            "🐙 Buscar Releases",
            QonectaTheme.surfaceTop,
            QonectaTheme.accent,
            QonectaTheme.accent,
        ) {
            val repo = ghRepoInput.text?.toString()?.trim().orEmpty().ifBlank { DEFAULT_GH_REPO }
            prefs.edit().putString(KEY_GH_REPO, repo).apply()
            checkGitHubReleases(context, repo, netStatusTv, urlInput)
        }
        ghRow.addView(ghBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        ghBox.addView(ghRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        netCard.addView(
            ghBox,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            },
        )

        val infoBox = TextView(context).apply {
            text = "🛡️ ¿Cómo funciona el blindaje Anti-OTA?\n" +
                "• En Android Automotive (LeapOS), las actualizaciones OTA actualizan /system pero respetan las apps ya instaladas en /data/app.\n" +
                "• Pulsa arriba la pestaña «🗂️ 2. Explorador Sistema C10 y Volcado USB» para navegar por /system, /vendor y /storage como en MT Manager o volcar las whitelists y la tienda oficial del C10 a tu pendrive USB."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(QonectaTheme.dim)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBox(QonectaTheme.bg, dp(14), dp(1), QonectaTheme.outline)
        }
        netCard.addView(
            infoBox,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )

        bodyRow.addView(
            usbCard,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f).apply { rightMargin = dp(12) },
        )
        bodyRow.addView(
            netCard,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.95f),
        )

        runUsbScan()
        return bodyRow
    }

    // =========================================================================
    // PESTAÑA 2: EXPLORADOR DEL SISTEMA C10 (ESTILO MT) + VOLCADO A USB / KIT MITM
    // =========================================================================

    private fun buildMtExplorerTab(
        context: Context,
        initialDir: File,
        onDirChanged: (File) -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        var currentDir = initialDir
        val bodyRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        // PANEL IZQUIERDO: Explorador del Sistema de Archivos C10 (estilo MT Manager)
        val leftCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBox(QonectaTheme.surface, dp(18), dp(1), QonectaTheme.outline)
        }

        leftCard.addView(TextView(context).apply {
            text = "🚗 PANEL IZQUIERDO · SISTEMA DE ARCHIVOS INTERNO DEL COCHE (ESTILO MT)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(QonectaTheme.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })

        // Botones rápidos de rutas del sistema C10
        val quickScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val quickRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val quickPaths = listOf(
            "📜 /system/etc" to "/system/etc",
            "📦 /system/priv-app" to "/system/priv-app",
            "📱 /system/app" to "/system/app",
            "⚙️ /vendor/etc" to "/vendor/etc",
            "🧩 /product/app" to "/product/app",
            "💾 /storage (USB)" to "/storage",
            "📥 Download" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
        )

        val pathBannerTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBox(QonectaTheme.bg, dp(10), dp(1), QonectaTheme.outline)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }

        val fileListScroll = ScrollView(context).apply { isFillViewport = true }
        val fileListCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fileListScroll.addView(fileListCol)

        val rightLogTv = TextView(context)

        fun navigateTo(dir: File) {
            currentDir = dir
            onDirChanged(dir)
            pathBannerTv.text = "📂 ${dir.absolutePath}"
            fileListCol.removeAllViews()

            // Fila para subir al directorio padre ".."
            val parent = dir.parentFile
            if (parent != null) {
                val upRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = roundedBox(QonectaTheme.bg, dp(12), dp(1), QonectaTheme.outline)
                    setOnClickListener { navigateTo(parent) }
                }
                upRow.addView(TextView(context).apply {
                    text = "⬆️  ..  (Subir a ${parent.absolutePath})"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                    setTextColor(QonectaTheme.accent)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                })
                fileListCol.addView(
                    upRow,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(6)
                    },
                )
            }

            val children = try {
                dir.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))
            } catch (_: Throwable) {
                null
            }

            if (children.isNullOrEmpty()) {
                fileListCol.addView(TextView(context).apply {
                    text = "Carpeta vacía o protegida por SELinux en modo lectura directa (${dir.absolutePath}).\nUsa los accesos rápidos superiores o el botón «⚡ Volcar Kit Anti-OTA al USB» a la derecha."
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(QonectaTheme.dim)
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                })
                return
            }

            children.take(120).forEach { f ->
                val isDir = f.isDirectory
                val isApk = !isDir && f.name.endsWith(".apk", ignoreCase = true)
                val isText = !isDir && (
                    f.name.endsWith(".xml", true) ||
                        f.name.endsWith(".prop", true) ||
                        f.name.endsWith(".conf", true) ||
                        f.name.endsWith(".json", true) ||
                        f.name.endsWith(".txt", true) ||
                        f.name.endsWith(".ini", true)
                    )

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    background = roundedBox(QonectaTheme.bg, dp(12), dp(1), QonectaTheme.outline)
                    if (isDir) {
                        setOnClickListener { navigateTo(f) }
                    } else if (isText) {
                        setOnClickListener { showTextFilePreviewDialog(context, f) }
                    }
                }

                val icon = when {
                    isDir -> "📁"
                    isApk -> "📦"
                    isText -> "📄"
                    else -> "🔹"
                }

                val infoCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                infoCol.addView(TextView(context).apply {
                    text = "$icon  ${f.name}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                    setTextColor(if (isDir) QonectaTheme.accent else QonectaTheme.text)
                    typeface = Typeface.create("sans-serif-medium", if (isDir || isApk) Typeface.BOLD else Typeface.NORMAL)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })

                val perms = buildString {
                    append(if (f.canRead()) "r" else "-")
                    append(if (f.canWrite()) "w" else "-")
                    append(if (f.canExecute()) "x" else "-")
                }
                val subText = if (isDir) {
                    val count = try { f.list()?.size ?: 0 } catch (_: Throwable) { 0 }
                    "Directorio · $count elementos · [$perms]"
                } else {
                    val kb = f.length() / 1024.0
                    val sizeStr = if (kb >= 1024.0) String.format(Locale.getDefault(), "%.2f MB", kb / 1024.0) else String.format(Locale.getDefault(), "%.1f KB", kb)
                    "$sizeStr · [$perms]"
                }
                infoCol.addView(TextView(context).apply {
                    text = subText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(QonectaTheme.dim)
                })
                row.addView(infoCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })

                if (isText) {
                    val viewBtn = pillButton(context, "👁️ Ver", QonectaTheme.surfaceTop, QonectaTheme.outline, QonectaTheme.text) {
                        showTextFilePreviewDialog(context, f)
                    }
                    row.addView(viewBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { rightMargin = dp(6) })
                }

                if (isApk) {
                    val instBtn = pillButton(context, "📲 Instalar", QonectaTheme.accent, QonectaTheme.accent, QonectaTheme.onAccent) {
                        installApkFile(context, f)
                    }
                    row.addView(instBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { rightMargin = dp(6) })
                }

                val copyBtn = pillButton(context, "➡️ Copiar a USB", QonectaTheme.surfaceTop, QonectaTheme.accent, QonectaTheme.accent) {
                    copyFileOrDirToUsbDump(context, f, rightLogTv)
                }
                row.addView(copyBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))

                fileListCol.addView(
                    row,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(6)
                    },
                )
            }
        }

        quickPaths.forEachIndexed { idx, (label, pathStr) ->
            val chip = pillButton(context, label, QonectaTheme.surfaceTop, QonectaTheme.outline, QonectaTheme.text) {
                navigateTo(File(pathStr))
            }
            quickRow.addView(
                chip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                    if (idx > 0) leftMargin = dp(6)
                },
            )
        }
        quickScroll.addView(quickRow)
        leftCard.addView(
            quickScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            },
        )
        leftCard.addView(
            pathBannerTv,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            },
        )
        leftCard.addView(fileListScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // PANEL DERECHO: Destino USB y Extractor Automático de Kit Anti-OTA / MITM
        val rightCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBox(QonectaTheme.surface, dp(18), dp(1), QonectaTheme.outline)
        }
        rightCard.addView(TextView(context).apply {
            text = "🔌 PANEL DERECHO · VOLCADO A PENDRIVE USB Y EXTRACTOR DE WHITELIST / MITM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(QonectaTheme.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })

        val targetDumpDir = resolveBestDumpDirectory(context)
        rightCard.addView(TextView(context).apply {
            text = "Carpeta de destino actual: ${targetDumpDir.absolutePath}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(QonectaTheme.dim)
            setPadding(0, dp(4), 0, dp(8))
        })

        val dumpAllBtn = pillButton(
            context,
            "⚡ Volcar Kit Anti-OTA al USB (Tienda C10, PackageInstaller, Whitelists XML y Paquetes)",
            QonectaTheme.accent,
            QonectaTheme.accent,
            QonectaTheme.onAccent,
        ) {
            exportAntiOtaKitToUsb(context, rightLogTv)
        }
        rightCard.addView(dumpAllBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        val logScroll = ScrollView(context).apply {
            isFillViewport = true
            background = roundedBox(QonectaTheme.bg, dp(14), dp(1), QonectaTheme.outline)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        rightLogTv.apply {
            text = "📋 Registro del Extractor C10 (Opciones A «MITM» y B «MT»):\n\n" +
                "• Usa el panel izquierdo para explorar /system/etc, /system/priv-app, /vendor o /storage como en MT Manager.\n" +
                "• Pulsa «➡️ Copiar a USB» en cualquier archivo o carpeta para guardarlo en tu Pendrive USB.\n" +
                "• O pulsa el botón turquesa superior «⚡ Volcar Kit Anti-OTA al USB» para extraer de golpe:\n" +
                "   1) Los APKs del sistema de Leapmotor (AppStore / Tienda, PackageInstaller, CarControl, AutoSdkServer).\n" +
                "   2) Los archivos XML de permisos y listas blancas (/system/etc/permissions y /system/etc/sysconfig).\n" +
                "   3) El inventario completo de nombres de paquete instalados en el C10 para configurar un proxy MITM si algún día llega un coche nuevo sin Vivaldi."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.MONOSPACE
        }
        logScroll.addView(rightLogTv)
        rightCard.addView(
            logScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(10)
            },
        )

        bodyRow.addView(
            leftCard,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.08f).apply { rightMargin = dp(12) },
        )
        bodyRow.addView(
            rightCard,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.92f),
        )

        navigateTo(currentDir)
        return bodyRow
    }

    private fun showTextFilePreviewDialog(context: Context, file: File) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val content = try {
            file.readText(Charsets.UTF_8).take(16_000)
        } catch (e: Exception) {
            "No se pudo leer ${file.absolutePath}: ${e.message}"
        }
        val scroll = ScrollView(context).apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(QonectaTheme.bg)
        }
        scroll.addView(TextView(context).apply {
            text = content
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(QonectaTheme.text)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        AlertDialog.Builder(context)
            .setTitle("📄 ${file.absolutePath}")
            .setView(scroll)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun resolveBestDumpDirectory(context: Context): File {
        // 1. Si el usuario concedió MANAGE_EXTERNAL_STORAGE y la raíz del USB (/storage/XXXX-XXXX) es escribible directamente:
        val usbRoots = ArrayList<File>()
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            sm?.storageVolumes?.forEach { vol ->
                if (vol.isRemovable) {
                    val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        vol.directory
                    } else {
                        runCatching { vol.javaClass.getMethod("getPathFile").invoke(vol) as? File }.getOrNull()
                    }
                    if (dir != null && dir.exists() && dir.canWrite()) {
                        usbRoots.add(dir)
                    }
                }
            }
        } catch (_: Throwable) {
        }

        File("/storage").listFiles()?.forEach { child ->
            if (child.isDirectory && child.name != "self" && child.name != "emulated" && child.canWrite()) {
                usbRoots.add(child)
            }
        }

        if (usbRoots.isNotEmpty()) {
            return File(usbRoots.first(), "Qonecta_C10_Dump").apply { mkdirs() }
        }

        // 2. Clave en Android 11 (API 30, Scoped Storage en Leapmotor C10):
        // La raíz /storage/XXXX-XXXX tiene canWrite()==false para java.io.File sin SAF,
        // PERO context.getExternalFilesDirs(null) devuelve /storage/XXXX-XXXX/Android/data/com.tencent.qqmusiccar/files
        // en el pendrive USB, donde Android 11 SIEMPRE permite escritura directa de carpetas y APKs sin bloqueo.
        try {
            val extDirs = context.getExternalFilesDirs(null)
            if (extDirs != null) {
                for (dir in extDirs) {
                    if (dir == null) continue
                    val path = dir.absolutePath
                    val isRemovable = runCatching { Environment.isExternalStorageRemovable(dir) }.getOrDefault(false) ||
                        (!path.contains("/emulated/") && !path.startsWith("/sdcard"))
                    if (isRemovable && (dir.exists() || dir.mkdirs()) && dir.canWrite()) {
                        return File(dir, "Qonecta_C10_Dump").apply { mkdirs() }
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // 3. Fallback a la carpeta Download interna del coche si no hay pendrive USB conectado
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: context.getExternalFilesDir(null)
            ?: context.filesDir
        return File(base, "Qonecta_C10_Dump").apply { mkdirs() }
    }

    /**
     * Si en una sesión anterior se volcó el Kit Anti-OTA a la memoria interna del coche
     * (/storage/emulated/0/Download/Qonecta_C10_Dump) porque Android 11 bloqueó la raíz del USB,
     * este método lo copia automáticamente al pendrive USB en cuanto está conectado.
     */
    fun syncInternalDumpToUsbIfPresent(context: Context): Int {
        return try {
            val internalDump = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Qonecta_C10_Dump",
            )
            if (!internalDump.exists() || !internalDump.isDirectory) return 0
            val bestTarget = resolveBestDumpDirectory(context)
            if (bestTarget.absolutePath == internalDump.absolutePath) return 0
            var copied = 0
            fun copyTree(src: File, dst: File) {
                if (src.isDirectory) {
                    dst.mkdirs()
                    src.listFiles()?.forEach { child ->
                        copyTree(child, File(dst, child.name))
                    }
                } else if (src.isFile && src.canRead()) {
                    dst.parentFile?.mkdirs()
                    if (!dst.exists() || dst.length() != src.length()) {
                        src.copyTo(dst, overwrite = true)
                        copied++
                    }
                }
            }
            copyTree(internalDump, bestTarget)
            copied
        } catch (_: Throwable) {
            0
        }
    }

    private fun copyFileOrDirToUsbDump(context: Context, source: File, logTv: TextView) {
        val destRoot = resolveBestDumpDirectory(context)
        logTv.text = "⏳ Copiando «${source.name}» a ${destRoot.absolutePath}..."
        ioExecutor.execute {
            try {
                var copiedCount = 0
                var copiedBytes = 0L
                fun copyRecursive(src: File, dst: File) {
                    if (src.isDirectory) {
                        dst.mkdirs()
                        src.listFiles()?.forEach { child ->
                            copyRecursive(child, File(dst, child.name))
                        }
                    } else if (src.isFile && src.canRead()) {
                        dst.parentFile?.mkdirs()
                        FileInputStream(src).use { input ->
                            FileOutputStream(dst).use { output ->
                                copiedBytes += input.copyTo(output)
                            }
                        }
                        copiedCount++
                    }
                }
                val target = File(destRoot, source.name)
                copyRecursive(source, target)
                val mb = copiedBytes / (1024.0 * 1024.0)
                uiHandler.post {
                    logTv.text = "✓ Copiado completado:\n" +
                        "• Origen: ${source.absolutePath}\n" +
                        "• Destino: ${target.absolutePath}\n" +
                        "• Archivos copiados: $copiedCount (${String.format(Locale.getDefault(), "%.2f MB", mb)})"
                    Toast.makeText(context, "Copiado a ${target.absolutePath}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                uiHandler.post {
                    logTv.text = "⚠️ Error al copiar ${source.name}: ${e.message}"
                }
            }
        }
    }

    private fun exportAntiOtaKitToUsb(context: Context, logTv: TextView) {
        val destRoot = resolveBestDumpDirectory(context)
        logTv.text = "⏳ Extrayendo Kit Anti-OTA y MITM del Leapmotor C10 en:\n${destRoot.absolutePath} ..."
        ioExecutor.execute {
            val sb = StringBuilder()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            sb.appendLine("=== VOLCADO KIT ANTI-OTA Y MITM · QONECTA UNIT ($stamp) ===")
            sb.appendLine("Carpeta destino: ${destRoot.absolutePath}")
            sb.appendLine()

            try {
                // 1. Volcar build.prop y archivos XML de configuración / permisos / sysconfig
                val sysCfgOut = File(destRoot, "1_system_configs").apply { mkdirs() }
                var xmlCount = 0
                listOf(
                    File("/system/build.prop"),
                    File("/vendor/build.prop"),
                    File("/system/etc/hosts"),
                ).forEach { f ->
                    if (f.exists() && f.canRead()) {
                        runCatching {
                            f.copyTo(File(sysCfgOut, f.name), overwrite = true)
                            xmlCount++
                        }
                    }
                }
                listOf(
                    File("/system/etc/permissions"),
                    File("/system/etc/sysconfig"),
                    File("/vendor/etc/permissions"),
                    File("/product/etc/permissions"),
                ).forEach { dir ->
                    if (dir.exists() && dir.canRead()) {
                        val subOut = File(sysCfgOut, "${dir.parentFile?.name}_${dir.name}").apply { mkdirs() }
                        dir.listFiles()?.forEach { xf ->
                            if (xf.isFile && xf.canRead()) {
                                runCatching {
                                    xf.copyTo(File(subOut, xf.name), overwrite = true)
                                    xmlCount++
                                }
                            }
                        }
                    }
                }
                sb.appendLine("✓ [1/3] Archivos XML de permisos/whitelists y build.prop extraídos: $xmlCount")

                // 2. Volcar APKs clave del sistema (Tienda Leapmotor, PackageInstaller, CarControl, AutoSdkServer, Vivaldi, etc.)
                val apksOut = File(destRoot, "2_c10_key_apks").apply { mkdirs() }
                val pm = context.packageManager
                @Suppress("DEPRECATION")
                val installed = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                var dumpedApks = 0
                val keywords = listOf(
                    "leapmotor",
                    "leapmoto",
                    "faa",
                    "appstore",
                    "appcenter",
                    "market",
                    "packageinstaller",
                    "documentsui",
                    "vivaldi",
                    "autosdk",
                    "carcontrol",
                    "forvia",
                    "manstep",
                    "neusoft",
                    "ota",
                    "update",
                    "iflytek",
                    "qqmusiccar",
                )

                val reportTxt = StringBuilder()
                reportTxt.appendLine("=== INVENTARIO DE PAQUETES DEL COCHE C10 (PARA WHITELIST Y MITM) ===")
                reportTxt.appendLine("Fecha: $stamp")
                reportTxt.appendLine("Total paquetes detectados: ${installed.size}")
                reportTxt.appendLine()

                for (pkg in installed.sortedBy { it.packageName }) {
                    val pName = pkg.packageName ?: continue
                    val srcPath = pkg.applicationInfo?.sourceDir.orEmpty()
                    val canInstallPkgs = pkg.requestedPermissions?.any { it == "android.permission.REQUEST_INSTALL_PACKAGES" } == true
                    val hasInternet = pkg.requestedPermissions?.any { it == "android.permission.INTERNET" } == true

                    reportTxt.appendLine(
                        String.format(
                            Locale.US,
                            "• %s (v%s) | installPkgs=%s | net=%s | path=%s",
                            pName,
                            pkg.versionName ?: "?",
                            if (canInstallPkgs) "SI" else "no",
                            if (hasInternet) "SI" else "no",
                            srcPath,
                        ),
                    )

                    val matchesKeyword = keywords.any { kw ->
                        pName.contains(kw, ignoreCase = true) || srcPath.contains(kw, ignoreCase = true)
                    }
                    if ((matchesKeyword || canInstallPkgs) && srcPath.isNotBlank() && pName != context.packageName) {
                        val srcFile = File(srcPath)
                        if (srcFile.exists() && srcFile.canRead()) {
                            runCatching {
                                val outApk = File(apksOut, "${pName}.apk")
                                srcFile.copyTo(outApk, overwrite = true)
                                dumpedApks++
                            }
                        }
                    }
                }

                val reportFile = File(destRoot, "3_INVENTARIO_PAQUETES_Y_MITM_C10.txt")
                reportFile.writeText(reportTxt.toString(), Charsets.UTF_8)

                // Si antes había archivos en Download/Qonecta_C10_Dump, sincronizarlos también
                syncInternalDumpToUsbIfPresent(context)

                sb.appendLine("✓ [2/3] APKs estratégicos extraídos (Tienda/Instalador/Leapmotor/Apps con permiso instalar): $dumpedApks")
                sb.appendLine("✓ [3/3] Informe completo guardado en: ${reportFile.name} (${installed.size} paquetes analizados)")
                sb.appendLine()
                sb.appendLine(" Paquetes en este sistema con permiso REQUEST_INSTALL_PACKAGES (capaces de instalar APKs):")
                installed.filter { pkg ->
                    pkg.requestedPermissions?.any { it == "android.permission.REQUEST_INSTALL_PACKAGES" } == true
                }.forEach { pkg ->
                    sb.appendLine("   • ${pkg.packageName}  (${pkg.applicationInfo?.sourceDir})")
                }

                uiHandler.post {
                    logTv.text = sb.toString()
                    Toast.makeText(context, "Kit Anti-OTA volcado en ${destRoot.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                uiHandler.post {
                    logTv.text = "⚠️ Error durante el volcado: ${e.message}\n\n$sb"
                }
            }
        }
    }

    /**
     * Genera la sección completa Anti-OTA / MITM para incluir directamente dentro de DiAuto-report-*.txt
     * (que se guarda en la raíz del pendrive USB mediante Storage Access Framework) y además sincroniza
     * cualquier volcado previo al directorio escribible del pendrive USB.
     */
    fun buildDiagnosticAntiOtaSection(context: Context): String {
        val sb = StringBuilder()
        try {
            val synced = syncInternalDumpToUsbIfPresent(context)
            val bestDir = resolveBestDumpDirectory(context)
            sb.appendLine("=== QONECTA UNIT · INVENTARIO ANTI-OTA, WHITELISTS Y PAQUETES C10 ===")
            sb.appendLine("Directorio USB activo para volcados APK/XML: ${bestDir.absolutePath} (sincronizados desde memoria interna=$synced)")

            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val installed = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            sb.appendLine("Total paquetes instalados en el C10: ${installed.size}")
            sb.appendLine("--- Paquetes con permiso REQUEST_INSTALL_PACKAGES (capaces de instalar APKs) ---")
            installed.filter { pkg ->
                pkg.requestedPermissions?.any { it == "android.permission.REQUEST_INSTALL_PACKAGES" } == true
            }.sortedBy { it.packageName }.forEach { pkg ->
                val installer = runCatching {
                    if (Build.VERSION.SDK_INT >= 30) {
                        pm.getInstallSourceInfo(pkg.packageName).let { it.installingPackageName ?: it.initiatingPackageName }
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getInstallerPackageName(pkg.packageName)
                    }
                }.getOrNull() ?: "sistema"
                sb.appendLine("  [INSTALLER] ${pkg.packageName} (v${pkg.versionName ?: "?"}) | instaladoPor=$installer | path=${pkg.applicationInfo?.sourceDir}")
            }

            sb.appendLine("--- Inventario completo de paquetes del coche (para Whitelist y MITM) ---")
            for (pkg in installed.sortedBy { it.packageName }) {
                val pName = pkg.packageName ?: continue
                val srcPath = pkg.applicationInfo?.sourceDir.orEmpty()
                val canInstall = pkg.requestedPermissions?.any { it == "android.permission.REQUEST_INSTALL_PACKAGES" } == true
                val hasNet = pkg.requestedPermissions?.any { it == "android.permission.INTERNET" } == true
                sb.appendLine("  • $pName (v${pkg.versionName ?: "?"}) | installPkgs=${if (canInstall) "SI" else "no"} | net=${if (hasNet) "SI" else "no"} | path=$srcPath")
            }

            sb.appendLine("--- Archivos XML de permisos y sysconfig en /system, /vendor y /product ---")
            listOf(
                File("/system/etc/permissions"),
                File("/system/etc/sysconfig"),
                File("/vendor/etc/permissions"),
                File("/product/etc/permissions"),
            ).forEach { dir ->
                if (dir.exists() && dir.canRead()) {
                    val files = dir.listFiles()?.map { it.name }?.sorted().orEmpty()
                    sb.appendLine("  [${dir.absolutePath}] (${files.size} archivos): ${files.joinToString(", ")}")
                }
            }
        } catch (e: Throwable) {
            sb.appendLine("Error generando sección Anti-OTA: ${e.message}")
        }
        return sb.toString()
    }

    fun installFromContentUri(context: Context, uri: Uri) {
        Toast.makeText(context, "Preparando paquete APK para instalar...", Toast.LENGTH_SHORT).show()
        ioExecutor.execute {
            try {
                val outDir = File(context.cacheDir, "ota_apks").apply { mkdirs() }
                val destFile = File(outDir, "selected_update.apk")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("No se pudo leer el archivo seleccionado")
                uiHandler.post {
                    installApkFile(context, destFile)
                }
            } catch (e: Exception) {
                uiHandler.post {
                    Toast.makeText(context, "Error al abrir el APK: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun installApkFile(context: Context, sourceFile: File) {
        if (!canRequestInstall(context)) {
            Toast.makeText(
                context,
                "Autoriza a Qonecta Unit para instalar aplicaciones y vuelve a pulsar Instalar.",
                Toast.LENGTH_LONG,
            ).show()
            openInstallPermissionSettings(context)
            return
        }

        ioExecutor.execute {
            try {
                val stageDir = File(context.cacheDir, "ota_apks").apply { mkdirs() }
                val stagedFile = if (sourceFile.parentFile?.absolutePath == stageDir.absolutePath) {
                    sourceFile
                } else {
                    val target = File(stageDir, sourceFile.name.ifBlank { "update_unit.apk" })
                    FileInputStream(sourceFile).use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output)
                        }
                    }
                    target
                }

                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    stagedFile,
                )
                uiHandler.post {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            } catch (e: Exception) {
                uiHandler.post {
                    Toast.makeText(context, "No se pudo lanzar el instalador: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private data class GhAsset(
        val releaseTag: String,
        val releaseName: String,
        val name: String,
        val downloadUrl: String,
        val sizeMb: Double,
    )

    private fun checkGitHubReleases(
        context: Context,
        rawRepo: String,
        statusTv: TextView,
        urlInput: EditText,
    ) {
        val cleanRepo = rawRepo
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .trim('/')
        statusTv.text = "⏳ Consultando GitHub Releases de $cleanRepo..."
        statusTv.setTextColor(QonectaTheme.accent)
        ioExecutor.execute {
            var conn: HttpURLConnection? = null
            try {
                val apiUrl = "https://api.github.com/repos/$cleanRepo/releases?per_page=5"
                conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 12_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "QonectaUnit-OTA/1.0")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("GitHub API devolvió HTTP $code para $cleanRepo")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = org.json.JSONArray(body)
                val assets = ArrayList<GhAsset>()
                for (i in 0 until arr.length()) {
                    val rel = arr.optJSONObject(i) ?: continue
                    val tag = rel.optString("tag_name", "release")
                    val relName = rel.optString("name", tag).ifBlank { tag }
                    val relAssets = rel.optJSONArray("assets") ?: continue
                    for (j in 0 until relAssets.length()) {
                        val a = relAssets.optJSONObject(j) ?: continue
                        val name = a.optString("name", "")
                        val dl = a.optString("browser_download_url", "")
                        val sizeBytes = a.optLong("size", 0L)
                        if (name.endsWith(".apk", ignoreCase = true) && dl.isNotBlank()) {
                            assets.add(
                                GhAsset(
                                    releaseTag = tag,
                                    releaseName = relName,
                                    name = name,
                                    downloadUrl = dl,
                                    sizeMb = sizeBytes / (1024.0 * 1024.0),
                                ),
                            )
                        }
                    }
                }
                uiHandler.post {
                    if (assets.isEmpty()) {
                        statusTv.text = "ℹ️ No hay archivos .apk adjuntos en los releases de $cleanRepo."
                        statusTv.setTextColor(QonectaTheme.amber)
                    } else if (assets.size == 1) {
                        val item = assets[0]
                        urlInput.setText(item.downloadUrl)
                        statusTv.text = "✓ Encontrado ${item.name} (${item.releaseTag}). Descargando..."
                        downloadAndInstallApk(context, item.downloadUrl, statusTv)
                    } else {
                        val labels = assets.map { a ->
                            String.format(Locale.getDefault(), "%s · %s (%.1f MB)", a.releaseTag, a.name, a.sizeMb)
                        }.toTypedArray()
                        AlertDialog.Builder(context)
                            .setTitle("Elige APK de GitHub ($cleanRepo)")
                            .setItems(labels) { _, which ->
                                val chosen = assets[which]
                                urlInput.setText(chosen.downloadUrl)
                                downloadAndInstallApk(context, chosen.downloadUrl, statusTv)
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                        statusTv.text = "✓ ${assets.size} APKs encontrados en GitHub Releases ($cleanRepo)."
                    }
                }
            } catch (e: Exception) {
                uiHandler.post {
                    statusTv.text = "⚠️ No se pudo consultar GitHub Releases: ${e.message}"
                    statusTv.setTextColor(QonectaTheme.amber)
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun downloadAndInstallApk(context: Context, urlStr: String, statusTv: TextView) {
        statusTv.text = "⏳ Conectando y descargando paquete APK..."
        statusTv.setTextColor(QonectaTheme.accent)
        ioExecutor.execute {
            var conn: HttpURLConnection? = null
            try {
                val outDir = File(context.cacheDir, "ota_apks").apply { mkdirs() }
                val outFile = File(outDir, "downloaded_update.apk")
                val url = URL(urlStr)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "QonectaUnit-OTA/1.0")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }
                val totalBytes = conn.contentLengthLong
                var downloaded = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(16 * 1024)
                        var read: Int
                        var lastUiMs = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastUiMs > 200L) {
                                lastUiMs = now
                                val mb = downloaded / (1024.0 * 1024.0)
                                val pctStr = if (totalBytes > 0) {
                                    String.format(Locale.getDefault(), "%.1f MB / %.1f MB (%d%%)", mb, totalBytes / (1024.0 * 1024.0), ((downloaded * 100L) / totalBytes).toInt())
                                } else {
                                    String.format(Locale.getDefault(), "%.1f MB descargados", mb)
                                }
                                uiHandler.post {
                                    statusTv.text = "⬇️ Descargando: $pctStr"
                                }
                            }
                        }
                    }
                }
                uiHandler.post {
                    statusTv.text = "✓ Descarga completada (${String.format(Locale.getDefault(), "%.1f", downloaded / (1024.0 * 1024.0))} MB). Abriendo instalador..."
                    statusTv.setTextColor(QonectaTheme.accent)
                    installApkFile(context, outFile)
                }
            } catch (e: Exception) {
                uiHandler.post {
                    statusTv.text = "⚠️ Error en la descarga: ${e.message}"
                    statusTv.setTextColor(QonectaTheme.amber)
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun scanForApks(context: Context): List<DetectedApk> {
        val candidateDirs = LinkedHashSet<File>()

        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            sm?.storageVolumes?.forEach { vol ->
                try {
                    val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        vol.directory
                    } else {
                        val m = vol.javaClass.getMethod("getPathFile")
                        m.invoke(vol) as? File
                    }
                    if (dir != null && dir.exists()) {
                        candidateDirs.add(dir)
                        candidateDirs.add(File(dir, "Download"))
                        candidateDirs.add(File(dir, "apk"))
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }

        listOf(
            "/storage",
            "/mnt/media_rw",
            "/storage/udisk",
            "/storage/usb0",
            "/storage/usb1",
            "/storage/usbotg",
        ).forEach { path ->
            val f = File(path)
            if (f.exists() && f.canRead()) {
                if (path == "/storage" || path == "/mnt/media_rw") {
                    f.listFiles()?.forEach { child ->
                        if (child.isDirectory && child.name != "self" && child.name != "emulated") {
                            candidateDirs.add(child)
                            candidateDirs.add(File(child, "Download"))
                            candidateDirs.add(File(child, "apk"))
                        }
                    }
                } else {
                    candidateDirs.add(f)
                }
            }
        }

        try {
            val extRoot = Environment.getExternalStorageDirectory()
            if (extRoot != null && extRoot.exists()) {
                candidateDirs.add(extRoot)
                candidateDirs.add(File(extRoot, "Download"))
            }
        } catch (_: Throwable) {
        }
        context.getExternalFilesDir(null)?.let { candidateDirs.add(it) }

        val seenPaths = HashSet<String>()
        val results = ArrayList<DetectedApk>()
        val pm = context.packageManager

        for (dir in candidateDirs) {
            val files = try {
                dir.listFiles()
            } catch (_: Throwable) {
                null
            } ?: continue

            for (f in files) {
                if (!f.isFile || !f.name.endsWith(".apk", ignoreCase = true)) continue
                val canonical = try {
                    f.canonicalPath
                } catch (_: Throwable) {
                    f.absolutePath
                }
                if (!seenPaths.add(canonical)) continue

                var appLabel = f.nameWithoutExtension
                var pkgName = "APK"
                var verName = "?"
                try {
                    @Suppress("DEPRECATION")
                    val info = pm.getPackageArchiveInfo(f.absolutePath, PackageManager.GET_META_DATA)
                    if (info != null) {
                        if (!info.packageName.isNullOrBlank()) pkgName = info.packageName
                        if (!info.versionName.isNullOrBlank()) verName = info.versionName!!
                        info.applicationInfo?.apply {
                            sourceDir = f.absolutePath
                            publicSourceDir = f.absolutePath
                            val lbl = pm.getApplicationLabel(this).toString()
                            if (lbl.isNotBlank()) appLabel = lbl
                        }
                    }
                } catch (_: Throwable) {
                }

                val isUsb = !canonical.contains("/emulated/") && !canonical.startsWith("/sdcard")
                val sourceTag = if (isUsb) "🔌 Pendrive USB" else "📁 Memoria C10"
                results.add(
                    DetectedApk(
                        file = f,
                        appLabel = appLabel,
                        packageName = pkgName,
                        versionName = verName,
                        sizeMb = f.length() / (1024.0 * 1024.0),
                        sourceTag = sourceTag,
                    ),
                )
            }
        }

        return results.sortedWith(
            compareByDescending<DetectedApk> { it.sourceTag.startsWith("🔌") }
                .thenByDescending { it.packageName == context.packageName }
                .thenByDescending { it.file.lastModified() },
        )
    }

    private fun canRequestInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    private fun openInstallPermissionSettings(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (_: Exception) {
            Toast.makeText(context, "Activa «Instalar aplicaciones desconocidas» en Ajustes del sistema", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAllFilesPermissionSettings(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
            }
        }
    }

    private fun roundedBox(fillColor: Int, radiusPx: Int, strokeWidthPx: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fillColor)
            if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
        }
    }

    private fun pillButton(
        context: Context,
        label: String,
        fillColor: Int,
        strokeColor: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): TextView {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        return TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTextColor(textColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(fillColor)
                setStroke(dp(1), strokeColor)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }
}
