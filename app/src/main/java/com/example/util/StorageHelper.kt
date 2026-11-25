package com.example.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

data class MediaItem(
    val file: File,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val mimeType: String,
    val isHidden: Boolean
)

/**
 * One mountable storage volume the File Manager can browse: the primary internal
 * storage plus any removable SD card. [label] is the platform volume description
 * ("Internal shared storage", "SD card", …) used by the breadcrumb/title; [root]
 * is the volume's public top-level directory (e.g. /storage/emulated/0 or
 * /storage/XXXX-XXXX). [isRemovable] distinguishes the SD card.
 */
data class StorageRoot(
    val root: File,
    val label: String,
    val isRemovable: Boolean
)

/**

 * Cached size + item count for one folder. [partial] means the walk hit its entry
 * budget, so [bytes] is a lower bound and the UI shows a "+" suffix.
 *
 * [dirMtime] is the folder's own mtime at the moment it was walked. Carrying it
 * inside the value lets a row reject a stale entry on its own, rather than showing
 * the previous total until the replacement walk finishes.
 */
data class FolderStats(
    val bytes: Long,
    val files: Int,
    val folders: Int,
    val partial: Boolean = false,
    val cancelled: Boolean = false,
    val dirMtime: Long = 0L
) {
    /** Items = files + immediate+nested folders, which is what a user means by "items". */
    val items: Int get() = files + folders
}

object StorageHelper {

    // Directories skipped during the broad media-index scan to avoid recursing
    // into large/noise folders (app caches, thumbnails, trash, etc.).
    private val NOISE_DIRS = setOf(
        "Android", ".thumbnails", ".cache", ".trash", "LOST.DIR",
        "cache", "thumbnails", ".android_secure", "ParallelConfigFiles",
        ".system", ".safariextz", "backup", "Backup"
    )

    // Raw-walk bounds. The depth was 2 for a long time, which meant anything
    // nested deeper than two folders below a scan root was invisible unless the
    // platform index happened to list it -- see [queryMediaStoreMedia], which
    // covers that gap. 4 is enough for real-world library nesting and the entry
    // cap keeps the walk from turning into a full-volume inventory.
    private const val MEDIA_SCAN_MAX_DEPTH = 4
    private const val MEDIA_SCAN_MAX_ENTRIES = 60_000

