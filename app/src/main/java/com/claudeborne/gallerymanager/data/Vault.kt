package com.claudeborne.gallerymanager.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Private vault: files are AES-256-GCM encrypted with a key that never leaves the phone's secure hardware (Android Keystore),
 * stored inside the app's private folder, and opened only after the screen lock is confirmed.
 * Data is encrypted in 1 MB chunks so big videos never need to fit in memory.
 */
data class VaultItem(val id: String, val name: String, val size: Long, val addedMs: Long)

/** Whether the vault is unlocked right now; set after the screen-lock check and cleared when the app goes to the background. */
object VaultSession { @Volatile var unlocked = false }

private const val ALIAS = "cb_vault_key"
private const val CHUNK = 1 shl 20

private val secretKey: SecretKey by lazy {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }
}

private fun seal(plain: ByteArray, len: Int): ByteArray {
    val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey) }
    return c.iv + c.doFinal(plain, 0, len)
}

private fun unseal(blob: ByteArray): ByteArray {
    val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, blob, 0, 12)) }
    return c.doFinal(blob, 12, blob.size - 12)
}

private fun encryptStream(input: InputStream, out: OutputStream) {
    val dos = DataOutputStream(out)
    val buf = ByteArray(CHUNK)
    while (true) {
        var n = 0
        while (n < CHUNK) { val r = input.read(buf, n, CHUNK - n); if (r < 0) break; n += r }
        if (n == 0) break
        val blob = seal(buf, n)
        dos.writeInt(blob.size); dos.write(blob)
        if (n < CHUNK) break
    }
    dos.flush()
}

private fun decryptStream(input: InputStream, out: OutputStream) {
    val dis = DataInputStream(input)
    while (true) {
        val len = try { dis.readInt() } catch (e: java.io.EOFException) { break }
        val blob = ByteArray(len)
        dis.readFully(blob)
        out.write(unseal(blob))
    }
}

private fun vaultDir(context: Context) = File(context.filesDir, "vault").also { it.mkdirs() }
private fun tempDir(context: Context) = File(context.cacheDir, "vault_tmp").also { it.mkdirs() }

fun vaultList(context: Context): List<VaultItem> =
    vaultDir(context).listFiles { f -> f.extension == "meta" }.orEmpty().mapNotNull { m ->
        runCatching {
            val p = String(unseal(m.readBytes()), Charsets.UTF_8).split('\t')
            VaultItem(m.nameWithoutExtension, p[0], p[1].toLong(), p[2].toLong())
        }.getOrNull()
    }.sortedByDescending { it.addedMs }

/** Encrypts [src] into the vault and removes the original. */
fun vaultAdd(context: Context, src: File) {
    val id = UUID.randomUUID().toString()
    val dir = vaultDir(context)
    File(dir, "$id.dat").outputStream().buffered().use { out -> src.inputStream().use { encryptStream(it, out) } }
    val meta = "${src.name}\t${src.length()}\t${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8)
    File(dir, "$id.meta").writeBytes(seal(meta, meta.size))
    src.delete()
}

/** Decrypts into [destDir] under the original name. */
fun vaultExport(context: Context, item: VaultItem, destDir: File): File {
    destDir.mkdirs()
    val dest = uniqueFile(destDir, item.name)
    File(vaultDir(context), "${item.id}.dat").inputStream().buffered().use { input -> dest.outputStream().buffered().use { decryptStream(input, it) } }
    return dest
}

/** Decrypts to a temporary file for viewing; call [vaultClearTemp] when finished. */
fun vaultOpenTemp(context: Context, item: VaultItem): File = vaultExport(context, item, File(tempDir(context), item.id))

fun vaultDelete(context: Context, item: VaultItem) {
    File(vaultDir(context), "${item.id}.dat").delete()
    File(vaultDir(context), "${item.id}.meta").delete()
}

fun vaultClearTemp(context: Context) { tempDir(context).deleteRecursively() }
