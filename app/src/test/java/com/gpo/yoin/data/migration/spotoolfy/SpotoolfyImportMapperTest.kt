package com.gpo.yoin.data.migration.spotoolfy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotoolfyImportMapperTest {

    private val first = "4uLU6hMCjMI75M1A2tKUQC"
    private val second = "7qiZfU4dY1lWllzX7mPBI3"

    private fun fixturePlan(): SpotoolfyImportPlan {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("spotoolfy/export_v1.json"))
        val export = (SpotoolfyExportParser.parse(stream) as SpotoolfyParseResult.Parsed).export
        return SpotoolfyImportMapper.map(export)
    }

    private fun record(
        trackId: String = first,
        note: String? = null,
        rating: Int? = null,
        at: Long = 1_000L,
        position: Long? = null,
        lyrics: String? = null,
    ) = SpotoolfyRecord(trackId, note, rating, position, at, lyrics)

    private fun map(vararg records: SpotoolfyRecord, tracks: List<SpotoolfyTrack> = emptyList()) =
        SpotoolfyImportMapper.map(SpotoolfyExport(tracks, records.toList(), unreadableRecords = 0))

    @Test
    fun should_importNotesAndLatestRatings_when_fileIsAV1Export() {
        val plan = fixturePlan()

        assertEquals(listOf(first, second), plan.notes.map { it.trackId })
        assertEquals("前奏一响就想起高中的夏天\n\n「把夏天折成纸飞机\n飞过你家的屋顶」", plan.notes[0].content)
        assertEquals("Placeholder Song", plan.notes[0].title)
        assertEquals("Placeholder Artist, Guest Artist", plan.notes[0].artist)
        assertEquals(83_000L, plan.notes[0].positionMs)
        // The metadata lines of an unsynced snapshot go; the lyric stays.
        assertEquals("summer ends in a minor key 🔥\n\n「summer ends in a minor key」", plan.notes[1].content)
        assertNull(plan.notes[1].positionMs)
        assertEquals(
            listOf(ImportedRating(first, 10f, 1_730_000_300_000L), ImportedRating(second, 5f, 1_730_000_500_000L)),
            plan.ratings,
        )
        // Two unreadable rows, the sample row, and the blank note with a rating Spotoolfy never offered.
        assertEquals(4, plan.skipped)
        assertEquals(1_730_000_000_000L, plan.firstRecordAt)
        assertEquals(1_730_000_500_000L, plan.lastRecordAt)
        assertEquals(setOf(first, second), plan.tracks.keys)
    }

    @Test
    fun should_dropSampleRowsAndForeignIds_when_trackIdIsNotASpotifyTrack() {
        assertEquals(first, SpotoolfyImportMapper.normalizeTrackId("spotify:track:$first"))
        assertEquals(first, SpotoolfyImportMapper.normalizeTrackId(" $first "))
        assertNull(SpotoolfyImportMapper.normalizeTrackId("spotify:track:sample1"))
        assertNull(SpotoolfyImportMapper.normalizeTrackId("spotify:episode:$first"))
        assertNull(SpotoolfyImportMapper.normalizeTrackId("${first}x"))
    }

    @Test
    fun should_mapRatingsToTenPointScale_when_ratingIsOneSpotoolfyOffered() {
        assertEquals(10f, SpotoolfyImportMapper.mapRating(5))
        assertEquals(5f, SpotoolfyImportMapper.mapRating(3))
        assertEquals(1f, SpotoolfyImportMapper.mapRating(0))
        assertNull(SpotoolfyImportMapper.mapRating(4))
    }

    @Test
    fun should_keepNewestRating_when_aTrackWasRatedSeveralTimes() {
        val plan = map(
            record(rating = 5, at = 3_000L),
            record(rating = 0, at = 1_000L),
            record(rating = 3, at = 3_000L),
        )

        // The newest wins; on a tie, the later row in the file.
        assertEquals(listOf(ImportedRating(first, 5f, 3_000L)), plan.ratings)
    }

    @Test
    fun should_importRatingOnly_when_recordHasNoNote() {
        val plan = map(record(rating = 0, lyrics = "[00:01.00]a line"))

        assertTrue(plan.notes.isEmpty())
        assertEquals(listOf(ImportedRating(first, 1f, 1_000L)), plan.ratings)
    }

    @Test
    fun should_giveTheSameNoteTheSameId_when_importedTwice() {
        val once = map(record(note = "night bus music", at = 5L)).notes.single().id
        val again = map(record(note = "night bus music", at = 5L)).notes.single().id

        assertEquals(once, again)
        assertNotEquals(once, map(record(note = "night bus music!", at = 5L)).notes.single().id)
        assertNotEquals(once, map(record(note = "night bus music", at = 6L)).notes.single().id)
        assertNotEquals(once, map(record(trackId = second, note = "night bus music", at = 5L)).notes.single().id)
    }

    @Test
    fun should_importOnce_when_fileRepeatsTheSameNote() {
        val plan = map(record(note = "twice", at = 5L), record(note = "twice", at = 5L))

        assertEquals(1, plan.notes.size)
    }

    @Test
    fun should_leaveNoteUnanchored_when_positionIsZeroOrMissing() {
        val plan = map(record(note = "a", position = 0L), record(note = "b", position = -5L), record(note = "c"))

        assertTrue(plan.notes.all { it.positionMs == null })
    }

    @Test
    fun should_stripTimeAndMetadataTags_when_snapshotIsRawLrc() {
        val snapshot = "[ti:Song]\n[ar:Artist]\n[00:01.00][00:30.00]chorus\r\n\n plain line "

        val lines = SpotoolfyImportMapper.lyricLines(snapshot)

        assertEquals(listOf("chorus", "plain line"), lines)
    }

    @Test
    fun should_leaveTitleUnknown_when_trackRowIsMissing() {
        val plan = map(record(note = "no metadata"))

        assertNull(plan.notes.single().title)
        assertNull(plan.notes.single().artist)
    }

    @Test
    fun should_beEmpty_when_onlySampleRowsExist() {
        val plan = map(record(trackId = "spotify:track:sample1", note = "This is a sample note.", rating = 5))

        assertTrue(plan.isEmpty)
        assertEquals(1, plan.skipped)
        assertEquals(0L, plan.firstRecordAt)
    }
}
