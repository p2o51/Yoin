package com.gpo.yoin.data.repository

import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.remote.Album
import com.gpo.yoin.data.remote.ArtistDetail
import com.gpo.yoin.data.remote.ArtistIndex
import com.gpo.yoin.data.remote.LyricsList
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
import kotlin.math.roundToInt

class YoinRepository(
    private val api: SubsonicApi,
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

    // ── Albums ──────────────────────────────────────────────────────────

    suspend fun getAlbumList(type: String, size: Int = 20, offset: Int = 0): List<Album> {
        requireConfigured()
        val body = unwrap(api.getAlbumList2(type, size, offset))
        return body.albumList2?.album.orEmpty()
    }

    suspend fun getAlbum(id: String): Album? {
        requireConfigured()
        val body = unwrap(api.getAlbum(id))
        return body.album
    }

    // ── Artists ─────────────────────────────────────────────────────────

    suspend fun getArtists(): List<ArtistIndex> {
        requireConfigured()
        val body = unwrap(api.getArtists())
        return body.artists?.index.orEmpty()
    }

    suspend fun getArtist(id: String): ArtistDetail? {
        requireConfigured()
        val body = unwrap(api.getArtist(id))
        return body.artist
    }

    // ── Search ──────────────────────────────────────────────────────────

    suspend fun search(query: String): SearchResult? {
        requireConfigured()
        val body = unwrap(api.search3(query))
        return body.searchResult3
    }

    // ── Favorites ───────────────────────────────────────────────────────

    suspend fun star(id: String? = null, albumId: String? = null, artistId: String? = null) {
        requireConfigured()
        unwrap(api.star(id, albumId, artistId))
    }

    suspend fun unstar(id: String? = null, albumId: String? = null, artistId: String? = null) {
        requireConfigured()
        unwrap(api.unstar(id, albumId, artistId))
    }

    suspend fun getStarred(): StarredResponse? {
        requireConfigured()
        val body = unwrap(api.getStarred2())
        return body.starred2
    }

    // ── Random ──────────────────────────────────────────────────────────

    suspend fun getRandomSongs(size: Int = 20): List<Song> {
        requireConfigured()
        val body = unwrap(api.getRandomSongs(size))
        return body.randomSongs?.song.orEmpty()
    }

    // ── Rating (local float + server sync) ──────────────────────────────

    suspend fun setRating(songId: String, rating: Float) {
        val serverRating = rating.roundToInt().coerceIn(0, 5)
        database.localRatingDao().upsert(
            LocalRating(
                songId = songId,
                rating = rating,
                serverRating = serverRating,
                needsSync = true,
            ),
        )
        if (!isConfigured) return
        try {
            unwrap(api.setRating(songId, serverRating))
            database.localRatingDao().upsert(
                LocalRating(
                    songId = songId,
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
        database.localRatingDao().getRating(songId)

    suspend fun syncPendingRatings() {
        val pending = database.localRatingDao().getRatingsNeedingSync().first()
        for (rating in pending) {
            try {
                unwrap(api.setRating(rating.songId, rating.serverRating))
                database.localRatingDao().upsert(rating.copy(needsSync = false))
            } catch (_: Exception) {
                // Skip this rating, will retry next sync
            }
        }
    }

    // ── Lyrics ──────────────────────────────────────────────────────────

    suspend fun getLyrics(songId: String): LyricsList? {
        requireConfigured()
        val body = unwrap(api.getLyricsBySongId(songId))
        return body.lyricsList
    }

    // ── Play History ────────────────────────────────────────────────────

    suspend fun recordPlay(song: Song, durationMs: Long, completedPercent: Float) {
        database.playHistoryDao().insert(
            PlayHistory(
                songId = song.id,
                title = song.title.orEmpty(),
                artist = song.artist.orEmpty(),
                album = song.album.orEmpty(),
                albumId = song.albumId.orEmpty(),
                coverArtId = song.coverArt,
                durationMs = durationMs,
                completedPercent = completedPercent,
            ),
        )
    }

    fun getRecentHistory(limit: Int = 50): Flow<List<PlayHistory>> =
        database.playHistoryDao().getRecentHistory(limit)

    // ── URLs (pass-through) ─────────────────────────────────────────────

    fun buildStreamUrl(songId: String): String =
        SubsonicApiFactory.buildStreamUrl(credentials(), songId)

    fun buildCoverArtUrl(coverArtId: String, size: Int? = null): String =
        SubsonicApiFactory.buildCoverArtUrl(credentials(), coverArtId, size)

    // ── Server test ─────────────────────────────────────────────────────

    suspend fun testConnection(): Boolean =
        try {
            val body = unwrap(api.ping())
            body.status == "ok"
        } catch (_: Exception) {
            false
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
}
