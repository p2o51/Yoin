package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface TrackAlbumLinkDao {
    @Upsert
    suspend fun upsert(link: TrackAlbumLink)

    @Upsert
    suspend fun upsertAll(links: List<TrackAlbumLink>)

    @Query(
        "SELECT * FROM track_album_links " +
            "WHERE profileId = :profileId AND provider = :provider AND trackId = :trackId",
    )
    suspend fun get(profileId: String, provider: String, trackId: String): TrackAlbumLink?

    /**
     * Tracks [profileId] noted or rated on [provider] whose album is not known yet: no link (a link with a blank
     * album is a lookup that found none, and is not retried) and no play with an album. Oldest note first.
     */
    @Query(
        "SELECT t.trackId FROM (" +
            "SELECT trackId, MIN(createdAt) AS since FROM song_notes " +
            "WHERE profileId = :profileId AND provider = :provider GROUP BY trackId " +
            "UNION ALL " +
            "SELECT songId AS trackId, updatedAt AS since FROM local_ratings " +
            "WHERE profileId = :profileId AND provider = :provider" +
            ") t " +
            "WHERE NOT EXISTS (SELECT 1 FROM track_album_links l " +
            "WHERE l.profileId = :profileId AND l.provider = :provider AND l.trackId = t.trackId) " +
            "AND NOT EXISTS (SELECT 1 FROM play_history h " +
            "WHERE h.profileId = :profileId AND h.provider = :provider AND h.songId = t.trackId AND h.albumId != '') " +
            "GROUP BY t.trackId ORDER BY MIN(t.since) LIMIT :limit",
    )
    suspend fun getUnlinkedTracks(profileId: String, provider: String, limit: Int): List<String>
}
