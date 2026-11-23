package com.example.ui.screens.imageeditor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class CropMode(val label: String, val ratio: Float?) {
    FREE("Free", null),
    SQUARE("1:1", 1f),
    R43("4:3", 4f / 3f),
    R34("3:4", 3f / 4f),
    R169("16:9", 16f / 9f),
    R916("9:16", 9f / 16f)
}

private enum class FilterMode(val label: String) {
    ORIGINAL("Original"),
    BW("B&W"),
    SEPIA("Sepia"),
    VIVID("Vivid"),
    COOL("Cool"),
    WARM("Warm"),
    FADE("Fade")
}

private enum class DragHandle { NONE, MOVE, TL, TR, BL, BR }

@Composable
fun ImageEditorScreen(
    viewModel: MainViewModel? = null,
    sourceFile: File? = null,
    sourceUri: Uri? = null,
    sourceName: String? = null,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val useGlass = viewModel?.useGlassmorphism?.collectAsState()?.value ?: false

    var currentFile by remember(sourceFile?.absolutePath) { mutableStateOf(sourceFile) }
    var currentUri by remember(sourceUri?.toString()) { mutableStateOf(sourceUri) }
    var currentName by remember(sourceName, sourceFile?.name, sourceUri?.toString()) {
        mutableStateOf(sourceName ?: sourceFile?.name ?: queryDisplayName(context, sourceUri) ?: "image")
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            currentFile = null
            currentUri = uri
            currentName = queryDisplayName(context, uri) ?: "image"
        } else if (currentFile == null && currentUri == null) {
            onClose()
        }
    }

    var working by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // tools state
    var brightness by remember { mutableFloatStateOf(0f) } // -100..100
    var contrast by remember { mutableFloatStateOf(1f) }   // 0.5..2
    var saturation by remember { mutableFloatStateOf(1f) } // 0..2
    var filter by remember { mutableStateOf(FilterMode.ORIGINAL) }
    var showAdjust by remember { mutableStateOf(true) }
    var cropEnabled by remember { mutableStateOf(false) }
    var cropMode by remember { mutableStateOf(CropMode.FREE) }
    // normalized crop rect 0..1 in IMAGE space
    var cropLeft by remember { mutableFloatStateOf(0f) }
    var cropTop by remember { mutableFloatStateOf(0f) }
    var cropRight by remember { mutableFloatStateOf(1f) }
    var cropBottom by remember { mutableFloatStateOf(1f) }

    fun resetCropToFull() {
        cropLeft = 0f; cropTop = 0f; cropRight = 1f; cropBottom = 1f
    }

    fun resetEdits() {
        brightness = 0f; contrast = 1f; saturation = 1f; filter = FilterMode.ORIGINAL
        cropEnabled = false; cropMode = CropMode.FREE
        resetCropToFull()
    }

    fun applyCropRatio(bmp: Bitmap?, mode: CropMode) {
        val b = bmp ?: run { resetCropToFull(); return }
        val w = b.width.toFloat(); val h = b.height.toFloat()
        val r = mode.ratio ?: run { resetCropToFull(); return }
        val imgR = w / h
        val (cw, ch) = if (imgR > r) {
            val ch = h; ch * r to ch
        } else {
            val cw = w; cw to cw / r
        }
        val inset = 0.0f
        val l = ((w - cw) / 2f) / w + inset
        val t = ((h - ch) / 2f) / h + inset
        cropLeft = l.coerceIn(0f, 1f)
        cropTop = t.coerceIn(0f, 1f)
        cropRight = (1f - ((w - cw) / 2f) / w).coerceIn(0f, 1f)
        cropBottom = (1f - ((h - ch) / 2f) / h).coerceIn(0f, 1f)
    }

    LaunchedEffect(currentFile?.absolutePath, currentUri?.toString()) {
        val f = currentFile; val u = currentUri
        if (f == null && u == null) {
            loadError = null; working = null; return@LaunchedEffect
        }
        loading = true; loadError = null
        resetEdits()
        val bmp = withContext(Dispatchers.IO) { decodeEditable(context, f, u) }
        working?.recycle()
        working = bmp
        loading = false
        if (bmp == null) loadError = "Couldn't open image"
    }

    DisposableEffect(Unit) { onDispose { working?.recycle() } }

    fun buildOutputBitmap(src: Bitmap): Bitmap {
        // 1) crop in image space
        var bmp = src
        if (cropEnabled) {
            val l = (cropLeft.coerceIn(0f, 1f) * bmp.width).roundToInt().coerceIn(0, bmp.width - 1)
            val t = (cropTop.coerceIn(0f, 1f) * bmp.height).roundToInt().coerceIn(0, bmp.height - 1)
            val r = (cropRight.coerceIn(0f, 1f) * bmp.width).roundToInt().coerceIn(l + 1, bmp.width)
            val b = (cropBottom.coerceIn(0f, 1f) * bmp.height).roundToInt().coerceIn(t + 1, bmp.height)
            val cw = (r - l).coerceAtLeast(1)
            val ch = (b - t).coerceAtLeast(1)
            if (cw < bmp.width || ch < bmp.height || l != 0 || t != 0) {
                bmp = Bitmap.createBitmap(bmp, l, t, cw, ch)
            }
        }
        // 2) color
        val cm = combinedColorMatrix(brightness, contrast, saturation, filter)
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }
        c.drawBitmap(bmp, 0f, 0f, p)
        if (bmp !== src) bmp.recycle()
        return out
    }

    fun saveCopy() {
        val src = working ?: return
        if (saving) return
        saving = true
        scope.launch {
            val nameBase = currentName.substringBeforeLast('.').ifBlank { "image" }
            val outName = "${nameBase}_edit_${System.currentTimeMillis()}.jpg"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val outBmp = buildOutputBitmap(src)
                    try { saveBitmapToPictures(context, outBmp, outName) } finally { outBmp.recycle() }
                }
            }
            saving = false
            result.onSuccess { path ->
                Toast.makeText(context, "Saved to $path", Toast.LENGTH_LONG).show()
                onClose()
            }.onFailure { e ->
                Toast.makeText(context, "Save failed: ${e.message ?: "unknown"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val hasSource = currentFile != null || currentUri != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        FloatingTopBar(useGlass = useGlass) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(AppIcons.Close, contentDescription = "Close") }
                Text(
                    "Image Editor",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (hasSource) {
                    TextButton(onClick = { resetEdits() }, enabled = !saving && !loading) { Text("Reset") }
                    Button(onClick = { saveCopy() }, enabled = !saving && !loading && working != null) {
                        if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Save")
                    }
                }
            }
        }
        if (!hasSource) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(AppIcons.Edit, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("Pick a photo to edit", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { picker.launch("image/*") }) {
                        Icon(AppIcons.Image, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text("Choose image")
                    }
                }
            }
            LaunchedEffect(Unit) { if (currentFile == null && currentUri == null) picker.launch("image/*") }
            return@Column
        }

        when {
            loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            loadError != null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(loadError ?: "Error", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { picker.launch("image/*") }) { Text("Pick another") }
                }
            }
            working != null -> {
                val bmp = working!!
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val previewMatrix = remember(brightness, contrast, saturation, filter) {
                        combinedColorMatrix(brightness, contrast, saturation, filter)
                    }
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Editing $currentName",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                            androidx.compose.ui.graphics.ColorMatrix(previewMatrix.array)
                        )
                    )
                    if (cropEnabled) {
                        CropOverlay(
                            imageWidth = bmp.width,
                            imageHeight = bmp.height,
                            left = cropLeft, top = cropTop, right = cropRight, bottom = cropBottom,
                            aspectRatio = cropMode.ratio,
                            onChange = { l, t, r, b ->
                                cropLeft = l; cropTop = t; cropRight = r; cropBottom = b
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // tools
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {
                        working = working?.let { rotateBitmap(it, 90f) }
                    }, label = { Text("Rotate") }, leadingIcon = { Icon(AppIcons.Refresh, null) })
                    AssistChip(onClick = { working = working?.let { flipBitmap(it, horizontal = true) } }, label = { Text("Flip H") }, leadingIcon = { Icon(AppIcons.Flip, null) })
                    AssistChip(onClick = { working = working?.let { flipBitmap(it, horizontal = false) } }, label = { Text("Flip V") }, leadingIcon = { Icon(AppIcons.Flip, null) })
                    FilterChip(selected = cropEnabled, onClick = {
                        val enabling = !cropEnabled
                        cropEnabled = enabling
                        if (enabling) applyCropRatio(working, cropMode)
                    }, label = { Text("Crop") }, leadingIcon = { Icon(AppIcons.Crop, null) })
                    FilterChip(selected = showAdjust, onClick = { showAdjust = !showAdjust }, label = { Text("Adjust") }, leadingIcon = { Icon(AppIcons.Tune, null) })
                    AssistChip(onClick = { picker.launch("image/*") }, label = { Text("Open") }, leadingIcon = { Icon(AppIcons.Image, null) })
                }

                if (cropEnabled) {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CropMode.entries.forEach { m ->
                            FilterChip(selected = cropMode == m, onClick = {
                                cropMode = m
                                applyCropRatio(working, m)
                            }, label = { Text(m.label) })
                        }
                    }
                    Text(
                        "Drag to move • Drag corners to resize",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (showAdjust) {
                    Spacer(Modifier.height(4.dp))
                    Text("Brightness ${brightness.toInt()}", style = MaterialTheme.typography.labelMedium)
                    Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -100f..100f)
                    Text("Contrast ${"%.2f".format(contrast)}", style = MaterialTheme.typography.labelMedium)
                    Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.5f..2f)
                    Text("Saturation ${"%.2f".format(saturation)}", style = MaterialTheme.typography.labelMedium)
                    Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..2f)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterMode.entries.forEach { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                        }
                    }
                }
            }
        }
    }
}

