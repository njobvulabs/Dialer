package com.example.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class InternalAudioCapture(
    private val sampleRate: Int = 48_000,
    private val includeMic: Boolean = false,
    private val onError: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var thread: Thread? = null
    private var internalRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var startUs = 0L
    private var framesWritten = 0L

    fun isRunning(): Boolean = running.get()

    fun isPaused(): Boolean = paused.get()

    fun pause() {
        paused.set(true)
    }

    fun resume() {
        paused.set(false)
    }

    fun start(context: Context, projection: MediaProjection, output: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onError("System audio capture needs Android 10 or newer")
            return
        }
        if (running.get()) return
        if (output.exists()) runCatching { output.delete() }
        runCatching { output.parentFile?.mkdirs() }

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val internal = AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()
        internalRecord = internal

        val mic = if (includeMic &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(format)
                    .build()
            }.getOrNull()
        } else null
        micRecord = mic

        val audioFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            2
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder = codec
        codec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        internal.startRecording()
        mic?.startRecording()
        running.set(true)
        paused.set(false)
        startUs = System.nanoTime() / 1_000L
        framesWritten = 0L
        trackIndex = -1
        muxerStarted = false

        thread = Thread({ pump() }, "nj-internal-audio").apply { start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { thread?.join(2_000) }
        thread = null
        runCatching { internalRecord?.stop() }
        runCatching { micRecord?.stop() }
        runCatching { internalRecord?.release() }
        runCatching { micRecord?.release() }
        internalRecord = null
        micRecord = null

        val codec = encoder ?: return
        val mux = muxer ?: return
        runCatching {
            val input = codec.dequeueInputBuffer(2_000L)
            if (input >= 0) {
                codec.getInputBuffer(input)?.clear()
                codec.queueInputBuffer(
                    input,
                    0,
                    0,
                    presentationTimeUs(),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
            drain(codec, mux, true)
            if (!muxerStarted) startMuxer(codec, mux)
            mux.stop()
        }.onFailure { onError(it.message ?: "Could not finalize audio") }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { mux.release() }
        encoder = null
        muxer = null
    }

    private fun presentationTimeUs(): Long =
        startUs + framesWritten * 1_000_000L / sampleRate

    private fun pump() {
        val samplesPerFrame = 1024
        val internalBuf = ShortArray(samplesPerFrame * 2)
        val micBuf = ShortArray(samplesPerFrame * 2)
        val mixed = ShortArray(samplesPerFrame * 2)
        val codec = encoder ?: return
        val mux = muxer ?: return
        val info = MediaCodec.BufferInfo()

        while (running.get()) {
            if (paused.get()) {
                drain(codec, mux, false)
                Thread.sleep(20L)
                continue
            }
            val internalRead = runCatching {
                internalRecord?.read(internalBuf, 0, internalBuf.size, 0) ?: -1
            }.getOrElse { -1 }
            val micRead = if (micRecord != null) {
                runCatching {
                    micRecord?.read(micBuf, 0, micBuf.size, 0) ?: -1
                }.getOrElse { -1 }
            } else -1

            if (internalRead <= 0 && micRead <= 0) {
                drain(codec, mux, false)
                Thread.sleep(5L)
                continue
            }

            val frames = maxOf(internalRead, micRead) / 2
            if (frames <= 0) continue
            for (i in 0 until frames * 2) {
                val a = if (i < internalRead) internalBuf[i] else 0
                val b = if (micRecord != null && i < micRead) micBuf[i] else 0
                mixed[i] = ((a.toInt() + b.toInt()) / 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            if (!encode(codec, mixed, frames * 2, info)) break
            drain(codec, mux, false)
        }
    }

    private fun encode(
        codec: MediaCodec,
        samples: ShortArray,
        sampleCount: Int,
        info: MediaCodec.BufferInfo
    ): Boolean {
        val timeout = 100_000L
        val index = codec.dequeueInputBuffer(timeout)
        if (index < 0) return true
        val buffer = codec.getInputBuffer(index) ?: return true
        buffer.clear()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(samples, 0, sampleCount)
        codec.queueInputBuffer(
            index,
            0,
            sampleCount * 2,
            presentationTimeUs(),
            0
        )
        framesWritten += sampleCount / 2
        return true
    }

    private fun drain(codec: MediaCodec, mux: MediaMuxer, endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, if (endOfStream) 2_000L else 0L)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) startMuxer(codec, mux)
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> {
                    if (index < 0) return
                    if (!muxerStarted) startMuxer(codec, mux)
                    if (info.size > 0 && trackIndex >= 0) {
                        val buffer: ByteBuffer = codec.getOutputBuffer(index) ?: run {
                            codec.releaseOutputBuffer(index, false)
                            continue
                        }
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        mux.writeSampleData(trackIndex, buffer, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) return
                }
            }
        }
    }

    private fun startMuxer(codec: MediaCodec, mux: MediaMuxer) {
        if (muxerStarted) return
        trackIndex = mux.addTrack(codec.outputFormat)
        mux.start()
        muxerStarted = true
    }

    companion object {
        fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
}
