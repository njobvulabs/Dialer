package com.example.ui.screens.vnc

import androidx.compose.ui.input.key.Key

object VncKeys {

    const val BACKSPACE = 0xFF08
    const val TAB = 0xFF09
    const val ENTER = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val INSERT = 0xFF63
    const val DELETE = 0xFFFF
    const val HOME = 0xFF50
    const val END = 0xFF57
    const val PAGE_UP = 0xFF55
    const val PAGE_DOWN = 0xFF56
    const val LEFT = 0xFF51
    const val UP = 0xFF52
    const val RIGHT = 0xFF53
    const val DOWN = 0xFF54
    const val SHIFT_LEFT = 0xFFE1
    const val SHIFT_RIGHT = 0xFFE2
    const val CTRL_LEFT = 0xFFE3
    const val CTRL_RIGHT = 0xFFE4
    const val ALT_LEFT = 0xFFE9
    const val ALT_RIGHT = 0xFFEA
    const val SUPER_LEFT = 0xFFEB
    const val SUPER_RIGHT = 0xFFEC
    const val CAPS_LOCK = 0xFFE5
    const val NUM_LOCK = 0xFF7F
    const val SPACE = 0x20

    val functionKeys = listOf(
        "F1" to 0xFFBE,
        "F2" to 0xFFBF,
        "F3" to 0xFFC0,
        "F4" to 0xFFC1,
        "F5" to 0xFFC2,
        "F6" to 0xFFC3,
        "F7" to 0xFFC4,
        "F8" to 0xFFC5,
        "F9" to 0xFFC6,
        "F10" to 0xFFC7,
        "F11" to 0xFFC8,
        "F12" to 0xFFC9
    )

    fun keysymForChar(char: Char): Int {
        return when (char) {
            '\n', '\r' -> ENTER
            '\t' -> TAB
            '\b' -> BACKSPACE
            '\u001B' -> ESCAPE
            else -> {
                val code = char.code
                when {
                    code in 0x20..0x7E -> code
                    code in 0xA0..0xFF -> code
                    code == 0x20AC -> 0x20AC
                    code == 0x00A3 -> 0x00A3
                    code == 0x00A5 -> 0x00A5
                    code == 0x00A9 -> 0x00A9
                    code == 0x00AE -> 0x00AE
                    else -> '?'.code
                }
            }
        }
    }

    fun keysymForHardware(key: Key, unicodeChar: Int, ctrlOrMeta: Boolean): Int? {
        when (key) {
            Key.Backspace -> return BACKSPACE
            Key.Enter, Key.NumPadEnter -> return ENTER
            Key.Tab -> return TAB
            Key.Escape -> return ESCAPE
            Key.Delete -> return DELETE
            Key.Insert -> return INSERT
            Key.DirectionLeft -> return LEFT
            Key.DirectionRight -> return RIGHT
            Key.DirectionUp -> return UP
            Key.DirectionDown -> return DOWN
            Key.MoveHome -> return HOME
            Key.MoveEnd -> return END
            Key.PageUp -> return PAGE_UP
            Key.PageDown -> return PAGE_DOWN
            Key.ShiftLeft -> return SHIFT_LEFT
            Key.ShiftRight -> return SHIFT_RIGHT
            Key.CtrlLeft -> return CTRL_LEFT
            Key.CtrlRight -> return CTRL_RIGHT
            Key.AltLeft -> return ALT_LEFT
            Key.AltRight -> return ALT_RIGHT
            Key.MetaLeft -> return SUPER_LEFT
            Key.MetaRight -> return SUPER_RIGHT
            Key.CapsLock -> return CAPS_LOCK
            Key.NumLock -> return NUM_LOCK
            Key.Spacebar -> return SPACE
        }

        val code = key.keyCode.toInt()
        if (code in Key.F1.keyCode.toInt()..Key.F12.keyCode.toInt()) {
            return 0xFFBE + (code - Key.F1.keyCode.toInt())
        }
        if (code in Key.A.keyCode.toInt()..Key.Z.keyCode.toInt()) {
            if (ctrlOrMeta) return 'a'.code + (code - Key.A.keyCode.toInt())
            if (unicodeChar in 0x20..0x7E) return unicodeChar
            return 'a'.code + (code - Key.A.keyCode.toInt())
        }
        if (code in Key.Zero.keyCode.toInt()..Key.Nine.keyCode.toInt()) {
            if (ctrlOrMeta) return '0'.code + (code - Key.Zero.keyCode.toInt())
            if (unicodeChar != 0) return keysymForChar(unicodeChar.toChar())
            return '0'.code + (code - Key.Zero.keyCode.toInt())
        }

        if (unicodeChar != 0) return keysymForChar(unicodeChar.toChar())
        return null
    }
}
