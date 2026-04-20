package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LyricsCacheDao {

    /** 只返回 TTL 内的记录；过期的当 miss 处理，由 upsert 覆盖。 */
    @Query(
        "SELECT * FROM lyrics_cache " +
            "WHERE trackProvider = :trackProvider AND trackRawId = :trackRawId " +
            "AND cachedAt >= :minCachedAt " +
            "LIMIT 1",
    )
    suspend fun getFresh(
        trackProvider: String,
        trackRawId: String,
        minCachedAt: Long,
    ): LyricsCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LyricsCache)
}
