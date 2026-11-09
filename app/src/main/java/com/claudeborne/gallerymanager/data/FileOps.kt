package com.claudeborne.gallerymanager.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns as C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Renaming and moving edit files other apps may own, so the caller first gets write access
// through MediaStore.createWriteRequest. Copying only creates new files and needs no permission.

/** Renames [item] to [baseName], keeping its extension. */
suspend fun renameItem(context: Context, item: MediaItem, baseName: String) = withContext(Dispatchers.IO) {
    val ext = item.name.substringAfterLast('.', "")
    val name = if (ext.isEmpty()) baseName else "$baseName.$ext"
    context.contentResolver.update(item.uri, ContentValues().apply { put(C.DISPLAY_NAME, name) }, null, null)
}

/** Moves [items] into [folder], a relative path such as "Pictures/Trips/". */
suspend fun moveItems(context: Context, items: List<MediaItem>, folder: String) = withContext(Dispatchers.IO) {
    items.forEach { context.contentResolver.update(it.uri, ContentValues().apply { put(C.RELATIVE_PATH, folder) }, null, null) }
}

/** Copies image [items] into [folder] as new files (MediaStore picks a free name if one is taken). Returns how many were copied. */
suspend fun copyItems(context: Context, items: List<MediaItem>, folder: String): Int = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var copied = 0
    items.forEach { item ->
        val values = ContentValues().apply {
            put(C.DISPLAY_NAME, item.name)
            put(C.MIME_TYPE, item.mime)
            put(C.RELATIVE_PATH, folder)
            if (item.takenMs > 0) put(C.DATE_TAKEN, item.takenMs) // keeps the copy in the same place on the timeline
            put(C.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return@forEach
        runCatching {
            resolver.openInputStream(item.uri)!!.use { input -> resolver.openOutputStream(target)!!.use { input.copyTo(it) } }
            resolver.update(target, ContentValues().apply { put(C.IS_PENDING, 0) }, null, null)
            copied++
        }.onFailure { resolver.delete(target, null, null) }
    }
    copied
}
