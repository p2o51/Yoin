package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CacheMetadataDao {
    @Query("SELECT * FROM cache_metadata")
    fun getAll(): Flow<List<CacheMetadata>>

    @Query("SELECT * FROM cache_metadata WHERE songId = :songId")
    suspend fun getBySongId(songId: String): CacheMetadata?

    @Query("SELECT COALESCE(SUM(fileSizeBytes), 0) FROM cache_metadata")
    fun getTotalCacheSize(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: CacheMetadata)

    @Query("DELETE FROM cache_metadata WHERE songId = :songId")
    suspend fun delete(songId: String)

    @Query(
        """
        DELETE FROM cache_metadata WHERE songId NOT IN (
            SELECT songId FROM cache_metadata
            ORDER BY lastAccessedAt DESC
            LIMIT :keepCount
        )
        """,
    )
    suspend fun deleteOldest(keepCount: Int)

    @Query("UPDATE cache_metadata SET lastAccessedAt = :time WHERE songId = :songId")
    suspend fun updateLastAccessed(songId: String, time: Long)
}
