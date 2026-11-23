package com.example.util

import android.os.Environment
import java.io.File

/**
 * Writes uncaught exceptions to `Download/NJCrash/crash_<ts>.txt`.
 *
 * Installed once per process from [com.example.NjApp]. The app runs several OS
 * processes (main, :webapps, :whatsapp, :whatsapp2) and a crash in a private
 * process is otherwise completely invisible: the Activity is destroyed, the
 * process is restarted, and the user just sees the app "close itself". The crash
 * file is the only on-device evidence available here — this device has no USB
 * debugging, so logcat is unreachable.
 */
object CrashLog {

    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True once [install] has run in this process. */
    val isInstalled: Boolean get() = installed.get()

    fun saveSandboxReport(report: String, packageName: String): File {
        val dir = crashDir()
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Could not create NJCrash folder")
        }
        val safeName = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "sandbox" }
        val file = File(dir, "apk_sandbox_${safeName}_${System.currentTimeMillis()}.txt")
        file.writeText(report)
        return file
    }

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Never let the crash reporter itself throw: a failure here would
            // replace the real stack trace with one of our own.
            runCatching {
                val dir = crashDir()
                dir.mkdirs()
                File(dir, "crash_${System.currentTimeMillis()}.txt").writeText(
                    "Time: ${System.currentTimeMillis()}\n" +
                        "Process: ${currentProcessName()}\n" +
                        "Thread: ${thread.name}\n\n" +
                        android.util.Log.getStackTraceString(throwable)
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun crashDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "NJCrash"
    )

    /**
     * Runtime process name (":whatsapp", ":webapps", …), or the package name in the
     * main process. Recorded with every crash because the symptom is
     * process-specific — a crash in `:whatsapp` is what makes a web app appear to
     * close itself, and the process is what tells the two apart.
     */
    private fun currentProcessName(): String =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.app.Application.getProcessName()
        } else {
            packageNameOf()
        }

    private fun packageNameOf(): String = "com.example"
}