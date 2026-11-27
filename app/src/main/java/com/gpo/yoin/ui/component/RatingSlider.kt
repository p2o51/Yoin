package com.gpo.yoin.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures
import kotlin.math.roundToInt

/**
 * Rating slider. Vertical (default): drag up to increase. Horizontal: drag
 * right to increase. The rating label is displayed inside the bar. Both
 * orientations share the exact same fill spring, label crossfade and rating
 * conversion — only the layout axis + gesture coordinate differ.
 *
 * @param rating current rating 0.0–10.0, or null when not rated: the bar is
 *   empty and shows no number (a 0.0 shows "0.0"). The first tap or drag
 *   sets a score through [onRatingChange], as on any other value.
 * @param onRatingChange called with the new rating (step 0.1)
 * @param orientation track axis; [Orientation.Vertical] fills from the bottom,
 *   [Orientation.Horizontal] fills from the start (left)
 * @param onInteractionChange true when a finger lands on the bar, false when
 *   it lifts — where a caller commits the value (the album rate sheet).
 * @param claimOnDown true (Now Playing): the bar takes the pointer on DOWN, so
 *   a parent's drag can't steal it. False (a sheet that scrolls or drags away):
 *   the bar waits for a drag along its own axis past touch slop, or a tap; a
 *   swipe across it goes to the parent and leaves the rating alone.
 * @param gestureKey the rated item's identity. Changing it cancels the old
 *   press without committing it; callers should reset their draft/held state
 *   for the new item. Fill and label animations keep their existing state.
 * @param unratedHint horizontal only: shown at the track's start while unrated,
 *   so an empty bar reads as a control. Fades out as a first score lands.
 */
@Composable
fun RatingSlider(
    rating: Float?,
    onRatingChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    orientation: Orientation = Orientation.Vertical,
    onInteractionChange: (active: Boolean) -> Unit = {},
    claimOnDown: Boolean = true,
    gestureKey: Any? = null,
    unratedHint: String? = null,
) {
    val isVertical = orientation == Orientation.Vertical
    val haptics = rememberYoinHaptics()
    val label = ratingLabel(rating)
    val ratingState = label?.let { stringResource(R.string.cmp_rating_state, it) }
        ?: stringResource(R.string.cmp_rating_state_unrated)
    // An item's gesture outlives recompositions: read the callers' latest
    // lambdas, not the first ones.
    val latestOnRatingChange by rememberUpdatedState(onRatingChange)
    val latestOnInteractionChange by rememberUpdatedState(onInteractionChange)
    val latestGestureKey by rememberUpdatedState(gestureKey)
    val animatedFraction by animateFloatAsState(
        targetValue = ratingFillFraction(rating),
        animationSpec = YoinMotion.spatialSpring(),
        label = "ratingFill",
    )

    // The track's length along its fill axis (height when vertical, width when
    // horizontal). One holder; the axis is selected at the gesture/measure sites.
    var trackDimensionPx by remember { mutableIntStateOf(1) }
    // A finger on the bar: a short bar shows its number only then (below).
    var held by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    // Too short for the number to sit clear of the fill (a short window's bar
    // at a high score printed 8.4 on the fill itself): the number only shows
    // while the finger sets it.
    // Not measured yet (1): assume room, so a long bar's number doesn't fade in on open.
    val labelRoom = trackDimensionPx <= 1 || with(density) { ratingLabelFits(trackDimensionPx.toDp(), isVertical) }

    fun snap(fraction: Float): Float = (fraction * 100).roundToInt().coerceIn(0, 100) / 10f

    Box(
        modifier = modifier
            .then(if (isVertical) Modifier.width(48.dp) else Modifier.fillMaxWidth().height(48.dp))
            .clip(YoinShapeTokens.Full)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged {
                trackDimensionPx = (if (isVertical) it.height else it.width).coerceAtLeast(1)
            }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = (rating ?: 0f).coerceIn(0f, 10f),
                    range = 0f..10f,
                    // 0.1-step control: 99 discrete values between the endpoints.
                    steps = 99,
                )
                stateDescription = ratingState
            }
            // One gesture handler for both tap and drag. We set the rating on the
            // initial DOWN (so a plain tap always registers) and CONSUME the down
            // plus every move — the Now Playing host wraps this whole screen in a
            // vertical `Modifier.draggable` (drag-to-dismiss, active in Compact and
            // on the fold). Two separate tap/drag detectors let that parent steal
            // the slider's vertical drag; claiming the pointer here keeps the
            // gesture local so the slider is actually adjustable.
            .pointerInput(orientation, claimOnDown, gestureKey) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // The first position sets the baseline silently; each 0.1
                    // step the drag crosses afterwards ticks.
                    var lastSnapped = Float.NaN
                    fun applyAt(rawPos: Float) {
                        val snapped = snap(ratingFractionFrom(rawPos, trackDimensionPx, orientation))
                        if (!lastSnapped.isNaN() && snapped != lastSnapped) {
                            haptics.performTick()
                        }
                        lastSnapped = snapped
                        latestOnRatingChange(snapped)
                    }
                    fun along(change: PointerInputChange): Float = if (isVertical) change.position.y else change.position.x
                    if (claimOnDown) {
                        down.consume()
                        held = true
                        latestOnInteractionChange(true)
                        applyAt(along(down))
                    } else {
                        val start = if (isVertical) {
                            awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        } else {
                            awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        }
                        if (start == null) {
                            // Lifted before any drag: a tap, which still rates. Taken
                            // by the parent (a scroll, the sheet's drag): not ours.
                            val up = currentEvent.changes.firstOrNull { it.id == down.id }
                            if (up != null && !up.pressed && !up.isConsumed) {
                                up.consume()
                                latestOnInteractionChange(true)
                                try {
                                    applyAt(along(up))
                                } finally {
                                    held = false
                                    latestOnInteractionChange(false)
                                }
                            }
                            return@awaitEachGesture
                        }
                        held = true
                        latestOnInteractionChange(true)
                        applyAt(along(start))
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                // UP may carry movement that had no preceding MOVE.
                                applyAt(along(change))
                                change.consume()
                                break
                            }
                            applyAt(along(change))
                            change.consume()
                        }
                    } finally {
                        held = false
                        // A replaced item's pointer coroutine is cancelled after
                        // recomposition: the latest callback already belongs to
                        // the new item and must not receive the old press's commit.
                        if (latestGestureKey == gestureKey) latestOnInteractionChange(false)
                    }
                }
            },
        contentAlignment = if (isVertical) Alignment.BottomCenter else Alignment.CenterStart,
    ) {
        // Filled portion grows along the fill axis.
        Box(
            modifier = (
                if (isVertical) {
                    Modifier.fillMaxWidth().fillMaxHeight(animatedFraction)
                } else {
                    Modifier.fillMaxHeight().fillMaxWidth(animatedFraction)
                }
                )
                .clip(YoinShapeTokens.Full)
                .background(MaterialTheme.colorScheme.primary),
        )

        // Unrated: no number. The last one fades out (and a first score fades
        // in) on the effects spring instead of popping.
        val lastLabel = remember { mutableStateOf("") }
        SideEffect { if (label != null) lastLabel.value = label }
        val labelAlpha by animateFloatAsState(
            targetValue = if (label != null && (labelRoom || held)) 1f else 0f,
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "ratingLabelAlpha",
        )
        // Horizontal and unrated: a hint at the start, so the empty track reads as something to set.
        if (unratedHint != null && !isVertical) {
            Text(
                text = unratedHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 1f - labelAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 20.dp)
                    .clearAndSetSemantics {},
            )
        }
        // The number inside the bar: onSurfaceVariant over the track and
        // onPrimary where the fill runs behind it, split along the fill's own
        // edge (rounded end included). Crossing the color by the score alone
        // (80 → 100 %) left 8.6 on a short bar half covered by a fill the
        // number didn't follow.
        val shownLabel = label ?: lastLabel.value
        val fillShape = YoinShapeTokens.Full
        RatingLabel(
            text = shownLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = labelAlpha),
            isVertical = isVertical,
        )
        RatingLabel(
            text = shownLabel,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = labelAlpha),
            isVertical = isVertical,
            // The same number again: one is enough for TalkBack.
            modifier = Modifier.clearAndSetSemantics {}.drawWithContent {
                // Read in draw: the fill's spring redraws the split, no recomposition.
                val fraction = animatedFraction.coerceIn(0f, 1f)
                if (fraction <= 0f) return@drawWithContent
                val fillSize = if (isVertical) {
                    Size(size.width, size.height * fraction)
                } else {
                    Size(size.width * fraction, size.height)
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
        )
    }
}

