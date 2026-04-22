package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface MemoryCopyCacheDao {
    @Query(
        "SELECT * FROM memory_copy_cache " +
            "WHERE provider = :provider " +
            "AND entityType = :entityType " +
            "AND entityId = :entityId " +
            "LIMIT 1",
    )
    suspend fun get(
        provider: String,
        entityType: String,
        entityId: String,
    ): MemoryCopyCache?

    @Upsert
    suspend fun upsert(entry: MemoryCopyCache)

    @Query(
        "DELETE FROM memory_copy_cache " +
            "WHERE provider = :provider " +
            "AND entityType = :entityType " +
            "AND entityId = :entityId",
    )
    suspend fun delete(
        provider: String,
        entityType: String,
        entityId: String,
    )

    @Query("DELETE FROM memory_copy_cache WHERE generatedAt < :olderThanEpochMs")
    suspend fun pruneOlderThan(olderThanEpochMs: Long)
}
