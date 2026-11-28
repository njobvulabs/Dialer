package com.example.util

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
/**
 * A lightweight, standalone HTTP server for local Wi-Fi / Hotspot file sharing.
 */
object WifiShareServer {

    const val PORT = 8870
    private const val TAG = "WifiShareServer"

    /** SharedPreferences file holding the persisted SAF tree root. */
    const val PREFS = "wifishare_saf"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var socket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val connectionCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val bytesTransferred = java.util.concurrent.atomic.AtomicLong(0L)
    val activeConnections: Int get() = connectionCount.get()
    val totalBytesTransferred: Long get() = bytesTransferred.get()

    @Volatile
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    var shareRoot: SafDocument? = null
        private set

    val isRunning: Boolean get() = running.get()

    /**
     * Resolves the active network IP address (works seamlessly on Wi-Fi and Mobile Hotspot AP mode).
     */
    fun getWifiIp(context: Context): String {
        // 1. Inspect network interfaces (most reliable for Hotspot AP and Wi-Fi interface detection)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                val candidateIps = mutableListOf<String>()
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback) continue
                    val name = intf.name.lowercase(Locale.ROOT)
                    val addrs = intf.inetAddresses
                    for (addr in addrs) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress.orEmpty()
                            if (host.isNotEmpty() && host != "127.0.0.1") {
                                if (name.contains("wlan") || name.contains("ap") || name.contains("softap") || name.contains("rndis") || name.contains("p2p")) {
                                    return host
                                }
                                candidateIps.add(host)
                            }
                        }
                    }
                }
                if (candidateIps.isNotEmpty()) return candidateIps.first()
            }
        } catch (_: Exception) {}

        // 2. Fallback to WifiManager
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo: WifiInfo? = wifiManager.connectionInfo
            val ip = wifiInfo?.ipAddress ?: 0
            if (ip != 0) {
                String.format(Locale.US, "%d.%d.%d.%d", (ip and 0xFF), (ip shr 8) and 0xFF, (ip shr 16) and 0xFF, (ip shr 24) and 0xFF)
            } else {
                fallbackIp()
            }
        } catch (_: Exception) {
            fallbackIp()
        }
    }

    private fun fallbackIp(): String {
        return runCatching {
            val s = java.net.InetAddress.getLocalHost()
            if (s is Inet4Address) s.hostAddress.orEmpty() else ""
        }.getOrElse { "" }
    }

    fun url(context: Context): String {
        val ip = getWifiIp(context)
        return "http://$ip:$PORT"
    }

    fun start(context: Context, treeUri: Uri): Boolean {
        if (running.get()) return true
        val root = SafDocument.fromTreeUri(context.contentResolver, treeUri) ?: return false
        if (!root.isDirectory) return false
        shareRoot = root
        acquireWifiLock(context)
        return try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(PORT))
            socket = server
            running.set(true)
            serverJob = scope.launch {
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        val client = server.accept()
                        scope.launch { handle(client) }
                    } catch (_: Exception) {
                        if (running.get()) break
                    }
                }
            }
            true
        } catch (_: Exception) {
            running.set(false)
            releaseWifiLock()
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        serverJob?.cancel()
        serverJob = null
        shareRoot = null
        releaseWifiLock()
    }

    /**
     * Hold a Wi-Fi lock for as long as the server is up.
     *
     * Without it, letting the screen go dark lets the ROM power-save the Wi-Fi
     * radio mid-transfer. That is the single most likely reason a transfer "just
     * stopped": the sender's connection dies, this end sees a truncated body, and
     * (before the fix above) the file was still reported as received. WAKE_LOCK is
     * already granted in the manifest, so this costs no new permission.
     */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock(context: Context) {
        if (wifiLock != null) return
        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nj:wifi-share").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    private fun handle(client: Socket) {
        client.use { c ->
            c.soTimeout = 60_000
            try {
                val input = c.getInputStream()
                val requestLine = readLineFromStream(input)
                if (requestLine.isEmpty()) return

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLineFromStream(input)
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx != -1) {
                        headers[line.substring(0, idx).trim().lowercase(Locale.ROOT)] = line.substring(idx + 1).trim()
                    }
                }

                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val rawPath = parts[1]
                val path = URLDecoder.decode(rawPath.substringBefore("?"), "UTF-8")
                val query = rawPath.substringAfter("?", "")

                when (method) {
                    "GET" -> handleGet(c, path, query)
                    "POST" -> handlePost(c, input, path, query, headers)
                    else -> respond(c, 405, "text/plain", "Method not allowed".toByteArray())
                }
            } catch (_: Exception) {
                runCatching { respond(c, 400, "text/plain", "Bad request".toByteArray()) }
            }
        }
    }

    private fun readLineFromStream(input: InputStream): String {
        val baos = ByteArrayOutputStream()
        var c: Int
        while (input.read().also { c = it } != -1) {
            if (c == '\n'.code) break
            if (c != '\r'.code) {
                baos.write(c)
            }
        }
        return baos.toString("UTF-8")
    }

    private fun handleGet(client: Socket, path: String, query: String) {
        val root = shareRoot ?: return respond(client, 503, "text/plain", "No share directory".toByteArray())
        when {
            path == "/" || path.isEmpty() -> respond(client, 200, "text/html; charset=utf-8", pageHtml().toByteArray())
            path == "/manifest.json" -> respond(
                client, 200, "application/manifest+json; charset=utf-8",
                PwaAssets.manifest("Wi-Fi Transfer", "Wi-Fi Transfer", "#0f172a", "#0f172a").toByteArray()
            )
            path == "/sw.js" -> respond(
                client, 200, "application/javascript; charset=utf-8",
                PwaAssets.swJs("nj-wifi-v2").toByteArray()
            )
            path == "/icons/icon-192.png" || path == "/favicon.ico" ->
                respond(client, 200, "image/png", PwaAssets.pngIcon(192, 0x0F172A, 0x6366F1))
            path == "/icons/icon-512.png" ->
                respond(client, 200, "image/png", PwaAssets.pngIcon(512, 0x0F172A, 0x6366F1))
            path == "/api/list" -> respond(client, 200, "application/json; charset=utf-8", listJson(root, query).toByteArray())
            path == "/api/qr" -> {
                val hostIp = client.localAddress?.hostAddress.orEmpty().ifEmpty { "127.0.0.1" }
                val svg = QrCodeGenerator.generateSvg("http://$hostIp:$PORT")
                respond(client, 200, "image/svg+xml", svg.toByteArray())
            }
            path.startsWith("/files/") -> serveFile(client, root, path.removePrefix("/files/"))
            else -> respond(client, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun handlePost(client: Socket, input: InputStream, path: String, query: String, headers: Map<String, String>) {
        val root = shareRoot ?: return respond(client, 503, "text/plain", "No share directory".toByteArray())
        if (path != "/upload") {
            respond(client, 404, "text/plain", "Not found".toByteArray())
            return
        }
        val rawName = query.substringAfter("name=", "").substringBefore("&").let { URLDecoder.decode(it, "UTF-8") }
        val rawDir = query.substringAfter("d=", "").let { URLDecoder.decode(it, "UTF-8") }
        val safeName = sanitizeName(rawName)
        if (safeName.isEmpty()) {
            respond(client, 400, "text/plain", "Missing or invalid filename".toByteArray())
            return
        }
        val dir = resolveInside(root, rawDir)
        if (dir == null) {
            respond(client, 400, "text/plain", "Invalid target directory".toByteArray())
            return
        }
        val target = dir.createFile(StorageHelper.getMimeType(safeName), safeName)
        val output = target?.openOutputStream("w")
        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L

        try {
            if (output == null) throw java.io.IOException("cannot open output")
            var received = 0L
            output.use { out ->
                if (contentLength >= 0) {
                    var remaining = contentLength
                    val buffer = ByteArray(8192)
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        remaining -= read
                        received += read
                    }
                } else {
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        received += read
                    }
                }
            }
            // A short body (screen slept, Wi-Fi dropped, user hit back in the
            // browser) used to fall through here as a success: the loop broke on
            // EOF, we answered ok:true, and both the sender and this list were
            // left believing a truncated file was complete. A declared
            // content-length that was not fully received is a failure, and the
            // half-written file is removed rather than kept as a silent casualty.
            if (contentLength >= 0 && received != contentLength) {
                throw java.io.IOException("incomplete upload: $received of $contentLength bytes")
            }
            respond(client, 200, "application/json", "{\"ok\":true,\"name\":\"${escapeJson(safeName)}\"}".toByteArray())
        } catch (_: Exception) {
            // Delete the partial target so a failed upload cannot masquerade as a
            // complete one on the next listing.
            runCatching { target?.delete() }
            respond(client, 500, "text/plain", "Upload failed".toByteArray())
        }
    }

    private fun serveFile(client: Socket, root: SafDocument, namePath: String) {
        val segments = namePath.split("/").filter { it.isNotEmpty() && it != "." && it != ".." }
        if (segments.isEmpty()) {
            respond(client, 400, "text/plain", "Bad filename".toByteArray())
            return
        }
        var current: SafDocument = root
        for (i in 0 until segments.size - 1) {
            val seg = sanitizeName(segments[i])
            if (seg.isEmpty()) {
                respond(client, 400, "text/plain", "Bad filename".toByteArray())
                return
            }
            val next = current.findChildDir(seg)
            if (next == null) {
                respond(client, 404, "text/plain", "File not found".toByteArray())
                return
            }
            current = next
        }
        val name = sanitizeName(segments.last())
        if (name.isEmpty()) {
            respond(client, 400, "text/plain", "Bad filename".toByteArray())
            return
        }
        val file = current.findChildFile(name)
        if (file == null || !file.exists) {
            respond(client, 404, "text/plain", "File not found".toByteArray())
            return
        }
        // Navigation above is child-document based, so we can never escape the
        // share root (no path traversal is possible).
        val displayName = file.name ?: name
        try {
            val mime = StorageHelper.getMimeType(displayName)
            val safeDispositionName = displayName.filterNot { it == '\r' || it == '\n' }
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $mime\r\n")
                append("Content-Length: ${file.length}\r\n")
                append("Content-Disposition: attachment; filename=\"$safeDispositionName\"\r\n")
                append("Connection: close\r\n\r\n")
            }
            val ins = file.openInputStream() ?: throw java.io.IOException("cannot read file")
            client.getOutputStream().also { out ->
                out.write(headers.toByteArray())
                out.flush()
                ins.use { it.copyTo(out) }
                out.flush()
            }
        } catch (_: Exception) {
            // Client disconnect during transfer
        }
    }

    private fun respond(client: Socket, code: Int, contentType: String, body: ByteArray) {
        runCatching {
            client.getOutputStream().use { out ->
                val status = when (code) {
                    200 -> "OK"
                    400 -> "Bad Request"
                    404 -> "Not Found"
                    405 -> "Method Not Allowed"
                    500 -> "Internal Server Error"
                    503 -> "Service Unavailable"
                    else -> "Error"
                }
                out.write(("HTTP/1.1 $code $status\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(body)
                out.flush()
            }
        }
    }

    private fun sanitizeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        if (base.isEmpty() || base == "." || base == "..") return ""
        return base.filterNot { it == '\u0000' || it == '/' || it == '\\' }.take(200)
    }

    private fun resolveInside(root: SafDocument, rel: String): SafDocument? {
        if (rel.isBlank()) return root
        var current = root
        // Resolve strictly by matching named children — no path traversal possible.
        for (seg in rel.trimStart('/').split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") return null
            val next = current.findChildDir(seg) ?: return null
            current = next
        }
        return current
    }

    private fun listJson(root: SafDocument, query: String): String {
        val rel = URLDecoder.decode(query.substringAfter("d=", ""), "UTF-8")
        val current = resolveInside(root, rel) ?: root
        val sort = query.substringAfter("sort=", "name").substringBefore("&")
        val order = query.substringAfter("order=", "asc").substringBefore("&")

        val children = current.listChildren()
        val folders = children.filter { it.isDirectory }
            .sortedWith(compareBy({ it.name?.lowercase(Locale.ROOT).orEmpty() }))
        val files = children.filter { !it.isDirectory }.let { list ->
            when (sort) {
                "size" -> list.sortedBy { it.length }
                "date" -> list.sortedBy { it.lastModified }
                else -> list.sortedBy { it.name?.lowercase(Locale.ROOT).orEmpty() }
            }
        }
        val ordered = if (order == "desc") files.asReversed() else files

        val sb = StringBuilder("{\"d\":")
        sb.append(json(if (rel.isBlank()) "" else rel.trim('/'))).append(",\"folders\":[")
        folders.forEachIndexed { i, f ->
            if (i > 0) sb.append(",")
            val modified = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(f.lastModified))
            sb.append("{\"name\":").append(json(f.name ?: ""))
                .append(",\"modified\":").append(json(modified))
                .append("}")
        }
        sb.append("],\"files\":[")
        ordered.forEachIndexed { i, f ->
            if (i > 0) sb.append(",")
            val modified = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(f.lastModified))
            sb.append("{\"name\":").append(json(f.name ?: ""))
                .append(",\"size\":").append(f.length)
                .append(",\"modified\":").append(json(modified))
                .append(",\"kind\":").append(json(fileKind(f.name ?: "")))
                .append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun fileKind(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif" -> "image"
            "mp4", "mkv", "webm", "avi", "3gp", "mov", "flv", "ts", "m4v" -> "video"
            "mp3", "wav", "ogg", "m4a", "flac", "opus", "wma", "aac", "amr" -> "audio"
            "pdf" -> "pdf"
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> "archive"
            "txt", "md", "log", "json", "xml", "csv", "html", "css", "js", "kt", "java" -> "text"
            else -> "file"
        }
    }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun json(s: String): String = "\"" + escapeJson(s) + "\""

    private fun pageHtml(): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
        <title>Wi-Fi Transfer</title>
        ${PwaAssets.pwaHead("Wi-Fi Transfer", "#0f172a")}
        <style>
          :root { color-scheme: dark light; }
          * { box-sizing: border-box; }
          body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            margin: 0; background: #0f172a; color: #f8fafc;
          }
          .wrap { max-width: 900px; margin: 0 auto; padding: 16px; }
          .header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
          .brand { display: flex; align-items: center; gap: 12px; }
          .brand img { width: 44px; height: 44px; border-radius: 10px; }
          .title { font-size: 1.3rem; font-weight: 700; margin: 0; }
          .sub { color: #94a3b8; font-size: 0.82rem; }
          .badge { background: #22c55e22; color: #4ade80; border: 1px solid #22c55e44; padding: 4px 10px; border-radius: 20px; font-size: 0.78rem; font-weight: 600; }
          .spacer { flex: 1; }
          .card { background: #1e293b; border-radius: 16px; padding: 16px; margin-bottom: 16px; border: 1px solid #334155; }
          .card h2 { margin: 0 0 12px 0; font-size: 1rem; display: flex; justify-content: space-between; align-items: center; gap: 8px; }
          .drop-zone {
            border: 2px dashed #475569; border-radius: 12px; padding: 26px 16px;
            text-align: center; color: #94a3b8; transition: all 0.2s ease; cursor: pointer;
          }
          .drop-zone.active { border-color: #6366f1; background: #6366f115; color: #818cf8; }
          .btn {
            border: 0; border-radius: 10px; padding: 10px 16px; font-size: 0.9rem; font-weight: 600;
            cursor: pointer; background: #6366f1; color: #fff; transition: background 0.2s;
            display: inline-flex; align-items: center; gap: 6px; text-decoration: none; border: 1px solid transparent;
          }
          .btn:hover { background: #4f46e5; }
          .btn-ghost { background: #334155; color: #e2e8f0; }
          .btn-ghost:hover { background: #475569; }
          .btn-danger { background: #ef444422; color: #f87171; border: 1px solid #ef444444; padding: 5px 9px; font-size: 0.8rem; }
          .btn-danger:hover { background: #ef4444; color: #fff; }
          .btn-sm { padding: 6px 10px; font-size: 0.8rem; }
          .toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
          .toolbar input[type=text] {
            flex: 1; min-width: 160px; padding: 10px 14px; border-radius: 10px; border: 1px solid #334155;
            background: #0f172a; color: #fff; font-size: 0.9rem; outline: none;
          }
          .toolbar input[type=text]:focus { border-color: #6366f1; }
          .breadcrumb { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; margin-bottom: 12px; font-size: 0.85rem; }
          .crumb { color: #818cf8; cursor: pointer; text-decoration: none; }
          .crumb:hover { text-decoration: underline; }
          .crumb.sep { color: #475569; }
          table { width: 100%; border-collapse: collapse; }
          th { text-align: left; color: #64748b; font-size: 0.78rem; text-transform: uppercase; padding: 8px 4px; border-bottom: 1px solid #334155; cursor: pointer; user-select: none; }
          th:hover { color: #94a3b8; }
          td { padding: 10px 4px; border-bottom: 1px solid #334155; vertical-align: middle; font-size: 0.9rem; }
          td.name { word-break: break-all; }
          td.thumb { width: 46px; padding-right: 10px; }
          .thumb-box { width: 40px; height: 40px; border-radius: 8px; background: #0f172a; display: flex; align-items: center; justify-content: center; overflow: hidden; border: 1px solid #334155; }
          .thumb-box img { width: 100%; height: 100%; object-fit: cover; }
          .thumb-box .emoji { font-size: 20px; }
          a.file-link { color: #818cf8; text-decoration: none; font-weight: 600; }
          a.file-link:hover { text-decoration: underline; }
          .actions { display: flex; gap: 6px; justify-content: flex-end; align-items: center; }
          .qr-modal { display: none; position: fixed; inset: 0; background: #000000aa; align-items: center; justify-content: center; z-index: 100; padding: 16px; }
          .qr-box { background: #1e293b; padding: 24px; border-radius: 16px; text-align: center; max-width: 300px; width: 100%; border: 1px solid #334155; }
          .qr-box img { width: 200px; height: 200px; border-radius: 8px; margin: 12px 0; }
          .progress-wrapper { margin-top: 14px; display: none; }
          .bar { height: 8px; background: #334155; border-radius: 4px; overflow: hidden; }
          .fill { height: 100%; width: 0%; background: #6366f1; transition: width 0.15s; }
          .stats { display: flex; justify-content: space-between; font-size: 0.85rem; color: #94a3b8; margin-top: 6px; }
          .empty { text-align: center; color: #64748b; padding: 30px 10px; font-size: 0.9rem; }
          .seg { display: flex; align-items: center; gap: 4px; }
          @media (max-width: 600px) {
            .wrap { padding: 12px; }
            .card { padding: 12px; }
            th:nth-child(3), td:nth-child(3) { display: none; }
            .header .badge { display: none; }
          }
        </style>
        </head>
        <body>
          <div class="wrap">
            <div class="header">
              <div class="brand">
                <img src="/icons/icon-192.png" alt="">
                <div>
                  <h1 class="title">Wi-Fi Transfer</h1>
                  <div class="sub">Direct local transfer &bull; No internet required</div>
                </div>
              </div>
              <span class="badge">Online</span>
              <span class="spacer"></span>
              <button class="btn btn-ghost btn-sm" onclick="showQr()">QR</button>
              <button class="btn btn-sm install-btn" id="installApp" style="display:none">Install App</button>
            </div>

            <div class="card">
              <h2><span>Send Files</span><button class="btn" onclick="document.getElementById('fileInput').click()">Choose Files</button></h2>
              <input type="file" id="fileInput" multiple hidden onchange="handleFiles(this.files)">
              <div class="drop-zone" id="dropZone">Drag &amp; drop files here or click Choose Files</div>
              <div class="progress-wrapper" id="progressWrapper">
                <div class="bar"><div class="fill" id="fill"></div></div>
                <div class="stats"><span id="statusText">Uploading...</span><span id="speedText">0 KB/s</span></div>
              </div>
            </div>

            <div class="card">
              <h2><span>Shared Files (<span id="fileCount">0</span>)</span></h2>
              <div class="breadcrumb" id="breadcrumb"></div>
              <div class="toolbar">
                <input type="text" id="searchInput" placeholder="Search files & folders..." oninput="renderList()">
                <button class="btn btn-ghost btn-sm" onclick="toggleSort('name')" id="sortName">Name</button>
                <button class="btn btn-ghost btn-sm" onclick="toggleSort('size')" id="sortSize">Size</button>
                <button class="btn btn-ghost btn-sm" onclick="toggleSort('date')" id="sortDate">Date</button>
                <button class="btn btn-ghost btn-sm" onclick="toggleOrder()" id="orderBtn">&uarr; Asc</button>
              </div>
              <table>
                <thead><tr><th></th><th>Name</th><th>Size</th><th style="width:110px;text-align:right">Action</th></tr></thead>
                <tbody id="fileList"><tr><td colspan="4" style="text-align:center;color:#64748b;padding:20px;">Loading...</td></tr></tbody>
              </table>
            </div>
          </div>

          <div class="qr-modal" id="qrModal" onclick="hideQr()">
            <div class="qr-box" onclick="event.stopPropagation()">
              <h3 style="margin:0;">Scan to Connect</h3>
              <img src="/api/qr" alt="QR Code">
              <p style="font-size:0.85rem;color:#94a3b8;margin:0 0 12px 0;">Scan with camera on another phone</p>
              <button class="btn" style="width:100%" onclick="hideQr()">Close</button>
            </div>
          </div>

        <script>
          let state = { d: '', sort: 'name', order: 'asc', all: { folders: [], files: [] } };
          const dropZone = document.getElementById('dropZone');
          const progressWrapper = document.getElementById('progressWrapper');
          const fill = document.getElementById('fill');
          const statusText = document.getElementById('statusText');
          const speedText = document.getElementById('speedText');

          ['dragenter', 'dragover'].forEach(e => dropZone.addEventListener(e, ev => { ev.preventDefault(); dropZone.classList.add('active'); }));
          ['dragleave', 'drop'].forEach(e => dropZone.addEventListener(e, ev => { ev.preventDefault(); dropZone.classList.remove('active'); }));
          dropZone.addEventListener('drop', ev => { if (ev.dataTransfer.files.length) handleFiles(ev.dataTransfer.files); });
          dropZone.addEventListener('click', () => document.getElementById('fileInput').click());

          function handleFiles(files) {
            if (!files.length) return;
            let i = 0;
            progressWrapper.style.display = 'block';
            function uploadNext() {
              if (i >= files.length) {
                statusText.textContent = 'Upload complete!';
                speedText.textContent = '';
                setTimeout(() => progressWrapper.style.display = 'none', 2500);
                loadList();
                return;
              }
              const file = files[i++];
              const startTime = Date.now();
              const xhr = new XMLHttpRequest();
              xhr.open('POST', '/upload?name=' + encodeURIComponent(file.name) + '&d=' + encodeURIComponent(state.d), true);
              xhr.upload.onloadstart = () => {
                fill.style.width = '2%';
                statusText.textContent = 'Uploading ' + file.name + '...';
              };
              xhr.upload.onprogress = (e) => {
                if (e.lengthComputable) {
                  const pct = Math.round((e.loaded / e.total) * 100);
                  fill.style.width = pct + '%';
                  const elapsed = (Date.now() - startTime) / 1000;
                  if (elapsed > 0) speedText.textContent = fmtSpeed(e.loaded / elapsed);
                  statusText.textContent = 'Uploading ' + file.name + ' (' + pct + '%)';
                }
              };
              xhr.onload = () => { fill.style.width = '100%'; uploadNext(); };
              xhr.onerror = () => { statusText.textContent = 'Failed: ' + file.name; uploadNext(); };
              xhr.send(file);
            }
            uploadNext();
          }

          function loadList() {
            const url = '/api/list?d=' + encodeURIComponent(state.d) + '&sort=' + state.sort + '&order=' + state.order;
            fetch(url).then(r => r.json()).then(d => {
              state.all = d;
              const total = d.folders.length + d.files.length;
              document.getElementById('fileCount').textContent = total;
              renderList();
              renderBreadcrumb();
            }).catch(() => {});
          }

          function renderBreadcrumb() {
            const bc = document.getElementById('breadcrumb');
            if (!state.d) { bc.innerHTML = ''; return; }
            const parts = state.d.split('/');
            let acc = '';
            let html = '<span class="crumb" onclick="goDir(\'\')">Root</span>';
            parts.forEach((p, i) => {
              acc += (acc ? '/' : '') + p;
              html += '<span class="crumb sep">/</span><span class="crumb" onclick="goDir(\'' + escapeJs(acc) + '\')">' + escapeHtml(p) + '</span>';
            });
            bc.innerHTML = html;
          }

          function goDir(d) { state.d = d; loadList(); }

          function toggleSort(s) {
            if (state.sort === s) { state.order = state.order === 'asc' ? 'desc' : 'asc'; }
            state.sort = s;
            loadList();
          }
          function toggleOrder() {
            state.order = state.order === 'asc' ? 'desc' : 'asc';
            document.getElementById('orderBtn').innerHTML = state.order === 'asc' ? '&uarr; Asc' : '&darr; Desc';
            loadList();
          }

          function renderList() {
            const query = document.getElementById('searchInput').value.toLowerCase();
            const folders = state.all.folders.filter(f => f.name.toLowerCase().includes(query));
            const files = state.all.files.filter(f => f.name.toLowerCase().includes(query));
            const tbody = document.getElementById('fileList');

            if (!folders.length && !files.length) {
              tbody.innerHTML = '<tr><td colspan="4" class="empty">' + (query ? 'No matches' : 'No files here') + '</td></tr>';
              return;
            }
            let html = '';
            folders.forEach(function(f) {
              html += '<tr>' +
                '<td class="thumb"><div class="thumb-box"><span class="emoji">&#128193;</span></div></td>' +
                '<td class="name"><a class="file-link" href="#" onclick="goDir(\'' + escapeJs(state.d ? state.d + '/' + f.name : f.name) + '\');return false;">' + escapeHtml(f.name) + '/</a><br><span style="color:#64748b;font-size:0.78rem">Folder</span></td>' +
                '<td style="color:#94a3b8;font-size:0.85rem">&mdash;</td>' +
                '<td style="text-align:right"><div class="actions"></div></td>' +
              '</tr>';
            });
            files.forEach(function(f) {
              const rel = state.d ? state.d + '/' + f.name : f.name;
              const thumb = thumbHtml(f);
              html += '<tr>' +
                '<td class="thumb"><div class="thumb-box">' + thumb + '</div></td>' +
                '<td class="name"><a class="file-link" href="/files/' + encodeURIComponent(rel) + '" download="' + escapeHtml(f.name) + '">' + escapeHtml(f.name) + '</a></td>' +
                '<td style="color:#94a3b8;font-size:0.85rem">' + fmt(f.size) + '</td>' +
                '<td style="text-align:right"><div class="actions">' +
                  '<a class="btn btn-sm" style="padding:5px 9px;font-size:0.8rem" href="/files/' + encodeURIComponent(rel) + '" download="' + escapeHtml(f.name) + '">Get</a>' +
                '</div></td>' +
              '</tr>';
            });
            tbody.innerHTML = html;
          }

          function thumbHtml(f) {
            if (f.kind === 'image') {
              return '<img src="/files/' + encodeURIComponent(state.d ? state.d + '/' + f.name : f.name) + '" alt="" loading="lazy">';
            }
            const emoji = { video: '&#127916;', audio: '&#127925;', pdf: '&#128196;', archive: '&#128230;', text: '&#128220;', file: '&#128462;' }[f.kind] || '&#128462;';
            return '<span class="emoji">' + emoji + '</span>';
          }

          function fmt(b) {
            if (b >= 1048576) return (b / 1048576).toFixed(1) + ' MB';
            if (b >= 1024) return (b / 1024).toFixed(1) + ' KB';
            return b + ' B';
          }
          function fmtSpeed(bps) {
            if (bps >= 1048576) return (bps / 1048576).toFixed(1) + ' MB/s';
            return (bps / 1024).toFixed(0) + ' KB/s';
          }
          function escapeHtml(s) {
            return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
          }
          function escapeJs(s) {
            return s.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
          }
          function showQr() { document.getElementById('qrModal').style.display = 'flex'; }
          function hideQr() { document.getElementById('qrModal').style.display = 'none'; }

          ${PwaAssets.toastJs()}
          ${PwaAssets.pwaScript()}
          loadList();
          setInterval(loadList, 4000);
        </script>
        </body>
        </html>
    """.trimIndent()
}

/**
 * Pure Kotlin QR Code Generator (Byte Mode, Version 1-4 with Reed-Solomon Error Correction).
 */
