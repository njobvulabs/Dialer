package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HidMacrosTest {
    @Test
    fun parseRoundTripsChordsTextAndDelays() {
        val raw = """
            [
              {"id":"copy","label":"Copy","builtIn":true,
               "steps":[{"type":"chord","modifiers":1,"usage":6}]},
              {"id":"custom","label":"Custom",
               "steps":[{"type":"text","text":"hello"},{"type":"delay","ms":40}]}
            ]
        """.trimIndent()

        val macros = HidMacros.parse(raw)

        assertEquals(2, macros.size)
        assertEquals("copy", macros[0].id)
        assertTrue(macros[0].builtIn)
        assertEquals(HidMacroStep.Chord(1, 6), macros[0].steps.single())
        assertEquals(HidMacroStep.Text("hello"), macros[1].steps[0])
        assertEquals(HidMacroStep.Delay(40), macros[1].steps[1])
    }

    @Test
    fun malformedEntriesAreSkipped() {
        val raw = """[{"id":"empty","steps":[]},{"id":"ok","steps":[{"type":"text","text":"x"}]}]"""
        val macros = HidMacros.parse(raw)
        assertEquals(listOf("ok"), macros.map { it.id })
    }

    @Test
    fun defaultsIncludeStandardShortcuts() {
        val labels = HidMacros.defaults().map { it.label }
        assertTrue("Copy" in labels)
        assertTrue("Paste" in labels)
        assertTrue("Alt+Tab" in labels)
    }
}
