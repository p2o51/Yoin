package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumRatingDao {
    @Query(
        "SELECT * FROM album_ratings " +
            "WHERE albumId = :albumId AND provider = :provider",
    )
    fun observe(albumId: String, provider: String): Flow<AlbumRating?>

    @Query(
        "SELECT * FROM album_ratings " +
            "WHERE albumId = :albumId AND provider = :provider",
    )
    suspend fun get(albumId: String, provider: String): AlbumRating?

    @Query(
        "SELECT * FROM album_ratings " +
            "WHERE albumId IN (:albumIds) AND provider = :provider",
    )
    suspend fun getAll(albumIds: List<String>, provider: String): List<AlbumRating>

    @Query(
        "SELECT * FROM album_ratings " +
            "WHERE ratingNeedsSync = 1 OR reviewNeedsSync = 1",
    )
    fun observePending(): Flow<List<AlbumRating>>

    @Upsert
    suspend fun upsert(rating: AlbumRating)

    @Query(
        "DELETE FROM album_ratings " +
            "WHERE albumId = :albumId AND provider = :provider",
    )
    suspend fun delete(albumId: String, provider: String)
}
