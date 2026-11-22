package com.gpo.yoin.data.highlight

import com.gpo.yoin.data.model.Lyrics

/**
 * The lyric-highlight question put to Gemini Nano, and the check on its answer.
 *
 * Nano only answers a multiple-choice question: the lyrics go out numbered (with blank lines marking stanza
 * breaks and skipped repeats) and the model names a line range (`{"start": 7, "end": 8}`). What Yoin shows is
 * always the original lines, so a model that drifts or translates can never put words in the song's mouth; an
 * answer that isn't a valid, complete passage is dropped rather than truncated mid-thought.
 *
 * Pure Kotlin (no android.*), so the whole contract is unit-tested.
 */
object LyricHighlightPrompt {

    /**
     * One numbered line as sent to the model; [startMs] is null for unsynced lyrics. [source] is its place in
     * the song itself: repeats are left out, so two neighbouring numbers can be far apart in the song. [stanza]
     * groups lines that belong to the same musical section (broken by blank/non-sung lines or long interludes).
     */
    data class Line(
        val number: Int,
        val text: String,
        val startMs: Long?,
        val source: Int = number - 1,
        val stanza: Int = 0,
    )

    /** The lyrics as the model sees them, plus a fingerprint so a changed lyric is picked again. */
    data class Prepared(val lines: List<Line>, val fingerprint: String) {
        fun line(number: Int): Line? = lines.getOrNull(number - 1)?.takeIf { it.number == number }
    }

    /** A validated pick: 1–[MAX_PICK_LINES] consecutive lines from one stanza. */
    data class Pick(val first: Line, val last: Line, val text: String) {
        val startMs: Long? get() = first.startMs
    }

    /** Songs with fewer distinct sung lines than this (mostly instrumentals and snippets) are skipped. */
    const val MIN_LINES = 4

    /** Lines sent at most. Long songs are mostly repeats; 90 lines stay well under Nano's 4,000-token window. */
    const val MAX_LINES = 90

    /** Owner 2026-11-22: any complete passage under 4 lines (1–3 lines) may be shown. */
    const val MAX_PICK_LINES = 3

    /** A passage longer than this (all its lines together) won't fit a card; rejected rather than chopped. */
    const val MAX_PICK_CHARS = 140

    /** A standalone one-line pick needs enough substance (not a bare "yeah" or single word). */
    const val MIN_SINGLE_LINE_CHARS = 6

    /** A pause of at least this many milliseconds between synced lines marks a new stanza / musical break. */
    const val STANZA_GAP_MS = 9_000L

    /**
     * Part of every fingerprint: a song picked under an older question is picked again (v2: two to four
     * lines, owner 2026-11-08; v3: echoes left out; v4: never one line when the song has a neighbour;
     * v5: stanza boundaries preserved, <4 lines allowed, no silent tail-chopping or cross-stanza gluing).
     */
    const val PROMPT_VERSION = "v5:"

    /** Output budget: the JSON answer is ~15 tokens. */
    const val MAX_OUTPUT_TOKENS = 24

    /**
     * Numbered, de-duplicated lines with stanza indices preserved. Repeats of a line already sent (choruses)
     * are left out, so the model judges each line once and the first occurrence is what gets picked.
     * Blank lines, stage directions, and long synced pauses advance [Line.stanza] so lines from different
     * sections are never stitched together.
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
        var stanza = 0
        var stanzaHasSung = false
        var prevSungStartMs: Long? = null

        for ((text, startMs) in raw) {
            val clean = text.trim()
            if (!isSung(clean)) {
                if (stanzaHasSung) {
                    stanza++
                    stanzaHasSung = false
                }
                continue
            }
            if (stanzaHasSung && startMs != null && prevSungStartMs != null && startMs - prevSungStartMs >= STANZA_GAP_MS) {
                stanza++
            }
            prevSungStartMs = startMs
            stanzaHasSung = true
            val source = sung++
            if (!seen.add(normalize(clean))) continue
            lines += Line(
                number = lines.size + 1,
                text = clean,
                startMs = startMs,
                source = source,
                stanza = stanza,
            )
            if (lines.size == MAX_LINES) break
        }
        if (lines.size < MIN_LINES) return null
        return Prepared(lines, PROMPT_VERSION + fingerprint(lines))
    }

    /** The system half of the prompt: the rules. Kept short; Nano follows short rules best. */
    const val SYSTEM_INSTRUCTION: String =
        "You pick the most memorable passage in a song's lyrics: 1 to 3 consecutive lines (usually 2 or 3) " +
            "from the SAME stanza that connect as one complete thought and land on a natural ending. " +
            "Blank lines mark section breaks: NEVER cross a blank line or join the end of one section to the " +
            "start of the next. NEVER stop mid-sentence or cut off the second half of a couplet. " +
            "Prefer vivid images and emotional resonance over repeated titles, filler (\"oh\", \"yeah\", " +
            "\"la la\"), or plain narration. " +
            "Answer with JSON only, in the form {\"start\": N, \"end\": M}, where N and M are line numbers " +
            "in the same stanza and M is N, N+1, or N+2. No other text."

