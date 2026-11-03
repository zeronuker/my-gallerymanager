package com.claudeborne.gallerymanager.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.claudeborne.gallerymanager.data.EditOps
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.data.applyEdits
import com.claudeborne.gallerymanager.data.decodeForEdit
import com.claudeborne.gallerymanager.data.saveEdited
import kotlinx.coroutines.launch

private val ratios = listOf("FREE" to null, "1:1" to 1f, "4:3" to 4f / 3, "3:4" to 3f / 4, "16:9" to 16f / 9, "9:16" to 9f / 16)

/** Simple photo editor: rotate, mirror and centre-crop, saved as a new copy. */
@Composable
fun EditScreen(item: MediaItem, onClose: () -> Unit, onSaved: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ops by remember { mutableStateOf(EditOps()) }
    var saving by remember { mutableStateOf(false) }
    val preview by produceState<android.graphics.Bitmap?>(null, item.uri) { value = decodeForEdit(context, item.uri, 1600) }
    val shown = remember(preview, ops) { preview?.let { applyEdits(it, ops) } }
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("← CANCEL", Modifier.weight(1f).androidxClickable(onClose), style = mono(DarkCb.mint, 11, 2.6f))
            Text(if (saving) "SAVING…" else "EDIT", style = mono(Color.White, 11, 2f))
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (shown != null) Image(shown.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text("LOADING…", style = mono(DarkCb.inkDim, 11, 2f))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ratios.forEach { (label, r) -> Chip(label, on = ops.ratio == r) { ops = ops.copy(ratio = r) } }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF0A1020)), horizontalArrangement = Arrangement.SpaceEvenly) {
            ActionLabel("⟲ LEFT", DarkCb.mint) { ops = ops.copy(rotation = (ops.rotation + 270) % 360) }
            ActionLabel("RIGHT ⟳", DarkCb.mint) { ops = ops.copy(rotation = (ops.rotation + 90) % 360) }
            ActionLabel("MIRROR", DarkCb.mint) { ops = ops.copy(flip = !ops.flip) }
            ActionLabel("SAVE COPY", DarkCb.mint, enabled = !saving && preview != null) {
                saving = true
                scope.launch {
                    val ok = saveEdited(context, item, ops)
                    saving = false
                    Toast.makeText(context, if (ok) "Saved to Pictures/Edited" else "Could not save", Toast.LENGTH_SHORT).show()
                    if (ok) onSaved()
                }
            }
        }
    }
}

private fun Modifier.androidxClickable(onClick: () -> Unit) = this.then(Modifier.clickable(onClick = onClick))
