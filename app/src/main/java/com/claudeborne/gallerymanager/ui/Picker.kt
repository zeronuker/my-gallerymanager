package com.claudeborne.gallerymanager.ui

import android.os.Environment
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.data.Entry
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.data.SortBy
import com.claudeborne.gallerymanager.data.createFolder
import com.claudeborne.gallerymanager.data.listDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Where the picker is looking: the start list of shortcuts, the list of storage volumes, or one folder. */
private sealed interface Place {
    data object Top : Place
    data object Roots : Place
    data class Dir(val file: File) : Place
}

/** What a screen asks the picker for; the main scaffold shows it full-screen over everything and closes it when it ends. */
internal class PickerRequest(
    val title: String,
    val pickFiles: Boolean,
    val confirmLabel: String,
    val roots: List<Pair<String, File>>,
    val shortcuts: List<Pair<String, File>> = emptyList(),
    val limit: File? = null,
    val hint: (File) -> String? = { null },
    val onFolder: (File) -> Unit = {},
    val onFiles: (List<File>) -> Unit = {},
)

/** The folders that already hold photos or videos, as (name, folder), for the picker's first view. */
internal fun photoFolders(library: List<MediaItem>): List<Pair<String, File>> {
    val base = Environment.getExternalStorageDirectory()
    return library.filter { it.path.isNotEmpty() }.distinctBy { it.path }
        .map { it.bucketName.ifEmpty { it.path } to File(base, it.path) }.sortedBy { it.first.lowercase() }
}

/**
 * The one file and folder picker, drawn as a framed window over the app (like the web apps' move picker), one folder level at a time.
 * [pickFiles] false: choose a destination folder, and [confirmLabel] ("COPY HERE") acts on the folder being looked at.
 * [pickFiles] true: tick files and folders, and [confirmLabel] ("SEND") acts on the ticked ones.
 * It starts on [shortcuts] (with "ALL FOLDERS" after them) when given, else on the storage volumes [roots], else inside [limit], which it cannot leave.
 * [hint] says why the folder being looked at cannot be chosen ("Already here"), or null when it can.
 */
