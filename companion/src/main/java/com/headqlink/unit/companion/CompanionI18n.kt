package com.headqlink.unit.companion

import android.content.Context
import java.util.Locale

/**
 * Sistema de internacionalización en vivo de Qonecta Companion.
 * Soporta Español (es), Inglés (en), Alemán (de) y Portugués (pt),
 * con detección automática del idioma del móvil ("auto" / "") o selección manual instantánea.
 */
object CompanionI18n {

    enum class Lang(
        val code: String,
        val flag: String,
        val badge: String,
        val nativeName: String,
    ) {
        ES("es", "🇪🇸", "ES", "Español"),
        EN("en", "🇬🇧", "EN", "English"),
        DE("de", "🇩🇪", "DE", "Deutsch"),
        PT("pt", "🇵🇹", "PT", "Português"),
    }

    @Volatile
    var prefCode: String = ""

    @JvmStatic
    fun init(context: Context) {
        runCatching {
            val saved = context.applicationContext
                .getSharedPreferences("companion", Context.MODE_PRIVATE)
                .getString("app_lang", "")
                .orEmpty()
                .trim()
                .lowercase(Locale.ROOT)
            prefCode = saved
        }
    }

    @JvmStatic
    fun setPreferredLanguage(context: Context, code: String) {
        val clean = code.trim().lowercase(Locale.ROOT)
        prefCode = clean
        context.applicationContext
            .getSharedPreferences("companion", Context.MODE_PRIVATE)
            .edit()
            .putString("app_lang", clean)
            .apply()
    }

    @JvmStatic
    fun isAutoMode(): Boolean = prefCode.isBlank() || prefCode == "auto"

    @JvmStatic
    fun systemLang(): Lang {
        val sys = Locale.getDefault().language.lowercase(Locale.ROOT)
        return when {
            sys.startsWith("es") -> Lang.ES
            sys.startsWith("de") -> Lang.DE
            sys.startsWith("pt") -> Lang.PT
            else -> Lang.EN
        }
    }

    @JvmStatic
    fun currentLang(): Lang {
        return when (prefCode.lowercase(Locale.ROOT)) {
            "es" -> Lang.ES
            "en" -> Lang.EN
            "de" -> Lang.DE
            "pt" -> Lang.PT
            else -> systemLang()
        }
    }

    @JvmStatic
    fun fromAcceptLanguageOrCurrent(acceptLangHeader: String?, queryLang: String? = null): Lang {
        val q = queryLang?.trim()?.lowercase(Locale.ROOT).orEmpty()
        when (q) {
            "es" -> return Lang.ES
            "en" -> return Lang.EN
            "de" -> return Lang.DE
            "pt" -> return Lang.PT
        }
        if (!isAutoMode()) return currentLang()
        val hdr = acceptLangHeader?.lowercase(Locale.ROOT).orEmpty()
        if (hdr.isNotBlank()) {
            val primary = hdr.split(',', ';').firstOrNull()?.trim().orEmpty()
            when {
                primary.startsWith("es") -> return Lang.ES
                primary.startsWith("de") -> return Lang.DE
                primary.startsWith("pt") -> return Lang.PT
                primary.startsWith("en") -> return Lang.EN
            }
        }
        return currentLang()
    }

    @JvmStatic
    fun tr(es: String, en: String, de: String, pt: String): String =
        trFor(currentLang(), es, en, de, pt)

    @JvmStatic
    fun trFor(lang: Lang, es: String, en: String, de: String, pt: String): String = when (lang) {
        Lang.ES -> es
        Lang.EN -> en
        Lang.DE -> de
        Lang.PT -> pt
    }
}
