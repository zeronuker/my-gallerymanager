package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.edit.Crop
import com.claudeborne.gallerymanager.edit.Label
import com.claudeborne.gallerymanager.edit.Mark
import com.claudeborne.gallerymanager.edit.Obscure
import com.claudeborne.gallerymanager.edit.Pen
import com.claudeborne.gallerymanager.edit.Pt
import com.claudeborne.gallerymanager.edit.Sticker
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class MarkTool(val label: String) {
    PEN("PEN"), MARKER("MARKER"), ERASER("ERASER"), TEXT("TEXT"), STICKER("STICKER"), BLUR("BLUR"), MOSAIC("MOSAIC")
}

/** Default size of each tool and the range its slider covers, as fractions of the picture width. */
internal fun sizeRange(tool: MarkTool): ClosedFloatingPointRange<Float> = when (tool) {
    MarkTool.PEN -> 0.002f..0.02f
    MarkTool.MARKER -> 0.01f..0.06f
    MarkTool.TEXT -> 0.03f..0.15f
    else -> 0.05f..0.3f
}

internal val MarkPalette = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFF6B6B, 0xFFFFB84D, 0xFFFFE14D, 0xFF3FE0C5, 0xFF3B8DFF, 0xFFFF7AD9,
).map { it.toInt() }

private val StickerGlyphs = listOf(
    "😀", "😂", "😍", "😎", "🥳", "😱", "👍", "👎", "❤️", "🔥", "⭐", "✨", "🎉", "💯", "👀", "💀", "🌈", "☀️", "🌙", "🍕", "🐶", "🐱", "🎈", "📍",
)

/** Resizes a text or sticker mark; other marks are returned untouched. */
internal fun Mark.withSize(size: Float): Mark = when (this) {
    is Label -> copy(size = size)
    is Sticker -> copy(size = size)
    else -> this
}

internal fun Mark.sizeOrNull(): Float? = when (this) { is Label -> size; is Sticker -> size; else -> null }

/**
 * Touch layer drawn over the picture. It turns touches into changes to [marks] (frame fractions, see [Pt]) and reports them through
 * [onLive] while a gesture runs and [onDone] when it ends.
 */
