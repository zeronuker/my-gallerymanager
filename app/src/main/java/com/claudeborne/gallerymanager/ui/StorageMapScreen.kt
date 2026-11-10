package com.claudeborne.gallerymanager.ui

import android.text.format.Formatter
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.data.BIN_DAYS
import com.claudeborne.gallerymanager.data.Entry
import com.claudeborne.gallerymanager.data.FileType
import com.claudeborne.gallerymanager.data.MapNode
import com.claudeborne.gallerymanager.data.ScanResult
import com.claudeborne.gallerymanager.data.TileRect
import com.claudeborne.gallerymanager.data.fileType
import com.claudeborne.gallerymanager.data.largestFiles
import com.claudeborne.gallerymanager.data.moveToBin
import com.claudeborne.gallerymanager.data.scanTree
import com.claudeborne.gallerymanager.data.squarify
import com.claudeborne.gallerymanager.data.zoomChain
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

private fun typeColor(t: FileType) = when (t) {
    FileType.PHOTO -> Color(0xFF3DDC97)
    FileType.VIDEO -> Color(0xFF4C8DFF)
    FileType.AUDIO -> Color(0xFFB388FF)
    FileType.DOCUMENT -> Color(0xFFFFB74D)
    FileType.ARCHIVE -> Color(0xFFFF8A65)
    FileType.APP -> Color(0xFFF06292)
    FileType.OTHER -> Color(0xFF8A94A8)
}

private fun typeLabel(t: FileType) = when (t) {
    FileType.PHOTO -> "PHOTOS"
    FileType.VIDEO -> "VIDEOS"
    FileType.AUDIO -> "AUDIO"
    FileType.DOCUMENT -> "DOCUMENTS"
    FileType.ARCHIVE -> "ARCHIVES"
    FileType.APP -> "APPS"
    FileType.OTHER -> "OTHER"
}

/**
 * What uses the space in [start]: a treemap one folder level at a time (tap a folder to zoom in), a legend of the file types, and a list of
 * the largest files. [onChanged] fires after something was deleted so the library follows.
 */
