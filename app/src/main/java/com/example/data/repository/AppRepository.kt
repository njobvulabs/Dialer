package com.example.data.repository

import com.example.data.db.*
import kotlinx.coroutines.flow.Flow

class AppRepository(private val db: AppDatabase) {

    // Contacts
    val allContacts: Flow<List<ContactEntity>> = db.contactDao().getAllContacts()
    val publicContacts: Flow<List<ContactEntity>> = db.contactDao().getPublicContacts()
    val privateContacts: Flow<List<ContactEntity>> = db.contactDao().getPrivateContacts()
    val favoriteContacts: Flow<List<ContactEntity>> = db.contactDao().getFavoriteContacts()

    suspend fun insertContact(contact: ContactEntity) = db.contactDao().insertContact(contact)
    suspend fun updateContact(contact: ContactEntity) = db.contactDao().updateContact(contact)
    suspend fun deleteContact(contact: ContactEntity) = db.contactDao().deleteContact(contact)
    suspend fun deleteContactById(id: Long) = db.contactDao().deleteContactById(id)
    suspend fun getContactsOnce(): List<ContactEntity> = db.contactDao().getAllContactsOnce()

    // Call Logs
    val allCallLogs: Flow<List<CallLogEntity>> = db.callLogDao().getAllCallLogs()
    fun callLogCount(type: CallType): Flow<Int> = db.callLogDao().countByType(type)

    suspend fun getCallLogsOnce(): List<CallLogEntity> = db.callLogDao().getAllCallLogsOnce()
    suspend fun getCallLogsSince(since: Long): List<CallLogEntity> = db.callLogDao().getCallLogsSince(since)
    suspend fun insertCallLog(callLog: CallLogEntity) = db.callLogDao().insertCallLog(callLog)
    suspend fun deleteCallLog(id: Long) = db.callLogDao().deleteCallLogById(id)
    suspend fun getCallLogById(id: Long): CallLogEntity? = db.callLogDao().getCallLogById(id)
    suspend fun updateCallLogNote(id: Long, note: String?) = db.callLogDao().updateCallLogNote(id, note)
    suspend fun updateCallLogDuration(id: Long, duration: Int) = db.callLogDao().updateCallLogDuration(id, duration)
    suspend fun updateCallLogType(id: Long, type: CallType) = db.callLogDao().updateCallLogType(id, type)
    suspend fun getLatestCallLog(): CallLogEntity? = db.callLogDao().getLatestCallLog()
    suspend fun updateLatestCallDuration(duration: Int) {
        db.callLogDao().getLatestCallLog()?.let { db.callLogDao().updateCallLogDuration(it.id, duration) }
    }
    suspend fun clearAllCallLogs() = db.callLogDao().clearAllCallLogs()

    // Blocked Numbers
    val allBlockedNumbers: Flow<List<BlockedNumberEntity>> = db.blockedNumberDao().getAllBlockedNumbers()

    suspend fun isNumberBlocked(number: String): Boolean = db.blockedNumberDao().isNumberBlocked(number)
    suspend fun blockNumber(number: String, reason: String = "User Blocked") {
        db.blockedNumberDao().insertBlockedNumber(BlockedNumberEntity(number = number, reason = reason))
    }
    suspend fun unblockNumber(number: String) = db.blockedNumberDao().unblockNumber(number)

    // Speed Dial
    val allSpeedDials: Flow<List<SpeedDialEntity>> = db.speedDialDao().getAllSpeedDials()

    suspend fun setSpeedDial(digit: Int, number: String, label: String) {
        db.speedDialDao().setSpeedDial(SpeedDialEntity(digit = digit, phoneNumber = number, label = label))
    }
    suspend fun removeSpeedDial(digit: Int) = db.speedDialDao().removeSpeedDial(digit)

    // Bookmarks
    val allBookmarks: Flow<List<BookmarkEntity>> = db.bookmarkDao().getAllBookmarks()

    suspend fun insertBookmark(title: String, url: String) {
        db.bookmarkDao().insertBookmark(BookmarkEntity(title = title, url = url))
    }
    suspend fun deleteBookmark(bookmark: BookmarkEntity) = db.bookmarkDao().deleteBookmark(bookmark)

    // History
    val allHistory: Flow<List<HistoryEntity>> = db.historyDao().getAllHistory()

    /**
     * Record a page open for the Top-sites ranking (B8). Repeats bump the counter
     * and the timestamp on the existing row rather than adding a second one — the
     * old insert-only path made every revisit a new row, so "most visited" was
     * really "most recently visited, with duplicates".
     */
    suspend fun recordVisit(title: String, url: String) {
        if (url.isBlank() || url.startsWith("about:")) return
        val now = System.currentTimeMillis()
        val touched = db.historyDao().bumpVisit(url, now)
        if (touched == 0) {
            db.historyDao().insertHistory(
                HistoryEntity(title = title.ifEmpty { url }, url = url, timestamp = now, visitCount = 1)
            )
        }
    }

