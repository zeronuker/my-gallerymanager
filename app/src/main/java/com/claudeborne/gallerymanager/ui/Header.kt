package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ported from my-brandkit/component/BrandBanner.jsx, mobile (<=640px) sizes. */
@Composable
private fun BrandBanner() {
    val cb = LocalCb.current
    val density = LocalDensity.current
    Row(Modifier.padding(horizontal = 12.dp, vertical = 1.dp), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
        Box(Modifier.size(64.dp), Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                drawPolyline(OUTER, cb.mint, 10f, 1f)
                drawPolyline(INNER, cb.mint, 6f, 1f)
            }
            Text("C", style = TextStyle(fontFamily = Tourney, fontWeight = FontWeight.Bold, fontSize = 58.sp, color = cb.mint, drawStyle = Stroke(with(density) { 2.dp.toPx() })))
        }
        Column(Modifier.width(IntrinsicSize.Max), Arrangement.spacedBy(1.dp), Alignment.CenterHorizontally) {
            Box(Modifier.size(160.dp, 20.dp), Alignment.Center) {
                Text(
                    "CLAUDEBORNE",
                    style = TextStyle(
                        fontFamily = Tourney, fontSize = 16.6.sp,
                        brush = Brush.horizontalGradient(0f to cb.mint, 0.55f to cb.blue, 1f to cb.violet),
                        drawStyle = Stroke(with(density) { 0.6.dp.toPx() }),
                    ),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(cb.lineStrong))
            Text(
                "ALTUS CIRRUS", Modifier.fillMaxWidth(), maxLines = 1,
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 4.sp, color = cb.blue, textAlign = TextAlign.Center),
            )
        }
    }
}

/** What the fixed header above the main pages shows; the page on show fills it in (see [ScreenHeader] with `fixed`). */
class HeaderInfo {
    var title by mutableStateOf("")
    var count by mutableStateOf("")
    var onBack by mutableStateOf<(() -> Unit)?>(null)
    var backLabel by mutableStateOf("")
    var trailing by mutableStateOf<(@Composable () -> Unit)?>(null)
}

val LocalHeaderInfo = compositionLocalOf<HeaderInfo?> { null }

/** False for a page that is only half in view while swiping: only the page on show fills in the fixed header. */
val LocalPageActive = compositionLocalOf { true }

/**
 * A screen's header. With [fixed] (the main pages) nothing is drawn here: the title, count and back link go to the one header that stays
 * put above the swiping pages. Without it (a Files tool, Trash, Settings) the screen draws its own.
 * [trailing] (view, sort, empty trash...) sits at the right end of the back-link row, so it only shows while there is a back link.
 */
@Composable
fun ScreenHeader(title: String, count: String, onBack: (() -> Unit)? = null, backLabel: String = "", fixed: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val info = LocalHeaderInfo.current
    val active = LocalPageActive.current
    if (fixed && info != null) {
        SideEffect { if (active) { info.title = title; info.count = count; info.onBack = onBack; info.backLabel = backLabel; info.trailing = trailing } }
        return
    }
    HeaderBar(title, count, onBack, backLabel, trailing)
}

/** Brand banner on the left; screen title with its count underneath on the right. An [onBack] adds a back link on the left, under the banner. */
@Composable
fun HeaderBar(title: String, count: String, onBack: (() -> Unit)?, backLabel: String, trailing: (@Composable () -> Unit)? = null) {
    val cb = LocalCb.current
    // the back link slides away when there is nothing to go back to; it keeps its last words, action and buttons while it does
    var lastLabel by remember { mutableStateOf(backLabel) }
    var lastBack by remember { mutableStateOf(onBack) }
    var lastTrailing by remember { mutableStateOf(trailing) }
    if (onBack != null) { lastLabel = backLabel; lastBack = onBack; lastTrailing = trailing }
    Column {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandBanner()
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                AnimatedContent(title, transitionSpec = { fadeIn(tween(MOTION_FAST_MS)) togetherWith fadeOut(tween(MOTION_FAST_MS)) }, label = "title") { text ->
                    Text(
                        text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Tourney, fontWeight = FontWeight.Bold, fontSize = 28.sp, brush = cb.grad),
                    )
                }
                // the count fades when it changes (ITEMS to SELECTED, a scan finishing, a speed appearing)
                AnimatedContent(count, transitionSpec = { fadeIn(tween(MOTION_FAST_MS)) togetherWith fadeOut(tween(MOTION_FAST_MS)) }, label = "count") { text ->
                    Text(text, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.sp, color = cb.inkDim))
                }
            }
        }
        Visible(onBack != null, enter = expandEnter, exit = shrinkExit) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "← $lastLabel", Modifier.clickable { lastBack?.invoke() }.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp, letterSpacing = 2.6.sp, color = cb.mint),
                )
                Box(Modifier.weight(1f))
                lastTrailing?.invoke()
            }
        }
    }
}
