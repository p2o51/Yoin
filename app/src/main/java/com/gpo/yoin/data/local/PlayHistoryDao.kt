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

    @Insert
    suspend fun insert(entry: PlayHistory)

    @Query("DELETE FROM play_history WHERE playedAt < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    @Query(
        "SELECT COUNT(*) FROM play_history " +
            "WHERE songId = :songId AND provider = :provider AND profileId = :profileId",
    )
    fun getPlayCount(songId: String, provider: String, profileId: String): Flow<Int>
}
