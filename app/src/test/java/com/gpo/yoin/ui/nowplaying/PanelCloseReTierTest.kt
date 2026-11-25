package com.gpo.yoin.ui.nowplaying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Owner 2026-11-25: the content beside the side panel re-tiers at the START
 * of the close (the commit), as it does at the start of the open — never
 * after the panel has gone, and never for a close that was cancelled.
 */
@RunWith(RobolectricTestRunner::class)
class PanelCloseReTierTest {

    @get:Rule
    val rule = createComposeRule()

    private val window = 800.dp
    private val frame = NowPlayingFrame(NowPlayingPresentation.Panel, panelAvailable = true, windowWidth = window)

    @Test
    fun should_reTierToTheFullWindow_when_theCloseIsCommitted_evenWhileThePanelStillSlides() {
        var expanded by mutableStateOf(true)
        var inset: NowPlayingPanelInset? = null
        rule.mainClock.autoAdvance = false
        rule.setContent { inset = rememberNowPlayingPanelInset(frame, expanded) }
        rule.mainClock.advanceTimeBy(2_000)
        rule.runOnIdle { assertDp(400.dp, inset!!.restingWidthBeside(window)) }

        rule.runOnUiThread { expanded = false }
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()

        rule.runOnIdle {
            assertTrue("the panel's spring has only started", inset!!.isMoving)
            assertDp(window, inset!!.restingWidthBeside(window))
        }
    }

    @Test
    fun should_keepTheNarrowTier_when_aDragOrBackIsCancelled() {
        // A drag or a back preview carries the panel without closing it; a
        // cancel springs it home. Only the commit turns `expanded` off.
        val motion = NowPlayingPanelMotion().apply { travelReader = { 180f } }
        var inset: NowPlayingPanelInset? = null
        rule.setContent { inset = rememberNowPlayingPanelInset(frame, expanded = true, motion = motion) }

        rule.runOnIdle {
            assertTrue(inset!!.isCarried)
            assertDp(400.dp, inset!!.restingWidthBeside(window))
        }
        motion.travelReader = { 0f }
        rule.runOnIdle { assertDp(400.dp, inset!!.restingWidthBeside(window)) }
    }

    @Test
    fun should_giveTheWholeWindow_when_notAPanel() {
        val full = NowPlayingFrame(NowPlayingPresentation.Enlarged, panelAvailable = true, windowWidth = window)
        var inset: NowPlayingPanelInset? = null
        rule.setContent { inset = rememberNowPlayingPanelInset(full, expanded = true) }
        rule.runOnIdle { assertDp(window, inset!!.restingWidthBeside(window)) }
    }

    private fun assertDp(expected: Dp, actual: Dp) = assertEquals(expected.value, actual.value, 0.001f)
}
