package com.claudeborne.gallerymanager.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.edit.Crop
import com.claudeborne.gallerymanager.edit.EditState
import com.claudeborne.gallerymanager.edit.Mark
import com.claudeborne.gallerymanager.edit.Patch
import com.claudeborne.gallerymanager.edit.Pt
import com.claudeborne.gallerymanager.edit.findHealOffset
import kotlin.math.roundToInt

internal enum class RetouchTool(val label: String) { HEAL("HEAL"), CLONE("CLONE") }

/**
 * Touch layer for Heal and Clone. Heal picks its own source when the stroke ends; Clone copies from [source], keeping the same
 * [offset] for every stroke after the first (so the copied area moves along with the brush).
 */
@Composable
internal fun RetouchOverlay(
    tool: RetouchTool, crop: Crop, marks: List<Mark>, bmp: Bitmap, st: EditState, size: Float, source: Pt?, offset: Pt?,
    onLive: (List<Mark>) -> Unit, onDone: () -> Unit, onSource: (Pt) -> Unit, onOffset: (Pt) -> Unit,
    modifier: Modifier,
) {
    val toolNow by rememberUpdatedState(tool)
    val cropNow by rememberUpdatedState(crop)
    val marksNow by rememberUpdatedState(marks)
    val stNow by rememberUpdatedState(st)
    val sizeNow by rememberUpdatedState(size)
    val sourceNow by rememberUpdatedState(source)
    val offsetNow by rememberUpdatedState(offset)
    val liveNow by rememberUpdatedState(onLive)
    val doneNow by rememberUpdatedState(onDone)
    val sourceSet by rememberUpdatedState(onSource)
    val offsetSet by rememberUpdatedState(onOffset)
    var view by remember { mutableStateOf(IntSize(1, 1)) }
    var touch by remember { mutableStateOf<Pt?>(null) } // brush position while a stroke runs
    var draft by remember { mutableStateOf<List<Pt>>(emptyList()) } // a Heal stroke waiting for its source

    fun pt(o: Offset) = Pt(
        (cropNow.l + o.x / view.width * cropNow.w).coerceIn(0f, 1f),
        (cropNow.t + o.y / view.height * cropNow.h).coerceIn(0f, 1f),
    )
    fun px(p: Pt) = Offset((p.u - cropNow.l) / cropNow.w * view.width, (p.v - cropNow.t) / cropNow.h * view.height)

    fun heal(pts: List<Pt>) {
        val off = findHealOffset(bmp, stNow, pts, sizeNow)
        liveNow(marksNow + Patch(pts, sizeNow, off.u, off.v, true))
        doneNow()
    }

    Canvas(
        modifier
            .onSizeChanged { view = it }
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val p = pt(o)
                    when (toolNow) {
                        RetouchTool.HEAL -> heal(listOf(p))
                        RetouchTool.CLONE -> {
                            val s = sourceNow
                            if (s == null) sourceSet(p)
                            else {
                                val off = offsetNow ?: Pt(s.u - p.u, s.v - p.v).also(offsetSet)
                                liveNow(marksNow + Patch(listOf(p), sizeNow, off.u, off.v, false))
                                doneNow()
                            }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                var base = emptyList<Mark>()
                var pts = emptyList<Pt>()
                var off: Pt? = null // null: this drag only sets the Clone source
                detectDragGestures(
                    onDragStart = { o ->
                        val p = pt(o)
                        base = marksNow
                        pts = listOf(p)
                        touch = p
                        off = null
                        when (toolNow) {
                            RetouchTool.HEAL -> draft = pts
                            RetouchTool.CLONE -> {
                                val s = sourceNow
                                if (s == null) sourceSet(p)
                                else {
                                    off = offsetNow ?: Pt(s.u - p.u, s.v - p.v).also(offsetSet)
                                    liveNow(base + Patch(pts, sizeNow, off!!.u, off!!.v, false))
                                }
                            }
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val p = pt(change.position)
                        touch = p
                        when (toolNow) {
                            RetouchTool.HEAL -> { pts = pts + p; draft = pts }
                            RetouchTool.CLONE -> off?.let { pts = pts + p; liveNow(base + Patch(pts, sizeNow, it.u, it.v, false)) }
                        }
                    },
                    onDragEnd = {
                        touch = null
                        if (toolNow == RetouchTool.HEAL) { draft = emptyList(); heal(pts) } else if (off != null) doneNow()
                    },
                    onDragCancel = { touch = null; draft = emptyList(); if (off != null) doneNow() },
                )
            },
    ) {
        val brush = sizeNow * view.width / cropNow.w // brush diameter in view pixels
        if (draft.isNotEmpty()) {
            val path = Path().apply {
                draft.forEachIndexed { i, p -> val o = px(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                if (draft.size == 1) { val o = px(draft[0]); lineTo(o.x + 0.01f, o.y) }
            }
            drawPath(path, Color(0x663FE0C5), style = Stroke(brush, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        touch?.let { drawCircle(Color.White, brush / 2f, px(it), style = Stroke(1.5.dp.toPx())) }
        if (toolNow == RetouchTool.CLONE) {
            // where the copy comes from: the chosen spot, following the brush once a stroke has started
            val spot = if (touch != null && offsetNow != null) Pt(touch!!.u + offsetNow!!.u, touch!!.v + offsetNow!!.v) else sourceNow
            spot?.let {
                val o = px(it)
                val r = 12.dp.toPx()
                drawCircle(DarkCb.mint, r, o, style = Stroke(2.dp.toPx()))
                drawLine(DarkCb.mint, Offset(o.x - r * 1.6f, o.y), Offset(o.x + r * 1.6f, o.y), 2.dp.toPx())
                drawLine(DarkCb.mint, Offset(o.x, o.y - r * 1.6f), Offset(o.x, o.y + r * 1.6f), 2.dp.toPx())
            }
        }
    }
}

@Composable
internal fun RetouchPanel(
    tool: RetouchTool, onTool: (RetouchTool) -> Unit, size: Float, onSize: (Float) -> Unit,
    sourceSet: Boolean, onNewSource: () -> Unit, canClear: Boolean, onClear: () -> Unit, onFace: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
            items(RetouchTool.entries) { Chip(it.label, tool == it, icon = when (it) { RetouchTool.HEAL -> "heal"; RetouchTool.CLONE -> "clone" }) { onTool(it) } }
            item { Chip("FACE", color = DarkCb.mint, icon = "face", onClick = onFace) }
            if (tool == RetouchTool.CLONE) item { Chip("SET SOURCE", color = DarkCb.ink, icon = "source", onClick = onNewSource) }
            if (canClear) item { Chip("CLEAR ALL", color = Danger, icon = "delete", onClick = onClear) }
        }
        Text(
            when {
                tool == RetouchTool.HEAL -> "PAINT OVER A BLEMISH. THE BEST NEARBY SPOT IS COPIED FOR YOU."
                !sourceSet -> "TAP WHERE TO COPY FROM, THEN PAINT THE SPOT TO COVER."
                else -> "PAINT OVER THE SPOT. SET SOURCE PICKS A NEW COPY POINT."
            },
            Modifier.padding(horizontal = 16.dp), style = monoStyle(DarkCb.inkDim, 0.6.sp),
        )
        EditSlider("BRUSH SIZE", size, 0.01f..0.15f, "${(size * 100).roundToInt()}", onSize, {}) {}
    }
}
