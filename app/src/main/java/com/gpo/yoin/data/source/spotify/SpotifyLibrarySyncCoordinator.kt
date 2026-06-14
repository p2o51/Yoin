package com.gpo.yoin.data.source.spotify

import androidx.room.withTransaction
import com.gpo.yoin.data.local.SpotifyLibraryCacheDao
import com.gpo.yoin.data.local.SpotifyLibrarySyncMeta
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.MusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-profile single-flight Spotify library sync with TTL stale-while-revalidate
 * and 429 backoff. All library reads for Spotify should flow through here.
 */
class SpotifyLibrarySyncCoordinator(
    private val database: YoinDatabase,
    private val rateLimitGate: SpotifyRateLimitGate,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<Result<Unit>>>()

    private val dao: SpotifyLibraryCacheDao
        get() = database.spotifyLibraryCacheDao()

    init {
        scope.launch(Dispatchers.IO) {
            runCatching {
                dao.getAllSyncMeta().forEach { meta ->
                    val remainingMs = meta.backoffUntilMs - clock()
                    if (remainingMs > 0L) {
                        val retryAfterSeconds = (remainingMs / 1_000L).coerceAtLeast(1L)
                        rateLimitGate.recordBackoff(meta.profileId, retryAfterSeconds)
                    }
                }
            }
        }
    }

    suspend fun isCacheFresh(profileId: String, maxAgeMs: Long = DEFAULT_TTL_MS): Boolean {
        val meta = dao.getSyncMeta(profileId) ?: return false
        return meta.cachedAt > 0 && clock() - meta.cachedAt <= maxAgeMs
    }

    suspend fun hasAnyCachedData(profileId: String): Boolean {
        val minCachedAt = 0L
        return dao.getFreshTracks(profileId, minCachedAt).isNotEmpty() ||
            dao.getFreshAlbums(profileId, minCachedAt).isNotEmpty() ||
            dao.getFreshArtists(profileId, minCachedAt).isNotEmpty() ||
            dao.getFreshPlaylists(profileId, minCachedAt).isNotEmpty()
    }

    suspend fun markStale(profileId: String) {
        val existing = dao.getSyncMeta(profileId)
        dao.upsertSyncMeta(
            existing?.copy(cachedAt = 0L)
                ?: SpotifyLibrarySyncMeta(profileId = profileId, cachedAt = 0L),
        )
    }

    suspend fun refreshLibrary(
        profileId: String,
        source: MusicSource,
        force: Boolean = false,
    ): Result<Unit> {
        if (source.id != com.gpo.yoin.data.model.MediaId.PROVIDER_SPOTIFY) {
            return Result.failure(IllegalStateException("Not a Spotify source"))
        }
        if (isRateLimited(profileId)) {
            return if (hasAnyCachedData(profileId)) {
                Result.success(Unit)
            } else {
                Result.failure(
                    SpotifyRateLimitException(
                        retryAfterSeconds = backoffRemainingSeconds(profileId),
                        endpoint = "library-sync",
                    ),
                )
            }
        }
        if (!force && isCacheFresh(profileId)) {
            return Result.success(Unit)
        }

        return mutex.withLock {
            val existing = inFlight[profileId]
            if (existing != null && existing.isActive) {
                existing
            } else {
                val job = scope.async(Dispatchers.IO) {
                    runCatching {
                        syncFromRemote(profileId, source, force)
                    }.fold(
                        onSuccess = { Result.success(Unit) },
                        onFailure = { error ->
                            handleSyncFailure(profileId, error)
                        },
                    )
                }
                inFlight[profileId] = job
                job
            }
        }.await()
    }

    suspend fun readArtists(profileId: String): List<Artist> {
        val minCachedAt = 0L
        return dao.getFreshArtists(profileId, minCachedAt).map { it.toArtist() }
    }

    suspend fun readAlbums(profileId: String): List<Album> {
        val minCachedAt = 0L
        return dao.getFreshAlbums(profileId, minCachedAt).map { it.toAlbum() }
    }

    suspend fun readPlaylists(profileId: String): List<Playlist> {
        val minCachedAt = 0L
        return dao.getFreshPlaylists(profileId, minCachedAt).map { it.toPlaylist() }
    }

    suspend fun readTracks(profileId: String): List<Track> {
        val minCachedAt = 0L
        return dao.getFreshTracks(profileId, minCachedAt)
            .filter { it.isSaved }
            .map { it.toTrack() }
    }

    suspend fun readStarred(profileId: String): Starred {
        val minCachedAt = 0L
        return buildStarredFromCache(
            tracks = dao.getFreshTracks(profileId, minCachedAt),
            albums = dao.getFreshAlbums(profileId, minCachedAt),
            artists = dao.getFreshArtists(profileId, minCachedAt),
        )
    }

    suspend fun readLocalSearchSnapshot(profileId: String): SpotifyLocalSearchSnapshot {
        val minCachedAt = 0L
        return SpotifyLocalSearchSnapshot(
            artists = dao.getFreshArtists(profileId, minCachedAt).map { it.toArtist() },
            albums = dao.getFreshAlbums(profileId, minCachedAt).map { it.toAlbum() },
            tracks = dao.getFreshTracks(profileId, minCachedAt)
                .filter { it.isSaved }
                .map { it.toTrack() },
            playlists = dao.getFreshPlaylists(profileId, minCachedAt).map { it.toPlaylist() },
            starred = buildStarredFromCache(
                tracks = dao.getFreshTracks(profileId, minCachedAt),
                albums = dao.getFreshAlbums(profileId, minCachedAt),
                artists = dao.getFreshArtists(profileId, minCachedAt),
            ),
        )
    }

    private suspend fun syncFromRemote(profileId: String, source: MusicSource, force: Boolean) {
        val spotifySource = source as? SpotifyMusicSource
        // Invalidate the source's in-memory caches so the sync pulls fresh
        // network data — EXCEPT on the very first cold sync (no prior meta),
        // where the caches were just warmed by prime() / a launch warm-up and
        // re-fetching would only slow the first Home paint. Forced refreshes and
        // TTL re-syncs always invalidate.
        val isColdFirstSync = dao.getSyncMeta(profileId) == null
        if (force || !isColdFirstSync) {
            spotifySource?.invalidateLibraryCaches()
        }
        // Warm the four independent library resources concurrently before the
        // derived reads below (which share those caches and would otherwise
        // serialise four round-trips). Already-warm caches return instantly.
        spotifySource?.warmLibraryCaches()
        val library = source.library()
        val now = clock()
        val artistIndices = library.getArtists()
        val artists = artistIndices.flatMap { index -> index.artists }
        val albums = library.getAlbumList(type = "alphabeticalByName", size = Int.MAX_VALUE)
        val playlists = library.getPlaylists()
        val starred = library.getStarred()

        database.withTransaction {
            val pendingTracks = dao.getPendingTracks(profileId)

            dao.deleteTracksForProfile(profileId)
            dao.deleteAlbumsForProfile(profileId)
            dao.deleteArtistsForProfile(profileId)
            dao.deletePlaylistsForProfile(profileId)

            dao.insertArtists(
                artists
                    .distinctBy { artist -> artist.id }
                    .map { artist -> artist.toSpotifyLibraryArtistCache(profileId, now) },
            )
            dao.insertAlbums(
                albums
                    .distinctBy { album -> album.id }
                    .map { album -> album.toSpotifyLibraryAlbumCache(profileId, now) },
            )
            dao.insertPlaylists(
                playlists
                    .distinctBy { playlist -> playlist.id }
                    .map { playlist -> playlist.toSpotifyLibraryPlaylistCache(profileId, now) },
            )
            dao.insertTracks(
                starred.tracks
                    .distinctBy { track -> track.id }
                    .map { track -> track.toSpotifyLibraryTrackCache(profileId, now) },
            )
            if (pendingTracks.isNotEmpty()) {
                val freshByTrackId = starred.tracks
                    .distinctBy { track -> track.id }
                    .associateBy { track -> track.id.rawId }
                val mergedPending = pendingTracks.map { pending ->
                    val fresh = freshByTrackId[pending.trackId]
                    if (fresh != null) {
                        fresh.toSpotifyLibraryTrackCache(profileId, now).copy(
                            isSaved = pending.isSaved,
                            pendingFavoriteAction = pending.pendingFavoriteAction,
                            lastSyncError = pending.lastSyncError,
                        )
                    } else {
                        pending
                    }
                }
                dao.insertTracks(mergedPending)
            }

            val existingMeta = dao.getSyncMeta(profileId)
            dao.upsertSyncMeta(
                SpotifyLibrarySyncMeta(
                    profileId = profileId,
                    cachedAt = now,
                    backoffUntilMs = 0L,
                    lastSyncError = null,
                ).let { fresh ->
                    existingMeta?.copy(
                        cachedAt = fresh.cachedAt,
                        backoffUntilMs = 0L,
                        lastSyncError = null,
                    ) ?: fresh
                },
            )
        }
        rateLimitGate.clear(profileId)
    }

    private suspend fun handleSyncFailure(profileId: String, error: Throwable): Result<Unit> {
        if (error is SpotifyRateLimitException) {
            rateLimitGate.recordBackoff(profileId, error.retryAfterSeconds)
            val until = clock() + error.retryAfterSeconds.coerceAtLeast(1L) * 1_000L
            val existing = dao.getSyncMeta(profileId)
            dao.upsertSyncMeta(
                (existing ?: SpotifyLibrarySyncMeta(profileId = profileId)).copy(
                    backoffUntilMs = until,
                    lastSyncError = error.message,
                ),
            )
            return if (hasAnyCachedData(profileId)) {
                Result.success(Unit)
            } else {
                Result.failure(error)
            }
        }
        val existing = dao.getSyncMeta(profileId)
        dao.upsertSyncMeta(
            (existing ?: SpotifyLibrarySyncMeta(profileId = profileId)).copy(
                lastSyncError = error.message,
            ),
        )
        return if (hasAnyCachedData(profileId)) {
            Result.success(Unit)
        } else {
            Result.failure(error)
        }
    }

    private suspend fun isRateLimited(profileId: String): Boolean {
        if (rateLimitGate.isBlocked(profileId)) return true
        val backoffUntilMs = dao.getSyncMeta(profileId)?.backoffUntilMs ?: return false
        return clock() < backoffUntilMs
    }

    private suspend fun backoffRemainingSeconds(profileId: String): Long {
        val gateRemaining = rateLimitGate.backoffRemainingMs(profileId)
        if (gateRemaining > 0L) {
            return (gateRemaining / 1_000L).coerceAtLeast(1L)
        }
        val dbRemaining = (dao.getSyncMeta(profileId)?.backoffUntilMs ?: 0L) - clock()
        return (dbRemaining / 1_000L).coerceAtLeast(1L)
    }

    data class SpotifyLocalSearchSnapshot(
        val artists: List<Artist>,
        val albums: List<Album>,
        val tracks: List<Track>,
        val playlists: List<Playlist>,
        val starred: Starred,
    )

    companion object {
        const val DEFAULT_TTL_MS = 60L * 60L * 1_000L
    }
}
