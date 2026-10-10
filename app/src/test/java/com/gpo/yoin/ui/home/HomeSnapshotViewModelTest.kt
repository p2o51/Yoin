package com.gpo.yoin.ui.home

import com.gpo.yoin.data.home.HomeLayoutStore
import com.gpo.yoin.data.home.HomeSectionPref
import com.gpo.yoin.data.home.HomeSnapshotStore
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.testutil.MainDispatcherRule
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryScoreKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Home's snapshot (P2 PR3, owner Q14a): a cold start paints the account's
 * last feed at once, the fresh load replaces it block by block, and only a
 * fresh feed is written back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeSnapshotViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @get:Rule
    val temp = TemporaryFolder()

    private val directory: File by lazy { File(temp.root, HomeSnapshotStore.DIRECTORY_NAME) }

    @Test
    fun should_paintTheSnapshotThenReplaceItBlockByBlock_when_aColdStartHasOne() = runTest {
        val profile = "snapshot-cold-start"
        val provider = MutableStateFlow<String?>(null)
        seed(profile, MediaId.PROVIDER_SUBSONIC, snapshotFeed())
        val repository = repository(provider)
        val localTier = CompletableDeferred<List<ActivityEvent>>()
        val played = CompletableDeferred<List<Album>>()
        val starred = CompletableDeferred<Starred>()
        val signals = CompletableDeferred<List<AlbumMemoryCandidate>>()
        every { repository.getRecentActivities(limit = any()) } returns flow { emit(localTier.await()) }
        coEvery { repository.getRecentlyPlayedAlbums(any()) } coAnswers { played.await() }
        coEvery { repository.getStarred() } coAnswers { starred.await() }
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } coAnswers { signals.await() }
        // Offline for this one: the snapshot's shelf stays.
        coEvery { repository.getPlaylists() } throws IOException("offline")
        val store = store()
        val viewModel = viewModel(repository, MutableStateFlow(profile), store)

        // The profile is known, its source isn't built yet: the snapshot paints.
        runCurrent()
        val painted = viewModel.uiState.value as HomeUiState.Content
        assertEquals(listOf("snap-artist"), painted.activities.map { it.entityId })
        assertEquals(listOf("snap-played"), painted.recentlyPlayed.map { it.id.rawId })
        assertEquals(listOf("Snap playlist"), painted.playlists.map { it.name })
        assertEquals(profile, painted.ownerProfileId)
        // A URL cover shows now; a Subsonic id waits for the source.
        assertEquals(listOf(null, "https://img.example/pl.jpg"), painted.widgetGrid.map { it.coverArtUrl })

        provider.value = MediaId.PROVIDER_SUBSONIC
        runCurrent()
        val withCovers = viewModel.uiState.value as HomeUiState.Content
        assertEquals(
            listOf(subsonicCoverUrl("al-snap"), "https://img.example/pl.jpg"),
            withCovers.widgetGrid.map { it.coverArtUrl }
        )
        assertEquals(painted.recentlyPlayed, withCovers.recentlyPlayed)
        assertEquals(painted.playlists, withCovers.playlists)
        // Not fresh: nothing was written back.
        advanceTimeBy(HomeSnapshotStore.SETTLE_MS + 10)
        runCurrent()
        assertEquals(
            listOf(MediaId.subsonic("snap-played").toString()),
            store().read(profile)?.feed?.recentlyPlayed?.map { it.id }
        )

        played.complete(listOf(album("fresh-played")))
        runCurrent()
        val withPlayed = viewModel.uiState.value as HomeUiState.Content
        assertEquals(listOf("fresh-played"), withPlayed.recentlyPlayed.map { it.id.rawId })
        assertEquals(painted.activities, withPlayed.activities)
        assertEquals(painted.recentlyAddedAlbums, withPlayed.recentlyAddedAlbums)

        localTier.complete(listOf(artistVisit("fresh-artist")))
        starred.complete(Starred(albums = listOf(album("fresh-added", addedAt = recently()))))
        signals.complete(emptyList())
        advanceTimeBy(5_000)
        runCurrent()
        val fresh = viewModel.uiState.value as HomeUiState.Content
        assertEquals(listOf("fresh-artist"), fresh.activities.map { it.entityId })
        assertEquals(listOf("fresh-added"), fresh.recentlyAddedAlbums.map { it.id.rawId })
        assertEquals(listOf("fresh-played"), fresh.recentlyPlayed.map { it.id.rawId })
        // The block whose read failed keeps what the snapshot showed.
        assertEquals(listOf("Snap playlist"), fresh.playlists.map { it.name })

        // The fresh feed is written in the background, without credentials.
        advanceTimeBy(HomeSnapshotStore.SETTLE_MS + HomeSnapshotStore.MIN_WRITE_INTERVAL_MS + 10)
        runCurrent()
        val written = store().read(profile)
        assertEquals(MediaId.PROVIDER_SUBSONIC, written?.provider)
        assertEquals(listOf("fresh-artist"), written?.feed?.activities?.map { it.entityId })
        assertEquals(listOf(MediaId.subsonic("fresh-played").toString()), written?.feed?.recentlyPlayed?.map { it.id })
        val file = directory.listFiles().orEmpty().single().readText()
        assertFalse(file, file.contains("t=tok"))
    }

    @Test
    fun should_dropTheSnapshot_when_theSourceThatArrivesIsAnotherProvider() = runTest {
        val profile = "snapshot-provider-mismatch-late"
        val provider = MutableStateFlow<String?>(null)
        seed(profile, MediaId.PROVIDER_SPOTIFY, snapshotFeed())
        val repository = repository(provider)
        coEvery { repository.getPlaylists() } returns listOf(playlist("fresh-pl", "Fresh playlist"))
        val seen = record(viewModel(repository, MutableStateFlow(profile), store()))

        runCurrent()
        // Painted on its own word before the source...
        assertEquals(listOf("Snap playlist"), (seen.last() as HomeUiState.Content).playlists.map { it.name })

        provider.value = MediaId.PROVIDER_SUBSONIC
        advanceUntilIdle()
        // ...which disagrees: dropped to Loading, then the fresh feed.
        val afterSource = seen.dropWhile { state -> state !is HomeUiState.Content }.drop(1)
        assertEquals(HomeUiState.Loading, afterSource.first())
        assertEquals(listOf("Fresh playlist"), (seen.last() as HomeUiState.Content).playlists.map { it.name })
    }

    @Test
    fun should_keepTheSnapshotUntilTheSourceArrives_when_theSourceOutlastsTheHold() = runTest {
        // A cold start whose source takes longer than Home's hold for it: the
        // sourceless load past the hold would put a next-to-empty feed over
        // the snapshot. The snapshot stays; the source's load replaces it.
        val profile = "snapshot-slow-source"
        val provider = MutableStateFlow<String?>(null)
        seed(profile, MediaId.PROVIDER_SUBSONIC, snapshotFeed())
        val repository = repository(provider)
        coEvery { repository.getPlaylists() } answers {
            if (provider.value == null) throw IOException("No profile configured")
            listOf(playlist("fresh-pl", "Fresh playlist"))
        }
        val seen = record(viewModel(repository, MutableStateFlow(profile), store()))

        runCurrent()
        assertEquals(listOf("Snap playlist"), (seen.last() as HomeUiState.Content).playlists.map { it.name })

        advanceTimeBy(30_000)
        runCurrent()
        // Past the hold: still the snapshot, and nothing was read from no source.
        assertEquals(listOf("snap-artist"), (seen.last() as HomeUiState.Content).activities.map { it.entityId })
        assertEquals(listOf("Snap playlist"), (seen.last() as HomeUiState.Content).playlists.map { it.name })
        coVerify(exactly = 0) { repository.getPlaylists() }

        provider.value = MediaId.PROVIDER_SUBSONIC
        advanceUntilIdle()
        assertEquals(listOf("Fresh playlist"), (seen.last() as HomeUiState.Content).playlists.map { it.name })
        // Snapshot to fresh feed, block by block: never Loading, never an empty shelf.
        val painted = seen.dropWhile { state -> state !is HomeUiState.Content }
        assertTrue(painted.none { state -> state is HomeUiState.Loading })
        assertTrue(painted.filterIsInstance<HomeUiState.Content>().all { content -> content.playlists.isNotEmpty() })
    }

    @Test
    fun should_keepTheSnapshot_when_profileManagerSettlesWithoutASource() = runTest {
        // Unreadable credentials: no source is coming until the account is
        // fixed. The snapshot stays rather than give way to a feed of nothing.
        val profile = "snapshot-no-source"
        val provider = MutableStateFlow<String?>(null)
        val settled = MutableStateFlow(false)
        seed(profile, MediaId.PROVIDER_SUBSONIC, snapshotFeed())
        val repository = repository(provider)
        every { repository.activeSourceSettled } returns settled
        val seen = record(viewModel(repository, MutableStateFlow(profile), store()))
        runCurrent()

        settled.value = true
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(listOf("Snap playlist"), (seen.last() as HomeUiState.Content).playlists.map { it.name })
        assertEquals(1, seen.count { state -> state is HomeUiState.Content })
        coVerify(exactly = 0) { repository.getRecentlyPlayedAlbums(any()) }
    }

    @Test
    fun should_neverPaintTheSnapshot_when_theSourceIsAnotherProviderFromTheStart() = runTest {
        val profile = "snapshot-provider-mismatch"
        val provider = MutableStateFlow<String?>(MediaId.PROVIDER_SUBSONIC)
        seed(profile, MediaId.PROVIDER_SPOTIFY, snapshotFeed())
        val repository = repository(provider)
        coEvery { repository.getPlaylists() } returns listOf(playlist("fresh-pl", "Fresh playlist"))
        val seen = record(viewModel(repository, MutableStateFlow(profile), store()))

        advanceUntilIdle()

        val playlists = seen.filterIsInstance<HomeUiState.Content>().flatMap { content -> content.playlists }
        assertTrue(playlists.isNotEmpty())
        assertTrue(playlists.none { it.name == "Snap playlist" })
    }

    @Test
    fun should_loadAsBeforeAndDeleteTheFile_when_theSnapshotIsCorrupt() = runTest {
        val profile = "snapshot-corrupt"
        // A whole feed on disk, unreadable by one field: nothing of it may paint.
        seed(profile, MediaId.PROVIDER_SUBSONIC, snapshotFeed())
        val file = directory.listFiles().orEmpty().single()
        val corrupt = file.readText().replace(Regex("\"savedAt\":\\d+"), "\"savedAt\":\"yesterday\"")
        assertTrue(corrupt.contains("Snap playlist") && corrupt.contains("yesterday"))
        file.writeText(corrupt)
        val repository = repository(MutableStateFlow(MediaId.PROVIDER_SUBSONIC))
        coEvery { repository.getPlaylists() } returns listOf(playlist("fresh-pl", "Fresh playlist"))
        every { repository.getRecentActivities(limit = any()) } returns flowOf(listOf(artistVisit("fresh-artist")))
        val seen = record(viewModel(repository, MutableStateFlow(profile), store()))

        advanceUntilIdle()

        val painted = seen.filterIsInstance<HomeUiState.Content>()
        assertTrue(painted.isNotEmpty())
        assertTrue(painted.none { content -> content.playlists.any { it.name == "Snap playlist" } })
        assertTrue(painted.none { content -> content.activities.any { it.entityId == "snap-artist" } })
        assertEquals(listOf("fresh-artist"), painted.first().activities.map { it.entityId })
        assertEquals(listOf("Fresh playlist"), painted.last().playlists.map { it.name })
        // The fresh feed replaced the unreadable file (the write runs in the background).
        advanceTimeBy(HomeSnapshotStore.SETTLE_MS + 10)
        runCurrent()
        assertEquals(listOf("Fresh playlist"), store().read(profile)?.feed?.playlists?.map { it.name })
    }

    @Test
    fun should_notWriteTheSnapshotBack_when_onlyTheActivityLogHasArrived() = runTest {
        // Spotify's snapshot holds an endpoint feed; the activity log emits at
        // once, every other block (the endpoint too) is still out. Nothing
        // read so far changes what is on disk, so nothing is rewritten — no
        // new savedAt, and the 30 s write window stays free for the fresh feed.
        val profile = "snapshot-spotify-no-rewrite"
        seed(profile, MediaId.PROVIDER_SPOTIFY, spotifySnapshotFeed())
        val onDisk = directory.listFiles().orEmpty().single().readText()
        val repository = repository(MutableStateFlow(MediaId.PROVIDER_SPOTIFY))
        val never = CompletableDeferred<Nothing>()
        // Live like Room's: its first emission comes through the debounce, onto the snapshot.
        val log = MutableStateFlow(listOf(artistVisit("local-artist")))
        every { repository.getRecentActivities(limit = any()) } returns log
        coEvery { repository.getSpotifyRecentActivities(any()) } coAnswers { never.await() }
        coEvery { repository.getCachedHomeGridPools(any()) } coAnswers { never.await() }
        coEvery { repository.getCachedHomeGridPools(isNull()) } coAnswers { never.await() }
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } coAnswers { never.await() }
        coEvery { repository.getStarred() } coAnswers { never.await() }
        coEvery { repository.getPlaylists() } coAnswers { never.await() }
        coEvery { repository.getRecentlyPlayedAlbums(any()) } coAnswers { never.await() }
        val viewModel = viewModel(repository, MutableStateFlow(profile), store())

        runCurrent()
        assertEquals(listOf("snap-remote"), activityIds(viewModel))
        // The activity log's debounced emission, then a whole settle + write window.
        advanceTimeBy(1_000 + HomeSnapshotStore.SETTLE_MS + HomeSnapshotStore.MIN_WRITE_INTERVAL_MS + 10)
        runCurrent()

        // The endpoint feed holds while its read is out; the file is as it was.
        assertEquals(listOf("snap-remote"), activityIds(viewModel))
        assertEquals(onDisk, directory.listFiles().orEmpty().single().readText())
    }

    @Test
    fun should_writeARediscoverRemovalOnlyWithAFreshBlock_when_aPlayLandsOnTheSnapshotAlone() = runTest {
        // A play takes its card off the snapshot's shelf at once; written back
        // only once a block read now is in the feed, the removal riding along.
        val profile = "snapshot-rediscover-removal"
        seed(profile, MediaId.PROVIDER_SUBSONIC, snapshotFeed().copy(rediscover = listOf(rediscoverAlbum("al-redis"))))
        val onDisk = directory.listFiles().orEmpty().single().readText()
        val repository = repository(MutableStateFlow(MediaId.PROVIDER_SUBSONIC))
        val never = CompletableDeferred<Nothing>()
        val playlists = CompletableDeferred<List<Playlist>>()
        val plays = MutableStateFlow<PlayHistory?>(null)
        every { repository.getRecentActivities(limit = any()) } returns flow { awaitCancellation() }
        every { repository.observeMostRecentPlay() } returns plays
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } coAnswers { never.await() }
        coEvery { repository.getStarred() } coAnswers { never.await() }
        coEvery { repository.getRecentlyPlayedAlbums(any()) } coAnswers { never.await() }
        coEvery { repository.getPlaylists() } coAnswers { playlists.await() }
        val viewModel = viewModel(repository, MutableStateFlow(profile), store())
        runCurrent()
        assertEquals(listOf("al-redis"), rediscoverIds(viewModel))

        plays.value = albumPlay("al-redis", profile)
        runCurrent()
        assertTrue(rediscoverIds(viewModel).isEmpty())
        advanceTimeBy(HomeSnapshotStore.SETTLE_MS + HomeSnapshotStore.MIN_WRITE_INTERVAL_MS + 10)
        runCurrent()
        assertEquals(onDisk, directory.listFiles().orEmpty().single().readText())

        playlists.complete(listOf(playlist("fresh-pl", "Fresh playlist")))
        runCurrent()
        advanceTimeBy(HomeSnapshotStore.SETTLE_MS + 10)
        runCurrent()
        val written = store().read(profile)?.feed
        assertEquals(listOf("Fresh playlist"), written?.playlists?.map { it.name })
        assertEquals(emptyList<Any>(), written?.rediscover.orEmpty())
    }

    @Test
    fun should_handTheSnapshotsEndpointFeedToTheActivityLog_when_theEndpointReadFails() = runTest {
        // An earlier process's recently-played (maybe days old) only holds
        // while this process's read is out: rate-limited or offline, the
        // activity log takes over and stays live, as without a snapshot.
        val profile = "snapshot-spotify-endpoint-fails"
        seed(profile, MediaId.PROVIDER_SPOTIFY, spotifySnapshotFeed())
        val repository = repository(MutableStateFlow(MediaId.PROVIDER_SPOTIFY))
        val log = MutableStateFlow(listOf(artistVisit("local-artist")))
        every { repository.getRecentActivities(limit = any()) } returns log
        coEvery { repository.getSpotifyRecentActivities(any()) } throws IOException("429")
        val viewModel = viewModel(repository, MutableStateFlow(profile), store())

        // The load's own answer, ahead of the activity log's debounced emission.
        runCurrent()
        val fallback = viewModel.uiState.value as HomeUiState.Content
        assertEquals(listOf("local-artist"), fallback.activities.map { it.entityId })
        assertFalse(fallback.activitiesFromRemote)

        // A play in Yoin goes into Activities, live.
        log.value = listOf(artistVisit("played-now"), artistVisit("local-artist"))
        advanceTimeBy(1_000 + 10)
        runCurrent()
        assertEquals(listOf("played-now", "local-artist"), activityIds(viewModel))

        // And the next cold start's snapshot holds the activity log, not the old endpoint feed.
        advanceTimeBy(HomeSnapshotStore.SETTLE_MS + HomeSnapshotStore.MIN_WRITE_INTERVAL_MS + 10)
        runCurrent()
        val written = store().read(profile)?.feed
        assertEquals(listOf("played-now", "local-artist"), written?.activities?.map { it.entityId })
        assertEquals(false, written?.activitiesFromRemote)
    }

    @Test
    fun should_holdTheSnapshotsEndpointFeedUntilTheEndpointAnswers_when_theActivityLogLandsFirst() = runTest {
        val profile = "snapshot-spotify-endpoint-answers"
        seed(profile, MediaId.PROVIDER_SPOTIFY, spotifySnapshotFeed())
        val repository = repository(MutableStateFlow(MediaId.PROVIDER_SPOTIFY))
        val endpoint = CompletableDeferred<List<ActivityEvent>>()
        var offline = false
        every { repository.getRecentActivities(limit = any()) } returns flowOf(listOf(artistVisit("local-artist")))
        coEvery { repository.getSpotifyRecentActivities(any()) } coAnswers {
            if (offline) throw IOException("offline") else endpoint.await()
        }
        val viewModel = viewModel(repository, MutableStateFlow(profile), store())
        val seen = record(viewModel)

        advanceTimeBy(5_000)
        runCurrent()
        // The local tier is in; the snapshot's endpoint feed doesn't flash to it.
        val beforeAnswer = seen.filterIsInstance<HomeUiState.Content>()
        assertTrue(beforeAnswer.isNotEmpty())
        assertTrue(beforeAnswer.all { content -> content.activities.map { it.entityId } == listOf("snap-remote") })

        endpoint.complete(listOf(spotifyArtistVisit("fresh-remote")))
        runCurrent()
        val answered = seen.last() as HomeUiState.Content
        assertEquals(listOf("fresh-remote"), answered.activities.map { it.entityId })
        assertTrue(answered.activitiesFromRemote)

        // This process's own endpoint feed: kept through a failed reload, as always.
        offline = true
        viewModel.refresh()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf("fresh-remote"), (seen.last() as HomeUiState.Content).activities.map { it.entityId })
    }

    @Test
    fun should_paintTheSnapshotInItsAccountsLayout_when_theLayoutReadsAfterTheSnapshot() = runTest {
        val profile = "snapshot-layout"
        seed(profile, MediaId.PROVIDER_SUBSONIC, snapshotFeed())
        val custom = HomeLayout.Default.toPrefs().reversed()
        val repository = repository(MutableStateFlow(MediaId.PROVIDER_SUBSONIC))
        every { repository.getRecentActivities(limit = any()) } returns flow { awaitCancellation() }
        val viewModel = viewModel(
            repository,
            MutableStateFlow(profile),
            store(),
            layouts = flow {
                delay(SNAPSHOT_LAYOUT_DELAY_MS)
                emit(custom)
            }
        )
        // Each Content as it went up, with the layout Home drew it in.
        val painted = mutableListOf<Pair<HomeUiState.Content, HomeLayout>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { state ->
                if (state is HomeUiState.Content) painted += state to viewModel.homeLayout.value
            }
        }

        runCurrent()
        // The snapshot is read; the layout isn't yet: nothing painted in the default one.
        assertEquals(HomeUiState.Loading, viewModel.uiState.value)
        assertTrue(painted.isEmpty())

        advanceTimeBy(SNAPSHOT_LAYOUT_DELAY_MS + 1)
        runCurrent()
        assertEquals(listOf("Snap playlist"), painted.first().first.playlists.map { it.name })
        assertTrue(painted.all { (_, layout) -> layout.sameSectionsAs(HomeLayout.reconcile(custom)) })
    }

    @Test
    fun should_loadWithoutTheSnapshot_when_theLayoutReadOutlastsTheWait() = runTest {
        val profile = "snapshot-layout-slow"
        seed(profile, MediaId.PROVIDER_SUBSONIC, snapshotFeed())
        val repository = repository(MutableStateFlow(MediaId.PROVIDER_SUBSONIC))
        coEvery { repository.getPlaylists() } returns listOf(playlist("fresh-pl", "Fresh playlist"))
        val seen = record(
            viewModel(
                repository,
                MutableStateFlow(profile),
                store(),
                layouts = flow {
                    delay(5_000)
                    emit(null)
                }
            )
        )

        advanceUntilIdle()

        val painted = seen.filterIsInstance<HomeUiState.Content>()
        assertTrue(painted.isNotEmpty())
        assertTrue(painted.none { content -> content.playlists.any { it.name == "Snap playlist" } })
        assertEquals(listOf("Fresh playlist"), painted.last().playlists.map { it.name })
    }

    @Test
    fun should_paintTheIncomingAccountsSnapshot_when_aSwitchCommits() = runTest {
        val outgoing = "snapshot-switch-a"
        val incoming = "snapshot-switch-b"
        seed(incoming, MediaId.PROVIDER_APPLE_MUSIC, snapshotFeed())
        val provider = MutableStateFlow<String?>(MediaId.PROVIDER_SUBSONIC)
        val profileId = MutableStateFlow<String?>(outgoing)
        val switching = MutableStateFlow<ProfileManager.SwitchState>(ProfileManager.SwitchState.Idle)
        val incomingLocalTier = CompletableDeferred<List<ActivityEvent>>()
        val repository = repository(provider)
        every { repository.getRecentActivities(limit = any()) } returns profileId.flatMapLatest { id ->
            if (id == incoming) flow { emit(incomingLocalTier.await()) } else flowOf(listOf(artistVisit("a-artist")))
        }
        val viewModel = viewModel(repository, profileId, store(), switching)
        advanceUntilIdle()
        assertEquals(listOf("a-artist"), activityIds(viewModel))

        switching.value = ProfileManager.SwitchState.Switching(incoming, ProfileManager.SwitchState.Stage.Priming)
        runCurrent()
        assertEquals(HomeUiState.Loading, viewModel.uiState.value)

        // ProfileManager.switchTo commits: the source, setActive, then Idle.
        provider.value = MediaId.PROVIDER_APPLE_MUSIC
        profileId.value = incoming
        switching.value = ProfileManager.SwitchState.Idle
        runCurrent()
        // The new account's own last feed, ahead of its local tier.
        val painted = viewModel.uiState.value as HomeUiState.Content
        assertEquals(incoming, painted.ownerProfileId)
        assertEquals(listOf("snap-artist"), painted.activities.map { it.entityId })

        incomingLocalTier.complete(listOf(artistVisit("b-artist")))
        advanceUntilIdle()
        assertEquals(listOf("b-artist"), activityIds(viewModel))
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun activityIds(viewModel: HomeViewModel): List<String> =
        (viewModel.uiState.value as HomeUiState.Content).activities.map { it.entityId }

    private fun rediscoverIds(viewModel: HomeViewModel): List<String> =
        (viewModel.uiState.value as HomeUiState.Content).rediscover.map { it.albumId.rawId }

    /** A store on the test's clock: a rewrite shows as a new savedAt. */
    private fun TestScope.store(): HomeSnapshotStore = HomeSnapshotStore(
        directory = { directory },
        scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        clock = { testScheduler.currentTime }
    )

    /** [profileId]'s snapshot on disk, written by a store of its own (an earlier process). */
    private fun TestScope.seed(profileId: String, provider: String, feed: HomeUiState.Content) {
        store().save(profileId, provider) { feed.toSnapshotFeed() }
        advanceTimeBy(HomeSnapshotStore.SETTLE_MS + 1)
        runCurrent()
    }

    /** Every state [viewModel] publishes, in order. */
    private fun TestScope.record(viewModel: HomeViewModel): List<HomeUiState> {
        val seen = mutableListOf<HomeUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect { seen += it } }
        return seen
    }

    private fun viewModel(
        repository: YoinRepository,
        profileId: MutableStateFlow<String?>,
        store: HomeSnapshotStore,
        switching: MutableStateFlow<ProfileManager.SwitchState> = MutableStateFlow(ProfileManager.SwitchState.Idle),
        layouts: Flow<List<HomeSectionPref>?> = flowOf(null)
    ): HomeViewModel = HomeViewModel(
        repository = repository,
        activeProfileId = profileId,
        homeLayoutStore = mockk<HomeLayoutStore>(relaxed = true).also { store ->
            every { store.layoutFlow(any()) } returns layouts
        },
        switchingState = switching,
        snapshotStore = store
    )

    /** A feed of every block, its covers as a Subsonic id and a URL. */
    private fun snapshotFeed(): HomeUiState.Content = HomeUiState.Content(
        activities = listOf(artistVisit("snap-artist")),
        widgetGrid = listOf(
            HomeWidgetCard(
                stableId = "grid-album:subsonic:al-snap",
                entityType = MemoryEntityType.ALBUM,
                title = "Snap album",
                subtitle = "Artist",
                coverArtUrl = subsonicCoverUrl("al-snap"),
                target = HomeWidgetTarget.AlbumDetail("subsonic:al-snap"),
                coverKey = "al-snap"
            ),
            HomeWidgetCard(
                stableId = "grid-playlist:subsonic:pl-snap",
                entityType = MemoryEntityType.PLAYLIST,
                title = "Snap playlist",
                subtitle = "",
                coverArtUrl = "https://img.example/pl.jpg",
                target = HomeWidgetTarget.PlaylistDetail("subsonic:pl-snap"),
                coverKey = "https://img.example/pl.jpg"
            )
        ),
        recentlyAddedAlbums = listOf(album("snap-added", addedAt = recently())),
        recentlyPlayed = listOf(album("snap-played")),
        playlists = listOf(playlist("pl-snap", "Snap playlist"))
    )

    /** Spotify's feed as an earlier process left it: Activities from the recently-played endpoint. */
    private fun spotifySnapshotFeed(): HomeUiState.Content = HomeUiState.Content(
        activities = listOf(spotifyArtistVisit("snap-remote")),
        activitiesFromRemote = true,
        playlists = listOf(playlist("pl-snap", "Snap playlist"))
    )

    /** A repository on [provider]'s source (null: not built yet), every read empty. */
    private fun repository(provider: MutableStateFlow<String?>): YoinRepository {
        val repository = mockk<YoinRepository>(relaxed = true)
        every { repository.currentProviderId() } answers { provider.value }
        every { repository.activeProviderId } returns provider
        every { repository.activeSourceSettled } returns MutableStateFlow(false)
        every { repository.activeSourceIdentity() } answers { provider.value }
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.getRecentActivities(limit = any()) } returns flowOf(emptyList<ActivityEvent>())
        every { repository.observeMemorySignalStamp() } returns emptyFlow()
        every { repository.observeMostRecentPlay() } returns emptyFlow()
        every { repository.resolveCoverUrl(any(), any()) } answers {
            when (val ref = firstArg<CoverRef?>()) {
                null -> null
                is CoverRef.Url -> ref.url.takeIf { provider.value != null }
                is CoverRef.SourceRelative -> subsonicCoverUrl(ref.coverArtId).takeIf { provider.value != null }
            }
        }
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
        coEvery { repository.getAlbumMemoryCandidates(any(), any()) } returns emptyList()
        coEvery { repository.countNotes() } returns 0
        coEvery {
            repository.replaceHomeGridPools(albums = any(), tracks = any(), playlists = any())
        } just runs
        return repository
    }

    private fun <T> emptyFlow(): Flow<T> = flow { }

    /** What the Subsonic source resolves a cover id to: a URL with the account's credentials. */
    private fun subsonicCoverUrl(coverArtId: String): String =
        "https://music.example/rest/getCoverArt?id=$coverArtId&size=480&u=me&t=tok&s=salt"

    private fun recently(): String = Instant.now().minus(1, ChronoUnit.HOURS).toString()

    private fun album(rawId: String, addedAt: String? = null): Album = Album(
        id = MediaId.subsonic(rawId),
        name = "Album $rawId",
        artist = "Artist",
        artistId = null,
        coverArt = CoverRef.SourceRelative(rawId),
        songCount = 10,
        durationSec = null,
        year = 2024,
        genre = null,
        addedAt = addedAt
    )

    private fun playlist(rawId: String, name: String): Playlist = Playlist(
        id = MediaId.subsonic(rawId),
        name = name,
        owner = "me",
        coverArt = null,
        songCount = 5,
        durationSec = 600
    )

    private fun artistVisit(rawId: String): ActivityEvent = ActivityEvent(
        entityType = "ARTIST",
        actionType = "VISITED",
        entityId = rawId,
        title = "Artist $rawId",
        subtitle = "Artist"
    )

    private fun rediscoverAlbum(rawId: String): HomeRediscoverItem = HomeRediscoverItem(
        albumId = MediaId.subsonic(rawId),
        albumName = "Album $rawId",
        artistName = "Artist",
        coverArtUrl = subsonicCoverUrl(rawId),
        score = 8f,
        scoreText = "8.0",
        scoreKind = MemoryScoreKind.ALBUM_RATING,
        lastPlayedAt = 1_600_000_000_000L,
        firstPlayedAt = 1_500_000_000_000L,
        playCount = 7,
        coverKey = rawId
    )

    /** A play of [albumId] from now on (Rediscover only acts on plays since it started watching). */
    private fun albumPlay(albumId: String, profile: String): PlayHistory = PlayHistory(
        id = 1,
        songId = "song-of-$albumId",
        profileId = profile,
        provider = MediaId.PROVIDER_SUBSONIC,
        title = "Song",
        artist = "Artist",
        album = "Album $albumId",
        albumId = albumId,
        coverArtId = null,
        playedAt = System.currentTimeMillis() + 60_000L,
        durationMs = 200_000L,
        completedPercent = 0f
    )

    private fun spotifyArtistVisit(rawId: String): ActivityEvent =
        artistVisit(rawId).copy(provider = MediaId.PROVIDER_SPOTIFY)

    private companion object {
        // Longer than a snapshot read, shorter than the wait for one.
        const val SNAPSHOT_LAYOUT_DELAY_MS = 200L
    }
}
