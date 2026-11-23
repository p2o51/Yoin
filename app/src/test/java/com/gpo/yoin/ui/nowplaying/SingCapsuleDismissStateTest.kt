package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingCapsuleDismissStateTest {
    @Test
    fun should_neverDismissInSingleContinuousDragPastLeftEdge() {
        val state = SingCapsuleDismissState(dismissThresholdPx = 56f)
        state.begin(currentLevel = 0.45f)

        val step1 = state.drag(translationX = -300f, trackWidthPx = 200f)
        assertEquals(SingCapsuleDismissState.StepResult.Update(level = 0f, overpullProgress = 0f), step1)

        val step2 = state.drag(translationX = -900f, trackWidthPx = 200f)
        assertEquals(SingCapsuleDismissState.StepResult.Update(level = 0f, overpullProgress = 0f), step2)
        state.end()
    }

    @Test
    fun should_dismissOnlyOnSecondLeftDragAfterReleasingAtZero() {
        val state = SingCapsuleDismissState(dismissThresholdPx = 56f)

        // Parked at 0%, lift and drag right: raises vocal level, never dismisses.
        state.begin(currentLevel = 0f)
        val right = state.drag(translationX = 100f, trackWidthPx = 200f)
        assertTrue(right is SingCapsuleDismissState.StepResult.Update)
        assertEquals(0.5f, (right as SingCapsuleDismissState.StepResult.Update).level, 1e-4f)
        assertEquals(0f, right.overpullProgress, 0f)
        state.end()

        // Parked at 0%, lift and start NEW drag left: partial shows elastic overpull, threshold triggers Dismiss.
        state.begin(currentLevel = 0f)
        val partial = state.drag(translationX = -28f, trackWidthPx = 200f)
        assertEquals(SingCapsuleDismissState.StepResult.Update(level = 0f, overpullProgress = 0.5f), partial)

        val dismiss = state.drag(translationX = -60f, trackWidthPx = 200f)
        assertEquals(SingCapsuleDismissState.StepResult.Dismiss, dismiss)
        state.end()
    }

    @Test
    fun should_suppressLyricToolsAutoHide_while_singIsPreparingOrActive() {
        assertTrue(
            lyricToolsIdleEligible(
                isPlaying = true,
                hasSyncedLyrics = true,
                autoScroll = true,
                lyricsPageSelected = true,
                toolsOnScreen = true,
                selectingLines = false,
                singEnabled = false,
            ),
        )
        assertEquals(
            false,
            lyricToolsIdleEligible(
                isPlaying = true,
                hasSyncedLyrics = true,
                autoScroll = true,
                lyricsPageSelected = true,
                toolsOnScreen = true,
                selectingLines = false,
                singEnabled = true,
            ),
        )
    }
}
