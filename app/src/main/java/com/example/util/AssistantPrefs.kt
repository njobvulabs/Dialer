package com.example.util

import android.content.Context
import org.json.JSONArray

object AssistantPrefs {
    const val PREF = "assistant"
    const val OVERLAY_X = "overlay_x"
    const val OVERLAY_Y = "overlay_y"
    const val PANEL_OVERLAY_X = "panel_overlay_x"
    const val PANEL_OVERLAY_Y = "panel_overlay_y"
    const val EXPANDED = "expanded"
    const val PINNED_ACTIONS = "pinned_actions"
    const val SHOW_FAVOURITES = "show_favourites"
    const val SHOW_RECENT = "show_recent"
    const val BUBBLE_SIZE = "bubble_size"
    const val BUBBLE_OPACITY = "bubble_opacity"
    const val BUBBLE_OPACITY_MIGRATED = "bubble_opacity_migrated"
    const val BUBBLE_IDLE_FADE = "bubble_idle_fade"
    const val BUBBLE_IDLE_DELAY = "bubble_idle_delay"
    const val BUBBLE_IDLE_OPACITY = "bubble_idle_opacity"
    const val PANEL_SIZE = "panel_size"
    const val PANEL_OPACITY = "panel_opacity"
    const val DEFAULT_TAB = "default_tab"
    const val DEFAULT_TAB_MIGRATED = "default_tab_migrated"
    const val SNAP_TO_EDGE = "snap_to_edge"
    const val COLLAPSE_ON_CALL = "collapse_on_call"
    const val COLLAPSE_ON_SCREEN_OFF = "collapse_on_screen_off"

    const val ACTION_MODE_HIDDEN = 0
    const val ACTION_MODE_FLOATING = 1
    const val ACTION_MODE_FULL = 2

    private const val CLIPBOARD_SNIPPETS = "clipboard_snippets"
    private const val ACTION_MODE_PREFIX = "action_mode_"
    private const val MAX_SNIPPETS = 10
    private const val DEFAULT_BUBBLE_OPACITY = 0.72f
    private const val DEFAULT_IDLE_OPACITY = 0.38f

    val QUICK_ACTION_IDS = listOf(
        "assistant_torch", "calculator", "assistant_camera", "screenrecorder", "data_connection",
        "sms", "contacts", "clipboard"
    )

