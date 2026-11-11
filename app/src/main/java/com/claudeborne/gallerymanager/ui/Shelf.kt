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
 * The Settings shelf: a small bar on the left or right edge, placed and sized by the user in Settings, on every screen it is placed over.
 * Swiping it toward the middle of the screen calls [onOpen]; a tap does too when the user turned that on. The area that takes the swipe is
 * wider and taller than the bar so a thumb can find it, and it is kept out of Android's Back gesture so the swipe is not taken for Back.
 * With auto-hide the bar fades out after a few seconds and comes back when a finger touches near it; the swipe area works either way.
 * A [preview] only draws the bar (always visible) so it can be shown over Settings while it is adjusted.
 */
@Composable
fun Shelf(onOpen: () -> Unit = {}, preview: Boolean = false) {
    val right = Settings.shelfEdge == 1
    val tap = Settings.shelfTap
    val inset = Settings.shelfInset.dp
    val thickness = Settings.shelfThickness
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    val moved = remember { floatArrayOf(0f) }
    // while a finger is on the bar it follows the swipe, grows and brightens; it springs back when let go
    var dragging by remember { mutableStateOf(false) }
    var pull by remember { mutableFloatStateOf(0f) }
    var touches by remember { mutableIntStateOf(0) }
    var hidden by remember { mutableStateOf(false) }
    // every touch near the bar shows it again and restarts the countdown
    LaunchedEffect(touches, Settings.shelfAutoHide, preview) {
        hidden = false
        if (Settings.shelfAutoHide && !preview) { delay(HIDE_AFTER_MS); hidden = true }
    }
    val shownPull by animateFloatAsState(if (dragging) pull else 0f, if (dragging) snap() else tween(MOTION_FAST_MS), label = "pull")
    val fade by animateFloatAsState(if (hidden && !dragging) 0f else 1f, tween(MOTION_MS), label = "shelfFade")
    val barWidth by animateDpAsState((if (dragging) thickness * 2 else thickness).dp, tween(MOTION_FAST_MS), label = "barWidth")
    val barColor by animateColorAsState(if (dragging) Color(0xFF3FE0C5) else Color(0x803FE0C5), tween(MOTION_FAST_MS), label = "barColor")

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val touchH = maxOf(Settings.shelfHeight + 24, 100).dp
        val touchW = inset + (thickness * 2).dp + 24.dp
        val top = (maxHeight * (Settings.shelfY / 100f) - touchH / 2).coerceIn(0.dp, maxOf(0.dp, maxHeight - touchH))
        val touch = if (preview) Modifier else Modifier
            .systemGestureExclusion()
            .semantics { contentDescription = "Settings shelf" }
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); touches++ } }
            .draggable(
                rememberDraggableState { moved[0] += it; pull = if (right) moved[0].coerceIn(-threshold, 0f) else moved[0].coerceIn(0f, threshold) }, Orientation.Horizontal,
                onDragStarted = { moved[0] = 0f; pull = 0f; dragging = true },
                onDragStopped = { dragging = false; if (if (right) moved[0] < -threshold else moved[0] > threshold) onOpen() },
            )
            .then(if (tap) Modifier.clickable(remember { MutableInteractionSource() }, null) { onOpen() } else Modifier)
        Box(
            Modifier.align(if (right) Alignment.TopEnd else Alignment.TopStart).offset(y = top).width(touchW).height(touchH).then(touch),
            if (right) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier.padding(if (right) PaddingValues(end = inset) else PaddingValues(start = inset))
                    .graphicsLayer { translationX = shownPull; alpha = fade }
                    .size(barWidth, Settings.shelfHeight.dp).clip(RoundedCornerShape(2.dp)).background(barColor),
            )
        }
    }
}
