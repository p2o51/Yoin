package com.gpo.yoin.debug

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/** DUMP-protected shell launcher. Closing/backgrounding it does not own or stop the experiment. */
@SuppressLint("Overdraw")
@UnstableApi
class MixedPlaylistProbeActivity : Activity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pending: Intent? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 18f
            setPadding(32, 64, 32, 32)
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
        val request = pending ?: return
        pending = null
        val command = request.getStringExtra("cmd") ?: "dump"
        status.text = "Mixed playlist debug probe\n$command\n${MixedPlaylistProbeService.lastDump}"
        if (command.startsWith("session_")) {
            sendSessionCommand(command.removePrefix("session_"), request)
        } else {
            val service = Intent(this, MixedPlaylistProbeService::class.java)
                .setAction(MixedPlaylistProbeService.ACTION_COMMAND)
            request.extras?.let(service::putExtras)
            if (command == "start") ContextCompat.startForegroundService(this, service) else startService(service)
        }
    }

    private fun sendSessionCommand(command: String, request: Intent) {
        controllerFuture?.let(MediaController::releaseFuture)
        val future = MediaController.Builder(
            this,
            SessionToken(this, ComponentName(this, MixedPlaylistProbeService::class.java))
        ).buildAsync()
        controllerFuture = future
        future.addListener({
            if (isDestroyed || isFinishing) return@addListener
            runCatching {
                val controller = future.get()
                when (command) {
                    "play" -> controller.play()
                    "pause" -> controller.pause()
                    "next" -> controller.seekToNextMediaItem()
                    "previous" -> controller.seekToPreviousMediaItem()
                    "seek" -> controller.seekTo(request.getLongExtra("position", 0))
                    "stop" -> controller.stop()
                    else -> error("Unsupported session command")
                }
                status.text = "Sent MediaSession $command\n${MixedPlaylistProbeService.lastDump}"
            }.onFailure {
                status.text = "MediaSession command failed: ${it.javaClass.simpleName}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onStop()
    }

    override fun onDestroy() {
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onDestroy()
    }
}
