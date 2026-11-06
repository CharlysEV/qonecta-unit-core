package com.headqlink.unit.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.headqlink.unit.bridge.BridgeMessage
import com.headqlink.unit.bridge.MapsRoute
import com.headqlink.unit.bridge.NavPoint

/**
 * Pantalla del Companion: estado de la conexión con el coche, emparejamiento en 2 pasos y permisos.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_ASSOCIATE = 10
        const val REQ_PERMS = 11

        const val BG = 0xFF0A0E14.toInt()
        const val SURFACE = 0xFF111821.toInt()
        const val SURFACE_TOP = 0xFF1E2935.toInt()
        const val OUTLINE = 0xFF243140.toInt()
        const val TEXT = 0xFFEAF2F7.toInt()
        const val DIM = 0xFFA3B3C2.toInt()
        const val FAINT = 0xFF8296A9.toInt()
        const val ACCENT = 0xFF00E5C7.toInt()
        const val ON_ACCENT = 0xFF00211C.toInt()
        const val OK = 0xFF4CFF9F.toInt()
        const val WARN = 0xFFFFB547.toInt()
        const val BAD = 0xFFFF6B7A.toInt()
    }

    private lateinit var prefs: CompanionPrefs
    private lateinit var root: LinearLayout
    private val stateListener: (BridgeState.Snapshot) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = CompanionPrefs(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        BridgeState.addListener(stateListener)
        if (prefs.isPaired && prefs.enabled && BridgeState.current.phase == BridgeState.Phase.STOPPED) {
            BridgeService.start(this)
        }
        render()
    }

    override fun onPause() {
        BridgeState.removeListener(stateListener)
        super.onPause()
    }

    // ------------------------------------------------------------------------------------------
    // Pantalla
    // ------------------------------------------------------------------------------------------

    private fun render() {
        root.removeAllViews()
        val s = BridgeState.current

        root.addView(text("Qonecta Unit Companion", 26f, TEXT, bold = true))
        root.addView(text("Tu móvil y Qonecta Unit del coche, conectados: las rutas del planificador del coche se abren en Google Maps y Android Auto las muestra.", 15f, DIM).apply {
            setPadding(0, dp(6), 0, dp(14))
        })

        // --- Estado ---
        root.addView(card().apply {
            val (label, color) = when (s.phase) {
                BridgeState.Phase.CONNECTED -> "● Conectado con ${s.carName.ifBlank { prefs.carName ?: "el coche" }}" to OK
                BridgeState.Phase.CONNECTING -> "● Conectando…" to ACCENT
                BridgeState.Phase.PAIRING -> "● Emparejando…" to ACCENT
                BridgeState.Phase.SEARCHING -> "● Buscando el coche…" to WARN
                BridgeState.Phase.STOPPED -> (if (prefs.isPaired) "● En espera" else "● Sin emparejar") to FAINT
            }
            addView(text(label, 20f, color, bold = true))
            if (s.transport.isNotBlank()) addView(text("Vía: ${s.transport}", 15f, DIM))
            if (s.detail.isNotBlank()) addView(text(s.detail, 15f, TEXT).apply { setPadding(0, dp(6), 0, 0) })
            val last = s.lastRoute.ifBlank { prefs.lastRouteSummary.orEmpty() }
            if (last.isNotBlank()) addView(text("Última ruta recibida: $last", 14f, FAINT).apply { setPadding(0, dp(8), 0, 0) })
            if (s.dataRequestsServed > 0) {
                addView(text("🌐 Datos servidos al coche por ${s.transport.ifBlank { "Bluetooth" }}: ${s.dataRequestsServed} petición(es) (${s.lastDataHost})", 14f, OK).apply {
                    setPadding(0, dp(6), 0, 0)
                })
            }
            if (prefs.isPaired) {
                addView(row(
                    pill(if (s.phase == BridgeState.Phase.STOPPED) "Conectar ahora" else "Reconectar", primary = true) {
                        prefs.enabled = true
                        BridgeService.start(this@MainActivity)
                    },
                    pill("Desconectar") {
                        prefs.enabled = false
                        BridgeService.stop(this@MainActivity)
                    },
                ).apply { setPadding(0, dp(12), 0, 0) })
            }
        })

        // --- Paso 1: coche por Bluetooth ---
        root.addView(card().apply {
            addView(text("1 · Tu coche por Bluetooth", 18f, TEXT, bold = true))
            val car = prefs.carBtName ?: prefs.carBtAddress
            addView(text(
                if (car != null) "Coche elegido: $car" else "Elige el Bluetooth del coche. Así la conexión funciona también con Android Auto por cable y se abre sola al subir al coche.",
                15f, if (car != null) OK else DIM,
            ).apply { setPadding(0, dp(6), 0, dp(10)) })
            addView(row(
                pill(if (car != null) "Cambiar coche" else "Elegir coche", primary = car == null) { associateCar() },
                pill("Lista de emparejados") { pickBondedDevice() },
            ))
        })

        // --- Paso 2: código ---
        root.addView(card().apply {
            addView(text("2 · Código del coche", 18f, TEXT, bold = true))
            addView(text(
                "En el coche: Ajustes y Menú Extendido → Qonecta Unit Companion → «Emparejar móvil». Escribe aquí las 6 cifras que salen.",
                15f, DIM,
            ).apply { setPadding(0, dp(6), 0, dp(10)) })
            val codeInput = EditText(this@MainActivity).apply {
                hint = "000000"
                setHintTextColor(FAINT)
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.3f
                gravity = Gravity.CENTER
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(6))
                background = rounded(SURFACE_TOP, dp(14), OUTLINE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            addView(codeInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)))
            addView(pill(if (prefs.isPaired) "Volver a emparejar" else "Emparejar", primary = true) {
                val code = codeInput.text?.toString()?.trim().orEmpty()
                if (code.length != 6) {
                    toast("Escribe las 6 cifras del coche")
                } else {
                    BridgeService.pair(this@MainActivity, code)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) })
        })

        // --- Servidor de Datos Bluetooth + Punto de Acceso ---
        root.addView(card().apply {
            addView(text("🌐 Servidor de Datos Bluetooth · Planificador y Coche", 18f, TEXT, bold = true))
            addView(text(
                "Mientras estás conectado por Bluetooth (incluso usando Cable USB o Wi-Fi Direct 5 GHz sin Hotspot), este servidor comparte Internet del móvil con el Planificador de Rutas de Qonecta Unit sin gastar los 2 GB del coche.\n" +
                    "Si además quieres dar Internet a otras apps del coche por Bluetooth PAN o Hotspot Wi-Fi, puedes abrirlo aquí en 1 toque:",
                14f, DIM,
            ).apply { setPadding(0, dp(4), 0, dp(10)) })
            addView(text(
                if (s.phase == BridgeState.Phase.CONNECTED)
                    "● Servidor de datos Bluetooth ACTIVO · Peticiones servidas: ${s.dataRequestsServed}"
                else
                    "● Servidor de datos listo (se activa automáticamente al conectar con el coche por Bluetooth)",
                14f, if (s.phase == BridgeState.Phase.CONNECTED) OK else ACCENT, bold = true,
            ).apply { setPadding(0, 0, 0, dp(10)) })
            addView(row(
                pill("🔵 Tethering Bluetooth / Hotspot", primary = true) { openTetherSettings() },
                pill("📶 Ajustes de Red") {
                    runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                },
            ))
        })

        // --- Permisos ---
        root.addView(card().apply {
            addView(text("Permisos", 18f, TEXT, bold = true))
            addView(text("Sin ellos la ruta llega como notificación y tienes que tocarla.", 14f, FAINT).apply { setPadding(0, dp(4), 0, dp(8)) })
            if (Build.VERSION.SDK_INT >= 33) {
                addView(permRow("Notificaciones", has(Manifest.permission.POST_NOTIFICATIONS)) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERMS)
                })
            }
            if (Build.VERSION.SDK_INT >= 31) {
                addView(permRow("Dispositivos cercanos (Bluetooth)", has(Manifest.permission.BLUETOOTH_CONNECT)) {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_PERMS)
                })
            }
            addView(permRow("Mostrar sobre otras apps (abrir Maps solo)", Settings.canDrawOverlays(this@MainActivity)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            })
            val pm = getSystemService(PowerManager::class.java)
            addView(permRow("Sin restricción de batería", pm?.isIgnoringBatteryOptimizations(packageName) == true) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            })
        })

        // --- Actualizador desde GitHub Releases ---
        root.addView(card().apply {
            addView(text("🐙 Actualizador · GitHub Releases", 18f, TEXT, bold = true))
            addView(text(
                "Consulta los últimos APK publicados en GitHub Releases para actualizar este Companion o descargar y servir el APK de Qonecta Unit al coche por Wi-Fi/Hotspot.",
                14f, DIM,
            ).apply { setPadding(0, dp(4), 0, dp(10)) })

            val repoInput = EditText(this@MainActivity).apply {
                hint = "CharlysEV/qonecta-unit"
                setHintTextColor(FAINT)
                setText(prefs.githubRepo)
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setSingleLine(true)
                background = rounded(SURFACE_TOP, dp(14), OUTLINE)
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            addView(repoInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

            val ghStatusTv = text(
                GitHubReleaseUpdater.hostedApkUrl?.let { "📡 Sirviendo APK al coche en: $it" }
                    ?: "Repositorio listo para buscar releases (.apk).",
                14f,
                if (GitHubReleaseUpdater.hostedApkUrl != null) OK else FAINT,
            ).apply { setPadding(0, dp(8), 0, dp(8)) }
            addView(ghStatusTv)

            val assetsContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(assetsContainer)

            addView(pill("🐙 Buscar APK en GitHub Releases", primary = true) {
                val repo = GitHubReleaseUpdater.normalizeRepo(repoInput.text?.toString().orEmpty())
                prefs.githubRepo = repo
                repoInput.setText(repo)
                ghStatusTv.text = "⏳ Consultando releases de $repo…"
                ghStatusTv.setTextColor(ACCENT)
                assetsContainer.removeAllViews()
                GitHubReleaseUpdater.fetchReleases(repo) { res ->
                    res.onSuccess { list ->
                        if (list.isEmpty()) {
                            ghStatusTv.text = "No se encontraron archivos .apk en los releases de $repo."
                            ghStatusTv.setTextColor(WARN)
                        } else {
                            ghStatusTv.text = "✓ ${list.size} archivo(s) .apk encontrados en $repo:"
                            ghStatusTv.setTextColor(OK)
                            list.forEach { asset ->
                                val itemBox = LinearLayout(this@MainActivity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    background = rounded(BG, dp(14), OUTLINE)
                                    setPadding(dp(12), dp(10), dp(12), dp(10))
                                    layoutParams = LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                    ).apply { topMargin = dp(8) }
                                }
                                val badge = when {
                                    asset.isCompanionApk -> "📱 Móvil"
                                    asset.isCarApk -> "🚗 Coche"
                                    else -> "📦 APK"
                                }
                                itemBox.addView(text("$badge · ${asset.assetName}", 15f, TEXT, bold = true))
                                itemBox.addView(text(
                                    String.format(
                                        java.util.Locale.US,
                                        "%s (%s) · %.1f MB",
                                        asset.tagName,
                                        asset.publishedAt.ifBlank { asset.releaseName },
                                        asset.sizeMb,
                                    ),
                                    13f, DIM,
                                ).apply { setPadding(0, dp(2), 0, dp(8)) })

                                itemBox.addView(row(
                                    pill("📲 Instalar en móvil", primary = asset.isCompanionApk) {
                                        ghStatusTv.text = "⏳ Descargando ${asset.assetName}…"
                                        ghStatusTv.setTextColor(ACCENT)
                                        GitHubReleaseUpdater.downloadAsset(
                                            this@MainActivity,
                                            asset,
                                            onProgress = { msg -> ghStatusTv.text = msg },
                                            onDone = { dl ->
                                                dl.onSuccess { file ->
                                                    GitHubReleaseUpdater.installApkOnPhone(this@MainActivity, file) { st ->
                                                        ghStatusTv.text = st
                                                    }
                                                }.onFailure { err ->
                                                    ghStatusTv.text = "⚠️ Error al descargar: ${err.message}"
                                                    ghStatusTv.setTextColor(BAD)
                                                }
                                            },
                                        )
                                    },
                                    pill("📡 Servir al coche", primary = asset.isCarApk) {
                                        ghStatusTv.text = "⏳ Descargando ${asset.assetName} para el coche…"
                                        ghStatusTv.setTextColor(ACCENT)
                                        GitHubReleaseUpdater.downloadAsset(
                                            this@MainActivity,
                                            asset,
                                            onProgress = { msg -> ghStatusTv.text = msg },
                                            onDone = { dl ->
                                                dl.onSuccess { file ->
                                                    GitHubReleaseUpdater.startHostingApkForCar(file) { st ->
                                                        ghStatusTv.text = st
                                                        ghStatusTv.setTextColor(OK)
                                                    }
                                                }.onFailure { err ->
                                                    ghStatusTv.text = "⚠️ Error al descargar: ${err.message}"
                                                    ghStatusTv.setTextColor(BAD)
                                                }
                                            },
                                        )
                                    },
                                ))
                                assetsContainer.addView(itemBox)
                            }
                        }
                    }.onFailure { err ->
                        ghStatusTv.text = "⚠️ No se pudo consultar GitHub: ${err.message}"
                        ghStatusTv.setTextColor(BAD)
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))
        })

        // --- Pruebas y olvidar ---
        root.addView(card().apply {
            addView(text("Pruebas", 18f, TEXT, bold = true))
            addView(text("«Probar Google Maps» abre en este móvil una ruta de prueba con una parada, con el mismo enlace que manda el coche.", 14f, FAINT).apply { setPadding(0, dp(4), 0, dp(10)) })
            addView(row(
                pill("Probar Google Maps") { testMaps() },
                pill("Olvidar coche") { confirmForget() },
            ))
        })
    }

    // ------------------------------------------------------------------------------------------
    // Acciones
    // ------------------------------------------------------------------------------------------

    private fun associateCar() {
        val cdm = getSystemService(CompanionDeviceManager::class.java)
        if (cdm == null) {
            pickBondedDevice()
            return
        }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            @Deprecated("Sigue llamándose en API 33+ desde onAssociationPending")
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                runCatching { startIntentSenderForResult(chooserLauncher, REQ_ASSOCIATE, null, 0, 0, 0) }
                    .onFailure { toast("No se pudo abrir el selector: ${it.message}") }
            }

            override fun onFailure(error: CharSequence?) {
                toast("No se encontró el coche${error?.let { ": $it" } ?: ""}. Prueba con «Lista de emparejados».")
            }
        }
        runCatching { cdm.associate(request, callback, null) }
            .onFailure {
                toast("Este móvil no permite asociar: uso la lista de emparejados")
                pickBondedDevice()
            }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_ASSOCIATE || resultCode != RESULT_OK || data == null) return
        var address: String? = null
        var name: String? = null
        if (Build.VERSION.SDK_INT >= 33) {
            val info: AssociationInfo? = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            address = info?.deviceMacAddress?.toString()?.uppercase()
            name = info?.displayName?.toString()
        }
        val device: BluetoothDevice? = data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
        if (address == null) address = device?.address
        if (name == null) name = runCatching { device?.name }.getOrNull()
        if (address == null) {
            toast("No llegó la dirección del coche")
            return
        }
        saveCar(address, name)
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { getSystemService(CompanionDeviceManager::class.java)?.startObservingDevicePresence(address) }
        }
        toast("Coche asociado: ${name ?: address}")
    }

    private fun pickBondedDevice() {
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_PERMS)
            toast("Da permiso de Dispositivos cercanos y vuelve a pulsar")
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val bonded = runCatching { adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
        if (bonded.isEmpty()) {
            toast("No hay dispositivos Bluetooth emparejados")
            return
        }
        val labels = bonded.map { d -> "${runCatching { d.name }.getOrNull() ?: "Sin nombre"}\n${d.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("¿Cuál es tu coche?")
            .setItems(labels) { _, i ->
                val d = bonded[i]
                saveCar(d.address, runCatching { d.name }.getOrNull())
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun saveCar(address: String, name: String?) {
        prefs.carBtAddress = address
        prefs.carBtName = name
        render()
        if (prefs.isPaired) BridgeService.start(this)
    }

    private fun testMaps() {
        val dest = NavPoint(42.880447, -8.545916, "Catedral de Santiago")
        val via = listOf(NavPoint(41.656060, -4.723680, "Parada de prueba (Valladolid)"))
        val built = MapsRoute.build(dest, via)
        val intent = MapsLauncher.buildIntent(this, built)
        runCatching { startActivity(intent) }.onFailure { toast("No hay Google Maps instalado") }
    }

    private fun openTetherSettings() {
        val tetherIntent = Intent().apply {
            setClassName("com.android.settings", "com.android.settings.TetherSettings")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(tetherIntent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        }
    }

    private fun confirmForget() {
        AlertDialog.Builder(this)
            .setTitle("¿Olvidar el coche?")
            .setMessage("Tendrás que volver a emparejar con un código nuevo.")
            .setPositiveButton("Olvidar") { _, _ ->
                prefs.forgetCar()
                BridgeService.stop(this)
                BridgeState.update { BridgeState.Snapshot() }
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    // ------------------------------------------------------------------------------------------
    // Piezas de interfaz (mismos colores que Qonecta+)
    // ------------------------------------------------------------------------------------------

    private fun has(perm: String) = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, dp(20), OUTLINE)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        }
    }

    private fun pill(label: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(if (primary) ON_ACCENT else TEXT)
        background = if (primary) rounded(ACCENT, dp(26)) else rounded(SURFACE_TOP, dp(26), OUTLINE)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(10)
            })
        }
    }

    private fun permRow(label: String, granted: Boolean, onFix: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(text((if (granted) "✓ " else "✗ ") + label, 15f, if (granted) OK else BAD),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!granted) addView(pill("Dar") { onFix() })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
