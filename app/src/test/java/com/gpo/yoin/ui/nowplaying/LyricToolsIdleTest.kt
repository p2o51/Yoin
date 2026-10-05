package com.gpo.yoin.ui.nowplaying

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyricToolsIdleTest {

    private fun TestScope.idleState() = LyricToolsIdleState(clock = { testScheduler.currentTime })

    @Test
    fun should_hideToolsAfterThreeSeconds_when_nothingTouchesThePlayer() = runTest {
        assertEquals(3_000L, LyricToolsIdleMs)
        val state = idleState()
        val job = launch { state.track(eligible = true) }
        runCurrent()

        advanceTimeBy(LyricToolsIdleMs - 1)
        runCurrent()
        assertFalse(state.idle)

        advanceTimeBy(2)
        runCurrent()
        assertTrue(state.idle)
        job.cancel()
    }

    @Test
    fun should_restartTheCount_when_theUserTouchesBeforeItRunsOut() = runTest {
        val state = idleState()
        val job = launch { state.track(eligible = true) }
        runCurrent()

        advanceTimeBy(2_000)
        state.onInteraction()
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(state.idle)

        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(state.idle)
        job.cancel()
    }

    @Test
    fun should_showAndHideAgain_when_touchedWhileIdle() = runTest {
        val state = idleState()
        val job = launch { state.track(eligible = true) }
        advanceTimeBy(LyricToolsIdleMs + 1)
        runCurrent()
        assertTrue(state.idle)

        state.onInteraction()
        assertFalse(state.idle)

        advanceTimeBy(LyricToolsIdleMs + LyricToolsIdlePollMs + 1)
        runCurrent()
        assertTrue(state.idle)
        job.cancel()
    }

    @Test
    fun should_neverHide_when_notEligible() = runTest {
        val state = idleState()
        state.track(eligible = false)
        advanceTimeBy(LyricToolsIdleMs * 3)
        runCurrent()
        assertFalse(state.idle)
    }

    @Test
    fun should_rearmWithAFreshCount_when_eligibilityReturns() = runTest {
        // Lyrics → About → Lyrics: LaunchedEffect(eligible) restarts track().
        val state = idleState()
        var job = launch { state.track(eligible = true) }
        advanceTimeBy(LyricToolsIdleMs + 1)
        runCurrent()
        assertTrue(state.idle)

        job.cancel()
        state.track(eligible = false) // the About page
        assertFalse(state.idle)
        advanceTimeBy(10_000)

        job = launch { state.track(eligible = true) } // back on Lyrics
        runCurrent()
        assertFalse(state.idle)
        advanceTimeBy(LyricToolsIdleMs + 1)
        runCurrent()
        assertTrue(state.idle)
        job.cancel()
    }

    @Test
    fun should_keepToolsVisible_when_selectingLines() {
        assertTrue(
            lyricToolsIdleEligible(
                isPlaying = true,
                hasSyncedLyrics = true,
                autoScroll = true,
                lyricsPageSelected = true,
                toolsOnScreen = true,
                selectingLines = false,
            ),
        )
        assertFalse(
            lyricToolsIdleEligible(
                isPlaying = true,
                hasSyncedLyrics = true,
                autoScroll = true,
                lyricsPageSelected = true,
                toolsOnScreen = true,
                selectingLines = true,
            ),
        )
    }

    @Test
    fun should_notArm_when_onAnotherPageOrPausedOrScrolledAway() {
        fun eligible(
            isPlaying: Boolean = true,
            autoScroll: Boolean = true,
            lyricsPage: Boolean = true,
        ) = lyricToolsIdleEligible(
            isPlaying = isPlaying,
            hasSyncedLyrics = true,
            autoScroll = autoScroll,
            lyricsPageSelected = lyricsPage,
            toolsOnScreen = true,
            selectingLines = false,
        )
        assertFalse(eligible(lyricsPage = false))
        assertFalse(eligible(isPlaying = false))
        assertFalse(eligible(autoScroll = false))
    }
}
