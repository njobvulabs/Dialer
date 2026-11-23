package com.example

import android.app.Application
import com.example.util.CrashLog

/**
 * Per-process startup for every one of the app's OS processes.
 *
 * [CrashLog.install] has to happen here rather than in an Activity: a crash in a
 * private process (:webapps, :whatsapp, :whatsapp2) destroys the Activity and
 * restarts the process, so from the user's side the web app simply closed itself
 * with no visible reason. The previous handler only existed in MainActivity (main
 * process) and GeckoWebActivity (:whatsapp), which left :webapps — where the
 * Facebook web app runs — with no diagnostics at all.
 *
 * The yt-dlp payload is deliberately NOT extracted here. Doing that in every
 * process at startup spent ~29 MB of Python/FFmpeg extraction even when the user
 * never opened a downloader. It is now warmed from the download surfaces.
 */
class NjApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install()
    }
}