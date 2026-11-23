package com.example.util

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import com.example.data.db.CallLogEntity
import com.example.data.db.CallType
import com.example.data.db.ContactEntity
import com.example.data.repository.AppRepository
import com.example.ui.components.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Offline contact & call-log tools: duplicate scan/merge and file backup/restore. */
object ContactTools {

    // ------------------------------------------------------------ duplicates

    data class DuplicateGroup(val primary: ContactEntity, val duplicates: List<ContactEntity>)

    fun normalize(number: String): String = ContactNumbers.normalize(number) ?: ""

    suspend fun findDuplicates(repository: AppRepository): List<DuplicateGroup> {
        val contacts = repository.getContactsOnce()
        // Expand each contact into (contact, numberKey) pairs before grouping.
        // Keying on the whole `phoneNumber` field only ever saw a person's LAST
        // number, so two records that shared any other number were never flagged.
        val byNumber = contacts
            .flatMap { contact ->
                ContactNumbers.keys(contact.phoneNumber)
                    .filter { it.length >= 7 }
                    .map { it to contact }
            }
            .groupBy({ it.first }, { it.second })
            .values
            .filter { group -> group.map { it.id }.distinct().size > 1 }
        val groupsById = mutableMapOf<Long, MutableList<ContactEntity>>()
        for (group in byNumber) {
            for (contact in group.distinctBy { it.id }) {
                groupsById.getOrPut(contact.id) { mutableListOf() }.add(contact)
            }
        }
        val groupedIds = groupsById.keys.toSet()
        // One group per pair, keyed on its lowest id so the same two people aren't
        // offered twice when they share more than one number.
        val numberGroups = groupsById.values
            .filter { it.size > 1 }
            .distinctBy { g -> g.map { it.id }.sorted() }
            .map { g ->
                // The richest record leads the group so merging keeps its fields.
                val primary = g.maxBy { ContactNumbers.list(it.phoneNumber).size }
                DuplicateGroup(primary, g.filter { it.id != primary.id })
            }
        val byName = contacts.filter { it.id !in groupedIds }
            .groupBy { it.name.trim().lowercase() }
            .filter { it.value.size > 1 }
        return (numberGroups + byName.values.map { group ->
            DuplicateGroup(group.first(), group.drop(1))
        })
    }

    /** Merges [duplicates] into [primary]: combines numbers, keeps richest fields. */
    suspend fun mergeGroup(repository: AppRepository, primary: ContactEntity, duplicates: List<ContactEntity>) {
        // Flatten every number first, then dedupe on the normalised key so
        // "+265 991 234 567" and "0991234567" collapse into one entry instead of
        // being stored twice as distinct raw strings.
        val seen = mutableSetOf<String>()
        val mergedNumbers = (listOf(primary.phoneNumber) + duplicates.map { it.phoneNumber })
            .flatMap { ContactNumbers.list(it) }
            .filter { number ->
                val key = ContactNumbers.normalize(number)
                key != null && seen.add(key)
            }
        val mergedEmail = listOf(primary.email).plus(duplicates.map { it.email })
            .firstOrNull { it.isNotBlank() } ?: ""
        repository.updateContact(
            primary.copy(
                phoneNumber = mergedNumbers.joinToString(", ").ifBlank { primary.phoneNumber },
                email = mergedEmail
            )
        )
        duplicates.forEach { repository.deleteContact(it) }
    }

    // ------------------------------------------------------- contacts backup

    /** Exports in-app contacts to a simple VCF file. Returns the file or null. */
    suspend fun exportContactsVcf(repository: AppRepository, outFile: File): File? =
        withContext(Dispatchers.IO) {
            try {
                val contacts = repository.getContactsOnce()
                if (contacts.isEmpty()) return@withContext null
                outFile.bufferedWriter().use { w ->
                    contacts.forEach { c ->
                        w.appendLine("BEGIN:VCARD")
                        w.appendLine("VERSION:3.0")
                        w.appendLine("FN:${c.name}")
                        c.phoneNumber.split(',', ';').forEach { n ->
                            w.appendLine("TEL;TYPE=CELL:${n.trim()}")
                        }
                        if (c.email.isNotBlank()) w.appendLine("EMAIL:${c.email}")
                        if (c.isFavorite) w.appendLine("CATEGORIES:Favorites")
                        w.appendLine("END:VCARD")
                    }
                }
                outFile
            } catch (e: Exception) {
                null
            }
        }

