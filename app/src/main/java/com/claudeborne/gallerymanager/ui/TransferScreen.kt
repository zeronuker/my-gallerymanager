package com.claudeborne.gallerymanager.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
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
import com.claudeborne.gallerymanager.transfer.SendResult
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
internal fun TransferScreen(files: List<Pair<File, String>>, backEnabled: Boolean, onClose: (finished: Boolean) -> Unit, onPick: () -> Unit) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(if (files.isNotEmpty()) Mode.PHONE else Mode.HOME) } // with files selected it opens straight on the send page
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
    var joinHelp by remember { mutableStateOf<String?>(null) } // what to do while joining the other phone's network, or by hand if that failed
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
            LocalSend.checkIdle() // a transfer that stopped moving data is ended, so the next sender is not turned away
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
    val rows = remember(tick, mode) { TransferHub.items.toList().asReversed() }
    var sendResult by remember { mutableStateOf<SendResult?>(null) } // how the send ended, until the user taps OK
    var typing by rememberSaveable { mutableStateOf(false) } // the code field is shown after ENTER CODE
    var confirmStop by remember { mutableStateOf(false) }
    val moving = sending || rows.any { !it.finished && it.error == null } // a transfer is under way, so leaving needs a confirm

    // back goes one step up: a computer send goes back to the phone list; with files selected the send page is the first page, so back leaves
    fun back() { if (mode == Mode.COMPUTER) mode = Mode.PHONE else if (mode != Mode.HOME && files.isEmpty()) mode = Mode.HOME else onClose(false) }
    fun requestBack() { if (moving) confirmStop = true else back() }
    BackHandler(enabled = backEnabled) { requestBack() }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun finish(result: SendResult) { leave?.invoke(); leave = null; sending = false; status = ""; sendResult = result }

    /** Asks the phone or LocalSend device at [peer] to take the files (the user there accepts or declines), then sends them. */
    fun runSend(peer: LocalSend.Peer, network: Network?) {
        status = "WAITING FOR ${peer.alias.uppercase()}…"
        joinHelp = null
        scope.launch { finish(TransferHub.sendLocalSend(peer, files, network)) }
    }

    /** Sends to the code in [text]: an address, or a scanned code. If it carries a direct link (network name and password), the phone joins that network first. */
    fun beginSend(text: String) {
        val target = parseAddress(text)
        when {
            sending -> return
            target == null -> return toast("That code doesn't look right")
        }
        sending = true
        joinHelp = null
        val peer = LocalSend.Peer("The other phone", target!!.host, target.port, false, "mobile")
        val ssid = target.ssid
        val pass = target.pass
        when {
            ssid == null || pass == null -> runSend(peer, null)
            DirectJoin.onSameNetwork(target.host) -> runSend(peer, null) // already on that network (joined by hand earlier, or still joined)
            else -> {
                status = "JOINING…"
                joinHelp = "When Android asks, choose the network $ssid in the list and confirm. It can take a few seconds to appear."
                leave = DirectJoin.join(context, ssid, pass, { runSend(peer, it) }, {
                    joinHelp = "Could not join automatically. Open the phone's Wi-Fi settings, join $ssid with the password $pass, then scan again."
                    finish(SendResult("Could not join the other phone's direct link.", ok = false, reached = false))
                })
            }
        }
    }
    /** Sends to a phone or LocalSend device found on the network; the user there has to accept first. */
    fun sendToPeer(peer: LocalSend.Peer) {
        if (sending) return
        sending = true
        runSend(peer, null)
    }
    val total = files.sumOf { it.first.length() }
    val wifi = remember(tick) { wifiState(context) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            when (mode) { Mode.HOME -> "TRANSFER"; Mode.RECEIVE -> "RECEIVE"; Mode.COMPUTER, Mode.PHONE -> "SEND" },
            if (speed > 0) "${Formatter.formatShortFileSize(context, speed).uppercase()}/S" else when (mode) { Mode.HOME -> "WI-FI"; Mode.RECEIVE -> "WAITING"; Mode.COMPUTER -> "TO A COMPUTER"; Mode.PHONE -> "${files.size} ${if (files.size == 1) "FILE" else "FILES"}" },
            ::requestBack, if (mode == Mode.COMPUTER) "SEND" else if (mode != Mode.HOME && files.isEmpty()) "TRANSFER" else "FILES",
        )
        AnimatedContent(mode, Modifier.weight(1f), transitionSpec = { fadeIn(tween(MOTION_MS)) togetherWith fadeOut(tween(MOTION_FAST_MS)) }, label = "transferMode") { shown -> when (shown) {
            // the start screen, reached with nothing selected: SEND opens the file picker, RECEIVE waits for files
            Mode.HOME -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceButton("SEND", enabled = true, primary = true) { onPick() }
                ChoiceButton("RECEIVE", enabled = true, primary = false) { mode = Mode.RECEIVE }
                Text("Both devices need the same Wi-Fi.", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim))
            }
            Mode.RECEIVE, Mode.COMPUTER -> Box(Modifier.fillMaxSize()) {
                Progress(rows) {
                    if (shown == Mode.RECEIVE && rows.isEmpty()) WaitingLine("WAITING FOR THE OTHER DEVICE…")
                    Connection(tick, group, computer = shown == Mode.COMPUTER)
                    FasterOptions(group, linkBusy, wifi, onStart = { requestDirect() }, onStop = { link.stop(); group = null })
                    AnimatedOverlay(linkError, expandEnter, shrinkExit) { Text(it, Modifier.padding(horizontal = 16.dp), style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = RED)) }
                    ActionText("STOP", cb.inkDim) { requestBack() }
                }
            }
            Mode.PHONE -> Box(Modifier.fillMaxSize()) {
                Progress(rows) {
                    AnimatedOverlay(joinHelp, expandEnter, shrinkExit) { Text(it, Modifier.padding(16.dp), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (sending) cb.mint else RED)) }
                    // sending, joining the other phone's network or waiting for it to accept: none of these has a percentage, so the bar sweeps
                    Visible(sending, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), expandEnter, shrinkExit) { ProgressBar(null) }
                    val now = System.currentTimeMillis()
                    val peers = remember(tick) { LocalSend.peers.values.filter { now - it.seen < 120_000 }.sortedBy { it.alias } }
                    Text("PHONES NEARBY", Modifier.padding(start = 16.dp, top = 8.dp), style = monoStyle(cb.mint, 2.4.sp))
                    if (peers.isEmpty()) Text("None found. The other phone must be on Receive.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim))
                    peers.forEach { p -> HomeRow(p.alias, "${p.deviceType.uppercase()} · ${p.host}") { sendToPeer(p) } }
                    val out = rows.filter { !it.incoming }
                    val moved = out.sumOf { it.done }
                    val sendingLine = if (moved > 0) "SENDING… ${Formatter.formatShortFileSize(context, moved).uppercase()} OF ${Formatter.formatShortFileSize(context, out.sumOf { it.size }).uppercase()}" else status.ifEmpty { "SENDING…" }
                    ActionText(if (sending) sendingLine else "SEARCH AGAIN", cb.mint) { if (!sending) TransferHub.rediscover() }
                    Text("OTHER WAYS", Modifier.padding(start = 16.dp, top = 8.dp), style = monoStyle(cb.mint, 2.4.sp))
                    HomeRow("TO A COMPUTER", "OPENS A WEB PAGE") { mode = Mode.COMPUTER }
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BrandButton("SCAN CODE", Modifier.weight(1f), primary = true) {
                                GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()).startScan()
                                    .addOnSuccessListener { address = it.rawValue.orEmpty(); beginSend(address) }
                                    .addOnFailureListener { toast("The scanner is not available. Use ENTER CODE.") }
                            }
                            BrandButton("ENTER CODE", Modifier.weight(1f), onClick = { typing = !typing })
                        }
                        Visible(typing, enter = expandEnter, exit = shrinkExit) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                CbTextField(address, { address = it }) // the keyboard opens only because the user asked for this field
                                BrandButton(if (sending) "SENDING…" else "SEND", Modifier.fillMaxWidth(), primary = true, enabled = !sending) { beginSend(address) }
                            }
                        }
                    }
                }
            }
        } }
    }

    // another device (the LocalSend app, or one like it) wants to send files: nothing is saved until the user says yes
    val incoming = remember(tick) { LocalSend.pending }
    incoming?.let { p ->
        CbDialog("INCOMING FILES", { p.answer.complete(false) }, subtitle = "${p.alias} · ${p.from}") {
            Text(
                "${p.count} ${if (p.count == 1) "file" else "files"} · ${Formatter.formatShortFileSize(context, p.bytes)}",
                style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2),
            )
            Text(
                p.names.take(3).joinToString("\n") + if (p.names.size > 3) "\nand ${p.names.size - 3} more" else "",
                style = monoStyle(cb.inkDim, 0.4.sp),
            )
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                ActionText("DECLINE", cb.inkDim) { p.answer.complete(false) }
                ActionText("ACCEPT", cb.mint) { p.answer.complete(true) }
            }
        }
    }
    // how a transfer ended, on each phone, until the user taps OK; OK then goes back to Files, where Transfer starts the next one
    // (a send that never reached the other phone stays here, so it can be tried again)
    val received = remember(tick) { LocalSend.result }
    received?.let { o -> ResultDialog(o.text, o.ok) { LocalSend.clearResult(); onClose(true) } }
    sendResult?.let { r -> ResultDialog(r.message, r.ok) { sendResult = null; if (r.reached) onClose(true) } }
    if (confirmStop) {
        CbDialog("STOP?", { confirmStop = false }) {
            Text("This cancels the transfer that is running.", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2))
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                ActionText("KEEP GOING", cb.inkDim) { confirmStop = false }
                ActionText("STOP", RED) { confirmStop = false; back() }
            }
        }
    }
}

