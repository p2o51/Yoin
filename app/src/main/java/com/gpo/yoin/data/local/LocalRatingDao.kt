package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalRatingDao {
    @Query(
        "SELECT * FROM local_ratings " +
            "WHERE profileId = :profileId AND songId = :songId AND provider = :provider",
    )
    fun getRating(songId: String, provider: String, profileId: String): Flow<LocalRating?>

    @Query(
        "SELECT * FROM local_ratings " +
            "WHERE profileId = :profileId AND provider = :provider AND songId IN (:songIds)",
    )
    suspend fun getRatings(
        songIds: List<String>,
        provider: String,
        profileId: String,
    ): List<LocalRating>

    /** Live twin of [getRatings]: the album page re-reads its tracks' scores when one changes elsewhere. */
    @Query(
        "SELECT * FROM local_ratings " +
            "WHERE profileId = :profileId AND provider = :provider AND songId IN (:songIds)",
    )
    fun observeRatings(
        songIds: List<String>,
        provider: String,
        profileId: String,
    ): Flow<List<LocalRating>>

    /**
     * Per album, this profile's track ratings (above 0): how many tracks and
     * the newest rating. A rating carries no album, so the album is the one
     * play_history recorded for the track, or the one track_album_links knows
     * (rated on an album page, or imported, but never played in Yoin); a track
     * known to neither belongs to none. Most-rated albums first.
     */
    @Query(
        "SELECT ph.albumId AS albumId, COUNT(DISTINCT r.songId) AS signalCount, " +
            "MAX(r.updatedAt) AS lastWrittenAt " +
            "FROM local_ratings r " +
            "JOIN (SELECT songId, albumId FROM play_history " +
            "WHERE profileId = :profileId AND provider = :provider AND albumId != '' " +
            "UNION SELECT trackId AS songId, albumId FROM track_album_links " +
            "WHERE profileId = :profileId AND provider = :provider AND albumId != '') ph " +
            "ON ph.songId = r.songId " +
            "WHERE r.profileId = :profileId AND r.provider = :provider AND r.rating > 0 " +
            "GROUP BY ph.albumId " +
            "ORDER BY signalCount DESC, lastWrittenAt DESC LIMIT :limit",
    )
    suspend fun getRatedAlbumAggregates(
        provider: String,
        profileId: String,
        limit: Int,
    ): List<AlbumTrackSignalAggregate>

    @Query(
        "SELECT * FROM local_ratings " +
            "WHERE profileId = :profileId AND provider = :provider AND needsSync = 1",
    )
    fun getRatingsNeedingSync(provider: String, profileId: String): Flow<List<LocalRating>>

    /**
     * Change stamp: re-emits whenever the profile's track ratings change.
     * COUNT catches deletes, MAX(updatedAt) catches inserts/edits.
     */
    @Query(
        "SELECT COUNT(*) + IFNULL(MAX(updatedAt), 0) FROM local_ratings " +
            "WHERE profileId = :profileId AND provider = :provider",
    )
    fun observeChangeStamp(provider: String, profileId: String): Flow<Long>

    @Upsert
    suspend fun upsert(rating: LocalRating)

    @Upsert
    suspend fun upsertAll(ratings: List<LocalRating>)

    @Query("DELETE FROM local_ratings WHERE profileId = :profileId AND provider = :provider AND songId IN (:songIds)")
    suspend fun deleteForSongs(songIds: List<String>, provider: String, profileId: String)

    /**
     * The provider took [rating]: clear the row's needsSync — only while the
     * row still holds that rating. A newer rating written meanwhile keeps its
     * value and its flag; a late answer about an older one never rolls it back.
     */
    @Query(
        "UPDATE local_ratings SET needsSync = 0 " +
            "WHERE profileId = :profileId AND songId = :songId AND provider = :provider AND rating = :rating"
    )
    suspend fun markSynced(songId: String, provider: String, profileId: String, rating: Float): Int

    @Query("DELETE FROM local_ratings")
    suspend fun deleteAll()
}
