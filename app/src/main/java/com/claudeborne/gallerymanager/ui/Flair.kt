package com.claudeborne.gallerymanager.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The "flair" layer: depth, spring motion, press feedback and haptics, so the brand look feels alive like OxygenOS / ColorOS.
// Colours all come from the brand tokens (mint / blue / violet at lower opacity); nothing here adds a hex colour.

/** The one spring the app uses for things that settle: a little overshoot, then rest. */
fun <T> cbSpring(bouncy: Boolean = true) = spring<T>(
    dampingRatio = if (bouncy) 0.62f else Spring.DampingRatioNoBouncy,
    stiffness = if (bouncy) 420f else 380f,
)

/** The press spring: quick and tight, so a tile snaps in under the finger and bounces back out. */
private val pressSpring = spring<Float>(dampingRatio = 0.55f, stiffness = 650f)

/**
 * Shrinks the element a little while a finger is down on it and springs back on release (OxygenOS style).
 * It only watches the touch and never consumes it, so the clickable it wraps keeps working; scrolling lets go of the squish.
 */
@Composable
fun Modifier.pressScale(pressedScale: Float = 0.95f): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (pressed) pressedScale else 1f, pressSpring, label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            pressed = true
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Final)
                // a finger that lifted, or moved while something (a list scroll) took the drag: let go
                if (e.changes.all { it.changedToUp() || !it.pressed } || e.changes.any { it.isConsumed && it.positionChange() != Offset.Zero }) break
            }
            pressed = false
        }
    }
}

/** A small tick on the phone: a selection started, a tab switched, a button pressed. */
@Composable
fun rememberTick(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
}

/** A firmer buzz, for a long press that begins a selection. */
@Composable
fun rememberThump(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }
}

/**
 * A soft coloured halo under an element, as a real elevation shadow tinted with a brand colour.
 * [color] is usually mint (the brand light); on light theme the halo is quieter.
 */
fun Modifier.cbGlow(color: Color, shape: Shape, elevation: Dp = 12.dp, strength: Float = 0.55f): Modifier =
    shadow(elevation, shape, clip = false, ambientColor = color.copy(alpha = strength * 0.5f), spotColor = color.copy(alpha = strength))

/** A faint light edge along the top of a surface, like glass catching light. Drawn over the surface's own fill. */
fun Modifier.topSheen(alpha: Float = 0.10f): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = alpha), Color.Transparent), 0f, size.height * 0.6f))
    drawLine(Color.White.copy(alpha = alpha * 1.6f), Offset.Zero, Offset(size.width, 0f), 1f)
}

/** Soft mint glow from the top left and violet glow from the bottom right, behind everything: the screen is no longer a flat sheet. */
fun Modifier.ambientGlow(cb: CbColors): Modifier = drawBehind {
    val a = if (cb.isDark) 0.16f else 0.10f
    val r = size.maxDimension * 0.75f
    drawRect(Brush.radialGradient(listOf(cb.mint.copy(alpha = a), Color.Transparent), Offset(0f, size.height * 0.05f), r))
    drawRect(Brush.radialGradient(listOf(cb.violet.copy(alpha = a), Color.Transparent), Offset(size.width, size.height * 0.95f), r))
}

/** The chamfered outline of the brand frames as a [Shape], so shadows and clips can follow it. */
@Composable
fun rememberChamferShape(cutDp: Float = 10f): Shape {
    val d = LocalDensity.current.density
    return remember(d, cutDp) { GenericShape { size, _ -> addPath(chamfered(size.width, size.height, 0.75f * d, cutDp * d)) } }
}
