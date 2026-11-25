package com.example.util

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class TextEncoding(val label: String) {
    AUTO("Auto detect"),
    UTF8("UTF-8"),
    UTF16LE("UTF-16 LE"),
    UTF16BE("UTF-16 BE"),
    LATIN1("Latin-1")
}

enum class NewlineStyle(val label: String, val separator: String) {
    LF("LF", "\n"),
    CRLF("CRLF", "\r\n"),
    CR("CR", "\r")
}

data class DecodedText(
    val text: String,
    val encoding: TextEncoding,
    val hadBom: Boolean,
    val newline: NewlineStyle
)

object TextFileCodec {
    fun decode(bytes: ByteArray, requested: TextEncoding): DecodedText? {
        val bom = detectBom(bytes)
        val encoding = when {
            requested != TextEncoding.AUTO -> requested
            bom != null -> bom.first
            else -> guessEncoding(bytes)
        }
        val payload = if (bom != null && bom.first == encoding) bytes.copyOfRange(bom.second, bytes.size) else bytes
        val charset = charsetFor(encoding) ?: return null
        val decoded = decodeStrict(payload, charset) ?: return null
        val clean = decoded.removePrefix("\uFEFF")
        if (looksBinary(clean)) return null
        val newline = when {
            clean.contains("\r\n") -> NewlineStyle.CRLF
            clean.contains('\r') && !clean.contains('\n') -> NewlineStyle.CR
            else -> NewlineStyle.LF
        }
        return DecodedText(
            text = normalizeToLf(clean),
            encoding = encoding,
            hadBom = bom != null && bom.first == encoding,
            newline = newline
        )
    }

    fun encode(
        text: String,
        encoding: TextEncoding,
        writeBom: Boolean,
        newline: NewlineStyle
    ): ByteArray? {
        val charset = charsetFor(if (encoding == TextEncoding.AUTO) TextEncoding.UTF8 else encoding) ?: return null
        val body = applyNewline(normalizeToLf(text), newline).toByteArray(charset)
        if (!writeBom) return body
        val bom = bomFor(if (encoding == TextEncoding.AUTO) TextEncoding.UTF8 else encoding) ?: return body
        return bom + body
    }

    fun normalizeToLf(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')

    fun applyNewline(value: String, newline: NewlineStyle): String =
        if (newline == NewlineStyle.LF) normalizeToLf(value)
        else normalizeToLf(value).replace("\n", newline.separator)

    private fun detectBom(bytes: ByteArray): Pair<TextEncoding, Int>? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            TextEncoding.UTF8 to 3
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            TextEncoding.UTF16LE to 2
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            TextEncoding.UTF16BE to 2
        else -> null
    }

    private fun guessEncoding(bytes: ByteArray): TextEncoding {
        if (bytes.size >= 2 && bytes[0] == 0.toByte() && bytes[1] != 0.toByte()) return TextEncoding.UTF16BE
        if (bytes.size >= 2 && bytes[1] == 0.toByte() && bytes[0] != 0.toByte()) return TextEncoding.UTF16LE
        return TextEncoding.UTF8
    }

    private fun charsetFor(encoding: TextEncoding): Charset? = when (encoding) {
        TextEncoding.AUTO -> StandardCharsets.UTF_8
        TextEncoding.UTF8 -> StandardCharsets.UTF_8
        TextEncoding.UTF16LE -> StandardCharsets.UTF_16LE
        TextEncoding.UTF16BE -> StandardCharsets.UTF_16BE
        TextEncoding.LATIN1 -> StandardCharsets.ISO_8859_1
    }

    private fun bomFor(encoding: TextEncoding): ByteArray? = when (encoding) {
        TextEncoding.UTF8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        TextEncoding.UTF16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        TextEncoding.UTF16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        else -> null
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun looksBinary(value: String): Boolean {
        if (value.isEmpty()) return false
        if (value.contains('\u0000')) return true
        val meaningful = value.filterNot { it.isWhitespace() }
        if (meaningful.isEmpty()) return false
        val controls = meaningful.count { it.isISOControl() }
        return controls.toFloat() / meaningful.length > 0.05f
    }
}
