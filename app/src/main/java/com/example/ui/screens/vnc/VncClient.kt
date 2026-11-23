package com.example.ui.screens.vnc

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

enum class VncConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR,
    DISCONNECTED,
}

class VncClient(private val listener: Listener) : Closeable {

    interface Listener {
        fun onState(state: VncConnectionState, message: String? = null)
        fun onFrame(bitmap: Bitmap)
        fun onClipboard(text: String)
        fun onBell()
    }

    private class VncException(message: String) : Exception(message)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    @Volatile
    private var output: DataOutputStream? = null
    @Volatile
    private var sessionSeq = 0

    private var bitmap: Bitmap? = null
    private var framebufferWidth = 0
    private var framebufferHeight = 0
    private var versionMinor = 8
    private var authType = 1
    private var lastPointerX = 0
    private var lastPointerY = 0

    fun connect(host: String, port: Int, password: String) {
        stopInternal()
        val session = ++sessionSeq
        job = scope.launch {
            try {
                postState(session, VncConnectionState.CONNECTING, "Connecting to $host:$port")
                val s = Socket()
                socket = s
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                val din = DataInputStream(BufferedInputStream(s.getInputStream()))
                val dout = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                input = din
                output = dout

                val serverVersion = negotiateVersion(din, dout)
                authenticate(din, dout, password)
                dout.writeByte(1)
                dout.flush()

                val serverName = readServerInit(din)
                sendSetPixelFormat(dout)
                sendSetEncodings(dout)
                sendFramebufferUpdateRequest(dout, false)
                postState(session, VncConnectionState.CONNECTED, "Connected to $serverName ($serverVersion)")

                while (session == sessionSeq) {
                    when (din.readUnsignedByte()) {
                        0 -> readFramebufferUpdate(session, din)
                        1 -> skipColourMap(din)
                        2 -> mainHandler.post { listener.onBell() }
                        3 -> readServerCutText(session, din)
                        else -> throw VncException("Unsupported server message")
                    }
                    if (session == sessionSeq) {
                        sendFramebufferUpdateRequest(dout, true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session == sessionSeq) {
                    postState(session, VncConnectionState.ERROR, friendlyMessage(e))
                }
            } finally {
                runCatching { socket?.close() }
                if (session == sessionSeq) {
                    socket = null
                    input = null
                    output = null
                    if (job?.isCancelled != true) {
                        postState(session, VncConnectionState.DISCONNECTED, null)
                    }
                }
            }
        }
    }

    override fun close() {
        sessionSeq++
        stopInternal()
        bitmap = null
        framebufferWidth = 0
        framebufferHeight = 0
    }

    fun sendPointer(buttonMask: Int, x: Int, y: Int) {
        val out = output ?: return
        val safeX = x.coerceIn(0, (framebufferWidth - 1).coerceAtLeast(0))
        val safeY = y.coerceIn(0, (framebufferHeight - 1).coerceAtLeast(0))
        lastPointerX = safeX
        lastPointerY = safeY
        synchronized(out) {
            out.writeByte(5)
            out.writeByte(buttonMask and 0xFF)
            out.writeShort(safeX)
            out.writeShort(safeY)
            out.flush()
        }
    }

    fun sendWheel(up: Boolean) {
        val mask = if (up) 8 else 16
        sendPointer(mask, lastPointerX, lastPointerY)
        sendPointer(0, lastPointerX, lastPointerY)
    }

    fun sendKey(keysym: Int, down: Boolean) {
        val out = output ?: return
        synchronized(out) {
            out.writeByte(4)
            out.writeByte(if (down) 1 else 0)
            out.writeShort(0)
            out.writeInt(keysym)
            out.flush()
        }
    }

    fun sendClientCutText(text: String) {
        val out = output ?: return
        val bytes = ByteArray(text.length)
        for (i in text.indices) {
            val c = text[i].code
            bytes[i] = if (c in 0..255) c.toByte() else '?'.code.toByte()
        }
        synchronized(out) {
            out.writeByte(6)
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(0)
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }
    }

    private fun stopInternal() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private fun postState(session: Int, state: VncConnectionState, message: String?) {
        if (session != sessionSeq) return
        mainHandler.post {
            if (session == sessionSeq) listener.onState(state, message)
        }
    }

    private fun postFrame(session: Int, frame: Bitmap) {
        if (session != sessionSeq) return
        mainHandler.post {
            if (session == sessionSeq) listener.onFrame(frame)
        }
    }

    private fun negotiateVersion(din: DataInputStream, dout: DataOutputStream): String {
        val bytes = ByteArray(12)
        din.readFully(bytes)
        val text = String(bytes, Charsets.US_ASCII)
        val match = VERSION_REGEX.find(text) ?: throw VncException("Not a VNC server")
        val major = match.groupValues[1].toIntOrNull() ?: 0
        val minor = match.groupValues[2].toIntOrNull() ?: 0
        if (major != 3) throw VncException("Unsupported RFB version $major.$minor")
        versionMinor = when {
            minor >= 8 -> 8
            minor == 7 -> 7
            else -> 3
        }
        dout.write("RFB 003.00${versionMinor}\n".toByteArray(Charsets.US_ASCII))
        dout.flush()
        return "3.$versionMinor"
    }

    private fun authenticate(din: DataInputStream, dout: DataOutputStream, password: String) {
        if (versionMinor >= 7) {
            val count = din.readUnsignedByte()
            if (count == 0) throw VncException("Server refused connection: ${readReason(din)}")
            val types = IntArray(count) { din.readUnsignedByte() }
            authType = when {
                2 in types -> 2
                1 in types -> 1
                else -> throw VncException("Unsupported security types: ${types.joinToString()}")
            }
            dout.writeByte(authType)
            dout.flush()
        } else {
            authType = din.readInt()
            if (authType == 0) throw VncException("Server refused connection: ${readReason(din)}")
            if (authType != 1 && authType != 2) {
                throw VncException("Unsupported security type $authType")
            }
        }

        if (authType == 2) {
            val challenge = ByteArray(16)
            din.readFully(challenge)
            dout.write(desResponse(password, challenge))
            dout.flush()
        }

        if (versionMinor >= 8 || authType == 2) {
            val result = din.readInt()
            if (result != 0) {
                val reason = if (versionMinor >= 8) readReason(din) else ""
                throw VncException(
                    if (reason.isBlank()) "Authentication failed" else "Authentication failed: $reason"
                )
            }
        }
    }

    private fun readServerInit(din: DataInputStream): String {
        val width = din.readUnsignedShort()
        val height = din.readUnsignedShort()
        din.readFully(ByteArray(16))
        val nameLength = din.readInt()
        if (nameLength < 0 || nameLength > MAX_STRING_BYTES) throw VncException("Invalid server name")
        val nameBytes = ByteArray(nameLength)
        din.readFully(nameBytes)
        createFramebuffer(width, height)
        return String(nameBytes, Charsets.UTF_8).trim().ifEmpty { "VNC server" }
    }

    private fun createFramebuffer(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION ||
            width.toLong() * height.toLong() > MAX_PIXELS
        ) {
            throw VncException("Invalid framebuffer size ${width}x$height")
        }
        framebufferWidth = width
        framebufferHeight = height
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    private fun sendSetPixelFormat(out: DataOutputStream) {
        synchronized(out) {
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(32)
            out.writeByte(24)
            out.writeByte(0)
            out.writeByte(1)
            out.writeShort(255)
            out.writeShort(255)
            out.writeShort(255)
            out.writeByte(16)
            out.writeByte(8)
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(0)
            out.flush()
        }
    }

    private fun sendSetEncodings(out: DataOutputStream) {
        val encodings = intArrayOf(6, 5, 4, 2, 1, 0, -223, -222, -221, -220, -219)
        synchronized(out) {
            out.writeByte(2)
            out.writeByte(0)
            out.writeShort(encodings.size)
            encodings.forEach { out.writeInt(it) }
            out.flush()
        }
    }

    private fun sendFramebufferUpdateRequest(out: DataOutputStream, incremental: Boolean) {
        val width = framebufferWidth
        val height = framebufferHeight
        if (width <= 0 || height <= 0) return
        synchronized(out) {
            out.writeByte(3)
            out.writeByte(if (incremental) 1 else 0)
            out.writeShort(0)
            out.writeShort(0)
            out.writeShort(width)
            out.writeShort(height)
            out.flush()
        }
    }

    private fun readFramebufferUpdate(session: Int, din: DataInputStream) {
        din.readUnsignedByte()
        val count = din.readUnsignedShort()
        var sizeChanged = false
        repeat(count) {
            val x = din.readUnsignedShort()
            val y = din.readUnsignedShort()
            val width = din.readUnsignedShort()
            val height = din.readUnsignedShort()
            when (val encoding = din.readInt()) {
                0 -> readRaw(din, x, y, width, height)
                1 -> readCopyRect(din, x, y, width, height)
                2 -> readRre(din, x, y, width, height)
                5 -> readHextile(din, x, y, width, height)
                6 -> readZlib(din, x, y, width, height)
                -223 -> {
                    createFramebuffer(width, height)
                    sizeChanged = true
                }
                -222 -> { /* Tight encoding - pending implementation */ }
                -221 -> { /* ZRLE encoding - pending implementation */ }
                -220 -> { /* Hextile extended - pending implementation */ }
                -219 -> { /* RRE extended - pending implementation */ }
                else -> throw VncException("Unsupported encoding $encoding")
            }
        }
        val frame = bitmap
        if (frame != null && (sizeChanged || count > 0)) {
            postFrame(session, frame)
        }
    }

    private fun readRaw(din: DataInputStream, x: Int, y: Int, width: Int, height: Int) {
        validateRect(x, y, width, height)
        val frame = bitmap ?: throw VncException("Framebuffer is not ready")
        val row = ByteArray(width * 4)
        val pixels = IntArray(width)
        for (rowIndex in 0 until height) {
            din.readFully(row)
            var offset = 0
            for (column in 0 until width) {
                val value = (row[offset].toInt() and 0xFF) or
                    ((row[offset + 1].toInt() and 0xFF) shl 8) or
                    ((row[offset + 2].toInt() and 0xFF) shl 16) or
                    ((row[offset + 3].toInt() and 0xFF) shl 24)
                pixels[column] = pixelToArgb(value)
                offset += 4
            }
            frame.setPixels(pixels, 0, width, x, y + rowIndex, width, 1)
        }
    }

    private fun readCopyRect(din: DataInputStream, x: Int, y: Int, width: Int, height: Int) {
        validateRect(x, y, width, height)
        val sourceX = din.readUnsignedShort()
        val sourceY = din.readUnsignedShort()
        validateRect(sourceX, sourceY, width, height)
        val frame = bitmap ?: throw VncException("Framebuffer is not ready")
        val pixels = IntArray(width * height)
        frame.getPixels(pixels, 0, width, sourceX, sourceY, width, height)
        frame.setPixels(pixels, 0, width, x, y, width, height)
    }

    private fun readRre(din: DataInputStream, x: Int, y: Int, width: Int, height: Int) {
        validateRect(x, y, width, height)
        val frame = bitmap ?: throw VncException("Framebuffer is not ready")
        val background = pixelToArgb(readRawPixel(din))
        val count = din.readInt()
        if (count < 0 || count > MAX_RRE_RECTS) throw VncException("Invalid RRE rectangle count")
        val pixels = IntArray(width * height) { background }
        repeat(count) {
            val color = pixelToArgb(readRawPixel(din))
            val rx = din.readUnsignedShort()
            val ry = din.readUnsignedShort()
            val rw = din.readUnsignedShort()
            val rh = din.readUnsignedShort()
            if (rw > width || rh > height || rx + rw > width || ry + rh > height) {
                throw VncException("Invalid RRE subrectangle")
            }
            for (row in 0 until rh) {
                java.util.Arrays.fill(pixels, (ry + row) * width + rx, (ry + row) * width + rx + rw, color)
            }
        }
        frame.setPixels(pixels, 0, width, x, y, width, height)
    }

    private fun readZlib(din: DataInputStream, x: Int, y: Int, width: Int, height: Int) {
        validateRect(x, y, width, height)
        val frame = bitmap ?: throw VncException("Framebuffer is not ready")
        val compressedLength = din.readInt()
        if (compressedLength <= 0 || compressedLength > MAX_ZLIB_BYTES) {
            throw VncException("Invalid zlib payload length")
        }
        val compressed = ByteArray(compressedLength)
        din.readFully(compressed)
        val expected = width * height * 4
        val raw = inflate(compressed, expected)
        val pixels = IntArray(width)
        for (row in 0 until height) {
            var offset = row * width * 4
            for (column in 0 until width) {
                val value = (raw[offset].toInt() and 0xFF) or
                    ((raw[offset + 1].toInt() and 0xFF) shl 8) or
                    ((raw[offset + 2].toInt() and 0xFF) shl 16) or
                    ((raw[offset + 3].toInt() and 0xFF) shl 24)
                pixels[column] = pixelToArgb(value)
                offset += 4
            }
            frame.setPixels(pixels, 0, width, x, y + row, width, 1)
        }
    }

    private fun readHextile(din: DataInputStream, x: Int, y: Int, width: Int, height: Int) {
        validateRect(x, y, width, height)
        val frame = bitmap ?: throw VncException("Framebuffer is not ready")
        val tilePixels = IntArray(16 * 16)
        var background = 0
        var foreground = 0
        val tileColumns = (width + 15) / 16
        val tileRows = (height + 15) / 16
        for (tileRow in 0 until tileRows) {
            for (tileColumn in 0 until tileColumns) {
                val tileWidth = minOf(16, width - tileColumn * 16)
                val tileHeight = minOf(16, height - tileRow * 16)
                val subencoding = din.readUnsignedByte()
                if (subencoding and 1 != 0) {
                    val row = ByteArray(tileWidth * 4)
                    for (rowIndex in 0 until tileHeight) {
                        din.readFully(row)
                        var offset = 0
                        for (column in 0 until tileWidth) {
                            val value = (row[offset].toInt() and 0xFF) or
                                ((row[offset + 1].toInt() and 0xFF) shl 8) or
                                ((row[offset + 2].toInt() and 0xFF) shl 16) or
                                ((row[offset + 3].toInt() and 0xFF) shl 24)
                            tilePixels[rowIndex * tileWidth + column] = pixelToArgb(value)
                            offset += 4
                        }
                    }
                } else {
                    if (subencoding and 2 != 0) background = pixelToArgb(readRawPixel(din))
                    if (subencoding and 4 != 0) foreground = pixelToArgb(readRawPixel(din))
                    java.util.Arrays.fill(tilePixels, 0, tileWidth * tileHeight, background)
                    if (subencoding and 8 != 0) {
                        val subrectCount = din.readUnsignedByte()
                        repeat(subrectCount) {
                            val color = if (subencoding and 16 != 0) {
                                pixelToArgb(readRawPixel(din))
                            } else {
                                foreground
                            }
                            val subX = din.readUnsignedByte()
                            val subY = din.readUnsignedByte()
                            val subWidth = din.readUnsignedByte()
                            val subHeight = din.readUnsignedByte()
                            if (subWidth > tileWidth || subHeight > tileHeight ||
                                subX + subWidth > tileWidth || subY + subHeight > tileHeight
                            ) {
                                throw VncException("Invalid hextile subrectangle")
                            }
                            for (row in 0 until subHeight) {
                                java.util.Arrays.fill(
                                    tilePixels,
                                    (subY + row) * tileWidth + subX,
                                    (subY + row) * tileWidth + subX + subWidth,
                                    color
                                )
                            }
                        }
                    }
                }
                frame.setPixels(
                    tilePixels,
                    0,
                    tileWidth,
                    x + tileColumn * 16,
                    y + tileRow * 16,
                    tileWidth,
                    tileHeight
                )
            }
        }
    }

    private fun readRawPixel(din: DataInputStream): Int {
        val b0 = din.readUnsignedByte()
        val b1 = din.readUnsignedByte()
        val b2 = din.readUnsignedByte()
        val b3 = din.readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun skipColourMap(din: DataInputStream) {
        din.readUnsignedByte()
        din.readUnsignedShort()
        val count = din.readUnsignedShort()
        repeat(count) {
            din.readUnsignedShort()
            din.readUnsignedShort()
            din.readUnsignedShort()
        }
    }

    private fun readServerCutText(session: Int, din: DataInputStream) {
        din.readFully(ByteArray(3))
        val length = din.readInt()
        if (length < 0 || length > MAX_STRING_BYTES) throw VncException("Invalid clipboard text")
        val bytes = ByteArray(length)
        din.readFully(bytes)
        val text = String(bytes, Charsets.ISO_8859_1)
        mainHandler.post {
            if (session == sessionSeq) listener.onClipboard(text)
        }
    }

    private fun validateRect(x: Int, y: Int, width: Int, height: Int) {
        if (x < 0 || y < 0 || width <= 0 || height <= 0 ||
            x + width > framebufferWidth || y + height > framebufferHeight
        ) {
            throw VncException("Invalid framebuffer rectangle")
        }
    }

    private fun inflate(compressed: ByteArray, expected: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val out = ByteArray(expected)
            var offset = 0
            while (offset < expected && !inflater.finished()) {
                val read = try {
                    inflater.inflate(out, offset, expected - offset)
                } catch (e: DataFormatException) {
                    throw VncException("Invalid zlib rectangle: ${e.message}")
                }
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                offset += read
            }
            if (offset < expected) throw VncException("Short zlib rectangle")
            return out
        } finally {
            inflater.end()
        }
    }

    private fun readReason(din: DataInputStream): String {
        val length = din.readInt()
        if (length < 0 || length > MAX_STRING_BYTES) return ""
        val bytes = ByteArray(length)
        din.readFully(bytes)
        return String(bytes, Charsets.UTF_8).trim()
    }

    private fun desResponse(password: String, challenge: ByteArray): ByteArray {
        val key = ByteArray(8)
        val passwordBytes = password.toByteArray(Charsets.ISO_8859_1)
        for (index in 0 until minOf(8, passwordBytes.size)) {
            key[index] = reverseBits(passwordBytes[index])
        }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(challenge)
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

    private fun pixelToArgb(value: Int): Int {
        val red = (value shr 16) and 0xFF
        val green = (value shr 8) and 0xFF
        val blue = value and 0xFF
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun friendlyMessage(error: Exception): String {
        val message = error.message?.trim().orEmpty()
        return message.ifEmpty { "Connection failed (${error.javaClass.simpleName})" }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 12_000
        const val MAX_DIMENSION = 16_384
        const val MAX_PIXELS = 16_000_000L
        const val MAX_STRING_BYTES = 1_048_576
        const val MAX_ZLIB_BYTES = 32 * 1024 * 1024
        const val MAX_RRE_RECTS = 1_000_000
        val VERSION_REGEX = Regex("""RFB (\d{3})\.(\d{3})""")
    }
}
