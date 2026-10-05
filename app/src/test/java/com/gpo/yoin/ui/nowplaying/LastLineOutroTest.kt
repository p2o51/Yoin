package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L5 (owner pick 2026-10-05, I-2 "同时下一首慢慢浮上来"): the last line's
 * stretch and the next song's rise share ONE outro window, which now opens as
 * the last line takes the stage instead of two seconds after it.
 */
class LastLineOutroTest {

    private val duration = 240_000L

    private fun lyricsEndingAt(lastStartMs: Long) = listOf(
        LyricLine(startMs = 10_000L, text = "first"),
        LyricLine(startMs = lastStartMs, text = "last"),
    )

    private val timedNext = UpNextLyrics(
        songId = "next",
        title = "Next",
        artist = "Artist",
        lines = listOf(LyricLine(startMs = 5_000L, text = "hello")),
    )

    @Test
    fun should_revealWithTheLastLine_when_itStartsInsideTheRevealLead() {
        val lastStart = duration - 6_000L
        val timing = upNextTimingFor(lyricsEndingAt(lastStart), timedNext, duration)!!

        // Was lastStart + 2s: the next song now floats up while the line is held.
        assertEquals(lastStart, timing.revealStartMs)
        assertEquals(duration - UpNextHandoffLeadMs, timing.handoffAtMs)
    }

    @Test
    fun should_waitForTheRevealLead_when_theOutroIsALongInstrumental() {
        val timing = upNextTimingFor(lyricsEndingAt(150_000L), timedNext, duration)!!

        assertEquals(duration - UpNextRevealLeadMs, timing.revealStartMs)
    }

    @Test
    fun should_stretchOverTheRevealWindow_when_aNextSongIsStaged() {
        for (lastStart in (duration - 60_000L)..(duration - UpNextHandoffMinLeadMs) step 250L) {
            val lyrics = lyricsEndingAt(lastStart)
            val staged = upNextTimingFor(lyrics, timedNext, duration) ?: continue
            assertEquals(staged, lastLineOutroFor(lyrics, duration))
            assertTrue(staged.revealStartMs >= lastStart)
            assertTrue(staged.revealStartMs <= staged.handoffAtMs)
        }
    }

    @Test
    fun should_stillHoldTheLastLine_when_nothingIsStagedNext() {
        val lyrics = lyricsEndingAt(duration - 6_000L)

        assertNull(upNextTimingFor(lyrics, null, duration))
        assertEquals(
            UpNextTiming(revealStartMs = duration - 6_000L, handoffAtMs = duration - UpNextHandoffLeadMs),
            lastLineOutroFor(lyrics, duration),
        )
    }

    @Test
    fun should_skipTheOutro_when_theLyricsAreUntimedOrTheDurationUnknown() {
        assertNull(lastLineOutroFor(listOf(LyricLine(startMs = null, text = "plain")), duration))
        assertNull(lastLineOutroFor(lyricsEndingAt(200_000L), 0L))
        assertNull(lastLineOutroFor(lyricsEndingAt(duration - 500L), duration))
    }

    @Test
    fun should_quantizeTheOutroProgress_when_thePlayheadMovesThroughTheWindow() {
        val timing = UpNextTiming(revealStartMs = 0L, handoffAtMs = 2_400L)

        assertEquals(0f, timing.outroProgress(-5_000L), 0f)
        assertEquals(0f, timing.outroProgress(40L), 0f)
        assertEquals(1f / 24f, timing.outroProgress(100L), 1e-6f)
        assertEquals(0.5f, timing.outroProgress(1_200L), 1e-6f)
        assertEquals(1f, timing.outroProgress(9_000L), 0f)
        // Only 25 distinct values across the window: at most 24 recompositions.
        val steps = (0L..2_400L step 10L).map { timing.outroProgress(it) }.toSet()
        assertEquals(UpNextRevealSteps + 1, steps.size)
    }

    @Test
    fun should_widenByScaleUpToEightPercent_when_theLineLeavesRoom() {
        assertEquals(LastLineStretchMode.Scale, lastLineStretchMode(0.5f))
        assertEquals(1f, lastLineStretchScale(0f, 0.5f), 0f)
        assertEquals(1.04f, lastLineStretchScale(0.5f, 0.5f), 1e-6f)
        assertEquals(1.08f, lastLineStretchScale(1f, 0.5f), 1e-6f)
        // At the threshold the widened line still fits its row.
        assertTrue(lastLineStretchScale(1f, LastLineStretchFullUsage) * LastLineStretchFullUsage <= 1f)
    }

    @Test
    fun should_followTheSpringOvershoot_when_theStretchLetsGo() {
        // The default spatial spring rebounds a hair past rest on release.
        assertTrue(lastLineStretchScale(-0.01f, 0.5f) < 1f)
    }

    @Test
    fun should_widenByLetterSpacing_when_theLineIsNearlyFullWidth() {
        assertEquals(LastLineStretchMode.LetterSpacing, lastLineStretchMode(0.95f))
        assertEquals(1f, lastLineStretchScale(1f, 0.95f), 0f)
        assertEquals(0f, lastLineLetterSpacingEm(0f), 0f)
        assertEquals(0.03f, lastLineLetterSpacingEm(0.5f), 1e-6f)
        assertEquals(LastLineStretchLetterSpacingEm, lastLineLetterSpacingEm(1.2f), 0f)
    }

    @Test
    fun should_holdStill_when_theLineIsNotMeasuredYet() {
        assertEquals(LastLineStretchMode.Unmeasured, lastLineStretchMode(0f))
        assertEquals(1f, lastLineStretchScale(1f, 0f), 0f)
    }
}
