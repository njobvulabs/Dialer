package com.example.util

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File

object ScreenRecorderStore {
    const val RELATIVE_PATH = "Movies/NJ-Dialer/Screen Recordings"

    fun dir(context: Context): File {
        val fallback = File(context.filesDir, "Screen Recordings")
        return runCatching {
            val target = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "NJ-Dialer/Screen Recordings"
            )
            if (target.exists() || target.mkdirs()) target else fallback.also { runCatching { it.mkdirs() } }
        }.getOrDefault(fallback.also { runCatching { it.mkdirs() } })
    }

    fun list(context: Context): List<File> =
        dir(context).listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun open(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Open recording").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun delete(context: Context, file: File): Boolean =
        runCatching { RecycleBin.trash(context, file) != null }.getOrDefault(false)
}
