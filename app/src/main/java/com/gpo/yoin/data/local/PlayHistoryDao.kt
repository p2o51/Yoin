package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayHistoryDao {
    @Query(
        "SELECT * FROM play_history " +
            "WHERE profileId = :profileId " +
            "AND provider = :provider " +
            "ORDER BY playedAt DESC LIMIT :limit",
    )
    fun getRecentHistory(profileId: String, provider: String, limit: Int): Flow<List<PlayHistory>>

    @Query(
        "SELECT * FROM play_history " +
            "WHERE songId = :songId AND provider = :provider AND profileId = :profileId " +
            "ORDER BY playedAt DESC LIMIT 1",
    )
    suspend fun getMostRecentPlay(songId: String, provider: String, profileId: String): PlayHistory?

    @Query(
        "SELECT MAX(playedAt) FROM play_history " +
            "WHERE albumId = :albumId AND provider = :provider AND profileId = :profileId",
    )
    suspend fun getAlbumLastPlayed(albumId: String, provider: String, profileId: String): Long?

    @Insert
    suspend fun insert(entry: PlayHistory)

    /** Each album's last play for this profile (Library's Recents); albumless plays are left out. */
    @Query(
        "SELECT albumId, MAX(playedAt) AS lastPlayedAt FROM play_history " +
            "WHERE profileId = :profileId AND provider = :provider AND albumId != '' " +
            "GROUP BY albumId"
    )
    fun observeAlbumLastPlayed(profileId: String, provider: String): Flow<List<AlbumLastPlayed>>

    @Query("DELETE FROM play_history WHERE playedAt < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    @Query(
        "SELECT COUNT(*) FROM play_history " +
            "WHERE songId = :songId AND provider = :provider AND profileId = :profileId",
    )
    fun getPlayCount(songId: String, provider: String, profileId: String): Flow<Int>

    @Query(
        "SELECT albumId, provider, album AS albumName, artist AS artistName, coverArtId, " +
            "COUNT(*) AS playCount, MIN(playedAt) AS firstPlayedAt, MAX(playedAt) AS lastPlayedAt " +
            "FROM play_history " +
            "WHERE profileId = :profileId AND provider = :provider AND albumId != '' " +
            "GROUP BY albumId, provider " +
            "ORDER BY lastPlayedAt DESC LIMIT :limit",
    )
    suspend fun getAlbumAggregates(
        profileId: String,
        provider: String,
        limit: Int,
    ): List<AlbumPlayHistoryAggregate>

    /**
     * Same aggregate as [getAlbumAggregates], but for the given raw album ids
     * only — no recency window. Albums with no play rows are simply absent.
     */
    @Query(
        "SELECT albumId, provider, album AS albumName, artist AS artistName, coverArtId, " +
            "COUNT(*) AS playCount, MIN(playedAt) AS firstPlayedAt, MAX(playedAt) AS lastPlayedAt " +
            "FROM play_history " +
            "WHERE profileId = :profileId AND provider = :provider AND albumId IN (:albumIds) " +
            "GROUP BY albumId, provider",
    )
    suspend fun getAlbumAggregatesFor(
        profileId: String,
        provider: String,
        albumIds: List<String>,
    ): List<AlbumPlayHistoryAggregate>

    /** Plays per song for the given tracks (the album page's ticket counts); unplayed songs are absent. */
    @Query(
        "SELECT songId, COUNT(*) AS playCount FROM play_history " +
            "WHERE profileId = :profileId AND provider = :provider AND songId IN (:songIds) " +
            "GROUP BY songId",
    )
    fun observeSongPlayCounts(
        songIds: List<String>,
        provider: String,
        profileId: String,
    ): Flow<List<SongPlayCount>>

    /** One album's plays in Yoin: how many, the first and the last (both null with no plays). */
    @Query(
        "SELECT COUNT(*) AS playCount, MIN(playedAt) AS firstPlayedAt, MAX(playedAt) AS lastPlayedAt " +
            "FROM play_history " +
            "WHERE albumId = :albumId AND provider = :provider AND profileId = :profileId",
    )
    fun observeAlbumPlayStats(albumId: String, provider: String, profileId: String): Flow<AlbumPlayStats>

    /** The newest play row for this scope; a single-row twin of [getRecentHistory]. */
    @Query(
        "SELECT * FROM play_history " +
            "WHERE profileId = :profileId AND provider = :provider " +
            "ORDER BY playedAt DESC LIMIT 1",
    )
    fun observeMostRecent(profileId: String, provider: String): Flow<PlayHistory?>

    /**
     * An artist's most-played songs for this profile. A play belongs to the
     * artist when it came from one of their releases OR carries their exact
     * name (features, releases outside the fetched discography).
     */
    @Query(
        "SELECT songId, provider, title, album, albumId, coverArtId, " +
            "MAX(durationMs) AS durationMs, COUNT(*) AS playCount, MAX(playedAt) AS lastPlayedAt " +
            "FROM play_history " +
            "WHERE profileId = :profileId AND provider = :provider " +
            "AND (albumId IN (:albumIds) OR artist = :artistName) " +
            "GROUP BY songId " +
            "ORDER BY playCount DESC, lastPlayedAt DESC LIMIT :limit",
    )
    suspend fun getArtistTopSongs(
        profileId: String,
        provider: String,
        albumIds: List<String>,
        artistName: String,
        limit: Int,
    ): List<ArtistSongPlayAggregate>

    /** Total plays across the same artist match as [getArtistTopSongs]. */
    @Query(
        "SELECT COUNT(*) AS playCount " +
            "FROM play_history " +
            "WHERE profileId = :profileId AND provider = :provider " +
            "AND (albumId IN (:albumIds) OR artist = :artistName)",
    )
    suspend fun getArtistPlayStats(
        profileId: String,
        provider: String,
        albumIds: List<String>,
        artistName: String,
    ): ArtistPlayStats

    /**
     * Rediscover's songs: tracks this profile kept something on — a track
     * rating above 0 or a non-blank song note — that were played in Yoin but
     * not since [playedBefore] (history only; visits never count), with their
     * play history and the memory itself. Ordered as Rediscover orders: rated
     * first, best rating first, then longest unplayed. Track metadata is the
     * history's own (one row per song id), enough to play the song again.
     */
    @Query(
        "SELECT ph.songId AS songId, ph.provider AS provider, ph.title AS title, ph.artist AS artist, " +
            "ph.album AS album, ph.albumId AS albumId, ph.coverArtId AS coverArtId, " +
            "MAX(ph.durationMs) AS durationMs, COUNT(*) AS playCount, " +
            "MIN(ph.playedAt) AS firstPlayedAt, MAX(ph.playedAt) AS lastPlayedAt, " +
            "(SELECT r.rating FROM local_ratings r " +
            "WHERE r.profileId = :profileId AND r.provider = :provider " +
            "AND r.songId = ph.songId AND r.rating > 0) AS rating, " +
            "(SELECT COUNT(*) FROM song_notes n " +
            "WHERE n.profileId = :profileId AND n.provider = :provider " +
            "AND n.trackId = ph.songId AND TRIM(n.content) != '') AS noteCount, " +
            "(SELECT n.content FROM song_notes n " +
            "WHERE n.profileId = :profileId AND n.provider = :provider " +
            "AND n.trackId = ph.songId AND TRIM(n.content) != '' " +
            "ORDER BY n.updatedAt DESC LIMIT 1) AS latestNote " +
            "FROM play_history ph " +
            "WHERE ph.profileId = :profileId AND ph.provider = :provider AND ph.songId IN (" +
            "SELECT songId FROM local_ratings " +
            "WHERE profileId = :profileId AND provider = :provider AND rating > 0 " +
            "UNION SELECT trackId FROM song_notes " +
            "WHERE profileId = :profileId AND provider = :provider AND TRIM(content) != '') " +
            "GROUP BY ph.songId " +
            "HAVING MAX(ph.playedAt) <= :playedBefore " +
            "ORDER BY rating IS NULL, rating DESC, lastPlayedAt ASC " +
            "LIMIT :limit",
    )
    suspend fun getRediscoverSongs(
        profileId: String,
        provider: String,
        playedBefore: Long,
        limit: Int,
    ): List<SongMemoryAggregate>
}

