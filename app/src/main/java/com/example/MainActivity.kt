package com.example

import android.Manifest
import android.app.Activity
import com.example.ui.components.AppIcons
import com.example.util.DownloadBridge
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.example.data.db.CallType
import com.example.service.CustomInCallService
import com.example.service.HidOverlayController
import com.example.service.HidPadTheme
import com.example.ui.MainViewModel
import com.example.ui.screens.home.LauncherPager
import com.example.ui.screens.home.LauncherShadeOverlay
import com.example.ui.screens.hid.HidPadScreen
import com.example.ui.screens.vault.VaultScreen
import com.example.ui.screens.browser.GeckoBrowserScreen
import com.example.ui.screens.camera.CameraScreen
import com.example.ui.screens.dialer.CallStatsScreen
import com.example.ui.screens.dialer.ContactToolsScreen
import com.example.ui.screens.dialer.InCallScreen
import com.example.ui.screens.dialer.PhoneMainScreen
import com.example.ui.screens.deviceinfo.DeviceInfoScreen
import com.example.ui.screens.gallery.GalleryScreen
import com.example.ui.screens.files.DocumentReaderDetailView
import com.example.ui.screens.files.FileManagerScreen
import com.example.ui.screens.terminal.TerminalScreen
import com.example.ui.screens.media.AudioPlayerScreen
import com.example.ui.screens.media.ImageViewerScreen
import com.example.ui.screens.media.VideoPlayerScreen
import com.example.ui.screens.media.VideoTabScreen
import com.example.ui.screens.notes.NotesScreen
import com.example.ui.screens.samba.SambaScreen
import com.example.ui.screens.sandbox.SandboxScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.sys.SysScreen
import com.example.ui.screens.wifishare.WifiShareScreen
import com.example.ui.screens.webapp.WebAppScreen
import com.example.ui.theme.AppAccent
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.MyApplicationTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.example.util.SystemCallHelper
import com.example.service.AudioEngine
import com.example.util.AssistantActions
import com.example.util.TempCleanup
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import java.io.File

private val externalOpenVnc = androidx.compose.runtime.mutableStateOf(false)

