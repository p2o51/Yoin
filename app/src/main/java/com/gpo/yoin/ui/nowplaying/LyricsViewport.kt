package com.gpo.yoin.ui.nowplaying

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

internal const val LyricsFocusFraction = 0.38f

/**
 * Apply to a wrapper around the list, so the changing measure policy does not
 * invalidate the list node itself. Measure at the expanded height throughout
 * the stage reshape. Only
 * its visible window and placement change. This avoids remeasuring/recentering
 * the lazy list after every animation frame, while preserving the active line's
 * position at [LyricsFocusFraction] of the visible viewport.
 */
internal fun Modifier.lyricsViewport(
    growthPx: Int,
    focusFraction: Float = LyricsFocusFraction,
): Modifier =
    clipToBounds().layout { measurable, constraints ->
        if (!constraints.hasBoundedHeight) {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        } else {
            val height = constraints.maxHeight
            val expandedHeight = (height + growthPx).coerceAtLeast(height)
            val placeable = measurable.measure(
                constraints.copy(minHeight = expandedHeight, maxHeight = expandedHeight),
            )
            layout(placeable.width, height) {
                placeable.placeWithLayer(
                    x = 0,
                    y = ((height - expandedHeight) * focusFraction).roundToInt(),
                )
            }
        }
    }
