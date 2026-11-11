package com.claudeborne.gallerymanager.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The user's choices. Each value is Compose state, so screens update the moment it changes, and every change is saved at once.
 * Call [init] once at start-up; change values with [update].
 */
object Settings {
    private lateinit var prefs: SharedPreferences

    var theme by mutableIntStateOf(1) // 1 dark, 2 light
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
    var shelfEdge by mutableIntStateOf(1) // the Settings shelf sits on the 0 left or 1 right edge
    var shelfTap by mutableStateOf(false) // a tap on the shelf opens Settings too, not only a swipe
    var shelfAutoHide by mutableStateOf(false) // the bar fades out after a few seconds and comes back when touched near
    var shelfThickness by mutableFloatStateOf(4f) // dp, 2..12
    var shelfHeight by mutableFloatStateOf(72f) // dp, 30..200
    var shelfInset by mutableFloatStateOf(0f) // dp from the chosen edge, 0..60
    var shelfY by mutableFloatStateOf(38f) // percent of the screen height down to the bar's middle, 10..90

    // these were whole numbers before they became smooth, so a saved value may be either kind
    private fun number(key: String, default: Float) = (prefs.all[key] as? Number)?.toFloat() ?: default

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        theme = if (prefs.getInt("theme", 1) == 2) 2 else 1 // the old "follow the phone" (0) becomes dark
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
        shelfEdge = prefs.getInt("shelfEdge", shelfEdge)
        shelfTap = prefs.getBoolean("shelfTap", shelfTap)
        shelfAutoHide = prefs.getBoolean("shelfAutoHide", shelfAutoHide)
        shelfThickness = number("shelfThickness", shelfThickness).coerceIn(2f, 12f)
        shelfHeight = number("shelfHeight", shelfHeight).coerceIn(30f, 200f)
        shelfInset = number("shelfInset", shelfInset).coerceIn(0f, 60f)
        shelfY = number("shelfY", shelfY).coerceIn(10f, 90f)
    }

    /** Applies [change] and saves everything. */
    fun update(change: Settings.() -> Unit) {
        change()
        prefs.edit()
            .putInt("theme", theme).putInt("gridCols", gridCols).putBoolean("splash", splash)
            .putBoolean("autoplay", autoplay).putBoolean("muted", muted).putInt("saveMode", saveMode)
            .putInt("editQuality", editQuality).putInt("exportQuality", exportQuality).putString("collageFolder", collageFolder)
            .putBoolean("stripOnShare", stripOnShare).putBoolean("lock", lock).putInt("lockAfter", lockAfter)
            .putInt("shelfEdge", shelfEdge).putBoolean("shelfTap", shelfTap).putBoolean("shelfAutoHide", shelfAutoHide)
            .putFloat("shelfThickness", shelfThickness).putFloat("shelfHeight", shelfHeight).putFloat("shelfInset", shelfInset).putFloat("shelfY", shelfY)
            .apply()
    }
}
