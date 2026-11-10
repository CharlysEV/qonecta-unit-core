package com.headqlink.unit.companion

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Actualizador de APKs desde GitHub Releases y **Servidor de Portal Cautivo + Proxy HTTP + APK**
 * para instalar `Qonecta Unit` en el Leapmotor C10 **sin usar Vivaldi** y sin que la app esté instalada.
 *
 * - Escucha simultáneamente en los puertos `8788` y `8080` (`0.0.0.0`).
 * - Si el coche se conecta al Hotspot Wi-Fi del móvil con Proxy Manual (`<IP-Hotspot>:8788` o `:8080`)
 *   o PAC (`http://<IP-Hotspot>:8788/proxy.pac`), intercepta el sondeo de conectividad de Android
 *   (`connectivitycheck.gstatic.com/generate_204`, `/gen_204`, etc.) y devuelve `302 Found` hacia
 *   `http://<IP-Hotspot>:8788/portal`.
 * - Esto hace que Android Automotive abra automáticamente la ventana del sistema
 *   `com.android.captiveportallogin` («Iniciar sesión en la red Wi-Fi») con nuestro instalador web,
 *   sin abrir Vivaldi.
 */
object GitHubReleaseUpdater {

    const val DEFAULT_UNIT_REPO = "CharlysEV/qonecta-unit"
    const val DEFAULT_COMPANION_REPO = "CharlysEV/qonecta-unit-companion"
    const val PORT_PRIMARY = 8788
    const val PORT_SECONDARY = 8080

    data class ReleaseAsset(
        val repoSlug: String,
        val projectLabel: String,
        val releaseName: String,
        val tagName: String,
        val publishedAt: String,
        val assetId: Long,
        val assetName: String,
        val downloadUrl: String,
        val apiAssetUrl: String,
        val sizeBytes: Long,
    ) {
        val sizeMb: Double
            get() = sizeBytes / (1024.0 * 1024.0)

        val isCompanionApk: Boolean
            get() = assetName.contains("companion", ignoreCase = true) ||
                repoSlug.contains("companion", ignoreCase = true)

        val isCarApk: Boolean
            get() = !isCompanionApk && (
                assetName.contains("coche", ignoreCase = true) ||
                    assetName.contains("unit", ignoreCase = true) ||
                    assetName.endsWith(".apk", ignoreCase = true)
                )
    }

