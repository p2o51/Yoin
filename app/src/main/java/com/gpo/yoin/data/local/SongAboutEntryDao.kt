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

    @Query(
        """
        SELECT COUNT(*) FROM song_about_entries
        WHERE titleKey = :titleKey AND artistKey = :artistKey AND albumKey = :albumKey
          AND kind = 'ask'
        """,
    )
    suspend fun countAskRows(
        titleKey: String,
        artistKey: String,
        albumKey: String,
    ): Int

    /**
     * Ask-row counts for a whole album in one query, grouped per song key —
     * batch companion to [countAskRows] so per-album consumers don't issue one
     * COUNT per track.
     */
    @Query(
        """
        SELECT titleKey, artistKey, COUNT(*) AS askCount FROM song_about_entries
        WHERE albumKey = :albumKey AND kind = 'ask'
        GROUP BY titleKey, artistKey
        """,
    )
    suspend fun countAskRowsByAlbum(albumKey: String): List<AskRowCount>

    @Upsert
    suspend fun upsert(row: SongAboutEntry)

    @Upsert
    suspend fun upsertAll(rows: List<SongAboutEntry>)

    @Query("DELETE FROM song_about_entries")
    suspend fun deleteAll()
}

data class AskRowCount(
    val titleKey: String,
    val artistKey: String,
    val askCount: Int,
)
