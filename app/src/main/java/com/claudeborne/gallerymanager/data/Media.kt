package com.claudeborne.gallerymanager.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns as C
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** id of an item opened from another app that is not in MediaStore (no trash, single-item viewer) */
const val EXTERNAL_ID = -1L

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val isVideo: Boolean,
    val takenMs: Long,
    val size: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val bucketId: Long,
    val bucketName: String,
    val path: String,
    val expiresMs: Long = 0, // when a trashed item is removed for good; 0 for normal items
)

private const val IMAGE = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
private const val VIDEO = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

/** All photos and videos on every storage volume, newest first; with [trashed] only what is in the system trash instead. */
suspend fun loadMedia(context: Context, trashed: Boolean = false): List<MediaItem> = withContext(Dispatchers.IO) {
    val projection = arrayOf(
        C._ID, C.DISPLAY_NAME, MediaStore.Files.FileColumns.MEDIA_TYPE, C.DATE_TAKEN, C.DATE_MODIFIED,
        C.SIZE, C.DURATION, C.BUCKET_ID, C.BUCKET_DISPLAY_NAME, C.MIME_TYPE, C.WIDTH, C.HEIGHT, C.RELATIVE_PATH,
        C.DATE_EXPIRES,
    )
    val args = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($IMAGE, $VIDEO)")
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
    }
    val items = mutableListOf<MediaItem>()
    context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, args, null)?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val isVideo = c.getInt(2) == VIDEO
            val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            // images without EXIF have no date taken; file modified time beats date added (download time)
            val taken = c.getLong(3).takeIf { it > 0 } ?: (c.getLong(4) * 1000)
            items += MediaItem(
                id = id,
                uri = ContentUris.withAppendedId(base, id).let { if (trashed) it.buildUpon().appendQueryParameter("includeTrashed", "1").build() else it }, // MediaStore hides trashed rows from direct reads otherwise
                name = c.getString(1).orEmpty(),
                mime = c.getString(9).orEmpty(),
                isVideo = isVideo,
                takenMs = taken,
                size = c.getLong(5),
                durationMs = c.getLong(6),
                width = c.getInt(10),
                height = c.getInt(11),
                bucketId = c.getLong(7),
                bucketName = c.getString(8).orEmpty(),
                path = c.getString(12).orEmpty(),
                expiresMs = c.getLong(13) * 1000,
            )
        }
    }
    items.sortedByDescending { it.takenMs }
}

/** An image/video handed to us by another app ("Open with"); only what the viewer needs. */
fun externalItem(context: Context, uri: Uri): MediaItem {
    val mime = context.contentResolver.getType(uri).orEmpty()
    return MediaItem(EXTERNAL_ID, uri, uri.lastPathSegment.orEmpty(), mime, mime.startsWith("video"), 0, 0, 0, 0, 0, 0, "", "")
}

/** Camera details and GPS from EXIF as label/value rows; empty for videos or files without EXIF. */
suspend fun readExif(context: Context, item: MediaItem): List<Pair<String, String>> = withContext(Dispatchers.IO) {
    if (item.isVideo) return@withContext emptyList()
    // setRequireOriginal keeps GPS (needs ACCESS_MEDIA_LOCATION); it throws for non-MediaStore uris
    val stream = runCatching { context.contentResolver.openInputStream(MediaStore.setRequireOriginal(item.uri)) }.getOrNull()
        ?: runCatching { context.contentResolver.openInputStream(item.uri) }.getOrNull()
        ?: return@withContext emptyList()
    stream.use {
        val e = ExifInterface(it)
        buildList {
            val make = e.getAttribute(ExifInterface.TAG_MAKE).orEmpty().trim()
            val model = e.getAttribute(ExifInterface.TAG_MODEL).orEmpty().trim()
            // many cameras put their brand in the model too ("Canon" + "Canon EOS 600D"); show it once
            val camera = if (model.startsWith(make, ignoreCase = true)) model.ifEmpty { make } else "$make $model".trim()
            if (camera.isNotEmpty()) add("CAMERA" to camera)
            val lens = listOfNotNull(
                e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { "f/%.1f".format(it) },
                e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { "%.0f mm".format(it) },
            ).joinToString(" · ")
            if (lens.isNotEmpty()) add("LENS" to lens)
            val exposure = listOfNotNull(
                e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }
                    ?.let { if (it < 1) "1/${Math.round(1 / it)} s" else "%.1f s".format(it) },
                e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" },
            ).joinToString(" · ")
            if (exposure.isNotEmpty()) add("EXPOSURE" to exposure)
            e.latLong?.let { add("GPS" to "%.5f, %.5f".format(it[0], it[1])) }
        }
    }
}
