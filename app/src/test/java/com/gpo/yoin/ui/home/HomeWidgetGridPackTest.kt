package com.gpo.yoin.ui.home

import com.gpo.yoin.ui.memories.MemoryEntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for [packWidgetRows].
 *
 * The 3-column cases are GOLDEN: they pin the shipped phone packing exactly
 * (one compact per wide row, wide side alternating by pair index) — Compact
 * rendering must stay byte-identical, so these expectations must never change.
 * The 4-column cases cover the Medium+ re-pack of the same 12-cell budget,
 * where a wide row carries the 1×2 plus TWO 1×1s.
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
    fun threeCol_secondWide_alternatesToRightSide() {
        val w1 = wide("w1")
        val w2 = wide("w2")
        val c = (1..8).map { compact("c$it") }
        val rows = packWidgetRows(listOf(w1, w2) + c, columns = 3)
        assertEquals(
            listOf(
                listOf(w1, c[0]),
                listOf(c[1], w2),
                listOf(c[2], c[3], c[4]),
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
                listOf(c[2], c[3], w2),
                listOf(c[4], c[5], c[6], c[7]),
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

    @Test
    fun bothColumnCounts_neverOverflowARow_andNeverDropCards() {
        val cards = listOf(wide("w1"), wide("w2"), wide("w3")) +
            (1..6).map { compact("c$it") }
        listOf(3, 4).forEach { columns ->
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
