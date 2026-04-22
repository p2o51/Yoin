package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SongAboutEntryDao {

    @Query(
        """
        SELECT * FROM song_about_entries
        WHERE titleKey = :titleKey AND artistKey = :artistKey AND albumKey = :albumKey
        ORDER BY
            CASE kind WHEN 'canonical' THEN 0 ELSE 1 END,
            updatedAt DESC
        """,
    )
    fun observe(
        titleKey: String,
        artistKey: String,
        albumKey: String,
    ): Flow<List<SongAboutEntry>>

    @Query(
        """
        SELECT * FROM song_about_entries
        WHERE titleKey = :titleKey AND artistKey = :artistKey AND albumKey = :albumKey
          AND kind = 'canonical'
        """,
    )
    suspend fun getCanonical(
        titleKey: String,
        artistKey: String,
        albumKey: String,
    ): List<SongAboutEntry>

    @Query(
        """
        SELECT * FROM song_about_entries
        WHERE titleKey = :titleKey AND artistKey = :artistKey AND albumKey = :albumKey
          AND kind = 'ask' AND entryKey = :questionKey
        LIMIT 1
        """,
    )
    suspend fun getAsk(
        titleKey: String,
        artistKey: String,
        albumKey: String,
        questionKey: String,
    ): SongAboutEntry?

    @Upsert
    suspend fun upsert(row: SongAboutEntry)

    @Upsert
    suspend fun upsertAll(rows: List<SongAboutEntry>)

    @Query("DELETE FROM song_about_entries")
    suspend fun deleteAll()
}
