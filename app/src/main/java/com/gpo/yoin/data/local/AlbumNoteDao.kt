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
            "WHERE albumId = :albumId AND provider = :provider " +
            "ORDER BY createdAt ASC",
    )
    fun observeForAlbum(albumId: String, provider: String): Flow<List<AlbumNote>>

    @Query(
        "SELECT DISTINCT albumId, provider FROM album_notes " +
            "WHERE provider = :provider AND albumId IN (:albumIds)",
    )
    fun observeKeys(
        albumIds: List<String>,
        provider: String,
    ): Flow<List<AlbumNoteKey>>

    @Query(
        "SELECT * FROM album_notes " +
            "WHERE albumId = :albumId AND provider = :provider " +
            "ORDER BY createdAt ASC",
    )
    suspend fun getForAlbum(albumId: String, provider: String): List<AlbumNote>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: AlbumNote)

    @Update
    suspend fun update(note: AlbumNote)

    @Query("DELETE FROM album_notes WHERE id = :id")
    suspend fun deleteById(id: String)
}
