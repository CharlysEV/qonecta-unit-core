package com.headqlink.unit.extended

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.telephony.TelephonyManager
import android.util.LruCache
import android.util.Xml
import android.widget.ImageView
import com.andrerinas.openheadunit.cluster.ClusterMapController
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.text.Normalizer
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

/**
 * Motor unificado de Radio por Internet (radio-browser.info + listas M3U + ICY StreamTitle + Auto-Resume + Mi Música USB),
 * TV / TDT (TDTChannels JSON + IPTV-org M3U + Guía EPG XMLTV en vivo) y Karaoke Sincronizado (LRCLIB LRC)
 * portado de Qonecta+ para Qonecta Unit en el Leapmotor C10.
 */
object QonectaMediaEngine {

    private const val PREFS_NAME = "qonecta_media_prefs"
    private const val KEY_RADIO_FAVS = "radio_favs_json"
    private const val KEY_RADIO_RECENT = "radio_recent_json"
    private const val KEY_RADIO_COUNTRY = "radio_country_iso"
    private const val KEY_RADIO_CUSTOM_M3U = "radio_custom_m3u"
    private const val KEY_RADIO_M3U_LISTS = "radio_m3u_lists_json"
    private const val KEY_RADIO_M3U_SELECTED = "radio_m3u_sel_idx"
    private const val KEY_RADIO_AUTO_RESUME = "radio_auto_resume_enabled"
    private const val KEY_RADIO_RESUME_STATE = "radio_resume_state_json"
    private const val KEY_TV_FAVS = "tv_favs_json"
    private const val KEY_TV_CUSTOM_M3U = "tv_custom_m3u"
    private const val KEY_TV_SOURCE_IDX = "tv_source_idx"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioPool = Executors.newCachedThreadPool()

    // =========================================================================
    // 0. CARGADOR ASÍNCRONO DE LOGOS CON CACHÉ LRU EN MEMORIA
    // =========================================================================

    private val logoMemCache = object : LruCache<String, Bitmap>(128) {}

    private data class BrandBadgeSpec(
        val topText: String,
        val subText: String,
        val bgStart: Int,
        val bgEnd: Int,
        val textColor: Int = Color.WHITE,
        val subColor: Int = 0xFFE0F2F1.toInt(),
        val accentRingColor: Int = 0
    )

    private fun resolveBrandSpec(title: String): BrandBadgeSpec? {
        val t = title.lowercase(Locale.ROOT)
        return when {
            // TV / TDT España & Internacional
            t.contains("la 1") || t == "la1" -> BrandBadgeSpec("1", "RTVE HD", 0xFF0051BA.toInt(), 0xFF002E7A.toInt(), Color.WHITE, 0xFF90CAF9.toInt(), 0xFF42A5F5.toInt())
            t.contains("la 2") || t == "la2" -> BrandBadgeSpec("2", "RTVE HD", 0xFF00897B.toInt(), 0xFF004D40.toInt(), Color.WHITE, 0xFFA7FFEB.toInt(), 0xFF26A69A.toInt())
            t.contains("24 horas") || t.contains("24h") -> BrandBadgeSpec("24h", "RTVE NOTICIAS", 0xFFD32F2F.toInt(), 0xFF8E0000.toInt(), Color.WHITE, 0xFFFFCDD2.toInt(), 0xFFFF5252.toInt())
            t.contains("teledeporte") || t == "tdp" -> BrandBadgeSpec("tdp", "DEPORTES", 0xFFF57C00.toInt(), 0xFFE65100.toInt(), Color.WHITE, 0xFFFFE0B2.toInt(), 0xFFFFAB40.toInt())
            t.contains("clan") -> BrandBadgeSpec("clan", "RTVE", 0xFFD81B60.toInt(), 0xFF880E4F.toInt(), Color.WHITE, 0xFFF8BBD0.toInt(), 0xFFFF4081.toInt())
            t.contains("antena 3") -> BrandBadgeSpec("A3", "ATRESMEDIA", 0xFFFF6D00.toInt(), 0xFFE65100.toInt(), Color.WHITE, 0xFFFFE0B2.toInt(), 0xFFFFAB40.toInt())
            t.contains("lasexta") || t.contains("la sexta") -> BrandBadgeSpec("6", "laSexta", 0xFF00C853.toInt(), 0xFF1B5E20.toInt(), Color.WHITE, 0xFFB9F6CA.toInt(), 0xFF69F0AE.toInt())
            t.contains("telecinco") -> BrandBadgeSpec("5", "TELECINCO", 0xFF0288D1.toInt(), 0xFF01579B.toInt(), Color.WHITE, 0xFFB3E5FC.toInt(), 0xFF40C4FF.toInt())
            t.contains("cuatro") -> BrandBadgeSpec("4", "CUATRO", 0xFFC62828.toInt(), 0xFF7F0000.toInt(), Color.WHITE, 0xFFFFCDD2.toInt(), 0xFFFF5252.toInt())
            t.contains("real madrid") -> BrandBadgeSpec("RMTV", "REAL MADRID", 0xFF1A237E.toInt(), 0xFF0D1346.toInt(), 0xFFFFD54F.toInt(), Color.WHITE, 0xFFFFCA28.toInt())
            t.contains("telemadrid") -> BrandBadgeSpec("★TM", "MADRID", 0xFFC62828.toInt(), 0xFF8E0000.toInt(), Color.WHITE, 0xFFFFCDD2.toInt(), 0xFFFF5252.toInt())
            t.contains("tv3") || t.contains("3cat") -> BrandBadgeSpec("3Cat", "CATALUNYA", 0xFFD50000.toInt(), 0xFF7F0000.toInt(), Color.WHITE, 0xFFFFCDD2.toInt())
            t.contains("canal sur") -> BrandBadgeSpec("CSur", "ANDALUCÍA", 0xFF2E7D32.toInt(), 0xFF1B5E20.toInt(), Color.WHITE, 0xFFC8E6C9.toInt())
            t.contains("tvg") -> BrandBadgeSpec("TVG", "GALICIA", 0xFF0277BD.toInt(), 0xFF004C8C.toInt(), Color.WHITE, 0xFFB3E5FC.toInt())
            t.contains("etb") -> BrandBadgeSpec("ETB", "EUSKADI", 0xFF1565C0.toInt(), 0xFF0D47A1.toInt(), Color.WHITE, 0xFFBBDEFB.toInt())
            t.contains("aragón") || t.contains("aragon") -> BrandBadgeSpec("ATV", "ARAGÓN", 0xFFF9A825.toInt(), 0xFFC62828.toInt(), Color.WHITE, 0xFFFFF9C4.toInt())
            t.contains("punt") -> BrandBadgeSpec("À.", "VALÈNCIA", 0xFFEF6C00.toInt(), 0xFFBF360C.toInt(), Color.WHITE, 0xFFFFE0B2.toInt())
            t.contains("euronews") -> BrandBadgeSpec("euro", "NEWS", 0xFF006064.toInt(), 0xFF00363A.toInt(), Color.WHITE, 0xFF80DEEA.toInt())
            t.contains("dw ") || t == "dw" -> BrandBadgeSpec("DW", "ESPAÑOL", 0xFF0277BD.toInt(), 0xFF01579B.toInt(), Color.WHITE, 0xFFB3E5FC.toInt())
            t.contains("france 24") -> BrandBadgeSpec("F24", "ESPAÑOL", 0xFF00838F.toInt(), 0xFF005662.toInt(), Color.WHITE, 0xFFB2EBF2.toInt())
            t.contains("red bull") -> BrandBadgeSpec("RB", "TV LIVE", 0xFFC62828.toInt(), 0xFF1A237E.toInt(), 0xFFFFD54F.toInt(), Color.WHITE)

            // Emisoras de Radio España & Global
            t.contains("rne") && t.contains("3") -> BrandBadgeSpec("R3", "RNE RADIO 3", 0xFFD32F2F.toInt(), 0xFF880E4F.toInt(), Color.WHITE, 0xFFFFCDD2.toInt(), 0xFFFF5252.toInt())
            t.contains("rne") && t.contains("5") -> BrandBadgeSpec("R5", "TODO NOTICIAS", 0xFFEF6C00.toInt(), 0xFFB71C1C.toInt(), Color.WHITE, 0xFFFFE0B2.toInt())
            t.contains("rne") && (t.contains("clásica") || t.contains("clasica")) -> BrandBadgeSpec("RC", "RNE CLÁSICA", 0xFF5E35B1.toInt(), 0xFF311B92.toInt(), Color.WHITE, 0xFFD1C4E9.toInt())
            t.contains("rne") -> BrandBadgeSpec("RNE", "NACIONAL", 0xFFC62828.toInt(), 0xFF7F0000.toInt(), Color.WHITE, 0xFFFFCDD2.toInt(), 0xFFFF5252.toInt())
            t.contains("los40") && t.contains("classic") -> BrandBadgeSpec("40", "CLASSIC", 0xFF6A1B9A.toInt(), 0xFF283593.toInt(), 0xFFFFD54F.toInt(), Color.WHITE)
            t.contains("los40") && t.contains("urban") -> BrandBadgeSpec("40", "URBAN", 0xFFFF6D00.toInt(), 0xFFC62828.toInt(), Color.WHITE, 0xFFFFE0B2.toInt())
            t.contains("los40") && t.contains("dance") -> BrandBadgeSpec("40", "DANCE", 0xFF00B0FF.toInt(), 0xFF4A148C.toInt(), Color.WHITE, 0xFFE1BEE7.toInt())
            t.contains("los40") -> BrandBadgeSpec("40", "LOS40", 0xFFE91E63.toInt(), 0xFF1E88E5.toInt(), Color.WHITE, 0xFFFFF59D.toInt(), 0xFFFFEB3B.toInt())
            t.contains("cadena ser") || t == "ser" -> BrandBadgeSpec("SER", "CADENA SER", 0xFF0D47A1.toInt(), 0xFF072659.toInt(), 0xFFFFEB3B.toInt(), Color.WHITE, 0xFFFFD600.toInt())
            t.contains("cadena dial") -> BrandBadgeSpec("DIAL", "CADENA DIAL", 0xFF00897B.toInt(), 0xFF004D40.toInt(), Color.WHITE, 0xFFA7FFEB.toInt())
            t.contains("cope") -> BrandBadgeSpec("COPE", "ESPAÑA", 0xFF0277BD.toInt(), 0xFF004C8C.toInt(), Color.WHITE, 0xFFB3E5FC.toInt())
            t.contains("cadena 100") -> BrandBadgeSpec("100", "CADENA 100", 0xFFD81B60.toInt(), 0xFF880E4F.toInt(), Color.WHITE, 0xFFF8BBD0.toInt())
            t.contains("rock fm") -> BrandBadgeSpec("ROCK", "FM", 0xFF212121.toInt(), 0xFF000000.toInt(), 0xFFFF5252.toInt(), Color.WHITE, 0xFFD32F2F.toInt())
            t.contains("megastar") -> BrandBadgeSpec("★", "MEGASTAR", 0xFFFFB300.toInt(), 0xFFE65100.toInt(), 0xFF1A237E.toInt(), Color.WHITE)
            t.contains("kiss fm") -> BrandBadgeSpec("KISS", "FM", 0xFFC2185B.toInt(), 0xFF880E4F.toInt(), Color.WHITE, 0xFFF8BBD0.toInt())
            t.contains("hit fm") -> BrandBadgeSpec("HIT", "FM", 0xFF0097A7.toInt(), 0xFF006064.toInt(), Color.WHITE, 0xFFB2EBF2.toInt())
            t.contains("onda cero") -> BrandBadgeSpec("O0", "ONDA CERO", 0xFF2E7D32.toInt(), 0xFF1B5E20.toInt(), Color.WHITE, 0xFFC8E6C9.toInt())
            t.contains("europa fm") -> BrandBadgeSpec("eFM", "EUROPA FM", 0xFF7B1FA2.toInt(), 0xFF4A148C.toInt(), Color.WHITE, 0xFFE1BEE7.toInt())
            t.contains("melodía") || t.contains("melodia") -> BrandBadgeSpec("M", "MELODÍA FM", 0xFF00838F.toInt(), 0xFF004D40.toInt(), Color.WHITE, 0xFFB2EBF2.toInt())
            t.contains("marca") -> BrandBadgeSpec("M", "RADIO MARCA", 0xFFD32F2F.toInt(), 0xFF9A0007.toInt(), Color.WHITE, 0xFFFFCDD2.toInt())
            t.contains("esradio") -> BrandBadgeSpec("es", "esRadio", 0xFF1565C0.toInt(), 0xFF0D47A1.toInt(), Color.WHITE, 0xFFBBDEFB.toInt())
            t.contains("rac1") -> BrandBadgeSpec("RAC1", "CATALUNYA", 0xFFE65100.toInt(), 0xFFBF360C.toInt(), Color.WHITE, 0xFFFFE0B2.toInt())
            t.contains("loca fm") -> BrandBadgeSpec("LOCA", "URBAN / DANCE", 0xFFAD1457.toInt(), 0xFF4A148C.toInt(), Color.WHITE, 0xFFF8BBD0.toInt())
            t.contains("jovem pan") -> BrandBadgeSpec("JP", "JOVEM PAN", 0xFFD32F2F.toInt(), 0xFF212121.toInt(), Color.WHITE, 0xFFFFCDD2.toInt())
            t.contains("comercial") -> BrandBadgeSpec("RC", "COMERCIAL", 0xFFC62828.toInt(), 0xFF8E0000.toInt(), Color.WHITE, 0xFFFFCDD2.toInt())
            t.contains("somafm") -> BrandBadgeSpec("SOMA", "FM CHILL", 0xFF00695C.toInt(), 0xFF004D40.toInt(), 0xFF80CBC4.toInt(), Color.WHITE)
            else -> null
        }
    }

