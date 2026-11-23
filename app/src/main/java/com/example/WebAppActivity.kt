package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.service.WebAppKeepAliveService
import com.example.ui.components.AppIcons
import com.example.util.CaptureDiagnostics
import com.example.util.ChromeFingerprint
import com.example.util.FacebookChatShim
import com.example.util.ViewportDiagnostics
import com.example.util.WebAppJsBridge
import com.example.util.WebViewHeaders
import com.example.util.WebViewPerms
import com.example.util.WhatsAppCallEnabler
import com.example.util.WhatsAppTapShim
import java.io.File

/**
 * Lightweight, app-style WebView host for the Apps Hub web apps (WhatsApp Web,
 * Telegram Web, Facebook). Runs in the main process (like the in-app browser)
 * so it stays alive as long as NJ-Dialer is foreground and "Exit & keep
 * running" resumes without reload; it uses its own WebView data directory
 * suffix so web-app sessions stay independent of other browser data.
 */
class WebAppActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUFFIX = "suffix"
        private const val EXTRA_DESKTOP = "desktop"

        @Volatile
        private var dataDirAppliedForProcess = false

        fun intent(
            ctx: Context,
            url: String,
            title: String,
            suffix: String,
            desktop: Boolean
        ): Intent = Intent(ctx, WebAppActivity::class.java).apply {
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_SUFFIX, suffix)
            putExtra(EXTRA_DESKTOP, desktop)
        }
    }

    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var handleView: TextView
    private lateinit var micToggle: TextView
    private lateinit var fbShiftToggle: TextView
    private var currentUrl: String = "https://www.google.com"
    private var restoreStateBundle: Bundle? = null
    private var appTitle: String = "Web"
    private var desktopUa: Boolean = false
    private var webUserAgent: String? = null
    private var chromeVisible = false
    private var waEnforcerReloaded = false
    private var lastConsoleToastTime = 0L

    // HTML5 video fullscreen support (YouTube etc.)
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenContainer: FrameLayout? = null

    private var googleCacheCleared = false

    private var pendingUpload: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var askedPermissions = false
    private var captureEnabled = true
    private var toggleTurningOn = false
    private var diagnosticShown = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val req = pendingPermission
            pendingPermission = null
            val mic = grants[Manifest.permission.RECORD_AUDIO] == true
            val cam = grants[Manifest.permission.CAMERA] == true
            if (mic && cam) {
                if (toggleTurningOn) {
                    toggleTurningOn = false
                    setCaptureEnabled(true)
                    Toast.makeText(this, "Mic & camera on", Toast.LENGTH_SHORT).show()
                }
                req?.grant(req.resources)
            } else {
                if (toggleTurningOn) toggleTurningOn = false
                req?.deny()
                showPermissionHelp()
            }
            refreshMicToggle()
        }

    private val uploadLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = pendingUpload
            pendingUpload = null
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
                cb?.onReceiveValue(uris.toTypedArray())
            } else {
                cb?.onReceiveValue(null)
            }
        }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun requestWebPermissionsIfNeeded() {
        if (askedPermissions) return
        askedPermissions = true
        val needMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        val needCam = checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        if (needMic || needCam) {
            root.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                    )
                }
            }, 600L)
        }
    }

    private fun showPermissionHelp() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Mic & camera access")
            .setMessage(
                "This app needs microphone & camera access for voice messages, videos and calls. " +
                    "If Android has stopped showing the popup (\"Don't allow\"), enable them under app settings."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
            .setNeutralButton("Try Again") { _, _ ->
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                )
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun refreshMicToggle() {
        if (!::micToggle.isInitialized) return
        val hasPerms = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        micToggle.text = when {
            captureEnabled && hasPerms -> "Mic/Cam: ON"
            captureEnabled && !hasPerms -> "⚠ Mic/Cam"
            else -> "Mic/Cam: OFF"
        }
        micToggle.setTextColor(if (captureEnabled && !hasPerms) Color.YELLOW else Color.WHITE)
    }

    private fun hasCapturePermissions(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showDiagnosticOnce() {
        if (diagnosticShown) return
        diagnosticShown = true
        val webViewVer = if (Build.VERSION.SDK_INT >= 26) {
            runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull() ?: "?"
        } else {
            "?"
        }
        Toast.makeText(
            this,
            "Android ${Build.VERSION.SDK_INT} • WebView $webViewVer • mic/cam=${if (hasCapturePermissions()) "granted" else "MISSING"}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun setCaptureEnabled(on: Boolean) {
        captureEnabled = on
        getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("capture_enabled", on)
            .apply()
        refreshMicToggle()
        if (::webView.isInitialized) {
            WebViewPerms.clear(webView)
            webView.reload()
        }
    }

    private fun toggleCapture() {
        if (captureEnabled) {
            setCaptureEnabled(false)
            Toast.makeText(this, "Mic & camera off", Toast.LENGTH_SHORT).show()
        } else {
            if (hasCapturePermissions()) {
                setCaptureEnabled(true)
                Toast.makeText(this, "Mic & camera on", Toast.LENGTH_SHORT).show()
            } else {
                toggleTurningOn = true
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                )
            }
        }
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun isWhatsAppHost(): Boolean =
        currentUrl.startsWith("https://", ignoreCase = true) &&
            Uri.parse(currentUrl).host?.contains("whatsapp.com", ignoreCase = true) == true

    private fun isFacebookHost(): Boolean =
        currentUrl.startsWith("https://", ignoreCase = true) &&
            Uri.parse(currentUrl).host?.contains("facebook.com", ignoreCase = true) == true

    private fun refreshFbShiftToggle() {
        if (::fbShiftToggle.isInitialized) {
            fbShiftToggle.visibility = if (isFacebookHost()) View.VISIBLE else View.GONE
        }
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setChromeVisible(visible: Boolean) {
        if (customView != null) return
        chromeVisible = visible
        if (::topBar.isInitialized) topBar.visibility = if (visible) View.VISIBLE else View.GONE
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun toggleChrome() {
        setChromeVisible(!chromeVisible)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreStateBundle = savedInstanceState
        enterImmersive()
        setChromeVisible(false)

        val extras = intent ?: Intent()
        currentUrl = extras.getStringExtra(EXTRA_URL) ?: currentUrl
        appTitle = extras.getStringExtra(EXTRA_TITLE) ?: "Web"
        desktopUa = extras.getBooleanExtra(EXTRA_DESKTOP, false)
        captureEnabled = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getBoolean("capture_enabled", true)

        if (!dataDirAppliedForProcess) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { WebView.setDataDirectorySuffix(extras.getStringExtra(EXTRA_SUFFIX) ?: "webapps") }
            }
            dataDirAppliedForProcess = true
        }

        setupUi(extras.getStringExtra(EXTRA_TITLE) ?: "Web")
        refreshMicToggle()
        showDiagnosticOnce()
        requestWebPermissionsIfNeeded()

        onBackPressedDispatcher.addCallback(this) {
            when {
                customView != null -> hideFullscreenVideo()
                webView.canGoBack() -> webView.goBack()
                else -> finish()
            }
        }
    }

    private fun showFullscreenVideo(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        if (view == null) return

        view.setBackgroundColor(Color.BLACK)
        topBar.visibility = View.GONE
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        fullscreenContainer = container
        val decor = window.decorView
        if (decor is ViewGroup) decor.addView(container)
        enterImmersive()
    }

    private fun hideFullscreenVideo() {
        if (customView == null) return
        runCatching { (customView?.parent as? ViewGroup)?.removeView(customView) }
        fullscreenContainer?.let { runCatching { (it.parent as? ViewGroup)?.removeView(it) } }
        fullscreenContainer = null
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        topBar.visibility = if (chromeVisible) View.VISIBLE else View.GONE
    }

    // Hosts that share Google's session cookies / redirect flows (YouTube,
    // Gemini, Google search). On these we bypass the WebView cache entirely:
    // cached redirect responses are the classic cause of ERR_TOO_MANY_REDIRECTS
    // on Google's login flows.
    private fun isGoogleHost(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return false
        return listOf(
            "mail.google.com",
            "accounts.google.com",
            "www.google.com",
            "google.com",
            "youtube.com",
            "gemini.google.com"
        ).any { host.contains(it, ignoreCase = true) }
    }

    private fun applyGoogleCachePolicy(view: WebView?, url: String?) {
        if (view == null) return
        val google = isGoogleHost(url)
        view.settings.cacheMode = if (google) {
            WebSettings.LOAD_NO_CACHE
        } else {
            WebSettings.LOAD_DEFAULT
        }
        if (google) {
            if (!googleCacheCleared) {
                googleCacheCleared = true
                runCatching { view.clearCache(true) }
            }
        } else {
            googleCacheCleared = false
        }
    }

    override fun onResume() {
        super.onResume()
        refreshMicToggle()
        WebAppHolder.resume()
        if (pendingRebuild) {
            pendingRebuild = false
            runOnUiThread { attachWebView() }
        }
    }

    private var isVisible = false
    private var pendingRebuild = false

    override fun onStart() {
        super.onStart()
        isVisible = true
        runCatching { webView.resumeTimers() }
        // Now that the page is visible again it no longer needs the keep-alive
        // foreground service (a foreground activity pins its own process).
        WebAppKeepAliveService.releaseOwn(this)
    }

    override fun onStop() {
        super.onStop()
        isVisible = false
        runCatching { webView.pauseTimers() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Only release the WebView's engine memory when we are about to be
        // killed (critical OOM), NOT on a normal background. Letting the page
        // stay live while backgrounded is what lets "Exit & keep running"
        // resume without reloading.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && !isVisible) {
            runCatching { webView.freeMemory() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_URL)?.let { newUrl ->
            val loaded = runCatching { webView.url }.getOrNull().orEmpty()
            // When reopening an already-loaded web app (Exit & keep running),
            // don't force a reload — bring the live page back as-is. Only load
            // the target URL if the WebView is fresh (nothing loaded yet).
            if (::webView.isInitialized && (loaded.isBlank() || loaded == "about:blank")) {
                currentUrl = newUrl
                refreshFbShiftToggle()
                webView.loadUrl(newUrl)
            }
        }
    }

    private fun openQuickDownload() {
        com.example.util.QuickDownloadDialog.show(
            this, currentUrl,
            onDetectVideo = { cb ->
                com.example.util.QuickDownloadDialog.detectInWebView(webView) { cb(it) }
            }
        )
    }

    /**
     * Two-choice Exit for the top-bar Exit button:
     *  - keep the web app alive in its background task and return to NJ-Dialer
     *    (reopening resumes the live page via onNewIntent with the same URL), or
     *  - fully close the web app (reloads next time).
     */
    private fun promptExit() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Exit web app")
            .setItems(arrayOf("Exit & keep running", "Close web app")) { _, which ->
                when (which) {
                    0 -> exitToNjDialer()
                    1 -> {
                        WebAppKeepAliveService.releaseOwn(this)
                        finish()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exitToNjDialer() {
        // Keep this web app's process alive so reopening resumes without reload,
        // releasing any previously kept-alive web app (last-used only).
        runCatching {
            WebAppKeepAliveService.releaseOthers(this)
            ContextCompat.startForegroundService(
                this,
                WebAppKeepAliveService.startIntent(this, appTitle)
            )
        }
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
            )
        }
    }

    private fun setupUi(title: String) {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(4.dp(), statusBarHeight(), 4.dp(), 4.dp())
        }

        val back = TextView(this).apply {
            text = "←  Back"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
            setOnClickListener {
                if (customView != null) hideFullscreenVideo()
                else if (webView.canGoBack()) webView.goBack()
                else finish()
            }
        }

        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(8.dp(), 0, 0, 0)
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }

        micToggle = TextView(this).apply {
            text = if (captureEnabled) "Mic/Cam: ON" else "Mic/Cam: OFF"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
            setOnClickListener { toggleCapture() }
        }

        fbShiftToggle = TextView(this).apply {
            text = "Fit"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
            visibility = View.GONE
            setOnClickListener {
                runCatching {
                    webView.evaluateJavascript(
                        "window.__igFbShim && window.__igFbShim.fit ? " +
                            "window.__igFbShim.fit() : window.alert('shim not loaded yet')",
                        null
                    )
                }
            }
        }

        val exit = TextView(this).apply {
            text = "Exit"
            setTextColor(Color.parseColor("#FFD600"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
            setOnClickListener { promptExit() }
        }

        val dlToggle = TextView(this).apply {
            text = "⬇"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(14.dp(), 6.dp(), 14.dp(), 6.dp())
            setOnClickListener { openQuickDownload() }
        }

        topBar.addView(back)
        topBar.addView(dlToggle)
        topBar.addView(titleView)
        topBar.addView(spacer)
        topBar.addView(fbShiftToggle)
        topBar.addView(micToggle)
        topBar.addView(exit)
        content.addView(topBar)
        root.addView(content)

        handleView = TextView(this).apply {
            text = "⌄"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.argb(90, 255, 255, 255))
            setBackgroundColor(Color.parseColor("#1A000000"))
            setPadding(14.dp(), 4.dp(), 14.dp(), 4.dp())
            setOnClickListener { toggleChrome() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = (statusBarHeight() + 6.dp())
            }
        }
        root.addView(handleView)

        setContentView(root)

        attachWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun attachWebView() {
        if (isFinishing || isDestroyed) return
        // Reuse the process-wide live WebView (survives Activity destruction on
        // aggressive ROMs); only create it the first time. Keeps "Exit & keep
        // running" from reloading: the loaded page lives in WebAppHolder.
        WebAppHolder.ensureDataDir(this, "webapps")
        val key = holderKey()
        if (::webView.isInitialized) WebAppHolder.detachFromParent(key)

        webView = WebAppHolder.obtainOrCreate(key) { WebView(this) }

        configureWebView(webView)

        // Ensure the (possibly reused) WebView carries the right layout params.
        webView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        content.addView(webView)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        wv.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = !isWhatsAppHost()
            settings.loadWithOverviewMode = !isWhatsAppHost()
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.userAgentString =
                WhatsAppCallEnabler.DESKTOP_UA
                    .takeIf { desktopUa || isWhatsAppHost() }
                    .let { it ?: settings.userAgentString }
                    .also { webUserAgent = it }

            runCatching {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }
            // Keep the renderer process alive under memory pressure.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) }
            }
            // Drop the `X-Requested-With: <package>` header Google uses to detect
            // (and block sign-in on) embedded WebViews.
            WebViewHeaders.stripXRequestedWith(this)
            // Re-bound on every attach: these callbacks capture this activity
            // instance, so a reused (holder) WebView must not keep pointing at
            // the previous, possibly-destroyed activity. remove-first avoids the
            // addJavascriptInterface double-add throw.
            runCatching { removeJavascriptInterface("__igAndroid") }
            addJavascriptInterface(WebAppJsBridge(this@WebAppActivity).also { it.webView = this }, "__igAndroid")
            setDownloadListener { url, _, contentDisposition, mimetype, _ ->
                val (name, _) = resolveDownloadFilename(url, contentDisposition, mimetype)
                try {
                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val req = DownloadManager.Request(Uri.parse(url)).apply {
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                        setTitle(name)
                        if (mimetype.isNotBlank()) setMimeType(mimetype)
                        setAllowedOverMetered(true)
                        setAllowedOverRoaming(true)
                    }
                    dm.enqueue(req)
                    Toast.makeText(this@WebAppActivity, "Downloading $name to Download/", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@WebAppActivity, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    val usesCapture = request.resources.any {
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                    }
                    Toast.makeText(
                        this@WebAppActivity,
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
                    val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    val cam = checkSelfPermission(Manifest.permission.CAMERA) ==
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

                override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                    showFullscreenVideo(view, callback)
                }

                override fun onHideCustomView() {
                    hideFullscreenVideo()
                }

                // Surface the call engine's console output (voip/wasm/relay/ICE
                // errors) as toasts so a failed WhatsApp call can be diagnosed
                // without a desktop browser.
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
                            Toast.makeText(
                                this@WebAppActivity,
                                "console: ${msg.take(140)}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    return false
                }

                // Some flows (e.g. call/meeting links) open a new window via
                // window.open; without this the navigation silently dies.
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    if (resultMsg == null) return false
                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                    transport.webView = this@WebAppActivity.webView
                    resultMsg.sendToTarget()
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    if (com.example.util.QuickDownloadDialog.isMediaUrl(url)) {
                        com.example.util.QuickDownloadDialog.logMediaUrl(url)
                    }
                    if (url.contains("whatsapp.com", ignoreCase = true)) {
                        WhatsAppCallEnabler.maybeInject(url, request.isForMainFrame, webUserAgent)
                            ?.let { return it }
                    }
                    return null
                }

                // SPA navigations (YouTube watch pages, IG reels, FB permalinks)
                // never fire onPageStarted — this one does.
                override fun doUpdateVisitedHistory(
                    view: WebView?,
                    url: String?,
                    isReload: Boolean
                ) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    url?.let { currentUrl = it }
                    refreshFbShiftToggle()
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
                    refreshFbShiftToggle()
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
                                view?.evaluateJavascript(
                                    WhatsAppCallEnabler.RELOAD_IF_NOT_INJECTED_JS,
                                    null
                                )
                            }
                        }
                        runCatching { view?.evaluateJavascript(WhatsAppCallEnabler.RTC_DIAG_JS, null) }
                        runCatching { view?.evaluateJavascript(WhatsAppCallEnabler.ICE_WARM_JS, null) }
                        runCatching { view?.evaluateJavascript(WhatsAppCallEnabler.DIAG_JS, null) }
                        runCatching { view?.evaluateJavascript(ViewportDiagnostics.JS, null) }
                    }
                    // Stale service workers are a documented cause of Chromium
                    // redirect loops on Google login flows; unregister them.
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

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    // The renderer is gone; the held WebView is unusable. Drop the
                    // dead instance now, but defer rebuilding until the Activity is
                    // visible again so a background HiOS renderer kill doesn't start
                    // a silent home-page reload while the user is elsewhere. On
                    // resume the fresh WebView restores the last URL (live DOM is
                    // unrecoverable once the renderer is dead).
                    runCatching { view?.destroy() }
                    WebAppHolder.release(holderKey())
                    if (isVisible) {
                        runOnUiThread { attachWebView() }
                    } else {
                        pendingRebuild = true
                    }
                    return true
                }
            }
            // Only load when the WebView is fresh or a *different* web app
            // (different site) was requested. A reused holder WebView showing the
            // same site keeps its live page/history, so reopen with "Exit & keep
            // running" resumes without reloading.
            val heldUrl = url.orEmpty()
            val requestedUrl = currentUrl
            val isFresh = heldUrl.isBlank() || heldUrl == "about:blank" ||
                !sameWebAppHost(heldUrl, requestedUrl)
            if (isFresh) {
                val saved = restoreStateBundle
                restoreStateBundle = null
                // If we have preserved WebView state (activity was recreated while
                // the OS kept the app alive), restore URL + history + scroll.
                val restored = if (saved != null) runCatching { restoreState(saved) }.getOrNull() else null
                if (restored != null && restored.size > 0) {
                    runCatching { webView.url }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { currentUrl = it }
                } else {
                    loadUrl(requestedUrl)
                }
            } else {
                // Reuse: the page is already live; keep the running session.
                restoreStateBundle = null
            }
        }
    }

    /** True when two URLs belong to the same site (host matches at the top level). */
    private fun sameWebAppHost(a: String, b: String): Boolean {
        fun host(u: String): String {
            val h = runCatching { java.net.URI(u).host }.getOrNull()?.lowercase() ?: return ""
            val parts = h.split(".").filter { it.isNotBlank() }
            return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else h
        }
        val ha = host(a)
        val hb = host(b)
        return ha.isNotBlank() && ha == hb
    }


    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) {
            runCatching { webView.saveState(outState) }
        }
    }

    override fun onPause() {
        super.onPause()
        hideFullscreenVideo()
        runCatching { CookieManager.getInstance().flush() }
        WebAppHolder.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideFullscreenVideo()
        runCatching { CookieManager.getInstance().flush() }
        // isFinishing distinguishes a user-driven "Close web app" (finish() ->
        // true, fully tear down the singleton) from the OS/HiOS destroying the
        // backgrounded Activity (false) — in the latter case keep the WebView
        // alive in WebAppHolder so reopening resumes without reloading.
        if (isFinishing) {
            WebAppHolder.release(holderKey())
        } else {
            WebAppHolder.detachFromParent(holderKey())
        }
    }

    private fun resolveDownloadFilename(
        url: String,
        contentDisposition: String?,
        mimeType: String?
    ): Pair<String, String> {
        var filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val ext = MimeTypeMap.getFileExtensionFromUrl(url)
        val mimeFromExt = if (ext.isNotBlank()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) else null
        val resolvedMime = mimeType?.ifBlank { null } ?: mimeFromExt ?: "application/octet-stream"
        if (filename.isBlank() || filename == "downloadfile") {
            filename = "download_${System.currentTimeMillis()}"
        }
        return Pair(filename, resolvedMime)
    }

    /** Stable key for the process-wide WebAppHolder; one key per web app. */
    private fun holderKey(): String =
        appTitle.ifBlank { currentUrl.ifBlank { "webapp" } }
}
