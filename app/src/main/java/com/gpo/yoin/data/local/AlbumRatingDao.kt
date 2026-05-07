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
            "WHERE profileId = :profileId AND albumId IN (:albumIds) AND provider = :provider",
    )
    suspend fun getAll(
        albumIds: List<String>,
        provider: String,
        profileId: String,
    ): List<AlbumRating>

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

    @Upsert
    suspend fun upsert(rating: AlbumRating)

    @Query(
        "DELETE FROM album_ratings " +
            "WHERE profileId = :profileId AND albumId = :albumId AND provider = :provider",
    )
    suspend fun delete(albumId: String, provider: String, profileId: String)
}
