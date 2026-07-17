package com.gpo.yoin.ui.experience

import com.gpo.yoin.ui.navigation.YoinSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class HomeSurface {
    Feed,
    Memories,
}

data class MemoryScrollPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
)

data class MemoriesSessionState(
    val deckId: Long = 0L,
    val currentDeckActivityIds: List<Long> = emptyList(),
    val currentPage: Int = 0,
    val perMemoryScrollOffsets: Map<Long, MemoryScrollPosition> = emptyMap(),
    // A pending request (from the home teaser) to open the deck stopped on a
    // specific album. Holds the candidate's sessionId. Consumed and cleared by
    // MemoriesViewModel once the focused deck is built. Reset by clearMemories().
    val pendingFocusSessionId: Long? = null,
)

data class ExperienceSessionState(
    val selectedSection: YoinSection = YoinSection.HOME,
    val homeSurface: HomeSurface = HomeSurface.Feed,
    val nowPlayingExpanded: Boolean = false,
    /**
     * True from the moment a detail launch is tapped until the last detail
     * window has left the screen: the shell bar wears its DETAIL chrome
     * (Play split + short pill) so the cross-window hand-off — and the
     * predictive-back preview on return — reads as one persistent bar.
     */
    val detailChromeActive: Boolean = false,
    val memories: MemoriesSessionState = MemoriesSessionState(),
)

class ExperienceSessionStore {
    private val _state = MutableStateFlow(ExperienceSessionState())
    val state: StateFlow<ExperienceSessionState> = _state.asStateFlow()

    fun setSelectedSection(section: YoinSection) {
        _state.update { current -> current.copy(selectedSection = section) }
    }

    fun setHomeSurface(surface: HomeSurface) {
        _state.update { current -> current.copy(homeSurface = surface) }
    }

    /** Ask the Memories deck to open stopped on a specific album (by candidate sessionId). */
    fun requestMemoriesFocus(sessionId: Long) {
        _state.update { current ->
            current.copy(memories = current.memories.copy(pendingFocusSessionId = sessionId))
        }
    }

    /**
     * Retire a consumed focus request, but only if it still matches [expected] —
     * so a superseded load can't wipe a newer tap's pending focus.
     */
    fun clearMemoriesFocus(expected: Long) {
        _state.update { current ->
            if (current.memories.pendingFocusSessionId != expected) {
                current
            } else {
                current.copy(memories = current.memories.copy(pendingFocusSessionId = null))
            }
        }
    }

    fun setNowPlayingExpanded(expanded: Boolean) {
        _state.update { current -> current.copy(nowPlayingExpanded = expanded) }
    }

    /** Flip the shell bar between nav chrome and detail (Play-split) chrome. */
    fun setDetailChromeActive(active: Boolean) {
        _state.update { current ->
            if (current.detailChromeActive == active) current
            else current.copy(detailChromeActive = active)
        }
    }

    /**
     * Ticks when a detail Activity's window has actually LEFT the screen
     * (its onStop — the system holds that until the exit animation ends).
     * The shell's detail-chrome restore (bar reverse morph) waits on this
     * instead of guessing the dissolve duration: OEMs replace or stretch
     * activity animations.
     */
    private val _detailWindowSettledTick = MutableStateFlow(0L)
    val detailWindowSettledTick: StateFlow<Long> = _detailWindowSettledTick.asStateFlow()

    fun noteDetailWindowSettled() {
        _detailWindowSettledTick.update { it + 1L }
    }

    fun replaceMemoriesDeck(
        activityIds: List<Long>,
        currentPage: Int,
    ) {
        val sanitizedIds = activityIds.distinct()
        val retainedActivityIds = sanitizedIds.toSet()
        val boundedPage = currentPage.coerceIn(
            minimumValue = 0,
            maximumValue = sanitizedIds.lastIndex.coerceAtLeast(0),
        )

        _state.update { current ->
            current.copy(
                memories = current.memories.copy(
                    deckId = current.memories.deckId + 1L,
                    currentDeckActivityIds = sanitizedIds,
                    currentPage = boundedPage,
                    perMemoryScrollOffsets = current.memories.perMemoryScrollOffsets
                        .filterKeys { activityId -> activityId in retainedActivityIds },
                ),
            )
        }
    }

    fun setMemoriesCurrentPage(page: Int) {
        _state.update { current ->
            current.copy(
                memories = current.memories.copy(
                    currentPage = page.coerceIn(
                        minimumValue = 0,
                        maximumValue = current.memories.currentDeckActivityIds.lastIndex.coerceAtLeast(0),
                    ),
                ),
            )
        }
    }

    fun setMemoryScrollPosition(
        activityId: Long,
        position: MemoryScrollPosition,
    ) {
        _state.update { current ->
            current.copy(
                memories = current.memories.copy(
                    perMemoryScrollOffsets = current.memories.perMemoryScrollOffsets +
                        (activityId to position),
                ),
            )
        }
    }

    fun clearMemories() {
        _state.update { current ->
            current.copy(memories = MemoriesSessionState())
        }
    }
}
