package com.example.ui.screens.sandbox

import android.content.Context
import top.niunaijun.blackbox.BlackBoxCore

object SandboxPrefs {
    private const val PREFS = "sandbox"
    private const val KEY_DEFAULT_FOLDERS = "default_shared_folders"
    private const val KEY_APPLY_DEFAULTS_ON_INSTALL = "apply_default_folders_on_install"
    private const val KEY_CONFIRM_BEFORE_STOP = "confirm_before_stop"
    private const val KEY_KEEP_SAF_BRIDGE = "keep_saf_bridge_with_any_shared_folder"
    private const val KEY_SELECTED_USER = "selected_user"
    private const val KEY_STOP_APPS_ON_LEAVE = "stop_apps_on_leave"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun defaultSharedFolders(context: Context): Set<String> {
        val stored = prefs(context).getStringSet(KEY_DEFAULT_FOLDERS, emptySet()).orEmpty()
        return stored.filter { it in SandboxStorageFolders.ALL }.toSet()
    }

    fun setDefaultSharedFolders(context: Context, folders: Set<String>) {
        prefs(context).edit()
            .putStringSet(KEY_DEFAULT_FOLDERS, folders.filter { it in SandboxStorageFolders.ALL }.toSet())
            .apply()
    }

    fun applyDefaultsOnInstall(context: Context): Boolean =
        prefs(context).getBoolean(KEY_APPLY_DEFAULTS_ON_INSTALL, false)

    fun setApplyDefaultsOnInstall(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_APPLY_DEFAULTS_ON_INSTALL, enabled).apply()
    }

    fun confirmBeforeStop(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONFIRM_BEFORE_STOP, false)

    fun setConfirmBeforeStop(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONFIRM_BEFORE_STOP, enabled).apply()
    }

    fun keepSafBridge(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SAF_BRIDGE, true)

    fun setKeepSafBridge(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_SAF_BRIDGE, enabled).apply()
    }

    fun selectedUser(context: Context): Int =
        prefs(context).getInt(KEY_SELECTED_USER, 0).coerceAtLeast(0)

    fun setSelectedUser(context: Context, userId: Int) {
        prefs(context).edit().putInt(KEY_SELECTED_USER, userId.coerceAtLeast(0)).apply()
    }

    fun stopAppsOnLeave(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STOP_APPS_ON_LEAVE, false)

    fun setStopAppsOnLeave(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STOP_APPS_ON_LEAVE, enabled).apply()
    }
}

object SandboxStorageFolders {
    val ALL = BlackBoxCore.getKnownSharedFolders()
}
