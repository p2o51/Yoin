package com.gpo.yoin.player.sing

import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SingVocalTrackTest {
    @get:Rule val temp = TemporaryFolder()

    private val block = SingVocalTrack.BLOCK_FRAMES

    private fun valueL(i: Int) = ((i % 2000) - 1000) / 1500f

    private fun valueR(i: Int) = -valueL(i) * 0.5f

    private fun write(track: SingVocalTrack, from: Int, to: Int) {
        val left = FloatBuffer.allocate(to - from)
        val right = FloatBuffer.allocate(to - from)
        for (i in from until to) {
            left.put(i - from, valueL(i))
            right.put(i - from, valueR(i))
        }
        track.writeVocal(from, to, left, right, sourceOffset = from)
    }

    @Test
    fun should_markBlocksReady_when_fullyWritten() {
        val frames = block * 3 + 100
        val track = SingVocalTrack.inMemory(44_100, frames)
        assertFalse(track.isReady(0))
        write(track, 0, block - 1)
        assertFalse(track.isReady(0)) // one frame short of the block
        write(track, block - 1, block + 10)
        assertTrue(track.isReady(0))
        assertFalse(track.isReady(block.toLong()))
        write(track, block * 3, frames) // the short last block
        assertTrue(track.isReady(frames - 1L))
        assertTrue(track.isReady(-5L) && track.isReady(frames + 5L)) // outside the song: no vocal
        assertEquals(2f / 4f, track.coverage, 1e-6f)
        assertFalse(track.isComplete)
        write(track, block + 10, block * 3)
        assertTrue(track.isComplete)
        assertEquals(valueL(1234), track.vocalLeft(1234), 1e-4f)
        assertEquals(valueR(block * 3 + 50), track.vocalRight(block * 3 + 50), 1e-4f)
        assertEquals(0.5f * (valueL(77) + valueR(77)), track.vocalMono(77), 1e-4f)
    }

    @Test
    fun should_reportReadyRun_when_askedFromAFrame() {
        val track = SingVocalTrack.inMemory(48_000, block * 6)
        write(track, block, block * 3)
        write(track, block * 4, block * 6)
        assertEquals(10L, track.readyEnd(10, 50_000)) // block 0 not in
        assertEquals(block * 3L, track.readyEnd(block + 5L, 1_000_000))
        assertEquals(block + 500L, track.readyEnd(block + 5L, block + 500L)) // capped at the limit
        assertEquals(Long.MAX_VALUE, track.readyEnd(block * 4L + 1, 1_000_000)) // runs to the song's end
        assertTrue(track.isCovered(block.toLong(), block * 3L))
        assertFalse(track.isCovered(block.toLong(), block * 3L + 1))
    }

    @Test
    fun should_keepAndReopenTrack_when_finished() {
        val file = File(temp.root, "song.vtrack.part")
        val frames = block * 2 + 7
        val track = SingVocalTrack.create(file, 44_100, frames)
        val mixL = FloatBuffer.wrap(FloatArray(frames) { 0.25f })
        val mixR = FloatBuffer.wrap(FloatArray(frames) { -0.75f })
        track.writeReference(mixL, mixR)
        write(track, 0, frames)
        track.finish()
        assertNull("an unfinished file isn't a track", SingVocalTrack.open(File(temp.root, "nothing")))

        val reopened = SingVocalTrack.open(file)
        assertNotNull(reopened)
        reopened!!
        assertTrue(reopened.isComplete)
        assertEquals(44_100, reopened.sampleRate)
        assertEquals(frames, reopened.frames)
        assertEquals(-0.25f, reopened.reference(5), 1e-4f)
        assertEquals(valueL(frames - 1), reopened.vocalLeft(frames - 1), 1e-4f)
    }

    @Test
    fun should_notOpen_when_neverFinished() {
        val file = File(temp.root, "cut.vtrack")
        SingVocalTrack.create(file, 44_100, block)
        assertNull(SingVocalTrack.open(file))
    }

    @Test
    fun should_neverShowUnwrittenSamples_when_readWhileWritten() {
        val blocks = 64
        val frames = block * blocks
        val track = SingVocalTrack.inMemory(44_100, frames)
        val order = (0 until blocks).shuffled(java.util.Random(7))
        val failure = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        val reader = Thread {
            while (done.count > 0 || !track.isComplete) {
                for (b in 0 until blocks) {
                    val at = b * block
                    if (!track.isReady(at.toLong())) continue
                    // A ready block is fully written: first, middle and last frame.
                    for (i in intArrayOf(at, at + block / 2, at + block - 1)) {
                        if (kotlin.math.abs(track.vocalLeft(i) - valueL(i)) > 1e-4f) failure.compareAndSet(null, "frame $i")
                    }
                }
                if (track.isComplete) break
            }
        }
        reader.start()
        for (b in order) write(track, b * block, (b + 1) * block)
        done.countDown()
        reader.join(10_000)
        assertNull(failure.get())
        assertTrue(track.isComplete)
    }
}
