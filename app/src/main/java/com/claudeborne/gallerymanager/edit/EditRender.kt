package com.claudeborne.gallerymanager.edit

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.HardwareBuffer
import android.media.ImageReader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// One fragment shader does every pixel edit, so the live preview and the saved full-size file look identical.
// Order: filter look, manual tone and colour, sharpness/clarity from neighbouring pixels, vignette, grain.
private const val AGSL = """
uniform shader image;
uniform shader lutImg; // tone curves, one pixel per input level
uniform float lutOn; uniform float lvOn; uniform float lvBlack; uniform float lvWhite; uniform float lvGamma;
uniform float hslOn; uniform float hslC[8]; uniform float hslH[8]; uniform float hslS[8]; uniform float hslL[8];
uniform shader faceMask; // red = skin, green = open mouth, blue = eyes
uniform float faceSkin; uniform float faceTeeth; uniform float faceEyes;
uniform shader maskImg; // how much of each pixel belongs to the subject (alpha)
uniform float bgMode; uniform float bgBlurR; uniform float3 bgCol; // 0 off, 1 blur, 2 colour, 3 transparent
uniform float unit;
uniform float2 vigCenter;
uniform float vigRadius;
uniform float exposure; uniform float brightness; uniform float contrast;
uniform float highlights; uniform float shadows; uniform float whites; uniform float blacks;
uniform float saturation; uniform float vibrance; uniform float warmth; uniform float tint;
uniform float sharpness; uniform float clarity; uniform float vignette; uniform float grain;
uniform float fSat; uniform float fContrast; uniform float fBright; uniform float fFade; uniform float fGlow; uniform float fAmt;
uniform float3 fTint; uniform float3 fLift;
uniform float snap; uniform float blurR; // mosaic cell size and blur radius for hidden regions; 0 = off
uniform float2 srcOff; uniform float heal; uniform float healR; // retouch patches: where to read from, and whether to match the colour around the spot

float lum(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }
float3 rgb2hsv(float3 c) {
    float4 K = float4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    float4 p = mix(float4(c.bg, K.wz), float4(c.gb, K.xy), step(c.b, c.g));
    float4 q = mix(float4(p.xyw, c.r), float4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    return float3(abs(q.z + (q.w - q.y) / (6.0 * d + 1.0e-10)), d / (q.x + 1.0e-10), q.x);
}
float3 hsv2rgb(float3 c) {
    float4 K = float4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    float3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}
float3 px(float2 p) { return float3(image.eval(p).rgb); }
float3 ring(float2 p, float r) {
    float q = r * 0.7071;
    return (px(p) + px(p + float2(r, 0)) + px(p - float2(r, 0)) + px(p + float2(0, r)) + px(p - float2(0, r))
          + px(p + float2(q, q)) + px(p - float2(q, q)) + px(p + float2(q, -q)) + px(p - float2(q, -q))) / 9.0;
}

half4 main(float2 coord) {
    float2 here = coord; // where this pixel really is; a retouch patch reads from coord + srcOff instead
    coord += srcOff;
    if (snap > 0.0) coord = (floor(coord / snap) + 0.5) * snap;
    half4 src = image.eval(coord);
    if (blurR > 0.0) {
        float r = blurR;
        float q = r * 0.7071;
        float h = r * 0.5;
        float g = h * 0.7071;
        float3 sum = float3(src.rgb)
            + px(coord + float2(r, 0)) + px(coord - float2(r, 0)) + px(coord + float2(0, r)) + px(coord - float2(0, r))
            + px(coord + float2(q, q)) + px(coord - float2(q, q)) + px(coord + float2(q, -q)) + px(coord - float2(q, -q))
            + px(coord + float2(h, 0)) + px(coord - float2(h, 0)) + px(coord + float2(0, h)) + px(coord - float2(0, h))
            + px(coord + float2(g, g)) + px(coord - float2(g, g)) + px(coord + float2(g, -g)) + px(coord - float2(g, -g));
        src = half4(half3(sum / 17.0), src.a);
    }
    float m = 1.0;
    if (bgMode > 0.5) {
        m = smoothstep(0.25, 0.75, maskImg.eval(here).a);
        if (bgMode < 1.5 && m < 0.999) {
            // three rings of taps give a soft blur of everything behind the subject
            // each pixel turns its taps by a random angle, so the gaps between taps become fine grain instead of ghost copies
            float spin = fract(sin(dot(floor(here / unit), float2(12.9898, 78.233))) * 43758.5453) * 6.2832;
            float3 acc = float3(src.rgb);
            for (int k = 1; k <= 4; k++) {
                float rr = bgBlurR * float(k) / 4.0;
                for (int j = 0; j < 8; j++) {
                    float a = float(j) * 0.7854 + spin + float(k) * 0.6;
                    acc += px(coord + float2(cos(a), sin(a)) * rr);
                }
            }
            src = half4(half3(mix(acc / 33.0, float3(src.rgb), m)), src.a);
        }
    }
    float3 base = float3(src.rgb);
    if (faceSkin + faceTeeth + faceEyes > 0.001) {
        float3 fm = faceMask.eval(here).rgb;
        if (faceSkin > 0.001 && fm.r > 0.01) {
            // smooth skin: average only neighbours that look like this pixel, so freckles soften but edges and features stay sharp
            float spin2 = fract(sin(dot(floor(here / unit), float2(39.346, 11.135))) * 43758.5453) * 6.2832;
            float rs = unit * (3.0 + 7.0 * faceSkin);
            float3 acc = base;
            float wsum = 1.0;
            for (int k = 1; k <= 3; k++) {
                for (int j = 0; j < 8; j++) {
                    float a2 = float(j) * 0.7854 + spin2 + float(k) * 0.5;
                    float3 d = px(coord + float2(cos(a2), sin(a2)) * rs * float(k) / 3.0);
                    float3 df = d - base;
                    float wt = exp(-dot(df, df) * 30.0);
                    acc += d * wt;
                    wsum += wt;
                }
            }
            float3 soft = acc / wsum;
            float amt = fm.r * min(1.0, faceSkin * 1.2);
            base = mix(base, soft, amt) + (base - soft) * 0.18 * fm.r; // a little texture stays so it does not look plastic
        }
        if (faceTeeth > 0.001 && fm.g > 0.01) {
            float lt = lum(base);
            float on = faceTeeth * fm.g * smoothstep(0.18, 0.4, lt); // not the dark tongue or gums
            // pull the yellow out (towards grey) and lift the brightness
            float3 white = mix(base, float3(lt), 0.95 * on);
            base = white * (1.0 + 0.3 * on) + 0.04 * on;
        }
        if (faceEyes > 0.001 && fm.b > 0.01) {
            // brighten the whites of the eyes only: skin of the lids is pinkish (colourful) and the iris is dark, both are left alone
            float le = lum(base);
            float sat2 = max(base.r, max(base.g, base.b)) - min(base.r, min(base.g, base.b));
            float on = faceEyes * fm.b * smoothstep(0.3, 0.6, le) * (1.0 - smoothstep(0.1, 0.3, sat2));
            base = base * exp2(0.45 * on) + 0.015 * on;
        }
    }
    if (heal > 0.5) base += ring(here, healR) - ring(coord, healR); // carry over the colour of the area around the spot
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

    if (hslOn > 0.5) {
        // per-colour tuning: each colour band pulls on the pixels whose hue is near it, and only on coloured (not grey) pixels
        float3 hsv = rgb2hsv(clamp(c, 0.0, 1.0));
        float gate = smoothstep(0.03, 0.2, hsv.y);
        float dh = 0.0; float ds = 0.0; float dl = 0.0;
        for (int k = 0; k < 8; k++) {
            float d = abs(hsv.x - hslC[k]);
            d = min(d, 1.0 - d);
            float w = (1.0 - smoothstep(0.0, 0.09, d)) * gate;
            dh += w * hslH[k]; ds += w * hslS[k]; dl += w * hslL[k];
        }
        hsv.x = fract(hsv.x + dh * 0.08 + 1.0);
        hsv.y = clamp(hsv.y * (1.0 + ds), 0.0, 1.0);
        hsv.z = clamp(hsv.z * (1.0 + dl * 0.5), 0.0, 1.0);
        c = hsv2rgb(hsv);
    }
    if (lvOn > 0.5) {
        c = (c - lvBlack) / max(lvWhite - lvBlack, 0.01);
        c = pow(max(c, float3(0.0)), float3(1.0 / lvGamma));
    }
    if (lutOn > 0.5) {
        c = clamp(c, 0.0, 1.0);
        c = float3(lutImg.eval(float2(c.r * 255.0 + 0.5, 0.5)).r, lutImg.eval(float2(c.g * 255.0 + 0.5, 0.5)).g, lutImg.eval(float2(c.b * 255.0 + 0.5, 0.5)).b);
    }

    c += (base - near) * sharpness * 2.0 + (base - far) * clarity * 1.2;

    float v = smoothstep(0.35, 1.05, length(here - vigCenter) / vigRadius);
    c = vignette >= 0.0 ? c * (1.0 - vignette * 0.8 * v) : mix(c, float3(1.0), -vignette * 0.7 * v);

    float n = fract(sin(dot(floor(here / unit * 0.5), float2(12.9898, 78.233))) * 43758.5453) - 0.5;
    c += n * grain * 0.3;

    if (bgMode > 1.5 && bgMode < 2.5) c = mix(bgCol, c, m);
    float outA = bgMode > 2.5 ? m * src.a : src.a;
    return half4(half3(clamp(c, 0.0, 1.0)) * outA, outA); // premultiplied
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
    private var boundMask: Bitmap? = null
    private var boundFaces: Bitmap? = null
    private var lutFor: Curves? = null
    private var lutBmp: Bitmap? = null
    private val dummyMask = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    /** Draws [src] with [st] into the top-left [outW] x [outH] of [canvas], showing only [crop]. */
    fun draw(canvas: Canvas, src: Bitmap, st: EditState, crop: Crop, outW: Int, outH: Int, mask: Bitmap? = null, faces: Bitmap? = null) {
        val cutout = mask != null && st.backdrop != Backdrop.KEEP
        val maskBmp = mask ?: dummyMask
        if (boundMask !== maskBmp || bound !== src) {
            // the mask is stretched over the picture whatever its own size
            val ms = BitmapShader(maskBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                setLocalMatrix(Matrix().apply { setScale(src.width.toFloat() / maskBmp.width, src.height.toFloat() / maskBmp.height) })
            }
            shader.setInputShader("maskImg", ms)
            boundMask = maskBmp
        }
        // the curve table is rebuilt only when the curves change
        if (!st.curves.isIdentity && st.curves != lutFor) {
            lutFor = st.curves
            lutBmp = curveLut(st.curves)
            shader.setInputShader("lutImg", BitmapShader(lutBmp!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        } else if (st.curves.isIdentity && lutFor != null) {
            lutFor = null
        }
        if (lutBmp == null) { // an input shader must always be set, even when unused
            lutBmp = dummyMask
            shader.setInputShader("lutImg", BitmapShader(dummyMask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        }
        val faceBmp = faces ?: dummyMask
        if (boundFaces !== faceBmp || bound !== src) {
            val fs = BitmapShader(faceBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                setLocalMatrix(Matrix().apply { setScale(src.width.toFloat() / faceBmp.width, src.height.toFloat() / faceBmp.height) })
            }
            shader.setInputShader("faceMask", fs)
            boundFaces = faceBmp
        }
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
        shader.setFloatUniform("lutOn", if (st.curves.isIdentity) 0f else 1f)
        shader.setFloatUniform("lvOn", if (levelsOn(st.lvBlack, st.lvWhite, st.lvGamma)) 1f else 0f)
        shader.setFloatUniform("lvBlack", st.lvBlack)
        shader.setFloatUniform("lvWhite", st.lvWhite)
        shader.setFloatUniform("lvGamma", st.lvGamma.coerceIn(0.2f, 5f))
        shader.setFloatUniform("hslOn", if (st.colours.any { it != 0f }) 1f else 0f)
        shader.setFloatUniform("hslC", FloatArray(8) { Band.entries[it].hue })
        shader.setFloatUniform("hslH", FloatArray(8) { st.colours[it * 3] })
        shader.setFloatUniform("hslS", FloatArray(8) { st.colours[it * 3 + 1] })
        shader.setFloatUniform("hslL", FloatArray(8) { st.colours[it * 3 + 2] })
        shader.setFloatUniform("faceSkin", if (faces != null) st.skin else 0f)
        shader.setFloatUniform("faceTeeth", if (faces != null) st.teeth else 0f)
        shader.setFloatUniform("faceEyes", if (faces != null) st.eyes else 0f)
        shader.setFloatUniform("bgMode", if (!cutout) 0f else when (st.backdrop) { Backdrop.BLUR -> 1f; Backdrop.COLOR -> 2f; else -> 3f })
        shader.setFloatUniform("bgBlurR", max(src.width, src.height) / 1500f * (10f + 40f * st.backdropBlur))
        shader.setFloatUniform("bgCol", Color.red(st.backdropColor) / 255f, Color.green(st.backdropColor) / 255f, Color.blue(st.backdropColor) / 255f)

        setPass()
        pass(canvas, src, m, outW, outH, null)

        // hidden regions and retouch patches: the picture drawn again over its own spot, in the order they were made
        val unit = max(src.width, src.height) / 1500f
        st.marks.forEach { mk ->
            when (mk) {
                is Obscure -> {
                    setPass(snap = if (mk.mosaic) unit * 14f else 0f, blur = if (mk.mosaic) 0f else unit * 12f)
                    val r = mk.r
                    pass(canvas, src, m, outW, outH, RectF((r.l - crop.l) * outW / crop.w, (r.t - crop.t) * outH / crop.h, (r.r - crop.l) * outW / crop.w, (r.b - crop.t) * outH / crop.h))
                }
                is Patch -> patch(canvas, src, st, m, outW, outH, mk)
                else -> Unit
            }
        }

        canvas.save()
        canvas.clipRect(0f, 0f, outW.toFloat(), outH.toFloat())
        drawMarks(canvas, st.marks, crop, outW, outH)
        canvas.restore()
    }

    private fun setPass(snap: Float = 0f, blur: Float = 0f, offX: Float = 0f, offY: Float = 0f, heal: Float = 0f, healR: Float = 0f) {
        shader.setFloatUniform("snap", snap)
        shader.setFloatUniform("blurR", blur)
        shader.setFloatUniform("srcOff", offX, offY)
        shader.setFloatUniform("heal", heal)
        shader.setFloatUniform("healR", healR)
    }

    /** Paints [p]'s stroke with the picture taken from its offset position, with soft edges. Works in source pixels. */
    private fun patch(canvas: Canvas, src: Bitmap, st: EditState, m: Matrix, outW: Int, outH: Int, p: Patch) {
        val (cw, ch) = frameSize(src.width, src.height, st.turns)
        val inv = Matrix().also { editMatrix(src.width, src.height, st, Crop(), cw.roundToInt(), ch.roundToInt()).invert(it) } // frame pixels to source pixels
        val pts = FloatArray(p.pts.size * 2)
        p.pts.forEachIndexed { i, q -> pts[2 * i] = q.u * cw; pts[2 * i + 1] = q.v * ch }
        inv.mapPoints(pts)
        val off = floatArrayOf(p.dx * cw, p.dy * ch).also { inv.mapVectors(it) }
        val v = FloatArray(9).also { inv.getValues(it) }
        val width = p.width * cw * hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])

        setPass(offX = off[0], offY = off[1], heal = if (p.heal) 1f else 0f, healR = width * 0.9f)
        val path = Path()
        for (i in 0 until pts.size / 2) if (i == 0) path.moveTo(pts[0], pts[1]) else path.lineTo(pts[2 * i], pts[2 * i + 1])
        if (pts.size == 2) path.lineTo(pts[0] + 0.01f, pts[1]) // a single tap still paints a dot
        val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = width
            maskFilter = BlurMaskFilter(max(1f, width * 0.2f), BlurMaskFilter.Blur.NORMAL) // soft edge so the patch blends in
            setShader(this@EditShader.shader) // not the Paint's own (empty) shader property
        }
        canvas.save()
        canvas.clipRect(0f, 0f, outW.toFloat(), outH.toFloat())
        canvas.concat(m)
        canvas.drawPath(path, brush)
        canvas.restore()
    }

    private fun pass(canvas: Canvas, src: Bitmap, m: Matrix, outW: Int, outH: Int, region: RectF?) {
        paint.setShader(shader) // a Paint only picks up uniform changes when the shader is set again
        canvas.save()
        canvas.clipRect(0f, 0f, outW.toFloat(), outH.toFloat())
        region?.let { canvas.clipRect(it) }
        canvas.concat(m)
        canvas.drawRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), paint)
        canvas.restore()
    }
}

/** Draws pens, text and stickers. Marks live in frame fractions; [crop] says which part of the frame the output shows. */
internal fun drawMarks(canvas: Canvas, marks: List<Mark>, crop: Crop, outW: Int, outH: Int) {
    val sx = outW / crop.w // output pixels per frame width
    val sy = outH / crop.h
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    for (m in marks) when (m) {
        is Pen -> {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = m.width * sx
            paint.color = if (m.marker) (m.color and 0x00FFFFFF) or 0x66000000 else m.color
            if (m.pts.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle((m.pts[0].u - crop.l) * sx, (m.pts[0].v - crop.t) * sy, m.width * sx / 2f, paint)
            } else {
                val path = Path()
                m.pts.forEachIndexed { i, p ->
                    val x = (p.u - crop.l) * sx
                    val y = (p.v - crop.t) * sy
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, paint)
            }
        }
        is Label -> {
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = m.size * sx
            paint.color = m.color
            canvas.save()
            canvas.rotate(m.rot, (m.u - crop.l) * sx, (m.v - crop.t) * sy)
            canvas.drawText(m.text, (m.u - crop.l) * sx, (m.v - crop.t) * sy + paint.textSize * 0.35f, paint)
            canvas.restore()
        }
        is Sticker -> {
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT
            paint.textSize = m.size * sx
            paint.color = Color.BLACK
            canvas.save()
            canvas.rotate(m.rot, (m.u - crop.l) * sx, (m.v - crop.t) * sy)
            canvas.drawText(m.glyph, (m.u - crop.l) * sx, (m.v - crop.t) * sy + paint.textSize * 0.35f, paint)
            canvas.restore()
        }
        is Obscure, is Patch -> Unit // drawn with the picture itself
    }
}

/** Renders [src] with [st] at full resolution (times [scale]), cropped, off screen on the GPU. */
fun renderEdited(src: Bitmap, st: EditState, scale: Float = 1f, mask: Bitmap? = null, faces: Bitmap? = null): Bitmap {
    val (cw, ch) = frameSize(src.width, src.height, st.turns)
    val w = (st.crop.w * cw * scale).toInt().coerceAtLeast(1)
    val h = (st.crop.h * ch * scale).toInt().coerceAtLeast(1)
    val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
    val node = RenderNode("edit").apply { setPosition(0, 0, w, h) }
    val canvas = node.beginRecording()
    EditShader().draw(canvas, src, st, st.crop, w, h, mask, faces)
    node.endRecording()
    val renderer = HardwareRenderer().apply {
        setSurface(reader.surface); setContentRoot(node)
        if (mask != null && st.backdrop == Backdrop.TRANSPARENT) isOpaque = false // keep the see-through background
    }
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
