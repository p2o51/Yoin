package com.gpo.yoin.shared.notes

import com.gpo.yoin.shared.data.lyrics.normalizeNfd
import com.gpo.yoin.shared.data.lyrics.normalizeNfkc

/*
 * How the @ popup orders its rows (owner 2026-11-24, "@N puts New York first"), shared by Android
 * (ui/notes/editor/MentionRanking.kt wraps it) and iOS. Pure: names in, row order out. Swift-facing and flat
 * (iosApp/docs/shared-kmp.md): enums, data classes, one object, no defaults; rows go in as three lists and
 * come back as indexes into those lists, so each platform keeps its own row type.
 */

/**
 * Latin letters for a name written in Chinese characters ("纽约" → "niu yue"), or null when there is nothing
 * to spell (no Han characters) or no way to spell it. Android spells with ICU's Han-Latin, iOS with
 * CFStringTransform (kCFStringTransformMandarinLatin + StripCombiningMarks).
 */
fun interface YoinMentionRomanizer {
    fun romanize(text: String): String?
}

/** How well a row's names match what is typed after the `@`, best first. */
enum class YoinMentionTier { TitlePrefix, Initials, TitleSubstring, Artist, None }

/**
 * Where a row came from, in listing order within a tier: the song playing, recent plays, the service's search
 * for the words typed, then rows an earlier search offered.
 */
enum class YoinMentionSource { Current, Recent, Search, Seen }

/** One row's names as the ranking reads them. [artist]: the second line's artist (a song's, an album's). */
data class YoinMentionRankRow(
    val title: String,
    val artist: String?,
    val source: YoinMentionSource,
)

/**
 * The rows kept, in order, as indexes into the lists given to [YoinMentionRanking.rank]; [order] lists the
 * kinds best match first (the top row is what Enter picks).
 */
data class YoinMentionRanked(
    val tracks: List<Int>,
    val albums: List<Int>,
    val artists: List<Int>,
    val order: List<MentionKind>,
)

/**
 * A title that starts with the query, then one whose initials do ("ny", or pinyin initials for Chinese names),
 * then one that holds it anywhere, then one whose artist matches. A Chinese name also matches by pinyin — full
 * ("niuyue") and initials ("ny") — so a pinyin keyboard still composing its letters finds 纽约 before anything is
 * committed. Within one tier the song playing and recent plays stay first, then the service's order.
 */
object YoinMentionRanking {
    /** The kinds' order before anything is typed. */
    val defaultOrder: List<MentionKind> = listOf(MentionKind.Track, MentionKind.Album, MentionKind.Artist)

    /** Lower case, accents and full width folded ("Ｎｅｗ Yörk" → "new york"): NFKD, marks dropped. */
    fun fold(text: String): String {
        val decomposed = normalizeNfd(normalizeNfkc(text))
        return buildString(decomposed.length) {
            for (c in decomposed) if (!c.isMark()) append(c)
        }.lowercase()
    }

    /** The typed query compared without the spaces and apostrophes a pinyin keyboard puts between syllables. */
    fun compact(text: String): String = fold(text).filterNot { it.isWhitespace() || it in SEPARATORS }

    fun tier(query: String, title: String, artist: String?, romanizer: YoinMentionRomanizer): YoinMentionTier {
        val folded = fold(query).trim()
        val compact = compact(query)
        if (compact.isEmpty()) return YoinMentionTier.None
        val t = key(title, romanizer)
        return when {
            t.folded.startsWith(folded) || t.compact.startsWith(compact) || t.spelled.startsWith(compact) ->
                YoinMentionTier.TitlePrefix
            t.initials.startsWith(compact) -> YoinMentionTier.Initials
            t.folded.contains(folded) || t.compact.contains(compact) || t.spelled.contains(compact) ->
                YoinMentionTier.TitleSubstring
            artist != null && matches(compact, folded, key(artist, romanizer)) -> YoinMentionTier.Artist
            else -> YoinMentionTier.None
        }
    }

    /**
     * The rows ordered for [query]. A row the account already knew (the song playing, a recent play) that
     * doesn't match is dropped; a search result stays, last (the service matched it on something it knows).
     * The kinds are listed best match first. A blank query keeps every row as given, kinds in [defaultOrder].
     */
    fun rank(
        query: String,
        tracks: List<YoinMentionRankRow>,
        albums: List<YoinMentionRankRow>,
        artists: List<YoinMentionRankRow>,
        romanizer: YoinMentionRomanizer,
    ): YoinMentionRanked {
        if (compact(query).isEmpty()) {
            return YoinMentionRanked(tracks.indices.toList(), albums.indices.toList(), artists.indices.toList(), defaultOrder)
        }
        val t = order(query, tracks, romanizer)
        val al = order(query, albums, romanizer)
        val ar = order(query, artists, romanizer)
        val best = listOf(MentionKind.Track to t, MentionKind.Album to al, MentionKind.Artist to ar)
            .filter { it.second.isNotEmpty() }
            .sortedWith(compareBy({ it.second.first().tier }, { it.second.first().source }))
            .map { it.first }
        return YoinMentionRanked(
            tracks = t.map { it.index },
            albums = al.map { it.index },
            artists = ar.map { it.index },
            order = best + defaultOrder.filterNot { it in best },
        )
    }

    private class Scored(val index: Int, val tier: YoinMentionTier, val source: YoinMentionSource)

    private fun order(query: String, rows: List<YoinMentionRankRow>, romanizer: YoinMentionRomanizer): List<Scored> =
        rows.mapIndexed { index, row -> Scored(index, tier(query, row.title, row.artist, romanizer), row.source) }
            .filter { it.tier != YoinMentionTier.None || it.source == YoinMentionSource.Search }
            .sortedWith(compareBy<Scored>({ it.tier }, { it.source }, { it.index }))

    /** A name as the words it is matched by: its own (folded) and, for Chinese, its pinyin syllables. */
    private class Key(val folded: String, val compact: String, val spelled: String, val initials: String)

    private fun key(name: String, romanizer: YoinMentionRomanizer): Key {
        val folded = fold(name)
        val words = splitWords(fold(romanizer.romanize(name) ?: name))
        return Key(
            folded = folded,
            compact = folded.filterNot { it.isWhitespace() || it in SEPARATORS },
            spelled = words.joinToString(""),
            initials = words.joinToString("") { it.take(1) },
        )
    }

    private fun matches(compact: String, folded: String, key: Key): Boolean =
        key.folded.contains(folded) || key.compact.contains(compact) || key.spelled.contains(compact) ||
            key.initials.startsWith(compact)

    /** Runs of letters and numbers (Android's `[^\p{L}\p{N}]+` split). */
    private fun splitWords(text: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        for (c in text) {
            if (c.isLetterOrNumber()) {
                word.append(c)
            } else if (word.isNotEmpty()) {
                out += word.toString()
                word.clear()
            }
        }
        if (word.isNotEmpty()) out += word.toString()
        return out
    }

    private fun Char.isMark(): Boolean = when (category) {
        CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK -> true
        else -> false
    }

    private fun Char.isLetterOrNumber(): Boolean = isLetter() || when (category) {
        CharCategory.DECIMAL_DIGIT_NUMBER, CharCategory.LETTER_NUMBER, CharCategory.OTHER_NUMBER -> true
        else -> false
    }

    private const val SEPARATORS = "'’·._-"
}
