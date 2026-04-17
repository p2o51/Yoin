package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalRatingDao {
    @Query("SELECT * FROM local_ratings WHERE songId = :songId AND provider = :provider")
    fun getRating(songId: String, provider: String): Flow<LocalRating?>

    @Query("SELECT * FROM local_ratings WHERE provider = :provider AND songId IN (:songIds)")
    suspend fun getRatings(songIds: List<String>, provider: String): List<LocalRating>

    @Query("SELECT * FROM local_ratings WHERE needsSync = 1")
    fun getRatingsNeedingSync(): Flow<List<LocalRating>>

    @Upsert
    suspend fun upsert(rating: LocalRating)

    @Query("DELETE FROM local_ratings")
    suspend fun deleteAll()
}
