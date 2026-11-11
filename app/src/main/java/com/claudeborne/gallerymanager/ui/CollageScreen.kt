package com.claudeborne.gallerymanager.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.collage.CellView
import com.claudeborne.gallerymanager.collage.CollageLayout
import com.claudeborne.gallerymanager.collage.CollageRatio
import com.claudeborne.gallerymanager.collage.CollageSpec
import com.claudeborne.gallerymanager.collage.cellRects
import com.claudeborne.gallerymanager.collage.clampView
import com.claudeborne.gallerymanager.collage.drawCollage
import com.claudeborne.gallerymanager.collage.layoutsFor
import com.claudeborne.gallerymanager.collage.renderCollage
import com.claudeborne.gallerymanager.collage.saveCollage
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.data.Settings
import com.claudeborne.gallerymanager.edit.loadBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val CollageBackgrounds = MarkPalette + 0xFF0A1020.toInt()

/** Puts [items] (2 to 9 photos) into a collage: pick a layout, shape and style, move each photo around its cell, then save. */
@Composable
fun CollageScreen(items: List<MediaItem>, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val n = items.size

    var photos by remember { mutableStateOf<List<Bitmap>?>(null) }
    LaunchedEffect(items) { photos = items.map { loadBitmap(context, it.uri, 2048) } }

    val layouts = remember(n) { layoutsFor(n) }
    var layoutIdx by remember { mutableIntStateOf(0) }
    var ratio by remember { mutableStateOf(CollageRatio.SQUARE) }
    var gap by remember { mutableFloatStateOf(0.012f) }
    var border by remember { mutableFloatStateOf(0.012f) }
    var radius by remember { mutableFloatStateOf(0f) }
    var background by remember { mutableIntStateOf(CollageBackgrounds[0]) }
    val order = remember { mutableStateListOf<Int>().apply { addAll(0 until n) } } // cell -> photo
    val views = remember { mutableStateListOf<CellView>().apply { repeat(n) { add(CellView()) } } }
    var selected by remember { mutableIntStateOf(-1) }
    var swap by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var styleItem by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }

    val spec = CollageSpec(layouts[layoutIdx], ratio, gap, border, radius, background)
    BackHandler { onClose() }

    fun save(p: List<Bitmap>) {
        saving = true
        val snapshot = spec
        val o = order.toList()
        val v = views.toList()
        scope.launch {
            val ok = runCatching { saveCollage(context, withContext(Dispatchers.Default) { renderCollage(snapshot, p, o, v) }) }.getOrDefault(false)
            saving = false
            Toast.makeText(context, if (ok) "Saved to Pictures/${Settings.collageFolder}" else "Couldn't save the collage", Toast.LENGTH_SHORT).show()
            if (ok) onSaved()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CloseButton { onClose() }
                Box(Modifier.weight(1f))
                ActionText("SAVE", DarkCb.mint) { photos?.let { save(it) } }
            }

            val p = photos
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (p == null) LoadingPane("LOADING PHOTOS", null, Modifier.align(Alignment.Center))
                else CollagePreview(
                    spec, p, order, views, selected,
                    onSelect = { i ->
                        if (swap && selected >= 0 && i >= 0 && i != selected) {
                            val a = order[selected]; order[selected] = order[i]; order[i] = a
                            val v = views[selected]; views[selected] = views[i]; views[i] = v
                            swap = false
                        }
                        selected = i
                    },
                )
                Box(Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                    Chip(if (swap) "TAP A PHOTO TO SWAP" else "SWAP", swap) { swap = !swap }
                }
            }

            Column(Modifier.fillMaxWidth().background(Panel).navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth()) {
                    listOf("LAYOUT", "STYLE").forEachIndexed { i, label ->
                        Column(Modifier.weight(1f).clickable { tab = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(2.dp).background(glide(tab == i, DarkCb.mint, Color.Transparent)))
                            Text(label, Modifier.padding(vertical = 12.dp), style = monoStyle(glide(tab == i, DarkCb.mint, DarkCb.inkDim), 2.2.sp))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(150.dp)) {
                    Crossfade(tab, animationSpec = tween(MOTION_FAST_MS), label = "collageTab") { tab -> when (tab) {
                        0 -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                itemsIndexed(layouts) { i, l ->
                                    LayoutThumb(l, i == layoutIdx) { layoutIdx = i; views.indices.forEach { views[it] = CellView() }; selected = -1 }
                                }
                            }
                            LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
                                items(CollageRatio.entries) { r -> Chip(r.label, ratio == r) { ratio = r } }
                            }
                        }
                        else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                            LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
                                itemsIndexed(listOf("SPACING", "BORDER", "CORNERS", "BACKGROUND")) { i, label -> Chip(label, styleItem == i) { styleItem = i } }
                            }
                            when (styleItem) {
                                0 -> StyleSlider("SPACING", gap, 0.05f) { gap = it }
                                1 -> StyleSlider("BORDER", border, 0.08f) { border = it }
                                2 -> StyleSlider("CORNERS", radius, 0.08f) { radius = it }
                                else -> LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    items(CollageBackgrounds) { c ->
                                        Box(
                                            Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                                                .border(if (c == background) 2.dp else 1.dp, glide(c == background, DarkCb.mint, Color(0x66FFFFFF)), CircleShape)
                                                .clickable { background = c },
                                        )
                                    }
                                }
                            }
                            Box(Modifier.height(4.dp))
                        }
                    } }
                }
            }
        }
        BusyOverlay(saving, "SAVING…", null)
    }
}

