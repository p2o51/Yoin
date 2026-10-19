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
import com.gpo.yoin.data.transfer.RestoreReview
import com.gpo.yoin.data.transfer.ReviewRow
import com.gpo.yoin.data.transfer.TrackResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    private val match: suspend (PlaylistBackupRecord, RestoreTarget, (Int, Int) -> Unit) -> List<TrackResolution>,
    private val create: suspend (PlaylistBackupRecord, RestoreTarget, List<ReviewRow>) -> RestoreOutcome,
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

    private val backup: PlaylistBackupRecord?
        get() = (uiState.value as? PlaylistBackupUiState.Content)?.backup

    /** Restore: a fresh choice of target, or back to the restore under way. */
    fun openRestore() {
        when (_restore.value) {
            RestoreUiState.Idle, is RestoreUiState.Done, is RestoreUiState.Failed ->
                _restore.value = RestoreUiState.ChoosingTarget
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
                val resolutions = match(backup, target) { done, total ->
                    if (_restore.value is RestoreUiState.Matching) {
                        _restore.value = RestoreUiState.Matching(target, done, total)
                    }
                }
                RestoreUiState.Review(target, RestoreReview.rows(resolutions))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
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
            val outcome = withContext(NonCancellable) { create(backup, review.target, review.rows) }
            _restore.value = RestoreUiState.Done(review.target, outcome.record)
        }
    }

    /** Drops the restore under way (a review nothing was written from) and closes the sheet. */
    fun cancelRestore() {
        if (_restore.value is RestoreUiState.Writing) return
        job?.cancel()
        _restore.value = RestoreUiState.Idle
        _sheetOpen.value = false
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

    class Factory(private val container: AppContainer, private val backupId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val service: PlaylistBackupService = container.playlistBackupService
            return PlaylistBackupViewModel(
                backupId = backupId,
                store = container.playlistBackupStore,
                restoreTargets = service.restoreTargets(),
                match = { backup, target, onProgress -> service.match(backup, target, onProgress) },
                create = { backup, target, rows -> service.create(backup, target, rows) },
            ) as T
        }
    }
}
