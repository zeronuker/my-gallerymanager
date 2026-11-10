package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.background
import android.app.Activity
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.claudeborne.gallerymanager.data.MediaItem

private class Album(val id: Long, val name: String, val cover: MediaItem?, val count: Int)

/** The albums grid. A long press selects albums, then the bar acts on every photo and video in them, as it does for files in the Files tab. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumsScreen(items: List<MediaItem>, favorites: Set<Long>, tools: FileTools, onChanged: () -> Unit, belowHeader: @Composable () -> Unit = {}, onOpen: (Long) -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    // items arrive newest first, so the first of each bucket is its cover and albums sort by recent activity;
    // Favorites is a virtual album that always comes first
    val albums = remember(items, favorites) {
        val favs = items.filter { it.id in favorites }
        listOf(Album(FAVORITES_ID, "Favorites", favs.firstOrNull(), favs.size)) +
            items.groupBy { it.bucketId }.map { (id, list) -> Album(id, list.first().bucketName.ifEmpty { "Unknown" }, list.first(), list.size) }
    }

    var selected by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }
    // everything in the selected albums; Favorites is a view over photos that live in other albums
    val chosen = remember(selected, items, favorites) { items.filter { it.bucketId in selected || (FAVORITES_ID in selected && it.id in favorites) }.distinctBy { it.id } }
    val editable = FAVORITES_ID !in selected // renaming or trashing "Favorites" would act on photos that belong elsewhere
    fun toggle(id: Long) { selected = if (id in selected) selected - id else selected + id }
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) { selected = emptySet(); onChanged() }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("ALBUMS", if (selecting) "${selected.size} SELECTED" else "${albums.size - 1} ALBUMS") // Favorites is not counted
        belowHeader()
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(albums, key = { it.id }) { a -> AlbumCard(a, a.id in selected, onClick = { if (selecting) toggle(a.id) else onOpen(a.id) }, onLongClick = { toggle(a.id) }) }
        }
        if (selecting) ActionBar(
            buildList {
                add(BarItem("ALL", cb.inkDim) { selected = albums.drop(1).map { it.id }.toSet() })
                add(BarItem("SHARE", cb.mint) { shareMedia(context, chosen) })
                add(BarItem("COPY", cb.mint) { tools.copy(chosen) { selected = emptySet() } })
                add(BarItem("MOVE", cb.mint) { tools.move(chosen) { selected = emptySet() } })
                if (editable && selected.size == 1) add(BarItem("RENAME", cb.mint) { tools.renameAlbum(chosen) })
                if (editable) add(BarItem("TRASH", RED) {
                    val request = MediaStore.createTrashRequest(context.contentResolver, chosen.map { it.uri }, true)
                    trashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                })
            },
        )
    }
}

// Card frame, all in dp: distance of the outer line, inner line and picture from the card edge
private const val OUTER_AT = 0.75f
private const val INNER_AT = 4f
private const val PICTURE_AT = 6f
private const val CUT = 10f // chamfer leg of the outer line
private const val SHRINK = 0.586f // 2 - sqrt(2): chamfer leg lost per dp of inset, keeps the lines parallel

private fun chamferLeg(at: Float) = CUT - (at - OUTER_AT) * SHRINK

internal fun chamfered(w: Float, h: Float, inset: Float, leg: Float) = Path().apply {
    val r = w - inset
    val b = h - inset
    moveTo(inset + leg, inset); lineTo(r - leg, inset); lineTo(r, inset + leg); lineTo(r, b - leg)
    lineTo(r - leg, b); lineTo(inset + leg, b); lineTo(inset, b - leg); lineTo(inset, inset + leg); close()
}

/** Brand card frame: chamfered double line (outer 1.5dp, inner 1dp) with an optional chamfered [fill]. Content needs about 6dp of padding. */
internal fun Modifier.doubleChamferFrame(color: Color, fill: Color? = null) = drawBehind {
    if (fill != null) drawPath(chamfered(size.width, size.height, OUTER_AT * density, CUT * density), fill)
    drawPath(chamfered(size.width, size.height, OUTER_AT * density, CUT * density), color, style = Stroke(1.5f * density, join = StrokeJoin.Miter))
    drawPath(chamfered(size.width, size.height, INNER_AT * density, chamferLeg(INNER_AT) * density), color, style = Stroke(1f * density, join = StrokeJoin.Miter))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(album: Album, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cb = LocalCb.current
    val dp = LocalDensity.current.density
    val pictureShape = remember(dp) { GenericShape { size, _ -> addPath(chamfered(size.width, size.height, 0f, chamferLeg(PICTURE_AT) * dp)) } }
    Column(
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).doubleChamferFrame(cb.mint, if (selected) cb.surface2 else null).padding(PICTURE_AT.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(pictureShape).background(cb.surface2), Alignment.Center) {
            if (album.cover != null) AsyncImage(album.cover.uri, album.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else CbIcon(Icon.HEART, cb.inkDim, size = 40.dp)
            if (selected) SelectedMark()
        }
        Row(Modifier.padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(
                album.name,
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = cb.ink),
            )
            Text("${album.count} ITEMS", Modifier.padding(start = 8.dp), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = cb.inkDim))
        }
    }
}
