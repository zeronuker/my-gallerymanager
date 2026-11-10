package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.claudeborne.gallerymanager.data.Settings

/**
 * The Settings shelf: a small bar on the left or right edge, about a third of the way down, on every screen it is placed over.
 * Swiping it toward the middle of the screen calls [onOpen]; a tap does too when the user turned that on. The area that takes the
 * swipe is wider and taller than the bar so a thumb can find it, and it is kept out of Android's Back gesture so the swipe is not taken for Back.
 */
@Composable
fun BoxScope.Shelf(onOpen: () -> Unit) {
    val right = Settings.shelfEdge == 1
    val tap = Settings.shelfTap
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    val moved = remember { floatArrayOf(0f) }
    // while a finger is on the bar it follows the swipe, grows and brightens; it springs back when let go
    var dragging by remember { mutableStateOf(false) }
    var pull by remember { mutableFloatStateOf(0f) }
    val shownPull by animateFloatAsState(if (dragging) pull else 0f, if (dragging) snap() else tween(MOTION_FAST_MS), label = "pull")
    val barWidth by animateDpAsState(if (dragging) 8.dp else 4.dp, tween(MOTION_FAST_MS), label = "barWidth")
    val barColor by animateColorAsState(if (dragging) Color(0xFF3FE0C5) else Color(0x803FE0C5), tween(MOTION_FAST_MS), label = "barColor")
    Column(Modifier.align(if (right) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight()) {
        Spacer(Modifier.weight(0.35f)) // the bar is centered 35% down; the touch area below is 100dp tall
        Box(
            Modifier.width(40.dp).height(100.dp)
                .systemGestureExclusion()
                .semantics { contentDescription = "Settings shelf" }
                .draggable(
                    rememberDraggableState { moved[0] += it; pull = if (right) moved[0].coerceIn(-threshold, 0f) else moved[0].coerceIn(0f, threshold) }, Orientation.Horizontal,
                    onDragStarted = { moved[0] = 0f; pull = 0f; dragging = true },
                    onDragStopped = { dragging = false; if (if (right) moved[0] < -threshold else moved[0] > threshold) onOpen() },
                )
                .then(if (tap) Modifier.clickable(remember { MutableInteractionSource() }, null) { onOpen() } else Modifier),
            if (right) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.graphicsLayer { translationX = shownPull }.size(barWidth, 72.dp).clip(RoundedCornerShape(2.dp)).background(barColor))
        }
        Spacer(Modifier.weight(0.65f))
    }
}
