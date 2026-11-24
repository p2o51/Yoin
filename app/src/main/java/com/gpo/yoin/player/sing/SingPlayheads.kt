package com.gpo.yoin.player.sing

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Where the listener is in each song the on-device engine works on: the [SingRequest]'s own position
 * (the player's, taken as Sing was turned on or followed the track) until [SingPlaybackSwitcher]'s
 * reports ([report]) come in, then the last report moved on by the time since while playing. The
 * first plan, the decoder's first seek and the kept-track Ready check all read [at] before any report
 * may have arrived, so [begin] seeds it from the request.
 */
internal class SingPlayheads(private val nanoTime: () -> Long = System::nanoTime) {
    class Playhead(val positionMs: Long, val atNanos: Long, val playing: Boolean)

    private val known = ConcurrentHashMap<String, Playhead>()

    /** Per request, bumped on every jump of the playhead (a seek): the lead is planned anew. */
    private val seeks = ConcurrentHashMap<String, Int>()

    /**
     * A new job for [request] (the other songs' playheads go): from the request's position, unless a
     * report for it came in just now (the switcher's first tick can beat the job's start) — an older
     * one is left from an earlier go at the song and says nothing about now.
     */
    fun begin(request: SingRequest) {
        val now = nanoTime()
        known.keys.removeAll { it != request.key }
        seeks.keys.removeAll { it != request.key }
        known.compute(request.key) { _, old ->
            if (old != null && now - old.atNanos <= FRESH_REPORT_NANOS) {
                old
            } else {
                Playhead(request.positionMs.coerceAtLeast(0L), now, request.playing)
            }
        }
    }

    /** A report from the player; true when it's a jump from where the last one said it would be (a seek). */
    fun report(request: SingRequest, positionMs: Long, playing: Boolean): Boolean {
        val now = nanoTime()
        val previous = known.put(request.key, Playhead(positionMs.coerceAtLeast(0L), now, playing)) ?: return false
        val expected = if (previous.playing) previous.positionMs + (now - previous.atNanos) / 1_000_000 else previous.positionMs
        val seek = abs(positionMs.coerceAtLeast(0L) - expected) > SEEK_JUMP_MS
        if (seek) seeks.merge(request.key, 1, Int::plus)
        return seek
    }

    /** The listener's position now: the last report (or the request's), moved on by the time since if playing. */
    fun at(request: SingRequest): Playhead {
        val now = nanoTime()
        val last = known[request.key] ?: Playhead(request.positionMs.coerceAtLeast(0L), now, request.playing)
        val position = if (last.playing) last.positionMs + (now - last.atNanos) / 1_000_000 else last.positionMs
        return Playhead(position, now, last.playing)
    }

    fun seekGeneration(request: SingRequest): Int = seeks[request.key] ?: 0

    fun forget(request: SingRequest) {
        known.remove(request.key)
        seeks.remove(request.key)
    }

    companion object {
        /** A report this far from where the last one said it would be is a seek. */
        const val SEEK_JUMP_MS = 1_000L

        /**
         * A report this recent beats the request's position (the switcher reports every 500 ms while Sing
         * is on, so anything older is from before this go).
         */
        const val FRESH_REPORT_NANOS = 1_000_000_000L
    }
}
