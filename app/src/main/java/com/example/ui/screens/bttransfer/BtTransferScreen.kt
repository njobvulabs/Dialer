package com.example.ui.screens.bttransfer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import com.example.ui.MainViewModel
import com.example.ui.components.formatFileSize
import com.example.util.BtPeer
import com.example.util.BtTransferManager
import com.example.util.BtTransferPhase
import com.example.util.BtTransferState

@Composable
fun BtTransferScreen(onClose: () -> Unit, viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by BtTransferManager.state.collectAsState()

    var peers by remember { mutableStateOf<List<BtPeer>>(emptyList()) }
    var bluetoothOn by remember { mutableStateOf(false) }
    var localName by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingPeer by remember { mutableStateOf<BtPeer?>(null) }
    var destinationLabel by remember {
        mutableStateOf(BtTransferManager.destinationLabel(context))
    }
    var customDestination by remember {
        mutableStateOf(BtTransferManager.destinationUri(context) != null)
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun refresh() {
        bluetoothOn = BtTransferManager.isBluetoothEnabled(context)
        localName = BtTransferManager.localName(context)
        peers = BtTransferManager.bondedDevices(context)
        destinationLabel = BtTransferManager.destinationLabel(context)
        customDestination = BtTransferManager.destinationUri(context) != null
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val action = pendingAction
        pendingAction = null
        if (BtTransferManager.hasPermissions(context)) {
            action?.invoke()
        } else {
            toast("Bluetooth permission is required")
        }
        refresh()
    }

    val destinationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            BtTransferManager.saveDestination(context, uri)
            destinationLabel = BtTransferManager.destinationLabel(context)
            customDestination = true
            toast("Incoming files will be saved to $destinationLabel")
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val peer = pendingPeer
        pendingPeer = null
        if (uri != null && peer != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            BtTransferManager.send(context, peer, uri)
        }
    }

    fun ensurePermissions(action: () -> Unit) {
        if (BtTransferManager.hasPermissions(context)) {
            action()
            return
        }
        pendingAction = action
        val permissions = BtTransferManager.requiredPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !BtTransferManager.hasNotificationPermission(context)
        ) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    fun startReceiving() {
        ensurePermissions {
            if (!BtTransferManager.isBluetoothEnabled(context)) {
                BtTransferManager.requestEnableBluetooth(context)
                toast("Turn on Bluetooth, then start receiving again")
            } else {
                BtTransferManager.startReceiving(context)
            }
        }
    }

    fun sendTo(peer: BtPeer) {
        ensurePermissions {
            if (!BtTransferManager.isBluetoothEnabled(context)) {
                BtTransferManager.requestEnableBluetooth(context)
                toast("Turn on Bluetooth, then try again")
            } else {
                pendingPeer = peer
                filePicker.launch(arrayOf("*/*"))
            }
        }
    }

    LaunchedEffect(refreshTick) { refresh() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        FloatingTopBar(modifier = Modifier.fillMaxWidth(), useGlass = useGlassmorphism) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Bluetooth Transfer",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    when {
                        !BtTransferManager.hasPermissions(context) -> "Bluetooth permission needed"
                        !bluetoothOn -> "Bluetooth is off"
                        else -> localName?.let { "Visible as $it" } ?: "Paired-device transfer"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.background)
            )
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusCard(
                state = state,
                permissionsGranted = BtTransferManager.hasPermissions(context),
                bluetoothOn = bluetoothOn,
                onStartReceiving = ::startReceiving,
                onCancel = { BtTransferManager.stop(context) },
                onRequestPermissions = { ensurePermissions { refresh() } },
            )

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Receive",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Start receiving, then pick this phone from the other phone's nj-dialer app. Both phones must be paired first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                ensurePermissions {
                                    if (!BtTransferManager.isBluetoothEnabled(context)) {
                                        BtTransferManager.requestEnableBluetooth(context)
                                    }
                                    BtTransferManager.requestDiscoverable(context)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(AppIcons.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Discoverable")
                        }
                        OutlinedButton(
                            onClick = {
                                ensurePermissions {
                                    if (!BtTransferManager.isBluetoothEnabled(context)) {
                                        BtTransferManager.requestEnableBluetooth(context)
                                    }
                                    BtTransferManager.openBluetoothSettings(context)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(AppIcons.BluetoothConnected, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pair new")
                        }
                    }
                }
            }

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Save incoming files to",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        destinationLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { destinationPicker.launch(null) }) {
                            Icon(AppIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Choose folder")
                        }
                        if (customDestination) {
                            TextButton(
                                onClick = {
                                    BtTransferManager.saveDestination(context, null)
                                    destinationLabel = BtTransferManager.destinationLabel(context)
                                    customDestination = false
                                }
                            ) {
                                Text("Use Downloads")
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Paired devices",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    refreshTick++
                    if (!BtTransferManager.hasPermissions(context)) {
                        ensurePermissions { refresh() }
                    }
                }) {
                    Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Refresh")
                }
            }

            if (peers.isEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            AppIcons.Bluetooth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            if (!BtTransferManager.hasPermissions(context)) {
                                "Grant Bluetooth permission to see paired devices."
                            } else if (!bluetoothOn) {
                                "Turn on Bluetooth to see paired devices."
                            } else {
                                "No paired devices yet. Pair the other phone in Bluetooth settings, then tap Refresh."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                peers.forEach { peer ->
                    PeerRow(
                        peer = peer,
                        enabled = bluetoothOn && state.phase != BtTransferPhase.SENDING &&
                            state.phase != BtTransferPhase.RECEIVING &&
                            state.phase != BtTransferPhase.CONNECTING,
                        onSend = { sendTo(peer) },
                    )
                }
            }

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "App-to-app only",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Android only lets nj-dialer receive on this private channel, so the other phone must also have nj-dialer installed. Keep both phones close and the screen on while transferring.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StatusCard(
    state: BtTransferState,
    permissionsGranted: Boolean,
    bluetoothOn: Boolean,
    onStartReceiving: () -> Unit,
    onCancel: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val active = state.phase == BtTransferPhase.SENDING ||
        state.phase == BtTransferPhase.RECEIVING ||
        state.phase == BtTransferPhase.CONNECTING
    val fraction = if (state.total > 0L) {
        (state.transferred.toFloat() / state.total.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    val title = when (state.phase) {
        BtTransferPhase.IDLE -> "Ready"
        BtTransferPhase.WAITING -> "Waiting for a sender"
        BtTransferPhase.CONNECTING -> "Connecting"
        BtTransferPhase.SENDING -> "Sending"
        BtTransferPhase.RECEIVING -> "Receiving"
        BtTransferPhase.COMPLETE -> "Transfer complete"
        BtTransferPhase.ERROR -> "Transfer failed"
    }
    val detail = when (state.phase) {
        BtTransferPhase.IDLE -> "Start receiving, or send a file to a paired phone."
        BtTransferPhase.WAITING -> "Keep this screen open. The other phone can now connect."
        BtTransferPhase.CONNECTING -> "Connecting to ${state.peerName.ifBlank { "the other phone" }}…"
        BtTransferPhase.SENDING, BtTransferPhase.RECEIVING -> buildString {
            append(state.fileName.ifBlank { "File" })
            if (state.peerName.isNotBlank()) {
                append(if (state.phase == BtTransferPhase.RECEIVING) " from " else " to ")
                append(state.peerName)
            }
            if (state.total > 0L) {
                append(" · ")
                append(formatFileSize(state.transferred))
                append(" / ")
                append(formatFileSize(state.total))
            }
        }
        BtTransferPhase.COMPLETE -> state.message.ifBlank { "The file was transferred." }
        BtTransferPhase.ERROR -> state.message.ifBlank { "The transfer could not finish." }
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = when (state.phase) {
            BtTransferPhase.ERROR -> MaterialTheme.colorScheme.errorContainer
            BtTransferPhase.COMPLETE -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        state.phase == BtTransferPhase.ERROR -> AppIcons.Warning
                        state.phase == BtTransferPhase.COMPLETE -> AppIcons.CheckCircle
                        active -> AppIcons.SwapHoriz
                        else -> AppIcons.Bluetooth
                    },
                    contentDescription = null,
                    tint = when (state.phase) {
                        BtTransferPhase.ERROR -> MaterialTheme.colorScheme.error
                        BtTransferPhase.COMPLETE -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (active) {
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    !permissionsGranted -> {
                        Button(onClick = onRequestPermissions) {
                            Text("Grant Bluetooth permission")
                        }
                    }
                    active || state.phase == BtTransferPhase.WAITING -> {
                        Button(onClick = onCancel) {
                            Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.phase == BtTransferPhase.WAITING) "Stop receiving" else "Cancel")
                        }
                    }
                    else -> {
                        Button(onClick = onStartReceiving) {
                            Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Start receiving")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerRow(
    peer: BtPeer,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                AppIcons.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    peer.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    peer.address,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onSend, enabled = enabled) {
                Icon(AppIcons.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Send")
            }
        }
    }
}
