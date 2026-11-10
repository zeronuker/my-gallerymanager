package com.claudeborne.gallerymanager.ui

import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.data.BIN_DAYS
import com.claudeborne.gallerymanager.data.Entry
import com.claudeborne.gallerymanager.data.SortBy
import com.claudeborne.gallerymanager.data.listDir
import com.claudeborne.gallerymanager.data.moveToBin
import com.claudeborne.gallerymanager.data.transfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One of the two folder views: where it is, what is selected in it, and what it lists. */
private class PaneState(path: String) {
    var path by mutableStateOf(path)
    var selected by mutableStateOf(emptySet<String>())
    var entries by mutableStateOf<List<Entry>>(emptyList())
}

private val paneSaver = listSaver<PaneState, Any>(
    save = { listOf(it.path, it.selected.toList()) },
    restore = { PaneState(it[0] as String).apply { @Suppress("UNCHECKED_CAST") (it[1] as List<String>).let { s -> selected = s.toSet() } } },
)

/**
 * Two folders at once, stacked in portrait and side by side in landscape. The pane touched last is the active one; COPY and MOVE send its
 * selection to the folder the other pane shows, so there is no paste step.
 */
@Composable
internal fun DualPaneScreen(start: File, roots: List<Pair<String, File>>, backEnabled: Boolean, onClose: () -> Unit, onChanged: () -> Unit, onOpen: (File) -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = rememberSaveable(saver = paneSaver) { PaneState(start.path) }
    val second = rememberSaveable(saver = paneSaver) { PaneState(start.path) }
    var active by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var erase by remember { mutableStateOf<List<File>?>(null) }
    val panes = listOf(first, second)
    val here = panes[active]
    val there = panes[1 - active]
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    panes.forEach { p -> LaunchedEffect(p.path, refresh) { p.entries = withContext(Dispatchers.IO) { listDir(File(p.path), false, SortBy.NAME, true) } } }

    fun rootOf(path: String) = roots.map { it.second }.filter { path.startsWith(it.path) }.maxByOrNull { it.path.length }
    fun up(p: PaneState) {
        val root = rootOf(p.path)
        if (root != null && p.path != root.path) { p.path = File(p.path).parent ?: p.path; p.selected = emptySet() }
    }
    fun activate(i: Int) { if (active != i) { panes[active].selected = emptySet(); active = i } } // a selection belongs to the pane it was made in

    val chosen = here.entries.filter { it.file.path in here.selected }.map { it.file }
    fun send(move: Boolean) {
        val files = chosen
        val target = File(there.path)
        scope.launch {
            busy = if (move) "MOVING…" else "COPYING…"
            val n = transfer(context, files, target, move)
            busy = null
            here.selected = emptySet()
            refresh++
            onChanged()
            toast("${if (move) "Moved" else "Copied"} $n of ${files.size}")
        }
    }

    BackHandler(enabled = backEnabled) {
        val root = rootOf(here.path)
        when {
            here.selected.isNotEmpty() -> here.selected = emptySet()
            root != null && here.path != root.path -> up(here)
            else -> onClose()
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("DUAL", busy ?: if (here.selected.isNotEmpty()) "${here.selected.size} SELECTED" else "2 PANES", onClose, "FILES")
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val pane: @Composable (Int, Modifier) -> Unit = { i, modifier ->
            Pane(
                panes[i], i == active, roots, modifier,
                onTap = { e ->
                    activate(i)
                    val p = panes[i]
                    when {
                        p.selected.isNotEmpty() -> p.selected = toggle(p.selected, e.file.path)
                        e.isDir -> { p.path = e.file.path; p.selected = emptySet() }
                        else -> onOpen(e.file)
                    }
                },
                onLong = { e -> activate(i); panes[i].selected = toggle(panes[i].selected, e.file.path) },
                onGo = { f -> activate(i); panes[i].path = f.path; panes[i].selected = emptySet() },
            )
        }
        if (landscape) Row(Modifier.weight(1f).fillMaxWidth(), Arrangement.spacedBy(6.dp)) { pane(0, Modifier.weight(1f)); pane(1, Modifier.weight(1f)) }
        else Column(Modifier.weight(1f).fillMaxWidth(), Arrangement.spacedBy(6.dp)) { pane(0, Modifier.weight(1f)); pane(1, Modifier.weight(1f)) }

        ActionBar(
            buildList {
                if (chosen.isNotEmpty()) {
                    add(BarItem("ALL", cb.inkDim) { here.selected = here.entries.map { it.file.path }.toSet() })
                    add(BarItem("COPY →", cb.mint) { send(move = false) })
                    add(BarItem("MOVE →", cb.mint) { send(move = true) })
                    add(BarItem("DELETE", RED) { erase = chosen })
                }
                add(BarItem("SWAP", cb.inkDim) {
                    val a = first.path
                    first.path = second.path
                    second.path = a
                    first.selected = emptySet(); second.selected = emptySet()
                })
            },
        )
    }

    erase?.let { files ->
        CbDialog("MOVE TO BIN", { erase = null }) {
            Text(
                "Move ${if (files.size == 1) files[0].name else "${files.size} items"} to the bin? You can restore ${if (files.size == 1) "it" else "them"} for $BIN_DAYS days.",
                style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2),
            )
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                ActionText("CANCEL", cb.inkDim) { erase = null }
                ActionText("MOVE TO BIN", RED) {
                    erase = null
                    scope.launch {
                        toast("Moved ${moveToBin(context, files)} to the bin")
                        here.selected = emptySet(); refresh++; onChanged()
                    }
                }
            }
        }
    }
}

@Composable
private fun Pane(p: PaneState, isActive: Boolean, roots: List<Pair<String, File>>, modifier: Modifier, onTap: (Entry) -> Unit, onLong: (Entry) -> Unit, onGo: (File) -> Unit) {
    val cb = LocalCb.current
    val dir = File(p.path)
    val root = roots.map { it.second }.filter { p.path.startsWith(it.path) }.maxByOrNull { it.path.length }
    Column(modifier.border(if (isActive) 2.dp else 1.dp, if (isActive) cb.mint else cb.line)) {
        if (root != null) Crumbs(root, roots.first { it.second.path == root.path }.first, dir, onGo)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (p.entries.isEmpty()) {
                Text("EMPTY FOLDER", Modifier.align(Alignment.Center), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.sp, color = cb.inkDim))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(p.entries, key = { it.file.path }) { e -> ListRow(e, e.file.path in p.selected, { onTap(e) }, { onLong(e) }) }
                }
            }
        }
    }
}
