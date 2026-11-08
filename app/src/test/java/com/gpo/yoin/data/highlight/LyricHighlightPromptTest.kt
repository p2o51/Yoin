package com.gpo.yoin.data.highlight

import com.gpo.yoin.data.model.LyricLine
import com.gpo.yoin.data.model.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricHighlightPromptTest {

    private fun synced(vararg lines: String): Lyrics.Synced =
        Lyrics.Synced(lines.mapIndexed { i, text -> LyricLine(startMs = i * 1_000L, text = text) })

    private val song = synced(
        "我把你说过的晚安",
        "折成纸船放回海里",
        "",
        "(Instrumental)",
        "我把你说过的晚安",
        "潮水退了又涨",
        "灯塔还亮着",
        "...",
        "你在哪里",
    )

    @Test
    fun should_numberSungLinesOnce_when_preparing() {
        val prepared = LyricHighlightPrompt.prepare(song)!!

        assertEquals(
            listOf("我把你说过的晚安", "折成纸船放回海里", "潮水退了又涨", "灯塔还亮着", "你在哪里"),
            prepared.lines.map { it.text },
        )
        assertEquals(listOf(1, 2, 3, 4, 5), prepared.lines.map { it.number })
        // The first occurrence keeps its time.
        assertEquals(0L, prepared.lines.first().startMs)
        assertEquals(5_000L, prepared.lines[2].startMs)
    }

    @Test
    fun should_skip_when_tooLittleIsSung() {
        assertNull(LyricHighlightPrompt.prepare(synced("la la", "la la", "(Instrumental)", "♪")))
    }

    @Test
    fun should_readUnsyncedLyrics_withoutTimes() {
        val prepared = LyricHighlightPrompt.prepare(Lyrics.Unsynced("one\ntwo\nthree\nfour"))!!
        assertEquals(4, prepared.lines.size)
        assertTrue(prepared.lines.all { it.startMs == null })
    }

    @Test
    fun should_changeFingerprint_when_lyricsChange() {
        val a = LyricHighlightPrompt.prepare(song)!!
        val b = LyricHighlightPrompt.prepare(synced("我把你说过的晚安", "折成纸船放回海里", "潮水退了又涨", "灯塔灭了"))!!
        assertEquals(a.fingerprint, LyricHighlightPrompt.prepare(song)!!.fingerprint)
        assertNotEquals(a.fingerprint, b.fingerprint)
    }

    @Test
    fun should_returnOriginalLines_when_answerIsValidJson() {
        val prepared = LyricHighlightPrompt.prepare(song)!!

        val pick = LyricHighlightPrompt.parse("{\"start\": 1, \"end\": 2}", prepared)!!

        assertEquals("我把你说过的晚安\n折成纸船放回海里", pick.text)
        assertEquals(0L, pick.startMs)
    }

    @Test
    fun should_acceptNearMisses_when_parsing() {
        val prepared = LyricHighlightPrompt.prepare(song)!!

        assertEquals("灯塔还亮着", LyricHighlightPrompt.parse("```json\n{\"start\":4,\"end\":4}\n```", prepared)!!.text)
        assertEquals("潮水退了又涨\n灯塔还亮着", LyricHighlightPrompt.parse("3-4", prepared)!!.text)
        assertEquals("你在哪里", LyricHighlightPrompt.parse("5", prepared)!!.text)
        assertEquals("你在哪里", LyricHighlightPrompt.parse("{\"start\": 5}", prepared)!!.text)
    }

    @Test
    fun should_reject_when_answerIsNotAValidRange() {
        val prepared = LyricHighlightPrompt.prepare(song)!!

        assertNull(LyricHighlightPrompt.parse(null, prepared))
        assertNull(LyricHighlightPrompt.parse("The best line is about the lighthouse.", prepared))
        assertNull(LyricHighlightPrompt.parse("{\"start\": 0, \"end\": 1}", prepared))
        assertNull(LyricHighlightPrompt.parse("{\"start\": 5, \"end\": 6}", prepared))
        assertNull(LyricHighlightPrompt.parse("{\"start\": 3, \"end\": 2}", prepared))
        // Far more lines than a card holds: the model drifted.
        assertNull(LyricHighlightPrompt.parse("{\"start\": 1, \"end\": 9}", prepared))
    }

    @Test
    fun should_stopAtAGapInTheSong_when_neighbouringNumbersAreFarApart() {
        val prepared = LyricHighlightPrompt.prepare(song)!!

        // Line 3 comes after a left-out repeat, so it is another part of the song.
        val pick = LyricHighlightPrompt.parse("```json\n{\"start\": 1, \"end\": 3}\n```", prepared)!!
        assertEquals("我把你说过的晚安\n折成纸船放回海里", pick.text)

        // Three lines that follow each other are kept whole.
        val three = LyricHighlightPrompt.parse("{\"start\": 3, \"end\": 5}", prepared)!!
        assertEquals("潮水退了又涨\n灯塔还亮着\n你在哪里", three.text)
    }

    @Test
    fun should_changeFingerprint_when_promptVersionChanges() {
        assertTrue(LyricHighlightPrompt.prepare(song)!!.fingerprint.startsWith(LyricHighlightPrompt.PROMPT_VERSION))
    }

    @Test
    fun should_keepFirstLine_when_twoLinesRunTooLong() {
        val long = "x".repeat(105)
        val prepared = LyricHighlightPrompt.prepare(synced("short one", long, "three", "four"))!!

        val pick = LyricHighlightPrompt.parse("{\"start\": 1, \"end\": 2}", prepared)!!
        assertEquals("short one", pick.text)

        val tooLong = LyricHighlightPrompt.prepare(synced("y".repeat(120), "two", "three", "four"))!!
        assertNull(LyricHighlightPrompt.parse("{\"start\": 1, \"end\": 1}", tooLong))
    }

    @Test
    fun should_listEveryLine_when_buildingPrompt() {
        val prepared = LyricHighlightPrompt.prepare(song)!!
        val prompt = LyricHighlightPrompt.userPrompt("潮汐表", "森川", prepared)

        assertTrue(prompt.startsWith("Song: 潮汐表 — 森川\n"))
        assertTrue(prompt.contains("1. 我把你说过的晚安\n"))
        assertTrue(prompt.contains("5. 你在哪里\n"))
    }
}
