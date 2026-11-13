package com.gpo.yoin.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.PlaylistItemRef
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.perf.YoinPerf
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.component.diagnosticReport
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.gpo.yoin.data.backup.PlaylistBackupRecord
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModel(
    private val playlistId: String,
    private val repository: YoinRepository,
    private val onPlaylistMutated: () -> Unit = {},
    // "Back up" (PlaylistBackupService.backUp): reads the playlist whole and saves a backup.
    private val backUpPlaylist: (suspend (MediaId) -> PlaylistBackupRecord)? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PlaylistDetailUiState>(PlaylistDetailUiState.Loading)
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    // Debug-only: `detail.content` marks the first Content only. Declared before
    // init, whose load can publish a mem-cached Content synchronously.
    private var perfContentMarked = false

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4)
    /** One-shot toasts (rename/delete/remove result). */
    val messages: SharedFlow<UiText> = _messages.asSharedFlow()

    /** A backup made: its snackbar carries View, which opens the backup ([backupId]). */
    data class BackedUp(val message: UiText, val backupId: String)

    private val _backedUp = MutableSharedFlow<BackedUp>(extraBufferCapacity = 1)
    val backedUp: SharedFlow<BackedUp> = _backedUp.asSharedFlow()

    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /**
     * Fires after a successful `deletePlaylist`. UI pops the detail route
     * back to the Library / Home surface it came from.
     */
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    private var playlistSongs: List<Track> = emptyList()
    private val playlistTrackIds = MutableStateFlow<List<MediaId>>(emptyList())
    // Spotify snapshot for concurrency on subsequent mutations. Refreshed on
    // every [loadPlaylist]; ignored by Subsonic (always null there).
    private var snapshotId: String? = null
    // The playlist's OWN cover ref (Spotify mosaic/custom art, Subsonic
    // playlist cover). Drives both the hero cover and the playback
    // ActivityContext — recording the first track's art in either place is
    // the bug where a playlist shows up wearing a song's cover.
    private var playlistCoverArt: CoverRef? = null

    val notedSongIds: StateFlow<Set<String>> = playlistTrackIds
        .flatMapLatest(repository::observeTracksWithNotes)
        .map { ids -> ids.mapTo(linkedSetOf(), MediaId::toString) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _backingUp = MutableStateFlow(false)

    /** A backup is being read; the menu row waits for it. */
    val backingUp: StateFlow<Boolean> = _backingUp.asStateFlow()

    /** Whether this page offers "Back up". */
    val canBackUp: Boolean get() = backUpPlaylist != null

    init {
        loadPlaylist()
    }

    /**
     * Saves the playlist as a new backup (docs/playlist-transfer.md §7) and
     * says on the snackbar how much of it came along.
     */
    fun backUp() {
        val backUp = backUpPlaylist ?: return
        if (_backingUp.value) return
        val id = MediaId.parseOrNull(playlistId) ?: return
        _backingUp.value = true
        viewModelScope.launch {
            val backup = try {
                backUp(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                _backingUp.value = false
            }
            if (backup == null) {
                _messages.tryEmit(UiText.Res(R.string.detail_playlist_backup_failed))
            } else {
                _backedUp.tryEmit(BackedUp(backupMessage(backup), backup.id))
            }
        }
    }

    fun getPlaylistSongs(): List<Track> = playlistSongs

    /**
     * Storage key of the playlist's own cover art (URL for Spotify, raw id for
     * Subsonic), for the playback [com.gpo.yoin.data.repository.ActivityContext].
     * Null when the playlist has no art of its own — callers fall back to a
     * track cover.
     */
    fun getPlaylistCoverArtKey(): String? = CoverRef.toStorageKey(playlistCoverArt)

    fun retry() {
        _uiState.value = PlaylistDetailUiState.Loading
        loadPlaylist()
    }

    /** Debug-only `detail.content`: the first Content this VM publishes (docs/perf/yoinperf-logging.md). */
    private fun markPerfContent(resolvedId: String) {
        if (!YoinPerf.enabled || perfContentMarked) return
        perfContentMarked = true
        YoinPerf.mark(
            "detail.content",
            "kind" to "playlist",
            "id" to playlistId,
            "resolved" to resolvedId.takeIf { it != playlistId }
        )
    }

    // Renames reach the service one after another, in the order they were
    // made. Each ends with the name the service then holds, which is what the
    // next one goes back to if it is refused.
    private var renaming: Deferred<String?>? = null

    // Renames made and not yet answered. While any is out, a read of the
    // playlist keeps the name on the page: it is the latest one asked for.
    private var renamesOut = 0

    /**
     * Rename: the page wears the new name at once and the service is told
     * after (as iOS's PlaylistActions.rename). If it refuses, the name it
     * still holds comes back and the page's snackbar says so.
     */
    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        fun shownName() = (_uiState.value as? PlaylistDetailUiState.Content)?.playlistName
        fun showName(shown: String) {
            val content = _uiState.value as? PlaylistDetailUiState.Content ?: return
            _uiState.value = content.copy(playlistName = shown)
        }
        val before = shownName()
        if (trimmed == before) return
        showName(trimmed)
        // With a rename still out, the name on the page is that one's, not the service's.
        val earlier = renaming?.takeIf { it.isActive }
        // The account the rename was made on. A queued one whose turn comes
        // after a switch is not sent: the same id on the new account is
        // another playlist.
        val profileId = repository.currentProfileId()
        renamesOut++
        // The page already wears the name, so the write is not dropped when
        // the page closes under it (as the song sheet's Favorite).
        renaming = viewModelScope.async(kotlinx.coroutines.NonCancellable) {
            val held = if (earlier != null) earlier.await() else before
            val result = if (repository.currentProfileId() != profileId) {
                Result.failure(IllegalStateException())
            } else {
                runCatching { repository.renamePlaylist(MediaId.parse(playlistId), trimmed) }
                    .getOrElse { Result.failure(it) }
            }
            renamesOut--
            result
                .fold(
                    onSuccess = {
                        onPlaylistMutated()
                        _messages.tryEmit(UiText.Res(R.string.detail_playlist_renamed))
                        loadPlaylist() // refresh name + snapshot
                        trimmed
                    },
                    onFailure = {
                        // Only while this name is still the one on the page: a
                        // later rename, or a read since, is not undone.
                        if (held != null && shownName() == trimmed) showName(held)
                        _messages.tryEmit(
                            it.message?.let(UiText::Raw) ?: UiText.Res(R.string.detail_playlist_error_rename),
                        )
                        held
                    },
                )
        }
    }

    /**
     * Delete waits for the service before the page leaves: the page's
     * snackbar leaves with it, so a refusal afterwards would have nowhere to
     * be said.
     */
    fun delete() {
        viewModelScope.launch {
            repository.deletePlaylist(MediaId.parse(playlistId))
                .onSuccess {
                    onPlaylistMutated()
                    _messages.tryEmit(UiText.Res(R.string.detail_playlist_deleted))
                    _deleted.tryEmit(Unit)
                }
                .onFailure {
                    _messages.tryEmit(it.message?.let(UiText::Raw) ?: UiText.Res(R.string.detail_playlist_error_delete))
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
                    _messages.tryEmit(it.message?.let(UiText::Raw) ?: UiText.Res(R.string.detail_playlist_error_remove))
                }
        }
    }

    private fun loadPlaylist() {
        viewModelScope.launch {
            try {
                repository.awaitActiveSource(DETAIL_SOURCE_WAIT_MS)
                val playlist = repository.getPlaylist(MediaId.parse(playlistId))
                if (playlist == null) {
                    _uiState.value = PlaylistDetailUiState.Error(UiText.Res(R.string.detail_playlist_error_not_found))
                    return@launch
                }
                playlistSongs = playlist.tracks
                playlistTrackIds.value = playlistSongs.map(Track::id)
                snapshotId = playlist.snapshotId
                playlistCoverArt = playlist.coverArt
                val heroSong = playlist.tracks.firstOrNull()
                val heroCoverRef = heroSong?.coverArt ?: heroSong?.albumId?.let { CoverRef.SourceRelative(it.rawId) }
                val songs = playlist.tracks.mapIndexed { index, song ->
                    PlaylistSong(
                        id = song.id.toString(),
                        title = song.title.orEmpty(),
                        artist = song.artist.orEmpty(),
                        album = song.album.orEmpty(),
                        duration = song.durationSec,
                        coverArtUrl = (song.coverArt
                            ?: song.albumId?.let { albumId -> CoverRef.SourceRelative(albumId.rawId) })?.let {
                            repository.resolveCoverUrl(it, size = ROW_COVER_SIZE)
                        },
                        position = index,
                    )
                }
                // The hero's sleeves: the rows' own album covers (same urls, so
                // no new request). The hero is resolved at the rows' size here
                // only to compare like for like.
                val heroCover = playlist.coverArt ?: heroCoverRef
                val sleeveCoverUrls = playlistSleeveCovers(
                    songCovers = songs.map(PlaylistSong::coverArtUrl),
                    heroCover = heroCover?.let { repository.resolveCoverUrl(it, size = ROW_COVER_SIZE) },
                    heroIsFirstTrackCover = playlist.coverArt == null && heroCoverRef != null,
                )
                _uiState.value = PlaylistDetailUiState.Content(
                    playlistId = playlist.id.toString(),
                    playlistName = if (renamesOut > 0) {
                        (_uiState.value as? PlaylistDetailUiState.Content)?.playlistName ?: playlist.name
                    } else {
                        playlist.name
                    },
                    owner = playlist.owner.orEmpty(),
                    comment = playlist.comment,
                    isPublic = null,
                    songCount = playlist.songCount,
                    totalDuration = playlist.durationSec,
                    // Hero shows the playlist's OWN cover (mosaic/custom art);
                    // a first-track ref is only the no-art fallback. The page
                    // palette follows whichever cover wins.
                    coverArtUrl = heroCover?.let { repository.resolveCoverUrl(it) },
                    canWrite = playlist.canWrite,
                    tracksUnavailable = playlist.tracksUnavailable,
                    songs = songs,
                    sleeveCoverUrls = sleeveCoverUrls,
                )
                markPerfContent(playlist.id.toString())
            } catch (e: Exception) {
                _uiState.value = PlaylistDetailUiState.Error(
                    e.toDetailMessage(R.string.detail_playlist_error_load),
                    diagnostics = e.diagnosticReport(),
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
                backUpPlaylist = container.playlistBackupService::backUp,
            ) as T
    }
}

/** The snackbar after a backup: how many songs it holds, of how many entries when some couldn't come along. */
internal fun backupMessage(backup: PlaylistBackupRecord): UiText {
    val total = backup.trackCount + backup.unexportedCount
    return if (backup.unexportedCount == 0) {
        UiText.Plural(R.plurals.detail_playlist_backup_done, backup.trackCount, listOf(backup.trackCount))
    } else {
        UiText.Plural(R.plurals.detail_playlist_backup_partial, total, listOf(backup.trackCount, total))
    }
}

/** Every row's cover url is resolved at this size (the hero's sleeves reuse them). */
private const val ROW_COVER_SIZE = 320
