package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.SingFixtures.buffer
import com.gpo.yoin.player.sing.SingFixtures.maxAbsError
import com.gpo.yoin.player.sing.SingFixtures.toArray
import com.gpo.yoin.player.sing.dsp.FullBandSeparator
import com.gpo.yoin.player.sing.dsp.LiveVocalSeparator
import com.gpo.yoin.player.sing.dsp.SeparationModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tanh

class LiveVocalSeparatorTest {
    /**
     * A stand-in model that isn't linear (so the per-chunk RMS normalisation matters) and treats every
     * bin, frame and plane differently (so a chunk edge in the wrong place shows).
     */
    private object FakeModel : SeparationModel {
        override fun run(input: FloatArray, output: FloatArray) {
            for (i in input.indices) output[i] = (0.6 * tanh(input[i] * (0.5 + (i % 7) * 0.1))).toFloat()
        }
    }

    /** Collects what the live separator hands out, checking every frame comes exactly once. */
    private class Collector(frames: Int) : LiveVocalSeparator.VocalSink {
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        val times = IntArray(frames)

        override fun onVocal(from: Int, to: Int, left: java.nio.FloatBuffer, right: java.nio.FloatBuffer, offset: Int) {
            for (i in from until to) {
                this.left[i] = left.get(i - offset)
                this.right[i] = right.get(i - offset)
                times[i]++
            }
        }
    }

    private fun wholeSong(left: FloatArray, right: FloatArray, rate: Int): Pair<FloatArray, FloatArray> {
        val result = FullBandSeparator().separate(left.buffer(), right.buffer(), left.size, rate, FakeModel)
        return result.vocalLeft.toArray(left.size) to result.vocalRight.toArray(left.size)
    }

    /** Runs the live separator, the priority frame changing as [priorityAfter] says (chunks run → frame). */
    private fun live(
        left: FloatArray,
        right: FloatArray,
        rate: Int,
        priorityAfter: (run: Int, separator: LiveVocalSeparator) -> Int,
    ): Pair<Collector, List<Int>> {
        val separator = LiveVocalSeparator(left.buffer(), right.buffer(), left.size, rate)
        val collector = Collector(left.size)
        separator.start(collector)
        val order = ArrayList<Int>()
        while (true) {
            val chunk = separator.nextChunk(priorityAfter(order.size, separator))
            if (chunk < 0) break
            separator.runChunk(chunk, FakeModel, collector)
            order += chunk
        }
        assertTrue(separator.isFinished)
        return collector to order
    }

    private fun assertMatchesWholeSong(rate: Int, seconds: Double) {
        val frames = (seconds * rate).toInt()
        val (left, right) = SingFixtures.synth(frames, rate)
        val (wholeL, wholeR) = wholeSong(left, right, rate)
        // Start in the middle, jump near the start after a few chunks (a seek), then wherever.
        val (live, order) = live(left, right, rate) { run, separator ->
            when {
                run < 3 -> separator.frameAtSource(frames / 2L)
                run < 5 -> separator.frameAtSource(rate / 3L)
                else -> separator.frameAtSource(frames * 3L / 4)
            }
        }
        assertEquals(order.size, order.toSet().size)
        assertTrue("every frame exactly once", live.times.all { it == 1 })
        val error = maxOf(maxAbsError(wholeL, live.left), maxAbsError(wholeR, live.right))
        assertTrue("live vs whole song: max error $error", error <= 1e-5)
        assertTrue("not trivially silent", SingFixtures.peak(wholeL) > 1e-3)
    }

    @Test
    fun should_matchWholeSong_when_chunksRunOutOfOrder_44k1() = assertMatchesWholeSong(44_100, 14.0)

    @Test
    fun should_matchWholeSong_when_chunksRunOutOfOrder_48k() = assertMatchesWholeSong(48_000, 11.0)

    @Test
    fun should_matchWholeSong_when_sourceAtCoreRate() = assertMatchesWholeSong(22_050, 13.0)

