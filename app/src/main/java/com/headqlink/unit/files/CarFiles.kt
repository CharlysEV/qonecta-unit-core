package com.headqlink.unit.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract

/**
 * Abre el explorador de archivos nativo del coche (DocumentsUI de Android) para instalar o
 * actualizar Qonecta Unit desde un pendrive USB sin pasar por Vivaldi, sin permisos de
 * instalación (`REQUEST_INSTALL_PACKAGES`) ni acceso a almacenamiento.
 *
 * En modo explorador (`FilesActivity`), tocar el `.apk` lanza el instalador de Android.
 * Si la unidad bloquea el explorador y cae en el selector (`ACTION_OPEN_DOCUMENT`), tocar el
 * icono `⤢` de la esquina del `.apk` abre igualmente el instalador del sistema.
 */
object CarFiles {

    const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    enum class OpenResult {
        BROWSER,
        PICKER,
        UNAVAILABLE,
    }

    /** Un almacenamiento visto por Android: su raíz en el explorador («primary» o el id del pen) y si es extraíble. */
    data class Volume(val rootId: String, val removable: Boolean)

    /** Puro: las raíces que se intentan abrir, el pen primero y la memoria interna después. */
    fun rootOrder(volumes: List<Volume>): List<String> =
        (volumes.filter { it.removable } + volumes.filterNot { it.removable }).map { it.rootId }.distinct()

    /** Puro: identificador del primer pendrive USB extraíble montado, o null si no hay ninguno. */
    fun preferredUsbRoot(volumes: List<Volume>): String? =
        volumes.firstOrNull { it.removable }?.rootId

    /**
     * Intent `ACTION_CREATE_DOCUMENT` para que el usuario elija dónde guardar un archivo
     * (por ejemplo un informe `.txt` de diagnóstico en su pendrive USB sin ocupar la memoria interna del coche).
     * En Android 8+ (API 26+) fija `EXTRA_INITIAL_URI` directamente a la raíz del pendrive USB si está montado.
     */
    fun createDocumentIntent(
        context: Context,
        fileName: String,
        mimeType: String = "text/plain",
    ): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mimeType
        putExtra(Intent.EXTRA_TITLE, fileName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val usbRoot = preferredUsbRoot(volumes(context))
            if (usbRoot != null) {
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildRootUri(EXTERNAL_STORAGE, usbRoot),
                )
            }
        }
    }

    fun volumes(context: Context): List<Volume> {
        if (Build.VERSION.SDK_INT < 24) return emptyList()
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return emptyList()
        return sm.storageVolumes.mapNotNull { v ->
            val mounted = v.state == Environment.MEDIA_MOUNTED || v.state == Environment.MEDIA_MOUNTED_READ_ONLY
            if (!mounted) return@mapNotNull null
            val id = if (v.isPrimary) "primary" else v.uuid ?: return@mapNotNull null
            Volume(id, v.isRemovable)
        }
    }

    /** El explorador abierto en una raíz. */
    fun browse(rootId: String): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(DocumentsContract.buildRootUri(EXTERNAL_STORAGE, rootId), DocumentsContract.Root.MIME_TYPE_ITEM)

    /** El selector de archivos de Android (el mismo que abre Vivaldi al subir un archivo). */
    fun picker(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)

    /** Modos explorador completo (sin pasar por selector): pen primero, memoria interna, y app Archivos nativa. */
    fun browserIntents(context: Context): List<Intent> = buildList {
        val roots = rootOrder(volumes(context)).ifEmpty { listOf("primary") }
        for (root in roots) add(browse(root))
        if (Build.VERSION.SDK_INT >= 29) {
            add(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_FILES))
        }
        add(Intent(Intent.ACTION_MAIN).setClassName("com.android.documentsui", "com.android.documentsui.files.FilesActivity"))
        add(Intent(Intent.ACTION_MAIN).setClassName("com.google.android.documentsui", "com.android.documentsui.files.FilesActivity"))
    }

    /** Todas las formas de abrir archivos en orden: explorador primero y selector como último recurso. */
    fun intents(context: Context): List<Intent> = browserIntents(context) + picker()

    /**
     * Abre el explorador de archivos del coche. Si la unidad solo permite el selector
     * (`ACTION_OPEN_DOCUMENT`), usa [onLaunchPicker] si se aporta (para lanzar con `ActivityResultLauncher`)
     * o `startActivity(picker())`.
     */
    fun open(
        context: Context,
        onLaunchPicker: ((Intent) -> Boolean)? = null,
    ): OpenResult {
        val needsNewTask = context !is Activity
        for (intent in browserIntents(context)) {
            if (needsNewTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                return OpenResult.BROWSER
            }
        }
        val pickerIntent = picker()
        if (onLaunchPicker != null) {
            if (runCatching { onLaunchPicker(pickerIntent) }.getOrDefault(false)) {
                return OpenResult.PICKER
            }
        }
        if (needsNewTask) pickerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { context.startActivity(pickerIntent) }.isSuccess) {
            OpenResult.PICKER
        } else {
            OpenResult.UNAVAILABLE
        }
    }
}
