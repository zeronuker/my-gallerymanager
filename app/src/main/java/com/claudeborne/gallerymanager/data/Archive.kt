package com.claudeborne.gallerymanager.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Packs [files] (folders included) into the zip [dest]. */
fun zipFiles(files: List<File>, dest: File) {
    ZipOutputStream(dest.outputStream().buffered()).use { zos ->
        fun add(f: File, entry: String) {
            if (f.isDirectory) {
                val kids = f.listFiles().orEmpty()
                if (kids.isEmpty()) { zos.putNextEntry(ZipEntry("$entry/")); zos.closeEntry() }
                kids.forEach { add(it, "$entry/${it.name}") }
            } else {
                zos.putNextEntry(ZipEntry(entry))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        files.forEach { add(it, it.name) }
    }
}

/** Extracts [zip] into [destDir]; returns the extracted top-level paths for the gallery scan. Blocks entries that escape the folder. */
fun unzipTo(zip: File, destDir: File): List<String> {
    val out = mutableListOf<String>()
    destDir.mkdirs()
    ZipFile(zip).use { zf ->
        for (e in zf.entries()) {
            val target = File(destDir, e.name)
            require(target.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) { "Unsafe path in zip" }
            if (e.isDirectory) target.mkdirs()
            else {
                target.parentFile?.mkdirs()
                zf.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
                out += target.absolutePath
            }
        }
    }
    return out
}

/** Entry names and sizes inside a zip, for the preview dialog. */
fun zipListing(zip: File): List<Pair<String, Long>> =
    ZipFile(zip).use { zf -> zf.entries().toList().map { it.name to it.size } }
