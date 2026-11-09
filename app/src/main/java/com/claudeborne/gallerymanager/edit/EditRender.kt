package com.claudeborne.gallerymanager.edit

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// One fragment shader does every pixel edit, so the live preview and the saved full-size file look identical.
// Order: filter look, manual tone and colour, sharpness/clarity from neighbouring pixels, vignette, grain.
private const val AGSL = """
uniform shader image;
uniform float unit;
uniform float2 vigCenter;
uniform float vigRadius;
uniform float exposure; uniform float brightness; uniform float contrast;
uniform float highlights; uniform float shadows; uniform float whites; uniform float blacks;
uniform float saturation; uniform float vibrance; uniform float warmth; uniform float tint;
uniform float sharpness; uniform float clarity; uniform float vignette; uniform float grain;
uniform float fSat; uniform float fContrast; uniform float fBright; uniform float fFade; uniform float fGlow; uniform float fAmt;
uniform float3 fTint; uniform float3 fLift;

float lum(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }
float3 px(float2 p) { return float3(image.eval(p).rgb); }

half4 main(float2 coord) {
    half4 src = image.eval(coord);
    float3 base = float3(src.rgb);
    float3 c = base;

    float3 near = (px(coord + float2(unit, 0)) + px(coord - float2(unit, 0)) + px(coord + float2(0, unit)) + px(coord - float2(0, unit))) * 0.25;
    float u = unit * 5.0;
    float d = u * 0.7071;
    float3 far = (px(coord + float2(u, 0)) + px(coord - float2(u, 0)) + px(coord + float2(0, u)) + px(coord - float2(0, u))
                + px(coord + float2(d, d)) + px(coord - float2(d, d)) + px(coord + float2(d, -d)) + px(coord - float2(d, -d))) * 0.125;

    float3 f = c;
    f = mix(float3(lum(f)), f, fSat);
    f = (f - 0.5) * fContrast + 0.5 + fBright;
    f = f * fTint + fLift;
    f = fFade + f * (1.0 - fFade);
    f = mix(f, 1.0 - (1.0 - f) * (1.0 - far), fGlow * 0.35);
    c = mix(c, f, fAmt);

    c *= exp2(exposure * 1.5);
    c += brightness * 0.25;
    c = (c - 0.5) * (1.0 + contrast * 0.8) + 0.5;
    float l = clamp(lum(c), 0.0, 1.0);
    c += shadows * 0.3 * (1.0 - smoothstep(0.0, 0.6, l));
    c += highlights * 0.3 * smoothstep(0.4, 1.0, l);
    c += whites * 0.2 * smoothstep(0.6, 1.0, l);
    c += blacks * 0.15 * (1.0 - smoothstep(0.0, 0.35, l));
    c.r += warmth * 0.12; c.b -= warmth * 0.12;
    c.g -= tint * 0.08; c.r += tint * 0.04; c.b += tint * 0.04;
    l = lum(c);
    c = mix(float3(l), c, 1.0 + saturation);
    float spread = clamp(max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)), 0.0, 1.0);
    c = mix(float3(l), c, 1.0 + vibrance * (1.0 - spread));

    c += (base - near) * sharpness * 2.0 + (base - far) * clarity * 1.2;

    float v = smoothstep(0.35, 1.05, length(coord - vigCenter) / vigRadius);
    c = vignette >= 0.0 ? c * (1.0 - vignette * 0.8 * v) : mix(c, float3(1.0), -vignette * 0.7 * v);

    float n = fract(sin(dot(floor(coord / unit * 0.5), float2(12.9898, 78.233))) * 43758.5453) - 0.5;
    c += n * grain * 0.3;

    return half4(half3(clamp(c, 0.0, 1.0)), src.a);
}
"""

fun frameSize(w: Int, h: Int, turns: Int) = if (turns % 2 == 0) w.toFloat() to h.toFloat() else h.toFloat() to w.toFloat()

/** Zoom that keeps a photo rotated by [angle] degrees covering its whole frame, so straightening never shows empty corners. */
private fun straightenScale(cw: Float, ch: Float, angle: Float): Float {
    val a = Math.toRadians(abs(angle).toDouble())
    return (cos(a) + sin(a) * max(cw / ch, ch / cw)).toFloat()
}

/** Maps source pixels to an [outW] x [outH] picture of [crop]: turn, flip, straighten, then crop and scale. */
fun editMatrix(srcW: Int, srcH: Int, st: EditState, crop: Crop, outW: Int, outH: Int): Matrix {
    val (cw, ch) = frameSize(srcW, srcH, st.turns)
    val s = straightenScale(cw, ch, st.angle)
    return Matrix().apply {
        postTranslate(-srcW / 2f, -srcH / 2f)
        postRotate(st.turns * 90f)
        postScale(if (st.flipH) -1f else 1f, if (st.flipV) -1f else 1f)
        postRotate(st.angle)
        postScale(s, s)
        postTranslate(cw / 2f, ch / 2f)
        postTranslate(-crop.l * cw, -crop.t * ch)
        postScale(outW / (crop.w * cw), outH / (crop.h * ch))
    }
}

