package com.gpo.yoin.ui.settings.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.backup.PlaylistBackupRecord
import com.gpo.yoin.data.backup.PlaylistBackupService
import com.gpo.yoin.data.backup.PlaylistBackupStore
import com.gpo.yoin.data.backup.PlaylistRestoreRecord
import com.gpo.yoin.data.backup.RestoreOutcome
import com.gpo.yoin.data.backup.RestoreTarget
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.transfer.RestoreReview
import com.gpo.yoin.data.transfer.ReviewRow
import com.gpo.yoin.data.transfer.ReviewStatus
import com.gpo.yoin.data.transfer.TrackResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings › Playlist backups: every backup, newest first. */
sealed interface PlaylistBackupsUiState {
    data object Loading : PlaylistBackupsUiState

    data class Content(val backups: List<PlaylistBackupRecord>) : PlaylistBackupsUiState
}

class PlaylistBackupsViewModel(store: PlaylistBackupStore) : ViewModel() {
    val uiState: StateFlow<PlaylistBackupsUiState> = store.observeAll()
        .map<List<PlaylistBackupRecord>, PlaylistBackupsUiState> { PlaylistBackupsUiState.Content(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistBackupsUiState.Loading)

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlaylistBackupsViewModel(container.playlistBackupStore) as T
    }
}

/** One backup's page. [Missing]: deleted here or on another device. */
sealed interface PlaylistBackupUiState {
    data object Loading : PlaylistBackupUiState

    data object Missing : PlaylistBackupUiState

    data class Content(val backup: PlaylistBackupRecord) : PlaylistBackupUiState
}

/**
 * A restore, from choosing where to through what came of it
 * (docs/playlist-transfer.md §7). One sheet shows every step; closing it
 * leaves the step running or waiting, and Restore reopens it there.
 */
sealed interface RestoreUiState {
    data object Idle : RestoreUiState

    data object ChoosingTarget : RestoreUiState

    data class Matching(val target: RestoreTarget, val done: Int, val total: Int) : RestoreUiState

    /** Nothing written yet: the user checks the rows to write. */
    data class Review(val target: RestoreTarget, val rows: List<ReviewRow>) : RestoreUiState

    data class Writing(val target: RestoreTarget, val review: Review) : RestoreUiState

    data class Done(val target: RestoreTarget, val record: PlaylistRestoreRecord) : RestoreUiState

    /** Matching couldn't reach the target. */
    data class Failed(val target: RestoreTarget) : RestoreUiState
}

/** [backup] with the contents of its version that began at [versionSince] (null, or no such version: as it is). */
internal fun restoreSnapshot(backup: PlaylistBackupRecord, versionSince: Long?): PlaylistBackupRecord {
    val version = versionSince?.let { since -> backup.versions.firstOrNull { it.since == since } } ?: return backup
    return backup.copy(content = version.content)
}

/** The review's search for one song on the target: what was asked, and what came back. */
data class RestoreSearchState(
    val position: Int,
    val query: String,
    val results: List<Track> = emptyList(),
    val loading: Boolean = false,
    /** The last search couldn't reach the target. */
    val failed: Boolean = false,
)

/** Where a restore's progress shows outside the app: the notification's Live Update. */
interface RestoreProgressSink {
    /** [outcomes]: what each song resolved to so far, in the order they came back. */
    fun matching(
        backup: PlaylistBackupRecord,
        target: RestoreTarget,
        done: Int,
        total: Int,
        outcomes: List<ReviewStatus> = emptyList(),
    )

    fun writing(backup: PlaylistBackupRecord, target: RestoreTarget, count: Int)

    fun finished(backup: PlaylistBackupRecord, target: RestoreTarget, record: PlaylistRestoreRecord)

    fun clear()

    object None : RestoreProgressSink {
        override fun matching(
            backup: PlaylistBackupRecord,
            target: RestoreTarget,
            done: Int,
            total: Int,
            outcomes: List<ReviewStatus>,
        ) = Unit

        override fun writing(backup: PlaylistBackupRecord, target: RestoreTarget, count: Int) = Unit

        override fun finished(backup: PlaylistBackupRecord, target: RestoreTarget, record: PlaylistRestoreRecord) = Unit

        override fun clear() = Unit
    }
}

