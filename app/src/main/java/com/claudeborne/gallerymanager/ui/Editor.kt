package com.claudeborne.gallerymanager.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.edit.Adjust
import com.claudeborne.gallerymanager.edit.Aspect
import com.claudeborne.gallerymanager.edit.Crop
import com.claudeborne.gallerymanager.edit.EditShader
import com.claudeborne.gallerymanager.edit.EditState
import com.claudeborne.gallerymanager.edit.Filters
import com.claudeborne.gallerymanager.edit.autoEnhance
import com.claudeborne.gallerymanager.edit.canReplace
import com.claudeborne.gallerymanager.edit.frameSize
import com.claudeborne.gallerymanager.edit.loadBitmap
import com.claudeborne.gallerymanager.edit.renderEdited
import com.claudeborne.gallerymanager.edit.saveEdited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val Panel = Color(0xFF0A1020)
private val Danger = Color(0xFFFF6B6B)

/** Full-screen photo editor. Edits are a list of [EditState] snapshots, so every step can be undone; the file is only written on save. */
@Composable
fun Editor(item: MediaItem, tools: FileTools, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id) { bitmap = loadBitmap(context, item.uri, 2048) }

    val history = remember { mutableStateListOf(EditState()) }
    var at by remember { mutableIntStateOf(0) }
    var live by remember { mutableStateOf<EditState?>(null) } // the in-progress value while a slider or handle is being dragged
    val state = live ?: history[at]
    fun commit(next: EditState) {
        while (history.size > at + 1) history.removeAt(history.lastIndex)
        if (next != history[at]) { history.add(next); at++ }
        live = null
    }

    var tool by remember { mutableIntStateOf(1) }
    var aspect by remember { mutableStateOf(Aspect.FREE) }
    var which by remember { mutableStateOf(Adjust.EXPOSURE) }
    var showOriginal by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var dialog by remember { mutableIntStateOf(0) } // 1 save options, 2 discard

    fun close() { if (at > 0) dialog = 2 else onClose() }
    BackHandler { close() }

    fun finish(ok: Boolean) {
        saving = false
        Toast.makeText(context, if (ok) "Saved" else "Couldn't save the photo", Toast.LENGTH_SHORT).show()
        if (ok) onSaved()
    }
    fun save(replace: Boolean) {
        dialog = 0
        saving = true
        val edits = state
        scope.launch {
            val out = runCatching { withContext(Dispatchers.Default) { renderEdited(loadBitmap(context, item.uri, 6144), edits) } }.getOrNull()
            when {
                out == null -> finish(false)
                replace -> { saving = false; tools.write(listOf(item.uri)) { saving = true; finish(saveEdited(context, item, out, true)) } }
                else -> finish(saveEdited(context, item, out, false))
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionText("✕", DarkCb.inkDim) { close() }
                Box(Modifier.weight(1f))
                ActionText("UNDO", if (at > 0) DarkCb.ink else DarkCb.inkDim) { if (at > 0) { at--; live = null } }
                ActionText("REDO", if (at < history.lastIndex) DarkCb.ink else DarkCb.inkDim) { if (at < history.lastIndex) { at++; live = null } }
                Box(Modifier.weight(1f))
                ActionText("SAVE", if (state.isEdited) DarkCb.mint else DarkCb.inkDim) { if (state.isEdited) dialog = 1 else onClose() }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val bmp = bitmap
                if (bmp == null) {
                    Text("LOADING", Modifier.align(Alignment.Center), style = monoStyle(DarkCb.inkDim, 2.6.sp))
                } else {
                    EditCanvas(
                        bmp, if (showOriginal) EditState() else state, cropMode = tool == 0 && !showOriginal, aspect,
                        onCrop = { live = state.copy(crop = it) }, onCropDone = { live?.let(::commit) },
                    )
                    Text(
                        "HOLD TO COMPARE",
                        Modifier.align(Alignment.BottomEnd).padding(12.dp).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 6.dp)
                            .pointerInput(Unit) { detectTapGestures(onPress = { showOriginal = true; tryAwaitRelease(); showOriginal = false }) },
                        style = monoStyle(DarkCb.ink, 1.5.sp),
                    )
                }
            }

            Column(Modifier.fillMaxWidth().background(Panel).navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth()) {
                    listOf("CROP", "ADJUST", "FILTERS").forEachIndexed { i, label ->
                        Column(Modifier.weight(1f).clickable { tool = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(2.dp).background(if (tool == i) DarkCb.mint else Color.Transparent))
                            Text(label, Modifier.padding(vertical = 12.dp), style = monoStyle(if (tool == i) DarkCb.mint else DarkCb.inkDim, 2.2.sp))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(150.dp)) {
                    val bmp = bitmap
                    if (bmp != null) when (tool) {
                        0 -> CropPanel(
                            state, aspect,
                            onAspect = { a ->
                                aspect = a
                                val (cw, ch) = frameSize(bmp.width, bmp.height, state.turns)
                                val frame = cw / ch
                                a.lock(frame)?.let { r ->
                                    // the biggest centred crop with that shape
                                    val (w, h) = if (r >= frame) 1f to frame / r else r / frame to 1f
                                    commit(state.copy(crop = Crop((1 - w) / 2, (1 - h) / 2, (1 + w) / 2, (1 + h) / 2)))
                                }
                            },
                            onRotate = { commit(state.copy(turns = (state.turns + 3) % 4, crop = Crop())); aspect = Aspect.FREE },
                            onFlipH = { val c = state.crop; commit(state.copy(flipH = !state.flipH, crop = Crop(1 - c.r, c.t, 1 - c.l, c.b))) },
                            onFlipV = { val c = state.crop; commit(state.copy(flipV = !state.flipV, crop = Crop(c.l, 1 - c.b, c.r, 1 - c.t))) },
                            onReset = { commit(state.copy(crop = Crop(), angle = 0f)); aspect = Aspect.FREE },
                            onAngle = { live = state.copy(angle = it) },
                            onAngleDone = { live?.let(::commit) },
                        )
                        1 -> AdjustPanel(
                            state, which, onWhich = { which = it },
                            onChange = { live = state.with(which, it) }, onDone = { live?.let(::commit) },
                            onAuto = {
                                val base = state
                                scope.launch { commit(base.copy(adjust = base.adjust + withContext(Dispatchers.Default) { autoEnhance(bmp) })) }
                            },
                            onReset = { commit(state.copy(adjust = emptyMap(), filter = 0, filterAmount = 1f)) },
                        )
                        else -> FilterPanel(
                            bmp, state, onPick = { commit(state.copy(filter = it)) },
                            onAmount = { live = state.copy(filterAmount = it) }, onAmountDone = { live?.let(::commit) },
                        )
                    }
                }
            }
        }

        when (dialog) {
            1 -> CbDialog("SAVE", { dialog = 0 }) {
                Choice("SAVE AS COPY", "Keeps the original untouched.") { save(false) }
                if (canReplace(item)) Choice("REPLACE ORIGINAL", "Overwrites this photo.") { save(true) }
                Row(Modifier.fillMaxWidth(), Arrangement.End) { ActionText("CANCEL", DarkCb.inkDim) { dialog = 0 } }
            }
            2 -> CbDialog("DISCARD EDITS?", { dialog = 0 }) {
                Text("Your changes will be lost.", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = DarkCb.ink2))
                Row(Modifier.fillMaxWidth(), Arrangement.End) {
                    ActionText("KEEP EDITING", DarkCb.inkDim) { dialog = 0 }
                    ActionText("DISCARD", Danger) { onClose() }
                }
            }
        }
        if (saving) {
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)).pointerInput(Unit) { detectTapGestures { } }, Alignment.Center) {
                Text("SAVING…", style = monoStyle(DarkCb.mint, 2.6.sp))
            }
        }
    }
}

