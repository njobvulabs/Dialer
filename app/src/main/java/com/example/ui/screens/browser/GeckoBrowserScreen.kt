package com.example.ui.screens.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.example.ui.components.AppSearchField
import com.example.util.QuickDownloadDialog
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.components.AppIcons
import com.example.ui.MainViewModel
import com.example.util.DlState
import com.example.util.DownloadManager as UtilDownloadManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

// --------------------------------------------------------- tabs & sessions

// Models/types extracted to BrowserModels.kt

// GeckoTabManager extracted to GeckoTabManager.kt

// BrowserUi extracted to BrowserUi.kt

// ------------------------------------------------------------------ helpers



// ------------------------------------------------------------------ screen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GeckoBrowserScreen(
    viewModel: MainViewModel,
    onToggleFullscreen: (Boolean) -> Unit = {},
    initialUrl: String? = null,
    forceDesktop: Boolean = false,
    appChrome: Boolean = false,
    onOpenVideoDownloader: (String) -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onExitBrowser: () -> Unit = {}
) {
    val context = LocalContext.current
    val browserPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    val chromeAtTopPref = remember { browserPrefs.getBoolean("browser_chrome_top", false) }
    val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val history by viewModel.browserHistory.collectAsState()
    val homePageUrl by viewModel.homePageUrl.collectAsState()
    val darkWebsites by viewModel.browserDarkMode.collectAsState()
    val desktopGlobal by viewModel.desktopMode.collectAsState()

    // ---- launchers (registered before anything that can trigger them)
    val ui = remember { BrowserUi() }
    // Declared before the assignment below: the lambda captures this local, and a
    // local `var` is not visible to a lambda that appears earlier in the body.
    var historyTick by remember { mutableIntStateOf(0) }
    // B8: user-pinned tiles, and the visit-ranked top sites.
    var ntpTiles by remember { mutableStateOf(loadNtpTiles(context)) }
    var showAddTileDialog by remember { mutableStateOf(false) }
    var tilePendingRemoval by remember { mutableStateOf<NtpTile?>(null) }
    // Hoist the flow so the collection is not restarted on every recomposition.
    val topSitesFlow = remember { viewModel.topSites(8) }
    val topSites by topSitesFlow.collectAsState(initial = emptyList())
    ui.onRecordHistory = { title, url -> viewModel.addBrowserHistory(title, url) }
    ui.onVisit = { title, url -> viewModel.recordVisit(title, url) }
    ui.onHistoryChanged = { historyTick++ }
    val capturePrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val clipData = data.clipData
            val uriStrings = mutableListOf<String>()
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i).uri?.let { uriStrings.add(it.toString()) }
                }
            } else {
                data.data?.let { uriStrings.add(it.toString()) }
            }
            ui.handleUploadResult(true, uriStrings)
        } else {
            ui.handleUploadResult(false, emptyList())
        }
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> ui.handlePermResult(grants) }
    ui.uploadLauncher = uploadLauncher
    ui.permLauncher = permLauncher
    ui.onFullscreenCb = onToggleFullscreen
    ui.ctx = context

    // ---- manager (process-wide)
    val manager = remember {
        BrowserHolder.manager ?: GeckoTabManager(context.applicationContext, ui).also { m ->
            if (!m.restoreTabs()) {
                m.tabs.add(GeckoTab(System.currentTimeMillis(), NTP_URL, "New Tab", false))
                m.activeIndex = 0
            }
            BrowserHolder.manager = m
        }
    }
    ui.mgr = manager
    // Re-point the process-wide manager at THIS composition's UI state (see the
    // `events` property). Without this the delegates keep writing to the previous
    // composition's dead state after a re-entry.
    manager.events = ui
    manager.captureEnabled = capturePrefs.getBoolean("capture_enabled", true)
    manager.globalDesktop = desktopGlobal || forceDesktop

    // ---- local UI state
    var geckoViewRef by remember { mutableStateOf<GeckoView?>(null) }
    var attachedTabId by remember { mutableStateOf(0L) }
    /** "back" / "forward" / null — which arrow's trail menu is open (B7). */
    var showNavMenu by remember { mutableStateOf<String?>(null) }
    var seenReloadTick by remember { mutableIntStateOf(0) }
    var toolbarHeightPx by remember { mutableIntStateOf(0) }
    // Chrome-toggle pill state. ui.toolbarShown is driven by Gecko's scroll /
    // fullscreen callbacks; this is the MANUAL override, so the two don't fight.
    var chromeVisible by remember { mutableStateOf(true) }
    var chromeAtTop by remember { mutableStateOf(chromeAtTopPref) }
    val activity = context as? Activity
    var showTabDrawer by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var showAddressEdit by remember { mutableStateOf(false) }
    var addressDraft by remember { mutableStateOf("") }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showSiteInfo by remember { mutableStateOf(false) }
    // Snackbar host for "Tab closed — Undo" (reopen closed tab).
    val snackbarHostState = remember { SnackbarHostState() }
    val browserScope = rememberCoroutineScope()
    // Destructive rows that used to fire on a single tap with no way back:
    // History → "Clear All" wiped every entry, and the per-bookmark trash icon
    // deleted that bookmark immediately.
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var pendingBookmarkDelete by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showEngineDialog by remember { mutableStateOf(false) }
    var showHomeDialog by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var ctxMenuTarget by remember { mutableStateOf<LinkContextTarget?>(null) }
    var readerOriginalUrl by remember { mutableStateOf<String?>(null) }
    var readerActive by remember { mutableStateOf(false) }
    var captureEnabled by remember { mutableStateOf(manager.captureEnabled) }
    // ---- nj-browser extension ports (Quick Download / detect-video) ----
    // Content scripts cannot reach the app on GeckoView (mozilla/geckoview#220),
    // so the extension's background relays every content port to a native port
    // and introduces it with {type:'ident', url}. Keyed by session: this browser
    // has many live sessions, unlike the single-session web-app screen.
    val njPorts = remember { mutableStateMapOf<GeckoSession, WebExtension.Port>() }
    var njExtensionReady by remember { mutableStateOf(false) }
    // The in-flight detect request: which session asked, and the dialog's callback.
    var pendingDetect by remember {
        mutableStateOf<Pair<GeckoSession, (QuickDownloadDialog.DetectedMedia?) -> Unit>?>(null)
    }
    // A page whose content script never attached (loaded before the extension
    // finished installing) is reloaded ONCE, on demand — never proactively, so
    // we don't yank a page out from under someone who is only reading it.
    val detectReloaded = remember { mutableStateMapOf<GeckoSession, Boolean>() }
    val activeTabId = manager.tabs.getOrNull(manager.activeIndex)?.id ?: 0L

    val activeTab = manager.tabs.getOrNull(manager.activeIndex)
    val activeSession = activeTab?.session

    // Bind the nj-browser extension's native port once per browser session. Every
    // content script that loads connects, and each connection is matched to the
    // tab whose live session is showing that URL.
    //
    // NOTE the "njbrowser" thread-name argument: setMessageDelegate dispatches these
    // callbacks on a Gecko background thread, NOT the main thread. Every touch of
    // Compose state below is therefore posted back to the main looper — otherwise the
    // port map is mutated off-thread while composition is reading it. (Same class of
    // bug as the worker-thread Toast NPE that used to kill :webapps.)
    val njMain = remember { Handler(Looper.getMainLooper()) }
    LaunchedEffect(Unit) {
        var ext = BrowserEngine.njBrowserExtension()
        var tries = 0
        while (ext == null && tries < 100) {
            delay(100)
            ext = BrowserEngine.njBrowserExtension()
            tries++
        }
        val e = ext ?: return@LaunchedEffect
        android.util.Log.i("GeckoBrowser", "njbrowser ready for video detect")
        runCatching {
            e.setMessageDelegate(object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) {
                    val directS = port.sender?.session
                    // This port's own session binding, resolved when its `ident`
                    // arrives. Captured per-port so a later reply is attributed to
                    // the page that actually sent it, not to whichever tab happened
                    // to connect most recently.
                    var bound: GeckoSession? = directS
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            val m = message as? JSONObject ?: return
                            when (m.optString("type")) {
                                // The relay introduces each side with its URL; that URL
                                // is what ties a port to one of the many live tabs.
                                "ident", "connected" -> {
                                    val u = m.optString("url")
                                    if (u.isBlank()) return
                                    val target = directS ?: manager.sessionForIdentUrl(u) ?: return
                                    bound = target
                                    njMain.post {
                                        val old = njPorts[target]
                                        if (old != null && old !== port) {
                                            runCatching { old.disconnect() }
                                        }
                                        njPorts[target] = port
                                        njExtensionReady = true
                                    }
                                }
                                "detectVideoResult" -> {
                                    val found = QuickDownloadDialog.parseDetectedMedia(
                                        m.optString("value").ifBlank { null }
                                    )
                                    val sess = bound
                                    njMain.post {
                                        val pending = pendingDetect
                                        if (pending == null) return@post
                                        // Only accept a reply for the session that
                                        // asked — another tab's page must never fill
                                        // in this dialog.
                                        if (sess != null && pending.first !== sess) {
                                            return@post
                                        }
                                        pendingDetect = null
                                        pending.second(found)
                                    }
                                }
                            }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            njMain.post {
                                njPorts.entries.firstOrNull { it.value === port }
                                    ?.let { njPorts.remove(it.key) }
                            }
                        }
                    })
                }
            }, "njbrowser")
        }.onFailure { android.util.Log.e("GeckoBrowser", "njbrowser bind failed", it) }
    }

    // The bottom chrome is visible only when scroll, fullscreen and the pill all
    // agree — fullscreen always wins, and a manual hide sticks until un-tapped.
    val chromeVisibleNow = ui.toolbarShown.value && chromeVisible && !ui.fullscreenActive.value
    val barOffset by animateFloatAsState(
        targetValue = if (chromeVisibleNow) 0f else toolbarHeightPx.toFloat(),
        animationSpec = tween(200),
        label = "barOffset"
    )

    // ---- sync effects
    LaunchedEffect(darkWebsites) { BrowserEngine.applyWebsiteColorScheme(darkWebsites) }
    LaunchedEffect(Unit) {
        val u = initialUrl?.trim()
        if (!u.isNullOrBlank() && manager.activeTab()?.url != u) {
            manager.newTab(u, isPrivate = false, activate = true)
            attachedTabId = 0L
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) manager.persistTabs()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            manager.persistTabs()
            // Leaving the browser hands the RAM back. This is a deliberate change
            // from the old "keep every session alive on dispose" behaviour, which
            // was chosen when staying alive was worth more than the memory: each
            // live session is a Gecko content process, and leaving five of them
            // resident while the user went back to the launcher is what made the
            // whole app get killed. Tabs are still listed, so re-entering reloads
            // the tab you were on (its URL/title are persisted above).
            manager.releaseAll()
        }
    }

    /**
     * Ask the active page's content script for its video URL, reloading once if the
     * script never attached. Mirrors GeckoWebAppScreen.sendPageCommand, but targets
     * one specific session because this browser has many.
     */
    fun sendDetectCommand(session: GeckoSession) {
        val port = njPorts[session]
        if (port != null) {
            runCatching { port.postMessage(JSONObject().put("cmd", "detectVideo")) }
                .onFailure {
                    android.util.Log.e("GeckoBrowser", "detectVideo post failed", it)
                    showToast(context, "Could not reach the page")
                }
            return
        }
        // No port = this document loaded before the njbrowser extension was ready,
        // so it never got the content script. Reload ONCE so a second Detect tap
        // works, and tell the user why nothing came back yet.
        if (!njExtensionReady) {
            showToast(context, "Download detection is still starting up — try again in a moment")
            return
        }
        val firstTry = detectReloaded[session] != true
        if (firstTry) {
            detectReloaded[session] = true
            runCatching { session.reload() }
            showToast(
                context,
                "Reloading page so download detection can attach — tap Detect again"
            )
        } else {
            showToast(context, "Download detection unavailable on this page")
        }
    }

    fun loadInActive(rawUrl: String) {
        val tab = manager.activeTab() ?: return
        var url = rawUrl.trim()
        if (url.isEmpty()) return
        if (readerActive) {
            readerActive = false
            readerOriginalUrl = null
        }
        if (url == NTP_URL) {
            // Homepage/blank target = New Tab page: drop the session so the
            // Compose NTP (speed dial) renders, exactly like a fresh tab.
            tab.url = NTP_URL
            attachedTabId = tab.id
            runCatching { geckoViewRef?.releaseSession() }
            ui.urlInput.value = ""
            ui.loading.value = false
            seenReloadTick = ui.reloadTick.value
            return
        }
        val shortcut = GeckoTabManager.resolveSearchShortcut(url)
        if (shortcut != null) {
            url = shortcut.second
        } else if (!GeckoTabManager.looksLikeUrl(url)) {
            url = GeckoTabManager.engineSearchUrl(
                manager.searchEngine(),
                url,
                manager.customEngineUrl(),
                manager.searxngInstance()
            )
        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        tab.url = url
        val s = tab.session
        if (s != null) s.loadUri(url) else attachedTabId = 0L
        ui.urlInput.value = url
    }

    /** Attach the current active tab's session to the GeckoView immediately.
     *  Used on explicit tab switches so the swap never depends on a later
     *  Compose update pass to actually happen. */
    fun attachActiveTabToView() {
        val view = geckoViewRef ?: return
        val tab = manager.activeTab() ?: return
        runCatching { view.releaseSession() }
        if (tab.url == NTP_URL) {
            attachedTabId = tab.id
            ui.urlInput.value = ""
        } else {
            val s = manager.ensureSession(tab)
            view.setSession(s)
            runCatching { s.setActive(true) }
            runCatching { view.requestFocus() }
            attachedTabId = tab.id
            ui.urlInput.value = tab.url
        }
        seenReloadTick = ui.reloadTick.value
    }

    fun toggleReader() {
        val session = activeSession ?: return
        if (readerActive) {
            readerActive = false
            val orig = readerOriginalUrl
            readerOriginalUrl = null
            orig?.let { session.loadUri(it) }
        } else {
            val current = ui.urlInput.value
            if (!current.startsWith("http")) {
                Toast.makeText(context, "No article to read here", Toast.LENGTH_SHORT).show()
                return
            }
            readerOriginalUrl = current
            readerActive = true
            session.loadUri("about:reader?url=" + URLEncoder.encode(current, "UTF-8"))
        }
    }

    fun saveScreenshot() {
        val view = geckoViewRef ?: return
        view.capturePixels().accept({ bmp ->
            if (bmp == null) {
                Toast.makeText(context, "Screenshot failed", Toast.LENGTH_SHORT).show()
                return@accept
            }
            try {
                val name = "NJBrowser_" + System.currentTimeMillis() + ".png"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/NJ-Dialer")
                    }
                    val uri = context.contentResolver
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                } else {
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "NJ-Dialer"
                    )
                    dir.mkdirs()
                    val f = File(dir, name)
                    FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    android.media.MediaScannerConnection.scanFile(
                        context, arrayOf(f.absolutePath), arrayOf("image/png"), null
                    )
                }
                Toast.makeText(context, "Screenshot saved to Pictures/NJ-Dialer", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Screenshot failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, { Toast.makeText(context, "Screenshot failed", Toast.LENGTH_SHORT).show() })
    }

    fun savePdf() {
        val session = activeSession
        if (session == null) {
            Toast.makeText(context, "Nothing loaded yet", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, "Saving PDF...", Toast.LENGTH_SHORT).show()
        session.saveAsPdf().accept({ stream ->
            if (stream == null) {
                Toast.makeText(context, "PDF save failed", Toast.LENGTH_SHORT).show()
                return@accept
            }
            try {
                val base = (activeTab?.title ?: "page")
                    .replace(Regex("[\\\\/:*?\"<>|]+"), "_").take(40)
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "NJ-Dialer"
                )
                dir.mkdirs()
                val f = File(dir, "${base}_${System.currentTimeMillis()}.pdf")
                FileOutputStream(f).use { out -> stream.copyTo(out, 64 * 1024) }
                runCatching { stream.close() }
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(f.absolutePath), arrayOf("application/pdf"), null
                )
                Toast.makeText(context, "Saved to Download/NJ-Dialer/${f.name}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "PDF save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, { Toast.makeText(context, "PDF save failed", Toast.LENGTH_SHORT).show() })
    }

    fun closeTabAt(index: Int) {
        val reopenBefore = manager.recentlyClosed.size
        if (index == manager.activeIndex) {
            runCatching { geckoViewRef?.releaseSession() }
        }
        manager.closeTab(index)
        attachedTabId = 0L
        // Offer an Undo only when the tab actually entered the reopen stack (not a
        // private tab or the blank New Tab page).
        if (manager.recentlyClosed.size > reopenBefore) {
            browserScope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "Tab closed",
                    actionLabel = "Undo",
                    // A snackbar with an action defaults to SnackbarDuration.Indefinite,
                    // which left "Tab closed — Undo" pinned forever and blocked the
                    // bottom controls. Force a finite window (and allow a swipe/X away).
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    manager.reopenLastClosed()
                    attachedTabId = 0L
                }
            }
        }
    }

    BackHandler(enabled = ui.fullscreenActive.value || showFindBar ||
        showAddressEdit || ui.canGoBack.value) {
        when {
            showAddressEdit -> showAddressEdit = false
            ui.fullscreenActive.value -> runCatching { activeSession?.exitFullScreen() }
            showFindBar -> {
                showFindBar = false
                findQuery = ""
                runCatching { activeSession?.finder?.clear() }
            }
            else -> runCatching { activeSession?.goBack() }
        }
    }

    // The browser is an immersive route, so the system bars stay hidden while it
    // is open. The pill toggles ONLY the floating browser chrome; it must not
    // show/hide the system bars. The page surface stays full-screen behind the
    // floating chrome so content is never clipped by toolbar/inset transitions.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        snackbarHost = {
            // The chrome bar is floating and own-positioned; the snackbar sits at
            // the window bottom, so inset it past the gesture nav bar by hand.
            SnackbarHost(hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding())
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {

            // ---------------- engine surface (full screen to bottom edge; chrome overlays it)
            AndroidView(
                factory = { ctx ->
                    GeckoView(ctx).also { v ->
                        v.layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        v.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        v.clipChildren = false
                        v.clipToPadding = false
                        runCatching { v.clipBounds = null }
                        v.setDynamicToolbarMaxHeight(0)
                        runCatching { v.setVerticalClipping(0) }
                        geckoViewRef = v
                    }
                },
                update = { view ->
                    view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    view.clipChildren = false
                    view.clipToPadding = false
                    runCatching { view.clipBounds = null }
                    val tab = manager.activeTab()
                    val needsAttach = tab != null &&
                        (attachedTabId != tab.id || seenReloadTick != ui.reloadTick.value)
                    if (tab != null && needsAttach) {
                        runCatching { view.releaseSession() }
                        if (tab.url == NTP_URL) {
                            // Pure new-tab page: show overlay without spawning a session.
                            attachedTabId = tab.id
                            ui.urlInput.value = ""
                        } else {
                            val s = manager.ensureSession(tab)
                            view.setSession(s)
                            runCatching { s.setActive(true) }
                            runCatching { view.requestFocus() }
                            attachedTabId = tab.id
                            ui.urlInput.value = if (tab.url == NTP_URL) "" else tab.url
                        }
                        seenReloadTick = ui.reloadTick.value
                    }
                    view.setDynamicToolbarMaxHeight(0)
                    runCatching { view.setVerticalClipping(0) }
                    view.requestLayout()
                    view.invalidate()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged {
                        geckoViewRef?.let { gv ->
                            gv.requestLayout()
                            gv.invalidate()
                        }
                    }
            )

            // ---------------- new tab page overlay
            if (activeTab?.url == NTP_URL) {
                Box(modifier = Modifier.fillMaxSize()) {
                    NewTabPage(
                        tiles = ntpTiles,
                        topSites = topSites,
                        history = history,
                        onOpen = { loadInActive(it) },
                        onAddTile = { showAddTileDialog = true },
                        onRemoveTile = { tilePendingRemoval = it }
                    )
                }
            }

            // ---------------- crash / kill interstitial
            val crashedTab = activeTab
            if (crashedTab != null && crashedTab.crashed && crashedTab.url != NTP_URL) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            AppIcons.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "This page crashed",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            crashedTab.url,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                            crashedTab.crashed = false
                            runCatching { crashedTab.session?.close() }
                            crashedTab.session = null
                            attachedTabId = 0L
                        }) { Text("Reload") }
                            OutlinedButton(onClick = {
                                val idx = manager.tabs.indexOfFirst { it.id == crashedTab.id }
                                if (idx >= 0) closeTabAt(idx) else {
                                    crashedTab.crashed = false
                                    attachedTabId = 0L
                                }
                            }) { Text("Close tab") }
                        }
                    }
                }
            }

            // ---------------- browser chrome (bottom by default, top optional)
            // Deliberately NO navigationBarsPadding / bottom inset here: the parent
            // Scaffold strips the bottom inset so this card's position depends only
            // on barOffset. Its visibility offset is therefore measured from the
            // chrome's own height (toolbarHeightPx), which stays correct.
            Column(
                modifier = Modifier
                    .align(if (chromeAtTop) Alignment.TopCenter else Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = if (chromeAtTop) -barOffset else barOffset
                    }
                    .onSizeChanged { toolbarHeightPx = it.height },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (chromeAtTop) {
                    ChromeToggleHandle(
                        onClick = { chromeVisible = false },
                        atTop = true,
                        expanded = true
                    )
                } else {
                    ChromeToggleHandle(
                        onClick = { chromeVisible = false },
                        atTop = false,
                        expanded = true
                    )
                }

                if (ui.loading.value) {
                    LinearProgressIndicator(
                        progress = { ui.progress.value / 100f },
                        modifier = Modifier.fillMaxWidth().height(3.dp)
                    )
                }

                if (showFindBar) {
                    FindInPageBar(
                        query = findQuery,
                        onQueryChange = { q ->
                            findQuery = q
                            val s = activeSession
                            if (s == null) {
                                // no session to search
                            } else if (q.isNotEmpty()) {
                                s.finder.find(q, GeckoSession.FINDER_FIND_FORWARD).accept({ r ->
                                    ui.matchInfo.value =
                                        if (r != null && r.total > 0) "${r.current}/${r.total}" else ""
                                }, { })
                            } else {
                                runCatching { s.finder.clear() }
                                ui.matchInfo.value = ""
                            }
                        },
                        matchLabel = ui.matchInfo.value,
                        onPrev = {
                            val s = activeSession ?: return@FindInPageBar
                            if (findQuery.isNotEmpty()) {
                                s.finder.find(findQuery, GeckoSession.FINDER_FIND_BACKWARDS)
                                    .accept({ r ->
                                        if (r != null) ui.matchInfo.value = "${r.current}/${r.total}"
                                    }, { })
                            }
                        },
                        onNext = {
                            val s = activeSession ?: return@FindInPageBar
                            if (findQuery.isNotEmpty()) {
                                s.finder.find(findQuery, GeckoSession.FINDER_FIND_FORWARD)
                                    .accept({ r ->
                                        if (r != null) ui.matchInfo.value = "${r.current}/${r.total}"
                                    }, { })
                            }
                        },
                        onClose = {
                            showFindBar = false
                            ui.matchInfo.value = ""
                            runCatching { activeSession?.finder?.clear() }
                        }
                    )
                }

                if (activeTab?.isPrivate == true) {
                    Surface(color = Color(0xFF581C87)) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(AppIcons.VisibilityOff, null,
                                tint = Color(0xFFE9D5FF), modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Private tab",
                                color = Color(0xFFE9D5FF),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                BrowserChrome(
                    isPrivate = activeTab?.isPrivate == true,
                    secure = ui.secure.value,
                    displayUrl = if (activeTab?.url == NTP_URL) "" else ui.urlInput.value,
                    canGoBack = ui.canGoBack.value,
                    canGoForward = ui.canGoForward.value,
                    loading = ui.loading.value,
                    progress = ui.progress.value,
                    tabCount = manager.tabs.size,
                    onAddressClick = {
                        addressDraft = if (activeTab?.url == NTP_URL) "" else ui.urlInput.value
                        showAddressEdit = true
                    },
                    onSiteInfoClick = {
                        if (activeTab?.isPrivate != true && activeTab?.url != NTP_URL) {
                            showSiteInfo = true
                        }
                    },
                    onBack = { runCatching { activeSession?.goBack() } },
                    onBackLongClick = { showNavMenu = "back" },
                    onForward = { runCatching { activeSession?.goForward() } },
                    onForwardLongClick = { showNavMenu = "forward" },
                    onReloadOrStop = {
                        val s = activeSession
                        val tab = manager.activeTab()
                        if (s != null) {
                            if (ui.loading.value) {
                                s.stop()
                            } else {
                                tab?.crashed = false
                                val u = ui.urlInput.value
                                if (u.startsWith("http")) s.loadUri(u) else s.reload()
                            }
                        } else if (tab != null && tab.url != NTP_URL) {
                            tab.crashed = false
                            attachedTabId = 0L
                        }
                    },
                    onHome = {
                        readerActive = false
                        readerOriginalUrl = null
                        loadInActive(homePageUrl)
                    },
                    onOpenDownloads = { onOpenDownloads() },
                    onOpenTabs = { showTabDrawer = !showTabDrawer },
                    onMenu = { showMenu = true },
                    onExit = { showExitDialog = true },
                    onNewPrivateTab = if (activeTab?.isPrivate == true) {
                        {
                            manager.newTab(NTP_URL, true, true)
                            attachedTabId = 0L
                        }
                    } else null
                )
            }

            if (!chromeVisibleNow) {
                Box(
                    modifier = Modifier.align(
                        if (chromeAtTop) Alignment.TopCenter else Alignment.BottomCenter
                    ),
                    contentAlignment = if (chromeAtTop) {
                        Alignment.TopCenter
                    } else {
                        Alignment.BottomCenter
                    }
                ) {
                    ChromeToggleHandle(
                        onClick = { chromeVisible = true },
                        atTop = chromeAtTop,
                        expanded = false
                    )
                }
            }

            // ---------------- tab switcher
            if (showTabDrawer) {
                TabSwitcherSheet(
                    tabs = manager.tabs,
                    activeIndex = manager.activeIndex,
                    hasPrivate = manager.tabs.any { it.isPrivate },
                    canReopen = manager.recentlyClosed.isNotEmpty(),
                    onDismiss = { showTabDrawer = false },
                    onSelect = { idx ->
                        manager.switchTo(idx)
                        showTabDrawer = false
                        if (manager.activeTab()?.id != attachedTabId) {
                            attachActiveTabToView()
                        }
                    },
                    onClose = { idx -> closeTabAt(idx) },
                    onTogglePin = { idx ->
                        val t = manager.tabs.getOrNull(idx) ?: return@TabSwitcherSheet
                        t.pinned = !t.pinned
                        manager.persistTabs()
                    },
                    onNewTab = {
                        manager.newTab(NTP_URL, false, true)
                        attachedTabId = 0L
                        showTabDrawer = false
                    },
                    onNewPrivateTab = {
                        manager.newTab(NTP_URL, true, true)
                        attachedTabId = 0L
                        showTabDrawer = false
                    },
                    onReopenClosed = {
                        manager.reopenLastClosed()
                        attachedTabId = 0L
                    },
                    onClosePrivate = {
                        manager.closeAllPrivate()
                        attachedTabId = 0L
                    },
                    onCloseAll = {
                        closeAllFlowImpl(manager) { attachedTabId = 0L }
                        showTabDrawer = false
                    }
                )
            }

            // ---------------- address / search overlay
            if (showAddressEdit) {
                val draft = addressDraft.trim()
                val shortcut = GeckoTabManager.resolveSearchShortcut(draft)
                val searchEngine = manager.searchEngine()
                val searchUrl = when {
                    shortcut != null -> shortcut.second
                    draft.isNotEmpty() && !GeckoTabManager.looksLikeUrl(draft) ->
                        GeckoTabManager.engineSearchUrl(
                            searchEngine,
                            draft,
                            manager.customEngineUrl(),
                            manager.searxngInstance()
                        )
                    else -> null
                }
                val suggestions = (bookmarks.map { it.title to it.url } +
                    history.map { it.title to it.url })
                    .distinctBy { it.second }
                    .filter {
                        it.first.contains(addressDraft, true) ||
                            it.second.contains(addressDraft, true)
                    }
                    .take(5)
                OmniboxOverlay(
                    draft = addressDraft,
                    onDraftChange = { addressDraft = it },
                    searchUrl = searchUrl,
                    searchLabel = shortcut?.first ?: searchEngine,
                    suggestions = suggestions,
                    showShortcutHint = searchUrl == null &&
                        (draft.startsWith("!") || draft.startsWith("@")),
                    onSubmit = {
                        showAddressEdit = false
                        loadInActive(addressDraft)
                    },
                    onOpenUrl = { u ->
                        showAddressEdit = false
                        loadInActive(u)
                    },
                    onDismiss = { showAddressEdit = false }
                )
            }

            // ---------------- menu
            if (showMenu) {
                ModalBottomSheet(
                    onDismissRequest = { showMenu = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            "Browser",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        MenuSection("Tabs")
                            MenuRow(AppIcons.Add, "New Tab") {
                                showMenu = false
                                manager.newTab(NTP_URL, false, true); attachedTabId = 0L
                            }
                            MenuRow(AppIcons.VisibilityOff, "New Private Tab") {
                                showMenu = false
                                manager.newTab(NTP_URL, true, true); attachedTabId = 0L
                            }
                            MenuRow(AppIcons.ContentCopy, "Duplicate Tab") {
                                showMenu = false
                                val t = manager.activeTab()
                                if (t != null) {
                                    manager.newTab(t.url, t.isPrivate, true)
                                    attachedTabId = 0L
                                }
                            }
                            if (manager.recentlyClosed.isNotEmpty()) {
                                MenuRow(AppIcons.Restore, "Reopen Closed Tab") {
                                    showMenu = false
                                    manager.reopenLastClosed()
                                    attachedTabId = 0L
                                }
                            }
                            if (manager.tabs.any { it.isPrivate }) {
                                MenuRow(AppIcons.Close, "Close Private Tabs") {
                                    showMenu = false
                                    manager.closeAllPrivate()
                                    attachedTabId = 0L
                                }
                            }
                            HorizontalDivider()
                            MenuSection("Page")
                            val isBookmarked = bookmarks.any { it.url == ui.urlInput.value &&
                                ui.urlInput.value.startsWith("http") }
                            MenuRow(
                                if (isBookmarked) AppIcons.Star else AppIcons.StarBorder,
                                if (isBookmarked) "Remove Bookmark" else "Add Bookmark"
                            ) {
                                if (isBookmarked) {
                                    bookmarks.find { it.url == ui.urlInput.value }
                                        ?.let { viewModel.deleteBookmark(it) }
                                    Toast.makeText(context, "Removed bookmark", Toast.LENGTH_SHORT).show()
                                } else {
                                    viewModel.addBookmark(activeTab?.title.ifBlankOrNull() ?: ui.urlInput.value,
                                        ui.urlInput.value)
                                    Toast.makeText(context, "Added bookmark", Toast.LENGTH_SHORT).show()
                                }
                                showMenu = false
                            }
                            MenuRow(AppIcons.Bookmarks, "Bookmarks (${bookmarks.size})") {
                                showMenu = false; showBookmarksDialog = true
                            }
                            MenuRow(AppIcons.History, "History (${history.size})") {
                                showMenu = false; showHistoryDialog = true
                            }
                            val activeDownloads = UtilDownloadManager.jobs.count {
                                it.state == DlState.QUEUED ||
                                    it.state == DlState.DOWNLOADING ||
                                    it.state == DlState.PAUSED
                            }
                            MenuRow(
                                AppIcons.Download,
                                if (activeDownloads > 0) "Downloads ($activeDownloads)"
                                else "Downloads"
                            ) {
                                showMenu = false; onOpenDownloads()
                            }
                            HorizontalDivider()
                            MenuSection("Tools")
                            MenuRow(AppIcons.FindInPage, "Find in Page") {
                                showMenu = false; showFindBar = true
                            }
                            MenuRow(AppIcons.ChromeReaderMode,
                                if (readerActive) "Exit Reader Mode" else "Reader Mode") {
                                showMenu = false; toggleReader()
                            }
                            MenuRow(AppIcons.PictureAsPdf, "Save Page as PDF") {
                                showMenu = false; savePdf()
                            }
                            MenuRow(AppIcons.CameraAlt, "Take Screenshot") {
                                showMenu = false; saveScreenshot()
                            }
                            MenuRow(AppIcons.Download, "Download Video From Page") {
                                showMenu = false
                                val pageUrl = ui.urlInput.value
                                if (pageUrl.startsWith("http")) {
                                    Toast.makeText(context, "Opening in Video Downloader...", Toast.LENGTH_SHORT).show()
                                    onOpenVideoDownloader(pageUrl)
                                } else {
                                    Toast.makeText(context, "Open a video page first", Toast.LENGTH_SHORT).show()
                                }
                            }
                            HorizontalDivider()
                            MenuSection("Site")
                            Row(modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    viewModel.toggleDesktopMode()
                                    val effective = !desktopGlobal
                                    activeTab?.let { manager.applyDesktopModeToSession(it, effective || forceDesktop) }
                                }
                                .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.DesktopWindows, null)
                                Spacer(Modifier.width(12.dp))
                                Text("Desktop Mode", modifier = Modifier.weight(1f))
                                Switch(checked = desktopGlobal, onCheckedChange = {
                                    viewModel.toggleDesktopMode()
                                    activeTab?.let {
                                        manager.applyDesktopModeToSession(it, !desktopGlobal || forceDesktop)
                                    }
                                })
                            }
                            val host = runCatching { Uri.parse(ui.urlInput.value).host }.getOrNull()
                            if (host != null && host.isNotBlank()) {
                                val curOverride = manager.desktopOverrideFor(host)
                                MenuRow(AppIcons.DesktopWindows,
                                    "This site: " + when (curOverride) {
                                        "desktop" -> "Desktop (tap: mobile)"
                                        "mobile" -> "Mobile (tap: default)"
                                        else -> "Default (tap: desktop)"
                                    }) {
                                    val next = when (curOverride) {
                                        null -> true; "desktop" -> false; else -> null
                                    }
                                    manager.setDesktopOverride(host, next)
                                    val effForSite = when (next) {
                                        true -> true
                                        false -> false
                                        null -> manager.globalDesktop
                                    }
                                    activeTab?.let { manager.applyDesktopModeToSession(it, effForSite) }
                                    Toast.makeText(context, "Reloading site...", Toast.LENGTH_SHORT).show()
                                    showMenu = false
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth()
                                .clickable { viewModel.toggleBrowserDarkMode() }
                                .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.DarkMode, null)
                                Spacer(Modifier.width(12.dp))
                                Text("Dark Websites", modifier = Modifier.weight(1f))
                                Switch(checked = darkWebsites,
                                    onCheckedChange = { viewModel.toggleBrowserDarkMode() })
                            }
                            HorizontalDivider()
                            Row(modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    captureEnabled = !captureEnabled
                                    manager.captureEnabled = captureEnabled
                                    capturePrefs.edit().putBoolean("capture_enabled", captureEnabled).apply()
                                }
                                .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (captureEnabled) AppIcons.Mic else AppIcons.MicOff, null)
                                Spacer(Modifier.width(12.dp))
                                Text("Mic & Camera for Sites", modifier = Modifier.weight(1f))
                                Switch(checked = captureEnabled, onCheckedChange = {
                                    captureEnabled = it
                                    manager.captureEnabled = it
                                    capturePrefs.edit().putBoolean("capture_enabled", it).apply()
                                })
                            }
                            HorizontalDivider()
                            MenuSection("Settings")
                            MenuRow(AppIcons.Search, "Default search engine (${manager.searchEngine()})") {
                                showMenu = false; showEngineDialog = true
                            }
                            MenuRow(
                                AppIcons.HomeWork,
                                "Home Page (" +
                                    if (homePageUrl == NTP_URL || homePageUrl == "about:blank") "New Tab Page"
                                    else homePageUrl + ")"
                            ) {
                                showMenu = false; showHomeDialog = true
                            }
                            MenuRow(AppIcons.CleaningServices, "Clear Cookies & Site Data") {
                                showMenu = false; showClearDataDialog = true
                            }
                            MenuRow(AppIcons.Lock, "Site Permissions") {
                                showMenu = false; showSiteInfo = true
                            }
                            MenuRow(
                                if (chromeAtTop) AppIcons.ArrowDownward
                                else AppIcons.ArrowUpward,
                                if (chromeAtTop) "Move toolbar to bottom"
                                else "Move toolbar to top"
                            ) {
                                showMenu = false
                                chromeAtTop = !chromeAtTop
                                browserPrefs.edit()
                                    .putBoolean("browser_chrome_top", chromeAtTop)
                                    .apply()
                            }
                            // Gives the long-standing `block_popups` pref an actual control. It was
                            // read by onPopupPrompt and written by setBlockPopups(), but no
                            // UI ever reached it, so it was permanently false.
                            var blockPopupsOn by remember {
                                mutableStateOf(manager.blockPopups())
                            }
                            Row(modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    blockPopupsOn = !blockPopupsOn
                                    manager.setBlockPopups(blockPopupsOn)
                                    Toast.makeText(
                                        context,
                                        if (blockPopupsOn) "Pop-ups will be denied"
                                        else "Pop-ups allowed",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.Block, null)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "Block Pop-ups",
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(checked = blockPopupsOn, onCheckedChange = {
                                    blockPopupsOn = it
                                    manager.setBlockPopups(it)
                                })
                            }
                            HorizontalDivider()
                            MenuRow(AppIcons.ContentCopy, "Copy Link") {
                                showMenu = false
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("url", ui.urlInput.value))
                                Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                            }
                            MenuRow(AppIcons.Download, "Quick Download Media") {
                                showMenu = false
                                val s = activeSession
                                if (s == null) {
                                    showToast(context, "No page loaded")
                                } else if (activeTab?.isPrivate == true) {
                                    showToast(context, "Downloads are off in private tabs")
                                } else {
                                    val act = activity
                                    if (act == null) {
                                        showToast(context, "Cannot open download dialog")
                                    } else {
                                        runCatching {
                                            QuickDownloadDialog.show(
                                                act,
                                                ui.urlInput.value,
                                                onDetectVideo = { cb ->
                                                    pendingDetect = s to cb
                                                    sendDetectCommand(s)
                                                }
                                            )
                                        }.onFailure {
                                            showToast(context, "Could not open download dialog")
                                        }
                                    }
                                }
                            }
                            HorizontalDivider()
                            MenuRow(AppIcons.Close, "Exit browser") {
                                showMenu = false
                                showExitDialog = true
                            }
                    }
                }
            }

            // ---------------- bookmarks dialog
            if (showBookmarksDialog) {
                AlertDialog(
                    onDismissRequest = { showBookmarksDialog = false },
                    title = { Text("Saved Bookmarks") },
                    text = {
                        if (bookmarks.isEmpty()) Text("No bookmarks saved yet.")
                        else LazyColumn(modifier = Modifier.heightIn(max = 350.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(bookmarks) { bm ->
                                Row(modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        showBookmarksDialog = false
                                        loadInActive(bm.url)
                                    }
                                    .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(bm.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            fontWeight = FontWeight.Bold)
                                        Text(bm.url, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = {
                                        pendingBookmarkDelete = bm.url to bm.title
                                    }) {
                                        Icon(AppIcons.Delete, "Delete",
                                            tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showBookmarksDialog = false }) { Text("Close") }
                    }
                )
            }

            // ---------------- history dialog
            if (showHistoryDialog) {
                var historySearch by remember { mutableStateOf("") }
                val filteredHistory = history.filter { h ->
                    historySearch.isBlank() ||
                        h.title.contains(historySearch, true) ||
                        h.url.contains(historySearch, true)
                }
                AlertDialog(
                    onDismissRequest = { showHistoryDialog = false },
                    title = {
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("History (${history.size})")
                            if (history.isNotEmpty()) {
                                TextButton(onClick = { showClearHistoryConfirm = true }) {
                                    Text("Clear All", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    },
                    text = {
                        Column {
                            if (history.isNotEmpty()) {
                                AppSearchField(
                                    value = historySearch,
                                    onValueChange = { historySearch = it },
                                    placeholder = "Search history",
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            when {
                                history.isEmpty() -> Text("No browsing history.")
                                filteredHistory.isEmpty() -> Text("No history matches “$historySearch”.")
                                else -> LazyColumn(
                                    modifier = Modifier.heightIn(max = 350.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(filteredHistory) { h ->
                                        Row(modifier = Modifier.fillMaxWidth()
                                            .clickable {
                                                showHistoryDialog = false
                                                loadInActive(h.url)
                                            }
                                            .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(h.title, maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontWeight = FontWeight.Bold)
                                                Text(h.url, maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            IconButton(onClick = { viewModel.deleteHistoryItem(h.id) }) {
                                                Icon(AppIcons.Close, "Remove")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showHistoryDialog = false }) { Text("Close") }
                    }
                )
            }

            // ---------------- new-tab tile add / remove (B8)
            if (showAddTileDialog) {
                var tileTitle by remember { mutableStateOf("") }
                var tileUrl by remember { mutableStateOf("") }
                val urlTyped = tileUrl.trim()
                val urlValid = urlTyped.startsWith("http://") || urlTyped.startsWith("https://")
                AlertDialog(
                    onDismissRequest = { showAddTileDialog = false },
                    title = { Text("Add tile") },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = tileTitle,
                                onValueChange = { tileTitle = it },
                                label = { Text("Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = tileUrl,
                                onValueChange = { tileUrl = it },
                                label = { Text("Address") },
                                singleLine = true,
                                isError = urlTyped.isNotEmpty() && !urlValid,
                                supportingText = {
                                    if (urlTyped.isNotEmpty() && !urlValid) {
                                        Text("Must start with http:// or https://")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = urlValid,
                            onClick = {
                                // Prefer the typed name, else derive one from the host.
                                val fallback = Uri.parse(urlTyped).host ?: urlTyped
                                ntpTiles = ntpTiles + NtpTile(
                                    title = tileTitle.trim().ifEmpty { fallback },
                                    url = urlTyped
                                )
                                saveNtpTiles(context, ntpTiles)
                                showAddTileDialog = false
                            }
                        ) { Text("Add") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAddTileDialog = false }) { Text("Cancel") }
                    }
                )
            }

            tilePendingRemoval?.let { tile ->
                AlertDialog(
                    onDismissRequest = { tilePendingRemoval = null },
                    title = { Text("Remove tile?") },
                    text = { Text("\"${tile.title}\" will be removed from the new tab page.") },
                    confirmButton = {
                        TextButton(onClick = {
                            ntpTiles = ntpTiles.filterNot { it.url == tile.url }
                            saveNtpTiles(context, ntpTiles)
                            tilePendingRemoval = null
                        }) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { tilePendingRemoval = null }) { Text("Cancel") }
                    }
                )
            }

            // ---------------- Back / Forward trail (B7)
            val navSnapshot = remember(historyTick, attachedTabId, showNavMenu) {
                manager.tabHistory[attachedTabId]
            }
            if (showNavMenu != null) {
                val goingBack = showNavMenu == "back"
                val entries = navSnapshot?.let {
                    if (goingBack) it.backEntries() else it.forwardEntries()
                }.orEmpty()
                AlertDialog(
                    onDismissRequest = { showNavMenu = null },
                    title = { Text(if (goingBack) "Back" else "Forward") },
                    text = {
                        if (entries.isEmpty()) {
                            Text(
                                "No pages to go " + if (goingBack) "back" else "forward",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                                itemsIndexed(entries) { _, entry ->
                                    val idx = entry.index
                                    val url = entry.value.first
                                    val label = entry.value.second.ifBlank { url }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                showNavMenu = null
                                                runCatching { activeSession?.gotoHistoryIndex(idx) }
                                            }
                                            .padding(vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            AppIcons.History, null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(label, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodyMedium)
                                            Text(url, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showNavMenu = null }) { Text("Close") }
                    }
                )
            }

            // ---------------- Site Info sheet
            if (showSiteInfo) {
                SiteInfoSheet(
                    tab = activeTab,
                    onDismiss = { showSiteInfo = false }
                )
            }

            // ---------------- clear data dialog
            if (showClearDataDialog) {
                var ckCookies by remember { mutableStateOf(true) }
                var ckSiteData by remember { mutableStateOf(true) }
                var ckCache by remember { mutableStateOf(true) }
                var ckHistory by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { showClearDataDialog = false },
                    title = { Text("Clear Browsing Data") },
                    text = {
                        Column {
                            ClearRow("Cookies & logins", ckCookies) { ckCookies = it }
                            ClearRow("Site storage & settings", ckSiteData) { ckSiteData = it }
                            ClearRow("Cached files", ckCache) { ckCache = it }
                            ClearRow("Browsing history", ckHistory) { ckHistory = it }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            showClearDataDialog = false
                            BrowserEngine.clearBrowsingData(ckCookies, ckSiteData, ckCache)
                            if (ckHistory) viewModel.clearBrowserHistory()
                            Toast.makeText(context, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                        }) { Text("Clear") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearDataDialog = false }) { Text("Cancel") }
                    }
                )
            }

            // ---------------- clear-history confirmation
            if (showClearHistoryConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearHistoryConfirm = false },
                    title = { Text("Clear browsing history?") },
                    text = {
                        Text("All ${history.size} history entr${if (history.size == 1) "y" else "ies"} will be permanently deleted.")
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showClearHistoryConfirm = false
                                viewModel.clearBrowserHistory()
                                Toast.makeText(context, "Browsing history cleared", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("Clear") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearHistoryConfirm = false }) { Text("Cancel") }
                    }
                )
            }

            // ---------------- delete-bookmark confirmation
            pendingBookmarkDelete?.let { (url, title) ->
                AlertDialog(
                    onDismissRequest = { pendingBookmarkDelete = null },
                    title = { Text("Delete bookmark?") },
                    text = { Text(title.ifBlankOrNull() ?: url) },
                    confirmButton = {
                        Button(
                            onClick = {
                                bookmarks.firstOrNull { it.url == url }?.let { viewModel.deleteBookmark(it) }
                                pendingBookmarkDelete = null
                                Toast.makeText(context, "Bookmark deleted", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingBookmarkDelete = null }) { Text("Cancel") }
                    }
                )
            }

            // ---------------- exit browser dialog
            if (showExitDialog) {
                AlertDialog(
                    onDismissRequest = { showExitDialog = false },
                    title = { Text("Exit browser") },
                    text = {
                        Text(
                            "Exit & keep running returns to NJ-Dialer without closing your tabs; reopening resumes the live pages."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showExitDialog = false
                            onExitBrowser()
                        }) { Text("Exit & keep running") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showExitDialog = false
                            closeAllFlowImpl(manager) { attachedTabId = 0L }
                            onExitBrowser()
                        }) { Text("Close browser") }
                    }
                )
            }

            // ---------------- long-press link / image / video / text menu
            ui.ctxMenuTarget.value?.let { target ->
                val link = target.url
                val text = target.selectedText
                fun dismiss() {
                    ui.ctxMenuTarget.value = null
                    ui.currentSelection = null
                }

                fun copyLink() {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("link", link))
                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                }

                fun copyText() {
                    // Prefer the page's own selection so the clipboard gets the
                    // real formatting-independent string Gecko reports; fall back
                    // to what we captured if the selection is already gone.
                    val selection = target.selection
                    if (selection != null) {
                        selection.copy()
                    } else {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
                    }
                    Toast.makeText(context, "Text copied", Toast.LENGTH_SHORT).show()
                }

                fun selectAll() {
                    target.selection?.selectAll() ?: run {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
                        Toast.makeText(context, "Selection cleared — text copied", Toast.LENGTH_SHORT).show()
                    }
                }

                fun searchSelection(siteOnly: Boolean) {
                    val query = text.trim()
                    if (query.isEmpty()) return
                    val host = runCatching {
                        Uri.parse(manager.activeTab()?.url.orEmpty()).host
                    }.getOrNull()
                    if (siteOnly && host.isNullOrBlank()) return
                    val finalQuery = if (siteOnly) "site:$host $query" else query
                    dismiss()
                    loadInActive(
                        GeckoTabManager.engineSearchUrl(
                            manager.searchEngine(),
                            finalQuery,
                            manager.customEngineUrl(),
                            manager.searxngInstance()
                        )
                    )
                }

                fun downloadImage() {
                    // Direct file save: an image has no streams to merge, so yt-dlp
                    // (Video Downloader) is the wrong tool here. The system
                    // DownloadManager is used instead and must not touch Gecko.
                    val source = link ?: return
                    val name = source.substringAfterLast('/').substringBefore('?')
                        .ifBlank { "image" }
                        .let { if (target.isImage) it else "$it.jpg" }
                        .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    runCatching {
                        val req = android.app.DownloadManager.Request(Uri.parse(source))
                            .setTitle(name)
                            .setNotificationVisibility(
                                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                            )
                            .setDestinationInExternalPublicDir(
                                android.os.Environment.DIRECTORY_DOWNLOADS,
                                "${name.substringBeforeLast('.', name)}_${System.currentTimeMillis() % 100000}" +
                                    ".${name.substringAfterLast('.', "jpg")}"
                            )
                        (context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager)
                            .enqueue(req)
                        Toast.makeText(context, "Downloading $name", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, "Download failed to start", Toast.LENGTH_SHORT).show()
                    }
                }

                AlertDialog(
                    onDismissRequest = { dismiss() },
                    title = {
                        Text(
                            when {
                                target.isVideo -> "Video options"
                                target.isImage -> "Image options"
                                target.isLink -> "Link options"
                                else -> "Text options"
                            }
                        )
                    },
                    text = {
                        Column {
                            if (target.isLink) {
                                Text(
                                    link.orEmpty(),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        val u = link ?: return@TextButton
                                        dismiss()
                                        manager.newTab(u, isPrivate = false, activate = true)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Open in New Tab") }
                                TextButton(
                                    onClick = {
                                        val u = link ?: return@TextButton
                                        dismiss()
                                        manager.newTab(u, isPrivate = true, activate = true)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Open in Incognito") }
                                TextButton(
                                    onClick = { dismiss(); copyLink() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Copy Link") }
                            }
                            // Text actions. These exist for a long-press on plain
                            // page text, which used to fall out of `onContextMenu`
                            // entirely and leave only Gecko's own popup.
                            if (target.hasSelection) {
                                if (target.isLink) Spacer(Modifier.height(8.dp))
                                Text(
                                    "“${text.take(80)}${if (text.length > 80) "…" else ""}”",
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                TextButton(
                                    onClick = { searchSelection(siteOnly = false) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Search Web for “${text.take(40)}”") }
                                val siteHost = runCatching {
                                    Uri.parse(manager.activeTab()?.url.orEmpty()).host
                                }.getOrNull()
                                if (!siteHost.isNullOrBlank()) {
                                    TextButton(
                                        onClick = { searchSelection(siteOnly = true) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Search This Site for “${text.take(40)}”") }
                                }
                                TextButton(
                                    onClick = { dismiss(); copyText() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Copy Text") }
                                TextButton(
                                    onClick = { dismiss(); selectAll() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Select All") }
                            }
                            if (target.isImage) {
                                TextButton(
                                    onClick = { dismiss(); downloadImage() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Download Image") }
                            }
                            if (target.isVideo) {
                                TextButton(
                                    onClick = {
                                        val u = link ?: return@TextButton
                                        dismiss()
                                        Toast.makeText(
                                            context,
                                            "Opening in Video Downloader...",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        onOpenVideoDownloader(u)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Download Video") }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { dismiss() }) { Text("Close") } }
                )
            }

            // ---------------- default search engine dialog
            if (showEngineDialog) {
                var selectedEngine by remember { mutableStateOf(manager.searchEngine()) }
                var customUrl by remember { mutableStateOf(manager.customEngineUrl()) }
                var searxngUrl by remember { mutableStateOf(manager.searxngInstance()) }
                val customValid = selectedEngine != "Custom" ||
                    customUrl.trim().contains("{search}")
                val searxngValid = selectedEngine != "SearXNG" ||
                    searxngUrl.trim().startsWith("http")
                val canSave = customValid && searxngValid

                AlertDialog(
                    onDismissRequest = { showEngineDialog = false },
                    title = { Text("Default search engine") },
                    text = {
                        Column {
                            Text(
                                "Used by the address bar, search suggestions and selected-text search.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 330.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                GeckoTabManager.SEARCH_ENGINES.forEach { engine ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable { selectedEngine = engine }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = selectedEngine == engine,
                                            onClick = { selectedEngine = engine }
                                        )
                                        Text(engine)
                                    }
                                }
                            }
                            if (selectedEngine == "Custom") {
                                OutlinedTextField(
                                    value = customUrl,
                                    onValueChange = { customUrl = it },
                                    label = { Text("URL with {search}") },
                                    placeholder = { Text("https://example.com/?q={search}") },
                                    singleLine = true,
                                    isError = customUrl.trim().isNotEmpty() && !customValid,
                                    supportingText = {
                                        if (customUrl.trim().isNotEmpty() && !customValid) {
                                            Text("Include {search} where the query goes")
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (selectedEngine == "SearXNG") {
                                OutlinedTextField(
                                    value = searxngUrl,
                                    onValueChange = { searxngUrl = it },
                                    label = { Text("SearXNG instance") },
                                    placeholder = { Text("https://searx.be") },
                                    singleLine = true,
                                    isError = searxngUrl.trim().isNotEmpty() && !searxngValid,
                                    supportingText = {
                                        if (searxngUrl.trim().isNotEmpty() && !searxngValid) {
                                            Text("Must start with http:// or https://")
                                        } else {
                                            Text("Queries use /search?q=… on this instance")
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = canSave,
                            onClick = {
                                manager.setSearchEngine(selectedEngine)
                                if (selectedEngine == "Custom") {
                                    manager.setCustomEngineUrl(customUrl.trim())
                                }
                                if (selectedEngine == "SearXNG") {
                                    manager.setSearxngInstance(
                                        searxngUrl.trim().ifBlank { "https://searx.be" }
                                    )
                                }
                                showEngineDialog = false
                            }
                        ) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEngineDialog = false }) { Text("Cancel") }
                    }
                )
            }

            // ---------------- homepage dialog
            if (showHomeDialog) {
                var homeBlank by remember {
                    mutableStateOf(homePageUrl == NTP_URL || homePageUrl == "about:blank")
                }
                var homeDraft by remember {
                    mutableStateOf(if (homeBlank) "" else homePageUrl)
                }
                AlertDialog(
                    onDismissRequest = { showHomeDialog = false },
                    title = { Text("Set Homepage") },
                    text = {
                        Column {
                            Row(modifier = Modifier.fillMaxWidth().clickable { homeBlank = !homeBlank }
                                .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = homeBlank, onCheckedChange = { homeBlank = it })
                                Text("Use blank page (New Tab page)")
                            }
                            OutlinedTextField(
                                value = homeDraft,
                                onValueChange = { homeDraft = it },
                                label = { Text("Homepage URL") },
                                singleLine = true,
                                enabled = !homeBlank,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            viewModel.setHomePageUrl(if (homeBlank) NTP_URL else homeDraft)
                            showHomeDialog = false
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showHomeDialog = false }) { Text("Cancel") }
                    }
                )
            }

            // ---------------- downloads (full-screen screen in MainActivity)
            // Downloads are managed in com.example.ui.screens.downloader.DownloadsScreen
            // (opened via onOpenDownloads).
        }
    }
}

