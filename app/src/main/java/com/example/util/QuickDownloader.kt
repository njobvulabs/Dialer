package com.example.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Entry point for downloads started from web apps / the browser. Everything
 * now runs through [DownloadManager]'s single queue, so these jobs appear in
 * the Video Downloader app with live progress and pause/resume/cancel, and
 * post system notifications like every other download.
 */
object QuickDownloader {

    /**
     * @return the job now running. If this exact URL + quality was already
     *   queued or downloading, that existing job is returned and no second one
     *   is created (double-tap safe).
     */
    fun download(context: Context, url: String, quality: String): DlJob {
        val appContext = context.applicationContext
        // Kick engine (+FFmpeg) extraction early on a worker thread so the
        // first job doesn't stall waiting for binaries to unpack.
        return DownloadManager.startDownload(appContext, url, quality)

    }

    /** BroadcastReceiver that cancels a running job by its queue id. */
    class CancelReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val jobId = intent.getStringExtra("jobId") ?: return
            runCatching {
                YoutubeDL.getInstance().destroyProcessById(jobId)
            }
        }
    }
}
