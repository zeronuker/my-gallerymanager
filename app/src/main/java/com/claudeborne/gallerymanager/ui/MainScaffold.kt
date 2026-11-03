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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.claudeborne.gallerymanager.data.Kind
import com.claudeborne.gallerymanager.data.externalItem
import com.claudeborne.gallerymanager.data.kindOf
import com.claudeborne.gallerymanager.data.openExternally

private val tabs = listOf("PHOTOS", "ALBUMS", "FILES", "TOOLS", "TRASH")

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
    var filesPath by rememberSaveable { mutableStateOf<String?>(null) }
    val clip = remember { ClipState() }
    val tools = remember { ToolsState() }
    val toolsScope = rememberCoroutineScope()
    var fileView by remember { mutableStateOf<android.net.Uri?>(null) } // an image/video opened from the Files tab
    val photosGrid = rememberLazyGridState() // kept here so the Photos scroll position survives tab switches

    val album = openAlbum
    val inAlbum = remember(items, album) { album?.let { a -> items.filter { it.bucketId == a } }.orEmpty() }
    val external = remember(openUri, fileView) { (fileView ?: openUri)?.let { externalItem(context, it) } }
    // the viewer swipes through whatever list the user opened it from
    var viewerIds by remember { mutableStateOf(emptyList<Long>()) }
    var editItem by remember { mutableStateOf<MediaItem?>(null) }
    val isExternal = viewerId == EXTERNAL_ID
    val viewerItems = remember(items, viewerIds, isExternal, external) {
        if (isExternal) listOfNotNull(external)
        else if (viewerIds.isEmpty()) items // opened from another app: browse the whole library
        else { val byId = items.associateBy { it.id }; viewerIds.mapNotNull { byId[it] } }
    }
    val closeViewer = {
        viewerId = null
        if (fileView != null) fileView = null else if (openUri != null) (context as? Activity)?.finish()
    }

    BackHandler(enabled = album != null) { openAlbum = null }
    BackHandler(enabled = viewerId != null) { closeViewer() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().cbGrid(cb).statusBarsPadding()) {
            Box(Modifier.weight(1f)) {
                when {
                    tab == 0 -> PhotosScreen(items, "PHOTOS", photosGrid, onChanged = onChanged) { m, list -> viewerIds = list.map { it.id }; viewerId = m.id }
                    tab == 2 -> FilesScreen(
                        filesPath, { filesPath = it }, clip, onChanged = onChanged,
                        onOpen = { f ->
                            when (kindOf(f)) {
                                Kind.IMAGE, Kind.VIDEO -> { fileView = android.net.Uri.fromFile(f); viewerId = EXTERNAL_ID }
                                else -> openExternally(context, f)
                            }
                        },
                    )
                    tab == 3 -> ToolsScreen(tools, toolsScope, onChanged)
                    tab == 4 -> TrashHome(trash, onChanged)
                    album != null -> key(album) {
                        PhotosScreen(inAlbum, inAlbum.firstOrNull()?.bucketName?.uppercase() ?: "ALBUM", onBack = { openAlbum = null }, onChanged = onChanged) { m, list -> viewerIds = list.map { it.id }; viewerId = m.id }
                    }
                    else -> AlbumsScreen(items) { openAlbum = it }
                }
            }
            Row(Modifier.fillMaxWidth().background(cb.surface1).navigationBarsPadding()) {
                tabs.forEachIndexed { i, label -> TabButton(label, selected = tab == i) { tab = i; openAlbum = null } }
            }
        }

        viewerId?.let { id ->
            Viewer(viewerItems, id, onPageChange = { viewerId = it }, onChanged = onChanged, onEdit = { editItem = it }, onClose = closeViewer)
        }
        editItem?.let { m -> EditScreen(m, onClose = { editItem = null }, onSaved = { editItem = null; onChanged() }) }
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
                    fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.4.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) cb.mint else cb.inkDim,
                ),
            )
        }
    }
}
