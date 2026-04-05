package com.gpo.yoin.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.roundToInt

/**
 * Vertical rating slider — drag up to increase, down to decrease.
 *
 * @param rating current rating 0.0–5.0
 * @param onRatingChange called with the new rating (step 0.1)
 */
@Composable
fun RatingSlider(
    rating: Float,
    onRatingChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val animatedFraction by animateFloatAsState(
        targetValue = (rating / 5f).coerceIn(0f, 1f),
        animationSpec = YoinMotion.spatialSpring(),
        label = "ratingFill",
    )

    var trackHeightPx by remember { mutableIntStateOf(1) }
    var dragRating by remember { mutableFloatStateOf(rating) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Rating label
        Text(
            text = "%.1f".format(rating),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Vertical bar track
        Box(
            modifier = Modifier
                .width(14.dp)
                .weight(1f)
                .clip(YoinShapeTokens.Full)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .onSizeChanged { trackHeightPx = it.height.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val fraction = 1f - (offset.y / trackHeightPx)
                        val snapped = (fraction * 50).roundToInt()
                            .coerceIn(0, 50) / 10f
                        onRatingChange(snapped)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val fraction = 1f - (offset.y / trackHeightPx)
                            dragRating = (fraction * 50).roundToInt()
                                .coerceIn(0, 50) / 10f
                            onRatingChange(dragRating)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val fraction =
                                1f - (change.position.y / trackHeightPx)
                            dragRating = (fraction * 50).roundToInt()
                                .coerceIn(0, 50) / 10f
                            onRatingChange(dragRating)
                        },
                    )
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            // Filled portion
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(animatedFraction)
                    .clip(YoinShapeTokens.Full)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
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
