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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemoriesViewModel(
    private val deckCoordinator: MemoriesDeckCoordinator,
    private val sessionStore: ExperienceSessionStore,
    private val repository: YoinRepository,
    private val activeProfileId: StateFlow<String?>,
    // The active MusicSource's provider id (null until the source is built).
    private val activeSourceId: Flow<String?>,
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
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            activeProfileId
                .collect {
                    deckCoordinator.invalidate()
                    sessionStore.clearMemories()
                    ensureLoaded(force = true)
                }
        }
        // Cold start: the profile id is restored synchronously but its source is
        // built asynchronously, so the first build can run against no source and
        // land on Empty. Retry an Empty / Error deck once the source is up (or
        // changes); a painted deck or a load in flight is left alone.
        viewModelScope.launch {
            activeSourceId
                .distinctUntilChanged()
                .collect {
                    when (_uiState.value) {
                        MemoriesUiState.Empty,
                        is MemoriesUiState.Error,
                        -> ensureLoaded(force = true)
                        else -> Unit
                    }
                }
        }
        observeMemorySignals()
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
            // Only a painted deck or a load already in flight short-circuits.
            // Empty / Error are NOT terminal: a cold-start build that raced the
            // active source comes back empty, and reopening must retry it.
            if (_uiState.value is MemoriesUiState.Content) return
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
        // …and any in-place refresh of the deck this load is about to replace.
        refreshJob?.cancel()
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
     * Rebuild the deck stopped on [focusSessionId] (a Home memory pill / grid
     * card tap). Cancels any in-flight load so the tap always wins, rebuilds
     * the candidate pool once (the coordinator never lands a focus tap on a
     * stale card or a cached empty pool), and clears the pending focus request
     * when done.
     */
    private fun ensureLoadedFocused(focusSessionId: Long) {
        initialLoadJob?.cancel()
        adjacentDeckJob?.cancel()
        refreshJob?.cancel()
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
        // The advance replaces the deck; an in-place refresh of the outgoing
        // one would only be dropped by its deck-identity check.
        refreshJob?.cancel()

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

    @OptIn(FlowPreview::class)
    private fun observeMemorySignals() {
        viewModelScope.launch {
            // Rating / note / review writes create no activity event and the
            // coordinator's resolve cache outlives them, so the open deck is
            // re-resolved in place when the memory signals move. drop(1) skips
            // the stamp the deck was built from; the debounce coalesces a burst
            // of writes (and keeps a refresh out of a pager swipe's way).
            repository.observeMemorySignalStamp()
                .drop(1)
                .debounce(MEMORY_SIGNAL_DEBOUNCE_MS)
                .collect { refreshDeckInPlace() }
        }
    }

    /**
     * Re-resolve the painted deck after a memory write without re-dealing it:
     * same cards in the same order, same `deckRevision` (so the deck's
     * AnimatedContent doesn't replay) and the session's page is untouched.
     * Latest signal wins; any load / advance cancels it.
     */
    private fun refreshDeckInPlace() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // A load or advance in flight owns the deck shape — let it land,
            // then refresh whatever it painted.
            initialLoadJob?.join()
            adjacentDeckJob?.join()
            val content = _uiState.value as? MemoriesUiState.Content ?: return@launch
            val profileId = activeProfileId.value
            val deckId = sessionState.value.deckId
            val deckIds = content.memories.map(MemoryEntry::sourceActivityId)
            val refreshed = try {
                deckCoordinator.refreshDeck(content.memories)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // The old cards stay up; the next signal or open retries.
                Log.w(TAG, "refreshDeck failed", error)
                return@launch
            }
            // Profile switched, or a reload / advance replaced the deck while
            // this resolved: the refresh belongs to a deck that's gone.
            if (activeProfileId.value != profileId) return@launch
            if (sessionState.value.deckId != deckId) return@launch
            val latest = _uiState.value as? MemoriesUiState.Content ?: return@launch
            if (latest.memories.map(MemoryEntry::sourceActivityId) != deckIds) return@launch
            _uiState.value = latest.copy(memories = refreshed)
        }
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
            var registered = false
            try {
                if (!repository.isNeoDBConfigured()) {
                    _events.tryEmit(MemoriesOneShotEvent.NeoDBNotConfigured)
                    return@launch
                }

                _syncingEntityIds.value = _syncingEntityIds.value + syncKey
                registered = true
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
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Offline with no cached detail, getAlbum throws (and so can the
                // local rating writes). Nothing above viewModelScope catches it,
                // so an escape here takes the whole process down — report it as
                // a failed sync instead.
                Log.w(TAG, "pushToNeoDb failed for $syncKey", error)
                _events.tryEmit(
                    MemoriesOneShotEvent.NeoDBSyncResult(
                        memoryStableId = memory.stableId,
                        success = false,
                        message = "NeoDB sync failed",
                    ),
                )
            } finally {
                if (registered) _syncingEntityIds.value = _syncingEntityIds.value - syncKey
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
                activeSourceId = container.profileManager.activeSource.map { source -> source?.id },
            ) as T
    }

    companion object {
        private const val TAG = "MemoriesViewModel"
        private const val MEMORY_SIGNAL_DEBOUNCE_MS = 250L
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
