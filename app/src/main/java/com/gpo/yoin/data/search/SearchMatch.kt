package com.gpo.yoin.data.search

import java.text.Normalizer
import java.util.Locale

/*
 * How a search query meets a piece of text (docs/search.md, "What matches"): case-, width- and
 * accent-insensitive, the same fold on both sides, and a strength saying how well it met. Local only:
 * the remote services rank their own rows; this ranks the sections around them and the ledger's hits.
 */

/** How well a query met one field, weakest first, so a larger ordinal is a better hit. */
enum class MatchStrength {
    None,

    /** Somewhere inside the text, mid-word (and every CJK hit that isn't at the start). */
    Contains,

    /** At the start of a word after the first: "dark" in "The Dark Side". */
    WordPrefix,

    /** At the very start: "ヨル" in "ヨルシカ". */
    Prefix,

    /** The whole text, folded. */
    Exact,
}

/**
 * What kind of field a hit was in. A title-like field outranks the rest whatever the strength
 * (docs/search.md: "a title hit outranks a body hit"); the weights live here and nowhere else.
 */
enum class MatchTier {
    /** What a result is only about: a song note's song name or artist (the note's own words rank above it). */
    Incidental,

    /** Writing: a note, a review, a diary entry. */
    Body,

    /** The thing's context: a song's artist or album, an album's artist, a playlist's owner. */
    Secondary,

    /** The thing's own name: a song or album title, an artist's name, a memory's title. */
    Primary,
}

/** One hit's rank. Compared tier first, then strength; [None] is below every real hit. */
data class SearchScore(val tier: MatchTier, val strength: MatchStrength) : Comparable<SearchScore> {
    val isHit: Boolean get() = strength != MatchStrength.None

    /** Exact or prefix on the thing's own name: what earns the best-match card. */
    val isStrongName: Boolean
        get() = tier == MatchTier.Primary && strength >= MatchStrength.Prefix

    override fun compareTo(other: SearchScore): Int =
        compareValuesBy(this, other, { it.isHit }, { it.tier }, { it.strength })

    companion object {
        val None = SearchScore(MatchTier.Incidental, MatchStrength.None)
    }
}

/**
 * [original] folded for matching, with each folded char's source index so a hit can be shown in the
 * original text. Folding: compatibility forms (full-width Ｙ, half-width ｶ) to their plain forms,
 * lowercase, Latin accents dropped (Björk = Bjork), runs of whitespace to one space, ends trimmed.
 * Kana keep their voicing marks: が is not か.
 */
class FoldedText private constructor(
    val original: String,
    val text: String,
    private val origins: IntArray,
) {
    /** The original chars behind folded [start] until [endExclusive]. */
    fun originalRange(start: Int, endExclusive: Int): IntRange {
        if (start >= endExclusive || origins.isEmpty()) return IntRange.EMPTY
        val from = origins[start]
        val last = origins[endExclusive - 1]
        val to = last + Character.charCount(original.codePointAt(last))
        return from until to
    }

    companion object {
        fun of(original: String): FoldedText {
            val out = StringBuilder(original.length)
            var origins = IntArray(original.length + 8)
            var count = 0
            var pendingSpace = false
            var i = 0
            while (i < original.length) {
                val cp = original.codePointAt(i)
                val width = Character.charCount(cp)
                for (ch in foldCodePoint(cp)) {
                    if (ch.isWhitespace()) {
                        pendingSpace = out.isNotEmpty()
                        continue
                    }
                    // One code point can fold to many chars (ﷺ), and a space may go first.
                    if (count + 2 > origins.size) origins = origins.copyOf(origins.size * 2)
                    if (pendingSpace) {
                        out.append(' ')
                        origins[count++] = i
                        pendingSpace = false
                    }
                    out.append(ch)
                    origins[count++] = i
                }
                i += width
            }
            return FoldedText(original, out.toString(), origins.copyOf(count))
        }

        // Decomposed, so a code point folds the same alone as inside a word: ｶﾞ and ガ both become カ + ゛.
        // Hangul syllables stay whole, or 가 would match inside 각.
        private fun foldCodePoint(cp: Int): String {
            val alone = String(Character.toChars(cp))
            if (cp in 0xAC00..0xD7A3) return alone
            val decomposed = Normalizer.normalize(alone, Normalizer.Form.NFKD).lowercase(Locale.ROOT)
            if (!decomposed.all(::isLatinLike)) return decomposed
            return decomposed.filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        }

        // Latin, Greek, Cyrillic and their combining marks: where dropping an accent is what people expect.
        // Kana voicing marks (U+3099, U+309A) are outside it, so が stays apart from か.
        private fun isLatinLike(ch: Char): Boolean = ch.code < 0x0530 || ch.code in 0x1E00..0x1FFF
    }
}

