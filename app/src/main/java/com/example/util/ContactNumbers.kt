package com.example.util

/**
 * One place that knows how to read the `ContactEntity.phoneNumber` field.
 *
 * A contact's field is **not** always a single number. Device contacts are
 * grouped per person (see `DeviceContacts.queryGrouped`) and carry every one of
 * that person's numbers joined into one comma-separated string; `ContactTools`
 * has written the same convention for a long time (VCF import, duplicate
 * merge), and backups round-trip it verbatim. So the field is a *list* and every
 * caller that compares or dials it has to go through here.
 *
 * The rule that got this wrong before: normalising the **whole field** with
 * `filter { isDigit() }.takeLast(10)` silently collapses `"099111, 088222"` into
 * a key built from both numbers at once, which only ever matches the person's
 * **last** number. Split first, then normalise.
 */
object ContactNumbers {

    /**
     * Last-10-digit key used to recognise the same number written in different
     * formats (`+265 991 234 567` / `0991234567` / `265991234567`).
     * Returns null for anything too short to be a real number.
     */
    fun normalize(raw: String): String? {

        val digits = raw.filter { it.isDigit() }
        if (digits.length < 3) return null
        return digits.takeLast(10)
    }

    /**
     * Every number in the field, in stored order, trimmed, with blanks dropped
     * and duplicates removed. Split on both separators: `ContactTools.mergeGroup`
     * writes `", "` but older rows and some VCF exports use `";"`.
     */
    fun list(phoneNumber: String): List<String> {
        if (phoneNumber.isBlank()) return emptyList()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<String>()
        for (part in phoneNumber.split(',', ';')) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val key = normalize(trimmed) ?: continue
            if (seen.add(key)) out.add(trimmed)
        }
        return out
    }

    /** Every [normalize] key in the field — the form used for lookups. */
    fun keys(phoneNumber: String): List<String> =
        list(phoneNumber).mapNotNull { normalize(it) }.distinct()

    /** True when any number in [phoneNumber] matches [raw]. */
    fun contains(phoneNumber: String, raw: String): Boolean {
        val key = normalize(raw) ?: return false
        return keys(phoneNumber).contains(key)
    }

    /**
     * The number to use when the UI has no specific one in hand (an old single
     * value, a speed-dial chip, a call log row). Falls back to the raw trimmed
     * field so a malformed value still reaches the dialer as typed rather than
     * becoming an empty string.
     */
    fun primary(phoneNumber: String): String =
        list(phoneNumber).firstOrNull() ?: phoneNumber.trim()

    /** Short label for a row that shows one number plus a count of the rest. */
    fun display(phoneNumber: String): String {
        val all = list(phoneNumber)
        if (all.isEmpty()) return phoneNumber.trim()
        if (all.size == 1) return all.first()
        return "${all.first()}  +${all.size - 1} more"
    }
}
