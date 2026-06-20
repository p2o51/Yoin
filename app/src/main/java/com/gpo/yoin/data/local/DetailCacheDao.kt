package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface DetailCacheDao {
    @Upsert
    suspend fun upsert(entry: DetailCacheEntry)

    @Query(
        "SELECT * FROM detail_cache " +
            "WHERE profileId = :profileId AND kind = :kind AND entityId = :entityId LIMIT 1",
    )
    suspend fun get(profileId: String, kind: String, entityId: String): DetailCacheEntry?

    @Query(
        "UPDATE detail_cache SET accessedAt = :now " +
            "WHERE profileId = :profileId AND kind = :kind AND entityId = :entityId",
    )
    suspend fun touch(profileId: String, kind: String, entityId: String, now: Long)

    @Query(
        "DELETE FROM detail_cache " +
            "WHERE profileId = :profileId AND kind = :kind AND entityId = :entityId",
    )
    suspend fun delete(profileId: String, kind: String, entityId: String)

    @Query("SELECT COALESCE(SUM(LENGTH(json)), 0) FROM detail_cache")
    suspend fun totalBytes(): Long

    @Query(
        "SELECT profileId, kind, entityId, LENGTH(json) AS bytes " +
            "FROM detail_cache ORDER BY accessedAt ASC",
    )
    suspend fun sizesOldestFirst(): List<DetailCacheSize>

    @Query("DELETE FROM detail_cache WHERE cachedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
