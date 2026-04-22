package com.gpo.yoin.ui.memories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import com.gpo.yoin.ui.experience.MemoryScrollPosition
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemoriesViewModel(
    private val deckCoordinator: MemoriesDeckCoordinator,
    private val sessionStore: ExperienceSessionStore,
    private val repository: YoinRepository,
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

    /**
     * 正在推 NeoDB 的 Memory entity id —— UI 用它禁用重复点击 +展示 loading。
     * 同步操作发生在后台；并发两条不同卡片的 push 是被允许的（不同 uuid）。
     */
    private val _syncingEntityIds = MutableStateFlow<Set<String>>(emptySet())
    val syncingEntityIds: StateFlow<Set<String>> = _syncingEntityIds.asStateFlow()

    private val _events = MutableSharedFlow<MemoriesOneShotEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MemoriesOneShotEvent> = _events.asSharedFlow()

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

    /**
     * Memory 卡片上「同步到 NeoDB」按钮的入口。
     *
     * - 没登录（NeoDB config 缺 token）→ 发 [MemoriesOneShotEvent.NeoDBNotConfigured]，
     *   让 UI 层引导用户去 Settings 配 BYOK。
     * - 登录了，但本地该专辑没评分也没评论 → 发 [MemoriesOneShotEvent.NeoDBNothingToSync]，
     *   告诉用户先评分 / 写 review 再来。
     * - 正常路径 → 走 repository.pushAlbumToNeoDB，成功 / 失败都通过
     *   [MemoriesOneShotEvent.NeoDBSyncResult] 通知 UI。
     *
     * entity 只接受 [MemoryEntityType.ALBUM] —— 单曲 / 歌单 Memory 不推。
     */
    fun pushToNeoDb(memory: MemoryEntry) {
        if (memory.entityType != MemoryEntityType.ALBUM) return
        if (memory.entityId in _syncingEntityIds.value) return

        viewModelScope.launch {
            if (!repository.isNeoDBConfigured()) {
                _events.tryEmit(MemoriesOneShotEvent.NeoDBNotConfigured)
                return@launch
            }

            _syncingEntityIds.value = _syncingEntityIds.value + memory.entityId
            try {
                // Memory 卡片的 `entityId` 只是裸 rawId —— 真正的 provider 从
                // 第一条 playback track 的 MediaId 取；都没有就回退 Subsonic。
                val provider = memory.playbackSongs.firstOrNull()?.id?.provider
                    ?: MediaId.PROVIDER_SUBSONIC
                val resolvedAlbumId = MediaId(provider, memory.entityId)
                val album = repository.getAlbum(resolvedAlbumId)
                if (album == null) {
                    _events.tryEmit(
                        MemoriesOneShotEvent.NeoDBSyncResult(
                            memoryStableId = memory.stableId,
                            success = false,
                            message = "Album metadata unavailable — try opening the album first.",
                        ),
                    )
                    return@launch
                }

                // 没评分也没 review —— 推上去是空操作，直接提示用户。
                val existingRating = runCatching {
                    repository.observeAlbumRating(resolvedAlbumId).first()
                }.getOrNull()
                val hasRating = (existingRating?.rating ?: 0f) > 0f
                val hasReview = !existingRating?.review.isNullOrBlank()
                if (!hasRating && !hasReview) {
                    _events.tryEmit(MemoriesOneShotEvent.NeoDBNothingToSync)
                    return@launch
                }

                // 按需置脏：只标有内容的一侧，避免把「空 rating」推到 NeoDB
                // 覆盖掉用户在网页端打的分。ratingNeedsSync 和 reviewNeedsSync
                // 两个脏位分开就是为了防这种情况。
                if (hasRating) {
                    repository.setAlbumRating(album, existingRating!!.rating)
                }
                if (hasReview) {
                    repository.setAlbumReview(album, existingRating!!.review)
                }

                val result = repository.pushAlbumToNeoDB(album)
                _events.tryEmit(
                    MemoriesOneShotEvent.NeoDBSyncResult(
                        memoryStableId = memory.stableId,
                        success = result.isSuccess,
                        message = if (result.isSuccess) {
                            "Synced to NeoDB"
                        } else {
                            result.exceptionOrNull()?.message ?: "NeoDB sync failed"
                        },
                    ),
                )
            } finally {
                _syncingEntityIds.value = _syncingEntityIds.value - memory.entityId
            }
        }
    }

    class Factory(
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MemoriesViewModel(
                deckCoordinator = container.memoriesDeckCoordinator,
                sessionStore = container.experienceSessionStore,
                repository = container.repository,
            ) as T
    }
}

/**
 * 一次性事件，送到 Screen 做 snackbar / 导航。不走 UiState 是因为这些事件
 * 触发后立即消费完就结束，不需要参与重组；放 UiState 会让每次 Content
 * recompose 都要处理一遍残留字段。
 */
sealed interface MemoriesOneShotEvent {
    data object NeoDBNotConfigured : MemoriesOneShotEvent

    data object NeoDBNothingToSync : MemoriesOneShotEvent

    data class NeoDBSyncResult(
        val memoryStableId: String,
        val success: Boolean,
        val message: String,
    ) : MemoriesOneShotEvent
}
