package com.example.util

import android.Manifest
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.example.data.db.AppDatabase
import com.example.data.db.LocalSmsEntity
import com.example.data.repository.AppRepository
import com.example.ui.screens.sms.EXTRA_LOCAL_ID
import com.example.ui.screens.sms.SMS_DELIVERED_ACTION
import com.example.ui.screens.sms.SMS_SENT_ACTION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class AssistantConversation(
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val unreadCount: Int = 0,
    val simSlot: Int = 0
)

data class AssistantSmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val simSlot: Int,
    val status: String? = null,
    val isLocal: Boolean = false
)

data class AssistantSmsResult<T>(val data: T, val error: String? = null)

object AssistantSms {
    private val OUTGOING_SMS_TYPES = setOf(2, 4, 5, 6)
    private const val DEDUP_WINDOW_MS = 60_000L
    private const val DEDUP_SKEW_MS = 5_000L

    fun hasReadSms(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasSendSms(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun repository(context: Context): AppRepository =
        AppRepository(AppDatabase.getDatabase(context.applicationContext))

    suspend fun loadConversations(context: Context): AssistantSmsResult<List<AssistantConversation>> =
        withContext(Dispatchers.IO) {
            if (!hasReadSms(context)) {
                return@withContext AssistantSmsResult(emptyList(), "READ_SMS permission not granted")
            }
            val byThread = LinkedHashMap<Long, AssistantConversation>()
            val unreadByThread = HashMap<Long, Int>()
            try {
                context.contentResolver.query(
                    Uri.parse("content://sms"),
                    arrayOf("_id", "thread_id", "address", "body", "date", "read", "sub_id"),
                    null,
                    null,
                    "date DESC"
                )?.use { cursor ->
                    val iThread = cursor.getColumnIndexOrThrow("thread_id")
                    val iAddress = cursor.getColumnIndex("address")
                    val iBody = cursor.getColumnIndex("body")
                    val iDate = cursor.getColumnIndex("date")
                    val iRead = cursor.getColumnIndex("read")
                    val iSub = cursor.subIdColumn()
                    while (cursor.moveToNext()) {
                        val threadId = cursor.getLong(iThread)
                        val subId = if (iSub >= 0) cursor.getLong(iSub) else -1L
                        val read = if (iRead >= 0) cursor.getInt(iRead) else 1
                        if (read == 0) unreadByThread[threadId] = (unreadByThread[threadId] ?: 0) + 1
                        if (!byThread.containsKey(threadId)) {
                            byThread[threadId] = AssistantConversation(
                                threadId = threadId,
                                address = if (iAddress >= 0) cursor.getString(iAddress) ?: "" else "",
                                body = if (iBody >= 0) cursor.getString(iBody) ?: "" else "",
                                date = if (iDate >= 0) cursor.getLong(iDate) else 0L,
                                unreadCount = unreadByThread[threadId] ?: 0,
                                simSlot = subIdToSlot(context, subId)
                            )
                        }
                    }
                }
                repository(context).getLocalSmsAll().forEach { local ->
                    val existing = byThread[local.threadId]
                    when {
                        existing == null -> byThread[local.threadId] = AssistantConversation(
                            threadId = local.threadId,
                            address = local.address,
                            body = local.body,
                            date = local.date,
                            simSlot = local.simSlot
                        )
                        local.date >= existing.date -> byThread[local.threadId] = existing.copy(
                            body = local.body,
                            date = local.date,
                            simSlot = local.simSlot
                        )
                    }
                }
            } catch (e: Exception) {
                return@withContext AssistantSmsResult(emptyList(), "Couldn't read SMS: ${e.message}")
            }
            val deleted = readDeletedConversations(context)
            val restored = HashSet<String>()
            val visible = byThread.values.filter { conversation ->
                val key = normalizeNumber(conversation.address)
                val deletedAt = deleted[key]
                when {
                    deletedAt == null -> true
                    conversation.date > deletedAt -> {
                        restored += key
                        true
                    }
                    else -> false
                }
            }
            if (restored.isNotEmpty()) clearDeletedConversations(context, restored)
            AssistantSmsResult(visible.sortedByDescending { it.date })
        }

    suspend fun loadThread(context: Context, threadId: Long): AssistantSmsResult<List<AssistantSmsMessage>> =
        withContext(Dispatchers.IO) {
            if (!hasReadSms(context)) {
                return@withContext AssistantSmsResult(emptyList(), "READ_SMS permission not granted")
            }
            val messages = mutableListOf<AssistantSmsMessage>()
            try {
                context.contentResolver.query(
                    Uri.parse("content://sms"),
                    arrayOf("_id", "address", "body", "date", "type", "sub_id"),
                    "thread_id = ?",
                    arrayOf(threadId.toString()),
                    "date ASC"
                )?.use { cursor ->
                    val iId = cursor.getColumnIndexOrThrow("_id")
                    val iAddress = cursor.getColumnIndex("address")
                    val iBody = cursor.getColumnIndex("body")
                    val iDate = cursor.getColumnIndex("date")
                    val iType = cursor.getColumnIndex("type")
                    val iSub = cursor.subIdColumn()
                    while (cursor.moveToNext()) {
                        val subId = if (iSub >= 0) cursor.getLong(iSub) else -1L
                        messages += AssistantSmsMessage(
                            id = cursor.getLong(iId),
                            address = if (iAddress >= 0) cursor.getString(iAddress) ?: "" else "",
                            body = if (iBody >= 0) cursor.getString(iBody) ?: "" else "",
                            date = if (iDate >= 0) cursor.getLong(iDate) else 0L,
                            type = if (iType >= 0) cursor.getInt(iType) else 1,
                            simSlot = subIdToSlot(context, subId)
                        )
                    }
                }
                val echoes = repository(context).getLocalSmsForThread(threadId)
                val consumed = HashSet<Int>()
                val merged = HashMap<Int, AssistantSmsMessage>()
                val loose = ArrayList<AssistantSmsMessage>()

                fun echoRow(local: LocalSmsEntity) = AssistantSmsMessage(
                    id = local.id,
                    address = local.address,
                    body = local.body,
                    date = local.date,
                    type = 2,
                    simSlot = local.simSlot,
                    status = local.status,
                    isLocal = true
                )

                for (local in echoes) {
                    if (local.threadId < 0) {
                        loose += echoRow(local)
                        continue
                    }
                    val candidates = messages.indices.filter { index ->
                        index !in consumed &&
                            messages[index].type in OUTGOING_SMS_TYPES &&
                            messages[index].date >= local.date - DEDUP_SKEW_MS &&
                            messages[index].date <= local.date + DEDUP_WINDOW_MS
                    }
                    if (candidates.isEmpty()) {
                        loose += echoRow(local)
                        continue
                    }
                    val normalized = normalizeForDedup(local.body)
                    val exact = candidates
                        .filter { normalizeForDedup(messages[it].body) == normalized }
                        .minByOrNull { abs(messages[it].date - local.date) }
                    if (exact != null) {
                        consumed += exact
                        merged[exact] = messages[exact].copy(
                            id = local.id,
                            type = 2,
                            status = local.status,
                            isLocal = true
                        )
                        continue
                    }
                    val accumulator = StringBuilder()
                    val run = mutableListOf<Int>()
                    var multipartHit = false
                    for (index in candidates.sortedBy { messages[it].date }) {
                        if (accumulator.length > local.body.length) break
                        accumulator.append(messages[index].body)
                        run += index
                        if (normalizeForDedup(accumulator.toString()) == normalized) {
                            multipartHit = true
                            break
                        }
                    }
                    if (multipartHit) {
                        consumed += run
                        val head = run.first()
                        merged[head] = messages[head].copy(
                            id = local.id,
                            body = local.body,
                            type = 2,
                            status = local.status,
                            isLocal = true
                        )
                        continue
                    }
                    if (candidates.size == 1) {
                        val only = candidates[0]
                        val other = normalizeForDedup(messages[only].body)
                        if (other.isNotEmpty() && normalized.isNotEmpty() &&
                            (other.contains(normalized) || normalized.contains(other))
                        ) {
                            consumed += only
                            merged[only] = messages[only].copy(
                                id = local.id,
                                type = 2,
                                status = local.status,
                                isLocal = true
                            )
                            continue
                        }
                    }
                    loose += echoRow(local)
                }
                val rebuilt = ArrayList<AssistantSmsMessage>(messages.size + loose.size)
                for (index in messages.indices) {
                    if (index in consumed && !merged.containsKey(index)) continue
                    rebuilt += merged[index] ?: messages[index]
                }
                rebuilt += loose
                messages.clear()
                messages.addAll(rebuilt)
                messages.sortBy { it.date }
            } catch (e: Exception) {
                return@withContext AssistantSmsResult(messages, "Couldn't read messages: ${e.message}")
            }
            AssistantSmsResult(messages)
        }

    suspend fun send(
        context: Context,
        threadId: Long,
        address: String,
        body: String,
        simSlot: Int = 0
    ): String? = withContext(Dispatchers.IO) {
        val number = address.trim()
        val text = body.trim()
        if (number.isBlank() || text.isBlank()) return@withContext "Enter a number and message"
        if (!hasSendSms(context)) return@withContext "Send SMS permission not granted"
        val repo = repository(context)
        val localId = repo.insertLocalSms(
            LocalSmsEntity(
                threadId = threadId,
                address = number,
                body = text,
                simSlot = simSlot,
                status = "SENDING"
            )
        )
        try {
            val manager = smsManager(context, simSlot)
            val parts = manager.divideMessage(text)
            val delivered = deliveredPendingIntent(context, localId)
            if (parts.size > 1) {
                val sent = ArrayList<PendingIntent>(parts.size)
                val delivery = ArrayList<PendingIntent>(parts.size)
                parts.indices.forEach { index ->
                    sent += sentPendingIntent(context, localId, index)
                    delivery += delivered
                }
                manager.sendMultipartTextMessage(number, null, parts, sent, delivery)
            } else {
                manager.sendTextMessage(number, null, text, sentPendingIntent(context, localId, 0), delivered)
            }
            adoptRealThreadIdAfterSend(context, repo, threadId, number)
            null
        } catch (e: Exception) {
            repo.updateLocalSmsStatus(localId, "FAILED")
            "Failed to send: ${e.message}"
        }
    }

    suspend fun resolveThreadIdForAddress(context: Context, address: String): Long = withContext(Dispatchers.IO) {
        val number = address.trim()
        if (number.isBlank()) return@withContext -1L
        val exact = queryProviderThreadId(context, "address = ?", arrayOf(number))
        if (exact >= 0) return@withContext exact
        val tail = significantTail(number)
        if (tail.length >= 7) {
            val loose = queryProviderThreadId(context, "address LIKE ?", arrayOf("%$tail"))
            if (loose >= 0) return@withContext loose
        }
        syntheticThreadId(number)
    }

    suspend fun markThreadRead(context: Context, threadId: Long) = withContext(Dispatchers.IO) {
        if (threadId < 0) return@withContext
        runCatching {
            context.contentResolver.update(
                Uri.parse("content://sms"),
                ContentValues().apply { put("read", 1) },
                "thread_id = ? AND read = 0",
                arrayOf(threadId.toString())
            )
        }
    }

    fun statusForResult(action: String?, resultCode: Int): String = when {
        action == SMS_DELIVERED_ACTION && resultCode == android.app.Activity.RESULT_OK -> "DELIVERED"
        resultCode == android.app.Activity.RESULT_OK -> "SENT"
        else -> "FAILED"
    }

    fun smsResultText(resultCode: Int): String = when (resultCode) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
        else -> "error ($resultCode)"
    }

    fun statusLabel(status: String?): String = when (status) {
        "SENDING" -> "Sending…"
        "SENT" -> "✓ Sent"
        "DELIVERED" -> "✓✓ Delivered"
        "FAILED" -> "Failed"
        else -> ""
    }

    fun segments(context: Context, body: String): Int =
        runCatching { smsManager(context, 0).divideMessage(body).size }.getOrDefault(0)

    fun formatDate(timestamp: Long): String {
        val format = if (System.currentTimeMillis() - timestamp < 24 * 3600 * 1000L) "HH:mm" else "MMM d"
        return SimpleDateFormat(format, Locale.getDefault()).format(Date(timestamp))
    }

    fun formatTime(timestamp: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    fun displayName(contactNames: Map<String, String>, number: String): String {
        if (number.isBlank()) return "(Unknown)"
        return contactNames[normalizeNumber(number)] ?: number
    }

    fun contactNameMap(context: Context): Map<String, String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return emptyMap()
        return runCatching {
            DeviceContacts.query(context)
                .filter { it.second.isNotBlank() }
                .associate { normalizeNumber(it.second) to it.first }
        }.getOrDefault(emptyMap())
    }

    private fun Cursor.subIdColumn(): Int =
        if (getColumnIndex("sub_id") >= 0) getColumnIndex("sub_id")
        else getColumnIndex("subscription_id")

    private suspend fun adoptRealThreadIdAfterSend(
        context: Context,
        repository: AppRepository,
        syntheticId: Long,
        address: String
    ): Long {
        if (syntheticId >= 0) return syntheticId
        val tail = significantTail(address)
        repeat(6) {
            val real = if (tail.length >= 7) {
                queryProviderThreadId(context, "address LIKE ?", arrayOf("%$tail"))
            } else {
                queryProviderThreadId(context, "address = ?", arrayOf(address))
            }
            if (real >= 0) {
                repository.migrateLocalSmsThread(syntheticId, real)
                return real
            }
            delay(400)
        }
        return syntheticId
    }

    private fun queryProviderThreadId(context: Context, selection: String, args: Array<String>): Long =
        runCatching {
            context.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("thread_id"),
                selection,
                args,
                "date DESC"
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L } ?: -1L
        }.getOrDefault(-1L)

