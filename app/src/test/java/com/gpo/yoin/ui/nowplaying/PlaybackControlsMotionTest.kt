package com.gpo.yoin.ui.nowplaying

import android.content.Context
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.R
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ticket 4 (Galaxy S26 recording): every PLAY ⇄ PAUSE cut the pill's width by
 * ~20dp in ONE frame and threw NEXT sideways with it — ButtonGroup sizes items
 * from their intrinsic width, which animateContentSize never animated. The
 * pill must glide over frames in both the word (English) and symbol modes.
 * Native graphics: the words need real text measurement.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackControlsMotionTest {

    @get:Rule
    val rule = createComposeRule()

    private var playing by mutableStateOf(false)
    private val playSource = MutableInteractionSource()
    private val nextSource = MutableInteractionSource()

    @Test
    fun should_glideThePill_when_playbackStartsInWordMode() {
        assertPillGlides(from = false, to = true)
    }

    @Test
    fun should_glideThePill_when_playbackPausesInWordMode() {
        assertPillGlides(from = true, to = false)
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun should_glideThePill_when_playbackStartsInSymbolMode() {
        assertPillGlides(from = false, to = true)
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun should_glideThePill_when_playbackPausesInSymbolMode() {
        assertPillGlides(from = true, to = false)
    }

    @Test
    fun should_widenNext_when_nextIsPressed() {
        playing = true
        setControls()
        val rest = nextBounds().width
        assertEquals("NEXT rests as a whole circle", 56f, rest.value, 0.5f)

        // The group's own press language: the pressed item grows, its
        // neighbour yields — NEXT never shrinks under the finger.
        nextSource.tryEmit(PressInteraction.Press(Offset.Zero))
        rule.waitForIdle()

        assertTrue("NEXT grows while pressed (${nextBounds().width} vs $rest)", nextBounds().width > rest)
    }

    private fun assertPillGlides(from: Boolean, to: Boolean) {
        playing = from
        setControls()
        val start = nextBounds().left.value

        rule.mainClock.autoAdvance = false
        playing = to
        val lefts = mutableListOf(start)
        repeat(FRAMES) {
            rule.mainClock.advanceTimeByFrame()
            lefts += nextBounds().left.value
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val end = nextBounds().left.value

        val travel = abs(end - start)
        assertTrue("PLAY and PAUSE pills differ in width ($travel dp)", travel > 20f)
        val biggestStep = lefts.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue(
            "the pill moves over frames: biggest one-frame step $biggestStep dp of $travel dp ($lefts)",
            biggestStep < travel * MAX_SHARE_OF_TRAVEL_PER_FRAME
        )
        assertEquals("the pill has settled within $FRAMES frames", end, lefts.last(), 0.5f)
        assertEquals("NEXT stays a whole circle", 56f, nextBounds().width.value, 0.5f)
    }

    private fun setControls() {
        rule.setContent {
            YoinTheme {
                Box(modifier = Modifier.width(400.dp)) {
                    PlaybackControls(
                        isPlaying = playing,
                        onTogglePlayPause = {},
                        onSkipNext = {},
                        onSkipPrevious = {},
                        positionMs = 0L,
                        durationMs = 200_000L,
                        progress = 0f,
                        buffered = 0f,
                        onSeek = {},
                        playInteractionSource = playSource,
                        nextInteractionSource = nextSource,
                        playPressed = false,
                        // Keep the wave still so the frame loop only sees the pill.
                        live = false
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun nextBounds(): DpRect = rule
        .onNodeWithContentDescription(
            ApplicationProvider.getApplicationContext<Context>().getString(R.string.np_cd_skip_next)
        )
        .getBoundsInRoot()

    private companion object {
        /** ~1s at 60fps: the Standard fast spatial spring has long settled. */
        const val FRAMES = 60

        /**
         * That spring moves at most ~23% of its travel in one 16ms frame; the
         * old pill cut ~55% (the word's whole width change) in its first.
         */
        const val MAX_SHARE_OF_TRAVEL_PER_FRAME = 0.35f
    }
}
