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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import kotlin.random.Random

/** An album: every photo and video in one folder, newest first. Favorites is a virtual album over photos that live elsewhere. */
private class Album(val id: Long, val name: String, val items: List<MediaItem>) {
    val count get() = items.size
    val newestMs get() = items.maxOfOrNull { it.takenMs } ?: 0L
}

/** The phone's main camera folder. */
private fun isCameraPath(path: String) = path.startsWith("DCIM/Camera", ignoreCase = true)

/**
 * The albums tab: a RECENT row, the phone's CAMERA folder as a hero, then ALL ALBUMS, each with a stacked cover (a collage of its newest photos)
 * and a strip of random photos and videos from inside it (picked again each time the tab is opened). A long press selects albums, then the bar
 * acts on every photo and video in them, as it does for files in the Files tab.
 * Favorites leads the list when something is marked, otherwise it sits at the bottom.
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
    val recent = remember(others) { others.take(6) }
    val favAlbum = remember(items, favorites) { Album(FAVORITES_ID, "Favorites", items.filter { it.id in favorites }) }
    val listAlbums = remember(others, favAlbum) { if (favAlbum.count > 0) listOf(favAlbum) + others else others + favAlbum }
    val now = remember(items) { System.currentTimeMillis() }

    // a new draw of the strips each time this page comes into view
    val active = LocalPageActive.current
    var seed by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) { if (active) seed++ }

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
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (recent.isNotEmpty()) {
                item(key = "h-recent") { SectionLabel("RECENT") }
                item(key = "recent") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recent, key = { it.id }) { a -> RecentCard(a, a.id in selected, now, onClick = { if (selecting) toggle(a.id) else onOpen(a.id) }, onLongClick = { toggle(a.id) }) }
                    }
                }
            }
            if (camera != null) {
                item(key = "h-camera") { SectionLabel("CAMERA") }
                item(key = "camera") { CameraHero(camera, camera.id in selected, now, onClick = { if (selecting) toggle(camera.id) else onOpen(camera.id) }, onLongClick = { toggle(camera.id) }) }
            }
            item(key = "h-all") { SectionLabel("ALL ALBUMS", "${listAlbums.size - 1} ALBUMS") } // Favorites is not counted
            items(listAlbums, key = { it.id }) { a ->
                Box(Modifier.animateItem()) { AlbumRow(a, a.id in selected, now, seed, onClick = { if (selecting) toggle(a.id) else onOpen(a.id) }, onLongClick = { toggle(a.id) }) }
            }
        }
        AnimatedVisibility(selecting, enter = barEnter, exit = barExit) { ActionBar(
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
        ) }
    }
}

/** A mono section label with a line running out to the right, and an optional count at the far end. */
@Composable
private fun SectionLabel(text: String, trailing: String = "") {
    val cb = LocalCb.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

/** "2H AGO", "3D AGO": how long ago, in the brand's short mono form. */
private fun agoText(now: Long, ms: Long): String {
    val min = ((now - ms) / 60000).coerceAtLeast(0)
    return when {
        min < 60 -> "${min.coerceAtLeast(1)}M AGO"
        min < 60 * 24 -> "${min / 60}H AGO"
        min < 60 * 24 * 14 -> "${min / (60 * 24)}D AGO"
        else -> "${min / (60 * 24 * 7)}W AGO"
    }
}

private fun updatedText(now: Long, ms: Long) = if (ms <= 0) "EMPTY" else if (now - ms < 24 * 3600_000L) "UPDATED TODAY" else "UPDATED ${agoText(now, ms)}"

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

/** A card in the RECENT row: the newest picture, the name, the count, and how long ago it changed. */
@Composable
private fun RecentCard(album: Album, selected: Boolean, now: Long, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cb = LocalCb.current
    Column(Modifier.width(104.dp).pressCombined(0.95f, onClick, onLongClick)) {
        Box(Modifier.size(104.dp).border(3.dp, glide(selected, cb.mint, Color.Transparent))) {
            Thumb(album.items.firstOrNull(), Modifier.fillMaxSize(), chamferShape(10f))
            Text(agoText(now, album.newestMs), Modifier.align(Alignment.TopEnd).background(cb.mint).padding(horizontal = 6.dp, vertical = 2.dp),
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = cb.surface0))
            SelectedMark(selected)
        }
        Text(album.name, Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = cb.ink))
        Text("${album.count} ITEMS", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = cb.inkDim))
    }
}

