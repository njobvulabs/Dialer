package com.example.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** A picked file copied to cache, keeping its original name + MIME metadata.
 *  [file] must be served to Gecko via FileProvider content URIs so the name
 *  and MIME derived from the content URI match what the user picked. */
data class PickedUpload(val file: File, val displayName: String, val mimeType: String?)

/**
 * Copies a content:// URI from the system file picker to a temporary cache file,
 * PRESERVING the original display name + extension (and thus the MIME the
 * FileProvider resolves from it). Returns metadata so the caller can hand the
 * GeckoView FilePrompt a FileProvider content URI.
 */
fun pickFileToCache(context: Context, uri: Uri): PickedUpload? {
    return try {
        val resolver = context.contentResolver
        val input = resolver.openInputStream(uri) ?: return null
        val displayName = resolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null

        }
        val base = sanitizeUploadName(displayName, context, uri)
        val dir = File(context.cacheDir, "uploads")
        dir.mkdirs()
        var f = File(dir, base)
        var n = 1
        while (f.exists()) {
            val dot = base.lastIndexOf('.')
            f = if (dot > 0) {
                File(dir, base.substring(0, dot) + "_$n" + base.substring(dot))
            } else {
                File(dir, base + "_$n")
            }
            n++
            if (n > 100) return null
        }
        f.outputStream().use { out -> input.copyTo(out) }
        input.close()
        // FileProvider resolves MIME from the file's EXTENSION, and GeckoView
        // drops files whose resolved type doesn't match what the page accepts.
        // If the source provider reports a concrete type but the cached name
        // wouldn't resolve to it (missing/odd extension => octet-stream), append
        // the proper extension so the content URI's type matches the source.
        val srcMime = resolver.getType(uri)
        val extResolvesWell = f.extension.isNotBlank() &&
            (guessUploadMime(f.extension.lowercase())?.equals(srcMime, ignoreCase = true) == true)
        if (srcMime != null && srcMime != "application/octet-stream" && !extResolvesWell) {
            val wantExt = mimeToExtension(srcMime)
            if (wantExt != null && wantExt.isNotBlank() &&
                f.extension.lowercase() != wantExt.lowercase()
            ) {
                val target = File(f.parentFile, f.name + "." + wantExt)
                if (!target.exists() && f.renameTo(target)) f = target
            }
        }
        PickedUpload(
            file = f,
            displayName = f.name,
            mimeType = srcMime
                ?: guessUploadMime(f.extension)
        )
    } catch (t: Throwable) {
        // Surface the real reason instead of silently dropping the pick (the
        // old swallow-all behavior made "picker closes, nothing attaches").
        android.util.Log.e("pickFileToCache", "copy failed uri=$uri", t)
        null
    }
}

private fun sanitizeUploadName(
    displayName: String?,
    context: Context,
    uri: Uri
): String {
    var name = displayName
        ?.replace(Regex("[\\\\/:*?\"<>|\u0000-\u001f]+"), "_")
        ?.trim()
        .orEmpty()
    if (name.isBlank() || name == "." || name == "..") {
        val ext = guessUploadExtension(context, uri)
        name = "upload_${System.currentTimeMillis()}.$ext"
    }
    return name
}

private fun guessUploadExtension(context: Context, uri: Uri): String {
    val mime = context.contentResolver.getType(uri)
    if (mime != null) {
        val subtype = mime.substringAfter("/", "")
        if (subtype.isNotBlank() && subtype != "*") return subtype
    }
    val byMap = mapOf(
        "text/vcard" to "vcf",
        "text/x-vcard" to "vcf",
        "text/csv" to "csv",
        "text/comma-separated-values" to "csv",
        "application/json" to "json",
        "application/backup" to "json",
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "video/mp4" to "mp4",
        "video/webm" to "webm",
        "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a",
    )
    byMap[mime]?.let { return it }
    return "bin"
}

private fun guessUploadMime(ext: String): String? {
    if (ext.isBlank()) return null
    return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
}

/** Extension for a concrete MIME, preferring common office/vcard overrides
 *  before the platform MimeTypeMap. Returns null for wildcard/unknown types. */
private fun mimeToExtension(mime: String): String? {
    if (mime.isBlank() || mime.endsWith("/*") || mime.endsWith("/")) return null
    val overrides = mapOf(
        "text/vcard" to "vcf",
        "text/x-vcard" to "vcf",
        "text/csv" to "csv",
        "text/comma-separated-values" to "csv",
        "application/json" to "json",
        "application/backup" to "json",
        "audio/x-m4a" to "m4a",
        "audio/mp4" to "m4a",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.oasis.opendocument.text" to "odt",
        "application/vnd.oasis.opendocument.spreadsheet" to "ods",
    )
    overrides[mime]?.let { return it }
    return runCatching {
        android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
    }.getOrNull()
}

/**
 * Copies a content:// URI from the system file picker to a temporary cache file.
 * Returns the File so downstream code can keep using filesystem paths.
 * The temp file is cleaned up on app restart (stored in cacheDir).
 */
fun copyUriToCache(context: Context, uri: Uri, suffix: String = ""): File? {
    return runCatching {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val ext = if (suffix.isNotEmpty()) ".$suffix" else guessExtension(context, uri)
        val tmp = File(context.cacheDir, "picker_${System.currentTimeMillis()}$ext")
        tmp.outputStream().use { out -> input.copyTo(out) }
        input.close()
        tmp
    }.getOrNull()
}

private fun guessExtension(context: Context, uri: Uri): String {
    // Try to get extension from content type
    val mimeType = context.contentResolver.getType(uri)
    if (mimeType != null) {
        val map = mapOf(
            "text/vcard" to "vcf",
            "text/x-vcard" to "vcf",
            "text/csv" to "csv",
            "text/comma-separated-values" to "csv",
            "application/json" to "json",
            "application/backup" to "json",
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "image/gif" to "gif",
            "video/mp4" to "mp4",
            "video/webm" to "webm",
            "audio/mpeg" to "mp3",
            "audio/mp4" to "m4a",
        )
        map[mimeType]?.let { return it }
        // Fallback: use subtype from mime
        val subtype = mimeType.substringAfter("/", "")
        if (subtype.isNotBlank() && subtype != "*") return subtype
    }
    // Try to get extension from display name
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIdx >= 0 && cursor.moveToFirst()) {
            val name = cursor.getString(nameIdx)
            val dot = name.lastIndexOf('.')
            if (dot >= 0) return name.substring(dot + 1)
        }
    }
    return "tmp"
}
