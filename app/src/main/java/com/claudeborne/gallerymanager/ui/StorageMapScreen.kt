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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableLongStateOf
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
import com.claudeborne.gallerymanager.data.extKey
import com.claudeborne.gallerymanager.data.largestFiles
import com.claudeborne.gallerymanager.data.moveToBin
import com.claudeborne.gallerymanager.data.scanTree
import com.claudeborne.gallerymanager.data.squarify
import com.claudeborne.gallerymanager.data.zoomChain
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** The vivid colour of each extension in [MAP_EXTENSIONS]; "other" is a quiet grey. */
private val ExtColors = mapOf(
    "jpg" to Color(0xFF19D68A), "heic" to Color(0xFF00C2A8), "png" to Color(0xFF8BE04A), "webp" to Color(0xFFC4E03A), "dng" to Color(0xFF2FE6C2),
    "mp4" to Color(0xFF3B82FF), "mov" to Color(0xFF6A5CFF), "mkv" to Color(0xFF4CC9F0), "opus" to Color(0xFFFF9F1C), "mp3" to Color(0xFFFFC233),
    "m4a" to Color(0xFFFFB347), "pdf" to Color(0xFFEF476F), "docx" to Color(0xFFF78FB3), "apk" to Color(0xFFFF5D3A), "zip" to Color(0xFFB455F7), "db" to Color(0xFF22D3EE),
)
private val OtherColor = Color(0xFF8B93A7)
private fun extColor(key: String) = ExtColors[key] ?: OtherColor

/** The last scan of each folder, kept while the app runs, so opening the map again shows it at once. */
private class CachedScan(val result: ScanResult, val at: Long)
private val scanCache = HashMap<String, CachedScan>()
private const val STALE_MS = 10 * 60 * 1000L // an older scan is shown first and then redone in the background

