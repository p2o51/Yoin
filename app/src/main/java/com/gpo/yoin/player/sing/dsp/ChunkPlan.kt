package com.gpo.yoin.player.sing.dsp

import com.gpo.yoin.player.sing.dsp.LyraSingSpec.CHUNK_FRAMES
import com.gpo.yoin.player.sing.dsp.LyraSingSpec.CROSSFADE_FLOOR
import kotlin.math.PI
import kotlin.math.cos

/**
 * Where the 256-frame model chunks of a song start, and how they cross-fade — the reference's rule:
 * a chunk every [step] frames from 0, the last one pinned to the end, each weighted by a symmetric
 * Hann (`np.hanning(256)`) floored at 1e-4, and every frame divided by the sum of its weights.
 *
 * [order] puts the chunk under the play position first (fast start), the rest of the song after it,
 * then what came before, nearest first.
 */
class ChunkPlan(val totalFrames: Int, val step: Int = LyraSingSpec.DEFAULT_STEP_FRAMES) {
    val starts: IntArray

    init {
        require(totalFrames > 0) { "no frames" }
        require(step in 1..CHUNK_FRAMES) { "step $step outside 1..$CHUNK_FRAMES" }
        val list = ArrayList<Int>()
        var s = 0
        val bound = maxOf(1, totalFrames - CHUNK_FRAMES + 1)
        while (s < bound) {
            list += s
            s += step
        }
        if (list.last() + CHUNK_FRAMES < totalFrames) list += maxOf(0, totalFrames - CHUNK_FRAMES)
        starts = list.toIntArray()
    }

    val size: Int get() = starts.size

    /** Frames of chunk [index] that exist (a song shorter than a chunk is zero-padded past its end). */
    fun validFrames(index: Int): Int = minOf(CHUNK_FRAMES, totalFrames - starts[index])

    /** How many chunks cover each frame; a frame is final once that many have added to it. */
    fun coverage(): IntArray {
        val count = IntArray(totalFrames)
        for (i in starts.indices) {
            val s = starts[i]
            for (t in 0 until validFrames(i)) count[s + t]++
        }
        return count
    }

    /** The chunk to start from for a play position at [frame]: the last one starting at or before it. */
    fun chunkAt(frame: Int): Int {
        var found = 0
        for (i in starts.indices) if (starts[i] <= frame) found = i
        return found
    }

    /** Every chunk once: [chunkAt] ([frame]) and those after it, then the earlier ones backwards. */
    fun order(frame: Int): IntArray {
        val first = chunkAt(frame.coerceIn(0, totalFrames - 1))
        return IntArray(size) { i -> if (i < size - first) first + i else first - 1 - (i - (size - first)) }
    }

    companion object {
        /** `np.maximum(np.hanning(256).astype(float32), 1e-4)`. */
        val CROSSFADE: FloatArray = FloatArray(CHUNK_FRAMES) { n ->
            maxOf((0.5 - 0.5 * cos(2.0 * PI * n / (CHUNK_FRAMES - 1))).toFloat(), CROSSFADE_FLOOR)
        }
    }
}