    private fun significantTail(number: String, digits: Int = 9): String {
        val digitsOnly = number.filter { it.isDigit() }
        return if (digitsOnly.length > digits) digitsOnly.takeLast(digits) else digitsOnly
    }

    private fun syntheticThreadId(number: String): Long {
        val hash = abs(significantTail(number).hashCode())
        return -(1_000_000_000L + hash)
    }

    private fun sentPendingIntent(context: Context, localId: Long, partIndex: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            localId.toInt() + 100 + partIndex,
            Intent(SMS_SENT_ACTION)
                .setPackage(context.packageName)
                .putExtra(EXTRA_LOCAL_ID, localId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun deliveredPendingIntent(context: Context, localId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            localId.toInt() + 1,
            Intent(SMS_DELIVERED_ACTION)
                .setPackage(context.packageName)
                .putExtra(EXTRA_LOCAL_ID, localId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun normalizeNumber(number: String): String =
        ContactNumbers.normalize(number) ?: number.filter { it.isDigit() }.takeLast(10)

    private fun normalizeForDedup(body: String): String =
        body.trim().filterNot { it.isWhitespace() }.lowercase(Locale.getDefault())

    private fun subIdToSlot(context: Context, subId: Long): Int {
        if (subId <= 0) return 0
        return runCatching {
            val manager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            manager?.activeSubscriptionInfoList
                ?.firstOrNull { it.subscriptionId.toLong() == subId }
                ?.let { it.simSlotIndex + 1 } ?: 0
        }.getOrDefault(0)
    }

    private fun defaultSmsSubId(context: Context): Int = runCatching {
        val manager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        var id = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (id == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            id = SubscriptionManager.getDefaultDataSubscriptionId()
        }
        if (id == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            id = manager?.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId
                ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        id
    }.getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)

    private fun slotToSubId(context: Context, slot: Int): Int {
        if (slot <= 0) return defaultSmsSubId(context)
        return runCatching {
            val manager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            manager?.activeSubscriptionInfoList
                ?.firstOrNull { it.simSlotIndex == slot - 1 }
                ?.subscriptionId ?: defaultSmsSubId(context)
        }.getOrDefault(defaultSmsSubId(context))
    }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context, slot: Int): SmsManager {
        val subId = slotToSubId(context, slot)
        return if (subId > 0) {
            SmsManager.getSmsManagerForSubscriptionId(subId)
        } else {
            context.getSystemService(SmsManager::class.java)
        }
    }

    private fun readDeletedConversations(context: Context): Map<String, Long> {
        val raw = context.getSharedPreferences("sms", Context.MODE_PRIVATE)
            .getStringSet("deleted_conversations", null) ?: return emptyMap()
        val result = HashMap<String, Long>(raw.size)
        raw.forEach { entry ->
            val separator = entry.indexOf('|')
            if (separator > 0) {
                val timestamp = entry.substring(0, separator).toLongOrNull() ?: return@forEach
                result[entry.substring(separator + 1)] = timestamp
            }
        }
        return result
    }

    private fun clearDeletedConversations(context: Context, keys: Set<String>) {
        if (keys.isEmpty()) return
        val prefs = context.getSharedPreferences("sms", Context.MODE_PRIVATE)
        val current = prefs.getStringSet("deleted_conversations", null)?.toMutableSet() ?: mutableSetOf()
        if (current.removeAll { it.substringAfter('|') in keys }) {
            prefs.edit().putStringSet("deleted_conversations", current).apply()
        }
    }
}
