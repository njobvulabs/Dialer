package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DownloadLocationsTest {

    @Test
    fun loadFrom_aggregatesSourcesAndSortsNewestFirst() {
        val root = Files.createTempDirectory("nj-downloads").toFile()
        try {
            val notesDir = File(root, "notes").apply { mkdirs() }
            val videoDir = File(root, "video").apply { mkdirs() }

            val older = File(notesDir, "older.txt").apply {
                writeText("note")
                setLastModified(1_000L)
            }
            val newer = File(videoDir, "newer.mp4").apply {
                writeText("video")
                setLastModified(2_000L)
            }

            val entries = DownloadLocations.loadFrom(
                listOf(
                    DownloadRoot(notesDir, DownloadSource.NOTES),
                    DownloadRoot(videoDir, DownloadSource.VIDEOS)
                )
            )

            assertEquals(listOf(newer.absolutePath, older.absolutePath), entries.map { it.file.absolutePath })
            assertEquals(
                listOf(DownloadSource.VIDEOS, DownloadSource.NOTES),
                entries.map { it.source }
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun loadFrom_skipsHiddenTemporaryAndDuplicateFiles() {
        val root = Files.createTempDirectory("nj-downloads").toFile()
        try {
            val downloads = File(root, "downloads").apply { mkdirs() }
            File(downloads, "keep.txt").writeText("keep")
            File(downloads, ".hidden").writeText("hidden")
            File(downloads, "movie.mp4.part").writeText("partial")
            File(downloads, "archive.njpart").writeText("partial")
            File(downloads, "temp.tmp").writeText("partial")

            val entries = DownloadLocations.loadFrom(
                listOf(
                    DownloadRoot(downloads, DownloadSource.DOWNLOADS),
                    DownloadRoot(downloads, DownloadSource.DOWNLOADS)
                )
            )

            assertEquals(1, entries.size)
            assertEquals("keep.txt", entries.single().file.name)
            assertEquals(DownloadSource.DOWNLOADS, entries.single().source)
            assertTrue(entries.none { it.file.name.startsWith(".") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun loadFrom_isEmptyWhenDirectoriesDoNotExist() {
        val root = Files.createTempDirectory("nj-downloads").toFile()
        try {
            val missing = File(root, "missing")
            val entries = DownloadLocations.loadFrom(
                listOf(DownloadRoot(missing, DownloadSource.DOWNLOADS))
            )

            assertTrue(entries.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
