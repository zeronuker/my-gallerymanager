package com.claudeborne.gallerymanager.ui

import android.app.Activity
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.claudeborne.gallerymanager.data.MediaItem

/** An album: every photo and video in one folder, newest first. Favorites is a virtual album over photos that live elsewhere. */
private class Album(val id: Long, val name: String, val items: List<MediaItem>) {
    val count get() = items.size
    val newestMs get() = items.maxOfOrNull { it.takenMs } ?: 0L
}

/** The phone's main camera folder. */
private fun isCameraPath(path: String) = path.startsWith("DCIM/Camera", ignoreCase = true)

/**
 * The albums tab: the phone's Camera folder as a hero card, then ALL ALBUMS as a three column grid of mosaic tiles (the newest photos inside each).
 * A long press selects albums, then the bar acts on every photo and video in them, as it does for files in the Files tab.
 * Favorites leads the grid when something is marked, otherwise it sits at the end.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumsScreen(items: List<MediaItem>, favorites: Set<Long>, tools: FileTools, onChanged: () -> Unit, onOpen: (Long) -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    // items arrive newest first, so groupBy gives albums by recent activity
    val buckets = remember(items) { items.groupBy { it.bucketId }.map { (id, list) -> Album(id, list.first().bucketName.ifEmpty { "Unknown" }, list) } }
    val camera = remember(buckets) { buckets.filter { b -> b.items.any { isCameraPath(it.path) } }.maxByOrNull { it.count } }
    val others = remember(buckets, camera) { buckets.filter { it !== camera } }
    val favAlbum = remember(items, favorites) { Album(FAVORITES_ID, "Favorites", items.filter { it.id in favorites }) }
    val listAlbums = remember(others, favAlbum) { if (favAlbum.count > 0) listOf(favAlbum) + others else others + favAlbum }
    val seen = remember { HashSet<Long>() } // tiles that already faded in, so scrolling one back into view does not replay it

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
        ScreenHeader("ALBUMS", if (selecting) "${selected.size} SELECTED" else "${buckets.size} ALBUMS", fixed = true) // Favorites is not counted
        val gridState = rememberLazyGridState()
        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(
                GridCells.Fixed(3), Modifier.fillMaxSize(), gridState, contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp + LocalPillInset.current),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (camera != null) item(key = "camera", span = { GridItemSpan(maxLineSpan) }) {
                    CameraHero(camera, camera.id in selected, onClick = { if (selecting) toggle(camera.id) else onOpen(camera.id) }, onLongClick = { toggle(camera.id) })
                }
                item(key = "h-all", span = { GridItemSpan(maxLineSpan) }) { SectionLabel("ALL ALBUMS", "${listAlbums.size - 1} ALBUMS") } // Favorites is not counted
                itemsIndexed(listAlbums, key = { _, a -> a.id }) { i, a ->
                    val first = remember { seen.add(a.id) }
                    Box(Modifier.animateItem()) {
                        AlbumTile(a, a.id in selected, 120 + i.coerceAtMost(8) * 40, first, onClick = { if (selecting) toggle(a.id) else onOpen(a.id) }, onLongClick = { toggle(a.id) })
                    }
                }
            }
            DragScrollBar(gridState, Modifier.align(Alignment.TopEnd))
        }
        if (selecting) ActionBar(
            buildList {
                val everyAlbum = buckets.all { it.id in selected } // Favorites is a view, not an album to pick
                add(BarItem(if (everyAlbum) "DESELECT ALL" else "ALL", cb.inkDim) { selected = if (everyAlbum) emptySet() else buckets.map { it.id }.toSet() })
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

/** A mono section label with a line running out to the right, and an optional count at the far end. */
@Composable
private fun SectionLabel(text: String, trailing: String = "") {
    val cb = LocalCb.current
    Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, style = monoStyle(cb.mint, 2.2.sp))
        Box(Modifier.weight(1f).height(1.dp).background(cb.lineStrong))
        if (trailing.isNotEmpty()) Text(trailing, style = monoStyle(cb.inkDim, 1.sp))
    }
}

