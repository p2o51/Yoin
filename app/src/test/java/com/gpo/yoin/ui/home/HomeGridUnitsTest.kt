package com.gpo.yoin.ui.home

import androidx.compose.animation.core.snap
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.gpo.yoin.ui.home.edit.runEditClockTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two-column grid units (HomeGridUnits.kt): mapping, clamping, pairs and the handle's unit drag. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeGridUnitsTest {

    private val a = HomeSection.Activities
    private val j = HomeSection.JumpBackIn
    private val m = HomeSection.MemoryGrid
    private val f = HomeSection.MadeForYou
    private val l = HomeSection.LyricHighlight
    private val ra = HomeSection.RecentlyAdded
    private val rp = HomeSection.RecentlyPlayed
    private val yp = HomeSection.YourPlaylists
    private val rd = HomeSection.Rediscover

    private val wide = HomeLayout.WideDefault

    private fun HomeLayout.rows() = homeFeedRows(enabledSections, pairs, twoColumn = true)

    @Test
    fun should_beTitlePlusUnitsPlusGaps() {
        assertEquals(190f, homeGridHeightDp(1), 0f)
        assertEquals(354f, homeGridHeightDp(2), 0f)
        assertEquals(518f, homeGridHeightDp(3), 0f)
        assertEquals(682f, homeGridHeightDp(4), 0f)
    }

    @Test
    fun should_mapPresetsToUnits() {
        assertEquals(listOf(1, 2, 3, 4), HomeRowPreset.entries.map { it.units })
        assertEquals(HomeRowPreset.S, homeUnitsPreset(0))
        assertEquals(HomeRowPreset.M, homeUnitsPreset(2))
        assertEquals(HomeRowPreset.XL, homeUnitsPreset(7))
    }

    @Test
    fun should_useDefaults_when_nothingChosen() {
        assertEquals(2, wide.unitsOf(a))
        assertEquals(2, wide.unitsOf(j))
        assertEquals(2, wide.unitsOf(l))
        assertEquals(2, wide.unitsOf(ra))
        assertEquals(1, wide.unitsOf(m))
        assertEquals(1, wide.unitsOf(yp))
        assertEquals(listOf(2, 1, 1, 2, 1, 1, 1), wide.rows().map(wide::unitsOf))
    }

    @Test
    fun should_clampChosenPresetIntoRange() {
        // XL (4) on Activities (1–3) reads as 3; on Jump Back In (1–4) as 4.
        val layout = wide.withRows(a, HomeRowPreset.XL).withRows(j, HomeRowPreset.XL)
        assertEquals(3, layout.unitsOf(a))
        assertEquals(4, layout.unitsOf(j))
        // L picked in two columns is 3, not the default 2; the phone's resize back to L clears the choice.
        assertEquals(3, wide.withUnits(a, 3).unitsOf(a))
        assertEquals(2, wide.withUnits(a, 3).withRows(a, HomeRowPreset.S).withRows(a, HomeRowPreset.L).unitsOf(a))
    }

    @Test
    fun should_intersectRanges_when_paired() {
        assertEquals(1..3, a.unitRange.intersectUnits(j.unitRange))
        assertEquals(1..2, homeRowUnitRange(l, ra))
        assertEquals(1..2, homeRowUnitRange(m, rp))
        assertEquals(1..4, homeRowUnitRange(j, null))
    }

    @Test
    fun should_takeLeftCountClampedToBoth_when_pairCountsDiffer() {
        // Jump Back In at 4 as the LEFT half of a pair with Activities (1–3): the row is 3.
        val swapped = wide
            .withRows(j, HomeRowPreset.XL)
            .withRows(a, HomeRowPreset.S)
            .withEnabledOrder(listOf(j, a) + wide.enabledSections.drop(2))
        val row = swapped.rows().first()
        assertEquals(HomeFeedRow.Halves(j, a), row)
        assertEquals(3, swapped.unitsOf(row))
        // The right half's own count doesn't decide it.
        val other = wide.withRows(a, HomeRowPreset.S).withRows(j, HomeRowPreset.XL)
        assertEquals(1, other.unitsOf(other.rows().first()))
    }

    @Test
    fun should_setBothHalves_when_unitsChangeOnEither() {
        val next = wide.withUnits(j, 3)
        assertEquals(3, next.unitsOf(a))
        assertEquals(3, next.unitsOf(j))
        assertEquals(3, next.unitsOf(next.rows().first()))
        // Held in the overlap: 4 on a 1–3 pair is 3.
        assertEquals(3, wide.withUnits(a, 4).unitsOf(j))
        // A full row only changes itself.
        val shelf = wide.withUnits(m, 2)
        assertEquals(2, shelf.unitsOf(m))
        assertEquals(1, shelf.unitsOf(f))
    }

    @Test
    fun should_returnSame_when_unitsUnchanged() {
        val set = wide.withUnits(a, 2)
        assertSame(set, set.withUnits(j, 2))
    }

    @Test
    fun should_persistChosenUnits_and_readThemBack() {
        val next = wide.withUnits(yp, 2)
        val reread = HomeLayout.reconcile(next.toPrefs())
        assertEquals(2, reread.unitsOf(yp))
        assertEquals(2, reread.unitsOf(rd))
        // An unchosen section stores no rows and keeps reading its default.
        assertEquals(2, reread.unitsOf(l))
    }

    @Test
    fun should_stepPairTogether_and_stopAtRangeEnds() = runTest(UnconfinedTestDispatcher()) {
        var layout = wide
        val drag = HomeUnitDrag(
            scope = this,
            density = Density(2f),
            spec = { snap() },
            commit = { section, units ->
                val next = layout.withUnits(section, units)
                (next !== layout).also { layout = next }
            },
            tick = {},
        )
        drag.info = { section ->
            val row = layout.rows().first { section in listOfNotNull(it.first, (it as? HomeFeedRow.Halves)?.right) }
            HomeUnitInfo(
                row = setOfNotNull(row.first, (row as? HomeFeedRow.Halves)?.right),
                units = layout.unitsOf(row),
                range = homeRowUnitRange(row.first, (row as? HomeFeedRow.Halves)?.right),
            )
        }
        assertTrue(drag.step(j, 1))
        assertEquals(3, layout.unitsOf(a))
        assertFalse("Activities caps the pair at 3", drag.canStep(a, 1))
        assertTrue(drag.step(a, -1))
        assertTrue(drag.step(a, -1))
        assertEquals(1, layout.unitsOf(j))
        assertFalse(drag.canStep(j, -1))
    }

    @Test
    fun should_snapToNearestCount_when_dragReleased() = runEditClockTest { scope ->
        var layout = wide
        val density = Density(1f)
        val drag = HomeUnitDrag(
            scope = scope,
            density = density,
            spec = { snap() },
            commit = { section, units ->
                val next = layout.withUnits(section, units)
                (next !== layout).also { layout = next }
            },
            tick = {},
        )
        drag.info = { section ->
            val row = layout.rows().first { it.first == a }
            HomeUnitInfo(setOf(a, j), layout.unitsOf(row), homeRowUnitRange(a, j)).takeIf { section in setOf(a, j) }
        }
        assertTrue(drag.grab(j, fingerY = 1000f))
        runCurrent()
        assertEquals(354, drag.liveHeightPx(listOf(a, j)))
        // 150 down: nearer 3 units (518) than 2 (354).
        drag.dragTo(1100f)
        runCurrent()
        assertEquals(454, drag.liveHeightPx(listOf(a, j)))
        assertEquals(3, drag.target)
        drag.release(cancelled = false)
        repeat(10) {
            advanceTimeBy(16)
            runCurrent()
        }
        assertEquals(3, layout.unitsOf(a))
        assertNull(drag.section)
        // A drag in one column does nothing.
        drag.info = { null }
        assertFalse(drag.grab(a, 0f))
    }
}
