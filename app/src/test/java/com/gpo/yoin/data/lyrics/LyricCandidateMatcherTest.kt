package com.gpo.yoin.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Candidate lists below are taken from live QQ searches (2026-10-05) for the
 * title / artist strings Apple Music's Japanese storefront actually reports. The
 * Hotel California / Yesterday lists (with album names) are live NetEase
 * `cloudsearch` answers from the same day.
 */
class LyricCandidateMatcherTest {

    private val matcher = LyricCandidateMatcher(fold = { it })

    @Test
    fun should_matchSimplifiedCandidate_when_requestIsTraditionalChinese() {
        val folding = LyricCandidateMatcher(fold = ::fakeTraditionalToSimplified)
        val candidates = listOf(
            c("雨爱", "杨丞琳"),
            c("雨爱 (DJ 阿若版)", "杨丞琳"),
            c("雨爱", "张德伊玲"),
        )

        assertEquals(0, folding.pick(candidates, title = "雨愛", artist = "楊丞琳"))
    }

    @Test
    fun should_preferPlainTitle_when_decoratedVersionComesFirst() {
        val candidates = listOf(
            c("晴天 (Live)", "周杰伦"),
            c("晴天", "周杰伦"),
        )

        assertEquals(1, matcher.pick(candidates, title = "晴天", artist = "周杰伦"))
    }

    @Test
    fun should_acceptDecoratedTitle_when_noPlainVersionExists() {
        val candidates = listOf(c("Detour (Explicit)", "Kim Petras"))

        assertEquals(0, matcher.pick(candidates, title = "Detour", artist = "Kim Petras"))
    }

    @Test
    fun should_matchArtist_when_latinWordsOverlapAcrossScripts() {
        val candidates = listOf(c("party 4 u", "Charli xcx"))

        assertEquals(0, matcher.pick(candidates, title = "party 4 u", artist = "チャーリーxcx"))
    }

    @Test
    fun should_matchArtist_when_requestNamesSeveralArtists() {
        val candidates = listOf(
            c("In My Bag", "Someone Else"),
            c("In My Bag (Explicit)", "FLO", "GloRilla"),
        )

        assertEquals(1, matcher.pick(candidates, title = "In My Bag", artist = "FLO & GloRilla"))
    }

    @Test
    fun should_rejectAll_when_comparableArtistDiffers() {
        val candidates = listOf(c("Stupid Love", "Jason Derulo"), c("Stupid Love", "Dan + Shay"))

        assertNull(matcher.pick(candidates, title = "Stupid Love", artist = "Lady Gaga"))
    }

    @Test
    fun should_skipWrongArtistAtTop_when_rightArtistIsFurtherDown() {
        val candidates = listOf(
            c("Stupid Love", "Jason Derulo"),
            c("stupid love", "米卡"),
            c("Stupid Love", "Lady Gaga"),
        )

        assertEquals(2, matcher.pick(candidates, title = "Stupid Love", artist = "Lady Gaga"))
    }

    @Test
    fun should_acceptUnverifiableArtist_when_everyTitleMatchIsBySameArtist() {
        val candidates = listOf(
            c("my tears ricochet", "Taylor Swift"),
            c("my tears ricochet (the long pond studio sessions)", "Taylor Swift"),
            c("cardigan", "Taylor Swift"),
        )

        assertEquals(0, matcher.pick(candidates, title = "my tears ricochet", artist = "テイラー・スウィフト"))
    }

    @Test
    fun should_rejectUnverifiableArtist_when_titleMatchesSeveralArtists() {
        val candidates = listOf(
            c("Stupid Love", "Jason Derulo"),
            c("stupid love", "米卡"),
            c("Stupid Love", "Lady Gaga"),
        )

        assertNull(matcher.pick(candidates, title = "Stupid Love", artist = "レディー・ガガ"))
    }

    @Test
    fun should_acceptDominantPerformer_when_unverifiableArtistHasMostSameTitledCuts() {
        val folding = LyricCandidateMatcher(fold = ::fakeTraditionalToSimplified)
        val candidates = listOf(
            c("痛快的哀艳 (苏打绿版)", "苏打绿"),
            c("痛快的哀艳 (Live)", "苏诗丁"),
            c("痛快的哀艳 (Endless Story Live)", "苏打绿"),
            c("痛快的哀艳", "苏打绿"),
            c("痛快的哀艳 (二十年一刻Live)", "苏打绿"),
            c("四季未了 (Live)", "苏打绿"),
            c("痛快的哀艳 (Live)", "苏打绿"),
            c("痛快的哀艳 (Remix)", "金鸿煊"),
            c("痛快的哀艳", "泪零Namida"),
            c("痛快的哀艳", "比利凯"),
        )

        assertEquals(3, folding.pick(candidates, title = "痛快的哀艷", artist = "ソーダグリーン"))
    }