/** Where and how well a query met one field ([source], folded). */
data class FieldMatch(val strength: MatchStrength, val source: FoldedText?, val start: Int = -1, val length: Int = 0) {
    /** The hit's chars in the field's original text; empty without a hit. */
    val originalRange: IntRange
        get() = if (source == null || start < 0) IntRange.EMPTY else source.originalRange(start, start + length)

    companion object {
        val None = FieldMatch(MatchStrength.None, null)
    }
}

/** A query, folded once, matched against many fields. Blank queries match nothing. */
class SearchQuery(raw: String) {
    val folded: String = FoldedText.of(raw).text

    val isBlank: Boolean get() = folded.isEmpty()

    fun match(text: String?): FieldMatch {
        if (isBlank || text.isNullOrBlank()) return FieldMatch.None
        val field = FoldedText.of(text)
        val haystack = field.text
        var first = -1
        var from = 0
        while (true) {
            val at = haystack.indexOf(folded, from)
            if (at < 0) break
            from = at + 1
            // A hit that stops inside a decomposed char (か before the ゛ of が) is no hit.
            val end = at + folded.length
            if (end < haystack.length && Character.getType(haystack[end]) == Character.NON_SPACING_MARK.toInt()) continue
            when {
                at == 0 && end == haystack.length -> return FieldMatch(MatchStrength.Exact, field, 0, folded.length)
                at == 0 -> return FieldMatch(MatchStrength.Prefix, field, 0, folded.length)
                !haystack[at - 1].isLetterOrDigit() && haystack[at].isLetterOrDigit() ->
                    return FieldMatch(MatchStrength.WordPrefix, field, at, folded.length)
                first < 0 -> first = at
            }
        }
        return if (first >= 0) FieldMatch(MatchStrength.Contains, field, first, folded.length) else FieldMatch.None
    }

    fun score(text: String?, tier: MatchTier): SearchScore = SearchScore(tier, match(text).strength)
}

/** A piece of text to show for a hit: the text and, inside it, the chars to highlight. */
data class SearchFragment(val text: String, val highlight: IntRange)

/**
 * The part of [match]'s field worth showing: the hit with a little before it, up to [maxChars],
 * line breaks as spaces, an ellipsis where text was cut. The whole text when it fits.
 */
fun SearchQuery.fragment(match: FieldMatch, maxChars: Int = FRAGMENT_MAX_CHARS): SearchFragment? {
    val original = match.source?.original ?: return null
    val flat = original.map { if (it == '\n' || it == '\r' || it == '\t') ' ' else it }.joinToString("")
    val hit = match.originalRange
    if (flat.length <= maxChars || hit.isEmpty()) {
        val text = flat.take(maxChars).trimEnd()
        val cut = if (flat.length > maxChars) "$text…" else text
        return SearchFragment(cut, if (hit.last < text.length) hit else IntRange.EMPTY)
    }
    var start = (hit.first - FRAGMENT_LEAD_CHARS).coerceAtLeast(0)
    if (start > 0 && Character.isLowSurrogate(flat[start])) start--
    var end = (start + maxChars).coerceAtMost(flat.length)
    if (end < flat.length && Character.isHighSurrogate(flat[end - 1])) end--
    if (end - start < maxChars) start = (end - maxChars).coerceAtLeast(0)
    val lead = if (start > 0) "…" else ""
    val tail = if (end < flat.length) "…" else ""
    val body = flat.substring(start, end).trim()
    val trimmedLead = flat.substring(start, end).length - flat.substring(start, end).trimStart().length
    val offset = lead.length - start - trimmedLead
    val from = (hit.first + offset).coerceAtLeast(lead.length)
    val to = (hit.last + offset).coerceAtMost(lead.length + body.length - 1)
    return SearchFragment(lead + body + tail, if (from <= to) from..to else IntRange.EMPTY)
}

/** A fragment's longest text, ellipses aside: about two lines in a result row. */
const val FRAGMENT_MAX_CHARS = 80

/** How much text a fragment keeps before its hit. */
private const val FRAGMENT_LEAD_CHARS = 24
