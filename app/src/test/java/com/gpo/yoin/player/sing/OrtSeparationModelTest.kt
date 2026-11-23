package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.dsp.LyraSingSpec
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
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
