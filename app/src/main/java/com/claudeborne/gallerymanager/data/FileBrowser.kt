package com.claudeborne.gallerymanager.data

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

// Plain java.io.File access to shared storage; it needs the "All files access" permission (Environment.isExternalStorageManager).

enum class SortBy { NAME, DATE, SIZE, TYPE }

/** One row of a folder listing; size, date and child count are read once so scrolling does no disk work. */
data class Entry(val file: File, val isDir: Boolean, val size: Long, val modified: Long, val children: Int)

/** Every mounted storage volume (internal, SD card, USB) as (label, root folder). */
fun storageRoots(context: Context): List<Pair<String, File>> =
    context.getSystemService(StorageManager::class.java).storageVolumes
        .filter { it.state == Environment.MEDIA_MOUNTED }
        .mapNotNull { v -> v.directory?.let { v.getDescription(context) to it } }

/** The entries of [dir] with folders first, then ordered by [by]. A folder we are not allowed to read lists as empty. */
fun listDir(dir: File, showHidden: Boolean, by: SortBy, ascending: Boolean): List<Entry> {
    val entries = (dir.listFiles() ?: return emptyList())
        .filter { showHidden || !it.name.startsWith(".") }
        .map { f -> f.isDirectory.let { d -> Entry(f, d, if (d) 0 else f.length(), f.lastModified(), if (d) f.list()?.size ?: 0 else 0) } }
    val order: Comparator<Entry> = when (by) {
        SortBy.NAME -> compareBy { it.file.name.lowercase() }
        SortBy.DATE -> compareBy { it.modified }
        SortBy.SIZE -> compareBy { it.size }
        SortBy.TYPE -> compareBy<Entry> { it.file.extension.lowercase() }.thenBy { it.file.name.lowercase() }
    }
    return entries.sortedWith(compareBy<Entry> { !it.isDir }.then(if (ascending) order else order.reversed()))
}

fun File.mime(): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()).orEmpty()
fun File.isImage() = mime().startsWith("image/")
fun File.isVideo() = mime().startsWith("video/")

/** Total bytes and number of files under [dir]. */
suspend fun folderStats(dir: File): Pair<Long, Int> = withContext(Dispatchers.IO) {
    var bytes = 0L
    var count = 0
    dir.walkTopDown().filter { it.isFile }.forEach { bytes += it.length(); count++ }
    bytes to count
}

fun createFolder(parent: File, name: String) = File(parent, name).mkdir()

/** Renames [file] within its folder; false if the name is taken or Android refuses. */
suspend fun renameFile(context: Context, file: File, name: String): Boolean = withContext(Dispatchers.IO) {
    val target = File(file.parentFile, name)
    if (target.exists()) return@withContext false
    val before = file.filePaths()
    val ok = file.renameTo(target)
    if (ok) rescan(context, before + target.filePaths())
    ok
}

/** Copies or moves [files] into [destDir] (folders go in whole), never overwriting: a taken name gets " (1)". Returns how many worked. */
suspend fun transfer(context: Context, files: List<File>, destDir: File, move: Boolean): Int = withContext(Dispatchers.IO) {
    val dest = destDir.canonicalPath
    val touched = mutableListOf<String>()
    var done = 0
    files.forEach { src ->
        val from = src.canonicalPath
        if (dest == from || dest.startsWith(from + File.separator)) return@forEach // a folder into itself
        if (move && src.parentFile?.canonicalPath == dest) return@forEach // already there
        val before = src.filePaths()
        val target = freeName(destDir, src.name)
        val ok = runCatching { (move && src.renameTo(target)) || (src.copyRecursively(target) && (!move || src.deleteRecursively())) }.getOrDefault(false)
        if (ok) { done++; touched += target.filePaths(); if (move) touched += before } else target.deleteRecursively()
    }
    rescan(context, touched)
    done
}

/** Deletes [files] for good. Returns how many were removed. */
suspend fun deleteFiles(context: Context, files: List<File>): Int = withContext(Dispatchers.IO) {
    val paths = files.flatMap { it.filePaths() }
    val done = files.count { it.deleteRecursively() }
    rescan(context, paths)
    done
}

private fun File.filePaths(): List<String> = walkTopDown().filter { it.isFile }.map { it.path }.toList()

private fun freeName(dir: File, name: String): File {
    var target = File(dir, name)
    if (!target.exists()) return target
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var i = 1
    while (target.exists()) target = File(dir, "$base ($i)$ext").also { i++ }
    return target
}

/** Tells MediaStore about changed files (added, moved or gone) so the photo and video library follows. Gives up after 5 s. */
private suspend fun rescan(context: Context, paths: List<String>) {
    if (paths.isEmpty()) return
    withTimeoutOrNull(5000) {
        suspendCancellableCoroutine { cont ->
            val left = AtomicInteger(paths.size)
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ -> if (left.decrementAndGet() == 0 && cont.isActive) cont.resume(Unit) }
        }
    }
}
