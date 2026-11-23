@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.player.sing.SingGainCurve
import com.gpo.yoin.player.sing.SingMode
import com.gpo.yoin.player.sing.VocalLevel
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Sing state & actions provided to Now Playing surfaces (cover menu, lyrics action bar, rating slots).
 * Null in isolated previews/harnesses so Sing UI stays cleanly absent unless bound.
 */
@Immutable
internal data class NowPlayingSingUi(
    val mode: SingMode,
    val vocalLevel: VocalLevel,
    val isSupported: Boolean,
    val onEnter: (expandToLyrics: Boolean) -> Unit,
    val onExit: () -> Unit,
    val onSetVocalLevel: (Float) -> Unit,
) {
    val isEnabled: Boolean get() = mode !is SingMode.Off
    val isPreparing: Boolean get() = mode is SingMode.Preparing
    val isActive: Boolean get() = mode is SingMode.Ready
}

internal val LocalNowPlayingSing = staticCompositionLocalOf<NowPlayingSingUi?> { null }

/**
 * Yoin 1.5dp rounded-cap stroke microphone icons matching `yoin.mic` proportions on iOS and
 * `docs/icons/svg/` Luminous stroke weight.
 */
internal object SingIcons {
    val Mic: ImageVector by lazy {
        ImageVector.Builder(
            name = "YoinSingMic",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Rounded microphone capsule (x: 9..15, y: 3.2..13.0, r: 3)
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 3.2f)
                arcTo(3f, 3f, 0f, false, true, 15f, 6.2f)
                verticalLineTo(10f)
                arcTo(3f, 3f, 0f, false, true, 9f, 10f)
                verticalLineTo(6.2f)
                arcTo(3f, 3f, 0f, false, true, 12f, 3.2f)
                close()
            }
            // Cradle U-arc
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6.3f, 10.6f)
                arcTo(5.7f, 5.7f, 0f, false, false, 17.7f, 10.6f)
            }
            // Stem
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 16.4f)
                verticalLineTo(20.5f)
            }
        }.build()
    }

    val MicOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "YoinSingMicOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 3.2f)
                arcTo(3f, 3f, 0f, false, true, 15f, 6.2f)
                verticalLineTo(10f)
                arcTo(3f, 3f, 0f, false, true, 9f, 10f)
                verticalLineTo(6.2f)
                arcTo(3f, 3f, 0f, false, true, 12f, 3.2f)
                close()
            }
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6.3f, 10.6f)
                arcTo(5.7f, 5.7f, 0f, false, false, 17.7f, 10.6f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 16.4f)
                verticalLineTo(20.5f)
            }
            // Diagonal slash (top-left to bottom-right)
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4.5f, 4.5f)
                lineTo(19.5f, 19.5f)
            }
        }.build()
    }
}

/**
 * Pure, testable state machine implementing the two-step anti-accidental-dismiss left-drag rule:
 * 1. Dragging to `0%` in one continuous gesture stops at minimum and NEVER turns Sing off, no matter
 *    how far past the left edge the finger travels.
 * 2. Only when vocal is ALREADY at `0%`, the user lifts their finger, and starts a NEW drag left past
 *    [dismissThresholdPx] does Sing turn off.
 */
@Stable
internal class SingCapsuleDismissState(
    private val dismissThresholdPx: Float,
) {
    sealed interface StepResult {
        data class Update(val level: Float, val overpullProgress: Float) : StepResult

        data object Dismiss : StepResult
    }

    var isActive: Boolean = false
        private set
    var startedAtMinimum: Boolean = false
        private set
    var startLevel: Float = SingGainCurve.DEFAULT_POSITION
        private set
    var dismissed: Boolean = false
        private set

    fun begin(currentLevel: Float) {
        isActive = true
        startedAtMinimum = currentLevel <= MIN_EPSILON
        startLevel = currentLevel.coerceIn(0f, 1f)
        dismissed = false
    }

    fun drag(translationX: Float, trackWidthPx: Float): StepResult {
        if (!isActive) begin(startLevel)
        if (dismissed) return StepResult.Dismiss

        if (startedAtMinimum && translationX < 0f) {
            val pull = -translationX
            val threshold = dismissThresholdPx.coerceAtLeast(12f)
            if (pull >= threshold) {
                dismissed = true
                return StepResult.Dismiss
            }
            return StepResult.Update(level = 0f, overpullProgress = (pull / threshold).coerceIn(0f, 1f))
        }

        val width = trackWidthPx.coerceAtLeast(1f)
        val raw = (startLevel + translationX / width).coerceIn(0f, 1f)
        return StepResult.Update(level = SingGainCurve.snap(raw), overpullProgress = 0f)
    }

    fun end() {
        isActive = false
        startedAtMinimum = false
        dismissed = false
    }

    companion object {
        const val MIN_EPSILON = 0.005f
    }
}

