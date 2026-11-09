package com.claudeborne.gallerymanager.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The user's choices. Each value is Compose state, so screens update the moment it changes, and every change is saved at once.
 * Call [init] once at start-up; change values with [update].
 */
object Settings {
    private lateinit var prefs: SharedPreferences

    var theme by mutableIntStateOf(0) // 0 follow the phone, 1 dark, 2 light
    var gridCols by mutableIntStateOf(4)
    var splash by mutableStateOf(true)
    var autoplay by mutableStateOf(true)
    var muted by mutableStateOf(false)
    var saveMode by mutableIntStateOf(0) // 0 ask, 1 always a copy, 2 always replace the original
    var editQuality by mutableIntStateOf(95) // JPG quality of edited photos and collages
    var exportQuality by mutableIntStateOf(90) // starting quality in Resize · Convert
    var collageFolder by mutableStateOf("Collages") // inside Pictures
    var stripOnShare by mutableStateOf(false)
    var lock by mutableStateOf(false)
    var lockAfter by mutableIntStateOf(0) // 0 at once, 1 after a minute away, 2 after five minutes away

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        theme = prefs.getInt("theme", theme)
        gridCols = prefs.getInt("gridCols", gridCols).coerceIn(2, 6)
        splash = prefs.getBoolean("splash", splash)
        autoplay = prefs.getBoolean("autoplay", autoplay)
        muted = prefs.getBoolean("muted", muted)
        saveMode = prefs.getInt("saveMode", saveMode)
        editQuality = prefs.getInt("editQuality", editQuality)
        exportQuality = prefs.getInt("exportQuality", exportQuality)
        collageFolder = prefs.getString("collageFolder", collageFolder) ?: collageFolder
        stripOnShare = prefs.getBoolean("stripOnShare", stripOnShare)
        lock = prefs.getBoolean("lock", lock)
        lockAfter = prefs.getInt("lockAfter", lockAfter)
    }

    /** Applies [change] and saves everything. */
    fun update(change: Settings.() -> Unit) {
        change()
        prefs.edit()
            .putInt("theme", theme).putInt("gridCols", gridCols).putBoolean("splash", splash)
            .putBoolean("autoplay", autoplay).putBoolean("muted", muted).putInt("saveMode", saveMode)
            .putInt("editQuality", editQuality).putInt("exportQuality", exportQuality).putString("collageFolder", collageFolder)
            .putBoolean("stripOnShare", stripOnShare).putBoolean("lock", lock).putInt("lockAfter", lockAfter)
            .apply()
    }
}
