package com.claudeborne.gallerymanager.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from the newest GitHub release. CI attaches `gallerymanager-<versionCode>.apk` to every release, so the
 * version comes from the file name. Every failure ends up in [state]; nothing here can throw into the UI.
 * Android itself refuses an APK that is not signed with the same key as the installed app.
 */
object Updater {
    private const val REPO = "zeronuker/my-gallerymanager"
    private val NAME = Regex("""gallerymanager-(\d+)\.apk""")

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val code: Long, val url: String, val size: Long) : State
        data class Downloading(val progress: Float) : State
        data class Ready(val apk: File) : State
        data class Failed(val reason: String) : State
    }

    var state: State by mutableStateOf(State.Idle)
        private set

    /** The update dialog is open: set when an update is found, cleared by the dialog's own buttons. */
    var showDialog by mutableStateOf(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Looks for a newer release. [manual] false (app start) stays silent when it cannot reach GitHub. */
    fun check(context: Context, manual: Boolean) {
        if (state is State.Checking || state is State.Downloading) return
        val app = context.applicationContext
        state = State.Checking
        scope.launch {
            state = try {
                val current = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
                latest(current)?.also { showDialog = true } ?: State.UpToDate.also { File(app.cacheDir, "update").deleteRecursively() }
            } catch (e: Exception) {
                if (manual) State.Failed(e.message ?: "could not check") else State.Idle
            }
        }
    }

    fun download(context: Context, update: State.Available) {
        if (state !is State.Available) return
        val app = context.applicationContext
        state = State.Downloading(0f)
        scope.launch {
            state = try {
                val dir = File(app.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
                val apk = File(dir, "update.apk")
                val c = URL(update.url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 30_000
                try {
                    if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                    c.inputStream.use { input ->
                        apk.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                state = State.Downloading((done.toFloat() / update.size).coerceIn(0f, 1f))
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
                // only a complete file for this app and this build is offered to the installer
                val info = app.packageManager.getPackageArchiveInfo(apk.path, 0)
                if (apk.length() != update.size || info == null || info.packageName != app.packageName || info.longVersionCode != update.code) {
                    apk.delete()
                    throw IOException("the download was incomplete")
                }
                State.Ready(apk)
            } catch (e: Exception) {
                File(app.cacheDir, "update").deleteRecursively()
                State.Failed(e.message ?: "download failed")
            }
        }
    }

    /** Hands the downloaded APK to Android's installer; first asks the user to allow installs from this app if they have not. */
    fun install(context: Context, ready: State.Ready) {
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", ready.apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            state = State.Failed(e.message ?: "could not start the installer")
        }
    }

    /** Back to a neutral state after the user has read a failure. */
    fun dismiss() {
        showDialog = false
        if (state is State.Failed) state = State.Idle
    }

    private fun latest(current: Long): State.Available? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode == 404) return null // no release published yet
            if (c.responseCode != 200) throw IOException("GitHub answered ${c.responseCode}")
            val assets = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val code = NAME.matchEntire(a.getString("name"))?.groupValues?.get(1)?.toLong() ?: continue
                if (code > current) return State.Available(code, a.getString("browser_download_url"), a.getLong("size"))
            }
            return null
        } finally {
            c.disconnect()
        }
    }
}
