package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.SingFixtures.buffer
import com.gpo.yoin.player.sing.dsp.LiveVocalSeparator
import com.gpo.yoin.player.sing.dsp.SeparationModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the engine separates first, against the vocal track's readiness blocks: the chunks V at the
 * playhead's whole block depends on must come first, or Ready waits for the rest of the song.
 */
class SingPlanGeometryTest {
    private object HalfModel : SeparationModel {
        override fun run(input: FloatArray, output: FloatArray) {
            for (i in input.indices) output[i] = 0.5f * input[i]
        }
    }

    private class Song(rate: Int, seconds: Double) {
        val frames = (rate * seconds).toInt()
        private val mix = SingFixtures.synth(frames, rate)
        val separator = LiveVocalSeparator(mix.first.buffer(), mix.second.buffer(), frames, rate)
        val track = SingVocalTrack.inMemory(rate, frames)
        val sink = LiveVocalSeparator.VocalSink { from, to, left, right, offset -> track.writeVocal(from, to, left, right, offset) }

        init {
            separator.start(sink)
        }

        /** Runs chunks as the engine does for a paused playhead at [positionMs] until Ready; the chunks. */
        fun runPausedUntilReady(positionMs: Long, limit: Int): List<Int> {
            val order = ArrayList<Int>()
            while (!SingReadiness.isReadyAt(track, positionMs) && order.size < limit) {
                val chunk = SingReadiness.chunkFromPlayhead(separator, track, positionMs)
                if (chunk < 0) break
                separator.runChunk(chunk, HalfModel, sink)
                order += chunk
            }
            return order
        }
    }

    @Test
    fun should_beReadyAfterTheChunksUnderTheReadySpan_when_pausedAt4900msOf8kHzSong() {
        // Round 3, B3 "Undertow" / B4 "Postcards from June": 8 kHz WAV, paused at 4.9 s. The playhead's
        // 4096-frame block starts at 4.608 s, inside chunk 1's overlap with chunk 2; planning from the
        // playhead's own STFT frame started at chunk 2 and swept forward to the end before chunk 1.
        val song = Song(8_000, 30.0)
        val playFrame = 4_900L * 8_000 / 1000
        assertEquals("the old plan's first chunk", 2, song.separator.nextChunk(song.separator.frameAtSource(playFrame)))
        assertEquals(36_864L, SingReadiness.planFrame(playFrame))

        val order = song.runPausedUntilReady(4_900L, limit = song.separator.chunkCount)

        assertTrue("Ready", SingReadiness.isReadyAt(song.track, 4_900L))
        assertEquals("chunks 1..4 cover 4.608 s to 8.9 s", listOf(1, 2, 3, 4), order)
    }

    @Test
    fun should_beReadyWithinTheSpansChunks_when_pausedAnywhere() {
        for (rate in intArrayOf(8_000, 11_025, 44_100)) {
            for (positionMs in longArrayOf(0L, 370L, 2_970L, 4_850L, 4_900L, 5_130L, 7_777L, 11_111L)) {
                val song = Song(rate, 20.0)
                val order = song.runPausedUntilReady(positionMs, limit = song.separator.chunkCount)
                assertTrue("$rate Hz @ $positionMs ms: Ready", SingReadiness.isReadyAt(song.track, positionMs))
                // A chunk is ~1.86 s apart; the span from the block start to 4 s on is under 5 s, which
                // ~3 chunks cover, plus their neighbours' overlap: never the forward sweep to the end.
                assertTrue("$rate Hz @ $positionMs ms: $order", order.size <= 5)
                assertEquals("$rate Hz @ $positionMs ms: in order $order", order.sorted(), order)
            }
        }
    }

    @Test
    fun should_startAtTheChunkUnderTheBlockStart_when_planningAPlayingTarget() {
        val song = Song(8_000, 30.0)
        // A target 4.9 s in plans from 4.608 s: chunk 1, as the paused case.
        assertEquals(1, SingReadiness.chunkForTarget(song.separator, song.track, 4_900L))
        // The predicted final end from the block start, after the chunks run, holds.
        val from = SingReadiness.planFrame(4_900L * 8)
        val predicted = song.separator.finalEndAfter(from, 4)
        repeat(4) {
            song.separator.runChunk(SingReadiness.chunkForTarget(song.separator, song.track, 4_900L), HalfModel, song.sink)
        }
        assertTrue("covered from $from to $predicted", song.track.isCovered(from, minOf(predicted, song.frames.toLong()) / SingVocalTrack.BLOCK_FRAMES * SingVocalTrack.BLOCK_FRAMES))
    }
}
