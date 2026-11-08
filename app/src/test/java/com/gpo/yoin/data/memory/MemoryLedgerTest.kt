package com.gpo.yoin.data.memory

import com.gpo.yoin.data.local.AlbumMemoryTitle
import com.gpo.yoin.data.local.AlbumNote
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.LedgerAlbumFactRow
import com.gpo.yoin.data.local.LedgerSongNoteRow
import com.gpo.yoin.data.local.LedgerTrackRatingRow
import com.gpo.yoin.data.local.SongNote
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The memory ledger: rows → entries, stats, the density grid, and the month list. */
class MemoryLedgerTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 8) // a Sunday

    private fun at(date: LocalDate, hour: Int = 12): Long = date.atTime(hour, 0).toInstant(zone).toEpochMilli()

    private fun fact(profile: String, album: String, name: String = "Album $album", cover: String? = "cover-$album") =
        LedgerAlbumFactRow(profile, "subsonic", album, name, "Artist", cover, 0L)

    private fun albumNote(id: String, profile: String, album: String, date: LocalDate, text: String = "note") =
        AlbumNote(id, profile, album, "subsonic", text, at(date), at(date), "", "")

    private fun songNote(id: String, profile: String, album: String?, date: LocalDate) = LedgerSongNoteRow(
        SongNote(id, profile, "t-$id", "subsonic", "lyric", at(date), at(date), "Song $id", "Artist", 90_000L),
        album,
    )

    private fun trackScore(profile: String, song: String, album: String?, date: LocalDate) =
        LedgerTrackRatingRow(profile, "subsonic", song, 8f, at(date), album)

    // ── buildMemoryLedger ──────────────────────────────────────────────

    @Test
    fun should_readEveryAccount_and_skipRemovedOnes() = runTest {
        val rows = MemoryLedgerRows(
            albumNotes = listOf(
                albumNote("n1", "a", "x", today),
                albumNote("n2", "b", "y", today.minusDays(1)),
                albumNote("n3", "gone", "z", today),
            ),
            albumFacts = listOf(fact("a", "x"), fact("b", "y"), fact("gone", "z")),
        )

        val entries = buildMemoryLedger(rows, profileIds = setOf("a", "b"))

        assertEquals(listOf("album-note:n1", "album-note:n2"), entries.map { it.id })
        assertEquals(listOf("a", "b"), entries.map { it.profileId })
    }

    @Test
    fun should_nameAlbums_fromPlays_thenNote_thenLookup() = runTest {
        val rows = MemoryLedgerRows(
            albumNotes = listOf(
                AlbumNote("n1", "a", "noted", "subsonic", "x", at(today), at(today), "From the note", "Note artist"),
                albumNote("n2", "a", "cached", today),
                albumNote("n3", "a", "nowhere", today),
            ),
        )
        val looked = mutableListOf<String>()

        val entries = buildMemoryLedger(rows, profileIds = null) { profile, provider, id ->
            looked += id
            if (id == "cached") MemoryLedgerAlbum(profile, provider, id, "From the cache", "", "c") else null
        }

        val byId = entries.associate { it.id to it.album }
        assertEquals("From the note", byId["album-note:n1"]?.name)
        assertEquals("From the cache", byId["album-note:n2"]?.name)
        // An album that can't be named at all is left out.
        assertFalse("album-note:n3" in byId)
        // The noted album has no cover: the cache is asked for one, once per album.
        assertEquals(listOf("noted", "cached", "nowhere"), looked)
    }

    @Test
    fun should_keepSongNote_withoutAlbum() = runTest {
        val entries = buildMemoryLedger(
            MemoryLedgerRows(songNotes = listOf(songNote("s1", "a", null, today))),
            profileIds = null,
        )

        assertEquals(MemoryLedgerKind.SongNote, entries.single().kind)
        assertNull(entries.single().album)
        assertEquals("Song s1", entries.single().trackTitle)
    }

    @Test
    fun should_splitRatingRow_intoScoreAndReview() = runTest {
        val rating = AlbumRating(
            profileId = "a",
            albumId = "x",
            rating = 7.5f,
            review = "  good  ",
            neoDbReviewUuid = null,
            updatedAt = at(today),
            reviewUpdatedAt = at(today.minusDays(3)),
        )
        val unscored = rating.copy(albumId = "y", rating = 0f, review = null)

        val entries = buildMemoryLedger(
            MemoryLedgerRows(
                albumRatings = listOf(rating, unscored),
                albumFacts = listOf(fact("a", "x"), fact("a", "y")),
            ),
            profileIds = null,
        )

        assertEquals(listOf(MemoryLedgerKind.AlbumRating, MemoryLedgerKind.Review), entries.map { it.kind })
        assertEquals("good", entries[1].text)
        assertEquals(at(today.minusDays(3)), entries[1].at)
    }

    @Test
    fun should_countStats() = runTest {
        val entries = buildMemoryLedger(
            MemoryLedgerRows(
                albumNotes = listOf(albumNote("n1", "a", "x", today, text = "回忆😀")),
                songNotes = listOf(songNote("s1", "a", "y", today.minusDays(40))),
                trackRatings = listOf(trackScore("a", "t9", "x", today)),
                titles = listOf(AlbumMemoryTitle("a", "subsonic", "x", "Title", at(today))),
                albumFacts = listOf(fact("a", "x"), fact("a", "y")),
            ),
            profileIds = null,
        )

        val stats = memoryLedgerStats(entries)

        assertEquals(2, stats.albums)
        assertEquals(2, stats.writings)
        assertEquals(3 + "lyric".length, stats.characters)
        assertEquals(1, stats.ratedTracks)
        assertEquals(at(today.minusDays(40)), stats.firstAt)
    }

    @Test
    fun should_nameScoredSong_fromRow_elseFromAlbumTracks() = runTest {
        val rows = MemoryLedgerRows(
            trackRatings = listOf(
                trackScore("a", "t1", "x", today).copy(title = "Played"),
                trackScore("a", "t2", "x", today),
                trackScore("a", "t3", null, today),
            ),
            albumFacts = listOf(fact("a", "x")),
        )

        val entries = buildMemoryLedger(rows, profileIds = null, trackTitle = { _, songId ->
            if (songId == "t2") "From the album" else null
        })

        val titles = entries.associate { it.id.substringAfterLast('|') to it.trackTitle }
        assertEquals("Played", titles["t1"])
        assertEquals("From the album", titles["t2"])
        assertNull(titles["t3"])
    }

    // ── buildMemoryGrid ────────────────────────────────────────────────

    private fun album(id: String) = MemoryLedgerAlbum("a", "subsonic", id, id, "", null)

    private fun entry(
        id: String,
        date: LocalDate,
        album: String? = "x",
        kind: MemoryLedgerKind = MemoryLedgerKind.AlbumNote,
    ) = MemoryLedgerEntry(id, kind, at(date), "a", album?.let(::album), text = "t")

    private fun daysBack(count: Int) = (0 until count).map { entry("e$it", today.minusDays(it.toLong())) }

    @Test
    fun should_pickScale_byActiveDays() {
        assertEquals(MemoryGridScale.Month, buildMemoryGrid(daysBack(9), today, zone).scale)
        assertEquals(MemoryGridScale.Week, buildMemoryGrid(daysBack(10), today, zone).scale)
        assertEquals(MemoryGridScale.Week, buildMemoryGrid(daysBack(29), today, zone).scale)
        assertEquals(MemoryGridScale.Day, buildMemoryGrid(daysBack(30), today, zone).scale)
    }

    @Test
    fun should_countOnlyLookback_when_choosingScale() {
        // 40 busy days, but all before the last 17 weeks: still a month a cell.
        val old = (0 until 40).map { entry("o$it", today.minusDays(200L + it)) }

        assertEquals(MemoryGridScale.Month, buildMemoryGrid(old, today, zone).scale)
    }

    @Test
    fun should_spanSixToTwelveMonths_when_monthly() {
        val recent = buildMemoryGrid(listOf(entry("e", today)), today, zone)
        assertEquals(6, recent.cells.size)
        assertEquals(YearMonth.of(2026, 6).atDay(1), recent.cells.first().start)
        assertEquals(YearMonth.of(2026, 11).atEndOfMonth(), recent.cells.last().end)

        val nine = buildMemoryGrid(listOf(entry("e", today.minusMonths(8))), today, zone)
        assertEquals(9, nine.cells.size)

        val old = buildMemoryGrid(listOf(entry("e", today.minusYears(3))), today, zone)
        assertEquals(12, old.cells.size)
    }

    @Test
    fun should_rankWrittenFirst_thenMostMarks_capFour_when_monthly() {
        val entries = listOf(
            entry("1", today, "scored", MemoryLedgerKind.TrackRating),
            entry("2", today, "scored", MemoryLedgerKind.TrackRating),
            entry("3", today, "scored", MemoryLedgerKind.TrackRating),
            entry("4", today, "wrote"),
            entry("5", today, "b"),
            entry("6", today, "b"),
            entry("7", today, "c"),
            entry("8", today, "d"),
        )

        val cell = buildMemoryGrid(entries, today, zone).cells.last()

        assertEquals(listOf("b", "wrote", "c", "d").sorted(), cell.marks.map { it.album.albumId }.sorted())
        assertEquals("b", cell.marks.first().album.albumId)
        assertTrue(cell.marks.all { it.wrote })
        assertEquals(8, cell.entries)
        assertEquals(5, cell.writings)
    }

    @Test
    fun should_layWeeks_fromFirstDayOfWeek() {
        val grid = buildMemoryGrid(daysBack(12), today, zone, firstDayOfWeek = DayOfWeek.MONDAY)

        assertEquals(MemoryGridScale.Week, grid.scale)
        assertEquals(26, grid.cells.size)
        assertEquals(LocalDate.of(2026, 11, 2), grid.cells.last().start)
        assertEquals(DayOfWeek.MONDAY, grid.cells.first().start.dayOfWeek)
        assertTrue(grid.cells.all { it.marks.size <= 2 })
        assertFalse(grid.cells.last().future)
    }

    @Test
    fun should_markFutureDays_when_daily() {
        val thursday = LocalDate.of(2026, 11, 5)
        val busy = (0 until 30).map { entry("e$it", thursday.minusDays(it.toLong())) }

        val grid = buildMemoryGrid(busy, thursday, zone, firstDayOfWeek = DayOfWeek.MONDAY)

        assertEquals(MemoryGridScale.Day, grid.scale)
        assertEquals(17 * 7, grid.cells.size)
        // Thursday the 5th: Friday to Sunday haven't come.
        assertEquals(3, grid.cells.count { it.future })
    }

    @Test
    fun should_spliceTwoAlbums_when_weekHasMore() {
        val entries = listOf(entry("1", today, "x"), entry("2", today, "y"), entry("3", today, "z")) +
            (1 until 12).map { entry("f$it", today.minusDays(it * 7L), "x") }

        val grid = buildMemoryGrid(entries, today, zone, scale = MemoryGridScale.Week)

        assertEquals(2, grid.cells.last().marks.size)
    }

    // ── memoryLedgerRows ───────────────────────────────────────────────

    @Test
    fun should_foldQuietMonths_and_scores() {
        val entries = listOf(
            entry("n", today),
            entry("s1", today, "x", MemoryLedgerKind.TrackRating),
            entry("s2", today, "y", MemoryLedgerKind.AlbumRating),
            entry("old", LocalDate.of(2026, 6, 20)),
        ).sortedByDescending { it.at }

        val rows = memoryLedgerRows(entries, today, zone)

        assertEquals(
            listOf(
                MemoryLedgerRow.Month(YearMonth.of(2026, 11), writings = 1, scores = 2),
                MemoryLedgerRow.Entry(entries.first { it.id == "n" }),
                MemoryLedgerRow.Scores(YearMonth.of(2026, 11), scores = 2, albums = 2),
                MemoryLedgerRow.Quiet(from = YearMonth.of(2026, 10), to = YearMonth.of(2026, 7)),
                MemoryLedgerRow.Month(YearMonth.of(2026, 6), writings = 1, scores = 0),
                MemoryLedgerRow.Entry(entries.first { it.id == "old" }),
            ),
            rows,
        )
    }

    @Test
    fun should_listScoresOneByOne_when_monthHasOnlyScores() {
        val entries = listOf(entry("s1", today, "x", MemoryLedgerKind.TrackRating))

        val rows = memoryLedgerRows(entries, today, zone)

        assertEquals(2, rows.size)
        assertTrue(rows[1] is MemoryLedgerRow.Entry)
    }

    @Test
    fun should_startAtThisMonth_evenWhenItIsQuiet() {
        val rows = memoryLedgerRows(listOf(entry("old", LocalDate.of(2026, 9, 1))), today, zone)

        assertEquals(MemoryLedgerRow.Quiet(YearMonth.of(2026, 11), YearMonth.of(2026, 10)), rows.first())
    }
}
