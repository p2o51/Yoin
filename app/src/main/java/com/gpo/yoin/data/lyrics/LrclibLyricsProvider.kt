package com.gpo.yoin.data.lyrics

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * LRCLIB 歌词源（https://lrclib.net）—— FOSS、无 key、无速率限制，LRC 同步歌词。
 * 两次 HTTP GET：
 * 1. 搜索：`/api/search?track_name=&artist_name=`，返回数组，取第一条 `.id`
 * 2. 取词：`/api/get/{id}`，返回 `{syncedLyrics, plainLyrics, ...}`
 *    优先 `syncedLyrics`（带时间戳），兜底 `plainLyrics`。
 *
 * LRCLIB 的 rate-limit 文档建议带上可识别 User-Agent（虽然目前没强制），补上以免
 * 将来被 429。
 */
class LrclibLyricsProvider(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val baseUrl: String = DEFAULT_BASE_URL,
) : LyricProvider() {

    override val name: String = "lrclib"

    override suspend fun search(title: String, artist: String): SongMatch? =
        searchMultiple(title, artist, limit = 1).firstOrNull()

    suspend fun searchMultiple(
        title: String,
        artist: String,
        limit: Int = 3,
    ): List<SongMatch> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .build()
        val request = Request.Builder().url(url).get().headers(HEADERS).build()

        runCatching {
            client.awaitResponse(request).use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "LRCLIB search failed: ${response.code}")
                    return@use emptyList<SongMatch>()
                }
                val raw = response.body?.string().orEmpty()
                val arr = json.parseToJsonElement(raw).jsonArray
                arr.asSequence().take(limit).mapNotNull { el ->
                    val obj = el.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val songTitle = obj["trackName"]?.jsonPrimitive?.contentOrNull ?: title
                    val songArtist = obj["artistName"]?.jsonPrimitive?.contentOrNull ?: artist
                    SongMatch(songId = id, title = songTitle, artist = songArtist)
                }.toList()
            }
        }.getOrElse { e ->
            Log.w(TAG, "LRCLIB search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun fetchLyric(songId: String): String? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/get/$songId".toHttpUrl()
        val request = Request.Builder().url(url).get().headers(HEADERS).build()

        runCatching {
            client.awaitResponse(request).use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "LRCLIB fetch failed: ${response.code}")
                    return@use null
                }
                val raw = response.body?.string().orEmpty()
                val obj = json.parseToJsonElement(raw).jsonObject
                val synced = obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull
                val plain = obj["plainLyrics"]?.jsonPrimitive?.contentOrNull
                synced?.takeIf { it.isNotBlank() } ?: plain?.takeIf { it.isNotBlank() }
            }
        }.getOrElse { e ->
            Log.w(TAG, "LRCLIB fetch error: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "LrclibLyricsProvider"
        private const val DEFAULT_BASE_URL = "https://lrclib.net"

        private val HEADERS: Headers = Headers.Builder()
            .add("user-agent", "Yoin/0.1.0 (https://github.com/p2o51/Yoin)")
            .add("accept", "application/json")
            .build()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
