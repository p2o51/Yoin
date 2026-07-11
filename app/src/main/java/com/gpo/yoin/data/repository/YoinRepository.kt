package com.gpo.yoin.data.repository

import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import androidx.room.withTransaction
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.local.AlbumNote
import com.gpo.yoin.data.local.AlbumNoteDao
import com.gpo.yoin.data.local.AlbumNoteKey
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.AlbumRatingDao
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.GeminiConfigDao
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.LyricsCache
import com.gpo.yoin.data.cache.Cached
import com.gpo.yoin.data.cache.DetailCacheStore
import com.gpo.yoin.data.local.LyricsCacheDao
import com.gpo.yoin.data.local.LyricsTranslationCache
import com.gpo.yoin.data.local.LyricsTranslationCacheDao
import com.gpo.yoin.data.local.MemoryCopyCache
import com.gpo.yoin.data.local.MemoryCopyCacheDao
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongAboutEntryDao
import com.gpo.yoin.data.local.HomeGridPoolCache
import com.gpo.yoin.data.local.toAlbum
import com.gpo.yoin.data.local.toGridPoolRow
import com.gpo.yoin.data.local.toPlaylist
import com.gpo.yoin.data.local.toTrack
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.local.SongNoteDao
import com.gpo.yoin.data.local.SongNoteKey
import com.gpo.yoin.data.local.SpotifyHomeAlbumCache
import com.gpo.yoin.data.local.SpotifyHomeArtistCache
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.integration.neodb.NeoDBSyncService
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.memory.AlbumMemoryCandidateBuilder
import com.gpo.yoin.data.lyrics.LrcParser
import com.gpo.yoin.data.lyrics.LyricsProviderRegistry
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.LyricLine
import com.gpo.yoin.data.model.Lyrics
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.PlaylistItemRef
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.model.YoinDevice
import com.gpo.yoin.data.remote.GeminiService
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.spotify.SpotifyLibrarySyncCoordinator
import com.gpo.yoin.data.source.spotify.SpotifyMusicSource
import com.gpo.yoin.data.source.spotify.SpotifyPlayHistoryObject
import com.gpo.yoin.data.source.spotify.SpotifyRateLimitGate
import com.gpo.yoin.data.source.spotify.toSpotifyLibraryTrackCache
import com.gpo.yoin.data.source.spotify.toTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Provider-agnostic orchestrator over local Room + the currently active
 * [MusicSource]. Remote calls are dispatched through `activeSource`; local
 * persistence (ratings, history, activity, song info) stays in Room.
 *
 * Adding a new backend means implementing [MusicSource] — nothing in this
 * class should need to change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YoinRepository(
    private val activeSource: StateFlow<MusicSource?>,
    private val activeProfileId: StateFlow<String?>,
    private val database: YoinDatabase,
    private val geminiService: GeminiService,
    private val songAboutEntryDao: SongAboutEntryDao,
    private val geminiConfigDao: GeminiConfigDao,
    private val lyricsCacheDao: LyricsCacheDao,
    private val lyricsTranslationCacheDao: LyricsTranslationCacheDao,
    private val songNoteDao: SongNoteDao,
    private val albumNoteDao: AlbumNoteDao,
    private val albumRatingDao: AlbumRatingDao,
    private val memoryCopyCacheDao: MemoryCopyCacheDao,
    private val neoDbSyncService: NeoDBSyncService,
    private val lyricsProviderRegistry: LyricsProviderRegistry = LyricsProviderRegistry(),
    private val spotifyLibrarySyncCoordinator: SpotifyLibrarySyncCoordinator? = null,
    private val spotifyRateLimitGate: SpotifyRateLimitGate? = null,
    private val repositoryScope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val clock: () -> Long = System::currentTimeMillis,
    private val detailCacheStore: DetailCacheStore? = null,
) {

    // ── In-memory detail cache ─────────────────────────────────────────
    // Album / artist / playlist detail responses are network round-trips on
    // Spotify; cache them in-process so repeat opens (and preloaded items) are
    // instant. Size-bounded LRU (entry counts below) + a 20-min freshness TTL;
    // stale entries still serve as an offline-ish fallback when a refetch
    // fails. All cleared on profile switch (a different account = different data).
    private val albumDetailCache =
        DetailMemoryCache<Album>(maxSize = 96, ttlMs = 20L * 60 * 1000, clock = clock)
    private val artistDetailCache =
        DetailMemoryCache<ArtistDetail>(maxSize = 64, ttlMs = 20L * 60 * 1000, clock = clock)
    private val playlistDetailCache =
        DetailMemoryCache<Playlist>(maxSize = 48, ttlMs = 20L * 60 * 1000, clock = clock)

    // Album/artist provider metadata is ~stable, so the persistent disk copy is
    // served without a network hit for a week (instant cold-start / offline);
    // playlists are mutable and always revalidate (see getPlaylist).
    private val detailDiskFreshMs = 7L * 24 * 60 * 60 * 1000

    // Stale-while-revalidate: a disk-served album/artist older than this triggers
    // a gated background refresh, so a like/follow toggled on another device or
    // the official app converges on the next open instead of lingering for the
    // full disk-fresh week. Short-enough to catch real changes, long-enough not
    // to refetch something just opened (respects the Spotify rate-limit gate).
    private val detailRevalidateAfterMs = 2L * 60 * 60 * 1000

    /** Size-bounded, TTL'd in-memory cache for one detail type. Thread-safe. */
    private class DetailMemoryCache<V : Any>(
        maxSize: Int,
        private val ttlMs: Long,
        private val clock: () -> Long,
    ) {
        private class Entry<V>(val value: V, val cachedAt: Long)

        private val lru = Collections.synchronizedMap(
            object : LinkedHashMap<String, Entry<V>>(maxSize, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<V>>?): Boolean =
                    size > maxSize
            },
        )

        /**
         * In-flight loads keyed by [memKey], so concurrent readers of one
         * entity coalesce onto a single fetch — see [loadCachedDetail]. Held
         * per cache instance so the three detail types stay fully typed and a
         * shared raw `provider:rawId` can never collide across them.
         */
        val inFlight = ConcurrentHashMap<String, Deferred<V?>>()

        /**
         * Bumped by [invalidate]. A shared load snapshots the generation before
         * it starts and skips its cache write-back when the counter has moved,
         * so a fetch that took off before an edit can't re-persist stale data.
         */
        private val generations = ConcurrentHashMap<String, Long>()

        fun generationOf(key: String): Long = generations[key] ?: 0L

        /**
         * Drop the entry AND detach any in-flight load: a post-edit re-read
         * must start a fresh fetch instead of coalescing onto a pre-edit one,
         * and the detached flight must not write its result back.
         */
        fun invalidate(key: String) {
            generations.merge(key, 1L) { old, inc -> old + inc }
            inFlight.remove(key)
            lru.remove(key)
        }

        fun getFresh(key: String): V? {
            val entry = lru[key] ?: return null
            return entry.value.takeIf { clock() - entry.cachedAt <= ttlMs }
        }

        fun getStale(key: String): V? = lru[key]?.value

        fun put(key: String, value: V) {
            lru[key] = Entry(value, clock())
        }

        fun remove(key: String) {
            lru.remove(key)
        }

        fun clear() {
            lru.clear()
        }
    }

    init {
        repositoryScope.launch {
            var lastProfileId: String? = activeProfileId.value
            activeProfileId.collect { profileId ->
                if (profileId != lastProfileId) {
                    _favoriteOverrides.value = emptyMap()
                    albumDetailCache.clear()
                    artistDetailCache.clear()
                    playlistDetailCache.clear()
                    lastProfileId = profileId
                }
            }
        }
        // One-time hygiene: age out long-untouched persistent detail entries.
        detailCacheStore?.let { store -> repositoryScope.launch { store.purgeExpired() } }
    }

    data class SpotifyHomeJumpBackInCacheSnapshot(
        val albums: List<Album>,
        val artists: List<com.gpo.yoin.data.model.Artist>,
    )

    sealed interface AboutLoadResult {
        /** Canonical rows were already present (or successfully fetched). */
        data object Success : AboutLoadResult
        data object ApiKeyMissing : AboutLoadResult
        data class Error(val message: String) : AboutLoadResult
    }

    sealed interface AskAboutResult {
        data class Success(val answer: String) : AskAboutResult
        data object ApiKeyMissing : AskAboutResult
        data class Error(val message: String) : AskAboutResult
    }

    sealed interface AskTitleResult {
        data class Success(val title: String) : AskTitleResult
        data object ApiKeyMissing : AskTitleResult
        data class Error(val message: String) : AskTitleResult
    }

    sealed interface LyricsTranslationResult {
        data class Success(
            val translations: Map<Int, String>,
            val lyrics: Lyrics? = null,
            val providerName: String? = null,
            val providerSongId: String? = null,
        ) : LyricsTranslationResult

        data class ProviderSwitchAvailable(
            val offer: LyricsTranslationProviderSwitchOffer,
        ) : LyricsTranslationResult

        data class AlreadyTargetLanguage(val targetLanguage: String) : LyricsTranslationResult
        data object ApiKeyMissing : LyricsTranslationResult
        data class Error(val message: String) : LyricsTranslationResult
    }

    data class LyricsTranslationProviderSwitchOffer(
        val providerName: String,
        val providerSongId: String,
        val lyrics: Lyrics,
        val rawLrc: String,
        val translations: Map<Int, String>,
    )

    data class LyricsApplyResult(
        val lyrics: Lyrics,
        val providerName: String,
        val providerSongId: String?,
    )

    data class LoadedLyrics(
        val lyrics: Lyrics,
        val providerName: String?,
        val providerSongId: String?,
    )

    data class LyricsSearchCandidate(
        val providerName: String,
        val songId: String,
        val title: String,
        val artist: String,
    )

    data class LyricsSearchProviderSection(
        val providerName: String,
        val candidates: List<LyricsSearchCandidate>,
        val errorMessage: String?,
    )

    /** True when a configured profile is currently active. */
    val isConfigured: Boolean
        get() = activeSource.value != null

    private val _favoriteOverrides = MutableStateFlow<Map<MediaId, Boolean>>(emptyMap())
    val favoriteOverrides: StateFlow<Map<MediaId, Boolean>> = _favoriteOverrides.asStateFlow()

    /** Striped per-track lock so rapid favorite taps on the same track serialize. */
    private val favoriteMutexes = Array(64) { Mutex() }

    /**
     * Capability set of the currently active [MusicSource], or empty when no
     * profile is active. Single source of truth for UI gating — feature
     * composables should collect this (e.g. via `stateIn` in a ViewModel)
     * rather than reaching for `AppContainer.profileManager.activeSource`.
     */
    val capabilities: Flow<Set<Capability>> =
        activeSource.map { source -> source?.capabilities ?: emptySet() }

    /** Snapshot of [capabilities] for synchronous reads (e.g. click handlers). */
    fun currentCapabilities(): Set<Capability> =
        activeSource.value?.capabilities ?: emptySet()

    /**
     * Provider id of the currently active source (e.g. `"subsonic"` /
     * `"spotify"`), or `null` when no profile is active. Exposed as a flow
     * so backend-specific code (e.g. PlaybackManager) can react to
     * profile switches without peeking inside ProfileManager.
     */
    val activeProviderId: Flow<String?> =
        activeSource.map { source -> source?.id }

    /** Synchronous snapshot of [activeProviderId]. */
    fun currentProviderId(): String? = activeSource.value?.id

    /** Synchronous snapshot of the active profile id. */
    fun currentProfileId(): String? = activeProfileId.value

    private fun requireSource(): MusicSource = activeSource.value
        ?: throw SubsonicException(
            code = -1,
            message = "No profile configured. Open Settings to add one.",
        )

    private fun spotifyCoordinator(): SpotifyLibrarySyncCoordinator =
        spotifyLibrarySyncCoordinator
            ?: error("Spotify library sync is not configured")

    private fun isSpotifyActive(): Boolean =
        currentProviderId() == MediaId.PROVIDER_SPOTIFY && spotifyLibrarySyncCoordinator != null

    private fun requireProfileId(): String =
        currentProfileId()
            ?: throw SubsonicException(code = -1, message = "No active profile")

    private fun requireSpotifySource(): SpotifyMusicSource =
        requireSource() as? SpotifyMusicSource
            ?: error("Active source is not Spotify")

    private fun spotifyProfileId(source: SpotifyMusicSource): String =
        source.profileId ?: requireProfileId()

    private suspend fun ensureSpotifyLibraryFresh(
        source: SpotifyMusicSource,
        force: Boolean = false,
    ): Result<Unit> =
        spotifyCoordinator().refreshLibrary(
            profileId = spotifyProfileId(source),
            source = source,
            force = force,
        )

    private suspend fun markSpotifyLibraryStaleIfNeeded() {
        val coordinator = spotifyLibrarySyncCoordinator ?: return
        val source = activeSource.value as? SpotifyMusicSource ?: return
        coordinator.markStale(spotifyProfileId(source))
    }

    private suspend fun setSpotifyFavorite(
        track: Track?,
        id: MediaId,
        favorite: Boolean,
    ): Result<Unit> {
        // Bind the source ONCE up front (before any suspension). A mid-flight
        // profile switch must not re-route this write to the wrong account or
        // blow up with a ClassCastException downstream.
        val source = requireSpotifySource()
        val profileId = spotifyProfileId(source)
        val rawId = id.rawId
        val dao = database.spotifyLibraryCacheDao()

        // Serialize rapid true/false/true taps per track so the last tap wins
        // instead of two writes racing the optimistic row + the network call.
        val mutex = favoriteMutexes[(id.hashCode() and Int.MAX_VALUE) % favoriteMutexes.size]
        return mutex.withLock {
            val now = clock()
            val existing = dao.getTrack(profileId, rawId)
            val optimistic = (existing?.toTrack() ?: track ?: Track(
                id = id,
                title = null,
                artist = null,
                artistId = null,
                album = null,
                albumId = null,
                coverArt = null,
                durationSec = null,
                trackNumber = null,
                year = null,
                genre = null,
                userRating = null,
                isStarred = favorite,
            )).copy(isStarred = favorite, addedAt = existing?.addedAt)

            dao.upsertTrack(
                optimistic.toSpotifyLibraryTrackCache(
                    profileId = profileId,
                    cachedAt = now,
                    pendingFavoriteAction = true,
                    lastSyncError = null,
                ),
            )
            _favoriteOverrides.value = _favoriteOverrides.value + (id to favorite)

            runCatching {
                // Spotify saves are eventually consistent — an immediate
                // contains-check returns false and would wrongly downgrade a
                // successful favorite, so we trust the mutation result alone.
                source.writeActions().setFavorite(id, favorite).getOrThrow()
            }.onSuccess {
                if (favorite) {
                    dao.updateTrackFavoriteState(
                        profileId = profileId,
                        trackId = rawId,
                        isSaved = true,
                        pending = false,
                        lastSyncError = null,
                        cachedAt = clock(),
                    )
                } else {
                    // Un-favorite removes the row so no isSaved=false orphan
                    // lingers in the saved-tracks cache.
                    dao.deleteTrack(profileId, rawId)
                }
                _favoriteOverrides.value = _favoriteOverrides.value - id
            }.onFailure { error ->
                if (existing == null) {
                    // The optimistic row was created by this call — drop it.
                    dao.deleteTrack(profileId, rawId)
                } else {
                    dao.updateTrackFavoriteState(
                        profileId = profileId,
                        trackId = rawId,
                        isSaved = existing.isSaved,
                        pending = false,
                        lastSyncError = error.message,
                        cachedAt = clock(),
                    )
                }
                _favoriteOverrides.value = _favoriteOverrides.value - id
            }
        }
    }

    // ── Albums ─────────────────────────────────────────────────────────

    suspend fun getAlbumList(type: String, size: Int = 20, offset: Int = 0): List<Album> {
        if (isSpotifyActive()) {
            val source = requireSpotifySource()
            val profileId = spotifyProfileId(source)
            ensureSpotifyLibraryFresh(source).getOrThrow()
            val albums = spotifyCoordinator().readAlbums(profileId)
            val sorted = when (type) {
                "alphabeticalByName" -> albums.sortedBy { it.name.lowercase() }
                "recent" -> albums.sortedByDescending { it.addedAt.orEmpty() }
                "random" -> albums.shuffled()
                else -> albums
            }
            return sorted.drop(offset.coerceAtLeast(0)).take(size.coerceAtLeast(0))
        }
        return requireSource().library().getAlbumList(type, size, offset)
    }

    suspend fun getAlbum(id: MediaId): Album? = loadCachedDetail(
        mem = albumDetailCache,
        baseKey = id.toString(),
        diskFreshMs = detailDiskFreshMs,
        diskRead = { profileId -> detailCacheStore?.readAlbum(profileId, id.toString()) },
        diskWrite = { profileId, value -> detailCacheStore?.writeAlbum(profileId, id.toString(), value) },
        fetch = { requireSource().library().getAlbum(id) },
    )

    // ── Artists ────────────────────────────────────────────────────────

    suspend fun getArtists(): List<ArtistIndex> {
        if (isSpotifyActive()) {
            val source = requireSpotifySource()
            val profileId = spotifyProfileId(source)
            ensureSpotifyLibraryFresh(source).getOrThrow()
            val artists = spotifyCoordinator().readArtists(profileId)
            return artists
                .filter { artist -> artist.name.isNotBlank() }
                .sortedBy { artist -> artist.name.lowercase() }
                .groupBy { artist ->
                    artist.name.firstOrNull()?.uppercaseChar()?.takeIf(Char::isLetter)?.toString() ?: "#"
                }
                .toSortedMap()
                .map { (name, grouped) -> ArtistIndex(name = name, artists = grouped) }
        }
        return requireSource().library().getArtists()
    }

    suspend fun getArtist(id: MediaId): ArtistDetail? = loadCachedDetail(
        mem = artistDetailCache,
        baseKey = id.toString(),
        diskFreshMs = detailDiskFreshMs,
        diskRead = { profileId -> detailCacheStore?.readArtist(profileId, id.toString()) },
        diskWrite = { profileId, value -> detailCacheStore?.writeArtist(profileId, id.toString(), value) },
        fetch = { requireSource().library().getArtist(id) },
    )

    /** The artist's most-popular tracks (Spotify "Popular"; empty for providers without it). */
    suspend fun getArtistTopTracks(id: MediaId): List<Track> =
        requireSource().library().getArtistTopTracks(id)

    // ── Search ─────────────────────────────────────────────────────────

    suspend fun search(query: String): SearchResults =
        requireSource().library().search(query)

    // ── Favorites ──────────────────────────────────────────────────────

    suspend fun setFavorite(id: MediaId, favorite: Boolean): Result<Unit> {
        if (id.provider == MediaId.PROVIDER_SPOTIFY && isSpotifyActive()) {
            return setSpotifyFavorite(track = null, id = id, favorite = favorite)
        }
        return requireSource().writeActions().setFavorite(id, favorite).onSuccess {
            _favoriteOverrides.value = _favoriteOverrides.value + (id to favorite)
        }
    }

    suspend fun setFavorite(track: Track, favorite: Boolean): Result<Unit> {
        val result = if (track.id.provider == MediaId.PROVIDER_SPOTIFY && isSpotifyActive()) {
            setSpotifyFavorite(track = track, id = track.id, favorite = favorite)
        } else {
            setFavorite(track.id, favorite)
        }
        // The cached album bakes in each track's isStarred at fetch time; drop it
        // so the next open re-derives the star instead of showing the pre-toggle
        // state once the transient favoriteOverrides entry is cleared.
        return result.onSuccess { track.albumId?.let { invalidateAlbumDetail(it) } }
    }

    /**
     * Follow / unfollow an artist — a distinct action from [setFavorite] (on
     * Spotify the follow endpoint, not the saved-tracks library). Optimistic via
     * [favoriteOverrides]; invalidates the cached artist detail on success so the
     * follow heart isn't served stale on re-open.
     */
    suspend fun setArtistFollowed(id: MediaId, followed: Boolean): Result<Unit> {
        _favoriteOverrides.value = _favoriteOverrides.value + (id to followed)
        return requireSource().writeActions().setArtistFollowed(id, followed)
            .onSuccess {
                invalidateArtistDetail(id)
                _favoriteOverrides.value = _favoriteOverrides.value - id
            }
            .onFailure {
                _favoriteOverrides.value = _favoriteOverrides.value - id
            }
    }

    suspend fun getStarred(): Starred {
        if (isSpotifyActive()) {
            val source = requireSpotifySource()
            val profileId = spotifyProfileId(source)
            ensureSpotifyLibraryFresh(source).getOrThrow()
            return spotifyCoordinator().readStarred(profileId)
        }
        return requireSource().library().getStarred()
    }


    // ── Random ─────────────────────────────────────────────────────────

    suspend fun getRandomSongs(size: Int = 20): List<Track> {
        if (isSpotifyActive()) {
            val source = requireSpotifySource()
            val profileId = spotifyProfileId(source)
            ensureSpotifyLibraryFresh(source).getOrThrow()
            return spotifyCoordinator()
                .readTracks(profileId)
                .shuffled()
                .take(size.coerceAtLeast(0))
        }
        return requireSource().library().getRandomSongs(size)
    }

    // ── Spotify Home cache ────────────────────────────────────────────

    suspend fun getCachedSpotifyHomeJumpBackIn(
        profileId: String,
        maxAgeMs: Long,
    ): SpotifyHomeJumpBackInCacheSnapshot {
        val minCachedAt = System.currentTimeMillis() - maxAgeMs
        val dao = database.spotifyHomeCacheDao()
        return SpotifyHomeJumpBackInCacheSnapshot(
            albums = dao.getFreshAlbums(profileId, minCachedAt).map(SpotifyHomeAlbumCache::toAlbum),
            artists = dao.getFreshArtists(profileId, minCachedAt).map(SpotifyHomeArtistCache::toArtist),
        )
    }

    suspend fun replaceSpotifyHomeJumpBackInCache(
        profileId: String,
        albums: List<Album>,
        artists: List<com.gpo.yoin.data.model.Artist>,
        cachedAt: Long = System.currentTimeMillis(),
    ) {
        database.withTransaction {
            val dao = database.spotifyHomeCacheDao()
            dao.deleteAlbumsForProfile(profileId)
            dao.deleteArtistsForProfile(profileId)
            dao.insertAlbums(
                albums
                    .distinctBy { album -> album.id }
                    .mapIndexed { index, album ->
                        album.toSpotifyHomeAlbumCache(
                            profileId = profileId,
                            sortOrder = index,
                            cachedAt = cachedAt,
                        )
                    },
            )
            dao.insertArtists(
                artists
                    .distinctBy { artist -> artist.id }
                    .mapIndexed { index, artist ->
                        artist.toSpotifyHomeArtistCache(
                            profileId = profileId,
                            sortOrder = index,
                            cachedAt = cachedAt,
                        )
                    },
            )
        }
    }

    // ── Home grid pool cache ──────────────────────────────────────────

    data class HomeGridPoolSnapshot(
        val albums: List<Album>,
        val tracks: List<Track>,
        val playlists: List<Playlist>,
        val cachedAt: Long,
    )

    /**
     * The persisted Jump Back In candidate pools for the active
     * profile+provider, in their stored (pre-shuffled) order. Null when
     * nothing is cached, or when [maxAgeMs] is given and the pools are older —
     * pass null to accept any age (the instant pre-paint path).
     */
    suspend fun getCachedHomeGridPools(maxAgeMs: Long?): HomeGridPoolSnapshot? {
        val provider = activeSource.value?.id ?: return null
        val profileId = activeProfileId.value ?: return null
        val rows = database.homeGridPoolDao().getForProfile(profileId, provider)
        if (rows.isEmpty()) return null
        val cachedAt = rows.minOf(HomeGridPoolCache::cachedAt)
        if (maxAgeMs != null && cachedAt < System.currentTimeMillis() - maxAgeMs) return null
        return HomeGridPoolSnapshot(
            albums = rows.filter { it.itemType == HomeGridPoolCache.TYPE_ALBUM }
                .map(HomeGridPoolCache::toAlbum),
            tracks = rows.filter { it.itemType == HomeGridPoolCache.TYPE_TRACK }
                .map(HomeGridPoolCache::toTrack),
            playlists = rows.filter { it.itemType == HomeGridPoolCache.TYPE_PLAYLIST }
                .map(HomeGridPoolCache::toPlaylist),
            cachedAt = cachedAt,
        )
    }

    /** Replace the active profile+provider's pools atomically, in list order. */
    suspend fun replaceHomeGridPools(
        albums: List<Album>,
        tracks: List<Track>,
        playlists: List<Playlist>,
    ) {
        val provider = activeSource.value?.id ?: return
        val profileId = activeProfileId.value ?: return
        val cachedAt = clock()
        val rows = buildList {
            albums.filter { it.id.provider == provider }.mapIndexedTo(this) { index, album ->
                album.toGridPoolRow(profileId, index, cachedAt)
            }
            tracks.filter { it.id.provider == provider }.mapIndexedTo(this) { index, track ->
                track.toGridPoolRow(profileId, index, cachedAt)
            }
            playlists.filter { it.id.provider == provider }.mapIndexedTo(this) { index, playlist ->
                playlist.toGridPoolRow(profileId, index, cachedAt)
            }
        }
        database.withTransaction {
            database.homeGridPoolDao().deleteForProfile(profileId, provider)
            database.homeGridPoolDao().insertAll(rows)
        }
    }

    // ── Playlists ──────────────────────────────────────────────────────

    suspend fun getPlaylists(): List<Playlist> {
        if (isSpotifyActive()) {
            val source = requireSpotifySource()
            val profileId = spotifyProfileId(source)
            ensureSpotifyLibraryFresh(source).getOrThrow()
            return spotifyCoordinator().readPlaylists(profileId)
        }
        return requireSource().library().getPlaylists()
    }

    suspend fun refreshSpotifyLibrary(force: Boolean = false): Result<Unit> {
        if (!isSpotifyActive()) return Result.success(Unit)
        val source = requireSpotifySource()
        return spotifyCoordinator().refreshLibrary(
            profileId = spotifyProfileId(source),
            source = source,
            force = force,
        )
    }

    /** True when the active Spotify profile has any cached library data. */
    suspend fun hasSpotifyCachedData(): Boolean {
        if (!isSpotifyActive()) return false
        val profileId = currentProfileId() ?: return false
        return spotifyCoordinator().hasAnyCachedData(profileId)
    }

    suspend fun getSpotifyLocalSearchSnapshot(): SpotifyLibrarySyncCoordinator.SpotifyLocalSearchSnapshot? {
        if (!isSpotifyActive()) return null
        val profileId = currentProfileId() ?: return null
        return spotifyCoordinator().readLocalSearchSnapshot(profileId)
    }

    suspend fun isSpotifyLibraryCacheFresh(): Boolean {
        if (!isSpotifyActive()) return false
        val profileId = currentProfileId() ?: return false
        return spotifyCoordinator().isCacheFresh(profileId)
    }

    suspend fun getPlaylist(id: MediaId): Playlist? = loadCachedDetail(
        mem = playlistDetailCache,
        baseKey = id.toString(),
        // Playlists are user-mutable → always revalidate online; disk is only an
        // offline fallback (and is overwritten by the next successful fetch).
        diskFreshMs = 0L,
        diskRead = { profileId -> detailCacheStore?.readPlaylist(profileId, id.toString()) },
        diskWrite = { profileId, value -> detailCacheStore?.writePlaylist(profileId, id.toString(), value) },
        fetch = { requireSource().library().getPlaylist(id) },
    )

    // ── Detail cache read-through + preload ────────────────────────────

    /**
     * In-memory cache key, profile-scoped so one account's detail can never be
     * served under another's (the disk layer is already keyed by profileId).
     * Neither a profile id nor a MediaId contains a space, so it's unambiguous.
     */
    private fun memKey(baseKey: String, profileId: String? = activeProfileId.value): String =
        if (profileId != null) "$profileId $baseKey" else baseKey

    /**
     * Layered read: in-memory fresh → disk-fresh (skip network) → network (write
     * both layers) → on error, any disk / in-memory copy (offline resilience).
     * Disk is profile-scoped + size-bounded ([DetailCacheStore]); mem is the hot
     * front keyed by [memKey] so accounts never cross. Concurrent loads of one
     * key are coalesced onto a single shared fetch (single-flight).
     *
     * `diskFreshMs == 0` (playlists) means "always revalidate online": neither
     * the mem nor the disk fresh fast-path is trusted, so external edits aren't
     * masked; the caches then serve only as an offline fallback. For trusted
     * (album/artist) entries a disk hit older than [detailRevalidateAfterMs]
     * kicks off a background refresh (stale-while-revalidate).
     */
    private suspend fun <V : Any> loadCachedDetail(
        mem: DetailMemoryCache<V>,
        baseKey: String,
        diskFreshMs: Long,
        diskRead: suspend (profileId: String) -> Cached<V>?,
        diskWrite: suspend (profileId: String, value: V) -> Unit,
        fetch: suspend () -> V?,
    ): V? {
        val profileId = activeProfileId.value
        val key = memKey(baseKey, profileId)
        if (diskFreshMs > 0L) {
            mem.getFresh(key)?.let { return it }
        }
        // Single-flight: racing loads of one key (a prefetch burst + a user tap
        // + a queue build) share one Deferred instead of each paying the fetch
        // and disk write. It runs on [repositoryScope] so a cancelled waiter
        // (e.g. an abandoned prefetch) can't abort the load for the rest, and a
        // failure propagates to every waiter. The entry is removed as the load
        // completes, so a failure never poisons its key.
        val shared = mem.inFlight.computeIfAbsent(key) {
            repositoryScope.async {
                loadDetailFromDiskOrNetwork(mem, key, profileId, diskFreshMs, diskRead, diskWrite, fetch)
            }.also { deferred ->
                // invokeOnCompletion, not try/finally: it fires even when the
                // scope is cancelled before the body runs, and the two-arg
                // remove can't evict a newer flight for the same key.
                deferred.invokeOnCompletion { mem.inFlight.remove(key, deferred) }
            }
        }
        return shared.await()
    }

    /**
     * The shared (single-flight) part of [loadCachedDetail]: disk-fresh →
     * network → on error, any disk / in-memory copy. The raw disk row is read
     * up front only when its freshness can be trusted (album/artist);
     * playlists (`diskFreshMs == 0`) go straight to the network and consult
     * disk purely as an offline fallback. The JSON decode is deferred to
     * [Cached.value], so a row that is never served is never decoded.
     */
    private suspend fun <V : Any> loadDetailFromDiskOrNetwork(
        mem: DetailMemoryCache<V>,
        key: String,
        profileId: String?,
        diskFreshMs: Long,
        diskRead: suspend (profileId: String) -> Cached<V>?,
        diskWrite: suspend (profileId: String, value: V) -> Unit,
        fetch: suspend () -> V?,
    ): V? {
        suspend fun diskRow(): Cached<V>? =
            profileId?.let { runCatching { diskRead(it) }.getOrNull() }

        // Snapshot the invalidation generation: an edit that lands while this
        // load is in flight bumps it, and every write-back below must then be
        // skipped or it would resurrect pre-edit data (7d fresh on disk).
        val generation = mem.generationOf(key)
        fun canWriteBack(): Boolean =
            activeProfileId.value == profileId && mem.generationOf(key) == generation

        val disk = if (diskFreshMs > 0L) diskRow() else null
        if (disk != null && clock() - disk.cachedAt <= diskFreshMs) {
            disk.value()?.let { value ->
                if (canWriteBack()) mem.put(key, value)
                if (clock() - disk.cachedAt > detailRevalidateAfterMs) {
                    revalidateDetail(mem, key, profileId, diskWrite, fetch)
                }
                return value
            }
        }
        return try {
            fetch()?.also { value ->
                // TOCTOU: only persist if still on the profile we resolved under
                // and the key wasn't invalidated mid-flight — a stale write-back
                // must not poison another account or overwrite a fresher edit.
                if (canWriteBack()) {
                    mem.put(key, value)
                    if (profileId != null) runCatching { diskWrite(profileId, value) }
                }
            }
        } catch (e: Exception) {
            (disk ?: diskRow())?.value()?.also { if (canWriteBack()) mem.put(key, it) }
                ?: mem.getStale(key) ?: throw e
        }
    }

    /** Background refresh of an already-served (slightly stale) disk entry. */
    private fun <V : Any> revalidateDetail(
        mem: DetailMemoryCache<V>,
        key: String,
        profileId: String?,
        diskWrite: suspend (profileId: String, value: V) -> Unit,
        fetch: suspend () -> V?,
    ) {
        val generation = mem.generationOf(key)
        repositoryScope.launch {
            runCatching { fetch() }.getOrNull()?.let { value ->
                if (activeProfileId.value == profileId && mem.generationOf(key) == generation) {
                    mem.put(key, value)
                    if (profileId != null) runCatching { diskWrite(profileId, value) }
                }
            }
        }
    }

    /** Drop the cached album detail (mem + disk) so its next read re-derives. */
    private fun invalidateAlbumDetail(id: MediaId) {
        val profileId = activeProfileId.value
        albumDetailCache.invalidate(memKey(id.toString(), profileId))
        profileId?.let { p -> repositoryScope.launch { detailCacheStore?.removeAlbum(p, id.toString()) } }
    }

    /** Drop the cached artist detail (mem + disk) — e.g. after a follow toggle. */
    private fun invalidateArtistDetail(id: MediaId) {
        val profileId = activeProfileId.value
        artistDetailCache.invalidate(memKey(id.toString(), profileId))
        profileId?.let { p -> repositoryScope.launch { detailCacheStore?.removeArtist(p, id.toString()) } }
    }

    /** Drop the cached playlist detail (mem + disk) after a playlist edit. */
    private suspend fun invalidatePlaylistDetail(id: MediaId) {
        val profileId = activeProfileId.value
        playlistDetailCache.invalidate(memKey(id.toString(), profileId))
        profileId?.let { detailCacheStore?.removePlaylist(it, id.toString()) }
    }

    /**
     * Warm the cache for something the user is likely to open next
     * (fire-and-forget on [repositoryScope]; no-op if already fresh). e.g. the
     * artist page preloads its album carousel so each tap opens instantly.
     */
    fun prefetchAlbum(id: MediaId) = prefetchDetail(albumDetailCache, id.toString()) { getAlbum(id) }

    fun prefetchArtist(id: MediaId) = prefetchDetail(artistDetailCache, id.toString()) { getArtist(id) }

    fun prefetchPlaylist(id: MediaId) =
        prefetchDetail(playlistDetailCache, id.toString()) { getPlaylist(id) }

    private fun <V : Any> prefetchDetail(
        cache: DetailMemoryCache<V>,
        baseKey: String,
        load: suspend () -> V?,
    ) {
        if (cache.getFresh(memKey(baseKey)) != null) return
        repositoryScope.launch { runCatching { load() } }
    }

    suspend fun createPlaylist(name: String, description: String? = null): Result<Playlist> =
        requireSource().writeActions().createPlaylist(name = name, description = description)
            .onSuccess { markSpotifyLibraryStaleIfNeeded() }

    suspend fun renamePlaylist(
        id: MediaId,
        name: String,
        description: String? = null,
    ): Result<Unit> =
        requireSource().writeActions().renamePlaylist(id = id, name = name, description = description)
            .onSuccess {
                markSpotifyLibraryStaleIfNeeded()
                invalidatePlaylistDetail(id)
            }

    suspend fun deletePlaylist(id: MediaId): Result<Unit> =
        requireSource().writeActions().deletePlaylist(id)
            .onSuccess {
                markSpotifyLibraryStaleIfNeeded()
                invalidatePlaylistDetail(id)
            }

    suspend fun addTracksToPlaylist(
        playlistId: MediaId,
        tracks: List<MediaId>,
    ): Result<String?> =
        requireSource().writeActions().addTracksToPlaylist(playlistId = playlistId, tracks = tracks)
            .onSuccess {
                markSpotifyLibraryStaleIfNeeded()
                invalidatePlaylistDetail(playlistId)
            }

    suspend fun removeTracksFromPlaylist(
        playlistId: MediaId,
        items: List<PlaylistItemRef>,
        snapshotId: String? = null,
    ): Result<String?> =
        requireSource().writeActions().removeTracksFromPlaylist(
            playlistId = playlistId,
            items = items,
            snapshotId = snapshotId,
        ).onSuccess {
            markSpotifyLibraryStaleIfNeeded()
            invalidatePlaylistDetail(playlistId)
        }

    // ── Rating (local-first, best-effort server sync) ──────────────────

    suspend fun setRating(trackId: MediaId, rating: Float) {
        val profileId = activeProfileId.value ?: return
        val localRating = rating.coerceIn(0f, 10f)
        val serverRating = (localRating / 2f).roundToInt().coerceIn(0, 5)
        val pending = LocalRating(
            profileId = profileId,
            songId = trackId.rawId,
            provider = trackId.provider,
            rating = localRating,
            serverRating = serverRating,
            needsSync = true,
        )
        database.localRatingDao().upsert(pending)
        val source = activeSource.value ?: return
        if (source.id != trackId.provider) return
        source.writeActions().setRating(trackId, serverRating).onSuccess {
            database.localRatingDao().upsert(pending.copy(needsSync = false))
        }
    }

    fun getRating(trackId: MediaId): Flow<LocalRating?> =
        activeProfileId.flatMapLatest { profileId ->
            if (profileId.isNullOrBlank()) {
                flowOf(null)
            } else {
                database.localRatingDao().getRating(trackId.rawId, trackId.provider, profileId)
            }
        }

    /**
     * Reactive saved-state for a Spotify track from the library cache — the
     * authoritative source for the Now Playing heart. Emits whenever the cache
     * row changes (optimistic favorite write, sync, un-favorite delete), so the
     * heart reflects the real Spotify library state and no longer reverts when
     * the transient [favoriteOverrides] entry is cleared on network success.
     *
     * Emits `null` when the id isn't a cached Spotify track (non-Spotify, or
     * the track simply isn't in the saved cache) so the caller can fall back to
     * the playback track's own isStarred.
     */
    fun observeSpotifyFavorite(id: MediaId): Flow<Boolean?> {
        if (id.provider != MediaId.PROVIDER_SPOTIFY) return flowOf(null)
        return activeProfileId.flatMapLatest { profileId ->
            if (profileId.isNullOrBlank()) {
                flowOf(null)
            } else {
                database.spotifyLibraryCacheDao()
                    .observeTrack(profileId, id.rawId)
                    .map { it?.isSaved }
            }
        }
    }

    suspend fun getRatings(trackIds: Collection<MediaId>): Map<MediaId, LocalRating> {
        val profileId = activeProfileId.value ?: return emptyMap()
        if (trackIds.isEmpty()) return emptyMap()
        return trackIds.asSequence()
            .distinct()
            .groupBy { it.provider }
            .flatMap { (provider, ids) ->
                database.localRatingDao().getRatings(ids.map { it.rawId }, provider, profileId)
            }
            .associateBy { MediaId(it.provider, it.songId) }
    }

    suspend fun syncPendingRatings() {
        val source = activeSource.value ?: return
        val profileId = activeProfileId.value ?: return
        val pending = database.localRatingDao()
            .getRatingsNeedingSync(source.id, profileId)
            .first()
        for (rating in pending) {
            if (rating.provider != source.id) continue
            val trackId = MediaId(rating.provider, rating.songId)
            source.writeActions().setRating(trackId, rating.serverRating).onSuccess {
                database.localRatingDao().upsert(rating.copy(needsSync = false))
            }
        }
    }

    // ── Notes ──────────────────────────────────────────────────────────

    fun observeNotes(trackId: MediaId): Flow<List<SongNote>> =
        activeProfileId.flatMapLatest { profileId ->
            if (profileId.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                songNoteDao.observeForTrack(trackId.rawId, trackId.provider, profileId)
            }
        }

    fun observeCrossProviderNotes(
        trackId: MediaId,
        title: String,
        artist: String,
    ): Flow<List<SongNote>> {
        val normalizedTitle = title.trim()
        val normalizedArtist = artist.trim()
        if (normalizedTitle.isEmpty() || normalizedArtist.isEmpty()) {
            return flowOf(emptyList())
        }
        return activeProfileId.flatMapLatest { profileId ->
            if (profileId.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                songNoteDao.observeCrossProvider(
                    title = normalizedTitle,
                    artist = normalizedArtist,
                    trackId = trackId.rawId,
                    provider = trackId.provider,
                    profileId = profileId,
                )
            }
        }
    }

    fun observeTracksWithNotes(trackIds: Collection<MediaId>): Flow<Set<MediaId>> {
        val distinctTrackIds = trackIds.distinct()
        if (distinctTrackIds.isEmpty()) {
            return flowOf(emptySet())
        }

        return activeProfileId.flatMapLatest { profileId ->
            if (profileId.isNullOrBlank()) {
                return@flatMapLatest flowOf(emptySet())
            }

            val groupedFlows = distinctTrackIds
                .groupBy(MediaId::provider)
                .map { (provider, ids) ->
                    songNoteDao.observeKeys(
                        trackIds = ids.map(MediaId::rawId),
                        provider = provider,
                        profileId = profileId,
                    )
                }

            if (groupedFlows.size == 1) {
                groupedFlows.first().map { keys -> keys.toMediaIdSet() }
            } else {
                combine(groupedFlows) { groups ->
                    buildSet {
                        groups.forEach { keys ->
                            addAll(keys.toMediaIdSet())
                        }
                    }
                }
            }
        }
    }

    /** User tapped Save —— 为当前曲目追加一条新的笔记。content 空串会被忽略。 */
    suspend fun addNote(track: Track, content: String): SongNote? {
        val profileId = activeProfileId.value ?: return null
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null
        val now = clock()
        val note = SongNote(
            id = java.util.UUID.randomUUID().toString(),
            profileId = profileId,
            trackId = track.id.rawId,
            provider = track.id.provider,
            content = trimmed,
            createdAt = now,
            updatedAt = now,
            title = track.title.orEmpty(),
            artist = track.artist.orEmpty(),
        )
        songNoteDao.insert(note)
        return note
    }

    suspend fun updateNote(note: SongNote, content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            songNoteDao.deleteById(note.id)
            return
        }
        songNoteDao.update(note.copy(content = trimmed, updatedAt = clock()))
    }

    suspend fun deleteNoteById(id: String) {
        songNoteDao.deleteById(id)
    }

    // ── Album notes ────────────────────────────────────────────────────

    fun observeAlbumNotes(albumId: MediaId): Flow<List<AlbumNote>> =
        activeProfileId.flatMapLatest { profileId ->
            if (profileId.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                albumNoteDao.observeForAlbum(albumId.rawId, albumId.provider, profileId)
            }
        }

    fun observeAlbumsWithNotes(albumIds: Collection<MediaId>): Flow<Set<MediaId>> {
        val distinct = albumIds.distinct()
        if (distinct.isEmpty()) return flowOf(emptySet())
        return activeProfileId.flatMapLatest { profileId ->
            if (profileId.isNullOrBlank()) {
                return@flatMapLatest flowOf(emptySet())
            }
            val grouped = distinct.groupBy(MediaId::provider)
                .map { (provider, ids) ->
                    albumNoteDao.observeKeys(ids.map(MediaId::rawId), provider, profileId)
                }
            if (grouped.size == 1) {
                grouped.first().map { keys -> keys.toAlbumMediaIdSet() }
            } else {
                combine(grouped) { groups ->
                    buildSet { groups.forEach { addAll(it.toAlbumMediaIdSet()) } }
                }
            }
        }
    }

    suspend fun addAlbumNote(album: Album, content: String): AlbumNote? {
        val profileId = activeProfileId.value ?: return null
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null
        val now = clock()
        val note = AlbumNote(
            id = java.util.UUID.randomUUID().toString(),
            profileId = profileId,
            albumId = album.id.rawId,
            provider = album.id.provider,
            content = trimmed,
            createdAt = now,
            updatedAt = now,
            albumName = album.name,
            artist = album.artist.orEmpty(),
        )
        albumNoteDao.insert(note)
        return note
    }

    suspend fun updateAlbumNote(note: AlbumNote, content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            albumNoteDao.deleteById(note.id)
            return
        }
        albumNoteDao.update(note.copy(content = trimmed, updatedAt = clock()))
    }

    suspend fun deleteAlbumNoteById(id: String) {
        albumNoteDao.deleteById(id)
    }

    /**
     * 把该专辑的单曲笔记原文按 (曲目序 → content) 拼起来 —— 只用于 AlbumDetail
     * 的默认聚合展示和 Review 草稿灵感。不推到 NeoDB。
     */
    suspend fun aggregateSongNotesForAlbum(album: Album): String {
        val profileId = activeProfileId.value ?: return ""
        if (album.tracks.isEmpty()) return ""
        val trackIds = album.tracks.map(Track::id)
        return trackIds.asSequence()
            .distinct()
            .groupBy(MediaId::provider)
            .flatMap { (provider, ids) ->
                songNoteDao.getForTracks(
                    trackIds = ids.map(MediaId::rawId),
                    provider = provider,
                    profileId = profileId,
                )
            }
            .sortedBy(SongNote::createdAt)
            .joinToString(separator = "\n\n") { note -> note.content }
    }

    // ── Album ratings / review ─────────────────────────────────────────

    fun observeAlbumRating(albumId: MediaId): Flow<AlbumRating?> =
        activeProfileId.flatMapLatest { profileId ->
            if (profileId.isNullOrBlank()) {
                flowOf(null)
            } else {
                albumRatingDao.observe(albumId.rawId, albumId.provider, profileId)
            }
        }

    suspend fun setAlbumRating(album: Album, rating: Float) {
        val profileId = activeProfileId.value ?: return
        val existing = albumRatingDao.get(album.id.rawId, album.id.provider, profileId)
        val entry = (existing ?: AlbumRating(
            profileId = profileId,
            albumId = album.id.rawId,
            provider = album.id.provider,
            rating = 0f,
            review = null,
            neoDbReviewUuid = null,
        )).copy(
            rating = rating.coerceIn(0f, 10f),
            ratingNeedsSync = true,
            updatedAt = clock(),
        )
        albumRatingDao.upsert(entry)
    }

    suspend fun setAlbumReview(album: Album, review: String?) {
        val profileId = activeProfileId.value ?: return
        val normalized = review?.trim().takeUnless { it.isNullOrEmpty() }
        val existing = albumRatingDao.get(album.id.rawId, album.id.provider, profileId)
        val entry = (existing ?: AlbumRating(
            profileId = profileId,
            albumId = album.id.rawId,
            provider = album.id.provider,
            rating = 0f,
            review = null,
            neoDbReviewUuid = null,
        )).copy(
            review = normalized,
            reviewNeedsSync = true,
            updatedAt = clock(),
        )
        albumRatingDao.upsert(entry)
    }

    suspend fun pushAlbumToNeoDB(album: Album): Result<Unit> =
        activeProfileId.value
            ?.let { profileId -> neoDbSyncService.pushAlbum(profileId, album) }
            ?: Result.failure(IllegalStateException("No active profile"))

    suspend fun pullAlbumFromNeoDB(album: Album): Result<AlbumRating?> =
        activeProfileId.value
            ?.let { profileId -> neoDbSyncService.pullAlbum(profileId, album) }
            ?: Result.failure(IllegalStateException("No active profile"))

    suspend fun isNeoDBConfigured(): Boolean = neoDbSyncService.isConfigured()

    // ── Album Memory candidates ───────────────────────────────────────

    suspend fun getAlbumMemoryCandidates(limit: Int = 48): List<AlbumMemoryCandidate> {
        val source = activeSource.value ?: return emptyList()
        val profileId = activeProfileId.value ?: return emptyList()
        return AlbumMemoryCandidateBuilder(
            profileId = profileId,
            provider = source.id,
            // Cached path (mem LRU + disk + single-flight) — the builder must
            // never hit source.library().getAlbum directly, or a 48-album scan
            // becomes a raw network fan-out.
            getAlbum = ::getAlbum,
            playHistoryDao = database.playHistoryDao(),
            activityEventDao = database.activityEventDao(),
            localRatingDao = database.localRatingDao(),
            albumRatingDao = albumRatingDao,
            albumNoteDao = albumNoteDao,
            songNoteDao = songNoteDao,
            songAboutEntryDao = songAboutEntryDao,
            resolveCoverUrl = { ref, size -> resolveCoverUrl(ref, size) },
        ).build(limit)
    }

    suspend fun getTopAlbumMemoryCandidate(): AlbumMemoryCandidate? =
        getAlbumMemoryCandidates(limit = 1).firstOrNull()

    /**
     * The active profile's most recently touched song notes on the current
     * provider, newest first. Feeds the home widget grid's noted-track card.
     */
    suspend fun getRecentSongNotes(limit: Int = 8): List<SongNote> {
        val provider = activeSource.value?.id ?: return emptyList()
        val profileId = activeProfileId.value ?: return emptyList()
        return songNoteDao.getRecent(provider = provider, profileId = profileId, limit = limit)
    }

    /**
     * Re-emits whenever the active profile's memory signals change — a song
     * note, a track rating, or an album rating/review write on the current
     * provider. The home widget grid listens to this to keep its two
     * memory-flavoured cards live: those writes create no activity event, so
     * observing the tables directly is the only reliable trigger.
     */
    fun observeMemorySignalStamp(): Flow<Long> =
        combine(activeProfileId, activeSource) { profileId, source ->
            profileId to source?.id
        }
            .flatMapLatest { (profileId, provider) ->
                if (profileId.isNullOrBlank() || provider == null) {
                    flowOf(0L)
                } else {
                    combine(
                        songNoteDao.observeChangeStamp(provider, profileId),
                        database.localRatingDao().observeChangeStamp(provider, profileId),
                        albumRatingDao.observeChangeStamp(provider, profileId),
                    ) { notes, ratings, albums -> notes + ratings + albums }
                }
            }
            .distinctUntilChanged()

    // ── Memory copy cache (Gemini 感性文案) ─────────────────────────────

    /**
     * 读/生成 Memory 专辑卡片的 Gemini 短评。命中缓存直接返回；否则
     * 后台调用 Gemini 并落库。Key 为 (profileId, albumId, provider,
     * signal hash)；signal 变了会重算（如均分或覆盖数变化）。
     *
     * 返回 null 表示无 API key / 没开 BYOK / 没有活跃 profile ——
     * 调用方应默默降级，不弹错。
     */
    suspend fun getOrGenerateAlbumMemoryCopy(
        album: Album,
        averageRating: Float?,
        ratedSongCount: Int,
        totalSongCount: Int,
    ): String? {
        val profileId = activeProfileId.value ?: return null
        val signal = buildString {
            append(album.id.toString()).append('|')
            append(album.name).append('|')
            append(album.artist.orEmpty()).append('|')
            append(album.year ?: 0).append('|')
            append(averageRating ?: 0f).append('|')
            append(ratedSongCount).append('/').append(totalSongCount)
        }
        val hash = signal.hashCode().toString()

        val cached = memoryCopyCacheDao.get(
            profileId = profileId,
            provider = album.id.provider,
            entityType = MemoryCopyCache.ENTITY_ALBUM,
            entityId = album.id.rawId,
        )
        if (cached != null && cached.promptHash == hash) return cached.copy

        val apiKey = geminiConfigDao.getConfig().first()?.apiKey
        if (apiKey.isNullOrBlank()) return cached?.copy

        return runCatching {
            val generated = geminiService.generateAlbumMemoryCopy(
                apiKey = apiKey,
                albumName = album.name,
                artist = album.artist,
                year = album.year,
                averageRating = averageRating,
                ratedSongCount = ratedSongCount,
                totalSongCount = totalSongCount,
            )
            memoryCopyCacheDao.upsert(
                MemoryCopyCache(
                    profileId = profileId,
                    provider = album.id.provider,
                    entityType = MemoryCopyCache.ENTITY_ALBUM,
                    entityId = album.id.rawId,
                    copy = generated,
                    promptHash = hash,
                    generatedAt = clock(),
                ),
            )
            generated
        }.getOrElse { cached?.copy }
    }

    // ── Devices ───────────────────────────────────────────────────────

    suspend fun listSpotifyDevices(): List<YoinDevice.SpotifyConnect> {
        val source = activeSource.value as? SpotifyMusicSource ?: return emptyList()
        return source.listDevices()
            .mapNotNull { device ->
                val id = device.id ?: return@mapNotNull null
                YoinDevice.SpotifyConnect(
                    id = id,
                    name = device.name,
                    isActive = device.isActive,
                    spotifyType = device.type,
                    isSelectable = !device.isRestricted,
                    statusText = when {
                        device.isRestricted -> "Unavailable from Spotify"
                        device.isPrivateSession -> "Private session"
                        else -> null
                    },
                )
            }
            .sortedByDescending(YoinDevice.SpotifyConnect::isActive)
    }

    suspend fun transferSpotifyPlayback(deviceId: String, play: Boolean = true) {
        val source = activeSource.value as? SpotifyMusicSource ?: return
        source.transferPlayback(deviceId = deviceId, play = play)
    }

    // ── Lyrics ─────────────────────────────────────────────────────────

    /**
     * Subsonic 走自家 `getLyricsBySongId.view`（服务端快，不走本地缓存）；其他
     * provider（目前只有 Spotify）没有服务器歌词，先查 [lyricsCacheDao] 30 天
     * 内的命中，否则走 [LyricsProviderRegistry] 串行兜底（QQ → 网易云 → LRCLIB）
     * 并把原始 LRC 落表。
     *
     * 需要 [title] + [artist] 做搜索；任一为空就直接返回 null。
     */
    suspend fun getLyrics(
        trackId: MediaId,
        title: String? = null,
        artist: String? = null,
    ): Lyrics? = getLoadedLyrics(trackId, title, artist)?.lyrics

    suspend fun getLoadedLyrics(
        trackId: MediaId,
        title: String? = null,
        artist: String? = null,
    ): LoadedLyrics? {
        val source = requireSource()
        if (source.id == MediaId.PROVIDER_SUBSONIC) {
            return source.metadata().getLyrics(trackId)?.let { lyrics ->
                LoadedLyrics(
                    lyrics = lyrics,
                    providerName = MediaId.PROVIDER_SUBSONIC,
                    providerSongId = trackId.rawId,
                )
            }
        }
        val t = title?.trim().orEmpty()
        val a = artist?.trim().orEmpty()
        if (t.isEmpty() || a.isEmpty()) return null

        val now = clock()
        val minCachedAt = now - LYRICS_CACHE_TTL_MS
        lyricsCacheDao
            .getFresh(trackId.provider, trackId.rawId, minCachedAt)
            ?.let {
                return LoadedLyrics(
                    lyrics = LrcParser.parse(it.lrc),
                    providerName = it.lyricsProvider,
                    providerSongId = it.lyricsProviderSongId,
                )
            }

        val hit = lyricsProviderRegistry.fetchLyric(t, a) ?: return null
        lyricsCacheDao.upsert(
            LyricsCache(
                trackProvider = trackId.provider,
                trackRawId = trackId.rawId,
                lyricsProvider = hit.providerName,
                lyricsProviderSongId = hit.providerSongId,
                lrc = hit.lrc,
                cachedAt = now,
            ),
        )
        return LoadedLyrics(
            lyrics = LrcParser.parse(hit.lrc),
            providerName = hit.providerName,
            providerSongId = hit.providerSongId,
        )
    }

    suspend fun searchAndApplyLyrics(
        trackId: MediaId,
        title: String?,
        artist: String?,
    ): Result<LyricsApplyResult> = runCatching {
        val t = title?.trim().orEmpty()
        val a = artist?.trim().orEmpty()
        require(t.isNotEmpty() && a.isNotEmpty()) {
            "Need title and artist to search lyrics"
        }

        val hit = lyricsProviderRegistry.fetchLyric(t, a)
            ?: throw NoSuchElementException("No lyrics found")
        lyricsCacheDao.upsert(
            LyricsCache(
                trackProvider = trackId.provider,
                trackRawId = trackId.rawId,
                lyricsProvider = hit.providerName,
                lyricsProviderSongId = hit.providerSongId,
                lrc = hit.lrc,
                cachedAt = clock(),
            ),
        )
        LyricsApplyResult(
            lyrics = LrcParser.parse(hit.lrc),
            providerName = hit.providerName,
            providerSongId = hit.providerSongId,
        )
    }

    fun lyricsProviderNames(): List<String> = lyricsProviderRegistry.providerNames

    suspend fun searchLyricsProviderSections(
        query: String,
    ): Result<List<LyricsSearchProviderSection>> = runCatching {
        val q = query.trim()
        require(q.isNotEmpty()) { "Search query is empty" }

        lyricsProviderRegistry.searchByProvider(
            title = q,
            artist = "",
            limitPerProvider = 3,
        ).map { providerResult ->
            LyricsSearchProviderSection(
                providerName = providerResult.providerName,
                candidates = providerResult.matches.map { match ->
                    LyricsSearchCandidate(
                        providerName = providerResult.providerName,
                        songId = match.songId,
                        title = match.title,
                        artist = match.artist,
                    )
                },
                errorMessage = providerResult.errorMessage,
            )
        }
    }

    suspend fun applyLyricsSearchResult(
        trackId: MediaId,
        providerName: String,
        songId: String,
    ): Result<LyricsApplyResult> = runCatching {
        val hit = lyricsProviderRegistry.fetchSelectedLyric(providerName, songId)
            ?: throw NoSuchElementException("No lyrics found")
        lyricsCacheDao.upsert(
            LyricsCache(
                trackProvider = trackId.provider,
                trackRawId = trackId.rawId,
                lyricsProvider = hit.providerName,
                lyricsProviderSongId = hit.providerSongId,
                lrc = hit.lrc,
                cachedAt = clock(),
            ),
        )
        LyricsApplyResult(
            lyrics = LrcParser.parse(hit.lrc),
            providerName = hit.providerName,
            providerSongId = hit.providerSongId,
        )
    }

    suspend fun applyLyrics(
        trackId: MediaId,
        rawLrc: String,
    ): Result<LyricsApplyResult> = runCatching {
        val trimmed = rawLrc.trim()
        require(trimmed.isNotEmpty()) { "Lyrics are empty" }
        lyricsCacheDao.upsert(
            LyricsCache(
                trackProvider = trackId.provider,
                trackRawId = trackId.rawId,
                lyricsProvider = "manual",
                lyricsProviderSongId = null,
                lrc = trimmed,
                cachedAt = clock(),
            ),
        )
        LyricsApplyResult(
            lyrics = LrcParser.parse(trimmed),
            providerName = "manual",
            providerSongId = null,
        )
    }

    suspend fun translateLyrics(
        trackId: MediaId,
        title: String?,
        artist: String?,
        lines: List<String>,
        currentLyricsProviderName: String?,
        currentLyricsProviderSongId: String?,
    ): LyricsTranslationResult {
        val t = title?.trim().orEmpty()
        val a = artist?.trim().orEmpty()
        val sourceLines = lines.map(String::trim).filter(String::isNotEmpty)
        if (sourceLines.isEmpty()) {
            return LyricsTranslationResult.Error("No lyrics to translate")
        }
        val config = geminiConfigDao.getConfig().first()
        val targetLanguage = GeminiConfig.normalizeTargetLanguage(config?.targetLanguage)
        if (sourceLines.appearToAlreadyBeTargetLanguage(targetLanguage)) {
            return LyricsTranslationResult.AlreadyTargetLanguage(targetLanguage)
        }

        if (targetLanguage.isChineseTargetLanguage()) {
            val providerResult = translateLyricsWithProvider(
                trackId = trackId,
                title = t,
                artist = a,
                sourceLines = sourceLines,
                targetLanguage = targetLanguage,
                currentLyricsProviderName = currentLyricsProviderName,
                currentLyricsProviderSongId = currentLyricsProviderSongId,
            )
            if (providerResult != null) return providerResult
        }

        val sourceHash = buildLyricsTranslationSourceHash(t, a, sourceLines)
        val cached = lyricsTranslationCacheDao.get(
            trackProvider = trackId.provider,
            trackRawId = trackId.rawId,
            sourceHash = sourceHash,
            targetLanguage = targetLanguage,
            model = GeminiService.MODEL,
        )
        cached
            ?.toTranslations(expectedLineCount = sourceLines.size)
            ?.let { return LyricsTranslationResult.Success(it) }

        val apiKey = config?.apiKey?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            return LyricsTranslationResult.ApiKeyMissing
        }
        return try {
            val translations = geminiService.translateLyricLines(
                apiKey = apiKey,
                title = t.ifEmpty { "Unknown song" },
                artist = a.ifEmpty { "Unknown artist" },
                lines = sourceLines,
                targetLanguage = targetLanguage,
            )
            val cleanedTranslations = translations.mapValues { (_, translation) ->
                GeminiService.cleanLineTranslation(translation)
            }
            lyricsTranslationCacheDao.upsert(
                LyricsTranslationCache(
                    trackProvider = trackId.provider,
                    trackRawId = trackId.rawId,
                    sourceHash = sourceHash,
                    targetLanguage = targetLanguage,
                    model = GeminiService.MODEL,
                    translationsJson = lyricTranslationCacheJson.encodeToString(
                        sourceLines.indices.map { index ->
                            cleanedTranslations[index].orEmpty()
                        },
                    ),
                    cachedAt = clock(),
                ),
            )
            LyricsTranslationResult.Success(cleanedTranslations)
        } catch (error: Exception) {
            LyricsTranslationResult.Error(error.message ?: "Failed to translate lyrics")
        }
    }

    suspend fun applyLyricsTranslationProviderSwitch(
        trackId: MediaId,
        offer: LyricsTranslationProviderSwitchOffer,
    ) {
        lyricsCacheDao.upsert(
            LyricsCache(
                trackProvider = trackId.provider,
                trackRawId = trackId.rawId,
                lyricsProvider = offer.providerName,
                lyricsProviderSongId = offer.providerSongId,
                lrc = offer.rawLrc,
                cachedAt = clock(),
            ),
        )
    }

    private suspend fun translateLyricsWithProvider(
        trackId: MediaId,
        title: String,
        artist: String,
        sourceLines: List<String>,
        targetLanguage: String,
        currentLyricsProviderName: String?,
        currentLyricsProviderSongId: String?,
    ): LyricsTranslationResult? {
        val currentProvider = currentLyricsProviderName?.takeIf { it in PROVIDER_TRANSLATION_NAMES }
            ?: return null
        val current = fetchProviderTranslation(
            providerName = currentProvider,
            providerSongId = currentLyricsProviderSongId,
            title = title,
            artist = artist,
            trackId = trackId,
            targetLanguage = targetLanguage,
            sourceLinesForFallbackCache = sourceLines,
            persistLyrics = true,
        )
        if (current != null) {
            return LyricsTranslationResult.Success(
                translations = current.translations,
                lyrics = current.lyrics,
                providerName = current.providerName,
                providerSongId = current.providerSongId,
            )
        }

        if (currentProvider == PROVIDER_QQ) {
            val netease = fetchProviderTranslation(
                providerName = PROVIDER_NETEASE,
                providerSongId = null,
                title = title,
                artist = artist,
                trackId = trackId,
                targetLanguage = targetLanguage,
                sourceLinesForFallbackCache = sourceLines,
                persistLyrics = false,
            )
            if (netease != null) {
                return LyricsTranslationResult.ProviderSwitchAvailable(
                    LyricsTranslationProviderSwitchOffer(
                        providerName = netease.providerName,
                        providerSongId = netease.providerSongId,
                        lyrics = netease.lyrics,
                        rawLrc = netease.rawLrc,
                        translations = netease.translations,
                    ),
                )
            }
        }
        return null
    }

    private suspend fun fetchProviderTranslation(
        providerName: String,
        providerSongId: String?,
        title: String,
        artist: String,
        trackId: MediaId,
        targetLanguage: String,
        sourceLinesForFallbackCache: List<String>,
        persistLyrics: Boolean,
    ): ProviderTranslation? {
        val hit = if (providerSongId != null) {
            lyricsProviderRegistry.fetchSelectedLyricWithTranslation(providerName, providerSongId)
        } else {
            lyricsProviderRegistry.searchAndFetchLyricWithTranslation(providerName, title, artist)
        } ?: return null
        val translatedLrc = hit.translatedLrc?.takeIf { it.isNotBlank() } ?: return null
        val lyrics = LrcParser.parse(hit.lrc)
        if (persistLyrics) {
            lyricsCacheDao.upsert(
                LyricsCache(
                    trackProvider = trackId.provider,
                    trackRawId = trackId.rawId,
                    lyricsProvider = hit.providerName,
                    lyricsProviderSongId = hit.providerSongId,
                    lrc = hit.lrc,
                    cachedAt = clock(),
                ),
            )
        }
        val providerSourceLines = lyrics.lineTexts().ifEmpty { sourceLinesForFallbackCache }
        val sourceHash = buildLyricsTranslationSourceHash(title, artist, providerSourceLines)
        val model = "provider:${hit.providerName}"
        lyricsTranslationCacheDao.get(
            trackProvider = trackId.provider,
            trackRawId = trackId.rawId,
            sourceHash = sourceHash,
            targetLanguage = targetLanguage,
            model = model,
        )?.toTranslations(expectedLineCount = providerSourceLines.size)?.let { translations ->
            return ProviderTranslation(
                providerName = hit.providerName,
                providerSongId = hit.providerSongId,
                lyrics = lyrics,
                rawLrc = hit.lrc,
                translations = translations,
            )
        }

        val translations = buildProviderTranslations(lyrics, translatedLrc)
        if (translations.isEmpty()) return null
        lyricsTranslationCacheDao.upsert(
            LyricsTranslationCache(
                trackProvider = trackId.provider,
                trackRawId = trackId.rawId,
                sourceHash = sourceHash,
                targetLanguage = targetLanguage,
                model = model,
                translationsJson = lyricTranslationCacheJson.encodeToString(
                    providerSourceLines.indices.map { index -> translations[index].orEmpty() },
                ),
                cachedAt = clock(),
            ),
        )
        return ProviderTranslation(
            providerName = hit.providerName,
            providerSongId = hit.providerSongId,
            lyrics = lyrics,
            rawLrc = hit.lrc,
            translations = translations,
        )
    }

    /**
     * Observe every About entry (canonical + ask) for the given song,
     * ordered canonical-first then ask by `updatedAt desc`. The same song
     * played from a different profile/provider will emit identical rows
     * because the key is derived from normalized `title + artist + album`.
     */
    fun observeAbout(
        title: String,
        artist: String,
        album: String,
    ): Flow<List<SongAboutEntry>> = songAboutEntryDao.observe(
        titleKey = SongAboutEntry.normalize(title),
        artistKey = SongAboutEntry.normalize(artist),
        albumKey = SongAboutEntry.normalize(album),
    )

    /**
     * Ensure the 6 canonical About rows exist for this song. No-op when
     * canonical rows are already cached unless [retry] is true, in which
     * case the call re-fetches and overwrites.
     *
     * Called lazily on first About open (compact or fullscreen), not per
     * track change.
     */
    suspend fun ensureCanonicalAbout(
        title: String,
        artist: String,
        album: String,
        retry: Boolean = false,
    ): AboutLoadResult {
        val titleKey = SongAboutEntry.normalize(title)
        val artistKey = SongAboutEntry.normalize(artist)
        val albumKey = SongAboutEntry.normalize(album)

        val existing = if (!retry) {
            songAboutEntryDao.getCanonical(titleKey, artistKey, albumKey)
        } else {
            emptyList()
        }
        if (!retry) {
            val existingKeys = existing.mapTo(mutableSetOf()) { it.entryKey }
            if (existingKeys.containsAll(SongAboutEntry.CANONICAL_ORDER)) {
                return AboutLoadResult.Success
            }
        }

        val config = geminiConfigDao.getConfig().first()
        val apiKey = config?.apiKey
        if (apiKey.isNullOrBlank()) {
            return if (existing.isNotEmpty()) {
                AboutLoadResult.Success
            } else {
                AboutLoadResult.ApiKeyMissing
            }
        }
        val targetLanguage = GeminiConfig.normalizeTargetLanguage(config.targetLanguage)

        return runCatching {
            val values = geminiService.generateCanonicalAbout(
                apiKey = apiKey,
                title = title,
                artist = artist,
                album = album,
                targetLanguage = targetLanguage,
            )
            val valuesByKey = values.associate { it.entryKey to it.answer }
            val now = clock()
            val rows = SongAboutEntry.CANONICAL_ORDER.map { entryKey ->
                SongAboutEntry(
                    titleKey = titleKey,
                    artistKey = artistKey,
                    albumKey = albumKey,
                    titleDisplay = title,
                    artistDisplay = artist,
                    albumDisplay = album,
                    kind = SongAboutEntry.KIND_CANONICAL,
                    entryKey = entryKey,
                    promptText = null,
                    titleText = null,
                    answerText = valuesByKey[entryKey].orEmpty(),
                    createdAt = now,
                    updatedAt = now,
                )
            }
            songAboutEntryDao.upsertAll(rows)
            AboutLoadResult.Success
        }.getOrElse { error ->
            AboutLoadResult.Error(error.message ?: "Failed to load song about info")
        }
    }

    /**
     * Generate a short non-persisted heading for the in-flight Ask Gemini
     * state. The eventual answer path still owns the persisted title.
     */
    suspend fun generateAskTitle(
        title: String,
        artist: String,
        album: String,
        question: String,
    ): AskTitleResult {
        val trimmedQuestion = question.trim()
        if (trimmedQuestion.isEmpty()) {
            return AskTitleResult.Error("Question is empty")
        }

        val config = geminiConfigDao.getConfig().first()
        val apiKey = config?.apiKey
        if (apiKey.isNullOrBlank()) return AskTitleResult.ApiKeyMissing
        val targetLanguage = GeminiConfig.normalizeTargetLanguage(config.targetLanguage)

        return runCatching {
            val heading = geminiService.generateAskTitle(
                apiKey = apiKey,
                title = title,
                artist = artist,
                album = album,
                question = trimmedQuestion,
                targetLanguage = targetLanguage,
            )
            AskTitleResult.Success(heading)
        }.getOrElse { error ->
            AskTitleResult.Error(error.message ?: "Failed to load Gemini title")
        }
    }

    /**
     * Ask Gemini a free-form question about the song. On success, upsert
     * an `ask` row keyed by the normalized question. Re-asking the same
     * normalized question updates the answer and `updatedAt` while
     * preserving the original `createdAt`.
     */
    suspend fun askAboutSong(
        title: String,
        artist: String,
        album: String,
        question: String,
    ): AskAboutResult {
        val trimmedQuestion = question.trim()
        if (trimmedQuestion.isEmpty()) {
            return AskAboutResult.Error("Question is empty")
        }

        val config = geminiConfigDao.getConfig().first()
        val apiKey = config?.apiKey
        if (apiKey.isNullOrBlank()) return AskAboutResult.ApiKeyMissing
        val targetLanguage = GeminiConfig.normalizeTargetLanguage(config.targetLanguage)

        val titleKey = SongAboutEntry.normalize(title)
        val artistKey = SongAboutEntry.normalize(artist)
        val albumKey = SongAboutEntry.normalize(album)
        val questionKey = SongAboutEntry.normalize(trimmedQuestion)

        return runCatching {
            val reply = geminiService.askAboutSong(
                apiKey = apiKey,
                title = title,
                artist = artist,
                album = album,
                question = trimmedQuestion,
                targetLanguage = targetLanguage,
            )
            val now = clock()
            val existing = songAboutEntryDao.getAsk(titleKey, artistKey, albumKey, questionKey)
            val row = SongAboutEntry(
                titleKey = titleKey,
                artistKey = artistKey,
                albumKey = albumKey,
                titleDisplay = title,
                artistDisplay = artist,
                albumDisplay = album,
                kind = SongAboutEntry.KIND_ASK,
                entryKey = questionKey,
                promptText = trimmedQuestion,
                titleText = reply.title,
                answerText = reply.answer,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            songAboutEntryDao.upsert(row)
            AskAboutResult.Success(reply.answer)
        }.getOrElse { error ->
            AskAboutResult.Error(error.message ?: "Failed to ask Gemini")
        }
    }

    // ── Play history / activity ────────────────────────────────────────

    suspend fun recordPlay(
        track: Track,
        durationMs: Long,
        completedPercent: Float,
        activityContext: ActivityContext = ActivityContext.None,
    ) {
        val profileId = activeProfileId.value ?: return
        database.playHistoryDao().insert(
            PlayHistory(
                songId = track.id.rawId,
                profileId = profileId,
                provider = track.id.provider,
                title = track.title.orEmpty(),
                artist = track.artist.orEmpty(),
                album = track.album.orEmpty(),
                albumId = track.albumId?.rawId.orEmpty(),
                // Store URL for Spotify (CoverRef.Url) or raw id for Subsonic
                // (CoverRef.SourceRelative). Readers use CoverRef.fromStorageKey
                // to reconstitute, so both providers round-trip correctly.
                coverArtId = CoverRef.toStorageKey(track.coverArt),
                durationMs = durationMs,
                completedPercent = completedPercent,
            ),
        )
        database.activityEventDao().insert(buildPlaybackActivity(track, activityContext, profileId))
    }

    fun getRecentHistory(limit: Int = 50): Flow<List<PlayHistory>> =
        combine(activeSource, activeProfileId) { source, profileId ->
            source?.id to profileId
        }.flatMapLatest { (provider, profileId) ->
            if (provider == null || profileId.isNullOrBlank()) {
                return@flatMapLatest flowOf(emptyList())
            }
            database.playHistoryDao().getRecentHistory(profileId, provider, limit)
        }

    fun getRecentActivities(limit: Int = 20): Flow<List<ActivityEvent>> =
        combine(activeSource, activeProfileId) { source, profileId ->
            source?.id to profileId
        }.flatMapLatest { (provider, profileId) ->
            if (provider == null || profileId.isNullOrBlank()) {
                return@flatMapLatest flowOf(emptyList())
            }
            database.activityEventDao().getRecentEvents(profileId, provider, limit * 8)
        }
            .map { events -> collapseToLatestUnique(events).take(limit) }

    /**
     * Spotify-only Activities source: the user's real recently-played history
     * (newest first), mapped into the same [ActivityEvent] shape the home feed
     * already consumes. Each play contributes an album, primary-artist and song
     * entry (deduped) so Activities can open the album, artist *and* song pages
     * — context the local visit/play recorder can't capture for Spotify.
     *
     * Throws on transport/auth failure (including 403 when the
     * `user-read-recently-played` scope is missing) so callers can fall back to
     * [getRecentActivities]. Returns empty — not an error — when the user simply
     * has no recent plays.
     */
    suspend fun getSpotifyRecentActivities(limit: Int = 20): List<ActivityEvent> {
        val source = activeSource.value as? SpotifyMusicSource ?: return emptyList()
        val profileId = spotifyProfileId(source)
        // Pull the full page (50, the endpoint max); one play fans out to
        // album/artist/song so the deduped feed still has plenty after take().
        val history = source.getRecentlyPlayed(limit = 50)
        return mapSpotifyRecentlyPlayedToActivities(history, profileId).take(limit)
    }

    private fun mapSpotifyRecentlyPlayedToActivities(
        history: List<SpotifyPlayHistoryObject>,
        profileId: String,
    ): List<ActivityEvent> {
        // First (newest) occurrence of each entity wins, and LinkedHashMap
        // preserves insertion order — which, because `history` is already
        // Spotify's newest-first order and each play offers album → artist →
        // song, IS the desired feed order. Deliberately NOT re-sorted by parsed
        // timestamp: a null/unparseable played_at (stamped `clock()` by the
        // fallback) would otherwise jump ahead of genuinely newer plays.
        val byEntity = LinkedHashMap<String, ActivityEvent>()
        fun offer(event: ActivityEvent) {
            byEntity.getOrPut("${event.entityType}:${event.entityId}") { event }
        }
        history.forEach { play ->
            val track = play.track ?: return@forEach
            val playedAt = parseSpotifyPlayedAt(play.playedAt)
            val album = track.album
            val artist = track.artists.firstOrNull()
            if (album != null) {
                offer(
                    ActivityEvent(
                        entityType = ActivityEntityType.ALBUM.name,
                        actionType = ActivityActionType.PLAYED.name,
                        entityId = album.id,
                        profileId = profileId,
                        provider = MediaId.PROVIDER_SPOTIFY,
                        title = album.name,
                        subtitle = album.artists.firstOrNull()?.name ?: artist?.name.orEmpty(),
                        coverArtId = album.images.firstOrNull()?.url,
                        albumId = album.id,
                        artistId = album.artists.firstOrNull()?.id,
                        timestamp = playedAt,
                    ),
                )
            }
            if (artist != null) {
                offer(
                    ActivityEvent(
                        entityType = ActivityEntityType.ARTIST.name,
                        actionType = ActivityActionType.PLAYED.name,
                        entityId = artist.id,
                        profileId = profileId,
                        provider = MediaId.PROVIDER_SPOTIFY,
                        title = artist.name,
                        subtitle = "Artist",
                        artistId = artist.id,
                        timestamp = playedAt,
                    ),
                )
            }
            val trackId = track.id
            if (trackId != null) {
                offer(
                    ActivityEvent(
                        entityType = ActivityEntityType.SONG.name,
                        actionType = ActivityActionType.PLAYED.name,
                        entityId = trackId,
                        profileId = profileId,
                        provider = MediaId.PROVIDER_SPOTIFY,
                        title = track.name,
                        subtitle = artist?.name.orEmpty(),
                        coverArtId = album?.images?.firstOrNull()?.url,
                        songId = trackId,
                        albumId = album?.id,
                        artistId = artist?.id,
                        timestamp = playedAt,
                    ),
                )
            }
        }
        return byEntity.values.toList()
    }

    private fun parseSpotifyPlayedAt(raw: String?): Long =
        raw?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: clock()

    suspend fun getRecentMemoryActivities(limit: Int = 48): List<ActivityEvent> {
        val provider = activeSource.value?.id ?: return emptyList()
        val profileId = activeProfileId.value ?: return emptyList()
        return database.activityEventDao()
            .getRecentEvents(profileId, provider, limit * 10)
            .first()
            .filter { it.actionType == ActivityActionType.PLAYED.name }
            .filter { it.entityType == ActivityEntityType.ALBUM.name }
            .let(::collapseToLatestUnique)
            .asSequence()
            .take(limit)
            .toList()
    }

    suspend fun getMostRecentPlay(trackId: MediaId): PlayHistory? =
        activeProfileId.value?.let { profileId ->
            database.playHistoryDao().getMostRecentPlay(trackId.rawId, trackId.provider, profileId)
        }

    /** Most-recent play across all of an album's tracks, as one grouped query. */
    suspend fun getAlbumLastPlayed(albumId: MediaId): Long? =
        activeProfileId.value?.let { profileId ->
            database.playHistoryDao().getAlbumLastPlayed(albumId.rawId, albumId.provider, profileId)
        }

    suspend fun recordAlbumVisit(album: Album) {
        val profileId = activeProfileId.value ?: return
        // Fire-and-forget telemetry — a failed insert (disk full, locked DB) must
        // never bubble into the caller's load try/catch and mask a loaded page.
        runCatching {
        database.activityEventDao().insert(
            ActivityEvent(
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.VISITED.name,
                entityId = album.id.rawId,
                profileId = profileId,
                provider = album.id.provider,
                title = album.name,
                subtitle = album.artist.orEmpty(),
                // Storage key encodes both provider flavours — URL for Spotify,
                // raw id for Subsonic. Fallback to the album's raw id only when
                // a SourceRelative artwork ref was missing (legacy Subsonic
                // path where cover id == album id).
                coverArtId = CoverRef.toStorageKey(album.coverArt)
                    ?: album.id.rawId.takeIf { album.id.provider == MediaId.PROVIDER_SUBSONIC },
                albumId = album.id.rawId,
                artistId = album.artistId?.rawId,
            ),
        )
        }
    }

    suspend fun recordArtistVisit(artist: ArtistDetail) {
        val profileId = activeProfileId.value ?: return
        runCatching {
        database.activityEventDao().insert(
            ActivityEvent(
                entityType = ActivityEntityType.ARTIST.name,
                actionType = ActivityActionType.VISITED.name,
                entityId = artist.id.rawId,
                profileId = profileId,
                provider = artist.id.provider,
                title = artist.name,
                subtitle = "Artist",
                coverArtId = CoverRef.toStorageKey(artist.coverArt),
                artistId = artist.id.rawId,
            ),
        )
        }
    }

    // ── URLs (provider-agnostic) ───────────────────────────────────────

    fun resolveCoverUrl(ref: CoverRef?, size: Int? = null): String? =
        ref?.let { activeSource.value?.resolveCoverUrl(it, size) }

    /** Convenience for legacy Subsonic cover-art-id callers that haven't
     *  migrated to [CoverRef] yet — wraps as [CoverRef.SourceRelative]. */
    fun resolveSubsonicCoverUrl(coverArtId: String?, size: Int? = null): String? =
        coverArtId?.let { resolveCoverUrl(CoverRef.SourceRelative(it), size) }

    // ── Server test ────────────────────────────────────────────────────

    suspend fun testConnection(): Boolean =
        requireSource().library().ping()

    // ── Internal helpers ───────────────────────────────────────────────

    private fun buildPlaybackActivity(
        track: Track,
        activityContext: ActivityContext,
        profileId: String,
    ): ActivityEvent {
        val trackProvider = track.id.provider
        return when (activityContext) {
            is ActivityContext.Album -> ActivityEvent(
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = activityContext.albumId,
                profileId = profileId,
                provider = trackProvider,
                title = activityContext.albumName,
                subtitle = activityContext.artistName.orEmpty()
                    .ifBlank { track.artist.orEmpty() },
                coverArtId = activityContext.coverArtId
                    ?: CoverRef.toStorageKey(track.coverArt)
                    ?: fallbackCoverKeyForSubsonic(trackProvider, track.albumId?.rawId),
                songId = track.id.rawId,
                albumId = activityContext.albumId,
                artistId = activityContext.artistId ?: track.artistId?.rawId,
            )

            is ActivityContext.Artist -> ActivityEvent(
                entityType = ActivityEntityType.ARTIST.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = activityContext.artistId,
                profileId = profileId,
                provider = trackProvider,
                title = activityContext.artistName,
                subtitle = "Artist",
                coverArtId = activityContext.coverArtId
                    ?: CoverRef.toStorageKey(track.coverArt),
                songId = track.id.rawId,
                albumId = track.albumId?.rawId,
                artistId = activityContext.artistId,
            )

            is ActivityContext.Playlist -> ActivityEvent(
                entityType = ActivityEntityType.PLAYLIST.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = activityContext.playlistId,
                profileId = profileId,
                provider = trackProvider,
                title = activityContext.playlistName,
                subtitle = activityContext.owner.orEmpty().ifBlank { "Playlist" },
                coverArtId = activityContext.coverArtId
                    ?: CoverRef.toStorageKey(track.coverArt)
                    ?: fallbackCoverKeyForSubsonic(trackProvider, track.albumId?.rawId),
                songId = track.id.rawId,
                albumId = track.albumId?.rawId,
                artistId = track.artistId?.rawId,
            )

            is ActivityContext.LikedSongs,
            ActivityContext.None -> ActivityEvent(
                entityType = ActivityEntityType.SONG.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = track.id.rawId,
                profileId = profileId,
                provider = trackProvider,
                title = track.title.orEmpty(),
                subtitle = track.artist.orEmpty(),
                coverArtId = CoverRef.toStorageKey(track.coverArt)
                    ?: fallbackCoverKeyForSubsonic(trackProvider, track.albumId?.rawId),
                songId = track.id.rawId,
                albumId = track.albumId?.rawId,
                artistId = track.artistId?.rawId,
            )
        }
    }

    /**
     * Last-resort fallback for activity rows whose track ships without any
     * cover-art ref. On Subsonic the album id is usable as a cover-art id
     * (they're the same namespace on most servers), but on Spotify the
     * album id is a bare Spotify id with no URL shape — using it would
     * poison the storage key, so we return null instead and let the UI
     * render a placeholder.
     */
    private fun fallbackCoverKeyForSubsonic(provider: String, albumRawId: String?): String? =
        albumRawId?.takeIf { provider == MediaId.PROVIDER_SUBSONIC }

private fun collapseToLatestUnique(events: List<ActivityEvent>): List<ActivityEvent> {
        val seenKeys = mutableSetOf<String>()
        val collapsed = mutableListOf<ActivityEvent>()
        events.forEach { event ->
            val stableKey = "${event.actionType}:${event.entityType}:${event.entityId}:${event.provider}"
            if (seenKeys.add(stableKey)) {
                collapsed += event
            }
        }
        return collapsed
    }

    companion object {
        /** 歌词缓存 TTL：30 天。Provider 返回的内容在这个窗口内复用不重拉。 */
        private val LYRICS_CACHE_TTL_MS: Long = 30L * 24L * 60L * 60L * 1000L
    }
}

