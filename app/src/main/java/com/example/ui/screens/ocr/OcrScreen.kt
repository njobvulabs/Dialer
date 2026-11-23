package com.example.ui.screens.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.components.AppIcons
import com.example.ui.theme.AppCornerRadius
import com.example.util.copyUriToCache
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun OcrScreen(
    onClose: () -> Unit,
    onTakePhotoFromCamera: () -> Unit = {},
    pendingFile: java.io.File? = null,
    onPendingConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var extracted by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun runOcr(bmp: Bitmap) {
        bitmap = bmp
        busy = true
        val image = InputImage.fromBitmap(bmp, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                extracted = visionText.text
                busy = false
                if (extracted.isBlank()) {
                    Toast.makeText(context, "No text found in image", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                busy = false
                Toast.makeText(context, "OCR failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    fun loadAndRun(path: String) {
        scope.launch {
            busy = true
            val bmp = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    BitmapFactory.decodeFile(path) ?: decodeSampled(path)
                }.getOrNull()
            }
            if (bmp != null) runOcr(bmp) else {
                busy = false
                Toast.makeText(context, "Could not load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK || result.data == null) return@rememberLauncherForActivityResult
        val uri = result.data!!.clipData?.getItemAt(0)?.uri ?: result.data!!.data ?: return@rememberLauncherForActivityResult
        val file = copyUriToCache(context, uri) ?: run {
            Toast.makeText(context, "Could not load image", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        loadAndRun(file.absolutePath)
    }

    // When returning from the in-app camera with a captured photo, run OCR on it.
    LaunchedEffect(pendingFile) {
        val f = pendingFile ?: return@LaunchedEffect
        loadAndRun(f.absolutePath)
        onPendingConsumed()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onTakePhotoFromCamera, modifier = Modifier.weight(1f)) {
                    Icon(AppIcons.PhotoCamera, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Take Photo")
                }
                OutlinedButton(
                    onClick = {
                        pickLauncher.launch(
                            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "image/*"
                            }
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(AppIcons.PhotoLibrary, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("From Gallery")
                }
            }

            if (busy) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Reading text...", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }

            bitmap?.let {
                Spacer(Modifier.height(12.dp))
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Selected image",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(AppCornerRadius.md)
                )
            }

            if (extracted.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Extracted Text", fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("ocr", extracted)
                        )
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }) { Icon(AppIcons.ContentCopy, contentDescription = "Copy") }
                    IconButton(onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, extracted)
                        }
                        runCatching {
                            context.startActivity(
                                android.content.Intent.createChooser(send, "Share text")
                            )
                        }
                    }) { Icon(AppIcons.Share, contentDescription = "Share") }
                    IconButton(onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val db = com.example.data.db.AppDatabase.getDatabase(context)
                                com.example.data.repository.AppRepository(db).insertNote(
                                    com.example.data.db.NoteEntity(
                                        title = "Scanned ${java.text.SimpleDateFormat("MMM d HH:mm",
                                            java.util.Locale.getDefault()).format(java.util.Date())}",
                                        content = extracted
                                    )
                                )
                            }.onSuccess {
                                launch(kotlinx.coroutines.Dispatchers.Main) {
                                    Toast.makeText(context, "Saved to Notes", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }) { Icon(AppIcons.Save, contentDescription = "Save to Notes") }
                }
                Surface(
                    shape = AppCornerRadius.md,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        extracted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else if (!busy && bitmap == null) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Point at any document, sign or screen and extract its text fully offline.\n\n" +
                        "Great for receipts, notes, exam questions and printed pages.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun decodeSampled(path: String, reqW: Int = 1600): Bitmap? =
    runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val sample = maxOf(1, opts.outWidth / reqW)
        BitmapFactory.decodeFile(
            path, BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }.getOrNull()
