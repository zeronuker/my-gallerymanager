package com.claudeborne.gallerymanager.ui

import android.app.Activity
import android.provider.MediaStore
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.aspectRatio
import com.claudeborne.gallerymanager.data.Settings
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.claudeborne.gallerymanager.data.BinItem
import com.claudeborne.gallerymanager.data.Entry
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.data.deleteFromBin
import com.claudeborne.gallerymanager.data.entryOf
import com.claudeborne.gallerymanager.data.listBin
import com.claudeborne.gallerymanager.data.restoreFromBin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DAY_MS = 86_400_000L
private const val MEDIA_TRASH_DAYS = 30 // how long Android keeps a trashed photo or video

/** One line of the trash: a file from the Files bin, or a photo or video from Android's trash. */
private sealed interface TrashRow {
    val key: String
    val deletedMs: Long
}

private class BinRow(val item: BinItem, val entry: Entry) : TrashRow {
    override val key = "b:${item.id}"
    override val deletedMs = item.deletedMs
}

private class MediaRow(val item: MediaItem) : TrashRow {
    override val key = "m:${item.id}"
    override val deletedMs = if (item.expiresMs > 0) item.expiresMs - MEDIA_TRASH_DAYS * DAY_MS else 0 // the trash stores when it expires, not when it was deleted
}

/**
 * Everything that was deleted, newest first: files from the Files bin and photos and videos from Android's trash. Tap to look at one
 * before deciding, long press to select. Android asks to confirm restoring or erasing photos and videos. [onChanged] fires after a restore.
 */
