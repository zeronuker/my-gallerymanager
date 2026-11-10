package com.claudeborne.gallerymanager.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

// Duplicate finder: files with identical content, found by size first, then a hash of the first 64 KB, then a hash of the whole file.

/** Files smaller than this are ignored; tiny cache and thumbnail files would only clutter the results. */
const val DUP_MIN_BYTES = 100 * 1024L

private const val HEAD_BYTES = 64 * 1024L

/** [files] all have the same content and are [size] bytes each, oldest first. */
class DupGroup(val size: Long, val files: List<File>) {
    val wasted get() = size * (files.size - 1)
}

/** Every group of identical files under [roots] (hidden folders skipped), the biggest waste first. [onProgress] gets a status line. */
suspend fun findDuplicates(roots: List<File>, onProgress: (String) -> Unit): List<DupGroup> = withContext(Dispatchers.IO) {
    val job = coroutineContext.job
    val bySize = HashMap<Long, MutableList<File>>()
    var seen = 0
    for (root in roots) {
        val top = root.toPath()
        Files.walkFileTree(top, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                job.ensureActive() // leaving the screen cancels the search
                return if (dir != top && dir.fileName.toString().startsWith(".")) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && attrs.size() >= DUP_MIN_BYTES && !file.fileName.toString().startsWith(".")) {
                    bySize.getOrPut(attrs.size()) { mutableListOf() } += file.toFile()
                }
                if (++seen % 500 == 0) onProgress("SCANNING $seen FILES")
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
        })
    }

    val sameSize = bySize.values.filter { it.size > 1 }
    val total = sameSize.sumOf { it.size }
    var checked = 0
    val groups = ArrayList<DupGroup>()
    for (candidates in sameSize) {
        // most files that share a size differ in the first 64 KB, so the whole-file hash only runs on what is left
        for (sameHead in candidates.sameHash(HEAD_BYTES, job)) {
            for (identical in sameHead.sameHash(Long.MAX_VALUE, job)) groups += DupGroup(identical[0].length(), identical.sortedBy { it.lastModified() })
        }
        checked += candidates.size
        onProgress("COMPARING $checked OF $total FILES")
    }
    groups.sortedByDescending { it.wasted }
}

/** The subsets of this list (two files or more) whose first [limit] bytes hash the same; unreadable files drop out. */
private fun List<File>.sameHash(limit: Long, job: Job): List<List<File>> =
    groupBy { f -> try { hash(f, limit, job) } catch (_: IOException) { null } }.filterKeys { it != null }.values.filter { it.size > 1 }

private fun hash(file: File, limit: Long, job: Job): String {
    val md = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(256 * 1024)
    var left = limit
    file.inputStream().use { input ->
        while (left > 0) {
            job.ensureActive()
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) break
            md.update(buffer, 0, n)
            left -= n
        }
    }
    return Base64.encodeToString(md.digest(), Base64.NO_WRAP)
}