private val lyricTranslationCacheJson = Json

private const val PROVIDER_QQ = "qq"
private const val PROVIDER_NETEASE = "netease"
private val PROVIDER_TRANSLATION_NAMES = setOf(PROVIDER_QQ, PROVIDER_NETEASE)

private data class ProviderTranslation(
    val providerName: String,
    val providerSongId: String,
    val lyrics: Lyrics,
    val rawLrc: String,
    val translations: Map<Int, String>,
)

private fun LyricsTranslationCache.toTranslations(expectedLineCount: Int): Map<Int, String>? =
    runCatching {
        lyricTranslationCacheJson.decodeFromString<List<String>>(translationsJson)
    }.getOrNull()
        ?.takeIf { it.size == expectedLineCount }
        ?.mapIndexedNotNull { index, translation ->
            GeminiService.cleanLineTranslation(translation)
                .takeIf(String::isNotBlank)
                ?.let { index to it }
        }
        ?.toMap()

private fun buildProviderTranslations(
    lyrics: Lyrics,
    translatedLrc: String,
): Map<Int, String> {
    val translated = LrcParser.parse(translatedLrc)
    if (lyrics is Lyrics.Synced && translated is Lyrics.Synced) {
        val byStart = translated.lines
            .mapNotNull { line -> line.text.takeIf(String::isNotBlank)?.let { line.startMs to it } }
            .toMap()
        val exact = lyrics.lines.mapIndexedNotNull { index, line ->
            byStart[line.startMs]
                ?.let(GeminiService::cleanLineTranslation)
                ?.takeIf(String::isNotBlank)
                ?.let { index to it }
        }.toMap()
        if (exact.isNotEmpty()) return exact
    }
    return lyrics.lineTexts()
        .zip(translated.lineTexts())
        .mapIndexedNotNull { index, pair ->
            GeminiService.cleanLineTranslation(pair.second)
                .takeIf(String::isNotBlank)
                ?.let { index to it }
        }
        .toMap()
}

