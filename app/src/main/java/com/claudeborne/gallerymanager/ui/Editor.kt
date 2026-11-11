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
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.claudeborne.gallerymanager.data.Settings
import com.claudeborne.gallerymanager.edit.Adjust
import com.claudeborne.gallerymanager.edit.Aspect
import com.claudeborne.gallerymanager.edit.Crop
import com.claudeborne.gallerymanager.edit.EditShader
import com.claudeborne.gallerymanager.edit.EditState
import com.claudeborne.gallerymanager.edit.Backdrop
import com.claudeborne.gallerymanager.edit.Band
import com.claudeborne.gallerymanager.edit.Curves
import com.claudeborne.gallerymanager.edit.NoColourTuning
import com.claudeborne.gallerymanager.edit.Filters
import com.claudeborne.gallerymanager.edit.findFaces
import com.claudeborne.gallerymanager.edit.findSubject
import com.claudeborne.gallerymanager.edit.Label
import com.claudeborne.gallerymanager.edit.Obscure
import com.claudeborne.gallerymanager.edit.Patch
import com.claudeborne.gallerymanager.edit.Pt
import com.claudeborne.gallerymanager.edit.Scope
import com.claudeborne.gallerymanager.edit.ScopedAdjust
import com.claudeborne.gallerymanager.edit.Sticker
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

internal val Panel = Color(0xFF0A1020)
internal val Danger = Color(0xFFFF6B6B)

