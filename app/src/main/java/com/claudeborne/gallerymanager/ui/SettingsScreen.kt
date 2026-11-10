package com.claudeborne.gallerymanager.ui

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.data.Settings
import java.io.File

private val Licences = listOf(
    "Real-ESRGAN (upscaling model), BSD-3-Clause, by Xintao Wang et al.; Qualcomm AI Hub export",
    "Google ML Kit: face detection and subject segmentation",
    "LiteRT (TensorFlow Lite runtime), Apache-2.0",
    "Coil (image loading), Apache-2.0",
    "AndroidX Media3 (video), Apache-2.0",
    "AndroidX, Jetpack Compose, Biometric, ExifInterface, Print, Apache-2.0",
    "Fonts: Tourney, Inter, JetBrains Mono (SIL Open Font Licence)",
)

private fun folderSize(f: File): Long = if (f.isFile) f.length() else f.listFiles().orEmpty().sumOf { folderSize(it) }

/** App settings. Every change is applied and saved immediately. */
@Composable
fun SettingsScreen(favorites: Favorites, onBack: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    var cacheBytes by remember { mutableStateOf(folderSize(context.cacheDir)) }
    var dialog by remember { mutableIntStateOf(0) } // 1 collage folder, 2 clear favorites
    var showLicences by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("SETTINGS", "v$version", onBack, "BACK")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Section("LOOK") {
                Choice("THEME", listOf("PHONE", "DARK", "LIGHT"), Settings.theme) { Settings.update { theme = it } }
                Choice("GRID COLUMNS", (2..6).map { "$it" }, Settings.gridCols - 2) { Settings.update { gridCols = it + 2 } }
                Toggle("SPLASH SCREEN", "Play the logo animation when the app starts.", Settings.splash) { Settings.update { splash = it } }
            }
            Section("SHELF") {
                Choice("EDGE", listOf("LEFT", "RIGHT"), Settings.shelfEdge) { Settings.update { shelfEdge = it } }
                Text(
                    "The small bar on that edge opens Settings when you swipe it toward the middle of the screen.",
                    style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim),
                )
                Toggle("TAP TO OPEN", "Tapping the bar opens Settings too.", Settings.shelfTap) { Settings.update { shelfTap = it } }
            }
            Section("VIEWER") {
                Toggle("AUTOPLAY VIDEOS", "Start a video as soon as you open it.", Settings.autoplay) { Settings.update { autoplay = it } }
                Toggle("START MUTED", "Videos open with the sound off.", Settings.muted) { Settings.update { muted = it } }
            }
            Section("EDITING") {
                Choice("WHEN YOU SAVE", listOf("ASK", "COPY", "REPLACE"), Settings.saveMode) { Settings.update { saveMode = it } }
                Text(
                    "Replace only works for JPG and PNG photos; others are saved as a copy.",
                    style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim),
                )
                EditSlider("EDIT QUALITY", Settings.editQuality.toFloat(), 50f..100f, "${Settings.editQuality}", { Settings.update { editQuality = it.toInt() } }, {}) { Settings.update { editQuality = 95 } }
                EditSlider("EXPORT QUALITY", Settings.exportQuality.toFloat(), 50f..100f, "${Settings.exportQuality}", { Settings.update { exportQuality = it.toInt() } }, {}) { Settings.update { exportQuality = 90 } }
                Row(Modifier.fillMaxWidth().clickable { dialog = 1 }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("COLLAGE FOLDER", style = monoStyle(cb.inkDim, 1.5.sp))
                    Text("Pictures/${Settings.collageFolder}", style = monoStyle(cb.mint, 1.sp))
                }
            }
            Section("PRIVACY") {
                Toggle("REMOVE LOCATION WHEN SHARING", "Photos are sent as copies without GPS data. Videos are sent as they are.", Settings.stripOnShare) { Settings.update { stripOnShare = it } }
                Toggle("APP LOCK", "Ask for your fingerprint, face or phone PIN to open the app. Also hides it from screenshots and recent apps.", Settings.lock) { on ->
                    when {
                        !on -> Settings.update { lock = false }
                        !canLock(context) -> Toast.makeText(context, "Set a screen lock or fingerprint on your phone first", Toast.LENGTH_LONG).show()
                        else -> authenticate(context, "Turn on app lock") { ok -> if (ok) Settings.update { lock = true } } // prove it works before relying on it
                    }
                }
                if (Settings.lock) Choice("LOCK AFTER", listOf("AT ONCE", "1 MIN", "5 MIN"), Settings.lockAfter) { Settings.update { lockAfter = it } }
            }
            Section("DATA") {
                Row(Modifier.fillMaxWidth().clickable { dialog = 2 }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("CLEAR FAVORITES", style = monoStyle(Danger, 1.5.sp))
                    Text("${favorites.ids.size}", style = monoStyle(cb.inkDim, 1.sp))
                }
                Row(
                    Modifier.fillMaxWidth().clickable {
                        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                        cacheBytes = folderSize(context.cacheDir)
                        Toast.makeText(context, "Temporary files cleared", Toast.LENGTH_SHORT).show()
                    }.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("CLEAR TEMPORARY FILES", style = monoStyle(cb.mint, 1.5.sp))
                    Text(Formatter.formatShortFileSize(context, cacheBytes), style = monoStyle(cb.inkDim, 1.sp))
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("AI UPSCALE MODEL", style = monoStyle(cb.inkDim, 1.5.sp))
                    Text("4.9 MB, BUILT IN", style = monoStyle(cb.inkDim, 1.sp))
                }
            }
            Section("ABOUT") {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("Altus Cirrus", style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = cb.ink))
                    Text("v$version", style = monoStyle(cb.inkDim, 1.sp))
                }
                Text("Everything stays on your phone. Nothing is uploaded.", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim))
                Text(if (showLicences) "HIDE LICENCES" else "LICENCES", Modifier.clickable { showLicences = !showLicences }.padding(vertical = 6.dp), style = monoStyle(cb.mint, 1.5.sp))
                if (showLicences) Licences.forEach { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.ink2)) }
            }
        }
    }

    when (dialog) {
        1 -> FolderNameDialog(Settings.collageFolder, { name -> Settings.update { collageFolder = name }; dialog = 0 }) { dialog = 0 }
        2 -> CbDialog("CLEAR FAVORITES?", { dialog = 0 }) {
            Text("Your photos stay where they are. Only the Favorites list is emptied.", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = DarkCb.ink2))
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                ActionText("CANCEL", DarkCb.inkDim) { dialog = 0 }
                ActionText("CLEAR", Danger) { favorites.clear(); dialog = 0 }
            }
        }
    }
}

