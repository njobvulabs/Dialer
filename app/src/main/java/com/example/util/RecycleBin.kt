package com.example.util

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * B10: a quota'd Recycle bin for the File Manager.
 *
 * Why this exists: every delete in this app used to be `file.deleteRecursively()`.
 * One mis-tap on the wrong row destroyed the file permanently — the confirm dialog
 * said "This action cannot be undone", which is an admission, not a design.
 *
 * Layout, under the internal-storage root:
 * ```
 *   .nj-trash/index.json          the entry list
 *   .nj-trash/b_<millis>/<id>/<original name>
 * ```
 * The bin is deliberately a **single internal bin** even now that the File Manager
 * browses an external SD card: trashing a file that lives on internal storage is a
 * same-volume rename, while an SD file is a cross-volume copy+delete (the fallback
 * path below), so no per-volume `.nj-trash` is kept on the card.
 *
 * Each entry gets its **own** directory rather than sharing one bucket, because the
 * quota eviction deletes the oldest *entry*, and a shared bucket would take its
 * neighbours with it.

 * Three rules that matter on this device:
 *  - **Rename first.** Same-volume trashing is a rename; the copy+delete path is only
 *    a fallback (and is what an SD-card delete takes). Trashing a 2 GB video folder
 *    must not read 2 GB when a rename would do.
 *  - **Quota-enforced.** An unbounded bin on a 32 GB phone is just a slower disk
 *    full, so after each delete the oldest entries are evicted until the bin fits.
 *  - **Atomic index.** Written temp-then-rename. A half-written index would orphan
 *    every file in the bin (data loss from a crash — the one thing this feature
 *    exists to prevent).
 */
object RecycleBin {

    private const val BIN_DIR = ".nj-trash"
    private const val INDEX = "index.json"
    private const val PREFS = "recycle_bin"
    private const val KEY_QUOTA_MB = "quota_mb"

    /** 50 GB by default. */
    const val DEFAULT_QUOTA_MB = 51200L

    /** One trashed item. [sizeBytes] is -1 when a directory walk hit its cap. */
    data class Entry(
        val id: String,
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val deletedAt: Long,
        val bucket: String,
        val originPath: String
    ) {
        val partialSize: Boolean get() = sizeBytes < 0
        val displaySize: Long get() = if (sizeBytes < 0) 0L else sizeBytes
    }

    data class TrashResult(
        val trashed: Int,
        val failed: Int,
        /** Entries evicted to make the bin fit its quota, oldest first. */
        val evicted: List<String>
    )

    fun binRoot(): File = File(Environment.getExternalStorageDirectory(), BIN_DIR)

    private fun indexFile() = File(binRoot(), INDEX)