@Composable
private fun StyleSlider(label: String, value: Float, max: Float, onChange: (Float) -> Unit) {
    EditSlider(label, value, 0f..max, "${(value / max * 100).roundToInt()}", onChange, {}) { onChange(0f) }
}

@Composable
private fun CollagePreview(spec: CollageSpec, photos: List<Bitmap>, order: List<Int>, views: MutableList<CellView>, selected: Int, onSelect: (Int) -> Unit) {
    val density = LocalDensity.current
    val specNow by rememberUpdatedState(spec)
    val selectedNow by rememberUpdatedState(selected)
    val selectNow by rememberUpdatedState(onSelect)
    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
        val ratio = spec.ratio.w / spec.ratio.h
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val w = if (maxW / maxH > ratio) maxH * ratio else maxW
        val h = w / ratio
        Canvas(
            Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() }).align(Alignment.Center)
                .pointerInput(Unit) {
                    detectTapGestures { o -> selectNow(cellRects(specNow, size.width.toFloat(), size.height.toFloat()).indexOfFirst { it.contains(o.x, o.y) }) }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val rects = cellRects(specNow, size.width.toFloat(), size.height.toFloat())
                        val i = if (selectedNow >= 0) selectedNow else rects.indexOfFirst { it.contains(centroid.x, centroid.y) }
                        if (i >= 0) {
                            selectNow(i)
                            val v = views[i]
                            views[i] = clampView(rects[i], photos[order[i]], CellView((v.scale * zoom).coerceIn(1f, 4f), v.ox + pan.x / rects[i].width(), v.oy + pan.y / rects[i].height()))
                        }
                    }
                },
        ) {
            drawIntoCanvas { drawCollage(it.nativeCanvas, spec, photos, order, views, size.width.roundToInt(), size.height.roundToInt()) }
            cellRects(spec, size.width, size.height).getOrNull(selected)?.let { r ->
                drawRect(DarkCb.mint, Offset(r.left, r.top), Size(r.width(), r.height()), style = Stroke(2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun LayoutThumb(layout: CollageLayout, selected: Boolean, onClick: () -> Unit) {
    Canvas(
        Modifier.size(64.dp).background(DarkCb.surface2).border(if (selected) 2.dp else 1.dp, if (selected) DarkCb.mint else Color(0x33FFFFFF)).clickable(onClick = onClick).padding(6.dp),
    ) {
        val inset = 1.5.dp.toPx()
        layout.cells.forEach { c ->
            drawRect(if (selected) DarkCb.mint else DarkCb.inkDim, Offset(c.left * size.width + inset, c.top * size.height + inset), Size(c.width() * size.width - 2 * inset, c.height() * size.height - 2 * inset))
        }
    }
}
