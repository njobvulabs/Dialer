package com.example.util

import android.content.Context
import android.net.Uri

/**
 * One browser capability a site can ask for. These are the permissions the app
 * actually has an opinion about; anything else Gecko asks for is denied outright
 * (see [permForGeckoType]).
 */
enum class SitePermission(val label: String) {
    GEOLOCATION("Location"),
    NOTIFICATIONS("Notifications"),
    MEDIA("Camera & microphone"),
    STORAGE("Persistent storage");

    companion object {
        /**
         * Map a `GeckoSession.PermissionDelegate.PERMISSION_*` constant onto a
         * capability we store. Returns null for everything else, which the delegate
         * denies — matching the old blanket-deny behaviour for unusual requests.
         */
        fun permForGeckoType(type: Int): SitePermission? = when (type) {
            org.mozilla.geckoview.GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> GEOLOCATION
            org.mozilla.geckoview.GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> NOTIFICATIONS

            org.mozilla.geckoview.GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> STORAGE
            else -> null
        }
    }
}

/** What to do with a [SitePermission] for a given origin. */
enum class SiteDecision { ASK, ALLOW, DENY }

/**
 * Per-origin permission decisions for the in-app browser, kept in their own
 * SharedPreferences file so "clear site data" and the browser's other prefs stay
 * separate from these.
 *
 * ### Why the app cannot record Gecko's own prompt answer
 * `GeckoSession.PermissionDelegate.onContentPermissionRequest` is **synchronous**
 * in GeckoView 153 — there is no `ContentPermission.Callback`, unlike the file and
 * popup prompts. The only deferral available is returning `VALUE_PROMPT`, which
 * hands the question to Gecko's own permission prompt. That prompt's answer never
 * comes back through the delegate, so an origin left on [SiteDecision.ASK] keeps
 * being asked by Gecko until the user pins a decision here. That is the standard
 * trade for a delegate-driven browser: an explicit decision made here is permanent
 * and authoritative, an undecided one behaves like a normal browser.
 *
 * Storage is one prefs entry per origin holding its decisions as
 * `"GEOLOCATION=ALLOW;MEDIA=DENY"`, which keeps the whole store enumerable
 * (`storedOrigins`) without a second index to keep in sync.
 */
object SitePermissions {

    private const val PREFS = "site_permissions"
    private const val SEP = ";"
    private const val KV = "="

    /**
     * The storage origin for [url], or null when there is nothing worth storing.
     * `about:`, `data:` and `file:` URLs have no host origin, so per-site rules
     * cannot apply to them.
     */
    fun originOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }

    fun decision(context: Context, origin: String?, permission: SitePermission): SiteDecision {
        val entry = origin?.let { read(context, it) } ?: return SiteDecision.ASK
        return entry[permission] ?: SiteDecision.ASK
    }

    /** Store [decision] for one capability. [SiteDecision.ASK] removes the entry. */
    fun set(context: Context, origin: String, permission: SitePermission, decision: SiteDecision) {
        val entry = read(context, origin).toMutableMap()
        if (decision == SiteDecision.ASK) entry.remove(permission) else entry[permission] = decision
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (entry.isEmpty()) remove(origin) else putString(origin, encode(entry))
        }.apply()
    }

    /** Every origin with at least one stored decision, newest write last. */
    fun storedOrigins(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.keys.sorted()

    /** The stored decisions for one origin, for display. */
    fun decisionsFor(context: Context, origin: String): Map<SitePermission, SiteDecision> = read(context, origin)

    fun clearOrigin(context: Context, origin: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(origin).apply()
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun read(context: Context, origin: String): Map<SitePermission, SiteDecision> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(origin, null) ?: return emptyMap()
        val out = HashMap<SitePermission, SiteDecision>()
        for (part in raw.split(SEP)) {
            if (part.isBlank()) continue
            val kv = part.split(KV)
            if (kv.size != 2) continue
            val perm = SitePermission.entries.firstOrNull { it.name == kv[0] } ?: continue
            val dec = SiteDecision.entries.firstOrNull { it.name == kv[1] } ?: continue
            out[perm] = dec
        }
        return out
    }

    private fun encode(entry: Map<SitePermission, SiteDecision>): String =
        entry.entries.joinToString(SEP) { "${it.key.name}$KV${it.value.name}" }
}