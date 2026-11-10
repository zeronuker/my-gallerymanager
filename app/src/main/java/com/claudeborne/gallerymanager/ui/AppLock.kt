package com.claudeborne.gallerymanager.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.claudeborne.gallerymanager.data.Settings

private const val AUTH = BIOMETRIC_STRONG or DEVICE_CREDENTIAL // fingerprint or face, with the phone's PIN, pattern or password as backup

/** Process-wide lock state: a fresh start is locked (when the lock is on); it unlocks only through [authenticate]. */
object AppLockState {
    var locked by mutableStateOf(false)
    private var leftAt = 0L

    /** Call once per process, after [Settings.init]. */
    fun start() { locked = Settings.lock }

    fun onLeft() { leftAt = SystemClock.elapsedRealtime() }

    fun onReturned() {
        if (!Settings.lock || locked) return
        val grace = when (Settings.lockAfter) { 0 -> 0L; 1 -> 60_000L; else -> 300_000L }
        if (SystemClock.elapsedRealtime() - leftAt >= grace) locked = true
    }
}

/** True if the phone has a screen lock or biometric set up, so the app lock can work. */
fun canLock(context: Context) = BiometricManager.from(context).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

private fun Context.activity(): FragmentActivity? {
    var c = this
    while (c is ContextWrapper) { if (c is FragmentActivity) return c; c = c.baseContext }
    return null
}

/** Shows the system unlock prompt. [onResult] gets true when the user passed it. */
fun authenticate(context: Context, title: String, onResult: (Boolean) -> Unit) {
    val activity = context.activity() ?: return onResult(false)
    val prompt = BiometricPrompt(
        activity, ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        },
    )
    prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(AUTH).build())
}

/** Covers the whole app with the lock screen while [AppLockState.locked]. Place it above everything else. */
@Composable
fun LockOverlay() {
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { AppLockState.onLeft() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { AppLockState.onReturned() }
    val locked = AppLockState.locked

    var asking by remember { mutableStateOf(false) }
    fun ask() {
        if (asking) return
        asking = true
        authenticate(context, "Unlock Altus Cirrus") { ok -> asking = false; if (ok) AppLockState.locked = false }
    }
    LaunchedEffect(locked) { if (locked) ask() } // the prompt opens by itself; the button is for trying again after cancelling

    val cb = LocalCb.current
    // fades in when the app locks and out when it unlocks
    AnimatedVisibility(locked, enter = fadeIn(tween(MOTION_FAST_MS)), exit = fadeOut(tween(MOTION_MS))) {
        Box(Modifier.fillMaxSize().cbGrid(cb).pointerInput(Unit) { detectTapGestures { } }.systemBarsPadding(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
                Text("CLAUDEBORNE", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.6.sp, color = cb.mint))
                Text("LOCKED", style = TextStyle(fontFamily = Tourney, fontWeight = FontWeight.Bold, fontSize = 30.sp, brush = cb.grad))
                Text(
                    "UNLOCK",
                    Modifier.background(cb.grad).clickable { ask() }.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.2.sp, color = DarkCb.surface0),
                )
                if (asking) ProgressBar(null, Modifier.padding(horizontal = 24.dp)) // the system prompt is up: show that the app is waiting for it
            }
        }
    }
}
