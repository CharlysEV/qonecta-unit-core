package com.headqlink.unit.extended

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Sistema de diseño y personalización compartido entre Qonecta+ (CarKit / CarStyle / CarTheme)
 * y Qonecta Unit para mantener continuidad visual absoluta en colores, degradados, geometría y superficies.
 */
object QonectaTheme {

    private const val PREFS_NAME = "qonecta_theme_prefs"
    private const val KEY_PRESET_ID = "accent_preset_id"
    private const val KEY_CUSTOM_ACCENT_HUE = "custom_accent_hue" // -1f = usar preset
    private const val KEY_THEME_MODE = "night_mode" // 0 = Noche C10 (default), 1 = Día Claro, 2 = Negro Puro OLED
    private const val KEY_PANEL_COLOR = "fixed_panel_color" // 0 = Automático
    private const val KEY_PANEL_HUE = "panel_custom_hue" // 0..360f
    private const val KEY_PANEL_LIGHT = "panel_custom_light" // 0..1f
    private const val KEY_PANEL_ALPHA = "panel_alpha_pct" // 0..85
    private const val KEY_UI_SCALE_PCT = "ui_scale_pct" // 92, 100, 110, 120
    private const val KEY_BORDER_STYLE = "card_border_style" // 0 = Estándar, 1 = Filo Neón, 2 = Minimalista Sin Borde
    private const val KEY_CORNER_STYLE = "card_corner_style" // 0 = Redondeado C10, 1 = Sport Recto, 2 = Cápsula Suave
    private const val KEY_AMBIENT_MODE = "ambient_mode" // 0 = Acuario, 1 = Reloj Cockpit C10, 2 = Nebulosa Neón

    data class AccentPreset(
        val id: String,
        val name: String,
        val subtitle: String,
        val accentNight: Int,
        val accent2Night: Int,
        val onAccentNight: Int,
        val accentBgNight: Int,
        val accentDay: Int,
        val accent2Day: Int
    )

    val PRESETS: List<AccentPreset> = listOf(
        AccentPreset(
            id = "electrico",
            name = "Eléctrico Qonecta+",
            subtitle = "Turquesa #00E5C7 → #4CFF9F original",
            accentNight = 0xFF00E5C7.toInt(),
            accent2Night = 0xFF4CFF9F.toInt(),
            onAccentNight = 0xFF00211C.toInt(),
            accentBgNight = 0xFF0F3B38.toInt(),
            accentDay = 0xFF00897B.toInt(),
            accent2Day = 0xFF15A05A.toInt()
        ),
        AccentPreset(
            id = "leap_green",
            name = "Verde Leapmotor C10",
            subtitle = "Esmeralda oficial del cockpit C10",
            accentNight = 0xFF10B981.toInt(),
            accent2Night = 0xFF6EE7B7.toInt(),
            onAccentNight = 0xFF022C22.toInt(),
            accentBgNight = 0xFF064E3B.toInt(),
            accentDay = 0xFF059669.toInt(),
            accent2Day = 0xFF10B981.toInt()
        ),
        AccentPreset(
            id = "c10_cyan",
            name = "Cian Cockpit",
            subtitle = "Azul celeste e índigo nocturno",
            accentNight = 0xFF38BDF8.toInt(),
            accent2Night = 0xFF818CF8.toInt(),
            onAccentNight = 0xFF082F49.toInt(),
            accentBgNight = 0xFF0C4A6E.toInt(),
            accentDay = 0xFF0284C7.toInt(),
            accent2Day = 0xFF4F46E5.toInt()
        ),
        AccentPreset(
            id = "amber_gt",
            name = "Ámbar GT",
            subtitle = "Dorado deportivo de alto contraste",
            accentNight = 0xFFFFB547.toInt(),
            accent2Night = 0xFFFDE047.toInt(),
            onAccentNight = 0xFF291800.toInt(),
            accentBgNight = 0xFF452708.toInt(),
            accentDay = 0xFFD97706.toInt(),
            accent2Day = 0xFFF59E0B.toInt()
        ),
        AccentPreset(
            id = "sport_red",
            name = "Rojo Sport",
            subtitle = "Coral y rojo dinámico modo Sport",
            accentNight = 0xFFFF6B7A.toInt(),
            accent2Night = 0xFFFF9E7D.toInt(),
            onAccentNight = 0xFF2D060B.toInt(),
            accentBgNight = 0xFF4C111B.toInt(),
            accentDay = 0xFFDC2626.toInt(),
            accent2Day = 0xFFEA580C.toInt()
        ),
        AccentPreset(
            id = "cyber_violet",
            name = "Violeta Neón",
            subtitle = "Púrpura y magenta eléctrico",
            accentNight = 0xFFB69CFF.toInt(),
            accent2Night = 0xFFF472B6.toInt(),
            onAccentNight = 0xFF1F093D.toInt(),
            accentBgNight = 0xFF34165E.toInt(),
            accentDay = 0xFF7C3AED.toInt(),
            accent2Day = 0xFFDB2777.toInt()
        ),
        AccentPreset(
            id = "luxury_gold",
            name = "Oro Design C10",
            subtitle = "Champán cálido versión Pro Max Design",
            accentNight = 0xFFF59E0B.toInt(),
            accent2Night = 0xFFFDE68A.toInt(),
            onAccentNight = 0xFF271500.toInt(),
            accentBgNight = 0xFF3E2406.toInt(),
            accentDay = 0xFFB45309.toInt(),
            accent2Day = 0xFFD97706.toInt()
        ),
        AccentPreset(
            id = "arctic_ice",
            name = "Glaciar Ártico",
            subtitle = "Cristal hielo de máxima nitidez",
            accentNight = 0xFF22D3EE.toInt(),
            accent2Night = 0xFFA5F3FC.toInt(),
            onAccentNight = 0xFF042630.toInt(),
            accentBgNight = 0xFF0B3B49.toInt(),
            accentDay = 0xFF0E7490.toInt(),
            accent2Day = 0xFF0891B2.toInt()
        )
    )