    // Only these extensions can ever become media-index entries. Filtering in
    // the walk keeps every APK, archive, log and random document out of the
    // candidate list; `.bin`/no-extension files are still kept so the existing
    // header sniff can recover misnamed MP4/MP3 files.
    private val MEDIA_INDEX_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "avi", "3gp", "mov", "flv", "ts", "m4v",
        "mp3", "wav", "ogg", "m4a", "flac", "opus", "wma", "aac", "amr", "mid",
        "pdf", "docx", "ppt", "pptx", "xls", "xlsx"
    )
    private val SNIFF_EXTENSIONS = setOf("bin", "")
    private val SUPPORTED_DOCUMENT_MIMES = setOf(
        "application/pdf",
        "application/msword",
        "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint"
    )

    /**
     * Paths the platform has already indexed, at any depth.
     *
     * MediaStore.Files with a `media_type` filter is one cheap query versus a
     * per-directory recursion, and it is the only source that finds media inside
     * trees the app cannot enumerate. Returns an empty list rather than throwing:
     * without the read permission (or on an OEM resolver that rejects the DATA
     * column) the filesystem walk is still the primary source, and callers treat
     * this as strictly additive.
     *
     * `media_type` literals: 1 = image, 2 = audio, 3 = video, 6 = document.
     */
    private fun queryMediaStoreMedia(context: Context): List<File> {
        val out = mutableListOf<File>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return out
        val granted = try {
            context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
        if (!granted) return out
        try {
            // Query every mounted external volume (primary + SD card) instead of
            // only the merged "external" volume, so SD-card media is indexed too.
            val volumes = runCatching { MediaStore.getExternalVolumeNames(context) }
                .getOrNull() ?: setOf("external")
            val projection = arrayOf(MediaStore.MediaColumns.DATA)
            // Images are never part of the media index (the Gallery has its own
            // source), so don't pay to enumerate them.
            val selection = "media_type IN (2,3,6)"
            for (vol in volumes) {
                runCatching {
                    val uri = MediaStore.Files.getContentUri(vol)
                    context.contentResolver.query(uri, projection, selection, null, null)?.use { c ->
                        val idx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                        if (idx >= 0) {
                            while (c.moveToNext()) {
                                val path = c.getString(idx) ?: continue
                                out.add(File(path))
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // DATA is deprecated; some OEM resolvers refuse it. Best-effort only.
        }
        return out
    }

    fun getRootDirectory(context: Context): File {
        val ext = Environment.getExternalStorageDirectory()
        return if (ext != null && ext.exists() && ext.canRead()) {
            ext
        } else {
            context.getExternalFilesDir(null) ?: context.filesDir
        }
    }

    /**
     * Every storage volume the File Manager can browse: the primary internal
     * storage plus any removable SD card. Ordered internal-first.
     *
     * Enumeration goes through [StorageManager] so the platform gives us the
     * volume description + removable flag. [StorageVolume.getDirectory] only
     * exists on API 30+, so on 26–29 the path is read reflectively
     * (`getPath`) with a final fallback that derives roots from
     * [Context.getExternalFilesDirs] (which returns app-specific dirs on every
     * volume the app can write). All of it is best-effort: a device without a
     * StorageManager still yields internal storage.
     */
    fun getStorageRoots(context: Context): List<StorageRoot> {
        val out = LinkedHashMap<String, StorageRoot>()

        val primary = getRootDirectory(context)
        out[primary.absolutePath] = StorageRoot(primary, "Internal storage", false)

        val sm = runCatching {
            context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        }.getOrNull()
        if (sm != null) {
            runCatching {
                for (v in sm.storageVolumes) {
                    val dir = volumeDir(v) ?: continue
                    if (!dir.exists() || !dir.canRead()) continue
                    val removable = runCatching { v.isRemovable }.getOrDefault(false)
                    val path = dir.absolutePath
                    if (out.containsKey(path)) continue
                    val label = runCatching { v.getDescription(context) }
                        .getOrNull()?.takeIf { it.isNotBlank() }
                        ?: if (removable) "SD card" else "Internal storage"
                    out[path] = StorageRoot(dir, label, removable)
                }
            }
        }

        // Fallback / supplement for API < 30 (no StorageVolume.getDirectory):
        // derive the volume root from the app-specific external dir on each
        // volume ("…/Android/data/<pkg>/files" -> "…").
        runCatching {
            context.getExternalFilesDirs(null).forEach { appDir ->
                if (appDir == null) return@forEach
                val marker = "/Android/"
                val p = appDir.absolutePath
                val cut = p.indexOf(marker)
                if (cut <= 0) return@forEach
                val rootPath = p.substring(0, cut)
                if (out.containsKey(rootPath)) return@forEach
                val root = File(rootPath)
                if (!root.exists() || !root.canRead()) return@forEach
                val removable = rootPath != primary.absolutePath
                out[rootPath] = StorageRoot(
                    root,
                    if (removable) "SD card" else "Internal storage",
                    removable
                )
            }
        }

        return out.values.toList()
    }

    /** Resolve a [StorageVolume]'s public top-level directory, or null. */
    private fun volumeDir(v: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return runCatching { v.directory }.getOrNull()
        }
        return runCatching {
            val p = v.javaClass.getMethod("getPath").invoke(v) as? String
            p?.let { File(it) }
        }.getOrNull()
    }

    /** True when [file] is the top-level directory of a storage volume. */
    fun isVolumeRoot(context: Context, file: File): Boolean =
        getStorageRoots(context).any { it.root.absolutePath == file.absolutePath }

    fun listFiles(dir: File, showHidden: Boolean = false): List<MediaItem> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { showHidden || !it.name.startsWith(".") }
            .map { file ->
                MediaItem(
                    file = file,
                    name = file.name,
                    path = file.absolutePath,
                    isDirectory = file.isDirectory,
                    sizeBytes = if (file.isDirectory) 0L else file.length(),
                    lastModified = file.lastModified(),
                    mimeType = getMimeType(file),
                    isHidden = file.name.startsWith(".")
                )
            }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /**
     * Walk [dir] and total up its size and item count. Always call this OFF the main
     * thread — it is a real filesystem recursion.
     *
     * Two deliberate bounds keep it survivable on a low-RAM phone:
     *  - [maxEntries] stops the walk after that many filesystem entries, and the
     *    result is flagged [FolderStats.partial] so the UI can render "12 MB+"
     *    rather than quietly lying with a truncated total. 8k is deliberately
     *    modest: on `/` almost every child is large, and a folder that reports a
     *    floor quickly is far more useful than one that stalls the batch.
     *  - [isCancelled] is polled per directory, so navigating away cancels the work
     *    instead of letting it finish into a cache nobody reads.
     *
     * Symlinked directories are skipped: a link back up the tree would otherwise
     * make the walk loop forever.
     */
    @JvmOverloads
    fun folderStats(
        dir: File,
        maxEntries: Int = 8_000,
        isCancelled: () -> Boolean = { false }
    ): FolderStats {
        // Read before the walk: matching the listing's own mtime is what lets a
        // row decide the cached total is still valid.
        val stamp = dir.lastModified()
        var bytes = 0L
        var files = 0
        var folders = 0
        var entries = 0
        var partial = false
        val seenDirs = HashSet<String>()
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        runCatching { seenDirs.add(dir.canonicalPath) }

        while (stack.isNotEmpty()) {
            if (isCancelled()) {
                return FolderStats(bytes, files, folders, partial = true, cancelled = true, dirMtime = stamp)
            }
            val cur = stack.removeLast()
            val children = cur.listFiles() ?: continue
            for (child in children) {
                entries++
                if (entries > maxEntries) {
                    partial = true
                    break
                }
                val isDir = child.isDirectory
                if (isDir) {
                    val canonical = runCatching { child.canonicalPath }.getOrNull()
                    if (canonical != null && !seenDirs.add(canonical)) continue
                    folders++
                    stack.addLast(child)
                } else {
                    files++
                    bytes += child.length()
                }
            }
            if (partial) break
        }
        return FolderStats(bytes, files, folders, partial = partial, dirMtime = stamp)
    }

    /**
     * Fill each directory's cached total into its [MediaItem.sizeBytes].
     *
     * Directories are listed with `sizeBytes = 0L`, which made "Sort by size" put
     * every folder in a meaningless tie. With the cache populated the folders sort
     * by real weight, and a folder that has not been measured yet still sorts as
     * 0 rather than dropping out of the list.
     */
    fun withFolderSizes(
        items: List<MediaItem>,
        stats: Map<String, FolderStats>
    ): List<MediaItem> = items.map { item ->
        if (!item.isDirectory) return@map item
        val s = stats[item.path] ?: return@map item
        if (item.sizeBytes == s.bytes) item else item.copy(sizeBytes = s.bytes)
    }

    // Folders always sort above files, in BOTH directions. Reversing a comparator
    // that has the folders-first key as its primary term flips that grouping too,
    // which is why the old `sortedWith(c).reversed()` buried folders under files
    // whenever the user picked a descending sort. Only the field comparator is
    // reversed here.
    fun sortedWithFoldersFirst(
        items: List<MediaItem>,
        option: SortOption,
        ascending: Boolean
    ): List<MediaItem> {
        val byField: Comparator<MediaItem> = when (option) {
            SortOption.NAME -> compareBy { it.name.lowercase() }
            SortOption.DATE -> compareBy { it.lastModified }
            SortOption.SIZE -> compareBy { it.sizeBytes }
            SortOption.TYPE -> compareBy { it.mimeType }
        }
        val ordered = if (ascending) byField else byField.reversed()
        return items.sortedWith(compareBy<MediaItem> { !it.isDirectory }.thenComparing(ordered))
    }

    fun searchFiles(root: File, query: String, maxResults: Int = 100): List<MediaItem> {
        val results = mutableListOf<MediaItem>()
        fun searchRecursive(dir: File) {
            if (results.size >= maxResults) return
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file.name.lowercase().contains(query.lowercase())) {
                    results.add(
                        MediaItem(
                            file = file,
                            name = file.name,
                            path = file.absolutePath,
                            isDirectory = file.isDirectory,
                            sizeBytes = if (file.isDirectory) 0L else file.length(),
                            lastModified = file.lastModified(),
                            mimeType = getMimeType(file),
                            isHidden = file.name.startsWith(".")
                        )
                    )
                }
                if (file.isDirectory && !file.name.startsWith(".")) {
                    searchRecursive(file)
                }
            }
        }
        searchRecursive(root)
        return results
    }

    fun createFolder(parent: File, folderName: String): Boolean {
        val newDir = File(parent, folderName)
        return newDir.mkdirs()
    }

    /**
     * Rename inside the same folder. Refuses a name that is blank, a path
     * separator, `.`/`..`, or one that already exists — `File.renameTo` silently
     * replaces the target on Linux, which used to destroy a file the user already
     * had with no prompt and no undo (unlike the B9 paste collision choice).
     * Returns false rather than throwing so the caller can show one message.
     */
    fun renameFile(file: File, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return false
        if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains('\u0000')) return false
        val parent = file.parentFile ?: return false
        val target = File(parent, trimmed)
        if (target.absolutePath == file.absolutePath) return false
        if (target.exists()) return false
        return file.renameTo(target)
    }

    fun deleteFile(file: File): Boolean {
        return if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    /**
     * What to do when the destination already holds an entry of the same name.
     * Silently overwriting (what this code used to do) is the one option that
     * cannot be undone, so it is no longer the default.
     */
    enum class Collision { OVERWRITE, KEEP_BOTH, SKIP }

    /**
     * Resolve a non-colliding name in [destDir] by appending " (1)", " (2)"… before
     * the extension. Used by [Collision.KEEP_BOTH], the new default, so a paste can
     * never destroy a file the user already had there.
     */
    fun uniqueNameIn(destDir: File, name: String): File {
        var candidate = File(destDir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var n = 1
        while (candidate.exists() && n < 10_000) {
            candidate = File(destDir, "$base ($n)$suffix")
            n++
        }
        return candidate
    }

    /** Names in [items] that already exist in [destDir] — the B9 collision prompt. */
    fun collidingNames(items: List<File>, destDir: File): List<String> =
        items.filter { File(destDir, it.name).exists() }.map { it.name }

    /**
     * Copy one entry into [destDir]. [collision] decides the same-name case:
     * KEEP_BOTH renames, SKIP leaves the destination alone and reports it as
     * skipped, OVERWRITE replaces it. Reports progress through [onBytes] so the
     * caller can drive a determinate progress bar instead of a spinner.
     */
    fun copyInto(
        src: File,
        destDir: File,
        collision: Collision = Collision.KEEP_BOTH,
        isCancelled: () -> Boolean = { false },
        onBytes: (Long) -> Unit = {}
    ): FileOpResult {
        if (!src.exists()) return FileOpResult(OpStatus.SKIPPED, null)
        if (isCancelled()) return FileOpResult(OpStatus.CANCELLED, null)
        var destFile = File(destDir, src.name)
        if (destFile.exists() || destFile == src) {
            when (collision) {
                Collision.SKIP -> return FileOpResult(OpStatus.SKIPPED, src)
                Collision.KEEP_BOTH -> destFile = uniqueNameIn(destDir, src.name)
                Collision.OVERWRITE -> Unit
            }
        }
        // Copying a directory into itself would recurse forever.
        if (src.isDirectory && destFile.canonicalPath.startsWith(src.canonicalPath + "/")) {
            return FileOpResult(OpStatus.SKIPPED, src)
        }
        return try {
            if (src.isDirectory) {
                copyDirTracked(src, destFile, isCancelled, onBytes)
            } else {
                val total = src.length()
                FileInputStream(src).use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var moved = 0L
                        while (true) {
                            if (isCancelled()) {
                                output.flush()
                                destFile.delete()
                                return FileOpResult(OpStatus.CANCELLED, src)
                            }
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            moved += read
                            onBytes(moved)
                        }
                        onBytes(total)
                    }
                }
            }
            FileOpResult(OpStatus.DONE, destFile)
        } catch (e: Exception) {
            destFile.delete()
            FileOpResult(OpStatus.FAILED, src)
        }
    }

    /** copyRecursively with byte reporting + cancellation between files. */
    private fun copyDirTracked(
        src: File,
        destDir: File,
        isCancelled: () -> Boolean,
        onBytes: (Long) -> Unit
    ) {
        if (!destDir.exists() && !destDir.mkdirs()) throw java.io.IOException("mkdirs failed")
        src.listFiles()?.forEach { child ->
            if (isCancelled()) throw java.io.IOException("cancelled")
            val target = File(destDir, child.name)
            if (child.isDirectory) {
                copyDirTracked(child, target, isCancelled, onBytes)
            } else {
                child.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                onBytes(child.length())
            }
        }
    }

    /** Outcome of one [copyInto] call, so the caller can report skips honestly. */
    enum class OpStatus { DONE, SKIPPED, CANCELLED, FAILED }
    data class FileOpResult(val status: OpStatus, val result: File?)

    /** Back-compat wrapper: old call sites keep their boolean contract. */
    fun copyFile(src: File, destDir: File): Boolean =
        copyInto(src, destDir, Collision.OVERWRITE).status == OpStatus.DONE

    /** Back-compat wrapper. Move = copy (renamed on collision) + delete the source. */
    fun moveFile(src: File, destDir: File): Boolean {
        val r = copyInto(src, destDir, Collision.KEEP_BOTH)
        if (r.status == OpStatus.DONE) {
            deleteFile(src)
            return true
        }
        return r.status == OpStatus.SKIPPED
    }

    /**
     * Breadth-first, bounded directory listing for the B9 destination picker.
     * Same low-RAM rules as [folderStats]: a hard cap, a depth limit, symlink
     * de-duplication via canonical paths, and cooperative cancellation.
     */
    fun listDestinations(
        root: File,
        maxDepth: Int = 5,
        cap: Int = 2_000,
        isCancelled: () -> Boolean = { false }
    ): List<File> {
        val out = ArrayList<File>(256)
        val seen = HashSet<String>()
        val queue = ArrayDeque<Pair<File, Int>>()
        runCatching { seen.add(root.canonicalPath) }
        queue.addLast(root to 0)
        while (queue.isNotEmpty() && out.size < cap) {
            if (isCancelled()) break
            val (dir, depth) = queue.removeFirst()
            out.add(dir)
            if (depth >= maxDepth) continue
            val kids = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (k in kids) {
                if (!k.isDirectory) continue
                val canon = runCatching { k.canonicalPath }.getOrNull() ?: continue
                if (!seen.add(canon)) continue
                queue.addLast(k to depth + 1)
            }
        }
        return out
    }

    /**
     * Compress [files] into [zipFile]. Writes to a sibling temp file and renames on
     * success, so a killed or failed compress can never leave a truncated `.zip`
     * that later fails to extract (or half-overwrite a previous good one).
     */
    fun zipFiles(files: List<File>, zipFile: File): Boolean {
        val tmp = File(zipFile.parentFile, ".${zipFile.name}.njpart")
        return try {
            FileOutputStream(tmp).use { fos ->
                java.util.zip.ZipOutputStream(fos).use { zos ->
                    fun addToZip(base: File, target: File) {
                        if (target.isDirectory) {
                            val entry = java.util.zip.ZipEntry(target.name + "/")
                            zos.putNextEntry(entry)
                            zos.closeEntry()
                            val children = target.listFiles() ?: return
                            for (child in children) {
                                addToZip(base, child)
                            }
                        } else {
                            val entryName = if (base.isDirectory) {
                                (target.absolutePath.removePrefix(base.absolutePath + File.separator)).replace('\\', '/')
                            } else {
                                target.name
                            }
                            val entry = java.util.zip.ZipEntry(entryName)
                            zos.putNextEntry(entry)
                            FileInputStream(target).use { fis ->
                                fis.copyTo(zos)
                            }
                            zos.closeEntry()
                        }
                    }
                    for (file in files) {
                        if (file.exists()) addToZip(file, file)
                    }
                }
            }
            zipFile.parentFile?.mkdirs()
            // Only now does the real archive exist; the partial one is discarded.
            val renamed = tmp.renameTo(zipFile) || run {
                tmp.copyTo(zipFile, overwrite = true)
                tmp.delete()
                true
            }
            renamed
        } catch (e: Exception) {
            e.printStackTrace()
            runCatching { tmp.delete() }
            false
        }
    }

    /**
     * Resolve an archive entry name inside [destDir], or null when the entry
     * tries to escape it.
     *
     * This is the zip-slip / tar-slip guard. `File(destDir, entry.name)` happily
     * honours `../../` and a leading `/` inside the entry name, and this app holds
     * MANAGE_EXTERNAL_STORAGE, so a crafted archive could otherwise write
     * anywhere the user can. Containment is checked on the *canonical* path, which
     * also collapses a symlinked parent that would otherwise point outside.
     */
    fun resolveArchiveEntry(destDir: File, entryName: String): File? {
        if (entryName.isEmpty() || entryName.contains('\u0000')) return null
        val root = runCatching { destDir.canonicalFile }.getOrElse { return null }
        val candidate = File(root, entryName)
        val resolved = runCatching { candidate.canonicalFile }.getOrElse { return null }
        // Must be strictly inside the destination: `root` itself is not a valid target.
        val rootPath = root.path + File.separator
        return if (resolved.path.startsWith(rootPath)) resolved else null
    }

    fun unzipFile(zipFile: File, destDir: File): Boolean {
        return try {
            if (!destDir.exists()) destDir.mkdirs()
            FileInputStream(zipFile).use { fis ->
                java.util.zip.ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val newFile = resolveArchiveEntry(destDir, entry.name)
                        if (newFile == null) {
                            // Escaping entry: refuse it, but keep extracting the rest
                            // so one hostile name does not fail a whole archive.
                            if (!entry.isDirectory) zis.copyTo(java.io.OutputStream.nullOutputStream())
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }
                        if (entry.isDirectory) {
                            newFile.mkdirs()
                        } else {
                            newFile.parentFile?.mkdirs()
                            FileOutputStream(newFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun unsupportedArchiveReason(ext: String): String? =
        if (ext.lowercase() in listOf("rar", "7z"))
            "Archive format .$ext is not supported for extraction. Convert it to .zip or .tar."
        else
            null

    fun extractArchive(file: File, destDir: File): String? {
        val ext = file.extension.lowercase()
        if (ext in listOf("rar", "7z")) {
            return unsupportedArchiveReason(ext)
        }
        if (!destDir.exists()) destDir.mkdirs()
        return try {
            when {
                ext == "zip" -> {
                    FileInputStream(file).use { fis ->
                        java.util.zip.ZipInputStream(fis).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                val newFile = resolveArchiveEntry(destDir, entry.name)
                                if (newFile == null) {
                                    if (!entry.isDirectory) zis.copyTo(java.io.OutputStream.nullOutputStream())
                                    zis.closeEntry()
                                    entry = zis.nextEntry
                                    continue
                                }
                                if (entry.isDirectory) {
                                    newFile.mkdirs()
                                } else {
                                    newFile.parentFile?.mkdirs()
                                    FileOutputStream(newFile).use { fos ->
                                        zis.copyTo(fos)
                                    }
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                    }
                    null
                }
                ext in listOf("tar", "tbz", "tgz", "gz") -> {
                    val input: java.io.InputStream = if (ext == "tar") {
                        FileInputStream(file)
                    } else {
                        BufferedInputStream(GzipCompressorInputStream(FileInputStream(file)))
                    }
                    input.use { stream ->
                        TarArchiveInputStream(stream).use { tar ->
                            var entry = tar.nextEntry
                            while (entry != null) {
                                val newFile = resolveArchiveEntry(destDir, entry.name)
                                if (newFile == null) {
                                    if (!entry.isDirectory) tar.copyTo(java.io.OutputStream.nullOutputStream())
                                    entry = tar.nextEntry
                                    continue
                                }
                                if (entry.isDirectory) {
                                    newFile.mkdirs()
                                } else {
                                    newFile.parentFile?.mkdirs()
                                    FileOutputStream(newFile).use { fos ->
                                        tar.copyTo(fos)
                                    }
                                }
                                entry = tar.nextEntry
                            }
                        }
                    }
                    null
                }
                else -> "Unsupported archive format."
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "Archive extraction failed: ${e.message}"
        }
    }

    fun detectRealExtensionFromHeader(file: File): String? {
        if (!file.exists() || !file.isFile || file.length() < 4) return null
        try {
            val bytes = ByteArray(12)
            FileInputStream(file).use { fis ->
                val read = fis.read(bytes)
                if (read < 4) return null
            }
            // PNG: 89 50 4E 47
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
                return "png"
            }
            // JPEG: FF D8 FF
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
                return "jpg"
            }
            // GIF: GIF8
            if (bytes[0] == 'G'.toByte() && bytes[1] == 'I'.toByte() && bytes[2] == 'F'.toByte() && bytes[3] == '8'.toByte()) {
                return "gif"
            }
            // PDF: %PDF
            if (bytes[0] == '%'.toByte() && bytes[1] == 'P'.toByte() && bytes[2] == 'D'.toByte() && bytes[3] == 'F'.toByte()) {
                return "pdf"
            }
            // ZIP / DOCX / APK: PK..
            if (bytes[0] == 'P'.toByte() && bytes[1] == 'K'.toByte() && bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) {
                return "zip"
            }
            // MP4: ftyp at index 4..7
            if (bytes.size >= 8 && bytes[4] == 'f'.toByte() && bytes[5] == 't'.toByte() && bytes[6] == 'y'.toByte() && bytes[7] == 'p'.toByte()) {
                return "mp4"
            }
            // MP3: ID3
            if (bytes[0] == 'I'.toByte() && bytes[1] == 'D'.toByte() && bytes[2] == '3'.toByte()) {
                return "mp3"
            }
        } catch (_: Exception) {}
        return null
    }

    fun autoFixBinFile(file: File): File? {
        if (!file.exists() || !file.isFile) return null
        val detectedExt = detectRealExtensionFromHeader(file)
        if (detectedExt != null && file.extension.lowercase() != detectedExt) {
            val newName = if (file.name.endsWith(".bin", ignoreCase = true)) {
                file.name.substringBeforeLast(".") + ".$detectedExt"
            } else {
                "${file.name}.$detectedExt"
            }
            val target = File(file.parentFile, newName)
            if (file.renameTo(target)) {
                return target
            }
        }
        return null
    }

    fun getAllAccessibleMediaItems(context: Context): List<MediaItem> {
        val candidates = mutableListOf<File>()
        var scannedEntries = 0
        val dirsToScan = mutableListOf<File>()

        try {
            dirsToScan.add(getRootDirectory(context))
        } catch (_: Exception) {}
        // Every other mounted volume (the SD card) so its media is indexed too.
        try {
            getStorageRoots(context).forEach { dirsToScan.add(it.root) }
        } catch (_: Exception) {}
        try {
            context.getExternalFilesDirs(null).forEach { it?.let { d -> dirsToScan.add(d) } }
        } catch (_: Exception) {}
        // Never walk all of filesDir: it holds the PRoot rootfs, Gecko profiles
        // and downloader metadata, which can contain tens of thousands of files
        // that are not user media. Only these two private dirs produce media.
        try {
            val privateFiles = context.filesDir
            dirsToScan.add(File(privateFiles, "Camera"))
            dirsToScan.add(File(privateFiles, "Recordings"))
        } catch (_: Exception) {}
        try {
            dirsToScan.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
            dirsToScan.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC))
            dirsToScan.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES))
            dirsToScan.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
        } catch (_: Exception) {}

        fun scanFolder(dir: File?, depth: Int = 0) {
            if (dir == null || !dir.exists() || !dir.canRead() || depth > MEDIA_SCAN_MAX_DEPTH) return
            if (dir.name == "Vault") return
            if (NOISE_DIRS.contains(dir.name)) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (scannedEntries >= MEDIA_SCAN_MAX_ENTRIES) return
                if (child.name.startsWith(".")) continue
                if (child.isFile) {
                    scannedEntries++
                    if (child.name.endsWith(".vault", ignoreCase = true)) continue
                    val ext = child.extension.lowercase()
                    if (ext !in MEDIA_INDEX_EXTENSIONS && ext !in SNIFF_EXTENSIONS) continue
                    candidates.add(child)
                } else if (child.isDirectory && depth < MEDIA_SCAN_MAX_DEPTH) {
                    scanFolder(child, depth + 1)
                }
            }
        }

        for (d in dirsToScan.distinctBy { it.absolutePath }) {
            scanFolder(d)
        }

        // The walk above is deliberately shallow-ish: it is a raw recursion over
        // every file on the volume and this runs on a low-RAM phone. Anything the
        // OS has already indexed is much cheaper to ask the platform for, and
        // MediaStore knows about files at ANY depth (plus files the app itself
        // never walks, e.g. inside Android/data it cannot list). Without this the
        // Video tab silently lost videos that MediaStore knows perfectly well --
        // e.g. WhatsApp/Telegram downloads nested a few folders deep.
        for (f in queryMediaStoreMedia(context)) {
            if (!f.isFile || f.name.startsWith(".") ||
                f.name.endsWith(".vault", ignoreCase = true)
            ) continue
            val ext = f.extension.lowercase()
            if (ext !in MEDIA_INDEX_EXTENSIONS && ext !in SNIFF_EXTENSIONS) continue
            candidates.add(f)
        }

        return candidates.distinctBy { it.absolutePath }.mapNotNull { file ->
            val ext = file.extension.lowercase()
            if (ext !in MEDIA_INDEX_EXTENSIONS && ext !in SNIFF_EXTENSIONS) return@mapNotNull null
            val mime = getMimeType(file)
            val supported = mime.startsWith("video/") ||
                mime.startsWith("audio/") ||
                mime in SUPPORTED_DOCUMENT_MIMES
            if (!supported) return@mapNotNull null
            MediaItem(
                file = file,
                name = file.name,
                path = file.absolutePath,
                isDirectory = false,
                sizeBytes = file.length(),
                lastModified = file.lastModified(),
                mimeType = mime,
                isHidden = file.name.startsWith(".")
            )
        }.sortedByDescending { it.lastModified }
    }

    fun getMimeType(file: File): String {
        var ext = file.extension.lowercase()
        if (ext == "bin" || ext.isEmpty()) {
            val detected = detectRealExtensionFromHeader(file)
            if (detected != null) {
                ext = detected
            }
        }
        return mimeForExtension(ext)
    }

    fun getMimeType(name: String): String =
        mimeForExtension(name.substringAfterLast('.', "").lowercase())

    private fun mimeForExtension(ext: String): String = when (ext) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "heic", "heif" -> "image/*"
            "mp4", "mkv", "webm", "3gp", "avi", "mov", "flv", "ts", "m4v" -> "video/*"
            "mp3", "wav", "ogg", "m4a", "flac", "opus", "wma", "aac", "amr", "mid" -> "audio/*"
            "pdf" -> "application/pdf"
            "txt", "json", "xml", "log", "md", "kt", "java", "py", "cpp", "c", "h", "css", "js", "html", "sh", "yml", "yaml" -> "text/plain"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "odt", "ods", "odp" -> "application/vnd.oasis.opendocument"
            "rtf" -> "application/rtf"
            "epub" -> "application/epub+zip"
            "csv" -> "text/comma-separated-values"
            "srt", "vtt", "sub" -> "text/subtitle"
            "zip", "rar", "7z", "tar", "gz", "bz2" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
}
