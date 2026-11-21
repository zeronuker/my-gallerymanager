package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** The picture a glass surface blurs: whatever [glassSource] draws. Needs no library, only a graphics layer (API 31+ for the blur; this app is 33+). */
internal class GlassState(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero) // where the source sits in the window
}

@Composable
internal fun rememberGlassState(): GlassState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassState(layer) }
}

/** Marks the content that glass surfaces blur: it draws as usual and is also kept in [s]. */
internal fun Modifier.glassSource(s: GlassState): Modifier = onGloballyPositioned { s.origin = it.positionInRoot() }.drawWithContent {
    s.layer.record(this, layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) { this@drawWithContent.drawContent() }
    drawLayer(s.layer)
}

/** Fills this with the source blurred, then a [tint] over it. Draw order matters: the surface must sit after the source in the same window. */
internal fun Modifier.glass(s: GlassState, shape: Shape, tint: Color, blur: Dp = 22.dp): Modifier = composed {
    val frosted = rememberGraphicsLayer()
    var pos by remember { mutableStateOf(Offset.Zero) }
    val blurPx = with(LocalDensity.current) { blur.toPx() }
    val pad = blurPx * 2 // the blur needs pictures just outside the edge too, or the rim goes pale
    onGloballyPositioned { pos = it.positionInRoot() }.clip(shape).drawBehind {
        frosted.renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
        frosted.record(this, layoutDirection, IntSize((size.width + 2 * pad).toInt(), (size.height + 2 * pad).toInt())) {
            translate(pad + s.origin.x - pos.x, pad + s.origin.y - pos.y) { drawLayer(s.layer) }
        }
        translate(-pad, -pad) { drawLayer(frosted) }
        drawRect(tint)
    }
}

/** A floating capsule of frosted glass; taps on it never reach what is behind. */
@Composable
internal fun GlassPill(glass: GlassState, modifier: Modifier = Modifier, tint: Color = LocalCb.current.surface1.copy(alpha = 0.64f), content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(modifier.glass(glass, shape, tint).border(1.dp, Color.White.copy(alpha = 0.14f), shape).pointerInput(Unit) { detectTapGestures { } }) { content() }
}
