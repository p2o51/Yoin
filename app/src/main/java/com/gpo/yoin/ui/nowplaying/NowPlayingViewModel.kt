package com.gpo.yoin.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.model.Lyrics as SourceLyrics
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.YoinDevice
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.player.CastManager
import com.gpo.yoin.player.CastState
import com.gpo.yoin.player.ConnectionPhase
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.ui.component.AddToPlaylistRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModel(
    private val playbackManager: PlaybackManager,
    private val repository: YoinRepository,
    private val castManager: CastManager,
    private val onPlaylistMutated: () -> Unit = {},
) : ViewModel() {

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    private val _lyricsLoading = MutableStateFlow(false)
    private val _isStarred = MutableStateFlow(false)

    // Transient ask-bar state drives the fullscreen About UI animation — NOT
    // persisted. See [AskBarState].
    private val _askState = MutableStateFlow<AskBarState>(AskBarState.Idle)
    val askState: StateFlow<AskBarState> = _askState.asStateFlow()

    // `aboutFetchError` / `aboutLoading` are UI-only overlays on top of the
    // observed Room flow. Room observer always has the ground truth list;
    // these two only surface the transient loading / error states that a
    // Flow of entries can't express.
    private val _aboutLoading = MutableStateFlow(false)
    private val _aboutError = MutableStateFlow<AboutUiState?>(null)

    // Fullscreen detail mode + selected page. Default = Compact/Lyrics so
    // that a cold Now Playing open preserves today's behaviour. YoinNavHost
    // can override via [setDetailMode] / [setDetailPage].
    private val _detailMode = MutableStateFlow(NowPlayingDetailMode.Compact)
    val detailMode: StateFlow<NowPlayingDetailMode> = _detailMode.asStateFlow()

    private val _detailPage = MutableStateFlow(NowPlayingDetailPage.Lyrics)
    val detailPage: StateFlow<NowPlayingDetailPage> = _detailPage.asStateFlow()

    private val currentSongId: StateFlow<MediaId?> = playbackManager.playbackState
        .map { it.currentTrack?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            // collectLatest（不是 collect）：切歌时立刻取消前一首的 loadLyrics
            // —— 否则前一首的 provider fetch（10 秒 callTimeout）会把
            // 整条 pipeline 串行阻塞住，连星标都要等前一首的 HTTP 超时才刷新。
            //
            // About/canonical fetch **不**在这里触发（v0.5 起懒加载）—— 等用户
            // 第一次打开 About 页再调 [onAboutOpened]。
            currentSongId.collectLatest { songId ->
                if (songId != null) {
                    // 切歌瞬间就应该生效的状态（星标 / 歌词清空 / loading on）
                    // 必须在任何 suspend 调用之前更新，否则一 suspend 就可能被下一次
                    // collectLatest 取消掉，用户看到的就是「上一首内容原地不动」。
                    val track = playbackManager.playbackState.value.currentTrack
                    _isStarred.value = track?.isStarred == true
                    _lyrics.value = emptyList()
                    _lyricsLoading.value = true
                    _aboutError.value = null
                    _askState.value = AskBarState.Idle

                    loadLyrics(songId, track?.title, track?.artist)
                } else {
                    _lyrics.value = emptyList()
                    _lyricsLoading.value = false
                    _isStarred.value = false
                    _aboutError.value = null
                    _askState.value = AskBarState.Idle
                }
            }
        }
    }

    private val ratingFlow = currentSongId.flatMapLatest { songId ->
        if (songId != null) {
            repository.getRating(songId).map { it?.rating ?: 0f }
        } else {
            flowOf(0f)
        }
    }

    val notesState: StateFlow<List<SongNote>> = currentSongId
        .flatMapLatest { songId ->
            if (songId != null) {
                repository.observeNotes(songId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Live About entries for the current song (canonical + ask rows). Uses
     * title + artist + album as the identity so the same song played from
     * a different profile / provider sees the same cached entries.
     */
    private val aboutEntriesFlow = playbackManager.playbackState
        .map { state ->
            val track = state.currentTrack
            if (track == null) {
                null
            } else {
                Triple(track.title.orEmpty(), track.artist.orEmpty(), track.album.orEmpty())
            }
        }
        .distinctUntilChanged()
        .flatMapLatest { trio ->
            if (trio == null) {
                flowOf(emptyList())
            } else {
                repository.observeAbout(trio.first, trio.second, trio.third)
            }
        }

    val aboutUiState: StateFlow<AboutUiState> = combine(
        aboutEntriesFlow,
        _aboutLoading,
        _aboutError,
    ) { entries, loading, error ->
        when {
            error != null -> error
            entries.isNotEmpty() -> AboutUiState.Ready(entries)
            loading -> AboutUiState.Loading
            else -> AboutUiState.Idle
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AboutUiState.Idle)

    private val _devicesState = MutableStateFlow(DevicesSheetState())
    val devicesState: StateFlow<DevicesSheetState> = _devicesState.asStateFlow()

    private val playbackAndContext = combine(
        playbackManager.playbackState,
        playbackManager.currentActivityContext,
    ) { state, ctx -> state to ctx }

    val uiState: StateFlow<NowPlayingUiState> = combine(
        playbackAndContext,
        _lyrics,
        _lyricsLoading,
        ratingFlow,
        _isStarred,
    ) { (state, activityContext), lyrics, lyricsLoading, rating, isStarred ->
        val song = state.currentTrack
        val pending = state.pendingTrack
        when {
            state.connectionPhase == ConnectionPhase.Error && song != null ->
                NowPlayingUiState.ConnectError(
                    songTitle = song.title.orEmpty(),
                    artist = song.artist.orEmpty(),
                    coverArtUrl = repository.resolveCoverUrl(song.coverArt),
                    message = state.connectionErrorMessage
                        ?: "Playback was interrupted.",
                )

            song != null -> NowPlayingUiState.Playing(
                songTitle = song.title.orEmpty(),
                artist = song.artist.orEmpty(),
                albumName = song.album.orEmpty(),
                coverArtUrl = repository.resolveCoverUrl(song.coverArt),
                isPlaying = state.isPlaying,
                positionMs = state.position,
                durationMs = state.duration,
                bufferedMs = state.bufferedPosition,
                songId = song.id.toString(),
                rating = rating,
                isStarred = isStarred,
                lyrics = lyrics,
                lyricsLoading = lyricsLoading,
                queue = state.queue.map { queueSong ->
                    QueueItem(
                        songId = queueSong.id.toString(),
                        title = queueSong.title.orEmpty(),
                        artist = queueSong.artist.orEmpty(),
                        coverArtUrl = repository.resolveCoverUrl(queueSong.coverArt),
                    )
                },
                currentQueueIndex = state.currentIndex,
                shuffleEnabled = state.shuffleEnabled,
                albumId = song.albumId?.toString(),
                artistId = song.artistId?.toString(),
                activityContext = activityContext,
            )

            // Backend is still handshaking for the track the user tapped —
            // show "about to play" UI, do NOT fake playing / progress.
            state.connectionPhase == ConnectionPhase.Connecting && pending != null ->
                NowPlayingUiState.Launching(
                    songTitle = pending.title.orEmpty(),
                    artist = pending.artist.orEmpty(),
                    albumName = pending.album.orEmpty(),
                    coverArtUrl = repository.resolveCoverUrl(pending.coverArt),
                    durationMs = pending.durationSec?.times(1_000L) ?: 0L,
                    hint = "Connecting to Spotify…",
                )

            // Backend refused / lost connection mid-launch — surface the
            // error with the track context so user knows what failed.
            state.connectionPhase == ConnectionPhase.Error && pending != null ->
                NowPlayingUiState.ConnectError(
                    songTitle = pending.title.orEmpty(),
                    artist = pending.artist.orEmpty(),
                    coverArtUrl = repository.resolveCoverUrl(pending.coverArt),
                    message = state.connectionErrorMessage
                        ?: "Couldn't start playback.",
                )

            else -> NowPlayingUiState.Idle
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingUiState.Idle)

    fun togglePlayPause() {
        val state = playbackManager.playbackState.value
        if (state.isPlaying) playbackManager.pause() else playbackManager.resume()
    }

    fun toggleShuffle() {
        playbackManager.toggleShuffle()
    }

    fun skipNext() {
        playbackManager.skipNext()
    }

    fun skipPrevious() {
        playbackManager.skipPrevious()
    }

    fun seekTo(fraction: Float) {
        val durationMs = playbackManager.playbackState.value.duration
        if (durationMs > 0) {
            playbackManager.seekTo((fraction.coerceIn(0f, 1f) * durationMs).toLong())
        }
    }

    fun setRating(rating: Float) {
        val songId = currentSongId.value ?: return
        viewModelScope.launch {
            repository.setRating(songId, rating)
        }
    }

    fun toggleFavorite() {
        val songId = currentSongId.value ?: return
        val nextFavorite = !_isStarred.value
        viewModelScope.launch {
            repository.setFavorite(songId, nextFavorite).onSuccess {
                _isStarred.value = nextFavorite
            }
        }
    }

    fun saveCurrentNote(content: String) {
        if (content.isBlank()) return
        val track = playbackManager.playbackState.value.currentTrack ?: return
        viewModelScope.launch {
            repository.addNote(track, content)
        }
    }

    fun deleteNote(id: String) {
        viewModelScope.launch {
            repository.deleteNoteById(id)
        }
    }

    fun refreshDevices() {
        viewModelScope.launch {
            val providerId = repository.currentProviderId()
            val castState = castManager.castState.value
            _devicesState.value = _devicesState.value.copy(
                providerId = providerId,
                devices = fallbackDevices(providerId, castState),
                loading = true,
                errorMessage = null,
            )
            runCatching {
                when (providerId) {
                    MediaId.PROVIDER_SPOTIFY -> repository.listSpotifyDevices()
                    else -> emptyList()
                }
            }.onSuccess { spotifyDevices ->
                _devicesState.value = DevicesSheetState(
                    providerId = providerId,
                    devices = buildDevices(providerId, spotifyDevices, castState),
                    loading = false,
                )
            }.onFailure { error ->
                _devicesState.value = DevicesSheetState(
                    providerId = providerId,
                    devices = fallbackDevices(providerId, castState),
                    loading = false,
                    errorMessage = error.message ?: "Couldn't load devices.",
                )
            }
        }
    }

    fun selectDevice(device: YoinDevice) {
        if (!device.isSelectable) return
        viewModelScope.launch {
            _devicesState.value = _devicesState.value.copy(busyDeviceId = device.id)
            runCatching {
                when (device) {
                    is YoinDevice.SpotifyConnect ->
                        repository.transferSpotifyPlayback(device.id)
                    is YoinDevice.LocalPlayback,
                    is YoinDevice.Chromecast -> Unit
                }
            }.onFailure { error ->
                _devicesState.value = _devicesState.value.copy(
                    busyDeviceId = null,
                    errorMessage = error.message ?: "Couldn't switch devices.",
                )
            }
            refreshDevices()
        }
    }

    // ── Add-to-playlist (long-press on ❤️) ─────────────────────────────
    //
    // The sheet has a single entry point: user long-presses the heart, we
    // snapshot the current song id into [addToPlaylistTarget], the
    // composable observes the non-null value and opens the sheet. When the
    // user picks a playlist or cancels, we null the target again.
    //
    // [writablePlaylists] refetches whenever the target changes from null
    // to non-null — the sheet always shows fresh data even if the user
    // created a playlist elsewhere in the same session.

    private val _addToPlaylistTarget = MutableStateFlow<List<MediaId>?>(null)
    val addToPlaylistTarget: StateFlow<List<MediaId>?> = _addToPlaylistTarget.asStateFlow()

    private val _addToPlaylistMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-shot confirmations / errors for the shell SnackbarHost to surface. */
    val addToPlaylistMessages: SharedFlow<String> = _addToPlaylistMessages.asSharedFlow()

    val writablePlaylists: StateFlow<List<AddToPlaylistRow>> = _addToPlaylistTarget
        .flatMapLatest { target ->
            if (target == null) flowOf(emptyList())
            else flowOf(fetchWritablePlaylists())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private suspend fun fetchWritablePlaylists(): List<AddToPlaylistRow> =
        runCatching { repository.getPlaylists() }
            .getOrDefault(emptyList())
            .filter { it.canWrite }
            .map { playlist ->
                AddToPlaylistRow(
                    id = playlist.id,
                    name = playlist.name,
                    songCount = playlist.songCount,
                    coverArtUrl = repository.resolveCoverUrl(playlist.coverArt),
                )
            }

    fun requestAddTracksToPlaylist(trackIds: List<MediaId>) {
        val distinctTargets = trackIds.distinct()
        if (distinctTargets.isEmpty()) return
        _addToPlaylistTarget.value = distinctTargets
    }

    fun requestAddCurrentToPlaylist() {
        val songId = currentSongId.value ?: return
        requestAddTracksToPlaylist(listOf(songId))
    }

    fun dismissAddToPlaylistSheet() {
        _addToPlaylistTarget.value = null
    }

    fun addTargetsToExistingPlaylist(playlistId: MediaId) {
        val targets = _addToPlaylistTarget.value ?: return
        val playlistName = writablePlaylists.value.firstOrNull { it.id == playlistId }?.name
            ?: "playlist"
        _addToPlaylistTarget.value = null
        viewModelScope.launch {
            repository.addTracksToPlaylist(playlistId, targets)
                .onSuccess {
                    onPlaylistMutated()
                    _addToPlaylistMessages.tryEmit("Added to $playlistName")
                }
                .onFailure {
                    _addToPlaylistMessages.tryEmit(
                        it.message ?: "Couldn't add to $playlistName",
                    )
                }
        }
    }

    fun createPlaylistAndAddTargets(name: String) {
        val targets = _addToPlaylistTarget.value ?: return
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            _addToPlaylistTarget.value = null
            return
        }
        _addToPlaylistTarget.value = null
        viewModelScope.launch {
            repository.createPlaylist(trimmedName)
                .onFailure {
                    _addToPlaylistMessages.tryEmit(
                        it.message ?: "Couldn't create \"$trimmedName\"",
                    )
                }
                .onSuccess { playlist ->
                    // Best effort — even if addTracks fails the empty playlist
                    // still exists, which matches user intent better than
                    // silently rolling back the create.
                    repository.addTracksToPlaylist(playlist.id, targets)
                        .onSuccess {
                            onPlaylistMutated()
                            _addToPlaylistMessages.tryEmit("Added to $trimmedName")
                        }
                        .onFailure {
                            onPlaylistMutated()
                            _addToPlaylistMessages.tryEmit(
                                it.message ?: "Created $trimmedName but couldn't add tracks",
                            )
                        }
                }
        }
    }

    fun skipToQueueItem(index: Int) {
        playbackManager.skipToQueueItem(index)
    }

    fun setDetailMode(mode: NowPlayingDetailMode) {
        _detailMode.value = mode
    }

    fun setDetailPage(page: NowPlayingDetailPage) {
        _detailPage.value = page
    }

    // Compact pager AND fullscreen pager both hit `onAboutOpened` when
    // their LaunchedEffects fire during the same frame — without an
    // in-flight guard we issue two concurrent Gemini calls, both see an
    // empty cache, and both write a canonical row set. Tracking the
    // active Job lets us short-circuit the second caller.
    private var canonicalFetchJob: Job? = null

    /**
     * First-time hook for About. Call when the user lands on the About tab
     * (compact or fullscreen). No-op when canonical rows are already
     * cached OR a canonical fetch is already in flight; otherwise issues
     * a single Grounded Gemini fetch.
     */
    fun onAboutOpened() {
        if (canonicalFetchJob?.isActive == true) return
        canonicalFetchJob = viewModelScope.launch { fetchCanonicalAbout(retry = false) }
    }

    /**
     * Explicit user-initiated retry. Overwrites existing canonical rows.
     * Cancels any pending passive fetch first — the user is asking for a
     * fresh answer, they shouldn't race an already-stale call.
     */
    fun retryFetchSongInfo() {
        canonicalFetchJob?.cancel()
        canonicalFetchJob = viewModelScope.launch { fetchCanonicalAbout(retry = true) }
    }

    private suspend fun fetchCanonicalAbout(retry: Boolean) {
        val song = playbackManager.playbackState.value.currentTrack ?: return
        val title = song.title.orEmpty()
        val artist = song.artist.orEmpty()
        val album = song.album.orEmpty()
        if (title.isBlank() || artist.isBlank()) return

        _aboutLoading.value = true
        _aboutError.value = null
        try {
            when (
                val result = repository.ensureCanonicalAbout(
                    title = title,
                    artist = artist,
                    album = album,
                    retry = retry,
                )
            ) {
                is YoinRepository.AboutLoadResult.Success -> _aboutError.value = null
                YoinRepository.AboutLoadResult.ApiKeyMissing -> {
                    _aboutError.value = AboutUiState.ApiKeyMissing
                }
                is YoinRepository.AboutLoadResult.Error -> {
                    _aboutError.value = AboutUiState.Error(result.message)
                }
            }
        } finally {
            _aboutLoading.value = false
        }
    }

    /** Called by the Ask Gemini bar when it gains text-field focus. */
    fun onAskBarFocused() {
        if (_askState.value !is AskBarState.Loading) {
            _askState.value = AskBarState.Focused
        }
    }

    fun dismissAskError() {
        if (_askState.value is AskBarState.Error) {
            _askState.value = AskBarState.Idle
        }
    }

    /**
     * User dismissed the keyboard without submitting (swipe-down, back
     * button, tapping outside the IME). Contract at the call site: only
     * fire when IME has actually transitioned from visible to hidden, so
     * the initial compose when Focused hasn't opened the IME yet doesn't
     * bounce state straight back to Idle.
     */
    fun onAskBarCollapseRequested() {
        if (_askState.value is AskBarState.Focused) {
            _askState.value = AskBarState.Idle
        }
    }

    /**
     * Submit a free-form Ask Gemini question for the current song. On
     * success, the observed About flow will emit the new row automatically;
     * this method only owns the transient [AskBarState] transitions.
     */
    fun askQuestion(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        val song = playbackManager.playbackState.value.currentTrack ?: return

        _askState.value = AskBarState.Loading
        viewModelScope.launch {
            val result = repository.askAboutSong(
                title = song.title.orEmpty(),
                artist = song.artist.orEmpty(),
                album = song.album.orEmpty(),
                question = trimmed,
            )
            _askState.value = when (result) {
                is YoinRepository.AskAboutResult.Success -> AskBarState.Idle
                YoinRepository.AskAboutResult.ApiKeyMissing ->
                    AskBarState.Error("Gemini API key missing")
                is YoinRepository.AskAboutResult.Error ->
                    AskBarState.Error(result.message)
            }
        }
    }

    private suspend fun loadLyrics(songId: MediaId, title: String?, artist: String?) {
        // 调用方（collectLatest 入口）已经把 _lyrics 清空 + loading=true。这里只在
        // 实际完成 / 失败时把 loading 关掉。若被 collectLatest 取消，让
        // CancellationException 透传，并且**不**碰 loading —— 紧接着新的 handler
        // 会把 loading 再次设成 true，避免中间闪一下 "No lyrics available"。
        try {
            _lyrics.value = when (val lyrics = repository.getLyrics(songId, title, artist)) {
                null -> emptyList()
                is SourceLyrics.Synced -> lyrics.lines.map { syncedLine ->
                    LyricLine(
                        startMs = syncedLine.startMs,
                        text = syncedLine.text,
                    )
                }
                is SourceLyrics.Unsynced -> lyrics.text.lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .map { line -> LyricLine(startMs = null, text = line) }
                    .toList()
            }
            _lyricsLoading.value = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            _lyrics.value = emptyList()
            _lyricsLoading.value = false
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NowPlayingViewModel(
                playbackManager = container.playbackManager,
                repository = container.repository,
                castManager = container.castManager,
                onPlaylistMutated = container::notifyPlaylistMutation,
            ) as T
    }
}

data class DevicesSheetState(
    val providerId: String? = null,
    val devices: List<YoinDevice> = emptyList(),
    val loading: Boolean = false,
    val busyDeviceId: String? = null,
    val errorMessage: String? = null,
)

private fun buildDevices(
    providerId: String?,
    spotifyDevices: List<YoinDevice.SpotifyConnect>,
    castState: CastState,
): List<YoinDevice> = when (providerId) {
    MediaId.PROVIDER_SPOTIFY -> spotifyDevices
    else -> fallbackDevices(providerId, castState)
}

private fun fallbackDevices(
    providerId: String?,
    castState: CastState,
): List<YoinDevice> = when (providerId) {
    MediaId.PROVIDER_SUBSONIC, MediaId.PROVIDER_LOCAL, null -> buildList {
        add(
            YoinDevice.LocalPlayback(
                isActive = castState !is CastState.Connected,
                isSelectable = false,
                statusText = if (castState is CastState.Connected) {
                    "Switch back from the Cast pill"
                } else {
                    null
                },
            ),
        )
        when (castState) {
            is CastState.Connected -> add(
                YoinDevice.Chromecast(
                    id = "cast-connected",
                    name = castState.deviceName,
                    isActive = true,
                    statusText = "Connected through Cast",
                ),
            )
            CastState.Available -> add(
                YoinDevice.Chromecast(
                    id = "cast-available",
                    name = "Chromecast",
                    isActive = false,
                    statusText = "Use the Cast pill to choose a device",
                ),
            )
            CastState.NotAvailable -> Unit
        }
    }
    else -> emptyList()
}
