package com.claudeborne.gallerymanager.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** id of the virtual Favorites album, shown first in Albums */
const val FAVORITES_ID = -2L

/**
 * Favourite photos and videos, remembered by this app only: a favourite just shows up in the Favorites album, nothing is copied or
 * moved. (Android's own favourite flag would ask for permission on every tap.)
 */
class Favorites(private val context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
    var ids by mutableStateOf(prefs.getStringSet("ids", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet())
        private set

    fun toggle(id: Long) {
        ids = if (id in ids) ids - id else ids + id
        prefs.edit().putStringSet("ids", ids.map { it.toString() }.toSet()).apply()
    }
}

@Composable
fun rememberFavorites(): Favorites {
    val context = LocalContext.current.applicationContext
    return remember { Favorites(context) }
}
