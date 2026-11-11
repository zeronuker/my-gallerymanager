package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Icon { SHARE, HEART, EDIT, TRASH, INFO, MORE, SEARCH, FOLDER, CHEVRON, CHECK }

/** Line icons drawn on a 24 x 24 grid, so the app needs no icon library and they match the brand's thin outlines. */
@Composable
fun CbIcon(icon: Icon, color: Color, modifier: Modifier = Modifier, filled: Boolean = false, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        val line = Stroke(1.8f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * k, y * k)
        fun DrawScope.seg(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), 1.8f * k, StrokeCap.Round)
        fun DrawScope.shape(path: Path, fill: Boolean = false) = drawPath(path, color, style = if (fill) Fill else line)

        when (icon) {
            Icon.SHARE -> {
                seg(8.2f, 10.9f, 15.8f, 6.1f)
                seg(8.2f, 13.1f, 15.8f, 17.9f)
                for ((x, y) in listOf(18f to 5f, 6f to 12f, 18f to 19f)) drawCircle(color, 2.7f * k, p(x, y), style = line)
            }
            Icon.HEART -> shape(
                Path().apply {
                    moveTo(12f * k, 21.35f * k); lineTo(10.55f * k, 20.03f * k)
                    cubicTo(5.4f * k, 15.36f * k, 2f * k, 12.28f * k, 2f * k, 8.5f * k)
                    cubicTo(2f * k, 5.42f * k, 4.42f * k, 3f * k, 7.5f * k, 3f * k)
                    cubicTo(9.24f * k, 3f * k, 10.91f * k, 3.81f * k, 12f * k, 5.09f * k)
                    cubicTo(13.09f * k, 3.81f * k, 14.76f * k, 3f * k, 16.5f * k, 3f * k)
                    cubicTo(19.58f * k, 3f * k, 22f * k, 5.42f * k, 22f * k, 8.5f * k)
                    cubicTo(22f * k, 12.28f * k, 18.6f * k, 15.36f * k, 13.45f * k, 20.04f * k)
                    close()
                },
                fill = filled,
            )
            Icon.EDIT -> {
                shape(Path().apply {
                    moveTo(4f * k, 20f * k); lineTo(4f * k, 16f * k); lineTo(15.5f * k, 4.5f * k); lineTo(19.5f * k, 8.5f * k); lineTo(8f * k, 20f * k); close()
                })
                seg(13f, 7f, 17f, 11f)
            }
            Icon.TRASH -> {
                seg(4f, 7f, 20f, 7f)
                shape(Path().apply { moveTo(9f * k, 7f * k); lineTo(9f * k, 4f * k); lineTo(15f * k, 4f * k); lineTo(15f * k, 7f * k) })
                shape(Path().apply { moveTo(6f * k, 7f * k); lineTo(7f * k, 20f * k); lineTo(17f * k, 20f * k); lineTo(18f * k, 7f * k) })
                seg(10f, 11f, 10f, 16f)
                seg(14f, 11f, 14f, 16f)
            }
            Icon.INFO -> {
                drawCircle(color, 9.5f * k, p(12f, 12f), style = line)
                seg(12f, 11f, 12f, 17f)
                drawCircle(color, 1.1f * k, p(12f, 7.6f))
            }
            Icon.MORE -> for (y in listOf(5f, 12f, 19f)) drawCircle(color, 1.7f * k, p(12f, y)) // three dots, one above the other
            Icon.SEARCH -> {
                drawCircle(color, 6.5f * k, p(10.5f, 10.5f), style = line)
                seg(15.3f, 15.3f, 20.5f, 20.5f)
            }
            Icon.FOLDER -> shape(Path().apply {
                moveTo(3f * k, 7f * k); lineTo(3f * k, 18f * k); lineTo(21f * k, 18f * k); lineTo(21f * k, 9f * k)
                lineTo(12f * k, 9f * k); lineTo(10f * k, 6f * k); lineTo(4f * k, 6f * k); close()
            })
            Icon.CHEVRON -> shape(Path().apply { moveTo(9f * k, 5f * k); lineTo(16f * k, 12f * k); lineTo(9f * k, 19f * k) })
            Icon.CHECK -> shape(Path().apply { moveTo(5f * k, 12.5f * k); lineTo(10f * k, 17.5f * k); lineTo(19f * k, 7f * k) })
        }
    }
}

/** An icon as a tappable 48dp target; [description] is read out by screen readers. */
@Composable
fun IconAction(icon: Icon, color: Color, description: String, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).semantics { contentDescription = description }.clickable(enabled = enabled, onClick = onClick),
        Alignment.Center,
    ) { CbIcon(icon, color, filled = filled, size = 26.dp) }
}
