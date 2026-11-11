package com.claudeborne.gallerymanager.ui

import android.app.Activity
import android.content.ContentUris
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.RowScope
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
    var openAlbum by rememberSaveable { mutableStateOf<Long?>(null) }
    var viewerFrom by rememberSaveable { mutableIntStateOf(FROM_LIBRARY) }
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
        else -> items
    }
    fun openFrom(from: Int) = { m: MediaItem -> viewerFrom = from; viewerId = m.id }
    val closeViewer = { viewerId = null; if (openUri != null) (context as? Activity)?.finish() }

    BackHandler(enabled = album != null && !showSettings) { openAlbum = null }
    BackHandler(enabled = viewerId != null) { closeViewer() }
    BackHandler(enabled = showSettings) { showSettings = false } // last: Settings can open over the viewer, so Back closes it first

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().cbGrid(cb).statusBarsPadding()) {
            Box(Modifier.weight(1f)) {
                HorizontalPager(mainPager, Modifier.fillMaxSize(), userScrollEnabled = !(mainPager.currentPage == 1 && filesDeep)) { page ->
                    if (page == 0) {
                        HorizontalPager(galleryPager, Modifier.fillMaxSize()) { sub ->
                            val subTabs: @Composable () -> Unit = { SubTabs(galleryPager) }
                            when {
                                sub == 0 -> PhotosScreen(images, "IMAGES", tools, onChanged, onCollage = { collage = it }, onJoin = { join = it }, gridState = imagesGrid, empty = "NO IMAGES", belowHeader = subTabs, onOpen = openFrom(FROM_IMAGES))
                                sub == 1 -> PhotosScreen(videos, "VIDEOS", tools, onChanged, onCollage = { collage = it }, onJoin = { join = it }, gridState = videosGrid, empty = "NO VIDEOS", belowHeader = subTabs, onOpen = openFrom(FROM_VIDEOS))
                                // opening an album slides it in over the albums list; closing slides it back out
                                else -> AnimatedContent(
                                    album,
                                    transitionSpec = { if (targetState != null) pushEnter togetherWith fadeOut(tween(MOTION_FAST_MS)) else fadeIn(tween(MOTION_MS)) togetherWith pushExit },
                                    label = "album",
                                ) { a ->
                                    if (a != null) key(a) {
                                        val title = if (a == FAVORITES_ID) "FAVORITES" else inAlbum.firstOrNull()?.bucketName?.uppercase() ?: "ALBUM"
                                        PhotosScreen(inAlbum, title, tools, onChanged, onCollage = { collage = it }, onJoin = { join = it }, onBack = { openAlbum = null }, onOpen = openFrom(FROM_ALBUM))
                                    } else AlbumsScreen(items, favorites.ids, tools, onChanged, subTabs) { openAlbum = it }
                                }
                            }
                        }
                    } else {
                        FilesScreen(items, trash, backEnabled = viewerId == null && editing == null && collage == null && join == null && !showSettings, onChanged, onDeep = { filesDeep = it }, onOpenMedia = openFrom(FROM_LIBRARY), openPicker = { pickerRequest = it })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(cb.surface1).navigationBarsPadding()) {
                mainTabs.forEachIndexed { i, label ->
                    TabButton(label, selected = mainPager.currentPage == i) {
                        showSettings = false
                        if (i == 0) openAlbum = null
                        scope.launch { mainPager.animateScrollToPage(i) }
                    }
                }
            }
        }

        AnimatedOverlay(viewerId) { id ->
            Viewer(viewerItems, id, tools, favorites, onPageChange = { viewerId = it }, onChanged = onChanged, onEdit = { editing = it }, onClose = closeViewer)
        }
        // the shelf is over the tabs, the viewer and the Files tools; the editors below cover it, since opening Settings mid-edit could lose work
        AnimatedVisibility(!showSettings, Modifier.fillMaxSize(), enter = fadeIn(tween(MOTION_MS)), exit = fadeOut(tween(MOTION_FAST_MS))) {
            Shelf(onOpen = { showSettings = true })
        }
        AnimatedOverlay(join) { JoinScreen(it, onClose = { join = null }, onSaved = { join = null; onChanged() }) }
        AnimatedOverlay(collage) { CollageScreen(it, onClose = { collage = null }, onSaved = { collage = null; onChanged() }) }
        AnimatedOverlay(editing) { if (it.isVideo) VideoEditor(it, onClose = { editing = null }, onSaved = { editing = null; onChanged() }, onLibraryChanged = onChanged) else Editor(it, tools, onClose = { editing = null }, onSaved = { editing = null; onChanged() }) }
        FileToolDialogs(tools)
        // a full screen over everything, the viewer included; the clickable swallows taps so nothing behind it reacts
        AnimatedVisibility(showSettings, Modifier.fillMaxSize(), enter = overlayEnter, exit = overlayExit) {
            Box(Modifier.fillMaxSize().cbGrid(cb).statusBarsPadding().navigationBarsPadding().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
                SettingsScreen(favorites, onBack = { showSettings = false }, openPicker = { pickerRequest = it })
            }
        }
        // while Settings is open the real bar shows over it, so the size and position sliders can be judged
        if (showSettings) Shelf(preview = true)
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

/** Images / Videos / Albums, under the header; tapping one slides to it, and swiping the page does the same. */
@Composable
private fun SubTabs(pager: PagerState) {
    val cb = LocalCb.current
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth()) {
        galleryTabs.forEachIndexed { i, label ->
            val selected = pager.currentPage == i
            Column(Modifier.weight(1f).clickable { scope.launch { pager.animateScrollToPage(i) } }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label, Modifier.padding(vertical = 10.dp),
                    style = TextStyle(
                        fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.2.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = glide(selected, cb.mint, cb.inkDim),
                    ),
                )
                TabUnderline(selected)
            }
        }
    }
}

@Composable
private fun RowScope.TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val cb = LocalCb.current
    Column(Modifier.weight(1f).height(52.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        TabUnderline(selected)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = TextStyle(
                    fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.2.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = glide(selected, cb.mint, cb.inkDim),
                ),
            )
        }
    }
}
