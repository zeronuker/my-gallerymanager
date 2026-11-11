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
import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
                    TransferChannels(
                        shown == Mode.COMPUTER, tick, group, linkBusy, linkError, wifi, shown == Mode.RECEIVE && rows.isEmpty(),
                        { requestDirect() }, { link.stop(); group = null }, { requestBack() },
                    )
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
                    peers.forEach { p -> PhoneCard(p) { sendToPeer(p) } }
                    val out = rows.filter { !it.incoming }
                    val moved = out.sumOf { it.done }
                    val sendingLine = if (moved > 0) "SENDING… ${Formatter.formatShortFileSize(context, moved).uppercase()} OF ${Formatter.formatShortFileSize(context, out.sumOf { it.size }).uppercase()}" else status.ifEmpty { "SENDING…" }
                    if (sending) Text(sendingLine, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = monoStyle(cb.mint, 1.sp))
                    else IconBrandButton("SEARCH AGAIN", Icon.REFRESH, Modifier.padding(horizontal = 16.dp).fillMaxWidth()) { TransferHub.rediscover() }
                    Text("OTHER WAYS", Modifier.padding(start = 16.dp, top = 8.dp), style = monoStyle(cb.mint, 2.4.sp))
                    WayCard(Icon.COMPUTER, "TO A COMPUTER", "Opens a web page") { mode = Mode.COMPUTER }
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconBrandButton("SCAN CODE", Icon.SCAN, Modifier.weight(1f), primary = true) {
                                GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()).startScan()
                                    .addOnSuccessListener { address = it.rawValue.orEmpty(); beginSend(address) }
                                    .addOnFailureListener { toast("The scanner is not available. Use ENTER CODE.") }
                            }
                            IconBrandButton("ENTER CODE", Icon.KEYBOARD, Modifier.weight(1f)) { typing = !typing }
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
        CbDialog("END CONNECTION?", { confirmStop = false }) {
            Text("This ends the connection and cancels the transfer that is running.", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = cb.ink2))
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                ActionText("KEEP GOING", cb.inkDim) { confirmStop = false }
                ActionText("END", RED) { confirmStop = false; back() }
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

private fun qrBitmap(text: String): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
    val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
private fun ChoiceButton(label: String, enabled: Boolean, primary: Boolean, onClick: () -> Unit) = WideBrandButton(label, primary, enabled, onClick)


private const val USB_WAIT_S = 30

/** A status line: the breathing dot while waiting ([live]), a still dot in [color] otherwise. */
@Composable
private fun StatusLine(text: String, color: Color, live: Boolean) {
    if (live) { WaitingLine(text); return }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(text, style = monoStyle(color, 1.sp))
    }
}

/**
 * How this phone can be reached, as three cards: Wi-Fi, the direct link and a USB cable. A card with a mint frame is in use right now
 * (more than one can be); the tapped card shows its own details below: the address and QR code, the direct link's network, or the steps
 * for USB tethering with a countdown. END CONNECTION leaves the page, which stops everything.
 */