    const val PANEL_NIGHT_SWATCH: Int = 0xFF1E1E20.toInt()
    const val PANEL_DAY_SWATCH: Int = 0xFFF1F3F4.toInt()

    /** Los 16 colores de barra/panel de CarTheme.PANEL_COLORS en Qonecta+. */
    val PANEL_COLORS: IntArray = intArrayOf(
        0xFF000000.toInt(),
        PANEL_NIGHT_SWATCH,
        0xFF2E3238.toInt(),
        0xFF5F6368.toInt(),
        0xFFBDC1C6.toInt(),
        PANEL_DAY_SWATCH,
        0xFF0D2B4E.toInt(),
        0xFF1967D2.toInt(),
        0xFF00695C.toInt(),
        0xFF137333.toInt(),
        0xFF4E5B31.toInt(),
        0xFFF9AB00.toInt(),
        0xFFE8710A.toInt(),
        0xFFC5221F.toInt(),
        0xFF6D1B2B.toInt(),
        0xFF6A1B9A.toInt()
    )

    @Volatile
    private var loaded = false

    @Volatile
    var currentPreset: AccentPreset = PRESETS[0]
        private set

    /** -1f cuando se usa un preset; 0..360f cuando el usuario elige un color de acento libre en la barra arcoíris. */
    @Volatile
    var customAccentHue: Float = -1f
        private set

    /** 0 = Noche C10 (default), 1 = Día Claro, 2 = Negro Puro OLED (#000000). */
    @Volatile
    var themeMode: Int = 0
        private set

    val isNight: Boolean
        get() = themeMode != 1

    val isOledPureBlack: Boolean
        get() = themeMode == 2

    @Volatile
    var fixedPanelColor: Int = 0
        private set

    @Volatile
    var panelCustomHue: Float = 200f
        private set

    @Volatile
    var panelCustomLight: Float = 0.35f
        private set

    @Volatile
    var panelAlphaPct: Int = 0
        private set

    /** Escala global de UI (92, 100, 110, 120). */
    @Volatile
    var uiScalePct: Int = 100
        private set

    val uiScaleFactor: Float
        get() = (uiScalePct.coerceIn(85, 130)) / 100f

    /** 0 = Contorno Estándar, 1 = Filo Neón Acento, 2 = Cristal Minimalista Sin Borde. */
    @Volatile
    var cardBorderStyle: Int = 0
        private set

    /** 0 = Redondeado C10 (22dp), 1 = Sport Anguloso (10dp), 2 = Cápsula Suave (30dp). */
    @Volatile
    var cornerStyle: Int = 0
        private set

