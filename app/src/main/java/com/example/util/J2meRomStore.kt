package com.example.util

import android.content.Context
import android.net.Uri
import com.example.j2me.J2meApp
import com.example.j2me.J2meRuntime
import com.example.ui.screens.j2me.J2mePrefs
import java.io.File

object J2meRomStore {
    fun load(context: Context): List<J2meApp> =
        J2meRuntime.listApps(context).sortedByDescending { J2mePrefs.lastPlayed(context, it.id) }

    fun import(context: Context, uri: Uri): J2meApp? {
        val id = J2meRuntime.install(context, uri)
        return J2meRuntime.listApps(context).firstOrNull { it.id == id }
    }

    fun touch(context: Context, appId: String) {
        J2mePrefs.touch(context, appId)
    }

    fun delete(context: Context, app: J2meApp): Boolean {
        val trashed = runCatching { RecycleBin.trash(context, File(app.path)) }.getOrNull() != null
        if (!trashed) return false
        J2meRuntime.delete(context, app.id)
        J2mePrefs.forget(context, app.id)
        return true
    }
}
