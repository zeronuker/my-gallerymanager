package com.claudeborne.gallerymanager.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.print.PrintHelper
import android.view.LayoutInflater
import com.claudeborne.gallerymanager.R
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.claudeborne.gallerymanager.data.EXTERNAL_ID
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.data.Settings
import com.claudeborne.gallerymanager.data.readExif
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TrashRed = Color(0xFFFF6B6B)
private val infoDateFmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

/** Full-screen swipeable viewer over [items], starting at the item with [startId]. [onChanged] fires after the library changed (trash). */
@Composable
fun Viewer(items: List<MediaItem>, startId: Long, tools: FileTools, favorites: Favorites, onPageChange: (Long) -> Unit, onChanged: () -> Unit, onEdit: (MediaItem) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagerState(items.indexOfFirst { it.id == startId }.coerceAtLeast(0)) { items.size }
    var showInfo by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(Settings.muted) } // the sound switch holds for every video opened while the viewer stays open
    LaunchedEffect(Settings.muted) { muted = Settings.muted } // and follows START MUTED when it is changed in Settings meanwhile
    var export by remember { mutableStateOf<Pair<MediaItem, Boolean>?>(null) } // the photo to resize/convert, and whether to start with location removal on
    var chrome by remember { mutableStateOf(true) } // tapping the media hides every control for a full-screen view
    val scope = rememberCoroutineScope()
    val current = items.getOrNull(pager.currentPage)
    LightStatusIcons()
    Immersive(hidden = !chrome)
    export?.let { (photo, strip) -> ExportDialog(photo, strip, onClose = { export = null }, onSaved = onChanged) }
    BackHandler(enabled = showInfo || showMore) { showInfo = false; showMore = false }

    LaunchedEffect(pager, items) {
        if (items.isEmpty()) onClose()
        snapshotFlow { pager.settledPage }.collect { items.getOrNull(it)?.let { m -> onPageChange(m.id) } }
    }

    // Android asks the user to confirm moving the item to the system trash, where it stays recoverable for ~30 days
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) onChanged()
    }

    // the picture fills the whole screen and the bars float over it, so showing or hiding them never moves or resizes the picture
    var barPx by remember { mutableIntStateOf(0) }
    val barHeight = with(LocalDensity.current) { barPx.toDp() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { items.getOrNull(it)?.id ?: it }) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            // a tap while Info or More is open closes that panel and nothing else; otherwise it shows or hides the controls
            val onTap = { if (showInfo || showMore) { showInfo = false; showMore = false } else chrome = !chrome }
            if (item.isVideo) VideoPage(item, active = pager.settledPage == page, chrome, onTap, muted, { muted = it }, bottomPad = barHeight) else ZoomableImage(item, onTap)
        }

        NavArrow("‹", Alignment.CenterStart, chrome && pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }
        NavArrow("›", Alignment.CenterEnd, chrome && pager.currentPage < items.size - 1) { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }

        Visible(chrome, Modifier.align(Alignment.TopCenter), enter = topBarEnter, exit = topBarExit) { Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                .statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("← BACK", Modifier.clickable(onClick = onClose), style = monoStyle(DarkCb.mint, 2.6.sp))
            Box(Modifier.weight(1f))
        } }

        Column(Modifier.align(Alignment.BottomCenter)) {
            Visible(chrome && showInfo && current != null, enter = popEnter, exit = popExit) {
                current?.let { InfoPanel(it, Modifier) }
            }

            Visible(chrome && showMore && current != null, enter = popEnter, exit = popExit) {
                if (current != null) MorePanel(
                    actions = buildList {
                        if (current.id != EXTERNAL_ID) {
                            add("RENAME" to { tools.rename(current) })
                            add("MOVE" to { tools.move(listOf(current)) })
                            add("COPY" to { tools.copy(listOf(current)) })
                        }
                        if (!current.isVideo) {
                            if (current.id != EXTERNAL_ID) {
                                add("RESIZE · CONVERT" to { export = current to false })
                                add("REMOVE LOCATION" to { export = current to true })
                            }
                            add("SET AS" to { launchChooser(context, Intent.ACTION_ATTACH_DATA, current, "Set as") })
                            add("EDIT WITH" to { launchChooser(context, Intent.ACTION_EDIT, current, "Edit with") })
                            add("PRINT" to { print(context, current) })
                        }
                    },
                    modifier = Modifier,
                ) { showMore = false }
            }

            AnimatedVisibility(chrome, enter = barEnter, exit = barExit) { Row(
                Modifier.fillMaxWidth().onSizeChanged { barPx = it.height }.background(Color(0xF20A1020)).navigationBarsPadding().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconAction(Icon.SHARE, DarkCb.mint, "Share", enabled = current != null) {
                    current?.let { shareMedia(context, listOf(it)) }
                }
                if (current != null && current.id != EXTERNAL_ID) {
                    val favorite = current.id in favorites.ids
                    IconAction(Icon.HEART, DarkCb.mint, if (favorite) "Remove from favorites" else "Add to favorites", filled = favorite) { favorites.toggle(current.id) }
                }
                // editing is for library photos and videos
                if (current != null && current.id != EXTERNAL_ID) IconAction(Icon.EDIT, DarkCb.mint, "Edit") { onEdit(current) }
                if (current?.id != EXTERNAL_ID) {
                    IconAction(Icon.TRASH, TrashRed, "Move to trash", enabled = current != null) {
                        current?.let {
                            val request = MediaStore.createTrashRequest(context.contentResolver, listOf(it.uri), true)
                            trashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                        }
                    }
                }
                IconAction(Icon.INFO, DarkCb.mint, "Info", enabled = current != null) { showInfo = !showInfo; showMore = false }
                // file tools: rename, move and copy for everything; resize, set as and print for photos
                IconAction(Icon.MORE, DarkCb.mint, "More", enabled = current != null) { showMore = !showMore; showInfo = false }
            } }
        }
    }
}

