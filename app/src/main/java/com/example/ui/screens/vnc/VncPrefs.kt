package com.example.ui.screens.vnc

import android.content.Context
import com.example.util.CredentialCipher
import org.json.JSONArray
import org.json.JSONObject

data class VncSavedHost(
    val name: String,
    val host: String,
    val port: Int,
    val password: String,
)

data class VncTarget(val host: String, val port: Int)

enum class VncInputMode { DIRECT, TRACKPAD, TIGHT, ZRLE, COMPRESSION, DEPTH }
enum class VncFitMode { FIT, ONE_TO_ONE, FIT_WIDTH }

data class VncViewSettings(
    val inputMode: VncInputMode = VncInputMode.DIRECT,
    val fitMode: VncFitMode = VncFitMode.FIT,
    val viewOnly: Boolean = false,
    val keepAwake: Boolean = true,
    val fullscreen: Boolean = false,
)

object VncPrefs {

    private const val PREFS = "vnc"
    private const val KEY_HOSTS = "hosts"
    private const val KEY_INPUT_MODE = "input_mode"
    private const val KEY_FIT_MODE = "fit_mode"
    private const val KEY_VIEW_ONLY = "view_only"
    private const val KEY_KEEP_AWAKE = "keep_awake"
    private const val KEY_FULLSCREEN = "fullscreen"
    private const val DEFAULT_PORT = 5900

    fun loadViewSettings(context: Context): VncViewSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return VncViewSettings(
            inputMode = runCatching {
                VncInputMode.valueOf(prefs.getString(KEY_INPUT_MODE, null) ?: "")
            }.getOrDefault(VncInputMode.DIRECT),
            fitMode = runCatching {
                VncFitMode.valueOf(prefs.getString(KEY_FIT_MODE, null) ?: "")
            }.getOrDefault(VncFitMode.FIT),
            viewOnly = prefs.getBoolean(KEY_VIEW_ONLY, false),
            keepAwake = prefs.getBoolean(KEY_KEEP_AWAKE, true),
            fullscreen = prefs.getBoolean(KEY_FULLSCREEN, false),
        )
    }

    fun saveViewSettings(context: Context, settings: VncViewSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_INPUT_MODE, settings.inputMode.name)
            .putString(KEY_FIT_MODE, settings.fitMode.name)
            .putBoolean(KEY_VIEW_ONLY, settings.viewOnly)
            .putBoolean(KEY_KEEP_AWAKE, settings.keepAwake)
            .putBoolean(KEY_FULLSCREEN, settings.fullscreen)
            .apply()
    }

    fun load(context: Context): List<VncSavedHost> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOSTS, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val host = item.optString("host").trim()
                    if (host.isEmpty()) continue
                    val port = item.optInt("port", DEFAULT_PORT)
                    if (port !in 1..65535) continue
                    add(
                        VncSavedHost(
                            name = item.optString("name").trim().ifEmpty { host },
                            host = host,
                            port = port,
                            password = CredentialCipher.decrypt(item.optString("password")),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, hosts: List<VncSavedHost>) {
        val array = JSONArray()
        hosts.forEach { saved ->
            array.put(
                JSONObject()
                    .put("name", saved.name)
                    .put("host", saved.host)
                    .put("port", saved.port)
                    .put("password", CredentialCipher.encrypt(saved.password))
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOSTS, array.toString())
            .apply()
    }

    fun upsert(context: Context, host: VncSavedHost): List<VncSavedHost> {
        val updated = load(context)
            .filterNot { it.host.equals(host.host, ignoreCase = true) && it.port == host.port }
            .toMutableList()
        updated.add(0, host)
        save(context, updated)
        return updated
    }

    fun remove(context: Context, host: VncSavedHost): List<VncSavedHost> {
        val updated = load(context).filterNot {
            it.host.equals(host.host, ignoreCase = true) && it.port == host.port
        }
        save(context, updated)
        return updated
    }
}

object VncAddress {

    fun parse(hostInput: String, portInput: String): VncTarget? {
        var raw = hostInput.trim()
        if (raw.isEmpty()) return null
        raw = raw
            .removePrefix("vnc://")
            .removePrefix("rfb://")
            .substringBefore('/')
            .trim()
        if (raw.isEmpty()) return null

        val explicitPort = portInput.trim().let { text ->
            if (text.isEmpty()) null else text.toIntOrNull() ?: return null
        }
        var host = raw
        var port = explicitPort ?: 5900

        if (raw.startsWith("[")) {
            val close = raw.indexOf(']')
            if (close <= 1) return null
            host = raw.substring(1, close)
            val tail = raw.substring(close + 1)
            if (tail.startsWith(":")) {
                port = tail.substring(1).toIntOrNull() ?: return null
            } else if (tail.isNotEmpty()) {
                return null
            }
        } else {
            val firstColon = raw.indexOf(':')
            val lastColon = raw.lastIndexOf(':')
            if (firstColon > 0 && firstColon == lastColon) {
                host = raw.substring(0, firstColon)
                port = raw.substring(firstColon + 1).toIntOrNull() ?: return null
            }
        }

        host = host.trim()
        if (host.isEmpty() || port !in 1..65535) return null
        return VncTarget(host, port)
    }
}