/**
 * Quiet frame-driven rotating arc drawn inside the Sing button during [SingMode.Preparing].
 * Zero popups or banners; avoids raw `tween(` / `spring(` APIs.
 */
@Composable
private fun SingPreparingRing(
    color: Color,
    strokeWidth: Dp = 2.dp,
    modifier: Modifier = Modifier,
) {
    var angleDeg by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastNanos != 0L) {
                    val dtSec = (frameNanos - lastNanos) / 1_000_000_000f
                    angleDeg = (angleDeg + dtSec * 340f) % 360f
                }
                lastNanos = frameNanos
            }
        }
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        val strokePx = strokeWidth.toPx()
        val inset = strokePx / 2f
        drawArc(
            color = color,
            startAngle = angleDeg,
            sweepAngle = 235f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokePx, size.height - strokePx),
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}

/**
 * Full-Screen Lyrics 5th (LAST) control:
 * - Off: `iconSize × iconSize` rounded square (`iconSize * 0.31`) matching [LyricsActionIcon].
 * - Preparing: stays `iconSize × iconSize` with a quiet spinning ring inside (tap cancels, zero popups).
 * - Active ([SingMode.Ready]): expands on [YoinMotion.defaultSpatialSpec] into a `124.dp` short Capsule
 *   (flush with the 364dp row: `4*52 + 4*8 + 124 = 364`), with live vocal fill, subtle 12% detent notch,
 *   left mic tap-to-exit, two-step anti-accidental-dismiss left-drag, and **zero percentage text**.
 */
