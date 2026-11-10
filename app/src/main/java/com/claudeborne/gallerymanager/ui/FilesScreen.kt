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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import com.claudeborne.gallerymanager.data.BIN_DAYS
import com.claudeborne.gallerymanager.data.Category
import com.claudeborne.gallerymanager.data.Entry
import com.claudeborne.gallerymanager.data.LARGE_FILE_BYTES
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.data.SortBy
import com.claudeborne.gallerymanager.data.categoryFiles
import com.claudeborne.gallerymanager.data.countChildren
import com.claudeborne.gallerymanager.data.createFolder
import com.claudeborne.gallerymanager.data.folderStats
import com.claudeborne.gallerymanager.data.isImage
import com.claudeborne.gallerymanager.data.isVideo
import com.claudeborne.gallerymanager.data.listBin
import com.claudeborne.gallerymanager.data.listDir
import com.claudeborne.gallerymanager.data.mime
import com.claudeborne.gallerymanager.data.moveToBin
import com.claudeborne.gallerymanager.data.purgeExpiredBin
import com.claudeborne.gallerymanager.data.renameFile
import com.claudeborne.gallerymanager.data.searchFiles
import com.claudeborne.gallerymanager.data.storageRoots
import com.claudeborne.gallerymanager.data.transfer
import com.claudeborne.gallerymanager.transfer.expandForSend
import com.claudeborne.gallerymanager.data.ARCHIVE_EXTENSIONS
import com.claudeborne.gallerymanager.data.extractArchive
import com.claudeborne.gallerymanager.data.defaultZipName
import com.claudeborne.gallerymanager.data.zipFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
internal val RED = Color(0xFFFF6B6B)

internal fun dateText(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateFmt)

private sealed interface FileDialog {
    data object NewFolder : FileDialog
    class Rename(val file: File) : FileDialog
    class Delete(val files: List<File>) : FileDialog
    class Props(val file: File) : FileDialog
    class Unzip(val file: File) : FileDialog
    class Zip(val files: List<File>) : FileDialog
}

