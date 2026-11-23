package com.example

import android.os.Build
import android.webkit.WebView

/**
 * Process-wide singleton holding the live WebViews used by the in-app web apps
 * (Telegram, FB, YouTube, etc.), keyed by app id. Because the web apps are
 * Compose screens embedded inside MainActivity (always-foreground main process),
 * both the app process and the WebView renderer stay alive, so reopening a web
 * app reuses its already-loaded page instead of reconstructing it and reloading.
 *
 * Each web app keeps its own WebView so switching between apps preserves each
 * app's live page. This mirrors how the in-app browser keeps its Gecko sessions
 * alive in `BrowserHolder` (see `GeckoBrowserScreen.kt`, which owns the tab and
 * session model inline).
 */
object WebAppHolder {

    private val views = LinkedHashMap<String, WebView>()


    @Volatile
    private var dataDirApplied = false

    /** Apply the web app data-directory suffix exactly once per process. */
    fun ensureDataDir(context: android.content.Context, suffix: String) {
        if (dataDirApplied) return
        dataDirApplied = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { WebView.setDataDirectorySuffix(suffix) }
        }
    }

    /** Obtain the live WebView for [key], creating it once if none exists yet. */
    fun obtainOrCreate(key: String, generator: () -> WebView): WebView {
        return views[key] ?: synchronized(this) {
            views[key] ?: generator().also { views[key] = it }
        }
    }

    /** The held WebView for [key], if any. */
    fun peek(key: String): WebView? = views[key]

    /** True if the WebView for [key] already has a loaded page. */
    fun isShowing(key: String): Boolean {
        val w = views[key] ?: return false
        val loaded = runCatching { w.url }.getOrNull().orEmpty()
        return loaded.isNotBlank() && loaded != "about:blank"
    }

    /** Detach the WebView for [key] from any host view hierarchy (does NOT destroy). */
    fun detachFromParent(key: String) {
        val w = views[key] ?: return
        runCatching {
            val parent = w.parent
            if (parent is android.view.ViewGroup) parent.removeView(w)
        }
    }

    /** Pause timers for all held WebViews when the host goes to background. */
    fun pause() {
        views.values.forEach { w ->
            runCatching { w.pauseTimers() }
            runCatching { w.onPause() }
        }
    }

    /** Resume timers for all held WebViews when the host returns to foreground. */
    fun resume() {
        views.values.forEach { w ->
            runCatching { w.onResume() }
            runCatching { w.resumeTimers() }
        }
    }

    /**
     * Fully release the WebView for [key] (hard teardown, e.g. "Close web app").
     * The next time the app is opened it starts fresh.
     */
    fun release(key: String) {
        val w = views.remove(key) ?: return
        runCatching { w.stopLoading() }
        runCatching { w.removeAllViews() }
        runCatching {
            val parent = w.parent
            if (parent is android.view.ViewGroup) parent.removeView(w)
        }
        runCatching { w.destroy() }
    }

    /** Release all held WebViews (process teardown / full reset). */
    fun releaseAll() {
        views.keys.toList().forEach { release(it) }
    }
}
