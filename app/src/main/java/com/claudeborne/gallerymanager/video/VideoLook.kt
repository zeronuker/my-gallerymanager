package com.claudeborne.gallerymanager.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.RgbAdjustment
import com.claudeborne.gallerymanager.edit.Crop
import com.claudeborne.gallerymanager.edit.Filters
import com.google.common.collect.ImmutableList
import kotlin.math.max
import kotlin.math.min

/** The colour numbers a [VideoEdit] adds up to once its filter and its sliders are combined. */
class Look(val brightness: Float, val contrast: Float, val saturation: Float, val red: Float, val green: Float, val blue: Float) {
    val isNeutral get() = brightness == 0f && contrast == 0f && saturation == 0f && red == 1f && green == 1f && blue == 1f
}

/** Brightness and contrast are -1..1 (0 = unchanged), saturation is a multiplier minus one, red/green/blue are channel multipliers. */
fun VideoEdit.look(): Look {
    val f = Filters[filter]
    val a = if (filter == 0) 0f else filterAmount
    fun mix(one: Float, to: Float) = one + (to - one) * a
    val fContrast = mix(1f, f.contrast)
    val fSat = mix(1f, f.sat)
    // a faded look lifts the blacks, which on a video is closest to a little extra brightness and less contrast
    val fBright = f.brightness * a + f.fade * a * 0.5f
    val tint = FloatArray(3) { mix(1f, f.tint[it]) }
    return Look(
        brightness = (brightness * 0.4f + fBright).coerceIn(-1f, 1f),
        contrast = ((1f + contrast) * fContrast * (1f - f.fade * a * 0.4f) - 1f).coerceIn(-1f, 1f),
        saturation = ((1f + saturation) * fSat - 1f).coerceIn(-1f, 1f),
        red = tint[0] * (1f + warmth * 0.12f),
        green = tint[1],
        blue = tint[2] * (1f - warmth * 0.12f),
    )
}

/** The colour effects for [edit]. Empty when nothing was changed. */
@OptIn(UnstableApi::class)
fun lookEffects(edit: VideoEdit): List<Effect> {
    val l = edit.look()
    if (l.isNeutral) return emptyList()
    return buildList {
        if (l.brightness != 0f) add(Brightness(l.brightness))
        if (l.contrast != 0f) add(Contrast(l.contrast))
        if (l.saturation != 0f) add(HslAdjustment.Builder().adjustSaturation(l.saturation * 100f).build())
        if (l.red != 1f || l.green != 1f || l.blue != 1f) {
            add(RgbAdjustment.Builder().setRedScale(l.red).setGreenScale(l.green).setBlueScale(l.blue).build())
        }
    }
}

/** The same look as a colour matrix, so the preview can show it on screen. Null when nothing was changed. */
fun VideoEdit.previewMatrix(): ColorMatrix? {
    val l = look()
    if (l.isNeutral) return null
    val m = ColorMatrix().apply { setSaturation(1f + l.saturation) }
    val c = 1f + l.contrast
    val shift = (0.5f - 0.5f * c + l.brightness) * 255f
    m.postConcat(ColorMatrix(floatArrayOf(
        c * l.red, 0f, 0f, 0f, shift,
        0f, c * l.green, 0f, 0f, shift,
        0f, 0f, c * l.blue, 0f, shift,
        0f, 0f, 0f, 1f, 0f,
    )))
    return m
}

/** Size of the finished picture in pixels, after turning, cropping and shrinking. */
fun VideoEdit.outputSize(info: VideoInfo): Pair<Int, Int> {
    val turned = turns % 2 == 1
    var w = (if (turned) info.height else info.width).toFloat()
    var h = (if (turned) info.width else info.height).toFloat()
    if (aspect.ratio > 0f) { if (w / h > aspect.ratio) w = h * aspect.ratio else h = w / aspect.ratio }
    if (size.height > 0 && size.height < h) { w = w * size.height / h; h = size.height.toFloat() }
    return max(2, w.toInt() / 2 * 2) to max(2, h.toInt() / 2 * 2) // encoders want even sizes
}

/** Draws the text, stickers and pen strokes over the whole picture, as one see-through overlay. Null when there are none. */
@OptIn(UnstableApi::class)
fun overlayEffect(edit: VideoEdit, info: VideoInfo): Effect? {
    if (edit.marks.isEmpty()) return null
    val (w, h) = edit.outputSize(info)
    val s = min(1f, 1920f / max(w, h)) // the overlay is stretched over the picture, so a sharp 1920 px version is plenty
    val bw = max(2, (w * s).toInt())
    val bh = max(2, (h * s).toInt())
    val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    com.claudeborne.gallerymanager.edit.drawMarks(Canvas(bmp), edit.marks, Crop(), bw, bh)
    return OverlayEffect(ImmutableList.of(BitmapOverlay.createStaticBitmapOverlay(bmp)))
}
