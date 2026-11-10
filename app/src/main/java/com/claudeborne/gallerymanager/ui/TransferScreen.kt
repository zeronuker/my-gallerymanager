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
import android.net.Network
import com.claudeborne.gallerymanager.transfer.DirectGroup
import com.claudeborne.gallerymanager.transfer.DirectJoin
import com.claudeborne.gallerymanager.transfer.DirectLink
import com.claudeborne.gallerymanager.transfer.LocalSend
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

    // the direct link: this phone makes a Wi-Fi Direct group (receiving side), or joins one (sending side)
    val link = remember { DirectLink(context) }
    var group by remember { mutableStateOf<DirectGroup?>(null) }
    var linkBusy by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var leave by remember { mutableStateOf<(() -> Unit)?>(null) } // leaves the other phone's network after sending
    fun startDirect() {
        linkBusy = true
        linkError = null
        link.start({ group = it; linkBusy = false }, { linkError = it; linkBusy = false })
    }
    val nearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startDirect() else linkError = "The direct link needs the \"Nearby devices\" permission"
    }
    fun requestDirect() {
        if (context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) startDirect()
        else nearby.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

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
    DisposableEffect(Unit) { onDispose { TransferHub.stop(); TransferService.stop(context); link.stop(); leave?.invoke() } }
    LaunchedEffect(mode) {
        group = null
        linkError = null
        link.stop()
        // while sending to a phone the server and discovery run too, so LocalSend devices answer and show up in the list
        if (mode != Mode.HOME) {
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

    /** Sends to the address in [text]. If it carries a direct link (network name and password), the phone joins that network first. */
    fun beginSend(text: String) {
        val target = parseAddress(text)
        when {
            sending -> return
            target == null -> return toast("Enter the address shown on the other phone")
        }
        sending = true
        fun finish(message: String) { leave?.invoke(); leave = null; sending = false; status = ""; toast(message) }
        fun go(network: Network?) {
            status = "SENDING…"
            scope.launch { finish("Sent ${TransferHub.send(files, target!!.host, target.port, target.token, network)} of ${files.size}") }
        }
        if (target!!.ssid != null && target.pass != null) {
            status = "JOINING…"
            leave = DirectJoin.join(context, target.ssid, target.pass, { go(it) }, { finish(it) })
        } else go(null)
    }
    /** Sends to a LocalSend device found on the network; the user there has to accept first. */
    fun sendToPeer(peer: LocalSend.Peer) {
        if (sending) return
        sending = true
        status = "WAITING FOR ${peer.alias.uppercase()}…"
        scope.launch {
            val message = TransferHub.sendLocalSend(peer, files)
            sending = false
            status = ""
            toast(message)
        }
    }
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
                    "Both devices need to be on the same network. Files you receive are saved in Download/Received.",
                    Modifier.padding(16.dp), style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim),
                )
                Text("THE FASTEST WAYS", Modifier.padding(start = 16.dp, top = 8.dp), style = monoStyle(cb.mint, 2.4.sp))
                Text(
                    "Phone to phone: RECEIVE, then \"Use a direct link\" for a router-free 5 GHz connection.\n\n" +
                        "Phone to computer, by cable: plug the phone in, turn on USB tethering (Settings, Network, Hotspot and tethering), then RECEIVE; the cable's address appears under the code.\n\n" +
                        "Phone to computer, by Wi-Fi: use the direct link, or turn on this phone's hotspot and join it from the computer.\n\n" +
                        "On the computer's page, the speed test shows what each way really gives.",
                    Modifier.padding(16.dp), style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim),
                )
            }
            Mode.RECEIVE, Mode.COMPUTER -> Box(Modifier.weight(1f)) {
                Progress(rows) {
                    Connection(tick, group, if (mode == Mode.COMPUTER) "On the computer, open this address in a browser. Or scan the code with this app on another phone." else "On the computer, open this address in a browser to send files here. Or scan the code with this app on another phone.")
                    when {
                        group != null -> ActionText("STOP DIRECT LINK", cb.inkDim) { link.stop(); group = null }
                        linkBusy -> Text("STARTING THE DIRECT LINK…", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = monoStyle(cb.inkDim, 1.sp))
                        else -> ActionText("USE A DIRECT LINK (NO ROUTER, FASTER)", cb.mint) { requestDirect() }
                    }
                    linkError?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = RED)) }
                }
            }
            Mode.PHONE -> Box(Modifier.weight(1f)) {
                Progress(rows) {
                    val now = System.currentTimeMillis()
                    val peers = remember(tick) { LocalSend.peers.values.filter { now - it.seen < 120_000 }.sortedBy { it.alias } }
                    Text("NEARBY DEVICES", Modifier.padding(start = 16.dp, top = 8.dp), style = monoStyle(cb.mint, 2.4.sp))
                    Text(
                        if (peers.isEmpty()) "None found yet. Open LocalSend or this app's RECEIVE on the other device, on the same network." else "LocalSend devices, and this app on other phones. The other device asks before anything is saved.",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim),
                    )
                    peers.forEach { p -> HomeRow(p.alias, "${p.deviceType.uppercase()} · ${p.host}") { sendToPeer(p) } }
                    ActionText(if (sending) status.ifEmpty { "SENDING…" } else "SEARCH AGAIN", cb.mint) { if (!sending) TransferHub.rediscover() }
                    Text("OR WITH A CODE", Modifier.padding(start = 16.dp, top = 8.dp), style = monoStyle(cb.mint, 2.4.sp))
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("On the other phone open TRANSFER, tap RECEIVE (and the direct link if you want the fastest speed), then scan its code or type the address it shows.", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim))
                        CbTextField(address, { address = it })
                        Row {
                            ActionText("SCAN CODE", cb.mint) {
                                GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()).startScan()
                                    .addOnSuccessListener { address = it.rawValue.orEmpty(); beginSend(address) }
                                    .addOnFailureListener { toast("The scanner is not available; type the address instead") }
                            }
                            ActionText(if (sending) status.ifEmpty { "SENDING…" } else "SEND", if (sending) cb.inkDim else cb.mint) { beginSend(address) }
                        }
                    }
                }
            }
        }
    }

    // another device (the LocalSend app, or one like it) wants to send files: nothing is saved until the user says yes
    val incoming = remember(tick) { LocalSend.pending }
    incoming?.let { p ->
        CbDialog("INCOMING FILES", { p.answer.complete(false) }) {
            Text(
                "${p.alias} wants to send ${p.count} ${if (p.count == 1) "file" else "files"} (${Formatter.formatShortFileSize(context, p.bytes)}).",
                style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2),
            )
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                ActionText("DECLINE", cb.inkDim) { p.answer.complete(false) }
                ActionText("ACCEPT", cb.mint) { p.answer.complete(true) }
            }
        }
    }
}

