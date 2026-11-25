package com.example.ui.screens.sandbox

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.example.util.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.entity.location.BLocation
import top.niunaijun.blackbox.fake.frameworks.BLocationManager
import java.io.File
import java.util.zip.ZipFile

data class SandboxApp(
    val packageName: String,
    val label: String,
    val version: String,
    val sharedFolders: Set<String> = emptySet(),
    val minSdk: Int = 0,
    val targetSdk: Int = 0,
    val requestedPermissions: List<String> = emptyList(),
    val userId: Int = 0
) {
    val sharedStorage: Boolean get() = sharedFolders.isNotEmpty()
}

data class SandboxHostApp(
    val packageName: String,
    val label: String,
    val version: String,
    val apkPath: String
)

data class SandboxUser(
    val id: Int,
    val name: String,
    val appCount: Int
)

data class SandboxFakeLocation(
    val latitude: Double,
    val longitude: Double
)

data class SandboxApkPreview(
    val packageName: String,
    val label: String,
    val version: String
)

data class SandboxApkCompatibility(
    val minSdk: Int = 0,
    val targetSdk: Int = 0,
    val nativeAbis: Set<String> = emptySet(),
    val warnings: List<String> = emptyList()
)

data class SandboxApkInspection(
    val file: File,
    val preview: SandboxApkPreview?,
    val error: String? = null,
    val splitFiles: List<File> = emptyList(),
    val compatibility: SandboxApkCompatibility = SandboxApkCompatibility()
) {
    val splitCount: Int get() = splitFiles.size
}

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
    private const val MAX_APK_ENTRIES = 64
    private const val MAX_APK_ENTRY_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_NATIVE_ABI_ENTRIES = 4096

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

    private fun nativeAbisIn(files: List<File>): Set<String> {
        val found = linkedSetOf<String>()
        var scanned = 0
        for (file in files) {
            runCatching {
                ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements() && scanned < MAX_NATIVE_ABI_ENTRIES) {
                        val name = entries.nextElement().name
                        scanned++
                        if (!name.startsWith("lib/")) continue
                        val abi = name.removePrefix("lib/").substringBefore('/', "")
                        if (abi.isNotBlank()) found.add(abi)
                    }
                }
            }
            if (scanned >= MAX_NATIVE_ABI_ENTRIES) break
        }
        return found
    }

    private fun compatibilityFor(info: PackageInfo?, files: List<File>): SandboxApkCompatibility {
        val appInfo = info?.applicationInfo
        val minSdk = appInfo?.minSdkVersion ?: 0
        val targetSdk = appInfo?.targetSdkVersion ?: 0
        val nativeAbis = nativeAbisIn(files)
        val warnings = mutableListOf<String>()
        if (minSdk > Build.VERSION.SDK_INT) {
            warnings += "Requires Android API $minSdk, but this device is API ${Build.VERSION.SDK_INT}."
        }
        if (targetSdk > Build.VERSION.SDK_INT) {
            warnings += "Targets API $targetSdk, so behavior may differ on this device."
        }
        when {
            nativeAbis.isEmpty() -> Unit
            !nativeAbis.contains("arm64-v8a") ->
                warnings += "Native libraries are for ${nativeAbis.sorted().joinToString(", ")}. This sandbox supports only arm64-v8a."
            nativeAbis.any { it != "arm64-v8a" } ->
                warnings += "Contains native libraries for ${nativeAbis.sorted().joinToString(", ")}. Only arm64-v8a code will work."
        }
        return SandboxApkCompatibility(minSdk, targetSdk, nativeAbis, warnings)
    }

    private fun appLabel(context: Context, info: PackageInfo?): String {
        if (info == null) return ""
        return runCatching {
            info.applicationInfo?.loadLabel(context.packageManager)?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: info.packageName
    }

    suspend fun loadApps(context: Context, userId: Int): List<SandboxApp> = withContext(Dispatchers.IO) {
        BlackBoxCore.get().getInstalledPackages(PackageManager.GET_PERMISSIONS, userId).orEmpty().map { info ->
            SandboxApp(
                packageName = info.packageName,
                label = appLabel(context, info),
                version = formatVersion(info),
                sharedFolders = runCatching {
                    BlackBoxCore.get().getSharedFolders(info.packageName, userId)
                }.getOrDefault(emptySet()),
                minSdk = info.applicationInfo?.minSdkVersion ?: 0,
                targetSdk = info.applicationInfo?.targetSdkVersion ?: 0,
                requestedPermissions = info.requestedPermissions?.toList().orEmpty(),
                userId = userId
            )
        }
    }

    suspend fun loadApp(context: Context, packageName: String, userId: Int): SandboxApp? = withContext(Dispatchers.IO) {
        val info = runCatching {
            BlackBoxCore.getBPackageManager().getPackageInfo(packageName, PackageManager.GET_PERMISSIONS, userId)
        }.getOrNull() ?: return@withContext null
        SandboxApp(
            packageName = packageName,
            label = appLabel(context, info),
            version = formatVersion(info),
            sharedFolders = runCatching {
                BlackBoxCore.get().getSharedFolders(packageName, userId)
            }.getOrDefault(emptySet()),
            minSdk = info.applicationInfo?.minSdkVersion ?: 0,
            targetSdk = info.applicationInfo?.targetSdkVersion ?: 0,
            requestedPermissions = info.requestedPermissions?.toList().orEmpty(),
            userId = userId
        )
    }

    private fun displayName(context: Context, uri: Uri): String? {
        val fromProvider = if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        } else {
            null
        }
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    private fun packageInfoFor(context: Context, file: File): PackageInfo? =
        runCatching { context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) }.getOrNull()

    private fun bundleEntryNames(file: File): List<String> = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().toList().filter { entry ->
                !entry.isDirectory &&
                    entry.name.substringAfterLast('/').endsWith(".apk", ignoreCase = true) &&
                    entry.name.count { it == '/' } <= 2
            }.map { it.name }
        }
    }.getOrDefault(emptyList())

    private fun uniqueStagedFile(dir: File, rawName: String, used: List<File>): File {
        val safeName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').ifEmpty { "apk_${used.size}.apk" }
        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val extension = if (dot > 0) safeName.substring(dot) else ""
        var candidate = File(dir, safeName)
        var index = 1
        while (candidate.exists() || candidate in used) {
            candidate = File(dir, "$base ($index)$extension")
            index++
        }
        return candidate
    }

    private fun extractApkEntries(source: File, dir: File, names: List<String>): List<File> {
        val extracted = mutableListOf<File>()
        ZipFile(source).use { zip ->
            for (entryName in names) {
                val entry = zip.getEntry(entryName) ?: continue
                val destination = uniqueStagedFile(dir, entryName.substringAfterLast('/'), extracted)
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_APK_ENTRY_BYTES) {
                                throw IllegalStateException("A split APK in this bundle is too large.")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                extracted += destination
            }
        }
        if (extracted.isEmpty()) throw IllegalStateException("This bundle does not contain any APK files.")
        return extracted
    }

    private fun chooseBaseApk(context: Context, files: List<File>): Pair<File, List<File>>? {
        val parsed = files.mapNotNull { file ->
            val info = packageInfoFor(context, file)
            val packageName = info?.packageName?.takeIf { it.isNotBlank() }
            if (packageName == null) null else Triple(file, info, packageName)
        }
        if (parsed.isEmpty()) return null
        val packageName = parsed.groupBy { it.third }.maxByOrNull { it.value.size }?.key ?: return null
        val candidates = parsed.filter { it.third == packageName }
        val base = candidates.firstOrNull { it.first.name.equals("base.apk", ignoreCase = true) }?.first
            ?: candidates.filter { !it.first.name.contains("split", ignoreCase = true) }
                .maxByOrNull { it.first.length() }?.first
            ?: candidates.maxByOrNull { it.first.length() }!!.first
        val splits = candidates.map { it.first }.filter { it != base }
        return base to splits
    }

    suspend fun inspectApk(context: Context, uri: Uri, userId: Int): SandboxApkInspection = withContext(Dispatchers.IO) {
        val token = "${System.currentTimeMillis()}_${(Math.random() * 1_000_000).toInt()}"
        val dir = File(context.cacheDir, "sandbox_install_$token")
        dir.mkdirs()
        val name = displayName(context, uri).orEmpty()
        val bundleName = listOf(".apks", ".xapk", ".zip").any { name.endsWith(it, ignoreCase = true) }
        val staged = File(dir, if (bundleName) "bundle.bin" else "base.apk")
        val inspection = try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open the selected APK")
            input.use { source -> staged.outputStream().use { source.copyTo(it) } }
            val entries = bundleEntryNames(staged)
            if (entries.size > MAX_APK_ENTRIES) {
                throw IllegalStateException("This APK bundle has too many split files.")
            }
            val files = if (entries.isNotEmpty()) {
                val extracted = extractApkEntries(staged, dir, entries)
                runCatching { staged.delete() }
                val selected = chooseBaseApk(context, extracted)
                    ?: throw IllegalStateException("Could not identify the base APK in this bundle.")
                listOf(selected.first) + selected.second
            } else {
                listOf(staged)
            }
            val file = files.first()
            val info = packageInfoFor(context, file)
            val compatibilityBase = compatibilityFor(info, files)
            val compatibilityWarnings = compatibilityBase.warnings.toMutableList()
            if (bundleName && entries.isNotEmpty() && files.none { it.name.equals("base.apk", ignoreCase = true) }) {
                compatibilityWarnings += "Bundle has split APKs but no base.apk; using the largest APK as the base."
            }
            val compatibility = compatibilityBase.copy(warnings = compatibilityWarnings)
            val packageName = info?.packageName?.takeIf { it.isNotBlank() }
            val preview = if (packageName == null) {
                null
            } else {
                info!!.applicationInfo?.sourceDir = file.absolutePath
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
            SandboxApkInspection(file, preview, error, files.drop(1), compatibility)
        } catch (error: Throwable) {
            SandboxApkInspection(staged, null, "Could not read APK: ${errorText(error)}")
        }
        if (inspection.error != null) discardInspection(inspection)
        inspection
    }

    fun discardInspection(inspection: SandboxApkInspection) {
        (listOf(inspection.file) + inspection.splitFiles).forEach { runCatching { it.delete() } }
        inspection.file.parentFile?.takeIf { it.name.startsWith("sandbox_install_") }?.let { runCatching { it.deleteRecursively() } }
    }

    suspend fun installApk(context: Context, inspection: SandboxApkInspection, userId: Int): SandboxInstallResult = withContext(Dispatchers.IO) {
        try {
            val result = runCatching {
                BlackBoxCore.get().installSplitPackageAsUser(inspection.file, inspection.splitFiles, userId)
            }
            result.fold(
                onSuccess = { install ->
                    if (install.success) {
                        val installedPackage = install.packageName?.takeIf { it.isNotBlank() }
                            ?: inspection.preview?.packageName
                        var appliedDefaults = false
                        if (installedPackage != null && SandboxPrefs.applyDefaultsOnInstall(context)) {
                            val folders = SandboxPrefs.defaultSharedFolders(context)
                            if (folders.isNotEmpty() && (!needsAllFilesAccessPrompt() || hasAllFilesAccess())) {
                                runCatching {
                                    BlackBoxCore.get().setSharedFolders(installedPackage, userId, folders)
                                }.onSuccess { appliedDefaults = true }
                            }
                        }
                        SandboxInstallResult(
                            ok = true,
                            message = "Installed ${install.packageName ?: inspection.preview?.label ?: "APK"} in the sandbox." +
                                if (appliedDefaults) " Default shared folders applied." else "",
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

    suspend fun setSharedFolders(
        packageName: String,
        label: String,
        userId: Int,
        folders: Set<String>,
        allFilesGranted: Boolean = hasAllFilesAccess()
    ): SandboxActionResult = withContext(Dispatchers.IO) {
        if (folders.isNotEmpty() && needsAllFilesAccessPrompt() && !allFilesGranted) {
            SandboxActionResult(
                ok = false,
                message = "Grant all files access before enabling shared folders.",
                needsAllFilesAccess = true
            )
        } else {
            runCatching {
                BlackBoxCore.get().setSharedFolders(packageName, userId, folders)
                BlackBoxCore.get().stopPackage(packageName, userId)
                BlackBoxCore.get().getSharedFolders(packageName, userId)
            }.fold(
                onSuccess = { active ->
                    val summary = if (active.isEmpty()) "Virtual storage restored"
                    else "Shared folders enabled: ${active.sorted().joinToString(", ")}"
                    SandboxActionResult(true, "$summary for $label. Relaunch it to apply.")
                },
                onFailure = { SandboxActionResult(false, "Could not change storage access: ${errorText(it)}") }
            )
        }
    }

    suspend fun setSharedFolder(
        packageName: String,
        label: String,
        userId: Int,
        folder: String,
        enabled: Boolean,
        allFilesGranted: Boolean = hasAllFilesAccess()
    ): SandboxActionResult = withContext(Dispatchers.IO) {
        val current = runCatching {
            BlackBoxCore.get().getSharedFolders(packageName, userId)
        }.getOrDefault(emptySet()).toMutableSet()
        if (enabled) current.add(folder) else current.remove(folder)
        setSharedFolders(packageName, label, userId, current, allFilesGranted)
    }

    suspend fun setSharedStorage(
        packageName: String,
        label: String,
        userId: Int,
        enabled: Boolean,
        allFilesGranted: Boolean = hasAllFilesAccess()
    ): SandboxActionResult = setSharedFolders(
        packageName,
        label,
        userId,
        if (enabled) SandboxStorageFolders.ALL else emptySet(),
        allFilesGranted
    )

    suspend fun loadHostApps(context: Context): List<SandboxHostApp> = withContext(Dispatchers.IO) {
        runCatching {
            context.packageManager.getInstalledPackages(0).orEmpty()
        }.getOrDefault(emptyList()).mapNotNull { info ->
            val appInfo = info.applicationInfo ?: return@mapNotNull null
            val apkPath = appInfo.sourceDir?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (info.packageName == context.packageName) return@mapNotNull null
            if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@mapNotNull null
            SandboxHostApp(
                packageName = info.packageName,
                label = appLabel(context, info),
                version = formatVersion(info),
                apkPath = apkPath
            )
        }.sortedBy { it.label.lowercase() }
    }

    suspend fun cloneHostApp(
        context: Context,
        packageName: String,
        label: String,
        userId: Int
    ): SandboxInstallResult = withContext(Dispatchers.IO) {
        runCatching {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            val appInfo = info.applicationInfo
                ?: throw IllegalStateException("No application info for $label")
            val apkPath = appInfo.sourceDir?.takeIf { File(it).exists() }
                ?: throw IllegalStateException("No installed APK found for $label")
            val configuredSplits = appInfo.splitSourceDirs?.filterNotNull().orEmpty()
            val splitFiles = configuredSplits.map(::File).filter { it.exists() }
            if (configuredSplits.isNotEmpty() && splitFiles.size != configuredSplits.size) {
                throw IllegalStateException("Some split APK files are missing for $label")
            }
            if (splitFiles.isEmpty()) {
                BlackBoxCore.get().installPackageAsUser(File(apkPath), userId)
            } else {
                BlackBoxCore.get().installSplitPackageAsUser(File(apkPath), splitFiles, userId)
            }
        }.fold(
            onSuccess = { install ->
                if (install.success) {
                    val appliedDefaults = applyInstallDefaults(context, install.packageName ?: packageName, userId)
                    SandboxInstallResult(
                        ok = true,
                        message = "Cloned $label into sandbox user $userId." +
                            if (appliedDefaults) " Default shared folders applied." else "",
                        preview = SandboxApkPreview(
                            packageName = install.packageName ?: packageName,
                            label = label,
                            version = ""
                        )
                    )
                } else {
                    SandboxInstallResult(
                        ok = false,
                        message = "Clone failed: ${friendlyInstallError(install.msg)}"
                    )
                }
            },
            onFailure = { SandboxInstallResult(false, "Clone failed: ${errorText(it)}") }
        )
    }

    suspend fun cloneToUser(
        context: Context,
        app: SandboxApp,
        targetUserId: Int
    ): SandboxInstallResult = withContext(Dispatchers.IO) {
        runCatching {
            val info = BlackBoxCore.getBPackageManager()
                .getPackageInfo(app.packageName, 0, app.userId)
            val appInfo = info.applicationInfo
                ?: throw IllegalStateException("No installed application info for ${app.label}")
            val apkPath = appInfo.sourceDir?.takeIf { File(it).exists() }
                ?: throw IllegalStateException("No installed APK found for ${app.label}")
            val configuredSplits = appInfo.splitSourceDirs?.filterNotNull().orEmpty()
            val splitFiles = configuredSplits.map(::File).filter { it.exists() }
            if (configuredSplits.isNotEmpty() && splitFiles.size != configuredSplits.size) {
                throw IllegalStateException("Some split APK files are missing for ${app.label}")
            }
            if (BlackBoxCore.get().isInstalled(app.packageName, targetUserId)) {
                throw IllegalStateException("${app.label} is already installed in sandbox user $targetUserId")
            }
            val install = if (splitFiles.isEmpty()) {
                BlackBoxCore.get().installPackageAsUser(File(apkPath), targetUserId)
            } else {
                BlackBoxCore.get().installSplitPackageAsUser(File(apkPath), splitFiles, targetUserId)
            }
            if (!install.success) {
                SandboxInstallResult(false, "Clone failed: ${friendlyInstallError(install.msg)}")
            } else {
                val appliedDefaults = applyInstallDefaults(context, app.packageName, targetUserId)
                SandboxInstallResult(
                    ok = true,
                    message = "Cloned ${app.label} into sandbox user $targetUserId." +
                        if (appliedDefaults) " Default shared folders applied." else "",
                    preview = SandboxApkPreview(app.packageName, app.label, app.version)
                )
            }
        }.getOrElse { SandboxInstallResult(false, "Clone failed: ${errorText(it)}") }
    }

    suspend fun users(): List<SandboxUser> = withContext(Dispatchers.IO) {
        runCatching {
            BlackBoxCore.get().getUsers().orEmpty().map { user ->
                val count = runCatching {
                    BlackBoxCore.get().getInstalledPackages(0, user.id).orEmpty().size
                }.getOrDefault(0)
                SandboxUser(id = user.id, name = user.name?.takeIf { it.isNotBlank() } ?: "User ${user.id}", appCount = count)
            }.sortedBy { it.id }
        }.getOrDefault(listOf(SandboxUser(0, "User 0", 0)))
    }

    suspend fun createUser(): SandboxActionResult = withContext(Dispatchers.IO) {
        runCatching {
            val existing = BlackBoxCore.get().getUsers().orEmpty().map { it.id }.toSet()
            val nextId = generateSequence(1) { it + 1 }.first { it !in existing }
            val user = BlackBoxCore.get().createUser(nextId)
            if (user == null) {
                SandboxActionResult(false, "Could not create sandbox user.")
            } else {
                SandboxActionResult(true, "Created sandbox user $nextId.")
            }
        }.getOrElse { SandboxActionResult(false, "Could not create user: ${errorText(it)}") }
    }

    suspend fun deleteUser(userId: Int): SandboxActionResult = withContext(Dispatchers.IO) {
        if (userId == 0) {
            SandboxActionResult(false, "The default sandbox user cannot be deleted.")
        } else {
            runCatching {
                BlackBoxCore.get().deleteUser(userId)
                SandboxActionResult(true, "Deleted sandbox user $userId and its apps.")
            }.getOrElse { SandboxActionResult(false, "Could not delete user: ${errorText(it)}") }
        }
    }

    suspend fun isRunning(packageName: String, userId: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching { BlackBoxCore.isRunningApplication(packageName, userId) }.getOrDefault(false)
    }

    suspend fun stopRunningApps(userId: Int) = withContext(Dispatchers.IO) {
        stopRunningAppsNow(userId)
    }

    fun stopRunningAppsNow(userId: Int) {
        runCatching {
            BlackBoxCore.get().getInstalledPackages(0, userId).orEmpty().forEach { info ->
                if (BlackBoxCore.isRunningApplication(info.packageName, userId)) {
                    runCatching { BlackBoxCore.get().stopPackage(info.packageName, userId) }
                }
            }
        }
    }

    fun fakeLocation(packageName: String, userId: Int): SandboxFakeLocation? = runCatching {
        val location = BLocationManager.get().getLocation(userId, packageName)
        if (location == null || location.isEmpty()) null
        else SandboxFakeLocation(location.latitude, location.longitude)
    }.getOrNull()

    fun globalFakeLocation(): SandboxFakeLocation? = runCatching {
        val location = BLocationManager.get().getGlobalLocation()
        if (location == null || location.isEmpty()) null
        else SandboxFakeLocation(location.latitude, location.longitude)
    }.getOrNull()

    fun setFakeLocation(
        packageName: String,
        userId: Int,
        location: SandboxFakeLocation?
    ): SandboxActionResult = runCatching {
        if (location == null) {
            BLocationManager.disableFakeLocation(userId, packageName)
            SandboxActionResult(true, "Fake location disabled for $packageName.")
        } else {
            validateCoordinates(location)
            BLocationManager.get().setLocation(userId, packageName, BLocation(location.latitude, location.longitude))
            BLocationManager.get().setPattern(userId, packageName, BLocationManager.OWN_MODE)
            SandboxActionResult(true, "Fake location set for $packageName.")
        }
    }.getOrElse { SandboxActionResult(false, "Could not set fake location: ${errorText(it)}") }

    fun useGlobalFakeLocation(packageName: String, userId: Int): SandboxActionResult = runCatching {
        if (globalFakeLocation() == null) {
            SandboxActionResult(false, "Set a global fake location first.")
        } else {
            BLocationManager.get().setPattern(userId, packageName, BLocationManager.GLOBAL_MODE)
            SandboxActionResult(true, "$packageName now uses the global fake location.")
        }
    }.getOrElse { SandboxActionResult(false, "Could not set fake location mode: ${errorText(it)}") }

    fun setGlobalFakeLocation(
        location: SandboxFakeLocation?,
        applyToAll: Boolean,
        apps: List<SandboxApp>
    ): SandboxActionResult = runCatching {
        if (location == null) {
            apps.forEach { app: SandboxApp ->
                runCatching { BLocationManager.disableFakeLocation(app.userId, app.packageName) }
            }
            BLocationManager.get().setGlobalLocation(null)
            SandboxActionResult(true, "Global fake location cleared.")
        } else {
            validateCoordinates(location)
            BLocationManager.get().setGlobalLocation(BLocation(location.latitude, location.longitude))
            if (applyToAll) {
                apps.forEach { app ->
                    runCatching {
                        BLocationManager.get().setPattern(app.userId, app.packageName, BLocationManager.GLOBAL_MODE)
                    }
                }
            }
            SandboxActionResult(
                true,
                if (applyToAll) "Global fake location applied to all sandbox apps."
                else "Global fake location saved. Apps using the global pattern will receive it."
            )
        }
    }.getOrElse { SandboxActionResult(false, "Could not set global fake location: ${errorText(it)}") }

    private fun validateCoordinates(location: SandboxFakeLocation) {
        require(location.latitude in -90.0..90.0) { "Latitude must be between -90 and 90." }
        require(location.longitude in -180.0..180.0) { "Longitude must be between -180 and 180." }
    }

    private fun applyInstallDefaults(context: Context, packageName: String, userId: Int): Boolean {
        if (!SandboxPrefs.applyDefaultsOnInstall(context)) return false
        val folders = SandboxPrefs.defaultSharedFolders(context)
        if (folders.isEmpty() || (needsAllFilesAccessPrompt() && !hasAllFilesAccess())) return false
        return runCatching {
            BlackBoxCore.get().setSharedFolders(packageName, userId, folders)
        }.isSuccess
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
