package com.claudeborne.gallerymanager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class ScanResult(
    val bytesByKind: Map<Kind, Long>,
    val countByKind: Map<Kind, Int>,
    val largest: List<FileEntry>,
    val recent: List<FileEntry>,
    val sameSize: Map<Long, List<File>>, // only sizes shared by 2+ files: duplicate candidates
    val junk: List<FileEntry>,
    val emptyDirs: List<File>,
)

private val junkExt = setOf("tmp", "temp", "log", "bak", "old", "thumb", "crdownload", "part")
private val junkDirs = setOf(".thumbnails", "thumbnails", ".cache", "cache", ".trash", ".tmp", "lost.dir")

private fun isJunk(f: File): Boolean {
    val e = extOf(f)
    if (e in junkExt) return true
    if (f.length() == 0L) return true
    return f.parentFile?.name?.lowercase() in junkDirs
}

/** One walk over the whole storage; feeds the storage overview, large files, recent files, duplicates and cleanup. */
suspend fun scanStorage(root: File, onProgress: (Int) -> Unit = {}): ScanResult = withContext(Dispatchers.IO) {
    val bytes = HashMap<Kind, Long>()
    val counts = HashMap<Kind, Int>()
    val bySize = HashMap<Long, MutableList<File>>()
    val largest = ArrayList<FileEntry>()
    val recent = ArrayList<FileEntry>()
    val junk = ArrayList<FileEntry>()
    val emptyDirs = ArrayList<File>()
    var seen = 0

    root.walkTopDown()
        .onEnter { d ->
            // Android/data and obb are private to other apps; the recycle bin is ours
            d.name != RECYCLE_DIR && !(d.parentFile?.name == "Android" && (d.name == "data" || d.name == "obb"))
        }
        .forEach { f ->
            if (f.isDirectory) {
                if (f != root && f.list()?.isEmpty() == true) emptyDirs += f
                return@forEach
            }
            val size = f.length()
            val kind = kindOf(f)
            bytes[kind] = (bytes[kind] ?: 0) + size
            counts[kind] = (counts[kind] ?: 0) + 1
            if (size >= 1024) bySize.getOrPut(size) { ArrayList(1) }.add(f)
            val entry = FileEntry(f, false, size, f.lastModified(), 0)
            // keep only the top 100 of each list so memory stays flat on huge storages
            if (largest.size < 100 || size > largest.last().size) { largest += entry; largest.sortByDescending { it.size }; if (largest.size > 100) largest.removeAt(largest.lastIndex) }
            if (recent.size < 100 || entry.modifiedMs > recent.last().modifiedMs) { recent += entry; recent.sortByDescending { it.modifiedMs }; if (recent.size > 100) recent.removeAt(recent.lastIndex) }
            if (isJunk(f)) junk += entry
            if (++seen % 500 == 0) onProgress(seen)
        }

    ScanResult(bytes, counts, largest, recent, bySize.filterValues { it.size > 1 }, junk, emptyDirs)
}

private fun hashOf(f: File): String {
    val md = MessageDigest.getInstance("SHA-1")
    f.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Groups of byte-identical files (same size first, then full content hash). Each group has 2+ files, newest first. */
suspend fun findDuplicates(sameSize: Map<Long, List<File>>, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<List<File>> = withContext(Dispatchers.IO) {
    val groups = ArrayList<List<File>>()
    val total = sameSize.values.sumOf { it.size }
    var done = 0
    for ((_, files) in sameSize) {
        val byHash = HashMap<String, MutableList<File>>()
        for (f in files) {
            runCatching { hashOf(f) }.getOrNull()?.let { byHash.getOrPut(it) { ArrayList() }.add(f) }
            onProgress(++done, total)
        }
        byHash.values.filter { it.size > 1 }.forEach { g -> groups += g.sortedByDescending { it.lastModified() } }
    }
    groups.sortedByDescending { it[0].length() * (it.size - 1) } // most wasted space first
}

/** Every file of one [kind] on the storage, biggest first (capped), for the tap-a-category view in Tools. */
suspend fun filesOfKind(root: File, kind: Kind, limit: Int = 3000): List<FileEntry> = withContext(Dispatchers.IO) {
    root.walkTopDown()
        .onEnter { d -> d.name != RECYCLE_DIR && !(d.parentFile?.name == "Android" && (d.name == "data" || d.name == "obb")) }
        .filter { it.isFile && kindOf(it) == kind }
        .map { FileEntry(it, false, it.length(), it.lastModified(), 0) }
        .sortedByDescending { it.size }
        .take(limit)
        .toList()
}
