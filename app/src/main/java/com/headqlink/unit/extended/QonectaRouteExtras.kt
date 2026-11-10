package com.headqlink.unit.extended

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// =============================================================================
// 1. DESTINOS Y VIAJES GUARDADOS (Port 1:1 de SavedPlaces.java de Qonecta+)
// =============================================================================

object QonectaSavedPlaces {
    const val HOME = "home"
    const val WORK = "work"
    const val MAX = 30
    const val MAX_VIA = 3
    private const val SAME_KM = 0.12
    private const val PREF_KEY = "qonecta_saved_places_json"
    private const val MIGRATED_KEY = "qonecta_saved_places_migrated_v1"

    data class Saved(
        var name: String = "",
        var detail: String = "",
        var lat: Double = Double.NaN,
        var lon: Double = Double.NaN,
        var tag: String = "",
        val via: MutableList<QonectaRoutePlanner.Place> = mutableListOf(),
        var roundTrip: Boolean = false,
    ) {
        fun toPlace(): QonectaRoutePlanner.Place = QonectaRoutePlanner.Place(
            name = name,
            detail = detail,
            lat = lat,
            lon = lon,
        )

        val isTrip: Boolean
            get() = via.isNotEmpty() || roundTrip

        val key: String
            get() = tag.ifEmpty { "$name|$lat|$lon" }

        val label: String
            get() = when (tag) {
                HOME -> "Casa"
                WORK -> "Trabajo"
                else -> name
            }

        fun displayLabel(): String = when (tag) {
            HOME -> "🏠 Casa"
            WORK -> "💼 Trabajo"
            else -> if (isTrip) "🗺 $name" else "★ $name"
        }
    }

    fun all(context: Context): List<Saved> = load(context)

    fun get(context: Context, tag: String): Saved? = tagged(load(context), tag)

    fun findMatching(context: Context, p: QonectaRoutePlanner.Place): Saved? =
        findSavedPlace(load(context), p)

    fun putFixed(context: Context, tag: String, pl: QonectaRoutePlanner.Place) =
        setTagged(context, pl, tag)

    fun addFavorite(context: Context, label: String, pl: QonectaRoutePlanner.Place) =
        add(context, pl, emptyList(), false, label)

