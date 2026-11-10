package com.claudeborne.gallerymanager.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Files deleted in the Files tab are moved into the app's own storage folder instead of being erased. Each one gets a folder
// "bin/<deletedMs>-<n>/" holding it under its own name, plus a sibling "bin/<deletedMs>-<n>.origin" text file with where it came from.

const val BIN_DAYS = 30
private const val DAY_MS = 86_400_000L

/** One deleted file or folder: [file] sits in the bin, [origin] is where it came from. */
data class BinItem(val id: String, val file: File, val origin: File, val deletedMs: Long)

private fun binRoot(context: Context) = File(context.getExternalFilesDir(null), "bin")
private fun originFile(bin: File, id: String) = File(bin, "$id.origin")

/** Moves [files] into the bin. Returns how many were moved. */
suspend fun moveToBin(context: Context, files: List<File>): Int = withContext(Dispatchers.IO) {
    val bin = binRoot(context).apply { mkdirs() }
    File(bin, ".nomedia").createNewFile() // keeps the media scanner, and so the Photos tab, away from the bin
    val now = System.currentTimeMillis()
    val gone = mutableListOf<String>()
    var done = 0
    files.forEachIndexed { i, f ->
        val id = "$now-$i"
        val dir = File(bin, id).apply { mkdirs() }
        val paths = f.filePaths()
        if (runCatching { f.moveTo(File(dir, f.name)) }.getOrDefault(false)) { originFile(bin, id).writeText(f.path); gone += paths; done++ }
        else dir.deleteRecursively()
    }
    rescan(context, gone)
    done
}

/** What is in the bin, most recently deleted first. */
fun listBin(context: Context): List<BinItem> {
    val bin = binRoot(context)
    return bin.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
        val item = dir.listFiles()?.firstOrNull() ?: return@mapNotNull null
        val origin = originFile(bin, dir.name).takeIf { it.exists() }?.readText() ?: return@mapNotNull null
        BinItem(dir.name, item, File(origin), dir.name.substringBefore('-').toLongOrNull() ?: 0)
    }.sortedByDescending { it.deletedMs }
}

/** Puts [items] back where they came from (under a free name if that is taken). Returns how many were restored. */
suspend fun restoreFromBin(context: Context, items: List<BinItem>): Int = withContext(Dispatchers.IO) {
    val bin = binRoot(context)
    val back = mutableListOf<String>()
    var done = 0
    items.forEach { item ->
        val parent = item.origin.parentFile ?: return@forEach
        parent.mkdirs()
        val target = freeName(parent, item.origin.name)
        if (runCatching { item.file.moveTo(target) }.getOrDefault(false)) {
            File(bin, item.id).deleteRecursively()
            originFile(bin, item.id).delete()
            back += target.filePaths()
            done++
        } else target.deleteRecursively()
    }
    rescan(context, back)
    done
}

/** Erases [items] for good. */
suspend fun deleteFromBin(context: Context, items: List<BinItem>) = withContext(Dispatchers.IO) {
    val bin = binRoot(context)
    items.forEach { File(bin, it.id).deleteRecursively(); originFile(bin, it.id).delete() }
}

/** Erases what has been in the bin longer than [BIN_DAYS] days. */
suspend fun purgeExpiredBin(context: Context) {
    val limit = System.currentTimeMillis() - BIN_DAYS * DAY_MS
    deleteFromBin(context, listBin(context).filter { it.deletedMs < limit })
}
