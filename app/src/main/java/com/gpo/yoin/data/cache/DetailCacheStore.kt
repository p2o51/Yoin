package com.gpo.yoin.data.cache

import com.gpo.yoin.data.local.DetailCacheDao
import com.gpo.yoin.data.local.DetailCacheEntry
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * A cached disk row. [cachedAt] is available immediately so callers can judge
 * freshness before paying the JSON decode; [value] decodes lazily (off the
 * caller's thread), so a row that is never served is never decoded. Only a
 * successfully decoded row is LRU-touched; a corrupt one is deleted instead.
 */
class Cached<out V>(val cachedAt: Long, private val load: suspend () -> V?) {
    /** Decode and return the row's value, or null when the JSON is corrupt. */
    suspend fun value(): V? = load()
}

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

    /**
     * Running total of stored JSON length so writes don't pay a full-table SUM
     * each time: seeded lazily (one SUM on the first write), adjusted per
     * upsert/delete, resynced by [trimToBudget], and dropped back to null
     * ("unknown", re-seed on next write) after bulk deletes. Guarded by
     * [sizeLock].
     */
    private var totalBytesEstimate: Long? = null
    private val sizeLock = Mutex()

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

    /** Drop an album's cached copy (e.g. after a track favorite changes it). */
    suspend fun removeAlbum(profileId: String, id: String) =
        deleteTracked(profileId, KIND_ALBUM, id)

    /** Drop an artist's cached copy after a follow toggle. */
    suspend fun removeArtist(profileId: String, id: String) =
        deleteTracked(profileId, KIND_ARTIST, id)

    /** Drop a playlist's cached copy after the user edits it. */
    suspend fun removePlaylist(profileId: String, id: String) =
        deleteTracked(profileId, KIND_PLAYLIST, id)

    /** Hygiene pass (call once on startup): age out anything past [maxAgeMs]. */
    suspend fun purgeExpired() {
        runCatching { dao.deleteOlderThan(clock() - maxAgeMs) }
        // Bulk delete of unknown size: drop the running total; the next write
        // re-seeds it with one SUM.
        sizeLock.withLock { totalBytesEstimate = null }
    }

    private suspend fun <V> read(
        profileId: String,
        kind: String,
        id: String,
        decode: (String) -> V,
    ): Cached<V>? {
        val entry = dao.get(profileId, kind, id) ?: return null
        if (clock() - entry.cachedAt > maxAgeMs) {
            deleteTracked(profileId, kind, id)
            return null
        }
        return Cached(entry.cachedAt) {
            // Corrupt / incompatible JSON → treat as a miss rather than crash.
            val decoded = withContext(Dispatchers.Default) {
                runCatching { decode(entry.json) }.getOrNull()
            }
            if (decoded != null) {
                // LRU-touch only rows we actually serve …
                runCatching { dao.touch(profileId, kind, id, clock()) }
            } else {
                // … never a corrupt one: delete it, or it would sit
                // LRU-protected in the cache forever.
                deleteTracked(profileId, kind, id)
            }
            decoded
        }
    }

    private suspend fun write(
        profileId: String,
        kind: String,
        id: String,
        encode: () -> String,
    ) {
        runCatching {
            val encoded = withContext(Dispatchers.Default) { encode() }
            val now = clock()
            sizeLock.withLock {
                // Code points, not String.length: SQLite LENGTH() counts code
                // points on TEXT, so Kotlin-side deltas must use the same unit
                // or non-BMP characters drift the estimate low.
                val oldLen = dao.get(profileId, kind, id)?.json?.codePointLength() ?: 0L
                val before = totalBytesEstimate ?: dao.totalBytes()
                // Unknown while mutating: stays null if anything below throws.
                totalBytesEstimate = null
                dao.upsert(DetailCacheEntry(profileId, kind, id, encoded, now, now))
                var total = (before - oldLen + encoded.codePointLength()).coerceAtLeast(0)
                if (total > maxBytes) total = trimToBudget()
                totalBytesEstimate = total
            }
        }
    }

    /**
     * Delete one row, keeping [totalBytesEstimate] in step. The length lookup
     * runs only while a total is being tracked, and only a confirmed-present
     * row adjusts it (so a repeated delete can't skew the estimate).
     */
    private suspend fun deleteTracked(profileId: String, kind: String, id: String) {
        sizeLock.withLock {
            val len = if (totalBytesEstimate != null) {
                runCatching { dao.get(profileId, kind, id)?.json?.codePointLength() }.getOrNull()
            } else {
                null
            }
            runCatching { dao.delete(profileId, kind, id) }.onSuccess {
                if (len != null) {
                    totalBytesEstimate = totalBytesEstimate?.minus(len)?.coerceAtLeast(0)
                }
            }
        }
    }

    /**
     * Evict oldest-accessed entries until back under [maxBytes]; returns the
     * post-eviction total (one fresh SUM, adjusted per deletion), which the
     * caller stores as the new running total. Called with [sizeLock] held.
     */
    private suspend fun trimToBudget(): Long {
        var total = dao.totalBytes()
        if (total <= maxBytes) return total
        // SQLite (minSdk 26) has no guaranteed window functions, so accumulate
        // and delete oldest-accessed in Kotlin until we're back under budget.
        for (row in dao.sizesOldestFirst()) {
            if (total <= maxBytes) break
            dao.delete(row.profileId, row.kind, row.entityId)
            total -= row.bytes
        }
        return total
    }

    /** Same unit as SQLite LENGTH() on TEXT: code points, not UTF-16 units. */
    private fun String.codePointLength(): Long = codePointCount(0, length).toLong()

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
