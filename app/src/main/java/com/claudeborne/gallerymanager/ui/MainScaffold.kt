package com.claudeborne.gallerymanager.ui

import android.app.Activity
import android.content.ContentUris
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.claudeborne.gallerymanager.data.EXTERNAL_ID
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.data.externalItem

private val tabs = listOf("PHOTOS", "ALBUMS", "TRASH", "SETTINGS")

/** [openUri] is set when another app opened a photo/video with us ("Open with"); closing the viewer then returns to that app. */
@Composable
fun MainScaffold(items: List<MediaItem>, trash: List<MediaItem>, openUri: Uri?, onChanged: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openAlbum by rememberSaveable { mutableStateOf<Long?>(null) }
    var viewerId by rememberSaveable {
        // MediaStore uris map to a library item; anything else (file manager, downloads) gets a single-item viewer
        mutableStateOf(openUri?.let { u -> (if (u.authority == "media") runCatching { ContentUris.parseId(u) }.getOrNull() else null) ?: EXTERNAL_ID })
    }
    val photosGrid = rememberLazyGridState() // kept here so the Photos scroll position survives tab switches
    val tools = rememberFileTools(items, onChanged)
    var editing by remember { mutableStateOf<MediaItem?>(null) }
    var collage by remember { mutableStateOf<List<MediaItem>?>(null) }
    var join by remember { mutableStateOf<List<MediaItem>?>(null) }

    val album = openAlbum
    val favorites = rememberFavorites()
    val inAlbum = remember(items, album, favorites.ids) {
        when (album) {
            null -> emptyList()
            FAVORITES_ID -> items.filter { it.id in favorites.ids }
            else -> items.filter { it.bucketId == album }
        }
    }
    val external = remember(openUri) { openUri?.let { externalItem(context, it) } }
    // the viewer swipes through whatever list the user opened it from
    val viewerItems = when {
        viewerId == EXTERNAL_ID -> listOfNotNull(external)
        tab == 0 -> items
        else -> inAlbum
    }
    val closeViewer = { viewerId = null; if (openUri != null) (context as? Activity)?.finish() }

    BackHandler(enabled = album != null) { openAlbum = null }
    BackHandler(enabled = viewerId != null) { closeViewer() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().cbGrid(cb).statusBarsPadding()) {
            Box(Modifier.weight(1f)) {
                when {
                    tab == 0 -> PhotosScreen(items, "PHOTOS", tools, onChanged, onCollage = { collage = it }, onJoin = { join = it }, gridState = photosGrid) { viewerId = it.id }
                    tab == 2 -> TrashScreen(trash, onChanged)
                    tab == 3 -> SettingsScreen(favorites)
                    album != null -> key(album) {
                        val title = if (album == FAVORITES_ID) "FAVORITES" else inAlbum.firstOrNull()?.bucketName?.uppercase() ?: "ALBUM"
                        PhotosScreen(inAlbum, title, tools, onChanged, onCollage = { collage = it }, onJoin = { join = it }, onBack = { openAlbum = null }) { viewerId = it.id }
                    }
                    else -> AlbumsScreen(items, favorites.ids) { openAlbum = it }
                }
            }
            Row(Modifier.fillMaxWidth().background(cb.surface1).navigationBarsPadding()) {
                tabs.forEachIndexed { i, label -> TabButton(label, selected = tab == i) { tab = i; openAlbum = null } }
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
