package com.gpo.yoin.player

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [startWindow] / [startWindowQueue]: at most 20 songs of history, 100 in all. */
class StartWindowTest {

    private val long = (0 until 250).map(::song)

    @Test
    fun should_keepNoHistory_when_startIsTheFirstSong() {
        val queue = startWindowQueue(long, startIndex = 0)

        assertEquals((0 until 100).map { "s$it" }, queue.tracks.map { it.id.rawId })
        assertEquals(0, queue.startIndex)
    }

    @Test
    fun should_keepTheSongsBefore_when_startIsWithinTheFirstTwenty() {
        val queue = startWindowQueue(long, startIndex = 12)

        assertEquals((0 until 100).map { "s$it" }, queue.tracks.map { it.id.rawId })
        assertEquals(12, queue.startIndex)
    }

    @Test
    fun should_centreOnTheStartWithTwentyBefore_when_startIsInTheMiddle() {
        val queue = startWindowQueue(long, startIndex = 150)

        assertEquals((130 until 230).map { "s$it" }, queue.tracks.map { it.id.rawId })
        assertEquals(20, queue.startIndex)
        assertEquals("s150", queue.tracks[queue.startIndex].id.rawId)
    }

    @Test
    fun should_endWithTheList_when_startIsTheLastSong() {
        val queue = startWindowQueue(long, startIndex = 249)

        // Twenty songs of history, never more: the window doesn't reach back to fill up to 100.
        assertEquals((229 until 250).map { "s$it" }, queue.tracks.map { it.id.rawId })
        assertEquals(20, queue.startIndex)
        assertEquals("s249", queue.tracks[queue.startIndex].id.rawId)
    }

    @Test
    fun should_keepTheWholeList_when_itIsShorterThanTheWindow() {
        val short = long.take(60)

        val fromStart = startWindowQueue(short, startIndex = 0)
        val fromEnd = startWindowQueue(short, startIndex = 59)

        assertEquals(short, fromStart.tracks)
        assertEquals(0, fromStart.startIndex)
        // Past twenty songs in, the history before the window is dropped.
        assertEquals(short.drop(39), fromEnd.tracks)
        assertEquals(20, fromEnd.startIndex)
    }

    @Test
    fun should_returnTheListAsGiven_when_startIsOutsideIt() {
        val short = long.take(3)

        val queue = startWindowQueue(short, startIndex = 3)

        assertSame(short, queue.tracks)
        assertEquals(3, queue.startIndex)
        assertTrue(startWindow(size = 0, startIndex = 0).isEmpty())
    }

    @Test
    fun should_neverExceedTheSpotifyUriLimit_when_anyStartIsPicked() {
        for (start in long.indices) {
            val window = startWindow(long.size, start)
            assertTrue(window.count() <= SPOTIFY_START_MAX_URIS)
            assertTrue(start in window)
            assertTrue(start - window.first <= SPOTIFY_START_HISTORY)
        }
    }

    private fun song(index: Int) = Track(
        id = MediaId.subsonic("s$index"),
        title = "Song $index",
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = null,
        coverArt = null,
        durationSec = 200,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )
}