    fun get(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun expanded(context: Context): Boolean = get(context).getBoolean(EXPANDED, false)

    fun setExpanded(context: Context, value: Boolean) {
        get(context).edit().putBoolean(EXPANDED, value).apply()
    }

    fun overlayX(context: Context, expanded: Boolean): Int {
        val prefs = get(context)
        val fallback = (12f * context.resources.displayMetrics.density).toInt()
        return if (expanded && prefs.contains(PANEL_OVERLAY_X)) {
            prefs.getInt(PANEL_OVERLAY_X, fallback)
        } else {
            prefs.getInt(OVERLAY_X, fallback)
        }
    }

    fun overlayY(context: Context, expanded: Boolean): Int {
        val prefs = get(context)
        val fallback = context.resources.displayMetrics.heightPixels / 5
        return if (expanded && prefs.contains(PANEL_OVERLAY_Y)) {
            prefs.getInt(PANEL_OVERLAY_Y, fallback)
        } else {
            prefs.getInt(OVERLAY_Y, fallback)
        }
    }

    fun saveOverlayPosition(context: Context, expanded: Boolean, x: Int, y: Int) {
        val editor = get(context).edit()
        if (expanded) {
            editor.putInt(PANEL_OVERLAY_X, x).putInt(PANEL_OVERLAY_Y, y)
        } else {
            editor.putInt(OVERLAY_X, x).putInt(OVERLAY_Y, y)
        }
        editor.apply()
    }

    fun bubbleSize(context: Context): Int = get(context).getInt(BUBBLE_SIZE, 0).coerceIn(0, 2)

    fun setBubbleSize(context: Context, value: Int) {
        get(context).edit().putInt(BUBBLE_SIZE, value.coerceIn(0, 2)).apply()
    }

    fun bubbleDp(context: Context): Int = when (bubbleSize(context)) {
        0 -> 44
        1 -> 52
        else -> 60
    }

    fun bubbleOpacity(context: Context): Float {
        val prefs = get(context)
        if (!prefs.getBoolean(BUBBLE_OPACITY_MIGRATED, false)) {
            val stored = prefs.getFloat(BUBBLE_OPACITY, DEFAULT_BUBBLE_OPACITY)
            val migrated = if (stored >= 0.99f) DEFAULT_BUBBLE_OPACITY else stored
            prefs.edit()
                .putFloat(BUBBLE_OPACITY, migrated)
                .putBoolean(BUBBLE_OPACITY_MIGRATED, true)
                .apply()
        }
        return prefs.getFloat(BUBBLE_OPACITY, DEFAULT_BUBBLE_OPACITY).coerceIn(0.35f, 1f)
    }

    fun setBubbleOpacity(context: Context, value: Float) {
        val opacity = value.coerceIn(0.35f, 1f)
        val editor = get(context).edit()
            .putFloat(BUBBLE_OPACITY, opacity)
            .putBoolean(BUBBLE_OPACITY_MIGRATED, true)
        if (bubbleIdleOpacity(context) > opacity) {
            editor.putFloat(BUBBLE_IDLE_OPACITY, opacity)
        }
        editor.apply()
    }

    fun bubbleIdleFade(context: Context): Boolean =
        get(context).getBoolean(BUBBLE_IDLE_FADE, true)

    fun setBubbleIdleFade(context: Context, value: Boolean) {
        get(context).edit().putBoolean(BUBBLE_IDLE_FADE, value).apply()
    }

    fun bubbleIdleDelayMs(context: Context): Long =
        get(context).getLong(BUBBLE_IDLE_DELAY, 8000L).coerceIn(3000L, 30000L)

    fun setBubbleIdleDelayMs(context: Context, value: Long) {
        get(context).edit().putLong(BUBBLE_IDLE_DELAY, value.coerceIn(3000L, 30000L)).apply()
    }

    fun bubbleIdleOpacity(context: Context): Float =
        get(context).getFloat(BUBBLE_IDLE_OPACITY, DEFAULT_IDLE_OPACITY).coerceIn(0.15f, bubbleOpacity(context))

    fun setBubbleIdleOpacity(context: Context, value: Float) {
        get(context).edit()
            .putFloat(BUBBLE_IDLE_OPACITY, value.coerceIn(0.15f, bubbleOpacity(context)))
            .apply()
    }

    fun panelSize(context: Context): Int = get(context).getInt(PANEL_SIZE, 1).coerceIn(0, 2)

    fun setPanelSize(context: Context, value: Int) {
        get(context).edit().putInt(PANEL_SIZE, value.coerceIn(0, 2)).apply()
    }

    fun panelDp(context: Context): Pair<Int, Int> = when (panelSize(context)) {
        0 -> 320 to 480
        1 -> 360 to 560
        else -> 400 to 620
    }

    fun panelOpacity(context: Context): Float =
        get(context).getFloat(PANEL_OPACITY, 0.97f).coerceIn(0.6f, 1f)

    fun setPanelOpacity(context: Context, value: Float) {
        get(context).edit().putFloat(PANEL_OPACITY, value.coerceIn(0.6f, 1f)).apply()
    }

    fun defaultTab(context: Context): Int {
        val prefs = get(context)
        if (!prefs.getBoolean(DEFAULT_TAB_MIGRATED, false)) {
            val stored = prefs.getInt(DEFAULT_TAB, 0)
            val migrated = if (stored == 1) 2 else stored
            prefs.edit()
                .putInt(DEFAULT_TAB, migrated)
                .putBoolean(DEFAULT_TAB_MIGRATED, true)
                .apply()
            return migrated.coerceIn(0, 3)
        }
        return prefs.getInt(DEFAULT_TAB, 0).coerceIn(0, 3)
    }

    fun setDefaultTab(context: Context, value: Int) {
        get(context).edit()
            .putInt(DEFAULT_TAB, value.coerceIn(0, 3))
            .putBoolean(DEFAULT_TAB_MIGRATED, true)
            .apply()
    }

    fun snapToEdge(context: Context): Boolean = get(context).getBoolean(SNAP_TO_EDGE, true)

    fun setSnapToEdge(context: Context, value: Boolean) {
        get(context).edit().putBoolean(SNAP_TO_EDGE, value).apply()
    }

    fun collapseOnCall(context: Context): Boolean = get(context).getBoolean(COLLAPSE_ON_CALL, true)

    fun setCollapseOnCall(context: Context, value: Boolean) {
        get(context).edit().putBoolean(COLLAPSE_ON_CALL, value).apply()
    }

    fun collapseOnScreenOff(context: Context): Boolean =
        get(context).getBoolean(COLLAPSE_ON_SCREEN_OFF, true)

    fun setCollapseOnScreenOff(context: Context, value: Boolean) {
        get(context).edit().putBoolean(COLLAPSE_ON_SCREEN_OFF, value).apply()
    }

    fun showFavourites(context: Context): Boolean = get(context).getBoolean(SHOW_FAVOURITES, true)

    fun setShowFavourites(context: Context, value: Boolean) {
        get(context).edit().putBoolean(SHOW_FAVOURITES, value).apply()
    }

    fun showRecent(context: Context): Boolean = get(context).getBoolean(SHOW_RECENT, true)

    fun setShowRecent(context: Context, value: Boolean) {
        get(context).edit().putBoolean(SHOW_RECENT, value).apply()
    }

    fun actionMode(context: Context, id: String): Int {
        val fallback = if (id in QUICK_ACTION_IDS) ACTION_MODE_FLOATING else ACTION_MODE_FULL
        val stored = get(context).getInt(ACTION_MODE_PREFIX + id, fallback).coerceIn(0, 2)
        return if (id in QUICK_ACTION_IDS && stored == ACTION_MODE_FULL) ACTION_MODE_FLOATING else stored
    }

    fun setActionMode(context: Context, id: String, value: Int) {
        get(context).edit().putInt(ACTION_MODE_PREFIX + id, value.coerceIn(0, 2)).apply()
    }

    fun resetLayout(context: Context) {
        get(context).edit()
            .remove(OVERLAY_X)
            .remove(OVERLAY_Y)
            .remove(PANEL_OVERLAY_X)
            .remove(PANEL_OVERLAY_Y)
            .putBoolean(EXPANDED, false)
            .putInt(BUBBLE_SIZE, 0)
            .putFloat(BUBBLE_OPACITY, DEFAULT_BUBBLE_OPACITY)
            .putBoolean(BUBBLE_OPACITY_MIGRATED, true)
            .putBoolean(BUBBLE_IDLE_FADE, true)
            .putLong(BUBBLE_IDLE_DELAY, 8000L)
            .putFloat(BUBBLE_IDLE_OPACITY, DEFAULT_IDLE_OPACITY)
            .putInt(PANEL_SIZE, 1)
            .putFloat(PANEL_OPACITY, 0.97f)
            .putInt(DEFAULT_TAB, 0)
            .putBoolean(DEFAULT_TAB_MIGRATED, true)
            .putBoolean(SNAP_TO_EDGE, true)
            .putBoolean(SHOW_FAVOURITES, true)
            .putBoolean(SHOW_RECENT, true)
            .apply()
        QUICK_ACTION_IDS.forEach { removeActionMode(context, it) }
    }

    fun removeActionMode(context: Context, id: String) {
        get(context).edit().remove(ACTION_MODE_PREFIX + id).apply()
    }

    fun snippets(context: Context): List<String> {
        val raw = get(context).getString(CLIPBOARD_SNIPPETS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length())
                .map { array.optString(it) }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_SNIPPETS)
        }.getOrDefault(emptyList())
    }

    fun addSnippet(context: Context, text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        val updated = (listOf(value) + snippets(context).filterNot {
            it.equals(value, ignoreCase = true)
        }).distinct().take(MAX_SNIPPETS)
        runCatching {
            val array = JSONArray()
            updated.forEach { array.put(it) }
            get(context).edit().putString(CLIPBOARD_SNIPPETS, array.toString()).apply()
        }
    }

    fun clearSnippets(context: Context) {
        get(context).edit().remove(CLIPBOARD_SNIPPETS).apply()
    }
}
