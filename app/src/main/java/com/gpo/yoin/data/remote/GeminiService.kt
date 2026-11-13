package com.gpo.yoin.data.remote

import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.remote.ai.AiClient
import com.gpo.yoin.data.remote.ai.AiSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Yoin's online AI calls (About, Ask, Memories copy, lyric translation).
 * Named for Gemini, the only service until AI settings v2 (2026-11-12); every
 * call now goes to whichever service [AiSettings] names, through [AiClient].
 */
class GeminiService(
    client: OkHttpClient,
    json: Json,
) {
    private val ai = AiClient(client, json)

    /** What the last About call searched, for the 「拿一首歌试试」 page. */
    data class AboutRun(
        val values: List<CanonicalAboutValue>,
        val rawText: String,
        val searchKind: AiSettings.SearchKind,
        val searchQueries: List<String>,
        val pagesRead: Int,
    )

    /**
     * Fetch the 6 canonical About fields (Creation Time / Location /
     * Lyricist / Composer / Producer / Review) in one searched call.
     *
     * Returns partial rows: only fields the model actually filled in come back;
     * "N/A" / empty tags are dropped. Ordering follows
     * [SongAboutEntry.CANONICAL_ORDER]. Repository layer stamps identity
     * keys and timestamps before persisting.
     */
    suspend fun generateCanonicalAbout(
        ai: AiSettings,
        title: String,
        artist: String,
        album: String,
        targetLanguage: String,
    ): List<CanonicalAboutValue> = runCanonicalAbout(ai, title, artist, album, targetLanguage).values

    /** [generateCanonicalAbout] with what it searched and the raw reply, for the test page. */
    suspend fun runCanonicalAbout(
        ai: AiSettings,
        title: String,
        artist: String,
        album: String,
        targetLanguage: String,
    ): AboutRun {
        val queries = buildList {
            add("$title $artist ${AiClient.creditsKeywords(title, artist)}")
            if (ai.searchQueries > 1) add("$title $artist $album")
        }.take(ai.searchQueries)
        val searched = searchFor(ai, queries)
        val prompt = buildPrompt(title, artist, album, targetLanguage, searchLead(ai, searched)) + searched.appendix
        val rawText = this.ai.generate(ai, prompt, temperature = 0.8f, useSearch = true)
        return AboutRun(
            values = parseTaggedResponse(rawText),
            rawText = rawText,
            searchKind = ai.searchKind,
            searchQueries = if (ai.searchKind == AiSettings.SearchKind.External) queries else emptyList(),
            pagesRead = searched.pages,
        )
    }

    /**
     * Searched free-form Q&A about a song. The model is asked to return a
     * concise headline version of the question alongside the detailed
     * answer, so the UI can show a short heading instead of re-echoing
     * the user's verbose prompt. Repository upserts both into the
     * `ask` row.
     */
    suspend fun askAboutSong(
        ai: AiSettings,
        title: String,
        artist: String,
        album: String,
        question: String,
        targetLanguage: String,
    ): AskAnswer {
        val searched = searchFor(ai, listOf("$title $artist $question"))
        val prompt = buildAskPrompt(title, artist, album, question, targetLanguage, searchLead(ai, searched)) + searched.appendix
        val rawText = this.ai.generate(ai, prompt, temperature = 0.8f, useSearch = true)
        return parseAskResponse(rawText, fallbackQuestion = question)
    }

    /**
     * Lightweight title-only call used by the Ask loading state.
     * The full searched answer still returns its own `[TITLE]` later; this
     * just gives the user a readable heading while the slower answer is
     * being generated.
     */
    suspend fun generateAskTitle(
        ai: AiSettings,
        title: String,
        artist: String,
        album: String,
        question: String,
        targetLanguage: String,
    ): String {
        val prompt = buildAskTitlePrompt(title, artist, album, question, targetLanguage)
        val rawText = this.ai.generate(ai, prompt, temperature = 0.2f, useSearch = false)
        return cleanAskTitle(rawText, fallbackQuestion = question)
    }

    /**
     * 「余音 Gemini 文案」—— 给 Memory 卡片生成一句 40–80 字的感性短评，
     * 把 Memory 从「事实回顾」升格为「情绪回响」。
     *
     * 输入信号保留到最低：专辑名、艺人、年份、已评曲目均分、评过的曲目
     * 数量。不传播 song_notes / review 原文给 Gemini —— 笔记是本地私密。
     *
     * 返回的是纯文本（没有 tag），调用方直接落 `memory_copy_cache.copy`。
     *
     * Superseded for Memories by [generateAlbumMemoryNarration] (showcase v4,
     * P4). Its only caller left is `YoinRepository.getOrGenerateAlbumMemoryCopy`,
     * which nothing calls any more; both go in the P7 cleanup once that file
     * is free to edit.
     */
    suspend fun generateAlbumMemoryCopy(
        ai: AiSettings,
        albumName: String,
        artist: String?,
        year: Int?,
        averageRating: Float?,
        ratedSongCount: Int,
        totalSongCount: Int,
    ): String {
        val prompt = buildMemoryPrompt(
            albumName = albumName,
            artist = artist,
            year = year,
            averageRating = averageRating,
            ratedSongCount = ratedSongCount,
            totalSongCount = totalSongCount,
        )
        // Memory 文案不走搜索 —— 只要感性回响，不需要外网事实核对；更快也更便宜。
        return this.ai.generate(ai, prompt, temperature = 0.8f, useSearch = false)
            .trim()
            .removeSurrounding("\"")
            .takeIf { it.isNotEmpty() }
            ?: throw GeminiException("No content in AI response")
    }

    /**
     * Yoin's narration on a Memories diary page (showcase v4): one or two
     * sentences about how the listener listened to one album, then a question
     * that follows from the last fact.
     *
     * The input is local listening facts only (plays, date span, seasons,
     * track names, the top-rated track, note counts), already phrased in
     * [languageName] where they are words. The review and the notes' text are
     * never sent. [alreadySaid] is a counted title on the page whose facts the
     * narration must not repeat (null since the motif titles went, 2026-10-06).
     *
     * Bump [MEMORY_NARRATION_PROMPT_VERSION] whenever the prompt changes: the
     * caller keys its cache on it, so copy written by an older prompt is never
     * shown again.
     */
    suspend fun generateAlbumMemoryNarration(
        ai: AiSettings,
        languageName: String,
        facts: List<String>,
        alreadySaid: String?,
    ): MemoryNarrationText {
        val prompt = buildMemoryNarrationPrompt(
            languageName = languageName,
            facts = facts,
            alreadySaid = alreadySaid,
        )
        // Only the given facts may be used: no search. The Memories deck waits
        // on this call; past the timeout the local template stands in.
        val rawText = this.ai.generate(
            ai,
            prompt,
            temperature = 0.4f,
            useSearch = false,
            timeoutSeconds = MEMORY_NARRATION_TIMEOUT_SECONDS,
        )
        return parseMemoryNarration(rawText)
            ?: throw GeminiException("No usable narration in AI response")
    }

    /**
     * Memory 卡的 AI 拟题 —— 给用户写下的字（乐评或笔记）起一个 ≤14 字的标题。
     * 同一枚标题复用到首页 Jump Back In 的 memory 槽位。
     *
     * 这是「不上传 note/review 原文」教条的唯一豁免（design.md 拟题豁免，
     * 2026-07-26）：prompt 携带占用者原文 + 专辑背景元数据，让模型结合它
     * 自身对这张专辑的了解拟题。仅此用途 —— [generateAlbumMemoryCopy]
     * 的输入契约不变。
     */
    suspend fun generateAlbumMemoryTitle(
        ai: AiSettings,
        albumName: String,
        artist: String?,
        year: Int?,
        writingKind: String,
        writingText: String,
    ): String {
        val prompt = buildMemoryTitlePrompt(
            albumName = albumName,
            artist = artist,
            year = year,
            writingKind = writingKind,
            writingText = writingText,
        )
        // 拟题不走搜索：专辑背景用模型自身知识即可，标题要快。
        return this.ai.generate(ai, prompt, temperature = 0.2f, useSearch = false)
            .trim()
            .removeSurrounding("\"")
            .replace("\n", " ")
            .takeIf { it.isNotEmpty() }
            ?: throw GeminiException("No content in AI response")
    }

    /**
     * Translate lyric lines for inline display. This intentionally avoids
     * search: the model should preserve line count and phrasing, not fetch
     * facts about the song.
     */
    suspend fun translateLyricLines(
        ai: AiSettings,
        title: String,
        artist: String,
        lines: List<String>,
        targetLanguage: String = "English",
    ): Map<Int, String> {
        val prompt = buildLyricsTranslationPrompt(title, artist, lines, targetLanguage)
        val rawText = this.ai.generate(ai, prompt, temperature = 0.8f, useSearch = false)
        return parseLineTranslations(rawText, lines.size)
            .takeIf { it.isNotEmpty() }
            ?: throw GeminiException("No usable lyric translations in AI response")
    }

    /** The model ids a Custom endpoint offers. */
    suspend fun listCustomModels(ai: AiSettings): List<String> =
        this.ai.listModels(ai.prefs.customFormat, ai.prefs.customBaseUrl, ai.prefs.customKey)

    private class Searched(val appendix: String, val pages: Int)

    /**
     * Run [queries] when Yoin searches for this service, and turn the pages
     * into the block appended to the prompt. Built-in or no search: nothing.
     */
    private suspend fun searchFor(ai: AiSettings, queries: List<String>): Searched {
        if (ai.searchKind != AiSettings.SearchKind.External) return Searched("", 0)
        val hits = coroutineScope {
            queries.map { query -> async { this@GeminiService.ai.search(ai, query) } }.awaitAll().flatten()
        }
        val block = AiClient.contextBlock(hits)
        if (block.isEmpty()) return Searched("", 0)
        return Searched("\n\nSearch results:\n$block", hits.distinctBy { it.url }.size.coerceAtMost(AiClient.MAX_PAGES_IN_PROMPT))
    }

    private fun searchLead(ai: AiSettings, searched: Searched): String = when {
        ai.searchKind == AiSettings.SearchKind.BuiltIn -> "Use the search tool to find accurate information."
        searched.pages > 0 -> "Use the search results provided below to find accurate information."
        else -> "Answer from what you reliably know; write \"N/A\" for anything you are not sure of."
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

    private fun buildMemoryTitlePrompt(
        albumName: String,
        artist: String?,
        year: Int?,
        writingKind: String,
        writingText: String,
    ): String {
        val artistLine = artist?.takeIf(String::isNotBlank) ?: "Unknown artist"
        val yearLine = year?.toString() ?: "Unknown year"
        return """
You are titling a private music-journal entry. The user wrote the following
$writingKind about an album; give it a short title.

Album: $albumName
Artist: $artistLine
Year: $yearLine

Their $writingKind:
$writingText

Write ONE title that captures the heart of what they wrote, drawing on what
you know about this album's sound and background where it sharpens the title.
Same language as their text. At most 14 CJK characters or 34 latin characters.
No quotes, no emoji, no trailing period, no line break.
Output only the title itself.
        """.trimIndent()
    }

    private fun buildPrompt(
        title: String,
        artist: String,
        album: String,
        targetLanguage: String,
        searchLead: String,
    ): String = """
Search for and provide detailed information about the following song:

Song: $title
Artist: $artist
Album: $album

$searchLead Write all human-readable
answers in $targetLanguage. Respond strictly in the following tagged format.
If any information cannot be found, write "N/A" inside the tag.

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
        targetLanguage: String,
        searchLead: String,
    ): String = """
Answer the following question about the song "$title" from the album "$album" by $artist.
$searchLead
Write the title and answer in $targetLanguage.

Respond strictly in the following tagged format:

[TITLE]
A concise headline that summarises the question — no more than 8 words, no trailing punctuation. This is shown as the visual heading above your answer, so it should feel like a title, not a full sentence.
[/TITLE]

[ANSWER]
The detailed answer. No more than 120 words. Plain prose only — no citation markers, headers, or bullet points.
[/ANSWER]

Question: $question
    """.trimIndent()

    private fun buildAskTitlePrompt(
        title: String,
        artist: String,
        album: String,
        question: String,
        targetLanguage: String,
    ): String = """
Write a concise visual heading for an in-progress Gemini answer about this song.
Write the heading in $targetLanguage.

Song: $title
Artist: $artist
Album: $album
Question: $question

Return only the heading. No more than 8 words. No trailing punctuation. Do not
answer the question.
    """.trimIndent()

    private fun buildLyricsTranslationPrompt(
        title: String,
        artist: String,
        lines: List<String>,
        targetLanguage: String,
    ): String {
        val indexedLines = lines.mapIndexed { index, line -> "[$index] $line" }
            .joinToString("\n")
        return """
Translate these lyric lines into $targetLanguage.

Song: $title
Artist: $artist

Preserve line count and meaning. Keep imagery natural, concise, and singable.
Return strictly one tagged block per source line:

[L0]translation for line 0[/L0]
[L1]translation for line 1[/L1]

Lines:
$indexedLines
        """.trimIndent()
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

    /** Narration + closing question returned by [generateAlbumMemoryNarration]. */
    data class MemoryNarrationText(
        val narration: String,
        val question: String,
    )

    companion object {
        /** The model every install used before AI settings v2 (still Gemini's 「好」). */
        internal const val MODEL = AiSettings.GEMINI_GOOD

        /**
         * Version of the Memories narration prompt. v1 was the poetic one-liner
         * of [generateAlbumMemoryCopy]; v2 is the showcase v4 voice. Part of
         * the narration cache key: bump it with every prompt change.
         */
        const val MEMORY_NARRATION_PROMPT_VERSION = 2
        private const val MEMORY_NARRATION_TIMEOUT_SECONDS = 8L

        /**
         * The v2 narration prompt (the prototype's voice rules, twostate4.html
         * "Yoin's voice"): second person, past tense, one or two sentences from
         * the given listening facts only, then a question that follows from the
         * last fact. Never judges the music, never touches the listener's own
         * words, never names the mechanism.
         */
        internal fun buildMemoryNarrationPrompt(
            languageName: String,
            facts: List<String>,
            alreadySaid: String?,
        ): String {
            val factLines = facts.joinToString("\n") { fact -> "- $fact" }
            val saidLine = alreadySaid?.takeIf(String::isNotBlank)?.let { said ->
                "\nThe title already on the page reads: \"$said\". Do not repeat any fact it states.\n"
            }.orEmpty()
            return """
You are Yoin, a music app. You write a few words at the top of a listener's
private diary page for one album, speaking to the listener directly.

Listening facts recorded in the app (the only things you may mention):
$factLines
$saidLine
Rules:
- Write in $languageName.
- NARRATION: one or two short sentences, second person, past tense, built only
  from the facts above. Pick the one or two facts that say most about how they
  listened; do not list them all.
- QUESTION: one short question that follows directly from the last fact you
  stated, so the listener wants to write their own words. It ends with "?" in
  English or "？" in Chinese.
- Never judge, praise or describe the music, the artist, the album or its
  sound. Never guess at feelings, moods or reasons.
- Never quote, paraphrase or allude to anything the listener wrote. You have
  not read it.
- Never name the mechanism: no words such as "memory", "memories",
  "remember", "记忆", "回忆".
- Keep track names, months and relative dates as given.
- No quotation marks around your text, no emoji, no hashtags, no line breaks.

The voice, from other albums (never reuse their facts):
[NARRATION]37 plays since March, the last one two days ago.[/NARRATION]
[QUESTION]You gave it a 9.5. What earned it?[/QUESTION]
[NARRATION]你跨了三个季节回来听，最高分是《Undertow》的 9.0。[/NARRATION]
[QUESTION]那整张专辑呢？[/QUESTION]

Output exactly this and nothing else:
[NARRATION]...[/NARRATION]
[QUESTION]...[/QUESTION]
            """.trimIndent()
        }

        /**
         * Parses `[NARRATION]…[/NARRATION]` + `[QUESTION]…[/QUESTION]`. Null when
         * either part is missing or blank, or the question isn't a question.
         */
        fun parseMemoryNarration(rawText: String): MemoryNarrationText? {
            fun clean(text: String?): String? = text
                ?.replace(Regex("""\s*\n\s*"""), " ")
                ?.trim()
                ?.trim('"', '“', '”')
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            val narration = clean(extractTagContent(rawText, "NARRATION")) ?: return null
            val question = clean(extractTagContent(rawText, "QUESTION")) ?: return null
            if (!question.endsWith("?") && !question.endsWith("？")) return null
            return MemoryNarrationText(narration = narration, question = question)
        }

        /** The cached form of a narration; [parseMemoryNarration] reads it back. */
        fun encodeMemoryNarration(text: MemoryNarrationText): String =
            "[NARRATION]${text.narration}[/NARRATION]\n[QUESTION]${text.question}[/QUESTION]"

        // Matches any `[TAG]`, `[/TAG]`, `[ TAG ]`, `[ / TAG ]` etc. with
        // tag identifiers. Used both to extract content by tag name and
        // to scrub leftover markers out of fallback prose so the user never
        // sees raw `[TITLE]` / `[ANSWER]` text in the UI.
        private val TAG_MARKER_REGEX = Regex(
            pattern = """\[\s*/?\s*[A-Z][A-Z0-9_]*\s*\]""",
            option = RegexOption.IGNORE_CASE,
        )
        private val LINE_TRANSLATION_REGEX = Regex(
            pattern = """\[\s*L(\d+)\s*\](.*?)\[\s*/\s*L\1\s*\]""",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val LEADING_LINE_MARKER_REGEX = Regex(
            pattern = """^(?:[-*•]\s*)?(?:(?:[\[【(]\s*\d+\s*[]】)])(?:[.)：:、])?|\d+[.)：:、])\s*""",
        )

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

        fun cleanAskTitle(rawText: String, fallbackQuestion: String): String =
            stripTagMarkers(rawText)
                .lines()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.trim('"', '\'', '“', '”', '‘', '’')
                ?.removeSuffix(".")
                ?.removeSuffix("?")
                ?.removeSuffix("!")
                ?.removeSuffix("。")
                ?.removeSuffix("？")
                ?.removeSuffix("！")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: fallbackQuestion.trim()

        fun parseLineTranslations(rawText: String, lineCount: Int): Map<Int, String> {
            val tagged = LINE_TRANSLATION_REGEX.findAll(rawText)
                .mapNotNull { match ->
                    val index = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val text = cleanLineTranslation(match.groupValues[2])
                    if (index in 0 until lineCount && text.isNotBlank()) {
                        index to text
                    } else {
                        null
                    }
                }
                .toMap()
            if (tagged.isNotEmpty()) return tagged

            val plainLines = rawText.lines()
                .map(::cleanLineTranslation)
                .filter { it.isNotEmpty() }
            if (plainLines.size != lineCount) return emptyMap()
            return plainLines.mapIndexed { index, line -> index to cleanLineTranslation(line) }
                .filter { (_, line) -> line.isNotBlank() }
                .toMap()
        }

        internal fun cleanLineTranslation(text: String): String {
            var cleaned = stripTagMarkers(text).trim()
            repeat(3) {
                cleaned = cleaned.replace(LEADING_LINE_MARKER_REGEX, "").trim()
            }
            return cleaned
        }
    }
}

/** [httpCode] is set when the service answered with an HTTP error. */
class GeminiException(message: String, val httpCode: Int? = null) : Exception(message)
