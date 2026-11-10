package com.headqlink.unit.probe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.graphics.Point
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.storage.StorageManager
import com.headqlink.unit.extended.LeapVehicleDataSource
import com.headqlink.unit.files.CarFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Sonda del coche, solo lectura: qué servicios de Leapmotor hay en la unidad, qué permiso piden, si una app puede
 * conectarse a ellos y qué telemetría en tiempo real devuelven (`IAutoSdkService` transacciones 17..45 y
 * `IBinderPool` sub-binders 0..12).
 */
object CarProbe {

    /** Un servicio al que intentar conectar: paquete y acción. */
    data class Target(val pkg: String, val action: String)

    val TARGETS = listOf(
        Target("com.leapmotor.carcontrol", "com.leapmotor.carcontrol.services.BINDER_POOL"),
        Target("com.leapmotor.carcontrol", "com.leapmotor.carcontrol.services.LP_BINDER_POOL"),
        Target("com.leapmotor.carcontrol", "com.leapmotor.carcontrol.CAR_CONTROL_SERVICE"),
        Target("com.leapmotor.autosdk.server", "com.leapmotor.autosdk.server.action.AUTOSDK_SERVICE"),
    )

    /** Permisos que pide el kit de Leapmotor (los pedimos en el manifiesto para ver si Android nos los da). */
    val PERMISSIONS = listOf("com.leapmotor.autosdk.server.USE_SERVICE")

    /** Paquetes del coche que interesan en la lista. */
    private val CAR_PREFIXES = listOf("com.leapmotor", "com.neusoft", "com.android.car")

    const val BIND_TIMEOUT_MS = 4000L

    suspend fun run(context: Context): String {
        val pm = context.packageManager
        val out = StringBuilder()
        out.appendLine("Qonecta Unit · sonda del coche (solo lectura)")
        out.appendLine("Fecha: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        out.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.DISPLAY}")
        out.appendLine("android.car: " + if (runCatching { Class.forName("android.car.Car") }.isSuccess) "sí" else "no")

        out.appendLine()
        out.appendLine("== Paquetes del coche")
        for (p in carPackages(pm)) out.appendLine("  $p")

        for (pkg in TARGETS.map { it.pkg }.distinct()) {
            out.appendLine()
            out.appendLine("== $pkg")
            out.append(describePackage(pm, pkg))
        }

        out.appendLine()
        out.appendLine("== Permisos que pedimos")
        for (name in PERMISSIONS) {
            val info = runCatching { pm.getPermissionInfo(name, 0) }.getOrNull()
            val granted = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
            out.appendLine("  $name: " + (info?.let { "existe, ${protectionName(protectionOf(it))}" } ?: "no existe")
                    + " · concedido a Qonecta Unit: " + if (granted) "sí" else "no")
        }

        out.appendLine()
        out.appendLine("== Conectar y leer telemetría en tiempo real")
        for (t in TARGETS) out.appendLine("  ${t.action}\n    " + bind(context, t))

        out.appendLine()
        out.appendLine("== Pantallas (el cuadro de instrumentos, si Android deja pintar en él)")
        out.append(describeDisplays(context))

        out.appendLine()
        out.appendLine("== Cámaras del vehículo (Camera2 HAL y paquetes AVM 360° / DVR / DMS)")
        out.append(describeCameras(context, pm))

        out.appendLine()
        out.appendLine("== Navegadores y tiendas (de dónde vino Vivaldi)")
        out.append(describeBrowsers(pm))

        out.appendLine()
        out.appendLine("== Abrir archivos sin Vivaldi")
        out.append(describeFiles(context))

        out.appendLine()
        out.appendLine("== Apps del lanzador")
        for (a in launcherApps(pm)) out.appendLine("  $a")
        return out.toString()
    }

