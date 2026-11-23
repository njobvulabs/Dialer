package com.example.ui.screens.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.view.Surface
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.compose.foundation.border
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.media3.effect.Media3Effect
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import com.example.ui.components.AppIcons
import com.example.ui.theme.AppCornerRadius
import com.example.util.RecycleBin
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.util.Consumer
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Effect
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import coil.compose.AsyncImage
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

// Tecno / Transsion Camera Modes
private enum class TecnoMode(val label: String, val isVideoMode: Boolean) {
    SHORT_VIDEO("SHORT VIDEO", true),
    VIDEO("VIDEO", true),
    AI_CAM("AI CAM", false),
    PORTRAIT("PORTRAIT", false),
    PRO("PRO", false)
}

private enum class AspectRatioMode(val label: String, val ratio: Float?, val cxRatio: Int) {
    RATIO_4_3("4:3", 3f / 4f, AspectRatio.RATIO_4_3),
    RATIO_16_9("16:9", 9f / 16f, AspectRatio.RATIO_16_9),
    RATIO_1_1("1:1", 1f, AspectRatio.RATIO_4_3),
    FULL("FULL", null, AspectRatio.RATIO_4_3)
}

private enum class FlashMode { OFF, ON, AUTO, TORCH }

private enum class CameraFilter(val label: String) {
    NORMAL("Original"), VIVID("Vivid"), MONO("Mono"), WARM("Warm"),
    COOL("Cool"), RETRO("Retro"), SEPIA("Sepia")
}

private enum class SceneType(val label: String) {
    AUTO("Auto"), NIGHT("Night"), SCENERY("Scenery"), FOOD("Food"),
    PORTRAIT("Portrait"), DOCUMENT("Document")
}

// Cap on the long edge of the processed photo. Decoding/re-encoding a full-res
// JPEG up to four times in a row OOMs on this device; a bounded single decode +
// single encode keeps the pipeline in a safe memory envelope.
private enum class PhotoResolution(val label: String, val maxLongEdge: Int) {
    FULL("Full", 0),
    P4096("4096 px", 4096),
    P2048("2048 px", 2048)
}

// CameraX Extensions mode selected for a given Tecno mode. Only modes that map onto an
// OEM extension are routed through it; every other mode uses the plain CameraX path.
private fun TecnoMode.extensionMode(): Int = when (this) {
    TecnoMode.AI_CAM -> ExtensionMode.AUTO
    TecnoMode.PORTRAIT -> ExtensionMode.BOKEH
    else -> ExtensionMode.NONE
}

// Persisted camera preferences (keep-screen-on, photo resolution, beauty).
private object CameraPrefs {
    private const val FILE = "camera"
    private const val KEY_KEEP_SCREEN = "keep_screen_on"
    private const val KEY_RESOLUTION = "photo_resolution"
    private const val KEY_BEAUTY = "beauty_strength"

    fun keepScreenOn(ctx: android.content.Context): Boolean =
        ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).getBoolean(KEY_KEEP_SCREEN, true)
    fun setKeepScreenOn(ctx: android.content.Context, v: Boolean) =
        ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP_SCREEN, v).apply()

    fun resolution(ctx: android.content.Context): PhotoResolution {
        val name = ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
            .getString(KEY_RESOLUTION, PhotoResolution.P4096.name)
        return runCatching { PhotoResolution.valueOf(name!!) }.getOrDefault(PhotoResolution.P4096)
    }
    fun setResolution(ctx: android.content.Context, v: PhotoResolution) =
        ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit().putString(KEY_RESOLUTION, v.name).apply()

    fun beautyStrength(ctx: android.content.Context): Float =
        ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).getFloat(KEY_BEAUTY, 0f)
    fun setBeautyStrength(ctx: android.content.Context, v: Float) =
        ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit().putFloat(KEY_BEAUTY, v).apply()
}

// Live viewfinder filter → media3 Effect pipeline (PREVIEW | VIDEO_CAPTURE).
private fun cameraFilterEffects(filter: CameraFilter): List<Effect> = when (filter) {
    CameraFilter.NORMAL -> emptyList()
    CameraFilter.VIVID -> listOf(HslAdjustment.Builder().adjustSaturation(0.5f).build())
    CameraFilter.MONO -> listOf(RgbFilter.createGrayscaleFilter())
    CameraFilter.WARM -> listOf(
        RgbAdjustment.Builder().setRedScale(1.1f).setGreenScale(1.0f).setBlueScale(0.9f).build()
    )
    CameraFilter.COOL -> listOf(
        RgbAdjustment.Builder().setRedScale(0.9f).setGreenScale(1.0f).setBlueScale(1.15f).build()
    )
    CameraFilter.RETRO -> listOf(
        RgbAdjustment.Builder().setRedScale(1.05f).setGreenScale(0.98f).setBlueScale(0.9f).build(),
        HslAdjustment.Builder().adjustSaturation(-0.2f).adjustLightness(0.04f).build()
    )
    CameraFilter.SEPIA -> listOf(
        RgbAdjustment.Builder().setRedScale(1.12f).setGreenScale(0.96f).setBlueScale(0.8f).build(),
        HslAdjustment.Builder().adjustSaturation(-0.35f).adjustLightness(0.03f).build()
    )
}

private fun qualityLabel(cameraInfo: androidx.camera.core.CameraInfo, q: Quality): String {
    val h = QualitySelector.getResolution(cameraInfo, q)?.height ?: 0
    return when {
        h >= 3500 -> "4K"
        h >= 1300 -> "2K"
        h >= 900 -> "1080P"
        h >= 600 -> "720P"
        h >= 400 -> "480P"
        h > 0 -> "${h}P"
        else -> when (q) {
            Quality.UHD -> "4K"
            Quality.FHD -> "1080P"
            Quality.HD -> "720P"
            Quality.SD -> "480P"
            Quality.LOWEST -> "Lowest"
            Quality.HIGHEST -> "Highest"
            else -> "Auto"
        }
    }
}

private fun buildVideoQualitySelector(q: Quality?): QualitySelector {
    val fallback = FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
    return if (q == null) {
        QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD), fallback)
    } else {
        QualitySelector.fromOrderedList(listOf(q, Quality.FHD, Quality.HD, Quality.SD), fallback)
    }
}

private fun lensFacingOf(info: androidx.camera.core.CameraInfo): Int {
    val facing = runCatching {
        Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
    }.getOrNull()
    return (facing as? Int) ?: CameraSelector.LENS_FACING_UNKNOWN
}

private object CameraStore {
    fun dir(context: Context): File {
        val fallback = File(context.filesDir, "Camera")
        return runCatching {
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val d = File(base, "NJ-Dialer/Camera")
            if (d.exists() || d.mkdirs()) d else fallback.also { runCatching { it.mkdirs() } }
        }.getOrElse {
            fallback.also { runCatching { it.mkdirs() } }
        }
    }

    fun list(context: Context): List<File> = runCatching {
        dir(context).listFiles { f -> f.isFile }.orEmpty()
            .sortedByDescending { it.lastModified() }
    }.getOrDefault(emptyList())

    fun newPhotoFile(context: Context): File = runCatching {
        File(dir(context), "IMG_${System.currentTimeMillis()}.jpg")
    }.getOrDefault(File(context.cacheDir, "IMG_${System.currentTimeMillis()}.jpg"))