/** The Files tab: a home screen (categories, bin, storage), folder browsing, search, and the file actions. */
@Composable
fun FilesScreen(library: List<MediaItem>, trash: List<MediaItem>, backEnabled: Boolean, onChanged: () -> Unit, onDeep: (Boolean) -> Unit, onOpenMedia: (MediaItem) -> Unit) {
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
    var path by rememberSaveable { mutableStateOf<String?>(null) } // null: the home screen, or a category view
    var category by rememberSaveable { mutableStateOf<Category?>(null) }
    var inBin by rememberSaveable { mutableStateOf(false) }
    var mapRoot by rememberSaveable { mutableStateOf<String?>(null) } // set while the storage map of that folder is open
    var inDupes by rememberSaveable { mutableStateOf(false) }
    var dualPath by rememberSaveable { mutableStateOf<String?>(null) } // the dual-pane screen is open while this is set; both panes start in this folder (saved, so turning the phone keeps it open)
    val dual = dualPath?.let(::File)
    var vault by remember { mutableStateOf<List<File>?>(null) } // the safe folder is open while this is set; the files in it are waiting to be moved in
    var transfer by remember { mutableStateOf<List<Pair<File, String>>?>(null) } // the files to send while the Transfer screen is open (empty: receive only)
    var picking by rememberSaveable { mutableStateOf(false) } // SEND was tapped with nothing selected: browse, select, then tap SEND
    var sort by rememberSaveable { mutableStateOf(SortBy.NAME) }
    var ascending by rememberSaveable { mutableStateOf(true) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var allScope by rememberSaveable { mutableStateOf(false) } // search every storage volume instead of the open folder
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var clipPaths by rememberSaveable { mutableStateOf(emptyList<String>()) } // COPY/MOVE remember what to paste; the user then browses to the target folder
    var clipMove by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<FileDialog?>(null) }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var binCount by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }

    val dir = path?.let { File(it) }
    fun rootOf(f: File) = roots.map { it.second }.filter { f.path.startsWith(it.path) }.maxByOrNull { it.path.length }
    val root = dir?.let { rootOf(it) }
    val cat = category
    val term = query.trim()
    val showResults = searching && term.isNotEmpty()
    // the tools (map, dual pane, bin...) are screens of their own, so swiping sideways must not carry the user off to the Gallery
    val deep = inBin || mapRoot != null || inDupes || dual != null || vault != null || transfer != null
    SideEffect { onDeep(deep) }

    LaunchedEffect(refresh, inBin) {
        withContext(Dispatchers.IO) { purgeExpiredBin(context); binCount = listBin(context).size }
    }
    LaunchedEffect(path, sort, ascending, hidden, refresh, category, searching, query, allScope) {
        when {
            showResults -> {
                delay(300) // wait for the typing to pause; the next keystroke cancels this and starts over
                searchFiles(if (allScope || dir == null) roots.map { it.second } else listOf(dir), term, hidden) { entries = it }
            }
            cat != null -> entries = categoryFiles(context, cat)
            dir != null -> {
                // the list goes on screen first; the item counts of its folders (one extra disk read each) follow
                val listed = withContext(Dispatchers.IO) { listDir(dir, hidden, sort, ascending) }
                entries = listed
                if (listed.any { it.isDir }) entries = withContext(Dispatchers.IO) { countChildren(listed) }
            }
            else -> entries = emptyList()
        }
    }

    fun go(to: File?) { path = to?.path; category = null; searching = false; query = ""; selected = emptySet() }
    fun up() { if (dir != null && root != null) go(if (dir.path == root.path) null else dir.parentFile) }
    fun done() { selected = emptySet(); refresh++; onChanged() }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    val chosen = entries.filter { it.file.path in selected }
    val selecting = chosen.isNotEmpty()
    BackHandler(enabled = backEnabled && !inBin && mapRoot == null && !inDupes && transfer == null && vault == null && dual == null && (selecting || searching || cat != null || dir != null)) {
        when {
            selecting -> selected = emptySet()
            searching -> { searching = false; query = "" }
            cat != null -> category = null
            else -> up()
        }
    }

    // a media file opens in our viewer when the library knows it, anything else goes to another app
    val byPath = remember(library) { library.associateBy { it.path + it.name } }
    fun open(file: File) {
        val key = rootOf(file)?.let { file.relativeTo(it).path.replace('\\', '/') }
        val media = key?.let { byPath[it] }
        if (media != null) onOpenMedia(media) else openWithApp(context, file)
    }
    fun tap(e: Entry) {
        when {
            picking && !e.isDir -> selected = toggle(selected, e.file.path) // choosing files to send: a tap selects, and a folder still opens
            selected.isNotEmpty() && !picking -> selected = toggle(selected, e.file.path) // read at the moment of the tap: a stale copy of "selecting" would open the file instead
            e.isDir -> go(e.file)
            e.file.extension.lowercase() in ARCHIVE_EXTENSIONS -> dialog = FileDialog.Unzip(e.file) // a zip, rar or 7z offers to extract
            else -> open(e.file)
        }
    }
    fun longPress(e: Entry) { selected = toggle(selected, e.file.path) }

    // which screen is showing: the tools are screens of their own, the browser is 0. The last value of each tool is kept so it can animate out.
    val screen = when { inBin -> 1; mapRoot != null -> 2; transfer != null -> 3; dual != null -> 4; vault != null -> 5; inDupes -> 6; else -> 0 }
    var lastMap by remember { mutableStateOf(mapRoot) }
    var lastTransfer by remember { mutableStateOf(transfer) }
    var lastDual by remember { mutableStateOf(dual) }
    var lastVault by remember { mutableStateOf(vault) }
    if (mapRoot != null) lastMap = mapRoot
    if (transfer != null) lastTransfer = transfer
    if (dual != null) lastDual = dual
    if (vault != null) lastVault = vault

    val browse: @Composable () -> Unit = {
    val onHome = dir == null && cat == null
    val rootLabel = roots.firstOrNull { it.second.path == root?.path }?.first.orEmpty()
    // back goes up one folder; from a storage root or a category it goes to the home screen
    val backLabel = when {
        cat != null || dir == null || root == null || dir.path == root.path -> "FILES"
        dir.parentFile?.path == root.path -> rootLabel
        else -> dir.parentFile?.name.orEmpty()
    }
    val onBack: (() -> Unit)? = if (cat != null) ({ category = null }) else if (dir != null) ::up else null
    val count = busy ?: when {
        selecting -> "${chosen.size} SELECTED"
        showResults -> "${entries.size} FOUND"
        cat != null -> "${entries.size} FILES"
        dir == null -> "${roots.size} STORAGE"
        else -> "${entries.size} ITEMS"
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(cat?.label ?: "FILES", count, onBack, backLabel.uppercase())
        AnimatedOverlay(busy, expandEnter, shrinkExit) { WorkingLine(it, null, cb.mint) } // copy, move, zip and extract report no percentage, so the bar sweeps
        Visible(picking, enter = expandEnter, exit = shrinkExit) {
            Row(Modifier.fillMaxWidth().background(cb.surface1), verticalAlignment = Alignment.CenterVertically) {
                Text("TAP FILES TO SELECT, THEN SEND", Modifier.weight(1f).padding(start = 16.dp, top = 10.dp, bottom = 10.dp), style = monoStyle(cb.mint, 0.6.sp))
                ActionText("CANCEL", cb.inkDim) { picking = false; selected = emptySet() }
            }
        }

        if (dir != null && root != null && !searching) Crumbs(root, rootLabel, dir) { go(it) }
        if (cat == null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                if (dir != null) ActionText("+ FOLDER", cb.mint) { dialog = FileDialog.NewFolder }
                if (dir != null) ActionText("MAP", cb.mint) { mapRoot = dir.path }
                if (dir != null) ActionText("SPLIT", cb.mint) { dualPath = dir.path }
                SearchAction(searching, query, { query = it }) { searching = !searching; if (!searching) query = "" }
                if (dir != null) {
                    SortBy.entries.forEach { s ->
                        val on = sort == s
                        ActionText(s.name + if (on) (if (ascending) " ▲" else " ▼") else "", if (on) cb.mint else cb.inkDim) {
                            if (on) ascending = !ascending else { sort = s; ascending = s == SortBy.NAME || s == SortBy.TYPE }
                        }
                    }
                    ActionText("HIDDEN", if (hidden) cb.mint else cb.inkDim) { hidden = !hidden }
                    ActionText(if (grid) "LIST" else "GRID", cb.inkDim) { grid = !grid }
                }
            }
        }
        if (searching && dir != null) ScopeRow(allScope) { allScope = it }

        Crossfade(onHome && !showResults, Modifier.weight(1f).fillMaxWidth(), tween(MOTION_MS), label = "home") { home ->
            if (home) {
                HomeList(roots, binCount + trash.size, onFolder = { go(it) }, onCategory = { category = it }, onBin = { inBin = true }, onMap = { mapRoot = roots.first().second.path }, onDupes = { inDupes = true }, onTransfer = { transfer = emptyList() }, onVault = { vault = emptyList() }, onDual = { dualPath = roots.first().second.path })
            } else {
                FileList(
                    entries, grid && cat == null && !showResults, selected, showPath = showResults || cat != null,
                    empty = if (showResults) "NO MATCHES" else "EMPTY FOLDER", onTap = ::tap, onLong = ::longPress,
                )
            }
        }

        AnimatedVisibility(clipPaths.isNotEmpty(), enter = barEnter, exit = barExit) {
            Row(Modifier.fillMaxWidth().background(cb.surface1).padding(vertical = 8.dp)) {
                Text(
                    "${if (clipMove) "MOVE" else "COPY"} ${clipPaths.size}", Modifier.weight(1f).padding(start = 16.dp).align(Alignment.CenterVertically),
                    style = monoStyle(cb.inkDim, 1.2.sp),
                )
                ActionText("CANCEL", cb.inkDim) { clipPaths = emptyList() }
                if (dir != null) ActionText("PASTE HERE", cb.mint) {
                    val files = clipPaths.map(::File)
                    val move = clipMove
                    clipPaths = emptyList()
                    scope.launch {
                        busy = if (move) "MOVING…" else "COPYING…"
                        toast("${if (move) "Moved" else "Copied"} ${transfer(context, files, dir, move)} of ${files.size}")
                        busy = null
                        done()
                    }
                }
            }
        }
        AnimatedVisibility((selecting || (picking && dir != null)) && clipPaths.isEmpty(), enter = barEnter, exit = barExit) {
            val toTransfer = { files: List<File> ->
                scope.launch { transfer = withContext(Dispatchers.IO) { expandForSend(files) }; selected = emptySet(); picking = false }
                Unit
            }
            ActionBar(
                buildList {
                    if (picking) { // choosing what to send: only the choices that lead to sending
                        if (selecting) {
                            add(BarItem("ALL", cb.inkDim) { selected = entries.filter { !it.isDir }.map { it.file.path }.toSet() })
                            add(BarItem("SEND", cb.mint) { toTransfer(chosen.map { it.file }) })
                        }
                        if (dir != null) add(BarItem("SEND THIS FOLDER", cb.mint) { toTransfer(listOf(dir)) })
                        return@buildList
                    }
                    add(BarItem("ALL", cb.inkDim) { selected = entries.map { it.file.path }.toSet() })
                    add(BarItem("SHARE", cb.mint) {
                        val files = chosen.filter { !it.isDir }.map { it.file }
                        if (files.isEmpty()) toast("Folders can't be shared") else shareFiles(context, files)
                    })
                    add(BarItem("COPY", cb.mint) { clipPaths = chosen.map { it.file.path }; clipMove = false; selected = emptySet() })
                    add(BarItem("MOVE", cb.mint) { clipPaths = chosen.map { it.file.path }; clipMove = true; selected = emptySet() })
                    add(BarItem("SAFE", cb.mint) { vault = chosen.map { it.file }; selected = emptySet() })
                    add(BarItem("ZIP", cb.mint) { dialog = FileDialog.Zip(chosen.map { it.file }) })
                    if (chosen.size == 1) {
                        add(BarItem("RENAME", cb.mint) { dialog = FileDialog.Rename(chosen[0].file) })
                        add(BarItem("INFO", cb.mint) { dialog = FileDialog.Props(chosen[0].file) })
                    }
                    add(BarItem("DELETE", RED) { dialog = FileDialog.Delete(chosen.map { it.file }) })
                },
            )
        }
    }
    }

    AnimatedContent(
        screen,
        transitionSpec = { if (targetState != 0) pushEnter togetherWith fadeOut(tween(MOTION_FAST_MS)) else fadeIn(tween(MOTION_MS)) togetherWith pushExit },
        label = "files",
    ) { s ->
        when (s) {
            1 -> TrashView(trash, backEnabled, onBack = { inBin = false }, onChanged = { refresh++; onChanged() })
            2 -> lastMap?.let { start ->
                val label = roots.firstOrNull { it.second.path == start }?.first ?: File(start).name
                StorageMapScreen(File(start), label, backEnabled, onClose = { mapRoot = null }, onBrowse = { go(it); mapRoot = null }, onOpen = ::open, onChanged = { refresh++; onChanged() })
            }
            3 -> lastTransfer?.let { TransferScreen(it, backEnabled, onClose = { transfer = null }, onPick = { transfer = null; picking = true }) }
            4 -> lastDual?.let { DualPaneScreen(it, roots, backEnabled, onClose = { dualPath = null }, onChanged = { refresh++; onChanged() }, onOpen = ::open) }
            5 -> lastVault?.let { VaultScreen(it, backEnabled, onClose = { vault = null }, onChanged = { refresh++; onChanged() }) }
            6 -> DuplicatesScreen(roots.map { it.second }, backEnabled, onClose = { inDupes = false }, onOpen = ::open, onChanged = { refresh++; onChanged() })
            else -> browse()
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
        is FileDialog.Delete -> CbDialog("MOVE TO TRASH", { dialog = null }) {
            Text(
                "Move ${if (d.files.size == 1) d.files[0].name else "${d.files.size} items"} to the trash? You can restore ${if (d.files.size == 1) "it" else "them"} for $BIN_DAYS days.",
                style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2),
            )
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                ActionText("CANCEL", cb.inkDim) { dialog = null }
                ActionText("MOVE TO TRASH", RED) {
                    dialog = null
                    scope.launch { toast("Moved ${moveToBin(context, d.files)} to the trash"); done() }
                }
            }
        }
        is FileDialog.Zip -> NameDialog("ZIP", defaultZipName(d.files), "CREATE", { dialog = null }, allowSame = true, suffix = ".zip") { name ->
            dialog = null
            val into = d.files[0].parentFile
            if (into != null) scope.launch {
                busy = "ZIPPING…"
                val zip = zipFiles(context, d.files, into, name.removeSuffix(".zip"))
                busy = null
                toast(if (zip != null) "Created ${zip.name}" else "Could not create the zip")
                done()
            }
        }
        is FileDialog.Props -> PropsDialog(d.file) { dialog = null }
        is FileDialog.Unzip -> CbDialog("EXTRACT", { dialog = null }) {
            Text("Extract ${d.file.name} into a new folder next to it?", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2))
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                ActionText("CANCEL", cb.inkDim) { dialog = null }
                ActionText("EXTRACT", cb.mint) {
                    dialog = null
                    scope.launch {
                        busy = "EXTRACTING…"
                        val out = extractArchive(context, d.file)
                        busy = null
                        toast(out.fold({ "Extracted to ${it.name}" }, { "Could not extract: ${it.message}" }))
                        done()
                    }
                }
            }
        }
        null -> Unit
    }
}

