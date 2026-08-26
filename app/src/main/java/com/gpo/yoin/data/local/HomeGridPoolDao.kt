package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HomeGridPoolDao {
    @Query(
        "SELECT * FROM home_grid_pool_cache " +
            "WHERE profileId = :profileId AND provider = :provider " +
            "ORDER BY itemType, sortOrder",
    )
    suspend fun getForProfile(profileId: String, provider: String): List<HomeGridPoolCache>

    @Query(
        "DELETE FROM home_grid_pool_cache " +
            "WHERE profileId = :profileId AND provider = :provider",
    )
    suspend fun deleteForProfile(profileId: String, provider: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<HomeGridPoolCache>)
}
