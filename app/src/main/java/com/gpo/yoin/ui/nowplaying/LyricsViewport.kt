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

/**
 * Opens a band of [bandPx] at the top of the lyrics window WITHOUT moving a
 * line: this node's content window (and whatever rides on it, e.g. the edge
 * fade) starts below the band, while [lyricsWindowContentAboveBand] keeps the
 * list inside at its full height and screen position. The list's measured
 * size never changes, so nothing re-centres. Pair them, outer → inner:
 * `lyricsWindowBelowBand(b).<fade>.clipToBounds().lyricsWindowContentAboveBand(b)`.
 */
internal fun Modifier.lyricsWindowBelowBand(bandPx: () -> Int): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val height = constraints.maxHeight
    val band = bandPx().coerceIn(0, height)
    val placeable = measurable.measure(
        constraints.copy(minHeight = height - band, maxHeight = height - band),
    )
    layout(placeable.width, height) { placeable.place(0, band) }
}

/** Inner half of [lyricsWindowBelowBand]: content at the full height, drawn from above the window. */
internal fun Modifier.lyricsWindowContentAboveBand(bandPx: () -> Int): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val window = constraints.maxHeight
    val band = bandPx().coerceAtLeast(0)
    val placeable = measurable.measure(
        constraints.copy(minHeight = window + band, maxHeight = window + band),
    )
    layout(placeable.width, window) { placeable.place(0, -band) }
}
