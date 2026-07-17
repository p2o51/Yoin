package com.gpo.yoin.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.ui.component.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ArtistDetailViewModel(
    private val artistId: String,
    private val repository: YoinRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ArtistDetailUiState>(ArtistDetailUiState.Loading)
    val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

    /**
     * In-flight load of the artist's most-popular tracks. A Deferred (not a plain
     * list) so Play can AWAIT it — tapping Play in the instant before the load
     * resolves must still play the top tracks, not silently fall back to the
     * whole discography.
     */
    private var topTracksDeferred: Deferred<List<Track>>? = null

    init {
        loadArtist()
        observeFollow()
    }

    fun retry() {
        _uiState.value = ArtistDetailUiState.Loading
        loadArtist()
    }

    private fun loadArtist() {
        viewModelScope.launch {
            try {
                val artist = repository.getArtist(MediaId.parse(artistId))
                if (artist == null) {
                    _uiState.value = ArtistDetailUiState.Error("Artist not found")
                    return@launch
                }
                repository.recordArtistVisit(artist)
                // Preload the first albums so tapping the carousel opens instantly.
                artist.albums.take(6).forEach { album -> repository.prefetchAlbum(album.id) }
                _uiState.value = ArtistDetailUiState.Content(
                    artistId = artist.id.toString(),
                    artistName = artist.name,
                    albumCount = artist.albumCount,
                    heroCoverArtUrl = artist.coverArt?.let { repository.resolveCoverUrl(it) },
                    isStarred = artist.isStarred,
                    albums = artist.albums.map { album ->
                        ArtistAlbum(
                            id = album.id.toString(),
                            name = album.name,
                            coverArtUrl = album.coverArt?.let { repository.resolveCoverUrl(it) },
                            year = album.year,
                            songCount = album.songCount,
                        )
                    },
                )
                loadSecondaryContent(artist.id)
            } catch (e: Exception) {
                _uiState.value = ArtistDetailUiState.Error(
                    e.toUserMessage("Couldn't load this artist."),
                )
            }
        }
    }

    /**
     * After the main content paints, load the artist's Popular tracks and merge
     * them in. Kept off the main load so it never blocks the hero/carousel.
     * Best-effort: a failure just leaves the section empty.
     */
    private fun loadSecondaryContent(artistId: MediaId) {
        val deferred = viewModelScope.async {
            runCatching { repository.getArtistTopTracks(artistId) }.getOrDefault(emptyList())
        }
        topTracksDeferred = deferred
        viewModelScope.launch {
            val top = deferred.await()
            val topRows = top.map { track ->
                ArtistTopTrack(
                    id = track.id.toString(),
                    title = track.title.orEmpty(),
                    artist = track.artist.orEmpty(),
                    coverArtUrl = track.coverArt?.let { repository.resolveCoverUrl(it) },
                    durationSec = track.durationSec,
                )
            }
            (_uiState.value as? ArtistDetailUiState.Content)?.let { current ->
                _uiState.value = current.copy(topTracks = topRows)
            }
        }
    }

    /**
     * The artist's Popular tracks, for default Play / a Popular row tap. Awaits
     * the in-flight load so an early Play uses the real list (or empty when the
     * artist genuinely has none / a non-Spotify source), never a stale snapshot.
     */
    suspend fun getTopTracks(): List<Track> = topTracksDeferred?.await() ?: emptyList()

    /**
     * Follow / unfollow the artist via the real follow endpoint (NOT setFavorite,
     * which is the saved-tracks like). Optimistic locally, reverted if the write
     * fails — the override observer can't revert (it bails when the override
     * clears on failure).
     */
    fun toggleFollow() {
        val current = _uiState.value as? ArtistDetailUiState.Content ?: return
        val id = MediaId.parseOrNull(artistId) ?: return
        val target = !current.isStarred
        _uiState.value = current.copy(isStarred = target)
        viewModelScope.launch {
            repository.setArtistFollowed(id, followed = target).onFailure {
                (_uiState.value as? ArtistDetailUiState.Content)?.let { c ->
                    _uiState.value = c.copy(isStarred = !target)
                }
            }
        }
    }

    /** Reflect follow state written elsewhere (or our own optimistic write). */
    private fun observeFollow() {
        viewModelScope.launch {
            val id = MediaId.parseOrNull(artistId) ?: return@launch
            repository.favoriteOverrides.collectLatest { overrides ->
                val starred = overrides[id] ?: return@collectLatest
                val current = _uiState.value as? ArtistDetailUiState.Content ?: return@collectLatest
                if (current.isStarred != starred) {
                    _uiState.value = current.copy(isStarred = starred)
                }
            }
        }
    }

    /**
     * Every track across the artist's albums, for the toolbar's Play / Shuffle.
     * Albums from the artist endpoint are summaries without tracks, so each is
     * loaded on demand here (a handful of quick queries on tap).
     */
    suspend fun getAllTracks(): List<Track> {
        val albums = (uiState.value as? ArtistDetailUiState.Content)?.albums ?: return emptyList()
        // Load albums concurrently (cap via the repo's own gating) so Play/Shuffle
        // doesn't stall on N serial network round-trips on a cold cache.
        return coroutineScope {
            albums.map { album ->
                async {
                    runCatching { repository.getAlbum(MediaId.parse(album.id))?.tracks }
                        .getOrNull()
                        .orEmpty()
                }
            }.awaitAll()
        }.flatten()
    }

    class Factory(
        private val artistId: String,
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ArtistDetailViewModel(artistId, container.repository) as T
    }
}
