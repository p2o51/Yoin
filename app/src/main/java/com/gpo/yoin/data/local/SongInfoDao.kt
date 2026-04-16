package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SongInfoDao {

    @Query("SELECT * FROM song_info WHERE songId = :songId LIMIT 1")
    suspend fun getBySongId(songId: String): SongInfo?

    @Upsert
    suspend fun upsert(songInfo: SongInfo)

    @Query("DELETE FROM song_info")
    suspend fun deleteAll()
}
