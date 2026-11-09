package com.claudeborne.gallerymanager.video

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns as C
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.claudeborne.gallerymanager.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** The picture effects for [edit], in the order they must run: turn and flip, centred crop, then shrink. Also used for the live preview. */
@OptIn(UnstableApi::class)
fun pictureEffects(edit: VideoEdit, info: VideoInfo): List<Effect> = buildList {
    addAll(lookEffects(edit)) // colour first, so crop and shrink only touch the finished colours
    if (edit.turns % 4 != 0 || edit.flip) {
        add(ScaleAndRotateTransformation.Builder().setRotationDegrees(-90f * (edit.turns % 4)).setScale(if (edit.flip) -1f else 1f, 1f).build())
    }
    if (edit.aspect.ratio > 0f) {
        val turned = edit.turns % 2 == 1
        val w = (if (turned) info.height else info.width).toFloat()
        val h = (if (turned) info.width else info.height).toFloat()
        val r = edit.aspect.ratio
        // the part of the frame to keep, in the -1..1 coordinates the crop effect uses
        if (w / h > r) { val f = h * r / w; add(Crop(-f, f, -1f, 1f)) } else { val f = w / r / h; add(Crop(-1f, 1f, -f, f)) }
    }
    // shrink only: a picture already smaller than the chosen height is left alone
    if (edit.size.height > 0 && edit.size.height < edit.copy(size = VideoSize.ORIGINAL).outputHeight(info)) add(Presentation.createForHeight(edit.size.height))
    if (edit.speed != 1f) add(SpeedChangeEffect(edit.speed))
    overlayEffect(edit, info)?.let { add(it) } // last, so it lines up with the finished picture
}

@OptIn(UnstableApi::class)
private fun volumeProcessor(volume: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
    // one mixing matrix per possible input layout (mono and stereo), each just scaling the loudness
    for (ch in 1..2) putChannelMixingMatrix(ChannelMixingMatrix.create(ch, ch).scaleBy(volume))
}

@OptIn(UnstableApi::class)
private fun soundProcessors(edit: VideoEdit): List<AudioProcessor> = buildList {
    if (edit.speed != 1f) add(SonicAudioProcessor().apply { setSpeed(edit.speed) })
    if (edit.volume != 1f) add(volumeProcessor(edit.volume))
}

/** Runs [composition] into [out], reporting progress 0..1. Returns true when it finished. Cancelling the caller cancels the export. */
@OptIn(UnstableApi::class)
private suspend fun runComposition(
    context: Context, composition: Composition, out: File, bitrate: Int?, optimizeTrim: Boolean, onProgress: (Float) -> Unit,
): Boolean = suspendCancellableCoroutine { cont ->
    val builder = Transformer.Builder(context)
    if (optimizeTrim) builder.experimentalSetTrimOptimizationEnabled(true) // a plain trim skips re-encoding where it can
    else if (bitrate != null) {
        builder.setEncoderFactory(
            DefaultEncoderFactory.Builder(context).setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build()).build(),
        )
    }
    val transformer = builder.addListener(object : Transformer.Listener {
        override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(true) }
        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
            android.util.Log.w("VideoExport", "export failed", exportException)
            if (cont.isActive) cont.resume(false)
        }
    }).build()
    transformer.start(composition, out.path)

    val handler = Handler(Looper.getMainLooper())
    val holder = ProgressHolder()
    lateinit var poll: Runnable
    poll = Runnable {
        if (cont.isActive) {
            if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) onProgress(holder.progress / 100f)
            handler.postDelayed(poll, 250)
        }
    }
    handler.post(poll)
    cont.invokeOnCancellation { handler.removeCallbacks(poll); transformer.cancel() }
}

/** The sound file as a track of its own that is mixed under the video and cut to [totalMs], fading with the video. */
@OptIn(UnstableApi::class)
private fun musicSequence(music: android.net.Uri, volume: Float, totalMs: Long, fadeInMs: Long, fadeOutMs: Long): EditedMediaItemSequence {
    val clip = PlayerItem.ClippingConfiguration.Builder().setEndPositionMs(totalMs).build()
    val source = PlayerItem.Builder().setUri(music).setClippingConfiguration(clip).build()
    val processors = buildList {
        if (volume != 1f) add(volumeProcessor(volume))
        fadeSound(fadeInMs, fadeOutMs, totalMs)?.let { add(it) }
    }
    return EditedMediaItemSequence(EditedMediaItem.Builder(source).setRemoveVideo(true).setEffects(Effects(processors, emptyList())).build())
}

