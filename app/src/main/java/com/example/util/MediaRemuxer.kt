package com.example.util

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object MediaRemuxer {
    fun mux(video: File, audio: File?, output: File) {
        if (output.exists()) runCatching { output.delete() }
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoExtractor = MediaExtractor()
        var audioExtractor: MediaExtractor? = null
        try {
            videoExtractor.setDataSource(video.absolutePath)
            val videoTrack = firstTrack(videoExtractor, "video/")
                ?: error("Recorded video has no video track")
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val videoIndex = muxer.addTrack(videoFormat)

            var audioIndex = -1
            if (audio != null && audio.exists() && audio.length() > 0) {
                audioExtractor = MediaExtractor()
                audioExtractor.setDataSource(audio.absolutePath)
                firstTrack(audioExtractor, "audio/")?.let { track ->
                    audioIndex = muxer.addTrack(audioExtractor.getTrackFormat(track))
                }
            }

            muxer.start()
            copyTrack(videoExtractor, videoTrack, muxer, videoIndex)
            audioExtractor?.let { extractor ->
                if (audioIndex >= 0) {
                    firstTrack(extractor, "audio/")?.let { track -> copyTrack(extractor, track, muxer, audioIndex) }
                }
            }
        } finally {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { videoExtractor.release() }
            audioExtractor?.let { runCatching { it.release() } }
        }
    }

    private fun firstTrack(extractor: MediaExtractor, prefix: String): Int? =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
        }

    private fun copyTrack(
        extractor: MediaExtractor,
        track: Int,
        muxer: MediaMuxer,
        outputTrack: Int
    ) {
        extractor.selectTrack(track)
        val buffer = ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.nativeOrder())
        val info = android.media.MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            info.size = extractor.readSampleData(buffer, 0)
            if (info.size < 0) break
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outputTrack, buffer, info)
            if (!extractor.advance()) break
        }
        extractor.unselectTrack(track)
    }
}
