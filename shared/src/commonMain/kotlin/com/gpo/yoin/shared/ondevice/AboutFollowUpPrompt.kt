package com.gpo.yoin.shared.ondevice

import com.gpo.yoin.shared.albumabout.YoinAlbumAbout

/**
 * One row of a song's About as the follow-up prompt reads it (Android `SongAboutEntry`, iOS
 * `SongAboutStore.Entry`): only what [AboutFollowUpPrompt.prepare] looks at.
 */
data class AboutFollowUpEntry(
    val kind: String,
    val entryKey: String,
    val titleDisplay: String,
    val artistDisplay: String,
    val titleText: String?,
    val promptText: String?,
    val answerText: String,
) {
    companion object {
        /** `AboutFollowUpEntry.KIND_CANONICAL`. */
        const val KIND_CANONICAL = "canonical"

        /** `AboutFollowUpEntry.CANON_REVIEW`. */
        const val CANON_REVIEW = "review"
    }
}

/**
 * On-device follow-up question prompt & validator for Now Playing's About -> Ask Gemini bar (owner 2026-11-22).
 *
 * Gemini Nano reads ONLY the About entries already on screen and proposes 2–3 short, natural follow-up questions.
 * Because Nano only suggests questions grounded in visible text (never answering factual trivia itself),
 * hallucination risk is minimal and tapping any chip hands the actual answer to cloud Gemini.
 *
 * Pure Kotlin, fully unit-tested. Moved from Android's `data/highlight/` to :shared on 2026-11-27 (parity p5)
 * unchanged, so iOS asks Apple Intelligence the same words; entries are [AboutFollowUpEntry] (Android maps its
 * `SongAboutEntry` rows). Pinned by `golden/ondevice-ai.json`.
 */
object AboutFollowUpPrompt {

    /** Output budget: 2–3 short questions in structured JSON (~80–115 tokens). */
    const val MAX_OUTPUT_TOKENS = 128

    /** Sampling for the follow-up call (Android AboutFollowUpGenerator). */
    const val TEMPERATURE = 0.45f
    const val TOP_K = 24

    /** Minimum About context length before asking Nano to suggest follow-ups. */
    const val MIN_CONTEXT_CHARS = 40

    /** Maximum About context length fed to Nano (keeps total prompt compact and fast). */
    const val MAX_CONTEXT_CHARS = 1_100

    const val MAX_QUESTIONS = 3

    const val MIN_ZH_CHARS = 4
    const val MAX_ZH_CHARS = 32
    const val MIN_EN_CHARS = 10
    const val MAX_EN_CHARS = 68
    const val MIN_EN_WORDS = 3
    const val MAX_EN_WORDS = 12

    /** Prepared input and stable cache fingerprint for a song's About entries. */
    data class Prepared(
        val title: String,
        val artist: String,
        val context: String,
        val asked: List<String>,
        val localeTag: String,
        val fingerprint: String,
    )

    /** Normalizes a locale tag into "zh" or "en". */
    fun normalizeLocale(languageTag: String): String =
        if (languageTag.lowercase().startsWith("zh")) "zh" else "en"

    /**
     * Extracts compact About text from [entries]. Returns null when there isn't enough substance to ground
     * meaningful follow-up questions (e.g., empty receipt or a single tiny metadata line).
     */
    fun prepare(
        entries: List<AboutFollowUpEntry>,
        asked: Collection<String>,
        localeTag: String,
    ): Prepared? {
        if (entries.isEmpty()) return null
        val first = entries.first()
        val title = first.titleDisplay.trim()
        val artist = first.artistDisplay.trim()
        val lang = normalizeLocale(localeTag)

        val parts = ArrayList<String>()
        var total = 0
        fun addSegment(label: String, text: String) {
            val clean = text.trim().replace(WHITESPACE, " ")
            if (clean.isEmpty() || total >= MAX_CONTEXT_CHARS) return
            val line = if (label.isNotEmpty()) "$label: $clean" else clean
            val remaining = MAX_CONTEXT_CHARS - total
            val piece = if (line.length <= remaining) line else line.take(remaining).trimEnd()
            if (piece.isNotEmpty()) {
                parts += piece
                total += piece.length
            }
        }

        // Prioritize the editorial review first, then canonical credits/facts, then recent Q&A.
        entries.firstOrNull { it.kind == AboutFollowUpEntry.KIND_CANONICAL && it.entryKey == AboutFollowUpEntry.CANON_REVIEW }
            ?.let { addSegment("Review", it.answerText) }
        entries.filter { it.kind == AboutFollowUpEntry.KIND_CANONICAL && it.entryKey != AboutFollowUpEntry.CANON_REVIEW }
            .forEach { addSegment(it.entryKey, it.answerText) }
        entries.filter { it.kind != AboutFollowUpEntry.KIND_CANONICAL }
            .take(3)
            .forEach { entry ->
                val heading = entry.titleText ?: entry.promptText ?: entry.entryKey
                addSegment(heading, entry.answerText)
            }

        val context = parts.joinToString("\n")
        if (context.length < MIN_CONTEXT_CHARS) return null

        val cleanAsked = asked.map(String::trim).filter(String::isNotEmpty).distinct()
        val fp = fingerprint(title, artist, lang, context, cleanAsked)
        return Prepared(
            title = title,
            artist = artist,
            context = context,
            asked = cleanAsked,
            localeTag = lang,
            fingerprint = fp,
        )
    }

