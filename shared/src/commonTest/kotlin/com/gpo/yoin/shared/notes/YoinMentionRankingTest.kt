package com.gpo.yoin.shared.notes

import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared @ popup ranking (Android's MentionRankingTest cases, run on the JVM and the iOS simulator). */
class YoinMentionRankingTest {

    private val pinyin = YoinMentionRomanizer { name ->
        mapOf("纽约" to "niu yue", "晴天" to "qing tian", "周杰伦" to "zhou jie lun", "你好 (Live)" to "ni hao (Live)")[name]
    }

    private fun row(title: String, artist: String? = null, source: YoinMentionSource = YoinMentionSource.Search) =
        YoinMentionRankRow(title, artist, source)

    private fun tier(query: String, title: String, artist: String? = null) =
        YoinMentionRanking.tier(query, title, artist, pinyin)

    private fun trackTitles(query: String, rows: List<YoinMentionRankRow>): List<String> =
        YoinMentionRanking.rank(query, rows, emptyList(), emptyList(), pinyin).tracks.map { rows[it].title }

    @Test
    fun should_putATitleStartingWithTheQuery_first_when_oneLetterIsTyped() {
        val rows = listOf(row("Animal"), row("Sunny"), row("New York"), row("Nights"))
        assertEquals(listOf("New York", "Nights", "Animal", "Sunny"), trackTitles("N", rows))
    }

    @Test
    fun should_rankPrefixThenInitialsThenSubstringThenArtist() {
        val rows = listOf(row("Holy York", artist = "Ny Band"), row("Pony"), row("Night Yard"), row("Nyx"))
        assertEquals(listOf("Nyx", "Night Yard", "Pony", "Holy York"), trackTitles("ny", rows))
        assertEquals(YoinMentionTier.Artist, tier("ny", "Holy York", "Ny Band"))
    }

    @Test
    fun should_matchChineseTitlesByPinyin_fullAndInitials() {
        assertEquals(YoinMentionTier.TitlePrefix, tier("niuyue", "纽约"))
        assertEquals(YoinMentionTier.TitlePrefix, tier("niu", "纽约"))
        assertEquals(YoinMentionTier.Initials, tier("ny", "纽约"))
        assertEquals(YoinMentionTier.TitleSubstring, tier("yue", "纽约"))
        assertEquals(YoinMentionTier.TitlePrefix, tier("纽", "纽约"))
        assertEquals(YoinMentionTier.Artist, tier("zjl", "晴天", "周杰伦"))
        assertEquals(YoinMentionTier.Initials, tier("nhl", "你好 (Live)"))
    }

    @Test
    fun should_readAComposingPinyinQuery_withTheKeyboardsSeparators() {
        val rows = listOf(row("晴天"), row("纽约"), row("Nuyorican Soul"))
        assertEquals("纽约", trackTitles("niu'yue", rows).first())
        assertEquals("纽约", trackTitles("niu yue", rows).first())
        assertEquals("晴天", trackTitles("qt", rows).first())
    }

    @Test
    fun should_foldCaseAccentsAndWidth() {
        assertEquals(YoinMentionTier.TitlePrefix, tier("bjo", "Björk Live"))
        assertEquals(YoinMentionTier.TitlePrefix, tier("ｎｅｗ", "New York"))
        assertEquals("new york", YoinMentionRanking.fold("Ｎｅｗ Yörk"))
    }

    @Test
    fun should_keepKnownRowsFirstWithinATier_andDropKnownRowsThatDontMatch() {
        val rows = listOf(
            row("Nights"),
            row("New York", source = YoinMentionSource.Recent),
            row("Nothing", source = YoinMentionSource.Current),
            row("Older", source = YoinMentionSource.Recent),
            row("Elsewhere"),
        )
        assertEquals(listOf("Nothing", "New York", "Nights", "Elsewhere"), trackTitles("n", rows))
    }

    @Test
    fun should_listTheBestMatchsKindFirst_andKeepEverything_when_nothingIsTyped() {
        val tracks = listOf(row("Hello Yorktown"))
        val albums = listOf(row("New York"))
        val ranked = YoinMentionRanking.rank("new", tracks, albums, emptyList(), pinyin)
        assertEquals(listOf(MentionKind.Album, MentionKind.Track, MentionKind.Artist), ranked.order)
        val blank = YoinMentionRanking.rank(" ", tracks, albums, emptyList(), pinyin)
        assertEquals(YoinMentionRanked(listOf(0), listOf(0), emptyList(), YoinMentionRanking.defaultOrder), blank)
    }
}
