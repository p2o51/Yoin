package com.gpo.yoin.data.memory

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/*
 * The Memories grid (owner 2026-11-08, "B3"): a calendar wall whose cells grow when you write less, so a
 * listener who writes once a month doesn't stare at an empty wall. How many days you wrote or scored on in
 * the last 17 weeks picks the scale:
 *  - fewer than 10: one cell a month, each a 2×2 of the month's top four albums;
 *  - 10 to 29: one cell a week, the last 26 weeks;
 *  - 30 or more: one cell a day, the last 17 weeks (a column a week).
 * A cell shows up to two albums (a week or day; spliced on the diagonal), or four (a month). An album you
 * wrote about ranks ahead of one you only scored, then the one with more marks.
 */

enum class MemoryGridScale { Month, Week, Day }

/** One album in a cell: whether anything was written about it there, and how many marks it got. */
data class MemoryGridMark(
    val album: MemoryLedgerAlbum,
    val wrote: Boolean,
    val count: Int,
)

data class MemoryGridCell(
    /** First day the cell covers. */
    val start: LocalDate,
    /** Last day the cell covers (inclusive). */
    val end: LocalDate,
    /** Ranked, at most [MemoryGrid.capacity] long; empty when nothing was kept then. */
    val marks: List<MemoryGridMark>,
    /** Writings (not scores) in the cell. */
    val writings: Int,
    /** Every entry in the cell, scores included. */
    val entries: Int,
    /** A day or week that hasn't come yet (the tail of the current week). */
    val future: Boolean,
) {
    val isEmpty: Boolean get() = entries == 0
}

data class MemoryGrid(
    val scale: MemoryGridScale,
    /** Oldest first. */
    val cells: List<MemoryGridCell>,
) {
    /** Albums one cell can show. */
    val capacity: Int get() = capacityOf(scale)

    companion object {
        /** Days looked back on to choose the scale: 17 weeks, about four months. */
        const val LOOKBACK_DAYS = 119
        const val WEEK_THRESHOLD = 10
        const val DAY_THRESHOLD = 30
        const val WEEKS = 26
        const val DAY_WEEKS = 17
        const val MIN_MONTHS = 6
        const val MAX_MONTHS = 12

        fun capacityOf(scale: MemoryGridScale): Int = if (scale == MemoryGridScale.Month) 4 else 2

        fun scaleFor(activeDays: Int): MemoryGridScale = when {
            activeDays < WEEK_THRESHOLD -> MemoryGridScale.Month
            activeDays < DAY_THRESHOLD -> MemoryGridScale.Week
            else -> MemoryGridScale.Day
        }
    }
}

/**
 * Lays [entries] out on the grid as of [today]. Weeks start on [firstDayOfWeek] (the locale's). An entry
 * without an album still counts toward its cell and the scale, but colours nothing.
 */
fun buildMemoryGrid(
    entries: List<MemoryLedgerEntry>,
    today: LocalDate,
    zone: ZoneId,
    firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    scale: MemoryGridScale? = null,
): MemoryGrid {
    val dated = entries.map { it.localDate(zone) to it }
    val lookbackStart = today.minusDays((MemoryGrid.LOOKBACK_DAYS - 1).toLong())
    val activeDays = dated.mapNotNullTo(HashSet()) { (day, _) -> day.takeIf { it in lookbackStart..today } }.size
    val chosen = scale ?: MemoryGrid.scaleFor(activeDays)
    val ranges: List<ClosedRange<LocalDate>> = when (chosen) {
        MemoryGridScale.Month -> {
            val now = YearMonth.from(today)
            val oldest = dated.minOfOrNull { (day, _) -> day }?.let(YearMonth::from) ?: now
            val back = (oldest.until(now, java.time.temporal.ChronoUnit.MONTHS).toInt() + 1)
                .coerceIn(MemoryGrid.MIN_MONTHS, MemoryGrid.MAX_MONTHS)
            (back - 1 downTo 0).map { ago -> now.minusMonths(ago.toLong()).let { it.atDay(1)..it.atEndOfMonth() } }
        }
        MemoryGridScale.Week, MemoryGridScale.Day -> {
            val weeks = if (chosen == MemoryGridScale.Week) MemoryGrid.WEEKS else MemoryGrid.DAY_WEEKS
            val thisWeek = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            val first = thisWeek.minusWeeks((weeks - 1).toLong())
            if (chosen == MemoryGridScale.Week) {
                (0 until weeks).map { w -> first.plusWeeks(w.toLong()).let { it..it.plusDays(6) } }
            } else {
                (0 until weeks * 7).map { d -> first.plusDays(d.toLong()).let { it..it } }
            }
        }
    }
    val capacity = MemoryGrid.capacityOf(chosen)
    val cells = ranges.map { range ->
        val inCell = dated.filter { (day, _) -> day in range }.map { it.second }
        MemoryGridCell(
            start = range.start,
            end = range.endInclusive,
            marks = rankMarks(inCell, capacity),
            writings = inCell.count { it.kind.wrote && it.kind != MemoryLedgerKind.Title },
            entries = inCell.size,
            future = range.start > today,
        )
    }
    return MemoryGrid(chosen, cells)
}

internal fun rankMarks(entries: List<MemoryLedgerEntry>, capacity: Int): List<MemoryGridMark> {
    data class Tally(val album: MemoryLedgerAlbum, var wrote: Boolean, var count: Int, var latest: Long)
    val tallies = LinkedHashMap<String, Tally>()
    entries.forEach { entry ->
        val album = entry.album ?: return@forEach
        val tally = tallies.getOrPut(album.key) { Tally(album, false, 0, entry.at) }
        tally.wrote = tally.wrote || entry.kind.wrote
        tally.count++
        tally.latest = maxOf(tally.latest, entry.at)
    }
    return tallies.values
        .sortedWith(
            compareByDescending<Tally> { it.wrote }
                .thenByDescending { it.count }
                .thenByDescending { it.latest }
                .thenBy { it.album.key },
        )
        .take(capacity)
        .map { MemoryGridMark(it.album, it.wrote, it.count) }
}

fun MemoryLedgerEntry.localDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
