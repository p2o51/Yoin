package com.gpo.yoin.player.sing.dsp

import com.gpo.yoin.player.sing.dsp.LyraSingSpec.CORE_RATE
import com.gpo.yoin.player.sing.dsp.LyraSingSpec.HOP
import com.gpo.yoin.player.sing.dsp.LyraSingSpec.N_FFT
import java.nio.FloatBuffer
import java.util.TreeMap

/**
 * [FullBandSeparator] a chunk at a time, for Sing while the song plays: the same pipeline on the same
 * signals — X decoded once, resampled to 22.05 kHz, STFT over the whole song (reflect padding only at
 * the song's true ends), the same chunk plan and cross-fade — so a chunk's edges come out exactly as
 * in a whole-song run, whatever order the chunks go in. Only float rounding differs (the order sums
 * are taken in).
 *
 * The caller picks each chunk with [nextChunk] (where the listener is, see there) and runs it with
 * [runChunk]; as soon as a stretch of V at the source rate is final — every chunk under its frames,
 * every frame under its samples, every core sample under the upsampling filter — it goes to the
 * [VocalSink], once.
 *
 * Carrying on ([markFinal]): V already final somewhere (a kept partial track) is never redone — only
 * the chunks some missing frame depends on run ([isChunkNeeded]); chunks at the edge of what's kept
 * run again (a frame needs every chunk over it), so their outputs are still the whole-song ones.
 *
 * Feeding as it decodes: [inputRange] says which source frames a chunk reads, so the caller can run
 * it as soon as those are decoded.
 *
 * Memory: the 22.05 kHz signals come from [work] (memory-mapped on the device), which must hand out
 * zeroed buffers (a fresh mapped file and [FloatBuffer.allocate] both are): V is summed into them, and
 * a page of a mapped file is only touched — resident — once a chunk writes there. Per second of audio:
 * 4 × 4 B × 22 050 ≈ 353 KB (mix L/R, V L/R at the core rate). The mix is resampled down lazily, a
 * block at a time, as chunks need it. Frames waiting for a neighbouring chunk hold their spectrum
 * (8 KB each) — a chunk's overlap at each edge of a stretch run so far.
 */
