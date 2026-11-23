package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HidTextDiffTest {
    @Test
    fun appendOnlyInsertsSuffix() {
        assertEquals(HidTextEdit(0, "world"), HidTextDiff.compute("hello ", "hello world"))
    }

    @Test
    fun editBackspacesToCommonPrefix() {
        assertEquals(HidTextEdit(5, "there"), HidTextDiff.compute("hello", "there"))
    }

    @Test
    fun identicalTextNeedsNoWork() {
        assertEquals(HidTextEdit(0, ""), HidTextDiff.compute("same", "same"))
    }

    @Test
    fun deletingEverythingBackspacesWholeString() {
        assertEquals(HidTextEdit(3, ""), HidTextDiff.compute("abc", ""))
    }
}
