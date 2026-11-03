package com.claudeborne.gallerymanager.ui

import android.app.Activity
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.claudeborne.gallerymanager.data.MediaItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun dayLabel(ms: Long): String {
    val day = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (day) {
        today -> "TODAY"
        today.minusDays(1) -> "YESTERDAY"
        else -> day.format(dateFmt).uppercase()
    }
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

enum class PhotoSort(val label: String) { NEWEST("NEWEST"), OLDEST("OLDEST"), BIGGEST("BIGGEST"), NAME("NAME") }
enum class PhotoFilter(val label: String) { ALL("ALL"), PHOTOS("PHOTOS"), VIDEOS("VIDEOS"), FAVORITES("♥ FAVORITES") }

/**
 * Grid of [items] (Photos tab, or one album when [onBack] is set) with filter, sort, search and multi-select.
 * [onOpen] gets the tapped item plus the list as currently shown, so the viewer swipes through exactly what the user sees.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotosScreen(
    items: List<MediaItem>,
    title: String,
    gridState: LazyGridState = rememberLazyGridState(),
    onBack: (() -> Unit)? = null,
    onChanged: () -> Unit = {},
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
) {
    val cb = LocalCb.current
    val context = LocalContext.current
    var cols by rememberSaveable { mutableIntStateOf(com.claudeborne.gallerymanager.data.AppSettings.gridCols) }
    var filter by rememberSaveable { mutableStateOf(PhotoFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(runCatching { PhotoSort.valueOf(com.claudeborne.gallerymanager.data.AppSettings.photoSort) }.getOrDefault(PhotoSort.NEWEST)) }
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var moveDialog by remember { mutableStateOf(false) }
    var newAlbum by remember { mutableStateOf("") }

    val shown = remember(items, filter, sort, query) {
        items.asSequence()
            .filter {
                when (filter) {
                    PhotoFilter.ALL -> true
                    PhotoFilter.PHOTOS -> !it.isVideo
                    PhotoFilter.VIDEOS -> it.isVideo
                    PhotoFilter.FAVORITES -> it.favorite
                }
            }
            .filter { query.isNullOrBlank() || it.name.contains(query!!, true) || it.bucketName.contains(query!!, true) }
            .toList()
            .let {
                when (sort) {
                    PhotoSort.NEWEST -> it
                    PhotoSort.OLDEST -> it.sortedBy { m -> m.takenMs }
                    PhotoSort.BIGGEST -> it.sortedByDescending { m -> m.size }
                    PhotoSort.NAME -> it.sortedBy { m -> m.name.lowercase() }
                }
            }
    }
    val groupByDay = sort == PhotoSort.NEWEST || sort == PhotoSort.OLDEST
    val groups = remember(shown, groupByDay) { if (groupByDay) shown.groupBy { dayLabel(it.takenMs) }.toList() else listOf("" to shown) }
    val chosen = shown.filter { it.id in selected }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) { selected = emptySet(); onChanged() }
    }
    fun ask(request: android.app.PendingIntent) = launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, if (selected.isNotEmpty()) "${selected.size} SELECTED" else "${shown.size} ITEMS", onBack, backLabel = "ALBUMS")

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PhotoFilter.entries.forEach { f -> Chip(f.label, on = filter == f) { filter = f } }
            Chip("↕ ${sort.label}") { sort = PhotoSort.entries[(sort.ordinal + 1) % PhotoSort.entries.size] }
            Chip(if (query != null) "SEARCH ✕" else "SEARCH", on = query != null) { query = if (query == null) "" else null }
        }
        query?.let { q -> Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { CbInput(q, { query = it }, "Search by name or album…") } }

        if (shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (items.isEmpty()) "NO PHOTOS OR VIDEOS" else "NOTHING MATCHES", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 2.sp, color = cb.inkDim))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                state = gridState,
                modifier = Modifier.weight(1f).fillMaxWidth().pinchToResize(cols) { cols = it },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                groups.forEach { (label, day) ->
                    if (label.isNotEmpty()) item(key = label, span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.4.sp, color = cb.mint))
                            val all = day.all { it.id in selected }
                            ActionLabel(if (all) "UNSELECT" else "SELECT", cb.inkDim) {
                                selected = if (all) selected - day.map { it.id }.toSet() else selected + day.map { it.id }
                            }
                        }
                    }
                    items(day, key = { it.id }) { m ->
                        Tile(m, m.id in selected, onClick = {
                            if (selected.isNotEmpty()) selected = if (m.id in selected) selected - m.id else selected + m.id
                            else onOpen(m, shown)
                        }, onLongClick = { selected = if (m.id in selected) selected - m.id else selected + m.id })
                    }
                }
            }
        }

        if (selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(cb.surface1).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
                ActionLabel("ALL", cb.inkDim) { selected = shown.map { it.id }.toSet() }
                ActionLabel("SHARE", cb.mint) {
                    val uris = ArrayList(chosen.map { it.uri })
                    val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, null))
                }
                val makeFav = !chosen.all { it.favorite }
                ActionLabel(if (makeFav) "♥ FAVORITE" else "♡ UNFAVORITE", cb.mint) {
                    ask(MediaStore.createFavoriteRequest(context.contentResolver, chosen.map { it.uri }, makeFav))
                }
                ActionLabel("MOVE", cb.mint) { moveDialog = true }
                ActionLabel("VAULT", cb.mint) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val files = chosen.map { File(com.claudeborne.gallerymanager.data.storageRoot, it.path + it.name) }.filter { it.isFile }
                            val before = files.map { it.absolutePath }
                            files.forEach { com.claudeborne.gallerymanager.data.vaultAdd(context, it) }
                            com.claudeborne.gallerymanager.data.scanPaths(context, before)
                        }
                        selected = emptySet(); onChanged()
                    }
                }
                ActionLabel("TRASH", DangerRed) { ask(MediaStore.createTrashRequest(context.contentResolver, chosen.map { it.uri }, true)) }
                ActionLabel("CANCEL", cb.inkDim) { selected = emptySet() }
            }
        }
    }

    if (moveDialog) {
        val albums = remember(items) { items.map { it.path }.filter { it.isNotEmpty() }.distinct().sorted() }
        fun moveTo(rel: String) {
            moveDialog = false
            if (!com.claudeborne.gallerymanager.data.hasAllFilesAccess()) {
                android.widget.Toast.makeText(context, "Allow All files access first (open the Files tab)", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            val files = chosen.map { File(com.claudeborne.gallerymanager.data.storageRoot, it.path + it.name) }.filter { it.isFile }
            scope.launch {
                withContext(Dispatchers.IO) {
                    val dest = File(com.claudeborne.gallerymanager.data.storageRoot, rel).also { it.mkdirs() }
                    val before = files.map { it.absolutePath }
                    val made = files.map { com.claudeborne.gallerymanager.data.moveInto(it, dest) }
                    com.claudeborne.gallerymanager.data.scanPaths(context, before + made.map { it.absolutePath })
                }
                selected = emptySet(); newAlbum = ""; onChanged()
            }
        }
        CbDialog("MOVE ${chosen.size} TO", { moveDialog = false }, "NEW ALBUM", onConfirm = {
            if (newAlbum.isNotBlank() && !newAlbum.contains('/')) moveTo("Pictures/" + newAlbum.trim())
        }) {
            CbInput(newAlbum, { newAlbum = it }, "New album name (then tap NEW ALBUM)")
            androidx.compose.foundation.layout.Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                albums.forEach { rel ->
                    Text(
                        rel.trimEnd('/'),
                        Modifier.fillMaxWidth().clickable { moveTo(rel) }.padding(vertical = 10.dp),
                        style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(item: MediaItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cb = LocalCb.current
    Box(
        Modifier.aspectRatio(1f).background(cb.surface2).combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selected) Modifier.border(3.dp, cb.mint) else Modifier),
    ) {
        AsyncImage(item.uri, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (item.isVideo) {
            Text(
                "▶ ${duration(item.durationMs)}",
                Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 2.dp),
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, color = Color.White),
            )
        }
        if (item.favorite) Text("♥", Modifier.align(Alignment.BottomEnd).padding(5.dp), style = TextStyle(fontSize = 13.sp, color = Color(0xFFFF6B8B)))
        if (selected) {
            Text(
                "✓",
                Modifier.align(Alignment.TopEnd).padding(4.dp).background(cb.mint).padding(horizontal = 5.dp),
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = DarkCb.surface0),
            )
        }
    }
}

/** Two-finger pinch changes the column count (2..6). Observes in the initial pass and consumes nothing, so scrolling still works. */
private fun Modifier.pinchToResize(cols: Int, onChange: (Int) -> Unit) = pointerInput(cols) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var scale = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size >= 2) {
                scale *= event.calculateZoom()
                if (scale > 1.4f) { onChange((cols - 1).coerceAtLeast(2)); return@awaitEachGesture }
                if (scale < 0.7f) { onChange((cols + 1).coerceAtMost(6)); return@awaitEachGesture }
            }
        } while (event.changes.any { it.pressed })
    }
}
