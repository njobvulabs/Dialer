package com.example.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticsExport {
    fun exportAndShare(context: Context) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val outDir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val zipFile = File(outDir, "nj-dialer-diagnostics-$stamp.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry("device-info.txt"))
            zip.write(buildString {
                appendLine("App: ${context.packageName}")
                val pi = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
                appendLine("Version: ${pi?.versionName ?: "?"} (${if (android.os.Build.VERSION.SDK_INT >= 28) pi?.longVersionCode ?: 0L else (pi?.versionCode ?: 0).toLong()})")
                appendLine("Build type: ${com.example.BuildConfig.BUILD_TYPE}")
                appendLine("SDK: ${Build.VERSION.SDK_INT}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("ROM: ${Build.DISPLAY}")
                appendLine("Time: ${Date()}")
            }.toByteArray())
            zip.closeEntry()
            val crashDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "NJCrash")
            crashDir.listFiles()?.filter { it.isFile && it.length() < 2_000_000L }?.sortedByDescending { it.lastModified() }?.take(20)?.forEach { f ->
                runCatching {
                    zip.putNextEntry(ZipEntry("NJCrash/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "NJ-Dialer diagnostics")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
