package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.service.WebAppKeepAliveService
import com.example.util.autoDownloadExternal
import com.example.util.contextViewTheme
import com.example.util.PickedUpload
import com.example.util.WhatsAppCallEnabler
import com.example.util.pickFileToCache
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import java.io.File

/**
 * WhatsApp Web on the GeckoView engine (Firefox's browser engine).
 *
 * The system WebView on some Android 11+ devices cannot gather ICE candidates
 * (crbug 1240237), which makes WebRTC calls fail with "calling couldn't start".
 * GeckoView ships its own WebRTC implementation, so this activity is used for
 * WhatsApp instead of the android.webkit.WebView.
 *
 * BARES GECKOVIEW ON PURPOSE (per-call-audio revert, v2.6).
 *
 * There is deliberately NO bundled WebExtension and NO page-world script here.
 * Everything that was added for the stuck-mute bug has been removed: the
 * getUserMedia/AEC wrapper, the calling-gating AB-prop overrides (including
 * `calling_lid_version` / `heartbeat_interval_s`), the document.title markers,
 * the content-script reload check, the `MODE_IN_COMMUNICATION` A/B and the whole
 * call-diagnostics subsystem. A 2026 test with the comm-mode A/B flipped OFF
 * and Bluetooth disconnected was STILL muted with those removals already in
 * place, which cleared both confounders and left the extension as the only
 * app-side intervention left.
 *
 * Plain GeckoView + a desktop Firefox UA is the state in which voice and video
 * calls both worked. WhatsApp Web supports WebRTC calling natively in desktop
 * Firefox, so the gating hooks were redundant — and they rewrote WA's own
 * call-signalling config on a 500ms setInterval for the life of the page, which
 * is a far more plausible mute cause than anything remaining natively.
 *
 * Kept because calls do not work without it: the desktop UA +
 * VIEWPORT_MODE_DESKTOP (otherwise WA serves a mobile/QR page),
 * suspendMediaWhenInactive(false), onMediaPermissionRequest granting the first
 * source of each kind, and the RECORD_AUDIO/CAMERA pre-grant. Those are
 * permission plumbing, not call-audio intervention — never move the mic into
 * an AudioManager mode or wrap getUserMedia here.
 */
