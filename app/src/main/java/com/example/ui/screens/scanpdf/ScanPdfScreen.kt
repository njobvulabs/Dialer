package com.example.ui.screens.scanpdf

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import com.example.util.PublicDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun ScanPdfScreen(viewModel: MainViewModel? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val useGlassmorphism = viewModel?.useGlassmorphism?.collectAsState()?.value ?: false
    var pages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var exporting by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected -> if (selected.isNotEmpty()) pages = (pages + selected).distinct() }
    val activity = context as? Activity
    LaunchedEffect(Unit) {
        val intent = activity?.intent
        val uriStr = intent?.getStringExtra("scan_pdf_uri")
        if (!uriStr.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(uriStr)
                pages = (pages + uri).distinct()
                intent.removeExtra("scan_pdf_uri")
            } catch (_: Exception) {}
        }
    }
    fun export() {
        if (pages.isEmpty()) {
            Toast.makeText(context, "Add photos first", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            exporting = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    PublicDownloads.saveBytes(context, "Scans", "scan_${System.currentTimeMillis()}.pdf", createPdf(context, pages), "application/pdf")
                }
            }
            withContext(Dispatchers.Main) {
                exporting = false
                result.fold(onSuccess = { saved ->
                    Toast.makeText(context, "Saved to ${saved.displayPath}", Toast.LENGTH_LONG).show()
                    saved.uri?.let { uri ->
                        try {
                            val viewIntent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/pdf"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            val chooser = Intent.createChooser(viewIntent, "Open PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(chooser)
                        } catch (_: Exception) {}
                    }
                }, onFailure = { Toast.makeText(context, "PDF export failed: ${it.localizedMessage}", Toast.LENGTH_LONG).show() })
            }
        }
    }
    Scaffold(floatingActionButton = { ExtendedFloatingActionButton(onClick = { if (pages.isNotEmpty() && !exporting) export() }, icon = { Icon(AppIcons.PictureAsPdf, null) }, text = { Text("Create PDF") }, expanded = pages.isNotEmpty() && !exporting) }) { padding ->
        Column(Modifier.fillMaxSize()) {
            FloatingTopBar(useGlass = useGlassmorphism) { Icon(AppIcons.DocumentScanner, null); Spacer(Modifier.width(10.dp)); Text("Scan to PDF", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)) }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 80.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) { Icon(AppIcons.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Add photos") }
                Text("Select document photos. They are converted locally into a single shareable PDF.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (pages.isEmpty()) Text("No photos added yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(pages) { uri ->
                            val bmp = remember(uri) { loadBitmap(context, uri) }
                            Card(Modifier.width(160.dp).height(220.dp)) {
                                if (bmp != null) Image(bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun loadBitmap(context: android.content.Context, uri: Uri): Bitmap? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
}.getOrNull()

private fun createPdf(context: android.content.Context, uris: List<Uri>): ByteArray {
    if (uris.isEmpty()) return ByteArray(0)
    val document = PdfDocument()
    uris.forEachIndexed { index, uri ->
        val bmp = loadBitmap(context, uri) ?: return@forEachIndexed
        val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, index + 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        canvas.drawBitmap(bmp, 0f, 0f, null)
        document.finishPage(page)
    }
    val out = ByteArrayOutputStream()
    document.writeTo(out)
    document.close()
    return out.toByteArray()
}