    fun remove(context: Context, keyOrTag: String) {
        val list = load(context)
        val changed = list.removeAll { it.tag == keyOrTag || it.key == keyOrTag || it.name == keyOrTag }
        if (changed) {
            prefs(context).edit().putString(PREF_KEY, toJson(list)).apply()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("qonecta_extended_prefs", Context.MODE_PRIVATE)

    fun load(context: Context): MutableList<Saved> {
        val p = prefs(context)
        if (!p.getBoolean(MIGRATED_KEY, false)) {
            migrateLegacyFavorites(p)
        }
        return parse(p.getString(PREF_KEY, null))
    }

    private fun migrateLegacyFavorites(p: android.content.SharedPreferences) {
        val existing = parse(p.getString(PREF_KEY, null))
        var json = toJson(existing)
        val homeAddr = p.getString("fav_addr_home", "").orEmpty()
        val homeLat = p.getFloat("fav_lat_home", Float.NaN).toDouble()
        val homeLon = p.getFloat("fav_lon_home", Float.NaN).toDouble()
        if (!homeLat.isNaN() && !homeLon.isNaN()) {
            json = setTaggedJson(json, QonectaRoutePlanner.Place(homeAddr.ifBlank { "Casa" }, homeAddr, homeLat, homeLon), HOME)
        }
        val workAddr = p.getString("fav_addr_work", "").orEmpty()
        val workLat = p.getFloat("fav_lat_work", Float.NaN).toDouble()
        val workLon = p.getFloat("fav_lon_work", Float.NaN).toDouble()
        if (!workLat.isNaN() && !workLon.isNaN()) {
            json = setTaggedJson(json, QonectaRoutePlanner.Place(workAddr.ifBlank { "Trabajo" }, workAddr, workLat, workLon), WORK)
        }
        for (slot in listOf("fav1", "fav2")) {
            val title = p.getString("fav_title_$slot", "").orEmpty()
            val addr = p.getString("fav_addr_$slot", "").orEmpty()
            val la = p.getFloat("fav_lat_$slot", Float.NaN).toDouble()
            val lo = p.getFloat("fav_lon_$slot", Float.NaN).toDouble()
            if (!la.isNaN() && !lo.isNaN()) {
                json = addJson(json, QonectaRoutePlanner.Place(title.ifBlank { addr }, addr, la, lo), emptyList(), false, title.ifBlank { addr })
            }
        }
        p.edit().putString(PREF_KEY, json).putBoolean(MIGRATED_KEY, true).apply()
    }

    fun parse(json: String?): MutableList<Saved> {
        val out = mutableListOf<Saved>()
        if (json.isNullOrBlank()) return out
        return try {
            val a = JSONArray(json)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val la = o.optDouble("la", Double.NaN)
                val lo = o.optDouble("lo", Double.NaN)
                if (la.isNaN() || lo.isNaN()) continue
                val s = Saved(
                    name = o.optString("n", ""),
                    detail = o.optString("d", ""),
                    lat = la,
                    lon = lo,
                    tag = tagOf(o.optString("t", "")),
                    roundTrip = o.optBoolean("r", false),
                )
                val v = o.optJSONArray("v")
                if (v != null) {
                    for (k in 0 until v.length()) {
                        if (s.via.size >= MAX_VIA) break
                        val vp = placeFrom(v.optJSONObject(k)) ?: continue
                        s.via.add(vp)
                    }
                }
                out.add(s)
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun toJson(list: List<Saved>): String {
        val a = JSONArray()
        for (s in list) {
            val o = JSONObject()
                .put("n", s.name)
                .put("d", s.detail)
                .put("la", s.lat)
                .put("lo", s.lon)
            if (s.tag.isNotEmpty()) o.put("t", s.tag)
            if (s.roundTrip) o.put("r", true)
            if (s.via.isNotEmpty()) {
                val v = JSONArray()
                for (p in s.via) v.put(placeJson(p))
                o.put("v", v)
            }
            a.put(o)
        }
        return a.toString()
    }

    fun tagged(list: List<Saved>, tag: String): Saved? = list.firstOrNull { it.tag == tag }

    fun findSavedPlace(list: List<Saved>, p: QonectaRoutePlanner.Place): Saved? {
        return list.firstOrNull {
            !it.isTrip && QonectaRoutePlanner.distanceKm(it.lat, it.lon, p.lat, p.lon) <= SAME_KM
        }
    }

    fun setTagged(context: Context, pl: QonectaRoutePlanner.Place, tag: String) {
        val p = prefs(context)
        val updated = setTaggedJson(p.getString(PREF_KEY, null), pl, tag)
        p.edit().putString(PREF_KEY, updated).apply()
    }

    private fun setTaggedJson(json: String?, pl: QonectaRoutePlanner.Place, tag: String): String {
        val list = parse(json)
        list.removeAll { it.tag == tag }
        val s = Saved(
            name = pl.name,
            detail = pl.detail,
            lat = pl.lat,
            lon = pl.lon,
            tag = tagOf(tag),
        )
        val insertIdx = if (tag == HOME) 0 else min(list.size, if (tagged(list, HOME) != null) 1 else 0)
        list.add(insertIdx, s)
        return toJson(trim(list))
    }

    fun add(
        context: Context,
        dest: QonectaRoutePlanner.Place,
        via: List<QonectaRoutePlanner.Place> = emptyList(),
        roundTrip: Boolean = false,
        name: String = dest.name,
    ) {
        val p = prefs(context)
        val updated = addJson(p.getString(PREF_KEY, null), dest, via, roundTrip, name)
        p.edit().putString(PREF_KEY, updated).apply()
    }

    private fun addJson(
        json: String?,
        dest: QonectaRoutePlanner.Place,
        via: List<QonectaRoutePlanner.Place>,
        roundTrip: Boolean,
        name: String,
    ): String {
        val list = parse(json)
        val s = Saved(
            name = name.trim().ifEmpty { dest.name },
            detail = dest.detail,
            lat = dest.lat,
            lon = dest.lon,
            roundTrip = roundTrip,
        )
        for (vp in via.take(MAX_VIA)) s.via.add(vp)
        list.removeAll { it.tag.isEmpty() && same(it, s) }
        list.add(s)
        return toJson(trim(list))
    }

    fun remove(context: Context, index: Int) {
        val list = load(context)
        if (index in list.indices) {
            list.removeAt(index)
            prefs(context).edit().putString(PREF_KEY, toJson(list)).apply()
        }
    }

    fun move(context: Context, index: Int, lat: Double, lon: Double) {
        if (lat.isNaN() || lon.isNaN()) return
        val list = load(context)
        if (index in list.indices) {
            list[index].lat = lat
            list[index].lon = lon
            prefs(context).edit().putString(PREF_KEY, toJson(list)).apply()
        }
    }

    fun rename(context: Context, index: Int, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val list = load(context)
        if (index in list.indices) {
            list[index].name = clean
            prefs(context).edit().putString(PREF_KEY, toJson(list)).apply()
        }
    }

    fun same(a: Saved, b: Saved): Boolean {
        if (a.roundTrip != b.roundTrip || a.via.size != b.via.size) return false
        if (QonectaRoutePlanner.distanceKm(a.lat, a.lon, b.lat, b.lon) > SAME_KM) return false
        for (i in a.via.indices) {
            val x = a.via[i]
            val y = b.via[i]
            if (QonectaRoutePlanner.distanceKm(x.lat, x.lon, y.lat, y.lon) > SAME_KM) return false
        }
        return true
    }

    private fun trim(list: MutableList<Saved>): MutableList<Saved> {
        var i = 0
        while (list.size > MAX && i < list.size) {
            if (list[i].tag.isEmpty()) list.removeAt(i) else i++
        }
        return list
    }

    private fun tagOf(t: String): String = when (t) {
        HOME -> HOME
        WORK -> WORK
        else -> ""
    }

    private fun placeJson(p: QonectaRoutePlanner.Place): JSONObject =
        JSONObject().put("n", p.name).put("d", p.detail).put("la", p.lat).put("lo", p.lon)

    private fun placeFrom(o: JSONObject?): QonectaRoutePlanner.Place? {
        if (o == null) return null
        val la = o.optDouble("la", Double.NaN)
        val lo = o.optDouble("lo", Double.NaN)
        if (la.isNaN() || lo.isNaN()) return null
        return QonectaRoutePlanner.Place(
            name = o.optString("n", ""),
            detail = o.optString("d", ""),
            lat = la,
            lon = lo,
        )
    }
}

// =============================================================================
// 2. MARCAS DE CARGADORES EN 3 ESTADOS (☆ Normal -> ★ Favorito -> ⊘ Vetado)
// =============================================================================

object QonectaChargerMarks {
    const val MARK_NONE = 0
    const val MARK_FAV = 1
    const val MARK_BAN = 2
    const val FAV = MARK_FAV
    const val BAN = MARK_BAN
    private const val PREF_KEY = "qonecta_charger_marks_json"

    data class MarkedCharger(
        val key: String,
        val name: String,
        val mark: Int,
        val maxKw: Double = 0.0,
    )

    fun allByState(context: Context, mark: Int): List<MarkedCharger> =
        getAllMarked(context).filter { it.mark == mark }

    fun remove(context: Context, key: String) = removeMark(context, key)

    private fun prefs(context: Context) =
        context.getSharedPreferences("qonecta_extended_prefs", Context.MODE_PRIVATE)

    fun getAllMarked(context: Context): MutableList<MarkedCharger> {
        val raw = prefs(context).getString(PREF_KEY, null)
        val out = mutableListOf<MarkedCharger>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val k = o.optString("k", "")
                    val m = o.optInt("m", MARK_NONE)
                    if (k.isNotEmpty() && (m == MARK_FAV || m == MARK_BAN)) {
                        out.add(MarkedCharger(k, o.optString("n", k), m))
                    }
                }
            }
        }
        return out
    }

    private fun saveAll(context: Context, list: List<MarkedCharger>) {
        val arr = JSONArray()
        for (item in list) {
            if (item.mark == MARK_FAV || item.mark == MARK_BAN) {
                arr.put(JSONObject().put("k", item.key).put("n", item.name).put("m", item.mark))
            }
        }
        prefs(context).edit().putString(PREF_KEY, arr.toString()).apply()
    }

    fun getMark(context: Context, key: String): Int =
        getAllMarked(context).firstOrNull { it.key == key }?.mark ?: MARK_NONE

    fun cycleMark(context: Context, charger: QonectaRoutePlanner.Charger): Int {
        val list = getAllMarked(context)
        val idx = list.indexOfFirst { it.key == charger.stableKey }
        val cur = if (idx >= 0) list[idx].mark else MARK_NONE
        val next = when (cur) {
            MARK_NONE -> MARK_FAV
            MARK_FAV -> MARK_BAN
            else -> MARK_NONE
        }
        if (idx >= 0) list.removeAt(idx)
        if (next != MARK_NONE) {
            list.add(0, MarkedCharger(charger.stableKey, charger.name, next))
        }
        saveAll(context, list)
        return next
    }

    fun removeMark(context: Context, key: String) {
        val list = getAllMarked(context)
        list.removeAll { it.key == key }
        saveAll(context, list)
    }

    fun favoriteKeys(context: Context): Set<String> =
        getAllMarked(context).filter { it.mark == MARK_FAV }.map { it.key }.toSet()

    fun bannedKeys(context: Context): Set<String> =
        getAllMarked(context).filter { it.mark == MARK_BAN }.map { it.key }.toSet()
}

// =============================================================================
// 3. CONSEJO DE VELOCIDAD EN RUTA (Port 1:1 de SpeedAdvice.java de Qonecta+)
// =============================================================================

object QonectaSpeedAdvice {
    val CAPS = intArrayOf(110, 100, 90)
    private const val MIN_NET_SAVED_MIN = 5.0
    private const val MIN_EXTRA_DRIVE_MIN = 1.0