    /** The user half: the song and its numbered lines, with blank lines where stanzas or omitted repeats break. */
    fun userPrompt(title: String, artist: String, prepared: Prepared): String = buildString {
        append("Song: ").append(title.trim()).append(" — ").append(artist.trim()).append('\n')
        append("Lyrics:\n")
        var prev: Line? = null
        for (line in prepared.lines) {
            if (prev != null && (line.stanza != prev.stanza || line.source != prev.source + 1)) {
                append('\n')
            }
            append(line.number).append(". ").append(line.text).append('\n')
            prev = line
        }
    }

    private val START = Regex("\"?start\"?\\s*[:=]\\s*(\\d{1,3})", RegexOption.IGNORE_CASE)
    private val END = Regex("\"?end\"?\\s*[:=]\\s*(\\d{1,3})", RegexOption.IGNORE_CASE)
    private val BARE_RANGE = Regex("^\\s*(\\d{1,3})\\s*(?:[-–,]\\s*(\\d{1,3}))?\\s*$")

    private val TRAILING_INCOMPLETE_PUNCT = setOf(',', '，', '、', ':', '：', ';', '；', '-', '—', '–')

    private val TRAILING_EN_DANGLING = setOf(
        "and", "or", "but", "so", "because", "if", "when", "while", "though", "although",
        "that", "which", "with", "without", "to", "for", "of", "in", "on", "at", "from",
        "the", "a", "an", "my", "your", "our", "their", "his", "her",
    )

    private val TRAILING_ZH_DANGLING = listOf(
        "因为", "所以", "但是", "可是", "如果", "虽然", "而且", "或者", "还是", "并且", "于是", "然后", "就算", "哪怕",
    )

    /**
     * Reads the model's answer. Accepts the asked-for JSON and common near-misses (code fences, "7-8", single
     * line number). Enforces stanza continuity, `< 4` line ceiling (1–3 lines), no silent tail-chopping when
     * over budget, and rejects picks that end mid-clause.
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
        if (asked < start) return null
        // Reject oversized ranges instead of silently chopping the tail of a longer thought.
        if (asked - start + 1 > MAX_PICK_LINES) return null

        val first = prepared.line(start) ?: return null
        val picked = mutableListOf(first)
        fun echoes(line: Line): Boolean {
            val words = normalize(line.text)
            return words.isEmpty() || picked.any { normalize(it.text).contains(words) }
        }

        var lastSource = first.source
        var crossedBreak = false
        for (number in (start + 1)..asked) {
            val line = prepared.line(number) ?: break
            if (line.source != lastSource + 1 || line.stanza != first.stanza) {
                crossedBreak = true
                break
            }
            lastSource = line.source
            if (!echoes(line)) picked += line
        }

        // If the model asked for multiple lines across a break and only a single stub survived, drop it.
        if (crossedBreak && picked.size < 2) return null

        // Single-line picks are allowed (< 4 lines), as long as the line carries enough weight on its own.
        if (picked.size == 1 && normalize(picked.first().text).length < MIN_SINGLE_LINE_CHARS) return null

        // Never chop off the last line when too long — chopping leaves a hanging half-thought.
        if (picked.sumOf { it.text.length } > MAX_PICK_CHARS) return null

        // Drop picks that visibly trail off mid-clause.
        if (endsIncomplete(picked.last().text)) return null

        return Pick(picked.first(), picked.last(), picked.joinToString("\n") { it.text })
    }

    /** True when [line] visibly stops mid-clause (trailing comma/colon or dangling conjunction). */
    internal fun endsIncomplete(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.last() in TRAILING_INCOMPLETE_PUNCT) return true
        if (TRAILING_ZH_DANGLING.any { trimmed.endsWith(it) && trimmed.length > it.length }) return true
        val lastWord = trimmed
            .trimEnd('"', '\'', '”', '’', ')', '）', ']', '】')
            .substringAfterLast(' ')
            .lowercase()
        if (lastWord.isNotEmpty() && lastWord.all { it in 'a'..'z' } && lastWord in TRAILING_EN_DANGLING) {
            return true
        }
        return false
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

    /** FNV-1a over the sent lines and their stanza breaks: cheap, stable across runs, changes whenever lyrics do. */
    internal fun fingerprint(lines: List<Line>): String {
        var hash = -0x340d631b7bdddcdbL
        for (line in lines) {
            hash = hash xor line.stanza.toLong()
            hash *= 0x100000001b3L
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
