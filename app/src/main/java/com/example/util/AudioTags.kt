package com.example.util

import android.media.MediaMetadataRetriever

/**
 * Small, bounded cache of audio tags for list rows.
 *
 * Reading tags opens the file, which is far too expensive to do on the main
 * thread for every recomposition. Rows call [get] from an IO dispatcher and the
 * result is memoised here; malformed files return null rather than throwing.
 */
data class AudioTagInfo(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long
)

object AudioTags {
    private const val MAX_ENTRIES = 400
    private val cache = object : LinkedHashMap<String, AudioTagInfo?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AudioTagInfo?>?): Boolean =
            size > MAX_ENTRIES
    }

    fun cached(path: String): AudioTagInfo? = synchronized(cache) { cache[path] }

    fun get(path: String): AudioTagInfo? {
        synchronized(cache) { if (cache.containsKey(path)) return cache[path] }
        val info = runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(path)
                AudioTagInfo(
                    title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.takeIf { it.isNotBlank() },
                    artist = (
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                        )?.takeIf { it.isNotBlank() },
                    album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                        ?.takeIf { it.isNotBlank() },
                    durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                )
            } finally {
                runCatching { mmr.release() }
            }
        }.getOrNull()
        synchronized(cache) { cache[path] = info }
        return info
    }

    fun clear() = synchronized(cache) { cache.clear() }
}