    data class Advice(
        val capKmh: Int,
        val stops: Int,
        val prevStops: Int,
        val extraDriveMin: Double,
        val savedChargeMin: Double,
        val arrivalPct: Double,
        val reachesCharger: Boolean,
    )

    fun computeBest(
        context: Context,
        plan: QonectaRoutePlanner.Plan,
        baseResult: QonectaRoutePlanner.ChargeResult,
        socNow: Double,
        filteredChargers: List<QonectaRoutePlanner.Charger>,
        settings: QonectaRoutePlanner.ChargeSettings,
    ): Advice? {
        if (plan.n < 2 || baseResult.outcome == QonectaRoutePlanner.Outcome.NO_STOPS) return null
        val noCharger = baseResult.outcome == QonectaRoutePlanner.Outcome.NO_CHARGER
        val baseStops = baseResult.stops.size
        val baseChargeMin = baseResult.chargeMinutes

        for (cap in CAPS) {
            val (cappedKwh, cappedSec) = QonectaRoutePlanner.computeSpeedCapEnergy(context, plan, cap)
            val extraDriveMin = (cappedSec - plan.totalSeconds) / 60.0
            if (extraDriveMin < MIN_EXTRA_DRIVE_MIN) continue

            val cappedRes = QonectaRoutePlanner.planChargingStops(
                plan = plan,
                socNow = socNow,
                filteredChargers = filteredChargers,
                settings = settings,
                overrideKwhCum = cappedKwh,
            )
            if (cappedRes.outcome == QonectaRoutePlanner.Outcome.NO_CHARGER) continue

            val stops = cappedRes.stops.size
            val savedCharge = baseChargeMin - cappedRes.chargeMinutes
            if (noCharger) {
                return Advice(cap, stops, baseStops, extraDriveMin, 0.0, cappedRes.arrivalPct, true)
            }
            if (stops < baseStops || savedCharge - extraDriveMin >= MIN_NET_SAVED_MIN) {
                return Advice(cap, stops, baseStops, extraDriveMin, savedCharge, cappedRes.arrivalPct, false)
            }
        }
        return null
    }

