package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.util.BtTransferManager
import com.example.util.BtTransferPhase
import com.example.util.BtTransferState
import com.example.util.SafDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max

@SuppressLint("MissingPermission")
class BtTransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var epoch = 0
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var activeSocket: BluetoothSocket? = null
    @Volatile private var serverJob: Job? = null
    @Volatile private var sendJob: Job? = null
    @Volatile private var foregroundStarted = false
    @Volatile private var lastNotificationAt = 0L
    @Volatile private var notificationTitle = "Bluetooth transfer"
    @Volatile private var notificationText = "Ready"
    @Volatile private var notificationProgress = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                BtTransferManager.update(BtTransferState())
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_SEND -> {
                ensureForeground("Connecting", "Preparing to send a file")
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                val name = intent.getStringExtra(EXTRA_NAME) ?: "file"
                val peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: address ?: "Device"
                val size = intent.getLongExtra(EXTRA_SIZE, -1L)
                val mime = intent.getStringExtra(EXTRA_MIME) ?: "application/octet-stream"
                if (address.isNullOrBlank() || uri == null) {
                    BtTransferManager.fail("Missing transfer details")
                    stopEverything()
                    return START_NOT_STICKY
                }
                cancelWork()
                val token = epoch
                sendJob = scope.launch {
                    runSend(token, address, peerName, uri, name, size, mime)
                }
            }
            else -> {
                ensureForeground("Waiting for a sender", "Keep both phones close and paired")
                if (serverJob?.isActive != true) {
                    cancelWork()
                    val token = epoch
                    serverJob = scope.launch { runServer(token) }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureForeground(title: String, text: String) {
        notificationTitle = title
        notificationText = text
        if (!foregroundStarted) {
            startForeground(NOTIF_ID, buildNotification())
            foregroundStarted = true
        } else {
            postNotification(force = true)
        }
    }

    private fun cancelWork() {
        epoch++
        runCatching { serverSocket?.close() }
        runCatching { activeSocket?.close() }
        serverSocket = null
        activeSocket = null
        serverJob?.cancel()
        sendJob?.cancel()
        serverJob = null
        sendJob = null
    }

    private fun stopEverything() {
        epoch++
        runCatching { serverSocket?.close() }
        runCatching { activeSocket?.close() }
        serverSocket = null
        activeSocket = null
        serverJob?.cancel()
        sendJob?.cancel()
        serverJob = null
        sendJob = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runServer(startEpoch: Int) {
        val adapter = BtTransferManager.adapter(this)
        if (adapter == null) {
            failAndStop(startEpoch, "Bluetooth is not available on this device")
            return
        }
        while (startEpoch == epoch) {
            var server: BluetoothServerSocket? = null
            try {
                if (!adapter.isEnabled) {
                    failAndStop(startEpoch, "Turn on Bluetooth to receive files")
                    return
                }
                BtTransferManager.update(
                    BtTransferState(
                        phase = BtTransferPhase.WAITING,
                        message = "Waiting for a sender",
                    )
                )
                notificationTitle = "Waiting for a sender"
                notificationText = "Keep both phones close and paired"
                notificationProgress = -1
                postNotification(force = true)

                server = openServerSocket(adapter)
                if (server == null) {
                    failAndStop(startEpoch, "Could not open a Bluetooth receive socket")
                    return
                }
                serverSocket = server
                val socket = server.accept()
                runCatching { server.close() }
                serverSocket = null
                if (socket == null) continue
                activeSocket = socket
                receiveOne(startEpoch, socket)
                activeSocket = null
                runCatching { socket.close() }
                if (startEpoch != epoch) return
                delay(2000)
            } catch (error: IOException) {
                if (startEpoch != epoch) return
                runCatching { server?.close() }
                serverSocket = null
                failAndStop(startEpoch, "Bluetooth receive failed: ${friendlyMessage(error)}")
                return
            } catch (error: Exception) {
                if (startEpoch != epoch) return
                runCatching { server?.close() }
                serverSocket = null
                failAndStop(startEpoch, "Bluetooth receive failed: ${friendlyMessage(error)}")
                return
            } finally {
                runCatching { server?.close() }
            }
        }
    }

    private fun openServerSocket(adapter: BluetoothAdapter): BluetoothServerSocket? {
        val secure = runCatching {
            adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
        }.getOrNull()
        if (secure != null) return secure
        return runCatching {
            adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
        }.getOrNull()
    }

    private suspend fun receiveOne(startEpoch: Int, socket: BluetoothSocket) {
        val peer = peerName(socket)
        var destination: ReceiveSink? = null
        try {
            val input = DataInputStream(BufferedInputStream(socket.inputStream))
            val magic = input.readUTF()
            if (magic != PROTOCOL_MAGIC) {
                failAndStop(startEpoch, "The other app is not compatible")
                return
            }
            val headerLength = input.readInt()
            if (headerLength <= 0 || headerLength > MAX_HEADER_BYTES) {
                failAndStop(startEpoch, "The incoming file header is invalid")
                return
            }
            val headerBytes = ByteArray(headerLength)
            input.readFully(headerBytes)
            val header = JSONObject(String(headerBytes, Charsets.UTF_8))
            val rawName = header.optString("name", "received.bin")
            val name = sanitizeFileName(rawName)
            val size = header.optLong("size", -1L)
            val mime = header.optString("mime", "application/octet-stream")
                .ifBlank { "application/octet-stream" }

            BtTransferManager.progress(
                BtTransferPhase.RECEIVING,
                peer,
                name,
                0L,
                max(size, 0L),
            )
            notificationTitle = "Receiving $name"
            notificationText = "From $peer"
            notificationProgress = if (size > 0L) 0 else -1
            postNotification(force = true)

            destination = openReceiveSink(name, mime)
            val digest = MessageDigest.getInstance("SHA-256")
            var transferred = 0L
            var lastUpdate = 0L

            destination.output.use { rawOut ->
                val output = BufferedOutputStream(rawOut, BUFFER_SIZE)
                if (size >= 0L) {
                    val buffer = ByteArray(BUFFER_SIZE)
                    var remaining = size
                    while (remaining > 0L && startEpoch == epoch) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) throw IOException("Sender closed the connection early")
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        transferred += read
                        remaining -= read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                            lastUpdate = now
                            publishProgress(BtTransferPhase.RECEIVING, peer, name, transferred, size)
                        }
                    }
                } else {
                    while (startEpoch == epoch) {
                        val chunk = input.readInt()
                        if (chunk == 0) break
                        if (chunk < 0 || chunk > MAX_CHUNK_BYTES) {
                            throw IOException("Incoming chunk is too large")
                        }
                        val buffer = ByteArray(chunk)
                        input.readFully(buffer)
                        output.write(buffer)
                        digest.update(buffer)
                        transferred += chunk
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                            lastUpdate = now
                            publishProgress(BtTransferPhase.RECEIVING, peer, name, transferred, -1L)
                        }
                    }
                }
                output.flush()
            }

            val expected = ByteArray(32)
            input.readFully(expected)
            val actual = digest.digest()
            val verified = expected.contentEquals(actual)
            val output = DataOutputStream(BufferedOutputStream(socket.outputStream))
            output.writeByte(if (verified) 1 else 0)
            output.flush()

            if (!verified) {
                destination.delete()
                destination = null
                failAndStop(startEpoch, "The file was corrupted in transit")
                return
            }

            val savedPath = destination.displayPath
            if (!destination.complete()) {
                destination.delete()
                destination = null
                failAndStop(startEpoch, "The file could not be saved")
                return
            }
            destination = null

            BtTransferManager.update(
                BtTransferState(
                    phase = BtTransferPhase.COMPLETE,
                    peerName = peer,
                    fileName = name,
                    transferred = transferred,
                    total = transferred,
                    message = "Saved to $savedPath",
                )
            )
            notificationTitle = "Saved $name"
            notificationText = "From $peer"
            notificationProgress = 100
            postNotification(force = true)
        } catch (timeout: SocketTimeoutException) {
            if (startEpoch == epoch) BtTransferManager.fail("The transfer timed out")
        } catch (error: IOException) {
            if (startEpoch == epoch) BtTransferManager.fail("Receive failed: ${friendlyMessage(error)}")
            destination?.delete()
        } catch (error: Exception) {
            if (startEpoch == epoch) BtTransferManager.fail("Receive failed: ${friendlyMessage(error)}")
            destination?.delete()
        }
    }

    private suspend fun runSend(
        startEpoch: Int,
        address: String,
        peerName: String,
        uri: Uri,
        name: String,
        size: Long,
        mime: String,
    ) {
        val adapter = BtTransferManager.adapter(this)
        if (adapter == null) {
            failAndStop(startEpoch, "Bluetooth is not available on this device")
            return
        }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            failAndStop(startEpoch, "This device is not paired with the selected phone")
            return
        }
        if (!adapter.isEnabled) {
            failAndStop(startEpoch, "Turn on Bluetooth to send files")
            return
        }
        BtTransferManager.progress(BtTransferPhase.CONNECTING, peerName, name, 0L, max(size, 0L))
        notificationTitle = "Connecting to $peerName"
        notificationText = "Preparing $name"
        notificationProgress = -1
        postNotification(force = true)

        val input = runCatching { contentResolver.openInputStream(uri) }.getOrNull()
        if (input == null) {
            failAndStop(startEpoch, "Could not open the selected file")
            return
        }
        var socket: BluetoothSocket? = null
        try {
            runCatching { adapter.cancelDiscovery() }
            socket = connectSocket(adapter, device)
            if (socket == null) {
                failAndStop(startEpoch, "Could not connect. Make sure the other phone is receiving and nearby.")
                return
            }
            activeSocket = socket
            val output = DataOutputStream(BufferedOutputStream(socket.outputStream))
            output.writeUTF(PROTOCOL_MAGIC)
            val header = JSONObject()
                .put("name", name)
                .put("size", size)
                .put("mime", mime)
                .put("sender", BtTransferManager.localName(this) ?: Build.MODEL)
            val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
            output.writeInt(headerBytes.size)
            output.write(headerBytes)
            output.flush()

            BtTransferManager.progress(BtTransferPhase.SENDING, peerName, name, 0L, max(size, 0L))
            notificationTitle = "Sending $name"
            notificationText = "To $peerName"
            notificationProgress = if (size > 0L) 0 else -1
            postNotification(force = true)

            val digest = MessageDigest.getInstance("SHA-256")
            var transferred = 0L
            var lastUpdate = 0L
            input.use { rawIn ->
                val buffer = ByteArray(BUFFER_SIZE)
                if (size >= 0L) {
                    var remaining = size
                    while (remaining > 0L && startEpoch == epoch) {
                        val read = rawIn.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) throw IOException("The selected file ended early")
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        transferred += read
                        remaining -= read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                            lastUpdate = now
                            publishProgress(BtTransferPhase.SENDING, peerName, name, transferred, size)
                        }
                    }
                } else {
                    while (startEpoch == epoch) {
                        val read = rawIn.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.writeInt(read)
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        transferred += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                            lastUpdate = now
                            publishProgress(BtTransferPhase.SENDING, peerName, name, transferred, -1L)
                        }
                    }
                    output.writeInt(0)
                }
                output.write(digest.digest())
                output.flush()
            }

            val ack = socket.inputStream.read()
            if (ack != 1) {
                failAndStop(startEpoch, "The other phone could not verify the file")
                return
            }
            BtTransferManager.update(
                BtTransferState(
                    phase = BtTransferPhase.COMPLETE,
                    peerName = peerName,
                    fileName = name,
                    transferred = transferred,
                    total = transferred,
                    message = "Sent to $peerName",
                )
            )
            notificationTitle = "Sent $name"
            notificationText = "To $peerName"
            notificationProgress = 100
            postNotification(force = true)
            mainHandler.postDelayed({
                if (startEpoch == epoch) stopEverything()
            }, 1500)
        } catch (error: Exception) {
            if (startEpoch == epoch) failAndStop(startEpoch, "Send failed: ${friendlyMessage(error)}")
        } finally {
            runCatching { input.close() }
            runCatching { socket?.close() }
            activeSocket = null
        }
    }

    private fun connectSocket(adapter: BluetoothAdapter, device: BluetoothDevice): BluetoothSocket? {
        val secure = runCatching {
            device.createRfcommSocketToServiceRecord(SERVICE_UUID)
        }.getOrNull()
        if (secure != null) {
            val connected = runCatching {
                secure.connect()
                true
            }.getOrDefault(false)
            if (connected) return secure
            runCatching { secure.close() }
        }
        return runCatching {
            val insecure = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
            insecure.connect()
            insecure
        }.getOrNull()
    }

    private fun publishProgress(
        phase: BtTransferPhase,
        peerName: String,
        fileName: String,
        transferred: Long,
        total: Long,
    ) {
        BtTransferManager.progress(
            phase,
            peerName,
            fileName,
            transferred,
            max(total, 0L),
        )
        notificationTitle = if (phase == BtTransferPhase.RECEIVING) {
            "Receiving $fileName"
        } else {
            "Sending $fileName"
        }
        notificationText = if (phase == BtTransferPhase.RECEIVING) {
            "From $peerName"
        } else {
            "To $peerName"
        }
        notificationProgress = if (total > 0L) {
            ((transferred * 100L) / total).toInt().coerceIn(0, 100)
        } else {
            -1
        }
        postNotification(force = false)
    }

    private fun openReceiveSink(name: String, mime: String): ReceiveSink {
        val tree = BtTransferManager.destinationUri(this)?.let {
            SafDocument.fromTreeUri(contentResolver, it)
        }
        if (tree != null && tree.isDirectory) {
            val existing = tree.listChildren().mapNotNull { it.name }.toSet()
            val finalName = uniqueName(name, existing)
            val document = tree.createFile(mime, finalName)
            if (document != null) {
                val output = document.openOutputStream()
                if (output != null) {
                    return ReceiveSink(
                        output = output,
                        displayPath = "${tree.name ?: "Selected folder"}/$finalName",
                        onDelete = { document.delete() },
                    )
                }
                document.delete()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return openMediaStoreSink(name, mime)
        }
        val directory = BtTransferManager.defaultDestinationDir(this)
        directory.mkdirs()
        val file = uniqueFile(directory, name)
        return ReceiveSink(
            output = FileOutputStream(file),
            displayPath = file.absolutePath,
            onDelete = { file.delete() },
        )
    }

    private fun openMediaStoreSink(name: String, mime: String): ReceiveSink {
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/NJ-Dialer/BT Transfer"
        val displayName = uniqueName(name, mediaStoreNames(relativePath))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the download file")
        try {
            val output = contentResolver.openOutputStream(uri)
                ?: throw IOException("Could not open the download file")
            return ReceiveSink(
                output = output,
                displayPath = "Download/NJ-Dialer/BT Transfer/$displayName",
                onDelete = { runCatching { contentResolver.delete(uri, null, null) } },
                onComplete = {
                    runCatching {
                        contentResolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                            null,
                            null,
                        )
                    }.isSuccess
                },
            )
        } catch (t: Throwable) {
            runCatching { contentResolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun mediaStoreNames(relativePath: String): Set<String> = runCatching {
        contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(relativePath),
            null,
        )?.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { add(it) }
                }
            }
        }
    }.getOrNull().orEmpty()

    private fun uniqueFile(directory: File, name: String): File {
        val clean = sanitizeFileName(name)
        val first = File(directory, clean)
        if (!first.exists()) return first
        val dot = clean.lastIndexOf('.')
        val base = if (dot > 0) clean.substring(0, dot) else clean
        val extension = if (dot > 0) clean.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = File(directory, "$base ($index)$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun uniqueName(name: String, existing: Set<String>): String {
        val clean = sanitizeFileName(name)
        if (clean !in existing) return clean
        val dot = clean.lastIndexOf('.')
        val base = if (dot > 0) clean.substring(0, dot) else clean
        val extension = if (dot > 0) clean.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$base ($index)$extension"
            if (candidate !in existing) return candidate
            index++
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f]"), "")
            .trim()
        return cleaned.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "received.bin"
    }

    private fun peerName(socket: BluetoothSocket): String =
        runCatching { socket.remoteDevice.name }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching { socket.remoteDevice.address }.getOrDefault("Bluetooth device")

    private fun failAndStop(startEpoch: Int, message: String) {
        if (startEpoch != epoch) return
        BtTransferManager.fail(message)
        notificationTitle = "Bluetooth transfer failed"
        notificationText = message
        notificationProgress = -1
        postNotification(force = true)
    }

    private fun friendlyMessage(error: Throwable): String {
        val text = error.message.orEmpty()
        return when {
            text.contains("socket closed", ignoreCase = true) -> "the connection was closed"
            text.contains("timeout", ignoreCase = true) -> "the transfer timed out"
            text.contains("eof", ignoreCase = true) -> "the other phone disconnected"
            text.isBlank() -> error.javaClass.simpleName
            else -> text
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Bluetooth file transfer",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                description = "Sending and receiving files over Bluetooth"
            }
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BtTransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(notificationProgress < 100)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stop)
        if (notificationProgress >= 0) {
            builder.setProgress(100, notificationProgress.coerceIn(0, 100), notificationProgress < 0)
        }
        return builder.build()
    }

    private fun postNotification(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationAt < NOTIFICATION_INTERVAL_MS) return
        lastNotificationAt = now
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (foregroundStarted) manager.notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        epoch++
        runCatching { serverSocket?.close() }
        runCatching { activeSocket?.close() }
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIF_ID)
        super.onDestroy()
    }

    private class ReceiveSink(
        val output: OutputStream,
        val displayPath: String,
        val onDelete: () -> Unit,
        val onComplete: () -> Boolean = { true },
    ) {
        fun delete() {
            runCatching { onDelete() }
        }

        fun complete(): Boolean = runCatching { onComplete() }.getOrDefault(false)
    }

    companion object {
        const val ACTION_START_SERVER = "com.example.action.BT_TRANSFER_SERVER"
        const val ACTION_SEND = "com.example.action.BT_TRANSFER_SEND"
        const val ACTION_STOP = "com.example.action.BT_TRANSFER_STOP"

        const val EXTRA_ADDRESS = "address"
        const val EXTRA_PEER_NAME = "peer_name"
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
        const val EXTRA_SIZE = "size"
        const val EXTRA_MIME = "mime"

        private const val CHANNEL_ID = "bt_transfer"
        private const val NOTIF_ID = 4310
        private const val SERVICE_NAME = "NJ-Dialer File Transfer"
        private const val PROTOCOL_MAGIC = "NJBT1"
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_CHUNK_BYTES = 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 350L
        private const val NOTIFICATION_INTERVAL_MS = 900L
        private val SERVICE_UUID: UUID = UUID.fromString("8f8e3a10-7b9d-4b5a-9c2c-6e2d3f4a5b60")
    }
}
