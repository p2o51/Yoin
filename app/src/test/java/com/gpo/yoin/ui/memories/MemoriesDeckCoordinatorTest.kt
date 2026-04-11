package com.gpo.yoin.ui.memories

import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MemoriesDeckCoordinatorTest {

    private val repository = mockk<YoinRepository>()
    private val sessionStore = ExperienceSessionStore()

    @Test
    fun should_load_candidates_only_once_per_session() = runTest {
        val activities = buildSongActivities(count = 8)
        val coordinator = buildCoordinator(activities)

        val firstDeck = coordinator.ensureDeck()
        val secondDeck = coordinator.ensureDeck()

        assertEquals(
            firstDeck.map(MemoryEntry::sourceActivityId),
            secondDeck.map(MemoryEntry::sourceActivityId),
        )
        coVerify(exactly = 1) { repository.getRecentMemoryActivities(limit = 48) }
    }

    @Test
    fun should_replace_current_deck_when_advancing() = runTest {
        val activities = buildSongActivities(count = 8)
        val coordinator = buildCoordinator(activities)

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
        val activities = buildSongActivities(count = 8)
        val coordinator = buildCoordinator(activities)

        coordinator.ensureDeck()
        val nextDeck = coordinator.advanceDeck(MemoryDeckDirection.Backward)

        assertEquals(nextDeck.lastIndex, sessionStore.state.value.memories.currentPage)
    }

    private fun buildCoordinator(activities: List<ActivityEvent>): MemoriesDeckCoordinator {
        coEvery { repository.getRecentMemoryActivities(limit = 48) } returns activities
        every { repository.getRating(any()) } returns flowOf(null)
        coEvery { repository.getMostRecentPlay(any()) } returns null

        return MemoriesDeckCoordinator(
            repository = repository,
            sessionStore = sessionStore,
            randomSeed = 42L,
        )
    }

    private fun buildSongActivities(count: Int): List<ActivityEvent> =
        (1..count).map { index ->
            ActivityEvent(
                id = index.toLong(),
                entityType = ActivityEntityType.SONG.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "song-$index",
                songId = "song-$index",
                title = "Song $index",
                subtitle = "Artist $index",
                timestamp = 1000L + index,
            )
        }
}
