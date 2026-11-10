package com.claudeborne.gallerymanager.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import com.claudeborne.gallerymanager.data.Entry
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.data.SortBy
import com.claudeborne.gallerymanager.data.createFolder
import com.claudeborne.gallerymanager.data.deleteFiles
import com.claudeborne.gallerymanager.data.folderStats
import com.claudeborne.gallerymanager.data.isImage
import com.claudeborne.gallerymanager.data.isVideo
import com.claudeborne.gallerymanager.data.listDir
import com.claudeborne.gallerymanager.data.mime
import com.claudeborne.gallerymanager.data.renameFile
import com.claudeborne.gallerymanager.data.storageRoots
import com.claudeborne.gallerymanager.data.transfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
private val RED = Color(0xFFFF6B6B)

private sealed interface FileDialog {
    data object NewFolder : FileDialog
    class Rename(val file: File) : FileDialog
    class Delete(val files: List<File>) : FileDialog
    class Props(val file: File) : FileDialog
}

/** The Files tab: browse real folders on every storage volume and copy, move, rename, delete and share what is in them. */
@Composable
fun FilesScreen(library: List<MediaItem>, backEnabled: Boolean, onChanged: () -> Unit, onOpenMedia: (MediaItem) -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var allowed by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = Environment.isExternalStorageManager() }
    if (!allowed) {
        PermissionGate(context)
        return
    }

    val roots = remember { storageRoots(context) }
    var path by rememberSaveable { mutableStateOf<String?>(null) } // null shows the list of storage volumes
    var sort by rememberSaveable { mutableStateOf(SortBy.NAME) }
    var ascending by rememberSaveable { mutableStateOf(true) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var clipPaths by rememberSaveable { mutableStateOf(emptyList<String>()) } // COPY/MOVE remember what to paste; the user then browses to the target folder
    var clipMove by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<FileDialog?>(null) }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }

    val dir = path?.let { File(it) }
    val root = dir?.let { d -> roots.map { it.second }.filter { d.path.startsWith(it.path) }.maxByOrNull { it.path.length } }
    LaunchedEffect(path, sort, ascending, hidden, refresh) {
        entries = dir?.let { d -> withContext(Dispatchers.IO) { listDir(d, hidden, sort, ascending) } } ?: emptyList()
    }

    fun go(to: File?) { path = to?.path; selected = emptySet() }
    fun up() { if (dir != null && root != null) go(if (dir.path == root.path) null else dir.parentFile) }
    fun done() { selected = emptySet(); refresh++; onChanged() }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    val chosen = entries.filter { it.file.path in selected }
    val selecting = chosen.isNotEmpty()
    BackHandler(enabled = backEnabled && (selecting || dir != null)) { if (selecting) selected = emptySet() else up() }

    // a media file opens in our viewer when the library knows it, anything else goes to another app
    val byPath = remember(library) { library.associateBy { it.path + it.name } }
    fun open(file: File) {
        val key = root?.let { file.relativeTo(it).path.replace('\\', '/') }
        val media = key?.let { byPath[it] }
        if (media != null) onOpenMedia(media) else openWithApp(context, file)
    }

    Column(Modifier.fillMaxSize()) {
        val rootLabel = roots.firstOrNull { it.second.path == root?.path }?.first.orEmpty()
        // back goes up one folder; from a storage root it goes to the list of storage volumes
        val backLabel = when {
            dir == null || root == null || dir.path == root.path -> "STORAGE"
            dir.parentFile?.path == root.path -> rootLabel
            else -> dir.parentFile?.name.orEmpty()
        }
        ScreenHeader("FILES", if (selecting) "${chosen.size} SELECTED" else if (dir == null) "${roots.size} STORAGE" else "${entries.size} ITEMS",
            onBack = if (dir != null) ::up else null, backLabel = backLabel.uppercase())

        if (dir == null || root == null) {
            LazyColumn(Modifier.weight(1f)) {
                items(roots, key = { it.second.path }) { (label, folder) -> VolumeRow(label, folder) { go(folder) } }
            }
            return@Column
        }

        Crumbs(root, rootLabel, dir) { go(it) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            ActionText("+ FOLDER", cb.mint) { dialog = FileDialog.NewFolder }
            SortBy.entries.forEach { s ->
                val on = sort == s
                ActionText(s.name + if (on) (if (ascending) " ▲" else " ▼") else "", if (on) cb.mint else cb.inkDim) {
                    if (on) ascending = !ascending else { sort = s; ascending = s == SortBy.NAME || s == SortBy.TYPE }
                }
            }
            ActionText("HIDDEN", if (hidden) cb.mint else cb.inkDim) { hidden = !hidden }
            ActionText(if (grid) "LIST" else "GRID", cb.inkDim) { grid = !grid }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (entries.isEmpty()) {
                Text("EMPTY FOLDER", Modifier.align(Alignment.Center), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 2.sp, color = cb.inkDim))
            } else if (grid) {
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(entries, key = { it.file.path }) { e ->
                        GridTile(e, e.file.path in selected, onClick = { if (selecting) selected = toggle(selected, e.file.path) else if (e.isDir) go(e.file) else open(e.file) }, onLongClick = { selected = toggle(selected, e.file.path) })
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.file.path }) { e ->
                        ListRow(e, e.file.path in selected, onClick = { if (selecting) selected = toggle(selected, e.file.path) else if (e.isDir) go(e.file) else open(e.file) }, onLongClick = { selected = toggle(selected, e.file.path) })
                    }
                }
            }
        }

        if (clipPaths.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(cb.surface1).padding(vertical = 8.dp)) {
                Text(
                    "${if (clipMove) "MOVE" else "COPY"} ${clipPaths.size}", Modifier.weight(1f).padding(start = 16.dp).align(Alignment.CenterVertically),
                    style = monoStyle(cb.inkDim, 1.2.sp),
                )
                ActionText("CANCEL", cb.inkDim) { clipPaths = emptyList() }
                ActionText("PASTE HERE", cb.mint) {
                    val files = clipPaths.map(::File)
                    val move = clipMove
                    clipPaths = emptyList()
                    scope.launch { toast("${if (move) "Moved" else "Copied"} ${transfer(context, files, dir, move)} of ${files.size}"); done() }
                }
            }
        } else if (selecting) {
            Row(Modifier.fillMaxWidth().background(cb.surface1).padding(vertical = 8.dp)) {
                BarAction("ALL", cb.inkDim) { selected = entries.map { it.file.path }.toSet() }
                BarAction("SHARE", cb.mint) {
                    val files = chosen.filter { !it.isDir }.map { it.file }
                    if (files.isEmpty()) toast("Folders can't be shared") else shareFiles(context, files)
                }
                BarAction("COPY", cb.mint) { clipPaths = chosen.map { it.file.path }; clipMove = false; selected = emptySet() }
                BarAction("MOVE", cb.mint) { clipPaths = chosen.map { it.file.path }; clipMove = true; selected = emptySet() }
                if (chosen.size == 1) {
                    BarAction("RENAME", cb.mint) { dialog = FileDialog.Rename(chosen[0].file) }
                    BarAction("INFO", cb.mint) { dialog = FileDialog.Props(chosen[0].file) }
                }
                BarAction("DELETE", RED) { dialog = FileDialog.Delete(chosen.map { it.file }) }
            }
        }
    }

    when (val d = dialog) {
        FileDialog.NewFolder -> NameDialog("NEW FOLDER", "", "CREATE", { dialog = null }) { name ->
            dialog = null
            if (dir != null && createFolder(dir, name)) refresh++ else toast("Could not create the folder")
        }
        is FileDialog.Rename -> NameDialog("RENAME", d.file.name, "RENAME", { dialog = null }) { name ->
            dialog = null
            scope.launch { if (renameFile(context, d.file, name)) done() else toast("That name is taken") }
        }
        is FileDialog.Delete -> CbDialog("DELETE", { dialog = null }) {
            Text(
                "Delete ${if (d.files.size == 1) d.files[0].name else "${d.files.size} items"} for good? This cannot be undone.",
                style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2),
            )
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                ActionText("CANCEL", cb.inkDim) { dialog = null }
                ActionText("DELETE", RED) {
                    dialog = null
                    scope.launch { toast("Deleted ${deleteFiles(context, d.files)}"); done() }
                }
            }
        }
        is FileDialog.Props -> PropsDialog(d.file) { dialog = null }
        null -> Unit
    }
}

