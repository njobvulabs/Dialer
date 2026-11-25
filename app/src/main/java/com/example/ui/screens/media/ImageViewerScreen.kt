package com.example.ui.screens.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.exifinterface.media.ExifInterface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import com.example.ui.components.AppIcons
import com.example.util.RecycleBin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Applies the EXIF rotation/orientation stored in the image so that photos
 * taken in portrait display upright (BitmapFactory ignores EXIF by default).
 */
private fun applyExifOrientation(context: Context, uri: Uri?, path: String?, bitmap: Bitmap?): Bitmap? {
    if (bitmap == null) return null
    return try {
        val exif = when {
            uri != null -> {
                val input = context.contentResolver.openInputStream(uri)
                    ?: return bitmap
                ExifInterface(input)
            }
            path != null -> ExifInterface(path)
            else -> return bitmap
        }
        val rotation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
        val matrix = Matrix()
        when (rotation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        ).also { if (it !== bitmap) bitmap.recycle() }
    } catch (_: Exception) {
        bitmap
    }
}

/**
 * Decodes a bitmap in a memory-safe manner using target bounding dimensions to avoid OOM crashes.
 */
private fun decodeSampledBitmapFromFile(context: Context, file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
    val decoded = try {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)

        var inSampleSize = 1
        if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
            val halfHeight: Int = options.outHeight / 2
            val halfWidth: Int = options.outWidth / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            this.inSampleSize = inSampleSize
        }
        BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
    } catch (_: Exception) {
        null
    }
    return applyExifOrientation(context, null, file.absolutePath, decoded)
}

private fun decodeSampledBitmapFromUri(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
    fun open(): java.io.InputStream? = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()

    val decoded = try {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, boundsOptions) }

        var inSampleSize = 1
        if (boundsOptions.outHeight > reqHeight || boundsOptions.outWidth > reqWidth) {
            val halfHeight: Int = boundsOptions.outHeight / 2
            val halfWidth: Int = boundsOptions.outWidth / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            this.inSampleSize = inSampleSize
        }
        open()?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
    } catch (_: Exception) {
        null
    }
    return applyExifOrientation(context, uri, null, decoded)
}

