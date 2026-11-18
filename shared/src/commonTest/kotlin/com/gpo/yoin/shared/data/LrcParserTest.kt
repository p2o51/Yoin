package com.gpo.yoin.shared.data

import com.gpo.yoin.shared.data.lyrics.LrcParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ported from the Android app's LrcParserTest, plus the offset tag and lineIndexAt. */
class LrcParserTest {

    @Test
    fun should_parseSyncedLines_when_basicLrc() {
        val result = LrcParser.parse("[00:12.34]first line\n[01:05.00]second line")
        assertTrue(result.synced)
        assertEquals(2, result.lines.size)
        assertEquals(12_340L, result.lines[0].timeMs)
        assertEquals("first line", result.lines[0].text)
        assertEquals(65_000L, result.lines[1].timeMs)
        assertEquals("second line", result.lines[1].text)
    }

    @Test
    fun should_dropMetadataTags_when_timedLinesPresent() {
        val raw = "[ti:Title]\n[ar:Artist]\n[al:Album]\n[by:uploader]\n[length:03:20]\n[00:01.00]line one"
        val result = LrcParser.parse(raw)
        assertTrue(result.synced)
        assertEquals(1, result.lines.size)
        assertEquals(1_000L, result.lines[0].timeMs)
        assertEquals("line one", result.lines[0].text)
    }

    @Test
    fun should_expandLine_when_multipleTimestamps() {
        val result = LrcParser.parse("[00:12.00][00:50.00]repeating chorus")
        assertEquals(listOf(12_000L, 50_000L), result.lines.map { it.timeMs })
        assertTrue(result.lines.all { it.text == "repeating chorus" })
    }

    @Test
    fun should_readFractionByDigitCount_when_tenthsHundredthsOrMillis() {
        assertEquals(123L, LrcParser.parse("[00:00.123]precise").lines.single().timeMs)
        assertEquals(120L, LrcParser.parse("[00:00.12]centi").lines.single().timeMs)
        assertEquals(500L, LrcParser.parse("[00:00.5]tenth").lines.single().timeMs)
        assertEquals(61_000L, LrcParser.parse("[01:01]no fraction").lines.single().timeMs)
        assertEquals(1_250L, LrcParser.parse("[00:01:25]colon fraction").lines.single().timeMs)
    }

    @Test
    fun should_fallBackToUnsynced_when_noTimestamps() {
        val raw = "Just some\nplain lyrics\nwithout timestamps"
        val result = LrcParser.parse(raw)
        assertFalse(result.synced)
        assertEquals(raw, result.text)
        assertEquals(3, result.lines.size)
        assertTrue(result.lines.all { it.timeMs == 0L })
        assertEquals(-1, result.lineIndexAt(10_000L))
    }

    @Test
    fun should_sortByTime_when_linesOutOfOrder() {
        val result = LrcParser.parse("[00:20.00]second\n[00:10.00]first")
        assertEquals(listOf("first", "second"), result.lines.map { it.text })
    }

    @Test
    fun should_returnEmptyUnsynced_when_inputEmpty() {
        val result = LrcParser.parse("")
        assertFalse(result.synced)
        assertTrue(result.lines.isEmpty())
        assertEquals("", result.text)
    }

    @Test
    fun should_dropTimestampOnlyLines_when_textEmpty() {
        val result = LrcParser.parse("[00:01.00]\n[00:02.00]real line")
        assertEquals(1, result.lines.size)
        assertEquals("real line", result.lines[0].text)
    }

    @Test
    fun should_showLinesSooner_when_positiveOffset() {
        val result = LrcParser.parse("[offset:+500]\n[00:01.00]a\n[00:00.20]b")
        assertEquals(listOf(0L, 500L), result.lines.map { it.timeMs })
        assertEquals(listOf("b", "a"), result.lines.map { it.text })
    }

    @Test
    fun should_showLinesLater_when_negativeOffset() {
        val result = LrcParser.parse("[offset:-100]\n[00:01.00]line one")
        assertEquals(1_100L, result.lines.single().timeMs)
    }

    @Test
    fun should_findCurrentLine_when_positionBetweenLines() {
        val result = LrcParser.parse("[00:01.00]a\n[00:03.00]b\n[00:05.00]c")
        assertEquals(-1, result.lineIndexAt(500L))
        assertEquals(0, result.lineIndexAt(1_000L))
        assertEquals(1, result.lineIndexAt(4_999L))
        assertEquals(2, result.lineIndexAt(60_000L))
    }
}
