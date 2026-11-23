package com.example.util

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream

/**
 * Shared writer for GeckoView "external response" downloads.
 *
 * Lives here (not in either host screen) because both GeckoView hosts need it and
 * they used to carry near-duplicate copies: the web-app copy was the one hardened
 * after the `Download/NJCrash` crash, the browser copy was not.
 *
 * Rules this implementation exists to enforce:
 *
 *  1. **Never overwrite.** A second download of the same name becomes
 *     `name (1).ext` rather than silently clobbering the earlier file.
 *  2. **Toast only on a Looper thread.** `autoDownloadExternal` runs on a bare
 *     `Thread`, and on API 30+ `Toast.makeText` throws
 *     `NullPointerException: Can't toast on a thread that has not called

import com.example.ui.components.AppIcons
 *     Looper.prepare()` from `Toast.getLooper()`. That uncaught throw killed the
 *     whole `:webapps` process, so the user saw "downloaded AND the web app
 *     closed" in one tap. Every user-facing message goes through [toastOnMain].
 *  3. **Catch Throwable, delete the partial file.** An OOM or a broken channel
 *     must not take the host process down.
 *  4. **Touch no Compose state**, so the save survives recomposition/teardown.
 */
fun autoDownloadExternal(context: Context, response: WebResponse, threadName: String = "nj-download") {
    val (baseName, mime) = geckoDownloadName(response)
    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    dir.mkdirs()
    val target = uniqueDownloadTarget(dir, baseName)

    val appContext = context.applicationContext
    val mainHandler = Handler(Looper.getMainLooper())
    fun toastOnMain(message: String) {
        mainHandler.post {
            runCatching {
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val body = response.body
    if (body == null) {
        toastOnMain("Download failed: empty response")
        return
    }
    Thread({
        try {
            FileOutputStream(target).use { out -> body.copyTo(out) }
            runCatching { body.close() }
            android.media.MediaScannerConnection.scanFile(
                appContext,
                arrayOf(target.absolutePath),
                arrayOf(mime.ifBlank { "*/*" }),
                null
            )
            toastOnMain("Saved ${target.name} to Download/")
        } catch (t: Throwable) {
            runCatching { body.close() }
            runCatching { target.delete() }
            toastOnMain("Download failed: ${t.message}")
        }
    }, threadName).start()
}

/** `name`, or `name (1)`, `name (2)`, … until nothing collides in [dir]. */
fun uniqueDownloadTarget(dir: File, baseName: String): File {
    var target = File(dir, baseName)
    if (!target.exists()) return target
    val dot = baseName.lastIndexOf('.')
    val stem = if (dot > 0) baseName.substring(0, dot) else baseName
    val ext = if (dot > 0) baseName.substring(dot) else ""
    var n = 1
    while (target.exists()) {
        target = File(dir, "${stem}_($n)$ext")
        n++
    }
    return target
}

/** Best-effort filename + mime for a Gecko download, honouring RFC 5987 `filename*`. */
fun geckoDownloadName(response: WebResponse): Pair<String, String> {
    val headers = response.headers
    // Blob/data downloads (WhatsApp documents, e.g. PDFs) have no HTTP headers;
    // GeckoView exposes their real name/type through these synthetic headers.
    val mime = (headers["webResponseInfo-contentType"] ?: headers["Content-Type"] ?: "")
        .substringBefore(";").trim()
    var name: String? = headers["webResponseInfo-filename"]?.takeIf { it.isNotBlank() }

    val cd = headers["Content-Disposition"]
    if (name == null && cd != null) {
        val star = Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(cd)
        if (star != null) {
            name = runCatching {
                java.net.URLDecoder.decode(star.groupValues[1].trim(), "UTF-8")
            }.getOrNull()
        }
        if (name.isNullOrBlank()) {
            val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)
            if (plain != null) name = plain.groupValues[1].trim()
        }
    }
    if (name.isNullOrBlank() && !response.uri.startsWith("blob:") && !response.uri.startsWith("data:")) {
        val path = response.uri.substringAfter("://").substringBefore("?").substringAfterLast("/")
        name = path.ifBlank { null }
    }
    var cleaned = (name ?: "").replace(Regex("[\\\\/:*?\"<>|]+"), "_").trim()
    if (cleaned.isBlank()) cleaned = "download_${System.currentTimeMillis()}"

    // A blob URI is just a UUID, and webResponseInfo-filename can be extension-less
    // (WhatsApp sends "document.pdf" but some paths only carry the MIME). Give the
    // saved file a real extension so a PDF is openable instead of a nameless blob.
    val existingExt = cleaned.substringAfterLast('.', "")
    if (!(existingExt.isNotBlank() && existingExt.length <= 6 && existingExt.all { it.isLetterOrDigit() })) {
        val ext = extensionForMime(mime)
        if (ext != null && !cleaned.endsWith(".$ext")) cleaned = "$cleaned.$ext"
    }
    return cleaned to mime
}

private fun extensionForMime(mime: String): String? {
    if (mime.isBlank()) return null
    val mapped = runCatching {
        android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
    }.getOrNull()
    if (!mapped.isNullOrBlank()) return mapped
    return when (mime.lowercase()) {
        "application/pdf" -> "pdf"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
        "text/comma-separated-values" -> "csv"
        "application/epub+zip" -> "epub"
        "application/zip" -> "zip"
        else -> null
    }
}