package com.claudeborne.gallerymanager.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.data.Settings
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

/** Date-grouped grid of [items]. Used for the Photos tab and for a single album ([onBack] set). */
@Composable
fun PhotosScreen(
    items: List<MediaItem>,
    title: String,
    tools: FileTools,
    onChanged: () -> Unit,
    onCollage: (List<MediaItem>) -> Unit,
    onJoin: (List<MediaItem>) -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
    onBack: (() -> Unit)? = null,
    empty: String = "NO PHOTOS OR VIDEOS",
    belowHeader: @Composable () -> Unit = {}, // the Gallery sub-tabs, which sit between the header and the grid
    onOpen: (MediaItem) -> Unit,
) {
    val cb = LocalCb.current
    val context = LocalContext.current
    var cols by rememberSaveable { mutableIntStateOf(Settings.gridCols) }
    val groups = remember(items) { items.groupBy { dayLabel(it.takenMs) }.toList() }

    // long-press starts selecting; while anything is selected a tap toggles instead of opening
    var selected by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    val chosen = items.filter { it.id in selected }
    val selecting = chosen.isNotEmpty()
    fun toggle(id: Long) { selected = if (id in selected) selected - id else selected + id }
    BackHandler(enabled = selecting) { selected = emptySet() }
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) { selected = emptySet(); onChanged() }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, if (selecting) "${chosen.size} SELECTED" else "${items.size} ITEMS", onBack, backLabel = "ALBUMS", shortcuts = true)
        belowHeader()

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(empty, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 2.sp, color = cb.inkDim))
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = gridState,
            modifier = Modifier.weight(1f).pinchToResize(cols) { cols = it },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            groups.forEach { (label, day) ->
                item(key = label, span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        label,
                        Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
                        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.4.sp, color = cb.mint),
                    )
                }
                items(day, key = { it.id }) { item ->
                    Tile(item, item.id in selected, onClick = { if (selecting) toggle(item.id) else onOpen(item) }, onLongClick = { toggle(item.id) })
                }
            }
        }

        if (selecting) {
            Row(Modifier.fillMaxWidth().background(cb.surface1).padding(vertical = 8.dp)) {
                BarAction("ALL", cb.inkDim) { selected = items.map { it.id }.toSet() }
                BarAction("SHARE", cb.mint) { share(context, chosen) }
                BarAction("COPY", cb.mint) { tools.copy(chosen) { selected = emptySet() } }
                BarAction("MOVE", cb.mint) { tools.move(chosen) { selected = emptySet() } }
                if (chosen.all { it.isVideo }) BarAction("JOIN", cb.mint) { // only videos selected: put them one after another
                    when {
                        chosen.size < 2 -> Toast.makeText(context, "Select at least 2 videos", Toast.LENGTH_SHORT).show()
                        chosen.size > 10 -> { Toast.makeText(context, "A join holds up to 10 clips; using the first 10", Toast.LENGTH_SHORT).show(); onJoin(chosen.take(10)) }
                        else -> onJoin(chosen)
                    }
                } else BarAction("COLLAGE", cb.mint) {
                    val photos = chosen.filterNot { it.isVideo }
                    when {
                        photos.size < 2 -> Toast.makeText(context, "Select at least 2 photos", Toast.LENGTH_SHORT).show()
                        photos.size > 9 -> { Toast.makeText(context, "A collage holds up to 9 photos; using the first 9", Toast.LENGTH_SHORT).show(); onCollage(photos.take(9)) }
                        else -> onCollage(photos)
                    }
                }
                BarAction("TRASH", Color(0xFFFF6B6B)) {
                    val request = MediaStore.createTrashRequest(context.contentResolver, chosen.map { it.uri }, true)
                    trashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
            }
        }
    }
}

/** One action in the selection bar; they share the width evenly so six fit on a phone. */
@Composable
private fun RowScope.BarAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 10.dp),
        style = monoStyle(color, 1.2.sp).copy(textAlign = TextAlign.Center),
    )
}

private fun share(context: Context, items: List<MediaItem>) = shareMedia(context, items)

/** Mint check in the corner of a selected tile. */
@Composable
internal fun BoxScope.SelectedMark() {
    Text(
        "✓",
        Modifier.align(Alignment.TopEnd).padding(4.dp).background(LocalCb.current.mint).padding(horizontal = 5.dp),
        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = DarkCb.surface0),
    )
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
        if (selected) SelectedMark()
        if (item.isVideo) {
            Text(
                "▶ ${duration(item.durationMs)}",
                Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 2.dp),
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, color = Color.White),
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
