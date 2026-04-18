package com.gpo.yoin.data.repository

import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.Lyrics
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.MusicSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    private val database: YoinDatabase,
) {

    /** True when a configured profile is currently active. */
    val isConfigured: Boolean
        get() = activeSource.value != null

    private fun requireSource(): MusicSource = activeSource.value
        ?: throw SubsonicException(
            code = -1,
            message = "No profile configured. Open Settings to add one.",
        )

    // ── Albums ─────────────────────────────────────────────────────────

    suspend fun getAlbumList(type: String, size: Int = 20, offset: Int = 0): List<Album> =
        requireSource().library().getAlbumList(type, size, offset)

    suspend fun getAlbum(id: MediaId): Album? =
        requireSource().library().getAlbum(id)

    // ── Artists ────────────────────────────────────────────────────────

    suspend fun getArtists(): List<ArtistIndex> =
        requireSource().library().getArtists()

    suspend fun getArtist(id: MediaId): ArtistDetail? =
        requireSource().library().getArtist(id)

    // ── Search ─────────────────────────────────────────────────────────

    suspend fun search(query: String): SearchResults =
        requireSource().library().search(query)

    // ── Favorites ──────────────────────────────────────────────────────

    suspend fun setFavorite(id: MediaId, favorite: Boolean): Result<Unit> =
        requireSource().writeActions().setFavorite(id, favorite)

    suspend fun getStarred(): Starred =
        requireSource().library().getStarred()

    // ── Random ─────────────────────────────────────────────────────────

    suspend fun getRandomSongs(size: Int = 20): List<Track> =
        requireSource().library().getRandomSongs(size)

    // ── Playlists ──────────────────────────────────────────────────────

    suspend fun getPlaylists(): List<Playlist> =
        requireSource().library().getPlaylists()

    suspend fun getPlaylist(id: MediaId): Playlist? =
        requireSource().library().getPlaylist(id)

    // ── Rating (local-first, best-effort server sync) ──────────────────

    suspend fun setRating(trackId: MediaId, rating: Float) {
        val serverRating = rating.roundToInt().coerceIn(0, 5)
        val pending = LocalRating(
            songId = trackId.rawId,
            provider = trackId.provider,
            rating = rating,
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
        database.localRatingDao().getRating(trackId.rawId, trackId.provider)

    suspend fun getRatings(trackIds: Collection<MediaId>): Map<MediaId, LocalRating> {
        if (trackIds.isEmpty()) return emptyMap()
        return trackIds.asSequence()
            .distinct()
            .groupBy { it.provider }
            .flatMap { (provider, ids) ->
                database.localRatingDao().getRatings(ids.map { it.rawId }, provider)
            }
            .associateBy { MediaId(it.provider, it.songId) }
    }

    suspend fun syncPendingRatings() {
        val source = activeSource.value ?: return
        val pending = database.localRatingDao().getRatingsNeedingSync().first()
        for (rating in pending) {
            if (rating.provider != source.id) continue
            val trackId = MediaId(rating.provider, rating.songId)
            source.writeActions().setRating(trackId, rating.serverRating).onSuccess {
                database.localRatingDao().upsert(rating.copy(needsSync = false))
            }
        }
    }

    // ── Lyrics ─────────────────────────────────────────────────────────

    suspend fun getLyrics(trackId: MediaId): Lyrics? =
        requireSource().metadata().getLyrics(trackId)

    // ── Play history / activity ────────────────────────────────────────

    suspend fun recordPlay(
        track: Track,
        durationMs: Long,
        completedPercent: Float,
        activityContext: ActivityContext = ActivityContext.None,
    ) {
        database.playHistoryDao().insert(
            PlayHistory(
                songId = track.id.rawId,
                provider = track.id.provider,
                title = track.title.orEmpty(),
                artist = track.artist.orEmpty(),
                album = track.album.orEmpty(),
                albumId = track.albumId?.rawId.orEmpty(),
                coverArtId = (track.coverArt as? CoverRef.SourceRelative)?.coverArtId,
                durationMs = durationMs,
                completedPercent = completedPercent,
            ),
        )
        database.activityEventDao().insert(buildPlaybackActivity(track, activityContext))
    }

    fun getRecentHistory(limit: Int = 50): Flow<List<PlayHistory>> =
        activeSource.flatMapLatest { source ->
            val provider = source?.id ?: return@flatMapLatest flowOf(emptyList())
            database.playHistoryDao().getRecentHistory(provider, limit)
        }

    fun getRecentActivities(limit: Int = 20): Flow<List<ActivityEvent>> =
        activeSource.flatMapLatest { source ->
            val provider = source?.id ?: return@flatMapLatest flowOf(emptyList())
            database.activityEventDao().getRecentEvents(provider, limit * 8)
        }
            .map { events -> collapseToLatestUnique(events).take(limit) }

    suspend fun getRecentMemoryActivities(limit: Int = 48): List<ActivityEvent> {
        val provider = activeSource.value?.id ?: return emptyList()
        return database.activityEventDao()
            .getRecentEvents(provider, limit * 10)
            .first()
            .filter { it.actionType == ActivityActionType.PLAYED.name }
            .filter {
                it.entityType == ActivityEntityType.SONG.name ||
                    it.entityType == ActivityEntityType.ALBUM.name ||
                    it.entityType == ActivityEntityType.PLAYLIST.name
            }
            .let(::collapseToLatestUnique)
            .asSequence()
            .take(limit)
            .toList()
    }

    suspend fun getMostRecentPlay(trackId: MediaId): PlayHistory? =
        database.playHistoryDao().getMostRecentPlay(trackId.rawId, trackId.provider)

    suspend fun recordAlbumVisit(album: Album) {
        database.activityEventDao().insert(
            ActivityEvent(
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.VISITED.name,
                entityId = album.id.rawId,
                provider = album.id.provider,
                title = album.name,
                subtitle = album.artist.orEmpty(),
                coverArtId = (album.coverArt as? CoverRef.SourceRelative)?.coverArtId
                    ?: album.id.rawId,
                albumId = album.id.rawId,
                artistId = album.artistId?.rawId,
            ),
        )
    }

    suspend fun recordArtistVisit(artist: ArtistDetail) {
        database.activityEventDao().insert(
            ActivityEvent(
                entityType = ActivityEntityType.ARTIST.name,
                actionType = ActivityActionType.VISITED.name,
                entityId = artist.id.rawId,
                provider = artist.id.provider,
                title = artist.name,
                subtitle = "Artist",
                coverArtId = (artist.coverArt as? CoverRef.SourceRelative)?.coverArtId,
                artistId = artist.id.rawId,
            ),
        )
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
    ): ActivityEvent {
        val trackProvider = track.id.provider
        return when (activityContext) {
            is ActivityContext.Album -> ActivityEvent(
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = activityContext.albumId,
                provider = trackProvider,
                title = activityContext.albumName,
                subtitle = activityContext.artistName.orEmpty()
                    .ifBlank { track.artist.orEmpty() },
                coverArtId = activityContext.coverArtId
                    ?: (track.coverArt as? CoverRef.SourceRelative)?.coverArtId
                    ?: track.albumId?.rawId,
                songId = track.id.rawId,
                albumId = activityContext.albumId,
                artistId = activityContext.artistId ?: track.artistId?.rawId,
            )

            is ActivityContext.Artist -> ActivityEvent(
                entityType = ActivityEntityType.ARTIST.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = activityContext.artistId,
                provider = trackProvider,
                title = activityContext.artistName,
                subtitle = "Artist",
                coverArtId = activityContext.coverArtId
                    ?: (track.coverArt as? CoverRef.SourceRelative)?.coverArtId,
                songId = track.id.rawId,
                albumId = track.albumId?.rawId,
                artistId = activityContext.artistId,
            )

            is ActivityContext.Playlist -> ActivityEvent(
                entityType = ActivityEntityType.PLAYLIST.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = activityContext.playlistId,
                provider = trackProvider,
                title = activityContext.playlistName,
                subtitle = activityContext.owner.orEmpty().ifBlank { "Playlist" },
                coverArtId = activityContext.coverArtId
                    ?: (track.coverArt as? CoverRef.SourceRelative)?.coverArtId
                    ?: track.albumId?.rawId,
                songId = track.id.rawId,
                albumId = track.albumId?.rawId,
                artistId = track.artistId?.rawId,
            )

            ActivityContext.None -> ActivityEvent(
                entityType = ActivityEntityType.SONG.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = track.id.rawId,
                provider = trackProvider,
                title = track.title.orEmpty(),
                subtitle = track.artist.orEmpty(),
                coverArtId = (track.coverArt as? CoverRef.SourceRelative)?.coverArtId
                    ?: track.albumId?.rawId,
                songId = track.id.rawId,
                albumId = track.albumId?.rawId,
                artistId = track.artistId?.rawId,
            )
        }
    }

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
}
