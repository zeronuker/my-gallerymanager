package com.claudeborne.gallerymanager.edit

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Chooses where a Heal stroke over [pts] (brush [width], as a fraction of the frame width) should copy from. It tries 24 nearby spots
 * (8 directions, 3 distances) and keeps the one whose surroundings look most like the surroundings of the stroke and whose own
 * texture is smooth, so a blemish is not replaced with another one. Returns the offset in frame fractions.
 */
fun findHealOffset(bmp: Bitmap, st: EditState, pts: List<Pt>, width: Float): Pt {
    val (cw, ch) = frameSize(bmp.width, bmp.height, st.turns)
    val inv = Matrix().also { editMatrix(bmp.width, bmp.height, st, Crop(), cw.roundToInt(), ch.roundToInt()).invert(it) } // frame px -> source px
    val radius = width * cw / 2f

    fun rgbAt(x: Float, y: Float): Int? {
        if (x < 0 || y < 0 || x > cw || y > ch) return null
        val p = floatArrayOf(x, y).also { inv.mapPoints(it) }
        val sx = p[0].toInt()
        val sy = p[1].toInt()
        return if (sx in 0 until bmp.width && sy in 0 until bmp.height) bmp.getPixel(sx, sy) else null
    }
    fun diff(a: Int, b: Int) = (abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))) / 3f

    // a few points along the stroke, and a ring of samples around each
    val every = max(1, pts.size / 12)
    val centres = pts.filterIndexed { i, _ -> i % every == 0 }.take(12)
    val ring = centres.flatMap { c -> (0 until 6).map { a -> floatArrayOf(c.u * cw + cos(a * Math.PI / 3).toFloat() * radius * 1.5f, c.v * ch + sin(a * Math.PI / 3).toFloat() * radius * 1.5f) } }

    // far enough to clear the whole stroke
    val extent = max((pts.maxOf { it.u } - pts.minOf { it.u }) * cw, (pts.maxOf { it.v } - pts.minOf { it.v }) * ch) + 2 * radius
    val reach = max(extent, radius * 2) * 1.3f

    var best = Pt(0.05f, 0f)
    var bestCost = Float.MAX_VALUE
    for (factor in listOf(1f, 1.6f, 2.4f)) for (k in 0 until 8) {
        val dx = cos(k * Math.PI / 4).toFloat() * reach * factor
        val dy = sin(k * Math.PI / 4).toFloat() * reach * factor
        var total = 0f
        var n = 0
        var outside = 0
        for (p in ring) {
            val a = rgbAt(p[0], p[1]) ?: continue
            val b = rgbAt(p[0] + dx, p[1] + dy)
            if (b == null) { outside++; continue }
            total += diff(a, b); n++
        }
        if (n == 0 || outside > ring.size / 4) continue
        // the copied area itself should be smooth: little variation across the brush
        val c = centres[centres.size / 2]
        val spots = listOf(0f to 0f, radius * 0.6f to 0f, -radius * 0.6f to 0f, 0f to radius * 0.6f, 0f to -radius * 0.6f)
            .mapNotNull { (ox, oy) -> rgbAt(c.u * cw + dx + ox, c.v * ch + dy + oy) }
        if (spots.size < 5) continue
        val mid = spots[0]
        val texture = spots.sumOf { diff(it, mid).toDouble() }.toFloat() / spots.size
        val cost = total / n + texture * 0.5f
        if (cost < bestCost) { bestCost = cost; best = Pt(dx / cw, dy / ch) }
    }
    return best
}