    /**
     * System instruction for ML Kit GenAI Structured Output (`AboutFollowUpSchema`).
     * Kept concise because constrained decoding already guarantees the typed list format.
     */
    fun systemInstruction(localeTag: String): String = if (normalizeLocale(localeTag) == "zh") {
        "你是音乐应用里的追问建议助手。根据歌曲「关于」内容，提出 3 个听众看完后会顺着想深挖的简短中文问题（6到22个字，以问号结尾）。" +
            "具体指向创作背景、编曲、人物、版本或现场故事；不要问简介里已经直接给出答案的阅读理解题，也不要编造事实或重复已问问题。"
    } else {
        "Suggest 3 short follow-up questions (4 to 11 words, ending with a question mark) a listener would naturally " +
            "want to explore next after reading the song notes. Go deeper into live versions, recording stories, " +
            "influences, or album role; never ask quiz questions already answered in the notes or repeat asked ones."
    }

    /** User prompt paired with [systemInstruction] for structured output calls. */
    fun userPrompt(prepared: Prepared): String = if (prepared.localeTag == "zh") {
        buildString {
            if (prepared.title.isNotEmpty()) {
                append("歌曲：").append(prepared.title)
                if (prepared.artist.isNotEmpty()) append(" — ").append(prepared.artist)
                append('\n')
            }
            if (prepared.asked.isNotEmpty()) {
                append("已经问过（不要重复）：").append(prepared.asked.joinToString("；")).append('\n')
            }
            append("关于内容：\n").append(prepared.context).append('\n')
        }
    } else {
        buildString {
            if (prepared.title.isNotEmpty()) {
                append("Song: ").append(prepared.title)
                if (prepared.artist.isNotEmpty()) append(" — ").append(prepared.artist)
                append('\n')
            }
            if (prepared.asked.isNotEmpty()) {
                append("Already asked (skip): ").append(prepared.asked.joinToString("; ")).append('\n')
            }
            append("Notes:\n").append(prepared.context).append('\n')
        }
    }

    /** Builds the Gemini Nano plain-text fallback prompt for 2–3 grounded follow-up questions. */
    fun buildPrompt(prepared: Prepared): String = if (prepared.localeTag == "zh") {
        buildString {
            append(
                "你是一个音乐应用里的追问建议助手。请根据下面给出的歌曲「关于」内容，提出 3 个听众看完后会顺着想继续深挖的简短问题。\n" +
                    "要求：\n" +
                    "1. 每个问题用简体中文，6到24个字，以问号结尾，具体指向简介里提到的创作背景、编曲、人物、版本或现场故事，不要泛泛套话。\n" +
                    "2. 顺着简介往深处问（创作细节、不同版本对比、录音趣事或风格渊源），不要问简介里已经直接给出答案的阅读理解题，也不要编造事实或重复已问问题。\n" +
                    "3. 只输出 JSON 字符串数组，格式如 [\"问题1？\",\"问题2？\",\"问题3？\"]，不要任何其他文字。\n\n",
            )
            append(userPrompt(prepared))
        }
    } else {
        buildString {
            append(
                "Suggest 3 short follow-up questions a listener would naturally want to explore next after reading the song notes below.\n" +
                    "Rules:\n" +
                    "1. English only, 4 to 11 words each (max 64 characters), ending with a question mark.\n" +
                    "2. Go deeper beyond what is already answered in the notes (live versions, recording stories, influences, album role) — NEVER ask quiz questions whose answer is already stated verbatim in the notes.\n" +
                    "3. Do not invent facts or repeat any already-asked questions.\n" +
                    "4. Output a JSON array of strings only, e.g. [\"Question 1?\",\"Question 2?\",\"Question 3?\"]. No other text.\n\n",
            )
            append(userPrompt(prepared))
        }
    }

    private val WHITESPACE = Regex("\\s+")
    private val LEADING_MARKER = Regex("^\\s*(?:[-*•]|\\d+[.)、])\\s*")
    private val META_PREFIXES = listOf(
        "here are", "sure", "certainly", "follow-up", "questions:",
        "好的", "以下是", "下面", "推荐问题", "追问：", "问题：",
    )

    /**
     * Parses Nano's raw text output into up to [MAX_QUESTIONS] validated, deduplicated follow-up questions that do
     * not repeat [asked]. Parses JSON string arrays properly (preserving nested CJK smart quotes like `“”`), and
     * falls back to bulleted/numbered lines if Nano misses JSON.
     */
    fun parse(
        answer: String?,
        asked: Collection<String>,
        localeTag: String,
    ): List<String> {
        if (answer.isNullOrBlank()) return emptyList()
        val body = answer.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val rawCandidates: List<String> = extractJsonArrayStrings(body)
            ?: body.lineSequence().map { it.trim() }.filter(String::isNotEmpty).toList()
        return validateQuestions(rawCandidates, asked, localeTag)
    }

