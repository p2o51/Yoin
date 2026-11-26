package com.gpo.yoin.ui.detail

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.lightColorScheme
import com.gpo.yoin.shared.albumabout.AlbumAboutAsk
import com.gpo.yoin.shared.albumabout.AlbumAboutContent
import com.gpo.yoin.shared.albumabout.AlbumCreditItem
import com.gpo.yoin.shared.albumabout.AlbumCreditKind
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
    fun should_keepThePiece_forAQuestionAboutTheAlbum_withoutAParagraph() {
        // Asked a question though the About itself was never written (or failed): the question still shows.
        val data = withNotes.copy(albumAbout = AlbumAboutContent(asks = listOf(AlbumAboutAsk("Q?", "T", "A"))))

        val about = buildAlbumScrapbook(album(), data, nowMillis = 0L).pieces[1] as ScrapPiece.AlbumAbout

        assertNull(about.text)
        assertFalse(about.pending)
        assertEquals(listOf("Q?"), about.asks.map { it.question })
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
