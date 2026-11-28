package com.example.ui.screens.browser

import android.content.Context
import com.example.BuildConfig
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Process-wide GeckoRuntime holder for the main browser.
 *
 * GeckoView allows only ONE GeckoRuntime per process; the :whatsapp/:whatsapp2
 * processes hold their own copies inside GeckoWebActivity, and this object owns
 * the one used by GeckoBrowserScreen in the main process.
 */
object BrowserEngine {
    private const val TAG = "BrowserEngine"

    /** Previously-bundled uBlock Origin id, kept only so existing profiles can
     *  have it purged (the xpi is no longer shipped). */
    private const val UBLOCK_ID = "uBlock0@raymondhill.net"

    const val NJBROWSER_LOCATION = "resource://android/assets/webExtensions/njbrowser/"
    const val NJBROWSER_ID = "njbrowser@njdialer"
    // WhatsApp pane extension removed
    const val COOKIEJAR_LOCATION = "resource://android/assets/webExtensions/cookiejar/"
    const val COOKIEJAR_ID = "cookiejar@njdialer"

    @Volatile
    private var runtime: GeckoRuntime? = null

    @Volatile
    private var njBrowserExtension: WebExtension? = null

    @Volatile
    private var cookieJarExtension: WebExtension? = null

    fun njBrowserExtension(): WebExtension? = njBrowserExtension

    fun cookieJarExtension(): WebExtension? = cookieJarExtension

    fun get(context: Context): GeckoRuntime {
        runtime?.let { return it }
        synchronized(this) {
            runtime?.let { return it }
            val appContext = context.applicationContext

            val contentBlocking = ContentBlocking.Settings.Builder()
                .antiTracking(ContentBlocking.AntiTracking.STRICT)
                .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                .build()

            val settings = GeckoRuntimeSettings.Builder()
                .consoleOutput(BuildConfig.DEBUG)
                .remoteDebuggingEnabled(BuildConfig.DEBUG)
                .contentBlocking(contentBlocking)
                .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_LIGHT)
                .build()

            val rt = GeckoRuntime.create(appContext, settings)

            // Auto-approve extension install/optional-permission prompts for the
            // bundled extensions (they ship with the app).
            rt.webExtensionController.promptDelegate =
                object : WebExtensionController.PromptDelegate {
                    override fun onInstallPromptRequest(
                        extension: WebExtension,
                        permissions: Array<String>,
                        origins: Array<String>,
                        dataCollectionPermissions: Array<String>
                    ): GeckoResult<WebExtension.PermissionPromptResponse> =
                        GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, true, null))

                    override fun onOptionalPrompt(
                        extension: WebExtension,
                        permissions: Array<String>,
                        origins: Array<String>,
                        dataCollectionPermissions: Array<String>
                    ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }

            // Remove the old bundled uBlock Origin from existing profiles.
            purgeUbo(rt)
            // Registers the page-control extension (fullscreen video, page zoom).
            registerNjBrowser(rt)
            // Registers the cookie-export extension so yt-dlp quick-downloads
            // carry the logged-in Gecko session cookies.
            registerCookieJar(rt)

            runtime = rt
            return rt
        }
    }

    /** Install/enable the bundled page-controls extension. */
    private fun registerNjBrowser(rt: GeckoRuntime) {
        rt.webExtensionController.ensureBuiltIn(NJBROWSER_LOCATION, NJBROWSER_ID).accept(
            { ext ->
                if (ext != null) njBrowserExtension = ext
                android.util.Log.i(TAG, "nj-browser controls ready: ${ext?.id}")
            },
            { err ->
                android.util.Log.e(TAG, "nj-browser controls install failed", err)
            }
        )
    }

    /** Install/enable the bundled cookie-export extension, then bind it so the
     *  app can request Netscape-format cookies for yt-dlp. */
    private fun registerCookieJar(rt: GeckoRuntime) {
        rt.webExtensionController.ensureBuiltIn(COOKIEJAR_LOCATION, COOKIEJAR_ID).accept(
            { ext ->
                if (ext != null) {
                    cookieJarExtension = ext
                    com.example.util.CookieExport.bind(ext)
                }
                android.util.Log.i(TAG, "cookie exporter ready: ${ext?.id}")
            },
            { err ->
                android.util.Log.e(TAG, "cookie exporter install failed", err)
            }
        )
    }

    /** Purge the previously-bundled uBlock Origin from an existing profile. */
    private fun purgeUbo(rt: GeckoRuntime) {
        runCatching {
            rt.webExtensionController.list().accept({ extensions ->
                extensions?.firstOrNull { it.id == UBLOCK_ID }?.let { ext ->
                    runCatching { rt.webExtensionController.uninstall(ext) }
                    android.util.Log.i(TAG, "purged uBlock Origin")
                }
            }, { })
        }
    }

    /** Apply the website color-scheme preference (dark mode for web content). */
    fun applyWebsiteColorScheme(dark: Boolean) {
        runCatching {
            runtime?.settings?.setPreferredColorScheme(
                if (dark) {
                    GeckoRuntimeSettings.COLOR_SCHEME_DARK
                } else {
                    GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
                }
            )
        }
    }

    /**
     * Clear browsing data. cookies: cookies + auth sessions;
     * siteData: DOM storage + site settings/permissions; cache: network + image caches.
     */
    fun clearBrowsingData(cookies: Boolean, siteData: Boolean, cache: Boolean): GeckoResult<Void>? {
        val rt = runtime ?: return null
        var flags = 0L
        if (cookies) {
            flags = flags or StorageController.ClearFlags.COOKIES or
                StorageController.ClearFlags.AUTH_SESSIONS
        }
        if (siteData) {
            flags = flags or StorageController.ClearFlags.DOM_STORAGES or
                StorageController.ClearFlags.SITE_SETTINGS
        }
        if (cache) flags = flags or StorageController.ClearFlags.ALL_CACHES
        if (flags == 0L) return null
        return try {
            rt.storageController.clearData(flags)
        } catch (e: Exception) {
            null
        }
    }
}
