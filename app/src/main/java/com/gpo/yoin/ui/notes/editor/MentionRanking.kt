package com.gpo.yoin.ui.notes.editor

import android.icu.text.Transliterator
import android.os.Build
import android.util.LruCache
import com.gpo.yoin.shared.notes.MentionKind
import java.text.Normalizer

/**
 * Latin letters for a name written in Chinese characters ("纽约" → "niu yue"), or null when there is nothing to
 * spell (no Han characters) or no way to spell it. The @ popup matches a typed "@niuyue" / "@ny" against them.
 */
fun interface MentionRomanizer {
    fun romanize(text: String): String?

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
 * How the @ popup orders its rows for what is typed after the `@` (owner 2026-11-24, "@N puts New York first"):
 * a title that starts with it, then one whose initials do ("ny", or pinyin initials for Chinese names), then one
 * that holds it anywhere, then one whose artist matches. A Chinese name also matches by pinyin — full ("niuyue")
 * and initials ("ny") — so a pinyin keyboard still composing its letters finds 纽约 before anything is
 * committed. Within one tier the song playing and recent plays stay first, as before, then the service's order.
 */
object MentionRanking {

    enum class Tier { TitlePrefix, Initials, TitleSubstring, Artist, None }

    /** Lower case, accents and full width folded ("Ｎｅｗ Yörk" → "new york"). */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(CombiningMarks, "")
            .lowercase()

    /** The typed query compared without the spaces and apostrophes a pinyin keyboard puts between syllables. */
    fun compact(text: String): String = fold(text).filterNot { it.isWhitespace() || it in Separators }

    /** A name as the words it is matched by: its own (folded) and, for Chinese, its pinyin syllables. */
    internal data class Key(val folded: String, val compact: String, val spelled: String, val initials: String)

    internal fun key(name: String, romanizer: MentionRomanizer): Key {
        val folded = fold(name)
        val words = fold(romanizer.romanize(name) ?: name)
            .split(WordBreak)
            .filter(String::isNotEmpty)
        return Key(
            folded = folded,
            compact = folded.filterNot { it.isWhitespace() || it in Separators },
            spelled = words.joinToString(""),
            initials = words.joinToString("") { it.take(1) },
        )
    }

    fun tier(query: String, row: MentionCandidate, romanizer: MentionRomanizer): Tier {
        val folded = fold(query).trim()
        val compact = compact(query)
        if (compact.isEmpty()) return Tier.None
        val title = key(row.mention.title, romanizer)
        return when {
            title.folded.startsWith(folded) || title.compact.startsWith(compact) || title.spelled.startsWith(compact) ->
                Tier.TitlePrefix
            title.initials.startsWith(compact) -> Tier.Initials
            title.folded.contains(folded) || title.compact.contains(compact) || title.spelled.contains(compact) ->
                Tier.TitleSubstring
            row.artist?.let { matches(compact, folded, key(it, romanizer)) } == true -> Tier.Artist
            else -> Tier.None
        }
    }

    private fun matches(compact: String, folded: String, key: Key): Boolean =
        key.folded.contains(folded) || key.compact.contains(compact) || key.spelled.contains(compact) ||
            key.initials.startsWith(compact)

    /**
     * [rows] ordered for [query]. A row the account already knew (the song playing, a recent play) that doesn't
     * match is dropped; a search result stays, last (the service matched it on something it knows). The kinds
     * are listed best match first, so the top row (what Enter picks) is the best one whatever its kind.
     */
    fun rank(query: String, rows: MentionCandidates, romanizer: MentionRomanizer): MentionCandidates {
        if (compact(query).isEmpty()) return rows
        data class Scored(val row: MentionCandidate, val tier: Tier, val index: Int)
        fun order(list: List<MentionCandidate>): List<Scored> = list
            .mapIndexed { index, row -> Scored(row, tier(query, row, romanizer), index) }
            .filter { it.tier != Tier.None || it.row.source == MentionCandidate.Source.Search }
            .sortedWith(compareBy<Scored>({ it.tier }, { it.row.source }, { it.index }))
        val tracks = order(rows.tracks)
        val albums = order(rows.albums)
        val artists = order(rows.artists)
        val best = listOf(MentionKind.Track to tracks, MentionKind.Album to albums, MentionKind.Artist to artists)
            .filter { it.second.isNotEmpty() }
            .sortedWith(compareBy({ it.second.first().tier }, { it.second.first().row.source }))
            .map { it.first }
        val kinds = best + MentionCandidates.DefaultOrder.filterNot { it in best }
        return MentionCandidates(
            tracks = tracks.map { it.row },
            albums = albums.map { it.row },
            artists = artists.map { it.row },
            order = kinds,
        )
    }

    private val CombiningMarks = Regex("\\p{M}+")
    private val WordBreak = Regex("[^\\p{L}\\p{N}]+")
    private const val Separators = "'’·._-"
}
