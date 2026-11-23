package com.example.ui.screens.webapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.ui.components.landscapeContentWidth
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import com.example.service.WebAppKeepAliveService
import com.example.ui.components.AppIcons
import com.example.util.QuickDownloadDialog
import com.example.util.autoDownloadExternal
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import com.example.util.pickFileToCache
import java.io.File

/**
 * Google blocks OAuth from embedded Android WebViews ("This browser or app may
 * not be secure") no matter how the UA/client hints are spoofed or the
 * `X-Requested-With` header is stripped. But Google accepts real Firefox, and
 * the app's in-app GeckoView browser works. So ALL hub web apps (Telegram,
 * Facebook, YouTube, Gemini, TikTok, ChatGPT,
 * Perplexity, AI Studio) are hosted on a GeckoView in
 * [GeckoWebAppActivity], which runs in its OWN OS process (`:webapps`) with its
 * own Gecko profile via [WebAppEngine]. The in-app browser keeps its own
 * default profile, so the browser's "Clear Browsing Data" (a global profile
 * wipe) can never log the web apps out.
 *
 * Each app's GeckoSession is kept alive in [GeckoWebAppHolder] keyed by app id
 * (mirroring WebAppHolder), so exiting to the hub and reopening resumes the live
 * page without reloading.
 */
object GeckoWebAppHolder {
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, GeckoSession>()

    fun sessionFor(appId: String, context: Context, url: String, desktop: Boolean): GeckoSession {
        sessions[appId]?.let { return it }
        synchronized(this) {
            sessions[appId]?.let { return it }
            val session = GeckoSession(
                GeckoSessionSettings.Builder()
                    .usePrivateMode(false)
                    .useTrackingProtection(true)
                    .suspendMediaWhenInactive(false)
                    .userAgentMode(
                        if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                        else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                    )
                    .build()
            )
            val profileDir = CustomWebApps.profileDirFor(appId)
            session.open(WebAppEngine.get(context.applicationContext, profileDir))
            session.loadUri(url)
            sessions[appId] = session
            return session
        }
    }

    fun release(appId: String) {
        val session = sessions.remove(appId) ?: return
        runCatching { session.close() }
    }

    // The in-flight file prompt lives here (process-wide) rather than in Compose
    // state: launching the system picker backgrounds the Activity and aggressive
    // OEM ROMs (Tecno HiOS) recreate it on return, which would drop local state
    // and turn prompt.confirm() into a silent no-op (Gecko receives nothing and
    // the attachment looks dead). Exact mirror of GeckoWebActivity's holder.
    private var pendingPrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var pendingPromptResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null

    fun capturePendingFilePrompt(
        prompt: GeckoSession.PromptDelegate.FilePrompt,
        deferred: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
    ) {
        pendingPrompt = prompt
        pendingPromptResult = deferred
    }

