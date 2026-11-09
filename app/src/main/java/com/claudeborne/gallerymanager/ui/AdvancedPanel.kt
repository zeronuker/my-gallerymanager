package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
            item { Chip("← BASIC", color = DarkCb.mint) { onMode(0) } }
            item { Chip("LEVELS", adv == ADV_LEVELS) { onMode(ADV_LEVELS) } }
            item { Chip("CURVES", adv == ADV_CURVES) { onMode(ADV_CURVES) } }
            item { Chip("COLOUR", adv == ADV_COLOUR) { onMode(ADV_COLOUR) } }
        }
        when (adv) {
            ADV_LEVELS -> {
                EditSlider("BLACK POINT", st.lvBlack, 0f..0.6f, "${(st.lvBlack * 100).roundToInt()}", { onLive(st.copy(lvBlack = minOf(it, st.lvWhite - 0.05f))) }, onDone) { onLive(st.copy(lvBlack = 0f)); onDone() }
                EditSlider("MIDTONES", st.lvGamma, 0.3f..3f, "%.2f".format(st.lvGamma), { onLive(st.copy(lvGamma = it)) }, onDone) { onLive(st.copy(lvGamma = 1f)); onDone() }
                EditSlider("WHITE POINT", st.lvWhite, 0.4f..1f, "${(st.lvWhite * 100).roundToInt()}", { onLive(st.copy(lvWhite = maxOf(it, st.lvBlack + 0.05f))) }, onDone) { onLive(st.copy(lvWhite = 1f)); onDone() }
            }
            ADV_CURVES -> Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CurveEditor(st.curves.channel(channel), channel, { onLive(st.copy(curves = st.curves.with(channel, it))) }, onDone, Modifier.size(180.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("RGB", "RED", "GREEN", "BLUE").forEachIndexed { i, label -> Chip(label, channel == i) { onChannel(i) } }
                    Chip("RESET", color = DarkCb.inkDim) { onLive(st.copy(curves = st.curves.with(channel, Curves().master))); onDone() }
                }
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

/** A curve you can shape: drag a point to move it, tap empty space to add one, double-tap a point to remove it. */
@Composable
private fun CurveEditor(points: List<Pt>, channel: Int, onChange: (List<Pt>) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val pts by rememberUpdatedState(points)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val line = when (channel) { 1 -> Color(0xFFFF6B6B); 2 -> Color(0xFF5BE08C); 3 -> Color(0xFF5B9DFF); else -> DarkCb.mint }

    fun nearest(o: Offset, w: Float, h: Float, slop: Float): Int {
        var best = -1
        var bestD = slop
        pts.forEachIndexed { i, p ->
            val d = hypot(p.u * w - o.x, (1f - p.v) * h - o.y)
            if (d < bestD) { best = i; bestD = d }
        }
        return best
    }

    Canvas(
        modifier.background(DarkCb.surface2)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { o ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        if (nearest(o, w, h, 28.dp.toPx()) < 0 && pts.size < MAX_POINTS) {
                            val u = (o.x / w).coerceIn(0.02f, 0.98f)
                            if (pts.none { abs(it.u - u) < MIN_GAP }) {
                                change((pts + Pt(u, (1f - o.y / h).coerceIn(0f, 1f))).sortedBy { it.u }); done()
                            }
                        }
                    },
                    onDoubleTap = { o ->
                        val i = nearest(o, size.width.toFloat(), size.height.toFloat(), 28.dp.toPx())
                        if (i > 0 && i < pts.lastIndex) { change(pts.filterIndexed { k, _ -> k != i }); done() } // the two end points stay
                    },
                )
            }
            .pointerInput(Unit) {
                var grab = -1
                detectDragGestures(
                    onDragStart = { o -> grab = nearest(o, size.width.toFloat(), size.height.toFloat(), 36.dp.toPx()) },
                    onDrag = { c, _ ->
                        if (grab >= 0) {
                            c.consume()
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            val v = (1f - c.position.y / h).coerceIn(0f, 1f)
                            val last = pts.lastIndex
                            val u = when (grab) {
                                0 -> 0f
                                last -> 1f
                                else -> (c.position.x / w).coerceIn(pts[grab - 1].u + MIN_GAP, pts[grab + 1].u - MIN_GAP)
                            }
                            change(pts.mapIndexed { i, p -> if (i == grab) Pt(u, v) else p })
                        }
                    },
                    onDragEnd = { if (grab >= 0) done(); grab = -1 },
                    onDragCancel = { grab = -1 },
                )
            },
    ) {
        val grid = Color(0x22FFFFFF)
        for (i in 1..3) {
            drawLine(grid, Offset(size.width * i / 4, 0f), Offset(size.width * i / 4, size.height), 1f)
            drawLine(grid, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1f)
        }
        drawLine(Color(0x44FFFFFF), Offset(0f, size.height), Offset(size.width, 0f), 1f) // the "no change" line
        val path = Path()
        for (i in 0..60) {
            val x = i / 60f
            val y = 1f - curveAt(points, x)
            if (i == 0) path.moveTo(x * size.width, y * size.height) else path.lineTo(x * size.width, y * size.height)
        }
        drawPath(path, line, style = Stroke(2.dp.toPx()))
        points.forEach { p -> drawCircle(Color.White, 5.dp.toPx(), Offset(p.u * size.width, (1f - p.v) * size.height), style = Stroke(2.dp.toPx())) }
    }
}
