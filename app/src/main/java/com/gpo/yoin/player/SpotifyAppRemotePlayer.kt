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

    fun onHostStart(context: Context) {
        Log.d(tag, "onHostStart: host=${context.javaClass.simpleName}")
        hostContext = context
        if (wantsConnection) {
            connectIfPossible()
        }
    }

    fun onHostStop() {
        Log.d(tag, "onHostStop")
        hostContext = null
        // Fresh foreground cycle → restore the one-shot retry budget. If
        // the user dismissed the app after a real auth failure, next
        // launch should get one more automatic attempt.
        coldStartAuthRetryAvailable = true
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
            publish(
                lastSnapshot.copy(
                    connectionPhase = ConnectionPhase.Error,
                    connectionErrorMessage = SpotifyConnectFailure.NoClientId.userMessage(),
                    connectionFailure = SpotifyConnectFailure.NoClientId,
                ),
            )
            return
        }
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
                // Successful connect — if a cold-start auth retry had been
                // consumed, refill the budget so a later hiccup in the same
                // session can also get one free attempt.
                coldStartAuthRetryAvailable = true
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
            coverArt = imageUri?.raw?.takeIf(String::isNotBlank)?.let { CoverRef.Url("https://i.scdn.co/image/$it") },
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
        if (!wantsConnection) return
        // Transient transport errors can be recovered by reconnecting.
        // Permanent failures (no Spotify app, non-Premium, bad auth) must not
        // retry — it would just churn and keep the error state flashing.
        if (failure is SpotifyConnectFailure.TransportFailure &&
            (error is SpotifyDisconnectedException || error is SpotifyRemoteServiceException)
        ) {
            disconnectRemote(preserveSnapshot = true)
            connectIfPossible()
        }
    }

    private companion object {
        /**
         * Gap between the failing connect and the silent retry. Long enough
         * for the SDK's auth flow to finish persisting consent, short enough
         * that the user doesn't perceive a visible hang. 1.2s empirically
         * covers the slow-device case without being noticeable on a Pixel.
         */
        const val COLD_START_AUTH_RETRY_DELAY_MS = 1_200L
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