internal fun toggle(set: Set<String>, p: String) = if (p in set) set - p else set + p

@Composable
private fun PermissionGate(context: Context) {
    val cb = LocalCb.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("FILES", "NO ACCESS")
        Column(Modifier.weight(1f).padding(32.dp), Arrangement.spacedBy(16.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
            Text(
                "To browse all folders, Altus Cirrus needs \"All files access\". Turn it on for this app, then come back. Nothing leaves your phone.",
                style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2, textAlign = TextAlign.Center),
            )
            ActionText("OPEN SETTINGS", cb.mint) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null)))
            }
        }
    }
}

/** The home screen: a card for each storage volume, the file categories as small cards, then the tools in two columns. */
@Composable
private fun HomeList(roots: List<Pair<String, File>>, binCount: Int, onFolder: (File) -> Unit, onCategory: (Category) -> Unit, onBin: () -> Unit, onMap: () -> Unit, onDupes: () -> Unit, onTransfer: () -> Unit, onVault: () -> Unit, onDual: () -> Unit) {
    val tools = listOf("STORAGE MAP" to onMap, "DUPLICATES" to onDupes, "DUAL PANE" to onDual, "SAFE FOLDER" to onVault, "TRANSFER" to onTransfer, "TRASH · $binCount" to onBin)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(roots, key = { it.second.path }) { (label, folder) -> StorageCard(label, folder) { onFolder(folder) } }
        item { SectionLabel("CATEGORIES") }
        items(Category.entries.chunked(2)) { pair ->
            Row(Modifier.padding(horizontal = 16.dp), Arrangement.spacedBy(8.dp)) {
                pair.forEach { MiniCard(it.label, Modifier.weight(1f)) { onCategory(it) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { SectionLabel("TOOLS") }
        items(tools.chunked(2)) { pair ->
            Row(Modifier.padding(horizontal = 16.dp), Arrangement.spacedBy(16.dp)) {
                pair.forEach { (name, open) -> ToolRow(name, Modifier.weight(1f), open) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A storage volume with how full it is. */
@Composable
private fun StorageCard(label: String, folder: File, onClick: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val stat = remember(folder) { StatFs(folder.path) }
    val total = stat.totalBytes
    val used = total - stat.availableBytes
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable(onClick = onClick).doubleChamferFrame(cb.mint).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = cb.ink))
        Text(
            "${Formatter.formatShortFileSize(context, used).uppercase()} USED OF ${Formatter.formatShortFileSize(context, total).uppercase()}",
            style = monoStyle(cb.inkDim, 1.sp),
        )
        // the usage bar fills up when the card appears
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val fill by animateFloatAsState(if (shown && total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f, tween(700), label = "usage")
        Box(Modifier.fillMaxWidth().height(6.dp).background(cb.surface2)) {
            Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(cb.grad))
        }
    }
}

@Composable
private fun MiniCard(title: String, modifier: Modifier, onClick: () -> Unit) {
    val cb = LocalCb.current
    Box(modifier.background(cb.surface1).border(1.dp, cb.line).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 16.dp)) {
        Text(title, maxLines = 1, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = cb.ink))
    }
}

@Composable
private fun ToolRow(title: String, modifier: Modifier, onClick: () -> Unit) {
    val cb = LocalCb.current
    Box(modifier.clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(title, maxLines = 1, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = cb.ink))
    }
}

/** The category's name as the screen title. */
private val Category.label get() = if (this == Category.LARGE) "LARGE FILES" else name

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp), style = monoStyle(LocalCb.current.mint, 2.4.sp))
}