private fun ago(ms: Long): String {
    val min = ms / 60_000
    return when { min < 1 -> "JUST NOW"; min == 1L -> "1 MIN AGO"; min < 60 -> "$min MIN AGO"; else -> "${min / 60} H AGO" }
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
    var scan by remember { mutableStateOf(scanCache[start.path]?.result) }
    var scannedAt by remember { mutableLongStateOf(scanCache[start.path]?.at ?: 0L) }
    var scanning by remember { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) } // moves on so "3 MIN AGO" stays true
    var progress by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) } // raised by RESCAN, and after something was deleted
    var zoom by rememberSaveable { mutableStateOf(emptyList<String>()) } // folders below the start, deepest last
    var view by rememberSaveable { mutableIntStateOf(0) } // 0 the map, 1 the file types, 2 the largest files
    var picked by remember { mutableStateOf<MapNode?>(null) }
    var erase by remember { mutableStateOf<MapNode?>(null) }

    LaunchedEffect(Unit) { while (true) { delay(30_000); nowMs = System.currentTimeMillis() } }
    LaunchedEffect(start, reload) {
        val cached = scanCache[start.path]
        if (cached != null && reload == 0) { // reopened: show the last scan at once, and redo it only when it is old
            scan = cached.result
            scannedAt = cached.at
            if (System.currentTimeMillis() - cached.at < STALE_MS) return@LaunchedEffect
        }
        scanning = true
        progress = 0
        val result = scanTree(start) { progress = it } // any map already on screen stays until the new one is ready
        val at = System.currentTimeMillis()
        scanCache[start.path] = CachedScan(result, at)
        zoom = zoomChain(result.root, zoom).drop(1).map { it.file.path } // a folder that is gone drops out of the zoom
        scan = result
        scannedAt = at
        nowMs = at
        scanning = false
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
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { LoadingPane("SCANNING… $progress FILES") } // the total is not known until the scan ends, so the bar sweeps
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
            ActionText("MAP", if (view == 0) cb.mint else cb.inkDim) { view = 0; picked = null }
            ActionText("TYPES", if (view == 1) cb.mint else cb.inkDim) { view = 1; picked = null }
            ActionText("LARGEST", if (view == 2) cb.mint else cb.inkDim) { view = 2; picked = null }
            ActionText("BROWSE HERE", cb.inkDim) { onBrowse(current.file) }
        }
        // when the map was made, and a way to make it again; while it is being redone the old map stays and a bar sweeps
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (scanning) "RESCANNING… $progress FILES" else "SCANNED ${ago(nowMs - scannedAt)}", Modifier.weight(1f), style = monoStyle(cb.inkDim, 1.sp))
            Row(
                Modifier.clickable(enabled = !scanning) { reload++ }.padding(horizontal = 16.dp, vertical = 10.dp),
                Arrangement.spacedBy(6.dp), Alignment.CenterVertically,
            ) {
                CbIcon(Icon.REFRESH, glide(!scanning, cb.mint, cb.inkDim), size = 16.dp)
                Text("RESCAN", style = monoStyle(glide(!scanning, cb.mint, cb.inkDim), 2.2.sp))
            }
        }
        Visible(scanning, Modifier.padding(horizontal = 16.dp), expandEnter, shrinkExit) { ProgressBar(null) }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // the map, its zoomed folders, the file types and the LARGEST list fade into one another
            Crossfade(if (view == 0) current else view, Modifier.fillMaxSize(), animationSpec = tween(MOTION_MS), label = "map") { target ->
                when (target) {
                    is MapNode -> Treemap(target, picked) { n, inside ->
                        // a folder zooms in (through the folder tiles it sat in, if any); a file is picked
                        if (n.isDir) { zoom = zoom + inside.map { it.file.path } + n.file.path; picked = null } else picked = if (picked === n) null else n
                    }
                    1 -> TypesList(result)
                    else -> {
                        val top = remember(result) { largestFiles(result.root) }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(top, key = { it.file.path }) { n ->
                                ListRow(Entry(n.file, false, n.size, n.modified, 0), picked === n, onClick = { picked = if (picked === n) null else n }, onLongClick = {}, path = n.file.parent)
                            }
                        }
                    }
                }
            }

            // the card floats over the bottom of the map, so picking a tile does not make the map reflow
            AnimatedOverlay(picked, barEnter, barExit, Modifier.align(Alignment.BottomCenter)) { n ->
            val base = if (view == 2) result.root.size else current.size
            Column(Modifier.fillMaxWidth().background(cb.surface1).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(n.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cb.ink))
                Text(
                    "${Formatter.formatShortFileSize(context, n.size).uppercase()} · ${if (base > 0) n.size * 100 / base else 0}% OF ${if (view == 2) "ALL" else "THIS FOLDER"} · ${dateText(n.modified)}",
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
        if (view == 0) TypeTable(result)
    }

    erase?.let { n ->
        CbDialog("MOVE TO TRASH", { erase = null }) {
            Text("Move ${n.file.name} to the trash? You can restore it for $BIN_DAYS days.", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2))
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                ActionText("CANCEL", cb.inkDim) { erase = null }
                ActionText("MOVE TO TRASH", RED) {
                    erase = null
                    picked = null
                    scope.launch {
                        Toast.makeText(context, "Moved ${moveToBin(context, listOf(n.file))} to the trash", Toast.LENGTH_SHORT).show()
                        reload++ // the tree is stale now, so scan again (the zoom is kept)
                        onChanged()
                    }
                }
            }
        }
    }
}

/** The biggest extensions under the map: colour, name, size and share of everything scanned, two to a line. */
@Composable
private fun TypeTable(result: ScanResult) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val top = remember(result) { result.extBytes.entries.sortedByDescending { it.value }.take(10) }
    val total = result.root.size.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        top.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                pair.forEach { e ->
                    Row(Modifier.weight(1f), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(extColor(e.key)))
                        Text(
                            "${extLabel(e.key)} ${Formatter.formatShortFileSize(context, e.value).uppercase()} · ${e.value * 100 / total}%",
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = monoStyle(cb.inkDim, 0.6.sp),
                        )
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

private fun extLabel(key: String) = if (key == "other") "OTHER" else ".$key"

/** Every extension, biggest first: how many files, how much space, and a bar for its share. */
@Composable
private fun TypesList(result: ScanResult) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val rows = remember(result) { result.extBytes.entries.sortedByDescending { it.value } }
    val total = result.root.size.coerceAtLeast(1)
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.key }) { e ->
            val count = result.extCount[e.key] ?: 0
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(12.dp).background(extColor(e.key)))
                    Text(extLabel(e.key), Modifier.weight(1f), style = monoStyle(cb.ink, 1.5.sp))
                    Text(
                        "$count ${if (count == 1) "FILE" else "FILES"} · ${Formatter.formatShortFileSize(context, e.value).uppercase()} · ${e.value * 100 / total}%",
                        style = monoStyle(cb.inkDim, 0.6.sp),
                    )
                }
                ProgressBar((e.value.toDouble() / total).toFloat(), Modifier.padding(top = 5.dp), color = extColor(e.key), track = cb.line)
            }
        }
    }
}

