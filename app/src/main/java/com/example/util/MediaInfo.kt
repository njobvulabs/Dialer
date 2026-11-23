package com.example.util

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

data class MediaInfo(
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val videoMime: String?,
    val audioMime: String?,
    val videoTrackIndex: Int,
    val audioTrackIndex: Int,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationUs: Long,
    val frameRate: Int,
    val audioSampleRate: Int,
    val audioChannels: Int,
    val videoBitrate: Int,
    val unsupportedReason: String?
)

object MediaProbe {


    private val unsupportedContainers = setOf(
        "video/x-matroska",
        "video/x-msvideo",
        "video/x-flv",
        "video/x-ms-asf",
        "video/x-ms-wmv",
        "video/webm"
    )

    private val unsupportedExtensions = setOf("mkv", "avi", "flv", "wmv", "webm")

    fun probe(path: String): MediaInfo {
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return MediaInfo(
                hasVideo = false, hasAudio = false, videoMime = null, audioMime = null,
                videoTrackIndex = -1, audioTrackIndex = -1, width = 0, height = 0,
                rotation = 0, durationUs = 0L, frameRate = 0,
                audioSampleRate = 0, audioChannels = 0, videoBitrate = 0,
                unsupportedReason = "File not found"
            )
        }

        val ext = file.extension.lowercase()
        var videoMime: String? = null
        var audioMime: String? = null
        var videoTrack = -1
        var audioTrack = -1
        var width = 0
        var height = 0
        var rotation = 0
        var durationUs = 0L
        var frameRate = 0
        var sampleRate = 0
        var channels = 0
        var videoBitrate = 0

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            val trackCount = extractor.trackCount
            for (i in 0 until trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrack < 0) {
                    videoTrack = i
                    videoMime = mime
                    width = if (fmt.containsKey(MediaFormat.KEY_WIDTH)) fmt.getInteger(MediaFormat.KEY_WIDTH) else 0
                    height = if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) fmt.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
                    frameRate = if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) fmt.getInteger(MediaFormat.KEY_FRAME_RATE) else 0
                    videoBitrate = if (fmt.containsKey(MediaFormat.KEY_BIT_RATE)) fmt.getInteger(MediaFormat.KEY_BIT_RATE) else 0
                    if (fmt.containsKey(MediaFormat.KEY_DURATION)) durationUs = fmt.getLong(MediaFormat.KEY_DURATION)
                } else if (mime.startsWith("audio/") && audioTrack < 0) {
                    audioTrack = i
                    audioMime = mime
                    sampleRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                    channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
                    if (fmt.containsKey(MediaFormat.KEY_DURATION)) durationUs = fmt.getLong(MediaFormat.KEY_DURATION)
                }
            }
        } catch (e: Exception) {
            return MediaInfo(
                hasVideo = false, hasAudio = false, videoMime = null, audioMime = null,
                videoTrackIndex = -1, audioTrackIndex = -1, width = 0, height = 0,
                rotation = 0, durationUs = 0L, frameRate = 0,
                audioSampleRate = 0, audioChannels = 0, videoBitrate = 0,
                unsupportedReason = "Cannot read this file: ${e.message}"
            )
        } finally {
            runCatching { extractor.release() }
        }

        if (videoTrack < 0 && audioTrack < 0) {
            return MediaInfo(
                hasVideo = false, hasAudio = false, videoMime = videoMime, audioMime = audioMime,
                videoTrackIndex = -1, audioTrackIndex = -1, width = width, height = height,
                rotation = rotation, durationUs = durationUs, frameRate = frameRate,
                audioSampleRate = sampleRate, audioChannels = channels, videoBitrate = videoBitrate,
                unsupportedReason = "No playable video or audio track found"
            )
        }

        val containerBlocked = videoMime?.let { unsupportedContainers.contains(it) } == true
        val extBlocked = ext in unsupportedExtensions
        if (containerBlocked || extBlocked) {
            val reason = when {
                videoMime == "video/x-matroska" -> "MKV (.mkv) is not supported on this device"
                videoMime == "video/x-msvideo" -> "AVI (.avi) is not supported on this device"
                videoMime == "video/x-flv" -> "FLV (.flv) is not supported on this device"
                videoMime == "video/x-ms-asf" || videoMime == "video/x-ms-wmv" -> "WMV (.wmv) is not supported on this device"
                videoMime == "video/webm" -> "WEBM (.webm) is not supported on this device"
                ext == "mkv" -> "MKV (.mkv) is not supported on this device"
                ext == "avi" -> "AVI (.avi) is not supported on this device"
                ext == "flv" -> "FLV (.flv) is not supported on this device"
                ext == "wmv" -> "WMV (.wmv) is not supported on this device"
                else -> "WEBM (.webm) is not supported on this device"
            }
            return MediaInfo(
                hasVideo = videoTrack >= 0, hasAudio = audioTrack >= 0,
                videoMime = videoMime, audioMime = audioMime,
                videoTrackIndex = videoTrack, audioTrackIndex = audioTrack,
                width = width, height = height, rotation = rotation, durationUs = durationUs,
                frameRate = frameRate, audioSampleRate = sampleRate, audioChannels = channels,
                videoBitrate = videoBitrate, unsupportedReason = reason
            )
        }

        return MediaInfo(
            hasVideo = videoTrack >= 0,
            hasAudio = audioTrack >= 0,
            videoMime = videoMime,
            audioMime = audioMime,
            videoTrackIndex = videoTrack,
            audioTrackIndex = audioTrack,
            width = width,
            height = height,
            rotation = rotation,
            durationUs = durationUs,
            frameRate = frameRate,
            audioSampleRate = sampleRate,
            audioChannels = channels,
            videoBitrate = videoBitrate,
            unsupportedReason = null
        )
    }
}