/** A fade of the picture over a piece that lasts [durMs]: from black at its start and to black at its end. Null when there is none. */
@OptIn(UnstableApi::class)
private fun fadePicture(inMs: Long, outMs: Long, durMs: Long): Effect? =
    if (inMs <= 0 && outMs <= 0) null else FadePicture(inMs * 1000, outMs * 1000, durMs * 1000)

@OptIn(UnstableApi::class)
private fun fadeSound(inMs: Long, outMs: Long, durMs: Long): AudioProcessor? =
    if (inMs <= 0 && outMs <= 0) null else FadeSound(inMs * 1000, outMs * 1000, durMs * 1000)

/** One piece of the finished video: how long it lasts, and how to build it once it is known whether it is the first or the last. */
private class Slot(val durMs: Long, val make: (fadeInMs: Long, fadeOutMs: Long) -> EditedMediaItem)

private fun builderFor(parts: List<EditedMediaItemSequence>, withTone: Boolean, forceAudio: Boolean) =
    Composition.Builder(parts).apply {
        // when the picture is re-encoded anyway, HDR recordings are turned into normal colours so the edit looks right everywhere;
        // a plain trim keeps the video untouched, which is what lets it skip re-encoding
        if (withTone) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        if (forceAudio) experimentalSetForceAudioTrack(true) // clips and stills without sound get silence, so the sound track never breaks
    }.build()

/**
 * Cuts, changes and re-encodes [item] as described by [edit] into a new file next to it, reporting progress 0..1.
 * Returns false if it failed or was cancelled. Must be called from the main thread (Transformer's rule).
 */
@OptIn(UnstableApi::class)
suspend fun exportVideo(context: Context, item: MediaItem, edit: VideoEdit, info: VideoInfo, onProgress: (Float) -> Unit): Boolean {
    val out = File.createTempFile("video", ".mp4", context.cacheDir)
    // a plain trim (or trim plus mute) is copied directly; if the file cannot be copied that way it falls through to re-encoding
    if (!edit.needsEncode && copyCut(context, item.uri, edit.startMs, edit.endMs, edit.volume > 0f, out, onProgress)) {
        return withContext(Dispatchers.IO) {
            try { saveVideo(context, out, item.name.substringBeforeLast('.') + "_edit.mp4", "video/mp4", item.path) } finally { out.delete() }
        }
    }
    val end = if (edit.endMs > 0) edit.endMs else info.durationMs
    val freezeAt = edit.freezeAtMs.takeIf { it in edit.startMs..end }
    val still = freezeAt?.let { t -> videoFrame(context, item.uri, t)?.let { bmp -> saveStill(context, bmp) } }
    try {
        val total = edit.outputMs(info)
        val sound = edit.volume > 0f && info.hasAudio

        // the part of the video from..to (0 = to the end), with every change applied
        fun segment(from: Long, to: Long, fadeIn: Long, fadeOut: Long, durMs: Long): EditedMediaItem {
            val clip = PlayerItem.ClippingConfiguration.Builder().setStartPositionMs(from).apply { if (to > 0) setEndPositionMs(to) }.build()
            val source = PlayerItem.Builder().setUri(item.uri).setClippingConfiguration(clip).build()
            val audio = if (edit.volume > 0f) soundProcessors(edit) + listOfNotNull(if (sound) fadeSound(fadeIn, fadeOut, durMs) else null) else emptyList()
            val picture = pictureEffects(edit, info) + listOfNotNull(fadePicture(fadeIn, fadeOut, durMs)) // last, so it darkens everything including text
            return EditedMediaItem.Builder(source).setRemoveAudio(edit.volume <= 0f).setEffects(Effects(audio, picture)).build()
        }

        val slots = ArrayList<Slot>()
        if (freezeAt == null || still == null) {
            slots += Slot(total) { fi, fo -> segment(edit.startMs, edit.endMs, fi, fo, total) }
        } else {
            val before = ((freezeAt - edit.startMs) / edit.speed).toLong()
            val after = ((end - freezeAt) / edit.speed).toLong()
            if (freezeAt > edit.startMs) slots += Slot(before) { fi, fo -> segment(edit.startMs, freezeAt, fi, fo, before) }
            // the held picture: the frame as a still image for the chosen time, with the same colour, turn, crop and size as the video
            slots += Slot(edit.freezeMs) { fi, fo ->
                val image = PlayerItem.Builder().setUri(android.net.Uri.fromFile(still)).setImageDurationMs(edit.freezeMs).build()
                val picture = pictureEffects(edit.copy(speed = 1f), info) + listOfNotNull(fadePicture(fi, fo, edit.freezeMs))
                EditedMediaItem.Builder(image).setDurationUs(edit.freezeMs * 1000).setFrameRate(30).setEffects(Effects(emptyList(), picture)).build()
            }
            if (freezeAt < end) slots += Slot(after) { fi, fo -> segment(freezeAt, edit.endMs, fi, fo, after) }
        }
        val items = slots.mapIndexed { n, slot -> slot.make(if (n == 0) edit.fadeInMs else 0L, if (n == slots.lastIndex) edit.fadeOutMs else 0L) }

        val sequences = ArrayList<EditedMediaItemSequence>().apply {
            add(EditedMediaItemSequence(items.first(), *items.drop(1).toTypedArray()))
            edit.music?.let { add(musicSequence(it, edit.musicVolume, total, edit.fadeInMs, edit.fadeOutMs)) }
        }
        val hasSound = sound || edit.music != null
        val composition = builderFor(sequences, withTone = true, forceAudio = hasSound && (items.size > 1 || edit.music != null))
        val bitrate = if (edit.size != VideoSize.ORIGINAL || edit.quality != VideoQuality.HIGH) edit.targetBitrate(info) else null
        val finished = runComposition(context, composition, out, bitrate, optimizeTrim = false, onProgress)
        return withContext(Dispatchers.IO) { finished && saveVideo(context, out, item.name.substringBeforeLast('.') + "_edit.mp4", "video/mp4", item.path) }
    } finally {
        still?.delete()
        out.delete()
    }
}