    data class RepoStatus(
        val repoSlug: String,
        val projectLabel: String,
        val assets: List<ReleaseAsset>,
        val errorMessage: String? = null,
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var primaryServer: ServerSocket? = null

    @Volatile
    private var secondaryServer: ServerSocket? = null

    @Volatile
    var hostedApkUrl: String? = null
        private set

    @Volatile
    var hostedPortalUrl: String? = null
        private set

    @Volatile
    var hostedApkFile: File? = null
        private set

    @Volatile
    var lastEventLog: String = ""
        private set

    @Volatile
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    val isHosting: Boolean
        get() = primaryServer?.isClosed == false || secondaryServer?.isClosed == false

    fun normalizeRepo(input: String, fallback: String = DEFAULT_UNIT_REPO): String {
        val trimmed = input.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .trim('/')
        return if (trimmed.contains("/")) trimmed else fallback
    }

    /**
     * Consulta en paralelo los dos repositorios (`unitRepo` y `companionRepo`), usando el
     * `githubToken` opcional si los repositorios son privados.
     */
    fun fetchDualReleases(
        unitRepo: String,
        companionRepo: String = DEFAULT_COMPANION_REPO,
        githubToken: String? = null,
        onResult: (List<RepoStatus>, List<ReleaseAsset>) -> Unit,
    ) {
        val cleanUnit = normalizeRepo(unitRepo, DEFAULT_UNIT_REPO)
        val cleanComp = normalizeRepo(companionRepo, DEFAULT_COMPANION_REPO)
        val cleanToken = githubToken?.trim()?.takeIf { it.isNotEmpty() }

        thread(name = "gh-releases-dual-fetch", isDaemon = true) {
            val statuses = mutableListOf<RepoStatus>()
            val combinedAssets = mutableListOf<ReleaseAsset>()

            val unitStatus = fetchRepoReleasesInternal(
                repoSlug = cleanUnit,
                projectLabel = "🚗 Qonecta Unit (Coche)",
                githubToken = cleanToken,
            )
            statuses.add(unitStatus)
            combinedAssets.addAll(unitStatus.assets)

            if (!cleanComp.equals(cleanUnit, ignoreCase = true)) {
                val compStatus = fetchRepoReleasesInternal(
                    repoSlug = cleanComp,
                    projectLabel = "📱 Qonecta Companion (Móvil)",
                    githubToken = cleanToken,
                )
                statuses.add(compStatus)
                combinedAssets.addAll(compStatus.assets)
            }

            mainHandler.post { onResult(statuses, combinedAssets) }
        }
    }

    private fun fetchRepoReleasesInternal(
        repoSlug: String,
        projectLabel: String,
        githubToken: String?,
    ): RepoStatus {
        return runCatching {
            val apiUrl = URL("https://api.github.com/repos/$repoSlug/releases?per_page=8")
            val conn = (apiUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "QonectaUnitCompanion/1.0")
                if (!githubToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $githubToken")
                }
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                val msg = when (code) {
                    404 -> if (githubToken.isNullOrBlank()) {
                        "Repositorio privado o sin acceso público (HTTP 404). Añade tu Token PAT de GitHub arriba."
                    } else {
                        "No encontrado (HTTP 404). Verifica el nombre $repoSlug o que el Token tenga permiso 'repo' / 'Contents: Read'."
                    }
                    401, 403 -> "Acceso denegado (HTTP $code). Verifica que el Token PAT de GitHub sea válido."
                    else -> "GitHub devolvió HTTP $code"
                }
                return RepoStatus(repoSlug, projectLabel, emptyList(), msg)
            }
            val arr = JSONArray(body)
            val assets = mutableListOf<ReleaseAsset>()
            for (i in 0 until arr.length()) {
                val rel = arr.optJSONObject(i) ?: continue
                val tagName = rel.optString("tag_name", "release")
                val relName = rel.optString("name", tagName).ifBlank { tagName }
                val published = rel.optString("published_at", "").take(10)
                val relAssets = rel.optJSONArray("assets") ?: continue
                for (j in 0 until relAssets.length()) {
                    val a = relAssets.optJSONObject(j) ?: continue
                    val name = a.optString("name", "")
                    if (!name.lowercase(Locale.ROOT).endsWith(".apk")) continue
                    val dl = a.optString("browser_download_url", "")
                    val apiAssetUrl = a.optString("url", "")
                    if (dl.isBlank() && apiAssetUrl.isBlank()) continue
                    assets.add(
                        ReleaseAsset(
                            repoSlug = repoSlug,
                            projectLabel = projectLabel,
                            releaseName = relName,
                            tagName = tagName,
                            publishedAt = published,
                            assetId = a.optLong("id", 0L),
                            assetName = name,
                            downloadUrl = dl,
                            apiAssetUrl = apiAssetUrl,
                            sizeBytes = a.optLong("size", 0L),
                        ),
                    )
                }
            }
            if (assets.isEmpty()) {
                RepoStatus(repoSlug, projectLabel, emptyList(), "Sin archivos .apk publicados todavía en Releases.")
            } else {
                RepoStatus(repoSlug, projectLabel, assets, null)
            }
        }.getOrElse { e ->
            RepoStatus(repoSlug, projectLabel, emptyList(), e.message ?: "Error de red")
        }
    }

