package com.claudeborne.gallerymanager.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** User preferences. Fields are Compose state, so screens update at once; call [save] after changing one. */
object AppSettings {
    private lateinit var prefs: SharedPreferences

    var theme by mutableStateOf("SYSTEM")      // SYSTEM | DARK | LIGHT
    var gridCols by mutableIntStateOf(4)       // Photos grid columns
    var slideSeconds by mutableIntStateOf(3)   // slideshow delay
    var photoSort by mutableStateOf("NEWEST")  // PhotoSort name
    var showHidden by mutableStateOf(false)    // Files tab: show hidden files

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        theme = prefs.getString("theme", "SYSTEM") ?: "SYSTEM"
        gridCols = prefs.getInt("cols", 4)
        slideSeconds = prefs.getInt("slide", 3)
        photoSort = prefs.getString("sort", "NEWEST") ?: "NEWEST"
        showHidden = prefs.getBoolean("hidden", false)
    }

    fun save() {
        prefs.edit().putString("theme", theme).putInt("cols", gridCols).putInt("slide", slideSeconds)
            .putString("sort", photoSort).putBoolean("hidden", showHidden).apply()
    }
}
