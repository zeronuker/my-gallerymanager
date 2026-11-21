package com.claudeborne.gallerymanager.ui

import android.app.Activity
import android.content.ContentUris
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.graphicsLayer
import com.claudeborne.gallerymanager.data.Settings
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.abs
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.data.EXTERNAL_ID
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.data.externalItem
import kotlinx.coroutines.launch

private val mainTabs = listOf("GALLERY", "FILES")
private val galleryTabs = listOf("IMAGES", "VIDEOS", "ALBUMS")

// which list the viewer pages through (it swipes through whatever the user opened it from)
private const val FROM_IMAGES = 0
private const val FROM_VIDEOS = 1
private const val FROM_ALBUM = 2
private const val FROM_LIBRARY = 3 // Files, or another app opening one file

/** [openUri] is set when another app opened a photo/video with us ("Open with"); closing the viewer then returns to that app. */
@Composable
fun MainScaffold(items: List<MediaItem>, trash: List<MediaItem>, openUri: Uri?, onChanged: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainPager = rememberPagerState { mainTabs.size } // Gallery <-> Files
    val galleryPager = rememberPagerState { galleryTabs.size } // Images <-> Videos <-> Albums
    var showSettings by rememberSaveable { mutableStateOf(false) } // settings, shown over the tabs
    val settingsPos = remember { Animatable(0f) } // how much of the Settings window shows, 0 closed to 1 open; it follows the finger while the bar is dragged
    val settingsVisible by remember { derivedStateOf { settingsPos.value > 0f } }
    LaunchedEffect(showSettings) { settingsPos.animateTo(if (showSettings) 1f else 0f, tween(if (showSettings) MOTION_MS else MOTION_FAST_MS)) }
    var openAlbum by rememberSaveable { mutableStateOf<Long?>(null) }
    var viewerFrom by rememberSaveable { mutableIntStateOf(FROM_LIBRARY) }
    var filesList by remember { mutableStateOf<List<MediaItem>?>(null) } // what the Files screen was showing when it opened the viewer
    var viewerId by rememberSaveable {
        // MediaStore uris map to a library item; anything else (file manager, downloads) gets a single-item viewer
        mutableStateOf(openUri?.let { u -> (if (u.authority == "media") runCatching { ContentUris.parseId(u) }.getOrNull() else null) ?: EXTERNAL_ID })
    }
    // kept here so each list's scroll position survives tab switches
    val imagesGrid = rememberLazyGridState()
    val videosGrid = rememberLazyGridState()
    val tools = rememberFileTools(items, onChanged)
    var pickerRequest by remember { mutableStateOf<PickerRequest?>(null) } // the one file and folder picker, shown full-screen over everything
    tools.openPicker = { pickerRequest = it }
    var editing by remember { mutableStateOf<MediaItem?>(null) }
    var collage by remember { mutableStateOf<List<MediaItem>?>(null) }
    var join by remember { mutableStateOf<List<MediaItem>?>(null) }
    var filesDeep by remember { mutableStateOf(false) } // a Files tool (map, dual pane...) is open, so sideways swipes belong to it

    val album = openAlbum
    val favorites = rememberFavorites()
    val images = remember(items) { items.filter { !it.isVideo } }
    val videos = remember(items) { items.filter { it.isVideo } }
    val inAlbum = remember(items, album, favorites.ids) {
        when (album) {
            null -> emptyList()
            FAVORITES_ID -> items.filter { it.id in favorites.ids }
            else -> items.filter { it.bucketId == album }
        }
    }
    val external = remember(openUri) { openUri?.let { externalItem(context, it) } }
    val viewerItems = when {
        viewerId == EXTERNAL_ID -> listOfNotNull(external)
        viewerFrom == FROM_IMAGES -> images
        viewerFrom == FROM_VIDEOS -> videos
        viewerFrom == FROM_ALBUM -> inAlbum
        else -> filesList?.let { l -> val live = items.mapTo(HashSet()) { it.id }; l.filter { it.id in live } } ?: items // trashed items drop out
    }
    fun openFrom(from: Int) = { m: MediaItem -> viewerFrom = from; viewerId = m.id }
    val closeViewer = { viewerId = null; if (openUri != null) (context as? Activity)?.finish() }

    BackHandler(enabled = album != null && !showSettings) { openAlbum = null }
    BackHandler(enabled = viewerId != null) { closeViewer() }
    BackHandler(enabled = showSettings) { showSettings = false } // last: Settings can open over the viewer, so Back closes it first

    val focusManager = LocalFocusManager.current
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }) { // a tap that nothing else took: the text field loses focus and the keyboard closes
        val headerInfo = remember { HeaderInfo() }
        val pillHost = remember { PillHost() }
        val glass = rememberGlassState()
        // the pill floats over the tabs, so lists scroll behind it and leave room at their end; a Files tool (map, dual pane...) keeps a strip of its own instead
        val pillRoom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + PillGap + PillHeight + 12.dp
        val toolOpen = mainPager.currentPage == 1 && filesDeep
        val toolRoom by animateDpAsState(if (toolOpen) pillRoom else 0.dp, tween(MOTION_MS), label = "toolRoom")
        Column(Modifier.fillMaxSize().cbGrid(cb).statusBarsPadding()) {
            CompositionLocalProvider(LocalHeaderInfo provides headerInfo, LocalPillHost provides pillHost, LocalPillInset provides (if (toolOpen) 0.dp else pillRoom)) {
                // the header stays put while the pages swipe under it; a Files tool that draws its own header takes its place
                Visible(!(mainPager.currentPage == 1 && filesDeep), enter = expandEnter, exit = shrinkExit) {
                    HeaderBar(headerInfo.title, headerInfo.count, headerInfo.onBack, headerInfo.backLabel, headerInfo.trailing)
                }
                Box(Modifier.weight(1f).padding(bottom = toolRoom).glassSource(glass)) {
                    HorizontalPager(mainPager, Modifier.fillMaxSize(), userScrollEnabled = !(mainPager.currentPage == 1 && filesDeep)) { page ->
                        if (page == 0) {
                            // the sub tabs float over the top of the photos, which scroll behind them (they blur through a source of their own)
                            Box(Modifier.fillMaxSize()) {
                                val topGlass = rememberGlassState()
                                CompositionLocalProvider(LocalTopInset provides TopPillRoom) {
                                HorizontalPager(galleryPager, Modifier.fillMaxSize().glassSource(topGlass)) { sub ->
                                    CompositionLocalProvider(LocalPageActive provides (mainPager.currentPage == 0 && galleryPager.currentPage == sub)) {
                                        when {
                                            sub == 0 -> PhotosScreen(images, "IMAGES", tools, onChanged, onCollage = { collage = it }, onJoin = { join = it }, gridState = imagesGrid, empty = "NO IMAGES", onOpen = openFrom(FROM_IMAGES))
                                            sub == 1 -> PhotosScreen(videos, "VIDEOS", tools, onChanged, onCollage = { collage = it }, onJoin = { join = it }, gridState = videosGrid, empty = "NO VIDEOS", onOpen = openFrom(FROM_VIDEOS))
                                            // opening an album slides it in over the albums list; closing slides it back out
                                            else -> AnimatedContent(
                                                album,
                                                transitionSpec = { if (targetState != null) pushEnter togetherWith fadeOut(tween(MOTION_FAST_MS)) else fadeIn(tween(MOTION_MS)) togetherWith pushExit },
                                                label = "album",
                                            ) { a ->
                                                if (a != null) key(a) {
                                                    val title = if (a == FAVORITES_ID) "FAVORITES" else inAlbum.firstOrNull()?.bucketName?.uppercase() ?: "ALBUM"
                                                    PhotosScreen(inAlbum, title, tools, onChanged, onCollage = { collage = it }, onJoin = { join = it }, onBack = { openAlbum = null }, onOpen = openFrom(FROM_ALBUM))
                                                } else AlbumsScreen(items, favorites.ids, tools, onChanged) { openAlbum = it }
                                            }
                                        }
                                    }
                                }
                                }
                                SubTabs(galleryPager, topGlass, Modifier.align(Alignment.TopCenter))
                            }
                        } else {
                            CompositionLocalProvider(LocalPageActive provides (mainPager.currentPage == 1)) {
                                FilesScreen(items, trash, backEnabled = viewerId == null && editing == null && collage == null && join == null && !showSettings, onChanged, onDeep = { filesDeep = it }, onOpenMedia = { m, list -> filesList = list; viewerFrom = FROM_LIBRARY; viewerId = m.id }, openPicker = { pickerRequest = it })
                            }
                        }
                    }
                }
            }
        }
        FloatingPill(mainPager, pillHost.actions, glass, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = PillGap)) { i ->
            showSettings = false
            if (i == 0) openAlbum = null
            scope.launch { mainPager.animateScrollToPage(i, animationSpec = glideSpring()) }
        }

        AnimatedOverlay(viewerId) { id ->
            Viewer(viewerItems, id, tools, favorites, onPageChange = { viewerId = it }, onChanged = onChanged, onEdit = { editing = it }, onClose = closeViewer)
        }
        AnimatedOverlay(join) { JoinScreen(it, onClose = { join = null }, onSaved = { join = null; onChanged() }) }
        AnimatedOverlay(collage) { CollageScreen(it, onClose = { collage = null }, onSaved = { collage = null; onChanged() }) }
        AnimatedOverlay(editing) { if (it.isVideo) VideoEditor(it, onClose = { editing = null }, onSaved = { editing = null; onChanged() }, onLibraryChanged = onChanged) else Editor(it, tools, onClose = { editing = null }, onSaved = { editing = null; onChanged() }) }
        FileToolDialogs(tools)
        // a full screen over everything, the viewer included; it slides in from the edge the bar is on and out again, and follows the finger while
        // the bar is dragged. The clickable swallows taps so nothing behind it reacts.
        if (settingsVisible) {
            val shelfRight = Settings.shelfEdge == 1
            Box(
                Modifier.fillMaxSize().graphicsLayer { translationX = (1f - settingsPos.value) * size.width * (if (shelfRight) 1f else -1f) }
                    .cbGrid(cb).statusBarsPadding().navigationBarsPadding().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                SettingsScreen(favorites, onBack = { showSettings = false }, openPicker = { pickerRequest = it })
            }
        }
        // the one bar, over the tabs, the viewer, the Files tools and Settings; the editors cover it, since opening Settings mid-edit could lose work.
        // It never swaps for another copy, so it does not jump: it brightens and changes length while the window moves.
        val shelfCovered = editing != null || collage != null || join != null
        AnimatedVisibility(!shelfCovered || showSettings, Modifier.fillMaxSize(), enter = fadeIn(tween(MOTION_MS)), exit = fadeOut(tween(MOTION_FAST_MS))) {
            Shelf(
                open = showSettings, moving = settingsPos.isRunning,
                onDrag = { f -> scope.launch { settingsPos.snapTo(f) } },
                onRelease = { go ->
                    if (go) showSettings = !showSettings // the effect above finishes the slide from where the finger left it
                    else scope.launch { settingsPos.animateTo(if (showSettings) 1f else 0f, tween(MOTION_FAST_MS)) } // not far enough: back to where it was
                },
                onToggle = { showSettings = !showSettings },
            )
        }
        // last, so it covers everything, Settings and the viewer included
        AnimatedOverlay(pickerRequest) { r ->
            Picker(
                r.title, r.pickFiles, r.confirmLabel, r.roots, r.shortcuts, r.limit, r.hint,
                onCancel = { pickerRequest = null },
                onFolder = { pickerRequest = null; r.onFolder(it) },
                onFiles = { pickerRequest = null; r.onFiles(it) },
            )
        }
    }
}