    fun newVideoFile(context: Context): File = runCatching {
        File(dir(context), "VID_${System.currentTimeMillis()}.mp4")
    }.getOrDefault(File(context.cacheDir, "VID_${System.currentTimeMillis()}.mp4"))

    fun scan(context: Context, file: File) {
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        }
    }
}

@Composable
fun CameraScreen(
    onBack: () -> Unit,
    onOpenMedia: (File, String) -> Unit = { _, _ -> },
    onOpenImageWithPlaylist: (File, List<File>) -> Unit = { _, _ -> },
    onPhotoCaptured: (File) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val mainExecutor = ContextCompat.getMainExecutor(context)

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var media3Effect by remember { mutableStateOf<Media3Effect?>(null) }
    var shortVideoJob by remember { mutableStateOf<Job?>(null) }

    var cameraError by remember { mutableStateOf(false) }
    var retryAttempt by remember { mutableIntStateOf(0) }

    var lensFacing by remember {
        mutableIntStateOf(if (hasBackCamera(context)) CameraCharacteristics.LENS_FACING_BACK
        else CameraCharacteristics.LENS_FACING_FRONT)
    }

    var selectedMode by remember { mutableStateOf(TecnoMode.AI_CAM) }
    var selectedAspectRatio by remember { mutableStateOf(AspectRatioMode.FULL) }
    var flash by remember { mutableStateOf(FlashMode.AUTO) }
    var hdrEnabled by remember { mutableStateOf(false) }
    var iso by remember { mutableIntStateOf(100) }
    var exposureTime by remember { mutableLongStateOf(10000000L) }
    var wbMode by remember { mutableIntStateOf(CaptureRequest.CONTROL_AWB_MODE_AUTO) }
    var wbPickerOpen by remember { mutableStateOf(false) }
    var beautyStrength by remember { mutableFloatStateOf(CameraPrefs.beautyStrength(context)) }
    var cameraFilter by remember { mutableStateOf(CameraFilter.NORMAL) }
    var showProDialog by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(false) }
    var timerSeconds by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(8f) }

    var isRecording by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var recordMs by remember { mutableLongStateOf(0L) }
    var capturedFiles by remember { mutableStateOf<List<File>>(CameraStore.list(context)) }
    var showGallery by remember { mutableStateOf(false) }

    var videoQuality by remember { mutableStateOf<Quality?>(null) }
    var videoQualities by remember { mutableStateOf<List<Quality>>(emptyList()) }
    var shortVideoDuration by remember { mutableIntStateOf(15) }
    var showFilterTray by remember { mutableStateOf(false) }
    /** Awaiting confirmation in the delete dialog; the badge no longer deletes outright. */
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    var scanEnabled by remember { mutableStateOf(false) }
    var scanResult by remember { mutableStateOf<String?>(null) }
    var lastScanMs by remember { mutableLongStateOf(0L) }
    var detectedScene by remember { mutableStateOf<SceneType?>(null) }

    // CameraX Extensions (OEM vendor pipeline: Auto / Portrait-Bokeh / HDR / Night / FaceRetouch).
    // Availability is queried once the provider is ready; when the device ships no vendor
    // library the whole feature degrades to the plain CameraX path.
    var extensionsManager by remember { mutableStateOf<ExtensionsManager?>(null) }
    var extensionAvailability by remember { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }
    var extensionInUse by remember { mutableStateOf(false) }

    // Camera settings (persisted)
    var keepScreenOn by remember { mutableStateOf(CameraPrefs.keepScreenOn(context)) }
    var photoResolution by remember { mutableStateOf(CameraPrefs.resolution(context)) }
    var showSettings by remember { mutableStateOf(false) }

    // Exposure compensation
    var evIndex by remember { mutableIntStateOf(0) }
    var evRange by remember { mutableStateOf<IntRange?>(null) }
    var evStep by remember { mutableFloatStateOf(1f / 3f) }
    var showEv by remember { mutableStateOf(false) }
    var showBeauty by remember { mutableStateOf(false) }

    // Tap-to-focus ring
    var focusRing by remember { mutableStateOf<Offset?>(null) }
    var focusTick by remember { mutableIntStateOf(0) }

    val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    fun toast(msg: String) {
        runCatching { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    LaunchedEffect(retryAttempt) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = runCatching { future.get() }.getOrNull()
            cameraError = cameraProvider == null
        }, mainExecutor)
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { activeRecording?.stop() }
            runCatching { media3Effect?.close() }
            runCatching { cameraProvider?.unbindAll() }
        }
    }

    // Keep the screen awake while the camera is open (keeps the viewfinder live).
    val cameraView = LocalView.current
    DisposableEffect(keepScreenOn) {
        cameraView.keepScreenOn = keepScreenOn
        onDispose { cameraView.keepScreenOn = false }
    }

    fun flashToCX(): Int = when (flash) {
        FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashMode.ON -> ImageCapture.FLASH_MODE_ON
        FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
        FlashMode.TORCH -> ImageCapture.FLASH_MODE_OFF
    }

    fun buildResolutionSelector(): ResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(
            AspectRatioStrategy(selectedAspectRatio.cxRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        )
        .build()

    // Query device-supported video qualities for the active camera (2K/1080P/720P/480P).
    LaunchedEffect(cameraProvider, lensFacing) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val info = provider.availableCameraInfos.firstOrNull { lensFacingOf(it) == lensFacing }
        val qs = if (info != null) QualitySelector.getSupportedQualities(info) else emptyList()
        videoQualities = qs
        if (videoQuality == null && qs.isNotEmpty()) videoQuality = qs.first()
    }

    // Query CameraX Extensions availability once the provider is ready. This asks the OEM
    // vendor library that ships on the device (if any) which extension modes it supports.
    LaunchedEffect(cameraProvider, lensFacing) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val selector = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        val manager = runCatching {
            suspendCancellableCoroutine<ExtensionsManager> { cont ->
                val future = ExtensionsManager.getInstanceAsync(context.applicationContext, provider)
                future.addListener({
                    runCatching { future.get() }
                        .onSuccess { if (!cont.isCancelled) cont.resumeWith(Result.success(it)) }
                        .onFailure { if (!cont.isCancelled) cont.resumeWith(Result.failure(it)) }
                }, mainExecutor)
            }
        }.getOrNull()
        extensionsManager = manager
        extensionAvailability = if (manager == null) emptyMap() else listOf(
            ExtensionMode.AUTO, ExtensionMode.BOKEH, ExtensionMode.HDR,
            ExtensionMode.NIGHT, ExtensionMode.FACE_RETOUCH
        ).associateWith {
            runCatching { manager.isExtensionAvailable(selector, it) }.getOrDefault(false)
        }
    }

    // Apply live filter updates without rebinding.
    LaunchedEffect(cameraFilter) {
        runCatching { media3Effect?.setEffects(cameraFilterEffects(cameraFilter)) }
    }

    // Bind CameraX with dynamic aspect ratio / quality / mode config
    LaunchedEffect(
        previewView, lensFacing, selectedMode, selectedAspectRatio, hdrEnabled, iso,
        exposureTime, wbMode, videoQuality, scanEnabled
    ) {
        val pv = previewView ?: return@LaunchedEffect
        val provider = cameraProvider ?: return@LaunchedEffect
        try {
            provider.unbindAll()
            runCatching { media3Effect?.close() }
            media3Effect = null

            val selector = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            // Route the mode through the OEM vendor extension when the device ships one.
            // Pro / Video modes always take the plain path (manual ISO / EV / WB and video).
            val extensionMode = selectedMode.extensionMode()
            var useExtension = extensionMode != ExtensionMode.NONE &&
                extensionsManager != null && extensionAvailability[extensionMode] == true

            val resolutionSelector = buildResolutionSelector()
            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
            preview.setSurfaceProvider(pv.surfaceProvider)

            val imgCap = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashToCX())
                .setTargetRotation(context.display?.rotation ?: Surface.ROTATION_0)
                .setResolutionSelector(resolutionSelector)
                .apply {
                    // Manual sensor overrides are Pro-style controls; the vendor extension owns
                    // the pipeline, so never poke it while an extension is active.
                    if (!useExtension) runCatching {
                        Camera2Interop.Extender(this).apply {
                            setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                            setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)
                            setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, wbMode)
                            if (hdrEnabled) {
                                setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR)
                            }
                        }
                    }
                }
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(buildVideoQualitySelector(videoQuality))
                .build()
            val vidCap = VideoCapture.withOutput(recorder)

            // Extensions and the media3 effect pipeline can't be combined, so the live filter
            // preview is skipped while an extension is active.
            val effect = if (useExtension) null else runCatching {
                Media3Effect(
                    context,
                    CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
                    mainExecutor,
                    Consumer<Throwable> { toast("Filter error") }
                ).also { it.setEffects(cameraFilterEffects(cameraFilter)) }
            }.getOrNull()
            media3Effect = effect

            val barcodeScanner = if (scanEnabled) {
                com.google.mlkit.vision.barcode.BarcodeScanning.getClient()
            } else null
            val barcodeAnalysis = if (barcodeScanner != null) {
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .apply {
                        setAnalyzer(
                            mainExecutor,
                            BarcodeAnalyzer(barcodeScanner) { value ->
                                if (System.currentTimeMillis() - lastScanMs > 2500) {
                                    lastScanMs = System.currentTimeMillis()
                                    scanResult = value
                                }
                            }
                        )
                    }
            } else null

            // Some vendor extensions can't carry an ImageAnalysis stream; drop live scanning
            // for that session rather than fail the bind.
            val analysisOk = !useExtension || runCatching {
                extensionsManager!!.isImageAnalysisSupported(selector, extensionMode)
            }.getOrDefault(false)

            fun bindGroup(sel: CameraSelector, withBarcode: Boolean, withEffect: Boolean): Camera {
                val groupBuilder = UseCaseGroup.Builder().addUseCase(preview)
                if (selectedMode.isVideoMode) groupBuilder.addUseCase(vidCap) else groupBuilder.addUseCase(imgCap)
                if (withBarcode) barcodeAnalysis?.let { groupBuilder.addUseCase(it) }
                if (withEffect && effect != null) groupBuilder.addEffect(effect)
                return provider.bindToLifecycle(lifecycleOwner, sel, groupBuilder.build())
            }

            var cam: Camera? = null
            if (useExtension) {
                val extSelector = runCatching {
                    extensionsManager!!.getExtensionEnabledCameraSelector(selector, extensionMode)
                }.getOrNull()
                if (extSelector != null) {
                    cam = runCatching { bindGroup(extSelector, analysisOk && scanEnabled, false) }.getOrNull()
                }
                if (cam == null) {
                    runCatching { provider.unbindAll() }
                    useExtension = false
                }
            }
            if (cam == null) {
                cam = try {
                    bindGroup(selector, scanEnabled, true)
                } catch (e: Exception) {
                    runCatching { effect?.close() }
                    media3Effect = null
                    provider.unbindAll()
                    bindGroup(selector, scanEnabled, false)
                }
            }
            extensionInUse = useExtension
            val activeCam = cam ?: return@LaunchedEffect

            camera = activeCam
            imageCapture = imgCap
            videoCapture = vidCap
            maxZoom = activeCam.cameraInfo.zoomState.value?.maxZoomRatio ?: 8f
            if (zoom > 1f) runCatching { activeCam.cameraControl.setZoomRatio(zoom) }

            runCatching {
                val es = activeCam.cameraInfo.exposureState
                if (es.isExposureCompensationSupported) {
                    val r = es.exposureCompensationRange
                    evRange = r.lower..r.upper
                    evStep = es.exposureCompensationStep.toFloat()
                    evIndex = es.exposureCompensationIndex.coerceIn(r.lower, r.upper)
                } else {
                    evRange = null
                }
            }
            cameraError = false
        } catch (e: SecurityException) {
            cameraError = true
        } catch (e: Exception) {
            cameraError = true
            toast("Camera error: ${e.message}")
        }
    }

    fun applyFlash() {
        runCatching { camera?.cameraControl?.enableTorch(flash == FlashMode.TORCH) }
        imageCapture?.flashMode = flashToCX()
    }

    fun setZoomValue(z: Float) {
        zoom = z.coerceIn(1f, maxZoom)
        runCatching { camera?.cameraControl?.setZoomRatio(zoom) }
    }

    // Tap-to-focus: map the tap into the PreviewView's metering coordinate space
    // and start an AF/AE/AWB action. The ring fades after a short delay.
    fun focusAt(offset: Offset) {
        val pv = previewView ?: return
        val w = pv.width.toFloat()
        val h = pv.height.toFloat()
        if (w <= 0f || h <= 0f) return
        focusRing = offset
        focusTick++
        runCatching {
            val point = pv.meteringPointFactory.createPoint(offset.x, offset.y)
            camera?.cameraControl?.startFocusAndMetering(
                FocusMeteringAction.Builder(point)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
            )
        }
    }

    fun applyEv(index: Int) {
        evIndex = index
        runCatching { camera?.cameraControl?.setExposureCompensationIndex(index) }
    }

    LaunchedEffect(focusTick) {
        if (focusRing != null) {
            delay(900)
            focusRing = null
        }
    }

    fun capturePhoto() {
        val ic = imageCapture ?: run { toast("Camera not ready"); return }
        val file = CameraStore.newPhotoFile(context)
        try {
            ic.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        scope.launch(Dispatchers.IO) {
                            val scene = processCapturedPhoto(
                                file = file,
                                aspect = selectedAspectRatio,
                                beauty = if (selectedMode == TecnoMode.AI_CAM || selectedMode == TecnoMode.PORTRAIT) beautyStrength else 0f,
                                portraitBlur = selectedMode == TecnoMode.PORTRAIT && !extensionInUse,
                                aiSceneDetect = selectedMode == TecnoMode.AI_CAM,
                                filter = cameraFilter,
                                maxLongEdge = photoResolution.maxLongEdge
                            )
                            if (selectedMode == TecnoMode.AI_CAM && scene != null) {
                                withContext(Dispatchers.Main) { detectedScene = scene }
                            }
                            CameraStore.scan(context, file)
                            withContext(Dispatchers.Main) {
                                capturedFiles = CameraStore.list(context)
                                toast("Photo saved (${selectedAspectRatio.label})")
                                onPhotoCaptured(file)
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        toast("Capture failed: ${exception.message}")
                    }
                }
            )
        } catch (e: Exception) {
            toast("Capture error: ${e.message}")
        }
    }

    fun startRecording() {
        val vc = videoCapture ?: run { toast("Camera not ready"); return }
        val file = CameraStore.newVideoFile(context)
        try {
            vc.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
                .withAudioEnabled()
                .start(mainExecutor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            isRecording = true
                            isPaused = false
                            recordMs = 0L
                        }
                        is VideoRecordEvent.Pause -> {
                            isPaused = true
                        }
                        is VideoRecordEvent.Resume -> {
                            isPaused = false
                        }
                        is VideoRecordEvent.Finalize -> {
                            isRecording = false
                            isPaused = false
                            activeRecording = null
                            CameraStore.scan(context, file)
                            capturedFiles = CameraStore.list(context)
                            toast(if (event.hasError()) "Video error" else "Video saved")
                        }
                        else -> {}
                    }
                }.also { activeRecording = it }
        } catch (e: Exception) {
            toast("Video error: ${e.message}")
        }
    }

    fun togglePauseRecording() {
        val rec = activeRecording ?: return
        runCatching { if (isPaused) rec.resume() else rec.pause() }
            .onFailure { toast("Pause not supported") }
    }

    fun stopRecording() {
        runCatching { activeRecording?.stop() }
    }

    fun onShutter() {
        if (isRecording) {
            stopRecording()
            return
        }
        if (timerSeconds > 0) {
            scope.launch {
                for (s in timerSeconds downTo 1) {
                    countdown = s
                    delay(1000)
                }
                countdown = 0
                if (selectedMode.isVideoMode) startRecording() else capturePhoto()
            }
            return
        }
        if (selectedMode.isVideoMode) startRecording() else capturePhoto()
    }

    // Recording clock: advances only while recording and not paused, so pausing
    // does not count toward the elapsed time or the short-video auto-stop.
    LaunchedEffect(isRecording, isPaused, selectedMode, shortVideoDuration) {
        if (isRecording) {
            while (isRecording && !isPaused) {
                delay(100)
                recordMs += 100
                if (selectedMode == TecnoMode.SHORT_VIDEO && recordMs >= shortVideoDuration * 1000L) {
                    stopRecording()
                    break
                }
            }
        }
    }

    if (!hasCamera || (selectedMode.isVideoMode && !hasAudio)) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(AppIcons.PhotoCamera, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Camera & Microphone Permissions Required", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Please allow permissions to access Tecno Camera capabilities.", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        )
                    }
                }) {
                    Text("Open App Settings")
                }
                TextButton(onClick = onBack) { Text("Back", color = Color.White) }
            }
        }
        return
    }

    if (cameraError) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    AppIcons.VideocamOff,
                    contentDescription = null,
                    tint = Color(0xFFFFD600),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Camera Unavailable", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Your device policy or hardware is restricting camera access. You can still use the rest of the dialer.",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = { retryAttempt++ }) {
                    Text("Retry Camera")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onBack) { Text("Back", color = Color.White) }
            }
        }
        return
    }

    if (showProDialog) {
        AlertDialog(
            onDismissRequest = { showProDialog = false },
            title = { Text("Pro Mode Settings", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ISO: $iso", modifier = Modifier.width(80.dp))
                        Slider(
                            value = iso.toFloat(),
                            onValueChange = { iso = it.toInt() },
                            valueRange = 100f..3200f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Shutter: ${exposureTime / 1000000}ms", modifier = Modifier.width(100.dp))
                        Slider(
                            value = exposureTime.toFloat(),
                            onValueChange = { exposureTime = it.toLong() },
                            valueRange = 1000000f..100000000L.toFloat(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("White Balance", modifier = Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { wbPickerOpen = true }) {
                                Text(wbLabel(wbMode))
                            }
                            DropdownMenu(
                                expanded = wbPickerOpen,
                                onDismissRequest = { wbPickerOpen = false }
                            ) {
                                listOf(
                                    CaptureRequest.CONTROL_AWB_MODE_AUTO to "Auto",
                                    CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to "Daylight",
                                    CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Cloudy",
                                    CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to "Fluorescent",
                                    CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to "Incandescent"
                                ).forEach { (m, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            wbMode = m
                                            wbPickerOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProDialog = false }) { Text("Done") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val aspectMod = selectedAspectRatio.ratio?.let { r ->
                Modifier.fillMaxWidth().aspectRatio(r)
            } ?: Modifier.fillMaxSize()

            Box(
                modifier = aspectMod
                    .clip(RoundedCornerShape(0.dp))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, gestureZoom, _ ->
                            setZoomValue(zoom * gestureZoom)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { setZoomValue(1f) },
                            onTap = { focusAt(it) }
                        )
                    }
            ) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also {
                            it.scaleType = PreviewView.ScaleType.FILL_CENTER
                            previewView = it
                        }
                    },
                    update = { previewView = it },
                    modifier = Modifier.fillMaxSize()
                )

                // Focus ring at the last tap point.
                focusRing?.let { point ->
                    val ringAlpha by animateFloatAsState(
                        targetValue = if (focusRing != null) 1f else 0f,
                        animationSpec = tween(300),
                        label = "focusRing"
                    )
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (point.x - 30.dp.toPx()).toInt(),
                                    (point.y - 30.dp.toPx()).toInt()
                                )
                            }
                            .size(60.dp)
                            .border(2.dp, Color(0xFFFFD600).copy(alpha = ringAlpha), CircleShape)
                    )
                }

                if (scanEnabled) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = AppCornerRadius.sm,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 8.dp)
                    ) {
                        Text(
                            if (scanResult == null) "Point at a QR code or barcode"
                            else "Code detected",
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                scanResult?.let { value ->
                    Surface(
                        color = Color(0xFF0B1220).copy(alpha = 0.92f),
                        shape = AppCornerRadius.md,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 90.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    AppIcons.QrCodeScanner,
                                    contentDescription = null,
                                    tint = Color(0xFFFFD600),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    value,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { scanResult = null }) {
                                    Icon(AppIcons.Close, "Dismiss", tint = Color.White)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TextButton(onClick = {
                                    val cm = context.getSystemService(
                                        android.content.Context.CLIPBOARD_SERVICE
                                    ) as android.content.ClipboardManager
                                    cm.setPrimaryClip(
                                        android.content.ClipData.newPlainText("code", value)
                                    )
                                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(AppIcons.ContentCopy, null, Modifier.size(16.dp), tint = Color.White)
                                    Text(" Copy", color = Color.White)
                                }
                                val openable = value.startsWith("http://") ||
                                    value.startsWith("https://") || value.startsWith("tel:") ||
                                    value.startsWith("mailto:") || value.startsWith("geo:")
                                if (openable) {
                                    TextButton(onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(value))
                                            )
                                        }.onFailure {
                                            Toast.makeText(context, "No app to open this", Toast.LENGTH_SHORT).show()
                                        }
                                    }) {
                                        Icon(AppIcons.OpenInNew, null, Modifier.size(16.dp), tint = Color(0xFFFFD600))
                                        Text(" Open", color = Color(0xFFFFD600))
                                    }
                                }
                            }
                        }
                    }
                }

                if (showGrid) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val thirdW = size.width / 3f
                        val thirdH = size.height / 3f
                        val line = Color.White.copy(alpha = 0.35f)
                        for (i in 1..2) {
                            drawLine(line, start = Offset(thirdW * i, 0f), end = Offset(thirdW * i, size.height), strokeWidth = 1f)
                            drawLine(line, start = Offset(0f, thirdH * i), end = Offset(size.width, thirdH * i), strokeWidth = 1f)
                        }
                    }
                }
            }
        }

        // Top Header Controls (self-inset: the screen is edge-to-edge now).
        // Back is fixed on the left, the toggles scroll horizontally in the
        // middle, and the Settings gear stays pinned on the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = {
                        selectedAspectRatio = when (selectedAspectRatio) {
                            AspectRatioMode.RATIO_4_3 -> AspectRatioMode.RATIO_16_9
                            AspectRatioMode.RATIO_16_9 -> AspectRatioMode.RATIO_1_1
                            AspectRatioMode.RATIO_1_1 -> AspectRatioMode.FULL
                            AspectRatioMode.FULL -> AspectRatioMode.RATIO_4_3
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                    color = Color.Black.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFD600))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(AppIcons.Crop, contentDescription = "Crop Ratio", tint = Color(0xFFFFD600), modifier = Modifier.size(16.dp))
                        Text(
                            text = selectedAspectRatio.label,
                            color = Color(0xFFFFD600),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }

                IconButton(onClick = {
                    flash = when (flash) {
                        FlashMode.OFF -> FlashMode.ON
                        FlashMode.ON -> FlashMode.AUTO
                        FlashMode.AUTO -> FlashMode.TORCH
                        FlashMode.TORCH -> FlashMode.OFF
                    }
                    applyFlash()
                }) {
                    Icon(
                        imageVector = when (flash) {
                            FlashMode.ON -> AppIcons.FlashOn
                            FlashMode.AUTO -> AppIcons.FlashAuto
                            FlashMode.TORCH -> AppIcons.FlashlightOn
                            FlashMode.OFF -> AppIcons.FlashOff
                        },
                        contentDescription = "Flash",
                        tint = if (flash != FlashMode.OFF) Color(0xFFFFD600) else Color.White
                    )
                }

                TextButton(onClick = { hdrEnabled = !hdrEnabled }) {
                    Text(
                        text = "HDR",
                        color = if (hdrEnabled) Color(0xFFFFD600) else Color.White.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                IconButton(onClick = {
                    timerSeconds = when (timerSeconds) {
                        0 -> 3
                        3 -> 10
                        else -> 0
                    }
                }) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(AppIcons.Timer, contentDescription = "Timer", tint = if (timerSeconds > 0) Color(0xFFFFD600) else Color.White)
                        if (timerSeconds > 0) {
                            Text("$timerSeconds", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                IconButton(onClick = { showFilterTray = !showFilterTray }) {
                    Icon(AppIcons.FilterVintage, contentDescription = "Filter", tint = if (cameraFilter != CameraFilter.NORMAL) Color(0xFFFFD600) else Color.White)
                }

                IconButton(onClick = { showGrid = !showGrid }) {
                    Icon(AppIcons.GridOn, contentDescription = "Grid", tint = if (showGrid) Color(0xFFFFD600) else Color.White)
                }

                IconButton(onClick = {
                    scanEnabled = !scanEnabled
                    if (!scanEnabled) scanResult = null
                }) {
                    Icon(
                        AppIcons.QrCodeScanner,
                        contentDescription = "Scan QR / Barcode",
                        tint = if (scanEnabled) Color(0xFFFFD600) else Color.White
                    )
                }

                IconButton(onClick = { showEv = !showEv }) {
                    Icon(
                        AppIcons.Brightness6,
                        contentDescription = "Exposure",
                        tint = if (showEv) Color(0xFFFFD600) else Color.White
                    )
                }

                if (selectedMode == TecnoMode.AI_CAM || selectedMode == TecnoMode.PORTRAIT) {
                    IconButton(onClick = { showBeauty = !showBeauty }) {
                        Icon(
                            AppIcons.AutoFixHigh,
                            contentDescription = "Beauty",
                            tint = if (showBeauty) Color(0xFFFFD600) else Color.White
                        )
                    }
                }
            }

            IconButton(onClick = { showSettings = true }) {
                Icon(AppIcons.Settings, contentDescription = "Camera settings", tint = Color.White)
            }
        }

        // AI scene detection badge (AI CAM mode).
        val sceneBadgeShown = selectedMode == TecnoMode.AI_CAM && detectedScene != null && !isRecording
        if (sceneBadgeShown) {
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp)
            ) {
                Text(
                    text = "AI: ${detectedScene!!.label}",
                    color = Color(0xFFFFD600),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Small marker so it is visible on-device when the OEM vendor extension is driving
        // this session (Auto for AI CAM, Bokeh for Portrait).
        if (extensionInUse) {
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 64.dp)
            ) {
                Text(
                    text = "OEM",
                    color = Color(0xFFFFD600),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Beauty Slider Overlay (Beauty is embedded in AI CAM, and also available in PORTRAIT).
        // Toggled from the top-bar wand button so it is not always on screen.
        if ((selectedMode == TecnoMode.AI_CAM || selectedMode == TecnoMode.PORTRAIT) && showBeauty) {
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = if (sceneBadgeShown) 104.dp else 64.dp)
                    .width(260.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AI Beauty", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = beautyStrength,
                        onValueChange = {
                            beautyStrength = it
                            CameraPrefs.setBeautyStrength(context, it)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text("${(beautyStrength * 100).toInt()}%", color = Color(0xFFFFD600), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Exposure compensation (vertical slider on the right edge).
        val ev = evRange
        if (showEv && ev != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 10.dp)
                ) {
                    Text("+", color = Color.White, fontSize = 14.sp)
                    Text(
                        text = String.format("%+.1f", evIndex * evStep),
                        color = Color(0xFFFFD600),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier.height(170.dp).width(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Slider(
                            value = evIndex.toFloat(),
                            onValueChange = { applyEv(it.roundToInt().coerceIn(ev.first, ev.last)) },
                            valueRange = ev.first.toFloat()..ev.last.toFloat(),
                            steps = (ev.last - ev.first - 1).coerceAtLeast(0),
                            modifier = Modifier.width(160.dp).rotate(-90f)
                        )
                    }
                    Text("−", color = Color.White, fontSize = 14.sp)
                }
            }
        }

        // Recording Indicator
        if (isRecording) {
            val total = if (selectedMode == TecnoMode.SHORT_VIDEO) shortVideoDuration * 1000L else 0L
            Surface(
                color = Color.Red,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                    val text = when {
                        isPaused -> "PAUSED"
                        total > 0 -> {
                            val remaining = ((total - recordMs) / 1000).coerceAtLeast(0)
                            "REC $remaining"
                        }
                        else -> "REC ${recordMs / 1000 / 60}:${String.format("%02d", (recordMs / 1000) % 60)}"
                    }
                    Text(
                        text = text,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Bottom Controls Container (self-inset: the screen is edge-to-edge now)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Filter tray (above the mode selector & shutter so it never overlaps the capture button)
            if (showFilterTray) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                ) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        itemsIndexed(CameraFilter.values()) { _, f ->
                            val selected = f == cameraFilter
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    cameraFilter = f
                                    showFilterTray = false
                                }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(if (selected) 54.dp else 48.dp)
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(if (selected) Color(0xFFFFD600) else Color.DarkGray.copy(alpha = 0.6f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (f == CameraFilter.NORMAL) {
                                        Icon(AppIcons.Remove, contentDescription = null, tint = Color.White)
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(if (selected) 44.dp else 38.dp)
                                                .clip(AppCornerRadius.sm)
                                                .background(filterSwatchColor(f))
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = f.label,
                                    color = if (selected) Color(0xFFFFD600) else Color.White.copy(alpha = 0.8f),
                                    fontSize = 10.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Short video duration selector (SHORT VIDEO mode)
            if (selectedMode == TecnoMode.SHORT_VIDEO) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 10.dp)
                ) {
                    listOf(15, 30).forEach { sec ->
                        val sel = shortVideoDuration == sec
                        Surface(
                            onClick = { if (!isRecording) shortVideoDuration = sec },
                            shape = MaterialTheme.shapes.large,
                            color = if (sel) Color(0xFFFFD600) else Color.Black.copy(alpha = 0.4f),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                                Text(
                                    text = "${sec}s",
                                    color = if (sel) Color.Black else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Video quality selector (VIDEO / SHORT VIDEO / DUAL modes)
            if (selectedMode.isVideoMode && videoQualities.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 10.dp)
                ) {
                    videoQualities.forEach { q ->
                        val sel = videoQuality == q
                        Surface(
                            onClick = { if (!isRecording) videoQuality = q },
                            shape = MaterialTheme.shapes.large,
                            color = if (sel) Color(0xFFFFD600) else Color.Black.copy(alpha = 0.4f),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                                Text(
                                    text = qualityLabel(camera?.cameraInfo ?: cameraProvider?.availableCameraInfos?.firstOrNull() ?: return@Box, q),
                                    color = if (sel) Color.Black else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Zoom Selector Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                listOf(1f, 2f).forEach { z ->
                    val isSelected = zoom == z
                    Surface(
                        onClick = { setZoomValue(z) },
                        shape = CircleShape,
                        color = if (isSelected) Color(0xFFFFD600) else Color.Black.copy(alpha = 0.4f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "${z.toInt()}X",
                                color = if (isSelected) Color.Black else Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                if (selectedMode == TecnoMode.PRO) {
                    Surface(
                        onClick = { showProDialog = true },
                        shape = CircleShape,
                        color = Color(0xFFFFD600),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(AppIcons.Tune, contentDescription = "PRO", tint = Color.Black, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Mode Selector
            val listState = rememberLazyListState()
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(horizontal = 140.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 20.dp)
            ) {
                itemsIndexed(TecnoMode.values()) { _, mode ->
                    val isSelected = mode == selectedMode
                    val textColor by animateColorAsState(if (isSelected) Color(0xFFFFD600) else Color.White.copy(alpha = 0.6f))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            if (!isRecording) {
                                selectedMode = mode
                            }
                        }
                    ) {
                        Text(
                            text = mode.label,
                            color = textColor,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFFD600))
                            )
                        }
                    }
                }
            }

            // Shutter Area
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = { showGallery = true },
                    shape = CircleShape,
                    color = Color.DarkGray.copy(alpha = 0.6f),
                    modifier = Modifier.size(50.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val latest = capturedFiles.firstOrNull()
                        if (latest != null && latest.extension.lowercase() == "jpg") {
                            AsyncImage(
                                model = latest,
                                contentDescription = "Gallery",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else {
                            Icon(AppIcons.PhotoLibrary, contentDescription = "Gallery", tint = Color.White)
                        }
                    }
                }

                Surface(
                    onClick = { onShutter() },
                    shape = CircleShape,
                    color = Color.Transparent,
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = Color.White,
                                radius = size.minDimension / 2f - 2.dp.toPx(),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx())
                            )
                        }
                        val innerColor = when {
                            isRecording -> Color.Red
                            selectedMode.isVideoMode -> Color.Red
                            selectedMode == TecnoMode.AI_CAM -> Color.White
                            else -> Color(0xFFFFD600)
                        }
                        Box(
                            modifier = Modifier
                                .size(if (isRecording) 32.dp else 64.dp)
                                .clip(if (isRecording) MaterialTheme.shapes.small else CircleShape)
                                .background(innerColor)
                        )
                    }
                }

                Surface(
                    onClick = {
                        if (isRecording) {
                            togglePauseRecording()
                        } else {
                            lensFacing = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                                CameraCharacteristics.LENS_FACING_FRONT
                            } else {
                                CameraCharacteristics.LENS_FACING_BACK
                            }
                        }
                    },
                    shape = CircleShape,
                    color = Color.DarkGray.copy(alpha = 0.6f),
                    modifier = Modifier.size(50.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isRecording) {
                            Icon(
                                if (isPaused) AppIcons.PlayArrow else AppIcons.Pause,
                                contentDescription = if (isPaused) "Resume" else "Pause",
                                tint = Color.White
                            )
                        } else {
                            Icon(AppIcons.Cameraswitch, contentDescription = "Switch Camera", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    if (countdown > 0) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$countdown",
                color = Color(0xFFFFD600),
                fontSize = 110.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    // A 24dp badge right on the photo is one mis-tap from an unrecoverable loss,
    // so deleting asks first and goes through the Recycle Bin like every other
    // file delete in the app (the old code called File.delete() directly, so a
    // wrong tap was gone forever).
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete photo?") },
            text = { Text("${target.name} will be moved to the Recycle bin.") },
            confirmButton = {
                Button(onClick = {
                    pendingDelete = null
                    scope.launch {
                        val trashed = withContext(Dispatchers.IO) {
                            RecycleBin.trash(context, target) != null
                        }
                        if (trashed) {
                            capturedFiles = CameraStore.list(context)
                            Toast.makeText(context, "Moved to Recycle bin", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Delete failed — file left in place", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Camera settings") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Keep screen on", modifier = Modifier.weight(1f))
                        Switch(
                            checked = keepScreenOn,
                            onCheckedChange = {
                                keepScreenOn = it
                                CameraPrefs.setKeepScreenOn(context, it)
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Photo resolution", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    PhotoResolution.values().forEach { res ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    photoResolution = res
                                    CameraPrefs.setResolution(context, res)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = photoResolution == res,
                                onClick = {
                                    photoResolution = res
                                    CameraPrefs.setResolution(context, res)
                                }
                            )
                            Text(res.label)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showSettings = false }) { Text("Done") }
            }
        )
    }

    if (showGallery) {
        Surface(color = Color(0xFF121212), modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showGallery = false }) {
                        Icon(AppIcons.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text("Gallery", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (capturedFiles.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No photos or videos captured yet", color = Color.White.copy(alpha = 0.5f))
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(capturedFiles, key = { it.absolutePath }) { f ->
                            Box(
                                modifier = Modifier.aspectRatio(1f).clip(MaterialTheme.shapes.extraSmall)
                                    .clickable {
                                        if (f.extension.lowercase() == "jpg") {
                                            val images = capturedFiles.filter { it.extension.lowercase() == "jpg" }
                                            onOpenImageWithPlaylist(f, images)
                                        } else {
                                            onOpenMedia(f, "video/mp4")
                                        }
                                    }
                            ) {
                                if (f.extension.lowercase() == "jpg") {
                                    AsyncImage(
                                        model = f,
                                        contentDescription = f.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Surface(
                                        onClick = { launchTranssionCropIntent(context, f) },
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.6f),
                                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(26.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(AppIcons.Crop, contentDescription = "Crop", tint = Color(0xFFFFD600), modifier = Modifier.size(14.dp))
                                        }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(AppIcons.PlayArrow, contentDescription = "Video", tint = Color.White, modifier = Modifier.size(36.dp))
                                    }
                                }
                                Surface(
                                    onClick = { pendingDelete = f },
                                    shape = CircleShape,
                                    color = Color.Black.copy(alpha = 0.6f),
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(AppIcons.Close, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun filterSwatchColor(f: CameraFilter): Color = when (f) {
    CameraFilter.NORMAL -> Color.DarkGray
    CameraFilter.VIVID -> Color(0xFF3FBF5F)
    CameraFilter.MONO -> Color(0xFF666666)
    CameraFilter.WARM -> Color(0xFFE0873A)
    CameraFilter.COOL -> Color(0xFF3A8AE0)
    CameraFilter.RETRO -> Color(0xFFB07C5A)
    CameraFilter.SEPIA -> Color(0xFF8A6A44)
}

// Decode a JPEG bounded to a maximum long edge (0 = no cap). Using inSampleSize
// keeps the working bitmap within a safe memory envelope on low-RAM devices.
private fun decodeBounded(file: File, maxLongEdge: Int): Bitmap? {
    return runCatching {
        if (maxLongEdge <= 0) return@runCatching BitmapFactory.decodeFile(file.absolutePath)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longEdge = kotlin.math.max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longEdge / sample > maxLongEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    }.getOrNull()
}

// Single-decode / single-encode post-capture pipeline. Previously each stage
// (crop, beauty, portrait blur, scene, filter, watermark) decoded and re-encoded
// the full-resolution JPEG, which could hold several full-res bitmaps at once and
// OOM. Now: one bounded decode, in-memory transforms, one encode — and when no
// transform is requested the camera's own JPEG is kept byte-for-byte (no generational
// loss, EXIF intact) instead of being needlessly re-encoded.
private fun processCapturedPhoto(
    file: File,
    aspect: AspectRatioMode,
    beauty: Float,
    portraitBlur: Boolean,
    aiSceneDetect: Boolean,
    filter: CameraFilter,
    maxLongEdge: Int
): SceneType? = runCatching {
    // CameraX writes the display orientation as an EXIF tag rather than rotating pixels.
    // If we re-encode we bake it into the bitmap; otherwise the file is left untouched.
    val orientation = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    var bmp: Bitmap = decodeBounded(file, maxLongEdge) ?: return@runCatching null

    // Scene detection runs on the decoded bitmap before any transforms.
    val scene = if (aiSceneDetect) detectScene(bmp) else null

    // Crop is only needed when the selected aspect actually differs from the capture. A
    // 90/270 EXIF rotation swaps the visual width/height, so compare against the
    // post-rotation dimensions (which is what the user actually sees).
    val swapped = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
        orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
        orientation == ExifInterface.ORIENTATION_TRANSVERSE
    val effectiveW = if (swapped) bmp.height else bmp.width
    val effectiveH = if (swapped) bmp.width else bmp.height
    val targetRatio = aspect.ratio
    val needsCrop = targetRatio != null &&
        kotlin.math.abs(effectiveW.toFloat() / effectiveH.toFloat() - targetRatio) >= 0.02f
    val needsFilter = filter != CameraFilter.NORMAL
    val hasEdits = needsCrop || beauty > 0f || portraitBlur || needsFilter

    // Nothing requested -> keep the camera's original JPEG untouched (EXIF intact).
    if (!hasEdits) {
        bmp.recycle()
        return@runCatching scene
    }

    // Bake EXIF orientation into the pixels: Bitmap.compress() writes no orientation tag.
    if (orientation != ExifInterface.ORIENTATION_NORMAL) {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated != bmp) {
            bmp.recycle()
            bmp = rotated
        }
    }

    // Crop to the selected aspect ratio (needsCrop guarantees targetRatio is non-null).
    if (needsCrop) {
        val r = targetRatio!!
        val cw = bmp.width
        val ch = bmp.height
        var nw = cw
        var nh = (cw / r).toInt()
        if (nh > ch) {
            nh = ch
            nw = (ch * r).toInt()
        }
        val x = ((cw - nw) / 2).coerceAtLeast(0)
        val y = ((ch - nh) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(bmp, x, y, nw, nh)
        if (cropped != bmp) {
            bmp.recycle()
            bmp = cropped
        }
    }

    // Beauty soft-focus: blur-overlay blend.
    if (beauty > 0f) {
        val s = beauty.coerceIn(0f, 1f)
        val w = bmp.width
        val h = bmp.height
        val small = Bitmap.createScaledBitmap(bmp, (w / 6).coerceAtLeast(1), (h / 6).coerceAtLeast(1), true)
        val up = Bitmap.createScaledBitmap(small, w, h, false)
        val paint = Paint().apply { alpha = (100 * s).toInt().coerceIn(0, 255) }
        Canvas(bmp).drawBitmap(up, 0f, 0f, paint)
        small.recycle()
        up.recycle()
    }

    // Portrait background blur (ML Kit selfie segmentation).
    if (portraitBlur) {
        val blurred = applyPortraitBlurInPlace(bmp, beauty.coerceIn(0.6f, 1f))
        if (blurred != null && blurred != bmp) {
            bmp.recycle()
            bmp = blurred
        }
    }

    // Colour filter in a single draw pass (NORMAL is identity and skipped).
    if (needsFilter) {
        Canvas(bmp).drawBitmap(bmp, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(filterColorMatrix(filter)) })
    }

    FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    bmp.recycle()
    scene
}.getOrNull()

private fun launchTranssionCropIntent(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent("com.android.camera.action.CROP").apply {
            setDataAndType(uri, "image/*")
            putExtra("crop", "true")
            putExtra("scale", true)
            putExtra("return-data", false)
            setPackage("com.transsion.camera")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val fallbackIntent = Intent("com.android.camera.action.CROP").apply {
                setDataAndType(uri, "image/*")
                putExtra("crop", "true")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(fallbackIntent)
        }.onFailure {
            Toast.makeText(context, "No external crop handler found", Toast.LENGTH_SHORT).show()
        }
    }
}

// Portrait background blur using on-device ML Kit selfie segmentation (fully offline).
// Operates on the already-decoded working bitmap and returns the blurred result
// (or null on failure), so no extra full-resolution source decode is needed.
private fun applyPortraitBlurInPlace(src: Bitmap, strength: Float = 0.8f): Bitmap? = runCatching {
    val w = src.width
    val h = src.height
    val maxDim = 640
    val scale = kotlin.math.min(1f, maxDim.toFloat() / kotlin.math.max(w, h))
    val bw = (w * scale).toInt().coerceAtLeast(1)
    val bh = (h * scale).toInt().coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(src, bw, bh, true)

    val options = SelfieSegmenterOptions.Builder()
        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
        .build()
    val segmenter = Segmentation.getClient(options)
    val task = segmenter.process(InputImage.fromBitmap(small, 0))
    val mask = Tasks.await(task, 15, TimeUnit.SECONDS)
    val buffer = mask.buffer
    buffer.rewind()
    val mw = mask.width
    val mh = mask.height
    val segW = if (mw > 0) mw else bw
    val segH = if (mh > 0) mh else bh

    val smallBlur = Bitmap.createScaledBitmap(src, (w / 8).coerceAtLeast(1), (h / 8).coerceAtLeast(1), true)
    val blurSmall = Bitmap.createScaledBitmap(smallBlur, bw, bh, true)

    val srcPix = IntArray(bw * bh)
    val blurPix = IntArray(bw * bh)
    val outPix = IntArray(bw * bh)
    small.getPixels(srcPix, 0, bw, 0, 0, bw, bh)
    blurSmall.getPixels(blurPix, 0, bw, 0, 0, bw, bh)

    val t = strength.coerceIn(0f, 1f)
    for (i in srcPix.indices) {
        val x = i % bw
        val y = i / bw
        val mx = (x * segW / bw).coerceIn(0, segW - 1)
        val my = (y * segH / bh).coerceIn(0, segH - 1)
        val conf = (buffer.get(my * segW + mx).toInt() and 0xFF) / 255f
        outPix[i] = blendPixel(srcPix[i], blurPix[i], conf * t)
    }

    val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    out.setPixels(outPix, 0, bw, 0, 0, bw, bh)
    val final = Bitmap.createScaledBitmap(out, w, h, true)

    runCatching { segmenter.close() }
    small.recycle(); smallBlur.recycle(); blurSmall.recycle(); out.recycle()
    final
}.getOrNull()

private fun blendPixel(src: Int, blur: Int, a: Float): Int {
    val aa = a.coerceIn(0f, 1f)
    val ba = 1f - aa
    val sr = (src shr 16) and 0xFF
    val sg = (src shr 8) and 0xFF
    val sb = src and 0xFF
    val br = (blur shr 16) and 0xFF
    val bg = (blur shr 8) and 0xFF
    val bb = blur and 0xFF
    val r = (sr * aa + br * ba).toInt()
    val g = (sg * aa + bg * ba).toInt()
    val b = (sb * aa + bb * ba).toInt()
    return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}

// Offline AI scene detection (histogram heuristics, no network required).
// Operates on a shared decoded bitmap; does not recycle the source.
private fun detectScene(src: Bitmap): SceneType {
    val scale = kotlin.math.min(1f, 256f / kotlin.math.max(src.width, src.height))
    val w = (src.width * scale).toInt().coerceAtLeast(1)
    val h = (src.height * scale).toInt().coerceAtLeast(1)
    val bmp = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, 0, 0, w, h)

    var lumSum = 0.0
    var satSum = 0.0
    var rSum = 0.0
    var gSum = 0.0
    var bSum = 0.0
    var skinCount = 0
    val n = pixels.size.coerceAtLeast(1)
    for (p in pixels) {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val maxc = kotlin.math.max(r, kotlin.math.max(g, b))
        val minc = kotlin.math.min(r, kotlin.math.min(g, b))
        val lum = (0.299f * r + 0.587f * g + 0.114f * b).toDouble()
        val sat = if (maxc == 0) 0.0 else (maxc - minc).toDouble() / maxc
        lumSum += lum
        satSum += sat
        rSum += r; gSum += g; bSum += b
        if (r > 95 && g > 40 && b > 20 && (r - g) > 15 && r > b && maxc - minc > 15) {
            skinCount++
        }
    }
    val avgLum = lumSum / n
    val avgSat = satSum / n
    val avgR = rSum / n
    val avgG = gSum / n
    val avgB = bSum / n
    val skinRatio = skinCount.toDouble() / n

    if (bmp != src) bmp.recycle()

    return when {
        avgLum < 60 -> SceneType.NIGHT
        avgSat < 0.12 && avgLum > 90 -> SceneType.DOCUMENT
        skinRatio > 0.12 && avgLum > 70 -> SceneType.PORTRAIT
        avgG > avgR * 1.05f && avgG > avgB * 1.08f && avgLum > 80 -> SceneType.SCENERY
        avgSat > 0.35 && avgR > avgB && avgLum in 70.0..190.0 -> SceneType.FOOD
        else -> SceneType.AUTO
    }
}

private fun filterColorMatrix(filter: CameraFilter): ColorMatrix {
    val cm = ColorMatrix()
    when (filter) {
        CameraFilter.NORMAL -> {}
        CameraFilter.VIVID -> cm.setSaturation(1.4f)
        CameraFilter.MONO -> cm.setSaturation(0f)
        CameraFilter.WARM -> cm.postConcat(ColorMatrix(floatArrayOf(
            1.12f, 0f, 0f, 0f, 12f,
            0f, 1.02f, 0f, 0f, 4f,
            0f, 0f, 0.9f, 0f, -6f,
            0f, 0f, 0f, 1f, 0f
        )))
        CameraFilter.COOL -> cm.postConcat(ColorMatrix(floatArrayOf(
            0.9f, 0f, 0f, 0f, -4f,
            0f, 1.0f, 0f, 0f, 0f,
            0f, 0f, 1.2f, 0f, 12f,
            0f, 0f, 0f, 1f, 0f
        )))
        CameraFilter.RETRO -> {
            cm.setSaturation(0.8f)
            cm.postConcat(ColorMatrix(floatArrayOf(
                0.9f, 0f, 0f, 0f, 8f,
                0f, 0.85f, 0f, 0f, 6f,
                0f, 0f, 0.78f, 0f, 4f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        CameraFilter.SEPIA -> {
            cm.setSaturation(0.3f)
            cm.postConcat(ColorMatrix(floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
    }
    return cm
}

private fun wbLabel(mode: Int): String = when (mode) {
    CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "Daylight"
    CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "Cloudy"
    CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "Fluorescent"
    CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "Incandescent"
    else -> "Auto"
}

private fun hasBackCamera(context: Context): Boolean {
    return runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        manager.cameraIdList.any { id ->
            runCatching { manager.getCameraCharacteristics(id) }
                .getOrNull()?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrDefault(true)
}
/** ML Kit barcode analyzer feeding scan results back to the camera UI. */
private class BarcodeAnalyzer(
    private val scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    private val onResult: (String) -> Unit
) : ImageAnalysis.Analyzer {

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = com.google.mlkit.vision.common.InputImage.fromMediaImage(
            mediaImage, imageProxy.imageInfo.rotationDegrees
        )
        scanner.process(input)
            .addOnSuccessListener { codes ->
                val first = codes.firstOrNull()
                val value = first?.rawValue ?: first?.displayValue
                if (!value.isNullOrBlank()) onResult(value)
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
