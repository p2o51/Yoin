package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SpotifyConfigDao {

    @Query("SELECT * FROM spotify_config WHERE id = 1 LIMIT 1")
    fun getConfig(): Flow<SpotifyConfig?>

    @Upsert
    suspend fun upsert(config: SpotifyConfig)
}