/** Routes a web-app id (built-in or custom slot) to the right Gecko host intent. */
private fun buildWebAppLaunchIntent(context: Context, appId: String): Intent? {
    val n = com.example.ui.screens.webapp.CustomWebApps.slotIndex(appId)
    return if (n != null) {
        when (n) {
            1 -> GeckoWebAppActivity1.intent(context, appId)
            2 -> GeckoWebAppActivity2.intent(context, appId)
            3 -> GeckoWebAppActivity3.intent(context, appId)
            else -> GeckoWebAppActivity4.intent(context, appId)
        }
    } else {
        GeckoWebAppActivity.intent(context, appId)
    }
}

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_DOWNLOADER = "com.example.OPEN_DOWNLOADER"
        const val EXTRA_OPEN_VNC = "com.example.OPEN_VNC"
    }

    private val viewModel: MainViewModel by viewModels()

    /** URL handed over from a share-sheet tap or a web-app long-press download. */
    private val externalDownloaderUrl = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val pendingShortcut = androidx.compose.runtime.mutableStateOf<PendingShortcut?>(null)
    private data class PendingCall(val number: String, val simSlot: Int, val name: String?)
    private data class PendingShortcut(
        val target: String,
        val phone: String? = null,
        val mode: String? = null,
        val query: String? = null
    )
    private val pendingCallConfirm = androidx.compose.runtime.mutableStateOf<PendingCall?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeIncomingUrl(intent)
    }

    private fun handleShortcutIntent(intent: Intent?) {
        intent ?: return
        if (intent.action != AssistantActions.ACTION_OPEN_SHORTCUT) return
        val target = intent.getStringExtra(AssistantActions.EXTRA_TARGET) ?: return
        pendingShortcut.value = PendingShortcut(
            target = target,
            phone = intent.getStringExtra(AssistantActions.EXTRA_PHONE),
            mode = intent.getStringExtra(AssistantActions.EXTRA_MODE),
            query = intent.getStringExtra(AssistantActions.EXTRA_QUERY)
        )
    }

    private fun consumeIncomingUrl(intent: Intent?) {
        intent ?: return
        handleShortcutIntent(intent)
        if (intent.action == "com.example.action.OPEN_SHORTCUT") return
        if (intent.getBooleanExtra(EXTRA_OPEN_VNC, false)) {
            externalOpenVnc.value = true
        }
        val fromShare = intent.getStringExtra(Intent.EXTRA_TEXT)
        val explicit = intent.getStringExtra(EXTRA_OPEN_DOWNLOADER)
        when {
            explicit != null ->
                externalDownloaderUrl.value = com.example.util.DownloadBridge.takePending()
            fromShare != null && fromShare.startsWith("http") -> {
                com.example.util.DownloadBridge.pendingUrl = fromShare
                externalDownloaderUrl.value = fromShare
            }
        }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeIncomingUrl(intent)
        handleShortcutIntent(intent)

        // Daily cleanup without initialising WorkManager during cold start. Wait
        // briefly so it never competes with first-frame work.
        lifecycleScope.launch {
            delay(30_000L)
            withContext(Dispatchers.IO) { TempCleanup.runIfDue(this@MainActivity) }
        }

        // Installed per process by NjApp; idempotent, so this is a no-op in the
        // main process and the guard lives in one place.
        com.example.util.CrashLog.install()

        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val accent by viewModel.accent.collectAsState()
            val dynamicColors by viewModel.dynamicColors.collectAsState()
            val amoledBlack by viewModel.amoledBlack.collectAsState()
            val fontScalePercent by viewModel.fontScale.collectAsState()
            val largerTouchTargets by viewModel.largerTouchTargets.collectAsState()
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = density.density,
                    fontScale = density.fontScale * (fontScalePercent / 100f)
                ),
                LocalMinimumInteractiveComponentSize provides if (largerTouchTargets) 56.dp else LocalMinimumInteractiveComponentSize.current
            ) {
            MyApplicationTheme(
                themeMode = themeMode,
                accent = accent,
                dynamicColor = dynamicColors,
                amoledBlack = amoledBlack
            ) {
                // Core dialer permissions are requested up front; media-access
                // permissions (READ_MEDIA_*) are requested just-in-time by the
                // screens that read MediaStore (Gallery, Image Editor), so we
                // don't overwhelm first launch.
                val permissionsList = mutableListOf(
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.WRITE_CALL_LOG,
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_SMS
                ).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                val permissionsState = rememberMultiplePermissionsState(permissionsList)

                LaunchedEffect(Unit) {
                    permissionsState.launchMultiplePermissionRequest()
                }

                MainAppContent(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun SystemBarsImmersiveUtility(isImmersive: Boolean) {
    val context = LocalContext.current
    DisposableEffect(isImmersive) {
        val window = (context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isImmersive) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val window = (context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(viewModel: MainViewModel) {
    val context = LocalContext.current
    // Launcher-style main surface: 0 = Home grid, 1 = Phone (Recents/Contacts/SMS).
    // There is no horizontal pager any more — the floating dock switches instantly,
    // and the only writers are tile taps, the dock, and Phone's own pills (via
    // onTabChanged). Both values are persisted by the SideEffect below.
    var mainScreen by rememberSaveable {
        mutableIntStateOf(viewModel.lastScreen.value.coerceIn(0, 1))
    }
    var phoneTab by rememberSaveable {
        mutableIntStateOf(viewModel.lastPhoneTab.value.coerceIn(0, 2))
    }

    fun showPhone(tab: Int) {
        phoneTab = tab.coerceIn(0, 2)
        mainScreen = 1
    }

    val activeCallState by CustomInCallService.callState.collectAsState()
    val launcherQuickShadeEnabled by viewModel.launcherQuickShadeEnabled.collectAsState()
    var shadeOpen by remember { mutableStateOf(false) }
    val terminalAccent by viewModel.accent.collectAsState()
    val terminalDynamicColors by viewModel.dynamicColors.collectAsState()
    val terminalAmoledBlack by viewModel.amoledBlack.collectAsState()

    fun restoredFile(path: String?): File? =
        path?.let { runCatching { File(it) }.getOrNull() }?.takeIf { it.exists() }

    var activeVideoFile by remember { mutableStateOf(restoredFile(viewModel.lastVideoFile.value)) }
    // Playlist of the folder the video was opened from (for prev/next + autoplay
    // + the on-screen playlist in the player). Reset with the active video.
    var activeVideoPlaylist by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var activeImageFile by remember { mutableStateOf(restoredFile(viewModel.lastImageFile.value)) }
    // Images to swipe between in the viewer when opened from a folder/list (the
    // Camera's in-app gallery). Not persisted: a restored playlist on cold start
    // would be stale. Empty -> the viewer shows the single activeImageFile.
    var activeImagePlaylist by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var activeImageIndex by remember { mutableIntStateOf(0) }
    var activeDocFile by remember { mutableStateOf(restoredFile(viewModel.lastDocFile.value)) }
    var activeAudioFile by remember { mutableStateOf(restoredFile(viewModel.lastAudioFile.value)) }
    // Streamed (Samba) video has no local File; it is played from the localhost proxy URL.
    var activeStreamVideoUrl by remember { mutableStateOf<String?>(null) }
    var editorImageFile by remember { mutableStateOf<java.io.File?>(null) }
    var editorImageUri by remember { mutableStateOf<Uri?>(null) }
    var editorImageName by remember { mutableStateOf<String?>(null) }
    var immersiveAppId by remember {
        mutableStateOf(
            viewModel.lastAppId.value?.takeIf {
                it !in listOf(
                    "camera", "flashlight", "recorder", "screenrecorder", "calculator", "qrscanner", "btinput", "stopwatch", "tts", "wifiaudio",
                    "appkiller", "networktools", "mediaserver", "ftpserver", "todo", "converter", "documents"
                ) && !it.startsWith("geckowebapp:")
            }
        )
    }
    var browserInitialUrl by remember { mutableStateOf<String?>(null) }
    var smsPrefill by remember { mutableStateOf("") }

    var showHidOverlay by remember { mutableStateOf(false) }
    var showHidLockPad by remember { mutableStateOf(false) }

    // True while the gallery's full-screen photo viewer is up (reported by
    // GalleryScreen) — the host then renders that screen edge-to-edge.
    var galleryViewerOpen by remember { mutableStateOf(false) }

    // A tool opened from the File Manager's ⋮ menu (Samba / Wi-Fi Transfer /
    // Device Info / APK Backup / Text Extract / Recycle bin) should land back on
    // the File Manager when closed, not the Apps Hub.
    var toolReturnToFiles by remember { mutableStateOf(false) }

    // A screen opened from *inside* the browser (the Downloads manager) goes
    // back to the browser on back/close instead of unwinding to the Apps Hub.
    // immersiveAppId is a single scalar with no navigation stack, so the screen
    // we came from has to be recorded explicitly.
    var immersiveReturnId by remember { mutableStateOf<String?>(null) }

    var isBrowserFullscreen by remember { mutableStateOf(false) }

    fun closeImmersive() {
        val returnTo = immersiveReturnId
        if (returnTo != null) {
            immersiveReturnId = null
            immersiveAppId = returnTo
            return
        }
        immersiveAppId = if (toolReturnToFiles) {
            toolReturnToFiles = false
            "files"
        } else {
            null
        }
        // Leaving the browser must drop its one-shot page state. browserInitialUrl
        // is consumed only on first composition, so a stale value would open a
        // duplicate tab on the next entry — and a stale isBrowserFullscreen keeps
        // shouldBeImmersive true, which suppresses the bottom nav and system bars.
        browserInitialUrl = null
        isBrowserFullscreen = false
    }

    fun showHidOverlay() {
        showHidOverlay = true
        immersiveAppId = "hidoverlay"
    }

    fun hideHidOverlay() {
        showHidOverlay = false
        immersiveAppId = null
    }

    fun showHidLockPad() {
        showHidLockPad = true
        immersiveAppId = "hidlock"
    }

    fun closeHidLockPad() {
        showHidLockPad = false
        immersiveAppId = null
    }

    // A last-session web app ("geckowebapp:<id>") is not relaunched here anymore:
    // the web apps now live on their own profile in the :webapps process. Route
    // it to GeckoWebAppActivity so its live session/profile is preserved.
    val restoredWebAppId = remember {
        viewModel.lastAppId.value?.takeIf { it.startsWith("geckowebapp:") }
            ?.removePrefix("geckowebapp:")
    }
    LaunchedEffect(Unit) {
        restoredWebAppId?.let { id ->
            val intent = buildWebAppLaunchIntent(context, id)
            if (intent != null) runCatching { context.startActivity(intent) }
        }
    }

    // OCR "Take Photo" routes through the in-app camera, then returns here.
    var ocrCaptureRequested by remember { mutableStateOf(false) }
    var ocrPendingFile by remember { mutableStateOf<java.io.File?>(null) }
    var scanPdfCaptureRequested by remember { mutableStateOf(false) }

    // Route share-sheet taps / web-app long-press downloads into the downloader.
    LaunchedEffect(externalDownloaderUrl.value) {
        val u = externalDownloaderUrl.value ?: return@LaunchedEffect
        externalDownloaderUrl.value = null
        DownloadBridge.pendingUrl = u
        immersiveAppId = "downloader"
    }
    var showStorageAccessDialog by remember { mutableStateOf(false) }

    // Persist last page state so the app relaunches where the user left off.
    SideEffect {
        viewModel.setLastScreen(mainScreen)
        viewModel.setLastPhoneTab(phoneTab)
    }
    // Persist the screen we'd return to, so a process death on the Downloads
    // manager resumes in the browser instead of dropping the browser entirely.
    SideEffect { viewModel.setLastAppId(immersiveReturnId ?: immersiveAppId) }
    SideEffect { viewModel.setLastVideoFile(activeVideoFile?.absolutePath) }
    SideEffect { viewModel.setLastImageFile(activeImageFile?.absolutePath) }
    SideEffect { viewModel.setLastDocFile(activeDocFile?.absolutePath) }
    SideEffect { viewModel.setLastAudioFile(activeAudioFile?.absolutePath) }

    // Automatic global full-screen immersive mode. Video, Camera and Bible were
    // Camera and Bible were deliberately taken OUT of this list (user decision:
    // no immersive fullscreen on them), so the system bars stay visible on them
    // — their own top/bottom controls are therefore inset-aware.
    // The VIDEO PLAYER is back IN: opening a video must play IMMERSIVE FULL
    // SCREEN (2026-10-06), so activeVideoFile/activeStreamVideoUrl now drive
    // SystemBarsImmersiveUtility, which hides every system bar while the player
    // is open and shows them again when it closes (its DisposableEffect key
    // flips with this flag). Do NOT add a second hide/show inside
    // VideoPlayerScreen: its onDispose show() would re-show the bars when
    // leaving a video that was opened from an already-immersive route (e.g.
    // Browser Downloads), whose key never changes. The player's control rows
    // keep their statusBarsPadding()/navigationBarsPadding() — those collapse
    // to 0 while the bars are hidden and come back when the bars do. Gallery was
    // never in this list; only its full-screen photo viewer escapes the Scaffold
    // inset, via edgeToEdgeImmersive in the immersive branch below.
    val shouldBeImmersive = activeCallState.hasActiveCall ||
            activeDocFile != null ||
            activeVideoFile != null ||
            activeStreamVideoUrl != null ||
            immersiveAppId in listOf("web", "browserdownloads") ||
            immersiveAppId?.startsWith("webapp:") == true ||
            isBrowserFullscreen

    val shadeBlockedRoutes = listOf(
        "camera", "screenrecorder", "terminal", "vnc", "hidoverlay", "hidlock",
        "qrscanner", "scanpdf", "imageeditor", "ocr", "sandbox"
    )
    val shadeAllowed = launcherQuickShadeEnabled &&
            !activeCallState.hasActiveCall &&
            activeImageFile == null &&
            activeDocFile == null &&
            activeVideoFile == null &&
            activeStreamVideoUrl == null &&
            !showStorageAccessDialog &&
            pendingCallConfirm.value == null &&
            editorImageFile == null &&
            editorImageUri == null &&
            !(immersiveAppId == "gallery" && galleryViewerOpen) &&
            !(immersiveAppId == "web" && isBrowserFullscreen) &&
            immersiveAppId !in shadeBlockedRoutes

    LaunchedEffect(shadeAllowed) {
        if (!shadeAllowed) shadeOpen = false
    }

    var shadeOpenRequest by remember { mutableIntStateOf(0) }
    var shadeOpenPage by remember { mutableIntStateOf(0) }
    val shadeDensity = LocalDensity.current
    val shadeWindowWidthPx = LocalWindowInfo.current.containerSize.width.toFloat().coerceAtLeast(1f)
    val liveShadeStatusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val shadeStatusBarResId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    val fallbackShadeStatusTop = if (shadeStatusBarResId > 0) {
        with(shadeDensity) { context.resources.getDimensionPixelSize(shadeStatusBarResId).toDp() }
    } else 0.dp
    val shadeStatusTop = if (shouldBeImmersive && liveShadeStatusTop <= 0.dp) {
        fallbackShadeStatusTop
    } else liveShadeStatusTop
    val shadePullBandHeightPx = with(shadeDensity) { (shadeStatusTop + 52.dp).toPx() }

    SystemBarsImmersiveUtility(isImmersive = shouldBeImmersive)

    // Prompt for "All files access" when entering the File Manager without it.
    LaunchedEffect(immersiveAppId) {
        if (immersiveAppId == "files" && !Environment.isExternalStorageManager()) {
            showStorageAccessDialog = true
        }
    }

    // Sync system call log into the app whenever the app resumes
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.syncSystemCallLog()
                viewModel.refreshDeviceContacts()
                viewModel.refreshSmsUnread()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            android.widget.Toast.makeText(
                context,
                "Allow \"Install unknown apps\" for this app first",
                android.widget.Toast.LENGTH_LONG
            ).show()
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
            return
        }
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Split APKs are installed by streaming a session that contains the
                // base plus every config split, handed over under EXTRA_INTENT
                // (Intent.EXTRA_INTENT, not PackageInstaller.EXTRA_INTENT —
                // the latter is @hide). Without it the installer only ever sees
                // the base and the install silently does nothing.
                putExtra(
                    Intent.EXTRA_INTENT,
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            }
            context.startActivity(intent)
        }.onFailure {
            android.widget.Toast.makeText(context, "Cannot open APK installer", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Last-resort handler for file types with no in-app viewer (archives, .bin,
    // unknown binaries). A FileProvider URI is mandatory: the receiving app is a
    // separate process, so a raw file:// URI would throw FileUriExposedException.
    fun openWithExternalApp(file: File, mimeType: String) {
        val type = mimeType.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: "application/octet-stream"
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, type)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri(file.name, uri)
            }
            if (view.resolveActivity(context.packageManager) != null) {
                context.startActivity(view)
            } else {
                context.startActivity(Intent.createChooser(view, "Open with"))
            }
        }.onFailure {
            android.widget.Toast.makeText(
                context, "No app can open ${file.name}", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Media & document click helper
    val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "heic", "heif")
    val videoExts = setOf("mp4", "mkv", "webm", "avi", "3gp", "mov", "flv", "ts", "m4v")
    val audioExts = setOf("mp3", "wav", "ogg", "m4a", "flac", "opus", "wma", "aac", "amr", "mid")
    val docExts = setOf("pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "rtf", "epub", "json", "md", "xml", "html", "log", "kt", "java", "srt", "vtt", "odt", "ods", "odp")

    // VLC-style folder playlist. Opening a video from the File Manager handed the
    // player a single file, and the player's Playlist button is gated on a list of
    // more than one entry -- so "play all videos in this folder" simply did not
    // exist on that path. This is one non-recursive listing of the folder the user
    // is already looking at, so it costs what rendering that folder already costs.
    fun siblingVideos(file: File): List<File> {
        val dir = file.parentFile ?: return emptyList()
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList()
        return files
            .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in videoExts }
            .sortedBy { it.name.lowercase() }
    }

    val openMediaHandler: (File, String) -> Unit = { file, mimeType ->
        val ext = file.extension.lowercase()
        if (mimeType.startsWith("image/") || ext in imageExts) {
            // Plain viewer: no folder playlist unless the caller set one (camera).
            activeImagePlaylist = emptyList()
            activeImageFile = file
        } else if (mimeType.startsWith("video/") || ext in videoExts) {
            activeVideoPlaylist = siblingVideos(file)
            activeVideoFile = file
        } else if (mimeType.startsWith("audio/") || ext in audioExts) {
            activeAudioFile = file
            immersiveAppId = "audio"
            toolReturnToFiles = true
        } else if (ext in docExts) {
            activeDocFile = file
        } else if (ext == "apk") {
            installApk(file)
        } else {
            // No in-app viewer for this type (archives, .bin, unknown binaries).
            // Previously this fell off the end of the if/else chain and the tap did
            // nothing at all, so hand it to whatever else can handle it.
            openWithExternalApp(file, mimeType)
        }
    }

    // "All files access" prompt for full file-manager functionality
    if (showStorageAccessDialog) {
        AlertDialog(
            onDismissRequest = { showStorageAccessDialog = false },
            title = { Text("Allow access to all files") },
            text = {
                Text("The File Manager needs \"All files access\" to list, open, copy, move, delete and compress every file on your device.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showStorageAccessDialog = false
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }) { Text("Grant Access") }
            },
            dismissButton = {
                TextButton(onClick = { showStorageAccessDialog = false }) { Text("Not Now") }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (shadeAllowed && !shadeOpen) {
                    Modifier.pointerInput(shadePullBandHeightPx, shadeWindowWidthPx) {
                        val revealThreshold = with(shadeDensity) { 32.dp.toPx() }
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial
                            )
                            if (down.position.y > shadePullBandHeightPx) return@awaitEachGesture
                            val openPage = if (down.position.x < shadeWindowWidthPx / 2f) 0 else 1
                            var lastTime = down.uptimeMillis
                            var velocityY = 0f
                            var totalY = 0f
                            var totalX = 0f
                            var claimed = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) {
                                    break
                                }
                                val delta = change.positionChange()
                                val elapsed = (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                                velocityY = delta.y * 1000f / elapsed
                                lastTime = change.uptimeMillis
                                totalY += delta.y
                                totalX += delta.x
                                if (!claimed && totalY > 8f && abs(totalY) > abs(totalX) * 1.15f) {
                                    claimed = true
                                }
                                if (claimed) {
                                    change.consume()
                                    if (totalY > revealThreshold || velocityY > 900f) {
                                        shadeOpenPage = openPage
                                        shadeOpenRequest++
                                        break
                                    }
                                }
                            }
                        }
                    }
                } else Modifier
            )
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (shadeOpen) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial
                            )
                            if (!down.isConsumed) down.consume()
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { if (!it.isConsumed) it.consume() }
                                if (event.changes.all { !it.pressed }) break
                            }
                        }
                    }
                } else Modifier
            )
    ) {
    if (activeCallState.hasActiveCall) {
        InCallScreen(
            callState = activeCallState,
            onEndCall = {
                // Return to main app
            },
            viewModel = viewModel
        )
    } else if (activeImageFile != null && immersiveAppId != "imageeditor") {
        ImageViewerScreen(
            imageFile = activeImageFile!!,
            onBack = {
                activeImageFile = null
                activeImagePlaylist = emptyList()
            },
            onEdit = {
                immersiveReturnId = null
                editorImageFile = activeImageFile
                editorImageUri = null
                editorImageName = activeImageFile?.name
                immersiveAppId = "imageeditor"
            },
            pagerFiles = activeImagePlaylist.ifEmpty { null },
            pagerInitialIndex = activeImageIndex
        )
    } else if (activeDocFile != null) {
        DocumentReaderDetailView(
            file = activeDocFile!!,
            onClose = { activeDocFile = null }
        )
    } else if (activeVideoFile != null) {
        VideoPlayerScreen(
            videoFile = activeVideoFile!!,
            playlist = activeVideoPlaylist,
            onBack = {
                activeVideoFile = null
                activeVideoPlaylist = emptyList()
            }
        )
    } else if (activeStreamVideoUrl != null) {
        VideoPlayerScreen(
            videoUrl = activeStreamVideoUrl,
            onBack = {
                activeStreamVideoUrl = null
                com.example.util.SambaMediaStream.stop()
            }
        )
    } else if (immersiveAppId != null) {
        BackHandler {
            closeImmersive()
        }
        // Camera and the gallery's full-screen photo viewer are drawn EDGE-TO-EDGE:
        // they drop the Scaffold inset and inset their own chrome instead, so the
        // strip behind the system bars is painted by the screen itself (black
        // camera preview / black photo viewer) rather than showing the theme
        // background there. Every other immersive screen keeps the normal inset.
        val edgeToEdgeImmersive =
            immersiveAppId == "camera" ||
                (immersiveAppId == "gallery" && galleryViewerOpen)
        Scaffold(
        ) { innerPadding ->
            // imePadding shrinks the immersive web content (GeckoView/WebView)
            // by the soft-keyboard height when an input is focused, so the page
            // reflows the focused field above the keyboard (edge-to-edge
            // windows don't shrink on their own). Keep this on the root of the
            // immersive branch — it hosts every web app + the in-app browser.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (edgeToEdgeImmersive) PaddingValues(0.dp) else innerPadding)
                    .imePadding()
            ) {
                when (immersiveAppId) {
                    "hidoverlay" -> {
                        val overlayContext = context
                        val overlayDragX = remember { mutableStateOf(0f) }
                        val overlayDragY = remember { mutableStateOf(0f) }
                        AndroidView(
                            factory = {
                                HidOverlayController.createComposeView(
                                    context = overlayContext,
                                    lifecycleOwner = this@MainActivity,
                                    savedStateRegistryOwner = this@MainActivity,
                                    viewModelStoreOwner = this@MainActivity,
                                    onClose = { showHidOverlay = false; immersiveAppId = null },
                                    onDrag = { dx, dy ->
                                        overlayDragX.value += dx
                                        overlayDragY.value += dy
                                    },
                                    title = "BT input"
                                )
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    "hidlock" -> MyApplicationTheme(
                        themeMode = AppThemeMode.SYSTEM,
                        accent = AppAccent.INDIGO,
                        dynamicColor = true,
                        amoledBlack = false
                    ) {
                        com.example.ui.screens.hid.HidPadScreen(
                            title = "Lock-screen input",
                            onClose = { showHidLockPad = false; immersiveAppId = null },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    "files" -> FileManagerScreen(
                        viewModel = viewModel,
                        onOpenMedia = openMediaHandler,
                        onOpenVault = { immersiveAppId = "vault" },
                        onExtractText = { file ->
                            ocrPendingFile = file
                            toolReturnToFiles = true
                            immersiveAppId = "ocr"
                        },
                        onExit = { immersiveAppId = null }
                    )
                    "web" -> GeckoBrowserScreen(
                        viewModel = viewModel,
                        onToggleFullscreen = { isBrowserFullscreen = it },
                        initialUrl = browserInitialUrl,
                        onOpenVideoDownloader = { url ->
                            com.example.util.DownloadBridge.pendingUrl = url
                            // The browser has already consumed initialUrl by the time
                            // this fires, so drop it: re-entering "web" must never
                            // spawn a stale duplicate tab.
                            browserInitialUrl = null
                            immersiveReturnId = "web"
                            immersiveAppId = "downloader"
                        },
                        onOpenDownloads = {
                            browserInitialUrl = null
                            immersiveReturnId = "web"
                            immersiveAppId = "browserdownloads"
                        },
                        onExitBrowser = { closeImmersive() }
                    )
                    "browserdownloads" -> com.example.ui.screens.downloader.DownloadsScreen(
                        viewModel = viewModel,
                        onOpenMedia = { file -> openMediaHandler(file, "") }
                    )
                    "downloads" -> com.example.ui.screens.downloader.DownloadsScreen(
                        viewModel = viewModel,
                        onOpenMedia = { file -> openMediaHandler(file, "") }
                    )
                    "audio" -> AudioPlayerScreen(
                        viewModel = viewModel,
                        initialTrackPath = activeAudioFile?.absolutePath,
                        onInitialTrackConsumed = { activeAudioFile = null }
                    )
                    "video" -> VideoTabScreen(
                        viewModel = viewModel,
                        onOpenMedia = openMediaHandler,
                        onOpenMediaWithPlaylist = { file, playlist ->
                            activeVideoPlaylist = playlist
                            activeVideoFile = file
                        }
                    )
                    "notes" -> NotesScreen(viewModel = viewModel)
                    "calendar" -> com.example.ui.screens.calendar.CalendarScreen(viewModel)
                    "scanpdf" -> com.example.ui.screens.scanpdf.ScanPdfScreen(viewModel)
                    "gallery" -> GalleryScreen(
                        viewModel = viewModel,
                        onBack = { immersiveAppId = null },
                        onViewerVisibleChange = { galleryViewerOpen = it },
                        onEditImage = { uri, name ->
                            activeImageFile = null
                            activeImagePlaylist = emptyList()
                            galleryViewerOpen = false
                            immersiveReturnId = "gallery"
                            editorImageFile = null
                            editorImageUri = uri
                            editorImageName = name
                            immersiveAppId = "imageeditor"
                        }
                    )
                    "samba" -> SambaScreen(
                        viewModel = viewModel,
                        onOpenMediaUrl = { url, _ ->
                            activeStreamVideoUrl = url
                        },
                        onOpenMediaFile = { file, mime ->
                            openMediaHandler(file, mime)
                        }
                    )
                    "wifishare" -> WifiShareScreen(
                        viewModel = viewModel,
                        onOpenBrowser = { url ->
                            browserInitialUrl = url
                            immersiveAppId = "web"
                        }
                    )
                    "trash" -> com.example.ui.screens.files.RecycleBinScreen(
                        viewModel = viewModel,
                        onBack = { closeImmersive() }
                    )
                    "deviceinfo" -> DeviceInfoScreen(viewModel = viewModel)
                    "apkbackup" -> DeviceInfoScreen(viewModel = viewModel, initialTab = "apps")
                    "sandbox" -> SandboxScreen(viewModel = viewModel)
                    "ocr" -> com.example.ui.screens.ocr.OcrScreen(
                        onClose = ::closeImmersive,
                        onTakePhotoFromCamera = {
                            ocrCaptureRequested = true
                            immersiveAppId = "camera"
                        },
                        pendingFile = ocrPendingFile,
                        onPendingConsumed = { ocrPendingFile = null }
                    )
                    "downloader" -> com.example.ui.screens.downloader.VideoDownloaderScreen(
                        initialUrl = com.example.util.DownloadBridge.takePending(),
                        onClose = { closeImmersive() },
                        onOpenMedia = { f ->
                            openMediaHandler(
                                f,
                                if (f.extension.lowercase() in listOf("m4a", "mp3", "opus", "ogg", "wav"))
                                    "audio/mp4" else "video/mp4"
                            )
                        }
                    )
                    "imageeditor" -> com.example.ui.screens.imageeditor.ImageEditorScreen(
                        viewModel = viewModel,
                        sourceFile = editorImageFile,
                        sourceUri = editorImageUri,
                        sourceName = editorImageName,
                        onClose = {
                            editorImageFile = null
                            editorImageUri = null
                            editorImageName = null
                            closeImmersive()
                        }
                    )
                    "bible" -> com.example.ui.screens.bible.BibleScreen(
                        onClose = { immersiveAppId = null },
                        viewModel = viewModel
                    )
                    "hymns" -> com.example.ui.screens.hymns.HymnScreen(
                        viewModel = viewModel,
                        onClose = { immersiveAppId = null }
                    )
                    "settings" -> SettingsScreen(viewModel = viewModel)
                    "assistantsettings" -> com.example.ui.screens.assistant.AssistantSettingsScreen()
                    "launchersettings" -> com.example.ui.screens.launchersettings.LauncherSettingsScreen(
                        viewModel = viewModel
                    )
                    "callstats" -> {
                        val logs by viewModel.callLogs.collectAsState()
                        CallStatsScreen(
                            logs = logs,
                            onMakeCall = { number, simSlot ->
                                if (viewModel.confirmBeforeCall.value) {
                                    pendingCallConfirm.value = PendingCall(number, simSlot, null)
                                } else {
                                    viewModel.addCallLog(number = number, name = null, type = CallType.OUTGOING, duration = 0, simSlot = simSlot)
                                    SystemCallHelper.makeCall(context, number, simSlot)
                                }
                            },
                            onClose = { closeImmersive() }
                        )
                    }
                    "contacttools" -> ContactToolsScreen()
                    "sys" -> SysScreen(onBack = { immersiveAppId = null })
                    // The three built-in tools moved out of System Tools into the
                    // Tools tab; they are ordinary immersive routes now, so back
                    // (top-bar arrow or gesture) closes to the main pager.
                    "qrscanner" -> com.example.ui.screens.qrscanner.QrScannerScreen(
                        onClose = { closeImmersive() },
                        onOpenUrl = { url ->
                            browserInitialUrl = url
                            immersiveReturnId = null
                            isBrowserFullscreen = false
                            immersiveAppId = "web"
                        }
                    )
                    "flashlight" -> com.example.ui.screens.flashlight.FlashlightScreen(
                        onBack = { closeImmersive() }
                    )
                    "recorder" -> Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            com.example.ui.components.FloatingTopBar {
                                Text(
                                    "Sound Recorder",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            com.example.ui.screens.recorder.RecorderScreen(viewModel = viewModel)
                        }
                    }
                    "screenrecorder" -> com.example.ui.screens.screenrecorder.ScreenRecorderScreen(
                        viewModel = viewModel
                    )
                    "stopwatch" -> com.example.ui.screens.tools.TimerStopwatchScreen(
                        viewModel = viewModel,
                        onBack = { closeImmersive() }
                    )
                    "calculator" -> Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            com.example.ui.components.FloatingTopBar {
                                Text(
                                    "Calculator",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            com.example.ui.screens.calculator.CalculatorScreen(viewModel = viewModel)
                        }
                    }
                    "radio" -> com.example.ui.screens.apps.RadioScreen(
                        viewModel = viewModel,
                        onBack = { closeImmersive() }
                    )
                    "terminal" -> MyApplicationTheme(
                        themeMode = AppThemeMode.DARK,
                        accent = terminalAccent,
                        dynamicColor = terminalDynamicColors,
                        amoledBlack = terminalAmoledBlack
                    ) {
                        com.example.ui.screens.terminal.TerminalScreen(
                            onBack = { immersiveAppId = null }
                        )
                    }
                    "btinput" -> com.example.ui.screens.hid.BtInputScreen(viewModel = viewModel)
                    "bttransfer" -> com.example.ui.screens.bttransfer.BtTransferScreen(
                        onClose = { closeImmersive() },
                        viewModel = viewModel
                    )
                    "vnc" -> com.example.ui.screens.vnc.VncScreen(onClose = { immersiveAppId = null }, viewModel = viewModel)
                    "camera" -> CameraScreen(
                        onBack = { immersiveAppId = null },
                        onOpenMedia = openMediaHandler,
                        onOpenImageWithPlaylist = { file, playlist ->
                            activeImagePlaylist = playlist
                            activeImageIndex = playlist.indexOf(file).coerceAtLeast(0)
                            activeImageFile = file
                        },
                        onPhotoCaptured = { file ->
                            if (ocrCaptureRequested) {
                                ocrCaptureRequested = false
                                ocrPendingFile = file
                                immersiveAppId = "ocr"
                            } else if (scanPdfCaptureRequested) {
                                scanPdfCaptureRequested = false
                                val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
                                val ctx = this@MainActivity
                                val intent = android.content.Intent(ctx, com.example.MainActivity::class.java).apply {
                                    putExtra("scan_pdf_uri", uri.toString())
                                    flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                                startActivity(intent)
                                immersiveAppId = "scanpdf"
                            }
                        }
                    )
                    "vault" -> VaultScreen(
                        onOpenMedia = openMediaHandler
                    )
                    "webappcreator" -> com.example.ui.screens.webapp.WebAppCreatorScreen(
                        viewModel = viewModel,
                        onLaunchApp = { id ->
                            buildWebAppLaunchIntent(context, id)?.let { intent ->
                                runCatching { context.startActivity(intent) }
                            }
                        },
                        onClose = { immersiveAppId = null }
                    )
                    else -> {
                        val id = immersiveAppId
                        if (id?.startsWith("webapp:") == true) {
                            val mod = com.example.ui.screens.webapp.webAppModule(
                                id.removePrefix("webapp:")
                            )
                            if (mod != null) {
                                WebAppScreen(
                                    appId = mod.id,
                                    onExit = { immersiveAppId = null }
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Back at the app root (no tool, no media, no browser open). On the Phone
        // screen it first returns to the Home grid; then, on Home, it asks before
        // truly exiting so a stray Back cannot silently drop the user to the OS
        // launcher with every keep-alive service still running.
        val confirmExitOnBack by viewModel.confirmExitOnBack.collectAsState()

        // Launcher badges and quick-dial row. These come from narrow SQL-backed
        // flows instead of re-collecting the entire call log / address book here.
        val smsUnread by viewModel.smsUnreadCount.collectAsState()
        val missedCount by viewModel.missedCallCount.collectAsState()
        val favoriteContacts by viewModel.favoriteContacts.collectAsState()

        var showExitDialog by remember { mutableStateOf(false) }
        BackHandler {
            // ON (default): ask before leaving. OFF: consume the press silently —
            // the app stays put and no dialog appears; the Home gesture still
            // backgrounds it. Deeper screens keep their own BackHandlers and run
            // first, so this only applies once the user is back at the root.
            if (mainScreen == 1) {
                mainScreen = 0
            } else if (confirmExitOnBack) {
                showExitDialog = true
            }
        }
        pendingCallConfirm.value?.let { call ->
            com.example.ui.components.AppConfirmDialog(
                title = "Place call?",
                message = "Call ${call.number} using SIM ${call.simSlot}?",
                confirmLabel = "Call",
                onConfirm = {
                    pendingCallConfirm.value = null
                    viewModel.addCallLog(number = call.number, name = call.name, type = com.example.data.db.CallType.OUTGOING, duration = 0, simSlot = call.simSlot)
                    SystemCallHelper.makeCall(context, call.number, call.simSlot)
                },
                onDismiss = { pendingCallConfirm.value = null }
            )
        }
        if (showExitDialog) {
            ExitAppDialog(
                onDismiss = { showExitDialog = false },
                onBackground = { moveTaskToBack(true) },
                onExit = {
                    showExitDialog = false
                    stopEverything(this@MainActivity)
                    finishAndRemoveTask()
                }
            )
        }
        Scaffold { innerPadding ->
            // The Home launcher is drawn EDGE-TO-EDGE: it drops the Scaffold inset so
            // a launcher background fills behind the status and navigation bars, and
            // self-insets its grid content instead. Every other main surface keeps the
            // normal inset.
            val mainEdgeToEdge = mainScreen == 0
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        if (isBrowserFullscreen || mainEdgeToEdge) PaddingValues(0.dp)
                        else innerPadding
                    )
            ) {
                // Launch a Gecko web app in its own OS process (:webapps), where it
                // has its own Gecko profile (browser Clear Data can't log it out).
                fun launchWebApp(id: String) {
                    runCatching { context.startActivity(GeckoWebAppActivity.intent(context, id)) }
                }

                fun launchCustomWebApp(id: String) {
                    val intent = buildWebAppLaunchIntent(context, id) ?: return
                    runCatching { context.startActivity(intent) }
                }

                // One router shared by the Home grid and the dock. Core ids switch the
                // main surface / open a known screen; everything else is a plain
                // immersive route (the same ids the old hubs handed to immersiveAppId).
                fun launchById(id: String) {
                    viewModel.recordRecentApp(id)
                    when (id) {
                        "phone" -> showPhone(0)
                        "contacts" -> showPhone(1)
                        "messages" -> showPhone(2)
                        "settings" -> immersiveAppId = "settings"
                        "assistant" -> AssistantActions.start(context)
                        "whatsapp" -> runCatching {
                            context.startActivity(GeckoWebActivity.whatsappIntent(context))
                        }
                        "whatsapp2" -> runCatching {
                            context.startActivity(GeckoWebActivity2.intent(context))
                        }
                        else -> {
                            if (com.example.ui.screens.webapp.webAppModule(id) != null) {
                                launchWebApp(id)
                            } else if (com.example.ui.screens.webapp.CustomWebApps.isSlotId(id)) {
                                launchCustomWebApp(id)
                            } else {
                                immersiveAppId = id
                            }
                        }
                    }
                }

                fun launchAssistantShortcut(shortcut: PendingShortcut) {
                    when (shortcut.target) {
                        AssistantActions.TARGET_CALL -> {
                            val number = shortcut.phone?.trim().takeUnless { it.isNullOrBlank() } ?: return
                            if (viewModel.confirmBeforeCall.value) {
                                pendingCallConfirm.value = PendingCall(number, 1, null)
                            } else {
                                viewModel.addCallLog(
                                    number = number,
                                    name = null,
                                    type = CallType.OUTGOING,
                                    duration = 0,
                                    simSlot = 1
                                )
                                SystemCallHelper.makeCall(context, number, 1)
                            }
                        }
                        AssistantActions.TARGET_SMS -> {
                            val number = shortcut.phone?.trim().takeUnless { it.isNullOrBlank() } ?: return
                            smsPrefill = number
                            showPhone(2)
                        }
                        AssistantActions.TARGET_SEARCH -> {
                            val query = shortcut.query?.trim().takeUnless { it.isNullOrBlank() } ?: return
                            browserInitialUrl = "https://duckduckgo.com/?q=" + Uri.encode(query)
                            immersiveAppId = "web"
                        }
                        else -> launchById(shortcut.target)
                    }
                }

                val shortcut by pendingShortcut
                LaunchedEffect(shortcut) {
                    shortcut?.let { target ->
                        pendingShortcut.value = null
                        launchAssistantShortcut(target)
                    }
                }

                when (mainScreen) {
                    0 -> LauncherPager(
                        viewModel = viewModel,
                        shadeOpen = shadeOpen,
                        favoriteContacts = favoriteContacts,
                        missedCount = missedCount,
                        smsUnread = smsUnread,
                        onLaunch = { launchById(it) },
                        onCallContact = { number ->
                            if (viewModel.confirmBeforeCall.value) {
                                pendingCallConfirm.value = PendingCall(number, 1, null)
                            } else {
                                viewModel.addCallLog(number = number, name = null, type = CallType.OUTGOING, duration = 0, simSlot = 1)
                                SystemCallHelper.makeCall(context, number, 1)
                            }
                        },
                        onSearchWeb = { query ->
                            browserInitialUrl =
                                "https://duckduckgo.com/?q=" + Uri.encode(query)
                            immersiveAppId = "web"
                        }
                    )
                    else -> PhoneMainScreen(
                        viewModel = viewModel,
                        onMakeCall = { number, simSlot ->
                            if (viewModel.confirmBeforeCall.value) {
                                pendingCallConfirm.value = PendingCall(number, simSlot, null)
                            } else {
                                viewModel.addCallLog(number = number, name = null, type = CallType.OUTGOING, duration = 0, simSlot = simSlot)
                                SystemCallHelper.makeCall(context, number, simSlot)
                            }
                        },
                        initialTab = phoneTab,
                        onTabChanged = { phoneTab = it.coerceIn(0, 2) },
                        smsInitialNumber = smsPrefill,
                        onSmsInitialNumberConsumed = { smsPrefill = "" }
                    )
                }
            }
            }
        }
        }

        if (shadeAllowed) {
            LauncherShadeOverlay(
                viewModel = viewModel,
                enabled = true,
                immersive = shouldBeImmersive,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f),
                openRequest = shadeOpenRequest,
                initialPage = shadeOpenPage,
                onShadeOpenChange = { shadeOpen = it }
            )
        }
    }
    }
}

/**
 * The back-at-app-root confirmation. "Exit & stop everything" is the destructive
 * path: web-app / terminal keep-alive foreground services and audio playback are
 * stopped, then the task is removed. System-bound services (SMS notification
 * listener, InCallService) are left alone — they are owned by the OS.
 */
@Composable
private fun ExitAppDialog(
    onDismiss: () -> Unit,
    onBackground: () -> Unit,
    onExit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exit NJ-Dialer?") },
        text = {
            Text(
                "Stopping everything also closes any running web apps, the " +
                    "terminal, audio playback and the local Linux desktop."
            )
        },
        confirmButton = {
            TextButton(onClick = onExit) {
                Text("Exit & stop everything", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onBackground) { Text("Keep running in background") }
        }
    )
}

/** Stop the keep-alive + playback services so the app is actually gone. */
private fun stopEverything(context: android.content.Context) {
    runCatching { com.example.service.WebAppKeepAliveService.releaseOthers(context) }
    runCatching { com.example.service.WebAppKeepAliveService.releaseOwn(context) }
    runCatching { com.example.service.TerminalKeepAliveService.releaseOthers(context) }
    runCatching { com.example.service.TerminalKeepAliveService.releaseOwn(context) }
    runCatching { com.example.service.AssistantOverlayService.stop(context) }
    runCatching {
        context.startService(
            android.content.Intent(context, com.example.service.AudioPlaybackService::class.java)
                .setAction(com.example.service.AudioPlaybackService.ACTION_STOP)
        )
    }
    }
