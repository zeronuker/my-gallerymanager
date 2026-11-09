package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Home for app settings. Only the About card exists so far; settings get added here as they are decided. */
@Composable
fun SettingsScreen() {
    val cb = LocalCb.current
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("SETTINGS", "v$version")
        Column(
            Modifier.padding(16.dp).fillMaxWidth().doubleChamferFrame(cb.mint).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("ABOUT", style = monoStyle(cb.mint, 2.6.sp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("Gallery Manager", style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = cb.ink))
                Text("v$version", style = monoStyle(cb.inkDim, 1.sp))
            }
            Text("Everything stays on your phone. Nothing is uploaded.", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim))
        }
    }
}
