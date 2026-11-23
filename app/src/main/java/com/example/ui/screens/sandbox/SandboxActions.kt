package com.example.ui.screens.sandbox

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.example.util.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import java.io.File

data class SandboxApp(
    val packageName: String,
    val label: String,
    val version: String,
    val sharedStorage: Boolean = false
)

data class SandboxApkPreview(
    val packageName: String,
    val label: String,
    val version: String
)

data class SandboxApkInspection(
    val file: File,
    val preview: SandboxApkPreview?,
    val error: String? = null
)

data class SandboxActionResult(
    val ok: Boolean,
    val message: String,
    val needsAllFilesAccess: Boolean = false
)

data class SandboxInstallResult(
    val ok: Boolean,
    val message: String,
    val preview: SandboxApkPreview? = null
)

object SandboxActions {
    private const val NO_ALL_FILES_ACCESS = "no_all_files_access"

    fun hasAllFilesAccess(): Boolean =
        runCatching { BlackBoxCore.get().hasAllFilesAccess() }.getOrDefault(false)

    fun needsAllFilesAccessPrompt(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun allFilesAccessMessage(): String =
        if (needsAllFilesAccessPrompt()) {
            "All files access is not granted. Grant access to launch cloned apps and use shared folders."
        } else {
            "All files access is not required on this Android version."
        }

    private fun errorText(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private fun formatVersion(info: PackageInfo?): String {
        if (info == null) return ""
        val versionName = info.versionName
        val versionCode = info.versionCode.takeIf { it != 0 }?.toString()
        return when {
            versionName.isNullOrBlank() -> versionCode ?: ""
            versionCode.isNullOrBlank() -> versionName
            else -> "$versionName ($versionCode)"
        }
    }

    private fun appLabel(context: Context, info: PackageInfo?): String {
        if (info == null) return ""
        return runCatching {
            info.applicationInfo?.loadLabel(context.packageManager)?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: info.packageName
    }

    suspend fun loadApps(context: Context, userId: Int): List<SandboxApp> = withContext(Dispatchers.IO) {
        BlackBoxCore.get().getInstalledPackages(0, userId).orEmpty().map { info ->
            SandboxApp(
                packageName = info.packageName,
                label = appLabel(context, info),
                version = formatVersion(info),
                sharedStorage = runCatching {
                    BlackBoxCore.get().hasSharedInternalStorageAccess(info.packageName, userId)
                }.getOrDefault(false)
            )
        }
    }

    suspend fun loadApp(context: Context, packageName: String, userId: Int): SandboxApp? = withContext(Dispatchers.IO) {
        val info = runCatching {
            BlackBoxCore.getBPackageManager().getPackageInfo(packageName, 0, userId)
        }.getOrNull() ?: return@withContext null
        SandboxApp(
            packageName = packageName,
            label = appLabel(context, info),
            version = formatVersion(info),
            sharedStorage = runCatching {
                BlackBoxCore.get().hasSharedInternalStorageAccess(packageName, userId)
            }.getOrDefault(false)
        )
    }

    suspend fun inspectApk(context: Context, uri: Uri, userId: Int): SandboxApkInspection = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "sandbox_${System.currentTimeMillis()}_${(Math.random() * 1_000_000).toInt()}.apk")
        val inspection = try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open the selected APK")
            input.use { source -> file.outputStream().use { source.copyTo(it) } }
            val info = runCatching {
                context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            }.getOrNull()
            val packageName = info?.packageName
            val preview = if (packageName.isNullOrBlank()) {
                null
            } else {
                info.applicationInfo?.sourceDir = file.absolutePath
                info.applicationInfo?.publicSourceDir = file.absolutePath
                SandboxApkPreview(
                    packageName = packageName,
                    label = appLabel(context, info),
                    version = formatVersion(info)
                )
            }
            val error = when {
                preview == null -> "Could not read the package name from this APK."
                preview.packageName == context.packageName -> "Cannot sandbox NJ-Dialer itself."
                BlackBoxCore.get().isInstalled(preview.packageName, userId) ->
                    "${preview.label} is already installed in the sandbox. Remove it before installing this APK."
                else -> null
            }
            SandboxApkInspection(file, preview, error)
        } catch (error: Throwable) {
            runCatching { file.delete() }
            SandboxApkInspection(file, null, "Could not read APK: ${errorText(error)}")
        }
        if (inspection.error != null) runCatching { file.delete() }
        inspection
    }

    fun discardInspection(inspection: SandboxApkInspection) {
        runCatching { inspection.file.delete() }
    }

