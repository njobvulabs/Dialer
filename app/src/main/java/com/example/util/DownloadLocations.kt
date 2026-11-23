package com.example.util

import android.os.Environment
import java.io.File

enum class DownloadSource(val label: String) {
    DOWNLOADS("Downloads"),
    NOTES("Notes export"),
    TASKS("Tasks export"),
    VIDEOS("Video download"),
    AUDIO("Audio download"),
    IMAGES("Image download")
}

data class DownloadRoot(val directory: File, val source: DownloadSource)

data class DownloadEntry(val file: File, val source: DownloadSource)

/**
 * Fixed, non-recursive list of the directories this app writes downloads and
 * exports into.
 *
 * A full storage walk is deliberately avoided: this screen only needs the files
 * the app itself owns, and a recursive scan on a low-RAM phone is expensive.
 */
object DownloadLocations {

    private val TEMP_SUFFIXES = listOf(
        ".part",
        ".crdownload",
        ".tmp",
        ".njpart",
        ".download"
    )

    fun roots(): List<DownloadRoot> {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        return listOf(
            DownloadRoot(downloads, DownloadSource.DOWNLOADS),
            DownloadRoot(File(downloads, "NJ-Dialer/Notes"), DownloadSource.NOTES),
            DownloadRoot(File(downloads, "NJ-Dialer/Tasks"), DownloadSource.TASKS),
            DownloadRoot(File(pictures, "NJ-Dialer"), DownloadSource.IMAGES),
            DownloadRoot(File(movies, "NJ-Dialer"), DownloadSource.VIDEOS),
            DownloadRoot(File(music, "NJ-Dialer Audio"), DownloadSource.AUDIO)
        )
    }

    fun load(): List<DownloadEntry> = loadFrom(roots())

    fun loadFrom(roots: List<DownloadRoot>): List<DownloadEntry> {
        val seen = HashSet<String>()
        val entries = ArrayList<DownloadEntry>()
        roots.forEach { root ->
            val files = runCatching { root.directory.listFiles() }.getOrNull() ?: return@forEach
            files.forEach { file ->
                if (!file.isFile || !isVisibleDownload(file)) return@forEach
                val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                if (seen.add(key)) {
                    entries += DownloadEntry(file, root.source)
                }
            }
        }
        return entries.sortedWith(
            compareByDescending<DownloadEntry> { it.file.lastModified() }
                .thenBy { it.file.name.lowercase() }
        )
    }

    private fun isVisibleDownload(file: File): Boolean {
        if (file.name.startsWith(".")) return false
        val name = file.name.lowercase()
        return TEMP_SUFFIXES.none { name.endsWith(it) }
    }
}