/**
 * Per album: how many of its tracks carry a memory signal (a song note, a
 * track rating) and when the newest was written. song_notes and local_ratings
 * hold no album, so the album is the one play_history recorded for the track.
 */
data class AlbumTrackSignalAggregate(
    val albumId: String,
    val signalCount: Int,
    val lastWrittenAt: Long,
)

/** One Rediscover song ([PlayHistoryDao.getRediscoverSongs]); ids raw, as history stores them. */
data class SongMemoryAggregate(
    val songId: String,
    val provider: String,
    val title: String,
    val artist: String,
    val album: String,
    /** Blank when the track played without an album. */
    val albumId: String,
    /** A [com.gpo.yoin.data.model.CoverRef] storage key. */
    val coverArtId: String?,
    val durationMs: Long,
    val playCount: Int,
    val firstPlayedAt: Long,
    val lastPlayedAt: Long,
    /** The track rating; null when unrated. */
    val rating: Float?,
    /** Non-blank song notes on the track. */
    val noteCount: Int,
    /** The newest non-blank note's text; null with none. */
    val latestNote: String?,
)

data class ArtistSongPlayAggregate(
    val songId: String,
    val provider: String,
    val title: String,
    val album: String,
    val albumId: String,
    val coverArtId: String?,
    val durationMs: Long,
    val playCount: Int,
    val lastPlayedAt: Long,
)

data class SongPlayCount(
    val songId: String,
    val playCount: Int,
)

data class AlbumPlayStats(
    val playCount: Int,
    val firstPlayedAt: Long?,
    val lastPlayedAt: Long?,
)

data class ArtistPlayStats(
    val playCount: Int,
)

data class AlbumPlayHistoryAggregate(
    val albumId: String,
    val provider: String,
    val albumName: String,
    val artistName: String,
    val coverArtId: String?,
    val playCount: Int,
    val firstPlayedAt: Long?,
    val lastPlayedAt: Long?,
)

data class AlbumLastPlayed(
    val albumId: String,
    val lastPlayedAt: Long
)
