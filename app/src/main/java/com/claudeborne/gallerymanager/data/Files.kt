package com.claudeborne.gallerymanager.data

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class FileEntry(val file: File, val isDir: Boolean, val size: Long, val modifiedMs: Long, val childCount: Int) {
    val name: String get() = file.name
}

enum class SortBy(val label: String) { NAME("NAME"), DATE("DATE"), SIZE("SIZE"), TYPE("TYPE") }

val storageRoot: File get() = Environment.getExternalStorageDirectory()

/** Files tab needs "All files access"; the gallery tabs do not. */
fun hasAllFilesAccess() = Environment.isExternalStorageManager()

/** Internal storage plus any SD card / USB drive Android currently mounts. */
fun storageVolumes(context: Context): List<Pair<String, File>> {
    val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    return sm.storageVolumes.mapNotNull { v ->
        v.directory?.let { dir -> (if (v.isPrimary) "INTERNAL" else v.getDescription(context).uppercase()) to dir }
    }.ifEmpty { listOf("INTERNAL" to storageRoot) }
}

fun extOf(file: File) = file.extension.lowercase()

fun mimeOf(file: File): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extOf(file)) ?: "*/*"

enum class Kind { FOLDER, IMAGE, VIDEO, AUDIO, DOC, ARCHIVE, APK, OTHER }

private val docExt = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "rtf", "odt", "ods", "json", "xml", "html", "log")
private val archiveExt = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")

fun kindOf(file: File): Kind {
    if (file.isDirectory) return Kind.FOLDER
    val e = extOf(file)
    val mime = mimeOf(file)
    return when {
        e == "apk" -> Kind.APK
        mime.startsWith("image/") -> Kind.IMAGE
        mime.startsWith("video/") -> Kind.VIDEO
        mime.startsWith("audio/") -> Kind.AUDIO
        e in archiveExt -> Kind.ARCHIVE
        e in docExt -> Kind.DOC
        else -> Kind.OTHER
    }
}

const val RECYCLE_DIR = ".cb_recycle"

/** Folder contents; the recycle bin folder is never listed. Folders always come first. */
suspend fun listDir(dir: File, showHidden: Boolean, sort: SortBy, ascending: Boolean): List<FileEntry> = withContext(Dispatchers.IO) {
    val entries = dir.listFiles().orEmpty()
        .filter { it.name != RECYCLE_DIR && (showHidden || !it.name.startsWith(".")) }
        .map { f ->
            val isDir = f.isDirectory
            FileEntry(f, isDir, if (isDir) 0 else f.length(), f.lastModified(), if (isDir) f.list()?.size ?: 0 else 0)
        }
    val cmp: Comparator<FileEntry> = when (sort) {
        SortBy.NAME -> compareBy { it.name.lowercase() }
        SortBy.DATE -> compareBy { it.modifiedMs }
        SortBy.SIZE -> compareBy { it.size }
        SortBy.TYPE -> compareBy<FileEntry> { extOf(it.file) }.thenBy { it.name.lowercase() }
    }
    val ordered = entries.sortedWith(if (ascending) cmp else cmp.reversed())
    ordered.filter { it.isDir } + ordered.filter { !it.isDir }
}

/** Every file or folder under [dir] whose name contains [query]; stops at [limit] hits. */
suspend fun searchFiles(dir: File, query: String, showHidden: Boolean, limit: Int = 300): List<FileEntry> = withContext(Dispatchers.IO) {
    val q = query.lowercase()
    val out = mutableListOf<FileEntry>()
    dir.walkTopDown()
        .onEnter { it.name != RECYCLE_DIR && (showHidden || it == dir || !it.name.startsWith(".")) }
        .forEach { f ->
            if (f != dir && f.name.lowercase().contains(q) && (showHidden || !f.name.startsWith("."))) {
                out += FileEntry(f, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified(), 0)
                if (out.size >= limit) return@withContext out
            }
        }
    out
}

fun dirSize(f: File): Long = if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

/** "name (1).ext" style name that does not exist yet in [dir]. */
fun uniqueFile(dir: File, name: String): File {
    var f = File(dir, name)
    if (!f.exists()) return f
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
    var i = 1
    while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
    return f
}

