package com.claudeborne.gallerymanager.data

import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns as F
import android.webkit.MimeTypeMap
import com.claudeborne.gallerymanager.transfer.BUFFER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.nio.ByteBuffer
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

// Plain java.io.File access to shared storage; it needs the "All files access" permission (Environment.isExternalStorageManager).

enum class SortBy { NAME, DATE, SIZE, TYPE }

/**
 * Category lists on the Files home screen, built from MediaStore so they appear without scanning the whole storage. A file can be in
 * more than one (a big video is in VIDEOS and LARGE); OTHERS holds every file the type categories above it do not match, large or not.
 */
enum class Category { IMAGES, VIDEOS, AUDIO, DOCUMENTS, APKS, ARCHIVES, LARGE, OTHERS }

/** The SQL that picks each type category (not LARGE and OTHERS); OTHERS is built from these so the two can never disagree. */
private val TYPE_CLAUSES: Map<Category, String> = mapOf(
    Category.IMAGES to "${F.MEDIA_TYPE} = ${F.MEDIA_TYPE_IMAGE}",
    Category.VIDEOS to "${F.MEDIA_TYPE} = ${F.MEDIA_TYPE_VIDEO}",
    Category.AUDIO to "${F.MEDIA_TYPE} = ${F.MEDIA_TYPE_AUDIO}",
    Category.DOCUMENTS to "${F.MIME_TYPE} LIKE 'text/%' OR ${F.MIME_TYPE} = 'application/pdf' OR ${F.MIME_TYPE} = 'application/msword' OR " +
        "${F.MIME_TYPE} LIKE 'application/vnd.ms-%' OR ${F.MIME_TYPE} LIKE 'application/vnd.openxmlformats%' OR ${F.MIME_TYPE} LIKE 'application/vnd.oasis%'",
    Category.APKS to "${F.MIME_TYPE} = 'application/vnd.android.package-archive' OR ${F.DISPLAY_NAME} LIKE '%.apk'",
    Category.ARCHIVES to listOf("zip", "rar", "7z", "tar", "gz", "tgz").joinToString(" OR ") { "${F.DISPLAY_NAME} LIKE '%.$it'" },
)

/** Files at or above this size count as "large" (the same 1000-based megabytes the app displays). */
const val LARGE_FILE_BYTES = 50_000_000L

/** One row of a folder listing; size and date are read once so scrolling does no disk work. [children] is -1 until [countChildren] fills it in. */
data class Entry(val file: File, val isDir: Boolean, val size: Long, val modified: Long, val children: Int = -1) {
    /** The lower-case name, made once because sorting a big folder compares names hundreds of thousands of times. */
    val key: String = file.name.lowercase()
}

/** One disk read for the type, size and date together (the File methods read the disk again for each). */
fun entryOf(f: File): Entry = runCatching {
    val a = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
    Entry(f, a.isDirectory, if (a.isDirectory) 0 else a.size(), a.lastModifiedTime().toMillis())
}.getOrElse { Entry(f, f.isDirectory, f.length(), f.lastModified()) }

/** [entries] with the number of items in each folder filled in. Opening every folder is the slow part of a listing, so it is done after the list is already on screen. */
fun countChildren(entries: List<Entry>): List<Entry> = entries.map { if (it.isDir) it.copy(children = it.file.list()?.size ?: 0) else it }

/** Every mounted storage volume (internal, SD card, USB) as (label, root folder). */
fun storageRoots(context: Context): List<Pair<String, File>> =
    context.getSystemService(StorageManager::class.java).storageVolumes
        .filter { it.state == Environment.MEDIA_MOUNTED }
        .mapNotNull { v -> v.directory?.let { v.getDescription(context) to it } }

