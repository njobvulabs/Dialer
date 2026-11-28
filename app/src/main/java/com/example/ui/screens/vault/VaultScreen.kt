package com.example.ui.screens.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.components.landscapeContentWidth
import com.example.ui.components.AppIcons
import com.example.util.AppLock
import com.example.util.StorageHelper
import com.example.util.VaultCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class VaultEntry(val file: File, val name: String, val size: Long)

/**
 * Locked vault of authenticated, at-rest encrypted files. Uses the shared app
 * PIN (see AppLock) so one PIN unlocks Notes + this File Vault. Files are
 * AES-256-GCM encrypted and stored in app-private storage; names and content
 * are unrecoverable without the PIN.
 */
@Composable
fun VaultScreen(onOpenMedia: (File, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var unlocked by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pinDraft by remember { mutableStateOf("") }
    var setupMode by remember { mutableStateOf(!AppLock.isPinSet(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<VaultEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<VaultEntry?>(null) }

    fun loadEntries() {
        if (!unlocked) return
        loading = true
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                val dir = VaultCipher.vaultDir(context)
                dir.listFiles()?.filter { it.name.endsWith(".vault") }?.mapNotNull { f ->
                    val name = VaultCipher.peekName(context, pin, f) ?: return@mapNotNull null
                    VaultEntry(f, name, f.length())
                }?.sortedBy { it.name } ?: emptyList()
            }
            entries = list
            loading = false
        }
    }

    val addLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            var ok = 0
            for (uri in uris) {
                val added = withContext(Dispatchers.IO) {
                    val name = queryDisplayName(context, uri) ?: "file_${System.currentTimeMillis()}"
                    val tmp = File(context.cacheDir, "vault_add_$name")
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { it.write(input.readBytes()) }
                            val res = VaultCipher.vaultFile(context, pin, tmp)
                            tmp.delete()
                            res
                        } ?: false
                    }.getOrDefault(false)
                }
                if (added) ok++
            }
            loading = false
            loadEntries()
            Toast.makeText(context, "Encrypted $ok file(s) to Vault", Toast.LENGTH_SHORT).show()
        }
    }

    fun unlock(pinStr: String) {
        if (setupMode) {
            if (pinStr.length < 4) { error = "PIN must be at least 4 characters"; return }
            AppLock.setPin(context, pinStr)
            pin = pinStr
            setupMode = false
            unlocked = true
            loadEntries()
        } else {
            if (!AppLock.verifyPin(context, pinStr)) {
                error = "Incorrect PIN"
                return
            }
            pin = pinStr
            unlocked = true
            loadEntries()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (!unlocked) {
            Column(
                modifier = Modifier
                    .fillMaxSize().landscapeContentWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(AppIcons.Lock, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                Spacer(Modifier.height(14.dp))
                Text(
                    if (setupMode) "Set a Vault PIN" else "Unlock Vault",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "The same PIN protects your private notes and this encrypted Vault.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = pinDraft,
                    onValueChange = { pinDraft = it; error = null },
                    label = { Text("PIN") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { unlock(pinDraft.trim()) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (setupMode) "Create PIN" else "Unlock")
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "Encrypted Vault (${entries.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (entries.isEmpty() && !loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Vault is empty — use + to add files", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(entries, key = { it.file.absolutePath }) { entry ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(AppIcons.Lock, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            formatSize(entry.size),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = {
                                        scope.launch {
                                            val res = withContext(Dispatchers.IO) {
                                                val dir = File(context.cacheDir, "vault_open").apply { mkdirs() }
                                                VaultCipher.decryptToFile(context, pin, entry.file, dir)
                                            }
                                            if (res != null) {
                                                onOpenMedia(res.second, StorageHelper.getMimeType(res.second))
                                            } else {
                                                Toast.makeText(context, "Open failed (wrong PIN?)", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }) {
                                        Icon(AppIcons.Visibility, contentDescription = "Open")
                                    }
                                    IconButton(onClick = {
                                        scope.launch {
                                            val res = withContext(Dispatchers.IO) {
                                                val dir = File(context.getExternalFilesDir(null), "Vault_Extracted").apply { mkdirs() }
                                                VaultCipher.decryptToFile(context, pin, entry.file, dir)
                                            }
                                            if (res != null) {
                                                Toast.makeText(context, "Extracted to app folder (see File Manager)", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Extract failed (wrong PIN?)", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }) {
                                        Icon(AppIcons.Output, contentDescription = "Extract")
                                    }
                                    IconButton(onClick = { pendingDelete = entry }) {
                                        Icon(AppIcons.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
                if (entries.isNotEmpty()) {
                    Text(
                        "Opened files are temporarily decrypted in the cache; extracted copies go to app-private storage.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
            FloatingActionButton(
                onClick = { addLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) {
                Icon(AppIcons.Add, contentDescription = "Add files")
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${entry.name}\"?") },
            text = { Text("The encrypted file will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    entry.file.delete()
                    pendingDelete = null
                    loadEntries()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
    }
}.getOrNull()

private fun formatSize(bytes: Long): String = com.example.ui.components.formatFileSize(bytes)
