package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HomeLayoutDao {

    @Query("SELECT * FROM home_layout WHERE profileId = :profileId LIMIT 1")
    fun getForProfile(profileId: String): Flow<HomeLayoutPreference?>

    /** [getForProfile] read once, without observing (a one-shot query: no invalidation tracking). */
    @Query("SELECT * FROM home_layout WHERE profileId = :profileId LIMIT 1")
    suspend fun getOnceForProfile(profileId: String): HomeLayoutPreference?

    @Upsert
    suspend fun upsert(preference: HomeLayoutPreference)

    @Query("DELETE FROM home_layout WHERE profileId = :profileId")
    suspend fun delete(profileId: String)
}
