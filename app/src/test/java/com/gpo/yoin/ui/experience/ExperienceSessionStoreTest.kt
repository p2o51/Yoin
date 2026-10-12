package com.gpo.yoin.ui.experience

import com.gpo.yoin.ui.navigation.YoinSection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceSessionStoreTest {

    private val store = ExperienceSessionStore()

    @Test
    fun should_preserve_shell_and_memories_state_within_session() {
        store.setSelectedSection(YoinSection.LIBRARY)
        store.setHomeSurface(HomeSurface.Memories)
        store.setNowPlayingExpanded(true)
        store.replaceMemoriesDeck(
            activityIds = listOf(11L, 12L, 13L),
            currentPage = 1,
        )
        store.setMemoryScrollPosition(
            activityId = 12L,
            position = MemoryScrollPosition(
                firstVisibleItemIndex = 4,
                firstVisibleItemScrollOffset = 28,
            ),
        )

        val state = store.state.value
        assertEquals(YoinSection.LIBRARY, state.selectedSection)
        assertEquals(HomeSurface.Memories, state.homeSurface)
        assertTrue(state.nowPlayingExpanded)
        assertEquals(listOf(11L, 12L, 13L), state.memories.currentDeckActivityIds)
        assertEquals(1, state.memories.currentPage)
        assertEquals(
            MemoryScrollPosition(firstVisibleItemIndex = 4, firstVisibleItemScrollOffset = 28),
            state.memories.perMemoryScrollOffsets[12L],
        )
    }

    @Test
    fun should_filter_stale_scroll_offsets_when_replacing_deck() {
        store.replaceMemoriesDeck(
            activityIds = listOf(21L, 22L, 23L),
            currentPage = 2,
        )
        store.setMemoryScrollPosition(21L, MemoryScrollPosition(firstVisibleItemIndex = 6))
        store.setMemoryScrollPosition(22L, MemoryScrollPosition(firstVisibleItemIndex = 2))

        store.replaceMemoriesDeck(
            activityIds = listOf(22L, 24L),
            currentPage = 5,
        )

        val memories = store.state.value.memories
        assertEquals(listOf(22L, 24L), memories.currentDeckActivityIds)
        assertEquals(1, memories.currentPage)
        assertFalse(memories.perMemoryScrollOffsets.containsKey(21L))
        assertTrue(memories.perMemoryScrollOffsets.containsKey(22L))
    }

    @Test
    fun should_clear_memories_session_without_touching_shell_state() {
        store.setSelectedSection(YoinSection.LIBRARY)
        store.setNowPlayingExpanded(true)
        store.replaceMemoriesDeck(
            activityIds = listOf(31L, 32L),
            currentPage = 1,
        )

        store.clearMemories()

        val state = store.state.value
        assertEquals(YoinSection.LIBRARY, state.selectedSection)
        assertTrue(state.nowPlayingExpanded)
        assertEquals(MemoriesSessionState(), state.memories)
    }

    @Test
    fun should_capture_detailEnterBaseline_before_committedFrameTick() {
        store.noteDetailEnterSlideStarted()
        store.noteDetailEnterSlideStarted()

        store.prepareDetailEnterSlide()
        store.noteDetailEnterSlideStarted()

        assertEquals(2L, store.detailEnterSlideBaseline)
        assertEquals(3L, store.detailEnterSlideTick.value)
    }

    @Test
    fun should_armChromeAndCaptureEnterBaseline_when_detailHandoffBegins() {
        store.noteDetailEnterSlideStarted()
        store.noteDetailEnterSlideStarted()

        val generation = store.beginDetailHandoff()

        assertTrue(store.state.value.detailChromeActive)
        assertEquals(2L, store.detailEnterSlideBaseline)
        assertEquals(generation, store.detailHandoffGeneration)
    }

    @Test
    fun should_restoreNavChrome_when_itsDetailWindowEnds() {
        val generation = store.beginDetailHandoff()

        store.endDetailHandoff(generation)

        assertFalse(store.state.value.detailChromeActive)
    }

    @Test
    fun should_keepNewerHandoff_when_staleDetailWindowEndsLate() {
        val stale = store.beginDetailHandoff()
        val current = store.beginDetailHandoff()

        store.endDetailHandoff(stale)

        assertNotEquals(stale, current)
        assertTrue(store.state.value.detailChromeActive)
    }

    @Test
    fun should_ignoreGenerationFromEarlierProcess_when_restoredDetailEnds() {
        // A detail restored after process death carries a generation armed by the
        // previous process's store, which started counting from an earlier seed.
        val earlierProcess = ExperienceSessionStore()
        val restoredGeneration = earlierProcess.beginDetailHandoff()
        val current = ExperienceSessionStore()
        current.beginDetailHandoff()

        current.endDetailHandoff(restoredGeneration)

        assertTrue(current.state.value.detailChromeActive)
    }

    @Test
    fun should_startFromNavPose_when_newShellResetsStaleHandoff() = runBlocking {
        store.beginDetailHandoff()
        store.noteDetailEnterSlideStarted()
        store.shellBarChromeMorph.snapTo(1f)
        store.detailBackPhase.value = DetailBackPhase.Committed
        store.detailBackProgress.floatValue = 0.4f
        store.detailBackTouchYDelta.floatValue = 12f
        store.windowBeneathRevealed.value = CoveredWindowAnimationGate.ShellWindowKey

        store.resetDetailHandoff()

        assertFalse(store.state.value.detailChromeActive)
        assertEquals(0f, store.shellBarChromeMorph.value)
        assertEquals(DetailBackPhase.Idle, store.detailBackPhase.value)
        assertEquals(0f, store.detailBackProgress.floatValue)
        assertEquals(0f, store.detailBackTouchYDelta.floatValue)
        assertNull(store.windowBeneathRevealed.value)
    }
}
