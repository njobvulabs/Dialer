package com.example.ui.screens.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.example.util.DownloadManager as UtilDownloadManager
import com.example.util.geckoDownloadName
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse

internal class BrowserUi : TabEvents {
    var ctx: Context? = null
    var mgr: GeckoTabManager? = null

    val urlInput = mutableStateOf("")
    val progress = mutableIntStateOf(0)
    val loading = mutableStateOf(false)
    val canGoBack = mutableStateOf(false)
    val canGoForward = mutableStateOf(false)
    val secure = mutableStateOf(true)
    val toolbarShown = mutableStateOf(true)
    val fullscreenActive = mutableStateOf(false)
    val matchInfo = mutableStateOf("")
    val reloadTick = mutableIntStateOf(0)
    val crashTick = mutableIntStateOf(0)
    var onFullscreenCb: ((Boolean) -> Unit)? = null
    var onRecordHistory: ((String, String) -> Unit)? = null
    override var onHistoryChanged: (() -> Unit)? = null
    override var onVisit: ((String, String) -> Unit)? = null
    /** B7: the tab's back/forward list changed, so an open trail menu must redraw. */



    var uploadLauncher: androidx.activity.result.ActivityResultLauncher<Intent>? = null
    var permLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
    var pendingUploadPrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    var pendingUploadResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    var pendingAndroidPerms: GeckoSession.PermissionDelegate.Callback? = null
    val ctxMenuTarget = mutableStateOf<LinkContextTarget?>(null)
    override var currentSelection: GeckoSession.SelectionActionDelegate.Selection? = null

    private fun toast(msg: String) {
        val c = ctx ?: return
        runCatching { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onPageStart(tab: GeckoTab, url: String) {
        urlInput.value = url
        progress.value = 5
        loading.value = true
        tab.crashed = false
    }

    override fun onPageStop(tab: GeckoTab, success: Boolean) {
        loading.value = false
        progress.value = 100
        if (success) {
            tab.crashed = false
            if (!tab.isPrivate) {
                val url = tab.url
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    onRecordHistory?.invoke(tab.title.ifBlank { url }, url)
                }
            }
        }
    }

    override fun onProgress(tab: GeckoTab, progress: Int) {
        this.progress.value = progress
    }

    override fun onTitleChange(tab: GeckoTab, title: String) {}

    override fun onLocationChange(tab: GeckoTab, url: String, sec: Boolean) {
        urlInput.value = url
        secure.value = sec || !url.startsWith("http")
    }

    override fun onNavStateChanged(tab: GeckoTab, back: Boolean, forward: Boolean) {
        canGoBack.value = back
        canGoForward.value = forward
    }

    override fun onExternalDownload(response: WebResponse) {
        val c = ctx ?: run { runCatching { response.body?.close() }; return }
        
        // Get filename and size from response
        val (fileName, mimeType) = geckoDownloadName(response)
        val contentLength = response.headers["Content-Length"]?.toLongOrNull()
        
        // Show confirmation dialog
        val alert = AlertDialog.Builder(c)
            .setTitle("Download")
            .setMessage("Download \"$fileName\"${contentLength?.let { " (${humanBytes(it)})" } ?: ""}?")
            .setPositiveButton("Yes") { _, _ ->
                // Create download job and start it
                val job = UtilDownloadManager.createDirectDownloadJob(response.uri, fileName, mimeType, contentLength)
                UtilDownloadManager.startDirectDownload(c, job)
            }
            .setNegativeButton("No") { _, _ ->
                runCatching { response.body?.close() }
            }
            .setOnCancelListener { runCatching { response.body?.close() } }
            .create()
        
        // Show on main thread
        Handler(Looper.getMainLooper()).post { alert.show() }
    }

    /** Human-readable byte size, e.g. "35.7 MB". */
    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1073741824f)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576f)
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024f)
        bytes > 0 -> "$bytes B"
        else -> ""
    }

    override fun onTabCrashed(tab: GeckoTab, killed: Boolean) {
        tab.crashed = true
        loading.value = false
        crashTick.value++
        toast(if (killed) "Page was killed" else "Page crashed")
    }

    override fun onFullScreenChanged(active: Boolean) {
        fullscreenActive.value = active
        toolbarShown.value = !active
        onFullscreenCb?.invoke(active)
    }

    override fun onDynamicToolbarVisibility(show: Boolean) {
        if (!fullscreenActive.value) toolbarShown.value = show
    }

    override fun onOpenInNewTab(url: String, activate: Boolean, privateTab: Boolean) {
        // Inherit the requesting tab's private flag. Hard-coding false here used to
        // promote a link opened from a private tab into a normal tab, which is
        // both a privacy leak and a "why is my private tab in the drawer" bug.
        mgr?.newTab(url, privateTab, activate)
        if (!activate) toast("Opened in background tab")
    }

    override fun onCloseTabRequest(tab: GeckoTab) {
        val m = mgr ?: return
        val idx = m.tabs.indexOfFirst { it.id == tab.id }
        if (idx >= 0) m.closeTab(idx)
    }

    override fun onLinkContextMenu(tab: GeckoTab, target: LinkContextTarget) {
        ctxMenuTarget.value = target
    }

    override fun onFilePrompt(
        prompt: GeckoSession.PromptDelegate.FilePrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val c = ctx ?: return null
        pendingUploadResult?.complete(pendingUploadPrompt?.dismiss())
        pendingUploadPrompt = prompt
        val deferred = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        pendingUploadResult = deferred
        uploadLauncher?.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        )
        return deferred
    }

    override fun requestAndroidPermissions(
        permissions: Array<out String>?,
        callback: GeckoSession.PermissionDelegate.Callback
    ) {
        val c = ctx ?: run { callback.reject(); return }
        val missing = permissions?.filter {
            runCatching { c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.getOrDefault(true)
        }.orEmpty()
        if (missing.isEmpty()) {
            callback.grant()
        } else {
            pendingAndroidPerms = callback
            permLauncher?.launch(missing.toTypedArray())
        }
    }

    fun handleUploadResult(ok: Boolean, paths: List<String>) {
        val prompt = pendingUploadPrompt
        val deferred = pendingUploadResult
        pendingUploadPrompt = null
        pendingUploadResult = null
        val activity = ctx as? Activity
        if (ok && activity != null && prompt != null) {
            val uris = paths.mapNotNull { p ->
                runCatching { android.net.Uri.parse(p) }.getOrNull()
            }.toTypedArray()
            deferred?.complete(prompt.confirm(activity, uris))
        } else {
            deferred?.complete(prompt?.dismiss())
        }
    }

    fun handlePermResult(grants: Map<String, Boolean>) {
        val cb = pendingAndroidPerms
        pendingAndroidPerms = null
        if (grants.values.all { it }) cb?.grant() else cb?.reject()
    }
}

