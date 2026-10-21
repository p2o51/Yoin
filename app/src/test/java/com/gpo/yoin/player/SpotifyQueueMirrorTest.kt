package com.gpo.yoin.player

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Yoin's copy of a Spotify queue against what App Remote reports playing: Add
 * to queue plays next, shuffle leaves every unplayed song to come, and a song
 * from outside the queue plays where Spotify plays it, with the queue after it.
 */
class SpotifyQueueMirrorTest {

    private val album = (1..5).map { track("a$it") }
    private val mirror = SpotifyQueueMirror()

    @Test
    fun should_listTheRestOfTheAlbum_when_playingInOrder() {
        mirror.reset(album, 0)
        mirror.onPlaying(album[0])

        val view = mirror.view(album[0], shuffle = false)

        assertEquals(0, view.currentIndex)
        assertEquals(listOf(1, 2, 3, 4), view.upcoming)
    }

    @Test
    fun should_playTheQueuedSongNext_when_addedToTheQueue() {
        val queued = track("q")
        mirror.reset(album, 0)
        mirror.onPlaying(album[0])

        mirror.add(queued, playing = album[0])
        val view = mirror.view(album[0], shuffle = false)

        assertEquals(listOf(album[0], queued) + album.drop(1), view.queue)
        assertEquals(setOf(1), view.userQueued)
        assertEquals(listOf(1, 2, 3, 4, 5), view.upcoming)
    }

    @Test
    fun should_keepTheRestOfTheAlbum_when_spotifyPlaysTheQueuedSong() {
        val queued = track("q")
        mirror.reset(album, 0)
        mirror.onPlaying(album[0])
        mirror.add(queued, playing = album[0])

        mirror.onPlaying(queued)
        val view = mirror.view(queued, shuffle = false)

        // It used to sit at the end: the queue jumped there and the album was gone.
        assertEquals(1, view.currentIndex)
        assertEquals(listOf(2, 3, 4, 5), view.upcoming)
        mirror.onPlaying(album[1])
        assertEquals(2, mirror.view(album[1], shuffle = false).currentIndex)
    }

    @Test
    fun should_queueAfterWhatTheUserQueuedBefore_when_addingAnotherSong() {
        val first = track("q1")
        val second = track("q2")
        mirror.reset(album, 0)
        mirror.onPlaying(album[0])

        mirror.add(first, playing = album[0])
        mirror.add(second, playing = album[0])

        assertEquals(listOf(album[0], first, second) + album.drop(1), mirror.tracks)
        assertEquals(setOf(1, 2), mirror.view(album[0], shuffle = false).userQueued)
    }

    @Test
    fun should_takeTheQueuedCopy_when_theQueuedSongIsAlsoLaterInTheAlbum() {
        mirror.reset(album, 0)
        mirror.onPlaying(album[0])
        mirror.add(album[3], playing = album[0])

        assertEquals(1, mirror.onPlaying(album[3]))
    }

    @Test
    fun should_listEveryUnplayedSong_when_spotifyShufflesOntoTheLastSong() {
        mirror.reset(album, 0)
        mirror.onPlaying(album[0])
        mirror.onPlaying(album[4])

        val view = mirror.view(album[4], shuffle = true)

        // It used to be empty: nothing comes after the list's last song.
        assertEquals(4, view.currentIndex)
        assertEquals(listOf(1, 2, 3), view.upcoming)
    }

    @Test
    fun should_listQueuedSongsFirst_when_shuffling() {
        val queued = track("q")
        mirror.reset(album, 2)
        mirror.onPlaying(album[2])
        mirror.add(queued, playing = album[2])

        val view = mirror.view(album[2], shuffle = true)

        assertEquals(listOf(3, 0, 1, 4, 5), view.upcoming)
    }

    @Test
    fun should_showTheSongFromOutsideAsPlaying_when_spotifyPlaysOneNotInTheQueue() {
        val elsewhere = track("elsewhere")
        mirror.reset(album, 0)
        mirror.onPlaying(album[0])
        mirror.onPlaying(album[1])

        assertEquals(-1, mirror.onPlaying(elsewhere))
        val view = mirror.view(elsewhere, shuffle = false)

        // It used to show the list's first song as the one playing.
        assertEquals(2, view.currentIndex)
        assertEquals(elsewhere, view.queue[view.currentIndex])
        assertEquals(2, view.foreignIndex)
        assertEquals(listOf(3, 4, 5), view.upcoming)
    }

    @Test
    fun should_carryOnAfterTheLastSongPlayed_when_spotifyComesBackFromOutside() {
        val elsewhere = track("elsewhere")
        mirror.reset(album, 0)
        mirror.onPlaying(album[1])
        mirror.onPlaying(elsewhere)

        assertEquals(2, mirror.onPlaying(album[2]))
        assertEquals(-1, mirror.view(album[2], shuffle = false).foreignIndex)
    }

    @Test
    fun should_showThePlayingSongAndTheQueued_when_addingToPlaybackAdoptedFromSpotify() {
        val playing = track("playing")
        val queued = track("q")

        mirror.add(queued, playing = playing)
        mirror.onPlaying(playing)
        val view = mirror.view(playing, shuffle = false)

        assertEquals(listOf(playing, queued), view.queue)
        assertEquals(0, view.currentIndex)
        assertEquals(setOf(1), view.userQueued)
    }

    @Test
    fun should_keepQueuedSongsNext_when_restartedWithThem() {
        val queued = track("q")

        mirror.reset(album, 3, queued = listOf(queued))

        assertEquals(listOf(album[0], album[1], album[2], album[3], queued, album[4]), mirror.tracks)
        assertEquals(listOf(4, 5), mirror.view(null, shuffle = false).upcoming)
    }

    @Test
    fun should_showOnlyThePlayingSong_when_nothingIsMirrored() {
        val playing = track("playing")

        val view = mirror.view(playing, shuffle = false)

        assertEquals(listOf(playing), view.queue)
        assertEquals(0, view.currentIndex)
    }

    private fun track(rawId: String) = Track(
        id = MediaId.spotify(rawId),
        title = rawId,
        artist = "TUIDE",
        artistId = MediaId.spotify("ar1"),
        album = "TUNE & PLAY",
        albumId = MediaId.spotify("al1"),
        coverArt = null,
        durationSec = 180,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )
}
