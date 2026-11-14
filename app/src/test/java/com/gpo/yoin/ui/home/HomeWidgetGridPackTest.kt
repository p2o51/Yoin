package com.gpo.yoin.ui.home

import com.gpo.yoin.ui.memories.MemoryEntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for [packWidgetRows].
 *
 * The 3-column cases pin the phone packing: one compact per wide row, wide
 * side alternating by pair index, and (owner 2026-11-14) the wide rows spread
 * over the shelf instead of stacked at its top.
 * The 4-column cases cover the Medium (and Tabletop) re-pack of the same
 * 12-cell budget, where a wide row carries the 1×2 plus TWO 1×1s; the
 * 6-column cases cover the full-window Wide canvas, where it carries FOUR.
 */
class HomeWidgetGridPackTest {

    // ---- 3-column golden (shipped phone behavior) ----

    @Test
    fun threeCol_singleWide_pairsWithFirstCompact_wideOnLeft() {
        val w1 = wide("w1")
        val c = (1..4).map { compact("c$it") }
        val rows = packWidgetRows(listOf(w1) + c, columns = 3)
        assertEquals(
            listOf(
                listOf(w1, c[0]),
                listOf(c[1], c[2], c[3]),
            ),
            rows,
        )
    }

    @Test
    fun threeCol_secondWide_alternatesToRightSide_andSpreadsDown() {
        val w1 = wide("w1")
        val w2 = wide("w2")
        val c = (1..8).map { compact("c$it") }
        val rows = packWidgetRows(listOf(w1, w2) + c, columns = 3)
        assertEquals(
            listOf(
                listOf(w1, c[0]),
                listOf(c[1], c[2], c[3]),
                listOf(c[4], w2),
                listOf(c[5], c[6], c[7]),
            ),
            rows,
        )
    }

    @Test
    fun threeCol_wideWithoutRemainingCompact_standsAlone() {
        val w1 = wide("w1")
        val w2 = wide("w2")
        val c1 = compact("c1")
        val rows = packWidgetRows(listOf(w1, w2, c1), columns = 3)
        assertEquals(
            listOf(
                listOf(w1, c1),
                listOf(w2),
            ),
            rows,
        )
    }

    @Test
    fun threeCol_widesLeadRegardlessOfInputPosition() {
        val c1 = compact("c1")
        val w1 = wide("w1")
        val c2 = compact("c2")
        val rows = packWidgetRows(listOf(c1, w1, c2), columns = 3)
        assertEquals(
            listOf(
                listOf(w1, c1),
                listOf(c2),
            ),
            rows,
        )
    }

    // ---- 4-column (Medium+ panes) ----

    @Test
    fun fourCol_twoWidesEightCompacts_fillThreeFullRows() {
        val w1 = wide("w1")
        val w2 = wide("w2")
        val c = (1..8).map { compact("c$it") }
        val cards = listOf(w1, w2) + c
        val rows = packWidgetRows(cards, columns = 4)
        assertEquals(
            listOf(
                listOf(w1, c[0], c[1]),
                listOf(c[2], c[3], c[4], c[5]),
                listOf(c[6], c[7], w2),
            ),
            rows,
        )
        // Full 12-cell budget, every row exactly 4 units, no card lost or
        // duplicated.
        rows.forEach { row -> assertEquals(4, units(row)) }
        assertEquals(cards.size, rows.flatten().size)
        assertEquals(cards.toSet(), rows.flatten().toSet())
    }

    @Test
    fun fourCol_zeroWides_chunksIntoRowsOfFour() {
        val c = (1..8).map { compact("c$it") }
        val rows = packWidgetRows(c, columns = 4)
        assertEquals(
            listOf(
                listOf(c[0], c[1], c[2], c[3]),
                listOf(c[4], c[5], c[6], c[7]),
            ),
            rows,
        )
    }

    @Test
    fun fourCol_leftoverCompactsShortOfFullRow_stillEmitted() {
        val w1 = wide("w1")
        val c = (1..3).map { compact("c$it") }
        val rows = packWidgetRows(listOf(w1) + c, columns = 4)
        assertEquals(
            listOf(
                listOf(w1, c[0], c[1]),
                listOf(c[2]),
            ),
            rows,
        )
    }

    @Test
    fun fourCol_wideWithSingleCompact_partiallyFilledRow() {
        val w1 = wide("w1")
        val c1 = compact("c1")
        val rows = packWidgetRows(listOf(w1, c1), columns = 4)
        assertEquals(listOf(listOf(w1, c1)), rows)
    }

    // ---- 6-column (full-window Wide canvas) ----