@Composable
private fun TransferChannels(
    computer: Boolean, tick: Int, group: DirectGroup?, linkBusy: Boolean, linkError: String?, wifi: Int, waiting: Boolean,
    onStartDirect: () -> Unit, onStopDirect: () -> Unit, onEnd: () -> Unit,
) {
    val cb = LocalCb.current
    val context = LocalContext.current
    val urls = remember(tick, group) { if (TransferHub.running) TransferHub.addresses() else emptyList() }
    val wifiUrl = urls.firstOrNull { it.first == "WI-FI" }?.second
    val usbUrl = urls.firstOrNull { it.first == "USB CABLE" }?.second
    var selected by rememberSaveable { mutableIntStateOf(-1) } // 0 Wi-Fi, 1 direct link, 2 USB; none until a network turns up or a card is tapped
    LaunchedEffect(wifiUrl != null) { if (selected == -1 && wifiUrl != null) selected = 0 }
    var usbSince by rememberSaveable { mutableLongStateOf(0L) } // when the wait for the USB network began
    val now = remember(tick) { SystemClock.elapsedRealtime() }
    val usbLeft = if (usbSince == 0L) USB_WAIT_S else (USB_WAIT_S - (now - usbSince) / 1000).toInt().coerceIn(0, USB_WAIT_S)
    val usbFailed = usbUrl == null && usbSince != 0L && usbLeft == 0
    val usbWaiting = usbUrl == null && usbSince != 0L && usbLeft > 0
    val directReady = group != null && TransferHub.running

    val status: Triple<String, Color, Boolean>? = when {
        selected == 2 && usbUrl != null -> Triple("CONNECTION ESTABLISHED", cb.mint, false)
        selected == 2 && usbFailed -> Triple("FAILED TO CONNECT", Warn, false)
        selected == 2 && usbWaiting -> Triple("WAITING FOR USB…", cb.mint, true)
        waiting -> Triple("WAITING FOR THE OTHER DEVICE…", cb.mint, true)
        else -> null
    }
    AnimatedContent(status, transitionSpec = { fadeIn(tween(MOTION_FAST_MS)) togetherWith fadeOut(tween(MOTION_FAST_MS)) }, label = "status") { st ->
        if (st != null) StatusLine(st.first, st.second, st.third) else Box(Modifier.height(1.dp))
    }

    Text("TRANSFER OVER", Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp), style = monoStyle(cb.mint, 2.4.sp))
    Row(Modifier.padding(horizontal = 16.dp), Arrangement.spacedBy(8.dp)) {
        ChannelCard(
            Icon.WIFI, "WI-FI", if (wifiUrl != null) "● IN USE" else if (wifi == 2) "OFF" else "NO NETWORK",
            lit = wifiUrl != null, bad = false, selected = selected == 0, Modifier.weight(1f),
        ) { selected = 0 }
        ChannelCard(
            Icon.DIRECT, "DIRECT", if (directReady) "● IN USE" else if (linkBusy) "STARTING…" else "",
            lit = directReady, bad = linkError != null && !linkBusy, selected = selected == 1, Modifier.weight(1f),
        ) {
            selected = 1
            if (wifi != 2 && group == null && !linkBusy) onStartDirect()
        }
        ChannelCard(
            Icon.PLUG, "USB",
            if (usbUrl != null) "● IN USE" else if (usbFailed) "FAILED" else if (usbWaiting) "%d:%02d".format(usbLeft / 60, usbLeft % 60) else "",
            lit = usbUrl != null, bad = usbFailed, selected = selected == 2, Modifier.weight(1f),
        ) {
            selected = 2
            if (usbSince == 0L) usbSince = SystemClock.elapsedRealtime()
        }
    }
    Crossfade(selected, animationSpec = tween(MOTION_FAST_MS), label = "channel") { which ->
        Column(Modifier.fillMaxWidth().padding(16.dp), Arrangement.spacedBy(10.dp), Alignment.CenterHorizontally) {
            when (which) {
                0 -> WifiPanel(computer, wifiUrl, wifi, urls)
                1 -> DirectPanel(group, directReady, linkBusy, linkError, wifi, onStopDirect)
                2 -> UsbPanel(usbUrl, usbLeft, usbFailed, onRetry = { usbSince = SystemClock.elapsedRealtime() }, onSettings = {
                    val tether = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
                    runCatching { context.startActivity(tether) }.onFailure { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) } }
                })
                else -> Text("Choose how to connect.", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim))
            }
        }
    }
    IconBrandButton("END CONNECTION", Icon.PLUG, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), danger = true, onClick = onEnd)
}

@Composable
private fun ChannelCard(icon: Icon, title: String, small: String, lit: Boolean, bad: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cb = LocalCb.current
    val edge by animateColorAsState(if (bad) Warn else if (lit) cb.mint else cb.line, tween(MOTION_FAST_MS), label = "cardEdge")
    val tint = if (bad) Warn else if (lit) cb.mint else cb.inkDim
    Column(
        modifier.background(if (selected) cb.surface2 else cb.surface1).border(if (lit || bad) 1.5.dp else 1.dp, edge).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CbIcon(icon, tint, size = 24.dp)
        Text(title, style = monoStyle(if (lit || selected) cb.ink else cb.inkDim, 1.sp))
        Text(small, Modifier.height(16.dp), style = monoStyle(tint, 0.4.sp).copy(fontSize = 10.sp))
    }
}

/** The Wi-Fi details: how to reach this phone over the network it is on. */
@Composable
private fun ColumnScope.WifiPanel(computer: Boolean, url: String?, wifi: Int, urls: List<Pair<String, String>>) {
    val cb = LocalCb.current
    if (url == null) {
        if (!TransferHub.running) WorkingLine("STARTING…", null, cb.inkDim)
        else Text(if (wifi == 2) "Wi-Fi is off." else "NO WI-FI CONNECTION", style = monoStyle(cb.inkDim, 2.sp))
        return
    }
    val ip = url.substringAfter("://").substringBefore(':') // a phone can type just this
    Text(
        if (computer) "Open this address on the computer,\nthen click a file to download it." else "Phone: scan the code or type $ip.\nComputer: open this address in a browser.",
        style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim, textAlign = TextAlign.Center),
    )
    Text("Visible as ${LocalSend.alias}", style = monoStyle(cb.inkDim, 0.6.sp))
    Column(Modifier.fillMaxWidth().doubleChamferFrame(cb).padding(16.dp), Arrangement.spacedBy(10.dp), Alignment.CenterHorizontally) {
        Image(remember(url) { qrBitmap(url) }, "QR code", Modifier.size(200.dp).background(Color.White), filterQuality = FilterQuality.None)
        Text(url, style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = cb.mint, textAlign = TextAlign.Center))
    }
    // other networks this phone is on (a hotspot, ethernet): a faster way in than the router
    urls.filter { it.first !in setOf("WI-FI", "USB CABLE", "DIRECT LINK") }.forEach { (label, u) -> Text("$label  $u", style = monoStyle(cb.inkDim, 0.6.sp), textAlign = TextAlign.Center) }
}

