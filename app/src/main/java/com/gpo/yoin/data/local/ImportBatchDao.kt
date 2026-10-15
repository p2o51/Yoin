package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportBatchDao {
    @Upsert
    suspend fun upsert(batch: ImportBatch)

    @Query("SELECT * FROM import_batches WHERE batchId = :batchId")
    suspend fun get(batchId: String): ImportBatch?

    @Query("SELECT * FROM import_batches WHERE status = 'staged' ORDER BY importedAt DESC")
    suspend fun getStaged(): List<ImportBatch>

    @Query("SELECT * FROM import_batches WHERE status = 'committed' AND fileSha256 = :fileSha256 LIMIT 1")
    suspend fun findCommittedByFile(fileSha256: String): ImportBatch?

    /** The newest batch written to [profileId]: Settings › About shows the recap entry while there is one. */
    @Query(
        "SELECT * FROM import_batches WHERE status = 'committed' AND profileId = :profileId " +
            "ORDER BY importedAt DESC LIMIT 1",
    )
    fun observeLatestCommitted(profileId: String): Flow<ImportBatch?>

    @Query("UPDATE import_batches SET status = 'committed', profileId = :profileId WHERE batchId = :batchId")
    suspend fun markCommitted(batchId: String, profileId: String): Int

    @Query("DELETE FROM import_batches WHERE batchId = :batchId")
    suspend fun delete(batchId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<ImportItem>)

    @Query("SELECT targetKey FROM import_items WHERE batchId = :batchId AND targetKind = :targetKind")
    suspend fun getItemKeys(batchId: String, targetKind: String): List<String>

    @Query("DELETE FROM import_items WHERE batchId = :batchId")
    suspend fun deleteItems(batchId: String)

    /**
     * Everything the user already keeps in [profileId] (docs/spotoolfy-migration.md §4.1): song notes, track
     * ratings, album ratings and reviews, album notes, and earlier imports. An import only goes into an account
     * where this is 0.
     */
    @Query(
        "SELECT " +
            "(SELECT COUNT(*) FROM song_notes WHERE profileId = :profileId) + " +
            "(SELECT COUNT(*) FROM local_ratings WHERE profileId = :profileId) + " +
            "(SELECT COUNT(*) FROM album_ratings WHERE profileId = :profileId) + " +
            "(SELECT COUNT(*) FROM album_notes WHERE profileId = :profileId) + " +
            "(SELECT COUNT(*) FROM import_batches WHERE profileId = :profileId AND status = 'committed')",
    )
    suspend fun countUserData(profileId: String): Int
}
