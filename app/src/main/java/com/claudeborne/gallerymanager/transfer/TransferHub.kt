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

/**
 * File transfer between this phone and a computer's browser or another phone running this app. The phone side runs a small HTTP server
 * (a computer opens its address in a browser; another phone's app uploads to it); a phone sending to another phone is the client.
 * Every request needs the session token, which is part of the address shown on screen and in its QR code.
 */
object TransferHub {
    const val DEFAULT_PORT = 8686

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
    private lateinit var appContext: Context

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
    }

    fun stop() {
        server?.stop()
        server = null
        running = false
    }

    /** Addresses another device can open, Wi-Fi first. */
    fun addresses(): List<String> {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        return java.util.Collections.list(interfaces)
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> java.util.Collections.list(ni.inetAddresses).filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { ni.name to it.hostAddress } }
            .sortedBy { if (it.first.startsWith("wlan")) 0 else 1 }
            .map { "http://${it.second}:$port/?t=$token" }
    }

    // ---- server side ----

    private fun handle(req: HttpRequest, res: HttpResponse) {
        if (req.query["t"] != token) return res.send(403, "text/plain", "forbidden".toByteArray())
        when {
            req.method == "GET" && req.path == "/" -> res.send(200, "text/html; charset=utf-8", WebPage.html(Build.MODEL, token).toByteArray())
            req.method == "GET" && req.path == "/api/info" -> res.send(200, "application/json", JSONObject().put("name", Build.MODEL).toString().toByteArray())
            req.method == "GET" && req.path == "/api/offers" -> res.send(200, "application/json", offersJson().toByteArray())
            req.method == "GET" && req.path.startsWith("/api/file/") -> download(req, res)
            req.method == "PUT" && req.path == "/api/upload" -> upload(req, res)
            else -> res.send(404, "text/plain", "not found".toByteArray())
        }
    }

    private fun offersJson() = JSONArray().apply {
        offers.forEach { put(JSONObject().put("id", it.id).put("name", it.rel).put("size", it.file.length())) }
    }.toString()

    /** Keeps a path sent by the other side inside the save folder: no empty parts, no "." and no "..". */
    private fun cleanRel(name: String?): String? =
        name.orEmpty().replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.takeIf { it.isNotEmpty() }?.joinToString("/")

    private fun upload(req: HttpRequest, res: HttpResponse) {
        val rel = cleanRel(req.query["name"]) ?: return res.send(400, "text/plain", "bad name".toByteArray())
        val size = req.body.total
        val parent = File(saveDir, rel.substringBeforeLast('/', "")).apply { mkdirs() }
        val target = freeName(parent, rel.substringAfterLast('/'))
        val part = File(parent, target.name + ".part") // renamed only once complete, so a broken transfer leaves no half file
        val item = Item(true, rel, size).also { items.add(it) }
        try {
            part.outputStream().use { out ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = req.body.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    item.done += n
                    bytes.addAndGet(n.toLong())
                }
            }
            if (item.done != size) throw IOException("incomplete")
            part.renameTo(target)
            req.query["mtime"]?.toLongOrNull()?.let { target.setLastModified(it) }
            item.finished = true
            MediaScannerConnection.scanFile(appContext, arrayOf(target.path), null, null)
            res.send(200, "application/json", JSONObject().put("ok", true).put("name", target.name).toString().toByteArray())
        } catch (e: IOException) {
            item.error = e.message ?: "failed"
            part.delete()
            throw e
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

    /** Uploads [files] (file, path to show) to the phone at [host]:[port] over [parallel] connections. Returns how many arrived. */
    suspend fun send(files: List<Pair<File, String>>, host: String, port: Int, token: String, network: Network? = null, parallel: Int = 4): Int = withContext(Dispatchers.IO) {
        reset()
        val queue = ConcurrentLinkedQueue(files.map { (f, rel) -> Item(false, rel, f.length()).also { items.add(it) } to f })
        val ok = AtomicInteger()
        coroutineScope {
            repeat(minOf(parallel, files.size)) {
                launch {
                    var socket: Socket? = null
                    try {
                        while (true) {
                            val (item, file) = queue.poll() ?: break
                            try {
                                val s = socket ?: connect(host, port, network).also { socket = it }
                                put(s, host, token, file, item)
                                ok.incrementAndGet()
                            } catch (e: IOException) {
                                item.error = e.message ?: "failed"
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
        ok.get()
    }

    /** [network] pins the connection to one Wi-Fi network (the direct link) even when the phone's default network is another. */
    private fun connect(host: String, port: Int, network: Network?) = Socket().apply {
        network?.bindSocket(this)
        tcpNoDelay = true
        sendBufferSize = 1 shl 20
        soTimeout = 30_000
        connect(InetSocketAddress(host, port), 5_000)
    }

    private fun CoroutineScope.put(socket: Socket, host: String, token: String, file: File, item: Item) {
        val out = socket.getOutputStream()
        val name = URLEncoder.encode(item.name, "UTF-8")
        out.write("PUT /api/upload?t=$token&name=$name&mtime=${file.lastModified()} HTTP/1.1\r\nHost: $host\r\nContent-Length: ${item.size}\r\nConnection: keep-alive\r\n\r\n".toByteArray())
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
        val reply = socket.getInputStream()
        val status = readLine(reply) ?: throw IOException("no reply")
        var length = 0L
        while (true) {
            val h = readLine(reply) ?: throw IOException("no reply")
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toLongOrNull() ?: 0
        }
        reply.skipFully(length)
        if (!status.contains(" 200")) throw IOException(status)
        item.finished = true
    }

    private fun InputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) { if (read() < 0) throw IOException("no reply"); left-- }
    }
}

/** Every file under the selected files and folders as (file, path to show): a folder keeps its name and its structure. */
fun expandForSend(selection: List<File>): List<Pair<File, String>> = selection.flatMap { f ->
    if (f.isDirectory) f.walkTopDown().filter { it.isFile }.map { it to it.relativeTo(f.parentFile ?: f).path.replace('\\', '/') }.toList()
    else listOf(f to f.name)
}
