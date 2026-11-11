package com.claudeborne.gallerymanager.transfer

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Network
import android.os.Build
import android.os.Environment
import com.claudeborne.gallerymanager.data.freeName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.zip.Deflater
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** How a send ended: what to tell the user, whether everything arrived, and whether the other side answered at all (if not, the user can try again). */
class SendResult(val message: String, val ok: Boolean, val reached: Boolean)

/**
 * File transfer between this phone and a computer's browser or another phone running this app. The phone side runs a small HTTP server
 * (a computer opens its address in a browser; another phone's app uploads to it); a phone sending to another phone is the client.
 * Every request needs the session token, which is part of the address shown on screen and in its QR code.
 */
object TransferHub {
    const val DEFAULT_PORT = LocalSend.PORT // LocalSend's own port, so the web page and LocalSend share one

    /** One file moving in or out; [done] counts the bytes so far. */
    class Item(val incoming: Boolean, val name: String, val size: Long) {
        @Volatile var done = 0L
        @Volatile var finished = false
        @Volatile var error: String? = null
    }

    /** A file this phone offers to the computer for download. */
    class Offer(val id: Int, val file: File, val rel: String)

    val items = CopyOnWriteArrayList<Item>()
    val bytes = AtomicLong() // everything moved this session, for the speed meter

    @Volatile var running = false
        private set
    var port = 0
        private set
    var token = ""
        private set
    private var offers: List<Offer> = emptyList()
    private var server: MiniHttpServer? = null
    private var discovery: LocalSend.Discovery? = null
    internal lateinit var appContext: Context

    /** Where files sent to this phone are saved. */
    val saveDir: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Received")

    /** Forgets the files and the byte count of the last session. */
    fun reset() { items.clear(); bytes.set(0) }

    /** Starts the server. [offered] (file, path to show) are what a computer can download from this phone. */
    fun start(context: Context, offered: List<Pair<File, String>>) {
        stop()
        appContext = context.applicationContext
        reset()
        offers = offered.mapIndexed { i, (f, rel) -> Offer(i, f, rel) }
        val random = SecureRandom()
        val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        token = (1..8).map { chars[random.nextInt(chars.length)] }.joinToString("")
        val s = MiniHttpServer(::handle)
        port = s.start(DEFAULT_PORT)
        server = s
        running = true
        discovery = LocalSend.Discovery(appContext).also { it.start() } // other LocalSend apps on the network find this phone, and it finds them
    }

    /** Announces this phone again, so devices that missed the first announcement show up. */
    fun rediscover() { discovery?.announceAgain() }

    fun stop() {
        discovery?.stop()
        discovery = null
        server?.stop()
        server = null
        running = false
        LocalSend.endSession()
    }