/** The phone's main camera folder: a wide card with a collage of its newest pictures. */
@Composable
private fun CameraHero(album: Album, selected: Boolean, now: Long, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cb = LocalCb.current
    val shape = chamferShape(10f)
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(176.dp).pressCombined(0.985f, onClick, onLongClick).clip(shape).background(cb.surface2)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Thumb(album.items.getOrNull(0), Modifier.weight(2f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Thumb(album.items.getOrNull(1), Modifier.weight(1f).fillMaxWidth())
                Thumb(album.items.getOrNull(2), Modifier.weight(1f).fillMaxWidth())
            }
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color(0xE605080F))))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("MAIN CAMERA", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 2.sp, color = cb.mint))
                Text("CAMERA", style = TextStyle(fontFamily = Tourney, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Color.White))
                Text("${album.count} ITEMS · ${updatedText(now, album.newestMs).removePrefix("UPDATED ")}", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = Color(0xFFD5DBEA)))
            }
            Text("OPEN →", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.5.sp, color = cb.mint))
        }
        if (selected) Box(Modifier.matchParentSize().border(3.dp, cb.mint))
        SelectedMark(selected)
    }
}

/**
 * The cover: a front tile holding a 2x2 collage of the newest pictures, over two tilted ones. [fan] 1 is at rest; the back tiles tilt further
 * while the row is pressed. An album with nothing in it shows a heart (Favorites) on the front.
 */
@Composable
private fun StackCover(album: Album, fan: Float) {
    val cb = LocalCb.current
    val dp = LocalDensity.current.density
    val tile = chamferShape(5f)
    Box(Modifier.size(78.dp)) {
        Thumb(album.items.getOrNull(5) ?: album.items.getOrNull(2), Modifier.matchParentSize().graphicsLayer { rotationZ = 7f * fan; translationX = 4f * dp * fan; alpha = 0.8f }, tile)
        Thumb(album.items.getOrNull(4) ?: album.items.getOrNull(1), Modifier.matchParentSize().graphicsLayer { rotationZ = -8f * fan; translationX = -4f * dp * fan; alpha = 0.65f }, tile)
        Box(Modifier.matchParentSize().clip(tile).background(cb.surface0), Alignment.Center) {
            if (album.items.isEmpty()) CbIcon(Icon.HEART, cb.inkDim, size = 28.dp)
            else Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    Thumb(album.items.getOrNull(0), Modifier.weight(1f).fillMaxHeight()); Thumb(album.items.getOrNull(1), Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    Thumb(album.items.getOrNull(2), Modifier.weight(1f).fillMaxHeight()); Thumb(album.items.getOrNull(3), Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/** An album in ALL ALBUMS: the stacked cover, the name and count, when it last changed, and a strip of random pictures from inside it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumRow(album: Album, selected: Boolean, now: Long, seed: Int, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cb = LocalCb.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fan by animateFloatAsState(if (pressed) 1.7f else 1f, spring(0.5f, 500f), label = "fan")
    val tint by animateColorAsState(if (selected) cb.mint.copy(alpha = 0.08f) else Color.Transparent, tween(MOTION_FAST_MS), label = "rowTint")
    val strip = remember(album.items, seed) { album.items.shuffled(Random(seed * 7919L + album.id)).take(5) }
    val tiny = chamferShape(4f)
    Box(Modifier.fillMaxWidth().pressScale(source, 0.98f).background(tint).combinedClickable(source, null, onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StackCover(album, fan)
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(end = 22.dp)) {
                    Text(album.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (album.count == 0) cb.inkDim else cb.ink))
                    Text("${album.count}", Modifier.padding(start = 8.dp), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = cb.inkDim))
                }
                Text(updatedText(now, album.newestMs), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = cb.inkDim))
                Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(5) { i -> if (i < strip.size) Thumb(strip[i], Modifier.weight(1f).aspectRatio(1f), tiny) else Spacer(Modifier.weight(1f)) }
                    if (album.count > 5) Box(Modifier.weight(1f).aspectRatio(1f).border(1.dp, cb.lineStrong), Alignment.Center) {
                        Text("+${album.count - 5}", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 9.sp, color = cb.inkDim))
                    } else Spacer(Modifier.weight(1f))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomCenter).background(cb.line))
        SelectedMark(selected)
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
