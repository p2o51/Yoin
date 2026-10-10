package com.gpo.yoin.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.perf.YoinPerf
import com.gpo.yoin.ui.common.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // Debug-only: `detail.content` marks the first Content only. Declared before
    // init, whose load can publish a mem-cached Content synchronously.
    private var perfContentMarked = false

    /** The loaded artist, kept so the personal layer can be re-read on resume. */
    private var loadedArtist: ArtistDetail? = null

    /**
     * The "Most Played" rows as playable tracks (same order), rebuilt from the
     * play-history rows each time the personal layer loads.
     */
    private var mostPlayedTracks: List<Track> = emptyList()

    init {
        loadArtist()
        observeFollow()
    }

    fun retry() {
        _uiState.value = ArtistDetailUiState.Loading
        loadArtist()
    }

    /** Debug-only `detail.content`: the first Content this VM publishes (docs/perf/yoinperf-logging.md). */
    private fun markPerfContent(resolvedId: String) {
        if (!YoinPerf.enabled || perfContentMarked) return
        perfContentMarked = true
        YoinPerf.mark(
            "detail.content",
            "kind" to "artist",
            "id" to artistId,
            "resolved" to resolvedId.takeIf { it != artistId }
        )
    }

    private fun loadArtist() {
        viewModelScope.launch {
            try {
                val artist = repository.getArtist(MediaId.parse(artistId))
                if (artist == null) {
                    _uiState.value = ArtistDetailUiState.Error(UiText.Res(R.string.detail_artist_error_not_found))
                    return@launch
                }
                loadedArtist = artist
                // The visit row feeds Home's activity and the widgets, not this page:
                // Content doesn't wait on the Room insert.
                launch { repository.recordArtistVisit(artist) }
                // Preload the newest releases so tapping a row opens instantly.
                artist.albums.take(6).forEach { album -> repository.prefetchAlbum(album.id) }
                _uiState.value = ArtistDetailUiState.Content(
                    artistId = artist.id.toString(),
                    artistName = artist.name,
                    heroCoverArtUrl = artist.coverArt?.let { repository.resolveCoverUrl(it) },
                    isStarred = artist.isStarred,
                    albums = artist.albums.map { album ->
                        ArtistAlbum(
                            id = album.id.toString(),
                            name = album.name,
                            coverArtUrl = album.coverArt?.let { repository.resolveCoverUrl(it) },
                            year = album.year,
                            songCount = album.songCount,
                            releaseType = album.releaseType,
                        )
                    },
                )
                markPerfContent(artist.id.toString())
                loadPersonal(artist)
            } catch (e: Exception) {
                _uiState.value = ArtistDetailUiState.Error(
                    e.toDetailMessage(R.string.detail_artist_error_load),
                )
            }
        }
    }

    /**
     * Re-read the personal layer (ratings + listening) — the Activity calls this
     * on resume, so a rating given on an album page or a play made meanwhile
     * shows up when the user comes back.
     */
    fun refreshPersonal() {
        val artist = loadedArtist ?: return
        viewModelScope.launch { loadPersonal(artist) }
    }

    /**
     * The user's own layer, merged in after the page paints: album ratings and
     * local listening (plays, last play, most-played songs). Best-effort — a
     * failed read leaves the page as the provider data alone.
     */
    private suspend fun loadPersonal(artist: ArtistDetail) {
        val albumIds = artist.albums.map { it.id }
        val ratings = runCatching { repository.getAlbumRatings(albumIds) }.getOrDefault(emptyMap())
        val listening = runCatching {
            repository.getArtistListening(artist.id, artist.name, albumIds)
        }.getOrNull()
        mostPlayedTracks = listening?.topSongs.orEmpty().map { row ->
            Track(
                id = MediaId(row.provider, row.songId),
                title = row.title,
                artist = artist.name,
                artistId = artist.id,
                album = row.album,
                albumId = row.albumId.takeIf { it.isNotBlank() }?.let { MediaId(row.provider, it) },
                coverArt = CoverRef.fromStorageKey(row.coverArtId),
                durationSec = (row.durationMs / 1000).toInt().takeIf { it > 0 },
                trackNumber = null,
                year = null,
                genre = null,
                userRating = null,
            )
        }
        val summary = listening?.let {
            ArtistListeningSummary(
                playCount = it.playCount,
                lastPlayedAt = it.lastPlayedAt,
                mostPlayed = it.topSongs.map { row ->
                    ArtistPlayedSong(
                        id = MediaId(row.provider, row.songId).toString(),
                        title = row.title,
                        album = row.album,
                        coverArtUrl = CoverRef.fromStorageKey(row.coverArtId)
                            ?.let { ref -> repository.resolveCoverUrl(ref) },
                        durationSec = (row.durationMs / 1000).toInt().takeIf { secs -> secs > 0 },
                        playCount = row.playCount,
                    )
                },
            )
        } ?: ArtistListeningSummary(playCount = 0, lastPlayedAt = null, mostPlayed = emptyList())
        (_uiState.value as? ArtistDetailUiState.Content)?.let { current ->
            _uiState.value = current.copy(
                albums = current.albums.map { album ->
                    album.copy(userRating = ratings[MediaId.parse(album.id).rawId])
                },
                listening = summary,
            )
        }
    }

    /** The "Most Played" rows as a play queue, in row order. */
    fun getMostPlayedTracks(): List<Track> = mostPlayedTracks

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
