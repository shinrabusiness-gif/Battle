package io.github.ultrascope.storage

import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Speichert Bilder unveraendert (Byte-genaue Kopie der Kameradaten) ueber MediaStore:
 *  - Bilder  -> Pictures/UltraScope/
 *  - Metadaten (JSON-Sidecar) -> Documents/UltraScope/
 * Es wird keine Speicherberechtigung benoetigt (Android 10+ Scoped Storage).
 */
object MediaStoreSaver {
    const val FOLDER = "UltraScope"

    fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    fun mimeFor(name: String): String = when {
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".dng", true) -> "image/x-adobe-dng"
        name.endsWith(".tif", true) || name.endsWith(".tiff", true) -> "image/tiff"
        else -> "application/octet-stream"
    }

    /** Kopiert [src] als Bild in die Galerie. */
    fun saveImage(context: Context, src: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(displayName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("MediaStore-Eintrag fuer $displayName fehlgeschlagen")
        try {
            resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 16) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Legt eine Text-/JSON-Datei in Documents/UltraScope ab. */
    fun saveText(context: Context, text: String, displayName: String, mime: String = "application/json"): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER)
        }
        return try {
            val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
            resolver.openOutputStream(uri)!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            uri
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Schreibt Metadaten verlustfrei in die EXIF-Daten einer JPEG-Datei
     * (die Bilddaten selbst werden dabei nicht neu kodiert).
     */
    fun writeExif(jpeg: File, description: String, userComment: String) {
        try {
            val exif = ExifInterface(jpeg.absolutePath)
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description.take(1000))
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, userComment.take(4000))
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "UltraScope")
            exif.saveAttributes()
        } catch (e: Exception) {
            // EXIF ist optional – das Sidecar-JSON enthaelt dieselben Angaben
        }
    }
}