/** The end of a transfer, as in the web apps' result dialog: a mint line when it all worked, an amber one when it did not. */
@Composable
private fun ResultDialog(text: String, ok: Boolean, onOk: () -> Unit) {
    CbDialog("TRANSFER", onOk, subtitle = if (ok) "DONE" else "NOT COMPLETE") {
        Text(text, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = if (ok) DarkCb.mint else Warn))
        Row(Modifier.fillMaxWidth(), Arrangement.End) { ActionText("OK", DarkCb.mint, onOk) }
    }
}

/** 0: connected to a Wi-Fi network, 1: Wi-Fi is on but not connected, 2: Wi-Fi is off. The direct link needs the Wi-Fi switch on, but not a router. */
private fun wifiState(context: Context): Int {
    val wm = context.applicationContext.getSystemService(WifiManager::class.java)
    return when {
        wm == null || !wm.isWifiEnabled -> 2
        TransferHub.addresses().any { it.first == "WI-FI" } -> 0
        else -> 1
    }
}

/** Where to send: [host] and [port] from a scanned code (http://192.168.1.5:53317/?t=abc12345) or a typed one (192.168.1.5); [ssid] and [pass] too when it is a direct link. */
private class Target(val host: String, val port: Int, val ssid: String?, val pass: String?)

