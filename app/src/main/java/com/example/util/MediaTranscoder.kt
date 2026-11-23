package com.example.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min

class ConverterCancelledException : Exception("Conversion cancelled")

object MediaTranscoder {

    class VideoOptions(
        val targetWidth: Int? = null,
        val targetHeight: Int? = null,
        val videoBitrate: Int? = null,
        val frameRate: Int? = null,
        val audioBitrate: Int = 128_000,
        val includeAudio: Boolean = true
    )

    fun extractAudio(

        inputPath: String,
        outputPath: String,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val info = validate(inputPath, needAudio = true, needVideo = false)
        val mime = info.audioMime
        if (mime == "audio/mp4a-latm") {
            copyAudioPassthrough(inputPath, info.audioTrackIndex, outputPath, progress, isCancelled)
        } else {
            transcodeAudioToM4a(inputPath, info.audioTrackIndex, outputPath, 192_000, progress, isCancelled)
        }
    }

    fun transcodeAudio(
        inputPath: String,
        outputPath: String,
        bitrate: Int,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val info = validate(inputPath, needAudio = true, needVideo = false)
        transcodeAudioToM4a(inputPath, info.audioTrackIndex, outputPath, bitrate, progress, isCancelled)
    }

    fun transcodeVideo(
        inputPath: String,
        outputPath: String,
        opts: VideoOptions,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val info = validate(inputPath, needAudio = false, needVideo = true)
        val base = outputPath.substringBeforeLast(".")
        val tmpVideo = "$base.tmp_video.mp4"
        val tmpAudio = "$base.tmp_audio.m4a"
        File(tmpVideo).delete()
        File(tmpAudio).delete()
        try {
            transcodeVideoTrack(
                inputPath, info.videoTrackIndex, tmpVideo, opts,
                { p -> progress(p * 0.8f) }, isCancelled
            )
            var audioUsed = false
            if (opts.includeAudio && info.hasAudio) {
                transcodeAudioToM4a(
                    inputPath, info.audioTrackIndex, tmpAudio, opts.audioBitrate,
                    { p -> progress(0.8f + p * 0.1f) }, isCancelled
                )
                audioUsed = true
            }
            mergeIntoMp4(
                tmpVideo, if (audioUsed) tmpAudio else null, outputPath,
                { p -> progress(0.9f + p * 0.1f) }, isCancelled
            )
            progress(1f)
        } finally {
            File(tmpVideo).delete()
            File(tmpAudio).delete()
        }
    }

