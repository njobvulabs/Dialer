package com.example.util

import jcifs.CIFSContext
import jcifs.smb.SmbFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Minimal localhost HTTP proxy that streams a single SMB file to the in-app
 * media players (VideoView/MediaPlayer) with HTTP Range support so seeking
 * works without downloading the whole file first.
 *
 * It serves one active remote file (the currently-open Samba media). Start it
 * with [start], which returns the `http://127.0.0.1:<port>/stream` URL to hand
 * to the player, and call [stop] when the player closes (or a new file opens).
 */

object SambaMediaStream {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var socket: ServerSocket? = null
    private var serverJob: Job? = null

    @Volatile
    private var activeContext: CIFSContext? = null
    @Volatile
    private var activeUrl: String? = null

    val isRunning: Boolean get() = running.get()

    fun start(context: CIFSContext, url: String): String? {
        stop()
        // Validate the remote file is reachable and get its length early.
        val length = runCatching { SmbFile(url, context).length() }.getOrDefault(-1L)
        if (length < 0) return null
        return try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("127.0.0.1", 0))
            socket = server
            activeContext = context
            activeUrl = url
            running.set(true)
            serverJob = scope.launch {
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        val client = server.accept()
                        scope.launch { handle(client, url) }
                    } catch (_: Exception) {
                        if (running.get()) break
                    }
                }
            }
            buildString { append("http://127.0.0.1:").append(server.localPort).append("/stream") }
        } catch (_: Exception) {
            running.set(false)
            null
        }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        serverJob?.cancel()
        serverJob = null
        activeContext = null
        activeUrl = null
    }

    private fun handle(client: Socket, url: String) {
        runCatching {
            client.soTimeout = 30_000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val rawPath = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase(Locale.ROOT)] = line.substring(idx + 1).trim()
                }
            }

            val ctx = activeContext ?: return
            val file = SmbFile(url, ctx)
            val total = runCatching { file.length() }.getOrDefault(0L)
            val mime = StorageHelper.getMimeType(url.substringAfterLast('/'))

            var start = 0L
            var end = total - 1
            var partial = false
            headers["range"]?.let { rangeHeader ->
                if (rangeHeader.startsWith("bytes=")) {
                    val r = rangeHeader.removePrefix("bytes=").split("-")
                    val s = r[0].toLongOrNull()
                    val e = r.getOrNull(1)?.trim()?.toLongOrNull()
                    if (s != null) {
                        start = s.coerceIn(0, (total - 1).coerceAtLeast(0))
                        if (e != null && e >= start) end = e.coerceAtMost(total - 1)
                        partial = true
                    }
                }
            }

            outputStream(file, ctx, start, end)?.use { bodyInput ->
                if (partial) {
                    writeResponse(
                        output, 206, mime, bodyInput,
                        "Content-Range: bytes $start-$end/$total\r\nAccept-Ranges: bytes\r\n"
                    )
                } else {
                    writeResponse(
                        output, 200, mime, bodyInput,
                        "Accept-Ranges: bytes\r\n"
                    )
                }
            } ?: runCatching { client.close() }
        }.onFailure {
            runCatching { client.close() }
        }
    }

    /** Open the remote file, skip `start` bytes and stream at most `len` bytes. */
    private fun outputStream(
        file: SmbFile,
        ctx: CIFSContext,
        start: Long,
        end: Long
    ): java.io.InputStream? {
        val totalLen = runCatching { file.length() }.getOrDefault(-1L)
        if (totalLen < 0) return null
        val raw: java.io.InputStream = runCatching { file.inputStream }.getOrNull() ?: return null
        return object : java.io.InputStream() {
            private var remaining = (end - start + 1).coerceAtLeast(0)
            private var done = false
            override fun read(): Int {
                if (done || remaining <= 0) return -1
                val b = raw.read()
                if (b == -1) {
                    done = true
                    return -1
                }
                remaining--
                return b
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (done || remaining <= 0) return -1
                val n = raw.read(b, off, minOf(len.toLong(), remaining).toInt())
                if (n <= 0) {
                    done = true
                    return n
                }
                remaining -= n
                return n
            }
        }.also { stream ->
            SkipUtil.skip(stream, start)
        }
    }

    private fun writeResponse(output: java.io.OutputStream, code: Int, contentType: String, body: java.io.InputStream, extra: String) {
        runCatching {
            val status = when (code) {
                206 -> "Partial Content"
                200 -> "OK"
                404 -> "Not Found"
                else -> "OK"
            }
            val head =
                "HTTP/1.1 $code $status\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    extra +
                    "Connection: close\r\n\r\n"
            output.write(head.toByteArray())
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = body.read(buf)
                if (r <= 0) break
                output.write((Integer.toHexString(r) + "\r\n").toByteArray())
                output.write(buf, 0, r)
                output.write("\r\n".toByteArray())
            }
            output.write("0\r\n\r\n".toByteArray())
            output.flush()
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        var c: Int
        while (input.read().also { c = it } != -1) {
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return if (sb.isEmpty() && c == -1) null else sb.toString()
    }

    fun release() {
        scope.cancel()
    }
}

/** Skips bytes from a stream without allocating, honoring both skip() and read(). */
private object SkipUtil {
    fun skip(stream: java.io.InputStream, count: Long) {
        var toSkip = count
        val buf = ByteArray(8192)
        while (toSkip > 0) {
            val n = stream.read(buf, 0, minOf(buf.size.toLong(), toSkip).toInt())
            if (n <= 0) break
            toSkip -= n
        }
    }
}
