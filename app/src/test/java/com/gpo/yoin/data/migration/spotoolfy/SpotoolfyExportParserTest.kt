package com.gpo.yoin.data.migration.spotoolfy

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Enumeration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotoolfyExportParserTest {

    private fun fixture(name: String): InputStream =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("spotoolfy/$name")) { "missing fixture $name" }

    private fun parse(json: String): SpotoolfyParseResult =
        SpotoolfyExportParser.parse(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))

    private fun parsed(result: SpotoolfyParseResult): SpotoolfyExport {
        assertTrue("expected Parsed, got $result", result is SpotoolfyParseResult.Parsed)
        return (result as SpotoolfyParseResult.Parsed).export
    }

    private fun export(records: String, tracks: String = "[]") =
        """{"tracks":$tracks,"records":$records,"translations":[],"play_contexts":[]}"""

    /** One stream over [parts], built lazily so a huge file never sits in memory. */
    private fun streamOf(parts: Sequence<String>): InputStream {
        val iterator = parts.iterator()
        val streams = object : Enumeration<InputStream> {
            override fun hasMoreElements() = iterator.hasNext()

            override fun nextElement(): InputStream = ByteArrayInputStream(iterator.next().toByteArray(Charsets.UTF_8))
        }
        return SequenceInputStream(streams)
    }

    @Test
    fun should_readTracksAndRecords_when_fileIsAV1Export() {
        val export = parsed(SpotoolfyExportParser.parse(fixture("export_v1.json")))

        assertEquals(
            listOf("4uLU6hMCjMI75M1A2tKUQC", "7qiZfU4dY1lWllzX7mPBI3", "spotify:track:sample1"),
            export.tracks.map { it.trackId },
        )
        assertEquals("Placeholder Artist, Guest Artist", export.tracks[0].artist)
        assertEquals("https://i.scdn.co/image/ab67616d0000b273placeholder0001", export.tracks[0].coverUrl)
        assertNull(export.tracks[2].coverUrl)
        // Six rows are objects; the one without recordedAt and the string row are unreadable.
        assertEquals(5, export.records.size)
        assertEquals(2, export.unreadableRecords)
        val second = export.records[1]
        assertEquals("  前奏一响就想起高中的夏天  ", second.note)
        assertEquals(3, second.rating)
        assertEquals(83_000L, second.positionMs)
        assertEquals("[00:12.34]把夏天折成纸飞机\n[00:15.00]飞过你家的屋顶", second.lyricsSnapshot)
    }

    @Test
    fun should_readTimesWrittenAsTextOrFraction_when_spotoolfyWroteThemThatWay() {
        val export = parsed(SpotoolfyExportParser.parse(fixture("export_v1.json")))

        assertEquals(1_730_000_300_000L, export.records[2].recordedAt)
        assertEquals(1_730_000_500_000L, export.records[3].recordedAt)
    }

    @Test
    fun should_countTextRatingAsNeutral_when_ratingIsAString() {
        val export = parsed(SpotoolfyExportParser.parse(fixture("export_v1.json")))

        assertEquals(3, export.records[3].rating)
    }

    @Test
    fun should_keepWholeNumberRatingsOnly_when_ratingIsAFraction() {
        val records = """[
            {"trackId":"a","recordedAt":1,"rating":5.0},
            {"trackId":"a","recordedAt":2,"rating":2.5},
            {"trackId":"a","recordedAt":3,"rating":true}
        ]"""

        assertEquals(listOf(5, null, null), parsed(parse(export(records))).records.map { it.rating })
    }

    @Test
    fun should_skipBom_when_fileStartsWithOne() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            export("""[{"trackId":"a","recordedAt":1}]""").toByteArray(Charsets.UTF_8)

        assertEquals(1, parsed(SpotoolfyExportParser.parse(ByteArrayInputStream(bytes))).records.size)
    }

    @Test
    fun should_returnNotSpotoolfy_when_aTableIsMissing() {
        assertEquals(SpotoolfyParseResult.NotSpotoolfy, parse("""{"tracks":[],"records":[],"translations":[]}"""))
    }

    @Test
    fun should_returnNotSpotoolfy_when_aTableIsNotAList() {
        assertEquals(
            SpotoolfyParseResult.NotSpotoolfy,
            parse("""{"tracks":[],"records":{},"translations":[],"play_contexts":[]}"""),
        )
    }

    @Test
    fun should_returnNotSpotoolfy_when_rootIsNotAnObject() {
        assertEquals(SpotoolfyParseResult.NotSpotoolfy, parse("""[{"tracks":[]}]"""))
    }

    @Test
    fun should_returnCorrupt_when_fileIsCutShort() {
        assertEquals(SpotoolfyParseResult.Corrupt, parse("""{"tracks":[],"records":[{"trackId":"a","""))
        assertEquals(SpotoolfyParseResult.Corrupt, parse(""))
        assertEquals(SpotoolfyParseResult.Corrupt, parse("not json at all"))
    }

    @Test
    fun should_returnTooLarge_when_fileIsOverTheLimit() {
        val row = """{"trackId":"4uLU6hMCjMI75M1A2tKUQC","recordedAt":1,"noteContent":"${"x".repeat(1000)}"},"""
        val rows = generateSequence { row }.take((SpotoolfyExportParser.MAX_BYTES / row.length + 10).toInt())

        val result = SpotoolfyExportParser.parse(streamOf(sequenceOf("""{"records":[""") + rows))

        assertEquals(SpotoolfyParseResult.TooLarge, result)
    }

    @Test
    fun should_readEveryRecord_when_exportHasFiftyThousandRecords() {
        val count = 50_000
        val rows = (0 until count).asSequence().map { i ->
            val comma = if (i == count - 1) "" else ","
            val at = 1_700_000_000_000L + i
            """{"id":$i,"trackId":"4uLU6hMCjMI75M1A2tKUQC","noteContent":"note $i","rating":5,"recordedAt":$at}$comma"""
        }
        val head = """{"tracks":[],"translations":[],"play_contexts":[],"records":["""

        val export = parsed(SpotoolfyExportParser.parse(streamOf(sequenceOf(head) + rows + sequenceOf("]}"))))

        assertEquals(count, export.records.size)
        assertEquals(1_700_000_049_999L, export.records.last().recordedAt)
    }
}
