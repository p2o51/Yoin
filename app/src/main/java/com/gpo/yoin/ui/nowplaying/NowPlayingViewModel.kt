package com.gpo.yoin.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.Lyrics as SourceLyrics
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.player.ConnectionPhase
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.ui.component.AddToPlaylistRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val onPlaylistMutated: () -> Unit = {},
) : ViewModel() {

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    private val _isStarred = MutableStateFlow(false)
    private val _songInfoState = MutableStateFlow<SongInfoUiState>(SongInfoUiState.Idle)
    val songInfoState: StateFlow<SongInfoUiState> = _songInfoState.asStateFlow()

    private val currentSongId: StateFlow<MediaId?> = playbackManager.playbackState
        .map { it.currentTrack?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            currentSongId.collect { songId ->
                if (songId != null) {
                    // Pull title/artist from the current track — Provider 兜底需要它们
                    // 搜索（非 Subsonic 路径），Subsonic 路径只用 songId、忽略这两个。
                    val track = playbackManager.playbackState.value.currentTrack
                    loadLyrics(songId, track?.title, track?.artist)
                    loadSongInfo(songId)
                    _isStarred.value = track?.isStarred == true
                } else {
                    _lyrics.value = emptyList()
                    _isStarred.value = false
                    _songInfoState.value = SongInfoUiState.Idle
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

    val uiState: StateFlow<NowPlayingUiState> = combine(
        playbackManager.playbackState,
        _lyrics,
        ratingFlow,
        _isStarred,
    ) { state, lyrics, rating, isStarred ->
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
                queue = state.queue.map { queueSong ->
                    QueueItem(
                        songId = queueSong.id.toString(),
                        title = queueSong.title.orEmpty(),
                        artist = queueSong.artist.orEmpty(),
                        coverArtUrl = repository.resolveCoverUrl(queueSong.coverArt),
                    )
                },
                currentQueueIndex = state.currentIndex,
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

    fun retryFetchSongInfo() {
        val songId = currentSongId.value ?: return
        viewModelScope.launch { loadSongInfo(songId) }
    }

    private suspend fun loadSongInfo(songId: MediaId) {
        _songInfoState.value = SongInfoUiState.Loading
        val song = playbackManager.playbackState.value.currentTrack
        when (
            val result = repository.loadSongInfo(
                songId = songId,
                title = song?.title.orEmpty(),
                artist = song?.artist.orEmpty(),
                album = song?.album.orEmpty(),
            )
        ) {
            is YoinRepository.SongInfoLoadResult.Success -> {
                _songInfoState.value = result.songInfo.toSuccessState()
            }

            YoinRepository.SongInfoLoadResult.ApiKeyMissing -> {
                _songInfoState.value = SongInfoUiState.ApiKeyMissing
            }

            is YoinRepository.SongInfoLoadResult.Error -> {
                _songInfoState.value = SongInfoUiState.Error(result.message)
            }
        }
    }

    private fun com.gpo.yoin.data.local.SongInfo.toSuccessState() = SongInfoUiState.Success(
        creationTime = creationTime,
        creationLocation = creationLocation,
        lyricist = lyricist,
        composer = composer,
        producer = producer,
        review = review,
    )

    private suspend fun loadLyrics(songId: MediaId, title: String?, artist: String?) {
        _lyrics.value = try {
            when (val lyrics = repository.getLyrics(songId, title, artist)) {
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
        } catch (_: Exception) {
            emptyList()
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NowPlayingViewModel(
                playbackManager = container.playbackManager,
                repository = container.repository,
                onPlaylistMutated = container::notifyPlaylistMutation,
            ) as T
    }
}
