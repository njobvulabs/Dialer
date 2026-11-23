package com.example.util

import android.webkit.WebView

/**
 * Android WebView appends an `X-Requested-With: <package name>` header to every
 * request. Google's sign-in JS/backend uses that header to detect (and block)
 * embedded WebViews ("This browser or app may not be secure"), even when the UA
 * string and client hints are spoofed as desktop Chrome. The platform API to
 * disable it (`WebView.setXRequestedWithHeaderLevel(WebView.X_REQUESTED_WITH_LEVEL_NONE)`,
 * API 33+) is `@hide` and therefore absent from the SDK stubs, so it must be
 * invoked via reflection; callers guard it with runCatching so a failure on any
 * device/WebView build is a silent no-op (the header is never re-added).
 */
object WebViewHeaders {
    /** `WebView.X_REQUESTED_WITH_LEVEL_NONE` (hidden constant, value 0). */
    private const val X_REQUESTED_WITH_LEVEL_NONE = 0

    fun stripXRequestedWith(wv: WebView) {
        runCatching {
            val m = WebView::class.java.getMethod(
                "setXRequestedWithHeaderLevel", Integer.TYPE
            )
            if (m.isAccessible) m.isAccessible = true
            m.invoke(wv, X_REQUESTED_WITH_LEVEL_NONE)

        }
    }
}