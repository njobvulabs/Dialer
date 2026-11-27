package com.example.ui.screens.retro

import android.media.AudioTrack
import android.view.Surface

object RetroNative {
    init {
        System.loadLibrary("njretro")
    }

    external fun init()
    external fun setPaths(systemDir: String, saveDir: String)
    external fun setSurface(surface: Surface?)
    external fun setAudioTrack(track: AudioTrack?, frames: Int, mode: Int)
    external fun loadRom(path: String): Boolean
    external fun unload()
    external fun runFrame(): Boolean
    external fun setButtons(mask: Long)
    external fun avInfo(): IntArray
    external fun reset()
    external fun saveSram(path: String): Boolean
    external fun loadSram(path: String): Boolean
    external fun saveState(path: String): Boolean
    external fun loadState(path: String): Boolean
}
