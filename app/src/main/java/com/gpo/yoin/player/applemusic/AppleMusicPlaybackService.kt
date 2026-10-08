package com.gpo.yoin.player.applemusic

import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.profile.ProfileManager.SwitchState
import com.gpo.yoin.data.source.applemusic.AppleMusicSource
import com.gpo.yoin.player.PlaybackService.Companion.yoinNotificationProvider
import com.gpo.yoin.player.SessionQuickActions
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The same session drives Yoin, Bluetooth and lock-screen controls; MusicKit owns audio/DRM. */
@UnstableApi
class AppleMusicPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var source: AppleMusicSource? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private val quickActions by lazy {
        val container = (application as YoinApplication).container
        SessionQuickActions(this, { container.playbackManager }, { container.repository })
    }

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(yoinNotificationProvider())
        // System UI keeps this service bound, so PlaybackManager's stopService alone leaves the
        // session alive as the media-button target: a headset "play" after an account switch
        // would resume Apple Music under another profile. The session dies with its source —
        // already when a switch starts, since onSwitchPrepare tears playback down either way.
        val profiles = (application as YoinApplication).container.profileManager
        scope.launch {
            combine(profiles.activeSource, profiles.switchingState, ::Pair).collect { (active, switching) ->
                if (source != null && (active !== source || switching is SwitchState.Switching)) {
                    releaseSession()
                    stopSelf()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val profiles = (application as YoinApplication).container.profileManager
        if (profiles.switchingState.value is SwitchState.Switching) return null
        val active = profiles.activeSource.value as? AppleMusicSource ?: return null
        // PlaybackManager fetches the token before binding. No network or secrets in an Intent.
        if (active.playbackDeveloperToken.isBlank()) return null
        if (source !== active) {
            releaseSession()
            val player = AppleMusicMedia3Player(
                this,
                active.playbackDeveloperToken,
                active.credentials.musicUserToken,
                onObservation = {},
                currentDeveloperToken = { active.playbackDeveloperToken }
            )
            session = MediaSession.Builder(this, player)
                .setId("apple-music")
                .setCallback(CatalogItemsCallback(quickActions.callback))
                .build()
                .also(quickActions::attach)
            source = active
            refreshJob = scope.launch {
                while (isActive) {
                    delay(30_000)
                    try {
                        active.refreshPlaybackToken()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { /* The next API/play request reports a failed refresh. */ }
                }
            }
        }
        return session
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (session?.player?.playWhenReady != true) stopSelf()
    }
    private fun releaseSession() {
        refreshJob?.cancel()
        quickActions.detach()
        session?.run {
            player.stop()
            player.release()
            release()
        }
        session = null
        source = null
    }
    override fun onDestroy() {
        releaseSession()
        quickActions.release()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * MusicKit items carry a catalog id instead of a URI. Media3's default
     * onAddMediaItems rejects every item without a localConfiguration, which
     * silently dropped each setMediaItems from PlaybackManager. Everything else is the
     * shared notification quick actions.
     */
    private class CatalogItemsCallback(quickActions: MediaSession.Callback) : MediaSession.Callback by quickActions {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> =
            if (mediaItems.all { it.mediaMetadata.extras?.getString("appleMusicCatalogId") != null }) {
                Futures.immediateFuture(mediaItems)
            } else {
                Futures.immediateFailedFuture(UnsupportedOperationException("Missing Apple Music catalog id"))
            }
    }
}
