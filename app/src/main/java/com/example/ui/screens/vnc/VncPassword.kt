package com.example.ui.screens.vnc

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object VncPassword {

    private val OBFUSCATION_KEY = byteArrayOf(23, 82, 107, 6, 35, 78, 88, 7)
    private const val TRANSFORMATION = "DES/ECB/NoPadding"
    private const val PASSWORD_BYTES = 8

    fun response(password: String, challenge: ByteArray): ByteArray {
        val key = passwordKey(password)
        return crypt(Cipher.ENCRYPT_MODE, key, challenge)
    }

    fun obfuscate(password: String): ByteArray {
        val plain = ByteArray(PASSWORD_BYTES)
        val bytes = password.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(bytes, 0, plain, 0, minOf(PASSWORD_BYTES, bytes.size))
        val key = ByteArray(PASSWORD_BYTES) { reverseBits(OBFUSCATION_KEY[it]) }
        return crypt(Cipher.ENCRYPT_MODE, key, plain)
    }

    fun deobfuscate(obfuscated: ByteArray): String? {
        if (obfuscated.size < PASSWORD_BYTES) return null
        val key = ByteArray(PASSWORD_BYTES) { reverseBits(OBFUSCATION_KEY[it]) }
        val plain = runCatching {
            crypt(Cipher.DECRYPT_MODE, key, obfuscated.copyOf(PASSWORD_BYTES))
        }.getOrNull() ?: return null
        val end = plain.indexOf(0.toByte()).let { if (it < 0) plain.size else it }
        return String(plain, 0, end, Charsets.ISO_8859_1).takeIf { it.isNotBlank() }
    }

    private fun passwordKey(password: String): ByteArray {
        val key = ByteArray(PASSWORD_BYTES)
        val bytes = password.toByteArray(Charsets.ISO_8859_1)
        for (index in 0 until minOf(PASSWORD_BYTES, bytes.size)) {
            key[index] = reverseBits(bytes[index])
        }
        return key
    }

    private fun crypt(mode: Int, key: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(mode, SecretKeySpec(key, "DES"))
        return cipher.doFinal(input)
    }

    private fun reverseBits(value: Byte): Byte {
        var source = value.toInt() and 0xFF
        var result = 0
        repeat(8) {
            result = (result shl 1) or (source and 1)
            source = source shr 1
        }
        return result.toByte()
    }
}
