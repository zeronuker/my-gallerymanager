package com.claudeborne.gallerymanager.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.claudeborne.gallerymanager.data.FileEntry
import com.claudeborne.gallerymanager.data.Kind
import com.claudeborne.gallerymanager.data.Recycle
import com.claudeborne.gallerymanager.data.ScanResult
import com.claudeborne.gallerymanager.data.VaultItem
import com.claudeborne.gallerymanager.data.VaultSession
import com.claudeborne.gallerymanager.data.findDuplicates
import com.claudeborne.gallerymanager.data.hasAllFilesAccess
import com.claudeborne.gallerymanager.data.openExternally
import com.claudeborne.gallerymanager.data.pathsUnder
import com.claudeborne.gallerymanager.data.scanPaths
import com.claudeborne.gallerymanager.data.scanStorage
import com.claudeborne.gallerymanager.data.storageRoot
import com.claudeborne.gallerymanager.data.vaultClearTemp
import com.claudeborne.gallerymanager.data.vaultDelete
import com.claudeborne.gallerymanager.data.vaultExport
import com.claudeborne.gallerymanager.data.vaultList
import com.claudeborne.gallerymanager.data.vaultOpenTemp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ToolPage { HOME, LARGE, RECENT, DUPES, CLEANUP, VAULT }

/** Lives in MainScaffold so the (slow) storage scan survives tab switches. */
class ToolsState {
    var page by mutableStateOf(ToolPage.HOME)
    var scan by mutableStateOf<ScanResult?>(null)
    var scanning by mutableStateOf(false)
    var seen by mutableIntStateOf(0)
    var dupes by mutableStateOf<List<List<File>>?>(null)
    var dupeProgress by mutableStateOf("")

    fun startScan(scope: CoroutineScope) {
        if (scanning) return
        scope.launch {
            scanning = true; seen = 0; dupes = null
            scan = scanStorage(storageRoot) { seen = it }
            scanning = false
        }
    }
}

private val kindColors = mapOf(
    Kind.IMAGE to Color(0xFF3FE0C5), Kind.VIDEO to Color(0xFF3B8DFF), Kind.AUDIO to Color(0xFF5B6BFF),
    Kind.DOC to Color(0xFFFFC857), Kind.ARCHIVE to Color(0xFFFF9F68), Kind.APK to Color(0xFFFF6B8B), Kind.OTHER to Color(0xFF7C87A3),
)
private val kindNames = mapOf(
    Kind.IMAGE to "IMAGES", Kind.VIDEO to "VIDEOS", Kind.AUDIO to "AUDIO", Kind.DOC to "DOCUMENTS",
    Kind.ARCHIVE to "ARCHIVES", Kind.APK to "APPS (APK)", Kind.OTHER to "OTHER",
)

/** Moves [files] to the file trash and tells the gallery. */
private suspend fun recycle(context: Context, files: List<File>) = withContext(Dispatchers.IO) {
    val paths = pathsUnder(files)
    files.forEach { runCatching { Recycle.put(it) } }
    scanPaths(context, paths)
}

