package com.gpo.yoin.debug

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.source.spotify.SpotifyAuthConfig
import com.gpo.yoin.player.SpotifyAppRemoteCompat
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/** DUMP-protected shell entry point. Service lifetime is independent of this visible launcher. */
@SuppressLint("Overdraw")
@UnstableApi
class SpotifyForegroundProbeActivity : Activity() {
    private val scope = MainScope()
    private var pending: Intent? = null
    private var authorizing = false
    private var resumed = false
    private var authRemote: SpotifyAppRemote? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 18f
            setPadding(32, 64, 32, 32)
            text = "Spotify foreground experiment\nNo audio is rendered by Yoin."
        }
        setContentView(status)
        pending = intent
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pending = intent
    }

    override fun onPostResume() {
        super.onPostResume()
        resumed = true
        dispatchPending()
    }

    private fun dispatchPending() {
        val command = pending ?: return
        pending = null
        if (command.getStringExtra("cmd") == "authorize") authorize(command) else forward(command)
    }

    private fun authorize(command: Intent) {
        if (authorizing) return
        authorizing = true
        scope.launch {
            val clientId = (application as YoinApplication).container.spotifyClientIdFlow.first { it.isNotBlank() }
            val params = ConnectionParams.Builder(clientId)
                .setRedirectUri(SpotifyAuthConfig.APP_REMOTE_REDIRECT_URI).showAuthView(true).build()
            SpotifyAppRemoteCompat.connect(
                this@SpotifyForegroundProbeActivity,
                params,
                object : Connector.ConnectionListener {
                    override fun onConnected(remote: SpotifyAppRemote) {
                        if (isDestroyed) {
                            SpotifyAppRemote.disconnect(remote)
                            return
                        }
                        authRemote = remote
                        // Release the Activity-owned authorization binding before Service creates its own.
                        SpotifyAppRemote.disconnect(remote)
                        authRemote = null
                        authorizing = false
                        log("authorization_ready")
                        pending = Intent(command).putExtra("cmd", "connect")
                        if (resumed) dispatchPending()
                    }

                    override fun onFailure(error: Throwable) {
                        authorizing = false
                        log("authorization_error", error.javaClass.simpleName)
                    }
                }
            )
        }
    }

    private fun forward(command: Intent) {
        val service = Intent(this, SpotifyForegroundProbeService::class.java)
            .setAction(SpotifyForegroundProbeService.ACTION_COMMAND)
        command.extras?.let(service::putExtras)
        // A visible Activity starts an ordinary started service. Media3 promotes it only
        // after its Player reports actual observed playback; no fake bootstrap PLAYING.
        startService(service)
        log("forwarded", command.getStringExtra("cmd") ?: "connect")
    }

    private fun log(kind: String, detail: String = "") {
        val event = JSONObject().put("kind", kind).put("detail", detail)
            .put("elapsed", SystemClock.elapsedRealtime()).put("wall", System.currentTimeMillis())
        Log.i(SpotifyForegroundProbeService.TAG, event.toString())
        status.text = "Spotify foreground experiment\n$event\nHome/lock leaves Service ownership intact."
    }

    override fun onStop() {
        log("activity_stop")
        super.onStop()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        authRemote?.let(SpotifyAppRemote::disconnect)
        scope.cancel()
        super.onDestroy()
    }
}
