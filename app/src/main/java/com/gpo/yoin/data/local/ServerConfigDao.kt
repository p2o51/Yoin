package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerConfigDao {
    @Query("SELECT * FROM server_config WHERE isActive = 1 LIMIT 1")
    fun getActiveServer(): Flow<ServerConfig?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: ServerConfig)

    @Update
    suspend fun update(config: ServerConfig)

    @Query("DELETE FROM server_config")
    suspend fun deleteAll()
}