private fun Lyrics.lineTexts(): List<String> = when (this) {
    is Lyrics.Synced -> lines.map(LyricLine::text)
    is Lyrics.Unsynced -> text.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()
}

private fun String.isChineseTargetLanguage(): Boolean {
    val normalized = trim().lowercase()
    return normalized == "chinese" ||
        "中文" in normalized ||
        "汉语" in normalized ||
        "simplified chinese" in normalized ||
        "traditional chinese" in normalized ||
        "chinese (" in normalized
}

private fun List<String>.appearToAlreadyBeTargetLanguage(targetLanguage: String): Boolean =
    targetLanguage.isChineseTargetLanguage() && appearsMostlyChinese()

private fun List<String>.appearsMostlyChinese(): Boolean {
    var han = 0
    var kana = 0
    var hangul = 0
    var latin = 0
    for (line in this) {
        for (char in line) {
            when {
                char.isHanIdeograph() -> han += 1
                char.isJapaneseKana() -> kana += 1
                char.isHangul() -> hangul += 1
                char.isLatinLetter() -> latin += 1
            }
        }
    }
    val meaningful = han + kana + hangul + latin
    if (han < 8 || meaningful < 12) return false
    if (kana > 0 || hangul > 0) return false
    val hanRatio = han.toFloat() / meaningful.toFloat()
    return hanRatio >= 0.72f || (han >= 20 && hanRatio >= 0.60f)
}

