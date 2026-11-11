package com.claudeborne.gallerymanager

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import com.claudeborne.gallerymanager.ui.MOTION_MS
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.claudeborne.gallerymanager.data.Settings
import com.claudeborne.gallerymanager.data.Updater
import com.claudeborne.gallerymanager.ui.UpdateDialog
import com.claudeborne.gallerymanager.ui.AppLockState
import com.claudeborne.gallerymanager.ui.ClaudeBorneTheme
import com.claudeborne.gallerymanager.ui.LockOverlay
import com.claudeborne.gallerymanager.ui.GalleryRoot
import com.claudeborne.gallerymanager.ui.SplashScreen

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // with the lock on, the window is also hidden from screenshots and the recent-apps view
            LaunchedEffect(Settings.lock) {
                if (Settings.lock) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            ClaudeBorneTheme {
                val viewUri = intent.takeIf { it.action == Intent.ACTION_VIEW }?.data
                var splash by rememberSaveable { mutableStateOf(viewUri == null && Settings.splash) } // no splash when another app just wants a photo shown
                Box(Modifier.fillMaxSize()) {
                    // while locked the content stays (so nothing is lost) but is hidden from screen readers too
                    Box(if (AppLockState.locked) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) { GalleryRoot(viewUri) }
                    AnimatedVisibility(splash, enter = EnterTransition.None, exit = fadeOut(tween(MOTION_MS))) { SplashScreen { splash = false } }
                    LockOverlay()
                    UpdateDialog()
                }
                // look for a newer build once per app start (not again after a rotation)
                LaunchedEffect(Unit) { if (Updater.state is Updater.State.Idle) Updater.check(this@MainActivity, manual = false) }
            }
        }
    }
}
