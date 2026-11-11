package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The editor, trash and transfer icons, kept as the small SVG shapes they were designed as (24 x 24 grid, 1.7 line) and drawn by [SvgIcon].
// Only path, circle and rect elements with the few attributes used below are understood.

private const val TR = "<path d=\"M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13\"/>"
private const val PEN = "<path d=\"M4 20l1-4L16 5l3 3L8 19z\"/><path d=\"M14 7l3 3\"/>"
private const val CROP = "<path d=\"M6 2v16h16M2 6h16v16\"/>"
private const val ROTATE = "<path d=\"M20 12a8 8 0 1 1-2.5-5.8\"/><path d=\"M20 4v5h-5\"/>"
private const val OK = "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M8 12.5l3 3 5-6\"/>"
private const val NO = "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M9 9l6 6M15 9l-6 6\"/>"

private val ICONS: Map<String, String> = mapOf(
    // trash
    "restore" to "<path d=\"M4 12a8 8 0 1 0 2.5-5.8\"/><path d=\"M4 4v5h5\"/>",
    "deleteForever" to TR + "<path d=\"M10 11l4 5M14 11l-4 5\"/>",
    // tool tabs
    "crop" to CROP,
    "adjust" to "<path d=\"M4 7h10M18 7h2M4 17h2M10 17h10\"/><circle cx=\"16\" cy=\"7\" r=\"2\"/><circle cx=\"8\" cy=\"17\" r=\"2\"/>",
    "filters" to "<path d=\"M12 3l1.8 4.7L18.5 9.5 13.8 11.3 12 16l-1.8-4.7L5.5 9.5l4.7-1.8z\"/><path d=\"M18 15l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z\"/>",
    "markup" to PEN,
    "retouch" to "<rect x=\"3\" y=\"9\" width=\"18\" height=\"6\" rx=\"3\" transform=\"rotate(-45 12 12)\"/><path d=\"M10.5 10.5l3 3\"/>",
    "cutout" to "<circle cx=\"6\" cy=\"6\" r=\"2.5\"/><circle cx=\"6\" cy=\"18\" r=\"2.5\"/><path d=\"M8 7.5L20 18M8 16.5L20 6\"/>",
    "trim" to "<rect x=\"3\" y=\"8\" width=\"18\" height=\"8\"/><rect x=\"8\" y=\"8\" width=\"8\" height=\"8\" fill=\"currentColor\" fill-opacity=\".4\"/><path d=\"M8 5v14M16 5v14\"/>",
    "speed" to "<path d=\"M3 6l8 6-8 6zM12 6l8 6-8 6z\"/>",
    "audio" to "<path d=\"M4 9.5h4l5-4.5v14l-5-4.5H4z\"/><path d=\"M16 9a4 4 0 0 1 0 6M18.5 6.5a8 8 0 0 1 0 11\"/>",
    "vrotate" to "<rect x=\"7\" y=\"3\" width=\"7\" height=\"12\" rx=\"1\" stroke-dasharray=\"2 2\"/><rect x=\"4\" y=\"12\" width=\"12\" height=\"8\" rx=\"1\"/><path d=\"M17 4a5 5 0 0 1 4 5M21 5v4h-4\"/>",
    "look" to "<path d=\"M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z\"/><circle cx=\"12\" cy=\"12\" r=\"3\"/>",
    "fade" to "<path d=\"M5 6v12\"/><path d=\"M9 6v12\" stroke-opacity=\".75\"/><path d=\"M13 6v12\" stroke-opacity=\".5\"/><path d=\"M17 6v12\" stroke-opacity=\".25\"/>",
    "freeze" to "<rect x=\"4\" y=\"5\" width=\"16\" height=\"14\" rx=\"1\"/><path d=\"M10 9v6M14 9v6\"/>",
    "compress" to "<path d=\"M4 4l6 6M10 6v4H6M20 4l-6 6M14 6v4h4M4 20l6-6M10 18v-4H6M20 20l-6-6M14 18v-4h4\"/>",
    // top row and collage
    "undo" to "<path d=\"M3 10h11a5 5 0 0 1 0 10H8\"/><path d=\"M7 6L3 10l4 4\"/>",
    "resetAll" to "<path d=\"M4 12a8 8 0 1 0 2.5-5.8\"/><path d=\"M4 4v5h5\"/><path d=\"M12 8v4l3 2\"/>",
    "redo" to "<path d=\"M21 10H10a5 5 0 0 0 0 10h6\"/><path d=\"M17 6l4 4-4 4\"/>",
    "swap" to "<rect x=\"3\" y=\"4\" width=\"7\" height=\"7\"/><rect x=\"14\" y=\"13\" width=\"7\" height=\"7\"/><path d=\"M12 6h6M16 3l3 3-3 3M12 18H6M8 15l-3 3 3 3\"/>",
    // inside the tools
    "rotate" to ROTATE,
    "flipH" to "<path d=\"M12 3v18\" stroke-dasharray=\"2 2\"/><path d=\"M9 7L3 17h6z\"/><path d=\"M15 7l6 10h-6z\"/>",
    "flipV" to "<path d=\"M3 12h18\" stroke-dasharray=\"2 2\"/><path d=\"M7 9l5-6 5 6z\"/><path d=\"M7 15l5 6 5-6z\"/>",
    "reset" to ROTATE,
    "free" to "<rect x=\"5\" y=\"5\" width=\"14\" height=\"14\" stroke-dasharray=\"3 2\"/><path d=\"M3 3h3M3 3v3M21 21h-3M21 21v-3\"/>",
    "orig" to "<rect x=\"3\" y=\"5\" width=\"18\" height=\"14\" rx=\"1\"/><path d=\"M9 10l3-2v8\"/>",
    "all" to "<rect x=\"4\" y=\"4\" width=\"16\" height=\"16\" rx=\"2\" fill=\"currentColor\" fill-opacity=\".35\"/>",
    "subject" to "<circle cx=\"12\" cy=\"9\" r=\"3.5\" fill=\"currentColor\" fill-opacity=\".4\"/><path d=\"M5 20c0-4 3-6 7-6s7 2 7 6z\" fill=\"currentColor\" fill-opacity=\".4\"/>",
    "back" to "<rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"2\"/><circle cx=\"12\" cy=\"10\" r=\"3\" stroke-dasharray=\"2 2\"/><path d=\"M7 19c0-3 2-5 5-5s5 2 5 5\" stroke-dasharray=\"2 2\"/>",
    "auto" to "<path d=\"M12 3l1.8 4.7L18.5 9.5 13.8 11.3 12 16l-1.8-4.7L5.5 9.5l4.7-1.8z\"/><path d=\"M18 15l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z\"/>",
    "levels" to "<path d=\"M4 20v-8M8 20V6M12 20v-6M16 20V4M20 20v-9\"/>",
    "curves" to "<path d=\"M3 21C9 21 8 3 21 3\"/><path d=\"M3 21h18M3 21V3\" opacity=\".4\"/>",
    "colour" to "<path d=\"M12 3a9 9 0 1 0 0 18c1.5 0 2-1 2-2s-1-1.5 0-3 3-1 5-1c1.5 0 2-1 2-2a9 9 0 0 0-9-10z\"/><circle cx=\"8\" cy=\"11\" r=\"1\"/><circle cx=\"12\" cy=\"7\" r=\"1\"/><circle cx=\"16\" cy=\"9\" r=\"1\"/>",
    "exposure" to "<rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"2\"/><path d=\"M7 9h4M9 7v4M13 16h4\"/>",
    "bright" to "<circle cx=\"12\" cy=\"12\" r=\"4\"/><path d=\"M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4\"/>",
    "contrast" to "<circle cx=\"12\" cy=\"12\" r=\"8\"/><path d=\"M12 4a8 8 0 0 1 0 16z\" fill=\"currentColor\"/>",
    "highlights" to "<circle cx=\"12\" cy=\"12\" r=\"7\"/><path d=\"M12 5a7 7 0 0 1 7 7h-7z\" fill=\"currentColor\"/>",
    "shadows" to "<circle cx=\"12\" cy=\"12\" r=\"7\"/><path d=\"M12 19a7 7 0 0 1-7-7h7z\" fill=\"currentColor\"/>",
    "whites" to "<circle cx=\"12\" cy=\"12\" r=\"6\"/><path d=\"M12 2v2M12 20v2M2 12h2M20 12h2\"/>",
    "blacks" to "<circle cx=\"12\" cy=\"12\" r=\"7\" fill=\"currentColor\"/>",
    "sat" to "<path d=\"M12 3s6 6 6 11a6 6 0 0 1-12 0c0-5 6-11 6-11z\"/><path d=\"M12 3s-6 6-6 11a6 6 0 0 0 6 6z\" fill=\"currentColor\"/>",
    "vib" to "<path d=\"M12 5s5 5 5 9a5 5 0 0 1-10 0c0-4 5-9 5-9z\"/><path d=\"M19 2v4M17 4h4\"/>",
    "warm" to "<path d=\"M10 14V5a2 2 0 0 1 4 0v9a4 4 0 1 1-4 0z\"/><path d=\"M12 9v8\"/>",
    "tint" to "<circle cx=\"9\" cy=\"12\" r=\"5\"/><circle cx=\"15\" cy=\"12\" r=\"5\"/>",
    "sharp" to "<path d=\"M12 4l8 16H4z\"/>",
    "clarity" to "<circle cx=\"12\" cy=\"12\" r=\"3\"/><path d=\"M12 3v3M12 18v3M3 12h3M18 12h3\"/>",
    "vig" to "<rect x=\"3\" y=\"5\" width=\"18\" height=\"14\" rx=\"4\"/><circle cx=\"12\" cy=\"12\" r=\"3.5\"/>",
    "grain" to "<circle cx=\"6\" cy=\"6\" r=\".9\" fill=\"currentColor\"/><circle cx=\"12\" cy=\"5\" r=\".9\" fill=\"currentColor\"/><circle cx=\"18\" cy=\"7\" r=\".9\" fill=\"currentColor\"/><circle cx=\"8\" cy=\"11\" r=\".9\" fill=\"currentColor\"/><circle cx=\"15\" cy=\"12\" r=\".9\" fill=\"currentColor\"/><circle cx=\"5\" cy=\"17\" r=\".9\" fill=\"currentColor\"/><circle cx=\"11\" cy=\"18\" r=\".9\" fill=\"currentColor\"/><circle cx=\"18\" cy=\"17\" r=\".9\" fill=\"currentColor\"/>",
    "pen" to PEN,
    "marker" to "<path d=\"M5 19h6M7 15l8-8 3 3-8 8H7z\"/>",
    "eraser" to "<path d=\"M7 17l-3-3 9-9 7 7-6 6H9z\"/><path d=\"M9 11l5 5\"/>",
    "text" to "<path d=\"M5 7V4h14v3M12 4v16M9 20h6\"/>",
    "sticker" to "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M8 14a4 4 0 0 0 8 0\"/><circle cx=\"9\" cy=\"10\" r=\".8\" fill=\"currentColor\"/><circle cx=\"15\" cy=\"10\" r=\".8\" fill=\"currentColor\"/>",
    "editText" to "<path d=\"M4 7V4h12v3M10 4v10M7 14h6\"/><path d=\"M14 20l1-3 5-5 2 2-5 5z\"/>",
    "delete" to TR + "<path d=\"M10 11v5M14 11v5\"/>",
    "heal" to "<rect x=\"3\" y=\"9\" width=\"18\" height=\"6\" rx=\"3\" transform=\"rotate(-45 12 12)\"/><path d=\"M10.5 10.5l3 3\"/>",
    "clone" to "<rect x=\"3\" y=\"3\" width=\"11\" height=\"11\"/><rect x=\"10\" y=\"10\" width=\"11\" height=\"11\" stroke-dasharray=\"2 2\"/>",
    "face" to "<path d=\"M12 3a6 6 0 0 0-6 6c0 5 2 8 6 12 4-4 6-7 6-12a6 6 0 0 0-6-6z\"/>",
    "skin" to "<path d=\"M4 9c3-3 5 3 8 0s5 3 8 0M4 15c3-3 5 3 8 0s5 3 8 0\"/>",
    "teeth" to "<path d=\"M5 8c0-2 2-3 4-3 1 0 2 1 3 1s2-1 3-1c2 0 4 1 4 3 0 5-2 11-3.5 11S14 14 12 14s-1 5-3 5S5 13 5 8z\"/>",
    "eyes" to "<path d=\"M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z\"/><circle cx=\"12\" cy=\"12\" r=\"3\"/>",
    "source" to "<circle cx=\"12\" cy=\"12\" r=\"7\"/><circle cx=\"12\" cy=\"12\" r=\"2\"/><path d=\"M12 2v4M12 18v4M2 12h4M18 12h4\"/>",
    "image" to "<rect x=\"3\" y=\"4\" width=\"18\" height=\"16\" rx=\"2\"/><path d=\"M3 17l6-5 4 4 3-3 5 5\"/>",
    "blur" to "<circle cx=\"12\" cy=\"12\" r=\"3\" fill=\"currentColor\"/><circle cx=\"12\" cy=\"12\" r=\"7\" stroke-dasharray=\"1 3\"/>",
    "checker" to "<rect x=\"4\" y=\"4\" width=\"16\" height=\"16\"/><path d=\"M4 12h16M12 4v16\"/><rect x=\"4\" y=\"4\" width=\"8\" height=\"8\" fill=\"currentColor\" fill-opacity=\".5\"/><rect x=\"12\" y=\"12\" width=\"8\" height=\"8\" fill=\"currentColor\" fill-opacity=\".5\"/>",
    "find" to "<circle cx=\"10\" cy=\"10\" r=\"6\"/><path d=\"M15 15l5 5\"/>",
    "start" to "<path d=\"M5 4v16\"/><path d=\"M9 6l10 6-10 6z\"/>",
    "end" to "<path d=\"M19 4v16\"/><path d=\"M15 6L5 12l10 6z\"/>",
    "scissors" to "<circle cx=\"6\" cy=\"6\" r=\"2.5\"/><circle cx=\"6\" cy=\"18\" r=\"2.5\"/><path d=\"M8 7.5L20 18M8 16.5L20 6\"/>",
    "mute" to "<path d=\"M4 9.5h4l5-4.5v14l-5-4.5H4z\"/><path d=\"M16.5 9l5 6M21.5 9l-5 6\"/>",
    "music" to "<path d=\"M9 18V6l10-2v12\"/><circle cx=\"7\" cy=\"18\" r=\"2\"/><circle cx=\"17\" cy=\"16\" r=\"2\"/>",
    "strength" to "<path d=\"M4 12h16\"/><circle cx=\"14\" cy=\"12\" r=\"3\" fill=\"currentColor\"/>",
    "fadeIn" to "<path d=\"M5 14v4M9 11v7M13 8v10M17 5v13\"/>",
    "fadeOut" to "<path d=\"M5 5v13M9 8v10M13 11v7M17 14v4\"/>",
    "snow" to "<path d=\"M12 3v18M4 7.5l16 9M4 16.5l16-9\"/>",
    "remove" to NO,
    "size" to "<path d=\"M4 4l6 6M10 6v4H6M20 4l-6 6M14 6v4h4M4 20l6-6M10 18v-4H6M20 20l-6-6M14 18v-4h4\"/>",
    "quality" to "<path d=\"M12 3l2.6 5.5 6 .8-4.4 4.2 1.1 6-5.3-2.9-5.3 2.9 1.1-6L3.4 9.3l6-.8z\"/>",
    // transfer marks
    "ok" to OK,
    "fail" to NO,
    "warn" to "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M12 7v6\"/><circle cx=\"12\" cy=\"16.5\" r=\".6\" fill=\"currentColor\"/>",
    "spinner" to "<path d=\"M12 4a8 8 0 1 1-8 8\"/>",
    "waiting" to "<circle cx=\"12\" cy=\"12\" r=\"9\" stroke-dasharray=\"2 3\"/>",
    "folder" to "<path d=\"M3 7v12h18V9h-9l-2-3H4z\"/>",
    "chevronDown" to "<path d=\"M6 9l6 6 6-6\"/>",
)

