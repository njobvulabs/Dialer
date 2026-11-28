package com.example.util

import android.content.Context
import android.provider.CallLog
import com.example.data.db.CallLogEntity
import com.example.data.db.CallType
import com.example.data.repository.AppRepository

object SystemCallLogImporter {

    private const val PREFS = "app_settings"
    private const val TOMBSTONES_KEY = "call_log_tombstones"
    private const val LAST_SYNC_KEY = "call_log_last_sync"
    private const val OVERLAP_MS = 120_000L

    /**
     * Reads the system call log (CallLog.Calls) and merges entries into the app's
     * local Room database. Reads incrementally from the last successful sync;
     * deduplicates against local rows AND permanently deleted ("tombstoned")
     * keys by (number, callType, timestamp within ±2 minutes).
     *
     * @return true only when the provider was read successfully, so callers can
     *   advance the sync cursor without treating a failed read as "no new calls".
     */
    suspend fun sync(repository: AppRepository, context: Context): Boolean {
        if (!hasReadPermission(context)) return false

        val startedAt = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSync = prefs.getLong(LAST_SYNC_KEY, 0L)
        val fullScan = lastSync <= 0L || lastSync > startedAt + 60_000L
        val since = if (fullScan) 0L else (lastSync - OVERLAP_MS).coerceAtLeast(0L)

        val existing = if (fullScan) {
            repository.getCallLogsOnce()
        } else {
            repository.getCallLogsSince(since)
        }
        val existingKeys = existing.map { keyOf(it.number, it.callType, it.timestamp) }.toSet()
        val tombstones = getTombstones(context)

        try {
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )

            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                if (fullScan) null else "${CallLog.Calls.DATE} >= ?",
                if (fullScan) null else arrayOf(since.toString()),
                "${CallLog.Calls.DATE} DESC"
            ) ?: return false

            cursor.use { c ->
                val numberCol = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameCol = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeCol = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateCol = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durationCol = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)

                val seenLocal = HashSet<String>()
                while (c.moveToNext()) {
                    val number = c.getString(numberCol).orEmpty().replace(" ", "")
                    if (number.isEmpty()) continue

                    val type = mapType(c.getInt(typeCol)) ?: continue
                    val timestamp = c.getLong(dateCol)
                    val duration = c.getInt(durationCol).coerceAtLeast(0)

                    val key = keyOf(number, type, timestamp)
                    if (key in existingKeys || key in seenLocal || key in tombstones) continue
                    seenLocal.add(key)

                    repository.insertCallLog(
                        CallLogEntity(
                            number = number,
                            callerName = c.getString(nameCol)?.ifBlank { null },
                            callType = type,
                            timestamp = timestamp,
                            durationSeconds = duration,
                            simSlot = 1,
                            isSpam = false
                        )
                    )
                }
            }
            prefs.edit().putLong(LAST_SYNC_KEY, startedAt).apply()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * Permanently clears all call history: tombstones every entry currently in the
     * system log so it can never be re-imported, attempts to erase the entries from
     * the system call log as well, then empties the local table.
     */
    suspend fun clearAll(repository: AppRepository, context: Context) {
        val keys = readSystemKeys(context)
        tombstoneAll(context, keys)
        if (hasWritePermission(context)) {
            runCatching {
                context.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
            }
        }
        repository.clearAllCallLogs()
    }

    /**
     * Permanently deletes a single history row: tombstones its key so it cannot be
     * re-imported, attempts to erase the matching entry from the system call log,
     * then removes the local row.
     */
    suspend fun deleteOne(repository: AppRepository, context: Context, log: CallLogEntity) {
        tombstoneAll(context, setOf(keyOf(log.number, log.callType, log.timestamp)))
        if (hasWritePermission(context)) {
            runCatching {
                val selection = "${CallLog.Calls.NUMBER} = ? AND " +
                    "${CallLog.Calls.DATE} >= ? AND " +
                    "${CallLog.Calls.DATE} <= ? AND " +
                    "${CallLog.Calls.TYPE} = ?"
                val args = arrayOf(
                    log.number,
                    (log.timestamp - 120_000L).toString(),
                    (log.timestamp + 120_000L).toString(),
                    toSystemType(log.callType).toString()
                )
                context.contentResolver.delete(CallLog.Calls.CONTENT_URI, selection, args)
            }
        }
        repository.deleteCallLog(log.id)
    }

    private fun keyOf(number: String, type: CallType, timestamp: Long) =
        "$number|$type|${timestamp / 120000}"

    private fun getTombstones(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(TOMBSTONES_KEY, emptySet()) ?: emptySet()

    private fun tombstoneAll(context: Context, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val current = getTombstones(context).toMutableSet()
        if (current.addAll(keys)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(TOMBSTONES_KEY, current).apply()
        }
    }

    private fun readSystemKeys(context: Context): List<String> {
        val keys = mutableListOf<String>()
        try {
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE
            )
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                val numberCol = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val typeCol = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateCol = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                while (c.moveToNext()) {
                    val number = c.getString(numberCol).orEmpty().replace(" ", "")
                    if (number.isEmpty()) continue
                    val type = mapType(c.getInt(typeCol)) ?: continue
                    keys.add(keyOf(number, type, c.getLong(dateCol)))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return keys
    }

    private fun mapType(systemType: Int): CallType? {
        return when (systemType) {
            CallLog.Calls.INCOMING_TYPE -> CallType.INCOMING
            CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
            CallLog.Calls.MISSED_TYPE -> CallType.MISSED
            CallLog.Calls.REJECTED_TYPE -> CallType.REJECTED
            else -> null
        }
    }

    private fun toSystemType(type: CallType): Int {
        return when (type) {
            CallType.INCOMING -> CallLog.Calls.INCOMING_TYPE
            CallType.OUTGOING -> CallLog.Calls.OUTGOING_TYPE
            CallType.MISSED -> CallLog.Calls.MISSED_TYPE
            CallType.REJECTED -> CallLog.Calls.REJECTED_TYPE
        }
    }

    private fun hasReadPermission(context: Context): Boolean {
        return hasPermission(context, android.Manifest.permission.READ_CALL_LOG)
    }

    private fun hasWritePermission(context: Context): Boolean {
        return hasPermission(context, android.Manifest.permission.WRITE_CALL_LOG)
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return try {
            context.checkSelfPermission(permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }
}