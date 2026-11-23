package com.example.util

import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Bridges the bundled `cookiejar@njdialer` WebExtension to yt-dlp.
 *
 * The web apps run on GeckoView (shared browser profile), so the old WebView
 * `CookieManager` no longer holds their logged-in cookies. The extension's
 * background page connects to the app with `runtime.connectNative` (content
 * scripts cannot reach the app directly on GeckoView), delivering this port;
 * `export(host)` asks the background to read that host's cookies and returns
 * them in Netscape format for yt-dlp to reuse.
 */
object CookieExport {
    private const val TAG = "CookieExport"
    private const val NATIVE_APP = "cookiejar"
    private const val TIMEOUT_MS = 2000L

    @Volatile
    private var bound = false


    @Volatile
    private var port: WebExtension.Port? = null

    private val pending = ConcurrentHashMap<String, CompletableFuture<String?>>()

    /** Attach the message delegate to the previously-installed extension. */
    fun bind(ext: WebExtension) {
        if (bound) return
        synchronized(this) {
            if (bound) return
            bound = true
            runCatching { ext.setMessageDelegate(delegate, NATIVE_APP) }
                .onFailure { android.util.Log.e(TAG, "setMessageDelegate failed", it) }
        }
    }

    private val delegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    val obj = (message as? JSONObject) ?: return
                    when (obj.optString("type")) {
                        "cookies" -> {
                            val host = obj.optString("host").lowercase()
                            pending.remove(host)?.complete(obj.optString("text").ifBlank { null })
                        }
                    }
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    if (this@CookieExport.port === port) this@CookieExport.port = null
                }
            })
            this@CookieExport.port = port
        }
    }

    /**
     * Export the logged-in cookies for [host] as a Netscape cookie file.
     * Blocking (up to [TIMEOUT_MS]); safe to call from the yt-dlp worker.
     * Returns null when no live port / cookie is available (caller falls back
     * to the legacy WebView cookie manager or anonymous download).
     */
    fun export(host: String): String? {
        val h = host.trim().lowercase().removePrefix("https://").removePrefix("http://")
        val p = port ?: return null
        val fut = CompletableFuture<String?>()
        pending[h] = fut
        return try {
            p.postMessage(JSONObject().put("cmd", "exportCookies").put("host", h))
            try {
                fut.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                null
            }
        } finally {
            pending.remove(h)
        }
    }
}