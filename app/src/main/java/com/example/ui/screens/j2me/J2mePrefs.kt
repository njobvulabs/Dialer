package com.example.ui.screens.j2me

import android.content.Context
import org.json.JSONObject

object J2mePrefs {
    private const val PREFS = "j2me"
    private const val KEY_LAST_PLAYED = "last_played"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastPlayed(context: Context, appId: String): Long {
        val json = JSONObject(prefs(context).getString(KEY_LAST_PLAYED, "{}") ?: "{}")
        return json.optLong(appId, 0L)
    }

    fun touch(context: Context, appId: String) {
        val json = JSONObject(prefs(context).getString(KEY_LAST_PLAYED, "{}") ?: "{}")
        json.put(appId, System.currentTimeMillis())
        prefs(context).edit().putString(KEY_LAST_PLAYED, json.toString()).apply()
    }

    fun forget(context: Context, appId: String) {
        val json = JSONObject(prefs(context).getString(KEY_LAST_PLAYED, "{}") ?: "{}")
        json.remove(appId)
        prefs(context).edit().putString(KEY_LAST_PLAYED, json.toString()).apply()
    }
}