    /** Addresses another device can open as (what the network is, address): Wi-Fi first, then the rest. The fast links show up here too. */
    fun addresses(): List<Pair<String, String>> {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        return java.util.Collections.list(interfaces)
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> java.util.Collections.list(ni.inetAddresses).filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { ni.name to it.hostAddress } }
            .sortedBy { if (it.first.startsWith("wlan0")) 0 else 1 }
            .map { (name, ip) -> networkLabel(name) to "http://$ip:$port/?t=$token" }
    }

    /** What kind of network an interface name stands for, from Android's usual naming. */
    private fun networkLabel(name: String) = when {
        name.startsWith("p2p") -> "DIRECT LINK"
        name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> "USB CABLE"
        name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("wlan1") -> "THIS PHONE'S HOTSPOT"
        name.startsWith("eth") -> "ETHERNET"
        name.startsWith("wlan") -> "WI-FI"
        else -> name.uppercase()
    }

    // ---- server side ----

    private fun handle(req: HttpRequest, res: HttpResponse) {
        if (req.path.startsWith(LocalSend.API)) return LocalSend.handle(req, res) // LocalSend has no token; the user accepts each transfer instead
        if (req.query["t"] != token) return res.send(403, "text/plain", "forbidden".toByteArray())
        when {
            req.method == "GET" && req.path == "/" -> res.send(200, "text/html; charset=utf-8", WebPage.html(Build.MODEL, token).toByteArray())
            req.method == "GET" && req.path == "/api/info" -> res.send(200, "application/json", JSONObject().put("name", Build.MODEL).toString().toByteArray())
            req.method == "GET" && req.path == "/api/offers" -> res.send(200, "application/json", offersJson().toByteArray())
            req.method == "GET" && req.path.startsWith("/api/file/") -> download(req, res)
            req.method == "GET" && req.path == "/api/zip" -> zipAll(res)
            req.method == "GET" && req.path == "/api/speed/down" -> { // the web page's speed test: a stream of zeros
                val size = (req.query["mb"]?.toLongOrNull() ?: 64).coerceIn(1, 1024) shl 20
                res.sendStream(200, "application/octet-stream", size, emptyMap(), Zeros(size)) { n -> bytes.addAndGet(n.toLong()) }
            }
            req.method == "PUT" && req.path == "/api/speed/up" -> { // and the other direction: reads and drops what the page sends
                val buf = ByteArray(BUFFER)
                var total = 0L
                while (true) { val n = req.body.read(buf); if (n < 0) break; total += n; bytes.addAndGet(n.toLong()) }
                res.send(200, "application/json", JSONObject().put("bytes", total).toString().toByteArray())
            }
            else -> res.send(404, "text/plain", "not found".toByteArray())
        }
    }

    private fun offersJson() = JSONArray().apply {
        offers.forEach { put(JSONObject().put("id", it.id).put("name", it.rel).put("size", it.file.length())) }
    }.toString()

    /** Keeps a path sent by the other side inside the save folder: no empty parts, no "." and no "..". */
    internal fun cleanRel(name: String?): String? =
        name.orEmpty().replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.takeIf { it.isNotEmpty() }?.joinToString("/")

    /**
     * Saves [body] as [rel] (a path below the save folder) under a free name and tells the media library. [size] is the length the sender
     * announced, or -1 if unknown. The file is renamed into place only once complete, so a broken transfer leaves no half file.
     */
    internal fun receiveFile(rel: String, size: Long, mtime: Long?, body: InputStream): File {
        val parent = File(saveDir, rel.substringBeforeLast('/', "")).apply { mkdirs() }
        val target = freeName(parent, rel.substringAfterLast('/'))
        val part = File(parent, target.name + ".part")
        val item = Item(true, rel, size).also { items.add(it) }
        try {
            part.outputStream().use { out ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = body.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    item.done += n
                    bytes.addAndGet(n.toLong())
                }
            }
            if (size >= 0 && item.done != size) throw IOException("incomplete")
            part.renameTo(target)
            mtime?.let { target.setLastModified(it) }
            item.finished = true
            MediaScannerConnection.scanFile(appContext, arrayOf(target.path), null, null)
            return target
        } catch (e: IOException) {
            item.error = e.message ?: "failed"
            part.delete()
            throw e
        }
    }

    /** Everything this phone offers as one zip, made while it is sent: no compression (photos and videos do not shrink), folders kept as they were. */
    private fun zipAll(res: HttpResponse) {
        val names = HashSet<String>()
        res.sendChunked(200, "application/zip", mapOf("Content-Disposition" to "attachment; filename=\"altus-cirrus-files.zip\"")) { out ->
            ZipOutputStream(BufferedOutputStream(out, BUFFER)).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION)
                offers.forEach { o ->
                    var name = o.rel
                    var n = 1
                    while (!names.add(name)) { // two offers with one path: the second gets " (1)"
                        val dot = o.rel.lastIndexOf('.').takeIf { it > o.rel.lastIndexOf('/') } ?: -1
                        name = if (dot < 0) "${o.rel} ($n)" else o.rel.substring(0, dot) + " ($n)" + o.rel.substring(dot)
                        n++
                    }
                    val item = Item(false, o.rel, o.file.length()).also { items.add(it) }
                    try {
                        zip.putNextEntry(ZipEntry(name).apply { time = o.file.lastModified() })
                        o.file.inputStream().use { input ->
                            val buf = ByteArray(BUFFER)
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                zip.write(buf, 0, read)
                                item.done += read
                                bytes.addAndGet(read.toLong())
                            }
                        }
                        zip.closeEntry()
                        item.finished = true
                    } catch (e: IOException) {
                        item.error = e.message ?: "failed"
                        throw e
                    }
                }
            }
        }
    }

    private fun download(req: HttpRequest, res: HttpResponse) {
        val offer = offers.firstOrNull { it.id == req.path.substringAfterLast('/').toIntOrNull() } ?: return res.send(404, "text/plain", "not found".toByteArray())
        val total = offer.file.length()
        var start = 0L
        var end = total - 1
        var code = 200
        val range = req.headers["range"]?.removePrefix("bytes=")
        if (range != null) { // lets a browser resume a download
            start = range.substringBefore('-').toLongOrNull() ?: 0
            end = range.substringAfter('-', "").toLongOrNull() ?: end
            if (start > end || end >= total) return res.send(416, "text/plain", "bad range".toByteArray())
            code = 206
        }
        val length = end - start + 1
        val name = URLEncoder.encode(offer.file.name, "UTF-8").replace("+", "%20")
        val extra = mutableMapOf("Content-Disposition" to "attachment; filename*=UTF-8''$name", "Accept-Ranges" to "bytes")
        if (code == 206) extra["Content-Range"] = "bytes $start-$end/$total"
        val item = Item(false, offer.rel, length).also { items.add(it) }
        try {
            offer.file.inputStream().use { input ->
                input.skip(start)
                res.sendStream(code, "application/octet-stream", length, extra, input) { n -> item.done += n; bytes.addAndGet(n.toLong()) }
            }
            item.finished = true
        } catch (e: IOException) {
            item.error = e.message ?: "failed"
            throw e
        }
    }

    // ---- client side: sending to another phone ----

    /** One file to upload: the request to make for it (a method and a path with its query) and the [item] that shows its progress. */
    private class Job(val file: File, val item: Item, val method: String, val path: String)

    /**
     * Sends [files] to another phone running this app, or to a LocalSend device: asks it first (the user there accepts or declines), then uploads.
     * [network] pins the connection to the direct link's network. Returns a message for the user.
     */
    suspend fun sendLocalSend(peer: LocalSend.Peer, files: List<Pair<File, String>>, network: Network? = null): SendResult = withContext(Dispatchers.IO) {
        reset()
        val shown = files.map { (f, rel) -> f to Item(false, rel, f.length()).also { items.add(it) } }
        val offer = JSONObject()
        val list = JSONObject()
        shown.forEachIndexed { i, (f, item) ->
            list.put(i.toString(), JSONObject().put("id", i.toString()).put("fileName", item.name).put("size", item.size)
                .put("fileType", android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream")
                .put("metadata", JSONObject().put("modified", java.time.Instant.ofEpochMilli(f.lastModified()).toString())))
        }
        offer.put("info", LocalSend.info()).put("files", list)
        val reply = try {
            Http.post(peer.host, peer.port, peer.https, LocalSend.API + "prepare-upload", offer.toString().toByteArray(), timeoutMs = 180_000, network = network) // the other user has time to accept
        } catch (e: IOException) {
            return@withContext SendResult(Http.explain(e, peer.alias), ok = false, reached = false)
        }
        when (reply.status) {
            200 -> Unit
            204 -> return@withContext SendResult("${peer.alias} needs nothing from this.", ok = false, reached = false)
            401 -> return@withContext SendResult("${peer.alias} asks for a PIN, which this app does not support yet.", ok = false, reached = false)
            403 -> return@withContext SendResult("${peer.alias} declined.", ok = false, reached = true)
            408 -> return@withContext SendResult("No answer from ${peer.alias}.", ok = false, reached = true)
            409 -> return@withContext SendResult("${peer.alias} is busy with another transfer.", ok = false, reached = false)
            else -> return@withContext SendResult("${peer.alias} answered ${reply.status}.", ok = false, reached = false)
        }
        val answer = JSONObject(String(reply.body))
        val session = answer.getString("sessionId")
        val tokens = answer.getJSONObject("files")
        val jobs = shown.mapIndexedNotNull { i, (f, item) ->
            tokens.optString(i.toString()).takeIf { it.isNotEmpty() }?.let { Job(f, item, "POST", "${LocalSend.API}upload?sessionId=$session&fileId=$i&token=$it") }
        }
        val ok = upload(jobs, peer.host, peer.port, peer.https, network)
        if (ok < files.size) { // tell the other side it is over, so it does not wait for the rest
            runCatching { Http.post(peer.host, peer.port, peer.https, LocalSend.API + "cancel", ByteArray(0), network = network) }
            SendResult("Sent $ok of ${files.size} to ${peer.alias}. Some files failed.", ok = false, reached = true)
        } else SendResult("Sent $ok of ${files.size} to ${peer.alias}.", ok = true, reached = true)
    }

    /** Runs [jobs] over a few connections at once, each reused for the next file. Returns how many were accepted. */
    private suspend fun upload(jobs: List<Job>, host: String, port: Int, https: Boolean, network: Network?, parallel: Int = 4): Int {
        val queue = ConcurrentLinkedQueue(jobs)
        val ok = AtomicInteger()
        coroutineScope {
            repeat(minOf(parallel, jobs.size)) {
                launch {
                    var socket: Socket? = null
                    try {
                        while (true) {
                            val job = queue.poll() ?: break
                            try {
                                val s = socket ?: Http.open(host, port, https, network).also { socket = it }
                                put(s, "$host:$port", job)
                                ok.incrementAndGet()
                            } catch (e: IOException) {
                                job.item.error = e.message ?: "failed"
                                socket?.close()
                                socket = null
                            }
                        }
                    } finally {
                        socket?.close()
                    }
                }
            }
        }
        return ok.get()
    }

    private fun CoroutineScope.put(socket: Socket, hostHeader: String, job: Job) {
        val file = job.file
        val item = job.item
        val out = socket.getOutputStream()
        out.write("${job.method} ${job.path} HTTP/1.1\r\nHost: $hostHeader\r\nContent-Length: ${item.size}\r\nConnection: keep-alive\r\n\r\n".toByteArray())
        val buf = ByteArray(BUFFER)
        var left = item.size
        file.inputStream().use { input ->
            while (left > 0) {
                ensureActive()
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("file changed while sending")
                out.write(buf, 0, n)
                left -= n
                item.done += n
                bytes.addAndGet(n.toLong())
            }
        }
        out.flush()
        val reply = Http.readReply(socket.getInputStream())
        if (reply.status !in 200..299) throw IOException("answered ${reply.status}")
        item.finished = true
    }
}

/** [size] zero bytes, for the speed test. */
private class Zeros(private var left: Long) : InputStream() {
    override fun read(): Int = if (left <= 0) -1 else { left--; 0 }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = minOf(len.toLong(), left).toInt()
        java.util.Arrays.fill(b, off, off + n, 0)
        left -= n
        return n
    }
}

/** Every file under the selected files and folders as (file, path to show): a folder keeps its name and its structure. */
fun expandForSend(selection: List<File>): List<Pair<File, String>> = selection.flatMap { f ->
    if (f.isDirectory) f.walkTopDown().filter { it.isFile }.map { it to it.relativeTo(f.parentFile ?: f).path.replace('\\', '/') }.toList()
    else listOf(f to f.name)
}