    fun formatText(a: Advice): String {
        val drive = max(1L, a.extraDriveMin.roundToInt().toLong())
        val charge = max(1L, a.savedChargeMin.roundToInt().toLong())
        if (a.reachesCharger) {
            return if (a.stops == 0) {
                String.format(Locale.getDefault(), "💡 A %d km/h llegas sin parar (con %.0f %%)", a.capKmh, a.arrivalPct)
            } else {
                String.format(
                    Locale.getDefault(),
                    "💡 A %d km/h llegas al cargador (%d %s)",
                    a.capKmh,
                    a.stops,
                    if (a.stops == 1) "parada" else "paradas",
                )
            }
        }
        if (a.stops == 0) {
            return String.format(
                Locale.getDefault(),
                "💡 A %d km/h llegas sin parar (+%d min conduciendo, −%d min cargando)",
                a.capKmh,
                drive,
                charge,
            )
        }
        if (a.stops < a.prevStops) {
            return String.format(
                Locale.getDefault(),
                "💡 A %d km/h haces %d %s en vez de %d (+%d min conduciendo, −%d min cargando)",
                a.capKmh,
                a.stops,
                if (a.stops == 1) "parada" else "paradas",
                a.prevStops,
                drive,
                charge,
            )
        }
        val net = max(1L, (a.savedChargeMin - a.extraDriveMin).roundToInt().toLong())
        return String.format(Locale.getDefault(), "💡 A %d km/h tardas %d min menos en total", a.capKmh, net)
    }
}

// =============================================================================
// 4. TECLADO PROPIO DEL COCHE (Port 1:1 de CarKeyboard.java de Qonecta+)
// =============================================================================

class QonectaCarKeyboard(context: Context) : LinearLayout(context) {

    private var target: EditText? = null
    private var onDone: (() -> Unit)? = null
    private var watcher: TextWatcher? = null
    private var shift = false
    private var symbols = false
    private var accents = false

    private val letterKeys = mutableListOf<TextView>()
    private val keysContainer: LinearLayout
    private var shiftKey: TextView? = null
    private var echoTv: TextView? = null
    private var clearBtn: TextView? = null

    private val d = context.resources.displayMetrics.density * 1.22f

    private fun dp(v: Int): Int = (v * d).roundToInt()

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(10))
        background = GradientDrawable().apply {
            setColor(QonectaTheme.surface)
            setStroke(max(1, dp(1)), QonectaTheme.outline)
            cornerRadius = dp(18).toFloat()
        }
        isClickable = true
        visibility = View.GONE

