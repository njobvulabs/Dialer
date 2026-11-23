package com.example.util

import android.content.Context
import java.io.File

/**
 * Best-effort cleanup of stale cache/camera files.
 *
 * This used to be a daily WorkManager job, which initialised WorkManager in
 * every cold start just to prune two directories. It now runs once per day from
 * MainActivity after a short delay, off the main thread.
 */
object TempCleanup {

    private const val PREFS = "app_settings"
    private const val KEY_LAST_RUN = "last_temp_cleanup"
    private const val STALE_MS = 2L * 24 * 60 * 60 * 1000
    private const val DEFAULT_INTERVAL_MS = 24L * 60 * 60 * 1000

    fun runIfDue(context: Context, minIntervalMs: Long = DEFAULT_INTERVAL_MS) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_RUN, 0L)
        if (last in 1..now && now - last < minIntervalMs) return

        prune(app.cacheDir, now)
        prune(File(app.filesDir, "Camera"), now)
        prefs.edit().putLong(KEY_LAST_RUN, System.currentTimeMillis()).apply()
    }

    private fun prune(root: File?, now: Long): Int {
        if (root == null || !root.exists() || !root.isDirectory) return 0
        var deleted = 0
        root.listFiles()?.forEach { file ->
            if (file.isFile && now - file.lastModified() > STALE_MS) {
                if (file.delete()) deleted++
            } else if (file.isDirectory) {
                deleted += prune(file, now)
            }
        }
        return deleted
    }
}
