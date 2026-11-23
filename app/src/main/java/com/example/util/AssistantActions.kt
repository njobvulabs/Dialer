package com.example.util

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.data.db.AppDatabase
import com.example.data.db.NoteEntity
import com.example.data.repository.AppRepository
import com.example.service.AudioPlaybackService
import com.example.service.AssistantOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AssistantActions {
    const val ACTION_OPEN_SHORTCUT = "com.example.action.OPEN_SHORTCUT"
    const val EXTRA_TARGET = "shortcut_target"
    const val EXTRA_PHONE = "assistant_phone"
    const val EXTRA_MODE = "assistant_mode"
    const val EXTRA_QUERY = "assistant_search"

    const val TARGET_CALL = "assistant_call"
    const val TARGET_SMS = "assistant_sms"
    const val TARGET_SEARCH = "assistant_search"

    const val MODE_SMS = "sms"

    fun open(
        context: Context,
        target: String?,
        phone: String? = null,
        mode: String? = null,
        query: String? = null
    ) {
        val value = target?.trim().takeUnless { it.isNullOrBlank() } ?: return
        val intent = android.content.Intent(ACTION_OPEN_SHORTCUT)
            .setPackage(context.packageName)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_TARGET, value)
        phone?.let { intent.putExtra(EXTRA_PHONE, it) }
        mode?.let { intent.putExtra(EXTRA_MODE, it) }
        query?.let { intent.putExtra(EXTRA_QUERY, it) }
        runCatching { context.startActivity(intent) }
    }

    fun start(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            requestOverlayPermission(context)
            Toast.makeText(context, "Grant overlay permission to start the assistant", Toast.LENGTH_LONG).show()
            return
        }
        ContextCompat.startForegroundService(context, AssistantOverlayService.startIntent(context))
    }

    fun stop(context: Context) = AssistantOverlayService.stop(context)

    fun audio(context: Context, action: String) {
        runCatching {
            ContextCompat.startForegroundService(context, AudioPlaybackService.newIntent(context, action))
        }
    }

    fun toggleTorch(context: Context) = AssistantTorchController.toggle(context)

    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun copyText(context: Context, text: String) {
        if (text.isBlank()) return
        runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            manager?.setPrimaryClip(ClipData.newPlainText("NJ-Dialer Assistant", text))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun addNote(context: Context, title: String, content: String) = withContext(Dispatchers.IO) {
        val note = NoteEntity(
            title = title.trim().ifBlank { "Quick note" },
            content = content.trim()
        )
        AppRepository(AppDatabase.getDatabase(context.applicationContext)).insertNote(note)
    }

    data class Contact(val name: String, val number: String, val favorite: Boolean)

    suspend fun contacts(context: Context): List<Contact> = withContext(Dispatchers.IO) {
        val repository = AppRepository(AppDatabase.getDatabase(context.applicationContext))
        val all = repository.getContactsOnce()
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<Contact>()

        fun add(name: String, number: String, favorite: Boolean) {
            val key = ContactNumbers.normalize(number) ?: number.trim()
            if (key.length < 3 || !seen.add(key)) return
            result += Contact(
                name = name.trim().ifBlank { number },
                number = number,
                favorite = favorite
            )
        }

        all.filter { it.isFavorite }.forEach { contact ->
            ContactNumbers.list(contact.phoneNumber).firstOrNull()?.let { number ->
                add(contact.name, number, true)
            }
        }
        repository.getCallLogsOnce()
            .sortedByDescending { it.timestamp }
            .forEach { log ->
                val matched = all.firstOrNull { ContactNumbers.contains(it.phoneNumber, log.number) }
                val name = matched?.name
                    ?: log.callerName
                    ?: log.number
                add(name, ContactNumbers.primary(log.number), matched?.isFavorite == true)
                if (result.size >= 8) return@forEach
            }
        result.take(8)
    }
}
