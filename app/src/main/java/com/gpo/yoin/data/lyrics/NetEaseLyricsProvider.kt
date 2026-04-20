package com.gpo.yoin.data.lyrics

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 网易云音乐歌词源，通过 Spotoolfy 官方的第三方代理 `api.spotoolfy.gojyuplus.com`
 * 间接调用网易 API（避开官方反爬）。Port 自
 * `spotoolfy_flutter/lib/services/lyrics/netease_provider.dart`。
 *
 * 不实现 `fetchLyricWithTranslation`（翻译本次不做）。逐字 JSON 歌词会被转换为
 * 标准 LRC（见 [parseJsonLyric]）。
 */
class NetEaseLyricsProvider(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val baseUrl: String = DEFAULT_BASE_URL,
) : LyricProvider() {

    override val name: String = "netease"

    override suspend fun search(title: String, artist: String): SongMatch? =
        searchMultiple(title, artist, limit = 1).firstOrNull()

    suspend fun searchMultiple(
        title: String,
        artist: String,
        limit: Int = 3,
    ): List<SongMatch> = withContext(Dispatchers.IO) {
        val keyword = "$title $artist"
        val url = "$baseUrl/cloudsearch".toHttpUrl().newBuilder()
            .addQueryParameter("keywords", keyword)
            .addQueryParameter("limit", limit.toString())
            .build()

        val request = Request.Builder().url(url).get().build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "NetEase search failed: ${response.code}")
                    return@use emptyList<SongMatch>()
                }
                val raw = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(raw).jsonObject
                val songs = root["result"]?.jsonObject?.get("songs")?.jsonArray
                    ?: return@use emptyList()

                songs.asSequence().take(limit).mapNotNull { el ->
                    val obj = el.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val songTitle = obj["name"]?.jsonPrimitive?.contentOrNull ?: title
                    val primaryArtist = obj["ar"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                        ?: artist
                    SongMatch(songId = id, title = songTitle, artist = primaryArtist)
                }.toList()
            }
        }.getOrElse { e ->
            Log.w(TAG, "NetEase search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun fetchLyric(songId: String): String? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/lyric/new".toHttpUrl().newBuilder()
            .addQueryParameter("id", songId)
            .build()
        val request = Request.Builder().url(url).get().build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "NetEase lyric failed: ${response.code}")
                    return@use null
                }
                val raw = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(raw).jsonObject
                val lrcText = root["lrc"]?.jsonObject
                    ?.get("lyric")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@use null

                if (lrcText.trimStart().startsWith('{')) {
                    parseJsonLyric(lrcText)
                } else {
                    lrcText
                }
            }
        }.getOrElse { e ->
            Log.w(TAG, "NetEase lyric parse error: ${e.message}")
            null
        }
    }

    /**
     * 网易逐字 JSON 歌词 → 标准 LRC。每行形如
     * `{"t": 12345, "c": [{"tx": "歌"}, {"tx": "词"}, ...]}`。
     * 对应 dart 的 `_parseJsonLyric`（`netease_provider.dart:151-189`）。
     */
    private fun parseJsonLyric(raw: String): String? {
        val out = mutableListOf<String>()
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (!trimmed.startsWith('{')) {
                out += line
                continue
            }
            runCatching {
                val obj = json.parseToJsonElement(trimmed).jsonObject
                val time = obj["t"]?.jsonPrimitive?.intOrNull ?: 0
                val parts = obj["c"]?.jsonArray ?: return@runCatching
                val text = parts.joinToString(separator = "") { part ->
                    part.jsonObject["tx"]?.jsonPrimitive?.contentOrNull.orEmpty()
                }
                if (isMetadataLine(text)) return@runCatching

                val minutes = (time / 60_000).toString().padStart(2, '0')
                val seconds = ((time % 60_000) / 1000).toString().padStart(2, '0')
                val centis = ((time % 1000) / 10).toString().padStart(2, '0')
                out += "[$minutes:$seconds.$centis]$text"
            }
        }
        return out.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun isMetadataLine(text: String): Boolean {
        val lower = text.lowercase()
        return METADATA_KEYWORDS.any { kw ->
            val k = kw.lowercase()
            lower.startsWith(k) || lower.contains(":$k") || lower.contains("：$k")
        }
    }

    companion object {
        private const val TAG = "NetEaseLyricsProvider"
        private const val DEFAULT_BASE_URL = "https://api.spotoolfy.gojyuplus.com"

        private val METADATA_KEYWORDS = listOf(
            "歌词贡献者", "翻译贡献者", "作词", "作曲", "编曲",
            "制作", "词曲", "词 / 曲", "lyricist", "composer",
            "arrange", "translation", "translator", "producer",
        )

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
