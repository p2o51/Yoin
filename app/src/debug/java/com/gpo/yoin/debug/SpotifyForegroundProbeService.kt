package com.gpo.yoin.debug

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.source.spotify.SpotifyAuthConfig
import com.gpo.yoin.player.PlaybackService.Companion.yoinNotificationProvider
import com.gpo.yoin.player.SpotifyAppRemoteCompat
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerContext
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Debug-only lifetime experiment: Spotify owns audio and focus; Yoin owns only a truthful
 * Media3 state mirror and the App Remote binding. No queue isolation or cross-source scheduling.
 */
@UnstableApi
class SpotifyForegroundProbeService : MediaSessionService() {
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var mirror: SpotifyForegroundProbePlayer
    private var session: MediaSession? = null
    private var remote: SpotifyAppRemote? = null
    private var subscription: Subscription<PlayerState>? = null
    private var contextSubscription: Subscription<PlayerContext>? = null
    private var connecting = false
    private var destroyed = false
    private var label = "baseline"
    private var commandId = 0L
    private var lastRaw: PlayerState? = null
    private var suggestedForeground = false
    private var wakeRequested = false
    private var wakeTimeoutMs = DEFAULT_WAKE_TIMEOUT_MS
    private var wakeDeadlineMs = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var finishDeadlineMs = 0L
    private var sessionClosed = false

