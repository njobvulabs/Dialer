package com.example.ui.screens.apkbackup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/**
 * Receives the async result of a [PackageInstaller] commit (used to install
 * split-APK apps) and surfaces success/failure to the user.
 */
class ApkInstallerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val msg = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "APK installed"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> null
            else -> {
                val err = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "Install failed (code $status)"
                err
            }
        }
        if (msg != null) {
            runCatching { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
        }
    }
}

