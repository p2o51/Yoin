package com.gpo.yoin.data.remote

import com.gpo.yoin.data.album.AlbumAboutGrounding
import com.gpo.yoin.data.album.AlbumAiCredits
import com.gpo.yoin.data.lyrics.isChineseTargetLanguage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * The album About prompts (owner + Carbon 2026-11-25), in the song About's style: one searched call, a
 * tagged reply. [CREDITS] holds a JSON object whose every item carries a confidence; anything below
 * "medium", anything missing and anything that reads like a placeholder is dropped, so the credits line
 * never shows a guess. [ABOUT] is the editorial paragraph in the AI language. The service's own facts
 * (label, dates, version, track list) go in as ground truth.
 */
internal object AlbumAboutPrompts {

    /** Tracks listed in the prompt; a box set's hundredth track adds nothing to the album's background. */
    const val MAX_PROMPT_TRACKS = 40

    /** The service's album notes quoted in the prompt, at most this long. */
    const val MAX_NOTES_CHARS = 1_500

    /** Names kept per credit item: the line stays a line. */
    const val MAX_NAMES = 4

    /** The search queries for [grounding] (the second only when the service allows two). */
    fun searchQueries(grounding: AlbumAboutGrounding, count: Int): List<String> = buildList {
        add("${grounding.album} ${grounding.artist} ${creditsKeywords(grounding.album + grounding.artist)}")
        if (count > 1) add("${grounding.album} ${grounding.artist} ${backgroundKeywords(grounding.album + grounding.artist)}")
    }.take(count.coerceAtLeast(1))

    /**
     * The About prompt. When the service already wrote the album's notes ([AlbumAboutGrounding.serviceNotes],
     * Apple Music), the same call asks for the credits only: no [ABOUT] block, the notes as one more source.
     */
    fun buildAboutPrompt(grounding: AlbumAboutGrounding, targetLanguage: String, searchLead: String): String =
        if (grounding.serviceNotes != null) {
            buildCreditsOnlyPrompt(grounding, targetLanguage, searchLead)
        } else {
            """
Write the About section of one album for a music app: its album-level credits and an editorial
background paragraph, the way Apple Music's album notes read.

${groundingLines(grounding)}

$searchLead The facts above come from the music service and are correct for this release:
build on them and never contradict them.

Respond strictly in the tagged format below, with nothing outside the tags.

${creditsBlock(targetLanguage)}

[ABOUT]
One objective paragraph in $targetLanguage, ${blurbLength(targetLanguage)}: the album's place in the
artist's career, its sound or concept, and the one behind-the-scenes fact most worth knowing.
Editorial and factual, like Apple Music's album notes. No hype or superlatives, no track-by-track
listing, no rating, no second person, no headings, lists or citation markers. Plain prose.
If you know too little about this album to write it without guessing, write N/A.
[/ABOUT]
            """.trimIndent()
        }

    private fun buildCreditsOnlyPrompt(grounding: AlbumAboutGrounding, targetLanguage: String, searchLead: String) = """
Find the album-level credits of one album for a music app.

${groundingLines(grounding)}

$searchLead The facts above come from the music service and are correct for this release:
build on them and never contradict them.

Respond strictly with the tagged block below, with nothing outside the tags.

${creditsBlock(targetLanguage)}
    """.trimIndent()

    private fun creditsBlock(targetLanguage: String) = """
[CREDITS]
One JSON object with exactly these keys. Each value is null or an object whose "confidence" is
"high" or "medium":
{
  "label": {"names": ["record label"], "confidence": "high"},
  "producers": {"names": ["producer"], "confidence": "high"},
  "mastering": {"names": ["mastering engineer"], "confidence": "high"},
  "mixing": {"names": ["mixing engineer"], "confidence": "high"},
  "recorded": {"time": "when it was recorded", "studios": ["studio, city"], "confidence": "high"},
  "source": {"work": "the film, game, anime, show or artist it belongs to", "confidence": "high"},
  "first_released": {"date": "when the original album first came out", "confidence": "high"}
}
Rules for CREDITS:
- Album-level credits of this release only: not one track's, not another edition's.
- "high" = stated in the search results or the album's liner notes; "medium" = well documented
  elsewhere. Anything less certain is null. Never guess, and never infer from the artist's other albums.
- Unknown = null. Never write "N/A", "unknown", "various" or any placeholder inside a value.
- "label": null when the service already gave the record label above.
- "recorded": "time" as a month and year or a range; "studios" as studio names with their city.
  Either may be null inside the object; the whole item is null when neither is known.
- "source": only for a soundtrack, music from a game, anime, film or show, or a tribute album;
  otherwise null.
- "first_released": only when this release is a reissue, remaster, or deluxe or anniversary edition
  of an earlier album; otherwise null.
- Names exactly as credited, in their original script. Dates in $targetLanguage.
[/CREDITS]
    """.trimIndent()