@Composable
internal fun StorageMapScreen(
    start: File, rootLabel: String, backEnabled: Boolean,
    onClose: () -> Unit, onBrowse: (File) -> Unit, onOpen: (File) -> Unit, onChanged: () -> Unit,
) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scan by remember { mutableStateOf<ScanResult?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var zoom by rememberSaveable { mutableStateOf(emptyList<String>()) } // folders below the start, deepest last
    var largest by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf<MapNode?>(null) }
    var erase by remember { mutableStateOf<MapNode?>(null) }

    LaunchedEffect(start, reload) {
        scan = null
        progress = 0
        val result = scanTree(start) { progress = it }
        zoom = zoomChain(result.root, zoom).drop(1).map { it.file.path } // a folder that is gone drops out of the zoom
        scan = result
    }
    val result = scan
    val chain = result?.let { zoomChain(it.root, zoom) }.orEmpty()
    val current = chain.lastOrNull()
    fun back() { if (picked != null) picked = null else if (zoom.isNotEmpty()) zoom = zoom.dropLast(1) else onClose() }
    BackHandler(enabled = backEnabled) { back() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "MAP", if (result == null) "SCANNING…" else "${Formatter.formatShortFileSize(context, result.root.size).uppercase()} · ${result.root.count} FILES",
            ::back, when { chain.size <= 1 -> "FILES"; chain.size == 2 -> rootLabel; else -> chain[chain.size - 2].file.name }.uppercase(),
        )
        if (result == null || current == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Text("SCANNING… $progress FILES", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 2.sp, color = cb.inkDim))
            }
            return@Column
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(Int.MAX_VALUE)).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            chain.forEachIndexed { i, n ->
                if (i > 0) Text(" › ", style = monoStyle(cb.inkDim, 0.sp))
                Text(
                    (if (i == 0) rootLabel else n.file.name).uppercase(),
                    Modifier.clickable { zoom = chain.drop(1).take(i).map { it.file.path }; picked = null }.padding(vertical = 4.dp),
                    style = monoStyle(if (n === current) cb.mint else cb.inkDim, 1.sp),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            ActionText("MAP", if (!largest) cb.mint else cb.inkDim) { largest = false; picked = null }
            ActionText("LARGEST", if (largest) cb.mint else cb.inkDim) { largest = true; picked = null }
            ActionText("BROWSE HERE", cb.inkDim) { onBrowse(current.file) }
        }
        if (!largest) Legend(result)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (largest) {
                val top = remember(result) { largestFiles(result.root) }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(top, key = { it.file.path }) { n ->
                        ListRow(Entry(n.file, false, n.size, n.modified, 0), picked === n, onClick = { picked = if (picked === n) null else n }, onLongClick = {}, path = n.file.parent)
                    }
                }
            } else {
                Treemap(current, picked) { n, inside ->
                    // a folder zooms in (through the folder tiles it sat in, if any); a file is picked
                    if (n.isDir) { zoom = zoom + inside.map { it.file.path } + n.file.path; picked = null } else picked = if (picked === n) null else n
                }
            }

            // the card floats over the bottom of the map, so picking a tile does not make the map reflow
            picked?.let { n ->
            val base = if (largest) result.root.size else current.size
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(cb.surface1).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(n.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cb.ink))
                Text(
                    "${Formatter.formatShortFileSize(context, n.size).uppercase()} · ${if (base > 0) n.size * 100 / base else 0}% OF ${if (largest) "ALL" else "THIS FOLDER"} · ${dateText(n.modified)}",
                    style = monoStyle(cb.inkDim, 0.6.sp),
                )
                Text(n.file.parent.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = monoStyle(cb.inkDim, 0.6.sp))
                Row {
                    ActionText("OPEN", cb.mint) { onOpen(n.file) }
                    ActionText("SHARE", cb.mint) { shareFiles(context, listOf(n.file)) }
                    ActionText("DELETE", RED) { erase = n }
                }
            }
            }
        }
    }

    erase?.let { n ->
        CbDialog("MOVE TO BIN", { erase = null }) {
            Text("Move ${n.file.name} to the bin? You can restore it for $BIN_DAYS days.", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2))
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                ActionText("CANCEL", cb.inkDim) { erase = null }
                ActionText("MOVE TO BIN", RED) {
                    erase = null
                    picked = null
                    scope.launch {
                        Toast.makeText(context, "Moved ${moveToBin(context, listOf(n.file))} to the bin", Toast.LENGTH_SHORT).show()
                        reload++ // the tree is stale now, so scan again (the zoom is kept)
                        onChanged()
                    }
                }
            }
        }
    }
}

/** Colour key for the file types, with the bytes each takes over everything scanned. */
@Composable
private fun Legend(result: ScanResult) {
    val cb = LocalCb.current
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        FileType.entries.filter { result.counts[it.ordinal] > 0 }.sortedByDescending { result.bytes[it.ordinal] }.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(10.dp).background(typeColor(t)))
                Text("${typeLabel(t)} ${Formatter.formatShortFileSize(context, result.bytes[t.ordinal]).uppercase()}", maxLines = 1, style = monoStyle(cb.inkDim, 0.6.sp))
            }
        }
    }
}

/** A tile placed on the map: [inside] are the folder tiles it is drawn inside, outermost first (empty at the top level); [panel] means [node] is a folder that shows its own contents. */
private class Placed(val node: MapNode, val rect: TileRect, val inside: List<MapNode>, var panel: Boolean = false)

private const val HEADER_DP = 17f

/**
 * The nested treemap of [node]: its biggest folders and files, and inside each big enough folder its own biggest items (up to three levels, like
 * WinDirStat). Tiles are coloured by file type, brighter the bigger the file, with a raised "cushion" shade. Big photos, videos and PDFs show a
 * preview. [onTile] gets the tapped node and the folder tiles it sat inside.
 */