    @Test
    fun should_emitSilence_when_clipTooShortToSeparate() {
        val (left, right) = SingFixtures.synth(800, 44_100)
        val (live, order) = live(left, right, 44_100) { _, _ -> 0 }
        assertTrue(order.isEmpty())
        assertTrue(live.times.all { it == 1 })
        assertTrue(live.left.all { it == 0f })
    }

    @Test
    fun should_runListenersChunksFirstThenRestThenEarlier_when_seeking() {
        val rate = 22_050
        val frames = rate * 20 // ~1723 STFT frames, 10 chunks starting 0, 160, …, 1440, 1467
        val (left, right) = SingFixtures.synth(frames, rate)
        val seekFrame = 1200
        val (_, order) = live(left, right, rate) { run, _ -> if (run < 3) 700 else seekFrame }
        // Frame 700 (needs from 697): chunk 3 (starts 480, ends 736) is the first one V there depends on.
        assertArrayEquals(intArrayOf(3, 4, 5), order.take(3).toIntArray())
        // Seek to 1200 (needs from 1197): chunk 6 (960..1216) first, forward to the end, then back.
        assertArrayEquals(intArrayOf(6, 7, 8, 9, 10, 2, 1, 0), order.drop(3).toIntArray())
    }

    @Test
    fun should_makePlayPositionFinal_when_firstChunksRun() {
        val rate = 44_100
        val frames = rate * 20
        val (left, right) = SingFixtures.synth(frames, rate)
        val separator = LiveVocalSeparator(left.buffer(), right.buffer(), frames, rate)
        val collector = Collector(frames)
        separator.start(collector)
        val play = rate * 9L
        val priority = separator.frameAtSource(play)
        // The first two chunks from the play position (the first is the only one under it).
        repeat(2) { separator.runChunk(separator.nextChunk(priority), FakeModel, collector) }
        assertEquals(1, collector.times[play.toInt()])
        assertTrue(separator.chunksDone == 2 && !separator.isFinished)
    }

    /** Where V is final from [from] on: the first frame after it not yet handed out. */
    private fun Collector.finalEnd(from: Int): Long {
        var at = from
        while (at < times.size && times[at] == 1) at++
        return at.toLong()
    }

    @Test
    fun should_predictWhereVIsFinal_when_planningChunksAhead() {
        for (rate in intArrayOf(44_100, 48_000)) {
            val frames = rate * 30
            val (left, right) = SingFixtures.synth(frames, rate)
            val separator = LiveVocalSeparator(left.buffer(), right.buffer(), frames, rate)
            val collector = Collector(frames)
            separator.start(collector)
            val from = rate * 7L + 1234
            val priority = separator.frameAtSource(from)
            // One chunk under a frame doesn't make it final (its neighbour overlaps it).
            for (k in 1..4) {
                val predicted = separator.finalEndAfter(from, 1)
                separator.runChunk(separator.nextChunk(priority), FakeModel, collector)
                val actual = collector.finalEnd(from.toInt())
                assertTrue("k=$k @ $rate: predicted $predicted ≤ actual $actual", predicted <= actual || predicted <= from)
                // Conservative by no more than the iSTFT and upsampler edges (a few hops).
                assertTrue("k=$k @ $rate: predicted $predicted ≫ behind actual $actual", actual - maxOf(predicted, from) < 4L * 256 * rate / 22_050)
            }
            // Planned from scratch: four chunks ahead predicts as far as running them does, and a chunk
            // already run comes free.
            val fresh = LiveVocalSeparator(left.buffer(), right.buffer(), frames, rate)
            val freshCollector = Collector(frames)
            fresh.start(freshCollector)
            val ahead = rate * 18L
            val four = fresh.finalEndAfter(ahead, 4)
            repeat(4) { fresh.runChunk(fresh.nextChunk(fresh.frameAtSource(ahead)), FakeModel, freshCollector) }
            assertTrue(four <= freshCollector.finalEnd(ahead.toInt()))
            assertEquals(four, fresh.finalEndAfter(ahead, 0))
            assertTrue(fresh.finalEndAfter(ahead, 1) > four)
            // To the end of the song: everything.
            assertEquals(Long.MAX_VALUE, fresh.finalEndAfter(ahead, 100))
        }
    }
}
