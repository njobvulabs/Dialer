package com.example.util

import android.content.Context
import android.content.SharedPreferences

object HidPrefs {
    const val FILE = "btinput"
    const val SENSITIVITY = "sensitivity"
    const val KEEP_SCREEN_ON = "keep_screen_on"
    const val NATURAL_SCROLL = "natural_scroll"
    const val TAP_TO_CLICK = "tap_to_click"
    const val LEFT_HANDED = "left_handed"
    const val HAPTICS = "haptics"
    const val MEDIA_KEYS = "media_keys"
    const val SYSTEM_KEYBOARD = "system_keyboard"
    const val LAST_HOST_ADDRESS = "last_host_address"
    const val LAST_HOST_NAME = "last_host_name"
    const val AUTO_CONNECT = "auto_connect"
    const val MACROS = "macros"
    const val OVERLAY_X = "overlay_x"
    const val OVERLAY_Y = "overlay_y"

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