@Composable
private fun FolderNameDialog(current: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    val name = text.trim()
    val valid = name.isNotEmpty() && '/' !in name && '\\' !in name
    CbDialog("COLLAGE FOLDER", onCancel) {
        CbTextField(text, { text = it })
        Text("Inside Pictures. Created the first time a collage is saved.", style = monoStyle(DarkCb.inkDim, 0.4.sp))
        Row(Modifier.fillMaxWidth(), Arrangement.End) {
            ActionText("CANCEL", DarkCb.inkDim, onCancel)
            ActionText("SAVE", if (valid) DarkCb.mint else DarkCb.inkDim) { if (valid) onSave(name) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val cb = LocalCb.current
    Column(Modifier.fillMaxWidth().doubleChamferFrame(cb.mint).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = monoStyle(cb.mint, 2.6.sp))
        content()
    }
}

@Composable
private fun Toggle(label: String, note: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val cb = LocalCb.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = monoStyle(cb.ink, 1.5.sp))
            Text(note, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim))
        }
        // the switch: a track with a round knob that slides to the right when on
        Box(Modifier.width(44.dp).height(24.dp).clip(CircleShape).background(if (on) cb.mint else cb.surface3)) {
            Box(Modifier.offset(x = if (on) 22.dp else 2.dp, y = 2.dp).size(20.dp).clip(CircleShape).background(if (on) DarkCb.surface0 else cb.inkDim))
        }
    }
}

@Composable
private fun Choice(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cb = LocalCb.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = monoStyle(cb.inkDim, 1.5.sp))
        Row { options.forEachIndexed { i, o -> Chip(o, selected == i) { onSelect(i) } } }
    }
}
