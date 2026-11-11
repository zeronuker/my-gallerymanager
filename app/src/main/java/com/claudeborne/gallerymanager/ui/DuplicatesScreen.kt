package com.claudeborne.gallerymanager.ui

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.data.BIN_DAYS
import com.claudeborne.gallerymanager.data.DUP_MIN_BYTES
import com.claudeborne.gallerymanager.data.DupGroup
import com.claudeborne.gallerymanager.data.Entry
import com.claudeborne.gallerymanager.data.findDuplicates
import com.claudeborne.gallerymanager.data.moveToBin
import kotlinx.coroutines.launch
import java.io.File

@Composable
private fun GroupHeader(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp), style = monoStyle(LocalCb.current.mint, 1.sp))
}

/**
 * Files with identical content. Tap a copy to select it (at least one copy of each file always stays), long-press to open it,
 * or use SELECT EXTRAS to keep only the oldest copy of everything. Deleting moves the copies to the trash.
 */
@Composable
internal fun DuplicatesScreen(roots: List<File>, backEnabled: Boolean, onClose: () -> Unit, onOpen: (File) -> Unit, onChanged: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<DupGroup>?>(null) }
    var status by remember { mutableStateOf("SCANNING…") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var erase by remember { mutableStateOf<List<File>?>(null) }
    var grid by rememberSaveable { mutableStateOf(false) } // rows or thumbnails
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) { groups = findDuplicates(roots) { status = it } }
    BackHandler(enabled = backEnabled) { if (selected.isNotEmpty()) selected = emptySet() else onClose() }

    val list = groups
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes).uppercase()
    val sizeOf = remember(list) { list.orEmpty().flatMap { g -> g.files.map { it.path to g.size } }.toMap() }
    val count = when {
        list == null -> status
        selected.isNotEmpty() -> "${selected.size} SELECTED · ${size(selected.sumOf { sizeOf[it] ?: 0 })}"
        else -> "${list.size} GROUPS · ${size(list.sumOf { it.wasted })}"
    }

    fun toggle(g: DupGroup, f: File) {
        when {
            f.path in selected -> selected -= f.path
            g.files.count { it.path !in selected } <= 1 -> Toast.makeText(context, "Keep at least one copy of each file", Toast.LENGTH_SHORT).show()
            else -> selected += f.path
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("DUPES", count, onClose, "FILES")
        if (list == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { LoadingPane(status) } // the scan reports a message, not a percentage, so the bar sweeps
            return@Column
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Identical files of ${size(DUP_MIN_BYTES)} or more. Tap to select, long-press to open.",
                Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 4.dp), style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim),
            )
            if (list.isNotEmpty()) ViewMenu(grid, { grid = it })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (list.isEmpty()) {
                Text("NO DUPLICATES FOUND", Modifier.align(Alignment.Center), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 2.sp, color = cb.inkDim))
            } else {
                val total = list.sumOf { it.files.size + 1 } // each group is a header and its copies
                fun header(g: DupGroup) = "${g.files.size} COPIES · ${size(g.size)} EACH · ${size(g.wasted)} WASTED"
                Crossfade(grid, Modifier.fillMaxSize(), animationSpec = tween(MOTION_FAST_MS), label = "dupView") { asGrid ->
                    Box(Modifier.fillMaxSize()) {
                        if (asGrid) {
                            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), gridState, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                list.forEach { g ->
                                    item(key = "h:" + g.files[0].path, span = { GridItemSpan(maxLineSpan) }) { GroupHeader(header(g)) }
                                    gridItems(g.files, key = { it.path }) { f ->
                                        Box(Modifier.animateItem()) { GridTile(Entry(f, false, g.size, f.lastModified(), 0), f.path in selected, { toggle(g, f) }, { onOpen(f) }) }
                                    }
                                }
                            }
                            DragScrollBar(total, gridState.firstVisibleItemIndex, gridState.layoutInfo.visibleItemsInfo.size, { gridState.scrollToItem(it) }, Modifier.align(Alignment.TopEnd))
                        } else {
                            LazyColumn(Modifier.fillMaxSize(), listState) {
                                list.forEach { g ->
                                    item(key = "h:" + g.files[0].path) { GroupHeader(header(g)) }
                                    items(g.files, key = { it.path }) { f ->
                                        val oldest = f === g.files[0]
                                        Box(Modifier.animateItem()) {
                                            ListRow(
                                                Entry(f, false, g.size, f.lastModified(), 0), f.path in selected, onClick = { toggle(g, f) }, onLongClick = { onOpen(f) },
                                                note = (if (oldest) "OLDEST · " else "") + "${size(g.size)} · ${dateText(f.lastModified())}", path = f.parent,
                                            )
                                        }
                                    }
                                }
                            }
                            DragScrollBar(total, listState.firstVisibleItemIndex, listState.layoutInfo.visibleItemsInfo.size, { listState.scrollToItem(it) }, Modifier.align(Alignment.TopEnd))
                        }
                    }
                }
            }
        }
        if (list.isNotEmpty()) {
            ActionBar(
                buildList {
                    add(BarItem("SELECT EXTRAS", cb.mint) { selected = list.flatMap { it.files.drop(1) }.map { it.path }.toSet() })
                    if (selected.isNotEmpty()) {
                        add(BarItem("CLEAR", cb.inkDim) { selected = emptySet() })
                        add(BarItem("DELETE", RED) { erase = list.flatMap { it.files }.filter { it.path in selected } })
                    }
                },
            )
        }
    }

    erase?.let { files ->
        CbDialog("MOVE TO TRASH", { erase = null }) {
            Text(
                "Move ${files.size} ${if (files.size == 1) "copy" else "copies"} (${size(files.sumOf { sizeOf[it.path] ?: 0 })}) to the trash? You can restore ${if (files.size == 1) "it" else "them"} for $BIN_DAYS days.",
                style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2),
            )
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                ActionText("CANCEL", cb.inkDim) { erase = null }
                ActionText("MOVE TO TRASH", RED) {
                    erase = null
                    scope.launch {
                        Toast.makeText(context, "Moved ${moveToBin(context, files)} to the trash", Toast.LENGTH_SHORT).show()
                        // what is gone leaves its group; a group left with one file is no longer a duplicate
                        groups = list.orEmpty().map { g -> DupGroup(g.size, g.files.filter { it.exists() }) }.filter { it.files.size > 1 }
                        selected = emptySet()
                        onChanged()
                    }
                }
            }
        }
    }
}