    fun buildAskPrompt(
        grounding: AlbumAboutGrounding,
        question: String,
        targetLanguage: String,
        searchLead: String,
    ): String = """
Answer the following question about the album "${grounding.album}" by ${grounding.artist}.

${groundingLines(grounding)}

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

    /** The service's facts, one per line; what it doesn't say is left out rather than written as unknown. */
    internal fun groundingLines(grounding: AlbumAboutGrounding): String = buildList {
        add("Album: ${grounding.album}")
        add("Artist: ${grounding.artist}")
        grounding.version?.takeIf(String::isNotBlank)?.let { add("Edition: $it") }
        grounding.releaseType?.let { add("Release type: $it") }
        grounding.releaseDate?.let { add("This release's date: $it") }
        grounding.originalReleaseDate?.takeIf { it != grounding.releaseDate }?.let { add("Original release date: $it") }
        if (grounding.labels.isNotEmpty()) add("Record label (from the service): ${grounding.labels.joinToString(", ")}")
        grounding.genre?.takeIf(String::isNotBlank)?.let { add("Genre: $it") }
        grounding.totalDurationSec?.takeIf { it > 0 }?.let { add("Length: ${it / 60} min") }
        grounding.serviceNotes?.let { notes ->
            add("Album notes (from the service): ${notes.replace(Regex("\\s+"), " ").take(MAX_NOTES_CHARS)}")
        }
        if (grounding.tracks.isNotEmpty()) {
            add("Tracks (${grounding.tracks.size}):")
            grounding.tracks.take(MAX_PROMPT_TRACKS).forEachIndexed { i, title -> add("${i + 1}. $title") }
            if (grounding.tracks.size > MAX_PROMPT_TRACKS) add("…")
        }
    }.joinToString("\n")

    internal fun blurbLength(targetLanguage: String): String = when {
        targetLanguage.isChineseTargetLanguage() -> "180 to 240 Chinese characters"
        targetLanguage == "Japanese" || targetLanguage == "Korean" -> "about as long as 120 to 160 English words"
        else -> "120 to 160 words"
    }

    private fun creditsKeywords(text: String): String = when {
        text.any { it in '가'..'힣' } -> "앨범 크레딧 프로듀서 레이블"
        text.any { it in '぀'..'ヿ' } -> "アルバム クレジット プロデューサー レーベル"
        text.any { it in '一'..'鿿' } -> "專輯 製作人 唱片公司 錄音"
        else -> "album credits producer label recorded"
    }

    private fun backgroundKeywords(text: String): String = when {
        text.any { it in '가'..'힣' } -> "앨범 리뷰 배경"
        text.any { it in '぀'..'ヿ' } -> "アルバム 解説 制作背景"
        text.any { it in '一'..'鿿' } -> "專輯 背景 樂評"
        else -> "album review background"
    }

    // -----------------------------------------------------------------------------------------------
    // Parsing
    // -----------------------------------------------------------------------------------------------

    /**
     * The credits and paragraph of one reply; either may be missing. [answered] tells "the model answered in
     * the format and knew nothing" (a [CREDITS] or [ABOUT] block, even an empty or N/A one) from a reply that
     * isn't an answer at all (empty, refused, off-format): only the first is kept as done.
     */
    data class Parsed(val credits: AlbumAiCredits, val blurb: String?, val answered: Boolean)

    fun parseAboutResponse(rawText: String): Parsed = Parsed(
        credits = parseCredits(extractTag(rawText, "CREDITS")),
        blurb = cleanBlurb(extractTag(rawText, "ABOUT")),
        answered = hasTag(rawText, "CREDITS") || hasTag(rawText, "ABOUT"),
    )

    private fun hasTag(text: String, tag: String): Boolean =
        Regex("""\[\s*$tag\s*]""", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /**
     * The [CREDITS] JSON as [AlbumAiCredits]: items below "medium" confidence, without a confidence, or
     * holding only placeholders are dropped. Tolerates a ```json fence and prose around the object;
     * unreadable JSON is no credits at all.
     */
    fun parseCredits(text: String?): AlbumAiCredits {
        val body = text ?: return AlbumAiCredits.None
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return AlbumAiCredits.None
        val root = runCatching { LenientJson.parseToJsonElement(body.substring(start, end + 1)) }
            .getOrNull() as? JsonObject ?: return AlbumAiCredits.None
        fun item(key: String): JsonObject? = (root[key] as? JsonObject)?.takeIf(::confident)
        fun names(key: String): List<String> = item(key)?.let { names(it["names"]) }.orEmpty()
        val recorded = item("recorded")
        return AlbumAiCredits(
            label = names("label"),
            producers = names("producers"),
            mastering = names("mastering"),
            mixing = names("mixing"),
            recordedTime = recorded?.let { text(it["time"]) },
            recordedStudios = recorded?.let { names(it["studios"]) }.orEmpty(),
            source = item("source")?.let { text(it["work"]) },
            firstReleased = item("first_released")?.let { text(it["date"]) },
        )
    }

    private fun confident(item: JsonObject): Boolean {
        val confidence = (item["confidence"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        return confidence == "high" || confidence == "medium"
    }

    private fun names(element: JsonElement?): List<String> {
        val values = when (element) {
            null, JsonNull -> emptyList()
            is JsonArray -> element.mapNotNull { text(it) }
            // A model that writes "A, B" instead of ["A", "B"] still gets its names in.
            is JsonPrimitive -> text(element)?.split(Regex("\\s*[,、;；]\\s*")).orEmpty()
            else -> emptyList()
        }
        return values.mapNotNull(::cleanValue).distinct().take(MAX_NAMES)
    }

    private fun text(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let(::cleanValue)

    /** A value as shown, or null when it is blank or a placeholder ("N/A", "unknown", "未知"…). */
    internal fun cleanValue(value: String): String? {
        val text = value.replace(Regex("\\s+"), " ").trim().trim('"', '“', '”').trim()
        if (text.isEmpty()) return null
        return text.takeUnless { it.lowercase().trimEnd('.', '。') in Placeholders }
    }

    private val Placeholders = setOf(
        "n/a", "na", "null", "none", "unknown", "not known", "various", "various artists", "tbd", "-", "—", "?",
        "未知", "不详", "不明", "无", "暂无", "無", "不詳", "未知的",
    )

    /** One paragraph: line breaks folded (no space between CJK sentences), markdown bold and "N/A" dropped. */
    internal fun cleanBlurb(text: String?): String? {
        val raw = text?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (cleanValue(raw) == null) return null
        val lines = raw.replace("**", "").lines().map(String::trim).filter(String::isNotEmpty)
        val joined = StringBuilder()
        lines.forEach { line ->
            if (joined.isNotEmpty()) {
                val last = joined.last()
                if (!(last.isCjk() && line.first().isCjk())) joined.append(' ')
            }
            joined.append(line)
        }
        return joined.toString().takeIf(String::isNotEmpty)
    }

    private fun Char.isCjk(): Boolean = code in 0x3000..0x9FFF || code in 0xFF00..0xFFEF

    private fun extractTag(text: String, tag: String): String? {
        val open = Regex("""\[\s*$tag\s*]""", RegexOption.IGNORE_CASE).find(text) ?: return null
        val close = Regex("""\[\s*/\s*$tag\s*]""", RegexOption.IGNORE_CASE).find(text, open.range.last + 1)
        return text.substring(open.range.last + 1, close?.range?.first ?: text.length).trim().takeIf(String::isNotEmpty)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val LenientJson = Json {
        isLenient = true
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }
}
