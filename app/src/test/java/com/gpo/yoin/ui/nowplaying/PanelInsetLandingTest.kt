package com.gpo.yoin.ui.nowplaying

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The columns beside the side panel re-tier back to full width when the
 * panel lands, not after its spring's sub-pixel tail (that tail held Home's
 * narrow layout ~250ms past the panel's exit, then it snapped).
 */
class PanelInsetLandingTest {

    private fun inset(open: Boolean, base: Dp, frozenTravel: Float = 0f) = NowPlayingPanelInset(
        panelOpen = open,
        panelWidth = 400.dp,
        base = mutableStateOf(base),
        motion = null,
        frozenTravelFraction = floatArrayOf(frozenTravel),
        baseTarget = if (open) 400.dp else 0.dp,
    )

    @Test
    fun should_holdTheWidth_when_thePanelIsOpenOrStillOpening() {
        assertTrue(inset(open = true, base = 400.dp).holdsWidth)
        assertTrue(inset(open = true, base = 0.dp).holdsWidth)
    }

    @Test
    fun should_holdTheWidth_when_theClosingPanelStillCoversPartOfIt() {
        assertTrue(inset(open = false, base = 120.dp).holdsWidth)
        assertTrue(inset(open = false, base = 2.dp).holdsWidth)
    }

    @Test
    fun should_releaseTheWidth_when_theClosingPanelHasLandedButItsSpringStillSettles() {
        val landing = inset(open = false, base = 0.4.dp)
        assertTrue("the spring is still running", landing.isMoving)
        assertFalse(landing.holdsWidth)
    }

    @Test
    fun should_releaseTheWidth_when_aDraggedPanelLandsFromItsCarriedPose() {
        // Dragged 90% off before release: the inset left is base × 10%.
        assertFalse(inset(open = false, base = 8.dp, frozenTravel = 0.9f).holdsWidth)
        assertTrue(inset(open = false, base = 40.dp, frozenTravel = 0.9f).holdsWidth)
    }

    @Test
    fun should_releaseTheWidth_when_closedAndAtRest() {
        assertFalse(inset(open = false, base = 0.dp).holdsWidth)
    }
}
