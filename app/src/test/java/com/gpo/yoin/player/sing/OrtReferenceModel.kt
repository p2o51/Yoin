package com.gpo.yoin.player.sing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.gpo.yoin.player.sing.dsp.LyraSingSpec
import com.gpo.yoin.player.sing.dsp.SeparationModel
import java.io.File
import java.nio.FloatBuffer

/**
 * Test only: the exported Lyra-Sing-Lite (.onnx) on the desktop ONNX Runtime's CPU kernels, so
 * [LyraSingModelParityTest] can check the Kotlin pipeline against Prism's Python onnxruntime outputs.
 * The app runs the model on LiteRT ([LiteRtSeparationModel]) and has no ONNX Runtime.
 */
class OrtReferenceModel private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) : SeparationModel {
    private val shape = longArrayOf(1, LyraSingSpec.MODEL_CHANNELS.toLong(), LyraSingSpec.MODEL_BINS.toLong(), LyraSingSpec.CHUNK_FRAMES.toLong())
    private val inputName = session.inputNames.firstOrNull() ?: LyraSingSpec.INPUT_NAME

    override fun run(input: FloatArray, output: FloatArray) {
        require(input.size == LyraSingSpec.CHUNK_FLOATS && output.size == LyraSingSpec.CHUNK_FLOATS)
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                (result.get(0) as OnnxTensor).floatBuffer.get(output)
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        fun open(model: File, threads: Int): OrtReferenceModel {
            val environment = OrtEnvironment.getEnvironment()
            runCatching { environment.setTelemetry(false) }
            val session = OrtSession.SessionOptions().use { options ->
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setIntraOpNumThreads(threads)
                environment.createSession(model.absolutePath, options)
            }
            return OrtReferenceModel(environment, session)
        }
    }
}