private fun queryContentMeta(context: Context, uri: Uri): Pair<String?, Long?> {
    var name: String? = null
    var size: Long? = null
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nIdx >= 0) name = c.getString(nIdx)
                if (sIdx >= 0 && !c.isNull(sIdx)) size = c.getLong(sIdx)
            }
        }
    }
    return name to size
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    imageFile: File,
    imageUri: Uri? = null,
    onBack: () -> Unit,
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    positionLabel: String? = null,
    onEdit: (() -> Unit)? = null,
    // Gallery swipe mode
    galleryItems: List<Pair<String, Uri>>? = null,
    initialIndex: Int = 0,
    onIndexChanged: ((Int) -> Unit)? = null,
    /**
     * File swipe mode (e.g. the Camera's in-app gallery): swipe between plain
     * [File]s without leaving the viewer. Each page is displayed from disk and the
     * per-file actions (share / details / delete-to-Recycle-Bin) follow the current
     * page. Mutually exclusive with [galleryItems].
     */
    pagerFiles: List<File>? = null,
    pagerInitialIndex: Int = 0,
    /**
     * Delete the currently shown image when it came from a content Uri.
     *
     * The gallery's MediaStore items arrive with a placeholder [imageFile]
     * (just a name, no path), so the built-in [File] delete below can never
     * work for them — the host screen owns that delete, confirmation and
     * consent prompt included. When null the trash icon is hidden.
     */
    onDeleteImage: ((Uri) -> Unit)? = null,
    slideshowIntervalMs: Long? = null
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }

    val isGalleryMode = galleryItems != null
    val isFilePagerMode = pagerFiles != null
    val pageCount = galleryItems?.size ?: pagerFiles?.size ?: 1
    val safeInitial = when {
        isGalleryMode -> initialIndex.coerceIn(0, galleryItems!!.lastIndex)
        isFilePagerMode -> pagerInitialIndex.coerceIn(0, pagerFiles!!.lastIndex)
        else -> 0
    }
    val pagerState = rememberPagerState(
        initialPage = safeInitial,
        pageCount = { pageCount }
    )
    val currentPage = when {
        isGalleryMode -> pagerState.currentPage.coerceIn(0, galleryItems!!.lastIndex)
        isFilePagerMode -> pagerState.currentPage.coerceIn(0, pagerFiles!!.lastIndex)
        else -> 0
    }

    // The file backing the currently shown page (single-file mode -> the passed file).
    val currentFile: File = if (isFilePagerMode) pagerFiles!![currentPage] else imageFile

    // Resolve the current item's uri / name for the overlay bars
    val effUri: Uri? = if (isGalleryMode) galleryItems!![currentPage].second else imageUri
    val scope = rememberCoroutineScope()
    val isGallerySource = effUri != null

    // Dialog & UI Visibility States
    var showOverlayControls by remember { mutableStateOf(true) }
    var showDetailsDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }

    val displayName = remember(currentPage, isGalleryMode, isFilePagerMode) {
        if (isGalleryMode) galleryItems!![currentPage].first
        else if (isFilePagerMode) pagerFiles!![currentPage].name
        else if (imageUri != null) queryContentMeta(context, imageUri).first ?: imageFile.name
        else imageFile.name
    }

    val effPositionLabel: String? = when {
        isGalleryMode -> "${currentPage + 1} of ${galleryItems!!.size}"
        isFilePagerMode -> "${currentPage + 1} of ${pagerFiles!!.size}"
        else -> positionLabel
    }

    // Transformation States (shared; reset when the pager page changes)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotationAngle by remember { mutableFloatStateOf(0f) }
    var isFlippedHorizontally by remember { mutableStateOf(false) }
    var isFlippedVertically by remember { mutableStateOf(false) }

    LaunchedEffect(currentPage) {
        scale = 1f
        offset = Offset.Zero
        rotationAngle = 0f
        isFlippedHorizontally = false
        isFlippedVertically = false
        onIndexChanged?.invoke(currentPage)
    }

    LaunchedEffect(currentPage, slideshowIntervalMs, pageCount) {
        val interval = slideshowIntervalMs ?: return@LaunchedEffect
        if (pageCount <= 1) return@LaunchedEffect
        delay(interval)
        if (pagerState.isScrollInProgress) return@LaunchedEffect
        val next = if (currentPage + 1 < pageCount) currentPage + 1 else 0
        pagerState.animateScrollToPage(next)
    }

    BackHandler {
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Tap gestures: Single tap toggles overlays; Double tap quick zooms
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        showOverlayControls = !showOverlayControls
                    },
                    onDoubleTap = { tapOffset ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            offset = Offset(
                                x = (size.width / 2f - tapOffset.x) * 1.5f,
                                y = (size.height / 2f - tapOffset.y) * 1.5f
                            )
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (galleryItems != null) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = scale <= 1f,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                ViewImagePage(
                    context = context,
                    file = null,
                    uri = galleryItems[page].second,
                    name = galleryItems[page].first,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    scale = scale,
                    offset = offset,
                    rotationAngle = rotationAngle,
                    isFlippedHorizontally = isFlippedHorizontally,
                    isFlippedVertically = isFlippedVertically,
                    onScale = { scale = it },
                    onOffset = { offset = it },
                    loading = true
                )
            }
        } else if (isFilePagerMode) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = scale <= 1f,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                ViewImagePage(
                    context = context,
                    file = pagerFiles!![page],
                    uri = null,
                    name = pagerFiles!![page].name,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    scale = scale,
                    offset = offset,
                    rotationAngle = rotationAngle,
                    isFlippedHorizontally = isFlippedHorizontally,
                    isFlippedVertically = isFlippedVertically,
                    onScale = { scale = it },
                    onOffset = { offset = it },
                    loading = false
                )
            }
        } else {
            ViewImagePage(
                context = context,
                file = imageFile,
                uri = imageUri,
                name = displayName,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                scale = scale,
                offset = offset,
                rotationAngle = rotationAngle,
                isFlippedHorizontally = isFlippedHorizontally,
                isFlippedVertically = isFlippedVertically,
                onScale = { scale = it },
                onOffset = { offset = it },
                loading = isGallerySource
            )
        }

        // Top Header Overlay Bar
        AnimatedVisibility(
            visible = showOverlayControls,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            // Floating bar: rounded card with 12.dp side margins (the app's standard
            // geometry), not a full-bleed strip. NO top padding: the card sits flush
            // against the very top of the screen and only the side margins remain —
            // the status-bar inset lives inside the Row (statusBarsPadding), so the
            // scrim reaches the top edge instead of leaving a gap above it.
            // Kept the translucent dark scrim rather than surfaceContainer: it floats
            // over arbitrary photos, where a light card cannot guarantee contrast.
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = AppIcons.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                maxLines = 1
                            )
                            if (effPositionLabel != null) {
                                Text(
                                    text = effPositionLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    Row {
                        if (onEdit != null) {
                            IconButton(onClick = onEdit) {
                                Icon(AppIcons.Edit, contentDescription = "Edit", tint = Color.White)
                            }
                        }
                        IconButton(onClick = { showDetailsDialog = true }) {
                            Icon(AppIcons.Info, contentDescription = "Details", tint = Color.White)
                        }
                        if (!isGallerySource || onDeleteImage != null) {
                            IconButton(
                                onClick = {
                                    val cb = onDeleteImage
                                    if (isGallerySource && cb != null && effUri != null) {
                                        // Host owns confirmation + MediaStore consent.
                                        cb(effUri)
                                    } else {
                                        showDeleteConfirmationDialog = true
                                    }
                                }
                            ) {
                                Icon(AppIcons.Delete, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.9f))
                            }
                        }
                    }
                }
            }
        }

        // Bottom Action Control Overlay Bar
        AnimatedVisibility(
            visible = showOverlayControls,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            // Floating counterpart of the top bar — see the note there for the
            // scrim-vs-surfaceContainer choice and for why there is no bottom
            // padding: the card sits flush with the very bottom edge and the
            // navigation-bar inset is applied inside the Row.
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous
                    if (onPrev != null) {
                        IconButton(onClick = onPrev) {
                            Icon(AppIcons.ChevronLeft, contentDescription = "Previous", tint = Color.White)
                        }
                    }

                    // Zoom Out
                    IconButton(
                        onClick = {
                            scale = (scale - 0.5f).coerceAtLeast(1f)
                            if (scale == 1f) offset = Offset.Zero
                        },
                        enabled = scale > 1f
                    ) {
                        Icon(
                            imageVector = AppIcons.ZoomOut,
                            contentDescription = "Zoom Out",
                            tint = if (scale > 1f) Color.White else Color.Gray
                        )
                    }

                    // Zoom In
                    IconButton(onClick = { scale = (scale + 0.5f).coerceAtMost(5f) }) {
                        Icon(AppIcons.ZoomIn, contentDescription = "Zoom In", tint = Color.White)
                    }

                    // Rotate Left
                    IconButton(onClick = { rotationAngle = (rotationAngle - 90f) % 360f }) {
                        Icon(AppIcons.RotateLeft, contentDescription = "Rotate Left", tint = Color.White)
                    }

                    // Rotate Right
                    IconButton(onClick = { rotationAngle = (rotationAngle + 90f) % 360f }) {
                        Icon(AppIcons.RotateRight, contentDescription = "Rotate Right", tint = Color.White)
                    }

                    // Flip Horizontal
                    IconButton(onClick = { isFlippedHorizontally = !isFlippedHorizontally }) {
                        Icon(
                            imageVector = AppIcons.Flip,
                            contentDescription = "Flip Horizontal",
                            tint = if (isFlippedHorizontally) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }

                    // Flip Vertical
                    IconButton(onClick = { isFlippedVertically = !isFlippedVertically }) {
                        Icon(
                            imageVector = AppIcons.SwapVert,
                            contentDescription = "Flip Vertical",
                            tint = if (isFlippedVertically) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }

                    // Reset All Transformations
                    IconButton(onClick = {
                        scale = 1f
                        offset = Offset.Zero
                        rotationAngle = 0f
                        isFlippedHorizontally = false
                        isFlippedVertically = false
                    }) {
                        Icon(AppIcons.FitScreen, contentDescription = "Reset Zoom & Orientation", tint = Color.White)
                    }

                    // Next
                    if (onNext != null) {
                        IconButton(onClick = onNext) {
                            Icon(AppIcons.ChevronRight, contentDescription = "Next", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    // Image Details Dialog
    if (showDetailsDialog) {
        val meta = if (isGallerySource) queryContentMeta(context, imageUri!!) else (currentFile.name to currentFile.length())
        val fileSizeMb = meta.second?.let {
            String.format(Locale.getDefault(), "%.2f MB", it.toDouble() / (1024 * 1024))
        }
        val lastModified = if (isGallerySource) null else
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(currentFile.lastModified()))

        AlertDialog(
            onDismissRequest = { showDetailsDialog = false },
            icon = { Icon(AppIcons.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Image Information") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Name: ${meta.first ?: currentFile.name}", fontWeight = FontWeight.SemiBold)
                    Text(if (fileSizeMb != null) "File Size: $fileSizeMb" else "File Size: Unknown")
                    if (!isGallerySource) {
                        lastModified?.let { Text("Last Modified: $it") }
                    }
                    Text(
                        text = if (isGallerySource) "Source: Device gallery media" else "Path: ${currentFile.absolutePath}",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetailsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmationDialog = false },
            icon = { Icon(AppIcons.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete Image") },
            text = { Text("Move \"${currentFile.name}\" to the Recycle bin? You can restore it later from the File Manager.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmationDialog = false
                        // Real file: route through the Recycle Bin so a mis-tap is
                        // recoverable, exactly like the File Manager and the camera
                        // grid. A raw File.delete() here was unrecoverable.
                        val toDelete = currentFile
                        scope.launch {
                            val trashed = withContext(Dispatchers.IO) {
                                runCatching { RecycleBin.trash(context, toDelete) != null }.getOrDefault(false)
                            }
                            if (trashed) {
                                Toast.makeText(context, "Moved to Recycle bin", Toast.LENGTH_SHORT).show()
                                onBack()
                            } else {
                                Toast.makeText(
                                    context,
                                    "Delete failed — file left in place",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmationDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/** Renders a single viewable image page with live pinch-to-zoom and pan. */
@Composable
private fun ViewImagePage(
    context: Context,
    file: File?,
    uri: Uri?,
    name: String,
    screenWidthPx: Int,
    screenHeightPx: Int,
    scale: Float,
    offset: Offset,
    rotationAngle: Float,
    isFlippedHorizontally: Boolean,
    isFlippedVertically: Boolean,
    onScale: (Float) -> Unit,
    onOffset: (Offset) -> Unit,
    loading: Boolean
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, file, uri) {
        value = withContext(Dispatchers.IO) {
            if (uri != null) {
                decodeSampledBitmapFromUri(context, uri, max(1080, screenWidthPx), max(1920, screenHeightPx))
            } else if (file != null) {
                decodeSampledBitmapFromFile(context, file, max(1080, screenWidthPx), max(1920, screenHeightPx))
            } else {
                null
            }
        }
    }
    val currentBitmap = bitmap
    val latestScale by rememberUpdatedState(scale)
    val latestOffset by rememberUpdatedState(offset)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    var zoom = 1f
                    var pan = Offset.Zero
                    var prevCentroid: Offset? = null
                    var prevDist = 0f
                    var consuming = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        when {
                            pressed.size >= 2 -> {
                                val c0 = pressed[0].position
                                val c1 = pressed[1].position
                                val centroid = (c0 + c1) / 2f
                                val dist = (c0 - c1).getDistance()
                                if (prevDist > 0f) zoom *= (dist / prevDist)
                                if (prevCentroid != null) pan += (centroid - prevCentroid)
                                prevDist = dist
                                prevCentroid = centroid
                                consuming = true
                                event.changes.forEach { it.consume() }
                            }
                            pressed.size == 1 -> {
                                if (consuming || latestScale > 1f) {
                                    val change = event.changes.first()
                                    pan += change.positionChange()
                                    change.consume()
                                }
                            }
                        }
                    } while (pressed.any { it.pressed })

                    val currentScale = latestScale
                    val newScale = (currentScale * zoom).coerceIn(1f, 6f)
                    onScale(newScale)
                    if (newScale > 1f) {
                        val maxX = (size.width * (newScale - 1f)) / 2f
                        val maxY = (size.height * (newScale - 1f)) / 2f
                        val updated = latestOffset + pan
                        onOffset(
                            Offset(
                                x = updated.x.coerceIn(-maxX, maxX),
                                y = updated.y.coerceIn(-maxY, maxY)
                            )
                        )
                    } else {
                        onOffset(Offset.Zero)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale * (if (isFlippedHorizontally) -1f else 1f),
                        scaleY = scale * (if (isFlippedVertically) -1f else 1f),
                        translationX = offset.x,
                        translationY = offset.y,
                        rotationZ = rotationAngle
                    )
            )
        } else if (loading) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Loading image…", color = Color.White)
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = AppIcons.BrokenImage,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Unable to render image preview", color = Color.White)
            }
        }
    }
}
