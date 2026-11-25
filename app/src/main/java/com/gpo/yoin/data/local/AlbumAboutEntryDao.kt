package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumAboutEntryDao {

    /** Every row of one album: canonical first, then questions newest first. */
    @Query(
        """
        SELECT * FROM album_about_entries
        WHERE provider = :provider AND albumId = :albumId
        ORDER BY CASE kind WHEN 'canonical' THEN 0 ELSE 1 END, createdAt DESC
        """,
    )
    fun observe(provider: String, albumId: String): Flow<List<AlbumAboutEntry>>

    @Query(
        """
        SELECT * FROM album_about_entries
        WHERE provider = :provider AND albumId = :albumId AND kind = 'canonical'
        """,
    )
    suspend fun getCanonical(provider: String, albumId: String): List<AlbumAboutEntry>

    @Query(
        """
        SELECT * FROM album_about_entries
        WHERE provider = :provider AND albumId = :albumId AND kind = :kind AND entryKey = :entryKey
        LIMIT 1
        """,
    )
    suspend fun get(provider: String, albumId: String, kind: String, entryKey: String): AlbumAboutEntry?

    @Upsert
    suspend fun upsert(row: AlbumAboutEntry)

    @Upsert
    suspend fun upsertAll(rows: List<AlbumAboutEntry>)

    /** Everything is Gemini's: a language switch clears the table, like the song rows' generated ones. */
    @Query("DELETE FROM album_about_entries")
    suspend fun deleteAll()
}
