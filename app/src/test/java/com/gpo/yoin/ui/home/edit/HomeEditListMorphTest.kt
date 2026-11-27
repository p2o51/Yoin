package com.gpo.yoin.ui.home.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pinched list's fold (owner 2026-11-27): each block morphs into its strip one after another, top to
 * bottom, on the slow spatial spring; the way back runs the same path.
 */
class HomeEditListMorphTest {

    @Test
    fun should_restAtBothEnds_when_theListIsOffOrOn() {
        listOf(0f, .3f, 1f).forEach { lane ->
            assertEquals(0f, homeListMorph(0f, lane), 0f)
            assertEquals(1f, homeListMorph(1f, lane), 1e-6f)
        }
    }

    @Test
    fun should_foldTopBlocksFirst_when_theListIsOnItsWay() {
        val p = .4f
        val top = homeListMorph(p, 0f)
        val middle = homeListMorph(p, .5f)
        val bottom = homeListMorph(p, 1f)
        assertTrue("$top > $middle > $bottom", top > middle && middle > bottom)
        // The bottom block waits out the spread.
        assertEquals(0f, homeListMorph(HomeEditListTokens.MorphSpread, 1f), 1e-6f)
        // The top block is done when the last one starts its final stretch.
        assertEquals(1f, homeListMorph(1f - HomeEditListTokens.MorphSpread, 0f), 1e-6f)
    }

    @Test
    fun should_carryTheSpringsOvershoot_when_theListPassesOne() {
        assertTrue(homeListMorph(1.02f, .5f) > 1f)
        // Below the feed the blocks just rest.
        assertEquals(0f, homeListMorph(-.03f, 0f), 0f)
    }

    @Test
    fun should_neverGoBackwards_when_progressRises() {
        listOf(0f, .25f, .8f, 1f).forEach { lane ->
            var last = -1f
            var p = 0f
            while (p <= 1f) {
                val e = homeListMorph(p, lane)
                assertTrue(e >= last)
                last = e
                p += .01f
            }
        }
    }

    @Test
    fun should_placeTheLane_when_aRowSitsInTheScrollArea() {
        val area = HomeEditSafeArea(top = 100f, bottom = 1100f)
        assertEquals(0f, homeListLane(50f, area), 0f)
        assertEquals(.5f, homeListLane(600f, area), 1e-6f)
        assertEquals(1f, homeListLane(2000f, area), 0f)
        assertEquals(0f, homeListLane(600f, HomeEditSafeArea(500f, 500f)), 0f)
    }

    @Test
    fun should_useTheSlowSpatialSpring_when_theListFolds() {
        val scheme = androidx.compose.material3.MotionScheme.expressive()
        val specs = HomeEditSpecs.create(scheme, reduced = false)
        val fold = specs.fold as androidx.compose.animation.core.SpringSpec<Float>
        val slow = scheme.slowSpatialSpec<Float>() as androidx.compose.animation.core.SpringSpec<Float>
        assertEquals(slow.stiffness, fold.stiffness, 0f)
        assertEquals(slow.dampingRatio, fold.dampingRatio, 0f)
    }
}
