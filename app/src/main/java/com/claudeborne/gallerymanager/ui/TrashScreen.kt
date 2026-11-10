package com.claudeborne.gallerymanager.ui

import android.app.Activity
import android.app.PendingIntent
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.claudeborne.gallerymanager.data.MediaItem

private const val DAY_MS = 86_400_000L

/** Items in the system trash: tap to select, then restore them or delete them for good. Android asks to confirm both. */
@Composable
fun TrashScreen(items: List<MediaItem>, onChanged: () -> Unit, onBack: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var viewing by remember { mutableStateOf<Long?>(null) } // the item open full-screen to look at before deciding
    val chosen = items.filter { it.id in selected }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) { selected = emptySet(); onChanged() }
    }
    fun ask(request: PendingIntent) = launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    // trashed items carry an includeTrashed flag for display; the requests want the plain uri
    fun plainUris(of: List<MediaItem>) = of.map { it.uri.buildUpon().clearQuery().build() }
    fun restore(of: List<MediaItem>) = ask(MediaStore.createTrashRequest(context.contentResolver, plainUris(of), false))
    fun deleteForever(of: List<MediaItem>) = ask(MediaStore.createDeleteRequest(context.contentResolver, plainUris(of)))

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("TRASH", if (chosen.isEmpty()) "${items.size} ITEMS" else "${chosen.size} SELECTED", onBack, "BACK")

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("TRASH IS EMPTY", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 2.sp, color = cb.inkDim))
            }
            return@Column
        }

        Text(
            "Items are removed for good after about 30 days.",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items, key = { it.id }) { item ->
                // a tap looks at the item (a small thumbnail is hard to judge); a long press, or a tap once something is selected, selects it
                val toggle = { selected = if (item.id in selected) selected - item.id else selected + item.id }
                TrashTile(item, item.id in selected, onClick = { if (selected.isEmpty()) viewing = item.id else toggle() }, onLongClick = toggle)
            }
        }

        if (chosen.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(cb.surface1).padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ActionText("ALL", cb.inkDim) { selected = items.map { it.id }.toSet() }
                ActionText("RESTORE", cb.mint) { restore(chosen) }
                ActionText("DELETE", Color(0xFFFF6B6B)) { deleteForever(chosen) }
            }
        }
    }
    viewing?.let { id ->
        TrashViewer(items, id, onRestore = { restore(listOf(it)) }, onDelete = { deleteForever(listOf(it)) }, onClose = { viewing = null })
    }
    }
}

/** Full-screen look at trashed items, swipeable, with restore and delete forever; Android asks to confirm either. */
@Composable
private fun TrashViewer(items: List<MediaItem>, startId: Long, onRestore: (MediaItem) -> Unit, onDelete: (MediaItem) -> Unit, onClose: () -> Unit) {
    val pager = rememberPagerState(items.indexOfFirst { it.id == startId }.coerceAtLeast(0)) { items.size }
    val current = items.getOrNull(pager.currentPage)
    BackHandler(onBack = onClose)
    LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) onClose() } // the last one was restored or deleted
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("← BACK", Modifier.clickable(onClick = onClose), style = monoStyle(DarkCb.mint, 2.6.sp))
            Box(Modifier.weight(1f))
            current?.let {
                val left = daysLeft(it)
                Text("${pager.currentPage + 1} / ${items.size}" + if (left.isEmpty()) "" else " · ${left.uppercase()} LEFT", style = monoStyle(Color.White, 1.sp))
            }
        }
        Box(Modifier.weight(1f)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { items.getOrNull(it)?.id ?: it }) { page ->
                val item = items.getOrNull(page) ?: return@HorizontalPager
                if (item.isVideo) VideoPage(item, active = pager.settledPage == page, chrome = true, onTap = {}) else ZoomableImage(item, onTap = {})
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF0A1020)).padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ActionText("RESTORE", DarkCb.mint) { current?.let(onRestore) }
            ActionText("DELETE FOREVER", Color(0xFFFF6B6B)) { current?.let(onDelete) }
        }
    }
}

@Composable
internal fun ActionText(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.2.sp, color = color),
    )
}

/** "29 days", or "1 day"; empty when the item has no removal date. */
private fun daysLeft(item: MediaItem): String {
    if (item.expiresMs <= 0) return ""
    val days = ((item.expiresMs - System.currentTimeMillis()) / DAY_MS).coerceAtLeast(0)
    return if (days == 1L) "1 day" else "$days days"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashTile(item: MediaItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cb = LocalCb.current
    Box(
        Modifier.aspectRatio(1f).background(cb.surface2).combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selected) Modifier.border(3.dp, cb.mint) else Modifier),
    ) {
        AsyncImage(item.uri, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (item.expiresMs > 0) {
            Text(
                daysLeft(item),
                Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 2.dp),
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, color = Color.White),
            )
        }
        if (selected) SelectedMark()
    }
}