internal fun monoStyle(color: Color, spacing: androidx.compose.ui.unit.TextUnit) =
    TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = spacing, color = color)

private fun clock(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

/** Round previous/next button floating over the side of the media. */
@Composable
private fun BoxScope.NavArrow(glyph: String, at: Alignment, visible: Boolean, onClick: () -> Unit) {
    AnimatedVisibility(visible, Modifier.align(at), enter = fadeIn(tween(MOTION_FAST_MS)), exit = fadeOut(tween(MOTION_FAST_MS))) {
        Box(
            Modifier.padding(8.dp).size(44.dp).clip(CircleShape).background(Color(0x80000000)).clickable(onClick = onClick),
            Alignment.Center,
        ) {
            Text(glyph, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 28.sp, color = DarkCb.mint))
        }
    }
}

/** Menu of file tools; tapping a row closes it with [onDone] and runs that action. */
@Composable
private fun MorePanel(actions: List<Pair<String, () -> Unit>>, modifier: Modifier, onDone: () -> Unit) {
    Column(modifier.fillMaxWidth().padding(12.dp).doubleChamferFrame(DarkCb, DarkCb.surface1).padding(16.dp)) {
        Text("MORE", Modifier.padding(bottom = 4.dp), style = monoStyle(DarkCb.mint, 2.6.sp))
        actions.forEach { (label, run) ->
            Text(
                label, Modifier.fillMaxWidth().clickable { onDone(); run() }.padding(vertical = 12.dp),
                style = monoStyle(DarkCb.ink, 2.2.sp).copy(fontSize = 13.sp),
            )
        }
    }
}

