package com.example.ui.screens.browser

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse

/** Marker URL for the built-in new-tab page (never sent to the engine). */
const val NTP_URL = "about:njnewtab"

/** Process-wide cap on live Gecko sessions (each is a content process). */
internal const val MAX_LIVE_BROWSER_SESSIONS = 3

/**
 * A user-pinned new-tab tile (B8). Kept in their own prefs file as a JSON array so
 * the new-tab page has somewhere to put a site the visit ranking would never
 * surface — a rarely used intranet page, or a web app the user opens daily but
 * never searches for.
 */
data class NtpTile(val title: String, val url: String)

private const val PREFS_NTP_TILES = "browser_ntp_tiles"

fun loadNtpTiles(context: Context): List<NtpTile> = runCatching {
    val raw = context.getSharedPreferences(PREFS_NTP_TILES, Context.MODE_PRIVATE)
        .getString("tiles", null) ?: return emptyList()
    val arr = JSONArray(raw)
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val u = o.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        NtpTile(o.optString("title").ifBlank { u }, u)
    }
}.getOrDefault(emptyList())

fun saveNtpTiles(context: Context, tiles: List<NtpTile>) {
    val arr = JSONArray()
    tiles.forEach {
        arr.put(JSONObject().put("title", it.title).put("url", it.url))
    }
    context.getSharedPreferences(PREFS_NTP_TILES, Context.MODE_PRIVATE)
        .edit().putString("tiles", arr.toString()).apply()
}

