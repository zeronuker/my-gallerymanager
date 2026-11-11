package com.claudeborne.gallerymanager.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val SHOW_MS = 1200L
private val MIN_THUMB = 40.dp

/**
 * A scroll bar for a lazy list or grid: a thin mint bar on the right edge that fades in while the list moves and can be dragged to jump.
 * It works on item positions, so it needs only the item count, the first and last visible index, and a way to scroll to an item.
 * Place it in a Box over the list, aligned to the end.
 */
@Composable
internal fun DragScrollBar(total: Int, first: Int, visible: Int, scrollTo: suspend (Int) -> Unit, modifier: Modifier = Modifier) {
    if (total <= visible || total == 0) return
    val cb = LocalCb.current
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(first, dragging) {
        shown = true
        if (!dragging) { delay(SHOW_MS); shown = false }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(MOTION_FAST_MS), label = "scrollBarFade")
    val width by animateDpAsState(if (dragging) 8.dp else 4.dp, tween(MOTION_FAST_MS), label = "scrollBarWidth")

    BoxWithConstraints(modifier.fillMaxHeight().width(24.dp)) {
        val trackPx = with(LocalDensity.current) { maxHeight.toPx() }
        val thumbPx = (trackPx * visible / total).coerceAtLeast(with(LocalDensity.current) { MIN_THUMB.toPx() })
        val travel = (trackPx - thumbPx).coerceAtLeast(1f)
        val frac = (first / (total - visible).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        // read through state so the drag keeps going while the list scrolls under it (changing values must not restart the gesture)
        val jump by rememberUpdatedState<(Float) -> Unit> { y ->
            val f = ((y - thumbPx / 2) / travel).coerceIn(0f, 1f)
            scope.launch { scrollTo((f * (total - visible)).roundToInt()) }
        }
        Box(
            // the bar sits in the edge zone where the phone's back gesture lives, so the system must leave it alone
            Modifier.fillMaxHeight().width(24.dp).systemGestureExclusion().pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true; jump(it.y) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, _ -> change.consume(); jump(change.position.y) },
                )
            },
        ) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(end = 4.dp)
                    .offset { androidx.compose.ui.unit.IntOffset(0, (frac * travel).roundToInt()) }
                    .graphicsLayer { this.alpha = alpha }
                    .width(width).height(with(LocalDensity.current) { thumbPx.toDp() }).clip(RoundedCornerShape(4.dp)).background(cb.mint),
            )
        }
    }
}