    fun generateBrandBadgeBitmap(title: String): Bitmap? {
        val spec = resolveBrandSpec(title) ?: return null
        val cacheKey = "brand_badge::$title"
        logoMemCache.get(cacheKey)?.let { if (!it.isRecycled) return it }

        val size = 160
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(4f, 4f, size - 4f, size - 4f)

        paint.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            spec.bgStart, spec.bgEnd, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, 34f, 34f, paint)
        paint.shader = null

        if (spec.accentRingColor != 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            paint.color = spec.accentRingColor
            canvas.drawRoundRect(RectF(8f, 8f, size - 8f, size - 8f), 30f, 30f, paint)
            paint.style = Paint.Style.FILL
        }

        // Brillo superior sutil tipo cristal
        paint.color = 0x24FFFFFF
        canvas.drawRoundRect(RectF(12f, 10f, size - 12f, size * 0.46f), 24f, 24f, paint)

        // Texto principal grande
        paint.color = spec.textColor
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        paint.textSize = when (spec.topText.length) {
            1 -> 74f
            2 -> 60f
            3 -> 48f
            else -> 38f
        }
        val mainY = if (spec.subText.isNotEmpty()) size * 0.56f else size * 0.64f
        canvas.drawText(spec.topText, size / 2f, mainY, paint)

        // Subtítulo de marca inferior
        if (spec.subText.isNotEmpty()) {
            paint.color = spec.subColor
            paint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            paint.textSize = 18.5f
            canvas.drawText(spec.subText, size / 2f, size * 0.82f, paint)
        }

