package com.example.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

enum class DlState { QUEUED, DOWNLOADING, PAUSED, FAILED, DONE, CANCELED }

data class DlJob(
    val id: Long,
    val url: String,
    val title: String,
    val quality: String,

    val outFile: File,
    var state: DlState = DlState.QUEUED,
    var progress: Float = 0f,
    var etaSec: Long = -1,
    var error: String? = null,
    var file: String? = null,
    var totalBytes: Long = -1,
    /**
     * Bytes actually fetched, summed over every stream yt-dlp downloads for
     * this job. [totalBytes] is the matching grand total, so the two are only
     * meaningful together.
     */
    var doneBytes: Long = 0L,
    /**
     * Sub-stage of a multi-stream (video+audio) download, e.g.
     * "Downloading audio track…" / "Merging formats…". Null while a plain
     * single-stream download is running. Shown next to the percentage so a
     * merged download never reads as stalled.
     */
    var phase: String? = null
)

/** Human-readable byte size, e.g. "35.7 MB". */
fun humanBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1073741824f)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576f)
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024f)
    bytes > 0 -> "$bytes B"
    else -> ""
}

/**
 * App-process-wide download queue. Jobs run in a singleton coroutine scope so
 * they keep running when the user navigates away from the downloader screen;
 * re-entering shows live progress.
 */
object DownloadManager {

    const val MIME_VIDEO = "video/mp4"
    const val MIME_AUDIO = "audio/mp4"

    val jobs = androidx.compose.runtime.mutableStateListOf<DlJob>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var appCtx: Context? = null

    private fun updateJob(jobId: Long, transform: (DlJob) -> DlJob) {
        val i = jobs.indexOfFirst { it.id == jobId }
        if (i >= 0) jobs[i] = transform(jobs[i])
        persistJobs()
    }

    // ---------------- cross-process job visibility ----------------
    //
    // DownloadManager is a Kotlin `object`, so `jobs` is per-process. A download
    // started from a Gecko web app runs in the :webapps process and was therefore
    // invisible to the main process's VideoDownloaderScreen — the job appeared to
    // start and then vanished from the only screen that lists jobs. A small JSON
    // snapshot in filesDir (shared by every process, unlike the per-process
    // singletons and unlike SharedPreferences, which is not multi-process safe)
    // lets each process publish and pick up the others' jobs.
    //
    // Within a process `jobs` stays the source of truth: the snapshot is written
    // on change and read once at attach time. A worker that died with its process
    // leaves its job in the last recorded state — the progress and error fields
    // still explain what happened, which is the point of the snapshot.

    private fun snapshotFile(context: Context): File =
        File(context.applicationContext.filesDir, "nj_dl_jobs.json")

    private val persistLock = Any()
    private var lastPersistAt = 0L

    /**
     * Jobs the user explicitly dismissed in this process. The snapshot refresh
     * below re-adds any job present in nj_dl_jobs.json that is missing from the
     * in-memory list, so without this a dismissed job vanished on dismiss then
     * reappeared on the next 2 s poll — and, because dismiss() used to skip
     * persistJobs(), it never left the snapshot at all. Browser direct downloads
     * (createDirectDownloadJob) were the ones users could not get rid of.
     */
    private val dismissedIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    /**
     * Job id -> system DownloadManager id, for `quality == "direct"` jobs.
     *
     * Direct downloads are handed to android.app.DownloadManager, so pausing one
     * means calling DownloadManager.pause(id) on the system download — a plain
     * `destroyProcessById` (the yt-dlp path) is a no-op here. This map remembers
     * the id so pause()/resumeOrRetry()/cancel() can reach the right download.
     *
     * Deliberately in-memory: if the process dies the system notification owns the
     * download and this app can no longer pause/resume it, so there is nothing
     * meaningful to persist.
     */
    private val directIds = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** Called by any UI that lists jobs, so it picks up other processes' work. */
    fun attach(context: Context) {
        appCtx = context.applicationContext
        synchronized(persistLock) {
            if (jobs.isNotEmpty()) return
            runCatching { restoreJobs(snapshotFile(context)) }
        }
    }

