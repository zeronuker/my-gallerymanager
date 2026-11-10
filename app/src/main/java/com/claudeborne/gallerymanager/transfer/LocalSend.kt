package com.claudeborne.gallerymanager.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.security.auth.x500.X500Principal
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Speaks the open LocalSend protocol (v2), so this phone shows up in the LocalSend app on computers and other phones, and the other
 * way round. It announces itself on the local network, answers the other devices' announcements, and receives files they send once
 * the user accepts them. It runs on plain HTTP (the protocol allows it and announces it), so there is no encryption overhead.
 */
object LocalSend {
    const val PORT = 53317
    const val API = "/api/localsend/v2/"
    private const val GROUP = "224.0.0.167"
    private const val IDLE_MS = 5 * 60_000L

    /** A device seen on the network. */
    class Peer(val alias: String, val host: String, val port: Int, val https: Boolean, val deviceType: String) {
        val seen = System.currentTimeMillis()
    }

    /** A sender waiting for the user to accept its files. The request handler blocks on [answer]. */
    class Pending(val alias: String, val count: Int, val bytes: Long) {
        val answer = CompletableFuture<Boolean>()
    }

    private class LsFile(val name: String, val size: Long, val token: String, val modified: Long?) { @Volatile var done = false }

    private class Session(val id: String, val files: Map<String, LsFile>) { @Volatile var last = System.currentTimeMillis() }

    val peers = ConcurrentHashMap<String, Peer>()
    @Volatile var pending: Pending? = null
        private set
    @Volatile private var session: Session? = null

    private val random = SecureRandom()
    val fingerprint = (1..16).joinToString("") { "%02x".format(random.nextInt(256)) }
    val alias: String get() = Build.MODEL

    private fun randomId() = (1..12).joinToString("") { "%02x".format(random.nextInt(256)) }

    /** What this device says about itself. [announce] is only set for the multicast announcement. */
    fun info(announce: Boolean? = null): JSONObject = JSONObject()
        .put("alias", alias).put("version", "2.0").put("deviceModel", Build.MANUFACTURER).put("deviceType", "mobile")
        .put("fingerprint", fingerprint).put("port", TransferHub.port).put("protocol", "http").put("download", false)
        .apply { if (announce != null) put("announce", announce) }

    fun endSession() {
        session = null
        pending?.answer?.complete(false)
    }

    // ---- receiving: the routes other LocalSend devices call ----

    fun handle(req: HttpRequest, res: HttpResponse) {
        when (req.method + " " + req.path.removePrefix(API)) {
            "GET info" -> res.json(200, info())
            "POST register" -> {
                val peer = JSONObject(String(req.body.readNBytes(MAX_JSON)))
                addPeer(req.remote, peer)
                res.json(200, info())
            }
            "POST prepare-upload" -> prepareUpload(req, res)
            "POST upload" -> upload(req, res)
            "POST cancel" -> { endSession(); res.send(200, "text/plain", ByteArray(0)) }
            else -> res.send(404, "text/plain", "not found".toByteArray())
        }
    }

    private const val MAX_JSON = 4_000_000

    private fun HttpResponse.json(code: Int, o: JSONObject) = send(code, "application/json", o.toString().toByteArray())

    private fun addPeer(host: String, json: JSONObject) {
        if (json.optString("fingerprint") == fingerprint) return // ourselves
        val peer = Peer(json.optString("alias", "Unknown"), host, json.optInt("port", PORT), json.optString("protocol") == "https", json.optString("deviceType", "desktop"))
        peers["$host:${peer.port}"] = peer
    }

