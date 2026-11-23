package com.example.ui.screens.webapp

import android.content.Context
import com.example.BuildConfig
import com.example.ui.components.AppIcons
import com.example.ui.screens.browser.BrowserEngine
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File

/**
 * Process-wide GeckoRuntime holder for the 12 GeckoView web apps (Telegram,
 * Facebook, YouTube, Gemini, TikTok, ChatGPT,
 * Perplexity, AI Studio).
 *
 * The apps run in their OWN OS process (`:webapps`, hosted by
 * [GeckoWebAppActivity]) on their OWN Gecko profile directory, so the in-app
 * browser's "Clear Browsing Data" — which globally wipes the main-process
 * default profile — can never log the web apps out, and an unclean HiOS kill
 * can't drop this profile's sign-ins because the keep-alive foreground service
 * pins the `:webapps` process (the same pattern that makes WhatsApp 1/2 durable).
 *
 * GeckoView allows only ONE GeckoRuntime per process; this object must only be
 * touched from the `:webapps` process.
 */
object WebAppEngine {
    const val PROFILE_DIR = "webapps-profile"

    @Volatile
    private var runtime: GeckoRuntime? = null

    @Volatile
    private var njBrowserExtension: WebExtension? = null

    @Volatile
    private var cookieJarExtension: WebExtension? = null

    fun njBrowserExtension(): WebExtension? = njBrowserExtension

    fun cookieJarExtension(): WebExtension? = cookieJarExtension

    fun get(context: Context, profileDir: String? = null): GeckoRuntime {
        runtime?.let { return it }
        synchronized(this) {
            runtime?.let { return it }
            val appContext = context.applicationContext

            val contentBlocking = ContentBlocking.Settings.Builder()
                .antiTracking(ContentBlocking.AntiTracking.STRICT)
                .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                .build()

            val profile = File(appContext.filesDir, profileDir ?: PROFILE_DIR)
            runCatching { profile.mkdirs() }

            val settings = GeckoRuntimeSettings.Builder()
                .consoleOutput(BuildConfig.DEBUG)
                .remoteDebuggingEnabled(true)
                .contentBlocking(contentBlocking)
                .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_LIGHT)
                .arguments(arrayOf("-profile", profile.absolutePath))
                .build()

            val rt = GeckoRuntime.create(appContext, settings)

            // Auto-approve extension install/optional-permission prompts for the
            // bundled extensions (mirrors the browser).
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

            registerNjBrowser(rt)
            registerCookieJar(rt)
            purgeUbo(rt)

            runtime = rt
            return rt
        }
    }

    /** Install/enable the bundled page-controls extension (⬇ detect, FB fit). */
    private fun registerNjBrowser(rt: GeckoRuntime) {
        rt.webExtensionController.ensureBuiltIn(BrowserEngine.NJBROWSER_LOCATION, BrowserEngine.NJBROWSER_ID).accept(
            { ext ->
                if (ext != null) njBrowserExtension = ext
                android.util.Log.i(TAG, "nj-browser controls ready: ${ext?.id}")
            },
            { err ->
                android.util.Log.e(TAG, "nj-browser controls install failed", err)
            }
        )
    }

    /** Install/enable the bundled cookie-export extension for yt-dlp Quick Download. */
    private fun registerCookieJar(rt: GeckoRuntime) {
        rt.webExtensionController.ensureBuiltIn(BrowserEngine.COOKIEJAR_LOCATION, BrowserEngine.COOKIEJAR_ID).accept(
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

    /** Purge the previously-bundled uBlock Origin from this runtime's profile. */
    private fun purgeUbo(rt: GeckoRuntime) {
        val id = "uBlock0@raymondhill.net"
        runCatching {
            rt.webExtensionController.list().accept({ extensions ->
                extensions?.firstOrNull { it.id == id }?.let { ext ->
                    runCatching { rt.webExtensionController.uninstall(ext) }
                    android.util.Log.i(TAG, "purged uBlock Origin")
                }
            }, { })
        }
    }

    private const val TAG = "WebAppEngine"
}