package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.dsp.LiveVocalSeparator

/**
 * When live on-device Sing is ready, and where to separate so it gets there soonest.
 *
 * - **Ready**: V covers [READY_AHEAD_MS] from the playhead. Past that the engine keeps separating
 *   ahead of the playhead, and if the playhead ever outruns V the mixer just lets the original vocal
 *   back in over its 120 ms availability ramp, so a short requirement costs nothing in quality.
 * - **Lead** ([planLead]): while the song plays, the work starts where the playhead *will be* when
 *   it is done, using the live ms/chunk (as the song separates, not the idle gate figure).
 * - **Handover** ([handover]): when the UI flips to Ready. Playing: the first time the mixer takes the
 *   vocal out (the cut-in glide starts there). Paused: as soon as V covers the Ready span at the
 *   playhead — the glide still waits for the first real vocal removal once playback starts.
 */
object SingReadiness {
    /** Sing is Ready once V covers this much from the playhead. */
    const val READY_AHEAD_MS = 4_000

    /**
     * Planned slack between where V starts and where the playhead is when it's done: covers the
     * track's 4096-frame readiness blocks (~93 ms), the iSTFT/upsampler edge and playhead jitter.
     */
    const val PLAN_MARGIN_MS = 300L

    /** The longest lead planned. */
    const val MAX_LEAD_MS = 30_000L

    /** Playing, still not engaged after this long: hand over anyway (the safety net). */
    const val HANDOVER_TIMEOUT_MS = 8_000L

    /** A plan: start separating [leadMs] ahead of the playhead; Ready after about [chunks] chunks. */
    data class Lead(val leadMs: Long, val chunks: Int)

    /**
     * Where to start separating for a playing listener: the fewest chunks k for which, starting where
     * the playhead will be when those k chunks are done (k × [perChunkMs] from now, less
     * [PLAN_MARGIN_MS]), V then reaches [aheadMs] past the playhead.
     *
     * [coverEndMs] (start, k): where V would be final (ms from the playhead now) once the k chunks
     * the separator picks for a start [start] ms ahead have run — chunks already done count as free.
     */
    fun planLead(
        perChunkMs: Double,
        aheadMs: Long,
        maxChunks: Int = 24,
        coverEndMs: (startMs: Long, chunks: Int) -> Long,
    ): Lead {
        require(perChunkMs > 0.0)
        for (k in 1..maxChunks) {
            val arrive = (k * perChunkMs).toLong()
            if (arrive > MAX_LEAD_MS) break
            val start = (arrive - PLAN_MARGIN_MS).coerceAtLeast(0L)
            if (coverEndMs(start, k) - arrive >= aheadMs) return Lead(start, k)
        }
        // Slower than the song: aim as far ahead as allowed; the playhead catches V up eventually.
        return Lead(MAX_LEAD_MS, maxChunks)
    }

    /** Why the UI flipped to Ready. */
    enum class Handover { Engaged, PausedCoverage, Timeout }

    /**
     * Whether to hand over now, and why: the mixer took V out ([engaged]); or the player isn't
     * playing and V covers the Ready span at the playhead ([coveredAtPlayhead]) — there's nothing
     * to engage until it plays; or [elapsedMs] since binding passed [timeoutMs]. Null: keep waiting.
     */
    fun handover(
        engaged: Boolean,
        playing: Boolean,
        coveredAtPlayhead: Boolean,
        elapsedMs: Long,
        timeoutMs: Long = HANDOVER_TIMEOUT_MS,
    ): Handover? = when {
        engaged -> Handover.Engaged
        !playing && coveredAtPlayhead -> Handover.PausedCoverage
        elapsedMs >= timeoutMs -> Handover.Timeout
        else -> null
    }

    /**
     * Where separating for a place at source [frame] must start: the start of its
     * [SingVocalTrack.BLOCK_FRAMES] readiness block. A block counts as in only once every frame of it
     * is, so V must be final from there — 93 ms before [frame] at most at 44.1 kHz, but 512 ms at
     * 8 kHz, which can reach into the chunk before the one under [frame].
     */
    fun planFrame(frame: Long): Long =
        frame.coerceAtLeast(0L) / SingVocalTrack.BLOCK_FRAMES * SingVocalTrack.BLOCK_FRAMES

    /**
     * The chunk to run next for a playhead that isn't moving ahead of V — paused, or with V already
     * there: the earliest chunk the first block still missing from the playhead's own block on
     * depends on (so the Ready span fills in first, whatever the block size), forwards; with V in to
     * the song's end from there, the earlier chunks. −1 when every chunk has run.
     */
    fun chunkFromPlayhead(separator: LiveVocalSeparator, track: SingVocalTrack, positionMs: Long): Int {
        val playFrame = positionMs.coerceAtLeast(0L) * track.sampleRate / 1000L
        val from = planFrame(playFrame)
        val gap = track.readyEnd(from, track.frames.toLong())
        return separator.nextChunkFrom(if (gap == Long.MAX_VALUE) playFrame else planFrame(gap))
    }

    /** The chunk to run next for V from [targetMs] on (a playing listener's planned start). */
    fun chunkForTarget(separator: LiveVocalSeparator, track: SingVocalTrack, targetMs: Long): Int =
        separator.nextChunkFrom(planFrame(targetMs.coerceAtLeast(0L) * track.sampleRate / 1000L))

    /** Whether V in [track] covers [READY_AHEAD_MS] (or to the song's end) from [positionMs]. */
    fun isReadyAt(track: SingVocalTrack, positionMs: Long): Boolean {
        val from = positionMs.coerceAtLeast(0L) * track.sampleRate / 1000L
        return track.isCovered(from, from + READY_AHEAD_MS.toLong() * track.sampleRate / 1000L)
    }
}
