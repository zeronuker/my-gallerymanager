package com.claudeborne.gallerymanager.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.claudeborne.gallerymanager.data.MediaItem

/** Full-screen swipeable viewer over [items], starting at the item with [startId]. */
@Composable
fun Viewer(items: List<MediaItem>, startId: Long, onPageChange: (Long) -> Unit, onClose: () -> Unit) {
    val pager = rememberPagerState(items.indexOfFirst { it.id == startId }.coerceAtLeast(0)) { items.size }
    LightStatusIcons()

    LaunchedEffect(pager, items) {
        if (items.isEmpty()) onClose()
        snapshotFlow { pager.settledPage }.collect { items.getOrNull(it)?.let { m -> onPageChange(m.id) } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { items.getOrNull(it)?.id ?: it }) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            if (item.isVideo) VideoPage(item, active = pager.settledPage == page) else ZoomableImage(item)
        }

        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                .statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "← BACK",
                Modifier.clickable(onClick = onClose),
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.6.sp, color = DarkCb.mint),
            )
            Box(Modifier.weight(1f))
            Text(
                "${pager.currentPage + 1} / ${items.size}",
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.sp, color = Color.White),
            )
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