@Composable
private fun Choice(title: String, note: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = DarkCb.mint))
        Text(note, style = monoStyle(DarkCb.inkDim, 0.5.sp))
    }
}

/** The picture, fitted to the space. In crop mode it shows the whole frame with the crop box on top. */
@Composable
private fun EditCanvas(bmp: Bitmap, st: EditState, cropMode: Boolean, lock: Aspect, onCrop: (Crop) -> Unit, onCropDone: () -> Unit) {
    val shader = remember { EditShader() }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
        val (cw, ch) = frameSize(bmp.width, bmp.height, st.turns)
        val crop = if (cropMode) Crop() else st.crop
        val ratio = crop.w * cw / (crop.h * ch)
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val w = if (maxW / maxH > ratio) maxH * ratio else maxW
        val h = w / ratio
        Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() }).align(Alignment.Center)) {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { shader.draw(it.nativeCanvas, bmp, st, crop, size.width.roundToInt(), size.height.roundToInt()) }
            }
            if (cropMode) CropOverlay(st.crop, lock.lock(cw / ch), cw / ch, onCrop, onCropDone, Modifier.fillMaxSize())
        }
    }
}

private const val MIN_CROP = 0.08f

/**
 * Moves or resizes [c] by a drag of ([dx], [dy]) in frame fractions. [grab] bits: 1 left, 2 top, 4 right, 8 bottom, 16 whole box.
 * [wh] is the width/height the box must keep in those fractions, or null when free.
 */