/** A tile placed on the map: [inside] are the folder tiles it is drawn inside, outermost first (empty at the top level); [panel] means [node] is a folder that shows its own contents. */
private class Placed(val node: MapNode, val rect: TileRect, val inside: List<MapNode>, var panel: Boolean = false)

private const val HEADER_DP = 17f

/**
 * The nested treemap of [node]: its biggest folders and files, and inside each big enough folder its own biggest items (up to three levels, like
 * WinDirStat). Every file is a tile in the vivid colour of its extension, with a raised "cushion" shade. [onTile] gets the tapped node and the folder tiles it sat inside.
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
                val fill = when {
                    n.aggregate -> cb.surface1
                    n.isDir -> cb.surface2
                    else -> extColor(n.file.extKey())
                }
                drawRect(fill, topLeft, size)
                if ((!n.isDir || n.aggregate) && minOf(size.width, size.height) > 8f) { // a speck is just its colour
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
    page?.let { Image(it, null, Modifier.appear().fillMaxSize(), contentScale = contentScale, alignment = Alignment.TopCenter) }
}

/** The top-level tiles of [node] and, inside each folder tile that is big enough, its own biggest items, down to three levels. [dp] is pixels per dp. */
private fun layoutNested(node: MapNode, w: Float, h: Float, dp: Float): List<Placed> {
    val out = ArrayList<Placed>()
    fun add(n: MapNode, r: TileRect, inside: List<MapNode>, depth: Int) {
        val placed = Placed(n, r, inside)
        out += placed
        if (!n.isDir || n.aggregate || depth >= 3 || r.w < 90 * dp || r.h < 70 * dp) return
        val inner = layoutTiles(n, r.w - 6, r.h - HEADER_DP * dp - 5, dp)
        if (inner.isEmpty()) return
        placed.panel = true
        for ((c, cr) in inner) add(c, TileRect(r.x + 3 + cr.x, r.y + HEADER_DP * dp + 2 + cr.y, cr.w, cr.h), inside + n, depth + 1)
    }
    for ((n, r) in layoutTiles(node, w, h, dp)) add(n, r, emptyList(), 1)
    return out
}

/**
 * Every item of [node] gets its own tile, biggest first. What would come out smaller than a 3dp square, and so could not be seen as its own
 * tile, is merged into one quiet "small files" tile instead (capped at [MAX_TILES] tiles so a folder of many thousands stays quick to draw).
 */
private fun layoutTiles(node: MapNode, w: Float, h: Float, dp: Float): List<Pair<MapNode, TileRect>> {
    val kids = node.children.filter { it.size > 0 }
    val total = kids.sumOf { it.size }
    if (total == 0L) return emptyList()
    val minArea = 9f * dp * dp
    val cut = kids.indexOfFirst { it.size.toDouble() / total * w * h < minArea }
    val shown = (if (cut < 0) kids else kids.take(maxOf(cut, 1))).take(MAX_TILES)
    val rest = kids.drop(shown.size)
    val tiles = (if (rest.size > 1) shown + MapNode(node.file, false, rest.sumOf { it.size }, emptyList(), rest.sumOf { it.count }, aggregate = true) else shown + rest)
        .sortedByDescending { it.size }
    return tiles.zip(squarify(tiles.map { it.size }, w, h))
}

private const val MAX_TILES = 3000
