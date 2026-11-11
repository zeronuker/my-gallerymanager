package com.claudeborne.gallerymanager.ui

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.data.Vault
import kotlinx.coroutines.launch
import java.io.File

private enum class Stage { NEW, LOCKED, RECOVER, OPEN }

/** A row of the vault listing: a folder inside the vault ([item] null) or a file. */
private class VaultRow(val name: String, val item: Vault.Item?)

/**
 * The safe folder. It asks for the fingerprint or PIN every time, then shows the encrypted files; [pendingAdd] are files the user chose to
 * move in, which go in as soon as the vault is open. Leaving this screen, or the app locking, locks the vault and wipes the decrypted copies.
 */
@Composable
internal fun VaultScreen(pendingAdd: List<File>, backEnabled: Boolean, onClose: () -> Unit, onChanged: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(if (Vault.exists) Stage.LOCKED else Stage.NEW) }
    var toAdd by remember { mutableStateOf(pendingAdd) }
    var busy by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf<String?>(null) } // the recovery key, shown once after creating
    var recoveryText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var folder by remember { mutableStateOf("") } // the folder being viewed inside the vault, "" or "Trips/"
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var erase by remember { mutableStateOf<List<Vault.Item>?>(null) }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    DisposableEffect(Unit) { onDispose { Vault.lock(context) } }
    LaunchedEffect(AppLockState.locked) { if (AppLockState.locked) onClose() } // the app locked: the vault locks with it

    /** Moves the files waiting in [toAdd] into the vault, now that it is open. */
    fun addPending() {
        if (toAdd.isEmpty()) return
        val files = toAdd
        toAdd = emptyList()
        scope.launch {
            busy = "ENCRYPTING…"
            val n = Vault.add(context, files)
            busy = null
            version++
            onChanged()
            toast("Moved $n files into the safe folder")
        }
    }

    fun opened() { stage = Stage.OPEN; version++; message = null; addPending() }

    fun askAndUnlock() {
        if (!canLock(context)) { message = "Set a screen lock (PIN, pattern or fingerprint) on this phone first."; return }
        authenticate(context, "Open the safe folder") { ok ->
            if (!ok) { message = "Not unlocked."; return@authenticate }
            scope.launch { if (Vault.unlock(context)) opened() else { stage = Stage.RECOVER; message = "This phone's key for the vault is gone. Enter the recovery key you wrote down." } }
        }
    }

    fun create() {
        if (!canLock(context)) { message = "Set a screen lock (PIN, pattern or fingerprint) on this phone first."; return }
        authenticate(context, "Create the safe folder") { ok ->
            if (ok) scope.launch { code = Vault.create(context); opened() } else message = "Not unlocked."
        }
    }

    // the fingerprint or PIN prompt comes up by itself when an existing vault is opened
    LaunchedEffect(Unit) { if (Vault.exists) askAndUnlock() }

    val all = remember(version, stage) { if (stage == Stage.OPEN) Vault.items() else emptyList() }
    // the rows of the folder being viewed: its sub-folders first, then its files
    val rows = remember(all, folder) {
        val inside = all.filter { it.name.startsWith(folder) }
        val folders = inside.filter { '/' in it.name.removePrefix(folder) }.map { it.name.removePrefix(folder).substringBefore('/') }.distinct().sorted().map { VaultRow(it, null) }
        val files = inside.filter { '/' !in it.name.removePrefix(folder) }.sortedBy { it.name.lowercase() }.map { VaultRow(it.name.removePrefix(folder), it) }
        folders + files
    }
    fun itemsIn(row: VaultRow): List<Vault.Item> = row.item?.let { listOf(it) } ?: all.filter { it.name.startsWith("$folder${row.name}/") }
    val chosen = rows.filter { it.name in selected }.flatMap(::itemsIn)

    BackHandler(enabled = backEnabled && code == null) {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            stage == Stage.OPEN && folder.isNotEmpty() -> folder = folder.dropLast(1).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            else -> onClose()
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "SAFE", busy ?: if (selected.isNotEmpty()) "${selected.size} SELECTED" else if (stage == Stage.OPEN) "${all.size} FILES" else "LOCKED",
            { if (stage == Stage.OPEN && folder.isNotEmpty()) folder = folder.dropLast(1).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } else onClose() },
            if (stage == Stage.OPEN && folder.isNotEmpty()) "UP" else "FILES",
        )
        AnimatedOverlay(busy, expandEnter, shrinkExit) { WorkingLine(it, null, cb.mint) } // encrypting and decrypting report no percentage, so the bar sweeps
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Crossfade(stage, Modifier.fillMaxSize(), animationSpec = tween(MOTION_MS), label = "vault") { shown -> when (shown) {
                Stage.NEW -> Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.spacedBy(14.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
                    Text(
                        "Files you move here are encrypted and hidden, and need your fingerprint or phone PIN to open. You also get a recovery key to write down once: it is the only way back in if you reinstall the app or change phone.",
                        style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2, textAlign = TextAlign.Center),
                    )
                    ActionText("CREATE SAFE FOLDER", cb.mint) { create() }
                    message?.let { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = RED, textAlign = TextAlign.Center)) }
                }
                Stage.LOCKED -> Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.spacedBy(14.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
                    Text("LOCKED", style = monoStyle(cb.mint, 3.sp))
                    ActionText("UNLOCK", cb.mint) { askAndUnlock() }
                    ActionText("USE THE RECOVERY KEY", cb.inkDim) { stage = Stage.RECOVER; message = null }
                    message?.let { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = RED, textAlign = TextAlign.Center)) }
                }
                Stage.RECOVER -> Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.spacedBy(14.dp)) {
                    Text(message ?: "Enter the recovery key you wrote down when you made the safe folder.", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2))
                    CbTextField(recoveryText, { recoveryText = it })
                    Row {
                        ActionText("BACK", cb.inkDim) { stage = Stage.LOCKED; message = null }
                        ActionText("UNLOCK", cb.mint) {
                            scope.launch { if (Vault.recover(context, recoveryText)) { recoveryText = ""; opened() } else message = "That recovery key does not open the vault." }
                        }
                    }
                }
                Stage.OPEN -> if (rows.isEmpty()) {
                    Text("THE SAFE FOLDER IS EMPTY", Modifier.align(Alignment.Center), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 2.sp, color = cb.inkDim))
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { it.name }) { r ->
                            val picked = r.name in selected
                            VaultRowView(
                                r, picked,
                                onClick = {
                                    when {
                                        selected.isNotEmpty() -> selected = if (picked) selected - r.name else selected + r.name
                                        r.item == null -> folder = "$folder${r.name}/"
                                        else -> scope.launch {
                                            busy = "DECRYPTING…"
                                            val copy = runCatching { Vault.open(context, r.item) }.getOrNull()
                                            busy = null
                                            if (copy != null) openWithApp(context, copy) else toast("Could not open this file")
                                        }
                                    }
                                },
                                onLong = { selected = if (picked) selected - r.name else selected + r.name },
                            )
                        }
                    }
                }
            } }
        }
        AnimatedVisibility(stage == Stage.OPEN && selected.isNotEmpty(), enter = barEnter, exit = barExit) {
            ActionBar(
                listOf(
                    BarItem(if (rows.all { it.name in selected }) "DESELECT ALL" else "ALL", cb.inkDim) { selected = if (rows.all { it.name in selected }) emptySet() else rows.map { it.name }.toSet() },
                    BarItem("RESTORE", cb.mint) {
                        val items = chosen
                        scope.launch {
                            busy = "RESTORING…"
                            val n = Vault.restore(context, items)
                            busy = null; selected = emptySet(); version++; onChanged()
                            toast("Restored $n files to where they came from")
                        }
                    },
                    BarItem("EXPORT", cb.mint) {
                        val items = chosen
                        scope.launch {
                            busy = "EXPORTING…"
                            val n = Vault.restore(context, items, Vault.exportDir)
                            busy = null; selected = emptySet(); version++; onChanged()
                            toast("Exported $n files to Download/Exported")
                        }
                    },
                    BarItem("DELETE", RED) { erase = chosen },
                ),
            )
        }
    }

    erase?.let { items ->
        CbDialog("DELETE FOR GOOD", { erase = null }) {
            Text("Delete ${items.size} ${if (items.size == 1) "file" else "files"} from the safe folder for good? This cannot be undone.", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2))
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                ActionText("CANCEL", cb.inkDim) { erase = null }
                ActionText("DELETE", RED) { erase = null; scope.launch { Vault.delete(items); selected = emptySet(); version++ } }
            }
        }
    }

    // the recovery key is shown once; the dialog cannot be dismissed any other way
    code?.let { c ->
        CbDialog("RECOVERY KEY", {}, outsideCloses = false) {
            Text("Write this down and keep it somewhere safe. It is shown only now, and it is the only way back in if the phone's key is lost (reinstall, new phone, lock screen change).", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.ink2))
            Text(c.split('-').chunked(4).joinToString("\n") { it.joinToString("-") }, Modifier.fillMaxWidth(), style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 1.sp, color = cb.mint, textAlign = TextAlign.Center))
            Row(Modifier.fillMaxWidth(), Arrangement.End) { ActionText("I WROTE IT DOWN", cb.mint) { code = null } }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultRowView(r: VaultRow, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(glide(selected, cb.surface2, Color.Transparent)).combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).background(cb.surface2).border(2.dp, glide(selected, cb.mint, Color.Transparent)), Alignment.Center) {
            Text(
                if (r.item == null) "DIR" else r.name.substringAfterLast('.', "").take(4).uppercase().ifEmpty { "FILE" }, maxLines = 1,
                style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp, color = if (r.item == null) cb.mint else cb.inkDim),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cb.ink))
            r.item?.let { Text("${Formatter.formatShortFileSize(context, it.size).uppercase()} · ${dateText(it.modified)}", maxLines = 1, style = monoStyle(cb.inkDim, 0.6.sp)) }
        }
    }
}