class LiveVocalSeparator(
    private val mixLeft: FloatBuffer,
    private val mixRight: FloatBuffer,
    val frames: Int,
    val sampleRate: Int,
    work: (floats: Int) -> FloatBuffer = { FloatBuffer.allocate(it) },
    step: Int = LyraSingSpec.DEFAULT_STEP_FRAMES,
) {
    /** Receives final V for source frames [from, to), at index (frame − [offset]) of [left]/[right]. */
    fun interface VocalSink {
        fun onVocal(from: Int, to: Int, left: FloatBuffer, right: FloatBuffer, offset: Int)
    }

    private val down = PolyphaseResampler(sampleRate, CORE_RATE)
    private val up = PolyphaseResampler(CORE_RATE, sampleRate)

    /** The song's length at 22.05 kHz. */
    val coreLength: Int = down.outputLength(frames)
    private val coreL = work(coreLength)
    private val coreR = work(coreLength)
    private val coreDown = BooleanArray((coreLength + CORE_BLOCK - 1) / CORE_BLOCK)
    private val vocalCoreL = work(coreLength)
    private val vocalCoreR = work(coreLength)

    /** Source frames below this come from the upsampled vocal; the rest (rounding) is silence. */
    private val upValid = minOf(frames, up.outputLength(coreLength))

    private val runner = ChunkRunner()
    private val stft = runner.stft

    /** A clip no longer than half a window has nothing to sing: V is silence throughout. */
    private val silent = coreLength <= N_FFT / 2
    val totalFrames: Int = if (silent) 0 else stft.frameCount(coreLength)
    private val plan: ChunkPlan? = if (silent) null else ChunkPlan(totalFrames, step)
    private val chunkDone = BooleanArray(plan?.size ?: 0)

    /** Chunks some frame not yet final depends on; recomputed after [markFinal]. */
    private val needed = BooleanArray(plan?.size ?: 0) { true }
    private val remaining: IntArray = plan?.coverage() ?: IntArray(0)
    private val pending = HashMap<Int, FrameSum>()
    private val inverted = BooleanArray(totalFrames)

    /** Hop blocks of core samples ([HOP] each); block k is final once frames k−1..k+2 are inverted. */
    private val hopBlocks = (coreLength + HOP - 1) / HOP
    private val coreFinal = BooleanArray(hopBlocks)
    private val sourceDone = IntervalSet()

    /** V at the source rate on its way to the sink (heap arrays: the sink reads them in bulk). */
    private var emitL = FloatArray(0)
    private var emitR = FloatArray(0)
    private var scratchL = FloatBuffer.wrap(emitL)
    private var scratchR = FloatBuffer.wrap(emitR)

    /** A hop block of V at the core rate, being normalised. */
    private val hopL = FloatArray(HOP)
    private val hopR = FloatArray(HOP)

    val chunkCount: Int get() = chunkDone.size

    var chunksDone: Int = 0
        private set

    /** Whether V is final for the whole song. */
    val isFinished: Boolean get() = sourceDone.covers(0, frames)

    /**
     * Emits what is final before any chunk runs: everything, for a clip too short to separate (V is
     * silence); nothing otherwise.
     */
    fun start(sink: VocalSink) {
        if (silent) emit(0, frames, sink)
    }

    /** The STFT frame under source frame [sourceFrame]. */
    fun frameAtSource(sourceFrame: Long): Int {
        val core = sourceFrame.coerceAtLeast(0L) * CORE_RATE / sampleRate
        return ((core + HOP / 2) / HOP).toInt().coerceIn(0, maxOf(0, totalFrames - 1))
    }

    fun isChunkDone(index: Int): Boolean = chunkDone[index]

    /** Whether chunk [index] still has to run: not run yet, and some frame not yet final depends on it. */
    fun isChunkNeeded(index: Int): Boolean = !chunkDone[index] && needed[index]

    /**
     * Source frames [from, to) already have their final V (kept from an earlier run): never emitted
     * again, and chunks only they depend on are skipped. Before any chunk runs.
     */
    fun markFinal(from: Int, to: Int) {
        check(chunksDone == 0) { "markFinal after chunks ran" }
        val a = from.coerceIn(0, frames)
        val b = to.coerceIn(0, frames)
        if (a >= b) return
        sourceDone.add(a, b)
        val plan = plan ?: return
        needed.fill(false)
        for ((gapFrom, gapTo) in sourceDone.gaps(0, frames)) {
            // V at source frames [gapFrom, gapTo) reads core samples jLo..jHi (the upsampler's reach),
            // core sample j lies in hop block j / HOP, final once frames k−1..k+2 are inverted, each
            // once every chunk over it has run.
            val jLo = maxOf(0L, ceilDiv(gapFrom.toLong() * up.down - up.reach, up.up.toLong()))
            val jHi = minOf(coreLength - 1L, Math.floorDiv((gapTo - 1L) * up.down + up.reach, up.up.toLong()))
            if (jLo > jHi) continue
            val fLo = maxOf(0L, jLo / HOP - 1).toInt()
            val fHi = minOf(totalFrames - 1L, jHi / HOP + 2).toInt()
            for (i in 0 until plan.size) {
                val s = plan.starts[i]
                if (s <= fHi && s + plan.validFrames(i) - 1 >= fLo) needed[i] = true
            }
        }
    }

    /**
     * The source frames [first, last) chunk [index] reads from the mix when it runs: its frames'
     * windows (reflect padding at the song's ends), rounded out to the blocks the mix is resampled
     * down in, and the downsampling filter's reach — so it can run once those are decoded.
     */
    fun inputRange(index: Int): Pair<Int, Int> {
        val plan = checkNotNull(plan) { "nothing to separate" }
        val start = plan.starts[index]
        val valid = plan.validFrames(index)
        val lo = start * HOP - N_FFT / 2
        val hi = (start + valid - 1) * HOP + N_FFT / 2
        var coreFrom = maxOf(0, lo)
        var coreTo = minOf(coreLength, hi)
        if (lo < 0) {
            coreFrom = 0
            coreTo = maxOf(coreTo, minOf(coreLength, N_FFT / 2 + 1))
        }
        if (hi > coreLength) {
            coreFrom = minOf(coreFrom, maxOf(0, coreLength - N_FFT / 2 - 1))
            coreTo = coreLength
        }
        // downRange works in whole CORE_BLOCKs.
        val blockFrom = coreFrom / CORE_BLOCK * CORE_BLOCK
        val blockTo = minOf(coreLength, ((maxOf(coreFrom + 1, coreTo) - 1) / CORE_BLOCK + 1) * CORE_BLOCK)
        if (down.isIdentity) return blockFrom.coerceIn(0, frames) to blockTo.coerceIn(0, frames)
        // Core sample m reads source j from ⌈(m·down − reach)/up⌉ to ⌊(m·down + reach)/up⌋.
        val first = maxOf(0L, ceilDiv(blockFrom.toLong() * down.down - down.reach, down.up.toLong()))
        val last = minOf(frames - 1L, Math.floorDiv((blockTo - 1L) * down.down + down.reach, down.up.toLong()))
        return first.toInt().coerceIn(0, frames) to (last + 1).toInt().coerceIn(0, frames)
    }

    /**
     * The chunk to run next for a listener at STFT frame [priorityFrame]: the first chunk not yet run
     * among those from the earliest one V at that frame depends on (so the play position is final as
     * soon as possible), forwards to the end of the song; once those are all done, the earlier ones,
     * nearest first. −1 when every chunk has run.
     */
    fun nextChunk(priorityFrame: Int): Int {
        if (plan == null) return -1
        // V at a core sample needs frames k−1..k+2 of its hop block k, and the upsampler reaches a few
        // core samples further: three frames back covers both.
        return nextChunkFromChunk(firstChunkCovering((priorityFrame - 3).coerceIn(0, totalFrames - 1)))
    }

    /**
     * [nextChunk] for V final from source frame [sourceFrame] on, exactly: the first chunk not yet run
     * from the earliest one V at [sourceFrame] depends on (through the upsampler's and the iSTFT's
     * reach), forwards, then the earlier ones. −1 when every chunk has run.
     */
    fun nextChunkFrom(sourceFrame: Long): Int {
        if (plan == null) return -1
        return nextChunkFromChunk(firstChunkFor(sourceFrame))
    }

    private fun nextChunkFromChunk(first: Int): Int {
        val plan = checkNotNull(plan)
        for (i in first until plan.size) if (isChunkNeeded(i)) return i
        for (i in first - 1 downTo 0) if (isChunkNeeded(i)) return i
        return -1
    }

    /**
     * The earliest chunk V at source frame [sourceFrame] depends on: the upsampling filter for that
     * frame reads core samples from j on; core sample j lies in hop block k = j / [HOP], final once
     * frames k−1..k+2 are inverted; frame k−1 needs every chunk over it, the earliest first.
     */
    private fun firstChunkFor(sourceFrame: Long): Int {
        val m = sourceFrame.coerceIn(0L, maxOf(0L, frames - 1L))
        val coreFirst = maxOf(0L, ceilDiv(m * up.down - up.reach, up.up.toLong()))
        val hopBlock = minOf(coreFirst / HOP, (hopBlocks - 1).toLong()).toInt()
        return firstChunkCovering((hopBlock - 1).coerceIn(0, totalFrames - 1))
    }

    /** The first chunk that covers STFT frame [frame] (chunks covering it run from there on). */
    private fun firstChunkCovering(frame: Int): Int {
        val plan = checkNotNull(plan)
        for (i in 0 until plan.size) {
            if (plan.starts[i] + plan.validFrames(i) > frame) return i
        }
        return plan.size - 1
    }

    /**
     * Where V would be final from source frame [sourceFrame] on — a source frame, or [Long.MAX_VALUE]
     * when to the song's end — once the next [chunks] chunks [nextChunkFrom] picks for that place have
     * run (forwards from the earliest chunk V there depends on; chunks already run come free).
     * Conservative by the iSTFT's and the upsampler's reach. For planning how far ahead of the
     * playhead to start.
     */
    fun finalEndAfter(sourceFrame: Long, chunks: Int): Long {
        val plan = plan ?: return Long.MAX_VALUE
        var i = firstChunkFor(sourceFrame)
        var left = chunks
        while (i < plan.size) {
            if (isChunkNeeded(i)) {
                if (left == 0) break
                left--
            }
            i++
        }
        if (i >= plan.size) return Long.MAX_VALUE
        // Frames before chunk i's start have every chunk they need; hop block k needs frames k−1..k+2,
        // so core samples are final below (start − 2) · HOP; then the upsampling filter's reach.
        val coreEnd = maxOf(0L, (plan.starts[i] - 2).toLong() * HOP)
        val end = Math.floorDiv(coreEnd * up.up - up.reach - 1, up.down.toLong()) + 1
        return end.coerceIn(0L, frames.toLong())
    }

    /** Runs chunk [index] through [model] and hands every stretch of V it completes to [sink]. */
    fun runChunk(index: Int, model: SeparationModel, sink: VocalSink) {
        val plan = checkNotNull(plan) { "nothing to separate" }
        check(!chunkDone[index]) { "chunk $index already run" }
        val start = plan.starts[index]
        val valid = plan.validFrames(index)
        ensureCore(start, valid)
        val scale = runner.run(coreL, coreR, coreLength, start, valid, model)
        val newlyFinal = ArrayList<Int>()
        for (t in 0 until valid) {
            val frame = start + t
            val sum = pending.getOrPut(frame) { FrameSum() }
            runner.accumulate(sum, t, scale, ChunkPlan.CROSSFADE[t])
            if (--remaining[frame] == 0) {
                pending.remove(frame)
                runner.invert(sum)
                stft.overlapAdd(frame, runner.outL, vocalCoreL, coreLength)
                stft.overlapAdd(frame, runner.outR, vocalCoreR, coreLength)
                inverted[frame] = true
                for (k in maxOf(0, frame - 2)..minOf(hopBlocks - 1, frame + 1)) {
                    if (!coreFinal[k] && hopBlockReady(k)) {
                        finishHopBlock(k)
                        newlyFinal += k
                    }
                }
            }
        }
        chunkDone[index] = true
        chunksDone++
        emitFinal(newlyFinal, sink)
    }

    private fun hopBlockReady(k: Int): Boolean {
        for (f in maxOf(0, k - 1)..minOf(totalFrames - 1, k + 2)) if (!inverted[f]) return false
        return true
    }

    /** Every frame over block [k] is in: divide by the window² envelope, as [Stft.normalize] does. */
    private fun finishHopBlock(k: Int) {
        coreFinal[k] = true
        val from = k * HOP
        val count = minOf(coreLength, from + HOP) - from
        vocalCoreL.getAt(from, hopL, 0, count)
        vocalCoreR.getAt(from, hopR, 0, count)
        for (n in 0 until count) {
            val envelope = stft.envelope(from + n, totalFrames)
            hopL[n] = (hopL[n] / envelope).toFloat()
            hopR[n] = (hopR[n] / envelope).toFloat()
        }
        vocalCoreL.putAt(from, hopL, 0, count)
        vocalCoreR.putAt(from, hopR, 0, count)
    }

    /** Hands out the source frames the newly final core [blocks] complete. */
    private fun emitFinal(blocks: List<Int>, sink: VocalSink) {
        var lastRunEnd = -1
        for (k in blocks.sorted()) {
            if (k <= lastRunEnd) continue
            var a = k
            while (a > 0 && coreFinal[a - 1]) a--
            var b = k
            while (b < hopBlocks - 1 && coreFinal[b + 1]) b++
            lastRunEnd = b
            val coreFrom = a * HOP
            val coreTo = minOf(coreLength, (b + 1) * HOP)
            val (lo, hi) = sourceRange(coreFrom, coreTo) ?: continue
            for ((from, to) in sourceDone.gaps(lo, hi)) emit(from, to, sink)
        }
    }

    /**
     * The source frames [lo, hi) whose upsampling filter reads only core samples in [coreFrom, coreTo)
     * (or past the song's ends, which read as zero): output m reads core j from ⌈(m·down − reach)/up⌉
     * to ⌊(m·down + reach)/up⌋.
     */
    private fun sourceRange(coreFrom: Int, coreTo: Int): Pair<Int, Int>? {
        val u = up.up.toLong()
        val d = up.down.toLong()
        val reach = up.reach.toLong()
        val lo = if (coreFrom == 0) 0L else Math.floorDiv((coreFrom - 1) * u + reach, d) + 1
        val hi = if (coreTo == coreLength) frames.toLong() else Math.floorDiv(coreTo * u - reach - 1, d) + 1
        val from = lo.coerceIn(0L, frames.toLong()).toInt()
        val to = hi.coerceIn(0L, frames.toLong()).toInt()
        return if (from < to) from to to else null
    }

    private fun emit(from: Int, to: Int, sink: VocalSink) {
        var at = from
        while (at < to) {
            val end = minOf(to, at + EMIT_BLOCK)
            val count = end - at
            if (emitL.size < count) {
                emitL = FloatArray(count)
                emitR = FloatArray(count)
                scratchL = FloatBuffer.wrap(emitL)
                scratchR = FloatBuffer.wrap(emitR)
            }
            val upEnd = minOf(end, upValid)
            if (!silent && at < upEnd) {
                up.process(vocalCoreL, coreLength, emitL, at, upEnd, outputOffset = at)
                up.process(vocalCoreR, coreLength, emitR, at, upEnd, outputOffset = at)
            }
            val zeroFrom = if (silent) at else maxOf(at, upEnd)
            emitL.fill(0f, zeroFrom - at, end - at)
            emitR.fill(0f, zeroFrom - at, end - at)
            sink.onVocal(at, end, scratchL, scratchR, at)
            sourceDone.add(at, end)
            at = end
        }
    }

    /** Resamples the mix down to 22.05 kHz where chunk frames [start, start + valid) read it. */
    private fun ensureCore(start: Int, valid: Int) {
        val lo = start * HOP - N_FFT / 2
        val hi = (start + valid - 1) * HOP + N_FFT / 2
        downRange(maxOf(0, lo), minOf(coreLength, hi))
        // Reflect padding reads up to half a window in from either end.
        if (lo < 0) downRange(0, minOf(coreLength, N_FFT / 2 + 1))
        if (hi > coreLength) downRange(maxOf(0, coreLength - N_FFT / 2 - 1), coreLength)
    }

    private fun downRange(from: Int, to: Int) {
        if (from >= to) return
        for (block in from / CORE_BLOCK..(to - 1) / CORE_BLOCK) {
            if (coreDown[block]) continue
            val s = block * CORE_BLOCK
            val e = minOf(coreLength, s + CORE_BLOCK)
            down.process(mixLeft, frames, coreL, s, e)
            down.process(mixRight, frames, coreR, s, e)
            coreDown[block] = true
        }
    }

    companion object {
        /** Core samples resampled down at a time. */
        private const val CORE_BLOCK = 8192

        /** Source frames handed to the sink at a time. */
        private const val EMIT_BLOCK = 65_536

        private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)
    }
}