    /**
     * Re-read the snapshot while a job list is on screen so jobs owned by another
     * process show live progress. Jobs this process owns are left alone — it is
     * already updating them in memory.
     */
    fun refreshFromSnapshot(context: Context) {
        appCtx = context.applicationContext
        runCatching {
            val arr = org.json.JSONArray(snapshotFile(context).readText())
            val foreign = mutableMapOf<Long, DlJob>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                foreign[o.getLong("id")] = DlJob(
                    id = o.getLong("id"),
                    url = o.optString("url"),
                    title = o.optString("title"),
                    quality = o.optString("quality"),
                    outFile = File(o.optString("outFile")),
                    state = runCatching { DlState.valueOf(o.optString("state")) }
                        .getOrDefault(DlState.PAUSED),
                    progress = o.optDouble("progress", 0.0).toFloat(),
                    etaSec = o.optLong("etaSec", -1L),
                    error = if (o.isNull("error")) null else o.optString("error"),
                    file = if (o.isNull("file")) null else o.optString("file"),
                    totalBytes = o.optLong("totalBytes", -1L),
                    doneBytes = o.optLong("doneBytes", 0L),
                    phase = if (o.isNull("phase")) null else o.optString("phase")
                )
            }
            // Drop finished jobs this process still lists from the snapshot's view
            // only when the snapshot agrees they are done; never resurrect a job
            // that has been removed here.
            foreign.forEach { (id, incoming) ->
                if (dismissedIds.contains(id)) return@forEach
                val i = jobs.indexOfFirst { it.id == id }
                if (i >= 0) {
                    jobs[i] = incoming
                } else {
                    jobs.add(incoming)
                }
            }
        }
    }

    private fun persistJobs(force: Boolean = false) {
        val ctx = appCtx ?: return
        val now = System.currentTimeMillis()
        synchronized(persistLock) {
            // Progress ticks are frequent; throttling keeps this off the hot path.
            if (!force && now - lastPersistAt < 1500L) return
            lastPersistAt = now
        }
        scope.launch {
            runCatching {
                val arr = org.json.JSONArray()
                jobs.forEach { j ->
                    arr.put(
                        org.json.JSONObject().apply {
                            put("id", j.id)
                            put("url", j.url)
                            put("title", j.title)
                            put("quality", j.quality)
                            put("outFile", j.outFile.absolutePath)
                            put("state", j.state.name)
                            put("progress", j.progress.toDouble())
                            put("etaSec", j.etaSec)
                            put("error", j.error ?: org.json.JSONObject.NULL)
                            put("file", j.file ?: org.json.JSONObject.NULL)
                            put("totalBytes", j.totalBytes)
                            put("doneBytes", j.doneBytes)
                            put("phase", j.phase ?: org.json.JSONObject.NULL)
                        }
                    )
                }
                snapshotFile(ctx).writeText(arr.toString())
            }
        }
    }

    private fun restoreJobs(file: File) {
        if (!file.exists()) return
        val arr = org.json.JSONArray(file.readText())
        val restored = mutableListOf<DlJob>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            restored += DlJob(
                id = o.getLong("id"),
                url = o.optString("url"),
                title = o.optString("title"),
                quality = o.optString("quality"),
                outFile = File(o.optString("outFile")),
                state = runCatching { DlState.valueOf(o.optString("state")) }
                    .getOrDefault(DlState.PAUSED),
                progress = o.optDouble("progress", 0.0).toFloat(),
                etaSec = o.optLong("etaSec", -1L),
                error = if (o.isNull("error")) null else o.optString("error"),
                file = if (o.isNull("file")) null else o.optString("file"),
                totalBytes = o.optLong("totalBytes", -1L),
                doneBytes = o.optLong("doneBytes", 0L),
                phase = if (o.isNull("phase")) null else o.optString("phase")
            )
        }
        restored.sortedByDescending { it.id }.forEach { jobs.add(it) }
    }

    fun isBusy(): Boolean =
        jobs.any { it.state == DlState.DOWNLOADING || it.state == DlState.QUEUED }

    /**
     * Start a job with the given quality preset ("best"/"1080"/"720"/"480"/"audio").
     *
     * Returns the job that is now running. If the same URL + quality is already
     * queued or downloading, that existing job is returned and **no** second job
     * is created — this is what makes a double-tap on "Download" a no-op instead
     * of a duplicate download.
     */
    fun startDownload(
        context: Context,
        targetUrl: String,
        quality: String,
        resuming: DlJob? = null
    ): DlJob {
        val key = normalizeUrl(targetUrl)
        if (resuming == null) {
            jobs.firstOrNull {
                (it.state == DlState.QUEUED || it.state == DlState.DOWNLOADING) &&
                    it.quality == quality && normalizeUrl(it.url) == key
            }?.let { return it }
        }
        val job = resuming ?: DlJob(
            id = System.currentTimeMillis(),
            url = targetUrl,
            title = targetUrl.take(48),
            quality = quality,
            outFile = File(
                File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        if (quality == "audio")
                            android.os.Environment.DIRECTORY_MUSIC
                        else android.os.Environment.DIRECTORY_MOVIES
                    ),
                    if (quality == "audio") "NJ-Dialer Audio" else "NJ-Dialer"
                ),
                "${if (quality == "audio") "audio" else "video"}_${System.currentTimeMillis()}." +
                    if (quality == "audio") "m4a" else "mp4"
            )
        )
        if (resuming == null) jobs.add(0, job)
        appCtx = context.applicationContext
        updateJob(job.id) { j ->
            j.copy(state = DlState.DOWNLOADING, error = null, phase = null, doneBytes = 0L)
        }
        persistJobs(force = true)
        notifyProgress(context.applicationContext, job, 0f, -1L)

        scope.launch {
            val outcome = runDownload(context.applicationContext, job)
            when (outcome) {
                RunOutcome.DONE -> Unit // UI updates already posted on main
                RunOutcome.CANCELED ->
                    updateJob(job.id) { j ->
                        if (j.state == DlState.DOWNLOADING) j.copy(state = DlState.PAUSED) else j
                    }
                RunOutcome.FAILED ->
                    updateJob(job.id) { j ->
                        if (j.state == DlState.DOWNLOADING)
                            j.copy(state = DlState.FAILED, error = j.error ?: "download failed", phase = null)
                        else j
                    }
            }
        }
        return job
    }

    /**
     * Canonical form of a download URL for duplicate detection: lowercased host
     * + path + query, no scheme, no fragment, no trailing slash.
     */
    private fun normalizeUrl(url: String): String = runCatching {
        val u = java.net.URI(url.trim())
        val host = (u.host ?: "").lowercase()
        if (host.isBlank()) {
            url.trim()
        } else {
            host + (u.rawPath ?: "").trimEnd('/') + (u.rawQuery?.let { "?$it" } ?: "")
        }
    }.getOrDefault(url.trim())

    private enum class RunOutcome { DONE, CANCELED, FAILED }

    private suspend fun runDownload(context: Context, job: DlJob): RunOutcome =
        withContext(Dispatchers.IO) {
            try {
                YtDlp.ensureInit(context)?.let {
                    postError(job.id, it)
                    return@withContext RunOutcome.FAILED
                }
                job.outFile.parentFile?.mkdirs()

                // Facebook/Instagram/TikTok often require the logged-in
                // session; hand yt-dlp the WebView cookies for this host.
                val cookieTmp = cookieFileFor(context, job.url)
                cookieTmp?.let { f ->
                    // The dump holds live session cookies for the logged-in account,
                    // so it must not outlive the job. Registering on the coroutine
                    // covers every exit — including an exception or a cancel that
                    // happens before the executeWithRetries finally below is reached.
                    // `deleteOnExit()` was doing nothing on Android, which left one
                    // stale credential file per FB/IG/TikTok download in cacheDir.
                    coroutineContext[Job]?.invokeOnCompletion { runCatching { f.delete() } }
                }

                // Resolve the original video name up front so the saved file
                // is "Song Title.mp4" instead of "video_1737...mp4" in the
                // File Manager. Falls back silently to the timestamp name.
                runCatching {
                    val infoReq = YoutubeDLRequest(job.url)
                    if (cookieTmp != null) {
                        infoReq.addOption("--cookies", cookieTmp.absolutePath)
                    }
                    YoutubeDL.getInstance().getInfo(infoReq).title
                }.getOrNull()?.let { raw ->
                    val t = sanitizeFileName(raw)
                    if (t.isNotBlank()) {
                        // Direct snapshot-list write (thread-safe) so the
                        // output path below sees the renamed file.
                        updateJob(job.id) { j ->
                            val ext = if (j.quality == "audio") "m4a" else "mp4"
                            var target = File(j.outFile.parentFile, "$t.$ext")
                            if (target.exists()) {
                                target = File(
                                    j.outFile.parentFile,
                                    "${t}_${j.id % 100000L}.$ext"
                                )
                            }
                            j.copy(title = t.take(48), outFile = target)
                        }
                    }
                }

                // Use the possibly-renamed job for the output path.
                val live = jobs.firstOrNull { it.id == job.id } ?: job
                live.outFile.parentFile?.mkdirs()

                val req = YoutubeDLRequest(live.url)
                req.addOption("--continue")                       // resume .part files
                req.addOption("--no-playlist")
                req.addOption("--no-mtime")
                req.addOption("-o", live.outFile.absolutePath)
                // Bundled FFmpeg: required to merge YouTube's separate
                // video+audio streams and for -x audio extraction.
                YtDlp.ffmpegLocation(context)?.let {
                    req.addOption("--ffmpeg-location", it)
                }
                if (live.quality == "audio") {
                    req.addOption("-f", "ba/b")
                    req.addOption("-x")
                    req.addOption("--audio-format", "m4a")
                } else {
                    when (live.quality) {
                        "1080" -> req.addOption("-f", "bv*[height<=1080]+ba/b[height<=1080]")
                        "720" -> req.addOption("-f", "bv*[height<=720]+ba/b[height<=720]")
                        "480" -> req.addOption("-f", "bv*[height<=480]+ba/b[height<=480]")
                        else -> req.addOption("-f", "bv*+ba/b")
                    }
                    req.addOption("--merge-output-format", "mp4")
                }

                try {
                    executeWithRetries(req, live.url, live.id.toString())
                } finally {
                    runCatching { cookieTmp?.delete() }
                }

                val finalFile = live.outFile
                if (!finalFile.exists() || finalFile.length() == 0L) {
                    postError(job.id, "no file produced — try another link or quality")
                    return@withContext RunOutcome.FAILED
                }
                mainHandler.post {
                    updateJob(job.id) { j ->
                        j.copy(
                            state = DlState.DONE, progress = 100f, phase = null,
                            file = finalFile.absolutePath,
                            // Merged output is the real artifact — trust its size
                            // over the sum of the source streams.
                            totalBytes = finalFile.length(),
                            doneBytes = finalFile.length()
                        )
                    }
                    HistoryStore.add(context, finalFile.absolutePath, job.url)
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        arrayOf(finalFile.absolutePath),
                        arrayOf(if (job.quality == "audio") MIME_AUDIO else MIME_VIDEO),
                        null
                    )
                    notifyFinished(
                        context, job, success = true,
                        text = "Saved to ${finalFile.parentFile?.name}/${finalFile.name}"
                    )
                }
                RunOutcome.DONE
            } catch (canceled: YoutubeDL.CanceledException) {
                RunOutcome.CANCELED
            } catch (e: Exception) {
                val tail = (e.message ?: "download failed").lines()
                    .filter { it.isNotBlank() }.lastOrNull()?.take(160) ?: "download failed"
                mainHandler.post {
                    updateJob(job.id) { j ->
                        j.copy(state = DlState.FAILED, error = tail, phase = null)
                    }
                }
                notifyFinished(context, job, success = false, text = tail)
                RunOutcome.FAILED
            }
        }

    /**
     * Execute a download; retries YouTube extraction with alternate player
     * clients when the site's anti-bot changes break the default one.
     *
     * A merged download (video-only + audio-only) makes yt-dlp report a full
     * 0→100% cycle **per stream**, which the raw percentage made the UI render
     * as "downloaded, then restarted". Progress is therefore re-weighted by
     * bytes here: finished streams are accumulated into [accounted] and the
     * displayed value is clamped to be monotonic and to stay below 100% until
     * the merged file actually exists.
     */
    private fun executeWithRetries(
        req: YoutubeDLRequest,
        targetUrl: String,
        procId: String
    ): YoutubeDLResponse {
        val host = runCatching { java.net.URI(targetUrl).host ?: "" }.getOrDefault("").lowercase()
        val isYouTube = host.contains("youtube.com") || host.contains("youtu.be")
        val clientChain: List<String?> =
            if (isYouTube) listOf(null, "youtube:player_client=android_vr", "youtube:player_client=tv_embedded")
            else listOf(null)
        val jobId = procId.toLongOrNull() ?: 0L
        var lastError: Exception? = null
        // Per-attempt byte accounting, reset for every retry below.
        var accounted = 0L
        var streamTotal = -1L
        var streamDest = ""
        for ((attempt, args) in clientChain.withIndex()) {
            accounted = 0L
            streamTotal = -1L
            streamDest = ""
            try {
                if (attempt > 0) {
                    mainHandler.post {
                        updateJob(jobId) { j ->
                            // progress is deliberately NOT reset: --continue
                            // resumes the .part files, so the bar is still honest.
                            j.copy(
                                state = DlState.DOWNLOADING, error = null, phase = "Retrying…",
                                title = "${j.title} (retry $attempt)"
                            )
                        }
                    }
                }
                return YoutubeDL.getInstance().execute(req, procId) { progress, eta, line ->
                    // A new "[download] Destination: <file>" means yt-dlp moved on
                    // to the next stream of the merged output (video → audio).
                    DEST_RE.find(line)?.groupValues?.getOrNull(1)?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { dest ->
                            if (dest != streamDest) {
                                if (streamTotal > 0) accounted += streamTotal
                                streamDest = dest
                                streamTotal = -1L
                            }
                        }
                    val lineTotal = parseTotalBytes(line)
                    if (lineTotal > 0 && streamTotal != lineTotal &&
                        (streamTotal <= 0 || lineTotal > streamTotal * 1.1f || lineTotal < streamTotal * 0.9f)
                    ) {
                        if (streamTotal > 0) accounted += streamTotal
                        streamTotal = lineTotal
                    }
                    val done = accounted +
                        (if (streamTotal > 0) (streamTotal * progress / 100f).toLong() else 0L)
                    val grand = accounted + maxOf(streamTotal, 0L)
                    val overall = if (grand > 0) done * 100f / grand else progress
                    val ph = phaseFor(line, accounted > 0)
                    mainHandler.post {
                        updateJob(jobId) { j ->
                            j.copy(
                                progress = maxOf(j.progress, minOf(99f, overall)),
                                doneBytes = done,
                                totalBytes = if (grand > 0) grand else j.totalBytes,
                                phase = ph,
                                etaSec = eta,
                                state = DlState.DOWNLOADING
                            )
                        }
                        appCtx?.let { ctx ->
                            jobs.firstOrNull { it.id.toString() == procId }?.let { j ->
                                notifyProgress(ctx, j, j.progress, eta)
                            }
                        }
                    }
                }
            } catch (canceled: YoutubeDL.CanceledException) {
                throw canceled
            } catch (e: Exception) {
                lastError = e
                val msg = e.message ?: ""
                if (!msg.contains("format", true) && !msg.contains("extract", true)) break
            }
        }
        throw lastError ?: IllegalStateException("download failed")
    }

    /** Sub-stage label from a yt-dlp output line, so a merged download never looks stalled. */
    private fun phaseFor(line: String, hasFinishedStream: Boolean): String? = when {
        MERGE_RE.containsMatchIn(line) -> "Merging formats…"
        EXTRACT_RE.containsMatchIn(line) -> "Converting audio…"
        hasFinishedStream -> "Downloading audio track…"
        else -> null
    }

    private val DEST_RE = Regex("\\[download]\\s+Destination:\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val MERGE_RE = Regex("\\[Merger]|Merging formats", RegexOption.IGNORE_CASE)
    private val EXTRACT_RE = Regex("\\[ExtractAudio]|Destination:.*\\.(m4a|mp3|opus)$", RegexOption.IGNORE_CASE)

    /**
     * Write cookies for [url]'s host to a Netscape-format file that yt-dlp
     * consumes via --cookies. Local-only; caller deletes it.
     *
     * The web apps now run on GeckoView, so the authentic source is the
     * shared Gecko session (exported through the cookiejar extension); the
     * WebView cookie manager is only a fallback for any remaining WebView
     * web apps.
     */
    private fun cookieFileFor(context: Context, url: String): File? {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
            ?: return null
        if (!listOf("facebook", "instagram", "tiktok", "youtu", "ytimg")
                .any { host.contains(it) }
        ) return null // YouTube etc. work anonymously; skip cookie plumbing
        purgeStaleCookieFiles(context)
        return runCatching {
            val gecko = com.example.util.CookieExport.export(host)
            val raw = if (!gecko.isNullOrBlank()) {
                gecko
            } else {
                android.webkit.CookieManager.getInstance()
                    .getCookie("https://$host") ?: return null
            }
            if (raw.isBlank()) return null
            val f = File(context.cacheDir, "ytdlp_cookies_${System.nanoTime()}.txt")
            f.bufferedWriter().use { w ->
                w.appendLine("# Netscape HTTP Cookie File")
                raw.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { c ->
                    val name = c.substringBefore('=').trim()
                    val value = c.substringAfter('=', "")
                    w.appendLine(".$host\tTRUE\t/\tTRUE\t2147483647\t$name\t$value")
                }
            }
            f
        }.getOrNull()
    }

    /** Cookie dumps are credentials; never let them pile up in cacheDir. */
    private fun purgeStaleCookieFiles(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
            context.cacheDir.listFiles { f ->
                f.name.startsWith("ytdlp_cookies_") && f.lastModified() < cutoff
            }?.forEach { runCatching { it.delete() } }
        }
    }

    /**
     * Extract total size from yt-dlp's "[download]  45.3% of ~35.67MiB..." lines.
     * The optional "~" matters: yt-dlp marks estimated sizes with it and uses
     * that form for the audio stream, so a regex without it silently loses
     * every size and the progress aggregator falls back to the per-stream
     * percentage.
     */
    private val SIZE_RE =
        Regex("of\\s*~?\\s*([0-9.]+)\\s*(B|KiB|MiB|GiB)", RegexOption.IGNORE_CASE)

    /** Filesystem-safe version of a video title. */
    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .take(60)

    private fun parseTotalBytes(line: String): Long {
        val m = SIZE_RE.find(line) ?: return -1L
        val v = m.groupValues[1].toFloatOrNull() ?: return -1L
        return when (m.groupValues[2].lowercase()) {
            "kib" -> (v * 1024L).toLong()
            "mib" -> (v * 1048576L).toLong()
            "gib" -> (v * 1073741824L).toLong()
            else -> v.toLong()   // plain bytes
        }
    }

    private fun postError(id: Long, msg: String) {
        mainHandler.post { updateJob(id) { j -> j.copy(state = DlState.FAILED, error = msg) } }
        appCtx?.let { ctx ->
            jobs.firstOrNull { it.id == id }?.let {
                notifyFinished(ctx, it, success = false, text = msg)
            }
        }
    }

    // ---- System notifications (shared by every download entry point) ----

    private const val NOTIF_CHANNEL = "quick_downloads"

    /** Stable int id derived from the Long job id; avoids audio-service ids. */
    private fun notifId(jobId: Long): Int =
        ((jobId % 100000L) + 20000L).toInt()

    private fun nm(context: Context): android.app.NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

    private fun ensureChannel(context: Context) {
        nm(context).createNotificationChannel(
            android.app.NotificationChannel(
                NOTIF_CHANNEL, "Downloads",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }

    private fun notifyProgress(context: Context, job: DlJob, pct: Float, etaSec: Long) {
        ensureChannel(context)
        val cancelPi = android.app.PendingIntent.getBroadcast(
            context, notifId(job.id),
            Intent(context, QuickDownloader.CancelReceiver::class.java)
                .putExtra("jobId", job.id.toString()),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val etaTxt = if (etaSec > 0) " • ETA ${etaSec}s" else ""
        val phaseTxt = if (job.phase != null) " • ${job.phase}" else ""
        val n: android.app.Notification =
            androidx.core.app.NotificationCompat.Builder(context, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(
                    if (job.quality == "audio") "Downloading audio"
                    else "Downloading ${job.quality.uppercase()} video"
                )
                .setContentText("${job.title} • ${pct.toInt()}%$phaseTxt$etaTxt")
                .setProgress(100, pct.toInt().coerceIn(0, 100), pct <= 0f)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Cancel", cancelPi
                )
                .build()
        runCatching { nm(context).notify(notifId(job.id), n) }
    }

    private fun notifyFinished(
        context: Context,
        job: DlJob,
        success: Boolean,
        text: String
    ) {
        ensureChannel(context)
        runCatching { nm(context).cancel(notifId(job.id)) } // clear progress bar
        val n: android.app.Notification =
            androidx.core.app.NotificationCompat.Builder(context, NOTIF_CHANNEL)
                .setSmallIcon(
                    if (success) android.R.drawable.stat_sys_download_done
                    else android.R.drawable.stat_notify_error
                )
                .setContentTitle(if (success) "Download complete" else "Download failed")
                .setContentText(text.take(120))
                .setAutoCancel(true)
                .build()
        runCatching { nm(context).notify(notifId(job.id) + 100000, n) }
    }

    fun cancelProgressNotif(job: DlJob) {
        appCtx?.let { runCatching { nm(it).cancel(notifId(job.id)) } }
    }

    fun pause(j: DlJob) {
        updateJob(j.id) { it.copy(state = DlState.PAUSED) }
        if (j.quality == "direct") {
            // Direct jobs live in the system DownloadManager; the yt-dlp process
            // destroy below is a no-op for them and would leave the transfer
            // running. Pause the real system download instead.
            directIds[j.id]?.let { dmId -> appCtx?.let { pauseSystemDownload(it, dmId) } }
        } else {
            scope.launch { runCatching { YoutubeDL.getInstance().destroyProcessById(j.id.toString()) } }
        }
        cancelProgressNotif(j)
    }

    fun resumeOrRetry(context: Context, j: DlJob) {
        if (j.quality == "direct") {
            val dmId = directIds[j.id]
            if (j.state == DlState.PAUSED && dmId != null) {
                // Resume the same system download instead of re-enqueueing.
                updateJob(j.id) { it.copy(state = DlState.DOWNLOADING, error = null, phase = null) }
                notifyProgress(context.applicationContext, j, j.progress, j.etaSec)
                resumeSystemDownload(context.applicationContext, dmId)
                return
            }
            // A failed direct job cannot be resumed; retry by starting a fresh one.
            startDirectDownload(context, j)
            return
        }
        startDownload(context, j.url, j.quality, resuming = j)
    }

    /**
     * Pause/resume one system DownloadManager download.
     *
     * `DownloadManager.pause(long…)` / `resume(long…)` exist in the framework but
     * are hidden from the public SDK (they are absent from android.jar), which is
     * why direct downloads never actually paused. Reflection is the only way to
     * reach them; it is best-effort and silent on failure, so a device that blocks
     * the hidden call simply leaves the download running.
     */
    private fun systemDownloadMethod(name: String): java.lang.reflect.Method? =
        runCatching {
            android.app.DownloadManager::class.java.getMethod(name, LongArray::class.java)
        }.getOrNull()

    private fun pauseSystemDownload(context: Context, id: Long) {
        runCatching {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            systemDownloadMethod("pause")?.invoke(dm, longArrayOf(id))
        }
    }

    private fun resumeSystemDownload(context: Context, id: Long) {
        runCatching {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            systemDownloadMethod("resume")?.invoke(dm, longArrayOf(id))
        }
    }

    fun cancel(j: DlJob) {
        if (j.quality == "direct") {
            directIds.remove(j.id)?.let { dmId ->
                val ctx = appCtx
                if (ctx != null) {
                    scope.launch {
                        runCatching {
                            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager)
                                .remove(dmId)
                        }
                    }
                }
            }
        } else if (j.state == DlState.DOWNLOADING) {
            scope.launch {
                runCatching { YoutubeDL.getInstance().destroyProcessById(j.id.toString()) }
            }
        }
        scope.launch {
            val dir = j.outFile.parentFile ?: return@launch
            dir.listFiles { f -> f.name.startsWith(j.outFile.nameWithoutExtension) }
                ?.forEach { runCatching { it.delete() } }
        }
        updateJob(j.id) { it.copy(state = DlState.CANCELED, progress = 0f) }
    }

    fun dismiss(j: DlJob) {
        cancelProgressNotif(j)
        dismissedIds.add(j.id)
        scope.launch {
            // Sweep leftover .part files / unmerged stream fragments so the
            // File Manager doesn't accumulate junk from failed attempts.
            val dir = j.outFile.parentFile ?: return@launch
            val base = j.outFile.nameWithoutExtension
            dir.listFiles { f -> f.name.startsWith(base) }?.forEach {
                if (it.absolutePath != j.file) runCatching { it.delete() }
            }
        }
        jobs.removeAll { it.id == j.id }
        // Must persist, otherwise the snapshot still lists this job and the
        // downloader's 2 s refreshFromSnapshot() poll resurrects it.
        persistJobs(force = true)
    }

    /**
     * Create a direct download job (for browser downloads, not yt-dlp).
     * Uses system DownloadManager for simple HTTP downloads.
     */
    fun createDirectDownloadJob(
        url: String,
        fileName: String,
        mimeType: String,
        contentLength: Long?
    ): DlJob {
        // Determine destination directory based on mime type
        val (destDir, subDir) = when {
            mimeType.startsWith("video/") -> {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES) to "NJ-Dialer"
            }
            mimeType.startsWith("audio/") -> {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC) to "NJ-Dialer Audio"
            }
            mimeType.startsWith("image/") -> {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) to "NJ-Dialer"
            }
            else -> {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) to ""
            }
        }
        
        val finalDir = if (subDir.isNotBlank()) File(destDir, subDir) else destDir
        finalDir.mkdirs()
        
        // Generate unique filename
        val target = uniqueDownloadTarget(finalDir, fileName)
        
        return DlJob(
            id = System.currentTimeMillis(),
            url = url,
            title = fileName,
            quality = "direct",
            outFile = target,
            totalBytes = contentLength ?: -1L,
            doneBytes = 0L,
            state = DlState.QUEUED
        )
    }

    /**
     * Start a direct HTTP download using system DownloadManager.
     * This runs independently of yt-dlp and shows real-time progress.
     */
    fun startDirectDownload(context: Context, job: DlJob) {
        // Check for duplicate (ignore this job itself, so a retry of a FAILED
        // direct job is not rejected and cannot be added to the list twice).
        val key = normalizeUrl(job.url)
        jobs.firstOrNull {
            it.id != job.id &&
                (it.state == DlState.QUEUED || it.state == DlState.DOWNLOADING) &&
                it.quality == "direct" && normalizeUrl(it.url) == key
        }?.let { return }
        
        if (jobs.none { it.id == job.id }) jobs.add(0, job)
        appCtx = context.applicationContext
        updateJob(job.id) { j ->
            j.copy(state = DlState.DOWNLOADING, error = null, phase = null, doneBytes = 0L)
        }
        persistJobs(force = true)
        notifyProgress(context.applicationContext, job, 0f, -1L)

        scope.launch {
            downloadDirect(context, job)
        }
    }

    private suspend fun downloadDirect(context: Context, job: DlJob) {
        val request = android.app.DownloadManager.Request(android.net.Uri.parse(job.url))
            .setDestinationUri(android.net.Uri.fromFile(job.outFile))
            .setTitle(job.title)
            .setDescription("Downloading...")
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedNetworkTypes(
                android.app.DownloadManager.Request.NETWORK_WIFI or 
                android.app.DownloadManager.Request.NETWORK_MOBILE
            )
            .setAllowedOverRoaming(false)
            .setRequiresCharging(false)
        
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val downloadId = downloadManager.enqueue(request)
        // Remember the system id so pause()/resumeOrRetry()/cancel() can reach it.
        directIds[job.id] = downloadId

        // Monitor progress
        val query = android.app.DownloadManager.Query().setFilterById(downloadId)
        var lastProgress = 0f

        try {
        while (true) {
            val cursor = downloadManager.query(query)
            try {
                if (!cursor.moveToFirst()) {
                    updateJob(job.id) { it.copy(state = DlState.FAILED, error = "Download query failed", phase = null) }
                    break
                }
                
                val status = cursor.getInt(cursor.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS))
                val bytesDownloaded = cursor.getLong(cursor.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val totalSize = cursor.getLong(cursor.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                
                when (status) {
                    android.app.DownloadManager.STATUS_SUCCESSFUL -> {
                        updateJob(job.id) { j ->
                            j.copy(state = DlState.DONE, progress = 100f, doneBytes = bytesDownloaded, file = job.outFile.absolutePath, phase = null)
                        }
                        // Scan file for media gallery
                        android.media.MediaScannerConnection.scanFile(
                            context,
                            arrayOf(job.outFile.absolutePath),
                            arrayOf(job.outFile.extension),
                            null
                        )
                        mainHandler.post {
                            runCatching { android.widget.Toast.makeText(context, "Saved ${job.outFile.name} to ${job.outFile.parentFile.name}/", android.widget.Toast.LENGTH_SHORT).show() }
                        }
                        break
                    }
                    android.app.DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(cursor.getColumnIndex(android.app.DownloadManager.COLUMN_REASON))
                        updateJob(job.id) { it.copy(state = DlState.FAILED, error = "Download failed (code $reason)", phase = null) }
                        break
                    }
                    android.app.DownloadManager.STATUS_RUNNING,
                    android.app.DownloadManager.STATUS_PAUSED -> {
                        // A user-paused job must not have its state or progress
                        // rewritten by the poll: it stays PAUSED until Resume.
                        val userPaused = jobs.firstOrNull { it.id == job.id }?.state == DlState.PAUSED
                        val progress = if (totalSize > 0) (bytesDownloaded.toFloat() / totalSize * 100f) else 0f
                        if (progress != lastProgress && !userPaused) {
                            lastProgress = progress
                            val eta = if (bytesDownloaded > 0 && totalSize > bytesDownloaded) {
                                // Estimate ETA based on current speed
                                val elapsed = System.currentTimeMillis() - job.id // approximate
                                if (elapsed > 0) ((totalSize - bytesDownloaded) * elapsed / bytesDownloaded / 1000).toLong() else -1L
                            } else -1L
                            updateJob(job.id) { j ->
                                j.copy(
                                    progress = progress,
                                    doneBytes = bytesDownloaded,
                                    totalBytes = if (totalSize > 0) totalSize else j.totalBytes,
                                    etaSec = eta,
                                    phase = if (totalSize > 0) "Downloading..." else null
                                )
                            }
                            notifyProgress(context, job, progress, eta)
                        }
                    }
                }
            } finally {
                cursor.close()
            }
            
            // Wait before polling again
            delay(500)
        }
        } finally {
            // The system download reached a terminal state (or the poll died);
            // drop the id so a later pause/resume cannot target a stale download.
            directIds.remove(job.id)
        }
    }

    /** Find a non-colliding filename in [dir] for [baseName]. */
    private fun uniqueDownloadTarget(dir: File, baseName: String): File {
        var target = File(dir, baseName)
        if (!target.exists()) return target
        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val ext = if (dot > 0) baseName.substring(dot) else ""
        var n = 1
        while (target.exists()) {
            target = File(dir, "${stem}_($n)$ext")
            n++
        }
        return target
    }
}

