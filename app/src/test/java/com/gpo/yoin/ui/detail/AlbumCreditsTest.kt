package com.gpo.yoin.ui.detail

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.lightColorScheme
import com.gpo.yoin.data.album.AlbumAboutAsk
import com.gpo.yoin.data.album.AlbumAboutContent
import com.gpo.yoin.data.album.AlbumAiCredits
import com.gpo.yoin.data.album.AlbumScrapbookData
import com.gpo.yoin.data.album.AlbumScrapbookNote
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.ReleaseDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumCreditsTest {

    private val full = AlbumAiCredits(
        label = listOf("AI Label"),
        producers = listOf("Manfred Eicher"),
        mastering = listOf("Jan Erik Kongshaug"),
        mixing = listOf("Mixer"),
        recordedTime = "1975年1月",
        recordedStudios = listOf("Rainbow Studio, Oslo"),
        source = "《攻壳机动队》",
        firstReleased = "1975年",
    )

    private fun kinds(items: List<AlbumCreditItem>) = items.map { it.kind }

    private val zh = object : AlbumCreditLabels {
        override fun label(kind: AlbumCreditKind) = when (kind) {
            AlbumCreditKind.Label -> "厂牌"
            AlbumCreditKind.Producer -> "制作"
            AlbumCreditKind.Mastering -> "母带"
            AlbumCreditKind.Mixing -> "混音"
            AlbumCreditKind.Recorded -> "录制"
            AlbumCreditKind.Source -> "原作"
            AlbumCreditKind.FirstReleased -> "首版"
        }
        override val nameSeparator = "、"
        override fun timeAndPlace(time: String, place: String) = "$time，$place"
        override fun date(date: ReleaseDate) = "${date.year}年"
    }

    private val en = object : AlbumCreditLabels {
        override fun label(kind: AlbumCreditKind) = when (kind) {
            AlbumCreditKind.Label -> "Label"
            AlbumCreditKind.Producer -> "Producer"
            AlbumCreditKind.Mastering -> "Mastering"
            AlbumCreditKind.Mixing -> "Mix"
            AlbumCreditKind.Recorded -> "Recorded"
            AlbumCreditKind.Source -> "Source"
            AlbumCreditKind.FirstReleased -> "First released"
        }
        override val nameSeparator = ", "
        override fun timeAndPlace(time: String, place: String) = "$time, $place"
        override fun date(date: ReleaseDate) = "${date.year}"
    }

    @Test
    fun should_takeTheServicesLabel_overTheAis() {
        val items = albumCreditItems(listOf(" ECM ", "ECM"), ReleaseDate(1975), null, full)

        assertEquals(listOf("ECM"), items.first { it.kind == AlbumCreditKind.Label }.names)
    }

    @Test
    fun should_fallBackToTheAisLabel_when_theServiceNamesNone() {
        val items = albumCreditItems(emptyList(), null, null, full)

        assertEquals(listOf("AI Label"), items.first { it.kind == AlbumCreditKind.Label }.names)
    }

    @Test
    fun should_orderTheItems_andPreferMasteringOverMix() {
        val items = albumCreditItems(emptyList(), null, null, full)

        assertEquals(
            listOf(
                AlbumCreditKind.Label,
                AlbumCreditKind.Producer,
                AlbumCreditKind.Mastering,
                AlbumCreditKind.Recorded,
                AlbumCreditKind.Source,
                AlbumCreditKind.FirstReleased,
            ),
            kinds(items),
        )
    }

    @Test
    fun should_showTheMix_when_masteringIsUnknown() {
        val items = albumCreditItems(emptyList(), null, null, AlbumAiCredits(mixing = listOf("Mixer")))

        assertEquals(listOf(AlbumCreditKind.Mixing), kinds(items))
    }

    @Test
    fun should_skipEveryUnknownItem_andLeaveNoLine_when_nothingIsKnown() {
        val items = albumCreditItems(emptyList(), ReleaseDate(2020), null, AlbumAiCredits.None)

        assertTrue(items.isEmpty())
        assertNull(albumCreditsLine(items, zh))
    }

    @Test
    fun should_leaveOutSourceAndFirstRelease_when_theyDontApply() {
        val items = albumCreditItems(listOf("ECM"), ReleaseDate(2020), null, AlbumAiCredits(producers = listOf("P")))

        assertEquals(listOf(AlbumCreditKind.Label, AlbumCreditKind.Producer), kinds(items))
    }

    @Test
    fun should_dateTheFirstReleaseFromTheService_when_itIsEarlier() {
        val items = albumCreditItems(emptyList(), ReleaseDate(2024, 3, 8), ReleaseDate(2014, 5, 20), full)

        val first = items.single { it.kind == AlbumCreditKind.FirstReleased }
        assertEquals(ReleaseDate(2014, 5, 20), first.date)
        assertNull(first.text)
    }

    @Test
    fun should_notCallItAReissue_when_theServiceDatesTheFirstReleaseThisYear() {
        // The service's original date is this release's: whatever the AI says, no 首版.
        val items = albumCreditItems(emptyList(), ReleaseDate(2014), ReleaseDate(2014, 5), full)

        assertFalse(AlbumCreditKind.FirstReleased in kinds(items))
    }

    @Test
    fun should_dropTheAisFirstRelease_when_itIsNotEarlierThanThisRelease() {
        val same = albumCreditItems(emptyList(), ReleaseDate(2016), null, AlbumAiCredits(firstReleased = "August 2016"))
        val earlier = albumCreditItems(emptyList(), ReleaseDate(2016), null, AlbumAiCredits(firstReleased = "1997年5月"))
        val undated = albumCreditItems(emptyList(), null, null, AlbumAiCredits(firstReleased = "1997"))

        assertTrue(same.isEmpty())
        assertEquals("1997年5月", earlier.single().text)
        assertEquals("1997", undated.single().text)
    }

    /** The line as read: no-break spaces as spaces, word joiners gone. */
    private fun plain(line: String?) = line?.replace("\u2060", "")?.replace('\u00A0', ' ')

    @Test
    fun should_joinNamesWithTheChineseComma_andItemsWithDots_inChinese() {
        val items = albumCreditItems(
            listOf("ECM"),
            ReleaseDate(1975),
            null,
            AlbumAiCredits(
                producers = listOf("Manfred Eicher", "Steve Lake"),
                recordedTime = "1975年1月",
                recordedStudios = listOf("Rainbow Studio"),
            ),
        )

        assertEquals(
            "厂牌 ECM · 制作 Manfred Eicher、Steve Lake · 录制 1975年1月，Rainbow Studio",
            plain(albumCreditsLine(items, zh)),
        )
    }

    @Test
    fun should_joinNamesWithCommas_inEnglish() {
        val items = albumCreditItems(emptyList(), null, null, AlbumAiCredits(producers = listOf("A", "B"), source = "Akira"))

        assertEquals("Producer A, B · Source Akira", plain(albumCreditsLine(items, en)))
    }

    @Test
    fun should_breakBetweenItemsAndWords_neverInsideALabelOrAChineseName() {
        val items = albumCreditItems(
            listOf("林间工作室"),
            null,
            null,
            full.copy(recordedTime = "Sep–Dec 1975", recordedStudios = listOf("大溪地录音室，台北"), source = "电影《夜航》"),
        )
        val line = checkNotNull(albumCreditsLine(items, zh))
        val cjk = { c: Char -> c.code in 0x2E80..0x9FFF || c.code in 0xFF00..0xFFEF }

        // The dot never starts a line: a no-break space before it, a breakable space after.
        assertEquals(items.size - 1, Regex("\u00A0· ").findAll(line).count())
        line.split(CreditItemSeparator).forEach { item ->
            // The label is glued to its value.
            assertTrue(item, item.substringBefore('\u00A0').none { it == ' ' } && '\u00A0' in item)
            for (i in 1 until item.length) {
                val (before, at) = item[i - 1] to item[i]
                // Word joiners and no-break spaces are themselves no-break points.
                if (at == '\u2060' || before == '\u2060' || at == '\u00A0' || before == '\u00A0') continue
                // A Chinese run only breaks after its comma; a dash holds its neighbours.
                val breakable = (cjk(before) || cjk(at) || before == '–') && before !in "，、；"
                assertFalse("break opportunity in \"$item\" at $i", breakable)
            }
        }
        // 原作 电影《夜航》 holds together; the recording place may break after its comma.
        assertTrue(line.contains("原\u2060作\u00A0电\u2060影\u2060《\u2060夜\u2060航\u2060》"))
        assertTrue(line.contains("大\u2060溪\u2060地\u2060录\u2060音\u2060室\u2060，台\u2060北"))
        assertTrue(line.contains("Sep–\u2060Dec 1975"))
        assertEquals("First\u00A0released", unbreakable("First released"))
        assertEquals("录\u2060制", unbreakable("录制"))
    }

    @Test
    fun should_letLatinValuesBreakBetweenWords_soANarrowColumnNeverSplitsAWord() {
        val items = albumCreditItems(emptyList(), null, null, AlbumAiCredits(recordedStudios = listOf("Sunset Sound, Los Angeles")))

        assertEquals("Recorded\u00A0Sunset Sound, Los Angeles", albumCreditsLine(items, en))
    }

    @Test
    fun should_showTimeOrPlaceAlone_when_onlyOneIsKnown() {
        val timeOnly = albumCreditItems(emptyList(), null, null, AlbumAiCredits(recordedTime = "1975"))
        val placeOnly = albumCreditItems(emptyList(), null, null, AlbumAiCredits(recordedStudios = listOf("A", "B")))

        assertEquals("Recorded 1975", plain(albumCreditsLine(timeOnly, en)))
        assertEquals("Recorded A, B", plain(albumCreditsLine(placeOnly, en)))
    }

    @Test
    fun should_paintTheAboutCardInTheTertiaryRole_likeTheAskCards() {
        listOf(lightColorScheme(), darkColorScheme()).forEach { scheme ->
            val roles = albumAboutRoles(scheme)
            assertEquals(scheme.tertiaryContainer, roles.container)
            assertEquals(scheme.onTertiaryContainer, roles.content)
            assertEquals(scheme.tertiary, roles.mark)
        }
    }

    // --- Scrapbook ordering -------------------------------------------------------------------------

    private fun album(labels: List<String> = emptyList()) = AlbumDetailUiState.Content(
        albumId = "subsonic:al-1",
        albumName = "Album",
        artistName = "Artist",
        artistId = null,
        coverArtId = null,
        coverArtUrl = null,
        year = 2020,
        songCount = 3,
        totalDuration = null,
        songs = (1..3).map { n -> AlbumSong("subsonic:t$n", "Song $n", "Artist", n, 200, isStarred = false) },
        recordLabels = labels,
        releaseDate = ReleaseDate(2020),
    )

    private val withNotes = AlbumScrapbookData(
        notes = mapOf(MediaId.subsonic("t2") to listOf(AlbumScrapbookNote("n1", "words", 1_000, 1))),
    )

    @Test
    fun should_putTheAboutCardRightAfterTheOpening_beforeTheTracks() {
        val data = withNotes.copy(
            albumAbout = AlbumAboutContent(
                blurb = "The paragraph.",
                asks = listOf(AlbumAboutAsk("Q?", "T", "A")),
                generated = true,
            ),
        )

        val pieces = buildAlbumScrapbook(album(), data, nowMillis = 0L).pieces

        assertTrue(pieces[0] is ScrapPiece.Opening)
        val about = pieces[1] as ScrapPiece.AlbumAbout
        assertEquals("The paragraph.", about.text)
        assertFalse(about.pending)
        assertEquals(1, about.asks.size)
        assertTrue(about.fullLine)
        assertTrue(pieces[2] is ScrapPiece.TrackCluster)
    }

    @Test
    fun should_holdTheCardsPlace_while_theAiWritesItTheFirstTime() {
        val pieces = buildAlbumScrapbook(album(), withNotes, nowMillis = 0L, aboutPending = true).pieces

        val about = pieces[1] as ScrapPiece.AlbumAbout
        assertNull(about.text)
        assertTrue(about.pending)
    }

    @Test
    fun should_leaveNoPiece_when_theAboutWasNeverAskedFor() {
        val pieces = buildAlbumScrapbook(album(), withNotes, nowMillis = 0L).pieces

        assertTrue(pieces.none { it is ScrapPiece.AlbumAbout })
    }

    @Test
    fun should_keepAQuietWayBackIn_when_theAiKnewNothing() {
        val data = withNotes.copy(albumAbout = AlbumAboutContent(generated = true))

        val about = buildAlbumScrapbook(album(), data, nowMillis = 0L).pieces[1] as ScrapPiece.AlbumAbout

        // No text and not writing: the paper shows the dashed ✦ circle that opens the sheet (generate, ask).
        assertNull(about.text)
        assertFalse(about.pending)
    }

    @Test
    fun should_countQuestionsAboutTheAlbum_onTheReceipt() {
        val data = withNotes.copy(
            plays = com.gpo.yoin.data.album.AlbumScrapbookPlays(count = 1, firstPlayedAt = 0L, lastPlayedAt = 0L),
            albumAbout = AlbumAboutContent(asks = listOf(AlbumAboutAsk("Q1?", null, "A"), AlbumAboutAsk("Q2?", null, "B"))),
        )

        val receipt = buildAlbumScrapbook(album(), data, nowMillis = 0L).pieces.filterIsInstance<ScrapPiece.Receipt>().single()

        assertEquals("2", receipt.lines.toMap()["Asked"])
    }

    @Test
    fun should_inkTheListenersPapers_withAtLeastAA_contrast_inEveryScheme() {
        val seeds = listOf(0.0, 30.0, 60.0, 120.0, 200.0, 240.0, 280.0, 330.0)
        for (hue in seeds) for (dark in listOf(false, true)) {
            val scheme = com.gpo.yoin.ui.theme.ExpressiveColorSchemeFactory.fromSeed(
                hct.Hct.from(hue, 48.0, 50.0).toInt(),
                isDark = dark,
            )
            val paper = listenerPaperRoles(scheme)
            val about = albumAboutRoles(scheme)
            // Time stamps and mention pills on the review / stickies / notes, text on the About card.
            assertTrue("paper mark $hue $dark", contrast(paper.mark, paper.container) >= 4.5)
            assertTrue("paper text $hue $dark", contrast(paper.content, paper.container) >= 4.5)
            assertTrue("about text $hue $dark", contrast(about.content, about.container) >= 4.5)
        }
    }

    private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return (maxOf(la, lb) / minOf(la, lb)).toDouble()
    }

    @Test
    fun should_keepTheOldParagraph_notTheDots_while_regenerating() {
        val data = withNotes.copy(albumAbout = AlbumAboutContent(blurb = "Old.", generated = true))

        val about = buildAlbumScrapbook(album(), data, nowMillis = 0L, aboutPending = true).pieces[1] as ScrapPiece.AlbumAbout

        assertEquals("Old.", about.text)
        assertFalse(about.pending)
    }

    @Test
    fun should_giveTheOpeningTheCreditsLine_withTheServicesLabelAndNoAi() {
        val opening = buildAlbumScrapbook(album(labels = listOf("ECM")), AlbumScrapbookData.Empty, nowMillis = 0L)
            .pieces.first() as ScrapPiece.Opening

        assertEquals(listOf(AlbumCreditItem(AlbumCreditKind.Label, names = listOf("ECM"))), opening.credits)
    }

    @Test
    fun should_showApplesOwnNotes_overTheAis_andSaySo() {
        val data = withNotes.copy(albumAbout = AlbumAboutContent(blurb = "The AI's paragraph.", generated = true))
        val content = album().copy(editorialNotes = "Apple's notes.")

        val about = buildAlbumScrapbook(content, data, nowMillis = 0L, aboutPending = true).pieces[1] as ScrapPiece.AlbumAbout

        assertEquals("Apple's notes.", about.text)
        assertTrue(about.fromService)
        // Writing the credits doesn't put the dots over Apple's text.
        assertFalse(about.pending)
    }

    @Test
    fun should_showApplesNotes_even_withoutAnyAi() {
        val about = buildAlbumScrapbook(album().copy(editorialNotes = "Apple's notes."), withNotes, nowMillis = 0L)
            .pieces[1] as ScrapPiece.AlbumAbout

        assertEquals("Apple's notes.", about.text)
    }
}
