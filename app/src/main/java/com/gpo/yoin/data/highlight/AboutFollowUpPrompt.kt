package com.gpo.yoin.data.highlight

import com.gpo.yoin.data.local.SongAboutEntry

/**
 * On-device follow-up question prompt & validator for Now Playing's About -> Ask Gemini bar (owner 2026-11-22).
 *
 * Gemini Nano reads ONLY the About entries already on screen and proposes 2–3 short, natural follow-up questions.
 * Because Nano only suggests questions grounded in visible text (never answering factual trivia itself),
 * hallucination risk is minimal and tapping any chip hands the actual answer to cloud Gemini.
 *
 * Pure Kotlin (no android.*), fully unit-tested.
 */
object AboutFollowUpPrompt {

    /** Output budget: 2–3 short questions in a JSON string array (~70–95 tokens). */
    const val MAX_OUTPUT_TOKENS = 112

    /** Minimum About context length before asking Nano to suggest follow-ups. */
    const val MIN_CONTEXT_CHARS = 40

    /** Maximum About context length fed to Nano (keeps total prompt compact and fast). */
    const val MAX_CONTEXT_CHARS = 1_100

    const val MAX_QUESTIONS = 3

    const val MIN_ZH_CHARS = 4
    const val MAX_ZH_CHARS = 24
    const val MIN_EN_CHARS = 10
    const val MAX_EN_CHARS = 64
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
        entries: List<SongAboutEntry>,
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
            val clean = text.trim().replace(Regex("\\s+"), " ")
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
        entries.firstOrNull { it.kind == SongAboutEntry.KIND_CANONICAL && it.entryKey == SongAboutEntry.CANON_REVIEW }
            ?.let { addSegment("Review", it.answerText) }
        entries.filter { it.kind == SongAboutEntry.KIND_CANONICAL && it.entryKey != SongAboutEntry.CANON_REVIEW }
            .forEach { addSegment(it.entryKey, it.answerText) }
        entries.filter { it.kind != SongAboutEntry.KIND_CANONICAL }
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

    /** Builds the Gemini Nano prompt for 2–3 grounded follow-up questions. */
    fun buildPrompt(prepared: Prepared): String = if (prepared.localeTag == "zh") {
        buildString {
            append(
                "你是一个音乐应用里的追问建议助手。请只根据下面给出的歌曲「关于」内容，提出 3 个听众看完后会顺着想继续问的简短问题。\n" +
                    "要求：\n" +
                    "1. 每个问题用简体中文，6到20个字，以问号结尾，具体指向简介里提到的创作背景、编曲、人物、版本或故事，不要泛泛套话。\n" +
                    "2. 不要编造简介之外的事实，不要重复已经问过的问题。\n" +
                    "3. 只输出 JSON 字符串数组，格式如 [\"问题1？\",\"问题2？\",\"问题3？\"]，不要任何其他文字。\n\n",
            )
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
            append(
                "Suggest 3 short follow-up questions a listener might naturally ask next after reading the song notes below.\n" +
                    "Rules:\n" +
                    "1. English only, 4 to 10 words each (max 60 characters), ending with a question mark.\n" +
                    "2. Ground every question in details actually mentioned in the notes; avoid generic filler or inventing facts.\n" +
                    "3. Do not repeat any already-asked questions.\n" +
                    "4. Output a JSON array of strings only, e.g. [\"Question 1?\",\"Question 2?\",\"Question 3?\"]. No other text.\n\n",
            )
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

    private val QUOTED_ITEM = Regex("\"([^\"]{3,100})\"")
    private val LEADING_MARKER = Regex("^\\s*(?:[-*•]|\\d+[.)、])\\s*")
    private val META_PREFIXES = listOf(
        "here are", "sure", "certainly", "follow-up", "questions:",
        "好的", "以下是", "下面", "推荐问题", "追问：", "问题：",
    )

    /**
     * Parses Nano's output into up to [MAX_QUESTIONS] validated, deduplicated follow-up questions that do not
     * repeat [asked]. Accepts a JSON string array as well as bulleted/numbered lines if Nano misses JSON.
     */
    fun parse(
        answer: String?,
        asked: Collection<String>,
        localeTag: String,
    ): List<String> {
        if (answer.isNullOrBlank()) return emptyList()
        val lang = normalizeLocale(localeTag)
        val body = answer.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .replace('“', '"')
            .replace('”', '"')
            .trim()

        val rawCandidates: List<String> = if (body.contains('[') && body.contains('"')) {
            val arrayPart = body.substringAfter('[').substringBeforeLast(']')
            QUOTED_ITEM.findAll(arrayPart).map { it.groupValues[1] }.toList()
        } else {
            body.lineSequence().map { it.trim() }.filter(String::isNotEmpty).toList()
        }

        val seen = asked.mapTo(HashSet(), SongAboutEntry::normalize)
        val out = ArrayList<String>(MAX_QUESTIONS)
        for (raw in rawCandidates) {
            val clean = sanitize(raw, lang) ?: continue
            val key = SongAboutEntry.normalize(clean)
            if (key.isEmpty() || !seen.add(key)) continue
            out += clean
            if (out.size == MAX_QUESTIONS) break
        }
        return out
    }

    internal fun sanitize(raw: String, localeTag: String): String? {
        val lang = normalizeLocale(localeTag)
        val cleaned = raw
            .replace(LEADING_MARKER, "")
            .trim()
            .trim('"', '\'', ',', '，', '[', ']')
            .replace(Regex("\\s+"), " ")
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
        val seen = asked.mapTo(HashSet(), SongAboutEntry::normalize)
        val out = ArrayList<String>(suggested.size + fallback.size)
        for (item in suggested + fallback) {
            val key = SongAboutEntry.normalize(item)
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
        return java.lang.Long.toHexString(hash)
    }
}
