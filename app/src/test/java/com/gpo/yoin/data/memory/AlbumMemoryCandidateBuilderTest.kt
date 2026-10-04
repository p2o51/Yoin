package com.gpo.yoin.data.memory

import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.local.ActivityEventDao
import com.gpo.yoin.data.local.AlbumNoteCount
import com.gpo.yoin.data.local.AlbumNoteDao
import com.gpo.yoin.data.local.AlbumPlayHistoryAggregate
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.AlbumRatingDao
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.LocalRatingDao
import com.gpo.yoin.data.local.AskRowCount
import com.gpo.yoin.data.local.PlayHistoryDao
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongAboutEntryDao
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.local.SongNoteDao
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.MusicLibrary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumMemoryCandidateBuilderTest {
    private val library = mockk<MusicLibrary>()
    private val playHistoryDao = mockk<PlayHistoryDao>()
    private val activityEventDao = mockk<ActivityEventDao>()
    private val localRatingDao = mockk<LocalRatingDao>()
    private val albumRatingDao = mockk<AlbumRatingDao>()
    private val albumNoteDao = mockk<AlbumNoteDao>()
    private val songNoteDao = mockk<SongNoteDao>()
    private val songAboutEntryDao = mockk<SongAboutEntryDao>()

    @Test
    fun should_create_candidate_when_rating_coverage_reaches_sixty_percent() = runTest {
        val album = album(trackCount = 10)
        stubBase(album)
        coEvery {
            localRatingDao.getRatings(any(), MediaId.PROVIDER_SUBSONIC, "profile-a")
        } returns (1..6).map { index ->
            LocalRating(
                profileId = "profile-a",
                songId = "track-$index",
                provider = MediaId.PROVIDER_SUBSONIC,
                rating = 8f,
                serverRating = 4,
            )
        }

        val candidates = builder().build(limit = 6)

        val candidate = candidates.single()
        assertTrue(candidate.isMemoryEligible)
        assertEquals(6, candidate.ratedTrackCount)
        assertEquals(0.6f, candidate.ratingCoverage, 0.001f)
        assertEquals(8f, candidate.averageSongRating ?: 0f, 0.001f)
    }

    @Test
    fun should_create_candidate_when_album_review_exists_below_rating_gate() = runTest {
        val album = album(trackCount = 10)
        stubBase(
            album = album,
            albumRatings = listOf(
                AlbumRating(
                    profileId = "profile-a",
                    albumId = "album-1",
                    provider = MediaId.PROVIDER_SUBSONIC,
                    rating = 7f,
                    review = "review",
                    neoDbReviewUuid = null,
                ),
            ),
        )
        coEvery {
            localRatingDao.getRatings(any(), MediaId.PROVIDER_SUBSONIC, "profile-a")
        } returns emptyList()

        val candidate = builder().build(limit = 6).single()

        assertTrue(candidate.isMemoryEligible)
        assertTrue(candidate.hasAlbumReview)
        assertEquals(0f, candidate.ratingCoverage, 0.001f)
    }

    @Test
    fun should_use_notes_as_gate_and_count_ask_ai_as_reference_only() = runTest {
        val album = album(trackCount = 10)
        stubBase(
            album = album,
            noteCounts = listOf(
                AlbumNoteCount(
                    albumId = "album-1",
                    provider = MediaId.PROVIDER_SUBSONIC,
                    noteCount = 2,
                ),
            ),
        )
        coEvery {
            localRatingDao.getRatings(any(), MediaId.PROVIDER_SUBSONIC, "profile-a")
        } returns emptyList()
        coEvery { songAboutEntryDao.countAskRowsByAlbum(any()) } returns (1..10).map { index ->
            AskRowCount(
                titleKey = SongAboutEntry.normalize("Track $index"),
                artistKey = SongAboutEntry.normalize("Artist One"),
                askCount = 1,
            )
        }

        val candidate = builder().build(limit = 6).single()

        assertTrue(candidate.isMemoryEligible)
        assertEquals(2, candidate.noteCount)
        assertEquals(10, candidate.askAiCount)
        assertEquals(null, candidate.averageSongRating)
    }

    @Test
    fun should_keep_candidate_queries_profile_scoped() = runTest {
        val album = album(trackCount = 2)
        stubBase(album)
        coEvery {
            localRatingDao.getRatings(any(), MediaId.PROVIDER_SUBSONIC, "profile-a")
        } returns listOf(
            LocalRating(
                profileId = "profile-a",
                songId = "track-1",
                provider = MediaId.PROVIDER_SUBSONIC,
                rating = 8f,
                serverRating = 4,
            ),
            LocalRating(
                profileId = "profile-a",
                songId = "track-2",
                provider = MediaId.PROVIDER_SUBSONIC,
                rating = 7f,
                serverRating = 4,
            ),
        )

        builder().build(limit = 6)

        coVerify {
            playHistoryDao.getAlbumAggregates("profile-a", MediaId.PROVIDER_SUBSONIC, any())
            albumRatingDao.getAllForProfile(MediaId.PROVIDER_SUBSONIC, "profile-a")
            albumNoteDao.getNoteCountsForProfile(MediaId.PROVIDER_SUBSONIC, "profile-a")
            songNoteDao.getForTracks(any(), MediaId.PROVIDER_SUBSONIC, "profile-a")
        }
    }

    @Test
    fun should_not_create_candidate_from_playback_only() = runTest {
        val album = album(trackCount = 10)
        stubBase(album)
        coEvery {
            localRatingDao.getRatings(any(), MediaId.PROVIDER_SUBSONIC, "profile-a")
        } returns emptyList()

        val candidates = builder().build(limit = 6)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun should_not_create_candidate_from_ask_ai_only() = runTest {
        val album = album(trackCount = 10)
        stubBase(album)
        coEvery {
            localRatingDao.getRatings(any(), MediaId.PROVIDER_SUBSONIC, "profile-a")
        } returns emptyList()
        coEvery { songAboutEntryDao.countAskRowsByAlbum(any()) } returns (1..10).map { index ->
            AskRowCount(
                titleKey = SongAboutEntry.normalize("Track $index"),
                artistKey = SongAboutEntry.normalize("Artist One"),
                askCount = 1,
            )
        }

        val candidates = builder().build(limit = 6)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun should_setLastWrittenAtToNewestWrite_when_ratingsNotesAndReviewExist() = runTest {
        val album = album(trackCount = 10)
        stubBase(
            album = album,
            albumRatings = listOf(
                AlbumRating(
                    profileId = "profile-a",
                    albumId = "album-1",
                    provider = MediaId.PROVIDER_SUBSONIC,
                    rating = 8f,
                    review = "review",
                    neoDbReviewUuid = null,
                    updatedAt = 4_000L,
                ),
            ),
        )
        coEvery {
            localRatingDao.getRatings(any(), MediaId.PROVIDER_SUBSONIC, "profile-a")
        } returns listOf(
            trackRating(trackIndex = 1, rating = 8f, updatedAt = 2_000L),
            trackRating(trackIndex = 2, rating = 7f, updatedAt = 5_000L),
        )
        coEvery {
            songNoteDao.getForTracks(any(), MediaId.PROVIDER_SUBSONIC, "profile-a")
        } returns listOf(
            songNote(id = "n1", trackIndex = 3, content = "the bridge", updatedAt = 6_000L),
            songNote(id = "n2", trackIndex = 4, content = "opener", updatedAt = 3_000L),
        )

        val candidate = builder().build(limit = 6).single()

        assertEquals(6_000L, candidate.lastWrittenAt)
        assertEquals(2, candidate.noteCount)
    }

    @Test
    fun should_ignoreBlankNotes_when_computingLastWrittenAt() = runTest {
        val album = album(trackCount = 10)
        stubBase(album)
        coEvery {
            localRatingDao.getRatings(any(), MediaId.PROVIDER_SUBSONIC, "profile-a")
        } returns emptyList()
        coEvery {
            songNoteDao.getForTracks(any(), MediaId.PROVIDER_SUBSONIC, "profile-a")
        } returns listOf(
            songNote(id = "n1", trackIndex = 1, content = "first", updatedAt = 2_000L),
            songNote(id = "n2", trackIndex = 2, content = "second", updatedAt = 3_000L),
            songNote(id = "n3", trackIndex = 3, content = "   ", updatedAt = 9_000L),
        )

        val candidate = builder().build(limit = 6).single()

        assertEquals(3_000L, candidate.lastWrittenAt)
        // Count semantics unchanged: the blank note counts toward neither value.
        assertEquals(2, candidate.noteCount)
    }

    @Test
    fun should_ignoreEmptyAlbumRatingRow_when_computingLastWrittenAt() = runTest {
        val album = album(trackCount = 10)
        stubBase(
            album = album,
            albumRatings = listOf(
                // A cleared row: no score, no review. Its updatedAt is not a write.
                AlbumRating(
                    profileId = "profile-a",
                    albumId = "album-1",
                    provider = MediaId.PROVIDER_SUBSONIC,
                    rating = 0f,
                    review = null,
                    neoDbReviewUuid = null,
                    updatedAt = 9_000L,
                ),
            ),
        )
        coEvery {
            localRatingDao.getRatings(any(), MediaId.PROVIDER_SUBSONIC, "profile-a")
        } returns (1..6).map { index ->
            trackRating(trackIndex = index, rating = 8f, updatedAt = 1_000L * index)
        }

        val candidate = builder().build(limit = 6).single()

        assertEquals(6_000L, candidate.lastWrittenAt)
        assertNull(candidate.albumRating)
    }

    @Test
    fun should_leaveLastWrittenAtNull_when_onlyPlaysExist() = runTest {
        val album = album(trackCount = 10)
        // Eligible through the album-note gate only: album notes carry no
        // timestamp into lastWrittenAt (nothing writes them yet), so the album's
        // remaining signals are plays (lastPlayedAt = 300) and a newer visit.
        stubBase(
            album = album,
            noteCounts = listOf(
                AlbumNoteCount(
                    albumId = "album-1",
                    provider = MediaId.PROVIDER_SUBSONIC,
                    noteCount = 2,
                ),
            ),
        )
        coEvery {
            activityEventDao.getRecentAlbumEvents("profile-a", MediaId.PROVIDER_SUBSONIC, any())
        } returns listOf(
            ActivityEvent(
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.VISITED.name,
                entityId = "album-1",
                profileId = "profile-a",
                provider = MediaId.PROVIDER_SUBSONIC,
                title = "Album One",
                subtitle = "Artist One",
                timestamp = 9_000L,
            ),
        )
        coEvery {
            localRatingDao.getRatings(any(), MediaId.PROVIDER_SUBSONIC, "profile-a")
        } returns emptyList()

        val candidate = builder().build(limit = 6).single()

        assertNull(candidate.lastWrittenAt)
        assertEquals(9_000L, candidate.lastPlayedAt)
    }

    private fun trackRating(trackIndex: Int, rating: Float, updatedAt: Long): LocalRating = LocalRating(
        profileId = "profile-a",
        songId = "track-$trackIndex",
        provider = MediaId.PROVIDER_SUBSONIC,
        rating = rating,
        serverRating = 4,
        updatedAt = updatedAt,
    )

    private fun songNote(id: String, trackIndex: Int, content: String, updatedAt: Long): SongNote = SongNote(
        id = id,
        profileId = "profile-a",
        trackId = "track-$trackIndex",
        provider = MediaId.PROVIDER_SUBSONIC,
        content = content,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        title = "Track $trackIndex",
        artist = "Artist One",
    )

    private fun builder(): AlbumMemoryCandidateBuilder =
        AlbumMemoryCandidateBuilder(
            profileId = "profile-a",
            provider = MediaId.PROVIDER_SUBSONIC,
            getAlbum = { id -> library.getAlbum(id) },
            playHistoryDao = playHistoryDao,
            activityEventDao = activityEventDao,
            localRatingDao = localRatingDao,
            albumRatingDao = albumRatingDao,
            albumNoteDao = albumNoteDao,
            songNoteDao = songNoteDao,
            songAboutEntryDao = songAboutEntryDao,
            resolveCoverUrl = { _, _ -> null },
        )

    private fun stubBase(
        album: Album,
        albumRatings: List<AlbumRating> = emptyList(),
        noteCounts: List<AlbumNoteCount> = emptyList(),
    ) {
        coEvery { library.getAlbum(MediaId.subsonic("album-1")) } returns album
        coEvery {
            playHistoryDao.getAlbumAggregates("profile-a", MediaId.PROVIDER_SUBSONIC, any())
        } returns listOf(
            AlbumPlayHistoryAggregate(
                albumId = "album-1",
                provider = MediaId.PROVIDER_SUBSONIC,
                albumName = "Album One",
                artistName = "Artist One",
                coverArtId = null,
                playCount = 3,
                firstPlayedAt = 100L,
                lastPlayedAt = 300L,
            ),
        )
        coEvery {
            activityEventDao.getRecentAlbumEvents("profile-a", MediaId.PROVIDER_SUBSONIC, any())
        } returns emptyList()
        coEvery {
            albumRatingDao.getAllForProfile(MediaId.PROVIDER_SUBSONIC, "profile-a")
        } returns albumRatings
        coEvery {
            albumRatingDao.get("album-1", MediaId.PROVIDER_SUBSONIC, "profile-a")
        } returns albumRatings.firstOrNull()
        coEvery {
            albumNoteDao.getNoteCountsForProfile(MediaId.PROVIDER_SUBSONIC, "profile-a")
        } returns noteCounts
        coEvery {
            songNoteDao.getForTracks(any(), MediaId.PROVIDER_SUBSONIC, "profile-a")
        } returns emptyList()
        coEvery { songAboutEntryDao.countAskRowsByAlbum(any()) } returns emptyList()
    }

    private fun album(trackCount: Int): Album =
        Album(
            id = MediaId.subsonic("album-1"),
            name = "Album One",
            artist = "Artist One",
            artistId = null,
            coverArt = null,
            songCount = trackCount,
            durationSec = null,
            year = null,
            genre = null,
            tracks = (1..trackCount).map { index ->
                Track(
                    id = MediaId.subsonic("track-$index"),
                    title = "Track $index",
                    artist = "Artist One",
                    artistId = null,
                    album = "Album One",
                    albumId = MediaId.subsonic("album-1"),
                    coverArt = null,
                    durationSec = null,
                    trackNumber = index,
                    year = null,
                    genre = null,
                    userRating = null,
                )
            },
        )
}
