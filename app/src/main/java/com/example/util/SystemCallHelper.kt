package com.example.util

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.util.Log
import android.widget.Toast

object SystemCallHelper {

    private const val TAG = "SystemCallHelper"

    fun isDefaultDialer(context: Context): Boolean {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager?.defaultDialerPackage == context.packageName) {
                return true

            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun requestDefaultDialerIntent(context: Context): Intent? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                } else {
                    Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                        putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
                    }
                }
            } else {
                Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                    putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
                }
            }
        } catch (e: Exception) {
            Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        }
    }

    fun openDefaultDialerSettings(context: Context) {
        val intents = mutableListOf<Intent>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                intents.add(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
            }
        }

        intents.add(
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
            }
        )

        intents.add(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        intents.add(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                // Try next intent
            }
        }
    }

    fun makeCall(context: Context, phoneNumber: String, simSlot: Int = 1) {
        val cleanNumber = phoneNumber.trim()
        if (cleanNumber.isEmpty()) return

        val uri = Uri.fromParts("tel", cleanNumber, null)
        Log.d(TAG, "makeCall($cleanNumber) simSlot=$simSlot isDefaultDialer=${isDefaultDialer(context)}")

        // Preferred path when this app is the default dialer: route the outgoing call
        // through Telecom using the phone account for the chosen SIM. The call then
        // arrives at this app's InCallService via onCallAdded(), so the in-call UI
        // reflects the real call state.
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecomManager != null && isDefaultDialer(context)) {
            try {
                val extras = Bundle().apply {
                    val accountHandle = phoneAccountForSlot(context, telecomManager, simSlot)
                    if (accountHandle != null) {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
                        Log.d(TAG, "Placing via Telecom on account: ${accountHandle.id}")
                    }
                }
                telecomManager.placeCall(uri, extras)
                return
            } catch (e: Exception) {
                Log.w(TAG, "placeCall failed; falling back to ACTION_CALL", e)
            }
        }

        // Otherwise delegate to the OS phone app. ACTION_CALL requires CALL_PHONE to
        // be granted; never silently fall back to opening our own dial screen without
        // actually placing a call.
        val intent = Intent(Intent.ACTION_CALL, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("com.android.phone.force.slot", true)
            putExtra("Cdma_Slot", simSlot - 1)
            putExtra("SimSlot", simSlot - 1)
            putExtra("simnum", simSlot)
            putExtra("slot_id", simSlot - 1)
        }

        try {
            if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("CALL_PHONE permission not granted")
            }
            context.startActivity(intent)
        } catch (e: SecurityException) {
            Log.e(TAG, "CALL_PHONE permission missing; cannot place call", e)
            Toast.makeText(context, "Calling blocked: grant the Phone/Call permission", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "ACTION_CALL failed for $cleanNumber", e)
            Toast.makeText(context, "Could not place the call: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun phoneAccountForSlot(context: Context, telecomManager: TelecomManager, simSlot: Int): PhoneAccountHandle? {
        return runCatching {
            val accounts = telecomManager.callCapablePhoneAccounts
            if (accounts.isEmpty()) return@runCatching null

            // Prefer an exact match on the subscription id for the requested SIM slot.
            val subscriptionManager =
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val targetSubId = subscriptionManager?.activeSubscriptionInfoList
                ?.firstOrNull { it.simSlotIndex == simSlot - 1 }
                ?.subscriptionId
            if (targetSubId != null) {
                accounts.firstOrNull {
                    it.id == "$targetSubId-${simSlot - 1}" || it.id.startsWith("$targetSubId/") || it.id.endsWith("/$targetSubId")
                }?.let { return@runCatching it }
            }

            // Otherwise fall back to position-based selection (accounts ordered by SIM slot).
            accounts.getOrNull(simSlot.coerceIn(1, accounts.size) - 1)
        }.getOrNull()
    }

    fun getActiveSimCards(context: Context): List<String> {
        return try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val list = sm?.activeSubscriptionInfoList
            if (list.isNullOrEmpty()) {
                listOf("SIM 1 (Default)", "SIM 2")
            } else {
                list.mapIndexed { index, info ->
                    "${info.carrierName} (SIM ${index + 1})"
                }
            }
        } catch (e: SecurityException) {
            listOf("SIM 1", "SIM 2")
        } catch (e: Exception) {
            listOf("SIM 1", "SIM 2")
        }
    }
}