@Composable
internal fun TrashView(media: List<MediaItem>, backEnabled: Boolean, onBack: () -> Unit, onChanged: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bin by remember { mutableStateOf<List<BinRow>>(emptyList()) }
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var erase by remember { mutableStateOf<List<TrashRow>?>(null) } // what a confirm dialog is about to erase
    var binDetail by remember { mutableStateOf<String?>(null) } // key of the bin file open full-screen
    var mediaView by remember { mutableStateOf<Long?>(null) } // id of the photo or video open full-screen
    var grid by rememberSaveable { mutableStateOf(false) } // rows or thumbnails

    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) { bin = withContext(Dispatchers.IO) { listBin(context).map { BinRow(it, entryOf(it.file)) } }; loaded = true }
    val rows = remember(bin, media) { (bin + media.map(::MediaRow)).sortedByDescending { it.deletedMs } }
    val chosen = rows.filter { it.key in selected }
    fun toggle(key: String) { selected = if (key in selected) selected - key else selected + key }
    BackHandler(enabled = backEnabled) { if (binDetail != null) binDetail = null else if (chosen.isNotEmpty()) selected = emptySet() else onBack() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) { selected = emptySet(); onChanged() }
    }
    fun askAndroid(items: List<MediaItem>, restore: Boolean) {
        if (items.isEmpty()) return
        // trashed items carry an includeTrashed flag for display; the requests want the plain uri
        val uris = items.map { it.uri.buildUpon().clearQuery().build() }
        val request = if (restore) MediaStore.createTrashRequest(context.contentResolver, uris, false) else MediaStore.createDeleteRequest(context.contentResolver, uris)
        launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }
    fun restore(of: List<TrashRow>) {
        val files = of.filterIsInstance<BinRow>().map { it.item }
        if (files.isNotEmpty()) scope.launch {
            Toast.makeText(context, "Restored ${restoreFromBin(context, files)}", Toast.LENGTH_SHORT).show()
            selected = emptySet(); binDetail = null; refresh++; onChanged()
        }
        askAndroid(of.filterIsInstance<MediaRow>().map { it.item }, restore = true)
    }

    Box(Modifier.fillMaxSize()) { // a full-screen look at one item draws over the list
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("TRASH", if (chosen.isEmpty()) "${rows.size} ITEMS" else "${chosen.size} SELECTED", onBack, "FILES")
            Text("Items are removed for good after about 30 days.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim))
            Row(Modifier.fillMaxWidth(), Arrangement.End, Alignment.CenterVertically) {
                AnimatedVisibility(rows.isNotEmpty(), enter = fadeIn(tween(MOTION_FAST_MS)), exit = fadeOut(tween(MOTION_FAST_MS))) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ViewMenu(grid, { grid = it })
                        ActionText("EMPTY TRASH", RED) { erase = rows }
                    }
                }
            }
            Crossfade(if (!loaded && rows.isEmpty()) 0 else if (rows.isEmpty()) 1 else 2, Modifier.weight(1f).fillMaxWidth(), animationSpec = tween(MOTION_MS), label = "trash") { state ->
                if (state == 0) {
                    LoadingPane("LOADING THE TRASH")
                } else if (state == 1) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("THE TRASH IS EMPTY", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 2.sp, color = cb.inkDim)) }
                } else {
                    // a tap looks at the item (a small thumbnail is hard to judge); a long press, or a tap once something is selected, selects it
                    fun tapOf(row: TrashRow): () -> Unit = {
                        if (selected.isNotEmpty()) toggle(row.key)
                        else if (row is BinRow) binDetail = row.key else if (row is MediaRow) mediaView = row.item.id
                    }
                    if (grid) LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(rows, key = { it.key }) { row ->
                            Box(Modifier.animateItem()) {
                                when (row) {
                                    is BinRow -> GridTile(row.entry, row.key in selected, tapOf(row)) { toggle(row.key) }
                                    is MediaRow -> MediaTrashTile(row.item, row.key in selected, tapOf(row)) { toggle(row.key) }
                                }
                            }
                        }
                    } else LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { it.key }) { row ->
                            Box(Modifier.animateItem()) {
                            when (row) {
                                is BinRow -> {
                                    val size = if (row.entry.isDir) "FOLDER" else Formatter.formatShortFileSize(context, row.entry.size).uppercase()
                                    ListRow(
                                        row.entry, row.key in selected, tapOf(row), { toggle(row.key) },
                                        note = "$size · DELETED ${dateText(row.item.deletedMs)}", path = "FROM ${row.item.origin.parent.orEmpty()}",
                                    )
                                }
                                is MediaRow -> MediaTrashRow(row.item, row.key in selected, tapOf(row)) { toggle(row.key) }
                            }
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(chosen.isNotEmpty(), enter = barEnter, exit = barExit) {
                ActionBar(
                    listOf(
                        BarItem(if (chosen.size == rows.size) "DESELECT ALL" else "ALL", cb.inkDim) { selected = if (chosen.size == rows.size) emptySet() else rows.map { it.key }.toSet() },
                        BarItem("RESTORE", cb.mint) { restore(chosen) },
                        BarItem("DELETE", RED) { erase = chosen },
                    ),
                )
            }
        }
        AnimatedOverlay(rows.firstOrNull { it.key == binDetail } as? BinRow) { row ->
            BinDetail(row.item, row.entry, onClose = { binDetail = null }, onRestore = { restore(listOf(row)) }, onDelete = { erase = listOf(row) })
        }
        AnimatedOverlay(mediaView) { id ->
            TrashViewer(media, id, onRestore = { askAndroid(listOf(it), restore = true) }, onDelete = { askAndroid(listOf(it), restore = false) }, onClose = { mediaView = null })
        }
    }

    erase?.let { doomed ->
        CbDialog("DELETE FOR GOOD", { erase = null }) {
            Text(
                "Erase ${if (doomed.size == 1) "this item" else "${doomed.size} items"} for good? This cannot be undone.",
                style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2),
            )
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                ActionText("CANCEL", cb.inkDim) { erase = null }
                ActionText("DELETE", RED) {
                    erase = null
                    scope.launch {
                        deleteFromBin(context, doomed.filterIsInstance<BinRow>().map { it.item })
                        selected = emptySet(); binDetail = null; refresh++
                        askAndroid(doomed.filterIsInstance<MediaRow>().map { it.item }, restore = false)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ActionText(label: String, color: Color, onClick: () -> Unit) {
    if (LocalInDialog.current) { // in a dialog the same call is a brand button; its colour says which kind
        val primary = color == DarkCb.mint || color == LocalCb.current.mint
        BrandButton(label, DialogButtonGap, primary = primary, danger = color == RED, cb = DarkCb, onClick = onClick)
        return
    }
    Text(
        label,
        Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.2.sp, color = animateColorAsState(color, tween(MOTION_FAST_MS), label = "action").value),
    )
}

/** "29 days", or "1 day"; empty when the item has no removal date. */
private fun daysLeft(item: MediaItem): String {
    if (item.expiresMs <= 0) return ""
    val days = ((item.expiresMs - System.currentTimeMillis()) / DAY_MS).coerceAtLeast(0)
    return if (days == 1L) "1 day" else "$days days"
}

/** A trashed photo or video as a thumbnail with how long it has left. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTrashTile(item: MediaItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cb = LocalCb.current
    val left = daysLeft(item)
    Box(
        Modifier.aspectRatio(1f).background(cb.surface2).combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .border(3.dp, glide(selected, cb.mint, Color.Transparent)),
    ) {
        AsyncImage(item.uri, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (left.isNotEmpty()) Text(
            left.uppercase(), Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 2.dp),
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, color = Color.White),
        )
        SelectedMark(selected)
    }
}

/** A trashed photo or video as a row that matches the file rows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTrashRow(item: MediaItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val left = daysLeft(item)
    Row(
        Modifier.fillMaxWidth().background(glide(selected, cb.surface2, Color.Transparent)).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).background(cb.surface2).border(2.dp, glide(selected, cb.mint, Color.Transparent))) {
            AsyncImage(item.uri, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cb.ink))
            Text(
                Formatter.formatShortFileSize(context, item.size).uppercase() + if (left.isEmpty()) "" else " · ${left.uppercase()} LEFT",
                maxLines = 1, style = monoStyle(cb.inkDim, 0.6.sp),
            )
        }
    }
}

/** Full-screen look at trashed photos and videos, swipeable, with restore and delete forever; Android asks to confirm either. */
@Composable
private fun TrashViewer(items: List<MediaItem>, startId: Long, onRestore: (MediaItem) -> Unit, onDelete: (MediaItem) -> Unit, onClose: () -> Unit) {
    val pager = rememberPagerState(items.indexOfFirst { it.id == startId }.coerceAtLeast(0)) { items.size }
    val current = items.getOrNull(pager.currentPage)
    var muted by remember { mutableStateOf(Settings.muted) }
    BackHandler(onBack = onClose)
    LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) onClose() } // the last one was restored or deleted
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("← BACK", Modifier.clickable(onClick = onClose), style = monoStyle(DarkCb.mint, 2.6.sp))
            Box(Modifier.weight(1f))
            current?.let {
                val left = daysLeft(it)
                if (left.isNotEmpty()) Text("${left.uppercase()} LEFT", style = monoStyle(Color.White, 1.sp))
            }
        }
        Box(Modifier.weight(1f)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { items.getOrNull(it)?.id ?: it }) { page ->
                val item = items.getOrNull(page) ?: return@HorizontalPager
                if (item.isVideo) VideoPage(item, active = pager.settledPage == page, chrome = true, onTap = {}, muted = muted, onMuted = { muted = it }) else ZoomableImage(item, onTap = {})
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF0A1020)).padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ActionText("RESTORE", DarkCb.mint) { current?.let(onRestore) }
            ActionText("DELETE FOREVER", Color(0xFFFF6B6B)) { current?.let(onDelete) }
        }
    }
}
