package com.gpo.yoin.data.highlight

import com.gpo.yoin.data.model.Lyrics

/**
 * The lyric-highlight question put to Gemini Nano, and the check on its answer.
 *
 * Nano only answers a multiple-choice question: the lyrics go out numbered and the model names a line range
 * ("{"start": 7, "end": 8}"). What Yoin shows is always the original lines, so a model that drifts or
 * translates can never put words in the song's mouth; an answer that isn't a valid range is dropped.
 *
 * Pure Kotlin (no android.*), so the whole contract is unit-tested.
 */
object LyricHighlightPrompt {

    /** One numbered line as sent to the model; [startMs] is null for unsynced lyrics. */
    data class Line(val number: Int, val text: String, val startMs: Long?)

    /** The lyrics as the model sees them, plus a fingerprint so a changed lyric is picked again. */
    data class Prepared(val lines: List<Line>, val fingerprint: String) {
        fun line(number: Int): Line? = lines.getOrNull(number - 1)?.takeIf { it.number == number }
    }

    /** A validated pick: 1–[MAX_PICK_LINES] consecutive original lines. */
    data class Pick(val first: Line, val last: Line, val text: String) {
        val startMs: Long? get() = first.startMs
    }

    /** Songs with fewer distinct sung lines than this (mostly instrumentals and snippets) are skipped. */
    const val MIN_LINES = 4

    /** Lines sent at most. Long songs are mostly repeats; 90 lines stay well under Nano's 4,000-token window. */
    const val MAX_LINES = 90

    const val MAX_PICK_LINES = 2

    /** The longest range taken (cut to [MAX_PICK_LINES]); anything longer is treated as a bad answer. */
    const val MAX_OVERRUN_LINES = 4

    /** A highlight longer than this (both lines together) no longer reads as a quote on a card. */
    const val MAX_PICK_CHARS = 72

    /** Output budget: the JSON answer is ~15 tokens. */
    const val MAX_OUTPUT_TOKENS = 24

    /**
     * Numbered, de-duplicated lines. Repeats of a line already sent (choruses) are left out, so the model
     * judges each line once and the first occurrence, the one a listener meets first, is what gets picked.
     * Returns null when too little is sung to choose from.
     */
    fun prepare(lyrics: Lyrics): Prepared? {
        val raw: List<Pair<String, Long?>> = when (lyrics) {
            is Lyrics.Synced -> lyrics.lines.map { it.text to it.startMs }
            is Lyrics.Unsynced -> lyrics.text.lineSequence().map { it to null }.toList()
        }
        val seen = HashSet<String>()
        val lines = ArrayList<Line>()
        for ((text, startMs) in raw) {
            val clean = text.trim()
            if (!isSung(clean)) continue
            if (!seen.add(normalize(clean))) continue
            lines += Line(number = lines.size + 1, text = clean, startMs = startMs)
            if (lines.size == MAX_LINES) break
        }
        if (lines.size < MIN_LINES) return null
        return Prepared(lines, fingerprint(lines))
    }

    /** The system half of the prompt: the rules. Kept short; Nano follows short rules best. */
    const val SYSTEM_INSTRUCTION: String =
        "You pick the single most memorable moment in a song's lyrics: the line or two a listener would " +
            "quote, write down, or remember the song by. Prefer vivid images and emotional turns over " +
            "titles repeated, filler (\"oh\", \"yeah\", \"la la\"), or plain narration. " +
            "Choose one line or two consecutive lines. " +
            "Answer with JSON only, in the form {\"start\": N, \"end\": M}, where N and M are line numbers " +
            "and M is N or N+1. No other text."

    /** The user half: the song and its numbered lines. */
    fun userPrompt(title: String, artist: String, prepared: Prepared): String = buildString {
        append("Song: ").append(title.trim()).append(" — ").append(artist.trim()).append('\n')
        append("Lyrics:\n")
        for (line in prepared.lines) {
            append(line.number).append(". ").append(line.text).append('\n')
        }
    }

    private val START = Regex("\"?start\"?\\s*[:=]\\s*(\\d{1,3})", RegexOption.IGNORE_CASE)
    private val END = Regex("\"?end\"?\\s*[:=]\\s*(\\d{1,3})", RegexOption.IGNORE_CASE)
    private val BARE_RANGE = Regex("^\\s*(\\d{1,3})\\s*(?:[-–,]\\s*(\\d{1,3}))?\\s*$")

    /**
     * Reads the model's answer. Accepts the asked-for JSON and the common near-misses (code fences,
     * "7-8"); a single number means a one-line pick. Null for anything that isn't a valid range of
     * this song's lines, or a pick that wouldn't fit a card.
     */
    fun parse(answer: String?, prepared: Prepared): Pick? {
        if (answer.isNullOrBlank()) return null
        val body = answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        var start = START.find(body)?.groupValues?.get(1)?.toIntOrNull()
        var end = END.find(body)?.groupValues?.get(1)?.toIntOrNull()
        if (start == null) {
            val bare = BARE_RANGE.find(body) ?: return null
            start = bare.groupValues[1].toIntOrNull()
            end = bare.groupValues[2].toIntOrNull()
        }
        start ?: return null
        val asked = end ?: start
        // A slightly long range (Nano often answers three lines) keeps its opening lines; a wild one is a drift.
        if (asked < start || asked - start + 1 > MAX_OVERRUN_LINES) return null
        val last = minOf(asked, start + MAX_PICK_LINES - 1)
        val first = prepared.line(start) ?: return null
        val second = prepared.line(last) ?: return null
        val text = if (first === second) first.text else first.text + "\n" + second.text
        if (text.replace("\n", "").length > MAX_PICK_CHARS) return trimToFit(first, second)
        return Pick(first, second, text)
    }

    /** A two-line pick that runs too long keeps its first line when that line fits alone. */
    private fun trimToFit(first: Line, second: Line): Pick? {
        if (first === second || first.text.length > MAX_PICK_CHARS) return null
        return Pick(first, first, first.text)
    }

    /** Something a person sings: has a letter in it and isn't only a bracketed stage note like "(Instrumental)". */
    internal fun isSung(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text.none(Char::isLetter)) return false
        val inner = text.trim()
        if ((inner.startsWith("(") && inner.endsWith(")")) || (inner.startsWith("[") && inner.endsWith("]")) ||
            (inner.startsWith("（") && inner.endsWith("）"))
        ) {
            return false
        }
        return true
    }

    private fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    /** FNV-1a over the sent lines: cheap, stable across runs, changes whenever the lyric does. */
    internal fun fingerprint(lines: List<Line>): String {
        var hash = -0x340d631b7bdddcdbL
        for (line in lines) {
            for (ch in line.text) {
                hash = hash xor ch.code.toLong()
                hash *= 0x100000001b3L
            }
            hash = hash xor '\n'.code.toLong()
            hash *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(hash)
    }
}