/** Draws edited pictures. Reuse one per screen: compiling the shader is the costly part. */
class EditShader {
    private val shader = RuntimeShader(AGSL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bound: Bitmap? = null

    /** Draws [src] with [st] into the top-left [outW] x [outH] of [canvas], showing only [crop]. */
    fun draw(canvas: Canvas, src: Bitmap, st: EditState, crop: Crop, outW: Int, outH: Int) {
        if (bound !== src) {
            shader.setInputShader("image", BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setFilterMode(BitmapShader.FILTER_MODE_LINEAR) })
            bound = src
        }
        val m = editMatrix(src.width, src.height, st, crop, outW, outH)
        val inv = Matrix().also { m.invert(it) }
        val centre = floatArrayOf(outW / 2f, outH / 2f).also { inv.mapPoints(it) }
        val v = FloatArray(9).also { inv.getValues(it) }
        val toSource = hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y]) // output pixels to source pixels, for the vignette

        shader.setFloatUniform("unit", max(src.width, src.height) / 1500f) // same look at preview and full size
        shader.setFloatUniform("vigCenter", centre[0], centre[1])
        shader.setFloatUniform("vigRadius", hypot(outW.toFloat(), outH.toFloat()) / 2f * toSource)
        Adjust.entries.forEach { shader.setFloatUniform(it.name.lowercase(), st.value(it)) }
        val f = Filters[st.filter]
        shader.setFloatUniform("fSat", f.sat)
        shader.setFloatUniform("fContrast", f.contrast)
        shader.setFloatUniform("fBright", f.brightness)
        shader.setFloatUniform("fFade", f.fade)
        shader.setFloatUniform("fGlow", f.glow)
        shader.setFloatUniform("fAmt", if (st.filter == 0) 0f else st.filterAmount)
        shader.setFloatUniform("fTint", f.tint)
        shader.setFloatUniform("fLift", f.lift)

        paint.setShader(shader) // a Paint only picks up uniform changes when the shader is set again
        canvas.save()
        canvas.clipRect(0f, 0f, outW.toFloat(), outH.toFloat())
        canvas.concat(m)
        canvas.drawRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), paint)
        canvas.restore()
    }
}

/** Renders [src] with [st] at full resolution, cropped, off screen on the GPU. */
fun renderEdited(src: Bitmap, st: EditState): Bitmap {
    val (cw, ch) = frameSize(src.width, src.height, st.turns)
    val w = (st.crop.w * cw).toInt().coerceAtLeast(1)
    val h = (st.crop.h * ch).toInt().coerceAtLeast(1)
    val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
    val node = RenderNode("edit").apply { setPosition(0, 0, w, h) }
    val canvas = node.beginRecording()
    EditShader().draw(canvas, src, st, st.crop, w, h)
    node.endRecording()
    val renderer = HardwareRenderer().apply { setSurface(reader.surface); setContentRoot(node) }
    renderer.createRenderRequest().syncAndDraw()
    // the GPU finishes asynchronously, so wait for the frame to arrive
    var image = reader.acquireNextImage()
    var tries = 0
    while (image == null && tries++ < 300) { Thread.sleep(10); image = reader.acquireNextImage() }
    checkNotNull(image) { "GPU render timed out" }
    val out = Bitmap.wrapHardwareBuffer(image.hardwareBuffer!!, null)!!.copy(Bitmap.Config.ARGB_8888, false)
    image.close(); renderer.destroy(); reader.close(); node.discardDisplayList()
    return out
}

/** Picks Adjust values from the picture's brightness spread: lift dark photos, widen flat ones, add a little colour. */
fun autoEnhance(bmp: Bitmap): Map<Adjust, Float> {
    val small = Bitmap.createScaledBitmap(bmp, 64, 64, true)
    val px = IntArray(64 * 64).also { small.getPixels(it, 0, 64, 0, 0, 64, 64) }
    val l = FloatArray(px.size) { (0.2126f * Color.red(px[it]) + 0.7152f * Color.green(px[it]) + 0.0722f * Color.blue(px[it])) / 255f }
    l.sort()
    val lo = l[(l.size * 0.01f).toInt()]
    val hi = l[(l.size * 0.99f).toInt()]
    val mean = l.average().toFloat()
    return mapOf(
        Adjust.EXPOSURE to ((0.45f - mean) * 1.2f).coerceIn(-0.5f, 0.5f),
        Adjust.CONTRAST to ((0.85f - (hi - lo)) * 0.6f).coerceIn(0f, 0.4f),
        Adjust.BLACKS to -min(lo * 2f, 0.4f),
        Adjust.WHITES to ((0.97f - hi) * 2f).coerceIn(0f, 0.4f),
        Adjust.SHADOWS to if (mean < 0.4f) 0.2f else 0.05f,
        Adjust.HIGHLIGHTS to -0.1f,
        Adjust.VIBRANCE to 0.15f,
        Adjust.SATURATION to 0.08f,
    ).filterValues { it != 0f }
}
