package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistBackupDao {
    /** Latest change first: Settings › Playlist cloud backup. */
    @Query("SELECT * FROM playlist_backups ORDER BY lastChangedAt DESC, id")
    fun observeAll(): Flow<List<PlaylistBackup>>

    @Query("SELECT * FROM playlist_backups WHERE id = :id")
    fun observe(id: String): Flow<PlaylistBackup?>

    @Query("SELECT * FROM playlist_backups WHERE id = :id")
    suspend fun get(id: String): PlaylistBackup?

    @Query("SELECT * FROM playlist_backups ORDER BY id")
    suspend fun getAll(): List<PlaylistBackup>

    /**
     * The backups of a playlist with id [sourcePlaylistId] on [sourceProvider], oldest first: one per account
     * (two Subsonic servers can both have a playlist "1"), more only if a sync raced. Which one belongs to a
     * profile is PlaylistBackupService's call.
     */
    @Query(
        "SELECT * FROM playlist_backups WHERE sourceProvider = :sourceProvider AND sourcePlaylistId = :sourcePlaylistId " +
            "ORDER BY firstBackedUpAt, id",
    )
    suspend fun findBySource(sourceProvider: String, sourcePlaylistId: String): List<PlaylistBackup>

    @Upsert
    suspend fun upsert(backup: PlaylistBackup)

    @Query("UPDATE playlist_backups SET restoresJson = :restoresJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateRestores(id: String, restoresJson: String, updatedAt: Long): Int

    @Query("DELETE FROM playlist_backups WHERE id = :id")
    suspend fun delete(id: String)
}
