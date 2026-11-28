package com.example.ui.screens.browser

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.EditText
import android.widget.LinearLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import com.example.util.SiteDecision
import com.example.util.SitePermission
import com.example.util.SitePermissions
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebResponse
import java.util.Locale

class GeckoTabManager(
    private val context: Context,
    events: TabEvents
) {
    /**
     * The delegate sink. It MUST be re-assignable, and it IS re-assigned on every
     * composition (see `manager.events = ui`).
     *
     * The manager is process-wide (`BrowserHolder.manager`) so tabs and their live
     * sessions survive leaving the screen, but `TabEvents` is implemented by the
     * per-composition `BrowserUi`. Holding it in a constructor `val` captured the
     * FIRST composition's instance forever: after leaving and re-entering the
     * browser, every navigation/title/history/context-menu callback wrote into a
     * dead snapshot state and the UI stopped updating — the exact class of bug the
     * existing `ui.mgr = manager` line already works around for the other direction.
     */
    var events: TabEvents = events
    /** Per-tab back/forward snapshots (B7), keyed by tab id. Gecko delivers
     *  HistoryDelegate on the main looper, so a plain map is fine here. */
    val tabHistory = HashMap<Long, SessionHistory>()

    companion object {
        private const val PREFS = "gecko_browser"
        private const val KEY_TABS = "tabs_json"
        private const val KEY_ACTIVE = "active_index"
        private const val KEY_DESKTOP_OVERRIDES = "desktop_overrides"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_CUSTOM_ENGINE = "custom_engine_url"
        private const val KEY_SEARXNG_INSTANCE = "searxng_instance"
        private const val KEY_BLOCK_POPUPS = "block_popups"

        /** Hosts that must always get the desktop UA so sign-in flows work. */
        private val FORCE_DESKTOP_HOSTS =
            listOf("accounts.google.com", "accounts.youtube.com", "sso.gmail.com")

        /**
         * Label -> URL template. The label is also the persisted value, so the
         * existing `search_engine` pref needs no migration. "Custom" and
         * "SearXNG" are handled separately because they need a user URL.
         */
        private val SEARCH_ENGINE_TEMPLATES = linkedMapOf(
            "Google" to "https://www.google.com/search?q={search}",
            "DuckDuckGo" to "https://duckduckgo.com/?q={search}",
            "Bing" to "https://www.bing.com/search?q={search}",
            "Brave" to "https://search.brave.com/search?q={search}",
            "Startpage" to "https://www.startpage.com/sp/search?query={search}",
            "Ecosia" to "https://www.ecosia.org/search?q={search}",
            "Qwant" to "https://www.qwant.com/?q={search}",
            "Yahoo" to "https://search.yahoo.com/search?p={search}",
            "Yandex" to "https://yandex.com/search/?text={search}",
            "Wikipedia" to "https://en.wikipedia.org/w/index.php?search={search}",
            "Mojeek" to "https://www.mojeek.com/search?q={search}"
        )

        val SEARCH_ENGINES = SEARCH_ENGINE_TEMPLATES.keys.toList() + listOf("SearXNG", "Custom")

        /** `!g`, `!ddg`, … / `@wiki`-style shortcuts resolved before a URL check. */
        private data class SearchShortcut(val label: String, val template: String)

        private val SEARCH_SHORTCUTS = linkedMapOf(
            "!g" to SearchShortcut("Google", SEARCH_ENGINE_TEMPLATES.getValue("Google")),
            "!google" to SearchShortcut("Google", SEARCH_ENGINE_TEMPLATES.getValue("Google")),
            "!ddg" to SearchShortcut("DuckDuckGo", SEARCH_ENGINE_TEMPLATES.getValue("DuckDuckGo")),
            "!b" to SearchShortcut("Bing", SEARCH_ENGINE_TEMPLATES.getValue("Bing")),
            "!br" to SearchShortcut("Brave", SEARCH_ENGINE_TEMPLATES.getValue("Brave")),
            "!sp" to SearchShortcut("Startpage", SEARCH_ENGINE_TEMPLATES.getValue("Startpage")),
            "!e" to SearchShortcut("Ecosia", SEARCH_ENGINE_TEMPLATES.getValue("Ecosia")),
            "!q" to SearchShortcut("Qwant", SEARCH_ENGINE_TEMPLATES.getValue("Qwant")),
            "!y" to SearchShortcut("Yahoo", SEARCH_ENGINE_TEMPLATES.getValue("Yahoo")),
            "!w" to SearchShortcut("Wikipedia", SEARCH_ENGINE_TEMPLATES.getValue("Wikipedia")),
            "@wiki" to SearchShortcut("Wikipedia", SEARCH_ENGINE_TEMPLATES.getValue("Wikipedia")),
            "!m" to SearchShortcut("Mojeek", SEARCH_ENGINE_TEMPLATES.getValue("Mojeek")),
            "!yt" to SearchShortcut(
                "YouTube", "https://www.youtube.com/results?search_query={search}"
            ),
            "!gh" to SearchShortcut(
                "GitHub", "https://github.com/search?q={search}"
            ),
            "!so" to SearchShortcut(
                "Stack Overflow", "https://stackoverflow.com/search?q={search}"
            ),
            "!maps" to SearchShortcut(
                "Google Maps", "https://www.google.com/maps/search/{search}"
            )
        )

        /** Returns (shortcut label, full search URL), or null when no shortcut matched. */
        fun resolveSearchShortcut(input: String): Pair<String, String>? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val token = trimmed.substringBefore(' ', "").lowercase(Locale.ROOT)
            val rest = trimmed.substringAfter(' ', "").trim()
            if (rest.isEmpty()) return null
            val shortcut = SEARCH_SHORTCUTS[token] ?: return null
            return shortcut.label to shortcut.template.replace("{search}", Uri.encode(rest))
        }

        fun engineSearchUrl(
            name: String,
            query: String,
            customTemplate: String,
            searxngInstance: String = "https://searx.be"
        ): String {
            val q = Uri.encode(query)
            if (name == "Custom") {
                val tpl = customTemplate.takeIf { it.contains("{search}") }
                    ?: customTemplate.takeIf { it.startsWith("http") }?.plus("{search}")
                    ?: "https://www.google.com/search?q={search}"
                return tpl.replace("{search}", q)
            }
            if (name == "SearXNG") {
                val raw = searxngInstance.trim().ifBlank { "https://searx.be" }
                val tpl = when {
                    raw.contains("{search}") -> raw
                    raw.contains("q=") -> raw + "{search}"
                    raw.contains("/search") -> raw.trimEnd('/') + "?q={search}"
                    else -> raw.trimEnd('/') + "/search?q={search}"
                }
                return tpl.replace("{search}", q)
            }
            val template = SEARCH_ENGINE_TEMPLATES[name]
                ?: SEARCH_ENGINE_TEMPLATES.getValue("Google")
            return template.replace("{search}", q)
        }

        /**
         * Max tabs holding a live Gecko session; the rest are LRU-closed and reload.
         */
        const val MAX_LIVE_SESSIONS = MAX_LIVE_BROWSER_SESSIONS

        /** Cap for the reopen-closed-tab stack. */
        const val MAX_RECENTLY_CLOSED = 10

        fun looksLikeUrl(input: String): Boolean {
            val t = input.trim()
            if (t.isEmpty()) return false
            if (t.startsWith("http://") || t.startsWith("https://") || t.contains("://")) return true
            return !t.contains(" ") && t.contains(".") && t.lastOrNull()?.isLetterOrDigit() == true
        }
    }
    val tabs = androidx.compose.runtime.mutableStateListOf<GeckoTab>()

    /**
     * Recently closed tabs (Reopen closed tab). Private tabs are never pushed —
     * same rule as persistence — and NTP entries are skipped so the stack holds
     * only real pages. Capped at [MAX_RECENTLY_CLOSED].
     */
    val recentlyClosed = androidx.compose.runtime.mutableStateListOf<ClosedTab>()

    var activeIndex by androidx.compose.runtime.mutableIntStateOf(0)
    var globalDesktop: Boolean = false
    var captureEnabled: Boolean = true

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- prefs

    fun searchEngine(): String {
        val saved = prefs.getString(KEY_SEARCH_ENGINE, "Google") ?: "Google"
        return GeckoTabManager.SEARCH_ENGINES.firstOrNull { it == saved } ?: "Google"
    }

    fun setSearchEngine(name: String) {
        val safe = GeckoTabManager.SEARCH_ENGINES.firstOrNull { it == name } ?: "Google"
        prefs.edit().putString(KEY_SEARCH_ENGINE, safe).apply()
    }

    fun customEngineUrl(): String = prefs.getString(KEY_CUSTOM_ENGINE, "") ?: ""

    fun setCustomEngineUrl(url: String) {
        prefs.edit().putString(KEY_CUSTOM_ENGINE, url).apply()
    }

    fun searxngInstance(): String =
        prefs.getString(KEY_SEARXNG_INSTANCE, "https://searx.be") ?: "https://searx.be"

    fun setSearxngInstance(url: String) {
        prefs.edit().putString(KEY_SEARXNG_INSTANCE, url.trim()).apply()
    }

    fun blockPopups(): Boolean = prefs.getBoolean(KEY_BLOCK_POPUPS, false)

    fun setBlockPopups(block: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_POPUPS, block).apply()
    }

    // ------------------------------------------------------- desktop mode

    private fun desktopOverrides(): MutableMap<String, String> {
        val raw = prefs.getString(KEY_DESKTOP_OVERRIDES, null) ?: return mutableMapOf()
        val out = mutableMapOf<String, String>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out[o.getString("host")] = o.getString("mode")
            }
        }
        return out
    }

    fun desktopOverrideFor(host: String?): String? {
        if (host.isNullOrBlank()) return null
        return desktopOverrides()[host]
    }

    fun setDesktopOverride(host: String, desktop: Boolean?) {
        val map = desktopOverrides()
        if (desktop == null) map.remove(host) else map[host] = if (desktop) "desktop" else "mobile"
        val arr = JSONArray()
        map.forEach { (h, m) -> arr.put(JSONObject().put("host", h).put("mode", m)) }
        prefs.edit().putString(KEY_DESKTOP_OVERRIDES, arr.toString()).apply()
    }

    private fun hostOf(url: String): String? =
        runCatching { Uri.parse(url).host }.getOrNull()?.lowercase()

    fun isDesktopForUrl(url: String): Boolean {
        val host = hostOf(url)
        if (host != null && FORCE_DESKTOP_HOSTS.any { host.endsWith(it) }) return true
        return when (desktopOverrideFor(host)) {
            "desktop" -> true
            "mobile" -> false
            else -> globalDesktop
        }
    }

    // ------------------------------------------------------------ sessions

    fun activeTab(): GeckoTab? = tabs.getOrNull(activeIndex)

    /**
     * Match an extension `ident` URL back to one of this browser's live sessions.
     *
     * The njbrowser background introduces each relayed content port with the URL of
     * the page it belongs to, and `port.sender?.session` is normally null for those
     * (the native port's sender is the background, not the content script). With many
     * live tabs "newest port wins" is therefore wrong — it would drive the wrong tab.
     * Only tabs that already have a live session are considered, so this never spawns
     * a session as a side effect of identifying a port.
     *
     * Returns null rather than guessing when the URL matches nothing: a wrong binding
     * would send one tab's detect reply into another tab's dialog, whereas no binding
     * just makes the ⬇ tap reload once and ask the user to tap Detect again.
     */
    fun sessionForIdentUrl(url: String): GeckoSession? {
        val want = identKey(url)
        if (want.isBlank()) return null
        return tabs.firstOrNull { it.session != null && identKey(it.url) == want }?.session
    }

    /** Scheme/host/path lowercased, fragment and trailing slash dropped. */
    private fun identKey(url: String): String = url
        .substringBefore("#")
        .trimEnd('/')
        .lowercase()

    private fun buildSession(tab: GeckoTab): GeckoSession {
        val desktop = isDesktopForUrl(tab.url)
        val builder = GeckoSessionSettings.Builder()
            .usePrivateMode(tab.isPrivate)
            .useTrackingProtection(true)
            .suspendMediaWhenInactive(false)
            .viewportMode(
                if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            )
            .userAgentMode(
                if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            )

        val session = GeckoSession(builder.build())
        attachDelegates(session, tab)

        val runtime = BrowserEngine.get(context)
        session.open(runtime)
        return session
    }

    /** Return the LIVE session for [tab], creating one only when there is none.
     *
     *  A tab's session is deliberately NOT closed on a tab switch (see [park]),
     *  so returning here is the normal path and nothing is reloaded — the page
     *  keeps its scroll position, form state and history, exactly like
     *  Firefox's own `switchToSessionAtIndex`. `loadUri` runs only for a tab that
     *  has never been opened (or was evicted/cold-restored).
     *
     *  Session-state snapshots are still not used: they caused blank white pages
     *  after cache clears, so they were removed by design. */
    fun ensureSession(tab: GeckoTab): GeckoSession {
        tab.session?.let { existing ->
            // Gecko may have killed the session's content process while it was in
            // the background; reopen it rather than replacing it (replacing would
            // lose the live page).
            if (!existing.isOpen) {
                runCatching { existing.open(BrowserEngine.get(context)) }
            }
            tab.lastUsedAt = System.currentTimeMillis()
            return existing
        }
        val session = buildSession(tab)
        tab.session = session
        tab.lastUsedAt = System.currentTimeMillis()
        if (tab.url.isNotBlank() && tab.url != NTP_URL) {
            session.loadUri(tab.url)
        }
        return session
    }

    fun switchTo(index: Int) {
        if (index !in tabs.indices || index == activeIndex) {
            activeIndex = index
            return
        }
        activeTab()?.let { park(it) }
        activeIndex = index
        evictStaleSessions()
        persistTabs()
    }

    fun newTab(url: String, isPrivate: Boolean, activate: Boolean): GeckoTab {
        val tab = GeckoTab(System.currentTimeMillis(), url, "", isPrivate)
        tabs.add(tab)
        if (activate) {
            activeTab()?.let { park(it) }
            activeIndex = tabs.lastIndex
            evictStaleSessions()
        }
        persistTabs()
        return tab
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val closing = tabs[index]
        rememberClosed(closing)
        closing.session?.let { s ->
            runCatching { s.setActive(false) }
            runCatching { s.close() }
        }
        closing.session = null
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            tabs.add(GeckoTab(System.currentTimeMillis(), NTP_URL, "New Tab", false))
            activeIndex = 0
        } else if (activeIndex >= tabs.size) {
            activeIndex = tabs.size - 1
        } else if (index < activeIndex) {
            activeIndex--
        }
        persistTabs()
    }

    /**
     * Push a just-closed tab onto the reopen stack. Private tabs are skipped (the
     * same non-persistence rule) and the NTP carries no page to return to.
     */
    private fun rememberClosed(tab: GeckoTab) {
        if (tab.isPrivate || tab.url == NTP_URL || tab.url.isBlank()) return
        recentlyClosed.add(0, ClosedTab(tab.url, tab.title))
        while (recentlyClosed.size > MAX_RECENTLY_CLOSED) recentlyClosed.removeAt(recentlyClosed.size - 1)
    }

    /** Reopen the most recently closed tab; returns the new tab, or null if none. */
    fun reopenLastClosed(): GeckoTab? {
        val closed = recentlyClosed.removeFirstOrNull() ?: return null
        return newTab(closed.url, isPrivate = false, activate = true)
    }

    /**
     * Close every private tab, leaving normal tabs untouched. Private tabs are
     * never persisted and never enter the reopen stack, so this is a straight
     * teardown.
     */
    fun closeAllPrivate() {
        val activeTab = tabs.getOrNull(activeIndex)
        var removedAny = false
        var i = tabs.lastIndex
        while (i >= 0) {
            if (tabs[i].isPrivate) {
                val t = tabs[i]
                t.session?.let { s ->
                    runCatching { s.setActive(false) }
                    runCatching { s.close() }
                }
                t.session = null
                tabs.removeAt(i)
                removedAny = true
            }
            i--
        }
        if (removedAny) {
            if (tabs.isEmpty()) {
                tabs.add(GeckoTab(System.currentTimeMillis(), NTP_URL, "New Tab", false))
                activeIndex = 0
            } else if (activeTab != null && !activeTab.isPrivate) {
                // Keep showing the same tab: its position may have shifted down
                // when a private tab ahead of it was removed.
                activeIndex = tabs.indexOf(activeTab).takeIf { it >= 0 }?.coerceAtMost(tabs.size - 1)
                    ?: (tabs.size - 1)
            } else if (activeIndex >= tabs.size) {
                activeIndex = tabs.size - 1
            }
            persistTabs()
        }
    }

    fun closeAll() {
        tabs.forEach { t ->
            t.session?.let { s ->
                runCatching { s.setActive(false) }
                runCatching { s.close() }
            }
            t.session = null
        }
        tabs.clear()
        // "Close all" is a deliberate wipe — clearing the reopen stack matches it.
        recentlyClosed.clear()
        tabs.add(GeckoTab(System.currentTimeMillis(), NTP_URL, "New Tab", false))
        activeIndex = 0
        persistTabs()
    }

    /**
     * Background a tab WITHOUT destroying its session.
     *
     *  This replaces the old `deactivate()`, which called `GeckoSession.close()`
     *  and nulled `tab.session` on every switch. That made `ensureSession()`
     *  rebuild the session and `loadUri()` the URL on each visit, so every tab
     *  switch reloaded the page and lost its scroll/form state.
     *
     *  `GeckoSession.close()` is the caller's to make — releasing a session from
     *  the view (`GeckoView.releaseSession()`) does NOT close it. We only stop it
     *  so a backgrounded tab doesn't keep pulling bytes, and mark it for LRU
     *  eviction. Active-state is handled by GeckoView's display acquire/release
     *  on setSession/releaseSession, so no manual setActive juggling is needed.
     */
    private fun park(tab: GeckoTab) {
        tab.session?.let { s ->
            runCatching { s.setActive(false) }
            runCatching { s.stop() }
        }
        tab.lastUsedAt = System.currentTimeMillis()
    }

    /**
     * Keep at most [MAX_LIVE_SESSIONS] tabs holding a live session. Each live tab
     * is its own Gecko content process, so an uncapped set would OOM a low-RAM
     * device. The least-recently-used background tab is closed first; its URL is
     * persisted, so it still comes back — just reloaded, which is the intended
     * trade-off beyond the cap. The active tab is never evicted.
     */
    private fun evictStaleSessions() {
        var live = tabs.count { it.session != null }
        if (live <= MAX_LIVE_SESSIONS) return
        val active = activeTab()
        val candidates = tabs
            .filter { it.session != null && it !== active && !it.pinned }
            .sortedBy { it.lastUsedAt }
        for (tab in candidates) {
            if (live <= MAX_LIVE_SESSIONS) break
            tab.session?.let { s ->
                runCatching { s.setActive(false) }
                runCatching { s.close() }
            }
            tab.session = null
            live--
        }
    }

    fun releaseAll() {
        tabs.forEach { t ->
            t.session?.let { s ->
                runCatching { s.setActive(false) }
                runCatching { s.close() }
            }
            t.session = null
        }
    }

    fun applyDesktopModeToSession(tab: GeckoTab, desktop: Boolean) {
        tab.session?.let { s ->
            runCatching {
                s.settings.setViewportMode(
                    if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                    else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
                )
                s.settings.setUserAgentMode(
                    if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                    else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                )
                s.reload()
            }
        }
    }

    // ---------------------------------------------------------- delegates

    private fun attachDelegates(session: GeckoSession, tab: GeckoTab) {
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tab.url = url
                // Per-page: the previous page's blocked count and certificate must
                // not carry into the new one while it loads.
                tab.blockedCount = 0
                tab.securityInfo = null
                events.onPageStart(tab, url)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                events.onPageStop(tab, success)
                persistTabs()
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                events.onProgress(tab, progress)
            }

            override fun onSecurityChange(
                session: GeckoSession,
                securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
            ) {
                tab.isSecure = securityInfo.isSecure
                tab.securityInfo = securityInfo
            }

            override fun onSessionStateChange(
                session: GeckoSession,
                sessionState: GeckoSession.SessionState
            ) {
                tab.savedState = sessionState
            }
        }

        // Live tracker-blocked count for the Site Info sheet. `ContentBlocking.
        // Controller.getLog()` would also work but is async and only sampled when
        // the sheet opens; a per-session delegate keeps a running total as the
        // page loads.
        session.contentBlockingDelegate = object : org.mozilla.geckoview.ContentBlocking.Delegate {
            override fun onContentBlocked(
                session: GeckoSession,
                event: org.mozilla.geckoview.ContentBlocking.BlockEvent
            ) {
                if (event.isBlocking) tab.blockedCount += 1
            }
        }

        // Route text selection through the app's own long-press menu.
        //
        // With no delegate set, GeckoView installs its own and pops up a two-row
        // Copy / Select all toolbar that the app cannot extend. Publishing the
        // selection here (and hiding Gecko's popup) lets `onContextMenu` hand
        // text actions to the same menu that serves links and media, and the
        // `Selection` handle is what makes Copy / Select all actually act on the
        // page rather than on a string we copied out of it.
        session.selectionActionDelegate = object : GeckoSession.SelectionActionDelegate {
            override fun onShowActionRequest(
                session: GeckoSession,
                selection: GeckoSession.SelectionActionDelegate.Selection
            ) {
                if (selection.text.isNullOrBlank()) {
                    selection.hide()
                    return
                }
                events.currentSelection = selection
                events.onLinkContextMenu(
                    tab,
                    LinkContextTarget(selectedText = selection.text, selection = selection)
                )
                selection.hide()
            }

            override fun onHideAction(session: GeckoSession, reason: Int) {
                events.currentSelection = null
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                tab.title = title ?: ""
                events.onTitleChange(tab, title ?: "")
                persistTabs()
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                events.onFullScreenChanged(fullScreen)
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                // Never `return` early here. A long-press on plain text has no
                // linkUri and no srcUri, and bailing out is what left the user
                // with nothing but Gecko's built-in Copy/Select all popup. Build
                // the target from whatever is actually under the finger and let
                // the UI decide which rows apply.
                val url = element.linkUri ?: element.srcUri
                val selection = events.currentSelection
                if (url == null && selection?.text.isNullOrBlank()) return
                events.onLinkContextMenu(
                    tab,
                    LinkContextTarget(
                        url = url,
                        isVideo = element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO,
                        selectedText = selection?.text.orEmpty(),
                        selection = selection
                    )
                )
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                events.onExternalDownload(response)
            }

            override fun onCrash(session: GeckoSession) {
                recreateAfterCrash(tab, killed = false)
            }

            override fun onKill(session: GeckoSession) {
                recreateAfterCrash(tab, killed = true)
            }

            override fun onCloseRequest(session: GeckoSession) {
                events.onCloseTabRequest(tab)
            }

            override fun onShowDynamicToolbar(session: GeckoSession) {
                events.onDynamicToolbarVisibility(true)
            }

            override fun onHideDynamicToolbar(session: GeckoSession) {
                events.onDynamicToolbarVisibility(false)
            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                isInBFCache: Boolean
            ) {
                if (!url.isNullOrEmpty()) {
                    // A freshly-opened session reports about:blank; never clobber the NTP marker.
                    if (tab.url == NTP_URL && (url.isBlank() || url == "about:blank")) return
                    tab.url = url
                    events.onLocationChange(tab, url, tab.isSecure)
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                tab.canGoBack = canGoBack
                events.onNavStateChanged(tab, canGoBack, tab.canGoForward)
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                tab.canGoForward = canGoForward
                events.onNavStateChanged(tab, tab.canGoBack, canGoForward)
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val uri = request.uri
                if (handleExternalScheme(uri)) return GeckoResult.fromValue(AllowOrDeny.DENY)
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW &&
                    uri != NTP_URL
                ) {
                    events.onOpenInNewTab(uri, request.hasUserGesture, tab.isPrivate)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return null
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: org.mozilla.geckoview.WebRequestError
            ): GeckoResult<String>? = null
        }

        session.historyDelegate = object : GeckoSession.HistoryDelegate {
            override fun onHistoryStateChange(
                session: GeckoSession,
                history: GeckoSession.HistoryDelegate.HistoryList
            ) {
                // Snapshot immediately: `history` is a live native-backed view and
                // must not be retained (or handed to a dialog) after this returns.
                tabHistory[tab.id] = SessionHistory(
                    items = history.mapNotNull { item ->
                        item.uri?.let { it to item.title.orEmpty() }
                    },
                    currentIndex = history.currentIndex
                )
                events.onHistoryChanged?.invoke()
            }

            override fun onVisited(
                session: GeckoSession,
                uri: String,
                title: String?,
                flags: Int
            ): GeckoResult<Boolean> {
                // Private tabs are recorded nowhere.
                if (tab.isPrivate) return GeckoResult.fromValue(false)
                if (uri.isNotBlank() &&
                    flags == GeckoSession.HistoryDelegate.VISIT_TOP_LEVEL
                ) {
                    events.onVisit?.invoke(title.orEmpty(), uri)
                }
                // false = do not persist into Gecko's own history database. The app
                // keeps its own Room history (and B8's visit counter), so a second,
                // unread copy would only grow the profile.
                return GeckoResult.fromValue(false)
            }
        }

        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                permission: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int> {
                // Per-site (B6). The old code blanket-ALLOWED location, notifications
                // and persistent storage for every site with no prompt and nothing the
                // user could see or change. Now an explicit decision for the origin
                // wins outright, and an undecided origin goes back to Gecko to ask.
                val perm = SitePermission.permForGeckoType(permission.permission)
                    // Anything unusual is still denied, as before.
                    ?: return GeckoResult.fromValue(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    )
                val origin = SitePermissions.originOf(permission.uri)
                    ?: SitePermissions.originOf(tab.url)
                return GeckoResult.fromValue(
                    when (SitePermissions.decision(context, origin, perm)) {
                        SiteDecision.ALLOW ->
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                        SiteDecision.DENY ->
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                        // GeckoView 153 has no async permission callback, so VALUE_PROMPT
                        // is the only way to actually ask. See SitePermissions for why the
                        // answer cannot be recorded from here.
                        SiteDecision.ASK ->
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
                    }
                )
            }

            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: GeckoSession.PermissionDelegate.Callback
            ) {
                events.requestAndroidPermissions(permissions, callback)
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) {
                // An explicit per-site decision (B6) overrides the global toggle in
                // both directions. Left on ASK this falls through to the pre-B6
                // behaviour on purpose: a new prompt here would interrupt the
                // Google Meet / Zoom calls that are verified working in this browser.
                val origin = SitePermissions.originOf(uri) ?: SitePermissions.originOf(tab.url)
                when (SitePermissions.decision(context, origin, SitePermission.MEDIA)) {
                    SiteDecision.ALLOW -> {
                        callback.grant(video?.firstOrNull(), audio?.firstOrNull())
                        return
                    }
                    SiteDecision.DENY -> {
                        callback.reject()
                        return
                    }
                    SiteDecision.ASK -> Unit
                }
                if (!captureEnabled) {
                    callback.reject()
                    return
                }
                callback.grant(video?.firstOrNull(), audio?.firstOrNull())
            }
        }

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                return events.onFilePrompt(prompt)
            }

            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                showBasicDialog(prompt.title, prompt.message, null, onOk = { prompt.dismiss() })
                return null
            }

            override fun onTextPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.TextPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                showTextDialog(prompt.title, prompt.message, prompt.defaultValue) {
                    prompt.confirm(it)
                }
                return null
            }

            override fun onButtonPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ButtonPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                showConfirmDialog(prompt.title, prompt.message, onYes = { prompt.confirm(0) })
                return null
            }

            override fun onColorPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ColorPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val default = prompt.defaultValue ?: "#3B82F6"
                showColorDialog(prompt.title, default) { prompt.confirm(it) }
                return null
            }

            override fun onDateTimePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.DateTimePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                showTextDialog(prompt.title, null, prompt.defaultValue) {
                    prompt.confirm(it)
                }
                return null
            }

            override fun onAuthPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AuthPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                showAuthDialog(prompt.title, prompt.message, prompt.authOptions.username ?: "") { user, pass ->
                    prompt.confirm(user, pass)
                }
                return null
            }

            override fun onChoicePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ChoicePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                showChoiceDialog(prompt)
                return null
            }

            override fun onPopupPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.PopupPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // GeckoView 153 has NO WindowDelegate / onNewWindow hook — the only
                // new-window entry point is this prompt, and it exposes the target
                // as `targetUri`. The old code answered ALLOW and discarded the URI,
                // so Gecko tried to open a window nothing owned and every
                // target="_blank" link and window.open() was silently dropped.
                //
                // We therefore always DENY the popup (so Gecko does nothing with it)
                // and perform the navigation ourselves: in the current tab when the
                // target is the same host, in a new tab otherwise.
                val target = prompt.targetUri
                if (blockPopups() || target.isNullOrBlank()) {
                    return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
                }
                if (sameHost(tab.url, target)) {
                    // Same host: stay in this tab so a target="_blank" link to a
                    // sibling page doesn't pile up tabs. loadUri (not tab.url
                    // alone) is what actually starts the navigation.
                    runCatching { session.loadUri(target) }
                        .onFailure { tab.url = target }
                } else {
                    events.onOpenInNewTab(target, true, tab.isPrivate)
                }
                return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
            }
        }
    }

    // Host comparison for the popup path, tolerant of scheme/port/trailing slash.
    private fun sameHost(a: String, b: String): Boolean {
        fun host(u: String) = runCatching { android.net.Uri.parse(u).host }.getOrNull()?.lowercase()
        val ha = host(a)
        val hb = host(b)
        return ha != null && hb != null && ha == hb
    }

    private fun recreateAfterCrash(tab: GeckoTab, killed: Boolean) {
        runCatching { tab.session?.close() }
        runCatching { tab.session = null }
        events.onTabCrashed(tab, killed)
    }

    private fun handleExternalScheme(uri: String): Boolean {
        val scheme = uri.substringBefore(":").lowercase()
        val external = when (scheme) {
            "tel", "mailto", "sms", "smsto", "mms", "geo", "market", "whatsapp", "tg" -> true
            "intent" -> true
            else -> false
        }
        if (!external) return false
        if (scheme == "intent") return handleIntentUri(uri)
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * `intent://` is not just another scheme: it can name an arbitrary component
     * and carry arbitrary extras, so a page could ask us to launch an activity we
     * never vetted. It used to be handed straight to `startActivity`, which is
     * exactly that. Now it is parsed and filtered: a plain browsable web target is
     * opened directly, anything else has to be confirmed by the user.
     */
    private fun handleIntentUri(uri: String): Boolean {
        val parsed = try {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            null
        } ?: return false
        val target = parsed.data
        val isPlainWebLink = parsed.action == Intent.ACTION_VIEW &&
            target != null &&
            (target.scheme == "http" || target.scheme == "https")
        if (isPlainWebLink) {
            return runCatching { context.startActivity(parsed) }.isSuccess
        }
        val what = parsed.component?.shortClassName
            ?: target?.scheme?.let { "another app ($it)" }
            ?: "another app"
        showConfirmDialog("Open external app?", "A website asked to open $what.") {
            runCatching { context.startActivity(parsed) }
        }
        return true
    }

    // ----------------------------------------------- basic prompt dialogs

    /** Show a dialog, guarding against BadTokenException when the Activity is gone. */
    private fun safeShow(builder: AlertDialog.Builder) {
        val ctx = context
        if (ctx is android.app.Activity && (ctx.isFinishing || ctx.isDestroyed)) return
        runCatching { builder.show() }
    }

    private fun showBasicDialog(title: String?, message: String?, input: String?, onOk: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            safeShow(
                AlertDialog.Builder(context)
                    .setTitle(title ?: "Message")
                    .setMessage(message ?: "")
                    .setPositiveButton("OK") { d, _ -> d.dismiss(); onOk() }
                    .setOnDismissListener { onOk() }
            )
        }
    }

    private fun showTextDialog(title: String?, message: String?, initial: String?, onOk: (String) -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val input = android.widget.EditText(context).apply {
                setText(initial ?: "")
                setPadding(48, 24, 48, 12)
            }
            AlertDialog.Builder(context)
                .setTitle(title ?: "Input")
                .setMessage(message?.takeIf { it.isNotBlank() })
                .setView(input)
                .setPositiveButton("OK") { d, _ ->
                    d.dismiss(); onOk(input.text.toString())
                }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .let { safeShow(it) }
        }
    }

    private fun showConfirmDialog(title: String?, message: String?, onYes: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            AlertDialog.Builder(context)
                .setTitle(title ?: "Confirm")
                .setMessage(message ?: "")
                .setPositiveButton("OK") { d, _ -> d.dismiss(); onYes() }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .let { safeShow(it) }
        }
    }

    private fun showColorDialog(title: String?, initial: String, onPick: (String) -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val colors = listOf("#FF0000", "#FF8800", "#FFDD00", "#22AA44", "#2E86DE",
                "#8E44AD", "#000000", "#777777", "#FFFFFF")
            val labels = arrayOf("Red", "Orange", "Yellow", "Green", "Blue", "Purple",
                "Black", "Gray", "White")
            AlertDialog.Builder(context)
                .setTitle(title ?: "Pick a color")
                .setItems(labels) { d, which ->
                    d.dismiss(); onPick(colors[which])
                }
                .setNeutralButton("Use $initial") { d, _ -> d.dismiss(); onPick(initial) }
                .let { safeShow(it) }
        }
    }

    private fun showAuthDialog(
        title: String?,
        message: String?,
        initialUser: String,
        onOk: (String, String) -> Unit
    ) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val container = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 24, 48, 12)
            }
            val userEdit = android.widget.EditText(context).apply {
                hint = "Username"; setText(initialUser)
            }
            val passEdit = android.widget.EditText(context).apply {
                hint = "Password"
                setSingleLine(true)
                transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            }
            container.addView(userEdit)
            container.addView(passEdit)
            AlertDialog.Builder(context)
                .setTitle(title ?: "Sign in")
                .setMessage(message?.takeIf { it.isNotBlank() })
                .setView(container)
                .setPositiveButton("OK") { d, _ ->
                    d.dismiss(); onOk(userEdit.text.toString(), passEdit.text.toString())
                }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .let { safeShow(it) }
        }
    }

    private fun showChoiceDialog(prompt: GeckoSession.PromptDelegate.ChoicePrompt) {
        val flatChoices = mutableListOf<GeckoSession.PromptDelegate.ChoicePrompt.Choice>()
        fun flatten(list: Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>?) {
            list?.forEach { c ->
                if (!c.separator) flatChoices.add(c)
                flatten(c.items)
            }
        }
        flatten(prompt.choices)
        if (flatChoices.isEmpty()) return
        val multiple = prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val labels = flatChoices.map { it.label ?: it.id }.toTypedArray()
            val checked = BooleanArray(labels.size) { flatChoices[it].selected }
            AlertDialog.Builder(context)
                .setTitle(prompt.title ?: "Choose")
                .setMessage(prompt.message?.takeIf { it.isNotBlank() })
                .apply {
                    if (multiple) {
                        setMultiChoiceItems(labels, checked) { _, _, _ -> }
                    } else {
                        setItems(labels) { d, which ->
                            d.dismiss()
                            flatChoices[which].id?.let { prompt.confirm(it) }
                        }
                    }
                }
                .apply {
                    if (multiple) {
                        setPositiveButton("OK") { d, _ ->
                            d.dismiss()
                            val ids = flatChoices.filterIndexed { idx, _ -> checked[idx] }
                                .mapNotNull { it.id }.toTypedArray()
                            prompt.confirm(ids)
                        }
                    }
                    setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                }
                .let { safeShow(it) }
        }
    }

    // -------------------------------------------------------- persistence

    fun persistTabs() {
        try {
            val arr = JSONArray()
            // Private tabs are deliberately NOT persisted. Their URL, title and
            // existence were all written to a plaintext SharedPreferences file, so
            // a private-mode visit came back after a restart and was readable from
            // the app's data directory. Firefox's rule is the right one: private
            // tabs die with the session.
            tabs.filterNot { it.isPrivate }.forEach { t ->
                val o = JSONObject()
                    .put("id", t.id)
                    .put("url", if (t.url == NTP_URL) "" else t.url)
                    .put("title", t.title)
                    .put("private", false)
                    .put("pinned", t.pinned)
                arr.put(o)
            }
            prefs.edit()
                .putString(KEY_TABS, arr.toString())
                .putInt(KEY_ACTIVE, activeIndex)
                .apply()
        } catch (e: Exception) {
            // ignore persistence failures
        }
    }

    /** Rebuild the tab list from persisted metadata (sessions are lazy). */
    fun restoreTabs(): Boolean {
        val raw = prefs.getString(KEY_TABS, null) ?: return false
        try {
            val arr = JSONArray(raw)
            val restored = mutableListOf<GeckoTab>()
            // Keep the persisted id. It is written by persistTabs(), so it is the
            // only thing that can tie a restored tab back to `attachedTabId` and a
            // still-live session; minting a fresh id here discarded it and forced a
            // needless re-attach. Fall back to a generated id only if one is absent
            // (or collides, which two entries restored in the same millisecond can do).
            val usedIds = mutableSetOf<Long>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                // Older builds persisted private tabs; drop those rows rather than
                // resurrecting them, otherwise a private visit recorded before the
                // fix would still come back once and then keep being re-persisted.
                if (o.optBoolean("private", false)) continue
                val url = o.optString("url", "")
                val persistedId = o.optLong("id", 0L)
                val id = if (persistedId != 0L && usedIds.add(persistedId)) {
                    persistedId
                } else {
                    System.currentTimeMillis() + i
                }
                usedIds.add(id)
                restored.add(
                    GeckoTab(
                        id = id,
                        url = url.ifBlank { NTP_URL },
                        title = o.optString("title", ""),
                        isPrivate = o.optBoolean("private", false)
                    ).apply {
                        pinned = o.optBoolean("pinned", false)
                    }
                )
            }
            if (restored.isEmpty()) return false
            tabs.clear()
            tabs.addAll(restored)
            activeIndex = prefs.getInt(KEY_ACTIVE, 0).coerceIn(0, tabs.lastIndex)
            return true
        } catch (e: Exception) {
            return false
        }
    }
}


