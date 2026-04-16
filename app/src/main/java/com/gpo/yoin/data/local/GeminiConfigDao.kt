package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface GeminiConfigDao {

    @Query("SELECT * FROM gemini_config WHERE id = 1 LIMIT 1")
    fun getConfig(): Flow<GeminiConfig?>

    @Upsert
    suspend fun upsert(config: GeminiConfig)
}
