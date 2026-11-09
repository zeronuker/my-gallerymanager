package com.claudeborne.gallerymanager.edit

import android.content.Context
import android.graphics.Bitmap
import com.claudeborne.gallerymanager.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class OutFormat(val label: String, val mime: String, val ext: String) {
    JPG("JPG", "image/jpeg", "jpg"), PNG("PNG", "image/png", "png"), WEBP("WEBP", "image/webp", "webp")
}

/** Longest side an export may have; bigger would not fit in memory or on the GPU. */
const val MAX_EDGE = 8192

/** The size [scale] gives a [w] x [h] picture, held to [MAX_EDGE]. */
fun scaledSize(w: Int, h: Int, scale: Float): Pair<Int, Int> {
    val s = min(scale, MAX_EDGE.toFloat() / max(w, h))
    return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
}

/**
 * Saves a copy of [item] next to it, resized by [scale], in [format] at [quality] (JPG and WEBP), optionally without GPS data.
 * Enlarging is a smooth resize with a little sharpening: it makes the picture bigger, it does not add detail.
 */
suspend fun exportCopy(context: Context, item: MediaItem, scale: Float, format: OutFormat, quality: Int, stripLocation: Boolean): Boolean =
    withContext(Dispatchers.Default) {
        runCatching {
            val longSide = max(item.width, item.height)
            // shrinking is best done by the decoder; enlarging happens on the GPU
            val src = if (scale < 1f && longSide > 0) loadBitmap(context, item.uri, (longSide * scale).roundToInt().coerceAtLeast(1)) else loadBitmap(context, item.uri, MAX_EDGE)
            val out = if (scale > 1f) renderEdited(src, EditState().with(Adjust.SHARPNESS, 0.25f), min(scale, MAX_EDGE.toFloat() / max(src.width, src.height))) else src

            val file = File.createTempFile("export", ".img", context.cacheDir)
            try {
                file.outputStream().use { stream ->
                    val type = when (format) {
                        OutFormat.JPG -> Bitmap.CompressFormat.JPEG
                        OutFormat.PNG -> Bitmap.CompressFormat.PNG
                        OutFormat.WEBP -> Bitmap.CompressFormat.WEBP_LOSSY
                    }
                    check(out.compress(type, quality, stream))
                }
                if (format != OutFormat.PNG) writeTags(file, carriedTags(context, item, stripLocation))

                val parts = listOfNotNull(if (scale != 1f) "${out.width}x${out.height}" else null, if (stripLocation) "nogps" else null).ifEmpty { listOf("copy") }
                insertImage(context, file, item.name.substringBeforeLast('.') + "_" + parts.joinToString("_") + "." + format.ext, format.mime, item.path)
            } finally {
                file.delete()
            }
        }.getOrDefault(false)
    }