    /** Most-visited URLs, newest first on ties. */
    fun topVisited(limit: Int) = db.historyDao().topVisited(limit)

    /**
     * Page-stop hook: refresh the stored title/timestamp for a URL already counted
     * by [recordVisit]. This used to unconditionally INSERT, which appended a new
     * row on every single page load — the reason the old history list filled up
     * with repeats of the same handful of sites. Now it only touches a row, and
     * inserts a first-time row only when there genuinely is none.
     */
    suspend fun insertHistory(title: String, url: String) {
        if (url.isBlank() || url.startsWith("about:")) return
        val now = System.currentTimeMillis()
        val touched = db.historyDao().touchHistory(url, title.ifEmpty { url }, now)
        if (touched == 0) {
            db.historyDao().insertHistory(
                HistoryEntity(title = title.ifEmpty { url }, url = url, timestamp = now)
            )
        }
    }
    suspend fun deleteHistory(id: Long) = db.historyDao().deleteHistory(id)
    suspend fun clearHistory() = db.historyDao().clearHistory()

    // Notes
    val allNotes: Flow<List<NoteEntity>> = db.noteDao().getAllNotes()

    suspend fun insertNote(note: NoteEntity) = db.noteDao().insertNote(note)
    suspend fun deleteNote(note: NoteEntity) = db.noteDao().deleteNote(note)
    suspend fun updateNoteDone(id: Long, done: Boolean) = db.noteDao().updateNoteDone(id, done)

    // Playlists
    val allPlaylists: Flow<List<PlaylistEntity>> = db.playlistDao().getAllPlaylists()

    suspend fun createPlaylist(name: String): Long = db.playlistDao().insertPlaylist(PlaylistEntity(name = name))
    fun getPlaylistItems(playlistId: Long): Flow<List<PlaylistItemEntity>> = db.playlistDao().getPlaylistItems(playlistId)
    suspend fun addPlaylistItem(item: PlaylistItemEntity) {
        val position = db.playlistDao().nextPlaylistPosition(item.playlistId)
        db.playlistDao().insertPlaylistItem(item.copy(position = position))
    }
    suspend fun deletePlaylistItem(id: Long) = db.playlistDao().deletePlaylistItem(id)
    suspend fun deletePlaylist(playlist: PlaylistEntity) = db.playlistDao().deletePlaylist(playlist)
    suspend fun renamePlaylist(id: Long, name: String) = db.playlistDao().renamePlaylist(id, name)

    /** Swap an item with its neighbour in the same playlist (dir -1 = up). */
    suspend fun movePlaylistItem(playlistId: Long, itemId: Long, dir: Int) {
        val items = db.playlistDao().getPlaylistItemsOnce(playlistId)
        val index = items.indexOfFirst { it.id == itemId }
        val target = index + dir
        if (index < 0 || target !in items.indices) return
        val a = items[index]
        val b = items[target]
        db.playlistDao().updatePlaylistItemPosition(a.id, b.position)
        db.playlistDao().updatePlaylistItemPosition(b.id, a.position)
    }

    // Local Sent SMS
    suspend fun getLocalSmsForThread(threadId: Long): List<LocalSmsEntity> =
        db.localSmsDao().forThread(threadId)

    suspend fun getLocalSmsAll(): List<LocalSmsEntity> = db.localSmsDao().all()

    suspend fun insertLocalSms(entity: LocalSmsEntity): Long = db.localSmsDao().insert(entity)

    suspend fun updateLocalSmsStatus(id: Long, status: String) = db.localSmsDao().updateStatus(id, status)

    suspend fun deleteLocalSms(entity: LocalSmsEntity) = db.localSmsDao().delete(entity)

    suspend fun deleteLocalSmsById(id: Long) = db.localSmsDao().deleteById(id)

    suspend fun deleteLocalSmsForThread(threadId: Long): Int = db.localSmsDao().deleteForThread(threadId)

    /** Re-parents a synthetic thread's echoes onto the real provider thread id. */
    suspend fun migrateLocalSmsThread(oldThreadId: Long, newThreadId: Long): Int =
        db.localSmsDao().migrateThread(oldThreadId, newThreadId)

    // Calendar
    fun upcomingCalendarEvents(from: Long) = db.calendarEventDao().upcoming(from)
    suspend fun allCalendarEvents() = db.calendarEventDao().all()
    suspend fun insertCalendarEvent(event: CalendarEventEntity) = db.calendarEventDao().insert(event)
    suspend fun updateCalendarEvent(event: CalendarEventEntity) = db.calendarEventDao().update(event)
    suspend fun deleteCalendarEvent(event: CalendarEventEntity) = db.calendarEventDao().delete(event)
}
