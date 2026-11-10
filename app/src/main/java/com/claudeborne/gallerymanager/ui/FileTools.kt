package com.claudeborne.gallerymanager.ui

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.data.copyItems
import com.claudeborne.gallerymanager.data.moveItems
import com.claudeborne.gallerymanager.data.renameItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal sealed interface ToolDialog {
    class Rename(val item: MediaItem) : ToolDialog
    class Folder(val items: List<MediaItem>, val move: Boolean, val skipped: Int, val onDone: () -> Unit) : ToolDialog
    class AlbumName(val items: List<MediaItem>) : ToolDialog // the items of one album, which is renamed by moving them to a sibling folder
}

/** Rename, move and copy flows shared by the viewer and the grid. Render [FileToolDialogs] once near the root. */
@Stable
class FileTools internal constructor(
    private val context: Context,
    private val library: () -> List<MediaItem>,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
) {
    internal var dialog by mutableStateOf<ToolDialog?>(null)
    internal var afterWrite: (suspend () -> Unit)? = null
    internal var requestWrite: (List<Uri>) -> Unit = {}

    fun rename(item: MediaItem) { dialog = ToolDialog.Rename(item) }
    fun renameAlbum(items: List<MediaItem>) { if (items.isNotEmpty()) dialog = ToolDialog.AlbumName(items) }
    /** [onDone] runs once the files were actually moved or copied (not when the user backs out). */
    fun move(selection: List<MediaItem>, onDone: () -> Unit = {}) = pickFolder(selection, move = true, onDone)
    fun copy(selection: List<MediaItem>, onDone: () -> Unit = {}) = pickFolder(selection, move = false, onDone)

    private fun pickFolder(selection: List<MediaItem>, move: Boolean, onDone: () -> Unit) {
        if (selection.isNotEmpty()) dialog = ToolDialog.Folder(selection, move, 0, onDone)
    }

    /** Folders that already hold photos or videos, as (relative path, display name). */
    internal fun folders() = library().filter { it.path.isNotEmpty() }.distinctBy { it.path }
        .map { it.path to it.bucketName.ifEmpty { it.path } }.sortedBy { it.second.lowercase() }

    internal fun doRename(item: MediaItem, baseName: String) {
        dialog = null
        write(listOf(item.uri)) { renameItem(context, item, baseName); toast("Renamed") }
    }

    internal fun doRenameAlbum(items: List<MediaItem>, name: String) {
        dialog = null
        val parent = items.first().path.trimEnd('/').substringBeforeLast('/', "")
        val target = if (parent.isEmpty()) "$name/" else "$parent/$name/"
        write(items.map { it.uri }) { moveItems(context, items, target); toast("Renamed") }
    }

    internal fun doFolder(d: ToolDialog.Folder, folder: String) {
        dialog = null
        val note = if (d.skipped > 0) " (${d.skipped} videos skipped)" else ""
        if (d.move) write(d.items.map { it.uri }) { moveItems(context, d.items, folder); toast("Moved ${d.items.size}$note"); d.onDone() }
        else scope.launch { toast("Copied ${copyItems(context, d.items, folder)}$note"); d.onDone(); onChanged() }
    }

    // changing files other apps own needs the user's OK first; the action runs once Android reports it was given
    internal fun write(uris: List<Uri>, then: suspend () -> Unit) { afterWrite = then; requestWrite(uris) }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/** [library] feeds the folder list; [onChanged] fires after files were renamed, moved or copied. */
@Composable
fun rememberFileTools(library: List<MediaItem>, onChanged: () -> Unit): FileTools {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestLibrary by rememberUpdatedState(library)
    val latestChanged by rememberUpdatedState(onChanged)
    val tools = remember { FileTools(context, { latestLibrary }, scope) { latestChanged() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val then = tools.afterWrite
        tools.afterWrite = null
        if (result.resultCode == Activity.RESULT_OK && then != null) scope.launch { then(); latestChanged() }
    }
    tools.requestWrite = { uris ->
        launcher.launch(IntentSenderRequest.Builder(MediaStore.createWriteRequest(context.contentResolver, uris).intentSender).build())
    }
    return tools
}

@Composable
fun FileToolDialogs(tools: FileTools) {
    when (val d = tools.dialog) {
        is ToolDialog.Rename -> RenameDialog(d.item.name.substringBeforeLast('.'), d.item.name.substringAfterLast('.', ""), { tools.doRename(d.item, it) }) { tools.dialog = null }
        is ToolDialog.AlbumName -> RenameDialog(d.items.first().bucketName, "", { tools.doRenameAlbum(d.items, it) }) { tools.dialog = null }
        is ToolDialog.Folder -> FolderDialog(d, tools.folders(), { tools.doFolder(d, it) }) { tools.dialog = null }
        null -> Unit
    }
}

/** Brand dialog: chamfered double-border card over a dimmed screen. */
@Composable
internal fun CbDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 24.dp).fillMaxWidth().doubleChamferFrame(DarkCb.mint, DarkCb.surface1).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = monoStyle(DarkCb.mint, 2.6.sp))
            content()
        }
    }
}

@Composable
internal fun CbTextField(value: String, onChange: (String) -> Unit, suffix: String = "") {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().background(DarkCb.surface2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value, onChange, Modifier.weight(1f).focusRequester(focus), singleLine = true,
            textStyle = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = DarkCb.ink), cursorBrush = SolidColor(DarkCb.mint),
        )
        if (suffix.isNotEmpty()) Text(suffix, style = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = DarkCb.inkDim))
    }
}

@Composable
private fun RenameDialog(base: String, ext: String, onRename: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(base) }
    val name = text.trim()
    val valid = name.isNotEmpty() && '/' !in name && name != base
    CbDialog("RENAME", onCancel) {
        CbTextField(text, { text = it }, if (ext.isEmpty()) "" else ".$ext")
        Row(Modifier.fillMaxWidth(), Arrangement.End) {
            ActionText("CANCEL", DarkCb.inkDim, onCancel)
            ActionText("RENAME", if (valid) DarkCb.mint else DarkCb.inkDim) { if (valid) onRename(name) }
        }
    }
}

@Composable
private fun FolderDialog(d: ToolDialog.Folder, folders: List<Pair<String, String>>, onPick: (String) -> Unit, onCancel: () -> Unit) {
    var naming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val here = d.items.map { it.path }.toSet().singleOrNull() // moving into the folder they are already in does nothing
    val count = if (d.items.size > 1) "${d.items.size} " else ""
    CbDialog("${if (d.move) "MOVE" else "COPY"} $count" + "TO", onCancel) {
        if (naming) {
            val name = newName.trim()
            val valid = name.isNotEmpty() && '/' !in name
            CbTextField(newName, { newName = it })
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                ActionText("BACK", DarkCb.inkDim) { naming = false }
                ActionText("CREATE", if (valid) DarkCb.mint else DarkCb.inkDim) { if (valid) onPick("Pictures/$name/") }
            }
        } else {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                item { FolderRow("NEW FOLDER…", "Pictures/", DarkCb.mint) { naming = true } }
                items(folders.filter { !d.move || it.first != here }, key = { it.first }) { (path, label) ->
                    FolderRow(label, path, DarkCb.ink) { onPick(path) }
                }
            }
            Row(Modifier.fillMaxWidth(), Arrangement.End) { ActionText("CANCEL", DarkCb.inkDim, onCancel) }
        }
    }
}

@Composable
private fun FolderRow(label: String, path: String, color: Color, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = color))
        Text(path, style = monoStyle(DarkCb.inkDim, 1.sp))
    }
}
