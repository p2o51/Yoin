package com.gpo.yoin.ui.memories

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.memory.AlbumMemoryTitleStore
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.detail.AlbumNeoDbSync
import com.gpo.yoin.ui.detail.albumNeoDbSync
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import com.gpo.yoin.ui.memories.copy.MemoryTitleKind
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class MemoriesViewModel(
    private val deckCoordinator: MemoriesDeckCoordinator,
    private val sessionStore: ExperienceSessionStore,
    private val repository: YoinRepository,
    private val activeProfileId: StateFlow<String?>,
    // The player, for the diary's playback highlight and "play from this note" (null in tests that don't care).
    private val playback: MemoriesPlayback? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    // The user's own album titles (null in tests that don't edit titles): Memories writes them and follows them.
    private val titleStore: AlbumMemoryTitleStore? = null,
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

    /** Albums whose last NeoDB push failed: their line offers a retry until a push lands. */
    private val _neoDbFailed = MutableStateFlow<Set<String>>(emptySet())

    private val _events = MutableSharedFlow<MemoriesOneShotEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MemoriesOneShotEvent> = _events.asSharedFlow()

    private var initialLoadJob: Job? = null
    private var adjacentDeckJob: Job? = null
    private var refreshJob: Job? = null
    private var seekJob: Job? = null

    /**
     * Reviews being saved from a diary's blank page, by [MemoryEntry.stableId]. A draft stays here until the
     * write lands, and stays after a failed one, so the blank page reopens with it.
     */
    private val _reviewDrafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val reviewDrafts: StateFlow<Map<String, String>> = _reviewDrafts.asStateFlow()

    /**
     * Title writes not yet landed, by [MemoryEntry.stableId]: the title the card already shows (null = Yoin's
     * own, a restore). Every deck painted meanwhile keeps it, and the store's echo skips the card until then.
     */
    private val pendingTitles = mutableMapOf<String, PendingTitle>()

    // One title write at a time, in the order the user saved them.
    private val titleWrites = Mutex()

    /** NeoDB is configured: the diary's quiet push entry shows only then. */
    private val _neoDbConfigured = MutableStateFlow(false)
    val neoDbConfigured: StateFlow<Boolean> = _neoDbConfigured.asStateFlow()

    /**
     * The playhead, narrowed to what the diary shows: the playing track's raw id and the note it is "inside"
     * (NP's rule, [memoryLitNoteId]). Both distinct: a position tick inside one note's stretch emits nothing
     * (the NP position-dedup invariant — no per-tick field reaches any UI state).
     */
    private val playhead: Flow<MemoriesPlayhead> = memoriesPlayheadFlow(
        state = playback?.state ?: flowOf(PlaybackState()),
        deck = _uiState.map { ui -> (ui as? MemoriesUiState.Content)?.memories.orEmpty() },
    )

    val playingTrackId: StateFlow<String?> = playhead
        .map { head -> head.trackId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(PLAYHEAD_STOP_MS), null)

    val litNoteId: StateFlow<String?> = playhead
        .map { head -> head.noteId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(PLAYHEAD_STOP_MS), null)

    init {
        viewModelScope.launch {
            activeProfileId
                .collect {
                    deckCoordinator.invalidate()
                    sessionStore.clearMemories()
                    ensureLoaded(force = true)
                    refreshNeoDbConfigured()
                }
        }
        observeMemorySignals()
        observeMemoryTitles()
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
                // Cold start: the profile id is restored synchronously but its
                // source is built asynchronously; a build against no source lands
                // on Empty. Bounded — after it, Empty / Error stay retryable.
                repository.awaitActiveSource(ACTIVE_SOURCE_WAIT_MS)

                val memories = withPendingTitles(deckCoordinator.ensureDeck())
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
                    error.message?.let(UiText::Raw) ?: UiText.Res(R.string.mem_error_load),
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
                // A widget tap can land here before the cold-start source exists.
                repository.awaitActiveSource(ACTIVE_SOURCE_WAIT_MS)
                val memories = withPendingTitles(deckCoordinator.ensureDeckFocused(focusSessionId))
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
                    error.message?.let(UiText::Raw) ?: UiText.Res(R.string.mem_error_load),
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
                val nextDeck = withPendingTitles(deckCoordinator.advanceDeck(direction))
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

    private suspend fun refreshNeoDbConfigured() {
        _neoDbConfigured.value = try {
            repository.isNeoDBConfigured()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Today's blank diary page was saved. Optimistic: the entry becomes the review at once (the diary turns
     * the page into the review entry in place) and the draft is held until the write lands. The album comes
     * from the detail cache ([YoinRepository.getAlbum]); without it (offline, no cache) — or if the write
     * throws — the optimistic review is rolled back, the draft is KEPT and [MemoriesOneShotEvent.ReviewSaveFailed]
     * reports it. A landed write moves the memory signal, and the in-place refresh re-resolves the card.
     */
    fun saveReview(memory: MemoryEntry, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || memory.entityType != MemoryEntityType.ALBUM) return
        val key = memory.stableId
        _reviewDrafts.value = _reviewDrafts.value + (key to trimmed)
        val before = (_uiState.value as? MemoriesUiState.Content)?.memories?.firstOrNull { it.stableId == key }
        val optimistic = MemoryWriting(kind = MemoryWriting.Kind.REVIEW, text = trimmed, writtenAt = clock())
        patchMemory(key) { entry -> entry.copy(review = optimistic, hasAlbumReview = true) }
        viewModelScope.launch {
            val saved = try {
                val album = repository.getAlbum(MediaId(memory.entityProvider, memory.entityId))
                if (album != null) {
                    repository.setAlbumReview(album, trimmed)
                    true
                } else {
                    false
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "saveReview failed for $key", error)
                false
            }
            if (saved) {
                _reviewDrafts.value = _reviewDrafts.value - key
                // R3: the words go to NeoDB on their own when signed in (read now: sign-in happens in Settings).
                refreshNeoDbConfigured()
                if (_neoDbConfigured.value) pushToNeoDb(memory)
            } else {
                patchMemory(key) { entry ->
                    if (entry.review?.text == trimmed) {
                        entry.copy(review = before?.review, hasAlbumReview = before?.hasAlbumReview ?: false)
                    } else {
                        entry
                    }
                }
                _events.tryEmit(
                    MemoriesOneShotEvent.ReviewSaveFailed(
                        memoryStableId = key,
                        message = UiText.Res(R.string.mem_snackbar_save_review),
                    ),
                )
            }
        }
    }

    private fun patchMemory(stableId: String, transform: (MemoryEntry) -> MemoryEntry) {
        val content = _uiState.value as? MemoriesUiState.Content ?: return
        if (content.memories.none { it.stableId == stableId }) return
        _uiState.value = content.copy(
            memories = content.memories.map { entry -> if (entry.stableId == stableId) transform(entry) else entry },
        )
    }

    /**
     * Play-from-a-note's second half: the caller has asked for [trackId] to play (onPlayMemoryTrack, which
     * always starts at 0); once that track really is current — and prepared (duration > 0) — seek to
     * [positionMs], once. Gives up after [SEEK_TIMEOUT_MS] (a provider that never reports it). When the track
     * is already current, seek now (resuming it if paused) instead of restarting it.
     */
    fun requestSeek(trackId: String, positionMs: Long) {
        val player = playback ?: return
        seekJob?.cancel()
        val now = player.state.value
        if (now.currentTrack?.id?.rawId == trackId && now.duration > 0L) {
            player.seekTo(positionMs)
            if (!now.isPlaying) player.resume()
            return
        }
        seekJob = viewModelScope.launch {
            val ready = withTimeoutOrNull(SEEK_TIMEOUT_MS) {
                player.state.first { state -> state.currentTrack?.id?.rawId == trackId && state.duration > 0L }
            }
            if (ready != null && positionMs > 0L) player.seekTo(positionMs)
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
            _uiState.value = latest.copy(memories = withPendingTitles(refreshed))
        }
    }

    /**
     * The user named this memory (tapped its title, typed, Save). Trimmed; a blank title is a restore
     * ([restoreMemoryTitle]); the same title as the card's own is not an edit. Optimistic: the card takes the
     * title at once (it morphs in place) and the write lands behind it; a failed write puts the old title back
     * and reports [MemoriesOneShotEvent.TitleSaveFailed].
     */
    fun saveMemoryTitle(memory: MemoryEntry, text: String) {
        writeMemoryTitle(memory, text.trim().takeIf(String::isNotEmpty))
    }

    /** Back to Yoin's own title (the AI title, else the motif, else the album name): the row is deleted. */
    fun restoreMemoryTitle(memory: MemoryEntry) {
        writeMemoryTitle(memory, null)
    }

    private fun writeMemoryTitle(memory: MemoryEntry, userTitle: String?) {
        val store = titleStore ?: return
        if (memory.entityType != MemoryEntityType.ALBUM) return
        val key = memory.stableId
        val shown = (_uiState.value as? MemoriesUiState.Content)?.memories?.firstOrNull { it.stableId == key } ?: memory
        val before = shown.userMemoryTitle()
        if (before == userTitle) return
        val pending = PendingTitle(userTitle)
        pendingTitles[key] = pending
        patchMemory(key) { entry -> entry.withUserMemoryTitle(userTitle) }
        val albumId = MediaId(memory.entityProvider, memory.entityId)
        viewModelScope.launch {
            val saved = try {
                titleWrites.withLock {
                    if (userTitle == null) store.clearTitle(albumId) else store.setTitle(albumId, userTitle)
                }
                true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "title write failed for $key", error)
                false
            }
            // a newer save of the same card owns it now
            if (pendingTitles[key] !== pending) return@launch
            pendingTitles.remove(key)
            if (!saved) {
                patchMemory(key) { entry ->
                    if (entry.userMemoryTitle() == userTitle) entry.withUserMemoryTitle(before) else entry
                }
                _events.tryEmit(
                    MemoriesOneShotEvent.TitleSaveFailed(
                        memoryStableId = key,
                        message = UiText.Res(R.string.mem_snackbar_save_title),
                    ),
                )
            }
        }
    }

    /** [memories] with the titles still being written, as the cards already show them. */
    private fun withPendingTitles(memories: List<MemoryEntry>): List<MemoryEntry> {
        if (pendingTitles.isEmpty()) return memories
        return memories.map { entry ->
            pendingTitles[entry.stableId]?.let { pending -> entry.withUserMemoryTitle(pending.userTitle) } ?: entry
        }
    }

    /**
     * The open deck follows the store: a title set on the album page, or pulled by sync, re-titles its card in
     * place (the coordinator reads the store on every deal; this covers the deck already painted). Cards with a
     * write in flight keep their optimistic title until it lands.
     */
    private fun observeMemoryTitles() {
        val store = titleStore ?: return
        viewModelScope.launch {
            store.observeTitles().collect { titles ->
                val content = _uiState.value as? MemoriesUiState.Content ?: return@collect
                val patched = content.memories.map { entry ->
                    if (entry.entityType != MemoryEntityType.ALBUM || entry.stableId in pendingTitles) {
                        entry
                    } else {
                        entry.withUserMemoryTitle(titles[MediaId(entry.entityProvider, entry.entityId)])
                    }
                }
                if (patched != content.memories) _uiState.value = content.copy(memories = patched)
            }
        }
    }

    /**
     * Where NeoDB stands for [memory]'s album, as the diary's last line reads it — the same states as the album
     * page's rate sheet ([albumNeoDbSync]): the row's dirty flags, plus a push in flight or one that failed.
     * Not an album memory: [AlbumNeoDbSync.Unknown] (no line).
     */
    fun neoDbSync(memory: MemoryEntry): Flow<AlbumNeoDbSync> {
        if (memory.entityType != MemoryEntityType.ALBUM) return flowOf(AlbumNeoDbSync.Unknown)
        val key = memory.neoDbKey()
        val row = repository.observeAlbumRating(MediaId(memory.entityProvider, memory.entityId))
        return combine(_neoDbConfigured, row, _syncingEntityIds, _neoDbFailed) { configured, rating, syncing, failed ->
            val base = albumNeoDbSync(configured, rating)
            when {
                key in syncing -> AlbumNeoDbSync.Syncing
                // A failure stands only while its change still waits: synced elsewhere (the album page), it's gone.
                base == AlbumNeoDbSync.Pending && key in failed -> AlbumNeoDbSync.Failed
                else -> base
            }
        }.distinctUntilChanged()
    }

    /**
     * Hands [memory]'s album to NeoDB when something changed since the last push (owner R3, 2026-10-06: sync is
     * automatic — a review written here goes on its own; the diary's line only offers a retry). Signed out: the
     * one-shot sign-in prompt. A failure reports once and leaves the line on "Retry".
     */
    fun pushToNeoDb(memory: MemoryEntry) {
        if (memory.entityType != MemoryEntityType.ALBUM) return
        val syncKey = memory.neoDbKey()
        if (syncKey in _syncingEntityIds.value) return

        viewModelScope.launch {
            var registered = false
            try {
                if (!repository.isNeoDBConfigured()) {
                    _events.tryEmit(MemoriesOneShotEvent.NeoDBNotConfigured)
                    return@launch
                }
                val albumId = MediaId(memory.entityProvider, memory.entityId)
                val row = repository.getAlbumRatingRow(albumId)
                if (albumNeoDbSync(configured = true, row = row) != AlbumNeoDbSync.Pending) {
                    _neoDbFailed.value = _neoDbFailed.value - syncKey
                    return@launch
                }

                _syncingEntityIds.value = _syncingEntityIds.value + syncKey
                registered = true
                val album = repository.getAlbum(albumId)
                val result = album?.let { repository.pushAlbumToNeoDB(it) }
                    ?: Result.failure(IllegalStateException("Album metadata unavailable"))
                if (result.isSuccess) {
                    _neoDbFailed.value = _neoDbFailed.value - syncKey
                } else {
                    Log.w(TAG, "pushToNeoDb failed for $syncKey", result.exceptionOrNull())
                    reportNeoDbFailure(memory)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Offline with no cached detail, getAlbum throws. Nothing above viewModelScope catches it, so an
                // escape here takes the whole process down — report it as a failed sync instead.
                Log.w(TAG, "pushToNeoDb failed for $syncKey", error)
                reportNeoDbFailure(memory)
            } finally {
                if (registered) _syncingEntityIds.value = _syncingEntityIds.value - syncKey
            }
        }
    }

    private fun reportNeoDbFailure(memory: MemoryEntry) {
        _neoDbFailed.value = _neoDbFailed.value + memory.neoDbKey()
        _events.tryEmit(
            MemoriesOneShotEvent.NeoDBSyncResult(
                memoryStableId = memory.stableId,
                success = false,
                message = UiText.Res(R.string.mem_snackbar_sync),
            ),
        )
    }

    private fun MemoryEntry.neoDbKey(): String = "$entityProvider:$entityId"

    /** Re-reads whether NeoDB is signed in (the shell resumed: Settings may have signed in or out). */
    fun refreshNeoDbState() {
        viewModelScope.launch { refreshNeoDbConfigured() }
    }

    class Factory(
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        private val playback = object : MemoriesPlayback {
            override val state: StateFlow<PlaybackState> get() = container.playbackManager.playbackState

            override fun seekTo(positionMs: Long) = container.playbackManager.seekTo(positionMs)

            override fun resume() = container.playbackManager.resume()
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MemoriesViewModel(
                deckCoordinator = container.memoriesDeckCoordinator.also { coordinator ->
                    // Belongs in AppContainer's constructor call; that file is
                    // being edited by another session, so it is attached here.
                    coordinator.narrationSource = GeminiMemoryNarrationSource(
                        geminiService = container.geminiService,
                        geminiConfigDao = container.database.geminiConfigDao(),
                        cacheDao = container.database.memoryCopyCacheDao(),
                        activeProfileId = container.profileManager.activeProfileId,
                    )
                },
                sessionStore = container.experienceSessionStore,
                repository = container.repository,
                activeProfileId = container.profileManager.activeProfileId,
                playback = playback,
                titleStore = container.albumMemoryTitleStore,
            ) as T
    }

    companion object {
        private const val TAG = "MemoriesViewModel"
        private const val MEMORY_SIGNAL_DEBOUNCE_MS = 250L

        /** Play-from-a-note gives up on its seek if the track isn't current and prepared by then. */
        const val SEEK_TIMEOUT_MS = 4_000L

        /** Max wait for the active source on a cold start before a deck build goes ahead without it. */
        private const val ACTIVE_SOURCE_WAIT_MS = 4_000L

        private const val PLAYHEAD_STOP_MS = 2_000L
    }
}

/** What the diary needs from the player: its state (position ticks included) and two commands. */
interface MemoriesPlayback {
    val state: StateFlow<PlaybackState>

    fun seekTo(positionMs: Long)

    fun resume()
}

/** A title write in flight: the user's title it sets, or null for a restore. Compared by identity. */
private class PendingTitle(val userTitle: String?)

/** The user's own title on this card, or null while it shows Yoin's. */
internal fun MemoryEntry.userMemoryTitle(): String? = memoryTitle.takeIf { memoryTitleKind == MemoryTitleKind.USER }

/** The playhead as the diary reads it: the playing track's raw id and the lit note's id. */
internal data class MemoriesPlayhead(val trackId: String?, val noteId: String?)

/** The player's state (ticking) narrowed to [MemoriesPlayhead] over the deck: emits only when it changes. */
internal fun memoriesPlayheadFlow(state: Flow<PlaybackState>, deck: Flow<List<MemoryEntry>>): Flow<MemoriesPlayhead> =
    combine(state, deck, ::memoriesPlayhead).distinctUntilChanged()

/**
 * The playhead over [deck]: the current track's raw id, and among the notes of that track in the deck (the
 * same provider only), the one the playhead is inside ([memoryLitNoteId]).
 */
internal fun memoriesPlayhead(state: PlaybackState, deck: List<MemoryEntry>): MemoriesPlayhead {
    val track = state.currentTrack ?: return MemoriesPlayhead(null, null)
    val raw = track.id.rawId
    val notes = deck.asSequence()
        .filter { entry -> entry.entityProvider == track.id.provider }
        .flatMap { entry -> entry.diaryTracks.asSequence() }
        .filter { row -> row.track.trackId == raw }
        .flatMap { row -> row.notes.asSequence() }
        .toList()
    return MemoriesPlayhead(raw, memoryLitNoteId(notes, raw, state.position))
}

/**
 * NP's rule (currentAnchoredNoteId) on diary notes: of [trackId]'s anchored notes, the latest whose anchor is
 * at or before [positionMs] (a tie goes to the later one in the list). Null when none is reached yet.
 */
internal fun memoryLitNoteId(notes: List<MemoryWriting>, trackId: String?, positionMs: Long): String? {
    if (trackId == null) return null
    var best: MemoryWriting? = null
    for (note in notes) {
        if (note.trackId != trackId) continue
        val anchor = note.positionMs ?: continue
        if (anchor <= positionMs && (best?.positionMs ?: -1L) <= anchor) best = note
    }
    return best?.noteId
}

/**
 * 一次性事件，送到 Screen 做 snackbar / 导航。不走 UiState 是因为这些事件
 * 触发后立即消费完就结束，不需要参与重组；放 UiState 会让每次 Content
 * recompose 都要处理一遍残留字段。
 */
sealed interface MemoriesOneShotEvent {
    data object NeoDBNotConfigured : MemoriesOneShotEvent

    data class NeoDBSyncResult(
        val memoryStableId: String,
        val success: Boolean,
        val message: UiText,
    ) : MemoriesOneShotEvent

    /** A diary review didn't save; its draft is kept ([MemoriesViewModel.reviewDrafts]). */
    data class ReviewSaveFailed(
        val memoryStableId: String,
        val message: UiText,
    ) : MemoriesOneShotEvent

    /** A title edit didn't land; the card is back on the title it had ([MemoriesViewModel.saveMemoryTitle]). */
    data class TitleSaveFailed(
        val memoryStableId: String,
        val message: UiText,
    ) : MemoriesOneShotEvent
}
