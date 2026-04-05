package com.gpo.yoin.data.remote

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Streaming

interface SubsonicApi {
    @GET("rest/ping")
    suspend fun ping(): SubsonicResponse

    @GET("rest/getAlbumList2")
    suspend fun getAlbumList2(
        @Query("type") type: String,
        @Query("size") size: Int? = null,
        @Query("offset") offset: Int? = null,
    ): SubsonicResponse

    @GET("rest/getAlbum")
    suspend fun getAlbum(
        @Query("id") id: String,
    ): SubsonicResponse

    @GET("rest/getArtists")
    suspend fun getArtists(): SubsonicResponse

    @GET("rest/getArtist")
    suspend fun getArtist(
        @Query("id") id: String,
    ): SubsonicResponse

    @GET("rest/search3")
    suspend fun search3(
        @Query("query") query: String,
        @Query("artistCount") artistCount: Int? = null,
        @Query("albumCount") albumCount: Int? = null,
        @Query("songCount") songCount: Int? = null,
    ): SubsonicResponse

    @Streaming
    @GET("rest/stream")
    suspend fun stream(
        @Query("id") id: String,
        @Query("maxBitRate") maxBitRate: Int? = null,
        @Query("format") format: String? = null,
    ): ResponseBody

    @Streaming
    @GET("rest/getCoverArt")
    suspend fun getCoverArt(
        @Query("id") id: String,
        @Query("size") size: Int? = null,
    ): ResponseBody

    @GET("rest/getLyricsBySongId")
    suspend fun getLyricsBySongId(
        @Query("id") id: String,
    ): SubsonicResponse

    @GET("rest/star")
    suspend fun star(
        @Query("id") id: String? = null,
        @Query("albumId") albumId: String? = null,
        @Query("artistId") artistId: String? = null,
    ): SubsonicResponse

    @GET("rest/unstar")
    suspend fun unstar(
        @Query("id") id: String? = null,
        @Query("albumId") albumId: String? = null,
        @Query("artistId") artistId: String? = null,
    ): SubsonicResponse

    @GET("rest/getStarred2")
    suspend fun getStarred2(): SubsonicResponse

    @GET("rest/getRandomSongs")
    suspend fun getRandomSongs(
        @Query("size") size: Int? = null,
    ): SubsonicResponse

    @GET("rest/setRating")
    suspend fun setRating(
        @Query("id") id: String,
        @Query("rating") rating: Int,
    ): SubsonicResponse
}
