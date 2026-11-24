package com.gpo.yoin.ui.notes.editor

import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.NoteMention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionRankingTest {

    // The device spells Chinese names with ICU; here a fixed table stands in for it.
    private val pinyin = MentionRomanizer { name ->
        mapOf(
            "纽约" to "niu yue",
            "晴天" to "qing tian",
            "周杰伦" to "zhou jie lun",
            "你好 (Live)" to "ni hao (Live)",
        )[name]
    }

    private fun track(
        title: String,
        artist: String? = null,
        source: MentionCandidate.Source = MentionCandidate.Source.Search,
        id: String = title,
    ) = MentionCandidate(
        mention = NoteMention(MentionKind.Track, "subsonic", id, title, artist),
        coverUrl = null,
        artist = artist,
        source = source,
    )

    private fun album(title: String, source: MentionCandidate.Source = MentionCandidate.Source.Search) = MentionCandidate(
        mention = NoteMention(MentionKind.Album, "subsonic", "al-$title", title, null),
        coverUrl = null,
        source = source,
    )

    private fun titles(rows: MentionCandidates) = rows.all.map { it.mention.title }

    @Test
    fun should_putATitleStartingWithTheQuery_first_when_oneLetterIsTyped() {
        val rows = MentionCandidates(tracks = listOf(track("Animal"), track("Sunny"), track("New York"), track("Nights")))

        val ranked = MentionRanking.rank("N", rows, pinyin)

        assertEquals(listOf("New York", "Nights", "Animal", "Sunny"), titles(ranked))
    }

    @Test
    fun should_rankPrefixThenInitialsThenSubstringThenArtist() {
        val rows = MentionCandidates(
            tracks = listOf(
                track("Holy York", artist = "Ny Band"),
                track("Pony"),
                track("Night Yard"),
                track("Nyx"),
            ),
        )

        val ranked = MentionRanking.rank("ny", rows, pinyin)

        // Nyx starts with it; "Night Yard" has its initials; "Pony" holds it; Holy York only by its artist.
        assertEquals(listOf("Nyx", "Night Yard", "Pony", "Holy York"), titles(ranked))
        assertEquals(MentionRanking.Tier.Artist, MentionRanking.tier("ny", rows.tracks[0], pinyin))
    }

    @Test
    fun should_matchChineseTitlesByPinyin_fullAndInitials() {
        val newYork = track("纽约")
        assertEquals(MentionRanking.Tier.TitlePrefix, MentionRanking.tier("niuyue", newYork, pinyin))
        assertEquals(MentionRanking.Tier.TitlePrefix, MentionRanking.tier("niu", newYork, pinyin))
        assertEquals(MentionRanking.Tier.Initials, MentionRanking.tier("ny", newYork, pinyin))
        assertEquals(MentionRanking.Tier.TitleSubstring, MentionRanking.tier("yue", newYork, pinyin))
        // committed characters still match as themselves
        assertEquals(MentionRanking.Tier.TitlePrefix, MentionRanking.tier("纽", newYork, pinyin))
    }

    @Test
    fun should_readAComposingPinyinQuery_withTheKeyboardsSeparators() {
        val rows = MentionCandidates(tracks = listOf(track("晴天"), track("纽约"), track("Nuyorican Soul")))

        // a pinyin keyboard composing "niu'yue" (or "niu yue") before it commits 纽约
        assertEquals("纽约", titles(MentionRanking.rank("niu'yue", rows, pinyin)).first())
        assertEquals("纽约", titles(MentionRanking.rank("niu yue", rows, pinyin)).first())
        assertEquals("晴天", titles(MentionRanking.rank("qt", rows, pinyin)).first())
    }

    @Test
    fun should_matchArtistsByPinyinToo() {
        val sunny = track("晴天", artist = "周杰伦")

        assertEquals(MentionRanking.Tier.Artist, MentionRanking.tier("zjl", sunny, pinyin))
        assertEquals(MentionRanking.Tier.Artist, MentionRanking.tier("zhoujie", sunny, pinyin))
    }

    @Test
    fun should_foldCaseAccentsAndWidth() {
        assertEquals(MentionRanking.Tier.TitlePrefix, MentionRanking.tier("bjo", track("Björk Live"), pinyin))
        assertEquals(MentionRanking.Tier.TitlePrefix, MentionRanking.tier("ｎｅｗ", track("New York"), pinyin))
    }

    @Test
    fun should_keepThePlayingSongAndRecentPlaysFirst_withinATier() {
        val rows = MentionCandidates(
            tracks = listOf(
                track("Nights", source = MentionCandidate.Source.Search),
                track("New York", source = MentionCandidate.Source.Recent),
                track("Nothing", source = MentionCandidate.Source.Current),
            ),
        )

        assertEquals(listOf("Nothing", "New York", "Nights"), titles(MentionRanking.rank("n", rows, pinyin)))
    }

    @Test
    fun should_dropKnownRowsThatDontMatch_butKeepTheSearchsOwnLast() {
        val rows = MentionCandidates(
            tracks = listOf(
                track("Elsewhere", source = MentionCandidate.Source.Search),
                track("Unrelated", source = MentionCandidate.Source.Recent),
                track("Older", source = MentionCandidate.Source.Seen),
                track("New York", source = MentionCandidate.Source.Search),
            ),
        )

        // the service found "Elsewhere" for its own reasons: it stays, after what matches here
        assertEquals(listOf("New York", "Elsewhere"), titles(MentionRanking.rank("new", rows, pinyin)))
    }

    @Test
    fun should_listTheBestMatchsKindFirst_soEnterPicksIt() {
        val rows = MentionCandidates(tracks = listOf(track("Hello Yorktown")), albums = listOf(album("New York")))

        val ranked = MentionRanking.rank("new", rows, pinyin)

        assertEquals(listOf(MentionKind.Album, MentionKind.Track, MentionKind.Artist), ranked.order)
        assertEquals("New York", ranked.all.first().mention.title)
    }

    @Test
    fun should_leaveTheRowsAsTheyAre_when_nothingIsTyped() {
        val rows = MentionCandidates(tracks = listOf(track("B", source = MentionCandidate.Source.Recent), track("A")))

        assertEquals(rows, MentionRanking.rank("", rows, pinyin))
        assertEquals(rows, MentionRanking.rank("  ", rows, pinyin))
    }

    @Test
    fun should_filterThePoolAtOnce_whileTheSearchIsOnItsWay() {
        val pool = MentionCandidates(
            tracks = listOf(
                track("Clair de Lune", source = MentionCandidate.Source.Recent),
                track("New York", source = MentionCandidate.Source.Search),
                track("纽约", source = MentionCandidate.Source.Current),
            ),
        )

        val now = mentionRowsWhileSearching("ny", pool, pinyin)

        assertEquals(listOf("纽约", "New York"), titles(now))
        // the search answers: its rows and the pool's matches, ranked together, each item once
        val found = MentionCandidates(tracks = listOf(track("Nylon"), track("New York")))
        val after = mentionRowsAfterSearch("ny", found, pool, pinyin)
        assertEquals(listOf("Nylon", "纽约", "New York"), titles(after))
        assertTrue(after.all.map { it.mention.ref }.let { it.size == it.toSet().size })
    }

    @Test
    fun should_spellAMixedTitle_wordByWord() {
        assertEquals(MentionRanking.Tier.Initials, MentionRanking.tier("nhl", track("你好 (Live)"), pinyin))
        assertEquals(MentionRanking.Tier.TitlePrefix, MentionRanking.tier("nihao", track("你好 (Live)"), pinyin))
    }
}
