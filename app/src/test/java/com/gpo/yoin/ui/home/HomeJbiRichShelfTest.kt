package com.gpo.yoin.ui.home

import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.memories.MemoryEntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jump Back In pulled longer keeps its memories and notes coming, and a card
 * can be put away for a while (owner 2026-10-13); the Memories bubble's idle
 * reminder brings back a different memory each time (owner 2026-10-15).
 */
class HomeJbiRichShelfTest {

    private fun cover(index: Int) = HomeWidgetCard(
        stableId = "c$index",
        entityType = listOf(MemoryEntityType.ALBUM, MemoryEntityType.SONG, MemoryEntityType.PLAYLIST)[index % 3],
        title = "Cover $index",
        subtitle = "Artist",
        coverArtUrl = "https://x/$index",
        target = HomeWidgetTarget.AlbumDetail("subsonic:c$index"),
    )

    private fun signal(index: Int) = HomeWidgetCard(
        stableId = "s$index",
        entityType = MemoryEntityType.ALBUM,
        title = "Signal $index",
        subtitle = "Artist",
        coverArtUrl = "https://x/s$index",
        ratingText = "8.0",
        comment = "Note $index",
        expanded = true,
        target = HomeWidgetTarget.MemoryFocus(index.toLong()),
    )

    private fun shelf(signals: Int, covers: Int = 40): List<HomeWidgetCard> =
        (1..signals).map(::signal) + (1..covers).map(::cover)

    private val phoneJbi = jbiGridSpec(feedUnits = 2, coverColumns = 3, isCompactHeight = false)

    private fun ids(layout: JbiLayout): Set<String> = layout.cells.mapTo(HashSet()) { it.card.stableId }

    // ---- longer shelves ----

    @Test
    fun should_addASignalEverySixCells_when_theShelfIsPastTwelve() {
        assertEquals(2, jbiSignalLimit(rows = 4, cells = 12))
        assertEquals(3, jbiSignalLimit(rows = 6, cells = 18))
        assertEquals(4, jbiSignalLimit(rows = 4, cells = 24))
    }

    @Test
    fun should_seatAThirdSignal_when_thePhoneShelfIsPulledToXl() {
        val ladder = jbiPackedLadder(phoneJbi, shelf(signals = 4))
        val counts = ladder.map { stop ->
            val cards = stop.payload.flatten()
            cards.count { it.expanded } to cards.count { !it.expanded }
        }
        // L is as before; XL's 18 cells seat three wide cards and twelve covers.
        assertEquals(listOf(1 to 4, 2 to 5, 2 to 8, 3 to 12), counts)
        ladder.zipWithNext().forEach { (lower, upper) ->
            val upperIds = upper.payload.flatten().mapTo(HashSet()) { it.stableId }
            assertTrue(upperIds.containsAll(lower.payload.flatten().map { it.stableId }))
        }
    }

    @Test
    fun should_standNewSignalsInTheGrownRows_when_aTemplateHasSpareSignals() {
        val grewWithSignals = IntArray(11)
        for (columns in 3..10) for (seed in 0 until 30) {
            val spec = jbiGridSpec(feedUnits = 4, coverColumns = columns, isCompactHeight = false)
            val cards = shelf(signals = 6)
            val base = jbiLayout(cards, spec.columns, spec.rows, seed)!!
            val xl = jbiTemplateLadder(spec, base, cards).last().payload
            val at = "K=$columns seed=$seed"
            if (xl === base) continue
            // Every card of L is still seated, once; the grid tiles once.
            assertTrue(at, ids(xl).containsAll(ids(base)))
            assertEquals(at, xl.cells.size, ids(xl).size)
            val seen = Array(xl.template.rows) { IntArray(columns) }
            xl.template.pieces.forEach { p ->
                for (r in p.row until p.endRow) for (c in p.column until p.endColumn) seen[r][c]++
            }
            assertTrue(at, seen.all { row -> row.all { it == 1 } })
            assertEquals(at, xl.template.pieces, xl.cells.map { it.piece })
            // The grown rows brought signal cards of their own, standing over the old bottom row.
            val added = xl.cells.filter { it.piece.kind.isSignal && it.card.stableId !in ids(base) }
            if (added.isNotEmpty()) grewWithSignals[columns]++
            added.forEach { cell ->
                assertEquals(at, JbiPieceKind.TallSignal, cell.piece.kind)
                assertEquals(at, base.template.rows - 1, cell.piece.row)
                assertTrue(at, cell.card.expanded)
            }
            // Never two signal cards side by side on the old bottom row.
            val bottom = base.template.rows - 1
            val bottomSignalColumns = xl.cells
                .filter { it.piece.kind.isSignal && bottom in it.piece.row until it.piece.endRow }
                .flatMap { it.piece.column until it.piece.endColumn }
                .sorted()
            assertTrue(at, added.all { cell -> (cell.piece.column + 1) !in bottomSignalColumns })
            // Reading order holds.
            val order = xl.cells.map { xl.template.topOf(it.piece) to it.piece.column }
            assertEquals(at, order.sortedWith(compareBy({ it.first }, { it.second })), order)
        }
        // A lane is free for a new card on most shelves (on three lanes the two
        // first cards can leave none).
        for (columns in 3..10) assertTrue("K=$columns", grewWithSignals[columns] > 0)
    }

    // ---- hiding ----

    @Test
    fun should_keyAMemoryCardByItsAlbum_when_hiding() {
        val memory = signal(1).copy(stableId = "${MEMORY_CARD_PREFIX}subsonic:al:42")
        val albumCover = cover(1).copy(target = HomeWidgetTarget.AlbumDetail("subsonic:al:42"))
        assertEquals(albumCover.jbiHideKey(), memory.jbiHideKey())
        assertEquals(MediaId("subsonic", "al:42"), memoryCardAlbum(memory.stableId))
        assertNull(memoryCardAlbum("grid-album:subsonic:al:42"))
    }

