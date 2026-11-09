package com.claudeborne.gallerymanager.video

import android.opengl.Matrix
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/** How loud or bright (0..1) a fade makes the moment [tUs] microseconds into a clip that lasts [totalUs]. */
internal fun fadeGain(tUs: Long, inUs: Long, outUs: Long, totalUs: Long): Float {
    var g = 1f
    if (inUs > 0 && tUs < inUs) g = min(g, max(0f, tUs.toFloat() / inUs))
    if (outUs > 0 && tUs > totalUs - outUs) g = min(g, max(0f, (totalUs - tUs).toFloat() / outUs))
    return g
}

/** Fades the picture from black at the start and to black at the end. */
@OptIn(UnstableApi::class)
class FadePicture(private val inUs: Long, private val outUs: Long, private val totalUs: Long) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val g = fadeGain(presentationTimeUs, inUs, outUs, totalUs)
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        m[0] = g; m[5] = g; m[10] = g // red, green and blue are scaled together; alpha stays
        return m
    }
}

/** Fades the sound in at the start and out at the end. */
@OptIn(UnstableApi::class)
class FadeSound(private val inUs: Long, private val outUs: Long, private val totalUs: Long) : BaseAudioProcessor() {
    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val ch = inputAudioFormat.channelCount
        val rate = inputAudioFormat.sampleRate
        val out = replaceOutputBuffer(inputBuffer.remaining())
        while (inputBuffer.remaining() >= 2 * ch) {
            val g = fadeGain(frames * 1_000_000L / rate, inUs, outUs, totalUs)
            repeat(ch) { out.putShort((inputBuffer.short * g).toInt().coerceIn(-32768, 32767).toShort()) }
            frames++
        }
        out.flip()
    }

    override fun onFlush() { frames = 0 }
    override fun onReset() { frames = 0 }
}
