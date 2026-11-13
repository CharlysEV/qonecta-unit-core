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
import com.headqlink.unit.companion.CompanionI18n.tr
import com.headqlink.unit.companion.cloud.CompanionCloud
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pestaña «Mi coche» con datos reales multi-idioma (ES / EN / DE / PT):
 * - Conectado a Qonecta Unit -> batería, autonomía, potencia y clima vienen del coche (manda Unit); la nube no se lee.
 * - Desconectado -> todo viene de la cuenta Leapmotor (sondeo cada 2 min con la app abierta).
 * - Órdenes remotas -> siempre por la cuenta Leapmotor (también nada más bajarte del coche).
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
        val appVer = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull() ?: "0.1.19"
        root.addView(text(activity, "Leapmotor C10  ·  v$appVer", 25f, TEXT, bold = true))
        val sourceLine = when {
            connected && unit != null -> tr(
                "● Manda Qonecta Unit · datos en vivo del coche (${bridge.transport})",
                "● Driven by Qonecta Unit · live car data (${bridge.transport})",
                "● Qonecta Unit aktiv · Live-Daten vom Fahrzeug (${bridge.transport})",
                "● Qonecta Unit ativo · dados em direto do carro (${bridge.transport})",
            ) to OK
            connected -> tr(
                "● Conectado a Qonecta Unit · esperando datos del coche…",
                "● Connected to Qonecta Unit · waiting for car data…",
                "● Mit Qonecta Unit verbunden · warte auf Fahrzeugdaten…",
                "● Ligado ao Qonecta Unit · a aguardar dados do carro…",
            ) to ACCENT
            cloud.state == CompanionCloud.State.OK -> {
                val rel = ago(cd?.carTimeMs ?: cloud.fetchedAtMs)
                tr(
                    "● Manda tu cuenta Leapmotor · dato del coche $rel",
                    "● Driven by your Leapmotor account · car update $rel",
                    "● Über dein Leapmotor-Konto · Fahrzeugdaten $rel",
                    "● Conta Leapmotor ativa · dados do carro $rel",
                ) to OK
            }
            else -> {
                val fallback = cloud.message.ifBlank {
                    tr(
                        "configura tu cuenta Leapmotor",
                        "set up your Leapmotor account",
                        "richte dein Leapmotor-Konto ein",
                        "configura a tua conta Leapmotor",
                    )
                }
                tr(
                    "● Sin conexión con el coche · $fallback",
                    "● Not connected to the car · $fallback",
                    "● Keine Verbindung zum Fahrzeug · $fallback",
                    "● Sem ligação ao carro · $fallback",
                ) to WARN
            }
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
                val clima = if (unit.acOn && !unit.hvacKw.isNaN()) {
                    val hw = String.format(Locale.getDefault(), "%.2f", unit.hvacKw)
                    tr(" · clima $hw kW", " · HVAC $hw kW", " · Klima $hw kW", " · clima $hw kW")
                } else ""
                val pLabel = if (!p.isNaN()) {
                    val pv = String.format(Locale.getDefault(), "%+.1f", p)
                    tr("Potencia $pv kW", "Power $pv kW", "Leistung $pv kW", "Potência $pv kW")
                } else {
                    tr("Potencia —", "Power —", "Leistung —", "Potência —")
                }
                val cab = if (!unit.cabinC.isNaN()) {
                    val cv = String.format(Locale.getDefault(), "%.1f", unit.cabinC)
                    tr(" · interior $cv °C", " · cabin $cv °C", " · Innenraum $cv °C", " · habitáculo $cv °C")
                } else ""
                addView(text(activity, pLabel + clima + cab, 13f, DIM).apply { setPadding(0, dp(activity, 4), 0, 0) })
            } else if (cd != null && (cd.charging || cd.pluggedIn)) {
                val chgTxt = if (cd.charging) {
                    val kwSuffix = if (!cd.chargePowerKw.isNaN()) {
                        val kw = String.format(Locale.getDefault(), "%.1f", cd.chargePowerKw)
                        tr(" a $kw kW", " at $kw kW", " mit $kw kW", " a $kw kW")
                    } else ""
                    tr("⚡ Cargando", "⚡ Charging", "⚡ Lädt", "⚡ A carregar") + kwSuffix
                } else {
                    tr("🔌 Enchufado", "🔌 Plugged in", "🔌 Angesteckt", "🔌 Ligado à tomada")
                }
                addView(text(activity, chgTxt, 13f, OK, bold = true).apply { setPadding(0, dp(activity, 4), 0, 0) })
            }
        })

        // ---------------- Modelo 3D con puertas y presiones reales (de la nube)
        root.addView(card(activity).apply {
            setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
            addView(text(
                activity,
                tr("PUERTAS Y PRESIONES", "DOORS & TYRE PRESSURES", "TÜREN & REIFENDRUCK", "PORTAS E PRESSÕES"),
                12.5f, ACCENT, bold = true,
            ))
            val sub3d = if (cd != null) {
                val rel = ago(cd.carTimeMs.takeIf { it > 0 } ?: cloud.fetchedAtMs)
                tr(
                    "Según tu cuenta Leapmotor · $rel",
                    "From your Leapmotor account · $rel",
                    "Laut deinem Leapmotor-Konto · $rel",
                    "Segundo a tua conta Leapmotor · $rel",
                )
            } else {
                tr(
                    "Sin datos: aparecerán al configurar la cuenta Leapmotor",
                    "No data yet: set up your Leapmotor account below",
                    "Keine Daten: erscheinen nach Einrichtung des Leapmotor-Kontos",
                    "Sem dados: aparecerão ao configurar a conta Leapmotor",
                )
            }
            addView(text(activity, sub3d, 12f, if (cd != null) DIM else WARN).apply { setPadding(0, dp(activity, 2), 0, 0) })
            val wv = obtainWebView(activity)
            (wv.parent as? ViewGroup)?.removeView(wv)
            val frame = FrameLayout(activity).apply { background = rounded(activity, BG, dp(activity, 16), OUTLINE) }
            frame.addView(wv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(activity, 250)))
            addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 250)).apply { topMargin = dp(activity, 8) })
            push3d()
        })

        // ---------------- Órdenes remotas (siempre por la cuenta)
        root.addView(card(activity).apply {
            addView(text(
                activity,
                tr(
                    "CONTROL REMOTO · CUENTA LEAPMOTOR",
                    "REMOTE CONTROL · LEAPMOTOR ACCOUNT",
                    "FERNSTEUERUNG · LEAPMOTOR-KONTO",
                    "CONTROLO REMOTO · CONTA LEAPMOTOR",
                ),
                12.5f, ACCENT, bold = true,
            ))
            val ready = cloud.state !in setOf(
                CompanionCloud.State.NO_CERT, CompanionCloud.State.NO_ACCOUNT,
                CompanionCloud.State.NEEDS_VEHICLE, CompanionCloud.State.EXPIRED,
            )
            val canSend = ready && cloud.hasPin
            addView(text(
                activity,
                when {
                    !ready -> tr(
                        "Configura la cuenta abajo para poder abrir y cerrar el coche desde el móvil.",
                        "Set up your account below to lock, unlock and control climate from your phone.",
                        "Richte unten dein Konto ein, um das Fahrzeug vom Smartphone aus zu steuern.",
                        "Configura a conta abaixo para poderes abrir e fechar o carro a partir do telemóvel.",
                    )
                    !cloud.hasPin -> tr(
                        "Guarda el PIN del coche (abajo) para enviar órdenes.",
                        "Save your vehicle PIN (below) to send remote commands.",
                        "Speichere unten die Fahrzeug-PIN, um Fernbefehle zu senden.",
                        "Guarda o PIN do carro (abaixo) para enviar comandos.",
                    )
                    busy -> tr(
                        "Enviando orden al coche…",
                        "Sending command to the car…",
                        "Sende Befehl ans Fahrzeug…",
                        "A enviar comando para o carro…",
                    )
                    else -> tr(
                        "Las órdenes van por la nube de Leapmotor, estés o no conectado a Qonecta Unit.",
                        "Commands are sent via Leapmotor Cloud whether or not you are connected to Qonecta Unit.",
                        "Befehle laufen über die Leapmotor-Cloud – egal ob mit Qonecta Unit verbunden oder nicht.",
                        "Os comandos vão pela nuvem da Leapmotor, estejas ou não ligado ao Qonecta Unit.",
                    )
                },
                13f, if (canSend) DIM else WARN,
            ).apply { setPadding(0, dp(activity, 4), 0, dp(activity, 10)) })

            fun order(label: String, o: CompanionCloud.Order, primary: Boolean = false, confirm: Boolean = false, temp: Int = 22) =
                pill(activity, label, primary = primary && canSend, enabled = canSend && !busy) {
                    val go = { sendOrder(activity, o, temp, onRefresh) }
                    if (confirm) {
                        AlertDialog.Builder(activity)
                            .setTitle(CompanionCloud.orderLabel(o, temp))
                            .setMessage(tr(
                                "¿Seguro? La orden se envía al coche por la nube de Leapmotor.",
                                "Are you sure? This command will be sent to the car via Leapmotor Cloud.",
                                "Sicher? Der Befehl wird über die Leapmotor-Cloud ans Fahrzeug gesendet.",
                                "Tens a certeza? O comando será enviado ao carro pela nuvem da Leapmotor.",
                            ))
                            .setPositiveButton(tr("Enviar", "Send", "Senden", "Enviar")) { _, _ -> go() }
                            .setNegativeButton(tr("Cancelar", "Cancel", "Abbrechen", "Cancelar"), null)
                            .show()
                    } else go()
                }

            addView(row(
                activity,
                order(tr("🔒 Cerrar", "🔒 Lock", "🔒 Verriegeln", "🔒 Trancar"), CompanionCloud.Order.LOCK, primary = true),
                order(tr("🔓 Abrir", "🔓 Unlock", "🔓 Entriegeln", "🔓 Destrancar"), CompanionCloud.Order.UNLOCK, confirm = true),
            ))
            addView(row(
                activity,
                order(tr("🧳 Abrir maletero", "🧳 Open trunk", "🧳 Kofferraum", "🧳 Abrir bagageira"), CompanionCloud.Order.TRUNK_OPEN, confirm = true),
                order(tr("❄️ Clima 22 °C", "❄️ Climate 22 °C", "❄️ Klima 22 °C", "❄️ Clima 22 °C"), CompanionCloud.Order.CLIMATE_ON),
            ).apply { setPadding(0, dp(activity, 8), 0, 0) })
            addView(row(
                activity,
                order(tr("⛔ Apagar clima", "⛔ Climate off", "⛔ Klima aus", "⛔ Desligar clima"), CompanionCloud.Order.CLIMATE_OFF),
                order(tr("🌫️ Desempañar", "🌫️ Defrost", "🌫️ Entfrosten", "🌫️ Desembaciar"), CompanionCloud.Order.DEFROST),
            ).apply { setPadding(0, dp(activity, 8), 0, 0) })
            addView(row(
                activity,
                order(tr("📍 Localizar", "📍 Locate", "📍 Orten", "📍 Localizar"), CompanionCloud.Order.FIND),
                order(tr("🔌 Soltar cable", "🔌 Unlock cable", "🔌 Kabel lösen", "🔌 Soltar cabo"), CompanionCloud.Order.CABLE_UNLOCK, confirm = true),
            ).apply { setPadding(0, dp(activity, 8), 0, 0) })
        })

        // ---------------- Tarifas de energía y combustible (Casa CA · Rápida CC · Gasolina · Diésel · REEV)
        root.addView(LeapCompanionHub.buildEnergyPricingCard(activity, onRefresh))

        // ---------------- Cuenta Leapmotor
        root.addView(accountCard(activity, onRefresh))
    }

    private fun lockSummary(cd: CompanionCloud.CarData?): Pair<String, Int> {
        if (cd == null) {
            return tr(
                "Cierre y puertas: sin dato (necesita la cuenta Leapmotor)",
                "Locks & doors: no data (requires Leapmotor account)",
                "Schloss & Türen: keine Daten (Leapmotor-Konto erforderlich)",
                "Fecho e portas: sem dados (requer conta Leapmotor)",
            ) to FAINT
        }
        val anyDoor = cd.doorsOpen.any { it == true }
        val boot = cd.bootOpen == true
        return when {
            anyDoor || boot -> {
                val parts = listOfNotNull(
                    if (anyDoor) tr("puerta abierta", "door open", "Tür offen", "porta aberta") else null,
                    if (boot) tr("maletero abierto", "trunk open", "Kofferraum offen", "bagageira aberta") else null,
                )
                val joiner = tr(" y ", " & ", " & ", " e ")
                "🔓 " + parts.joinToString(joiner) to WARN
            }
            cd.locked == true -> {
                val odo = if (!cd.odometerKm.isNaN()) String.format(Locale.getDefault(), " · %,.0f km", cd.odometerKm) else ""
                tr("🔒 Cerrado", "🔒 Locked", "🔒 Verriegelt", "🔒 Trancado") + odo to OK
            }
            cd.locked == false -> tr("🔓 Abierto (sin cerrar)", "🔓 Unlocked", "🔓 Entriegelt (offen)", "🔓 Destrancado (aberto)") to WARN
            else -> tr("Cierre: sin dato", "Lock: no data", "Schloss: keine Daten", "Fecho: sem dados") to FAINT
        }
    }

    private fun sendOrder(activity: Activity, o: CompanionCloud.Order, temp: Int, onRefresh: () -> Unit) {
        busy = true
        onRefresh()
        CompanionCloud.send(o, temp) { r ->
            busy = false
            val label = CompanionCloud.orderLabel(o, temp)
            val msg = r.fold(
                onSuccess = { ok ->
                    if (ok) {
                        tr(
                            "✓ $label: confirmado por el coche",
                            "✓ $label: confirmed by the car",
                            "✓ $label: vom Fahrzeug bestätigt",
                            "✓ $label: confirmado pelo carro",
                        )
                    } else {
                        tr(
                            "$label: enviada, pero el coche no la confirmó a tiempo",
                            "$label: sent, but the car did not confirm in time",
                            "$label: gesendet, aber das Fahrzeug hat nicht rechtzeitig bestätigt",
                            "$label: enviado, mas o carro não confirmou a tempo",
                        )
                    }
                },
                onFailure = { e -> "✗ $label: ${CompanionCloud.describe(e)}" },
            )
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            onRefresh()
        }
    }

    // ---------------------------------------------------------------- cuenta

    private fun accountCard(activity: Activity, onRefresh: () -> Unit) = card(activity).apply {
        val c = CompanionCloud.current
        addView(text(
            activity,
            tr("CUENTA LEAPMOTOR", "LEAPMOTOR ACCOUNT", "LEAPMOTOR-KONTO", "CONTA LEAPMOTOR"),
            12.5f, ACCENT, bold = true,
        ))
        val (statusText, color) = when (c.state) {
            CompanionCloud.State.NO_CERT -> tr(
                "Paso 1 de 3: importa el certificado de cliente",
                "Step 1 of 3: import client certificate",
                "Schritt 1 von 3: Client-Zertifikat importieren",
                "Passo 1 de 3: importa o certificado de cliente",
            ) to WARN
            CompanionCloud.State.NO_ACCOUNT -> tr(
                "Paso 2 de 3: entra con tu cuenta",
                "Step 2 of 3: sign in to your account",
                "Schritt 2 von 3: mit deinem Konto anmelden",
                "Passo 2 de 3: entra com a tua conta",
            ) to WARN
            CompanionCloud.State.NEEDS_VEHICLE -> tr(
                "Paso 3 de 3: elige tu coche",
                "Step 3 of 3: select your car",
                "Schritt 3 von 3: Fahrzeug auswählen",
                "Passo 3 de 3: escolhe o teu carro",
            ) to WARN
            CompanionCloud.State.EXPIRED -> tr(
                "Sesión caducada: vuelve a entrar",
                "Session expired: sign in again",
                "Sitzung abgelaufen: neu anmelden",
                "Sessão expirada: volta a iniciar sessão",
            ) to BAD
            CompanionCloud.State.SERVER_KEY -> tr(
                "Clave del servidor desconocida",
                "Unknown server key",
                "Unbekannter Serverschlüssel",
                "Chave do servidor desconhecida",
            ) to BAD
            CompanionCloud.State.ERROR -> tr(
                "Último intento fallido",
                "Last attempt failed",
                "Letzter Versuch fehlgeschlagen",
                "Última tentativa falhou",
            ) to WARN
            CompanionCloud.State.PAUSED_BY_UNIT -> tr(
                "Activa · en pausa mientras manda Qonecta Unit",
                "Active · paused while Qonecta Unit is live",
                "Aktiv · pausiert solange Qonecta Unit verbunden ist",
                "Ativa · em pausa enquanto o Qonecta Unit está ligado",
            ) to OK
            CompanionCloud.State.WAITING -> tr(
                "Activa · leyendo…",
                "Active · reading…",
                "Aktiv · lese Daten…",
                "Ativa · a ler…",
            ) to ACCENT
            CompanionCloud.State.OK -> tr(
                "Activa · ${c.maskedEmail}",
                "Active · ${c.maskedEmail}",
                "Aktiv · ${c.maskedEmail}",
                "Ativa · ${c.maskedEmail}",
            ) to OK
        }
        addView(text(activity, statusText, 15f, color, bold = true).apply { setPadding(0, dp(activity, 4), 0, 0) })
        if (c.message.isNotBlank()) addView(text(activity, c.message, 13f, DIM).apply { setPadding(0, dp(activity, 2), 0, dp(activity, 8)) })

        when (c.state) {
            CompanionCloud.State.NO_CERT -> {
                addView(text(
                    activity,
                    tr(
                        "Es el mismo certificado que usas en Qonecta+. Elige el .crt y el .key a la vez, o un único .p12/.pfx.",
                        "Same client certificate used in Qonecta+. Select both .crt and .key together, or a single .p12/.pfx file.",
                        "Dasselbe Zertifikat wie in Qonecta+. Wähle .crt und .key gleichzeitig oder eine einzelne .p12/.pfx-Datei.",
                        "É o mesmo certificado que usas no Qonecta+. Seleciona o .crt e o .key ao mesmo tempo, ou um único .p12/.pfx.",
                    ),
                    12.5f, FAINT,
                ).apply { setPadding(0, 0, 0, dp(activity, 8)) })
                addView(pill(
                    activity,
                    tr("📄 Importar certificado", "📄 Import certificate", "📄 Zertifikat importieren", "📄 Importar certificado"),
                    primary = true,
                ) { pickCertificate(activity) })
            }
            CompanionCloud.State.NO_ACCOUNT, CompanionCloud.State.EXPIRED -> {
                addView(pill(
                    activity,
                    tr("🔑 Entrar con mi cuenta Leapmotor", "🔑 Sign in to Leapmotor", "🔑 Mit Leapmotor-Konto anmelden", "🔑 Entrar na conta Leapmotor"),
                    primary = true,
                ) { showLogin(activity, onRefresh) })
                addView(pill(
                    activity,
                    tr("📄 Cambiar certificado", "📄 Change certificate", "📄 Zertifikat ändern", "📄 Alterar certificado"),
                ) { pickCertificate(activity) }, lp(activity, top = 8))
            }
            CompanionCloud.State.NEEDS_VEHICLE -> addView(pill(
                activity,
                tr("🚗 Elegir coche", "🚗 Select car", "🚗 Fahrzeug wählen", "🚗 Escolher carro"),
                primary = true,
            ) { pickVehicle(activity, onRefresh) })
            CompanionCloud.State.SERVER_KEY -> addView(pill(
                activity,
                tr(
                    "Confiar en la clave nueva (${c.serverKeyFingerprint.take(17)}…)",
                    "Trust new server key (${c.serverKeyFingerprint.take(17)}…)",
                    "Neuem Schlüssel vertrauen (${c.serverKeyFingerprint.take(17)}…)",
                    "Confiar na chave nova (${c.serverKeyFingerprint.take(17)}…)",
                ),
                primary = true,
            ) {
                AlertDialog.Builder(activity)
                    .setTitle(tr("Clave del servidor de Leapmotor", "Leapmotor server key", "Leapmotor-Serverschlüssel", "Chave do servidor da Leapmotor"))
                    .setMessage(tr(
                        "El servidor presenta otra clave (huella ${c.serverKeyFingerprint}). Acéptala solo si sabes que Leapmotor ha cambiado su certificado.",
                        "The server presented a different key (fingerprint ${c.serverKeyFingerprint}). Accept it only if you know Leapmotor rotated its certificate.",
                        "Der Server präsentiert einen anderen Schlüssel (Fingerabdruck ${c.serverKeyFingerprint}). Nur akzeptieren, wenn Leapmotor sein Zertifikat erneuert hat.",
                        "O servidor apresenta outra chave (impressão ${c.serverKeyFingerprint}). Aceita-a apenas se souberes que a Leapmotor alterou o certificado.",
                    ))
                    .setPositiveButton(tr("Confiar", "Trust", "Vertrauen", "Confiar")) { _, _ -> CompanionCloud.acceptServerKey { onRefresh() } }
                    .setNegativeButton(tr("Cancelar", "Cancel", "Abbrechen", "Cancelar"), null)
                    .show()
            })
            else -> {
                addView(row(
                    activity,
                    pill(
                        activity,
                        if (c.hasPin) tr("🔢 Cambiar PIN", "🔢 Change PIN", "🔢 PIN ändern", "🔢 Alterar PIN")
                        else tr("🔢 Guardar PIN", "🔢 Save PIN", "🔢 PIN speichern", "🔢 Guardar PIN"),
                        primary = !c.hasPin,
                    ) { showPin(activity, onRefresh) },
                    pill(
                        activity,
                        tr("🔄 Leer ahora", "🔄 Refresh now", "🔄 Jetzt abrufen", "🔄 Ler agora"),
                        enabled = !CompanionCloud.unitIsMaster,
                    ) {
                        CompanionCloud.readNow { ok ->
                            Toast.makeText(
                                activity,
                                if (ok) tr("Estado leído", "Status updated", "Status aktualisiert", "Estado atualizado") else CompanionCloud.current.message,
                                Toast.LENGTH_SHORT,
                            ).show()
                            onRefresh()
                        }
                    },
                ))
                addView(row(
                    activity,
                    pill(activity, tr("🚗 Cambiar coche", "🚗 Change car", "🚗 Fahrzeug wechseln", "🚗 Mudar de carro")) { pickVehicle(activity, onRefresh) },
                    pill(activity, tr("Cerrar sesión", "Sign out", "Abmelden", "Terminar sessão")) {
                        AlertDialog.Builder(activity)
                            .setTitle(tr("¿Cerrar la sesión de Leapmotor?", "Sign out of Leapmotor?", "Bei Leapmotor abmelden?", "Terminar sessão da Leapmotor?"))
                            .setMessage(tr(
                                "El certificado se queda guardado. Tendrás que volver a poner tu contraseña.",
                                "Your client certificate stays saved. You will need to enter your password again next time.",
                                "Das Zertifikat bleibt gespeichert. Du musst beim nächsten Mal dein Passwort erneut eingeben.",
                                "O certificado fica guardado. Terás de voltar a introduzir a tua palavra-passe.",
                            ))
                            .setPositiveButton(tr("Cerrar sesión", "Sign out", "Abmelden", "Terminar sessão")) { _, _ -> CompanionCloud.logout() }
                            .setNegativeButton(tr("Cancelar", "Cancel", "Abbrechen", "Cancelar"), null)
                            .show()
                    },
                ).apply { setPadding(0, dp(activity, 8), 0, 0) })
            }
        }
        addView(text(
            activity,
            tr(
                "La contraseña no se guarda. El certificado, la sesión y el PIN se guardan cifrados con el Keystore del móvil.",
                "Your password is never stored. Certificate, session and PIN are encrypted with Android Keystore.",
                "Das Passwort wird nicht gespeichert. Zertifikat, Sitzung und PIN werden verschlüsselt im Android-Keystore gesichert.",
                "A palavra-passe não é guardada. O certificado, a sessão e o PIN são guardados cifrados no Keystore do telemóvel.",
            ),
            11.5f, FAINT,
        ).apply { setPadding(0, dp(activity, 10), 0, 0) })
    }

    private fun pickCertificate(activity: Activity) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        runCatching { activity.startActivityForResult(i, REQ_CERT) }
            .onFailure {
                Toast.makeText(
                    activity,
                    tr("No hay selector de archivos en este móvil", "No file picker found on this phone", "Kein Dateimanager auf diesem Smartphone gefunden", "Não existe seletor de ficheiros neste telemóvel"),
                    Toast.LENGTH_LONG,
                ).show()
            }
    }

    fun onCertPicked(activity: Activity, data: Intent?, onRefresh: () -> Unit) {
        val uris = mutableListOf<Uri>()
        data?.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let(uris::add) }
        if (uris.isEmpty()) data?.data?.let(uris::add)
        if (uris.isEmpty()) return
        val files = uris.mapNotNull { u -> runCatching { displayName(activity, u) to readUri(activity, u) }.getOrNull() }
        if (files.isEmpty()) {
            Toast.makeText(
                activity,
                tr("No se pudo leer el archivo", "Could not read file", "Datei konnte nicht gelesen werden", "Não foi possível ler o ficheiro"),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        importWith(activity, files, null, onRefresh)
    }

    private fun importWith(activity: Activity, files: List<Pair<String, ByteArray>>, pw: CharArray?, onRefresh: () -> Unit) {
        CompanionCloud.importCertificate(files, pw) { r ->
            when (r) {
                CompanionCloud.ImportResult.Ok -> Toast.makeText(
                    activity,
                    tr("✓ Certificado guardado", "✓ Certificate saved", "✓ Zertifikat gespeichert", "✓ Certificado guardado"),
                    Toast.LENGTH_SHORT,
                ).show()
                CompanionCloud.ImportResult.NeedsPassword, CompanionCloud.ImportResult.WrongPassword -> {
                    val et = EditText(activity).apply {
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        hint = tr("Contraseña del certificado", "Certificate password", "Zertifikatspasswort", "Palavra-passe do certificado")
                    }
                    AlertDialog.Builder(activity)
                        .setTitle(
                            if (r == CompanionCloud.ImportResult.WrongPassword) {
                                tr("Contraseña incorrecta", "Incorrect password", "Falsches Passwort", "Palavra-passe incorreta")
                            } else {
                                tr("El certificado tiene contraseña", "Certificate is password-protected", "Zertifikat ist passwortgeschützt", "O certificado tem palavra-passe")
                            }
                        )
                        .setView(et)
                        .setPositiveButton(tr("Probar", "Try", "Prüfen", "Tentar")) { _, _ -> importWith(activity, files, et.text.toString().toCharArray(), onRefresh) }
                        .setNegativeButton(tr("Cancelar", "Cancel", "Abbrechen", "Cancelar"), null)
                        .show()
                }
                is CompanionCloud.ImportResult.Failed -> Toast.makeText(activity, r.why, Toast.LENGTH_LONG).show()
            }
            onRefresh()
        }
    }

    private fun showLogin(activity: Activity, onRefresh: () -> Unit) {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), 0) }
        val email = EditText(activity).apply {
            hint = tr("Correo de Leapmotor", "Leapmotor email", "Leapmotor-E-Mail", "E-mail da Leapmotor")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val pass = EditText(activity).apply {
            hint = tr("Contraseña", "Password", "Passwort", "Palavra-passe")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(email)
        box.addView(pass)
        AlertDialog.Builder(activity)
            .setTitle(tr("Entrar en Leapmotor", "Sign in to Leapmotor", "Bei Leapmotor anmelden", "Iniciar sessão na Leapmotor"))
            .setView(box)
            .setPositiveButton(tr("Entrar", "Sign in", "Anmelden", "Entrar")) { _, _ ->
                val m = email.text.toString().trim()
                val p = pass.text.toString()
                if (m.isEmpty() || p.isEmpty()) {
                    Toast.makeText(
                        activity,
                        tr("Escribe correo y contraseña", "Enter email and password", "Bitte E-Mail und Passwort eingeben", "Introduz e-mail e palavra-passe"),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                Toast.makeText(
                    activity,
                    tr("Entrando…", "Signing in…", "Anmeldung läuft…", "A iniciar sessão…"),
                    Toast.LENGTH_SHORT,
                ).show()
                CompanionCloud.login(m, p) { r ->
                    r.onSuccess { cars ->
                        when {
                            cars.isEmpty() -> Toast.makeText(
                                activity,
                                tr("La cuenta no tiene coches", "No vehicles found on this account", "Keine Fahrzeuge in diesem Konto gefunden", "A conta não tem veículos"),
                                Toast.LENGTH_LONG,
                            ).show()
                            cars.size > 1 -> pickFrom(activity, cars, onRefresh)
                            else -> Toast.makeText(
                                activity,
                                tr(
                                    "✓ Sesión iniciada · ${cars[0].label}",
                                    "✓ Signed in · ${cars[0].label}",
                                    "✓ Angemeldet · ${cars[0].label}",
                                    "✓ Sessão iniciada · ${cars[0].label}",
                                ),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }.onFailure { e ->
                        Toast.makeText(
                            activity,
                            tr(
                                "No se pudo entrar: ${CompanionCloud.describe(e)}",
                                "Could not sign in: ${CompanionCloud.describe(e)}",
                                "Anmeldung fehlgeschlagen: ${CompanionCloud.describe(e)}",
                                "Não foi possível entrar: ${CompanionCloud.describe(e)}",
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    onRefresh()
                }
            }
            .setNegativeButton(tr("Cancelar", "Cancel", "Abbrechen", "Cancelar"), null)
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
            .setTitle(tr("¿Cuál es tu coche?", "Which car is yours?", "Welches ist dein Fahrzeug?", "Qual é o teu carro?"))
            .setItems(cars.map { it.label }.toTypedArray()) { _, i ->
                CompanionCloud.chooseVehicle(cars[i]) { r ->
                    r.onFailure { e -> Toast.makeText(activity, CompanionCloud.describe(e), Toast.LENGTH_LONG).show() }
                    onRefresh()
                }
            }
            .show()
    }

    private fun showPin(activity: Activity, onRefresh: () -> Unit) {
        val et = EditText(activity).apply {
            hint = tr("PIN del coche (app Leapmotor)", "Vehicle PIN (Leapmotor app)", "Fahrzeug-PIN (Leapmotor-App)", "PIN do carro (app Leapmotor)")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(activity)
            .setTitle(tr("PIN de las órdenes remotas", "Remote command PIN", "PIN für Fernbefehle", "PIN para comandos remotos"))
            .setMessage(tr(
                "El mismo PIN que pide la app oficial de Leapmotor para abrir, cerrar o poner el clima. Se guarda cifrado.",
                "The same PIN requested by the official Leapmotor app to lock, unlock or start climate. Stored encrypted.",
                "Dieselbe PIN wie in der offiziellen Leapmotor-App zum Öffnen, Schließen oder Klimatisieren. Wird verschlüsselt gespeichert.",
                "O mesmo PIN pedido pela app oficial da Leapmotor para abrir, fechar ou ligar a climatização. Guardado cifrado.",
            ))
            .setView(et)
            .setPositiveButton(tr("Guardar", "Save", "Speichern", "Guardar")) { _, _ ->
                val ok = CompanionCloud.savePin(et.text.toString().trim())
                Toast.makeText(
                    activity,
                    if (ok) tr("✓ PIN guardado", "✓ PIN saved", "✓ PIN gespeichert", "✓ PIN guardado")
                    else tr("El PIN son de 4 a 12 cifras", "PIN must be 4 to 12 digits", "Die PIN muss 4 bis 12 Ziffern haben", "O PIN deve ter entre 4 e 12 dígitos"),
                    Toast.LENGTH_SHORT,
                ).show()
                onRefresh()
            }
            .setNegativeButton(tr("Cancelar", "Cancel", "Abbrechen", "Cancelar"), null)
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
        val tags = arrayOf(
            tr("Del. izq", "Front L", "Vorne L", "Fr. esq"),
            tr("Del. der", "Front R", "Vorne R", "Fr. dir"),
            tr("Tras. izq", "Rear L", "Hinten L", "Tr. esq"),
            tr("Tras. der", "Rear R", "Hinten R", "Tr. dir"),
        )
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
            s < 60 -> tr("hace ${s} s", "${s}s ago", "vor ${s} s", "há ${s} s")
            s < 3600 -> {
                val m = s / 60
                tr("hace $m min", "${m}m ago", "vor $m Min.", "há $m min")
            }
            s < 172800 -> {
                val h = s / 3600
                tr("hace $h h", "${h}h ago", "vor $h Std.", "há $h h")
            }
            else -> {
                val d = s / 86400
                tr("hace $d d", "${d}d ago", "vor $d T.", "há $d d")
            }
        }
    }

    private fun displayName(ctx: Context, u: Uri): String = runCatching {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: (u.lastPathSegment ?: "file")

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
