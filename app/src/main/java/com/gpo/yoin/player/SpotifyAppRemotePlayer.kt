package com.gpo.yoin.player

import android.content.Context
import android.content.res.Resources
import android.util.Log
import androidx.media3.common.Player
import com.gpo.yoin.R
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.spotify.EXTRA_SPOTIFY_URI_TYPE
import com.gpo.yoin.data.source.spotify.SpotifyAuthConfig
import com.gpo.yoin.data.source.spotify.SpotifyAuthException
import com.gpo.yoin.data.source.spotify.SpotifyPlayerQueue
import com.gpo.yoin.data.source.spotify.spotifyUriType
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.AuthenticationFailedException
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.SpotifyDisconnectedException
import com.spotify.android.appremote.api.error.SpotifyRemoteServiceException
import com.spotify.android.appremote.api.error.UnsupportedFeatureVersionException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.PendingResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Empty
import com.spotify.protocol.types.LibraryState
import com.spotify.protocol.types.PlayerContext
import com.spotify.protocol.types.PlayerState
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal data class SpotifyRemoteSnapshot(
    val currentTrack: Track? = null,
    /** Track the caller asked to play; held until first real PlayerState arrives. */
    val pendingTrack: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    /** Entries of [queue] the user added; see [SpotifyQueueView]. */
    val userQueued: Set<Int> = emptySet(),
    /** Entries of [queue] still to play, in Spotify's order; see [SpotifyQueueView]. */
    val upcoming: List<Int> = emptyList(),
    /** The entry Spotify plays from outside Yoin's queue, -1 for none; see [SpotifyQueueView]. */
    val foreignIndex: Int = -1,
    val repeatMode: Int = Player.REPEAT_MODE_ALL,
    val shuffleEnabled: Boolean = false,
    val connectionPhase: ConnectionPhase = ConnectionPhase.Idle,
    val connectionErrorMessage: String? = null,
    val connectionFailure: SpotifyConnectFailure? = null,
    val observedPlayerState: Boolean = false,
    /**
     * The other Spotify Connect device Spotify plays on (a speaker, a computer), shown on Now
     * Playing; null when it plays on this phone or Yoin doesn't know where.
     */
    val playingOn: String? = null,
) {
    /** [queue] without the song from outside it ([foreignIndex]): the queue Yoin handed Spotify. */
    val yoinQueue: List<Track>
        get() = if (foreignIndex in queue.indices) queue.filterIndexed { i, _ -> i != foreignIndex } else queue
}

/** A queue-sheet tap's restart ([SpotifyAppRemotePlayer.restartAt]): [tracks] from [startIndex], [queued] next. */
internal data class SpotifyRestart(
    val tracks: List<Track>,
    val startIndex: Int,
    val queued: List<Track>,
    /** [startIndex] is no position in the album or playlist: start at the song's uri. */
    val byUri: Boolean = false,
)

/** What Spotify is currently playing FROM (album / playlist / …), used to show a
 *  "Playing from X" label when we adopt externally-started playback. */
internal data class SpotifyPlaybackContext(
    val uri: String?,
    val title: String?,
)

/**
 * Typed failure for the App Remote connection lifecycle. Maps 1:1 to an
 * actionable UX — each shell snackbar action and each `Now Playing` error
 * rendering keys off this instead of a freeform string.
 */
sealed interface SpotifyConnectFailure {
    /** User hasn't entered a Spotify Developer Client ID yet. */
    data object NoClientId : SpotifyConnectFailure

    /** The Spotify app is not installed on the device. */
    data object SpotifyAppMissing : SpotifyConnectFailure

    /** Spotify explicitly reported that App Remote playback needs Premium. */
    data object PremiumRequired : SpotifyConnectFailure

    /** Auth handshake failed (not logged in, token revoked, etc). */
    data class AuthFailure(val message: String) : SpotifyConnectFailure

    /** Network / IPC / offline-mode / version mismatch. */
    data class TransportFailure(val message: String) : SpotifyConnectFailure

    /**
     * The Spotify app didn't answer a start the user is waiting on (asleep in
     * the background, or closed), and no other device plays Spotify either.
     * Opening it wakes App Remote; the start then runs when Yoin is back,
     * unless Spotify is already playing something.
     */
    data object SpotifyAsleep : SpotifyConnectFailure

    fun userMessage(resources: Resources? = null): String = when (this) {
        NoClientId -> resources?.getString(R.string.player_spotify_no_client_id)
            ?: "Open Settings → Spotify to enter a Client ID."
        SpotifyAppMissing -> resources?.getString(R.string.player_spotify_app_missing)
            ?: "Install the Spotify app to play Spotify tracks."
        PremiumRequired -> resources?.getString(R.string.player_spotify_premium_required)
            ?: "Spotify Premium is required for in-app playback." // i18n-allow: SpotifyTypedFailureMappingTest asserts this English
        is AuthFailure -> message.ifBlank {
            resources?.getString(R.string.player_spotify_auth_failed)
                ?: "Spotify authorization failed."
        }
        is TransportFailure -> message.ifBlank {
            resources?.getString(R.string.player_spotify_connection_lost)
                ?: "Spotify playback connection was lost."
        }
        SpotifyAsleep -> resources?.getString(R.string.player_spotify_asleep)
            ?: "Can't reach Spotify. Open it, come back, and this song plays."
    }
}

/**
 * Spotify Connect through the Web API, for when Spotify plays on another device and App Remote
 * can't reach the Spotify app on this phone: where and what it plays, and the commands that
 * drive that device. Null state: no device is active.
 */
internal interface SpotifyConnectWeb {
    suspend fun state(): com.gpo.yoin.data.source.spotify.SpotifyConnectState?

    suspend fun command(command: com.gpo.yoin.data.source.spotify.SpotifyConnectCommand, deviceId: String?)
}

/**
 * How [SpotifyAppRemotePlayer] reaches App Remote: the SDK (through
 * [SpotifyAppRemoteCompat]'s package lookup) in the app, a fake in tests.
 */
internal interface AppRemoteConnector {
    fun connect(context: Context, params: ConnectionParams, listener: Connector.ConnectionListener)

    fun disconnect(remote: SpotifyAppRemote)

    object Sdk : AppRemoteConnector {
        override fun connect(context: Context, params: ConnectionParams, listener: Connector.ConnectionListener) =
            SpotifyAppRemoteCompat.connect(context, params, listener)

        override fun disconnect(remote: SpotifyAppRemote) = SpotifyAppRemote.disconnect(remote)
    }
}

