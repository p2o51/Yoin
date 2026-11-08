package com.gpo.yoin.ui.component

import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalPlaybackProgressSmoother = staticCompositionLocalOf<PlaybackProgressSmoother?> { null }

/**
 * Runs the pill's progress front between the player's 4 Hz position ticks.
 *
 * On a 15–60 s song each tick moves the front several pixels, so the raw
 * fraction visibly steps. Instead of tweening each tick (a per-tick animation
 * and its own frames), the pill asks this in its DRAW phase, which already
 * runs on every wave frame while playing: the last tick plus the time since,
 * at the song's real rate. No extra state, frames, or recompositions.
 *
 * Shared by every window (like [PlaybackWaveState]) so the shell's and a
 * detail window's pill draw the same front through the hand-off.
 *
 * A tick that lands slightly behind what was already drawn (frame-time
 * jitter) re-anchors at the drawn front instead of stepping back; a real
 * seek, track change, or a stall longer than [MaxLeadMs] snaps to the tick.
 */
class PlaybackProgressSmoother(private val durationMs: () -> Long) {
    private var sample = Float.NaN
    private var anchor = 0f
    private var anchorNanos = 0L
    private var wasPlaying = false
    private var shown = 0f

    /** The fraction to draw for this [nowNanos]. Call only from draw. */
    fun displayed(progress: Float, isPlaying: Boolean, nowNanos: Long): Float {
        val duration = durationMs()
        if (!isPlaying || duration <= 0L) {
            sample = progress
            anchor = progress
            anchorNanos = nowNanos
            wasPlaying = false
            shown = progress
            return progress
        }
        val perNano = 1f / (duration * 1_000_000f)
        val maxLead = MaxLeadMs * 1_000_000f * perNano
        if (!wasPlaying || progress != sample) {
            val behind = shown - progress
            anchor = if (wasPlaying && behind > 0f && behind <= maxLead) shown else progress
            anchorNanos = nowNanos
            sample = progress
            wasPlaying = true
        }
        val lead = ((nowNanos - anchorNanos) * perNano).coerceIn(0f, maxLead)
        shown = (anchor + lead).coerceIn(0f, 1f)
        return shown
    }

    private companion object {
        /** Two ticks and a bit: covers a late tick, never runs far past a buffering stall. */
        const val MaxLeadMs = 600f
    }
}
