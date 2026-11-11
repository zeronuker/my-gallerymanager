package com.claudeborne.gallerymanager.ui

import android.text.format.Formatter
import android.widget.Toast
import android.os.Environment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import com.claudeborne.gallerymanager.data.Updater
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

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
internal fun SettingsScreen(favorites: Favorites, onBack: () -> Unit, openPicker: (PickerRequest) -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    // the build number is the one the updater compares, so it shows whether this is the newest build
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { "v${it.versionName} · BUILD ${it.longVersionCode}" } }.getOrNull().orEmpty() }
    var cacheBytes by remember { mutableStateOf(folderSize(context.cacheDir)) }
    var dialog by remember { mutableIntStateOf(0) } // 1 collage folder, 2 clear favorites
    var showLicences by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("SETTINGS", version, onBack, "BACK")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Section("LOOK") {
                Setting("THEME", listOf("DARK", "LIGHT"), Settings.theme - 1) { Settings.update { theme = it + 1 } }
                Setting("GRID COLUMNS", (2..6).map { "$it" }, Settings.gridCols - 2) { Settings.update { gridCols = it + 2 } }
                Toggle("START ANIMATION", Settings.splash) { Settings.update { splash = it } }
            }
            Section("SETTINGS SHELF") {
                Setting("EDGE", listOf("LEFT", "RIGHT"), Settings.shelfEdge) { Settings.update { shelfEdge = it } }
                Toggle("TAP TO OPEN", Settings.shelfTap) { Settings.update { shelfTap = it } }
                Toggle("AUTO HIDE", Settings.shelfAutoHide) { Settings.update { shelfAutoHide = it } }
                // the real bar shows over this screen while these are changed; tap a number to put it back
                ShelfSlider("THICKNESS", Settings.shelfThickness, 2f..12f, " dp", 4f) { Settings.update { shelfThickness = it } }
                ShelfSlider("HEIGHT", Settings.shelfHeight, 30f..200f, " dp", 72f) { Settings.update { shelfHeight = it } }
                ShelfSlider("VERTICAL POSITION", Settings.shelfY, 10f..90f, "%", 38f) { Settings.update { shelfY = it } }
                ShelfSlider("DISTANCE FROM EDGE", Settings.shelfInset, 0f..60f, " dp", 0f) { Settings.update { shelfInset = it } }
            }
            Section("VIEWER") {
                Toggle("AUTOPLAY VIDEOS", Settings.autoplay) { Settings.update { autoplay = it } }
                Toggle("START MUTED", Settings.muted) { Settings.update { muted = it } }
            }
            Section("EDITING") {
                Setting("WHEN SAVING", listOf("ASK", "COPY", "REPLACE"), Settings.saveMode) { Settings.update { saveMode = it } }
                EditSlider("EDIT QUALITY", Settings.editQuality.toFloat(), 50f..100f, "${Settings.editQuality}", { Settings.update { editQuality = it.toInt() } }, {}, cb.ink) { Settings.update { editQuality = 95 } }
                EditSlider("EXPORT QUALITY", Settings.exportQuality.toFloat(), 50f..100f, "${Settings.exportQuality}", { Settings.update { exportQuality = it.toInt() } }, {}, cb.ink) { Settings.update { exportQuality = 90 } }
                Row(
                    Modifier.fillMaxWidth().clickable {
                        // the collage folder lives inside Pictures, so the picker cannot leave it
                        val pictures = File(Environment.getExternalStorageDirectory(), "Pictures").also { it.mkdirs() }
                        openPicker(
                            PickerRequest(
                                title = "COLLAGE FOLDER", pickFiles = false, confirmLabel = "SELECT", roots = emptyList(), limit = pictures,
                                hint = { d -> if (d == pictures) "Open or create a folder in Pictures" else null },
                                onFolder = { d -> Settings.update { collageFolder = d.relativeTo(pictures).path } },
                            ),
                        )
                    }.padding(horizontal = 16.dp, vertical = 6.dp), // lines up with the sliders above
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("COLLAGE FOLDER", style = monoStyle(cb.ink, 1.5.sp))
                    Text("Pictures/${Settings.collageFolder}", style = monoStyle(cb.mint, 1.sp))
                }
            }
            Section("PRIVACY") {
                Toggle("REMOVE LOCATION WHEN SHARING", Settings.stripOnShare) { Settings.update { stripOnShare = it } }
                Toggle("APP LOCK", Settings.lock) { on ->
                    when {
                        !on -> Settings.update { lock = false }
                        !canLock(context) -> Toast.makeText(context, "Set a screen lock or fingerprint on your phone first", Toast.LENGTH_LONG).show()
                        else -> authenticate(context, "Turn on app lock") { ok -> if (ok) Settings.update { lock = true } } // prove it works before relying on it
                    }
                }
                AnimatedVisibility(Settings.lock, enter = expandEnter, exit = shrinkExit) {
                    Setting("LOCK AFTER", listOf("AT ONCE", "1 MIN", "5 MIN"), Settings.lockAfter) { Settings.update { lockAfter = it } }
                }
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
                    Crossfade(Formatter.formatShortFileSize(context, cacheBytes), animationSpec = tween(MOTION_MS), label = "cache") { Text(it, style = monoStyle(cb.inkDim, 1.sp)) }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("AI UPSCALE MODEL", style = monoStyle(cb.ink, 1.5.sp))
                    Text("4.9 MB, BUILT IN", style = monoStyle(cb.inkDim, 1.sp))
                }
            }
            Section("ABOUT") {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("Altus Cirrus", style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = cb.ink))
                    Text(version, style = monoStyle(cb.inkDim, 1.sp))
                }
                val update = Updater.state
                Row(
                    Modifier.fillMaxWidth().clickable {
                        // a found update whose dialog was closed is reopened; anything else checks again
                        if (update is Updater.State.Available || update is Updater.State.Ready) Updater.showDialog = true else Updater.check(context, manual = true)
                    }.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("CHECK FOR UPDATES", style = monoStyle(cb.mint, 1.5.sp))
                    val status = when (update) {
                        is Updater.State.Checking -> "CHECKING…"
                        is Updater.State.UpToDate -> "UP TO DATE"
                        is Updater.State.Available, is Updater.State.Downloading, is Updater.State.Ready -> "NEW BUILD"
                        is Updater.State.Failed -> "FAILED"
                        else -> ""
                    }
                    Crossfade(status, animationSpec = tween(MOTION_MS), label = "updateStatus") { Text(it, style = monoStyle(if (it == "FAILED") Warn else cb.inkDim, 1.sp)) }
                }
                AnimatedVisibility(update is Updater.State.Checking, enter = expandEnter, exit = shrinkExit) { ProgressBar(null, color = cb.mint, track = cb.line) }
                Text(if (showLicences) "HIDE LICENCES" else "LICENCES", Modifier.clickable { showLicences = !showLicences }.padding(vertical = 6.dp), style = monoStyle(cb.mint, 1.5.sp))
                AnimatedVisibility(showLicences, enter = expandEnter, exit = shrinkExit) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Licences.forEach { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.ink2)) } }
                }
            }
        }
    }

    when (dialog) {
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
private fun Section(title: String, content: @Composable () -> Unit) {
    val cb = LocalCb.current
    Column(Modifier.fillMaxWidth().doubleChamferFrame(cb).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = monoStyle(cb.mint, 2.6.sp))
        content()
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val cb = LocalCb.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp), style = monoStyle(cb.ink, 1.5.sp))
        // the switch: a track with a round knob that slides to the right when on
        val knob by animateDpAsState(if (on) 22.dp else 2.dp, tween(MOTION_FAST_MS), label = "knob")
        Box(Modifier.width(44.dp).height(24.dp).clip(CircleShape).background(glide(on, cb.mint, cb.surface3))) {
            Box(Modifier.offset(x = knob, y = 2.dp).size(20.dp).clip(CircleShape).background(glide(on, DarkCb.surface0, cb.inkDim)))
        }
    }
}

/** A setting with its options on the same row, aligned right. */
@Composable
private fun Setting(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cb = LocalCb.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp), style = monoStyle(cb.ink, 1.5.sp))
        options.forEachIndexed { i, o -> Chip(o, selected == i) { onSelect(i) } }
    }
}

/** A smooth slider in 0.1 steps; tap the number to put it back to [default]. */
@Composable
private fun ShelfSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, default: Float, onChange: (Float) -> Unit) {
    EditSlider(label, value, range, String.format(Locale.US, "%.1f", value) + unit, { onChange((it * 10).roundToInt() / 10f) }, {}, LocalCb.current.ink) { onChange(default) }
}
