package com.claudeborne.gallerymanager.ui

import android.app.Activity
import android.content.Intent
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.claudeborne.gallerymanager.data.EXTERNAL_ID
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.data.readExif
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TrashRed = Color(0xFFFF6B6B)
private val infoDateFmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

/** Full-screen swipeable viewer over [items], starting at the item with [startId]. [onChanged] fires after the library changed (trash). */
@Composable
fun Viewer(items: List<MediaItem>, startId: Long, onPageChange: (Long) -> Unit, onChanged: () -> Unit, onEdit: (MediaItem) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagerState(items.indexOfFirst { it.id == startId }.coerceAtLeast(0)) { items.size }
    var showInfo by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) } // slideshow
    val current = items.getOrNull(pager.currentPage)
    LightStatusIcons()
    BackHandler(enabled = showInfo) { showInfo = false }

    LaunchedEffect(pager, items) {
        if (items.isEmpty()) onClose()
        snapshotFlow { pager.settledPage }.collect { items.getOrNull(it)?.let { m -> onPageChange(m.id) } }
    }

    // slideshow: every 3 s to the next photo; stops at the end or on a video
    LaunchedEffect(playing, pager.currentPage) {
        if (!playing) return@LaunchedEffect
        if (items.getOrNull(pager.currentPage)?.isVideo == true || pager.currentPage >= items.size - 1) { playing = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(3000)
        pager.animateScrollToPage(pager.currentPage + 1)
    }
    val favLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) onChanged()
    }

    // Android asks the user to confirm moving the item to the system trash, where it stays recoverable for ~30 days
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) onChanged()
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { items.getOrNull(it)?.id ?: it }) { page ->
                val item = items.getOrNull(page) ?: return@HorizontalPager
                if (item.isVideo) VideoPage(item, active = pager.settledPage == page) else ZoomableImage(item)
            }

            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("← BACK", Modifier.clickable(onClick = onClose), style = monoStyle(DarkCb.mint, 2.6.sp))
                Box(Modifier.weight(1f))
                Text("${pager.currentPage + 1} / ${items.size}", style = monoStyle(Color.White, 1.sp))
            }

            if (showInfo && current != null) {
                InfoPanel(current, Modifier.align(Alignment.BottomCenter))
            }
        }

        Row(
            Modifier.fillMaxWidth().background(Color(0xFF0A1020)).navigationBarsPadding().padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ActionButton("SHARE", DarkCb.mint, enabled = current != null) {
                current?.let {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = it.mime.ifEmpty { "*/*" }
                        putExtra(Intent.EXTRA_STREAM, it.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, null))
                }
            }
            ActionButton("INFO", DarkCb.mint, enabled = current != null) { showInfo = !showInfo }
            if (current != null && current.id != EXTERNAL_ID) {
                ActionButton(if (current.favorite) "♥" else "♡", Color(0xFFFF6B8B), enabled = true) {
                    val request = MediaStore.createFavoriteRequest(context.contentResolver, listOf(current.uri), !current.favorite)
                    favLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
            }
            if (items.size > 1) ActionButton(if (playing) "STOP" else "SLIDE", DarkCb.mint, enabled = true) { playing = !playing }
            if (current != null && !current.isVideo) ActionButton("EDIT", DarkCb.mint, enabled = true) { onEdit(current) }
            if (current?.id != EXTERNAL_ID) {
                ActionButton("TRASH", TrashRed, enabled = current != null) {
                    current?.let {
                        val request = MediaStore.createTrashRequest(context.contentResolver, listOf(it.uri), true)
                        trashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    }
                }
            }
        }
    }
}

private fun monoStyle(color: Color, spacing: androidx.compose.ui.unit.TextUnit) =
    TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = spacing, color = color)

@Composable
private fun ActionButton(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        style = monoStyle(color, 1.2.sp),
    )
}

/** File facts from MediaStore plus camera/GPS rows read from EXIF when the item is a photo. */
@Composable
private fun InfoPanel(item: MediaItem, modifier: Modifier) {
    val context = LocalContext.current
    val exif by produceState(emptyList(), item.id) { value = readExif(context, item) }
    val rows = buildList {
        add("NAME" to item.name)
        if (item.takenMs > 0) add("DATE" to Instant.ofEpochMilli(item.takenMs).atZone(ZoneId.systemDefault()).format(infoDateFmt))
        if (item.width > 0) add("RESOLUTION" to "${item.width} × ${item.height}")
        if (item.isVideo && item.durationMs > 0) add("DURATION" to "%d:%02d".format(item.durationMs / 60000, item.durationMs / 1000 % 60))
        if (item.size > 0) add("SIZE" to Formatter.formatShortFileSize(context, item.size))
        if (item.mime.isNotEmpty()) add("TYPE" to item.mime)
        if (item.path.isNotEmpty()) add("PATH" to item.path + item.name)
        addAll(exif)
    }
    Column(
        modifier.fillMaxWidth().heightIn(max = 360.dp).background(Color(0xFF141A2E)).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("INFO", style = monoStyle(DarkCb.mint, 2.6.sp))
        rows.forEach { (label, value) ->
            Column {
                Text(label, style = monoStyle(DarkCb.inkDim, 1.5.sp))
                Text(value, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = DarkCb.ink))
            }
        }
    }
}

/** The viewer is always black, so keep the status bar icons light while it is open. */
@Composable
private fun LightStatusIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val controller = WindowCompat.getInsetsController((view.context as Activity).window, view)
        val before = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = before }
    }
}

// ponytail: zooms around the center, and pan stops the pager while zoomed; upgrade to centroid zoom + edge hand-off if needed
@Composable
private fun ZoomableImage(item: MediaItem) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier.fillMaxSize().clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        // one finger at 1x is left alone so the pager can swipe
                        if ((event.changes.size > 1 || scale > 1f) && (zoom != 1f || pan != Offset.Zero)) {
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            val maxX = size.width * (scale - 1f) / 2f
                            val maxY = size.height * (scale - 1f) / 2f
                            offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
    ) {
        AsyncImage(
            ImageRequest.Builder(context).data(item.uri).size(2400).build(),
            item.name,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}

/** Only the page on screen owns a player; others show the thumbnail. */
@Composable
private fun VideoPage(item: MediaItem, active: Boolean) {
    if (!active) {
        AsyncImage(item.uri, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        return
    }
    val context = LocalContext.current
    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(PlayerItem.fromUri(item.uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }
    AndroidView({ PlayerView(it).apply { this.player = player } }, Modifier.fillMaxSize())
}
