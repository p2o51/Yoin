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

    @Query(
        "SELECT * FROM activity_events " +
            "WHERE profileId = :profileId " +
            "AND provider = :provider " +
            "AND entityType = 'ALBUM' " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit",
    )
    suspend fun getRecentAlbumEvents(
        profileId: String,
        provider: String,
        limit: Int,
    ): List<ActivityEvent>

    /**
     * The last time this profile opened or played each album, artist and
     * playlist (Library's Recents). [ActivityEntityLastSeen.entityId] is the
     * column as stored: a raw id or a legacy `provider:rawId`.
     */
    @Query(
        "SELECT entityType, entityId, MAX(timestamp) AS lastAt FROM activity_events " +
            "WHERE profileId = :profileId " +
            "AND provider = :provider " +
            "AND entityType IN ('ALBUM', 'ARTIST', 'PLAYLIST') " +
            "GROUP BY entityType, entityId"
    )
    fun observeEntityLastSeen(profileId: String, provider: String): Flow<List<ActivityEntityLastSeen>>

    @Insert
    suspend fun insert(entry: ActivityEvent)
}

data class ActivityEntityLastSeen(
    val entityType: String,
    val entityId: String,
    val lastAt: Long
)
