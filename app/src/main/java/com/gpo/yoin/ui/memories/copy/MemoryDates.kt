package com.gpo.yoin.ui.memories.copy

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * One date grammar for the whole Memories page, ported from the approved
 * prototype (twostate4.html: `day`, `dayZh`, `dayY`, `stats`, `entryH`):
 * - this year's dates drop the year ("Jul 26", "7月26日");
 * - any other year keeps it ("Nov 8, 2025", "2025年11月8日");
 * - diary entry headers always carry the year, like a diary page.
 *
 * "Today" is always passed in (from the coordinator's injected clock and
 * zone); nothing here reads the system clock or the default locale. Month
 * names are fixed English and Chinese tables, not DateTimeFormatter output.
 */
object MemoryDates {
    private val MONTH_SHORT = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
    private val MONTH_EN = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    /** Chinese month names. Months never take 两: 二月, never 两月 (两 is for counting only). */
    val ZH_MONTHS: List<String> = listOf(
        "一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月",
    )

    fun localDate(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /** Whole calendar days from [from] to [to]; negative when [to] is earlier. */
    fun daysBetween(from: LocalDate, to: LocalDate): Int = ChronoUnit.DAYS.between(from, to).toInt()

    /** "Jul 26" this year, "Nov 8, 2025" in any other year. */
    fun day(date: LocalDate, today: LocalDate): String {
        val base = "${MONTH_SHORT[date.monthValue - 1]} ${date.dayOfMonth}"
        return if (date.year != today.year) "$base, ${date.year}" else base
    }

    /** "7月26日" this year, "2025年11月8日" in any other year (the same year rule as [day]). */
    fun dayZh(date: LocalDate, today: LocalDate): String {
        val year = if (date.year != today.year) "${date.year}年" else ""
        return "$year${date.monthValue}月${date.dayOfMonth}日"
    }

    /** Always with the year: "Nov 8, 2025". Diary entry headers use it. */
    fun dayWithYear(date: LocalDate): String = "${MONTH_SHORT[date.monthValue - 1]} ${date.dayOfMonth}, ${date.year}"

    /** Full month name: "February" / "二月". */
    fun monthName(month: Int, language: MemoryProseLanguage): String = when (language) {
        MemoryProseLanguage.ZH -> ZH_MONTHS[month - 1]
        MemoryProseLanguage.EN -> MONTH_EN[month - 1]
    }

    /** A diary entry header: "Jul 26, 2026 · Your review". The date heads the entry. */
    fun entryHeader(date: LocalDate, label: String): String = "${dayWithYear(date)} · $label"

    fun reviewHeader(writtenOn: LocalDate): String = entryHeader(writtenOn, "Your review")

    /** Today's blank diary page. */
    fun todayHeader(today: LocalDate): String = entryHeader(today, "Today")

    /** The top bar's slot A, under "Memories". Only shown for an album played in Yoin. */
    fun lastHeard(lastHeard: LocalDate, today: LocalDate): String = "Last heard ${day(lastHeard, today)}"

    /**
     * The diary footer's two numerals: how many times you played it, and how
     * many days it has been with you (first play to today). Only for an album
     * played in Yoin; a never-played album has no footer at all.
     */
    fun footer(plays: Int, firstHeard: LocalDate, today: LocalDate): MemoryFooterStats {
        val days = daysBetween(firstHeard, today)
        return MemoryFooterStats(
            plays = plays,
            playsCaption = if (plays == 1) "play" else "plays",
            days = days,
            daysCaption = "${if (days == 1) "day" else "days"} since ${day(firstHeard, today)}",
        )
    }
}

/** The diary footer: two numerals, each with a one-line caption (UI strings, English). */
data class MemoryFooterStats(
    val plays: Int,
    val playsCaption: String,
    val days: Int,
    val daysCaption: String,
)
