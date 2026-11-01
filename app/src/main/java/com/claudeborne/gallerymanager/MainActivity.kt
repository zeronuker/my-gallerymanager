package com.claudeborne.gallerymanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.ui.ClaudeBorneTheme
import com.claudeborne.gallerymanager.ui.JetBrainsMono
import com.claudeborne.gallerymanager.ui.LocalCb
import com.claudeborne.gallerymanager.ui.SplashScreen
import com.claudeborne.gallerymanager.ui.Tourney
import com.claudeborne.gallerymanager.ui.cbGrid

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClaudeBorneTheme {
                var splash by remember { mutableStateOf(true) }
                Box(Modifier.fillMaxSize()) {
                    Home()
                    if (splash) SplashScreen { splash = false }
                }
            }
        }
    }
}

// ponytail: Phase 0 placeholder — replaced by the gallery in Phase 1
@Composable
private fun Home() {
    val cb = LocalCb.current
    val context = LocalContext.current
    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
    Column(
        Modifier.fillMaxSize().cbGrid(cb).systemBarsPadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("CLAUDEBORNE", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.6.sp, color = cb.mint))
        Text(
            "GALLERY MANAGER",
            style = TextStyle(fontFamily = Tourney, fontWeight = FontWeight.Bold, fontSize = 32.sp, brush = cb.grad),
        )
        Text("v$version", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = cb.inkDim))
    }
}
