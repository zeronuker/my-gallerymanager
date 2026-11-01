package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.claudeborne.gallerymanager.data.MediaItem

private class Album(val id: Long, val name: String, val cover: MediaItem, val count: Int)

@Composable
fun AlbumsScreen(items: List<MediaItem>, onOpen: (Long) -> Unit) {
    // items arrive newest first, so the first of each bucket is its cover and albums sort by recent activity
    val albums = remember(items) {
        items.groupBy { it.bucketId }.map { (id, list) ->
            Album(id, list.first().bucketName.ifEmpty { "Unknown" }, list.first(), list.size)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("ALBUMS", "${albums.size} ALBUMS")
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(albums, key = { it.id }) { AlbumCard(it) { onOpen(it.id) } }
        }
    }
}

@Composable
private fun AlbumCard(album: Album, onClick: () -> Unit) {
    val cb = LocalCb.current
    Column(Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            album.cover.uri, album.name,
            Modifier.fillMaxWidth().aspectRatio(1f).background(cb.surface2),
            contentScale = ContentScale.Crop,
        )
        Text(
            album.name,
            Modifier.padding(top = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = cb.ink),
        )
        Text("${album.count} ITEMS", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = cb.inkDim))
    }
}