/** Disjoint half-open int ranges, merged as they're added. */
internal class IntervalSet {
    private val ranges = TreeMap<Int, Int>()

    fun add(from: Int, to: Int) {
        if (from >= to) return
        var start = from
        var end = to
        val floor = ranges.floorEntry(start)
        if (floor != null && floor.value >= start) {
            start = floor.key
            end = maxOf(end, floor.value)
            ranges.remove(floor.key)
        }
        while (true) {
            val next = ranges.ceilingEntry(start) ?: break
            if (next.key > end) break
            end = maxOf(end, next.value)
            ranges.remove(next.key)
        }
        ranges[start] = end
    }

    /** The ranges in the set, in order. */
    fun ranges(): List<Pair<Int, Int>> = ranges.map { it.key to it.value }

    fun covers(from: Int, to: Int): Boolean {
        if (from >= to) return true
        val entry = ranges.floorEntry(from) ?: return false
        return entry.value >= to
    }

    /** The parts of [from, to) not in the set, in order. */
    fun gaps(from: Int, to: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var at = from
        ranges.floorEntry(from)?.let { if (it.value > at) at = it.value }
        while (at < to) {
            val next = ranges.ceilingEntry(at)
            if (next == null || next.key >= to) {
                out += at to to
                break
            }
            if (next.key > at) out += at to next.key
            at = maxOf(at, next.value)
        }
        return out
    }
}
