package com.claudeborne.gallerymanager.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val FadeChoices = listOf("NONE" to 0L, "0.5S" to 500L, "1S" to 1000L, "2S" to 2000L)

/** Two rows of choices: how long the video fades in from black at the start, and out to black at the end. */
@Composable
internal fun FadeChips(inMs: Long, outMs: Long, onChange: (Long, Long) -> Unit) {
    Text("FADE IN", Modifier.padding(start = 16.dp), style = monoStyle(DarkCb.inkDim, 1.5.sp))
    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) { items(FadeChoices) { (label, ms) -> Chip(label, inMs == ms) { onChange(ms, outMs) } } }
    Text("FADE OUT", Modifier.padding(start = 16.dp), style = monoStyle(DarkCb.inkDim, 1.5.sp))
    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) { items(FadeChoices) { (label, ms) -> Chip(label, outMs == ms) { onChange(inMs, ms) } } }
}

/** Music choice: pick a sound file, see its name, or take it away, and set how loud it plays under the video. */
@Composable
internal fun MusicControls(name: String, hasMusic: Boolean, volume: Float, onPick: () -> Unit, onClear: () -> Unit, onVolume: (Float) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
        item { Chip(if (hasMusic) "♪ ${name.take(18)}" else "ADD MUSIC", hasMusic, color = DarkCb.ink, onClick = onPick) }
        if (hasMusic) item { Chip("REMOVE", color = Danger, onClick = onClear) }
    }
    if (hasMusic) EditSlider("MUSIC VOLUME", volume, 0f..1f, "${(volume * 100).roundToInt()}%", onVolume, {}) { onVolume(0.7f) }
}

/** The display name of the file behind [uri], without its extension. */
internal fun musicFileName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0).orEmpty().substringBeforeLast('.') else null
    }
}.getOrNull() ?: "music"
