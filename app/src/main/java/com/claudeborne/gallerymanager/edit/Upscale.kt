package com.claudeborne.gallerymanager.edit

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.ensureActive
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

// Real-ESRGAN-General-x4v3 (BSD-3-Clause), Qualcomm AI Hub float TFLite export: 128x128 RGB in (0..1), 512x512 out.
private const val MODEL = "real_esrgan_general_x4v3.tflite"
private const val TILE = 128
private const val SCALE = 4
private const val BORDER = 16 // input pixels around each tile that are thrown away, where the model sees too little context
private const val STEP = TILE - 2 * BORDER

/** Longest side of the picture fed to the model. The result is 4x that, so it stays within what fits in memory. */
const val AI_INPUT_MAX = 1536

/** The size an AI upscale of a [w] x [h] picture comes out at. */
fun aiSize(w: Int, h: Int): Pair<Int, Int> {
    val s = min(1f, AI_INPUT_MAX.toFloat() / max(w, h))
    return max(1, (w * s).toInt()) * SCALE to max(1, (h * s).toInt()) * SCALE
}

/**
 * Enlarges [src] four times with the Real-ESRGAN model, which redraws fine detail rather than just stretching pixels.
 * It works tile by tile ([onProgress] gets 0..1 after each one) and stops promptly when the calling coroutine is cancelled.
 */
suspend fun upscale4x(context: Context, src: Bitmap, onProgress: (Float) -> Unit = {}): Bitmap {
    val bytes = context.assets.open(MODEL).use { it.readBytes() }
    val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
    val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(max(2, Runtime.getRuntime().availableProcessors() / 2)))
    try {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val out = Bitmap.createBitmap(w * SCALE, h * SCALE, Bitmap.Config.ARGB_8888)

        val input = ByteBuffer.allocateDirect(TILE * TILE * 3 * 4).order(ByteOrder.nativeOrder())
        val output = ByteBuffer.allocateDirect(TILE * SCALE * TILE * SCALE * 3 * 4).order(ByteOrder.nativeOrder())
        val result = IntArray(STEP * SCALE * STEP * SCALE)

        val cols = (w + STEP - 1) / STEP
        val rows = (h + STEP - 1) / STEP
        for (r in 0 until rows) for (c in 0 until cols) {
            coroutineContext.ensureActive()
            val bx = c * STEP // the part of the picture this tile is responsible for
            val by = r * STEP
            input.rewind()
            for (y in 0 until TILE) for (x in 0 until TILE) {
                // beyond the edge of the picture the nearest edge pixel is repeated
                val p = px[(by - BORDER + y).coerceIn(0, h - 1) * w + (bx - BORDER + x).coerceIn(0, w - 1)]
                input.putFloat(((p shr 16) and 255) / 255f)
                input.putFloat(((p shr 8) and 255) / 255f)
                input.putFloat((p and 255) / 255f)
            }
            output.rewind()
            interpreter.run(input, output)

            val cw = min(STEP, w - bx) * SCALE // how much of the tile's result is kept
            val ch = min(STEP, h - by) * SCALE
            output.rewind()
            val floats = output.asFloatBuffer()
            for (y in 0 until ch) {
                val row = ((BORDER * SCALE + y) * TILE * SCALE + BORDER * SCALE) * 3
                for (x in 0 until cw) {
                    val i = row + x * 3
                    val rr = (floats.get(i).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    val gg = (floats.get(i + 1).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    val bb = (floats.get(i + 2).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    result[y * cw + x] = (255 shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
            out.setPixels(result, 0, cw, bx * SCALE, by * SCALE, cw, ch)
            onProgress((r * cols + c + 1f) / (rows * cols))
        }
        return out
    } finally {
        interpreter.close()
    }
}
