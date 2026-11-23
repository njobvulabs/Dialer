package com.example.ui.screens.webapp

import android.content.Context
import com.example.ui.components.AppIcons
import com.example.ui.screens.apps.AppItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * A user-created web app (the NJ-Dialer "Web App Creator"). Persisted in the
 * `custom_webapps` prefs file as a JSON array.
 *
 * The app is hosted by one of the fixed isolated Gecko web-app slots
 * (`customweb1`..`customweb4`), each of which runs in its own OS process with its
 * own Gecko profile directory (see `GeckoWebAppActivity1..4` + `WebAppEngine`).
 */
data class CustomWebApp(
    val id: String,
    val name: String,
    val url: String,
    val desktop: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long
)

object CustomWebApps {
    const val SLOT_COUNT = 4
    const val ID_PREFIX = "customweb"

    private const val PREFS_NAME = "custom_webapps"
    private const val KEY_LIST = "list"

    fun slotId(slot: Int): String = "${ID_PREFIX}${slot}"

    fun slotIndex(id: String): Int? =
        id.removePrefix(ID_PREFIX).toIntOrNull()?.takeIf { it in 1..SLOT_COUNT }

    fun isSlotId(id: String): Boolean = slotIndex(id) != null

    /** Gecko profile directory name for a slot (inside filesDir). */
    fun profileDirName(slot: Int): String = "custom_webapp_$slot"

    fun profileDirFor(appId: String): String? = slotIndex(appId)?.let { profileDirName(it) }

    fun list(context: Context): List<CustomWebApp> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LIST, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            val out = mutableListOf<CustomWebApp>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                out.add(
                    CustomWebApp(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        url = obj.optString("url"),
                        desktop = obj.optBoolean("desktop", true),
                        createdAt = obj.optLong("createdAt", 0L),
                        updatedAt = obj.optLong("updatedAt", 0L)
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, app: CustomWebApp) {
        val remaining = list(context).filterNot { it.id == app.id } + app
        val arr = JSONArray(remaining.map { o ->
            JSONObject().apply {
                put("id", o.id)
                put("name", o.name)
                put("url", o.url)
                put("desktop", o.desktop)
                put("createdAt", o.createdAt)
                put("updatedAt", o.updatedAt)
            }
        })
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIST, arr.toString())
            .apply()
    }

    fun delete(context: Context, appId: String) {
        val remaining = list(context).filterNot { it.id == appId }
        val arr = JSONArray(remaining.map { o ->
            JSONObject().apply {
                put("id", o.id)
                put("name", o.name)
                put("url", o.url)
                put("desktop", o.desktop)
                put("createdAt", o.createdAt)
                put("updatedAt", o.updatedAt)
            }
        })
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIST, arr.toString())
            .apply()
    }

    fun forId(context: Context, appId: String): CustomWebApp? = list(context).firstOrNull { it.id == appId }

    fun clearProfile(context: Context, appId: String): Boolean {
        val dirName = profileDirFor(appId) ?: return false
        return java.io.File(context.filesDir, dirName).deleteRecursively()
    }

    /** Normalize a user-entered URL: add https:// when no scheme, require http(s). */
    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
        return runCatching { android.net.Uri.parse(withScheme) }.getOrNull()
            ?.takeIf { it.scheme == "http" || it.scheme == "https" }
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun toWebAppModule(app: CustomWebApp): WebAppModule = WebAppModule(
        id = app.id,
        url = app.url,
        title = app.name,
        desktop = app.desktop
    )

    fun toAppItem(app: CustomWebApp): AppItem = AppItem(
        id = app.id,
        title = app.name,
        icon = AppIcons.Language,
        description = app.url,
        keywords = "web,creator,add,app",
        section = "Web apps"
    )
}