@Composable
internal fun SingLyricsCapsuleKey(
    sing: NowPlayingSingUi,
    iconSize: Dp = 52.dp,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val density = LocalDensity.current
    val isActive = sing.isActive
    val isPreparing = sing.isPreparing
    val expandedWidth = if (iconSize >= 50.dp) 124.dp else 108.dp
    val targetWidth = if (isActive) expandedWidth else iconSize
    val targetCorner = if (isActive) iconSize / 2 else iconSize * 0.31f

    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard),
        label = "singCapsuleWidth",
    )
    val animatedCorner by animateDpAsState(
        targetValue = targetCorner,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard),
        label = "singCapsuleCorner",
    )

    var dragging by remember { mutableStateOf(false) }
    var overpull by remember { mutableFloatStateOf(0f) }
    val animatedOverpull by animateFloatAsState(
        targetValue = overpull,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard),
        label = "singCapsuleOverpull",
    )
    val latestSing by rememberUpdatedState(sing)
    val latestHaptics by rememberUpdatedState(haptics)
    val thresholdPx = with(density) { 28.dp.toPx() }
    val micZonePx = with(density) { 34.dp.toPx() }
    val slopPx = with(density) { 5.dp.toPx() }
    val dismissState = remember(thresholdPx) { SingCapsuleDismissState(thresholdPx) }

    val shape = RoundedCornerShape(animatedCorner)
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val fillColor = MaterialTheme.colorScheme.primary
    val onFillColor = MaterialTheme.colorScheme.onPrimary
    val singLabel = stringResource(R.string.np_sing)
    val vocalLabel = stringResource(R.string.np_cd_sing_vocal_volume)

    val liveLevel = sing.vocalLevel.position.coerceIn(0f, 1f)
    val animatedLevel by animateFloatAsState(
        targetValue = liveLevel,
        animationSpec = YoinMotion.spatialSpring(),
        label = "singCapsuleLevel",
    )
    val shownFraction = if (dragging) liveLevel else animatedLevel
    val detentFraction = SingGainCurve.DEFAULT_POSITION

    var capsuleWidthPx by remember { mutableIntStateOf(1) }

    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        Box(
            modifier = modifier
                .offset {
                    val shiftPx = if (isActive) (-animatedOverpull * 6.dp.toPx()).roundToInt() else 0
                    IntOffset(shiftPx, 0)
                }
                .size(width = animatedWidth, height = iconSize)
                .onSizeChanged { capsuleWidthPx = it.width.coerceAtLeast(1) }
                .clip(shape)
                .background(containerColor)
                .handPointer()
                .semantics {
                    if (isActive) {
                        contentDescription = vocalLabel
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            current = liveLevel,
                            range = 0f..1f,
                        )
                    } else {
                        contentDescription = singLabel
                        role = Role.Button
                    }
                }
                .then(
                    if (!isActive) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(),
                        ) {
                            latestHaptics.performContextClick()
                            if (latestSing.isEnabled) {
                                latestSing.onExit()
                            } else {
                                latestSing.onEnter(false)
                            }
                        }
                    } else {
                        Modifier.pointerInput(dismissState) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val startPos = down.position
                                var moved = false
                                var wasInDetent = latestSing.vocalLevel.atDefault
                                dismissState.begin(latestSing.vocalLevel.position)
                                dragging = true
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        val dx = change.position.x - startPos.x
                                        val dy = change.position.y - startPos.y
                                        if (!moved && hypot(dx, dy) >= slopPx) {
                                            moved = true
                                        }
                                        if (!change.pressed) {
                                            change.consume()
                                            if (!moved && !dismissState.dismissed && startPos.x <= micZonePx) {
                                                latestHaptics.performContextClick()
                                                latestSing.onExit()
                                            }
                                            break
                                        }
                                        if (moved) {
                                            change.consume()
                                            val trackW = (capsuleWidthPx - 16.dp.toPx()).coerceAtLeast(1f)
                                            when (val step = dismissState.drag(dx, trackW)) {
                                                is SingCapsuleDismissState.StepResult.Update -> {
                                                    overpull = step.overpullProgress
                                                    val inDetent = step.level == SingGainCurve.DEFAULT_POSITION
                                                    if (inDetent && !wasInDetent) {
                                                        latestHaptics.performTick()
                                                    }
                                                    wasInDetent = inDetent
                                                    latestSing.onSetVocalLevel(step.level)
                                                }
                                                SingCapsuleDismissState.StepResult.Dismiss -> {
                                                    latestHaptics.performConfirm()
                                                    overpull = 0f
                                                    latestSing.onExit()
                                                    break
                                                }
                                            }
                                        }
                                    }
                                } finally {
                                    dismissState.end()
                                    dragging = false
                                    overpull = 0f
                                }
                            }
                        }
                    },
                ),
            contentAlignment = if (isActive) Alignment.CenterStart else Alignment.Center,
        ) {
            if (isActive) {
                // Live vocal fill bar (zero percentage text)
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(shownFraction.coerceIn(0f, 1f))
                        .clip(CircleShape)
                        .background(fillColor),
                )
                // Subtle 12% sweet-spot detent notch
                val notchColor = if (shownFraction >= detentFraction) {
                    onFillColor.copy(alpha = 0.42f)
                } else {
                    contentColor.copy(alpha = 0.32f)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset {
                            val minX = micZonePx
                            val x = maxOf(minX, capsuleWidthPx * detentFraction).roundToInt()
                            IntOffset(x, 0)
                        }
                        .size(width = 2.dp, height = 10.dp)
                        .clip(CircleShape)
                        .background(notchColor),
                )
                // Left mic glyph with dual-tone clip over fill edge
                Box(
                    modifier = Modifier.size(width = 34.dp, height = iconSize),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = SingIcons.Mic,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(width = 34.dp, height = iconSize)
                        .clearAndSetSemantics {}
                        .drawWithContent {
                            val frac = shownFraction.coerceIn(0f, 1f)
                            val fillW = capsuleWidthPx * frac
                            if (fillW <= 0f) return@drawWithContent
                            val path = Path().apply {
                                addOutline(
                                    CircleShape.createOutline(
                                        Size(fillW, size.height),
                                        layoutDirection,
                                        this@drawWithContent,
                                    ),
                                )
                            }
                            clipPath(path) { this@drawWithContent.drawContent() }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = SingIcons.Mic,
                        contentDescription = null,
                        tint = onFillColor,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                Icon(
                    imageVector = SingIcons.Mic,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(22.dp),
                )
                if (isPreparing) {
                    SingPreparingRing(
                        color = fillColor,
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(6.dp),
                    )
                }
            }
        }
    }
}

