package com.claudeborne.gallerymanager.ui

import android.graphics.Bitmap
import android.text.format.Formatter
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.claudeborne.gallerymanager.edit.Crop
import com.claudeborne.gallerymanager.edit.Filters
import com.claudeborne.gallerymanager.edit.Label
import com.claudeborne.gallerymanager.edit.Pt
import com.claudeborne.gallerymanager.edit.Sticker
import com.claudeborne.gallerymanager.edit.drawMarks
import com.claudeborne.gallerymanager.video.previewMatrix
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
fun VideoEditor(item: MediaItem, onClose: () -> Unit, onSaved: () -> Unit, onLibraryChanged: () -> Unit) {
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
    var askReset by remember { mutableStateOf(false) }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) edit = edit.copy(music = uri, musicName = musicFileName(context, uri))
    }
    var lookWhich by remember { mutableIntStateOf(0) } // LOOK slider shown: 0 brightness, 1 contrast, 2 saturation, 3 warmth, 4 filter strength
    var mtool by remember { mutableStateOf(MarkTool.PEN) }
    var mcolor by remember { mutableIntStateOf(MarkPalette[0]) }
    val msizes = remember { mutableStateMapOf(MarkTool.PEN to 0.006f, MarkTool.MARKER to 0.03f, MarkTool.TEXT to 0.07f, MarkTool.STICKER to 0.14f) }
    var glyph by remember { mutableStateOf("😀") }
    var selected by remember { mutableIntStateOf(-1) }
    var textAt by remember { mutableStateOf<Pt?>(null) }
    var editText by remember { mutableStateOf(false) }

    // every settled change is one step, so dragging a slider is one UNDO; RESET is a step too, so it can be undone
    val history = remember { mutableStateListOf(VideoEdit()) }
    var at by remember { mutableIntStateOf(0) }
    fun flush() {
        if (edit != history[at]) {
            while (history.size > at + 1) history.removeAt(history.lastIndex)
            history.add(edit)
            at++
        }
    }
    LaunchedEffect(edit) { delay(350); flush() }
    fun undo() { flush(); if (at > 0) { at--; edit = history[at]; selected = -1 } }
    fun redo() { if (at < history.lastIndex && edit == history[at]) { at++; edit = history[at]; selected = -1 } }
    fun resetAll() { flush(); edit = VideoEdit(); selected = -1; flush() }

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
    val selMark = edit.marks.getOrNull(selected)?.takeIf { it.sizeOrNull() != null }
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
                        if (ok) onLibraryChanged()
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
                val look = edit.previewMatrix()
                if (shown != null) {
                    Box(
                        Modifier.align(Alignment.Center).size(with(density) { shown.first.toDp() }, with(density) { shown.second.toDp() })
                            .graphicsLayer { rotationZ = 90f * edit.turns; scaleX = if (edit.flip) -1f else 1f },
                    ) {
                        AndroidView(
                            { ctx -> (LayoutInflater.from(ctx).inflate(R.layout.player_texture, null) as PlayerView).also { it.player = player } },
                            Modifier.fillMaxSize(),
                            // the colour look is shown by giving the video's own surface a colour filter
                            update = { pv ->
                                (pv.videoSurfaceView as? android.view.TextureView)?.setLayerPaint(
                                    look?.let { android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(it) } },
                                )
                            },
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
                if (shown != null) {
                    // the finished picture's frame: text, stickers and pen strokes are placed on it
                    val cw = if (turned) shown.second else shown.first
                    val ch = if (turned) shown.first else shown.second
                    val r = edit.aspect.ratio
                    val fw = if (r > 0f) (if (cw / ch > r) ch * r else cw) else cw
                    val fh = if (r > 0f) fw / r else ch
                    Box(Modifier.align(Alignment.Center).size(with(density) { fw.toDp() }, with(density) { fh.toDp() })) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawIntoCanvas { drawMarks(it.nativeCanvas, edit.marks, Crop(), size.width.roundToInt(), size.height.roundToInt()) }
                        }
                        if (tool == 6) {
                            MarkupOverlay(
                                mtool, Crop(), edit.marks, mcolor, msizes[mtool] ?: 0.05f, glyph, selected,
                                onLive = { edit = edit.copy(marks = it) }, onDone = {}, onSelect = { selected = it },
                                onText = { textAt = it },
                                onSticker = { p ->
                                    val index = edit.marks.size
                                    edit = edit.copy(marks = edit.marks + Sticker(glyph, p.u, p.v, msizes.getValue(MarkTool.STICKER)))
                                    selected = index
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                if (!playing) Text("▶", Modifier.align(Alignment.Center), style = TextStyle(fontSize = 48.sp, color = Color(0xCCFFFFFF)))
            }

            val i = info
            if (i == null) {
                Box(Modifier.fillMaxWidth().height(260.dp).background(Panel), Alignment.Center) { LoadingPane("LOADING VIDEO") }
            } else {
                Column(Modifier.fillMaxWidth().background(Panel).navigationBarsPadding()) {
                    val canUndo = at > 0 || edit != history[at]
                    val canRedo = at < history.lastIndex && edit == history[at]
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), Arrangement.End) {
                        ActionText("UNDO", if (canUndo) DarkCb.ink else DarkCb.inkDim) { undo() }
                        ActionText("REDO", if (canRedo) DarkCb.ink else DarkCb.inkDim) { redo() }
                        ActionText("RESET", if (changed) DarkCb.ink else DarkCb.inkDim) { if (changed) askReset = true }
                    }
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
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState())) {
                        listOf("TRIM", "SPEED", "AUDIO", "ROTATE", "CROP", "LOOK", "MARKUP", "FADE", "FREEZE", "COMPRESS").forEachIndexed { n, label ->
                            Column(Modifier.clickable { tool = n; selected = -1 }.padding(horizontal = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.width(48.dp).height(2.dp).background(glide(tool == n, DarkCb.mint, Color.Transparent)))
                                Text(label, Modifier.padding(vertical = 10.dp), style = monoStyle(glide(tool == n, DarkCb.mint, DarkCb.inkDim), 0.8.sp).copy(fontSize = 10.sp))
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(when (tool) { 2 -> 200.dp; 5 -> 200.dp; 6 -> if (selMark != null) 240.dp else 200.dp; 7 -> 190.dp; else -> 130.dp })) {
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
                                2 -> {
                                    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
                                        if (i.hasAudio) {
                                            item { Chip(if (edit.volume <= 0f) "MUTED ✓" else "MUTE", edit.volume <= 0f) { edit = edit.copy(volume = if (edit.volume <= 0f) 1f else 0f) } }
                                            item { Chip("RESET", color = DarkCb.inkDim) { edit = edit.copy(volume = 1f) } }
                                        }
                                    }
                                    if (i.hasAudio) EditSlider("VOLUME", edit.volume, 0f..2f, "${(edit.volume * 100).roundToInt()}%", { edit = edit.copy(volume = it) }, {}) { edit = edit.copy(volume = 1f) }
                                    else Hint("THIS VIDEO HAS NO SOUND OF ITS OWN.")
                                    MusicControls(
                                        edit.musicName, edit.music != null, edit.musicVolume,
                                        onPick = { pickMusic.launch(arrayOf("audio/*")) },
                                        onClear = { edit = edit.copy(music = null, musicName = "") },
                                        onVolume = { edit = edit.copy(musicVolume = it) },
                                    )
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
                                5 -> {
                                    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
                                        listOf("BRIGHTNESS", "CONTRAST", "SATURATION", "WARMTH").forEachIndexed { n, label -> item { Chip(label, lookWhich == n) { lookWhich = n } } }
                                        if (edit.filter != 0) item { Chip("STRENGTH", lookWhich == 4) { lookWhich = 4 } }
                                        item {
                                            Chip("RESET", color = DarkCb.inkDim) {
                                                edit = edit.copy(brightness = 0f, contrast = 0f, saturation = 0f, warmth = 0f, filter = 0, filterAmount = 1f)
                                                lookWhich = 0
                                            }
                                        }
                                    }
                                    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
                                        itemsIndexed(Filters) { n, f -> Chip(f.name, edit.filter == n) { edit = edit.copy(filter = n); if (n == 0 && lookWhich == 4) lookWhich = 0 } }
                                    }
                                    val strength = lookWhich == 4 && edit.filter != 0
                                    val which = if (strength) 4 else lookWhich.coerceAtMost(3)
                                    val value = when (which) { 0 -> edit.brightness; 1 -> edit.contrast; 2 -> edit.saturation; 3 -> edit.warmth; else -> edit.filterAmount }
                                    val reset = if (which == 4) 1f else 0f
                                    val set = { v: Float ->
                                        edit = when (which) {
                                            0 -> edit.copy(brightness = v); 1 -> edit.copy(contrast = v); 2 -> edit.copy(saturation = v); 3 -> edit.copy(warmth = v)
                                            else -> edit.copy(filterAmount = v)
                                        }
                                    }
                                    EditSlider(
                                        listOf("BRIGHTNESS", "CONTRAST", "SATURATION", "WARMTH", "STRENGTH")[which], value, if (which == 4) 0f..1f else -1f..1f,
                                        if (which == 4) "${(value * 100).roundToInt()}" else "%+d".format((value * 100).roundToInt()), set, {},
                                    ) { set(reset) }
                                }
                                6 -> MarkupPanel(
                                    mtool, onTool = { mtool = it; selected = -1 }, mcolor,
                                    onColor = { c ->
                                        mcolor = c
                                        (selMark as? Label)?.let { l -> edit = edit.copy(marks = edit.marks.mapIndexed { n, m -> if (n == selected) l.copy(color = c) else m }) }
                                    },
                                    glyph, onGlyph = { glyph = it },
                                    size = selMark?.sizeOrNull() ?: msizes[mtool] ?: 0.05f,
                                    range = selMark?.let { sizeRange(if (it is Label) MarkTool.TEXT else MarkTool.STICKER) } ?: sizeRange(mtool),
                                    onSize = { v ->
                                        if (selMark != null) edit = edit.copy(marks = edit.marks.mapIndexed { n, m -> if (n == selected) m.withSize(v) else m }) else msizes[mtool] = v
                                    },
                                    onSizeDone = {},
                                    canDelete = selMark != null,
                                    onDelete = { edit = edit.copy(marks = edit.marks.filterIndexed { n, _ -> n != selected }); selected = -1 },
                                    rot = selMark?.rotOrNull(),
                                    onRot = { v -> edit = edit.copy(marks = edit.marks.mapIndexed { n, m -> if (n == selected) m.withRot(v) else m }) },
                                    onEditText = if (selMark is Label) ({ editText = true }) else null,
                                    tools = listOf(MarkTool.PEN, MarkTool.MARKER, MarkTool.ERASER, MarkTool.TEXT, MarkTool.STICKER),
                                )
                                7 -> {
                                    FadeChips(edit.fadeInMs, edit.fadeOutMs) { a, b -> edit = edit.copy(fadeInMs = a, fadeOutMs = b) }
                                    Hint("PICTURE AND SOUND FADE FROM AND TO BLACK. SHOWN IN THE SAVED VIDEO, NOT IN THIS PREVIEW.")
                                }
                                8 -> {
                                    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
                                        item {
                                            Chip("FREEZE HERE", color = DarkCb.ink) {
                                                edit = edit.copy(freezeAtMs = player.currentPosition.coerceIn(edit.startMs, endMs))
                                            }
                                        }
                                        if (edit.freezeAtMs >= 0) item { Chip("REMOVE", color = Danger) { edit = edit.copy(freezeAtMs = -1) } }
                                        items(listOf(1000L, 2000L, 3000L, 5000L)) { ms -> Chip("${ms / 1000}S", edit.freezeMs == ms) { edit = edit.copy(freezeMs = ms) } }
                                    }
                                    Hint(
                                        if (edit.freezeAtMs >= 0) "THE PICTURE AT ${fmt(edit.freezeAtMs)} IS HELD STILL FOR ${edit.freezeMs / 1000} SECONDS. THE SOUND PAUSES."
                                        else "PLAY TO A MOMENT, THEN TAP FREEZE HERE TO HOLD THAT PICTURE STILL.",
                                    )
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

        textAt?.let { p ->
            AddTextDialog(
                initial = "",
                onAdd = { words ->
                    val index = edit.marks.size
                    edit = edit.copy(marks = edit.marks + Label(words, p.u, p.v, msizes.getValue(MarkTool.TEXT), mcolor))
                    selected = index
                    textAt = null
                },
                onCancel = { textAt = null },
            )
        }
        if (editText && selMark is Label) {
            AddTextDialog(
                initial = selMark.text,
                onAdd = { words -> edit = edit.copy(marks = edit.marks.mapIndexed { n, m -> if (n == selected && m is Label) m.copy(text = words) else m }); editText = false },
                onCancel = { editText = false },
            )
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
        if (askReset) {
            CbDialog("RESET ALL EDITS?", { askReset = false }) {
                Text("The video goes back to how it was. You can still press UNDO to get your edits back.", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = DarkCb.ink2))
                Row(Modifier.fillMaxWidth(), Arrangement.End) {
                    ActionText("CANCEL", DarkCb.inkDim) { askReset = false }
                    ActionText("RESET", Danger) { askReset = false; resetAll() }
                }
            }
        }
        BusyOverlay(saving, "SAVING… ${(progress * 100).roundToInt()}%", progress) {
            Text("KEEP THE APP OPEN. LONG VIDEOS TAKE A WHILE.", style = monoStyle(DarkCb.inkDim, 0.6.sp))
            ActionText("CANCEL", Danger) { job?.cancel() }
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
                    if (b != null) Image(b.asImageBitmap(), null, Modifier.appear().fillMaxSize(), contentScale = ContentScale.Crop) // each frame fades in as it is read
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
