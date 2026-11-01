package com.claudeborne.gallerymanager.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.claudeborne.gallerymanager.data.MediaItem
import com.claudeborne.gallerymanager.data.loadMedia

private val mediaPermissions =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

@Composable
fun GalleryRoot() {
    val context = LocalContext.current
    fun granted() = mediaPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    var hasAccess by remember { mutableStateOf(granted()) }
    var askedAndDenied by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasAccess = granted()
        askedAndDenied = !hasAccess
    }

    // picks up permission changes made in Settings and new photos taken meanwhile
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasAccess = granted()
        reload++
    }
    LaunchedEffect(hasAccess, reload) {
        if (hasAccess) items = loadMedia(context)
    }

    when {
        !hasAccess -> PermissionScreen(
            denied = askedAndDenied,
            onGrant = { launcher.launch(mediaPermissions) },
            onSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
        )
        items != null -> MainScaffold(items!!)
    }
}

@Composable
private fun PermissionScreen(denied: Boolean, onGrant: () -> Unit, onSettings: () -> Unit) {
    val cb = LocalCb.current
    Column(
        Modifier.fillMaxSize().cbGrid(cb).systemBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("CLAUDEBORNE", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.6.sp, color = cb.mint))
        Text("ALLOW ACCESS", style = TextStyle(fontFamily = Tourney, fontWeight = FontWeight.Bold, fontSize = 30.sp, brush = cb.grad))
        Text(
            if (denied) "Access was denied. Turn on Photos and videos for this app in Settings."
            else "Gallery Manager needs access to your photos and videos to show them. Nothing leaves your phone.",
            style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2, textAlign = TextAlign.Center),
        )
        CbButton(if (denied) "OPEN SETTINGS" else "GRANT ACCESS", onClick = if (denied) onSettings else onGrant)
    }
}

@Composable
private fun CbButton(label: String, onClick: () -> Unit) {
    val cb = LocalCb.current
    Text(
        label,
        Modifier.background(cb.grad).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.2.sp, color = DarkCb.surface0),
    )
}
