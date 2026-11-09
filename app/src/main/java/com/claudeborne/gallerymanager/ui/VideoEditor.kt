package com.claudeborne.gallerymanager.ui

import android.graphics.Bitmap
import android.text.format.Formatter
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.claudeborne.gallerymanager.R
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.data.Settings
import com.claudeborne.gallerymanager.edit.insertImage
import com.claudeborne.gallerymanager.video.VideoAspect
import com.claudeborne.gallerymanager.video.VideoEdit
import com.claudeborne.gallerymanager.video.VideoInfo
import com.claudeborne.gallerymanager.video.VideoQuality
import com.claudeborne.gallerymanager.video.VideoSize
import com.claudeborne.gallerymanager.video.estimatedBytes
import com.claudeborne.gallerymanager.video.exportVideo
import com.claudeborne.gallerymanager.video.outputMs
import com.claudeborne.gallerymanager.video.readVideoInfo
import com.claudeborne.gallerymanager.video.videoFrame
import com.claudeborne.gallerymanager.video.videoThumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val Speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)
private const val MIN_CLIP_MS = 500L
private const val THUMBS = 10

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Full-screen video editor: trim on a timeline, change speed and sound, turn, crop and shrink, then save a new copy. */
@OptIn(UnstableApi::class)
@Composable
fun VideoEditor(item: MediaItem, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var thumbs by remember { mutableStateOf<List<Bitmap?>>(emptyList()) }
    LaunchedEffect(item.id) {
        val i = readVideoInfo(context, item.uri)
        info = i
        thumbs = videoThumbs(context, item.uri, i.durationMs, THUMBS)
    }

    var edit by remember { mutableStateOf(VideoEdit()) }
    var tool by remember { mutableIntStateOf(0) }
    var pos by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    var askDiscard by remember { mutableStateOf(false) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(PlayerItem.fromUri(item.uri))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }

    val dur = info?.durationMs ?: 0L
    val endMs = if (edit.endMs > 0) edit.endMs else dur
    val startNow by rememberUpdatedState(edit.startMs)
    val endNow by rememberUpdatedState(endMs)

    // turn, flip and crop are previewed with plain screen transforms (below); speed and volume play through the player
    LaunchedEffect(edit.speed) { player.setPlaybackSpeed(edit.speed) }
    LaunchedEffect(edit.volume) { player.volume = min(edit.volume, 1f) }
    // follow the playhead and loop inside the part that is kept
    LaunchedEffect(Unit) {
        while (true) {
            pos = player.currentPosition
            if (player.isPlaying && endNow > 0 && pos >= endNow) player.seekTo(startNow)
            delay(60)
        }
    }

    val changed = edit != VideoEdit()
    fun close() { if (saving) return; if (changed) askDiscard = true else onClose() }
    BackHandler { close() }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else {
            if (player.currentPosition < startNow || player.currentPosition >= endNow) player.seekTo(startNow)
            player.play()
        }
    }

    fun save() {
        val i = info ?: return
        saving = true
        progress = 0f
        player.pause()
        job = scope.launch {
            try {
                val ok = exportVideo(context, item, edit, i) { progress = it }
                Toast.makeText(context, if (ok) "Saved a copy" else "Couldn't save the video" + if (i.hasAudio && edit.volume > 0f) ". If its sound is unusual, mute it in AUDIO and try again." else "", Toast.LENGTH_LONG).show()
                if (ok) onSaved()
            } finally {
                saving = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionText("✕", DarkCb.inkDim) { close() }
                Box(Modifier.weight(1f))
                ActionText("SAVE FRAME", DarkCb.ink) {
                    val t = player.currentPosition
                    scope.launch {
                        val ok = saveFrame(context, item, t)
                        Toast.makeText(context, if (ok) "Frame saved as a photo" else "Couldn't save the frame", Toast.LENGTH_SHORT).show()
                        if (ok) onSaved()
                    }
                }
                ActionText("SAVE", if (changed) DarkCb.mint else DarkCb.inkDim) { if (changed) save() }
            }

            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { togglePlay() },
            ) {
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val vi = info
                val turned = edit.turns % 2 == 1
                // size of the picture as recorded, fitted to the box; when turned a quarter, it is made smaller so the turned picture fits too
                val shown = vi?.let { v ->
                    val aspect = v.width.toFloat() / v.height
                    val w0 = if (boxW / boxH > aspect) boxH * aspect else boxW
                    val h0 = w0 / aspect
                    val s = if (turned) minOf(boxW / h0, boxH / w0) else 1f
                    Pair(w0 * s, h0 * s)
                }
                val density = androidx.compose.ui.platform.LocalDensity.current
                if (shown != null) {
                    Box(
                        Modifier.align(Alignment.Center).size(with(density) { shown.first.toDp() }, with(density) { shown.second.toDp() })
                            .graphicsLayer { rotationZ = 90f * edit.turns; scaleX = if (edit.flip) -1f else 1f },
                    ) {
                        AndroidView(
                            { ctx -> (LayoutInflater.from(ctx).inflate(R.layout.player_texture, null) as PlayerView).also { it.player = player } },
                            Modifier.fillMaxSize(),
                        )
                    }
                    // dim what a crop will cut away
                    if (edit.aspect.ratio > 0f) {
                        val cw = if (turned) shown.second else shown.first
                        val ch = if (turned) shown.first else shown.second
                        val r = edit.aspect.ratio
                        val kw = if (cw / ch > r) ch * r else cw
                        val kh = kw / r
                        Canvas(Modifier.fillMaxSize()) {
                            val l = (size.width - kw) / 2; val t = (size.height - kh) / 2
                            val dim = Color(0xB3000000)
                            val cl = (size.width - cw) / 2; val ct = (size.height - ch) / 2
                            drawRect(dim, Offset(cl, ct), Size(cw, t - ct))
                            drawRect(dim, Offset(cl, t + kh), Size(cw, ct + ch - (t + kh)))
                            drawRect(dim, Offset(cl, t), Size(l - cl, kh))
                            drawRect(dim, Offset(l + kw, t), Size(cl + cw - (l + kw), kh))
                            drawRect(DarkCb.mint, Offset(l, t), Size(kw, kh), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                        }
                    }
                }
                if (!playing) Text("▶", Modifier.align(Alignment.Center), style = TextStyle(fontSize = 48.sp, color = Color(0xCCFFFFFF)))
            }

            val i = info
            if (i == null) {
                Box(Modifier.fillMaxWidth().height(260.dp).background(Panel), Alignment.Center) { Text("LOADING", style = monoStyle(DarkCb.inkDim, 2.6.sp)) }
            } else {
                Column(Modifier.fillMaxWidth().background(Panel).navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmt(pos), style = monoStyle(Color.White, 1.sp))
                        Text("${fmt(edit.startMs)} – ${fmt(endMs)}  →  ${fmt(edit.outputMs(i))}", style = monoStyle(DarkCb.mint, 1.sp))
                    }
                    TrimStrip(
                        thumbs, dur, edit.startMs, endMs, pos,
                        onStart = { t -> edit = edit.copy(startMs = t); player.seekTo(t) },
                        onEnd = { t -> edit = edit.copy(endMs = if (t >= dur) 0 else t); player.seekTo(t) },
                        onSeek = { t -> player.seekTo(t) },
                        Modifier.fillMaxWidth().padding(horizontal = 32.dp).height(56.dp), // margin keeps the end handles clear of Android's Back-swipe zone
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        listOf("TRIM", "SPEED", "AUDIO", "ROTATE", "CROP", "COMPRESS").forEachIndexed { n, label ->
                            Column(Modifier.weight(1f).clickable { tool = n }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.fillMaxWidth().height(2.dp).background(if (tool == n) DarkCb.mint else Color.Transparent))
                                Text(label, Modifier.padding(vertical = 10.dp), style = monoStyle(if (tool == n) DarkCb.mint else DarkCb.inkDim, 0.8.sp).copy(fontSize = 10.sp))
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(130.dp)) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                            when (tool) {
                                0 -> {
                                    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
                                        item { Chip("START HERE", color = DarkCb.ink) { edit = edit.copy(startMs = min(player.currentPosition, endMs - MIN_CLIP_MS).coerceAtLeast(0)) } }
                                        item { Chip("END HERE", color = DarkCb.ink) { val t = max(player.currentPosition, edit.startMs + MIN_CLIP_MS); edit = edit.copy(endMs = if (t >= dur) 0 else t) } }
                                        item { Chip("RESET TRIM", color = DarkCb.inkDim) { edit = edit.copy(startMs = 0, endMs = 0) } }
                                        item { Chip(if (edit.exactCut) "EXACT CUT ✓" else "EXACT CUT", edit.exactCut) { edit = edit.copy(exactCut = !edit.exactCut) } }
                                    }
                                    Hint(if (edit.exactCut) "EXACT: STARTS AT THE FRAME YOU PICKED. TAKES LONGER." else "FAST: INSTANT AND LOSSLESS, BUT THE START MAY MOVE BACK A FEW SECONDS TO A KEY FRAME.")
                                }
                                1 -> {
                                    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
                                        items(Speeds) { s -> Chip(if (s == 1f) "1×" else "${s}×".replace(".0×", "×"), edit.speed == s) { edit = edit.copy(speed = s) } }
                                    }
                                    Hint("SLOW MOTION BELOW 1×, FAST FORWARD ABOVE. SOUND CHANGES SPEED TOO.")
                                }
                                2 -> if (!i.hasAudio) Hint("THIS VIDEO HAS NO SOUND.") else {
                                    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
                                        item { Chip(if (edit.volume <= 0f) "MUTED ✓" else "MUTE", edit.volume <= 0f) { edit = edit.copy(volume = if (edit.volume <= 0f) 1f else 0f) } }
                                        item { Chip("RESET", color = DarkCb.inkDim) { edit = edit.copy(volume = 1f) } }
                                    }
                                    EditSlider("VOLUME", edit.volume, 0f..2f, "${(edit.volume * 100).roundToInt()}%", { edit = edit.copy(volume = it) }, {}) { edit = edit.copy(volume = 1f) }
                                }
                                3 -> {
                                    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
                                        item { Chip("ROTATE", color = DarkCb.ink) { edit = edit.copy(turns = (edit.turns + 1) % 4) } }
                                        item { Chip("FLIP", edit.flip, color = DarkCb.ink) { edit = edit.copy(flip = !edit.flip) } }
                                        item { Chip("RESET", color = DarkCb.inkDim) { edit = edit.copy(turns = 0, flip = false) } }
                                    }
                                    Hint("ROTATE TURNS THE VIDEO A QUARTER TURN EACH TAP. FLIP MIRRORS IT.")
                                }
                                4 -> {
                                    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
                                        items(VideoAspect.entries) { a -> Chip(a.label, edit.aspect == a) { edit = edit.copy(aspect = a) } }
                                    }
                                    Hint("CUTS THE VIDEO DOWN TO THIS SHAPE, FROM THE MIDDLE.")
                                }
                                else -> {
                                    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
                                        items(VideoSize.entries) { s -> Chip(s.label, edit.size == s) { edit = edit.copy(size = s) } }
                                    }
                                    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
                                        items(VideoQuality.entries) { q -> Chip(q.label, edit.quality == q) { edit = edit.copy(quality = q) } }
                                    }
                                    val est = edit.estimatedBytes(i)
                                    Hint(if (est > 0) "ABOUT ${Formatter.formatShortFileSize(context, est)} (NOW ${Formatter.formatShortFileSize(context, item.size)})" else "NO CHANGE TO THE FILE SIZE YET.")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (askDiscard) {
            CbDialog("DISCARD EDITS?", { askDiscard = false }) {
                Text("Your changes will be lost.", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = DarkCb.ink2))
                Row(Modifier.fillMaxWidth(), Arrangement.End) {
                    ActionText("KEEP EDITING", DarkCb.inkDim) { askDiscard = false }
                    ActionText("DISCARD", Danger) { onClose() }
                }
            }
        }
        if (saving) {
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)).pointerInput(Unit) { detectTapGestures { } }, Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("SAVING… ${(progress * 100).roundToInt()}%", style = monoStyle(DarkCb.mint, 2.6.sp))
                    Text("KEEP THE APP OPEN. LONG VIDEOS TAKE A WHILE.", style = monoStyle(DarkCb.inkDim, 0.6.sp))
                    ActionText("CANCEL", Danger) { job?.cancel() }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp), style = monoStyle(DarkCb.inkDim, 0.6.sp))
}