@Composable
internal fun Picker(
    title: String,
    pickFiles: Boolean,
    confirmLabel: String,
    roots: List<Pair<String, File>>,
    shortcuts: List<Pair<String, File>> = emptyList(),
    limit: File? = null,
    hint: (File) -> String? = { null },
    onCancel: () -> Unit,
    onFolder: (File) -> Unit = {},
    onFiles: (List<File>) -> Unit = {},
) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val base = Environment.getExternalStorageDirectory()
    var stack by remember { mutableStateOf(listOf<Place>(if (limit != null) Place.Dir(limit) else if (shortcuts.isNotEmpty()) Place.Top else Place.Roots)) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var naming by remember { mutableStateOf(false) }
    val here = (stack.last() as? Place.Dir)?.file

    fun back() { if (stack.size > 1) stack = stack.dropLast(1) else onCancel() }
    fun open(f: File) { stack = stack + Place.Dir(f) }
    BackHandler { back() }

    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(remember { MutableInteractionSource() }, null) {}) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp).doubleChamferFrame(cb, cb.surface1).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable { back() }, Alignment.Center) { CbIcon(Icon.CHEVRON, cb.mint, Modifier.graphicsLayer { scaleX = -1f }, size = 22.dp) }
                Text(title, Modifier.padding(start = 4.dp), style = monoStyle(cb.mint, 2.6.sp))
            }

            AnimatedContent(
                stack, Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val deeper = targetState.size - initialState.size
                    when {
                        deeper == 0 -> fadeIn(tween(MOTION_FAST_MS)) togetherWith fadeOut(tween(MOTION_FAST_MS))
                        deeper > 0 -> (slideInHorizontally(tween(MOTION_MS)) { it / 4 } + fadeIn(tween(MOTION_MS))) togetherWith (slideOutHorizontally(tween(MOTION_MS)) { -it / 4 } + fadeOut(tween(MOTION_FAST_MS)))
                        else -> (slideInHorizontally(tween(MOTION_MS)) { -it / 4 } + fadeIn(tween(MOTION_MS))) togetherWith (slideOutHorizontally(tween(MOTION_MS)) { it / 4 } + fadeOut(tween(MOTION_FAST_MS)))
                    }
                },
                label = "pickerLevel",
            ) { st ->
                val place = st.last()
                Column(Modifier.fillMaxSize()) {
                    if (place is Place.Dir) Crumbs(place.file, limit, roots) { target -> stack = if (Place.Dir(target) in stack) stack.dropLastWhile { it != Place.Dir(target) } else stack.dropLast(1) + Place.Dir(target) }
                    when (place) {
                        Place.Top -> LazyColumn(Modifier.fillMaxSize()) {
                            item { Text("PHOTO FOLDERS", Modifier.padding(top = 10.dp, bottom = 2.dp), style = monoStyle(cb.mint, 2.4.sp)) }
                            items(shortcuts, key = { it.second.path }) { (name, dir) -> PickRow(name, dir.relativeTo(base).path, onClick = { open(dir) }) }
                            item { PickRow("ALL FOLDERS", null, onClick = { stack = stack + Place.Roots }) }
                        }
                        Place.Roots -> LazyColumn(Modifier.fillMaxSize()) {
                            items(roots, key = { it.second.path }) { (name, dir) -> PickRow(name, null, onClick = { open(dir) }) }
                        }
                        is Place.Dir -> {
                            val entries by produceState<List<Entry>?>(null, place.file, naming) {
                                value = withContext(Dispatchers.IO) { listDir(place.file, false, SortBy.NAME, true).filter { pickFiles || it.isDir } }
                            }
                            val list = entries
                            if (list == null) LoadingPane("LOADING")
                            else LazyColumn(Modifier.fillMaxSize()) {
                                if (!pickFiles) item(key = "new") { PickRow("+ NEW FOLDER", null, accent = true, chevron = false, onClick = { naming = true }) }
                                items(list, key = { it.file.path }) { e ->
                                    val on = e.file.path in selected
                                    val tick = { selected = if (on) selected - e.file.path else selected + e.file.path }
                                    if (e.isDir) PickRow(e.file.name, null, ticked = if (pickFiles) on else null, onTick = tick, onClick = { open(e.file) })
                                    else FileRow(e, on, tick)
                                }
                                if (list.isEmpty()) item { Text(if (pickFiles) "EMPTY FOLDER" else "NO FOLDERS HERE", Modifier.padding(vertical = 16.dp), style = monoStyle(cb.inkDim, 2.sp)) }
                            }
                        }
                    }
                }
            }

            val problem = when {
                pickFiles -> if (selected.isEmpty()) "Tick files or folders" else null
                here == null -> "Open a folder first"
                else -> hint(here)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    problem ?: if (pickFiles) "${selected.size} selected" else "", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim),
                )
                BrandButton("CANCEL", onClick = onCancel)
                BrandButton(
                    if (pickFiles && selected.isNotEmpty()) "$confirmLabel ${selected.size}" else confirmLabel, primary = true, enabled = problem == null,
                ) { if (pickFiles) onFiles(selected.map(::File)) else here?.let(onFolder) }
            }
        }
    }

    if (naming && here != null) {
        var name by remember { mutableStateOf("") }
        CbDialog("NEW FOLDER", { naming = false }) {
            CbTextField(name, { name = it })
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                ActionText("CANCEL", DarkCb.inkDim) { naming = false }
                ActionText("CREATE", DarkCb.mint) {
                    val clean = name.trim()
                    if (clean.isEmpty() || '/' in clean) return@ActionText
                    if (createFolder(here, clean)) { naming = false; open(File(here, clean)) } else Toast.makeText(context, "Could not create the folder", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

/** Path from the start (the volume, or [limit]) down to [dir]; each part can be tapped to go back to it. */
@Composable
private fun Crumbs(dir: File, limit: File?, roots: List<Pair<String, File>>, onGo: (File) -> Unit) {
    val cb = LocalCb.current
    val root = limit ?: roots.filter { dir.path.startsWith(it.second.path) }.maxByOrNull { it.second.path.length }?.second ?: return
    val label = if (limit != null) limit.name else roots.first { it.second.path == root.path }.first
    val parts = generateSequence(dir) { if (it.path == root.path) null else it.parentFile }.toList().reversed()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(Int.MAX_VALUE)).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, f ->
            if (i > 0) Text(" › ", style = monoStyle(cb.inkDim, 0.sp))
            Text(
                (if (f.path == root.path) label else f.name).uppercase(), Modifier.clickable(enabled = f.path != dir.path) { onGo(f) }.padding(vertical = 4.dp),
                style = monoStyle(glide(f.path == dir.path, cb.mint, cb.inkDim), 1.sp),
            )
        }
    }
}

/** The round tick: empty, or filled mint with a check. */
@Composable
private fun TickBox(on: Boolean, modifier: Modifier = Modifier) {
    val cb = LocalCb.current
    Box(
        modifier.size(22.dp).clip(CircleShape).background(glide(on, cb.mint, Color.Transparent)).border(1.dp, glide(on, cb.mint, cb.lineStrong), CircleShape),
        Alignment.Center,
    ) { if (on) CbIcon(Icon.CHECK, cb.surface0, size = 14.dp) }
}

/** A folder (or shortcut) row: folder icon, name, and a chevron to open it. [ticked] non-null adds a tick before it, so a folder can also be chosen. */
@Composable
private fun PickRow(name: String, sub: String?, ticked: Boolean? = null, accent: Boolean = false, chevron: Boolean = true, onTick: () -> Unit = {}, onClick: () -> Unit) {
    val cb = LocalCb.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ticked != null) TickBox(ticked, Modifier.clickable(onClick = onTick))
        if (!accent) CbIcon(Icon.FOLDER, cb.mint, size = 22.dp)
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (accent) cb.mint else cb.ink))
            if (sub != null) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = monoStyle(cb.inkDim, 0.6.sp))
        }
        if (chevron) CbIcon(Icon.CHEVRON, cb.inkDim, size = 18.dp)
    }
}

/** A file row: tick, picture or type, name, size. A tap ticks it. */
@Composable
private fun FileRow(e: Entry, on: Boolean, onTick: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onTick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TickBox(on)
        Box(Modifier.size(44.dp).background(cb.surface2)) { Thumb(e) }
        Column(Modifier.weight(1f)) {
            Text(e.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cb.ink))
            Text(Formatter.formatShortFileSize(context, e.size).uppercase(), style = monoStyle(cb.inkDim, 0.6.sp))
        }
    }
}
