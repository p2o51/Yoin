package com.gpo.yoin.data.remote

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Streaming

interface SubsonicApi {
    @GET("rest/ping.view")
    suspend fun ping(): SubsonicResponse

    @GET("rest/getAlbumList2.view")
    suspend fun getAlbumList2(
        @Query("type") type: String,
        @Query("size") size: Int? = null,
        @Query("offset") offset: Int? = null,
    ): SubsonicResponse

    @GET("rest/getAlbum.view")
    suspend fun getAlbum(
        @Query("id") id: String,
    ): SubsonicResponse

    @GET("rest/getArtists.view")
    suspend fun getArtists(): SubsonicResponse

    @GET("rest/getArtist.view")
    suspend fun getArtist(
        @Query("id") id: String,
    ): SubsonicResponse

    @GET("rest/search3.view")
    suspend fun search3(
        @Query("query") query: String,
        @Query("artistCount") artistCount: Int? = null,
        @Query("albumCount") albumCount: Int? = null,
        @Query("songCount") songCount: Int? = null,
    ): SubsonicResponse

    @Streaming
    @GET("rest/stream.view")
    suspend fun stream(
        @Query("id") id: String,
        @Query("maxBitRate") maxBitRate: Int? = null,
        @Query("format") format: String? = null,
    ): ResponseBody

    @Streaming
    @GET("rest/getCoverArt.view")
    suspend fun getCoverArt(
        @Query("id") id: String,
        @Query("size") size: Int? = null,
    ): ResponseBody

    @GET("rest/getLyricsBySongId.view")
    suspend fun getLyricsBySongId(
        @Query("id") id: String,
    ): SubsonicResponse

    @GET("rest/star.view")
    suspend fun star(
        @Query("id") id: String? = null,
        @Query("albumId") albumId: String? = null,
        @Query("artistId") artistId: String? = null,
    ): SubsonicResponse

    @GET("rest/unstar.view")
    suspend fun unstar(
        @Query("id") id: String? = null,
        @Query("albumId") albumId: String? = null,
        @Query("artistId") artistId: String? = null,
    ): SubsonicResponse

    @GET("rest/getStarred2.view")
    suspend fun getStarred2(): SubsonicResponse

    @GET("rest/getRandomSongs.view")
    suspend fun getRandomSongs(
        @Query("size") size: Int? = null,
    ): SubsonicResponse

    @GET("rest/getPlaylists.view")
    suspend fun getPlaylists(
        @Query("username") username: String? = null,
    ): SubsonicResponse

    @GET("rest/getPlaylist.view")
    suspend fun getPlaylist(
        @Query("id") id: String,
    ): SubsonicResponse

    @GET("rest/setRating.view")
    suspend fun setRating(
        @Query("id") id: String,
        @Query("rating") rating: Int,
    ): SubsonicResponse

    @GET("rest/createPlaylist.view")
    suspend fun createPlaylist(
        @Query("name") name: String,
    ): SubsonicResponse

    /**
     * Subsonic's `updatePlaylist.view` is a swiss-army endpoint: pass
     * [name] to rename; [comment] to re-describe; [songIdToAdd] to append
     * tracks (repeatable); [songIndexToRemove] to delete by zero-based
     * position (repeatable). Retrofit serialises repeated query params
     * correctly when given a `List<String>` / `List<Int>`.
     */
    @GET("rest/updatePlaylist.view")
    suspend fun updatePlaylist(
        @Query("playlistId") playlistId: String,
        @Query("name") name: String? = null,
        @Query("comment") comment: String? = null,
        @Query("songIdToAdd") songIdToAdd: List<String>? = null,
        @Query("songIndexToRemove") songIndexToRemove: List<Int>? = null,
    ): SubsonicResponse

    @GET("rest/deletePlaylist.view")
    suspend fun deletePlaylist(
        @Query("id") id: String,
    ): SubsonicResponse
}
