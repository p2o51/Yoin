package com.gpo.yoin.ui.nowplaying

import com.gpo.yoin.player.PlayMode

/**
 * Notices taps on the round play-mode button that go all the way round its
 * cycle ([PlayMode.next]) and back to where they started: four quick taps,
 * each within [maxGapMs] of the last. Someone stepping through every mode is looking for
 * one the cycle doesn't hold, so the button splits once to show its two
 * axes (owner 2026-10-21, Gemini review 1.4).
 */
internal class PlayModeCycle(private val maxGapMs: Long = PlayModeCycleGapMs) {
    private var start: PlayMode? = null
    private var lastTapMs = Long.MIN_VALUE

    /** A tap moving the button from [from] to [to] at [nowMs]; true when it closes a full cycle. */
    fun onTap(from: PlayMode, to: PlayMode, nowMs: Long): Boolean {
        val quick = lastTapMs != Long.MIN_VALUE && nowMs - lastTapMs <= maxGapMs
        lastTapMs = nowMs
        if (!quick || start == null) start = from
        // The cycle comes back to a mode only after all four of its taps.
        val closed = to == start
        if (closed) {
            start = null
            lastTapMs = Long.MIN_VALUE
        }
        return closed
    }
}

/** The longest pause between two taps that still counts as one run round the cycle. */
internal const val PlayModeCycleGapMs = 1_500L
