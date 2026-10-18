package com.gpo.yoin.data.transfer

import com.gpo.yoin.data.model.Track
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Decides which of another service's tracks is a playlist's track
 * (docs/playlist-transfer.md §4). Pure: no service calls, so the rules are
 * unit-tested on their own.
 *
 * A candidate is scored on title, artist and duration. Title and artist are
 * compared after [normalizeTitle] / [normalizeArtist] (case, width, accents,
 * "feat." credits and "Remastered" tags fall away; CJK stays). Duration
 * within [DURATION_TOLERANCE_SEC] counts in full; beyond
 * [DURATION_REJECT_SEC] the candidate is a different recording.
 */
object TrackMatcher {
    const val DURATION_TOLERANCE_SEC = 3
    const val DURATION_REJECT_SEC = 10

    /** A score at or above this, inside the duration tolerance, is a match. */
    const val MATCH_SCORE = 0.85

    /** Below [MATCH_SCORE] but at or above this: shown as unsure, not added. */
    const val AMBIGUOUS_SCORE = 0.65

    private const val TITLE_WEIGHT = 0.5
    private const val ARTIST_WEIGHT = 0.35
    private const val DURATION_WEIGHT = 0.15
    private const val TITLE_FLOOR = 0.6
    private const val ARTIST_FLOOR = 0.5

    /** [source]'s best counterpart among [candidates] from a title search. */
    fun choose(source: Track, candidates: List<Track>): MatchDecision {
        val scored = candidates
            .map { it to score(source, it) }
            .filter { (_, score) -> score > 0.0 }
            .sortedWith(
                compareByDescending<Pair<Track, Double>> { it.second }
                    .thenBy { durationGap(source, it.first) ?: Int.MAX_VALUE }
            )
        val best = scored.firstOrNull() ?: return MatchDecision.NotFound
        val withinDuration = durationGap(source, best.first)?.let { it <= DURATION_TOLERANCE_SEC } ?: true
        return when {
            best.second >= MATCH_SCORE && withinDuration -> MatchDecision.Matched(best.first, MatchMethod.Search)
            best.second >= AMBIGUOUS_SCORE ->
                MatchDecision.Ambiguous(scored.takeWhile { it.second >= AMBIGUOUS_SCORE }.map { it.first }.take(3))
            else -> MatchDecision.NotFound
        }
    }

    /**
     * [source]'s counterpart among tracks that share its ISRC: the same
     * recording, so only a duration far off (a different edit filed under one
     * code) rules one out. Ties go to the closest title and album.
     */
    fun chooseByIsrc(source: Track, candidates: List<Track>): MatchDecision {
        val usable = candidates.filter { candidate ->
            durationGap(source, candidate)?.let { it <= DURATION_REJECT_SEC } ?: true
        }
        val best = usable.maxWithOrNull(
            compareBy<Track> { titleSimilarity(source.title, it.title) }
                .thenBy { if (sameAlbum(source, it)) 1 else 0 }
                .thenBy { -(durationGap(source, it) ?: 0) }
        ) ?: return MatchDecision.NotFound
        return MatchDecision.Matched(best, MatchMethod.Isrc)
    }

    /** 0 when a floor rules [candidate] out, else the weighted similarity in (0, 1]. */
    fun score(source: Track, candidate: Track): Double {
        val title = titleSimilarity(source.title, candidate.title)
        if (title < TITLE_FLOOR) return 0.0
        val artist = artistSimilarity(source.artist, candidate.artist)
        if (artist < ARTIST_FLOOR) return 0.0
        val gap = durationGap(source, candidate)
        val duration = when {
            gap == null -> 0.8
            gap <= DURATION_TOLERANCE_SEC -> 1.0
            gap <= DURATION_REJECT_SEC -> 0.4
            else -> return 0.0
        }
        return TITLE_WEIGHT * title + ARTIST_WEIGHT * artist + DURATION_WEIGHT * duration
    }

    /** What a title search on the target asks for: the bare title and the first artist. */
    fun searchQuery(track: Track): String? {
        val title = stripDecorations(track.title.orEmpty()).trim()
        if (title.isEmpty()) return null
        val artist = splitArtists(track.artist.orEmpty()).firstOrNull().orEmpty()
        return if (artist.isEmpty()) title else "$title $artist"
    }

    /** The bare title alone, the search after [searchQuery] found nothing. */
    fun titleQuery(track: Track): String? = stripDecorations(track.title.orEmpty()).trim().takeIf(String::isNotEmpty)

