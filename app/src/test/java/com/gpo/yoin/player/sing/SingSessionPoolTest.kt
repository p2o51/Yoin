package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.dsp.SeparationModel
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SingSessionPoolTest {
    private class FakeSession : SeparationModel {
        var closed = false

        override fun run(input: FloatArray, output: FloatArray) = Unit

        override fun close() {
            closed = true
        }
    }

    private val model = File("model.onnx")
    private val opened = ArrayList<FakeSession>()

    private fun TestScope.pool() = SingSessionPool(backgroundScope, idleMs = 60_000) { FakeSession().also { opened += it } }

    @Test
    fun should_openWhileDecoding_when_prewarmed() = runTest {
        val pool = pool()
        pool.prewarm(model)
        runCurrent()
        assertEquals("opened before anyone asked", 1, opened.size)
        val lease = pool.take(model)
        assertSame(opened[0], lease.session)
        assertEquals(1, opened.size)
    }

    @Test
    fun should_reuseWarmSession_when_nextSongWithinGrace() = runTest {
        val pool = pool()
        val first = pool.take(model)
        pool.give(first)
        advanceTimeBy(59_000)
        val second = pool.take(model)
        assertSame(first.session, second.session)
        assertEquals(1, opened.size)
        assertFalse(opened[0].closed)
    }

    @Test
    fun should_closeIdleSession_when_unusedForGrace() = runTest {
        val pool = pool()
        pool.give(pool.take(model))
        advanceTimeBy(60_001)
        assertTrue(opened[0].closed)
        assertFalse(pool.isWarm)
        pool.take(model)
        assertEquals(2, opened.size)
    }

    @Test
    fun should_closeUnusedPrewarm_when_noSongTakesIt() = runTest {
        val pool = pool()
        pool.prewarm(model)
        runCurrent()
        advanceTimeBy(60_001)
        assertTrue(opened[0].closed)
    }

    @Test
    fun should_closeEverything_when_singTurnsOff() = runTest {
        val pool = pool()
        pool.prewarm(File("other.onnx"))
        runCurrent()
        val inUse = pool.take(model)
        assertTrue("another model's session goes", opened[0].closed)
        pool.close()
        assertFalse(inUse.session.let { (it as FakeSession).closed })
        // Back from the song that was cancelled: closed, not kept warm.
        pool.give(inUse)
        assertTrue((inUse.session as FakeSession).closed)
        assertFalse(pool.isWarm)
    }

    @Test
    fun should_keepOpeningWarm_when_takerCancelled() = runTest {
        // The pool's own work runs only when the test says so: the open is "still going".
        val queue = ArrayDeque<Runnable>()
        val poolScope = CoroutineScope(Executor { queue += it }.asCoroutineDispatcher() + Job())
        val pool = SingSessionPool(poolScope) { FakeSession().also { opened += it } }
        val taker = backgroundScope.async { pool.take(model) }
        runCurrent()
        assertTrue(opened.isEmpty())
        taker.cancel()
        runCurrent()
        while (queue.isNotEmpty()) queue.removeFirst().run()
        // The open finished on its own and the session waits for the next song.
        assertTrue(pool.isWarm)
        assertSame(opened[0], pool.take(model).session)
        assertFalse(opened[0].closed)
        poolScope.cancel()
    }
}
