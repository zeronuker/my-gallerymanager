package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

/** Date-grouped grid of [items]. Used for the Photos tab and for a single album ([onBack] set). */
@Composable
fun PhotosScreen(
    items: List<MediaItem>,
    title: String,
    gridState: LazyGridState = rememberLazyGridState(),
    onBack: (() -> Unit)? = null,
    onOpen: (MediaItem) -> Unit,
) {
    val cb = LocalCb.current
    var cols by rememberSaveable { mutableIntStateOf(4) }
    val groups = remember(items) { items.groupBy { dayLabel(it.takenMs) }.toList() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, "${items.size} ITEMS", onBack, backLabel = "ALBUMS")

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("NO PHOTOS OR VIDEOS", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 2.sp, color = cb.inkDim))
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = gridState,
            modifier = Modifier.fillMaxSize().pinchToResize(cols) { cols = it },
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
                items(day, key = { it.id }) { Tile(it) { onOpen(it) } }
            }
        }
    }
}

@Composable
private fun Tile(item: MediaItem, onClick: () -> Unit) {
    val cb = LocalCb.current
    Box(Modifier.aspectRatio(1f).background(cb.surface2).clickable(onClick = onClick)) {
        AsyncImage(item.uri, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
