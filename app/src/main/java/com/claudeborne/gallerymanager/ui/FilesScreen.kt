package com.claudeborne.gallerymanager.ui

import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import com.claudeborne.gallerymanager.data.FileEntry
import com.claudeborne.gallerymanager.data.Kind
import com.claudeborne.gallerymanager.data.Recycle
import com.claudeborne.gallerymanager.data.SortBy
import com.claudeborne.gallerymanager.data.copyInto
import com.claudeborne.gallerymanager.data.dirSize
import com.claudeborne.gallerymanager.data.extOf
import com.claudeborne.gallerymanager.data.hasAllFilesAccess
import com.claudeborne.gallerymanager.data.kindOf
import com.claudeborne.gallerymanager.data.listDir
import com.claudeborne.gallerymanager.data.moveInto
import com.claudeborne.gallerymanager.data.pathsUnder
import com.claudeborne.gallerymanager.data.scanPaths
import com.claudeborne.gallerymanager.data.searchFiles
import com.claudeborne.gallerymanager.data.shareFiles
import com.claudeborne.gallerymanager.data.storageRoot
import com.claudeborne.gallerymanager.data.storageVolumes
import com.claudeborne.gallerymanager.data.uniqueFile
import com.claudeborne.gallerymanager.data.unzipTo
import com.claudeborne.gallerymanager.data.zipFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Copy/cut basket; lives in MainScaffold so it survives tab switches. */
class ClipState {
    var files by mutableStateOf(listOf<File>())
    var cut by mutableStateOf(false)
    fun clear() { files = emptyList() }
}

private sealed interface Dlg {
    data class Rename(val file: File) : Dlg
    data object NewFolder : Dlg
    data class Delete(val files: List<File>) : Dlg
    data class Info(val file: File) : Dlg
    data class Unzip(val file: File) : Dlg
    data object ZipName : Dlg
    data class Error(val msg: String) : Dlg
}

