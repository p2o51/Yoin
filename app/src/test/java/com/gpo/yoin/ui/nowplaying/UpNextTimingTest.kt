package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpNextTimingTest {

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
    fun should_revealTenSecondsOutAndHandOverEarly_when_theOutroIsLong() {
        val timing = upNextTimingFor(lyricsEndingAt(200_000L), timedNext, duration)

        assertEquals(UpNextTiming(revealStartMs = 230_000L, handoffAtMs = 238_600L), timing)
    }

    @Test
    fun should_stillChoreograph_when_theLastLineStartsThreeSecondsBeforeTheEnd() {
        // Used to return null (no outro) — songs ending on their last word.
        val lastStart = duration - 3_000L
        val timing = upNextTimingFor(lyricsEndingAt(lastStart), timedNext, duration)

        assertNotNull(timing)
        timing!!
        assertTrue("reveal starts during the last line", timing.revealStartMs >= lastStart)
        assertTrue("hand-over lands before the end", timing.handoffAtMs <= duration - UpNextHandoffMinLeadMs)
        assertTrue(timing.revealStartMs < timing.handoffAtMs)
        assertEquals(duration - 1_000L, timing.handoffAtMs)
    }

    @Test
    fun should_revealWithTheLastLine_when_itStartsJustBeforeTheEnd() {
        val lastStart = duration - 1_500L
        val timing = upNextTimingFor(lyricsEndingAt(lastStart), timedNext, duration)!!

        assertEquals(lastStart, timing.revealStartMs)
        assertEquals(duration - UpNextHandoffMinLeadMs, timing.handoffAtMs)
    }

    @Test
    fun should_handOverInsideTheSongChangeCheck_when_anyTimingIsProduced() {
        for (lastStart in (duration - 60_000L)..(duration - UpNextHandoffMinLeadMs) step 100L) {
            val timing = upNextTimingFor(lyricsEndingAt(lastStart), timedNext, duration) ?: continue
            // The last 250ms tick before the change must already count as "handed over".
            val lastTick = duration - 250L
            assertTrue(lastTick >= timing.handoffAtMs - UpNextHandoffTickSlackMs)
            assertTrue(timing.revealStartMs >= lastStart)
            assertTrue(timing.revealStartMs <= timing.handoffAtMs)
        }
    }

    @Test
    fun should_skip_when_theLastLineStartsInsideTheFinalHandOverLead() {
        assertNull(upNextTimingFor(lyricsEndingAt(duration - 500L), timedNext, duration))
    }

    @Test
    fun should_skip_when_theNextSongsLyricsAreUntimed() {
        val untimed = timedNext.copy(lines = listOf(LyricLine(startMs = null, text = "plain")))

        assertNull(upNextTimingFor(lyricsEndingAt(200_000L), untimed, duration))
    }

    @Test
    fun should_skip_when_thereIsNoNextSongOrDuration() {
        assertNull(upNextTimingFor(lyricsEndingAt(200_000L), null, duration))
        assertNull(upNextTimingFor(lyricsEndingAt(200_000L), timedNext, 0L))
        assertNull(
            upNextTimingFor(listOf(LyricLine(startMs = null, text = "untimed")), timedNext, duration),
        )
    }
}
