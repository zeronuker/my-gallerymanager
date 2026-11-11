package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.edit.Band
import com.claudeborne.gallerymanager.edit.BandAxis
import com.claudeborne.gallerymanager.edit.Curves
import com.claudeborne.gallerymanager.edit.EditState
import com.claudeborne.gallerymanager.edit.Pt
import com.claudeborne.gallerymanager.edit.curveAt
import com.claudeborne.gallerymanager.edit.tune
import com.claudeborne.gallerymanager.edit.tuned
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 0 = the plain sliders; the others are the advanced tools shown instead of them. */
internal const val ADV_LEVELS = 1
internal const val ADV_CURVES = 2
internal const val ADV_COLOUR = 3

/** The advanced tone and colour tools. [onLive] shows a change while a control is being dragged; [onDone] keeps it as an undo step. */
@Composable
internal fun AdvancedPanel(
    adv: Int, st: EditState, onMode: (Int) -> Unit, onLive: (EditState) -> Unit, onDone: () -> Unit,
    channel: Int, onChannel: (Int) -> Unit, band: Band, onBand: (Band) -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
            item { Chip("BASIC", color = DarkCb.mint, icon = "back") { onMode(0) } }
            item { Chip("LEVELS", adv == ADV_LEVELS, icon = "levels") { onMode(ADV_LEVELS) } }
            item { Chip("CURVES", adv == ADV_CURVES, icon = "curves") { onMode(ADV_CURVES) } }
            item { Chip("COLOUR", adv == ADV_COLOUR, icon = "colour") { onMode(ADV_COLOUR) } }
        }
        when (adv) {
            ADV_LEVELS -> {
                EditSlider("BLACK POINT", st.lvBlack, 0f..0.6f, "${(st.lvBlack * 100).roundToInt()}", { onLive(st.copy(lvBlack = minOf(it, st.lvWhite - 0.05f))) }, onDone) { onLive(st.copy(lvBlack = 0f)); onDone() }
                EditSlider("MIDTONES", st.lvGamma, 0.3f..3f, "%.2f".format(st.lvGamma), { onLive(st.copy(lvGamma = it)) }, onDone) { onLive(st.copy(lvGamma = 1f)); onDone() }
                EditSlider("WHITE POINT", st.lvWhite, 0.4f..1f, "${(st.lvWhite * 100).roundToInt()}", { onLive(st.copy(lvWhite = maxOf(it, st.lvBlack + 0.05f))) }, onDone) { onLive(st.copy(lvWhite = 1f)); onDone() }
            }
            ADV_CURVES -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    listOf("RGB", "RED", "GREEN", "BLUE").forEachIndexed { i, label -> Chip(label, channel == i) { onChannel(i) } }
                    Chip("RESET", color = DarkCb.inkDim, icon = "reset") { onLive(st.copy(curves = st.curves.with(channel, Curves().master))); onDone() }
                }
                CurveEditor(st.curves.channel(channel), channel, { onLive(st.copy(curves = st.curves.with(channel, it))) }, onDone, Modifier.fillMaxWidth().height(210.dp))
            }
            else -> {
                LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) { items(Band.entries) { b -> Chip(b.label, band == b) { onBand(b) } } }
                BandAxis.entries.forEach { axis ->
                    val v = st.colours.tuned(band, axis)
                    EditSlider(axis.label, v, -1f..1f, "%+d".format((v * 100).roundToInt()), { onLive(st.copy(colours = st.colours.tune(band, axis, it))) }, onDone) {
                        onLive(st.copy(colours = st.colours.tune(band, axis, 0f))); onDone()
                    }
                }
            }
        }
    }
}

private const val MAX_POINTS = 8
private const val MIN_GAP = 0.03f

/**
 * A curve you can shape. Touch near a point to grab it; touch anywhere else to put a new point right there and drag it. Everything
 * reacts the moment your finger lands. Double-tap a point to remove it (the two end points stay).
 */
