package com.gpo.yoin.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.PlaylistItemRef
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.model.comment
import com.gpo.yoin.data.model.duration
import com.gpo.yoin.data.model.entry
import com.gpo.yoin.data.model.isPublic
import com.gpo.yoin.data.repository.YoinRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaylistDetailViewModel(
    private val playlistId: String,
    private val repository: YoinRepository,
    private val onPlaylistMutated: () -> Unit = {},
) : ViewModel() {

    private val _uiState = MutableStateFlow<PlaylistDetailUiState>(PlaylistDetailUiState.Loading)
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-shot toasts (rename/delete/remove result). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /**
     * Fires after a successful `deletePlaylist`. UI pops the detail route
     * back to the Library / Home surface it came from.
     */
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    private var playlistSongs: List<Track> = emptyList()
    // Spotify snapshot for concurrency on subsequent mutations. Refreshed on
    // every [loadPlaylist]; ignored by Subsonic (always null there).
    private var snapshotId: String? = null

    init {
        loadPlaylist()
    }

    fun getPlaylistSongs(): List<Track> = playlistSongs

    fun retry() {
        _uiState.value = PlaylistDetailUiState.Loading
        loadPlaylist()
    }

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.renamePlaylist(MediaId.parse(playlistId), trimmed)
                .onSuccess {
                    onPlaylistMutated()
                    _messages.tryEmit("Renamed")
                    loadPlaylist() // refresh name + snapshot
                }
                .onFailure {
                    _messages.tryEmit(it.message ?: "Couldn't rename playlist")
                }
        }
    }

    fun delete() {
        viewModelScope.launch {
            repository.deletePlaylist(MediaId.parse(playlistId))
                .onSuccess {
                    onPlaylistMutated()
                    _messages.tryEmit("Playlist deleted")
                    _deleted.tryEmit(Unit)
                }
                .onFailure {
                    _messages.tryEmit(it.message ?: "Couldn't delete playlist")
                }
        }
    }

    fun removeTrack(position: Int, trackId: String) {
        val parsedTrackId = runCatching { MediaId.parse(trackId) }.getOrNull() ?: return
        viewModelScope.launch {
            repository.removeTracksFromPlaylist(
                playlistId = MediaId.parse(playlistId),
                items = listOf(PlaylistItemRef(trackId = parsedTrackId, position = position)),
                snapshotId = snapshotId,
            )
                .onSuccess { newSnapshot ->
                    // Refresh to pick up the new snapshot + re-indexed positions
                    // rather than patching locally — avoids index drift with
                    // concurrent server-side edits.
                    onPlaylistMutated()
                    snapshotId = newSnapshot
                    loadPlaylist()
                }
                .onFailure {
                    _messages.tryEmit(it.message ?: "Couldn't remove track")
                }
        }
    }

    private fun loadPlaylist() {
        viewModelScope.launch {
            try {
                val playlist = repository.getPlaylist(MediaId.parse(playlistId))
                if (playlist == null) {
                    _uiState.value = PlaylistDetailUiState.Error("Playlist not found")
                    return@launch
                }
                playlistSongs = playlist.entry
                snapshotId = playlist.snapshotId
                val heroSong = playlist.entry.firstOrNull()
                val heroCoverRef = heroSong?.coverArt ?: heroSong?.albumId?.let { CoverRef.SourceRelative(it.rawId) }
                _uiState.value = PlaylistDetailUiState.Content(
                    playlistId = playlist.id.toString(),
                    playlistName = playlist.name,
                    owner = playlist.owner.orEmpty(),
                    comment = playlist.comment,
                    isPublic = playlist.isPublic,
                    songCount = playlist.songCount,
                    totalDuration = playlist.duration,
                    coverArtUrl = heroCoverRef?.let { repository.resolveCoverUrl(it) },
                    canWrite = playlist.canWrite,
                    songs = playlist.entry.mapIndexed { index, song ->
                        PlaylistSong(
                            id = song.id.toString(),
                            title = song.title.orEmpty(),
                            artist = song.artist.orEmpty(),
                            album = song.album.orEmpty(),
                            duration = song.duration,
                            coverArtUrl = (song.coverArt
                                ?: song.albumId?.let { albumId -> CoverRef.SourceRelative(albumId.rawId) })?.let {
                                repository.resolveCoverUrl(it, size = 320)
                            },
                            position = index,
                        )
                    },
                )
            } catch (e: Exception) {
                _uiState.value = PlaylistDetailUiState.Error(
                    e.message ?: "Failed to load playlist",
                )
            }
        }
    }

    class Factory(
        private val playlistId: String,
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlaylistDetailViewModel(
                playlistId = playlistId,
                repository = container.repository,
                onPlaylistMutated = container::notifyPlaylistMutation,
            ) as T
    }
}