        addView(buildEchoRow(), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
        keysContainer = LinearLayout(context).apply { orientation = VERTICAL }
        addView(keysContainer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rebuildKeys()
    }

    fun attachTo(editText: EditText, doneAction: () -> Unit) {
        suppressSystemIme(editText)
        editText.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                setTarget(editText, doneAction)
                editText.requestFocus()
            }
            false
        }
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) setTarget(editText, doneAction)
        }
    }

    fun setTarget(editText: EditText, doneAction: () -> Unit) {
        if (target != null && watcher != null) {
            target?.removeTextChangedListener(watcher)
        }
        target = editText
        onDone = doneAction
        suppressSystemIme(editText)
        if (editText.text == null || editText.text.isEmpty()) {
            val it = editText.inputType
            shift = (it and InputType.TYPE_TEXT_VARIATION_URI) == 0 &&
                (it and InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) == 0 &&
                (it and InputType.TYPE_TEXT_VARIATION_PASSWORD) == 0
        }
        symbols = false
        accents = false
        rebuildKeys()
        watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshEcho()
            }
        }
        editText.addTextChangedListener(watcher)
        refreshEcho()
        visibility = View.VISIBLE
    }

    fun hide() {
        if (target != null && watcher != null) {
            target?.removeTextChangedListener(watcher)
        }
        target = null
        onDone = null
        watcher = null
        visibility = View.GONE
    }

    private fun suppressSystemIme(et: EditText) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            et.showSoftInputOnFocus = false
        }
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(et.windowToken, 0)
        }
    }

    private fun buildEchoRow(): LinearLayout {
        val r = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                setColor(QonectaTheme.bg)
                setStroke(max(1, dp(1)), QonectaTheme.outline)
                cornerRadius = dp(12).toFloat()
            }
        }
        echoTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 15.5f * d)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.START
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        r.addView(echoTv, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        clearBtn = TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * d)
            setTextColor(QonectaTheme.dim)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener {
                target?.text?.clear()
            }
        }
        r.addView(clearBtn)
        return r
    }

    private fun refreshEcho() {
        val et = target ?: return
        val echo = echoTv ?: return
        val txt = et.text?.toString().orEmpty()
        if (txt.isEmpty()) {
            val h = et.hint
            echo.text = if (!h.isNullOrEmpty()) h else "Escribe…"
            echo.setTextColor(QonectaTheme.faint)
            clearBtn?.visibility = View.GONE
        } else {
            val pw = (et.inputType and (InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) != 0
            echo.text = if (pw) "•".repeat(txt.length) else "$txt│"
            echo.setTextColor(QonectaTheme.text)
            clearBtn?.visibility = View.VISIBLE
        }
    }

    private fun rebuildKeys() {
        keysContainer.removeAllViews()
        letterKeys.clear()
        shiftKey = null

        val rows = when {
            accents -> ACCENTS
            symbols -> SYMBOLS
            else -> LETTERS
        }
        val letters = !accents && !symbols

        if (!symbols) {
            keysContainer.addView(buildCharsRow(NUMBERS, isLetters = false))
        }
        keysContainer.addView(buildCharsRow(rows[0], isLetters = letters))
        keysContainer.addView(buildCharsRow(rows[1], isLetters = letters))

        val r3 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        if (letters) {
            val sh = makeKey(if (shift) "⬆" else "⇧", weight = 1.4f, special = shift) {
                shift = !shift
                refreshCase()
            }
            shiftKey = sh
            r3.addView(sh)
        }
        for (ch in rows[2]) {
            val k = makeKey(ch, weight = 1f, special = false) { typeText(keyText(ch, letters)) }
            if (letters) letterKeys.add(k)
            r3.addView(k)
        }
        r3.addView(makeKey("⌫", weight = 1.5f, special = true) { backspace() })
        keysContainer.addView(r3, rowParams())

        // Fila inferior: #+= | äé | . @ - _ / .com | Espacio | Ir | Ocultar
        val r4 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        r4.addView(makeKey(if (symbols) "ABC" else "#+=", weight = 1.2f, special = symbols) {
            symbols = !symbols
            accents = false
            rebuildKeys()
        })
        r4.addView(makeKey(if (accents) "abc" else "äé", weight = 1.0f, special = accents) {
            accents = !accents
            symbols = false
            rebuildKeys()
        })
        for (q in arrayOf(".", "@", "-", "_", "/", ".com")) {
            r4.addView(makeKey(q, weight = if (q.length > 1) 1.15f else 0.8f, special = false) { typeText(q) })
        }
        r4.addView(makeKey("Espacio", weight = 2.6f, special = false) { typeText(" ") })
        r4.addView(makeKey("Ir", weight = 1.3f, special = true, primary = true) {
            val done = onDone
            hide()
            done?.invoke()
        })
        r4.addView(makeKey("Ocultar", weight = 1.25f, special = true) { hide() })
        keysContainer.addView(r4, rowParams())

        if (letters) refreshCase()
    }

    private fun buildCharsRow(chars: Array<String>, isLetters: Boolean): LinearLayout {
        val r = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (ch in chars) {
            val k = makeKey(ch, weight = 1f, special = false) { typeText(keyText(ch, isLetters)) }
            if (isLetters) letterKeys.add(k)
            r.addView(k)
        }
        r.layoutParams = rowParams()
        return r
    }

    private fun rowParams(): LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
        }

    private fun keyText(ch: String, isLetters: Boolean): String =
        if (isLetters && shift) ch.uppercase(Locale.getDefault()) else ch

    private fun refreshCase() {
        for (k in letterKeys) {
            val s = k.text.toString()
            k.text = if (shift) s.uppercase(Locale.getDefault()) else s.lowercase(Locale.getDefault())
        }
        shiftKey?.let {
            it.text = if (shift) "⬆" else "⇧"
            it.background = keyBg(special = shift, primary = false)
            it.setTextColor(if (shift) QonectaTheme.accent else QonectaTheme.text)
        }
    }

    private fun makeKey(
        label: String,
        weight: Float,
        special: Boolean,
        primary: Boolean = false,
        tap: () -> Unit,
    ): TextView {
        val tv = TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (label.length > 2) 12.5f else 15.5f) * d)
            setTextColor(
                when {
                    primary -> QonectaTheme.onAccent
                    special -> QonectaTheme.accent
                    else -> QonectaTheme.text
                },
            )
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, dp(9))
            background = keyBg(special, primary)
        }
        tv.setOnClickListener {
            flashKey(tv, special, primary)
            tap()
        }
        tv.layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            leftMargin = dp(2)
            rightMargin = dp(2)
        }
        return tv
    }

    private fun keyBg(special: Boolean, primary: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(
            when {
                primary -> QonectaTheme.accent
                special -> QonectaTheme.withAlpha(QonectaTheme.accent, 0x28)
                else -> QonectaTheme.surfaceTop
            },
        )
        setStroke(max(1, dp(1)), if (special || primary) QonectaTheme.accent else QonectaTheme.outline)
        cornerRadius = dp(9).toFloat()
    }

    private fun flashKey(tv: TextView, special: Boolean, primary: Boolean) {
        if (primary) return
        tv.background = GradientDrawable().apply {
            setColor(QonectaTheme.accent)
            cornerRadius = dp(9).toFloat()
        }
        tv.setTextColor(QonectaTheme.onAccent)
        tv.postDelayed({
            tv.background = keyBg(special, primary = false)
            tv.setTextColor(if (special) QonectaTheme.accent else QonectaTheme.text)
        }, 90L)
    }

    private fun typeText(s: String) {
        val et = target ?: return
        val ed = et.text ?: return
        val a = max(0, min(et.selectionStart, et.selectionEnd))
        val b = max(0, max(et.selectionStart, et.selectionEnd))
        ed.replace(a, b, s)
        if (shift && !symbols && !accents) {
            shift = false
            refreshCase()
        }
    }

    private fun backspace() {
        val et = target ?: return
        val ed = et.text ?: return
        val a = et.selectionStart
        val b = et.selectionEnd
        if (a != b && a >= 0 && b >= 0) {
            ed.delete(min(a, b), max(a, b))
        } else if (a > 0) {
            ed.delete(a - 1, a)
        }
    }

    companion object {
        fun attach(
            context: Context,
            editText: EditText,
            doneLabel: String = "Ir",
            onDone: () -> Unit,
        ): QonectaCarKeyboard = QonectaCarKeyboard(context).apply {
            attachTo(editText, onDone)
            setTarget(editText, onDone)
        }

        private val NUMBERS = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        private val LETTERS = arrayOf(
            arrayOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            arrayOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ñ"),
            arrayOf("z", "x", "c", "v", "b", "n", "m"),
        )
        private val ACCENTS = arrayOf(
            arrayOf("á", "é", "í", "ó", "ú", "ü", "ñ", "ç"),
            arrayOf("à", "è", "ì", "ò", "ù", "â", "ê", "î"),
            arrayOf("ô", "û", "ä", "ë", "ï", "ö", "ã", "õ"),
        )
        private val SYMBOLS = arrayOf(
            arrayOf("!", "?", "¿", "¡", "&", "%", "$", "€", "#", "*"),
            arrayOf("(", ")", "+", "=", ":", ";", "\"", "'", ",", "."),
            arrayOf("<", ">", "[", "]", "{", "}", "\\", "|", "~"),
        )
    }
}

