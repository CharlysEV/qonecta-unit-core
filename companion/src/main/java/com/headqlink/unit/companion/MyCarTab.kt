package com.headqlink.unit.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.headqlink.unit.companion.cloud.CompanionCloud
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pestaña «Mi coche» con datos de verdad:
 * - Conectado a Qonecta Unit → batería, autonomía, potencia y clima vienen del coche (manda Unit); la nube no se lee.
 * - Desconectado → todo viene de la cuenta Leapmotor (sondeo cada 2 min con la app abierta).
 * - Órdenes remotas → siempre por la cuenta Leapmotor (también nada más bajarte del coche).
 * Puertas, maletero, cierre y presiones solo los da la nube: Qonecta Unit todavía no los lee del coche.
 */
object MyCarTab {

    const val REQ_CERT = 21

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
    private const val WARN = 0xFFFFB547.toInt()
    private const val BAD = 0xFFFF6B7A.toInt()

    private var webView: WebView? = null
    @Volatile
    private var webReady = false
    private var paintHex = "#123c29"
    @Volatile
    private var busy = false

    // =========================================================================

    fun build(activity: Activity, root: LinearLayout, onRefresh: () -> Unit) {
        val cloud = CompanionCloud.current
        val unit = UnitTelemetry.current
        val cd = cloud.data
        val bridge = BridgeState.current
        val connected = bridge.phase == BridgeState.Phase.CONNECTED

        // ---------------- Cabecera + quién manda
        val appVer = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull() ?: "0.1.7"
        root.addView(text(activity, "Leapmotor C10  ·  v$appVer", 25f, TEXT, bold = true))
        val sourceLine = when {
            connected && unit != null -> "● Manda Qonecta Unit · datos en vivo del coche (${bridge.transport})" to OK
            connected -> "● Conectado a Qonecta Unit · esperando datos del coche…" to ACCENT
            cloud.state == CompanionCloud.State.OK -> "● Manda tu cuenta Leapmotor · dato del coche ${ago(cd?.carTimeMs ?: cloud.fetchedAtMs)}" to OK
            else -> "● Sin conexión con el coche · ${cloud.message.ifBlank { "configura tu cuenta Leapmotor" }}" to WARN
        }
        root.addView(text(activity, sourceLine.first, 13f, sourceLine.second, bold = true).apply { setPadding(0, dp(activity, 2), 0, 0) })

        // ---------------- Batería y autonomía
        val soc = unit?.socPct?.takeUnless { it.isNaN() } ?: cd?.socPct?.takeUnless { it.isNaN() }
        val range = unit?.rangeKm?.takeUnless { it.isNaN() } ?: cd?.rangeKm?.takeUnless { it.isNaN() }
        root.addView(card(activity).apply {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            row.addView(text(activity, range?.let { "${it.roundToInt()}" } ?: "—", 42f, TEXT, bold = true))
            row.addView(text(activity, " km  ·  " + (soc?.let { "${it.roundToInt()} %" } ?: "— %"), 18f, ACCENT, bold = true).apply {
                setPadding(0, 0, 0, dp(activity, 6))
            })
            addView(row)
            val lockLine = lockSummary(cd)
            addView(text(activity, lockLine.first, 13.5f, lockLine.second, bold = true))
            if (unit != null) {
                val p = unit.powerKw
                val clima = if (unit.acOn && !unit.hvacKw.isNaN()) String.format(Locale.getDefault(), " · clima %.2f kW", unit.hvacKw) else ""
                addView(text(
                    activity,
                    (if (!p.isNaN()) String.format(Locale.getDefault(), "Potencia %+.1f kW", p) else "Potencia —") + clima +
                        (if (!unit.cabinC.isNaN()) String.format(Locale.getDefault(), " · interior %.1f °C", unit.cabinC) else ""),
                    13f, DIM,
                ).apply { setPadding(0, dp(activity, 4), 0, 0) })
            } else if (cd != null && (cd.charging || cd.pluggedIn)) {
                addView(text(
                    activity,
                    if (cd.charging) "⚡ Cargando" + (if (!cd.chargePowerKw.isNaN()) String.format(Locale.getDefault(), " a %.1f kW", cd.chargePowerKw) else "") else "🔌 Enchufado",
                    13f, OK, bold = true,
                ).apply { setPadding(0, dp(activity, 4), 0, 0) })
            }
        })

        // ---------------- Modelo 3D con puertas y presiones reales (de la nube)
        root.addView(card(activity).apply {
            setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
            addView(text(activity, "PUERTAS Y PRESIONES", 12.5f, ACCENT, bold = true))
            addView(text(
                activity,
                if (cd != null) "Según tu cuenta Leapmotor · ${ago(cd.carTimeMs.takeIf { it > 0 } ?: cloud.fetchedAtMs)}" else "Sin datos: aparecerán al configurar la cuenta Leapmotor",
                12f, if (cd != null) DIM else WARN,
            ).apply { setPadding(0, dp(activity, 2), 0, 0) })
            val wv = obtainWebView(activity)
            (wv.parent as? ViewGroup)?.removeView(wv)
            val frame = FrameLayout(activity).apply { background = rounded(activity, BG, dp(activity, 16), OUTLINE) }
            frame.addView(wv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(activity, 250)))
            addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 250)).apply { topMargin = dp(activity, 8) })
            push3d()
        })

        // ---------------- Órdenes remotas (siempre por la cuenta)
        root.addView(card(activity).apply {
            addView(text(activity, "CONTROL REMOTO · CUENTA LEAPMOTOR", 12.5f, ACCENT, bold = true))
            val ready = cloud.state !in setOf(
                CompanionCloud.State.NO_CERT, CompanionCloud.State.NO_ACCOUNT,
                CompanionCloud.State.NEEDS_VEHICLE, CompanionCloud.State.EXPIRED,
            )
            val canSend = ready && cloud.hasPin
            addView(text(
                activity,
                when {
                    !ready -> "Configura la cuenta abajo para poder abrir y cerrar el coche desde el móvil."
                    !cloud.hasPin -> "Guarda el PIN del coche (abajo) para enviar órdenes."
                    busy -> "Enviando orden al coche…"
                    else -> "Las órdenes van por la nube de Leapmotor, estés o no conectado a Qonecta Unit."
                },
                13f, if (canSend) DIM else WARN,
            ).apply { setPadding(0, dp(activity, 4), 0, dp(activity, 10)) })

            fun order(label: String, o: CompanionCloud.Order, primary: Boolean = false, confirm: Boolean = false, temp: Int = 22) =
                pill(activity, label, primary = primary && canSend, enabled = canSend && !busy) {
                    val go = { sendOrder(activity, o, temp, onRefresh) }
                    if (confirm) {
                        AlertDialog.Builder(activity)
                            .setTitle(CompanionCloud.orderLabel(o, temp))
                            .setMessage("¿Seguro? La orden se envía al coche por la nube de Leapmotor.")
                            .setPositiveButton("Enviar") { _, _ -> go() }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    } else go()
                }

            addView(row(activity, order("🔒 Cerrar", CompanionCloud.Order.LOCK, primary = true), order("🔓 Abrir", CompanionCloud.Order.UNLOCK, confirm = true)))
            addView(row(activity, order("🧳 Abrir maletero", CompanionCloud.Order.TRUNK_OPEN, confirm = true), order("❄️ Clima 22 °C", CompanionCloud.Order.CLIMATE_ON)).apply { setPadding(0, dp(activity, 8), 0, 0) })
            addView(row(activity, order("⛔ Apagar clima", CompanionCloud.Order.CLIMATE_OFF), order("🌫️ Desempañar", CompanionCloud.Order.DEFROST)).apply { setPadding(0, dp(activity, 8), 0, 0) })
            addView(row(activity, order("📍 Localizar", CompanionCloud.Order.FIND), order("🔌 Soltar cable", CompanionCloud.Order.CABLE_UNLOCK, confirm = true)).apply { setPadding(0, dp(activity, 8), 0, 0) })
        })

        // ---------------- Tarifas de energía y combustible (Casa CA · Rápida CC · Gasolina · Diésel · REEV)
        root.addView(LeapCompanionHub.buildEnergyPricingCard(activity, onRefresh))

        // ---------------- Cuenta Leapmotor
        root.addView(accountCard(activity, onRefresh))
    }

    private fun lockSummary(cd: CompanionCloud.CarData?): Pair<String, Int> {
        if (cd == null) return "Cierre y puertas: sin dato (necesita la cuenta Leapmotor)" to FAINT
        val anyDoor = cd.doorsOpen.any { it == true }
        val boot = cd.bootOpen == true
        return when {
            anyDoor || boot -> "🔓 " + listOfNotNull(if (anyDoor) "puerta abierta" else null, if (boot) "maletero abierto" else null).joinToString(" y ") to WARN
            cd.locked == true -> "🔒 Cerrado" + (if (!cd.odometerKm.isNaN()) String.format(Locale.getDefault(), " · %,.0f km", cd.odometerKm) else "") to OK
            cd.locked == false -> "🔓 Abierto (sin cerrar)" to WARN
            else -> "Cierre: sin dato" to FAINT
        }
    }

    private fun sendOrder(activity: Activity, o: CompanionCloud.Order, temp: Int, onRefresh: () -> Unit) {
        busy = true
        onRefresh()
        CompanionCloud.send(o, temp) { r ->
            busy = false
            val label = CompanionCloud.orderLabel(o, temp)
            val msg = r.fold(
                onSuccess = { ok -> if (ok) "✓ $label: confirmado por el coche" else "$label: enviada, pero el coche no la confirmó a tiempo" },
                onFailure = { e -> "✗ $label: ${CompanionCloud.describe(e)}" },
            )
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            onRefresh()
        }
    }

    // ---------------------------------------------------------------- cuenta

    private fun accountCard(activity: Activity, onRefresh: () -> Unit) = card(activity).apply {
        val c = CompanionCloud.current
        addView(text(activity, "CUENTA LEAPMOTOR", 12.5f, ACCENT, bold = true))
        val (statusText, color) = when (c.state) {
            CompanionCloud.State.NO_CERT -> "Paso 1 de 3: importa el certificado de cliente" to WARN
            CompanionCloud.State.NO_ACCOUNT -> "Paso 2 de 3: entra con tu cuenta" to WARN
            CompanionCloud.State.NEEDS_VEHICLE -> "Paso 3 de 3: elige tu coche" to WARN
            CompanionCloud.State.EXPIRED -> "Sesión caducada: vuelve a entrar" to BAD
            CompanionCloud.State.SERVER_KEY -> "Clave del servidor desconocida" to BAD
            CompanionCloud.State.ERROR -> "Último intento fallido" to WARN
            CompanionCloud.State.PAUSED_BY_UNIT -> "Activa · en pausa mientras manda Qonecta Unit" to OK
            CompanionCloud.State.WAITING -> "Activa · leyendo…" to ACCENT
            CompanionCloud.State.OK -> "Activa · ${c.maskedEmail}" to OK
        }
        addView(text(activity, statusText, 15f, color, bold = true).apply { setPadding(0, dp(activity, 4), 0, 0) })
        if (c.message.isNotBlank()) addView(text(activity, c.message, 13f, DIM).apply { setPadding(0, dp(activity, 2), 0, dp(activity, 8)) })

        when (c.state) {
            CompanionCloud.State.NO_CERT -> {
                addView(text(activity, "Es el mismo certificado que usas en Qonecta+. Elige el .crt y el .key a la vez, o un único .p12/.pfx.", 12.5f, FAINT).apply { setPadding(0, 0, 0, dp(activity, 8)) })
                addView(pill(activity, "📄 Importar certificado", primary = true) { pickCertificate(activity) })
            }
            CompanionCloud.State.NO_ACCOUNT, CompanionCloud.State.EXPIRED -> {
                addView(pill(activity, "🔑 Entrar con mi cuenta Leapmotor", primary = true) { showLogin(activity, onRefresh) })
                addView(pill(activity, "📄 Cambiar certificado") { pickCertificate(activity) }, lp(activity, top = 8))
            }
            CompanionCloud.State.NEEDS_VEHICLE -> addView(pill(activity, "🚗 Elegir coche", primary = true) { pickVehicle(activity, onRefresh) })
            CompanionCloud.State.SERVER_KEY -> addView(pill(activity, "Confiar en la clave nueva (${c.serverKeyFingerprint.take(17)}…)", primary = true) {
                AlertDialog.Builder(activity)
                    .setTitle("Clave del servidor de Leapmotor")
                    .setMessage("El servidor presenta otra clave (huella ${c.serverKeyFingerprint}). Acéptala solo si sabes que Leapmotor ha cambiado su certificado.")
                    .setPositiveButton("Confiar") { _, _ -> CompanionCloud.acceptServerKey { onRefresh() } }
                    .setNegativeButton("Cancelar", null)
                    .show()
            })
            else -> {
                addView(row(
                    activity,
                    pill(activity, if (c.hasPin) "🔢 Cambiar PIN" else "🔢 Guardar PIN", primary = !c.hasPin) { showPin(activity, onRefresh) },
                    pill(activity, "🔄 Leer ahora", enabled = !CompanionCloud.unitIsMaster) {
                        CompanionCloud.readNow { ok -> Toast.makeText(activity, if (ok) "Estado leído" else CompanionCloud.current.message, Toast.LENGTH_SHORT).show(); onRefresh() }
                    },
                ))
                addView(row(
                    activity,
                    pill(activity, "🚗 Cambiar coche") { pickVehicle(activity, onRefresh) },
                    pill(activity, "Cerrar sesión") {
                        AlertDialog.Builder(activity)
                            .setTitle("¿Cerrar la sesión de Leapmotor?")
                            .setMessage("El certificado se queda guardado. Tendrás que volver a poner tu contraseña.")
                            .setPositiveButton("Cerrar sesión") { _, _ -> CompanionCloud.logout() }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    },
                ).apply { setPadding(0, dp(activity, 8), 0, 0) })
            }
        }
        addView(text(activity, "La contraseña no se guarda. El certificado, la sesión y el PIN se guardan cifrados con el Keystore del móvil.", 11.5f, FAINT).apply { setPadding(0, dp(activity, 10), 0, 0) })
    }

    private fun pickCertificate(activity: Activity) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        runCatching { activity.startActivityForResult(i, REQ_CERT) }
            .onFailure { Toast.makeText(activity, "No hay selector de archivos en este móvil", Toast.LENGTH_LONG).show() }
    }

    fun onCertPicked(activity: Activity, data: Intent?, onRefresh: () -> Unit) {
        val uris = mutableListOf<Uri>()
        data?.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let(uris::add) }
        if (uris.isEmpty()) data?.data?.let(uris::add)
        if (uris.isEmpty()) return
        val files = uris.mapNotNull { u -> runCatching { displayName(activity, u) to readUri(activity, u) }.getOrNull() }
        if (files.isEmpty()) {
            Toast.makeText(activity, "No se pudo leer el archivo", Toast.LENGTH_LONG).show()
            return
        }
        importWith(activity, files, null, onRefresh)
    }

    private fun importWith(activity: Activity, files: List<Pair<String, ByteArray>>, pw: CharArray?, onRefresh: () -> Unit) {
        CompanionCloud.importCertificate(files, pw) { r ->
            when (r) {
                CompanionCloud.ImportResult.Ok -> Toast.makeText(activity, "✓ Certificado guardado", Toast.LENGTH_SHORT).show()
                CompanionCloud.ImportResult.NeedsPassword, CompanionCloud.ImportResult.WrongPassword -> {
                    val et = EditText(activity).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; hint = "Contraseña del certificado" }
                    AlertDialog.Builder(activity)
                        .setTitle(if (r == CompanionCloud.ImportResult.WrongPassword) "Contraseña incorrecta" else "El certificado tiene contraseña")
                        .setView(et)
                        .setPositiveButton("Probar") { _, _ -> importWith(activity, files, et.text.toString().toCharArray(), onRefresh) }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
                is CompanionCloud.ImportResult.Failed -> Toast.makeText(activity, r.why, Toast.LENGTH_LONG).show()
            }
            onRefresh()
        }
    }

    private fun showLogin(activity: Activity, onRefresh: () -> Unit) {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), 0) }
        val email = EditText(activity).apply { hint = "Correo de Leapmotor"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val pass = EditText(activity).apply { hint = "Contraseña"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(email)
        box.addView(pass)
        AlertDialog.Builder(activity)
            .setTitle("Entrar en Leapmotor")
            .setView(box)
            .setPositiveButton("Entrar") { _, _ ->
                val m = email.text.toString().trim()
                val p = pass.text.toString()
                if (m.isEmpty() || p.isEmpty()) {
                    Toast.makeText(activity, "Escribe correo y contraseña", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Toast.makeText(activity, "Entrando…", Toast.LENGTH_SHORT).show()
                CompanionCloud.login(m, p) { r ->
                    r.onSuccess { cars ->
                        when {
                            cars.isEmpty() -> Toast.makeText(activity, "La cuenta no tiene coches", Toast.LENGTH_LONG).show()
                            cars.size > 1 -> pickFrom(activity, cars, onRefresh)
                            else -> Toast.makeText(activity, "✓ Sesión iniciada · ${cars[0].label}", Toast.LENGTH_LONG).show()
                        }
                    }.onFailure { e -> Toast.makeText(activity, "No se pudo entrar: ${CompanionCloud.describe(e)}", Toast.LENGTH_LONG).show() }
                    onRefresh()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickVehicle(activity: Activity, onRefresh: () -> Unit) {
        CompanionCloud.vehicles { r ->
            r.onSuccess { pickFrom(activity, it, onRefresh) }
                .onFailure { e -> Toast.makeText(activity, CompanionCloud.describe(e), Toast.LENGTH_LONG).show() }
        }
    }

    private fun pickFrom(activity: Activity, cars: List<CompanionCloud.Vehicle>, onRefresh: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("¿Cuál es tu coche?")
            .setItems(cars.map { it.label }.toTypedArray()) { _, i ->
                CompanionCloud.chooseVehicle(cars[i]) { r ->
                    r.onFailure { e -> Toast.makeText(activity, CompanionCloud.describe(e), Toast.LENGTH_LONG).show() }
                    onRefresh()
                }
            }
            .show()
    }

    private fun showPin(activity: Activity, onRefresh: () -> Unit) {
        val et = EditText(activity).apply { hint = "PIN del coche (app Leapmotor)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        AlertDialog.Builder(activity)
            .setTitle("PIN de las órdenes remotas")
            .setMessage("El mismo PIN que pide la app oficial de Leapmotor para abrir, cerrar o poner el clima. Se guarda cifrado.")
            .setView(et)
            .setPositiveButton("Guardar") { _, _ ->
                val ok = CompanionCloud.savePin(et.text.toString().trim())
                Toast.makeText(activity, if (ok) "✓ PIN guardado" else "El PIN son de 4 a 12 cifras", Toast.LENGTH_SHORT).show()
                onRefresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- 3D

    @SuppressLint("SetJavaScriptEnabled")
    private fun obtainWebView(activity: Activity): WebView {
        webView?.let { if (it.context === activity) return it }
        webReady = false
        val wv = WebView(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = true
            addJavascriptInterface(object {
                @JavascriptInterface fun ready() { webReady = true; post { push3d() } }
                @JavascriptInterface fun color(hex: String?) { if (!hex.isNullOrBlank()) paintHex = hex }
                // Las puertas del dibujo reflejan el coche real: un toque no las abre (se vuelve a pintar el estado real).
                @JavascriptInterface fun doorToggled(@Suppress("UNUSED_PARAMETER") i: Int, @Suppress("UNUSED_PARAMETER") open: Boolean) { post { push3d() } }
                @JavascriptInterface fun bootToggled(@Suppress("UNUSED_PARAMETER") open: Boolean) { post { push3d() } }
                @JavascriptInterface fun snapshot(@Suppress("UNUSED_PARAMETER") d: String?) {}
                @JavascriptInterface fun failed(@Suppress("UNUSED_PARAMETER") why: String?) {}
            }, "HQL")
            loadUrl("file:///android_asset/car3d.html")
        }
        webView = wv
        return wv
    }

    fun push3d() {
        val wv = webView ?: return
        if (!webReady) return
        val cd = CompanionCloud.current.data
        val tags = arrayOf("Del. izq", "Del. der", "Tras. izq", "Tras. der")
        val tyres = JSONArray()
        for (i in 0..3) {
            val bar = cd?.tyreBars?.getOrNull(i) ?: Double.NaN
            tyres.put(
                JSONObject()
                    .put("text", if (bar.isNaN()) "—" else String.format(Locale.getDefault(), "%.2f", bar))
                    .put("unit", "bar")
                    .put("warn", cd?.tyreWarn?.getOrNull(i) == true)
                    .put("tag", tags[i]),
            )
        }
        val doors = JSONArray()
        for (i in 0..3) doors.put(cd?.doorsOpen?.getOrNull(i) == true)
        val payload = JSONObject()
            .put("tyres", tyres)
            .put("doors", doors)
            .put("boot", cd?.bootOpen == true)
            .put("color", paintHex)
            .put("hint", "")
        wv.evaluateJavascript("if(window.update)update($payload);", null)
    }

    // ---------------------------------------------------------------- utilidades

    private fun ago(ms: Long): String {
        if (ms <= 0) return "—"
        val s = (System.currentTimeMillis() - ms).coerceAtLeast(0) / 1000
        return when {
            s < 60 -> "hace ${s} s"
            s < 3600 -> "hace ${s / 60} min"
            s < 172800 -> "hace ${s / 3600} h"
            else -> "hace ${s / 86400} d"
        }
    }

    private fun displayName(ctx: Context, u: Uri): String = runCatching {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: (u.lastPathSegment ?: "archivo")

    private fun readUri(ctx: Context, u: Uri): ByteArray {
        ctx.contentResolver.openInputStream(u).use { input ->
            requireNotNull(input)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (out.size() > 512 * 1024) break
            }
            return out.toByteArray()
        }
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun lp(ctx: Context, top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, top) }

    private fun text(ctx: Context, s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun rounded(ctx: Context, fill: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(ctx, 1), stroke)
    }

    private fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, SURFACE, dp(ctx, 20), OUTLINE)
        setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        layoutParams = lp(ctx, top = 12)
    }

    private fun pill(ctx: Context, label: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(if (primary) ON_ACCENT else TEXT)
        background = if (primary) rounded(ctx, ACCENT, dp(ctx, 24)) else rounded(ctx, SURFACE_TOP, dp(ctx, 24), OUTLINE)
        setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11))
        alpha = if (enabled) 1f else 0.4f
        isEnabled = enabled
        isClickable = enabled
        setOnClickListener { if (enabled) onClick() }
    }

    private fun row(ctx: Context, vararg views: TextView) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(ctx, 8) })
        }
    }
}
