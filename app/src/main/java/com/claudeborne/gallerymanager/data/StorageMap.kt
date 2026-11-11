package com.claudeborne.gallerymanager.data

import kotlinx.coroutines.Dispatchers
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
import kotlin.coroutines.coroutineContext

// The storage map: a size tree of a folder, drawn as a treemap (every rectangle's area is its size, like WinDirStat).

enum class FileType { PHOTO, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APP, OTHER }

private val ARCHIVES = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
private val APPS = setOf("apk", "xapk", "apks", "aab")

fun File.fileType(): FileType {
    val ext = extension.lowercase()
    val mime = mime()
    return when {
        ext in APPS -> FileType.APP
        ext in ARCHIVES -> FileType.ARCHIVE
        mime.startsWith("image/") -> FileType.PHOTO
        mime.startsWith("video/") -> FileType.VIDEO
        mime.startsWith("audio/") -> FileType.AUDIO
        mime == "application/pdf" || mime == "application/msword" || mime.startsWith("text/") || mime.contains("officedocument") ||
            mime.startsWith("application/vnd.ms-") || mime.startsWith("application/vnd.oasis") -> FileType.DOCUMENT
        else -> FileType.OTHER
    }
}

/** The extensions the map gives a colour of their own; every other file counts as "other". */
val MAP_EXTENSIONS = listOf("jpg", "heic", "png", "webp", "dng", "mp4", "mov", "mkv", "opus", "mp3", "m4a", "pdf", "docx", "apk", "zip", "db")
private val EXTENSION_SET = MAP_EXTENSIONS.toSet()
private val EXTENSION_ALIAS = mapOf("jpeg" to "jpg", "heif" to "heic", "m4v" to "mp4", "doc" to "docx")

/** One of [MAP_EXTENSIONS], or "other": what colours the file's tile and which line of the type table it counts in. */
fun File.extKey(): String {
    val e = extension.lowercase()
    val k = EXTENSION_ALIAS[e] ?: e
    return if (k in EXTENSION_SET) k else "other"
}

/**
 * A folder or file in the size tree. Files under [SMALL_FILE_BYTES] are not kept one by one: each folder holds their total as a
 * single [aggregate] node, which keeps the tree small on a phone with hundreds of thousands of files.
 */
class MapNode(
    val file: File,
    val isDir: Boolean,
    val size: Long,
    val children: List<MapNode>,
    val count: Int, // files inside (1 for a file)
    val modified: Long = 0,
    val aggregate: Boolean = false,
)

/** The scanned [root] plus the bytes and file counts per [FileType] and per [extKey] over everything scanned (small files included). */
class ScanResult(val root: MapNode, val bytes: LongArray, val counts: IntArray, val extBytes: Map<String, Long>, val extCount: Map<String, Int>)

const val SMALL_FILE_BYTES = 16 * 1024L

private class Builder(val file: File) {
    val kids = mutableListOf<MapNode>()
    var smallBytes = 0L
    var smallCount = 0
}

/** Walks [root] once and builds its size tree. [onProgress] gets the number of files seen so far. Unreadable folders count as empty. */
suspend fun scanTree(root: File, onProgress: (Int) -> Unit): ScanResult = withContext(Dispatchers.IO) {
    val job = coroutineContext.job
    val bytes = LongArray(FileType.entries.size)
    val counts = IntArray(FileType.entries.size)
    val extBytes = HashMap<String, Long>()
    val extCount = HashMap<String, Int>()
    val stack = ArrayDeque<Builder>()
    var result: MapNode? = null
    var seen = 0
    Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            job.ensureActive() // leaving the screen cancels the scan
            stack.addLast(Builder(dir.toFile()))
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            val f = file.toFile()
            val size = attrs.size()
            val type = f.fileType().ordinal
            bytes[type] += size
            counts[type]++
            val key = f.extKey()
            extBytes[key] = (extBytes[key] ?: 0L) + size
            extCount[key] = (extCount[key] ?: 0) + 1
            if (++seen % 500 == 0) onProgress(seen)
            val parent = stack.last()
            if (size >= SMALL_FILE_BYTES) parent.kids += MapNode(f, false, size, emptyList(), 1, attrs.lastModifiedTime().toMillis())
            else { parent.smallBytes += size; parent.smallCount++ }
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            val b = stack.removeLast()
            if (b.smallCount > 0) b.kids += MapNode(b.file, false, b.smallBytes, emptyList(), b.smallCount, aggregate = true)
            val node = MapNode(b.file, true, b.kids.sumOf { it.size }, b.kids.sortedByDescending { it.size }, b.kids.sumOf { it.count })
            if (stack.isEmpty()) result = node else stack.last().kids += node
            return FileVisitResult.CONTINUE
        }
    })
    onProgress(seen)
    ScanResult(result ?: MapNode(root, true, 0, emptyList(), 0), bytes, counts, extBytes, extCount)
}

/** The nodes along [paths] below [root], starting with [root] itself; stops early if a path is no longer in the tree. */
fun zoomChain(root: MapNode, paths: List<String>): List<MapNode> {
    val chain = mutableListOf(root)
    for (p in paths) chain += chain.last().children.firstOrNull { it.file.path == p } ?: break
    return chain
}

/** The biggest individual files in the tree, largest first. */
fun largestFiles(root: MapNode, limit: Int = 100): List<MapNode> {
    val files = ArrayList<MapNode>()
    fun add(n: MapNode) { if (n.aggregate) return; if (n.isDir) n.children.forEach(::add) else files += n }
    add(root)
    return files.sortedByDescending { it.size }.take(limit)
}

/** Where a node goes in the treemap, in pixels. */
class TileRect(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Squarified treemap (Bruls, Huizing and van Wijk): lays out [sizes] (largest first, all above zero) in a [width] x [height] box so that
 * every rectangle's area is proportional to its size and the rectangles stay close to square.
 */
fun squarify(sizes: List<Long>, width: Float, height: Float): List<TileRect> {
    val total = sizes.sum().toDouble()
    if (sizes.isEmpty() || total <= 0.0) return emptyList()
    val areas = sizes.map { it / total * width * height }
    val out = ArrayList<TileRect>(sizes.size)
    var x = 0f
    var y = 0f
    var w = width
    var h = height
    var i = 0
    while (i < areas.size) {
        val side = minOf(w, h).toDouble()
        // grow the row while that keeps the worst rectangle in it from getting less square
        var sum = areas[i]
        var worst = worstRatio(side, sum, areas[i], areas[i])
        var j = i + 1
        while (j < areas.size) {
            val next = worstRatio(side, sum + areas[j], areas[i], areas[j])
            if (next > worst) break
            sum += areas[j]; worst = next; j++
        }
        if (w >= h) { // a column down the left edge
            val colW = (sum / h).toFloat()
            var yy = y
            for (k in i until j) { val hh = (areas[k] / colW).toFloat(); out += TileRect(x, yy, colW, hh); yy += hh }
            x += colW; w -= colW
        } else { // a row along the top edge
            val rowH = (sum / w).toFloat()
            var xx = x
            for (k in i until j) { val ww = (areas[k] / rowH).toFloat(); out += TileRect(xx, y, ww, rowH); xx += ww }
            y += rowH; h -= rowH
        }
        i = j
    }
    return out
}

private fun worstRatio(side: Double, sum: Double, max: Double, min: Double) = maxOf(side * side * max / (sum * sum), sum * sum / (side * side * min))
