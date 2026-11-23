package com.example

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.util.CrashLog
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import top.niunaijun.blackbox.core.NativeCore
import top.niunaijun.blackbox.utils.BBDiag
import java.io.File

class NjApp : Application() {
    private var blackBoxInitialized = false

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        CrashLog.install()
        if (base == null) return

        val process = processName(base)
        if (!needsBlackBox(process, base.packageName)) return

        runCatching { BBDiag.installCrashHandler(base) }
            .onFailure { Log.e(TAG, "BBDiag install failed", it) }
        runCatching { BlackBoxCore.get().closeCodeInit() }
            .onFailure { Log.e(TAG, "closeCodeInit failed", it) }
        runCatching { BlackBoxCore.get().onBeforeMainApplicationAttach(this, base) }
            .onFailure { Log.e(TAG, "before application attach failed", it) }

        runCatching {
            BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
                override fun getHostPackageName(): String = base.packageName
                override fun isEnableDaemonService(): Boolean = true
                override fun isEnableLauncherActivity(): Boolean = true
            })
            BlackBoxCore.get().addAppLifecycleCallback(object : AppLifecycleCallback() {
                override fun beforeCreateApplication(
                    packageName: String?,
                    processName: String?,
                    context: Context?,
                    userId: Int
                ) {
                    if (!packageName.isNullOrBlank()) {
                        runCatching { NativeCore.cacheRealUid(packageName) }
                            .onFailure { Log.e(TAG, "cacheRealUid failed", it) }
                    }
                }
            })
            blackBoxInitialized = true
        }.onFailure { Log.e(TAG, "BlackBox attach failed", it) }

        runCatching { BlackBoxCore.get().onAfterMainApplicationAttach(this, base) }
            .onFailure { Log.e(TAG, "after application attach failed", it) }
    }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install()
        if (!blackBoxInitialized) return
        runCatching { BlackBoxCore.get().doCreate() }
            .onFailure { Log.e(TAG, "BlackBox doCreate failed", it) }
    }

    private fun processName(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching {
                File("/proc/self/cmdline").readText().substringBefore('\u0000')
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: context.packageName
        }

    private fun needsBlackBox(process: String, packageName: String): Boolean {
        val suffix = process.removePrefix(packageName)
        return suffix.isEmpty() || suffix == ":black" || SANDBOX_PROXY_PROCESS.matches(suffix)
    }

    companion object {
        private const val TAG = "NjApp"
        private val SANDBOX_PROXY_PROCESS = Regex(":p\\d{1,2}")
    }
}