    @Test
    fun should_pickStudioCut_when_dominantPerformerAlsoHasTaylorsVersion() {
        val candidates = listOf(
            c("I Knew You Were Trouble", "Taylor Swift"),
            c("I Knew You Were Trouble (Taylor’s Version)", "Taylor Swift"),
            c("I Knew You Were Trouble", "Party Hit Kings"),
            c("I Knew You Were Trouble (Live from the BRITs 2013)", "Taylor Swift"),
            c("I Knew You Were Trouble (Fish Fugue Remix)", "Fish Fugue", "Taylor Swift"),
        )

        assertEquals(0, matcher.pick(candidates, title = "I Knew You Were Trouble", artist = "テイラー・スウィフト"))
    }

    @Test
    fun should_returnNull_when_topRankedPerformerHasOnlyOneCut() {
        val candidates = listOf(
            c("What Was That", "Lorde"),
            c("What Was That", "Chartwave Collective"),
            c("What Was That (Live)", "Chartwave Collective"),
        )

        assertNull(matcher.pick(candidates, title = "What Was That", artist = "ロード"))
    }

    @Test
    fun should_notTreatKanaPrefix_as_sameArtist() {
        // smartbox suggests "ロードオブメジャー" for "ロード" (Lorde); a prefix is not a match.
        val candidates = listOf(c("What Was That", "ロードオブメジャー"))

        assertNull(matcher.pick(candidates, title = "What Was That", artist = "ロード"))
    }

    @Test
    fun should_notMatchShortLatinFragment_insideLongerName() {
        val candidates = listOf(c("After LIKE", "Five Seconds"), c("After LIKE", "IVE"))

        assertEquals(1, matcher.pick(candidates, title = "After LIKE", artist = "IVE"))
    }

    @Test
    fun should_returnNull_when_noTitleMatches() {
        val candidates = listOf(c("Making A Life Outa Livin'(feat. Ms. Lex)", "Murdoq"))

        assertNull(matcher.pick(candidates, title = "Making Out", artist = "Lexie Liu"))
    }

    @Test
    fun should_ignoreCaseWidthAndPunctuation_when_comparingTitles() {
        val candidates = listOf(c("ＡＰＴ．", "ROSÉ"))

        assertEquals(0, matcher.pick(candidates, title = "APT.", artist = "ROSÉ & Bruno Mars"))
    }

    @Test
    fun should_skipInstrumentalCut_when_requestIsTheVocalVersion() {
        val candidates = listOf(
            c("晴天 (伴奏)", "周杰伦"),
            c("Instant Crush (Inst.)", "Daft Punk"),
            c("晴天 (Live)", "周杰伦"),
        )

        assertEquals(2, matcher.pick(candidates, title = "晴天", artist = "周杰伦"))
        assertNull(matcher.pick(candidates, title = "Instant Crush", artist = "Daft Punk"))
    }

    @Test
    fun should_acceptInstrumentalCut_when_requestIsInstrumentalToo() {
        val candidates = listOf(c("晴天 (伴奏)", "周杰伦"))

        assertEquals(0, matcher.pick(candidates, title = "晴天 (伴奏版)", artist = "周杰伦"))
    }

    // ── same-master notes (remaster, mono, album version, ...) ───────────────

    @Test
    fun should_rankRemasterWithPlainTitle_when_bothNameTheStudioCut() {
        val remasterFirst = listOf(c("Hotel California (2013 Remaster)", "Eagles"), c("Hotel California", "Eagles"))
        val plainFirst = listOf(c("Hotel California", "Eagles"), c("Hotel California (1999 Remaster)", "Eagles"))

        // Same rank, so the platform's order decides either way.
        assertEquals(0, matcher.pick(remasterFirst, title = "Hotel California", artist = "Eagles"))
        assertEquals(0, matcher.pick(plainFirst, title = "Hotel California", artist = "Eagles"))
    }

