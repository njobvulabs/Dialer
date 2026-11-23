package com.example.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY isFavorite DESC, name ASC")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE isPrivate = 0 ORDER BY isFavorite DESC, name ASC")
    fun getPublicContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE isPrivate = 1 ORDER BY isFavorite DESC, name ASC")
    fun getPrivateContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE isFavorite = 1 ORDER BY name ASC")
    fun getFavoriteContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE phoneNumber LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%'")
    fun searchContacts(query: String): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts ORDER BY name ASC")
    suspend fun getAllContactsOnce(): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity): Long


    @Update
    suspend fun updateContact(contact: ContactEntity)

    @Delete
    suspend fun deleteContact(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun deleteContactById(id: Long)
}

@Dao
interface CallLogDao {
    @Query("SELECT * FROM call_logs ORDER BY timestamp DESC")
    fun getAllCallLogs(): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs ORDER BY timestamp DESC")
    suspend fun getAllCallLogsOnce(): List<CallLogEntity>

    @Query("SELECT * FROM call_logs WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun getCallLogsSince(since: Long): List<CallLogEntity>

    @Query("SELECT COUNT(*) FROM call_logs WHERE callType = :type")
    fun countByType(type: CallType): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallLog(callLog: CallLogEntity): Long

    @Query("DELETE FROM call_logs WHERE id = :id")
    suspend fun deleteCallLogById(id: Long)

    @Query("SELECT * FROM call_logs WHERE id = :id")
    suspend fun getCallLogById(id: Long): CallLogEntity?

    @Query("UPDATE call_logs SET note = :note WHERE id = :id")
    suspend fun updateCallLogNote(id: Long, note: String?)

    @Query("UPDATE call_logs SET durationSeconds = :duration WHERE id = :id")
    suspend fun updateCallLogDuration(id: Long, duration: Int)

    @Query("UPDATE call_logs SET callType = :type WHERE id = :id")
    suspend fun updateCallLogType(id: Long, type: CallType)

    @Query("SELECT * FROM call_logs ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestCallLog(): CallLogEntity?

    @Query("DELETE FROM call_logs")
    suspend fun clearAllCallLogs()
}

@Dao
interface BlockedNumberDao {
    @Query("SELECT * FROM blocked_numbers ORDER BY timestamp DESC")
    fun getAllBlockedNumbers(): Flow<List<BlockedNumberEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_numbers WHERE number = :number LIMIT 1)")
    suspend fun isNumberBlocked(number: String): Boolean

    @Query("SELECT * FROM blocked_numbers ORDER BY timestamp DESC")
    suspend fun getBlockedOnce(): List<BlockedNumberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedNumber(blockedNumber: BlockedNumberEntity)

    @Query("DELETE FROM blocked_numbers WHERE number = :number")
    suspend fun unblockNumber(number: String)
}

@Dao
interface SpeedDialDao {
    @Query("SELECT * FROM speed_dials ORDER BY digit ASC")
    fun getAllSpeedDials(): Flow<List<SpeedDialEntity>>

    @Query("SELECT * FROM speed_dials ORDER BY digit ASC")
    suspend fun getAllSpeedDialsOnce(): List<SpeedDialEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSpeedDial(speedDial: SpeedDialEntity)

    @Query("DELETE FROM speed_dials WHERE digit = :digit")
    suspend fun removeSpeedDial(digit: Int)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    suspend fun getAllBookmarksOnce(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkEntity)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM browser_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM browser_history ORDER BY timestamp DESC")
    suspend fun getAllHistoryOnce(): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: HistoryEntity)

    /** Returns rows touched, so the caller knows whether it must insert instead. */
    @Query("UPDATE browser_history SET visitCount = visitCount + 1, timestamp = :ts WHERE url = :url")
    suspend fun bumpVisit(url: String, ts: Long): Int

    /**
     * Update the stored title/timestamp of an existing row WITHOUT counting a new
     * visit. Fires on page stop, when Gecko finally knows the real title — the
     * visit itself was already counted by [bumpVisit] at navigation commit, so
     * counting it here too would inflate every site.
     */
    @Query("UPDATE browser_history SET title = :title, timestamp = :ts WHERE url = :url")
    suspend fun touchHistory(url: String, title: String, ts: Long): Int

    /** Ranked for the new-tab page's "Top sites" (B8). */
    @Query("SELECT * FROM browser_history ORDER BY visitCount DESC, timestamp DESC LIMIT :limit")
    fun topVisited(limit: Int): Flow<List<HistoryEntity>>

    @Query("DELETE FROM browser_history WHERE id = :id")
    suspend fun deleteHistory(id: Long)

    @Query("DELETE FROM browser_history")
    suspend fun clearHistory()
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY timestamp DESC")
    fun getAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY timestamp DESC")
    suspend fun getAllNotesOnce(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchNotes(query: String): Flow<List<NoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity)

    @Delete
    suspend fun deleteNote(note: NoteEntity)

    @Query("UPDATE notes SET done = :done WHERE id = :id")
    suspend fun updateNoteDone(id: Long, done: Boolean)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC, id ASC")
    fun getPlaylistItems(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC, id ASC")
    suspend fun getPlaylistItemsOnce(playlistId: Long): List<PlaylistItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistItem(item: PlaylistItemEntity)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun nextPlaylistPosition(playlistId: Long): Int

    @Query("UPDATE playlist_items SET position = :position WHERE id = :id")
    suspend fun updatePlaylistItemPosition(id: Long, position: Int)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlist_items WHERE id = :id")
    suspend fun deletePlaylistItem(id: Long)

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)
}

@Dao
interface LocalSmsDao {
    @Query("SELECT * FROM local_sms WHERE threadId = :threadId ORDER BY date ASC")
    suspend fun forThread(threadId: Long): List<LocalSmsEntity>

    @Query("SELECT * FROM local_sms ORDER BY date DESC")
    suspend fun all(): List<LocalSmsEntity>

    @Query("SELECT * FROM local_sms WHERE id = :id")
    suspend fun byId(id: Long): LocalSmsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LocalSmsEntity): Long

    @Update
    suspend fun update(entity: LocalSmsEntity)

    @Query("UPDATE local_sms SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Delete
    suspend fun delete(entity: LocalSmsEntity)

    @Query("DELETE FROM local_sms WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Used by "Delete conversation": drops every locally-echoed message of a thread. */
    @Query("DELETE FROM local_sms WHERE threadId = :threadId")
    suspend fun deleteForThread(threadId: Long): Int

    /**
     * Re-parents locally-echoed messages from a SYNTHETIC thread id to the real
     * `content://sms` thread id the OS created once the message was actually sent.
     * Without this, a message to a brand-new recipient lands in two places — the
     * Room echo under the negative synthetic id and the OS copy under the real
     * thread — which is what showed up as a duplicated ("clone") conversation.
     */
    @Query("UPDATE local_sms SET threadId = :newThreadId WHERE threadId = :oldThreadId")
    suspend fun migrateThread(oldThreadId: Long, newThreadId: Long): Int
}

@Dao
interface CalendarEventDao {
    @Query("SELECT * FROM calendar_events ORDER BY startAt ASC")
    suspend fun all(): List<CalendarEventEntity>

    @Query("SELECT * FROM calendar_events WHERE endAt >= :from ORDER BY startAt ASC")
    fun upcoming(from: Long): Flow<List<CalendarEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CalendarEventEntity): Long

    @Update
    suspend fun update(event: CalendarEventEntity): Int

    @Delete
    suspend fun delete(event: CalendarEventEntity)
}
