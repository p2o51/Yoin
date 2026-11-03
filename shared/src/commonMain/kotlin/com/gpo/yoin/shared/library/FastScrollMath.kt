package com.gpo.yoin.shared.library

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Library fast scroller's mappings, in lines (a grid row, a list item).
 * Shared by both apps: Android YoinFastScroller.kt, iOS LibraryFastScroller.swift.
 *
 * The bubble and the ticks are linear in the item index: the bubble names
 * the section of the last item of line `round(f × lastLine)`, and a
 * section's tick sits where that first becomes its own, `line(start) /
 * lastLine`. The list position follows the same line for as long as it
 * can — so dropping the handle on a tick puts that section's line at the
 * top (mid-row starts stop on their row, accepted by the owner) — and the
 * last stretch of the track, where the list can no longer bring a line to
 * the top, covers the final screen instead, so the handle reaches the
 * bottom exactly when the list does. The handle's resting place is the
 * same mapping run backwards, so grabbing it never jumps the list.
 *
 * Sections are given as parallel arrays: each section's first item
 * ([starts], ascending) and the label of its tick ([tickLabels]; adjacent
 * sections with the same tick label share one tick).
 */
object FastScrollMath {

    /** Content shorter than this many screens shows no scroller. */
    const val MIN_SCREENS = 3f

    fun lineCount(itemCount: Int, itemsPerLine: Int): Int {
        if (itemCount <= 0) return 0
        val perLine = itemsPerLine.coerceAtLeast(1)
        return (itemCount + perLine - 1) / perLine
    }

    fun isWorthShowing(itemCount: Int, itemsPerLine: Int, linesPerScreen: Float): Boolean =
        linesPerScreen > 0f && lineCount(itemCount, itemsPerLine) > MIN_SCREENS * linesPerScreen

    /** The handle fraction for a list resting at line [position]. */
    fun handleFraction(position: Float, lines: Int, linesPerScreen: Float): Float {
        val range = scrollRange(lines, linesPerScreen)
        if (range <= 0f) return 0f
        val p = position.coerceIn(0f, range)
        val last = (lines - 1).toFloat()
        val tail = last - range
        val knee = range - tail
        if (tail <= 0f || knee <= 0f) return p / range
        if (p <= knee) return p / last
        val kneeFraction = knee / last
        return kneeFraction + (p - knee) / tail * (1f - kneeFraction)
    }

    /** The list line (fractional) for handle fraction [fraction]: [handleFraction] inverted. */
    fun positionFor(fraction: Float, lines: Int, linesPerScreen: Float): Float {
        val range = scrollRange(lines, linesPerScreen)
        if (range <= 0f) return 0f
        val f = fraction.coerceIn(0f, 1f)
        val last = (lines - 1).toFloat()
        val tail = last - range
        val knee = range - tail
        if (tail <= 0f || knee <= 0f) return f * range
        val kneeFraction = knee / last
        if (f <= kneeFraction) return f * last
        return knee + (f - kneeFraction) / (1f - kneeFraction) * tail
    }

    /**
     * The item to jump to for [fraction]: the first item of its line. The
     * list's end is seldom a whole line, so past the last line the list can
     * still bring to the top the jump aims a line further, which the lazy
     * layout clamps to its true end: rounded back instead, the last part of a
     * row stayed under whatever covers the list's bottom (a floating bar),
     * with the handle already at the bottom of its track.
     */
    fun jumpIndex(fraction: Float, itemCount: Int, itemsPerLine: Int, linesPerScreen: Float): Int {
        if (itemCount <= 0) return 0
        val perLine = itemsPerLine.coerceAtLeast(1)
        val lines = lineCount(itemCount, perLine)
        val position = positionFor(fraction, lines, linesPerScreen)
        val lastTopLine = floor(scrollRange(lines, linesPerScreen))
        val line = if (position > lastTopLine) ceil(position).toInt() else position.roundToInt()
        return (line * perLine).coerceIn(0, itemCount - 1)
    }

    /** The item whose section the bubble names at [fraction]. */
    fun labelIndex(fraction: Float, itemCount: Int, itemsPerLine: Int): Int {
        if (itemCount <= 0) return 0
        val perLine = itemsPerLine.coerceAtLeast(1)
        val last = lineCount(itemCount, perLine) - 1
        val line = (fraction.coerceIn(0f, 1f) * last).roundToInt().coerceIn(0, last)
        return min(itemCount - 1, line * perLine + perLine - 1)
    }

    fun tickFraction(startIndex: Int, itemCount: Int, itemsPerLine: Int): Float {
        val perLine = itemsPerLine.coerceAtLeast(1)
        val last = lineCount(itemCount, perLine) - 1
        if (last <= 0) return 0f
        return ((startIndex / perLine).toFloat() / last).coerceIn(0f, 1f)
    }

    /** The section holding [index] (sections by ascending [starts]), or -1. */
    fun sectionAt(starts: IntArray, index: Int): Int {
        var low = 0
        var high = starts.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= index) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /**
     * The sections that carry a tick (indices into [starts]): one per run of
     * sections sharing a tick label, and one per line at most — when several
     * runs start on the same line, the last one keeps the tick. The bubble
     * names a line's last item, so of the runs starting on a line only the
     * last can ever be named there; a tick for an earlier one would light up
     * beside a bubble naming another.
     */
    fun tickSections(starts: IntArray, tickLabels: List<String>, itemsPerLine: Int = 1): IntArray {
        val perLine = itemsPerLine.coerceAtLeast(1)
        val ticks = ArrayList<Int>()
        for (i in starts.indices) {
            if (i > 0 && tickLabels[i - 1] == tickLabels[i]) continue
            val last = ticks.lastOrNull()
            if (last != null && starts[last] / perLine == starts[i] / perLine) {
                ticks[ticks.lastIndex] = i
            } else {
                ticks += i
            }
        }
        return ticks.toIntArray()
    }

    /**
     * For each section, the index of its run of sections sharing a tick
     * label. The handle passing from one run into the next is what plays the
     * segment tick: a letter, a year; a timeline's months under one year
     * only change the bubble's words.
     */
    fun tickRuns(tickLabels: List<String>): IntArray {
        val runs = IntArray(tickLabels.size)
        var run = -1
        for (i in tickLabels.indices) {
            if (i == 0 || tickLabels[i - 1] != tickLabels[i]) run++
            runs[i] = run
        }
        return runs
    }

    /**
     * Where each tick's centre sits on a track [extent] long whose first
     * tick sits [offset] down: the place the handle's centre has when the
     * bubble first names that tick's run.
     */
    fun tickCenters(
        tickStarts: IntArray,
        itemCount: Int,
        itemsPerLine: Int,
        extent: Float,
        offset: Float,
    ): FloatArray = FloatArray(tickStarts.size) {
        tickFraction(tickStarts[it], itemCount, itemsPerLine) * extent + offset
    }

    /**
     * Where each tick's label is drawn, given the ticks' centres (ascending)
     * and the least gap between two labels; NaN for a tick left unlabelled.
     *
     * Top-down, a label crowded by the one above moves down to the gap — at
     * most [minGap] below its own centre, and never past the next tick's
     * centre, so it stays on its own run's stretch of the track and dropping
     * the handle on it still lands in that run. With no room left there it
     * goes unlabelled. So a run that spans [minGap] of the track keeps its
     * label whatever crowds it from above — a long run never loses it to a
     * short neighbour (a year of 350 albums to one of 20) — except where the
     * last label needs the room: both ends keep their own places, the first
     * label never moves and the ones between leave the last its gap.
     */
    fun placeTicks(centers: FloatArray, minGap: Float): FloatArray {
        val places = FloatArray(centers.size) { Float.NaN }
        if (centers.isEmpty()) return places
        places[0] = centers[0]
        val end = centers.lastIndex
        if (end == 0) return places
        val lowest = centers[end] - minGap
        var above = centers[0]
        for (i in 1 until end) {
            val y = max(centers[i], above + minGap)
            if (y < centers[i + 1] && y <= lowest) {
                places[i] = y
                above = y
            }
        }
        places[end] = centers[end]
        return places
    }

    /**
     * The lines one screen of a lazy layout holds, as far as the scroll range
     * goes: the viewport inside its content padding plus the spacing the
     * last line has none of after it. So `lines - linesPerScreen` is exactly
     * how far the list scrolls, and the handle meets the bottom of its track
     * exactly when the list meets its end.
     */
    fun screenLines(viewport: Int, beforePadding: Int, afterPadding: Int, spacing: Int, line: Float): Float {
        val inner = viewport - beforePadding - afterPadding
        if (inner <= 0 || line <= 0f) return 0f
        return (inner + spacing) / line
    }

    fun scrollRange(lines: Int, linesPerScreen: Float): Float = (lines - linesPerScreen).coerceAtLeast(0f)
}
