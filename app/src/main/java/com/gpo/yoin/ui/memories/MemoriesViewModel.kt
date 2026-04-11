package com.gpo.yoin.ui.memories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import com.gpo.yoin.ui.experience.MemoryScrollPosition
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemoriesViewModel(
    private val deckCoordinator: MemoriesDeckCoordinator,
    private val sessionStore: ExperienceSessionStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow<MemoriesUiState>(MemoriesUiState.Loading)
    val uiState: StateFlow<MemoriesUiState> = _uiState.asStateFlow()

    val sessionState = sessionStore.state
        .map { state -> state.memories }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = sessionStore.state.value.memories,
        )

    private var initialLoadJob: Job? = null
    private var adjacentDeckJob: Job? = null

    fun ensureLoaded(force: Boolean = false) {
        if (!force) {
            when (_uiState.value) {
                is MemoriesUiState.Content,
                MemoriesUiState.Empty,
                -> return
                else -> Unit
            }
            if (initialLoadJob?.isActive == true) return
        }

        initialLoadJob = viewModelScope.launch {
            _uiState.value = MemoriesUiState.Loading
            try {
                if (force) {
                    deckCoordinator.invalidate()
                    sessionStore.clearMemories()
                }

                val memories = deckCoordinator.ensureDeck()
                _uiState.value = if (memories.isEmpty()) {
                    MemoriesUiState.Empty
                } else {
                    MemoriesUiState.Content(
                        memories = memories,
                        deckRevision = sessionState.value.deckId.toInt(),
                        deckDirection = MemoryDeckDirection.Forward,
                    )
                }
            } catch (error: Exception) {
                _uiState.value = MemoriesUiState.Error(
                    error.message ?: "Failed to load memories",
                )
            } finally {
                initialLoadJob = null
            }
        }
    }

    fun refresh() {
        ensureLoaded(force = true)
    }

    fun advanceDeck(direction: MemoryDeckDirection) {
        val currentContent = _uiState.value as? MemoriesUiState.Content ?: return
        if (adjacentDeckJob?.isActive == true) return

        _uiState.value = currentContent.copy(isLoadingAdjacentDeck = true)
        adjacentDeckJob = viewModelScope.launch {
            try {
                val nextDeck = deckCoordinator.advanceDeck(direction)
                if (nextDeck.isEmpty()) {
                    _uiState.value = currentContent.copy(isLoadingAdjacentDeck = false)
                    return@launch
                }

                _uiState.value = currentContent.copy(
                    memories = nextDeck,
                    deckRevision = sessionState.value.deckId.toInt(),
                    deckDirection = direction,
                    isLoadingAdjacentDeck = false,
                )
            } catch (_: Exception) {
                val latest = _uiState.value as? MemoriesUiState.Content ?: return@launch
                _uiState.value = latest.copy(isLoadingAdjacentDeck = false)
            } finally {
                adjacentDeckJob = null
            }
        }
    }

    fun setCurrentPage(page: Int) {
        val currentPage = sessionState.value.currentPage
        if (currentPage == page) return
        sessionStore.setMemoriesCurrentPage(page)
    }

    fun setMemoryScroll(
        activityId: Long,
        position: MemoryScrollPosition,
    ) {
        if (sessionState.value.perMemoryScrollOffsets[activityId] == position) return
        sessionStore.setMemoryScrollPosition(activityId, position)
    }

    class Factory(
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MemoriesViewModel(
                deckCoordinator = container.memoriesDeckCoordinator,
                sessionStore = container.experienceSessionStore,
            ) as T
    }
}
