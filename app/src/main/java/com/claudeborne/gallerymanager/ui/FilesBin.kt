package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.produceState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImage
import com.claudeborne.gallerymanager.data.isImage
import com.claudeborne.gallerymanager.data.isVideo
import com.claudeborne.gallerymanager.data.mime
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.data.BinItem
import com.claudeborne.gallerymanager.data.Entry
import com.claudeborne.gallerymanager.data.deleteFromBin
import com.claudeborne.gallerymanager.data.entryOf
import com.claudeborne.gallerymanager.data.listBin
import com.claudeborne.gallerymanager.data.restoreFromBin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One deleted file, big: a preview where there is one (picture, video frame, PDF first page, start of a text file) and its details, with restore and delete forever. */
@Composable
internal fun BinDetail(item: BinItem, entry: Entry, onClose: () -> Unit, onRestore: () -> Unit, onDelete: () -> Unit) {
    val cb = LocalCb.current
    val file = item.file
    Column(Modifier.fillMaxSize().background(cb.surface0).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("← BACK", Modifier.clickable(onClick = onClose), style = monoStyle(cb.mint, 2.6.sp))
        }
        Text(file.name, Modifier.padding(horizontal = 16.dp), maxLines = 2, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = cb.ink))
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp).background(Color.Black), Alignment.Center) {
            when {
                entry.isDir -> BigLabel("FOLDER")
                file.isImage() || file.isVideo() -> AsyncImage(file, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                file.extension.equals("pdf", true) -> PdfFirstPage(file, 1080, ContentScale.Fit)
                file.mime().startsWith("text/") -> {
                    val text by produceState<String?>(null, file) { value = withContext(Dispatchers.IO) { runCatching { file.bufferedReader().use { r -> CharArray(3000).let { b -> String(b, 0, r.read(b).coerceAtLeast(0)) } } }.getOrDefault("") } }
                    Crossfade(text, animationSpec = tween(MOTION_MS), label = "preview") { t ->
                        if (t == null) LoadingPane("LOADING")
                        else Text(t, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = Color.White))
                    }
                }
                else -> BigLabel(file.extension.take(5).uppercase().ifEmpty { "FILE" })
            }
        }
        Row(Modifier.fillMaxWidth().background(cb.surface1).padding(vertical = 8.dp), Arrangement.SpaceEvenly) {
            SvgIconButton("restore", cb.mint, "Restore", onClick = onRestore)
            SvgIconButton("deleteForever", RED, "Delete forever", onClick = onDelete) // asks first: the trash screen shows its confirm dialog
        }
    }
}

@Composable
private fun BigLabel(text: String) {
    Text(text, style = TextStyle(fontFamily = Tourney, fontWeight = FontWeight.Bold, fontSize = 48.sp, brush = LocalCb.current.grad))
}