/**
 * In-place morph for [NowPlayingRatingBar] when Sing is active and the Lyrics action bar capsule is not
 * visible (Phone Compact, or Tablet Dual-Pane switched away from Lyrics). Supports both [Orientation.Vertical]
 * (rating column) and [Orientation.Horizontal] (stacked landscape bar). Zero percentage text.
 */
@Composable
internal fun SingVocalSlider(
    level: VocalLevel,
    onLevelChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    orientation: Orientation = Orientation.Vertical,
) {
    val isVertical = orientation == Orientation.Vertical
    val haptics = rememberYoinHaptics()
    val latestOnLevelChange by rememberUpdatedState(onLevelChange)
    val latestHaptics by rememberUpdatedState(haptics)
    var dragging by remember { mutableStateOf(false) }
    var trackDimensionPx by remember { mutableIntStateOf(1) }

    val targetFraction = level.position.coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = YoinMotion.spatialSpring(),
        label = "singVocalSliderFill",
    )
    val shownFraction = if (dragging) targetFraction else animatedFraction
    val detentFraction = SingGainCurve.DEFAULT_POSITION
    val vocalLabel = stringResource(R.string.np_cd_sing_vocal_volume)

    Box(
        modifier = modifier
            .then(if (isVertical) Modifier.width(48.dp) else Modifier.fillMaxWidth().height(48.dp))
            .clip(YoinShapeTokens.Full)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged {
                trackDimensionPx = (if (isVertical) it.height else it.width).coerceAtLeast(1)
            }
            .semantics {
                contentDescription = vocalLabel
                progressBarRangeInfo = ProgressBarRangeInfo(current = targetFraction, range = 0f..1f)
            }
            .pointerInput(orientation) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    dragging = true
                    var wasInDetent = false
                    fun applyAt(rawPos: Float) {
                        val raw = if (isVertical) {
                            1f - (rawPos / trackDimensionPx)
                        } else {
                            rawPos / trackDimensionPx
                        }
                        val snapped = SingGainCurve.snap(raw)
                        val inDetent = snapped == SingGainCurve.DEFAULT_POSITION
                        if (inDetent && !wasInDetent) {
                            latestHaptics.performTick()
                        }
                        wasInDetent = inDetent
                        latestOnLevelChange(snapped)
                    }
                    applyAt(if (isVertical) down.position.y else down.position.x)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            applyAt(if (isVertical) change.position.y else change.position.x)
                            change.consume()
                            if (!change.pressed) break
                        }
                    } finally {
                        dragging = false
                    }
                }
            },
        contentAlignment = if (isVertical) Alignment.BottomCenter else Alignment.CenterStart,
    ) {
        Box(
            modifier = (
                if (isVertical) {
                    Modifier.fillMaxWidth().fillMaxHeight(shownFraction.coerceIn(0f, 1f))
                } else {
                    Modifier.fillMaxHeight().fillMaxWidth(shownFraction.coerceIn(0f, 1f))
                }
                )
                .clip(YoinShapeTokens.Full)
                .background(MaterialTheme.colorScheme.primary),
        )

        // Subtle 12% detent notch
        val notchColor = if (shownFraction >= detentFraction) {
            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
        }
        Box(
            modifier = Modifier
                .align(if (isVertical) Alignment.BottomCenter else Alignment.CenterStart)
                .offset {
                    if (isVertical) {
                        val minFromBottom = 36.dp.roundToPx()
                        val yFromBottom = maxOf(minFromBottom, (trackDimensionPx * detentFraction).roundToInt())
                        IntOffset(0, -yFromBottom)
                    } else {
                        val minFromStart = 36.dp.roundToPx()
                        val xFromStart = maxOf(minFromStart, (trackDimensionPx * detentFraction).roundToInt())
                        IntOffset(xFromStart, 0)
                    }
                }
                .size(
                    width = if (isVertical) 12.dp else 2.dp,
                    height = if (isVertical) 2.dp else 12.dp,
                )
                .clip(CircleShape)
                .background(notchColor),
        )

        // Dual-tone mic icon clipped along the live fill boundary
        val iconAlign = if (isVertical) Alignment.BottomCenter else Alignment.CenterStart
        val iconPadding = if (isVertical) Modifier.padding(bottom = 12.dp) else Modifier.padding(start = 14.dp)
        val fillShape = YoinShapeTokens.Full
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = SingIcons.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(iconAlign)
                    .then(iconPadding)
                    .size(20.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {}
                    .drawWithContent {
                        val frac = shownFraction.coerceIn(0f, 1f)
                        if (frac <= 0f) return@drawWithContent
                        val fillSize = if (isVertical) {
                            Size(size.width, size.height * frac)
                        } else {
                            Size(size.width * frac, size.height)
                        }
                        val origin = when {
                            isVertical -> Offset(0f, size.height - fillSize.height)
                            layoutDirection == LayoutDirection.Rtl -> Offset(size.width - fillSize.width, 0f)
                            else -> Offset.Zero
                        }
                        val path = Path().apply {
                            addOutline(fillShape.createOutline(fillSize, layoutDirection, this@drawWithContent))
                            translate(origin)
                        }
                        clipPath(path) { this@drawWithContent.drawContent() }
                    },
            ) {
                Icon(
                    imageVector = SingIcons.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(iconAlign)
                        .then(iconPadding)
                        .size(20.dp),
                )
            }
        }
    }
}