@Composable
private fun CurveEditor(points: List<Pt>, channel: Int, onChange: (List<Pt>) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val pts by rememberUpdatedState(points)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    var active by remember { mutableIntStateOf(-1) }
    val line = when (channel) { 1 -> Color(0xFFFF6B6B); 2 -> Color(0xFF5BE08C); 3 -> Color(0xFF5B9DFF); else -> DarkCb.mint }
    val pad = 14.dp // room around the graph so the end points at its corners are fully touchable

    Canvas(
        modifier.background(DarkCb.surface2).pointerInput(Unit) {
            val padPx = pad.toPx()
            var lastUp = 0L // for double-tap
            var lastIdx = -1
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val iw = size.width - 2 * padPx
                val ih = size.height - 2 * padPx
                fun toPt(o: Offset) = Pt(((o.x - padPx) / iw).coerceIn(0f, 1f), (1f - (o.y - padPx) / ih).coerceIn(0f, 1f))
                fun nearest(o: Offset, slop: Float, onlyX: Boolean = false): Int {
                    var best = -1
                    var bestD = slop
                    pts.forEachIndexed { i, p ->
                        val dx = padPx + p.u * iw - o.x
                        val dy = padPx + (1f - p.v) * ih - o.y
                        val d = if (onlyX) abs(dx) else hypot(dx, dy)
                        if (d < bestD) { best = i; bestD = d }
                    }
                    return best
                }

                var work = pts // the curve as this gesture changes it (the shared state catches up a frame later)
                var idx = nearest(down.position, 44.dp.toPx())
                val now = System.currentTimeMillis()
                if (idx > 0 && idx < work.lastIndex && idx == lastIdx && now - lastUp < 350) {
                    change(work.filterIndexed { k, _ -> k != idx }); done()
                    active = -1; lastIdx = -1; lastUp = 0L
                    waitForUpOrCancellation()
                    return@awaitEachGesture
                }
                if (idx < 0) {
                    val p = toPt(down.position)
                    val u = p.u.coerceIn(0.02f, 0.98f)
                    if (work.size < MAX_POINTS && work.none { abs(it.u - u) < MIN_GAP }) {
                        work = (work + Pt(u, p.v)).sortedBy { it.u }
                        idx = work.indexOfFirst { it.u == u }
                        change(work)
                    } else {
                        idx = nearest(down.position, Float.MAX_VALUE, onlyX = true) // no room for a new point: move the closest one
                    }
                }
                active = idx

                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    c.consume()
                    val p = toPt(c.position)
                    val last = work.lastIndex
                    val u = when (idx) {
                        0 -> 0f
                        last -> 1f
                        else -> {
                            val lo = work[idx - 1].u + MIN_GAP
                            val hi = work[idx + 1].u - MIN_GAP
                            if (lo <= hi) p.u.coerceIn(lo, hi) else work[idx].u
                        }
                    }
                    work = work.mapIndexed { i, q -> if (i == idx) Pt(u, p.v) else q }
                    change(work)
                }
                done()
                lastIdx = idx
                lastUp = System.currentTimeMillis()
            }
        },
    ) {
        val p = pad.toPx()
        val iw = size.width - 2 * p
        val ih = size.height - 2 * p
        val grid = Color(0x22FFFFFF)
        for (i in 0..4) {
            drawLine(grid, Offset(p + iw * i / 4, p), Offset(p + iw * i / 4, p + ih), 1f)
            drawLine(grid, Offset(p, p + ih * i / 4), Offset(p + iw, p + ih * i / 4), 1f)
        }
        drawLine(Color(0x44FFFFFF), Offset(p, p + ih), Offset(p + iw, p), 1f) // the "no change" line
        val path = Path()
        for (i in 0..80) {
            val x = i / 80f
            val y = 1f - curveAt(points, x)
            if (i == 0) path.moveTo(p + x * iw, p + y * ih) else path.lineTo(p + x * iw, p + y * ih)
        }
        drawPath(path, line, style = Stroke(2.5.dp.toPx()))
        points.forEachIndexed { i, pt ->
            val c = Offset(p + pt.u * iw, p + (1f - pt.v) * ih)
            if (i == active) drawCircle(line, 9.dp.toPx(), c)
            drawCircle(Color.White, if (i == active) 9.dp.toPx() else 7.dp.toPx(), c, style = Stroke(2.dp.toPx()))
        }
    }
}
