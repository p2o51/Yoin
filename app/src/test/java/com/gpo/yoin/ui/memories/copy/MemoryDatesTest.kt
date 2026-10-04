package com.gpo.yoin.ui.memories.copy

import com.gpo.yoin.ui.memories.copy.CopyGolden.TODAY
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MemoryDatesTest {

    @Test
    fun should_drop_year_when_date_is_this_year() {
        assertEquals("Jul 26", MemoryDates.day(LocalDate.of(2026, 7, 26), TODAY))
        assertEquals("Nov 8, 2025", MemoryDates.day(LocalDate.of(2025, 11, 8), TODAY))
        assertEquals("7月26日", MemoryDates.dayZh(LocalDate.of(2026, 7, 26), TODAY))
        assertEquals("2025年11月8日", MemoryDates.dayZh(LocalDate.of(2025, 11, 8), TODAY))
        // diary entry headers always carry the year
        assertEquals("Jul 26, 2026 · Your review", MemoryDates.reviewHeader(LocalDate.of(2026, 7, 26)))
        assertEquals("Oct 4, 2026 · Today", MemoryDates.todayHeader(TODAY))
    }

    @Test
    fun should_never_use_liang_in_month_names() {
        MemoryDates.ZH_MONTHS.forEach { month -> assertFalse(month, month.contains('两')) }
        assertEquals("二月", MemoryDates.monthName(2, MemoryProseLanguage.ZH))
        assertEquals("十二月", MemoryDates.monthName(12, MemoryProseLanguage.ZH))
        val february = MemoryListening(plays = 2, firstHeard = LocalDate.of(2026, 2, 2), lastHeard = TODAY)
        assertEquals("二月", MemoryVoice.since(february, TODAY, MemoryProseLanguage.ZH))
        // counting still says 两
        assertEquals("两", MemoryVoice.num(2, MemoryProseLanguage.ZH))
        val golden = CopyGolden.load("copy-dates.json").list("zhMonths").map { it.toString().trim('"') }
        assertEquals(golden, MemoryDates.ZH_MONTHS)
    }

    @Test
    fun should_match_prototype_day_grammar() {
        CopyGolden.load("copy-dates.json").list("days").forEach { element ->
            val o = element.jsonObject
            val date = LocalDate.parse(o.text("iso"))
            assertEquals(o.text("day"), MemoryDates.day(date, TODAY))
            assertEquals(o.text("dayZh"), MemoryDates.dayZh(date, TODAY))
            assertEquals(o.text("dayY"), MemoryDates.dayWithYear(date))
        }
    }

    @Test
    fun should_match_prototype_footer_last_heard_and_headers() {
        CopyGolden.load("copy-dates.json").list("memories").forEach { element ->
            val o = element.jsonObject
            val input = CopyGolden.inputs.getValue(o.text("key"))
            val listening = input.listening!!
            assertEquals(o.text("lastHeard"), MemoryDates.lastHeard(listening.lastHeard, TODAY))
            val footer = MemoryDates.footer(listening.plays, listening.firstHeard, TODAY)
            val cells = o.list("footer").map { it.jsonObject }
            assertEquals(cells[0].text("caption"), footer.playsCaption)
            assertEquals(cells[0].integer("value"), footer.plays)
            assertEquals(cells[1].text("caption"), footer.daysCaption)
            assertEquals(cells[1].integer("value"), footer.days)
            val reviewHeader = input.reviewWrittenOn?.let(MemoryDates::reviewHeader)
            assertEquals(o.optText("reviewHeader"), reviewHeader)
            assertEquals(o.text("blankHeader"), MemoryDates.todayHeader(TODAY))
        }
    }

    @Test
    fun should_use_singular_captions_for_one() {
        val footer = MemoryDates.footer(plays = 1, firstHeard = TODAY.minusDays(1), today = TODAY)
        assertEquals("play", footer.playsCaption)
        assertEquals("day since Oct 3", footer.daysCaption)
        assertEquals(1, footer.days)
    }

    @Test
    fun should_read_local_date_in_injected_zone() {
        // 07:30 on Oct 4 in Shanghai is still 23:30 on Oct 3 in UTC
        val epochMs = ZonedDateTime.of(2026, 10, 4, 7, 30, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 10, 4), MemoryDates.localDate(epochMs, ZoneId.of("Asia/Shanghai")))
        assertEquals(LocalDate.of(2026, 10, 3), MemoryDates.localDate(epochMs, ZoneId.of("UTC")))
    }
}
