package com.headqlink.unit.companion

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Actualizador desde GitHub Releases para Qonecta Unit Companion:
 * - Consulta los releases de GitHub (`https://api.github.com/repos/{owner}/{repo}/releases`).
 * - Descarga activos `.apk` (tanto `QonectaUnitCompanion_*.apk` para el móvil como
 *   `com.tencent.qqmusiccar_*.apk` para el coche).
 * - Instala en el móvil mediante `PackageInstaller` o sirve el APK del coche por HTTP local (`:8088`)
 *   para que el Centro Anti-OTA del Leapmotor C10 lo descargue por Hotspot sin cables ni pendrive.
 */
object GitHubReleaseUpdater {

    data class ReleaseAsset(
        val tagName: String,
        val releaseName: String,
        val publishedAt: String,
        val assetName: String,
        val downloadUrl: String,
        val sizeBytes: Long,
    ) {
        val sizeMb: Double get() = sizeBytes / (1024.0 * 1024.0)
        val isCarApk: Boolean
            get() = assetName.contains("qqmusiccar", ignoreCase = true) ||
                assetName.contains("unit", ignoreCase = true) && !assetName.contains("companion", ignoreCase = true)
        val isCompanionApk: Boolean
            get() = assetName.contains("companion", ignoreCase = true)
    }

    private val io = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile
    private var httpServerSocket: ServerSocket? = null

    @Volatile
    var hostedApkUrl: String? = null
        private set

    fun normalizeRepo(raw: String): String {
        return raw.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removeSuffix(".git")
            .trim('/')
            .ifBlank { "CharlysEV/qonecta-unit" }
    }

    fun fetchReleases(
        rawRepo: String,
        onResult: (Result<List<ReleaseAsset>>) -> Unit,
    ) {
        val repo = normalizeRepo(rawRepo)
        io.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("https://api.github.com/repos/$repo/releases?per_page=8")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 12_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "QonectaUnitCompanion/1.0")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("GitHub API devolvió HTTP $code para $repo")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(body)
                val out = ArrayList<ReleaseAsset>()
                for (i in 0 until arr.length()) {
                    val rel = arr.optJSONObject(i) ?: continue
                    val tag = rel.optString("tag_name", "release")
                    val title = rel.optString("name", tag).ifBlank { tag }
                    val date = rel.optString("published_at", "").take(10)
                    val assets = rel.optJSONArray("assets") ?: continue
                    for (j in 0 until assets.length()) {
                        val a = assets.optJSONObject(j) ?: continue
                        val name = a.optString("name", "")
                        val dl = a.optString("browser_download_url", "")
                        val size = a.optLong("size", 0L)
                        if (name.endsWith(".apk", ignoreCase = true) && dl.isNotBlank()) {
                            out.add(
                                ReleaseAsset(
                                    tagName = tag,
                                    releaseName = title,
                                    publishedAt = date,
                                    assetName = name,
                                    downloadUrl = dl,
                                    sizeBytes = size,
                                ),
                            )
                        }
                    }
                }
                ui.post { onResult(Result.success(out)) }
            } catch (e: Exception) {
                ui.post { onResult(Result.failure(e)) }
            } finally {
                conn?.disconnect()
            }
        }
    }

    fun downloadAsset(
        context: Context,
        asset: ReleaseAsset,
        onProgress: (String) -> Unit,
        onDone: (Result<File>) -> Unit,
    ) {
        io.execute {
            var conn: HttpURLConnection? = null
            try {
                val dir = File(context.cacheDir, "gh_releases").apply { mkdirs() }
                val safeName = asset.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val outFile = File(dir, safeName)
                conn = (URL(asset.downloadUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "QonectaUnitCompanion/1.0")
                }
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.sizeBytes
                var downloaded = 0L
                var lastTick = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(16 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            downloaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastTick > 200L) {
                                lastTick = now
                                val mb = downloaded / (1024.0 * 1024.0)
                                val msg = if (total > 0) {
                                    String.format(
                                        Locale.US,
                                        "Descargando %s: %.1f / %.1f MB (%d%%)",
                                        asset.assetName,
                                        mb,
                                        total / (1024.0 * 1024.0),
                                        ((downloaded * 100L) / total).toInt(),
                                    )
                                } else {
                                    String.format(Locale.US, "Descargando %s: %.1f MB", asset.assetName, mb)
                                }
                                ui.post { onProgress(msg) }
                            }
                        }
                    }
                }
                ui.post { onDone(Result.success(outFile)) }
            } catch (e: Exception) {
                ui.post { onDone(Result.failure(e)) }
            } finally {
                conn?.disconnect()
            }
        }
    }

    fun installApkOnPhone(context: Context, apkFile: File, onStatus: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            onStatus("Activa «Instalar aplicaciones desconocidas» y vuelve a pulsar Instalar.")
            val i = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }
            return
        }
        io.execute {
            try {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setSize(apkFile.length())
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    FileInputStream(apkFile).use { input ->
                        session.openWrite("package.apk", 0, apkFile.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    val intent = Intent(context, InstallResultReceiver::class.java)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                    session.commit(pi.intentSender)
                }
                ui.post { onStatus("✓ Instalador abierto para ${apkFile.name}") }
            } catch (e: Exception) {
                ui.post { onStatus("⚠️ Error al iniciar instalación: ${e.message}") }
            }
        }
    }

    /**
     * Sirve un archivo APK descargado en `http://<ip-local>:8088/unit.apk` para que el coche
     * pueda descargarlo desde el Centro Anti-OTA cuando esté conectado al Hotspot del móvil.
     */
    fun startHostingApkForCar(apkFile: File, onStatus: (String) -> Unit) {
        stopHostingApkForCar()
        io.execute {
            try {
                val server = ServerSocket(8088)
                httpServerSocket = server
                val ip = findLocalIpv4() ?: "192.168.43.1"
                val url = "http://$ip:8088/unit.apk"
                hostedApkUrl = url
                ui.post {
                    onStatus("📡 Sirviendo ${apkFile.name} para el coche en:\n$url\n(Escribe esta URL en el Centro Anti-OTA del coche)")
                }
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    io.execute {
                        client.use { sock ->
                            runCatching {
                                sock.soTimeout = 15_000
                                val reader = sock.getInputStream().bufferedReader()
                                reader.readLine() // GET /unit.apk HTTP/1.1
                                val out = sock.getOutputStream()
                                val header = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/vnd.android.package-archive\r\n" +
                                    "Content-Length: ${apkFile.length()}\r\n" +
                                    "Content-Disposition: attachment; filename=\"${apkFile.name}\"\r\n" +
                                    "Connection: close\r\n\r\n"
                                out.write(header.toByteArray(Charsets.UTF_8))
                                FileInputStream(apkFile).use { fis -> fis.copyTo(out) }
                                out.flush()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                ui.post { onStatus("⚠️ No se pudo abrir servidor local: ${e.message}") }
            }
        }
    }

    fun stopHostingApkForCar() {
        runCatching { httpServerSocket?.close() }
        httpServerSocket = null
        hostedApkUrl = null
    }

    private fun findLocalIpv4(): String? {
        return runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val candidates = ArrayList<String>()
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("172.") || ip.startsWith("10.")) {
                            candidates.add(ip)
                        }
                    }
                }
            }
            candidates.firstOrNull { it.startsWith("192.168.43.") || it.startsWith("192.168.") }
                ?: candidates.firstOrNull()
        }.getOrNull()
    }
}
