package com.claudeborne.gallerymanager.ui

import android.text.format.Formatter
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
                Treemap(current, picked) { n ->
                    if (n.isDir) { zoom = zoom + n.file.path; picked = null } else picked = if (picked === n) null else n
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

/** The treemap of [node]'s children. Tiles under 1.5% of the folder are merged into one "other" tile so every tile stays tappable. */
@Composable
private fun Treemap(node: MapNode, picked: MapNode?, onTile: (MapNode) -> Unit) {
    val cb = LocalCb.current
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 4.dp)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val tiles = remember(node, w, h) { layoutTiles(node, w, h) }
        if (tiles.isEmpty()) Text("NOTHING HERE", Modifier.align(Alignment.Center), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 2.sp, color = cb.inkDim))
        tiles.forEach { (n, r) ->
            val wDp = with(density) { r.w.toDp() }
            val hDp = with(density) { r.h.toDp() }
            val dark = !n.isDir && !n.aggregate // bright tiles take dark text
            val fill = when { n.aggregate -> cb.surface1; n.isDir -> cb.surface2; else -> typeColor(n.file.fileType()) }
            Box(Modifier.offset { IntOffset(r.x.roundToInt(), r.y.roundToInt()) }.size(wDp, hDp).padding(1.dp)) {
                Column(
                    Modifier.fillMaxSize().background(fill)
                        .then(if (picked === n) Modifier.border(3.dp, Color.White) else if (n.isDir) Modifier.border(1.5.dp, cb.mint) else Modifier)
                        .clickable(enabled = !n.aggregate) { onTile(n) }.padding(4.dp),
                ) {
                    if (wDp > 56.dp && hDp > 34.dp) {
                        val ink = if (dark) DarkCb.surface0 else cb.ink
                        Text(
                            if (n.aggregate) "OTHER · ${n.count} FILES" else n.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = ink),
                        )
                        Text(Formatter.formatShortFileSize(LocalContext.current, n.size).uppercase(), maxLines = 1, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, color = ink))
                    }
                }
            }
        }
    }
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