@Composable
fun ToolsScreen(state: ToolsState, scope: CoroutineScope, onChanged: () -> Unit) {
    var access by remember { mutableStateOf(hasAllFilesAccess()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = hasAllFilesAccess() }
    if (!access) { AccessGate(); return }

    LaunchedEffect(Unit) { if (state.scan == null) state.startScan(scope) }
    BackHandler(enabled = state.page != ToolPage.HOME) { state.page = ToolPage.HOME }

    val scan = state.scan
    when (state.page) {
        ToolPage.HOME -> ToolsHome(state, scope)
        ToolPage.LARGE -> FileListPage("LARGE FILES", scan?.largest.orEmpty(), state, scope, onChanged)
        ToolPage.RECENT -> FileListPage("RECENT FILES", scan?.recent.orEmpty(), state, scope, onChanged)
        ToolPage.DUPES -> DuplicatesPage(state, scope, onChanged)
        ToolPage.CLEANUP -> CleanupPage(state, scope, onChanged)
        ToolPage.VAULT -> VaultPage(state, onChanged)
    }
}

@Composable
private fun ToolsHome(state: ToolsState, scope: CoroutineScope) {
    val cb = LocalCb.current
    val scan = state.scan
    val stat = remember(state.scan) { runCatching { StatFs(storageRoot.path) }.getOrNull() }
    val total = stat?.totalBytes ?: 0L
    val free = stat?.availableBytes ?: 0L

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("TOOLS", if (state.scanning) "SCANNING…" else "STORAGE")
        LazyColumn(Modifier.weight(1f)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("${sizeText(total - free)} used of ${sizeText(total)}", style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = cb.ink))
                    Text("${sizeText(free)} FREE", style = mono(cb.mint, 11, 2f))
                    // stacked bar of what fills the storage, one segment per file type
                    Row(Modifier.padding(top = 10.dp).fillMaxWidth().height(10.dp).background(cb.surface3)) {
                        scan?.bytesByKind?.entries?.sortedByDescending { it.value }?.forEach { (k, b) ->
                            if (total > 0 && b > 0) Box(Modifier.weight((b.toFloat() / total).coerceAtLeast(0.002f)).height(10.dp).background(kindColors[k] ?: cb.ink2))
                        }
                        Box(Modifier.weight((free.toFloat() / total.coerceAtLeast(1)).coerceAtLeast(0.001f)).height(10.dp))
                    }
                    if (state.scanning) Text("Scanning… ${state.seen} files so far", Modifier.padding(top = 8.dp), style = mono(cb.inkDim, 10, 1f))
                    scan?.let { r ->
                        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            r.bytesByKind.entries.sortedByDescending { it.value }.forEach { (k, b) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.height(10.dp).weight(0.05f).background(kindColors[k] ?: cb.ink2))
                                    Text(kindNames[k] ?: "", Modifier.weight(0.5f).padding(start = 8.dp), style = mono(cb.ink2, 10, 1.6f))
                                    Text("${r.countByKind[k] ?: 0} files", Modifier.weight(0.25f), style = mono(cb.inkDim, 10, 0.5f))
                                    Text(sizeText(b), style = mono(cb.ink, 10, 0.5f))
                                }
                            }
                        }
                    }
                    ActionLabel(if (state.scanning) "SCANNING…" else "RESCAN", cb.mint, enabled = !state.scanning) { state.startScan(scope) }
                }
            }
            val r = scan
            toolRow("LARGE FILES", "The 100 biggest files taking up space", r != null) { state.page = ToolPage.LARGE }
            toolRow("RECENT FILES", "The latest 100 files added or changed", r != null) { state.page = ToolPage.RECENT }
            toolRow("DUPLICATES", "Identical files wasting space", r != null) { state.page = ToolPage.DUPES }
            toolRow("CLEANUP", if (r == null) "Junk files and empty folders" else "${r.junk.size} junk files · ${r.emptyDirs.size} empty folders", r != null) { state.page = ToolPage.CLEANUP }
            toolRow("PRIVATE VAULT", "Encrypted files, locked with your screen lock", true) { state.page = ToolPage.VAULT }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.toolRow(title: String, sub: String, enabled: Boolean, onClick: () -> Unit) {
    item {
        val cb = LocalCb
        Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(title, style = mono(if (enabled) cb.current.mint else cb.current.inkDim, 11, 2.6f))
            Text(sub, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.current.ink2))
        }
    }
}

@Composable
private fun PageHeader(title: String, count: String, state: ToolsState) {
    ScreenHeader(title, count, onBack = { state.page = ToolPage.HOME }, backLabel = "TOOLS")
}

/** Shared list for Large files / Recent files: tap opens, long-press-free checkboxes via tap on the left tick. */
@Composable
private fun FileListPage(title: String, entries: List<FileEntry>, state: ToolsState, scope: CoroutineScope, onChanged: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirm by remember { mutableStateOf(false) }
    val chosen = entries.filter { it.file.absolutePath in selected }.map { it.file }

    Column(Modifier.fillMaxSize()) {
        PageHeader(title, if (chosen.isEmpty()) "${entries.size} FILES" else "${chosen.size} SELECTED · ${sizeText(entries.filter { it.file in chosen }.sumOf { it.size })}", state)
        if (entries.isEmpty()) EmptyNote("NOTHING FOUND")
        LazyColumn(Modifier.weight(1f)) {
            items(entries, key = { it.file.absolutePath }) { e ->
                val sel = e.file.absolutePath in selected
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (sel) "☑" else "☐",
                        Modifier.clickable { selected = if (sel) selected - e.file.absolutePath else selected + e.file.absolutePath }.padding(10.dp),
                        style = TextStyle(fontSize = 20.sp, color = if (sel) cb.mint else cb.inkDim),
                    )
                    Column(Modifier.weight(1f).clickable { openExternally(context, e.file) }.padding(vertical = 8.dp)) {
                        Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = cb.ink))
                        Text("${sizeText(e.size)} · ${dateText(e.modifiedMs)}", style = mono(cb.inkDim, 10, 0.6f))
                        Text(e.file.parent.orEmpty().removePrefix(storageRoot.path), maxLines = 1, overflow = TextOverflow.Ellipsis, style = mono(cb.inkDim, 9, 0.4f))
                    }
                }
            }
        }
        if (chosen.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(cb.surface1), horizontalArrangement = Arrangement.SpaceEvenly) {
                ActionLabel("CLEAR", cb.inkDim) { selected = emptySet() }
                ActionLabel("DELETE", DangerRed) { confirm = true }
            }
        }
    }
    if (confirm) CbDialog("DELETE", { confirm = false }, "DELETE", danger = true, onConfirm = {
        confirm = false
        scope.launch { recycle(context, chosen); selected = emptySet(); state.startScan(scope); onChanged() }
    }) { DialogText("Move ${chosen.size} file${if (chosen.size == 1) "" else "s"} to the file trash? You can restore them for 30 days.") }
}

