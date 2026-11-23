package com.example.util

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.service.BtTransferService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class BtTransferPhase {
    IDLE,
    WAITING,
    CONNECTING,
    SENDING,
    RECEIVING,
    COMPLETE,
    ERROR,
}

data class BtTransferState(
    val phase: BtTransferPhase = BtTransferPhase.IDLE,
    val peerName: String = "",
    val fileName: String = "",
    val transferred: Long = 0L,
    val total: Long = 0L,
    val message: String = "",
)

data class BtPeer(
    val name: String,
    val address: String,
)

object BtTransferManager {
    const val PREFS = "bt_transfer"

    private val _state = MutableStateFlow(BtTransferState())
    val state: StateFlow<BtTransferState> = _state.asStateFlow()

    fun update(state: BtTransferState) {
        _state.value = state
    }

    fun progress(
        phase: BtTransferPhase,
        peerName: String,
        fileName: String,
        transferred: Long,
        total: Long,
    ) {
        _state.value = BtTransferState(
            phase = phase,
            peerName = peerName,
            fileName = fileName,
            transferred = transferred,
            total = total,
            message = "",
        )
    }

    fun fail(message: String) {
        _state.value = _state.value.copy(
            phase = BtTransferPhase.ERROR,
            message = message,
        )
    }

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            emptyArray()
        }

    fun hasPermissions(context: Context): Boolean {
        val required = requiredPermissions()
        return required.isEmpty() || required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission")
    fun isBluetoothEnabled(context: Context): Boolean {
        if (!hasPermissions(context)) return false
        return runCatching { adapter(context)?.isEnabled == true }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun localName(context: Context): String? {
        if (!hasPermissions(context)) return null
        return runCatching { adapter(context)?.name }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    fun bondedDevices(context: Context): List<BtPeer> {
        if (!hasPermissions(context)) return emptyList()
        return runCatching {
            adapter(context)?.bondedDevices
                ?.map { device ->
                    BtPeer(
                        name = device.name?.takeIf { it.isNotBlank() }
                            ?: device.address,
                        address = device.address,
                    )
                }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    fun requestDiscoverable(context: Context, seconds: Int = 300) {
        runCatching {
            context.startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, seconds)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    fun requestEnableBluetooth(context: Context) {
        runCatching {
            context.startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openBluetoothSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun startReceiving(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, BtTransferService::class.java)
                .setAction(BtTransferService.ACTION_START_SERVER),
        )
    }

    fun stop(context: Context) {
        runCatching {
            context.startService(
                Intent(context, BtTransferService::class.java)
                    .setAction(BtTransferService.ACTION_STOP)
            )
        }
    }

    fun send(context: Context, peer: BtPeer, uri: Uri) {
        val name = displayName(context, uri) ?: "file"
        val size = displaySize(context, uri)
        ContextCompat.startForegroundService(
            context,
            Intent(context, BtTransferService::class.java)
                .setAction(BtTransferService.ACTION_SEND)
                .putExtra(BtTransferService.EXTRA_ADDRESS, peer.address)
                .putExtra(BtTransferService.EXTRA_PEER_NAME, peer.name)
                .putExtra(BtTransferService.EXTRA_URI, uri.toString())
                .putExtra(BtTransferService.EXTRA_NAME, name)
                .putExtra(BtTransferService.EXTRA_SIZE, size)
                .putExtra(
                    BtTransferService.EXTRA_MIME,
                    context.contentResolver.getType(uri) ?: "application/octet-stream",
                ),
        )
    }

    fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment?.substringAfterLast('/')
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else {
                        null
                    }
                }
        }.getOrNull()
    }

    fun displaySize(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") {
            return runCatching { File(uri.path.orEmpty()).length() }.getOrDefault(-1L)
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else -1L
                    } else {
                        -1L
                    }
                }
        }.getOrDefault(-1L) ?: -1L
    }

    fun saveDestination(context: Context, uri: Uri?) {
        SafPrefs.save(context, PREFS, uri)
    }

    fun destinationUri(context: Context): Uri? = SafPrefs.load(context, PREFS)

    fun destinationLabel(context: Context): String {
        val uri = destinationUri(context)
        if (uri != null) {
            val name = SafDocument.displayName(context.contentResolver, uri)
            if (!name.isNullOrBlank()) return name
        }
        return defaultDestinationDir(context).absolutePath
    }

    fun defaultDestinationDir(context: Context): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "NJ-Dialer/BT Transfer",
        )
}
