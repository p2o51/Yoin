package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SpotifyLibraryCacheDao {
    @Query("SELECT * FROM spotify_library_sync_meta WHERE profileId = :profileId LIMIT 1")
    suspend fun getSyncMeta(profileId: String): SpotifyLibrarySyncMeta?

    @Query("SELECT * FROM spotify_library_sync_meta")
    suspend fun getAllSyncMeta(): List<SpotifyLibrarySyncMeta>

    @Upsert
    suspend fun upsertSyncMeta(meta: SpotifyLibrarySyncMeta)

    @Query(
        "SELECT * FROM spotify_library_track_cache " +
            "WHERE profileId = :profileId AND cachedAt >= :minCachedAt " +
            "ORDER BY addedAt DESC, title COLLATE NOCASE ASC",
    )
    suspend fun getFreshTracks(
        profileId: String,
        minCachedAt: Long,
    ): List<SpotifyLibraryTrackCache>

    @Query(
        "SELECT * FROM spotify_library_album_cache " +
            "WHERE profileId = :profileId AND cachedAt >= :minCachedAt " +
            "ORDER BY name COLLATE NOCASE ASC",
    )
    suspend fun getFreshAlbums(
        profileId: String,
        minCachedAt: Long,
    ): List<SpotifyLibraryAlbumCache>

    @Query(
        "SELECT * FROM spotify_library_artist_cache " +
            "WHERE profileId = :profileId AND cachedAt >= :minCachedAt " +
            "ORDER BY name COLLATE NOCASE ASC",
    )
    suspend fun getFreshArtists(
        profileId: String,
        minCachedAt: Long,
    ): List<SpotifyLibraryArtistCache>

    @Query(
        "SELECT * FROM spotify_library_playlist_cache " +
            "WHERE profileId = :profileId AND cachedAt >= :minCachedAt " +
            "ORDER BY name COLLATE NOCASE ASC",
    )
    suspend fun getFreshPlaylists(
        profileId: String,
        minCachedAt: Long,
    ): List<SpotifyLibraryPlaylistCache>

    @Query(
        "SELECT * FROM spotify_library_track_cache " +
            "WHERE profileId = :profileId AND trackId = :trackId LIMIT 1",
    )
    suspend fun getTrack(profileId: String, trackId: String): SpotifyLibraryTrackCache?

    /** Reactive saved-state for one track — drives the Now Playing heart. */
    @Query(
        "SELECT * FROM spotify_library_track_cache " +
            "WHERE profileId = :profileId AND trackId = :trackId LIMIT 1",
    )
    fun observeTrack(profileId: String, trackId: String): Flow<SpotifyLibraryTrackCache?>

    @Query(
        "SELECT * FROM spotify_library_track_cache " +
            "WHERE profileId = :profileId AND pendingFavoriteAction = 1",
    )
    suspend fun getPendingTracks(profileId: String): List<SpotifyLibraryTrackCache>

    @Upsert
    suspend fun upsertTrack(track: SpotifyLibraryTrackCache)

    @Query(
        "UPDATE spotify_library_track_cache " +
            "SET isSaved = :isSaved, pendingFavoriteAction = :pending, " +
            "lastSyncError = :lastSyncError, cachedAt = :cachedAt " +
            "WHERE profileId = :profileId AND trackId = :trackId",
    )
    suspend fun updateTrackFavoriteState(
        profileId: String,
        trackId: String,
        isSaved: Boolean,
        pending: Boolean,
        lastSyncError: String?,
        cachedAt: Long,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(items: List<SpotifyLibraryTrackCache>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbums(items: List<SpotifyLibraryAlbumCache>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtists(items: List<SpotifyLibraryArtistCache>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(items: List<SpotifyLibraryPlaylistCache>)

    @Query("DELETE FROM spotify_library_track_cache WHERE profileId = :profileId AND trackId = :trackId")
    suspend fun deleteTrack(profileId: String, trackId: String)

    @Query("DELETE FROM spotify_library_track_cache WHERE profileId = :profileId")
    suspend fun deleteTracksForProfile(profileId: String)

    @Query("DELETE FROM spotify_library_album_cache WHERE profileId = :profileId")
    suspend fun deleteAlbumsForProfile(profileId: String)

    @Query("DELETE FROM spotify_library_artist_cache WHERE profileId = :profileId")
    suspend fun deleteArtistsForProfile(profileId: String)

    @Query("DELETE FROM spotify_library_playlist_cache WHERE profileId = :profileId")
    suspend fun deletePlaylistsForProfile(profileId: String)
}
