package com.example.util

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Floating quick-download dialog used inside web apps (Facebook, Instagram,
 * YouTube...) so users can grab a video without leaving the app.
 *
 * Single-screen design: link entry, quality picker and Detect stay visible at
 * all times; the live download queue with Pause/Resume/Retry/Cancel sits below.

import com.example.ui.components.AppIcons
 */
object QuickDownloadDialog {

    data class DetectedMedia(val url: String, val kind: String)

    private var dialogRef: AlertDialog? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Last links seen this session — used as fallback when the box is empty. */
    @Volatile private var lastDetected: String? = null
    @Volatile private var lastSubmitted: String? = null

    /** Ring buffer of media URLs observed by the host activity's WebViewClient. */
    private val mediaLog = ArrayDeque<String>(21)

    /** Called from shouldInterceptRequest for any media-ish URL. Thread-safe. */
    fun logMediaUrl(url: String) {
        synchronized(mediaLog) {
            mediaLog.remove(url)
            mediaLog.addFirst(url)
            while (mediaLog.size > 20) mediaLog.removeLast()
        }
    }

    fun isMediaUrl(url: String): Boolean {
        val u = url.substringBefore('#').lowercase()
        return MEDIA_REGEX.containsMatchIn(u)
    }

    private val MEDIA_REGEX = Regex(
        "\\.(mp4|m3u8|mpd|webm|m4s)([?&]|$)|videoplayback|googlevideo|fbcdn.*video|video.*\\.mp4"
    )

    /**
     * Runs the shared multi-strategy video detector inside [webView].
     * Falls back to the Kotlin-side mediaLog if page JS yields nothing.
     */
    fun detectInWebView(webView: WebView, onResult: (DetectedMedia?) -> Unit) {
        val fallback: () -> Unit = {
            val logged = synchronized(mediaLog) { mediaLog.firstOrNull() }
            onResult(logged?.let { DetectedMedia(it, "Media stream") })
        }
        runCatching {
            webView.evaluateJavascript(DETECT_JS) { value ->
                val parsed = parseDetection(value)
                if (parsed != null) onResult(parsed) else fallback()
            }
        }.onFailure { fallback() }
    }

    private fun parseDetection(value: String?): DetectedMedia? {
        // JS returns base64(JSON.stringify({kind,url})) to dodge quote escaping.
        val b64 = value?.trim()?.removeSurrounding("\"").orEmpty()
        if (b64.isEmpty()) return null
        return runCatching {
            val json = String(Base64.decode(b64, Base64.DEFAULT))
            val o = org.json.JSONObject(json)
            val u = o.optString("url")
            val k = o.optString("kind", "Video link")
            if (u.startsWith("http")) DetectedMedia(u, k) else null
        }.getOrNull()
    }

    /**
     * Same decoder as [detectInWebView]'s internal parsing, exposed so the
     * Gecko web apps can decode a base64 detect result returned over a
     * WebExtension port (where WebView JS evaluation is unavailable).
     */
    fun parseDetectedMedia(value: String?): DetectedMedia? = parseDetection(value)

