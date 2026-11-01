package com.claudeborne.gallerymanager.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns as C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val takenMs: Long,
    val size: Long,
    val durationMs: Long,
    val bucketId: Long,
    val bucketName: String,
)

private const val IMAGE = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
private const val VIDEO = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

/** All photos and videos on every storage volume, newest first. Trashed items are excluded by MediaStore. */
suspend fun loadMedia(context: Context): List<MediaItem> = withContext(Dispatchers.IO) {
    val projection = arrayOf(
        C._ID, C.DISPLAY_NAME, MediaStore.Files.FileColumns.MEDIA_TYPE, C.DATE_TAKEN, C.DATE_MODIFIED,
        C.SIZE, C.DURATION, C.BUCKET_ID, C.BUCKET_DISPLAY_NAME,
    )
    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($IMAGE, $VIDEO)"
    val items = mutableListOf<MediaItem>()
    context.contentResolver.query(
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, selection, null, null,
    )?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val isVideo = c.getInt(2) == VIDEO
            val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            // images without EXIF have no date taken; file modified time beats date added (download time)
            val taken = c.getLong(3).takeIf { it > 0 } ?: (c.getLong(4) * 1000)
            items += MediaItem(
                id = id,
                uri = ContentUris.withAppendedId(base, id),
                name = c.getString(1).orEmpty(),
                isVideo = isVideo,
                takenMs = taken,
                size = c.getLong(5),
                durationMs = c.getLong(6),
                bucketId = c.getLong(7),
                bucketName = c.getString(8).orEmpty(),
            )
        }
    }
    items.sortedByDescending { it.takenMs }
}