@Composable
internal fun MarkupOverlay(
    tool: MarkTool, crop: Crop, marks: List<Mark>, color: Int, size: Float, glyph: String, selected: Int,
    onLive: (List<Mark>) -> Unit, onDone: () -> Unit, onSelect: (Int) -> Unit, onText: (Pt) -> Unit, onSticker: (Pt) -> Unit,
    modifier: Modifier,
) {
    val toolNow by rememberUpdatedState(tool)
    val cropNow by rememberUpdatedState(crop)
    val marksNow by rememberUpdatedState(marks)
    val colorNow by rememberUpdatedState(color)
    val sizeNow by rememberUpdatedState(size)
    val liveNow by rememberUpdatedState(onLive)
    val doneNow by rememberUpdatedState(onDone)
    val selectNow by rememberUpdatedState(onSelect)
    val textNow by rememberUpdatedState(onText)
    val stickerNow by rememberUpdatedState(onSticker)
    var view by remember { mutableStateOf(IntSize(1, 1)) }
    val minHit = with(LocalDensity.current) { 40.dp.toPx() }
    val eraseR = with(LocalDensity.current) { 22.dp.toPx() }

    fun pt(o: Offset) = Pt(
        (cropNow.l + o.x / view.width * cropNow.w).coerceIn(0f, 1f),
        (cropNow.t + o.y / view.height * cropNow.h).coerceIn(0f, 1f),
    )
    fun px(p: Pt) = Offset((p.u - cropNow.l) / cropNow.w * view.width, (p.v - cropNow.t) / cropNow.h * view.height)
    fun perFrame() = view.width / cropNow.w // view pixels per picture width

    /** centre and half size of a text or sticker mark in view pixels, or null for other marks */
    fun bounds(m: Mark): FloatArray? = when (m) {
        is Label -> px(Pt(m.u, m.v)).let { floatArrayOf(it.x, it.y, m.text.length * m.size * perFrame() * 0.3f, m.size * perFrame() * 0.65f) }
        is Sticker -> px(Pt(m.u, m.v)).let { floatArrayOf(it.x, it.y, m.size * perFrame() * 0.6f, m.size * perFrame() * 0.6f) }
        else -> null
    }
    fun hit(o: Offset): Int {
        for (i in marksNow.indices.reversed()) {
            val b = bounds(marksNow[i]) ?: continue
            if (abs(o.x - b[0]) <= max(b[2], minHit) && abs(o.y - b[1]) <= max(b[3], minHit)) return i
        }
        return -1
    }
    fun erase(o: Offset): List<Mark> {
        val grab = hit(o)
        return marksNow.filterIndexed { i, m ->
            when (m) {
                is Pen -> m.pts.none { hypot(px(it).x - o.x, px(it).y - o.y) < eraseR + m.width * perFrame() / 2 }
                is Obscure -> !(o.x in px(Pt(m.r.l, m.r.t)).x..px(Pt(m.r.r, m.r.b)).x && o.y in px(Pt(m.r.l, m.r.t)).y..px(Pt(m.r.r, m.r.b)).y)
                else -> i != grab
            }
        }
    }
    fun pen(pts: List<Pt>) = Pen(pts.toList(), colorNow, sizeNow, marker = toolNow == MarkTool.MARKER)

    Canvas(
        modifier
            .onSizeChanged { view = it }
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    when (toolNow) {
                        MarkTool.PEN, MarkTool.MARKER -> { liveNow(marksNow + pen(listOf(pt(o)))); doneNow() }
                        MarkTool.ERASER -> { liveNow(erase(o)); doneNow() }
                        MarkTool.TEXT -> hit(o).let { if (it >= 0) selectNow(it) else textNow(pt(o)) }
                        MarkTool.STICKER -> hit(o).let { if (it >= 0) selectNow(it) else stickerNow(pt(o)) }
                        else -> Unit
                    }
                }
            }
            .pointerInput(Unit) {
                var base = emptyList<Mark>()
                var pts = emptyList<Pt>()
                var moving = -1
                var origin = Pt(0f, 0f)
                fun region(p: Pt) = Obscure(Crop(min(origin.u, p.u), min(origin.v, p.v), max(origin.u, p.u), max(origin.v, p.v)), toolNow == MarkTool.MOSAIC)
                detectDragGestures(
                    onDragStart = { o ->
                        base = marksNow
                        when (toolNow) {
                            MarkTool.PEN, MarkTool.MARKER -> { pts = listOf(pt(o)); liveNow(base + pen(pts)) }
                            MarkTool.ERASER -> liveNow(erase(o))
                            MarkTool.TEXT, MarkTool.STICKER -> { moving = hit(o); if (moving >= 0) selectNow(moving) }
                            MarkTool.BLUR, MarkTool.MOSAIC -> { origin = pt(o); liveNow(base + region(origin)) }
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        when (toolNow) {
                            MarkTool.PEN, MarkTool.MARKER -> { pts = pts + pt(change.position); liveNow(base + pen(pts)) }
                            MarkTool.ERASER -> liveNow(erase(change.position))
                            MarkTool.TEXT, MarkTool.STICKER -> if (moving >= 0) {
                                val du = amount.x / view.width * cropNow.w
                                val dv = amount.y / view.height * cropNow.h
                                liveNow(marksNow.mapIndexed { i, m ->
                                    if (i != moving) m else when (m) {
                                        is Label -> m.copy(u = m.u + du, v = m.v + dv)
                                        is Sticker -> m.copy(u = m.u + du, v = m.v + dv)
                                        else -> m
                                    }
                                })
                            }
                            MarkTool.BLUR, MarkTool.MOSAIC -> liveNow(base + region(pt(change.position)))
                        }
                    },
                    onDragEnd = { moving = -1; doneNow() },
                    onDragCancel = { moving = -1; doneNow() },
                )
            },
    ) {
        // outline around the selected text or sticker
        marks.getOrNull(selected)?.let { bounds(it) }?.let { b ->
            drawRect(DarkCb.mint, Offset(b[0] - b[2], b[1] - b[3]), Size(b[2] * 2, b[3] * 2), style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Composable
internal fun MarkupPanel(
    tool: MarkTool, onTool: (MarkTool) -> Unit, color: Int, onColor: (Int) -> Unit, glyph: String, onGlyph: (String) -> Unit,
    size: Float, range: ClosedFloatingPointRange<Float>, onSize: (Float) -> Unit, onSizeDone: () -> Unit, canDelete: Boolean, onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
            items(MarkTool.entries) { Chip(it.label, tool == it) { onTool(it) } }
            if (canDelete) item { Chip("DELETE", color = Danger, onClick = onDelete) }
        }
        when (tool) {
            MarkTool.PEN, MarkTool.MARKER, MarkTool.TEXT -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                items(MarkPalette) { c ->
                    Column(
                        Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                            .border(if (c == color) 2.dp else 1.dp, if (c == color) DarkCb.mint else Color(0x66FFFFFF), CircleShape).clickable { onColor(c) },
                    ) {}
                }
            }
            MarkTool.STICKER -> LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                items(StickerGlyphs) { g ->
                    Text(
                        g, Modifier.clickable { onGlyph(g) }.background(if (g == glyph) DarkCb.surface3 else Color.Transparent).padding(horizontal = 8.dp, vertical = 4.dp),
                        style = TextStyle(fontSize = 24.sp),
                    )
                }
            }
            MarkTool.ERASER -> Text("TOUCH A MARK TO REMOVE IT", Modifier.padding(horizontal = 16.dp), style = monoStyle(DarkCb.inkDim, 1.5.sp))
            MarkTool.BLUR, MarkTool.MOSAIC -> Text("DRAG OVER THE AREA TO HIDE", Modifier.padding(horizontal = 16.dp), style = monoStyle(DarkCb.inkDim, 1.5.sp))
        }
        if (tool != MarkTool.ERASER && tool != MarkTool.BLUR && tool != MarkTool.MOSAIC) {
            EditSlider("SIZE", size, range, "${((size - range.start) / (range.endInclusive - range.start) * 100).roundToInt()}", onSize, onSizeDone) {}
        } else {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {}
        }
    }
}

/** Asks for the words of a new text mark. */
@Composable
internal fun AddTextDialog(onAdd: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val value = text.trim()
    CbDialog("ADD TEXT", onCancel) {
        CbTextField(text, { text = it })
        Row(Modifier.fillMaxWidth(), Arrangement.End) {
            ActionText("CANCEL", DarkCb.inkDim, onCancel)
            ActionText("ADD", if (value.isNotEmpty()) DarkCb.mint else DarkCb.inkDim) { if (value.isNotEmpty()) onAdd(value) }
        }
    }
}
