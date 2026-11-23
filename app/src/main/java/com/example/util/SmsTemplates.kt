package com.example.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object SmsTemplates {
    private const val PREFS = "app_settings"
    private const val KEY = "sms_quick_templates_json"
    private const val MAX = 20

    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotEmpty()) add(s)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, templates: List<String>) {
        val arr = JSONArray()
        templates.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX).forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return load(context)
        val next = (listOf(t) + load(context)).distinct().take(MAX)
        save(context, next)
        return next
    }

    fun remove(context: Context, text: String): List<String> {
        val next = load(context).filterNot { it == text }
        save(context, next)
        return next
    }
}