    @Test
    fun should_keySongsAndPlaylistsApart_when_hiding() {
        val song = Track(
            id = MediaId("subsonic", "t1"),
            title = "Song",
            artist = null,
            artistId = null,
            album = null,
            albumId = null,
            coverArt = null,
            durationSec = null,
            trackNumber = null,
            year = null,
            genre = null,
            userRating = null,
        )
        val songCard = cover(2).copy(target = HomeWidgetTarget.PlaySong(song))
        val playlistCard = cover(3).copy(target = HomeWidgetTarget.PlaylistDetail("subsonic:t1"))
        assertEquals("song:subsonic:t1", songCard.jbiHideKey())
        assertEquals("playlist:subsonic:t1", playlistCard.jbiHideKey())
    }

    @Test
    fun should_forgetAHiddenCard_when_itsTimeIsUp() {
        val store = JbiHiddenStore.InMemory()
        store.hide("subsonic|p1", "album:subsonic:a", until = 1_000L)
        store.hide("subsonic|p2", "album:subsonic:b", until = 5_000L)
        assertEquals(setOf("album:subsonic:a"), store.hidden("subsonic|p1", now = 999L))
        assertEquals(emptySet<String>(), store.hidden("subsonic|p1", now = 1_000L))
        // Per account.
        assertEquals(setOf("album:subsonic:b"), store.hidden("subsonic|p2", now = 999L))
        assertEquals(3L * 24 * 60 * 60 * 1000, JbiHideSpan.ThreeDays.millis)
        assertEquals(7L * 24 * 60 * 60 * 1000, JbiHideSpan.OneWeek.millis)
    }

    // ---- memories ----

    private fun candidate(
        albumId: String,
        lastWrittenAt: Long? = null,
        review: Boolean = false,
        rating: Float? = 8f,
    ) = AlbumMemoryCandidate(
        profileId = "p1",
        provider = MediaId.PROVIDER_SUBSONIC,
        albumId = albumId,
        albumName = "Album $albumId",
        artistName = "Artist",
        totalTracks = 10,
        ratedTrackCount = 0,
        ratingCoverage = 0f,
        averageSongRating = null,
        albumRating = rating,
        hasAlbumReview = review,
        noteCount = 0,
        askAiCount = 0,
        firstPlayedAt = null,
        lastPlayedAt = null,
        lastWrittenAt = lastWrittenAt,
        playCount = 1,
        neoDbSynced = false,
        isMemoryEligible = true,
        year = null,
        durationSeconds = null,
        coverArtUrl = null,
    )

    @Test
    fun should_putTheDefaultPickFirstThenReviewedThenRated_when_pickingSeveralMemories() {
        val candidates = listOf(
            candidate("a"),
            candidate("b", review = true),
            candidate("c"),
            candidate("d", review = true),
            candidate("e", rating = null),
            candidate("latest", review = true),
        )
        val one = pickJbiMemoryCandidate(candidates, avoidRawAlbumId = "latest")
        val picks = pickJbiMemoryCandidates(candidates, avoidRawAlbumId = "latest", count = 4)
        assertEquals(one, picks.first())
        assertEquals(listOf("b", "d", "a", "c"), picks.map { it.albumId })
        assertEquals(listOf("b"), pickJbiMemoryCandidates(candidates, "latest", count = 1).map { it.albumId })
    }

    @Test
    fun should_bringBackTheOtherMemoriesNewestFirst_when_buildingTheRecall() {
        val latest = candidate("new", lastWrittenAt = 300L)
        val recall = pickMemoryRecall(
            listOf(candidate("old", lastWrittenAt = 100L), latest, candidate("mid", lastWrittenAt = 200L)),
            latest,
        )
        assertEquals(listOf("mid", "old"), recall.map { it.albumId })
        val pill = buildHomeMemoryPill(
            listOf(candidate("old", lastWrittenAt = 100L), latest, candidate("mid", lastWrittenAt = 200L)),
            noteCount = 0,
        )
        assertEquals("new", pill.latest?.albumName?.removePrefix("Album "))
        assertEquals(listOf("Album mid", "Album old"), pill.recall.map { it.albumName })
    }

    @Test
    fun should_tellNewsAndRotateTheIdleReminder_when_choosingWhatTheBubbleSays() {
        val pill = buildHomeMemoryPill(
            listOf(
                candidate("new", lastWrittenAt = 300L),
                candidate("mid", lastWrittenAt = 200L),
                candidate("old", lastWrittenAt = 100L),
            ),
            noteCount = 0,
        )
        assertEquals("Album new", memoryBubbleSubject(pill, BubbleReason.News, turn = 5)?.albumName)
        val idle = (0..3).map { memoryBubbleSubject(pill, BubbleReason.Idle, turn = it)?.albumName }
        assertEquals(listOf("Album mid", "Album old", "Album mid", "Album old"), idle)
        // Nothing else kept yet: the reminder says the latest.
        val alone = buildHomeMemoryPill(listOf(candidate("only", lastWrittenAt = 1L)), noteCount = 0)
        assertEquals("Album only", memoryBubbleSubject(alone, BubbleReason.Idle, turn = 3)?.albumName)
        val store = InMemoryMemoryBubbleSeenStore()
        assertEquals(listOf(0, 1, 2), List(3) { store.nextRecallTurn("s") })
        assertEquals(0, store.nextRecallTurn("other"))
    }
}
