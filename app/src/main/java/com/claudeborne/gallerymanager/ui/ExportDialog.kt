package com.claudeborne.gallerymanager.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.data.readExif
import com.claudeborne.gallerymanager.edit.AI_INPUT_MAX
import com.claudeborne.gallerymanager.edit.MAX_EDGE
import com.claudeborne.gallerymanager.edit.aiSize
import com.claudeborne.gallerymanager.edit.OutFormat
import com.claudeborne.gallerymanager.edit.exportCopy
import com.claudeborne.gallerymanager.edit.scaledSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

private val Sizes = listOf("ORIGINAL" to 1f, "75%" to 0.75f, "50%" to 0.5f, "25%" to 0.25f, "2×" to 2f, "4×" to 4f)
private const val AI = -4f // the "AI 4×" choice, kept in the same slot as the plain sizes

/** Saves a resized, converted and/or location-free copy of [item]. [stripLocation] starts the location switch on. */
@Composable
fun ExportDialog(item: MediaItem, stripLocation: Boolean, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var format by remember { mutableStateOf(if (item.mime == "image/png") OutFormat.PNG else OutFormat.JPG) }
    var quality by remember { mutableFloatStateOf(90f) }
    var strip by remember { mutableStateOf(stripLocation) }
    var saving by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    val hasGps by produceState(false, item.id) { value = readExif(context, item).any { it.first == "GPS" } }

    val ai = scale == AI
    CbDialog(if (stripLocation) "REMOVE LOCATION" else "RESIZE · CONVERT", { if (!saving) onClose() }) {
        Text("SIZE", style = monoStyle(DarkCb.inkDim, 1.5.sp))
        LazyRow { items(Sizes + ("AI 4×" to AI)) { (label, s) -> Chip(label, scale == s) { if (!saving) scale = s } } }
        if (item.width > 0) {
            val (w, h) = if (ai) aiSize(item.width, item.height) else scaledSize(item.width, item.height, scale)
            val capped = scale > 1f && max(w, h) >= MAX_EDGE
            Text("${item.width} × ${item.height}  →  $w × $h" + if (capped) "  (MAX $MAX_EDGE)" else "", style = monoStyle(DarkCb.ink, 0.5.sp))
        }
        if (ai) Text("AI redraws fine detail, on your phone. Best on small or soft photos; large photos are first reduced to ${AI_INPUT_MAX}px. Takes a while.", style = monoStyle(DarkCb.inkDim, 0.2.sp))
        else if (scale > 1f) Text("Bigger, smoother and a little sharper. It does not add new detail.", style = monoStyle(DarkCb.inkDim, 0.2.sp))

        Text("FORMAT", style = monoStyle(DarkCb.inkDim, 1.5.sp))
        Row { OutFormat.entries.forEach { f -> Chip(f.label, format == f) { format = f } } }
        if (format != OutFormat.PNG) EditSlider("QUALITY", quality, 50f..100f, "${quality.roundToInt()}", { quality = it }, {}) { quality = 90f }

        if (hasGps) Chip(if (strip) "✓ LOCATION WILL BE REMOVED" else "REMOVE LOCATION", strip, color = DarkCb.ink) { strip = !strip }
        else Text("NO LOCATION IN THIS PHOTO", style = monoStyle(DarkCb.inkDim, 1.2.sp))

        Row(Modifier.fillMaxWidth(), Arrangement.End) {
            ActionText("CANCEL", DarkCb.inkDim) { if (saving) job?.cancel() else onClose() }
            ActionText(if (saving) (if (ai) "${(progress * 100).roundToInt()}%" else "SAVING…") else "SAVE COPY", DarkCb.mint) {
                if (!saving) {
                    saving = true
                    progress = 0f
                    job = scope.launch {
                        try {
                            val ok = exportCopy(context, item, if (ai) 1f else scale, format, quality.roundToInt(), strip && hasGps, ai) { progress = it }
                            Toast.makeText(context, if (ok) "Saved a copy" else "Couldn't save the copy", Toast.LENGTH_SHORT).show()
                            if (ok) { onSaved(); onClose() }
                        } finally {
                            saving = false
                        }
                    }
                }
            }
        }
    }
}
