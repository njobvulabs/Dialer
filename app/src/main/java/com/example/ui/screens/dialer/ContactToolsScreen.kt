package com.example.ui.screens.dialer

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.db.AppDatabase
import com.example.data.repository.AppRepository
import com.example.ui.components.AppIcons
import com.example.util.ContactTools
import com.example.util.copyUriToCache
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ContactToolsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { AppRepository(AppDatabase.getDatabase(context)) }

    var duplicates by remember { mutableStateOf<List<ContactTools.DuplicateGroup>>(emptyList()) }
    var scanned by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    fun msg(s: String) {
        status = s
        Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
    }

    val pickVcfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK || result.data == null) return@rememberLauncherForActivityResult
        val uri = result.data!!.clipData?.getItemAt(0)?.uri ?: result.data!!.data ?: return@rememberLauncherForActivityResult
        val file = copyUriToCache(context, uri, "vcf") ?: run { msg("Failed to read file"); return@rememberLauncherForActivityResult }
        busy = true
        scope.launch {
            val n = ContactTools.importContactsVcf(repository, file)
            busy = false
            msg("Imported $n contacts")
        }
    }
    val pickCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK || result.data == null) return@rememberLauncherForActivityResult
        val uri = result.data!!.clipData?.getItemAt(0)?.uri ?: result.data!!.data ?: return@rememberLauncherForActivityResult
        val file = copyUriToCache(context, uri, "csv") ?: run { msg("Failed to read file"); return@rememberLauncherForActivityResult }
        busy = true
        scope.launch {
            val n = ContactTools.importCallLogsCsv(repository, file)
            busy = false
            msg("Imported $n call log entries")
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("Contact Tools", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            if (busy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (status.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(status, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(14.dp))

            ToolCard(
                title = "Duplicate Contacts",
                description = "Find contacts that share a phone number or name and merge them."
            ) {
                Button(onClick = {
                    busy = true
                    scope.launch {
                        duplicates = ContactTools.findDuplicates(repository)
                        scanned = true
                        busy = false
                        msg("Found ${duplicates.size} duplicate group(s)")
                    }
                }) { Text("Scan for Duplicates") }

                if (scanned && duplicates.isEmpty()) {
                    Text("No duplicates found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp))
                }
                duplicates.forEach { group ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(10.dp)
                    ) {
                        Text(group.primary.name, fontWeight = FontWeight.SemiBold)
                        Text(group.primary.phoneNumber,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        group.duplicates.forEach { d ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.name,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text("${d.phoneNumber} • will be removed",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        Button(
                            onClick = {
                                busy = true
                                scope.launch {
                                    ContactTools.mergeGroup(repository, group.primary, group.duplicates)
                                    duplicates = ContactTools.findDuplicates(repository)
                                    busy = false
                                    msg("Contacts merged")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(AppIcons.MergeType, null, Modifier.size(16.dp))
                            Text(" Merge into \"${group.primary.name}\"")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            ToolCard(
                title = "Contacts Backup (offline)",
                description = "Export all in-app contacts to a VCF file, or import contacts back from one."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        busy = true
                        scope.launch {
                            val dir = File(
                                android.os.Environment.getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS
                                ), "NJ-Dialer"
                            )
                            dir.mkdirs()
                            val f = File(dir, "contacts_${System.currentTimeMillis()}.vcf")
                            val out = ContactTools.exportContactsVcf(repository, f)
                            busy = false
                            msg(if (out != null) "Saved to ${out.absolutePath}" else "No contacts to export")
                        }
                    }) {
                        Icon(AppIcons.SaveAlt, null, Modifier.size(16.dp))
                        Text(" Export VCF")
                    }
                    OutlinedButton(onClick = {
                        pickVcfLauncher.launch(
                            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "text/vcard"
                            }
                        )
                    }) {
                        Icon(AppIcons.Restore, null, Modifier.size(16.dp))
                        Text(" Import VCF")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            ToolCard(
                title = "Call Log Backup (offline)",
                description = "Export your call history to CSV or restore it from a previous export."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        busy = true
                        scope.launch {
                            val logs = repository.getCallLogsOnce()
                            val dir = File(
                                android.os.Environment.getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS
                                ), "NJ-Dialer"
                            )
                            dir.mkdirs()
                            val f = File(dir, "calllog_${System.currentTimeMillis()}.csv")
                            val out = ContactTools.exportCallLogsCsv(logs, f)
                            busy = false
                            msg(if (out != null) "Saved ${logs.size} entries" else "Export failed")
                        }
                    }) {
                        Icon(AppIcons.SaveAlt, null, Modifier.size(16.dp))
                        Text(" Export CSV")
                    }
                    OutlinedButton(onClick = {
                        pickCsvLauncher.launch(
                            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "text/*"
                            }
                        )
                    }) {
                        Icon(AppIcons.Restore, null, Modifier.size(16.dp))
                        Text(" Import CSV")
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Group, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(title, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Text(description, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        content()
    }
}
