package com.example.ui.screens.terminal

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.os.Process
import com.example.NjApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.Channels

object TerminalManager {

    private const val ROOTFS_URL = "https://github.com/termux/proot-distro/releases/download/v4.6.0/debian-aarch64-pd-v4.6.0.tar.xz"
    private const val ROOTFS_DIR_NAME = "debian-rootfs"
    private const val READY_MARKER = ".nj_rootfs_ready"
    private const val ARCHIVE_FILE_NAME = "debian-rootfs.tar.xz"
    private const val BUFFER_SIZE = 1 shl 16
    private const val PROOT_ARGS = "-0 --link2symlink -r {ROOTFS} -b /dev -b /proc -b /sys -b {CACHE}:/tmp -w /root /bin/bash -l"
    private const val SETUP_VERSION = "3" // bump to force re-setup

    fun rootfsDir(context: Context): File = File(context.filesDir, ROOTFS_DIR_NAME)

    fun isInstalled(context: Context): Boolean {
        val d = rootfsDir(context)
        val markerFile = File(d, READY_MARKER)
        if (!markerFile.exists()) return false
        val savedVersion = markerFile.readText().trim()
        if (savedVersion != SETUP_VERSION) {
            // Version mismatch - delete old rootfs to force fresh setup
            deleteRecursively(d)
            return false
        }
        return File(d, "bin/bash").exists()
    }

    fun prootPath(context: Context): String {
        return File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath
    }

    fun loaderPath(context: Context): String {
        return File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so").absolutePath
    }

    fun buildProotArgs(context: Context): String {
        val rootfs = rootfsDir(context).absolutePath
        val cacheDir = context.cacheDir.absolutePath
        return PROOT_ARGS
            .replace("{ROOTFS}", rootfs)
            .replace("{CACHE}", cacheDir)
    }

    fun buildProotArgs(context: Context, command: List<String>): List<String> {
        val rootfs = rootfsDir(context).absolutePath
        val cacheDir = context.cacheDir.absolutePath
        val core = PROOT_ARGS
            .substringBefore(" /bin/bash")
            .split(" ")
            .filter { it.isNotBlank() }
            .map { it.replace("{ROOTFS}", rootfs).replace("{CACHE}", cacheDir) }
        return core + command
    }

    fun buildEnv(context: Context): Array<String> {
        val cacheDir = context.cacheDir.absolutePath
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val loader = File(nativeLibDir, "libproot-loader.so").absolutePath
        return arrayOf(
            "PROOT_LOADER=$loader",
            "PROOT_TMP_DIR=$cacheDir",
            "PROOT_NO_SECCOMP=1",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PREFIX=/usr",
            "TMPDIR=/tmp",
        )
    }

    suspend fun ensureInstalled(
        context: Context,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val dir = rootfsDir(context)
        if (isInstalled(context)) {
            onStatus("Rootfs already installed")
            return@withContext true
        }

        val archiveFile = File(context.filesDir, ARCHIVE_FILE_NAME)
        val tempDir = File(context.filesDir, ROOTFS_DIR_NAME + ".tmp")

        try {
            onStatus("Copying rootfs from assets...")
            copyFromAssets(context, archiveFile) { progress ->
                onProgress(progress.coerceIn(0f, 100f))
            }

            onStatus("Extracting rootfs...")
            onProgress(0f)
            extractRootfs(archiveFile, dir, tempDir) { progress ->
                onProgress(progress.coerceIn(0f, 100f))
            }

            File(dir, READY_MARKER).writeText(SETUP_VERSION)
            setupPasswd(dir)
            setupResolvConf(dir)
            onStatus("Rootfs ready")
            onProgress(100f)
            true
        } catch (e: Exception) {
            onStatus("Error: ${e.message}")
            false
        } finally {
            archiveFile.delete()
            deleteRecursively(tempDir)
        }
    }

