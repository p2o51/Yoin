package com.gpo.yoin.ui.library

import com.gpo.yoin.R
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.common.AppNotice
import com.gpo.yoin.ui.common.AppNotices
import com.gpo.yoin.ui.common.UiText
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Deleting a playlist, as iOS's PlaylistActions.delete: the page closes at
 * once and the playlist leaves the Library's lists at once ([State.pending]);
 * the service is told after, in [scope], which outlives the page. Taken: the
 * lists drop it for good ([State.done]) and the app's notice says so. Refused
 * (or no answer): it comes back where it was and the notice says it could not
 * be deleted.
 *
 * A deletion belongs to the account it was made on. One whose turn comes
 * after a switch is not sent: the same id on the new account is another
 * playlist. Deletions go out one after another.
 */
class PlaylistDeletions(
    private val scope: CoroutineScope,
    private val currentProfileId: () -> String?,
    // YoinRepository.playlistDeleterForCurrentAccount: deletes on the account in use when asked, or not at all.
    private val deleterForCurrentAccount: () -> suspend (MediaId) -> Result<Unit>,
    private val notices: AppNotices,
    // AppContainer.notifyPlaylistMutation: lists that read playlists read them again.
    private val onDeleted: () -> Unit = {},
) {
    /** A playlist of one account. */
    data class Key(val profileId: String?, val id: MediaId)

    /** A deletion the service took; [seq] orders them. */
    data class Done(val seq: Long, val key: Key)

    /**
     * Playlists on their way out ([pending]: off the lists until the service
     * answers) and the last ones it took ([done], oldest first). One value, so
     * a list sees a deletion land and its playlist leave [pending] together.
     */
    data class State(val pending: Set<Key> = emptySet(), val done: List<Done> = emptyList()) {
        val lastSeq: Long get() = done.lastOrNull()?.seq ?: 0L

        /** The deletions taken since [seq], a list's last look. */
        fun doneAfter(seq: Long): List<Key> = done.filter { it.seq > seq }.map(Done::key)

        /** [profileId]'s playlists on their way out. */
        fun pendingFor(profileId: String?): Set<MediaId> =
            pending.filterTo(HashSet()) { it.profileId == profileId }.mapTo(HashSet(), Key::id)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val seq = AtomicLong()
    private val writes = Mutex()

    /** Takes [id] off the lists now and asks the account in use to delete it. */
    fun delete(id: MediaId) {
        val key = Key(currentProfileId(), id)
        if (key in _state.value.pending) return
        val deleteOnThisAccount = deleterForCurrentAccount()
        _state.update { it.copy(pending = it.pending + key) }
        scope.launch {
            val result = writes.withLock { send(key, deleteOnThisAccount) }
            if (result.isSuccess) {
                val done = Done(seq.incrementAndGet(), key)
                _state.update { it.copy(pending = it.pending - key, done = (it.done + done).takeLast(DONE_KEPT)) }
                onDeleted()
                notices.post(AppNotice(UiText.Res(R.string.detail_playlist_deleted)))
            } else {
                _state.update { it.copy(pending = it.pending - key) }
                notices.post(AppNotice(UiText.Res(R.string.detail_playlist_error_delete)))
            }
        }
    }

    private suspend fun send(key: Key, deletePlaylist: suspend (MediaId) -> Result<Unit>): Result<Unit> {
        if (currentProfileId() != key.profileId) return Result.failure(IllegalStateException("Account changed"))
        return try {
            deletePlaylist(key.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private companion object {
        /** Deletions [State.done] remembers; a list looks far more often than that many land. */
        const val DONE_KEPT = 32
    }
}
