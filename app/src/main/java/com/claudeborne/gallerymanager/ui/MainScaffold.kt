package com.claudeborne.gallerymanager.ui

import android.app.Activity
import android.content.ContentUris
import android.net.Uri
import androidx.activity.compose.BackHandler
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

private const val NO_OVERLAY = 0
private const val TRASH = 1
private const val SETTINGS = 2

/** [openUri] is set when another app opened a photo/video with us ("Open with"); closing the viewer then returns to that app. */
@Composable
fun MainScaffold(items: List<MediaItem>, trash: List<MediaItem>, openUri: Uri?, onChanged: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainPager = rememberPagerState { mainTabs.size } // Gallery <-> Files
    val galleryPager = rememberPagerState { galleryTabs.size } // Images <-> Videos <-> Albums
    var overlay by rememberSaveable { mutableIntStateOf(NO_OVERLAY) } // trash or settings, shown over the tabs
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
    val shortcuts = remember { Shortcuts(onTrash = { overlay = TRASH }, onSettings = { overlay = SETTINGS }) }

    BackHandler(enabled = overlay != NO_OVERLAY) { overlay = NO_OVERLAY }
    BackHandler(enabled = album != null && overlay == NO_OVERLAY) { openAlbum = null }
    BackHandler(enabled = viewerId != null) { closeViewer() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().cbGrid(cb).statusBarsPadding()) {
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalShortcuts provides shortcuts) {
                    HorizontalPager(mainPager, Modifier.fillMaxSize(), userScrollEnabled = !(mainPager.currentPage == 1 && filesDeep)) { page ->
                        if (page == 0) {
                            HorizontalPager(galleryPager, Modifier.fillMaxSize()) { sub ->
                                val subTabs: @Composable () -> Unit = { SubTabs(galleryPager) }
                                when {
                                    sub == 0 -> PhotosScreen(images, "IMAGES", tools, onChanged, onCollage = { collage = it }, onJoin = { join = it }, gridState = imagesGrid, empty = "NO IMAGES", belowHeader = subTabs, onOpen = openFrom(FROM_IMAGES))
                                    sub == 1 -> PhotosScreen(videos, "VIDEOS", tools, onChanged, onCollage = { collage = it }, onJoin = { join = it }, gridState = videosGrid, empty = "NO VIDEOS", belowHeader = subTabs, onOpen = openFrom(FROM_VIDEOS))
                                    album != null -> key(album) {
                                        val title = if (album == FAVORITES_ID) "FAVORITES" else inAlbum.firstOrNull()?.bucketName?.uppercase() ?: "ALBUM"
                                        PhotosScreen(inAlbum, title, tools, onChanged, onCollage = { collage = it }, onJoin = { join = it }, onBack = { openAlbum = null }, onOpen = openFrom(FROM_ALBUM))
                                    }
                                    else -> AlbumsScreen(items, favorites.ids, tools, onChanged, subTabs) { openAlbum = it }
                                }
                            }
                        } else {
                            FilesScreen(items, backEnabled = viewerId == null && editing == null && collage == null && join == null && overlay == NO_OVERLAY, onChanged, onDeep = { filesDeep = it }, onOpenMedia = openFrom(FROM_LIBRARY))
                        }
                    }
                }
                if (overlay != NO_OVERLAY) {
                    // an opaque layer over the tabs; the clickable swallows taps so nothing behind it reacts
                    Box(Modifier.fillMaxSize().cbGrid(cb).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
                        if (overlay == TRASH) TrashScreen(trash, onChanged, onBack = { overlay = NO_OVERLAY })
                        else SettingsScreen(favorites, onBack = { overlay = NO_OVERLAY })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(cb.surface1).navigationBarsPadding()) {
                mainTabs.forEachIndexed { i, label ->
                    TabButton(label, selected = mainPager.currentPage == i) {
                        overlay = NO_OVERLAY
                        if (i == 0) openAlbum = null
                        scope.launch { mainPager.animateScrollToPage(i) }
                    }
                }
            }
        }

        viewerId?.let { id ->
            Viewer(viewerItems, id, tools, favorites, onPageChange = { viewerId = it }, onChanged = onChanged, onEdit = { editing = it }, onClose = closeViewer)
        }
        join?.let { JoinScreen(it, onClose = { join = null }, onSaved = { join = null; onChanged() }) }
        collage?.let { CollageScreen(it, onClose = { collage = null }, onSaved = { collage = null; onChanged() }) }
        editing?.let { if (it.isVideo) VideoEditor(it, onClose = { editing = null }, onSaved = { editing = null; onChanged() }, onLibraryChanged = onChanged) else Editor(it, tools, onClose = { editing = null }, onSaved = { editing = null; onChanged() }) }
        FileToolDialogs(tools)
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
                        color = if (selected) cb.mint else cb.inkDim,
                    ),
                )
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) cb.grad else SolidColor(cb.line)))
            }
        }
    }
}

@Composable
private fun RowScope.TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val cb = LocalCb.current
    Column(Modifier.weight(1f).height(52.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) cb.grad else SolidColor(cb.line)))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = TextStyle(
                    fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.2.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) cb.mint else cb.inkDim,
                ),
            )
        }
    }
}
