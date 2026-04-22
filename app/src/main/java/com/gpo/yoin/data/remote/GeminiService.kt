package com.gpo.yoin.data.remote

import com.gpo.yoin.data.local.SongInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiService(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun generateSongInfo(
        apiKey: String,
        title: String,
        artist: String,
        album: String,
    ): SongInfo = withContext(Dispatchers.IO) {
        val prompt = buildPrompt(title, artist, album)
        val requestBody = GeminiRequest(
            contents = listOf(
                GeminiContent(parts = listOf(GeminiPart(text = prompt))),
            ),
            tools = listOf(GeminiTool()),
        )

        val bodyJson = json.encodeToString(requestBody)
        val request = Request.Builder()
            .url("$BASE_URL$MODEL:generateContent?key=$apiKey")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw GeminiException("Empty response from Gemini API")

        if (!response.isSuccessful) {
            throw GeminiException(
                "Gemini API error (${response.code}): ${extractErrorMessage(responseBody)}",
            )
        }

        val geminiResponse = json.decodeFromString<GeminiResponse>(responseBody)
        val rawText = geminiResponse.candidates
            ?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw GeminiException("No content in Gemini response")

        parseTaggedResponse(rawText, songId = "")
    }

    /**
     * 「余音 Gemini 文案」—— 给 Memory 卡片生成一句 40–80 字的感性短评，
     * 把 Memory 从「事实回顾」升格为「情绪回响」。
     *
     * 输入信号保留到最低：专辑名、艺人、年份、已评曲目均分、评过的曲目
     * 数量。不传播 song_notes / review 原文给 Gemini —— 笔记是本地私密。
     *
     * 返回的是纯文本（没有 tag），调用方直接落 `memory_copy_cache.copy`。
     */
    suspend fun generateAlbumMemoryCopy(
        apiKey: String,
        albumName: String,
        artist: String?,
        year: Int?,
        averageRating: Float?,
        ratedSongCount: Int,
        totalSongCount: Int,
    ): String = withContext(Dispatchers.IO) {
        val prompt = buildMemoryPrompt(
            albumName = albumName,
            artist = artist,
            year = year,
            averageRating = averageRating,
            ratedSongCount = ratedSongCount,
            totalSongCount = totalSongCount,
        )
        val requestBody = GeminiRequest(
            contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt)))),
            // Memory 文案不走 search tool —— 只要 LLM 基于 embedding 给感性
            // 回响，不需要外网事实核对；更快也更便宜。
            tools = null,
        )

        val bodyJson = json.encodeToString(requestBody)
        val request = Request.Builder()
            .url("$BASE_URL$MODEL:generateContent?key=$apiKey")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw GeminiException("Empty response from Gemini API")

        if (!response.isSuccessful) {
            throw GeminiException(
                "Gemini API error (${response.code}): ${extractErrorMessage(responseBody)}",
            )
        }

        val geminiResponse = json.decodeFromString<GeminiResponse>(responseBody)
        geminiResponse.candidates
            ?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?.trim()
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotEmpty() }
            ?: throw GeminiException("No content in Gemini response")
    }

    private fun buildMemoryPrompt(
        albumName: String,
        artist: String?,
        year: Int?,
        averageRating: Float?,
        ratedSongCount: Int,
        totalSongCount: Int,
    ): String {
        val artistLine = artist?.takeIf(String::isNotBlank) ?: "Unknown artist"
        val yearLine = year?.toString() ?: "Unknown year"
        val ratingLine = averageRating
            ?.takeIf { it > 0f }
            ?.let { "%.1f / 10, based on %d of %d songs rated".format(it, ratedSongCount, totalSongCount) }
            ?: "User hasn't rated this album yet"
        return """
You are writing a single short line of poetic Chinese reflection for a music
memory card. The user is revisiting this album on a private music journal.

Album: $albumName
Artist: $artistLine
Year: $yearLine
User rating signal: $ratingLine

Write ONE line in Simplified Chinese, 30 to 60 characters, no quotes, no
emoji, no hashtags, no english, no line break. Evoke the emotional echo of
this album — not a factual summary. Avoid clichés like "经典" or "神专".
Output only the line itself.
        """.trimIndent()
    }

    private fun buildPrompt(title: String, artist: String, album: String): String = """
Search for and provide detailed information about the following song:

Song: $title
Artist: $artist
Album: $album

Use the search tool to find accurate information. Respond strictly in the following tagged format. If any information cannot be found, write "N/A" inside the tag.

[CREATION_TIME]
When the song was created/recorded
[/CREATION_TIME]

[CREATION_LOCATION]
Where the song was created/recorded
[/CREATION_LOCATION]

[LYRICIST]
Lyricist(s)
[/LYRICIST]

[COMPOSER]
Composer(s)
[/COMPOSER]

[PRODUCER]
Producer(s)
[/PRODUCER]

[REVIEW]
A 2-4 sentence analysis of the song including its background, musical characteristics, and cultural significance. Be insightful and professional but not overly academic.
[/REVIEW]

Respond strictly in the tagged format above. Every field must include its corresponding tags.
    """.trimIndent()

    private fun extractErrorMessage(body: String): String = try {
        val errorResponse = json.decodeFromString<GeminiErrorResponse>(body)
        errorResponse.error?.message ?: body.take(200)
    } catch (_: Exception) {
        body.take(200)
    }

    companion object {
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val MODEL = "gemini-3.1-flash-lite-preview"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun parseTaggedResponse(rawText: String, songId: String): SongInfo {
            fun extract(tag: String): String? {
                val start = rawText.indexOf("[$tag]")
                val end = rawText.indexOf("[/$tag]")
                if (start == -1 || end == -1 || end <= start) return null
                val content = rawText.substring(start + tag.length + 2, end).trim()
                return content.takeIf {
                    it.isNotEmpty() &&
                        !it.equals("N/A", ignoreCase = true) &&
                        !it.equals("null", ignoreCase = true) &&
                        it != "无法获取"
                }
            }
            return SongInfo(
                songId = songId,
                creationTime = extract("CREATION_TIME"),
                creationLocation = extract("CREATION_LOCATION"),
                lyricist = extract("LYRICIST"),
                composer = extract("COMPOSER"),
                producer = extract("PRODUCER"),
                review = extract("REVIEW"),
            )
        }
    }
}

class GeminiException(message: String) : Exception(message)

@kotlinx.serialization.Serializable
private data class GeminiErrorResponse(
    val error: GeminiErrorDetail? = null,
)

@kotlinx.serialization.Serializable
private data class GeminiErrorDetail(
    val message: String? = null,
)
