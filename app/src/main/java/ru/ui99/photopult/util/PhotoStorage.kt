package ru.ui99.photopult.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Saves photos to the gallery (DCIM/Photopult via MediaStore) and prepares the pieces the transfer
 * needs: a cache file for the FILE payload and a small base64 thumbnail for instant display.
 */
object PhotoStorage {

    private const val RELATIVE_DIR = "DCIM/Photopult"

    /** Persist full-quality JPEG bytes to the gallery. Returns the content Uri or null. */
    fun saveJpegToGallery(context: Context, bytes: ByteArray, displayName: String): Uri? =
        runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        }.getOrNull()

    /** Copy an already-received file (Uri) into the gallery under [displayName]. */
    fun copyUriToGallery(context: Context, source: Uri, displayName: String): Uri? =
        runCatching {
            val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
                ?: return@runCatching null
            saveJpegToGallery(context, bytes, displayName)
        }.getOrNull()

    /** Write JPEG bytes to a cache file to hand to the Nearby FILE payload. */
    fun writeCacheFile(context: Context, bytes: ByteArray, name: String): File {
        val dir = File(context.cacheDir, "outgoing").apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use { it.write(bytes) }
        return file
    }

    /** Downscale to a small JPEG and base64-encode it for the instant thumbnail. */
    fun makeThumbnailBase64(bytes: ByteArray, maxDim: Int = 240, quality: Int = 60): String {
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""
        val scale = maxDim.toFloat() / maxOf(full.width, full.height).coerceAtLeast(1)
        val thumb = if (scale < 1f) {
            Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            full
        }
        val out = ByteArrayOutputStream()
        thumb.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (thumb !== full) thumb.recycle()
        full.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    @Suppress("unused")
    fun legacyDcimDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Photopult")
}