sealed interface PlaylistBackupEvent {
    /** The backup was deleted from this page: close it. */
    data object Deleted : PlaylistBackupEvent
}

/**
 * One backup's page and its restore. The restore lives in this ViewModel's
 * scope, not the sheet's: matching carries on while the sheet is closed, and
 * the review waits for the user to come back to it.
 */
class PlaylistBackupViewModel(
    private val backupId: String,
    private val store: PlaylistBackupStore,
    restoreTargets: Flow<List<RestoreTarget>>,
    private val match: suspend (
        PlaylistBackupRecord,
        RestoreTarget,
        (Int, Int) -> Unit,
        (TrackResolution) -> Unit,
    ) -> List<TrackResolution>,
    private val create: suspend (PlaylistBackupRecord, RestoreTarget, List<ReviewRow>) -> RestoreOutcome,
    private val searchTarget: suspend (RestoreTarget, String) -> List<Track> = { _, _ -> emptyList() },
    private val backUpNow: suspend (PlaylistBackupRecord) -> Unit = {},
    private val progress: RestoreProgressSink = RestoreProgressSink.None,
) : ViewModel() {

    val uiState: StateFlow<PlaylistBackupUiState> = store.observe(backupId)
        .map { record -> record?.let(PlaylistBackupUiState::Content) ?: PlaylistBackupUiState.Missing }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlaylistBackupUiState.Loading)

    val targets: StateFlow<List<RestoreTarget>> = restoreTargets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _restore = MutableStateFlow<RestoreUiState>(RestoreUiState.Idle)
    val restore: StateFlow<RestoreUiState> = _restore.asStateFlow()

    private val _sheetOpen = MutableStateFlow(false)
    val sheetOpen: StateFlow<Boolean> = _sheetOpen.asStateFlow()

    private val _events = MutableSharedFlow<PlaylistBackupEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<PlaylistBackupEvent> = _events.asSharedFlow()

    private var job: Job? = null

    private val _search = MutableStateFlow<RestoreSearchState?>(null)
    val search: StateFlow<RestoreSearchState?> = _search.asStateFlow()
    private var searchJob: Job? = null

    private val _backingUp = MutableStateFlow(false)
    val backingUp: StateFlow<Boolean> = _backingUp.asStateFlow()

    /** Which version a restore takes: null = the current one, else that version's [BackupVersion.since]. */
    private val _restoreVersion = MutableStateFlow<Long?>(null)
    val restoreVersion: StateFlow<Long?> = _restoreVersion.asStateFlow()

    /** The backup as the restore sees it: its current contents, or the chosen earlier version's. */
    private val backup: PlaylistBackupRecord?
        get() {
            val current = (uiState.value as? PlaylistBackupUiState.Content)?.backup ?: return null
            return restoreSnapshot(current, _restoreVersion.value)
        }

    /**
     * Restore: a fresh choice of target — of the current contents, or of the
     * earlier version that began at [versionSince] — or back to the restore
     * under way.
     */
    fun openRestore(versionSince: Long? = null) {
        when (_restore.value) {
            RestoreUiState.Idle, is RestoreUiState.Done, is RestoreUiState.Failed -> {
                _restoreVersion.value = versionSince
                _restore.value = RestoreUiState.ChoosingTarget
            }
            else -> Unit
        }
        _sheetOpen.value = true
    }

    /** Closes the sheet; a finished restore is put away, one under way carries on. */
    fun dismissSheet() {
        _sheetOpen.value = false
        when (_restore.value) {
            RestoreUiState.ChoosingTarget, is RestoreUiState.Done, is RestoreUiState.Failed ->
                _restore.value = RestoreUiState.Idle
            else -> Unit
        }
    }

    fun restoreTo(target: RestoreTarget) {
        val backup = backup ?: return
        if (job?.isActive == true) return
        _restore.value = RestoreUiState.Matching(target, done = 0, total = backup.trackCount)
        job = viewModelScope.launch {
            _restore.value = try {
                progress.matching(backup, target, 0, backup.trackCount)
                val outcomes = ArrayList<ReviewStatus>(backup.trackCount)
                val resolutions = match(
                    backup,
                    target,
                    { done, total ->
                        if (_restore.value is RestoreUiState.Matching) {
                            _restore.value = RestoreUiState.Matching(target, done, total)
                            progress.matching(backup, target, done, total, outcomes.toList())
                        }
                    },
                    { resolution -> outcomes += RestoreReview.statusOf(resolution) },
                )
                // Matching done: the review waits in the app, not in the notification.
                progress.clear()
                RestoreUiState.Review(target, RestoreReview.rows(resolutions))
            } catch (cancelled: CancellationException) {
                progress.clear()
                throw cancelled
            } catch (_: Exception) {
                progress.clear()
                RestoreUiState.Failed(target)
            }
        }
    }

    fun setChecked(position: Int, checked: Boolean) = updateReview { RestoreReview.setChecked(it, position, checked) }

    fun choose(position: Int, index: Int) = updateReview { RestoreReview.choose(it, position, index) }

    /** Writes the checked rows to the target; the backup's history records it. */
    fun createPlaylist() {
        val backup = backup ?: return
        val review = _restore.value as? RestoreUiState.Review ?: return
        if (RestoreReview.trackIdsToWrite(review.rows).isEmpty()) return
        _restore.value = RestoreUiState.Writing(review.target, review)
        job = viewModelScope.launch {
            // Once started, the write and its history entry finish even if the page closes.
            progress.writing(backup, review.target, RestoreReview.trackIdsToWrite(review.rows).size)
            val outcome = withContext(NonCancellable) { create(backup, review.target, review.rows) }
            progress.finished(backup, review.target, outcome.record)
            _restore.value = RestoreUiState.Done(review.target, outcome.record)
        }
    }

    /** Drops the restore under way (a review nothing was written from) and closes the sheet. */
    fun cancelRestore() {
        if (_restore.value is RestoreUiState.Writing) return
        job?.cancel()
        progress.clear()
        closeSearch()
        _restore.value = RestoreUiState.Idle
        _sheetOpen.value = false
    }

    /** Opens the search for the song at [position] on the restore's target, starting from its title and artist. */
    fun openSearch(position: Int, query: String) {
        _search.value = RestoreSearchState(position = position, query = query)
        runSearch(query, debounce = false)
    }

    fun runSearch(query: String, debounce: Boolean = true) {
        val target = (_restore.value as? RestoreUiState.Review)?.target ?: return
        val current = _search.value ?: return
        val trimmed = query.trim()
        _search.value = current.copy(query = query, loading = trimmed.isNotEmpty(), failed = false)
        searchJob?.cancel()
        if (trimmed.isEmpty()) return
        searchJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val results = try {
                searchTarget(target, trimmed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            _search.value = _search.value?.takeIf { it.position == current.position }?.copy(
                results = results.orEmpty(),
                loading = false,
                failed = results == null,
            )
        }
    }

    /** [track] becomes the searched row's song, checked; the search closes. */
    fun pickSearchResult(track: Track) {
        val position = _search.value?.position ?: return
        updateReview { RestoreReview.pick(it, position, track) }
        closeSearch()
    }

    fun closeSearch() {
        searchJob?.cancel()
        _search.value = null
    }

    /** Reads the playlist again now (the page's Back up now). */
    fun backUpAgain() {
        val backup = backup ?: return
        if (_backingUp.value) return
        _backingUp.value = true
        viewModelScope.launch {
            try {
                backUpNow(backup)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Left as it was; the page keeps showing the last backup.
            } finally {
                _backingUp.value = false
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            job?.cancel()
            store.delete(backupId)
            _events.tryEmit(PlaylistBackupEvent.Deleted)
        }
    }

    private fun updateReview(change: (List<ReviewRow>) -> List<ReviewRow>) {
        val review = _restore.value as? RestoreUiState.Review ?: return
        _restore.value = review.copy(rows = change(review.rows))
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
    }

    class Factory(
        private val container: AppContainer,
        private val backupId: String,
        private val progress: RestoreProgressSink = RestoreProgressSink.None,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val service: PlaylistBackupService = container.playlistBackupService
            return PlaylistBackupViewModel(
                backupId = backupId,
                store = container.playlistBackupStore,
                restoreTargets = service.restoreTargets(),
                match = { backup, target, onProgress, onResolved -> service.match(backup, target, onProgress, onResolved) },
                create = { backup, target, rows -> service.create(backup, target, rows) },
                searchTarget = { target, query -> service.search(target, query) },
                backUpNow = { backup -> service.followNow(backup) },
                progress = progress,
            ) as T
        }
    }
}
