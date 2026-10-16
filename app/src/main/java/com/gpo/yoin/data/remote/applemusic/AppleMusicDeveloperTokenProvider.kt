package com.gpo.yoin.data.remote.applemusic

import com.gpo.yoin.perf.YoinPerfHttp
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Fetches a signed developer JWT from a configured HTTPS service; never receives a .p8 key. */
class AppleMusicDeveloperTokenProvider(
    private val endpoint: HttpUrl,
    transport: OkHttpClient = OkHttpClient(),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    private val http = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .apply { YoinPerfHttp.interceptor()?.let(::addInterceptor) } // debug-only timing; never the query
        .build()
    private val mutex = Mutex()
    private var cached: String? = null
    private var expiresAt: Long = 0

    init {
        require(endpoint.isHttps || endpoint.host in setOf("localhost", "127.0.0.1", "::1")) {
            "Apple Music token endpoint must use HTTPS"
        }
        require(endpoint.username.isEmpty() && endpoint.password.isEmpty())
    }

    /** A token with more than [minValiditySeconds] left: the cached one while it has that long, else a new one. */
    suspend fun token(minValiditySeconds: Long = 60): String = mutex.withLock {
        cached?.takeIf { expiresAt > nowEpochSeconds() + minValiditySeconds }?.let { return@withLock it }
        val token = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(endpoint).header("Accept", "application/json").build())
                .execute().use { response ->
                    if (response.code != 200) {
                        throw IOException(
                            "Developer token service unavailable (${response.code})"
                        )
                    }
                    val body = response.body.string()
                    try {
                        Json.parseToJsonElement(body).jsonObject
                            .get("developerToken")?.jsonPrimitive?.contentOrNull
                            ?: throw IOException("Developer token service returned no token")
                    } catch (_: IllegalArgumentException) {
                        // JSON parser errors may quote the token service's response body.
                        throw IOException("Developer token service returned an invalid response")
                    }
                }
        }
        val expiry = developerTokenExpiry(token)
            ?: throw IOException("Developer token service returned an invalid token")
        if (expiry <= nowEpochSeconds() + 60) throw IOException("Developer token is expired or expires too soon")
        cached = token
        expiresAt = expiry
        token
    }

    suspend fun invalidate() = mutex.withLock {
        cached = null
        expiresAt = 0
    }
}

/**
 * The `exp` (epoch seconds) of an ES256 developer JWT, or null for anything else. Only a freshness/shape check:
 * Apple verifies the signature.
 */
fun developerTokenExpiry(token: String): Long? = runCatching {
    val parts = token.split('.')
    require(parts.size == 3 && parts.all { it.isNotBlank() })
    val header = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[0]))).jsonObject
    require(header["alg"]?.jsonPrimitive?.contentOrNull == "ES256")
    val payload = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]))).jsonObject
    requireNotNull(payload["exp"]?.jsonPrimitive?.longOrNull)
}.getOrNull()
