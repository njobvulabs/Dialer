package com.example.ui.screens.ppsspp

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
import kotlin.math.sqrt

internal const val PSP_CROSS = 1L shl 0
internal const val PSP_CIRCLE = 1L shl 8
internal const val PSP_SELECT = 1L shl 2
internal const val PSP_START = 1L shl 3
internal const val PSP_UP = 1L shl 4
internal const val PSP_DOWN = 1L shl 5
internal const val PSP_LEFT = 1L shl 6
internal const val PSP_RIGHT = 1L shl 7
internal const val PSP_TRIANGLE = 1L shl 9
internal const val PSP_SQUARE = 1L shl 1
internal const val PSP_L = 1L shl 10
internal const val PSP_R = 1L shl 11

private data class PadOffset(val x: Dp, val y: Dp)

private data class PadPoint(val x: Float, val y: Float)

private data class PadTarget(
    val bit: Long,
    val cx: Dp,
    val cy: Dp,
    val width: Dp,
    val height: Dp,
    val touchRadius: Dp,
    val label: String,
    val isPill: Boolean
)

private data class PadTargetPx(
    val bit: Long,
    val cx: Float,
    val cy: Float,
    val width: Dp,
    val height: Dp,
    val radius: Float,
    val label: String,
    val isPill: Boolean
)

private data class PadHitGeometry(
    val showAnalog: Boolean,
    val dpadCenterPx: PadPoint,
    val dpadRadiusPx: Float,
    val analogCenterPx: PadPoint,
    val analogTouchRadiusPx: Float,
    val analogMaxTravelPx: Float,
    val targetsPx: List<PadTargetPx>,
    val menuGuardStartPx: Float = Float.MAX_VALUE,
    val menuGuardBottomPx: Float = 0f
)

