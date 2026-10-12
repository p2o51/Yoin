package com.gpo.yoin.player

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [startWindow] / [startWindowQueue]: 100 songs (or the whole list when shorter), 20 of them before the start where it can. */
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
    fun should_reachFurtherBack_when_startIsNearTheEnd() {
        val queue = startWindowQueue(long, startIndex = 249)

        // The window doesn't shrink at the end of the list: it still holds 100.
        assertEquals((150 until 250).map { "s$it" }, queue.tracks.map { it.id.rawId })
        assertEquals(99, queue.startIndex)
        assertEquals("s249", queue.tracks[queue.startIndex].id.rawId)
    }

    @Test
    fun should_keepTheWholeList_when_itIsShorterThanTheWindow() {
        val short = long.take(60)

        val fromStart = startWindowQueue(short, startIndex = 0)
        val fromEnd = startWindowQueue(short, startIndex = 59)

        assertEquals(short, fromStart.tracks)
        assertEquals(0, fromStart.startIndex)
        assertEquals(short, fromEnd.tracks)
        assertEquals(59, fromEnd.startIndex)
    }

    @Test
    fun should_keepAllFiftySongs_when_subsonicRandomListStartsNearItsEnd() {
        // Subsonic's Songs tab is getRandomSongs(50): a late row still repeats and shuffles over all 50.
        val random = long.take(50)

        val queue = startWindowQueue(random, startIndex = 40)

        assertEquals(random, queue.tracks)
        assertEquals(40, queue.startIndex)
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
    fun should_holdAFullWindowWithinTheSpotifyUriLimit_when_anyStartIsPicked() {
        for (size in listOf(1, 50, 100, 101, 250)) {
            for (start in 0 until size) {
                val window = startWindow(size, start)
                assertEquals(minOf(size, SPOTIFY_START_MAX_URIS), window.count())
                assertTrue(start in window)
                assertTrue(start - window.first >= minOf(start, SPOTIFY_START_HISTORY))
            }
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
