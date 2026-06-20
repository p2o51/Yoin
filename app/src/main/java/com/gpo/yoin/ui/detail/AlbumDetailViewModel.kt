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
import kotlinx.coroutines.flow.collectLatest
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
        observeFavoriteOverrides()
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
                albumSongs = album.tracks.applyFavoriteOverrides(repository.favoriteOverrides.value)
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
                    songs = albumSongs.map { song -> song.toAlbumSong(album.artist) },
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

                // 单曲均分 + 专辑级 last-play 是「算出来的」信号：本地 ratings
                // 取均值，play_history 各单曲最近播放取 MAX。加载后算一次并 merge
                // 回 Content（本页用户评分走 album_ratings，不影响这里的均分）。
                launch {
                    val ratings = repository.getRatings(albumSongs.map(Track::id))
                    val ratedValues = albumSongs.mapNotNull {
                        ratings[it.id]?.rating?.takeIf { r -> r > 0f }
                    }
                    val avg = ratedValues.takeIf { it.isNotEmpty() }?.average()?.toFloat()
                    val lastPlayed = albumSongs
                        .mapNotNull {
                            runCatching { repository.getMostRecentPlay(it.id)?.playedAt }.getOrNull()
                        }
                        .maxOrNull()
                    val cur = _uiState.value as? AlbumDetailUiState.Content ?: return@launch
                    _uiState.value = cur.copy(
                        averageTrackRating = avg,
                        ratedTrackCount = ratedValues.size,
                        lastPlayedAt = lastPlayed,
                    )
                }
            } catch (e: Exception) {
                _uiState.value = AlbumDetailUiState.Error(
                    e.message ?: "Failed to load album",
                )
            }
        }
    }

    fun toggleStar(songId: String) {
        val track = albumSongs.find { it.id.toString() == songId } ?: return
        viewModelScope.launch {
            repository.setFavorite(track, favorite = !track.isStarred)
                .onSuccess {
                    applyFavoriteOverrides(repository.favoriteOverrides.value)
                }
        }
    }

    private fun observeFavoriteOverrides() {
        viewModelScope.launch {
            repository.favoriteOverrides.collectLatest { overrides ->
                applyFavoriteOverrides(overrides)
            }
        }
    }

    private fun applyFavoriteOverrides(overrides: Map<MediaId, Boolean>) {
        if (overrides.isEmpty()) return
        albumSongs = albumSongs.applyFavoriteOverrides(overrides)

        val current = _uiState.value as? AlbumDetailUiState.Content ?: return
        _uiState.value = current.copy(
            songs = current.songs.map { song ->
                val id = MediaId.parseOrNull(song.id)
                val isStarred = id?.let(overrides::get) ?: return@map song
                song.copy(isStarred = isStarred)
            },
        )
    }

    private fun List<Track>.applyFavoriteOverrides(
        overrides: Map<MediaId, Boolean>,
    ): List<Track> = map { track ->
        overrides[track.id]?.let { isStarred -> track.copy(isStarred = isStarred) } ?: track
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

private fun Track.toAlbumSong(albumArtist: String?): AlbumSong = AlbumSong(
    id = id.toString(),
    title = title.orEmpty(),
    artist = artist.orEmpty(),
    trackNumber = trackNumber,
    duration = durationSec,
    isStarred = isStarred,
    featArtist = artist?.takeIf {
        it.isNotBlank() && !it.equals(albumArtist, ignoreCase = true)
    },
)

private fun String.toProviderLabel(): String = when (this) {
    MediaId.PROVIDER_SPOTIFY -> "Spotify"
    MediaId.PROVIDER_SUBSONIC -> "Subsonic"
    MediaId.PROVIDER_LOCAL -> "Local"
    else -> replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
