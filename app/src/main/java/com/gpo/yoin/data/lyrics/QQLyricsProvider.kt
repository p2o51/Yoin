package com.gpo.yoin.data.lyrics

import android.util.Log
import java.util.Base64
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
 * QQ 音乐歌词源。2026-07 按接口现状重写（QQ 把桌面端接口全部加了登录墙）：
 *
 * - 已死（需登录）：桌面搜索 `DoSearchForQQMusicDesktop`（`code: 2001`）、
 *   旧取词 `fcg_query_lyric_new.fcg`（`retcode: 1101`）、
 *   `GetPlayLyricInfo` 传 `songMID`（`code: 24001`）。
 * - 免登录可用：
 *   1. 搜索：GET `c.y.qq.com/soso/fcgi-bin/client_search_cp`（老客户端搜索，返回数字 songid）
 *   2. 取词：POST `u.y.qq.com/cgi-bin/musicu.fcg` 调
 *      `music.musichallSong.PlayLyricInfo.GetPlayLyricInfo`，传**数字** `songID` +
 *      `crypt=0`，`lyric`/`trans` 是 base64 明文 LRC。
 *
 * 注意：逐字 QRC 内容（`qrc=1` 或 QRC-only 歌曲）服务端会强发 hex 密文，用的是
 * 未公开的新加密算法（LX Music 靠预编译二进制解），这里识别后直接放弃。
 */
class QQLyricsProvider(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val searchUrl: String = DEFAULT_SEARCH_URL,
    private val playLyricInfoUrl: String = DEFAULT_PLAY_LYRIC_INFO_URL,
) : LyricProvider() {

    override val name: String = "qq"

    override fun canFetch(songId: String): Boolean = songId.toLongOrNull() != null

    override suspend fun search(title: String, artist: String): SongMatch? =
        searchMultiple(title, artist, limit = 1).firstOrNull()

    override suspend fun searchMultiple(
        title: String,
        artist: String,
        limit: Int,
    ): List<SongMatch> = withContext(Dispatchers.IO) {
        val url = searchUrl.toHttpUrl().newBuilder()
            .addQueryParameter("w", "$title $artist")
            .addQueryParameter("format", "json")
            .addQueryParameter("n", limit.toString())
            .addQueryParameter("t", "0")
            .addQueryParameter("cr", "1")
            .addQueryParameter("new_json", "1")
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .headers(BASE_HEADERS)
            .build()

        runCatching {
            client.awaitResponse(request).use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "QQ search failed: ${response.code}")
                    return@use emptyList<SongMatch>()
                }
                val raw = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(raw).jsonObject
                val songList = root["data"]?.jsonObject
                    ?.get("song")?.jsonObject
                    ?.get("list")?.jsonArray
                    ?: return@use emptyList()

                songList.mapNotNull { el ->
                    val obj = el.jsonObject
                    val songId = obj["id"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.toLongOrNull() != null }
                        ?: return@mapNotNull null
                    val songTitle = (obj["title"]?.jsonPrimitive?.contentOrNull
                        ?: obj["name"]?.jsonPrimitive?.contentOrNull)
                        .normalizeField(title)
                    val primaryArtist = obj["singer"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    SongMatch(
                        songId = songId,
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

    override suspend fun fetchLyric(songId: String): String? =
        fetchLyricWithTranslation(songId)?.lyric

    override suspend fun fetchLyricWithTranslation(songId: String): LyricPayload? = withContext(Dispatchers.IO) {
        val numericSongId = songId.toLongOrNull()
        if (numericSongId == null) {
            Log.w(TAG, "QQ lyric needs numeric songID, got: $songId")
            return@withContext null
        }
        val payload = buildJsonObject {
            putJsonObject("comm") {
                put("ct", "19")
                put("cv", "1859")
                put("uin", "0")
            }
            putJsonObject("req_1") {
                put("module", "music.musichallSong.PlayLyricInfo")
                put("method", "GetPlayLyricInfo")
                putJsonObject("param") {
                    put("format", "json")
                    put("crypt", 0)
                    put("ct", 19)
                    put("cv", 1873)
                    put("interval", 0)
                    put("lrc_t", 0)
                    put("qrc", 0)
                    put("qrc_t", 0)
                    put("roma", 0)
                    put("roma_t", 0)
                    put("songID", numericSongId)
                    put("trans", 1)
                    put("trans_t", 0)
                    put("type", -1)
                }
            }
        }
        val request = Request.Builder()
            .url(playLyricInfoUrl)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .headers(PLAY_LYRIC_HEADERS)
            .build()

        runCatching {
            client.awaitResponse(request).use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "QQ play lyric info failed: ${response.code}")
                    return@use null
                }
                val raw = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(raw).jsonObject
                val req1 = root["req_1"]?.jsonObject ?: return@use null
                val code = req1["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (code != null && code != 0) {
                    Log.w(TAG, "QQ play lyric info code=$code")
                    return@use null
                }
                val data = req1["data"]?.jsonObject ?: return@use null
                val lyric = decodeMaybeBase64(data["lyric"]?.jsonPrimitive?.contentOrNull)
                    ?: return@use null
                val translatedLyric = decodeMaybeBase64(data["trans"]?.jsonPrimitive?.contentOrNull)
                LyricPayload(
                    lyric = lyric,
                    translatedLyric = translatedLyric,
                )
            }
        }.getOrElse { e ->
            Log.w(TAG, "QQ play lyric info parse error: ${e.message}")
            null
        }
    }

    private fun decodeMaybeBase64(raw: String?): String? {
        val normalized = QQEncoding.normalizeNullable(raw) ?: return null
        val trimmed = normalized.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("[")) return trimmed
        // QRC-only 歌曲会被强发 hex 密文（新算法，解不了），识别后放弃。
        // 真实密文很长；下限避免把极短、碰巧全 hex 的合法 base64 误杀。
        if (trimmed.length >= MIN_QRC_HEX_LENGTH &&
            trimmed.length % 2 == 0 &&
            trimmed.all { it.isHexDigit }
        ) {
            return null
        }
        return runCatching {
            String(Base64.getDecoder().decode(trimmed), Charsets.UTF_8)
        }.getOrNull()
            ?.let(QQEncoding::normalizeNullable)
            ?.takeIf { it.isNotBlank() }
    }

    private val Char.isHexDigit: Boolean
        get() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun String?.normalizeField(fallback: String): String {
        val trimmed = (this ?: fallback).trim()
        val resolved = trimmed.ifEmpty { fallback }
        return QQEncoding.normalize(resolved)
    }

    companion object {
        private const val TAG = "QQLyricsProvider"
        private const val DEFAULT_SEARCH_URL =
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
        private const val DEFAULT_PLAY_LYRIC_INFO_URL =
            "https://u.y.qq.com/cgi-bin/musicu.fcg"

        private val JSON_MEDIA_TYPE = "application/json;charset=utf-8".toMediaType()

        private val BASE_HEADERS: Headers = Headers.Builder()
            .add("referer", "https://y.qq.com/")
            .add(
                "user-agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36",
            )
            .build()

        private const val MIN_QRC_HEX_LENGTH = 16

        private val PLAY_LYRIC_HEADERS: Headers = BASE_HEADERS.newBuilder()
            .add("content-type", "application/json;charset=utf-8")
            .build()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
