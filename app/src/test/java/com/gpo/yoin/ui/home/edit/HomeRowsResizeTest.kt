package com.gpo.yoin.ui.home.edit

import org.junit.Assert.assertEquals
import org.junit.Test

/** The resize's pure pieces: which stops compose, where a drag stands, where a release lands. */
class HomeRowsResizeTest {

    // Stops at 100 / 150 / 200 / 300 px (S, M, L, XL).
    private val all = mapOf(0 to 100f, 1 to 150f, 2 to 200f, 3 to 300f)

    @Test
    fun should_bracketTheHeight_when_bothNeighboursAreMeasured() {
        assertEquals(HomeRowsPair(1, 2), homeRowsPair(170f, 4, all, fallback = 2))
        assertEquals(HomeRowsPair(0, 1), homeRowsPair(120f, 4, all, fallback = 2))
        assertEquals(HomeRowsPair(2, 3), homeRowsPair(250f, 4, all, fallback = 2))
    }

    @Test
    fun should_composeTheNextStopUp_when_restingOnAStop() {
        // Exactly on L: L with XL above it, so XL is measured before the finger moves.
        assertEquals(HomeRowsPair(2, 3), homeRowsPair(200f, 4, all, fallback = 2))
    }

    @Test
    fun should_composeTheUnmeasuredNeighbour_when_theHeightLeavesTheKnownStops() {
        val onlyL = mapOf(2 to 200f)
        assertEquals(HomeRowsPair(2, 3), homeRowsPair(210f, 4, onlyL, fallback = 2))
        assertEquals(HomeRowsPair(1, 2), homeRowsPair(190f, 4, onlyL, fallback = 2))
        assertEquals(HomeRowsPair(2, 2), homeRowsPair(190f, 4, emptyMap(), fallback = 2))
    }

    @Test
    fun should_holdTheEndStop_when_rubberBandingPastIt() {
        assertEquals(HomeRowsPair(3, 3), homeRowsPair(340f, 4, all, fallback = 2))
        assertEquals(HomeRowsPair(0, 0), homeRowsPair(60f, 4, all, fallback = 2))
    }

    @Test
    fun should_changeStop_when_pastTheMidpointPlusHysteresis() {
        // L → XL midpoint 250: needs 256 with a 6px hysteresis.
        assertEquals(2, homeRowsNearest(255f, 2, 4, all, hysteresis = 6f))
        assertEquals(3, homeRowsNearest(257f, 2, 4, all, hysteresis = 6f))
        // Back down: XL holds until 244.
        assertEquals(3, homeRowsNearest(245f, 3, 4, all, hysteresis = 6f))
        assertEquals(2, homeRowsNearest(243f, 3, 4, all, hysteresis = 6f))
        // Several stops in one move.
        assertEquals(0, homeRowsNearest(90f, 3, 4, all, hysteresis = 6f))
    }

    @Test
    fun should_notStepPastAnUnmeasuredStop_when_findingTheNearest() {
        assertEquals(2, homeRowsNearest(400f, 2, 4, mapOf(2 to 200f), hysteresis = 6f))
    }

    @Test
    fun should_landOnTheNearestStop_when_releasedSlowly() {
        assertEquals(2, homeRowsReleaseStop(230f, 2, 4, all, velocity = 300f, flingVelocity = 650f))
    }

    @Test
    fun should_travelOneMoreStop_when_flungPastTheNextMidpoint() {
        // 230 + 1000 × .15 = 380 > 250: XL.
        assertEquals(3, homeRowsReleaseStop(230f, 2, 4, all, velocity = 1000f, flingVelocity = 650f))
        // 180 − 1000 × .15 = 30 < 175: M.
        assertEquals(1, homeRowsReleaseStop(180f, 2, 4, all, velocity = -1000f, flingVelocity = 650f))
        // A fling whose projection stays short of the midpoint stays.
        assertEquals(
            2,
            homeRowsReleaseStop(200f, 2, 4, all, velocity = 700f, flingVelocity = 650f, projectSeconds = .05f),
        )
        // Nothing past the ends.
        assertEquals(3, homeRowsReleaseStop(320f, 3, 4, all, velocity = 2000f, flingVelocity = 650f))
    }

    @Test
    fun should_rampSquared_when_insideTheAutoScrollEdge() {
        assertEquals(0f, homeRowsEdgeRamp(-1f), 0f)
        assertEquals(.25f, homeRowsEdgeRamp(.5f), 1e-6f)
        assertEquals(1f, homeRowsEdgeRamp(3f), 0f)
    }
}
