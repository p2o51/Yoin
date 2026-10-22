package com.gpo.yoin.player.applemusic

/**
 * A song MusicKit keeps loading without ever starting or failing.
 *
 * MusicKit (1.1.1) loads each song's assets in a task whose exceptions nothing reads: when that task throws, no
 * asset arrives, no error is reported, and the song buffers at 0:00 for good. Catalog ids above Int.MAX_VALUE
 * did exactly this until build-logic's MusicKitLargeIdPlugin. The prepare timeout only covers the wait before
 * MusicKit leaves STOPPED; this covers the rest: playback wanted, MusicKit buffering, and neither the item, the
 * position nor the buffered position moving for [stallMs]. Anything that moves, a pause, a new prepare or an
 * error restarts the clock.
 */
internal class MusicKitStallWatch(private val stallMs: Long = MUSICKIT_STALL_MS) {
    private var since: Long? = null
    private var last: Progress? = null

    /** True once the current wait has lasted [stallMs]; the watch then starts over. */
    fun observe(
        nowMs: Long,
        wantsPlay: Boolean,
        buffering: Boolean,
        itemKey: Long?,
        positionMs: Long,
        bufferedMs: Long,
    ): Boolean {
        if (!wantsPlay || !buffering) {
            reset()
            return false
        }
        val progress = Progress(itemKey, positionMs, bufferedMs)
        val start = since
        if (start == null || progress != last) {
            last = progress
            since = nowMs
            return false
        }
        if (nowMs - start < stallMs) return false
        reset()
        return true
    }

    private fun reset() {
        since = null
        last = null
    }

    private data class Progress(val itemKey: Long?, val positionMs: Long, val bufferedMs: Long)
}

/** Generous for a slow network: the first audio usually arrives in a few seconds. */
internal const val MUSICKIT_STALL_MS = 30_000L
