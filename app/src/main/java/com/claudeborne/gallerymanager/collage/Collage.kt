package com.claudeborne.gallerymanager.collage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns as C
import com.claudeborne.gallerymanager.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Where each photo goes, as fractions (0..1) of the collage area. */
class CollageLayout(val cells: List<RectF>)

enum class CollageRatio(val label: String, val w: Float, val h: Float) {
    SQUARE("1:1", 1f, 1f), R45("4:5", 4f, 5f), R34("3:4", 3f, 4f), R43("4:3", 4f, 3f), R169("16:9", 16f, 9f), R916("9:16", 9f, 16f)
}

/** How one photo sits inside its cell: zoom on top of "fill the cell", and a shift as a fraction of the cell. */
data class CellView(val scale: Float = 1f, val ox: Float = 0f, val oy: Float = 0f)

/** Spacing, border and corner size are fractions of the collage width. */
data class CollageSpec(
    val layout: CollageLayout,
    val ratio: CollageRatio = CollageRatio.SQUARE,
    val gap: Float = 0.012f,
    val border: Float = 0.012f,
    val radius: Float = 0f,
    val background: Int = 0xFFFFFFFF.toInt(),
)

/** Rows of equal-height cells, each row split evenly: [counts] is how many cells per row. [turned] swaps rows for columns. */
private fun rows(counts: List<Int>, turned: Boolean): CollageLayout {
    val cells = ArrayList<RectF>()
    counts.forEachIndexed { r, k ->
        val t = r / counts.size.toFloat()
        val b = (r + 1) / counts.size.toFloat()
        for (c in 0 until k) {
            val l = c / k.toFloat()
            val rt = (c + 1) / k.toFloat()
            cells += if (turned) RectF(t, l, b, rt) else RectF(l, t, rt, b)
        }
    }
    return CollageLayout(cells)
}

/** One big photo on one side and the rest stacked beside it (or under it when [wide]). */
private fun hero(n: Int, wide: Boolean): CollageLayout {
    val cells = ArrayList<RectF>()
    val rest = n - 1
    if (wide) {
        cells += RectF(0f, 0f, 1f, 0.5f)
        for (i in 0 until rest) cells += RectF(i / rest.toFloat(), 0.5f, (i + 1) / rest.toFloat(), 1f)
    } else {
        cells += RectF(0f, 0f, 0.5f, 1f)
        for (i in 0 until rest) cells += RectF(0.5f, i / rest.toFloat(), 1f, (i + 1) / rest.toFloat())
    }
    return CollageLayout(cells)
}

/** Every way to split [n] into rows of 1 to 3 photos, at most 4 rows. */
private fun compositions(n: Int, left: Int = n, rowsLeft: Int = 4): List<List<Int>> =
    if (left == 0) listOf(emptyList())
    else if (rowsLeft == 0) emptyList()
    else (1..minOf(3, left)).flatMap { k -> compositions(n, left - k, rowsLeft - 1).map { listOf(k) + it } }

/** Layouts for [n] photos, most even first. */
fun layoutsFor(n: Int): List<CollageLayout> {
    val sets = compositions(n).sortedBy { c -> c.maxOrNull()!! - c.minOrNull()!! + c.size * 0.01f }.take(8)
    val all = sets.map { rows(it, false) } + sets.take(5).map { rows(it, true) } + if (n >= 3) listOf(hero(n, false), hero(n, true)) else emptyList()
    val seen = HashSet<List<Int>>()
    return all.filter { l -> seen.add(l.cells.flatMap { listOf((it.left * 100).toInt(), (it.top * 100).toInt(), (it.right * 100).toInt(), (it.bottom * 100).toInt()) }) }
}

