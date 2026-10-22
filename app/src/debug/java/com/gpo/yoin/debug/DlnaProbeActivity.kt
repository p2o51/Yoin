package com.gpo.yoin.debug

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.source.subsonic.SubsonicMusicSource
import com.gpo.yoin.player.PlaybackService
import com.gpo.yoin.player.dlna.DlnaManager
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Shell-only smoke test. Fixture profiles can be created only in a disposable .dlnaqa app. */
@UnstableApi
@SuppressLint("Overdraw") // Debug-only programmatic view; avoids the detector's FIR property inference crash.
class DlnaProbeActivity : Activity() {
    private val scope = MainScope()
    private val manager: DlnaManager get() = (application as YoinApplication).container.dlnaManager
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 20f
            setPadding(32, 64, 32, 32)
        }
        setContentView(status)
        val pending = MediaController.Builder(
            this,
            SessionToken(this, ComponentName(this, PlaybackService::class.java))
        )
            .buildAsync()
        controllerFuture = pending
        pending.addListener({
            if (isFinishing || isDestroyed) {
                MediaController.releaseFuture(pending)
                return@addListener
            }
            runCatching { pending.get() }.onSuccess { connected ->
                controller = connected
                connected.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = record(player)
                })
                command(intent)
                scope.launch {
                    while (true) {
                        record(connected)
                        delay(1000)
                    }
                }
            }.onFailure { emit("session_error ${it.javaClass.simpleName}") }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (controller != null) command(intent)
    }

    private fun command(request: Intent) {
        val cmd = request.getStringExtra("cmd") ?: "discover"
        emit("command $cmd")
        scope.launch {
            try {
                when (cmd) {
                    "setup_fixture" -> {
                        // This path may create a test account only in a separate, disposable APK.
                        check(packageName.endsWith(".dlnaqa"))
                        val base = request.getStringExtra("stream_base") ?: error("Missing fixture base")
                        val container = (application as YoinApplication).container
                        val profiles = container.profileManager
                        val profile = profiles.create(
                            "DLNA fixture",
                            ProfileCredentials.Subsonic(base, "fixture", "fixture")
                        )
                        profiles.switchTo(profile.id)
                        check(profiles.activeProfileId.value == profile.id) { "Fixture profile switch failed" }
                        val source = requireNotNull(profiles.activeSource.value) as? SubsonicMusicSource
                            ?: error("Fixture did not create a Subsonic source")
                        val album = requireNotNull(source.library().getAlbum(MediaId.subsonic("fixture")))
                        check(album.tracks.map { it.id } == listOf(MediaId.subsonic("a"), MediaId.subsonic("b")))
                        check(album.tracks.all { it.extras["subsonic.contentType"] == "audio/wav" })
                        container.playbackManager.connect()
                        container.playbackManager.play(album.tracks, 0, source)
                        withTimeout(10_000) {
                            container.playbackManager.playbackState.first {
                                it.currentTrack?.id == MediaId.subsonic("a") && it.isPlaying
                            }
                            while (controller?.isPlaying != true || (controller?.currentPosition ?: 0) <= 0) delay(50)
                        }
                        check(!PlaybackService.usingDlna.value)
                        emit(
                            "fixture local_playing=true tracks=${album.tracks.size} " +
                                "position=${controller?.currentPosition}"
                        )
                    }
                    "transfer" -> {
                        manager.discover()
                        val host = request.getStringExtra("host") ?: error("Missing renderer host")
                        val device = manager.devices.value.first { it.descriptionUrl.host == host }
                        val expectedItem = controller?.currentMediaItem?.mediaId
                        val shouldPlay = controller?.playWhenReady == true
                        val result = (application as YoinApplication).container.playbackManager.transferToDlna(device)
                        result.getOrThrow()
                        withTimeout(10_000) {
                            PlaybackService.usingDlna.first { it }
                            while (
                                manager.player.playWhenReady != shouldPlay || shouldPlay && !manager.player.isPlaying
                                ) delay(
                                    100
                                )
                        }
                        check(manager.player.currentMediaItem?.mediaId == expectedItem)
                        emit(
                            "transfer success=true remote_playing=${manager.player.isPlaying} " +
                                "position=${manager.player.currentPosition}"
                        )
                    }
                    "handback" -> {
                        check(manager.connectedDevice.value != null)
                        val expectedItem = manager.player.currentMediaItem?.mediaId
                        val expectedIndex = manager.player.currentMediaItemIndex
                        val expectedPosition = manager.player.currentPosition
                        val shouldPlay = manager.player.playWhenReady
                        val result = (application as YoinApplication).container.playbackManager.transferFromDlna()
                        result.getOrThrow()
                        withTimeout(10_000) {
                            PlaybackService.usingDlna.first { !it }
                            while (
                                controller?.currentMediaItem?.mediaId != expectedItem ||
                                controller?.playWhenReady != shouldPlay || shouldPlay && controller?.isPlaying != true
                                ) delay(100)
                        }
                        check(controller?.currentMediaItemIndex == expectedIndex)
                        check(abs((controller?.currentPosition ?: 0) - expectedPosition) <= 3000)
                        emit(
                            "handback success=true local_playing=${controller?.isPlaying} " +
                                "position=${controller?.currentPosition}"
                        )
                    }
                    "discover" -> {
                        manager.discover()
                        emit("discovered count=${manager.devices.value.size}")
                    }
                    "connect" -> {
                        manager.discover()
                        val host = request.getStringExtra("host") ?: error("Missing renderer host")
                        val device = manager.devices.value.first { it.descriptionUrl.host == host }
                        val base = request.getStringExtra("stream_base") ?: error("Missing test stream base")
                        val seconds = request.getIntExtra("duration_secs", 12)
                        val items = listOf("a", "b").map { id ->
                            MediaItem.Builder().setMediaId("subsonic:dlna-smoke-$id")
                                .setUri("$base/rest/stream.view?id=$id").setMimeType("audio/wav")
                                .setMediaMetadata(
                                    MediaMetadata.Builder().setTitle("DLNA smoke $id")
                                        .setArtist("Yoin hardware validation")
                                        .setExtras(Bundle().apply { putInt("duration_secs", seconds) }).build()
                                ).build()
                        }
                        val result = manager.connect(device, items, 0, 0, true)
                        emit("connect success=${result.isSuccess}")
                    }
                    "pause" -> controller?.pause()
                    "play" -> controller?.play()
                    "next" -> controller?.seekToNextMediaItem()
                    "previous" -> controller?.seekToPreviousMediaItem()
                    "seek" -> controller?.seekTo(request.getLongExtra("position_ms", 0))
                    "repeat" -> controller?.repeatMode = request.getIntExtra("mode", 0)
                    "disconnect" -> emit("disconnect success=${manager.disconnect().isSuccess}")
                    "finish" -> finish()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                emit("error command=$cmd type=${failure.javaClass.simpleName}")
            }
        }
    }

    private fun record(player: Player) {
        emit(
            "state playback=${player.playbackState} playing=${player.isPlaying} position=${player.currentPosition} " +
                "index=${player.currentMediaItemIndex} count=${player.mediaItemCount} " +
                "item=${player.currentMediaItem?.mediaId} error=${player.playerError?.errorCode}"
        )
    }

    private fun emit(message: String) {
        Log.i("YoinDlnaProbe", message)
        status.text = message
    }

    override fun onDestroy() {
        scope.cancel()
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        controller = null
        super.onDestroy()
    }
}
