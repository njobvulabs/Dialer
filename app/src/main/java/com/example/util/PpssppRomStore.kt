package com.example.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class PpssppRom(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val lastPlayed: Long
)

object PpssppRomStore {
    private const val MAX_ROM_BYTES = 4L * 1024L * 1024L * 1024L
    private val EXTENSIONS = setOf("iso", "cso", "pbp", "chd")

    data class Dirs(
        val root: File,
        val roms: File,
        val saves: File,
        val states: File,
        val system: File,
        val content: File,
        val index: File
    )

    fun dirs(context: Context): Dirs {
        val root = File(context.filesDir, "ppsspp")
        val roms = File(root, "roms")
        val saves = File(root, "saves")
        val states = File(root, "states")
        val system = File(root, "system")
        val content = File(root, "content")
        listOf(root, roms, saves, states, system, content).forEach { it.mkdirs() }
        return Dirs(root, roms, saves, states, system, content, File(root, "library.json"))
    }

    private fun discardTemp(context: Context, file: File) {
        if (file.exists()) runCatching { RecycleBin.trash(context, file) }
    }

    fun load(context: Context): List<PpssppRom> {
        val file = dirs(context).index
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { position ->
                val item = array.getJSONObject(position)
                val id = item.optString("id")
                val path = item.optString("path")
                if (id.isBlank() || path.isBlank() || !File(path).exists()) null else PpssppRom(
                    id = id,
                    name = item.optString("name").ifBlank { "Game" },
                    path = path,
                    size = item.optLong("size"),
                    lastPlayed = item.optLong("lastPlayed")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, items: List<PpssppRom>) {
        val array = JSONArray()
        items.forEach { rom ->
            array.put(
                JSONObject()
                    .put("id", rom.id)
                    .put("name", rom.name)
                    .put("path", rom.path)
                    .put("size", rom.size)
                    .put("lastPlayed", rom.lastPlayed)
            )
        }
        dirs(context).index.writeText(array.toString())
    }

    fun import(context: Context, uri: Uri): PpssppRom? {
        val rawName = displayName(context, uri)
        val extension = rawName.substringAfterLast('.', "").lowercase()
        if (extension !in EXTENSIONS) return null
        val temp = File(dirs(context).roms, "pending-${UUID.randomUUID()}.$extension")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var tooLarge = false
        runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("empty input")
            input.use { stream ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (total + read > MAX_ROM_BYTES) {
                            tooLarge = true
                            break
                        }
                        total += read
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
            }
        }.onFailure {
            discardTemp(context, temp)
            return null
        }
        if (tooLarge || total == 0L) {
            discardTemp(context, temp)
            return null
        }
        val id = digest.digest().joinToString("") { "%02x".format(it) }
        val existing = load(context).firstOrNull { it.id == id }
        if (existing != null) {
            discardTemp(context, temp)
            return touch(context, existing.id).firstOrNull { it.id == id } ?: existing
        }
        val target = File(dirs(context).roms, "$id.$extension")
        if (!target.exists() && !temp.renameTo(target)) {
            discardTemp(context, temp)
            return null
        }
        if (target.exists()) discardTemp(context, temp)
        val display = rawName.substringBeforeLast('.', rawName).trim().take(96).ifBlank { "Game" }
        val rom = PpssppRom(id, display, target.absolutePath, total, System.currentTimeMillis())
        save(context, listOf(rom) + load(context).filterNot { it.id == id })
        return rom
    }

    fun touch(context: Context, id: String): List<PpssppRom> {
        val updated = load(context).map { if (it.id == id) it.copy(lastPlayed = System.currentTimeMillis()) else it }
        save(context, updated)
        return updated
    }

    fun delete(context: Context, rom: PpssppRom): Boolean {
        val result = runCatching { RecycleBin.trash(context, File(rom.path)) }.getOrNull() != null
        if (!result) return false
        save(context, load(context).filterNot { it.id == rom.id })
        return true
    }

    fun stateFile(context: Context, rom: PpssppRom, slot: Int): File =
        File(dirs(context).states, "${rom.id}.state${slot.coerceIn(1, 3)}")

    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        }
        return fromProvider?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "game.iso"
    }
}
