package com.gpo.yoin.player.applemusic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.gpo.yoin.MainActivity
import com.gpo.yoin.R
import com.gpo.yoin.data.remote.applemusic.AppleMusicDeveloperTokenProvider
import com.gpo.yoin.data.remote.applemusic.AppleMusicValidationStore
import com.gpo.yoin.symbols.R as SymbolsR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Isolated integration gate. Does not create a selectable Profile until account QA is complete. */
@UnstableApi
class AppleMusicValidationService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private var player: AppleMusicMedia3Player? = null
    private var generation = 0
    private var startJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != PLAY) return super.onStartCommand(intent, flags, startId)
        val catalogId = intent.getStringExtra("catalogId")
        if (catalogId == null || !catalogId.matches(Regex("[0-9]+"))) {
            stopSelf()
            return START_NOT_STICKY
        }
        val currentGeneration = ++generation
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.player_apple_music_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        startForeground(
            BOOTSTRAP_NOTIFICATION,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(SymbolsR.drawable.ic_yoin_music_note)
                .setContentTitle(getString(R.string.player_apple_music_notification_title))
                .setContentText(getString(R.string.player_apple_music_preparing))
                .setContentIntent(openApp)
                .build(),
        )
        observation.value = AppleMusicPlaybackObservation(
            message = getString(R.string.player_apple_music_connecting),
        )
        startJob?.cancel()
        startJob = scope.launch {
            try {
                val account = requireNotNull(AppleMusicValidationStore(this@AppleMusicValidationService).read())
                val developer =
                    withTimeout(20_000) { AppleMusicDeveloperTokenProvider(account.endpoint.toHttpUrl()).token() }
                if (generation != currentGeneration) return@launch
                session?.let {
                    removeSession(it)
                    it.release()
                }
                player?.release()
                var observedPlaying = false
                val newPlayer =
                    AppleMusicMedia3Player(this@AppleMusicValidationService, developer, account.musicUserToken) {
                        observation.value = it
                        observedPlaying = observedPlaying || it.playing
                        if (it.playing) manager.cancel(BOOTSTRAP_NOTIFICATION)
                    }
                player = newPlayer
                session = MediaSession.Builder(this@AppleMusicValidationService, newPlayer)
                    .setId("apple-music-validation").setSessionActivity(openApp).build()
                addSession(session!!)
                newPlayer.playCatalogSong(catalogId)
                delay(30_000)
                if (generation == currentGeneration && !observedPlaying) {
                    observation.value = AppleMusicPlaybackObservation(
                        message = "MusicKit could not start playback. Reconnect and retry.", error = true
                    )
                    stopSelf()
                }
            } catch (_: TimeoutCancellationException) {
                observation.value = AppleMusicPlaybackObservation(
                    message = "The token service timed out. Retry when it is available.", error = true
                )
                stopSelf()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: LinkageError) {
                observation.value = AppleMusicPlaybackObservation(
                    message = "This device cannot load the MusicKit playback library.", error = true
                )
                stopSelf()
            } catch (_: Exception) {
                observation.value = AppleMusicPlaybackObservation(
                    message = "Playback could not start. Check the token service and reconnect.", error = true
                )
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        generation++
        scope.cancel()
        session?.let {
            removeSession(it)
            it.release()
        }
        player?.release()
        if (!observation.value.error) {
            observation.value = AppleMusicPlaybackObservation()
        }
        getSystemService(NotificationManager::class.java).cancel(BOOTSTRAP_NOTIFICATION)
        super.onDestroy()
    }

    companion object {
        private const val PLAY = "com.gpo.yoin.applemusic.PLAY_VALIDATION"
        private const val CHANNEL = "apple_music_validation"
        private const val BOOTSTRAP_NOTIFICATION = 4102
        private val observation = MutableStateFlow(AppleMusicPlaybackObservation())
        val playback = observation.asStateFlow()
        fun play(context: Context, catalogId: String) = context.startForegroundService(
            Intent(context, AppleMusicValidationService::class.java).setAction(PLAY).putExtra("catalogId", catalogId)
        )
        fun stop(context: Context) {
            context.stopService(Intent(context, AppleMusicValidationService::class.java))
        }
    }
}
