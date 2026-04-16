package com.gpo.yoin.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.remote.GeminiService
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.data.repository.YoinRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModel(
    private val playbackManager: PlaybackManager,
    private val repository: YoinRepository,
    private val geminiService: GeminiService,
    private val database: YoinDatabase,
) : ViewModel() {

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    private val _isStarred = MutableStateFlow(false)
    private val _songInfoState = MutableStateFlow<SongInfoUiState>(SongInfoUiState.Idle)
    val songInfoState: StateFlow<SongInfoUiState> = _songInfoState.asStateFlow()

    // Track current song ID so we can reload lyrics / starred state on change
    private val currentSongId: StateFlow<String?> = playbackManager.playbackState
        .map { it.currentSong?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // When song changes, load lyrics and starred state
        viewModelScope.launch {
            currentSongId.collect { songId ->
                if (songId != null) {
                    loadLyrics(songId)
                    loadSongInfo(songId)
                    _isStarred.value =
                        playbackManager.playbackState.value.currentSong?.starred != null
                } else {
                    _lyrics.value = emptyList()
                    _isStarred.value = false
                    _songInfoState.value = SongInfoUiState.Idle
                }
            }
        }
    }

    // Rating flow from Room, keyed by current song
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
        val song = state.currentSong
        if (song == null) {
            NowPlayingUiState.Idle
        } else {
            NowPlayingUiState.Playing(
                songTitle = song.title.orEmpty(),
                artist = song.artist.orEmpty(),
                albumName = song.album.orEmpty(),
                coverArtUrl = song.coverArt?.let { repository.buildCoverArtUrl(it) },
                isPlaying = state.isPlaying,
                positionMs = state.position,
                durationMs = state.duration,
                bufferedMs = state.bufferedPosition,
                songId = song.id,
                rating = rating,
                isStarred = isStarred,
                lyrics = lyrics,
                queue = state.queue.map { queueSong ->
                    QueueItem(
                        songId = queueSong.id,
                        title = queueSong.title.orEmpty(),
                        artist = queueSong.artist.orEmpty(),
                        coverArtUrl = queueSong.coverArt?.let {
                            repository.buildCoverArtUrl(it)
                        },
                    )
                },
                currentQueueIndex = state.currentIndex,
            )
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

    /** @param fraction 0.0–1.0 mapped to current track duration. */
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
        val starred = _isStarred.value
        viewModelScope.launch {
            try {
                if (starred) {
                    repository.unstar(id = songId)
                } else {
                    repository.star(id = songId)
                }
                _isStarred.value = !starred
            } catch (_: Exception) {
                // Revert on failure — keep UI consistent
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

    private suspend fun loadSongInfo(songId: String) {
        _songInfoState.value = SongInfoUiState.Loading
        try {
            // Check Room cache first
            val cached = database.songInfoDao().getBySongId(songId)
            if (cached != null) {
                _songInfoState.value = cached.toSuccessState()
                return
            }
            // Check API key
            val config = database.geminiConfigDao().getConfig().first()
            val apiKey = config?.apiKey
            if (apiKey.isNullOrBlank()) {
                _songInfoState.value = SongInfoUiState.ApiKeyMissing
                return
            }
            // Fetch from Gemini
            val song = playbackManager.playbackState.value.currentSong
            val result = geminiService.generateSongInfo(
                apiKey = apiKey,
                title = song?.title.orEmpty(),
                artist = song?.artist.orEmpty(),
                album = song?.album.orEmpty(),
            )
            val songInfo = result.copy(songId = songId)
            database.songInfoDao().upsert(songInfo)
            _songInfoState.value = songInfo.toSuccessState()
        } catch (e: Exception) {
            _songInfoState.value = SongInfoUiState.Error(
                e.message ?: "Failed to load song info",
            )
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

    private suspend fun loadLyrics(songId: String) {
        try {
            val lyricsList = repository.getLyrics(songId)
            // Prefer synced lyrics, fall back to unsynced
            val structured = lyricsList?.structuredLyrics
                ?.firstOrNull { it.synced }
                ?: lyricsList?.structuredLyrics?.firstOrNull()
            _lyrics.value = structured?.line?.map { syncedLine ->
                LyricLine(
                    startMs = syncedLine.start,
                    text = syncedLine.value,
                )
            }.orEmpty()
        } catch (_: Exception) {
            _lyrics.value = emptyList()
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NowPlayingViewModel(
                playbackManager = container.playbackManager,
                repository = container.repository,
                geminiService = container.geminiService,
                database = container.database,
            ) as T
    }
}
