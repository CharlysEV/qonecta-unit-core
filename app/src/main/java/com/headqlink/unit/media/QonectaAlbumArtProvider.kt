package com.headqlink.unit.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Serves album art Bitmaps via `content://<applicationId>.albumart/art-<hex>.png`
 * for the native Leapmotor C10 desktop media widget and instrument cluster media card.
 *
 * Mirrors the exact LeapOS media contract discovered from LeapLink's `AlbumArtProvider`:
 * - Writes PNG files into `cacheDir/albumart/art-<hash>.png`
 * - Keeps at most 2 recent PNG files so cache never grows unbounded
 * - Validates file names against `art-[0-9a-f]{1,8}\.png`
 */
class QonectaAlbumArtProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        val segment = uri.lastPathSegment ?: return null
        return if (FILE_NAME_REGEX.matches(segment)) "image/png" else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") {
            throw SecurityException("QonectaAlbumArtProvider is read-only")
        }
        val ctx = context ?: throw FileNotFoundException("No context available")
        val segment = uri.lastPathSegment ?: throw FileNotFoundException("Missing segment: $uri")
        if (!FILE_NAME_REGEX.matches(segment)) {
            throw FileNotFoundException("Invalid album art URI: $uri")
        }
        val file = File(File(ctx.cacheDir, CACHE_SUBDIR), segment)
        if (!file.isFile) {
            throw FileNotFoundException("Album art file not found: $uri")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val TAG = "QonectaAlbumArt"
        private const val CACHE_SUBDIR = "albumart"
        private val FILE_NAME_REGEX = Regex("^art-[0-9a-f]{1,8}\\.png$")

        @Volatile
        private var lastHash: Int = 0

        @Volatile
        private var lastUri: Uri? = null

        /**
         * Stores [bitmap] as a PNG in `cacheDir/albumart/art-<hex>.png` and returns its
         * `content://<packageName>.albumart/art-<hex>.png` URI for LeapOS `MediaMetadataCompat`.
         */
        fun publishAlbumArt(context: Context, bitmap: Bitmap?): Uri? {
            if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                return null
            }
            val hash = computeBitmapSampleHash(bitmap)
            val cached = lastUri
            if (hash == lastHash && cached != null) {
                return cached
            }
            return try {
                val dir = File(context.cacheDir, CACHE_SUBDIR)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val fileName = "art-${Integer.toHexString(hash)}.png"
                val outFile = File(dir, fileName)
                FileOutputStream(outFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 92, fos)
                    fos.flush()
                }
                // Keep at most the 2 most recent files (current + previous) so LeapOS reading the old URI never races
                dir.listFiles()
                    ?.filter { it.isFile && it.name != fileName }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(1)
                    ?.forEach { old -> runCatching { old.delete() } }

                val authority = "${context.packageName}.albumart"
                val uri = Uri.Builder()
                    .scheme("content")
                    .authority(authority)
                    .appendPath(fileName)
                    .build()
                lastHash = hash
                lastUri = uri
                uri
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to publish album art PNG: ${t.message}")
                null
            }
        }

        private fun computeBitmapSampleHash(bmp: Bitmap): Int {
            var h = 31 * bmp.width + bmp.height
            val stepX = (bmp.width / 6).coerceAtLeast(1)
            val stepY = (bmp.height / 6).coerceAtLeast(1)
            var y = 0
            while (y < bmp.height) {
                var x = 0
                while (x < bmp.width) {
                    h = 31 * h + bmp.getPixel(x, y)
                    x += stepX
                }
                y += stepY
            }
            return if (h == 0) 1 else h
        }
    }
}
