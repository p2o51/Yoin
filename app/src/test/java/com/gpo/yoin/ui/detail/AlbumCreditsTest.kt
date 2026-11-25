package com.gpo.yoin.ui.detail

import androidx.compose.material3.darkColorScheme
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
            "厂⁠牌 ECM · 制⁠作 Manfred Eicher、Steve Lake · 录⁠制 1975年1月，Rainbow Studio",
            albumCreditsLine(items, zh),
        )
    }

    @Test
    fun should_joinNamesWithCommas_inEnglish() {
        val items = albumCreditItems(emptyList(), null, null, AlbumAiCredits(producers = listOf("A", "B"), source = "Akira"))

        assertEquals("Producer A, B · Source Akira", albumCreditsLine(items, en))
    }

    @Test
    fun should_wrapOnlyAfterASeparator_neverBetweenALabelAndItsValue() {
        val line = checkNotNull(albumCreditsLine(albumCreditItems(listOf("ECM"), null, null, full), en))

        // Every item boundary is "no-break space, dot, space": a line can only break after the dot.
        assertEquals(5, Regex(" · ").findAll(line).count())
        assertFalse(line.contains(" · "))
        // Each label is glued to its value.
        listOf("Label", "Producer", "Mastering", "Recorded", "Source", "First released").forEach { label ->
            assertTrue(label, line.contains("$label "))
        }
        // Latin labels keep their letters as they are; CJK labels are joined so 录制 never splits.
        assertEquals("First released", unbreakable("First released"))
        assertEquals("录⁠制", unbreakable("录制"))
    }

    @Test
    fun should_showTimeOrPlaceAlone_when_onlyOneIsKnown() {
        val timeOnly = albumCreditItems(emptyList(), null, null, AlbumAiCredits(recordedTime = "1975"))
        val placeOnly = albumCreditItems(emptyList(), null, null, AlbumAiCredits(recordedStudios = listOf("A", "B")))

        assertEquals("Recorded 1975", albumCreditsLine(timeOnly, en))
        assertEquals("Recorded A, B", albumCreditsLine(placeOnly, en))
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
    fun should_leaveTheCardOff_when_thereIsNoParagraph() {
        val data = withNotes.copy(albumAbout = AlbumAboutContent(generated = true))

        val pieces = buildAlbumScrapbook(album(), data, nowMillis = 0L).pieces

        assertTrue(pieces.none { it is ScrapPiece.AlbumAbout })
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
