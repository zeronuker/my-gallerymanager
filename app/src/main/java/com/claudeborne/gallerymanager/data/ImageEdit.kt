package com.claudeborne.gallerymanager.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Non-destructive edit choices: rotation in degrees, mirror, and a centre-crop aspect ratio (width / height, null = keep). */
data class EditOps(val rotation: Int = 0, val flip: Boolean = false, val ratio: Float? = null)

/** Decodes with EXIF rotation applied, shrunk so the longest side is about [maxSide] px. */
suspend fun decodeForEdit(context: Context, uri: Uri, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(maxOf(1, maxOf(info.size.width, info.size.height) / maxSide))
        }
    }.getOrNull()
}

fun applyEdits(src: Bitmap, ops: EditOps): Bitmap {
    var b = src
    if (ops.rotation != 0 || ops.flip) {
        val m = Matrix()
        if (ops.flip) m.postScale(-1f, 1f, src.width / 2f, src.height / 2f)
        m.postRotate(ops.rotation.toFloat())
        b = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
    ops.ratio?.let { r ->
        val w = b.width
        val h = b.height
        val cw = if (w.toFloat() / h > r) (h * r).toInt() else w
        val ch = if (w.toFloat() / h > r) h else (w / r).toInt()
        if (cw in 1 until w + 1 && ch in 1 until h + 1 && (cw != w || ch != h)) b = Bitmap.createBitmap(b, (w - cw) / 2, (h - ch) / 2, cw, ch)
    }
    return b
}

/** Saves the edited picture as a NEW file in Pictures/Edited; the original stays untouched. */
suspend fun saveEdited(context: Context, item: MediaItem, ops: EditOps): Boolean = withContext(Dispatchers.IO) {
    val full = decodeForEdit(context, item.uri, 4096) ?: return@withContext false
    val out = applyEdits(full, ops)
    val png = item.mime == "image/png"
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, item.name.substringBeforeLast('.') + "_edit." + if (png) "png" else "jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, if (png) "image/png" else "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Edited")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return@withContext false
    val ok = runCatching {
        resolver.openOutputStream(uri)?.use { out.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) } ?: false
    }.getOrDefault(false)
    if (!ok) { resolver.delete(uri, null, null); return@withContext false }
    values.clear()
    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    true
}