    /** Imports contacts from a VCF file. Returns number of imported contacts. */
    suspend fun importContactsVcf(repository: AppRepository, file: File): Int =
        withContext(Dispatchers.IO) {
            var count = 0
            try {
                val existing = mutableSetOf<String>()
                repository.getContactsOnce().forEach {
                    existing.addAll(ContactNumbers.keys(it.phoneNumber))
                }
                var name = ""
                var email = ""
                val tels = mutableListOf<String>()
                suspend fun flush() {
                    if (name.isBlank() && tels.isEmpty()) return
                    // Keep only the numbers that aren't already saved. Dropping the
                    // whole card because ONE of its numbers was a duplicate used to
                    // lose the contact's other, brand-new numbers.
                    val fresh = tels
                        .map { it.trim() }
                        .filter { number ->
                            val key = ContactNumbers.normalize(number)
                            key != null && existing.add(key)
                        }
                    if (fresh.isNotEmpty()) {
                        runCatching {
                            repository.insertContact(
                                ContactEntity(name = name.ifBlank { fresh.first() },
                                    phoneNumber = fresh.joinToString(", "), email = email)
                            )
                        }
                        count++
                    }
                    name = ""; email = ""; tels.clear()
                }
                file.useLines { lines ->
                    lines.forEach { raw ->
                        val line = raw.trim()
                        when {
                            line.equals("BEGIN:VCARD", true) -> {}
                            line.startsWith("FN:", true) -> {
                                name = line.substringAfter(':').trim()
                            }
                            line.startsWith("N:", true) && name.isBlank() -> {
                                val parts = line.substringAfter(':').split(';')
                                name = (parts.getOrNull(1)?.trim() ?: "") + " " +
                                    (parts.getOrNull(0)?.trim() ?: "")
                            }
                            line.startsWith("TEL", true) -> {
                                val t = line.substringAfter(':').trim()
                                if (t.isNotEmpty()) tels.add(t)
                            }
                            line.startsWith("EMAIL", true) -> email = line.substringAfter(':').trim()
                            line.startsWith("END:VCARD", true) -> flush()
                        }
                    }
                }
                flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            count
        }

    // ------------------------------------------------------ call log backup

    fun exportCallLogsCsv(logs: List<CallLogEntity>, outFile: File): File? = try {
        outFile.bufferedWriter().use { w ->
            w.appendLine("type,number,name,timestamp,durationSeconds,simSlot")
            logs.forEach { l ->
                w.appendLine(
                    "${l.callType.name},${csv(l.number)},${csv(l.callerName ?: "")}," +
                        "${l.timestamp},${l.durationSeconds},${l.simSlot}"
                )
            }
        }
        outFile
    } catch (e: Exception) {
        null
    }

    suspend fun importCallLogsCsv(repository: AppRepository, file: File): Int =
        withContext(Dispatchers.IO) {
            var count = 0
            try {
                file.useLines { lines ->
                    lines.drop(1).forEach { raw ->
                        val parts = splitCsvLine(raw)
                        if (parts.size >= 6) {
                            val type = runCatching { CallType.valueOf(parts[0]) }.getOrNull() ?: return@forEach
                            runCatching {
                                repository.insertCallLog(
                                    CallLogEntity(
                                        number = parts[1],
                                        callerName = parts[2].ifBlank { null },
                                        callType = type,
                                        timestamp = parts[3].toLongOrNull() ?: System.currentTimeMillis(),
                                        durationSeconds = parts[4].toIntOrNull() ?: 0,
                                        simSlot = parts[5].toIntOrNull() ?: 1
                                    )
                                )
                            }
                            count++
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            count
        }

    private fun csv(v: String): String = "\"${v.replace("\"", "\"\"")}\""

    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        line.forEach { ch ->
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString())
        return out
    }
}