private data class ImageDrawRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

private fun computeImageRect(containerW: Float, containerH: Float, imageW: Int, imageH: Int): ImageDrawRect {
    if (containerW <= 0f || containerH <= 0f || imageW <= 0 || imageH <= 0) {
        return ImageDrawRect(0f, 0f, containerW, containerH)
    }
    val scale = min(containerW / imageW.toFloat(), containerH / imageH.toFloat())
    val dw = imageW * scale
    val dh = imageH * scale
    val ox = (containerW - dw) / 2f
    val oy = (containerH - dh) / 2f
    return ImageDrawRect(ox, oy, dw, dh)
}

@Composable
private fun CropOverlay(
    imageWidth: Int,
    imageHeight: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    aspectRatio: Float?,
    onChange: (Float, Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { 22.dp.toPx() }
    val minCropPx = with(density) { 48.dp.toPx() }

    var dragHandle by remember { mutableStateOf(DragHandle.NONE) }
    var startRect by remember { mutableStateOf(Rect.Zero) }
    var startTouch by remember { mutableStateOf(Offset.Zero) }

    val leftState by rememberUpdatedState(left)
    val topState by rememberUpdatedState(top)
    val rightState by rememberUpdatedState(right)
    val bottomState by rememberUpdatedState(bottom)
    val ratioState by rememberUpdatedState(aspectRatio)

    ComposeCanvas(
        modifier = modifier
            .onSizeChanged { boxSize = it }
            .pointerInput(imageWidth, imageHeight) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = boxSize.width.toFloat().coerceAtLeast(1f)
                        val h = boxSize.height.toFloat().coerceAtLeast(1f)
                        val img = computeImageRect(w, h, imageWidth, imageHeight)
                        val lpx = img.left + leftState * img.width
                        val tpx = img.top + topState * img.height
                        val rpx = img.left + rightState * img.width
                        val bpx = img.top + bottomState * img.height
                        val rect = Rect(lpx, tpx, rpx, bpx)
                        startRect = rect
                        startTouch = offset
                        dragHandle = when {
                            near(offset, Offset(rect.left, rect.top), handleRadiusPx) -> DragHandle.TL
                            near(offset, Offset(rect.right, rect.top), handleRadiusPx) -> DragHandle.TR
                            near(offset, Offset(rect.left, rect.bottom), handleRadiusPx) -> DragHandle.BL
                            near(offset, Offset(rect.right, rect.bottom), handleRadiusPx) -> DragHandle.BR
                            rect.contains(offset) -> DragHandle.MOVE
                            else -> DragHandle.NONE
                        }
                    },
                    onDrag = { change, _ ->
                        if (dragHandle == DragHandle.NONE) return@detectDragGestures
                        change.consume()
                        val pos = change.position
                        val w = boxSize.width.toFloat().coerceAtLeast(1f)
                        val h = boxSize.height.toFloat().coerceAtLeast(1f)
                        val img = computeImageRect(w, h, imageWidth, imageHeight)
                        val dx = pos.x - startTouch.x
                        val dy = pos.y - startTouch.y
                        val ratio = ratioState
                        val minW = minCropPx.coerceAtMost(img.width)
                        val minH = minCropPx.coerceAtMost(img.height)

                        var nl = startRect.left
                        var nt = startRect.top
                        var nr = startRect.right
                        var nb = startRect.bottom

                        when (dragHandle) {
                            DragHandle.MOVE -> {
                                nl = startRect.left + dx
                                nt = startRect.top + dy
                                nr = nl + startRect.width
                                nb = nt + startRect.height
                                if (nl < img.left) { val d = img.left - nl; nl += d; nr += d }
                                if (nt < img.top) { val d = img.top - nt; nt += d; nb += d }
                                if (nr > img.right) { val d = nr - img.right; nl -= d; nr -= d }
                                if (nb > img.bottom) { val d = nb - img.bottom; nt -= d; nb -= d }
                            }
                            DragHandle.TL, DragHandle.TR, DragHandle.BL, DragHandle.BR -> {
                                if (ratio == null) {
                                    when (dragHandle) {
                                        DragHandle.TL -> { nl = (startRect.left + dx).coerceIn(img.left, startRect.right - minW); nt = (startRect.top + dy).coerceIn(img.top, startRect.bottom - minH) }
                                        DragHandle.TR -> { nr = (startRect.right + dx).coerceIn(startRect.left + minW, img.right); nt = (startRect.top + dy).coerceIn(img.top, startRect.bottom - minH) }
                                        DragHandle.BL -> { nl = (startRect.left + dx).coerceIn(img.left, startRect.right - minW); nb = (startRect.bottom + dy).coerceIn(startRect.top + minH, img.bottom) }
                                        DragHandle.BR -> { nr = (startRect.right + dx).coerceIn(startRect.left + minW, img.right); nb = (startRect.bottom + dy).coerceIn(startRect.top + minH, img.bottom) }
                                        else -> {}
                                    }
                                } else {
                                    // keep aspect ratio anchored at opposite corner
                                    val (ax, ay, sx, sy) = when (dragHandle) {
                                        DragHandle.BR -> Quad(startRect.left, startRect.top, 1f, 1f)
                                        DragHandle.TL -> Quad(startRect.right, startRect.bottom, -1f, -1f)
                                        DragHandle.TR -> Quad(startRect.left, startRect.bottom, 1f, -1f)
                                        else -> Quad(startRect.right, startRect.top, -1f, 1f) // BL
                                    }
                                    var cw = abs((startRect.left + if (sx > 0) dx else 0f) - (startRect.right + if (sx < 0) dx else 0f))
                                    var ch = abs((startRect.top + if (sy > 0) dy else 0f) - (startRect.bottom + if (sy < 0) dy else 0f))
                                    // also allow using pointer directly for stability
                                    cw = abs(pos.x - ax).coerceAtLeast(minW)
                                    ch = abs(pos.y - ay).coerceAtLeast(minH)
                                    if (cw / ch > ratio) cw = ch * ratio else ch = cw / ratio
                                    // clamp to image bounds from anchor
                                    val maxW = if (sx > 0) img.right - ax else ax - img.left
                                    val maxH = if (sy > 0) img.bottom - ay else ay - img.top
                                    val boundW = min(maxW, maxH * ratio)
                                    val boundH = boundW / ratio
                                    cw = cw.coerceIn(minW, boundW.coerceAtLeast(minW))
                                    ch = cw / ratio
                                    if (ch < minH) { ch = minH.coerceAtMost(boundH); cw = ch * ratio }
                                    nl = if (sx > 0) ax else ax - cw
                                    nr = if (sx > 0) ax + cw else ax
                                    nt = if (sy > 0) ay else ay - ch
                                    nb = if (sy > 0) ay + ch else ay
                                }
                            }
                            else -> {}
                        }

                        // final clamp inside image rect
                        nl = nl.coerceIn(img.left, img.right - minW)
                        nr = nr.coerceIn(nl + minW, img.right)
                        nt = nt.coerceIn(img.top, img.bottom - minH)
                        nb = nb.coerceIn(nt + minH, img.bottom)

                        val outL = ((nl - img.left) / img.width).coerceIn(0f, 1f)
                        val outT = ((nt - img.top) / img.height).coerceIn(0f, 1f)
                        val outR = ((nr - img.left) / img.width).coerceIn(0f, 1f)
                        val outB = ((nb - img.top) / img.height).coerceIn(0f, 1f)
                        if (outR - outL > 0.01f && outB - outT > 0.01f) {
                            onChange(outL, outT, outR, outB)
                        }
                    },
                    onDragEnd = { dragHandle = DragHandle.NONE },
                    onDragCancel = { dragHandle = DragHandle.NONE }
                )
            }
    ) {
        val w = size.width; val h = size.height
        val img = computeImageRect(w, h, imageWidth, imageHeight)
        val l = img.left + left * img.width
        val t = img.top + top * img.height
        val r = img.left + right * img.width
        val b = img.top + bottom * img.height
        val shade = Color.Black.copy(alpha = 0.5f)
        // shade outside crop but inside/over whole canvas
        drawRect(shade, topLeft = Offset(0f, 0f), size = Size(w, t.coerceAtLeast(0f)))
        drawRect(shade, topLeft = Offset(0f, b), size = Size(w, (h - b).coerceAtLeast(0f)))
        drawRect(shade, topLeft = Offset(0f, t), size = Size(l.coerceAtLeast(0f), (b - t).coerceAtLeast(0f)))
        drawRect(shade, topLeft = Offset(r, t), size = Size((w - r).coerceAtLeast(0f), (b - t).coerceAtLeast(0f)))

        val strokeW = 2.dp.toPx()
        drawRect(Color.White, topLeft = Offset(l, t), size = Size((r - l).coerceAtLeast(0f), (b - t).coerceAtLeast(0f)), style = Stroke(strokeW))
        val thirdW = (r - l) / 3f; val thirdH = (b - t) / 3f
        val grid = Color.White.copy(alpha = 0.45f)
        for (i in 1..2) {
            drawLine(grid, Offset(l + thirdW * i, t), Offset(l + thirdW * i, b), strokeWidth = 1.dp.toPx())
            drawLine(grid, Offset(l, t + thirdH * i), Offset(r, t + thirdH * i), strokeWidth = 1.dp.toPx())
        }
        // corner handles
        val handleR = 6.dp.toPx()
        val corners = listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b))
        corners.forEach { c ->
            drawCircle(Color.White, radius = handleR, center = c)
        }
    }
}