/** Shared download history used by both the downloader UI and quick downloads. */
object HistoryStore {
    data class Entry(val file: String, val url: String, val ts: Long = System.currentTimeMillis())

    fun load(context: Context): List<Entry> =
        runCatching {
            val prefs = context.getSharedPreferences("video_dl", Context.MODE_PRIVATE)
            val arr = org.json.JSONArray(prefs.getString("history", "[]") ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val f = o.optString("file")
                if (f.isBlank() || !File(f).exists()) null
                else Entry(f, o.optString("url"), o.optLong("ts"))
            }
        }.getOrDefault(emptyList())

    fun add(context: Context, file: String, url: String) {
        val current = load(context) + Entry(file, url)
        save(context, current)
    }

    /** Drop one entry from the history list (does not delete the file). */
    fun remove(context: Context, file: String): List<Entry> =
        save(context, load(context).filterNot { it.file == file })

    fun save(context: Context, entries: List<Entry>): List<Entry> {
        val trimmed = entries.distinctBy { it.file }.take(50)
        runCatching {
            val arr = org.json.JSONArray()
            trimmed.forEach {
                arr.put(org.json.JSONObject().put("file", it.file).put("url", it.url).put("ts", it.ts))
            }
            context.getSharedPreferences("video_dl", Context.MODE_PRIVATE)
                .edit().putString("history", arr.toString()).apply()
        }
        return trimmed
    }
}
