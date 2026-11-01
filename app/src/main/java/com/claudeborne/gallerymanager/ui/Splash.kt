package com.claudeborne.gallerymanager.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// Ported from my-brandkit/component/SplashScreen.jsx (same timings and geometry)
private const val FADE_IN = 700
private const val MOVE_DRAW = 1600
private const val WORD_FADE = 1000
private const val HOLD = 1000L
private const val OUT = 800

private val OUTER = listOf(50f to 334f, 50f to 86f, 86f to 50f, 426f to 50f, 462f to 86f, 462f to 426f, 426f to 462f, 178f to 462f)
private val INNER = listOf(66f to 330f, 66f to 102f, 102f to 66f, 410f to 66f, 446f to 102f, 446f to 410f, 410f to 446f, 190f to 446f)

private fun DrawScope.drawPolyline(points: List<Pair<Float, Float>>, color: Color, width: Float, progress: Float) {
    val s = size.width / 512f
    val path = Path().apply {
        moveTo(points[0].first * s, points[0].second * s)
        points.drop(1).forEach { lineTo(it.first * s, it.second * s) }
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val segment = Path()
    measure.getSegment(0f, measure.length * progress, segment, true)
    drawPath(segment, color, style = Stroke(width = width * s, join = StrokeJoin.Miter))
}

@Composable
fun SplashScreen(onFinish: () -> Unit) {
    val cb = LocalCb.current
    val cAlpha = remember { Animatable(0f) }
    val move = remember { Animatable(0f) }
    val wordAlpha = remember { Animatable(0f) }
    val overlayAlpha = remember { Animatable(1f) }
    var wordWidth by remember { mutableIntStateOf(0) }
    val gap = with(LocalDensity.current) { 10.dp.toPx() }

    LaunchedEffect(Unit) {
        cAlpha.animateTo(1f, tween(FADE_IN))
        move.animateTo(1f, tween(MOVE_DRAW, easing = EaseInOut))
        wordAlpha.animateTo(1f, tween(WORD_FADE))
        delay(HOLD)
        overlayAlpha.animateTo(0f, tween(OUT, easing = LinearEasing))
        onFinish()
    }

    // tap anywhere to skip
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlayAlpha.value }
            .cbGrid(cb)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onFinish),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(64.dp).graphicsLayer {
                    alpha = cAlpha.value
                    translationX = (wordWidth + gap) / 2 * (1 - move.value)
                },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.matchParentSize()) {
                    drawPolyline(OUTER, cb.mint, 10f, move.value)
                    drawPolyline(INNER, cb.mint, 6f, move.value)
                }
                Text(
                    "C",
                    style = TextStyle(
                        fontFamily = Tourney, fontWeight = FontWeight.Bold, fontSize = 52.sp,
                        color = cb.mint, drawStyle = Stroke(width = 3f),
                    ),
                )
            }
            Column(
                // Max intrinsic width keeps the underline as wide as the text instead of filling the row
                Modifier.width(IntrinsicSize.Max).graphicsLayer { alpha = wordAlpha.value }.onSizeChanged { wordWidth = it.width },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "CLAUDEBORNE",
                    style = TextStyle(fontFamily = Tourney, fontSize = 26.sp, brush = cb.grad, drawStyle = Stroke(width = 3f)),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(cb.lineStrong))
            }
        }
    }
}
