package com.claudeborne.gallerymanager.transfer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

// A tiny HTTP/1.1 server and the helpers the transfer client shares with it. It exists so that file bodies stream straight between the
// socket and the file with big buffers and keep-alive connections; no web server library is needed for that.

const val BUFFER = 256 * 1024

/**
 * [body] ends where the request body ends (after Content-Length bytes, or at the last chunk); [bodyLength] is -1 for a chunked body.
 * A handler that reads less than all of it makes the connection unusable for the next request.
 */
class HttpRequest(
    val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>,
    val body: InputStream, val bodyLength: Long, val remote: String,
)

/** A request body that knows whether it was read to its end. */
interface Body { val finished: Boolean }

class LimitedInput(private val source: InputStream, total: Long) : InputStream(), Body {
    private var remaining = total
    override val finished get() = remaining <= 0

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = source.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }
}

/** A body sent with Transfer-Encoding: chunked, which some clients use when they stream a file of unknown length. */
class ChunkedInput(private val source: InputStream) : InputStream(), Body {
    private var left = 0L
    private var needLineBreak = false
    override var finished = false
        private set

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (finished) return -1
        if (left == 0L) {
            if (needLineBreak) { readLine(source); needLineBreak = false }
            val size = (readLine(source) ?: throw IOException("bad chunk")).substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("bad chunk")
            if (size == 0L) {
                while (true) { val trailer = readLine(source); if (trailer == null || trailer.isEmpty()) break }
                finished = true
                return -1
            }
            left = size
        }
        val n = source.read(b, off, minOf(len.toLong(), left).toInt())
        if (n < 0) throw IOException("chunk ended early")
        left -= n
        if (left == 0L) needLineBreak = true
        return n
    }
}

/** Writes what it is given as HTTP chunks; closing it ends the body and leaves [out] open for the next request. */
class ChunkedOutput(private val out: OutputStream) : OutputStream() {
    private var closed = false
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        out.write((Integer.toHexString(len) + "\r\n").toByteArray())
        out.write(b, off, len)
        out.write("\r\n".toByteArray())
    }
    override fun flush() = out.flush()
    override fun close() {
        if (closed) return
        closed = true
        out.write("0\r\n\r\n".toByteArray())
        out.flush()
    }
}

/** Writes the one response to a request: call [send], [sendStream] or [sendChunked] exactly once. */
class HttpResponse(private val out: OutputStream) {
    var sent = false
        private set

    /** A [length] of -1 means the length is not known, so the body goes out in chunks. */
    private fun head(code: Int, type: String, length: Long, extra: Map<String, String>) {
        val framing = if (length >= 0) "Content-Length: $length" else "Transfer-Encoding: chunked"
        val sb = StringBuilder("HTTP/1.1 $code ${STATUS[code] ?: "OK"}\r\nContent-Type: $type\r\n$framing\r\nCache-Control: no-store\r\n")
        extra.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        out.write(sb.append("\r\n").toString().toByteArray())
        sent = true
    }

    fun send(code: Int, type: String, bytes: ByteArray, extra: Map<String, String> = emptyMap()) {
        head(code, type, bytes.size.toLong(), extra)
        out.write(bytes)
    }

    /** For a body whose length is not known up front (a zip made as it goes): [body] writes it into the stream it is given. */
    fun sendChunked(code: Int, type: String, extra: Map<String, String>, body: (OutputStream) -> Unit) {
        head(code, type, -1, extra)
        ChunkedOutput(out).use(body)
    }

    /** Copies exactly [length] bytes of [input]; [onBytes] is told about each chunk so the caller can show progress. */
    fun sendStream(code: Int, type: String, length: Long, extra: Map<String, String>, input: InputStream, onBytes: (Int) -> Unit) {
        head(code, type, length, extra)
        val buf = ByteArray(BUFFER)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("file ended early")
            out.write(buf, 0, n)
            left -= n
            onBytes(n)
        }
    }

    private companion object {
        val STATUS = mapOf(200 to "OK", 206 to "Partial Content", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 416 to "Range Not Satisfiable", 500 to "Internal Server Error")
    }
}

/** Reads one header line (without the line break) a byte at a time, so nothing of the body is consumed. Null at the end of the stream. */
fun readLine(input: InputStream): String? {
    val sb = StringBuilder()
    while (true) {
        val b = input.read()
        if (b < 0) return if (sb.isEmpty()) null else sb.toString()
        if (b == '\n'.code) return sb.toString().trimEnd('\r')
        if (sb.length > 8192) return null
        sb.append(b.toChar())
    }
}

class MiniHttpServer(private val handler: (HttpRequest, HttpResponse) -> Unit) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    /** Starts listening on [preferred], or on any free port if that one is taken. Returns the port in use. */
    fun start(preferred: Int): Int {
        val s = bind(preferred) ?: bind(0) ?: throw IOException("no free port")
        server = s
        pool.execute {
            try { while (true) { val c = s.accept(); pool.execute { serve(c) } } } catch (_: IOException) { /* closed by stop() */ }
        }
        return s.localPort
    }

    fun stop() {
        server?.close()
        pool.shutdownNow()
    }

    private fun bind(port: Int): ServerSocket? = try {
        ServerSocket().apply { reuseAddress = true; receiveBufferSize = 1 shl 20; bind(InetSocketAddress(port)) }
    } catch (_: IOException) { null }

    private fun serve(socket: Socket) {
        try {
            socket.use { s ->
                s.tcpNoDelay = true
                s.soTimeout = 60_000
                s.sendBufferSize = 1 shl 20
                val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
                val output = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) continue
                    val parts = line.split(' ')
                    if (parts.size < 3) return
                    val headers = HashMap<String, String>()
                    while (true) {
                        val h = readLine(input) ?: return
                        if (h.isEmpty()) break
                        val i = h.indexOf(':')
                        if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
                    }
                    val target = parts[1]
                    val path = target.substringBefore('?')
                    val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }
                        .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                    val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
                    val length = headers["content-length"]?.toLongOrNull() ?: 0
                    val body: InputStream = if (chunked) ChunkedInput(input) else LimitedInput(input, length)
                    val res = HttpResponse(output)
                    try {
                        handler(HttpRequest(parts[0], path, query, headers, body, if (chunked) -1 else length, s.inetAddress?.hostAddress.orEmpty()), res)
                    } catch (e: IOException) {
                        return
                    } catch (e: Exception) {
                        if (res.sent) return
                        res.send(500, "text/plain", (e.message ?: "error").toByteArray())
                    }
                    output.flush()
                    if (!(body as Body).finished || headers["connection"].equals("close", ignoreCase = true)) return
                }
            }
        } catch (_: IOException) { /* the other side went away */ }
    }
}
