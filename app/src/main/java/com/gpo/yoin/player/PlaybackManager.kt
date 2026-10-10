package com.gpo.yoin.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.gpo.yoin.R
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.PlaybackHandle
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.model.isUnplayableAppleImport
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.spotify.SpotifyMusicSource
import com.gpo.yoin.perf.YoinPerf
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

class PlaybackManager(
    private val context: Context,
    private val repository: YoinRepository,
    private val castManager: CastManager? = null,
    spotifyClientIdProvider: () -> String,
) {
    private enum class ActiveBackend {
        NONE,
        LOCAL,
        SPOTIFY_REMOTE,
    }

    private var controller: MediaController? = null
    private var controllerUsesMusicKit = false
    private var requestedMedia3Source: MusicSource? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionUpdateJob: Job? = null
    // True while any Yoin Activity is started (driven by YoinApplication's
    // host lifecycle). Gates the 250ms position ticker: nothing consumes
    // interpolated positions in the background — the media notification is
    // driven by Media3 itself, not this StateFlow.
    private var hostStarted = false
    private var connectJob: Job? = null
    private val pendingCommands = mutableListOf<(MediaController) -> Unit>()
    private var lastRecordedTrackId: MediaId? = null
    private val _currentActivityContext = MutableStateFlow<ActivityContext>(ActivityContext.None)
    val currentActivityContext: StateFlow<ActivityContext> = _currentActivityContext.asStateFlow()
    // Last context Spotify reported it's playing FROM (album / playlist / …). Used to
    // label externally-started playback. See [onSpotifyPlayerContext].
    private var lastSpotifyContext: SpotifyPlaybackContext? = null
    private var lastKnownDurationSecById: Map<MediaId, Int> = emptyMap()
    private var activeBackend: ActiveBackend = ActiveBackend.NONE
    private var pendingSpotifyHandoff: Boolean = false
    private var preserveLocalUiDuringSpotifyHandoff: Boolean = false

    /**
     * The play mode the user last picked (repeat all by default). Applied to
     * whichever backend a Yoin-started queue lands on, so both the default
     * and the user's choice survive a provider switch. Playback started
     * outside Yoin (e.g. in the Spotify app) is only observed, never
     * overwritten. Not persisted across process death.
     */
    private var preferredPlayMode: PlayMode = PlayMode.RepeatAll

    /** The Spotify source of the last Spotify play, for queue-sheet taps. */
    private var spotifySource: SpotifyMusicSource? = null

    /**
     * Wall-clock anchor for Spotify position interpolation.
     *
     * App Remote only emits `PlayerState` on state transitions (play /
     * pause / seek / track change), not continuously during playback —
     * so between events we synthesize progress locally. The naive
     * "position += tickInterval" loop drifts with coroutine scheduling
     * jitter (delay is approximate, not exact). Wall-clock anchoring
     * solves it: each real event re-pins `anchorPosition` /
     * `anchorWallClock`, and every tick computes
     * `anchorPosition + (now - anchorWallClock)`. Any drift gets wiped
     * on the next real emission.
     */
    private var spotifyPositionAnchorMs: Long = 0L
    private var spotifyPositionAnchorWallClock: Long = 0L
    private val spotifyRemotePlayer = SpotifyAppRemotePlayer(
        applicationContext = context.applicationContext,
        clientIdProvider = spotifyClientIdProvider,
        onSnapshot = ::publishRemoteState,
        onActionRequired = ::emitSpotifyActionRequired,
        onContext = ::onSpotifyPlayerContext,
    )

    /**
     * The Spotify heart check, made here — once per track change, however
     * many Now Playing hosts are open — and pushed into the repository's
     * favorite state (this depends on the repository, never the reverse).
     */
    private val savedStateRefresher = SpotifySavedStateRefresher(
        scope = scope,
        clock = System::currentTimeMillis,
        appRemoteState = ::appRemoteLibraryState,
        sink = object : SpotifySavedStateSink {
            override fun currentProfileId(): String? = repository.currentProfileId()

            override fun recordAppRemoteState(profileId: String, trackId: MediaId, saved: Boolean) {
                repository.recordFavoriteState(profileId, trackId, saved)
            }

            override suspend fun checkWithWebApi(track: Track) {
                repository.refreshFavoriteStates(listOf(track))
            }
        }
    )

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    /**
     * One-shot playback events surfaced up to the shell (see `YoinNavHost`)
     * for actionable user prompts — typically a snackbar when Spotify
     * App Remote refuses to connect.
     */
    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    init {
        castManager?.let { cm ->
            scope.launch {
                cm.castState.collect { state ->
                    _playbackState.value = _playbackState.value.copy(
                        isCasting = state is CastState.Connected,
                        castDeviceName = (state as? CastState.Connected)?.deviceName,
                    )
                }
            }
        }
        // Warm the App Remote whenever the active profile is (or becomes)
        // Spotify and we have a live host. Lets NowPlaying reflect whatever
        // Spotify is playing externally — the user may have been listening
        // via car audio / smart speaker before opening the app.
        scope.launch {
            repository.activeProviderId.collect { providerId ->
                if (providerId == MediaId.PROVIDER_SPOTIFY) {
                    spotifyRemotePlayer.warmConnection()
                }
            }
        }
    }

    suspend fun connect() {
        if (activeBackend == ActiveBackend.SPOTIFY_REMOTE) return
        if (controller != null) return
        connectInBackground()
        connectJob?.join()
    }

    fun connectInBackground() {
        if (activeBackend == ActiveBackend.SPOTIFY_REMOTE) return
        if (controller != null || connectJob?.isActive == true) return
        _playbackState.value = _playbackState.value.copy(
            connectionPhase = ConnectionPhase.Connecting,
            connectionErrorMessage = null,
        )
        connectJob = scope.launch {
            try {
                val built = buildController()
                controller = built
                built.addListener(playerListener)
                castManager?.setLocalPlayerProvider { if (controllerUsesMusicKit) null else controller }
                syncState()
                startPositionUpdates()
                flushPendingCommands(built)
            } catch (cancellation: CancellationException) {
                // Cancellation (profile switch / Spotify handoff) is not a
                // connection failure — don't flash an Error phase.
                throw cancellation
            } catch (error: Throwable) {
                pendingCommands.clear()
                _playbackState.value = _playbackState.value.copy(
                    connectionPhase = ConnectionPhase.Error,
                    connectionErrorMessage = error.message
                        ?: context.getString(R.string.player_init_failed),
                )
            } finally {
                connectJob = null
            }
        }
    }

    fun onHostStart(hostContext: Context) {
        hostStarted = true
        // Resume the position ticker if playback kept going while we were
        // backgrounded; the first tick re-anchors from the authoritative
        // source (controller position / Spotify wall-clock anchor).
        if (_playbackState.value.isPlaying) {
            startPositionUpdates()
        }
        spotifyRemotePlayer.onHostStart(hostContext)
        // Host just came up — if Spotify is already active, the init-time
        // collector may have fired before hostContext was set, so re-issue
        // the warm connection now. `warmConnection` is idempotent.
        if (repository.currentProviderId() == MediaId.PROVIDER_SPOTIFY) {
            spotifyRemotePlayer.warmConnection()
        }
    }

    fun onHostStop() {
        // Kill the ticker BEFORE forwarding: the Spotify teardown below
        // republishes a preserved snapshot (often isPlaying=true) that would
        // otherwise restart the loop and keep 4Hz wakeups alive forever.
        hostStarted = false
        stopPositionUpdates()
        spotifyRemotePlayer.onHostStop()
    }

    fun disconnect() {
        com.gpo.yoin.player.applemusic.AppleMusicValidationService.stop(context)
        spotifyRemotePlayer.disconnect(resetState = false)
        savedStateRefresher.reset()
        activeBackend = ActiveBackend.NONE
        requestedMedia3Source = null
        pendingSpotifyHandoff = false
        preserveLocalUiDuringSpotifyHandoff = false
        pendingCommands.clear()
        connectJob?.cancel()
        connectJob = null
        positionUpdateJob?.cancel()
        positionUpdateJob = null
        controller?.stop()
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        context.stopService(android.content.Intent(context, com.gpo.yoin.player.applemusic.AppleMusicPlaybackService::class.java))
        _playbackState.value = PlaybackState(connectionPhase = ConnectionPhase.Idle)
    }

    // ── Playback controls ─────────────────────────────────────────────

    /**
     * Starts [tracks] at [startIndex]. [shuffled] says the caller already
     * shuffled the list for a Shuffle button: Media3 / MusicKit play it as
     * given, while Spotify starts its context at the first track with
     * Spotify's own shuffle on (a context can't take Yoin's order). Apple
     * Music imports MusicKit can't play are dropped first ([playableQueue]).
     * [explicitStart] says the user picked tracks[startIndex] (a row tap); a
     * Play or Shuffle button picks no song, and neither does a shuffled list's
     * first place, so an import there gives way to the next song that plays.
     */
    fun play(
        tracks: List<Track>,
        startIndex: Int = 0,
        source: MusicSource,
        activityContext: ActivityContext = ActivityContext.None,
        shuffled: Boolean = false,
        explicitStart: Boolean = true,
    ) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        val queue = playableQueue(tracks, startIndex, explicitStart = explicitStart && !shuffled)
        startQueue(queue.tracks, queue.startIndex, source, activityContext, shuffled)
    }

    private fun startQueue(
        tracks: List<Track>,
        startIndex: Int,
        source: MusicSource,
        activityContext: ActivityContext,
        shuffled: Boolean
    ) {
        com.gpo.yoin.player.applemusic.AppleMusicValidationService.stop(context)
        lastRecordedTrackId = null
        _currentActivityContext.value = activityContext
        lastKnownDurationSecById = tracks
            .mapNotNull { track -> track.durationSec?.let { track.id to it } }
            .toMap()
        scope.launch {
            // Guarded: handleFor / buildMediaItem are provider calls that can
            // throw, and the scope has no CoroutineExceptionHandler.
            runCatching {
                if (source.id != MediaId.PROVIDER_SPOTIFY) selectMedia3Source(source)
                when (val handle = source.playback().handleFor(tracks[startIndex])) {
                    is PlaybackHandle.DirectStream -> {
                        pendingSpotifyHandoff = false
                        preserveLocalUiDuringSpotifyHandoff = false
                        activeBackend = ActiveBackend.LOCAL
                        spotifyRemotePlayer.disconnect(resetState = false)
                        val items = tracks.map { buildMediaItem(it, source) }
                        val playMode = preferredPlayMode
                        executeOrQueue { player ->
                            player.setMediaItems(items, startIndex, 0L)
                            player.prepare()
                            player.play()
                            player.applyPlayMode(playMode)
                        }
                    }

                    is PlaybackHandle.ExternalController -> {
                        if (handle.type == PlaybackHandle.ControllerType.APPLE_MUSIC_KIT) {
                            pendingSpotifyHandoff = false
                            preserveLocalUiDuringSpotifyHandoff = false
                            activeBackend = ActiveBackend.LOCAL
                            spotifyRemotePlayer.disconnect(resetState = false)
                            val items = tracks.map { buildMediaItem(it, source) }
                            val playMode = preferredPlayMode
                            executeOrQueue { player ->
                                player.setMediaItems(items, startIndex, 0L)
                                player.playWhenReady = true
                                player.prepare()
                                player.applyPlayMode(playMode)
                            }
                            return@runCatching
                        }
                        pendingSpotifyHandoff = true
                        preserveLocalUiDuringSpotifyHandoff =
                            activeBackend == ActiveBackend.LOCAL &&
                                controller != null &&
                                _playbackState.value.currentTrack != null
                        if (!preserveLocalUiDuringSpotifyHandoff) {
                            activeBackend = ActiveBackend.SPOTIFY_REMOTE
                        }
                        // If the user tapped inside an album / playlist / artist,
                        // route through Spotify's Web API so the context sticks
                        // ("playing from X" in Spotify's own UI + proper
                        // recommendation signal). Bare-track entry points (queue,
                        // search result, memories single track) keep the
                        // non-context App Remote path — playQueue falls back
                        // transparently on Web API failure too.
                        spotifySource = source as? SpotifyMusicSource
                        spotifyRemotePlayer.playQueue(
                            tracks = tracks,
                            startIndex = startIndex,
                            startContextPlayback = buildSpotifyStartFn(
                                source = source,
                                activityContext = activityContext,
                                tracks = tracks,
                                startIndex = startIndex,
                                shuffled = shuffled,
                            ),
                            // A shuffled album / playlist / Liked start keeps its
                            // context, so Spotify does the shuffling; a plain
                            // list is played in the order Yoin already shuffled.
                            playMode = if (shuffled && activityContext.isSpotifyContext()) {
                                PlayMode.Shuffle
                            } else {
                                preferredPlayMode
                            },
                        )
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _playbackState.value = _playbackState.value.copy(
                    connectionPhase = ConnectionPhase.Error,
                    connectionErrorMessage = error.message
                        ?: context.getString(R.string.player_play_failed),
                )
                Log.w(TAG, "play failed for ${tracks[startIndex].id}", error)
            }
        }
    }

    fun playSingle(
        track: Track,
        source: MusicSource,
        activityContext: ActivityContext = ActivityContext.None,
    ) {
        play(listOf(track), 0, source, activityContext)
    }

    fun pause() {
        when (activeBackend) {
            ActiveBackend.SPOTIFY_REMOTE -> spotifyRemotePlayer.pause()
            else -> executeOrQueue { it.pause() }
        }
    }

    fun resume() {
        com.gpo.yoin.player.applemusic.AppleMusicValidationService.stop(context)
        when (activeBackend) {
            ActiveBackend.SPOTIFY_REMOTE -> spotifyRemotePlayer.resume()
            else -> executeOrQueue { it.play() }
        }
    }

    fun skipNext() {
        when (activeBackend) {
            ActiveBackend.SPOTIFY_REMOTE -> spotifyRemotePlayer.skipNext()
            else -> executeOrQueue { player ->
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                }
            }
        }
    }

    fun skipPrevious() {
        when (activeBackend) {
            ActiveBackend.SPOTIFY_REMOTE -> spotifyRemotePlayer.skipPrevious()
            // Spotify's rule (owner W2, 2026-10-05): past the first 3 s
            // (maxSeekToPreviousPosition) previous restarts the song; only near
            // its start does it go back a song. The pulse reads a restart via
            // TransportTap.restartedInPlace.
            else -> executeOrQueue { player -> player.seekToPrevious() }
        }
    }

    fun seekTo(positionMs: Long) {
        when (activeBackend) {
            ActiveBackend.SPOTIFY_REMOTE -> spotifyRemotePlayer.seekTo(positionMs)
            else -> executeOrQueue { it.seekTo(positionMs) }
        }
    }

    fun setRepeatMode(mode: Int) {
        when (activeBackend) {
            ActiveBackend.SPOTIFY_REMOTE -> spotifyRemotePlayer.setRepeatMode(mode)
            else -> executeOrQueue { it.repeatMode = mode }
        }
    }

    /**
     * Switches the play mode. Repeat and shuffle are always written together
     * (see [PlayMode]); the mode is also remembered for the next queue Yoin
     * starts, on any backend.
     */
    fun setPlayMode(mode: PlayMode) {
        preferredPlayMode = mode
        when (activeBackend) {
            ActiveBackend.SPOTIFY_REMOTE -> {
                spotifyRemotePlayer.setRepeatMode(mode.repeatMode)
                spotifyRemotePlayer.setShuffle(mode.shuffle)
            }
            else -> executeOrQueue { it.applyPlayMode(mode) }
        }
    }

    // ── Queue management ──────────────────────────────────────────────

    fun addToQueue(track: Track, source: MusicSource) = addToQueue(listOf(track), source, next = false)

    /**
     * Queues [tracks] in order (a detail page's ▾ Play next / Add to queue,
     * owner F1 2026-10-05), Spotify's way: Play next right after the current
     * song; Add to queue after what the user already queued there, ahead of
     * the rest of the album or playlist. Spotify has one queue, its own,
     * which plays before the context resumes: both land there. Apple Music
     * imports MusicKit can't play are left out, as in [play]: one used to
     * fail the whole batch.
     */
    fun addToQueue(tracks: List<Track>, source: MusicSource, next: Boolean) {
        val playable = tracks.filterNot { it.isUnplayableAppleImport }
        if (playable.isEmpty()) return
        queueTracks(playable, source, next)
    }

    private fun queueTracks(tracks: List<Track>, source: MusicSource, next: Boolean) {
        scope.launch {
            runCatching {
                val handle = source.playback().handleFor(tracks.first())
                if (handle is PlaybackHandle.ExternalController &&
                    handle.type != PlaybackHandle.ControllerType.APPLE_MUSIC_KIT
                ) {
                    activeBackend = ActiveBackend.SPOTIFY_REMOTE
                    disconnectLocalController(resetState = false)
                    tracks.forEach(spotifyRemotePlayer::addToQueue)
                    return@runCatching
                }
                if (handle is PlaybackHandle.ExternalController) {
                    selectMedia3Source(source)
                    activeBackend = ActiveBackend.LOCAL
                } else {
                    pendingSpotifyHandoff = false
                    preserveLocalUiDuringSpotifyHandoff = false
                }
                val items = tracks.map { track -> buildMediaItem(track, source, userQueued = true) }
                executeOrQueue { player ->
                    if (player.mediaItemCount == 0) {
                        player.addMediaItems(items)
                        return@executeOrQueue
                    }
                    var at = player.currentMediaItemIndex + 1
                    if (!next) {
                        while (at < player.mediaItemCount && player.getMediaItemAt(at).isUserQueued()) at++
                    }
                    player.addMediaItems(at, items)
                }
            }.onFailure { error ->
                Log.w(TAG, "addToQueue failed for ${tracks.size} tracks", error)
            }
        }
    }

    /** Moves queue entry [from] to [to] (list indices; the sheet keeps moves inside one section). */
    fun moveQueueItem(from: Int, to: Int) {
        if (from == to || activeBackend == ActiveBackend.SPOTIFY_REMOTE) return
        executeOrQueue { player ->
            if (from in 0 until player.mediaItemCount && to in 0 until player.mediaItemCount) {
                player.moveMediaItem(from, to)
            }
        }
    }

    /** Takes queue entry [index] out (never the current one). */
    fun removeQueueItem(index: Int) {
        if (activeBackend == ActiveBackend.SPOTIFY_REMOTE) return
        executeOrQueue { player ->
            if (index in 0 until player.mediaItemCount && index != player.currentMediaItemIndex) {
                player.removeMediaItem(index)
            }
        }
    }

    /** Drops every entry the user added (the sheet's "Clear" on Next in queue). */
    fun clearUserQueue() {
        if (activeBackend == ActiveBackend.SPOTIFY_REMOTE) return
        executeOrQueue { player ->
            for (i in player.mediaItemCount - 1 downTo 0) {
                if (i != player.currentMediaItemIndex && player.getMediaItemAt(i).isUserQueued()) {
                    player.removeMediaItem(i)
                }
            }
        }
    }

    private fun MediaItem.isUserQueued(): Boolean = mediaMetadata.extras?.getBoolean(EXTRA_USER_QUEUED, false) == true

    fun clearQueue() {
        when (activeBackend) {
            ActiveBackend.SPOTIFY_REMOTE -> spotifyRemotePlayer.clearQueue()
            else -> executeOrQueue { it.clearMediaItems() }
        }
    }

    fun skipToQueueItem(index: Int) {
        lastRecordedTrackId = null
        when (activeBackend) {
            ActiveBackend.SPOTIFY_REMOTE -> {
                // A tap in the queue sheet restarts the same list at that
                // track through the Web API (see play()), not App Remote's
                // play + queue loop, which would pile the list into the
                // user's Spotify queue again.
                val queue = _playbackState.value.queue
                val source = spotifySource
                if (index !in queue.indices || source == null) {
                    spotifyRemotePlayer.skipToQueueItem(index)
                } else {
                    spotifyRemotePlayer.playQueue(
                        tracks = queue,
                        startIndex = index,
                        startContextPlayback = buildSpotifyStartFn(
                            source = source,
                            activityContext = _currentActivityContext.value,
                            tracks = queue,
                            startIndex = index,
                            shuffled = false,
                        ),
                        // Keep whatever Spotify is in now (a Shuffle start
                        // turned its shuffle on).
                        playMode = _playbackState.value.playMode,
                    )
                }
            }
            else -> executeOrQueue { player ->
                if (index in 0 until player.mediaItemCount) {
                    player.seekToDefaultPosition(index)
                }
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) { syncState() }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncState()
            if (isPlaying) startPositionUpdates() else stopPositionUpdates()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncState()
        }

        // A pause / play while buffering changes no isPlaying — but the
        // control reads playWhenReady.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            syncState()
        }

        // playWhenReadyOf reads the suppression too; while buffering, a focus
        // loss / regain changes no isPlaying either.
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            syncState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncState()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            syncState()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            syncState()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            syncState()
        }
    }

    private fun syncState() {
        if (activeBackend == ActiveBackend.SPOTIFY_REMOTE) return
        val player = controller ?: return
        val queue = buildList {
            for (i in 0 until player.mediaItemCount) {
                add(player.getMediaItemAt(i).toTrack())
            }
        }
        val currentIndex = player.currentMediaItemIndex
        val currentItem = player.currentMediaItem?.toTrack()
        val entryIds = List(player.mediaItemCount) { i ->
            player.getMediaItemAt(i).mediaMetadata.extras?.getString(EXTRA_QUEUE_ENTRY) ?: "i$i"
        }
        val userQueued = (0 until player.mediaItemCount).filterTo(mutableSetOf()) { i ->
            player.getMediaItemAt(i).isUserQueued()
        }
        val resolved = when {
            currentItem != null -> currentItem
            currentIndex in queue.indices -> queue[currentIndex]
            player.mediaItemCount == 0 -> null
            else -> _playbackState.value.currentTrack
        }
        publishPlaybackState(
            PlaybackState(
                currentTrack = resolved,
                pendingTrack = null,
                isPlaying = player.isPlaying,
                position = player.currentPosition.coerceAtLeast(0L),
                duration = player.duration.coerceAtLeast(0L),
                bufferedPosition = player.bufferedPosition.coerceAtLeast(0L),
                queue = queue,
                currentIndex = currentIndex,
                queueEntryIds = entryIds,
                userQueued = userQueued,
                upcoming = upcomingOrder(player.currentTimeline, currentIndex, player.shuffleModeEnabled),
                queueEdit = if (controllerUsesMusicKit) QueueEdit.Remove else QueueEdit.Full,
                nextTrack = player.nextMediaItemIndex
                    .takeIf { it != C.INDEX_UNSET && it != currentIndex }
                    ?.let(queue::getOrNull),
                repeatMode = player.repeatMode,
                shuffleEnabled = player.shuffleModeEnabled,
                audioSessionId = PlaybackService.audioSessionId.value,
                isCasting = _playbackState.value.isCasting,
                castDeviceName = _playbackState.value.castDeviceName,
                connectionPhase = when {
                    player.playerError != null -> ConnectionPhase.Error
                    player.playbackState == Player.STATE_BUFFERING -> ConnectionPhase.Connecting
                    else -> ConnectionPhase.Ready
                },
                connectionErrorMessage = player.playerError?.message,
                playWhenReady = playWhenReadyOf(
                    playWhenReady = player.playWhenReady,
                    playbackState = player.playbackState,
                    playbackSuppressionReason = player.playbackSuppressionReason,
                ),
            ),
        )
    }

    private fun startPositionUpdates() {
        if (!hostStarted) return
        if (positionUpdateJob?.isActive == true) return
        positionUpdateJob = scope.launch {
            while (isActive) {
                when (activeBackend) {
                    ActiveBackend.LOCAL -> {
                        val player = controller
                        if (player != null && player.isPlaying) {
                            _playbackState.value = _playbackState.value.copy(
                                position = player.currentPosition.coerceAtLeast(0L),
                                bufferedPosition = player.bufferedPosition.coerceAtLeast(0L),
                            )
                        }
                    }

                    ActiveBackend.SPOTIFY_REMOTE -> {
                        val state = _playbackState.value
                        if (state.isPlaying) {
                            // Wall-clock interpolation, not naive accumulation —
                            // delay() jitter doesn't compound because we re-pin
                            // the anchor on every incoming PlayerState.
                            val elapsed = System.currentTimeMillis() -
                                spotifyPositionAnchorWallClock
                            val projected = spotifyPositionAnchorMs + elapsed
                            val cap = state.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                            val advanced = projected.coerceIn(0L, cap)
                            _playbackState.value = state.copy(
                                position = advanced,
                                bufferedPosition = state.duration.takeIf { it > 0L } ?: advanced,
                            )
                        }
                    }

                    ActiveBackend.NONE -> Unit
                }
                delay(POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    private fun executeOrQueue(command: (MediaController) -> Unit) {
        val player = controller
        if (player != null) {
            command(player)
        } else {
            pendingCommands += command
            connectInBackground()
        }
    }

    private fun Player.applyPlayMode(mode: PlayMode) {
        repeatMode = mode.repeatMode
        shuffleModeEnabled = mode.shuffle
    }

    private fun flushPendingCommands(player: MediaController) {
        val commands = pendingCommands.toList()
        pendingCommands.clear()
        commands.forEach { it(player) }
    }

    private fun disconnectLocalController(resetState: Boolean) {
        pendingCommands.clear()
        connectJob?.cancel()
        connectJob = null
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        if (resetState) {
            positionUpdateJob?.cancel()
            positionUpdateJob = null
            _playbackState.value = PlaybackState(connectionPhase = ConnectionPhase.Idle)
        }
    }

    private fun publishRemoteState(snapshot: SpotifyRemoteSnapshot) {
        if (activeBackend != ActiveBackend.SPOTIFY_REMOTE && !pendingSpotifyHandoff) {
            // Warm-connect adoption: if we're idle, the active profile is
            // Spotify, and Spotify just pushed a real PlayerState (the
            // subscription fired with actual track data, connection phase
            // went Ready), claim ourselves the active backend so
            // NowPlaying can render what Spotify is playing externally.
            val canAdoptWarmConnect = activeBackend == ActiveBackend.NONE &&
                snapshot.observedPlayerState &&
                snapshot.currentTrack != null &&
                snapshot.connectionPhase == ConnectionPhase.Ready &&
                repository.currentProviderId() == MediaId.PROVIDER_SPOTIFY
            if (!canAdoptWarmConnect) return
            activeBackend = ActiveBackend.SPOTIFY_REMOTE
            // This track was started OUTSIDE the app (Spotify itself). Derive the
            // "playing from" context from Spotify's own PlayerContext (album /
            // playlist) so we show "Playing from X" instead of a bare label; fall
            // back to None for unrecognised contexts (artist radio, collection, …).
            // [onSpotifyPlayerContext] keeps it in sync as the external context changes.
            _currentActivityContext.value =
                deriveActivityContext(lastSpotifyContext) ?: ActivityContext.None
        }
        // Re-pin the position anchor on every real snapshot — any seek /
        // pause / resume / track change re-syncs to Spotify's authoritative
        // position and the ticker keeps interpolating from there.
        if (snapshot.observedPlayerState) {
            spotifyPositionAnchorMs = snapshot.positionMs
            spotifyPositionAnchorWallClock = System.currentTimeMillis()
        }
        val previous = _playbackState.value
        val next = PlaybackState(
            currentTrack = snapshot.currentTrack,
            pendingTrack = snapshot.pendingTrack,
            isPlaying = snapshot.isPlaying,
            position = snapshot.positionMs,
            duration = snapshot.durationMs,
            bufferedPosition = snapshot.durationMs.takeIf { it > 0L } ?: snapshot.positionMs,
            queue = snapshot.queue,
            currentIndex = snapshot.currentIndex,
            // Spotify's queue is read here, never rearranged (App Remote can't).
            upcoming = if (snapshot.currentIndex >= 0) {
                (snapshot.currentIndex + 1 until snapshot.queue.size).toList()
            } else {
                emptyList()
            },
            queueEdit = QueueEdit.None,
            repeatMode = snapshot.repeatMode,
            shuffleEnabled = snapshot.shuffleEnabled,
            audioSessionId = PlaybackService.audioSessionId.value,
            isCasting = previous.isCasting,
            castDeviceName = previous.castDeviceName,
            connectionPhase = snapshot.connectionPhase,
            connectionErrorMessage = snapshot.connectionErrorMessage,
            connectionFailure = snapshot.connectionFailure,
        )
        when (snapshot.connectionPhase) {
            ConnectionPhase.Ready -> {
                if (pendingSpotifyHandoff) {
                    pendingSpotifyHandoff = false
                    preserveLocalUiDuringSpotifyHandoff = false
                    disconnectLocalController(resetState = false)
                    activeBackend = ActiveBackend.SPOTIFY_REMOTE
                }
                publishPlaybackState(next)
                maybeEmitConnectFailure(previous, next)
                // Spotify's own state, adopted or Yoin-started alike: checks the
                // heart on a track change, rechecks now and then on later events.
                if (activeBackend == ActiveBackend.SPOTIFY_REMOTE && snapshot.observedPlayerState) {
                    savedStateRefresher.onPlayerState(snapshot.currentTrack)
                }
            }

            ConnectionPhase.Error -> {
                if (pendingSpotifyHandoff && preserveLocalUiDuringSpotifyHandoff && controller != null) {
                    pendingSpotifyHandoff = false
                    preserveLocalUiDuringSpotifyHandoff = false
                    activeBackend = ActiveBackend.LOCAL
                    maybeEmitConnectFailure(previous, next)
                    syncState()
                    return
                }
                pendingSpotifyHandoff = false
                preserveLocalUiDuringSpotifyHandoff = false
                publishPlaybackState(next)
                maybeEmitConnectFailure(previous, next)
            }

            ConnectionPhase.Connecting -> {
                if (pendingSpotifyHandoff && preserveLocalUiDuringSpotifyHandoff && controller != null) {
                    return
                }
                publishPlaybackState(next)
                maybeEmitConnectFailure(previous, next)
            }

            ConnectionPhase.Idle -> {
                publishPlaybackState(next)
                maybeEmitConnectFailure(previous, next)
            }
        }
    }

    /**
     * Emit a one-shot [PlaybackEvent.SpotifyConnectError] when the remote
     * connection transitions into an error state (or changes to a different
     * failure kind). Doesn't emit on steady-state error re-publishes so the
     * shell snackbar doesn't thrash.
     */
    private fun maybeEmitConnectFailure(previous: PlaybackState, next: PlaybackState) {
        if (next.connectionPhase != ConnectionPhase.Error) return
        val failure = next.connectionFailure ?: return
        val sameAsBefore = previous.connectionPhase == ConnectionPhase.Error &&
            previous.connectionFailure == failure
        if (sameAsBefore) return
        val message = next.connectionErrorMessage ?: failure.userMessage(context.resources)
        scope.launch {
            _events.emit(PlaybackEvent.SpotifyConnectError(failure = failure, message = message))
        }
    }

    private fun emitSpotifyActionRequired(failure: SpotifyConnectFailure, message: String) {
        scope.launch {
            _events.emit(PlaybackEvent.SpotifyActionRequired(failure = failure, message = message))
        }
    }

    private fun publishPlaybackState(state: PlaybackState) {
        _playbackState.value = state
        if (state.isPlaying) {
            startPositionUpdates()
        } else {
            stopPositionUpdates()
        }

        val track = state.currentTrack ?: return
        if (track.id == lastRecordedTrackId || !state.isPlaying) return
        lastRecordedTrackId = track.id
        val fallbackDurationSec = track.durationSec ?: lastKnownDurationSecById[track.id]
        scope.launch {
            // Losing one history row must never take playback down with it
            // (scope has no CoroutineExceptionHandler — an uncaught Room
            // failure here would crash the app on track change).
            runCatching {
                repository.recordPlay(
                    track = track,
                    durationMs = state.duration.coerceAtLeast(0L).takeIf { it > 0L }
                        ?: ((fallbackDurationSec ?: 0) * 1_000L),
                    completedPercent = 0f,
                    activityContext = _currentActivityContext.value,
                )
            }.onFailure { error ->
                Log.w(TAG, "recordPlay failed for ${track.id}", error)
            }
        }
    }

    /**
     * Build a Media3 [MediaItem] for a [Track], routing through the owning
     * [MusicSource]'s [PlaybackHandle]. Subsonic returns [DirectStream] and
     * goes through Media3 directly. External-controller providers are handled
     * before this method is called.
     */
    private suspend fun buildMediaItem(track: Track, source: MusicSource, userQueued: Boolean = false): MediaItem {
        val handle = source.playback().handleFor(track)
        val streamUrl = when (handle) {
            is PlaybackHandle.DirectStream -> handle.uri
            is PlaybackHandle.ExternalController -> {
                require(handle.type == PlaybackHandle.ControllerType.APPLE_MUSIC_KIT)
                null // DRM streams never become URLs or enter ExoPlayer's cache.
            }
        }
        val artworkUri = track.coverArt
            ?.let { ref -> source.resolveCoverUrl(ref) }
            ?.let(Uri::parse)

        val extras = Bundle().apply {
            if (handle is PlaybackHandle.ExternalController && handle.type == PlaybackHandle.ControllerType.APPLE_MUSIC_KIT) {
                putString("appleMusicCatalogId", handle.payload as String)
            }
            putString(EXTRA_MEDIA_ID, track.id.toString())
            putString(EXTRA_PROVIDER, track.id.provider)
            putString(EXTRA_ARTIST_ID, track.artistId?.toString())
            putString(EXTRA_ALBUM_ID, track.albumId?.toString())
            putString(EXTRA_COVER_ART, (track.coverArt as? CoverRef.SourceRelative)?.coverArtId)
            putString(EXTRA_COVER_URL, (track.coverArt as? CoverRef.Url)?.url)
            track.durationSec?.let { putInt(EXTRA_DURATION, it) }
            track.trackNumber?.let { putInt(EXTRA_TRACK, it) }
            track.year?.let { putInt(EXTRA_YEAR, it) }
            putString(EXTRA_GENRE, track.genre)
            putBoolean(EXTRA_STARRED, track.isStarred)
            track.userRating?.let { putInt(EXTRA_USER_RATING, it) }
            // The queue sheet's handles on this entry: a stable key through
            // moves, and whether the user added it (Spotify's "Next in queue").
            putString(EXTRA_QUEUE_ENTRY, java.util.UUID.randomUUID().toString())
            if (userQueued) putBoolean(EXTRA_USER_QUEUED, true)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(artworkUri)
            .setTrackNumber(track.trackNumber)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(streamUrl)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun MediaItem.toTrack(): Track {
        val extras = mediaMetadata.extras
        val idString = extras?.getString(EXTRA_MEDIA_ID) ?: mediaId
        val id = MediaId.parseOrNull(idString)
            ?: MediaId.subsonic(mediaId)  // legacy session fallback
        val coverRef: CoverRef? = extras?.getString(EXTRA_COVER_URL)?.let(CoverRef::Url)
            ?: extras?.getString(EXTRA_COVER_ART)?.let(CoverRef::SourceRelative)
        return Track(
            id = id,
            title = mediaMetadata.title?.toString(),
            artist = mediaMetadata.artist?.toString(),
            artistId = extras?.getString(EXTRA_ARTIST_ID)?.let(MediaId::parseOrNull),
            album = mediaMetadata.albumTitle?.toString(),
            albumId = extras?.getString(EXTRA_ALBUM_ID)?.let(MediaId::parseOrNull),
            coverArt = coverRef,
            durationSec = extras?.takeIf { it.containsKey(EXTRA_DURATION) }?.getInt(EXTRA_DURATION),
            trackNumber = extras?.takeIf { it.containsKey(EXTRA_TRACK) }?.getInt(EXTRA_TRACK),
            year = extras?.takeIf { it.containsKey(EXTRA_YEAR) }?.getInt(EXTRA_YEAR),
            genre = extras?.getString(EXTRA_GENRE),
            userRating = extras?.takeIf { it.containsKey(EXTRA_USER_RATING) }
                ?.getInt(EXTRA_USER_RATING),
            isStarred = extras?.getBoolean(EXTRA_STARRED, false) == true,
        )
    }

    /**
     * The Web API start for a Spotify queue (see [spotifyStartAttempts]):
     * tries each attempt best first and throws the last failure when none
     * works, so [SpotifyAppRemotePlayer.playQueue] falls back to App Remote.
     * `null` when the source isn't Spotify or there is nothing to try.
     */
    private fun buildSpotifyStartFn(
        source: MusicSource,
        activityContext: ActivityContext,
        tracks: List<Track>,
        startIndex: Int,
        shuffled: Boolean,
    ): (suspend () -> Unit)? {
        val spotifySource = source as? SpotifyMusicSource ?: return null
        val attempts = spotifyStartAttempts(
            activityContext = activityContext,
            tracks = tracks,
            startIndex = startIndex,
            shuffled = shuffled,
            playlistOffset = { id, index -> spotifySource.resolvePlaylistContextOffset(id, index) },
        )
        if (attempts.isEmpty()) return null
        return {
            val deviceId = runCatching { spotifySource.localDeviceId(localDeviceNames()) }
                .onFailure { e -> if (e is CancellationException) throw e }
                .getOrNull()
            if (deviceId == null) Log.w(TAG, "Spotify: this device isn't in the Connect list; the active device plays")
            var failure: Throwable? = null
            val started = attempts.any { attempt ->
                runCatching { spotifySource.start(attempt, deviceId) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Spotify start $attempt failed: ${e.javaClass.simpleName}: ${e.message}")
                        failure = e
                    }
                    .isSuccess
            }
            if (!started) throw failure ?: IllegalStateException("No Spotify start succeeded")
        }
    }

    private fun ActivityContext.isSpotifyContext(): Boolean =
        this is ActivityContext.Album || this is ActivityContext.Playlist || this is ActivityContext.LikedSongs

    private suspend fun SpotifyMusicSource.start(attempt: SpotifyStartAttempt, deviceId: String?) = when (attempt) {
        is SpotifyStartAttempt.Context -> startPlayback(
            contextUri = attempt.contextUri,
            offsetUri = attempt.offsetUri,
            offsetPosition = attempt.offsetPosition,
            deviceId = deviceId,
        )
        is SpotifyStartAttempt.LikedSongs -> startPlayback(
            contextUri = SPOTIFY_LIKED_SONGS_CONTEXT_URI,
            offsetUri = attempt.offsetUri,
            deviceId = deviceId,
        )
        is SpotifyStartAttempt.Tracks -> startPlayback(
            uris = attempt.uris,
            offsetPosition = attempt.offsetPosition,
            deviceId = deviceId,
        )
    }

    /** Names Spotify may list this phone under: the user-set device name, then the model. */
    private fun localDeviceNames(): List<String> = listOfNotNull(
        runCatching {
            android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        }.getOrNull(),
        android.os.Build.MODEL,
    )

    /** App Remote's answer whether [uri] is in Liked Songs; null when it can't give one. */
    private suspend fun appRemoteLibraryState(uri: String): Boolean? {
        val perf = YoinPerf.begin("favorite.check")
        val state = spotifyRemotePlayer.libraryState(uri)
        YoinPerf.end(
            perf,
            "src" to "appRemote",
            "ok" to state.isSuccess,
            "err" to state.exceptionOrNull()?.javaClass?.simpleName
        )
        return state.getOrNull()?.isAdded
    }

    /**
     * The debug library-state probe (`app/src/debug` LibraryStateProbeReceiver,
     * docs/perf/yoinperf-logging.md): connects App Remote when it isn't —
     * plays nothing, leaves the queue alone, and is closed again after the
     * reads where nothing else wanted it ([SpotifyAppRemotePlayer.withProbeConnection])
     * — and reads Spotify's library state for each of [uris], one after another.
     */
    internal suspend fun probeSpotifyLibraryStates(
        uris: List<String>,
        connectTimeoutMs: Long
    ): SpotifyLibraryStateProbe {
        val connectStart = SystemClock.elapsedRealtime()
        return spotifyRemotePlayer.withProbeConnection(connectTimeoutMs) { connection ->
            val connectMs = SystemClock.elapsedRealtime() - connectStart
            val providerId = repository.currentProviderId()
            if (connection != SpotifyAppRemotePlayer.ProbeConnection.Connected) {
                return@withProbeConnection SpotifyLibraryStateProbe(connection, connectMs, emptyList(), providerId)
            }
            val readings = uris.map { uri ->
                val start = SystemClock.elapsedRealtime()
                val state = spotifyRemotePlayer.libraryState(uri)
                SpotifyLibraryStateReading(
                    uri = uri,
                    isAdded = state.getOrNull()?.isAdded,
                    canAdd = state.getOrNull()?.canAdd,
                    elapsedMs = SystemClock.elapsedRealtime() - start,
                    error = state.exceptionOrNull()
                )
            }
            SpotifyLibraryStateProbe(connection, connectMs, readings, providerId)
        }
    }

    /**
     * Spotify reported a new "playing from" context (album / playlist / …). When we're
     * playing externally-started Spotify content, derive an [ActivityContext] so Now
     * Playing shows "Playing from X" instead of a bare "Now Playing".
     *
     * Skipped while a local play() is handing off to Spotify (pendingSpotifyHandoff):
     * play() already set a RICH context (with cover/artist) and we keep it; we also
     * keep it whenever Spotify's context URI matches the current in-app context.
     */
    private fun onSpotifyPlayerContext(context: SpotifyPlaybackContext?) {
        lastSpotifyContext = context
        if (pendingSpotifyHandoff) return
        if (context?.uri == activityContextUri(_currentActivityContext.value)) return
        _currentActivityContext.value = deriveActivityContext(context) ?: ActivityContext.None
    }

    /** URI of the context an [ActivityContext] represents, to compare against Spotify's. */
    private fun activityContextUri(context: ActivityContext): String? = when (context) {
        is ActivityContext.Album -> MediaId.parseOrNull(context.albumId)
            ?.takeIf { it.provider == MediaId.PROVIDER_SPOTIFY }
            ?.let { "spotify:album:${it.rawId}" }
        is ActivityContext.Playlist -> MediaId.parseOrNull(context.playlistId)
            ?.takeIf { it.provider == MediaId.PROVIDER_SPOTIFY }
            ?.let { "spotify:playlist:${it.rawId}" }
        else -> null
    }

    /** Map a Spotify "playing from" context to an [ActivityContext]; null if unrecognised. */
    private fun deriveActivityContext(context: SpotifyPlaybackContext?): ActivityContext? {
        val uri = context?.uri ?: return null
        val title = (context.title?.takeIf { it.isNotBlank() }).orEmpty()
        return when {
            uri.startsWith("spotify:album:") ->
                uri.removePrefix("spotify:album:").takeIf { it.isNotBlank() }?.let {
                    ActivityContext.Album(albumId = MediaId.spotify(it).toString(), albumName = title)
                }
            uri.startsWith("spotify:playlist:") ->
                uri.removePrefix("spotify:playlist:").takeIf { it.isNotBlank() }?.let {
                    ActivityContext.Playlist(playlistId = MediaId.spotify(it).toString(), playlistName = title)
                }
            else -> null
        }
    }

    private fun selectMedia3Source(source: MusicSource) {
        requestedMedia3Source = source
        val usesMusicKit = source.id == MediaId.PROVIDER_APPLE_MUSIC
        if ((controller != null || connectJob?.isActive == true) && controllerUsesMusicKit != usesMusicKit) {
            controller?.stop()
            disconnectLocalController(resetState = true)
        }
    }

    private suspend fun buildController(): MediaController {
        val source = requestedMedia3Source
            ?: (context.applicationContext as com.gpo.yoin.YoinApplication).container.profileManager.activeSource.value
        val apple = source as? com.gpo.yoin.data.source.applemusic.AppleMusicSource
        controllerUsesMusicKit = apple != null
        apple?.refreshPlaybackToken()
        val serviceClass = if (apple != null) {
            com.gpo.yoin.player.applemusic.AppleMusicPlaybackService::class.java
        } else {
            PlaybackService::class.java
        }
        val sessionToken = SessionToken(context, ComponentName(context, serviceClass))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        return suspendCancellableCoroutine { continuation ->
            // If connectJob is cancelled (profile switch / Spotify handoff)
            // while the controller is still being built, releaseFuture both
            // cancels a pending build and releases an already-built
            // controller — otherwise the orphan keeps PlaybackService bound
            // forever. The resume onCancellation lambda covers the remaining
            // race: cancellation landing after resume but before the
            // coroutine consumes the value. releaseFuture is idempotent.
            continuation.invokeOnCancellation { MediaController.releaseFuture(future) }
            Futures.addCallback(
                future,
                object : FutureCallback<MediaController> {
                    override fun onSuccess(result: MediaController) {
                        continuation.resume(result) { _, _, _ ->
                            MediaController.releaseFuture(future)
                        }
                    }

                    override fun onFailure(t: Throwable) {
                        continuation.resumeWithException(t)
                    }
                },
                MoreExecutors.directExecutor(),
            )
        }
    }

    companion object {
        private const val TAG = "PlaybackManager"

        private const val POSITION_UPDATE_INTERVAL_MS = 250L

        private const val EXTRA_MEDIA_ID = "media_id"
        private const val EXTRA_PROVIDER = "provider"
        private const val EXTRA_ALBUM_ID = "album_id"
        private const val EXTRA_ARTIST_ID = "artist_id"
        private const val EXTRA_COVER_ART = "cover_art"
        private const val EXTRA_COVER_URL = "cover_url"
        private const val EXTRA_DURATION = "duration_secs"
        private const val EXTRA_TRACK = "track"
        private const val EXTRA_YEAR = "year"
        private const val EXTRA_GENRE = "genre"
        private const val EXTRA_STARRED = "starred"
        private const val EXTRA_USER_RATING = "user_rating"
        private const val EXTRA_QUEUE_ENTRY = "yoin_queue_entry"
        private const val EXTRA_USER_QUEUED = "yoin_user_queued"
    }
}

/**
 * What the debug library-state probe found: how App Remote's connection
 * went, then one reading per URI. [activeProviderId]: the active account's
 * service as the probe ran (null: none).
 */
internal data class SpotifyLibraryStateProbe(
    val connection: SpotifyAppRemotePlayer.ProbeConnection,
    val connectMs: Long,
    val readings: List<SpotifyLibraryStateReading>,
    val activeProviderId: String? = null
) {
    /**
     * The probe's notes on the connection, one fact a line, for its log. An
     * account that isn't Spotify's is said on its own line: it doesn't stop
     * the probe, which connects App Remote for itself on any account. Nor
     * does it explain a missing client id — that is the app's one setting,
     * not the account's.
     */
    fun connectionNotes(connectTimeoutMs: Long): List<String> = buildList {
        if (activeProviderId != MediaId.PROVIDER_SPOTIFY) {
            add(
                "active account is ${activeProviderId ?: "none"}, not Spotify: " +
                    "App Remote connects for the probe alone and closes after it"
            )
        }
        when (connection) {
            SpotifyAppRemotePlayer.ProbeConnection.Connected -> Unit
            SpotifyAppRemotePlayer.ProbeConnection.NoClientId -> add(
                "no Spotify client id configured: set one in Settings › Spotify (one for the app, not per account)"
            )
            SpotifyAppRemotePlayer.ProbeConnection.NoHost ->
                add("App Remote did not connect: open Yoin (an Activity must be started) and retry")
            SpotifyAppRemotePlayer.ProbeConnection.TimedOut ->
                add("App Remote did not connect within ${connectTimeoutMs / 1_000} s: open Spotify and retry")
        }
    }
}

/** One App Remote `getLibraryState` read: its answer, or the [error] it failed with. */
internal data class SpotifyLibraryStateReading(
    val uri: String,
    val isAdded: Boolean?,
    val canAdd: Boolean?,
    val elapsedMs: Long,
    val error: Throwable?
)

/**
 * The window indices of [timeline] that play after [current], in play order
 * ([shuffle] follows the timeline's shuffle order), without wrapping: the
 * queue sheet's "next" list.
 */
internal fun upcomingOrder(timeline: Timeline, current: Int, shuffle: Boolean): List<Int> {
    if (current < 0 || current >= timeline.windowCount) return emptyList()
    val order = mutableListOf<Int>()
    var index = timeline.getNextWindowIndex(current, Player.REPEAT_MODE_OFF, shuffle)
    while (index != C.INDEX_UNSET && order.size < timeline.windowCount) {
        order += index
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
    }
    return order
}

