package com.example.service

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Speaks the caller's name over the ringtone using on-device TTS. */
object CallAnnouncer {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var initializing = false

    fun ensureInit(appContext: Context) {
        if (ready || initializing) return
        initializing = true
        tts = TextToSpeech(appContext.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            initializing = false
            if (ready) {
                runCatching { tts?.language = Locale.getDefault() }
            }
        }
    }

    fun speak(text: String) {
        if (!ready) return
        runCatching {

            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "caller_announce_${System.currentTimeMillis()}")
        }
    }

    fun stopSpeaking() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        initializing = false
    }
}