/** The entries of [dir] with folders first, then ordered by [by]. A folder we are not allowed to read lists as empty. */
fun listDir(dir: File, showHidden: Boolean, by: SortBy, ascending: Boolean): List<Entry> {
    val names = dir.list() ?: return emptyList()
    // each file's details cost a trip to the storage, which waits rather than works, so many are asked for at once
    val entries = names.filter { showHidden || !it.startsWith(".") }.toList().parallelStream().map { entryOf(File(dir, it)) }.toList()
    val order: Comparator<Entry> = when (by) {
        SortBy.NAME -> compareBy { it.key }
        SortBy.DATE -> compareBy { it.modified }
        SortBy.SIZE -> compareBy { it.size }
        SortBy.TYPE -> compareBy<Entry> { it.file.extension.lowercase() }.thenBy { it.key }
    }
    val sorted = entries.sortedWith(compareBy<Entry> { !it.isDir }.then(if (ascending) order else order.reversed()))
    return sorted
}

/**
 * Files and folders under [roots] whose name contains [query], found by walking the folders. [onResults] gets the matches so far
 * every 20 hits and once more at the end, so a long search can show results while it runs. Stops at [limit] matches.
 */
suspend fun searchFiles(roots: List<File>, query: String, showHidden: Boolean, limit: Int = 500, onResults: (List<Entry>) -> Unit) = withContext(Dispatchers.IO) {
    val found = mutableListOf<Entry>()
    search@ for (root in roots) {
        for (f in root.walkTopDown().onEnter { it == root || showHidden || !it.name.startsWith(".") }) {
            ensureActive() // a new keystroke cancels the old search
            if (f == root || !f.name.contains(query, ignoreCase = true) || (!showHidden && f.name.startsWith("."))) continue
            found += entryOf(f)
            if (found.size % 20 == 0) onResults(found.toList())
            if (found.size >= limit) break@search
        }
    }
    onResults(found.toList())
}

/** The files in [category], newest first (largest first for [Category.LARGE]). */
suspend fun categoryFiles(context: Context, category: Category): List<Entry> = withContext(Dispatchers.IO) {
    val where = when (category) {
        Category.LARGE -> "${F.SIZE} >= $LARGE_FILE_BYTES"
        // IFNULL: a file with no mime type must count as "not matched", not as unknown
        Category.OTHERS -> "${F.SIZE} > 0 AND ${F.DISPLAY_NAME} NOT LIKE '.%' AND " + TYPE_CLAUSES.values.joinToString(" AND ") { "NOT IFNULL(($it), 0)" }
        else -> TYPE_CLAUSES.getValue(category)
    }
    val args = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
    }
    val entries = mutableListOf<Entry>()
    // path, size and date all come from the media library's own row, so no file is touched on disk (that is what makes a big library slow)
    val columns = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
    context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), columns, args, null)?.use { c ->
        while (c.moveToNext()) {
            val path = c.getString(0) ?: continue
            if (c.getLong(1) > 0 || category != Category.LARGE) entries += Entry(File(path), false, c.getLong(1), c.getLong(2) * 1000)
        }
    }
    if (category == Category.LARGE) entries.sortedByDescending { it.size } else entries.sortedByDescending { it.modified }
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
        val ok = runCatching { if (move) src.moveTo(target) else src.copyRecursively(target) }.getOrDefault(false)
        if (ok) { done++; touched += target.filePaths(); if (move) touched += before } else target.deleteRecursively()
    }
    rescan(context, touched)
    done
}

/** Packs [files] (folders go in whole) into a new zip in [destDir], named after the single item or "Archive". Returns the zip, or null on failure. */
suspend fun zipFiles(context: Context, files: List<File>, destDir: File): File? = withContext(Dispatchers.IO) {
    val single = files.singleOrNull()
    val zip = freeName(destDir, (if (single == null) "Archive" else if (single.isDirectory) single.name else single.nameWithoutExtension) + ".zip")
    val ok = runCatching {
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            files.forEach { top ->
                top.walkTopDown().forEach { f ->
                    ensureActive()
                    val name = f.relativeTo(top.parentFile ?: top).path.replace('\\', '/')
                    if (f.isDirectory) out.putNextEntry(ZipEntry("$name/")) else { out.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(out) } }
                    out.closeEntry()
                }
            }
        }
    }.isSuccess
    if (ok) { rescan(context, listOf(zip.path)); zip } else { zip.delete(); null }
}