private data class Quad(val ax: Float, val ay: Float, val sx: Float, val sy: Float)

private fun near(p: Offset, c: Offset, r: Float): Boolean {
    val dx = p.x - c.x; val dy = p.y - c.y
    return dx * dx + dy * dy <= r * r
}

private fun queryDisplayName(context: Context, uri: Uri?): String? {
    if (uri == null) return null
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}

private fun decodeEditable(context: Context, file: File?, uri: Uri?): Bitmap? {
    val decoded = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (file != null) BitmapFactory.decodeFile(file.absolutePath, bounds)
        else if (uri != null) context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val maxDim = 2048
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1); inPreferredConfig = Bitmap.Config.ARGB_8888 }
        if (file != null) BitmapFactory.decodeFile(file.absolutePath, opts)
        else if (uri != null) context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        else null
    } catch (_: Throwable) { null } ?: return null
    return applyExifEditable(context, file, uri, decoded)
}

private fun applyExifEditable(context: Context, file: File?, uri: Uri?, bitmap: Bitmap): Bitmap {
    return try {
        val exif = when {
            file != null -> ExifInterface(file.absolutePath)
            uri != null -> context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } ?: return bitmap
            else -> return bitmap
        }
        val o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true).also { if (it !== bitmap) bitmap.recycle() }
    } catch (_: Throwable) { bitmap }
}