    fun consumePendingFilePrompt():
        Pair<GeckoSession.PromptDelegate.FilePrompt?, GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?> {
        val p = pendingPrompt
        val d = pendingPromptResult
        pendingPrompt = null
        pendingPromptResult = null
        return p to d
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Drains a Gecko [WebResponse] straight into `Download/` and reports the result
 * with a single toast.
 *
 * Deliberately has no confirmation step: the user already tapped Download in
 * Gecko's own context menu, and the in-app "Download File?" dialog we used to
 * raise here is what took the web app down. That dialog was the *only* thing
 * holding the response, so tapping its Download button nulled the state, which
 * unmounted the composable out from under a body that was still being read, and
 * the `:webapps` process died — after the file had already been written, which
 * is why the user saw a successful download and a closed web app at once.
 *
 * Self-contained on purpose: nothing here reads or writes Compose state, so the
 * download survives the screen being recomposed or torn down. [Throwable] is
 * caught rather than just [Exception] so that an OOM or a channel error can't
 * take the whole web-app process down with it.
 */
/**
 * Full-screen web app host for Google-account apps running on the shared GeckoView
 * engine. Chrome bar mirrors WebAppScreen: ← Back / ↻ Reload / title / Exit, all
 * theme-colored, with an Exit two-choice dialog ("Exit & keep running" / "Close").
 */
@Composable
fun GeckoWebAppScreen(appId: String, onExit: () -> Unit) {
    val context = LocalContext.current
    val module = webAppModule(appId)
        ?: CustomWebApps.list(context).firstOrNull { it.id == appId }
        ?.let { WebAppModule(it.id, it.url, it.name, it.desktop) }
    if (module == null) {
        onExit()
        return
    }
    val activity = remember { context.findActivity() }
    val appPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    val useGlassmorphism by remember { mutableStateOf(appPrefs.getBoolean("ui_glassmorphism", false)) }

    // Cached status-bar height (mirrors GeckoWebActivity.statusBarHeight()).
    // Used to pin the chrome-toggle pill just below the camera at a constant
    // position — unlike live WindowInsets it doesn't collapse to 0 when
    // immersive mode hides the system bars.
    val density = LocalDensity.current
    val statusBarTop = remember {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) with(density) { context.resources.getDimension(id).toDp() } else 0.dp
    }

    var chromeVisible by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    val canGoBackState = remember { mutableStateOf(false) }
    var canGoBack by canGoBackState
    var isLoading by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf(module.url) }
    var detectedVideoUrl by remember { mutableStateOf<String?>(null) }
    var pendingDetect by remember {
        mutableStateOf<((QuickDownloadDialog.DetectedMedia?) -> Unit)?>(null)
    }
    var androidPermsRequest by remember {
        mutableStateOf<Pair<Array<String>, GeckoSession.PermissionDelegate.Callback>?>(null)
    }
    val geckoViewRef = remember { mutableStateOf<GeckoView?>(null) }
    // Ports from the bundled nj-browser extension, keyed by session (mirrors
    // GeckoBrowserScreen). This screen commands its own session's content script.
    val njPorts = remember { mutableStateMapOf<GeckoSession, WebExtension.Port>() }
    // Guards the one-time reload that re-injects the njbrowser content script
    // when a session loaded its page before the extension was ready.
    var retryReloadDone by remember(appId) { mutableStateOf(false) }

    val session = remember(appId) {
        GeckoWebAppHolder.sessionFor(appId, context, module.url, module.desktop)
    }

    // Wire the nj-browser extension's port for THIS session so the ⬇
    // (detectVideo) and FB "Fit" commands can reach the page. Content scripts
    // connect on every page load.
    LaunchedEffect(session) {
        var ext = WebAppEngine.njBrowserExtension()
        var tries = 0
        while (ext == null && tries < 100) {
            delay(100)
            ext = WebAppEngine.njBrowserExtension()
            tries++
        }
        val e = ext ?: return@LaunchedEffect
        // Content scripts cannot reach the app directly on GeckoView
        // (mozilla/geckoview#220): the extension's background relays each
        // content port to a native port and introduces it with {type:'ident',
        // url}. This screen owns exactly one session, so the newest ident wins
        // (a held-over port from before a navigation is disconnected).
        val bindTo = { s: GeckoSession, p: WebExtension.Port ->
            val old = njPorts[s]
            if (old != null && old !== p) runCatching { old.disconnect() }
            njPorts[s] = p
        }
        runCatching {
            e.setMessageDelegate(object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) {
                    val directS = port.sender?.session
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            (message as? JSONObject)?.let { m ->
                                when (m.optString("type")) {
                                    "ident", "connected" -> {
                                        val u = m.optString("url")
                                        if (u.isNotBlank() && (directS == null || njPorts[session] == null)) {
                                            bindTo(session, port)
                                        }
                                    }
                                    "detectVideoResult" -> {
                                        val found = QuickDownloadDialog.parseDetectedMedia(
                                            m.optString("value").ifBlank { null }
                                        )
                                        detectedVideoUrl = found?.url
                                        pendingDetect?.invoke(found)
                                        pendingDetect = null
                                    }
                                    "fitResult" -> {
                                        if (!m.optBoolean("value")) {
                                            Toast.makeText(
                                                context, "Fitting only works on Facebook pages",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            if (njPorts[session] === port) njPorts.remove(session)
                        }
                    })
                    if (directS != null) bindTo(directS, port)
                }
            }, "njbrowser")
        }
        // Content scripts only attach to documents that load once the extension
        // is ready. On a fresh process the first open can load the URL before
        // the njbrowser extension finishes installing, so that page never gets
        // the content script (no FB auto-fit / Fit / ⬇) for its whole lifetime.
        // Force ONE reload so the now-installed scripts land on this page.
        if (!njPorts.containsKey(session)) {
            for (i in 0 until 6) {
                delay(250)
                if (njPorts.containsKey(session)) break
            }
        }
        if (!njPorts.containsKey(session) && !retryReloadDone) {
            retryReloadDone = true
            android.util.Log.i("GeckoWebApp", "njbrowser content script missing on $appId; reloading once")
            runCatching { session.reload() }
        }
    }

