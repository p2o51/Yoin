package com.gpo.yoin.player.sing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import com.gpo.yoin.player.sing.dsp.LyraSingSpec
import com.gpo.yoin.player.sing.dsp.SeparationModel
import java.io.File
import java.nio.FloatBuffer

/**
 * Lyra-Sing-Lite in an ONNX Runtime session on the CPU, through XNNPACK when the runtime has it
 * (the Android package does; the desktop one the unit tests use does not, and runs ORT's own CPU
 * kernels). One session per song: [close] it as soon as the song's chunks are through (race to idle).
 */
class OrtSeparationModel private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    val usesXnnpack: Boolean,
) : SeparationModel {
    private val shape = longArrayOf(1, LyraSingSpec.MODEL_CHANNELS.toLong(), LyraSingSpec.MODEL_BINS.toLong(), LyraSingSpec.CHUNK_FRAMES.toLong())

    override fun run(input: FloatArray, output: FloatArray) {
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(LyraSingSpec.INPUT_NAME to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                out.floatBuffer.get(output)
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        fun open(model: File, threads: Int, execution: SingExecution = SingExecution.Xnnpack): OrtSeparationModel {
            val environment = OrtEnvironment.getEnvironment()
            // Yoin sends nothing anywhere it isn't asked to; the Android package's telemetry provider is
            // removed from the manifest too (AndroidManifest.xml).
            runCatching { environment.setTelemetry(false) }
            val xnnpack = execution == SingExecution.Xnnpack && OrtProvider.XNNPACK in OrtEnvironment.getAvailableProviders()
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                if (xnnpack) {
                    // ORT's advice for XNNPACK: its own pool does the work, ORT's stays at one thread
                    // and doesn't spin.
                    setIntraOpNumThreads(1)
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                    addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                } else {
                    setIntraOpNumThreads(threads)
                }
            }
            val session = options.use { environment.createSession(model.absolutePath, it) }
            return OrtSeparationModel(environment, session, xnnpack)
        }
    }
}
