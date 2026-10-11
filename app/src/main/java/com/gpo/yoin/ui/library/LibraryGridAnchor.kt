@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.library

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch

/**
 * The item a Library grid brings back to its top after a width change
 * ([KeepGridAnchorAcrossWidthChanges], as a lazy index). A scroll records it
 * as it goes; a fast-scroller jump moves the list without scrolling it, so
 * the jump sets it itself ([libraryJumpAnchor]). Plain, not state: only the
 * width-change effect reads it.
 *
 * An exact anchor (the search surface's grids) also holds where in that row
 * the grid sat, [offset] px past its top, at the viewport [width] the scroll
 * left it at (0 until a scroll has), and how tall a row was then, [rowPx]
 * (a cell and the gap under it).
 */
internal class LibraryGridAnchor(var index: Int) {
    var offset: Int = 0
    var width: Int = 0
    var rowPx: Int = 0
}

/** Where a grid is, and whether a scroll is moving it. */
private data class GridPlace(
    val index: Int,
    val offset: Int,
    val width: Int,
    val rowPx: Int,
    val scrolling: Boolean
)

/** A row of [state]'s grid as laid out now, its gap included; 0 with nothing laid out. */
private fun rowPx(state: LazyGridState): Int {
    val info = state.layoutInfo
    val cell = info.visibleItemsInfo.firstOrNull() ?: return 0
    return cell.size.height + info.mainAxisItemSpacing
}

/**
 * The shell pane narrows when a split opens and widens again when it closes,
 * so a grid's column count changes under the user. LazyGrid then re-anchors
 * on the first item of the row holding its first visible item, drifting up to
 * a row on every narrow ↔ wide trip. This remembers the item the user scrolled
 * to (index changes seen while a scroll is in progress — relayouts happen
 * outside one) and brings its row back to the top after each width change.
 * A fast-scroller jump is no scroll: it moves the returned anchor itself
 * ([LibraryGridFastScroller]).
 *
 * [exact] is for the search surface's grids, whose width changes only while
 * the full-window search morphs to and from its pill (and on a rotation): the
 * grid passes through every width on the way and comes back to the one it
 * left. Back at that width it lands exactly where the user left it, offset
 * into the row and all, rather than with the row flush to the top; on the
 * way it keeps the same share of the row scrolled past, so the morph carries
 * the grid there instead of snapping it on its last frame. Pass an
 * [anchor] kept with the grid's state there: the search's content leaves
 * composition once it has folded to the pill, and an anchor remembered here
 * would come back seeded from the place the morph left, a row off.
 */
@Composable
internal fun KeepGridAnchorAcrossWidthChanges(
    state: LazyGridState,
    anchor: LibraryGridAnchor = remember(state) { LibraryGridAnchor(state.firstVisibleItemIndex) },
    exact: Boolean = false
): LibraryGridAnchor {
    LaunchedEffect(state, anchor, exact) {
        launch {
            if (exact) {
                // Each frame of a scroll, and the one it ends on: a fling's last
                // step can land after it stopped. A restore below is one frame's
                // snap, never seen as scrolling here; nor is a relayout, whose
                // width differs from the frame before.
                var scrolling = false
                var lastWidth = 0
                snapshotFlow {
                    GridPlace(
                        index = state.firstVisibleItemIndex,
                        offset = state.firstVisibleItemScrollOffset,
                        width = state.layoutInfo.viewportSize.width,
                        rowPx = rowPx(state),
                        scrolling = state.isScrollInProgress
                    )
                }.collect { place ->
                    if ((place.scrolling || scrolling) && place.width == lastWidth && place.width > 0) {
                        anchor.index = place.index
                        anchor.offset = place.offset
                        anchor.width = place.width
                        anchor.rowPx = place.rowPx
                    }
                    scrolling = place.scrolling
                    lastWidth = place.width
                }
            } else {
                snapshotFlow { state.firstVisibleItemIndex }
                    .collect { index -> if (state.isScrollInProgress) anchor.index = index }
            }
        }
        var lastWidth = 0
        snapshotFlow { state.layoutInfo.viewportSize.width }
            .collect { width ->
                if (exact && width > 0 && width == anchor.width) {
                    val there = state.firstVisibleItemIndex == anchor.index &&
                        state.firstVisibleItemScrollOffset == anchor.offset
                    if (!there) state.scrollToItem(anchor.index, anchor.offset)
                } else if (lastWidth > 0 && width > 0 && width != lastWidth) {
                    val row = rowPx(state)
                    val offset = if (exact && anchor.rowPx > 0 && row > 0) {
                        (anchor.offset.toLong() * row / anchor.rowPx).toInt()
                    } else {
                        0
                    }
                    state.scrollToItem(anchor.index, offset)
                }
                lastWidth = width
            }
    }
    return anchor
}
