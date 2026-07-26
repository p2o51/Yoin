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
