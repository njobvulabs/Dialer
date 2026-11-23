package com.example.util

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

enum class HidStatus {
    UNSUPPORTED,
    BLUETOOTH_OFF,
    NEEDS_PERMISSION,
    IDLE,
    REGISTERING,
    READY,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class HidHost(val name: String, val address: String)

data class HidState(
    val status: HidStatus = HidStatus.IDLE,
    val registered: Boolean = false,
    val connected: Boolean = false,
    val hostName: String? = null,
    val message: String? = null,
)

data class HidLedState(
    val numLock: Boolean = false,
    val capsLock: Boolean = false,
    val scrollLock: Boolean = false,
)

@SuppressLint("MissingPermission", "NewApi")
object BtHidController {
    private const val TAG = "BtHidController"

    private val _state = MutableStateFlow(HidState())
    val state: StateFlow<HidState> = _state.asStateFlow()

    private val _led = MutableStateFlow(HidLedState())
    val led: StateFlow<HidLedState> = _led.asStateFlow()

    private val _mediaKeys = MutableStateFlow(false)
    val mediaKeys: StateFlow<Boolean> = _mediaKeys.asStateFlow()

    @SuppressLint("StaticFieldLeak")
    private var impl: HidApi28? = null

    @Volatile
    private var mediaKeysEnabled = false

