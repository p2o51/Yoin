/*
 * TEST-ONLY REFERENCE: the Android implementation as it was before it moved to :shared
 * (git show 69bfd77a0:<path>), kept verbatim apart from the package and visibility so the parity
 * tests can prove the shared code returns the same values.
 */
package com.gpo.yoin.shared.reference

import com.gpo.yoin.ui.component.FastScrollSection
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class RefFastScrollTick(val label: String, val startIndex: Int)

object RefFastScrollMath {

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

    /** The section holding [index] (sections in list order), or -1. */
    fun sectionAt(sections: List<FastScrollSection>, index: Int): Int {
        var low = 0
        var high = sections.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (sections[mid].startIndex <= index) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /**
     * One tick per run of sections sharing a tick label, and one per line at
     * most: when several runs start on the same line, the last one keeps the
     * tick. The bubble names a line's last item, so of the runs starting on
     * a line only the last can ever be named there; a tick for an earlier
     * one would light up beside a bubble naming another.
     */
    fun ticks(sections: List<FastScrollSection>, itemsPerLine: Int = 1): List<RefFastScrollTick> {
        val perLine = itemsPerLine.coerceAtLeast(1)
        val ticks = ArrayList<RefFastScrollTick>()
        sections.forEachIndexed { i, section ->
            if (i > 0 && sections[i - 1].tickLabel == section.tickLabel) return@forEachIndexed
            val tick = RefFastScrollTick(section.tickLabel, section.startIndex)
            val last = ticks.lastOrNull()
            if (last != null && last.startIndex / perLine == section.startIndex / perLine) {
                ticks[ticks.lastIndex] = tick
            } else {
                ticks += tick
            }
        }
        return ticks
    }

    /**
     * For each section, the index of its run of sections sharing a tick
     * label. The handle passing from one run into the next is what plays the
     * segment tick: a letter, a year; a timeline's months under one year
     * only change the bubble's words.
     */
    fun tickRuns(sections: List<FastScrollSection>): IntArray {
        val runs = IntArray(sections.size)
        var run = -1
        sections.forEachIndexed { i, section ->
            if (i == 0 || sections[i - 1].tickLabel != section.tickLabel) run++
            runs[i] = run
        }
        return runs
    }

    /**
     * Where each tick's centre sits on a track [extentPx] long whose first
     * tick sits [offsetPx] down: the place the handle's centre has when the
     * bubble first names that tick's run.
     */
    fun tickCenters(
        ticks: List<RefFastScrollTick>,
        itemCount: Int,
        itemsPerLine: Int,
        extentPx: Float,
        offsetPx: Float
    ): FloatArray = FloatArray(ticks.size) {
        tickFraction(ticks[it].startIndex, itemCount, itemsPerLine) * extentPx + offsetPx
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
    fun screenLines(viewportPx: Int, beforePaddingPx: Int, afterPaddingPx: Int, spacingPx: Int, linePx: Float): Float {
        val inner = viewportPx - beforePaddingPx - afterPaddingPx
        if (inner <= 0 || linePx <= 0f) return 0f
        return (inner + spacingPx) / linePx
    }

    private fun scrollRange(lines: Int, linesPerScreen: Float): Float = (lines - linesPerScreen).coerceAtLeast(0f)
}