// Classify by fixed Unicode code-point ranges rather than Character.UnicodeBlock:
// the Extension E–H block constants require API 34/36 and crash with
// NoSuchFieldError below minSdk (26). The ranges below are immutable Unicode block
// boundaries, so this is equivalent to the previous UnicodeBlock comparisons while
// staying API-safe. (A single UTF-16 Char never reaches the supplementary-plane
// blocks > U+FFFF, exactly as before — the caller iterates Char-by-Char.)
private fun Char.isHanIdeograph(): Boolean = when (code) {
    in 0x4E00..0x9FFF, // CJK Unified Ideographs
    in 0x3400..0x4DBF, // Extension A
    in 0x20000..0x2A6DF, // Extension B
    in 0x2A700..0x2B73F, // Extension C
    in 0x2B740..0x2B81F, // Extension D
    in 0x2B820..0x2CEAF, // Extension E
    in 0x2CEB0..0x2EBEF, // Extension F
    in 0x30000..0x3134F, // Extension G
    in 0x31350..0x323AF, // Extension H
    in 0xF900..0xFAFF, // CJK Compatibility Ideographs
    in 0x2F800..0x2FA1F, // CJK Compatibility Ideographs Supplement
    -> true

    else -> false
}

private fun Char.isJapaneseKana(): Boolean {
    val block = Character.UnicodeBlock.of(this)
    return block == Character.UnicodeBlock.HIRAGANA ||
        block == Character.UnicodeBlock.KATAKANA ||
        block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
}