    private val ticker = object : Runnable {
        override fun run() {
            if (destroyed) return
            poll()
            updateWakeLock()
            emit("heartbeat")
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        mirror = SpotifyForegroundProbePlayer(::execute)
        setMediaNotificationProvider(yoinNotificationProvider())
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        val launcher = Intent(this, SpotifyForegroundProbeActivity::class.java)
            .putExtra("cmd", "status")
        session = MediaSession.Builder(this, mirror)
            .setId("spotify-foreground-probe")
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 7190, launcher, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            ).build().also(::addSession)
        handler.post(ticker)
        emit("service_create")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action != ACTION_COMMAND) return START_NOT_STICKY
        label = intent.getStringExtra("label") ?: label
        if (intent.hasExtra("wake_lock")) {
            releaseWakeLock("configuration")
            wakeRequested = intent.getBooleanExtra("wake_lock", false)
            wakeTimeoutMs = intent.getLongExtra("wake_timeout_ms", DEFAULT_WAKE_TIMEOUT_MS)
                .coerceIn(1000, MAX_WAKE_TIMEOUT_MS)
            wakeDeadlineMs = 0
            updateWakeLock()
        }
        val name = intent.getStringExtra("cmd") ?: "connect"
        when (name) {
            "connect" -> connect()
            "status" -> {
                poll()
                emit("status")
            }
            "finish" -> finishSession()
            else -> execute(
                SpotifyProbeAction(
                    name = name,
                    uri = intent.getStringExtra("uri").orEmpty(),
                    position = intent.getLongExtra("position", 0),
                    mode = intent.getIntExtra("mode", Player.REPEAT_MODE_OFF)
                )
            )
        }
        return START_NOT_STICKY
    }

    private fun connect() {
        if (remote?.isConnected == true || connecting) {
            emit("connect_existing")
            return
        }
        connecting = true
        scope.launch {
            val clientId = withTimeoutOrNull(10_000) {
                (application as YoinApplication).container.spotifyClientIdFlow.first { it.isNotBlank() }
            }
            if (clientId == null) {
                connecting = false
                emit("connect_error", "type" to "MissingClientId")
                return@launch
            }
            if (destroyed) return@launch
            val params = ConnectionParams.Builder(clientId)
                .setRedirectUri(SpotifyAuthConfig.APP_REMOTE_REDIRECT_URI)
                // Consent is obtained by the visible Activity's explicit authorize command.
                .showAuthView(false).build()
            emit("connect_start", "owner" to "service")
            SpotifyAppRemoteCompat.connect(
                this@SpotifyForegroundProbeService,
                params,
                object : Connector.ConnectionListener {
                    override fun onConnected(connection: SpotifyAppRemote) {
                        handler.post {
                            if (destroyed) {
                                SpotifyAppRemote.disconnect(connection)
                                return@post
                            }
                            connecting = false
                            remote = connection
                            mirror.connectionChanged(true)
                            emit("connected", "owner" to "service")
                            subscription = connection.playerApi.subscribeToPlayerState().setEventCallback { sample ->
                                handler.post { if (remote === connection) observe("event", sample) }
                            }
                            subscription?.setErrorCallback { error ->
                                handler.post { if (remote === connection) lostConnection("subscription_error", error) }
                            }
                            contextSubscription = connection.playerApi.subscribeToPlayerContext()
                                .setEventCallback { value ->
                                    handler.post { emit("context", "uri" to value.uri, "title" to value.title) }
                                }
                            contextSubscription?.setErrorCallback { error ->
                                handler.post { emit("context_error", "type" to error.javaClass.simpleName) }
                            }
                            poll()
                        }
                    }

                    override fun onFailure(error: Throwable) {
                        handler.post {
                            connecting = false
                            lostConnection("connect_error", error)
                        }
                    }
                }
            )
        }
    }

    private fun poll() {
        val connection = remote ?: return
        if (!connection.isConnected) {
            lostConnection("disconnected", null)
            return
        }
        connection.playerApi.playerState.setResultCallback { sample ->
            handler.post { if (remote === connection) observe("poll", sample) }
        }.setErrorCallback { error ->
            handler.post { emit("poll_error", "type" to error.javaClass.simpleName) }
        }
    }

    private fun observe(kind: String, sample: PlayerState) {
        if (destroyed) return
        lastRaw = sample
        emit(
            kind,
            "raw_uri" to sample.track?.uri,
            "position" to sample.playbackPosition,
            "duration" to sample.track?.duration,
            "paused" to sample.isPaused,
            "speed" to sample.playbackSpeed,
            "repeat" to sample.playbackOptions?.repeatMode,
            "shuffle" to sample.playbackOptions?.isShuffling
        )
        mirror.observe(sample)
        updateWakeLock()
        // Finishing is an explicit user command. No heuristic natural-end or URI interception.
        if (finishDeadlineMs != 0L && (sample.isPaused || sample.track == null)) {
            emit("finish_pause_confirmed")
            closeSessionAndStop()
        }
    }

    private fun execute(action: SpotifyProbeAction): ListenableFuture<*> {
        val id = ++commandId
        emit(
            "command",
            "id" to id,
            "cmd" to action.name,
            "uri" to action.uri.takeIf { it.startsWith("spotify:track:") },
            "position" to action.position.takeIf { action.name == "seek" },
            "mode" to action.mode.takeIf { action.name == "repeat" }
        )
        val connection = remote?.takeIf { it.isConnected }
            ?: return rejected(id, action.name, "NotConnected")
        val api = connection.playerApi
        return when (action.name) {
            "play" -> {
                if (!TRACK_URI.matches(action.uri)) return rejected(id, action.name, "InvalidTrackUri")
                awaitAck(id, action.name, api.play(action.uri))
            }
            "pause" -> awaitAck(id, action.name, api.pause())
            "resume" -> awaitAck(id, action.name, api.resume())
            "repeat" -> {
                if (action.mode !in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL) {
                    return rejected(id, action.name, "InvalidRepeatMode")
                }
                awaitAck(id, action.name, api.setRepeat(action.mode))
            }
            "seek" -> awaitAck(id, action.name, api.seekTo(action.position.coerceAtLeast(0)))
            "next" -> awaitAck(id, action.name, api.skipNext())
            "previous" -> awaitAck(id, action.name, api.skipPrevious())
            else -> rejected(id, action.name, "UnknownCommand")
        }
    }

    private fun <T> awaitAck(id: Long, name: String, call: CallResult<T>): ListenableFuture<*> {
        val future = SettableFuture.create<Unit>()
        call.setResultCallback {
            handler.post {
                if (destroyed) {
                    future.cancel(false)
                } else {
                    emit("ack", "id" to id, "cmd" to name)
                    poll()
                    future.set(Unit)
                }
            }
        }.setErrorCallback { error ->
            handler.post {
                emit("command_error", "id" to id, "cmd" to name, "type" to error.javaClass.simpleName)
                future.setException(error)
            }
        }
        handler.postDelayed({
            if (!future.isDone) {
                emit("command_timeout", "id" to id, "cmd" to name)
                future.setException(java.util.concurrent.TimeoutException("Spotify command acknowledgement timed out"))
            }
        }, 10_000)
        return future
    }

    private fun rejected(id: Long, name: String, reason: String): ListenableFuture<*> {
        emit("command_rejected", "id" to id, "cmd" to name, "type" to reason)
        return Futures.immediateFailedFuture<Unit>(IllegalStateException(reason))
    }

    private fun lostConnection(kind: String, error: Throwable?) {
        if (destroyed) return
        subscription?.cancel()
        contextSubscription?.cancel()
        subscription = null
        contextSubscription = null
        remote?.let(SpotifyAppRemote::disconnect)
        remote = null
        lastRaw = null
        mirror.connectionChanged(false)
        releaseWakeLock(kind)
        emit(kind, "type" to error?.javaClass?.simpleName)
    }

    private fun updateWakeLock() {
        val sample = lastRaw
        val active = wakeRequested && remote?.isConnected == true && sample?.track != null && !sample.isPaused
        if (!active) {
            releaseWakeLock("inactive")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (wakeDeadlineMs == 0L) wakeDeadlineMs = now + wakeTimeoutMs
        val remaining = wakeDeadlineMs - now
        if (remaining <= 0L) {
            releaseWakeLock("timeout")
            return
        }
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:spotify-fg-probe")
            .apply {
                setReferenceCounted(false)
                acquire(remaining)
            }
        emit("wake_acquired", "timeout_ms" to remaining)
        handler.postDelayed({
            if (!destroyed && SystemClock.elapsedRealtime() >= wakeDeadlineMs) releaseWakeLock("timeout")
        }, remaining)
    }

    private fun releaseWakeLock(reason: String) {
        val lock = wakeLock ?: return
        if (lock.isHeld) lock.release()
        wakeLock = null
        emit("wake_released", "reason" to reason)
    }

    private fun finishSession() {
        releaseWakeLock("finish")
        wakeRequested = false
        emit("finish_requested")
        if (remote?.isConnected != true) {
            closeSessionAndStop()
            return
        }
        finishDeadlineMs = SystemClock.elapsedRealtime() + FINISH_TIMEOUT_MS
        execute(SpotifyProbeAction("pause"))
        handler.postDelayed({
            if (!destroyed && finishDeadlineMs != 0L) {
                emit("finish_pause_timeout")
                closeSessionAndStop()
            }
        }, FINISH_TIMEOUT_MS)
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        suggestedForeground = startInForegroundRequired
        emit("notification_before", "start_foreground_required" to startInForegroundRequired)
        super.onUpdateNotification(session, startInForegroundRequired)
        emit("notification_after", "start_foreground_required" to startInForegroundRequired)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        emit("task_removed")
        if (!isPlaybackOngoing) stopSelf()
    }

    private fun closeSessionAndStop() {
        if (sessionClosed) return
        sessionClosed = true
        finishDeadlineMs = 0
        subscription?.cancel()
        contextSubscription?.cancel()
        subscription = null
        contextSubscription = null
        remote?.let(SpotifyAppRemote::disconnect)
        remote = null
        releaseWakeLock("session_closed")
        session?.let {
            removeSession(it)
            it.release()
        }
        session = null
        mirror.release()
        emit("session_closed")
        stopSelf()
    }

    private fun emit(kind: String, vararg fields: Pair<String, Any?>) {
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        val json = JSONObject().put("elapsed", SystemClock.elapsedRealtime()).put("wall", System.currentTimeMillis())
            .put("kind", kind).put("label", label).put("pid", android.os.Process.myPid())
            .put("connected", remote?.isConnected == true).put("foreground_suggested", suggestedForeground)
            .put("playback_ongoing", isPlaybackOngoing).put("process_importance", process.importance)
            .put("wake_requested", wakeRequested).put("wake_held", wakeLock?.isHeld == true)
        fields.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        Log.i(TAG, json.toString())
    }

    override fun onDestroy() {
        emit("service_destroy")
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        subscription?.cancel()
        contextSubscription?.cancel()
        remote?.let(SpotifyAppRemote::disconnect)
        remote = null
        releaseWakeLock("destroy")
        if (!sessionClosed) {
            session?.let {
                removeSession(it)
                it.release()
            }
            session = null
            mirror.release()
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val TAG = "YoinSpotifyFgProbe"
        const val ACTION_COMMAND = "com.gpo.yoin.debug.SPOTIFY_FOREGROUND_COMMAND"
        private const val POLL_INTERVAL_MS = 1000L
        private const val FINISH_TIMEOUT_MS = 5000L
        private const val DEFAULT_WAKE_TIMEOUT_MS = 600_000L
        private const val MAX_WAKE_TIMEOUT_MS = 900_000L
        private val TRACK_URI = Regex("spotify:track:[A-Za-z0-9]+")
    }
}
