package com.gpo.yoin.player.sing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import android.util.Log
import com.gpo.yoin.player.sing.dsp.LyraSingSpec
import com.gpo.yoin.player.sing.dsp.SeparationModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Lyra-Sing-Lite in an ONNX Runtime session on the CPU, through XNNPACK when the runtime has it
 * (the Android package does; the desktop one the unit tests use does not, and runs ORT's own CPU
 * kernels). Close it once nothing needs it ([SingSessionPool] keeps one warm briefly between songs).
 *
 * The input and output tensors are bound once to two direct buffers (2 MB each) and reused for every
 * chunk: a heap array handed to ORT is copied into a fresh direct buffer per call, and reading an
 * output back allocates another copy — ~4 MB of Java garbage per chunk, ~400 MB over a song.
 */
class OrtSeparationModel private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    val usesXnnpack: Boolean,
) : SeparationModel {
    private val shape = longArrayOf(1, LyraSingSpec.MODEL_CHANNELS.toLong(), LyraSingSpec.MODEL_BINS.toLong(), LyraSingSpec.CHUNK_FRAMES.toLong())
    private val inputBuffer = directFloats(LyraSingSpec.CHUNK_FLOATS)
    private val outputBuffer = directFloats(LyraSingSpec.CHUNK_FLOATS)
    private val inputName = session.inputNames.firstOrNull() ?: LyraSingSpec.INPUT_NAME
    private val outputName = session.outputNames.firstOrNull() ?: LyraSingSpec.OUTPUT_NAME
    private val inputTensor = OnnxTensor.createTensor(environment, inputBuffer, shape)
    private val outputTensor = OnnxTensor.createTensor(environment, outputBuffer, shape)

    /** False once a run with the pinned output failed: then every call allocates as ORT likes. */
    private var pinned = true

    /** Whether runs still write straight into the reused output tensor (for tests). */
    internal val usesPinnedOutput: Boolean get() = pinned

    override fun run(input: FloatArray, output: FloatArray) {
        require(input.size == LyraSingSpec.CHUNK_FLOATS && output.size == LyraSingSpec.CHUNK_FLOATS)
        inputBuffer.clear()
        inputBuffer.put(input)
        if (pinned) {
            val ok = runCatching {
                session.run(mapOf(inputName to inputTensor), mapOf(outputName to outputTensor)).close()
            }.isSuccess
            if (ok) {
                outputBuffer.clear()
                outputBuffer.get(output)
                return
            }
            Log.w("YoinSing", "pinned ORT output failed; copying outputs from now on")
            pinned = false
        }
        session.run(mapOf(inputName to inputTensor)).use { result ->
            val out = result.get(0) as OnnxTensor
            out.floatBuffer.get(output)
        }
    }

    override fun close() {
        inputTensor.close()
        outputTensor.close()
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
            return try {
                OrtSeparationModel(environment, session, xnnpack)
            } catch (e: Exception) {
                session.close()
                throw e
            }
        }

        private fun directFloats(count: Int): FloatBuffer =
            ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}
