package com.gpo.yoin.ui.memories

import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoriesDeckCoordinatorTest {

    private val repository = mockk<YoinRepository>()
    private val sessionStore = ExperienceSessionStore()

    @Test
    fun should_load_candidates_only_once_per_session() = runTest {
        val candidates = buildAlbumCandidates(count = 8)
        val coordinator = buildCoordinator(candidates)

        val firstDeck = coordinator.ensureDeck()
        val secondDeck = coordinator.ensureDeck()

        assertEquals(
            firstDeck.map(MemoryEntry::sourceActivityId),
            secondDeck.map(MemoryEntry::sourceActivityId),
        )
        coVerify(exactly = 1) { repository.getAlbumMemoryCandidates(limit = 48) }
    }

    @Test
    fun should_replace_current_deck_when_advancing() = runTest {
        val candidates = buildAlbumCandidates(count = 8)
        val coordinator = buildCoordinator(candidates)

        val initialDeck = coordinator.ensureDeck()
        val nextDeck = coordinator.advanceDeck(MemoryDeckDirection.Forward)

        assertNotEquals(
            initialDeck.map(MemoryEntry::sourceActivityId),
            nextDeck.map(MemoryEntry::sourceActivityId),
        )
        assertEquals(
            nextDeck.map(MemoryEntry::sourceActivityId),
            sessionStore.state.value.memories.currentDeckActivityIds,
        )
        assertEquals(0, sessionStore.state.value.memories.currentPage)
    }

    @Test
    fun should_land_on_last_page_when_advancing_backward() = runTest {
        val candidates = buildAlbumCandidates(count = 8)
        val coordinator = buildCoordinator(candidates)

        coordinator.ensureDeck()
        val nextDeck = coordinator.advanceDeck(MemoryDeckDirection.Backward)

        assertEquals(nextDeck.lastIndex, sessionStore.state.value.memories.currentPage)
    }

    @Test
    fun should_prefer_album_rating_over_average_track_rating() = runTest {
        val candidate = buildAlbumCandidates(count = 1).single().copy(
            averageSongRating = 6.5f,
            albumRating = 9f,
            hasAlbumReview = true,
            noteCount = 2,
            askAiCount = 1,
        )
        val coordinator = buildCoordinator(listOf(candidate))

        val memory = coordinator.ensureDeck().single()

        assertEquals("9.0", memory.scoreText)
        assertEquals(MemoryScoreKind.ALBUM_RATING, memory.scoreKind)
        assertEquals("Album rating", memory.scoreSupportingText)
        assertTrue(memory.reasonChips.contains("Album review"))
        assertTrue(memory.reasonChips.contains("7/10 songs rated"))
        assertTrue(memory.reasonChips.contains("2 notes"))
        assertTrue(memory.reasonChips.contains("Ask AI references"))
        assertTrue(memory.reasonChips.contains("Recently revisited"))
        assertTrue(memory.reasonChips.contains("NeoDB ready"))
        assertNotNull(memory.narrativeCopy)
    }

    @Test
    fun should_use_average_track_rating_when_album_rating_is_missing() = runTest {
        val candidate = buildAlbumCandidates(count = 1).single().copy(
            averageSongRating = 7.25f,
            albumRating = null,
            hasAlbumReview = false,
        )
        val coordinator = buildCoordinator(listOf(candidate))

        val memory = coordinator.ensureDeck().single()

        assertEquals("7.3", memory.scoreText)
        assertEquals(MemoryScoreKind.AVERAGE_TRACK_RATING, memory.scoreKind)
        assertEquals("Track average", memory.scoreSupportingText)
        assertTrue(memory.narrativeCopy?.isNotBlank() == true)
    }

    @Test
    fun should_notCacheEmptyPool_when_firstBuildIsEmpty() = runTest {
        // Cold start: the first build races the active source and comes back empty.
        val coordinator = buildCoordinator(emptyList())
        assertTrue(coordinator.ensureDeck().isEmpty())

        coEvery { repository.getAlbumMemoryCandidates(limit = 48) } returns buildAlbumCandidates(count = 4)
        val deck = coordinator.ensureDeck()
        coordinator.ensureDeck()

        assertEquals(4, deck.size)
        // Empty build not cached → rebuilt once; the non-empty pool IS cached.
        coVerify(exactly = 2) { repository.getAlbumMemoryCandidates(limit = 48) }
    }

    @Test
    fun should_rebuildPool_when_focusRequestedAfterEmptyPool() = runTest {
        val coordinator = buildCoordinator(emptyList())
        assertTrue(coordinator.ensureDeck().isEmpty())

        val candidates = buildAlbumCandidates(count = 8)
        coEvery { repository.getAlbumMemoryCandidates(limit = 48) } returns candidates
        val focusSessionId = candidates[5].sessionId
        val deck = coordinator.ensureDeckFocused(focusSessionId)

        assertEquals(focusSessionId, deck.first().sourceActivityId)
        val session = sessionStore.state.value.memories
        assertEquals(focusSessionId, session.currentDeckActivityIds[session.currentPage])
        coVerify(exactly = 2) { repository.getAlbumMemoryCandidates(limit = 48) }
    }

    @Test
    fun should_resolveFocusedCardFresh_when_ratingChangedSinceLastOpen() = runTest {
        val candidates = buildAlbumCandidates(count = 3).map { candidate ->
            candidate.copy(albumRating = 7f)
        }
        val coordinator = buildCoordinator(candidates)
        val focus = candidates[1]
        val staleCard = coordinator.ensureDeck().single { memory ->
            memory.sourceActivityId == focus.sessionId
        }
        assertEquals("7.0", staleCard.scoreText)

        // The user re-rates the album after the deck was opened; Home's pill now
        // shows 9.0 and the tap must land on a card that agrees.
        coEvery { repository.getAlbumMemoryCandidates(limit = 48) } returns candidates.map { candidate ->
            if (candidate.sessionId == focus.sessionId) candidate.copy(albumRating = 9f) else candidate
        }
        val deck = coordinator.ensureDeckFocused(focus.sessionId)

        val focusedCard = deck[sessionStore.state.value.memories.currentPage]
        assertEquals(focus.sessionId, focusedCard.sourceActivityId)
        assertEquals("9.0", focusedCard.scoreText)
        assertEquals(MemoryScoreKind.ALBUM_RATING, focusedCard.scoreKind)
        coVerify(exactly = 2) { repository.getAlbumMemoryCandidates(limit = 48) }
    }

    @Test
    fun should_keep_stale_entry_when_candidate_leaves_pool_after_write() = runTest {
        val candidates = buildAlbumCandidates(count = 8).map { candidate ->
            candidate.copy(albumRating = 7f)
        }
        val coordinator = buildCoordinator(candidates)
        val deck = coordinator.ensureDeck()
        sessionStore.setMemoriesCurrentPage(3)
        val sessionBefore = sessionStore.state.value.memories
        val rerated = deck[0]
        val leaving = deck[1]

        // One write re-rates the first card; another drops the second card's
        // album out of the eligible pool.
        coEvery { repository.getAlbumMemoryCandidates(limit = 48) } returns candidates
            .filterNot { candidate -> candidate.sessionId == leaving.sourceActivityId }
            .map { candidate ->
                if (candidate.sessionId == rerated.sourceActivityId) candidate.copy(albumRating = 9f) else candidate
            }
        val refreshed = coordinator.refreshDeck(deck)

        // Same cards, same order: the departed album keeps its old card so no
        // later page shifts under the user.
        assertEquals(
            deck.map(MemoryEntry::sourceActivityId),
            refreshed.map(MemoryEntry::sourceActivityId),
        )
        assertSame(leaving, refreshed[1])
        assertEquals("9.0", refreshed[0].scoreText)
        assertEquals("7.0", refreshed[2].scoreText)
        // In place: the session's deck identity and page are untouched.
        assertEquals(sessionBefore, sessionStore.state.value.memories)
        coVerify(exactly = 2) { repository.getAlbumMemoryCandidates(limit = 48) }
    }

    @Test
    fun should_return_previous_deck_when_refresh_rebuilds_empty_pool() = runTest {
        val coordinator = buildCoordinator(buildAlbumCandidates(count = 8))
        val deck = coordinator.ensureDeck()

        // The source went away mid-session: nothing to re-resolve against.
        coEvery { repository.getAlbumMemoryCandidates(limit = 48) } returns emptyList()
        val refreshed = coordinator.refreshDeck(deck)

        assertEquals(deck, refreshed)
        assertEquals(
            deck.map(MemoryEntry::sourceActivityId),
            sessionStore.state.value.memories.currentDeckActivityIds,
        )
    }

    private fun buildCoordinator(candidates: List<AlbumMemoryCandidate>): MemoriesDeckCoordinator {
        coEvery { repository.getAlbumMemoryCandidates(limit = 48) } returns candidates
        coEvery { repository.getAlbum(any()) } returns null
        coEvery { repository.getRatings(any()) } returns emptyMap()

        return MemoriesDeckCoordinator(
            repository = repository,
            sessionStore = sessionStore,
            randomSeed = 42L,
        )
    }

    private fun buildAlbumCandidates(count: Int): List<AlbumMemoryCandidate> =
        (1..count).map { index ->
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
}
