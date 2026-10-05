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
     * Per album, this profile's track ratings (above 0) on tracks it played
     * in Yoin: how many tracks and the newest rating. A rating carries no
     * album, so the album is the one play_history recorded for the track; a
     * track never played in Yoin (or played with no album id) belongs to none.
     * Most-rated albums first.
     */
    @Query(
        "SELECT ph.albumId AS albumId, COUNT(DISTINCT r.songId) AS signalCount, " +
            "MAX(r.updatedAt) AS lastWrittenAt " +
            "FROM local_ratings r " +
            "JOIN (SELECT DISTINCT songId, albumId FROM play_history " +
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

    @Query("DELETE FROM local_ratings")
    suspend fun deleteAll()
}
