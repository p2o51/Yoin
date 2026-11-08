package com.gpo.yoin.data.memory

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/*
 * The All Memories list: entries grouped by month, newest first. Months with nothing in them fold into one
 * quiet line ("July to August, nothing written") so a sparse year doesn't scroll forever, and a month's
 * scores fold into one line under its writing (scores are many and short; the writing is what you came
 * back for). Showing only scores lists them one by one instead.
 */

enum class MemoryLedgerFilter { All, Writing, Scores }

sealed interface MemoryLedgerRow {
    val key: String

    data class Month(val month: YearMonth, val writings: Int, val scores: Int) : MemoryLedgerRow {
        override val key: String get() = "month:$month"
    }

    data class Entry(val entry: MemoryLedgerEntry) : MemoryLedgerRow {
        override val key: String get() = "entry:${entry.id}"
    }

    /** A month's scores, folded: [scores] marks across [albums] albums. */
    data class Scores(val month: YearMonth, val scores: Int, val albums: Int) : MemoryLedgerRow {
        override val key: String get() = "scores:$month"
    }

    /** Empty months, newest first: [from] is the later month, [to] the earlier (the same for one month). */
    data class Quiet(val from: YearMonth, val to: YearMonth) : MemoryLedgerRow {
        override val key: String get() = "quiet:$from:$to"
    }
}

fun MemoryLedgerEntry.matches(filter: MemoryLedgerFilter): Boolean = when (filter) {
    MemoryLedgerFilter.All -> true
    MemoryLedgerFilter.Writing -> kind.wrote
    MemoryLedgerFilter.Scores -> !kind.wrote
}

/**
 * Rows for [entries] (newest first), from [today]'s month back to the oldest entry's. With [foldScores]
 * a month's scores become one [MemoryLedgerRow.Scores] line after its writing.
 */
fun memoryLedgerRows(
    entries: List<MemoryLedgerEntry>,
    today: LocalDate,
    zone: ZoneId,
    foldScores: Boolean = true,
): List<MemoryLedgerRow> {
    if (entries.isEmpty()) return emptyList()
    val byMonth = entries.groupBy { YearMonth.from(it.localDate(zone)) }
    val newest = maxOf(YearMonth.from(today), byMonth.keys.max())
    val oldest = byMonth.keys.min()
    val rows = ArrayList<MemoryLedgerRow>()
    var quietFrom: YearMonth? = null
    var quietTo: YearMonth? = null
    fun flushQuiet() {
        val from = quietFrom ?: return
        rows += MemoryLedgerRow.Quiet(from, quietTo ?: from)
        quietFrom = null
        quietTo = null
    }
    var month = newest
    while (month >= oldest) {
        val inMonth = byMonth[month].orEmpty()
            .sortedWith(compareByDescending<MemoryLedgerEntry> { it.at }.thenBy { it.id })
        if (inMonth.isEmpty()) {
            if (quietFrom == null) quietFrom = month
            quietTo = month
        } else {
            flushQuiet()
            val writing = inMonth.filter { it.kind.wrote }
            val scores = inMonth.filterNot { it.kind.wrote }
            rows += MemoryLedgerRow.Month(month, writings = writing.size, scores = scores.size)
            if (foldScores && writing.isNotEmpty()) {
                writing.forEach { rows += MemoryLedgerRow.Entry(it) }
                if (scores.isNotEmpty()) {
                    rows += MemoryLedgerRow.Scores(
                        month = month,
                        scores = scores.size,
                        albums = scores.mapNotNullTo(HashSet()) { it.album?.key }.size,
                    )
                }
            } else {
                // A month with only scores (or a scores-only view) shows them one by one: folding them
                // would leave the month with nothing but a count.
                inMonth.forEach { rows += MemoryLedgerRow.Entry(it) }
            }
        }
        month = month.minusMonths(1)
    }
    flushQuiet()
    return rows
}
