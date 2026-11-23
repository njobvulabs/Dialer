package com.example.ui.screens.webapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.WebAppHolder
import com.example.ui.components.AppIcons
import com.example.util.CaptureDiagnostics
import com.example.util.ChromeFingerprint
import com.example.util.FacebookChatShim
import com.example.util.QuickDownloadDialog
import com.example.util.ViewportDiagnostics
import com.example.util.WebViewHeaders
import com.example.util.WebViewPerms
import com.example.util.WhatsAppCallEnabler
import com.example.util.WhatsAppTapShim

/**
 * Web app module registry: the in-app web apps (Telegram, FB, YouTube, ...).
 * These render as Compose screens inside MainActivity so they live in the
 * always-foreground main process — like the in-app browser — and never reload
 * when the user exits to the hub and reopens them.
 */
data class WebAppModule(val id: String, val url: String, val title: String, val desktop: Boolean)

val WEB_APP_MODULES = listOf(
    WebAppModule("telegram", "https://web.telegram.org/a", "Telegram Web", true),
    WebAppModule("facebook", "https://www.facebook.com/", "Facebook", true),
    WebAppModule("youtube", "https://www.youtube.com/", "YouTube", false),
    WebAppModule("gemini", "https://gemini.google.com/", "Gemini", true),
    WebAppModule("tiktok", "https://www.tiktok.com/", "TikTok", true),
    WebAppModule("chatgpt", "https://chatgpt.com/", "ChatGPT", true),
    WebAppModule("perplexity", "https://www.perplexity.ai/", "Perplexity", true),
    WebAppModule("aistudio", "https://aistudio.google.com/", "AI Studio", true),
    WebAppModule("classroom", "https://classroom.google.com/", "Google Classroom", true),
    WebAppModule("meet", "https://meet.google.com/", "Google Meet", true),
    WebAppModule("drive", "https://drive.google.com/", "Google Drive", true),
    WebAppModule("docs", "https://docs.google.com/", "Google Docs", true),
    WebAppModule("gmail", "https://mail.google.com/", "Gmail", true),
    WebAppModule("claude", "https://claude.ai/new", "Claude", true),
    WebAppModule("grok", "https://grok.com/", "Grok", true),
    WebAppModule("photos", "https://photos.google.com/", "Google Photos", true),
    WebAppModule("copilot", "https://copilot.microsoft.com/", "Microsoft Copilot", true),
    WebAppModule("deepseek", "https://chat.deepseek.com/", "DeepSeek", true),
    WebAppModule("manus", "https://manus.im/", "Manus AI", true)
)

fun webAppModule(id: String): WebAppModule? = WEB_APP_MODULES.firstOrNull { it.id == id }