@Composable
private fun DuplicatesPage(state: ToolsState, scope: CoroutineScope, onChanged: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirm by remember { mutableStateOf(false) }
    val groups = state.dupes

    LaunchedEffect(state.scan) {
        val scan = state.scan ?: return@LaunchedEffect
        if (state.dupes == null) {
            state.dupes = findDuplicates(scan.sameSize) { d, t -> state.dupeProgress = "Comparing $d of $t files…" }
        }
    }
    val extras = groups.orEmpty().flatMap { it.drop(1) } // keep the newest of each group, offer the rest
    val chosen = extras.filter { it.absolutePath in selected }
    val wasted = extras.sumOf { it.length() }

    Column(Modifier.fillMaxSize()) {
        PageHeader("DUPLICATES", if (groups == null) "SCANNING…" else "${sizeText(wasted)} WASTED", state)
        if (groups == null) {
            EmptyNote(state.dupeProgress.ifEmpty { "SCANNING…" }.uppercase())
        } else if (groups.isEmpty()) {
            EmptyNote("NO DUPLICATES FOUND")
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(groups, key = { it[0].absolutePath }) { g ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("${g.size} COPIES · ${sizeText(g[0].length())} EACH", style = mono(cb.mint, 10, 1.8f))
                        g.forEachIndexed { i, f ->
                            val sel = f.absolutePath in selected
                            Row(Modifier.fillMaxWidth().clickable(enabled = i > 0) { selected = if (sel) selected - f.absolutePath else selected + f.absolutePath }, verticalAlignment = Alignment.CenterVertically) {
                                Text(if (i == 0) "KEEP" else if (sel) "☑" else "☐", Modifier.padding(end = 8.dp, top = 6.dp, bottom = 6.dp), style = if (i == 0) mono(cb.inkDim, 9, 1f) else TextStyle(fontSize = 18.sp, color = if (sel) cb.mint else cb.inkDim))
                                Column(Modifier.weight(1f)) {
                                    Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink))
                                    Text(f.parent.orEmpty().removePrefix(storageRoot.path), maxLines = 1, overflow = TextOverflow.Ellipsis, style = mono(cb.inkDim, 9, 0.4f))
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(cb.surface1), horizontalArrangement = Arrangement.SpaceEvenly) {
                ActionLabel("SELECT ALL EXTRAS", cb.mint) { selected = extras.map { it.absolutePath }.toSet() }
                ActionLabel("DELETE ${chosen.size}", DangerRed, enabled = chosen.isNotEmpty()) { confirm = true }
            }
        }
    }
    if (confirm) CbDialog("DELETE DUPLICATES", { confirm = false }, "DELETE", danger = true, onConfirm = {
        confirm = false
        scope.launch { recycle(context, chosen); selected = emptySet(); state.startScan(scope); onChanged() }
    }) { DialogText("Move ${chosen.size} duplicate file${if (chosen.size == 1) "" else "s"} (${sizeText(chosen.sumOf { it.length() })}) to the file trash? One copy of each stays where it is.") }
}

@Composable
private fun CleanupPage(state: ToolsState, scope: CoroutineScope, onChanged: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val scan = state.scan
    var confirm by remember { mutableStateOf(false) }
    val junk = scan?.junk.orEmpty()
    val dirs = scan?.emptyDirs.orEmpty()

    Column(Modifier.fillMaxSize()) {
        PageHeader("CLEANUP", "${sizeText(junk.sumOf { it.size })} JUNK", state)
        Text("Temporary, log and empty files, thumbnail caches, and empty folders. Everything goes to the file trash first, so you can undo.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim))
        if (junk.isEmpty() && dirs.isEmpty()) EmptyNote("ALL CLEAN")
        LazyColumn(Modifier.weight(1f)) {
            if (dirs.isNotEmpty()) item { Text("${dirs.size} EMPTY FOLDERS", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = mono(cb.mint, 10, 2f)) }
            if (junk.isNotEmpty()) item { Text("${junk.size} JUNK FILES", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = mono(cb.mint, 10, 2f)) }
            items(junk.take(300), key = { it.file.absolutePath }) { e ->
                Column(Modifier.fillMaxWidth().clickable { openExternally(context, e.file) }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink))
                    Text("${sizeText(e.size)} · ${e.file.parent.orEmpty().removePrefix(storageRoot.path)}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = mono(cb.inkDim, 9, 0.4f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(cb.surface1), horizontalArrangement = Arrangement.Center) {
            ActionLabel("CLEAN EVERYTHING", DangerRed, enabled = junk.isNotEmpty() || dirs.isNotEmpty()) { confirm = true }
        }
    }
    if (confirm) CbDialog("CLEANUP", { confirm = false }, "CLEAN", danger = true, onConfirm = {
        confirm = false
        scope.launch {
            recycle(context, junk.map { it.file })
            withContext(Dispatchers.IO) { dirs.forEach { runCatching { if (it.list()?.isEmpty() == true) it.delete() } } }
            state.startScan(scope); onChanged()
        }
    }) { DialogText("Move ${junk.size} junk files to the file trash and delete ${dirs.size} empty folders?") }
}

// ---------------- vault ----------------

@Composable
private fun VaultPage(state: ToolsState, onChanged: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var unlocked by remember { mutableStateOf(VaultSession.unlocked) }
    var items by remember { mutableStateOf<List<VaultItem>>(emptyList()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    val unlockLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) { VaultSession.unlocked = true; unlocked = true }
    }
    // lock again as soon as the app leaves the screen, and wipe any decrypted temp copies
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { VaultSession.unlocked = false; unlocked = false; vaultClearTemp(context) }
    LaunchedEffect(unlocked, reload) { if (unlocked) items = withContext(Dispatchers.IO) { vaultList(context) } }
    val chosen = items.filter { it.id in selected }

    Column(Modifier.fillMaxSize()) {
        PageHeader("VAULT", if (unlocked) "${items.size} FILES" else "LOCKED", state)
        if (!unlocked) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (km.isDeviceSecure) "Files in the vault are encrypted and can only be opened after you confirm your fingerprint, PIN or pattern."
                    else "Set a screen lock (PIN, pattern or fingerprint) in Android Settings first. The vault uses it to stay locked.",
                    style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                )
                if (km.isDeviceSecure) Text(
                    "UNLOCK",
                    Modifier.background(cb.grad).clickable {
                        km.createConfirmDeviceCredentialIntent("Private vault", "Confirm it is you")?.let { unlockLauncher.launch(it) }
                    }.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = mono(DarkCb.surface0, 11, 2.2f, bold = true),
                )
            }
        } else {
            if (items.isEmpty()) EmptyNote("VAULT IS EMPTY")
            Text("Add files with VAULT in the Files tab or when selecting photos.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim))
            LazyColumn(Modifier.weight(1f)) {
                items(items, key = { it.id }) { v ->
                    val sel = v.id in selected
                    Column(
                        Modifier.fillMaxWidth().then(if (sel) Modifier.background(cb.surface2) else Modifier)
                            .clickable { selected = if (sel) selected - v.id else selected + v.id }.padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text((if (sel) "✓ " else "") + v.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = cb.ink))
                        Text("${sizeText(v.size)} · ${dateText(v.addedMs)}", style = mono(cb.inkDim, 10, 0.6f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(cb.surface1).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
                ActionLabel("OPEN", cb.mint, enabled = chosen.size == 1) {
                    scope.launch {
                        val f = withContext(Dispatchers.IO) { runCatching { vaultOpenTemp(context, chosen[0]) }.getOrNull() }
                        if (f != null) openExternally(context, f) else message = "Could not open that file"
                    }
                }
                ActionLabel("RESTORE", cb.mint, enabled = chosen.isNotEmpty()) {
                    scope.launch {
                        val dest = File(storageRoot, "Download/Vault restored")
                        val out = withContext(Dispatchers.IO) {
                            chosen.mapNotNull { v -> runCatching { vaultExport(context, v, dest).also { vaultDelete(context, v) } }.getOrNull() }
                        }
                        scanPaths(context, out.map { it.absolutePath })
                        message = "${out.size} file(s) restored to Download/Vault restored"
                        selected = emptySet(); reload++; onChanged()
                    }
                }
                ActionLabel("DELETE", DangerRed, enabled = chosen.isNotEmpty()) { confirmDelete = true }
                ActionLabel("LOCK", cb.inkDim) { VaultSession.unlocked = false; unlocked = false; vaultClearTemp(context) }
            }
        }
    }
    if (confirmDelete) CbDialog("DELETE FROM VAULT", { confirmDelete = false }, "DELETE", danger = true, onConfirm = {
        confirmDelete = false
        scope.launch { withContext(Dispatchers.IO) { chosen.forEach { vaultDelete(context, it) } }; selected = emptySet(); reload++ }
    }) { DialogText("Permanently delete ${chosen.size} file(s) from the vault? This cannot be undone.") }
    message?.let { m -> CbDialog("VAULT", { message = null }, "") { DialogText(m) } }
}