    /**
     * Validates, sanitizes, and deduplicates a candidate question list (shared by structured output
     * [AboutFollowUpSchema] and raw-text [parse]).
     */
    fun validateQuestions(
        rawQuestions: List<String>,
        asked: Collection<String>,
        localeTag: String,
    ): List<String> {
        val lang = normalizeLocale(localeTag)
        val seen = asked.mapTo(HashSet(), YoinAlbumAbout::questionKey)
        val out = ArrayList<String>(MAX_QUESTIONS)
        for (raw in rawQuestions) {
            val clean = sanitize(raw, lang) ?: continue
            val key = YoinAlbumAbout.questionKey(clean)
            if (key.isEmpty() || !seen.add(key)) continue
            out += clean
            if (out.size == MAX_QUESTIONS) break
        }
        return out
    }

    /**
     * Extracts string elements from a JSON array literal `[ "...", "..." ]` without corrupting nested CJK smart
     * quotes (`“”`) inside the strings. Returns null when [body] does not contain a valid JSON string array.
     */
    fun extractJsonArrayStrings(body: String): List<String>? {
        val start = body.indexOf('[')
        val end = body.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val slice = body.substring(start + 1, end)
        val out = ArrayList<String>()
        var i = 0
        while (i < slice.length) {
            val quoteIdx = slice.indexOf('"', i)
            if (quoteIdx < 0) break
            val buf = StringBuilder()
            var j = quoteIdx + 1
            var closed = false
            while (j < slice.length) {
                val ch = slice[j]
                if (ch == '\\' && j + 1 < slice.length) {
                    val next = slice[j + 1]
                    when (next) {
                        '"', '\\', '/' -> buf.append(next)
                        'n' -> buf.append(' ')
                        'r', 't' -> buf.append(' ')
                        else -> buf.append(next)
                    }
                    j += 2
                } else if (ch == '"') {
                    closed = true
                    j++
                    break
                } else {
                    buf.append(ch)
                    j++
                }
            }
            if (!closed) break
            out += buf.toString()
            i = j
        }
        return out.takeIf { it.isNotEmpty() }
    }

    fun sanitize(raw: String, localeTag: String): String? {
        val lang = normalizeLocale(localeTag)
        val cleaned = raw
            .replace(LEADING_MARKER, "")
            .trim()
            .trim('"', '\'', ',', '，', '[', ']')
            .replace(WHITESPACE, " ")
            .trim()
        if (cleaned.isEmpty() || '\n' in cleaned) return null
        if ('{' in cleaned || '}' in cleaned) return null
        val lower = cleaned.lowercase()
        if (META_PREFIXES.any { lower.startsWith(it) }) return null

        return if (lang == "zh") {
            if (cleaned.length !in MIN_ZH_CHARS..MAX_ZH_CHARS) return null
            if (cleaned.count { it in '\u4E00'..'\u9FFF' } < 3) return null
            if (cleaned.endsWith("？") || cleaned.endsWith("?")) {
                cleaned.dropLast(1) + "？"
            } else {
                "$cleaned？"
            }
        } else {
            if (cleaned.length !in MIN_EN_CHARS..MAX_EN_CHARS) return null
            if (cleaned.any { it in '\u4E00'..'\u9FFF' }) return null
            val words = cleaned.split(' ').filter(String::isNotEmpty)
            if (words.size !in MIN_EN_WORDS..MAX_EN_WORDS) return null
            if (cleaned.endsWith('?')) cleaned else "$cleaned?"
        }
    }

    /** Merges on-device follow-ups ahead of fallback quick prompts, removing already-asked and duplicates. */
    fun mergeWithFallback(
        suggested: List<String>,
        fallback: List<String>,
        asked: Collection<String>,
    ): List<String> {
        val seen = asked.mapTo(HashSet(), YoinAlbumAbout::questionKey)
        val out = ArrayList<String>(suggested.size + fallback.size)
        for (item in suggested + fallback) {
            val key = YoinAlbumAbout.questionKey(item)
            if (key.isNotEmpty() && seen.add(key)) out += item
        }
        return out
    }

    private fun fingerprint(
        title: String,
        artist: String,
        lang: String,
        context: String,
        asked: List<String>,
    ): String {
        var hash = -0x340d631b7bdddcdbL
        fun mix(s: String) {
            for (ch in s) {
                hash = hash xor ch.code.toLong()
                hash *= 0x100000001b3L
            }
            hash = hash xor 0x1FL
            hash *= 0x100000001b3L
        }
        mix(title)
        mix(artist)
        mix(lang)
        mix(context)
        asked.forEach(::mix)
        return hash.toULong().toString(16)
    }
}