/** The file manager: browse every folder, search, sort, select and copy/move/delete/zip. [path] null shows the storage home. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(path: String?, onPath: (String?) -> Unit, clip: ClipState, onOpen: (File) -> Unit, onChanged: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(hasAllFilesAccess()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = hasAllFilesAccess() }

    if (!access) {
        AccessGate(); return
    }

    val volumes = remember { storageVolumes(context) }
    val dir = path?.let { File(it) }
    var sort by rememberSaveable { mutableStateOf(SortBy.NAME) }
    var ascending by rememberSaveable { mutableStateOf(true) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf<String?>(null) } // null = not searching
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var reload by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(dir, sort, ascending, showHidden, reload, query) {
        val d = dir
        val q = query
        entries = when {
            d == null -> emptyList()
            q != null -> if (q.length >= 2) searchFiles(d, q, showHidden) else emptyList()
            else -> listDir(d, showHidden, sort, ascending)
        }
    }

    val chosen = entries.filter { it.file.absolutePath in selected }.map { it.file }

    fun runOp(label: String, block: () -> Unit) {
        scope.launch {
            busy = label
            try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) { dialog = Dlg.Error(e.message ?: "Operation failed") }
            busy = null; selected = emptySet(); reload++; onChanged()
        }
    }

    fun goUp() {
        val d = dir ?: return
        onPath(if (volumes.any { it.second == d }) null else d.parentFile?.absolutePath)
    }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    BackHandler(enabled = selected.isEmpty() && query != null) { query = null }
    BackHandler(enabled = selected.isEmpty() && query == null && dir != null) { goUp() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "FILES",
            when {
                selected.isNotEmpty() -> "${selected.size} SELECTED"
                dir == null -> "STORAGE"
                else -> "${entries.size} ITEMS"
            },
        )

        if (dir == null) {
            StorageHome(volumes, onPath)
        } else {
            Breadcrumb(dir, volumes, onPath)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(if (query != null) "SEARCH ✕" else "SEARCH", on = query != null) { query = if (query == null) "" else null }
                Chip("${sort.label} ${if (ascending) "↑" else "↓"}") {
                    // tapping cycles the sort key; the arrow flips when the cycle wraps
                    val next = SortBy.entries[(sort.ordinal + 1) % SortBy.entries.size]
                    if (next == SortBy.NAME) ascending = !ascending
                    sort = next
                }
                Chip("HIDDEN", on = showHidden) { showHidden = !showHidden }
                Chip("+ FOLDER") { dialog = Dlg.NewFolder }
            }
            query?.let { q ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { CbInput(q, { query = it }, "Search in this folder…") }
            }

            if (entries.isEmpty()) EmptyNote(if (query != null) (if ((query ?: "").length < 2) "TYPE 2+ LETTERS" else "NO MATCHES") else "EMPTY FOLDER")
            LazyColumn(Modifier.weight(1f)) {
                items(entries, key = { it.file.absolutePath }) { e ->
                    val sel = e.file.absolutePath in selected
                    FileRow(
                        e, sel, showPath = query != null,
                        onClick = {
                            when {
                                selected.isNotEmpty() -> selected = if (sel) selected - e.file.absolutePath else selected + e.file.absolutePath
                                e.isDir -> { query = null; onPath(e.file.absolutePath) }
                                extOf(e.file) == "zip" -> dialog = Dlg.Unzip(e.file)
                                else -> onOpen(e.file)
                            }
                        },
                        onLongClick = { selected = if (sel) selected - e.file.absolutePath else selected + e.file.absolutePath },
                    )
                }
            }

            if (clip.files.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().background(cb.surface2).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${clip.files.size} TO ${if (clip.cut) "MOVE" else "COPY"}", Modifier.weight(1f).padding(start = 8.dp), style = mono(cb.ink2, 11, 1.6f))
                    ActionLabel("CANCEL", cb.inkDim) { clip.clear() }
                    ActionLabel("PASTE HERE", cb.mint) {
                        val files = clip.files; val cut = clip.cut
                        val before = pathsUnder(files)
                        runOp(if (cut) "MOVING" else "COPYING") {
                            val made = files.map { if (cut) moveInto(it, dir) else copyInto(it, dir) }
                            scanPaths(context, pathsUnder(made) + if (cut) before else emptyList())
                        }
                        clip.clear()
                    }
                }
            }
            if (selected.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().background(cb.surface1).horizontalScroll(rememberScrollState())) {
                    ActionLabel("ALL", cb.inkDim) { selected = entries.map { it.file.absolutePath }.toSet() }
                    ActionLabel("COPY", cb.mint) { clip.files = chosen; clip.cut = false; selected = emptySet() }
                    ActionLabel("MOVE", cb.mint) { clip.files = chosen; clip.cut = true; selected = emptySet() }
                    ActionLabel("SHARE", cb.mint, enabled = chosen.any { it.isFile }) { shareFiles(context, chosen) }
                    ActionLabel("RENAME", cb.mint, enabled = chosen.size == 1) { dialog = Dlg.Rename(chosen[0]) }
                    ActionLabel("ZIP", cb.mint) { dialog = Dlg.ZipName }
                    ActionLabel("VAULT", cb.mint, enabled = chosen.any { it.isFile }) {
                        val files = chosen.filter { it.isFile }
                        val before = pathsUnder(files)
                        runOp("ENCRYPTING") { files.forEach { com.claudeborne.gallerymanager.data.vaultAdd(context, it) }; scanPaths(context, before) }
                    }
                    ActionLabel("INFO", cb.mint, enabled = chosen.size == 1) { dialog = Dlg.Info(chosen[0]) }
                    ActionLabel("DELETE", DangerRed) { dialog = Dlg.Delete(chosen) }
                }
            }
        }
    }

    // ---- dialogs ----
    when (val d = dialog) {
        is Dlg.Rename -> {
            var name by remember { mutableStateOf(d.file.name) }
            CbDialog("RENAME", { dialog = null }, "RENAME", onConfirm = {
                val target = File(d.file.parentFile, name.trim())
                dialog = null
                if (name.isBlank() || name.contains('/')) dialog = Dlg.Error("Invalid name")
                else if (target.exists()) dialog = Dlg.Error("“${target.name}” already exists")
                else {
                    val before = pathsUnder(listOf(d.file))
                    runOp("RENAMING") { d.file.renameTo(target); scanPaths(context, before + pathsUnder(listOf(target))) }
                }
            }) { CbInput(name, { name = it }) }
        }
        Dlg.NewFolder -> {
            var name by remember { mutableStateOf("") }
            CbDialog("NEW FOLDER", { dialog = null }, "CREATE", onConfirm = {
                dialog = null
                if (name.isBlank() || name.contains('/')) dialog = Dlg.Error("Invalid name")
                else if (File(dir, name.trim()).exists()) dialog = Dlg.Error("That name already exists")
                else runOp("CREATING") { File(dir, name.trim()).mkdirs() }
            }) { CbInput(name, { name = it }, "Folder name") }
        }
        is Dlg.Delete -> CbDialog("DELETE", { dialog = null }, "DELETE", danger = true, onConfirm = {
            val files = d.files; dialog = null
            val before = pathsUnder(files)
            runOp("DELETING") { files.forEach { Recycle.put(it) }; scanPaths(context, before) }
        }) { DialogText("Move ${d.files.size} item${if (d.files.size == 1) "" else "s"} to the file trash? You can restore them from the TRASH tab for 30 days.") }
        is Dlg.Info -> {
            var size by remember { mutableStateOf<Long?>(null) }
            LaunchedEffect(d.file) { size = withContext(Dispatchers.IO) { dirSize(d.file) } }
            CbDialog("INFO", { dialog = null }, "") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoLine("NAME", d.file.name)
                    InfoLine("PATH", d.file.parent.orEmpty())
                    InfoLine("TYPE", if (d.file.isDirectory) "Folder" else (extOf(d.file).ifEmpty { "File" }.uppercase()))
                    InfoLine("SIZE", size?.let { sizeText(it) } ?: "…")
                    InfoLine("MODIFIED", dateText(d.file.lastModified()))
                }
            }
        }
        is Dlg.Unzip -> CbDialog("ZIP ARCHIVE", { dialog = null }, "EXTRACT", onConfirm = {
            val zip = d.file; dialog = null
            runOp("EXTRACTING") {
                val target = uniqueFile(zip.parentFile ?: storageRoot, zip.nameWithoutExtension)
                scanPaths(context, unzipTo(zip, target))
            }
        }) {
            DialogText("Extract “${d.file.name}” into a new folder next to it?")
            Chip("OPEN WITH ANOTHER APP") { dialog = null; com.claudeborne.gallerymanager.data.openExternally(context, d.file) }
        }
        Dlg.ZipName -> {
            var name by remember { mutableStateOf(if (chosen.size == 1) chosen[0].nameWithoutExtension else "Archive") }
            CbDialog("ZIP ${chosen.size} ITEMS", { dialog = null }, "CREATE", onConfirm = {
                val files = chosen; dialog = null
                runOp("ZIPPING") {
                    val out = uniqueFile(dir ?: storageRoot, name.trim().removeSuffix(".zip") + ".zip")
                    zipFiles(files, out)
                }
            }) { CbInput(name, { name = it }, "Archive name") }
        }
        is Dlg.Error -> CbDialog("PROBLEM", { dialog = null }, "") { DialogText(d.msg) }
        null -> {}
    }

    busy?.let { label ->
        Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
            Text("$label…", style = mono(cb.mint, 12, 2.6f, bold = true))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val cb = LocalCb.current
    Column {
        Text(label, style = mono(cb.inkDim, 10, 2f))
        Text(value, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.ink))
    }
}

@Composable
internal fun AccessGate() {
    val cb = LocalCb.current
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("FILE ACCESS", style = TextStyle(fontFamily = Tourney, fontSize = 28.sp, brush = cb.grad))
        Text(
            "To browse and manage every folder, Android needs you to allow “All files access” for this app. Nothing leaves your phone.",
            style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        )
        Text(
            "ALLOW ACCESS",
            Modifier.background(cb.grad).clickable {
                val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null))
                runCatching { context.startActivity(i) }.onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }.padding(horizontal = 20.dp, vertical = 12.dp),
            style = mono(DarkCb.surface0, 11, 2.2f, bold = true),
        )
    }
}

@Composable
private fun StorageHome(volumes: List<Pair<String, File>>, onPath: (String?) -> Unit) {
    val cb = LocalCb.current
    val shortcuts = remember {
        listOf("Download", "DCIM", "Pictures", "Movies", "Music", "Documents", "Android").map { File(storageRoot, it) }.filter { it.isDirectory }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(volumes) { (name, dir) ->
            val stat = remember(dir) { runCatching { StatFs(dir.path) }.getOrNull() }
            val total = stat?.totalBytes ?: 0L
            val free = stat?.availableBytes ?: 0L
            Column(Modifier.fillMaxWidth().clickable { onPath(dir.absolutePath) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(name, style = mono(cb.mint, 11, 2.6f))
                Text("${sizeText(free)} free of ${sizeText(total)}", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2))
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).background(cb.surface3)) {
                    val frac = if (total > 0) (total - free).toFloat() / total else 0f
                    Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(4.dp).background(cb.grad))
                }
            }
        }
        item { Text("SHORTCUTS", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = mono(cb.inkDim, 11, 2.4f)) }
        items(shortcuts) { f ->
            Text(
                f.name,
                Modifier.fillMaxWidth().clickable { onPath(f.absolutePath) }.padding(horizontal = 16.dp, vertical = 14.dp),
                style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = cb.ink),
            )
        }
    }
}

@Composable
private fun Breadcrumb(dir: File, volumes: List<Pair<String, File>>, onPath: (String?) -> Unit) {
    val cb = LocalCb.current
    val vol = volumes.firstOrNull { dir.path.startsWith(it.second.path) } ?: volumes.first()
    val parts = dir.relativeTo(vol.second).path.split('/').filter { it.isNotEmpty() }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(Int.MAX_VALUE)).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("HOME", Modifier.clickable { onPath(null) }.padding(vertical = 6.dp), style = mono(cb.mint, 10, 1.6f))
        Text(" › ", style = mono(cb.inkDim, 10, 1f))
        Text(vol.first, Modifier.clickable { onPath(vol.second.absolutePath) }.padding(vertical = 6.dp), style = mono(cb.mint, 10, 1.6f))
        var acc = vol.second
        parts.forEach { p ->
            acc = File(acc, p)
            val target = acc.absolutePath
            Text(" › ", style = mono(cb.inkDim, 10, 1f))
            Text(p, Modifier.clickable { onPath(target) }.padding(vertical = 6.dp), style = mono(cb.ink2, 10, 1.2f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(e: FileEntry, selected: Boolean, showPath: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cb = LocalCb.current
    val kind = remember(e.file) { kindOf(e.file) }
    Row(
        Modifier.fillMaxWidth().then(if (selected) Modifier.background(cb.surface2) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).background(cb.surface2).then(if (selected) Modifier.border(2.dp, cb.mint) else Modifier), contentAlignment = Alignment.Center) {
            if (kind == Kind.IMAGE || kind == Kind.VIDEO) AsyncImage(e.file, e.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(
                if (e.isDir) "▸" else extOf(e.file).take(4).uppercase().ifEmpty { "·" },
                style = mono(if (e.isDir) cb.mint else cb.ink2, if (e.isDir) 20 else 10, 0.8f, bold = e.isDir),
            )
            if (selected) Text("✓", Modifier.align(Alignment.TopEnd).background(cb.mint).padding(horizontal = 4.dp), style = mono(DarkCb.surface0, 11, 0f))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = cb.ink))
            Text(
                (if (e.isDir) "${e.childCount} items" else sizeText(e.size)) + " · " + dateText(e.modifiedMs),
                style = mono(cb.inkDim, 10, 0.6f),
            )
            if (showPath) Text(e.file.parent.orEmpty().removePrefix(storageRoot.path), maxLines = 1, overflow = TextOverflow.Ellipsis, style = mono(cb.inkDim, 9, 0.4f))
        }
    }
}
