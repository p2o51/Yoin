package com.gpo.yoin.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.PlaybackHandle
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.spotify.SpotifyMusicSource
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
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
import kotlin.coroutines.resume
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionUpdateJob: Job? = null
    private var connectJob: Job? = null
    private val pendingCommands = mutableListOf<(MediaController) -> Unit>()
    private var lastRecordedTrackId: MediaId? = null
    private var currentActivityContext: ActivityContext = ActivityContext.None
    private var lastKnownDurationSecById: Map<MediaId, Int> = emptyMap()
    private var activeBackend: ActiveBackend = ActiveBackend.NONE
    private var pendingSpotifyHandoff: Boolean = false
    private var preserveLocalUiDuringSpotifyHandoff: Boolean = false

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
                castManager?.setLocalPlayerProvider { controller }
                syncState()
                startPositionUpdates()
                flushPendingCommands(built)
            } catch (error: Throwable) {
                _playbackState.value = _playbackState.value.copy(
                    connectionPhase = ConnectionPhase.Error,
                    connectionErrorMessage = error.message ?: "Unable to initialize playback",
                )
            } finally {
                connectJob = null
            }
        }
    }

    fun onHostStart(hostContext: Context) {
        spotifyRemotePlayer.onHostStart(hostContext)
        // Host just came up — if Spotify is already active, the init-time
        // collector may have fired before hostContext was set, so re-issue
        // the warm connection now. `warmConnection` is idempotent.
        if (repository.currentProviderId() == MediaId.PROVIDER_SPOTIFY) {
            spotifyRemotePlayer.warmConnection()
        }
    }

    fun onHostStop() {
        spotifyRemotePlayer.onHostStop()
    }

    fun disconnect() {
        spotifyRemotePlayer.disconnect(resetState = false)
        activeBackend = ActiveBackend.NONE
        pendingSpotifyHandoff = false
        preserveLocalUiDuringSpotifyHandoff = false
        pendingCommands.clear()
        connectJob?.cancel()
        connectJob = null
        positionUpdateJob?.cancel()
        positionUpdateJob = null
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        _playbackState.value = PlaybackState(connectionPhase = ConnectionPhase.Idle)
    }

    // ── Playback controls ─────────────────────────────────────────────

    fun play(
        tracks: List<Track>,
        startIndex: Int = 0,
        source: MusicSource,
        activityContext: ActivityContext = ActivityContext.None,
    ) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        lastRecordedTrackId = null
        currentActivityContext = activityContext
        lastKnownDurationSecById = tracks
            .mapNotNull { track -> track.durationSec?.let { track.id to it } }
            .toMap()
        scope.launch {
            when (source.playback().handleFor(tracks[startIndex])) {
                is PlaybackHandle.DirectStream -> {
                    pendingSpotifyHandoff = false
                    preserveLocalUiDuringSpotifyHandoff = false
                    activeBackend = ActiveBackend.LOCAL
                    spotifyRemotePlayer.disconnect(resetState = false)
                    val items = tracks.map { buildMediaItem(it, source) }
                    executeOrQueue { player ->
                        player.setMediaItems(items, startIndex, 0L)
                        player.prepare()
                        player.play()
                    }
                }

                is PlaybackHandle.ExternalController -> {
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
                    val startContextPlayback = buildSpotifyContextPlaybackFn(
                        source = source,
                        activityContext = activityContext,
                        startIndex = startIndex,
                    )
                    spotifyRemotePlayer.playQueue(tracks, startIndex, startContextPlayback)
                }
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
            else -> executeOrQueue { player ->
                if (player.hasPreviousMediaItem()) {
                    player.seekToPreviousMediaItem()
                }
            }
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

    fun toggleShuffle() {
        when (activeBackend) {
            ActiveBackend.SPOTIFY_REMOTE -> spotifyRemotePlayer.toggleShuffle()
            else -> executeOrQueue { player ->
                player.shuffleModeEnabled = !player.shuffleModeEnabled
            }
        }
    }

    // ── Queue management ──────────────────────────────────────────────

    fun addToQueue(track: Track, source: MusicSource) {
        scope.launch {
            when (source.playback().handleFor(track)) {
                is PlaybackHandle.DirectStream -> {
                    pendingSpotifyHandoff = false
                    preserveLocalUiDuringSpotifyHandoff = false
                    val item = buildMediaItem(track, source)
                    executeOrQueue { player -> player.addMediaItem(item) }
                }

                is PlaybackHandle.ExternalController -> {
                    activeBackend = ActiveBackend.SPOTIFY_REMOTE
                    disconnectLocalController(resetState = false)
                    spotifyRemotePlayer.addToQueue(track)
                }
            }
        }
    }

    fun clearQueue() {
        when (activeBackend) {
            ActiveBackend.SPOTIFY_REMOTE -> spotifyRemotePlayer.clearQueue()
            else -> executeOrQueue { it.clearMediaItems() }
        }
    }

    fun skipToQueueItem(index: Int) {
        lastRecordedTrackId = null
        when (activeBackend) {
            ActiveBackend.SPOTIFY_REMOTE -> spotifyRemotePlayer.skipToQueueItem(index)
            else -> executeOrQueue { player ->
                if (index in 0 until player.mediaItemCount) {
                    player.seekToDefaultPosition(index)
                }
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncState()
            if (isPlaying) startPositionUpdates() else stopPositionUpdates()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
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
                repeatMode = player.repeatMode,
                shuffleEnabled = player.shuffleModeEnabled,
                audioSessionId = PlaybackService.audioSessionId.value,
                isCasting = _playbackState.value.isCasting,
                castDeviceName = _playbackState.value.castDeviceName,
                connectionPhase = ConnectionPhase.Ready,
                connectionErrorMessage = null,
            ),
        )
    }

    private fun startPositionUpdates() {
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
        val message = next.connectionErrorMessage ?: failure.userMessage()
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
            repository.recordPlay(
                track = track,
                durationMs = state.duration.coerceAtLeast(0L).takeIf { it > 0L }
                    ?: ((fallbackDurationSec ?: 0) * 1_000L),
                completedPercent = 0f,
                activityContext = currentActivityContext,
            )
        }
    }

    /**
     * Build a Media3 [MediaItem] for a [Track], routing through the owning
     * [MusicSource]'s [PlaybackHandle]. Subsonic returns [DirectStream] and
     * goes through Media3 directly. External-controller providers are handled
     * before this method is called.
     */
    private suspend fun buildMediaItem(track: Track, source: MusicSource): MediaItem {
        val handle = source.playback().handleFor(track)
        val streamUrl = when (handle) {
            is PlaybackHandle.DirectStream -> handle.uri
            is PlaybackHandle.ExternalController ->
                throw UnsupportedOperationException(
                    "ExternalController playback lands in phase 3 (Spotify App Remote)",
                )
        }
        val artworkUri = track.coverArt
            ?.let { ref -> source.resolveCoverUrl(ref) }
            ?.let(Uri::parse)

        val extras = Bundle().apply {
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
     * Translate an [ActivityContext] + owning [source] into a suspend lambda
     * that calls Spotify Web API's `PUT /me/player/play` preserving the
     * playback context. Returns `null` when:
     * - the source isn't Spotify (Subsonic has its own end-to-end queue);
     * - the context is `None` (bare-track entry points — search, queue tap,
     *   memories single); the non-context App Remote path is correct here;
     * - the context id isn't in Spotify's provider namespace (shouldn't
     *   happen when source is Spotify, but defensive for mixed-provider
     *   futures).
     *
     * `spotify:artist:...` isn't wired — Spotify's Web API treats artist
     * context as "artist radio", not "top tracks in order", so offsets
     * don't align with our `tracks` list. Drop to the App Remote path.
     */
    private fun buildSpotifyContextPlaybackFn(
        source: MusicSource,
        activityContext: ActivityContext,
        startIndex: Int,
    ): (suspend () -> Unit)? {
        val spotifySource = source as? SpotifyMusicSource ?: return null
        val contextUri: String
        val offsetPosition: Int
        when (activityContext) {
            is ActivityContext.Album -> {
                val albumId = MediaId.parseOrNull(activityContext.albumId)
                    ?.takeIf { it.provider == MediaId.PROVIDER_SPOTIFY }
                    ?: return null
                contextUri = "spotify:album:${albumId.rawId}"
                offsetPosition = startIndex
            }

            is ActivityContext.Playlist -> {
                val playlistId = MediaId.parseOrNull(activityContext.playlistId)
                    ?.takeIf { it.provider == MediaId.PROVIDER_SPOTIFY }
                    ?: return null
                offsetPosition = spotifySource.resolvePlaylistContextOffset(
                    playlistId = playlistId,
                    visibleStartIndex = startIndex,
                )
                    // If we cannot prove the raw playlist offset, drop back to
                    // the old App Remote queue path rather than risking the
                    // wrong song starting from a filtered playlist.
                    ?: return null
                contextUri = "spotify:playlist:${playlistId.rawId}"
            }

            is ActivityContext.Artist, ActivityContext.None -> return null
        }
        return {
            spotifySource.startContextPlayback(
                contextUri = contextUri,
                offsetPosition = offsetPosition,
            )
        }
    }

    private suspend fun buildController(): MediaController {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        return suspendCancellableCoroutine { continuation ->
            Futures.addCallback(
                future,
                object : FutureCallback<MediaController> {
                    override fun onSuccess(result: MediaController) {
                        continuation.resume(result)
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
    }
}
