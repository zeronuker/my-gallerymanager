package com.claudeborne.gallerymanager.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.data.Settings
import java.io.File

private val GPS_TAGS = listOf(
    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
    ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF, ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
    ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF, ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
    ExifInterface.TAG_GPS_MAP_DATUM, ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE,
    ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_VERSION_ID,
)

/** Formats that can carry location data and that Android's EXIF editor can rewrite. */
private fun canStrip(item: MediaItem) = !item.isVideo && item.mime in setOf("image/jpeg", "image/png", "image/webp")

/** A copy of [item] in the app's cache with every location tag removed, or null if it could not be made. */
private fun strippedCopy(context: Context, item: MediaItem, index: Int): Uri? = runCatching {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(dir, "${index}_${item.name}")
    // the original stream (not Android's redacted one), so what is removed is removed by us
    val input = runCatching { context.contentResolver.openInputStream(MediaStore.setRequireOriginal(item.uri)) }.getOrNull()
        ?: context.contentResolver.openInputStream(item.uri)!!
    input.use { src -> file.outputStream().use { src.copyTo(it) } }
    ExifInterface(file.path).apply { GPS_TAGS.forEach { setAttribute(it, null) }; saveAttributes() }
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}.getOrNull()

/** Opens Android's share sheet for [items]. With "remove location when sharing" on, photos go out as location-free copies. */
fun shareMedia(context: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    File(context.cacheDir, "share").deleteRecursively() // the copies from the last share are no longer needed
    val uris = items.mapIndexed { i, item -> (if (Settings.stripOnShare && canStrip(item)) strippedCopy(context, item, i) else null) ?: item.uri }
    val send = if (items.size == 1) {
        Intent(Intent.ACTION_SEND).apply { type = items[0].mime.ifEmpty { "*/*" }; putExtra(Intent.EXTRA_STREAM, uris[0]) }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
    }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}