open class GeckoWebActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PROFILE_DIR = "profile_dir"

        /**
         * Retired extension ids, UNINSTALLED from existing profiles on every
         * start (see [purgeWhatsAppExtension]). Kept as constants so the purge
         * stays correct after the assets are gone — an installed extension
         * survives app updates and would otherwise keep injecting its content
         * scripts forever.
         */
        private const val WHATSAPP_EXT_ID = "whatsapp-dialer-bridge@mozilla.org"
        private const val OLD_PAGEMAIN_ID = "whatsapp-pagemain@njdialer"
        private const val WHATSAPP1_PROFILE_DIR = "whatsapp1-profile"

        // ---- Gecko audio-processing A/B (see [applyAudioProcessingPref]) ----
        // Evidence that made this the only remaining app-side lever for
        // WhatsApp's silent outgoing call mic: the mic hardware, this ROM's
        // capture path, GeckoView getUserMedia (WhatsApp/Telegram/Facebook
        // voice notes all record) and GeckoView WebRTC send+receive (Google
        // Meet and Zoom both carry voice AND video) are all confirmed working.
        // WhatsApp calls alone are one-way, and they reproduce identically in
        // the :whatsapp process (custom profile, stale Firefox/140 UA) and in
        // the main-process in-app browser (default profile, honest 153 UA), so
        // the process, the profile and the UA are all eliminated too. The one
        // link never varied is the APM Gecko builds for WhatsApp's call-time
        // constraints, which differ from the plain getUserMedia() a voice note
        // uses — so this A/B toggles exactly that.
        private const val PREFS = "app_settings"
        private const val KEY_PROCESSING = "whatsapp_audio_processing"

        private const val PROC_DEFAULT = "default"
        private const val PROC_OFF = "off"
        private const val PROC_PLATFORM = "platform"

        private val PROCESSING_PREFS = listOf(
            "media.getusermedia.audio.processing.aec.enabled",
            "media.getusermedia.audio.processing.noise.enabled",
            "media.getusermedia.audio.processing.agc.enabled",
            "media.getusermedia.audio.processing.hpf.enabled",
            "media.getusermedia.audio.processing.transient.enabled"
        )
        private const val PLATFORM_PREF = "media.getusermedia.audio.processing.platform.enabled"

        private fun processingLabel(mode: String) = when (mode) {
            PROC_OFF -> "Off"
            PROC_PLATFORM -> "Platform"
            else -> "Default"
        }

        private fun currentProcessingMode(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PROCESSING, PROC_DEFAULT) ?: PROC_DEFAULT

        /**
         * Writes the selected Gecko audio-processing mode into the runtime's
         * profile.
         *
         * Called from [ensureRuntime] immediately after `GeckoRuntime.create()`
         * and BEFORE any GeckoSession exists, because Gecko builds its audio
         * processing chain once per process on the first capture — setting these
         * prefs afterwards has no effect on the already-initialised chain, which
         * is why switching the mode in the menu requires closing and reopening
         * WhatsApp.
         *
         * Default clears the user prefs, which restores Gecko's own defaults
         * exactly (all five processors enabled, platform.enabled=false, i.e. the
         * software APM). It is the shipped default so that no unvalidated
         * intervention is active unless a mode has actually been shown to work.
         */
        private fun applyAudioProcessingPref(context: Context) {
            val mode = currentProcessingMode(context)
            runCatching {
                // Any previous mode may have left user prefs behind; Default
                // must clear all six so it is a true reset to engine defaults.
                PROCESSING_PREFS.forEach { GeckoPreferenceController.clearGeckoUserPref(it) }
                GeckoPreferenceController.clearGeckoUserPref(PLATFORM_PREF)
                when (mode) {
                    PROC_OFF -> PROCESSING_PREFS.forEach {
                        GeckoPreferenceController.setGeckoPref(
                            it, false, GeckoPreferenceController.PREF_BRANCH_USER
                        )
                    }
                    PROC_PLATFORM -> {
                        PROCESSING_PREFS.forEach {
                            GeckoPreferenceController.setGeckoPref(
                                it, false, GeckoPreferenceController.PREF_BRANCH_USER
                            )
                        }
                        // ROM processing instead of Gecko's software chain, so
                        // the software processors must stay off to avoid double
                        // processing.
                        GeckoPreferenceController.setGeckoPref(
                            PLATFORM_PREF, true, GeckoPreferenceController.PREF_BRANCH_USER
                        )
                    }
                }
            }
        }

        // GeckoView allows only ONE GeckoRuntime per process. Hold it in a
        // per-process static (each of :whatsapp / :whatsapp2 has its own copy),
        // so recreating the activity never calls GeckoRuntime.create() twice.
        @Volatile private var processRuntime: GeckoRuntime? = null

        // The in-flight file prompt lives here (process-wide) instead of as
        // instance fields: opening the system picker backgrounds this Activity
        // and aggressive OEM ROMs (Tecno HiOS) recreate it on return, which
        // would null instance fields and turn prompt.confirm() into a silent
        // no-op — WhatsApp then receives nothing and the pick looks dead.
        private var pendingPrompt: GeckoSession.PromptDelegate.FilePrompt? = null
        private var pendingPromptResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null

        private fun capturePendingPrompt(
            prompt: GeckoSession.PromptDelegate.FilePrompt,
            deferred: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
        ) {
            pendingPrompt = prompt
            pendingPromptResult = deferred
        }

        private fun consumePendingPrompt():
            Pair<GeckoSession.PromptDelegate.FilePrompt?, GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?> {
            val p = pendingPrompt
            val d = pendingPromptResult
            pendingPrompt = null
            pendingPromptResult = null
            return p to d
        }

        private fun clearPendingPrompt() {
            pendingPrompt = null
            pendingPromptResult = null
        }

        fun intent(
            ctx: Context,
            url: String,
            title: String,
            profileDir: String? = null,
            activityClass: Class<out GeckoWebActivity> = GeckoWebActivity::class.java
        ): Intent =
            Intent(ctx, activityClass)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PROFILE_DIR, profileDir)

        /**
         * WhatsApp account 1 with its own Gecko profile directory, mirroring
         * GeckoWebActivity2 for account 2. Without this it shares GeckoView's
         * default profile with the main-process browser, so "Clear browsing
         * data" in the browser (which clears the default profile's cookies
         * and storage) silently logs out WhatsApp 1 while account 2 (isolated
         * in whatsapp2-profile) stays signed in.
         */
        fun whatsappIntent(ctx: Context): Intent =
            intent(
                ctx,
                "https://web.whatsapp.com/",
                "WhatsApp Web",
                profileDir = ctx.filesDir.absolutePath + "/" + WHATSAPP1_PROFILE_DIR,
                activityClass = GeckoWebActivity::class.java
            )
    }

    private lateinit var geckoView: GeckoView
    private var session: GeckoSession? = null
    private var titleView: TextView? = null
    private var currentUrl = "https://web.whatsapp.com/"
    private var appTitle = "WhatsApp Web"
    private var profileDir: String? = null
    private var pageLoads = 0
    private var geckoRuntime: GeckoRuntime?
        get() = processRuntime
        set(value) { processRuntime = value }

    private var pendingPermission: GeckoSession.PermissionDelegate.Callback? = null
    private var mediaPreGrantDone = false
    private var keyboardVisible = false

    private var topBarView: LinearLayout? = null
    private var handleView: View? = null
    private var statusPad = 0
    private var chromeVisible = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        if (granted) {
            pendingPermission?.grant()
        } else {
            pendingPermission?.reject()
        }
        pendingPermission = null
    }

    /** Pre-grants microphone + camera once at startup so a WA call's getUserMedia
     *  never has to wait on a permission dialog mid-call (which WA treats as a
     *  failed mic and locks the mute toggle). */
    private val mediaPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
    }

    private fun preGrantMediaPermissions(force: Boolean = false) {
        val missing = listOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.CAMERA
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && (force || !mediaPreGrantDone)) {
            mediaPreGrantDone = true
            mediaPermLauncher.launch(missing.toTypedArray())
        }
    }

    private val uploadLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val (prompt, deferred) = consumePendingPrompt()
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data!!
            val clipData = data.clipData
            val uris = mutableListOf<android.net.Uri>()
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i).uri?.let { uris.add(it) }
                }
            } else {
                data.data?.let { uris.add(it) }
            }
            if (uris.isEmpty()) {
                runCatching {
                    android.widget.Toast.makeText(
                        this, "Could not attach selected file(s)", android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                deferred?.complete(prompt?.dismiss())
            } else {
                // GeckoView's FilePrompt.confirm() only supports file:// URIs
                // (Mozilla Bug 1515440/1591640: getFile() returns null for SAF
                // content:// URIs — even FileProvider ones — and the pick is
                // SILENTLY dropped, so attachments looked "lost"). Copy every
                // pick into cache keeping its original display name + extension
                // (a renamed temp `picker_...` file made WhatsApp rename
                // attachments / treat photos as generic documents), then confirm
                // the FilePrompt with Uri.fromFile(file) file:// URIs.
                val cached = mutableListOf<PickedUpload>()
                var failed = 0
                for (u in uris) {
                    val p = pickFileToCache(this, u)
                    if (p != null) cached.add(p) else failed++
                }
                android.util.Log.i(
                    "GeckoWebActivity",
                    "file picker: cached=${cached.size} failed=$failed (of ${uris.size} picked)"
                )
                val out = cached.map { android.net.Uri.fromFile(it.file) }
                if (out.isEmpty()) {
                    runCatching {
                        android.widget.Toast.makeText(
                            this,
                            "Could not attach selected file(s)",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    android.util.Log.i("GeckoWebActivity", "file picker: all copies failed, dismissing")
                    deferred?.complete(prompt?.dismiss())
                } else {
                    android.util.Log.i("GeckoWebActivity", "file picker confirm: ${out.size} uri(s)")
                    for (u in out) {
                        android.util.Log.i(
                            "GeckoWebActivity",
                            "file picker uri: ${u} type=${contentResolver.getType(u)}"
                        )
                    }
                    try {
                        if (prompt != null) {
                            deferred?.complete(prompt.confirm(this, out.toTypedArray()))
                        } else {
                            // Prompt lost (Activity recreated while the picker was
                            // open / process restarted). A stale confirm is a
                            // silent no-op — surface it so it isn't confused with
                            // a successful attach.
                            android.util.Log.w(
                                "GeckoWebActivity",
                                "file picker: prompt lost before confirm, nothing sent"
                            )
                            runCatching {
                                Toast.makeText(
                                    this,
                                    "Attach expired, please try again",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    } catch (t: Throwable) {
                        android.util.Log.e("GeckoWebActivity", "file prompt confirm failed", t)
                        deferred?.complete(prompt?.dismiss())
                    }
                }
            }
        } else {
            android.util.Log.i("GeckoWebActivity", "file picker canceled")
            deferred?.complete(prompt?.dismiss())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        preGrantMediaPermissions()
        currentUrl = intent.getStringExtra(EXTRA_URL) ?: currentUrl
        profileDir = intent.getStringExtra(EXTRA_PROFILE_DIR)
        appTitle = intent.getStringExtra(EXTRA_TITLE) ?: "WhatsApp Web"
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setupUi(appTitle)
        enterImmersive()
        setChromeVisible(false)
        initEngine()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val s = session
                    if (s != null && pageLoads > 1) {
                        runCatching { s.goBack() }.onFailure { finish() }
                    } else {
                        finish()
                    }
                }
            }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newUrl = intent.getStringExtra(EXTRA_URL)
        if (newUrl != null && newUrl != currentUrl) {
            currentUrl = newUrl
            runCatching { session?.loadUri(newUrl) }
        }
        runCatching { session?.setActive(true) }
        runCatching { geckoView.requestFocus() }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun roundedChip(bg: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10.dp().toFloat()
            setColor(bg)
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
        topBarView?.visibility = if (visible) View.VISIBLE else View.GONE
        handleView?.let { v ->
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return@let
            lp.topMargin = if (visible) (statusPad + 56.dp()) else (statusPad + 6.dp())
            v.requestLayout()
        }
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

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun setupUi(title: String) {
        val theme = contextViewTheme(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(theme.background)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        statusPad = statusBarHeight()
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16.dp().toFloat()
                setColor(theme.surface)
            }
            elevation = 4.dp().toFloat()
            setPadding(8.dp(), 6.dp(), 8.dp(), 6.dp())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (statusPad + 8.dp())
                val maxW = 840.dp()
                val w = resources.displayMetrics.widthPixels
                val hm = if (w > maxW) ((w - maxW) / 2).coerceAtLeast(12.dp()) else 12.dp()
                leftMargin = hm
                rightMargin = hm
            }
        }
        titleView = TextView(this).apply {
            text = title
            setTextColor(theme.onSurface)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
        }
        val spacer = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        val exit = TextView(this).apply {
            text = "Exit"
            setTextColor(theme.onPrimaryContainer)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setBackground(roundedChip(theme.primaryContainer))
            setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
            setOnClickListener { promptExit() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 6.dp() }
        }
        topBar.addView(titleView)
        topBar.addView(spacer)
        topBar.addView(exit)
        topBarView = topBar
        content.addView(topBar)
        root.addView(content)
        geckoView = GeckoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        runCatching { session?.settings?.setUserAgentOverride(WhatsAppCallEnabler.DESKTOP_FIREFOX_UA) }
        content.addView(geckoView)
        val handleView = TextView(this).apply {
            text = "⌄"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.argb(90, 255, 255, 255))
            setBackgroundColor(Color.parseColor("#1A000000"))
            setPadding(14.dp(), 4.dp(), 14.dp(), 4.dp())
            setOnClickListener { toggleChrome() }
            // Hidden debug: long-press the handle to flip the hw-media-render
            // test knob for the black-remote-video investigation.
            setOnLongClickListener {
                showRenderDebugDialog()
                true
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = (statusPad + 56.dp())
            }
        }
        this.handleView = handleView
        root.addView(handleView)
        setContentView(root)
        setupKeyboardListener(root)
    }

    private fun initEngine() {
        // ensureRuntime() purges the retired WhatsApp extension before the
        // session below can load a page - no content scripts, no page-world
        // call-signalling overrides, plain GeckoView + desktop UA.
        val runtime = ensureRuntime()
        val session = buildSession()
        this.session = session
        geckoView.setSession(session)
        openSession(session, runtime)
    }

    private fun buildSession(): GeckoSession {
        val session = GeckoSession(
            GeckoSessionSettings.Builder()
            .userAgentOverride(WhatsAppCallEnabler.DESKTOP_FIREFOX_UA)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
            .suspendMediaWhenInactive(false)
            .build()
        )

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                val t = title ?: currentUrl
                titleView?.text = t.ifBlank { currentUrl }
            }

            override fun onCrash(session: GeckoSession) {
                runOnUiThread { recoverFromCrash("page crashed") }
            }

            override fun onKill(session: GeckoSession) {
                runOnUiThread { recoverFromCrash("page was killed") }
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                runOnUiThread {
                    runCatching { com.example.util.autoDownloadExternal(this@GeckoWebActivity, response, "nj-whatsapp-download") }
                }
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                val target = element.linkUri ?: element.srcUri ?: return
                runOnUiThread { showLinkMenu(target) }
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                pageLoads++
                currentUrl = url
            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                isInBFCache: Boolean
            ) {
                if (!url.isNullOrEmpty()) currentUrl = url
            // no-op
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? = null
        }

        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                type: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int> {
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            }

            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<String>?,
                callback: GeckoSession.PermissionDelegate.Callback
            ) {
                val list = permissions.orEmpty()
                val missing = list
                    .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) {
                    callback.grant()
                } else {
                    pendingPermission = callback
                    permissionLauncher.launch(missing.toTypedArray())
                }
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) {
                // Grant the first offered source of each kind, exactly like
                // GeckoView's documented example and the browser/web-app
                // delegates in this app. (An earlier "prefer a specific
                // non-default device id" heuristic handed Gecko a source it
                // could not start, producing a silent call track that
                // WhatsApp shows as a stuck-muted mic.)
                val mic = audio?.firstOrNull {
                    it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE
                } ?: audio?.firstOrNull()
                val cam = video?.firstOrNull {
                    it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA
                } ?: video?.firstOrNull()
                callback.grant(cam, mic)
            }
        }

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val deferred = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                android.util.Log.i(
                    "GeckoWebActivity",
                    "file prompt: type=${prompt.type} " +
                        "mimeTypes=${prompt.mimeTypes?.joinToString() ?: "null"} capture=${prompt.capture}"
                )
                capturePendingPrompt(prompt, deferred)
                runOnUiThread {
                    // Must use the ActivityResult launcher - a plain startActivity
                    // never delivers the picked files back to uploadLauncher.
                    uploadLauncher.launch(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                    )
                }
                return deferred
            }
        }

        return session
    }

    /**
     * Uninstall the retired bundled WhatsApp extension from this profile.
     *
     * The extension is GONE by decision (see the class KDoc): it installed a
     * page-world script that rewrote WhatsApp's own call-signalling config
     * (`enable_web_calling`, `calling_lid_version: 1`, `heartbeat_interval_s: 5`)
     * on a 500ms setInterval for the life of the page, plus a getUserMedia
     * wrapper and document.title markers. Plain GeckoView + desktop UA is the
     * configuration in which calls worked.
     *
     * This purge is REQUIRED, not optional cleanup: an installed WebExtension is
     * stored in the profile and survives app updates, so merely deleting the
     * asset + the install call would leave it injecting content scripts into
     * every page forever and the revert would not actually change on-device
     * behaviour. Runs from [ensureRuntime] before any session exists, so it
     * completes before the first page load. Idempotent - uninstalling an absent
     * extension is a no-op. Mirrors the existing `BrowserEngine.purgeUbo`
     * pattern used for uBlock.
     */
    private fun purgeWhatsAppExtension(runtime: GeckoRuntime) {
        runCatching {
            runtime.webExtensionController.list().accept({ extensions ->
                extensions
                    ?.filter { it.id == WHATSAPP_EXT_ID || it.id == OLD_PAGEMAIN_ID }
                    ?.forEach { ext ->
                        runCatching { runtime.webExtensionController.uninstall(ext) }
                        android.util.Log.i("GeckoWebActivity", "purged ${ext.id}")
                    }
            }, { })
        }
    }

    private fun registerWhatsappPane(runtime: GeckoRuntime) { }

    private fun openSession(session: GeckoSession, runtime: GeckoRuntime) {
        session.open(runtime)
        runCatching { session.setActive(true) }
        runCatching { session.settings?.userAgentOverride = WhatsAppCallEnabler.DESKTOP_FIREFOX_UA }
        runCatching { session.settings?.viewportMode = GeckoSessionSettings.VIEWPORT_MODE_DESKTOP }
        runCatching { session.loadUri(currentUrl) }
        // Try to zoom out on desktop WhatsApp for better fit
        geckoView.postDelayed({ runCatching { session.purgeHistory() } }, 2000)
    }

    fun setInChat(inChat: Boolean) { }

    override fun onResume() {
        super.onResume()
        runCatching { session?.setActive(true) }
        runCatching { geckoView.requestFocus() }
        // Now that the page is visible again it no longer needs the keep-alive
        // foreground service (a foreground activity pins its own process).
        WebAppKeepAliveService.releaseOwn(this)
    }

    override fun onPause() {
        super.onPause()
        runCatching { session?.setActive(false) }
    }

    /**
     * Two-choice Exit for the top-bar Exit button:
     *  - keep the web app alive in its background task and return to NJ-Dialer
     *    (reopening resumes the live page), or
     *  - fully close the web app (the session is discarded; the Gecko profile is
     *    kept, so the next open reloads the page but stays signed in).
     */
    private fun promptExit() {
        if (isFinishing || isDestroyed) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Exit web app")
            .setItems(arrayOf("Exit & keep running", "Close web app")) { _, which ->
                when (which) {
                    0 -> exitToNjDialer()
                    1 -> closeWebApp()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Hand off to NJ-Dialer. FLAG_ACTIVITY_CLEAR_TOP makes the system destroy
     * every activity sitting above MainActivity in the task, so this also takes
     * the web app out of the task.
     */
    private fun launchMainActivity() {
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

    private fun exitToNjDialer() {
        // Keep this web app's process alive so reopening resumes without reload,
        // releasing any previously kept-alive web app (last-used only).
        runCatching {
            WebAppKeepAliveService.releaseOthers(this)
            androidx.core.content.ContextCompat.startForegroundService(
                this,
                WebAppKeepAliveService.startIntent(this, appTitle)
            )
        }
        // Deliberately no finish() here: the session is an instance field, so this
        // activity has to stay alive to own it. The Gecko web apps can finish()
        // because they keep their session in GeckoWebAppHolder.
        launchMainActivity()
    }

    /**
     * Fully close the web app, the way the working Gecko web apps do.
     *
     * The session is discarded and the keep-alive pin dropped, then the SAME
     * CLEAR_TOP hand-off that exitToNjDialer() uses is performed instead of
     * relying on a bare finish(). That hand-off is processed by the system server
     * and reliably removes this activity from the task, whereas finish() inside
     * :whatsapp could be lost if the ROM freezes the process before the destroy is
     * dispatched (which left WhatsApp un-closeable while every other web app
     * worked). finish() is still called as a fallback for the case where this
     * activity is the task root and CLEAR_TOP has nothing above MainActivity.
     */
    private fun closeWebApp() {
        runCatching { geckoView.releaseSession() }
        runCatching { session?.close() }
        session = null
        WebAppKeepAliveService.releaseOwn(this)
        launchMainActivity()
        finish()
    }

    private fun ensureRuntime(): GeckoRuntime {
        geckoRuntime?.let { return it }
        val builder = GeckoRuntimeSettings.Builder().remoteDebuggingEnabled(true)
        val dir = profileDir
        if (!dir.isNullOrBlank()) {
            runCatching { File(dir).mkdirs() }
            builder.arguments(arrayOf("-profile", dir))
        }
        val rt = GeckoRuntime.create(applicationContext, builder.build())
        // Must run before any GeckoSession exists: Gecko builds its audio
        // processing chain once per process on the first capture, so the
        // selected mode has to be in the page can call getUserMedia().
        applyAudioProcessingPref(applicationContext)
        purgeWhatsAppExtension(rt)
        geckoRuntime = rt
        return rt
    }

    /** Hidden (handle long-press) menu: page reload + the Gecko audio-processing A/B.
     *
     *  The second row cycles Default -> Off -> Platform and only writes Gecko
     *  prefs; it never touches an Android audio API. The toast is not optional:
     *  `geckoRuntime` is a per-process singleton and Gecko builds its audio
     *  processing chain once per process on the first capture, so a mode change
     *  cannot affect the running session. The user must fully close the web app
     *  ("Close web app", not "Exit & keep running" — the keep-alive service pins
     *  the process) and reopen it.
     *
     *  The call-audio-route A/B toggle was REMOVED together with
     *  `applyCallAudioMode`/`commModeForced` - forcing MODE_IN_COMMUNICATION
     *  during a call was one of the interventions this revert strips, and it is
     *  exactly the kind of thing that must not be reachable from a menu again.
     *  So must the former "Show call/media diagnostics", "Mic level test",
     *  "Share latest call log" and "Clear call logs" rows: the mic test opened
     *  competing `AudioRecord`s on the live call's mic and force-cleared the
     *  system-wide `AudioManager.isMicrophoneMute`, and the 3-second snapshot
     *  loop enumerated devices while a call was up - both interfered with the
     *  very capture path they observed. Do not re-add either. */
    private fun showRenderDebugDialog() {
        if (isFinishing || isDestroyed) return
        val mode = currentProcessingMode(this)
        val items = arrayOf("Reload WhatsApp", "Gecko audio processing: ${processingLabel(mode)}")
        android.app.AlertDialog.Builder(this)
            .setTitle("WhatsApp")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        Toast.makeText(this, "Reloading WhatsApp\u2026", Toast.LENGTH_SHORT).show()
                        runCatching { session?.reload() }
                    }
                    1 -> {
                        val next = when (mode) {
                            PROC_DEFAULT -> PROC_OFF
                            PROC_OFF -> PROC_PLATFORM
                            else -> PROC_DEFAULT
                        }
                        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putString(KEY_PROCESSING, next).apply()
                        // Persist to the live profile too, so the next process
                        // start picks it up even if the user never reopens this
                        // menu. Harmless if the chain is already built.
                        runCatching {
                            applyAudioProcessingPref(this)
                        }
                        Toast.makeText(
                            this,
                            "Audio processing: ${processingLabel(next)} \u2014 fully close WhatsApp and reopen",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLinkMenu(url: String) {
        val options = arrayOf("Download Video", "Copy link")
        android.app.AlertDialog.Builder(this)
            .setTitle("Link options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        com.example.util.DownloadBridge.pendingUrl = url
                        Toast.makeText(this, "Opening in Video Downloader...", Toast.LENGTH_SHORT).show()
                        startActivity(
                            android.content.Intent(this, com.example.MainActivity::class.java)
                                .putExtra(com.example.MainActivity.EXTRA_OPEN_DOWNLOADER, true)
                                .addFlags(
                                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                        or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                                )
                        )
                    }
                    1 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("link", url))
                        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun recoverFromCrash(reason: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, "WhatsApp $reason — restarting", Toast.LENGTH_SHORT).show()
        val runtime = geckoRuntime ?: return
        val old = session
        session = null
        runCatching { old?.close() }
        runCatching { geckoView.releaseSession() }
        val s = buildSession()
        session = s
        geckoView.setSession(s)
        openSession(s, runtime)
    }

    // Installed per process by NjApp (which also covers :webapps, where the
    // Gecko web apps run and where an unhandled crash previously left no trace).
    // Kept idempotent, so this is a no-op here.
    private fun installCrashLogger() {
        com.example.util.CrashLog.install()
    }

    private fun setupKeyboardListener(root: View) {
        root.viewTreeObserver.addOnGlobalLayoutListener {
            try {
                val rect = Rect()
                root.getWindowVisibleDisplayFrame(rect)
                val screenH = root.height
                val keyH = screenH - rect.height()
                val shown = keyH > screenH * 0.25
                if (shown && !keyboardVisible) {
                    keyboardVisible = true
                    root.setPadding(0, 0, 0, keyH)
                } else if (!shown && keyboardVisible) {
                    keyboardVisible = false
                    root.setPadding(0, 0, 0, 0)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        // Only reap the file-prompt holder on a real user exit. On recreation
        // (config/OEM) the holder must survive so the new instance can still
        // deliver the system picker's result.
        if (isFinishing) clearPendingPrompt()
        super.onDestroy()
        runCatching { session?.close() }
        session = null
    }
}