// =============================================================================
// 5. MAPA DE LA RUTA CON PARADAS (Port 1:1 de RouteMap.java + Vector Canvas instantáneo)
// =============================================================================

class QonectaRouteMapView(
    context: Context,
    private val plan: QonectaRoutePlanner.Plan,
    private val chargeResult: QonectaRoutePlanner.ChargeResult?,
    private val filteredChargers: List<QonectaRoutePlanner.Charger>,
) : FrameLayout(context) {

    private val vectorMap = InteractiveRouteVectorCanvas(context, plan, chargeResult, filteredChargers)
    private var webView: WebView? = null
    private var showingOsmWeb = false

    init {
        addView(vectorMap, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Creamos también el WebView Leaflet de RouteMap.java encima si hay internet para cargar teselas OSM
        runCatching {
            val wv = buildLeafletWebView(context, plan, chargeResult)
            wv.visibility = View.GONE
            webView = wv
            addView(wv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    fun toggleMapMode(): Boolean {
        val wv = webView ?: return false
        showingOsmWeb = !showingOsmWeb
        wv.visibility = if (showingOsmWeb) View.VISIBLE else View.GONE
        vectorMap.visibility = if (showingOsmWeb) View.GONE else View.VISIBLE
        return showingOsmWeb
    }

    fun resetCamera() {
        vectorMap.resetCamera()
    }

    fun destroyMap() {
        runCatching {
            webView?.loadUrl("about:blank")
            webView?.destroy()
        }
        webView = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildLeafletWebView(
        context: Context,
        p: QonectaRoutePlanner.Plan,
        cr: QonectaRoutePlanner.ChargeResult?,
    ): WebView {
        val wv = WebView(context)
        wv.setBackgroundColor(0xFF0E1319.toInt())
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        wv.webViewClient = WebViewClient()
        wv.loadDataWithBaseURL("https://openstreetmap.org/", buildLeafletHtml(p, cr), "text/html", "UTF-8", null)
        return wv
    }

    private fun buildLeafletHtml(p: QonectaRoutePlanner.Plan, cr: QonectaRoutePlanner.ChargeResult?): String {
        val pts = StringBuilder("[")
        val step = max(1, p.n / 300)
        var i = 0
        while (i < p.n) {
            if (i > 0) pts.append(',')
            pts.append(String.format(Locale.US, "[%.5f,%.5f]", p.lat[i], p.lon[i]))
            if (i < p.n - 1 && i + step >= p.n - 1) {
                pts.append(String.format(Locale.US, ",[%.5f,%.5f]", p.lat[p.n - 1], p.lon[p.n - 1]))
                break
            }
            i += step
        }
        pts.append(']')
        val split = if (step > 0) p.progressIdx / step else 0

        val stopsJson = StringBuilder("[")
        if (cr != null) {
            for (k in cr.stops.indices) {
                val s = cr.stops[k]
                if (k > 0) stopsJson.append(',')
                val kw = if (s.charger.maxKw > 0) String.format(Locale.getDefault(), "%.0f kW", s.charger.maxKw) else ""
                val lbl = String.format(
                    Locale.getDefault(),
                    "P%d · %.0f→%.0f %% · %.0f min",
                    k + 1,
                    s.arrivePct,
                    s.departPct,
                    s.minutes,
                )
                val sub = s.charger.name + (if (kw.isEmpty()) "" else " · $kw")
                stopsJson.append(
                    String.format(
                        Locale.US,
                        "{\"la\":%.5f,\"lo\":%.5f,\"lbl\":%s,\"sub\":%s}",
                        s.charger.lat,
                        s.charger.lon,
                        JSONObject.quote(lbl),
                        JSONObject.quote(sub),
                    ),
                )
            }
        }
        stopsJson.append(']')
        val pi = p.progressIdx.coerceIn(0, p.n - 1)

        return "<!doctype html><html><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>" +
            "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>" +
            "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>" +
            "<style>" +
            "html,body,#m{margin:0;height:100%;background:#0e1319;font-family:sans-serif}" +
            ".leaflet-tile-pane{filter:invert(1) hue-rotate(185deg) brightness(.88) contrast(.92) saturate(.7)}" +
            ".leaflet-control-attribution{display:none}" +
            ".tag{background:#151d26;color:#eef3f8;border:2px solid #00e5c7;border-radius:10px;" +
            "padding:4px 9px;font-size:13px;font-weight:700;white-space:nowrap;box-shadow:0 3px 10px rgba(0,0,0,.6)}" +
            ".tag b{color:#00e5c7}.tag small{display:block;color:#9fb0c2;font-weight:500;font-size:11px}" +
            ".dest{border-color:#2ee59d}.here{width:16px;height:16px;border-radius:50%;background:#00e5c7;" +
            "border:3px solid #0e1319;box-shadow:0 0 0 5px rgba(0,229,199,.32)}" +
            "</style></head><body><div id='m'></div><script>" +
            "var pts=$pts,split=$split,stops=$stopsJson;" +
            "var m=L.map('m',{zoomControl:true});" +
            "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:18}).addTo(m);" +
            "if(split>0)L.polyline(pts.slice(0,split+1),{color:'#5a6978',weight:5,opacity:.7}).addTo(m);" +
            "var rem=L.polyline(pts.slice(split),{color:'#00e5c7',weight:6}).addTo(m);" +
            "m.fitBounds(L.polyline(pts).getBounds(),{padding:[42,42]});" +
            "L.marker([${String.format(Locale.US, "%.5f,%.5f", p.lat[pi], p.lon[pi])}],{icon:L.divIcon({className:'here',iconSize:[16,16],iconAnchor:[8,8]})}).addTo(m);" +
            "var last=pts[pts.length-1];" +
            "L.marker(last,{icon:L.divIcon({className:'',html:\"<div class='tag dest'>🏁 \"+${JSONObject.quote(p.destination)}+\"</div>\",iconAnchor:[12,32]})}).addTo(m);" +
            "stops.forEach(function(s){L.marker([s.la,s.lo],{icon:L.divIcon({className:'',html:\"<div class='tag'><b>⚡ \"+s.lbl+\"</b><small>\"+s.sub+\"</small></div>\",iconAnchor:[14,42]})}).addTo(m);});" +
            "</script></body></html>"
    }

    /**
     * Mapa vectorial oscuro interactivo con zoom/arrastre táctil que funciona al instante en 0 ms
     * (con o sin internet), mostrando el trazado real OSRM, las paradas intermedias, todos los cargadores
     * filtrados, las etiquetas completas de cada parada de carga (`⚡ P1 · 12→80% · 24 min`), la posición actual y la meta.
     */
    private class InteractiveRouteVectorCanvas(
        context: Context,
        private val plan: QonectaRoutePlanner.Plan,
        private val chargeResult: QonectaRoutePlanner.ChargeResult?,
        private val filteredChargers: List<QonectaRoutePlanner.Charger>,
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = context.resources.displayMetrics.density * 1.25f
        private val routeDonePath = Path()
        private val routeRemPath = Path()
        private val gridDash = DashPathEffect(floatArrayOf(8f, 10f), 0f)
        private val boxRect = RectF()

        private var zoom = 1f
        private var panX = 0f
        private var panY = 0f

        private val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val prev = zoom
                    zoom = (zoom * detector.scaleFactor).coerceIn(0.75f, 8.0f)
                    val factor = zoom / prev
                    panX = detector.focusX - (detector.focusX - panX) * factor
                    panY = detector.focusY - (detector.focusY - panY) * factor
                    invalidate()
                    return true
                }
            },
        )

        private val gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    panX -= distanceX
                    panY -= distanceY
                    invalidate()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val prev = zoom
                    zoom = if (zoom < 3.5f) zoom * 1.7f else 1f
                    if (zoom == 1f) {
                        panX = 0f
                        panY = 0f
                    } else {
                        val factor = zoom / prev
                        panX = e.x - (e.x - panX) * factor
                        panY = e.y - (e.y - panY) * factor
                    }
                    invalidate()
                    return true
                }
            },
        )

        fun resetCamera() {
            zoom = 1f
            panX = 0f
            panY = 0f
            invalidate()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 20f || h <= 20f || plan.n < 2) return

            canvas.drawColor(0xFF0E1319.toInt())

            // Rejilla cartográfica de fondo
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = 0x1A9FB0C2
            paint.pathEffect = gridDash
            val stepGrid = 72f * d
            var gx = (panX % stepGrid + stepGrid) % stepGrid
            while (gx < w) {
                canvas.drawLine(gx, 0f, gx, h, paint)
                gx += stepGrid
            }
            var gy = (panY % stepGrid + stepGrid) % stepGrid
            while (gy < h) {
                canvas.drawLine(0f, gy, w, gy, paint)
                gy += stepGrid
            }
            paint.pathEffect = null

            // Bounding box geográfica de la ruta
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE
            for (i in 0 until plan.n) {
                minLat = min(minLat, plan.lat[i])
                maxLat = max(maxLat, plan.lat[i])
                minLon = min(minLon, plan.lon[i])
                maxLon = max(maxLon, plan.lon[i])
            }
            val midLat = (minLat + maxLat) / 2.0
            val cosLat = max(0.25, cos(Math.toRadians(midLat)))
            val spanLonKm = max(2.0, (maxLon - minLon) * cosLat * 111.32)
            val spanLatKm = max(2.0, (maxLat - minLat) * 111.32)

            val pad = 58f * d
            val availW = max(80f, w - pad * 2f)
            val availH = max(80f, h - pad * 2f)
            val baseScale = min(availW / spanLonKm.toFloat(), availH / spanLatKm.toFloat()) * zoom
            val cx = w / 2f + panX
            val cy = h / 2f + panY
            val midLon = (minLon + maxLon) / 2.0

            fun toScreen(la: Double, lo: Double): PointF {
                val dxKm = (lo - midLon) * cosLat * 111.32
                val dyKm = (la - midLat) * 111.32
                return PointF(
                    cx + dxKm.toFloat() * baseScale,
                    cy - dyKm.toFloat() * baseScale,
                )
            }

            // Construimos los trazados (recorrido completado vs restante)
            routeDonePath.rewind()
            routeRemPath.rewind()
            val prog = plan.progressIdx.coerceIn(0, plan.n - 1)
            for (i in 0..prog) {
                val pt = toScreen(plan.lat[i], plan.lon[i])
                if (i == 0) routeDonePath.moveTo(pt.x, pt.y) else routeDonePath.lineTo(pt.x, pt.y)
            }
            for (i in prog until plan.n) {
                val pt = toScreen(plan.lat[i], plan.lon[i])
                if (i == prog) routeRemPath.moveTo(pt.x, pt.y) else routeRemPath.lineTo(pt.x, pt.y)
            }

            // Sombra/halo exterior de la carretera
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = 11f * d
            paint.color = 0x3300E5C7
            canvas.drawPath(routeRemPath, paint)

            if (prog > 0) {
                paint.strokeWidth = 5f * d
                paint.color = 0xFF5A6978.toInt()
                canvas.drawPath(routeDonePath, paint)
            }
            paint.strokeWidth = 5.5f * d
            paint.color = QonectaTheme.accent
            canvas.drawPath(routeRemPath, paint)

            // Puntos de otros cargadores filtrados a lo largo del corredor
            val plannedKeys = chargeResult?.stops?.map { it.charger.stableKey }?.toSet().orEmpty()
            paint.style = Paint.Style.FILL
            for (ch in filteredChargers) {
                if (plannedKeys.contains(ch.stableKey)) continue
                val pt = toScreen(ch.lat, ch.lon)
                paint.color = 0xFF151D26.toInt()
                canvas.drawCircle(pt.x, pt.y, 5.5f * d, paint)
                paint.color = if (ch.maxKw >= 150.0) QonectaTheme.green else QonectaTheme.accent
                canvas.drawCircle(pt.x, pt.y, 3.5f * d, paint)
            }

            // Paradas intermedias (via)
            val viaCount = if (plan.roundTrip) max(0, plan.waypoints.size - 2) else max(0, plan.waypoints.size - 1)
            for (vIdx in 0 until viaCount) {
                val wp = plan.waypoints[vIdx]
                val pt = toScreen(wp.lat, wp.lon)
                drawMapBadge(
                    canvas = canvas,
                    x = pt.x,
                    y = pt.y,
                    title = "📍 Parada ${vIdx + 1} · ${wp.name.take(20)}",
                    subtitle = null,
                    borderColor = QonectaTheme.amber,
                )
            }

            // Marcador de Salida / Posición actual
            val curPt = toScreen(plan.lat[prog], plan.lon[prog])
            paint.color = 0x5500E5C7
            canvas.drawCircle(curPt.x, curPt.y, 13f * d, paint)
            paint.color = 0xFF0E1319.toInt()
            canvas.drawCircle(curPt.x, curPt.y, 8.5f * d, paint)
            paint.color = QonectaTheme.accent
            canvas.drawCircle(curPt.x, curPt.y, 6f * d, paint)

            // Marcador de Destino
            val destPt = toScreen(plan.destLat, plan.destLon)
            paint.color = QonectaTheme.green
            canvas.drawCircle(destPt.x, destPt.y, 7f * d, paint)
            drawMapBadge(
                canvas = canvas,
                x = destPt.x,
                y = destPt.y - 10f * d,
                title = "🏁 ${plan.destination.take(26)}",
                subtitle = String.format(Locale.getDefault(), "%.0f km · llegas con %.0f %%", plan.totalKm, max(0.0, chargeResult?.arrivalPct ?: 0.0)),
                borderColor = QonectaTheme.green,
            )

            // Etiquetas de Paradas de Carga Planificadas (⚡ P1 · 12→80 % · 24 min)
            if (chargeResult != null) {
                for (k in chargeResult.stops.indices) {
                    val s = chargeResult.stops[k]
                    val pt = toScreen(s.charger.lat, s.charger.lon)
                    paint.color = 0x6600E5C7
                    canvas.drawCircle(pt.x, pt.y, 12f * d, paint)
                    paint.color = QonectaTheme.accent
                    canvas.drawCircle(pt.x, pt.y, 6.5f * d, paint)

                    val lbl = String.format(
                        Locale.getDefault(),
                        "⚡ P%d · %.0f→%.0f %% · %.0f min",
                        k + 1,
                        s.arrivePct,
                        s.departPct,
                        s.minutes,
                    )
                    val kwStr = if (s.charger.maxKw > 0) String.format(Locale.getDefault(), " · %.0f kW", s.charger.maxKw) else ""
                    val sub = "${s.charger.name.take(24)}$kwStr (km ${s.km.roundToInt()})"
                    drawMapBadge(
                        canvas = canvas,
                        x = pt.x,
                        y = pt.y - 12f * d,
                        title = lbl,
                        subtitle = sub,
                        borderColor = QonectaTheme.accent,
                    )
                }
            }

            // Pie informativo de gestos
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textSize = 11.5f * d
            paint.color = QonectaTheme.dim
            canvas.drawText("Arrastra para mover · Pellizca o haz doble toque para ampliar (${String.format(Locale.US, "%.1fx", zoom)})", 16f * d, h - 14f * d, paint)
        }

        private fun drawMapBadge(
            canvas: Canvas,
            x: Float,
            y: Float,
            title: String,
            subtitle: String?,
            borderColor: Int,
        ) {
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            paint.textSize = 12.5f * d
            val wTitle = paint.measureText(title)
            var wSub = 0f
            if (!subtitle.isNullOrBlank()) {
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                paint.textSize = 10.5f * d
                wSub = paint.measureText(subtitle)
            }
            val boxW = max(wTitle, wSub) + 18f * d
            val boxH = if (subtitle.isNullOrBlank()) 26f * d else 42f * d
            val left = (x - boxW / 2f).coerceIn(8f * d, max(8f * d, width - boxW - 8f * d))
            val top = (y - boxH).coerceIn(8f * d, max(8f * d, height - boxH - 28f * d))
            boxRect.set(left, top, left + boxW, top + boxH)

            paint.style = Paint.Style.FILL
            paint.color = 0xEE151D26.toInt()
            canvas.drawRoundRect(boxRect, 9f * d, 9f * d, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f * d
            paint.color = borderColor
            canvas.drawRoundRect(boxRect, 9f * d, 9f * d, paint)

            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            paint.textSize = 12.5f * d
            paint.color = QonectaTheme.text
            canvas.drawText(title, boxRect.left + 9f * d, boxRect.top + 16f * d, paint)

            if (!subtitle.isNullOrBlank()) {
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                paint.textSize = 10.5f * d
                paint.color = QonectaTheme.dim
                canvas.drawText(subtitle, boxRect.left + 9f * d, boxRect.top + 33f * d, paint)
            }
        }
    }
}
