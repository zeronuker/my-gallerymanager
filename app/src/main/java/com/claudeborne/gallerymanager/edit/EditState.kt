package com.claudeborne.gallerymanager.edit

/** One slider in the Adjust tool. Values run from [min] to 1; 0 means untouched. */
enum class Adjust(val label: String, val min: Float = -1f) {
    EXPOSURE("EXPOSURE"), BRIGHTNESS("BRIGHTNESS"), CONTRAST("CONTRAST"), HIGHLIGHTS("HIGHLIGHTS"), SHADOWS("SHADOWS"),
    WHITES("WHITES"), BLACKS("BLACKS"), SATURATION("SATURATION"), VIBRANCE("VIBRANCE"), WARMTH("WARMTH"), TINT("TINT"),
    SHARPNESS("SHARPNESS"), CLARITY("CLARITY"), VIGNETTE("VIGNETTE"), GRAIN("GRAIN", min = 0f),
}

/** Which part of the picture the Adjust sliders act on. */
enum class Scope(val label: String) { ALL("ALL"), SUBJECT("SUBJECT"), BACKGROUND("BACKGROUND") }

/** The sliders that can be limited to the subject or the background; the shader reads them in this order. */
val ScopedAdjust = listOf(Adjust.EXPOSURE, Adjust.BRIGHTNESS, Adjust.CONTRAST, Adjust.SATURATION, Adjust.WARMTH, Adjust.SHARPNESS)

private fun Map<Adjust, Float>.put(a: Adjust, v: Float) = if (v == 0f) this - a else this + (a to v)

/** Crop rectangle as fractions of the rotated, straightened frame. */
data class Crop(val l: Float = 0f, val t: Float = 0f, val r: Float = 1f, val b: Float = 1f) {
    val w get() = r - l
    val h get() = b - t
}

/** A point in the rotated, straightened frame, as fractions of its width and height (so crops made later never move it). */
data class Pt(val u: Float, val v: Float)

/** Things drawn on top of the picture. Sizes are fractions of the frame width. */
sealed interface Mark
data class Pen(val pts: List<Pt>, val color: Int, val width: Float, val marker: Boolean) : Mark
data class Label(val text: String, val u: Float, val v: Float, val size: Float, val color: Int, val rot: Float = 0f) : Mark
data class Sticker(val glyph: String, val u: Float, val v: Float, val size: Float, val rot: Float = 0f) : Mark
/** A region of the picture hidden behind a mosaic or a blur. */
data class Obscure(val r: Crop, val mosaic: Boolean) : Mark
/** Paint over [pts] with the picture as it looks ([dx], [dy]) away, in frame fractions. [heal] also matches the colour to the surroundings. */
data class Patch(val pts: List<Pt>, val width: Float, val dx: Float, val dy: Float, val heal: Boolean) : Mark

/** Everything the user changed, kept apart from the pixels so any step can be undone. */
data class EditState(
    val turns: Int = 0, // quarter turns clockwise
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val angle: Float = 0f, // straighten, degrees
    val crop: Crop = Crop(),
    val adjust: Map<Adjust, Float> = emptyMap(),
    val subject: Map<Adjust, Float> = emptyMap(), // extra changes that act only on the subject / only on the background, see [ScopedAdjust]
    val background: Map<Adjust, Float> = emptyMap(),
    val filter: Int = 0, // index into [Filters]
    val filterAmount: Float = 1f,
    val marks: List<Mark> = emptyList(),
    val backdrop: Backdrop = Backdrop.KEEP,
    val backdropColor: Int = 0xFFFFFFFF.toInt(),
    val backdropBlur: Float = 0.5f,
    val lvBlack: Float = 0f, // levels: darkest and brightest input kept, and the midtone gamma
    val lvWhite: Float = 1f,
    val lvGamma: Float = 1f,
    val curves: Curves = Curves(),
    val colours: List<Float> = NoColourTuning, // per-colour tuning, see Band
    val skin: Float = 0f, // portrait retouch strengths, 0..1
    val teeth: Float = 0f,
    val eyes: Float = 0f,
) {
    fun value(a: Adjust, s: Scope = Scope.ALL) = (when (s) { Scope.ALL -> adjust; Scope.SUBJECT -> subject; Scope.BACKGROUND -> background })[a] ?: 0f
    fun with(a: Adjust, v: Float, s: Scope = Scope.ALL) = when (s) {
        Scope.ALL -> copy(adjust = adjust.put(a, v))
        Scope.SUBJECT -> copy(subject = subject.put(a, v))
        Scope.BACKGROUND -> copy(background = background.put(a, v))
    }
    val isEdited get() = this != EditState()
}

enum class Aspect(val label: String, val ratio: Float) {
    FREE("FREE", 0f), ORIGINAL("ORIGINAL", 0f), SQUARE("1:1", 1f), R43("4:3", 4f / 3), R34("3:4", 3f / 4), R169("16:9", 16f / 9), R916("9:16", 9f / 16);

    /** Width / height in pixels the crop must keep, or null when it is free. [frame] is the uncropped frame's own ratio. */
    fun lock(frame: Float): Float? = when (this) { FREE -> null; ORIGINAL -> frame; else -> ratio }
}

/** A look built from a few simple knobs; the shader applies these before the manual adjustments. */
class FilterDef(
    val name: String,
    val sat: Float = 1f,
    val contrast: Float = 1f,
    val brightness: Float = 0f,
    val fade: Float = 0f, // raises the blacks
    val tint: FloatArray = floatArrayOf(1f, 1f, 1f), // multiplies each channel
    val lift: FloatArray = floatArrayOf(0f, 0f, 0f), // adds to each channel
    val glow: Float = 0f,
)

val Filters = listOf(
    FilterDef("ORIGINAL"),
    FilterDef("VIVID", sat = 1.35f, contrast = 1.1f),
    FilterDef("WARM", sat = 1.05f, tint = floatArrayOf(1.07f, 1f, 0.89f)),
    FilterDef("COOL", tint = floatArrayOf(0.91f, 1f, 1.09f)),
    FilterDef("FADE", sat = 0.85f, contrast = 0.92f, fade = 0.07f),
    FilterDef("MATTE", sat = 0.9f, contrast = 0.95f, fade = 0.1f, tint = floatArrayOf(1f, 0.99f, 1.03f)),
    FilterDef("VINTAGE", sat = 0.8f, fade = 0.06f, tint = floatArrayOf(1.08f, 1f, 0.86f)),
    FilterDef("CHROME", sat = 1.15f, contrast = 1.2f),
    FilterDef("TEAL ORANGE", sat = 1.1f, contrast = 1.12f, tint = floatArrayOf(1.1f, 1f, 0.9f), lift = floatArrayOf(0f, 0.02f, 0.04f)),
    FilterDef("MONO", sat = 0f),
    FilterDef("NOIR", sat = 0f, contrast = 1.35f, brightness = -0.03f),
    FilterDef("SILVER", sat = 0f, contrast = 1.1f, fade = 0.05f, tint = floatArrayOf(0.98f, 1f, 1.03f)),
    FilterDef("SEPIA", sat = 0f, tint = floatArrayOf(1.12f, 0.97f, 0.78f), lift = floatArrayOf(0.02f, 0.01f, 0f)),
    FilterDef("DRAMATIC", sat = 1.1f, contrast = 1.4f, brightness = -0.04f),
    FilterDef("SOFT GLOW", sat = 1.05f, glow = 1f),
)
