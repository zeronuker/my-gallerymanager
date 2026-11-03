package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.data.Recycle
import com.claudeborne.gallerymanager.data.RecycledItem
import com.claudeborne.gallerymanager.data.hasAllFilesAccess
import com.claudeborne.gallerymanager.data.pathsUnder
import com.claudeborne.gallerymanager.data.scanPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The TRASH tab: switch between the system photo trash and files deleted from the Files tab. */
@Composable
fun TrashHome(trash: List<com.claudeborne.gallerymanager.data.MediaItem>, onChanged: () -> Unit) {
    val cb = LocalCb.current
    var files by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Chip("PHOTOS & VIDEOS", on = !files) { files = false }
            Text("  ", style = mono(cb.inkDim))
            Chip("FILES", on = files) { files = true }
        }
        if (files) RecycleScreen(onChanged) else TrashScreen(trash, onChanged)
    }
}

@Composable
fun RecycleScreen(onChanged: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<RecycledItem>>(emptyList()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) { items = if (hasAllFilesAccess()) withContext(Dispatchers.IO) { Recycle.list() } else emptyList() }
    val chosen = items.filter { it.stored.name in selected }

    suspend fun act(block: () -> Unit) {
        withContext(Dispatchers.IO) { runCatching(block).onFailure { message = it.message ?: "Failed" } }
        selected = emptySet(); reload++; onChanged()
    }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("FILE TRASH", if (chosen.isEmpty()) "${items.size} ITEMS" else "${chosen.size} SELECTED")
        if (items.isEmpty()) { EmptyNote("FILE TRASH IS EMPTY"); return@Column }
        Text("Deleted files are removed for good after 30 days.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim))
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.stored.name }) { it ->
                val sel = it.stored.name in selected
                val daysLeft = (30 - (System.currentTimeMillis() - it.deletedMs) / 86_400_000L).coerceAtLeast(0)
                Column(
                    Modifier.fillMaxWidth().then(if (sel) Modifier.background(cb.surface2) else Modifier)
                        .clickable { selected = if (sel) selected - it.stored.name else selected + it.stored.name }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text((if (sel) "✓ " else "") + it.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = cb.ink))
                    Text("${it.originalPath.substringBeforeLast('/')}  ·  ${daysLeft}D LEFT", maxLines = 1, overflow = TextOverflow.Ellipsis, style = mono(cb.inkDim, 9, 0.4f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(cb.surface1).horizontalScroll(rememberScrollState())) {
            ActionLabel("ALL", cb.inkDim) { selected = items.map { it.stored.name }.toSet() }
            ActionLabel("RESTORE", cb.mint, enabled = chosen.isNotEmpty()) {
                scope.launch { act { val paths = chosen.map { Recycle.restore(it) }; scanPaths(context, pathsUnder(paths)) } }
            }
            ActionLabel("DELETE", DangerRed, enabled = chosen.isNotEmpty()) {
                scope.launch { act { chosen.forEach { Recycle.deleteForever(it) } } }
            }
            ActionLabel("EMPTY ALL", DangerRed) { confirmEmpty = true }
        }
    }
    if (confirmEmpty) CbDialog("EMPTY FILE TRASH", { confirmEmpty = false }, "EMPTY", danger = true, onConfirm = {
        confirmEmpty = false
        scope.launch { act { Recycle.emptyAll() } }
    }) { DialogText("Permanently delete everything in the file trash? This cannot be undone.") }
    message?.let { CbDialog("PROBLEM", { message = null }, "") { DialogText(it) } }
}

