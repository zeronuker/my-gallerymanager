package com.claudeborne.gallerymanager.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Shape to crop the video to (always centred); [ratio] is width / height, or 0 for "keep as it is". */
enum class VideoAspect(val label: String, val ratio: Float) {
    ORIGINAL("ORIGINAL", 0f), SQUARE("1:1", 1f), R45("4:5", 4f / 5), R916("9:16", 9f / 16), R169("16:9", 16f / 9), R43("4:3", 4f / 3)
}

/** How much to shrink the picture: 0 keeps the size. */
enum class VideoSize(val label: String, val height: Int) { ORIGINAL("ORIGINAL", 0), P1080("1080P", 1080), P720("720P", 720), P480("480P", 480) }

enum class VideoQuality(val label: String, val factor: Float) { HIGH("HIGH", 1f), MEDIUM("MEDIUM", 0.6f), LOW("LOW", 0.35f) }

/** Everything the user chose for a video. Times are milliseconds in the original; [endMs] 0 means "to the end". */
data class VideoEdit(
    val startMs: Long = 0,
    val endMs: Long = 0,
    val speed: Float = 1f,
    val volume: Float = 1f, // 0 = no sound, 1 = as recorded, up to 2
    val turns: Int = 0, // quarter turns clockwise
    val flip: Boolean = false,
    val aspect: VideoAspect = VideoAspect.ORIGINAL,
    val size: VideoSize = VideoSize.ORIGINAL,
    val quality: VideoQuality = VideoQuality.HIGH,
    val brightness: Float = 0f, // -1..1, 0 = unchanged
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val filter: Int = 0, // index into the photo Filters list
    val filterAmount: Float = 1f,
    val marks: List<com.claudeborne.gallerymanager.edit.Mark> = emptyList(), // text, stickers and pen strokes over the whole video
    val exactCut: Boolean = false, // cut at exactly the chosen frames (re-encodes) instead of the nearest key frame (instant, lossless)
) {
    /** True when the picture or sound has to be re-encoded. A plain trim, with or without the sound, is just copied. */
    val hasLook get() = brightness != 0f || contrast != 0f || saturation != 0f || warmth != 0f || filter != 0
    val needsEncode get() = exactCut || hasLook || marks.isNotEmpty() || speed != 1f || (volume > 0f && volume != 1f) || turns != 0 || flip || aspect != VideoAspect.ORIGINAL || size != VideoSize.ORIGINAL || quality != VideoQuality.HIGH
}

/** Facts about a video, as it is shown (after its rotation flag is applied). */
class VideoInfo(val durationMs: Long, val width: Int, val height: Int, val bitrate: Int, val hasAudio: Boolean)

suspend fun readVideoInfo(context: Context, uri: Uri): VideoInfo = withContext(Dispatchers.IO) {
    MediaMetadataRetriever().use { r ->
        r.setDataSource(context, uri)
        fun num(key: Int) = r.extractMetadata(key)?.toLongOrNull() ?: 0L
        val rot = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()
        val w = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
        val h = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
        VideoInfo(
            durationMs = num(MediaMetadataRetriever.METADATA_KEY_DURATION),
            width = if (rot % 180 == 0) w else h,
            height = if (rot % 180 == 0) h else w,
            bitrate = num(MediaMetadataRetriever.METADATA_KEY_BITRATE).toInt(),
            hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
        )
    }
}

/** [count] small pictures spread along the video, for the trim strip. */
suspend fun videoThumbs(context: Context, uri: Uri, durationMs: Long, count: Int): List<Bitmap?> = withContext(Dispatchers.IO) {
    MediaMetadataRetriever().use { r ->
        r.setDataSource(context, uri)
        List(count) { i ->
            val t = durationMs * 1000L * i / count
            runCatching { r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 200, 200) }.getOrNull()
        }
    }
}

/** The picture at [timeMs], full size. */
suspend fun videoFrame(context: Context, uri: Uri, timeMs: Long): Bitmap? = withContext(Dispatchers.IO) {
    MediaMetadataRetriever().use { r ->
        r.setDataSource(context, uri)
        runCatching { r.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull()
    }
}

/** Length of the finished video, after trimming and the speed change. */
fun VideoEdit.outputMs(info: VideoInfo): Long = (((if (endMs > 0) endMs else info.durationMs) - startMs) / speed).toLong().coerceAtLeast(0)

/** Height of the finished picture. */
fun VideoEdit.outputHeight(info: VideoInfo): Int {
    val turned = turns % 2 == 1
    val w = if (turned) info.height else info.width
    val h = if (turned) info.width else info.height
    val cropH = if (aspect.ratio > 0f) (if (w.toFloat() / h > aspect.ratio) h else (w / aspect.ratio).roundToInt()) else h
    return if (size.height > 0) minOf(size.height, cropH) else cropH
}

/** Rough bitrate (bits per second) of the finished video; used for the encoder and for the size estimate. */
fun VideoEdit.targetBitrate(info: VideoInfo): Int {
    val h = outputHeight(info)
    val base = when {
        h >= 1000 -> 10_000_000
        h >= 700 -> 5_000_000
        h >= 450 -> 2_500_000
        else -> 1_200_000
    }
    return (base * quality.factor).toInt()
}

/** Estimated file size in bytes, or 0 when the video will not be re-encoded. */
fun VideoEdit.estimatedBytes(info: VideoInfo): Long =
    if (!needsEncode) 0L else (targetBitrate(info) + (if (volume > 0f) 128_000 else 0)) / 8L * outputMs(info) / 1000L