internal class SpotifyAppRemotePlayer(
    private val applicationContext: Context,
    private val clientIdProvider: () -> String,
    private val onSnapshot: (SpotifyRemoteSnapshot) -> Unit,
    private val onActionRequired: (SpotifyConnectFailure, String) -> Unit = { _, _ -> },
    private val onContext: (SpotifyPlaybackContext?) -> Unit = {},
    private val appRemote: AppRemoteConnector = AppRemoteConnector.Sdk,
    /** Spotify's queue through the Web API ([SpotifyQueueMirror.syncUserQueue]); null when there's no way to read it. */
    private val queueReader: suspend () -> SpotifyPlayerQueue? = { null },
    /**
     * Spotify Connect through the Web API ([SpotifyConnectWeb]): where Spotify plays, and the
     * commands for another device. Null where there is no way to reach it (tests, no account).
     */
    private val connectWeb: SpotifyConnectWeb? = null,
    /** The Spotify app's own launcher entry ([SpotifyAppRemoteCompat.launchIntent]); null when there's none. */
    private val spotifyLaunchIntent: (Context) -> android.content.Intent? = SpotifyAppRemoteCompat::launchIntent,
) {
    private val tag = "SpotifyAppRemotePlayer"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var hostContext: Context? = null
    private var remote: SpotifyAppRemote? = null
    private var playerStateSubscription: Subscription<PlayerState>? = null
    private var playerContextSubscription: Subscription<PlayerContext>? = null
    private var connectJob: Job? = null
    private var replaceableOperationJob: Job? = null
    private var queueSyncJob: Job? = null

    /** The song Spotify's queue was last taken for while following playback Yoin didn't start. */
    private var adoptedFor: MediaId? = null

    /** The song a queue read in flight is for; a failed or discarded read leaves [adoptedFor] alone. */
    private var readingFor: MediaId? = null
    private var wantsConnection: Boolean = false
    private val mirror = SpotifyQueueMirror()
    private val mirroredQueue: List<Track> get() = mirror.tracks

    /**
     * The mode a Yoin start writes once Spotify plays it ([applyPlayMode]), shown from the
     * start's first state on: Spotify's first state still has the mode from before the start,
     * and the button used to flash back to it (Gemini review 2026-10-20).
     */
    private var playModeWrite: PlayModeWrite? = null

    private class PlayModeWrite(val mode: PlayMode)
    private var lastSnapshot: SpotifyRemoteSnapshot = SpotifyRemoteSnapshot()
    private val pendingOperations = mutableListOf<suspend (SpotifyAppRemote) -> Unit>()

    // A superseded start releases its cancelled HTTP call before the next start is sent.
    private val startMutex = Mutex()
    private var pendingStart: PendingStart? = null

    private class PendingStart(
        val track: Track,
        /** The Web API start, on a device id (null: this phone); null when the start has none. */
        val webStart: (suspend (deviceId: String?) -> Unit)? = null,
    ) {
        val heard = CompletableDeferred<Unit>()
        var commandSent = false
    }

    /**
     * Budget for silently retrying a cold-start [UserNotAuthorizedException].
     *
     * On a fresh app launch the first `SpotifyAppRemote.connect(...)` call
     * sometimes returns `UserNotAuthorizedException` *even with
     * `showAuthView(true)`*: the SDK pops the auth view, Spotify records
     * consent, but our first continuation has already resumed with the
     * exception. The next connect then succeeds because consent is
     * cached. Auto-retrying once makes the user's first tap on a track
     * "just work" instead of requiring them to dismiss a fake error and
     * tap again. Budget refills on any successful connect + on host stop
     * so every app-foreground cycle gets a fresh attempt.
     */
    private var coldStartAuthRetryAvailable: Boolean = true

    /**
     * Budget for silently retrying when the client id flow hasn't emitted
     * its stored value yet. `spotifyClientIdFlow` in AppContainer starts
     * with `FALLBACK_CLIENT_ID` (empty when local.properties has no dev
     * id) and only switches to the Room-stored value after the first DAO
     * emission. If the user taps a Spotify track before that emission
     * lands, `clientIdProvider()` returns blank → we previously published
     * `NoClientId` and the user saw a scary banner even though their id
     * was just about to load. Short delay-and-retry solves it.
     */
    private var clientIdBootstrapRetryAvailable: Boolean = true

    /**
     * Budget for silently retrying transient transport failures. Spotify's
     * SDK reports `SpotifyAppRemoteException: Result was not delivered on
     * time` after a ~30s bound-service timeout when the Spotify process
     * is cold-starting; the second attempt almost always succeeds because
     * Spotify is now running. Other TransportFailure causes (disconnect
     * mid-session, transient IPC errors) follow the same recover-on-next
     * pattern. One free retry per session, refilled on any successful
     * connect and on `onHostStop`.
     */
    private var transportRetryAvailable: Boolean = true

    /** The silent retry [handleRemoteError] scheduled; a hint shown meanwhile cancels it. */
    private var retryJob: Job? = null

    /** Bounds how long a waiting start sits on a Spotify app that doesn't answer ([armUnreachableWatch]). */
    private var unreachableWatch: Job? = null

    /**
     * The hint for an unreachable Spotify is up ([reportUnreachable]) and the start the user
     * asked for is held in [pendingOperations]. It runs on the next connect only if the user
     * opened Spotify from the hint ([openSpotify]); otherwise the next connect drops it, so a
     * song tapped hours ago never starts by itself.
     */
    private var parked: Boolean = false

    /** The user opened Spotify from the hint: the held start may run on the next connect. */
    private var openedForStart: Boolean = false

    /**
     * The other device Spotify plays on while App Remote isn't connected: Yoin drives it
     * through the Web API ([connectWeb]) and polls its state ([webPollJob]). Null otherwise.
     */
    private var webDevice: WebDevice? = null
    private var webPollJob: Job? = null
    private var webCheckJob: Job? = null
    private var deviceNameJob: Job? = null

    private data class WebDevice(val id: String?, val name: String)

    /** The host is now [context] (another started Activity); the connection stays as it is. */
    fun onHostChanged(context: Context) {
        hostContext = context
    }

    fun onHostStart(context: Context) {
        Log.d(tag, "onHostStart: host=${context.javaClass.simpleName}")
        hostContext = context
        // Back without opening Spotify from the hint: the song it held was let go.
        if (parked && !openedForStart) {
            parked = false
            dropHeldStart()
        }
        if (wantsConnection) {
            connectIfPossible()
            if (pendingOperations.isNotEmpty()) armUnreachableWatch()
        }
        if (webDevice != null) startWebPolling(immediately = true)
    }

    /**
     * Opportunistically establish a live App Remote connection without the
     * user having tapped anything yet, so the UI can reflect whatever
     * Spotify happens to be playing externally (e.g. the user was already
     * listening via car audio, smart speaker, or another device).
     *
     * Idempotent — no new connect when the remote is already connected or
     * already connecting — but the want is recorded first, whatever is up:
     * a connection the debug probe opened ([withProbeConnection]) is then
     * the account's to keep, not closed under it when the probe ends. Its
     * state and context went unreported while it was the probe's alone, so
     * they are subscribed to again and come in now. Treated as a "soft"
     * connection attempt: if we can't reach the Spotify app right now, we
     * stay silent (no banner, no snapshot with `Error`). The real failure
     * path still fires when the user explicitly plays something.
     */
    fun warmConnection() {
        val wanted = wantsConnection
        wantsConnection = true
        val connected = remote?.takeIf { it.isConnected }
        if (connected != null) {
            if (!wanted) {
                Log.d(tag, "warmConnection: keeping the probe's connection, subscribing again")
                subscribeToPlayerState(connected)
                subscribeToPlayerContext(connected)
            }
            return
        }
        if (webDevice != null) {
            startWebPolling(immediately = true)
        }
        if (connectJob?.isActive == true) return
        Log.d(tag, "warmConnection: attempting soft connect for PlayerState observation")
        connectIfPossible()
        // Meanwhile: Spotify on a speaker or a computer shows (and is driven) without the app here.
        checkOtherDevice()
    }

    fun onHostStop() {
        Log.d(tag, "onHostStop")
        hostContext = null
        // Fresh foreground cycle → restore the one-shot retry budgets. If
        // the user dismissed the app after a real auth failure, next
        // launch should get one more automatic attempt.
        coldStartAuthRetryAvailable = true
        clientIdBootstrapRetryAvailable = true
        transportRetryAvailable = true
        unreachableWatch?.cancel()
        unreachableWatch = null
        webPollJob?.cancel()
        webPollJob = null
        // Cancel any in-flight handshake so a late onConnected can't
        // resurrect playback in the background (assign a remote, flush
        // queued play ops, then defeat the next onHostStart's
        // isConnected check). Unlike disconnect(), keep pendingOperations:
        // queued user intents should run on the next foreground connect.
        connectJob?.cancel()
        connectJob = null
        disconnectRemote(preserveSnapshot = true)
    }

    fun disconnect(resetState: Boolean) {
        Log.d(tag, "disconnect(resetState=$resetState)")
        wantsConnection = false
        pendingOperations.clear()
        mirror.clear()
        queueSyncJob?.cancel()
        adoptedFor = null
        playModeWrite = null
        connectJob?.cancel()
        connectJob = null
        replaceableOperationJob?.cancel()
        replaceableOperationJob = null
        pendingStart = null
        clearUnreachable()
        exitWebMode()
        webCheckJob?.cancel()
        deviceNameJob?.cancel()
        disconnectRemote(preserveSnapshot = !resetState)
        if (resetState) {
            publish(SpotifyRemoteSnapshot())
        }
    }

    /**
     * Starts the requested track, confirming it through App Remote's state subscription.
     * A Web API acknowledgement alone is insufficient: after a bounded wait, one App Remote
     * play is allowed. Starts never append tracks to Spotify's persistent user queue;
     * [queued] is what the user already queued there, which Spotify plays next.
     */
    fun playQueue(
        tracks: List<Track>,
        startIndex: Int,
        startContextPlayback: (suspend (deviceId: String?) -> Unit)? = null,
        playMode: PlayMode? = null,
        queued: List<Track> = emptyList(),
        remoteContextStart: (suspend () -> SpotifyRemoteContextStart?)? = null,
    ) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        Log.d(
            tag,
            "playQueue: size=${tracks.size} startIndex=$startIndex track=${tracks[startIndex].id} " +
                "hasContext=${startContextPlayback != null}"
        )
        mirror.reset(tracks, startIndex, queued)
        queueSyncJob?.cancel()
        adoptedFor = null
        val modeWrite = playMode?.let(::PlayModeWrite)
        playModeWrite = modeWrite
        val start = PendingStart(tracks[startIndex], startContextPlayback)
        pendingStart = start
        val repeatMode = playMode?.repeatMode ?: lastSnapshot.repeatMode
        val shuffleEnabled = playMode?.shuffle ?: lastSnapshot.shuffleEnabled
        val view = mirror.view(playing = null, shuffle = shuffleEnabled)
        // Hold the tap-target as `pendingTrack`. Do NOT write `currentTrack`
        // / `isPlaying = true` / a non-zero position before App Remote
        // confirms with a real PlayerState — that used to produce the
        // "title + total duration + progress stuck at 0:00" bug.
        publish(
            SpotifyRemoteSnapshot(
                currentTrack = null,
                pendingTrack = tracks[startIndex],
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                queue = view.queue,
                currentIndex = view.currentIndex,
                userQueued = view.userQueued,
                upcoming = view.upcoming,
                repeatMode = repeatMode,
                shuffleEnabled = shuffleEnabled,
                connectionPhase = ConnectionPhase.Connecting,
                connectionErrorMessage = null,
                observedPlayerState = false,
            ),
        )
        if (remote?.isConnected != true && connectWeb != null) {
            // App Remote isn't up: if Spotify plays on another device, start there now rather than
            // wait on the app here (a speaker playing is where the user listens).
            scope.launch {
                val state = readConnectState()
                if (pendingStart === start && remote?.isConnected != true && state != null && state.playsElsewhere) {
                    clearUnreachable()
                    startOnOtherDevice(state)
                }
            }
        }
        enqueueOperation(replacePending = true) { connected ->
            try {
                if (pendingStart !== start) return@enqueueOperation
                start.commandSent = true
                val accepted = startContextPlayback?.let { command ->
                    // Where Spotify plays now: a song tapped while a speaker plays starts there.
                    val target = startTarget()
                    if (pendingStart !== start) return@enqueueOperation
                    runCatching { command(target) }.onFailure { error ->
                        if (error is CancellationException) throw error
                        emitContextPlaybackActionIfNeeded(error)
                        Log.w(tag, "Web API start failed; trying App Remote", error)
                    }.isSuccess
                } == true
                if (accepted && awaitStart(start)) {
                    playMode?.let { applyPlayMode(connected, it) }
                    syncQueueWithSpotify()
                    return@enqueueOperation
                }
                if (pendingStart !== start) return@enqueueOperation
                // If the host stopped, observe on reconnect without issuing another start.
                if (remote !== connected) {
                    confirmOnReconnect(start)
                    return@enqueueOperation
                }
                // The album or playlist where App Remote can start it, the song alone where it can't.
                val remoteContext = remoteContextStart?.let { find ->
                    runCatching { find() }.onFailure { error ->
                        if (error is CancellationException) throw error
                        Log.w(tag, "App Remote context start unresolved; playing the song alone", error)
                    }.getOrNull()
                }
                if (pendingStart !== start) return@enqueueOperation
                val answered = withTimeoutOrNull(SPOTIFY_START_HEARD_TIMEOUT_MS) {
                    if (remoteContext != null) {
                        Log.d(tag, "App Remote start: ${remoteContext.contextUri} at ${remoteContext.index}")
                        connected.playerApi.skipToIndex(remoteContext.contextUri, remoteContext.index).awaitUnit()
                    } else {
                        connected.playerApi.play(start.track.spotifyUri()).awaitUnit()
                    }
                    true
                } == true
                check(answered || start.heard.isCompleted) {
                    applicationContext.getString(R.string.player_spotify_unavailable)
                }
                if (awaitStart(start)) {
                    playMode?.let { applyPlayMode(connected, it) }
                    syncQueueWithSpotify()
                } else if (pendingStart === start) {
                    if (remote !== connected) {
                        confirmOnReconnect(start)
                    } else {
                        error(applicationContext.getString(R.string.player_spotify_unavailable))
                    }
                }
            } catch (error: Throwable) {
                // A failed start must not disappear into a reconnect that has no operation to replay.
                handleRemoteError(error, userStart = true)
            } finally {
                // Written, failed or given up: Spotify's own word stands from here.
                if (playModeWrite === modeWrite) playModeWrite = null
            }
        }
    }

    private suspend fun awaitStart(start: PendingStart): Boolean =
        withTimeoutOrNull(SPOTIFY_START_HEARD_TIMEOUT_MS) { start.heard.await() } != null

    /** Reconnecting observes the result; it must not restart a song the user may have changed meanwhile. */
    private fun confirmOnReconnect(start: PendingStart) {
        if (pendingStart !== start) return
        enqueueOperation { connected ->
            if (pendingStart !== start) return@enqueueOperation
            if (!awaitStart(start) && pendingStart === start) {
                if (remote !== connected) {
                    confirmOnReconnect(start)
                } else {
                    handleRemoteError(
                        IllegalStateException(applicationContext.getString(R.string.player_spotify_unavailable)),
                        userStart = true
                    )
                }
            }
        }
    }

    fun pause() {
        if (webCommand(com.gpo.yoin.data.source.spotify.SpotifyConnectCommand.Pause, lastSnapshot.copy(isPlaying = false))) return
        Log.d(tag, "pause")
        enqueueOperation { connected ->
            connected.playerApi.pause().awaitUnit()
        }
    }

    fun resume() {
        if (webCommand(com.gpo.yoin.data.source.spotify.SpotifyConnectCommand.Resume, lastSnapshot.copy(isPlaying = true))) return
        Log.d(tag, "resume")
        enqueueOperation { connected ->
            connected.playerApi.resume().awaitUnit()
        }
    }

    fun skipNext() {
        if (webCommand(com.gpo.yoin.data.source.spotify.SpotifyConnectCommand.Next, null)) return
        enqueueOperation { connected ->
            connected.playerApi.skipNext().awaitUnit()
        }
    }

    fun skipPrevious() {
        if (webCommand(com.gpo.yoin.data.source.spotify.SpotifyConnectCommand.Previous, null)) return
        enqueueOperation { connected ->
            connected.playerApi.skipPrevious().awaitUnit()
        }
    }

    fun seekTo(positionMs: Long) {
        if (webCommand(
                com.gpo.yoin.data.source.spotify.SpotifyConnectCommand.Seek(positionMs.coerceAtLeast(0L)),
                lastSnapshot.copy(positionMs = positionMs.coerceAtLeast(0L)),
            )
        ) {
            return
        }
        enqueueOperation { connected ->
            connected.playerApi.seekTo(positionMs.coerceAtLeast(0L)).awaitUnit()
        }
    }

    fun setRepeatMode(mode: Int) {
        val repeatState = when (mode) {
            Player.REPEAT_MODE_ONE -> "track"
            Player.REPEAT_MODE_ALL -> "context"
            else -> "off"
        }
        if (webCommand(
                com.gpo.yoin.data.source.spotify.SpotifyConnectCommand.Repeat(repeatState),
                lastSnapshot.copy(repeatMode = mode),
            )
        ) {
            return
        }
        enqueueOperation { connected ->
            connected.playerApi.setRepeat(mode.coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL))
                .awaitUnit()
        }
    }

    fun setShuffle(enabled: Boolean) {
        if (webCommand(
                com.gpo.yoin.data.source.spotify.SpotifyConnectCommand.Shuffle(enabled),
                lastSnapshot.copy(shuffleEnabled = enabled),
            )
        ) {
            return
        }
        enqueueOperation { connected ->
            connected.playerApi.setShuffle(enabled).awaitUnit()
        }
    }

    /**
     * Writes repeat + shuffle for a Yoin-started queue. A failure here is
     * logged and swallowed: the queue that just started matters more than
     * the mode, and the button keeps showing what Spotify reports.
     */
    private suspend fun applyPlayMode(connected: SpotifyAppRemote, mode: PlayMode) {
        runCatching {
            withTimeoutOrNull(SPOTIFY_START_HEARD_TIMEOUT_MS) {
                connected.playerApi.setRepeat(mode.repeatMode).awaitUnit()
                connected.playerApi.setShuffle(mode.shuffle).awaitUnit()
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Log.w(tag, "applyPlayMode($mode) failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun addToQueue(track: Track) {
        mirror.add(track, playing = lastSnapshot.currentTrack)
        publishMirror(lastSnapshot)
        enqueueOperation { connected ->
            connected.playerApi.queue(track.spotifyUri()).awaitUnit()
            syncQueueWithSpotify()
        }
    }

    /**
     * Reads Spotify's queue a moment after a start or an Add to queue, and
     * puts what was already queued there into the mirror
     * ([SpotifyQueueMirror.syncUserQueue]). A failed read, or one taken while
     * Spotify had already moved on to another song, leaves the mirror as it is.
     */
    private fun syncQueueWithSpotify(adopt: Boolean = false) {
        queueSyncJob?.cancel()
        queueSyncJob = scope.launch {
            delay(SPOTIFY_QUEUE_READ_DELAY_MS)
            val reported = try {
                queueReader()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(tag, "Spotify queue read failed: ${error.javaClass.simpleName}: ${error.message}")
                null
            } ?: return@launch
            val playing = lastSnapshot.currentTrack
            if (reported.playing != null && playing != null && !reported.playing.isSameSongAs(playing)) return@launch
            if (adopt || mirror.tracks.isEmpty() || mirror.adopted) {
                val now = playing ?: reported.playing ?: return@launch
                mirror.adopt(now, reported.next)
                adoptedFor = now.id
                Log.d(tag, "syncQueueWithSpotify: following Spotify's queue (${reported.next.size} next)")
                publishMirror(lastSnapshot)
            } else if (mirror.syncUserQueue(reported.next)) {
                Log.d(tag, "syncQueueWithSpotify: mirror took Spotify's queue (${reported.next.size} reported)")
                publishMirror(lastSnapshot)
            }
        }
    }

    /**
     * Playback Yoin didn't start: reads Spotify's queue once for each new
     * song, so the sheet lists what comes next ([SpotifyQueueMirror.adopt]).
     * Nothing while a Yoin start is on its way, or while Yoin's own queue
     * plays. [takeOver]: the user moved on in the Spotify app
     * ([SpotifyContextSync] follows Spotify now), so a song from outside
     * Yoin's queue replaces it with Spotify's.
     */
    fun followSpotifyQueue(takeOver: Boolean = false) {
        val playing = lastSnapshot.currentTrack ?: return
        if (pendingStart != null) return
        val replacing = takeOver && lastSnapshot.foreignIndex >= 0
        if (mirror.tracks.isNotEmpty() && !mirror.adopted && !replacing) return
        if (playing.id == adoptedFor) return
        // One read at a time; one that failed or was discarded is tried again at the next state.
        if (playing.id == readingFor && queueSyncJob?.isActive == true) return
        readingFor = playing.id
        syncQueueWithSpotify(adopt = replacing)
    }

    fun clearQueue() {
        mirror.clear()
        queueSyncJob?.cancel()
        adoptedFor = null
        publishMirror(lastSnapshot)
    }

    fun skipToQueueItem(index: Int) {
        val restart = restartAt(index) ?: return
        playQueue(restart.tracks, restart.startIndex, queued = restart.queued)
    }

    /**
     * How to restart the queue at entry [index] of the queue last shown: the
     * album or playlist's songs, without the user's queued ones, from the
     * one tapped, with the queued songs still to play kept next (they stay
     * in Spotify's own queue across a start). A queued song tapped, or one
     * from outside the queue, restarts the whole list shown at it.
     */
    fun restartAt(index: Int): SpotifyRestart? {
        val shown = lastSnapshot
        if (index !in shown.queue.indices) return null
        if (index in shown.userQueued || index == shown.foreignIndex) {
            return SpotifyRestart(shown.queue, index, emptyList(), byUri = true)
        }
        val context = shown.queue.indices.filter { it !in shown.userQueued && it != shown.foreignIndex }
        return SpotifyRestart(
            tracks = context.map(shown.queue::get),
            startIndex = context.indexOf(index),
            queued = shown.upcoming.filter { it in shown.userQueued }.map(shown.queue::get),
        )
    }

    /** [base] with the queue as [mirror] shows it now. */
    private fun publishMirror(base: SpotifyRemoteSnapshot) {
        publish(base.withView(mirror.view(base.currentTrack, base.shuffleEnabled)))
    }

    private fun SpotifyRemoteSnapshot.withView(view: SpotifyQueueView) = copy(
        queue = view.queue,
        currentIndex = view.currentIndex,
        userQueued = view.userQueued,
        upcoming = view.upcoming,
        foreignIndex = view.foreignIndex,
    )

    /**
     * Spotify's own library state for [uri] — a track or an album, all
     * UserApi accepts — read over App Remote's local IPC: no Web API request,
     * so none of the rate-limit budget. Fails when App Remote isn't
     * connected, the call errors, or no answer comes within
     * [LIBRARY_STATE_TIMEOUT_MS]. Never connects, plays or touches the queue.
     */
    suspend fun libraryState(uri: String): Result<LibraryState> = withContext(Dispatchers.Main.immediate) {
        val connected = remote?.takeIf { it.isConnected }
            ?: return@withContext Result.failure(IllegalStateException("App Remote is not connected"))
        try {
            val state = withTimeout(LIBRARY_STATE_TIMEOUT_MS) { connected.userApi.getLibraryState(uri).awaitData() }
            state?.let { Result.success(it) } ?: Result.failure(IllegalStateException("No library state for $uri"))
        } catch (timeout: TimeoutCancellationException) {
            Result.failure(timeout)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /**
     * For the debug library-state probe: runs [block] with App Remote
     * connected when it can be, connecting it for the probe when it isn't
     * (waiting up to [timeoutMs]; it connects only while a Yoin Activity is
     * started), and leaves things as it found them. The probe never asks for
     * the connection to stay ([wantsConnection] is left as it was), so no
     * later host start reconnects for it; a connection it opened where none
     * was wanted — a Subsonic or Apple Music account — reports nothing to
     * the player while it lasts and closes when [block] is done, unless
     * something wanted it meanwhile (a Spotify account's [warmConnection], a
     * play). Without a client id it doesn't try — once Room has had
     * [CLIENT_ID_BOOTSTRAP_GRACE_MS] to read it, as a cold connect gets: a
     * process the broadcast just started may not have it yet. Plays nothing.
     * Returns [block]'s result with how the connection was left
     * ([ProbeLeft]), as it turned out once [block] was done.
     */
    suspend fun <T> withProbeConnection(timeoutMs: Long, block: suspend (ProbeConnection) -> T): ProbeRun<T> =
        withContext(Dispatchers.Main.immediate) {
            if (remote?.isConnected == true) {
                return@withContext ProbeRun(block(ProbeConnection.Connected), ProbeLeft.AsFound)
            }
            if (!awaitClientId(CLIENT_ID_BOOTSTRAP_GRACE_MS)) {
                return@withContext ProbeRun(block(ProbeConnection.NoClientId), ProbeLeft.NotConnected)
            }
            connectIfPossible()
            val connected = withTimeoutOrNull(timeoutMs) {
                while (remote?.isConnected != true) delay(CONNECTION_POLL_MS)
                true
            } ?: false
            var closed = false
            val value = try {
                block(
                    when {
                        connected -> ProbeConnection.Connected
                        hostContext == null -> ProbeConnection.NoHost
                        else -> ProbeConnection.TimedOut
                    }
                )
            } finally {
                // Opened for the probe alone (nothing asked for it since): closed again.
                if (!wantsConnection && pendingOperations.isEmpty()) {
                    connectJob?.cancel()
                    connectJob = null
                    if (remote != null) disconnectRemote(preserveSnapshot = true)
                    closed = true
                }
            }
            val left = when {
                !connected -> ProbeLeft.NotConnected
                closed -> ProbeLeft.Closed
                else -> ProbeLeft.Kept
            }
            ProbeRun(value, left)
        }

    /**
     * Whether a client id is set, or comes in within [graceMs]: it is read
     * from Room on a cold start, and blank until that first read lands.
     */
    private suspend fun awaitClientId(graceMs: Long): Boolean = clientIdProvider().isNotBlank() ||
        withTimeoutOrNull(graceMs) {
            while (clientIdProvider().isBlank()) delay(CONNECTION_POLL_MS)
            true
        } == true

    /** How the debug probe's App Remote connection went ([withProbeConnection]). */
    internal enum class ProbeConnection {
        /** Connected, or already was. */
        Connected,

        /**
         * No Spotify client id set, even after Room's first read: App Remote
         * can't connect at all. It is the app's one setting (Settings ›
         * Spotify), not an account's — switching accounts doesn't change it.
         */
        NoClientId,

        /** No started Yoin Activity to connect from. */
        NoHost,

        /** A host was there, but App Remote didn't connect in time. */
        TimedOut
    }

    /** What [withProbeConnection]'s block returned, and how the probe left App Remote's connection. */
    internal data class ProbeRun<T>(val value: T, val left: ProbeLeft)

    /** How the debug probe left App Remote's connection ([withProbeConnection]). */
    internal enum class ProbeLeft {
        /** It was connected before the probe: left as it was. */
        AsFound,

        /**
         * Opened for the probe, and something wanted it meanwhile — a Spotify
         * account's warm-up (a cold start's source coming in), a play: kept.
         */
        Kept,

        /** Opened for the probe alone: closed once it was done. */
        Closed,

        /** Not connected for the probe (no client id, no host, or not in time). */
        NotConnected
    }

    private fun enqueueOperation(
        replacePending: Boolean = false,
        operation: suspend (SpotifyAppRemote) -> Unit,
    ) {
        Log.d(
            tag,
            "enqueueOperation(replacePending=$replacePending, connected=${remote?.isConnected == true}, pending=${pendingOperations.size})",
        )
        wantsConnection = true
        if (replacePending) {
            // A new start replaces whatever a hint held, and asks again from scratch.
            clearUnreachable()
            pendingOperations.clear()
            // Cancel the old start, including its confirmation wait. The mutex
            // still orders a blocking HTTP call's completion before the new start.
            replaceableOperationJob?.cancel()
            replaceableOperationJob = null
        }
        val connected = remote
        if (connected != null && connected.isConnected) {
            launchOperation(replaceable = replacePending) {
                runCatching { operation(connected) }
                    .onFailure(::handleRemoteError)
            }
            return
        }
        pendingOperations += operation
        connectIfPossible()
        armUnreachableWatch()
    }

    /**
     * Runs a remote operation on [scope]. Replaceable launches (a play
     * request, or the flushed pending queue that may contain one) keep
     * their Job in [replaceableOperationJob] so the next
     * `replacePending = true` enqueue can cancel them mid-flight. One-shot
     * ops (pause / seek / …) stay untracked so they never displace a
     * running start from the field.
     */
    private fun launchOperation(replaceable: Boolean, block: suspend () -> Unit) {
        if (replaceable) {
            // A batch stuck on a dead remote (disconnect can strand in-flight
            // CallResults) must not leak when the next batch takes the field.
            replaceableOperationJob?.cancel()
        }
        val job = scope.launch {
            if (replaceable) startMutex.withLock { block() } else block()
        }
        if (replaceable) {
            replaceableOperationJob = job
        }
    }

    private fun connectIfPossible() {
        if (remote?.isConnected == true || connectJob?.isActive == true) return
        val context = hostContext ?: return
        val clientId = clientIdProvider().trim()
        Log.d(tag, "connectIfPossible: hasHost=true clientIdConfigured=${clientId.isNotBlank()}")
        if (clientId.isBlank()) {
            // Blank value on cold start is usually "Room hasn't emitted the
            // stored id yet", not "user genuinely never set one". Give the
            // flow a brief grace period before publishing the error banner.
            // Budget is one-shot per host session so a *genuinely* blank id
            // still surfaces the banner on second attempt. We retry whether
            // there's a pending op or just a warm connect — both paths
            // deserve to wait for Room.
            if (clientIdBootstrapRetryAvailable && wantsConnection) {
                clientIdBootstrapRetryAvailable = false
                Log.d(tag, "connectIfPossible: clientId blank, retrying after bootstrap grace")
                scope.launch {
                    kotlinx.coroutines.delay(CLIENT_ID_BOOTSTRAP_GRACE_MS)
                    connectIfPossible()
                }
                return
            }
            // No user is actively waiting on us (warm connect with no
            // queued ops, never observed real PlayerState) — silently
            // bail rather than flashing a NoClientId banner. The user
            // will trigger a real connect when they tap something, at
            // which point we'll have a pending op and the banner is
            // appropriate.
            if (!isUserWaitingOnConnection()) {
                Log.d(tag, "connectIfPossible: clientId blank with no user waiting — silent abort")
                return
            }
            publish(
                lastSnapshot.copy(
                    connectionPhase = ConnectionPhase.Error,
                    connectionErrorMessage = SpotifyConnectFailure.NoClientId.userMessage(
                        applicationContext.resources,
                    ),
                    connectionFailure = SpotifyConnectFailure.NoClientId,
                ),
            )
            return
        }
        // Saw a non-blank value, so subsequent connects no longer need the
        // bootstrap grace — refill the budget.
        clientIdBootstrapRetryAvailable = true
        connectJob = scope.launch {
            // Host resume / retry path: if the previous attempt ended in
            // Error, move back to Connecting so UI doesn't render a stuck
            // error while we try again.
            if (lastSnapshot.connectionPhase == ConnectionPhase.Error) {
                publish(
                    lastSnapshot.copy(
                        connectionPhase = ConnectionPhase.Connecting,
                        connectionErrorMessage = null,
                        connectionFailure = null,
                    ),
                )
            }
            runCatching {
                connect(context, clientId)
            }.onSuccess { connected ->
                Log.d(tag, "connectIfPossible: connected")
                unreachableWatch?.cancel()
                unreachableWatch = null
                // App Remote reaches Spotify, wherever it plays (Spotify Connect included).
                exitWebMode()
                refreshPlayingOn()
                if (parked) {
                    try {
                        settleHeldStart(connected)
                    } catch (error: CancellationException) {
                        // Host stop raced the look: don't leak the live remote's IPC connection.
                        appRemote.disconnect(connected)
                        throw error
                    }
                }
                remote = connected
                // Successful connect — refill all single-shot retry budgets so
                // a later hiccup in the same session can also get one free
                // attempt at each recovery path.
                coldStartAuthRetryAvailable = true
                transportRetryAvailable = true
                subscribeToPlayerState(connected)
                subscribeToPlayerContext(connected)
                // Connect succeeded, but we do NOT move to Ready yet — we
                // wait for the first real PlayerState via the subscription.
                // connectionPhase stays `Connecting`.
                publish(
                    lastSnapshot.copy(
                        connectionErrorMessage = null,
                        connectionFailure = null,
                    ),
                )
                flushPendingOperations(connected)
            }.onFailure { error ->
                Log.w(tag, "connectIfPossible: failed", error)
                handleRemoteError(error, duringConnect = true)
            }
            connectJob = null
        }
    }

    private suspend fun connect(
        context: Context,
        clientId: String,
    ): SpotifyAppRemote = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        // Reuse the PKCE OAuth redirect URI so App Remote matches what the
        // user has already registered in the Spotify Developer Dashboard.
        // A second URI (APP_REMOTE_REDIRECT_URI) would need its own Dashboard
        // whitelist entry — if it's missing, Spotify rejects the connection
        // with UserNotAuthorizedException before any scope check runs. Keeping
        // the split callback activity is harmless (it just finish()es), but
        // the ConnectionParams URI must match a whitelisted one.
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(SpotifyAuthConfig.REDIRECT_URI)
            .showAuthView(true)
            .build()
        appRemote.connect(
            context,
            params,
            object : Connector.ConnectionListener {
                override fun onConnected(spotifyAppRemote: SpotifyAppRemote) {
                    if (completed.compareAndSet(false, true)) {
                        // If the continuation was cancelled (host stop / disconnect
                        // raced the handshake), a plain resume would silently drop
                        // a live remote and leak its IPC connection — disconnect it.
                        continuation.resume(spotifyAppRemote) { _ ->
                            Log.d(tag, "connect: cancelled before delivery, disconnecting remote")
                            appRemote.disconnect(spotifyAppRemote)
                        }
                    } else {
                        Log.d(tag, "connect: ignoring duplicate onConnected callback")
                    }
                }

                override fun onFailure(throwable: Throwable) {
                    if (completed.compareAndSet(false, true)) {
                        continuation.resumeWithException(throwable)
                    } else {
                        Log.d(tag, "connect: ignoring late failure callback", throwable)
                    }
                }
            },
        )
    }

    private fun flushPendingOperations(connected: SpotifyAppRemote) {
        val operations = pendingOperations.toList()
        pendingOperations.clear()
        Log.d(tag, "flushPendingOperations: count=${operations.size}")
        launchOperation(replaceable = true) {
            for (operation in operations) {
                runCatching { operation(connected) }
                    .onFailure { error ->
                        Log.w(tag, "flushPendingOperations: operation failed", error)
                        handleRemoteError(error)
                    }
            }
        }
    }

    private fun subscribeToPlayerState(connected: SpotifyAppRemote) {
        Log.d(tag, "subscribeToPlayerState")
        playerStateSubscription?.cancel()
        playerStateSubscription = connected.playerApi.subscribeToPlayerState()
            .setEventCallback { playerState ->
                scope.launch {
                    Log.d(
                        tag,
                        "playerState: paused=${playerState.isPaused} position=${playerState.playbackPosition} trackUri=${playerState.track?.uri}",
                    )
                    // A connection only the debug probe opened reports nothing.
                    if (wantsConnection) {
                        val start = pendingStart
                        if (start != null && (!start.commandSent || !spotifyPlays(playerState, start.track))) {
                            return@launch
                        }
                        start?.heard?.complete(Unit)
                        pendingStart = null
                        val before = lastSnapshot.currentTrack?.id
                        publish(fromPlayerState(playerState))
                        // A new song may be playing on another device: Now Playing names it.
                        if (lastSnapshot.currentTrack?.id != before) refreshPlayingOn()
                        followSpotifyQueue()
                    }
                }
            }
            .setLifecycleCallback(
                object : Subscription.LifecycleCallback {
                    override fun onStart() = Unit
                    override fun onStop() = Unit
                },
            ) as Subscription<PlayerState>
        playerStateSubscription?.setErrorCallback { throwable ->
            scope.launch {
                Log.w(tag, "playerState subscription error", throwable)
                handleRemoteError(throwable)
            }
        }
    }

    // Subscribe to "what's playing FROM" (album / playlist / …). Independent of the
    // PlayerState subscription; lets us label externally-started playback as
    // "Playing from X" instead of a bare "Now Playing".
    private fun subscribeToPlayerContext(connected: SpotifyAppRemote) {
        playerContextSubscription?.cancel()
        playerContextSubscription = connected.playerApi.subscribeToPlayerContext()
            .setEventCallback { ctx ->
                scope.launch {
                    if (wantsConnection) onContext(SpotifyPlaybackContext(uri = ctx.uri, title = ctx.title))
                }
            }
            .setLifecycleCallback(
                object : Subscription.LifecycleCallback {
                    override fun onStart() = Unit
                    override fun onStop() = Unit
                },
            ) as Subscription<PlayerContext>
        playerContextSubscription?.setErrorCallback { throwable ->
            scope.launch {
                Log.w(tag, "playerContext subscription error", throwable)
            }
        }
    }

    private fun disconnectRemote(preserveSnapshot: Boolean) {
        Log.d(tag, "disconnectRemote(preserveSnapshot=$preserveSnapshot)")
        playerStateSubscription?.cancel()
        playerStateSubscription = null
        playerContextSubscription?.cancel()
        playerContextSubscription = null
        remote?.let(appRemote::disconnect)
        remote = null
        if (preserveSnapshot) {
            publish(
                lastSnapshot.copy(
                    connectionPhase = ConnectionPhase.Idle,
                    connectionErrorMessage = null,
                ),
            )
        }
    }

    private fun fromPlayerState(playerState: PlayerState): SpotifyRemoteSnapshot {
        val mappedTrack = playerState.track?.toYoinTrack() ?: lastSnapshot.currentTrack
        val mirrorIndex = mappedTrack?.let(mirror::onPlaying) ?: -1
        // Yoin's own copy of the song (its metadata), unless Spotify relinked it.
        val currentTrack = mirroredQueue.getOrNull(mirrorIndex)?.takeIf { it.id == mappedTrack?.id }
            ?: mappedTrack
            ?: lastSnapshot.currentTrack
        val playMode = playModeWrite?.mode
        val shuffleEnabled = playMode?.shuffle ?: (playerState.playbackOptions?.isShuffling == true)
        val view = mirror.view(currentTrack, shuffleEnabled)
        return lastSnapshot.copy(
            currentTrack = currentTrack,
            pendingTrack = null,
            isPlaying = !playerState.isPaused,
            positionMs = playerState.playbackPosition.coerceAtLeast(0L),
            durationMs = currentTrack?.durationSec?.times(1_000L)
                ?: playerState.track?.duration?.coerceAtLeast(0L)
                ?: lastSnapshot.durationMs,
            repeatMode = playMode?.repeatMode ?: playerState.playbackOptions?.repeatMode ?: Player.REPEAT_MODE_ALL,
            shuffleEnabled = shuffleEnabled,
            connectionPhase = ConnectionPhase.Ready,
            connectionErrorMessage = null,
            observedPlayerState = true,
        ).withView(view)
    }

    private fun com.spotify.protocol.types.Track.toYoinTrack(): Track {
        val fallbackTrack = lastSnapshot.currentTrack
            ?: mirroredQueue.firstOrNull()
        val rawId = uri
            ?.substringAfterLast(':')
            ?.takeIf(String::isNotBlank)
            ?: fallbackTrack?.id
                ?.takeIf { it.provider == MediaId.PROVIDER_SPOTIFY }
                ?.rawId
            ?: "spotify-remote-unknown"
        return mirroredQueue.firstOrNull { track ->
            track.id.provider == MediaId.PROVIDER_SPOTIFY && track.id.rawId == rawId
        } ?: fallbackTrack?.takeIf { it.id.provider == MediaId.PROVIDER_SPOTIFY && it.id.rawId == rawId } ?: Track(
            id = MediaId.spotify(rawId),
            title = name,
            artist = artists.firstOrNull()?.name ?: artist?.name,
            artistId = (artists.firstOrNull()?.uri ?: artist?.uri)?.substringAfterLast(':')
                ?.takeIf(String::isNotBlank)
                ?.let(MediaId::spotify),
            album = album?.name,
            albumId = album?.uri?.substringAfterLast(':')?.takeIf(String::isNotBlank)?.let(MediaId::spotify),
            coverArt = imageUri?.raw?.takeIf(String::isNotBlank)?.let(::spotifyImageUrlFromProtocolUri)
                ?.let(CoverRef::Url),
            durationSec = (duration / 1_000L).toInt(),
            trackNumber = null,
            year = null,
            genre = null,
            userRating = null,
            isStarred = false,
            // An episode or a local file keeps its kind, so nothing asks
            // Spotify whether its id is a liked track.
            extras = spotifyUriType(uri)?.let { type -> mapOf(EXTRA_SPOTIFY_URI_TYPE to type) }.orEmpty(),
        )
    }

    private fun Track.spotifyUri(): String {
        require(id.provider == MediaId.PROVIDER_SPOTIFY) {
            "SpotifyAppRemotePlayer received a non-Spotify track: ${id}"
        }
        return "spotify:track:${id.rawId}"
    }

    private fun handleRemoteError(error: Throwable, userStart: Boolean = false, duringConnect: Boolean = false) {
        // Cancellation (a replaced start, a connect cancelled by host
        // stop) is not a remote failure — rethrow so the coroutine just
        // dies instead of consuming retry budgets, disconnecting a live
        // replacement remote, or publishing an Error banner.
        if (error is CancellationException) throw error
        Log.w(tag, "handleRemoteError: ${error::class.java.simpleName}", error)

        // Cold-start `UserNotAuthorizedException` quirk: the SDK's first
        // connect call after a fresh app launch often returns this even
        // though Spotify has just recorded consent behind the scenes. The
        // next connect succeeds. Silently retry once rather than flashing
        // a scary "needs authorization" banner that the user has to
        // dismiss before tapping the same track again.
        if (error is UserNotAuthorizedException &&
            coldStartAuthRetryAvailable &&
            wantsConnection &&
            !userStart &&
            pendingOperations.isNotEmpty()
        ) {
            coldStartAuthRetryAvailable = false
            Log.d(tag, "handleRemoteError: silently retrying cold-start UserNotAuthorizedException")
            // Keep UI in Connecting during the retry window. Do NOT publish
            // an Error snapshot — if the retry succeeds the user never
            // sees a flash of failure. If the retry also fails we'll fall
            // back through this same method and publish Error then.
            disconnectRemote(preserveSnapshot = true)
            retryJob = scope.launch {
                kotlinx.coroutines.delay(COLD_START_AUTH_RETRY_DELAY_MS)
                connectIfPossible()
            }
            return
        }

        val failure = failureFor(error)

        // Transient transport failures (network blip, IPC timeout, SDK's
        // 30s bound-service `SpotifyAppRemoteException` cold-start timeout,
        // mid-session disconnect, etc.) — auto-retry once. The SDK's parent
        // `SpotifyAppRemoteException` falls through `failureFor`'s `else`
        // branch into `TransportFailure`, so this catches the timeout case
        // we previously missed (only the two named subclasses retried).
        if (failure is SpotifyConnectFailure.TransportFailure &&
            transportRetryAvailable &&
            wantsConnection &&
            !userStart
        ) {
            transportRetryAvailable = false
            Log.d(tag, "handleRemoteError: silently retrying transport failure after ${TRANSPORT_RETRY_DELAY_MS}ms")
            disconnectRemote(preserveSnapshot = true)
            retryJob = scope.launch {
                kotlinx.coroutines.delay(TRANSPORT_RETRY_DELAY_MS)
                connectIfPossible()
            }
            return
        }

        // The Spotify app didn't answer while a start waits on it: say so, with a way to wake it.
        if (duringConnect && pendingOperations.isNotEmpty() && isUnreachable(error)) {
            reportUnreachable()
            return
        }

        // Warm-connect-only failure: nobody's actively waiting on us
        // (no queued ops, no PlayerState ever observed). Surfacing an
        // Error banner here is just noise — the user wasn't trying to do
        // anything Spotify-related yet. Stay quiet and let the next user
        // action retrigger the connect attempt naturally.
        if (!userStart && !isUserWaitingOnConnection()) {
            Log.d(tag, "handleRemoteError: warm-connect failure, suppressing Error banner")
            disconnectRemote(preserveSnapshot = true)
            checkOtherDevice()
            return
        }

        publishError(failure, failure.userMessage(applicationContext.resources))
    }

    private fun publishError(failure: SpotifyConnectFailure, message: String) {
        // The error is the answer; the watch must not replace it with an "asleep" hint.
        unreachableWatch?.cancel()
        unreachableWatch = null
        // If the user had already been watching something play (observed
        // at least one real PlayerState), preserve `currentTrack` and let
        // UI render an "interrupted" state. If we never observed a frame,
        // keep the `pendingTrack` so the Launching UI can still show what
        // the user was *trying* to play with the error message.
        val errorSnapshot = if (lastSnapshot.observedPlayerState) {
            lastSnapshot.copy(
                connectionPhase = ConnectionPhase.Error,
                connectionErrorMessage = message,
                connectionFailure = failure,
            )
        } else {
            SpotifyRemoteSnapshot(
                currentTrack = null,
                pendingTrack = lastSnapshot.pendingTrack,
                queue = lastSnapshot.queue,
                currentIndex = lastSnapshot.currentIndex,
                userQueued = lastSnapshot.userQueued,
                upcoming = lastSnapshot.upcoming,
                foreignIndex = lastSnapshot.foreignIndex,
                connectionPhase = ConnectionPhase.Error,
                connectionErrorMessage = message,
                connectionFailure = failure,
                observedPlayerState = false,
            )
        }
        publish(errorSnapshot)
    }

    /**
     * True when the user has either queued an operation that's waiting on
     * the connection (`pendingOperations`) or already observed a real
     * PlayerState in this session. Used to decide whether a connect
     * failure is worth surfacing to UI — warm/speculative connects with
     * neither shouldn't flash banners.
     */
    /** No answer from the Spotify app at all, as opposed to one that says why (offline, needs an update, …). */
    private fun isUnreachable(error: Throwable): Boolean =
        failureFor(error) is SpotifyConnectFailure.TransportFailure &&
            error !is OfflineModeException &&
            error !is UnsupportedFeatureVersionException

    /**
     * A start waiting on the connection gets [SPOTIFY_UNREACHABLE_AFTER_MS], not the SDK's ~30 s
     * bound-service timeout (twice, with the silent retry): Spotify asleep in the background
     * used to leave the tap hanging for up to a minute, then a hint with nothing to do.
     */
    private fun armUnreachableWatch() {
        if (remote?.isConnected == true || hostContext == null || unreachableWatch?.isActive == true) return
        unreachableWatch = scope.launch {
            delay(SPOTIFY_UNREACHABLE_AFTER_MS)
            unreachableWatch = null
            // Only a start still waiting: a typed failure (no Client ID, auth, …) already said what's wrong.
            if (remote?.isConnected != true &&
                pendingOperations.isNotEmpty() &&
                lastSnapshot.connectionPhase != ConnectionPhase.Error
            ) {
                Log.d(tag, "unreachableWatch: no connection after ${SPOTIFY_UNREACHABLE_AFTER_MS}ms")
                reportUnreachable()
            }
        }
    }

    /**
     * Stops waiting on App Remote and asks the Web API (a read) where Spotify plays. On another
     * device: Yoin drives that one ([enterWebMode]) and the waiting start plays there. Nowhere:
     * the start is held ([parked]) and the user is told Spotify is asleep here
     * ([SpotifyConnectFailure.SpotifyAsleep]). Nothing here pauses or moves Spotify's playback.
     */
    private fun reportUnreachable() {
        retryJob?.cancel()
        retryJob = null
        unreachableWatch?.cancel()
        unreachableWatch = null
        connectJob?.cancel()
        connectJob = null
        parked = true
        openedForStart = false
        val held = pendingOperations.size
        scope.launch {
            val state = readConnectState()
            // A new start, a connection or a sign-out came first: this answer is stale.
            if (!parked || remote?.isConnected == true || pendingOperations.size != held) return@launch
            Log.d(tag, "reportUnreachable: elsewhere=${state?.playsElsewhere == true}")
            if (state != null && state.playsElsewhere) {
                parked = false
                startOnOtherDevice(state)
            } else {
                publishError(SpotifyConnectFailure.SpotifyAsleep, SpotifyConnectFailure.SpotifyAsleep.userMessage(applicationContext.resources))
            }
        }
    }

    /**
     * Opens the Spotify app from the hint, without a song, so nothing it plays changes; App
     * Remote connects when Yoin is back, and the held start runs then, unless Spotify is
     * playing by that time. False when the Spotify app can't be opened.
     */
    fun openSpotify(): Boolean {
        val context = hostContext ?: applicationContext
        val intent = spotifyLaunchIntent(context) ?: return false
        if (context !is android.app.Activity) intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(intent) }
            .onFailure { Log.w(tag, "openSpotify: launch failed", it) }
            .isSuccess
        if (opened && parked) openedForStart = true
        return opened
    }

    /**
     * The first connect after a hint decides what the held start does: dropped unless the
     * user opened Spotify for it, and dropped when Spotify is already playing by then (the
     * user picked something there).
     */
    private suspend fun settleHeldStart(connected: SpotifyAppRemote) {
        // The flags stay set through the look: a host stop that cancels it leaves the start
        // held, and the next connect looks again instead of playing it unchecked.
        val opened = openedForStart
        val keep = opened && run {
            val state = try {
                withTimeoutOrNull(PLAYER_STATE_LOOK_TIMEOUT_MS) { connected.playerApi.playerState.awaitData() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                null
            }
            // Unknown counts as playing: better a start the user taps again than one that cuts in.
            state?.isPaused == true
        }
        // A new tap during the look replaced the held start (and cleared [parked]): it is the user's own.
        if (!parked) {
            Log.d(tag, "settleHeldStart: a new start came in meanwhile, leaving it alone")
            return
        }
        parked = false
        openedForStart = false
        Log.d(tag, "settleHeldStart: opened=$opened keep=$keep")
        if (!keep) dropHeldStart()
    }

    /** Lets the start a hint held go, and the hint with it. */
    private fun dropHeldStart() {
        Log.d(tag, "dropHeldStart: pending=${pendingOperations.size}")
        pendingOperations.clear()
        pendingStart = null
        publish(
            lastSnapshot.copy(
                pendingTrack = null,
                connectionPhase = if (lastSnapshot.connectionPhase == ConnectionPhase.Error) {
                    ConnectionPhase.Idle
                } else {
                    lastSnapshot.connectionPhase
                },
                connectionFailure = null,
                connectionErrorMessage = null,
            ),
        )
    }

    private fun clearUnreachable() {
        parked = false
        openedForStart = false
        unreachableWatch?.cancel()
        unreachableWatch = null
    }

    // ── Spotify on another device (Spotify Connect through the Web API) ──────────

    /** Where Spotify plays (a read); null when nothing is active, it can't be read, or there's no [connectWeb]. */
    private suspend fun readConnectState(): com.gpo.yoin.data.source.spotify.SpotifyConnectState? {
        val web = connectWeb ?: return null
        return try {
            withTimeoutOrNull(CONNECT_STATE_TIMEOUT_MS) { web.state() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(tag, "readConnectState: unreadable", error)
            null
        }
    }

    /**
     * The device a start plays on: the other device Spotify is playing on right now, so a tap
     * there carries on there (51, 2026-11-06); null (this phone) when it plays nowhere else.
     * Records that device for Now Playing either way.
     */
    private suspend fun startTarget(): String? {
        val state = readConnectState()
        val elsewhere = state?.takeIf { it.playsElsewhere }
        if (lastSnapshot.playingOn != elsewhere?.deviceName) publish(lastSnapshot.copy(playingOn = elsewhere?.deviceName))
        return elsewhere?.deviceId
    }

    /** True while Yoin drives another device through the Web API instead of App Remote. */
    private val drivingOtherDevice: Boolean
        get() = webDevice != null && remote?.isConnected != true

    /**
     * Spotify plays on another device and App Remote can't reach the app here: Yoin follows that
     * device from the Web API, polling while a Yoin screen is up, and its controls drive it there.
     */
    private fun enterWebMode(state: com.gpo.yoin.data.source.spotify.SpotifyConnectState) {
        Log.d(tag, "enterWebMode: device=${state.deviceName}")
        wantsConnection = true
        webDevice = WebDevice(state.deviceId, state.deviceName)
        unreachableWatch?.cancel()
        unreachableWatch = null
        publishWebState(state)
        startWebPolling()
    }

    private fun exitWebMode() {
        if (webDevice == null) return
        Log.d(tag, "exitWebMode")
        webDevice = null
        webPollJob?.cancel()
        webPollJob = null
    }

    private fun startWebPolling(immediately: Boolean = false) {
        if (webDevice == null || hostContext == null) return
        webPollJob?.cancel()
        webPollJob = scope.launch {
            if (!immediately) delay(WEB_POLL_MS)
            while (webDevice != null && remote?.isConnected != true) {
                val state = readConnectState()
                when {
                    remote?.isConnected == true || webDevice == null -> return@launch
                    // Nothing plays anywhere any more: show it stopped, and leave the device.
                    state == null -> {
                        publish(lastSnapshot.copy(isPlaying = false, playingOn = null))
                        exitWebMode()
                        return@launch
                    }
                    // Spotify moved to this phone: App Remote reaches it now.
                    state.isThisDevice -> {
                        publish(lastSnapshot.copy(playingOn = null))
                        exitWebMode()
                        connectIfPossible()
                        return@launch
                    }
                    else -> {
                        webDevice = WebDevice(state.deviceId, state.deviceName)
                        publishWebState(state)
                    }
                }
                delay(WEB_POLL_MS)
            }
        }
    }

    private fun publishWebState(state: com.gpo.yoin.data.source.spotify.SpotifyConnectState) {
        val start = pendingStart
        val heard = state.track
        // A Yoin start still on its way: its song, not the one the device played before it.
        if (start != null && (heard == null || !sameSong(heard, start.track))) return
        if (start != null) {
            start.heard.complete(Unit)
            pendingStart = null
        }
        val mapped = heard?.let { song -> mirroredQueue.firstOrNull { it.id == song.id } ?: song }
        val current = mapped ?: lastSnapshot.currentTrack
        val mirrorIndex = mapped?.let(mirror::onPlaying) ?: -1
        val currentTrack = mirroredQueue.getOrNull(mirrorIndex)?.takeIf { it.id == mapped?.id } ?: current
        val view = mirror.view(currentTrack, state.shuffle)
        publish(
            lastSnapshot.copy(
                currentTrack = currentTrack,
                pendingTrack = null,
                isPlaying = state.isPlaying,
                positionMs = state.progressMs,
                durationMs = currentTrack?.durationSec?.times(1_000L) ?: lastSnapshot.durationMs,
                shuffleEnabled = state.shuffle,
                repeatMode = when (state.repeatState) {
                    "track" -> Player.REPEAT_MODE_ONE
                    "context" -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                },
                connectionPhase = ConnectionPhase.Ready,
                connectionErrorMessage = null,
                connectionFailure = null,
                observedPlayerState = true,
                playingOn = state.deviceName,
            ).withView(view),
        )
    }

    private fun sameSong(heard: Track, wanted: Track): Boolean =
        heard.id == wanted.id ||
            (heard.title.orEmpty().equals(wanted.title.orEmpty(), ignoreCase = true) && heard.title != null)

    /**
     * The waiting start, played on the other device [state] names (App Remote can't reach the
     * app here); then Yoin follows that device. Without a Web API start, the device carries on
     * with what it plays and Yoin shows that.
     */
    private fun startOnOtherDevice(state: com.gpo.yoin.data.source.spotify.SpotifyConnectState) {
        val start = pendingStart
        pendingOperations.clear()
        enterWebMode(state)
        val webStart = start?.webStart ?: run {
            pendingStart = null
            publishWebState(state)
            return
        }
        launchOperation(replaceable = true) {
            if (pendingStart !== start) return@launchOperation
            start.commandSent = true
            try {
                webStart(state.deviceId)
                // Spotify Connect takes a moment to report the new song.
                withTimeoutOrNull(SPOTIFY_START_HEARD_TIMEOUT_MS) {
                    while (pendingStart === start) {
                        delay(WEB_START_POLL_MS)
                        readConnectState()?.let(::publishWebState)
                    }
                }
                if (pendingStart === start) {
                    // Heard nothing that matches (a relinked copy): show what the device plays.
                    pendingStart = null
                    readConnectState()?.let(::publishWebState)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(tag, "startOnOtherDevice: start failed", error)
                if (pendingStart === start) pendingStart = null
                publishError(
                    SpotifyConnectFailure.TransportFailure(applicationContext.getString(R.string.player_spotify_unavailable)),
                    applicationContext.getString(R.string.player_spotify_unavailable),
                )
            }
        }
    }

    /** One control on the other device; Spotify's state is read back shortly after. */
    private fun webCommand(command: com.gpo.yoin.data.source.spotify.SpotifyConnectCommand, optimistic: SpotifyRemoteSnapshot? = null): Boolean {
        val device = webDevice?.takeIf { drivingOtherDevice } ?: return false
        val web = connectWeb ?: return false
        optimistic?.let(::publish)
        scope.launch {
            try {
                web.command(command, device.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(tag, "webCommand: $command failed", error)
            }
            delay(WEB_COMMAND_SETTLE_MS)
            if (drivingOtherDevice) readConnectState()?.takeIf { !it.isThisDevice }?.let(::publishWebState)
            startWebPolling()
        }
        return true
    }

    /**
     * App Remote couldn't connect without anyone waiting (opening Yoin, coming back to it): if
     * Spotify plays on another device, Yoin shows that and drives it. Nothing is played or paused.
     */
    private fun checkOtherDevice() {
        if (connectWeb == null || webDevice != null || webCheckJob?.isActive == true) return
        webCheckJob = scope.launch {
            val state = readConnectState()
            if (state != null && state.playsElsewhere && remote?.isConnected != true && pendingStart == null) {
                enterWebMode(state)
            }
        }
    }

    /** With App Remote connected: the other device Spotify plays on, for Now Playing (it doesn't say). */
    private fun refreshPlayingOn() {
        if (connectWeb == null) return
        deviceNameJob?.cancel()
        deviceNameJob = scope.launch {
            val state = readConnectState() ?: return@launch
            // Only a device that plays: a speaker paused since the start isn't where the song is.
            val name = state.deviceName.takeIf { state.playsElsewhere }
            if (name != lastSnapshot.playingOn) publish(lastSnapshot.copy(playingOn = name))
        }
    }

    private fun isUserWaitingOnConnection(): Boolean =
        pendingOperations.isNotEmpty() || lastSnapshot.observedPlayerState

    private companion object {
        /**
         * How long a library-state read waits for Spotify's answer. It is
         * local IPC and normally answers in milliseconds; a CallResult a
         * dropped connection stranded must not hold the heart check open.
         */
        const val LIBRARY_STATE_TIMEOUT_MS = 3_000L

        /** How often [withProbeConnection] looks whether the connect (or the client id) landed. */
        const val CONNECTION_POLL_MS = 100L

        /**
         * Gap between the failing connect and the silent retry. Long enough
         * for the SDK's auth flow to finish persisting consent, short enough
         * that the user doesn't perceive a visible hang. 1.2s empirically
         * covers the slow-device case without being noticeable on a Pixel.
         */
        const val COLD_START_AUTH_RETRY_DELAY_MS = 1_200L

        /**
         * Grace window for Room → StateFlow to emit the stored client id
         * before we declare "No Client ID". Room's first emission by
         * itself lands well inside 400ms, but Batch 3D added a startup
         * migration coroutine that can keep the IO dispatcher busy with
         * its first `AndroidKeyStore` key-generation (200-1000ms on
         * upgrade; one-time per install). Bumping the grace to 1500ms
         * absorbs that without pushing a scary "Client ID not configured"
         * banner at users who were mid-first-launch after a build
         * upgrade. Budget is still one-shot per host session so a
         * *genuinely* blank id still surfaces on second attempt.
         */
        const val CLIENT_ID_BOOTSTRAP_GRACE_MS = 1_500L

        /**
         * Backoff before retrying after a transient transport failure.
         * Mostly covers the Spotify SDK's ~30s bound-service cold-start
         * timeout — by the time we land here Spotify is almost certainly
         * up, but a small pause gives the IPC channel a moment to reset.
         */
        const val TRANSPORT_RETRY_DELAY_MS = 800L

        /** The Web API's word on where Spotify plays; past this it counts as unknown. */
        const val CONNECT_STATE_TIMEOUT_MS = 2_500L

        /** How often Yoin reads the other device's state while it drives it. */
        const val WEB_POLL_MS = 2_000L

        /** How often a start on the other device is looked for. */
        const val WEB_START_POLL_MS = 600L

        /** How long after a command on the other device its state is read back. */
        const val WEB_COMMAND_SETTLE_MS = 500L

        /** The look at Spotify's state before a held start runs ([settleHeldStart]). */
        const val PLAYER_STATE_LOOK_TIMEOUT_MS = 2_000L
    }

    private fun failureFor(error: Throwable): SpotifyConnectFailure = when (error) {
        is CouldNotFindSpotifyApp -> SpotifyConnectFailure.SpotifyAppMissing
        is UserNotAuthorizedException ->
            userNotAuthorizedFailure(error.message, applicationContext.resources)
        is NotLoggedInException ->
            SpotifyConnectFailure.AuthFailure(
                applicationContext.getString(R.string.player_spotify_log_in),
            )
        is AuthenticationFailedException ->
            SpotifyConnectFailure.AuthFailure(
                applicationContext.getString(R.string.player_spotify_auth_reconnect),
            )
        is OfflineModeException ->
            SpotifyConnectFailure.TransportFailure(
                applicationContext.getString(R.string.player_spotify_offline),
            )
        is UnsupportedFeatureVersionException ->
            SpotifyConnectFailure.TransportFailure(
                applicationContext.getString(R.string.player_spotify_update_app),
            )
        is SpotifyDisconnectedException, is SpotifyRemoteServiceException ->
            SpotifyConnectFailure.TransportFailure(
                error.message ?: applicationContext.getString(R.string.player_spotify_disconnected),
            )
        else -> SpotifyConnectFailure.TransportFailure(
            error.message ?: applicationContext.getString(R.string.player_spotify_unavailable),
        )
    }

    private fun publish(snapshot: SpotifyRemoteSnapshot) {
        lastSnapshot = snapshot
        onSnapshot(snapshot)
    }

    private fun emitContextPlaybackActionIfNeeded(error: Throwable) {
        val failure = contextPlaybackActionFailure(error) ?: return
        onActionRequired(failure, failure.userMessage(applicationContext.resources))
    }

    private fun contextPlaybackActionFailure(error: Throwable): SpotifyConnectFailure? =
        when (error) {
            is SpotifyAuthException -> when (error.code) {
                HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> SpotifyConnectFailure.AuthFailure(
                    applicationContext.getString(R.string.player_spotify_track_only_fallback),
                )
                else -> null
            }

            else -> null
        }
}

/** Grace for the subscription to report the requested track after a start command answers. */
internal const val SPOTIFY_START_HEARD_TIMEOUT_MS = 4_000L

/** How long a start waits on a Spotify app that doesn't answer before the hint ([SpotifyAppRemotePlayer.openSpotify]). */
internal const val SPOTIFY_UNREACHABLE_AFTER_MS = 5_000L

/** Wait before reading Spotify's queue after a start or an Add to queue: the Web API lags the app a little. */
internal const val SPOTIFY_QUEUE_READ_DELAY_MS = 800L

/** Match Spotify's market-relinked copy without accepting a paused pre-start state. */
internal fun spotifyPlays(state: PlayerState, track: Track): Boolean {
    if (state.isPaused) return false
    val playing = state.track ?: return false
    if (track.id.provider == MediaId.PROVIDER_SPOTIFY && playing.uri == "spotify:track:${track.id.rawId}") return true
    val title = track.title?.takeIf(String::isNotBlank) ?: return false
    val artist = track.artist?.takeIf(String::isNotBlank) ?: return false
    val playingArtist = playing.artists?.firstOrNull()?.name ?: playing.artist?.name
    return title.equals(playing.name, ignoreCase = true) && artist.equals(playingArtist, ignoreCase = true)
}

/**
 * Turn a Spotify App Remote [com.spotify.protocol.types.ImageUri.raw] string
 * (e.g. `"spotify:image:ab67616d0000b273..."`) into a public CDN URL Coil
 * can fetch directly (`"https://i.scdn.co/image/ab67616d0000b273..."`).
 *
 * Naively concatenating the whole protocol URI onto the CDN prefix yields
 * a 404 — the CDN wants just the trailing image id. Returns `null` for
 * blank input and passes through values that are already URLs (defensive
 * against SDK version drift).
 */
internal fun spotifyImageUrlFromProtocolUri(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) return trimmed
    val imageId = trimmed.substringAfterLast(':').trim()
    if (imageId.isBlank()) return null
    return "https://i.scdn.co/image/$imageId"
}

internal fun userNotAuthorizedFailure(
    rawMessage: String?,
    resources: Resources? = null,
): SpotifyConnectFailure {
    val normalized = rawMessage.normalizedSpotifyErrorMessage()
    return when {
        normalized?.contains("premium", ignoreCase = true) == true ->
            SpotifyConnectFailure.PremiumRequired

        normalized?.contains("authorization is required", ignoreCase = true) == true ||
            normalized?.contains("auth-flow", ignoreCase = true) == true ||
            normalized?.contains("authorize", ignoreCase = true) == true ->
            SpotifyConnectFailure.AuthFailure(
                resources?.getString(R.string.player_spotify_needs_permission)
                    ?: "Spotify needs permission in the Spotify app. Open Spotify, approve access if prompted, and try again.", // i18n-allow: SpotifyTypedFailureMappingTest and SpotifyAppRemotePlayerMappingTest assert this English
            )

        else -> SpotifyConnectFailure.AuthFailure(
            normalized ?: resources?.getString(R.string.player_spotify_needs_authorization)
                ?: "Spotify needs authorization in the Spotify app. Open Spotify and try again.",
        )
    }
}

internal fun String?.normalizedSpotifyErrorMessage(): String? {
    val raw = this?.trim().orEmpty()
    if (raw.isBlank()) return null
    val embeddedMessage = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"")
        .find(raw)
        ?.groupValues
        ?.getOrNull(1)
    return (embeddedMessage ?: raw)
        .replace("\\\"", "\"")
        .trim()
        .ifBlank { null }
}

/** The call's data; null when Spotify answered without any. */
private suspend fun <T> CallResult<T>.awaitData(): T? = suspendCancellableCoroutine { continuation ->
    setResultCallback { data -> continuation.resume(data) }
    setErrorCallback { throwable -> continuation.resumeWithException(throwable) }
    continuation.invokeOnCancellation { cancel() }
}

private suspend fun CallResult<Empty>.awaitUnit() {
    suspendCancellableCoroutine<Unit> { continuation ->
        setResultCallback {
            continuation.resume(Unit)
        }
        setErrorCallback { throwable ->
            continuation.resumeWithException(throwable)
        }
        continuation.invokeOnCancellation {
            cancel()
        }
    }
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
