package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The swap-in-place decision. The same rule hides the outgoing list on the
 * swap frame (2026-10-05 QA: both identical layers drew for one frame and the
 * half-transparent lines flashed darker), so it must only ever say yes when
 * the incoming list really opens where the outgoing one stopped.
 */
class UpNextHandoverTest {

    private val duration = 70_000L
    private val lyrics = listOf(
        LyricLine(startMs = 2_000L, text = "first"),
        LyricLine(startMs = 58_000L, text = "last"),
    )
    private val next = UpNextLyrics(
        songId = "second",
        title = "Second Platform",
        artist = "Night QA Band",
        lines = listOf(LyricLine(startMs = 2_000L, text = "hello")),
    )
    private val handoffAt = upNextTimingFor(lyrics, next, duration)!!.handoffAtMs

    @Test
    fun should_swapInPlace_when_theListHandedOverToTheIncomingSong() {
        assertTrue(upNextHandedOver(lyrics, next, duration, true, "second", handoffAt))
        // The last 250ms tick before the change can land just short of it.
        assertTrue(upNextHandedOver(lyrics, next, duration, true, "second", handoffAt - UpNextHandoffTickSlackMs))
    }

    @Test
    fun should_slide_when_theSongChangesBeforeTheHandover() {
        assertFalse(upNextHandedOver(lyrics, next, duration, true, "second", 30_000L))
    }

    @Test
    fun should_slide_when_anotherSongComesNext() {
        assertFalse(upNextHandedOver(lyrics, next, duration, true, "third", handoffAt + 100L))
    }

    @Test
    fun should_slide_when_theListWasNotFollowingThePlayhead() {
        assertFalse(upNextHandedOver(lyrics, next, duration, false, "second", handoffAt + 100L))
    }

    @Test
    fun should_slide_when_nothingWasStaged() {
        assertFalse(upNextHandedOver(lyrics, null, duration, true, "second", handoffAt + 100L))
    }
}
