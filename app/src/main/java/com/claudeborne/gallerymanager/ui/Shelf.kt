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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    Column(Modifier.align(if (right) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight()) {
        Spacer(Modifier.weight(0.35f)) // the bar is centered 35% down; the touch area below is 100dp tall
        Box(
            Modifier.width(40.dp).height(100.dp)
                .systemGestureExclusion()
                .semantics { contentDescription = "Settings shelf" }
                .draggable(
                    rememberDraggableState { moved[0] += it }, Orientation.Horizontal,
                    onDragStarted = { moved[0] = 0f },
                    onDragStopped = { if (if (right) moved[0] < -threshold else moved[0] > threshold) onOpen() },
                )
                .then(if (tap) Modifier.clickable(remember { MutableInteractionSource() }, null) { onOpen() } else Modifier),
            if (right) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(4.dp, 72.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x803FE0C5)))
        }
        Spacer(Modifier.weight(0.65f))
    }
}