    fun downloadAsset(
        context: Context,
        asset: ReleaseAsset,
        githubToken: String? = null,
        onProgress: (String) -> Unit,
        onDone: (Result<File>) -> Unit,
    ) {
        val appContext = context.applicationContext
        val cleanToken = githubToken?.trim()?.takeIf { it.isNotEmpty() }
        thread(name = "gh-apk-download", isDaemon = true) {
            val result = runCatching {
                val dir = File(appContext.cacheDir, "gh_releases").apply { mkdirs() }
                val targetFile = File(dir, asset.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_"))
                val effectiveUrl = if (cleanToken != null && asset.apiAssetUrl.isNotBlank()) {
                    asset.apiAssetUrl
                } else {
                    asset.downloadUrl
                }
                val conn = (URL(effectiveUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "QonectaUnitCompanion/1.0")
                    if (cleanToken != null) {
                        setRequestProperty("Authorization", "Bearer $cleanToken")
                        setRequestProperty("Accept", "application/octet-stream")
                    }
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code al descargar ${asset.assetName}")
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.sizeBytes
                var downloaded = 0L
                var lastPct = -1
                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buf = ByteArray(32 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct && pct % 5 == 0) {
                                    lastPct = pct
                                    mainHandler.post {
                                        onProgress("Descargando ${asset.assetName}: $pct%")
                                    }
                                }
                            }
                        }
                        output.flush()
                    }
                }
                if (asset.isCarApk) {
                    runCatching {
                        appContext.getExternalFilesDir(null)?.let { extDir ->
                            extDir.mkdirs()
                            targetFile.copyTo(File(extDir, targetFile.name), overwrite = true)
                        }
                    }
                }
                targetFile
            }
            mainHandler.post { onDone(result) }
        }
    }

    /**
     * Instala el APK descargado en el propio teléfono usando `PackageInstaller.Session`.
     */
    fun installApkOnPhone(context: Context, apkFile: File, onStatus: (String) -> Unit) {
        thread(name = "gh-apk-install", isDaemon = true) {
            runCatching {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setSize(apkFile.length())
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("apk", 0, apkFile.length()).use { out ->
                        FileInputStream(apkFile).use { input ->
                            input.copyTo(out, 32 * 1024)
                        }
                        session.fsync(out)
                    }
                    val intent = Intent(context, InstallResultReceiver::class.java)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                    session.commit(pending.intentSender)
                }
                mainHandler.post {
                    onStatus("Instalador de Android abierto para ${apkFile.name}")
                }
            }.onFailure { e ->
                mainHandler.post {
                    onStatus("Error al iniciar instalación: ${e.message}")
                }
            }
        }
    }

    /**
     * Levanta el **Servidor Dual de Portal Cautivo + Proxy HTTP/PAC + Instalador APK** en los
     * puertos `8788` y `8080` del móvil para que el coche (conectado al Hotspot del móvil)
     * abra automáticamente `com.android.captiveportallogin` («Iniciar sesión en la red Wi-Fi»)
     * e instale `Qonecta Unit` **sin abrir Vivaldi**.
     */
    fun startHostingApkForCar(context: Context? = null, apkFile: File, onStatus: (String) -> Unit) {
        stopHostingApk()
        hostedApkFile = apkFile

        if (context != null) {
            runCatching {
                val appCtx = context.applicationContext
                val wm = appCtx.getSystemService(WifiManager::class.java)
                wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "QonectaCompanion:ApkServer")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                val pm = appCtx.getSystemService(PowerManager::class.java)
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QonectaCompanion:ApkWake")?.apply {
                    setReferenceCounted(false)
                    acquire(30 * 60 * 1000L) // hasta 30 min
                }
            }
        }

        val ip = findBestHotspotOrLocalIp() ?: "192.168.43.1"
        val portalUrl = "http://$ip:$PORT_PRIMARY/portal"
        val apkUrl = "http://$ip:$PORT_PRIMARY/${apkFile.name}"
        hostedPortalUrl = portalUrl
        hostedApkUrl = apkUrl

        fun postStatus(msg: String) {
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            lastEventLog = "[$stamp] $msg"
            android.util.Log.i("CaptivePortal", lastEventLog)
            mainHandler.post { onStatus(lastEventLog) }
        }

        // Servidor principal en 8788
        thread(name = "captive-portal-8788", isDaemon = true) {
            try {
                val server = ServerSocket(PORT_PRIMARY)
                primaryServer = server
                val curIp = findBestHotspotOrLocalIp() ?: ip
                hostedPortalUrl = "http://$curIp:$PORT_PRIMARY/portal"
                hostedApkUrl = "http://$curIp:$PORT_PRIMARY/${apkFile.name}"
                postStatus(
                    "🟢 Servidor APK + Portal ACTIVO (${apkFile.name} · WifiLock ON)\n" +
                        "• IP Hotspot: $curIp  |  Puertos: $PORT_PRIMARY y $PORT_SECONDARY\n" +
                        "• Portal: http://$curIp:$PORT_PRIMARY/portal",
                )
                while (!server.isClosed) {
                    val client = server.accept()
                    thread(name = "captive-client-8788", isDaemon = true) {
                        handleCaptiveOrApkClient(client, apkFile, PORT_PRIMARY, ::postStatus)
                    }
                }
            } catch (e: Exception) {
                if (primaryServer?.isClosed != true) {
                    postStatus("⚠️ Servidor puerto $PORT_PRIMARY detenido: ${e.message}")
                }
            }
        }

        // Servidor secundario en 8080
        thread(name = "captive-portal-8080", isDaemon = true) {
            runCatching {
                val server = ServerSocket(PORT_SECONDARY)
                secondaryServer = server
                while (!server.isClosed) {
                    val client = server.accept()
                    thread(name = "captive-client-8080", isDaemon = true) {
                        handleCaptiveOrApkClient(client, apkFile, PORT_SECONDARY, ::postStatus)
                    }
                }
            }
        }
    }

    fun stopHostingApk() {
        runCatching { primaryServer?.close() }
        runCatching { secondaryServer?.close() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wifiLock = null
        wakeLock = null
        primaryServer = null
        secondaryServer = null
        hostedApkUrl = null
        hostedPortalUrl = null
    }

    private fun handleCaptiveOrApkClient(
        socket: Socket,
        apkFile: File,
        listenPort: Int,
        onEvent: (String) -> Unit,
    ) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 45_000
                val clientIp = s.inetAddress?.hostAddress ?: "coche"
                val localSocketIp = s.localAddress?.hostAddress
                    ?.takeIf { it != "0.0.0.0" && it != "127.0.0.1" && !it.contains(":") }
                    ?: findBestHotspotOrLocalIp()
                    ?: "192.168.43.1"

                val reader = s.getInputStream().bufferedReader(Charsets.UTF_8)
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0)?.uppercase(Locale.ROOT) ?: "GET"
                val rawTarget = parts.getOrNull(1) ?: "/"

                var hostHeader = ""
                var userAgent = ""
                var rangeHeader = ""
                while (true) {
                    val h = reader.readLine()
                    if (h.isNullOrEmpty()) break
                    when {
                        h.startsWith("Host:", ignoreCase = true) ->
                            hostHeader = h.substringAfter(":").trim()
                        h.startsWith("User-Agent:", ignoreCase = true) ->
                            userAgent = h.substringAfter(":").trim()
                        h.startsWith("Range:", ignoreCase = true) ->
                            rangeHeader = h.substringAfter(":").trim()
                    }
                }

                val uaLower = userAgent.lowercase(Locale.ROOT)
                val isVivaldi = uaLower.contains("vivaldi")
                val isDownloadMgr = uaLower.contains("downloadmanager") || uaLower.contains("dalvik")
                val clientAppLabel = when {
                    isVivaldi -> "Vivaldi"
                    isDownloadMgr -> "DownloadManager Sistema"
                    uaLower.contains("wv") || uaLower.contains("android") -> "CaptivePortalLogin"
                    else -> "Navegador Coche"
                }

                val out: OutputStream = s.getOutputStream()
                android.util.Log.i("CaptivePortal", "REQ from=$clientIp ($clientAppLabel) local=$localSocketIp:$listenPort line='$requestLine' host='$hostHeader' range='$rangeHeader'")

                if (method == "CONNECT") {
                    val resp = "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                    out.write(resp.toByteArray(Charsets.UTF_8))
                    out.flush()
                    return
                }

                val targetLower = rawTarget.lowercase(Locale.ROOT)
                val hostLower = hostHeader.lowercase(Locale.ROOT)

                val isProxyPac = targetLower.endsWith("/proxy.pac") || targetLower.contains("wpad.dat")
                val isApkDownload = targetLower.contains("/download/") ||
                    targetLower.contains("/octet/") ||
                    targetLower.contains("/pdfapk/") ||
                    targetLower.contains("/mp3apk/") ||
                    targetLower.contains("/zipapk/") ||
                    targetLower.endsWith(".apk") ||
                    targetLower.contains(".apk?")

                val isCaptiveProbe = !isApkDownload && !isProxyPac && (
                    targetLower.contains("generate_204") ||
                        targetLower.contains("gen_204") ||
                        targetLower.contains("ncsi.txt") ||
                        targetLower.contains("connecttest.txt") ||
                        targetLower.contains("hotspot-detect") ||
                        targetLower.contains("success.txt") ||
                        hostLower.contains("gstatic.com") ||
                        hostLower.contains("google.com") ||
                        hostLower.contains("google.cn") ||
                        hostLower.contains("googleapis.com") ||
                        hostLower.contains("captive.apple.com") ||
                        hostLower.contains("msftconnecttest.com") ||
                        (rawTarget.startsWith("http://") && !rawTarget.contains(localSocketIp))
                    )

                when {
                    isProxyPac -> {
                        onEvent("📄 Coche ($clientIp · $clientAppLabel) descargó PAC (/proxy.pac)")
                        val pacBody = """
                            function FindProxyForURL(url, host) {
                                return "PROXY $localSocketIp:$listenPort; DIRECT";
                            }
                        """.trimIndent()
                        val bodyBytes = pacBody.toByteArray(Charsets.UTF_8)
                        val header = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: application/x-ns-proxy-autoconfig\r\n")
                            append("Cache-Control: no-store\r\n")
                            append("Content-Length: ${bodyBytes.size}\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        out.write(header.toByteArray(Charsets.UTF_8))
                        out.write(bodyBytes)
                        out.flush()
                    }

                    isCaptiveProbe -> {
                        val redirectUrl = "http://$localSocketIp:$listenPort/portal"
                        onEvent("🔔 Comprobación de red ($clientIp · $clientAppLabel) → Redirect 302 a $redirectUrl")
                        val header = buildString {
                            append("HTTP/1.1 302 Found\r\n")
                            append("Location: $redirectUrl\r\n")
                            append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                            append("Pragma: no-cache\r\n")
                            append("Expires: 0\r\n")
                            append("Content-Length: 0\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        out.write(header.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }

                    isApkDownload -> {
                        val totalBytes = apkFile.length()
                        val mb = String.format(Locale.US, "%.1f MB", totalBytes / (1024.0 * 1024.0))
                        val outName = "com.tencent.qqmusiccar.apk"

                        // Soporte de HTTP Range (206 Partial Content) para DownloadManager de Android
                        var startByte = 0L
                        var endByte = totalBytes - 1L
                        var isPartial = false
                        if (rangeHeader.startsWith("bytes=", ignoreCase = true) && totalBytes > 0) {
                            val spec = rangeHeader.substringAfter("=").substringBefore(",").trim()
                            val rParts = spec.split("-")
                            val reqStart = rParts.getOrNull(0)?.toLongOrNull()
                            val reqEnd = rParts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLongOrNull()
                            if (reqStart != null && reqStart in 0 until totalBytes) {
                                startByte = reqStart
                                endByte = (reqEnd ?: (totalBytes - 1L)).coerceIn(startByte, totalBytes - 1L)
                                isPartial = true
                            }
                        }
                        val contentLen = (endByte - startByte + 1L).coerceAtLeast(0L)

                        onEvent("📥 Coche ($clientIp · $clientAppLabel) descargando APK ($mb)${if (isPartial) " [Range $startByte-$endByte]" else ""}…")

                        val header = buildString {
                            if (isPartial) {
                                append("HTTP/1.1 206 Partial Content\r\n")
                                append("Content-Range: bytes $startByte-$endByte/$totalBytes\r\n")
                            } else {
                                append("HTTP/1.1 200 OK\r\n")
                            }
                            append("Content-Type: application/vnd.android.package-archive\r\n")
                            append("Content-Disposition: attachment; filename=\"$outName\"\r\n")
                            append("Access-Control-Allow-Origin: *\r\n")
                            append("Accept-Ranges: bytes\r\n")
                            append("ETag: \"qu-${apkFile.lastModified()}-$totalBytes\"\r\n")
                            append("Cache-Control: no-store\r\n")
                            append("Content-Length: $contentLen\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        out.write(header.toByteArray(Charsets.UTF_8))
                        if (method != "HEAD" && contentLen > 0) {
                            var sent = 0L
                            var lastPct = -1
                            RandomAccessFile(apkFile, "r").use { raf ->
                                raf.seek(startByte)
                                val buf = ByteArray(64 * 1024)
                                var remaining = contentLen
                                while (remaining > 0) {
                                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                                    val r = raf.read(buf, 0, toRead)
                                    if (r <= 0) break
                                    out.write(buf, 0, r)
                                    sent += r
                                    remaining -= r
                                    if (totalBytes > 0) {
                                        val pct = (((startByte + sent) * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                        if (pct != lastPct && (pct % 20 == 0 || pct == 100)) {
                                            lastPct = pct
                                            onEvent("📥 Enviando ${apkFile.name} a $clientAppLabel ($clientIp): $pct%")
                                        }
                                    }
                                }
                            }
                        }
                        out.flush()
                        onEvent("✅ ${apkFile.name} enviado al 100% a $clientAppLabel ($clientIp).")
                    }

                    else -> {
                        onEvent("🌐 Coche ($clientIp) abrió el Portal desde $clientAppLabel")
                        val htmlBytes = buildCaptivePortalHtml(localSocketIp, listenPort, apkFile, isVivaldi, clientAppLabel).toByteArray(Charsets.UTF_8)
                        val header = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: text/html; charset=utf-8\r\n")
                            append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                            append("Content-Length: ${htmlBytes.size}\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        out.write(header.toByteArray(Charsets.UTF_8))
                        if (method != "HEAD") {
                            out.write(htmlBytes)
                        }
                        out.flush()
                    }
                }
            }
        }
    }

    private fun buildCaptivePortalHtml(
        serverIp: String,
        serverPort: Int,
        apkFile: File,
        isVivaldi: Boolean,
        clientAppLabel: String,
    ): String {
        val sizeMb = String.format(Locale.US, "%.1f MB", apkFile.length() / (1024.0 * 1024.0))
        val dlUrl = "http://$serverIp:$serverPort/download/com.tencent.qqmusiccar.apk"
        val altUrl = "http://$serverIp:$serverPort/${apkFile.name}"

        val bannerHtml = if (isVivaldi) {
            """
            <div class="banner banner-warn">
                ⚠️ <b>Abierto desde Vivaldi Automotive:</b> Vivaldi bloquea las descargas directas de APK.<br>
                👉 <b>Usa el Botón 2 (Amarillo)</b> con el APK en tu Pendrive USB y pulsa el icono pequeño <b>&#x2922; (vista previa)</b> en la esquina del archivo dentro de «Archivos».
            </div>
            """.trimIndent()
        } else {
            """
            <div class="banner banner-ok">
                🟢 <b>Abierto desde $clientAppLabel:</b> Descarga e instalación directa habilitada.<br>
                👉 Pulsa el <b>Botón 1 (Verde)</b> para descargar e instalar Qonecta Unit, o el <b>Botón 2 (Amarillo)</b> si tienes el APK en un Pendrive USB.
            </div>
            """.trimIndent()
        }

        return """
            <!DOCTYPE html>
            <html lang="es">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Instalador Qonecta Unit · C10</title>
                <style>
                    body {
                        margin: 0;
                        padding: 18px;
                        background: #060D17;
                        color: #EAF2F7;
                        font-family: system-ui, sans-serif;
                    }
                    .card {
                        max-width: 820px;
                        margin: 0 auto;
                        background: #0F1E2E;
                        border: 2px solid #00E676;
                        border-radius: 20px;
                        padding: 22px;
                        box-shadow: 0 10px 28px rgba(0,0,0,0.45);
                    }
                    h1 {
                        margin: 0 0 6px 0;
                        font-size: 26px;
                        color: #00E676;
                        text-align: center;
                    }
                    .sub {
                        color: #A7C4BC;
                        font-size: 15px;
                        margin-bottom: 14px;
                        text-align: center;
                    }
                    .banner {
                        border-radius: 14px;
                        padding: 14px 16px;
                        font-size: 15px;
                        line-height: 1.45;
                        margin-bottom: 16px;
                    }
                    .banner-ok {
                        background: #062919;
                        border: 1px solid #00E676;
                        color: #CFFAE2;
                    }
                    .banner-warn {
                        background: #2A1F04;
                        border: 1px solid #FFD54F;
                        color: #FFF3CD;
                    }
                    .btn {
                        display: block;
                        width: 100%;
                        box-sizing: border-box;
                        text-align: center;
                        text-decoration: none;
                        font-size: 20px;
                        font-weight: 800;
                        padding: 19px 14px;
                        margin: 12px 0;
                        border-radius: 14px;
                        border: none;
                        cursor: pointer;
                    }
                    .btn-green {
                        background: #00E676;
                        color: #03170B;
                    }
                    .btn-usb {
                        background: #FFD54F;
                        color: #1F1600;
                    }
                    .btn-alt {
                        background: #152C40;
                        color: #80CBC4;
                        border: 2px solid #00BFA5;
                        font-size: 17px;
                        padding: 15px 12px;
                    }
                    .guide {
                        background: #08131F;
                        border: 1px solid #1E3A52;
                        border-radius: 14px;
                        padding: 15px 18px;
                        margin-top: 16px;
                        font-size: 15px;
                        color: #B0BEC5;
                        line-height: 1.5;
                    }
                    .guide b { color: #00E676; }
                    .hl { color: #FFD54F; font-weight: bold; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>🚗 Instalador Qonecta Unit · $sizeMb</h1>
                    <div class="sub">
                        Conectado a <b>$serverIp:$serverPort</b> · Paquete: <b>${apkFile.name}</b>
                    </div>

                    $bannerHtml

                    <a class="btn btn-green" href="$dlUrl" download="com.tencent.qqmusiccar.apk">
                        📲 1. DESCARGAR E INSTALAR QONECTA UNIT (.APK)
                    </a>

                    <label class="btn btn-usb" for="usbFilePicker">
                        📁 2. ABRIR «ARCHIVOS DEL COCHE» (INSTALAR DESDE USB)
                    </label>
                    <input id="usbFilePicker" type="file" accept="*/*" style="display:none;">

                    <a class="btn btn-alt" href="$altUrl">
                        📦 3. Enlace Directo Alternativo (${apkFile.name})
                    </a>

                    <div class="guide">
                        <b>💡 Cómo instalar desde Pendrive USB con el Botón 2 (Amarillo):</b><br>
                        1️⃣ Guarda <code>${apkFile.name}</code> en tu pendrive USB (puedes pasarlo desde tu móvil con el botón «Guardar APK en USB» de Qonecta Unit Companion) y conéctalo al coche.<br>
                        2️⃣ Pulsa el botón amarillo <b>«📁 2. ABRIR ARCHIVOS DEL COCHE»</b>.<br>
                        3️⃣ En la ventana de Archivos del coche, entra en tu <b>Unidad USB</b> a la izquierda.<br>
                        4️⃣ <span class="hl">TRUCO CLAVE: NO toques el centro del archivo.</span> Toca el <b>icono pequeño de vista previa (dos flechas en diagonal &#x2922; en la esquina del icono del APK)</b> para que «Archivos» abra directamente el Instalador del Sistema.
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * Busca la IP del Hotspot Wi-Fi del móvil (priorizando interfaces de punto de acceso como
     * `swlan0`, `ap0`, `wlan1`, `softap0`, `rndis0`) y si no, devuelve la IPv4 local de `wlan0`.
     */
    fun findBestHotspotOrLocalIp(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val candidates = mutableListOf<Pair<String, String>>()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase(Locale.ROOT)
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("p2p") || name.startsWith("tun") || name.startsWith("dummy")) {
                    continue
                }
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("172.") || ip.startsWith("10.")) {
                            candidates.add(name to ip)
                        }
                    }
                }
            }
            // Prioridad 1: Interfaz explícita de Hotspot en Samsung / Android (swlan0, ap0, softap, wlan1)
            candidates.firstOrNull { (name, _) ->
                name.contains("swlan") || name.contains("ap") || name == "wlan1" || name.contains("rndis")
            }?.second
                // Prioridad 2: subred clásica de Hotspot Android (192.168.43.x o 192.168.187.x)
                ?: candidates.firstOrNull { (_, ip) ->
                    ip.startsWith("192.168.43.") || ip.startsWith("192.168.187.")
                }?.second
                // Prioridad 3: cualquier wlan
                ?: candidates.firstOrNull { (name, _) -> name.startsWith("wlan") }?.second
                ?: candidates.firstOrNull()?.second
        }.getOrNull()
    }

    /**
     * Devuelve todas las interfaces Wi-Fi / Hotspot activas para mostrarlas claramente al usuario.
     */
    fun describeActiveNetworkIps(): String {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val list = mutableListOf<String>()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase(Locale.ROOT)
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") || name.startsWith("dummy")) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("172.") || ip.startsWith("10.")) {
                            val tag = when {
                                name.contains("swlan") || name.contains("ap") || name == "wlan1" -> "Hotspot Wi-Fi"
                                name.startsWith("wlan") -> "Wi-Fi"
                                name.contains("rndis") || name.contains("usb") -> "USB Tethering"
                                name.contains("bt") || name.contains("pan") -> "Bluetooth PAN"
                                else -> name
                            }
                            list.add("$ip ($tag · $name)")
                        }
                    }
                }
            }
            if (list.isEmpty()) "Sin IP local detectada (enciende el Punto de acceso / Hotspot)" else list.joinToString("  |  ")
        }.getOrDefault("192.168.43.1")
    }

    /**
     * Devuelve las URLs HTTP locales en todas las interfaces activas del móvil (Hotspot, Wi-Fi Direct P2P, Wi-Fi)
     * para que `Qonecta Unit` en el coche pueda hacer HTTP Pull rápido del APK si tiene ruta IP hacia el móvil.
     */
    fun listAllLocalHttpUrlsForHostedApk(): List<String> {
        return runCatching {
            val urls = LinkedHashSet<String>()
            findBestHotspotOrLocalIp()?.let { best ->
                urls.add("http://$best:$PORT_PRIMARY/download/com.tencent.qqmusiccar.apk")
            }
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase(Locale.ROOT)
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") || name.startsWith("dummy")) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("172.") || ip.startsWith("10.")) {
                            urls.add("http://$ip:$PORT_PRIMARY/download/com.tencent.qqmusiccar.apk")
                        }
                    }
                }
            }
            urls.toList()
        }.getOrDefault(emptyList())
    }

    /**
     * Extrae `(versionName, versionCode)` reales del APK usando `PackageManager`.
     */
    @Suppress("DEPRECATION")
    fun extractApkVersionMeta(context: Context, apkFile: File): Pair<String, Int> {
        return runCatching {
            val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            val vName = info?.versionName?.takeIf { it.isNotBlank() }
                ?: Regex("(\\d+\\.\\d+\\.\\d+[^.]*)").find(apkFile.name)?.groupValues?.getOrNull(1)
                ?: apkFile.nameWithoutExtension
            val vCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                (info?.longVersionCode ?: 0L).toInt()
            } else {
                info?.versionCode ?: 0
            }
            vName to vCode
        }.getOrDefault(apkFile.nameWithoutExtension to 0)
    }

    fun sha256Hex(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            var r: Int
            while (input.read(buf).also { r = it } > 0) {
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Busca el APK local más reciente de `Qonecta Unit` en carpetas accesibles sin restricciones
     * de Scoped Storage en Android 14/15 (`getExternalFilesDir(null)`, `cacheDir/gh_releases`, `filesDir`)
     * y también en `/sdcard/Download`.
     */
    fun findBestLocalCarApk(context: Context, fallbackVer: String = "0.1.17"): File? {
        val searchDirs = listOfNotNull(
            context.getExternalFilesDir(null),
            File(context.filesDir, "apk_updates"),
            File(context.cacheDir, "gh_releases"),
            context.filesDir,
            File("/sdcard/Download"),
        )
        val candidates = mutableListOf<File>()
        for (dir in searchDirs) {
            val files = runCatching {
                dir.listFiles { f ->
                    f.isFile && f.canRead() && f.length() > 100_000L &&
                        (f.name.startsWith("QonectaUnit_", ignoreCase = true) || f.name.startsWith("com.tencent.qqmusiccar", ignoreCase = true)) &&
                        f.name.endsWith(".apk", ignoreCase = true)
                }?.toList().orEmpty()
            }.getOrDefault(emptyList())
            candidates.addAll(files)
        }
        if (candidates.isNotEmpty()) {
            // Comparar por versión numérica real (ej. 0.1.15 -> 15 > 0.1.6 -> 6) y luego por fecha de modificación
            return candidates.sortedWith(
                compareByDescending<File> { extractVersionScore(it.name) }
                    .thenByDescending { it.lastModified() },
            ).firstOrNull()
        }
        val directExt = context.getExternalFilesDir(null)?.let { File(it, "QonectaUnit_${fallbackVer}-unit.apk") }
        if (directExt != null && directExt.isFile && directExt.canRead()) return directExt
        val directDl = File("/sdcard/Download/QonectaUnit_${fallbackVer}-unit.apk")
        if (directDl.isFile && directDl.canRead()) return directDl
        return null
    }

    private fun extractVersionScore(fileName: String): Long {
        val m = Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(fileName) ?: return 0L
        val maj = m.groupValues[1].toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLongOrNull() ?: 0L
        val pat = m.groupValues[3].toLongOrNull() ?: 0L
        return maj * 1_000_000L + min * 1_000L + pat
    }
}
