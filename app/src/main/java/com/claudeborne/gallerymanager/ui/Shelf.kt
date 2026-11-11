package com.claudeborne.gallerymanager.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.claudeborne.gallerymanager.data.Settings
import kotlinx.coroutines.delay

private const val HIDE_AFTER_MS = 3000L

/**
 * The Settings shelf: a small bar on the left or right edge, placed and sized by the user in Settings. Dragging it toward the middle of the
 * screen pulls the Settings window in under the finger: [onDrag] gets how much of the window shows (0 to 1) and [onRelease] whether the drag
 * went far enough to switch. A tap switches too when the user turned that on, and always while Settings is [open], when this same bar is the
 * way to close it (tap it, or drag it back to the edge). Slide or tap, the bar does the same: while the window moves ([moving]) or is dragged
 * it brightens and gets longer when opening, shorter when closing; its thickness never changes, and it never leaves the edge.
 * With auto-hide the bar fades out after a few seconds and comes back when a finger touches near it; the swipe area works either way.
 */
@Composable
fun Shelf(open: Boolean, moving: Boolean, onDrag: (Float) -> Unit, onRelease: (Boolean) -> Unit, onToggle: () -> Unit) {
    val right = Settings.shelfEdge == 1
    val tap = Settings.shelfTap
    val inset = Settings.shelfInset.dp
    val thickness = Settings.shelfThickness
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    val closeThreshold = with(LocalDensity.current) { 12.dp.toPx() } // closing drags toward the edge, which is right there, so it is short
    val moved = remember { floatArrayOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var touches by remember { mutableIntStateOf(0) }
    var hidden by remember { mutableStateOf(false) }
    // every touch near the bar shows it again and restarts the countdown; while Settings is open the bar stays, so the sliders can be judged
    LaunchedEffect(touches, Settings.shelfAutoHide, open) {
        hidden = false
        if (Settings.shelfAutoHide && !open) { delay(HIDE_AFTER_MS); hidden = true }
    }
    val lit = dragging || moving
    val opening = if (dragging) !open else open // a drag starts from the state it is leaving; the slide after it is already in the new state
    val fade by animateFloatAsState(if (hidden && !lit && !open) 0f else 1f, tween(MOTION_MS), label = "shelfFade")
    val length by animateFloatAsState(if (!lit) 1f else if (opening) 1.25f else 0.8f, tween(MOTION_FAST_MS), label = "barLength")
    val barColor by animateColorAsState(if (lit) Color(0xFF3FE0C5) else Color(0x803FE0C5), tween(MOTION_FAST_MS), label = "barColor")

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val touchH = ((Settings.shelfHeight * 1.25f).toInt() + 24).coerceAtLeast(100).dp
        // closed: a wide strip at the edge so a thumb can find it; open: a little narrower, so Settings' own controls stay usable. Both reach the edge.
        val touchW = inset + (if (open) thickness + 28 else thickness * 2 + 24).dp
        val top = (maxHeight * (Settings.shelfY / 100f) - touchH / 2).coerceIn(0.dp, maxOf(0.dp, maxHeight - touchH))
        val touch = Modifier
            .systemGestureExclusion()
            .semantics { contentDescription = if (open) "Close Settings" else "Settings shelf" }
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); touches++ } }
            .draggable(
                rememberDraggableState {
                    moved[0] += it
                    val toward = if (right) -moved[0] else moved[0] // positive: toward the middle of the screen
                    onDrag(((if (open) 1f else 0f) + toward / widthPx).coerceIn(0f, 1f)) // the window shows as much as the finger has pulled
                },
                Orientation.Horizontal,
                onDragStarted = { moved[0] = 0f; dragging = true },
                onDragStopped = {
                    dragging = false
                    val toward = if (right) -moved[0] else moved[0]
                    onRelease(if (open) toward < -closeThreshold else toward > threshold)
                },
            )
            .then(if (tap || open) Modifier.clickable(remember { MutableInteractionSource() }, null) { onToggle() } else Modifier)
        Box(
            Modifier.align(if (right) Alignment.TopEnd else Alignment.TopStart).offset(y = top)
                .width(touchW).height(touchH).then(touch),
            if (right) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier.padding(if (right) PaddingValues(end = inset) else PaddingValues(start = inset))
                    .graphicsLayer { alpha = fade }
                    .size(thickness.dp, (Settings.shelfHeight * length).dp).clip(RoundedCornerShape(2.dp)).background(barColor),
            )
        }
    }
}
