package com.gpo.yoin.data.lyrics

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * 逐字 JSON 歌词会被转换为标准 LRC（见 [parseJsonLyric]）。
 *
 * 自动匹配（[search]）多要几条，交给 [LyricCandidateMatcher] 校验，和 QQ、华为同一套
 * 规则：标题必须对上，艺人能比就必须对上，原版优先于 Live / Remix，伴奏不算。认不出
 * 就返回 null 让下一个 provider 试——网易云的第一条常是翻唱、同名的别人，甚至标题都
 * 不对的歌。手动搜索（[searchMultiple]）保持网易云自己的排序。
 */
class NetEaseLyricsProvider(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val baseUrl: String = DEFAULT_BASE_URL,
) : LyricProvider() {

    override val name: String = "netease"

    override val nativeTranslation: NativeTranslation = NativeTranslation.SimplifiedChinese

    private val matcher = LyricCandidateMatcher()

    override suspend fun search(title: String, artist: String): SongMatch? =
        withContext(Dispatchers.IO) {
            val songs = findSongs(title, artist, SEARCH_PAGE_SIZE) ?: return@withContext null
            val picked = matcher.pick(
                candidates = songs.map { LyricCandidateMatcher.Candidate(it.title.orEmpty(), it.artists, it.album) },
                title = title,
                artist = artist,
            ) ?: return@withContext null
            songs[picked].toMatch(fallbackTitle = title, fallbackArtist = artist)
        }

    override suspend fun searchMultiple(
        title: String,
        artist: String,
        limit: Int,
    ): List<SongMatch> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        findSongs(title, artist, limit).orEmpty()
            .take(limit)
            .map { it.toMatch(fallbackTitle = title, fallbackArtist = artist) }
    }

    /**
     * `cloudsearch`，按网易云的原序返回。null = 没答上（网络、非 2xx、解析失败）；
     * 空列表是真实的"搜不到"。没有 id 的条目取不了词，直接跳过。
     */
    private suspend fun findSongs(title: String, artist: String, pageSize: Int): List<NetEaseSong>? {
        val url = "$baseUrl/cloudsearch".toHttpUrl().newBuilder()
            .addQueryParameter("keywords", "$title $artist")
            .addQueryParameter("limit", pageSize.toString())
            .build()
        val request = Request.Builder().url(url).get().build()

        return try {
            client.awaitResponse(request).use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "NetEase search failed: ${response.code}")
                    return@use null
                }
                val root = json.parseToJsonElement(response.body.string()).jsonObject
                val songs = root["result"]?.jsonObject?.get("songs")?.jsonArray
                    ?: return@use emptyList()
                songs.mapNotNull(::parseSong)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "NetEase search error: ${e.message}")
            null
        }
    }

    private fun parseSong(element: JsonElement): NetEaseSong? {
        val obj = element as? JsonObject ?: return null
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return null
        val artists = (obj["ar"] as? JsonArray).orEmpty().mapNotNull { artist ->
            ((artist as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }
        return NetEaseSong(
            id = id,
            title = (obj["name"] as? JsonPrimitive)?.contentOrNull,
            artists = artists,
            album = ((obj["al"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotEmpty),
        )
    }

    /**
     * 一条搜索结果。[title] 缺失时不拿请求的歌名顶替去比对——那样任何一条没名字的
     * 结果都会被当成"标题对上了"；只在交给界面时（[toMatch]）才回退。[album] 只给匹配器
     * 认现场 / 访谈专辑用。
     */
    private class NetEaseSong(
        val id: String,
        val title: String?,
        val artists: List<String>,
        val album: String?,
    ) {
        fun toMatch(fallbackTitle: String, fallbackArtist: String): SongMatch = SongMatch(
            songId = id,
            title = title ?: fallbackTitle,
            artist = artists.firstOrNull() ?: fallbackArtist,
        )
    }

    override suspend fun fetchLyric(songId: String): String? = withContext(Dispatchers.IO) {
        fetchLyricPayload(songId)?.lyric
    }

    override suspend fun fetchLyricWithTranslation(songId: String): LyricPayload? =
        withContext(Dispatchers.IO) {
            fetchLyricPayload(songId)
        }

    private suspend fun fetchLyricPayload(songId: String): LyricPayload? {
        val url = "$baseUrl/lyric/new".toHttpUrl().newBuilder()
            .addQueryParameter("id", songId)
            .build()
        val request = Request.Builder().url(url).get().build()

        return runCatching {
            client.awaitResponse(request).use { response ->
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
                val translatedText = root["tlyric"]?.jsonObject
                    ?.get("lyric")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                LyricPayload(
                    lyric = parseProviderLyric(lrcText) ?: return@use null,
                    translatedLyric = translatedText?.let(::parseProviderLyric),
                )
            }
        }.getOrElse { e ->
            Log.w(TAG, "NetEase lyric parse error: ${e.message}")
            null
        }
    }

    private fun parseProviderLyric(raw: String): String? =
        if (raw.trimStart().startsWith('{')) {
            parseJsonLyric(raw)
        } else {
            raw
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

        /** 自动匹配时要的候选数，和 QQ 一致。 */
        private const val SEARCH_PAGE_SIZE = 10

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
