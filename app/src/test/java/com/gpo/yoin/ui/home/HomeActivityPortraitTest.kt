package com.gpo.yoin.ui.home

import com.gpo.yoin.data.home.HomeLayoutStore
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import com.gpo.yoin.data.source.spotify.SpotifyPortraitPassEnd
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Owner Q16 in Home: once a Spotify load is done and the feed rests (on
 * screen, nothing over it, its list still), the artists the Activities
 * picture are asked for their portraits, and each one splices into the
 * endpoint feed as it lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeActivityPortraitTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val asked = mutableListOf<List<String>>()
    private val requested = mutableListOf<String>()

    // How the next passes end (Done once these run out).
    private val passEnds = ArrayDeque<SpotifyPortraitPassEnd>()

    @Test
    fun should_spliceThePortrait_when_theFeedRests() = runTest {
        val repository = spotifyRepository(endpoint = { feed("artist-1") })
        val viewModel = homeViewModel(repository, "spotify-portrait-rests")
        advanceUntilIdle()
        assertEquals(ALBUM_COVER, artistCover(viewModel))

        viewModel.onFeedAtRestChanged(true)
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
    fun should_waitForTheFeedToRest_when_itIsScrolled() = runTest {
        val repository = spotifyRepository(endpoint = { feed("artist-1") })
        val viewModel = homeViewModel(repository, "spotify-portrait-scrolls")
        advanceUntilIdle()

        viewModel.onFeedAtRestChanged(true)
        viewModel.onFeedAtRestChanged(false)
        viewModel.onActivityArtistsShown(listOf("spotify:artist-1"))
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(asked.isEmpty())
        assertEquals(ALBUM_COVER, artistCover(viewModel))

        viewModel.onFeedAtRestChanged(true)
        advanceTimeBy(1_600)
        runCurrent()
        assertEquals(portraitOf("artist-1"), artistCover(viewModel))
    }

    @Test
    fun should_notAsk_when_homeIsNotOnScreen() = runTest {
        // Library, a detail page or Now Playing over Home, the background:
        // the feed never says it rests.
        val repository = spotifyRepository(endpoint = { feed("artist-1") })
        val viewModel = homeViewModel(repository, "spotify-portrait-hidden")
        advanceUntilIdle()

        viewModel.onActivityArtistsShown(listOf("spotify:artist-1"))
        advanceTimeBy(60_000)
        runCurrent()

        coVerify(exactly = 0) { repository.fillSpotifyActivityArtistPortraits(any(), any(), any()) }
        assertEquals(ALBUM_COVER, artistCover(viewModel))

        viewModel.onFeedAtRestChanged(true)
        advanceTimeBy(1_600)
        runCurrent()
        assertEquals(portraitOf("artist-1"), artistCover(viewModel))
    }

    @Test
    fun should_holdTheNextRequest_when_theFeedStopsRestingMidPass() = runTest {
        val repository = spotifyRepository(endpoint = { feed("artist-1", "artist-2") })
        val viewModel = homeViewModel(repository, "spotify-portrait-mid-pass")
        advanceUntilIdle()

        viewModel.onFeedAtRestChanged(true)
        viewModel.onActivityArtistsShown(listOf("spotify:artist-1", "spotify:artist-2"))
        advanceTimeBy(1_600)
        runCurrent()
        assertEquals(listOf("artist-1"), requested)

        // A tap opens an artist page (or the list is flung) between two requests.
        viewModel.onFeedAtRestChanged(false)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf("artist-1"), requested)

        // Back on Home: the next request waits for the feed to rest a beat again.
        viewModel.onFeedAtRestChanged(true)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("artist-1"), requested)
        advanceTimeBy(600)
        runCurrent()
        assertEquals(listOf("artist-1", "artist-2"), requested)
        assertEquals(portraitOf("artist-2"), artistCover(viewModel, "artist-2"))
    }

    @Test
    fun should_lookUpActivityPortraitsOnlyOnSpotify_when_servicesAreCompared() {
        // Subsonic and Apple Music build their Activities from local records,
        // which carry their covers.
        assertTrue(ServiceFeatureCatalog.spotify.activityArtistPortraits)
        assertFalse(ServiceFeatureCatalog.subsonic.activityArtistPortraits)
        assertFalse(ServiceFeatureCatalog.appleMusic.activityArtistPortraits)
        assertFalse(ServiceFeatureCatalog.local.activityArtistPortraits)
    }

    @Test
    fun should_askOnlyForTheFeedsSpotifyArtists_when_theFeedReportsOthers() = runTest {
        val repository = spotifyRepository(endpoint = { feed("artist-1") })
        val viewModel = homeViewModel(repository, "spotify-portrait-only-feed")
        advanceUntilIdle()

        viewModel.onFeedAtRestChanged(true)
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

        viewModel.onFeedAtRestChanged(true)
        viewModel.onActivityArtistsShown(listOf("spotify:artist-1"))
        advanceTimeBy(5_000)
        runCurrent()

        coVerify(exactly = 0) { repository.fillSpotifyActivityArtistPortraits(any(), any(), any()) }
    }

    @Test
    fun should_askAgainAtTheNextRestAfterAMinute_when_aPassEndsOnAFailedRead() = runTest {
        passEnds += SpotifyPortraitPassEnd.Error
        val repository = spotifyRepository(endpoint = { feed("artist-1") })
        val viewModel = homeViewModel(repository, "spotify-portrait-retry")
        advanceUntilIdle()
        val start = currentTime

        viewModel.onFeedAtRestChanged(true)
        viewModel.onActivityArtistsShown(listOf("spotify:artist-1"))
        advanceTo(start + 1_600)
        assertEquals(1, asked.size)
        assertEquals(ALBUM_COVER, artistCover(viewModel))

        // The same artists, the feed resting all along: not before the wait is over.
        advanceTo(start + 1_500 + 60_000 + 1_400)
        assertEquals(1, asked.size)

        advanceTo(start + 1_500 + 60_000 + 1_600)
        assertEquals(listOf(listOf("artist-1"), listOf("artist-1")), asked)
        assertEquals(portraitOf("artist-1"), artistCover(viewModel))

        // Done: not asked again.
        advanceTo(currentTime + 3 * HOUR_MS)
        assertEquals(2, asked.size)
    }

    @Test
    fun should_waitLongerBeforeEachRetry_when_passesKeepStopping() = runTest {
        passEnds += listOf(
            SpotifyPortraitPassEnd.Error,
            SpotifyPortraitPassEnd.Gate,
            SpotifyPortraitPassEnd.Error,
            SpotifyPortraitPassEnd.Error
        )
        val repository = spotifyRepository(endpoint = { feed("artist-1") })
        val viewModel = homeViewModel(repository, "spotify-portrait-backoff")
        advanceUntilIdle()
        val start = currentTime

        viewModel.onFeedAtRestChanged(true)
        viewModel.onActivityArtistsShown(listOf("spotify:artist-1"))
        // Each pass: the feed's settle, then the wait after the pass before it.
        var pass = start + 1_500
        listOf(60_000L, 5 * MINUTE_MS, 30 * MINUTE_MS, 30 * MINUTE_MS).forEachIndexed { index, wait ->
            advanceTo(pass + 100)
            assertEquals(index + 1, asked.size)
            pass += wait + 1_500
            advanceTo(pass - 100)
            assertEquals(index + 1, asked.size)
        }
        advanceTo(pass + 100)
        assertEquals(5, asked.size)
        assertEquals(portraitOf("artist-1"), artistCover(viewModel))
        // The closed gate's pass asked nothing.
        assertEquals(List(4) { "artist-1" }, requested)
    }

    @Test
    fun should_retryOnlyOnceTheFeedRests_when_theWaitEndsWhileItMoves() = runTest {
        passEnds += SpotifyPortraitPassEnd.Gate
        val repository = spotifyRepository(endpoint = { feed("artist-1") })
        val viewModel = homeViewModel(repository, "spotify-portrait-retry-moves")
        advanceUntilIdle()
        val start = currentTime

        viewModel.onFeedAtRestChanged(true)
        viewModel.onActivityArtistsShown(listOf("spotify:artist-1"))
        advanceTo(start + 1_600)
        assertEquals(1, asked.size)

        viewModel.onFeedAtRestChanged(false)
        advanceTo(start + 10 * MINUTE_MS)
        assertEquals(1, asked.size)

        viewModel.onFeedAtRestChanged(true)
        advanceTo(currentTime + 1_600)
        assertEquals(2, asked.size)
        assertEquals(portraitOf("artist-1"), artistCover(viewModel))
    }

    @Test
    fun should_notAskAgain_when_aPassMeetsTheRateLimit() = runTest {
        passEnds += SpotifyPortraitPassEnd.RateLimited
        val repository = spotifyRepository(endpoint = { feed("artist-1") })
        val viewModel = homeViewModel(repository, "spotify-portrait-429")
        advanceUntilIdle()

        viewModel.onFeedAtRestChanged(true)
        viewModel.onActivityArtistsShown(listOf("spotify:artist-1"))
        advanceTo(currentTime + 1_600)
        viewModel.onFeedAtRestChanged(false)
        viewModel.onFeedAtRestChanged(true)
        advanceTo(currentTime + 3 * HOUR_MS)

        assertEquals(1, asked.size)
        assertEquals(ALBUM_COVER, artistCover(viewModel))
    }

    private fun TestScope.advanceTo(timeMs: Long) {
        advanceTimeBy(timeMs - currentTime)
        runCurrent()
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
            val end = passEnds.removeFirstOrNull() ?: SpotifyPortraitPassEnd.Done
            if (end != SpotifyPortraitPassEnd.Done) {
                // Stopped: a closed gate asks nothing; a failed read or a 429 at the first request.
                if (end != SpotifyPortraitPassEnd.Gate) {
                    awaitTurn()
                    requested += artistIds.first()
                }
                return@coAnswers end
            }
            artistIds.forEachIndexed { index, artistId ->
                // The repository's beat between two requests of a pass.
                if (index > 0) delay(250)
                awaitTurn()
                requested += artistId
                onPortrait(artistId, portraitOf(artistId))
            }
            SpotifyPortraitPassEnd.Done
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

    private fun artistCover(viewModel: HomeViewModel, artistId: String = "artist-1"): String? =
        (viewModel.uiState.value as HomeUiState.Content).activities
            .single { it.entityType == ActivityEntityType.ARTIST.name && it.entityId == artistId }
            .coverArtId

    /** One play as recently-played maps it: the album, then its artists on the album's cover. */
    private fun feed(vararg artistIds: String): List<ActivityEvent> = listOf(
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
        )
    ) + artistIds.map { artistId ->
        ActivityEvent(
            entityType = ActivityEntityType.ARTIST.name,
            actionType = ActivityActionType.PLAYED.name,
            entityId = artistId,
            provider = MediaId.PROVIDER_SPOTIFY,
            title = "Artist $artistId",
            subtitle = "Artist",
            coverArtId = ALBUM_COVER,
            artistId = artistId,
            timestamp = 2L
        )
    }

    private companion object {
        const val ALBUM_COVER = "https://i.scdn.co/image/album-1"
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS

        fun portraitOf(artistId: String) = "https://i.scdn.co/image/portrait-$artistId"
    }
}
