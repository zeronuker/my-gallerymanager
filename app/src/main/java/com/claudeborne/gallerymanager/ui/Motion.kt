package com.claudeborne.gallerymanager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Every transition in the app comes from here, so timings stay the same everywhere (see CLAUDE.md, "Animation rule").

const val MOTION_MS = 260 // the web apps' sheets
const val MOTION_FAST_MS = 180 // and their pop-outs

// OxygenOS feel: things that arrive settle with a spring (a slight overshoot); exits stay short timed fades so they never linger.

/** Full-screen overlays (viewer, editors, Settings, Files tools): rise a little while fading. */
val overlayEnter: EnterTransition = fadeIn(tween(MOTION_MS)) + slideInVertically(spring(0.85f, 420f)) { it / 14 }
val overlayExit: ExitTransition = fadeOut(tween(MOTION_FAST_MS)) + slideOutVertically(tween(MOTION_FAST_MS)) { it / 14 }

/** Drill-in screens inside a tab (a Files tool, an album): slide from the right while fading. */
val pushEnter: EnterTransition = fadeIn(tween(MOTION_MS)) + slideInHorizontally(spring(0.85f, 420f)) { it / 8 }
val pushExit: ExitTransition = fadeOut(tween(MOTION_FAST_MS)) + slideOutHorizontally(tween(MOTION_FAST_MS)) { it / 8 }

/** Bars at the bottom (selection bars, paste bar, video controls). */
val barEnter: EnterTransition = fadeIn(tween(MOTION_FAST_MS)) + slideInVertically(spring(0.72f, 450f)) { it }
val barExit: ExitTransition = fadeOut(tween(MOTION_FAST_MS)) + slideOutVertically(tween(MOTION_FAST_MS)) { it }

/** Bars at the top (viewer top row). */
val topBarEnter: EnterTransition = fadeIn(tween(MOTION_FAST_MS)) + slideInVertically(spring(0.72f, 450f)) { -it }
val topBarExit: ExitTransition = fadeOut(tween(MOTION_FAST_MS)) + slideOutVertically(tween(MOTION_FAST_MS)) { -it }

/** Banners that push the content below them down (a working line under a header). */
val expandEnter: EnterTransition = fadeIn(tween(MOTION_FAST_MS)) + expandVertically(tween(MOTION_FAST_MS))
val shrinkExit: ExitTransition = fadeOut(tween(MOTION_FAST_MS)) + shrinkVertically(tween(MOTION_FAST_MS))

/** Menus, cards and small pop-ups. */
val popEnter: EnterTransition = fadeIn(tween(MOTION_FAST_MS)) + scaleIn(spring(0.6f, 500f), initialScale = 0.86f)
val popExit: ExitTransition = fadeOut(tween(MOTION_FAST_MS)) + scaleOut(tween(MOTION_FAST_MS), targetScale = 0.92f)

/**
 * Shows [content] for a non-null [value] with [enter]/[exit]. The last non-null value is kept while it animates out,
 * so `foo?.let { Screen(it) }` becomes `AnimatedOverlay(foo) { Screen(it) }` and still closes smoothly.
 */
@Composable
fun <T : Any> AnimatedOverlay(value: T?, enter: EnterTransition = overlayEnter, exit: ExitTransition = overlayExit, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    var last by remember { mutableStateOf(value) }
    if (value != null) last = value
    val shown = last
    Visible(value != null, modifier, enter, exit) { if (shown != null) content(shown) }
}

/** [on] colour or [off] colour, glided between. */
@Composable
fun glide(on: Boolean, onColor: Color, offColor: Color): Color = animateColorAsState(if (on) onColor else offColor, tween(MOTION_FAST_MS), label = "glide").value

/** AnimatedVisibility without a layout scope: inside a Box that sits in a Column, plain `AnimatedVisibility` resolves to the Column one and fails. */
@Composable
fun Visible(visible: Boolean, modifier: Modifier = Modifier, enter: EnterTransition, exit: ExitTransition, content: @Composable AnimatedVisibilityScope.() -> Unit) =
    AnimatedVisibility(visible, modifier, enter, exit, content = content)

/** Fades in when it first appears (a new row in a list that has no stable keys). [delayMs] staggers a group; [enabled] false skips it. */
@Composable
fun Modifier.appear(delayMs: Int = 0, enabled: Boolean = true): Modifier {
    val a = remember { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (enabled) {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs.toLong())
            a.animateTo(1f, tween(MOTION_MS))
        }
    }
    return graphicsLayer { alpha = a.value }
}

