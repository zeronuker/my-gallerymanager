package com.claudeborne.gallerymanager.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen shell shared by the built-in PDF, text and audio viewers. */
@Composable
private fun ViewerShell(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val cb = LocalCb.current
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().cbGrid(cb).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("← BACK", Modifier.clickable(onClick = onClose), style = mono(cb.mint, 11, 2.6f))
            Text(title, Modifier.weight(1f).padding(start = 16.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.ink2))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

// ---------------- PDF ----------------

@Composable
fun PdfScreen(file: File, onClose: () -> Unit) {
    val cb = LocalCb.current
    val pfd = remember(file) { runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull() }
    val renderer = remember(pfd) { pfd?.let { runCatching { PdfRenderer(it) }.getOrNull() } }
    val lock = remember { Mutex() } // PdfRenderer can only have one page open at a time
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() }; runCatching { pfd?.close() } } }
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }

    ViewerShell(file.name, onClose) {
        if (renderer == null) EmptyNote("CANNOT OPEN THIS PDF")
        else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(renderer.pageCount) { i ->
                val bmp by produceState<Bitmap?>(null, i) {
                    value = lock.withLock {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                renderer.openPage(i).use { page ->
                                    val h = (widthPx * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
                                    Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also {
                                        it.eraseColor(android.graphics.Color.WHITE)
                                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                                }
                            }.getOrNull()
                        }
                    }
                }
                val b = bmp
                if (b != null) Image(b.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                else Box(Modifier.fillMaxWidth().height(420.dp).background(cb.surface2), contentAlignment = Alignment.Center) { Text("PAGE ${i + 1}", style = mono(cb.inkDim)) }
            }
        }
    }
}

// ---------------- text ----------------

val textExtensions = setOf("txt", "md", "log", "json", "xml", "csv", "html", "htm", "ini", "conf", "yml", "yaml", "kt", "java", "js", "py", "css", "srt", "gradle", "properties")

@Composable
fun TextScreen(file: File, onClose: () -> Unit) {
    val cb = LocalCb.current
    val text by produceState<String?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                file.inputStream().use { s ->
                    val buf = ByteArray(1_000_000)
                    val n = s.read(buf)
                    String(buf, 0, maxOf(n, 0)) + if (file.length() > buf.size) "\n\n… (showing the first 1 MB)" else ""
                }
            }.getOrNull()
        }
    }
    ViewerShell(file.name, onClose) {
        val t = text
        if (t == null) EmptyNote("LOADING…")
        else {
            val lines = remember(t) { t.split('\n') }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                itemsIndexed(lines) { i, line ->
                    Row {
                        Text("${i + 1}", Modifier.padding(end = 10.dp), style = mono(cb.inkDim, 10, 0f))
                        Text(line, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = cb.ink))
                    }
                }
            }
        }
    }
}

// ---------------- audio ----------------

private fun clock(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

/** Plays [file] and moves on through [playlist] (the other audio files in the same folder). Stops when the app leaves the screen. */
@Composable
fun AudioScreen(file: File, playlist: List<File>, onClose: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    var index by remember { mutableIntStateOf(playlist.indexOf(file).coerceAtLeast(0)) }
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }

    LaunchedEffect(index) {
        player.setMediaItem(PlayerItem.fromUri(Uri.fromFile(playlist[index])))
        player.prepare(); player.play()
    }
    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying
            pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0)
            if (player.playbackState == Player.STATE_ENDED && index < playlist.lastIndex) index++
            delay(300)
        }
    }

    ViewerShell("Music player", onClose) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("♪", style = TextStyle(fontSize = 72.sp, brush = cb.grad))
            Text(playlist[index].nameWithoutExtension, style = TextStyle(fontFamily = Tourney, fontSize = 22.sp, color = cb.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
            Text("${index + 1} / ${playlist.size}", style = mono(cb.inkDim, 10, 2f))
            Slider(
                value = if (dur > 0) pos.toFloat() / dur else 0f,
                onValueChange = { player.seekTo((it * dur).toLong()); pos = (it * dur).toLong() },
                colors = SliderDefaults.colors(thumbColor = cb.mint, activeTrackColor = cb.mint, inactiveTrackColor = cb.surface3),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(pos), style = mono(cb.inkDim, 10, 1f))
                Text(clock(dur), style = mono(cb.inkDim, 10, 1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionLabel("⏮ PREV", cb.mint, enabled = index > 0) { index-- }
                Text(
                    if (playing) "PAUSE" else "PLAY",
                    Modifier.background(cb.grad).clickable { if (player.isPlaying) player.pause() else player.play() }.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = mono(DarkCb.surface0, 11, 2.2f, bold = true),
                )
                ActionLabel("NEXT ⏭", cb.mint, enabled = index < playlist.lastIndex) { index++ }
            }
        }
    }
}
