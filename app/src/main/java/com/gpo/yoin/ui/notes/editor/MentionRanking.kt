package com.gpo.yoin.ui.notes.editor

import android.icu.text.Transliterator
import android.os.Build
import android.util.LruCache
import com.gpo.yoin.shared.notes.YoinMentionRanking
import com.gpo.yoin.shared.notes.YoinMentionRankRow
import com.gpo.yoin.shared.notes.YoinMentionRomanizer
import com.gpo.yoin.shared.notes.YoinMentionSource

/**
 * Latin letters for a name written in Chinese characters ("纽约" → "niu yue"), or null when there is nothing to
 * spell (no Han characters) or no way to spell it. The @ popup matches a typed "@niuyue" / "@ny" against them.
 */
fun interface MentionRomanizer : YoinMentionRomanizer {
    override fun romanize(text: String): String?

    companion object {
        val None = MentionRomanizer { null }
    }
}

/**
 * Pinyin from the platform's ICU (`Han-Latin; Latin-ASCII`, Android 10+), so Yoin carries no pinyin table of
 * its own: the system's covers every Han character and reads common words as words (多音字 in context).
 * Below Android 10 there is none and names match by their own letters only. Answers are cached by name.
 */
object IcuMentionRomanizer : MentionRomanizer {
    private val transliterator: Transliterator? by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@lazy null
        try {
            Transliterator.getInstance("Han-Latin; Latin-ASCII")
        } catch (_: RuntimeException) {
            null
        }
    }
    private val cache = LruCache<String, String>(CacheSize)

    override fun romanize(text: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (text.none(::isHan)) return null
        cache.get(text)?.let { return it }
        val latin = transliterator ?: return null
        val out = synchronized(latin) { latin.transliterate(text) }
        cache.put(text, out)
        return out
    }

    private const val CacheSize = 512
}

internal fun isHan(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

/**
 * How the @ popup orders its rows (owner 2026-11-24, "@N puts New York first"): `:shared`'s [YoinMentionRanking],
 * which iOS uses too, over this app's [MentionCandidate] rows. A title that starts with what is typed, then one
 * whose initials do, then one that holds it anywhere, then one whose artist matches; Chinese names also by pinyin.
 */
object MentionRanking {

    enum class Tier { TitlePrefix, Initials, TitleSubstring, Artist, None }

    /** Lower case, accents and full width folded ("Ｎｅｗ Yörk" → "new york"). */
    fun fold(text: String): String = YoinMentionRanking.fold(text)

    /** The typed query compared without the spaces and apostrophes a pinyin keyboard puts between syllables. */
    fun compact(text: String): String = YoinMentionRanking.compact(text)

    fun tier(query: String, row: MentionCandidate, romanizer: MentionRomanizer): Tier =
        Tier.valueOf(YoinMentionRanking.tier(query, row.mention.title, row.artist, romanizer).name)

    /**
     * [rows] ordered for [query]. A row the account already knew (the song playing, a recent play) that doesn't
     * match is dropped; a search result stays, last. The kinds are listed best match first, so the top row (what
     * Enter picks) is the best one whatever its kind.
     */
    fun rank(query: String, rows: MentionCandidates, romanizer: MentionRomanizer): MentionCandidates {
        if (compact(query).isEmpty()) return rows
        val ranked = YoinMentionRanking.rank(
            query,
            rows.tracks.map(::rankRow),
            rows.albums.map(::rankRow),
            rows.artists.map(::rankRow),
            romanizer,
        )
        return MentionCandidates(
            tracks = ranked.tracks.map(rows.tracks::get),
            albums = ranked.albums.map(rows.albums::get),
            artists = ranked.artists.map(rows.artists::get),
            order = ranked.order,
        )
    }

    private fun rankRow(row: MentionCandidate) =
        YoinMentionRankRow(row.mention.title, row.artist, YoinMentionSource.valueOf(row.source.name))
}