    @Test
    fun should_preferRemaster_when_liveCutRanksFirst() {
        val candidates = listOf(
            c("Hotel California (Live on MTV, 1994)", "Eagles"),
            c("Hotel California (2013 Remaster)", "Eagles"),
        )

        assertEquals(1, matcher.pick(candidates, title = "Hotel California", artist = "Eagles"))
    }

    @Test
    fun should_preferRemaster_when_plainTitleComesFromUnpluggedAlbum() {
        val candidates = listOf(
            c("Hotel California", "Eagles", album = "Unplugged 1994 - The Second Night"),
            c("Hotel California (2013 Remaster)", "Eagles", album = "The Studio Albums 1972-1979 (2013 Remaster)"),
        )

        assertEquals(1, matcher.pick(candidates, title = "Hotel California", artist = "Eagles"))
    }

    @Test
    fun should_pickStudioRemaster_when_netEaseListsLiveAndUnpluggedCuts() {
        val candidates = listOf(
            c("Hotel California (2013 Remaster)", "Eagles", album = "The Studio Albums 1972-1979 (2013 Remaster)"),
            c("Hotel California (Live on MTV, 1994)", "Eagles", album = "Hell Freezes Over"),
            c("Hotel California (1999 Remaster)", "Eagles", album = "Selected Works 1972-1999"),
            c("Hotel California", "Eagles", album = "Unplugged 1994 - The Second Night"),
            c("Hotel California (live)", "Eagles", album = "Unplugged 1994 (live)"),
            c("Hotel California", "Eagles", album = "The Summit, Houston, 1976 (Hd Remastered Edition)"),
        )

        assertEquals(0, matcher.pick(candidates, title = "Hotel California", artist = "Eagles"))
        // Without the first entry the next studio cut wins, never the unplugged one.
        assertEquals(1, matcher.pick(candidates.drop(1), title = "Hotel California", artist = "Eagles"))
    }

    @Test
    fun should_pickRemaster_when_netEaseListsAnInterviewAlbumEntry() {
        val candidates = listOf(
            c("Yesterday (Remastered)", "The Beatles", album = "Help! (Remastered)"),
            c("Yesterday", "The Beatles", "Marty Murray", album = "Famous Interviews - The Beatles"),
            c("Yesterday (live)", "The Beatles", album = "Live in Tokyo 1966"),
            c("Yesterday (Take 1 - Remastered)", "The Beatles", album = "Anthology Highlights"),
            c("Yesterday (2023 Mix)", "The Beatles", album = "The Beatles 1962 – 1966 (2023 Edition)"),
        )

        assertEquals(0, matcher.pick(candidates, title = "Yesterday", artist = "The Beatles"))
        // Interview entry first: still the studio remaster.
        assertEquals(1, matcher.pick(candidates.take(2).reversed(), title = "Yesterday", artist = "The Beatles"))
    }

    @Test
    fun should_rankInterviewAlbumBelowLiveCut_when_noStudioCutExists() {
        val candidates = listOf(
            c("Yesterday", "The Beatles", "Marty Murray", album = "Famous Interviews - The Beatles"),
            c("Yesterday (live)", "The Beatles", album = "Live in Tokyo 1966"),
        )

        assertEquals(1, matcher.pick(candidates, title = "Yesterday", artist = "The Beatles"))
        assertEquals(0, matcher.pick(candidates.take(1), title = "Yesterday", artist = "The Beatles"))
    }

    @Test
    fun should_treatNoteAsOriginal_when_itOnlyNamesTheSameMaster() {
        val sameMaster = listOf(
            "(Remastered)", "(Remaster)", "(2013 Remaster)", "(Remastered 2009)", "- Remastered 2009",
            "- 2011 Remastered Version", "(Digitally Remastered)", "(40th Anniversary Remaster)",
            "(Hd Remastered Edition)", "(Mono)", "(Stereo)", "(Mono Version)", "(2009 Stereo Mix)",
            "(Mono / Remastered 2009)", "(Album Version)", "(LP Version)", "(Original Version)",
            "(Original Mix)", "(Explicit)", "[Explicit]", "(2019 リマスター)", "(アルバム・バージョン)",
        )
        for (note in sameMaster) {
            val candidates = listOf(c("Placeholder Song (Live)", "Band"), c("Placeholder Song $note", "Band"))

            assertEquals(note, 1, matcher.pick(candidates, title = "Placeholder Song", artist = "Band"))
        }
    }

