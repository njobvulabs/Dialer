package com.example.util

data class HidTextEdit(val backspaces: Int, val insert: String)

object HidTextDiff {
    fun compute(old: String, new: String): HidTextEdit {
        var common = 0
        val limit = minOf(old.length, new.length)
        while (common < limit && old[common] == new[common]) common++
        return HidTextEdit(old.length - common, new.substring(common))
    }
}