private fun toggle(set: Set<String>, p: String) = if (p in set) set - p else set + p

@Composable
private fun PermissionGate(context: Context) {
    val cb = LocalCb.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("FILES", "NO ACCESS")
        Column(Modifier.weight(1f).padding(32.dp), Arrangement.spacedBy(16.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
            Text(
                "To browse all folders, Gallery Manager needs \"All files access\". Turn it on for this app, then come back. Nothing leaves your phone.",
                style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2, textAlign = TextAlign.Center),
            )
            ActionText("OPEN SETTINGS", cb.mint) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null)))
            }
        }
    }
}

@Composable
private fun VolumeRow(label: String, folder: File, onClick: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val stat = remember(folder) { StatFs(folder.path) }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = cb.ink))
        Text(
            "${Formatter.formatShortFileSize(context, stat.availableBytes)} FREE OF ${Formatter.formatShortFileSize(context, stat.totalBytes)}",
            style = monoStyle(cb.inkDim, 1.sp),
        )
    }
}

/** Path from the storage root to [dir]; each part is tappable. */
@Composable
private fun Crumbs(root: File, rootLabel: String, dir: File, onGo: (File) -> Unit) {
    val cb = LocalCb.current
    val parts = generateSequence(dir) { if (it.path == root.path) null else it.parentFile }.toList().reversed()
    val scroll = rememberScrollState(Int.MAX_VALUE) // starts scrolled to the current folder
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, f ->
            if (i > 0) Text(" › ", style = monoStyle(cb.inkDim, 0.sp))
            Text(
                (if (f.path == root.path) rootLabel else f.name).uppercase(), Modifier.clickable { onGo(f) }.padding(vertical = 4.dp),
                style = monoStyle(if (f.path == dir.path) cb.mint else cb.inkDim, 1.sp),
            )
        }
    }
}