@Composable
internal fun HomeRow(title: String, sub: String, onClick: () -> Unit) {
    val cb = LocalCb.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = cb.ink))
        Text(sub, style = monoStyle(cb.inkDim, 1.sp))
    }
}

/**
 * The search button: a magnifier and the word SEARCH. Tapping it swaps the word for the text field, to the right of the magnifier,
 * which stays; tapping the magnifier again closes the field.
 */
@Composable
private fun SearchAction(searching: Boolean, query: String, onQuery: (String) -> Unit, onToggle: () -> Unit) {
    val cb = LocalCb.current
    Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CbIcon(Icon.SEARCH, if (searching) cb.mint else cb.inkDim, size = 20.dp)
            AnimatedVisibility(!searching, enter = fadeIn(tween(MOTION_FAST_MS)), exit = fadeOut(tween(MOTION_FAST_MS))) { Text("SEARCH", Modifier.padding(start = 6.dp), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.2.sp, color = cb.inkDim)) }
        }
        AnimatedVisibility(searching, enter = fadeIn(tween(MOTION_FAST_MS)) + expandHorizontally(tween(MOTION_FAST_MS)), exit = fadeOut(tween(MOTION_FAST_MS)) + shrinkHorizontally(tween(MOTION_FAST_MS))) {
            Box(Modifier.width(220.dp).padding(end = 8.dp)) { CbTextField(query, onQuery) }
        }
    }
}

