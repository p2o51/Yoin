package com.gpo.yoin.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A grid section's cells (HomeGridCells) when the width moves — a block
 * turning half ⇄ full, a rotation: every cell takes its new place at once and
 * only the new layout's cells are composed. Springing from the old layout's
 * places (and fading the old width's cells out), Jump Back In drew its old
 * and new layouts over each other while its block widened or narrowed.
 */
@RunWith(RobolectricTestRunner::class)
class HomeGridCellsReseatTest {

    @get:Rule
    val rule = createComposeRule()

    private var widthDp by mutableStateOf(400)
    private var units by mutableStateOf(2)
    private val composed = HashMap<Int, Int>()
    private val coordinates = HashMap<Int, LayoutCoordinates>()
    private var grid: LayoutCoordinates? = null

    private fun columnsAt(widthDp: Int) = widthDp / 130

    private fun setUp() {
        rule.setContent {
            val columns = columnsAt(widthDp)
            val cells = homeGridPack(List(12) { 1 }, columns, units)
            Box(Modifier.width(widthDp.dp)) {
                HomeGridCells(
                    cells = cells,
                    columns = columns,
                    gap = 8.dp,
                    modifier = Modifier.onPlaced { grid = it },
                ) { cell ->
                    DisposableEffect(cell.index) {
                        composed[cell.index] = (composed[cell.index] ?: 0) + 1
                        onDispose { composed[cell.index] = composed.getValue(cell.index) - 1 }
                    }
                    Box(Modifier.fillMaxSize().onPlaced { coordinates[cell.index] = it })
                }
            }
        }
        rule.waitForIdle()
    }

    private fun nextFrame() {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
    }

    private fun composedNow(): Set<Int> = composed.filterValues { it > 0 }.keys

    /** Each cell's top-left in the grid now, against where [columns] × [rows] put it. */
    private fun assertSeated(count: Int, columns: Int) {
        val grid = grid!!
        val (gap, unit, rowGap) = with(rule.density) { Triple(8.dp.roundToPx(), HomeGridTokens.Unit.roundToPx(), HomeGridTokens.Gap.roundToPx()) }
        homeGridPack(List(count) { 1 }, columns, units).forEach { cell ->
            val (x, _) = homeGridCellX(cell.column, cell.span, columns, grid.size.width, gap)
            val at = grid.localPositionOf(coordinates.getValue(cell.index), Offset.Zero)
            assertEquals("cell ${cell.index} x", x.toFloat(), at.x, .5f)
            assertEquals("cell ${cell.index} y", (cell.row * (unit + rowGap)).toFloat(), at.y, .5f)
        }
    }

    @Test
    fun should_reseatAtOnce_withOnlyNewCellsComposed_when_widthMoves() {
        setUp()
        assertEquals((0 until 6).toSet(), composedNow())

        // Half → full: 3 columns to 6. On the very next frame every cell is where 6 columns put it.
        rule.mainClock.autoAdvance = false
        widthDp = 800
        nextFrame()
        assertEquals((0 until 12).toSet(), composedNow())
        assertSeated(count = 12, columns = 6)

        // Full → half: the cells 3 columns leave out are gone at once, not fading where they were.
        widthDp = 400
        nextFrame()
        assertEquals((0 until 6).toSet(), composedNow())
        assertSeated(count = 6, columns = 3)
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun should_stillFadeOut_when_unitCountShrinksAtSameWidth() {
        setUp()
        rule.mainClock.autoAdvance = false
        units = 1
        nextFrame()
        // The second row fades out where it was: still composed a frame on…
        assertTrue(composedNow().containsAll((3 until 6).toList()))
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        // …and gone once it has.
        assertEquals((0 until 3).toSet(), composedNow())
    }

    @Test
    fun should_reseat_only_whenWidthOrColumnsChangeFromOneSeated() {
        assertTrue(homeGridReseats(last = 3, now = 6))
        assertTrue(homeGridReseats(last = 820, now = 400))
        assertTrue(!homeGridReseats(last = 0, now = 6))
        assertTrue(!homeGridReseats(last = 6, now = 6))
    }

    @Test
    fun should_newComposition_only_forChangeAtSameWidth() {
        // (width class, seed)
        val sameWidth = { old: Pair<Int, Int>, new: Pair<Int, Int> -> old.first == new.first }
        assertEquals(0, homeContentGeneration(0, 1 to 7, 1 to 7, sameWidth))
        assertEquals(1, homeContentGeneration(0, 1 to 7, 1 to 8, sameWidth))
        assertEquals(0, homeContentGeneration(0, 1 to 7, 2 to 7, sameWidth))
        assertEquals(0, homeContentGeneration(0, 1 to 7, 2 to 8, sameWidth))
        val generations = HomeContentGenerations<Pair<Int, Int>>()
        assertEquals(0, generations.of(1 to 7, sameWidth))
        // Half → full and back: the one composition.
        assertEquals(0, generations.of(2 to 7, sameWidth))
        assertEquals(0, generations.of(1 to 7, sameWidth))
        // A new seed at the same width: a new one, which crossfades.
        assertEquals(1, generations.of(1 to 9, sameWidth))
        assertEquals(1, generations.of(1 to 9, sameWidth))
    }
}