    fun titleSimilarity(a: String?, b: String?): Double {
        val left = normalizeTitle(a.orEmpty())
        val right = normalizeTitle(b.orEmpty())
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0
        return max(similarity(left, right), tokenOverlap(left, right) * 0.95)
    }

    /**
     * The best pairing of the two credits' artists: "A, B" against "A & B"
     * is 1, and so is "A feat. C" against "A" — the lead artist decides.
     */
    fun artistSimilarity(a: String?, b: String?): Double {
        val left = splitArtists(a.orEmpty()).map(::normalizeArtist).filter(String::isNotEmpty)
        val right = splitArtists(b.orEmpty()).map(::normalizeArtist).filter(String::isNotEmpty)
        if (left.isEmpty() || right.isEmpty()) return if (left.isEmpty() && right.isEmpty()) 0.6 else 0.5
        if (left.any { it in right }) return 1.0
        val whole = similarity(left.joinToString(" "), right.joinToString(" "))
        val pairs = left.maxOf { l -> right.maxOf { r -> similarity(l, r) } }
        return max(whole, pairs)
    }

    fun normalizeTitle(title: String): String = normalize(stripDecorations(title))

    fun normalizeArtist(artist: String): String = normalize(artist).removePrefix("the ").trim()

    /** "Song (feat. X) - Remastered 2011" → "Song": credits and edition tags, not words that change the recording. */
    internal fun stripDecorations(title: String): String {
        var result = title
        result = BRACKETED.replace(result) { match ->
            if (DECORATION.containsMatchIn(match.value)) "" else match.value
        }
        val dash = DASH_SUFFIX.find(result)
        if (dash != null && DECORATION.containsMatchIn(dash.groupValues[1])) {
            result = result.substring(0, dash.range.first)
        }
        result = FEAT_TAIL.replace(result, "")
        return result.trim()
    }

    internal fun splitArtists(artist: String): List<String> =
        artist.split(ARTIST_SEPARATOR).map(String::trim).filter(String::isNotEmpty)

    private fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
            .lowercase()
            .replace('&', ' ')
        val builder = StringBuilder(folded.length)
        for (char in folded) {
            builder.append(if (char.isLetterOrDigit()) char else ' ')
        }
        return builder.toString().split(' ').filter(String::isNotEmpty).joinToString(" ")
    }

    private fun tokenOverlap(a: String, b: String): Double {
        val left = a.split(' ').toSet()
        val right = b.split(' ').toSet()
        val shared = left.intersect(right).size
        return shared.toDouble() / max(left.size, right.size)
    }

    /** 1 − Levenshtein distance / longer length. */
    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val longer = max(a.length, b.length)
        if (longer == 0) return 1.0
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return 1.0 - previous[b.length].toDouble() / longer
    }

    private fun durationGap(a: Track, b: Track): Int? {
        val left = a.durationSec?.takeIf { it > 0 } ?: return null
        val right = b.durationSec?.takeIf { it > 0 } ?: return null
        return abs(left - right)
    }

    private fun sameAlbum(a: Track, b: Track): Boolean {
        val left = normalizeTitle(a.album.orEmpty())
        return left.isNotEmpty() && left == normalizeTitle(b.album.orEmpty())
    }

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val BRACKETED = Regex("[(\\[（【][^)\\]）】]*[)\\]）】]")
    private val DASH_SUFFIX = Regex("\\s[-–—]\\s(.+)$")
    private val DECORATION = Regex(
        "(?i)\\b(feat\\.?|ft\\.?|featuring|with|remaster(ed)?|\\d{4}\\s+remaster|deluxe|bonus track|explicit|clean|" +
            "single version|album version|original mix|from\\s.+)"
    )
    private val FEAT_TAIL = Regex("(?i)\\s(feat\\.?|ft\\.?|featuring)\\s.*$")
    private val ARTIST_SEPARATOR =
        Regex("(?i)\\s*(,|&|、|/|;|\\sx\\s|\\sand\\s|\\sfeat\\.?\\s|\\sft\\.?\\s|\\sfeaturing\\s|\\swith\\s)\\s*")
}

/** How a track found its counterpart. */
enum class MatchMethod { Isrc, Search }

sealed interface MatchDecision {
    data class Matched(val track: Track, val method: MatchMethod) : MatchDecision

    /** Close, but not close enough to add without asking: the best few, best first. */
    data class Ambiguous(val candidates: List<Track>) : MatchDecision
    data object NotFound : MatchDecision
}