@Composable
private fun Treemap(node: MapNode, picked: MapNode?, onTile: (MapNode, List<MapNode>) -> Unit) {
    val cb = LocalCb.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 4.dp)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val dp = density.density
        val placed = remember(node, w, h) { layoutNested(node, w, h, dp) }
        if (placed.isEmpty()) Text("NOTHING HERE", Modifier.align(Alignment.Center), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 2.sp, color = cb.inkDim))

        // brightness follows the size of a file relative to the others in view
        val files = placed.filter { !it.node.isDir && !it.node.aggregate }
        val lo = files.minOfOrNull { ln(it.node.size.toDouble()) } ?: 0.0
        val hi = files.maxOfOrNull { ln(it.node.size.toDouble()) } ?: 1.0
        // the previews sit under the canvas, which leaves their tiles unpainted; only the biggest few get one
        val previews = remember(placed) {
            files.filter { val t = it.node.file.fileType(); (t == FileType.PHOTO || t == FileType.VIDEO || it.node.file.extension.equals("pdf", true)) && it.rect.w > 56 * dp && it.rect.h > 46 * dp }
                .sortedByDescending { it.rect.w * it.rect.h }.take(28)
        }
        previews.forEach { p ->
            val r = p.rect
            Box(Modifier.offset { IntOffset(r.x.roundToInt() + 1, r.y.roundToInt() + 1) }.size(with(density) { (r.w - 2).toDp() }, with(density) { (r.h - 2).toDp() }).clip(RectangleShape)) {
                if (p.node.file.extension.equals("pdf", true)) PdfFirstPage(p.node.file) else AsyncImage(p.node.file, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }

        Canvas(
            Modifier.fillMaxSize().pointerInput(placed) {
                detectTapGestures { at ->
                    val hit = placed.lastOrNull { !it.node.aggregate && at.x >= it.rect.x && at.x <= it.rect.x + it.rect.w && at.y >= it.rect.y && at.y <= it.rect.y + it.rect.h }
                    if (hit != null) onTile(hit.node, hit.inside)
                }
            },
        ) {
            placed.forEach { p ->
                val r = p.rect
                val n = p.node
                val topLeft = Offset(r.x + 1f, r.y + 1f)
                val size = Size(r.w - 2f, r.h - 2f)
                if (size.width <= 1f || size.height <= 1f) return@forEach
                val hasPreview = p in previews
                if (!hasPreview) {
                    val fill = when {
                        n.aggregate -> cb.surface1
                        n.isDir -> cb.surface2
                        else -> shadeFor(n.file.fileType(), n.size, lo, hi)
                    }
                    drawRect(fill, topLeft, size)
                }
                if (!n.isDir || n.aggregate) {
                    // cushion: a highlight toward the top left and a darker rim, which gives every tile its raised look
                    drawRect(
                        Brush.radialGradient(
                            0f to Color.White.copy(alpha = 0.30f), 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.38f),
                            center = Offset(topLeft.x + size.width * 0.3f, topLeft.y + size.height * 0.25f), radius = maxOf(size.width, size.height) * 0.85f,
                        ),
                        topLeft, size,
                    )
                }
                if (n.isDir && !n.aggregate) drawRect(cb.mint.copy(alpha = if (p.panel) 0.6f else 0.9f), topLeft, size, style = Stroke(1.5f))
                if (picked === n) drawRect(Color.White, topLeft, size, style = Stroke(3f * dp))
                label(measurer, p, topLeft, size, dp, Formatter.formatShortFileSize(context, n.size).uppercase())
            }
        }
    }
}

