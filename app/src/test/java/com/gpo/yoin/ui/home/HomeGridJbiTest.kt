package com.gpo.yoin.ui.home

import com.gpo.yoin.ui.memories.MemoryEntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jump Back In on the two-column grid (owner 2026-11-27: a half cell on the Fold showed three memory 1×2s
 * stacked and no covers). A half shows mostly covers and one 1×2 at most; a full row keeps the shelf's
 * signal budget; no row has a hole while covers last.
 */
class HomeGridJbiTest {

    private fun cover(i: Int) = HomeWidgetCard(
        stableId = "c$i",
        entityType = MemoryEntityType.ALBUM,
        title = "Cover $i",
        subtitle = "Artist",
        coverArtUrl = null,
        target = HomeWidgetTarget.AlbumDetail("subsonic:c$i"),
    )

    private fun signal(i: Int) = HomeWidgetCard(
        stableId = "s$i",
        entityType = MemoryEntityType.ALBUM,
        title = "Signal $i",
        subtitle = "Artist",
        coverArtUrl = null,
        ratingText = "8.0",
        comment = "Note $i",
        expanded = true,
        target = HomeWidgetTarget.MemoryFocus(i.toLong()),
    )

    /** The VM's order: every wide card first (the longer shelves' extras too), then covers. */
    private fun shelf(signals: Int = 6, covers: Int = 40) = (1..signals).map(::signal) + (1..covers).map(::cover)

    private fun List<HomeGridCell>.signals(cards: List<HomeWidgetCard>) = count { cards[it.index].expanded }

    private fun assertNoHoles(cells: List<HomeGridCell>, columns: Int, units: Int) {
        val taken = Array(units) { BooleanArray(columns) }
        cells.forEach { cell ->
            for (c in cell.column until cell.column + cell.span) {
                assertTrue("overlap at ${cell.row},$c", !taken[cell.row][c])
                taken[cell.row][c] = true
            }
        }
        taken.forEachIndexed { r, row -> assertTrue("hole in row $r: ${row.toList()}", row.all { it }) }
    }

    // Halves: Fold inner (880dp → 398dp halves; the owner's ~410), the 840dp boundary (378dp), Pixel Tablet
    // landscape (1280dp → 598dp halves).
    private val halves = listOf(378f, 398f, 410f, 598f)

    // Full rows of the same windows' content.
    private val fullRows = listOf(776f, 816f, 1216f)

    @Test
    fun should_seatOneSignalAtMostAndMostlyCovers_when_jbiIsAHalfCell() {
        val cards = shelf()
        halves.forEach { width ->
            val columns = homeGridColumns(width)
            assertTrue("$width: $columns columns", columns >= 3)
            (1..4).forEach { units ->
                val cells = homeGridJbiSeat(cards, columns, units, half = true)
                val signals = cells.signals(cards)
                val covers = cells.size - signals
                assertTrue("$width × $units: $signals signals", signals <= 1)
                assertTrue("$width × $units: $covers covers vs $signals signals", covers > signals * 2)
                assertNoHoles(cells, columns, units)
            }
        }
    }

    @Test
    fun should_beAllCovers_when_aHalfIsOneUnitOfThreeColumns() {
        val cards = shelf()
        val cells = homeGridJbiSeat(cards, columns = homeGridColumns(398f), units = 1, half = true)
        assertEquals(0, cells.signals(cards))
        assertEquals(3, cells.size)
    }

    @Test
    fun should_keepOneSignal_when_theTabletHalfIsOneUnit() {
        val cards = shelf()
        val columns = homeGridColumns(598f)
        assertEquals(5, columns)
        val cells = homeGridJbiSeat(cards, columns, units = 1, half = true)
        assertEquals(1, cells.signals(cards))
        // In the right-hand lane: its copy ends the row.
        val signal = cells.single { cards[it.index].expanded }
        assertEquals(columns - 2, signal.column)
        assertNoHoles(cells, columns, 1)
    }

    @Test
    fun should_followTheShelfBudget_when_jbiIsAFullRow() {
        val cards = shelf()
        fullRows.forEach { width ->
            val columns = homeGridColumns(width)
            (1..4).forEach { units ->
                val cells = homeGridJbiSeat(cards, columns, units, half = false)
                val signals = cells.signals(cards)
                val budget = minOf(jbiSignalLimit(units, columns * units), units)
                assertTrue("$width × $units: $signals > $budget", signals <= budget)
                assertTrue("$width × $units: no signal", signals >= 1)
                // Never two on one row.
                val perRow = cells.filter { cards[it.index].expanded }.groupBy { it.row }
                assertTrue(perRow.values.all { it.size == 1 })
                assertNoHoles(cells, columns, units)
            }
        }
    }

    @Test
    fun should_spreadSignalsDownTheRows_when_aFullRowTakesTwo() {
        val cards = shelf()
        val columns = homeGridColumns(1216f)
        val cells = homeGridJbiSeat(cards, columns, units = 4, half = false)
        val signals = cells.filter { cards[it.index].expanded }
        assertTrue(signals.size >= 2)
        // The first in the right-hand lane on the top row, the next on the left further down.
        assertEquals(0, signals.first { it.index == 0 }.row)
        assertEquals(columns - 2, signals.first { it.index == 0 }.column)
        assertEquals(0, signals.first { it.index == 1 }.column)
        assertTrue(signals.first { it.index == 1 }.row > 0)
    }

    @Test
    fun should_seatOnlyCovers_when_thereAreNoSignals() {
        val cards = shelf(signals = 0, covers = 12)
        val cells = homeGridJbiSeat(cards, columns = 3, units = 3, half = true)
        assertEquals(9, cells.size)
        assertEquals((0 until 9).toList(), cells.map { it.index })
    }

    @Test
    fun should_pointEveryCellIntoTheCards_when_theShelfIsShort() {
        val cards = shelf(signals = 3, covers = 2)
        val cells = homeGridJbiSeat(cards, columns = 5, units = 3, half = true)
        assertEquals(cells.size, cells.map { it.index }.toSet().size)
        assertTrue(cells.all { it.index in cards.indices })
        assertTrue(cells.signals(cards) <= 1)
    }

    @Test
    fun should_takeNoSignal_when_theGridHasTwoColumns() {
        assertEquals(0, homeGridJbiSignalLimit(columns = 2, units = 4, covers = 40, half = false))
    }
}
