package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 2026-10-05 QA: once the idle lyric tools collapsed their slot, the title sank
 * onto the spot where ✓ / search had been, and the first tap meant for a tool
 * opened the album instead. The player's layout in miniature: a lyrics area
 * above, the title in the bottom band the tools left, the touch tracker on
 * the root and the wake guard on the column.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class LyricToolsWakeGuardTest {

    @get:Rule
    val rule = createComposeRule()

    private var titleClicks = 0
    private var lyricTaps = 0
    private var dragged = 0f
    private lateinit var idle: LyricToolsIdleState

    private fun setPlayer(guardEnabled: Boolean = true) {
        idle = LyricToolsIdleState(clock = { rule.mainClock.currentTime })
        rule.setContent {
            LaunchedEffect(Unit) { idle.track(eligible = true) }
            Box(
                modifier = Modifier
                    .size(360.dp, 640.dp)
                    .lyricToolsTouchTracker(idle)
                    // Stands in for the host's vertical drag-to-dismiss.
                    .draggable(
                        state = rememberDraggableState { dragged += it },
                        orientation = Orientation.Vertical,
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .lyricToolsWakeGuard(idle, enabled = guardEnabled, band = BandHeight),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("lyrics")
                            .clickable { lyricTaps++ },
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(BandHeight)
                            .testTag("title")
                            .clickable { titleClicks++ },
                    )
                }
            }
        }
    }

    private fun letTheToolsStepAway() {
        rule.mainClock.advanceTimeBy(LyricToolsIdleMs + 500L)
        rule.waitForIdle()
        assertTrue("precondition: tools are away", idle.idle)
    }

    @Test
    fun should_onlyWakeTheTools_when_theTitleIsTappedWhileTheyAreAway() {
        setPlayer()
        letTheToolsStepAway()

        rule.onNodeWithTag("title").performTouchInput { click() }
        rule.waitForIdle()

        assertEquals("the wake tap must not open the album", 0, titleClicks)
        assertFalse("the tap brought the tools back", idle.idle)
    }

    @Test
    fun should_openTheAlbum_when_theTitleIsTappedWhileTheToolsShow() {
        setPlayer()
        assertFalse("precondition: tools are showing", idle.idle)

        rule.onNodeWithTag("title").performTouchInput { click() }
        rule.waitForIdle()

        assertEquals(1, titleClicks)
    }

    @Test
    fun should_openTheAlbumOnTheSecondTap_when_theFirstOneWokeTheTools() {
        setPlayer()
        letTheToolsStepAway()

        rule.onNodeWithTag("title").performTouchInput { click() }
        rule.mainClock.advanceTimeBy(400L)
        rule.onNodeWithTag("title").performTouchInput { click() }
        rule.waitForIdle()

        assertEquals(1, titleClicks)
    }

    @Test
    fun should_letTapsThrough_when_theyLandAboveTheBandWhileTheToolsAreAway() {
        setPlayer()
        letTheToolsStepAway()

        rule.onNodeWithTag("lyrics").performTouchInput { click() }
        rule.waitForIdle()

        assertEquals("a lyric line above the band still answers its tap", 1, lyricTaps)
        assertFalse(idle.idle)
    }

    @Test
    fun should_stillStartTheDrag_when_aDragBeginsInTheBandWhileTheToolsAreAway() {
        setPlayer()
        letTheToolsStepAway()

        rule.onNodeWithTag("title").performTouchInput { swipeUp() }
        rule.waitForIdle()

        assertTrue("the drag reached the dismiss draggable", dragged != 0f)
        assertEquals(0, titleClicks)
    }

    @Test
    fun should_openTheAlbum_when_theLayoutDoesNotCollapseTheTools() {
        // Tools that fade in place (tab row, 16:9) leave nothing to sink onto the
        // title: no band guard there (the tools carry their own in-place guard).
        setPlayer(guardEnabled = false)
        letTheToolsStepAway()

        rule.onNodeWithTag("title").performTouchInput { click() }
        rule.waitForIdle()

        assertEquals(1, titleClicks)
    }

    // ── Tools that fade in place (tab row): the guard sits on the tools ─────

    private var searchClicks = 0
    private var tabClicks = 0

    private fun setTabRow() {
        idle = LyricToolsIdleState(clock = { rule.mainClock.currentTime })
        rule.setContent {
            LaunchedEffect(Unit) { idle.track(eligible = true) }
            Box(
                modifier = Modifier
                    .size(360.dp, 640.dp)
                    .lyricToolsTouchTracker(idle),
            ) {
                Row {
                    Box(
                        modifier = Modifier
                            .size(120.dp, ToolHeight)
                            .testTag("tab")
                            .clickable { tabClicks++ },
                    )
                    Box(modifier = Modifier.lyricToolsInPlaceWakeGuard(idle)) {
                        Box(
                            modifier = Modifier
                                .size(ToolHeight)
                                .testTag("search")
                                .clickable { searchClicks++ },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun should_onlyWakeTheTools_when_anInPlaceToolIsTappedWhileTheyAreAway() {
        setTabRow()
        letTheToolsStepAway()

        rule.onNodeWithTag("search").performTouchInput { click() }
        rule.waitForIdle()

        assertEquals("a blind tap must not open the search sheet", 0, searchClicks)
        assertFalse("the tap brought the tools back", idle.idle)

        rule.mainClock.advanceTimeBy(400L)
        rule.onNodeWithTag("search").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals("once back, the tool answers", 1, searchClicks)
    }

    @Test
    fun should_fireTheTool_when_anInPlaceToolIsTappedWhileShowing() {
        setTabRow()
        assertFalse("precondition: tools are showing", idle.idle)

        rule.onNodeWithTag("search").performTouchInput { click() }
        rule.waitForIdle()

        assertEquals(1, searchClicks)
    }

    @Test
    fun should_letTheTabAnswer_when_tappedBesideTheInPlaceToolsWhileTheyAreAway() {
        setTabRow()
        letTheToolsStepAway()

        rule.onNodeWithTag("tab").performTouchInput { click() }
        rule.waitForIdle()

        assertEquals("the visible tab beside the tools is not guarded", 1, tabClicks)
    }

    @Test
    fun should_swallowOnlyIdleDownsInsideTheBottomBand_when_routing() {
        assertTrue(lyricToolsWakeTapSwallowed(toolsIdle = true, downY = 590f, nodeHeight = 600f, bandPx = 136f))
        assertTrue(lyricToolsWakeTapSwallowed(toolsIdle = true, downY = 464f, nodeHeight = 600f, bandPx = 136f))
        assertFalse(lyricToolsWakeTapSwallowed(toolsIdle = true, downY = 463f, nodeHeight = 600f, bandPx = 136f))
        assertFalse(lyricToolsWakeTapSwallowed(toolsIdle = false, downY = 590f, nodeHeight = 600f, bandPx = 136f))
        assertFalse(lyricToolsWakeTapSwallowed(toolsIdle = true, downY = 590f, nodeHeight = 600f, bandPx = 0f))
    }

    private companion object {
        val BandHeight = 136.dp
        val ToolHeight = 44.dp
    }
}
