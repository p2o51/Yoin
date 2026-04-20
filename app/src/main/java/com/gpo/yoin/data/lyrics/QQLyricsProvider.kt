package com.gpo.yoin.data.lyrics

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * QQ 音乐歌词源。Port 自 `spotoolfy_flutter/lib/services/lyrics/qq_provider_mobile.dart`。
 * 两次公网请求：
 * 1. 搜索：POST `u.y.qq.com/cgi-bin/musicu.fcg` 拿到 `songmid`
 * 2. 取词：GET `c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=&format=json&nobase64=1`
 *    返回 JSON，原文在 `lyric` 字段；遇 403/429 翻转到备用域名 `u6.y.qq.com`。
 */
class QQLyricsProvider(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : LyricProvider() {

    @Volatile
    private var useBackupDomain: Boolean = false

    override val name: String = "qq"

    override suspend fun search(title: String, artist: String): SongMatch? =
        searchMultiple(title, artist, limit = 1).firstOrNull()

    suspend fun searchMultiple(
        title: String,
        artist: String,
        limit: Int = 3,
    ): List<SongMatch> = withContext(Dispatchers.IO) {
        val keyword = "$title $artist"
        val payload = buildJsonObject {
            putJsonObject("comm") {
                put("ct", "19")
                put("cv", "1859")
                put("uin", "0")
            }
            putJsonObject("req") {
                put("method", "DoSearchForQQMusicDesktop")
                put("module", "music.search.SearchCgiService")
                putJsonObject("param") {
                    put("grp", 1)
                    put("num_per_page", limit)
                    put("page_num", 1)
                    put("query", keyword)
                    put("search_type", 0)
                }
            }
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(SEARCH_URL)
            .post(body)
            .headers(SEARCH_HEADERS)
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "QQ search failed: ${response.code}")
                    return@use emptyList<SongMatch>()
                }
                val raw = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(raw).jsonObject
                val songList = root["req"]?.jsonObject
                    ?.get("data")?.jsonObject
                    ?.get("body")?.jsonObject
                    ?.get("song")?.jsonObject
                    ?.get("list")?.jsonArray
                    ?: return@use emptyList()

                songList.mapNotNull { el ->
                    val obj = el.jsonObject
                    val mid = obj["mid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val songTitle = (obj["title"]?.jsonPrimitive?.contentOrNull
                        ?: obj["name"]?.jsonPrimitive?.contentOrNull)
                        .normalizeField(title)
                    val primaryArtist = obj["singer"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    SongMatch(
                        songId = mid,
                        title = songTitle,
                        artist = primaryArtist.normalizeField(artist),
                    )
                }
            }
        }.getOrElse { e ->
            Log.w(TAG, "QQ search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun fetchLyric(songId: String): String? = withContext(Dispatchers.IO) {
        val base = if (useBackupDomain) BACKUP_LYRIC_URL else PRIMARY_LYRIC_URL
        val url = base.toHttpUrl().newBuilder()
            .addQueryParameter("songmid", songId)
            .addQueryParameter("format", "json")
            .addQueryParameter("nobase64", "1")
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .headers(BASE_HEADERS)
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code == 403 || response.code == 429) {
                    Log.w(TAG, "QQ lyric blocked (${response.code}), flipping to backup domain")
                    useBackupDomain = !useBackupDomain
                    return@use null
                }
                if (!response.isSuccessful) {
                    Log.w(TAG, "QQ lyric failed: ${response.code}")
                    return@use null
                }
                val raw = response.body?.string().orEmpty()
                val obj = json.parseToJsonElement(raw).jsonObject
                QQEncoding.normalizeNullable(obj["lyric"]?.jsonPrimitive?.contentOrNull)
            }
        }.getOrElse { e ->
            Log.w(TAG, "QQ lyric parse error: ${e.message}")
            null
        }
    }

    private fun String?.normalizeField(fallback: String): String {
        val trimmed = (this ?: fallback).trim()
        val resolved = trimmed.ifEmpty { fallback }
        return QQEncoding.normalize(resolved)
    }

    companion object {
        private const val TAG = "QQLyricsProvider"
        private const val SEARCH_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        private const val PRIMARY_LYRIC_URL =
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
        private const val BACKUP_LYRIC_URL =
            "https://u6.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"

        private val JSON_MEDIA_TYPE = "application/json;charset=utf-8".toMediaType()

        private val BASE_HEADERS: Headers = Headers.Builder()
            .add("referer", "https://y.qq.com/")
            .add(
                "user-agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36",
            )
            .build()

        private val SEARCH_HEADERS: Headers = BASE_HEADERS.newBuilder()
            .add("content-type", "application/json;charset=utf-8")
            .build()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