    @Test
    fun sixCol_twoWidesEightCompacts_fillTwoFullRows() {
        val w1 = wide("w1")
        val w2 = wide("w2")
        val c = (1..8).map { compact("c$it") }
        val cards = listOf(w1, w2) + c
        val rows = packWidgetRows(cards, columns = 6)
        assertEquals(
            listOf(
                listOf(w1, c[0], c[1], c[2], c[3]),
                listOf(c[4], c[5], c[6], c[7], w2),
            ),
            rows,
        )
        // The full 12-cell budget divides evenly (12 % 6 == 0): every row is
        // exactly 6 units, no card lost or duplicated.
        rows.forEach { row -> assertEquals(6, units(row)) }
        assertEquals(cards.size, rows.flatten().size)
        assertEquals(cards.toSet(), rows.flatten().toSet())
    }

    @Test
    fun sixCol_zeroWides_chunksIntoRowsOfSix() {
        val c = (1..12).map { compact("c$it") }
        val rows = packWidgetRows(c, columns = 6)
        assertEquals(
            listOf(
                listOf(c[0], c[1], c[2], c[3], c[4], c[5]),
                listOf(c[6], c[7], c[8], c[9], c[10], c[11]),
            ),
            rows,
        )
    }

    @Test
    fun sixCol_leftoverCompactsShortOfFullRow_stillEmitted() {
        val w1 = wide("w1")
        val c = (1..5).map { compact("c$it") }
        val rows = packWidgetRows(listOf(w1) + c, columns = 6)
        assertEquals(
            listOf(
                listOf(w1, c[0], c[1], c[2], c[3]),
                listOf(c[4]),
            ),
            rows,
        )
    }

    @Test
    fun sixCol_wideWithSingleCompact_partiallyFilledRow() {
        val w1 = wide("w1")
        val c1 = compact("c1")
        val rows = packWidgetRows(listOf(w1, c1), columns = 6)
        assertEquals(listOf(listOf(w1, c1)), rows)
    }

    @Test
    fun allColumnCounts_neverOverflowARow_andNeverDropCards() {
        val cards = listOf(wide("w1"), wide("w2"), wide("w3")) +
            (1..6).map { compact("c$it") }
        listOf(3, 4, 6).forEach { columns ->
            val rows = packWidgetRows(cards, columns = columns)
            rows.forEach { row ->
                assertTrue(
                    "row exceeds $columns units: $row",
                    units(row) <= columns,
                )
            }
            assertEquals(cards.size, rows.flatten().size)
            assertEquals(cards.toSet(), rows.flatten().toSet())
        }
    }

    // ---- spreading the wide rows (owner 2026-11-14) ----

    @Test
    fun should_spreadWideRowsEvenly_when_phoneShelfHasMoreRows() {
        // The phone presets (2 / 3 / 4 / 6 rows) and a few longer shelves.
        for (rowCount in 2..10) {
            for (k in 1..minOf(4, rowCount)) {
                val wides = (1..k).map { wide("w$it") }
                val covers = (1..(rowCount * 3 - 2 * k)).map { compact("c$it") }
                val rows = packWidgetRows(wides + covers, columns = 3)
                val at = "R=$rowCount k=$k"
                assertEquals(at, rowCount, rows.size)
                val wideRows = rows.indices.filter { r -> rows[r].any { it.expanded } }
                assertEquals(at, jbiSpreadRows(k, rowCount), wideRows)
                assertEquals(at, 0, wideRows.first())
                // Gaps differ by at most one row: even, not front-loaded.
                val gaps = (wideRows + rowCount).zipWithNext { a, b -> b - a }
                assertTrue("$at $gaps", gaps.max() - gaps.min() <= 1)
                // Never two wide rows touching while the shelf has room to part them.
                if (2 * k <= rowCount) assertTrue("$at $wideRows", gaps.dropLast(1).all { it >= 2 })
                // Every card once, covers and wides each in their ranking order.
                val flat = rows.flatten()
                assertEquals(at, covers, flat.filterNot { it.expanded })
                assertEquals(at, wides, flat.filter { it.expanded })
                rows.forEach { row -> assertEquals(at, 3, units(row)) }
            }
        }
    }

    @Test
    fun should_spreadByCeiling_when_pickingRows() {
        assertEquals(listOf(0), jbiSpreadRows(1, 2))
        assertEquals(listOf(0, 2), jbiSpreadRows(2, 3))
        assertEquals(listOf(0, 2), jbiSpreadRows(2, 4))
        assertEquals(listOf(0, 2, 4), jbiSpreadRows(3, 6))
        assertEquals(listOf(0, 3, 5), jbiSpreadRows(3, 7))
        assertEquals(listOf(0, 1), jbiSpreadRows(2, 2))
    }

    // ---- helpers ----

    private fun compact(id: String): HomeWidgetCard = HomeWidgetCard(
        stableId = id,
        entityType = MemoryEntityType.ALBUM,
        title = id,
        subtitle = "subtitle",
        coverArtUrl = null,
        target = HomeWidgetTarget.AlbumDetail(albumId = id),
    )

    private fun wide(id: String): HomeWidgetCard = compact(id).copy(
        expanded = true,
        ratingText = "7.0",
    )

    private fun units(row: List<HomeWidgetCard>): Int =
        row.sumOf { card -> if (card.expanded) 2 else 1 }
}