/** Full-screen photo editor. Edits are a list of [EditState] snapshots, so every step can be undone; the file is only written on save. */
@Composable
fun Editor(item: MediaItem, tools: FileTools, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var mask by remember { mutableStateOf<Bitmap?>(null) } // the subject found by CUTOUT, the size of [bitmap]
    var maskTried by remember { mutableStateOf(false) }
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
    var adjScope by remember { mutableStateOf(Scope.ALL) } // ADJUST changes the whole picture, only the subject, or only the background
    // the subject is found the first time CUTOUT is opened, or ADJUST is limited to subject/background (and again after "FIND AGAIN")
    LaunchedEffect(tool, bitmap, maskTried, adjScope) {
        val b = bitmap
        if ((tool == 5 || (tool == 1 && adjScope != Scope.ALL)) && b != null && mask == null && !maskTried) {
            mask = findSubject(context, b)
            maskTried = true
        }
    }
    var aspect by remember { mutableStateOf(Aspect.FREE) }
    var which by remember { mutableStateOf(Adjust.EXPOSURE) }
    var adv by remember { mutableIntStateOf(0) } // ADJUST showing: 0 plain sliders, else levels / curves / colour
    var curveCh by remember { mutableIntStateOf(0) }
    var band by remember { mutableStateOf(Band.RED) }

    // markup: the chosen tool, colour, sizes per tool, sticker, the selected text/sticker, and where new text is being added
    var mtool by remember { mutableStateOf(MarkTool.PEN) }
    var mcolor by remember { mutableIntStateOf(MarkPalette[0]) }
    val sizes = remember { mutableStateMapOf(MarkTool.PEN to 0.006f, MarkTool.MARKER to 0.03f, MarkTool.TEXT to 0.07f, MarkTool.STICKER to 0.14f) }
    var glyph by remember { mutableStateOf("😀") }
    var selected by remember { mutableIntStateOf(-1) }
    var textAt by remember { mutableStateOf<Pt?>(null) }
    var editText by remember { mutableStateOf(false) } // the selected text mark's words are being changed
    val selMark = state.marks.getOrNull(selected)?.takeIf { it.sizeOrNull() != null }
    fun finishMarks() { // a drag that never grew into a real region leaves nothing behind
        live?.let { s -> commit(s.copy(marks = s.marks.filterNot { it is Obscure && (it.r.w < 0.01f || it.r.h < 0.01f) })) }
    }

    // retouch: Heal or Clone, brush size, and Clone's chosen source point and the offset it fixes on the first stroke
    var rtool by remember { mutableStateOf(RetouchTool.HEAL) }
    var rface by remember { mutableStateOf(false) } // RETOUCH tab showing the portrait tools
    var fwhich by remember { mutableStateOf(FaceFix.SKIN) }
    var faces by remember { mutableStateOf<Bitmap?>(null) } // skin/teeth/eye map of the faces found
    var facesTried by remember { mutableStateOf(false) }
    var rsize by remember { mutableFloatStateOf(0.05f) }
    var cloneSrc by remember { mutableStateOf<Pt?>(null) }
    var cloneOff by remember { mutableStateOf<Pt?>(null) }

    // faces are looked for the first time the portrait tools are opened
    LaunchedEffect(tool, rface, bitmap, facesTried) {
        val b = bitmap
        if (tool == 4 && rface && b != null && faces == null && !facesTried) {
            faces = findFaces(b)
            facesTried = true
        }
    }

    var showOriginal by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var enhancing by remember { mutableStateOf(false) } // AUTO is working out the adjustments
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
            val see = edits.backdrop == Backdrop.TRANSPARENT && mask != null // a see-through background can only be kept in a PNG
            val out = runCatching {
                withContext(Dispatchers.Default) {
                    val big = loadBitmap(context, item.uri, 6144)
                    renderEdited(
                        big, edits, 1f,
                        mask?.let { Bitmap.createScaledBitmap(it, big.width, big.height, true) },
                        faces?.let { Bitmap.createScaledBitmap(it, big.width, big.height, true) },
                    )
                }
            }.getOrNull()
            when {
                out == null -> finish(false)
                replace -> { saving = false; tools.write(listOf(item.uri)) { saving = true; finish(saveEdited(context, item, out, true)) } }
                else -> finish(saveEdited(context, item, out, false, asPng = see))
            }
        }
    }

    // follows the default save choice in Settings; a see-through background can only be saved as a new PNG
    fun startSave() {
        val see = state.backdrop == Backdrop.TRANSPARENT && mask != null
        when {
            Settings.saveMode == 1 || (Settings.saveMode == 2 && (see || !canReplace(item))) -> save(false)
            Settings.saveMode == 2 -> save(true)
            else -> dialog = 1
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CloseButton { close() }
                Box(Modifier.weight(1f))
                ActionText("UNDO", if (at > 0) DarkCb.ink else DarkCb.inkDim) { if (at > 0) { at--; live = null; selected = -1 } }
                ActionText("REDO", if (at < history.lastIndex) DarkCb.ink else DarkCb.inkDim) { if (at < history.lastIndex) { at++; live = null; selected = -1 } }
                ActionText("RESET", if (state.isEdited) DarkCb.ink else DarkCb.inkDim) { if (state.isEdited) dialog = 3 }
                Box(Modifier.weight(1f))
                ActionText("SAVE", if (state.isEdited) DarkCb.mint else DarkCb.inkDim) { if (state.isEdited) startSave() else onClose() }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val bmp = bitmap
                if (bmp == null) {
                    LoadingPane("LOADING PHOTO", null, Modifier.align(Alignment.Center))
                } else {
                    EditCanvas(
                        bmp, if (showOriginal) EditState() else state, cropMode = tool == 0 && !showOriginal, aspect,
                        onCrop = { live = state.copy(crop = it) }, onCropDone = { live?.let(::commit) },
                        onePan = tool == 1 || tool == 2 || tool == 5, // ADJUST, FILTERS and CUTOUT have nothing to draw, so one finger can move a zoomed picture
                        mask = mask, faces = faces,
                        overlay = when {
                            showOriginal -> null
                            tool == 3 -> {
                            {
                                MarkupOverlay(
                                    mtool, state.crop, state.marks, mcolor, sizes[mtool] ?: 0.05f, glyph, selected,
                                    onLive = { live = state.copy(marks = it) }, onDone = ::finishMarks, onSelect = { selected = it },
                                    onText = { textAt = it },
                                    onSticker = { p ->
                                        val index = state.marks.size
                                        commit(state.copy(marks = state.marks + Sticker(glyph, p.u, p.v, sizes.getValue(MarkTool.STICKER))))
                                        selected = index
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            }
                            tool == 4 && !rface -> {
                                {
                                    RetouchOverlay(
                                        rtool, state.crop, state.marks, bmp, state, rsize, cloneSrc, cloneOff,
                                        onLive = { live = state.copy(marks = it) }, onDone = ::finishMarks,
                                        onSource = { cloneSrc = it; cloneOff = null }, onOffset = { cloneOff = it },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            else -> null
                        },
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
                    listOf("CROP", "ADJUST", "FILTERS", "MARKUP", "RETOUCH", "CUTOUT").forEachIndexed { i, label ->
                        Column(Modifier.weight(1f).clickable { tool = i; selected = -1 }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(2.dp).background(glide(tool == i, DarkCb.mint, Color.Transparent)))
                            Text(label, Modifier.padding(vertical = 12.dp), style = monoStyle(glide(tool == i, DarkCb.mint, DarkCb.inkDim), 2.2.sp))
                        }
                    }
                }
                // a selected text or sticker has one more slider, so the panel is taller then; the height glides between tools
                val panelHeight by animateDpAsState(if (tool == 3 && selMark != null) 200.dp else if (tool == 1) when (adv) { ADV_LEVELS -> 250.dp; ADV_CURVES -> 330.dp; ADV_COLOUR -> 270.dp; else -> 190.dp } else 150.dp, tween(MOTION_MS), label = "panel")
                Box(Modifier.fillMaxWidth().height(panelHeight)) {
                    val bmp = bitmap
                    if (bmp != null) Crossfade(tool, animationSpec = tween(MOTION_FAST_MS), label = "tool") { shownTool -> when (shownTool) {
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
                        1 -> if (adv != 0) AdvancedPanel(
                            adv, state, onMode = { adv = it }, onLive = { live = it }, onDone = { live?.let(::commit) },
                            channel = curveCh, onChannel = { curveCh = it }, band = band, onBand = { band = it },
                        ) else AdjustPanel(
                            state, which, adjScope, found = mask != null, tried = maskTried,
                            onScope = { adjScope = it; if (it != Scope.ALL && which !in ScopedAdjust) which = Adjust.EXPOSURE },
                            onWhich = { which = it }, onMode = { adv = it },
                            onChange = { live = state.with(which, it, adjScope) }, onDone = { live?.let(::commit) },
                            onAuto = {
                                val base = state
                                scope.launch {
                                    enhancing = true
                                    try { commit(base.copy(adjust = base.adjust + withContext(Dispatchers.Default) { autoEnhance(bmp) })) } finally { enhancing = false }
                                }
                            },
                            onReset = {
                                commit(
                                    if (adjScope == Scope.SUBJECT) state.copy(subject = emptyMap())
                                    else if (adjScope == Scope.BACKGROUND) state.copy(background = emptyMap())
                                    else state.copy(
                                        adjust = emptyMap(), subject = emptyMap(), background = emptyMap(), filter = 0, filterAmount = 1f,
                                        lvBlack = 0f, lvWhite = 1f, lvGamma = 1f, curves = Curves(), colours = NoColourTuning,
                                    ),
                                )
                            },
                        )
                        2 -> FilterPanel(
                            bmp, state, onPick = { commit(state.copy(filter = it)) },
                            onAmount = { live = state.copy(filterAmount = it) }, onAmountDone = { live?.let(::commit) },
                        )
                        3 -> MarkupPanel(
                            mtool, onTool = { mtool = it; selected = -1 }, mcolor,
                            onColor = { c ->
                                mcolor = c
                                (selMark as? Label)?.let { label -> commit(state.copy(marks = state.marks.mapIndexed { i, m -> if (i == selected) label.copy(color = c) else m })) }
                            },
                            glyph, onGlyph = { glyph = it },
                            size = selMark?.sizeOrNull() ?: sizes[mtool] ?: 0.05f,
                            range = selMark?.let { sizeRange(if (it is Label) MarkTool.TEXT else MarkTool.STICKER) } ?: sizeRange(mtool),
                            onSize = { v ->
                                if (selMark != null) live = state.copy(marks = state.marks.mapIndexed { i, m -> if (i == selected) m.withSize(v) else m })
                                else sizes[mtool] = v
                            },
                            onSizeDone = { live?.let(::commit) },
                            canDelete = selMark != null,
                            onDelete = { commit(state.copy(marks = state.marks.filterIndexed { i, _ -> i != selected })); selected = -1 },
                            rot = selMark?.rotOrNull(),
                            onRot = { v -> live = state.copy(marks = state.marks.mapIndexed { i, m -> if (i == selected) m.withRot(v) else m }) },
                            onEditText = if (selMark is Label) ({ editText = true }) else null,
                        )
                        4 -> if (rface) FacePanel(
                            state, found = faces != null, tried = facesTried, which = fwhich, onWhich = { fwhich = it },
                            onChange = { live = state.withStrength(fwhich, it) }, onDone = { live?.let(::commit) },
                            onHeal = { rface = false; rtool = RetouchTool.HEAL }, onClone = { rface = false; rtool = RetouchTool.CLONE },
                            onFind = { faces = null; facesTried = false },
                        ) else RetouchPanel(
                            rtool, onTool = { rtool = it }, rsize, onSize = { rsize = it },
                            sourceSet = cloneSrc != null, onNewSource = { cloneSrc = null; cloneOff = null },
                            canClear = state.marks.any { it is Patch }, onClear = { commit(state.copy(marks = state.marks.filterNot { it is Patch })) },
                            onFace = { rface = true },
                        )
                        else -> CutoutPanel(
                            state, found = mask != null, tried = maskTried,
                            onFind = { maskTried = false; mask = null },
                            onPick = { commit(state.copy(backdrop = it)) },
                            onBlur = { live = state.copy(backdrop = Backdrop.BLUR, backdropBlur = it) }, onBlurDone = { live?.let(::commit) },
                            onColor = { commit(state.copy(backdrop = Backdrop.COLOR, backdropColor = it)) },
                        )
                    } }
                }
            }
        }

        when (dialog) {
            1 -> CbDialog("SAVE", { dialog = 0 }) {
                val see = state.backdrop == Backdrop.TRANSPARENT && mask != null
                Choice(if (see) "SAVE AS COPY (PNG)" else "SAVE AS COPY", if (see) "A see-through background needs PNG. The original stays untouched." else "Keeps the original untouched.") { save(false) }
                if (!see && canReplace(item)) Choice("REPLACE ORIGINAL", "Overwrites this photo.") { save(true) }
                Row(Modifier.fillMaxWidth(), Arrangement.End) { ActionText("CANCEL", DarkCb.inkDim) { dialog = 0 } }
            }
            2 -> CbDialog("DISCARD EDITS?", { dialog = 0 }) {
                Text("Your changes will be lost.", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = DarkCb.ink2))
                Row(Modifier.fillMaxWidth(), Arrangement.End) {
                    ActionText("KEEP EDITING", DarkCb.inkDim) { dialog = 0 }
                    ActionText("DISCARD", Danger) { onClose() }
                }
            }
            3 -> CbDialog("RESET ALL EDITS?", { dialog = 0 }) {
                Text("The photo goes back to how it was. You can still press UNDO to get your edits back.", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = DarkCb.ink2))
                Row(Modifier.fillMaxWidth(), Arrangement.End) {
                    ActionText("CANCEL", DarkCb.inkDim) { dialog = 0 }
                    ActionText("RESET", Danger) { dialog = 0; selected = -1; textAt = null; aspect = Aspect.FREE; commit(EditState()) } // a step of its own, so UNDO brings the edits back
                }
            }
        }
        textAt?.let { p ->
            AddTextDialog(
                initial = "",
                onAdd = { words ->
                    val index = state.marks.size
                    commit(state.copy(marks = state.marks + Label(words, p.u, p.v, sizes.getValue(MarkTool.TEXT), mcolor)))
                    selected = index
                    textAt = null
                },
                onCancel = { textAt = null },
            )
        }
        if (editText && selMark is Label) {
            AddTextDialog(
                initial = selMark.text,
                onAdd = { words ->
                    commit(state.copy(marks = state.marks.mapIndexed { i, m -> if (i == selected && m is Label) m.copy(text = words) else m }))
                    editText = false
                },
                onCancel = { editText = false },
            )
        }
        BusyOverlay(saving || enhancing, if (saving) "SAVING…" else "ENHANCING…", null)
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
private fun EditCanvas(
    bmp: Bitmap, st: EditState, cropMode: Boolean, lock: Aspect, onCrop: (Crop) -> Unit, onCropDone: () -> Unit, onePan: Boolean,
    overlay: (@Composable () -> Unit)? = null, mask: Bitmap? = null, faces: Bitmap? = null,
) {
    val shader = remember { EditShader() }
    val density = LocalDensity.current
    // the view: two fingers pinch and move it in every tool (one finger keeps drawing or dragging handles); it never touches the picture's edits
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var fitTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(fitTick) {
        if (fitTick > 0) {
            val z0 = zoom; val p0 = pan
            animate(0f, 1f, animationSpec = tween(MOTION_MS)) { t, _ -> zoom = z0 + (1f - z0) * t; pan = p0 * (1f - t) }
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().padding(8.dp).clipToBounds().pointerInput(onePan) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val down = event.changes.count { it.pressed }
                    val moving = down >= 2 || (down == 1 && onePan && zoom > 1.01f)
                    if (moving) {
                        val dz = if (down >= 2) event.calculateZoom() else 1f
                        val dp = event.calculatePan()
                        val centre = if (down >= 2) event.calculateCentroid() else Offset.Zero
                        if (dz != 1f || dp != Offset.Zero) {
                            val newZoom = (zoom * dz).coerceIn(1f, 6f)
                            val k = newZoom / zoom
                            val half = Offset(size.width / 2f, size.height / 2f)
                            val cc = if (down >= 2) centre - half else Offset.Zero
                            // the point under the fingers stays under them while the zoom changes
                            val next = cc - (cc - pan) * k + dp
                            val limit = Offset((newZoom - 1f) * size.width / 2f, (newZoom - 1f) * size.height / 2f)
                            zoom = newZoom
                            pan = Offset(next.x.coerceIn(-limit.x, limit.x), next.y.coerceIn(-limit.y, limit.y))
                            event.changes.forEach { it.consume() }
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        },
    ) {
        val (cw, ch) = frameSize(bmp.width, bmp.height, st.turns)
        val crop = if (cropMode) Crop() else st.crop
        val ratio = crop.w * cw / (crop.h * ch)
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val w = if (maxW / maxH > ratio) maxH * ratio else maxW
        val h = w / ratio
        Visible(zoom > 1.01f, Modifier.align(Alignment.BottomStart).padding(4.dp), enter = popEnter, exit = popExit) {
            Text("FIT", Modifier.background(Color(0x99000000)).clickable { fitTick++ }.padding(horizontal = 10.dp, vertical = 8.dp), style = monoStyle(DarkCb.mint, 1.5.sp))
        }
        Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() }).align(Alignment.Center).graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }) {
            Canvas(Modifier.fillMaxSize()) {
                if (mask != null && st.backdrop == Backdrop.TRANSPARENT) { // chequerboard shows what is see-through
                    val cell = 12.dp.toPx()
                    var y = 0f
                    var row = 0
                    while (y < size.height) {
                        var x = 0f
                        var col = row
                        while (x < size.width) {
                            drawRect(if (col % 2 == 0) Color(0xFF5A5F6E) else Color(0xFF3A3F4E), Offset(x, y), Size(cell, cell))
                            x += cell; col++
                        }
                        y += cell; row++
                    }
                }
                drawIntoCanvas { shader.draw(it.nativeCanvas, bmp, st, crop, size.width.roundToInt(), size.height.roundToInt(), mask, faces) }
            }
            if (cropMode) CropOverlay(st.crop, lock.lock(cw / ch), cw / ch, onCrop, onCropDone, Modifier.fillMaxSize())
            overlay?.invoke()
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
internal fun Chip(label: String, selected: Boolean = false, color: Color = DarkCb.inkDim, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clickable(onClick = onClick).background(glide(selected, DarkCb.surface3, Color.Transparent)).padding(horizontal = 10.dp, vertical = 8.dp),
        style = monoStyle(glide(selected, DarkCb.mint, color), 1.6.sp),
    )
}

@Composable
internal fun EditSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit, onDone: () -> Unit, onReset: () -> Unit) {
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
    st: EditState, which: Adjust, scope: Scope, found: Boolean, tried: Boolean, onScope: (Scope) -> Unit,
    onWhich: (Adjust) -> Unit, onMode: (Int) -> Unit, onChange: (Float) -> Unit, onDone: () -> Unit, onAuto: () -> Unit, onReset: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
            items(Scope.entries) { s -> Chip(s.label, scope == s) { onScope(s) } }
        }
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
            if (scope == Scope.ALL) {
                item { Chip("AUTO", color = DarkCb.mint, onClick = onAuto) }
                item { Chip("RESET", onClick = onReset) }
                item { Chip("LEVELS", color = DarkCb.ink) { onMode(ADV_LEVELS) } }
                item { Chip("CURVES", color = DarkCb.ink) { onMode(ADV_CURVES) } }
                item { Chip("COLOUR", color = DarkCb.ink) { onMode(ADV_COLOUR) } }
                items(Adjust.entries) { a -> Chip(a.label, which == a) { onWhich(a) } }
            } else {
                item { Chip("RESET", onClick = onReset) }
                items(ScopedAdjust) { a -> Chip(a.label, which == a) { onWhich(a) } }
            }
        }
        if (scope != Scope.ALL && !found) {
            if (tried) Text("NO SUBJECT FOUND IN THIS PICTURE.", Modifier.padding(horizontal = 16.dp), style = monoStyle(DarkCb.inkDim, 0.6.sp))
            else WorkingLine("FINDING THE SUBJECT...", null, DarkCb.inkDim)
        } else {
            val v = st.value(which, scope)
            EditSlider(which.label, v, which.min..1f, "%+d".format((v * 100).roundToInt()), onChange, onDone) { onChange(0f); onDone() }
        }
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