private fun parseAddress(text: String): Target? {
    val uri = runCatching { Uri.parse(text.trim().let { if ("://" in it) it else "http://$it" }) }.getOrNull() ?: return null
    val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
    return Target(host, if (uri.port > 0) uri.port else TransferHub.DEFAULT_PORT, uri.getQueryParameter("ssid"), uri.getQueryParameter("pw"))
}

/**
 * The address (and its QR code) the other device uses to reach this phone. With a direct link the code also carries the network name and
 * password, so another phone joins the network by itself; a computer joins it by hand with the name and password shown.
 */
@Composable
private fun Connection(tick: Int, group: DirectGroup?, computer: Boolean) {
    val cb = LocalCb.current
    val urls = remember(tick, group) { if (TransferHub.running) TransferHub.addresses() else emptyList() }
    val state = if (group != null && TransferHub.running) 0 else if (urls.isEmpty()) 1 else 2
    Crossfade(state, animationSpec = tween(MOTION_MS), label = "connection") { _ ->
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (group != null && TransferHub.running) {
            val plain = "http://${group.host}:${TransferHub.port}/?t=${TransferHub.token}"
            val full = plain + "&ssid=" + Uri.encode(group.ssid) + "&pw=" + Uri.encode(group.passphrase)
            Text("DIRECT LINK READY", style = monoStyle(cb.mint, 1.sp))
            Text("Phone: scan this code.", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim, textAlign = TextAlign.Center))
            Image(remember(full) { qrBitmap(full) }, "QR code", Modifier.size(200.dp).background(androidx.compose.ui.graphics.Color.White), filterQuality = FilterQuality.None)
            Text("Computer: join ${group.ssid}, password ${group.passphrase}, then open:", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim, textAlign = TextAlign.Center))
            Text(plain, style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = cb.mint, textAlign = TextAlign.Center))
            return@Column
        }
        if (urls.isEmpty()) {
            if (TransferHub.running) Text("NO WI-FI CONNECTION", style = monoStyle(cb.inkDim, 2.sp))
            else WorkingLine("STARTING…", null, cb.inkDim)
            return@Column
        }
        val ip = urls[0].second.substringAfter("://").substringBefore(':') // a phone can type just this
        Text(
            if (computer) "Open this address on the computer,\nthen click a file to download it." else "Phone: scan the code or type $ip.\nComputer: open this address in a browser.",
            style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim, textAlign = TextAlign.Center),
        )
        Text("Visible as ${LocalSend.alias}", style = monoStyle(cb.inkDim, 0.6.sp))
        Text(urls[0].first, style = monoStyle(cb.inkDim, 1.sp))
        Text(urls[0].second, style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = cb.mint, textAlign = TextAlign.Center))
        val qr = remember(urls[0].second) { qrBitmap(urls[0].second) }
        Image(qr, "QR code", Modifier.size(200.dp).background(androidx.compose.ui.graphics.Color.White), filterQuality = FilterQuality.None)
        // other networks this phone is on: a USB cable (tethering) or a hotspot give a faster way in than the router
        urls.drop(1).forEach { (label, url) -> Text("$label  $url", style = monoStyle(cb.inkDim, 0.6.sp), textAlign = TextAlign.Center) }
    }
    }
}

