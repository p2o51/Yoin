package com.gpo.yoin.data.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.remote.SubsonicStreamUrls
import com.gpo.yoin.data.source.subsonic.SubsonicMusicSource
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * The device's song storage: the stream cache songs fill as they play
 * ([streamCache], 500 MB, the least recently played go first) and the
 * downloads the listener keeps ([downloadCache], in files/, nothing evicts it;
 * Media3's [DownloadManager] fills it, [YoinDownloadService] keeps it running).
 * One instance for the process: a cache directory takes one SimpleCache.
 *
 * A download is queued under [OfflineRequestUri], not the signed stream url,
 * so the download index holds no credential; [resolveDownload] signs it with
 * the active Subsonic account as the download opens. Each download carries an
 * [OfflinePayload], which is how [library] lists it with no network. Another
 * profile's downloads are stopped ([STOP_OTHER_PROFILE]) until it is active.
 */
@OptIn(UnstableApi::class)
class OfflineDownloads(
    context: Context,
    val settingsStore: DownloadSettingsStore,
    private val activeProfileId: StateFlow<String?>,
    private val activeSubsonic: () -> SubsonicMusicSource?,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val databaseProvider = StandaloneDatabaseProvider(appContext)

    val streamCache: SimpleCache by lazy {
        SimpleCache(
            File(appContext.cacheDir, STREAM_CACHE_DIR).apply { mkdirs() },
            LeastRecentlyUsedCacheEvictor(STREAM_CACHE_BYTES),
            databaseProvider,
        )
    }

    val downloadCache: SimpleCache by lazy {
        SimpleCache(File(appContext.filesDir, DOWNLOAD_CACHE_DIR).apply { mkdirs() }, NoOpCacheEvictor(), databaseProvider)
    }

    /** Created on the main thread (the service's, or [ensureStarted]'s): DownloadManager is driven from its creating thread. */
    val manager: DownloadManager by lazy {
        val upstream = ResolvingDataSource.Factory(OkHttpDataSource.Factory(OkHttpClient()), ::resolveDownload)
        DownloadManager(appContext, databaseProvider, downloadCache, upstream, Executors.newFixedThreadPool(2)).apply {
            maxParallelDownloads = 2
            requirements = requirementsFor(settingsStore.current)
            // A new manager starts paused; YoinDownloadService resumes it too, but
            // downloads left queued by the last process go on without waiting for it.
            resumeDownloads()
            addListener(object : DownloadManager.Listener {
                override fun onInitialized(downloadManager: DownloadManager) = refresh()
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) = refresh()
                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh()
            })
        }
    }

    private val allSongs = MutableStateFlow<List<Pair<Download, OfflinePayload>>>(emptyList())

    /** The active profile's downloads, as albums, playlists and songs. */
    val library: StateFlow<OfflineLibrary> = combine(allSongs, activeProfileId) { songs, profileId ->
        OfflineLibrary(
            songs
                .filter { (_, payload) -> payload.profileId == profileId }
                .map { (download, payload) -> download.toOfflineSong(payload) },
        )
    }.stateIn(scope, SharingStarted.Eagerly, OfflineLibrary())

    private val _streamCacheBytes = MutableStateFlow(0L)
    val streamCacheBytes: StateFlow<Long> = _streamCacheBytes.asStateFlow()

    /** Every profile's downloads on disk, bytes. */
    val downloadBytes: StateFlow<Long> = allSongs
        .map { songs -> songs.sumOf { it.first.bytesDownloaded } }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    /** Each completed download's quality, by key, for what the player says it plays. */
    private val qualities = ConcurrentHashMap<String, DownloadQuality>()

    private var started = false

    /** Brings the manager up so [library] fills; called as the app starts. */
    fun ensureStarted() {
        if (started) return
        started = true
        scope.launch(Dispatchers.Main) {
            manager
            refresh()
            pollWhileDownloading()
        }
        scope.launch {
            settingsStore.settings.map { requirementsFor(it) }.distinctUntilChanged().collect { requirements ->
                // The manager directly, not through the service: this runs as the
                // app starts, when a background start of the service isn't allowed.
                withContext(Dispatchers.Main) {
                    if (manager.requirements != requirements) manager.requirements = requirements
                }
            }
        }
        scope.launch { activeProfileId.collect { applyProfileStops() } }
        refreshStreamCacheBytes()
    }

    /** Whether the song under [key] is wholly downloaded: what the player reads from instead of the network. */
    fun isComplete(key: String): Boolean {
        val cache: Cache = downloadCache
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0 && cache.isCached(key, 0, length)
    }

    fun qualityOf(key: String): DownloadQuality? = qualities[key]

    /**
     * Downloads [tracks] as [collection] (an album, a playlist, or one song) at
     * the quality Settings › Downloads names. Songs already downloaded keep
     * their file and quality, and are now held by [collection] too.
     * Returns how many songs were queued; 0 when the active account isn't Subsonic.
     */
    suspend fun download(tracks: List<Track>, collection: OfflineCollection): Int = withContext(Dispatchers.IO) {
        val source = activeSubsonic() ?: return@withContext 0
        val profileId = activeProfileId.value ?: return@withContext 0
        val quality = settingsStore.current.quality
        val existing = allSongs.value.associate { (download, payload) -> download.request.id to payload }
        val requests = tracks.mapNotNull { track ->
            if (track.id.provider != MediaId.PROVIDER_SUBSONIC) return@mapNotNull null
            val key = OfflineKeys.of(source.buildStreamUrl(track.id.rawId)) ?: return@mapNotNull null
            val payload = existing[key]?.withCollection(collection)
                ?: payloadOf(track, profileId, quality).withCollection(collection)
            requestOf(key, track.id.rawId, payload)
        }
        withContext(Dispatchers.Main) {
            requests.forEach(::add)
        }
        requests.size
    }

    /**
     * [download] in this object's scope, so a page closed right after the tap
     * still queues; [onQueued] gets the count on the main thread.
     */
    fun downloadLater(load: suspend () -> List<Track>, collection: OfflineCollection, onQueued: (Int) -> Unit) {
        scope.launch {
            val count = runCatching { download(load(), collection) }.getOrDefault(0)
            withContext(Dispatchers.Main) { onQueued(count) }
        }
    }

    /** [remove] in this object's scope; [onRemoved] runs on the main thread. */
    fun removeLater(collectionKey: String, onRemoved: () -> Unit) {
        scope.launch {
            remove(collectionKey)
            withContext(Dispatchers.Main) { onRemoved() }
        }
    }

    /** Whether a download queued now waits: mobile data isn't allowed and the network is metered (or none). */
    fun waitsForWifi(): Boolean {
        if (settingsStore.current.overMobileData) return false
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Removes [collectionKey]: its songs go unless another download still holds them. */
    suspend fun remove(collectionKey: String) = withContext(Dispatchers.IO) {
        val held = allSongs.value.filter { (_, payload) -> payload.collections.any { it.key == collectionKey } }
        withContext(Dispatchers.Main) {
            held.forEach { (download, payload) ->
                val rest = payload.withoutCollection(collectionKey)
                if (rest == null) {
                    viaService(
                        send = {
                            DownloadService.sendRemoveDownload(
                                appContext,
                                YoinDownloadService::class.java,
                                download.request.id,
                                false,
                            )
                        },
                        direct = { manager.removeDownload(download.request.id) },
                    )
                } else {
                    val parts = OfflineRequestUri.parse(download.request.uri.toString())
                    if (parts != null) {
                        add(requestOf(download.request.id, parts.songRawId, rest))
                    }
                }
            }
        }
    }

    /** Every profile's downloads. */
    fun removeAll() {
        scope.launch(Dispatchers.Main) {
            viaService(
                send = { DownloadService.sendRemoveAllDownloads(appContext, YoinDownloadService::class.java, false) },
                direct = { manager.removeAllDownloads() },
            )
        }
    }

    fun clearStreamCache() {
        scope.launch {
            val cache = streamCache
            cache.keys.toList().forEach { key -> runCatching { cache.removeResource(key) } }
            refreshStreamCacheBytes()
        }
    }

    fun refreshStreamCacheBytes() {
        scope.launch { _streamCacheBytes.value = streamCache.cacheSpace }
    }

    // ── Internals ────────────────────────────────────────────────────

    /** The signed stream url for a queued [OfflineRequestUri], as the download opens it. */
    private fun resolveDownload(dataSpec: DataSpec): DataSpec {
        val parts = OfflineRequestUri.parse(dataSpec.uri.toString()) ?: return dataSpec
        val source = activeSubsonic() ?: throw IOException("No Subsonic account is active")
        val signed = source.buildStreamUrl(parts.songRawId)
        // A profile switch between queueing and opening: never store another
        // account's song under this key.
        if (OfflineKeys.of(signed) != parts.key) throw IOException("The download belongs to another account")
        val url = SubsonicStreamUrls.withTranscode(signed, parts.quality.maxBitRateKbps, parts.quality.format)
        return dataSpec.withUri(Uri.parse(url))
    }

    private fun refresh() {
        scope.launch {
            val songs = runCatching {
                buildList {
                    manager.downloadIndex.getDownloads().use { cursor ->
                        while (cursor.moveToNext()) {
                            val download = cursor.download
                            val payload = OfflinePayload.decode(download.request.data) ?: continue
                            add(download to payload)
                        }
                    }
                }.sortedBy { it.first.startTimeMs }
            }.getOrElse { return@launch }
            qualities.clear()
            songs.forEach { (download, payload) ->
                if (download.state == Download.STATE_COMPLETED) qualities[download.request.id] = payload.downloadQuality
            }
            allSongs.value = songs
            applyProfileStops()
        }
    }

    /** Download progress isn't an event: read it again each second while something downloads. */
    private suspend fun pollWhileDownloading() {
        while (true) {
            delay(POLL_MS)
            if (manager.currentDownloads.any { it.state == Download.STATE_DOWNLOADING }) refresh()
        }
    }

    private suspend fun applyProfileStops() {
        val active = activeProfileId.value
        val changes = allSongs.value.mapNotNull { (download, payload) ->
            val wanted = if (payload.profileId == active) Download.STOP_REASON_NONE else STOP_OTHER_PROFILE
            if (download.stopReason == wanted || download.state == Download.STATE_COMPLETED) null else download.request.id to wanted
        }
        if (changes.isEmpty()) return
        withContext(Dispatchers.Main) {
            changes.forEach { (id, reason) -> manager.setStopReason(id, reason) }
        }
    }

    private fun add(request: DownloadRequest) = viaService(
        send = { DownloadService.sendAddDownload(appContext, YoinDownloadService::class.java, request, false) },
        direct = { manager.addDownload(request) },
    )

    /**
     * Through [YoinDownloadService], which keeps the process up and shows the
     * progress; straight to the manager when Android won't start the service
     * (the app went to the background between the tap and here).
     */
    private inline fun viaService(send: () -> Unit, direct: () -> Unit) {
        try {
            send()
        } catch (refused: IllegalStateException) {
            direct()
        }
    }

    private fun requestOf(key: String, songRawId: String, payload: OfflinePayload): DownloadRequest =
        DownloadRequest.Builder(key, Uri.parse(OfflineRequestUri.build(songRawId, key, payload.downloadQuality)))
            .setCustomCacheKey(key)
            .setData(payload.encode())
            .build()

    private fun Download.toOfflineSong(payload: OfflinePayload) = OfflineSong(
        key = request.id,
        payload = payload,
        state = when (state) {
            Download.STATE_COMPLETED -> OfflineSong.State.Done
            Download.STATE_DOWNLOADING -> OfflineSong.State.Downloading
            Download.STATE_FAILED -> OfflineSong.State.Failed
            Download.STATE_STOPPED -> OfflineSong.State.Waiting
            else -> OfflineSong.State.Queued
        },
        percent = percentDownloaded,
        bytes = bytesDownloaded,
    )

    companion object {
        const val STREAM_CACHE_DIR = "media_cache"
        const val STREAM_CACHE_BYTES = 500L * 1024 * 1024
        const val DOWNLOAD_CACHE_DIR = "offline_media"

        /** A download of a profile other than the active one: it waits for that profile. */
        const val STOP_OTHER_PROFILE = 1

        private const val POLL_MS = 1_000L

        /** Wi‑Fi only unless the listener allows mobile data (Spotify's Download using cellular). */
        fun requirementsFor(settings: DownloadSettings): Requirements =
            Requirements(if (settings.overMobileData) Requirements.NETWORK else Requirements.NETWORK_UNMETERED)

        fun payloadOf(track: Track, profileId: String, quality: DownloadQuality) = OfflinePayload(
            profileId = profileId,
            songId = track.id.toString(),
            title = track.title,
            artist = track.artist,
            album = track.album,
            albumId = track.albumId?.toString(),
            cover = CoverRef.toStorageKey(track.coverArt),
            durationSec = track.durationSec,
            trackNumber = track.trackNumber,
            discNumber = track.discNumber,
            quality = quality.key,
        )
    }
}