/** The name and size on a tile, on a dark strip so it reads over any colour or preview. */
private fun DrawScope.label(measurer: TextMeasurer, p: Placed, at: Offset, size: Size, dp: Float, sizeText: String) {
    val n = p.node
    if (size.width < 58 * dp || (size.height < 28 * dp && !p.panel)) return
    val text = if (n.aggregate) "${n.count} small files" else n.file.name
    val color = if (n.isDir && !n.aggregate) Color(0xFF3DDC97) else Color(0xFFE8ECF5)
    val title = measurer.measure(
        text = if (p.panel) "$text  $sizeText" else text,
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = color),
        overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = (size.width - 8 * dp).toInt().coerceAtLeast(1)),
    )
    drawRect(Color(0xB8080C18), Offset(at.x + 2 * dp, at.y + 2 * dp), Size(title.size.width + 8 * dp, title.size.height + 2 * dp))
    drawText(title, topLeft = Offset(at.x + 6 * dp, at.y + 3 * dp))
    if (!p.panel && size.height > 46 * dp) {
        val sub = measurer.measure(sizeText, TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, color = Color(0xFF9FB0D0)), maxLines = 1)
        drawRect(Color(0xB8080C18), Offset(at.x + 2 * dp, at.y + title.size.height + 5 * dp), Size(sub.size.width + 8 * dp, sub.size.height + 2 * dp))
        drawText(sub, topLeft = Offset(at.x + 6 * dp, at.y + title.size.height + 6 * dp))
    }
}

/** A file's tile colour: its type's hue, lighter and more saturated the bigger the file is among those in view. */
private fun shadeFor(t: FileType, size: Long, lo: Double, hi: Double): Color {
    val k = if (hi > lo) ((ln(size.toDouble()) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) else 1f
    val base = typeColor(t)
    val dark = Color(base.red * 0.28f, base.green * 0.28f, base.blue * 0.28f, 1f)
    return lerp(dark, base, 0.35f + 0.65f * k)
}

/** The first page of a PDF as a picture, drawn once in the background. Nothing shows if the file cannot be opened. */
@Composable
internal fun PdfFirstPage(file: File, width: Int = 320, contentScale: ContentScale = ContentScale.Crop) {
    val page by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        renderer.openPage(0).use { p ->
                            val scale = width.toFloat() / p.width
                            val bmp = Bitmap.createBitmap(width, (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp.asImageBitmap()
                        }
                    }
                }
            }.getOrNull()
        }
    }
    page?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = contentScale, alignment = Alignment.TopCenter) }
}

/** The top-level tiles of [node] and, inside each folder tile that is big enough, its own biggest items, down to three levels. [dp] is pixels per dp. */
private fun layoutNested(node: MapNode, w: Float, h: Float, dp: Float): List<Placed> {
    val out = ArrayList<Placed>()
    fun add(n: MapNode, r: TileRect, inside: List<MapNode>, depth: Int) {
        val placed = Placed(n, r, inside)
        out += placed
        if (!n.isDir || n.aggregate || depth >= 3 || r.w < 90 * dp || r.h < 70 * dp) return
        val inner = layoutTiles(n, r.w - 6, r.h - HEADER_DP * dp - 5)
        if (inner.isEmpty()) return
        placed.panel = true
        for ((c, cr) in inner) add(c, TileRect(r.x + 3 + cr.x, r.y + HEADER_DP * dp + 2 + cr.y, cr.w, cr.h), inside + n, depth + 1)
    }
    for ((n, r) in layoutTiles(node, w, h)) add(n, r, emptyList(), 1)
    return out
}

private fun layoutTiles(node: MapNode, w: Float, h: Float): List<Pair<MapNode, TileRect>> {
    val kids = node.children.filter { it.size > 0 }
    val total = kids.sumOf { it.size }
    if (total == 0L) return emptyList()
    val big = kids.take(40).takeWhile { it.size * 1000 >= total * 15 }
    val rest = kids.drop(big.size)
    val tiles = (if (rest.size > 1) big + MapNode(node.file, false, rest.sumOf { it.size }, emptyList(), rest.sumOf { it.count }, aggregate = true) else big + rest)
        .sortedByDescending { it.size }
    return tiles.zip(squarify(tiles.map { it.size }, w, h))
}
