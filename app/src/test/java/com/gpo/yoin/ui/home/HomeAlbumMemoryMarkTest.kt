package com.gpo.yoin.ui.home

import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryScoreKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which album covers wear a memory mark (owner 2026-10-11, T4), and what the mark carries. */
class HomeAlbumMemoryMarkTest {

    @Test
    fun should_markTheAlbumRatingSolid_when_theAlbumIsRated() {
        val mark = albumMemoryMarkOf(candidate("a", albumRating = 9f, average = 6.5f, ratedTracks = 4, notes = 2))

        assertEquals(HomeAlbumMemoryMark(MemoryScoreKind.ALBUM_RATING, scoreText = "9.0", noteCount = 2), mark)
    }

    @Test
    fun should_markTheTrackAverage_when_onlyTracksAreRated() {
        val mark = albumMemoryMarkOf(candidate("a", average = 7.44f, ratedTracks = 3))

        assertEquals(HomeAlbumMemoryMark(MemoryScoreKind.AVERAGE_TRACK_RATING, scoreText = "7.4", noteCount = 0), mark)
    }

    @Test
    fun should_markNotesWithoutAScore_when_onlyNotesAreKept() {
        val mark = albumMemoryMarkOf(candidate("a", notes = 3))

        assertEquals(HomeAlbumMemoryMark(MemoryScoreKind.NONE, scoreText = null, noteCount = 3), mark)
    }

    @Test
    fun should_leaveTheCoverBare_when_nothingIsWritten() {
        // Plays, a review alone, or an average with no rated track behind it.
        assertNull(albumMemoryMarkOf(candidate("plays")))
        assertNull(albumMemoryMarkOf(candidate("review", review = true)))
        assertNull(albumMemoryMarkOf(candidate("zero", albumRating = 0f, average = 0f)))
        assertNull(albumMemoryMarkOf(candidate("ghost", average = 8f, ratedTracks = 0)))
    }

    @Test
    fun should_keyMarksByRawAlbumId_when_thePoolMixesStoredForms() {
        val marks = homeAlbumMemoryMarks(
            listOf(
                // A legacy row keeps the provider prefix; the first build of an album wins.
                candidate("subsonic:legacy", albumRating = 8f),
                candidate("legacy", average = 5f, ratedTracks = 2),
                candidate("noted", notes = 1),
                candidate("bare"),
                candidate("")
            )
        )

        assertEquals(setOf("legacy", "noted"), marks.keys)
        assertEquals(MemoryScoreKind.ALBUM_RATING, marks.getValue("legacy").kind)
        assertEquals("8.0", marks.getValue("legacy").scoreText)
        assertEquals(MemoryScoreKind.NONE, marks.getValue("noted").kind)
        assertEquals(1, marks.getValue("noted").noteCount)
    }

    @Test
    fun should_markOnlyPlainAlbumCovers_when_jumpBackInMixesCards() {
        val marks = mapOf(
            "rated" to HomeAlbumMemoryMark(MemoryScoreKind.ALBUM_RATING, "9.0", 0),
            "song-album" to HomeAlbumMemoryMark(MemoryScoreKind.NONE, null, 2)
        )
        val plain = albumCard("rated")
        // The memory 1×2 shows its score already; songs and playlists never wear a mark.
        val memory = albumCard("rated").copy(
            stableId = "grid-memory:subsonic:rated",
            expanded = true,
            target = HomeWidgetTarget.MemoryFocus(sessionId = 1L)
        )
        val song = HomeWidgetCard(
            stableId = "grid-song:subsonic:s1",
            entityType = MemoryEntityType.SONG,
            title = "Song",
            subtitle = "",
            coverArtUrl = null,
            target = HomeWidgetTarget.PlaySong(track("s1", albumRawId = "song-album"))
        )
        val playlist = HomeWidgetCard(
            stableId = "grid-playlist:subsonic:rated",
            entityType = MemoryEntityType.PLAYLIST,
            title = "Playlist",
            subtitle = "",
            coverArtUrl = null,
            target = HomeWidgetTarget.PlaylistDetail("subsonic:rated")
        )

        assertEquals(marks.getValue("rated"), plain.albumMemoryMark(marks))
        assertNull(albumCard("unrated").albumMemoryMark(marks))
        assertNull(memory.albumMemoryMark(marks))
        assertNull(song.albumMemoryMark(marks))
        assertNull(playlist.albumMemoryMark(marks))
    }

    @Test
    fun should_markShelfAlbums_when_theirRawIdHasAMark() {
        val mark = HomeAlbumMemoryMark(MemoryScoreKind.AVERAGE_TRACK_RATING, "7.0", 1)
        val marks = mapOf("x1" to mark)

        assertEquals(mark, album("x1").albumMemoryMark(marks))
        assertNull(album("x2").albumMemoryMark(marks))
        assertNull(album("x1").albumMemoryMark(emptyMap()))
    }

    private fun albumCard(rawId: String): HomeWidgetCard = HomeWidgetCard(
        stableId = "grid-album:subsonic:$rawId",
        entityType = MemoryEntityType.ALBUM,
        title = "Album $rawId",
        subtitle = "",
        coverArtUrl = null,
        target = HomeWidgetTarget.AlbumDetail(MediaId.subsonic(rawId).toString())
    )

    private fun album(rawId: String): Album = Album(
        id = MediaId.spotify(rawId),
        name = "Album $rawId",
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = 10,
        durationSec = null,
        year = null,
        genre = null
    )

    private fun track(rawId: String, albumRawId: String): Track = Track(
        id = MediaId.subsonic(rawId),
        title = "Song $rawId",
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = MediaId.subsonic(albumRawId),
        coverArt = null,
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )

    private fun candidate(
        albumId: String,
        albumRating: Float? = null,
        average: Float? = null,
        ratedTracks: Int = if (average != null) 1 else 0,
        notes: Int = 0,
        review: Boolean = false
    ): AlbumMemoryCandidate = AlbumMemoryCandidate(
        profileId = "p",
        provider = MediaId.PROVIDER_SUBSONIC,
        albumId = albumId,
        albumName = "Album $albumId",
        artistName = "Artist",
        totalTracks = 10,
        ratedTrackCount = ratedTracks,
        ratingCoverage = ratedTracks / 10f,
        averageSongRating = average,
        albumRating = albumRating,
        hasAlbumReview = review,
        noteCount = notes,
        askAiCount = 0,
        firstPlayedAt = null,
        lastPlayedAt = null,
        playCount = 3,
        neoDbSynced = false,
        isMemoryEligible = false,
        year = null,
        durationSeconds = null,
        coverArtUrl = null
    )
}
