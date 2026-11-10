package com.claudeborne.gallerymanager.ui

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.data.BIN_DAYS
import com.claudeborne.gallerymanager.data.BinItem
import com.claudeborne.gallerymanager.data.Entry
import com.claudeborne.gallerymanager.data.deleteFromBin
import com.claudeborne.gallerymanager.data.entryOf
import com.claudeborne.gallerymanager.data.listBin
import com.claudeborne.gallerymanager.data.restoreFromBin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What was deleted in the Files tab: restore it, or erase it for good. [onChanged] fires after a restore so the library follows. */
@Composable
internal fun BinView(backEnabled: Boolean, onBack: () -> Unit, onChanged: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<Pair<BinItem, Entry>>>(emptyList()) }
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var erase by remember { mutableStateOf<List<BinItem>?>(null) } // what a confirm dialog is about to erase

    LaunchedEffect(refresh) { rows = withContext(Dispatchers.IO) { listBin(context).map { it to entryOf(it.file) } } }
    val chosen = rows.map { it.first }.filter { it.id in selected }
    BackHandler(enabled = backEnabled) { if (chosen.isNotEmpty()) selected = emptySet() else onBack() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("BIN", if (chosen.isEmpty()) "${rows.size} ITEMS" else "${chosen.size} SELECTED", onBack, "FILES")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Items are removed for good after $BIN_DAYS days.", Modifier.weight(1f).padding(horizontal = 16.dp), style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim))
            if (rows.isNotEmpty()) ActionText("EMPTY BIN", RED) { erase = rows.map { it.first } }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                Text("THE BIN IS EMPTY", Modifier.align(Alignment.Center), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 2.sp, color = cb.inkDim))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.first.id }) { (item, entry) ->
                        val size = if (entry.isDir) "${entry.children} ITEMS" else Formatter.formatShortFileSize(context, entry.size).uppercase()
                        ListRow(
                            entry, item.id in selected, onClick = { selected = toggle(selected, item.id) }, onLongClick = { selected = toggle(selected, item.id) },
                            note = "$size · DELETED ${dateText(item.deletedMs)}", path = "FROM ${item.origin.parent.orEmpty()}",
                        )
                    }
                }
            }
        }
        if (chosen.isNotEmpty()) {
            ActionBar(
                listOf(
                    BarItem("ALL", cb.inkDim) { selected = rows.map { it.first.id }.toSet() },
                    BarItem("RESTORE", cb.mint) {
                        scope.launch {
                            Toast.makeText(context, "Restored ${restoreFromBin(context, chosen)}", Toast.LENGTH_SHORT).show()
                            selected = emptySet(); refresh++; onChanged()
                        }
                    },
                    BarItem("DELETE", RED) { erase = chosen },
                ),
            )
        }
    }

    erase?.let { items ->
        CbDialog("DELETE FOR GOOD", { erase = null }) {
            Text(
                "Erase ${if (items.size == 1) items[0].file.name else "${items.size} items"} for good? This cannot be undone.",
                style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2),
            )
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                ActionText("CANCEL", cb.inkDim) { erase = null }
                ActionText("DELETE", RED) {
                    erase = null
                    scope.launch { deleteFromBin(context, items); selected = emptySet(); refresh++ }
                }
            }
        }
    }
}
