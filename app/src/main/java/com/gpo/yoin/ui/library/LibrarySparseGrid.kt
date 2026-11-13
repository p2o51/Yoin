package com.gpo.yoin.ui.library

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// A short Library on a wide screen (owner 2026-11-12, 「内容少的资料库用更大的格子」):
// at phone-sized cells a handful of albums sit in one row with the rest of the
// page empty below. While the shelf can't fill the screen, the cells grow —
// fewer columns, never fewer than half the usual count (cells at most about
// twice the phone size) nor three — until the rows just fill the page. A
// shelf that fills it at the usual cells keeps them. The iPad grows its
// Library cells by the same rule.

/**
 * Columns for [itemCount] cells on a [width] × [height] page: [baseColumns] (the
 * adaptive count for the usual cells), or fewer while the rows don't fill
 * [height]. [cellExtra] is a cell's height past its square artwork (the
 * title lines and their gap), [leading] what sits above the first row (the
 * sort row), [rowGap] / [columnGap] the grid's spacing.
 */
internal fun librarySparseColumns(
    itemCount: Int,
    baseColumns: Int,
    width: Dp,
    height: Dp,
    cellExtra: Dp = LibrarySparseCellExtra,
    leading: Dp = 0.dp,
    rowGap: Dp = 16.dp,
    columnGap: Dp = 12.dp,
): Int {
    if (baseColumns <= LibrarySparseMinColumns || itemCount <= 0) return baseColumns
    val fewest = maxOf(LibrarySparseMinColumns, (baseColumns + 1) / 2)
    for (columns in fewest until baseColumns) {
        val cell = (width - columnGap * (columns - 1)) / columns
        val rows = (itemCount + columns - 1) / columns
        val content = leading + (cell + cellExtra) * rows + rowGap * (rows - 1)
        if (content <= height) return columns
    }
    return baseColumns
}

/**
 * The Library grid's cells past Compact: as many [minSize] cells as fit
 * (GridCells.Adaptive), grown by [librarySparseColumns] while [itemCount]
 * cells can't fill [viewportHeight].
 */
@Immutable
internal class LibrarySparseCells(
    private val minSize: Dp,
    private val itemCount: Int,
    private val viewportHeight: Dp,
    private val leading: Dp,
) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val base = maxOf((availableSize + spacing) / (minSize.roundToPx() + spacing), 1)
        val columns = librarySparseColumns(
            itemCount = itemCount,
            baseColumns = base,
            width = availableSize.toDp(),
            height = viewportHeight,
            leading = leading,
            columnGap = spacing.toDp(),
        )
        // Spread the remainder like GridCells.Fixed / Adaptive do.
        val gridSize = availableSize - spacing * (columns - 1)
        val slot = gridSize / columns
        val remaining = gridSize % columns
        return List(columns) { slot + if (it < remaining) 1 else 0 }
    }

    override fun equals(other: Any?): Boolean = other is LibrarySparseCells &&
        minSize == other.minSize && itemCount == other.itemCount &&
        viewportHeight == other.viewportHeight && leading == other.leading

    override fun hashCode(): Int =
        ((minSize.hashCode() * 31 + itemCount) * 31 + viewportHeight.hashCode()) * 31 + leading.hashCode()
}

/** Title + subtitle under a cell's artwork (5dp gap, titleSmall, a 12sp line). */
internal val LibrarySparseCellExtra = 44.dp

/** The sort row above the first cells, when it shows. */
internal val LibrarySparseSortRow = 48.dp

private const val LibrarySparseMinColumns = 3
