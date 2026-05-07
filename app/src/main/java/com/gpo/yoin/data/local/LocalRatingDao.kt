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

    @Query(
        "SELECT * FROM local_ratings " +
            "WHERE profileId = :profileId AND provider = :provider AND needsSync = 1",
    )
    fun getRatingsNeedingSync(provider: String, profileId: String): Flow<List<LocalRating>>

    @Upsert
    suspend fun upsert(rating: LocalRating)

    @Query("DELETE FROM local_ratings")
    suspend fun deleteAll()
}