    /** 0 = Acuario Qonecta+, 1 = Reloj Cockpit C10, 2 = Nebulosa Neón. */
    @Volatile
    var ambientMode: Int = 0
        private set

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val id = prefs.getString(KEY_PRESET_ID, "electrico") ?: "electrico"
            currentPreset = PRESETS.firstOrNull { it.id == id } ?: PRESETS[0]
            customAccentHue = prefs.getFloat(KEY_CUSTOM_ACCENT_HUE, -1f)
            themeMode = prefs.getInt(KEY_THEME_MODE, 0).coerceIn(0, 2)
            fixedPanelColor = prefs.getInt(KEY_PANEL_COLOR, 0)
            panelCustomHue = prefs.getFloat(KEY_PANEL_HUE, 200f).coerceIn(0f, 360f)
            panelCustomLight = prefs.getFloat(KEY_PANEL_LIGHT, 0.35f).coerceIn(0.08f, 1f)
            panelAlphaPct = prefs.getInt(KEY_PANEL_ALPHA, 0).coerceIn(0, 85)
            uiScalePct = prefs.getInt(KEY_UI_SCALE_PCT, 100).coerceIn(90, 125)
            cardBorderStyle = prefs.getInt(KEY_BORDER_STYLE, 0).coerceIn(0, 2)
            cornerStyle = prefs.getInt(KEY_CORNER_STYLE, 0).coerceIn(0, 2)
            ambientMode = prefs.getInt(KEY_AMBIENT_MODE, 0).coerceIn(0, 2)
            loaded = true
        }
    }

    fun selectPreset(context: Context, presetId: String) {
        ensureLoaded(context)
        val found = PRESETS.firstOrNull { it.id == presetId } ?: PRESETS[0]
        currentPreset = found
        customAccentHue = -1f
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRESET_ID, found.id)
            .putFloat(KEY_CUSTOM_ACCENT_HUE, -1f)
            .apply()
    }

    fun setCustomAccentHue(context: Context, hue: Float) {
        ensureLoaded(context)
        customAccentHue = hue.coerceIn(0f, 360f)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_CUSTOM_ACCENT_HUE, customAccentHue)
            .apply()
    }

    fun setThemeMode(context: Context, mode: Int) {
        ensureLoaded(context)
        themeMode = mode.coerceIn(0, 2)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_THEME_MODE, themeMode).apply()
    }

    fun setNightMode(context: Context, night: Boolean) {
        setThemeMode(context, if (night) 0 else 1)
    }

    fun setFixedPanel(context: Context, color: Int) {
        ensureLoaded(context)
        fixedPanelColor = color
        if (color != 0) {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            panelCustomHue = hsv[0]
            panelCustomLight = hsv[2].coerceIn(0.12f, 1f)
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PANEL_COLOR, color)
            .putFloat(KEY_PANEL_HUE, panelCustomHue)
            .putFloat(KEY_PANEL_LIGHT, panelCustomLight)
            .apply()
    }

    fun setPanelHueAndLight(context: Context, hue: Float, light: Float) {
        ensureLoaded(context)
        panelCustomHue = hue.coerceIn(0f, 360f)
        panelCustomLight = light.coerceIn(0.10f, 1f)
        fixedPanelColor = fromHue(panelCustomHue, panelCustomLight)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PANEL_COLOR, fixedPanelColor)
            .putFloat(KEY_PANEL_HUE, panelCustomHue)
            .putFloat(KEY_PANEL_LIGHT, panelCustomLight)
            .apply()
    }

    fun setPanelAlpha(context: Context, pct: Int) {
        ensureLoaded(context)
        panelAlphaPct = pct.coerceIn(0, 85)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PANEL_ALPHA, panelAlphaPct).apply()
    }

    fun setUiScalePct(context: Context, pct: Int) {
        ensureLoaded(context)
        uiScalePct = pct.coerceIn(90, 125)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_UI_SCALE_PCT, uiScalePct).apply()
    }

    fun setCardBorderStyle(context: Context, style: Int) {
        ensureLoaded(context)
        cardBorderStyle = style.coerceIn(0, 2)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_BORDER_STYLE, cardBorderStyle).apply()
    }

    fun setCornerStyle(context: Context, style: Int) {
        ensureLoaded(context)
        cornerStyle = style.coerceIn(0, 2)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CORNER_STYLE, cornerStyle).apply()
    }

    fun setAmbientMode(context: Context, mode: Int) {
        ensureLoaded(context)
        ambientMode = mode.coerceIn(0, 2)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_AMBIENT_MODE, ambientMode).apply()
    }

    fun resetAllDefaults(context: Context) {
        ensureLoaded(context)
        currentPreset = PRESETS[0]
        customAccentHue = -1f
        themeMode = 0
        fixedPanelColor = 0
        panelCustomHue = 200f
        panelCustomLight = 0.35f
        panelAlphaPct = 0
        uiScalePct = 100
        cardBorderStyle = 0
        cornerStyle = 0
        ambientMode = 0
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    /**
     * Portado 1:1 de CarTheme.fromHue(float hue, float light) en Qonecta+:
     * De tono (0-360°) y claridad (0-1) a color ARGB: de oscuro a vivo hasta 0,75 y de ahí hacia el blanco.
     */
    fun fromHue(hue: Float, light: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val l = light.coerceIn(0f, 1f)
        val s = if (l <= 0.75f) 0.78f else 0.78f * (1f - (l - 0.75f) / 0.25f)
        val v = if (l <= 0.75f) 0.12f + (l / 0.75f) * 0.88f else 1f
        return Color.HSVToColor(floatArrayOf(h, max(0f, s), min(1f, v)))
    }

    /** Luminancia relativa para decidir texto claro u oscuro sobre un color de muestra (portado de CarTheme.isDark). */
    fun isDark(color: Int): Boolean {
        val r = ((color shr 16) and 0xFF) / 255.0
        val g = ((color shr 8) and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0
        val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return lum < 0.52
    }

    // Colores dinámicos CarKit / CarStyle
    val bg: Int
        get() {
            val base = when {
                fixedPanelColor != 0 -> fixedPanelColor
                isOledPureBlack -> 0xFF000000.toInt()
                isNight -> 0xFF0A0E14.toInt()
                else -> 0xFFF1F4F7.toInt()
            }
            return if (panelAlphaPct > 0) dimWithBlack(base, panelAlphaPct) else base
        }

    val surface: Int
        get() = when {
            fixedPanelColor != 0 -> blendColors(bg, if (isNight) 0xFF141D28.toInt() else 0xFFFFFFFF.toInt(), 0.35f)
            isOledPureBlack -> 0xFF05080C.toInt()
            isNight -> 0xFF111821.toInt()
            else -> 0xFFFFFFFF.toInt()
        }

    val surfaceHi: Int
        get() = when {
            fixedPanelColor != 0 -> blendColors(bg, if (isNight) 0xFF1E2A38.toInt() else 0xFFF8FAFC.toInt(), 0.48f)
            isOledPureBlack -> 0xFF0B1017.toInt()
            isNight -> 0xFF17202B.toInt()
            else -> 0xFFF2F5F8.toInt()
        }

    val surfaceTop: Int
        get() = when {
            fixedPanelColor != 0 -> blendColors(bg, if (isNight) 0xFF263545.toInt() else 0xFFE2E8F0.toInt(), 0.58f)
            isOledPureBlack -> 0xFF111823.toInt()
            isNight -> 0xFF1E2935.toInt()
            else -> 0xFFD8DFE6.toInt()
        }

    val outline: Int
        get() = when {
            cardBorderStyle == 1 -> withAlpha(accent, if (isNight) 190 else 160)
            cardBorderStyle == 2 -> withAlpha(if (isNight) 0xFF243140.toInt() else 0xFFC2CCD6.toInt(), 45)
            isOledPureBlack -> 0xFF1D2938.toInt()
            isNight -> 0xFF243140.toInt()
            else -> 0xFFC2CCD6.toInt()
        }

    val edge: Int
        get() = when {
            cardBorderStyle == 1 -> accent
            isNight -> 0xFF2E4256.toInt()
            else -> 0xFFAAB8C5.toInt()
        }

    val text: Int
        get() = if (isNight) 0xFFEAF2F7.toInt() else 0xFF0F1A24.toInt()

    val dim: Int
        get() = if (isNight) 0xFFA3B3C2.toInt() else 0xFF46535F.toInt()

    val faint: Int
        get() = if (isNight) 0xFF8296A9.toInt() else 0xFF5B6977.toInt()

    val accent: Int
        get() {
            if (customAccentHue >= 0f) {
                return Color.HSVToColor(
                    floatArrayOf(
                        customAccentHue,
                        if (isNight) 0.82f else 0.88f,
                        if (isNight) 0.96f else 0.78f
                    )
                )
            }
            return if (isNight) currentPreset.accentNight else currentPreset.accentDay
        }

    val accent2: Int
        get() {
            if (customAccentHue >= 0f) {
                val h2 = (customAccentHue + 32f) % 360f
                return Color.HSVToColor(
                    floatArrayOf(
                        h2,
                        if (isNight) 0.72f else 0.82f,
                        if (isNight) 1.0f else 0.86f
                    )
                )
            }
            return if (isNight) currentPreset.accent2Night else currentPreset.accent2Day
        }

    val onAccent: Int
        get() {
            if (customAccentHue >= 0f) {
                return if (isDark(accent)) 0xFFFFFFFF.toInt() else 0xFF06181E.toInt()
            }
            return if (isNight) currentPreset.onAccentNight else 0xFFFFFFFF.toInt()
        }

    val accentBg: Int
        get() {
            if (customAccentHue >= 0f) {
                return Color.HSVToColor(
                    floatArrayOf(
                        customAccentHue,
                        if (isNight) 0.70f else 0.25f,
                        if (isNight) 0.26f else 0.94f
                    )
                )
            }
            return if (isNight) currentPreset.accentBgNight else 0xFFD4F1EC.toInt()
        }

    val green: Int
        get() = if (isNight) 0xFF4CFF9F.toInt() else 0xFF128A47.toInt()

    val amber: Int
        get() = if (isNight) 0xFFFFB547.toInt() else 0xFFB25A00.toInt()

    val red: Int
        get() = if (isNight) 0xFFFF6B7A.toInt() else 0xFFC62837.toInt()

    val blue: Int
        get() = if (isNight) 0xFF5AA9FF.toInt() else 0xFF1C6ED6.toInt()

    val violet: Int
        get() = if (isNight) 0xFFB69CFF.toInt() else 0xFF6F45D1.toInt()

    fun adjustCornerRadius(baseRadiusPx: Float): Float {
        return when (cornerStyle) {
            1 -> baseRadiusPx * 0.42f // Sport Anguloso
            2 -> baseRadiusPx * 1.45f // Cápsula Suave
            else -> baseRadiusPx
        }
    }

    fun blendColors(c1: Int, c2: Int, ratio: Float): Int {
        val k = ratio.coerceIn(0f, 1f)
        val r = (((c1 shr 16) and 0xFF) * (1f - k) + ((c2 shr 16) and 0xFF) * k).roundToInt()
        val g = (((c1 shr 8) and 0xFF) * (1f - k) + ((c2 shr 8) and 0xFF) * k).roundToInt()
        val b = ((c1 and 0xFF) * (1f - k) + (c2 and 0xFF) * k).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun dimWithBlack(color: Int, pct: Int): Int {
        val k = 1f - pct.coerceIn(0, 100) / 100f
        val r = Math.round(((color shr 16) and 0xFF) * k)
        val g = Math.round(((color shr 8) and 0xFF) * k)
        val b = Math.round((color and 0xFF) * k)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun withAlpha(color: Int, alpha: Int): Int {
        return (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
    }

    /** Píldora activa con el degradado de acento característico de Qonecta+ (CarStyle.accent). */
    fun accentGradientPill(radiusPx: Float): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(accent, accent2)
        ).apply {
            cornerRadius = adjustCornerRadius(radiusPx)
        }
    }

    /** Tarjeta al estilo CarKit.card de Qonecta+: degradado vertical + estilo de borde configurable. */
    fun cardDrawable(radiusPx: Float, strokeColor: Int = outline, strokePx: Int = 2): GradientDrawable {
        val topCol = if (cardBorderStyle == 1) blendColors(surfaceTop, accent, 0.14f) else surfaceTop
        val actualStrokePx = when (cardBorderStyle) {
            1 -> max(strokePx, 3)
            2 -> 0
            else -> strokePx
        }
        val actualStrokeColor = if (cardBorderStyle == 1 && strokeColor == outline) withAlpha(accent, 210) else strokeColor
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topCol, surface)
        ).apply {
            cornerRadius = adjustCornerRadius(radiusPx)
            if (actualStrokePx > 0) {
                setStroke(actualStrokePx, actualStrokeColor)
            }
        }
    }

    /** Ficha interior al estilo CarKit.chip. */
    fun chipDrawable(radiusPx: Float, strokeColor: Int = outline): GradientDrawable {
        val actualStrokePx = if (cardBorderStyle == 2) 0 else 1
        return GradientDrawable().apply {
            cornerRadius = adjustCornerRadius(radiusPx)
            setColor(surfaceHi)
            if (actualStrokePx > 0) {
                setStroke(actualStrokePx, strokeColor)
            }
        }
    }
}