    suspend fun installApk(inspection: SandboxApkInspection, userId: Int): SandboxInstallResult = withContext(Dispatchers.IO) {
        try {
            val result = runCatching { BlackBoxCore.get().installPackageAsUser(inspection.file, userId) }
            result.fold(
                onSuccess = { install ->
                    if (install.success) {
                        SandboxInstallResult(
                            ok = true,
                            message = "Installed ${install.packageName ?: inspection.preview?.label ?: "APK"} in the sandbox.",
                            preview = inspection.preview
                        )
                    } else {
                        SandboxInstallResult(
                            ok = false,
                            message = "Install failed: ${friendlyInstallError(install.msg)}",
                            preview = inspection.preview
                        )
                    }
                },
                onFailure = {
                    SandboxInstallResult(
                        ok = false,
                        message = "Install failed: ${errorText(it)}",
                        preview = inspection.preview
                    )
                }
            )
        } finally {
            discardInspection(inspection)
        }
    }

    fun friendlyInstallError(raw: String?): String {
        val message = raw?.trim().takeUnless { it.isNullOrBlank() } ?: return "unknown error"
        return when {
            message.contains("already", ignoreCase = true) && message.contains("install", ignoreCase = true) ->
                "$message Remove the existing sandbox app first."
            message.contains("INSTALL_FAILED_", ignoreCase = true) ->
                message.replace("INSTALL_FAILED_", "Install failed: ", ignoreCase = true)
                    .replace('_', ' ')
            message.contains("PARSE", ignoreCase = true) ->
                "This APK could not be parsed. It may be corrupt or incompatible."
            else -> message.replace('_', ' ')
        }
    }

    fun friendlyLaunchError(raw: String?): String {
        val message = raw?.trim().takeUnless { it.isNullOrBlank() } ?: return "unknown error"
        return when {
            message.contains(NO_ALL_FILES_ACCESS, ignoreCase = true) ->
                "All files access is required. Grant access and retry."
            message.contains("not installed", ignoreCase = true) ->
                "The app is no longer installed in the sandbox."
            message.contains("UnsatisfiedLinkError", ignoreCase = true) ||
                message.contains("No implementation found", ignoreCase = true) ->
                "A required native library is missing or incompatible with this device."
            message.contains("32-bit", ignoreCase = true) || message.contains("32 bit", ignoreCase = true) ->
                "This APK needs 32-bit native libraries, which this sandbox does not support."
            message.contains("MediaSessionService", ignoreCase = true) ||
                message.contains("Manifest doesn't declare", ignoreCase = true) ->
                "The app expects a component that is missing from its manifest or the virtual environment."
            else -> message.replace('_', ' ')
        }
    }

    suspend fun launch(
        packageName: String,
        label: String,
        userId: Int,
        allFilesGranted: Boolean = hasAllFilesAccess()
    ): SandboxActionResult = withContext(Dispatchers.IO) {
        if (needsAllFilesAccessPrompt() && !allFilesGranted) {
            SandboxActionResult(
                ok = false,
                message = "All files access is not granted. Grant access to launch $label.",
                needsAllFilesAccess = true
            )
        } else {
            val launched = runCatching { BlackBoxCore.get().launchApk(packageName, userId) }
            val rawError = BlackBoxCore.get().lastLaunchError
            when {
                launched.isFailure -> SandboxActionResult(
                    ok = false,
                    message = "Launch failed: ${errorText(launched.exceptionOrNull()!!)}"
                )
                launched.getOrDefault(false) -> SandboxActionResult(true, "Launched $label.")
                else -> SandboxActionResult(
                    ok = false,
                    message = "Launch failed: ${friendlyLaunchError(rawError)}",
                    needsAllFilesAccess = rawError?.contains(NO_ALL_FILES_ACCESS, ignoreCase = true) == true
                )
            }
        }
    }

    suspend fun stop(packageName: String, label: String, userId: Int): SandboxActionResult = withContext(Dispatchers.IO) {
        runCatching { BlackBoxCore.get().stopPackage(packageName, userId) }.fold(
            onSuccess = { SandboxActionResult(true, "Stop requested for $label.") },
            onFailure = { SandboxActionResult(false, "Could not request stop: ${errorText(it)}") }
        )
    }

    suspend fun clear(packageName: String, label: String, userId: Int): SandboxActionResult = withContext(Dispatchers.IO) {
        runCatching { BlackBoxCore.get().clearPackage(packageName, userId) }.fold(
            onSuccess = { SandboxActionResult(true, "Clear requested for $label.") },
            onFailure = { SandboxActionResult(false, "Could not request clear: ${errorText(it)}") }
        )
    }

