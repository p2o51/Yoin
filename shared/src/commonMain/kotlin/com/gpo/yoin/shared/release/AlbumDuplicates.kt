package com.gpo.yoin.shared.release

/**
 * One copy of a release as a service lists it, for [AlbumDuplicates]. Every field but [name] may be unknown
 * (null): a field only tells two copies apart when both of them know it.
 *
 * - [releaseDate]: ISO "yyyy", "yyyy-mm" or "yyyy-mm-dd" as the service dates it (Spotify `release_date` at its
 *   `release_date_precision`).
 * - [trackCount]: the whole tracklist's length (Spotify `total_tracks`), not the tracks read so far.
 * - [durationSec]: the whole tracklist's running time, when it was read.
 * - [rating]: explicit or clean as the service marks the release; Spotify's simplified album objects (artist
 *   releases, search rows) say neither.
 */
data class AlbumCopy(
    val name: String,
    val artist: String?,
    val releaseDate: String?,
    val trackCount: Int?,
    val durationSec: Int?,
    val rating: ContentRating?,
    val kind: ReleaseType?,
)

/**
 * Whether two album ids are likely the same release (owner 2026-11-27: "some artists have many identical albums
 * with different AlbumIDs", Spotify). Spotify's catalogue holds several album objects for one release — a label's
 * re-delivery, another territory's copy, a clean copy beside the explicit one — and its artist releases and
 * search list every one; no field links them, so they are matched by what they show.
 *
 * Detection only: nothing merges or links albums on it. It is the rule a suggested manual link would use (the
 * design is a separate proposal).
 *
 * Two copies are likely the same release when all of these hold:
 * - the same name after [normalizedName] (case, full-width forms, curly quotes, dashes and runs of spaces don't
 *   count; brackets and their words do, so "Abbey Road (Remastered 2009)", "Donda (Deluxe)" and
 *   "Fearless (Taylor's Version)" stay their own releases). A blank name (Spotify's after a takedown) never
 *   matches;
 * - the same release date down to the precision both give ("2004" matches "2004-02-10"; "2004-02-10" doesn't
 *   match "2004-02-09"); a date on one copy only doesn't match;
 * - the same artist, kind (album / EP / single / compilation) and track count, when both know it;
 * - the same running time within [DURATION_SLACK_PER_TRACK_SEC] a track (at least [DURATION_SLACK_MIN_SEC]),
 *   when both know it;
 * - the same rating when both know it: an explicit and a clean copy are two editions, each with its own "E"
 *   (or none).
 */
object AlbumDuplicates {

    const val DURATION_SLACK_PER_TRACK_SEC = 2
    const val DURATION_SLACK_MIN_SEC = 2

    fun likelySameRelease(a: AlbumCopy, b: AlbumCopy): Boolean {
        val name = normalizedName(a.name)
        if (name.isEmpty() || name != normalizedName(b.name)) return false
        val aArtist = a.artist?.let(::normalizedName)?.takeIf(String::isNotEmpty)
        val bArtist = b.artist?.let(::normalizedName)?.takeIf(String::isNotEmpty)
        if (aArtist != null && bArtist != null && aArtist != bArtist) return false
        if (!sameDate(a.releaseDate, b.releaseDate)) return false
        if (a.kind != null && b.kind != null && a.kind != b.kind) return false
        if (a.trackCount != null && b.trackCount != null && a.trackCount != b.trackCount) return false
        if (a.rating != null && b.rating != null && a.rating != b.rating) return false
        val aLength = a.durationSec
        val bLength = b.durationSec
        if (aLength != null && bLength != null && aLength > 0 && bLength > 0) {
            val tracks = a.trackCount ?: b.trackCount ?: 1
            val slack = maxOf(DURATION_SLACK_MIN_SEC, DURATION_SLACK_PER_TRACK_SEC * tracks)
            if (kotlin.math.abs(aLength - bLength) > slack) return false
        }
        return true
    }

    /**
     * [name] as two copies of one release are compared: lower case, full-width ASCII and the ideographic and
     * no-break spaces as their plain forms, curly quotes and primes as straight ones, en / em dashes and minus
     * signs as "-", every run of white space as one space, trimmed. Nothing is removed.
     */
    fun normalizedName(name: String): String {
        val out = StringBuilder(name.length)
        var space = false
        for (raw in name) {
            val c = when (raw) {
                in '！'..'～' -> raw - 0xFEE0
                '　', ' ' -> ' '
                '‘', '’', '‛', '′', '´', '`' -> '\''
                '“', '”', '‟', '″' -> '"'
                '‐', '‑', '‒', '–', '—', '―', '−' -> '-'
                else -> raw
            }
            if (c.isWhitespace()) {
                space = out.isNotEmpty()
                continue
            }
            if (space) out.append(' ')
            space = false
            out.append(c.lowercaseChar())
        }
        return out.toString()
    }

    /** Dates down to the precision both give; neither dated matches, one dated does not. */
    private fun sameDate(a: String?, b: String?): Boolean {
        val x = dateParts(a)
        val y = dateParts(b)
        if (x == null || y == null) return x == null && y == null
        return (0 until minOf(x.size, y.size)).all { x[it] == y[it] }
    }

    private fun dateParts(date: String?): List<Int>? {
        val parts = date?.trim()?.takeIf(String::isNotEmpty)?.split('-') ?: return null
        val numbers = parts.take(3).map { it.toIntOrNull() ?: return null }
        // Spotify's "0000" (no year) says nothing.
        if (numbers.first() <= 0) return null
        return numbers
    }
}
