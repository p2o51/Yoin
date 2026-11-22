package com.gpo.yoin.data.highlight

import java.time.DayOfWeek
import java.time.Month
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
        // Morning exceeds 12 Chinese chars -> dropped; afternoon (12 chars) & evening valid -> partial set kept (2 >= 2).
        val raw = """{"morning":"今天早上阳光特别好适合慢慢听完一整张专辑","afternoon":"下午可以继续听草东的曲子","evening":"晚安，放一首歌"}"""

        val parsed = HomeGreetingPrompt.parse(raw, day = 20_000L, localeTag = "zh", profileId = "p1")

        assertNotNull(parsed)
        assertNull(parsed!!.morning)
        assertEquals("下午可以继续听草东的曲子", parsed.afternoon)
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
                """{"morning":"早安","afternoon":"Good afternoon","evening":"Too long 超过十二个字的一句话问候语"}""",
                day = 20_000L,
                localeTag = "zh",
                profileId = "p1",
            ),
        )
    }

    @Test
    fun should_includeMonthDayAndAtMostTwoRecentPlaysInPrompt() {
        val prompt = HomeGreetingPrompt.userPrompt(
            recentPlays = listOf(
                HomeGreetingPrompt.RecentPlay("宇多田光", "First Love"),
                HomeGreetingPrompt.RecentPlay("草东没有派对", "丑奴儿"),
                HomeGreetingPrompt.RecentPlay("宇多田光", "Distance"),
                HomeGreetingPrompt.RecentPlay("方大同", "橙月"),
            ),
            dayOfWeek = DayOfWeek.FRIDAY,
            month = Month.NOVEMBER,
            localeTag = "zh-CN",
        )
        assertEquals("十一月 · 周五 | 最近播过：宇多田光《First Love》、草东没有派对《丑奴儿》", prompt)

        val fallback = HomeGreetingPrompt.buildPrompt(
            recentArtists = listOf("卢广仲", "陈绮贞", "卢广仲", "方大同"),
            dayOfWeek = DayOfWeek.SUNDAY,
            localeTag = "zh-CN",
            month = Month.NOVEMBER,
        )
        assertTrue(fallback.contains("十一月 · 周日"))
        assertTrue(fallback.contains("卢广仲、陈绮贞"))
        assertTrue(!fallback.contains("方大同"))
    }

    @Test
    fun should_validateStructuredSlotsDirectly() {
        val validated = HomeGreetingPrompt.validateSlots(
            morning = "周五早，听听宇多田光",
            afternoon = "午后可以再听点怀旧的",
            evening = "晚上放松，听点舒服的。",
            day = 20_000L,
            localeTag = "zh-CN",
            profileId = "p1",
        )
        assertNotNull(validated)
        assertEquals("周五早，听听宇多田光", validated!!.morning)
        assertEquals("午后可以再听点怀旧的", validated.afternoon)
        assertEquals("晚上放松，听点舒服的", validated.evening)
    }
}