/**
 * The bar's number, laid out over the whole bar so both colored copies (and
 * the fill's clip) share the bar's own coordinates.
 */
@Composable
private fun BoxScope.RatingLabel(
    text: String,
    color: Color,
    isVertical: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = Modifier.matchParentSize().then(modifier)) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge.withTabularFigures(),
            color = color,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .align(if (isVertical) Alignment.TopCenter else Alignment.CenterEnd)
                .widthIn(min = 36.dp)
                .then(if (isVertical) Modifier.padding(top = 8.dp) else Modifier.padding(end = 12.dp)),
        )
    }
}

/**
 * Whether a bar [length] long along its fill axis has room for its number at rest: at least
 * [RatingLabelMinVertical] tall standing, [RatingLabelMinHorizontal] wide lying. Shorter bars show the
 * number only while a finger is on them (the review's 8.4 printed over the fill, 2026-11-27).
 */
internal fun ratingLabelFits(length: Dp, vertical: Boolean): Boolean =
    length >= if (vertical) RatingLabelMinVertical else RatingLabelMinHorizontal

internal val RatingLabelMinVertical = 160.dp
internal val RatingLabelMinHorizontal = 120.dp

/**
 * Map a gesture coordinate to a 0..1 fill fraction. Vertical tracks fill from
 * the BOTTOM so the y position is inverted; horizontal tracks fill from the
 * START (left) so the x position is used directly.
 */
private fun ratingFractionFrom(position: Float, dimensionPx: Int, orientation: Orientation): Float =
    when (orientation) {
        Orientation.Vertical -> 1f - (position / dimensionPx)
        Orientation.Horizontal -> position / dimensionPx
    }

// ── Previews ────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun RatingSliderPreview() {
    YoinTheme {
        RatingSlider(
            rating = 3.7f,
            onRatingChange = {},
            modifier = Modifier.height(200.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun RatingSliderZeroPreview() {
    YoinTheme {
        RatingSlider(
            rating = 0f,
            onRatingChange = {},
            modifier = Modifier.height(200.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun RatingSliderUnratedPreview() {
    YoinTheme {
        RatingSlider(
            rating = null,
            onRatingChange = {},
            modifier = Modifier.height(200.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F, widthDp = 320)
@Composable
private fun RatingSliderHorizontalPreview() {
    YoinTheme {
        RatingSlider(
            rating = 3.7f,
            onRatingChange = {},
            modifier = Modifier.width(280.dp),
            orientation = Orientation.Horizontal,
        )
    }
}
