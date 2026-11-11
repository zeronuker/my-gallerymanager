package com.claudeborne.gallerymanager.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.animateFloat
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The ClaudeBorne button, ported from brand.css `.cb-btn`: square, mono, uppercase, a faint line border that turns mint when pressed;
// the primary one is filled with the brand gradient and dark text.

/** True inside [CbDialog]: there, every [ActionText] is drawn as a brand button (mint = primary, red = destructive, anything else = secondary). */
internal val LocalInDialog = compositionLocalOf { false }

@Composable
fun BrandButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    cb: CbColors = LocalCb.current,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val red = Color(0xFFFF6B6B)
    val edge by animateColorAsState(if (pressed && enabled) cb.mint else if (danger) red.copy(alpha = 0.6f) else cb.lineStrong, tween(MOTION_FAST_MS), label = "edge")
    val ink = glide(enabled, if (primary) cb.surface0 else if (danger) red else cb.ink, cb.inkDim)
    val filled = primary && enabled // a primary that cannot be used yet is drawn as a quiet outlined button, so its words stay readable
    val framed = if (filled) modifier.background(cb.grad) else modifier.border(1.dp, edge)
    Text(
        label,
        (if (enabled) framed.clickable(press, null, onClick = onClick) else framed).padding(horizontal = 20.dp, vertical = 12.dp),
        style = monoStyle(ink, 2.2.sp).copy(fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center),
    )
}

/** A button as wide as its parent (the start screen's SEND and RECEIVE). */
@Composable
fun WideBrandButton(label: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) =
    BrandButton(label, Modifier.fillMaxWidth(), primary = primary, enabled = enabled, onClick = onClick)

/** The brand's status dot (as in the web update toast): mint with a soft halo that breathes, for "live" states such as waiting for a device. */
@Composable
fun LiveDot(modifier: Modifier = Modifier) {
    val cb = LocalCb.current
    val halo by androidx.compose.animation.core.rememberInfiniteTransition(label = "dot").animateFloat(
        0.15f, 0.45f, androidx.compose.animation.core.infiniteRepeatable(tween(1100), androidx.compose.animation.core.RepeatMode.Reverse), label = "halo",
    )
    androidx.compose.foundation.layout.Box(modifier.size(13.dp), androidx.compose.ui.Alignment.Center) {
        androidx.compose.foundation.layout.Box(Modifier.size(13.dp).background(cb.mint.copy(alpha = halo), androidx.compose.foundation.shape.CircleShape))
        androidx.compose.foundation.layout.Box(Modifier.size(7.dp).background(cb.mint, androidx.compose.foundation.shape.CircleShape))
    }
}

/** A live status line: the dot and the words. */
@Composable
fun WaitingLine(text: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        LiveDot()
        Text(text, style = monoStyle(LocalCb.current.inkDim, 1.sp))
    }
}

/** The small gap dialogs leave around each button. */
internal val DialogButtonGap = Modifier.padding(horizontal = 4.dp)

/** The close button of a full-screen editor: a red cross, big, with a 48dp touch target. */
@Composable
fun CloseButton(onClick: () -> Unit) {
    Box(Modifier.size(48.dp).semantics { contentDescription = "Close" }.clickable(onClick = onClick), Alignment.Center) {
        CbIcon(Icon.CLOSE, RED, size = 28.dp)
    }
}
