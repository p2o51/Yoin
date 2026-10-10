package com.gpo.yoin.data.source.spotify

import androidx.room.withTransaction
import com.gpo.yoin.data.local.SpotifyLibraryAlbumCache
import com.gpo.yoin.data.local.SpotifyLibraryArtistCache
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
import kotlinx.coroutines.flow.Flow
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

    // Album saves and artist follows written into the mirror through Yoin.
    // A sync rewrites each table whole from lists it read before such a
    // write (or too soon after it: Spotify's library writes are eventually
    // consistent), so it lays them back over its rewrite until a list read
    // after them agrees, or their grace is over — the tracks'
    // pendingFavoriteAction, in memory since these tables have no column.
    private val pendingAlbums = PendingMirrorWrites<SpotifyLibraryAlbumCache>(clock)
    private val pendingArtists = PendingMirrorWrites<SpotifyLibraryArtistCache>(clock)

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

    /** Whether the saved-albums mirror (the newest 200 saved albums) holds [albumId] as saved. */
    suspend fun isAlbumCachedAsSaved(profileId: String, albumId: String): Boolean =
        dao.getAlbum(profileId, albumId)?.isSaved == true

    /** [albumId]'s saved-albums mirror row, live; null while the mirror doesn't hold it. */
    fun observeAlbum(profileId: String, albumId: String): Flow<SpotifyLibraryAlbumCache?> =
        dao.observeAlbum(profileId, albumId)

    /**
     * An album save or removal written through Yoin, laid into the mirror at
     * once rather than marking the whole library stale (a re-sync pages all
     * four lists): a save files [album] at the top of Recently added
     * ([addedAt], Spotify's own format) unless the mirror already had it; a
     * removal drops its row. The next sync re-reads the list as usual, and
     * keeps this write over it while its list may not show it yet.
     */
    suspend fun recordAlbumSaved(profileId: String, album: Album, saved: Boolean, addedAt: String) {
        val albumId = album.id.rawId
        if (!saved) {
            pendingAlbums.record(profileId, albumId, state = false, row = null)
            dao.deleteAlbum(profileId, albumId)
            return
        }
        val existing = dao.getAlbum(profileId, albumId)
        val row = existing?.copy(isSaved = true, cachedAt = clock())
            ?: album.copy(isStarred = true).toSpotifyLibraryAlbumCache(profileId, clock(), addedAt)
        pendingAlbums.record(profileId, albumId, state = true, row = row)
        dao.upsertAlbum(row)
    }

    /**
     * An artist follow or unfollow about to be written through Yoin, before
     * its row goes into the mirror ([row]: the row filed for a follow, null
     * when none is): from now on a sync keeps it over its rewrite, as an
     * album save. Returns the ticket [forgetArtistFollow] takes.
     */
    fun holdArtistFollow(
        profileId: String,
        artistId: String,
        followed: Boolean,
        row: SpotifyLibraryArtistCache?
    ): Long = pendingArtists.record(profileId, artistId, state = followed, row = row)

    /** The follow write [ticket] failed: no sync keeps it any more (call before putting the row back). */
    fun forgetArtistFollow(profileId: String, artistId: String, ticket: Long) {
        pendingArtists.forget(profileId, artistId, ticket)
    }

    private suspend fun syncFromRemote(profileId: String, source: MusicSource, force: Boolean) {
        val spotifySource = source as? SpotifyMusicSource
        // Invalidate the source's in-memory caches so the sync pulls fresh
        // network data — EXCEPT on the very first cold sync (no prior meta),
        // where the caches were just warmed by prime() / a launch warm-up and
        // re-fetching would only slow the first Home paint. Forced refreshes and
        // TTL re-syncs always invalidate, and so does a first sync after a like
        // written since that warm-up: the starred tracks stored below come from
        // the saved-tracks list, and the warm one predates the like.
        //
        // A TTL re-sync joins a list read that started moments ago (an album
        // open's saved-tracks read on the same cold start) instead of paging
        // the list a second time beside it. Not while a like is unsettled —
        // that read may predate it — and not on a forced refresh.
        // Taken before any list is read: writes after these are newer than the lists.
        val albumsCheckpoint = pendingAlbums.checkpoint()
        val artistsCheckpoint = pendingArtists.checkpoint()
        // The earliest a list this sync reads itself can be from.
        val syncStartedAtMs = clock()
        val isColdFirstSync = dao.getSyncMeta(profileId) == null
        val unsettledLikes = spotifySource?.hasUnsettledFavoriteWrites() == true
        if (force || !isColdFirstSync || unsettledLikes) {
            spotifySource?.invalidateLibraryCaches(keepRecentLoads = !force && !unsettledLikes)
        }
        // Warm the four independent library resources concurrently before the
        // derived reads below (which share those caches and would otherwise
        // serialise four round-trips). Already-warm caches return instantly.
        val readTimes = spotifySource?.warmLibraryCaches()
        val library = source.library()
        val now = clock()

        // Each mirror row is dated by when its list was read, not when the
        // sync wrote it: a list prime() read before a cold start's first
        // sync, or a read already out that a re-sync joined, is older than
        // now, and an answer about a track or album Yoin got in between (App
        // Remote, contains — FavoriteStateOverlay, on the same clock) must
        // outrank it (resolveLearnedFavoriteState). Not known: the sync's
        // start, the earliest this sync's own reads can be from.
        fun readAt(readStartedAtMs: Long?): Long =
            readStartedAtMs?.takeIf { it > 0L }?.coerceAtMost(now) ?: syncStartedAtMs

        val tracksReadAtMs = readAt(readTimes?.savedTracksMs)
        val albumsReadAtMs = readAt(readTimes?.savedAlbumsMs)
        val playlistsReadAtMs = readAt(readTimes?.playlistsMs)
        val artistsReadAtMs = readAt(readTimes?.followedArtistsMs)
        val artistIndices = library.getArtists()
        val artists = artistIndices.flatMap { index -> index.artists }.distinctBy { artist -> artist.id }
        val albums = library.getAlbumList(type = "alphabeticalByName", size = Int.MAX_VALUE)
            .distinctBy { album -> album.id }
        val playlists = library.getPlaylists()
        val starred = library.getStarred()
        val listedAlbumIds = albums.mapTo(HashSet()) { album -> album.id.rawId }
        val listedArtists = artists.associateBy { artist -> artist.id.rawId }

        database.withTransaction {
            val pendingTracks = dao.getPendingTracks(profileId)

            dao.deleteTracksForProfile(profileId)
            dao.deleteAlbumsForProfile(profileId)
            dao.deleteArtistsForProfile(profileId)
            dao.deletePlaylistsForProfile(profileId)

            dao.insertArtists(artists.map { artist -> artist.toSpotifyLibraryArtistCache(profileId, artistsReadAtMs) })
            dao.insertAlbums(albums.map { album -> album.toSpotifyLibraryAlbumCache(profileId, albumsReadAtMs) })
            // Yoin's own writes the lists may not show yet, back over them.
            pendingAlbums.toLayBack(profileId, albumsCheckpoint).forEach { (albumId, write) ->
                val listed = albumId in listedAlbumIds
                when {
                    write.state && !listed -> write.row?.let { row -> dao.upsertAlbum(row) }
                    !write.state && listed -> dao.deleteAlbum(profileId, albumId)
                }
            }
            pendingArtists.toLayBack(profileId, artistsCheckpoint).forEach { (artistId, write) ->
                val listed = listedArtists[artistId]
                when {
                    listed != null && listed.isStarred != write.state -> dao.upsertArtist(
                        listed.toSpotifyLibraryArtistCache(profileId, artistsReadAtMs).copy(isFollowed = write.state)
                    )
                    listed == null && write.state -> write.row?.let { row -> dao.upsertArtist(row) }
                }
            }
            dao.insertPlaylists(
                playlists
                    .distinctBy { playlist -> playlist.id }
                    .map { playlist -> playlist.toSpotifyLibraryPlaylistCache(profileId, playlistsReadAtMs) },
            )
            dao.insertTracks(
                starred.tracks
                    .distinctBy { track -> track.id }
                    .map { track -> track.toSpotifyLibraryTrackCache(profileId, tracksReadAtMs) },
            )
            if (pendingTracks.isNotEmpty()) {
                val freshByTrackId = starred.tracks
                    .distinctBy { track -> track.id }
                    .associateBy { track -> track.id.rawId }
                val mergedPending = pendingTracks.map { pending ->
                    val fresh = freshByTrackId[pending.trackId]
                    if (fresh != null) {
                        fresh.toSpotifyLibraryTrackCache(profileId, tracksReadAtMs).copy(
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
        // The writes these lists show — or that Spotify had long enough to — are settled.
        pendingAlbums.reconcile(profileId, albumsCheckpoint) { albumId -> albumId in listedAlbumIds }
        pendingArtists.reconcile(profileId, artistsCheckpoint) { artistId ->
            listedArtists[artistId]?.isStarred == true
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

        /** How long a sync keeps a write through Yoin over lists that don't show it ([SavedTrackDelta]'s grace). */
        const val PENDING_WRITE_GRACE_MS = SavedTrackDelta.DEFAULT_GRACE_MS
    }
}

/**
 * Writes through Yoin to one mirror table, per profile and raw id, as the
 * mirror took them ([Write.state]: saved / followed; [Write.row]: the row
 * filed, if any), in order. A sync lays back the ones its lists can't be
 * trusted to show — written after its [checkpoint], or within the grace
 * — and settles the ones a list read after them agrees with, or whose
 * grace is over (then the lists are the truth).
 */
private class PendingMirrorWrites<R>(
    private val clock: () -> Long,
    private val graceMs: Long = SpotifyLibrarySyncCoordinator.PENDING_WRITE_GRACE_MS
) {
    class Write<R>(val state: Boolean, val row: R?, val sequence: Long, val writtenAtMs: Long)

    private val lock = Any()
    private val entries = HashMap<Pair<String, String>, Write<R>>()
    private var sequence = 0L

    /** Records a write; returns its sequence, its ticket for [forget]. */
    fun record(profileId: String, id: String, state: Boolean, row: R?): Long = synchronized(lock) {
        val ticket = ++sequence
        entries[profileId to id] = Write(state, row, ticket, clock())
        ticket
    }

    /** Drops the write [ticket] (a write since then stays). */
    fun forget(profileId: String, id: String, ticket: Long) {
        synchronized(lock) {
            if (entries[profileId to id]?.sequence == ticket) entries.remove(profileId to id)
        }
    }

    /** Taken before a sync reads its lists: writes up to it are older than the lists. */
    fun checkpoint(): Long = synchronized(lock) { sequence }

    /** [profileId]'s writes, by id, that lists read at [checkpoint] may not show. */
    fun toLayBack(profileId: String, checkpoint: Long): List<Pair<String, Write<R>>> = synchronized(lock) {
        val now = clock()
        entries.mapNotNull { (key, write) ->
            val (writeProfileId, id) = key
            val unseen = write.sequence > checkpoint || now - write.writtenAtMs < graceMs
            (id to write).takeIf { writeProfileId == profileId && unseen }
        }
    }

    /** Settles [profileId]'s writes older than [checkpoint] that [listed] agrees with or whose grace is over. */
    fun reconcile(profileId: String, checkpoint: Long, listed: (id: String) -> Boolean) {
        synchronized(lock) {
            val now = clock()
            entries.entries.removeAll { (key, write) ->
                key.first == profileId &&
                    write.sequence <= checkpoint &&
                    (listed(key.second) == write.state || now - write.writtenAtMs >= graceMs)
            }
        }
    }
}
