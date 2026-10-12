package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumRatingDao {
    @Query(
        "SELECT * FROM album_ratings " +
            "WHERE profileId = :profileId AND albumId = :albumId AND provider = :provider",
    )
    fun observe(albumId: String, provider: String, profileId: String): Flow<AlbumRating?>

    @Query(
        "SELECT * FROM album_ratings " +
            "WHERE profileId = :profileId AND albumId = :albumId AND provider = :provider",
    )
    suspend fun get(albumId: String, provider: String, profileId: String): AlbumRating?

    @Query(
        "SELECT * FROM album_ratings " +
            "WHERE profileId = :profileId AND provider = :provider " +
            "AND (ratingNeedsSync = 1 OR reviewNeedsSync = 1)",
    )
    fun observePending(provider: String, profileId: String): Flow<List<AlbumRating>>

    @Query(
        "SELECT * FROM album_ratings " +
            "WHERE profileId = :profileId AND provider = :provider",
    )
    suspend fun getAllForProfile(provider: String, profileId: String): List<AlbumRating>

    /**
     * Change stamp: re-emits whenever the profile's album ratings / reviews
     * change. COUNT catches deletes, MAX(updatedAt) catches inserts/edits.
     */
    @Query(
        "SELECT COUNT(*) + IFNULL(MAX(updatedAt), 0) FROM album_ratings " +
            "WHERE profileId = :profileId AND provider = :provider",
    )
    fun observeChangeStamp(provider: String, profileId: String): Flow<Long>

    @Upsert
    suspend fun upsert(rating: AlbumRating)

    /**
     * A NeoDB push landed the rating [sentRating]: clears its dirty flag only if the row still holds that rating.
     * A score set while the push was in flight stays dirty and goes with the next push (never overwritten by
     * the push's snapshot). Returns the rows changed (0: it moved on).
     */
    @Query(
        "UPDATE album_ratings SET ratingNeedsSync = 0, updatedAt = :now " +
            "WHERE profileId = :profileId AND albumId = :albumId AND provider = :provider AND rating = :sentRating",
    )
    suspend fun markRatingPushed(albumId: String, provider: String, profileId: String, sentRating: Float, now: Long): Int

    /**
     * A NeoDB push landed the text [sentReview] (null: the clear): clears its dirty flag and keeps the remote
     * review's [reviewUuid] only if the row still holds those words; words written meanwhile stay dirty.
     */
    @Query(
        "UPDATE album_ratings SET reviewNeedsSync = 0, neoDbReviewUuid = :reviewUuid, updatedAt = :now " +
            "WHERE profileId = :profileId AND albumId = :albumId AND provider = :provider AND review IS :sentReview",
    )
    suspend fun markReviewPushed(
        albumId: String,
        provider: String,
        profileId: String,
        sentReview: String?,
        reviewUuid: String?,
        now: Long,
    ): Int

    @Query(
        "DELETE FROM album_ratings " +
            "WHERE profileId = :profileId AND albumId = :albumId AND provider = :provider",
    )
    suspend fun delete(albumId: String, provider: String, profileId: String)
}
