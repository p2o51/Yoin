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
)

data class ExperienceSessionState(
    val selectedSection: YoinSection = YoinSection.HOME,
    val homeSurface: HomeSurface = HomeSurface.Feed,
    val nowPlayingExpanded: Boolean = false,
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

    fun setNowPlayingExpanded(expanded: Boolean) {
        _state.update { current -> current.copy(nowPlayingExpanded = expanded) }
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
