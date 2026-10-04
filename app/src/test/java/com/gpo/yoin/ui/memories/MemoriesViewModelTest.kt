package com.gpo.yoin.ui.memories

import app.cash.turbine.test
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.testutil.MainDispatcherRule
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val activeSourceId = MutableStateFlow<String?>("subsonic")
    private val memorySignal = MutableStateFlow(0L)

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
    fun should_reload_when_active_source_becomes_ready_after_empty() = runTest {
        // Cold start: the profile id is restored but its source isn't built yet,
        // so the first build sees no candidates.
        activeSourceId.value = null
        stubCandidates(emptyList())
        val viewModel = buildViewModel()
        advanceUntilIdle()
        assertEquals(MemoriesUiState.Empty, viewModel.uiState.value)

        stubCandidates(buildAlbumCandidates(count = 4))
        activeSourceId.value = "subsonic"
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
    }

    private fun buildViewModel(): MemoriesViewModel {
        every { repository.observeMemorySignalStamp() } returns memorySignal
        return MemoriesViewModel(
            deckCoordinator = MemoriesDeckCoordinator(
                repository = repository,
                sessionStore = sessionStore,
                randomSeed = 42L,
            ),
            sessionStore = sessionStore,
            repository = repository,
            activeProfileId = activeProfileId,
            activeSourceId = activeSourceId,
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
