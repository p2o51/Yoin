package com.gpo.yoin.data.remote

import okhttp3.Interceptor
import okhttp3.Response

class SubsonicInterceptor(
    private val credentialsProvider: () -> ServerCredentials,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val credentials = credentialsProvider()
        val (token, salt) = SubsonicAuth.generateToken(credentials.password)

        val originalUrl = chain.request().url
        val newUrl =
            originalUrl.newBuilder()
                .addQueryParameter("u", credentials.username)
                .addQueryParameter("t", token)
                .addQueryParameter("s", salt)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("f", RESPONSE_FORMAT)
                .build()

        val newRequest = chain.request().newBuilder().url(newUrl).build()
        return chain.proceed(newRequest)
    }

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "Yoin"
        const val RESPONSE_FORMAT = "json"
    }
}
