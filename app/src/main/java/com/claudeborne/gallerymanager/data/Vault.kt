package com.claudeborne.gallerymanager.data

import android.content.Context
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.claudeborne.gallerymanager.transfer.BUFFER
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The safe folder. Files are encrypted (AES-256-GCM in 1 MB chunks, so videos stream) into `Documents/.GalleryVault`, with random file
 * names and an encrypted index that holds the real names. One random vault key does all the work. It is stored twice in vault.json:
 * sealed by an Android Keystore key (the everyday unlock, behind the fingerprint or PIN prompt) and sealed by a recovery key that the
 * user writes down once (the way back in after an uninstall, a new phone, or a lost Keystore key). The vault key is held in memory
 * only while the safe folder screen is open.
 */
object Vault {
    /** One file in the vault. [name] is its path inside the vault ("Trips/a.jpg"), [origin] where it came from. */
    class Item(val id: String, val name: String, val size: Long, val modified: Long, val origin: String, val mime: String)

    private const val ALIAS = "gm_vault_wrap"
    private const val SEGMENT = 1 shl 20
    private val random = SecureRandom()
    @Volatile private var key: ByteArray? = null
    private var index: MutableList<Item> = mutableListOf()
    private lateinit var appContext: Context

    val dir: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), ".GalleryVault")
    private val header get() = File(dir, "vault.json")
    private val indexFile get() = File(dir, "index.gmv")

    val exists: Boolean get() = header.exists()
    val unlocked: Boolean get() = key != null

    /** Where EXPORT puts decrypted copies. */
    val exportDir: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Exported")

    // ---- keys ----

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    private fun seal(key: SecretKey, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) } // the cipher picks a fresh random IV
        return c.iv + c.doFinal(data)
    }

    private fun unseal(key: SecretKey, blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12)) }
        return c.doFinal(blob, 12, blob.size - 12)
    }

    private fun recoveryKey(code: ByteArray): SecretKey =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("gm-vault-recovery-v1".toByteArray() + code), "AES") // the code is 160 random bits, so no slow key stretching is needed

    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun encode(b: ByteArray): String {
        var acc = 0
        var bits = 0
        val sb = StringBuilder()
        for (x in b) {
            acc = (acc shl 8) or (x.toInt() and 0xff)
            bits += 8
            while (bits >= 5) { sb.append(B32[(acc shr (bits - 5)) and 31]); bits -= 5 }
            acc = acc and ((1 shl bits) - 1)
        }
        return sb.toString()
    }

    private fun decode(text: String): ByteArray? {
        val letters = text.uppercase().filter { it in B32 }
        if (letters.length != 32) return null
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>()
        for (ch in letters) {
            acc = (acc shl 5) or B32.indexOf(ch)
            bits += 5
            if (bits >= 8) { out += ((acc shr (bits - 8)) and 0xff).toByte(); bits -= 8 }
            acc = acc and ((1 shl bits) - 1)
        }
        return out.toByteArray()
    }

    // ---- life cycle ----

    /** Makes a new vault and unlocks it. Returns the recovery key to show the user once, as 8 groups of 4. */
    suspend fun create(context: Context): String = withContext(Dispatchers.IO) {
        appContext = context.applicationContext
        dir.mkdirs()
        File(dir, ".nomedia").createNewFile() // keeps the media scanner out
        val k = ByteArray(32).also(random::nextBytes)
        val code = ByteArray(20).also(random::nextBytes)
        writeHeader(k, code)
        key = k
        index = mutableListOf()
        writeIndex()
        encode(code).chunked(4).joinToString("-")
    }

    private fun writeHeader(k: ByteArray, code: ByteArray?) {
        val old = runCatching { JSONObject(header.readText()) }.getOrNull()
        val h = JSONObject().put("v", 1).put("kc", android.util.Base64.encodeToString(seal(keystoreKey(), k), 0))
            .put("rc", code?.let { android.util.Base64.encodeToString(seal(recoveryKey(it), k), 0) } ?: old?.optString("rc"))
        val tmp = File(dir, "vault.json.tmp")
        tmp.writeText(h.toString())
        tmp.renameTo(header)
    }

    /** Opens the vault with the Keystore key (call after the fingerprint or PIN prompt). False if that key is gone: use [recover]. */
    suspend fun unlock(context: Context): Boolean = withContext(Dispatchers.IO) {
        appContext = context.applicationContext
        runCatching {
            val blob = android.util.Base64.decode(JSONObject(header.readText()).getString("kc"), 0)
            key = unseal(keystoreKey(), blob)
            readIndex()
        }.isSuccess
    }

    /** Opens the vault with the written-down recovery key, then seals it to a fresh Keystore key. False if the code is wrong. */
    suspend fun recover(context: Context, text: String): Boolean = withContext(Dispatchers.IO) {
        appContext = context.applicationContext
        val code = decode(text) ?: return@withContext false
        runCatching {
            val blob = android.util.Base64.decode(JSONObject(header.readText()).getString("rc"), 0)
            val k = unseal(recoveryKey(code), blob)
            key = k
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
            writeHeader(k, null)
            readIndex()
        }.isSuccess
    }

    /** Forgets the key and removes every decrypted copy. */
    fun lock(context: Context) {
        key?.fill(0)
        key = null
        index = mutableListOf()
        File(context.cacheDir, "vault").deleteRecursively()
    }

    // ---- index ----

    private fun aesKey() = SecretKeySpec(key ?: throw IOException("the safe folder is locked"), "AES")

    private fun readIndex() {
        index = if (!indexFile.exists()) mutableListOf() else {
            val json = JSONArray(String(unseal(aesKey(), indexFile.readBytes())))
            MutableList(json.length()) { i -> json.getJSONObject(i).let { Item(it.getString("id"), it.getString("name"), it.getLong("size"), it.getLong("modified"), it.getString("origin"), it.getString("mime")) } }
        }
    }

    private fun writeIndex() {
        val json = JSONArray().apply { index.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size).put("modified", it.modified).put("origin", it.origin).put("mime", it.mime)) } }
        val tmp = File(dir, "index.gmv.tmp")
        tmp.writeBytes(seal(aesKey(), json.toString().toByteArray()))
        tmp.renameTo(indexFile)
    }

    fun items(): List<Item> = index.toList()

    // ---- files ----

    private fun streaming() = AesGcmHkdfStreaming(key ?: throw IOException("the safe folder is locked"), "HmacSha256", 32, SEGMENT, 0)

    private fun encryptTo(src: File, id: String) {
        File(dir, "$id.gmv").outputStream().channel.use { out ->
            streaming().newEncryptingChannel(out, id.toByteArray()).use { enc ->
                src.inputStream().channel.use { input ->
                    val buf = ByteBuffer.allocate(BUFFER)
                    while (input.read(buf) >= 0) { buf.flip(); while (buf.hasRemaining()) enc.write(buf); buf.clear() }
                }
            }
        }
    }

    private fun decryptTo(item: Item, dest: File) {
        dest.parentFile?.mkdirs()
        File(dir, "${item.id}.gmv").inputStream().channel.use { input ->
            streaming().newDecryptingChannel(input, item.id.toByteArray()).use { dec ->
                dest.outputStream().channel.use { out ->
                    val buf = ByteBuffer.allocate(BUFFER)
                    while (dec.read(buf) >= 0) { buf.flip(); while (buf.hasRemaining()) out.write(buf); buf.clear() }
                }
            }
        }
    }

    private fun newId() = (1..16).joinToString("") { "%02x".format(random.nextInt(256)) }

    /** Encrypts [tops] (files and folders) into the vault and deletes the originals. Returns how many files went in. */
    suspend fun add(context: Context, tops: List<File>): Int = withContext(Dispatchers.IO) {
        val moved = mutableListOf<String>()
        var done = 0
        for (top in tops) {
            val base = top.parentFile ?: continue
            val files = if (top.isDirectory) top.walkTopDown().filter { it.isFile }.toList() else listOf(top)
            for (batch in files.chunked(25)) {
                ensureActive()
                val added = mutableListOf<File>()
                for (f in batch) {
                    val id = newId()
                    try {
                        encryptTo(f, id)
                        index += Item(id, f.relativeTo(base).path.replace('\\', '/'), f.length(), f.lastModified(), f.path, f.mime())
                        added += f
                    } catch (e: IOException) {
                        File(dir, "$id.gmv").delete()
                    }
                }
                writeIndex() // the entries are on disk before any original is deleted
                added.forEach { if (it.delete()) { moved += it.path; done++ } }
            }
            if (top.isDirectory && top.walkTopDown().none { it.isFile }) top.deleteRecursively() // what is left is empty folders
        }
        rescan(context, moved)
        done
    }

    /** A readable copy of [item] in the app's cache, for opening. It is removed by [lock]. */
    suspend fun open(context: Context, item: Item): File = withContext(Dispatchers.IO) {
        File(context.cacheDir, "vault/${item.id}/${item.name.substringAfterLast('/')}").also { decryptTo(item, it) }
    }

    /** Decrypts [items] back to where they came from, or under [into] keeping their folders. Removes them from the vault. Returns how many worked. */
    suspend fun restore(context: Context, items: List<Item>, into: File? = null): Int = withContext(Dispatchers.IO) {
        val back = mutableListOf<String>()
        val gone = mutableSetOf<String>()
        for (item in items) {
            ensureActive()
            val wanted = if (into != null) File(into, item.name) else File(item.origin)
            val parent = wanted.parentFile ?: continue
            parent.mkdirs()
            val target = freeName(parent, wanted.name)
            try {
                decryptTo(item, target)
                target.setLastModified(item.modified)
                back += target.path
                gone += item.id
            } catch (e: IOException) {
                target.delete()
            }
        }
        removeFromVault(gone)
        rescan(context, back)
        gone.size
    }

    /** Deletes [items] from the vault for good. */
    suspend fun delete(items: List<Item>) = withContext(Dispatchers.IO) { removeFromVault(items.map { it.id }.toSet()) }

    private fun removeFromVault(ids: Set<String>) {
        index.removeAll { it.id in ids }
        writeIndex()
        ids.forEach { File(dir, "$it.gmv").delete() }
    }
}