/** Joins [clips] one after another (each fitted into the same frame, with bars where the shapes differ) into one new video. */
@OptIn(UnstableApi::class)
suspend fun exportJoin(
    context: Context, clips: List<MediaItem>, infos: List<VideoInfo>, heightPx: Int, fadeInMs: Long, fadeOutMs: Long,
    music: android.net.Uri?, musicVolume: Float, onProgress: (Float) -> Unit,
): Boolean {
    val out = File.createTempFile("joined", ".mp4", context.cacheDir)
    try {
        val first = infos.first()
        val h = heightPx / 2 * 2
        val w = ((h * first.width.toFloat() / first.height) / 2).toInt() * 2
        val items = clips.mapIndexed { n, clip ->
            val fadeIn = if (n == 0) fadeInMs else 0L
            val fadeOut = if (n == clips.lastIndex) fadeOutMs else 0L
            val dur = infos[n].durationMs
            val picture = listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)) + listOfNotNull(fadePicture(fadeIn, fadeOut, dur))
            EditedMediaItem.Builder(PlayerItem.fromUri(clip.uri)).setEffects(Effects(listOfNotNull(fadeSound(fadeIn, fadeOut, dur)), picture)).build()
        }
        val total = infos.sumOf { it.durationMs }
        val sequences = ArrayList<EditedMediaItemSequence>().apply {
            add(EditedMediaItemSequence(items.first(), *items.drop(1).toTypedArray()))
            music?.let { add(musicSequence(it, musicVolume, total, fadeInMs, fadeOutMs)) }
        }
        val composition = builderFor(sequences, withTone = true, forceAudio = true)
        val finished = runComposition(context, composition, out, bitrate = null, optimizeTrim = false, onProgress)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        return withContext(Dispatchers.IO) { finished && saveVideo(context, out, "Joined_$stamp.mp4", "video/mp4", clips.first().path) }
    } finally {
        out.delete()
    }
}

/** Writes [bmp] to a temporary JPG, for use as a still image in the video. */
private suspend fun saveStill(context: Context, bmp: android.graphics.Bitmap): File = withContext(Dispatchers.IO) {
    File.createTempFile("still", ".jpg", context.cacheDir).also { f -> f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) } }
}

/** Adds [file] to the phone's videos as [name] in [folder]. Returns false if it could not be written. */
fun saveVideo(context: Context, file: File, name: String, mime: String, folder: String): Boolean {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(C.DISPLAY_NAME, name)
        put(C.MIME_TYPE, mime)
        put(C.RELATIVE_PATH, folder)
        put(C.IS_PENDING, 1)
    }
    val target = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return false
    val ok = runCatching { resolver.openOutputStream(target)!!.use { out -> file.inputStream().use { it.copyTo(out) } } }.isSuccess
    if (ok) resolver.update(target, ContentValues().apply { put(C.IS_PENDING, 0) }, null, null) else resolver.delete(target, null, null)
    return ok
}
