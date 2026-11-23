package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phoneNumber: String,
    val email: String = "",
    val isFavorite: Boolean = false,
    val groupName: String = "General",
    val isPrivate: Boolean = false,
    val avatarUri: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class CallType {
    INCOMING, OUTGOING, MISSED, REJECTED
}

@Entity(tableName = "call_logs")
data class CallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val callerName: String? = null,
    val callType: CallType,

    val timestamp: Long = System.currentTimeMillis(),
    val durationSeconds: Int = 0,
    val simSlot: Int = 1,
    val isSpam: Boolean = false,
    val note: String? = null
)

@Entity(tableName = "blocked_numbers")
data class BlockedNumberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val reason: String = "User Blocked",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "speed_dials")
data class SpeedDialEntity(
    @PrimaryKey val digit: Int, // 1 to 9
    val phoneNumber: String,
    val label: String
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "browser_history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * How many times this URL was opened (B8). One row per URL: a repeat visit
     * bumps this counter and the timestamp instead of inserting a duplicate, which
     * is what makes "Top sites" on the new-tab page meaningful.
     */
    val visitCount: Int = 1
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val colorHex: String = "#3B82F6",
    val isPinned: Boolean = false,
    val category: String = "General",
    val isPrivate: Boolean = false,
    val isTodo: Boolean = false,
    val done: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlist_items")
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val title: String,
    val uri: String,
    val artist: String = "Unknown Artist",
    val durationMs: Long = 0,
    /** Manual ordering inside a playlist; ties fall back to the row id. */
    val position: Int = 0
)

// Locally-sent SMS messages. Since nj-dialer is not the default SMS app it cannot
// write into the system content://sms provider, so sent messages are persisted here
// and merged with provider messages in the SMS UI.
@Entity(tableName = "local_sms")
data class LocalSmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long = System.currentTimeMillis(),
    val simSlot: Int = 0,
    val status: String = "SENT" // "SENDING" | "SENT" | "DELIVERED" | "FAILED"
)

/** A locally-owned calendar item. Times are epoch milliseconds in the device zone. */
@Entity(tableName = "calendar_events")
data class CalendarEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val details: String = "",
    val startAt: Long,
    val endAt: Long,
    val reminderMinutes: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
