package com.claudeborne.gallerymanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.data.MediaItem

private val tabs = listOf("PHOTOS", "ALBUMS")

@Composable
fun MainScaffold(items: List<MediaItem>) {
    val cb = LocalCb.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openAlbum by rememberSaveable { mutableStateOf<Long?>(null) }
    val photosGrid = rememberLazyGridState() // kept here so the Photos scroll position survives tab switches

    BackHandler(enabled = openAlbum != null) { openAlbum = null }

    Column(Modifier.fillMaxSize().cbGrid(cb).statusBarsPadding()) {
        Box(Modifier.weight(1f)) {
            val album = openAlbum
            when {
                tab == 0 -> PhotosScreen(items, "PHOTOS", photosGrid)
                album != null -> {
                    val inAlbum = remember(items, album) { items.filter { it.bucketId == album } }
                    key(album) {
                        PhotosScreen(inAlbum, inAlbum.firstOrNull()?.bucketName?.uppercase() ?: "ALBUM", onBack = { openAlbum = null })
                    }
                }
                else -> AlbumsScreen(items) { openAlbum = it }
            }
        }
        Row(Modifier.fillMaxWidth().background(cb.surface1).navigationBarsPadding()) {
            tabs.forEachIndexed { i, label -> TabButton(label, selected = tab == i) { tab = i; openAlbum = null } }
        }
    }
}

@Composable
private fun RowScope.TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val cb = LocalCb.current
    Column(Modifier.weight(1f).height(52.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) cb.grad else androidx.compose.ui.graphics.SolidColor(cb.line)))
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