    /**
     * @param initialUrl pre-filled URL (usually the current page).
     * @param onDetectVideo optional hook that sniffs the page for a video URL
     *   and invokes its callback with the result (null if none found).
     */
    fun show(
        activity: Activity,
        initialUrl: String,
        onDetectVideo: ((result: (DetectedMedia?) -> Unit) -> Unit)? = null
    ) {
        // Never stack a second dialog on top of a live one — replace it.
        dialogRef?.takeIf { it.isShowing }?.let { old ->
            runCatching { old.dismiss() }
        }
        // Opening this dialog is a strong signal a download is coming. Warm the
        // bundled Python/FFmpeg payload off the main thread while the user picks
        // a link, instead of doing it in every app process at startup.
        YtDlp.warmUp(activity.applicationContext)
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val halfPad = pad / 2

        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

        // ---------- Link entry (always visible) ----------
        val hintText = TextView(activity).apply {
            text = "Paste or detect a video link:"
            textSize = 12f
        }

        val urlField = EditText(activity).apply {
            setText(
                initialUrl.trim().ifBlank { lastDetected ?: lastSubmitted ?: "" }
            )
            this.hint = "Video or page link"
            setSingleLine(true)
        }

        var quality = "best"
        val qualityRow = RadioGroup(activity).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf(
                "best" to "Best", "1080" to "1080p", "720" to "720p",
                "480" to "480p", "audio" to "Audio"
            ).forEachIndexed { idx, (key, label) ->
                addView(
                    RadioButton(activity).apply {
                        text = label
                        textSize = 12f
                        setSingleLine(true)
                        includeFontPadding = false
                        setPadding(0, dp(2), dp(6), dp(2))
                        id = idx
                        isChecked = key == quality
                        setOnClickListener { quality = key }
                    },
                    RadioGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }
        // Scroll horizontally rather than letting labels wrap onto new lines.
        val qualityScroller = android.widget.HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(qualityRow)
        }

        var detectBtn: Button? = null
        if (onDetectVideo != null) {
            detectBtn = Button(activity).apply {
                text = "Detect video on this page"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = -dp(6) }
                setOnClickListener {
                    it.isEnabled = false
                    onDetectVideo { found ->
                        activity.runOnUiThread {
                            it.isEnabled = true
                            if (found != null) {
                                urlField.setText(found.url)
                                lastDetected = found.url
                                Toast.makeText(
                                    activity,
                                    "${found.kind} found — tap Download",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    activity,
                                    "No direct video found — page link will be used",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        }

        // ---------- Live queue section (always visible) ----------
        val listHolder = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroller = ScrollView(activity).apply {
            addView(listHolder)
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, halfPad, pad, 0)
            addView(hintText)
            addView(urlField)
            addView(qualityScroller)
            detectBtn?.let { addView(it) }
            addView(
                TextView(activity).apply {
                    text = "Downloads"
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(10), 0, 0)
                }
            )
            addView(scroller)
        }

        fun renderRows() {
            listHolder.removeAllViews()
            val jobs = DownloadManager.jobs.take(5)
            if (jobs.isEmpty()) {
                listHolder.addView(
                    TextView(activity).apply { text = "No downloads yet." }
                )
            }
            jobs.forEach { job -> listHolder.addView(rowFor(activity, job)) }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Quick Download")
            .setView(container)
            .setPositiveButton("Download", null)
            .setNegativeButton("Close", null)
            .create()

        dialogRef = dialog

        var dlBtn: android.widget.Button? = null
        fun submit() {
            // Fall back to the last detected/submitted link when empty.
            val target = urlField.text.toString().trim()
                .ifBlank { lastDetected ?: lastSubmitted ?: "" }
            if (target.startsWith("http")) {
                val before = DownloadManager.jobs.size
                val job = QuickDownloader.download(activity, target, quality)
                lastSubmitted = target
                urlField.setText(target)   // keep it visible/editable for edits
                val dup = DownloadManager.jobs.size == before
                Toast.makeText(
                    activity,
                    if (dup) "Already in the download queue"
                    else "Added to Downloads",
                    Toast.LENGTH_SHORT
                ).show()
                // Disarm the button so a double-tap can't fire a second submit;
                // editing the link re-arms it (see the watcher below).
                dlBtn?.isEnabled = false
                dlBtn?.text = if (dup) "Already queued ✓" else "Added ✓"
                renderRows()
            } else {
                Toast.makeText(activity, "Enter a valid link", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
        // Keep the dialog open after Download so live progress stays visible.
        dlBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setOnClickListener { submit() }
        }
        dialog.window?.setGravity(Gravity.BOTTOM)

        // Changing the link re-arms Download for the next item.
        urlField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                dlBtn?.isEnabled = true
                dlBtn?.text = "Download"
            }
        })

        renderRows()
        val poller = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                renderRows()
                handler.postDelayed(this, 500)
            }
        }
        handler.post(poller)
        dialog.setOnDismissListener {
            handler.removeCallbacks(poller)
            dialogRef = null
        }
    }

    /** One queue row inside the popup's Downloads section. */
    private fun rowFor(activity: Activity, job: DlJob): LinearLayout {
        val pad = (8 * activity.resources.displayMetrics.density).toInt()
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, pad)
        }
        val titleTv = TextView(activity).apply {
            text = job.title.take(40)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 13f
        }
        val statusTv = TextView(activity).apply {
            textSize = 12f
            text = statusText(job)
        }
        row.addView(titleTv)
        row.addView(statusTv)