/**
 * In-place morph for [FavoriteButton] (`44.dp` circle) showing Yoin's 1.5dp stroke `MicOff` icon to exit Sing.
 */
@Composable
internal fun SingExitButton(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    isPreparing: Boolean = false,
) {
    val haptics = rememberYoinHaptics()
    val exitLabel = stringResource(R.string.np_cd_exit_sing)
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .handPointer()
            .semantics {
                contentDescription = exitLabel
                role = Role.Button
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
            ) {
                haptics.performContextClick()
                onExit()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = SingIcons.MicOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(22.dp),
        )
        if (isPreparing) {
            SingPreparingRing(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}

/**
 * Animated slot that cross-morphs between the standard RatingBar and [SingVocalSlider] using YoinMotion tokens.
 */
@Composable
internal fun SingAwareRatingBarSlot(
    showSing: Boolean,
    sing: NowPlayingSingUi?,
    orientation: Orientation = Orientation.Vertical,
    modifier: Modifier = Modifier,
    ratingBar: @Composable () -> Unit,
) {
    AnimatedContent(
        targetState = showSing && sing != null,
        transitionSpec = {
            (
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) +
                    YoinMotion.scaleIn(role = YoinMotionRole.Standard, initialScale = 0.94f)
                ) togetherWith YoinMotion.fadeOut(role = YoinMotionRole.Standard)
        },
        label = "singRatingMorph",
        modifier = modifier,
    ) { isSingSlot ->
        if (isSingSlot && sing != null) {
            SingVocalSlider(
                level = sing.vocalLevel,
                onLevelChange = sing.onSetVocalLevel,
                orientation = orientation,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ratingBar()
        }
    }
}

/**
 * Animated slot that cross-morphs between the Favorite heart button(s) and [SingExitButton].
 */
@Composable
internal fun SingAwareFavoriteSlot(
    showSing: Boolean,
    sing: NowPlayingSingUi?,
    modifier: Modifier = Modifier,
    favoriteButtons: @Composable () -> Unit,
) {
    AnimatedContent(
        targetState = showSing && sing != null,
        transitionSpec = {
            (
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) +
                    YoinMotion.scaleIn(role = YoinMotionRole.Standard, initialScale = 0.90f)
                ) togetherWith YoinMotion.fadeOut(role = YoinMotionRole.Standard)
        },
        contentAlignment = Alignment.Center,
        label = "singFavoriteMorph",
        modifier = modifier,
    ) { isSingSlot ->
        if (isSingSlot && sing != null) {
            SingExitButton(
                onExit = sing.onExit,
                isPreparing = sing.isPreparing,
            )
        } else {
            favoriteButtons()
        }
    }
}