    private suspend fun copyFromAssets(
        context: Context,
        dest: File,
        onProgress: (Float) -> Unit
    ) {
        val assetManager = context.assets
        val input = assetManager.open(ARCHIVE_FILE_NAME)
        val totalSize = input.available().toLong()
        var copied = 0L

        input.use { `in` ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = `in`.read(buffer)
                    if (count == -1) break
                    output.write(buffer, 0, count)
                    copied += count
                    if (totalSize > 0) {
                        onProgress((copied.toFloat() / totalSize * 100f).coerceIn(0f, 100f))
                    }
                }
            }
        }
    }

    private fun extractRootfs(
        archiveFile: File,
        destDir: File,
        tempDir: File,
        onProgress: (Float) -> Unit
    ) {
        deleteRecursively(destDir)
        destDir.mkdirs()
        tempDir.mkdirs()

        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis, BUFFER_SIZE).use { bis ->
                XZInputStream(bis).use { xzis ->
                    TarArchiveInputStream(xzis).use { tis ->
                        var entry = tis.nextEntry
                        var entryCount = 0
                        while (entry != null) {
                            entryCount++
                            val name = entry.name.replace('\\', '/')
                            val stripped = if (name.contains('/')) {
                                name.substringAfter('/', "")
                            } else {
                                ""
                            }
                            if (stripped.isNotEmpty()) {
                                val targetFile = File(destDir, stripped)
                                val canonicalDest = destDir.canonicalPath
                                val canonicalTarget = targetFile.canonicalPath
                                if (!canonicalTarget.startsWith(canonicalDest)) {
                                    throw SecurityException("Path traversal attempt: $name")
                                }

                                if (entry.isDirectory) {
                                    targetFile.mkdirs()
                                } else if (entry.isSymbolicLink) {
                                    val linkTarget = entry.linkName
                                    targetFile.parentFile?.mkdirs()
                                    Os.symlink(linkTarget, targetFile.absolutePath)
                                } else if (entry.isLink) {
                                    val linkTarget = entry.linkName
                                    val strippedLink = if (linkTarget.contains('/')) {
                                        linkTarget.substringAfter('/', "")
                                    } else {
                                        linkTarget
                                    }
                                    val linkTargetFile = File(destDir, strippedLink)
                                    targetFile.parentFile?.mkdirs()
                                    Os.link(linkTargetFile.absolutePath, targetFile.absolutePath)
                                } else {
                                    targetFile.parentFile?.mkdirs()
                                    FileOutputStream(targetFile).use { fos ->
                                        BufferedOutputStream(fos, BUFFER_SIZE).use { bos ->
                                            val buffer = ByteArray(BUFFER_SIZE)
                                            while (true) {
                                                val count = tis.read(buffer)
                                                if (count == -1) break
                                                bos.write(buffer, 0, count)
                                            }
                                        }
                                    }
                                    val mode = entry.mode and 0x1FF
                                    if (mode != 0) {
                                        Os.chmod(targetFile.absolutePath, mode)
                                    }
                                }
                            }
                            entry = tis.nextEntry
                        }
                        onProgress(100f)
                    }
                }
            }
        }
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }

    private fun setupPasswd(rootfsDir: File) {
        val passwdFile = File(rootfsDir, "etc/passwd")
        val uid = Process.myUid()
        val passwdEntry = "root:x:0:0:root:/root:/bin/bash\n"
        val uidEntry = "user$uid:x:$uid:$uid:user$uid:/home/user$uid:/bin/bash\n"
        
        // Always rewrite passwd to ensure root is primary and current UID is mapped
        var content = if (passwdFile.exists()) {
            passwdFile.readText()
        } else {
            ""
        }
        
        // Remove any existing root or uid entries
        content = content.lines()
            .filter { !it.startsWith("root:") && !it.contains(":$uid:") }
            .joinToString("\n")
        
        // Create home directories
        File(rootfsDir, "root").mkdirs()
        File(rootfsDir, "home/user$uid").mkdirs()
        
        // Write root first (primary), then actual UID user
        FileWriter(passwdFile).use { writer ->
            writer.write(content)
            if (content.isNotBlank()) writer.write("\n")
            writer.write(passwdEntry)
            writer.write(uidEntry)
        }
    }

    private fun setupResolvConf(rootfsDir: File) {
        val resolvFile = File(rootfsDir, "etc/resolv.conf")
        val resolvContent = "nameserver 8.8.8.8\nnameserver 8.8.4.4\n"
        resolvFile.writeText(resolvContent)
    }

    // Terminal preferences (stored in app_settings)
    private const val PREFS_NAME = "app_settings"
    private const val KEY_TERMINAL_THEME = "terminal_theme"
    private const val KEY_TERMINAL_BELL_VIBRATE = "terminal_bell_vibrate"
    private const val KEY_TERMINAL_FONT_SIZE = "terminal_font_size"
    private const val KEY_TERMINAL_SCALE = "terminal_scale"
    private const val KEY_TERMINAL_EXTRA_KEYS = "terminal_extra_keys"

    fun getTerminalTheme(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TERMINAL_THEME, "default") ?: "default"

    fun setTerminalTheme(context: Context, theme: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TERMINAL_THEME, theme).apply()
    }

    fun getTerminalBellVibrate(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TERMINAL_BELL_VIBRATE, true)

    fun setTerminalBellVibrate(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TERMINAL_BELL_VIBRATE, enabled).apply()
    }

    fun getTerminalFontSize(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_TERMINAL_FONT_SIZE, 14f)

    fun setTerminalFontSize(context: Context, size: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_TERMINAL_FONT_SIZE, size.coerceIn(9f, 32f)).apply()
    }

    fun getTerminalScale(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_TERMINAL_SCALE, 1.0f)

    fun setTerminalScale(context: Context, scale: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_TERMINAL_SCALE, scale.coerceIn(0.5f, 3.0f)).apply()
    }

    fun getExtraKeysVisible(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TERMINAL_EXTRA_KEYS, true)

    fun setExtraKeysVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TERMINAL_EXTRA_KEYS, visible).apply()
    }

    // Theme color definitions (bg, fg, cursor, 16 ANSI colors)
    data class TerminalThemeColors(
        val background: Int,
        val foreground: Int,
        val cursor: Int,
        val ansi: IntArray // 16 colors
    )

    fun getThemeColors(theme: String): TerminalThemeColors = when (theme) {
        "solarized" -> TerminalThemeColors(
            background = 0xFF002B36.toInt(),
            foreground = 0xFF839496.toInt(),
            cursor = 0xFF268BD2.toInt(),
            ansi = intArrayOf(
                0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(), // 0-3: black, red, green, yellow
                0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFEEE8D5.toInt(), // 4-7: blue, magenta, cyan, white
                0xFF002B36.toInt(), 0xFFCB4B16.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(), // 8-11: bright black, red, green, yellow
                0xFF839496.toInt(), 0xFF6C71C4.toInt(), 0xFF93A1A1.toInt(), 0xFFFDF6E3.toInt()  // 12-15: bright blue, magenta, cyan, white
            )
        )
        "dracula" -> TerminalThemeColors(
            background = 0xFF282A36.toInt(),
            foreground = 0xFFF8F8F2.toInt(),
            cursor = 0xFFBD93F9.toInt(),
            ansi = intArrayOf(
                0xFF21222C.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(), // 0-3
                0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFF8F8F2.toInt(), // 4-7
                0xFF6272A4.toInt(), 0xFFFF6E6E.toInt(), 0xFF69FF94.toInt(), 0xFFFFFFA6.toInt(), // 8-11
                0xFFD6ACFF.toInt(), 0xFFFF92DF.toInt(), 0xFFA4FFFF.toInt(), 0xFFFFFFFF.toInt()  // 12-15
            )
        )
        "gruvbox" -> TerminalThemeColors(
            background = 0xFF282828.toInt(),
            foreground = 0xFFEBDDB2.toInt(),
            cursor = 0xFFFABD2F.toInt(),
            ansi = intArrayOf(
                0xFF282828.toInt(), 0xFFCC241D.toInt(), 0xFF98971A.toInt(), 0xFFD79921.toInt(), // 0-3
                0xFF458588.toInt(), 0xFFB16286.toInt(), 0xFF689D6A.toInt(), 0xFFA89984.toInt(), // 4-7
                0xFF928374.toInt(), 0xFFFB4934.toInt(), 0xFFB8BB26.toInt(), 0xFFFABD2F.toInt(), // 8-11
                0xFF83A598.toInt(), 0xFFD3869B.toInt(), 0xFF8EC07C.toInt(), 0xFFEBDBB2.toInt()  // 12-15
            )
        )
        else -> TerminalThemeColors(
            background = 0xFF1E1E1E.toInt(), // Default dark
            foreground = 0xFFE0E0E0.toInt(),
            cursor = 0xFF00FF00.toInt(),
            ansi = intArrayOf(
                0xFF000000.toInt(), 0xFFCC0000.toInt(), 0xFF4E9A06.toInt(), 0xFFC4A000.toInt(), // 0-3
                0xFF3465A4.toInt(), 0xFF75507B.toInt(), 0xFF06989A.toInt(), 0xFFD3D7CF.toInt(), // 4-7
                0xFF555753.toInt(), 0xFFEF2929.toInt(), 0xFF8AE234.toInt(), 0xFFFCE94F.toInt(), // 8-11
                0xFF729FCF.toInt(), 0xFFAD7FA8.toInt(), 0xFF34E2E2.toInt(), 0xFFEEEEEC.toInt()  // 12-15
            )
        )
    }
}