/** A bar for work in progress: [progress] 0..1 fills smoothly; null sweeps back and forth because the length of the work is not known. */
@Composable
fun ProgressBar(progress: Float?, modifier: Modifier = Modifier, color: Color = DarkCb.mint, track: Color = Color(0x33FFFFFF)) {
    val shown by animateFloatAsState(progress ?: 0f, tween(MOTION_MS, easing = LinearEasing), label = "progress")
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    Box(modifier.fillMaxWidth().height(3.dp).background(track).clipToBounds().drawBehind {
        if (progress != null) {
            drawRect(color, size = Size(size.width * shown.coerceIn(0f, 1f), size.height))
        } else {
            val w = size.width * 0.35f
            val x = -w + (size.width + w) * sweep
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, color, Color.Transparent), x, x + w), Offset(x, 0f), Size(w, size.height))
        }
    })
}

/** A line of mono text with a [ProgressBar] under it: the standard look for "something is working". */
@Composable
fun WorkingLine(text: String, progress: Float? = null, color: Color = DarkCb.mint, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        Text(text, style = monoStyle(color, 1.sp))
        ProgressBar(progress, color = color)
    }
}

/** A dimmed cover over a whole screen while it saves: the words, a bar that follows [progress] (or sweeps when null), and any [extra] (a CANCEL, a note). */
@Composable
fun BusyOverlay(visible: Boolean, text: String, progress: Float?, extra: @Composable () -> Unit = {}) {
    AnimatedVisibility(visible, Modifier.fillMaxSize(), enter = fadeIn(tween(MOTION_FAST_MS)), exit = fadeOut(tween(MOTION_MS))) {
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)).pointerInput(Unit) { detectTapGestures { } }, Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(text, style = monoStyle(DarkCb.mint, 2.6.sp))
                ProgressBar(progress, Modifier.width(220.dp))
                extra()
            }
        }
    }
}

/** Centered "LOADING" with a moving bar, for screens waiting on their content. */
@Composable
fun LoadingPane(text: String = "LOADING", progress: Float? = null, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp, androidx.compose.ui.Alignment.CenterVertically)) {
        Text(text, style = monoStyle(DarkCb.inkDim, 2.6.sp))
        ProgressBar(progress, Modifier.padding(horizontal = 48.dp))
    }
}

/** Spring used by tab pagers and other taps that glide somewhere. */
fun <T> glideSpring() = spring<T>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

/** Shrinks a little while [source] is pressed and springs back. Pair it with a `clickable(source, null)` further down the chain. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, target: Float = 0.96f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) target else 1f, spring(0.55f, 600f), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A tap target that squishes while pressed (tiles, rows): [pressScale] plus a ripple-free clickable. */
fun Modifier.pressClickable(target: Float = 0.96f, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    pressScale(source, target).clickable(source, null, onClick = onClick)
}

/**
 * A bar that floats a little: a soft shadow under it and a thin light line along its top edge. [color] is its fill.
 * (Real backdrop blur would need a library; this is the depth without it.)
 */
fun Modifier.softBar(color: Color, light: Color = Color(0x14FFFFFF)): Modifier =
    shadow(10.dp, ambientColor = Color.Black, spotColor = Color.Black).background(color).drawBehind { drawRect(light, size = Size(size.width, 1.dp.toPx())) }

/**
 * [text] such as "30 ITEMS": the leading number counts to its new value, and the words around it fade when they change.
 * Text without a leading number just fades.
 */
@Composable
fun RollingCount(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val match = remember(text) { Regex("^(\\d+)(.*)$").find(text) }
    val number = match?.groupValues?.get(1)?.toIntOrNull()
    val words = if (number != null) match.groupValues[2] else text
    val shown by animateIntAsState(number ?: 0, tween(MOTION_MS * 2, easing = FastOutSlowInEasing), label = "countRoll")
    androidx.compose.animation.AnimatedContent(words, modifier, transitionSpec = { fadeIn(tween(MOTION_FAST_MS)) togetherWith fadeOut(tween(MOTION_FAST_MS)) }, label = "countWords") { w ->
        Text(if (number != null) "$shown$w" else w, style = style)
    }
}