    // The web app is visible again, so this foreground screen pins the main
    // process by itself: release any keep-alive started by "Exit & keep
    // running" (mirrors GeckoWebActivity.onResume → releaseOwn).
    LaunchedEffect(Unit) {
        WebAppKeepAliveService.releaseOwn(context)
    }

    // Runtime Android permission requests come from the session delegate (not a
    // composable), so funnel them through state + this launcher.
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val req = androidPermsRequest
        androidPermsRequest = null
        if (req != null) {
            val (perms, cb) = req
            if (perms.all { result[it] == true }) cb.grant() else cb.reject()
        }
    }
    LaunchedEffect(androidPermsRequest) {
        androidPermsRequest?.let { permLauncher.launch(it.first) }
    }

    // File attach (PromptDelegate.FilePrompt → system ACTION_OPEN_DOCUMENT
    // picker). GeckoView's FilePrompt.confirm() only supports file:// URIs
    // (Mozilla Bug 1515440/1591640: getFile() returns null for SAF content://
    // URIs — even FileProvider ones — and the pick is SILENTLY dropped). So
    // every picked file is copied into cache under its original display name +
    // extension and the prompt is confirmed with Uri.fromFile(file) file://
    // URIs — the same rule as WhatsApp's GeckoWebActivity.
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val (prompt, deferred) = GeckoWebAppHolder.consumePendingFilePrompt()
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val clipData = data.clipData
            val uris = mutableListOf<Uri>()
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i).uri?.let { uris.add(it) }
                }
            } else {
                data.data?.let { uris.add(it) }
            }
            if (uris.isEmpty()) {
                runCatching { Toast.makeText(context, "Could not attach selected files", Toast.LENGTH_LONG).show() }
                deferred?.complete(prompt?.dismiss())
            } else {
                val cached = uris.mapNotNull { pickFileToCache(context, it) }
                android.util.Log.i("GeckoWebApp", "file picker: cached=${cached.size} of ${uris.size}")
                val out = cached.map { Uri.fromFile(it.file) }
                if (out.isEmpty()) {
                    runCatching { Toast.makeText(context, "Could not attach selected files", Toast.LENGTH_LONG).show() }
                    deferred?.complete(prompt?.dismiss())
                } else {
                    try {
                        if (prompt != null) {
                            deferred?.complete(prompt.confirm(context, out.toTypedArray()))
                        } else {
                            // Prompt lost (Activity recreated while the picker
                            // was open / process restarted). A stale confirm is
                            // a silent no-op — surface it.
                            android.util.Log.w("GeckoWebApp", "file picker: prompt lost before confirm")
                            runCatching { Toast.makeText(context, "Attach expired, please try again", Toast.LENGTH_LONG).show() }
                        }
                    } catch (t: Throwable) {
                        android.util.Log.e("GeckoWebApp", "file prompt confirm failed", t)
                        deferred?.complete(prompt?.dismiss())
                    }
                }
            }
        } else {
            android.util.Log.i("GeckoWebApp", "file picker canceled")
            deferred?.complete(prompt?.dismiss())
        }
    }

    // (Re)bind the delegates each time the screen mounts so a persisted session
    // never keeps pointing at a dead Activity. The session itself keeps living
    // in GeckoWebAppHolder across mounts.
    DisposableEffect(appId) {
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                val current = isLoading
                if (!current) {
                    Handler(Looper.getMainLooper()).post { isLoading = true }
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                Handler(Looper.getMainLooper()).post { isLoading = false }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                Handler(Looper.getMainLooper()).post { isLoading = progress in 1..99 }
            }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                isInBFCache: Boolean
            ) {
                url?.let { u ->
                    Handler(Looper.getMainLooper()).post { currentUrl = u }
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                val b = canGoBack
                Handler(Looper.getMainLooper()).post { canGoBackState.value = b }
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val uri = request.uri
                val scheme = uri.substringBefore(":").lowercase()
                val external = scheme in setOf(
                    "tel", "mailto", "sms", "smsto", "mms", "geo", "market", "whatsapp", "tg", "intent"
                )
                if (external) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                // Popups (e.g. some Google OAuth windows) open inline in the same
                // session instead of dying in a new unmanaged window.
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    session.loadUri(uri)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return null
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                // Straight to disk, no second confirmation dialog. The user has
                // already tapped "Download" in Gecko's own context menu, so the
                // in-app "Download File?" prompt only added a step — and it cost
                // us the web app: the dialog held the only reference to the
                // WebResponse, so tapping Download set that state to null and
                // unmounted the composable while the response body was still
                // being drained from its channel, and the :webapps process went
                // down with it (the file itself had already been written).
                Handler(Looper.getMainLooper()).post {
                    runCatching { autoDownloadExternal(context, response) }
                        .onFailure {
                            Toast.makeText(
                                context,
                                "Download failed: ${it.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                }
            }

            // DOM fullscreen (a page's own requestFullscreen, e.g. a video
            // player): mirror the chrome-toggle immersion so a fullscreened
            // video is actually fullscreen (bar + system bars gone).
            override fun onFullScreen(session: GeckoSession, enabled: Boolean) {
                Handler(Looper.getMainLooper()).post {
                    val act = activity
                    if (act != null) {
                        val controller =
                            WindowInsetsControllerCompat(act.window, act.window.decorView)
                        if (enabled) {
                            chromeVisible = false
                            controller.hide(WindowInsetsCompat.Type.systemBars())
                            controller.systemBarsBehavior =
                                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        } else {
                            // Restore the bar too, otherwise the window comes back with the
                            // system bars visible but the chrome bar still hidden.
                            chromeVisible = true
                            controller.show(WindowInsetsCompat.Type.systemBars())
                        }
                    }
                }
            }
        }
        // Sensible defaults: allow the benign content permissions, ask for real
        // runtime permissions (mic/camera), grant media streams.
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                permission: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int> {
                val ptype = permission.permission
                val allow = ptype == GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION ||
                    ptype == GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION ||
                    ptype == GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE
                return GeckoResult.fromValue(
                    if (allow) {
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    } else {
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    }
                )
            }

            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: GeckoSession.PermissionDelegate.Callback
            ) {
                if (activity == null) {
                    callback.reject()
                    return
                }
                val missing = permissions.orEmpty().filter {
                    activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    callback.grant()
                } else {
                    Handler(Looper.getMainLooper()).post {
                        androidPermsRequest = missing.toTypedArray() to callback
                    }
                }
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) {
                callback.grant(video?.firstOrNull(), audio?.firstOrNull())
            }
        }
        // JS prompts (alert/confirm) via the platform dialog; native alerts are
        // safer than appcompat ones under the DeviceDefault theme (see AGENTS).
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val act = activity ?: return null
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        android.app.AlertDialog.Builder(act)
                            .setTitle(prompt.title ?: "Message")
                            .setMessage(prompt.message ?: "")
                            .setPositiveButton("OK") { d, _ -> d.dismiss(); prompt.dismiss() }
                            .setOnDismissListener { prompt.dismiss() }
                            .show()
                    }
                }
                return null
            }

            override fun onTextPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.TextPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val act = activity ?: return null
                Handler(Looper.getMainLooper()).post {
                    val input = android.widget.EditText(act).apply { setText(prompt.defaultValue) }
                    runCatching {
                        android.app.AlertDialog.Builder(act)
                            .setTitle(prompt.title ?: "Input")
                            .setMessage(prompt.message ?: "")
                            .setView(input)
                            .setPositiveButton("OK") { d, _ -> d.dismiss(); prompt.confirm(input.text.toString()) }
                            .setNegativeButton("Cancel") { d, _ -> d.dismiss(); prompt.dismiss() }
                            .setOnDismissListener { prompt.dismiss() }
                            .show()
                    }
                }
                return null
            }

            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val deferred = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                // Holder is process-wide so an Activity recreation while the
                // picker is open can't null the pending prompt (mirror of
                // GeckoWebActivity; see GeckoWebAppHolder.capturePendingFilePrompt).
                GeckoWebAppHolder.capturePendingFilePrompt(prompt, deferred)
                android.util.Log.i(
                    "GeckoWebApp",
                    "file prompt: type=${prompt.type} " +
                        "mimeTypes=${prompt.mimeTypes?.joinToString() ?: "null"} capture=${prompt.capture}"
                )
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        filePickerLauncher.launch(
                            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                            }
                        )
                    }.onFailure { t ->
                        android.util.Log.e("GeckoWebApp", "file picker launch failed", t)
                        GeckoWebAppHolder.consumePendingFilePrompt()
                            ?.second?.complete(prompt.dismiss())
                    }
                }
                return deferred
            }
        }
        onDispose {
            // Detach the session from this screen's view so the next mount's
            // setSession doesn't hit an already-attached view. "Exit & keep
            // running" keeps the session alive (no reload on reopen); "Close"
            // calls GeckoWebAppHolder.release.
            geckoViewRef.value?.releaseSession()
        }
    }

    fun keepRunning() {
        showExitDialog = false
        // Pin THIS process (the :webapps process in which this screen now runs)
        // with the keep-alive foreground service (W/A pattern): the web-app
        // profile must not be fate-packed by HiOS while backgrounded, or an
        // unclean kill drops the un-checkpointed Gecko writes and the next
        // reopen lands on the sign-in screen. startIntent resolves the service
        // class from the current process name (:webapps → KeepAliveWebappsService).
        // The service is released again when the page becomes visible.
        runCatching {
            WebAppKeepAliveService.releaseOthers(context)
            ContextCompat.startForegroundService(
                context,
                WebAppKeepAliveService.startIntent(context, module.title)
            )
        }
        onExit()
    }

    fun closeApp() {
        showExitDialog = false
        WebAppKeepAliveService.releaseOwn(context)
        GeckoWebAppHolder.release(appId)
        onExit()
    }

    fun sendPageCommand(cmd: String) {
        val port = njPorts[session]
        if (port != null) {
            runCatching { port.postMessage(JSONObject().put("cmd", cmd)) }
        } else {
            // No content-script port = the page loaded before the extension was
            // ready (or a kept-alive session from an older build). Reload so a
            // user's Fit/⬇ tap actually takes effect instead of silently dying.
            runCatching { session.reload() }
        }
    }

    fun isFacebookHost(): Boolean {
        val host = runCatching { Uri.parse(currentUrl).host?.lowercase() }.getOrNull() ?: ""
        return host.contains("facebook.com")
    }

    // System back (GeckoWebAppActivity / main-process fallback): page history
    // first, root → the same two-choice exit dialog as the top-bar Back/Exit.
    BackHandler {
        if (canGoBack) runCatching { session.goBack() }
        else showExitDialog = true
    }

    // Never leave the Activity with hidden system bars (e.g. "Exit & keep
    // running" backgrounds it, or the screen is closed after immersive mode).
    DisposableEffect(Unit) {
        onDispose {
            val act = activity
            if (act != null) {
                runCatching {
                    WindowInsetsControllerCompat(act.window, act.window.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (chromeVisible) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .landscapeContentWidth()
                        .align(Alignment.CenterHorizontally)
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp)
                        .padding(top = 8.dp),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 3.dp,
                    color = if (useGlassmorphism) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f) else MaterialTheme.colorScheme.surfaceContainer
                ) {
                    // fillMaxWidth + spacedBy(6) + non-wrapping single-line chips: the
                    // bar is measured with a decreasing max width left-to-right, so a
                    // wrapping Text as the LAST child ("Exit") would be measured with
                    // whatever is left over and soft-wrap one character per line,
                    // blowing the wrap-height card up to 4 lines tall.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "← Back",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                    shape = MaterialTheme.shapes.small
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                                .clickable {
                                    if (canGoBack) runCatching { session.goBack() }
                                    else showExitDialog = true
                                }
                        )
                        Text(
                            "⬇",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                    shape = MaterialTheme.shapes.small
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                                .clickable {
                                    runCatching {
                                        QuickDownloadDialog.show(
                                            activity ?: return@clickable,
                                            currentUrl,
                                            onDetectVideo = { cb ->
                                                // Fresh live detect through the nj-browser
                                                // port; result arrives in onPortMessage.
                                                detectedVideoUrl = null
                                                pendingDetect = cb
                                                sendPageCommand("detectVideo")
                                            }
                                        )
                                    }
                                }
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (isFacebookHost()) {
                            Text(
                                "Fit",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                        shape = MaterialTheme.shapes.small
                                    )
                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                                    .clickable { sendPageCommand("fit") }
                            )
                        }
                        Text(
                            "↻ Reload",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                    shape = MaterialTheme.shapes.small
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                                .clickable { runCatching { session.reload() } }
                        )
                        Text(
                            "Exit",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    shape = MaterialTheme.shapes.small
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                                .clickable { showExitDialog = true }
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { ctx ->
                        GeckoView(ctx).also { v ->
                            geckoViewRef.value = v
                            v.setSession(session)
                        }
                    },
                    update = { v ->
                        if (v.session != session) runCatching { v.setSession(session) }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                            .size(32.dp),
                        strokeWidth = 3.dp
                    )
                }
            }
        }

        // Chrome-toggle handle pill: pinned relative to the cached status-bar
        // height (exactly like WhatsApp's handle). When the floating chrome bar
        // is shown the pill floats below the card so it doesn't straddle the
        // card's top edge; when immersive it sits at the top of the page.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (chromeVisible) statusBarTop + 58.dp else statusBarTop + 6.dp)
                .size(width = 56.dp, height = 32.dp)
                .clickable {
                    chromeVisible = !chromeVisible
                    val act = activity
                    if (act != null) {
                        val controller =
                            WindowInsetsControllerCompat(act.window, act.window.decorView)
                        if (chromeVisible) {
                            controller.show(WindowInsetsCompat.Type.systemBars())
                        } else {
                            controller.hide(WindowInsetsCompat.Type.systemBars())
                            controller.systemBarsBehavior =
                                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (chromeVisible) "⌃" else "⌄",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        shape = MaterialTheme.shapes.small
                    )
                    .padding(horizontal = 14.dp, vertical = 1.dp)
            )
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Exit web app") },
            text = {
                Text("Reopening this app resumes the current page (sign-in stays).")
            },
            confirmButton = {
                TextButton(onClick = { keepRunning() }) { Text("Exit & keep running") }
            },
            dismissButton = {
                TextButton(onClick = { closeApp() }) { Text("Close web app") }
            }
        )
    }
}