private sealed interface Shape { val attrs: Map<String, String> }
private class PathShape(val path: Path, override val attrs: Map<String, String>) : Shape
private class CircleShape(val cx: Float, val cy: Float, val r: Float, override val attrs: Map<String, String>) : Shape
private class RectShape(val x: Float, val y: Float, val w: Float, val h: Float, val rx: Float, override val attrs: Map<String, String>) : Shape

private val element = Regex("<(path|circle|rect)\\b([^>]*?)/?>")
private val attribute = Regex("([\\w-]+)=\"([^\"]*)\"")

private fun parse(name: String): List<Shape> = element.findAll(ICONS[name] ?: error("no icon $name")).map { m ->
    val a = attribute.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
    fun f(key: String) = a[key]?.toFloatOrNull() ?: 0f
    when (m.groupValues[1]) {
        "path" -> PathShape(PathParser().parsePathString(a.getValue("d")).toPath(), a)
        "circle" -> CircleShape(f("cx"), f("cy"), f("r"), a)
        else -> RectShape(f("x"), f("y"), f("width"), f("height"), f("rx"), a)
    }
}.toList()

/** A line icon by name, drawn in [color] at [size]. */
@Composable
fun SvgIcon(name: String, color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val shapes = remember(name) { parse(name) }
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        withTransform({ scale(k, k, Offset.Zero) }) { shapes.forEach { draw(it, color) } } // drawn on the 24 grid, so the line width scales with the icon
    }
}