private fun qrBitmap(text: String): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
    val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
private fun ChoiceButton(label: String, enabled: Boolean, primary: Boolean, onClick: () -> Unit) = WideBrandButton(label, primary, enabled, onClick)

/** Offline transfer: the ways that need no router (the direct link, and a USB cable). Folded away until asked for. */
@Composable
private fun FasterOptions(group: DirectGroup?, linkBusy: Boolean, wifi: Int, onStart: () -> Unit, onStop: () -> Unit) {
    val cb = LocalCb.current
    var open by rememberSaveable(wifi != 0) { mutableStateOf(wifi != 0) } // not on Wi-Fi: this is the way in, so it opens by itself
    Text(
        if (open) "▾ OFFLINE TRANSFER" else "▸ OFFLINE TRANSFER",
        Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp), style = monoStyle(cb.mint, 1.sp),
    )
    Visible(open, enter = expandEnter, exit = shrinkExit) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (wifi != 0) Text(if (wifi == 2) "Wi-Fi is off." else "Not connected to Wi-Fi.", style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim))
            OptionCard("DIRECT LINK", "A private network from this phone.") {
                Crossfade(if (wifi == 2) 3 else if (group != null) 0 else if (linkBusy) 1 else 2, animationSpec = tween(MOTION_FAST_MS), label = "direct") { s ->
                    when (s) {
                        0 -> ActionText("STOP DIRECT LINK", cb.inkDim, onStop)
                        1 -> WorkingLine("STARTING THE DIRECT LINK…", null, cb.inkDim) // no percentage: Android gives none, so the bar sweeps
                        3 -> Text("Turn on Wi-Fi to use it. No router needed.", style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.mint))
                        else -> ActionText("START DIRECT LINK", cb.mint, onStart)
                    }
                }
            }
            OptionCard("USB CABLE", "Plug in, then turn on USB tethering in Settings.") {}
        }
    }
}

@Composable
private fun OptionCard(title: String, note: String, action: @Composable ColumnScope.() -> Unit) {
    val cb = LocalCb.current
    Column(Modifier.fillMaxWidth().background(cb.surface1).border(1.dp, cb.line).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = monoStyle(cb.ink, 1.5.sp))
        Text(note, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim))
        action()
    }
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
            Column(Modifier.appear().fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("${if (it.incoming) "↓" else "↑"} ${it.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = cb.ink))
                // the bar is redrawn twice a second; it glides between updates
                ProgressBar(if (it.size > 0) (it.done.toFloat() / it.size).coerceIn(0f, 1f) else 1f, color = if (it.error != null) RED else cb.mint, track = cb.line)
                Text(
                    it.error?.let { e -> "FAILED · $e" } ?: "${Formatter.formatShortFileSize(context, it.done).uppercase()} OF ${Formatter.formatShortFileSize(context, it.size).uppercase()}${if (it.finished) " · DONE" else ""}",
                    style = monoStyle(if (it.error != null) RED else cb.inkDim, 0.6.sp),
                )
            }
        }
    }
}
