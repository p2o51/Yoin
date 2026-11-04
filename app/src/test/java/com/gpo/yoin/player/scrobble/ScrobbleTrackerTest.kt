package com.gpo.yoin.player.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrobbleTrackerTest {
    private var now = 0L
    private val tracker = ScrobbleTracker(monotonicMs = { now }, wallMs = { 1_000_000L + now })

    private fun snap(
        playing: Boolean,
        song: String = "s1",
        key: String = "e1#0",
        durationMs: Long = 200_000L,
        profile: String = "p1",
    ) = ScrobbleSnapshot(profile, song, key, playing, positionMs = now, durationMs = durationMs)

    private fun List<ScrobbleEvent>.submits() = filterIsInstance<ScrobbleEvent.Submit>()

    @Test
    fun `rule is half the song or four minutes and skips short songs`() {
        val rule = ScrobbleRule()
        assertEquals(100_000L, rule.thresholdMs(200_000L))
        assertEquals(240_000L, rule.thresholdMs(600_000L))
        assertNull(rule.thresholdMs(30_000L))
        assertEquals(240_000L, rule.thresholdMs(0L))
    }

    @Test
    fun `start announces now playing once and submits at half`() {
        val started = tracker.update(snap(playing = true))
        assertEquals(ScrobbleEvent.Started("p1", "s1", 0L), started.first())
        assertEquals(100_000L, tracker.msUntilSubmit())

        now = 99_999L
        assertTrue(tracker.update(snap(playing = true)).submits().isEmpty())
        now = 100_000L
        val due = tracker.update(snap(playing = true)).submits()
        assertEquals(listOf(ScrobbleEvent.Submit("p1", "s1", 1_000_000L)), due)

        now = 190_000L
        assertTrue(tracker.update(snap(playing = true)).submits().isEmpty())
        assertNull(tracker.msUntilSubmit())
    }

    @Test
    fun `paused time does not count`() {
        tracker.update(snap(playing = true))
        now = 60_000L
        tracker.update(snap(playing = false))
        now = 600_000L
        assertNull(tracker.msUntilSubmit())
        assertTrue(tracker.update(snap(playing = false)).submits().isEmpty())
        tracker.update(snap(playing = true))
        assertEquals(40_000L, tracker.msUntilSubmit())
        now += 40_000L
        assertEquals(1, tracker.update(snap(playing = true)).submits().size)
    }

    @Test
    fun `skipping early counts nothing and stops the old song`() {
        tracker.update(snap(playing = true))
        now = 20_000L
        val events = tracker.update(snap(playing = true, song = "s2", key = "e2#0"))
        assertTrue(events.submits().isEmpty())
        assertEquals(
            ScrobbleEvent.StateChanged("p1", "s1", PlaybackReportState.STOPPED, 0L),
            events.first(),
        )
        assertTrue(events.any { it is ScrobbleEvent.Started && it.songId == "s2" })
    }

    @Test
    fun `a song never played is never announced`() {
        assertTrue(tracker.update(snap(playing = false)).isEmpty())
        assertTrue(tracker.update(null).isEmpty())
    }

    @Test
    fun `a repeat of the same song counts again`() {
        tracker.update(snap(playing = true))
        now = 100_000L
        assertEquals(1, tracker.update(snap(playing = true)).submits().size)
        now = 200_000L
        tracker.update(snap(playing = true, key = "e1#1"))
        now = 300_000L
        assertEquals(1, tracker.update(snap(playing = true, key = "e1#1")).submits().size)
    }

    @Test
    fun `a short song never counts`() {
        tracker.update(snap(playing = true, durationMs = 25_000L))
        assertNull(tracker.msUntilSubmit())
        now = 25_000L
        assertTrue(tracker.update(null).submits().isEmpty())
    }

    @Test
    fun `a song reaching its due point while switching away still counts`() {
        tracker.update(snap(playing = true))
        now = 120_000L
        val events = tracker.update(snap(playing = true, song = "s2", key = "e2#0"))
        assertEquals(listOf(ScrobbleEvent.Submit("p1", "s1", 1_000_000L)), events.submits())
    }

    @Test
    fun `pause and resume are reported after the start`() {
        val start = tracker.update(snap(playing = true))
        assertEquals(PlaybackReportState.PLAYING, (start[1] as ScrobbleEvent.StateChanged).state)
        now = 5_000L
        val pause = tracker.update(snap(playing = false))
        assertEquals(PlaybackReportState.PAUSED, (pause.single() as ScrobbleEvent.StateChanged).state)
        now = 6_000L
        val resume = tracker.update(snap(playing = true))
        assertEquals(PlaybackReportState.PLAYING, (resume.single() as ScrobbleEvent.StateChanged).state)
    }
}