    fun quotaBytes(context: Context): Long {
        val mb = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_QUOTA_MB, DEFAULT_QUOTA_MB)
        return (if (mb <= 0) DEFAULT_QUOTA_MB else mb) * 1024L * 1024L
    }

    fun setQuotaMb(context: Context, mb: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_QUOTA_MB, mb.coerceAtLeast(64L)).apply()
    }

    // --------------------------------------------------------------- index io

    /**
     * Every index mutation is a read-modify-write (read file, edit list, write
     * file). Two of those racing in one process lost rows: the File Manager
     * trashing while an Audio/Video delete trashed, or a double tap. A lost row
     * means an invisible, unrestorable payload that still eats storage, so the
     * whole read→edit→write cycle is serialized on this monitor.
     */
    private val indexLock = Any()

    private fun readIndex(): MutableList<Entry> {
        val f = indexFile()
        if (!f.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val bucket = o.optString("bucket")
                val name = o.optString("name")
                if (id.isBlank() || bucket.isBlank() || name.isBlank()) return@mapNotNull null
                Entry(
                    id = id,
                    name = name,
                    isDirectory = o.optBoolean("isDirectory"),
                    sizeBytes = o.optLong("sizeBytes"),
                    deletedAt = o.optLong("deletedAt"),
                    bucket = bucket,
                    originPath = o.optString("originPath")
                )
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun writeIndex(entries: List<Entry>) {
        val target = indexFile()
        val arr = JSONArray()
        entries.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("isDirectory", it.isDirectory)
                    .put("sizeBytes", it.sizeBytes)
                    .put("deletedAt", it.deletedAt)
                    .put("bucket", it.bucket)
                    .put("originPath", it.originPath)
            )
        }
        runCatching {
            target.parentFile?.mkdirs()
            // Temp + rename: a torn index orphans every payload file in the bin.
            val tmp = File(target.parentFile, "$INDEX.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(arr.toString())
                tmp.delete()
            }
        }
    }

    /** Newest first, which is the order both the screen and the eviction use. */
    fun entries(): List<Entry> = readIndex().sortedByDescending { it.deletedAt }

    fun totalBytes(): Long = readIndex().sumOf { if (it.sizeBytes > 0) it.sizeBytes else 0L }

    fun isEmpty(): Boolean = readIndex().isEmpty()

    /** Permanently delete entries whose [Entry.deletedAt] is older than [retentionDays] days. */
    fun purgeExpired(retentionDays: Long = 30L): List<String> =
        synchronized(indexLock) {
            val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L
            val expired = readIndex().filter { it.deletedAt < cutoff }.toList()
            var purged = 0
            for (e in expired) {
                if (!deleteForever(e)) break
                purged++
            }
            if (purged > 0) emptyBin()
            expired.map { it.id }
        }

    /** Where an entry's payload currently lives inside the bin. */
    fun payloadFile(entry: Entry) =
        File(File(File(binRoot(), entry.bucket), entry.id), entry.name)

    // -------------------------------------------------------------- trashing

    /**
     * Move [file] into the bin. Returns null if it could not be moved — the caller
     * must NOT then delete it, or the user's fallback would defeat the whole point.
     */
    fun trash(context: Context, file: File): Entry? =
        synchronized(indexLock) { trashInternal(context, listOf(file)).second.firstOrNull() }

    /**
     * Move a batch into the bin and enforce the quota. Each item gets its own
     * payload dir, so one failed item cannot take the rest of the batch with it.
     */
    fun trash(context: Context, files: List<File>): TrashResult =
        synchronized(indexLock) { trashInternal(context, files).first }

    private fun trashInternal(
        context: Context,
        files: List<File>
    ): Pair<TrashResult, List<Entry>> {
        var index = readIndex()
        val now = System.currentTimeMillis()
        val bucket = "b_$now"
        var trashed = 0
        var failed = 0
        val evicted = mutableListOf<String>()
        val created = mutableListOf<Entry>()

        files.forEach { src ->
            if (!src.exists()) {
                failed++
                return@forEach
            }
            // Never let the bin trash itself — that would delete the index out
            // from under the entries we are about to write.
            if (src.canonicalPathSafe() == binRoot().canonicalPathSafe()) {
                failed++
                return@forEach
            }
            val id = "${UUID.randomUUID()}"
            val holder = File(File(binRoot(), bucket), id)
            val target = File(holder, src.name)
            val moved = try {
                if (!holder.mkdirs()) {
                    failed++
                    return@forEach
                }
                // Same volume: a rename is instant. The copy path is the fallback
                // for cross-volume or exotic filesystems.
                if (!src.renameTo(target)) {
                    if (src.isDirectory) {
                        src.copyRecursively(target, overwrite = false)
                        src.deleteRecursively()
                    } else {
                        src.copyTo(target, overwrite = false)
                        src.delete()
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
            if (!moved || !target.exists()) {
                runCatching { holder.deleteRecursively() }
                failed++
                return@forEach
            }
            val entry = Entry(
                id = id,
                name = src.name,
                isDirectory = src.isDirectory,
                sizeBytes = measureSize(target),
                deletedAt = now,
                bucket = bucket,
                originPath = src.absolutePath
            )
            index.add(entry)
            created.add(entry)
            trashed++
        }

        // Quota: evict oldest-first until the bin fits. An over-quota bin on a
        // small phone is a disk-full bug waiting to happen.
        //
        // The entries created by THIS call are pinned out of the sweep. They are
        // the newest, so they sorted last — which meant a single folder bigger
        // than the whole quota evicted the item the user had just deleted, while
        // the UI still toasted "moved to Recycle bin" and offered Undo for an
        // entry that no longer existed.
        val fresh = created.mapTo(mutableSetOf()) { it.id }
        val quota = quotaBytes(context)
        var total = index.sumOf { effectiveSize(it, quota) }
        val oldestFirst = index.filter { it.id !in fresh }.sortedBy { it.deletedAt }
        for (e in oldestFirst) {
            if (total <= quota) break
            if (removePayload(e)) {
                total -= effectiveSize(e, quota)
                evicted.add(e.name)
            }
            index.removeAll { it.id == e.id }
        }

        writeIndex(index)
        pruneEmptyBucketDirs()
        return TrashResult(trashed, failed, evicted) to created
    }

    // -------------------------------------------------------------- restoring

    /**
     * Put an entry back where it came from. Recreates the original parent folder
     * (so restoring still works after the containing folder itself was deleted) and
     * renames on collision instead of overwriting, because the restored file may be
     * an *older* version of a file the user has since replaced.
     */
    fun restore(entry: Entry): Boolean = synchronized(indexLock) { restoreLocked(entry) }

    private fun restoreLocked(entry: Entry): Boolean {
        val payload = payloadFile(entry)
        if (!payload.exists()) {
            // Payload gone but index row present — clean the row so the list is
            // not full of entries that can never be restored.
            writeIndex(readIndex().filterNot { it.id == entry.id })
            return false
        }
        val origin = File(entry.originPath)
        val parent = origin.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val target = if (origin.exists()) {
            StorageHelper.uniqueNameIn(parent, entry.name)
        } else {
            origin
        }
        val ok = runCatching {
            if (!payload.renameTo(target)) {
                if (payload.isDirectory) {
                    payload.copyRecursively(target, overwrite = false)
                    payload.deleteRecursively()
                } else {
                    payload.copyTo(target, overwrite = false)
                    payload.delete()
                }
            }
            true
        }.getOrDefault(false)
        if (ok) {
            writeIndex(readIndex().filterNot { it.id == entry.id })
            pruneEmptyBucketDirs()
        }
        return ok
    }

    fun restoreById(id: String): Boolean {
        val entry = readIndex().firstOrNull { it.id == id } ?: return false
        return restore(entry)
    }

    // -------------------------------------------------------------- emptying

    /** Permanently remove one entry (the bin's own "delete forever" row). */
    fun deleteForever(entry: Entry): Boolean = synchronized(indexLock) {
        val removed = removePayload(entry)
        writeIndex(readIndex().filterNot { it.id == entry.id })
        pruneEmptyBucketDirs()
        removed
    }

    /** Empty the whole bin. Returns how many entries were dropped. */
    fun emptyBin(): Int = synchronized(indexLock) {
        val index = readIndex()
        index.forEach { removePayload(it) }
        writeIndex(emptyList())
        runCatching { binRoot().listFiles()?.forEach { it.deleteRecursively() } }
        index.size
    }

    private fun removePayload(entry: Entry): Boolean {
        val holder = File(File(binRoot(), entry.bucket), entry.id)
        return runCatching { if (holder.exists()) holder.deleteRecursively() else true }
            .getOrDefault(false)
    }

    private fun pruneEmptyBucketDirs() {
        runCatching {
            binRoot().listFiles()?.forEach { f ->
                if (f.isDirectory && f.name.startsWith("b_") &&
                    (f.listFiles()?.isEmpty() ?: true)
                ) {
                    f.delete()
                }
            }
        }
    }

    // --------------------------------------------------------------- helpers

    /**
     * Bytes an entry counts against the quota.
     *
     * `measureSize` returns a NEGATIVE value when a tree was too large to walk
     * inside its cap, i.e. "unknown, and at least this much". The old accounting
     * skipped negatives (`if (sizeBytes > 0)`), so a 30 000-file folder counted
     * as 0 bytes: the bin sailed past its quota and nothing was ever evicted.
     * An unknown size now counts as one full [quota], which is enough pressure to
     * make progress, and it is subtracted symmetrically so the running total
     * cannot drift.
     */
    private fun effectiveSize(entry: Entry, quota: Long): Long =
        if (entry.sizeBytes >= 0) entry.sizeBytes else quota

    /** Bounded walk, same rules as folderStats: cap + symlink guard. */
    private fun measureSize(f: File): Long {
        if (!f.isDirectory) return if (f.exists()) f.length() else 0L
        var bytes = 0L
        var entries = 0
        val cap = 20_000
        val seen = HashSet<String>()
        val stack = ArrayDeque<File>()
        stack.addLast(f)
        runCatching { seen.add(f.canonicalPath) }
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val kids = runCatching { cur.listFiles() }.getOrNull() ?: continue
            for (k in kids) {
                if (++entries > cap) return -1L
                if (k.isDirectory) {
                    val canon = runCatching { k.canonicalPath }.getOrNull() ?: continue
                    if (seen.add(canon)) stack.addLast(k)
                } else {
                    bytes += k.length()
                }
            }
        }
        return bytes
    }

    private fun File.canonicalPathSafe(): String = runCatching { canonicalPath }.getOrDefault(absolutePath)
}
