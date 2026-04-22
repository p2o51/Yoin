package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NeoDBConfigDao {
    @Query("SELECT * FROM neodb_config WHERE id = 1 LIMIT 1")
    fun observe(): Flow<NeoDBConfig?>

    @Query("SELECT * FROM neodb_config WHERE id = 1 LIMIT 1")
    suspend fun get(): NeoDBConfig?

    @Upsert
    suspend fun upsert(config: NeoDBConfig)

    @Query("DELETE FROM neodb_config")
    suspend fun clear()
}
