package com.gpo.yoin.player

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import android.media.AudioManager
import android.net.ConnectivityManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.remote.SubsonicCacheKey
import com.gpo.yoin.data.remote.SubsonicStreamUrls
import okhttp3.OkHttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    private var localPlayer: ExoPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val cacheDir = File(cacheDir, CACHE_DIR_NAME)
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE_BYTES)
        val simpleCache = SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(this))
        cache = simpleCache

        val httpClient = OkHttpClient()
        val upstreamFactory = OkHttpDataSource.Factory(httpClient)
        val cacheDataSourceFactory = mediaCacheDataSourceFactory(simpleCache, upstreamFactory)
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val audioManager = getSystemService(AudioManager::class.java)
        val traits = SubsonicServerTraitsCache(httpClient)
        val resolver = SubsonicStreamResolver(
            settings = { StreamQualityPreference.current(this) },
            isUnmetered = { connectivity?.activeNetworkUnmetered() ?: false },
            outputKind = { audioManager?.currentOutputKind() ?: AudioOutputKind.Speaker },
            traitsFor = traits::traitsFor,
            sourceKbpsFor = SubsonicSourceBitrates::of,
        )
        streamResolver = resolver
        // Resolved above the cache, so a transcode is stored under its own key
        // (SubsonicCacheKey keeps maxBitRate and format).
        val dataSourceFactory = ResolvingDataSource.Factory(cacheDataSourceFactory, resolver)
        // A transcoded mp3 is constant bit rate with no seek table of its own.
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractors))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        localPlayer = player
        // Default play mode is repeat all (see PlayMode). PlaybackManager also
        // applies the user's current mode whenever Yoin starts a queue.
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.addListener(StreamFormatListener(player, resolver))

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
        serviceScope.launch {
            container.dlnaManager.connectedDevice.collect { device ->
                // The local player stays paused throughout a remote session. Losing
                // a speaker never makes the phone start unexpectedly.
                player.pause()
                mediaSession?.setPlayer(if (device != null) container.dlnaManager.player else player)
                _usingDlna.value = device != null
            }
        }
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
        val container = (application as YoinApplication).container
        if (mediaSession?.player === container.dlnaManager.player && container.dlnaManager.connectedDevice.value != null) {
            container.playbackManager.onRemotePlaybackServiceDestroyed()
        }
        serviceScope.cancel()
        quickActions?.release()
        quickActions = null
        mediaSession?.run {
            release()
        }
        localPlayer?.release()
        localPlayer = null
        streamResolver = null
        _playingFormat.value = null
        mediaSession = null
        _usingDlna.value = false
        cache?.release()
        cache = null
        super.onDestroy()
    }

    /**
     * Keeps the stream choices of the playing song and the next one (the
     * player preloads it), and reports what the player decodes.
     */
    @OptIn(UnstableApi::class)
    private class StreamFormatListener(
        private val player: ExoPlayer,
        private val resolver: SubsonicStreamResolver,
    ) : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val keep = listOfNotNull(
                mediaItem,
                player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let(player::getMediaItemAt),
            ).mapNotNull { it.localConfiguration?.uri?.toString()?.let(SubsonicStreamUrls::songIdOf) }
            resolver.retainOnly(keep.toSet())
            _playingFormat.value = null
        }

        override fun onTracksChanged(tracks: Tracks) {
            val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            val format = group?.let { (0 until it.length).firstOrNull(it::isTrackSelected)?.let(it::getTrackFormat) }
            val mediaId = player.currentMediaItem?.mediaId
            _playingFormat.value = if (format != null && mediaId != null) {
                PlayingFormat(mediaId = mediaId, decoded = format.toAudioFormat())
            } else {
                null
            }
        }
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

        @Volatile
        private var streamResolver: SubsonicStreamResolver? = null

        /** The stream the local player opened for a Subsonic song, by its raw id; null before it opens. */
        internal fun streamDeliveryFor(songRawId: String): StreamDelivery? = streamResolver?.deliveryFor(songRawId)

        private val _playingFormat = MutableStateFlow<PlayingFormat?>(null)

        /** What the local player is decoding now, as its tracks say; null when it has no audio track yet. */
        val playingFormat: StateFlow<PlayingFormat?> = _playingFormat.asStateFlow()

        private val _usingDlna = MutableStateFlow(false)
        /** The service has applied the route change before a handoff sends new commands. */
        internal val usingDlna: StateFlow<Boolean> = _usingDlna.asStateFlow()
    }
}
