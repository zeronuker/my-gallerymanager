package com.claudeborne.gallerymanager.ui

import android.text.format.Formatter
import android.widget.Toast
import android.os.Environment
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/** The tabs down the left of Settings: the same layout as every ClaudeBorne app's Settings window. */
private enum class SetTab(val label: String, val hint: String, val icon: String) {
    GENERAL("GENERAL", "look · viewer · editing", "setGeneral"),
    SHELF("SHELF", "edge · size · position", "setShelf"),
    PRIVACY("PRIVACY", "location · lock · stored data", "setPrivacy"),
    ABOUT("ABOUT", "version · update · licences", "setAbout"),
}

/**
 * App settings, laid out like every ClaudeBorne app's Settings window: tabs down the left (a slim column of icons on a
 * phone, icon, name and hint on a tablet), the chosen tab's settings on the right. Every change is applied and saved immediately.
 */
@Composable
internal fun SettingsScreen(favorites: Favorites, onBack: () -> Unit, openPicker: (PickerRequest) -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    // the build number is the one the updater compares, so it shows whether this is the newest build
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { "v${it.versionName} · BUILD ${it.longVersionCode}" } }.getOrNull().orEmpty() }
    var cacheBytes by remember { mutableStateOf(folderSize(context.cacheDir)) }
    var dialog by remember { mutableIntStateOf(0) } // 1 collage folder, 2 clear favorites
    var showLicences by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("SETTINGS", version, onBack, "BACK")
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 600.dp
            Row(Modifier.fillMaxSize()) {
                SettingsRail(tab, wide) { tab = it }
                Box(Modifier.width(1.dp).fillMaxHeight().background(cb.line))
                // changing tab slides the page: up when moving down the tabs, down when moving back up
                AnimatedContent(
                    tab, Modifier.weight(1f).fillMaxHeight(),
                    transitionSpec = {
                        val dir = if (targetState >= initialState) 1 else -1
                        (fadeIn(tween(MOTION_MS)) + slideInVertically(tween(MOTION_MS)) { dir * it / 14 }) togetherWith
                            (fadeOut(tween(MOTION_FAST_MS)) + slideOutVertically(tween(MOTION_FAST_MS)) { -dir * it / 14 })
                    },
                    label = "settingsTab",
                ) { shown ->
                    val t = SetTab.entries[shown]
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(t.label, style = TextStyle(fontFamily = Tourney, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 1.6.sp, color = cb.ink))
                        Text(t.hint.uppercase(), style = monoStyle(cb.inkDim, 1.8.sp))
                        when (t) {
                            SetTab.GENERAL -> {
                                Section("LOOK") {
                                    Setting("THEME", listOf("DARK", "LIGHT"), Settings.theme - 1) { Settings.update { theme = it + 1 } }
                                    Setting("GRID COLUMNS", (2..6).map { "$it" }, Settings.gridCols - 2, stacked = !wide) { Settings.update { gridCols = it + 2 } }
                                    Toggle("START ANIMATION", Settings.splash) { Settings.update { splash = it } }
                                }
                                Section("VIEWER") {
                                    Toggle("AUTOPLAY VIDEOS", Settings.autoplay) { Settings.update { autoplay = it } }
                                    Toggle("START MUTED", Settings.muted) { Settings.update { muted = it } }
                                }
                                Section("EDITING") {
                                    Setting("WHEN SAVING", listOf("ASK", "COPY", "REPLACE"), Settings.saveMode, stacked = !wide) { Settings.update { saveMode = it } }
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
                            }
                            SetTab.SHELF -> Section("SETTINGS SHELF") {
                                Setting("EDGE", listOf("LEFT", "RIGHT"), Settings.shelfEdge) { Settings.update { shelfEdge = it } }
                                Toggle("TAP TO OPEN", Settings.shelfTap) { Settings.update { shelfTap = it } }
                                Toggle("AUTO HIDE", Settings.shelfAutoHide) { Settings.update { shelfAutoHide = it } }
                                // the real bar shows over this screen while these are changed; tap a number to put it back
                                ShelfSlider("THICKNESS", Settings.shelfThickness, 2f..12f, " dp", 4f) { Settings.update { shelfThickness = it } }
                                ShelfSlider("HEIGHT", Settings.shelfHeight, 30f..200f, " dp", 72f) { Settings.update { shelfHeight = it } }
                                ShelfSlider("VERTICAL POSITION", Settings.shelfY, 10f..90f, "%", 38f) { Settings.update { shelfY = it } }
                                ShelfSlider("DISTANCE FROM EDGE", Settings.shelfInset, 0f..60f, " dp", 0f) { Settings.update { shelfInset = it } }
                            }
                            SetTab.PRIVACY -> {
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
                                        Setting("LOCK AFTER", listOf("AT ONCE", "1 MIN", "5 MIN"), Settings.lockAfter, stacked = !wide) { Settings.update { lockAfter = it } }
                                    }
                                }
                                Section("STORED DATA") {
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
                                }
                            }
                            SetTab.ABOUT -> {
                                Section("APP") {
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
                                Section("BUILT IN") {
                                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("AI UPSCALE MODEL", style = monoStyle(cb.ink, 1.5.sp))
                                        Text("4.9 MB", style = monoStyle(cb.inkDim, 1.sp))
                                    }
                                }
                            }
                        }
                    }
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