    @Test
    fun should_keepNoteDecorated_when_itNamesAnotherVersion() {
        val otherVersions = listOf(
            "(Live)", "(Unplugged)", "(Remix)", "(2023 Mix)", "(Single Version)", "(Single Edit)", "(Radio Edit)",
            "(Edit)", "(Clean)", "(Original)", "(Take 1 - Remastered)", "(Live / Remastered)",
            "(Taylor’s Version)", "(Acoustic)", "(Cover)", "(Sped Up)", "(Slowed)", "(Demo)", "(feat. Mono)",
        )
        for (note in otherVersions) {
            val candidates = listOf(c("Placeholder Song $note", "Band"), c("Placeholder Song", "Band"))

            assertEquals(note, 1, matcher.pick(candidates, title = "Placeholder Song", artist = "Band"))
        }
    }

    @Test
    fun should_keepFeatClauseDecorated_when_featuredNameLooksLikeAMasterWord() {
        val candidates = listOf(c("Placeholder Song feat. Stereo", "Band"), c("Placeholder Song", "Band"))

        assertEquals(1, matcher.pick(candidates, title = "Placeholder Song", artist = "Band"))
    }

    @Test
    fun should_matchPlainCandidate_when_requestTitleCarriesRemaster() {
        val candidates = listOf(c("Yesterday (Live)", "The Beatles"), c("Yesterday", "The Beatles"))

        assertEquals(1, matcher.pick(candidates, title = "Yesterday - Remastered 2009", artist = "The Beatles"))
        assertEquals(1, matcher.pick(candidates, title = "Yesterday (2015 Remaster)", artist = "The Beatles"))
    }

    // ── album names ──────────────────────────────────────────────────────────

    @Test
    fun should_preferStudioAlbum_when_plainTitleComesFromConcertAlbum() {
        val candidates = listOf(
            c("晴天", "周杰伦", album = "周杰伦 2004 无与伦比 演唱会 Live CD"),
            c("晴天", "周杰伦", album = "叶惠美"),
        )

        assertEquals(1, matcher.pick(candidates, title = "晴天", artist = "周杰伦"))
    }

    @Test
    fun should_notFlagStudioAlbum_when_liveIsJustAWordInItsName() {
        val throughThis = listOf(
            c("Placeholder Song", "Band", album = "Live Through This"),
            c("Placeholder Song (Remastered)", "Band", album = "Collection"),
        )
        val mysteryTour = listOf(
            c("Placeholder Song", "Band", album = "Magical Mystery Tour"),
            c("Placeholder Song (Remastered 2009)", "Band", album = "1967-1970"),
        )

        assertEquals(0, matcher.pick(throughThis, title = "Placeholder Song", artist = "Band"))
        assertEquals(0, matcher.pick(mysteryTour, title = "Placeholder Song", artist = "Band"))
    }

    @Test
    fun should_flagLiveAlbum_when_liveOrTourIsFollowedByAPlaceOrYear() {
        for (album in listOf("Live in Tokyo 1966", "After Hours (Live At SoFi Stadium)", "idina: live", "Tour 2019")) {
            val candidates = listOf(
                c("Placeholder Song", "Band", album = album),
                c("Placeholder Song", "Band", album = "Studio Album"),
            )

            assertEquals(album, 1, matcher.pick(candidates, title = "Placeholder Song", artist = "Band"))
        }
    }

    @Test
    fun should_rankInstrumentalAlbumBelowLiveCut_when_titleLooksPlain() {
        val candidates = listOf(
            c("Placeholder Song", "Band", album = "Placeholder Song (Instrumental)"),
            c("Placeholder Song (Live)", "Band"),
        )

        assertEquals(1, matcher.pick(candidates, title = "Placeholder Song", artist = "Band"))
    }

    @Test
    fun should_ignoreAlbum_when_requestItselfIsALiveCut() {
        val candidates = listOf(
            c("Hotel California (Live, 1994)", "Eagles", album = "Hell Freezes Over"),
            c("Hotel California (Live)", "Eagles", album = "Unplugged 1994 (live)"),
        )

        assertEquals(1, matcher.pick(candidates, title = "Hotel California (Live)", artist = "Eagles"))
    }

    private fun c(title: String, vararg artists: String, album: String? = null) =
        LyricCandidateMatcher.Candidate(title = title, artists = artists.toList(), album = album)

    private fun fakeTraditionalToSimplified(value: String): String = value
        .replace('愛', '爱')
        .replace('楊', '杨')
        .replace('艷', '艳')
}