/** Pulls [v] back so the photo still covers its [cell]. */
fun clampView(cell: RectF, bmp: Bitmap, v: CellView): CellView {
    val fill = max(cell.width() / bmp.width, cell.height() / bmp.height) * v.scale
    // float rounding can leave the slack a hair below zero, which would make coerceIn throw
    val slackX = ((bmp.width * fill - cell.width()) / 2f / cell.width()).coerceAtLeast(0f)
    val slackY = ((bmp.height * fill - cell.height()) / 2f / cell.height()).coerceAtLeast(0f)
    return v.copy(ox = v.ox.coerceIn(-slackX, slackX), oy = v.oy.coerceIn(-slackY, slackY))
}

/** The rectangles (in pixels of a [w] x [h] collage) the photos occupy, after border and spacing. */
fun cellRects(spec: CollageSpec, w: Float, h: Float): List<RectF> {
    val border = spec.border * w
    val half = spec.gap * w / 2f
    val iw = w - 2 * border
    val ih = h - 2 * border
    return spec.layout.cells.map { c ->
        RectF(
            border + c.left * iw + if (c.left > 0.001f) half else 0f,
            border + c.top * ih + if (c.top > 0.001f) half else 0f,
            border + c.right * iw - if (c.right < 0.999f) half else 0f,
            border + c.bottom * ih - if (c.bottom < 0.999f) half else 0f,
        )
    }
}

/** Draws the collage into the top-left [w] x [h] of [canvas]. Cell i shows `photos[order[i]]` placed by `views[i]`. */
fun drawCollage(canvas: Canvas, spec: CollageSpec, photos: List<Bitmap>, order: List<Int>, views: List<CellView>, w: Int, h: Int) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.save()
    canvas.clipRect(0f, 0f, w.toFloat(), h.toFloat())
    canvas.drawColor(spec.background)
    val radius = spec.radius * w
    cellRects(spec, w.toFloat(), h.toFloat()).forEachIndexed { i, rect ->
        val bmp = photos[order[i]]
        val v = views[i]
        val fill = max(rect.width() / bmp.width, rect.height() / bmp.height) * v.scale
        // the photo can be moved around but never far enough to leave the cell uncovered
        val slackX = ((bmp.width * fill - rect.width()) / 2f).coerceAtLeast(0f)
        val slackY = ((bmp.height * fill - rect.height()) / 2f).coerceAtLeast(0f)
        val tx = (v.ox * rect.width()).coerceIn(-slackX, slackX)
        val ty = (v.oy * rect.height()).coerceIn(-slackY, slackY)
        val m = Matrix().apply {
            setScale(fill, fill)
            postTranslate(rect.centerX() - bmp.width * fill / 2f + tx, rect.centerY() - bmp.height * fill / 2f + ty)
        }
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
        canvas.drawBitmap(bmp, m, paint)
        canvas.restore()
    }
    canvas.restore()
}

/** Renders the finished collage with a long side of [longSide] pixels. */
fun renderCollage(spec: CollageSpec, photos: List<Bitmap>, order: List<Int>, views: List<CellView>, longSide: Int = 2400): Bitmap {
    val wide = spec.ratio.w >= spec.ratio.h
    val w = if (wide) longSide else (longSide * spec.ratio.w / spec.ratio.h).toInt()
    val h = if (wide) (longSide * spec.ratio.h / spec.ratio.w).toInt() else longSide
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawCollage(Canvas(it), spec, photos, order, views, w, h) }
}

/** Saves [bitmap] as a new JPEG in the collage folder (see Settings). Returns false if it could not be written. */
suspend fun saveCollage(context: Context, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val now = System.currentTimeMillis()
    val values = ContentValues().apply {
        put(C.DISPLAY_NAME, "Collage_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now)) + ".jpg")
        put(C.MIME_TYPE, "image/jpeg")
        put(C.RELATIVE_PATH, "Pictures/${Settings.collageFolder}/")
        put(C.DATE_TAKEN, now)
        put(C.IS_PENDING, 1)
    }
    val target = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return@withContext false
    val ok = runCatching { resolver.openOutputStream(target)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, Settings.editQuality, it) } }.getOrDefault(false)
    if (ok) resolver.update(target, ContentValues().apply { put(C.IS_PENDING, 0) }, null, null) else resolver.delete(target, null, null)
    ok
}
