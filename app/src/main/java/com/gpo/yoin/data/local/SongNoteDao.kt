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

    @Query(
        "SELECT * FROM song_notes " +
            "WHERE profileId = :profileId AND provider = :provider " +
            "ORDER BY updatedAt DESC LIMIT :limit",
    )
    suspend fun getRecent(provider: String, profileId: String, limit: Int): List<SongNote>

    /**
     * Non-blank notes the profile wrote on [provider]: song notes + album notes.
     * Matches AlbumMemoryCandidate.noteCount's definition.
     */
    @Query(
        "SELECT " +
            "(SELECT COUNT(*) FROM song_notes " +
            "WHERE profileId = :profileId AND provider = :provider AND TRIM(content) != '') + " +
            "(SELECT COUNT(*) FROM album_notes " +
            "WHERE profileId = :profileId AND provider = :provider AND TRIM(content) != '')",
    )
    suspend fun countNonBlankNotes(provider: String, profileId: String): Int

    /**
     * Change stamp: re-emits whenever the profile's notes change. COUNT catches
     * deletes, MAX(updatedAt) catches inserts/edits — together any write moves
     * the value. Cheap enough to observe permanently.
     */
    @Query(
        "SELECT COUNT(*) + IFNULL(MAX(updatedAt), 0) FROM song_notes " +
            "WHERE profileId = :profileId AND provider = :provider",
    )
    fun observeChangeStamp(provider: String, profileId: String): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: SongNote)

    @Update
    suspend fun update(note: SongNote)

    @Query("DELETE FROM song_notes WHERE id = :id")
    suspend fun deleteById(id: String)
}
