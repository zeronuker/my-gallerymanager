package com.claudeborne.gallerymanager.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.transfer.TransferHub
import com.claudeborne.gallerymanager.transfer.TransferService
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private enum class Mode { HOME, RECEIVE, COMPUTER, PHONE }

/**
 * Sends and receives files over Wi-Fi. [files] (file, path to show) are what the user chose to send; with none, only RECEIVE is offered.
 * A computer needs only a browser; another phone needs this app.
 */
@Composable
internal fun TransferScreen(files: List<Pair<File, String>>, backEnabled: Boolean, onClose: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(Mode.HOME) }
    var address by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) } // bumped twice a second so the progress bars redraw
    var speed by remember { mutableLongStateOf(0L) }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        var last = TransferHub.bytes.get()
        var at = SystemClock.elapsedRealtime()
        while (true) {
            delay(500)
            val now = SystemClock.elapsedRealtime()
            val bytes = TransferHub.bytes.get()
            speed = (bytes - last) * 1000 / (now - at).coerceAtLeast(1)
            last = bytes
            at = now
            tick++
        }
    }
    DisposableEffect(Unit) { onDispose { TransferHub.stop(); TransferService.stop(context) } }
    LaunchedEffect(mode) {
        if (mode == Mode.RECEIVE || mode == Mode.COMPUTER) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
            TransferHub.start(context, if (mode == Mode.COMPUTER) files else emptyList())
            TransferService.start(context)
        } else if (!sending) {
            TransferHub.stop()
            TransferService.stop(context)
            TransferHub.reset() // the list of the last session does not belong on the next screen
        }
    }
    BackHandler(enabled = backEnabled) { if (mode != Mode.HOME) mode = Mode.HOME else onClose() }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    val rows = remember(tick, mode) { TransferHub.items.toList().asReversed() }
    val total = files.sumOf { it.first.length() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "TRANSFER", if (speed > 0) "${Formatter.formatShortFileSize(context, speed).uppercase()}/S" else when (mode) { Mode.HOME -> "WI-FI"; Mode.RECEIVE -> "RECEIVING"; Mode.COMPUTER -> "TO A COMPUTER"; Mode.PHONE -> "TO A PHONE" },
            { if (mode != Mode.HOME) mode = Mode.HOME else onClose() }, if (mode != Mode.HOME) "TRANSFER" else "FILES",
        )
        when (mode) {
            Mode.HOME -> Column {
                if (files.isNotEmpty()) {
                    Text("${files.size} FILES · ${Formatter.formatShortFileSize(context, total).uppercase()} READY TO SEND", Modifier.padding(16.dp), style = monoStyle(cb.mint, 1.sp))
                    HomeRow("TO ANOTHER PHONE", "SCAN ITS CODE OR TYPE ITS ADDRESS") { mode = Mode.PHONE }
                    HomeRow("TO A COMPUTER", "THE COMPUTER OPENS A WEB PAGE, NO INSTALL") { mode = Mode.COMPUTER }
                } else {
                    Text("To send, select files in Files and tap SEND.", Modifier.padding(16.dp), style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim))
                }
                HomeRow("RECEIVE", "SHOW A CODE FOR OTHERS TO SEND TO THIS PHONE") { mode = Mode.RECEIVE }
                Text(
                    "Both devices need to be on the same Wi-Fi. Files you receive are saved in Download/Received.",
                    Modifier.padding(16.dp), style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim),
                )
            }
            Mode.RECEIVE, Mode.COMPUTER -> Column(Modifier.weight(1f)) {
                Connection(tick, if (mode == Mode.COMPUTER) "On the computer, open this address in a browser. Or scan the code with this app on another phone." else "On the computer, open this address in a browser to send files here. Or scan the code with this app on another phone.")
                Progress(rows)
            }
            Mode.PHONE -> Column(Modifier.weight(1f)) {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("On the other phone open TRANSFER, tap RECEIVE, then scan its code or type the address it shows.", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim))
                    CbTextField(address, { address = it })
                    Row {
                        ActionText("SCAN CODE", cb.mint) {
                            GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()).startScan()
                                .addOnSuccessListener { address = it.rawValue.orEmpty() }
                                .addOnFailureListener { toast("The scanner is not available; type the address instead") }
                        }
                        ActionText(if (sending) "SENDING…" else "SEND", if (sending) cb.inkDim else cb.mint) {
                            val target = parseAddress(address)
                            when {
                                sending -> Unit
                                target == null -> toast("Enter the address shown on the other phone")
                                else -> scope.launch {
                                    sending = true
                                    TransferService.start(context)
                                    val ok = TransferHub.send(files, target.first, target.second, target.third)
                                    TransferService.stop(context)
                                    sending = false
                                    toast("Sent $ok of ${files.size}")
                                }
                            }
                        }
                    }
                }
                Progress(rows)
            }
        }
    }
}

/** (host, port, token) from an address like http://192.168.1.5:8686/?t=abc12345, or null. */
private fun parseAddress(text: String): Triple<String, Int, String>? {
    val uri = runCatching { Uri.parse(text.trim().let { if ("://" in it) it else "http://$it" }) }.getOrNull() ?: return null
    val host = uri.host ?: return null
    val token = uri.getQueryParameter("t") ?: return null
    return Triple(host, if (uri.port > 0) uri.port else TransferHub.DEFAULT_PORT, token)
}

/** The address (and its QR code) the other device uses to reach this phone. */
@Composable
private fun Connection(tick: Int, help: String) {
    val cb = LocalCb.current
    val urls = remember(tick) { if (TransferHub.running) TransferHub.addresses() else emptyList() }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (urls.isEmpty()) {
            Text(if (TransferHub.running) "NO WI-FI CONNECTION" else "STARTING…", style = monoStyle(cb.inkDim, 2.sp))
            return@Column
        }
        Text(help, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim, textAlign = TextAlign.Center))
        Text(urls[0], style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = cb.mint, textAlign = TextAlign.Center))
        val qr = remember(urls[0]) { qrBitmap(urls[0]) }
        Image(qr, "QR code", Modifier.size(200.dp).background(androidx.compose.ui.graphics.Color.White), filterQuality = FilterQuality.None)
        urls.drop(1).forEach { Text(it, style = monoStyle(cb.inkDim, 0.6.sp)) }
    }
}

private fun qrBitmap(text: String): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
    val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** One row per file moving in or out, newest first. */
@Composable
private fun Progress(rows: List<TransferHub.Item>) {
    val cb = LocalCb.current
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows.size) { i ->
            val it = rows[i]
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("${if (it.incoming) "↓" else "↑"} ${it.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = cb.ink))
                Box(Modifier.fillMaxWidth().height(4.dp).background(cb.line)) {
                    Box(Modifier.fillMaxWidth(if (it.size > 0) (it.done.toFloat() / it.size).coerceIn(0f, 1f) else 1f).fillMaxHeight().background(if (it.error != null) RED else cb.mint))
                }
                Text(
                    it.error?.let { e -> "FAILED · $e" } ?: "${Formatter.formatShortFileSize(context, it.done).uppercase()} OF ${Formatter.formatShortFileSize(context, it.size).uppercase()}${if (it.finished) " · DONE" else ""}",
                    style = monoStyle(if (it.error != null) RED else cb.inkDim, 0.6.sp),
                )
            }
        }
    }
}