    private fun prepareUpload(req: HttpRequest, res: HttpResponse) {
        val body = JSONObject(String(req.body.readNBytes(MAX_JSON)))
        val alias = body.optJSONObject("info")?.optString("alias").orEmpty().ifEmpty { "Unknown device" }
        val list = body.optJSONObject("files") ?: return res.send(400, "text/plain", "no files".toByteArray())
        val files = LinkedHashMap<String, LsFile>()
        for (id in list.keys()) {
            val f = list.getJSONObject(id)
            val modified = f.optJSONObject("metadata")?.optString("modified")?.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            files[id] = LsFile(TransferHub.cleanRel(f.optString("fileName")) ?: "file", f.optLong("size"), randomId(), modified)
        }
        if (files.isEmpty()) return res.send(204, "text/plain", ByteArray(0))
        val current = session
        if (pending != null || (current != null && System.currentTimeMillis() - current.last < IDLE_MS)) return res.send(409, "text/plain", "busy".toByteArray())

        val p = Pending(alias, files.size, files.values.sumOf { it.size })
        pending = p
        val accepted = try { p.answer.get(120, TimeUnit.SECONDS) } catch (_: Exception) { false } finally { pending = null }
        if (!accepted) return res.send(403, "text/plain", "rejected".toByteArray())

        val s = Session(randomId(), files)
        session = s
        res.json(200, JSONObject().put("sessionId", s.id).put("files", JSONObject().also { o -> files.forEach { (id, f) -> o.put(id, f.token) } }))
    }

    private fun upload(req: HttpRequest, res: HttpResponse) {
        val s = session
        val file = s?.files?.get(req.query["fileId"])
        if (s == null || req.query["sessionId"] != s.id || file == null || req.query["token"] != file.token) return res.send(403, "text/plain", "forbidden".toByteArray())
        s.last = System.currentTimeMillis()
        TransferHub.receiveFile(file.name, file.size, file.modified, req.body)
        file.done = true
        s.last = System.currentTimeMillis()
        if (s.files.values.all { it.done }) session = null
        res.send(200, "text/plain", ByteArray(0))
    }

    // ---- finding other devices ----

    /** Announces this device on the local network and listens for the others' announcements; answers them so they see us too. */
    class Discovery(private val context: Context) {
        private var socket: MulticastSocket? = null
        private var lock: WifiManager.MulticastLock? = null

        fun start() {
            lock = context.getSystemService(WifiManager::class.java).createMulticastLock("gm:localsend").apply { setReferenceCounted(false); acquire() }
            val s = try {
                MulticastSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
            } catch (_: IOException) { return } // another LocalSend on this phone already has the port: skip discovery, the rest still works
            socket = s
            val group = InetAddress.getByName(GROUP)
            interfaces().forEach { runCatching { s.joinGroup(InetSocketAddress(group, PORT), it) } }
            thread(isDaemon = true) { listen(s) }
            thread(isDaemon = true) { // a few announcements, in case the first is lost
                for (wait in longArrayOf(0, 1000, 2000)) {
                    Thread.sleep(wait)
                    if (socket?.isClosed != false) return@thread
                    announce(s, group, info(announce = true))
                }
            }
        }

        fun stop() {
            socket?.close()
            socket = null
            lock?.release()
            lock = null
        }

        fun announceAgain() {
            val s = socket ?: return
            thread(isDaemon = true) { announce(s, InetAddress.getByName(GROUP), info(announce = true)) }
        }

        private fun interfaces(): List<NetworkInterface> {
            val all = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            return java.util.Collections.list(all).filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
        }

        private fun announce(s: MulticastSocket, group: InetAddress, json: JSONObject) {
            val data = json.toString().toByteArray()
            for (ni in interfaces()) runCatching { s.networkInterface = ni; s.send(DatagramPacket(data, data.size, group, PORT)) }
        }

        private fun listen(s: MulticastSocket) {
            val buf = ByteArray(8192)
            while (true) {
                val packet = DatagramPacket(buf, buf.size)
                try { s.receive(packet) } catch (_: IOException) { return } // closed by stop()
                val json = runCatching { JSONObject(String(packet.data, 0, packet.length)) }.getOrNull() ?: continue
                if (json.optString("fingerprint") == fingerprint) continue
                val host = packet.address.hostAddress ?: continue
                addPeer(host, json)
                if (json.optBoolean("announce")) respond(s, host, json)
            }
        }

        /** The protocol answers an announcement by registering with the sender over HTTP, and by a multicast reply if that fails. */
        private fun respond(s: MulticastSocket, host: String, peer: JSONObject) {
            thread(isDaemon = true) {
                val ok = try {
                    Http.post(host, peer.optInt("port", PORT), peer.optString("protocol") == "https", API + "register", info().toString().toByteArray()).status == 200
                } catch (_: IOException) { false }
                if (!ok) announce(s, InetAddress.getByName(GROUP), info(announce = false))
            }
        }
    }
}