/** Where to send: [host], [port] and [token] from an address like http://192.168.1.5:53317/?t=abc12345; [ssid] and [pass] too when it is a direct link. */
private class Target(val host: String, val port: Int, val token: String, val ssid: String?, val pass: String?)

private fun parseAddress(text: String): Target? {
    val uri = runCatching { Uri.parse(text.trim().let { if ("://" in it) it else "http://$it" }) }.getOrNull() ?: return null
    val host = uri.host ?: return null
    val token = uri.getQueryParameter("t") ?: return null
    return Target(host, if (uri.port > 0) uri.port else TransferHub.DEFAULT_PORT, token, uri.getQueryParameter("ssid"), uri.getQueryParameter("pw"))
}

/**
 * The address (and its QR code) the other device uses to reach this phone. With a direct link the code also carries the network name and
 * password, so another phone joins the network by itself; a computer joins it by hand with the name and password shown.
 */
@Composable
private fun Connection(tick: Int, group: DirectGroup?, help: String) {
    val cb = LocalCb.current
    val urls = remember(tick, group) { if (TransferHub.running) TransferHub.addresses() else emptyList() }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (group != null && TransferHub.running) {
            val plain = "http://${group.host}:${TransferHub.port}/?t=${TransferHub.token}"
            val full = plain + "&ssid=" + Uri.encode(group.ssid) + "&pw=" + Uri.encode(group.passphrase)
            Text("DIRECT LINK READY · 5 GHZ REQUESTED", style = monoStyle(cb.mint, 1.sp))
            Text("On another phone, scan this code.", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim, textAlign = TextAlign.Center))
            Image(remember(full) { qrBitmap(full) }, "QR code", Modifier.size(200.dp).background(androidx.compose.ui.graphics.Color.White), filterQuality = FilterQuality.None)
            Text("On a computer, join the Wi-Fi network ${group.ssid} with the password ${group.passphrase}, then open", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim, textAlign = TextAlign.Center))
            Text(plain, style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = cb.mint, textAlign = TextAlign.Center))
            return@Column
        }
        if (urls.isEmpty()) {
            Text(if (TransferHub.running) "NO WI-FI CONNECTION" else "STARTING…", style = monoStyle(cb.inkDim, 2.sp))
            return@Column
        }
        Text(help, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim, textAlign = TextAlign.Center))
        Text("Visible in LocalSend as ${LocalSend.alias}", style = monoStyle(cb.inkDim, 0.6.sp))
        Text(urls[0].first, style = monoStyle(cb.inkDim, 1.sp))
        Text(urls[0].second, style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = cb.mint, textAlign = TextAlign.Center))
        val qr = remember(urls[0].second) { qrBitmap(urls[0].second) }
        Image(qr, "QR code", Modifier.size(200.dp).background(androidx.compose.ui.graphics.Color.White), filterQuality = FilterQuality.None)
        // other networks this phone is on: a USB cable (tethering) or a hotspot give a faster way in than the router
        urls.drop(1).forEach { (label, url) -> Text("$label  $url", style = monoStyle(cb.inkDim, 0.6.sp), textAlign = TextAlign.Center) }
    }
}

private fun qrBitmap(text: String): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
    val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** One row per file moving in or out, newest first. */
@Composable
private fun Progress(rows: List<TransferHub.Item>, header: @Composable () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        item { header() }
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