        val btns = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun actBtn(label: String, action: () -> Unit): Button =
            Button(activity).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                minWidth = 0
                setPadding(pad, 0, pad, 0)
                setOnClickListener { action() }
            }
        when (job.state) {
            DlState.DOWNLOADING -> {
                btns.addView(actBtn("Pause") { DownloadManager.pause(job) })
                btns.addView(actBtn("Cancel") { DownloadManager.cancel(job) })
            }
            DlState.PAUSED -> {
                btns.addView(actBtn("Resume") { DownloadManager.resumeOrRetry(activity, job) })
                btns.addView(actBtn("Cancel") { DownloadManager.cancel(job) })
            }
            DlState.FAILED, DlState.CANCELED -> {
                btns.addView(actBtn("Retry") { DownloadManager.resumeOrRetry(activity, job) })
                btns.addView(actBtn("Dismiss") { DownloadManager.dismiss(job) })
            }
            DlState.DONE -> {
                btns.addView(actBtn("Done ✓ — Dismiss") { DownloadManager.dismiss(job) })
            }
            DlState.QUEUED -> {
                btns.addView(actBtn("Cancel") { DownloadManager.cancel(job) })
            }
        }
        row.addView(btns)
        return row
    }

    private fun statusText(job: DlJob): String {
        val total = if (job.totalBytes > 0) humanBytes(job.totalBytes) else ""
        val doneB = if (job.totalBytes > 0) humanBytes(job.doneBytes) else ""
        val sizeTxt =
            if (doneB.isNotBlank() && total.isNotBlank()) "$doneB of $total" else ""
        // Merged downloads run a second stream and an ffmpeg pass after the bar
        // is full — name that stage so it never reads as stalled.
        val phaseTxt = if (job.phase != null) " • ${job.phase}" else ""
        return when (job.state) {
            DlState.QUEUED -> "Queued…"
            DlState.DOWNLOADING ->
                "${job.progress.toInt()}%" +
                    (if (sizeTxt.isNotBlank()) " • $sizeTxt" else "") +
                    phaseTxt +
                    (if (job.etaSec > 0) " • ETA ${job.etaSec}s" else "")
            DlState.PAUSED -> "Paused at ${job.progress.toInt()}%" +
                (if (sizeTxt.isNotBlank()) " • $sizeTxt" else "")
            DlState.FAILED -> "Failed: ${(job.error ?: "").take(80)}"
            DlState.DONE ->
                "Done${if (sizeTxt.isNotBlank()) " • $sizeTxt" else ""}"
            DlState.CANCELED -> "Canceled"
        }
    }


    /** Convenience: open the dialog from any context with an activity. */
    fun showFrom(view: View, initialUrl: String) {
        val act = view.context as? Activity ?: return
        show(act, initialUrl)
    }

    /**
     * Multi-strategy page-side detector. Returns "" when nothing was found.
     * Result is base64-encoded JSON so Kotlin can parse it without escaping pain.
     */
    private val DETECT_JS = """
        (function(){
          function ok(u){return !!u&&(typeof u==='string')&&u.indexOf('http')===0;}
          function out(kind,url){return btoa(unescape(encodeURIComponent(JSON.stringify({kind:kind,url:url}))));}
          function plink(u){
            if(!ok(u))return null;
            if(/(watch\?v=|\/shorts\/|\/reel\/|\/videos\/|video\.php|\/watch\/|\/p\/|\/tv\/)/.test(u))return u.split('?__')[0];
            return null;
          }
          var v=document.querySelector('video');
          var s=v&&(v.currentSrc||v.src);
          if(ok(s)&&s.indexOf('blob:')!==0&&s.indexOf('data:')!==0)return out('direct video',s);
          var c=document.querySelector('link[rel="canonical"]');
          var p=plink(c&&c.href);if(p)return out('video page link',p);
          p=plink(location.href);if(p)return out('video page link',p);
          if(v){
            var a=null,n=v;
            for(var i=0;i<6&&!a&&n;i++){
              a=n.closest?n.closest('a[href]'):null;
              if(!a&&n.querySelectorAll){
                var list=n.querySelectorAll('a[href]');
                for(var j=0;j<list.length;j++){
                  if(plink(list[j].href)){a=list[j];break;}
                }
              }
              n=n.parentElement;
            }
            p=a&&plink(a.href);if(p)return out('video page link',p);
          }
          try{
            var y=(window.ytInitialPlayerResponse&&window.ytInitialPlayerResponse.videoDetails)||null;
            if(y&&y.videoId)return out('video page link','https://www.youtube.com/watch?v='+y.videoId);
          }catch(e){}
          try{
            var m=document.querySelector('meta[property="og:video"]');
            if(m&&ok(m.content)&&m.content.indexOf('blob:')!==0)return out('direct video',m.content);
          }catch(e){}
          try{
            var ents=performance.getEntries()||[];
            for(var k2=ents.length-1;k2>=0;k2--){
              var u2=ents[k2].name||'';
              if(/(\.mp4([?#]|$)|\.m3u8([?#]|$)|\.mpd([?#]|$)|videoplayback|googlevideo)/.test(u2)){
                return out('media stream',u2);
              }
            }
          }catch(e){}
          return '';
        })()
    """.trimIndent()
}
