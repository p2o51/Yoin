package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.dsp.LyraSingSpec
import ai.onnxruntime.OrtEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session wrapper's reused, pinned tensors on the desktop ONNX Runtime, with a one-node model
 * (voc_spec = −mix_spec, same shape as Lyra-Sing-Lite) written out here: every chunk's output is that
 * chunk's, not a stale or zero buffer.
 */
class OrtSeparationModelTest {
    @Test
    fun should_returnEachChunksOwnOutput_when_tensorsAreReused() {
        val file = File.createTempFile("neg", ".onnx").apply { deleteOnExit() }
        file.writeBytes(negModel())
        OrtSeparationModel.open(file, threads = 1, execution = SingExecution.Cpu).use { model ->
            val output = FloatArray(LyraSingSpec.CHUNK_FLOATS)
            for (round in 1..3) {
                val input = FloatArray(LyraSingSpec.CHUNK_FLOATS) { (it % 97) * 0.01f * round }
                model.run(input, output)
                for (i in input.indices step 4099) assertEquals("round $round at $i", -input[i], output[i], 0f)
                assertEquals(-input.last(), output.last(), 0f)
            }
            assertTrue("pinned output kept", model.usesPinnedOutput)
        }
    }

    private fun negFile() = File.createTempFile("neg", ".onnx").apply {
        deleteOnExit()
        writeBytes(negModel())
    }

    private fun assertNegates(model: OrtSeparationModel, round: Int) {
        val input = FloatArray(LyraSingSpec.CHUNK_FLOATS) { (it % 89) * 0.02f - round }
        val output = FloatArray(LyraSingSpec.CHUNK_FLOATS)
        model.run(input, output)
        for (i in input.indices step 3001) assertEquals("round $round at $i", -input[i], output[i], 0f)
    }

    @Test
    fun should_shrinkTheArenaAfterTheFirstRunOnly_when_arenaPresent() {
        OrtSeparationModel.open(negFile(), threads = 1, execution = SingExecution.Cpu).use { model ->
            assertEquals(null, model.arenaShrunk)
            for (round in 1..3) assertNegates(model, round)
            assertEquals(true, model.arenaShrunk)
            assertTrue(model.usesPinnedOutput)
        }
    }

    @Test
    fun should_runPlainly_when_ortRefusesTheShrink() {
        // No arena: ORT refuses the shrink request before running anything; the chunk still runs once,
        // pinned.
        OrtSeparationModel.open(negFile(), threads = 1, execution = SingExecution.Cpu, cpuArena = false).use { model ->
            for (round in 1..2) assertNegates(model, round)
            assertEquals(false, model.arenaShrunk)
            assertTrue("the refusal isn't a pinned-output failure", model.usesPinnedOutput)
        }
    }

    @Test
    fun should_releaseEverything_when_closed() {
        val before = OrtSeparationModel.openSessions
        val model = OrtSeparationModel.open(negFile(), threads = 1, execution = SingExecution.Cpu)
        assertEquals(before + 1, OrtSeparationModel.openSessions)
        assertNegates(model, 1)
        model.close()
        model.close() // twice is fine (the pool may close what Sing off already closed)
        assertEquals(before, OrtSeparationModel.openSessions)
        assertThrows(IllegalStateException::class.java) { assertNegates(model, 2) }
    }

    @Test
    fun should_runTheTrimSession_when_lastSessionCloses() {
        // The desktop runtime has no XNNPACK; the one-node trim model and its shrink run on ORT's CPU
        // arena all the same.
        assertTrue(OrtSeparationModel.trimSharedArena(OrtEnvironment.getEnvironment(), xnnpack = false))
    }

    @Test
    fun should_giveOrtTwoNonSpinningThreads_when_xnnpackRuns() {
        val xnnpack = OrtSeparationModel.threading(4, xnnpack = true)
        assertEquals(OrtSeparationModel.Threading(xnnpackThreads = 4, ortThreads = 2, ortSpinning = false), xnnpack)
        assertEquals("0", OrtSeparationModel.configEntries(xnnpack)["session.intra_op.allow_spinning"])
        assertEquals(OrtSeparationModel.Threading(2, 2, false), OrtSeparationModel.threading(2, xnnpack = true))
        val cpu = OrtSeparationModel.threading(4, xnnpack = false)
        assertEquals(OrtSeparationModel.Threading(xnnpackThreads = 0, ortThreads = 4, ortSpinning = null), cpu)
        assertFalse("ORT's default spinning", "session.intra_op.allow_spinning" in OrtSeparationModel.configEntries(cpu))
        for (t in listOf(xnnpack, cpu)) {
            assertEquals("1", OrtSeparationModel.configEntries(t)["session.use_device_allocator_for_initializers"])
        }
    }

    /** A minimal ONNX ModelProto: Neg from mix_spec to voc_spec, [1, 4, 512, 256] float, opset 17. */
    private fun negModel(): ByteArray {
        val dims = longArrayOf(1, LyraSingSpec.MODEL_CHANNELS.toLong(), LyraSingSpec.MODEL_BINS.toLong(), LyraSingSpec.CHUNK_FRAMES.toLong())
        fun valueInfo(name: String) = message {
            string(1, name)
            bytes(
                2,
                message { // TypeProto
                    bytes(
                        1,
                        message { // TypeProto.Tensor
                            varint(1, 1) // FLOAT
                            bytes(2, message { dims.forEach { d -> bytes(1, message { varint(1, d) }) } })
                        },
                    )
                },
            )
        }
        val graph = message {
            bytes(
                1,
                message { // NodeProto
                    string(1, LyraSingSpec.INPUT_NAME)
                    string(2, LyraSingSpec.OUTPUT_NAME)
                    string(4, "Neg")
                },
            )
            string(2, "neg")
            bytes(11, valueInfo(LyraSingSpec.INPUT_NAME))
            bytes(12, valueInfo(LyraSingSpec.OUTPUT_NAME))
        }
        return message {
            varint(1, 8) // ir_version
            bytes(7, graph)
            bytes(8, message { varint(2, 17) }) // opset_import: default domain, 17
        }
    }

    private class Proto {
        val out = ByteArrayOutputStream()

        private fun rawVarint(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }

        fun varint(field: Int, value: Long) {
            rawVarint((field shl 3).toLong())
            rawVarint(value)
        }

        fun bytes(field: Int, value: ByteArray) {
            rawVarint(((field shl 3) or 2).toLong())
            rawVarint(value.size.toLong())
            out.write(value)
        }

        fun string(field: Int, value: String) = bytes(field, value.toByteArray())
    }

    private fun message(build: Proto.() -> Unit): ByteArray = Proto().apply(build).out.toByteArray()
}
