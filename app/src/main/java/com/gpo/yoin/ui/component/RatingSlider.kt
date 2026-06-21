package com.gpo.yoin.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
 * @param rating current rating 0.0–10.0
 * @param onRatingChange called with the new rating (step 0.1)
 * @param orientation track axis; [Orientation.Vertical] fills from the bottom,
 *   [Orientation.Horizontal] fills from the start (left)
 */
@Composable
fun RatingSlider(
    rating: Float,
    onRatingChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    orientation: Orientation = Orientation.Vertical,
) {
    val isVertical = orientation == Orientation.Vertical
    val animatedFraction by animateFloatAsState(
        targetValue = (rating / 10f).coerceIn(0f, 1f),
        animationSpec = YoinMotion.spatialSpring(),
        label = "ratingFill",
    )

    // The track's length along its fill axis (height when vertical, width when
    // horizontal). One holder; the axis is selected at the gesture/measure sites.
    var trackDimensionPx by remember { mutableIntStateOf(1) }

    fun snap(fraction: Float): Float = (fraction * 100).roundToInt().coerceIn(0, 100) / 10f

    Box(
        modifier = modifier
            .then(if (isVertical) Modifier.width(48.dp) else Modifier.fillMaxWidth().height(48.dp))
            .clip(YoinShapeTokens.Full)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged {
                trackDimensionPx = (if (isVertical) it.height else it.width).coerceAtLeast(1)
            }
            // One gesture handler for both tap and drag. We set the rating on the
            // initial DOWN (so a plain tap always registers) and CONSUME the down
            // plus every move — the Now Playing host wraps this whole screen in a
            // vertical `Modifier.draggable` (drag-to-dismiss, active in Compact and
            // on the fold). Two separate tap/drag detectors let that parent steal
            // the slider's vertical drag; claiming the pointer here keeps the
            // gesture local so the slider is actually adjustable.
            .pointerInput(orientation) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    fun applyAt(rawPos: Float) {
                        onRatingChange(snap(ratingFractionFrom(rawPos, trackDimensionPx, orientation)))
                    }
                    applyAt(if (isVertical) down.position.y else down.position.x)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        applyAt(if (isVertical) change.position.y else change.position.x)
                        change.consume()
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

        // Rating label inside the bar. Cross-fade the label color from
        // onSurfaceVariant → onPrimary as the primary fill reaches it: the label
        // sits in the last ~20% of the track, so the crossover starts once the
        // fill exceeds 80% (rating ≥ 4).
        val labelCrossoverT = ((animatedFraction - 0.80f) / 0.20f).coerceIn(0f, 1f)
        val labelTarget = lerp(
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.onPrimary,
            labelCrossoverT,
        )
        val labelColor by animateColorAsState(
            targetValue = labelTarget,
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "ratingLabelColor",
        )
        Text(
            text = formatRatingLabel(rating),
            style = MaterialTheme.typography.titleLarge.withTabularFigures(),
            color = labelColor,
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
 * Map a gesture coordinate to a 0..1 fill fraction. Vertical tracks fill from
 * the BOTTOM so the y position is inverted; horizontal tracks fill from the
 * START (left) so the x position is used directly.
 */
private fun ratingFractionFrom(position: Float, dimensionPx: Int, orientation: Orientation): Float =
    when (orientation) {
        Orientation.Vertical -> 1f - (position / dimensionPx)
        Orientation.Horizontal -> position / dimensionPx
    }

private fun formatRatingLabel(rating: Float): String {
    val clamped = rating.coerceIn(0f, 10f)
    val roundedTenths = (clamped * 10f).roundToInt()
    if (roundedTenths >= 100) return "10"
    return "%d.%d".format(roundedTenths / 10, roundedTenths % 10)
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
private fun RatingSliderEmptyPreview() {
    YoinTheme {
        RatingSlider(
            rating = 0f,
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
