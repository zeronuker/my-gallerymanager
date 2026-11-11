package com.claudeborne.gallerymanager.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.video.VideoInfo
import com.claudeborne.gallerymanager.video.exportJoin
import com.claudeborne.gallerymanager.video.readVideoInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Heights = listOf("1080P" to 1080, "720P" to 720, "480P" to 480)

private fun fmt(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

/** Puts [items] (two or more videos) one after another into a single new video: set the order, size, fades and music, then save. */
@Composable
fun JoinScreen(items: List<MediaItem>, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val clips = remember { mutableStateListOf<MediaItem>().apply { addAll(items) } }
    val infos = remember { mutableStateMapOf<Long, VideoInfo>() }
    LaunchedEffect(items) { items.forEach { infos[it.id] = readVideoInfo(context, it.uri) } }

    var height by remember { mutableStateOf(1080) }
    var fadeIn by remember { mutableLongStateOf(0L) }
    var fadeOut by remember { mutableLongStateOf(0L) }
    var music by remember { mutableStateOf<Uri?>(null) }
    var musicName by remember { mutableStateOf("") }
    var musicVolume by remember { mutableFloatStateOf(0.7f) }
    var saving by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { music = u; musicName = musicFileName(context, u) } }

    BackHandler { if (!saving) onClose() }
    val ready = clips.all { infos[it.id] != null }
    val total = clips.sumOf { infos[it.id]?.durationMs ?: 0L }

    fun save() {
        saving = true
        progress = 0f
        val list = clips.toList()
        job = scope.launch {
            try {
                val ok = exportJoin(context, list, list.map { infos.getValue(it.id) }, height, fadeIn, fadeOut, music, musicVolume) { progress = it }
                Toast.makeText(context, if (ok) "Saved the joined video" else "Couldn't join the videos", Toast.LENGTH_SHORT).show()
                if (ok) onSaved()
            } finally {
                saving = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CloseButton { if (!saving) onClose() }
                Text("JOIN · ${clips.size} CLIPS · ${fmt(total)}", Modifier.weight(1f).padding(horizontal = 8.dp), style = monoStyle(DarkCb.ink, 1.5.sp), maxLines = 1)
                ActionText("SAVE", if (ready && clips.size >= 2) DarkCb.mint else DarkCb.inkDim) { if (ready && clips.size >= 2 && !saving) save() }
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(clips, key = { _, c -> c.id }) { n, clip ->
                    Row(Modifier.animateItem().fillMaxWidth().background(DarkCb.surface1), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(clip.uri, clip.name, Modifier.size(72.dp).background(DarkCb.surface2), contentScale = ContentScale.Crop)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text("${n + 1}", style = monoStyle(DarkCb.mint, 1.sp))
                            Text(clip.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = DarkCb.ink))
                            Text(infos[clip.id]?.let { fmt(it.durationMs) } ?: "…", style = monoStyle(DarkCb.inkDim, 1.sp))
                        }
                        Row {
                            ActionText("▲", if (n > 0) DarkCb.ink else DarkCb.inkDim) { if (n > 0) { val c = clips.removeAt(n); clips.add(n - 1, c) } }
                            ActionText("▼", if (n < clips.lastIndex) DarkCb.ink else DarkCb.inkDim) { if (n < clips.lastIndex) { val c = clips.removeAt(n); clips.add(n + 1, c) } }
                            ActionText("✕", Danger) { if (clips.size > 2) clips.removeAt(n) else Toast.makeText(context, "A join needs at least 2 clips", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth().background(Panel).navigationBarsPadding().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("SIZE", Modifier.padding(start = 16.dp), style = monoStyle(DarkCb.inkDim, 1.5.sp))
                LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) { items(Heights) { (label, h) -> Chip(label, height == h) { height = h } } }
                FadeChips(fadeIn, fadeOut) { a, b -> fadeIn = a; fadeOut = b }
                MusicControls(musicName, music != null, musicVolume, onPick = { pick.launch(arrayOf("audio/*")) }, onClear = { music = null; musicName = "" }, onVolume = { musicVolume = it })
                Text(
                    "CLIPS ARE FITTED INTO THE FIRST CLIP'S SHAPE, WITH BARS WHERE THE SHAPES DIFFER.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = monoStyle(DarkCb.inkDim, 0.5.sp),
                )
            }
        }
        BusyOverlay(saving, "JOINING… ${(progress * 100).roundToInt()}%", progress) {
            Text("KEEP THE APP OPEN. LONG VIDEOS TAKE A WHILE.", style = monoStyle(DarkCb.inkDim, 0.6.sp))
            ActionText("CANCEL", Danger) { job?.cancel() }
        }
    }
}