private fun DrawScope.draw(shape: Shape, color: Color) {
    val a = shape.attrs
    val opacity = a["opacity"]?.toFloatOrNull() ?: 1f
    val rotation = a["transform"]?.let { Regex("rotate\\(([-\\d.]+) ([-\\d.]+) ([-\\d.]+)\\)").find(it)?.groupValues?.drop(1)?.map { v -> v.toFloat() } }
    val dash = a["stroke-dasharray"]?.split(' ')?.map { it.toFloat() }?.toFloatArray()
    val stroke = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash?.let { PathEffect.dashPathEffect(it) })
    val strokeColor = color.copy(alpha = color.alpha * opacity * (a["stroke-opacity"]?.toFloatOrNull() ?: 1f))
    val fillColor = if (a["fill"] == "currentColor") color.copy(alpha = color.alpha * opacity * (a["fill-opacity"]?.toFloatOrNull() ?: 1f)) else null
    val draw: DrawScope.() -> Unit = {
        when (shape) {
            is PathShape -> { fillColor?.let { drawPath(shape.path, it) }; drawPath(shape.path, strokeColor, style = stroke) }
            is CircleShape -> {
                fillColor?.let { drawCircle(it, shape.r, Offset(shape.cx, shape.cy)) }
                drawCircle(strokeColor, shape.r, Offset(shape.cx, shape.cy), style = stroke)
            }
            is RectShape -> {
                val corner = CornerRadius(shape.rx)
                fillColor?.let { drawRoundRect(it, Offset(shape.x, shape.y), Size(shape.w, shape.h), corner) }
                drawRoundRect(strokeColor, Offset(shape.x, shape.y), Size(shape.w, shape.h), corner, style = stroke)
            }
        }
    }
    if (rotation != null) withTransform({ rotate(rotation[0], Offset(rotation[1], rotation[2])) }) { draw() } else draw()
}

/** An [SvgIcon] as a 48dp tap target; [description] is read out by screen readers. */
@Composable
fun SvgIconButton(name: String, color: Color, description: String, modifier: Modifier = Modifier, size: Dp = 28.dp, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier.size(48.dp).semantics { contentDescription = description }.clickable(enabled = enabled, onClick = onClick),
        androidx.compose.ui.Alignment.Center,
    ) { SvgIcon(name, color, size = size) }
}
