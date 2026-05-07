package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumNoteDao {
    @Query(
        "SELECT * FROM album_notes " +
            "WHERE profileId = :profileId AND albumId = :albumId AND provider = :provider " +
            "ORDER BY createdAt ASC",
    )
    fun observeForAlbum(albumId: String, provider: String, profileId: String): Flow<List<AlbumNote>>

    @Query(
        "SELECT DISTINCT profileId, albumId, provider FROM album_notes " +
            "WHERE profileId = :profileId AND provider = :provider AND albumId IN (:albumIds)",
    )
    fun observeKeys(
        albumIds: List<String>,
        provider: String,
        profileId: String,
    ): Flow<List<AlbumNoteKey>>

    @Query(
        "SELECT * FROM album_notes " +
            "WHERE profileId = :profileId AND albumId = :albumId AND provider = :provider " +
            "ORDER BY createdAt ASC",
    )
    suspend fun getForAlbum(albumId: String, provider: String, profileId: String): List<AlbumNote>

    @Query(
        "SELECT albumId, provider, COUNT(*) AS noteCount FROM album_notes " +
            "WHERE profileId = :profileId AND provider = :provider " +
            "GROUP BY albumId, provider",
    )
    suspend fun getNoteCountsForProfile(provider: String, profileId: String): List<AlbumNoteCount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: AlbumNote)

    @Update
    suspend fun update(note: AlbumNote)

    @Query("DELETE FROM album_notes WHERE id = :id")
    suspend fun deleteById(id: String)
}

data class AlbumNoteCount(
    val albumId: String,
    val provider: String,
    val noteCount: Int,
)
