package com.headqlink.unit.files

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract

/**
 * El explorador de archivos del propio coche (DocumentsUI de Android), para instalar o actualizar desde el pen sin
 * depender de Vivaldi: en el explorador, tocar un APK abre el instalador de Android. Si el coche no deja abrir el
 * explorador, queda el selector de archivos, donde el icono ⤢ de un APK hace lo mismo.
 */
object CarFiles {

    const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /** Un almacenamiento visto por Android: su raíz en el explorador («primary» o el id del pen) y si es extraíble. */
    data class Volume(val rootId: String, val removable: Boolean)

    /** Puro: las raíces que se intentan abrir, el pen primero y la memoria interna después. */
    fun rootOrder(volumes: List<Volume>): List<String> =
        (volumes.filter { it.removable } + volumes.filterNot { it.removable }).map { it.rootId }.distinct()

    fun volumes(context: Context): List<Volume> {
        if (Build.VERSION.SDK_INT < 24) return emptyList()
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return emptyList()
        return sm.storageVolumes.mapNotNull { v ->
            val id = if (v.isPrimary) "primary" else v.uuid ?: return@mapNotNull null
            Volume(id, v.isRemovable)
        }
    }

    /** El explorador abierto en una raíz. */
    fun browse(rootId: String): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(DocumentsContract.buildRootUri(EXTERNAL_STORAGE, rootId), DocumentsContract.Root.MIME_TYPE_ITEM)

    /** El selector de archivos de Android (el mismo que abre Vivaldi al subir un archivo). */
    fun picker(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")

    /** Las formas de abrir los archivos, en orden: cada raíz (el pen primero), la app Archivos y el selector. */
    fun intents(context: Context): List<Intent> = buildList {
        for (root in rootOrder(volumes(context))) add(browse(root))
        if (Build.VERSION.SDK_INT >= 29) add(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_FILES))
        add(picker())
    }

    /** Abre la primera que Android acepte; false si ninguna. */
    fun open(context: Context): Boolean =
        intents(context).any { runCatching { context.startActivity(it) }.isSuccess }
}