/** File extensions [extractArchive] opens. */
val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z")

/**
 * Extracts [archive] (zip, RAR 4 and 5, or 7z) into a new folder beside it named after the archive, using libarchive. Returns that
 * folder, or the reason it failed. Password-protected archives are not supported.
 */
suspend fun extractArchive(context: Context, archive: File): Result<File> = withContext(Dispatchers.IO) {
    val target = freeName(archive.parentFile ?: return@withContext Result.failure(IOException("no folder")), archive.nameWithoutExtension)
    val base = target.canonicalPath + File.separator
    var found = 0
    val result = runCatching {
        val handle = Archive.readNew()
        try {
            Archive.setCharset(handle, "UTF-8".toByteArray()) // 7z and some RAR names are UTF-16; without this libarchive cannot convert them
            Archive.readSupportFilterAll(handle)
            Archive.readSupportFormatAll(handle)
            Archive.readOpenFileName(handle, archive.path.toByteArray(), 64 * 1024)
            val buffer = ByteBuffer.allocateDirect(BUFFER)
            while (true) {
                ensureActive()
                val entry = Archive.readNextHeader(handle)
                if (entry == 0L) break
                // a name that is not valid UTF-8 is read as raw bytes instead of being dropped
                val name = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.let { String(it, Charsets.UTF_8) } ?: continue
                val out = File(target, name).canonicalFile
                if (!out.path.startsWith(base)) continue // an entry that tries to leave the folder ("../x") is skipped
                // zip, 7z and RAR files are regular unless they say otherwise (some archives leave the type unset)
                when (ArchiveEntry.filetype(entry) and ArchiveEntry.AE_IFMT) {
                    ArchiveEntry.AE_IFLNK, ArchiveEntry.AE_IFCHR, ArchiveEntry.AE_IFBLK, ArchiveEntry.AE_IFIFO, ArchiveEntry.AE_IFSOCK -> Unit // links and special files are left out
                    ArchiveEntry.AE_IFDIR -> { out.mkdirs(); found++ }
                    else -> {
                        found++
                        out.parentFile?.mkdirs()
                        out.outputStream().channel.use { channel ->
                            while (true) {
                                buffer.clear()
                                Archive.readData(handle, buffer)
                                if (buffer.position() == 0) break
                                buffer.flip()
                                while (buffer.hasRemaining()) channel.write(buffer)
                            }
                        }
                        if (ArchiveEntry.mtimeIsSet(entry)) out.setLastModified(ArchiveEntry.mtime(entry) * 1000)
                    }
                }
            }
            if (found == 0) throw IOException("the archive holds no files this app can extract (it may be encrypted)")
        } finally {
            Archive.free(handle)
        }
    }
    result.fold(
        { rescan(context, target.filePaths()); Result.success(target) },
        { target.deleteRecursively(); Result.failure(IOException(it.message ?: "could not read the archive")) },
    )
}

internal fun File.filePaths(): List<String> = walkTopDown().filter { it.isFile }.map { it.path }.toList()

/** Renames, or when that cannot work (another storage volume) copies and then removes the original. */
internal fun File.moveTo(target: File): Boolean = renameTo(target) || (copyRecursively(target) && deleteRecursively())

internal fun freeName(dir: File, name: String): File {
    var target = File(dir, name)
    if (!target.exists()) return target
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var i = 1
    while (target.exists()) target = File(dir, "$base ($i)$ext").also { i++ }
    return target
}

/** Tells MediaStore about changed files (added, moved or gone) so the photo and video library follows. Gives up after 5 s. */
internal suspend fun rescan(context: Context, paths: List<String>) {
    if (paths.isEmpty()) return
    withTimeoutOrNull(5000) {
        suspendCancellableCoroutine { cont ->
            val left = AtomicInteger(paths.size)
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ -> if (left.decrementAndGet() == 0 && cont.isActive) cont.resume(Unit) }
        }
    }
}