@Composable
fun PpssppGamepad(
    alpha: Float,
    scale: Float,
    verticalOffset: Dp,
    showAnalog: Boolean = true,
    modifier: Modifier = Modifier,
    onButtons: (Long) -> Unit,
    onAnalog: (Float, Float) -> Unit
) {
    val density = LocalDensity.current
    val currentOnButtons = rememberUpdatedState(onButtons)
    val currentOnAnalog = rememberUpdatedState(onAnalog)
    val effectiveAlpha = alpha.coerceIn(0.3f, 1f)

    val held = remember { mutableStateMapOf<PointerId, Long>() }
    var analogPointerId by remember { mutableStateOf<PointerId?>(null) }
    var pressedBits by remember { mutableStateOf(0L) }
    var analogOffsetPx by remember { mutableStateOf(Offset.Zero) }
    var lastBits by remember { mutableStateOf(0L) }
    var lastAnalog by remember { mutableStateOf(Offset.Zero) }

    val geometryState = remember {
        mutableStateOf(
            PadHitGeometry(
                showAnalog = true,
                dpadCenterPx = PadPoint(0f, 0f),
                dpadRadiusPx = 0f,
                analogCenterPx = PadPoint(0f, 0f),
                analogTouchRadiusPx = 0f,
                analogMaxTravelPx = 0f,
                targetsPx = emptyList()
            )
        )
    }

    fun syncButtons() {
        val mask = held.values.fold(0L) { acc, bits -> acc or bits }
        if (mask != pressedBits) pressedBits = mask
        if (mask != lastBits) {
            lastBits = mask
            currentOnButtons.value(mask)
        }
    }

    fun updateAnalogFrom(position: Offset, geom: PadHitGeometry) {
        var dx = position.x - geom.analogCenterPx.x
        var dy = position.y - geom.analogCenterPx.y
        val distance = sqrt(dx * dx + dy * dy)
        val maxTravel = geom.analogMaxTravelPx.coerceAtLeast(1f)
        if (distance > maxTravel && distance > 0f) {
            dx *= maxTravel / distance
            dy *= maxTravel / distance
        }
        val vector = Offset(dx, dy)
        analogOffsetPx = vector
        if (vector != lastAnalog) {
            lastAnalog = vector
            val nx = (dx / maxTravel).coerceIn(-1f, 1f)
            val ny = (-dy / maxTravel).coerceIn(-1f, 1f)
            currentOnAnalog.value(nx * 32767f, ny * 32767f)
        }
    }

    fun resetAnalog() {
        analogPointerId = null
        analogOffsetPx = Offset.Zero
        if (lastAnalog != Offset.Zero) {
            lastAnalog = Offset.Zero
            currentOnAnalog.value(0f, 0f)
        }
    }

    fun buttonBitsAt(position: Offset, geom: PadHitGeometry): Long {
        if (position.x >= geom.menuGuardStartPx && position.y <= geom.menuGuardBottomPx) return 0L
        val dx = position.x - geom.dpadCenterPx.x
        val dy = position.y - geom.dpadCenterPx.y
        if (dx * dx + dy * dy <= geom.dpadRadiusPx * geom.dpadRadiusPx) {
            val ax = abs(dx)
            val ay = abs(dy)
            val dead = geom.dpadRadiusPx * 0.14f
            val diagonal = geom.dpadRadiusPx * 0.36f
            if (ax < dead && ay < dead) return 0L
            val vertical = if (dy < 0f) PSP_UP else PSP_DOWN
            val horizontal = if (dx < 0f) PSP_LEFT else PSP_RIGHT
            if (ax >= diagonal && ay >= diagonal) return vertical or horizontal
            if (ax > ay * 1.35f) return horizontal
            if (ay > ax * 1.35f) return vertical
            return vertical or horizontal
        }
        for (target in geom.targetsPx) {
            val tx = position.x - target.cx
            val ty = position.y - target.cy
            if (tx * tx + ty * ty <= target.radius * target.radius) return target.bit
        }
        return 0L
    }

    DisposableEffect(Unit) {
        onDispose {
            held.clear()
            resetAnalog()
            currentOnButtons.value(0L)
        }
    }

    BoxWithConstraints(
        modifier = modifier.navigationBarsPadding(),
        contentAlignment = Alignment.TopStart
    ) {
        val padW = maxWidth
        val padH = maxHeight
        val isLandscape = padW > padH

        val baseSize = if (isLandscape) {
            minOf(padW.value * 0.062f, padH.value * 0.135f).coerceIn(44f, 58f)
        } else {
            (padW.value * 0.15f).coerceIn(48f, 62f)
        }
        val visual = baseSize.dp * scale

        val dpadArm = visual * 0.82f
        val dpadThickness = visual * 0.78f
        val dpadSpan = dpadArm * 2f + dpadThickness
        val dpadRadiusPx = with(density) { (dpadSpan * 0.68f).toPx() }

        val analogSize = visual * 1.44f
        val analogKnobSize = visual * 0.64f
        val analogMaxTravelDp = (analogSize - analogKnobSize) * 0.44f
        val analogMaxTravelPx = with(density) { analogMaxTravelDp.toPx() }
        val analogTouchRadiusPx = with(density) { (analogSize * 0.66f).toPx() }

        val bottomPad = 14.dp
        val sysW = visual * 1.12f
        val sysH = visual * 0.56f
        val sysY = (padH - sysH * 0.5f - bottomPad - verticalOffset)
            .coerceIn(sysH * 2.2f, padH - sysH * 0.5f - 4.dp)

        val dpadCenter = if (isLandscape) {
            PadOffset(
                x = (dpadSpan * 0.5f + 24.dp).coerceAtMost(padW * 0.25f),
                y = (sysY - dpadSpan * 0.5f - 10.dp)
                    .coerceIn(dpadSpan * 0.5f + 92.dp, padH - dpadSpan * 0.5f - 8.dp)
            )
        } else {
            val pY = (sysY - sysH * 0.5f - dpadSpan * 0.5f - 14.dp)
                .coerceIn(dpadSpan * 0.5f + analogSize + 10.dp, sysY - sysH - dpadSpan * 0.3f)
            PadOffset(x = (dpadSpan * 0.5f + 14.dp).coerceAtMost(padW * 0.44f), y = pY)
        }

        val analogCenter = if (isLandscape) {
            PadOffset(
                x = (dpadCenter.x + dpadSpan * 0.5f + analogSize * 0.68f)
                    .coerceAtMost(padW * 0.42f),
                y = dpadCenter.y
            )
        } else {
            PadOffset(
                x = dpadCenter.x,
                y = (dpadCenter.y - dpadSpan * 0.5f - analogSize * 0.58f)
                    .coerceAtLeast(analogSize * 0.5f + visual + 8.dp)
            )
        }

        val faceSize = visual * 0.94f
        val faceSpread = faceSize * 0.94f
        val faceCenterX = if (isLandscape) {
            (padW - faceSize * 0.5f - faceSpread - 24.dp).coerceAtLeast(padW * 0.72f)
        } else {
            (padW - faceSize * 0.5f - faceSpread - 14.dp).coerceAtLeast(padW * 0.58f)
        }
        val faceCenterY = if (isLandscape) {
            dpadCenter.y
        } else {
            (sysY - sysH * 0.5f - faceSpread - faceSize * 0.5f - 16.dp)
                .coerceAtLeast(faceSpread + faceSize)
        }

        val shoulderW = visual * 1.44f
        val shoulderH = visual * 0.62f
        val shoulderY = if (isLandscape) {
            64.dp.coerceAtMost(
                (dpadCenter.y - dpadSpan * 0.5f - shoulderH * 0.5f - 8.dp).coerceAtLeast(56.dp)
            )
        } else {
            (analogCenter.y - analogSize * 0.5f - shoulderH * 0.5f - 10.dp)
                .coerceAtLeast(shoulderH * 0.5f + 4.dp)
        }
        val shoulderLeftX = (shoulderW * 0.5f + if (isLandscape) 26.dp else 16.dp)
            .coerceAtMost(padW * 0.35f)
        val shoulderRightX = (padW - shoulderW * 0.5f - if (isLandscape) 26.dp else 16.dp)
            .coerceAtLeast(padW * 0.65f)

        val selX = if (isLandscape) padW * 0.5f - sysW * 0.65f else faceCenterX - sysW * 0.62f
        val staX = if (isLandscape) padW * 0.5f + sysW * 0.65f else faceCenterX + sysW * 0.62f

        val targets = listOf(
            PadTarget(PSP_TRIANGLE, faceCenterX, faceCenterY - faceSpread, faceSize, faceSize, faceSize * 0.76f, "△", false),
            PadTarget(PSP_CIRCLE, faceCenterX + faceSpread, faceCenterY, faceSize, faceSize, faceSize * 0.76f, "○", false),
            PadTarget(PSP_CROSS, faceCenterX, faceCenterY + faceSpread, faceSize, faceSize, faceSize * 0.76f, "✕", false),
            PadTarget(PSP_SQUARE, faceCenterX - faceSpread, faceCenterY, faceSize, faceSize, faceSize * 0.76f, "□", false),
            PadTarget(PSP_SELECT, selX, sysY, sysW, sysH, sysW * 0.72f, "SEL", true),
            PadTarget(PSP_START, staX, sysY, sysW, sysH, sysW * 0.72f, "STA", true),
            PadTarget(PSP_L, shoulderLeftX, shoulderY, shoulderW, shoulderH, shoulderW * 0.72f, "L", true),
            PadTarget(PSP_R, shoulderRightX, shoulderY, shoulderW, shoulderH, shoulderW * 0.72f, "R", true)
        )

        val targetsPx = targets.map { target ->
            PadTargetPx(
                bit = target.bit,
                cx = with(density) { target.cx.toPx() },
                cy = with(density) { target.cy.toPx() },
                width = target.width,
                height = target.height,
                radius = with(density) { target.touchRadius.toPx() },
                label = target.label,
                isPill = target.isPill
            )
        }

        val dpadCenterPx = with(density) { PadPoint(dpadCenter.x.toPx(), dpadCenter.y.toPx()) }
        val analogCenterPx = with(density) { PadPoint(analogCenter.x.toPx(), analogCenter.y.toPx()) }

        geometryState.value = PadHitGeometry(
            showAnalog = showAnalog,
            dpadCenterPx = dpadCenterPx,
            dpadRadiusPx = dpadRadiusPx,
            analogCenterPx = analogCenterPx,
            analogTouchRadiusPx = analogTouchRadiusPx,
            analogMaxTravelPx = analogMaxTravelPx,
            targetsPx = targetsPx,
            menuGuardStartPx = if (isLandscape) with(density) { (padW - 104.dp).toPx() } else Float.MAX_VALUE,
            menuGuardBottomPx = if (isLandscape) with(density) { 72.dp.toPx() } else 0f
        )

        val baseColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = effectiveAlpha)
        val pressedColor = MaterialTheme.colorScheme.primary.copy(alpha = (effectiveAlpha + 0.22f).coerceAtMost(1f))
        val border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = (effectiveAlpha * 0.72f).coerceAtMost(0.9f))
        )

        if (showAnalog) {
            Surface(
                shape = CircleShape,
                color = baseColor.copy(alpha = (effectiveAlpha * 0.72f).coerceAtMost(1f)),
                border = border,
                modifier = Modifier
                    .offset(x = analogCenter.x - analogSize * 0.5f, y = analogCenter.y - analogSize * 0.5f)
                    .size(analogSize)
            ) {}
            Surface(
                shape = CircleShape,
                color = if (analogPointerId != null) {
                    pressedColor
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = (effectiveAlpha * 0.88f).coerceAtMost(1f))
                },
                border = border,
                modifier = Modifier
                    .offset(
                        x = analogCenter.x + with(density) { analogOffsetPx.x.toDp() } - analogKnobSize * 0.5f,
                        y = analogCenter.y + with(density) { analogOffsetPx.y.toDp() } - analogKnobSize * 0.5f
                    )
                    .size(analogKnobSize)
            ) {}
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = baseColor,
            border = border,
            modifier = Modifier
                .offset(x = dpadCenter.x - dpadSpan * 0.5f, y = dpadCenter.y - dpadThickness * 0.5f)
                .size(width = dpadSpan, height = dpadThickness)
        ) {}
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = baseColor,
            border = border,
            modifier = Modifier
                .offset(x = dpadCenter.x - dpadThickness * 0.5f, y = dpadCenter.y - dpadSpan * 0.5f)
                .size(width = dpadThickness, height = dpadSpan)
        ) {}
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = baseColor,
            modifier = Modifier
                .offset(x = dpadCenter.x - (dpadThickness - 2.dp) * 0.5f, y = dpadCenter.y - (dpadThickness - 2.dp) * 0.5f)
                .size(dpadThickness - 2.dp)
        ) {}
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = (effectiveAlpha * 0.2f).coerceAtMost(0.35f)),
            modifier = Modifier
                .offset(x = dpadCenter.x - dpadThickness * 0.22f, y = dpadCenter.y - dpadThickness * 0.22f)
                .size(dpadThickness * 0.44f)
        ) {}

        val dpadGlyphs = listOf(
            Triple(PSP_UP, PadOffset(dpadCenter.x, dpadCenter.y - dpadArm * 0.85f), "▲"),
            Triple(PSP_DOWN, PadOffset(dpadCenter.x, dpadCenter.y + dpadArm * 0.85f), "▼"),
            Triple(PSP_LEFT, PadOffset(dpadCenter.x - dpadArm * 0.85f, dpadCenter.y), "◀"),
            Triple(PSP_RIGHT, PadOffset(dpadCenter.x + dpadArm * 0.85f, dpadCenter.y), "▶")
        )
        val glyphSize = dpadThickness * 0.72f
        dpadGlyphs.forEach { (bit, position, glyph) ->
            val pressed = pressedBits and bit != 0L
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (pressed) pressedColor else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0f),
                modifier = Modifier
                    .offset(x = position.x - glyphSize * 0.5f, y = position.y - glyphSize * 0.5f)
                    .size(glyphSize)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = glyph,
                        color = if (pressed) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }

        targets.forEach { target ->
            val pressed = pressedBits and target.bit != 0L
            val x = target.cx - target.width * 0.5f
            val y = target.cy - target.height * 0.5f
            Surface(
                shape = if (target.isPill) RoundedCornerShape(50) else CircleShape,
                color = if (pressed) pressedColor else baseColor,
                border = border,
                modifier = Modifier
                    .offset(x = x, y = y)
                    .size(width = target.width, height = target.height)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = target.label,
                        color = if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        style = if (target.isPill) {
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
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val geom = geometryState.value
                            for (change in event.changes) {
                                val id = change.id
                                if (!change.pressed) {
                                    if (id == analogPointerId) {
                                        resetAnalog()
                                        change.consume()
                                    } else if (held.remove(id) != null) {
                                        change.consume()
                                    }
                                    continue
                                }

                                if (id == analogPointerId) {
                                    updateAnalogFrom(change.position, geom)
                                    change.consume()
                                    continue
                                }

                                if (geom.showAnalog && analogPointerId == null) {
                                    val aDx = change.position.x - geom.analogCenterPx.x
                                    val aDy = change.position.y - geom.analogCenterPx.y
                                    if (aDx * aDx + aDy * aDy <= geom.analogTouchRadiusPx * geom.analogTouchRadiusPx) {
                                        analogPointerId = id
                                        updateAnalogFrom(change.position, geom)
                                        change.consume()
                                        continue
                                    }
                                }

                                val bits = buttonBitsAt(change.position, geom)
                                if (bits != 0L) {
                                    held[id] = bits
                                    change.consume()
                                } else if (held.remove(id) != null) {
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
