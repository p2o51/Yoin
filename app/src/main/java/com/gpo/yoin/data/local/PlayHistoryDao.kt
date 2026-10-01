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

    /** Total plays + the most recent play across the same artist match as [getArtistTopSongs]. */
    @Query(
        "SELECT COUNT(*) AS playCount, MAX(playedAt) AS lastPlayedAt " +
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
}

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

data class ArtistPlayStats(
    val playCount: Int,
    val lastPlayedAt: Long?,
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
