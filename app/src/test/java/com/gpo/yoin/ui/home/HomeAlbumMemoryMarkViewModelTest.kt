package com.gpo.yoin.ui.home

import com.gpo.yoin.data.home.HomeLayoutStore
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.testutil.MainDispatcherRule
import com.gpo.yoin.ui.memories.MemoryScoreKind
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Home's cover memory marks (owner 2026-10-11, T4) come from the memory
 * signals' own candidate build: which covers get one, and what it carries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeAlbumMemoryMarkViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun should_markTheCoversOfAlbumsWithMemories_when_signalsBuild() = runTest {
        val repository = repository()
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            // Eligible and reviewed: it becomes the memory 1×2, whose card shows its own score.
            candidate("memory", albumRating = 9f, review = true, eligible = true),
            candidate("rated", albumRating = 8.5f, notes = 2),
            candidate("tracks", average = 7.25f, ratedTracks = 5),
            candidate("noted", notes = 1),
            candidate("played")
        )
        coEvery { repository.getCachedHomeGridPools(any()) } returns pools("memory", "rated", "played")
        coEvery { repository.getCachedHomeGridPools(isNull()) } returns pools("memory", "rated", "played")
        val recentlyPlayed = listOf(subsonicAlbum("tracks"), subsonicAlbum("played"))
        coEvery { repository.getRecentlyPlayedAlbums(any()) } returns recentlyPlayed
        coEvery { repository.getStarred() } returns Starred(albums = listOf(recentlyAdded("noted")))

        val viewModel = homeViewModel(repository)
        advanceUntilIdle()

        val content = viewModel.uiState.value as HomeUiState.Content
        val marks = content.albumMemoryMarks
        assertEquals(setOf("memory", "rated", "tracks", "noted"), marks.keys)
        assertEquals(HomeAlbumMemoryMark(MemoryScoreKind.ALBUM_RATING, "8.5", 2), marks.getValue("rated"))
        assertEquals(HomeAlbumMemoryMark(MemoryScoreKind.AVERAGE_TRACK_RATING, "7.3", 0), marks.getValue("tracks"))
        assertEquals(HomeAlbumMemoryMark(MemoryScoreKind.NONE, null, 1), marks.getValue("noted"))

        // Jump Back In: only the plain rated album wears one; the 1×2 and the bare album don't.
        val marked = content.widgetGrid.filter { card -> card.albumMemoryMark(marks) != null }
        assertEquals(listOf("grid-album:subsonic:rated"), marked.map(HomeWidgetCard::stableId))
        // Recently Played and Recently Added album cards.
        assertEquals(
            marks.getValue("tracks"),
            content.recentlyPlayed.single { it.id.rawId == "tracks" }.albumMemoryMark(marks)
        )
        assertNull(content.recentlyPlayed.single { it.id.rawId == "played" }.albumMemoryMark(marks))
        assertEquals(marks.getValue("noted"), content.recentlyAddedAlbums.single().albumMemoryMark(marks))
    }

    @Test
    fun should_followAWrite_when_theSignalStampMoves() = runTest {
        val stamp = MutableStateFlow(0L)
        val repository = repository(stamp = stamp)
        var candidates = listOf(candidate("album", notes = 1))
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } answers { candidates }

        val viewModel = homeViewModel(repository)
        advanceUntilIdle()
        assertEquals(MemoryScoreKind.NONE, marksOf(viewModel).getValue("album").kind)

        // The user rates the album: its mark inks in.
        candidates = listOf(candidate("album", albumRating = 7f, notes = 1))
        stamp.value = 1L
        advanceUntilIdle()

        assertEquals(HomeAlbumMemoryMark(MemoryScoreKind.ALBUM_RATING, "7.0", 1), marksOf(viewModel).getValue("album"))
    }

    @Test
    fun should_keepTheMarks_when_aSignalBuildFails() = runTest {
        val stamp = MutableStateFlow(0L)
        val repository = repository(stamp = stamp)
        var fail = false
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } answers {
            if (fail) error("cold cache") else listOf(candidate("album", albumRating = 6f))
        }

        val viewModel = homeViewModel(repository)
        advanceUntilIdle()
        val before = marksOf(viewModel)
        assertEquals(setOf("album"), before.keys)

        fail = true
        stamp.value = 1L
        advanceUntilIdle()
        assertEquals(before, marksOf(viewModel))

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(before, marksOf(viewModel))
    }

    @Test
    fun should_markAppleRecentlyPlayedCovers_when_theyComeWithTheirCatalogIds() = runTest {
        val apple = MediaId.PROVIDER_APPLE_MUSIC
        val repository = repository(provider = apple)
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            // Rated where the album page keeps it: under the catalog id it opens as.
            candidate("1440857781", albumRating = 8f, provider = apple),
            // An imported album Apple can't match is rated under its library id.
            candidate("library:l.imported", notes = 2, provider = apple)
        )
        coEvery { repository.getRecentlyPlayedAlbums(any()) } returns listOf(
            album(MediaId(apple, "1440857781")),
            album(MediaId(apple, "library:l.imported")),
            album(MediaId(apple, "library:l.unrated"))
        )

        val viewModel = homeViewModel(repository)
        advanceUntilIdle()

        val content = viewModel.uiState.value as HomeUiState.Content
        val marks = content.albumMemoryMarks
        assertEquals(
            listOf(
                HomeAlbumMemoryMark(MemoryScoreKind.ALBUM_RATING, "8.0", 0),
                HomeAlbumMemoryMark(MemoryScoreKind.NONE, null, 2),
                null
            ),
            content.recentlyPlayed.map { album -> album.albumMemoryMark(marks) }
        )
    }

    @Test
    fun should_markSpotifyCovers_when_theirRawIdsAreMarked() = runTest {
        val spotify = MediaId.PROVIDER_SPOTIFY
        val repository = repository(provider = spotify)
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            candidate("6dVIqQ8qmQ5GBnJ9shOYGE", average = 7.5f, ratedTracks = 4, provider = spotify)
        )
        coEvery { repository.getRecentlyPlayedAlbums(any()) } returns listOf(
            album(MediaId.spotify("6dVIqQ8qmQ5GBnJ9shOYGE")),
            album(MediaId.spotify("1ATL5GLyefJaxhQzSPVrLX"))
        )

        val viewModel = homeViewModel(repository)
        advanceUntilIdle()

        val content = viewModel.uiState.value as HomeUiState.Content
        assertEquals(
            listOf(HomeAlbumMemoryMark(MemoryScoreKind.AVERAGE_TRACK_RATING, "7.5", 0), null),
            content.recentlyPlayed.map { album -> album.albumMemoryMark(content.albumMemoryMarks) }
        )
    }

    private fun marksOf(viewModel: HomeViewModel): Map<String, HomeAlbumMemoryMark> =
        (viewModel.uiState.value as HomeUiState.Content).albumMemoryMarks

    private fun repository(stamp: Flow<Long> = flowOf(), provider: String = MediaId.PROVIDER_SUBSONIC): YoinRepository {
        val repository = mockk<YoinRepository>(relaxed = true)
        every { repository.currentProviderId() } returns provider
        every { repository.activeProviderId } returns flowOf(provider)
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.getRecentActivities(limit = any()) } returns flowOf(emptyList<ActivityEvent>())
        every { repository.observeMemorySignalStamp() } returns stamp
        every { repository.observeMostRecentPlay() } returns flowOf()
        every { repository.resolveCoverUrl(any(), any()) } returns null
        every { repository.getRating(any()) } returns flowOf(null)
        coEvery { repository.countNotes() } returns 0
        coEvery { repository.getRecentSongNotes(any()) } returns emptyList()
        coEvery { repository.getMostRecentPlay(any()) } returns null
        coEvery { repository.getStarred() } returns Starred()
        coEvery { repository.getCachedHomeGridPools(any()) } returns null
        coEvery { repository.getCachedHomeGridPools(isNull()) } returns null
        coEvery { repository.getAlbumList("random", any(), any()) } returns emptyList()
        coEvery { repository.getRandomSongs(any()) } returns emptyList()
        coEvery { repository.getPlaylists() } returns emptyList()
        coEvery { repository.getRecentlyPlayedAlbums(any()) } returns emptyList()
        coEvery {
            repository.replaceHomeGridPools(albums = any(), tracks = any(), playlists = any())
        } just runs
        return repository
    }

    private fun homeViewModel(repository: YoinRepository): HomeViewModel = HomeViewModel(
        repository = repository,
        activeProfileId = MutableStateFlow(PROFILE),
        homeLayoutStore = mockk<HomeLayoutStore>(relaxed = true).also { store ->
            every { store.layoutFlow(any()) } returns flowOf(null)
        }
    )

    private fun pools(vararg albumRawIds: String): YoinRepository.HomeGridPoolSnapshot =
        YoinRepository.HomeGridPoolSnapshot(
            albums = albumRawIds.map(::subsonicAlbum),
            tracks = emptyList(),
            playlists = emptyList(),
            cachedAt = 1L
        )

    private fun subsonicAlbum(rawId: String): Album = album(MediaId.subsonic(rawId))

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

    /** A library album saved an hour ago: inside Recently Added's window. */
    private fun recentlyAdded(rawId: String): Album =
        subsonicAlbum(rawId).copy(addedAt = Instant.now().minus(1, ChronoUnit.HOURS).toString())

    private fun candidate(
        albumId: String,
        albumRating: Float? = null,
        average: Float? = null,
        ratedTracks: Int = if (average != null) 1 else 0,
        notes: Int = 0,
        review: Boolean = false,
        eligible: Boolean = false,
        provider: String = MediaId.PROVIDER_SUBSONIC
    ): AlbumMemoryCandidate = AlbumMemoryCandidate(
        profileId = PROFILE,
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
        lastWrittenAt = 1_000L,
        playCount = 3,
        neoDbSynced = false,
        isMemoryEligible = eligible,
        year = null,
        durationSeconds = null,
        coverArtUrl = null
    )

    private companion object {
        const val PROFILE = "subsonic-marks"
    }
}
