package com.example.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records phone calls trying the best audio sources first. VOICE_CALL captures
 * both sides on devices that allow it (many Transsion/Tecno builds do);
 * VOICE_COMMUNICATION and MIC are progressively looser fallbacks.
 */
object CallRecorder {

    private var recorder: MediaRecorder? = null
    private var filePath: String? = null
    private var usedSource: Int = -1

    private val _isRecording = MutableStateFlow(false)

    val isRecording = _asState()

    private fun _asState() = _isRecording.asStateFlow()

    fun outputDir(): File {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        }
        return File(base, "NJ-Dialer Calls").apply { mkdirs() }
    }

    /** @return true when recording started. */
    fun start(context: Context): Boolean {
        if (_isRecording.value) return true
        stopInternal()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(outputDir(), "call_$stamp.m4a")

        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
        for (src in sources) {
            try {
                val r: MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                r.setAudioSource(src)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(128000)
                r.setAudioSamplingRate(44100)
                r.setOutputFile(out.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                filePath = out.absolutePath
                usedSource = src
                _isRecording.value = true
                return true
            } catch (e: Exception) {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                recorder = null
                out.delete()
            }
        }
        return false
    }

    /** Stops recording; returns the saved file path or null when nothing recorded. */
    fun stop(): String? {
        val path = filePath
        val r = recorder
        var saved: String? = null
        try {
            if (r != null && _isRecording.value) {
                r.stop()
                saved = path
            }
        } catch (e: Exception) {
            saved = null
            path?.let { File(it).delete() }
        } finally {
            runCatching { r?.release() }
            recorder = null
            filePath = null
            usedSource = -1
            _isRecording.value = false
        }
        return saved
    }

    private fun stopInternal() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        filePath = null
        _isRecording.value = false
    }

    fun sourceName(): String = when (usedSource) {
        MediaRecorder.AudioSource.VOICE_CALL -> "Voice call (both sides)"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "Communication (mic side)"
        MediaRecorder.AudioSource.MIC -> "Microphone"
        else -> ""
    }
}
