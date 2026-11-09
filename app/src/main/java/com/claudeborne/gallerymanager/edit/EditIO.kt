package com.claudeborne.gallerymanager.edit

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns as C
import androidx.exifinterface.media.ExifInterface
import com.claudeborne.gallerymanager.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Decodes [uri] upright (EXIF rotation applied), shrunk so its long side is at most [maxEdge]. */
suspend fun loadBitmap(context: Context, uri: Uri, maxEdge: Int): Bitmap = withContext(Dispatchers.IO) {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val long = max(info.size.width, info.size.height)
        if (long > maxEdge) {
            val s = maxEdge.toFloat() / long
            decoder.setTargetSize((info.size.width * s).roundToInt().coerceAtLeast(1), (info.size.height * s).roundToInt().coerceAtLeast(1))
        }
    }
}

// camera and location facts worth carrying over to the edited file; orientation is left out because the pixels are already upright
private val EXIF_TAGS = listOf(
    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
)

private fun readExifTags(context: Context, uri: Uri): Map<String, String> = runCatching {
    // setRequireOriginal keeps GPS; it throws for non-MediaStore uris
    val stream = runCatching { context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri)) }.getOrNull()
        ?: context.contentResolver.openInputStream(uri)
    stream?.use { s -> ExifInterface(s).let { e -> EXIF_TAGS.mapNotNull { t -> e.getAttribute(t)?.let { t to it } }.toMap() } } ?: emptyMap()
}.getOrDefault(emptyMap())

/** True when [item] can be overwritten in place without changing its format. */
fun canReplace(item: MediaItem) = item.mime == "image/jpeg" || item.mime == "image/png"

/**
 * Writes [bitmap] as a new "<name>_edit" file next to [item], or over [item] itself when [replace] (the caller must already hold
 * write access). Camera and GPS details are copied over. Returns false if writing failed.
 */
suspend fun saveEdited(context: Context, item: MediaItem, bitmap: Bitmap, replace: Boolean): Boolean = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val png = item.mime == "image/png"
    var tags = if (png) emptyMap() else readExifTags(context, item.uri) // read before a replace overwrites them
    // MediaStore re-reads the date from the file after every write, so a photo without an EXIF date would jump to today; give it one
    if (!png && item.takenMs > 0 && ExifInterface.TAG_DATETIME_ORIGINAL !in tags) {
        tags = tags + (ExifInterface.TAG_DATETIME_ORIGINAL to SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(item.takenMs)))
    }
    val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG

    // Build the finished file (pixels plus EXIF) first and hand it to MediaStore in one write: it re-reads the date from the file
    // after every write, so a file written in two steps would be scanned half done and lose its date.
    val file = File.createTempFile("edit", ".img", context.cacheDir)
    try {
        file.outputStream().use { if (!bitmap.compress(format, 95, it)) return@withContext false }
        if (tags.isNotEmpty()) runCatching { ExifInterface(file.path).apply { tags.forEach { (k, v) -> setAttribute(k, v) }; saveAttributes() } }

        if (replace) {
            runCatching { resolver.openOutputStream(item.uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } } }.isSuccess
        } else {
            val values = ContentValues().apply {
                put(C.DISPLAY_NAME, item.name.substringBeforeLast('.') + "_edit." + if (png) "png" else "jpg")
                put(C.MIME_TYPE, if (png) "image/png" else "image/jpeg")
                put(C.RELATIVE_PATH, item.path)
                put(C.IS_PENDING, 1)
            }
            val target = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return@withContext false
            val ok = runCatching { resolver.openOutputStream(target)!!.use { out -> file.inputStream().use { it.copyTo(out) } } }.isSuccess
            if (ok) resolver.update(target, ContentValues().apply { put(C.IS_PENDING, 0) }, null, null) else resolver.delete(target, null, null)
            ok
        }
    } finally {
        file.delete()
    }
}
