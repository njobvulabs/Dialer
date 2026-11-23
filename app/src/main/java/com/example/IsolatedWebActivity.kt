package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.service.WebAppKeepAliveService
import com.example.ui.components.AppIcons
import com.example.util.CaptureDiagnostics
import com.example.util.ViewportDiagnostics
import com.example.util.WebAppJsBridge
import com.example.util.copyUriToCache
import com.example.util.WebViewPerms
import com.example.util.WhatsAppCallEnabler
import com.example.util.WhatsAppTapShim

/**
 * Runs web.whatsapp.com in its own OS process + its own WebView data directory
 * so a second WhatsApp account can stay logged in alongside the main one.
 */
class IsolatedWebActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var root: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var micToggle: TextView
    private var webUserAgent: String? = null
    private var chromeVisible = true
    private val chromeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideChromeRunnable = Runnable { setChromeVisible(false) }

    private var waEnforcerReloaded = false

    private var lastConsoleToastTime = 0L
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
                val cacheFiles = uris.mapNotNull { copyUriToCache(this, it) }
                if (cacheFiles.isNotEmpty()) {
                    cb?.onReceiveValue(cacheFiles.map { Uri.fromFile(it) }.toTypedArray())
                } else {
                    cb?.onReceiveValue(null)
                }
            } else {
                cb?.onReceiveValue(null)
            }
        }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

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

    private fun openQuickDownload() {
        val pageUrl = runCatching { webView.url }.getOrNull().orEmpty()
        com.example.util.QuickDownloadDialog.show(
            this, pageUrl,
            onDetectVideo = { cb ->
                com.example.util.QuickDownloadDialog.detectInWebView(webView) { cb(it) }
            }
        )
    }

    /**
     * Two-choice Exit for the top-bar Exit button:
     *  - keep the web app alive in the background and return to NJ-Dialer, or
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
        runCatching {
            WebAppKeepAliveService.releaseOthers(this)
            androidx.core.content.ContextCompat.startForegroundService(
                this,
                WebAppKeepAliveService.startIntent(this, "Web app")
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

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setChromeVisible(visible: Boolean) {
        chromeVisible = visible
        if (::topBar.isInitialized) topBar.visibility = if (visible) View.VISIBLE else View.GONE
        chromeHandler.removeCallbacks(hideChromeRunnable)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
            chromeHandler.postDelayed(hideChromeRunnable, 3500)
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun peekChrome() {
        if (!chromeVisible) setChromeVisible(true) else {
            chromeHandler.removeCallbacks(hideChromeRunnable)
            chromeHandler.postDelayed(hideChromeRunnable, 3500)
        }
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersive()
        setChromeVisible(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { WebView.setDataDirectorySuffix("whatsapp2") }
        }

        captureEnabled = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getBoolean("capture_enabled", true)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        }

        val title = TextView(this).apply {
            text = "WhatsApp 2nd"
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
        topBar.addView(title)
        topBar.addView(spacer)
        topBar.addView(micToggle)
        topBar.addView(exit)
        root.addView(topBar)
        setContentView(root)

        attachWebView()
        refreshMicToggle()
        showDiagnosticOnce()
        requestWebPermissionsIfNeeded()

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshMicToggle()
    }

    private var isVisible = false

    override fun onStart() {
        super.onStart()
        isVisible = true
        runCatching { webView.resumeTimers() }
    }

    override fun onStop() {
        super.onStop()
        isVisible = false
        runCatching { webView.pauseTimers() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Only release the WebView engine when about to be killed (critical
        // OOM), not on a normal background, so a kept-alive page survives.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && !isVisible) {
            runCatching { webView.freeMemory() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun attachWebView() {
        if (isFinishing || isDestroyed) return
        if (::webView.isInitialized) root.removeView(webView)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.userAgentString =
                WhatsAppCallEnabler.DESKTOP_UA.also { webUserAgent = it }
            
            runCatching {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }
            // Keep the renderer process alive under memory pressure.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) }
            }
            addJavascriptInterface(WebAppJsBridge(this@IsolatedWebActivity).also { it.webView = this }, "__igAndroid")
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP) peekChrome()
                false
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    Toast.makeText(
                        this@IsolatedWebActivity,
                        "Page asks: ${request.resources.joinToString().take(60)}",
                        Toast.LENGTH_SHORT
                    ).show()
                    val usesCapture = request.resources.any {
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                    }
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
                        type = if (fileChooserParams?.acceptTypes?.any { it.isNotEmpty() } == true)
                            "*/*" else "*/*"

                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

                        val acceptTypes = fileChooserParams?.acceptTypes
                            ?.filter { it.isNotEmpty() }
                            ?.toTypedArray()
                        if (!acceptTypes.isNullOrEmpty()) {
                            putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes)
                        }
                    }
                    uploadLauncher.launch(intent)
                    return true
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    if (resultMsg == null) return false
                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                    transport.webView = this@IsolatedWebActivity.webView
                    resultMsg.sendToTarget()
                    return true
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
                                this@IsolatedWebActivity,
                                "console: ${msg.take(140)}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    return false
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

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.settings?.useWideViewPort = false
                    view?.settings?.loadWithOverviewMode = false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
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
                    runCatching { view?.evaluateJavascript(CaptureDiagnostics.JS, null) }
                    runCatching { view?.evaluateJavascript(ViewportDiagnostics.JS, null) }
                    if (WhatsAppTapShim.ENABLED &&
                        url?.contains(WhatsAppTapShim.HOST_MARKER, ignoreCase = true) == true
                    ) {
                        runCatching { view?.evaluateJavascript(WhatsAppTapShim.JS, null) }
                    }
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    runCatching { view?.destroy() }
                    runOnUiThread { attachWebView() }
                    return true
                }
            }
            loadUrl("https://web.whatsapp.com/")
        }
        root.addView(webView)
    }

    override fun onPause() {
        super.onPause()
        runCatching { CookieManager.getInstance().flush() }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { CookieManager.getInstance().flush() }
        runCatching { webView.destroy() }
    }
}
