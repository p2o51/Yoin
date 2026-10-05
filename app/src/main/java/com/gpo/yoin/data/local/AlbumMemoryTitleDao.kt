package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The user's album Memory titles ([AlbumMemoryTitle]). Every query is scoped by profile, and the
 * single-row ones by provider and album too (AGENTS: remote ids are filtered on id AND provider).
 * Feature code goes through `AlbumMemoryTitleStore`; the sync adapter reads and writes here directly.
 */
@Dao
interface AlbumMemoryTitleDao {
    /** The user's title for one album, live; null while there is none (Yoin's own title shows). */
    @Query(
        "SELECT * FROM album_memory_titles " +
            "WHERE profileId = :profileId AND provider = :provider AND albumId = :albumId " +
            "LIMIT 1",
    )
    fun observe(profileId: String, provider: String, albumId: String): Flow<AlbumMemoryTitle?>

    /** One-shot read of [observe]. */
    @Query(
        "SELECT * FROM album_memory_titles " +
            "WHERE profileId = :profileId AND provider = :provider AND albumId = :albumId " +
            "LIMIT 1",
    )
    suspend fun get(profileId: String, provider: String, albumId: String): AlbumMemoryTitle?

    /** Insert or replace the row for its (profileId, provider, albumId). */
    @Upsert
    suspend fun upsert(title: AlbumMemoryTitle)

    /** Hard delete: the album goes back to Yoin's title. Returns the rows removed (0 or 1). */
    @Query(
        "DELETE FROM album_memory_titles " +
            "WHERE profileId = :profileId AND provider = :provider AND albumId = :albumId",
    )
    suspend fun delete(profileId: String, provider: String, albumId: String): Int

    /** Every title the profile set, across providers (the sync adapter's read). */
    @Query("SELECT * FROM album_memory_titles WHERE profileId = :profileId")
    suspend fun getAllForProfile(profileId: String): List<AlbumMemoryTitle>

    /** [getAllForProfile], live: re-emits on any write (Memories patches its open deck from it). */
    @Query("SELECT * FROM album_memory_titles WHERE profileId = :profileId")
    fun observeAllForProfile(profileId: String): Flow<List<AlbumMemoryTitle>>
}
