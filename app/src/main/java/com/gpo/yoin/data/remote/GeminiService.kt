package com.gpo.yoin.data.remote

import com.gpo.yoin.data.local.SongAboutEntry
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
    /**
     * Fetch the 6 canonical About fields (Creation Time / Location /
     * Lyricist / Composer / Producer / Review) via a single Grounded call.
     *
     * Returns partial rows: only fields Gemini actually filled in come back;
     * "N/A" / empty tags are dropped. Ordering follows
     * [SongAboutEntry.CANONICAL_ORDER]. Repository layer stamps identity
     * keys and timestamps before persisting.
     */
    suspend fun generateCanonicalAbout(
        apiKey: String,
        title: String,
        artist: String,
        album: String,
    ): List<CanonicalAboutValue> = withContext(Dispatchers.IO) {
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

        parseTaggedResponse(rawText)
    }

    /**
     * Grounded free-form Q&A about a song. Gemini is asked to return a
     * concise headline version of the question alongside the detailed
     * answer, so the UI can show a short heading instead of re-echoing
     * the user's verbose prompt. Repository upserts both into the
     * `ask` row.
     */
    suspend fun askAboutSong(
        apiKey: String,
        title: String,
        artist: String,
        album: String,
        question: String,
    ): AskAnswer = withContext(Dispatchers.IO) {
        val prompt = buildAskPrompt(title, artist, album, question)
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
            ?.takeIf { it.isNotEmpty() }
            ?: throw GeminiException("No content in Gemini response")

        parseAskResponse(rawText, fallbackQuestion = question)
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

    private fun buildAskPrompt(
        title: String,
        artist: String,
        album: String,
        question: String,
    ): String = """
Answer the following question about the song "$title" from the album "$album" by $artist.
Use the search tool to find accurate, up-to-date information.

Respond strictly in the following tagged format:

[TITLE]
A concise headline that summarises the question — no more than 8 words, no trailing punctuation. This is shown as the visual heading above your answer, so it should feel like a title, not a full sentence. Use the language the user asked in.
[/TITLE]

[ANSWER]
The detailed answer. No more than 120 words. Plain prose only — no citation markers, headers, or bullet points.
[/ANSWER]

Question: $question
    """.trimIndent()

    private fun extractErrorMessage(body: String): String = try {
        val errorResponse = json.decodeFromString<GeminiErrorResponse>(body)
        errorResponse.error?.message ?: body.take(200)
    } catch (_: Exception) {
        body.take(200)
    }

    /** One canonical About field returned by [generateCanonicalAbout]. */
    data class CanonicalAboutValue(
        val entryKey: String,
        val answer: String,
    )

    /** Title + answer pair returned by [askAboutSong]. */
    data class AskAnswer(
        val title: String,
        val answer: String,
    )

    companion object {
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val MODEL = "gemini-3.1-flash-lite-preview"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // Matches any `[TAG]`, `[/TAG]`, `[ TAG ]`, `[ / TAG ]` etc. with
        // ALL_CAPS identifiers. Used both to extract content by tag name and
        // to scrub leftover markers out of fallback prose so the user never
        // sees raw `[TITLE]` / `[ANSWER]` text in the UI.
        private val TAG_MARKER_REGEX = Regex("""\[\s*/?\s*[A-Z][A-Z0-9_]*\s*\]""")

        /**
         * Extract the content between `[TAG]` and `[/TAG]`, tolerant of:
         * - case variations (e.g. `[title]`, `[Title]`)
         * - whitespace inside brackets (e.g. `[ TITLE ]`)
         * - missing closing tag (response truncated mid-stream): reads from
         *   open tag to end of string instead of returning null
         *
         * Returns null only when the open tag itself is missing or the
         * extracted content is blank.
         */
        private fun extractTagContent(text: String, tag: String): String? {
            val escaped = Regex.escape(tag)
            val openRegex = Regex("""\[\s*$escaped\s*\]""", RegexOption.IGNORE_CASE)
            val closeRegex = Regex("""\[\s*/\s*$escaped\s*\]""", RegexOption.IGNORE_CASE)
            val openMatch = openRegex.find(text) ?: return null
            val contentStart = openMatch.range.last + 1
            val closeMatch = closeRegex.find(text, contentStart)
            val contentEnd = closeMatch?.range?.first ?: text.length
            return text.substring(contentStart, contentEnd)
                .let { stripTagMarkers(it) }
                .takeIf { it.isNotEmpty() }
        }

        /**
         * Strip any leftover `[TAG]` / `[/TAG]` markers from prose. Used on
         * fallback paths so a malformed / truncated response still renders
         * as clean text rather than showing the raw schema to the user.
         */
        private fun stripTagMarkers(text: String): String =
            text.replace(TAG_MARKER_REGEX, "").trim()

        /**
         * Parse the tagged response emitted by [buildPrompt] into a list of
         * [CanonicalAboutValue] — only fields with real content are returned,
         * preserving [SongAboutEntry.CANONICAL_ORDER].
         */
        fun parseTaggedResponse(rawText: String): List<CanonicalAboutValue> {
            fun extract(tag: String): String? {
                val content = extractTagContent(rawText, tag) ?: return null
                return content.takeIf {
                    !it.equals("N/A", ignoreCase = true) &&
                        !it.equals("null", ignoreCase = true) &&
                        it != "无法获取"
                }
            }
            val pairs = listOf(
                SongAboutEntry.CANON_CREATION_TIME to extract("CREATION_TIME"),
                SongAboutEntry.CANON_CREATION_LOCATION to extract("CREATION_LOCATION"),
                SongAboutEntry.CANON_LYRICIST to extract("LYRICIST"),
                SongAboutEntry.CANON_COMPOSER to extract("COMPOSER"),
                SongAboutEntry.CANON_PRODUCER to extract("PRODUCER"),
                SongAboutEntry.CANON_REVIEW to extract("REVIEW"),
            )
            return pairs.mapNotNull { (key, value) ->
                value?.let { CanonicalAboutValue(entryKey = key, answer = it) }
            }
        }

        /**
         * Parse an Ask Gemini response. Expects `[TITLE]...[/TITLE]` and
         * `[ANSWER]...[/ANSWER]` blocks; tolerates case/whitespace variants
         * and truncated responses (missing closing tag). If Gemini ignores
         * the schema entirely and returns plain prose, we fall back to the
         * user's original question as the title and strip any stray tag
         * markers out of the prose so the answer still reads clean.
         */
        fun parseAskResponse(rawText: String, fallbackQuestion: String): AskAnswer {
            val title = extractTagContent(rawText, "TITLE")
            val answer = extractTagContent(rawText, "ANSWER")
            return when {
                title != null && answer != null -> AskAnswer(title, answer)
                answer != null -> AskAnswer(fallbackQuestion.trim(), answer)
                title != null -> AskAnswer(title, stripTagMarkers(rawText))
                else -> AskAnswer(fallbackQuestion.trim(), stripTagMarkers(rawText))
            }
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
