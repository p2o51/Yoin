package com.gpo.yoin.data.highlight

import com.gpo.yoin.shared.ondevice.HomeGreetingPrompt
import java.time.DayOfWeek
import java.time.Month
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGreetingPromptTest {

    @Test
    fun should_parseValidChineseThreeSlotJson() {
        val raw = """
            ```json
            {"morning": "早安，先听一首", "afternoon": "这周常听 Radiohead 的《In Rainbows》", "evening": "今晚留给音乐！"}
            ```
        """.trimIndent()

        val parsed = HomeGreetingPrompt.parse(raw, day = 20_000L, localeTag = "zh-CN", profileId = "p1")

        assertNotNull(parsed)
        assertEquals("zh", parsed!!.localeTag)
        assertEquals("早安，先听一首", parsed.morning)
        assertEquals("这周常听 Radiohead 的《In Rainbows》", parsed.afternoon)
        assertEquals("今晚留给音乐", parsed.evening)
    }

    @Test
    fun should_parseValidEnglishThreeSlotJson() {
        val raw = """{"morning":"Still spinning Blonde","afternoon":"In Rainbows is on repeat","evening":"Back to Love Deluxe tonight."}"""

        val parsed = HomeGreetingPrompt.parse(raw, day = 20_000L, localeTag = "en-US", profileId = "p1")

        assertNotNull(parsed)
        assertEquals("en", parsed!!.localeTag)
        assertEquals("Still spinning Blonde", parsed.morning)
        assertEquals("In Rainbows is on repeat", parsed.afternoon)
        assertEquals("Back to Love Deluxe tonight", parsed.evening)
    }

    @Test
    fun should_useVisualWidthForChinese_allowingEnglishBandNames_andRejectingOverflow() {
        // Mixed ZH + English band/album fits comfortably within visual width 38.
        assertEquals(
            "刚听完 Frank Ocean 的《Blonde》",
            HomeGreetingPrompt.sanitize("刚听完 Frank Ocean 的《Blonde》", "zh"),
        )
        // Oversized ZH (> 38 visual width) is rejected while 2 valid slots survive.
        val raw = """{"morning":"今天早上阳光特别好适合坐在窗边慢慢听完一整张黑胶唱片","afternoon":"午间放一张喜欢的唱片","evening":"晚安，放一首歌"}"""
        val parsed = HomeGreetingPrompt.parse(raw, day = 20_000L, localeTag = "zh", profileId = "p1")
        assertNotNull(parsed)
        assertNull(parsed!!.morning)
        assertEquals("午间放一张喜欢的唱片", parsed.afternoon)
        assertEquals("晚安，放一首歌", parsed.evening)

        // English > 8 words or > 44 chars rejected.
        assertNull(HomeGreetingPrompt.sanitize("Hope you have a really wonderfully calm and peaceful morning listening session", "en"))
    }

    @Test
    fun should_rejectPrescriptiveCliches_emoji_scriptMismatch_andExampleBleed() {
        assertNull(HomeGreetingPrompt.sanitize("晚上听点舒缓的", "zh"))
        assertNull(HomeGreetingPrompt.sanitize("周六早，放松一下", "zh"))
        assertNull(HomeGreetingPrompt.sanitize("无特定曲目", "zh"))
        assertNull(HomeGreetingPrompt.sanitize("Love Deluxe is missing", "en"))
        assertNull(HomeGreetingPrompt.sanitize("早安 ☀️", "zh"))
        assertNull(HomeGreetingPrompt.sanitize("Good morning", "zh"))
        assertNull(HomeGreetingPrompt.sanitize("早上好", "en"))
        assertNull(HomeGreetingPrompt.sanitize("Morning: good day", "en"))
        assertNull(HomeGreetingPrompt.sanitize("好的，早上好", "zh"))
        assertNull(HomeGreetingPrompt.sanitize("line1\nline2", "en"))

        // Example artist ("Bill Evans") NOT in user cues -> blocked as example bleed.
        val userCues = HomeGreetingPrompt.SlotCues(
            morning = HomeGreetingPrompt.RecentPlay("宇多田光", "First Love"),
            afternoon = HomeGreetingPrompt.RecentPlay("Radiohead", "In Rainbows"),
            evening = HomeGreetingPrompt.RecentPlay("草东没有派对", "丑奴儿"),
        )
        val bled = HomeGreetingPrompt.validateSlots(
            morning = "刚听完宇多田光《First Love》",
            afternoon = "这周常听 Radiohead",
            evening = "好久没放 Bill Evans 了",
            day = 20_000L,
            localeTag = "zh-CN",
            profileId = "p1",
            cues = userCues,
        )
        assertNotNull(bled)
        assertNull(bled!!.evening)

        // Same line IS allowed when the user actually has Bill Evans in their cues.
        val billCues = userCues.copy(evening = HomeGreetingPrompt.RecentPlay("Bill Evans", "Waltz for Debby"))
        val legit = HomeGreetingPrompt.validateSlots(
            morning = "刚听完宇多田光《First Love》",
            afternoon = "这周常听 Radiohead",
            evening = "好久没放 Bill Evans 了",
            day = 20_000L,
            localeTag = "zh-CN",
            profileId = "p1",
            cues = billCues,
        )
        assertEquals("好久没放 Bill Evans 了", legit!!.evening)
    }

    @Test
    fun should_buildDistinctSlotPrompt_omittingMissingSlots_andDetectNoonConflicts() {
        val prompt = HomeGreetingPrompt.userPrompt(
            cues = HomeGreetingPrompt.SlotCues(
                morning = HomeGreetingPrompt.RecentPlay("宇多田光", "First Love"),
                afternoon = null,
                evening = HomeGreetingPrompt.RecentPlay("草东没有派对", "丑奴儿"),
            ),
            dayOfWeek = DayOfWeek.FRIDAY,
            month = Month.NOVEMBER,
            localeTag = "zh-CN",
        )
        assertTrue(prompt.contains("十一月 · 周五"))
        assertTrue(prompt.contains("早·刚听过：宇多田光《First Love》"))
        assertFalse(prompt.contains("午·"))
        assertTrue(prompt.contains("晚·好久没放：草东没有派对《丑奴儿》"))

        assertTrue(HomeGreetingPrompt.conflictsWithNoon("下午接着听草东"))
        assertTrue(HomeGreetingPrompt.conflictsWithNoon("Quiet afternoon spin"))
        assertFalse(HomeGreetingPrompt.conflictsWithNoon("这周常听 Radiohead"))
    }

    @Test
    fun should_filterSevenDayRepeats() {
        val validated = HomeGreetingPrompt.validateSlots(
            morning = "刚听完《First Love》",
            afternoon = "这周常听 Radiohead",
            evening = "好久没放草东了",
            day = 20_000L,
            localeTag = "zh-CN",
            profileId = "p1",
            avoidRecent = listOf("刚听完《First Love》"),
        )
        assertNotNull(validated)
        assertNull(validated!!.morning)
        assertEquals("这周常听 Radiohead", validated.afternoon)
        assertEquals("好久没放草东了", validated.evening)
    }
}
