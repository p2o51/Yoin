@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.library

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.FastScrollMath
import com.gpo.yoin.ui.component.FastScrollSection
import com.gpo.yoin.ui.component.YoinFastScroller

/** The Library grids' fast scroller, for tests to find. */
internal const val LIBRARY_FAST_SCROLLER_TAG = "library.fastScroller"

// The grids' top content padding: the handle's top end meets the first row.
private val LibraryScrollerTopInset = 8.dp

/**
 * The item a Library grid brings back to its top after a width change
 * (KeepGridAnchorAcrossWidthChanges, as a lazy index). A scroll records it
 * as it goes; a fast-scroller jump moves the list without scrolling it, so
 * the jump sets it itself ([libraryJumpAnchor]). Plain, not state: only the
 * width-change effect reads it.
 */
internal class LibraryGridAnchor(var index: Int)

/**
 * The anchor for a jump to lazy index [jumped], the first item of a row
 * (leading full-span rows counted, [leadingItems]): the section that starts
 * on that row — the one the bubble names there, the last — else the row
 * itself. A width change then keeps that section's first item in the top
 * row, however the new columns break the rows.
 */
internal fun libraryJumpAnchor(
    jumped: Int,
    leadingItems: Int,
    itemsPerLine: Int,
    sections: List<FastScrollSection>
): Int {
    val rowStart = jumped - leadingItems
    if (rowStart < 0) return jumped
    val rowEnd = rowStart + itemsPerLine.coerceAtLeast(1) - 1
    val section = sections.getOrNull(FastScrollMath.sectionAt(sections, rowEnd)) ?: return jumped
    return if (section.startIndex >= rowStart) section.startIndex + leadingItems else jumped
}

/**
 * [YoinFastScroller] over a Library grid (Artists, Albums, All). Place it
 * over the grid in the grid's own Box (`matchParentSize()`), not inside the
 * grid's seamDissolveViewport: a jump is no scroll, so the seam stays still.
 *
 * Its track runs from the grid's first row ([LibraryScrollerTopInset]) to
 * [bottomInset] above the grid's bottom (the floating bar's clearance), and
 * reaches [endOverhang] past the grid's end edge, so the handle hugs the
 * page's own edge across the page gutter — the shell column's edge in a
 * Wide split, never into the 24dp gap beside it.
 *
 * @param leadingItems full-span rows before the first item (the sort row).
 */
@Composable
internal fun LibraryGridFastScroller(
    state: LazyGridState,
    sections: List<FastScrollSection>,
    leadingItems: Int,
    anchor: LibraryGridAnchor,
    endOverhang: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier
) {
    val currentSections by rememberUpdatedState(sections)
    val currentLeading by rememberUpdatedState(leadingItems)
    YoinFastScroller(
        state = state,
        sections = sections,
        leadingItems = leadingItems,
        onJumped = { jumped ->
            anchor.index = libraryJumpAnchor(
                jumped = jumped,
                leadingItems = currentLeading,
                itemsPerLine = state.layoutInfo.maxSpan,
                sections = currentSections
            )
        },
        modifier = modifier
            .padding(top = LibraryScrollerTopInset, bottom = bottomInset)
            .overhangEnd(endOverhang)
            .testTag(LIBRARY_FAST_SCROLLER_TAG)
    )
}

/**
 * Lays the content out [overhang] wider than this node, the extra past its
 * end edge (the left one in RTL): nothing on the way clips it, and touches
 * there still reach it.
 */
private fun Modifier.overhangEnd(overhang: Dp): Modifier = if (overhang <= 0.dp) {
    this
} else {
    layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
        val width = constraints.maxWidth
        val wide = width + overhang.roundToPx()
        val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
        layout(width, placeable.height) { placeable.placeRelative(0, 0) }
    }
}