/** Inside a folder: search just that folder, or all storage. */
@Composable
private fun ScopeRow(allScope: Boolean, onScope: (Boolean) -> Unit) {
    val cb = LocalCb.current
    Row(Modifier.padding(horizontal = 8.dp)) {
        ActionText("THIS FOLDER", if (!allScope) cb.mint else cb.inkDim) { onScope(false) }
        ActionText("ALL STORAGE", if (allScope) cb.mint else cb.inkDim) { onScope(true) }
    }
}

/** Path from the storage root to [dir]; each part is tappable. */
@Composable
internal fun Crumbs(root: File, rootLabel: String, dir: File, onGo: (File) -> Unit) {
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

/** Folder contents, search results or a category as a list or a grid. With [showPath] each row also names its folder. */
@Composable
private fun FileList(entries: List<Entry>, grid: Boolean, selected: Set<String>, showPath: Boolean, empty: String, onTap: (Entry) -> Unit, onLong: (Entry) -> Unit) {
    val cb = LocalCb.current
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(empty, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 2.sp, color = cb.inkDim))
        }
    } else if (grid) {
        LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(entries, key = { it.file.path }) { e -> Box(Modifier.animateItem()) { GridTile(e, e.file.path in selected, { onTap(e) }, { onLong(e) }) } }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries, key = { it.file.path }) { e -> Box(Modifier.animateItem()) { ListRow(e, e.file.path in selected, { onTap(e) }, { onLong(e) }, path = if (showPath) e.file.parent else null) } }
        }
    }
}

