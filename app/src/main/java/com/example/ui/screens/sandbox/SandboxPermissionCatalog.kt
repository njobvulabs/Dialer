package com.example.ui.screens.sandbox

enum class SandboxPermissionStatus(val label: String) {
    AutoGranted("Sandbox auto-granted"),
    HostOrSystem("Host/system dependent")
}

data class SandboxPermissionInfo(
    val name: String,
    val category: String,
    val status: SandboxPermissionStatus,
    val detail: String
)

object SandboxPermissionCatalog {
    fun summarize(names: List<String>): List<SandboxPermissionInfo> =
        names.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy<String> { categoryOf(it) }.thenBy { it })
            .map { name ->
                val status = statusOf(name)
                SandboxPermissionInfo(
                    name = name,
                    category = categoryOf(name),
                    status = status.first,
                    detail = status.second
                )
            }

    private fun categoryOf(name: String): String = when {
        name.contains("FOREGROUND_SERVICE") -> "Foreground services"
        name == "android.permission.INTERNET" ||
            name == "android.permission.ACCESS_NETWORK_STATE" ||
            name == "android.permission.ACCESS_WIFI_STATE" ||
            name == "android.permission.CHANGE_WIFI_STATE" -> "Network"
        name == "android.permission.RECORD_AUDIO" ||
            name == "android.permission.CAPTURE_AUDIO_OUTPUT" ||
            name == "android.permission.MODIFY_AUDIO_SETTINGS" ||
            name.contains("MEDIA_PROJECTION") -> "Audio"
        name == "android.permission.CAMERA" -> "Camera"
        name == "android.permission.POST_NOTIFICATIONS" -> "Notifications"
        name == "android.permission.ACCOUNT_MANAGER" ||
            name == "android.permission.SEND_SMS" -> "Accounts & SMS"
        name.startsWith("com.xiaomi.") || name.startsWith("miui.") || name.contains("MIUI") ->
            "Xiaomi / MIUI"
        name.contains("STORAGE") || name.contains("MEDIA") || name == "android.permission.ACCESS_MEDIA_LOCATION" ->
            "Storage & media"
        name.contains("LOCATION") -> "Location"
        name.contains("BLUETOOTH") || name.contains("NFC") -> "Nearby connections"
        name.contains("CONTACT") || name.contains("CALENDAR") || name.contains("PHONE") -> "Contacts & phone"
        else -> "Other"
    }

    private fun statusOf(name: String): Pair<SandboxPermissionStatus, String> = when {
        isAutoGranted(name) ->
            SandboxPermissionStatus.AutoGranted to "Explicitly allowed by the sandbox permission proxies."
        name.startsWith("com.xiaomi.") || name.startsWith("miui.") || name.contains("MIUI") ->
            SandboxPermissionStatus.HostOrSystem to "Vendor permission; availability depends on the ROM and host app identity."
        else ->
            SandboxPermissionStatus.HostOrSystem to "Not explicitly changed by the sandbox. It may work through NJ-Dialer's permissions or fail in the guest app."
    }

    private fun isAutoGranted(name: String): Boolean = when {
        name == "android.permission.INTERNET" ||
            name == "android.permission.ACCESS_NETWORK_STATE" -> true
        name == "android.permission.RECORD_AUDIO" ||
            name == "android.permission.CAPTURE_AUDIO_OUTPUT" ||
            name == "android.permission.MODIFY_AUDIO_SETTINGS" -> true
        name == "android.permission.FOREGROUND_SERVICE_MICROPHONE" ||
            name == "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" ||
            name == "android.permission.FOREGROUND_SERVICE_CAMERA" ||
            name == "android.permission.FOREGROUND_SERVICE_LOCATION" ||
            name == "android.permission.FOREGROUND_SERVICE_HEALTH" ||
            name == "android.permission.FOREGROUND_SERVICE_DATA_SYNC" ||
            name == "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" ||
            name == "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED" ||
            name == "android.permission.FOREGROUND_SERVICE_PHONE_CALL" ||
            name == "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" -> true
        name == "android.permission.READ_EXTERNAL_STORAGE" ||
            name == "android.permission.WRITE_EXTERNAL_STORAGE" ||
            name.startsWith("android.permission.READ_MEDIA_") ||
            name == "android.permission.ACCESS_MEDIA_LOCATION" -> true
        name == "android.permission.POST_NOTIFICATIONS" ||
            name == "android.permission.ACCOUNT_MANAGER" ||
            name == "android.permission.SEND_SMS" -> true
        name == "miui.permission.USE_INTERNAL_GENERAL_API" ||
            name == "miui.permission.OPTIMIZE_POWER" ||
            name == "miui.permission.RUN_IN_BACKGROUND" ||
            name == "miui.permission.POST_NOTIFICATIONS" ||
            name == "miui.permission.AUTO_START" ||
            name == "miui.permission.BACKGROUND_POPUP_WINDOW" ||
            name == "miui.permission.SHOW_WHEN_LOCKED" ||
            name == "miui.permission.TURN_SCREEN_ON" -> true
        else -> false
    }
}