private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
    val m = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { if (it !== src) src.recycle() }
}

private fun flipBitmap(src: Bitmap, horizontal: Boolean): Bitmap {
    val m = Matrix().apply { postScale(if (horizontal) -1f else 1f, if (horizontal) 1f else -1f) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { if (it !== src) src.recycle() }
}

private fun combinedColorMatrix(brightness: Float, contrast: Float, saturation: Float, filter: FilterMode): ColorMatrix {
    val cm = ColorMatrix()
    val sat = ColorMatrix().apply { setSaturation(saturation) }
    cm.postConcat(sat)
    val t = brightness * 2.55f
    val scale = contrast
    val translate = (-0.5f * scale + 0.5f) * 255f + t
    val cb = ColorMatrix(floatArrayOf(
        scale, 0f, 0f, 0f, translate,
        0f, scale, 0f, 0f, translate,
        0f, 0f, scale, 0f, translate,
        0f, 0f, 0f, 1f, 0f
    ))
    cm.postConcat(cb)
    val f = when (filter) {
        FilterMode.ORIGINAL -> null
        FilterMode.BW -> ColorMatrix().apply { setSaturation(0f) }
        FilterMode.SEPIA -> ColorMatrix(floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        FilterMode.VIVID -> ColorMatrix().apply { setSaturation(1.5f) }
        FilterMode.COOL -> ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1.1f, 0f, 10f,
            0f, 0f, 0f, 1f, 0f
        ))
        FilterMode.WARM -> ColorMatrix(floatArrayOf(
            1.1f, 0f, 0f, 0f, 10f,
            0f, 1.05f, 0f, 0f, 0f,
            0f, 0f, 0.95f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        FilterMode.FADE -> ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 20f,
            0f, 1f, 0f, 0f, 20f,
            0f, 0f, 1f, 0f, 20f,
            0f, 0f, 0f, 0.9f, 0f
        ))
    }
    if (f != null) cm.postConcat(f)
    return cm
}

private fun saveBitmapToPictures(context: Context, bitmap: Bitmap, displayName: String): String {
    val folder = "Pictures/NJ-Dialer/Edits"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("Could not create image")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw java.io.IOException("Compress failed")
            } ?: throw java.io.IOException("Could not open output")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return "$folder/$displayName"
    } else {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "NJ-Dialer/Edits")
        if (!dir.exists() && !dir.mkdirs()) throw java.io.IOException("Could not create folder")
        var target = File(dir, displayName)
        if (target.exists()) target = File(dir, displayName.substringBeforeLast('.') + "_" + System.currentTimeMillis() + ".jpg")
        FileOutputStream(target).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw java.io.IOException("Compress failed")
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("image/jpeg"), null)
        return target.absolutePath
    }
}