private fun subtitle(context: Context, e: Entry): String =
    (if (e.isDir) (if (e.children >= 0) "${e.children} ITEMS" else "FOLDER") else Formatter.formatShortFileSize(context, e.size).uppercase()) + " · ${dateText(e.modified)}"

/** One file row; [note] replaces the size and date line, [path] adds a line with the folder. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ListRow(e: Entry, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, note: String? = null, path: String? = null) {
    val cb = LocalCb.current
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(glide(selected, cb.surface2, Color.Transparent)).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).background(cb.surface2).border(2.dp, glide(selected, cb.mint, Color.Transparent))) { Thumb(e) }
        Column(Modifier.weight(1f)) {
            Text(e.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cb.ink))
            Text(note ?: subtitle(context, e), maxLines = 1, style = monoStyle(cb.inkDim, 0.6.sp))
            if (path != null) Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = monoStyle(cb.inkDim, 0.6.sp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridTile(e: Entry, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cb = LocalCb.current
    Box(
        Modifier.aspectRatio(1f).background(cb.surface2).combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .border(3.dp, glide(selected, cb.mint, Color.Transparent)),
    ) {
        Thumb(e)
        Text(
            e.file.name, Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 2.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontSize = 11.sp, color = Color.White),
        )
        SelectedMark(selected)
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

internal class BarItem(val label: String, val color: Color, val onClick: () -> Unit)

/** The bottom action bar; more than six actions would not fit one row on a phone, so they split over two. */
@Composable
internal fun ActionBar(actions: List<BarItem>) {
    val rows = if (actions.size > 6) actions.chunked((actions.size + 1) / 2) else listOf(actions)
    Column(Modifier.fillMaxWidth().background(LocalCb.current.surface1).padding(vertical = 4.dp)) {
        rows.forEach { row -> Row(Modifier.fillMaxWidth()) { row.forEach { BarAction(it.label, it.color, it.onClick) } } }
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
private fun NameDialog(title: String, initial: String, action: String, onCancel: () -> Unit, allowSame: Boolean = false, suffix: String = "", onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val name = text.trim()
    val valid = name.isNotEmpty() && '/' !in name && (allowSame || name != initial) // a zip may keep the offered name; a rename must change it
    CbDialog(title, onCancel) {
        CbTextField(text, { text = it }, suffix)
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
        add("MODIFIED" to dateText(file.lastModified()))
    }
    CbDialog("INFO", onClose) {
        rows.forEach { (k, v) ->
            Column {
                Text(k, style = monoStyle(DarkCb.inkDim, 1.6.sp))
                Crossfade(v, animationSpec = tween(MOTION_MS), label = "prop") { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = DarkCb.ink)) }
            }
        }
        Row(Modifier.fillMaxWidth(), Arrangement.End) { ActionText("CLOSE", DarkCb.mint, onClose) }
    }
}

private fun fileUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

internal fun openWithApp(context: Context, file: File) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(fileUri(context, file), file.mime().ifEmpty { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.startActivity(view) } catch (_: ActivityNotFoundException) { Toast.makeText(context, "No app can open this", Toast.LENGTH_SHORT).show() }
}

internal fun shareFiles(context: Context, files: List<File>) {
    val uris = ArrayList(files.map { fileUri(context, it) })
    val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(files[0].mime().ifEmpty { "*/*" })
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
    context.startActivity(Intent.createChooser(send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
}