/** A small HTTP client for talking to other devices; it understands chunked replies and, for HTTPS peers, accepts their self-signed certificates. */
internal object Http {
    class Reply(val status: Int, val body: ByteArray)

    // ponytail: LocalSend devices use self-signed certificates and no certificate authority, so there is nothing to verify against; this only talks to the local network
    private val trustAll: SSLSocketFactory by lazy {
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        val manager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply { init(clientKeys(), arrayOf(manager), null) }.socketFactory
    }

    private const val CLIENT_ALIAS = "gm_localsend_client"

    /**
     * LocalSend's server asks every client for a certificate (it knows devices by theirs) and turns away those that have none. This phone
     * makes one self-signed certificate once, with its key kept in Android Keystore, and presents it on every secure connection.
     * Null (no certificate) if the Keystore refuses, which only costs the ability to send to LocalSend over HTTPS.
     */
    private fun clientKeys(): Array<KeyManager>? = runCatching {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(CLIENT_ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(CLIENT_ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        // the TLS stack hashes the handshake itself and asks the Keystore to sign the digest as it is (NONE)
                        .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                        .setCertificateSubject(X500Principal("CN=Gallery Manager"))
                        .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
                        .setCertificateNotAfter(Date(System.currentTimeMillis() + 10 * 365 * 86_400_000L))
                        .build(),
                )
            }.generateKeyPair()
        }
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, null) }.keyManagers
    }.onFailure { android.util.Log.w("LocalSend", "no client certificate: $it") }.getOrNull()

    /** Says in plain words why talking to [name] failed, from the error [e] (timeout, refused, secure-connection problem). */
    fun explain(e: IOException, name: String): String = when (e) {
        is SocketTimeoutException -> "$name did not answer in time. Check it is on the same network and that its firewall allows LocalSend."
        is ConnectException -> "$name refused the connection. Check LocalSend is open there and its firewall allows it."
        is SSLException -> "The secure connection to $name failed (${e.message ?: "no details"})."
        else -> "Could not reach $name (${e.message ?: "no details"})."
    }

    /** [network] pins the connection to one Wi-Fi network (the direct link) even when the phone's default network is another. */
    fun open(host: String, port: Int, https: Boolean, network: Network? = null, timeoutMs: Int = 30_000): Socket {
        val raw = Socket()
        network?.bindSocket(raw)
        raw.tcpNoDelay = true
        raw.sendBufferSize = 1 shl 20
        raw.soTimeout = timeoutMs
        raw.connect(InetSocketAddress(host, port), 5_000)
        return if (https) trustAll.createSocket(raw, host, port, true) else raw
    }

    /** Reads a response: status line, headers, then the body by Content-Length, by chunks, or to the end of the connection. */
    fun readReply(input: InputStream): Reply {
        val status = (readLine(input) ?: throw IOException("no reply")).split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad reply")
        var length = -1L
        var chunked = false
        while (true) {
            val h = readLine(input) ?: throw IOException("no reply")
            if (h.isEmpty()) break
            val name = h.substringBefore(':').trim().lowercase()
            val value = h.substringAfter(':').trim()
            if (name == "content-length") length = value.toLongOrNull() ?: -1
            if (name == "transfer-encoding" && value.contains("chunked", ignoreCase = true)) chunked = true
        }
        val body = when {
            status == 204 -> ByteArray(0)
            chunked -> ChunkedInput(input).readBytes()
            length >= 0 -> input.readNBytes(length.toInt())
            else -> input.readBytes()
        }
        return Reply(status, body)
    }

    /** One JSON POST on its own connection. */
    fun post(host: String, port: Int, https: Boolean, path: String, json: ByteArray, timeoutMs: Int = 10_000): Reply =
        open(host, port, https, timeoutMs = timeoutMs).use { s ->
            val out = s.getOutputStream()
            out.write("POST $path HTTP/1.1\r\nHost: $host:$port\r\nContent-Type: application/json\r\nContent-Length: ${json.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(json)
            out.flush()
            readReply(s.getInputStream())
        }
}
