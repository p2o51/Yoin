package com.gpo.yoin.ui.component

// ---------------------------------------------------------------------------
// Shared duration formatting. One home for the m:ss / "1h 12m" strings that
// track rows, playback pills and header meta lines all render.
// ---------------------------------------------------------------------------

/** Track duration in whole seconds → "m:ss" (e.g. 225 → "3:45"). */
internal fun formatTrackDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
}

/** Playback position in milliseconds → "m:ss" (e.g. "3:45", "0:00"); negatives clamp to "0:00". */
internal fun formatTrackDurationMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/** Total runtime in whole seconds → "38m" / "1h 12m" (album header + playlist pill format). */
internal fun formatTotalDuration(seconds: Int): String {
    val totalMin = seconds / 60
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
