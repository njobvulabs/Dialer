package com.example.util

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Real, at-rest encryption for the File Manager Vault.
 *
 * The encryption key is derived from the shared [AppLock] PIN via
 * PBKDF2WithHmacSHA256, using the same per-install random salt as the lock so a
 * single PIN controls both locking and encryption. Each file gets its own
 * random IV (AES-256-GCM). The original filename is encrypted together with the
 * file content, and on-disk blobs are named `<uuid>.vault`, so neither the
 * content nor the filename is readable without the PIN. Losing/forgetting the
 * PIN makes the data permanently unrecoverable (standard vault behaviour).
 */
object VaultCipher {


    private val MAGIC = "NJVLT".toByteArray(Charsets.UTF_8)
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128
    private const val GCM_KEY_LENGTH_BITS = 256
    private const val PBKDF2_ITERATIONS = 120_000

    fun vaultDir(context: Context): File =
        File(context.filesDir, "Vault").apply { mkdirs() }

    private fun deriveKey(context: Context, pin: String): SecretKey {
        val salt = AppLock.getOrCreateSalt(context).toByteArray(Charsets.UTF_8)
        val spec: KeySpec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, GCM_KEY_LENGTH_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
    }

    /** Encrypts [src] (content + filename) into the Vault; removes the source on success. */
    fun vaultFile(context: Context, pin: String, src: File): Boolean {
        return try {
            val srcName = src.name.toByteArray(Charsets.UTF_8)
            val content = src.readBytes()
            val plain = ByteBuffer.allocate(4 + srcName.size + content.size)
                .putInt(srcName.size).put(srcName).put(content).array()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(context, pin))
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain)

            val blob = ByteBuffer.allocate(MAGIC.size + iv.size + encrypted.size)
                .put(MAGIC).put(iv).put(encrypted).array()
            val dest = File(vaultDir(context), "${SecureRandom().nextLong().let { if (it < 0) -it else it }}.vault")
            dest.writeBytes(blob)
            if (!src.delete()) src.deleteOnExit()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Returns the original filename for a vault blob, or null if the PIN is wrong. */
    fun peekName(context: Context, pin: String, vaultFile: File): String? =
        decrypt(context, pin, vaultFile)?.first

    /** Decrypts a vault blob to a temp file in [destDir], returning (originalName, tempFile). */
    fun decryptToFile(context: Context, pin: String, vaultFile: File, destDir: File): Pair<String, File>? {
        val (name, content) = decrypt(context, pin, vaultFile) ?: return null
        val out = File(destDir, sanitize(name)).apply { writeBytes(content) }
        return name to out
    }

    private fun decrypt(context: Context, pin: String, vaultFile: File): Pair<String, ByteArray>? {
        return try {
            val bytes = vaultFile.readBytes()
            if (bytes.size < MAGIC.size + IV_LENGTH_BYTES + TAG_LENGTH_BITS / 8) return null
            if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
            var pos = MAGIC.size
            val iv = bytes.copyOfRange(pos, pos + IV_LENGTH_BYTES); pos += IV_LENGTH_BYTES
            val ciphertext = bytes.copyOfRange(pos, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(context, pin), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            val plain = cipher.doFinal(ciphertext)
            val bb = ByteBuffer.wrap(plain)
            val nameLen = bb.int
            if (nameLen < 0 || nameLen > plain.size - 4) return null
            val nameBytes = ByteArray(nameLen); bb.get(nameBytes)
            val name = String(nameBytes, Charsets.UTF_8)
            val content = plain.copyOfRange(4 + nameLen, plain.size)
            name to content
        } catch (e: Exception) {
            null
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().ifEmpty { "vault_file" }
}
