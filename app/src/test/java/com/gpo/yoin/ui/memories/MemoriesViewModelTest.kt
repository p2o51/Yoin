package com.gpo.yoin.ui.memories

import app.cash.turbine.test
import com.gpo.yoin.data.local.AlbumMemoryTitle
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.memory.AlbumMemoryTitleStore
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.testutil.MainDispatcherRule
import com.gpo.yoin.ui.detail.AlbumNeoDbSync
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import com.gpo.yoin.ui.memories.copy.MemoryTitleKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoriesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repository = mockk<YoinRepository>()
    private val sessionStore = ExperienceSessionStore()
    private val activeProfileId = MutableStateFlow<String?>("profile-a")

    // What YoinRepository.awaitActiveSource waits on: null until the cold-start source is built.
    private val activeSource = MutableStateFlow<MusicSource?>(mockk<MusicSource>())
    private val memorySignal = MutableStateFlow(0L)
    private val titleDao = FakeAlbumMemoryTitleDao()
    private val titleStore = AlbumMemoryTitleStore(titleDao, activeProfileId, clock = { 7_000L })

    @Test
    fun should_retitle_card_at_once_and_persist_when_title_saved() = runTest {
        stubCandidates(buildAlbumCandidates(count = 4))
        val viewModel = buildViewModel()
        advanceUntilIdle()
        val memory = (viewModel.uiState.value as MemoriesUiState.Content).memories.first()
        val yoinTitle = memory.memoryTitle

        viewModel.saveMemoryTitle(memory, "  Night bus ")
        // optimistic: the card is renamed before the write lands
        val optimistic = (viewModel.uiState.value as MemoriesUiState.Content).memories.first()
        assertEquals("Night bus", optimistic.memoryTitle)
        assertEquals(MemoryTitleKind.USER, optimistic.memoryTitleKind)
        advanceUntilIdle()

        val row = titleDao.rows.value.single()
        assertEquals(AlbumMemoryTitle("profile-a", "subsonic", memory.entityId, "Night bus", 7_000L), row)
        assertEquals("Night bus", (viewModel.uiState.value as MemoriesUiState.Content).memories.first().memoryTitle)

        // an empty save is a restore: the row is deleted and Yoin's title is back
        viewModel.saveMemoryTitle(memory, "   ")
        advanceUntilIdle()
        assertTrue(titleDao.rows.value.isEmpty())
        val restored = (viewModel.uiState.value as MemoriesUiState.Content).memories.first()
        assertEquals(yoinTitle, restored.memoryTitle)
        assertEquals(memory.memoryTitleKind, restored.memoryTitleKind)
    }

    @Test
    fun should_roll_back_and_report_when_title_write_fails() = runTest {
        stubCandidates(buildAlbumCandidates(count = 4))
        val viewModel = buildViewModel()
        advanceUntilIdle()
        val memory = (viewModel.uiState.value as MemoriesUiState.Content).memories.first()
        titleDao.failWrites = true

        viewModel.events.test {
            viewModel.saveMemoryTitle(memory, "Night bus")
            advanceUntilIdle()

            val event = awaitItem() as MemoriesOneShotEvent.TitleSaveFailed
            assertEquals(memory.stableId, event.memoryStableId)
            expectNoEvents()
        }
        val after = (viewModel.uiState.value as MemoriesUiState.Content).memories.first()
        assertEquals(memory.memoryTitle, after.memoryTitle)
        assertEquals(memory.memoryTitleKind, after.memoryTitleKind)
    }

    @Test
    fun should_retitle_open_card_when_title_changes_elsewhere() = runTest {
        stubCandidates(buildAlbumCandidates(count = 4))
        val viewModel = buildViewModel()
        advanceUntilIdle()
        val memory = (viewModel.uiState.value as MemoriesUiState.Content).memories.first()

        // the album page (or a sync pull) names the album while the deck is open
        titleStore.setTitle(MediaId("subsonic", memory.entityId), "From the album page")
        advanceUntilIdle()

        val after = (viewModel.uiState.value as MemoriesUiState.Content).memories.first()
        assertEquals("From the album page", after.memoryTitle)
        assertEquals(MemoryTitleKind.USER, after.memoryTitleKind)
        // nothing else on the deck moved
        assertEquals(
            memory.copy(
                memoryTitle = after.memoryTitle,
                memoryTitleKind = after.memoryTitleKind,
                generatedMemoryTitle = after.generatedMemoryTitle,
                generatedMemoryTitleKind = after.generatedMemoryTitleKind,
            ),
            after,
        )
    }

    @Test
    fun should_reload_when_previous_result_was_empty() = runTest {
        stubCandidates(emptyList())
        val viewModel = buildViewModel()
        advanceUntilIdle()
        assertEquals(MemoriesUiState.Empty, viewModel.uiState.value)

        // Reopening Memories (the screen's LaunchedEffect) once the pool has
        // something must retry rather than treat Empty as final.
        stubCandidates(buildAlbumCandidates(count = 4))
        viewModel.ensureLoaded()
        advanceUntilIdle()

        val content = viewModel.uiState.value as MemoriesUiState.Content
        assertEquals(4, content.memories.size)
    }

    @Test
    fun should_buildDeck_when_activeSourceArrivesAfterInit() = runTest {
        // Cold start: the profile id is restored but its source isn't built yet.
        // The build waits for it instead of landing on Empty.
        activeSource.value = null
        stubCandidates(buildAlbumCandidates(count = 4))
        val viewModel = buildViewModel()
        advanceUntilIdle()
        assertEquals(MemoriesUiState.Loading, viewModel.uiState.value)
        coVerify(exactly = 0) { repository.getAlbumMemoryCandidates(limit = 48) }

        activeSource.value = mockk<MusicSource>()
        advanceUntilIdle()

        val content = viewModel.uiState.value as MemoriesUiState.Content
        assertEquals(4, content.memories.size)
    }

    @Test
    fun should_retryDeck_when_activeSourceArrivesAfterWaitRunsOut() = runTest {
        // The source takes longer than the bounded wait: the deck builds without
        // it and lands on Empty. Once the source does arrive, the deck rebuilds.
        activeSource.value = null
        stubCandidates(emptyList())
        val viewModel = buildViewModel(
            awaitSource = { timeoutMs -> withTimeoutOrNull(timeoutMs) { activeSource.filterNotNull().first() } }
        )
        advanceUntilIdle()
        assertEquals(MemoriesUiState.Empty, viewModel.uiState.value)

        stubCandidates(buildAlbumCandidates(count = 4))
        activeSource.value = mockk<MusicSource>()
        advanceUntilIdle()

        val content = viewModel.uiState.value as MemoriesUiState.Content
        assertEquals(4, content.memories.size)
    }

    @Test
    fun should_refresh_current_deck_in_place_when_memory_signal_changes() = runTest {
        val candidates = buildAlbumCandidates(count = 8).map { candidate ->
            candidate.copy(albumRating = 7f)
        }
        stubCandidates(candidates)
        val viewModel = buildViewModel()
        advanceUntilIdle()
        val before = viewModel.uiState.value as MemoriesUiState.Content
        viewModel.setCurrentPage(2)
        val sessionBefore = sessionStore.state.value.memories
        val rerated = before.memories[2].sourceActivityId

        // The user re-rates the album on the open page (e.g. via Go to album).
        stubCandidates(
            candidates.map { candidate ->
                if (candidate.sessionId == rerated) candidate.copy(albumRating = 9f) else candidate
            },
        )
        viewModel.uiState.test {
            assertEquals(before, awaitItem())

            memorySignal.value = 1L
            advanceUntilIdle()

            // Exactly one in-place update: no Loading flash, no re-deal.
            val after = awaitItem() as MemoriesUiState.Content
            assertEquals(before.deckRevision, after.deckRevision)
            assertEquals(
                before.memories.map(MemoryEntry::sourceActivityId),
                after.memories.map(MemoryEntry::sourceActivityId),
            )
            assertEquals("9.0", after.memories[2].scoreText)
            assertEquals("7.0", after.memories[0].scoreText)
            expectNoEvents()
        }
        assertEquals(sessionBefore, sessionStore.state.value.memories)
        assertEquals(2, sessionStore.state.value.memories.currentPage)
    }

    @Test
    fun should_emit_failure_result_when_album_lookup_throws_during_neodb_push() = runTest {
        stubCandidates(emptyList())
        coEvery { repository.isNeoDBConfigured() } returns true
        val viewModel = buildViewModel()
        advanceUntilIdle()
        // Offline with no cached detail: the detail cache rethrows to every waiter.
        coEvery { repository.getAlbum(any()) } throws IOException("offline")
        coEvery { repository.getAlbumRatingRow(any()) } returns dirtyRatingRow()
        val memory = buildMemoryEntry()

        viewModel.events.test {
            viewModel.pushToNeoDb(memory)
            advanceUntilIdle()

            val event = awaitItem() as MemoriesOneShotEvent.NeoDBSyncResult
            assertFalse(event.success)
            assertEquals(memory.stableId, event.memoryStableId)
            expectNoEvents()
        }
        assertTrue(viewModel.syncingEntityIds.value.isEmpty())
        // The diary's line stays on the retry until a push lands.
        coEvery { repository.observeAlbumRating(any()) } returns flowOf(dirtyRatingRow())
        assertEquals(AlbumNeoDbSync.Failed, viewModel.neoDbSync(memory).first())
        // Synced meanwhile from the album page: the row is clean, so the old failure no longer shows.
        coEvery { repository.observeAlbumRating(any()) } returns flowOf(
            dirtyRatingRow().copy(ratingNeedsSync = false),
        )
        assertEquals(AlbumNeoDbSync.Synced, viewModel.neoDbSync(memory).first())
    }

    @Test
    fun should_keep_draft_and_report_when_album_unavailable_on_save() = runTest {
        stubCandidates(buildAlbumCandidates(count = 4))
        val viewModel = buildViewModel()
        advanceUntilIdle()
        val memory = (viewModel.uiState.value as MemoriesUiState.Content).memories.first()
        assertNull(memory.review)
        // offline, the album not cached: getAlbum gives nothing to write the review against
        coEvery { repository.getAlbum(any()) } returns null

        viewModel.events.test {
            viewModel.saveReview(memory, "  好听。 ")
            // optimistic: the blank page is the review at once, and the draft is held
            val optimistic = (viewModel.uiState.value as MemoriesUiState.Content).memories.first()
            assertEquals("好听。", optimistic.review?.text)
            assertEquals("好听。", viewModel.reviewDrafts.value[memory.stableId])
            advanceUntilIdle()

            val event = awaitItem() as MemoriesOneShotEvent.ReviewSaveFailed
            assertEquals(memory.stableId, event.memoryStableId)
            expectNoEvents()
        }
        // rolled back, the draft kept for the blank page to reopen with
        val after = (viewModel.uiState.value as MemoriesUiState.Content).memories.first()
        assertNull(after.review)
        assertFalse(after.hasAlbumReview)
        assertEquals("好听。", viewModel.reviewDrafts.value[memory.stableId])
        coVerify(exactly = 0) { repository.setAlbumReview(any(), any()) }
    }

    @Test
    fun should_light_latest_anchored_note_at_or_before_playhead() {
        val notes = listOf(
            songNote("n-late", "t2", 102_000L),
            songNote("n-early", "t2", 8_000L),
            songNote("n-none", "t2", null),
            songNote("n-other", "t3", 1_000L),
        )
        assertNull(memoryLitNoteId(notes, "t2", 7_999L))
        assertEquals("n-early", memoryLitNoteId(notes, "t2", 8_000L))
        assertEquals("n-early", memoryLitNoteId(notes, "t2", 101_999L))
        assertEquals("n-late", memoryLitNoteId(notes, "t2", 102_000L))
        assertEquals("n-late", memoryLitNoteId(notes, "t2", 900_000L))
        // another track's notes, or nothing playing, light nothing
        assertEquals("n-other", memoryLitNoteId(notes, "t3", 2_000L))
        assertNull(memoryLitNoteId(notes, null, 2_000L))
        // a tie goes to the later one (NP's rule)
        val tie = listOf(songNote("a", "t2", 5_000L), songNote("b", "t2", 5_000L))
        assertEquals("b", memoryLitNoteId(tie, "t2", 6_000L))

        // over the deck: only the same provider's notes count
        val deck = listOf(noteDeckEntry())
        assertEquals(MemoriesPlayhead("t2", "n-early"), memoriesPlayhead(playing("t2", 9_000L), deck))
        assertEquals(MemoriesPlayhead("t2", null), memoriesPlayhead(playing("t2", 9_000L, provider = "spotify"), deck))
        assertEquals(MemoriesPlayhead(null, null), memoriesPlayhead(PlaybackState(), deck))
    }

    @Test
    fun should_seek_once_when_target_track_becomes_current() = runTest {
        stubCandidates(emptyList())
        val player = FakePlayback(playing("t1", 30_000L))
        val viewModel = buildViewModel(player)
        advanceUntilIdle()

        // the note's track is not playing yet: wait for it (runCurrent: virtual time must not reach the 4s cap)
        viewModel.requestSeek("t2", 125_000L)
        runCurrent()
        assertTrue(player.seeks.isEmpty())
        // current, but not prepared (duration 0): still waiting
        player.state.value = playing("t2", 0L, duration = 0L)
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(player.seeks.isEmpty())
        // prepared: one seek
        player.state.value = playing("t2", 0L)
        runCurrent()
        player.state.value = playing("t2", 250L)
        player.state.value = playing("t2", 500L)
        advanceUntilIdle()
        assertEquals(listOf(125_000L), player.seeks)

        // a track that never comes: given up after 4s
        viewModel.requestSeek("t9", 5_000L)
        advanceTimeBy(MemoriesViewModel.SEEK_TIMEOUT_MS + 1)
        player.state.value = playing("t9", 0L)
        advanceUntilIdle()
        assertEquals(listOf(125_000L), player.seeks)

        // already playing (paused) the note's track: seek now and resume, no restart needed
        player.state.value = playing("t2", 60_000L, isPlaying = false)
        viewModel.requestSeek("t2", 8_000L)
        assertEquals(listOf(125_000L, 8_000L), player.seeks)
        assertEquals(1, player.resumes)
    }

    @Test
    fun should_not_emit_when_position_ticks_within_same_note() = runTest {
        val state = MutableStateFlow(PlaybackState())
        val deck = MutableStateFlow(listOf(noteDeckEntry()))
        memoriesPlayheadFlow(state, deck).test {
            assertEquals(MemoriesPlayhead(null, null), awaitItem())
            state.value = playing("t2", 9_000L)
            assertEquals(MemoriesPlayhead("t2", "n-early"), awaitItem())
            // 250ms ticks inside n-early's stretch: nothing reaches the diary
            for (at in 9_250L..101_750L step 250L) state.value = playing("t2", at)
            expectNoEvents()
            state.value = playing("t2", 102_000L)
            assertEquals(MemoriesPlayhead("t2", "n-late"), awaitItem())
            state.value = playing("t2", 102_250L)
            expectNoEvents()
        }
    }

    private class FakePlayback(initial: PlaybackState) : MemoriesPlayback {
        override val state = MutableStateFlow(initial)
        val seeks = mutableListOf<Long>()
        var resumes = 0

        override fun seekTo(positionMs: Long) {
            seeks += positionMs
        }

        override fun resume() {
            resumes++
        }
    }

    private fun playing(
        track: String,
        at: Long,
        duration: Long = 212_000L,
        provider: String = "subsonic",
        isPlaying: Boolean = true,
    ) = PlaybackState(
        currentTrack = Track(
            id = MediaId(provider, track),
            title = track,
            artist = null,
            artistId = null,
            album = null,
            albumId = null,
            coverArt = null,
            durationSec = (duration / 1000).toInt(),
            trackNumber = null,
            year = null,
            genre = null,
            userRating = null,
        ),
        isPlaying = isPlaying,
        position = at,
        duration = duration,
    )

    private fun songNote(id: String, track: String, positionMs: Long?) = MemoryWriting(
        kind = MemoryWriting.Kind.SONG_NOTE,
        text = id,
        writtenAt = 0L,
        trackId = track,
        positionMs = positionMs,
        noteId = id,
    )

    private fun noteDeckEntry(): MemoryEntry {
        val track = MemoryTrack(
            stableId = "s2",
            title = "Thin Ice",
            artist = "",
            durationSeconds = 212,
            rating = 8.5f,
            number = 2,
            trackId = "t2",
            playbackIndex = 1,
        )
        return buildMemoryEntry().copy(
            diaryTracks = listOf(
                MemoryDiaryTrack(
                    track = track,
                    notes = listOf(songNote("n-early", "t2", 8_000L), songNote("n-late", "t2", 102_000L)),
                ),
            ),
        )
    }

    private fun buildViewModel(
        playback: MemoriesPlayback? = null,
        // YoinRepository.awaitActiveSource over [activeSource]; unbounded unless a test bounds it.
        awaitSource: suspend (timeoutMs: Long) -> MusicSource? = { activeSource.filterNotNull().first() }
    ): MemoriesViewModel {
        every { repository.observeMemorySignalStamp() } returns memorySignal
        coEvery { repository.awaitActiveSource(any()) } coAnswers { awaitSource(firstArg()) }
        every { repository.activeProviderId } returns activeSource.map { source ->
            source?.let { MediaId.PROVIDER_SUBSONIC }
        }
        return MemoriesViewModel(
            deckCoordinator = MemoriesDeckCoordinator(
                repository = repository,
                sessionStore = sessionStore,
                randomSeed = 42L,
            ),
            sessionStore = sessionStore,
            repository = repository,
            activeProfileId = activeProfileId,
            playback = playback,
            titleStore = titleStore,
        )
    }

    private fun stubCandidates(candidates: List<AlbumMemoryCandidate>) {
        coEvery { repository.getAlbumMemoryCandidates(limit = 48) } returns candidates
        coEvery { repository.getAlbum(any()) } returns null
        coEvery { repository.getRatings(any()) } returns emptyMap()
    }

    private fun buildAlbumCandidates(count: Int): List<AlbumMemoryCandidate> = (1..count).map { index ->
        AlbumMemoryCandidate(
            profileId = "profile-a",
            provider = "subsonic",
            albumId = "album-$index",
            albumName = "Album $index",
            artistName = "Artist $index",
            totalTracks = 10,
            ratedTrackCount = 7,
            ratingCoverage = 0.7f,
            averageSongRating = 7f,
            albumRating = null,
            hasAlbumReview = false,
            noteCount = 0,
            askAiCount = 0,
            firstPlayedAt = 1000L + index,
            lastPlayedAt = 2000L + index,
            playCount = 1,
            neoDbSynced = false,
            isMemoryEligible = true,
            year = null,
            durationSeconds = null,
            coverArtUrl = null,
        )
    }

    private fun dirtyRatingRow() = AlbumRating(
        profileId = "profile-a",
        albumId = "album-1",
        rating = 8f,
        review = null,
        neoDbReviewUuid = null,
        ratingNeedsSync = true,
    )

    private fun buildMemoryEntry(): MemoryEntry = MemoryEntry(
        stableId = "album:profile-a:subsonic:album-1",
        sourceActivityId = 1L,
        entityType = MemoryEntityType.ALBUM,
        entityId = "album-1",
        entityProvider = "subsonic",
        title = "Album 1",
        supportingText = "Artist 1",
        metaText = null,
        coverArtUrl = null,
        timestamp = 0L,
        scoreText = "9.0",
        scoreSupportingText = null,
        footerText = null,
        playbackSongs = emptyList(),
        tracks = emptyList(),
    )
}
