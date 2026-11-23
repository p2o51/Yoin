@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import kotlinx.coroutines.delay

/**
 * Sing state & actions provided to Now Playing surfaces (cover menu, lyrics action bar, rating slots).
 * Null in isolated previews/harnesses so Sing UI stays cleanly absent unless bound.
 */
@Immutable
internal data class NowPlayingSingUi(
    val mode: SingMode,
    val vocalLevel: VocalLevel,
    val isSupported: Boolean,
    val failureTick: Int = 0,
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
 * Full-Screen Lyrics 5th (LAST) control:
 * - Off: `iconSize × iconSize` rounded square (`iconSize * 0.31`) matching [LyricsActionIcon].
 * - Preparing: stays `iconSize × iconSize` with a crisp `CircularProgressIndicator` (`22.dp`, `2.dp`,
 *   `onPrimaryContainer`, matching `TrackLibraryButton`; tap cancels, zero popups).
 * - Failure: brief horizontal micro-shake + `errorContainer` tint flash (no modal/toast).
 * - Active ([SingMode.Ready]): expands on [YoinMotion.defaultSpatialSpec] into a `124.dp` short Capsule
 *   (`surfaceVariant` track + `primary` flat-edged fill clipped by the outer capsule for crisp contrast),
 *   protected by `Modifier.systemGestureExclusion()`, with subtle 12% detent notch, left mic tap-to-exit,
 *   two-step anti-accidental-dismiss left-drag, and **zero percentage text**.
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
    var errorFlash by remember { mutableStateOf(false) }
    val errorShakeDp = remember { Animatable(0f) }
    val animatedOverpull by animateFloatAsState(
        targetValue = overpull,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard),
        label = "singCapsuleOverpull",
    )
    val latestSing by rememberUpdatedState(sing)
    val latestHaptics by rememberUpdatedState(haptics)

    LaunchedEffect(sing.failureTick) {
        if (sing.failureTick <= 0) return@LaunchedEffect
        latestHaptics.performReject()
        errorFlash = true
        try {
            errorShakeDp.snapTo(6f)
            errorShakeDp.animateTo(0f, animationSpec = YoinMotion.mascotShakeSpring())
            delay(220L)
        } finally {
            errorFlash = false
        }
    }

    val thresholdPx = with(density) { 28.dp.toPx() }
    val micZonePx = with(density) { 34.dp.toPx() }
    val slopPx = with(density) { 5.dp.toPx() }
    val gesturePadRightPx = with(density) { 16.dp.toPx() }
    val gesturePadVerticalPx = with(density) { 8.dp.toPx() }
    val dismissState = remember(thresholdPx) { SingCapsuleDismissState(thresholdPx) }

    val shape = RoundedCornerShape(animatedCorner)
    // Match LyricsActionIcon (`primaryContainer`) when compact, and RatingSlider (`surfaceVariant` track
    // vs `primary` fill) when expanded so the vocal fill has crisp, unmistakable contrast across every cover.
    val containerColor by animateColorAsState(
        targetValue = when {
            isActive -> MaterialTheme.colorScheme.surfaceVariant
            errorFlash -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Standard),
        label = "singContainerColor",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            isActive -> MaterialTheme.colorScheme.onSurfaceVariant
            errorFlash -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onPrimaryContainer
        },
        animationSpec = YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Standard),
        label = "singContentColor",
    )
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
                    val overpullPx = if (isActive) (-animatedOverpull * 6.dp.toPx()) else 0f
                    val shakePx = errorShakeDp.value.dp.toPx()
                    IntOffset((overpullPx + shakePx).roundToInt(), 0)
                }
                .size(width = animatedWidth, height = iconSize)
                .then(
                    if (isActive) {
                        // Extend exclusion 16dp past the right edge (and 8dp vertically) so drags
                        // starting just outside the capsule never trigger Android's edge back gesture.
                        Modifier.systemGestureExclusion { coords ->
                            Rect(
                                left = 0f,
                                top = -gesturePadVerticalPx,
                                right = coords.size.width + gesturePadRightPx,
                                bottom = coords.size.height + gesturePadVerticalPx,
                            )
                        }
                    } else {
                        Modifier
                    },
                )
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
                // Plain flat-edged rectangle clipped cleanly by the outer capsule — disappears cleanly at 0%.
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(shownFraction.coerceIn(0f, 1f))
                        .background(fillColor),
                )
                // Subtle 12% sweet-spot detent notch
                val notchColor = if (shownFraction >= detentFraction) {
                    onFillColor.copy(alpha = 0.48f)
                } else {
                    contentColor.copy(alpha = 0.36f)
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
                // Left mic glyph with dual-tone clip over the flat fill edge
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
                            val fillW = capsuleWidthPx * shownFraction.coerceIn(0f, 1f)
                            if (fillW <= 0f) return@drawWithContent
                            clipRect(left = 0f, top = 0f, right = fillW.coerceAtMost(size.width), bottom = size.height) {
                                this@drawWithContent.drawContent()
                            }
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
                AnimatedContent(
                    targetState = isPreparing,
                    transitionSpec = {
                        YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                            YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                    },
                    contentAlignment = Alignment.Center,
                    label = "singKeyContent",
                ) { preparing ->
                    if (preparing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = contentColor,
                        )
                    } else {
                        Icon(
                            imageVector = SingIcons.Mic,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * In-place morph for [NowPlayingRatingBar] when Sing is active and the Lyrics action bar capsule is not
 * visible (Phone Compact, or Tablet Dual-Pane switched away from Lyrics). Supports both [Orientation.Vertical]
 * (rating column) and [Orientation.Horizontal] (stacked landscape bar).
 *
 * Fill is a flat-edged rectangle clipped by the outer [YoinShapeTokens.Full] capsule so pulling down to 0%
 * disappears cleanly with zero bowl/circle distortion. Zero percentage text.
 */
@Composable
internal fun SingVocalSlider(
    level: VocalLevel,
    onLevelChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    orientation: Orientation = Orientation.Vertical,
    /** Continuous 0..1 spatial-spring morph progress from RatingBar (0) to SingVocalSlider (1). */
    morphProgress: Float = 1f,
    /** RatingBar's current 0..1 fill fraction so the fill glides continuously on morph. */
    fromRatingFraction: Float = 0f,
    /** Optional frozen position used during exit morph so resetting 0% → 12% never flashes a fill. */
    overridePosition: Float? = null,
) {
    val isVertical = orientation == Orientation.Vertical
    val haptics = rememberYoinHaptics()
    val latestOnLevelChange by rememberUpdatedState(onLevelChange)
    val latestHaptics by rememberUpdatedState(haptics)
    var dragging by remember { mutableStateOf(false) }
    var trackDimensionPx by remember { mutableIntStateOf(1) }

    val targetFraction = (overridePosition ?: level.position).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = YoinMotion.spatialSpring(),
        label = "singVocalSliderFill",
    )
    val vocalFraction = if (dragging || overridePosition != null) targetFraction else animatedFraction
    val morph = morphProgress.coerceIn(0f, 1f)
    val shownFraction = (fromRatingFraction + (vocalFraction - fromRatingFraction) * morph).coerceIn(0f, 1f)
    val detentFraction = SingGainCurve.DEFAULT_POSITION
    val vocalLabel = stringResource(R.string.np_cd_sing_vocal_volume)

    Box(
        modifier = modifier
            .then(if (isVertical) Modifier.width(48.dp) else Modifier.fillMaxWidth().height(48.dp))
            .systemGestureExclusion()
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
        // Plain flat-edged rectangle clipped by the outer capsule — zero circle/bowl distortion near 0%.
        Box(
            modifier = (
                if (isVertical) {
                    Modifier.fillMaxWidth().fillMaxHeight(shownFraction)
                } else {
                    Modifier.fillMaxHeight().fillMaxWidth(shownFraction)
                }
                )
                .background(MaterialTheme.colorScheme.primary),
        )

        // Subtle 12% detent notch (glides in smoothly with morph)
        val notchColor = if (shownFraction >= detentFraction) {
            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.48f * morph)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f * morph)
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

        // Dual-tone mic icon clipped along the flat live fill edge, gliding in on morph
        val iconAlign = if (isVertical) Alignment.BottomCenter else Alignment.CenterStart
        val iconPadding = if (isVertical) Modifier.padding(bottom = 12.dp) else Modifier.padding(start = 14.dp)
        val iconMorphModifier = Modifier.graphicsLayer {
            alpha = morph
            val s = 0.72f + 0.28f * morph
            scaleX = s
            scaleY = s
            val slide = (1f - morph) * 12.dp.toPx()
            if (isVertical) translationY = slide else translationX = -slide
        }
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = SingIcons.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(iconAlign)
                    .then(iconPadding)
                    .then(iconMorphModifier)
                    .size(20.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {}
                    .drawWithContent {
                        if (shownFraction <= 0f) return@drawWithContent
                        val left = if (!isVertical && layoutDirection == LayoutDirection.Rtl) {
                            size.width * (1f - shownFraction)
                        } else {
                            0f
                        }
                        val right = if (!isVertical && layoutDirection != LayoutDirection.Rtl) {
                            size.width * shownFraction
                        } else {
                            size.width
                        }
                        val top = if (isVertical) size.height * (1f - shownFraction) else 0f
                        val bottom = size.height
                        clipRect(left = left, top = top, right = right, bottom = bottom) {
                            this@drawWithContent.drawContent()
                        }
                    },
            ) {
                Icon(
                    imageVector = SingIcons.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(iconAlign)
                        .then(iconPadding)
                        .then(iconMorphModifier)
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
        if (isPreparing) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            Icon(
                imageVector = SingIcons.MicOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Spatial-spring in-place morph between [NowPlayingRatingBar] and [SingVocalSlider].
 * The capsule track never blinks or pops: its colored fill physically glides along
 * [YoinMotion.spatialSpring] from [ratingFraction] to the live vocal level while the mic glyph
 * slides smoothly into place.
 */
@Composable
internal fun SingAwareRatingBarSlot(
    showSing: Boolean,
    sing: NowPlayingSingUi?,
    ratingFraction: Float,
    orientation: Orientation = Orientation.Vertical,
    modifier: Modifier = Modifier,
    ratingBar: @Composable () -> Unit,
) {
    val active = showSing && sing != null
    // Snapshot the live vocal level while active so exiting at 0% (which resets remembered level to 12%
    // for the next session) morphs cleanly from 0% → rating without flashing a ~15% fill at the bottom.
    var lastActivePosition by remember { mutableFloatStateOf(sing?.vocalLevel?.position ?: SingGainCurve.DEFAULT_POSITION) }
    if (active && sing != null) {
        lastActivePosition = sing.vocalLevel.position
    }
    val morph by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = YoinMotion.spatialSpring(),
        label = "singRatingMorphProgress",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Keep the real RatingBar mounted and interactive when at rest (morph <= 0.01f).
        if (morph < 0.99f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = (1f - morph * 1.4f).coerceIn(0f, 1f)
                    },
            ) {
                ratingBar()
            }
        }
        // Continuous morph overlay + active SingVocalSlider: glides fill smoothly from ratingFraction ↔ vocalLevel.
        if (morph > 0.01f && sing != null) {
            SingVocalSlider(
                level = sing.vocalLevel,
                onLevelChange = sing.onSetVocalLevel,
                orientation = orientation,
                morphProgress = morph,
                fromRatingFraction = ratingFraction,
                overridePosition = if (active) null else lastActivePosition,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Restrained spatial-spring in-place morph between the Favorite heart circle and [SingExitButton].
 * Keeps a steady `44.dp` circle with no extra bounce or shape wobble: background color blends
 * continuously on [YoinMotion.spatialSpring] while the heart and `MicOff` glyphs cross-scale cleanly.
 */
@Composable
internal fun SingAwareFavoriteSlot(
    showSing: Boolean,
    sing: NowPlayingSingUi?,
    isStarred: Boolean = false,
    modifier: Modifier = Modifier,
    favoriteButtons: @Composable () -> Unit,
) {
    val active = showSing && sing != null
    val morph by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = YoinMotion.spatialSpring(),
        label = "singFavoriteMorphProgress",
    )
    val clamped = morph.coerceIn(0f, 1f)
    val heartBg = if (isStarred) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val blendedBg = lerp(heartBg, MaterialTheme.colorScheme.primaryContainer, clamped)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (clamped <= 0.01f) {
            favoriteButtons()
        } else if (clamped >= 0.99f && sing != null) {
            SingExitButton(
                onExit = sing.onExit,
                isPreparing = sing.isPreparing,
            )
        } else {
            // Mid-morph: one steady 44dp circle whose fill color blends continuously while icons swap cleanly.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(blendedBg),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.graphicsLayer {
                        val s = (1f - 0.18f * clamped).coerceAtLeast(0.8f)
                        scaleX = s
                        scaleY = s
                        alpha = (1f - clamped * 1.4f).coerceIn(0f, 1f)
                    },
                ) {
                    favoriteButtons()
                }
                if (sing != null) {
                    Icon(
                        imageVector = SingIcons.MicOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer {
                                val s = (0.82f + 0.18f * clamped).coerceIn(0.8f, 1f)
                                scaleX = s
                                scaleY = s
                                alpha = ((clamped - 0.15f) / 0.85f).coerceIn(0f, 1f)
                            },
                    )
                }
            }
        }
    }
}
