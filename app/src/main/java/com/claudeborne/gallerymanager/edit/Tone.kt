package com.claudeborne.gallerymanager.edit

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

private val IDENTITY = listOf(Pt(0f, 0f), Pt(1f, 1f))

/** Tone curves: one for overall brightness and one per colour channel. Points run from (0,0) to (1,1); u is the input, v the output. */
data class Curves(
    val master: List<Pt> = IDENTITY,
    val r: List<Pt> = IDENTITY,
    val g: List<Pt> = IDENTITY,
    val b: List<Pt> = IDENTITY,
) {
    val isIdentity get() = this == Curves()
    fun channel(i: Int) = when (i) { 0 -> master; 1 -> r; 2 -> g; else -> b }
    fun with(i: Int, pts: List<Pt>) = when (i) { 0 -> copy(master = pts); 1 -> copy(r = pts); 2 -> copy(g = pts); else -> copy(b = pts) }
}

/** The colour bands of the per-colour tuning, with their hue (0..1 around the colour wheel). */
enum class Band(val label: String, val hue: Float) {
    RED("RED", 0f), ORANGE("ORANGE", 0.083f), YELLOW("YELLOW", 0.167f), GREEN("GREEN", 0.333f),
    AQUA("AQUA", 0.5f), BLUE("BLUE", 0.667f), PURPLE("PURPLE", 0.764f), MAGENTA("MAGENTA", 0.889f)
}

/** Which of a band's three sliders: hue shift, saturation, brightness. */
enum class BandAxis(val label: String) { HUE("HUE"), SAT("SATURATION"), LUM("BRIGHTNESS") }

/** The 24 per-colour values, three per band, all 0 (untouched). */
val NoColourTuning: List<Float> = List(Band.entries.size * 3) { 0f }

fun List<Float>.tuned(band: Band, axis: BandAxis) = this[band.ordinal * 3 + axis.ordinal]
fun List<Float>.tune(band: Band, axis: BandAxis, v: Float) = toMutableList().also { it[band.ordinal * 3 + axis.ordinal] = v }

/** Smooth curve through [pts] (monotone cubic, so it never overshoots between points), as 256 outputs for inputs 0..255. */
private fun curveValues(pts: List<Pt>): FloatArray {
    val p = pts.sortedBy { it.u }
    val n = p.size
    val h = FloatArray(n - 1) { max(p[it + 1].u - p[it].u, 1e-4f) }
    val d = FloatArray(n - 1) { (p[it + 1].v - p[it].v) / h[it] }
    val m = FloatArray(n)
    m[0] = d[0]
    m[n - 1] = d[n - 2]
    for (k in 1 until n - 1) {
        m[k] = if (d[k - 1] * d[k] <= 0f) 0f else {
            val w1 = 2 * h[k] + h[k - 1]
            val w2 = h[k] + 2 * h[k - 1]
            (w1 + w2) / (w1 / d[k - 1] + w2 / d[k])
        }
    }
    return FloatArray(256) { i ->
        val x = i / 255f
        when {
            x <= p[0].u -> p[0].v
            x >= p[n - 1].u -> p[n - 1].v
            else -> {
                var k = 0
                while (x > p[k + 1].u) k++
                val t = (x - p[k].u) / h[k]
                val t2 = t * t
                val t3 = t2 * t
                (2 * t3 - 3 * t2 + 1) * p[k].v + (t3 - 2 * t2 + t) * h[k] * m[k] + (-2 * t3 + 3 * t2) * p[k + 1].v + (t3 - t2) * h[k] * m[k + 1]
            }
        }.coerceIn(0f, 1f)
    }
}

/** A 256 x 1 picture the shader reads: pixel i holds the red, green and blue results for an input of i. */
fun curveLut(c: Curves): Bitmap {
    val master = curveValues(c.master)
    val r = curveValues(c.r)
    val g = curveValues(c.g)
    val b = curveValues(c.b)
    fun at(a: FloatArray, v: Float) = a[(v * 255f + 0.5f).toInt().coerceIn(0, 255)]
    val px = IntArray(256) { i ->
        val v = master[i]
        val rr = (at(r, v) * 255f + 0.5f).toInt()
        val gg = (at(g, v) * 255f + 0.5f).toInt()
        val bb = (at(b, v) * 255f + 0.5f).toInt()
        (255 shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
    return Bitmap.createBitmap(px, 256, 1, Bitmap.Config.ARGB_8888)
}

/** Where [pts] sits for an input of [x] (0..1): used to draw the curve in the editor. */
fun curveAt(pts: List<Pt>, x: Float): Float = curveValues(pts)[(x.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)]

/** Levels need the white point above the black point. */
fun levelsOn(black: Float, white: Float, gamma: Float) = black > 0.001f || white < 0.999f || min(gamma, 1f / gamma) < 0.999f