// the receiving app only needs to read the image; it saves any edit as its own file
private fun launchChooser(context: Context, action: String, item: MediaItem, title: String) {
    val intent = Intent(action).setDataAndType(item.uri, item.mime.ifEmpty { "image/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, title))
}

private fun print(context: Context, item: MediaItem) {
    runCatching { PrintHelper(context).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }.printBitmap(item.name, item.uri) }
        .onFailure { Toast.makeText(context, "Couldn't print this image", Toast.LENGTH_SHORT).show() }
}

/** File facts from MediaStore plus camera/GPS rows read from EXIF when the item is a photo. */
@Composable
private fun InfoPanel(item: MediaItem, modifier: Modifier) {
    val context = LocalContext.current
    val exif by produceState(emptyList(), item.id) { value = readExif(context, item) }
    val rows = buildList {
        add("NAME" to item.name)
        if (item.takenMs > 0) add("DATE" to Instant.ofEpochMilli(item.takenMs).atZone(ZoneId.systemDefault()).format(infoDateFmt))
        if (item.size > 0) add("SIZE" to Formatter.formatShortFileSize(context, item.size))
        if (item.width > 0) add("RESOLUTION" to "${item.width} × ${item.height}")
        if (item.isVideo && item.durationMs > 0) add("DURATION" to clock(item.durationMs))
        if (item.mime.isNotEmpty()) add("TYPE" to item.mime)
        addAll(exif)
        if (item.path.isNotEmpty()) add("PATH" to item.path + item.name)
    }
    // NAME and PATH take a whole line; everything else pairs up two to a line
    val full = setOf("NAME", "PATH")
    val lines = buildList<List<Pair<String, String>>> {
        var half: Pair<String, String>? = null
        rows.forEach { row ->
            if (row.first in full) {
                half?.let { add(listOf(it)) }
                half = null
                add(listOf(row))
            } else if (half == null) half = row else { add(listOf(half!!, row)); half = null }
        }
        half?.let { add(listOf(it)) }
    }
    Column(
        modifier.fillMaxWidth().padding(12.dp).heightIn(max = 360.dp).doubleChamferFrame(DarkCb, DarkCb.surface1)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("INFO", Modifier.padding(bottom = 2.dp), style = monoStyle(DarkCb.mint, 2.6.sp))
        lines.forEach { line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                line.forEach { (label, value) -> InfoTile(label, value, Modifier.weight(1f)) }
                if (line.size == 1 && line[0].first !in full) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InfoTile(label: String, value: String, modifier: Modifier) {
    val dp = LocalDensity.current.density
    val shape = remember(dp) { GenericShape { size, _ -> addPath(chamfered(size.width, size.height, 0f, 7f * dp)) } }
    Column(modifier.clip(shape).background(DarkCb.surface2).padding(horizontal = 10.dp, vertical = 7.dp)) {
        Text(label, style = monoStyle(DarkCb.inkDim, 1.5.sp))
        Text(value, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = DarkCb.ink))
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

/** Hides the status and navigation bars while [hidden]; an edge swipe brings them back briefly. */
@Composable
private fun Immersive(hidden: Boolean) {
    val view = LocalView.current
    val controller = remember(view) { WindowCompat.getInsetsController((view.context as Activity).window, view) }
    LaunchedEffect(hidden) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(controller) {
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }
}

@Composable
internal fun ZoomableImage(item: MediaItem, onTap: () -> Unit) {
    val context = LocalContext.current
    ZoomBox(onTap) {
        AsyncImage(
            ImageRequest.Builder(context).data(item.uri).size(2400).build(),
            item.name,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}

/** Double-tap or pinch zooms [content]; a single tap calls [onTap]. */
// ponytail: zooms around the center, and pan stops the pager while zoomed; upgrade to centroid zoom + edge hand-off if needed
@Composable
private fun ZoomBox(onTap: () -> Unit, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().clipToBounds()) {
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }) { content() }
        // gestures sit on a transparent layer above the content, so they also work over a video view
        Box(
            Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = {
                    // glides to 2.5x, or back to 1x with the picture drifting back to the middle
                    scope.launch {
                        val s0 = scale
                        val o0 = offset
                        if (s0 > 1f) animate(s0, 1f, animationSpec = tween(MOTION_MS)) { v, _ -> scale = v; offset = o0 * ((v - 1f) / (s0 - 1f)) }
                        else animate(s0, 2.5f, animationSpec = tween(MOTION_MS)) { v, _ -> scale = v }
                    }
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
        )
    }
}

/** Only the page on screen owns a player; others show the thumbnail. */
@Composable
internal fun VideoPage(item: MediaItem, active: Boolean, chrome: Boolean, onTap: () -> Unit, muted: Boolean, onMuted: (Boolean) -> Unit, bottomPad: Dp = 0.dp) {
    if (!active) {
        AsyncImage(item.uri, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        return
    }
    val context = LocalContext.current
    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(PlayerItem.fromUri(item.uri))
            prepare()
            volume = if (muted) 0f else 1f
            playWhenReady = Settings.autoplay
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(muted, player) { player.volume = if (muted) 0f else 1f }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }
    // the skip buttons move 5 seconds each press; a chip over the video says how far, adding up while the presses keep coming
    var skipped by remember(player) { mutableLongStateOf(0L) } // signed milliseconds moved so far, 0 when no chip shows
    var skipTick by remember(player) { mutableIntStateOf(0) }
    fun skip(by: Long) {
        val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + by).coerceIn(0L, end))
        skipped = if (skipped != 0L && (skipped < 0) == (by < 0)) skipped + by else by
        skipTick++
    }
    LaunchedEffect(skipTick) { if (skipTick > 0) { delay(900); skipped = 0L } }
    var loading by remember(player) { mutableStateOf(true) } // until the first frames are ready
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { loading = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            ZoomBox(onTap) {
                // texture_view (set in the layout) so the video follows the zoom transform; a SurfaceView would not
                AndroidView({ (LayoutInflater.from(it).inflate(R.layout.player_texture, null) as PlayerView).also { v -> v.player = player } }, Modifier.fillMaxSize())
            }
            Visible(loading, Modifier.align(Alignment.Center), enter = fadeIn(tween(MOTION_FAST_MS)), exit = fadeOut(tween(MOTION_FAST_MS))) {
                ProgressBar(null, Modifier.width(160.dp))
            }
            val chip = skipped.takeIf { it != 0L }
            AnimatedOverlay(chip?.takeIf { it < 0 }, popEnter, popExit, Modifier.align(Alignment.CenterStart).padding(start = 64.dp)) { SkipLabel(it) }
            AnimatedOverlay(chip?.takeIf { it > 0 }, popEnter, popExit, Modifier.align(Alignment.CenterEnd).padding(end = 64.dp)) { SkipLabel(it) }
        }
        Visible(chrome, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPad), barEnter, barExit) { VideoControls(player, { skip(it) }, muted, onMuted) }
    }
}

/** Timeline with elapsed/total time, and the buttons underneath it. */
@Composable
private fun VideoControls(player: Player, onSkip: (Long) -> Unit, muted: Boolean, onMuted: (Boolean) -> Unit) {
    var playing by remember { mutableStateOf(player.isPlaying) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var drag by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0) // unknown duration is a huge negative
            delay(200)
        }
    }
    val shown = drag?.let { (it * dur).toLong() } ?: pos
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Slider(
            value = drag ?: if (dur > 0) pos.toFloat() / dur else 0f,
            onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { player.seekTo((it * dur).toLong()) }; drag = null },
            colors = SliderDefaults.colors(thumbColor = DarkCb.mint, activeTrackColor = DarkCb.mint, inactiveTrackColor = Color(0x3DFFFFFF)),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(shown), style = monoStyle(Color.White, 1.sp))
            Text(clock(dur), style = monoStyle(DarkCb.inkDim, 1.sp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(Icon.REWIND, Color.White, "Back 5 seconds") { onSkip(-5_000) }
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Color.White).clickable {
                    if (playing) player.pause() else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                },
                Alignment.Center,
            ) {
                Canvas(Modifier.size(22.dp)) {
                    val ink = Color(0xFF0A1020)
                    if (playing) {
                        drawRect(ink, Offset(size.width * .12f, 0f), Size(size.width * .3f, size.height))
                        drawRect(ink, Offset(size.width * .58f, 0f), Size(size.width * .3f, size.height))
                    } else {
                        drawPath(Path().apply { moveTo(size.width * .15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * .15f, size.height); close() }, ink)
                    }
                }
            }
                IconAction(Icon.FORWARD, Color.White, "Forward 5 seconds") { onSkip(5_000) }
            }
            Box(Modifier.weight(1f), Alignment.CenterEnd) {
                IconAction(if (muted) Icon.SPEAKER_OFF else Icon.SPEAKER, Color.White, if (muted) "Sound on" else "Sound off") { onMuted(!muted) }
            }
        }
    }
}

/** The small label over the video that says how far a skip went: "+5S", "-10S". */
@Composable
private fun SkipLabel(ms: Long) {
    Text(
        (if (ms < 0) "−" else "+") + "${abs(ms) / 1000}S",
        Modifier.background(Color(0xB3000000)).padding(horizontal = 12.dp, vertical = 8.dp),
        style = monoStyle(DarkCb.mint, 1.5.sp).copy(fontSize = 14.sp),
    )
}
