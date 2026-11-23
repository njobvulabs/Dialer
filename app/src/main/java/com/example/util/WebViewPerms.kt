package com.example.util

import android.webkit.WebView

/**
 * WebView.clearPermissions() was removed from the API 36 compile SDK but still
 * exists on the devices this app targets (Android 13/14). Called via reflection
 * so the code compiles against 36 and works at runtime on the device.
 */
object WebViewPerms {
    fun clear(webView: WebView?) {
        if (webView == null) return
        runCatching {
            val method = WebView::class.java.getMethod("clearPermissions")
            method.invoke(webView)
        }
    }
}