    fun remuxToMp4(
        inputPath: String,
        outputPath: String,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val info = validate(inputPath, needAudio = false, needVideo = true)
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(inputPath)
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vMux = if (info.videoTrackIndex >= 0) muxer.addTrack(extractor.getTrackFormat(info.videoTrackIndex)) else -1
            val aMux = if (info.audioTrackIndex >= 0) muxer.addTrack(extractor.getTrackFormat(info.audioTrackIndex)) else -1
            muxer.start()
            val duration = info.durationUs
            if (vMux >= 0) {
                extractor.selectTrack(info.videoTrackIndex)
                copySamples(extractor, muxer, vMux, duration) { p -> progress(p * 0.8f) }
            }
            if (aMux >= 0) {
                extractor.selectTrack(info.audioTrackIndex)
                copySamples(extractor, muxer, aMux, duration) { p -> progress(0.8f + p * 0.2f) }
            }
            progress(1f)
        } catch (e: Exception) {
            runCatching { muxer?.stop() }
            throw e
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun validate(inputPath: String, needAudio: Boolean, needVideo: Boolean): MediaInfo {
        val info = MediaProbe.probe(inputPath)
        if (info.unsupportedReason != null) throw UnsupportedOperationException(info.unsupportedReason)
        if (needAudio && !info.hasAudio) throw UnsupportedOperationException("This file has no audio track")
        if (needVideo && !info.hasVideo) throw UnsupportedOperationException("This file has no video track")
        return info
    }

    private fun copyAudioPassthrough(
        srcPath: String,
        trackIndex: Int,
        outPath: String,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(srcPath)
            extractor.selectTrack(trackIndex)
            val fmt = extractor.getTrackFormat(trackIndex)
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val mux = muxer.addTrack(fmt)
            muxer.start()
            copySamples(extractor, muxer, mux, durationUs, progress)
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun transcodeAudioToM4a(
        srcPath: String,
        trackIndex: Int,
        outPath: String,
        bitrate: Int,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(srcPath)
            extractor.selectTrack(trackIndex)
            val srcFmt = extractor.getTrackFormat(trackIndex)
            val mime = srcFmt.getString(MediaFormat.KEY_MIME) ?: throw UnsupportedOperationException("Unknown audio codec")
            val sampleRate = if (srcFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (srcFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) srcFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            val durationUs = srcFmt.getLong(MediaFormat.KEY_DURATION)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(srcFmt, null, null, 0)
            decoder.start()

            val encFmt = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels)
            encFmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            encFmt.setInteger(MediaFormat.KEY_AAC_PROFILE, 2)
            encFmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
            encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxState = MuxState()

            var decoderInputEOS = false
            var decoderOutputEOS = false
            var encoderInputEOS = false
            var encoderOutputEOS = false

            val bufInfo = MediaCodec.BufferInfo()

            while (!encoderOutputEOS) {
                checkCancelled(isCancelled)
                if (!decoderInputEOS) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decoderInputEOS = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                            if (durationUs > 0) progress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                    }
                }
                if (!decoderOutputEOS) {
                    val outIdx = decoder.dequeueOutputBuffer(bufInfo, 0)
                    if (outIdx >= 0) {
                        val buf = decoder.getOutputBuffer(outIdx)!!
                        val eos = (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (bufInfo.size > 0) {
                            feedPcmToEncoder(encoder, buf, bufInfo.offset, bufInfo.size, bufInfo.presentationTimeUs, isCancelled)
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (eos) decoderOutputEOS = true
                    }
                }
                if (decoderOutputEOS && !encoderInputEOS) {
                    val inIdx = encoder.dequeueInputBuffer(0)
                    if (inIdx >= 0) {
                        encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encoderInputEOS = true
                    }
                }
                if (drainEncoderOutput(encoder, muxer, bufInfo, muxState)) encoderOutputEOS = true
                if (!encoderOutputEOS && !muxState.ready) {
                    Thread.sleep(2)
                }
            }
        } finally {
            runCatching { encoder?.signalEndOfInputStream() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun feedPcmToEncoder(
        encoder: MediaCodec,
        src: ByteBuffer,
        offset: Int,
        size: Int,
        ptsUs: Long,
        isCancelled: () -> Boolean
    ) {
        var copied = 0
        while (copied < size) {
            checkCancelled(isCancelled)
            val inIdx = encoder.dequeueInputBuffer(10_000)
            if (inIdx < 0) continue
            val inBuf = encoder.getInputBuffer(inIdx)!!
            inBuf.clear()
            val chunk = min(size - copied, inBuf.remaining())
            val slice = src.duplicate()
            slice.position(offset + copied)
            slice.limit(offset + copied + chunk)
            inBuf.put(slice)
            encoder.queueInputBuffer(inIdx, 0, chunk, ptsUs, 0)
            copied += chunk
        }
    }

    private fun transcodeVideoTrack(
        srcPath: String,
        trackIndex: Int,
        outPath: String,
        opts: VideoOptions,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val extractor = MediaExtractor()
        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var surface: Surface? = null
        try {
            extractor.setDataSource(srcPath)
            extractor.selectTrack(trackIndex)
            val srcFmt = extractor.getTrackFormat(trackIndex)
            val vMime = srcFmt.getString(MediaFormat.KEY_MIME) ?: throw UnsupportedOperationException("Unknown video codec")
            val srcW = srcFmt.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = srcFmt.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (srcFmt.containsKey(MediaFormat.KEY_ROTATION)) srcFmt.getInteger(MediaFormat.KEY_ROTATION) else 0
            val durationUs = srcFmt.getLong(MediaFormat.KEY_DURATION)
            val srcFps = if (srcFmt.containsKey(MediaFormat.KEY_FRAME_RATE)) srcFmt.getInteger(MediaFormat.KEY_FRAME_RATE) else 0

            // Preserve the source's color space through the encode. Without these,
            // opaque VFR/HLG/any non-default content gets a gray/green tint because
            // the encoder defaults to unknown/limited.
            val colorStandard = if (srcFmt.containsKey(MediaFormat.KEY_COLOR_STANDARD))
                srcFmt.getInteger(MediaFormat.KEY_COLOR_STANDARD) else MediaFormat.COLOR_STANDARD_BT709
            val colorTransfer = if (srcFmt.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
                srcFmt.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            val colorRange = if (srcFmt.containsKey(MediaFormat.KEY_COLOR_RANGE))
                srcFmt.getInteger(MediaFormat.KEY_COLOR_RANGE) else MediaFormat.COLOR_RANGE_LIMITED

            // Size the output by the *display* dimensions, not the stored ones:
            // 90/270 rotation swaps the axes and non-square pixels stretch the frame.
            val parW = if (srcFmt.containsKey(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH))
                srcFmt.getInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH) else 1
            val parH = if (srcFmt.containsKey(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT))
                srcFmt.getInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT) else 1
            val effW = (srcW.toLong() * parW + parH / 2) / parH
            val rotated = rotation == 90 || rotation == 270
            val dispW = (if (rotated) srcH else effW).toInt().coerceAtLeast(2)
            val dispH = (if (rotated) effW else srcH).toInt().coerceAtLeast(2)

            val (outW, outH) = scaleDimensions(dispW, dispH, opts.targetWidth, opts.targetHeight)
            val fps = (opts.frameRate ?: 0).takeIf { it > 0 } ?: srcFps.takeIf { it > 0 } ?: 30
            val bitrate = opts.videoBitrate ?: estimateVideoBitrate(outW, outH, fps)

            val encFmt = MediaFormat.createVideoFormat("video/avc", outW, outH)
            encFmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            encFmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            encFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            encFmt.setInteger(MediaFormat.KEY_COLOR_STANDARD, colorStandard)
            encFmt.setInteger(MediaFormat.KEY_COLOR_TRANSFER, colorTransfer)
            encFmt.setInteger(MediaFormat.KEY_COLOR_RANGE, colorRange)
            // With surface input the encoder rotates the frames to these display
            // dimensions. If a 90/270 source ever comes out double-rotated on a
            // given SoC, swap outW/outH and drop this key instead.
            if (rotation != 0) encFmt.setInteger(MediaFormat.KEY_ROTATION, rotation)

            encoder = MediaCodec.createEncoderByType("video/avc")
            encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = encoder.createInputSurface()
            encoder.start()

            decoder = MediaCodec.createDecoderByType(vMime)
            decoder.configure(srcFmt, surface, null, 0)
            decoder.start()

            muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxState = MuxState()
            var sawInputEOS = false
            var decoderEOS = false
            var signaledEOS = false
            var encoderEOS = false

            val bufInfo = MediaCodec.BufferInfo()

            // Real-time pacing: without throttling, the surface-input encoder
            // flushes frames faster than the source's presentation timestamps,
            // drops frames to compensate, and produces choppy output with
            // artifacts ("plays in cut"). Pace each rendered frame against a
            // wall-clock anchored to the first decoded frame's PTS.
            var clockStartNs: Long? = null
            var clockOffsetUs = 0L
            var prevRenderedPts = -1L

            while (!encoderEOS) {
                checkCancelled(isCancelled)
                if (!sawInputEOS) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                            if (durationUs > 0) progress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 0.9f))
                        }
                    }
                }
                if (!decoderEOS) {
                    val outIdx = decoder.dequeueOutputBuffer(bufInfo, 10_000)
                    if (outIdx >= 0) {
                        val eos = (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val render = bufInfo.size > 0
                        val pts = bufInfo.presentationTimeUs
                        if (render && pts > 0 && pts != prevRenderedPts) {
                            prevRenderedPts = pts
                            if (clockStartNs == null) {
                                clockStartNs = System.nanoTime()
                                clockOffsetUs = pts
                            }
                            val elapsedTargetNs = (pts - clockOffsetUs) * 1000
                            val nowNs = System.nanoTime() - clockStartNs!!
                            val remainingNs = elapsedTargetNs - nowNs
                            if (remainingNs > 0) {
                                Thread.sleep(remainingNs / 1_000_000, (remainingNs % 1_000_000).toInt())
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, render)
                        if (eos) decoderEOS = true
                    }
                }
                if (decoderEOS && !signaledEOS) {
                    encoder.signalEndOfInputStream()
                    signaledEOS = true
                }
                if (drainEncoderOutput(encoder, muxer, bufInfo, muxState)) encoderEOS = true
                if (!encoderEOS && !muxState.ready) {
                    Thread.sleep(2)
                }
            }
            progress(0.95f)
        } finally {
            runCatching { surface?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private class MuxState {
        var track: Int = -1
        var started: Boolean = false
        val ready: Boolean get() = track >= 0
    }

    private fun drainEncoderOutput(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufInfo: MediaCodec.BufferInfo,
        state: MuxState
    ): Boolean {
        var eosSeen = false
        while (true) {
            val eOut = encoder.dequeueOutputBuffer(bufInfo, 0)
            when {
                eOut == MediaCodec.INFO_TRY_AGAIN_LATER -> return eosSeen
                eOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!state.started) {
                        state.track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        state.started = true
                    }
                }
                eOut == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                eOut >= 0 -> {
                    val buf = encoder.getOutputBuffer(eOut)!!
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufInfo.size = 0
                    if (bufInfo.size > 0 && state.started) {
                        muxer.writeSampleData(state.track, buf, bufInfo)
                    }
                    val eos = (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    encoder.releaseOutputBuffer(eOut, false)
                    if (eos) eosSeen = true
                }
            }
        }
    }

    private fun copySamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxerTrack: Int,
        durationUs: Long,
        progress: (Float) -> Unit
    ) {
        val buf = ByteBuffer.allocateDirect(1 shl 20)
        val bi = MediaCodec.BufferInfo()
        var firstPts: Long? = null
        var shift = 0L
        while (true) {
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) break
            val raw = extractor.sampleTime
            if (firstPts == null) {
                firstPts = raw
                if (raw < 0) shift = -raw
            }
            val pts = raw + shift
            if (pts >= 0) {
                bi.offset = 0
                bi.size = size
                bi.presentationTimeUs = pts
                bi.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrack, buf, bi)
            }
            extractor.advance()
            if (durationUs > 0) progress((pts.toFloat() / durationUs).coerceIn(0f, 1f))
        }
    }

    private fun mergeIntoMp4(
        videoPath: String,
        audioPath: String?,
        outPath: String,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val vEx = MediaExtractor()
        val aEx = if (audioPath != null) MediaExtractor() else null
        var muxer: MediaMuxer? = null
        try {
            vEx.setDataSource(videoPath)
            vEx.selectTrack(0)
            if (aEx != null && audioPath != null) {
                aEx.setDataSource(audioPath)
                aEx.selectTrack(0)
            }
            val vDur = vEx.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION)

            muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vMux = muxer.addTrack(vEx.getTrackFormat(0))
            val aMux = if (aEx != null) muxer.addTrack(aEx.getTrackFormat(0)) else -1
            muxer.start()

            copySamples(vEx, muxer, vMux, vDur) { p -> progress(p * 0.5f) }
            if (aEx != null) {
                val aDur = aEx.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION)
                copySamples(aEx, muxer, aMux, aDur) { p -> progress(0.5f + p * 0.5f) }
            }
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { vEx.release() }
            runCatching { aEx?.release() }
        }
    }

    private fun scaleDimensions(w: Int, h: Int, targetW: Int?, targetH: Int?): Pair<Int, Int> {
        var outW = w
        var outH = h
        if (targetW != null && targetW > 0 && outW > targetW) {
            outH = (outH.toLong() * targetW / outW).toInt()
            outW = targetW
        }
        if (targetH != null && targetH > 0 && outH > targetH) {
            outW = (outW.toLong() * targetH / outH).toInt()
            outH = targetH
        }
        outW = (outW / 2) * 2
        outH = (outH / 2) * 2
        if (outW < 2) outW = 2
        if (outH < 2) outH = 2
        return outW to outH
    }

    private fun estimateVideoBitrate(w: Int, h: Int, fps: Int): Int {
        val bps = w.toLong() * h.toLong() * fps.toLong() / 4
        return bps.coerceIn(200_000L, 8_000_000L).toInt()
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw ConverterCancelledException()
    }
}
