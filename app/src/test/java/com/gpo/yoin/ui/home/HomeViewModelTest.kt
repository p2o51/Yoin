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
        every { repository.getRecentActivities(limit = any()) } returns flowOf(emptyList<ActivityEvent>())
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
        coEvery { repository.getAlbumList("random", 24, 0) } coAnswers {
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
        every { repository.getRecentActivities(limit = any()) } returns flowOf(emptyList<ActivityEvent>())
        coEvery { repository.getSpotifyRecentActivities(any()) } returns emptyList()
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
        every { repository.getRecentActivities(limit = any()) } returns flowOf(emptyList<ActivityEvent>())
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
        // 首页 memory 槽位渲染缓存的 AI 拟题（v2.2 印章卡决定）——不再是乐评全文。
        coEvery { repository.getCachedAlbumMemoryTitle(any()) } returns "Still my favourite"
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
            // Same provider as the memory album, or the a1 dedup would never match.
            listOf("a1", "a2", "a3", "a4", "a5").map { album(it, "Album $it").copy(id = MediaId.subsonic(it)) }
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
        // The VM hands over every deduped cover (tablet templates seat more);
        // the phone shelf still fills exactly 3×4 = 12 cells.
        val phoneShelf = trimToPhoneShelf(grid)
        assertEquals(12, phoneShelf.sumOf { card -> if (card.expanded) 2 else 1 })

        val expanded = grid.filter { it.expanded }
        assertEquals(2, expanded.size)
        assertTrue(
            expanded.any { card ->
                card.target is HomeWidgetTarget.MemoryFocus && card.comment == "Still my favourite"
            },
        )
        assertTrue(
            expanded.any { card ->
                (card.target as? HomeWidgetTarget.PlaySong)?.song?.id?.rawId == "t9" &&
                    card.comment == "贴着心跳的一首歌。"
            },
        )

        // 4 albums + 3 tracks + 4 playlists after dedup, all handed over.
        assertEquals(11, grid.count { !it.expanded })
        val compacts = phoneShelf.filterNot { it.expanded }
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
        // The phone's design composition: 3 albums + 2 tracks + 3 playlists.
        assertEquals(3, compacts.count { it.entityType == MemoryEntityType.ALBUM })
        assertEquals(2, compacts.count { it.entityType == MemoryEntityType.SONG })
        assertEquals(3, compacts.count { it.entityType == MemoryEntityType.PLAYLIST })
    }

    @Test
    fun should_exposeMemoryPill_when_candidatesExist() = runTest {
        val repository = memorySignalRepository(profile = "subsonic-pill")
        coEvery { repository.getAlbumMemoryCandidates(any()) } returns listOf(
            memoryCandidate("strong", profile = "subsonic-pill", review = true, lastWrittenAt = 1_000L),
            memoryCandidate("fresh", profile = "subsonic-pill", lastWrittenAt = 9_000L, albumRating = null, average = 7.4f),
        )
        coEvery { repository.countNotes() } returns 5

        val viewModel = homeViewModel(repository, "subsonic-pill")
        advanceUntilIdle()

        val pill = (viewModel.uiState.value as HomeUiState.Content).memoryPill
        assertEquals("fresh", pill?.latest?.albumId?.rawId)
        assertEquals(com.gpo.yoin.ui.memories.MemoryScoreKind.AVERAGE_TRACK_RATING, pill?.latest?.scoreKind)
        assertEquals("7.4", pill?.latest?.scoreText)
        assertEquals(5, pill?.noteCount)
    }

    @Test
    fun should_fetchMemoryCandidatesOnce_when_loadingContent() = runTest {
        val repository = memorySignalRepository(profile = "subsonic-once")
        coEvery { repository.getAlbumMemoryCandidates(any()) } returns listOf(
            memoryCandidate("a1", profile = "subsonic-once", review = true),
        )
        coEvery { repository.countNotes() } returns 1

        homeViewModel(repository, "subsonic-once")
        advanceUntilIdle()

        // Pill and the grid's memory 1×2 share ONE build, at the deck's pool size.
        coVerify(exactly = 1) { repository.getAlbumMemoryCandidates(48) }
    }

    @Test
    fun should_preferAnotherAlbumForJbiMemoryCard_when_pillShowsLatest() = runTest {
        val repository = memorySignalRepository(profile = "subsonic-jbi")
        // The plain recipe would crown "latest" (the only review) for the 1×2
        // too; the pill already shows it, so the grid takes "older".
        coEvery { repository.getAlbumMemoryCandidates(any()) } returns listOf(
            memoryCandidate("latest", profile = "subsonic-jbi", review = true, lastWrittenAt = 9_000L),
            memoryCandidate("older", profile = "subsonic-jbi", lastWrittenAt = 1_000L),
        )
        coEvery { repository.countNotes() } returns 0

        val viewModel = homeViewModel(repository, "subsonic-jbi")
        advanceUntilIdle()

        val content = viewModel.uiState.value as HomeUiState.Content
        assertEquals("latest", content.memoryPill?.latest?.albumId?.rawId)
        val memoryCard = content.widgetGrid.single { it.target is HomeWidgetTarget.MemoryFocus }
        assertEquals("grid-memory:${MediaId.PROVIDER_SUBSONIC}:older", memoryCard.stableId)
    }

    @Test
    fun should_keepJbiMemoryAlbum_when_writingOnItDuringLiveSplice() = runTest {
        val stamp = MutableStateFlow(0L)
        val repository = memorySignalRepository(profile = "subsonic-sticky", stamp = stamp)
        var candidates = listOf(
            memoryCandidate("a", profile = "subsonic-sticky", review = true, lastWrittenAt = 9_000L),
            memoryCandidate("b", profile = "subsonic-sticky", lastWrittenAt = 1_000L),
        )
        coEvery { repository.getAlbumMemoryCandidates(any()) } answers { candidates }
        coEvery { repository.countNotes() } returns 0

        val viewModel = homeViewModel(repository, "subsonic-sticky")
        advanceUntilIdle()
        val before = (viewModel.uiState.value as HomeUiState.Content)
            .widgetGrid.single { it.target is HomeWidgetTarget.MemoryFocus }
        assertEquals("grid-memory:${MediaId.PROVIDER_SUBSONIC}:b", before.stableId)

        // The user rates a track on b: b becomes the newest write (the pill's
        // album). The live splice keeps b in the 1×2 instead of swapping to a.
        candidates = listOf(
            memoryCandidate("a", profile = "subsonic-sticky", review = true, lastWrittenAt = 9_000L),
            memoryCandidate("b", profile = "subsonic-sticky", lastWrittenAt = 20_000L, albumRating = 9.1f),
        )
        stamp.value = 1L
        advanceUntilIdle()

        val content = viewModel.uiState.value as HomeUiState.Content
        assertEquals("b", content.memoryPill?.latest?.albumId?.rawId)
        val after = content.widgetGrid.single { it.target is HomeWidgetTarget.MemoryFocus }
        assertEquals("grid-memory:${MediaId.PROVIDER_SUBSONIC}:b", after.stableId)
        assertEquals("9.1", after.ratingText)
    }

    @Test
    fun should_refreshNoteCount_when_signalStampMoves() = runTest {
        val stamp = MutableStateFlow(0L)
        val repository = memorySignalRepository(profile = "subsonic-stamp", stamp = stamp)
        coEvery { repository.getAlbumMemoryCandidates(any()) } returns emptyList()
        var notes = 3
        coEvery { repository.countNotes() } answers { notes }

        val viewModel = homeViewModel(repository, "subsonic-stamp")
        advanceUntilIdle()
        assertEquals(3, (viewModel.uiState.value as HomeUiState.Content).memoryPill?.noteCount)

        notes = 4
        stamp.value = 1L
        advanceUntilIdle()

        val pill = (viewModel.uiState.value as HomeUiState.Content).memoryPill
        assertEquals(4, pill?.noteCount)
        assertEquals(null, pill?.latest)
    }

    @Test
    fun should_keepPreviousPill_when_candidateBuildThrows() = runTest {
        val stamp = MutableStateFlow(0L)
        val repository = memorySignalRepository(profile = "subsonic-keep", stamp = stamp)
        var fail = false
        coEvery { repository.getAlbumMemoryCandidates(any()) } answers {
            if (fail) error("cold cache") else listOf(memoryCandidate("a1", profile = "subsonic-keep"))
        }
        coEvery { repository.countNotes() } returns 2

        val viewModel = homeViewModel(repository, "subsonic-keep")
        advanceUntilIdle()
        val before = (viewModel.uiState.value as HomeUiState.Content).memoryPill
        assertEquals("a1", before?.latest?.albumId?.rawId)

        fail = true
        stamp.value = 1L
        advanceUntilIdle()

        assertEquals(before, (viewModel.uiState.value as HomeUiState.Content).memoryPill)
    }

    @Test
    fun should_keepPreviousPill_when_noteCountIsUnscopedDuringTick() = runTest {
        val stamp = MutableStateFlow(0L)
        val repository = memorySignalRepository(profile = "subsonic-unscoped", stamp = stamp)
        coEvery { repository.getAlbumMemoryCandidates(any()) } returns listOf(
            memoryCandidate("a1", profile = "subsonic-unscoped"),
        )
        // countNotes() answers null when the repository has no active scope.
        var scoped = true
        coEvery { repository.countNotes() } answers { if (scoped) 1 else null }

        val viewModel = homeViewModel(repository, "subsonic-unscoped")
        advanceUntilIdle()
        val before = (viewModel.uiState.value as HomeUiState.Content).memoryPill

        scoped = false
        stamp.value = 1L
        advanceUntilIdle()

        assertEquals(before, (viewModel.uiState.value as HomeUiState.Content).memoryPill)
    }

    @Test
    fun should_keepPreviousPill_when_refreshCannotResolveSignals() = runTest {
        val repository = memorySignalRepository(profile = "subsonic-refresh-keep")
        var fail = false
        coEvery { repository.getAlbumMemoryCandidates(any()) } answers {
            if (fail) error("cold cache") else listOf(memoryCandidate("a1", profile = "subsonic-refresh-keep"))
        }
        coEvery { repository.countNotes() } returns 2

        val viewModel = homeViewModel(repository, "subsonic-refresh-keep")
        advanceUntilIdle()
        val before = (viewModel.uiState.value as HomeUiState.Content).memoryPill
        assertEquals("a1", before?.latest?.albumId?.rawId)

        fail = true
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(before, (viewModel.uiState.value as HomeUiState.Content).memoryPill)
    }

    @Test
    fun should_sortCoverlessPoolItemsLast_when_buildingGrid() = runTest {
        val repository = memorySignalRepository(profile = "subsonic-covers")
        coEvery { repository.getAlbumMemoryCandidates(any()) } returns emptyList()
        coEvery { repository.countNotes() } returns 0
        // Playlists p1/p2 have no artwork, p3/p4 do.
        coEvery { repository.getPlaylists() } returns listOf("p1", "p2", "p3", "p4").map { rawId ->
            Playlist(
                id = MediaId.subsonic(rawId),
                name = "Playlist $rawId",
                owner = "owner",
                coverArt = if (rawId == "p1" || rawId == "p2") null else com.gpo.yoin.data.model.CoverRef.Url("https://x/$rawId"),
                songCount = 5,
                durationSec = 600,
            )
        }
        every { repository.resolveCoverUrl(any(), any()) } answers {
            (firstArg<com.gpo.yoin.data.model.CoverRef?>() as? com.gpo.yoin.data.model.CoverRef.Url)?.url
        }

        val viewModel = homeViewModel(repository, "subsonic-covers")
        advanceUntilIdle()

        val grid = (viewModel.uiState.value as HomeUiState.Content).widgetGrid
        val firstArtless = grid.indexOfFirst { it.coverArtUrl == null }
        val lastWithArt = grid.indexOfLast { it.coverArtUrl != null }
        assertTrue(grid.any { it.entityType == MemoryEntityType.PLAYLIST })
        assertTrue(firstArtless == -1 || firstArtless > lastWithArt)
    }

    @Test
    fun should_dropArtlessCards_when_artCanFillTheShelf() = runTest {
        // The Apple Music shape: no tracks, 8 albums with art, 6 playlists of
        // which 2 are artless → the phone's 12 cells are all art, and the two
        // artless ones trail the deeper list.
        val repository = memorySignalRepository(profile = "applemusic-art")
        coEvery { repository.getAlbumMemoryCandidates(any()) } returns emptyList()
        coEvery { repository.countNotes() } returns 0
        coEvery { repository.getAlbumList("random", any(), any()) } returns
            (1..8).map { album("al$it", "Album $it").copy(coverArt = com.gpo.yoin.data.model.CoverRef.Url("https://x/al$it")) }
        coEvery { repository.getPlaylists() } returns (1..6).map { index ->
            Playlist(
                id = MediaId.subsonic("pl$index"),
                name = "Playlist $index",
                owner = "owner",
                coverArt = if (index <= 2) null else com.gpo.yoin.data.model.CoverRef.Url("https://x/pl$index"),
                songCount = 5,
                durationSec = 600,
            )
        }
        every { repository.resolveCoverUrl(any(), any()) } answers {
            (firstArg<com.gpo.yoin.data.model.CoverRef?>() as? com.gpo.yoin.data.model.CoverRef.Url)?.url
        }

        val viewModel = homeViewModel(repository, "applemusic-art")
        advanceUntilIdle()

        val grid = (viewModel.uiState.value as HomeUiState.Content).widgetGrid
        assertEquals(14, grid.size)
        assertTrue(trimToPhoneShelf(grid).let { it.size == 12 && it.all { card -> card.coverArtUrl != null } })
        assertTrue(grid.takeLast(2).all { it.coverArtUrl == null })
    }

    /** A Subsonic-scoped relaxed repository with an empty feed and no pools. */
    private fun memorySignalRepository(
        profile: String,
        stamp: kotlinx.coroutines.flow.Flow<Long> = flowOf(),
    ): YoinRepository {
        val repository = mockk<YoinRepository>(relaxed = true)
        every { repository.currentProviderId() } returns MediaId.PROVIDER_SUBSONIC
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.getRecentActivities(limit = any()) } returns flowOf(emptyList<ActivityEvent>())
        every { repository.observeMemorySignalStamp() } returns stamp
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
        coEvery { repository.getCachedAlbumMemoryTitle(any()) } returns null
        coEvery {
            repository.replaceHomeGridPools(albums = any(), tracks = any(), playlists = any())
        } just runs
        return repository
    }

    private fun homeViewModel(repository: YoinRepository, profile: String): HomeViewModel {
        val homeLayoutStore = mockk<HomeLayoutStore>(relaxed = true)
        every { homeLayoutStore.layoutFlow(any()) } returns flowOf(null)
        return HomeViewModel(
            repository = repository,
            activeProfileId = MutableStateFlow(profile),
            homeLayoutStore = homeLayoutStore,
        )
    }

    private fun memoryCandidate(
        albumId: String,
        profile: String,
        review: Boolean = false,
        albumRating: Float? = 8f,
        average: Float? = null,
        lastWrittenAt: Long? = null,
    ): AlbumMemoryCandidate = AlbumMemoryCandidate(
        profileId = profile,
        provider = MediaId.PROVIDER_SUBSONIC,
        albumId = albumId,
        albumName = "Album $albumId",
        artistName = "Artist",
        totalTracks = 10,
        ratedTrackCount = 6,
        ratingCoverage = 0.6f,
        averageSongRating = average,
        albumRating = albumRating,
        hasAlbumReview = review,
        noteCount = 0,
        askAiCount = 0,
        firstPlayedAt = null,
        lastPlayedAt = null,
        lastWrittenAt = lastWrittenAt,
        playCount = 0,
        neoDbSynced = false,
        isMemoryEligible = true,
        year = null,
        durationSeconds = null,
        coverArtUrl = null,
    )

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