/** The column of tabs, with a highlight (a gradient line at its left) that glides to the chosen one. */
@Composable
private fun SettingsRail(selected: Int, wide: Boolean, onSelect: (Int) -> Unit) {
    val cb = LocalCb.current
    val itemH = if (wide) 56.dp else 66.dp
    val gap = 2.dp
    val markY by animateDpAsState((itemH + gap) * selected, tween(MOTION_MS), label = "railMark")
    Box(Modifier.width(if (wide) 200.dp else 78.dp).fillMaxHeight().background(cb.surface0).padding(horizontal = 6.dp, vertical = 8.dp)) {
        Box(Modifier.offset(y = markY).fillMaxWidth().height(itemH).background(cb.surface2)) {
            Box(Modifier.padding(vertical = 6.dp).width(2.dp).fillMaxHeight().background(cb.grad))
        }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            SetTab.entries.forEachIndexed { i, t ->
                val on = i == selected
                val color = glide(on, cb.mint, cb.ink2)
                val pick = Modifier.fillMaxWidth().height(itemH).selectable(on, role = Role.Tab) { onSelect(i) }
                if (wide) {
                    Row(pick.padding(horizontal = 12.dp), Arrangement.spacedBy(11.dp), Alignment.CenterVertically) {
                        SvgIcon(t.icon, color, size = 20.dp)
                        Column {
                            Text(t.label, style = monoStyle(color, 1.8.sp))
                            Text(t.hint, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontSize = 11.sp, color = cb.inkDim))
                        }
                    }
                } else {
                    Column(pick, Arrangement.spacedBy(5.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
                        SvgIcon(t.icon, color, size = 22.dp)
                        Text(t.label, style = monoStyle(color, 0.8.sp).copy(fontSize = 9.sp))
                    }
                }
            }
        }
    }
}

/** A titled group: the title in mint with a hairline after it, like the web apps' Settings. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val cb = LocalCb.current
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = monoStyle(cb.mint, 2.6.sp))
            Box(Modifier.padding(start = 10.dp).weight(1f).height(1.dp).background(cb.lineStrong))
        }
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

/** A setting with its options on the same row, aligned right; [stacked] puts them under the name instead (a narrow pane). */
@Composable
private fun Setting(label: String, options: List<String>, selected: Int, stacked: Boolean = false, onSelect: (Int) -> Unit) {
    val cb = LocalCb.current
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = monoStyle(cb.ink, 1.5.sp))
            Row { options.forEachIndexed { i, o -> Chip(o, selected == i) { onSelect(i) } } }
        }
        return
    }
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
