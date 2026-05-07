package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SongNoteDao {
    @Query(
        "SELECT * FROM song_notes " +
            "WHERE profileId = :profileId AND trackId = :trackId AND provider = :provider " +
            "ORDER BY createdAt DESC",
    )
    fun observeForTrack(trackId: String, provider: String, profileId: String): Flow<List<SongNote>>

    @Query(
        "SELECT * FROM song_notes " +
            "WHERE profileId = :profileId AND title = :title AND artist = :artist " +
            "AND NOT (trackId = :trackId AND provider = :provider) " +
            "ORDER BY updatedAt DESC",
    )
    fun observeCrossProvider(
        title: String,
        artist: String,
        trackId: String,
        provider: String,
        profileId: String,
    ): Flow<List<SongNote>>

    @Query(
        "SELECT DISTINCT profileId, trackId, provider FROM song_notes " +
            "WHERE profileId = :profileId AND provider = :provider AND trackId IN (:trackIds)",
    )
    fun observeKeys(
        trackIds: List<String>,
        provider: String,
        profileId: String,
    ): Flow<List<SongNoteKey>>

    @Query(
        "SELECT * FROM song_notes " +
            "WHERE profileId = :profileId AND provider = :provider AND trackId IN (:trackIds)",
    )
    suspend fun getForTracks(
        trackIds: List<String>,
        provider: String,
        profileId: String,
    ): List<SongNote>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: SongNote)

    @Update
    suspend fun update(note: SongNote)

    @Query("DELETE FROM song_notes WHERE id = :id")
    suspend fun deleteById(id: String)
}
