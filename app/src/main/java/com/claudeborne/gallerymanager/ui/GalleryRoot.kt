package com.claudeborne.gallerymanager.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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

private val mediaPermissions = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

// location access is requested together but optional: without it photos just show no GPS in Info
private val requestedPermissions = mediaPermissions + Manifest.permission.ACCESS_MEDIA_LOCATION

@Composable
fun GalleryRoot(openUri: Uri? = null) {
    val context = LocalContext.current
    fun granted() = mediaPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    var hasAccess by remember { mutableStateOf(granted()) }
    var askedAndDenied by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var trash by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasAccess = granted()
        askedAndDenied = !hasAccess
    }

    // picks up permission changes made in Settings
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasAccess = granted() }

    // the media library tells us when photos or videos are added, changed or removed (a camera shot, a file received over Wi-Fi, another app);
    // a burst of changes is waited out so ten new files cause one reload instead of ten
    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val again = Runnable { reload++ }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { handler.removeCallbacks(again); handler.postDelayed(again, 1000) }
        }
        context.contentResolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer); handler.removeCallbacks(again) }
    }
    LaunchedEffect(hasAccess, reload) {
        if (hasAccess) {
            items = loadMedia(context)
            trash = loadMedia(context, trashed = true)
        }
    }

    // permission screen, loading, and the app fade into one another
    val stage = when { !hasAccess -> 0; items == null -> 1; else -> 2 }
    AnimatedContent(stage, transitionSpec = { fadeIn(tween(MOTION_MS)) togetherWith fadeOut(tween(MOTION_FAST_MS)) }, label = "root") { s ->
        when (s) {
            0 -> PermissionScreen(
                denied = askedAndDenied,
                onGrant = { launcher.launch(requestedPermissions) },
                onSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
            )
            1 -> Box(Modifier.fillMaxSize().cbGrid(LocalCb.current), Alignment.Center) { LoadingPane("LOADING YOUR LIBRARY") }
            else -> items?.let { MainScaffold(it, trash, openUri) { reload++ } }
        }
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
            else "Altus Cirrus needs access to your photos and videos to show them. Nothing leaves your phone.",
            style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2, textAlign = TextAlign.Center),
        )
        CbButton(if (denied) "OPEN SETTINGS" else "GRANT ACCESS", onClick = if (denied) onSettings else onGrant)
    }
}

@Composable
private fun CbButton(label: String, onClick: () -> Unit) = BrandButton(label, primary = true, onClick = onClick)
