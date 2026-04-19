package com.gpo.yoin.player

import android.content.Context
import android.util.Log
import androidx.media3.common.Player
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.spotify.SpotifyAuthConfig
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
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class SpotifyRemoteSnapshot(
    val currentTrack: Track? = null,
    /** Track the caller asked to play; held until first real PlayerState arrives. */
    val pendingTrack: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,
    val connectionPhase: ConnectionPhase = ConnectionPhase.Idle,
    val connectionErrorMessage: String? = null,
    val connectionFailure: SpotifyConnectFailure? = null,
    val observedPlayerState: Boolean = false,
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

    fun userMessage(): String = when (this) {
        NoClientId -> "Open Settings → Spotify to enter a Client ID."
        SpotifyAppMissing -> "Install the Spotify app to play Spotify tracks."
        PremiumRequired -> "Spotify Premium is required for in-app playback."
        is AuthFailure -> message.ifBlank { "Spotify authorization failed." }
        is TransportFailure -> message.ifBlank { "Spotify playback connection was lost." }
    }
}

internal class SpotifyAppRemotePlayer(
    private val applicationContext: Context,
    private val clientIdProvider: () -> String,
    private val onSnapshot: (SpotifyRemoteSnapshot) -> Unit,
) {
    private val tag = "SpotifyAppRemotePlayer"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var hostContext: Context? = null
    private var remote: SpotifyAppRemote? = null
    private var playerStateSubscription: Subscription<PlayerState>? = null
    private var connectJob: Job? = null
    private var wantsConnection: Boolean = false
    private var mirroredQueue: List<Track> = emptyList()
    private var lastSnapshot: SpotifyRemoteSnapshot = SpotifyRemoteSnapshot()
    private val pendingOperations = mutableListOf<suspend (SpotifyAppRemote) -> Unit>()

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

    fun onHostStart(context: Context) {
        Log.d(tag, "onHostStart: host=${context.javaClass.simpleName}")
        hostContext = context
        if (wantsConnection) {
            connectIfPossible()
        }
    }

    /**
     * Opportunistically establish a live App Remote connection without the
     * user having tapped anything yet, so the UI can reflect whatever
     * Spotify happens to be playing externally (e.g. the user was already
     * listening via car audio, smart speaker, or another device).
     *
     * Idempotent — no-op when the remote is already connected or already
     * connecting. Treated as a "soft" connection attempt: if we can't
     * reach the Spotify app right now, we stay silent (no banner, no
     * snapshot with `Error`). The real failure path still fires when the
     * user explicitly plays something.
     */
    fun warmConnection() {
        if (remote?.isConnected == true || connectJob?.isActive == true) return
        Log.d(tag, "warmConnection: attempting soft connect for PlayerState observation")
        wantsConnection = true
        connectIfPossible()
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
        disconnectRemote(preserveSnapshot = true)
    }

    fun disconnect(resetState: Boolean) {
        Log.d(tag, "disconnect(resetState=$resetState)")
        wantsConnection = false
        pendingOperations.clear()
        mirroredQueue = emptyList()
        connectJob?.cancel()
        connectJob = null
        disconnectRemote(preserveSnapshot = !resetState)
        if (resetState) {
            publish(SpotifyRemoteSnapshot())
        }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        Log.d(tag, "playQueue: size=${tracks.size} startIndex=$startIndex track=${tracks[startIndex].id}")
        mirroredQueue = tracks
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
                queue = tracks,
                currentIndex = startIndex,
                repeatMode = lastSnapshot.repeatMode,
                shuffleEnabled = lastSnapshot.shuffleEnabled,
                connectionPhase = ConnectionPhase.Connecting,
                connectionErrorMessage = null,
                observedPlayerState = false,
            ),
        )
        enqueueOperation(replacePending = true) { connected ->
            val current = tracks[startIndex]
            connected.playerApi.play(current.spotifyUri()).awaitUnit()
            tracks.drop(startIndex + 1).forEach { track ->
                connected.playerApi.queue(track.spotifyUri()).awaitUnit()
            }
        }
    }

    fun pause() {
        Log.d(tag, "pause")
        enqueueOperation { connected ->
            connected.playerApi.pause().awaitUnit()
        }
    }

    fun resume() {
        Log.d(tag, "resume")
        enqueueOperation { connected ->
            connected.playerApi.resume().awaitUnit()
        }
    }

    fun skipNext() {
        enqueueOperation { connected ->
            connected.playerApi.skipNext().awaitUnit()
        }
    }

    fun skipPrevious() {
        enqueueOperation { connected ->
            connected.playerApi.skipPrevious().awaitUnit()
        }
    }

    fun seekTo(positionMs: Long) {
        enqueueOperation { connected ->
            connected.playerApi.seekTo(positionMs.coerceAtLeast(0L)).awaitUnit()
        }
    }

    fun setRepeatMode(mode: Int) {
        enqueueOperation { connected ->
            connected.playerApi.setRepeat(mode.coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL))
                .awaitUnit()
        }
    }

    fun toggleShuffle() {
        val target = !lastSnapshot.shuffleEnabled
        enqueueOperation { connected ->
            connected.playerApi.setShuffle(target).awaitUnit()
        }
    }

    fun addToQueue(track: Track) {
        mirroredQueue = mirroredQueue + track
        publish(lastSnapshot.copy(queue = mirroredQueue))
        enqueueOperation { connected ->
            connected.playerApi.queue(track.spotifyUri()).awaitUnit()
        }
    }

    fun clearQueue() {
        mirroredQueue = emptyList()
        publish(
            lastSnapshot.copy(
                queue = emptyList(),
                currentIndex = if (lastSnapshot.currentTrack != null) 0 else -1,
            ),
        )
    }

    fun skipToQueueItem(index: Int) {
        if (index !in mirroredQueue.indices) return
        playQueue(mirroredQueue, index)
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
            pendingOperations.clear()
        }
        val connected = remote
        if (connected != null && connected.isConnected) {
            scope.launch {
                runCatching { operation(connected) }
                    .onFailure(::handleRemoteError)
            }
            return
        }
        pendingOperations += operation
        connectIfPossible()
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
                    connectionErrorMessage = SpotifyConnectFailure.NoClientId.userMessage(),
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
                remote = connected
                // Successful connect — refill all single-shot retry budgets so
                // a later hiccup in the same session can also get one free
                // attempt at each recovery path.
                coldStartAuthRetryAvailable = true
                transportRetryAvailable = true
                subscribeToPlayerState(connected)
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
                handleRemoteError(error)
            }
            connectJob = null
        }
    }

    private suspend fun connect(
        context: Context,
        clientId: String,
    ): SpotifyAppRemote = suspendCancellableCoroutine { continuation ->
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
        SpotifyAppRemote.connect(
            context,
            params,
            object : Connector.ConnectionListener {
                override fun onConnected(spotifyAppRemote: SpotifyAppRemote) {
                    continuation.resume(spotifyAppRemote)
                }

                override fun onFailure(throwable: Throwable) {
                    continuation.resumeWithException(throwable)
                }
            },
        )
    }

    private fun flushPendingOperations(connected: SpotifyAppRemote) {
        val operations = pendingOperations.toList()
        pendingOperations.clear()
        Log.d(tag, "flushPendingOperations: count=${operations.size}")
        scope.launch {
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
                    publish(fromPlayerState(playerState))
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

    private fun disconnectRemote(preserveSnapshot: Boolean) {
        Log.d(tag, "disconnectRemote(preserveSnapshot=$preserveSnapshot)")
        playerStateSubscription?.cancel()
        playerStateSubscription = null
        remote?.let(SpotifyAppRemote::disconnect)
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
        val queueIndex = mappedTrack?.let { current ->
            mirroredQueue.indexOfFirst { queued -> queued.id == current.id }
        } ?: -1
        val queue = when {
            queueIndex >= 0 -> mirroredQueue
            mirroredQueue.isNotEmpty() -> mirroredQueue
            mappedTrack != null -> listOf(mappedTrack)
            lastSnapshot.queue.isNotEmpty() -> lastSnapshot.queue
            else -> mirroredQueue
        }
        val currentTrack = when {
            queueIndex >= 0 -> queue[queueIndex]
            mappedTrack != null -> mappedTrack
            else -> lastSnapshot.currentTrack
        }
        return lastSnapshot.copy(
            currentTrack = currentTrack,
            pendingTrack = null,
            isPlaying = !playerState.isPaused,
            positionMs = playerState.playbackPosition.coerceAtLeast(0L),
            durationMs = currentTrack?.durationSec?.times(1_000L)
                ?: playerState.track?.duration?.coerceAtLeast(0L)
                ?: lastSnapshot.durationMs,
            queue = queue,
            currentIndex = when {
                queueIndex >= 0 -> queueIndex
                currentTrack != null -> 0
                else -> lastSnapshot.currentIndex
            },
            repeatMode = playerState.playbackOptions?.repeatMode ?: Player.REPEAT_MODE_OFF,
            shuffleEnabled = playerState.playbackOptions?.isShuffling == true,
            connectionPhase = ConnectionPhase.Ready,
            connectionErrorMessage = null,
            observedPlayerState = true,
        )
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
        )
    }

    private fun Track.spotifyUri(): String {
        require(id.provider == MediaId.PROVIDER_SPOTIFY) {
            "SpotifyAppRemotePlayer received a non-Spotify track: ${id}"
        }
        return "spotify:track:${id.rawId}"
    }

    private fun handleRemoteError(error: Throwable) {
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
            pendingOperations.isNotEmpty()
        ) {
            coldStartAuthRetryAvailable = false
            Log.d(tag, "handleRemoteError: silently retrying cold-start UserNotAuthorizedException")
            // Keep UI in Connecting during the retry window. Do NOT publish
            // an Error snapshot — if the retry succeeds the user never
            // sees a flash of failure. If the retry also fails we'll fall
            // back through this same method and publish Error then.
            disconnectRemote(preserveSnapshot = true)
            scope.launch {
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
            wantsConnection
        ) {
            transportRetryAvailable = false
            Log.d(tag, "handleRemoteError: silently retrying transport failure after ${TRANSPORT_RETRY_DELAY_MS}ms")
            disconnectRemote(preserveSnapshot = true)
            scope.launch {
                kotlinx.coroutines.delay(TRANSPORT_RETRY_DELAY_MS)
                connectIfPossible()
            }
            return
        }

        // Warm-connect-only failure: nobody's actively waiting on us
        // (no queued ops, no PlayerState ever observed). Surfacing an
        // Error banner here is just noise — the user wasn't trying to do
        // anything Spotify-related yet. Stay quiet and let the next user
        // action retrigger the connect attempt naturally.
        if (!isUserWaitingOnConnection()) {
            Log.d(tag, "handleRemoteError: warm-connect failure, suppressing Error banner")
            disconnectRemote(preserveSnapshot = true)
            return
        }

        val message = failure.userMessage()
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
    private fun isUserWaitingOnConnection(): Boolean =
        pendingOperations.isNotEmpty() || lastSnapshot.observedPlayerState

    private companion object {
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
    }

    private fun failureFor(error: Throwable): SpotifyConnectFailure = when (error) {
        is CouldNotFindSpotifyApp -> SpotifyConnectFailure.SpotifyAppMissing
        is UserNotAuthorizedException -> userNotAuthorizedFailure(error.message)
        is NotLoggedInException ->
            SpotifyConnectFailure.AuthFailure("Log in to the Spotify app to continue.")
        is AuthenticationFailedException ->
            SpotifyConnectFailure.AuthFailure(
                "Spotify authorization failed. Reconnect the profile and try again.",
            )
        is OfflineModeException ->
            SpotifyConnectFailure.TransportFailure("Spotify is offline right now.")
        is UnsupportedFeatureVersionException ->
            SpotifyConnectFailure.TransportFailure(
                "Update the Spotify app to use in-app playback.",
            )
        is SpotifyDisconnectedException, is SpotifyRemoteServiceException ->
            SpotifyConnectFailure.TransportFailure(
                error.message ?: "Spotify playback connection was lost.",
            )
        else -> SpotifyConnectFailure.TransportFailure(
            error.message ?: "Spotify playback is unavailable.",
        )
    }

    private fun publish(snapshot: SpotifyRemoteSnapshot) {
        lastSnapshot = snapshot
        onSnapshot(snapshot)
    }
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

internal fun userNotAuthorizedFailure(rawMessage: String?): SpotifyConnectFailure {
    val normalized = rawMessage.normalizedSpotifyErrorMessage()
    return when {
        normalized?.contains("premium", ignoreCase = true) == true ->
            SpotifyConnectFailure.PremiumRequired

        normalized?.contains("authorization is required", ignoreCase = true) == true ||
            normalized?.contains("auth-flow", ignoreCase = true) == true ||
            normalized?.contains("authorize", ignoreCase = true) == true ->
            SpotifyConnectFailure.AuthFailure(
                "Spotify needs permission in the Spotify app. Open Spotify, approve access if prompted, and try again.",
            )

        else -> SpotifyConnectFailure.AuthFailure(
            normalized ?: "Spotify needs authorization in the Spotify app. Open Spotify and try again.",
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