/** The video as a strip of pictures with a mint handle at each end of the part that is kept, and a white playhead. */
@Composable
private fun TrimStrip(
    thumbs: List<Bitmap?>, dur: Long, startMs: Long, endMs: Long, posMs: Long,
    onStart: (Long) -> Unit, onEnd: (Long) -> Unit, onSeek: (Long) -> Unit, modifier: Modifier,
) {
    val startNow by rememberUpdatedState(startMs)
    val endNow by rememberUpdatedState(endMs)
    val durNow by rememberUpdatedState(dur)
    val onStartNow by rememberUpdatedState(onStart)
    val onEndNow by rememberUpdatedState(onEnd)
    val onSeekNow by rememberUpdatedState(onSeek)

    Box(modifier.background(DarkCb.surface2)) {
        Row(Modifier.fillMaxSize()) {
            thumbs.forEach { b ->
                Box(Modifier.weight(1f).fillMaxSize()) {
                    if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                val slop = 30.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val d = durNow
                    if (d <= 0 || w <= 0f) return@awaitEachGesture
                    val xs = startNow.toFloat() / d * w
                    val xe = endNow.toFloat() / d * w
                    val dxs = abs(down.position.x - xs)
                    val dxe = abs(down.position.x - xe)
                    val mode = when { dxs < slop && dxs <= dxe -> 0; dxe < slop -> 1; else -> 2 } // start handle, end handle, or just move the playhead
                    do {
                        val c = awaitPointerEvent().changes.first()
                        val t = (c.position.x / w * d).toLong().coerceIn(0, d)
                        when (mode) {
                            0 -> onStartNow(min(t, endNow - MIN_CLIP_MS).coerceAtLeast(0))
                            1 -> onEndNow(max(t, startNow + MIN_CLIP_MS))
                            else -> onSeekNow(t)
                        }
                        c.consume()
                    } while (c.pressed)
                }
            },
        ) {
            if (dur <= 0) return@Canvas
            val xs = startMs.toFloat() / dur * size.width
            val xe = endMs.toFloat() / dur * size.width
            val dim = Color(0xAA000000)
            drawRect(dim, Offset.Zero, Size(xs, size.height))
            drawRect(dim, Offset(xe, 0f), Size(size.width - xe, size.height))
            val bar = 4.dp.toPx()
            drawRect(DarkCb.mint, Offset(xs, 0f), Size(xe - xs, bar))
            drawRect(DarkCb.mint, Offset(xs, size.height - bar), Size(xe - xs, bar))
            val hw = 10.dp.toPx()
            drawRect(DarkCb.mint, Offset(xs - hw / 2, 0f), Size(hw, size.height))
            drawRect(DarkCb.mint, Offset(xe - hw / 2, 0f), Size(hw, size.height))
            val px = posMs.toFloat() / dur * size.width
            drawRect(Color.White, Offset(px - 1.5.dp.toPx(), 0f), Size(3.dp.toPx(), size.height))
        }
    }
}

/** Saves the picture at [timeMs] as a JPG photo next to the video (or in Pictures/Video frames when the video lives in Movies). */
private suspend fun saveFrame(context: android.content.Context, item: MediaItem, timeMs: Long): Boolean = withContext(Dispatchers.IO) {
    val bmp = videoFrame(context, item.uri, timeMs) ?: return@withContext false
    val file = File.createTempFile("frame", ".jpg", context.cacheDir)
    try {
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, Settings.editQuality, it) }
        val folder = if (item.path.startsWith("DCIM/") || item.path.startsWith("Pictures/")) item.path else "Pictures/Video frames/"
        insertImage(context, file, item.name.substringBeforeLast('.') + "_" + fmt(timeMs).replace(':', '-') + ".jpg", "image/jpeg", folder)
    } finally {
        file.delete()
    }
}
