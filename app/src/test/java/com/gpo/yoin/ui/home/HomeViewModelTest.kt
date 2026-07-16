package com.gpo.yoin.ui.home

import com.gpo.yoin.data.home.HomeLayoutStore
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun should_render_stale_grid_pools_before_network_rotation() = runTest {
        val repository = mockk<YoinRepository>(relaxed = true)
        val profileId = MutableStateFlow("spotify-profile")
        val gate = CompletableDeferred<Unit>()
        val cachedAlbum = album("cached-album", "Cached Album")
        val freshAlbum = album("fresh-album", "Fresh Album")

        every { repository.currentProviderId() } returns MediaId.PROVIDER_SPOTIFY
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.getRecentActivities(limit = 20) } returns flowOf(emptyList<ActivityEvent>())
        // TTL-bounded read: pools have expired → the fresh path must rotate.
        coEvery { repository.getCachedHomeGridPools(isNull(inverse = true)) } returns null
        // Any-age read: the instant pre-paint path still has the old batch.
        coEvery { repository.getCachedHomeGridPools(isNull()) } returns
            YoinRepository.HomeGridPoolSnapshot(
                albums = listOf(cachedAlbum),
                tracks = emptyList(),
                playlists = emptyList(),
                cachedAt = 1L,
            )
        coEvery { repository.getAlbumList("random", 18, 0) } coAnswers {
            gate.await()
            listOf(freshAlbum)
        }
        coEvery {
            repository.replaceHomeGridPools(albums = any(), tracks = any(), playlists = any())
        } just runs

        val homeLayoutStore = mockk<HomeLayoutStore>(relaxed = true)
        every { homeLayoutStore.layoutFlow(any()) } returns flowOf(null)

        val viewModel = HomeViewModel(
            repository = repository,
            activeProfileId = profileId,
            homeLayoutStore = homeLayoutStore,
        )

        advanceUntilIdle()

        val cachedState = viewModel.uiState.value as HomeUiState.Content
        assertTrue(
            cachedState.widgetGrid.any { card ->
                card.target == HomeWidgetTarget.AlbumDetail(cachedAlbum.id.toString())
            },
        )

        gate.complete(Unit)
        advanceUntilIdle()

        val freshState = viewModel.uiState.value as HomeUiState.Content
        assertTrue(
            freshState.widgetGrid.any { card ->
                card.target == HomeWidgetTarget.AlbumDetail(freshAlbum.id.toString())
            },
        )
        // The rotated batch is persisted for the next opens.
        coVerify {
            repository.replaceHomeGridPools(
                albums = listOf(freshAlbum),
                tracks = any(),
                playlists = any(),
            )
        }
    }

    @Test
    fun recently_added_keeps_only_last_week_newest_first() = runTest {
        val repository = mockk<YoinRepository>(relaxed = true)
        // Distinct profile id: HomeViewModel's static homeContentCache is keyed
        // by provider|profile and persists across instances, so reusing another
        // test's key would leak that test's cached content into this one.
        val profileId = MutableStateFlow("spotify-recent")

        every { repository.currentProviderId() } returns MediaId.PROVIDER_SPOTIFY
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.getRecentActivities(limit = 20) } returns flowOf(emptyList<ActivityEvent>())
        coEvery { repository.getSpotifyRecentActivities(20) } returns emptyList()
        coEvery { repository.getCachedHomeGridPools(any()) } returns null
        coEvery { repository.getCachedHomeGridPools(isNull()) } returns null
        coEvery { repository.getAlbumList("random", any(), any()) } returns emptyList()
        coEvery {
            repository.replaceHomeGridPools(albums = any(), tracks = any(), playlists = any())
        } just runs

        val now = Instant.now()
        // Legacy Subsonic servers emit zone-less date-times — must parse as UTC.
        val zoneless = LocalDateTime.ofInstant(now.minus(3, ChronoUnit.DAYS), ZoneOffset.UTC).toString()
        coEvery { repository.getStarred() } returns Starred(
            tracks = listOf(
                savedTrack("old", now.minus(20, ChronoUnit.DAYS).toString()),
                savedTrack("recent", now.minus(2, ChronoUnit.DAYS).toString()),
                savedTrack("zoneless", zoneless),
                savedTrack("newest", now.minus(1, ChronoUnit.HOURS).toString()),
                savedTrack("no-date", ""),
            ),
            albums = listOf(
                album("album-old", "Old").copy(addedAt = now.minus(30, ChronoUnit.DAYS).toString()),
                album("album-recent", "Recent").copy(addedAt = now.minus(4, ChronoUnit.DAYS).toString()),
                album("album-newest", "Newest").copy(addedAt = now.minus(2, ChronoUnit.HOURS).toString()),
                album("album-no-date", "No date").copy(addedAt = null),
            ),
        )

        val homeLayoutStore = mockk<HomeLayoutStore>(relaxed = true)
        every { homeLayoutStore.layoutFlow(any()) } returns flowOf(null)

        val viewModel = HomeViewModel(
            repository = repository,
            activeProfileId = profileId,
            homeLayoutStore = homeLayoutStore,
        )

        advanceUntilIdle()

        val content = viewModel.uiState.value as HomeUiState.Content
        // Only items added within the last 7 days survive, newest first; the
        // unparseable-date item is dropped, the zone-less one parses as UTC.
        assertEquals(
            listOf("newest", "recent", "zoneless"),
            content.recentlyAddedTracks.map { it.id.rawId },
        )
        // Albums apply the same window / ordering, independently of tracks.
        assertEquals(
            listOf("album-newest", "album-recent"),
            content.recentlyAddedAlbums.map { it.id.rawId },
        )
    }

    @Test
    fun widget_grid_composes_wide_cards_with_dedup_and_twelve_cell_budget() = runTest {
        val repository = mockk<YoinRepository>(relaxed = true)
        // Distinct profile id — the static homeContentCache is keyed by
        // provider|profile and would leak other tests' content otherwise.
        val profileId = MutableStateFlow("subsonic-grid")
        val now = System.currentTimeMillis()

        every { repository.currentProviderId() } returns MediaId.PROVIDER_SUBSONIC
        every { repository.currentCapabilities() } returns setOf(Capability.RANDOM_SONGS)
        every { repository.getRecentActivities(limit = 20) } returns flowOf(emptyList<ActivityEvent>())
        every { repository.observeMemorySignalStamp() } returns flowOf()
        every { repository.resolveCoverUrl(any(), any()) } returns null
        every { repository.getRating(any()) } returns flowOf(null)
        coEvery { repository.getMostRecentPlay(any()) } returns null
        coEvery { repository.getStarred() } returns com.gpo.yoin.data.model.Starred()
        // No persisted pools → the grid builds (and persists) a fresh batch.
        coEvery { repository.getCachedHomeGridPools(any()) } returns null
        coEvery { repository.getCachedHomeGridPools(isNull()) } returns null
        coEvery {
            repository.replaceHomeGridPools(albums = any(), tracks = any(), playlists = any())
        } just runs

        // One reviewed memory candidate (album a1) → the memory 1×2 card.
        coEvery { repository.getAlbumMemoryCandidates(any()) } returns listOf(
            AlbumMemoryCandidate(
                profileId = "subsonic-grid",
                provider = MediaId.PROVIDER_SUBSONIC,
                albumId = "a1",
                albumName = "Album One",
                artistName = "Artist One",
                totalTracks = 10,
                ratedTrackCount = 6,
                ratingCoverage = 0.6f,
                averageSongRating = 7.2f,
                albumRating = 8.6f,
                hasAlbumReview = true,
                noteCount = 1,
                askAiCount = 0,
                firstPlayedAt = now - 100_000L,
                lastPlayedAt = now - 50_000L,
                playCount = 3,
                neoDbSynced = false,
                isMemoryEligible = true,
                year = 2024,
                durationSeconds = 2400,
                coverArtUrl = null,
            ),
        )
        every { repository.observeAlbumRating(any()) } returns flowOf(
            AlbumRating(
                profileId = "subsonic-grid",
                albumId = "a1",
                provider = MediaId.PROVIDER_SUBSONIC,
                rating = 8.6f,
                review = "still my favourite",
                neoDbReviewUuid = null,
            ),
        )
        // One recent note on track t9 → the noted-track 1×2 card.
        coEvery { repository.getRecentSongNotes(any()) } returns listOf(
            SongNote(
                id = "n1",
                profileId = "subsonic-grid",
                trackId = "t9",
                provider = MediaId.PROVIDER_SUBSONIC,
                content = "贴着心跳的一首歌。",
                createdAt = now - 10_000L,
                updatedAt = now - 10_000L,
                title = "Song Nine",
                artist = "Artist Nine",
            ),
        )
        // Pools overfilled so the take()/budget/dedup logic actually bites;
        // a1 (the memory album) and t9 (the noted track) appear in the pools
        // and must be deduped out of the compact cards.
        coEvery { repository.getAlbumList("random", any(), any()) } returns
            listOf("a1", "a2", "a3", "a4", "a5").map { album(it, "Album $it") }
        coEvery { repository.getRandomSongs(any()) } returns
            listOf("t9", "t1", "t2", "t3").map { rawId ->
                // Same provider as the noted track, or the t9 dedup
                // (MediaId equality) would silently never match.
                savedTrack(rawId, "").copy(id = MediaId.subsonic(rawId))
            }
        coEvery { repository.getPlaylists() } returns listOf("p1", "p2", "p3", "p4").map {
            Playlist(
                id = MediaId.subsonic(it),
                name = "Playlist $it",
                owner = "owner",
                coverArt = null,
                songCount = 5,
                durationSec = 600,
            )
        }

        val homeLayoutStore = mockk<HomeLayoutStore>(relaxed = true)
        every { homeLayoutStore.layoutFlow(any()) } returns flowOf(null)

        val viewModel = HomeViewModel(
            repository = repository,
            activeProfileId = profileId,
            homeLayoutStore = homeLayoutStore,
        )

        advanceUntilIdle()

        val grid = (viewModel.uiState.value as HomeUiState.Content).widgetGrid
        // Budget: two 1×2 cards + compacts fill exactly 3×4 = 12 cells.
        assertEquals(12, grid.sumOf { card -> if (card.expanded) 2 else 1 })

        val expanded = grid.filter { it.expanded }
        assertEquals(2, expanded.size)
        assertTrue(
            expanded.any { card ->
                card.target is HomeWidgetTarget.MemoryFocus && card.comment == "still my favourite"
            },
        )
        assertTrue(
            expanded.any { card ->
                (card.target as? HomeWidgetTarget.PlaySong)?.song?.id?.rawId == "t9" &&
                    card.comment == "贴着心跳的一首歌。"
            },
        )

        val compacts = grid.filterNot { it.expanded }
        assertEquals(8, compacts.size)
        // Dedup: the wide cards' album/track never repeat as compact cards.
        assertTrue(
            compacts.none { card ->
                (card.target as? HomeWidgetTarget.AlbumDetail)?.albumId ==
                    MediaId.subsonic("a1").toString()
            },
        )
        assertTrue(
            compacts.none { card ->
                (card.target as? HomeWidgetTarget.PlaySong)?.song?.id?.rawId == "t9"
            },
        )
        // The design composition: 3 albums + 2 tracks + 3 playlists.
        assertEquals(3, compacts.count { it.entityType == MemoryEntityType.ALBUM })
        assertEquals(2, compacts.count { it.entityType == MemoryEntityType.SONG })
        assertEquals(3, compacts.count { it.entityType == MemoryEntityType.PLAYLIST })
    }

    private fun savedTrack(rawId: String, addedAt: String): Track = Track(
        id = MediaId.spotify(rawId),
        title = rawId,
        artist = "Artist",
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
        addedAt = addedAt,
    )

    private fun album(rawId: String, name: String): Album = Album(
        id = MediaId.spotify(rawId),
        name = name,
        artist = "Artist",
        artistId = MediaId.spotify("artist-$rawId"),
        coverArt = null,
        songCount = 10,
        durationSec = null,
        year = 2024,
        genre = null,
    )
}
