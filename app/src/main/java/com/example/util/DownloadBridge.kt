package com.example.util

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File

/** Bridge so the browser can hand a page/video URL to the downloader screen. */
object DownloadBridge {
    @Volatile var pendingUrl: String? = null
    fun takePending(): String? = pendingUrl.also { pendingUrl = null }
}

/** Thin init/update/wrapper around bundled yt-dlp (youtubedl-android). */
object YtDlp {

    @Volatile private var initialized = false
    @Volatile private var warmUpStarted = false
    @Volatile private var ffmpegReady = false
    @Volatile private var initError: String? = null

    private const val PREFS = "ytdlp_engine"
    private const val KEY_UPDATED = "engine_updated"

    fun ensureInit(context: Context): String? {
        if (initialized) return null

        synchronized(this) {
            if (initialized) return null
            return try {
                YoutubeDL.getInstance().init(context.applicationContext)
                // FFmpeg is required for merging DASH streams (YouTube etc.)
                // and for -x audio extraction. Non-fatal on failure; jobs that
                // need it will surface a clear error instead.
                ffmpegReady = try {
                    com.yausername.ffmpeg.FFmpeg.getInstance()
                        .init(context.applicationContext)
                    true
                } catch (e: Exception) {
                    false
                }
                initialized = true
                initError = null
                null
            } catch (e: Exception) {
                initError = e.message ?: "init failed"
                initError
            }
        }
    }

    /**
     * Kick [ensureInit] off on a background thread so the first `Download` tap
     * never pays for it.
     *
     * [ensureInit] is a blocking call that unpacks the bundled Python
     * (~11.7 MB) and FFmpeg (~17.1 MB) payloads. It used to run inline on the
     * download path, i.e. inside the web app's process right after the user
     * tapped Download — ~29 MB of extraction in one go, which is a realistic
     * low-memory-kill on this device and presented as "the web app closed".
     * Calling this when a download surface opens (the downloader screen or
     * Quick Download dialog) moves the cost off that path; [ensureInit] stays
     * the synchronous entry point and is idempotent, so a download that beats
     * the warm-up still initialises correctly.
     */
    fun warmUp(context: Context) {
        if (initialized || warmUpStarted) return
        synchronized(this) {
            if (initialized || warmUpStarted) return
            warmUpStarted = true
        }
        Thread({
            runCatching { ensureInit(context.applicationContext) }
        }, "ytdlp-warmup").apply { isDaemon = true }.start()
    }

    /**
     * Path for yt-dlp's --ffmpeg-location, or null when FFmpeg did not
     * initialize. The library extracts under noBackupFilesDir; the exact
     * subdir varies between releases, so probe known locations first.
     */
    fun ffmpegLocation(context: Context): String? {
        if (!ffmpegReady) return null
        val base = context.noBackupFilesDir ?: return null
        val candidates = listOf(
            java.io.File(java.io.File(base, "packages"), "ffmpeg"),
            java.io.File(java.io.File(base, "youtubedl-android"), "ffmpeg")
        )
        candidates.firstOrNull { c -> c.isDirectory && c.listFiles()?.isNotEmpty() == true }
            ?.let { return it.absolutePath }
        // Last resort: any file named *ffmpeg* anywhere under noBackupFilesDir.
        val found = base.walkTopDown()
            .filter { it.isFile && it.name.contains("ffmpeg", true) }
            .firstOrNull() ?: return null
        return found.parentFile?.absolutePath ?: base.absolutePath
    }

    fun lastInitError(): String? = initError

    fun isReady(): Boolean = initialized && initError == null

    /**
     * Download and install the latest official yt-dlp release into app storage.
     * Sites like YouTube change constantly; an up-to-date extractor fixes most
     * "No video formats found" failures. Returns null on success.
     */
    fun updateEngine(context: Context): String? {
        if (!isReady()) return "engine not initialized"
        return try {
            YoutubeDL.getInstance().updateYoutubeDL(
                context.applicationContext,
                YoutubeDL.UpdateChannel.STABLE
            )
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_UPDATED, System.currentTimeMillis())
                .apply()
            null
        } catch (e: Exception) {
            e.message ?: "engine update failed"
        }
    }

    /** True until the first successful engine update this install. */
    fun needsAutoUpdate(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_UPDATED, 0L) == 0L

    fun lastAutoUpdate(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_UPDATED, 0L)

    fun engineVersion(context: Context): String =
        runCatching {
            YoutubeDL.getInstance().version(context.applicationContext)
        }.getOrNull() ?: "unknown"
}
