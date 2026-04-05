package com.gpo.yoin.data.remote

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object SubsonicApiFactory {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun create(
        credentialsProvider: () -> ServerCredentials,
        loggingEnabled: Boolean = false,
    ): SubsonicApi {
        val credentials = credentialsProvider()
        val baseUrl = credentials.serverUrl.trimEnd('/')

        val client =
            OkHttpClient.Builder()
                .addInterceptor(SubsonicInterceptor(credentialsProvider))
                .apply {
                    if (loggingEnabled) {
                        addInterceptor(
                            HttpLoggingInterceptor().apply {
                                level = HttpLoggingInterceptor.Level.BODY
                            },
                        )
                    }
                }
                .build()

        val contentType = "application/json".toMediaType()

        return Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(SubsonicApi::class.java)
    }

    /**
     * Builds a stream URL for direct use with Media3 / ExoPlayer.
     */
    fun buildStreamUrl(
        credentials: ServerCredentials,
        songId: String,
        maxBitRate: Int? = null,
        format: String? = null,
    ): String {
        val (token, salt) = SubsonicAuth.generateToken(credentials.password)
        val base = credentials.serverUrl.trimEnd('/')
        return buildString {
            append("$base/rest/stream")
            append("?id=$songId")
            append("&u=${credentials.username}")
            append("&t=$token")
            append("&s=$salt")
            append("&v=${SubsonicInterceptor.API_VERSION}")
            append("&c=${SubsonicInterceptor.CLIENT_NAME}")
            append("&f=${SubsonicInterceptor.RESPONSE_FORMAT}")
            maxBitRate?.let { append("&maxBitRate=$it") }
            format?.let { append("&format=$it") }
        }
    }

    /**
     * Builds a cover art URL for direct use with Coil image loading.
     */
    fun buildCoverArtUrl(
        credentials: ServerCredentials,
        coverArtId: String,
        size: Int? = null,
    ): String {
        val (token, salt) = SubsonicAuth.generateToken(credentials.password)
        val base = credentials.serverUrl.trimEnd('/')
        return buildString {
            append("$base/rest/getCoverArt")
            append("?id=$coverArtId")
            append("&u=${credentials.username}")
            append("&t=$token")
            append("&s=$salt")
            append("&v=${SubsonicInterceptor.API_VERSION}")
            append("&c=${SubsonicInterceptor.CLIENT_NAME}")
            append("&f=${SubsonicInterceptor.RESPONSE_FORMAT}")
            size?.let { append("&size=$it") }
        }
    }
}
