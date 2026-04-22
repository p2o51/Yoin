package com.gpo.yoin.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDetailViewModel(
    private val albumId: String,
    private val repository: YoinRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AlbumDetailUiState>(AlbumDetailUiState.Loading)
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    private var albumSongs: List<Track> = emptyList()
    private var loadedAlbum: Album? = null
    private val albumTrackIds = MutableStateFlow<List<MediaId>>(emptyList())
    private val _expandedSongId = MutableStateFlow<String?>(null)
    val expandedSongId: StateFlow<String?> = _expandedSongId.asStateFlow()

    val notedSongIds: StateFlow<Set<String>> = albumTrackIds
        .flatMapLatest(repository::observeTracksWithNotes)
        .map { ids -> ids.mapTo(linkedSetOf(), MediaId::toString) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val expandedNoteBundle: StateFlow<AlbumExpandedNoteBundle?> = _expandedSongId
        .flatMapLatest { songId ->
            val track = albumSongs.firstOrNull { it.id.toString() == songId }
                ?: return@flatMapLatest flowOf(null)
            combine(
                repository.observeNotes(track.id),
                repository.observeCrossProviderNotes(
                    trackId = track.id,
                    title = track.title.orEmpty(),
                    artist = track.artist.orEmpty(),
                ),
            ) { primary, crossProvider ->
                AlbumExpandedNoteBundle(
                    songId = track.id.toString(),
                    primaryNotes = primary
                        .filter { it.content.isNotBlank() }
                        .map { AlbumPrimaryNote(id = it.id, content = it.content, createdAt = it.createdAt) },
                    crossProviderNotes = crossProvider
                        .mapNotNull { note ->
                            note.content.takeIf(String::isNotBlank)?.let { content ->
                                AlbumCrossProviderNote(
                                    providerLabel = note.provider.toProviderLabel(),
                                    content = content,
                                )
                            }
                        },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        loadAlbum()
    }

    fun getAlbumSongs(): List<Track> = albumSongs

    fun retry() {
        _uiState.value = AlbumDetailUiState.Loading
        loadAlbum()
    }

    private fun loadAlbum() {
        viewModelScope.launch {
            try {
                val parsedAlbumId = MediaId.parse(albumId)
                val album = repository.getAlbum(parsedAlbumId)
                if (album == null) {
                    _uiState.value = AlbumDetailUiState.Error("Album not found")
                    return@launch
                }
                loadedAlbum = album
                albumSongs = album.tracks
                albumTrackIds.value = albumSongs.map(Track::id)
                repository.recordAlbumVisit(album)
                _uiState.value = AlbumDetailUiState.Content(
                    albumId = album.id.toString(),
                    albumName = album.name,
                    artistName = album.artist.orEmpty(),
                    artistId = album.artistId?.toString(),
                    coverArtId = CoverRef.toStorageKey(album.coverArt),
                    coverArtUrl = album.coverArt?.let { repository.resolveCoverUrl(it) },
                    year = album.year,
                    songCount = album.songCount,
                    totalDuration = album.durationSec,
                    songs = album.tracks.map { song ->
                        AlbumSong(
                            id = song.id.toString(),
                            title = song.title.orEmpty(),
                            artist = song.artist.orEmpty(),
                            trackNumber = song.trackNumber,
                            duration = song.durationSec,
                            isStarred = song.isStarred,
                        )
                    },
                )

                // 观察 album_ratings，把持久化状态 merge 回 Content —— 用户在
                // 别处（Memory / 以后的 NeoDB 拉取）改了评分 / 评论时，打开
                // AlbumDetail 要看到最新值。pull-from-NeoDB 之后这条 flow 也会
                // 自动刷到新结果。
                launch {
                    repository.observeAlbumRating(parsedAlbumId).collect { rating ->
                        val current = _uiState.value as? AlbumDetailUiState.Content
                            ?: return@collect
                        // 只在 review 没有未保存编辑时同步下游 review；
                        // 保护用户当前正在输入的草稿不被覆盖。
                        val nextReview = if (current.reviewHasUnsavedEdits) {
                            current.userReview
                        } else {
                            rating?.review.orEmpty()
                        }
                        _uiState.value = current.copy(
                            userRating = rating?.rating?.takeIf { it > 0f },
                            userReview = nextReview,
                            reviewHasUnsavedEdits = current.reviewHasUnsavedEdits &&
                                nextReview != rating?.review.orEmpty(),
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = AlbumDetailUiState.Error(
                    e.message ?: "Failed to load album",
                )
            }
        }
    }

    fun toggleStar(songId: String) {
        val current = _uiState.value as? AlbumDetailUiState.Content ?: return
        val song = current.songs.find { it.id == songId } ?: return
        viewModelScope.launch {
            try {
                repository.setFavorite(MediaId.parse(songId), favorite = !song.isStarred)
                _uiState.value = current.copy(
                    songs = current.songs.map {
                        if (it.id == songId) it.copy(isStarred = !it.isStarred) else it
                    },
                )
            } catch (_: Exception) {
                // Silently ignore star/unstar failures
            }
        }
    }

    fun toggleExpandedSong(songId: String) {
        _expandedSongId.value = if (_expandedSongId.value == songId) null else songId
    }

    /**
     * 拖动 slider 结束（onValueChangeFinished）时调用。整数步进 0..10；
     * 0 当「撤销评分」落库（[YoinRepository.setAlbumRating] 接受 0）。
     */
    fun setUserRating(rating: Float) {
        val album = loadedAlbum ?: return
        viewModelScope.launch {
            repository.setAlbumRating(album, rating)
        }
    }

    /** 输入 review 时调用 —— 只更新本地 UiState，不 upsert。 */
    fun onReviewDraftChange(text: String) {
        val current = _uiState.value as? AlbumDetailUiState.Content ?: return
        _uiState.value = current.copy(
            userReview = text,
            reviewHasUnsavedEdits = true,
        )
    }

    /** Save 按钮：把草稿落 Room（空串会走 delete-review 语义）。 */
    fun saveUserReview() {
        val album = loadedAlbum ?: return
        val current = _uiState.value as? AlbumDetailUiState.Content ?: return
        val draft = current.userReview
        viewModelScope.launch {
            repository.setAlbumReview(album, draft)
            _uiState.value = (_uiState.value as? AlbumDetailUiState.Content)
                ?.copy(reviewHasUnsavedEdits = false) ?: return@launch
        }
    }

    class Factory(
        private val albumId: String,
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AlbumDetailViewModel(albumId, container.repository) as T
    }
}

data class AlbumExpandedNoteBundle(
    val songId: String,
    val primaryNotes: List<AlbumPrimaryNote>,
    val crossProviderNotes: List<AlbumCrossProviderNote>,
)

data class AlbumPrimaryNote(
    val id: String,
    val content: String,
    val createdAt: Long,
)

data class AlbumCrossProviderNote(
    val providerLabel: String,
    val content: String,
)

private fun String.toProviderLabel(): String = when (this) {
    MediaId.PROVIDER_SPOTIFY -> "Spotify"
    MediaId.PROVIDER_SUBSONIC -> "Subsonic"
    MediaId.PROVIDER_LOCAL -> "Local"
    else -> replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
