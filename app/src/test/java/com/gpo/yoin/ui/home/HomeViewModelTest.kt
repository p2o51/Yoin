package com.gpo.yoin.ui.home

import app.cash.turbine.test
import com.gpo.yoin.data.home.FakeHomeLayoutDao
import com.gpo.yoin.data.home.HomeLayoutStore
import com.gpo.yoin.data.home.HomeSectionPref
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.local.SongMemoryAggregate
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.memory.AlbumMemoryTitleSource
import com.gpo.yoin.data.memory.RediscoverSongSource
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.testutil.MainDispatcherRule
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.memories.ResolvedMemoryTitle
import com.gpo.yoin.ui.memories.copy.MemoryProseLanguage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        every { repository.activeProviderId } returns flowOf(MediaId.PROVIDER_SPOTIFY)
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
    fun recently_added_keeps_only_last_30_days_newest_first() = runTest {
        val repository = mockk<YoinRepository>(relaxed = true)
        // Distinct profile id: HomeViewModel's static homeContentCache is keyed
        // by provider|profile and persists across instances, so reusing another
        // test's key would leak that test's cached content into this one.
        val profileId = MutableStateFlow("spotify-recent")

        every { repository.currentProviderId() } returns MediaId.PROVIDER_SPOTIFY
        every { repository.activeProviderId } returns flowOf(MediaId.PROVIDER_SPOTIFY)
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
                savedTrack("old", now.minus(40, ChronoUnit.DAYS).toString()),
                savedTrack("recent", now.minus(2, ChronoUnit.DAYS).toString()),
                savedTrack("zoneless", zoneless),
                savedTrack("newest", now.minus(1, ChronoUnit.HOURS).toString()),
                savedTrack("no-date", ""),
            ),
            albums = listOf(
                album("album-old", "Old").copy(addedAt = now.minus(31, ChronoUnit.DAYS).toString()),
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
        every { repository.activeProviderId } returns flowOf(MediaId.PROVIDER_SUBSONIC)
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
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
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
        // 首页 memory 槽位渲染 Memory 标题（v2.2 印章卡决定）——不再是乐评全文；标题由 memoryTitle 解析器给出。
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
            // The shared resolver (Memories / album page / Home) hands back the cached AI title.
            memoryTitle = { candidate ->
                ResolvedMemoryTitle(
                    text = "Still my favourite",
                    source = AlbumMemoryTitleSource.AI,
                    canRestoreGenerated = false,
                    generatedText = "Still my favourite",
                    generatedSource = AlbumMemoryTitleSource.AI,
                    proseLanguage = MemoryProseLanguage.EN,
                    albumName = candidate.albumName,
                )
            },
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
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
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
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            memoryCandidate("a1", profile = "subsonic-once", review = true),
        )
        coEvery { repository.countNotes() } returns 1

        homeViewModel(repository, "subsonic-once")
        advanceUntilIdle()

        // Pill and the grid's memory 1×2 share ONE build, at the deck's pool size.
        coVerify(exactly = 1) { repository.getAlbumMemoryCandidates(48, true) }
    }

    @Test
    fun should_preferAnotherAlbumForJbiMemoryCard_when_pillShowsLatest() = runTest {
        val repository = memorySignalRepository(profile = "subsonic-jbi")
        // The plain recipe would crown "latest" (the only review) for the 1×2
        // too; the pill already shows it, so the grid takes "older".
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
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
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } answers { candidates }
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
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns emptyList()
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
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } answers {
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
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
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
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } answers {
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
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns emptyList()
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
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns emptyList()
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

    // ── Layout writes ──────────────────────────────────────────────────

    @Test
    fun should_notWriteLayout_when_layoutUnchanged() = runTest {
        val repository = memorySignalRepository(profile = "subsonic-layout-same")
        val dao = FakeHomeLayoutDao()
        val homeLayoutStore = HomeLayoutStore(dao)
        homeLayoutStore.setLayout("subsonic-layout-same", HomeLayout.Default.moved(HomeSection.Rediscover, 0).toPrefs())
        val viewModel = homeViewModel(repository, "subsonic-layout-same", homeLayoutStore = homeLayoutStore)
        advanceUntilIdle()

        viewModel.setHomeLayout(viewModel.homeLayout.value)
        viewModel.setHomeLayout(viewModel.homeLayout.value.copy(newSections = emptySet()))
        advanceUntilIdle()

        assertEquals(1, dao.upserts)
    }

    @Test
    fun should_clearLayout_when_layoutEqualsDefault() = runTest {
        val repository = memorySignalRepository(profile = "subsonic-layout-default")
        val dao = FakeHomeLayoutDao()
        val homeLayoutStore = HomeLayoutStore(dao)
        homeLayoutStore.setLayout("subsonic-layout-default", legacyPrefs())
        val viewModel = homeViewModel(repository, "subsonic-layout-default", homeLayoutStore = homeLayoutStore)
        advanceUntilIdle()
        assertEquals(setOf(HomeSection.Rediscover), viewModel.homeLayout.value.newSections)

        viewModel.setHomeLayout(viewModel.homeLayout.value.reset())
        advanceUntilIdle()

        // Back to "never customized": Rediscover follows its default again.
        assertNull(dao.raw("subsonic-layout-default"))
        assertEquals(HomeLayout.Default, viewModel.homeLayout.value)
    }

    @Test
    fun should_carryRetainedIds_when_layoutArrivesWithout() = runTest {
        val repository = memorySignalRepository(profile = "subsonic-layout-retained")
        val dao = FakeHomeLayoutDao()
        val homeLayoutStore = HomeLayoutStore(dao)
        homeLayoutStore.setLayout(
            "subsonic-layout-retained",
            HomeLayout.Default.toPrefs() + HomeSectionPref(id = "your_tracks", enabled = true),
        )
        val viewModel = homeViewModel(repository, "subsonic-layout-retained", homeLayoutStore = homeLayoutStore)
        advanceUntilIdle()

        // The old editor rebuilds HomeLayout(sections) and drops retained ids.
        val moved = HomeLayout.Default.moved(HomeSection.Activities, 3)
        viewModel.setHomeLayout(HomeLayout(moved.sections))
        advanceUntilIdle()

        assertEquals(
            moved.toPrefs() + HomeSectionPref(id = "your_tracks", enabled = true),
            homeLayoutStore.layoutFlow("subsonic-layout-retained").first(),
        )

        // Default sections with a retained id are still a customization: kept, not cleared.
        viewModel.setHomeLayout(HomeLayout.Default)
        advanceUntilIdle()

        assertEquals(
            HomeLayout.Default.toPrefs() + HomeSectionPref(id = "your_tracks", enabled = true),
            homeLayoutStore.layoutFlow("subsonic-layout-retained").first(),
        )
    }

    // ── Edit-mode freeze ───────────────────────────────────────────────

    @Test
    fun should_queueContent_when_editing() = runTest {
        val stamp = MutableStateFlow(0L)
        val repository = memorySignalRepository(profile = "subsonic-freeze", stamp = stamp)
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns emptyList()
        var notes = 1
        coEvery { repository.countNotes() } answers { notes }
        val viewModel = homeViewModel(repository, "subsonic-freeze")
        advanceUntilIdle()

        viewModel.uiState.test {
            assertEquals(1, (awaitItem() as HomeUiState.Content).memoryPill?.noteCount)

            viewModel.setEditing(true)
            notes = 2
            stamp.value = 1L
            advanceUntilIdle()
            expectNoEvents()

            viewModel.setEditing(false)
            assertEquals(2, (awaitItem() as HomeUiState.Content).memoryPill?.noteCount)
        }
    }

    @Test
    fun should_publishLatestQueued_when_editingEnds() = runTest {
        val stamp = MutableStateFlow(0L)
        val activities = MutableStateFlow(emptyList<ActivityEvent>())
        val repository = memorySignalRepository(profile = "subsonic-thaw", stamp = stamp)
        every { repository.getRecentActivities(limit = any()) } returns activities
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns emptyList()
        var notes = 1
        coEvery { repository.countNotes() } answers { notes }
        val viewModel = homeViewModel(repository, "subsonic-thaw")
        advanceUntilIdle()

        viewModel.uiState.test {
            awaitItem()
            viewModel.setEditing(true)
            // Three splices while frozen, each building on the queued one.
            notes = 2
            stamp.value = 1L
            advanceUntilIdle()
            activities.value = listOf(artistVisit("ar1"))
            advanceUntilIdle()
            notes = 3
            stamp.value = 2L
            advanceUntilIdle()
            expectNoEvents()

            viewModel.setEditing(false)
            val published = awaitItem() as HomeUiState.Content
            assertEquals(3, published.memoryPill?.noteCount)
            assertEquals(listOf("ar1"), published.activities.map { it.entityId })
            expectNoEvents()
        }
    }

    // ── Edit-session hints ─────────────────────────────────────────────

    @Test
    fun should_snapshotHintsBeforeRecording_when_editSessionStarts() = runTest {
        val repository = memorySignalRepository(profile = "subsonic-hints")
        val homeLayoutStore = mockk<HomeLayoutStore>(relaxed = true)
        every { homeLayoutStore.layoutFlow(any()) } returns flowOf(legacyPrefs())
        val hintStore = HomeEditHintStore.InMemory().apply { recordEditSession() }
        val viewModel = homeViewModel(
            repository,
            "subsonic-hints",
            homeLayoutStore = homeLayoutStore,
            homeEditHintStore = hintStore,
        )
        advanceUntilIdle()

        // One session so far → this one still shows the hint; Rediscover is unseen.
        assertEquals(
            HomeEditSessionHints(showHeaderHint = true, newBadges = setOf(HomeSection.Rediscover)),
            viewModel.onEditSessionStarted(),
        )
        assertEquals(2, hintStore.editSessionCount())
        assertEquals(setOf("rediscover"), hintStore.seenSectionIds())

        assertEquals(HomeEditSessionHints(), viewModel.onEditSessionStarted())
        // Entering edit never writes the layout (Q6a).
        coVerify(exactly = 0) { homeLayoutStore.setLayout(any(), any()) }
        coVerify(exactly = 0) { homeLayoutStore.clearLayout(any()) }
    }

    @Test
    fun should_reemitUnseenNewSections_when_editSessionMarksThemSeen() = runTest {
        val repository = memorySignalRepository(profile = "subsonic-unseen")
        val homeLayoutStore = mockk<HomeLayoutStore>(relaxed = true)
        every { homeLayoutStore.layoutFlow(any()) } returns flowOf(legacyPrefs())
        val viewModel = homeViewModel(repository, "subsonic-unseen", homeLayoutStore = homeLayoutStore)
        advanceUntilIdle()

        viewModel.unseenNewSections.test {
            assertEquals(setOf(HomeSection.Rediscover), awaitItem())

            viewModel.onEditSessionStarted()

            assertEquals(emptySet<HomeSection>(), awaitItem())
        }
    }

    // ── Rediscover ─────────────────────────────────────────────────────

    @Test
    fun should_feedPillAndJbiFromEligibleOnly_when_poolHasIneligible() = runTest {
        val profile = "subsonic-rd-eligible"
        val repository = memorySignalRepository(profile = profile)
        // "loud" is the newest write but not a Memory: neither the pill nor the
        // grid's 1×2 may see it, though the build now includes it.
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            rediscoverCandidate(
                "loud",
                profile,
                eligible = false,
                lastPlayedFromHistoryAt = null,
                lastWrittenAt = 99_000L,
            ),
            memoryCandidate("kept", profile = profile, review = true, lastWrittenAt = 1_000L),
        )
        coEvery { repository.countNotes() } returns 0

        val viewModel = homeViewModel(repository, profile, nowMillis = { NOW })
        advanceUntilIdle()

        val content = viewModel.uiState.value as HomeUiState.Content
        assertEquals("kept", content.memoryPill?.latest?.albumId?.rawId)
        val memoryCard = content.widgetGrid.single { it.target is HomeWidgetTarget.MemoryFocus }
        assertEquals("grid-memory:${MediaId.PROVIDER_SUBSONIC}:kept", memoryCard.stableId)
        coVerify(exactly = 1) { repository.getAlbumMemoryCandidates(48, true) }
    }

    @Test
    fun should_exposeRediscover_when_poolHasStaleAlbumsWithMemories() = runTest {
        val profile = "subsonic-rd-expose"
        val repository = memorySignalRepository(profile = profile)
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            // Notes only, no score: comes back after every scored album.
            rediscoverCandidate("noted", profile, albumRating = null).copy(
                ratedTrackCount = 0,
                ratingCoverage = 0f,
                noteCount = 2,
            ),
            rediscoverCandidate("stale", profile, albumRating = 9f, cover = "https://x/stale"),
            rediscoverCandidate("recent", profile, albumRating = 9.5f, lastPlayedFromHistoryAt = NOW - 10 * DAY),
            // Below the old 8.0 bar: still a memory, so it comes back.
            rediscoverCandidate("low", profile, albumRating = 7.5f),
            // Played long ago but nothing kept on it: not a memory.
            rediscoverCandidate("played", profile, albumRating = null).copy(ratedTrackCount = 0, ratingCoverage = 0f),
        )
        coEvery { repository.countNotes() } returns 0

        val viewModel = homeViewModel(repository, profile, nowMillis = { NOW })
        advanceUntilIdle()

        fun expected(id: String, score: Float?, cover: String? = null, notes: Int = 0, ratedTracks: Int = 6) =
            HomeRediscoverItem(
                albumId = MediaId.subsonic(id),
                albumName = "Album $id",
                artistName = "Artist",
                coverArtUrl = cover,
                score = score,
                scoreText = score?.let { "%.1f".format(java.util.Locale.US, it) },
                scoreKind = if (score == null) MemoryScoreKind.NONE else MemoryScoreKind.ALBUM_RATING,
                lastPlayedAt = NOW - 200 * DAY,
                firstPlayedAt = NOW - 600 * DAY,
                playCount = 23,
                noteCount = notes,
                ratedTrackCount = ratedTracks,
            )
        assertEquals(
            listOf(
                expected("stale", 9f, cover = "https://x/stale"),
                expected("low", 7.5f),
                expected("noted", null, notes = 2, ratedTracks = 0),
            ),
            (viewModel.uiState.value as HomeUiState.Content).rediscover,
        )
    }

    @Test
    fun should_dedupeRediscoverAgainstPillAndJbiMemory() = runTest {
        val profile = "subsonic-rd-dedupe"
        val repository = memorySignalRepository(profile = profile)
        // All three are stale and rated high; the pill shows "pillAlbum" (newest
        // write) and the grid's 1×2 takes "jbiAlbum", so only "free" is left.
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            rediscoverCandidate(
                "pillAlbum",
                profile,
                albumRating = 9.5f,
                eligible = true,
                review = true,
                lastWrittenAt = 9_000L,
            ),
            rediscoverCandidate("jbiAlbum", profile, albumRating = 9f, eligible = true, lastWrittenAt = 1_000L),
            rediscoverCandidate("free", profile, albumRating = 8f),
        )
        coEvery { repository.countNotes() } returns 0

        val viewModel = homeViewModel(repository, profile, nowMillis = { NOW })
        advanceUntilIdle()

        val content = viewModel.uiState.value as HomeUiState.Content
        assertEquals("pillAlbum", content.memoryPill?.latest?.albumId?.rawId)
        assertEquals(
            "grid-memory:${MediaId.PROVIDER_SUBSONIC}:jbiAlbum",
            content.widgetGrid.single { it.target is HomeWidgetTarget.MemoryFocus }.stableId,
        )
        assertEquals(listOf("free"), content.rediscover.map { it.albumId.rawId })
    }

    @Test
    fun should_leaveAlbumsAndSongsOutOfRediscover_when_recentlyAddedShowsThem() = runTest {
        val profile = "subsonic-rd-recently-added"
        val stamp = MutableStateFlow(0L)
        val repository = memorySignalRepository(profile = profile, stamp = stamp)
        // "fresh" was saved yesterday but hasn't played in Yoin for 200 days: it
        // qualifies for both shelves, and Recently Added keeps it.
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            rediscoverCandidate("fresh", profile, albumRating = 9.5f),
            rediscoverCandidate("free", profile, albumRating = 8f),
        )
        coEvery { repository.countNotes() } returns 0
        val yesterday = Instant.now().minus(1, ChronoUnit.DAYS).toString()
        coEvery { repository.getStarred() } returns Starred(
            tracks = listOf(savedTrack("s-fresh", yesterday)),
            albums = listOf(album("fresh", "Fresh").copy(addedAt = yesterday)),
        )
        val source = RediscoverSongSource { _, _, _, _ ->
            listOf(
                rediscoverSong("s-fresh", rating = 9f),
                rediscoverSong("s-free", rating = 8.5f),
            )
        }

        val viewModel = homeViewModel(repository, profile, nowMillis = { NOW }, rediscoverSongs = source)
        advanceUntilIdle()

        val content = viewModel.uiState.value as HomeUiState.Content
        assertEquals(listOf("fresh"), content.recentlyAddedAlbums.map { it.id.rawId })
        assertEquals(listOf("s-fresh"), content.recentlyAddedTracks.map { it.id.rawId })
        val expected = listOf("rediscover-song:subsonic:s-free", "rediscover:subsonic:free")
        assertEquals(expected, content.rediscover.map { it.shelfKey })

        // A live memory tick re-picks against the Recently Added on screen.
        stamp.value = 1L
        advanceUntilIdle()

        assertEquals(expected, (viewModel.uiState.value as HomeUiState.Content).rediscover.map { it.shelfKey })
    }

    @Test
    fun should_leaveASongOutOfRediscover_when_recentlyAddedShowsItsAlbum() = runTest {
        val profile = "subsonic-rd-recently-added-album-song"
        val stamp = MutableStateFlow(0L)
        val repository = memorySignalRepository(profile = profile, stamp = stamp)
        // Device QA: with "fresh" off the shelf as an album, its noted track came
        // back as a song card wearing the same cover Recently Added shows.
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            rediscoverCandidate("fresh", profile, albumRating = 9.5f),
            rediscoverCandidate("free", profile, albumRating = 8f),
        )
        coEvery { repository.countNotes() } returns 0
        val yesterday = Instant.now().minus(1, ChronoUnit.DAYS).toString()
        coEvery { repository.getStarred() } returns Starred(
            albums = listOf(album("fresh", "Fresh").copy(addedAt = yesterday)),
        )
        val source = RediscoverSongSource { _, _, _, _ ->
            listOf(
                rediscoverSong("s-in-fresh", rating = 9f, note = "winter tape", albumId = "fresh"),
                rediscoverSong("s-free", rating = 8.5f),
            )
        }

        val viewModel = homeViewModel(repository, profile, nowMillis = { NOW }, rediscoverSongs = source)
        advanceUntilIdle()

        val expected = listOf("rediscover-song:subsonic:s-free", "rediscover:subsonic:free")
        assertEquals(expected, (viewModel.uiState.value as HomeUiState.Content).rediscover.map { it.shelfKey })

        stamp.value = 1L
        advanceUntilIdle()

        assertEquals(expected, (viewModel.uiState.value as HomeUiState.Content).rediscover.map { it.shelfKey })
    }

    @Test
    fun should_removeRediscoverCard_when_itsAlbumPlays() = runTest {
        val profile = "subsonic-rd-remove"
        val plays = MutableStateFlow<PlayHistory?>(null)
        val repository = memorySignalRepository(profile = profile, plays = plays)
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            rediscoverCandidate("a", profile, albumRating = 9f),
            rediscoverCandidate("b", profile, albumRating = 8.5f),
        )
        coEvery { repository.countNotes() } returns 0
        val viewModel = homeViewModel(repository, profile, nowMillis = { NOW })
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), rediscoverIds(viewModel))

        plays.value = play(id = 1, albumId = "a", profile = profile, playedAt = NOW + 1_000L)
        advanceUntilIdle()

        assertEquals(listOf("b"), rediscoverIds(viewModel))
    }

    @Test
    fun should_ignoreExistingLatestPlay_when_subscriptionStarts() = runTest {
        val profile = "subsonic-rd-existing"
        // The newest row already in history (a returning user's last play,
        // months ago) is on the shelf's album: it must stay.
        val plays = MutableStateFlow<PlayHistory?>(
            play(id = 1, albumId = "a", profile = profile, playedAt = NOW - 200 * DAY),
        )
        val repository = memorySignalRepository(profile = profile, plays = plays)
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            rediscoverCandidate("a", profile, albumRating = 9f),
        )
        coEvery { repository.countNotes() } returns 0

        val viewModel = homeViewModel(repository, profile, nowMillis = { NOW })
        advanceUntilIdle()

        assertEquals(listOf("a"), rediscoverIds(viewModel))
    }

    @Test
    fun should_notResurrectPlayedCard_when_staleTickLands() = runTest {
        val profile = "subsonic-rd-resurrect"
        val stamp = MutableStateFlow(0L)
        val plays = MutableStateFlow<PlayHistory?>(null)
        val repository = memorySignalRepository(profile = profile, stamp = stamp, plays = plays)
        // The builder still reports "a" as unplayed: its history hasn't caught
        // up with the play yet.
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            rediscoverCandidate("a", profile, albumRating = 9f),
        )
        var notes = 1
        coEvery { repository.countNotes() } answers { notes }
        val viewModel = homeViewModel(repository, profile, nowMillis = { NOW })
        advanceUntilIdle()
        assertEquals(listOf("a"), rediscoverIds(viewModel))

        plays.value = play(id = 1, albumId = "a", profile = profile, playedAt = NOW + 1_000L)
        advanceUntilIdle()
        notes = 2
        stamp.value = 1L
        advanceUntilIdle()

        val content = viewModel.uiState.value as HomeUiState.Content
        assertEquals(2, content.memoryPill?.noteCount)
        assertEquals(emptyList<String>(), content.rediscover.map { it.albumId.rawId })

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), rediscoverIds(viewModel))
    }

    @Test
    fun should_queueRediscoverRemoval_when_editing() = runTest {
        val profile = "subsonic-rd-queue"
        val plays = MutableStateFlow<PlayHistory?>(null)
        val repository = memorySignalRepository(profile = profile, plays = plays)
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            rediscoverCandidate("a", profile, albumRating = 9f),
            rediscoverCandidate("b", profile, albumRating = 8.5f),
        )
        coEvery { repository.countNotes() } returns 0
        val viewModel = homeViewModel(repository, profile, nowMillis = { NOW })
        advanceUntilIdle()

        viewModel.uiState.test {
            assertEquals(listOf("a", "b"), (awaitItem() as HomeUiState.Content).rediscover.map { it.albumId.rawId })

            viewModel.setEditing(true)
            plays.value = play(id = 1, albumId = "a", profile = profile, playedAt = NOW + 1_000L)
            advanceUntilIdle()
            expectNoEvents()

            viewModel.setEditing(false)
            assertEquals(listOf("b"), (awaitItem() as HomeUiState.Content).rediscover.map { it.albumId.rawId })
        }
    }

    @Test
    fun should_applyQueuedContent_when_editingEnds() = runTest {
        val profile = "subsonic-rd-thaw"
        val stamp = MutableStateFlow(0L)
        val plays = MutableStateFlow<PlayHistory?>(null)
        val repository = memorySignalRepository(profile = profile, stamp = stamp, plays = plays)
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            rediscoverCandidate("a", profile, albumRating = 9f),
            rediscoverCandidate("b", profile, albumRating = 8.5f),
        )
        var notes = 1
        coEvery { repository.countNotes() } answers { notes }
        val viewModel = homeViewModel(repository, profile, nowMillis = { NOW })
        advanceUntilIdle()

        viewModel.uiState.test {
            awaitItem()
            viewModel.setEditing(true)
            // A removal, then a signal tick that builds on the queued state.
            plays.value = play(id = 1, albumId = "a", profile = profile, playedAt = NOW + 1_000L)
            advanceUntilIdle()
            notes = 2
            stamp.value = 1L
            advanceUntilIdle()
            expectNoEvents()

            viewModel.setEditing(false)
            val published = awaitItem() as HomeUiState.Content
            assertEquals(listOf("b"), published.rediscover.map { it.albumId.rawId })
            assertEquals(2, published.memoryPill?.noteCount)
            expectNoEvents()
        }
    }

    @Test
    fun should_keepRediscover_when_tickCannotResolveSignals() = runTest {
        val profile = "subsonic-rd-keep"
        val stamp = MutableStateFlow(0L)
        val repository = memorySignalRepository(profile = profile, stamp = stamp)
        var fail = false
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } answers {
            if (fail) error("cold cache") else listOf(rediscoverCandidate("a", profile, albumRating = 9f))
        }
        coEvery { repository.countNotes() } returns 0
        val viewModel = homeViewModel(repository, profile, nowMillis = { NOW })
        advanceUntilIdle()
        assertEquals(listOf("a"), rediscoverIds(viewModel))

        fail = true
        stamp.value = 1L
        advanceUntilIdle()
        assertEquals(listOf("a"), rediscoverIds(viewModel))

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(listOf("a"), rediscoverIds(viewModel))
    }

    @Test
    fun should_mixSongsIntoRediscover_when_songSourceHasRatedAndNotedSongs() = runTest {
        val profile = "subsonic-rd-songs"
        val repository = memorySignalRepository(profile = profile)
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            rediscoverCandidate("a", profile, albumRating = 9f),
            rediscoverCandidate("low", profile, albumRating = 6f),
        )
        coEvery { repository.countNotes() } returns 0
        val loads = mutableListOf<List<Any>>()
        val source = RediscoverSongSource { provider, profileId, playedBefore, limit ->
            loads += listOf(provider, profileId, playedBefore, limit)
            listOf(
                rediscoverSong("s1", rating = 8f, albumId = "x", coverArtId = "cover-s1"),
                rediscoverSong("s2", rating = null, note = "worth the wait", albumId = "y"),
            )
        }

        val viewModel = homeViewModel(repository, profile, nowMillis = { NOW }, rediscoverSongs = source)
        advanceUntilIdle()

        val shelf = (viewModel.uiState.value as HomeUiState.Content).rediscover
        assertEquals(
            listOf(
                "rediscover:subsonic:a",
                "rediscover-song:subsonic:s1",
                "rediscover:subsonic:low",
                "rediscover-song:subsonic:s2",
            ),
            shelf.map { it.shelfKey },
        )
        val rated = shelf[1]
        assertEquals(
            HomeRediscoverItem(
                albumId = MediaId.subsonic("x"),
                albumName = "Album x",
                artistName = "Artist",
                coverArtUrl = null,
                score = 8f,
                scoreText = "8.0",
                scoreKind = MemoryScoreKind.ALBUM_RATING,
                lastPlayedAt = NOW - 150 * DAY,
                firstPlayedAt = NOW - 400 * DAY,
                playCount = 7,
                noteCount = 0,
                song = Track(
                    id = MediaId.subsonic("s1"),
                    title = "Song s1",
                    artist = "Artist",
                    artistId = null,
                    album = "Album x",
                    albumId = MediaId.subsonic("x"),
                    coverArt = com.gpo.yoin.data.model.CoverRef.fromStorageKey("cover-s1"),
                    durationSec = 200,
                    trackNumber = null,
                    year = null,
                    genre = null,
                    userRating = null,
                ),
            ),
            rated,
        )
        assertEquals("Song s1", rated.title)
        val noted = shelf[3]
        assertNull(noted.score)
        assertEquals(MemoryScoreKind.NONE, noted.scoreKind)
        assertEquals("worth the wait", noted.noteSnippet)
        assertEquals(listOf(listOf<Any>(MediaId.PROVIDER_SUBSONIC, profile, NOW - 90 * DAY, 64)), loads)
    }

    @Test
    fun should_keepSongOffShelf_when_itsAlbumIsAnAlbumCard() = runTest {
        val profile = "subsonic-rd-song-album"
        val repository = memorySignalRepository(profile = profile)
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            rediscoverCandidate("a", profile, albumRating = 7f),
        )
        coEvery { repository.countNotes() } returns 0
        val source = RediscoverSongSource { _, _, _, _ ->
            listOf(rediscoverSong("from-a", rating = 9.5f, albumId = "a"), rediscoverSong("free", rating = 6f))
        }

        val viewModel = homeViewModel(repository, profile, nowMillis = { NOW }, rediscoverSongs = source)
        advanceUntilIdle()

        assertEquals(
            listOf("rediscover:subsonic:a", "rediscover-song:subsonic:free"),
            (viewModel.uiState.value as HomeUiState.Content).rediscover.map { it.shelfKey },
        )
    }

    @Test
    fun should_dropTheJbiNotedSong_when_itWouldComeBack() = runTest {
        val profile = "subsonic-rd-song-noted"
        val repository = memorySignalRepository(profile = profile)
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns emptyList()
        coEvery { repository.countNotes() } returns 1
        // The grid's noted-track 1×2 is s2's newest note.
        coEvery { repository.getRecentSongNotes(any()) } returns listOf(
            SongNote(
                id = "note-1",
                profileId = profile,
                trackId = "s2",
                provider = MediaId.PROVIDER_SUBSONIC,
                content = "worth the wait",
                createdAt = 1L,
                updatedAt = 1L,
                title = "Song s2",
                artist = "Artist",
            ),
        )
        val source = RediscoverSongSource { _, _, _, _ ->
            listOf(rediscoverSong("s1", rating = 8f), rediscoverSong("s2", rating = null, note = "worth the wait"))
        }

        val viewModel = homeViewModel(repository, profile, nowMillis = { NOW }, rediscoverSongs = source)
        advanceUntilIdle()

        assertEquals(
            listOf("rediscover-song:subsonic:s1"),
            (viewModel.uiState.value as HomeUiState.Content).rediscover.map { it.shelfKey },
        )
    }

    @Test
    fun should_removeSongCard_when_theSongOrItsAlbumPlays() = runTest {
        val profile = "subsonic-rd-song-remove"
        val plays = MutableStateFlow<PlayHistory?>(null)
        val repository = memorySignalRepository(profile = profile, plays = plays)
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns emptyList()
        coEvery { repository.countNotes() } returns 0
        val source = RediscoverSongSource { _, _, _, _ ->
            listOf(
                rediscoverSong("song-1", rating = 9f, albumId = "x"),
                rediscoverSong("s-y", rating = 8f, albumId = "y"),
                rediscoverSong("s-z", rating = 7f, albumId = "z"),
            )
        }
        val viewModel = homeViewModel(repository, profile, nowMillis = { NOW }, rediscoverSongs = source)
        advanceUntilIdle()
        assertEquals(3, (viewModel.uiState.value as HomeUiState.Content).rediscover.size)

        // play(id = 1) is song-1 itself; play(id = 2) is another track of album y.
        plays.value = play(id = 1, albumId = "x", profile = profile, playedAt = NOW + 1_000L)
        advanceUntilIdle()
        plays.value = play(id = 2, albumId = "y", profile = profile, playedAt = NOW + 2_000L)
        advanceUntilIdle()

        assertEquals(
            listOf("rediscover-song:subsonic:s-z"),
            (viewModel.uiState.value as HomeUiState.Content).rediscover.map { it.shelfKey },
        )
        // A rebuild doesn't bring them back.
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(
            listOf("rediscover-song:subsonic:s-z"),
            (viewModel.uiState.value as HomeUiState.Content).rediscover.map { it.shelfKey },
        )
    }

    @Test
    fun should_keepAlbumsOnShelf_when_songSourceFails() = runTest {
        val profile = "subsonic-rd-song-fail"
        val repository = memorySignalRepository(profile = profile)
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            rediscoverCandidate("a", profile, albumRating = 9f),
        )
        coEvery { repository.countNotes() } returns 0
        val source = RediscoverSongSource { _, _, _, _ -> error("database closed") }

        val viewModel = homeViewModel(repository, profile, nowMillis = { NOW }, rediscoverSongs = source)
        advanceUntilIdle()

        assertEquals(listOf("a"), rediscoverIds(viewModel))
    }

    @Test
    fun should_snapshotNewBadgesAndMarkSeen_when_editSessionStarts() = runTest {
        val profile = "subsonic-rd-badges"
        val repository = memorySignalRepository(profile = profile)
        val homeLayoutStore = mockk<HomeLayoutStore>(relaxed = true)
        every { homeLayoutStore.layoutFlow(any()) } returns flowOf(legacyPrefs())
        val hintStore = HomeEditHintStore.InMemory()
        val viewModel = homeViewModel(
            repository,
            profile,
            homeLayoutStore = homeLayoutStore,
            homeEditHintStore = hintStore,
        )
        advanceUntilIdle()
        assertEquals(setOf(HomeSection.Rediscover), viewModel.unseenNewSections.value)

        assertEquals(setOf(HomeSection.Rediscover), viewModel.onEditSessionStarted().newBadges)
        advanceUntilIdle()

        assertEquals(setOf("rediscover"), hintStore.seenSectionIds())
        assertEquals(emptySet<HomeSection>(), viewModel.unseenNewSections.value)
        // Seen once is seen: the next session badges nothing.
        assertEquals(emptySet<HomeSection>(), viewModel.onEditSessionStarted().newBadges)
    }

    @Test
    fun should_showHeaderHintOnlyFirstTwoSessions_when_editSessionsStart() = runTest {
        val profile = "subsonic-rd-hint"
        val repository = memorySignalRepository(profile = profile)
        val hintStore = HomeEditHintStore.InMemory()
        val viewModel = homeViewModel(repository, profile, homeEditHintStore = hintStore)
        advanceUntilIdle()

        val shown = List(3) { viewModel.onEditSessionStarted().showHeaderHint }

        assertEquals(listOf(true, true, false), shown)
        assertEquals(3, hintStore.editSessionCount())
    }

    /** A Subsonic-scoped relaxed repository with an empty feed and no pools. */
    @Test
    fun should_loadContent_when_sourceArrivesAfterInit() = runTest {
        // Cold start: the profile id is restored synchronously, its source is
        // built a beat later. Home holds Loading through that beat and loads
        // when the source lands, instead of publishing a feed read from none.
        val profile = "subsonic-cold-start"
        val provider = MutableStateFlow<String?>(null)
        val repository = scopedRepository(profile, provider)
        coEvery { repository.getStarred() } answers {
            if (provider.value == null) throw IOException("No profile configured")
            Starred(albums = listOf(recentAlbum("cold-added")))
        }
        val viewModel = homeViewModel(repository, profile)

        viewModel.uiState.test {
            assertEquals(HomeUiState.Loading, awaitItem())
            advanceTimeBy(1_000)
            expectNoEvents()
            coVerify(exactly = 0) { repository.getStarred() }

            provider.value = MediaId.PROVIDER_SUBSONIC
            val content = awaitItem() as HomeUiState.Content
            assertEquals(listOf("cold-added"), content.recentlyAddedAlbums.map { it.id.rawId })
        }
    }

    @Test
    fun should_notDropResult_when_providerResolvesMidLoad() = runTest {
        // The source is slower than Home's hold: a load starts without one and
        // is still in flight when the provider resolves. That load gives way to
        // the provider's, which publishes — Home never stays on Loading.
        val profile = "subsonic-mid-load"
        val provider = MutableStateFlow<String?>(null)
        val repository = scopedRepository(profile, provider)
        val sourcelessStarred = CompletableDeferred<Starred>()
        coEvery { repository.getStarred() } coAnswers {
            if (provider.value == null) {
                sourcelessStarred.await()
            } else {
                Starred(albums = listOf(recentAlbum("mid-load-added")))
            }
        }
        val viewModel = homeViewModel(repository, profile)

        viewModel.uiState.test {
            assertEquals(HomeUiState.Loading, awaitItem())
            // Past the hold: the sourceless load now waits on its library read.
            advanceTimeBy(5_000)
            expectNoEvents()
            coVerify(atLeast = 1) { repository.getStarred() }

            provider.value = MediaId.PROVIDER_SUBSONIC
            val content = awaitItem() as HomeUiState.Content
            assertEquals(listOf("mid-load-added"), content.recentlyAddedAlbums.map { it.id.rawId })

            // The superseded load can't land over it.
            sourcelessStarred.complete(Starred())
            advanceUntilIdle()
            expectNoEvents()
        }
    }

    @Test
    fun should_keepGridPool_when_allPoolFetchesFail() = runTest {
        // Offline when the pools are due to rotate: all three reads fail. The
        // persisted batch is not replaced (an empty write would wipe it) and the
        // shelf shows it, however old.
        val profile = "subsonic-offline-pools"
        val repository = memorySignalRepository(profile = profile)
        val keptAlbum = album("kept-album", "Kept Album")
        every { repository.currentCapabilities() } returns setOf(Capability.RANDOM_SONGS)
        coEvery { repository.getCachedHomeGridPools(isNull(inverse = true)) } returns null
        coEvery { repository.getCachedHomeGridPools(isNull()) } returns YoinRepository.HomeGridPoolSnapshot(
            albums = listOf(keptAlbum),
            tracks = emptyList(),
            playlists = emptyList(),
            cachedAt = 1L
        )
        coEvery { repository.getAlbumList("random", any(), any()) } throws IOException("offline")
        coEvery { repository.getRandomSongs(any()) } throws IOException("offline")
        coEvery { repository.getPlaylists() } throws IOException("offline")

        val viewModel = homeViewModel(repository, profile)

        viewModel.uiState.test {
            assertEquals(HomeUiState.Loading, awaitItem())
            val content = awaitItem() as HomeUiState.Content
            assertTrue(
                content.widgetGrid.any { card ->
                    card.target == HomeWidgetTarget.AlbumDetail(keptAlbum.id.toString())
                }
            )
        }
        coVerify(exactly = 0) {
            repository.replaceHomeGridPools(albums = any(), tracks = any(), playlists = any())
        }
    }

    @Test
    fun should_keepGridPool_when_failedReadsLeaveOnlyEmptyResults() = runTest {
        // Apple Music offline: no random-songs endpoint, so the tracks pool
        // samples starred tracks — which Apple answers empty without a request.
        // That "success" mustn't let the two failed reads wipe the batch.
        val profile = "applemusic-offline-pools"
        val repository = memorySignalRepository(profile = profile)
        val keptAlbum = album("kept-apple-album", "Kept Album")
        every { repository.currentCapabilities() } returns emptySet()
        coEvery { repository.getCachedHomeGridPools(isNull(inverse = true)) } returns null
        coEvery { repository.getCachedHomeGridPools(isNull()) } returns YoinRepository.HomeGridPoolSnapshot(
            albums = listOf(keptAlbum),
            tracks = emptyList(),
            playlists = emptyList(),
            cachedAt = 1L
        )
        coEvery { repository.getAlbumList("random", any(), any()) } throws IOException("offline")
        coEvery { repository.getStarred() } returns Starred()
        coEvery { repository.getPlaylists() } throws IOException("offline")

        val viewModel = homeViewModel(repository, profile)

        viewModel.uiState.test {
            assertEquals(HomeUiState.Loading, awaitItem())
            val content = awaitItem() as HomeUiState.Content
            assertTrue(
                content.widgetGrid.any { card ->
                    card.target == HomeWidgetTarget.AlbumDetail(keptAlbum.id.toString())
                }
            )
        }
        coVerify(exactly = 0) {
            repository.replaceHomeGridPools(albums = any(), tracks = any(), playlists = any())
        }
    }

    @Test
    fun should_refreshOnce_when_accountSwitches() = runTest {
        val provider = MutableStateFlow<String?>(MediaId.PROVIDER_SUBSONIC)
        val profileId = MutableStateFlow<String?>("subsonic-switch-a")
        val revision = MutableStateFlow(0L)
        val repository = scopedRepository("subsonic-switch-a", provider)
        coEvery { repository.getRecentlyPlayedAlbums(any()) } returns emptyList()
        val viewModel = scopedViewModel(repository, profileId, revision)

        viewModel.uiState.test {
            assertEquals(HomeUiState.Loading, awaitItem())
            assertTrue(awaitItem() is HomeUiState.Content)
            coVerify(exactly = 1) { repository.getRecentlyPlayedAlbums(any()) }

            // ProfileManager.switchTo, in its order: the new account's source,
            // then setActive. The new account has nothing cached: Loading, then
            // its feed.
            provider.value = MediaId.PROVIDER_APPLE_MUSIC
            profileId.value = "applemusic-switch-b"
            assertEquals(HomeUiState.Loading, awaitItem())
            assertTrue(awaitItem() is HomeUiState.Content)
            // Then the revision tick (onSwitchCommit): the scope already loaded.
            revision.value = 1L
            advanceUntilIdle()
            expectNoEvents()
        }
        coVerify(exactly = 2) { repository.getRecentlyPlayedAlbums(any()) }
    }

    @Test
    fun should_refreshOnce_when_activeAccountIsDeleted() = runTest {
        val provider = MutableStateFlow<String?>(MediaId.PROVIDER_SUBSONIC)
        val profileId = MutableStateFlow<String?>("subsonic-delete-a")
        val revision = MutableStateFlow(0L)
        val repository = scopedRepository("subsonic-delete-a", provider)
        coEvery { repository.getRecentlyPlayedAlbums(any()) } returns emptyList()
        val viewModel = scopedViewModel(repository, profileId, revision)

        viewModel.uiState.test {
            assertEquals(HomeUiState.Loading, awaitItem())
            assertTrue(awaitItem() is HomeUiState.Content)

            // ProfileManager.delete: the source goes first (and the profile
            // list is read meanwhile), then setActive(remaining) and its new
            // source; Settings ticks the revision after delete returns.
            provider.value = null
            runCurrent()
            // The deleted account's feed comes down while the next one's source builds.
            assertEquals(HomeUiState.Loading, awaitItem())
            profileId.value = "subsonic-delete-b"
            provider.value = MediaId.PROVIDER_SUBSONIC
            assertTrue(awaitItem() is HomeUiState.Content)
            revision.value = 1L
            advanceUntilIdle()
            expectNoEvents()
        }
        coVerify(exactly = 2) { repository.getRecentlyPlayedAlbums(any()) }
    }

    @Test
    fun should_reload_when_activeAccountCredentialsChange() = runTest {
        // An edit of the active account's credentials rebuilds its source for
        // the same provider: the scope doesn't move, so the revision reloads.
        val provider = MutableStateFlow<String?>(MediaId.PROVIDER_SUBSONIC)
        val profileId = MutableStateFlow<String?>("subsonic-credentials")
        val revision = MutableStateFlow(0L)
        val repository = scopedRepository("subsonic-credentials", provider)
        var source = Any()
        every { repository.activeSourceIdentity() } answers { source }
        coEvery { repository.getRecentlyPlayedAlbums(any()) } returns emptyList()
        scopedViewModel(repository, profileId, revision)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.getRecentlyPlayedAlbums(any()) }

        // ProfileManager.update: a new source for the same provider, then the tick.
        source = Any()
        revision.value = 1L
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.getRecentlyPlayedAlbums(any()) }
    }

    @Test
    fun should_notReload_when_revisionTicksWithoutSourceRebuild() = runTest {
        // Adding or editing another account ticks the revision too; the active
        // source stays as it was, and so does Home.
        val provider = MutableStateFlow<String?>(MediaId.PROVIDER_SUBSONIC)
        val profileId = MutableStateFlow<String?>("subsonic-other-account")
        val revision = MutableStateFlow(0L)
        val repository = scopedRepository("subsonic-other-account", provider)
        coEvery { repository.getRecentlyPlayedAlbums(any()) } returns emptyList()
        scopedViewModel(repository, profileId, revision)
        advanceUntilIdle()

        revision.value = 1L
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getRecentlyPlayedAlbums(any()) }
    }

    @Test
    fun should_reload_when_credentialsChangeAfterProfileMovedWithoutTick() = runTest {
        // A profile can become active with no revision tick (the start-up Apple
        // Music migration creates and activates one). A later edit of its
        // credentials still reloads: the check is against the scope Home loaded,
        // not against where the previous tick left the profile.
        val provider = MutableStateFlow<String?>(MediaId.PROVIDER_SUBSONIC)
        val profileId = MutableStateFlow<String?>("subsonic-untold-a")
        val revision = MutableStateFlow(0L)
        val repository = scopedRepository("subsonic-untold-a", provider)
        var source = Any()
        every { repository.activeSourceIdentity() } answers { source }
        coEvery { repository.getRecentlyPlayedAlbums(any()) } returns emptyList()
        scopedViewModel(repository, profileId, revision)
        advanceUntilIdle()

        source = Any()
        profileId.value = "subsonic-untold-b"
        advanceUntilIdle()
        coVerify(exactly = 2) { repository.getRecentlyPlayedAlbums(any()) }

        source = Any()
        revision.value = 1L
        advanceUntilIdle()

        coVerify(exactly = 3) { repository.getRecentlyPlayedAlbums(any()) }
    }

    @Test
    fun should_holdOutgoingFeed_when_switchLandsInTwoBeats() = runTest {
        // ProfileManager.switchTo sets the new source, then setActive. Should the
        // two land in separate beats, the in-between scope (new provider on the
        // outgoing profile) loads nothing and shows no Loading.
        val provider = MutableStateFlow<String?>(MediaId.PROVIDER_SUBSONIC)
        val profileId = MutableStateFlow<String?>("subsonic-two-beats-a")
        val revision = MutableStateFlow(0L)
        val repository = scopedRepository("subsonic-two-beats-a", provider)
        coEvery { repository.getRecentlyPlayedAlbums(any()) } returns emptyList()
        val viewModel = scopedViewModel(repository, profileId, revision)

        viewModel.uiState.test {
            assertEquals(HomeUiState.Loading, awaitItem())
            assertTrue(awaitItem() is HomeUiState.Content)

            provider.value = MediaId.PROVIDER_APPLE_MUSIC
            runCurrent()
            advanceTimeBy(1_000)
            expectNoEvents()
            coVerify(exactly = 1) { repository.getRecentlyPlayedAlbums(any()) }

            profileId.value = "applemusic-two-beats-b"
            assertEquals(HomeUiState.Loading, awaitItem())
            assertTrue(awaitItem() is HomeUiState.Content)
        }
        coVerify(exactly = 2) { repository.getRecentlyPlayedAlbums(any()) }
    }

    @Test
    fun should_loadWithoutSource_when_profileManagerSettlesWithoutOne() = runTest {
        // Unreadable credentials (a backup restored onto another device): no
        // source is coming. Home holds only until ProfileManager says so — not
        // the whole bound, and not again on Retry.
        val profile = "subsonic-no-source"
        val provider = MutableStateFlow<String?>(null)
        val settled = MutableStateFlow(false)
        val repository = scopedRepository(profile, provider, settled)
        coEvery { repository.getRecentlyPlayedAlbums(any()) } returns emptyList()
        val viewModel = homeViewModel(repository, profile)

        advanceTimeBy(1_000)
        assertEquals(HomeUiState.Loading, viewModel.uiState.value)

        settled.value = true
        runCurrent()
        assertTrue(viewModel.uiState.value is HomeUiState.Content)
        coVerify(exactly = 1) { repository.getRecentlyPlayedAlbums(any()) }

        viewModel.refresh()
        advanceTimeBy(100)
        coVerify(exactly = 2) { repository.getRecentlyPlayedAlbums(any()) }
    }

    @Test
    fun should_rebuildSignals_when_reloadFailsBeforePublishingThem() = runTest {
        // A reload reads a new stamp for its signals, then fails elsewhere
        // while they are still being built: the feed it started from stays up,
        // built on the old stamp. That stamp's tick must still rebuild the
        // pill — the reload never published its signals.
        val profile = "subsonic-unpublished-stamp"
        val stamp = MutableStateFlow(5L)
        val repository = memorySignalRepository(profile = profile, stamp = stamp)
        var notes = 1
        var reloading = false
        every { repository.getRecentActivities(limit = any()) } answers {
            flow {
                if (reloading) {
                    delay(10)
                    throw IOException("offline")
                }
                emit(emptyList<ActivityEvent>())
            }
        }
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } coAnswers {
            // The reload's build outlasts its failure.
            if (reloading) delay(50)
            emptyList()
        }
        coEvery { repository.countNotes() } answers { notes }
        val viewModel = homeViewModel(repository, profile)
        advanceUntilIdle()
        assertEquals(1, (viewModel.uiState.value as HomeUiState.Content).memoryPill?.noteCount)

        notes = 2
        stamp.value = 6L
        reloading = true
        viewModel.refresh()
        advanceTimeBy(100)
        assertEquals(1, (viewModel.uiState.value as HomeUiState.Content).memoryPill?.noteCount)

        advanceUntilIdle()
        assertEquals(2, (viewModel.uiState.value as HomeUiState.Content).memoryPill?.noteCount)
    }

    @Test
    fun should_skipSignalRebuild_when_firstTickMatchesLoadStamp() = runTest {
        val profile = "subsonic-first-tick"
        val stamp = MutableStateFlow(5L)
        val repository = memorySignalRepository(profile = profile, stamp = stamp)
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns listOf(
            memoryCandidate("a1", profile = profile)
        )
        coEvery { repository.countNotes() } returns 1

        homeViewModel(repository, profile)
        advanceUntilIdle()

        // The stamp's first tick is the one the load built on: no second build.
        coVerify(exactly = 1) { repository.getAlbumMemoryCandidates(48, true) }

        stamp.value = 6L
        advanceUntilIdle()
        coVerify(exactly = 2) { repository.getAlbumMemoryCandidates(48, true) }
    }

    @Test
    fun should_rebuildSignals_when_stampMovesBeforeFirstTick() = runTest {
        // A note written after the load read the stamp but before the observer's
        // first (debounced) tick: that tick carries the new stamp and rebuilds.
        val profile = "subsonic-early-write"
        val stamp = MutableStateFlow(5L)
        val repository = memorySignalRepository(profile = profile, stamp = stamp)
        var notes = 1
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns emptyList()
        coEvery { repository.countNotes() } answers { notes }

        val viewModel = homeViewModel(repository, profile)
        runCurrent()
        assertEquals(1, (viewModel.uiState.value as HomeUiState.Content).memoryPill?.noteCount)

        notes = 2
        stamp.value = 6L
        advanceUntilIdle()

        assertEquals(2, (viewModel.uiState.value as HomeUiState.Content).memoryPill?.noteCount)
    }

    @Test
    fun should_applySignalWrite_when_itLandsWhileASlowShelfLoads() = runTest {
        // Cold start with a slow network shelf: the local tier and the signals
        // are up at once while the shelf is still out. A note written then
        // ticks onto the feed up — it doesn't wait for the shelf — and the
        // shelf splices in later without taking the write back.
        val profile = "subsonic-write-during-load"
        val stamp = MutableStateFlow(5L)
        val repository = memorySignalRepository(profile = profile, stamp = stamp)
        var notes = 1
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns emptyList()
        coEvery { repository.countNotes() } answers { notes }
        coEvery { repository.getRecentlyPlayedAlbums(any()) } coAnswers {
            delay(5_000)
            listOf(recentAlbum("late-played"))
        }

        val viewModel = homeViewModel(repository, profile)
        advanceTimeBy(500)
        assertEquals(1, (viewModel.uiState.value as HomeUiState.Content).memoryPill?.noteCount)

        notes = 2
        stamp.value = 6L
        advanceTimeBy(2_000)
        val beforeShelf = viewModel.uiState.value as HomeUiState.Content
        assertEquals(2, beforeShelf.memoryPill?.noteCount)
        assertTrue(beforeShelf.recentlyPlayed.isEmpty())

        advanceUntilIdle()
        val content = viewModel.uiState.value as HomeUiState.Content
        assertEquals(2, content.memoryPill?.noteCount)
        assertEquals(listOf("late-played"), content.recentlyPlayed.map { it.id.rawId })
    }

    @Test
    fun should_buildSignalsOnce_when_aWriteTicksWhileTheLoadBuildsThem() = runTest {
        // The local tier is up and the load's candidate build still out when a
        // note is written. Its tick leaves the build to the load, which
        // publishes signals read on the older stamp and then replays the tick:
        // two builds, not a third racing the load's.
        val profile = "subsonic-tick-during-signals"
        val stamp = MutableStateFlow(5L)
        val repository = memorySignalRepository(profile = profile, stamp = stamp)
        var notes = 1
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } coAnswers {
            delay(3_000)
            emptyList()
        }
        coEvery { repository.countNotes() } answers { notes }

        val viewModel = homeViewModel(repository, profile)
        runCurrent()
        assertNull((viewModel.uiState.value as HomeUiState.Content).memoryPill)

        advanceTimeBy(500)
        notes = 2
        stamp.value = 6L
        advanceUntilIdle()

        assertEquals(2, (viewModel.uiState.value as HomeUiState.Content).memoryPill?.noteCount)
        coVerify(exactly = 2) { repository.getAlbumMemoryCandidates(48, true) }
    }

    @Test
    fun should_keepSignalWrite_when_reloadPublishesOlderSignalsOverIt() = runTest {
        // A same-scope reload keeps the feed up. A note written after the
        // reload read its stamp is spliced in on its tick, then the reload
        // publishes its older signals over the splice — and replays the tick.
        val profile = "subsonic-write-during-reload"
        val stamp = MutableStateFlow(5L)
        val repository = memorySignalRepository(profile = profile, stamp = stamp)
        var notes = 1
        var slowShelf = false
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns emptyList()
        coEvery { repository.countNotes() } answers { notes }
        coEvery { repository.getRecentlyPlayedAlbums(any()) } coAnswers {
            if (slowShelf) delay(5_000)
            emptyList()
        }
        val viewModel = homeViewModel(repository, profile)
        advanceUntilIdle()
        assertEquals(1, (viewModel.uiState.value as HomeUiState.Content).memoryPill?.noteCount)

        slowShelf = true
        viewModel.refresh()
        advanceTimeBy(500)
        notes = 2
        stamp.value = 6L
        advanceTimeBy(2_000)
        // The tick spliced the new note count into the feed still up.
        assertEquals(2, (viewModel.uiState.value as HomeUiState.Content).memoryPill?.noteCount)

        advanceUntilIdle()
        assertEquals(2, (viewModel.uiState.value as HomeUiState.Content).memoryPill?.noteCount)
    }

    @Test
    fun should_publishLocalTierFirst_when_networkShelvesAreSlow() = runTest {
        // A slow server: the activity log and the persisted pools go up at
        // once; each network shelf then splices into that same feed as it
        // lands, the blocks already up staying as they are.
        val profile = "subsonic-tiers"
        val repository = memorySignalRepository(profile = profile)
        val visit = artistVisit("tier-artist")
        val pooled = album("pooled-album", "Pooled")
        val pools = YoinRepository.HomeGridPoolSnapshot(
            albums = listOf(pooled),
            tracks = emptyList(),
            playlists = emptyList(),
            cachedAt = 1L
        )
        val starred = CompletableDeferred<Starred>()
        val played = CompletableDeferred<List<Album>>()
        val playlists = CompletableDeferred<List<Playlist>>()
        every { repository.getRecentActivities(limit = any()) } returns flowOf(listOf(visit))
        coEvery { repository.getCachedHomeGridPools(any()) } returns pools
        coEvery { repository.getCachedHomeGridPools(isNull()) } returns pools
        coEvery { repository.getStarred() } coAnswers { starred.await() }
        coEvery { repository.getRecentlyPlayedAlbums(any()) } coAnswers { played.await() }
        coEvery { repository.getPlaylists() } coAnswers { playlists.await() }
        val viewModel = homeViewModel(repository, profile)

        viewModel.uiState.test {
            assertEquals(HomeUiState.Loading, awaitItem())
            val local = awaitItem() as HomeUiState.Content
            assertEquals(listOf(visit), local.activities)
            assertTrue(local.widgetGrid.any { it.target == HomeWidgetTarget.AlbumDetail(pooled.id.toString()) })
            assertTrue(local.recentlyAddedAlbums.isEmpty())
            assertTrue(local.recentlyPlayed.isEmpty())
            assertTrue(local.playlists.isEmpty())

            played.complete(listOf(recentAlbum("played-1")))
            val withPlayed = awaitItem() as HomeUiState.Content
            assertEquals(listOf("played-1"), withPlayed.recentlyPlayed.map { it.id.rawId })
            assertEquals(local.activities, withPlayed.activities)
            assertEquals(local.widgetGrid, withPlayed.widgetGrid)

            starred.complete(Starred(albums = listOf(recentAlbum("added-1"))))
            assertEquals(
                listOf("added-1"),
                (awaitItem() as HomeUiState.Content).recentlyAddedAlbums.map { it.id.rawId }
            )

            playlists.complete(listOf(playlist("pl-1")))
            val full = awaitItem() as HomeUiState.Content
            assertEquals(listOf("pl-1"), full.playlists.map { it.id.rawId })
            assertEquals(listOf("played-1"), full.recentlyPlayed.map { it.id.rawId })
            assertEquals(listOf("added-1"), full.recentlyAddedAlbums.map { it.id.rawId })
        }
    }

    @Test
    fun should_keepShelves_when_reloadReadsFail() = runTest {
        // A same-scope reload (Retry, a credentials edit) whose network reads
        // fail keeps the shelves up instead of clearing them.
        val profile = "subsonic-keep-on-failure"
        val repository = memorySignalRepository(profile = profile)
        var offline = false
        coEvery { repository.getRecentlyPlayedAlbums(any()) } answers {
            if (offline) throw IOException("offline") else listOf(recentAlbum("played-1"))
        }
        coEvery { repository.getPlaylists() } answers {
            if (offline) throw IOException("offline") else listOf(playlist("pl-1"))
        }
        coEvery { repository.getStarred() } answers {
            if (offline) throw IOException("offline") else Starred(albums = listOf(recentAlbum("added-1")))
        }
        val viewModel = homeViewModel(repository, profile)
        advanceUntilIdle()

        offline = true
        viewModel.refresh()
        advanceUntilIdle()

        val content = viewModel.uiState.value as HomeUiState.Content
        assertEquals(listOf("played-1"), content.recentlyPlayed.map { it.id.rawId })
        assertEquals(listOf("pl-1"), content.playlists.map { it.id.rawId })
        assertEquals(listOf("added-1"), content.recentlyAddedAlbums.map { it.id.rawId })
        coVerify(exactly = 2) { repository.getRecentlyPlayedAlbums(any()) }
    }

    @Test
    fun should_emptyShelves_when_reloadReadsComeBackEmpty() = runTest {
        // An empty answer is the library's truth, not a failure: it clears.
        val profile = "subsonic-empty-on-reload"
        val repository = memorySignalRepository(profile = profile)
        var emptied = false
        coEvery { repository.getRecentlyPlayedAlbums(any()) } answers {
            if (emptied) emptyList() else listOf(recentAlbum("played-1"))
        }
        coEvery { repository.getPlaylists() } answers { if (emptied) emptyList() else listOf(playlist("pl-1")) }
        coEvery { repository.getStarred() } answers {
            if (emptied) Starred() else Starred(albums = listOf(recentAlbum("added-1")))
        }
        val viewModel = homeViewModel(repository, profile)
        advanceUntilIdle()

        emptied = true
        viewModel.refresh()
        advanceUntilIdle()

        val content = viewModel.uiState.value as HomeUiState.Content
        assertTrue(content.recentlyPlayed.isEmpty())
        assertTrue(content.playlists.isEmpty())
        assertTrue(content.recentlyAddedAlbums.isEmpty())
    }

    @Test
    fun should_spliceHeroFootnote_when_itResolvesAfterLocalTier() = runTest {
        // The hero's footnote reads the album through the detail cache — on a
        // cold one, the network. The local tier doesn't wait for it.
        val profile = "subsonic-hero-footnote"
        val repository = memorySignalRepository(profile = profile)
        val hero = ActivityEvent(
            entityType = "ALBUM",
            actionType = "PLAYED",
            entityId = "hero-album",
            profileId = profile,
            title = "Hero",
            subtitle = "Artist"
        )
        val heroAlbum = CompletableDeferred<Album?>()
        every { repository.getRecentActivities(limit = any()) } returns flowOf(listOf(hero))
        coEvery { repository.getAlbum(MediaId.subsonic("hero-album")) } coAnswers { heroAlbum.await() }
        val viewModel = homeViewModel(repository, profile)

        viewModel.uiState.test {
            assertEquals(HomeUiState.Loading, awaitItem())
            val local = awaitItem() as HomeUiState.Content
            assertEquals(listOf(hero), local.activities)
            assertNull(local.activityHeroYear)

            heroAlbum.complete(album("hero-album", "Hero").copy(id = MediaId.subsonic("hero-album"), year = 2019))
            val withFootnote = awaitItem() as HomeUiState.Content
            assertEquals(2019, withFootnote.activityHeroYear)
            assertEquals(10, withFootnote.activityHeroSongCount)
        }
    }

    @Test
    fun should_waitForEndpoint_when_spotifyLocalTierIsEmpty() = runTest {
        // Spotify's Activities are its recently-played endpoint's. With no
        // activity log and no pools on this device there is nothing local to
        // put up, and an empty feed would be wrong: Loading holds until the
        // endpoint answers.
        val profile = "spotify-empty-local"
        val repository = memorySignalRepository(profile = profile)
        every { repository.currentProviderId() } returns MediaId.PROVIDER_SPOTIFY
        every { repository.activeProviderId } returns flowOf(MediaId.PROVIDER_SPOTIFY)
        val endpoint = CompletableDeferred<List<ActivityEvent>>()
        val remote = listOf(artistVisit("remote-artist").copy(provider = MediaId.PROVIDER_SPOTIFY))
        coEvery { repository.getSpotifyRecentActivities(any()) } coAnswers { endpoint.await() }
        val viewModel = homeViewModel(repository, profile)

        viewModel.uiState.test {
            assertEquals(HomeUiState.Loading, awaitItem())
            advanceTimeBy(1_000)
            expectNoEvents()

            endpoint.complete(remote)
            val content = awaitItem() as HomeUiState.Content
            assertEquals(remote, content.activities)
            assertTrue(content.activitiesFromRemote)
        }
    }

    @Test
    fun should_keepEndpointFeed_when_reloadEndpointFails() = runTest {
        // The endpoint's feed is up; a reload whose endpoint read fails keeps
        // it rather than falling back to the local log.
        val profile = "spotify-endpoint-fails"
        val repository = memorySignalRepository(profile = profile)
        every { repository.currentProviderId() } returns MediaId.PROVIDER_SPOTIFY
        every { repository.activeProviderId } returns flowOf(MediaId.PROVIDER_SPOTIFY)
        val remote = listOf(artistVisit("remote-artist").copy(provider = MediaId.PROVIDER_SPOTIFY))
        every { repository.getRecentActivities(limit = any()) } returns flowOf(listOf(artistVisit("local-artist")))
        var offline = false
        coEvery { repository.getSpotifyRecentActivities(any()) } answers {
            if (offline) throw IOException("offline") else remote
        }
        val viewModel = homeViewModel(repository, profile)
        advanceUntilIdle()
        assertEquals(remote, (viewModel.uiState.value as HomeUiState.Content).activities)

        offline = true
        viewModel.refresh()
        advanceUntilIdle()

        val content = viewModel.uiState.value as HomeUiState.Content
        assertEquals(remote, content.activities)
        assertTrue(content.activitiesFromRemote)
    }

    @Test
    fun should_showLoading_when_accountSwitchStarts_until_newAccountsFeed() = runTest {
        // Q14b: from the tap to the new account's first feed Home shows
        // Loading — through the ping and warm-up, and past the commit until
        // that account's local tier is up.
        val outgoing = "subsonic-switch-hold-a"
        val incoming = "applemusic-switch-hold-b"
        val provider = MutableStateFlow<String?>(MediaId.PROVIDER_SUBSONIC)
        val profileId = MutableStateFlow<String?>(outgoing)
        val switching = MutableStateFlow<ProfileManager.SwitchState>(ProfileManager.SwitchState.Idle)
        val repository = scopedRepository(outgoing, provider)
        every { repository.getRecentActivities(limit = any()) } returns profileId.flatMapLatest { id ->
            flow {
                if (id == incoming) delay(1_000)
                emit(listOf(artistVisit("artist-of-$id")))
            }
        }
        val viewModel = switchingViewModel(repository, profileId, switching)

        viewModel.uiState.test {
            assertEquals(HomeUiState.Loading, awaitItem())
            assertEquals(
                listOf("artist-of-$outgoing"),
                (awaitItem() as HomeUiState.Content).activities.map { it.entityId }
            )

            switching.value = ProfileManager.SwitchState.Switching(incoming, ProfileManager.SwitchState.Stage.Preparing)
            assertEquals(HomeUiState.Loading, awaitItem())
            switching.value = ProfileManager.SwitchState.Switching(incoming, ProfileManager.SwitchState.Stage.Priming)
            advanceTimeBy(2_000)
            expectNoEvents()

            // ProfileManager.switchTo commits: the source, setActive, then Idle.
            provider.value = MediaId.PROVIDER_APPLE_MUSIC
            profileId.value = incoming
            switching.value = ProfileManager.SwitchState.Idle
            advanceTimeBy(500)
            expectNoEvents()

            assertEquals(
                listOf("artist-of-$incoming"),
                (awaitItem() as HomeUiState.Content).activities.map { it.entityId }
            )
        }
    }

    @Test
    fun should_restoreFeed_when_accountSwitchFails() = runTest {
        // A failed switch leaves the outgoing account active, its source
        // untouched: the feed it showed comes back as it was, not reloaded.
        val outgoing = "subsonic-switch-fails-a"
        val provider = MutableStateFlow<String?>(MediaId.PROVIDER_SUBSONIC)
        val profileId = MutableStateFlow<String?>(outgoing)
        val switching = MutableStateFlow<ProfileManager.SwitchState>(ProfileManager.SwitchState.Idle)
        val repository = scopedRepository(outgoing, provider)
        coEvery { repository.getRecentlyPlayedAlbums(any()) } returns listOf(recentAlbum("played-a"))
        val viewModel = switchingViewModel(repository, profileId, switching)

        viewModel.uiState.test {
            assertEquals(HomeUiState.Loading, awaitItem())
            val before = awaitItem() as HomeUiState.Content

            switching.value = ProfileManager.SwitchState.Switching(
                "spotify-switch-fails-b",
                ProfileManager.SwitchState.Stage.Connecting
            )
            assertEquals(HomeUiState.Loading, awaitItem())

            switching.value = ProfileManager.SwitchState.Error("spotify-switch-fails-b", "Server did not respond")
            assertEquals(before, awaitItem())
            switching.value = ProfileManager.SwitchState.Idle
            advanceUntilIdle()
            expectNoEvents()
        }
        coVerify(exactly = 1) { repository.getRecentlyPlayedAlbums(any()) }
    }

    /**
     * [memorySignalRepository] whose active provider follows [provider], as the
     * active source's id does. The source's identity follows it too (a rebuild
     * for the same provider is a test's own stub); [settled] is
     * ProfileManager's "no build in flight".
     */
    private fun scopedRepository(
        profile: String,
        provider: MutableStateFlow<String?>,
        settled: Flow<Boolean> = MutableStateFlow(false)
    ): YoinRepository = memorySignalRepository(profile = profile).also { repository ->
        every { repository.currentProviderId() } answers { provider.value }
        every { repository.activeProviderId } returns provider
        every { repository.activeSourceSettled } returns settled
        every { repository.activeSourceIdentity() } answers { provider.value }
    }

    private fun scopedViewModel(
        repository: YoinRepository,
        profileId: MutableStateFlow<String?>,
        revision: MutableStateFlow<Long>
    ): HomeViewModel = HomeViewModel(
        repository = repository,
        activeProfileId = profileId,
        homeLayoutStore = mockk<HomeLayoutStore>(relaxed = true).also { store ->
            every { store.layoutFlow(any()) } returns flowOf(null)
        },
        configurationRevision = revision
    )

    private fun switchingViewModel(
        repository: YoinRepository,
        profileId: MutableStateFlow<String?>,
        switching: MutableStateFlow<ProfileManager.SwitchState>
    ): HomeViewModel = HomeViewModel(
        repository = repository,
        activeProfileId = profileId,
        homeLayoutStore = mockk<HomeLayoutStore>(relaxed = true).also { store ->
            every { store.layoutFlow(any()) } returns flowOf(null)
        },
        switchingState = switching
    )

    private fun playlist(rawId: String): Playlist = Playlist(
        id = MediaId.subsonic(rawId),
        name = "Playlist $rawId",
        owner = "owner",
        coverArt = null,
        songCount = 5,
        durationSec = 600
    )

    /** A library album saved an hour ago: inside Recently Added's window. */
    private fun recentAlbum(rawId: String): Album =
        album(rawId, "Album $rawId").copy(addedAt = Instant.now().minus(1, ChronoUnit.HOURS).toString())

    private fun memorySignalRepository(
        profile: String,
        stamp: Flow<Long> = flowOf(),
        plays: Flow<PlayHistory?> = flowOf(),
    ): YoinRepository {
        val repository = mockk<YoinRepository>(relaxed = true)
        every { repository.currentProviderId() } returns MediaId.PROVIDER_SUBSONIC
        every { repository.activeProviderId } returns flowOf(MediaId.PROVIDER_SUBSONIC)
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.getRecentActivities(limit = any()) } returns flowOf(emptyList<ActivityEvent>())
        every { repository.observeMemorySignalStamp() } returns stamp
        every { repository.observeMostRecentPlay() } returns plays
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
        coEvery {
            repository.replaceHomeGridPools(albums = any(), tracks = any(), playlists = any())
        } just runs
        return repository
    }

    private fun homeViewModel(
        repository: YoinRepository,
        profile: String,
        homeLayoutStore: HomeLayoutStore = mockk<HomeLayoutStore>(relaxed = true).also { store ->
            every { store.layoutFlow(any()) } returns flowOf(null)
        },
        homeEditHintStore: HomeEditHintStore = HomeEditHintStore.InMemory(),
        nowMillis: () -> Long = System::currentTimeMillis,
        rediscoverSongs: RediscoverSongSource = RediscoverSongSource.None,
    ): HomeViewModel = HomeViewModel(
        repository = repository,
        activeProfileId = MutableStateFlow(profile),
        homeLayoutStore = homeLayoutStore,
        homeEditHintStore = homeEditHintStore,
        nowMillis = nowMillis,
        rediscoverSongs = rediscoverSongs,
    )

    /** A rated or noted song as the song source returns it: last played 150 days before [NOW], 7 plays. */
    private fun rediscoverSong(
        songId: String,
        rating: Float?,
        note: String? = null,
        albumId: String = "album-of-$songId",
        coverArtId: String? = null,
    ): SongMemoryAggregate = SongMemoryAggregate(
        songId = songId,
        provider = MediaId.PROVIDER_SUBSONIC,
        title = "Song $songId",
        artist = "Artist",
        album = "Album $albumId",
        albumId = albumId,
        coverArtId = coverArtId,
        durationMs = 200_000L,
        playCount = 7,
        firstPlayedAt = NOW - 400 * DAY,
        lastPlayedAt = NOW - 150 * DAY,
        rating = rating,
        noteCount = if (note != null) 1 else 0,
        latestNote = note,
    )

    private fun rediscoverIds(viewModel: HomeViewModel): List<String> =
        (viewModel.uiState.value as HomeUiState.Content).rediscover.map { it.albumId.rawId }

    /**
     * A candidate as the ineligible-inclusive build returns it: by default rated
     * 9, last played 200 days before [NOW], first played 600 days before, 23
     * plays — and not a Memory.
     */
    private fun rediscoverCandidate(
        albumId: String,
        profile: String,
        albumRating: Float? = 9f,
        eligible: Boolean = false,
        review: Boolean = false,
        lastWrittenAt: Long? = null,
        lastPlayedFromHistoryAt: Long? = NOW - 200 * DAY,
        cover: String? = null,
    ): AlbumMemoryCandidate = memoryCandidate(
        albumId = albumId,
        profile = profile,
        review = review,
        albumRating = albumRating,
        lastWrittenAt = lastWrittenAt,
    ).copy(
        isMemoryEligible = eligible,
        coverArtUrl = cover,
        firstPlayedFromHistoryAt = lastPlayedFromHistoryAt?.let { NOW - 600 * DAY },
        lastPlayedFromHistoryAt = lastPlayedFromHistoryAt,
        playCountFromHistory = if (lastPlayedFromHistoryAt != null) 23 else 0,
    )

    private fun play(id: Long, albumId: String, profile: String, playedAt: Long): PlayHistory = PlayHistory(
        id = id,
        songId = "song-$id",
        profileId = profile,
        provider = MediaId.PROVIDER_SUBSONIC,
        title = "Song $id",
        artist = "Artist",
        album = "Album $albumId",
        albumId = albumId,
        coverArtId = null,
        playedAt = playedAt,
        durationMs = 200_000L,
        completedPercent = 0f,
    )

    /** A layout customized before Rediscover shipped: reconcile appends it hidden and new. */
    private fun legacyPrefs(): List<HomeSectionPref> = listOf(
        HomeSectionPref(id = "activities", enabled = true),
        HomeSectionPref(id = "jump_back_in", enabled = true),
        HomeSectionPref(id = "recently_added", enabled = true),
    )

    private fun artistVisit(rawId: String): ActivityEvent = ActivityEvent(
        entityType = "ARTIST",
        actionType = "VISITED",
        entityId = rawId,
        profileId = "subsonic-thaw",
        title = "Artist $rawId",
        subtitle = "Artist",
    )

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

    private companion object {
        // Rediscover's fixed clock.
        const val NOW = 1_800_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000
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
