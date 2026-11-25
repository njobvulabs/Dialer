package com.example.util

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.attribute.FileTime
import java.util.Collections
import java.util.UUID

object ArchiveFormats {
    data class Progress(
        val entriesDone: Int,
        val entriesTotal: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
        val current: String
    )

    fun listEntries(file: File, password: CharArray?): List<String> = runCatching {
        when (file.extension.lowercase()) {
            "zip" -> zipEntries(file, password).map { it.name }
            "7z" -> sevenZFile(file, password).use { archive -> archive.entries.map { it.name } }
            "tar", "tbz", "tgz", "gz" -> tarStream(file).use { tar ->
                val out = mutableListOf<String>()
                var entry = tar.nextEntry
                while (entry != null) {
                    out += if (entry.isDirectory) entry.name + "/" else entry.name
                    if (out.size >= 200) break
                    entry = tar.nextEntry
                }
                out
            }
            else -> emptyList()
        }.take(200)
    }.getOrDefault(emptyList())

    fun extract(
        file: File,
        destDir: File,
        password: CharArray?,
        collision: StorageHelper.Collision = StorageHelper.Collision.KEEP_BOTH,
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): String? {
        if (!destDir.exists() && !destDir.mkdirs()) return "Could not create destination folder"
        return try {
            when (file.extension.lowercase()) {
                "zip" -> extractZip(file, destDir, password, collision, onProgress, isCancelled)
                "7z" -> extract7z(file, destDir, password, collision, onProgress, isCancelled)
                "tar", "tbz", "tgz", "gz" -> extractTarLike(file, destDir, collision, onProgress, isCancelled)
                else -> "Unsupported archive format."
            }
        } catch (e: Exception) {
            e.message ?: "Archive extraction failed"
        }
    }

    fun create(
        files: List<File>,
        output: File,
        format: String,
        password: CharArray?,
        level: Int,
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): String? {
        if (files.isEmpty()) return "No files selected"
        val tmp = File(output.parentFile, ".${output.name}.njpart")
        return try {
            output.parentFile?.mkdirs()
            when (format.uppercase()) {
                "7Z" -> create7z(files, tmp, password, onProgress, isCancelled)
                else -> createZip(files, tmp, level, onProgress, isCancelled)
            }?.let { return it }
            if (isCancelled()) {
                tmp.delete()
                "Cancelled"
            } else if (tmp.renameTo(output) || run {
                    tmp.copyTo(output, overwrite = true)
                    tmp.delete()
                    true
                }) null else "Could not write archive"
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            e.message ?: "Could not create archive"
        }
    }

    fun stageSplitArchive(file: File, tempDir: File): Pair<File, Boolean> {
        val name = file.name
        val parent = file.parentFile ?: return file to false
        val parts = when {
            name.endsWith(".zip", ignoreCase = true) -> {
                val base = name.substringBeforeLast('.')
                val splits = (1..99).map { File(parent, String.format("%s.z%02d", base, it)) }
                    .takeWhile { it.exists() }
                if (splits.isEmpty()) emptyList() else splits + file
            }
            Regex("\\.\\d{3}$").containsMatchIn(name) -> {
                val base = name.substringBeforeLast('.')
                val splits = mutableListOf<File>()
                var index = 1
                while (true) {
                    val part = File(parent, String.format("%s.%03d", base, index))
                    if (!part.exists()) break
                    splits += part
                    index++
                }
                splits
            }
            else -> emptyList()
        }
        if (parts.size < 2) return file to false
        val staged = File(tempDir, "archive_split_${UUID.randomUUID()}_${name.substringAfterLast('.', "bin")}")
        return try {
            FileOutputStream(staged).use { out ->
                parts.forEach { part -> FileInputStream(part).use { it.copyTo(out) } }
            }
            staged to true
        } catch (_: Exception) {
            file to false
        }
    }

    private fun zipEntries(file: File, password: CharArray?): List<ZipArchiveEntry> {
        val pass = password?.let { String(it) }?.takeIf { it.isNotBlank() }
        val zip = if (pass == null) ZipFile(file) else ZipFile(file, pass)
        return zip.use { Collections.list(it.entries) }
    }

