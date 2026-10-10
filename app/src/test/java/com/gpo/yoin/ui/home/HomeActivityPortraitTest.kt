package com.gpo.yoin.ui.home

import com.gpo.yoin.data.home.HomeLayoutStore
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Owner Q16 in Home: once a Spotify load is done and the feed rests, the
 * artists the Activities seat are asked for their portraits, and each one
 * splices into the endpoint feed as it lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeActivityPortraitTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val asked = mutableListOf<List<String>>()

    @Test
    fun should_spliceThePortrait_when_theFeedRests() = runTest {
        val repository = spotifyRepository(endpoint = { feed("artist-1") })
        val viewModel = homeViewModel(repository, "spotify-portrait-rests")
        advanceUntilIdle()
        assertEquals(ALBUM_COVER, artistCover(viewModel))

        viewModel.onActivityArtistsShown(listOf("spotify:artist-1"))
        advanceTimeBy(1_000)
        runCurrent()
        // Not yet: the feed rests a beat first.
        assertTrue(asked.isEmpty())

        advanceTimeBy(600)
        runCurrent()
        assertEquals(listOf(listOf("artist-1")), asked)
        val content = viewModel.uiState.value as HomeUiState.Content
        assertEquals(portraitOf("artist-1"), artistCover(viewModel))
        assertTrue(content.activitiesFromRemote)
    }

    @Test
    fun should_waitForTheListToStop_when_theFeedScrolls() = runTest {
        val repository = spotifyRepository(endpoint = { feed("artist-1") })
        val viewModel = homeViewModel(repository, "spotify-portrait-scrolls")
        advanceUntilIdle()

        viewModel.onFeedScrollChanged(true)
        viewModel.onActivityArtistsShown(listOf("spotify:artist-1"))
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(asked.isEmpty())
        assertEquals(ALBUM_COVER, artistCover(viewModel))

        viewModel.onFeedScrollChanged(false)
        advanceTimeBy(1_600)
        runCurrent()
        assertEquals(portraitOf("artist-1"), artistCover(viewModel))
    }

    @Test
    fun should_askOnlyForTheFeedsSpotifyArtists_when_theFeedReportsOthers() = runTest {
        val repository = spotifyRepository(endpoint = { feed("artist-1") })
        val viewModel = homeViewModel(repository, "spotify-portrait-only-feed")
        advanceUntilIdle()

        viewModel.onActivityArtistsShown(listOf("subsonic:artist-1", "spotify:elsewhere", "spotify:artist-1"))
        advanceTimeBy(1_600)
        runCurrent()

        assertEquals(listOf(listOf("artist-1")), asked)
    }

    @Test
    fun should_notAskForPortraits_when_theActivityLogStandsIn() = runTest {
        // The endpoint read failed: the Activities are the activity log's, not recently-played's.
        val repository = spotifyRepository(endpoint = { throw IOException("offline") })
        every { repository.getRecentActivities(limit = any()) } returns flowOf(feed("artist-1"))
        val viewModel = homeViewModel(repository, "spotify-portrait-local")
        advanceUntilIdle()

        viewModel.onActivityArtistsShown(listOf("spotify:artist-1"))
        advanceTimeBy(5_000)
        runCurrent()

        coVerify(exactly = 0) { repository.fillSpotifyActivityArtistPortraits(any(), any(), any()) }
    }

    private fun spotifyRepository(endpoint: () -> List<ActivityEvent>): YoinRepository {
        val repository = mockk<YoinRepository>(relaxed = true)
        every { repository.currentProviderId() } returns MediaId.PROVIDER_SPOTIFY
        every { repository.activeProviderId } returns flowOf(MediaId.PROVIDER_SPOTIFY)
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.getRecentActivities(limit = any()) } returns flowOf(emptyList<ActivityEvent>())
        every { repository.observeMemorySignalStamp() } returns flowOf()
        every { repository.observeMostRecentPlay() } returns flowOf()
        every { repository.resolveCoverUrl(any(), any()) } returns null
        every { repository.getRating(any()) } returns flowOf(null)
        coEvery { repository.getRecentSongNotes(any()) } returns emptyList()
        coEvery { repository.getMostRecentPlay(any()) } returns null
        coEvery { repository.getStarred() } returns Starred()
        coEvery { repository.getCachedHomeGridPools(any()) } returns null
        coEvery { repository.getCachedHomeGridPools(isNull()) } returns null
        coEvery { repository.getAlbumList("random", any(), any()) } returns emptyList()
        coEvery { repository.getRandomSongs(any()) } returns emptyList()
        coEvery { repository.getPlaylists() } returns emptyList()
        coEvery { repository.getRecentlyPlayedAlbums(any()) } returns emptyList()
        coEvery { repository.replaceHomeGridPools(albums = any(), tracks = any(), playlists = any()) } just runs
        coEvery { repository.getSpotifyRecentActivities(any()) } answers { endpoint() }
        coEvery { repository.fillSpotifyActivityArtistPortraits(any(), any(), any()) } coAnswers {
            val artistIds = firstArg<List<String>>()
            val awaitTurn = secondArg<suspend () -> Unit>()
            val onPortrait = thirdArg<(String, String) -> Unit>()
            asked += artistIds
            artistIds.forEach { artistId ->
                awaitTurn()
                onPortrait(artistId, portraitOf(artistId))
            }
        }
        return repository
    }

    private fun homeViewModel(repository: YoinRepository, profile: String): HomeViewModel = HomeViewModel(
        repository = repository,
        activeProfileId = MutableStateFlow(profile),
        homeLayoutStore = mockk<HomeLayoutStore>(relaxed = true).also { store ->
            every { store.layoutFlow(any()) } returns flowOf(null)
        }
    )

    private fun artistCover(viewModel: HomeViewModel): String? =
        (viewModel.uiState.value as HomeUiState.Content).activities
            .single { it.entityType == ActivityEntityType.ARTIST.name }
            .coverArtId

    /** One play as recently-played maps it: the album, then its artist on the album's cover. */
    private fun feed(artistId: String): List<ActivityEvent> = listOf(
        ActivityEvent(
            entityType = ActivityEntityType.ALBUM.name,
            actionType = ActivityActionType.PLAYED.name,
            entityId = "album-1",
            provider = MediaId.PROVIDER_SPOTIFY,
            title = "Album",
            subtitle = "Artist",
            coverArtId = ALBUM_COVER,
            albumId = "album-1",
            timestamp = 2L
        ),
        ActivityEvent(
            entityType = ActivityEntityType.ARTIST.name,
            actionType = ActivityActionType.PLAYED.name,
            entityId = artistId,
            provider = MediaId.PROVIDER_SPOTIFY,
            title = "Artist",
            subtitle = "Artist",
            coverArtId = ALBUM_COVER,
            artistId = artistId,
            timestamp = 2L
        )
    )

    private companion object {
        const val ALBUM_COVER = "https://i.scdn.co/image/album-1"

        fun portraitOf(artistId: String) = "https://i.scdn.co/image/portrait-$artistId"
    }
}
