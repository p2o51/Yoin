package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityEventDao {
    @Query(
        "SELECT * FROM activity_events " +
            "WHERE profileId = :profileId " +
            "AND provider = :provider " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit",
    )
    fun getRecentEvents(profileId: String, provider: String, limit: Int): Flow<List<ActivityEvent>>

    @Insert
    suspend fun insert(entry: ActivityEvent)
}
