package com.gpo.yoin.data.repository

import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.remote.Album
import com.gpo.yoin.data.remote.ArtistDetail
import com.gpo.yoin.data.remote.ArtistIndex
import com.gpo.yoin.data.remote.LyricsList
import com.gpo.yoin.data.remote.Playlist
import com.gpo.yoin.data.remote.SearchResult
import com.gpo.yoin.data.remote.Song
import com.gpo.yoin.data.remote.StarredResponse
import com.gpo.yoin.data.remote.SubsonicApi
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.gpo.yoin.data.remote.SubsonicResponse
import com.gpo.yoin.data.remote.SubsonicResponseBody
import com.gpo.yoin.data.remote.ServerCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * Subsonic-shaped repository used by v1 ViewModels.
 *
 * The provider-agnostic [com.gpo.yoin.data.source.MusicSource] interface and
 * Profile system are already wired (see `AppContainer.profileManager`), but
 * the UI layer has not been migrated to consume neutral models yet. That is
 * phase 2 of the multi-provider refactor (see `docs/design.md` — Provider
 * abstraction). Internal DB calls tag all rows with
 * [MediaId.PROVIDER_SUBSONIC].
 */
class YoinRepository(
    private val apiProvider: () -> SubsonicApi,
    private val database: YoinDatabase,
    private val credentials: () -> ServerCredentials,
) {
    /** True when a server URL has been configured. */
    val isConfigured: Boolean
        get() = credentials().serverUrl.isNotBlank()

    private fun requireConfigured() {
        if (!isConfigured) {
            throw SubsonicException(
                code = -1,
                message = "No server configured. Go to Settings to add a server.",
            )
        }
    }

    private fun api(): SubsonicApi = apiProvider()

    // ── Albums ──────────────────────────────────────────────────────────

    suspend fun getAlbumList(type: String, size: Int = 20, offset: Int = 0): List<Album> {
        requireConfigured()
        val body = unwrap(api().getAlbumList2(type, size, offset))
        return body.albumList2?.album.orEmpty()
    }

    suspend fun getAlbum(id: String): Album? {
        requireConfigured()
        val body = unwrap(api().getAlbum(id))
        return body.album
    }

    // ── Artists ─────────────────────────────────────────────────────────

    suspend fun getArtists(): List<ArtistIndex> {
        requireConfigured()
        val body = unwrap(api().getArtists())
        return body.artists?.index.orEmpty()
    }

    suspend fun getArtist(id: String): ArtistDetail? {
        requireConfigured()
        val body = unwrap(api().getArtist(id))
        return body.artist
    }

    // ── Search ──────────────────────────────────────────────────────────

    suspend fun search(query: String): SearchResult? {
        requireConfigured()
        val body = unwrap(api().search3(query))
        return body.searchResult3
    }

    // ── Favorites ───────────────────────────────────────────────────────

    suspend fun star(id: String? = null, albumId: String? = null, artistId: String? = null) {
        requireConfigured()
        unwrap(api().star(id, albumId, artistId))
    }

    suspend fun unstar(id: String? = null, albumId: String? = null, artistId: String? = null) {
        requireConfigured()
        unwrap(api().unstar(id, albumId, artistId))
    }

    suspend fun getStarred(): StarredResponse? {
        requireConfigured()
        val body = unwrap(api().getStarred2())
        return body.starred2
    }

    // ── Random ──────────────────────────────────────────────────────────

    suspend fun getRandomSongs(size: Int = 20): List<Song> {
        requireConfigured()
        val body = unwrap(api().getRandomSongs(size))
        return body.randomSongs?.song.orEmpty()
    }

    // ── Playlists ───────────────────────────────────────────────────────

    suspend fun getPlaylists(username: String? = null): List<Playlist> {
        requireConfigured()
        val body = unwrap(api().getPlaylists(username))
        return body.playlists?.playlist.orEmpty()
    }

    suspend fun getPlaylist(id: String): Playlist? {
        requireConfigured()
        val body = unwrap(api().getPlaylist(id))
        return body.playlist
    }

    // ── Rating (local float + server sync) ──────────────────────────────

    suspend fun setRating(songId: String, rating: Float) {
        val serverRating = rating.roundToInt().coerceIn(0, 5)
        database.localRatingDao().upsert(
            LocalRating(
                songId = songId,
                provider = MediaId.PROVIDER_SUBSONIC,
                rating = rating,
                serverRating = serverRating,
                needsSync = true,
            ),
        )
        if (!isConfigured) return
        try {
            unwrap(api().setRating(songId, serverRating))
            database.localRatingDao().upsert(
                LocalRating(
                    songId = songId,
                    provider = MediaId.PROVIDER_SUBSONIC,
                    rating = rating,
                    serverRating = serverRating,
                    needsSync = false,
                ),
            )
        } catch (_: Exception) {
            // Will be synced later via syncPendingRatings
        }
    }

    fun getRating(songId: String): Flow<LocalRating?> =
        database.localRatingDao().getRating(songId, MediaId.PROVIDER_SUBSONIC)

    suspend fun getRatings(songIds: Collection<String>): Map<String, LocalRating> {
        if (songIds.isEmpty()) return emptyMap()
        return database.localRatingDao()
            .getRatings(songIds.distinct(), MediaId.PROVIDER_SUBSONIC)
            .associateBy { it.songId }
    }

    suspend fun syncPendingRatings() {
        val pending = database.localRatingDao().getRatingsNeedingSync().first()
        for (rating in pending) {
            if (rating.provider != MediaId.PROVIDER_SUBSONIC) continue
            try {
                unwrap(api().setRating(rating.songId, rating.serverRating))
                database.localRatingDao().upsert(rating.copy(needsSync = false))
            } catch (_: Exception) {
                // Skip this rating, will retry next sync
            }
        }
    }

    // ── Lyrics ──────────────────────────────────────────────────────────

    suspend fun getLyrics(songId: String): LyricsList? {
        requireConfigured()
        val body = unwrap(api().getLyricsBySongId(songId))
        return body.lyricsList
    }

    // ── Play History ────────────────────────────────────────────────────

    suspend fun recordPlay(
        song: Song,
        durationMs: Long,
        completedPercent: Float,
        activityContext: ActivityContext = ActivityContext.None,
    ) {
        database.playHistoryDao().insert(
            PlayHistory(
                songId = song.id,
                provider = MediaId.PROVIDER_SUBSONIC,
                title = song.title.orEmpty(),
                artist = song.artist.orEmpty(),
                album = song.album.orEmpty(),
                albumId = song.albumId.orEmpty(),
                coverArtId = song.coverArt,
                durationMs = durationMs,
                completedPercent = completedPercent,
            ),
        )
        database.activityEventDao().insert(
            buildPlaybackActivity(song = song, activityContext = activityContext),
        )
    }

    fun getRecentHistory(limit: Int = 50): Flow<List<PlayHistory>> =
        database.playHistoryDao().getRecentHistory(limit)

    fun getRecentActivities(limit: Int = 20): Flow<List<ActivityEvent>> =
        database.activityEventDao()
            .getRecentEvents(limit * 8)
            .map { events -> collapseToLatestUnique(events).take(limit) }

    suspend fun getRecentMemoryActivities(limit: Int = 48): List<ActivityEvent> =
        database.activityEventDao()
            .getRecentEvents(limit * 10)
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

    suspend fun getMostRecentPlay(songId: String): PlayHistory? =
        database.playHistoryDao().getMostRecentPlay(songId, MediaId.PROVIDER_SUBSONIC)

    suspend fun recordAlbumVisit(album: Album) {
        database.activityEventDao().insert(
            ActivityEvent(
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.VISITED.name,
                entityId = album.id,
                provider = MediaId.PROVIDER_SUBSONIC,
                title = album.name,
                subtitle = album.artist.orEmpty(),
                coverArtId = album.coverArt ?: album.id,
                albumId = album.id,
                artistId = album.artistId,
            ),
        )
    }

    suspend fun recordArtistVisit(artist: ArtistDetail) {
        database.activityEventDao().insert(
            ActivityEvent(
                entityType = ActivityEntityType.ARTIST.name,
                actionType = ActivityActionType.VISITED.name,
                entityId = artist.id,
                provider = MediaId.PROVIDER_SUBSONIC,
                title = artist.name,
                subtitle = "Artist",
                coverArtId = artist.coverArt,
                artistId = artist.id,
            ),
        )
    }

    // ── URLs (pass-through) ─────────────────────────────────────────────

    fun buildStreamUrl(songId: String): String =
        SubsonicApiFactory.buildStreamUrl(credentials(), songId)

    fun buildCoverArtUrl(coverArtId: String, size: Int? = null): String =
        SubsonicApiFactory.buildCoverArtUrl(credentials(), coverArtId, size)

    // ── Server test ─────────────────────────────────────────────────────

    suspend fun testConnection(): Boolean {
        requireConfigured()
        unwrap(api().ping())
        return true
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private fun unwrap(response: SubsonicResponse): SubsonicResponseBody {
        val body = response.subsonicResponse
        if (body.status != "ok") {
            val error = body.error
            throw SubsonicException(
                code = error?.code ?: -1,
                message = error?.message,
            )
        }
        return body
    }

    private fun buildPlaybackActivity(
        song: Song,
        activityContext: ActivityContext,
    ): ActivityEvent = when (activityContext) {
        is ActivityContext.Album -> ActivityEvent(
            entityType = ActivityEntityType.ALBUM.name,
            actionType = ActivityActionType.PLAYED.name,
            entityId = activityContext.albumId,
            provider = MediaId.PROVIDER_SUBSONIC,
            title = activityContext.albumName,
            subtitle = activityContext.artistName.orEmpty().ifBlank { song.artist.orEmpty() },
            coverArtId = activityContext.coverArtId ?: song.coverArt ?: song.albumId,
            songId = song.id,
            albumId = activityContext.albumId,
            artistId = activityContext.artistId ?: song.artistId,
        )

        is ActivityContext.Artist -> ActivityEvent(
            entityType = ActivityEntityType.ARTIST.name,
            actionType = ActivityActionType.PLAYED.name,
            entityId = activityContext.artistId,
            provider = MediaId.PROVIDER_SUBSONIC,
            title = activityContext.artistName,
            subtitle = "Artist",
            coverArtId = activityContext.coverArtId ?: song.coverArt,
            songId = song.id,
            albumId = song.albumId,
            artistId = activityContext.artistId,
        )

        is ActivityContext.Playlist -> ActivityEvent(
            entityType = ActivityEntityType.PLAYLIST.name,
            actionType = ActivityActionType.PLAYED.name,
            entityId = activityContext.playlistId,
            provider = MediaId.PROVIDER_SUBSONIC,
            title = activityContext.playlistName,
            subtitle = activityContext.owner.orEmpty().ifBlank { "Playlist" },
            coverArtId = activityContext.coverArtId ?: song.coverArt ?: song.albumId,
            songId = song.id,
            albumId = song.albumId,
            artistId = song.artistId,
        )

        ActivityContext.None -> ActivityEvent(
            entityType = ActivityEntityType.SONG.name,
            actionType = ActivityActionType.PLAYED.name,
            entityId = song.id,
            provider = MediaId.PROVIDER_SUBSONIC,
            title = song.title.orEmpty(),
            subtitle = song.artist.orEmpty(),
            coverArtId = song.coverArt ?: song.albumId,
            songId = song.id,
            albumId = song.albumId,
            artistId = song.artistId,
        )
    }

    private fun collapseToLatestUnique(events: List<ActivityEvent>): List<ActivityEvent> {
        val seenKeys = mutableSetOf<String>()
        val collapsed = mutableListOf<ActivityEvent>()
        events.forEach { event ->
            val stableKey = "${event.actionType}:${event.entityType}:${event.entityId}"
            if (seenKeys.add(stableKey)) {
                collapsed += event
            }
        }
        return collapsed
    }
}
