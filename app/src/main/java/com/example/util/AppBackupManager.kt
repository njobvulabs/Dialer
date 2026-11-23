package com.example.util

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.db.BlockedNumberEntity
import com.example.data.db.ContactEntity
import com.example.data.db.CalendarEventEntity
import com.example.data.db.NoteEntity
import com.example.data.repository.AppRepository
import com.example.ui.components.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One-file backup/restore of user data: notes, to-dos, bookmarks, browser
 * history, speed dials, blocked numbers, calendar events and key app preferences.
 */
object AppBackupManager {

    private const val VERSION = 1

    /** Prefs (from app_settings) included in backups. */
    private val PREF_KEYS_BOOLEAN = listOf(
        "vibrate_on_connect", "flash_on_call", "block_unknown_callers",
        "block_private_callers", "block_international", "silent_first_time",
        "announce_caller", "auto_record_calls", "missed_reminder_enabled"
    )
    private val PREF_KEYS_STRING = listOf(
        "theme_mode", "accent", "missed_reminder_interval", "local_country_code"
    )

    fun backupFile(): File {
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ), "NJ-Dialer"
        )
        dir.mkdirs()
        return File(dir, "nj_backup_${System.currentTimeMillis()}.json")
    }

    suspend fun export(context: Context, repository: AppRepository): File? =
        withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(context)
                val json = buildJson(context, db, repository)
                val out = backupFile()
                out.writeText(json.toString(2))
                out
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    private suspend fun buildJson(context: Context, db: AppDatabase, repository: AppRepository): JSONObject {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("exported_at", System.currentTimeMillis())

        root.put("notes", JSONArray().apply {
            db.noteDao().getAllNotesOnce().forEach { n ->
                put(JSONObject()
                    .put("title", n.title)
                    .put("content", n.content)
                    .put("colorHex", n.colorHex)
                    .put("isPinned", n.isPinned)
                    .put("category", n.category)
                    .put("isPrivate", n.isPrivate)
                    .put("isTodo", n.isTodo)
                    .put("done", n.done)
                    .put("timestamp", n.timestamp))
            }
        })

        // Legacy todo_prefs blob (kept for older nj-dialer versions restoring
        // this backup). Tasks now live in the notes table as isTodo rows.
        root.put("todos", JSONArray().apply {
            db.noteDao().getAllNotesOnce().filter { it.isTodo }.forEach { n ->
                put(JSONObject()
                    .put("id", "note_${n.id}")
                    .put("text", n.title)
                    .put("done", n.done)
                    .put("ts", n.timestamp))
            }
        })

        root.put("bookmarks", JSONArray().apply {
            db.bookmarkDao().getAllBookmarksOnce().forEach { b ->
                put(JSONObject().put("title", b.title).put("url", b.url)
                    .put("createdAt", b.createdAt))
            }
        })

        root.put("browser_history", JSONArray().apply {
            db.historyDao().getAllHistoryOnce().forEach { h ->
                put(JSONObject().put("title", h.title).put("url", h.url)
                    .put("timestamp", h.timestamp))
            }
        })

        root.put("speed_dials", JSONArray().apply {
            db.speedDialDao().getAllSpeedDialsOnce().forEach { s ->
                put(JSONObject().put("digit", s.digit).put("phoneNumber", s.phoneNumber)
                    .put("label", s.label))
            }
        })

        root.put("blocked_numbers", JSONArray().apply {
            db.blockedNumberDao().getBlockedOnce().forEach { b ->
                put(JSONObject().put("number", b.number).put("reason", b.reason)
                    .put("timestamp", b.timestamp))
            }
        })

        root.put("contacts", JSONArray().apply {
            db.contactDao().getAllContactsOnce().forEach { c ->
                put(JSONObject()
                    .put("name", c.name)
                    .put("phoneNumber", c.phoneNumber)
                    .put("email", c.email)
                    .put("isFavorite", c.isFavorite)
                    .put("groupName", c.groupName)
                    .put("isPrivate", c.isPrivate))
            }
        })

        // Calendar is app-owned data (unlike the system call/SMS providers), so it
        // can be restored faithfully on a replacement device.
        root.put("calendar_events", JSONArray().apply {
            repository.allCalendarEvents().forEach { event ->
                put(JSONObject().put("title", event.title).put("details", event.details)
                    .put("startAt", event.startAt).put("endAt", event.endAt)
                    .put("createdAt", event.createdAt))
            }
        })

        val prefsJson = JSONObject()
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        PREF_KEYS_BOOLEAN.forEach { prefsJson.put(it, prefs.getBoolean(it, false)) }
        PREF_KEYS_STRING.forEach { prefsJson.put(it, prefs.getString(it, "") ?: "") }
        root.put("prefs", prefsJson)

        return root
    }

    data class ImportResult(val notes: Int, val todos: Boolean, val bookmarks: Int,
                            val history: Int, val contacts: Int, val calendarEvents: Int)

    suspend fun import(context: Context, file: File, repository: AppRepository): ImportResult? =
        withContext(Dispatchers.IO) {
            try {
                val root = JSONObject(file.readText())
                val db = AppDatabase.getDatabase(context)

                // Notes (append). Include isTodo/done rows; also read a legacy
                // "todos" JSONArray string in the same backup so older backups
                // (whose tasks lived outside the notes table) still import as tasks.
                var noteCount = 0
                val notesArr = root.optJSONArray("notes") ?: JSONArray()
                for (i in 0 until notesArr.length()) {
                    val o = notesArr.getJSONObject(i)
                    val title = o.optString("title")
                    val content = o.optString("content")
                    if (title.isEmpty() && content.isEmpty()) continue
                    runCatching {
                        repository.insertNote(
                            NoteEntity(
                                title = title,
                                content = content,
                                colorHex = o.optString("colorHex", "#3B82F6"),
                                isPinned = o.optBoolean("isPinned"),
                                category = o.optString("category", "General"),
                                isPrivate = o.optBoolean("isPrivate"),
                                isTodo = o.optBoolean("isTodo"),
                                done = o.optBoolean("done"),
                                timestamp = o.optLong("timestamp")
                            )
                        )
                        noteCount++
                    }
                }
                val legacyTodos = root.optJSONArray("todos")
                if (legacyTodos != null) {
                    for (i in 0 until legacyTodos.length()) {
                        val o = legacyTodos.optJSONObject(i)
                        val text = o?.optString("text", "")?.trim() ?: ""
                        if (text.isEmpty()) continue
                        runCatching {
                            repository.insertNote(
                                NoteEntity(
                                    title = text,
                                    content = "",
                                    colorHex = "#10B981",
                                    category = "Tasks",
                                    isTodo = true,
                                    done = o.optBoolean("done", false),
                                    timestamp = o.optLong("ts", System.currentTimeMillis())
                                )
                            )
                            noteCount++
                        }
                    }
                }

                // Bookmarks (append by URL)
                var bmCount = 0
                val bmArr = root.optJSONArray("bookmarks") ?: JSONArray()
                val existingUrls = db.bookmarkDao().getAllBookmarksOnce().map { it.url }.toSet()
                for (i in 0 until bmArr.length()) {
                    val o = bmArr.getJSONObject(i)
                    val url = o.optString("url")
                    if (url in existingUrls) continue
                    runCatching { repository.insertBookmark(o.optString("title"), url); bmCount++ }
                }

                // Browser history (append)
                var histCount = 0
                val histArr = root.optJSONArray("browser_history") ?: JSONArray()
                for (i in 0 until histArr.length()) {
                    val o = histArr.getJSONObject(i)
                    runCatching {
                        repository.insertHistory(o.optString("title"), o.optString("url"))
                        histCount++
                    }
                }

                // Speed dials (replace)
                val sdArr = root.optJSONArray("speed_dials")
                if (sdArr != null) {
                    for (i in 0 until sdArr.length()) {
                        val o = sdArr.getJSONObject(i)
                        runCatching {
                            repository.setSpeedDial(
                                o.getInt("digit"), o.getString("phoneNumber"), o.optString("label")
                            )
                        }
                    }
                }

                // Blocked numbers (append)
                val bnArr = root.optJSONArray("blocked_numbers")
                if (bnArr != null) {
                    for (i in 0 until bnArr.length()) {
                        val o = bnArr.getJSONObject(i)
                        runCatching {
                            db.blockedNumberDao().insertBlockedNumber(
                                BlockedNumberEntity(
                                    number = o.getString("number"),
                                    reason = o.optString("reason", "User Blocked"),
                                    timestamp = o.optLong("timestamp")
                                )
                            )
                        }
                    }
                }

                // Contacts (skip numbers already present)
                var contactCount = 0
                val ctArr = root.optJSONArray("contacts")
                if (ctArr != null) {
                    val existingNums = ContactTools.run {
                        repository.getContactsOnce().flatMap {
                            it.phoneNumber.split(",").map { p -> normalize(p) }
                        }.toSet()
                    }
                    for (i in 0 until ctArr.length()) {
                        val o = ctArr.getJSONObject(i)
                        val nums = o.optString("phoneNumber").split(",").map { it.trim() }
                        if (nums.any { ContactTools.normalize(it) in existingNums }) continue
                        runCatching {
                            repository.insertContact(
                                ContactEntity(
                                    name = o.optString("name"),
                                    phoneNumber = o.optString("phoneNumber"),
                                    email = o.optString("email"),
                                    isFavorite = o.optBoolean("isFavorite"),
                                    groupName = o.optString("groupName", "General"),
                                    isPrivate = o.optBoolean("isPrivate")
                                )
                            )
                            contactCount++
                        }
                    }
                }

                // Prefs
                val prefsJson = root.optJSONObject("prefs")
                if (prefsJson != null) {
                    val e = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit()
                    PREF_KEYS_BOOLEAN.forEach { k ->
                        if (prefsJson.has(k)) e.putBoolean(k, prefsJson.getBoolean(k))
                    }
                    PREF_KEYS_STRING.forEach { k ->
                        if (prefsJson.has(k)) e.putString(k, prefsJson.getString(k))
                    }
                    e.apply()
                }

                // Calendar (append).
                var calendarCount = 0
                val calendarArr = root.optJSONArray("calendar_events") ?: JSONArray()
                for (i in 0 until calendarArr.length()) {
                    val o = calendarArr.optJSONObject(i) ?: continue
                    val startAt = o.optLong("startAt", 0L)
                    if (startAt <= 0L) continue
                    runCatching {
                        val event = CalendarEventEntity(
                            title = o.optString("title", "Event"),
                            details = o.optString("details"),
                            startAt = startAt,
                            endAt = o.optLong("endAt", startAt + 3_600_000L),
                            reminderMinutes = 0,
                            createdAt = o.optLong("createdAt", System.currentTimeMillis())
                        )
                        repository.insertCalendarEvent(event)
                        calendarCount++
                    }
                }

                ImportResult(noteCount, true, bmCount, histCount, contactCount, calendarCount)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
}