private fun dragCrop(c: Crop, grab: Int, dx: Float, dy: Float, wh: Float?): Crop {
    if (grab == 0) return c
    if (grab == 16) {
        val mx = dx.coerceIn(-c.l, 1f - c.r)
        val my = dy.coerceIn(-c.t, 1f - c.b)
        return Crop(c.l + mx, c.t + my, c.r + mx, c.b + my)
    }
    var l = c.l; var t = c.t; var r = c.r; var b = c.b
    if (grab and 1 != 0) l = (l + dx).coerceIn(0f, r - MIN_CROP)
    if (grab and 4 != 0) r = (r + dx).coerceIn(l + MIN_CROP, 1f)
    if (grab and 2 != 0) t = (t + dy).coerceIn(0f, b - MIN_CROP)
    if (grab and 8 != 0) b = (b + dy).coerceIn(t + MIN_CROP, 1f)
    if (wh == null) return Crop(l, t, r, b)

    // keep the shape: width leads when a side was dragged, height otherwise; the opposite edge stays put
    var w = r - l
    var h = b - t
    if (grab and 5 != 0) h = w / wh else w = h * wh
    if (w > 1f) { w = 1f; h = w / wh }
    if (h > 1f) { h = 1f; w = h * wh }
    val nl = when { grab and 1 != 0 -> r - w; grab and 4 != 0 -> l; else -> (l + r) / 2 - w / 2 }.coerceIn(0f, 1f - w)
    val nt = when { grab and 2 != 0 -> b - h; grab and 8 != 0 -> t; else -> (t + b) / 2 - h / 2 }.coerceIn(0f, 1f - h)
    return Crop(nl, nt, nl + w, nt + h)
}

@Composable
private fun CropOverlay(crop: Crop, lock: Float?, frameAspect: Float, onChange: (Crop) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val current by rememberUpdatedState(crop)
    val lockNow by rememberUpdatedState(lock)
    val changeNow by rememberUpdatedState(onChange)
    val doneNow by rememberUpdatedState(onDone)
    var grab by remember { mutableIntStateOf(0) }
    Canvas(
        modifier.pointerInput(Unit) {
            val slop = 36.dp.toPx()
            detectDragGestures(
                onDragStart = { p ->
                    val c = current
                    val l = c.l * size.width; val t = c.t * size.height; val r = c.r * size.width; val b = c.b * size.height
                    val inX = p.x in (l - slop)..(r + slop)
                    val inY = p.y in (t - slop)..(b + slop)
                    var g = 0
                    // when the box is narrow both sides can be in reach; the nearer one wins
                    if (inY && abs(p.x - l) < slop && abs(p.x - l) <= abs(p.x - r)) g = g or 1
                    if (inY && abs(p.x - r) < slop && abs(p.x - r) < abs(p.x - l)) g = g or 4
                    if (inX && abs(p.y - t) < slop && abs(p.y - t) <= abs(p.y - b)) g = g or 2
                    if (inX && abs(p.y - b) < slop && abs(p.y - b) < abs(p.y - t)) g = g or 8
                    if (g == 0 && p.x in l..r && p.y in t..b) g = 16
                    grab = g
                },
                onDrag = { change, drag ->
                    change.consume()
                    changeNow(dragCrop(current, grab, drag.x / size.width, drag.y / size.height, lockNow?.let { it / frameAspect }))
                },
                onDragEnd = { grab = 0; doneNow() },
                onDragCancel = { grab = 0; doneNow() },
            )
        },
    ) {
        val x0 = crop.l * size.width; val y0 = crop.t * size.height; val x1 = crop.r * size.width; val y1 = crop.b * size.height
        val dim = Color(0x99000000)
        drawRect(dim, Offset.Zero, Size(size.width, y0))
        drawRect(dim, Offset(0f, y1), Size(size.width, size.height - y1))
        drawRect(dim, Offset(0f, y0), Size(x0, y1 - y0))
        drawRect(dim, Offset(x1, y0), Size(size.width - x1, y1 - y0))
        val grid = Color(0x55FFFFFF)
        for (i in 1..2) {
            drawLine(grid, Offset(x0 + (x1 - x0) * i / 3, y0), Offset(x0 + (x1 - x0) * i / 3, y1), 1f)
            drawLine(grid, Offset(x0, y0 + (y1 - y0) * i / 3), Offset(x1, y0 + (y1 - y0) * i / 3), 1f)
        }
        drawRect(DarkCb.mint, Offset(x0, y0), Size(x1 - x0, y1 - y0), style = Stroke(1.5.dp.toPx()))
        val arm = 18.dp.toPx(); val th = 3.dp.toPx()
        for ((cx, dirX) in listOf(x0 to 1f, x1 to -1f)) for ((cy, dirY) in listOf(y0 to 1f, y1 to -1f)) {
            drawLine(DarkCb.mint, Offset(cx, cy), Offset(cx + arm * dirX, cy), th)
            drawLine(DarkCb.mint, Offset(cx, cy), Offset(cx, cy + arm * dirY), th)
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean = false, color: Color = DarkCb.inkDim, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clickable(onClick = onClick).background(if (selected) DarkCb.surface3 else Color.Transparent).padding(horizontal = 10.dp, vertical = 8.dp),
        style = monoStyle(if (selected) DarkCb.mint else color, 1.6.sp),
    )
}

@Composable
private fun EditSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit, onDone: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(label, style = monoStyle(DarkCb.inkDim, 1.5.sp))
            Text(display, Modifier.clickable(onClick = onReset), style = monoStyle(DarkCb.mint, 1.5.sp)) // tap the number to reset
        }
        Slider(
            value.coerceIn(range), onChange, valueRange = range, onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = DarkCb.mint, activeTrackColor = DarkCb.mint, inactiveTrackColor = Color(0x3DFFFFFF)),
        )
    }
}

