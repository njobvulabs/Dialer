package com.example.util

import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Forces WhatsApp Web (which renders in a wide ~800px layout with no viewport
 * meta) to lay out at device width with scale = 1.
 *
 * Root cause of the "menus open at top-left" bug: the page computes popup
 * positions in its large layout viewport, but the WebView scales that layout
 * down to fit the phone, so position:fixed overlays collapse toward the
 * top-left. Injecting a width=device-width viewport makes layout == visual
 * viewport (scale = 1), which anchors menus correctly.
 */
object ViewportMetaInjector {
    private const val META = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">"

    private val HEAD_OPEN = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
    private val VIEWPORT_META = Regex(
        "<meta[^>]*name=[\"']viewport[\"'][^>]*>",

        RegexOption.IGNORE_CASE
    )

    /**
     * Rewrites the main-frame HTML to inject (or replace) a device-width
     * viewport meta. Returns the original string untouched if there's no <head>.
     */
    fun rewriteHtml(html: String): String {
        val existing = VIEWPORT_META.find(html)
        return if (existing != null) {
            html.substring(0, existing.range.first) +
                META +
                html.substring(existing.range.last + 1)
        } else {
            val m = HEAD_OPEN.find(html) ?: return html
            val injectAt = m.range.last + 1
            html.substring(0, injectAt) + META + html.substring(injectAt)
        }
    }

    /**
     * Fetches the main-frame document and returns a rewritten WebResourceResponse
     * when the URL is a whatsapp main-frame HTML document. Returns null so the
     * caller falls through to the normal network path otherwise.
     */
    fun maybeInject(url: String?, isMainFrame: Boolean, userAgent: String?): WebResourceResponse? {
        if (!isMainFrame || url == null) return null
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        if (!url.contains("whatsapp.com", ignoreCase = true)) return null
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.requestMethod = "GET"
            if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            conn.setRequestProperty("Accept-Encoding", "identity")
            val existing = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            if (existing != null) conn.setRequestProperty("Cookie", existing)
            conn.connect()
            for ((key, values) in conn.headerFields) {
                if (key != null && key.equals("set-cookie", ignoreCase = true)) {
                    for (v in values) {
                        runCatching { CookieManager.getInstance().setCookie(url, v) }
                    }
                }
            }
            val ctype = conn.contentType ?: ""
            if (!ctype.contains("text/html", ignoreCase = true) && !ctype.contains("application/xhtml", ignoreCase = true)) {
                conn.disconnect()
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            val html = String(bytes, StandardCharsets.UTF_8)
            val rewritten = rewriteHtml(html)
            if (rewritten != html) {
                WebResourceResponse(
                    "text/html",
                    "utf-8",
                    ByteArrayInputStream(rewritten.toByteArray(StandardCharsets.UTF_8))
                )
            } else {
                null
            }
        }.getOrNull()
    }
}