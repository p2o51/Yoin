package com.gpo.yoin.ui.home

// Jump Back In on the two-column grid (owner 2026-11-27, 「三个这个摆成一排，零作用」): a half cell on a
// foldable showed nothing but memory 1×2s, one to a row. The VM hands the shelf over wide cards first (every
// memory and note the longer phone shelves can take), and the grid packed that list row-major with no
// signal budget: at three cover columns each 1×2 left one column the next 1×2 didn't fit, so every unit
// row was a 1×2 and a hole, and the covers never came. Here the grid seats it by the shelf's own rules:
// the signal budget of the phone and the templated panes (jbiSignalLimit, jbiSparseSignalLimit), never more
// than a quarter of the cells and one to a row, a single one in a half; each sits in its row's right-hand
// lane (its copy ends the row, as the templates prefer), the next on the left, spread down the rows
// (jbiSpreadRows); covers fill every other cell in reading order, so no row has a hole while covers last.

/**
 * Signal cards (rating / note 1×2s) Jump Back In takes on a grid of [columns] × [units] with [covers]
 * covers to hand: [jbiSignalLimit] and [jbiSparseSignalLimit], at most a quarter of the cells (a single
 * row of three or four is all covers), one to a row, and one in a [half] cell. None on fewer than three
 * columns: a 1×2 there would be the whole row. Pure.
 */
internal fun homeGridJbiSignalLimit(columns: Int, units: Int, covers: Int, half: Boolean): Int {
    if (columns < JbiGridSignalMinColumns || units < 1) return 0
    val cells = columns * units
    val limit = minOf(
        jbiSignalLimit(units, cells),
        jbiSparseSignalLimit(covers, columns),
        (cells - 1) / JbiGridCellsPerSignal,
        units,
    )
    return if (half) minOf(limit, 1) else limit
}

/**
 * [cards] (the VM's order: wide cards first, then covers by rank) seated on [columns] × [units] cells:
 * each [HomeGridCell.index] points into [cards]. The signals [homeGridJbiSignalLimit] allows, spread
 * down the rows ([jbiSpreadRows]) and alternating right-hand lane / left; covers row-major around them.
 * The others wait for a larger count. Pure.
 */
internal fun homeGridJbiSeat(cards: List<HomeWidgetCard>, columns: Int, units: Int, half: Boolean): List<HomeGridCell> {
    val cols = columns.coerceAtLeast(1)
    val rows = units.coerceAtLeast(1)
    val covers = cards.indices.filterNot { cards[it].expanded }
    val signalIndices = cards.indices.filter { cards[it].expanded }
    val count = minOf(signalIndices.size, homeGridJbiSignalLimit(cols, rows, covers.size, half))
    // A short shelf spreads its signals over the rows it fills, not over empty ones.
    val filled = ((count * JbiGridSignalSpan + covers.size + cols - 1) / cols).coerceIn(1, rows)
    val signalRows = jbiSpreadRows(count, filled)
    val taken = Array(rows) { BooleanArray(cols) }
    val out = ArrayList<HomeGridCell>()
    for (k in 0 until count) {
        val row = signalRows[k]
        val column = if (k % 2 == 0) cols - JbiGridSignalSpan else 0
        taken[row][column] = true
        taken[row][column + 1] = true
        out += HomeGridCell(signalIndices[k], row, column, JbiGridSignalSpan)
    }
    val next = covers.iterator()
    for (r in 0 until rows) for (c in 0 until cols) {
        if (taken[r][c] || !next.hasNext()) continue
        out += HomeGridCell(next.next(), r, c, 1)
    }
    return out.sortedWith(compareBy({ it.row }, { it.column }))
}

private const val JbiGridSignalSpan = 2
private const val JbiGridSignalMinColumns = 3
private const val JbiGridCellsPerSignal = 4