    suspend fun remove(packageName: String, label: String, userId: Int): SandboxActionResult = withContext(Dispatchers.IO) {
        runCatching {
            BlackBoxCore.get().uninstallPackageAsUser(packageName, userId)
            BlackBoxCore.get().setSharedInternalStorageAccess(packageName, userId, false)
        }.fold(
            onSuccess = { SandboxActionResult(true, "Removed $label from the sandbox.") },
            onFailure = { SandboxActionResult(false, "Could not request removal: ${errorText(it)}") }
        )
    }

    suspend fun setSharedStorage(
        packageName: String,
        label: String,
        userId: Int,
        enabled: Boolean,
        allFilesGranted: Boolean = hasAllFilesAccess()
    ): SandboxActionResult = withContext(Dispatchers.IO) {
        if (enabled && needsAllFilesAccessPrompt() && !allFilesGranted) {
            SandboxActionResult(
                ok = false,
                message = "Grant all files access before enabling shared folders.",
                needsAllFilesAccess = true
            )
        } else {
            runCatching {
                BlackBoxCore.get().setSharedInternalStorageAccess(packageName, userId, enabled)
                BlackBoxCore.get().stopPackage(packageName, userId)
                BlackBoxCore.get().hasSharedInternalStorageAccess(packageName, userId)
            }.fold(
                onSuccess = { active ->
                    SandboxActionResult(
                        ok = true,
                        message = if (active) "Shared folders enabled for $label. Relaunch it to apply."
                        else "Virtual storage restored for $label. Relaunch it to apply."
                    )
                },
                onFailure = { SandboxActionResult(false, "Could not change storage access: ${errorText(it)}") }
            )
        }
    }

    suspend fun environmentReport(): String = withContext(Dispatchers.IO) {
        runCatching { BlackBoxCore.get().getEnvironmentReport() }
            .getOrElse { "Environment report failed: ${errorText(it)}" }
    }

    suspend fun diagnosticReport(packageName: String, userId: Int): String = withContext(Dispatchers.IO) {
        runCatching { BlackBoxCore.get().getDiagnosticReport(packageName, userId) }
            .getOrElse { "Diagnostics failed: ${errorText(it)}" }
    }

    suspend fun saveReport(context: Context, report: String, packageName: String): String = withContext(Dispatchers.IO) {
        runCatching { CrashLog.saveSandboxReport(report, packageName).absolutePath }.fold(
            onSuccess = { path -> "Saved to $path" },
            onFailure = { "Could not save: ${errorText(it)}" }
        )
    }

    suspend fun exportCrashLogs(context: Context, packageName: String?, userId: Int): String = withContext(Dispatchers.IO) {
        runCatching {
            val report = StringBuilder()
            fun appendFile(title: String, file: File, maxChars: Int = 30000) {
                report.append("\n=== ").append(title).append(" ===\n")
                if (!file.exists()) {
                    report.append("(missing: ").append(file.absolutePath).append(")\n")
                    return
                }
                val text = runCatching { file.readText() }.getOrDefault("(unreadable)")
                if (text.length > maxChars) {
                    report.append("(truncated, last ").append(maxChars).append(" chars)\n")
                    report.append(text.takeLast(maxChars))
                } else {
                    report.append(text)
                }
            }
            if (packageName == null) {
                report.append(BlackBoxCore.get().getEnvironmentReport())
            } else {
                report.append(BlackBoxCore.get().getDiagnosticReport(packageName, userId))
            }
            val diagDir = File(context.filesDir, "bb_diag")
            appendFile("BBDiag crash", File(diagDir, "crash.txt"))
            appendFile("BBDiag stage", File(diagDir, "stage.txt"), 4000)
            appendFile("BBDiag trace", File(diagDir, "trace.log"))
            val crashDir = File(context.filesDir, "crash_logs")
            val logs = crashDir.listFiles { file ->
                file.isFile && file.name.endsWith(".log", ignoreCase = true)
            }.orEmpty().sortedByDescending { it.lastModified() }.take(8)
            if (logs.isEmpty()) {
                report.append("\n=== CrashMonitor logs ===\n(no logs in ")
                    .append(crashDir.absolutePath).append(")\n")
            } else {
                logs.forEachIndexed { index, file ->
                    appendFile("CrashMonitor ${index + 1}: ${file.name}", file)
                }
            }
            CrashLog.saveSandboxReport(report.toString(), packageName ?: "sandbox").absolutePath
        }.fold(
            onSuccess = { path -> "Saved crash logs to $path" },
            onFailure = { "Could not export crash logs: ${errorText(it)}" }
        )
    }
}
