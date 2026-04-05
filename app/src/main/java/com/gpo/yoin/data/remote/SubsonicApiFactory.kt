package com.gpo.yoin.data.remote

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
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
        val rawUrl = credentials.serverUrl.trim().trimEnd('/')
        // Use placeholder when no server is configured to avoid Retrofit crash
        val baseUrl = rawUrl.ifBlank { "http://localhost" }

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
        return buildAuthenticatedRestUrl(
            credentials = credentials,
            endpoint = "stream.view",
            queryParameters =
                buildList {
                    add("id" to songId)
                    maxBitRate?.let { add("maxBitRate" to it.toString()) }
                    format?.let { add("format" to it) }
                },
        )
    }

    /**
     * Builds a cover art URL for direct use with Coil image loading.
     */
    fun buildCoverArtUrl(
        credentials: ServerCredentials,
        coverArtId: String,
        size: Int? = null,
    ): String {
        return buildAuthenticatedRestUrl(
            credentials = credentials,
            endpoint = "getCoverArt.view",
            queryParameters =
                buildList {
                    add("id" to coverArtId)
                    size?.let { add("size" to it.toString()) }
                },
        )
    }

    private fun buildAuthenticatedRestUrl(
        credentials: ServerCredentials,
        endpoint: String,
        queryParameters: List<Pair<String, String>>,
    ): String {
        val (token, salt) = SubsonicAuth.stableToken(credentials)
        val baseUrl = credentials.serverUrl.trim().trimEnd('/').toHttpUrl()
        return baseUrl.newBuilder()
            .addPathSegment("rest")
            .addPathSegment(endpoint)
            .apply {
                queryParameters.forEach { (key, value) ->
                    addQueryParameter(key, value)
                }
                addQueryParameter("u", credentials.username)
                addQueryParameter("t", token)
                addQueryParameter("s", salt)
                addQueryParameter("v", SubsonicInterceptor.API_VERSION)
                addQueryParameter("c", SubsonicInterceptor.CLIENT_NAME)
                addQueryParameter("f", SubsonicInterceptor.RESPONSE_FORMAT)
            }
            .build()
            .toString()
    }
}
