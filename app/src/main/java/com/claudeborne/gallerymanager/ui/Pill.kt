package com.claudeborne.gallerymanager.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where the floating pill takes its selection actions from. A screen that shows [ActionBar] hands its actions here instead of drawing a bar;
 * the pill swaps its tabs for them while any are registered, and the last screen to register wins.
 */
internal class PillHost {
    private val entries = mutableStateListOf<Pair<Any, List<BarItem>>>()
    val actions: List<BarItem>? get() = entries.lastOrNull()?.second
    fun set(key: Any, items: List<BarItem>) {
        val i = entries.indexOfFirst { it.first === key }
        if (i < 0) entries.add(key to items) else entries[i] = key to items
    }
    fun remove(key: Any) { entries.removeAll { it.first === key } }
}

internal val LocalPillHost = compositionLocalOf<PillHost?> { null }

/** How much room a scrolling list leaves at its end so its last items can be lifted clear of the floating pill. */
internal val LocalPillInset = compositionLocalOf { 0.dp }

internal val PillHeight: Dp = 52.dp
internal val PillGap: Dp = 14.dp // between the pill and the bottom of the screen
