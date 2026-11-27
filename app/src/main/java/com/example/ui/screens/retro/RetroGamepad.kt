package com.example.ui.screens.retro

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot

internal const val RETRO_B = 1L shl 0
internal const val RETRO_Y = 1L shl 1
internal const val RETRO_SELECT = 1L shl 2
internal const val RETRO_START = 1L shl 3
internal const val RETRO_UP = 1L shl 4
internal const val RETRO_DOWN = 1L shl 5
internal const val RETRO_LEFT = 1L shl 6
internal const val RETRO_RIGHT = 1L shl 7
internal const val RETRO_A = 1L shl 8
internal const val RETRO_X = 1L shl 9
internal const val RETRO_L = 1L shl 10
internal const val RETRO_R = 1L shl 11

private data class GamepadKey(
    val bit: Long,
    val x: Dp,
    val y: Dp,
    val width: Dp,
    val height: Dp,
    val touchRadius: Dp,
    val label: String,
    val isPill: Boolean = false
)

private data class HitTarget(
    val bit: Long,
    val cx: Float,
    val cy: Float,
    val radius: Float
)

private data class DpOffset(val x: Dp, val y: Dp)

@Composable
fun RetroGamepad(
    alpha: Float,
    scale: Float,
    verticalOffset: Dp,
    showAnalog: Boolean = true,
    modifier: Modifier = Modifier,
    onButtons: (Long) -> Unit
) {
    val density = LocalDensity.current
    val currentOnButtons = rememberUpdatedState(onButtons)
    val effectiveAlpha = alpha.coerceIn(0.35f, 1f)

    BoxWithConstraints(
        modifier = modifier.navigationBarsPadding(),
        contentAlignment = Alignment.TopStart
    ) {
        val isLandscape = maxWidth > maxHeight

        val baseSize = if (isLandscape) {
            minOf(maxWidth.value * 0.066f, maxHeight.value * 0.135f).coerceIn(44f, 60f)
        } else {
            (maxWidth.value * 0.155f).coerceIn(48f, 64f)
        }
        val visual = baseSize.dp * scale

        val bottomPad = 12.dp
        val sysW = visual * 1.12f
        val sysH = visual * 0.56f
        val sysTouchRadius = sysW * 0.68f
        val sysY = (maxHeight - sysH * 0.5f - bottomPad - verticalOffset)
            .coerceIn(sysH * 2.2f, maxHeight - sysH * 0.5f - 4.dp)

        // Left side: Analog stick + Cross D-Pad
        val dpadArmLength = visual * 0.80f
        val dpadArmThickness = visual * 0.76f
        val dpadTotalSpan = dpadArmLength * 2f + dpadArmThickness
        val dpadTouchRadius = dpadTotalSpan * 0.64f

        val dpadCenterX = (dpadTotalSpan * 0.5f + if (isLandscape) 30.dp else 14.dp)
            .coerceAtMost(maxWidth * 0.34f)
        val dpadCenterY = if (isLandscape) {
            (sysY - dpadTotalSpan * 0.5f - 10.dp)
                .coerceIn(dpadTotalSpan * 0.5f + 78.dp, maxHeight - dpadTotalSpan * 0.5f - 8.dp)
        } else {
            (sysY - dpadTotalSpan * 0.5f - 12.dp)
                .coerceIn(dpadTotalSpan * 0.5f + visual * 2.2f, maxHeight - dpadTotalSpan * 0.5f - 8.dp)
        }
        val dpadCenter = DpOffset(dpadCenterX, dpadCenterY)

        val stickOuterSize = visual * 1.32f
        val stickKnobSize = visual * 0.55f
        val stickMaxTravel = (stickOuterSize - stickKnobSize) * 0.45f
        val stickCenter = if (isLandscape) {
            DpOffset(
                x = (dpadCenterX + dpadTotalSpan * 0.5f + stickOuterSize * 0.65f).coerceAtMost(maxWidth * 0.44f),
                y = dpadCenterY
            )
        } else {
            DpOffset(
                x = dpadCenterX,
                y = (dpadCenterY - dpadTotalSpan * 0.5f - stickOuterSize * 0.62f)
                    .coerceAtLeast(stickOuterSize * 0.5f + visual + 6.dp)
            )
        }

        // Right side: PSP diamond face buttons (Triangle=X, Square=Y, Circle=A, Cross=B)
        val faceBtnSize = visual * 0.94f
        val faceTouchRadius = faceBtnSize * 0.72f
        val diamondOffset = faceBtnSize * 0.94f
        val rightEdgeX = (maxWidth - faceBtnSize * 0.5f - if (isLandscape) 30.dp else 14.dp)
            .coerceAtLeast(maxWidth * 0.68f)
        val diamondCenterX = rightEdgeX - diamondOffset
        val diamondCenterY = if (isLandscape) {
            dpadCenterY
        } else {
            (sysY - sysH * 0.5f - diamondOffset - faceBtnSize * 0.5f - 16.dp)
                .coerceAtLeast(diamondOffset + faceBtnSize)
        }

        // Shoulder buttons (L / R) positioned below top HUD & menu bar so there is zero overlap
        val shoulderW = visual * 1.38f
        val shoulderH = visual * 0.64f
        val shoulderTouchRadius = shoulderW * 0.68f
        val shoulderY = if (isLandscape) {
            60.dp.coerceAtMost((dpadCenterY - dpadTotalSpan * 0.5f - shoulderH * 0.5f - 8.dp).coerceAtLeast(52.dp))
        } else {
            (stickCenter.y - stickOuterSize * 0.5f - shoulderH * 0.5f - 10.dp)
                .coerceAtLeast(shoulderH * 0.5f + 4.dp)
        }
        val shoulderLeftX = (shoulderW * 0.5f + if (isLandscape) 30.dp else 16.dp).coerceAtMost(maxWidth * 0.35f)
        val shoulderRightX = (maxWidth - shoulderW * 0.5f - if (isLandscape) 30.dp else 16.dp).coerceAtLeast(maxWidth * 0.65f)

        // SEL and STA
        val selX = if (isLandscape) maxWidth * 0.5f - sysW * 0.65f else diamondCenterX - sysW * 0.62f
        val staX = if (isLandscape) maxWidth * 0.5f + sysW * 0.65f else diamondCenterX + sysW * 0.62f

        val actionKeys = listOf(
            GamepadKey(RETRO_X, diamondCenterX, diamondCenterY - diamondOffset, faceBtnSize, faceBtnSize, faceTouchRadius, "△"),
            GamepadKey(RETRO_Y, diamondCenterX - diamondOffset, diamondCenterY, faceBtnSize, faceBtnSize, faceTouchRadius, "□"),
            GamepadKey(RETRO_A, diamondCenterX + diamondOffset, diamondCenterY, faceBtnSize, faceBtnSize, faceTouchRadius, "○"),
            GamepadKey(RETRO_B, diamondCenterX, diamondCenterY + diamondOffset, faceBtnSize, faceBtnSize, faceTouchRadius, "✕"),
            GamepadKey(RETRO_SELECT, selX, sysY, sysW, sysH, sysTouchRadius, "SEL", isPill = true),
            GamepadKey(RETRO_START, staX, sysY, sysW, sysH, sysTouchRadius, "STA", isPill = true),
            GamepadKey(RETRO_L, shoulderLeftX, shoulderY, shoulderW, shoulderH, shoulderTouchRadius, "L", isPill = true),
            GamepadKey(RETRO_R, shoulderRightX, shoulderY, shoulderW, shoulderH, shoulderTouchRadius, "R", isPill = true)
        )

        val heldButtons = remember { mutableStateMapOf<PointerId, Long>() }
        var analogPointerId by remember { mutableStateOf<PointerId?>(null) }
        var analogKnobDp by remember { mutableStateOf(DpOffset(0.dp, 0.dp)) }
        var analogBits by remember { mutableStateOf(0L) }
        var pressedBits by remember { mutableStateOf(0L) }
        var lastSent by remember { mutableStateOf(0L) }

        fun syncButtons() {
            val mask = heldButtons.values.fold(analogBits) { acc, bits -> acc or bits }
            if (mask != pressedBits) pressedBits = mask
            if (mask != lastSent) {
                lastSent = mask
                currentOnButtons.value(mask)
            }
        }

        val menuGuardStartPx = with(density) { (maxWidth - 104.dp).toPx() }
        val menuGuardBottomPx = with(density) { 72.dp.toPx() }

        val dpadPxX = with(density) { dpadCenter.x.toPx() }
        val dpadPxY = with(density) { dpadCenter.y.toPx() }
        val dpadRadiusPx = with(density) { dpadTouchRadius.toPx() }

        val stickPxX = with(density) { stickCenter.x.toPx() }
        val stickPxY = with(density) { stickCenter.y.toPx() }
        val stickRadiusPx = with(density) { (stickOuterSize * 0.62f).toPx() }
        val stickMaxTravelPx = with(density) { stickMaxTravel.toPx() }

        val keyHitTargets = remember(actionKeys, density) {
            actionKeys.map { key ->
                HitTarget(
                    bit = key.bit,
                    cx = with(density) { key.x.toPx() },
                    cy = with(density) { key.y.toPx() },
                    radius = with(density) { key.touchRadius.toPx() }
                )
            }
        }

        fun dpadBitsAt(dx: Float, dy: Float): Long {
            val absX = abs(dx)
            val absY = abs(dy)
            val dead = dpadRadiusPx * 0.15f
            val diagonal = dpadRadiusPx * 0.38f
            if (absX < dead && absY < dead) return 0L
            val vertical = if (dy < 0f) RETRO_UP else RETRO_DOWN
            val horizontal = if (dx < 0f) RETRO_LEFT else RETRO_RIGHT
            if (absX >= diagonal && absY >= diagonal) return vertical or horizontal
            if (absX > absY * 1.35f) return horizontal
            if (absY > absX * 1.35f) return vertical
            return vertical or horizontal
        }

        fun updateAnalogFrom(position: Offset) {
            val dx = position.x - stickPxX
            val dy = position.y - stickPxY
            val dist = hypot(dx, dy)
            val clampedDist = dist.coerceAtMost(stickMaxTravelPx)
            val nx = if (dist > 0.001f) (dx / dist) * clampedDist else 0f
            val ny = if (dist > 0.001f) (dy / dist) * clampedDist else 0f
            analogKnobDp = with(density) { DpOffset(nx.toDp(), ny.toDp()) }

            val normX = if (stickMaxTravelPx > 0f) nx / stickMaxTravelPx else 0f
            val normY = if (stickMaxTravelPx > 0f) ny / stickMaxTravelPx else 0f
            val deadzone = 0.35f
            var bits = 0L
            if (normY < -deadzone) bits = bits or RETRO_UP
            if (normY > deadzone) bits = bits or RETRO_DOWN
            if (normX < -deadzone) bits = bits or RETRO_LEFT
            if (normX > deadzone) bits = bits or RETRO_RIGHT
            analogBits = bits
        }

        fun resetAnalog() {
            analogPointerId = null
            analogKnobDp = DpOffset(0.dp, 0.dp)
            analogBits = 0L
        }

        fun buttonBitsAt(position: Offset): Long {
            if (isLandscape && position.x >= menuGuardStartPx && position.y <= menuGuardBottomPx) return 0L
            val dDx = position.x - dpadPxX
            val dDy = position.y - dpadPxY
            if (dDx * dDx + dDy * dDy <= dpadRadiusPx * dpadRadiusPx) {
                return dpadBitsAt(dDx, dDy)
            }
            for (target in keyHitTargets) {
                val kDx = position.x - target.cx
                val kDy = position.y - target.cy
                if (kDx * kDx + kDy * kDy <= target.radius * target.radius) {
                    return target.bit
                }
            }
            return 0L
        }

        DisposableEffect(Unit) {
            onDispose {
                heldButtons.clear()
                resetAnalog()
                currentOnButtons.value(0L)
            }
        }

        val baseSurfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = effectiveAlpha)
        val pressedSurfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = (effectiveAlpha + 0.22f).coerceAtMost(1f))
        val borderStroke = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = (effectiveAlpha * 0.75f).coerceAtMost(0.9f))
        )

        if (showAnalog) {
            Surface(
                shape = CircleShape,
                color = baseSurfaceColor.copy(alpha = (effectiveAlpha * 0.7f).coerceAtMost(1f)),
                border = borderStroke,
                modifier = Modifier
                    .offset(x = stickCenter.x - stickOuterSize * 0.5f, y = stickCenter.y - stickOuterSize * 0.5f)
                    .size(stickOuterSize)
            ) {}
            Surface(
                shape = CircleShape,
                color = if (analogPointerId != null) {
                    pressedSurfaceColor
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = (effectiveAlpha * 0.88f).coerceAtMost(1f))
                },
                border = borderStroke,
                modifier = Modifier
                    .offset(
                        x = stickCenter.x + analogKnobDp.x - stickKnobSize * 0.5f,
                        y = stickCenter.y + analogKnobDp.y - stickKnobSize * 0.5f
                    )
                    .size(stickKnobSize)
            ) {}
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = baseSurfaceColor,
            border = borderStroke,
            modifier = Modifier
                .offset(
                    x = dpadCenter.x - dpadTotalSpan * 0.5f,
                    y = dpadCenter.y - dpadArmThickness * 0.5f
                )
                .size(width = dpadTotalSpan, height = dpadArmThickness)
        ) {}
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = baseSurfaceColor,
            border = borderStroke,
            modifier = Modifier
                .offset(
                    x = dpadCenter.x - dpadArmThickness * 0.5f,
                    y = dpadCenter.y - dpadTotalSpan * 0.5f
                )
                .size(width = dpadArmThickness, height = dpadTotalSpan)
        ) {}
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = baseSurfaceColor,
            modifier = Modifier
                .offset(
                    x = dpadCenter.x - (dpadArmThickness - 2.dp) * 0.5f,
                    y = dpadCenter.y - (dpadArmThickness - 2.dp) * 0.5f
                )
                .size(dpadArmThickness - 2.dp)
        ) {}
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = (effectiveAlpha * 0.18f).coerceAtMost(0.35f)),
            modifier = Modifier
                .offset(
                    x = dpadCenter.x - dpadArmThickness * 0.22f,
                    y = dpadCenter.y - dpadArmThickness * 0.22f
                )
                .size(dpadArmThickness * 0.44f)
        ) {}

        val dpadDirections = listOf(
            Triple(RETRO_UP, DpOffset(dpadCenter.x, dpadCenter.y - dpadArmLength * 0.85f), "▲"),
            Triple(RETRO_DOWN, DpOffset(dpadCenter.x, dpadCenter.y + dpadArmLength * 0.85f), "▼"),
            Triple(RETRO_LEFT, DpOffset(dpadCenter.x - dpadArmLength * 0.85f, dpadCenter.y), "◀"),
            Triple(RETRO_RIGHT, DpOffset(dpadCenter.x + dpadArmLength * 0.85f, dpadCenter.y), "▶")
        )
        val capSize = dpadArmThickness * 0.72f
        dpadDirections.forEach { (bit, pos, glyph) ->
            val isPressed = pressedBits and bit != 0L
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isPressed) pressedSurfaceColor else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0f),
                modifier = Modifier
                    .offset(x = pos.x - capSize * 0.5f, y = pos.y - capSize * 0.5f)
                    .size(capSize)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = glyph,
                        color = if (isPressed) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }

        actionKeys.forEach { key ->
            val pressed = pressedBits and key.bit != 0L
            val background = if (pressed) pressedSurfaceColor else baseSurfaceColor
            val contentColor = if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

            Surface(
                shape = if (key.isPill) RoundedCornerShape(50) else CircleShape,
                color = background,
                border = borderStroke,
                modifier = Modifier
                    .offset(x = key.x - key.width * 0.5f, y = key.y - key.height * 0.5f)
                    .size(width = key.width, height = key.height)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = key.label,
                        color = contentColor,
                        style = if (key.isPill) {
                            MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(
                    showAnalog,
                    dpadPxX,
                    dpadPxY,
                    dpadRadiusPx,
                    stickPxX,
                    stickPxY,
                    stickRadiusPx,
                    stickMaxTravelPx,
                    keyHitTargets
                ) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            for (change in event.changes) {
                                if (!change.pressed) {
                                    if (change.id == analogPointerId) {
                                        resetAnalog()
                                        change.consume()
                                    } else if (heldButtons.remove(change.id) != null) {
                                        change.consume()
                                    }
                                    continue
                                }

                                if (change.id == analogPointerId) {
                                    updateAnalogFrom(change.position)
                                    change.consume()
                                    continue
                                }

                                if (showAnalog && analogPointerId == null) {
                                    val sDx = change.position.x - stickPxX
                                    val sDy = change.position.y - stickPxY
                                    if (sDx * sDx + sDy * sDy <= stickRadiusPx * stickRadiusPx) {
                                        analogPointerId = change.id
                                        updateAnalogFrom(change.position)
                                        change.consume()
                                        continue
                                    }
                                }

                                val bits = buttonBitsAt(change.position)
                                if (bits != 0L) {
                                    heldButtons[change.id] = bits
                                    change.consume()
                                } else if (heldButtons.remove(change.id) != null) {
                                    change.consume()
                                }
                            }
                            syncButtons()
                        }
                    }
                }
        )
    }
}
