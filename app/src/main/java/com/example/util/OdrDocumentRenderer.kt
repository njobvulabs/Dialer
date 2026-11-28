package com.example.util

import android.content.Context
import android.net.Uri
import app.opendocument.core.Html
import app.opendocument.core.HtmlConfig
import app.opendocument.core.Odr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object OdrDocumentRenderer {
    const val VERSION = "7.8.0"

    val PRIMARY_EXTENSIONS = setOf(
        "doc",
        "ppt",
        "xls",
        "odt",
        "ods",
        "odp",
        "rtf",
        "csv"
    )

    val FALLBACK_EXTENSIONS = setOf(
        "pdf",
        "docx",
        "pptx",
        "xlsx"
    )

    val SUPPORTED_EXTENSIONS = PRIMARY_EXTENSIONS + FALLBACK_EXTENSIONS

    private const val MAX_CACHE_ENTRIES = 6
    private const val MAX_SOURCE_BYTES = 512L * 1024L * 1024L

    data class RenderedDocument(
        val url: String,
        val pageName: String?,
        val root: File
    ) {
        fun cleanup() {
            root.deleteRecursively()
        }
    }

    suspend fun render(context: Context, file: File): RenderedDocument = withContext(Dispatchers.IO) {
        require(file.isFile) { "Document not found" }
        require(file.length() <= MAX_SOURCE_BYTES) { "Document is too large to render" }

        val cacheRoot = File(context.cacheDir, "odr_documents")
        require(cacheRoot.isDirectory || cacheRoot.mkdirs()) { "Could not create ODR cache" }
        prune(cacheRoot)

        val root = File(cacheRoot, UUID.randomUUID().toString())
        val inputDir = File(root, "input")
        val outputDir = File(root, "output")
        require(inputDir.mkdirs() && outputDir.mkdirs()) { "Could not create ODR work directory" }

        val staged = File(inputDir, safeName(file))
        file.inputStream().use { input ->
            staged.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        var completed = false
        try {
            val config = HtmlConfig()
            config.embedImages = true
            config.embedShippedResources = true
            config.relativeResourcePaths = true
            config.allowExternalContent = false
            config.lazyLayout = false
            config.lazyLoading = false

            val renderedPage = Odr.open(staged.absolutePath).use { decoded ->
                Html.translate(decoded, config).use { service ->
                    val html = service.bringOffline(outputDir.absolutePath)
                    val page = html.pages().firstOrNull {
                        val candidate = resolveCandidate(outputDir, it.path)
                        isInside(outputDir, candidate) && candidate.isFile
                    }
                    val pageFile = resolvePage(outputDir, page?.path, config.documentOutputFileName)
                    Pair(page?.name, Uri.fromFile(pageFile).toString())
                }
            }

            completed = true
            RenderedDocument(
                url = renderedPage.second,
                pageName = renderedPage.first,
                root = root
            )
        } finally {
            if (!completed) {
                root.deleteRecursively()
            }
        }
    }

    fun supports(extension: String): Boolean {
        return extension.lowercase() in SUPPORTED_EXTENSIONS
    }

    private fun prune(cacheRoot: File) {
        val directories = cacheRoot.listFiles { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: return
        directories.drop(MAX_CACHE_ENTRIES).forEach { it.deleteRecursively() }
    }

    private fun safeName(file: File): String {
        val base = file.nameWithoutExtension
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .ifBlank { "document" }
        val extension = file.extension.lowercase()
        return if (extension.isBlank()) base else "$base.$extension"
    }

    private fun resolvePage(outputDir: File, pagePath: String?, defaultName: String): File {
        val candidates = listOfNotNull(
            pagePath?.takeIf { it.isNotBlank() }?.let { resolveCandidate(outputDir, it) },
            File(outputDir, defaultName)
        )
        return candidates.firstOrNull { isInside(outputDir, it) && it.isFile }
            ?: outputDir.walkTopDown().firstOrNull { isInside(outputDir, it) && it.isFile && it.extension.equals("html", ignoreCase = true) }
            ?: throw IllegalStateException("OpenDocument produced no HTML page")
    }

    private fun resolveCandidate(outputDir: File, path: String): File {
        val direct = File(path)
        return if (direct.isAbsolute) direct else File(outputDir, path)
    }

    private fun isInside(root: File, file: File): Boolean {
        val rootPath = root.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        return filePath.startsWith(rootPath)
    }
}
