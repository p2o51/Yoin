package com.gpo.yoin.data.lyrics

import com.gpo.yoin.data.model.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun parses_basic_synced_lrc() {
        val raw = """
            [00:12.34]first line
            [01:05.00]second line
        """.trimIndent()

        val result = LrcParser.parse(raw)

        assertTrue(result is Lyrics.Synced)
        result as Lyrics.Synced
        assertEquals(2, result.lines.size)
        assertEquals(12_340L, result.lines[0].startMs)
        assertEquals("first line", result.lines[0].text)
        assertEquals(65_000L, result.lines[1].startMs)
        assertEquals("second line", result.lines[1].text)
    }

    @Test
    fun strips_metadata_tags_keeps_timed_lines() {
        val raw = """
            [ti:Title]
            [ar:Artist]
            [al:Album]
            [by:uploader]
            [offset:-100]
            [00:01.00]line one
        """.trimIndent()

        val result = LrcParser.parse(raw)

        assertTrue(result is Lyrics.Synced)
        result as Lyrics.Synced
        assertEquals(1, result.lines.size)
        assertEquals(1_000L, result.lines[0].startMs)
        assertEquals("line one", result.lines[0].text)
    }

    @Test
    fun expands_multi_timestamp_lines() {
        val raw = "[00:12.00][00:50.00]repeating chorus"

        val result = LrcParser.parse(raw)

        assertTrue(result is Lyrics.Synced)
        result as Lyrics.Synced
        assertEquals(2, result.lines.size)
        assertEquals(12_000L, result.lines[0].startMs)
        assertEquals(50_000L, result.lines[1].startMs)
        assertTrue(result.lines.all { it.text == "repeating chorus" })
    }

    @Test
    fun three_digit_millis_are_preserved() {
        val raw = "[00:00.123]precise"

        val result = LrcParser.parse(raw) as Lyrics.Synced
        assertEquals(123L, result.lines.first().startMs)
    }

    @Test
    fun two_digit_millis_are_read_as_centiseconds() {
        val raw = "[00:00.12]centi"

        val result = LrcParser.parse(raw) as Lyrics.Synced
        assertEquals(120L, result.lines.first().startMs)
    }

    @Test
    fun falls_back_to_unsynced_when_no_timestamps() {
        val raw = """
            Just some
            plain lyrics
            without timestamps
        """.trimIndent()

        val result = LrcParser.parse(raw)

        assertTrue(result is Lyrics.Unsynced)
        result as Lyrics.Unsynced
        assertEquals(raw, result.text)
    }

    @Test
    fun sorts_lines_by_start_ms() {
        val raw = """
            [00:20.00]second
            [00:10.00]first
        """.trimIndent()

        val result = LrcParser.parse(raw) as Lyrics.Synced
        assertEquals(10_000L, result.lines[0].startMs)
        assertEquals(20_000L, result.lines[1].startMs)
    }

    @Test
    fun empty_input_is_empty_unsynced() {
        val result = LrcParser.parse("")
        assertTrue(result is Lyrics.Unsynced)
        assertEquals("", (result as Lyrics.Unsynced).text)
    }

    @Test
    fun drops_timestamp_only_lines_with_empty_text() {
        val raw = """
            [00:01.00]
            [00:02.00]real line
        """.trimIndent()

        val result = LrcParser.parse(raw) as Lyrics.Synced
        assertEquals(1, result.lines.size)
        assertEquals("real line", result.lines[0].text)
    }
}
