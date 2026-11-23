package com.example.util

import android.content.Context
import com.example.data.repository.AppRepository
import com.example.ui.components.AppIcons

sealed class ScreeningDecision {
    /** Let the call ring normally. */
    object Allow : ScreeningDecision()
    /** Reject the call outright. */
    data class Block(val reason: String) : ScreeningDecision()
    /** Let it ring but silence the ringer (first-time caller). */
    object Silence : ScreeningDecision()
}

/**
 * Extended incoming-call rules evaluated by CustomInCallService:
 * blocked list, unknown callers, private/hidden numbers, international
 * numbers, blocked prefixes and first-time-caller silent mode.
 */
object CallScreening {

    const val PREF_BLOCK_PRIVATE = "block_private_callers"
    const val PREF_BLOCK_INTERNATIONAL = "block_international"
    const val PREF_SILENT_FIRST_TIME = "silent_first_time"
    const val PREF_PREFIXES = "block_prefixes"
    const val PREF_LOCAL_CC = "local_country_code"

    fun blockedPrefixes(context: Context): Set<String> =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getStringSet(PREF_PREFIXES, emptySet()) ?: emptySet()

    fun setBlockedPrefixes(context: Context, prefixes: Set<String>) {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putStringSet(PREF_PREFIXES, prefixes).apply()
    }

    fun localCountryCode(context: Context): String =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getString(PREF_LOCAL_CC, "265") ?: "265"

    fun isPrivateNumber(number: String): Boolean {
        val n = number.trim().lowercase()
        return n.isEmpty() || n == "-" || n == "-1" || n == "unknown" ||
            n == "private" || n == "restricted" || n == "hidden" || n == "null" || n == "anonymous"
    }

    fun isInternational(number: String, localCc: String): Boolean {
        if (!number.startsWith("+")) return false
        return !number.removePrefix("+").startsWith(localCc)
    }

    fun matchesPrefix(number: String, prefixes: Set<String>): Boolean {
        val digits = number.filter { it.isDigit() }
        return prefixes.any { p ->
            val pd = p.filter { it.isDigit() }
            pd.isNotEmpty() && digits.startsWith(pd)
        }
    }

    suspend fun isFirstTimeCaller(repository: AppRepository, number: String): Boolean {
        val digits = number.filter { it.isDigit() }.takeLast(10)
        if (digits.length < 5) return false
        val inLogs = repository.getCallLogsOnce().any {
            it.number.filter { c -> c.isDigit() }.takeLast(10) == digits
        }
        if (inLogs) return false
        return true
    }

    /**
     * Full evaluation for an incoming call. [isKnownContact] should reflect the
     * user's saved contacts (in-app DB + device contacts).
     */
    suspend fun evaluate(
        context: Context,
        number: String,
        repository: AppRepository,
        isKnownContact: Boolean
    ): ScreeningDecision {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        if (repository.isNumberBlocked(number)) return ScreeningDecision.Block("Blocked number")

        if (prefs.getBoolean("block_unknown_callers", false) && !isKnownContact) {
            return ScreeningDecision.Block("Unknown caller")
        }

        if (prefs.getBoolean(PREF_BLOCK_PRIVATE, false) && isPrivateNumber(number)) {
            return ScreeningDecision.Block("Private number")
        }

        if (prefs.getBoolean(PREF_BLOCK_INTERNATIONAL, false) &&
            !isPrivateNumber(number) &&
            isInternational(number, localCountryCode(context))
        ) {
            return ScreeningDecision.Block("International number")
        }

        val prefixes = blockedPrefixes(context)
        if (prefixes.isNotEmpty() && matchesPrefix(number, prefixes)) {
            return ScreeningDecision.Block("Blocked prefix")
        }

        if (prefs.getBoolean(PREF_SILENT_FIRST_TIME, false) && !isKnownContact &&
            isFirstTimeCaller(repository, number)
        ) {
            return ScreeningDecision.Silence
        }

        return ScreeningDecision.Allow
    }
}