    fun start(context: Context) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            publish(HidState(status = HidStatus.UNSUPPORTED, message = "Bluetooth HID input needs Android 9 or newer."))
            return
        }
        if (!hasPermissions(app)) {
            publish(HidState(status = HidStatus.NEEDS_PERMISSION, message = "Allow Nearby devices to use Bluetooth input."))
            return
        }
        if (!isBluetoothEnabled(app)) {
            publish(HidState(status = HidStatus.BLUETOOTH_OFF, message = "Turn on Bluetooth to connect a PC."))
            return
        }
        mediaKeysEnabled = HidPrefs.get(app).getBoolean(HidPrefs.MEDIA_KEYS, false)
        _mediaKeys.value = mediaKeysEnabled
        val api = impl ?: HidApi28(app, mediaKeysEnabled).also { impl = it }
        api.open()
    }

    fun stop() {
        impl?.close()
        impl = null
        publish(HidState(status = HidStatus.IDLE))
    }

    fun register() {
        impl?.register()
    }

    fun unregister() {
        impl?.unregister()
    }

    fun disconnect() {
        impl?.disconnect()
    }

    fun connect(address: String) {
        impl?.connect(address)
    }

    @SuppressLint("MissingPermission")
    fun bondedHosts(context: Context): List<HidHost> {
        if (!hasPermissions(context)) return emptyList()
        return runCatching {
            context.getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.bondedDevices
                .orEmpty()
                .map { HidHost(it.safeName(), it.address) }
        }.getOrDefault(emptyList())
    }

    fun moveMouse(dx: Int, dy: Int, buttons: Int) {
        impl?.sendMouse(buttons, dx, dy, 0, 0)
    }

    fun scroll(wheel: Int, pan: Int = 0, buttons: Int = 0) {
        impl?.sendMouse(buttons, 0, 0, wheel, pan)
    }

    fun click(button: Int) {
        impl?.click(button)
    }

    fun setButtons(buttons: Int) {
        impl?.sendMouse(buttons, 0, 0, 0, 0)
    }

    fun tapKey(usage: Int, modifiers: Int = 0) {
        impl?.tapKey(usage, modifiers)
    }

    fun typeText(text: String, modifiers: Int = 0) {
        impl?.typeText(text, modifiers)
    }

    fun sendConsumer(usage: Int) {
        impl?.sendConsumer(usage)
    }

    fun runMacro(macro: HidMacro) {
        impl?.runMacro(macro)
    }

    fun setMediaKeys(enabled: Boolean) {
        mediaKeysEnabled = enabled
        _mediaKeys.value = enabled
        impl?.setMediaKeys(enabled)
    }

    fun reconnect(address: String) {
        impl?.connect(address)
    }

    fun requestDiscoverable(context: Context) {
        runCatching {
            context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            })
        }
    }

    fun requestEnableBluetooth(context: Context) {
        runCatching {
            context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    @SuppressLint("MissingPermission")
    fun isBluetoothEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        }.getOrDefault(false)
    }

    fun hasPermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermissions(): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyArray()
        return arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    }

    private fun publish(state: HidState) {
        _state.value = state
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.safeName(): String =
        runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: address

    @RequiresApi(Build.VERSION_CODES.P)
    @SuppressLint("MissingPermission")
    private class HidApi28(
        private val context: Context,
        initialMediaKeys: Boolean,
    ) {
        @SuppressLint("MissingPermission")
        private val adapter: BluetoothAdapter? =
            context.getSystemService(BluetoothManager::class.java)?.adapter
        private val executor = Executors.newSingleThreadExecutor()
        private val keyExecutor = Executors.newSingleThreadExecutor()

        @Volatile
        private var hid: BluetoothHidDevice? = null

        @Volatile
        private var host: BluetoothDevice? = null

        @Volatile
        private var registered = false

        @Volatile
        private var connected = false

        private var serviceRequested = false
        private var pendingRegister = false
        private var pendingConnectAddress: String? = null
        private var unregistering = false
        private var closed = false

        @Volatile
        private var mediaKeysEnabled = initialMediaKeys

        private val serviceListener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val device = proxy as? BluetoothHidDevice
                if (device == null) {
                    serviceRequested = false
                    pendingRegister = false
                    fail("This device does not expose the Bluetooth HID Device profile.")
                    return
                }
                if (closed) {
                    runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device) }
                    return
                }
                hid = device
                if (pendingRegister) {
                    pendingRegister = false
                    register()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                hid = null
                registered = false
                connected = false
                host = null
                serviceRequested = false
                pendingRegister = false
                val bluetoothOff = runCatching { adapter?.isEnabled == false }.getOrDefault(false)
                if (bluetoothOff) {
                    publish(HidState(status = HidStatus.BLUETOOTH_OFF, message = "Turn on Bluetooth to connect a PC."))
                } else {
                    fail("Bluetooth HID service disconnected. Try again.")
                }
            }
        }

        private val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                this@HidApi28.registered = registered
                if (registered) {
                    if (pluggedDevice != null) host = pluggedDevice
                    publish(
                        HidState(
                            status = if (connected) HidStatus.CONNECTED else HidStatus.READY,
                            registered = true,
                            connected = connected,
                            hostName = host?.safeName(),
                        )
                    )
                    val address = pendingConnectAddress
                    pendingConnectAddress = null
                    if (address != null) connect(address)
                } else if (pendingRegister) {
                    pendingRegister = false
                    unregistering = false
                    register()
                } else if (unregistering) {
                    unregistering = false
                    connected = false
                    host = null
                    publish(HidState(status = HidStatus.IDLE))
                } else {
                    connected = false
                    host = null
                    fail("The phone's Bluetooth stack rejected the HID Device role. This ROM may block Bluetooth HID.")
                }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTING -> {
                        publish(
                            HidState(
                                status = HidStatus.CONNECTING,
                                registered = registered,
                                connected = false,
                                hostName = device.safeName(),
                            )
                        )
                    }
                    BluetoothProfile.STATE_CONNECTED -> {
                        host = device
                        connected = true
                        publish(
                            HidState(
                                status = HidStatus.CONNECTED,
                                registered = true,
                                connected = true,
                                hostName = device.safeName(),
                            )
                        )
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (host?.address == device.address) {
                            host = null
                            connected = false
                        }
                        publish(
                            HidState(
                                status = if (registered) HidStatus.READY else HidStatus.IDLE,
                                registered = registered,
                                connected = false,
                                hostName = null,
                            )
                        )
                    }
                }
            }

            override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
                runCatching {
                    val size = when (id.toInt()) {
                        HidReportDescriptor.MOUSE_REPORT_ID -> HidReportDescriptor.MOUSE_PAYLOAD_SIZE
                        HidReportDescriptor.KEYBOARD_REPORT_ID -> HidReportDescriptor.KEYBOARD_PAYLOAD_SIZE
                        HidReportDescriptor.CONSUMER_REPORT_ID -> HidReportDescriptor.CONSUMER_PAYLOAD_SIZE
                        else -> bufferSize
                    }
                    val clamped = if (bufferSize > 0) size.coerceIn(0, bufferSize) else size
                    hid?.replyReport(device, type, id, ByteArray(clamped.coerceAtLeast(0)))
                }
            }

            override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
                if (id.toInt() != HidReportDescriptor.KEYBOARD_REPORT_ID || data.isEmpty()) return
                val leds = data[0].toInt() and 0xFF
                _led.value = HidLedState(
                    numLock = leds and 0x01 != 0,
                    capsLock = leds and 0x02 != 0,
                    scrollLock = leds and 0x04 != 0,
                )
            }

            override fun onVirtualCableUnplug(device: BluetoothDevice) {
                if (host?.address == device.address) {
                    host = null
                    connected = false
                    publish(HidState(status = if (registered) HidStatus.READY else HidStatus.IDLE, registered = registered))
                }
            }
        }

        @SuppressLint("MissingPermission")
        fun open() {
            if (hid != null) {
                if (!registered) register()
                return
            }
            if (serviceRequested) return
            val adapter = adapter
            if (adapter == null) {
                fail("Bluetooth is not available on this device.")
                return
            }
            pendingRegister = true
            closed = false
            serviceRequested = runCatching {
                adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
            }.getOrDefault(false)
            if (!serviceRequested) {
                pendingRegister = false
                fail("This device does not expose the Bluetooth HID Device profile.")
            }
        }

        @SuppressLint("MissingPermission")
        fun register() {
            if (registered) return
            val device = hid
            if (device == null) {
                pendingRegister = true
                open()
                return
            }
            if (!BtHidController.hasPermissions(context)) {
                publish(HidState(status = HidStatus.NEEDS_PERMISSION, message = "Allow Nearby devices to use Bluetooth input."))
                return
            }
            val settings = BluetoothHidDeviceAppSdpSettings(
                "NJ-Dialer Input",
                "NJ-Dialer Bluetooth mouse and keyboard",
                "NJ-Dialer",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                HidReportDescriptor.build(mediaKeysEnabled),
            )
            publish(HidState(status = HidStatus.REGISTERING))
            val accepted = runCatching {
                device.registerApp(settings, null, null, executor, callback)
            }.getOrElse { error ->
                Log.w(TAG, "registerApp failed", error)
                false
            }
            if (!accepted) {
                fail("This phone's Bluetooth stack rejected the HID Device role. Bluetooth HID may be disabled on this ROM.")
            }
        }

        @SuppressLint("MissingPermission")
        fun unregister() {
            val device = hid ?: return
            unregistering = true
            pendingRegister = false
            pendingConnectAddress = null
            runCatching { device.unregisterApp() }
                .onFailure {
                    unregistering = false
                    fail("Could not stop the HID device.")
                }
        }

        @SuppressLint("MissingPermission")
        fun connect(address: String) {
            if (!registered) {
                pendingConnectAddress = address
                register()
                return
            }
            val device = adapter?.bondedDevices?.firstOrNull { it.address.equals(address, ignoreCase = true) }
            if (device == null) {
                fail("That computer is no longer paired. Pair it from Bluetooth settings first.")
                return
            }
            pendingConnectAddress = null
            publish(
                HidState(
                    status = HidStatus.CONNECTING,
                    registered = true,
                    hostName = device.safeName(),
                )
            )
            val ok = runCatching { hid?.connect(device) == true }.getOrDefault(false)
            if (!ok) {
                publish(HidState(status = HidStatus.READY, registered = true, message = "Could not start the connection. Try pairing again."))
            }
        }

        @SuppressLint("MissingPermission")
        fun disconnect() {
            val device = host ?: return
            runCatching { hid?.disconnect(device) }
        }

        @SuppressLint("MissingPermission")
        fun bondedHosts(): List<HidHost> {
            if (!BtHidController.hasPermissions(context)) return emptyList()
            return runCatching {
                adapter?.bondedDevices.orEmpty().map { HidHost(it.safeName(), it.address) }
            }.getOrDefault(emptyList())
        }

        fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int) {
            val payload = byteArrayOf(
                (buttons and 0x1F).toByte(),
                dx.coerceIn(-127, 127).toByte(),
                dy.coerceIn(-127, 127).toByte(),
                wheel.coerceIn(-127, 127).toByte(),
                pan.coerceIn(-127, 127).toByte(),
            )
            send(HidReportDescriptor.MOUSE_REPORT_ID, payload)
        }

        fun click(button: Int) {
            val device = hid ?: return
            val target = host ?: return
            executor.execute {
                runCatching {
                    val mask = (button and 0x1F).toByte()
                    device.sendReport(
                        target,
                        HidReportDescriptor.MOUSE_REPORT_ID,
                        byteArrayOf(mask, 0, 0, 0, 0),
                    )
                    Thread.sleep(25)
                    device.sendReport(
                        target,
                        HidReportDescriptor.MOUSE_REPORT_ID,
                        byteArrayOf(0, 0, 0, 0, 0),
                    )
                }.onFailure { Log.w(TAG, "click failed", it) }
            }
        }

        fun tapKey(usage: Int, modifiers: Int) {
            val device = hid ?: return
            val target = host ?: return
            keyExecutor.execute {
                runCatching {
                    device.sendReport(
                        target,
                        HidReportDescriptor.KEYBOARD_REPORT_ID,
                        keyboardPayload(modifiers, usage),
                    )
                    Thread.sleep(12)
                    device.sendReport(
                        target,
                        HidReportDescriptor.KEYBOARD_REPORT_ID,
                        ByteArray(HidReportDescriptor.KEYBOARD_PAYLOAD_SIZE),
                    )
                }.onFailure { Log.w(TAG, "tapKey failed", it) }
            }
        }

        fun typeText(text: String, modifiers: Int) {
            if (text.isEmpty()) return
            val device = hid ?: return
            val target = host ?: return
            keyExecutor.execute {
                runCatching { typeTextNow(device, target, text, modifiers) }
                    .onFailure { Log.w(TAG, "typeText failed", it) }
            }
        }

        fun sendConsumer(usage: Int) {
            if (!mediaKeysEnabled) return
            val device = hid ?: return
            val target = host ?: return
            keyExecutor.execute {
                runCatching {
                    val payload = byteArrayOf(
                        (usage and 0xFF).toByte(),
                        ((usage shr 8) and 0xFF).toByte(),
                    )
                    device.sendReport(target, HidReportDescriptor.CONSUMER_REPORT_ID, payload)
                    Thread.sleep(18)
                    device.sendReport(
                        target,
                        HidReportDescriptor.CONSUMER_REPORT_ID,
                        ByteArray(HidReportDescriptor.CONSUMER_PAYLOAD_SIZE),
                    )
                }.onFailure { Log.w(TAG, "sendConsumer failed", it) }
            }
        }

        fun runMacro(macro: HidMacro) {
            val device = hid ?: return
            val target = host ?: return
            keyExecutor.execute {
                runCatching {
                    macro.steps.forEach { step ->
                        when (step) {
                            is HidMacroStep.Chord -> {
                                device.sendReport(
                                    target,
                                    HidReportDescriptor.KEYBOARD_REPORT_ID,
                                    keyboardPayload(step.modifiers, step.usage),
                                )
                                Thread.sleep(18)
                                device.sendReport(
                                    target,
                                    HidReportDescriptor.KEYBOARD_REPORT_ID,
                                    ByteArray(HidReportDescriptor.KEYBOARD_PAYLOAD_SIZE),
                                )
                                Thread.sleep(30)
                            }
                            is HidMacroStep.Text -> typeTextNow(
                                device, target, step.text,
                                prefixModifiers = 0,
                            )
                            is HidMacroStep.Delay -> Thread.sleep(step.ms.coerceIn(0L, 10_000L))
                        }
                    }
                }.onFailure { Log.w(TAG, "runMacro failed", it) }
            }
        }

        private fun typeTextNow(
            device: BluetoothHidDevice,
            target: BluetoothDevice,
            text: String,
            prefixModifiers: Int,
        ) {
            text.forEachIndexed { index, char ->
                val stroke = HidKeymap.forChar(char) ?: return@forEachIndexed
                val mods = if (index == 0) prefixModifiers or stroke.modifiers else stroke.modifiers
                device.sendReport(
                    target,
                    HidReportDescriptor.KEYBOARD_REPORT_ID,
                    keyboardPayload(mods, stroke.usage),
                )
                Thread.sleep(8)
                device.sendReport(
                    target,
                    HidReportDescriptor.KEYBOARD_REPORT_ID,
                    ByteArray(HidReportDescriptor.KEYBOARD_PAYLOAD_SIZE),
                )
                Thread.sleep(8)
            }
        }

        private fun send(reportId: Int, payload: ByteArray) {
            val device = hid ?: return
            val target = host ?: return
            executor.execute {
                runCatching { device.sendReport(target, reportId, payload) }
                    .onFailure { Log.w(TAG, "sendReport failed", it) }
            }
        }

        fun setMediaKeys(enabled: Boolean) {
            if (mediaKeysEnabled == enabled) return
            mediaKeysEnabled = enabled
            if (registered) {
                pendingRegister = true
                val device = hid
                if (device != null) {
                    unregistering = true
                    runCatching { device.unregisterApp() }.onFailure {
                        pendingRegister = false
                        unregistering = false
                        fail("Could not apply the keyboard layout. Re-pair the computer.")
                    }
                }
            }
        }

        fun close() {
            closed = true
            runCatching { hid?.unregisterApp() }
            runCatching {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
            }
            hid = null
            host = null
            registered = false
            connected = false
            serviceRequested = false
            pendingRegister = false
            pendingConnectAddress = null
            unregistering = false
        }

        private fun keyboardPayload(modifiers: Int, usage: Int): ByteArray {
            // The HID key array only covers usages 0x00..0x65. Modifier usages
            // (0xE0..0xE7) have to become bits in the modifier byte instead,
            // which is also what makes a standalone Win tap open the Start menu.
            val modifierBit = if (usage in 0xE0..0xE7) 1 shl (usage - 0xE0) else 0
            val effectiveModifiers = modifiers or modifierBit
            val keyUsage = if (modifierBit != 0) 0 else usage
            return byteArrayOf(
                (effectiveModifiers and 0xFF).toByte(),
                0,
                (keyUsage and 0xFF).toByte(),
                0, 0, 0, 0, 0,
            )
        }

        private fun fail(message: String) {
            Log.w(TAG, message)
            publish(HidState(status = HidStatus.ERROR, registered = registered, connected = connected, message = message))
        }

        @SuppressLint("MissingPermission")
        private fun BluetoothDevice.safeName(): String =
            runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: address
    }
}