/** Chamfered corners of [leg] dp, as on the brand cards. */
@Composable
private fun chamferShape(leg: Float): Shape {
    val dp = LocalDensity.current.density
    return remember(dp, leg) { GenericShape { size, _ -> addPath(chamfered(size.width, size.height, 0f, leg * dp)) } }
}
/** Squishes while pressed and takes taps and long presses. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.pressCombined(target: Float, onClick: () -> Unit, onLongClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    pressScale(source, target).combinedClickable(source, null, onClick = onClick, onLongClick = onLongClick)
}

/** One picture of a photo or video, with a play badge for videos; empty when [item] is null. */
@Composable
private fun Thumb(item: MediaItem?, modifier: Modifier, shape: Shape = RectangleShape) {
    val cb = LocalCb.current
    Box(modifier.clip(shape).background(cb.surface2)) {
        if (item != null) AsyncImage(item.uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (item?.isVideo == true) {
            Text("▶", Modifier.align(Alignment.BottomStart).padding(2.dp).background(Color(0x99000000)).padding(horizontal = 3.dp),
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 8.sp, color = Color.White))
        }
    }
}

/** A 2x2 mosaic of an album's newest pictures in a chamfered tile; Favorites with nothing marked shows a heart. */
@Composable
private fun Mosaic(album: Album, modifier: Modifier) {
    val cb = LocalCb.current
    Box(modifier.clip(chamferShape(8f)).background(cb.surface2), Alignment.Center) {
        if (album.items.isEmpty()) CbIcon(Icon.HEART, cb.inkDim, size = 28.dp)
        else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                Thumb(album.items.getOrNull(0), Modifier.weight(1f).fillMaxHeight()); Thumb(album.items.getOrNull(1), Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                Thumb(album.items.getOrNull(2), Modifier.weight(1f).fillMaxHeight()); Thumb(album.items.getOrNull(3), Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** The phone's main camera folder: a wide card with a mosaic of its newest pictures, the name and the count on a fade. */
@Composable
private fun CameraHero(album: Album, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cb = LocalCb.current
    Box(Modifier.fillMaxWidth().height(196.dp).pressCombined(0.985f, onClick, onLongClick).clip(chamferShape(10f)).background(cb.surface2)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Thumb(album.items.getOrNull(0), Modifier.weight(2f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Thumb(album.items.getOrNull(1), Modifier.weight(1f).fillMaxWidth())
                Thumb(album.items.getOrNull(2), Modifier.weight(1f).fillMaxWidth())
            }
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xE605080F))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("Camera", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Color.White))
            RollingCount("${album.count} ITEMS", TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = Color(0xFFD5DBEA)))
        }
        Box(Modifier.matchParentSize().border(3.dp, glide(selected, cb.mint, Color.Transparent)))
        SelectedMark(selected)
    }
}

/** An album in ALL ALBUMS: the mosaic, then the name and the count. It squishes while pressed and fades in one after another when first shown. */
@Composable
private fun AlbumTile(album: Album, selected: Boolean, delayMs: Int, first: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cb = LocalCb.current
    Column(Modifier.appear(delayMs, first).pressCombined(0.95f, onClick, onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).border(3.dp, glide(selected, cb.mint, Color.Transparent))) {
            Mosaic(album, Modifier.fillMaxSize())
            SelectedMark(selected)
        }
        Text(album.name, Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = if (album.count == 0) cb.inkDim else cb.ink))
        RollingCount("${album.count}", TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = cb.inkDim))
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

/**
 * Brand card frame, as ChamferFrame in the web apps: a chamfered double line (outer 1.5dp, inner 1dp) in the brand gradient (mint, blue, violet),
 * with an optional chamfered [fill]. Content needs about 6dp of padding.
 */
internal fun Modifier.doubleChamferFrame(cb: CbColors, fill: Color? = null) = drawBehind {
    val line = androidx.compose.ui.graphics.Brush.linearGradient(listOf(cb.mint, cb.blue, cb.violet), androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(size.width, size.height))
    if (fill != null) drawPath(chamfered(size.width, size.height, OUTER_AT * density, CUT * density), fill)
    drawPath(chamfered(size.width, size.height, OUTER_AT * density, CUT * density), line, style = Stroke(1.5f * density, join = StrokeJoin.Miter))
    drawPath(chamfered(size.width, size.height, INNER_AT * density, chamferLeg(INNER_AT) * density), line, style = Stroke(1f * density, join = StrokeJoin.Miter))
}