    private fun sevenZFile(file: File, password: CharArray?): SevenZFile =
        if (password == null || password.isEmpty()) SevenZFile(file) else SevenZFile(file, password)

    private fun tarStream(file: File): TarArchiveInputStream {
        val raw = BufferedInputStream(FileInputStream(file))
        return if (file.extension.lowercase() in setOf("tgz", "tbz", "gz")) {
            TarArchiveInputStream(GzipCompressorInputStream(raw))
        } else TarArchiveInputStream(raw)
    }

    private fun extractZip(
        file: File,
        destDir: File,
        password: CharArray?,
        collision: StorageHelper.Collision,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean
    ): String? {
        val pass = password?.let { String(it) }?.takeIf { it.isNotBlank() }
        val zip = if (pass == null) ZipFile(file) else ZipFile(file, pass)
        zip.use { archive ->
            val entries = Collections.list(archive.entries)
            val totalBytes = entries.sumOf { it.size.coerceAtLeast(0L) }
            var doneBytes = 0L
            for ((index, entry) in entries.withIndex()) {
                if (isCancelled()) return "Cancelled"
                val target = StorageHelper.resolveArchiveEntry(destDir, entry.name) ?: continue
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    val output = targetForCollision(target, collision) ?: continue
                    output.parentFile?.mkdirs()
                    val startBytes = doneBytes
                    archive.getInputStream(entry).use { input ->
                        FileOutputStream(output).use { out ->
                            if (!copyTracked(input, out, entry.getSize(), { doneBytes = startBytes + it }, isCancelled)) return "Cancelled"
                        }
                    }
                    if (entry.time > 0L) runCatching { output.setLastModified(entry.time) }
                }
                onProgress(Progress(index + 1, entries.size, doneBytes, totalBytes, entry.name))
            }
        }
        return null
    }

    private fun extract7z(
        file: File,
        destDir: File,
        password: CharArray?,
        collision: StorageHelper.Collision,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean
    ): String? {
        sevenZFile(file, password).use { archive ->
            val entries = archive.entries.toList()
            val totalBytes = entries.sumOf { it.getSize().coerceAtLeast(0L) }
            var doneBytes = 0L
            for ((index, entry) in entries.withIndex()) {
                if (isCancelled()) return "Cancelled"
                val target = StorageHelper.resolveArchiveEntry(destDir, entry.name) ?: continue
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    val output = targetForCollision(target, collision) ?: continue
                    output.parentFile?.mkdirs()
                    val startBytes = doneBytes
                    archive.getInputStream(entry).use { input ->
                        FileOutputStream(output).use { out ->
                            if (!copyTracked(input, out, entry.getSize(), { doneBytes = startBytes + it }, isCancelled)) return "Cancelled"
                        }
                    }
                    runCatching { entry.lastModifiedDate?.time?.let { output.setLastModified(it) } }
                }
                onProgress(Progress(index + 1, entries.size, doneBytes, totalBytes, entry.name))
            }
        }
        return null
    }

    private fun extractTarLike(
        file: File,
        destDir: File,
        collision: StorageHelper.Collision,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean
    ): String? {
        tarStream(file).use { tar ->
            var index = 0
            var doneBytes = 0L
            var entry = tar.nextEntry
            while (entry != null) {
                if (isCancelled()) return "Cancelled"
                val target = StorageHelper.resolveArchiveEntry(destDir, entry.name) ?: run {
                    entry = tar.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    val output = targetForCollision(target, collision) ?: run {
                        entry = tar.nextEntry
                        continue
                    }
                    output.parentFile?.mkdirs()
                    val startBytes = doneBytes
                    FileOutputStream(output).use { out ->
                        if (!copyTracked(tar, out, entry.size, { doneBytes = startBytes + it }, isCancelled)) return "Cancelled"
                    }
                    val modified = (entry as? TarArchiveEntry)?.modTime?.time
                    if (modified != null) runCatching { output.setLastModified(modified) }
                }
                index++
                onProgress(Progress(index, index + 1, doneBytes, entry.size.coerceAtLeast(0L), entry.name))
                entry = tar.nextEntry
            }
        }
        return null
    }

    private fun createZip(
        files: List<File>,
        output: File,
        level: Int,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean
    ): String? {
        val entries = expandFiles(files)
        val totalBytes = entries.sumOf { it.second.length() }
        var doneBytes = 0L
                ZipArchiveOutputStream(output).use { zip ->
            zip.setLevel(level.coerceIn(1, 9))
            entries.forEachIndexed { index, pair ->
                if (isCancelled()) return "Cancelled"
                val entry = ZipArchiveEntry(pair.first)
                entry.setTime(FileTime.fromMillis(pair.second.lastModified()))
                zip.putArchiveEntry(entry)
                val startBytes = doneBytes
                FileInputStream(pair.second).use { input ->
                    if (!copyTracked(input, zip, pair.second.length(), { doneBytes = startBytes + it }, isCancelled)) return "Cancelled"
                }
                zip.closeArchiveEntry()
                onProgress(Progress(index + 1, entries.size, doneBytes, totalBytes, pair.first))
            }
        }
        return null
    }

    private fun create7z(
        files: List<File>,
        output: File,
        password: CharArray?,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean
    ): String? {
        val entries = expandFiles(files)
        val totalBytes = entries.sumOf { it.second.length() }
        var doneBytes = 0L
        val seven = if (password == null || password.isEmpty()) SevenZOutputFile(output) else SevenZOutputFile(output, password)
        seven.use { archive ->
            entries.forEachIndexed { index, pair ->
                if (isCancelled()) return "Cancelled"
                val entry = SevenZArchiveEntry().apply {
                    name = pair.first
                    setSize(pair.second.length())
                    setLastModifiedTime(FileTime.fromMillis(pair.second.lastModified()))
                }
                archive.putArchiveEntry(entry)
                val startBytes = doneBytes
                FileInputStream(pair.second).use { input ->
                    if (!copyTracked(input, archive, pair.second.length(), { doneBytes = startBytes + it }, isCancelled)) return "Cancelled"
                }
                archive.closeArchiveEntry()
                onProgress(Progress(index + 1, entries.size, doneBytes, totalBytes, pair.first))
            }
        }
        return null
    }

    private fun expandFiles(files: List<File>): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        fun add(base: File, file: File) {
            val name = if (base.isDirectory) {
                file.absolutePath.removePrefix(base.absolutePath + File.separator).replace(File.separatorChar, '/')
            } else file.name
            if (file.isDirectory) {
                file.listFiles()?.sortedBy { it.name }?.forEach { add(base, it) }
            } else if (file.isFile) out += name to file
        }
        files.forEach { add(it, it) }
        return out
    }

    private fun targetForCollision(target: File, collision: StorageHelper.Collision): File? = when (collision) {
        StorageHelper.Collision.OVERWRITE -> target
        StorageHelper.Collision.KEEP_BOTH -> {
            val parent = target.parentFile ?: return null
            StorageHelper.uniqueNameIn(parent, target.name)
        }
        StorageHelper.Collision.SKIP -> if (target.exists()) null else target
    }

    private fun copyTracked(
        input: InputStream,
        output: OutputStream,
        total: Long,
        onProgress: (Long) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean = copyTracked(input, total, onProgress, isCancelled) { buffer, offset, length ->
        output.write(buffer, offset, length)
    }

    private fun copyTracked(
        input: InputStream,
        output: SevenZOutputFile,
        total: Long,
        onProgress: (Long) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean = copyTracked(input, total, onProgress, isCancelled) { buffer, offset, length ->
        output.write(buffer, offset, length)
    }

    private fun copyTracked(
        input: InputStream,
        total: Long,
        onProgress: (Long) -> Unit,
        isCancelled: () -> Boolean,
        write: (ByteArray, Int, Int) -> Unit
    ): Boolean {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            if (isCancelled()) return false
            val read = input.read(buffer)
            if (read < 0) break
            write(buffer, 0, read)
            copied += read
            onProgress(copied.coerceAtMost(if (total > 0L) total else Long.MAX_VALUE))
        }
        return true
    }
}
