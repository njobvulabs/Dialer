package com.example.ui.screens.ppsspp

import android.media.AudioTrack
import android.view.Surface

object PpssppRetroNative {
    init {
        System.loadLibrary("ppssppjni")
    }

    external fun init()
    external fun setPaths(systemDir: String, saveDir: String, contentDir: String)
    external fun setOption(key: String, value: String?)
    external fun setSurface(surface: Surface?)
    external fun setAudioTrack(track: AudioTrack?, frames: Int, mode: Int)
    external fun loadRom(path: String): Boolean
    external fun unload()
    external fun runFrame(): Boolean
    external fun setButtons(mask: Long)
    external fun setAnalog(x: Short, y: Short)
    external fun avInfo(): IntArray
    external fun reset()
    external fun saveState(path: String): Boolean
    external fun loadState(path: String): Boolean
}
