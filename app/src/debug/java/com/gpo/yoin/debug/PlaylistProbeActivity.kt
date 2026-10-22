package com.gpo.yoin.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.remote.applemusic.AppleMusicDeveloperTokenProvider
import com.gpo.yoin.data.source.spotify.SpotifyAuthConfig
import com.gpo.yoin.player.SpotifyAppRemoteCompat
import com.gpo.yoin.player.applemusic.AppleMusicMedia3Player
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerContext
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Shell-driven debug experiment. Intentionally keeps its binding onStop to measure actual SDK behavior. */
@UnstableApi
class PlaylistProbeActivity : Activity() {
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private var remote: SpotifyAppRemote? = null
    private var states: Subscription<PlayerState>? = null
    private var contexts: Subscription<PlayerContext>? = null
    private var apple: AppleMusicMedia3Player? = null
    private var label = "baseline"
    private lateinit var status: TextView
    private val ticker = object : Runnable {
        override fun run() {
            remote?.takeIf { it.isConnected }?.playerApi?.playerState
                ?.setResultCallback { recordState("poll", it) }
                ?.setErrorCallback { emit("poll_error", "type" to it.javaClass.simpleName) }
            apple?.let {
                emit(
                    "apple_poll",
                    "state" to it.playbackState,
                    "playing" to it.isPlaying,
                    "position" to it.currentPosition,
                    "duration" to it.duration,
                    "item" to it.currentMediaItem?.mediaId
                )
            }
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 20f
            setPadding(32, 64, 32, 32)
        }
        setContentView(status)
        emit("activity_create")
        handler.post(ticker)
        command(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        command(intent)
    }
    override fun onStart() {
        super.onStart()
        emit("activity_start")
    }
    override fun onStop() {
        emit("activity_stop")
        super.onStop()
    }
    private fun command(intent: Intent) {
        label = intent.getStringExtra("label") ?: label
        val cmd = intent.getStringExtra("cmd") ?: "connect"
        val uri = intent.getStringExtra("uri").orEmpty()
        emit("command", "cmd" to cmd, "uri" to uri.takeIf { it.startsWith("spotify:track:") })
        when (cmd) {
            "connect" -> connect()
            "play" -> remote?.playerApi?.play(uri)?.setResultCallback { emit("ack", "cmd" to cmd) }
                ?.setErrorCallback { emit("error", "cmd" to cmd, "type" to it.javaClass.simpleName) }
            "pause" -> remote?.playerApi?.pause()?.setResultCallback { emit("ack", "cmd" to cmd) }
            "resume" -> remote?.playerApi?.resume()?.setResultCallback { emit("ack", "cmd" to cmd) }
            "queue" -> remote?.playerApi?.queue(uri)?.setResultCallback { emit("ack", "cmd" to cmd) }
            "repeat" -> remote?.playerApi?.setRepeat(intent.getIntExtra("mode", 0))
                ?.setResultCallback { emit("ack", "cmd" to cmd) }
            "seek" -> remote?.playerApi?.seekTo(intent.getLongExtra("position", 0))
                ?.setResultCallback { emit("ack", "cmd" to cmd) }
            "next" -> remote?.playerApi?.skipNext()?.setResultCallback { emit("ack", "cmd" to cmd) }
            "apple" -> scope.launch {
                try {
                    remote?.playerApi?.pause()
                    val manager = (application as YoinApplication).container.profileManager
                    val profile = manager.profiles.first().first { it.provider == "applemusic" }
                    val credentials = manager.decodeCredentials(profile) as ProfileCredentials.AppleMusic
                    val tokens = AppleMusicDeveloperTokenProvider(credentials.endpoint.toHttpUrl())
                    val developer = tokens.token()
                    if (apple == null) {
                        apple = AppleMusicMedia3Player(
                            this@PlaylistProbeActivity, developer,
                            credentials.musicUserToken, refreshDeveloperToken = { tokens.token() },
                            onObservation = { observation ->
                                emit(
                                    "apple_native", "playing" to observation.playing,
                                    "stopped" to observation.stopped, "error" to observation.error,
                                    "position" to observation.positionMs, "duration" to observation.durationMs,
                                    "item" to observation.catalogId, "queue_epoch" to observation.queueEpoch,
                                    "queue_item" to observation.nativeQueueItemId,
                                    "start_confirmed" to observation.startPositionConfirmed,
                                    "sequence" to observation.sequence
                                )
                            }
                        ).also { player ->
                            player.addListener(object : Player.Listener {
                                override fun onPlaybackStateChanged(playbackState: Int) {
                                    emit(
                                        "apple_media3_state", "state" to playbackState,
                                        "position" to player.currentPosition, "item" to player.currentMediaItem?.mediaId
                                    )
                                }
                                override fun onIsPlayingChanged(isPlaying: Boolean) {
                                    emit(
                                        "apple_media3_playing", "playing" to isPlaying,
                                        "position" to player.currentPosition, "item" to player.currentMediaItem?.mediaId
                                    )
                                }
                            })
                        }
                    }
                    val ids = intent.getStringExtra("ids").orEmpty().split(',').filter { it.isNotBlank() }
                    apple!!.setMediaItems(
                        ids.map { MediaItem.Builder().setMediaId("applemusic:$it").build() },
                        0,
                        intent.getLongExtra("start_position", 0)
                    )
                    apple!!.repeatMode = Player.REPEAT_MODE_OFF
                    apple!!.playWhenReady = true
                    apple!!.prepare()
                } catch (e: Exception) {
                    emit("apple_error", "type" to e.javaClass.simpleName)
                }
            }
            "apple_seek" -> apple?.seekTo(intent.getLongExtra("position", 0))
            "apple_pause" -> apple?.pause()
            "apple_play" -> apple?.play()
            "apple_next" -> apple?.seekToNextMediaItem()
            "apple_stop" -> apple?.stop()
            "apple_repeat" -> apple?.repeatMode = intent.getIntExtra("mode", Player.REPEAT_MODE_OFF)
            "apple_shuffle" -> apple?.shuffleModeEnabled = intent.getBooleanExtra("enabled", false)
            "finish" -> finish()
        }
    }
    private fun connect() {
        if (remote?.isConnected == true) {
            emit("connected", "existing" to true)
            return
        }
        scope.launch {
            val client = (application as YoinApplication).container.spotifyClientIdFlow.first { it.isNotBlank() }
            val params = ConnectionParams.Builder(client).setRedirectUri(SpotifyAuthConfig.APP_REMOTE_REDIRECT_URI)
                .showAuthView(true).build()
            SpotifyAppRemoteCompat.connect(
                this@PlaylistProbeActivity,
                params,
                object : Connector.ConnectionListener {
                    override fun onConnected(connected: SpotifyAppRemote) {
                        remote = connected
                        emit("connected")
                        states = connected.playerApi.subscribeToPlayerState().setEventCallback {
                            recordState("event", it)
                        }
                        states?.setErrorCallback { emit("subscription_error", "type" to it.javaClass.simpleName) }
                        contexts = connected.playerApi.subscribeToPlayerContext().setEventCallback {
                            emit("context", "uri" to it.uri, "title" to it.title)
                        }
                    }
                    override fun onFailure(error: Throwable) {
                        emit("connect_error", "type" to error.javaClass.simpleName)
                    }
                }
            )
        }
    }
    private fun recordState(kind: String, state: PlayerState) {
        emit(
            kind,
            "uri" to state.track?.uri,
            "position" to state.playbackPosition,
            "duration" to state.track?.duration,
            "paused" to state.isPaused,
            "speed" to state.playbackSpeed,
            "repeat" to state.playbackOptions?.repeatMode,
            "shuffle" to state.playbackOptions?.isShuffling
        )
    }
    private fun emit(kind: String, vararg fields: Pair<String, Any?>) {
        val json = JSONObject().put("elapsed", SystemClock.elapsedRealtime()).put("wall", System.currentTimeMillis())
            .put("label", label).put("kind", kind)
        fields.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        Log.i("YoinPlaylistProbe", json.toString())
        if (::status.isInitialized) status.text = "Yoin Playlist debug probe\n$label\n$kind\n$json"
    }
    override fun onDestroy() {
        emit("activity_destroy")
        handler.removeCallbacksAndMessages(null)
        states?.cancel()
        contexts?.cancel()
        remote?.let(SpotifyAppRemote::disconnect)
        apple?.stop()
        apple?.release()
        scope.cancel()
        super.onDestroy()
    }
}