/** Deterministic tile colour from the URL, so a tile keeps the same colour. */
fun tileColor(url: String): Color {
    val palette = listOf(
        Color(0xFFEF5350), Color(0xFFAB47BC), Color(0xFF5C6BC0), Color(0xFF29B6F6),
        Color(0xFF26A69A), Color(0xFF66BB6A), Color(0xFF9CCC65), Color(0xFFFFA726)
    )
    return palette[(url.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % palette.size]
}

class GeckoTab(
    val id: Long,
    url: String,
    title: String,
    val isPrivate: Boolean
) {
    // Compose-state backed: these are written from Gecko's delegates while the
    // tab sits in a SnapshotStateList. As plain vars the mutation did not
    // invalidate any reader, so the tab drawer kept showing a stale title/URL.
    var url by mutableStateOf(url)
    var title by mutableStateOf(title)
    var isSecure by mutableStateOf(false)
    /**
     * Full security information for the active page (Site Info sheet). The
     * callback already receives it — it used to be reduced to `isSecure` and the
     * certificate, origin and mixed-content detail were thrown away.
     */
    var securityInfo by mutableStateOf<GeckoSession.ProgressDelegate.SecurityInformation?>(null)
    /** Trackers blocked on this page, counted live from the content-blocking delegate. */
    var blockedCount by mutableStateOf(0)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    /** LRU stamp for the live-session cap. */
    var lastUsedAt: Long = System.currentTimeMillis()
    var session: GeckoSession? = null
    /** Pinned tabs are kept out of LRU eviction and shown distinctly. */
    var pinned by mutableStateOf(false)
    /** Best-effort saved session state for eviction/restart continuity. */
    var savedState: GeckoSession.SessionState? = null
    /** True while a page in this tab is in a crashed/killed error state. */
    var crashed by mutableStateOf(false)
}

/**
 * A plain snapshot of one tab's session history (B7).
 *
 * Gecko's `HistoryList` is a *live view* backed by native state, so it must not be
 * retained past the callback — the list is copied into this immediately, which is
 * also what makes it safe to hand to a Compose dialog.
 */
data class SessionHistory(
    val items: List<Pair<String, String>>,
    val currentIndex: Int
) {
    /** Entries ahead of the current page, nearest first — what the Back menu shows. */
    fun backEntries(): List<IndexedValue<Pair<String, String>>> =
        items.indices.filter { it < currentIndex }.sortedByDescending { it }.map { IndexedValue(it, items[it]) }

    /** Entries behind the current page, nearest first — what the Forward menu shows. */
    fun forwardEntries(): List<IndexedValue<Pair<String, String>>> =
        items.indices.filter { it > currentIndex }.sortedBy { it }.map { IndexedValue(it, items[it]) }
}

/** A closed tab remembered for "Reopen closed tab" (URL + title, never a session). */
data class ClosedTab(val url: String, val title: String)

/** Events forwarded from session delegates to the UI layer. */
interface TabEvents {
    fun onPageStart(tab: GeckoTab, url: String)
    fun onPageStop(tab: GeckoTab, success: Boolean)
    fun onProgress(tab: GeckoTab, progress: Int)
    fun onTitleChange(tab: GeckoTab, title: String)
    fun onLocationChange(tab: GeckoTab, url: String, secure: Boolean)
    fun onNavStateChanged(tab: GeckoTab, canGoBack: Boolean, canGoForward: Boolean)
    /**
     * B7: the tab's back/forward list changed, so an open trail menu must redraw.
     * A settable hook (not a method) because the delegate runs outside the
     * composable scope that owns the state.
     */
    var onHistoryChanged: (() -> Unit)?
    /**
     * B8: a top-level page open, for the Top-sites ranking. Deliberately separate
     * from the history-list recording so the visit counter and the plain list can
     * evolve independently, and so private tabs can be filtered out here.
     */
    var onVisit: ((String, String) -> Unit)?
    fun onExternalDownload(response: WebResponse)
    fun onTabCrashed(tab: GeckoTab, killed: Boolean)
    fun onFullScreenChanged(active: Boolean)
    fun onDynamicToolbarVisibility(show: Boolean)
/**
     * Open [url] in a new tab. [privateTab] must be the source tab's own flag —
     * a link or popup opened from a private tab may only open another private tab.
     * It defaults to false purely so existing call sites still compile; every
     * delegate that has a [GeckoTab] in scope passes it explicitly.
     */
    fun onOpenInNewTab(url: String, activate: Boolean, privateTab: Boolean = false)
    fun onCloseTabRequest(tab: GeckoTab)
    fun onLinkContextMenu(tab: GeckoTab, target: LinkContextTarget)
    /**
     * The live text selection, published by [selectionActionDelegate] so that a
     * subsequent `onContextMenu` can include text actions in the app's own menu.
     */
    var currentSelection: GeckoSession.SelectionActionDelegate.Selection?
    fun onFilePrompt(prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?
    fun requestAndroidPermissions(
        permissions: Array<out String>?,
        callback: GeckoSession.PermissionDelegate.Callback
    )
}

/**
 * Target of a long-press in the page.
 *
 * A long-press can land on three different things and they need different menus:
 *  - a link / image / video → [url] (+ [isVideo])
 *  - plain text              → [selectedText]
 *  - both at once (a link inside a selection) → everything
 *
 * [url] is nullable on purpose: the old code did `element.linkUri ?: element.srcUri ?: return`,
 * so a long-press on ordinary text produced NO menu at all and the user only got
 * Gecko's own two-row Copy/Select all popup. Text now produces this menu instead.
 */
data class LinkContextTarget(
    val url: String? = null,
    val isVideo: Boolean = false,
    val selectedText: String = "",
    /** The live Gecko selection, kept so Copy/Select all act on the page. */
    val selection: GeckoSession.SelectionActionDelegate.Selection? = null
) {
    val hasSelection: Boolean get() = selectedText.isNotBlank()
    val isLink: Boolean get() = !url.isNullOrBlank()

    val isImage: Boolean
        get() {
            if (isVideo) return false
            val path = url?.substringBefore('?')?.substringBefore('#')?.lowercase() ?: return false
            return path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".png") || path.endsWith(".gif") ||
                path.endsWith(".webp") || path.endsWith(".bmp")
        }
}

data class QuickLink(val title: String, val url: String, val color: Color)

val QUICK_LINKS = listOf(
    QuickLink("Google", "https://www.google.com", Color(0xFF4285F4)),
    QuickLink("YouTube", "https://www.youtube.com", Color(0xFFFF0000)),
    QuickLink("Facebook", "https://www.facebook.com", Color(0xFF1877F2)),
    QuickLink("WhatsApp", "https://web.whatsapp.com", Color(0xFF25D366)),
    QuickLink("Instagram", "https://www.instagram.com", Color(0xFFE1306C)),
    QuickLink("X", "https://x.com", Color(0xFF000000)),
    QuickLink("Gmail", "https://mail.google.com", Color(0xFFEA4335)),
    QuickLink("ChatGPT", "https://chatgpt.com", Color(0xFF10A37F))
)

/** Process-wide browser state so tabs survive leaving/re-entering the app screen. */
internal object BrowserHolder {
    @Volatile var manager: GeckoTabManager? = null
}
