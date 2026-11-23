package com.example.util

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File

object AssistantCameraStore {
    fun dir(context: Context): File {
        val fallback = File(context.filesDir, "Camera")
        return runCatching {
            val publicDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES
            )
            val dir = File(publicDir, "NJ-Dialer/Camera")
            if (dir.exists() || dir.mkdirs()) dir else fallback.also { runCatching { it.mkdirs() } }
        }.getOrElse {
            fallback.also { runCatching { it.mkdirs() } }
        }
    }

    fun newPhotoFile(context: Context): File = runCatching {
        File(dir(context), "IMG_${System.currentTimeMillis()}.jpg")
    }.getOrDefault(File(context.cacheDir, "IMG_${System.currentTimeMillis()}.jpg"))

    fun scan(context: Context, file: File) {
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        }
    }
}
