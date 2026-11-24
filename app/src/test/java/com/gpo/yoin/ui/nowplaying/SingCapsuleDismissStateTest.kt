package com.gpo.yoin.ui.nowplaying

import com.gpo.yoin.player.sing.SingGainCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingCapsuleDismissStateTest {
    private fun update(level: Float, overpull: Float = 0f) = SingCapsuleDismissState.StepResult.Update(level, overpull)

    private fun assertUpdate(level: Float, overpull: Float, step: SingCapsuleDismissState.StepResult) {
        assertTrue("$step", step is SingCapsuleDismissState.StepResult.Update)
        step as SingCapsuleDismissState.StepResult.Update
        assertEquals(level, step.level, 1e-4f)
        assertEquals(overpull, step.overpullProgress, 1e-4f)
    }

    @Test
    fun should_stopAtZeroAndNeverDismiss_when_draggingDownPastMinimum() {
        val state = SingCapsuleDismissState(dismissThresholdPx = 56f)
        state.begin(currentLevel = 0.45f)
        assertEquals(update(0f), state.drag(translationX = -300f, trackWidthPx = 200f))
        assertEquals(update(0f), state.drag(translationX = -900f, trackWidthPx = 200f))
        state.end()

        // Starting at 0 % and pulling further down: still nothing but 0 %.
        state.begin(currentLevel = 0f)
        assertEquals(update(0f), state.drag(translationX = -5_000f, trackWidthPx = 200f))
        state.end()
    }

    @Test
    fun should_dismissInOneGesture_when_draggingUpPastMaximumByThreshold() {
        val state = SingCapsuleDismissState(dismissThresholdPx = 56f)
        state.begin(currentLevel = 0.5f)
        // 0.5 → 1.0 takes 100 px of a 200 px track: no overpull yet.
        val atTop = state.drag(translationX = 100f, trackWidthPx = 200f)
        assertEquals(update(1f), atTop)
        // 28 px past the top: half way to the threshold, elastic feedback.
        assertUpdate(1f, 0.5f, state.drag(translationX = 128f, trackWidthPx = 200f))
        // Back below the top: the level follows again, overpull gone.
        val back = state.drag(translationX = 60f, trackWidthPx = 200f) as SingCapsuleDismissState.StepResult.Update
        assertEquals(0.8f, back.level, 1e-4f)
        assertEquals(0f, back.overpullProgress, 0f)
        // 56 px past the top, same gesture, no release needed: Sing off.
        assertEquals(SingCapsuleDismissState.StepResult.Dismiss, state.drag(translationX = 156f, trackWidthPx = 200f))
        assertTrue(state.dismissed)
        // Stays dismissed for the rest of the gesture.
        assertEquals(SingCapsuleDismissState.StepResult.Dismiss, state.drag(translationX = 0f, trackWidthPx = 200f))
        state.end()
    }

    @Test
    fun should_dismissRightAway_when_alreadyAtMaximum() {
        val state = SingCapsuleDismissState(dismissThresholdPx = 56f)
        state.begin(currentLevel = 1f)
        assertUpdate(1f, 0.25f, state.drag(translationX = 14f, trackWidthPx = 200f))
        assertEquals(SingCapsuleDismissState.StepResult.Dismiss, state.drag(translationX = 60f, trackWidthPx = 200f))
        state.end()
        // A new gesture starts fresh.
        state.begin(currentLevel = 1f)
        assertEquals(update(1f), state.drag(translationX = 0f, trackWidthPx = 200f))
    }

    @Test
    fun should_snapToDefault_when_draggedNearTwelvePercent() {
        val state = SingCapsuleDismissState(dismissThresholdPx = 56f)
        state.begin(currentLevel = 0f)
        val near = SingGainCurve.positionFor(0.11f)
        val step = state.drag(translationX = near * 200f, trackWidthPx = 200f) as SingCapsuleDismissState.StepResult.Update
        assertEquals(SingGainCurve.DEFAULT_POSITION, step.level, 0f)
    }

    @Test
    fun should_followFingerAndDismissPastTop_when_absoluteSlider() {
        val state = SingCapsuleDismissState(dismissThresholdPx = 56f)
        state.begin(0f)
        val mid = state.at(rawLevel = 0.75f, trackPx = 400f) as SingCapsuleDismissState.StepResult.Update
        assertEquals(0.75f, mid.level, 1e-6f)
        assertEquals(update(0f), state.at(rawLevel = -0.5f, trackPx = 400f)) // far below the bottom
        assertUpdate(1f, 0.5f, state.at(rawLevel = 1.07f, trackPx = 400f)) // 28 px above the top
        assertEquals(SingCapsuleDismissState.StepResult.Dismiss, state.at(rawLevel = 1.14f, trackPx = 400f))
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
