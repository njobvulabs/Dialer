package com.example.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

data class SavedDownload(
    val uri: Uri?,
    val file: File?,
    val displayPath: String
)

/**
 * Saves user-visible text exports under Download/NJ-Dialer/<folder>/.
 *
 * API 29+ uses MediaStore.Downloads so no storage permission is needed and the
 * file is indexed immediately. API 26-28 writes the public Downloads directory
 * directly (WRITE_EXTERNAL_STORAGE is required) and MediaScanner indexes it.
 */
object PublicDownloads {

    /** Writes a binary export using the same scoped-storage path as text exports. */
    fun saveBytes(context: Context, folder: String, fileName: String, bytes: ByteArray, mimeType: String): SavedDownload {
        val safeFolder = folder.trim('/').ifBlank { "Exports" }
        val safeName = fileName.substringAfterLast('/').ifBlank { "export_${System.currentTimeMillis()}" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/NJ-Dialer/$safeFolder"
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: throw IOException("Could not create the export file")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("Could not open the export file for writing")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return SavedDownload(uri, null, "$relativePath/$safeName")
            } catch (t: Throwable) { runCatching { resolver.delete(uri, null, null) }; throw t }
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!granted) throw IOException("Storage permission is needed to save to Downloads")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NJ-Dialer/$safeFolder")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create the export folder")
        val target = uniqueFile(dir, safeName)
        target.writeBytes(bytes)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
        return SavedDownload(null, target, "Download/NJ-Dialer/$safeFolder/${target.name}")
    }

    fun saveText(
        context: Context,
        folder: String,
        fileName: String,
        text: String,
        mimeType: String = "text/plain"
    ): SavedDownload {
        val safeFolder = folder.trim('/').ifBlank { "Notes" }
        val safeName = fileName.substringAfterLast('/').ifBlank {
            "export_${System.currentTimeMillis()}.txt"
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, safeFolder, safeName, text, mimeType)
        } else {
            saveLegacy(context, safeFolder, safeName, text, mimeType)
        }
    }

    private fun saveWithMediaStore(
        context: Context,
        folder: String,
        fileName: String,
        text: String,
        mimeType: String
    ): SavedDownload {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/NJ-Dialer/$folder"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Could not create the export file")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("Could not open the export file for writing")

            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }

        val actualName = resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: fileName

        return SavedDownload(
            uri = uri,
            file = null,
            displayPath = "$relativePath/$actualName"
        )
    }

    private fun saveLegacy(
        context: Context,
        folder: String,
        fileName: String,
        text: String,
        mimeType: String
    ): SavedDownload {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            throw IOException("Storage permission is needed to save to Downloads")
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "NJ-Dialer/$folder"
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Could not create the export folder")
        }
        val target = uniqueFile(dir, fileName)
        target.writeText(text, Charsets.UTF_8)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(mimeType),
            null
        )
        return SavedDownload(
            uri = null,
            file = target,
            displayPath = "Download/NJ-Dialer/$folder/${target.name}"
        )
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "${stem}_($n)$ext")
            n++
        }
        return candidate
    }
}
