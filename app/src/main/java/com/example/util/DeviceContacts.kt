package com.example.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

object DeviceContacts {

    /**
     * One device contact with **all** of their phone numbers.
     *
     * [query] deliberately stays one-row-per-number: it backs the "is this number
     * in my contacts?" checks and the contact picker, where one row per number is
     * the useful shape. The Contacts *list* wants the opposite — a person with
     * four numbers must be ONE row — and it needs [queryGrouped] for that.
     */
    data class DeviceContact(
        val name: String,
        val lookupKey: String,
        val numbers: List<String>
    )

    /** Reads the system address book and returns (name, phoneNumber) pairs. */
    fun query(context: Context): List<Pair<String, String>> {

        return queryRows(context).map { it.first to it.second }
    }

    /**
     * Reads the system address book **grouped per person**.
     *
     * `Phone.CONTENT_URI` is one row per phone number, so a person with several
     * numbers arrives as several rows. Grouping is done on CONTACT_ID (with
     * LOOKUP_KEY as the fallback), which is the platform's own "these rows are the
     * same person" key — NOT on the display name, since two different people
     * legitimately share a name and merging them would lose a contact.
     */
    fun queryGrouped(context: Context): List<DeviceContact> {
        val rows = queryRows(context)
        val grouped = LinkedHashMap<String, DeviceContact>()
        val numbersByKey = mutableMapOf<String, LinkedHashSet<String>>()
        for ((name, number, key) in rows) {
            val groupKey = key.ifBlank { "$name|$number" }
            if (!grouped.containsKey(groupKey)) {
                grouped[groupKey] = DeviceContact(name, key, emptyList())
                numbersByKey[groupKey] = LinkedHashSet()
            }
            numbersByKey[groupKey]?.add(number)
        }
        return grouped.map { (key, contact) ->
            contact.copy(numbers = numbersByKey[key].orEmpty().toList())
        }.sortedBy { it.name.lowercase() }
    }

    private fun queryRows(context: Context): List<Triple<String, String, String>> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val result = mutableListOf<Triple<String, String, String>>()
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY
            )
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
            cursor?.use { c ->
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val contactIdIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val lookupIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
                val seen = mutableSetOf<String>()
                while (c.moveToNext()) {
                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                    val number = if (numberIdx >= 0) c.getString(numberIdx) ?: "" else ""
                    if (number.isBlank()) continue
                    val digits = number.filter { it.isDigit() }.takeLast(10)
                    if (digits.length < 3 || !seen.add(digits)) continue
                    val contactId = if (contactIdIdx >= 0) c.getLong(contactIdIdx) else 0L
                    val lookupKey = if (lookupIdx >= 0) c.getString(lookupIdx) ?: "" else ""
                    // CONTACT_ID is the stable "same person" key. LOOKUP_KEY is
                    // preferred when present because it survives a merge of two
                    // device contacts; otherwise fall back to the raw id.
                    val key = lookupKey.ifBlank { contactId.toString() }
                    result.add(
                        Triple(
                            name.trim().ifEmpty { "Unknown" },
                            number.trim(),
                            key
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return result
    }
}
