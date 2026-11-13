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

    /**
     * The artist-page visits of [artistIds] that recorded a cover (the
     * artist's portrait), newest first.
     */
    @Query(
        "SELECT * FROM activity_events " +
            "WHERE profileId = :profileId " +
            "AND provider = :provider " +
            "AND entityType = 'ARTIST' AND actionType = 'VISITED' " +
            "AND coverArtId IS NOT NULL AND coverArtId != '' " +
            "AND entityId IN (:artistIds) " +
            "ORDER BY timestamp DESC, id DESC"
    )
    suspend fun getArtistVisitsWithCover(
        profileId: String,
        provider: String,
        artistIds: List<String>
    ): List<ActivityEvent>

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

    /**
     * The albums and playlists the user's other accounts play most (Home's
     * "From your other profiles"): plays since [since] per entity, counted
     * per track started, most first. Accounts that no longer exist are left
     * out. No row cap here: one account's long tail must not crowd another
     * out before each account is capped on its own (buildOtherProfileItems).
     * [OtherProfilePlayAggregate.entityId] is the column as stored.
     */
    @Query(
        "SELECT profileId, provider, entityType, entityId, title, subtitle, coverArtId, " +
            "COUNT(*) AS playCount, MAX(timestamp) AS lastAt FROM activity_events " +
            "WHERE profileId != :activeProfileId " +
            "AND profileId IN (SELECT id FROM profiles) " +
            "AND actionType = 'PLAYED' " +
            "AND entityType IN ('ALBUM', 'PLAYLIST') " +
            "AND timestamp >= :since " +
            "GROUP BY profileId, provider, entityType, entityId " +
            "ORDER BY playCount DESC, lastAt DESC"
    )
    fun observeOtherProfilesMostPlayed(
        activeProfileId: String,
        since: Long
    ): Flow<List<OtherProfilePlayAggregate>>

    @Insert
    suspend fun insert(entry: ActivityEvent)

    /**
     * A playlist renamed on [profileId]'s account: its rows take the new
     * [title], so Home's Activities and the widgets don't keep the name it
     * had when it was played. [entityIds] are its stored forms (the raw id,
     * and the MediaId string older rows kept).
     */
    @Query(
        "UPDATE activity_events SET title = :title " +
            "WHERE profileId = :profileId AND provider = :provider " +
            "AND entityType = 'PLAYLIST' AND entityId IN (:entityIds)",
    )
    suspend fun renamePlaylist(profileId: String, provider: String, entityIds: List<String>, title: String)
}

data class OtherProfilePlayAggregate(
    val profileId: String,
    val provider: String,
    val entityType: String,
    val entityId: String,
    val title: String,
    val subtitle: String,
    val coverArtId: String?,
    val playCount: Int,
    val lastAt: Long
)

data class ActivityEntityLastSeen(
    val entityType: String,
    val entityId: String,
    val lastAt: Long
)
