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
                // The build's own order: the raw candidate (plays, rated tracks, song notes)
                // ahead of the legacy provider:raw album rating row, which read no tracks.
                candidate("legacy", average = 5f, ratedTracks = 2, notes = 3),
                candidate("subsonic:legacy", albumRating = 8f),
                candidate("noted", notes = 1),
                candidate("bare"),
                candidate("")
            )
        )

        assertEquals(setOf("legacy", "noted"), marks.keys)
        // Your album rating outranks the track average, and the notes stay counted.
        assertEquals(HomeAlbumMemoryMark(MemoryScoreKind.ALBUM_RATING, "8.0", 3), marks.getValue("legacy"))
        assertEquals(MemoryScoreKind.NONE, marks.getValue("noted").kind)
        assertEquals(1, marks.getValue("noted").noteCount)
    }

    @Test
    fun should_keepTheStrongerMark_when_anAlbumComesBackTwice() {
        val marks = homeAlbumMemoryMarks(
            listOf(
                // Same kind twice: the earlier (stronger in the build's order) score stays.
                candidate("twice", average = 7f, ratedTracks = 4),
                candidate("subsonic:twice", average = 6f, ratedTracks = 1, notes = 2),
                // Notes alone never cover a score that came first.
                candidate("scored", albumRating = 9f),
                candidate("subsonic:scored", notes = 4)
            )
        )

        assertEquals(HomeAlbumMemoryMark(MemoryScoreKind.AVERAGE_TRACK_RATING, "7.0", 2), marks.getValue("twice"))
        assertEquals(HomeAlbumMemoryMark(MemoryScoreKind.ALBUM_RATING, "9.0", 4), marks.getValue("scored"))
    }

    @Test
    fun should_matchSpotifyCovers_when_theirRawIdsAreMarked() {
        val rawId = "6dVIqQ8qmQ5GBnJ9shOYGE"
        val marks = homeAlbumMemoryMarks(
            listOf(candidate(rawId, albumRating = 8f, provider = MediaId.PROVIDER_SPOTIFY))
        )
        val card = albumCard(MediaId.spotify(rawId))

        assertEquals(setOf(rawId), marks.keys)
        assertEquals(marks.getValue(rawId), card.albumMemoryMark(marks))
        assertEquals(marks.getValue(rawId), album(MediaId.spotify(rawId)).albumMemoryMark(marks))
    }

    @Test
    fun should_matchAppleCovers_when_theirCatalogOrLibraryIdsAreMarked() {
        val apple = MediaId.PROVIDER_APPLE_MUSIC
        val marks = homeAlbumMemoryMarks(
            listOf(
                // A catalog album (ratings are kept under the id the album opens as).
                candidate("1440857781", albumRating = 9f, provider = apple),
                // An imported album Apple can't match keeps its library id, colon and all.
                candidate("library:l.imported", notes = 2, provider = apple),
                // A legacy row of the catalog album: only the owning provider's prefix goes.
                candidate("applemusic:1440857781", notes = 1, provider = apple)
            )
        )

        assertEquals(setOf("1440857781", "library:l.imported"), marks.keys)
        assertEquals(
            HomeAlbumMemoryMark(MemoryScoreKind.ALBUM_RATING, "9.0", 1),
            album(MediaId(apple, "1440857781")).albumMemoryMark(marks)
        )
        assertEquals(marks.getValue("1440857781"), albumCard(MediaId(apple, "1440857781")).albumMemoryMark(marks))
        assertEquals(
            marks.getValue("library:l.imported"),
            albumCard(MediaId(apple, "library:l.imported")).albumMemoryMark(marks)
        )
        // A library album that came without its catalog id doesn't borrow the catalog album's mark.
        assertNull(album(MediaId(apple, "library:l.1440857781")).albumMemoryMark(marks))
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

    private fun albumCard(rawId: String): HomeWidgetCard = albumCard(MediaId.subsonic(rawId))

    private fun albumCard(id: MediaId): HomeWidgetCard = HomeWidgetCard(
        stableId = "grid-album:$id",
        entityType = MemoryEntityType.ALBUM,
        title = "Album ${id.rawId}",
        subtitle = "",
        coverArtUrl = null,
        target = HomeWidgetTarget.AlbumDetail(id.toString())
    )

    private fun album(rawId: String): Album = album(MediaId.spotify(rawId))

    private fun album(id: MediaId): Album = Album(
        id = id,
        name = "Album ${id.rawId}",
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
        review: Boolean = false,
        provider: String = MediaId.PROVIDER_SUBSONIC
    ): AlbumMemoryCandidate = AlbumMemoryCandidate(
        profileId = "p",
        provider = provider,
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