fun copyInto(src: File, destDir: File): File {
    val dest = uniqueFile(destDir, src.name)
    if (src.isDirectory) {
        require(!dest.canonicalPath.startsWith(src.canonicalPath + File.separator)) { "Cannot copy a folder into itself" }
        src.copyRecursively(dest, overwrite = false)
    } else src.copyTo(dest, overwrite = false)
    return dest
}

fun moveInto(src: File, destDir: File): File {
    val dest = uniqueFile(destDir, src.name)
    if (src.isDirectory) require(!dest.canonicalPath.startsWith(src.canonicalPath + File.separator)) { "Cannot move a folder into itself" }
    if (src.renameTo(dest)) return dest
    // different storage volume: copy then delete
    copyInto(src, destDir).also { copied ->
        if (copied != dest) copied.renameTo(dest)
        src.deleteRecursively()
    }
    return dest
}

/** Absolute paths of every file in [files] (folders expanded); take this BEFORE moving or deleting so the gallery can be told. */
fun pathsUnder(files: List<File>): List<String> =
    files.flatMap { f -> if (f.isDirectory) f.walkTopDown().filter { it.isFile }.toList() else listOf(f) }.map { it.absolutePath }

/** Tell the gallery about new/removed files so Photos and Albums update. */
fun scanPaths(context: Context, paths: List<String>) {
    if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
}

fun openExternally(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun shareFiles(context: Context, files: List<File>) {
    val list = files.filter { it.isFile }
    if (list.isEmpty()) return
    val uris = ArrayList(list.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
    val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    send.type = if (list.size == 1) mimeOf(list[0]) else "*/*"
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---- file recycle bin: deleted files wait 30 days in .cb_recycle on the same storage, so a mistake is recoverable ----

data class RecycledItem(val stored: File, val originalPath: String, val deletedMs: Long) {
    val displayName: String get() = File(originalPath).name
}

object Recycle {
    private const val KEEP_MS = 30L * 24 * 3600 * 1000
    private fun bin(): File = File(storageRoot, RECYCLE_DIR).also { it.mkdirs() }
    private fun index() = File(bin(), "index.tsv")

    private fun readIndex(): List<RecycledItem> {
        val f = index()
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 3) null else RecycledItem(File(bin(), p[0]), p[1], p[2].toLongOrNull() ?: 0)
        }.filter { it.stored.exists() }
    }

    private fun writeIndex(items: List<RecycledItem>) {
        index().writeText(items.joinToString("\n") { "${it.stored.name}\t${it.originalPath}\t${it.deletedMs}" })
    }

    fun list(): List<RecycledItem> {
        val all = readIndex()
        val now = System.currentTimeMillis()
        val (old, keep) = all.partition { now - it.deletedMs > KEEP_MS }
        if (old.isNotEmpty()) { old.forEach { it.stored.deleteRecursively() }; writeIndex(keep) }
        return keep.sortedByDescending { it.deletedMs }
    }

    fun put(file: File) {
        val stored = File(bin(), "${System.currentTimeMillis()}_${file.name}")
        if (!file.renameTo(stored)) { copyInto(file, bin()).renameTo(stored); file.deleteRecursively() }
        writeIndex(readIndex() + RecycledItem(stored, file.absolutePath, System.currentTimeMillis()))
    }

    fun restore(item: RecycledItem): File {
        val target = File(item.originalPath)
        val dir = target.parentFile ?: storageRoot
        dir.mkdirs()
        val dest = uniqueFile(dir, target.name)
        if (!item.stored.renameTo(dest)) { copyInto(item.stored, dir).renameTo(dest); item.stored.deleteRecursively() }
        writeIndex(readIndex().filter { it.stored != item.stored })
        return dest
    }

    fun deleteForever(item: RecycledItem) {
        item.stored.deleteRecursively()
        writeIndex(readIndex().filter { it.stored != item.stored })
    }

    fun emptyAll() {
        readIndex().forEach { it.stored.deleteRecursively() }
        writeIndex(emptyList())
    }
}
