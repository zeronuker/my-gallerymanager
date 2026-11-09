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
private fun soundProcessors(edit: VideoEdit): List<AudioProcessor> = buildList {
    if (edit.speed != 1f) add(SonicAudioProcessor().apply { setSpeed(edit.speed) })
    if (edit.volume != 1f) {
        add(ChannelMixingAudioProcessor().apply {
            // one mixing matrix per possible input layout (mono and stereo), each just scaling the loudness
            for (ch in 1..2) putChannelMixingMatrix(ChannelMixingMatrix.create(ch, ch).scaleBy(edit.volume))
        })
    }
}

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
    val finished = suspendCancellableCoroutine<Boolean> { cont ->
        val clip = PlayerItem.ClippingConfiguration.Builder()
            .setStartPositionMs(edit.startMs)
            .apply { if (edit.endMs > 0) setEndPositionMs(edit.endMs) }
            .build()
        val source = PlayerItem.Builder().setUri(item.uri).setClippingConfiguration(clip).build()
        val effects = Effects(if (edit.volume > 0f) soundProcessors(edit) else emptyList(), pictureEffects(edit, info))
        val edited = EditedMediaItem.Builder(source).setRemoveAudio(edit.volume <= 0f).setEffects(effects).build()

        val builder = Transformer.Builder(context)
        if (!edit.needsEncode) builder.experimentalSetTrimOptimizationEnabled(true) // a plain trim skips re-encoding where it can
        else if (edit.size != VideoSize.ORIGINAL || edit.quality != VideoQuality.HIGH) {
            builder.setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(edit.targetBitrate(info)).build())
                    .build(),
            )
        }
        val transformer = builder.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(true) }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { if (cont.isActive) cont.resume(false) }
        }).build()

        val composition = Composition.Builder(EditedMediaItemSequence(edited))
            // when the picture is re-encoded anyway, HDR recordings are turned into normal colours so the edit looks right everywhere;
            // a plain trim keeps the video untouched, which is what lets it skip re-encoding
            .apply { if (edit.needsEncode) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) }
            .build()
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
    return withContext(Dispatchers.IO) {
        try {
            finished && saveVideo(context, out, item.name.substringBeforeLast('.') + "_edit.mp4", "video/mp4", item.path)
        } finally {
            out.delete()
        }
    }
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
