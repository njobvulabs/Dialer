package com.example.util

import java.text.Normalizer

data class HidKeyStroke(val usage: Int, val modifiers: Int)

object HidKeymap {
    const val MOD_CTRL = 0x01
    const val MOD_SHIFT = 0x02
    const val MOD_ALT = 0x04
    const val MOD_GUI = 0x08

    const val KEY_ENTER = 0x28
    const val KEY_ESC = 0x29
    const val KEY_BACKSPACE = 0x2A
    const val KEY_TAB = 0x2B
    const val KEY_SPACE = 0x2C
    const val KEY_CAPS_LOCK = 0x39
    const val KEY_F1 = 0x3A
    const val KEY_F2 = 0x3B
    const val KEY_F3 = 0x3C
    const val KEY_F4 = 0x3D
    const val KEY_F5 = 0x3E
    const val KEY_F6 = 0x3F
    const val KEY_F7 = 0x40
    const val KEY_F8 = 0x41
    const val KEY_F9 = 0x42
    const val KEY_F10 = 0x43
    const val KEY_F11 = 0x44
    const val KEY_F12 = 0x45
    const val KEY_INSERT = 0x49
    const val KEY_HOME = 0x4A
    const val KEY_PAGE_UP = 0x4B
    const val KEY_DELETE = 0x4C
    const val KEY_END = 0x4D
    const val KEY_PAGE_DOWN = 0x4E
    const val KEY_RIGHT = 0x4F
    const val KEY_LEFT = 0x50
    const val KEY_DOWN = 0x51
    const val KEY_UP = 0x52
    const val KEY_MENU = 0x65
    const val KEY_PRINT_SCREEN = 0x46
    const val KEY_SCROLL_LOCK = 0x47
    const val KEY_PAUSE = 0x48
    const val KEY_LEFT_GUI = 0xE3

    const val CONSUMER_PLAY_PAUSE = 0x00CD
    const val CONSUMER_NEXT = 0x00B5
    const val CONSUMER_PREVIOUS = 0x00B6
    const val CONSUMER_STOP = 0x00B7
    const val CONSUMER_MUTE = 0x00E2
    const val CONSUMER_VOLUME_UP = 0x00E9
    const val CONSUMER_VOLUME_DOWN = 0x00EA
    const val CONSUMER_BROWSER_HOME = 0x0223
    const val CONSUMER_BROWSER_BACK = 0x0224
    const val CONSUMER_BROWSER_FORWARD = 0x0225
    const val CONSUMER_BROWSER_REFRESH = 0x0227

    private val plain = mapOf(
        'a' to 0x04, 'b' to 0x05, 'c' to 0x06, 'd' to 0x07, 'e' to 0x08,
        'f' to 0x09, 'g' to 0x0A, 'h' to 0x0B, 'i' to 0x0C, 'j' to 0x0D,
        'k' to 0x0E, 'l' to 0x0F, 'm' to 0x10, 'n' to 0x11, 'o' to 0x12,
        'p' to 0x13, 'q' to 0x14, 'r' to 0x15, 's' to 0x16, 't' to 0x17,
        'u' to 0x18, 'v' to 0x19, 'w' to 0x1A, 'x' to 0x1B, 'y' to 0x1C,
        'z' to 0x1D,
        '1' to 0x1E, '2' to 0x1F, '3' to 0x20, '4' to 0x21, '5' to 0x22,
        '6' to 0x23, '7' to 0x24, '8' to 0x25, '9' to 0x26, '0' to 0x27,
        '\n' to KEY_ENTER, '\t' to KEY_TAB, ' ' to KEY_SPACE,
        '-' to 0x2D, '=' to 0x2E, '[' to 0x2F, ']' to 0x30, '\\' to 0x31,
        ';' to 0x33, '\'' to 0x34, '`' to 0x35, ',' to 0x36, '.' to 0x37,
        '/' to 0x38,
    )

    private val shifted = mapOf(
        '!' to 0x1E, '@' to 0x1F, '#' to 0x20, '$' to 0x21, '%' to 0x22,
        '^' to 0x23, '&' to 0x24, '*' to 0x25, '(' to 0x26, ')' to 0x27,
        '_' to 0x2D, '+' to 0x2E, '{' to 0x2F, '}' to 0x30, '|' to 0x31,
        ':' to 0x33, '"' to 0x34, '~' to 0x35, '<' to 0x36, '>' to 0x37,
        '?' to 0x38,
    )

    fun forChar(c: Char): HidKeyStroke? {
        if (c in 'A'..'Z') {
            val usage = plain[c.lowercaseChar()] ?: return null
            return HidKeyStroke(usage, MOD_SHIFT)
        }
        plain[c]?.let { return HidKeyStroke(it, 0) }
        shifted[c]?.let { return HidKeyStroke(it, MOD_SHIFT) }
        if (c.code < 128) return null
        return when (c) {
            '\u2018', '\u2019', '\u201A', '\u201B', '\u2032' -> forChar('\'')
            '\u201C', '\u201D', '\u201E', '\u201F', '\u2033' -> forChar('"')
            '\u2013', '\u2014', '\u2212' -> forChar('-')
            '\u2026' -> forChar('.')
            '\u00A0' -> forChar(' ')
            '\u00DF' -> forChar('s')
            '\u00E6' -> forChar('a')
            '\u0153' -> forChar('o')
            '\u00F8' -> forChar('o')
            '\u00F0' -> forChar('d')
            '\u00FE' -> forChar('t')
            else -> {
                val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
                val stripped = decomposed.filter { it.code !in 0x0300..0x036F }
                if (stripped.length == 1 && stripped[0] != c) forChar(stripped[0]) else null
            }
        }
    }

    fun unsupportedChars(text: String): String =
        text.filter { forChar(it) == null }.toSet().joinToString("")

    fun transliterate(text: String): String = buildString(text.length) {
        text.forEach { c ->
            val stroke = forChar(c)
            if (stroke == null) return@forEach
            val base = plain.entries.firstOrNull { it.value == stroke.usage }?.key
            if (base == null) append(c) else append(base)
        }
    }
}
