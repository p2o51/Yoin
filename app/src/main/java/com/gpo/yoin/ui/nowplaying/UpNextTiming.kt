package com.gpo.yoin.ui.nowplaying

import kotlin.math.roundToInt

/**
 * The outro choreography, in playhead time:
 *  - from [revealStartMs] the last line stretches to the right (I-2, the held
 *    note) and, at the same time, the next song's title card and first lines
 *    slowly fade up (to partial strength) and rise from below it — one window,
 *    one progress ([outroProgress]);
 *  - hand-over at [handoffAtMs]: the stretch lets go, the list glides the next
 *    title up to the exact spot the next song's own list opens with (its first
 *    item, at the top), the next lines come to full strength and this song's
 *    lines fade out. When the song then changes, the new list is
 *    pixel-identical below that title and swaps in place.
 */
internal data class UpNextTiming(val revealStartMs: Long, val handoffAtMs: Long)

/**
 * Deterministic outro timing: whenever the next song's timed lyrics are known
 * and this song has a timed last line, the reveal runs with the last line and
 * the hand-over lands before the song ends.
 *
 * Earlier rule skipped the outro outright when the last line started within
 * 3.4s of the end (hand-over lead + the last line's own 2s) — songs that end on
 * their last word never showed it. Now the hand-over slides later instead
 * (never closer to the end than [UpNextHandoffMinLeadMs], which the song-change
 * check still sees).
 *
 * Null only when there is nothing to choreograph: no next song, untimed next
 * lyrics (their own list opens untimed, so the swap could not match), or no
 * [lastLineOutroFor] window.
 */
internal fun upNextTimingFor(
    lyrics: List<LyricLine>,
    upNext: UpNextLyrics?,
    durationMs: Long,
): UpNextTiming? {
    if (upNext == null) return null
    if (upNext.lines.none { it.startMs != null }) return null
    return lastLineOutroFor(lyrics, durationMs)
}

/**
 * The last line's outro window, with or without a next song to stage: the
 * stretch (and, when staged, the reveal) runs [UpNextTiming.revealStartMs] →
 * [UpNextTiming.handoffAtMs].
 *
 * The window opens as the last line takes the stage — or [UpNextRevealLeadMs]
 * before the end, after a long instrumental tail — so the next song floats up
 * WHILE the last line is held (owner, 2026-10-05: "同时下一首慢慢浮上来"), not
 * two seconds after it as before.
 *
 * Null for untimed lyrics, an unknown duration, or a last line inside the
 * final [UpNextHandoffMinLeadMs] (mistimed, or no room to move at all).
 */
internal fun lastLineOutroFor(lyrics: List<LyricLine>, durationMs: Long): UpNextTiming? {
    if (durationMs <= 0L) return null
    val lastStart = lyrics.lastOrNull { it.startMs != null }?.startMs ?: return null
    if (durationMs - lastStart < UpNextHandoffMinLeadMs) return null
    // The last line keeps the stage for its own beat when there is room;
    // otherwise the hand-over waits as late as the song-change check allows.
    val handoffAt = maxOf(
        durationMs - UpNextHandoffLeadMs,
        minOf(lastStart + UpNextMinLastLineMs, durationMs - UpNextHandoffMinLeadMs),
    )
    // lastStart ≤ handoffAt always (lastStart ≤ end − min lead), so the window is never inverted.
    val revealStart = maxOf(durationMs - UpNextRevealLeadMs, lastStart)
    return UpNextTiming(revealStartMs = revealStart, handoffAtMs = handoffAt)
}

/**
 * How far into the outro window [positionMs] is, 0..1, quantized to [steps]
 * so a 4Hz tick recomposes only when the outro visibly advances (springs
 * smooth the steps).
 */
internal fun UpNextTiming.outroProgress(positionMs: Long, steps: Int = UpNextRevealSteps): Float {
    val span = (handoffAtMs - revealStartMs).coerceAtLeast(1L)
    val p = ((positionMs - revealStartMs).toFloat() / span).coerceIn(0f, 1f)
    return (p * steps).roundToInt() / steps.toFloat()
}

/** Recomposition granularity of the outro (springs smooth between steps). */
internal const val UpNextRevealSteps = 24

/** Outro: how long before the end the next song starts to show. */
internal const val UpNextRevealLeadMs = 10_000L

/** Outro: how long before the end the next title glides up and takes over. */
internal const val UpNextHandoffLeadMs = 1_400L

/**
 * Latest hand-over, measured from the end. Leaves the glide most of its travel
 * and keeps the hand-over inside what the song-change check
 * ([UpNextHandoffTickSlackMs] after the last 250ms tick) still recognises.
 */
internal const val UpNextHandoffMinLeadMs = 900L

/** The last line keeps the stage at least this long before the hand-over (when the outro has room). */
internal const val UpNextMinLastLineMs = 2_000L

/** Position ticks arrive every 250ms; tolerate one when judging the hand-over. */
internal const val UpNextHandoffTickSlackMs = 300L
