package com.example.ui.screens.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.service.AssistantOverlayService
import com.example.ui.components.AppIcons
import com.example.util.AssistantCameraStore

@SuppressLint("MissingPermission")
@Composable
fun AssistantInlineCameraCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    val cameraForegroundReady by AssistantOverlayService.cameraForegroundReady.collectAsState()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var previewUseCase by remember { mutableStateOf<Preview?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openPermissions() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            runCatching {
                ContextCompat.startForegroundService(context, AssistantOverlayService.cameraIntent(context))
            }
        }
    }

    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                provider = runCatching { future.get() }.getOrNull()
                    ?: run {
                        error = "Could not start camera"
                        null
                    }
            },
            mainExecutor
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            val useCases = listOfNotNull(previewUseCase, imageCapture)
            runCatching {
                if (useCases.isEmpty()) provider?.unbindAll()
                else provider?.unbind(*useCases.toTypedArray())
            }
            imageCapture = null
            previewUseCase = null
            previewView = null
        }
    }

    fun bindCamera(view: PreviewView) {
        ready = false
        val cameraProvider = provider
        val oldUseCases = listOfNotNull(previewUseCase, imageCapture)
        if (cameraProvider != null && oldUseCases.isNotEmpty()) {
            runCatching { cameraProvider.unbind(*oldUseCases.toTypedArray()) }
        }
        imageCapture = null
        previewUseCase = null
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted != hasPermission) hasPermission = granted
        if (!granted) {
            error = "Camera permission needed"
            return
        }
        if (!cameraForegroundReady) {
            error = "Starting camera service…"
            return
        }
        if (cameraProvider == null) return
        val frontSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()
        if (lensFacing == CameraSelector.LENS_FACING_FRONT && !cameraProvider.hasCamera(frontSelector)) {
            lensFacing = CameraSelector.LENS_FACING_BACK
        }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (!cameraProvider.hasCamera(selector)) {
            error = "Camera not available"
            return
        }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        runCatching {
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            previewUseCase = preview
            imageCapture = capture
            ready = true
            error = null
        }.onFailure {
            imageCapture = null
            previewUseCase = null
            error = it.message ?: "Camera unavailable"
        }
    }

    LaunchedEffect(hasPermission, lensFacing, provider, previewView, cameraForegroundReady) {
        val view = previewView ?: return@LaunchedEffect
        bindCamera(view)
    }

    fun takePhoto() {
        val capture = imageCapture
        if (capture == null) {
            Toast.makeText(context, "Camera is not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val file = AssistantCameraStore.newPhotoFile(context)
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    AssistantCameraStore.scan(context, file)
                    Toast.makeText(context, "Saved ${file.name}", Toast.LENGTH_SHORT).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    runCatching { file.delete() }
                    Toast.makeText(
                        context,
                        exception.message ?: "Could not save photo",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = AppIcons.PhotoCamera,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Inline camera",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (hasPermission) {
                    IconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            AppIcons.Cameraswitch,
                            contentDescription = "Switch camera",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (!hasPermission) {
                Text(
                    "Camera permission is needed for inline photos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = { openPermissions() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open permissions")
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                            }
                        },
                        update = { previewView = it },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (!ready && error == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (error != null) {
                        Text(
                            text = error ?: "Camera unavailable",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Button(
                    onClick = ::takePhoto,
                    enabled = ready && imageCapture != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        AppIcons.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Capture photo")
                }
            }
        }
    }
}
