package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SpotifyHomeCacheDao {
    @Query(
        "SELECT * FROM spotify_home_album_cache " +
            "WHERE profileId = :profileId AND cachedAt >= :minCachedAt " +
            "ORDER BY sortOrder ASC",
    )
    suspend fun getFreshAlbums(
        profileId: String,
        minCachedAt: Long,
    ): List<SpotifyHomeAlbumCache>

    @Query(
        "SELECT * FROM spotify_home_artist_cache " +
            "WHERE profileId = :profileId AND cachedAt >= :minCachedAt " +
            "ORDER BY sortOrder ASC",
    )
    suspend fun getFreshArtists(
        profileId: String,
        minCachedAt: Long,
    ): List<SpotifyHomeArtistCache>

    /** The cached rows of [artistIds] (MediaId strings) for [profileId], any age. */
    @Query(
        "SELECT * FROM spotify_home_artist_cache " +
            "WHERE profileId = :profileId AND artistId IN (:artistIds)"
    )
    suspend fun getArtists(profileId: String, artistIds: List<String>): List<SpotifyHomeArtistCache>

    @Query("DELETE FROM spotify_home_artist_cache WHERE profileId = :profileId AND cachedAt < :before")
    suspend fun deleteArtistsCachedBefore(profileId: String, before: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbums(items: List<SpotifyHomeAlbumCache>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtists(items: List<SpotifyHomeArtistCache>)

    @Query("DELETE FROM spotify_home_album_cache WHERE profileId = :profileId")
    suspend fun deleteAlbumsForProfile(profileId: String)

    @Query("DELETE FROM spotify_home_artist_cache WHERE profileId = :profileId")
    suspend fun deleteArtistsForProfile(profileId: String)
}
