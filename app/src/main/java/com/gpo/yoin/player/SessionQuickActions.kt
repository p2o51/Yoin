package com.gpo.yoin.player

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.gpo.yoin.R
import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The buttons Yoin's own media sessions (Subsonic via [PlaybackService], Apple Music via
 * `AppleMusicPlaybackService`) add to the notification, lock screen and Bluetooth / car
 * controllers: a shuffle toggle and the service's save action — the heart where the
 * service has favorites, library add for Apple Music. Previous / next aren't listed:
 * the platform draws them from the player's own commands, and these sit in the two
 * custom slots beside them.
 *
 * Taps go through the same [PlaybackManager] / [YoinRepository] calls Now Playing uses,
 * so both surfaces read one state (the remembered play mode, [YoinRepository.favoriteOverrides],
 * library membership).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SessionQuickActions(
    private val context: Context,
    private val playbackManager: () -> PlaybackManager,
    private val repository: () -> YoinRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The heart a tap asked for, until its write settles; also blocks a second tap mid-write. */
    private val favoriteWrites = MutableStateFlow<Map<MediaId, Boolean>>(emptyMap())
    private val libraryWrites = MutableStateFlow<Set<MediaId>>(emptySet())
    private var attached: Job? = null

    /** Session callback with the quick-action commands; wrap it with `by` to add more. */
    val callback: MediaSession.Callback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ConnectionResult {
            val commands = ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SHUFFLE)
                .add(FAVORITE)
                .add(LIBRARY_ADD)
                .build()
            return ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                SHUFFLE.customAction -> toggleShuffle()
                FAVORITE.customAction -> toggleFavorite()
                LIBRARY_ADD.customAction -> addToLibrary()
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /** Keeps [session]'s buttons in step with playback until [detach]. Call before releasing it. */
    fun attach(session: MediaSession) {
        attached?.cancel()
        attached = scope.launch {
            launch { refreshLibraryMembership() }
            buttons().collect(session::setMediaButtonPreferences)
        }
    }

    fun detach() {
        attached?.cancel()
        attached = null
    }

    fun release() = scope.cancel()

    private sealed interface SaveState {
        data class Favorite(val starred: Boolean) : SaveState
        data class Library(val membership: LibraryMembership) : SaveState
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun buttons(): Flow<List<CommandButton>> {
        val manager = playbackManager()
        val repository = repository()
        val shuffle = manager.playbackState
            .map { PlayMode.of(it.repeatMode, it.shuffleEnabled) == PlayMode.Shuffle }
            .distinctUntilChanged()
        val track = manager.playbackState
            .map { it.currentTrack }
            .distinctUntilChanged { old, new -> old?.id == new?.id && old?.isStarred == new?.isStarred }
        val save = combine(track, repository.capabilities, ::Pair).flatMapLatest { (track, capabilities) ->
            when {
                track == null || !track.isSavable() -> flowOf(null)
                Capability.LIBRARY_ADD in capabilities ->
                    repository.observeLibraryMembership(track.id).map(SaveState::Library)
                Capability.FAVORITES in capabilities ->
                    combine(favoriteWrites, repository.favoriteOverrides) { writes, overrides ->
                        SaveState.Favorite(writes[track.id] ?: overrides[track.id] ?: track.isStarred)
                    }
                else -> flowOf(null)
            }
        }
        return combine(shuffle, save) { shuffled, saveState ->
            listOfNotNull(shuffleButton(shuffled), saveState?.let(::saveButton))
        }.distinctUntilChanged()
    }

    private fun shuffleButton(on: Boolean) = button(
        icon = if (on) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF,
        label = if (on) R.string.player_action_shuffle_off else R.string.player_action_shuffle_on,
        command = SHUFFLE,
    )

    private fun saveButton(state: SaveState) = when (state) {
        is SaveState.Favorite -> button(
            icon = if (state.starred) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
            label = if (state.starred) R.string.np_cd_remove_favorite else R.string.np_cd_add_favorite,
            command = FAVORITE,
        )
        is SaveState.Library -> when (state.membership) {
            // Apple Music has no remove-from-library API; the filled check is a state, its tap a no-op.
            LibraryMembership.Added, LibraryMembership.Pending -> button(
                icon = CommandButton.ICON_CHECK_CIRCLE_FILLED,
                label = R.string.player_action_in_library,
                command = LIBRARY_ADD,
            )
            else -> button(
                icon = CommandButton.ICON_PLUS_CIRCLE_UNFILLED,
                label = R.string.player_action_add_to_library,
                command = LIBRARY_ADD,
            )
        }
    }

    private fun button(icon: Int, @StringRes label: Int, command: SessionCommand) =
        CommandButton.Builder(icon)
            .setDisplayName(context.getString(label))
            .setSessionCommand(command)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    private fun toggleShuffle() {
        val manager = playbackManager()
        val state = manager.playbackState.value
        val shuffled = PlayMode.of(state.repeatMode, state.shuffleEnabled) == PlayMode.Shuffle
        manager.setPlayMode(if (shuffled) PlayMode.RepeatAll else PlayMode.Shuffle)
    }

    private fun toggleFavorite() {
        val repository = repository()
        val track = playbackManager().playbackState.value.currentTrack ?: return
        if (!track.isSavable() || Capability.FAVORITES !in repository.currentCapabilities()) return
        if (track.id in favoriteWrites.value) return
        val next = !(repository.favoriteOverrides.value[track.id] ?: track.isStarred)
        favoriteWrites.update { it + (track.id to next) }
        scope.launch {
            try {
                repository.setFavorite(track, next).onFailure {
                    toast(if (next) R.string.np_msg_couldnt_save_favorite else R.string.np_msg_couldnt_remove_favorite)
                }
            } finally {
                favoriteWrites.update { it - track.id }
            }
        }
    }

    private fun addToLibrary() {
        val repository = repository()
        val track = playbackManager().playbackState.value.currentTrack ?: return
        if (!track.isSavable() || Capability.LIBRARY_ADD !in repository.currentCapabilities()) return
        if (track.id in libraryWrites.value) return
        libraryWrites.update { it + track.id }
        scope.launch {
            try {
                val membership = repository.observeLibraryMembership(track.id).first()
                if (membership == LibraryMembership.Added || membership == LibraryMembership.Pending) return@launch
                repository.addToLibrary(track).onFailure { toast(R.string.np_msg_couldnt_add_apple) }
            } finally {
                libraryWrites.update { it - track.id }
            }
        }
    }

    /**
     * Now Playing checks membership when the song changes, but only while its ViewModel
     * lives; with the task swiped away the session would show "add" for songs already in
     * the library. Waits a beat first so the usual Now Playing check answers instead.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun refreshLibraryMembership() {
        val repository = repository()
        playbackManager().playbackState
            .map { it.currentTrack }
            .distinctUntilChanged { old, new -> old?.id == new?.id }
            .flatMapLatest { track ->
                if (track == null || !track.isSavable()) flowOf(null)
                else repository.observeLibraryMembership(track.id).map { track to it }
            }
            .collectLatest { pair ->
                val (track, membership) = pair ?: return@collectLatest
                if (membership != LibraryMembership.Unknown) return@collectLatest
                if (Capability.LIBRARY_ADD !in repository.currentCapabilities()) return@collectLatest
                delay(MEMBERSHIP_CHECK_DELAY_MS)
                if (repository.observeLibraryMembership(track.id).first() == LibraryMembership.Unknown) {
                    repository.refreshLibraryMembership(track)
                }
            }
    }

    private fun toast(@StringRes message: Int) =
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    /** Externally started tracks can arrive without a usable id; writing one would 4xx. */
    private fun Track.isSavable() = id.rawId.isNotBlank()

    private companion object {
        const val MEMBERSHIP_CHECK_DELAY_MS = 1_500L
        val SHUFFLE = SessionCommand("com.gpo.yoin.session.SHUFFLE", Bundle.EMPTY)
        val FAVORITE = SessionCommand("com.gpo.yoin.session.FAVORITE", Bundle.EMPTY)
        val LIBRARY_ADD = SessionCommand("com.gpo.yoin.session.LIBRARY_ADD", Bundle.EMPTY)
    }
}
