package com.gpo.yoin.player

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.remote.SubsonicCacheKey
import okhttp3.OkHttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Reads a song from [cache] before [upstream], and stores what [upstream]
 * plays. Every song is stored under [mediaCacheKey].
 */
@OptIn(UnstableApi::class)
internal fun mediaCacheDataSourceFactory(cache: Cache, upstream: DataSource.Factory): CacheDataSource.Factory =
    CacheDataSource.Factory()
        .setCache(cache)
        .setCacheKeyFactory(::mediaCacheKey)
        .setUpstreamDataSourceFactory(upstream)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

/**
 * A song's key in the media cache: [SubsonicCacheKey] for a signed Subsonic
 * stream url, else Media3's own key (the spec's key, or its url). The stream url
 * carries a salt drawn per process, so keyed on the url a song one process
 * stored is never found by the next: it downloads again online, and offline it
 * does not play although its bytes are on disk.
 */
@OptIn(UnstableApi::class)
internal fun mediaCacheKey(spec: DataSpec): String {
    spec.key?.let { return it }
    val url = spec.uri.toString()
    return SubsonicCacheKey.of(url) ?: url
}

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var cache: SimpleCache? = null
    private var quickActions: SessionQuickActions? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val cacheDir = File(cacheDir, CACHE_DIR_NAME)
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE_BYTES)
        val simpleCache = SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(this))
        cache = simpleCache

        val upstreamFactory = OkHttpDataSource.Factory(OkHttpClient())
        val cacheDataSourceFactory = mediaCacheDataSourceFactory(simpleCache, upstreamFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        // Default play mode is repeat all (see PlayMode). PlaybackManager also
        // applies the user's current mode whenever Yoin starts a queue.
        player.repeatMode = Player.REPEAT_MODE_ALL

        setMediaNotificationProvider(yoinNotificationProvider())
        val container = (application as YoinApplication).container
        val actions = SessionQuickActions(this, { container.playbackManager }, { container.repository })
        quickActions = actions
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(OpenNowPlayingActivity.pendingIntent(this))
            .setCallback(actions.callback)
            .build()
            .also(actions::attach)
        _audioSessionId.value = player.audioSessionId
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        quickActions?.release()
        quickActions = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        cache?.release()
        cache = null
        super.onDestroy()
    }

    companion object {
        /** Media3's notification with Yoin's status-bar mark instead of the default note. */
        @OptIn(UnstableApi::class)
        internal fun MediaSessionService.yoinNotificationProvider() =
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_stat_yoin)
            }

        private const val CACHE_DIR_NAME = "media_cache"
        private const val MAX_CACHE_SIZE_BYTES = 500L * 1024 * 1024 // 500 MB

        private val _audioSessionId = MutableStateFlow(0)

        /** Audio session ID from ExoPlayer, observed by [AudioVisualizerManager]. */
        val audioSessionId: StateFlow<Int> = _audioSessionId.asStateFlow()
    }
}