private fun subtitle(context: Context, e: Entry): String {
    val date = Instant.ofEpochMilli(e.modified).atZone(ZoneId.systemDefault()).format(dateFmt)
    return (if (e.isDir) "${e.children} ITEMS" else Formatter.formatShortFileSize(context, e.size).uppercase()) + " · $date"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(e: Entry, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(if (selected) cb.surface2 else Color.Transparent).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).background(cb.surface2).then(if (selected) Modifier.border(2.dp, cb.mint) else Modifier)) { Thumb(e) }
        Column(Modifier.weight(1f)) {
            Text(e.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cb.ink))
            Text(subtitle(context, e), maxLines = 1, style = monoStyle(cb.inkDim, 0.6.sp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridTile(e: Entry, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cb = LocalCb.current
    Box(
        Modifier.aspectRatio(1f).background(cb.surface2).combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selected) Modifier.border(3.dp, cb.mint) else Modifier),
    ) {
        Thumb(e)
        Text(
            e.file.name, Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 2.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontSize = 11.sp, color = Color.White),
        )
        if (selected) SelectedMark()
    }
}

/** A thumbnail for photos and videos; folders and other files show DIR or their extension. */
@Composable
private fun Thumb(e: Entry) {
    val cb = LocalCb.current
    if (!e.isDir && (e.file.isImage() || e.file.isVideo())) {
        AsyncImage(e.file, e.file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(
                if (e.isDir) "DIR" else e.file.extension.take(4).uppercase().ifEmpty { "FILE" }, maxLines = 1,
                style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp, color = if (e.isDir) cb.mint else cb.inkDim),
            )
        }
    }
}

@Composable
private fun RowScope.BarAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label, Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 10.dp),
        style = monoStyle(color, 0.6.sp).copy(textAlign = TextAlign.Center),
    )
}

@Composable
private fun NameDialog(title: String, initial: String, action: String, onCancel: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val name = text.trim()
    val valid = name.isNotEmpty() && '/' !in name && name != initial
    CbDialog(title, onCancel) {
        CbTextField(text, { text = it })
        Row(Modifier.fillMaxWidth(), Arrangement.End) {
            ActionText("CANCEL", DarkCb.inkDim, onCancel)
            ActionText(action, if (valid) DarkCb.mint else DarkCb.inkDim) { if (valid) onOk(name) }
        }
    }
}

@Composable
private fun PropsDialog(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    // a folder's size means walking it, so that part fills in once it is known
    val stats by produceState<Pair<Long, Int>?>(null, file) { if (file.isDirectory) value = folderStats(file) }
    val rows = buildList {
        add("NAME" to file.name)
        add("PATH" to file.parent.orEmpty())
        if (file.isDirectory) add("CONTAINS" to (stats?.let { "${it.second} files, ${Formatter.formatShortFileSize(context, it.first)}" } ?: "…"))
        else { add("TYPE" to file.mime().ifEmpty { "unknown" }); add("SIZE" to Formatter.formatShortFileSize(context, file.length())) }
        add("MODIFIED" to Instant.ofEpochMilli(file.lastModified()).atZone(ZoneId.systemDefault()).format(dateFmt))
    }
    CbDialog("INFO", onClose) {
        rows.forEach { (k, v) ->
            Column {
                Text(k, style = monoStyle(DarkCb.inkDim, 1.6.sp))
                Text(v, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = DarkCb.ink))
            }
        }
        Row(Modifier.fillMaxWidth(), Arrangement.End) { ActionText("CLOSE", DarkCb.mint, onClose) }
    }
}

private fun fileUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

private fun openWithApp(context: Context, file: File) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(fileUri(context, file), file.mime().ifEmpty { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.startActivity(view) } catch (_: ActivityNotFoundException) { Toast.makeText(context, "No app can open this", Toast.LENGTH_SHORT).show() }
}

private fun shareFiles(context: Context, files: List<File>) {
    val uris = ArrayList(files.map { fileUri(context, it) })
    val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(files[0].mime().ifEmpty { "*/*" })
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
    context.startActivity(Intent.createChooser(send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
}
