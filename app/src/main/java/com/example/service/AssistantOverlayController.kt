package com.example.service

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.ui.screens.assistant.AssistantOverlayContent
import com.example.util.AssistantActions
import com.example.util.AssistantPrefs
import com.example.util.AssistantRecorderController

private const val EDGE_MIN_VISIBLE_DP = 24
private const val EDGE_SNAP_DISTANCE_DP = 28
private const val EDGE_ANIM_MS = 180L

object AssistantOverlayController {
    private val expandedState = mutableStateOf(false)
    private val layoutRevision = mutableStateOf(0)
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var owner: OverlayOwner? = null
    private var wm: WindowManager? = null
    private var positionAnimator: ValueAnimator? = null

    fun isShowing(): Boolean = view != null

    fun show(context: Context, expand: Boolean = false) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(app)) {
            AssistantActions.requestOverlayPermission(app)
            return
        }
        if (view != null) {
            if (expand) setExpanded(app, true)
            return
        }
        val manager = app.getSystemService(WindowManager::class.java) ?: return
        wm = manager
        expandedState.value = AssistantPrefs.expanded(app)
        val layout = buildLayout(app, expandedState.value)
        params = layout

        val lifecycleOwner = OverlayOwner().also { owner = it }
        lifecycleOwner.attach()
        val composeView = ComposeView(app).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setContent {
                val revision = layoutRevision.value
                key(revision) {
                    HidPadTheme {
                        AssistantOverlayContent(
                            expanded = expandedState.value,
                            onExpandedChange = { setExpanded(app, it) },
                            onClose = { AssistantOverlayService.stop(app) },
                            onOpenSettings = { AssistantActions.open(app, "assistantsettings") },
                            onDrag = { dx, dy -> moveBy(app, dx, dy) },
                            onDragEnd = { finishDrag(app) }
                        )
                    }
                }
            }
        }
        view = composeView
        runCatching { manager.addView(composeView, layout) }.onFailure {
            owner?.destroy()
            owner = null
            view = null
            params = null
            wm = null
        }
    }

    fun collapse(context: Context) = setExpanded(context, false)

    fun expand(context: Context) = setExpanded(context, true)

    fun refreshLayout(context: Context) {
        val app = context.applicationContext
        val composeView = view ?: return
        val current = params ?: return
        cancelPositionAnimation()
        val target = buildLayout(app, expandedState.value)
        current.width = target.width
        current.height = target.height
        current.flags = target.flags
        current.gravity = target.gravity
        current.softInputMode = target.softInputMode
        clampLayout(app, current, expandedState.value)
        layoutRevision.value++
        runCatching { wm?.updateViewLayout(composeView, current) }
    }

    fun hide(context: Context) {
        val composeView = view
        cancelPositionAnimation()
        expandedState.value = false
        AssistantPrefs.setExpanded(context.applicationContext, false)
        view = null
        params = null
        runCatching { wm?.removeView(composeView) }
        wm = null
        owner?.destroy()
        owner = null
    }

    private fun setExpanded(context: Context, value: Boolean) {
        val app = context.applicationContext
        cancelPositionAnimation()
        if (!value) AssistantRecorderController.stopIfRecording(app)
        expandedState.value = value
        AssistantPrefs.setExpanded(app, value)
        val current = params ?: return
        val target = buildLayout(app, value)
        current.width = target.width
        current.height = target.height
        current.flags = target.flags
        current.gravity = target.gravity
        current.softInputMode = target.softInputMode
        clampLayout(app, current, value)
        view?.let { runCatching { wm?.updateViewLayout(it, current) } }
    }

    private fun moveBy(context: Context, dx: Float, dy: Float) {
        val app = context.applicationContext
        val current = params ?: return
        cancelPositionAnimation()
        current.x += dx.toInt()
        current.y += dy.toInt()
        clampLayout(app, current, expandedState.value)
        val composeView = view ?: return
        runCatching { wm?.updateViewLayout(composeView, current) }
        AssistantPrefs.saveOverlayPosition(app, expandedState.value, current.x, current.y)
    }

    private fun finishDrag(context: Context) {
        val app = context.applicationContext
        val current = params ?: return
        val composeView = view ?: return
        val manager = wm ?: return
        cancelPositionAnimation()
        if (expandedState.value) {
            clampFull(app, current)
            runCatching { manager.updateViewLayout(composeView, current) }
            AssistantPrefs.saveOverlayPosition(app, true, current.x, current.y)
            return
        }
        clampBubble(app, current)
        val metrics = app.resources.displayMetrics
        val offscreen = maxBubbleOffscreen(app, current.width)
        val fullRight = (metrics.widthPixels - current.width).coerceAtLeast(0)
        val targetX = if (AssistantPrefs.snapToEdge(app)) {
            val threshold = dp(app, EDGE_SNAP_DISTANCE_DP)
            when {
                current.x <= -offscreen + threshold -> -offscreen
                current.x >= fullRight + offscreen - threshold -> fullRight + offscreen
                else -> current.x.coerceIn(0, fullRight)
            }
        } else {
            current.x.coerceIn(0, fullRight)
        }
        animateX(app, current, composeView, manager, targetX)
    }

    private fun animateX(
        context: Context,
        layout: WindowManager.LayoutParams,
        composeView: ComposeView,
        manager: WindowManager,
        targetX: Int
    ) {
        if (layout.x == targetX) {
            AssistantPrefs.saveOverlayPosition(context, false, layout.x, layout.y)
            return
        }
        val animator = ValueAnimator.ofInt(layout.x, targetX).apply {
            duration = EDGE_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                layout.x = animation.animatedValue as Int
                runCatching { manager.updateViewLayout(composeView, layout) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    positionAnimator = null
                    layout.x = targetX
                    runCatching { manager.updateViewLayout(composeView, layout) }
                    AssistantPrefs.saveOverlayPosition(context, false, layout.x, layout.y)
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    positionAnimator = null
                }
            })
        }
        positionAnimator = animator
        animator.start()
    }

    private fun cancelPositionAnimation() {
        positionAnimator?.cancel()
        positionAnimator = null
    }

    private fun clampLayout(context: Context, layout: WindowManager.LayoutParams, expanded: Boolean) {
        if (expanded) clampFull(context, layout) else clampBubble(context, layout)
    }

    private fun clampFull(context: Context, layout: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        layout.x = layout.x.coerceIn(0, (metrics.widthPixels - layout.width).coerceAtLeast(0))
        layout.y = layout.y.coerceIn(0, (metrics.heightPixels - layout.height).coerceAtLeast(0))
    }

    private fun clampBubble(context: Context, layout: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        val offscreen = maxBubbleOffscreen(context, layout.width)
        val minX = -offscreen
        val maxX = (metrics.widthPixels - layout.width + offscreen).coerceAtLeast(minX)
        layout.x = layout.x.coerceIn(minX, maxX)
        layout.y = layout.y.coerceIn(0, (metrics.heightPixels - layout.height).coerceAtLeast(0))
    }

    private fun maxBubbleOffscreen(context: Context, width: Int): Int {
        val minVisible = maxOf(dp(context, EDGE_MIN_VISIBLE_DP), (width * 0.5f).toInt())
        return (width - minVisible).coerceAtLeast(0)
    }

    private fun buildLayout(context: Context, expanded: Boolean): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val (panelWidth, panelHeight) = AssistantPrefs.panelDp(context)
        val bubbleWidth = dp(context, AssistantPrefs.bubbleDp(context))
        val width = if (expanded) {
            minOf(metrics.widthPixels - dp(context, 16), dp(context, panelWidth))
        } else {
            bubbleWidth
        }
        val height = if (expanded) {
            minOf(metrics.heightPixels - dp(context, 72), dp(context, panelHeight))
        } else {
            bubbleWidth
        }
        val flags = if (expanded) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        }
        val prefs = AssistantPrefs.get(context)
        val minSize = if (expanded) dp(context, 200) else dp(context, 40)
        return WindowManager.LayoutParams(
            width.coerceAtLeast(minSize),
            height.coerceAtLeast(minSize),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = AssistantPrefs.overlayX(context, expanded)
            if (expanded && !prefs.contains(AssistantPrefs.PANEL_OVERLAY_X)) {
                val bubbleX = prefs.getInt(AssistantPrefs.OVERLAY_X, dp(context, 12))
                x = when {
                    bubbleX <= 0 -> 0
                    bubbleX >= metrics.widthPixels - bubbleWidth ->
                        (metrics.widthPixels - width).coerceAtLeast(0)
                    else -> bubbleX
                }
            }
            y = AssistantPrefs.overlayY(context, expanded)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            clampLayout(context, this, expanded)
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
        override val savedStateRegistry: SavedStateRegistry
            get() = controller.savedStateRegistry

        fun attach() {
            controller.performAttach()
            runCatching { controller.performRestore(Bundle()) }
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            if (registry.currentState == Lifecycle.State.DESTROYED) return
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
