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

    /**
     * One numbered line as sent to the model; [startMs] is null for unsynced lyrics. [source] is its place in
     * the song itself: repeats are left out, so two neighbouring numbers can be far apart in the song.
     */
    data class Line(val number: Int, val text: String, val startMs: Long?, val source: Int = number - 1)

    /** The lyrics as the model sees them, plus a fingerprint so a changed lyric is picked again. */
    data class Prepared(val lines: List<Line>, val fingerprint: String) {
        fun line(number: Int): Line? = lines.getOrNull(number - 1)?.takeIf { it.number == number }
    }

    /** A validated pick: 1–[MAX_PICK_LINES] lines that follow each other in the song. */
    data class Pick(val first: Line, val last: Line, val text: String) {
        val startMs: Long? get() = first.startMs
    }

    /** Songs with fewer distinct sung lines than this (mostly instrumentals and snippets) are skipped. */
    const val MIN_LINES = 4

    /** Lines sent at most. Long songs are mostly repeats; 90 lines stay well under Nano's 4,000-token window. */
    const val MAX_LINES = 90

    /** Owner 2026-11-08: a passage of two to four lines that reads as one thought. */
    const val MIN_PICK_LINES = 2
    const val MAX_PICK_LINES = 4

    /** A passage longer than this (all its lines together) no longer fits a card; its last lines are dropped. */
    const val MAX_PICK_CHARS = 110

    /**
     * Part of every fingerprint: a song picked under an older question is picked again (v2: two to four
     * lines, owner 2026-11-08; v3: echoes left out; v4: never one line when the song has a neighbour).
     */
    const val PROMPT_VERSION = "v4:"

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
        // Counts every sung line, repeats included, so a pick can tell lines that follow each other in the song.
        var sung = 0
        for ((text, startMs) in raw) {
            val clean = text.trim()
            if (!isSung(clean)) continue
            val source = sung++
            if (!seen.add(normalize(clean))) continue
            lines += Line(number = lines.size + 1, text = clean, startMs = startMs, source = source)
            if (lines.size == MAX_LINES) break
        }
        if (lines.size < MIN_LINES) return null
        return Prepared(lines, PROMPT_VERSION + fingerprint(lines))
    }

    /** The system half of the prompt: the rules. Kept short; Nano follows short rules best. */
    const val SYSTEM_INSTRUCTION: String =
        "You pick the most memorable passage in a song's lyrics: two to four consecutive lines that read " +
            "as one complete thought, the sentence or image a listener would quote or remember the song by. " +
            "The lines must belong together: never join the end of one thought to the start of another. " +
            "Prefer vivid images and emotional turns over titles repeated, filler (\"oh\", \"yeah\", " +
            "\"la la\"), or plain narration. " +
            "Answer with JSON only, in the form {\"start\": N, \"end\": M}, where N and M are line numbers " +
            "and M is from N+1 to N+3. No other text."

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
        // A long range keeps its opening lines (the loop below stops at four); a backwards one is nonsense.
        if (asked < start) return null
        val first = prepared.line(start) ?: return null
        // Lines that follow each other in the song only: the numbered list leaves repeats out, so the next
        // number can be a different part of the song, which would read as two thoughts glued together.
        val picked = mutableListOf(first)
        fun echoes(line: Line): Boolean {
            // An echo of a line already picked ("盛开着花" after "我在升华 盛开着花") adds nothing to the passage.
            val words = normalize(line.text)
            return words.isEmpty() || picked.any { normalize(it.text).contains(words) }
        }
        var lastSource = first.source
        var number = start + 1
        // The asked range, and past it while the passage is still one line (two to four, owner 2026-11-08).
        while (picked.size < MAX_PICK_LINES && (number <= asked || picked.size < MIN_PICK_LINES)) {
            val line = prepared.line(number++) ?: break
            if (line.source != lastSource + 1) break
            lastSource = line.source
            if (!echoes(line)) picked += line
        }
        // Still one line (the song's last, or only echoes after it): take the line before it instead.
        if (picked.size < MIN_PICK_LINES) {
            prepared.line(start - 1)
                ?.takeIf { it.source == first.source - 1 && !echoes(it) }
                ?.let { picked.add(0, it) }
        }
        // Too long for a card: drop lines from the end, but never below one.
        while (picked.size > 1 && picked.sumOf { it.text.length } > MAX_PICK_CHARS) picked.removeAt(picked.lastIndex)
        if (picked.size == 1 && picked.first().text.length > MAX_PICK_CHARS) return null
        return Pick(picked.first(), picked.last(), picked.joinToString("\n") { it.text })
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