    private fun describeDisplays(context: Context): String {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return "  sin DisplayManager\n"
        val presentation = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { it.displayId }.toSet()
        val sb = StringBuilder()
        for (d in dm.displays) {
            val size = Point().also { @Suppress("DEPRECATION") d.getRealSize(it) }
            sb.appendLine("  ${d.displayId}: «${d.name}» ${size.x}×${size.y} ${d.refreshRate.roundToInt()} Hz" +
                    " · flags=0x${Integer.toHexString(d.flags)} · estado=${d.state}" +
                    if (d.displayId in presentation) " · PRESENTACIÓN (una app puede pintar ahí)" else "")
        }
        return sb.toString()
    }

    @Suppress("DEPRECATION")
    fun describeCameras(context: Context, pm: PackageManager = context.packageManager): String {
        val sb = StringBuilder()
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm == null) {
            sb.appendLine("  sin CameraManager")
        } else {
            val ids = runCatching { cm.cameraIdList }.getOrNull() ?: emptyArray()
            sb.appendLine("  CameraManager.cameraIdList (${ids.size}): ${ids.joinToString().ifEmpty { "ninguna expuesta a Camera2 estándar" }}")
            for (id in ids) {
                runCatching {
                    val ch = cm.getCameraCharacteristics(id)
                    val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT (DMS/Interior)"
                        CameraCharacteristics.LENS_FACING_BACK -> "BACK (Exterior/DVR)"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL (AVM/USB)"
                        else -> "UNKNOWN"
                    }
                    val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val sizes = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                        ?.take(4)?.joinToString { "${it.width}x${it.height}" } ?: "?"
                    sb.appendLine("    cam[$id]: facing=$facing · YUV=$sizes")
                }.onFailure {
                    sb.appendLine("    cam[$id]: error leyendo características (${it.message})")
                }
            }
        }
        runCatching {
            val devDir = java.io.File("/dev")
            val videoNodes = devDir.listFiles { _, name ->
                name.startsWith("video") || name.startsWith("media") || name.startsWith("v4l")
            }?.sortedBy { it.name }.orEmpty()
            sb.appendLine("  Nodos V4L2 en /dev (${videoNodes.size}): " + videoNodes.joinToString { "${it.name}(r=${it.canRead()},w=${it.canWrite()})" })
        }
        val camPkgs = listOf(
            "com.leapmotor.cameraaround",
            "com.leapmotor.driverecord",
            "com.leapmotor.facevideo",
            "org.codeaurora.snapcam",
            "com.android.camera2",
        )
        for (pkg in camPkgs) {
            val info = runCatching {
                pm.getPackageInfo(
                    pkg,
                    PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS,
                )
            }.getOrNull()
            if (info == null) {
                sb.appendLine("  paquete $pkg: no instalado")
                continue
            }
            sb.appendLine("  paquete $pkg (${info.versionName}):")
            info.activities?.forEach {
                sb.appendLine("    activity ${it.name} · exported=${it.exported} · perm=${it.permission ?: "ninguno"}")
            }
            info.services?.forEach {
                sb.appendLine("    service ${it.name} · exported=${it.exported} · perm=${it.permission ?: "ninguno"}")
            }
            info.providers?.forEach {
                sb.appendLine("    provider ${it.authority} (${it.name}) · exported=${it.exported}")
            }
        }
        return sb.toString()
    }

    private fun describeBrowsers(pm: PackageManager): String {
        val sb = StringBuilder()
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val market = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MARKET)
        val vivaldi = installed(pm).filter { it.contains("vivaldi") }
        sb.appendLine("  navegadores:")
        for (p in (vivaldi + handlers(pm, web)).distinct()) sb.appendLine("    " + origin(pm, p))
        sb.appendLine("  tiendas:")
        for (p in handlers(pm, market)) sb.appendLine("    " + origin(pm, p))
        return sb.toString()
    }

    private fun describeFiles(context: Context): String {
        val pm = context.packageManager
        val sb = StringBuilder()
        if (Build.VERSION.SDK_INT >= 24) {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            sm?.storageVolumes?.forEach { v ->
                sb.appendLine("  almacenamiento «${v.getDescription(context)}» · extraíble=${v.isRemovable} · principal=${v.isPrimary} · ${v.state} · id=${v.uuid ?: "primary"}")
            }
        }
        val apk = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://x/a.apk"), "application/vnd.android.package-archive")
        val ways = listOf(
            "explorador (raíz del pen o interna)" to CarFiles.browse("primary"),
            "app Archivos" to Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_FILES"),
            "selector (abrir documento)" to CarFiles.picker(),
            "selector (coger contenido)" to Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
            "instalador de APK" to apk,
        )
        for ((name, intent) in ways) sb.appendLine("  $name: " + handlers(pm, intent, withActivity = true).ifEmpty { listOf("nadie") }.joinToString())
        sb.appendLine("  orden del botón «Archivos del coche»: " + CarFiles.rootOrder(CarFiles.volumes(context)).joinToString().ifEmpty { "sin raíces" } + ", app Archivos, selector")
        return sb.toString()
    }

    @Suppress("DEPRECATION")
    private fun installed(pm: PackageManager): List<String> =
        runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList()).map { it.packageName }

    /** Quién atiende un intent: los paquetes (o paquete/actividad). */
    private fun handlers(pm: PackageManager, intent: Intent, withActivity: Boolean = false): List<String> =
        runCatching { pm.queryIntentActivities(intent, if (Build.VERSION.SDK_INT >= 23) PackageManager.MATCH_ALL else 0) }
            .getOrDefault(emptyList())
            .map { if (withActivity) "${it.activityInfo.packageName}/${it.activityInfo.name.substringAfterLast('.')}" else it.activityInfo.packageName }
            .distinct()

    private fun launcherApps(pm: PackageManager): List<String> =
        runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0) }
            .getOrDefault(emptyList())
            .map { "${it.loadLabel(pm)} · ${it.activityInfo.packageName}" }
            .distinct().sorted()

    /** De fábrica o instalada después, y quién la instaló (la tienda, si vino de una). */
    @Suppress("DEPRECATION")
    private fun origin(pm: PackageManager, pkg: String): String {
        val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: return "$pkg (no instalado)"
        val flags = info.applicationInfo?.flags ?: 0
        val kind = when {
            flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 -> "de fábrica, actualizada"
            flags and ApplicationInfo.FLAG_SYSTEM != 0 -> "de fábrica"
            else -> "instalada después"
        }
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).let { it.installingPackageName ?: it.initiatingPackageName }
            else pm.getInstallerPackageName(pkg)
        }.getOrNull()
        val since = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(info.firstInstallTime))
        return "$pkg ${info.versionName ?: ""} · $kind · instalada por ${installer ?: "?"} · desde $since"
    }

    @Suppress("DEPRECATION")
    private fun carPackages(pm: PackageManager): List<String> =
        runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
            .filter { p -> CAR_PREFIXES.any { p.packageName.startsWith(it) } }
            .sortedBy { it.packageName }
            .map { "${it.packageName} ${it.versionName ?: ""} (${if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()})" }

    @Suppress("DEPRECATION")
    private fun describePackage(pm: PackageManager, pkg: String): String {
        val info = runCatching {
            pm.getPackageInfo(pkg, PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS)
        }.getOrNull() ?: return "  no instalado (o Android no nos deja verlo)\n"
        val sb = StringBuilder()
        sb.appendLine("  versión ${info.versionName} · sistema: ${(info.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0}")
        info.permissions?.forEach { sb.appendLine("  declara permiso ${it.name}: ${protectionName(protectionOf(it))}") }
        info.services?.forEach {
            sb.appendLine("  servicio ${it.name} · exportado=${it.exported} · permiso=${it.permission ?: "ninguno"}")
        }
        return sb.toString()
    }

    @Suppress("DEPRECATION")
    private fun protectionOf(info: PermissionInfo): Int =
        if (Build.VERSION.SDK_INT >= 28) info.protection or info.protectionFlags else info.protectionLevel

    /** Conecta, lee los valores de telemetría disponibles en solo lectura y desconecta en seguida. */
    private suspend fun bind(context: Context, t: Target): String = withContext(Dispatchers.Main) {
        val intent = Intent(t.action).setPackage(t.pkg)
        val si = context.packageManager.resolveService(intent, 0)?.serviceInfo
            ?: return@withContext "no hay servicio para esa acción"
        val head = "${si.name} (exportado=${si.exported}, permiso=${si.permission ?: "ninguno"})"
        var conn: ServiceConnection? = null
        val result = try {
            withTimeoutOrNull(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine<String> { cont ->
                    fun done(s: String) {
                        if (cont.isActive) cont.resume(s)
                    }
                    val c = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                            val desc = runCatching { binder?.interfaceDescriptor }.getOrNull() ?: "?"
                            val extra = StringBuilder()
                            if (binder != null && desc == LeapVehicleDataSource.AUTOSDK_DESCRIPTOR) {
                                for (spec in LeapVehicleDataSource.POLL_SPECS) {
                                    val v = LeapVehicleDataSource.transactAutoSdkValue(binder, spec)
                                    if (v != null) {
                                        extra.append("\n      [tx ${spec.code}] ${spec.label}: $v")
                                    }
                                }
                            } else if (binder != null && (desc == LeapVehicleDataSource.BINDER_POOL_DESCRIPTOR || desc.contains("BinderPool"))) {
                                for (poolId in 0..12) {
                                    val sub = LeapVehicleDataSource.queryBinderFromPool(binder, desc, poolId)
                                    if (sub != null) {
                                        val subDesc = runCatching { sub.interfaceDescriptor }.getOrNull() ?: "?"
                                        extra.append("\n      [pool $poolId] -> $subDesc")
                                    }
                                }
                            }
                            done("CONECTA · interfaz $desc$extra")
                        }

                        override fun onServiceDisconnected(name: ComponentName) {}

                        override fun onNullBinding(name: ComponentName) = done("conecta, pero el servicio no da interfaz")

                        override fun onBindingDied(name: ComponentName) = done("la conexión se cayó")
                    }
                    conn = c
                    try {
                        if (!context.bindService(intent, c, Context.BIND_AUTO_CREATE)) done("Android no deja conectar (bindService = false)")
                    } catch (e: SecurityException) {
                        done("RECHAZADO: ${e.message}")
                    }
                }
            } ?: "sin respuesta en ${BIND_TIMEOUT_MS / 1000} s"
        } finally {
            conn?.let { runCatching { context.unbindService(it) } }
        }
        "$head\n    → $result"
    }

    /** Puro: el nivel de protección de un permiso en palabras («signature + privileged», «normal»…). */
    fun protectionName(level: Int): String {
        val base = when (level and PermissionInfo.PROTECTION_MASK_BASE) {
            PermissionInfo.PROTECTION_NORMAL -> "normal"
            PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
            PermissionInfo.PROTECTION_SIGNATURE -> "signature"
            PermissionInfo.PROTECTION_SIGNATURE or PermissionInfo.PROTECTION_DANGEROUS -> "signatureOrSystem"
            4 -> "internal"
            else -> "base ${level and PermissionInfo.PROTECTION_MASK_BASE}"
        }
        val flags = FLAGS.filter { (bit, _) -> level and bit != 0 }.map { it.second }
        return if (flags.isEmpty()) base else base + " + " + flags.joinToString(" + ")
    }

    private val FLAGS = listOf(
        0x10 to "privileged",
        0x20 to "development",
        0x40 to "appop",
        0x80 to "pre23",
        0x100 to "installer",
        0x200 to "verifier",
        0x400 to "preinstalled",
    )
}
