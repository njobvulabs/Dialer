package com.example.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast

/**
 * Minimal JS bridge: lets the injected page script surface diagnostics as toasts
 * (e.g. when the WhatsApp tap shim dispatches a fallback click), and lets it ask
 * the host to deliver a real (trusted) key event to the WebView so the page
 * receives an isTrusted=true activation (used for gesture-gated WhatsApp call
 * controls that reject synthetic mouse events).
 */
class WebAppJsBridge(private val context: Context) {

    /**
     * The WebView that should receive trusted key events. Set by each hosting
     * activity once its WebView is attached.
     */
    var webView: WebView? = null


    @JavascriptInterface
    fun report(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Like [report] but with a LONG toast, for diagnostics that must not be missed. */
    @JavascriptInterface
    fun reportLong(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Sends a real ENTER key press to the WebView. When the page has already
     * focused an element (via JS), Chromium turns ENTER on a focused
     * button/role=button into a trusted click event.
     */
    @JavascriptInterface
    fun activate() {
        Handler(Looper.getMainLooper()).post {
            val view = webView ?: return@post
            runCatching {
                view.requestFocus()
                view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }
    }
}
