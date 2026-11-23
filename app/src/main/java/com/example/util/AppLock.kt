package com.example.util

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Shared lock for the app (Notes private vault + File Vault).
 *
 * A single PIN unlocks all protected areas. The PIN is never stored; only a
 * salted SHA-256 digest is kept. The salt is random and generated once per
 * app install so identical PINs never produce the same stored hash across
 * installs. NOTE: SHA-256 is intentionally used for fingerprinting parity with
 * the existing Notes implementation; see VaultCipher for the AES-GCM layer used
 * to actually encrypt file content with the same PIN.
 */
object AppLock {
    private const val LOCK_PREFS = "app_lock_prefs"
    private const val SALT_KEY = "lock_salt"
    private const val PIN_HASH_KEY = "pin_hash"

    private fun prefs(context: Context) =
        context.getSharedPreferences(LOCK_PREFS, Context.MODE_PRIVATE)

    fun getOrCreateSalt(context: Context): String {

        prefs(context).getString(SALT_KEY, null)?.let { return it }
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val salt = bytes.joinToString("") { "%02x".format(it) }
        prefs(context).edit().putString(SALT_KEY, salt).apply()
        return salt
    }

    fun hashPin(context: Context, pin: String): String {
        val salt = getOrCreateSalt(context)
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest((salt + pin + salt).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun isPinSet(context: Context): Boolean = prefs(context).contains(PIN_HASH_KEY)

    fun setPin(context: Context, pin: String) {
        prefs(context).edit().putString(PIN_HASH_KEY, hashPin(context, pin)).apply()
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val stored = prefs(context).getString(PIN_HASH_KEY, null) ?: return false
        return stored == hashPin(context, pin)
    }

    fun changePin(context: Context, oldPin: String, newPin: String): Boolean {
        if (!verifyPin(context, oldPin)) return false
        setPin(context, newPin)
        return true
    }

    fun clearPin(context: Context) {
        prefs(context).edit().remove(PIN_HASH_KEY).apply()
    }
}
