package com.gpo.yoin.data.remote.musicbrainz

import com.gpo.yoin.data.source.TrackNames
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The names a recording was released under, by its ISRC, from MusicBrainz (`GET /ws/2/isrc/{isrc}`): free, no
 * key. Spotify names Hebe Tien's 《摩天輪》 "Ferries wheel" in every language, but its ISRC TWD950519106 is
 * 摩天輪 / 田馥甄 there, 244 800 ms like Spotify's. Lyrics sources index those names.
 *
 * MusicBrainz asks for at most one request a second and a User-Agent naming the app; requests here wait their
 * turn. Answers are kept per ISRC, misses too; a failed request isn't kept.
 */
class MusicBrainzIsrcClient(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: HttpUrl = "https://musicbrainz.org/".toHttpUrl(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val turn = Mutex()
    private var lastRequestAt = 0L
    private val namesByIsrc = object : LinkedHashMap<String, TrackNames?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackNames?>?): Boolean =
            size > KEPT
    }

    /**
     * The recording's title and first credited artist. With several recordings under one ISRC, the one whose
     * length is within [LENGTH_TOLERANCE_MS] of [durationMs] (when both are known), else the first. Null when
     * MusicBrainz doesn't know the code, or nothing matches the length. Throws on a network or server failure.
     */
    suspend fun names(isrc: String, durationMs: Long?): TrackNames? {
        val code = isrc.trim().uppercase().takeIf { it.matches(ISRC_PATTERN) } ?: return null
        synchronized(namesByIsrc) { if (code in namesByIsrc) return namesByIsrc[code] }
        val body = turn.withLock {
            val wait = lastRequestAt + MIN_GAP_MS - clock()
            if (wait > 0) delay(wait)
            try {
                fetch(code)
            } finally {
                lastRequestAt = clock()
            }
        }
        val names = body?.let { parse(it, durationMs) }
        synchronized(namesByIsrc) { namesByIsrc[code] = names }
        return names
    }

    /** The response body, or null for a code MusicBrainz doesn't have (404). */
    private suspend fun fetch(code: String): String? {
        val url = baseUrl.newBuilder()
            .addPathSegments("ws/2/isrc")
            .addPathSegment(code)
            .addQueryParameter("fmt", "json")
            .addQueryParameter("inc", "artist-credits")
            .build()
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        return client.await(request).use { response ->
            when {
                response.code == HTTP_NOT_FOUND -> null
                !response.isSuccessful -> throw IOException("MusicBrainz ${response.code}")
                else -> response.body.string()
            }
        }
    }

    internal fun parse(body: String, durationMs: Long?): TrackNames? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val recordings = (root["recordings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val named = recordings.mapNotNull { recording ->
            val title = recording.string("title")?.trim().orEmpty()
            val credits = (recording["artist-credit"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val artist = credits.firstOrNull()?.string("name")?.trim().orEmpty()
            val length = (recording["length"] as? JsonPrimitive)?.longOrNull
            if (title.isEmpty() || artist.isEmpty()) null else TrackNames(title, artist) to length
        }
        if (named.isEmpty()) return null
        if (durationMs == null || durationMs <= 0) return named.first().first
        val fitting = named.filter { (_, length) -> length == null || abs(length - durationMs) <= LENGTH_TOLERANCE_MS }
        // A known length that fits beats an unknown one.
        return (fitting.firstOrNull { it.second != null } ?: fitting.firstOrNull())?.first
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** Names `Yoin` and where to reach it, as MusicBrainz's rate-limit rules ask. */
        const val USER_AGENT = "Yoin/1 ( https://github.com/p2o51/Yoin )"
        private const val MIN_GAP_MS = 1_100L
        private const val LENGTH_TOLERANCE_MS = 3_000L
        private const val HTTP_NOT_FOUND = 404
        private const val KEPT = 256
        private val ISRC_PATTERN = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")

        private fun defaultClient(): OkHttpClient = com.gpo.yoin.data.remote.YoinNetworkCore.newApiBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/** An enqueued call that cancels with its coroutine. The caller closes the response. */
private suspend fun OkHttpClient.await(request: Request): Response = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { runCatching { call.cancel() } }
    call.enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) { _, _, _ -> response.close() } else response.close()
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        },
    )
}
