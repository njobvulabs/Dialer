package com.example.ui.screens.vnc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VncPasswordTest {

    @Test
    fun responseMatchesTigerVncDes() {
        val challenge = ByteArray(16) { it.toByte() }
        val expected = hex("eae3a1cb74ca6daac183f66460190bb5")
        assertArrayEquals(expected, VncPassword.response("abcdefgh", challenge))
    }

    @Test
    fun obfuscationMatchesTigerVncPasswd() {
        assertArrayEquals(hex("db8ab02565e3b4c2"), VncPassword.obfuscate("abcdefgh"))
    }

    @Test
    fun obfuscatedPasswordRoundTrips() {
        val password = "Vnc12345"
        assertEquals(password, VncPassword.deobfuscate(VncPassword.obfuscate(password)))
    }

    @Test
    fun shortPasswordIsPaddedWithNuls() {
        assertEquals("abc", VncPassword.deobfuscate(VncPassword.obfuscate("abc")))
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}