/** How much [i] is the tab on show: 1 at its own page, 0 a page away, and in between while swiping, so lines and colours follow the finger. */
private fun PagerState.near(i: Int): Float = (1f - abs(currentPage + currentPageOffsetFraction - i)).coerceIn(0f, 1f)

private fun tabStyle(k: Float, mint: Color, dim: Color) = TextStyle(
    fontFamily = JetBrainsMono, fontSize = 14.sp, letterSpacing = 2.2.sp, textAlign = TextAlign.Center,
    fontWeight = if (k > 0.5f) FontWeight.Bold else FontWeight.Normal, color = lerp(dim, mint, k),
)

/** Equal tabs with a mint highlight that slides with the pager; tapping one calls [onSelect]. */
@Composable
private fun PillTabs(pager: PagerState, labels: List<String>, modifier: Modifier, onSelect: (Int) -> Unit) {
    val cb = LocalCb.current
    Box(modifier.padding(4.dp)) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(1f / labels.size)
                .graphicsLayer { translationX = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, labels.size - 1f) * size.width }
                .clip(RoundedCornerShape(50)).background(cb.mint.copy(alpha = 0.14f)),
        )
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                Box(Modifier.weight(1f).fillMaxHeight().pressClickable(0.94f) { onSelect(i) }, Alignment.Center) {
                    Text(label, style = tabStyle(pager.near(i), cb.mint, cb.inkDim).copy(fontSize = 12.sp, letterSpacing = 1.8.sp))
                }
            }
        }
    }
}