        logoMemCache.put(cacheKey, bmp)
        return bmp
    }

    fun getCachedLogo(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return logoMemCache.get(url)
    }

    fun loadLogoInto(
        imageView: ImageView,
        url: String?,
        title: String = "",
        onLoaded: () -> Unit = {},
        onFallback: () -> Unit
    ) {
        val brandBmp = if (title.isNotBlank()) generateBrandBadgeBitmap(title) else null
        if (brandBmp != null) {
            imageView.setPadding(0, 0, 0, 0)
            imageView.setImageBitmap(brandBmp)
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            onLoaded()
            return
        }

        val cleanUrl = url?.trim().orEmpty()
        imageView.tag = cleanUrl
        if (cleanUrl.isEmpty() || (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://"))) {
            onFallback()
            return
        }
        val cached = logoMemCache.get(cleanUrl)
        if (cached != null && !cached.isRecycled) {
            imageView.setImageBitmap(cached)
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            onLoaded()
            return
        }
        onFallback()
        ioPool.execute {
            val bmp = fetchBitmap(cleanUrl)
            if (bmp != null) {
                logoMemCache.put(cleanUrl, bmp)
                mainHandler.post {
                    if (imageView.tag == cleanUrl) {
                        imageView.setImageBitmap(bmp)
                        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                        onLoaded()
                    }
                }
            }
        }
    }

    private fun fetchBitmap(urlStr: String): Bitmap? {
        val primary = fetchBitmapRaw(urlStr)
        if (primary != null) return primary
        return try {
            val host = URL(urlStr).host
            if (host.isNotBlank()) {
                fetchBitmapRaw("https://www.google.com/s2/favicons?sz=128&domain=$host")
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchBitmapRaw(urlStr: String): Bitmap? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; Leapmotor C10) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return null
            }
            val bytes = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } > 0 && out.size() < 2 * 1024 * 1024) {
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            conn.disconnect()
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        }
    }

    // =========================================================================
    // 1. RADIO POR INTERNET + MI MÚSICA USB / COCHE (Portado de RadioPlayer + RadioScreen + MusicLibrary)
    // =========================================================================

    data class RadioStation(
        val name: String,
        val url: String,
        val logo: String = "",
        val group: String = "",
        val country: String = "",
        val local: Boolean = false,
        val album: String = "",
        val durMs: Long = 0L
    )

    data class M3uListEntry(
        val name: String,
        val url: String
    )

    private val RADIO_DIRECTORY_HOSTS = arrayOf(
        "all.api.radio-browser.info",
        "de1.api.radio-browser.info",
        "fi1.api.radio-browser.info",
        "nl1.api.radio-browser.info"
    )

    val DEFAULT_SPANISH_AND_GLOBAL_STATIONS: List<RadioStation> = listOf(
        RadioStation(
            name = "RNE Radio Nacional",
            url = "https://rtvelivestream.rtve.es/rtvesec/rne/rne_r1_main.m3u8",
            logo = "https://img2.rtve.es/css/rtve.2022/i/logos/logo-rne.png",
            group = "Noticias · España",
            country = "ES"
        ),
        RadioStation(
            name = "RNE Radio 3",
            url = "https://rtvelivestream.rtve.es/rtvesec/rne/rne_r3_main.m3u8",
            logo = "https://img2.rtve.es/css/rtve.2022/i/logos/logo-r3.png",
            group = "Música / Indie · España",
            country = "ES"
        ),
        RadioStation(
            name = "RNE Radio 5 Todo Noticias",
            url = "https://rtvelivestream.rtve.es/rtvesec/rne/rne_r5_madrid_main.m3u8",
            logo = "https://img2.rtve.es/css/rtve.2022/i/logos/logo-r5.png",
            group = "Noticias 24h · España",
            country = "ES"
        ),
        RadioStation(
            name = "RNE Radio Clásica",
            url = "https://rtvelivestream.rtve.es/rtvesec/rne/rne_r2_main.m3u8",
            logo = "https://img2.rtve.es/css/rtve.2022/i/logos/logo-r2.png",
            group = "Clásica · España",
            country = "ES"
        ),
        RadioStation(
            name = "LOS40 España",
            url = "https://playerservices.streamtheworld.com/api/livestream-redirect/LOS40.mp3",
            logo = "https://los40.com/favicon.ico",
            group = "Pop / Hits · España",
            country = "ES"
        ),
        RadioStation(
            name = "LOS40 Classic",
            url = "https://playerservices.streamtheworld.com/api/livestream-redirect/LOS40_CLASSIC.mp3",
            logo = "https://los40.com/favicon.ico",
            group = "Clásicos Pop · España",
            country = "ES"
        ),
        RadioStation(
            name = "LOS40 Urban",
            url = "https://playerservices.streamtheworld.com/api/livestream-redirect/LOS40_URBAN.mp3",
            logo = "https://los40.com/favicon.ico",
            group = "Urbano · España",
            country = "ES"
        ),
        RadioStation(
            name = "LOS40 Dance",
            url = "https://playerservices.streamtheworld.com/api/livestream-redirect/LOS40_DANCE.mp3",
            logo = "https://los40.com/favicon.ico",
            group = "Electrónica · España",
            country = "ES"
        ),
        RadioStation(
            name = "Cadena SER",
            url = "https://playerservices.streamtheworld.com/api/livestream-redirect/CADENASER.mp3",
            logo = "https://cadenaser.com/favicon.ico",
            group = "Generalista · España",
            country = "ES"
        ),
        RadioStation(
            name = "Cadena Dial",
            url = "https://playerservices.streamtheworld.com/api/livestream-redirect/CADENADIAL.mp3",
            logo = "https://www.cadenadial.com/favicon.ico",
            group = "Pop Español · España",
            country = "ES"
        ),
        RadioStation(
            name = "COPE España",
            url = "https://net1-cope-rrcast.flumotion.com/cope/net1-low.mp3",
            logo = "https://www.cope.es/favicon.ico",
            group = "Generalista / Deportes · España",
            country = "ES"
        ),
        RadioStation(
            name = "Cadena 100",
            url = "https://cadena100-cope-rrcast.flumotion.com/cope/cadena100-low.mp3",
            logo = "https://www.cadena100.es/favicon.ico",
            group = "Pop / Variedad · España",
            country = "ES"
        ),
        RadioStation(
            name = "Rock FM",
            url = "https://rockfm-cope-rrcast.flumotion.com/cope/rockfm-low.mp3",
            logo = "https://www.rockfm.fm/favicon.ico",
            group = "Rock Clásico · España",
            country = "ES"
        ),
        RadioStation(
            name = "MegaStar FM",
            url = "https://megastar-cope-rrcast.flumotion.com/cope/megastar-low.mp3",
            logo = "https://www.megastar.fm/favicon.ico",
            group = "Hits / Dance · España",
            country = "ES"
        ),
        RadioStation(
            name = "Kiss FM España",
            url = "https://kissfm.kissfmradio.cires21.com/kissfm.mp3",
            logo = "https://www.kissfm.es/favicon.ico",
            group = "80s 90s Pop · España",
            country = "ES"
        ),
        RadioStation(
            name = "Hit FM",
            url = "https://hitfm.kissfmradio.cires21.com/hitfm.mp3",
            logo = "https://www.hitfm.es/favicon.ico",
            group = "Dance / Hits · España",
            country = "ES"
        ),
        RadioStation(
            name = "Onda Cero",
            url = "https://atres-live.ondacero.es/live/ondacero/master.m3u8",
            logo = "https://www.ondacero.es/favicon.ico",
            group = "Generalista · España",
            country = "ES"
        ),
        RadioStation(
            name = "Europa FM",
            url = "https://atres-live.europafm.com/live/europafm/master.m3u8",
            logo = "https://www.europafm.com/favicon.ico",
            group = "Pop / Rock · España",
            country = "ES"
        ),
        RadioStation(
            name = "Melodía FM",
            url = "https://atres-live.melodia-fm.com/live/melodiafm/master.m3u8",
            logo = "https://www.melodia-fm.com/favicon.ico",
            group = "Clásicos · España",
            country = "ES"
        ),
        RadioStation(
            name = "Radio Marca",
            url = "https://playerservices.streamtheworld.com/api/livestream-redirect/RADIOMARCA_NACIONAL.mp3",
            logo = "https://www.marca.com/favicon.ico",
            group = "Deportes 24h · España",
            country = "ES"
        ),
        RadioStation(
            name = "esRadio",
            url = "https://libertaddigital-radio-live1.flumotion.com/libertaddigital/ld-live1-low.mp3",
            logo = "https://esradio.libertaddigital.com/favicon.ico",
            group = "Generalista · España",
            country = "ES"
        ),
        RadioStation(
            name = "RAC1 Catalunya",
            url = "https://playerservices.streamtheworld.com/api/livestream-redirect/RAC1.mp3",
            logo = "https://www.rac1.cat/favicon.ico",
            group = "Generalista · Catalunya",
            country = "ES"
        ),
        RadioStation(
            name = "Loca FM",
            url = "https://s3.we4stream.com:2020/stream/locafm",
            logo = "https://www.locafm.com/favicon.ico",
            group = "Electrónica / Remember · España",
            country = "ES"
        ),
        RadioStation(
            name = "Jovem Pan FM Brasil",
            url = "https://stream.fabricahost.com.br/jovempanfmsjc",
            logo = "https://jovempan.com.br/favicon.ico",
            group = "Brasil · Pop/Noticias",
            country = "BR"
        ),
        RadioStation(
            name = "Rádio Comercial Portugal",
            url = "https://stream-icy.bauermedia.pt/comercial.mp3",
            logo = "https://radiocomercial.pt/favicon.ico",
            group = "Portugal · Hits",
            country = "PT"
        ),
        RadioStation(
            name = "SomaFM Groove Salad",
            url = "https://ice2.somafm.com/groovesalad-128-mp3",
            logo = "https://somafm.com/img3/groovesalad-400.jpg",
            group = "Chill / Ambient",
            country = "US"
        ),
        RadioStation(
            name = "SomaFM Secret Agent",
            url = "https://ice2.somafm.com/secretagent-128-mp3",
            logo = "https://somafm.com/img3/secretagent-400.jpg",
            group = "Lounge / Jazz",
            country = "US"
        )
    )

    val RADIO_COUNTRIES: List<Pair<String, String>> = listOf(
        "ES" to "🇪🇸 España",
        "BR" to "🇧🇷 Brasil",
        "PT" to "🇵🇹 Portugal",
        "FR" to "🇫🇷 Francia",
        "DE" to "🇩🇪 Alemania",
        "IT" to "🇮🇹 Italia",
        "GB" to "🇬🇧 Reino Unido",
        "US" to "🇺🇸 Estados Unidos",
        "MX" to "🇲🇽 México",
        "AR" to "🇦🇷 Argentina",
        "CO" to "🇨🇴 Colombia",
        "CL" to "🇨🇱 Chile"
    )

    /** Bandera emoji Unicode a partir del código ISO de 2 letras (igual que RadioScreen.flag en Qonecta+). */
    fun countryFlag(iso: String?): String {
        if (iso == null || iso.length != 2) return "🌍"
        val u = iso.uppercase(Locale.ROOT)
        val c0 = u[0]
        val c1 = u[1]
        if (c0 !in 'A'..'Z' || c1 !in 'A'..'Z') return "🌍"
        return String(Character.toChars(0x1F1E6 + (c0 - 'A'))) +
            String(Character.toChars(0x1F1E6 + (c1 - 'A')))
    }

    /** Nombre del país en el idioma actual con su bandera delante (igual que RadioScreen.countryName). */
    fun countryDisplayName(iso: String): String {
        val code = iso.uppercase(Locale.ROOT)
        val name = Locale("", code).getDisplayCountry(Locale.getDefault()).ifBlank { code }
        return "${countryFlag(code)}  $name"
    }

    /** Lista completa de todos los países del mundo (ISO 3166-1 alpha-2) ordenada alfabéticamente (como Qonecta+). */
    fun allCountriesSorted(): List<Pair<String, String>> {
        val codes = Locale.getISOCountries()
        val list = ArrayList<Pair<String, String>>(codes.size)
        for (c in codes) {
            list.add(c.uppercase(Locale.ROOT) to countryDisplayName(c))
        }
        list.sortWith { a, b ->
            val na = Locale("", a.first).getDisplayCountry(Locale.getDefault())
            val nb = Locale("", b.first).getDisplayCountry(Locale.getDefault())
            na.compareTo(nb, ignoreCase = true)
        }
        return list
    }

    /** Detección automática del país igual que RadioScreen.autoCountry en Qonecta+. */
    fun autoDetectCountry(context: Context): String {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null) {
                val net = tm.networkCountryIso
                if (!net.isNullOrBlank() && net.length == 2) return net.uppercase(Locale.ROOT)
                val sim = tm.simCountryIso
                if (!sim.isNullOrBlank() && sim.length == 2) return sim.uppercase(Locale.ROOT)
            }
        } catch (_: Exception) {
        }
        val loc = Locale.getDefault().country
        return if (!loc.isNullOrBlank() && loc.length == 2) loc.uppercase(Locale.ROOT) else "ES"
    }

    private var radioPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val radioPlaylist = ArrayList<RadioStation>()
    private var radioIndex = -1
    private var stopByUser = false
    private var icyPollSeq = 0

    @Volatile
    var isRadioPlaying: Boolean = false
        private set

    @Volatile
    var isRadioBuffering: Boolean = false
        private set

    @Volatile
    var radioError: String = ""
        private set

    /** Título en vivo emitido por la emisora («Artista - Canción» o programa actual por ICY StreamTitle). */
    @Volatile
    var radioLiveTitle: String = ""
        private set

    private val radioListeners = CopyOnWriteArrayList<Runnable>()

    fun addRadioListener(r: Runnable) {
        if (!radioListeners.contains(r)) radioListeners.add(r)
    }

    fun removeRadioListener(r: Runnable) {
        radioListeners.remove(r)
    }

    private fun notifyRadioChanged(context: Context?) {
        val current = currentStation()
        if (context != null && current != null) {
            val liveSong = radioLiveTitle.trim()
            val displayTitle = if (liveSong.isNotEmpty() && !current.local) {
                "$liveSong · ${current.name}"
            } else {
                current.name
            }
            val displayArtist = when {
                current.local -> current.group.ifBlank { "Mi Música USB / Coche" }
                liveSong.isNotEmpty() -> current.name
                else -> current.group.ifBlank { "Radio en directo" }
            }
            val posMs = runCatching {
                if (current.local && radioPlayer != null) radioPlayer!!.currentPosition.toLong() else 0L
            }.getOrDefault(0L)
            ClusterMapController.updateMedia(
                title = displayTitle,
                artist = displayArtist,
                album = if (current.local) current.album.ifBlank { "Mi Música C10" } else "Qonecta+ Radio",
                appName = if (current.local) "MÚSICA QONECTA+" else "RADIO QONECTA+",
                isPlaying = isRadioPlaying || isRadioBuffering,
                positionMs = posMs,
                durationMs = current.durMs
            )
        }
        mainHandler.post {
            for (r in radioListeners) r.run()
        }
    }

    fun currentStation(): RadioStation? {
        return if (radioIndex in 0 until radioPlaylist.size) radioPlaylist[radioIndex] else null
    }

    fun currentPositionMs(): Long {
        val st = currentStation() ?: return 0L
        if (!st.local) return 0L
        return runCatching { radioPlayer?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
    }

    fun playStation(context: Context, stations: List<RadioStation>, index: Int, startPosMs: Long = 0L) {
        if (stations.isEmpty() || index !in stations.indices) return
        val appCtx = context.applicationContext
        stopByUser = false
        radioPlaylist.clear()
        radioPlaylist.addAll(stations)
        radioIndex = index
        val st = radioPlaylist[radioIndex]
        if (!st.local) {
            addRadioRecent(appCtx, st)
        }
        saveResumeState(appCtx, startPosMs)
        startRadioInternal(appCtx, st, startPosMs)
    }

    private fun startRadioInternal(appCtx: Context, st: RadioStation, startPosMs: Long = 0L) {
        stopRadioInternal(notify = false)
        isRadioBuffering = true
        isRadioPlaying = false
        radioError = ""
        radioLiveTitle = ""
        notifyRadioChanged(appCtx)

        requestAudioFocus(appCtx)

        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setOnPreparedListener { player ->
                isRadioBuffering = false
                isRadioPlaying = true
                radioError = ""
                if (st.local && startPosMs > 0L) {
                    runCatching { player.seekTo(startPosMs.toInt()) }
                }
                runCatching { player.start() }
                notifyRadioChanged(appCtx)
                if (!st.local) {
                    startIcyMetadataPolling(appCtx, st)
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                isRadioBuffering = false
                isRadioPlaying = false
                abandonAudioFocus(appCtx)
                radioError = "Señal temporalmente no disponible ($what/$extra)"
                notifyRadioChanged(appCtx)
                // Reintento automático tras 3 s si no fue parado por el usuario (como RadioPlayer.java)
                if (!stopByUser && !st.local) {
                    val retryStation = st
                    mainHandler.postDelayed({
                        if (!stopByUser && currentStation()?.url == retryStation.url && !isRadioPlaying) {
                            startRadioInternal(appCtx, retryStation)
                        }
                    }, 3500L)
                }
                true
            }
            mp.setOnCompletionListener {
                if (st.local && radioPlaylist.size > 1 && !stopByUser) {
                    // En «Mi música», al acabar una canción pasa automáticamente a la siguiente (RadioPlayer.java)
                    stepRadio(appCtx, 1)
                } else if (!st.local && !stopByUser) {
                    // Si un stream de radio corta por fin de flujo, reconecta automáticamente
                    startRadioInternal(appCtx, st)
                } else {
                    isRadioBuffering = false
                    isRadioPlaying = false
                    abandonAudioFocus(appCtx)
                    notifyRadioChanged(appCtx)
                }
            }
            if (st.url.startsWith("content://") || st.url.startsWith("file://")) {
                mp.setDataSource(appCtx, Uri.parse(st.url))
            } else {
                mp.setDataSource(st.url)
            }
            mp.prepareAsync()
            radioPlayer = mp
        } catch (e: Exception) {
            isRadioBuffering = false
            isRadioPlaying = false
            abandonAudioFocus(appCtx)
            radioError = "Error al abrir: ${e.message ?: "red"}"
            notifyRadioChanged(appCtx)
        }
    }

    /** Extrae en segundo plano el StreamTitle ICY de la emisora en curso cada 20 s (sin interferir en el audio). */
    private fun startIcyMetadataPolling(appCtx: Context, st: RadioStation) {
        val seq = ++icyPollSeq
        val pollTask = object : Runnable {
            override fun run() {
                if (seq != icyPollSeq || !isRadioPlaying || currentStation()?.url != st.url) return
                ioPool.execute {
                    val title = fetchIcyStreamTitle(st.url)
                    if (seq == icyPollSeq && !title.isNullOrBlank() && title != radioLiveTitle) {
                        radioLiveTitle = title
                        notifyRadioChanged(appCtx)
                    }
                }
                mainHandler.postDelayed(this, 20_000L)
            }
        }
        mainHandler.postDelayed(pollTask, 1500L)
    }

    private fun fetchIcyStreamTitle(streamUrl: String): String? {
        if (streamUrl.endsWith(".m3u8", ignoreCase = true)) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                instanceFollowRedirects = true
                setRequestProperty("Icy-MetaData", "1")
                setRequestProperty("User-Agent", "QonectaUnit/1.0")
            }
            val metaInt = conn.getHeaderFieldInt("icy-metaint", -1)
            if (metaInt <= 0 || metaInt > 131072) return null
            conn.inputStream.use { input ->
                var remaining = metaInt
                val skipBuf = ByteArray(4096)
                while (remaining > 0) {
                    val r = input.read(skipBuf, 0, minOf(skipBuf.size, remaining))
                    if (r <= 0) return null
                    remaining -= r
                }
                val lenByte = input.read()
                if (lenByte <= 0) return null
                val metaLen = lenByte * 16
                val metaBytes = ByteArray(metaLen)
                var readMeta = 0
                while (readMeta < metaLen) {
                    val r = input.read(metaBytes, readMeta, metaLen - readMeta)
                    if (r <= 0) break
                    readMeta += r
                }
                val metaStr = String(metaBytes, 0, readMeta, StandardCharsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                val m = Pattern.compile("StreamTitle='([^']*)'", Pattern.CASE_INSENSITIVE).matcher(metaStr)
                if (m.find()) {
                    val raw = m.group(1)?.trim().orEmpty()
                    if (raw.isNotEmpty() && !raw.equals("unknown", ignoreCase = true)) raw else null
                } else null
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    fun toggleRadio(context: Context) {
        val mp = radioPlayer
        if (mp == null) {
            if (resumeSavedRadioIfAvailable(context, forceEvenIfToggleOff = true)) return
            val list = getRadioRecents(context).ifEmpty { DEFAULT_SPANISH_AND_GLOBAL_STATIONS }
            playStation(context, list, 0)
            return
        }
        try {
            if (mp.isPlaying) {
                stopByUser = true
                saveResumeState(context.applicationContext, currentPositionMs())
                mp.pause()
                isRadioPlaying = false
                abandonAudioFocus(context.applicationContext)
            } else {
                stopByUser = false
                requestAudioFocus(context.applicationContext)
                mp.start()
                isRadioPlaying = true
            }
        } catch (_: Exception) {
            currentStation()?.let { startRadioInternal(context.applicationContext, it) }
        }
        notifyRadioChanged(context)
    }

    fun stepRadio(context: Context, delta: Int) {
        if (radioPlaylist.isEmpty()) {
            val list = getRadioRecents(context).ifEmpty { DEFAULT_SPANISH_AND_GLOBAL_STATIONS }
            playStation(context, list, 0)
            return
        }
        stopByUser = false
        radioIndex = (radioIndex + delta + radioPlaylist.size) % radioPlaylist.size
        val st = radioPlaylist[radioIndex]
        if (!st.local) {
            addRadioRecent(context.applicationContext, st)
        }
        saveResumeState(context.applicationContext, 0L)
        startRadioInternal(context.applicationContext, st)
    }

    fun stopRadio(context: Context) {
        stopByUser = true
        saveResumeState(context.applicationContext, currentPositionMs())
        abandonAudioFocus(context.applicationContext)
        stopRadioInternal(notify = false)
        notifyRadioChanged(context)
    }

    private fun stopRadioInternal(notify: Boolean) {
        icyPollSeq++
        val mp = radioPlayer
        radioPlayer = null
        isRadioPlaying = false
        isRadioBuffering = false
        radioLiveTitle = ""
        if (mp != null) {
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        if (notify) notifyRadioChanged(null)
    }

    private fun abandonAudioFocus(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { req ->
                runCatching { am.abandonAudioFocusRequest(req) }
            }
            audioFocusRequest = null
        }
    }

    private fun requestAudioFocus(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            abandonAudioFocus(context)
            // Usamos AUDIOFOCUS_GAIN_TRANSIENT para que la radio nativa FM/DAB del Leapmotor C10
            // solo pause temporalmente y nunca quede bloqueada ni silenciada de forma permanente.
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            runCatching {
                                if (radioPlayer?.isPlaying == true) {
                                    radioPlayer?.pause()
                                    isRadioPlaying = false
                                }
                                abandonAudioFocus(context.applicationContext)
                                notifyRadioChanged(context)
                            }
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            runCatching { radioPlayer?.setVolume(0.25f, 0.25f) }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            runCatching { radioPlayer?.setVolume(1.0f, 1.0f) }
                        }
                    }
                }
                .build()
            audioFocusRequest = req
            runCatching { am.requestAudioFocus(req) }
        }
    }

    // ---- Auto-Resume al conectar (Portado de RadioPlayer.saveForResume / resumeSaved) ----

    fun isRadioAutoResumeEnabled(context: Context): Boolean {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RADIO_AUTO_RESUME, false)
    }

    fun setRadioAutoResumeEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_RADIO_AUTO_RESUME, enabled).apply()
        if (enabled) saveResumeState(context.applicationContext, currentPositionMs())
    }

    private fun saveResumeState(context: Context, posMs: Long) {
        if (radioPlaylist.isEmpty() || radioIndex !in radioPlaylist.indices) return
        try {
            val from = maxOf(0, radioIndex - 30)
            val to = minOf(radioPlaylist.size, radioIndex + 30)
            val sub = radioPlaylist.subList(from, to)
            val o = JSONObject()
            o.put("list", JSONArray(stationsToJson(sub)))
            o.put("index", radioIndex - from)
            o.put("pos", maxOf(0L, posMs))
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_RADIO_RESUME_STATE, o.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun resumeSavedRadioIfAvailable(context: Context, forceEvenIfToggleOff: Boolean = false): Boolean {
        if (!forceEvenIfToggleOff && !isRadioAutoResumeEnabled(context)) return false
        if (isRadioPlaying || isRadioBuffering) return true
        val raw = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RADIO_RESUME_STATE, "") ?: ""
        if (raw.isBlank()) return false
        return try {
            val o = JSONObject(raw)
            val list = parseStationsJson(o.optJSONArray("list")?.toString() ?: "")
            val idx = o.optInt("index", -1)
            val pos = o.optLong("pos", 0L)
            if (list.isNotEmpty() && idx in list.indices) {
                playStation(context, list, idx, if (list[idx].local) pos else 0L)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    fun getSelectedRadioCountry(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_RADIO_COUNTRY, "") ?: ""
        return if (saved.length == 2) saved.uppercase(Locale.ROOT) else autoDetectCountry(context)
    }

    fun setSelectedRadioCountry(context: Context, iso: String) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_RADIO_COUNTRY, iso.uppercase(Locale.ROOT)).apply()
    }

    // ---- Múltiples Listas M3U de Radio (Portado de Config.m3uLists(Config.RADIO)) ----

    fun getRadioM3uLists(context: Context): List<M3uListEntry> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RADIO_M3U_LISTS, "") ?: ""
        val out = ArrayList<M3uListEntry>()
        if (raw.isNotBlank()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name").trim()
                    val url = o.optString("url").trim()
                    if (url.isNotEmpty()) {
                        out.add(M3uListEntry(name.ifEmpty { "Lista M3U ${out.size + 1}" }, url))
                    }
                }
            } catch (_: Exception) {
            }
        }
        val legacy = prefs.getString(KEY_RADIO_CUSTOM_M3U, "")?.trim().orEmpty()
        if (out.isEmpty() && legacy.isNotEmpty()) {
            out.add(M3uListEntry("Mi Lista M3U", legacy))
        }
        return out
    }

    fun saveRadioM3uLists(context: Context, lists: List<M3uListEntry>, selectedIdx: Int = 0) {
        val arr = JSONArray()
        for (item in lists) {
            if (item.url.isNotBlank()) {
                arr.put(JSONObject().put("name", item.name.ifBlank { "Lista M3U" }).put("url", item.url.trim()))
            }
        }
        val firstUrl = lists.firstOrNull()?.url?.trim().orEmpty()
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RADIO_M3U_LISTS, arr.toString())
            .putString(KEY_RADIO_CUSTOM_M3U, firstUrl)
            .putInt(KEY_RADIO_M3U_SELECTED, selectedIdx.coerceAtLeast(0))
            .apply()
    }

    fun getSelectedRadioM3uIndex(context: Context): Int {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_RADIO_M3U_SELECTED, 0)
    }

    fun setSelectedRadioM3uIndex(context: Context, index: Int) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_RADIO_M3U_SELECTED, index.coerceAtLeast(0)).apply()
    }

    fun getCustomRadioM3u(context: Context): String {
        val lists = getRadioM3uLists(context)
        val idx = getSelectedRadioM3uIndex(context)
        if (lists.isNotEmpty()) {
            return lists.getOrElse(idx) { lists.first() }.url
        }
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RADIO_CUSTOM_M3U, "") ?: ""
    }

    fun setCustomRadioM3u(context: Context, url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            saveRadioM3uLists(context, emptyList(), 0)
            return
        }
        val current = getRadioM3uLists(context).toMutableList()
        val existingIdx = current.indexOfFirst { it.url.equals(trimmed, ignoreCase = true) }
        if (existingIdx >= 0) {
            setSelectedRadioM3uIndex(context, existingIdx)
        } else {
            val hostLabel = runCatching { Uri.parse(trimmed).host ?: "Lista ${current.size + 1}" }.getOrDefault("Lista ${current.size + 1}")
            current.add(M3uListEntry("📋 $hostLabel", trimmed))
            saveRadioM3uLists(context, current, current.size - 1)
        }
    }

    fun getRadioFavorites(context: Context): List<RadioStation> {
        val raw = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RADIO_FAVS, "") ?: ""
        return parseStationsJson(raw)
    }

    fun isRadioFavorite(context: Context, station: RadioStation): Boolean {
        return getRadioFavorites(context).any { it.url == station.url }
    }

    fun toggleRadioFavorite(context: Context, station: RadioStation): Boolean {
        val current = getRadioFavorites(context).toMutableList()
        val idx = current.indexOfFirst { it.url == station.url }
        val added = if (idx >= 0) {
            current.removeAt(idx)
            false
        } else {
            current.add(0, station)
            true
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_RADIO_FAVS, stationsToJson(current)).apply()
        return added
    }

    fun getRadioRecents(context: Context): List<RadioStation> {
        val raw = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RADIO_RECENT, "") ?: ""
        return parseStationsJson(raw)
    }

    private fun addRadioRecent(context: Context, station: RadioStation) {
        if (station.local) return
        val current = getRadioRecents(context).filterNot { it.url == station.url }.toMutableList()
        current.add(0, station)
        val trimmed = current.take(30)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_RADIO_RECENT, stationsToJson(trimmed)).apply()
    }

    // ---- Mi Música Local / Pendrive USB C10 (Portado de MusicLibrary.java + escáner directo de Pendrive) ----

    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav")

    fun queryLocalAndUsbMusic(context: Context): List<RadioStation> {
        val out = ArrayList<RadioStation>()
        val seenPaths = HashSet<String>()

        // 1. Escanear Pendrive USB conectado al C10 (ej. /storage/0AFD-2C95) y carpetas Music/Download/Podcasts
        val roots = ArrayList<File>()
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (sm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                for (vol in sm.storageVolumes) {
                    val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        vol.directory
                    } else {
                        runCatching {
                            val m = vol.javaClass.getMethod("getPathFile")
                            m.invoke(vol) as? File
                        }.getOrNull()
                    }
                    if (dir != null && dir.exists()) roots.add(dir)
                }
            }
        } catch (_: Exception) {
        }
        File("/storage").listFiles()?.forEach { child ->
            if (child.isDirectory && child.name != "self" && child.name != "emulated" && !roots.contains(child)) {
                roots.add(child)
            }
        }
        roots.add(File("/storage/emulated/0/Music"))
        roots.add(File("/storage/emulated/0/Download"))

        for (root in roots) {
            if (!root.exists() || !root.canRead()) continue
            scanAudioFolderRecursive(root, 0, out, seenPaths)
            if (out.size >= 1500) break
        }

        // 2. Consultar también MediaStore.Audio.Media (igual que MusicLibrary.query en Qonecta+)
        try {
            val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val cols = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATA
            )
            val where = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 15000"
            val order = "${MediaStore.Audio.Media.ARTIST} COLLATE NOCASE, ${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
            context.contentResolver.query(base, cols, where, null, order)?.use { cur ->
                while (cur.moveToNext() && out.size < 2500) {
                    val id = cur.getLong(0)
                    val rawTitle = cur.getString(1)
                    val rawArtist = cur.getString(2)
                    val rawAlbum = cur.getString(3) ?: ""
                    val durMs = cur.getLong(4)
                    val dispName = cur.getString(5)
                    val dataPath = cur.getString(6) ?: ""
                    if (dataPath.isNotEmpty() && !seenPaths.add(dataPath)) continue

                    val cleanTitle = cleanTrackTitle(rawTitle, dispName)
                    val cleanArtist = cleanTrackArtist(rawArtist)
                    val uriStr = ContentUris.withAppendedId(base, id).toString()
                    out.add(
                        RadioStation(
                            name = cleanTitle,
                            url = uriStr,
                            logo = "",
                            group = formatMusicSubtitle(cleanArtist, rawAlbum, durMs),
                            country = "USB",
                            local = true,
                            album = rawAlbum,
                            durMs = durMs
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        return out
    }

    private fun scanAudioFolderRecursive(
        dir: File,
        depth: Int,
        out: MutableList<RadioStation>,
        seenPaths: MutableSet<String>
    ) {
        if (depth > 4 || out.size >= 1500) return
        val skipNames = setOf("Android", "LOST.DIR", ".Trash", "System Volume Information", "Qonecta_C10_Dump")
        if (dir.name in skipNames || dir.name.startsWith(".")) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (out.size >= 1500) return
            if (f.isDirectory) {
                scanAudioFolderRecursive(f, depth + 1, out, seenPaths)
            } else if (f.isFile && f.length() > 32 * 1024) {
                val ext = f.extension.lowercase(Locale.ROOT)
                if (ext in AUDIO_EXTENSIONS && seenPaths.add(f.absolutePath)) {
                    var title = cleanTrackTitle(null, f.name)
                    var artist = f.parentFile?.name?.takeUnless {
                        it.equals("Music", true) || it.equals("Download", true) || it.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"))
                    } ?: "Pendrive USB C10"
                    var album = ""
                    var durMs = 0L
                    if (out.size < 120) {
                        runCatching {
                            val mmr = MediaMetadataRetriever()
                            mmr.setDataSource(f.absolutePath)
                            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it.trim() }
                            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = cleanTrackArtist(it).ifEmpty { artist } }
                            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { album = it.trim() }
                            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { durMs = it }
                            mmr.release()
                        }
                    }
                    out.add(
                        RadioStation(
                            name = title,
                            url = Uri.fromFile(f).toString(),
                            logo = "",
                            group = formatMusicSubtitle(artist, album, durMs),
                            country = "USB",
                            local = true,
                            album = album,
                            durMs = durMs
                        )
                    )
                }
            }
        }
    }

    private fun cleanTrackTitle(title: String?, fileName: String?): String {
        if (!title.isNullOrBlank()) return title.trim()
        val f = fileName?.trim().orEmpty()
        val dot = f.lastIndexOf('.')
        return if (dot > 0) f.substring(0, dot) else f.ifEmpty { "Pista de audio" }
    }

    private fun cleanTrackArtist(artist: String?): String {
        val a = artist?.trim().orEmpty()
        return if (a.equals("<unknown>", ignoreCase = true)) "" else a
    }

    fun formatMusicSubtitle(artist: String, album: String, durMs: Long): String {
        val durStr = if (durMs > 0) {
            String.format(Locale.ROOT, "%d:%02d", durMs / 60_000L, (durMs / 1000L) % 60L)
        } else ""
        val parts = ArrayList<String>(3)
        if (artist.isNotBlank()) parts.add(artist)
        if (album.isNotBlank() && !album.equals(artist, ignoreCase = true)) parts.add(album)
        if (durStr.isNotEmpty()) parts.add(durStr)
        return parts.joinToString(" · ").ifEmpty { "Audio local / USB" }
    }

    private fun parseStationsJson(raw: String): List<RadioStation> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<RadioStation>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val url = o.optString("url").trim()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    out.add(
                        RadioStation(
                            name = name,
                            url = url,
                            logo = o.optString("logo"),
                            group = o.optString("group"),
                            country = o.optString("country"),
                            local = o.optBoolean("local", false),
                            album = o.optString("album", ""),
                            durMs = o.optLong("dur", 0L)
                        )
                    )
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun stationsToJson(list: List<RadioStation>): String {
        val arr = JSONArray()
        for (s in list) {
            val o = JSONObject()
            o.put("name", s.name)
            o.put("url", s.url)
            o.put("logo", s.logo)
            o.put("group", s.group)
            o.put("country", s.country)
            if (s.local) o.put("local", true)
            if (s.album.isNotEmpty()) o.put("album", s.album)
            if (s.durMs > 0L) o.put("dur", s.durMs)
            arr.put(o)
        }
        return arr.toString()
    }

    /** Descarga las 150 emisoras más escuchadas del país `cc` o por búsqueda `query` desde radio-browser.info (como Qonecta+). */
    fun fetchRadioBrowserStations(cc: String, query: String = ""): List<RadioStation> {
        val path = if (query.isNotBlank()) {
            "/json/stations/search?name=" + URLEncoder.encode(query.trim(), "UTF-8") +
                "&order=clickcount&reverse=true&hidebroken=true&limit=150"
        } else {
            "/json/stations/search?countrycode=" + URLEncoder.encode(cc.uppercase(Locale.ROOT), "UTF-8") +
                "&order=clickcount&reverse=true&hidebroken=true&limit=150"
        }
        for (host in RADIO_DIRECTORY_HOSTS) {
            try {
                val body = httpGetText("https://$host$path", 7000)
                val arr = JSONArray(body)
                val out = ArrayList<RadioStation>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name").trim()
                    val url = o.optString("url_resolved", o.optString("url")).trim()
                    if (name.isNotEmpty() && url.isNotEmpty()) {
                        out.add(
                            RadioStation(
                                name = name,
                                url = url,
                                logo = o.optString("favicon").trim(),
                                group = o.optString("tags").trim(),
                                country = o.optString("countrycode", cc)
                            )
                        )
                    }
                }
                if (out.isNotEmpty()) return out
            } catch (_: Exception) {
            }
        }
        return if (query.isNotBlank()) {
            DEFAULT_SPANISH_AND_GLOBAL_STATIONS.filter {
                it.name.contains(query, ignoreCase = true) || it.group.contains(query, ignoreCase = true)
            }
        } else {
            DEFAULT_SPANISH_AND_GLOBAL_STATIONS.filter { it.country.equals(cc, ignoreCase = true) }
                .ifEmpty { DEFAULT_SPANISH_AND_GLOBAL_STATIONS }
        }
    }

    fun fetchRadioM3uStations(m3uUrl: String, countryIso: String = "es"): List<RadioStation> {
        val converted = convertKnownM3uPage(m3uUrl, countryIso)
        val text = httpGetText(converted, 10000)
        return parseM3uChannels(text).map {
            RadioStation(name = it.name, url = it.url, logo = it.logo, group = it.group)
        }
    }

    // =========================================================================
    // 2. TELEVISIÓN / TDT / IPTV + GUÍA EPG XMLTV EN VIVO (Portado de IptvScreen + Epg + M3uSource)
    // =========================================================================

    data class TvChannel(
        val name: String,
        val group: String,
        val logo: String = "",
        val url: String,
        val urls: List<String> = listOf(url),
        val epgId: String = ""
    )

    const val WEB_PREFIX = "web:"

    data class TvSourcePreset(
        val title: String,
        val url: String,
        val defaultEpgUrl: String = ""
    )

    val TV_SOURCE_PRESETS: List<TvSourcePreset> = listOf(
        TvSourcePreset(
            "🇪🇸 TDT España (Directo)",
            "builtin:es_tdt",
            "https://www.tdtchannels.com/epg/TV.xml.gz"
        ),
        TvSourcePreset(
            "🇪🇸 TDTChannels Oficial (tv.json)",
            "https://www.tdtchannels.com/lists/tv.json",
            "https://www.tdtchannels.com/epg/TV.xml.gz"
        ),
        TvSourcePreset("🇪🇸 IPTV-org España", "https://iptv-org.github.io/iptv/countries/es.m3u"),
        TvSourcePreset("🇧🇷 IPTV-org Brasil", "https://iptv-org.github.io/iptv/countries/br.m3u"),
        TvSourcePreset("🇵🇹 IPTV-org Portugal", "https://iptv-org.github.io/iptv/countries/pt.m3u"),
        TvSourcePreset("🌍 Internacional / Noticias", "https://iptv-org.github.io/iptv/categories/news.m3u")
    )

    val BUILTIN_TDT_CHANNELS: List<TvChannel> = listOf(
        TvChannel(
            name = "La 1 HD (RTVE)",
            group = "Generalistas",
            logo = "https://graph.facebook.com/la1tve/picture?type=large",
            url = "https://rtvelivestream.rtve.es/rtvesec/la1/la1_main_dvr.m3u8",
            urls = listOf(
                "https://rtvelivestream.rtve.es/rtvesec/la1/la1_main_dvr.m3u8",
                "https://ztnr.rtve.es/ztnr/1688877.m3u8",
                WEB_PREFIX + "https://www.rtve.es/play/videos/directo/canales-lineales/la-1/"
            ),
            epgId = "La1.TV"
        ),
        TvChannel(
            name = "La 2 HD (RTVE)",
            group = "Generalistas",
            logo = "https://graph.facebook.com/la2detve/picture?type=large",
            url = "https://rtvelivestream.rtve.es/rtvesec/la2/la2_main_dvr.m3u8",
            urls = listOf(
                "https://rtvelivestream.rtve.es/rtvesec/la2/la2_main_dvr.m3u8",
                "https://ztnr.rtve.es/ztnr/1688885.m3u8",
                WEB_PREFIX + "https://www.rtve.es/play/videos/directo/canales-lineales/la-2/"
            ),
            epgId = "La2.TV"
        ),
        TvChannel(
            name = "Canal 24 Horas (RTVE)",
            group = "Informativos",
            logo = "https://img2.rtve.es/css/rtve.2022/i/logos/logo-24h.png",
            url = "https://rtvelivestream.rtve.es/rtvesec/24h/24h_main_dvr.m3u8",
            urls = listOf(
                "https://rtvelivestream.rtve.es/rtvesec/24h/24h_main_dvr.m3u8",
                "https://ztnr.rtve.es/ztnr/1694255.m3u8",
                WEB_PREFIX + "https://www.rtve.es/play/videos/directo/canales-lineales/24h/"
            ),
            epgId = "24Horas.TV"
        ),
        TvChannel(
            name = "Teledeporte (RTVE)",
            group = "Deportes",
            logo = "https://img2.rtve.es/css/rtve.2022/i/logos/logo-tdp.png",
            url = "https://rtvelivestream.rtve.es/rtvesec/tdp/tdp_main_dvr.m3u8",
            urls = listOf(
                "https://rtvelivestream.rtve.es/rtvesec/tdp/tdp_main_dvr.m3u8",
                "https://ztnr.rtve.es/ztnr/1712295.m3u8",
                WEB_PREFIX + "https://www.rtve.es/play/videos/directo/canales-lineales/tdp/"
            ),
            epgId = "Teledeporte.TV"
        ),
        TvChannel(
            name = "Clan TVE",
            group = "Infantil",
            logo = "https://img2.rtve.es/css/rtve.2022/i/logos/logo-clan.png",
            url = "https://rtvelivestream.rtve.es/rtvesec/clan/clan_main_dvr.m3u8",
            urls = listOf(
                "https://rtvelivestream.rtve.es/rtvesec/clan/clan_main_dvr.m3u8",
                "https://ztnr.rtve.es/ztnr/5466990.m3u8"
            ),
            epgId = "Clan.TV"
        ),
        TvChannel(
            name = "Antena 3 Directo",
            group = "Generalistas",
            logo = "https://www.antena3.com/favicon.ico",
            url = WEB_PREFIX + "https://www.atresplayer.com/directos/antena3/",
            urls = listOf(WEB_PREFIX + "https://www.atresplayer.com/directos/antena3/"),
            epgId = "Antena3.TV"
        ),
        TvChannel(
            name = "laSexta Directo",
            group = "Generalistas",
            logo = "https://www.lasexta.com/favicon.ico",
            url = WEB_PREFIX + "https://www.atresplayer.com/directos/lasexta/",
            urls = listOf(WEB_PREFIX + "https://www.atresplayer.com/directos/lasexta/"),
            epgId = "LaSexta.TV"
        ),
        TvChannel(
            name = "Telecinco Directo",
            group = "Generalistas",
            logo = "https://www.telecinco.es/favicon.ico",
            url = WEB_PREFIX + "https://www.mitele.es/directo/telecinco/",
            urls = listOf(WEB_PREFIX + "https://www.mitele.es/directo/telecinco/"),
            epgId = "Telecinco.TV"
        ),
        TvChannel(
            name = "Cuatro Directo",
            group = "Generalistas",
            logo = "https://www.cuatro.com/favicon.ico",
            url = WEB_PREFIX + "https://www.mitele.es/directo/cuatro/",
            urls = listOf(WEB_PREFIX + "https://www.mitele.es/directo/cuatro/"),
            epgId = "Cuatro.TV"
        ),
        TvChannel(
            name = "Real Madrid TV",
            group = "Deportes",
            logo = "https://www.realmadrid.com/favicon.ico",
            url = "https://rmtv.akamaized.net/hls/live/2043153/rmtv-es-web/master.m3u8",
            urls = listOf("https://rmtv.akamaized.net/hls/live/2043153/rmtv-es-web/master.m3u8"),
            epgId = "RealMadridTV.TV"
        ),
        TvChannel(
            name = "Telemadrid INT",
            group = "Autonómicos",
            logo = "https://www.telemadrid.es/favicon.ico",
            url = "https://telemadridhls2-live-hls.secure2.footprint.net/egress/chandler/telemadrid/telemadrid_1/index.m3u8",
            urls = listOf("https://telemadridhls2-live-hls.secure2.footprint.net/egress/chandler/telemadrid/telemadrid_1/index.m3u8"),
            epgId = "Telemadrid.TV"
        ),
        TvChannel(
            name = "TV3 Catalunya (3Cat)",
            group = "Autonómicos",
            logo = "https://www.ccma.cat/favicon.ico",
            url = "https://directes-tv-int.ccma.cat/live-origin/tv3-hls/master.m3u8",
            urls = listOf("https://directes-tv-int.ccma.cat/live-origin/tv3-hls/master.m3u8"),
            epgId = "TV3.TV"
        ),
        TvChannel(
            name = "Canal Sur Andalucía",
            group = "Autonómicos",
            logo = "https://www.canalsur.es/favicon.ico",
            url = "https://cdnlive.shooowit.net/rtvalive/smil:channel1.smil/playlist.m3u8",
            urls = listOf("https://cdnlive.shooowit.net/rtvalive/smil:channel1.smil/playlist.m3u8"),
            epgId = "CanalSur.TV"
        ),
        TvChannel(
            name = "TVG Galicia",
            group = "Autonómicos",
            logo = "https://www.crtvg.es/favicon.ico",
            url = "https://crtvg-europa.flumotion.com/playlist.m3u8",
            urls = listOf("https://crtvg-europa.flumotion.com/playlist.m3u8"),
            epgId = "TVG.TV"
        ),
        TvChannel(
            name = "ETB Basque",
            group = "Autonómicos",
            logo = "https://www.eitb.eus/favicon.ico",
            url = "https://multimedia.eitb.eus/live-content/etbbasque-hls/master.m3u8",
            urls = listOf("https://multimedia.eitb.eus/live-content/etbbasque-hls/master.m3u8"),
            epgId = "ETB1.TV"
        ),
        TvChannel(
            name = "Aragón TV INT",
            group = "Autonómicos",
            logo = "https://www.aragontelevision.es/favicon.ico",
            url = "https://cartv.streaming.aranova.es/hls/live/aragontv_canal1.m3u8",
            urls = listOf("https://cartv.streaming.aranova.es/hls/live/aragontv_canal1.m3u8"),
            epgId = "AragonTV.TV"
        ),
        TvChannel(
            name = "À Punt València",
            group = "Autonómicos",
            logo = "https://www.apuntmedia.es/favicon.ico",
            url = WEB_PREFIX + "https://www.apuntmedia.es/directe/",
            urls = listOf(WEB_PREFIX + "https://www.apuntmedia.es/directe/"),
            epgId = "APunt.TV"
        ),
        TvChannel(
            name = "Euronews Español",
            group = "Informativos",
            logo = "https://es.euronews.com/favicon.ico",
            url = WEB_PREFIX + "https://es.euronews.com/embed/live",
            urls = listOf(WEB_PREFIX + "https://es.euronews.com/embed/live"),
            epgId = "Euronews.TV"
        ),
        TvChannel(
            name = "DW Español",
            group = "Informativos",
            logo = "https://www.dw.com/favicon.ico",
            url = "https://dwamdstream104.akamaized.net/hls/live/2015530/dwstream104/index.m3u8",
            urls = listOf("https://dwamdstream104.akamaized.net/hls/live/2015530/dwstream104/index.m3u8")
        ),
        TvChannel(
            name = "France 24 Español",
            group = "Informativos",
            logo = "https://www.france24.com/favicon.ico",
            url = "https://static.france24.com/live/F24_ES_HI_HLS/live_web.m3u8",
            urls = listOf("https://static.france24.com/live/F24_ES_HI_HLS/live_web.m3u8")
        ),
        TvChannel(
            name = "Red Bull TV",
            group = "Deportes",
            logo = "https://www.redbull.com/favicon.ico",
            url = "https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8",
            urls = listOf("https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8")
        )
    )

    private val ATTR_PATTERN = Pattern.compile("([a-zA-Z-]+)=\"([^\"]*)\"")
    private val TVG_URL_PATTERN = Pattern.compile("(?:url-tvg|x-tvg-url)=\"([^\"]+)\"")
    private val YT_CHANNEL = Pattern.compile("youtube\\.com/channel/([\\w-]+)/live")
    private val YT_VIDEO = Pattern.compile("youtube\\.com/(?:live/|watch\\?v=|embed/)([\\w-]{6,})")

    fun getSelectedTvSourceIndex(context: Context): Int {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_TV_SOURCE_IDX, 0)
    }

    fun setSelectedTvSourceIndex(context: Context, index: Int) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TV_SOURCE_IDX, index).apply()
    }

    fun getCustomTvM3u(context: Context): String {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TV_CUSTOM_M3U, "") ?: ""
    }

    fun setCustomTvM3u(context: Context, url: String) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TV_CUSTOM_M3U, url.trim()).apply()
    }

    /**
     * Entrada de lista M3U / JSON / Xtream Codes personalizada (portado de M3uSource.java + Config.m3uLists en Qonecta+).
     * Soporta usuario y contraseña opcionales tanto para servidores Xtream Codes (get.php / player_api.php)
     * como para autenticación HTTP Basic.
     */
    data class CustomM3uEntry(
        val id: String,
        val name: String,
        val url: String,
        val username: String = "",
        val password: String = ""
    )

    private const val KEY_TV_CUSTOM_LISTS_JSON = "tv_custom_lists_v2_json"
    private val URL_PARTS_PATTERN = Pattern.compile("(?i)(https?://)(?:([^/?#@]*)@)?([^/?#]*)(/[^?#]*)?(?:\\?([^#]*))?(#.*)?")

    fun getCustomTvLists(context: Context): List<CustomM3uEntry> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TV_CUSTOM_LISTS_JSON, "") ?: ""
        val out = ArrayList<CustomM3uEntry>()
        if (raw.isNotBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val u = o.optString("url").trim()
                    if (u.isNotEmpty()) {
                        out.add(
                            CustomM3uEntry(
                                id = o.optString("id", "list_$i"),
                                name = o.optString("name", "Mi Lista ${i + 1}").ifBlank { "Mi Lista ${i + 1}" },
                                url = u,
                                username = o.optString("username", ""),
                                password = o.optString("password", "")
                            )
                        )
                    }
                }
            }
        }
        val legacy = getCustomTvM3u(context)
        if (out.isEmpty() && legacy.isNotBlank()) {
            out.add(CustomM3uEntry(id = "legacy_1", name = "Mi Lista M3U", url = legacy))
        }
        return out
    }

    fun saveCustomTvLists(context: Context, lists: List<CustomM3uEntry>) {
        val arr = JSONArray()
        for (item in lists) {
            if (item.url.isBlank()) continue
            val o = JSONObject()
            o.put("id", item.id)
            o.put("name", item.name)
            o.put("url", item.url.trim())
            o.put("username", item.username.trim())
            o.put("password", item.password)
            arr.put(o)
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TV_CUSTOM_LISTS_JSON, arr.toString()).apply()
        if (lists.isNotEmpty()) {
            setCustomTvM3u(context, lists.first().url)
        }
    }

    /**
     * Portado de M3uSource.xtreamUrl y M3uSource.request en Qonecta+:
     * Construye {URL efectiva, Cabecera Authorization o null} a partir de una URL y credenciales opcionales.
     */
    fun buildAuthenticatedM3uRequest(rawUrl: String, username: String = "", password: String = ""): Pair<String, String?> {
        val u = rawUrl.trim()
        val user = username.trim()
        val pass = password
        if ((user.isEmpty() && pass.isEmpty()) || !u.startsWith("http", ignoreCase = true)) {
            return u to null
        }
        val m = URL_PARTS_PATTERN.matcher(u)
        if (m.matches()) {
            val path = m.group(4) ?: ""
            val lowerPath = path.lowercase(Locale.ROOT)
            val isXtream = lowerPath.isEmpty() || lowerPath == "/" || lowerPath.endsWith("/get.php") || lowerPath.endsWith("/player_api.php")
            if (isXtream) {
                val effPath = when {
                    lowerPath.endsWith("/get.php") -> path
                    lowerPath.endsWith("/player_api.php") -> path.substring(0, path.length - "player_api.php".length) + "get.php"
                    else -> "/get.php"
                }
                val query = if (lowerPath.endsWith("/get.php")) m.group(5) else null
                val q = StringBuilder("username=").append(urlEnc(user)).append("&password=").append(urlEnc(pass))
                var hasType = false
                var hasOutput = false
                if (!query.isNullOrBlank()) {
                    for (p in query.split("&")) {
                        val eq = p.indexOf('=')
                        val k = (if (eq >= 0) p.substring(0, eq) else p).lowercase(Locale.ROOT)
                        if (p.isEmpty() || k == "username" || k == "password") continue
                        if (k == "type") hasType = true
                        if (k == "output") hasOutput = true
                        q.append('&').append(p)
                    }
                }
                if (!hasType) q.append("&type=m3u_plus")
                if (!hasOutput) q.append("&output=ts")
                return "${m.group(1)}${m.group(3)}$effPath?$q" to null
            }
        }
        val authBytes = "$user:$pass".toByteArray(StandardCharsets.UTF_8)
        val basicHeader = "Basic " + android.util.Base64.encodeToString(authBytes, android.util.Base64.NO_WRAP)
        return u to basicHeader
    }

    fun getTvFavorites(context: Context): List<TvChannel> {
        val raw = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TV_FAVS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<TvChannel>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val url = o.optString("url").trim()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    val urlsArr = o.optJSONArray("urls")
                    val urls = ArrayList<String>()
                    if (urlsArr != null) {
                        for (k in 0 until urlsArr.length()) {
                            val u = urlsArr.optString(k).trim()
                            if (u.isNotEmpty()) urls.add(u)
                        }
                    }
                    if (urls.isEmpty()) urls.add(url)
                    out.add(
                        TvChannel(
                            name = name,
                            group = o.optString("group", "Favoritos"),
                            logo = o.optString("logo"),
                            url = url,
                            urls = urls,
                            epgId = o.optString("epg", "")
                        )
                    )
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isTvFavorite(context: Context, ch: TvChannel): Boolean {
        return getTvFavorites(context).any { it.name.equals(ch.name, ignoreCase = true) || it.url == ch.url }
    }

    fun toggleTvFavorite(context: Context, ch: TvChannel): Boolean {
        val current = getTvFavorites(context).toMutableList()
        val idx = current.indexOfFirst { it.name.equals(ch.name, ignoreCase = true) || it.url == ch.url }
        val added = if (idx >= 0) {
            current.removeAt(idx)
            false
        } else {
            current.add(0, ch)
            true
        }
        val arr = JSONArray()
        for (c in current) {
            val o = JSONObject()
            o.put("name", c.name)
            o.put("group", c.group)
            o.put("logo", c.logo)
            o.put("url", c.url)
            if (c.epgId.isNotEmpty()) o.put("epg", c.epgId)
            val ua = JSONArray()
            c.urls.forEach { ua.put(it) }
            o.put("urls", ua)
            arr.put(o)
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TV_FAVS, arr.toString()).apply()
        return added
    }

    data class FetchedTvList(
        val channels: List<TvChannel>,
        val epgUrl: String? = null,
        val convertedFrom: String? = null
    )

    fun fetchTvChannelsWithEpg(sourceUrl: String, username: String = "", password: String = ""): FetchedTvList {
        if (sourceUrl == "builtin:es_tdt" || sourceUrl.isBlank()) {
            return FetchedTvList(
                channels = BUILTIN_TDT_CHANNELS,
                epgUrl = "https://www.tdtchannels.com/epg/TV.xml.gz"
            )
        }
        val converted = convertKnownM3uPage(sourceUrl, "es")
        val convertedNotice = if (converted != sourceUrl.trim()) converted else null
        val (effectiveUrl, authHeader) = buildAuthenticatedM3uRequest(converted, username, password)
        val text = httpGetText(effectiveUrl, 12000, authHeader).trim().removePrefix("\uFEFF").trim()
        val isJson = text.startsWith("{")
        val parsed = if (isJson) parseTdtJson(text) else parseM3uChannels(text)
        val epgUrl = if (isJson) extractJsonEpgUrl(text) else extractM3uEpgUrl(text)
        val merged = mergeTvChannels(parsed).ifEmpty { BUILTIN_TDT_CHANNELS }
        return FetchedTvList(channels = merged, epgUrl = epgUrl, convertedFrom = convertedNotice)
    }

    fun fetchTvChannels(sourceUrl: String, username: String = "", password: String = ""): List<TvChannel> =
        fetchTvChannelsWithEpg(sourceUrl, username, password).channels

    /** Portado de M3uSource.convert en Qonecta+ (incluye iprd.org, iptv-org, tdtchannels y github blob). */
    fun convertKnownM3uPage(url: String, cc: String): String {
        val u = url.trim()
        if (u.contains("github.com/") && (u.contains("/blob/") || u.contains("/raw/"))) {
            return u.replace("github.com/", "raw.githubusercontent.com/")
                .replace("/blob/", "/")
                .replace("/raw/", "/")
        }
        if (u.equals("https://iptv-org.github.io", ignoreCase = true) ||
            u.equals("https://github.com/iptv-org/iptv", ignoreCase = true)
        ) {
            return "https://iptv-org.github.io/iptv/countries/${cc.lowercase(Locale.ROOT)}.m3u"
        }
        if (u.contains("iprd.org", ignoreCase = true) || u.contains("famelack.com", ignoreCase = true)) {
            return "https://raw.githubusercontent.com/iprd-org/iprd/main/streams/${cc.lowercase(Locale.ROOT)}/${cc.lowercase(Locale.ROOT)}.m3u"
        }
        if (u.equals("https://www.tdtchannels.com", ignoreCase = true) ||
            u.equals("https://tdtchannels.com", ignoreCase = true)
        ) {
            return "https://www.tdtchannels.com/lists/tv.json"
        }
        return u
    }

    private fun extractM3uEpgUrl(text: String): String? {
        var count = 0
        for (rawLine in text.lineSequence()) {
            val l = rawLine.trim()
            if (l.startsWith("#EXTINF", ignoreCase = true) || ++count > 40) break
            if (!l.startsWith("#EXTM3U", ignoreCase = true)) continue
            val m = TVG_URL_PATTERN.matcher(l)
            if (m.find()) {
                val u = m.group(1)?.split(",")?.firstOrNull()?.trim().orEmpty()
                if (u.startsWith("http")) return u
            }
        }
        return null
    }

    private fun extractJsonEpgUrl(json: String): String? {
        return try {
            val epgObj = JSONObject(json).optJSONObject("epg") ?: return null
            val u = epgObj.optString("xml.gz", epgObj.optString("xml", "")).trim()
            if (u.startsWith("http")) u else null
        } catch (_: Exception) {
            null
        }
    }

    /** Portado de IptvScreen.parseTdtJson en Qonecta+. */
    fun parseTdtJson(json: String): List<TvChannel> {
        val out = ArrayList<TvChannel>()
        val countries = JSONObject(json).optJSONArray("countries") ?: return out
        for (i in 0 until countries.length()) {
            val country = countries.optJSONObject(i) ?: continue
            val ambits = country.optJSONArray("ambits") ?: continue
            for (j in 0 until ambits.length()) {
                val ambit = ambits.optJSONObject(j) ?: continue
                val chans = ambit.optJSONArray("channels") ?: continue
                val group = ambit.optString("name").trim()
                for (k in 0 until chans.length()) {
                    if (out.size >= 4000) break
                    val o = chans.optJSONObject(k) ?: continue
                    val name = o.optString("name").trim()
                    val logo = if (o.isNull("logo")) "" else o.optString("logo").trim()
                    val epgId = if (o.isNull("epg_id")) "" else o.optString("epg_id").trim()
                    val urls = ArrayList<String>()
                    val webUrls = ArrayList<String>()
                    val opts = o.optJSONArray("options")
                    if (opts != null) {
                        for (n in 0 until opts.length()) {
                            val op = opts.optJSONObject(n) ?: continue
                            if (op.isNull("url")) continue
                            val fmt = op.optString("format")
                            val u = op.optString("url").trim()
                            if (u.isEmpty()) continue
                            if (fmt == "m3u8" || looksLikeDirectVideo(u)) {
                                urls.add(u)
                            } else if (fmt == "youtube" || fmt == "stream") {
                                webUrls.add(WEB_PREFIX + u)
                            }
                        }
                    }
                    urls.addAll(webUrls)
                    val site = if (o.isNull("web")) "" else o.optString("web").trim()
                    if (urls.isEmpty() && site.startsWith("http")) {
                        urls.add(WEB_PREFIX + site)
                    }
                    if (name.isEmpty() || urls.isEmpty()) continue
                    out.add(TvChannel(name = name, group = group, logo = logo, url = urls[0], urls = urls, epgId = epgId))
                }
            }
        }
        return out
    }

    /** Portado de IptvScreen.parse en Qonecta+. */
    fun parseM3uChannels(text: String): List<TvChannel> {
        val out = ArrayList<TvChannel>()
        BufferedReader(StringReader(text)).use { reader ->
            var pendingName: String? = null
            var pendingLogo = ""
            var pendingGroup = "General"
            var pendingEpg = ""
            var line: String?
            while (reader.readLine().also { line = it } != null && out.size < 5000) {
                val l = line!!.trim().removePrefix("\uFEFF").trim()
                if (l.isEmpty()) continue
                if (l.startsWith("#EXTINF", ignoreCase = true)) {
                    val comma = l.lastIndexOf(',')
                    pendingName = if (comma >= 0) l.substring(comma + 1).trim() else "Canal"
                    pendingLogo = ""
                    pendingGroup = "General"
                    pendingEpg = ""
                    val attrPart = if (comma >= 0) l.substring(0, comma) else l
                    val m = ATTR_PATTERN.matcher(attrPart)
                    while (m.find()) {
                        when (m.group(1)) {
                            "tvg-logo" -> pendingLogo = m.group(2) ?: ""
                            "group-title" -> pendingGroup = (m.group(2) ?: "").ifBlank { "General" }
                            "tvg-id" -> pendingEpg = (m.group(2) ?: "").trim()
                        }
                    }
                } else if (!l.startsWith("#") && (l.startsWith("http://") || l.startsWith("https://"))) {
                    val name = pendingName ?: l
                    out.add(
                        TvChannel(
                            name = name,
                            group = pendingGroup,
                            logo = pendingLogo,
                            url = l,
                            urls = listOf(l),
                            epgId = pendingEpg
                        )
                    )
                    pendingName = null
                }
            }
        }
        return out
    }

    private fun mergeTvChannels(list: List<TvChannel>): List<TvChannel> {
        val byKey = LinkedHashMap<String, TvChannel>()
        for (ch in list) {
            val key = "${ch.group}\u0000${ch.name.lowercase(Locale.ROOT)}"
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = ch
            } else {
                val combined = existing.urls.toMutableList()
                for (u in ch.urls) if (!combined.contains(u)) combined.add(u)
                byKey[key] = existing.copy(
                    logo = existing.logo.ifBlank { ch.logo },
                    urls = combined,
                    epgId = existing.epgId.ifBlank { ch.epgId }
                )
            }
        }
        return byKey.values.toList()
    }

    fun looksLikeDirectVideo(url: String): Boolean {
        val clean = url.lowercase(Locale.ROOT).replace(Regex("[?#].*$"), "")
        return clean.endsWith(".m3u8") || clean.endsWith(".mpd") || clean.endsWith(".ts") || clean.endsWith(".mp4")
    }

    /** Portado de IptvScreen.embedFor en Qonecta+. */
    fun embedUrlFor(url: String): String? {
        var m = YT_CHANNEL.matcher(url)
        if (m.find()) return "https://www.youtube.com/embed/live_stream?channel=${m.group(1)}&autoplay=1&playsinline=1"
        m = YT_VIDEO.matcher(url)
        if (m.find()) return "https://www.youtube.com/embed/${m.group(1)}?autoplay=1&playsinline=1"
        if (url.startsWith("https://player.twitch.tv/") || url.startsWith("http://player.twitch.tv/")) {
            val u = url.replace(Regex("([?&])parent=[^&]*&?"), "$1").replace(Regex("[?&]$"), "")
            return u + (if (u.contains("?")) "&" else "?") + "parent=charlysev.github.io&autoplay=true&muted=false"
        }
        return null
    }

    fun embedHtml(src: String): String {
        val safe = src.replace("&", "&amp;").replace("\"", "&quot;")
        return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{border:0;width:100%;height:100%}</style>" +
            "</head><body><iframe src=\"$safe\" allow=\"autoplay; encrypted-media; fullscreen; picture-in-picture\" " +
            "allowfullscreen referrerpolicy=\"strict-origin-when-cross-origin\"></iframe></body></html>"
    }

    // ---- Guía de Programación XMLTV (Portado de Epg.java en Qonecta+) ----

    data class EpgProg(val start: Long, val stop: Long, val title: String)

    class EpgGuide(val parsedAt: Long) {
        val byId = HashMap<String, MutableList<EpgProg>>()
        val idByName = HashMap<String, String>()

        private fun listFor(epgId: String?, name: String?): List<EpgProg>? {
            var l = if (epgId.isNullOrBlank()) null else byId[epgId]
            if (l == null && !epgId.isNullOrBlank()) {
                val k = idByName[normEpgName(epgId)]
                if (k != null) l = byId[k]
            }
            if (l == null && !name.isNullOrBlank()) {
                val k = idByName[normEpgName(name)]
                if (k != null) l = byId[k]
            }
            return l
        }

        fun now(epgId: String?, name: String?, nowMs: Long = System.currentTimeMillis()): EpgProg? {
            val l = listFor(epgId, name) ?: return null
            for (p in l) {
                if (p.start > nowMs) break
                if (nowMs < p.stop) return p
            }
            return null
        }

        fun next(epgId: String?, name: String?, nowMs: Long = System.currentTimeMillis()): EpgProg? {
            val l = listFor(epgId, name) ?: return null
            for (p in l) {
                if (p.start > nowMs) return p
            }
            return null
        }
    }

    private var cachedEpgUrl: String? = null
    private var cachedEpgGuide: EpgGuide? = null
    private val epgTimeFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)

    fun formatEpgNow(prog: EpgProg): String {
        return "${prog.title} · hasta ${epgTimeFormat.format(Date(prog.stop))}"
    }

    fun normEpgName(s: String): String {
        var t = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .lowercase(Locale.ROOT)
            .trim()
        t = t.replace(Regex("\\(.*?\\)"), "").trim()
        t = t.replace(Regex("\\.tv$"), "").replace(Regex("\\s+(hd|tv|directo|int)$"), "")
        return t.replace(Regex("[^a-z0-9]"), "")
    }

    fun parseXmltvTime(s: String?): Long {
        val clean = s?.trim().orEmpty()
        if (clean.length < 12) return -1L
        return try {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
            cal.clear()
            val sec = if (clean.length >= 14 && clean[12].isDigit() && clean[13].isDigit()) {
                clean.substring(12, 14).toInt()
            } else 0
            cal.set(
                clean.substring(0, 4).toInt(),
                clean.substring(4, 6).toInt() - 1,
                clean.substring(6, 8).toInt(),
                clean.substring(8, 10).toInt(),
                clean.substring(10, 12).toInt(),
                sec
            )
            var ms = cal.timeInMillis
            val sp = clean.indexOf(' ')
            if (sp > 0) {
                val z = clean.substring(sp + 1).trim()
                if (z.length == 5 && (z[0] == '+' || z[0] == '-')) {
                    val offMin = z.substring(1, 3).toInt() * 60 + z.substring(3, 5).toInt()
                    ms -= (if (z[0] == '-') -offMin else offMin) * 60_000L
                }
            }
            ms
        } catch (_: Exception) {
            -1L
        }
    }

    fun loadEpgGuide(context: Context, url: String?): EpgGuide? {
        if (url.isNullOrBlank() || !url.startsWith("http")) return null
        val now = System.currentTimeMillis()
        synchronized(this) {
            val cur = cachedEpgGuide
            if (url == cachedEpgUrl && cur != null && now - cur.parsedAt < 3 * 3600_000L) {
                return cur
            }
        }
        val dir = File(context.applicationContext.cacheDir, "epg")
        val file = File(dir, Integer.toHexString(url.hashCode()) + ".xml")
        if (!file.exists() || now - file.lastModified() > 6 * 3600_000L) {
            runCatching { downloadEpgFile(url, dir, file) }
        }
        if (!file.exists()) return null
        return try {
            FileInputStream(file).use { fis ->
                val g = parseXmltvStream(fis, now)
                synchronized(this) {
                    cachedEpgUrl = url
                    cachedEpgGuide = g
                }
                g
            }
        } catch (_: Exception) {
            runCatching { file.delete() }
            null
        }
    }

    private fun downloadEpgFile(url: String, dir: File, target: File) {
        if (!dir.isDirectory && !dir.mkdirs()) return
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "QonectaUnit/1.0 (Leapmotor C10)")
        }
        try {
            if (conn.responseCode >= 400) return
            val tmp = File(dir, target.name + ".part")
            var total = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var r: Int
                    while (input.read(buf).also { r = it } > 0) {
                        total += r
                        if (total > 36L * 1024L * 1024L) throw IllegalStateException("EPG too large")
                        out.write(buf, 0, r)
                    }
                }
            }
            tmp.renameTo(target)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseXmltvStream(raw: InputStream, nowMs: Long): EpgGuide {
        var stream: InputStream = BufferedInputStream(raw)
        stream.mark(2)
        val b0 = stream.read()
        val b1 = stream.read()
        stream.reset()
        if (b0 == 0x1F && b1 == 0x8B) {
            stream = GZIPInputStream(stream)
        }
        val guide = EpgGuide(nowMs)
        val fromMs = nowMs - 3600_000L
        val toMs = nowMs + 16 * 3600_000L
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        var chanId: String? = null
        var progChan: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var totalProgs = 0
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        chanId = parser.getAttributeValue(null, "id")
                        if (!chanId.isNullOrBlank()) {
                            guide.idByName[normEpgName(chanId)] = chanId
                        }
                    }
                    "display-name" -> {
                        if (chanId != null) {
                            val disp = parser.nextText()?.trim().orEmpty()
                            if (disp.isNotEmpty()) {
                                val norm = normEpgName(disp)
                                if (!guide.idByName.containsKey(norm)) {
                                    guide.idByName[norm] = chanId
                                }
                            }
                        }
                    }
                    "programme" -> {
                        progChan = parser.getAttributeValue(null, "channel")
                        start = parseXmltvTime(parser.getAttributeValue(null, "start"))
                        stop = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                        title = null
                    }
                    "title" -> {
                        if (progChan != null && title == null) {
                            title = parser.nextText()?.trim()
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "channel" -> chanId = null
                    "programme" -> {
                        if (progChan != null && !title.isNullOrBlank() &&
                            start > 0 && stop > start && stop > fromMs && start < toMs && totalProgs < 50_000
                        ) {
                            val list = guide.byId.getOrPut(progChan) { ArrayList() }
                            list.add(EpgProg(start, stop, title))
                            totalProgs++
                        }
                        progChan = null
                    }
                }
            }
            ev = parser.next()
        }
        guide.byId.values.forEach { list -> list.sortBy { it.start } }
        return guide
    }

    // ---- Bloqueador ligero de dominios de anuncios para canales Web (Portado de AdBlock.java) ----

    private val AD_DOMAINS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "imasdk.googleapis.com", "adnxs.com", "rubiconproject.com",
        "pubmatic.com", "criteo.com", "taboola.com", "outbrain.com",
        "smartadserver.com", "fwmrm.net", "2mdn.net", "moatads.com"
    )

    fun isAdDomain(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT)?.trim().orEmpty()
        if (h.isEmpty()) return false
        return AD_DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    // =========================================================================
    // 3. KARAOKE SINCRONIZADO (Portado de Lyrics.java + KaraokeScreen.java)
    // =========================================================================

    data class LyricLine(val ms: Long, val text: String)

    data class LyricResult(
        val artist: String,
        val title: String,
        val synced: List<LyricLine> = emptyList(),
        val plain: String = "",
        val instrumental: Boolean = false
    ) {
        fun found(): Boolean = synced.isNotEmpty() || plain.isNotEmpty() || instrumental
    }

    val KARAOKE_QUICK_SONGS: List<Pair<String, String>> = listOf(
        "Queen" to "Don't Stop Me Now",
        "Queen" to "Bohemian Rhapsody",
        "Radiohead" to "No Surprises",
        "Coldplay" to "Viva La Vida",
        "Estopa" to "Como Camarón",
        "Hombres G" to "Devuélveme a mi chica",
        "Adele" to "Rolling in the Deep",
        "The Weeknd" to "Blinding Lights"
    )

    private const val LRCLIB_API = "https://lrclib.net/api/"
    private val LRC_TIME_PATTERN = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
    private val LRC_OFFSET_PATTERN = Pattern.compile("^\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE)
    private val TITLE_JUNK_PATTERN = Pattern.compile(
        "\\s*(?:-\\s*(?:\\d{4}\\s+)?(?:remaster(?:ed)?|remasterizad[oa]|live|en vivo|en directo|radio edit|single version" +
            "|mono|stereo|versi[oó]n|version|edit|mix|acoustic|ac[uú]stic[oa])\\b.*" +
            "|[(\\[](?:feat\\.?|ft\\.?|with|con|remaster|from|de la|live|en vivo|en directo)[^)\\]]*[)\\]])",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    private val lyricsCache = object : LinkedHashMap<String, LyricResult>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricResult>?): Boolean = size > 40
    }

    fun parseLrc(lrc: String?): List<LyricLine> {
        if (lrc.isNullOrBlank()) return emptyList()
        val out = ArrayList<LyricLine>()
        var offset = 0L
        for (raw in lrc.replace("\r", "").split("\n")) {
            val l = raw.trim()
            val off = LRC_OFFSET_PATTERN.matcher(l)
            if (off.find()) {
                offset = (off.group(1)?.replace("+", "")?.toLongOrNull()) ?: 0L
                continue
            }
            val times = ArrayList<Long>()
            var pos = 0
            val m = LRC_TIME_PATTERN.matcher(l)
            while (m.find() && m.start() == pos) {
                var ms = (m.group(1)?.toLongOrNull() ?: 0L) * 60_000L + (m.group(2)?.toLongOrNull() ?: 0L) * 1000L
                val frac = m.group(3)
                if (!frac.isNullOrEmpty()) {
                    ms += Math.round((frac.toLongOrNull() ?: 0L) * Math.pow(10.0, (3 - frac.length).toDouble()))
                }
                times.add(ms)
                pos = m.end()
            }
            if (times.isEmpty()) continue
            val text = l.substring(pos).trim()
            for (t in times) {
                out.add(LyricLine(maxOf(0L, t - offset), text))
            }
        }
        out.sortBy { it.ms }
        return out
    }

    fun lyricIndexAt(lines: List<LyricLine>, ms: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].ms <= ms) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    fun cleanSongTitle(title: String?): String {
        if (title.isNullOrBlank()) return ""
        val cleaned = TITLE_JUNK_PATTERN.matcher(title).replaceAll("").trim()
        return cleaned.ifEmpty { title.trim() }
    }

    fun mainArtist(artist: String?): String {
        if (artist.isNullOrBlank()) return ""
        val stripped = artist.split(Regex("\\s+[\u2022\u00B7]\\s+"))[0].trim()
        val first = stripped.split(Regex(",|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+", RegexOption.IGNORE_CASE))[0].trim()
        return first.ifEmpty { stripped }
    }

    /** Si suena la radio y emite «Artista - Canción», extrae par (artista, canción) igual que RadioSession.song en Qonecta+. */
    fun extractSongFromRadioTitle(rawTitle: String, stationName: String): Pair<String, String>? {
        val clean = rawTitle.removeSuffix(" · $stationName").trim()
        val sep = clean.indexOf(" - ")
        if (sep <= 0 || sep + 3 >= clean.length) return null
        val a = clean.substring(0, sep).trim()
        val t = clean.substring(sep + 3).trim()
        if (a.isEmpty() || t.isEmpty() || a.equals(stationName, ignoreCase = true)) return null
        return a to t
    }

    fun getCachedLyrics(artist: String, title: String): LyricResult? {
        val key = "${mainArtist(artist).lowercase(Locale.ROOT)}|${cleanSongTitle(title).lowercase(Locale.ROOT)}"
        synchronized(lyricsCache) {
            return lyricsCache[key]
        }
    }

    fun fetchLyrics(artist: String, title: String, album: String = "", durationMs: Long = 0L): LyricResult {
        val cleanA = mainArtist(artist)
        val cleanT = cleanSongTitle(title)
        val cacheKey = "${cleanA.lowercase(Locale.ROOT)}|${cleanT.lowercase(Locale.ROOT)}"
        synchronized(lyricsCache) {
            lyricsCache[cacheKey]?.let { return it }
        }
        val durationS = if (durationMs > 0) Math.round(durationMs / 1000.0).toInt() else 0
        var result: LyricResult? = null

        // 1. Exact get
        if (cleanA.isNotEmpty() && cleanT.isNotEmpty()) {
            try {
                val sb = StringBuilder(LRCLIB_API)
                    .append("get?artist_name=").append(urlEnc(cleanA))
                    .append("&track_name=").append(urlEnc(cleanT))
                if (album.isNotBlank()) sb.append("&album_name=").append(urlEnc(album))
                if (durationS > 0) sb.append("&duration=").append(durationS)
                val obj = JSONObject(httpGetText(sb.toString(), 7000))
                result = lyricFromJson(cleanA, cleanT, obj)
            } catch (_: Exception) {
            }
        }

        // 2. Search fallback
        if (result == null || !result.found()) {
            try {
                val searchUrl = if (cleanA.isNotEmpty()) {
                    "${LRCLIB_API}search?track_name=${urlEnc(cleanT)}&artist_name=${urlEnc(cleanA)}"
                } else {
                    "${LRCLIB_API}search?q=${urlEnc(cleanT)}"
                }
                val arr = JSONArray(httpGetText(searchUrl, 8000))
                var bestObj: JSONObject? = null
                var bestScore = Double.MAX_VALUE
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val d = if (durationS > 0) Math.abs(o.optDouble("duration", 0.0) - durationS) else 0.0
                    if (durationS > 0 && d > 12.0 && bestObj != null) continue
                    val synced = !o.isNull("syncedLyrics") && o.optString("syncedLyrics", "").isNotBlank()
                    val plain = !o.isNull("plainLyrics") && o.optString("plainLyrics", "").isNotBlank()
                    val inst = o.optBoolean("instrumental")
                    if (!synced && !plain && !inst) continue
                    val score = d + (if (synced) 0.0 else 100.0)
                    if (score < bestScore) {
                        bestScore = score
                        bestObj = o
                    }
                }
                if (bestObj != null) {
                    result = lyricFromJson(
                        bestObj.optString("artistName", cleanA),
                        bestObj.optString("trackName", cleanT),
                        bestObj
                    )
                }
            } catch (_: Exception) {
            }
        }

        val finalRes = result ?: LyricResult(artist = cleanA, title = cleanT)
        if (finalRes.found()) {
            synchronized(lyricsCache) {
                lyricsCache[cacheKey] = finalRes
            }
        }
        return finalRes
    }

    private fun lyricFromJson(artist: String, title: String, o: JSONObject): LyricResult {
        val syncedRaw = if (o.isNull("syncedLyrics")) "" else o.optString("syncedLyrics", "")
        val plainRaw = if (o.isNull("plainLyrics")) "" else o.optString("plainLyrics", "").trim()
        return LyricResult(
            artist = artist,
            title = title,
            synced = parseLrc(syncedRaw),
            plain = plainRaw,
            instrumental = o.optBoolean("instrumental")
        )
    }

    private fun urlEnc(s: String): String {
        return runCatching { URLEncoder.encode(s, "UTF-8").replace("+", "%20") }.getOrDefault("")
    }

    // =========================================================================
    // 5. ADBLOCK JS, CALIDAD YOUTUBE Y GALERÍA DE VÍDEOS / FOTOS USB (Portado de AdBlockJs.java, YouTubeQuality.java, MediaLibrary.java)
    // =========================================================================

    private const val GENERIC_AD_CSS =
        "ins.adsbygoogle,.adsbygoogle,[id^=\"google_ads_iframe\"],[id^=\"div-gpt-ad\"],[data-google-query-id]," +
            "iframe[src*=\"doubleclick.net\"],iframe[src*=\"googlesyndication.com\"],[id^=\"taboola-\"]," +
            ".trc_related_container,.OUTBRAIN,.ob-widget{display:none!important}"

    private const val YOUTUBE_AD_CSS =
        "ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-companion-ad-renderer,ytm-ad-slot-renderer," +
            "ad-slot-renderer,ytd-ad-slot-renderer,ytd-promoted-sparkles-web-renderer,ytd-promoted-video-renderer," +
            "ytd-display-ad-renderer,ytd-in-feed-ad-layout-renderer,ytd-banner-promo-renderer,ytm-statement-banner-renderer," +
            "ytd-statement-banner-renderer,ytm-paid-content-overlay-renderer,#player-ads,#masthead-ad," +
            ".ytp-ad-overlay-container,.ytp-ad-image-overlay,.ytp-ad-text-overlay{display:none!important}"

    private fun jsQuoted(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Guion completo de AdBlockJs.java en Qonecta+: bloquea anuncios de YouTube y webs, salta y silencia anuncios de vídeo. */
    val ADBLOCK_JS_SCRIPT: String = "(function(){" +
        "if(window.__hqlAd)return;window.__hqlAd=1;" +
        "var yt=/(^|\\.)youtube(-nocookie)?\\.com$/.test(location.hostname);" +
        "var css=" + jsQuoted(GENERIC_AD_CSS) + "+(yt?" + jsQuoted(YOUTUBE_AD_CSS) + ":'');" +
        "function addCss(){try{var s=document.createElement('style');s.textContent=css;" +
        "(document.head||document.documentElement).appendChild(s);}catch(e){}}" +
        "if(document.documentElement)addCss();else document.addEventListener('DOMContentLoaded',addCss);" +
        "if(!yt)return;" +
        "var K=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams'];" +
        "function clean(o){if(!o||typeof o!=='object')return;for(var i=0;i<K.length;i++){if(K[i] in o)try{delete o[K[i]];}catch(e){}}}" +
        "function prune(o){try{if(Array.isArray(o)){for(var j=0;j<o.length;j++){clean(o[j]);if(o[j])clean(o[j].playerResponse);}}" +
        "else if(o&&typeof o==='object'){clean(o);clean(o.playerResponse);}}catch(e){}return o;}" +
        "var P=JSON.parse;JSON.parse=function(){return prune(P.apply(this,arguments));};" +
        "if(window.Response&&Response.prototype.json){var J=Response.prototype.json;" +
        "Response.prototype.json=function(){return J.apply(this,arguments).then(prune);};}" +
        "try{var ip;Object.defineProperty(window,'ytInitialPlayerResponse',{configurable:true," +
        "get:function(){return ip;},set:function(v){ip=prune(v);}});}catch(e){}" +
        "var muted=false;" +
        "setInterval(function(){try{" +
        "var ad=document.querySelector('.ad-showing,.ad-interrupting');var v=document.querySelector('video');" +
        "if(ad&&v){if(!muted){muted=true;v.muted=true;}" +
        "if(isFinite(v.duration)&&v.duration>0&&v.currentTime<v.duration-0.3)v.currentTime=v.duration-0.1;}" +
        "else if(muted&&v){muted=false;v.muted=false;}" +
        "var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button," +
        ".ytp-ad-skip-button-slot button,.ytp-ad-overlay-close-button');if(b)b.click();" +
        "}catch(e){}},400);" +
        "})();"

    /** Guion de YouTubeQuality.java en Qonecta+ (0 = Auto, 360, 480, 720). */
    fun youtubeQualityJs(q: Int): String {
        val safeQ = maxOf(0, q)
        return "(function(q){window.__hqlYtq=q;if(window.__hqlYtqT)return;" +
            "function lv(n){return n>=720?'hd720':n>=480?'large':n>=360?'medium':'small';}" +
            "function ap(){var t=window.__hqlYtq;if(!t)return;" +
            "try{var n=Date.now();localStorage.setItem('yt-player-quality',JSON.stringify({data:JSON.stringify(" +
            "{quality:t,previousQuality:t}),expiration:n+31536000000,creation:n}));}catch(e){}" +
            "var p=document.getElementById('movie_player')||document.querySelector('.html5-video-player');" +
            "if(!p||!p.setPlaybackQualityRange)return;var l=lv(t);" +
            "try{if(p.getPlaybackQuality&&p.getPlaybackQuality()==l)return;p.setPlaybackQualityRange(l,l);" +
            "if(p.setPlaybackQuality)p.setPlaybackQuality(l);}catch(e){}}" +
            "window.__hqlYtqT=setInterval(ap,2000);ap();})($safeQ)"
    }

    data class LocalMediaItem(
        val title: String,
        val subtitle: String,
        val uri: String,
        val isVideo: Boolean = true,
        val albumCategory: String = "USB Pendrive",
        val durationMs: Long = 0L,
        val srtUri: String? = null
    )

    val BUILTIN_DEMO_VIDEOS: List<LocalMediaItem> = listOf(
        LocalMediaItem(
            title = "Big Buck Bunny (HD 1080p · Blender Studio)",
            subtitle = "Película Corta Animación · HLS HD 1080p · Ideal para probar Pantalla Completa 100%",
            uri = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            isVideo = true,
            albumCategory = "Streams Demo HD",
            durationMs = 596_000L
        ),
        LocalMediaItem(
            title = "Sintel / Akamai HD (1080p · Cine & Acción)",
            subtitle = "Stream Cine HD · Doble toque ±10 s y Proporción Llenar 100% / 16:9",
            uri = "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8",
            isVideo = true,
            albumCategory = "Streams Demo HD",
            durationMs = 888_000L
        ),
        LocalMediaItem(
            title = "Red Bull TV HD (Deporte & Motor en Vivo)",
            subtitle = "Canal Internacional 1080p · Prueba de proporción 16:9 y Llenar Pantalla 100%",
            uri = "https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8",
            isVideo = true,
            albumCategory = "Streams Demo HD",
            durationMs = 0L
        ),
        LocalMediaItem(
            title = "DW Español HD (Documentales & Noticias)",
            subtitle = "Emisión HD · Reproducción continua a toda pantalla",
            uri = "https://dwamdstream104.akamaized.net/hls/live/2015530/dwstream104/index.m3u8",
            isVideo = true,
            albumCategory = "Streams Demo HD",
            durationMs = 0L
        ),
        LocalMediaItem(
            title = "RTVE Canal 24 Horas HD (Directo 2K/HD)",
            subtitle = "Emisión oficial RTVE para pantallas 2K automotrices (2560x1440)",
            uri = "https://rtvelivestream.rtve.es/rtvesec/24h/24h_main_dvr.m3u8",
            isVideo = true,
            albumCategory = "Streams Demo HD",
            durationMs = 0L
        ),
        LocalMediaItem(
            title = "Apple fMP4 Cinema Calibration (HD)",
            subtitle = "Calibración de vídeo y audio multicanal en alta definición",
            uri = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
            isVideo = true,
            albumCategory = "Streams Demo HD",
            durationMs = 1800_000L
        )
    )

    private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "m4v", "avi", "ts", "3gp")
    private val PHOTO_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp")

    fun scanLocalVideosAndPhotos(context: Context): List<LocalMediaItem> {
        val out = ArrayList<LocalMediaItem>()
        val seenUris = HashSet<String>()

        // 1. MediaStore Video
        runCatching {
            val proj = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.DURATION
            )
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                proj,
                null,
                null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                while (c.moveToNext() && out.size < 250) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol)?.substringBeforeLast('.') ?: "Vídeo $id"
                    val bucket = c.getString(bucketCol) ?: "Vídeos"
                    val dur = c.getLong(durCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString()
                    if (seenUris.add(uri)) {
                        out.add(
                            LocalMediaItem(
                                title = name,
                                subtitle = "$bucket · Vídeo Local",
                                uri = uri,
                                isVideo = true,
                                albumCategory = if (bucket.contains("usb", true)) "USB Pendrive" else "Películas / Vídeos",
                                durationMs = dur
                            )
                        )
                    }
                }
            }
        }

        // 2. Escaneo directo de Pendrive USB y carpetas del coche (/storage, /mnt/media_rw, Movies, Download, DCIM)
        val roots = LinkedHashSet<File>()
        runCatching {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (sm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                for (vol in sm.storageVolumes) {
                    val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        vol.directory
                    } else {
                        runCatching {
                            val m = vol.javaClass.getMethod("getPathFile")
                            m.invoke(vol) as? File
                        }.getOrNull()
                    }
                    if (dir != null && dir.exists()) roots.add(dir)
                }
            }
        }
        listOf("/storage", "/mnt/media_rw", "/mnt/usb", "/sdcard/Movies", "/sdcard/Download", "/sdcard/DCIM").forEach { p ->
            val f = File(p)
            if (f.exists()) roots.add(f)
        }

        fun walkMediaDir(dir: File, depth: Int) {
            if (depth > 3 || out.size >= 300) return
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (child in children) {
                if (out.size >= 300) break
                if (child.isDirectory) {
                    val n = child.name.lowercase(Locale.ROOT)
                    if (!n.startsWith(".") && n != "android" && n != "lost.dir") {
                        walkMediaDir(child, depth + 1)
                    }
                } else if (child.isFile && child.length() > 8_192L) {
                    val ext = child.extension.lowercase(Locale.ROOT)
                    val isVid = ext in VIDEO_EXTS
                    val isPho = ext in PHOTO_EXTS
                    if (isVid || isPho) {
                        val u = Uri.fromFile(child).toString()
                        if (seenUris.add(u)) {
                            val folderName = child.parentFile?.name ?: "USB"
                            val srtFile = if (isVid) File(child.parentFile, child.nameWithoutExtension + ".srt") else null
                            out.add(
                                LocalMediaItem(
                                    title = child.nameWithoutExtension,
                                    subtitle = "$folderName · ${ext.uppercase(Locale.ROOT)} · ${String.format(Locale.ROOT, "%.1f MB", child.length() / (1024.0 * 1024.0))}",
                                    uri = u,
                                    isVideo = isVid,
                                    albumCategory = if (isVid) "USB Pendrive" else "Fotos",
                                    srtUri = if (srtFile != null && srtFile.isFile) Uri.fromFile(srtFile).toString() else null
                                )
                            )
                        }
                    }
                }
            }
        }
        for (r in roots) walkMediaDir(r, 0)

        out.addAll(BUILTIN_DEMO_VIDEOS)
        return out
    }

    // =========================================================================
    // HTTP HELPER (Soporta GZIP, UTF-8 BOM, Basic Auth y fallback Windows-1252 como M3uSource)
    // =========================================================================

    private fun httpGetText(urlStr: String, timeoutMs: Int, authHeader: String? = null): String {
        var currentUrl = urlStr
        val initialHost = runCatching { URL(urlStr).host }.getOrNull()
        for (redirect in 0..4) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "QonectaUnit/1.0 (Leapmotor C10; Android 11)")
                setRequestProperty("Accept-Encoding", "gzip")
                if (!authHeader.isNullOrBlank() && URL(currentUrl).host.equals(initialHost, ignoreCase = true)) {
                    setRequestProperty("Authorization", authHeader)
                }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                currentUrl = URL(URL(currentUrl), loc).toString()
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw IllegalStateException("HTTP $code")
            }
            val rawBytes = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var n: Int
                val maxBytes = 16 * 1024 * 1024
                while (input.read(buf).also { n = it } > 0) {
                    if (out.size() + n > maxBytes) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            conn.disconnect()
            return decodeBytes(rawBytes)
        }
        throw IllegalStateException("Demasiadas redirecciones")
    }

    private fun decodeBytes(bytes: ByteArray): String {
        val unzipped = if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B) {
            runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { gis ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var n: Int
                    while (gis.read(buf).also { n = it } > 0) {
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            }.getOrDefault(bytes)
        } else {
            bytes
        }
        if (unzipped.size >= 3 &&
            (unzipped[0].toInt() and 0xFF) == 0xEF &&
            (unzipped[1].toInt() and 0xFF) == 0xBB &&
            (unzipped[2].toInt() and 0xFF) == 0xBF
        ) {
            return String(unzipped, 3, unzipped.size - 3, StandardCharsets.UTF_8)
        }
        return try {
            String(unzipped, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            String(unzipped, Charset.forName("windows-1252"))
        }
    }
}
