package com.gpo.yoin.ui.home

import com.gpo.yoin.data.memory.MemoryGridScale
import com.gpo.yoin.data.memory.buildMemoryGrid
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single-column All Memories card spans the feed's content width (owner 2026-11-24, small tablet in
 * portrait: it used to stop at 560dp): up to the old card it keeps the scale's own columns, wider it adds
 * columns of the old card's cell size.
 */
class HomeMemoryCardWallTest {

    /** The card's inner width for a portrait window: content min(W, 720) − 32, less the card's 2 × 16 padding. */
    private fun inner(windowDp: Float): Float = minOf(windowDp, 720f) - 32f - 32f

    @Test
    fun should_keepTheScaleColumns_when_noWiderThanTheOldCard() {
        for (scale in MemoryGridScale.entries) {
            for (window in listOf(360f, 412f, 480f, 560f, 592f)) {
                assertNull("$scale W=$window", memoryCardWallColumns(scale, inner(window)))
            }
            assertNull(memoryCardWallColumns(scale, MemoryCardWallReferenceDp))
        }
    }

    @Test
    fun should_addColumnsOfTheOldCellSize_atEveryMediumPortraitWidth() {
        val bases = mapOf(MemoryGridScale.Day to (17 to 3f), MemoryGridScale.Week to (13 to 5f), MemoryGridScale.Month to (6 to 8f))
        for ((scale, base) in bases) {
            val (columns0, gap) = base
            val oldCell = (MemoryCardWallReferenceDp - gap * (columns0 - 1)) / columns0
            var previous = columns0
            for (window in listOf(600f, 640f, 700f, 760f, 800f, 839f)) {
                val width = inner(window)
                val columns = memoryCardWallColumns(scale, width) ?: columns0
                assertTrue("$scale W=$window", columns >= previous)
                previous = columns
                val cell = (width - gap * (columns - 1)) / columns
                // The cells stay the old card's: never smaller, and less than one column's worth bigger.
                assertTrue("$scale W=$window cell=$cell", cell >= oldCell - 0.01f)
                assertTrue("$scale W=$window cell=$cell", cell < oldCell + (oldCell + gap) / columns + 0.01f)
            }
        }
        // ≈ the owner's tablet (content 688): 21 day weeks, 16 week columns, 7 months.
        assertEquals(21, memoryCardWallColumns(MemoryGridScale.Day, 656f))
        assertEquals(16, memoryCardWallColumns(MemoryGridScale.Week, 656f))
        assertEquals(7, memoryCardWallColumns(MemoryGridScale.Month, 656f))
    }

    @Test
    fun should_neverAskForMoreMonthsThanTheGridKeeps() {
        // A landscape phone's wide card: months stop at the grid's twelve.
        assertTrue(memoryCardWallColumns(MemoryGridScale.Month, 2000f)!! <= 12)
    }

    @Test
    fun should_buildOneCellPerWallSlot() {
        val today = LocalDate.of(2026, 11, 24)
        for (scale in MemoryGridScale.entries) {
            val columns = memoryCardWallColumns(scale, 656f)!!
            val grid = buildMemoryGrid(
                emptyList(),
                today,
                ZoneOffset.UTC,
                scale = scale,
                periods = memoryCardWallPeriods(scale, columns),
            )
            val rows = when (scale) {
                MemoryGridScale.Day -> 7
                MemoryGridScale.Week -> 2
                MemoryGridScale.Month -> 1
            }
            assertEquals("$scale", columns * rows, grid.cells.size)
        }
    }
}
