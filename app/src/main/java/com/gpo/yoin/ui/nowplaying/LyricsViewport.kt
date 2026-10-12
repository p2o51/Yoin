package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Where the line being sung sits on the expanded lyric page: its row's CENTRE
 * at this share of the clean band, the part of the window between the two edge
 * fades. With the fades equal that is the window's middle, on a ~600dp portrait
 * page and a ~227dp landscape phone page alike.
 */
internal const val LyricsFocusFraction = 0.5f

/** Each edge fade of the expanded lyric page, at most. */
internal val LyricsEdgeFade = 64.dp

/**
 * …and never more than this share of the window per edge, so a short window
 * (a landscape phone's ~227dp page) keeps a clean middle for a wrapped line
 * and its translation.
 */
internal val LyricsEdgeFadeMaxFraction = 0.2f

/** The edge fade a [viewportPx]-tall lyric window draws, for a full fade of [fadePx]. */
internal fun lyricsEdgeFadePx(viewportPx: Int, fadePx: Int): Int =
    minOf(fadePx, (viewportPx * LyricsEdgeFadeMaxFraction).roundToInt()).coerceAtLeast(0)

/**
 * The `scrollOffset` for `LazyListState.scrollToItem` that seats a [rowPx]-tall
 * focus row: its centre at [focusFraction] of the clean band between
 * [fadeTopPx] and the bottom fade, the whole row inside the band. A row taller
 * than the band starts at the band's top, so its first line reads crisp and
 * the rest runs into the bottom fade.
 *
 * Measured from the list's content start, which sits [contentPaddingTopPx]
 * below the window's top (a scroll offset of 0 puts the row's top there).
 */
internal fun lyricsFocusScrollOffsetPx(
    viewportPx: Int,
    contentPaddingTopPx: Int,
    rowPx: Int,
    fadeTopPx: Int,
    fadeBottomPx: Int,
    focusFraction: Float = LyricsFocusFraction
): Int {
    val bandTop = fadeTopPx
    val bandBottom = (viewportPx - fadeBottomPx).coerceAtLeast(bandTop)
    val rowTop = if (rowPx >= bandBottom - bandTop) {
        bandTop
    } else {
        (bandTop + (bandBottom - bandTop) * focusFraction - rowPx / 2f)
            .roundToInt()
            .coerceIn(bandTop, bandBottom - rowPx)
    }
    return contentPaddingTopPx - rowTop
}

/**
 * [lyricsFocusScrollOffsetPx] for item [target] of this layout, with edge fades
 * of [fadePx] at most. A row that isn't laid out yet is sized like the lyric
 * rows that are (their mean), so the caller seats it again once it is
 * ([lyricsFocusSeatRemainderPx]).
 */
internal fun LazyListLayoutInfo.lyricsFocusScrollOffsetPx(target: Int, fadePx: Int): Int {
    val viewport = viewportSize.height
    val fade = lyricsEdgeFadePx(viewport, fadePx)
    val rowPx = visibleItemsInfo.firstOrNull { it.index == target }?.size
        ?: visibleItemsInfo.filter { it.key is Int }.takeIf { it.isNotEmpty() }?.let { rows ->
            rows.sumOf { it.size } / rows.size
        }
        ?: 0
    return lyricsFocusScrollOffsetPx(
        viewportPx = viewport,
        contentPaddingTopPx = -viewportStartOffset,
        rowPx = rowPx,
        fadeTopPx = fade,
        fadeBottomPx = fade
    )
}

/**
 * How far item [target] still has to scroll (px, + = forward) to sit on its
 * focus seat, by its laid-out size; null while it isn't laid out.
 */
internal fun LazyListLayoutInfo.lyricsFocusSeatRemainderPx(target: Int, fadePx: Int): Int? {
    val row = visibleItemsInfo.firstOrNull { it.index == target } ?: return null
    // A scroll offset of s leaves the row's offset at -s.
    return lyricsFocusScrollOffsetPx(target, fadePx) + row.offset
}

/**
 * Apply to a wrapper around the list, so the changing measure policy does not
 * invalidate the list node itself. Measure at the expanded height throughout
 * the stage reshape. Only
 * its visible window and placement change. This avoids remeasuring/recentering
 * the lazy list after every animation frame, while keeping the active line at
 * [focusFraction] of the visible window. Pass the share of the window the
 * focus seat sits at: with equal edge fades and a seat in the middle of the
 * band between them ([lyricsFocusScrollOffsetPx]), that is
 * [LyricsFocusFraction] at any window height.
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
