package com.claudeborne.gallerymanager

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.claudeborne.gallerymanager.ui.ClaudeBorneTheme
import com.claudeborne.gallerymanager.ui.GalleryRoot
import com.claudeborne.gallerymanager.ui.SplashScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClaudeBorneTheme {
                val viewUri = intent.takeIf { it.action == Intent.ACTION_VIEW }?.data
                var splash by rememberSaveable { mutableStateOf(viewUri == null) } // no splash when another app just wants a photo shown
                Box(Modifier.fillMaxSize()) {
                    GalleryRoot(viewUri)
                    if (splash) SplashScreen { splash = false }
                }
            }
        }
    }
}
