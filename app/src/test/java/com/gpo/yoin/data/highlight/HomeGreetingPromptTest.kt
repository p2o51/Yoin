package com.gpo.yoin.data.highlight

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGreetingPromptTest {

    @Test
    fun should_parseValidChineseThreeSlotJson() {
        val raw = """
            ```json
            {"morning": "早安，先听一首", "afternoon": "午后放慢一点。", "evening": "今晚留给音乐！"}
            ```
        """.trimIndent()

        val parsed = HomeGreetingPrompt.parse(raw, day = 20_000L, localeTag = "zh-CN", profileId = "p1")

        assertNotNull(parsed)
        assertEquals("zh", parsed!!.localeTag)
        assertEquals("早安，先听一首", parsed.morning)
        assertEquals("午后放慢一点", parsed.afternoon)
        assertEquals("今晚留给音乐", parsed.evening)
    }

    @Test
    fun should_parseValidEnglishThreeSlotJson() {
        val raw = """{"morning":"Easy morning spin","afternoon":"Slow afternoon listening","evening":"Quiet evening ahead."}"""

        val parsed = HomeGreetingPrompt.parse(raw, day = 20_000L, localeTag = "en-US", profileId = "p1")

        assertNotNull(parsed)
        assertEquals("en", parsed!!.localeTag)
        assertEquals("Easy morning spin", parsed.morning)
        assertEquals("Slow afternoon listening", parsed.afternoon)
        assertEquals("Quiet evening ahead", parsed.evening)
    }

    @Test
    fun should_enforceHardLengthCaps_andRejectOverflowingSlots() {
        // Morning exceeds 10 Chinese chars -> dropped; afternoon & evening valid -> partial set kept (2 >= 2).
        val raw = """{"morning":"今天早上阳光特别好适合慢慢听完一整张专辑","afternoon":"午后听点轻的","evening":"晚安，放一首歌"}"""

        val parsed = HomeGreetingPrompt.parse(raw, day = 20_000L, localeTag = "zh", profileId = "p1")

        assertNotNull(parsed)
        assertNull(parsed!!.morning)
        assertEquals("午后听点轻的", parsed.afternoon)
        assertEquals("晚安，放一首歌", parsed.evening)

        // English > 4 words or > 26 chars rejected.
        assertNull(HomeGreetingPrompt.sanitize("Hope you have a really wonderful morning", "en"))
        assertNull(HomeGreetingPrompt.sanitize("Extraordinarily peaceful morning", "en"))
    }

    @Test
    fun should_rejectEmoji_scriptMismatch_andMetaChatter() {
        assertNull(HomeGreetingPrompt.sanitize("早安 ☀️", "zh"))
        assertNull(HomeGreetingPrompt.sanitize("Good morning", "zh"))
        assertNull(HomeGreetingPrompt.sanitize("早上好", "en"))
        assertNull(HomeGreetingPrompt.sanitize("Morning: good day", "en"))
        assertNull(HomeGreetingPrompt.sanitize("好的，早上好", "zh"))
        assertNull(HomeGreetingPrompt.sanitize("line1\nline2", "en"))

        // Fewer than 2 valid slots -> whole answer dropped.
        assertNull(
            HomeGreetingPrompt.parse(
                """{"morning":"早安","afternoon":"Good afternoon","evening":"Too long 超过十个字的一句话问候"}""",
                day = 20_000L,
                localeTag = "zh",
                profileId = "p1",
            ),
        )
    }

    @Test
    fun should_includeDayAndAtMostTwoArtistsInPrompt() {
        val prompt = HomeGreetingPrompt.buildPrompt(
            recentArtists = listOf("卢广仲", "陈绮贞", "卢广仲", "方大同"),
            dayOfWeek = DayOfWeek.SUNDAY,
            localeTag = "zh-CN",
        )
        assertTrue(prompt.contains("周日"))
        assertTrue(prompt.contains("卢广仲、陈绮贞"))
        assertTrue(!prompt.contains("方大同"))
    }
}
