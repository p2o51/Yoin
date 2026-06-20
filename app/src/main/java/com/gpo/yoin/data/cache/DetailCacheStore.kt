package com.gpo.yoin.data.cache

import com.gpo.yoin.data.local.DetailCacheDao
import com.gpo.yoin.data.local.DetailCacheEntry
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.Playlist
import kotlinx.serialization.json.Json

/** A cached value plus when it was written, so callers can judge freshness. */
data class Cached<out V>(val value: V, val cachedAt: Long)

/**
 * Persistent (Room) cache for album / artist / playlist DETAIL responses,
 * stored as JSON and bounded by a byte budget — like Spotify's on-device cache.
 *
 * Eviction is LRU by `accessedAt` (oldest-touched dropped first) once the total
 * JSON size exceeds [maxBytes]; entries older than [maxAgeMs] are never served
 * and get purged. Profile-scoped via the key, so it survives account switches
 * without leaking one account's data into another.
 */
class DetailCacheStore(
    private val dao: DetailCacheDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    suspend fun readAlbum(profileId: String, id: String): Cached<Album>? =
        read(profileId, KIND_ALBUM, id) { json.decodeFromString(AlbumDto.serializer(), it).toDomain() }

    suspend fun writeAlbum(profileId: String, id: String, value: Album) =
        write(profileId, KIND_ALBUM, id) { json.encodeToString(AlbumDto.serializer(), AlbumDto.from(value)) }

    suspend fun readArtist(profileId: String, id: String): Cached<ArtistDetail>? =
        read(profileId, KIND_ARTIST, id) {
            json.decodeFromString(ArtistDetailDto.serializer(), it).toDomain()
        }

    suspend fun writeArtist(profileId: String, id: String, value: ArtistDetail) =
        write(profileId, KIND_ARTIST, id) {
            json.encodeToString(ArtistDetailDto.serializer(), ArtistDetailDto.from(value))
        }

    suspend fun readPlaylist(profileId: String, id: String): Cached<Playlist>? =
        read(profileId, KIND_PLAYLIST, id) {
            json.decodeFromString(PlaylistDto.serializer(), it).toDomain()
        }

    suspend fun writePlaylist(profileId: String, id: String, value: Playlist) =
        write(profileId, KIND_PLAYLIST, id) {
            json.encodeToString(PlaylistDto.serializer(), PlaylistDto.from(value))
        }

    /** Drop a playlist's cached copy after the user edits it. */
    suspend fun removePlaylist(profileId: String, id: String) {
        runCatching { dao.delete(profileId, KIND_PLAYLIST, id) }
    }

    /** Hygiene pass (call once on startup): age out anything past [maxAgeMs]. */
    suspend fun purgeExpired() {
        runCatching { dao.deleteOlderThan(clock() - maxAgeMs) }
    }

    private suspend fun <V> read(
        profileId: String,
        kind: String,
        id: String,
        decode: (String) -> V,
    ): Cached<V>? {
        val entry = dao.get(profileId, kind, id) ?: return null
        if (clock() - entry.cachedAt > maxAgeMs) {
            runCatching { dao.delete(profileId, kind, id) }
            return null
        }
        runCatching { dao.touch(profileId, kind, id, clock()) }
        // Corrupt / incompatible JSON → treat as a miss rather than crash.
        return runCatching { Cached(decode(entry.json), entry.cachedAt) }.getOrNull()
    }

    private suspend fun write(
        profileId: String,
        kind: String,
        id: String,
        encode: () -> String,
    ) {
        runCatching {
            val now = clock()
            dao.upsert(DetailCacheEntry(profileId, kind, id, encode(), now, now))
            trimToBudget()
        }
    }

    private suspend fun trimToBudget() {
        var total = dao.totalBytes()
        if (total <= maxBytes) return
        // SQLite (minSdk 26) has no guaranteed window functions, so accumulate
        // and delete oldest-accessed in Kotlin until we're back under budget.
        for (row in dao.sizesOldestFirst()) {
            if (total <= maxBytes) break
            dao.delete(row.profileId, row.kind, row.entityId)
            total -= row.bytes
        }
    }

    companion object {
        private const val KIND_ALBUM = "ALBUM"
        private const val KIND_ARTIST = "ARTIST"
        private const val KIND_PLAYLIST = "PLAYLIST"

        /** ~24 MB on-disk cap (JSON char count proxy). */
        const val DEFAULT_MAX_BYTES = 24L * 1024 * 1024

        /** Entries older than this are never served and get purged (30 days). */
        const val DEFAULT_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