/**
 * Full-screen web app host embedded in MainActivity (always-foreground main
 * process). The WebView is held in [WebAppHolder] keyed by app id, so switching
 * apps or returning to the hub and reopening preserves each live page and never
 * reloads — matching the in-app browser's behavior on HiOS.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebAppScreen(appId: String, onExit: () -> Unit) {
    val module = webAppModule(appId)
    if (module == null) {
        onExit()
        return
    }

    val context = LocalContext.current
    val activity = remember { context.findActivity() }

    // Real status-bar inset, used to pin the chrome-toggle pill below the camera
    // at a constant position (WindowInsets.statusBars.getTop is independent of
    // the Scaffold's inset consumption, so this is reliable under edge-to-edge).
    val density = LocalDensity.current
    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(density).dp }

    var currentUrl by remember { mutableStateOf(module.url) }
    var webUserAgent by remember { mutableStateOf<String?>(null) }
    var captureEnabled by remember {
        mutableStateOf(
            context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .getBoolean("capture_enabled", true)
        )
    }
    var chromeVisible by remember { mutableStateOf(true) }
    var showExitDialog by remember { mutableStateOf(false) }
    var googleCacheCleared by remember { mutableStateOf(false) }
    var lastConsoleToastTime by remember { mutableLongStateOf(0L) }
    var waEnforcerReloaded by remember { mutableStateOf(false) }

    // File chooser + web-permission plumbing (bound to the long-lived host).
    var pendingUpload by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingPermission by remember { mutableStateOf<PermissionRequest?>(null) }
    var toggleTurningOn by remember { mutableStateOf(false) }

    // Fullscreen (HTML5 video) support.
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var customContainer by remember { mutableStateOf<FrameLayout?>(null) }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingUpload
        pendingUpload = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val clipData = data.clipData
            val uris = mutableListOf<Uri>()
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) clipData.getItemAt(i).uri?.let { uris.add(it) }
            } else {
                data.data?.let { uris.add(it) }
            }
            cb?.onReceiveValue(uris.toTypedArray())
        } else {
            cb?.onReceiveValue(null)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val req = pendingPermission
        pendingPermission = null
        val mic = grants[Manifest.permission.RECORD_AUDIO] == true
        val cam = grants[Manifest.permission.CAMERA] == true
        if (mic && cam) {
            if (toggleTurningOn) {
                toggleTurningOn = false
                captureEnabled = true
                saveCaptureEnabled(context, true)
                Toast.makeText(context, "Mic & camera on", Toast.LENGTH_SHORT).show()
            }
            req?.grant(req.resources)
        } else {
            if (toggleTurningOn) toggleTurningOn = false
            req?.deny()
        }
    }

    fun isWhatsAppHost(): Boolean =
        currentUrl.startsWith("https://", ignoreCase = true) &&
            Uri.parse(currentUrl).host?.contains("whatsapp.com", ignoreCase = true) == true

    fun isFacebookHost(): Boolean =
        currentUrl.startsWith("https://", ignoreCase = true) &&
            Uri.parse(currentUrl).host?.contains("facebook.com", ignoreCase = true) == true

    fun isGoogleHost(u: String?): Boolean {
        if (u.isNullOrBlank()) return false
        val host = runCatching { Uri.parse(u).host }.getOrNull() ?: return false
        return listOf(
            "mail.google.com", "accounts.google.com", "www.google.com", "google.com",
            "youtube.com", "gemini.google.com"
        ).any { host.contains(it, ignoreCase = true) }
    }

    fun applyGoogleCachePolicy(view: WebView?, u: String?) {
        if (view == null) return
        val google = isGoogleHost(u)
        view.settings.cacheMode = if (google) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        if (google) {
            if (!googleCacheCleared) {
                googleCacheCleared = true
                runCatching { view.clearCache(true) }
            }
        } else {
            googleCacheCleared = false
        }
    }

    fun resolveDownloadFilename(u: String, cd: String?, mime: String?): Pair<String, String> {
        var filename = URLUtil.guessFileName(u, cd, mime)
        val ext = MimeTypeMap.getFileExtensionFromUrl(u)
        val mimeFromExt = if (ext.isNotBlank()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) else null
        val resolvedMime = mime?.ifBlank { null } ?: mimeFromExt ?: "application/octet-stream"
        if (filename.isBlank() || filename == "downloadfile") filename = "download_${System.currentTimeMillis()}"
        return Pair(filename, resolvedMime)
    }

    val key = "webapp:$appId"
    val heldWebView = WebAppHolder.obtainOrCreate(key) {
        // Use the host Activity context (MainActivity is app-lifetime, so no leak)
        // to preserve WebView features like JS dialogs and autofill.
        WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    // Configure exactly once per app session (idempotent); the WebView object is
    // created/reused by obtainOrCreate, and the callbacks are bound to the live
    // host only at the first attach.
    var configRun by remember(key) { mutableStateOf(false) }
    if (!configRun) {
        configRun = true
    heldWebView.let { wv ->
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.javaScriptCanOpenWindowsAutomatically = true
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.useWideViewPort = !isWhatsAppHost()
        wv.settings.loadWithOverviewMode = !isWhatsAppHost()
        wv.settings.setSupportZoom(false)
        wv.settings.builtInZoomControls = false
        wv.settings.displayZoomControls = false
        wv.settings.allowFileAccess = true
        wv.settings.allowContentAccess = true
        wv.settings.userAgentString =
            WhatsAppCallEnabler.DESKTOP_UA
                .takeIf { module.desktop || isWhatsAppHost() }
                .let { it ?: wv.settings.userAgentString }
                .also { webUserAgent = it }

        runCatching {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) }
        }
        // Android WebView appends an `X-Requested-With: <package>` header to
        // every request; Google's sign-in JS detects that header and shows
        // "This browser or app may not be secure". Drop it so the app presents
        // exactly like a real browser (reflection: the API is @hide).
        WebViewHeaders.stripXRequestedWith(wv)
        runCatching { wv.removeJavascriptInterface("__igAndroid") }
        wv.addJavascriptInterface(
            com.example.util.WebAppJsBridge(context).also { it.webView = wv },
            "__igAndroid"
        )
        wv.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            val (name, _) = resolveDownloadFilename(url, contentDisposition, mimetype)
            try {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    setTitle(name)
                    if (mimetype.isNotBlank()) setMimeType(mimetype)
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }
                dm.enqueue(req)
                Toast.makeText(context, "Downloading $name to Download/", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val usesCapture = request.resources.any {
                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                }
                Toast.makeText(
                    context,
                    "Page asks: ${request.resources.joinToString().take(60)}",
                    Toast.LENGTH_SHORT
                ).show()
                if (usesCapture && !captureEnabled) {
                    request.deny()
                    return
                }
                if (!usesCapture) {
                    request.grant(request.resources)
                    return
                }
                val mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                val cam = context.checkSelfPermission(Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (mic && cam) {
                    request.grant(request.resources)
                } else {
                    pendingPermission = request
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                    )
                }
            }

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                pendingUpload?.onReceiveValue(null)
                pendingUpload = filePathCallback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                uploadLauncher.launch(intent)
                return true
            }

            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                val msg = message?.message().orEmpty()
                val low = msg.lowercase()
                val interesting = listOf(
                    "voip", "wasm", "relay", "rtc", "ice", "webrtc", "error", "fail"
                ).any { low.contains(it) }
                if (interesting) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastConsoleToastTime > 2500) {
                        lastConsoleToastTime = now
                        Toast.makeText(context, "console: ${msg.take(140)}", Toast.LENGTH_SHORT).show()
                    }
                }
                return false
            }

            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                val act = activity ?: return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                if (view == null) return
                view.setBackgroundColor(AndroidColor.BLACK)
                val container = FrameLayout(act).apply {
                    setBackgroundColor(AndroidColor.BLACK)
                    addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
                customContainer = container
                val decor = act.window?.decorView
                if (decor is ViewGroup) decor.addView(container)
                runCatching {
                    act.window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                }
            }

            override fun onHideCustomView() {
                runCatching { (customView?.parent as? ViewGroup)?.removeView(customView) }
                customContainer?.let { runCatching { (it.parent as? ViewGroup)?.removeView(it) } }
                customContainer = null
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                val act = activity
                if (act != null && act.window != null) {
                    runCatching { act.window!!.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN) }
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                if (resultMsg == null) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = heldWebView
                resultMsg.sendToTarget()
                return true
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (QuickDownloadDialog.isMediaUrl(url)) QuickDownloadDialog.logMediaUrl(url)
                if (url.contains("whatsapp.com", ignoreCase = true)) {
                    WhatsAppCallEnabler.maybeInject(url, request.isForMainFrame, webUserAgent)
                        ?.let { return it }
                }
                return null
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                url?.let { currentUrl = it }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString()
                applyGoogleCachePolicy(view, url)
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                applyGoogleCachePolicy(view, url)
                url?.let { currentUrl = it }
                if (url != null && url.contains("whatsapp.com", ignoreCase = true)) {
                    view?.settings?.useWideViewPort = false
                    view?.settings?.loadWithOverviewMode = false
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                runCatching { view?.evaluateJavascript(CaptureDiagnostics.JS, null) }
                runCatching { view?.evaluateJavascript(ChromeFingerprint.JS, null) }
                if (url?.contains("whatsapp.com", ignoreCase = true) == true) {
                    runCatching { view?.evaluateJavascript(WhatsAppCallEnabler.INJECT_JS, null) }
                    if (!waEnforcerReloaded) {
                        waEnforcerReloaded = true
                        runCatching {
                            view?.evaluateJavascript(WhatsAppCallEnabler.RELOAD_IF_NOT_INJECTED_JS, null)
                        }
                    }
                    runCatching { view?.evaluateJavascript(WhatsAppCallEnabler.RTC_DIAG_JS, null) }
                    runCatching { view?.evaluateJavascript(WhatsAppCallEnabler.ICE_WARM_JS, null) }
                    runCatching { view?.evaluateJavascript(WhatsAppCallEnabler.DIAG_JS, null) }
                    runCatching { view?.evaluateJavascript(ViewportDiagnostics.JS, null) }
                }
                if (isGoogleHost(url)) {
                    runCatching {
                        view?.evaluateJavascript(
                            "navigator.serviceWorker&&navigator.serviceWorker.getRegistrations&&" +
                                "navigator.serviceWorker.getRegistrations().then(function(rs){rs.forEach(function(r){r.unregister()})});",
                            null
                        )
                    }
                }
                if (WhatsAppTapShim.ENABLED &&
                    url?.contains(WhatsAppTapShim.HOST_MARKER, ignoreCase = true) == true
                ) {
                    runCatching { view?.evaluateJavascript(WhatsAppTapShim.JS, null) }
                }
                if (FacebookChatShim.ENABLED &&
                    url?.contains(FacebookChatShim.HOST_MARKER, ignoreCase = true) == true
                ) {
                    runCatching { view?.evaluateJavascript(FacebookChatShim.JS, null) }
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                runCatching { view?.destroy() }
                WebAppHolder.release(key)
                return true
            }
        }
    }
    } // end if (!configRun)

    // Load only when fresh; a reused holder WebView already shows its live page.
    DisposableEffect(key) {
        WebAppHolder.resume()
        val wv = WebAppHolder.peek(key)
        val loaded = runCatching { wv?.url }.getOrNull().orEmpty()
        if (loaded.isBlank() || loaded == "about:blank") {
            if (activity != null && !activity.isFinishing) {
                heldWebView.loadUrl(module.url)
            }
        }
        onDispose {
            // Detach from this AndroidView's parent group so the reused WebView
            // can be re-added to a new host next time without a parent conflict.
            WebAppHolder.detachFromParent(key)
        }
    }

    fun toggleCapture() {
        if (captureEnabled) {
            captureEnabled = false
            saveCaptureEnabled(context, false)
            Toast.makeText(context, "Mic & camera off", Toast.LENGTH_SHORT).show()
        } else {
            val hasPerms = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (hasPerms) {
                captureEnabled = true
                saveCaptureEnabled(context, true)
                Toast.makeText(context, "Mic & camera on", Toast.LENGTH_SHORT).show()
            } else {
                toggleTurningOn = true
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                )
            }
        }
        WebViewPerms.clear(heldWebView)
        runCatching { heldWebView.reload() }
    }

    fun hideFullscreenVideo() {
        if (customView == null) return
        runCatching { (customView?.parent as? ViewGroup)?.removeView(customView) }
        customContainer?.let { runCatching { (it.parent as? ViewGroup)?.removeView(it) } }
        customContainer = null
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        val act = activity
        if (act != null && act.window != null) {
            runCatching { act.window!!.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN) }
        }
    }

    fun exit() {
        // "Exit & keep running": return to the hub; the WebView stays alive in
        // WebAppHolder so a re-tap resumes the live page without reloading.
        hideFullscreenVideo()
        WebAppHolder.detachFromParent(key)
        WebAppHolder.pause()
        showExitDialog = false
        onExit()
    }

    fun closeApp() {
        // "Close web app": full teardown, so it reloads fresh next open.
        hideFullscreenVideo()
        WebAppHolder.release(key)
        showExitDialog = false
        onExit()
    }

    val hasPerms = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

    // System back exits fullscreen video first; MainActivity's handler handles
    // the rest (returning to the hub). Innermost enabled handler wins.
    BackHandler(enabled = customView != null) {
        hideFullscreenVideo()
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // WebView and the optional top bar live in a vertical flow so the page
        // resizes with the bar (fullscreen when it's hidden, fitting just below
        // it when shown) — matching how the WhatsApp web apps behave.
        Column(modifier = Modifier.fillMaxSize()) {
            if (chromeVisible) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp)
                        .padding(top = 8.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shadowElevation = 3.dp,
                    tonalElevation = 3.dp
                ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "← Back",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape = MaterialTheme.shapes.small)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .clickable {
                                runCatching {
                                    if (customView != null) hideFullscreenVideo()
                                    else if (heldWebView.canGoBack()) heldWebView.goBack()
                                    else exit()
                                }
                            }
                    )
                    Text(
                        "⬇",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape = MaterialTheme.shapes.small)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clickable {
                                runCatching {
                                    QuickDownloadDialog.show(
                                        activity ?: context as? Activity ?: return@clickable,
                                        currentUrl,
                                        onDetectVideo = { cb ->
                                            QuickDownloadDialog.detectInWebView(heldWebView) { cb(it) }
                                        }
                                    )
                                }
                            }
                    )
                    Text(
                        module.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    if (isFacebookHost()) {
                        Text(
                            "Fit",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape = MaterialTheme.shapes.small)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .clickable {
                                    runCatching {
                                        heldWebView.evaluateJavascript(
                                            "window.__igFbShim && window.__igFbShim.fit ? " +
                                                "window.__igFbShim.fit() : window.alert('shim not loaded yet')",
                                            null
                                        )
                                    }
                                }
                        )
                    }
                    Text(
                        when {
                            captureEnabled && hasPerms -> "Mic/Cam: ON"
                            captureEnabled && !hasPerms -> "⚠ Mic/Cam"
                            else -> "Mic/Cam: OFF"
                        },
                        color = if (captureEnabled && !hasPerms) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape = MaterialTheme.shapes.small)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .clickable { toggleCapture() }
                    )
                    Text(
                        "Exit",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), shape = MaterialTheme.shapes.small)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clickable { showExitDialog = true }
                    )
                }
            }
        }

        // The held WebView fills the remaining space below the top bar, so it
        // resizes with the bar (fullscreen when hidden) instead of overlaying it.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { heldWebView },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

        // Chrome-toggle handle: a FIXED overlay pill pinned just below the bottom
        // edge of the top bar, so it stays in one place (like WhatsApp) whether
        // the top bar is shown or hidden, and never sits over the camera or bar.
        // Large transparent touch target wrapping a small visual pill, so it is
        // easy to tap while looking unobtrusive.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = statusBarTop + 52.dp)
                .size(width = 56.dp, height = 32.dp)
                .clickable { chromeVisible = !chromeVisible },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (chromeVisible) "⌃" else "⌄",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape = MaterialTheme.shapes.small)
                    .padding(horizontal = 14.dp, vertical = 1.dp)
            )
        }

        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text("Exit web app") },
                text = {
                    Text(
                        when {
                            !isFacebookHost() && !isWhatsAppHost() && module.id != "telegram" ->
                                "Nothing to interrupt: reopening this app resumes the current page."
                            else -> "Reopening this app resumes the current page without reloading."
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = { exit() }) { Text("Exit & keep running") }
                },
                dismissButton = {
                    TextButton(onClick = { closeApp() }) { Text("Close web app") }
                }
            )
        }
    }
}

private fun saveCaptureEnabled(context: Context, on: Boolean) {
    context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("capture_enabled", on)
        .apply()
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