/** Images / Videos / Albums as a frosted capsule across the top of the photos; tapping one slides to it, and swiping the page does the same. */
@Composable
private fun SubTabs(pager: PagerState, glass: GlassState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    GlassPill(glass, modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
        PillTabs(pager, galleryTabs, Modifier.fillMaxWidth().height(TopPillHeight)) { i -> scope.launch { pager.animateScrollToPage(i, animationSpec = glideSpring()) } }
    }
}

/**
 * Gallery / Files as a floating frosted capsule over the bottom of the tabs; the mint highlight slides with the swipe. While a screen has selection
 * [actions] it turns into them (stretching to fit) and back again when the selection ends.
 */
@Composable
private fun FloatingPill(pager: PagerState, actions: List<BarItem>?, glass: GlassState, modifier: Modifier, onSelect: (Int) -> Unit) {
    val cb = LocalCb.current
    var last by remember { mutableStateOf(actions ?: emptyList()) }
    if (actions != null) last = actions // kept while the pill shrinks back, so the labels do not vanish first
    GlassPill(glass, modifier) {
        AnimatedContent(
            actions != null,
            transitionSpec = {
                (fadeIn(tween(MOTION_MS)) + scaleIn(spring(0.7f, 400f), initialScale = 0.92f)) togetherWith fadeOut(tween(MOTION_FAST_MS)) using SizeTransform(clip = false) { _, _ -> spring(0.75f, 380f) }
            },
            label = "pill",
        ) { acting ->
            if (acting) {
                val maxWidth = LocalConfiguration.current.screenWidthDp.dp - 32.dp
                Row(Modifier.height(PillHeight).widthIn(max = maxWidth).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    last.forEach { a ->
                        Box(Modifier.height(PillHeight).pressClickable(0.9f, a.onClick).padding(horizontal = 14.dp), Alignment.Center) {
                            Crossfade(a.label, animationSpec = tween(MOTION_FAST_MS), label = "pillLabel") { l -> Text(l, style = monoStyle(a.color, 0.6.sp)) }
                        }
                    }
                }
            } else {
                PillTabs(pager, mainTabs, Modifier.width(232.dp).height(PillHeight), onSelect)
            }
        }
    }
}