@Composable
private fun CropPanel(
    st: EditState, aspect: Aspect, onAspect: (Aspect) -> Unit, onRotate: () -> Unit, onFlipH: () -> Unit, onFlipV: () -> Unit,
    onReset: () -> Unit, onAngle: (Float) -> Unit, onAngleDone: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
            items(Aspect.entries) { Chip(it.label, aspect == it) { onAspect(it) } }
        }
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
            item { Chip("ROTATE", color = DarkCb.ink, onClick = onRotate) }
            item { Chip("FLIP H", color = DarkCb.ink, onClick = onFlipH) }
            item { Chip("FLIP V", color = DarkCb.ink, onClick = onFlipV) }
            item { Chip("RESET", onClick = onReset) }
        }
        EditSlider("STRAIGHTEN", st.angle, -45f..45f, "%.1f°".format(st.angle), onAngle, onAngleDone) { onAngle(0f); onAngleDone() }
    }
}

@Composable
private fun AdjustPanel(
    st: EditState, which: Adjust, onWhich: (Adjust) -> Unit, onChange: (Float) -> Unit, onDone: () -> Unit, onAuto: () -> Unit, onReset: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
            item { Chip("AUTO", color = DarkCb.mint, onClick = onAuto) }
            item { Chip("RESET", onClick = onReset) }
            items(Adjust.entries) { a -> Chip(a.label, which == a) { onWhich(a) } }
        }
        val v = st.value(which)
        EditSlider(which.label, v, which.min..1f, "%+d".format((v * 100).roundToInt()), onChange, onDone) { onChange(0f); onDone() }
    }
}

@Composable
private fun FilterPanel(bmp: Bitmap, st: EditState, onPick: (Int) -> Unit, onAmount: (Float) -> Unit, onAmountDone: () -> Unit) {
    val shader = remember { EditShader() } // one shader draws every thumbnail, one after the other
    val thumb = remember(bmp) {
        val s = 160f / maxOf(bmp.width, bmp.height)
        Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt().coerceAtLeast(1), (bmp.height * s).roundToInt().coerceAtLeast(1), true)
    }
    // a centred square of the thumbnail
    val square = remember(thumb) {
        if (thumb.width >= thumb.height) { val f = thumb.height.toFloat() / thumb.width; Crop((1 - f) / 2, 0f, (1 + f) / 2, 1f) }
        else { val f = thumb.width.toFloat() / thumb.height; Crop(0f, (1 - f) / 2, 1f, (1 + f) / 2) }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(Filters) { i, f ->
                Column(Modifier.clickable { onPick(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.size(64.dp).then(if (st.filter == i) Modifier.border(2.dp, DarkCb.mint) else Modifier)) {
                        drawIntoCanvas { shader.draw(it.nativeCanvas, thumb, EditState(filter = i), square, size.width.roundToInt(), size.height.roundToInt()) }
                    }
                    Text(f.name, Modifier.padding(top = 4.dp), style = monoStyle(if (st.filter == i) DarkCb.mint else DarkCb.inkDim, 0.5.sp).copy(fontSize = 9.sp))
                }
            }
        }
        if (st.filter != 0) {
            EditSlider("INTENSITY", st.filterAmount, 0f..1f, "${(st.filterAmount * 100).roundToInt()}", onAmount, onAmountDone) { onAmount(1f); onAmountDone() }
        } else {
            Text("PICK A LOOK", Modifier.padding(horizontal = 16.dp), style = monoStyle(DarkCb.inkDim, 1.5.sp))
        }
    }
}
