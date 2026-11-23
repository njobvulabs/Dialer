package com.example.util

import android.content.Context

/**
 * Shared setting for opting into an idle expiry on the kept-alive services.
 *
 * Kept in its own file (rather than app_settings) so a service running in a
 * private web-app process reads a value that was not already cached by the
 * theme/preference readers in that process. 0 = never stop.
 */
object KeepAliveTimeout {
    const val PREFS_NAME = "keep_alive_config"
    const val KEY_MINUTES = "timeout_min"

    val OPTIONS = listOf(0, 15, 30, 60, 120)

    fun normalize(minutes: Int): Int = if (minutes in OPTIONS) minutes else 0

    fun read(context: Context): Int = normalize(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MINUTES, 0)
    )

    fun write(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MINUTES, normalize(minutes)).apply()
    }

    fun millis(context: Context): Long = read(context) * 60_000L

    fun label(minutes: Int): String = if (minutes <= 0) "Never" else "$minutes min"
}
