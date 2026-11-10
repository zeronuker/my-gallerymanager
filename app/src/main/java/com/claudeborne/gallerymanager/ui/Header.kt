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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
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

/** Brand banner on the left; screen title with its count underneath on the right. An [onBack] adds a back link on the left, under the banner. */
@Composable
fun ScreenHeader(title: String, count: String, onBack: (() -> Unit)? = null, backLabel: String = "") {
    val cb = LocalCb.current
    Column {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandBanner()
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(
                    title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Tourney, fontWeight = FontWeight.Bold, fontSize = 28.sp, brush = cb.grad),
                )
                Text(count, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.sp, color = cb.inkDim))
            }
        }
        if (onBack != null) {
            Text(
                "← $backLabel", Modifier.clickable(onClick = onBack).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.6.sp, color = cb.mint),
            )
        }
    }
}