/** The direct link's details: a private network this phone makes. Its code carries the network name and password, so another phone joins by itself. */
@Composable
private fun ColumnScope.DirectPanel(group: DirectGroup?, ready: Boolean, busy: Boolean, error: String?, wifi: Int, onStop: () -> Unit) {
    val cb = LocalCb.current
    when {
        ready && group != null -> {
            val plain = "http://${group.host}:${TransferHub.port}/?t=${TransferHub.token}"
            val full = plain + "&ssid=" + Uri.encode(group.ssid) + "&pw=" + Uri.encode(group.passphrase)
            Text("Phone: scan this code.", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim, textAlign = TextAlign.Center))
            Column(Modifier.fillMaxWidth().doubleChamferFrame(cb).padding(16.dp), Arrangement.spacedBy(10.dp), Alignment.CenterHorizontally) {
                Image(remember(full) { qrBitmap(full) }, "QR code", Modifier.size(200.dp).background(Color.White), filterQuality = FilterQuality.None)
            }
            Text("Computer: join ${group.ssid}, password ${group.passphrase}, then open:", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim, textAlign = TextAlign.Center))
            Text(plain, style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = cb.mint, textAlign = TextAlign.Center))
            ActionText("STOP DIRECT LINK", cb.inkDim, onStop)
        }
        busy -> WorkingLine("STARTING THE DIRECT LINK…", null, cb.inkDim) // no percentage: Android gives none, so the bar sweeps
        error != null -> Text(error, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = RED, textAlign = TextAlign.Center))
        wifi == 2 -> Text("Turn on Wi-Fi to use it. No router needed.", style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.mint))
        else -> WorkingLine("STARTING THE DIRECT LINK…", null, cb.inkDim)
    }
}

/** The USB details: the steps while waiting (with the countdown bar), the address once the cable network is up, or what to try when it never came. */
@Composable
private fun ColumnScope.UsbPanel(url: String?, left: Int, failed: Boolean, onRetry: () -> Unit, onSettings: () -> Unit) {
    val cb = LocalCb.current
    val note = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.inkDim)
    when {
        url != null -> {
            Text("On the computer, open this address in a browser:", style = note.copy(textAlign = TextAlign.Center))
            Text(url, style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = cb.mint, textAlign = TextAlign.Center))
        }
        failed -> {
            Text("No USB network found after $USB_WAIT_S seconds. Check the cable, and that USB tethering is on.", style = note.copy(color = Warn, textAlign = TextAlign.Center))
            IconBrandButton("TRY AGAIN", Icon.REFRESH, Modifier.fillMaxWidth(), onClick = onRetry)
            IconBrandButton("OPEN ANDROID SETTINGS", Icon.GEAR, Modifier.fillMaxWidth(), onClick = onSettings)
        }
        else -> {
            Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
                listOf("Plug the cable into the computer.", "Turn on USB tethering in Android settings.", "The address appears here. Open it on the computer.").forEachIndexed { i, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${i + 1}", style = monoStyle(cb.mint, 0.sp))
                        Text(step, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = cb.ink))
                    }
                }
            }
            ProgressBar((USB_WAIT_S - left) / USB_WAIT_S.toFloat(), color = cb.mint, track = cb.line)
            IconBrandButton("OPEN ANDROID SETTINGS", Icon.GEAR, Modifier.fillMaxWidth(), onClick = onSettings)
        }
    }
}

/** A phone found on the network, as a contact card: its first letter, its name, and what and where it is. */
@Composable
private fun PhoneCard(p: LocalSend.Peer, onClick: () -> Unit) {
    val cb = LocalCb.current
    val dp = LocalDensity.current.density
    val shape = remember(dp) { GenericShape { size, _ -> addPath(chamfered(size.width, size.height, 0f, 8f * dp)) } }
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clickable(onClick = onClick).doubleChamferFrame(cb).padding(14.dp),
        Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(shape).background(cb.surface2), Alignment.Center) {
            Text(p.alias.take(1).uppercase(), style = TextStyle(fontFamily = Tourney, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = cb.mint))
        }
        Column(Modifier.weight(1f)) {
            Text(p.alias, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cb.ink))
            Text("${p.deviceType.uppercase()} · ${p.host}", maxLines = 1, style = monoStyle(cb.inkDim, 1.sp))
        }
        CbIcon(Icon.CHEVRON, cb.mint, size = 20.dp)
    }
}

/** A way to send that is not a phone on the network: an icon, a name and a short note. */
@Composable
private fun WayCard(icon: Icon, title: String, note: String, onClick: () -> Unit) {
    val cb = LocalCb.current
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().background(cb.surface1).border(1.dp, cb.line).clickable(onClick = onClick).padding(14.dp),
        Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
    ) {
        CbIcon(icon, cb.mint, size = 24.dp)
        Column {
            Text(title, style = monoStyle(cb.ink, 1.5.sp))
            Text(note, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim))
        }
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