private fun Char.isHangul(): Boolean {
    val block = Character.UnicodeBlock.of(this)
    return block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
        block == Character.UnicodeBlock.HANGUL_JAMO ||
        block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO ||
        block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_A ||
        block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_B
}

private fun Char.isLatinLetter(): Boolean =
    (this in 'A'..'Z') || (this in 'a'..'z')

private fun buildLyricsTranslationSourceHash(
    title: String,
    artist: String,
    lines: List<String>,
): String = sha256Hex(
    buildString {
        append(title).append('\u001F')
        append(artist).append('\u001F')
        lines.forEach { line ->
            append(line.length).append(':').append(line).append('\u001E')
        }
    },
)

private fun sha256Hex(text: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
    return bytes.joinToString(separator = "") { byte ->
        ((byte.toInt() and 0xff) + 0x100).toString(16).substring(1)
    }
}

private fun List<SongNoteKey>.toMediaIdSet(): Set<MediaId> =
    mapTo(linkedSetOf()) { key -> MediaId(key.provider, key.trackId) }

private fun List<AlbumNoteKey>.toAlbumMediaIdSet(): Set<MediaId> =
    mapTo(linkedSetOf()) { key -> MediaId(key.provider, key.albumId) }

private fun Album.toSpotifyHomeAlbumCache(
    profileId: String,
    sortOrder: Int,
    cachedAt: Long,
): SpotifyHomeAlbumCache = SpotifyHomeAlbumCache(
    profileId = profileId,
    albumId = id.toString(),
    name = name,
    artist = artist,
    artistId = artistId?.toString(),
    coverArtKey = CoverRef.toStorageKey(coverArt),
    songCount = songCount,
    year = year,
    sortOrder = sortOrder,
    cachedAt = cachedAt,
)

private fun SpotifyHomeAlbumCache.toAlbum(): Album = Album(
    id = MediaId.parse(albumId),
    name = name,
    artist = artist,
    artistId = artistId?.let(MediaId::parse),
    coverArt = CoverRef.fromStorageKey(coverArtKey),
    songCount = songCount,
    durationSec = null,
    year = year,
    genre = null,
    tracks = emptyList(),
)

private fun com.gpo.yoin.data.model.Artist.toSpotifyHomeArtistCache(
    profileId: String,
    sortOrder: Int,
    cachedAt: Long,
): SpotifyHomeArtistCache = SpotifyHomeArtistCache(
    profileId = profileId,
    artistId = id.toString(),
    name = name,
    coverArtKey = CoverRef.toStorageKey(coverArt),
    sortOrder = sortOrder,
    cachedAt = cachedAt,
)

private fun SpotifyHomeArtistCache.toArtist(): com.gpo.yoin.data.model.Artist =
    com.gpo.yoin.data.model.Artist(
        id = MediaId.parse(artistId),
        name = name,
        albumCount = null,
        coverArt = CoverRef.fromStorageKey(coverArtKey),
        isStarred = false,
    )
