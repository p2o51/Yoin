package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.dsp.LyraSingSpec
import com.gpo.yoin.player.sing.dsp.SeparationModel
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SingRoutedModelTest {
    /** A backend that writes [value] everywhere, or fails as told. */
    private inner class FakeBackend(
        val name: String,
        private val value: Float = 1f,
        private val failRuns: Set<Int> = emptySet(),
        private val nanAt: Set<Int> = emptySet(),
    ) : SeparationModel {
        var runs = 0
        var closed = false

        override fun run(input: FloatArray, output: FloatArray) {
            check(!closed)
            val n = runs++
            if (n in failRuns) throw IllegalStateException("$name run $n failed")
            output.fill(value)
            if (n in nanAt) output[output.size / 2] = Float.NaN
        }

        override fun close() {
            closed = true
        }
    }

    private val input = FloatArray(LyraSingSpec.CHUNK_FLOATS)
    private val output = FloatArray(LyraSingSpec.CHUNK_FLOATS)
    private val opened = mutableListOf<FakeBackend>()
    private val running = mutableListOf<String>()

    private fun router(vararg backends: Pair<String, () -> FakeBackend>): SingRoutedModel<String> {
        val byName = backends.toMap()
        return SingRoutedModel(
            candidates = backends.map { it.first },
            open = { name -> byName.getValue(name)().also { opened += it } },
            onRunning = { running += it },
        )
    }

    @Test
    fun should_runOnFirstBackend_when_itOpensAndRuns() {
        val r = router("NPU" to { FakeBackend("NPU", 1f) }, "GPU" to { FakeBackend("GPU", 2f) })
        r.run(input, output)
        r.run(input, output)
        assertEquals("NPU", r.active)
        assertEquals(1f, output[0])
        assertEquals(listOf("NPU"), opened.map { it.name }) // the GPU is never opened
        assertEquals(listOf("NPU"), running) // reported once
        assertEquals(0, r.switches)
    }

    @Test
    fun should_openNext_when_backendFailsToOpen() {
        val r = router(
            "NPU" to { throw IOException("dispatch library missing") },
            "GPU" to { throw UnsatisfiedLinkError("libOpenCL.so") },
            "CPU" to { FakeBackend("CPU", 3f) },
        )
        assertEquals("CPU", r.active)
        assertEquals(listOf("NPU", "GPU"), r.failures.map { it.first })
        // Failing to open isn't a switch: the gate's warm-up is still the first backend's.
        assertEquals(0, r.switches)
        r.run(input, output)
        assertEquals(3f, output[0])
        assertEquals(listOf("CPU"), running)
    }

    @Test
    fun should_fallBackAndRerunChunk_when_runThrows() {
        val r = router("NPU" to { FakeBackend("NPU", 1f, failRuns = setOf(0)) }, "GPU" to { FakeBackend("GPU", 2f) })
        r.run(input, output)
        assertEquals("GPU", r.active)
        assertEquals(2f, output[0])
        assertTrue(opened[0].closed) // the failed one is closed before the next opens
        assertEquals(1, r.switches)
        assertEquals(listOf("GPU"), running)
    }

    @Test
    fun should_fallBack_when_outputNotFinite() {
        val r = router("GPU" to { FakeBackend("GPU", 2f, nanAt = setOf(3)) }, "CPU" to { FakeBackend("CPU", 3f) })
        repeat(3) { r.run(input, output) }
        assertEquals("GPU", r.active)
        r.run(input, output) // fp16 overflow mid-song
        assertEquals("CPU", r.active)
        assertTrue(SingRoutedModel.allFinite(output))
        assertEquals(3f, output[0])
        assertTrue(r.failures.single().second is SingRoutedModel.NonFinite)
        assertEquals(listOf("GPU", "CPU"), running)
    }

    @Test
    fun should_throwNoBackend_when_everyBackendFails() {
        assertThrows(SingRoutedModel.NoBackend::class.java) {
            router("NPU" to { throw IOException("a") }, "CPU" to { throw IOException("b") })
        }
        val r = router("NPU" to { FakeBackend("NPU", failRuns = setOf(0)) }, "CPU" to { FakeBackend("CPU", failRuns = setOf(0)) })
        val e = assertThrows(SingRoutedModel.NoBackend::class.java) { r.run(input, output) }
        assertTrue(e.message!!.contains("NPU") && e.message!!.contains("CPU"))
        assertTrue(opened.all { it.closed })
    }

    @Test
    fun should_closeActive_when_closed() {
        val r = router("NPU" to { FakeBackend("NPU") })
        r.close()
        assertTrue(opened.single().closed)
        assertNull(r.active)
        assertThrows(IllegalStateException::class.java) { r.run(input, output) }
        r.close() // twice is fine
    }

    @Test
    fun should_benchmarkTheBackendThatTookOver_when_warmUpFallsBack() {
        // The NPU fails its first chunk; opening the GPU makes the warm-up slow. The gate warms the GPU
        // once more and times the run after.
        val clock = longArrayOf(0)
        val costs = ArrayDeque(listOf(4_000L, 300L, 200L))
        val backend = { name: String, fail: Boolean ->
            object : SeparationModel {
                override fun run(input: FloatArray, output: FloatArray) {
                    if (fail) {
                        clock[0] += 1_000_000_000L
                        throw IllegalStateException("$name failed")
                    }
                    clock[0] += costs.removeFirst() * 1_000_000L
                }
            }
        }
        val r = SingRoutedModel(listOf("NPU", "GPU"), open = { name -> backend(name, name == "NPU") })
        assertEquals(200L, SingDeviceGate.benchmarkChunkMs(r) { clock[0] })
        assertEquals("GPU", r.active)
    }
}
