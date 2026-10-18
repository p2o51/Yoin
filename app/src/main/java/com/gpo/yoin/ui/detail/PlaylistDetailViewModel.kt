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
import com.gpo.yoin.data.transfer.MatchDecision
import com.gpo.yoin.data.transfer.PlaylistCopyTarget
import com.gpo.yoin.data.transfer.TransferReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModel(
    private val playlistId: String,
    private val repository: YoinRepository,
    private val onPlaylistMutated: () -> Unit = {},
    copyTargets: Flow<List<PlaylistCopyTarget>> = flowOf(emptyList()),
    // The copy itself (PlaylistCopyService.copy): playlist, target profile, progress.
    private val copyPlaylist: (suspend (MediaId, String, (Int, Int) -> Unit) -> TransferReport)? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PlaylistDetailUiState>(PlaylistDetailUiState.Loading)
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    // Debug-only: `detail.content` marks the first Content only. Declared before
    // init, whose load can publish a mem-cached Content synchronously.
    private var perfContentMarked = false

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4)
    /** One-shot toasts (rename/delete/remove result). */
    val messages: SharedFlow<UiText> = _messages.asSharedFlow()

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

    /** Profiles "Copy to…" offers; empty hides the row. */
    val copyTargets: StateFlow<List<PlaylistCopyTarget>> = copyTargets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _copyState = MutableStateFlow<PlaylistCopyState>(PlaylistCopyState.Closed)
    val copyState: StateFlow<PlaylistCopyState> = _copyState.asStateFlow()
    private var copyJob: Job? = null

    // The sheet closed while the copy ran: its outcome goes to the snackbar instead.
    private var copySheetDismissed = false

    init {
        loadPlaylist()
    }

    fun openCopy() {
        if (copyJob?.isActive == true) {
            copySheetDismissed = false
            return
        }
        _copyState.value = PlaylistCopyState.Choosing
    }

    fun dismissCopy() {
        if (copyJob?.isActive == true) copySheetDismissed = true
        _copyState.value = PlaylistCopyState.Closed
    }

    fun copyTo(target: PlaylistCopyTarget) {
        val copy = copyPlaylist ?: return
        if (copyJob?.isActive == true) return
        val id = MediaId.parseOrNull(playlistId) ?: return
        copySheetDismissed = false
        _copyState.value = PlaylistCopyState.Copying(target.displayName, done = 0, total = playlistSongs.size)
        copyJob = viewModelScope.launch {
            val outcome = try {
                copyOutcome(target.displayName, copy(id, target.profileId) { done, total -> showCopyProgress(target, done, total) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PlaylistCopyState.Failed(target.displayName)
            }
            if (copySheetDismissed) {
                copySheetDismissed = false
                _messages.tryEmit(copyMessage(outcome))
            } else {
                _copyState.value = outcome
            }
        }
    }

    private fun showCopyProgress(target: PlaylistCopyTarget, done: Int, total: Int) {
        if (copySheetDismissed) return
        _copyState.value = PlaylistCopyState.Copying(target.displayName, done, total)
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

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.renamePlaylist(MediaId.parse(playlistId), trimmed)
                .onSuccess {
                    onPlaylistMutated()
                    _messages.tryEmit(UiText.Res(R.string.detail_playlist_renamed))
                    loadPlaylist() // refresh name + snapshot
                }
                .onFailure {
                    _messages.tryEmit(it.message?.let(UiText::Raw) ?: UiText.Res(R.string.detail_playlist_error_rename))
                }
        }
    }

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

    private fun copyMessage(outcome: PlaylistCopyState): UiText = when (outcome) {
        is PlaylistCopyState.Copied -> if (outcome.added == 0) {
            UiText.Res(R.string.detail_playlist_copy_none, listOf(outcome.target))
        } else {
            UiText.Plural(
                R.plurals.detail_playlist_copy_done,
                outcome.total,
                listOf(outcome.added, outcome.total, outcome.target),
            )
        }
        else -> UiText.Res(R.string.detail_playlist_copy_failed)
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
                _uiState.value = PlaylistDetailUiState.Content(
                    playlistId = playlist.id.toString(),
                    playlistName = playlist.name,
                    owner = playlist.owner.orEmpty(),
                    comment = playlist.comment,
                    isPublic = null,
                    songCount = playlist.songCount,
                    totalDuration = playlist.durationSec,
                    // Hero shows the playlist's OWN cover (mosaic/custom art);
                    // a first-track ref is only the no-art fallback. The page
                    // palette follows whichever cover wins.
                    coverArtUrl = (playlist.coverArt ?: heroCoverRef)?.let { repository.resolveCoverUrl(it) },
                    canWrite = playlist.canWrite,
                    songs = playlist.tracks.mapIndexed { index, song ->
                        PlaylistSong(
                            id = song.id.toString(),
                            title = song.title.orEmpty(),
                            artist = song.artist.orEmpty(),
                            album = song.album.orEmpty(),
                            duration = song.durationSec,
                            coverArtUrl = (song.coverArt
                                ?: song.albumId?.let { albumId -> CoverRef.SourceRelative(albumId.rawId) })?.let {
                                repository.resolveCoverUrl(it, size = 320)
                            },
                            position = index,
                        )
                    },
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
                copyTargets = container.playlistCopyService.targets(),
                copyPlaylist = { id, target, onProgress ->
                    container.playlistCopyService.copy(id, target, onProgress)
                },
            ) as T
    }
}

/** What the sheet shows for [report], the copy to [target]. */
internal fun copyOutcome(target: String, report: TransferReport): PlaylistCopyState {
    // Nothing was made and nothing was meant to be (no song matched): an outcome, not a failure.
    if (report.created == null && report.failure != null) return PlaylistCopyState.Failed(target)
    return PlaylistCopyState.Copied(
        target = target,
        added = report.added,
        total = report.total,
        missing = report.missing.map { resolution ->
            PlaylistCopyMissing(
                position = resolution.position,
                title = resolution.source.title.orEmpty(),
                artist = resolution.source.artist.orEmpty(),
                unsure = resolution.decision is MatchDecision.Ambiguous,
            )
        },
        stoppedEarly = report.failure != null,
    )
}
