package com.gpo.yoin.ui.memories

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemoriesViewModel(
    private val deckCoordinator: MemoriesDeckCoordinator,
    private val sessionStore: ExperienceSessionStore,
    private val repository: YoinRepository,
    private val activeProfileId: StateFlow<String?>,
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

    init {
        viewModelScope.launch {
            activeProfileId
                .collect {
                    deckCoordinator.invalidate()
                    sessionStore.clearMemories()
                    ensureLoaded(force = true)
                }
        }
        // The home teaser parks a focus request in the session store; consume it
        // here so the deck opens stopped on that album whether the screen is
        // already mounted or about to mount.
        viewModelScope.launch {
            sessionState
                .map { state -> state.pendingFocusSessionId }
                .distinctUntilChanged()
                .collect { focusSessionId ->
                    if (focusSessionId != null) ensureLoadedFocused(focusSessionId)
                }
        }
    }

    fun ensureLoaded(force: Boolean = false) {
        if (!force) {
            // A pending focus request owns the next load — let ensureLoadedFocused
            // build the focused deck instead of racing a generic one in.
            if (sessionState.value.pendingFocusSessionId != null) return
            when (_uiState.value) {
                is MemoriesUiState.Content,
                MemoriesUiState.Empty,
                -> return
                else -> Unit
            }
            if (initialLoadJob?.isActive == true) return
        }

        // Single-writer: cancel any in-flight load (a focused load, or a prior
        // generic one) so only the latest request writes the deck. The captured
        // `job` + identity check in `finally` stops a superseded job — whose
        // cancellation `finally` runs late on Main.immediate — from nulling the
        // live job's reference and defeating the isActive guard.
        initialLoadJob?.cancel()
        // A reset also orphans any in-flight deck advance — cancel it so it
        // can't write (or re-persist) the previous profile's deck afterwards.
        adjacentDeckJob?.cancel()
        val job = viewModelScope.launch {
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
            } catch (cancellation: CancellationException) {
                // A focused load (or profile switch) superseded this one — don't
                // paint an Error over the deck the winning load is building.
                throw cancellation
            } catch (error: Exception) {
                _uiState.value = MemoriesUiState.Error(
                    error.message ?: "Failed to load memories",
                )
            } finally {
                if (initialLoadJob === coroutineContext[Job]) initialLoadJob = null
            }
        }
        initialLoadJob = job
    }

    /**
     * Rebuild the deck stopped on [focusSessionId] (home teaser entry). Cancels
     * any in-flight load so a teaser tap always wins, reuses the cached candidate
     * pool (no full re-scan), and clears the pending focus request when done.
     */
    private fun ensureLoadedFocused(focusSessionId: Long) {
        initialLoadJob?.cancel()
        adjacentDeckJob?.cancel()
        val job = viewModelScope.launch {
            _uiState.value = MemoriesUiState.Loading
            try {
                val memories = deckCoordinator.ensureDeckFocused(focusSessionId)
                _uiState.value = if (memories.isEmpty()) {
                    MemoriesUiState.Empty
                } else {
                    MemoriesUiState.Content(
                        memories = memories,
                        deckRevision = sessionState.value.deckId.toInt(),
                        deckDirection = MemoryDeckDirection.Forward,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.value = MemoriesUiState.Error(
                    error.message ?: "Failed to load memories",
                )
            } finally {
                // Compare-and-clear: only retire the focus request this job is
                // consuming, so a superseded job can't wipe a newer tap's request.
                sessionStore.clearMemoriesFocus(focusSessionId)
                if (initialLoadJob === coroutineContext[Job]) initialLoadJob = null
            }
        }
        initialLoadJob = job
    }

    fun refresh() {
        ensureLoaded(force = true)
    }

    fun advanceDeck(direction: MemoryDeckDirection) {
        val currentContent = _uiState.value as? MemoriesUiState.Content ?: return
        if (adjacentDeckJob?.isActive == true) return
        // The coordinator call below can outlive a profile switch (which only
        // cancels this job); re-check the owning profile after the suspension
        // so a slow advance can never paint one account's deck under another.
        val profileId = activeProfileId.value

        _uiState.value = currentContent.copy(isLoadingAdjacentDeck = true)
        adjacentDeckJob = viewModelScope.launch {
            try {
                val nextDeck = deckCoordinator.advanceDeck(direction)
                if (activeProfileId.value != profileId) return@launch
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
            } catch (cancellation: CancellationException) {
                // Superseded by a reload / profile switch — the winning load owns
                // the state, but never leave a Content stuck mid-advance.
                val latest = _uiState.value as? MemoriesUiState.Content
                if (latest?.isLoadingAdjacentDeck == true) {
                    _uiState.value = latest.copy(isLoadingAdjacentDeck = false)
                }
                throw cancellation
            } catch (_: Exception) {
                val latest = _uiState.value as? MemoriesUiState.Content ?: return@launch
                _uiState.value = latest.copy(isLoadingAdjacentDeck = false)
            } finally {
                if (adjacentDeckJob === coroutineContext[Job]) adjacentDeckJob = null
            }
        }
    }

    fun setCurrentPage(page: Int) {
        val currentPage = sessionState.value.currentPage
        if (currentPage == page) return
        sessionStore.setMemoriesCurrentPage(page)
    }

    /**
     * Memory 卡片上「同步到 NeoDB」按钮的入口。
     *
     * - 没登录（NeoDB config 缺 token）→ 发 [MemoriesOneShotEvent.NeoDBNotConfigured]，
     *   让 UI 层引导用户去 Settings 配 BYOK。
     * - 登录了，但本地该专辑缺评分或 review → 发 [MemoriesOneShotEvent.NeoDBNothingToSync]，
     *   告诉用户先补齐专辑评分和 review 再来。
     * - 正常路径 → 走 repository.pushAlbumToNeoDB，成功 / 失败都通过
     *   [MemoriesOneShotEvent.NeoDBSyncResult] 通知 UI。
     *
     * entity 只接受 [MemoryEntityType.ALBUM] —— 单曲 / 歌单 Memory 不推。
     */
    fun pushToNeoDb(memory: MemoryEntry) {
        if (memory.entityType != MemoryEntityType.ALBUM) return
        val syncKey = "${memory.entityProvider}:${memory.entityId}"
        if (syncKey in _syncingEntityIds.value) return

        viewModelScope.launch {
            if (!repository.isNeoDBConfigured()) {
                _events.tryEmit(MemoriesOneShotEvent.NeoDBNotConfigured)
                return@launch
            }

            _syncingEntityIds.value = _syncingEntityIds.value + syncKey
            try {
                val resolvedAlbumId = MediaId(memory.entityProvider, memory.entityId)
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

                // NeoDB 以 album Mark + Review 为目标；第一阶段要求两者
                // 都存在，避免把半截 Memory 推成远端状态。
                val existingRating = runCatching {
                    repository.observeAlbumRating(resolvedAlbumId).first()
                }.getOrNull()
                val hasRating = (existingRating?.rating ?: 0f) > 0f
                val hasReview = !existingRating?.review.isNullOrBlank()
                if (!hasRating || !hasReview) {
                    _events.tryEmit(MemoriesOneShotEvent.NeoDBNothingToSync)
                    return@launch
                }

                // 按需置脏：只标有内容的一侧，避免把「空 rating」推到 NeoDB
                // 覆盖掉用户在网页端打的分。ratingNeedsSync 和 reviewNeedsSync
                // 两个脏位分开就是为了防这种情况。
                val rating = existingRating
                if (hasRating) {
                    repository.setAlbumRating(album, rating.rating)
                }
                if (hasReview) {
                    repository.setAlbumReview(album, rating.review)
                }

                val result = repository.pushAlbumToNeoDB(album)
                if (result.isFailure) {
                    Log.w(
                        TAG,
                        "pushToNeoDb failed for ${resolvedAlbumId.provider}:${resolvedAlbumId.rawId}",
                        result.exceptionOrNull(),
                    )
                }
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
                _syncingEntityIds.value = _syncingEntityIds.value - syncKey
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
                activeProfileId = container.profileManager.activeProfileId,
            ) as T
    }

    companion object {
        private const val TAG = "MemoriesViewModel"
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
