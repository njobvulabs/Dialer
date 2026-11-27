package com.example.ui.screens.home

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.service.LauncherNotification
import com.example.ui.MainViewModel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun LauncherShadeOverlay(
    viewModel: MainViewModel,
    enabled: Boolean,
    immersive: Boolean = false,
    openRequest: Int = 0,
    initialPage: Int = 0,
    modifier: Modifier = Modifier,
    onShadeOpenChange: (Boolean) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var shadePage by remember { mutableStateOf(initialPage.coerceIn(0, 1)) }
    val context = LocalContext.current
    val hostView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val notifications by viewModel.launcherNotifications.collectAsState()
    val reduceMotion by viewModel.reduceMotion.collectAsState()
    var shadeOpen by remember { mutableStateOf(false) }
    var notificationReplyOpen by remember { mutableStateOf(false) }
    var backdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    var disposed by remember { mutableStateOf(false) }
    val offsetY = remember { Animatable(-2000f) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var screenHeightPx by remember { mutableStateOf(1f) }
    var hasAccess by remember {
        mutableStateOf(
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        )
    }

    DisposableEffect(Unit) {
        onDispose { disposed = true }
    }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                viewModel.refreshLauncherNotifications()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun dismiss(fromVisualOffset: Float? = null) {
        scope.launch {
            if (fromVisualOffset != null) {
                offsetY.snapTo(fromVisualOffset)
                dragOffsetY = 0f
            }
            offsetY.animateTo(-screenHeightPx, tween(if (reduceMotion) 0 else 180))
            shadeOpen = false
            notificationReplyOpen = false
            onShadeOpenChange(false)
        }
    }

    fun open() {
        scope.launch {
            shadeOpen = true
            onShadeOpenChange(true)
            viewModel.refreshLauncherNotifications()
            dragOffsetY = 0f
            offsetY.snapTo(-screenHeightPx)
            offsetY.animateTo(0f, tween(if (reduceMotion) 0 else 220))
        }
    }

    fun openNotification(notif: LauncherNotification) {
        val activityOptions = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activityOptions.setPendingIntentBackgroundActivityLaunchAllowed(true)
        }
        val options = activityOptions.toBundle()
        val sent = runCatching {
            notif.contentIntent?.let { pendingIntent ->
                pendingIntent.send(context, 0, null, null, Handler(Looper.getMainLooper()), null, options)
                true
            } ?: false
        }.getOrDefault(false)
        if (!sent) {
            runCatching {
                context.packageManager.getLaunchIntentForPackage(notif.packageName)?.let { intent ->
                    context.startActivity(intent, options)
                }
            }
        }
        dismiss()
    }

    fun captureBackdrop() {
        val activity = context.findActivity() ?: return
        val width = hostView.width
        val height = hostView.height
        if (width <= 0 || height <= 0) return
        val full = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
        runCatching {
            PixelCopy.request(
                activity.window,
                full,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        val maxEdge = 900f
                        val scale = min(1f, maxEdge / maxOf(width, height).toFloat())
                        val scaled = if (scale < 1f) {
                            Bitmap.createScaledBitmap(
                                full,
                                (width * scale).roundToInt().coerceAtLeast(1),
                                (height * scale).roundToInt().coerceAtLeast(1),
                                true
                            )
                        } else full
                        if (scaled !== full) full.recycle()
                        if (disposed) {
                            if (!scaled.isRecycled) scaled.recycle()
                        } else {
                            backdrop = scaled.asImageBitmap()
                        }
                    } else {
                        full.recycle()
                    }
                },
                Handler(Looper.getMainLooper())
            )
        }.onFailure { full.recycle() }
    }

    LaunchedEffect(enabled) {
        if (!enabled && shadeOpen) dismiss()
    }

    LaunchedEffect(openRequest) {
        if (openRequest > 0) {
            shadePage = initialPage.coerceIn(0, 1)
            captureBackdrop()
            open()
        }
    }

    if (shadeOpen && !notificationReplyOpen) BackHandler { dismiss() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val liveStatusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val liveBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val statusBarResId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val fallbackStatusTop = if (statusBarResId > 0) {
            with(density) { context.resources.getDimensionPixelSize(statusBarResId).toDp() }
        } else 0.dp
        val navBarResId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val fallbackBottom = if (navBarResId > 0) {
            with(density) { context.resources.getDimensionPixelSize(navBarResId).toDp() }
        } else 0.dp
        val statusTop = if (immersive && liveStatusTop <= 0.dp) fallbackStatusTop else liveStatusTop
        val bottomPad = if (immersive && liveBottom <= 0.dp) fallbackBottom else liveBottom
        val fullHeightPx = with(density) { maxHeight.toPx() }
        screenHeightPx = fullHeightPx

        LaunchedEffect(fullHeightPx) {
            if (!shadeOpen) {
                dragOffsetY = 0f
                offsetY.snapTo(-fullHeightPx)
            }
        }

        if (enabled && !shadeOpen) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = statusTop + 5.dp)
                    .width(112.dp)
                    .height(6.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0f),
                        RoundedCornerShape(3.dp)
                    )
            )

        }

        if (shadeOpen) {
            val dismissThresholdPx = with(density) { 72.dp.toPx().coerceAtMost(fullHeightPx * 0.22f) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, (offsetY.value + dragOffsetY).roundToInt()) }
            ) {
                if (backdrop != null) {
                    Image(
                        bitmap = backdrop!!,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(18.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.52f))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = 6.dp,
                            end = 6.dp,
                            top = statusTop + 8.dp,
                            bottom = bottomPad + 8.dp
                        )
                ) {
                    when (shadePage) {
                        0 -> LauncherNotificationsPanel(
                            notifications = notifications,
                            hasAccess = hasAccess,
                            onOpenNotification = { notif: LauncherNotification ->
                                openNotification(notif)
                            },
                            onDismissNotification = { key -> viewModel.dismissLauncherNotification(key) },
                            onClearAllNotifications = { viewModel.clearAllLauncherNotifications() },
                            onPerformAction = { key, index, input ->
                                viewModel.actionLauncherNotification(key, index, input)
                            },
                            onReplyModeChange = { notificationReplyOpen = it },
                            fillRemaining = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                        1 -> LauncherQuickSettingsPanel(
                            viewModel = viewModel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .pointerInput(fullHeightPx, dismissThresholdPx) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var lastTime = down.uptimeMillis
                                    var velocityY = 0f
                                    var totalY = 0f
                                    var totalX = 0f
                                    var claimed = false
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Main)
                                        val change = event.changes.firstOrNull() ?: break
                                        if (!change.pressed) {
                                            val shouldDismiss = claimed &&
                                                    (totalY <= -dismissThresholdPx || velocityY < -700f)
                                            if (shouldDismiss) {
                                                dismiss(offsetY.value + dragOffsetY)
                                            } else {
                                                dragOffsetY = 0f
                                                scope.launch {
                                                    if (shadeOpen) {
                                                        offsetY.animateTo(
                                                            0f,
                                                            tween(if (reduceMotion) 0 else 160)
                                                        )
                                                    }
                                                }
                                            }
                                            break
                                        }
                                        val delta = change.positionChange()
                                        val elapsed = (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                                        velocityY = delta.y * 1000f / elapsed
                                        lastTime = change.uptimeMillis
                                        totalY += delta.y
                                        totalX += delta.x
                                        if (!claimed && totalY < -8f && abs(totalY) > abs(totalX) * 1.15f) {
                                            claimed = true
                                        }
                                        if (claimed) {
                                            change.consume()
                                            dragOffsetY = totalY.coerceAtMost(0f).coerceAtLeast(-fullHeightPx)
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(42.dp)
                                .height(5.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(3.dp)
                                )
                        )
                    }
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
