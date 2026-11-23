package com.gpo.yoin.data.highlight

import com.gpo.yoin.data.model.LyricLine
import com.gpo.yoin.data.model.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricHighlightPromptTest {

    private fun synced(vararg lines: String): Lyrics.Synced =
        Lyrics.Synced(lines.mapIndexed { i, text -> LyricLine(startMs = i * 2_000L, text = text) })

    private val song = synced(
        "我把你说过的晚安",
        "折成纸船放回海里",
        "",
        "(Instrumental)",
        "我把你说过的晚安",
        "潮水退了又涨",
        "灯塔还亮着",
        "...",
        "你在哪里寻找远方",
    )

    @Test
    fun should_numberSungLinesOnce_andTrackStanzaBreaks_when_preparing() {
        val prepared = LyricHighlightPrompt.prepare(song)!!

        assertEquals(
            listOf("我把你说过的晚安", "折成纸船放回海里", "潮水退了又涨", "灯塔还亮着", "你在哪里寻找远方"),
            prepared.lines.map { it.text },
        )
        assertEquals(listOf(1, 2, 3, 4, 5), prepared.lines.map { it.number })
        // Blank/(Instrumental) and "..." advance the stanza counter.
        assertEquals(listOf(0, 0, 1, 1, 2), prepared.lines.map { it.stanza })
        assertEquals(0L, prepared.lines.first().startMs)
        assertEquals(10_000L, prepared.lines[2].startMs)
    }

    @Test
    fun should_splitStanzaOnLongSyncedInterlude() {
        val interlude = Lyrics.Synced(
            listOf(
                LyricLine(startMs = 0L, text = "太阳公公出来了 他对我呀笑呀笑"),
                LyricLine(startMs = 4_000L, text = "我爱你 你知不知道"),
                // 10-second instrumental break into chorus
                LyricLine(startMs = 14_000L, text = "曾经在我眼前 却又消失不见"),
                LyricLine(startMs = 18_000L, text = "这是今天的第六遍"),
            ),
        )
        val prepared = LyricHighlightPrompt.prepare(interlude)!!

        assertEquals(listOf(0, 0, 1, 1), prepared.lines.map { it.stanza })
        val prompt = LyricHighlightPrompt.userPrompt("我爱你", "卢广仲", prepared)
        // A blank line separates stanza 0 from stanza 1 so Nano sees the musical break.
        assertTrue(prompt.contains("2. 我爱你 你知不知道\n\n3. 曾经在我眼前 却又消失不见\n"))

        // Regression from owner screenshot: asking for 1-3 stops at the stanza break, leaving 1-2 intact.
        val pick = LyricHighlightPrompt.parse("{\"start\": 1, \"end\": 3}", prepared)!!
        assertEquals("太阳公公出来了 他对我呀笑呀笑\n我爱你 你知不知道", pick.text)
    }

    @Test
    fun should_skip_when_tooLittleIsSung() {
        assertNull(LyricHighlightPrompt.prepare(synced("la la", "la la", "(Instrumental)", "♪")))
    }

    @Test
    fun should_readUnsyncedLyrics_andPreserveBlankLineStanzas() {
        val prepared = LyricHighlightPrompt.prepare(
            Lyrics.Unsynced("First line of verse\nSecond line of verse\n\nFirst line of chorus\nSecond line of chorus"),
        )!!
        assertEquals(4, prepared.lines.size)
        assertTrue(prepared.lines.all { it.startMs == null })
        assertEquals(listOf(0, 0, 1, 1), prepared.lines.map { it.stanza })
    }

    @Test
    fun should_changeFingerprint_when_lyricsChange() {
        val a = LyricHighlightPrompt.prepare(song)!!
        val b = LyricHighlightPrompt.prepare(synced("我把你说过的晚安", "折成纸船放回海里", "潮水退了又涨", "灯塔灭了"))!!
        assertEquals(a.fingerprint, LyricHighlightPrompt.prepare(song)!!.fingerprint)
        assertNotEquals(a.fingerprint, b.fingerprint)
    }

    @Test
    fun should_returnOriginalLines_forOneTwoOrThreeLinePicks() {
        val verse = synced(
            "我把你说过的晚安折成纸船",
            "潮水退了又涨灯塔还亮着",
            "你在海风里回头看了我一眼",
            "从此夜色都有了你的名字",
        )
        val prepared = LyricHighlightPrompt.prepare(verse)!!

        // 1 line (< 4 lines, substantive)
        assertEquals(
            "我把你说过的晚安折成纸船",
            LyricHighlightPrompt.parse("{\"start\": 1, \"end\": 1}", prepared)!!.text,
        )
        // 2 lines
        assertEquals(
            "我把你说过的晚安折成纸船\n潮水退了又涨灯塔还亮着",
            LyricHighlightPrompt.parse("{\"start\": 1, \"end\": 2}", prepared)!!.text,
        )
        // 3 lines
        assertEquals(
            "我把你说过的晚安折成纸船\n潮水退了又涨灯塔还亮着\n你在海风里回头看了我一眼",
            LyricHighlightPrompt.parse("{\"start\": 1, \"end\": 3}", prepared)!!.text,
        )
        // 4 lines rejected (must be < 4; never silently chop the 4th line)
        assertNull(LyricHighlightPrompt.parse("{\"start\": 1, \"end\": 4}", prepared))
    }

    @Test
    fun should_acceptNearMisses_withoutGluingUnaskedLines() {
        val prepared = LyricHighlightPrompt.prepare(song)!!

        assertEquals(
            "潮水退了又涨\n灯塔还亮着",
            LyricHighlightPrompt.parse("```json\n{\"start\":3,\"end\":4}\n```", prepared)!!.text,
        )
        assertEquals("潮水退了又涨\n灯塔还亮着", LyricHighlightPrompt.parse("3-4", prepared)!!.text)
        // Single substantive line stands on its own without pulling line 4 across a stanza break.
        assertEquals("你在哪里寻找远方", LyricHighlightPrompt.parse("5", prepared)!!.text)
        assertEquals("你在哪里寻找远方", LyricHighlightPrompt.parse("{\"start\": 5}", prepared)!!.text)
    }

    @Test
    fun should_rejectTinyOneLineStubs_andCrossStanzaSingleLineRemnants() {
        val prepared = LyricHighlightPrompt.prepare(
            synced("晚安", "折成纸船放回海里", "", "潮水退了又涨", "灯塔还亮着"),
        )!!

        // Too short to stand alone as a 1-line highlight.
        assertNull(LyricHighlightPrompt.parse("{\"start\": 1, \"end\": 1}", prepared))
        // Asking 2-3 crosses the blank line after line 2, leaving only 1 stub line -> rejected.
        assertNull(LyricHighlightPrompt.parse("{\"start\": 2, \"end\": 3}", prepared))
    }

    @Test
    fun should_reject_when_answerIsNotAValidRange() {
        val prepared = LyricHighlightPrompt.prepare(song)!!

        assertNull(LyricHighlightPrompt.parse(null, prepared))
        assertNull(LyricHighlightPrompt.parse("The best line is about the lighthouse.", prepared))
        assertNull(LyricHighlightPrompt.parse("{\"start\": 0, \"end\": 1}", prepared))
        assertNull(LyricHighlightPrompt.parse("{\"start\": 3, \"end\": 2}", prepared))
        assertNull(LyricHighlightPrompt.parse("{\"start\": 1, \"end\": 9}", prepared))
    }

    @Test
    fun should_skipEchoes_withinTheSameStanza() {
        val prepared = LyricHighlightPrompt.prepare(
            synced("我在升华 盛开着花", "盛开着花", "风吹过来带走所有遗憾", "夜色温柔"),
        )!!

        val pick = LyricHighlightPrompt.parse("{\"start\": 1, \"end\": 3}", prepared)!!
        assertEquals("我在升华 盛开着花\n风吹过来带走所有遗憾", pick.text)
    }

    @Test
    fun should_rejectInsteadOfTailChopping_when_pickExceedsCharLimit() {
        val long = "x".repeat(135)
        val prepared = LyricHighlightPrompt.prepare(synced("short one here", long, "three here", "four here"))!!

        // v5 rejects over-long picks rather than silently chopping the second line off.
        assertNull(LyricHighlightPrompt.parse("{\"start\": 1, \"end\": 2}", prepared))

        val tooLongSingle = LyricHighlightPrompt.prepare(synced("y".repeat(150), "two", "three", "four"))!!
        assertNull(LyricHighlightPrompt.parse("{\"start\": 1, \"end\": 1}", tooLongSingle))
    }

    @Test
    fun should_rejectPicksEndingMidClause() {
        val prepared = LyricHighlightPrompt.prepare(
            synced(
                "We drove past the harbor lights",
                "Waiting for the rain and",
                "如果明天太阳不再升起，",
                "我还是会记得你",
            ),
        )!!

        assertTrue(LyricHighlightPrompt.endsIncomplete("Waiting for the rain and"))
        assertTrue(LyricHighlightPrompt.endsIncomplete("如果明天太阳不再升起，"))
        assertTrue(LyricHighlightPrompt.endsIncomplete("你走之后因为"))
        assertFalse(LyricHighlightPrompt.endsIncomplete("我还是会记得你"))
        assertFalse(LyricHighlightPrompt.endsIncomplete("Time stands still in the quiet room."))

        assertNull(LyricHighlightPrompt.parse("{\"start\": 1, \"end\": 2}", prepared))
        assertNull(LyricHighlightPrompt.parse("{\"start\": 2, \"end\": 3}", prepared))
        assertEquals("如果明天太阳不再升起，\n我还是会记得你", LyricHighlightPrompt.parse("{\"start\": 3, \"end\": 4}", prepared)!!.text)
    }

    @Test
    fun should_useV5PromptVersionPrefix() {
        assertEquals("v5:", LyricHighlightPrompt.PROMPT_VERSION)
        assertTrue(LyricHighlightPrompt.prepare(song)!!.fingerprint.startsWith("v5:"))
    }

    @Test
    fun should_listEveryLine_withStanzaBreaks_when_buildingPrompt() {
        val prepared = LyricHighlightPrompt.prepare(song)!!
        val prompt = LyricHighlightPrompt.userPrompt("潮汐表", "森川", prepared)

        assertTrue(prompt.startsWith("Song: 潮汐表 — 森川\n"))
        assertTrue(prompt.contains("1. 我把你说过的晚安\n2. 折成纸船放回海里\n\n3. 潮水退了又涨\n"))
        assertTrue(prompt.contains("4. 灯塔还亮着\n\n5. 你在哪里寻找远方\n"))
    }
}
