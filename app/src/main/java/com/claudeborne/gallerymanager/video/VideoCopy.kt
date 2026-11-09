package com.claudeborne.gallerymanager.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Cuts [uri] to [startMs]..[endMs] (0 = to the end) by copying the stored data as it is, with no decoding or re-encoding, so it is almost
 * instant and loses no quality. The cut can only begin on a key frame, so the start moves back to the nearest one before [startMs].
 * With [keepSound] false the sound is left out. Returns false when the file cannot be copied this way (the caller then re-encodes).
 */
suspend fun copyCut(context: Context, uri: Uri, startMs: Long, endMs: Long, keepSound: Boolean, out: File, onProgress: (Float) -> Unit): Boolean =
    withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, uri, null)
            val rotation = MediaMetadataRetriever().use { r ->
                r.setDataSource(context, uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            }
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(rotation) }

            // keep the video, and the sound unless it is being dropped; other tracks (timecodes, subtitles) are skipped
            val map = HashMap<Int, Int>() // track in the source -> track in the copy
            var maxSize = 1 shl 20
            for (t in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(t)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || (keepSound && mime.startsWith("audio/"))) {
                    extractor.selectTrack(t)
                    map[t] = muxer.addTrack(f)
                    if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxSize = maxOf(maxSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }
            if (map.isEmpty()) return@withContext false

            val total = MediaMetadataRetriever().use { r ->
                r.setDataSource(context, uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
            val startUs = startMs * 1000L
            val endUs = if (endMs > 0) endMs * 1000L else Long.MAX_VALUE
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val baseUs = maxOf(0L, extractor.sampleTime)

            muxer.start()
            val buffer = ByteBuffer.allocateDirect(maxSize)
            val info = android.media.MediaCodec.BufferInfo()
            var wrote = 0
            while (true) {
                coroutineContext.ensureActive()
                val size = extractor.readSampleData(buffer, 0)
                val time = extractor.sampleTime
                if (size < 0 || time < 0 || time > endUs) break
                val track = map[extractor.sampleTrackIndex]
                if (track != null) {
                    info.set(0, size, maxOf(0L, time - baseUs), extractor.sampleFlags)
                    muxer.writeSampleData(track, buffer, info)
                    wrote++
                }
                extractor.advance()
                if (total > 0 && wrote % 30 == 0) onProgress(((time - startUs) / 1000f / ((if (endMs > 0) endMs else total) - startMs).coerceAtLeast(1)).coerceIn(0f, 1f))
            }
            muxer.stop()
            muxer.release()
            muxer = null
            wrote > 0
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("VideoCopy", "direct copy failed, will re-encode instead", e)
            false
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }
