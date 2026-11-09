package com.claudeborne.gallerymanager.ui

import android.app.Activity
import android.app.PendingIntent
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
fun TrashScreen(items: List<MediaItem>, onChanged: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    val chosen = items.filter { it.id in selected }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) { selected = emptySet(); onChanged() }
    }
    fun ask(request: PendingIntent) = launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    // trashed items carry an includeTrashed flag for display; the requests want the plain uri
    fun plainUris() = chosen.map { it.uri.buildUpon().clearQuery().build() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("TRASH", if (chosen.isEmpty()) "${items.size} ITEMS" else "${chosen.size} SELECTED")

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
                TrashTile(item, item.id in selected) { selected = if (item.id in selected) selected - item.id else selected + item.id }
            }
        }

        if (chosen.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(cb.surface1).padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ActionText("ALL", cb.inkDim) { selected = items.map { it.id }.toSet() }
                ActionText("RESTORE", cb.mint) { ask(MediaStore.createTrashRequest(context.contentResolver, plainUris(), false)) }
                ActionText("DELETE", Color(0xFFFF6B6B)) { ask(MediaStore.createDeleteRequest(context.contentResolver, plainUris())) }
            }
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

@Composable
private fun TrashTile(item: MediaItem, selected: Boolean, onClick: () -> Unit) {
    val cb = LocalCb.current
    val daysLeft = ((item.expiresMs - System.currentTimeMillis()) / DAY_MS).coerceAtLeast(0)
    Box(
        Modifier.aspectRatio(1f).background(cb.surface2).clickable(onClick = onClick)
            .then(if (selected) Modifier.border(3.dp, cb.mint) else Modifier),
    ) {
        AsyncImage(item.uri, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (item.expiresMs > 0) {
            Text(
                "${daysLeft}D",
                Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 2.dp),
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, color = Color.White),
            )
        }
        if (selected) {
            Text(
                "✓",
                Modifier.align(Alignment.TopEnd).padding(4.dp).background(cb.mint).padding(horizontal = 5.dp),
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = DarkCb.surface0),
            )
        }
    }
}
