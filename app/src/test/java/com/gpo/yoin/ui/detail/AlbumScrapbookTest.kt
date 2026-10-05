package com.gpo.yoin.ui.detail

import com.gpo.yoin.data.album.AlbumScrapbookAbout
import com.gpo.yoin.data.album.AlbumScrapbookAlbumNote
import com.gpo.yoin.data.album.AlbumScrapbookAsk
import com.gpo.yoin.data.album.AlbumScrapbookData
import com.gpo.yoin.data.album.AlbumScrapbookFact
import com.gpo.yoin.data.album.AlbumScrapbookNote
import com.gpo.yoin.data.album.AlbumScrapbookPlays
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.model.MediaId
import java.time.ZoneOffset
import kotlin.math.sign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumScrapbookTest {

    private val now = 1_791_000_000_000L
    private val day = 86_400_000L

    @Test
    fun should_keepSameTilts_when_rebuiltWithSameInputs() {
        val content = album(trackCount = 12, review = "Good.")
        val data = richData()

        val first = buildAlbumScrapbook(content, data, now, ZoneOffset.UTC)
        val second = buildAlbumScrapbook(content, data, now, ZoneOffset.UTC)

        assertEquals(first, second)
    }

    @Test
    fun should_changeLayoutRhythm_when_albumIdDiffers() {
        val data = richData()
        val a = buildAlbumScrapbook(album(trackCount = 12, id = "subsonic:al-a"), data, now, ZoneOffset.UTC)
        val b = buildAlbumScrapbook(album(trackCount = 12, id = "subsonic:al-b"), data, now, ZoneOffset.UTC)

        assertNotEquals(a.tilts(), b.tilts())
    }

    @Test
    fun should_neverTiltAPieceByZero_when_built() {
        val book = buildAlbumScrapbook(album(trackCount = 12, review = "Good."), richData(), now, ZoneOffset.UTC)

        assertTrue(book.tilts().none { it == 0f })
    }

    @Test
    fun should_putUntouchedTracksInNotYet_when_noScoreNoteOrQuestion() {
        val data = AlbumScrapbookData(
            ratings = mapOf(id(1) to 8f),
            notes = mapOf(id(2) to listOf(note("n", "hi", 10_000))),
            about = mapOf(id(3) to AlbumScrapbookAbout(asks = listOf(ask("q", "A."))))
        )

        val book = buildAlbumScrapbook(album(trackCount = 5), data, now, ZoneOffset.UTC)

        val notYet = book.pieces.filterIsInstance<ScrapPiece.NotYet>().single()
        assertEquals(listOf(4, 5), notYet.tracks.map { it.number })
        assertFalse(notYet.all)
    }

    @Test
    fun should_listEveryTrackAsNotYet_when_albumHasNothing() {
        val book = buildAlbumScrapbook(album(trackCount = 3), AlbumScrapbookData.Empty, now, ZoneOffset.UTC)

        val opening = book.pieces.filterIsInstance<ScrapPiece.Opening>().single()
        assertNull(opening.review)
        val notYet = book.pieces.filterIsInstance<ScrapPiece.NotYet>().single()
        assertTrue(notYet.all)
        assertEquals(3, notYet.tracks.size)
        assertTrue(book.pieces.none { it is ScrapPiece.Receipt || it is ScrapPiece.Masthead })
    }

    @Test
    fun should_pickOneBestTrack_when_topScoreAtLeastNine() {
        val data = AlbumScrapbookData(ratings = mapOf(id(2) to 9.6f, id(4) to 9.6f, id(5) to 8f))

        val book = buildAlbumScrapbook(album(trackCount = 6), data, now, ZoneOffset.UTC)

        val best = book.pieces.filterIsInstance<ScrapPiece.TrackCluster>().filter { it.best }
        assertEquals(listOf(2), best.map { it.track.number })
    }

    @Test
    fun should_haveNoBestTrack_when_topScoreBelowNine() {
        val data = AlbumScrapbookData(ratings = mapOf(id(1) to 8.9f, id(2) to 7f))

        val book = buildAlbumScrapbook(album(trackCount = 3), data, now, ZoneOffset.UTC)

        assertTrue(book.pieces.filterIsInstance<ScrapPiece.TrackCluster>().none { it.best })
    }

    @Test
    fun should_pairScoreOnlyTracks_when_theyRunTogether() {
        val data = AlbumScrapbookData(
            ratings = mapOf(id(1) to 7f, id(2) to 7.5f, id(3) to 8f, id(5) to 6f),
            notes = mapOf(id(4) to listOf(note("n", "x", null))),
        )

        val book = buildAlbumScrapbook(album(trackCount = 6), data, now, ZoneOffset.UTC)

        val sheets = book.pieces.filterIsInstance<ScrapPiece.ContactSheet>()
        assertEquals(listOf(listOf(1, 2), listOf(3), listOf(5)), sheets.map { s -> s.tracks.map { it.number } })
        // the cluster for track 4 sits between the run before it and the one after
        val order = book.pieces.map { it.key }
        assertTrue(order.indexOf("track:subsonic:t4#3") in order.indexOf("sheet:subsonic:t3#2")..order.indexOf("sheet:subsonic:t5#4"))
    }

    @Test
    fun should_alternateSides_when_clustersFollowEachOther() {
        val data = AlbumScrapbookData(
            notes = (1..4).associate { id(it) to listOf(note("n$it", "x", null)) },
        )

        val clusters = buildAlbumScrapbook(album(trackCount = 4), data, now, ZoneOffset.UTC)
            .pieces.filterIsInstance<ScrapPiece.TrackCluster>()

        clusters.zipWithNext().forEach { (a, b) -> assertNotEquals(a.mirror, b.mirror) }
    }

    @Test
    fun should_leanNeighboursOppositeWays_when_sameSign() {
        assertEquals(-1.5f, opposite(2f, 1.5f))
        assertEquals(-1.5f, opposite(1f, -1.5f))
        assertEquals(1.5f, opposite(null, 1.5f))
        assertEquals(1.5f, opposite(-2f, 1.5f))
    }

    @Test
    fun should_showFirstThreeNotes_when_trackHasMore() {
        val notes = (1..5).map { note("n$it", "line $it", it * 10_000L) }
        val data = AlbumScrapbookData(notes = mapOf(id(1) to notes))

        val cluster = buildAlbumScrapbook(album(trackCount = 1), data, now, ZoneOffset.UTC)
            .pieces.filterIsInstance<ScrapPiece.TrackCluster>().single()

        assertEquals(5, cluster.notes.size)
        assertEquals(2, cluster.hiddenNotes)
    }

    @Test
    fun should_useAboutParagraphFirstSentence_when_trackHasNoQuestion() {
        val about = AlbumScrapbookAbout(
            facts = listOf(
                AlbumScrapbookFact(SongAboutEntry.CANON_PRODUCER, "Pharrell"),
                AlbumScrapbookFact(SongAboutEntry.CANON_REVIEW, "A slow song. It was written in 2015."),
            ),
        )
        val cluster = buildAlbumScrapbook(album(trackCount = 1), AlbumScrapbookData(about = mapOf(id(1) to about)), now, ZoneOffset.UTC)
            .pieces.filterIsInstance<ScrapPiece.TrackCluster>().single()

        assertEquals("A slow song.", cluster.aboutLine)
        assertNull(cluster.ask)
        // the paragraph is not a tag; the producer is
        assertEquals(listOf("Producer"), cluster.tags.map { it.label })
        assertEquals(2, cluster.sheet?.facts?.size)
    }

    @Test
    fun should_dropAboutLine_when_trackHasAQuestion() {
        val about = AlbumScrapbookAbout(
            asks = listOf(ask("Why?", "Because.")),
            facts = listOf(AlbumScrapbookFact(SongAboutEntry.CANON_REVIEW, "A slow song.")),
        )
        val cluster = buildAlbumScrapbook(album(trackCount = 1), AlbumScrapbookData(about = mapOf(id(1) to about)), now, ZoneOffset.UTC)
            .pieces.filterIsInstance<ScrapPiece.TrackCluster>().single()

        assertEquals("Why?", cluster.ask?.question)
        assertNull(cluster.aboutLine)
    }

    @Test
    fun should_neverEndWithEllipsis_when_answerIsLong() {
        val answer = "第一句话很短。第二句话也不长，但是加起来会超过预算吗？" + "第三句".repeat(40) + "。"

        val excerpt = answerExcerpt(answer, budget = 40)

        assertEquals("第一句话很短。", excerpt.text)
        assertTrue(excerpt.truncated)
        assertFalse(excerpt.text.endsWith("…"))
    }

    @Test
    fun should_keepTwoSentences_when_theyFitTheBudget() {
        val excerpt = answerExcerpt("One. Two! Three?", budget = 130)

        assertEquals("One. Two!", excerpt.text)
        assertTrue(excerpt.truncated)
    }

    @Test
    fun should_countCjkAsTwo_when_weighingText() {
        assertEquals(4, weightedLength("**中文**"))
        assertEquals(3, weightedLength("abc"))
    }

    @Test
    fun should_showMasthead_when_memoryTitleCached() {
        val with = buildAlbumScrapbook(album(trackCount = 1), AlbumScrapbookData(memoryTitle = "从白天听到天黑"), now, ZoneOffset.UTC)
        val without = buildAlbumScrapbook(album(trackCount = 1), AlbumScrapbookData(), now, ZoneOffset.UTC)

        assertEquals("从白天听到天黑", (with.pieces.first() as ScrapPiece.Masthead).title)
        assertTrue(without.pieces.none { it is ScrapPiece.Masthead })
    }

    @Test
    fun should_writeReceipt_when_albumWasPlayedInYoin() {
        val data = AlbumScrapbookData(
            ratings = mapOf(id(1) to 8f, MediaId(MediaId.PROVIDER_SUBSONIC, "gone") to 9f),
            notes = mapOf(id(1) to listOf(note("a", "x", 1L), note("b", "y", 2L))),
            about = mapOf(id(2) to AlbumScrapbookAbout(asks = listOf(ask("q", "a.")))),
            plays = AlbumScrapbookPlays(count = 12, firstPlayedAt = now - 7 * day, lastPlayedAt = now - day),
        )

        val receipt = buildAlbumScrapbook(album(trackCount = 3), data, now, ZoneOffset.UTC)
            .pieces.filterIsInstance<ScrapPiece.Receipt>().single()

        assertEquals(
            listOf(
                "Plays in Yoin" to "12",
                "Days since first play" to "7",
                "Last played" to "Yesterday",
                "Rated" to "1 / 3",
                "Notes" to "2",
                "Asked" to "1",
            ),
            receipt.lines,
        )
        assertEquals("ALBUM — ARTIST", receipt.header)
    }

    @Test
    fun should_pasteTheAlbumNoteOnTheOpening_when_albumHasOne() {
        val data = AlbumScrapbookData(albumNote = AlbumScrapbookAlbumNote("an", "  Heard it on the way to the airport.  ", 1L))

        val opening = buildAlbumScrapbook(album(trackCount = 2, review = "Good."), data, now, ZoneOffset.UTC)
            .pieces.filterIsInstance<ScrapPiece.Opening>().single()

        assertEquals("Heard it on the way to the airport.", opening.albumNote)
        // a paper tilt, leaning against the review clipping
        assertTrue(opening.albumNoteTilt in ScrapbookRules.PaperTilts.toList())
        assertNotEquals(sign(opening.reviewTilt), sign(opening.albumNoteTilt))
    }

    @Test
    fun should_pasteNoAlbumNote_when_albumHasNoneOrABlankOne() {
        listOf(AlbumScrapbookData.Empty, AlbumScrapbookData(albumNote = AlbumScrapbookAlbumNote("an", "   ", 1L)))
            .forEach { data ->
                val opening = buildAlbumScrapbook(album(trackCount = 2), data, now, ZoneOffset.UTC)
                    .pieces.filterIsInstance<ScrapPiece.Opening>().single()

                assertNull(opening.albumNote)
                assertEquals(0f, opening.albumNoteTilt)
            }
    }

    @Test
    fun should_countTheAlbumNote_when_writingTheReceipt() {
        val data = AlbumScrapbookData(
            notes = mapOf(id(1) to listOf(note("a", "x", 1L))),
            albumNote = AlbumScrapbookAlbumNote("an", "The album's note.", 1L),
            plays = AlbumScrapbookPlays(count = 1, firstPlayedAt = now, lastPlayedAt = now),
        )

        val receipt = buildAlbumScrapbook(album(trackCount = 2), data, now, ZoneOffset.UTC)
            .pieces.filterIsInstance<ScrapPiece.Receipt>().single()

        assertTrue("Notes" to "2" in receipt.lines)
    }

    @Test
    fun should_omitReceipt_when_neverPlayedInYoin() {
        val book = buildAlbumScrapbook(album(trackCount = 2), AlbumScrapbookData(ratings = mapOf(id(1) to 8f)), now, ZoneOffset.UTC)

        assertTrue(book.pieces.none { it is ScrapPiece.Receipt })
    }

    @Test
    fun should_formatScoresLikeTheEmblem_when_rounding() {
        assertEquals("10.0", scrapScoreText(9.95f))
        assertEquals("7.8", scrapScoreText(7.8f))
        assertEquals("8.0", scrapScoreText(8f))
    }

    @Test
    fun should_markRatedTracksInAlbumOrder_when_buildingEmblemSpec() {
        val content = album(trackCount = 4).copy(
            ratedSongIds = setOf("subsonic:t2", "subsonic:t4"),
            averageTrackRating = 8f,
            ratedTrackCount = 2,
        )

        val spec = content.emblemSpec()

        assertEquals(listOf(false, true, false, true), spec.trackRated)
        assertEquals(AlbumScoreKind.Average, spec.score.kind)
    }

    @Test
    fun should_useOneLaneOnPhones_when_widthIsCompact() {
        assertEquals(1, scrapLanes(androidx.compose.ui.unit.Dp(358f)))
        assertEquals(2, scrapLanes(androidx.compose.ui.unit.Dp(752f)))
        assertEquals(2, scrapLanes(androidx.compose.ui.unit.Dp(608f)))
        assertEquals(3, scrapLanes(androidx.compose.ui.unit.Dp(1040f)))
    }

    private fun AlbumScrapbook.tilts(): List<Float> = pieces.flatMap { piece ->
        when (piece) {
            is ScrapPiece.Opening ->
                listOfNotNull(piece.coverTilt, piece.reviewTilt, piece.albumNoteTilt.takeIf { piece.albumNote != null })
            is ScrapPiece.TrackCluster -> listOfNotNull(
                piece.ticketTilt,
                piece.notesTilt.takeIf { piece.notes.isNotEmpty() },
                piece.cardTilt.takeIf { piece.ask != null || piece.aboutLine != null },
                piece.track.heartTilt,
            ) + piece.tags.map { it.tilt }
            is ScrapPiece.ContactSheet -> piece.tilts
            is ScrapPiece.NotYet -> piece.tracks.map { it.tilt }
            is ScrapPiece.Receipt -> listOf(piece.tilt)
            is ScrapPiece.Masthead -> emptyList()
        }
    }

    private fun id(n: Int) = MediaId(MediaId.PROVIDER_SUBSONIC, "t$n")

    private fun note(id: String, text: String, at: Long?) = AlbumScrapbookNote(id, text, at, createdAt = 1L)

    private fun ask(question: String, answer: String) = AlbumScrapbookAsk(question, title = null, answer = answer)

    private fun richData() = AlbumScrapbookData(
        ratings = mapOf(id(1) to 8.4f, id(2) to 9.3f, id(3) to 9.1f, id(5) to 8.8f, id(7) to 9.6f),
        notes = mapOf(id(2) to listOf(note("n1", "guitar", 52_000)), id(7) to listOf(note("n2", "voices", 161_000))),
        about = mapOf(
            id(9) to AlbumScrapbookAbout(asks = listOf(ask("Why the switch?", "It changes. Halfway."))),
            id(3) to AlbumScrapbookAbout(facts = listOf(AlbumScrapbookFact(SongAboutEntry.CANON_PRODUCER, "Pharrell"))),
        ),
        plays = AlbumScrapbookPlays(count = 3, firstPlayedAt = now - 3 * day, lastPlayedAt = now),
        memoryTitle = "Title",
        albumNote = AlbumScrapbookAlbumNote("an", "Album note words.", 1L),
    )

    private fun album(
        trackCount: Int,
        id: String = "subsonic:al-1",
        review: String = "",
    ) = AlbumDetailUiState.Content(
        albumId = id,
        albumName = "Album",
        artistName = "Artist",
        artistId = null,
        coverArtId = null,
        coverArtUrl = null,
        year = 2020,
        songCount = trackCount,
        totalDuration = null,
        songs = (1..trackCount).map { n ->
            AlbumSong(id = "subsonic:t$n", title = "Song $n", artist = "Artist", trackNumber = n, duration = 200, isStarred = n == 2)
        },
        userReview = review,
    )
}
