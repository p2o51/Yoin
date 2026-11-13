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

        val entries = buildMemoryLedger(rows, profileIds = null, lookup = { profile, provider, id ->
            looked += id
            if (id == "cached") MemoryLedgerAlbum(profile, provider, id, "From the cache", "", "c") else null
        })

        val byId = entries.associate { it.id to it.album }
        assertEquals("From the note", byId["album-note:n1"]?.name)
        assertEquals("From the cache", byId["album-note:n2"]?.name)
        // An album that can't be named at all is left out.
        assertFalse("album-note:n3" in byId)
        // The noted album has no cover: the cache is asked for one, once per album.
        assertEquals(listOf("noted", "cached", "nowhere"), looked)
    }

    @Test
    fun should_nameAScoresAlbum_fromItsSongsLookup() = runTest {
        val entries = buildMemoryLedger(
            MemoryLedgerRows(
                trackRatings = listOf(
                    LedgerTrackRatingRow("a", "subsonic", "song1", 7.9f, at(today), linkedAlbumId = null),
                ),
            ),
            profileIds = null,
            knownTrack = { profile, provider, song ->
                if (profile == "a" && provider == "subsonic" && song == "song1") {
                    LedgerTrack("Pulse", "al1", "Pulse EP", "Regene", "c-al1")
                } else {
                    null
                }
            },
        )

        val entry = entries.single()
        assertEquals("Pulse", entry.trackTitle)
        assertEquals("al1", entry.album?.albumId)
        assertEquals("Pulse EP", entry.album?.name)
        assertEquals("c-al1", entry.album?.coverKey)
    }

    @Test
    fun should_keepAnAlbumsOwnMarks_when_onlyItsSongsLookupNamesIt() = runTest {
        // Synced from another device: the album was never played or opened here, and its note carries no name.
        val rating = AlbumRating(
            profileId = "a",
            albumId = "al1",
            rating = 8.5f,
            review = "kept",
            neoDbReviewUuid = null,
            updatedAt = at(today),
        )

        val entries = buildMemoryLedger(
            MemoryLedgerRows(
                albumNotes = listOf(albumNote("n1", "a", "al1", today)),
                albumRatings = listOf(rating),
                trackRatings = listOf(trackScore("a", "song1", null, today), trackScore("a", "song2", null, today)),
                titles = listOf(AlbumMemoryTitle("a", "subsonic", "al1", "Title", at(today))),
            ),
            profileIds = null,
            knownTrack = { _, _, song -> LedgerTrack("Song $song", "al1", "Pulse EP", "Regene", "c-al1") },
        )

        // The album's note, score, review and title stand beside the two song scores that named it.
        assertEquals(
            listOf(
                MemoryLedgerKind.AlbumNote,
                MemoryLedgerKind.Review,
                MemoryLedgerKind.Title,
                MemoryLedgerKind.AlbumRating,
                MemoryLedgerKind.TrackRating,
                MemoryLedgerKind.TrackRating,
            ),
            entries.map { it.kind }.sorted(),
        )
        assertTrue(entries.all { it.album?.name == "Pulse EP" })
        assertEquals(2, memoryLedgerStats(entries).writings)
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

    // ── memoryTimelineRows ─────────────────────────────────────────────

    private fun mark(
        id: String,
        date: LocalDate,
        album: String?,
        kind: MemoryLedgerKind,
        song: String? = null,
        score: Float? = null,
        text: String? = null,
    ) = MemoryLedgerEntry(
        id = id,
        kind = kind,
        at = at(date),
        profileId = "a",
        album = album?.let(::album),
        text = text,
        trackTitle = song,
        songId = song,
        score = score,
    )

    @Test
    fun should_gatherAnAlbumsMonth_intoOneCard_between_songNotes() {
        val entries = listOf(
            mark("t", today, "x", MemoryLedgerKind.Title, text = "Rain"),
            mark("n", today.minusDays(1), "x", MemoryLedgerKind.SongNote, song = "s1", text = "note"),
            mark("r", today.minusDays(2), "x", MemoryLedgerKind.AlbumRating, score = 8.5f),
            mark("s1", today.minusDays(3), "x", MemoryLedgerKind.TrackRating, song = "s1", score = 9f),
            mark("s2", today.minusDays(3), "x", MemoryLedgerKind.TrackRating, song = "s2", score = 7f),
            mark("y", today.minusDays(4), "y", MemoryLedgerKind.AlbumRating, score = 6f),
            mark("old", LocalDate.of(2026, 6, 20), "z", MemoryLedgerKind.Review, text = "old"),
        ).sortedByDescending { it.at }

        val rows = memoryTimelineRows(entries, today, zone)

        assertEquals(
            listOf("month:2026-11", "album:x", "note:n", "album:y", "quiet", "month:2026-06", "album:z"),
            rows.map {
                when (it) {
                    is MemoryTimelineRow.Album -> "album:${it.item.album.albumId}"
                    is MemoryTimelineRow.Quiet -> "quiet"
                    else -> it.key
                }
            },
        )
        val card = (rows[1] as MemoryTimelineRow.Album).item
        assertEquals("Rain", card.title)
        assertEquals(8.5f, card.score)
        assertEquals(listOf(9f, 7f), card.songs.map { it.score })
        // Cards alternate sides, across months.
        assertEquals(listOf(false, true, false), rows.filterIsInstance<MemoryTimelineRow.Album>().map { it.end })
        assertEquals(MemoryTimelineRow.Quiet(YearMonth.of(2026, 10), YearMonth.of(2026, 7)), rows[4])
    }

    @Test
    fun should_gatherAMonthsScoresWithoutAlbum_intoOneCard() {
        val rows = memoryTimelineRows(
            listOf(
                mark("s", today, null, MemoryLedgerKind.TrackRating, song = "s", score = 8f),
                mark("n", today.minusDays(1), null, MemoryLedgerKind.SongNote, song = "n", text = "hm"),
                mark("t", today.minusDays(2), null, MemoryLedgerKind.TrackRating, song = "t", score = 6f),
            ),
            today,
            zone,
        )

        val scores = rows[1] as MemoryTimelineRow.Scores
        assertEquals(listOf("s", "t"), scores.entries.map { it.id })
        assertTrue(rows[2] is MemoryTimelineRow.Note)
        assertEquals(3, rows.size)
    }

    @Test
    fun should_startAtThisMonth_evenWhenItIsQuiet() {
        val rows = memoryTimelineRows(listOf(entry("old", LocalDate.of(2026, 9, 1))), today, zone)

        assertEquals(MemoryTimelineRow.Quiet(YearMonth.of(2026, 11), YearMonth.of(2026, 10)), rows.first())
    }

    // ── memoryLedgerItems ──────────────────────────────────────────────

    @Test
    fun should_makeAnItem_perAlbum_and_perNotedSong() {
        val entries = listOf(
            mark("n1", today, "x", MemoryLedgerKind.SongNote, song = "s1", text = "new"),
            mark("n0", today.minusDays(5), "x", MemoryLedgerKind.SongNote, song = "s1", text = "old"),
            mark("s1", today.minusDays(1), "x", MemoryLedgerKind.TrackRating, song = "s1", score = 9f),
            mark("r", today.minusDays(2), "y", MemoryLedgerKind.AlbumRating, score = 7f),
            mark("lost", today.minusDays(3), null, MemoryLedgerKind.TrackRating, song = "s9", score = 6f),
        )

        val items = memoryLedgerItems(entries)

        assertEquals(
            listOf("song:a|s1", "album:a|subsonic|x", "album:a|subsonic|y", "song:a|s9"),
            items.map { it.key },
        )
        val song = items[0] as MemoryLedgerItem.Song
        assertEquals("new", song.note?.text)
        assertEquals(2, song.notes)
        assertEquals(9f, song.score)
        // The scored song makes album x an item too, listing it.
        assertEquals(listOf(9f), (items[1] as MemoryLedgerItem.Album).songs.map { it.score })
    }

    @Test
    fun should_sortItems_byScore_unscoredLast_and_byName() {
        val entries = listOf(
            mark("a", today, "b", MemoryLedgerKind.Title, text = "t"),
            mark("b", today.minusDays(1), "a", MemoryLedgerKind.AlbumRating, score = 6f),
            mark("c", today.minusDays(2), "c", MemoryLedgerKind.AlbumRating, score = 9f),
        )
        val items = memoryLedgerItems(entries)

        assertEquals(listOf("c", "a", "b"), items.sortedFor(MemoryLedgerSort.Score, naturalOrder()).map { it.name })
        assertEquals(listOf("a", "b", "c"), items.sortedFor(MemoryLedgerSort.Name, naturalOrder()).map { it.name })
    }
}
