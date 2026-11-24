package com.gpo.yoin.player.sing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import android.util.Log
import com.gpo.yoin.player.sing.dsp.LyraSingSpec
import com.gpo.yoin.player.sing.dsp.SeparationModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Lyra-Sing-Lite in an ONNX Runtime session on the CPU, through XNNPACK when the runtime has it
 * (the Android package does; the desktop one the unit tests use does not, and runs ORT's own CPU
 * kernels). Close it once nothing needs it ([SingSessionPool] keeps one warm briefly between songs).
 *
 * The input and output tensors are bound once to two direct buffers (2 MB each) and reused for every
 * chunk: a heap array handed to ORT is copied into a fresh direct buffer per call, and reading an
 * output back allocates another copy — ~4 MB of Java garbage per chunk, ~400 MB over a song.
 *
 * Native memory. The model's activations are big (a [1, 144, 512, 256] float tensor is 75 MB; the
 * planned peak of one run is ~250 MB) and live in ORT's CPU arena (malloc, the app's Native Heap). The
 * arena grows by doubling while the first run lays tensors out one at a time and never shrinks on its
 * own: ~550 MB after one run, held while the session lives. With XNNPACK the arena belongs to the XNNPACK
 * provider's allocator, which ORT creates once per process and keeps after every session has closed
 * (Round 3: Native Heap 59 → 551 MB at the first chunk, still 561 MB 30 s after Sing off). So:
 * - the first run asks ORT to shrink the arena when it ends ([ARENA_SHRINK]); later runs reuse the
 *   memory-pattern block, allocated in one piece: the arena then holds ~the planned peak.
 * - weights stay out of the arena (they would pin its first regions);
 * - when the last session closes, [trimSharedArena] runs a one-node session with the same shrink
 *   request, which hands the process-wide arena back to the system.
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
    private val inputs = mapOf(inputName to inputTensor)
    private val pinnedOutputs = mapOf(outputName to outputTensor)

    /** False once a run with the pinned output failed: then every call allocates as ORT likes. */
    private var pinned = true

    private var runs = 0

    @Volatile private var closed = false

    /** Whether runs still write straight into the reused output tensor (for tests). */
    internal val usesPinnedOutput: Boolean get() = pinned

    /** Whether the first run's arena shrink went through (null before it; for tests and logs). */
    internal var arenaShrunk: Boolean? = null
        private set

    init {
        liveSessions.incrementAndGet()
    }

    override fun run(input: FloatArray, output: FloatArray) {
        require(input.size == LyraSingSpec.CHUNK_FLOATS && output.size == LyraSingSpec.CHUNK_FLOATS)
        check(!closed) { "session closed" }
        inputBuffer.clear()
        inputBuffer.put(input)
        val shrink = runs++ == 0
        if (pinned) {
            // The shrink request is checked before anything runs: refused (no arena), the plain run
            // that follows is the only one.
            if (shrink) {
                arenaShrunk = runPinned(shrinkOptions(environment))
                if (arenaShrunk == true) return readPinned(output)
            }
            if (runPinned(null)) return readPinned(output)
            Log.w(TAG, "pinned ORT output failed; copying outputs from now on")
            pinned = false
        }
        session.run(inputs).use { result ->
            val out = result.get(0) as OnnxTensor
            out.floatBuffer.get(output)
        }
    }

    private fun runPinned(options: OrtSession.RunOptions?): Boolean = try {
        if (options == null) {
            session.run(inputs, pinnedOutputs).close()
        } else {
            options.use { session.run(inputs, emptySet(), pinnedOutputs, it).close() }
        }
        true
    } catch (e: OrtException) {
        if (options != null) Log.d(TAG, "arena shrink refused: ${e.message}")
        false
    }

    private fun readPinned(output: FloatArray) {
        outputBuffer.clear()
        outputBuffer.get(output)
    }

    override fun close() {
        if (closed) return
        closed = true
        inputTensor.close()
        outputTensor.close()
        session.close()
        // Off the caller's thread (Sing off closes from the UI): a small session to open and run.
        if (liveSessions.decrementAndGet() == 0 && usesXnnpack) thread(name = "YoinSing-trim") { trimSharedArena(environment) }
    }

    /** [threading]'s answer; [ortSpinning] null leaves ORT's default. */
    data class Threading(val xnnpackThreads: Int, val ortThreads: Int, val ortSpinning: Boolean?)

    companion object {
        private const val TAG = "YoinSing"

        /** ORT's run option: shrink these devices' arenas once the run ends. */
        internal const val ARENA_SHRINK_KEY = "memory.enable_memory_arena_shrinkage"
        internal const val ARENA_SHRINK = "cpu:0"

        /** ORT's intra-op threads next to XNNPACK's pool: the nodes XNNPACK doesn't take (see [threading]). */
        const val ORT_THREADS_WITH_XNNPACK = 2

        /** Sessions open in this process (the shared arena is trimmed when it drops to 0). */
        private val liveSessions = AtomicInteger()

        /** Sessions open now (for tests). */
        internal val openSessions: Int get() = liveSessions.get()

        /** Tags the gate's stored timings with this threading and memory set-up. */
        const val CONFIG_TAG = "ort2+shrink"

        /**
         * How [threads] are spread for [xnnpack]: XNNPACK runs the 102 Convs on its own pool of
         * [threads]; ORT's pool takes everything XNNPACK has no kernel for — Sigmoid, Mul, Add (60, 70
         * and 26 nodes over full-size tensors), ReduceMean, Concat, Slice, Transpose and the layout
         * transposes around XNNPACK's NHWC nodes — and with one thread ran all of that serially
         * (Round 3: the separation thread 94 % busy, XNNPACK's workers 27 %). Two ORT threads, not
         * spinning: the two pools take turns (one node at a time), so no more than [threads] cores work
         * at once. Without XNNPACK, ORT's pool does everything.
         */
        fun threading(threads: Int, xnnpack: Boolean): Threading =
            if (xnnpack) {
                Threading(xnnpackThreads = threads, ortThreads = minOf(ORT_THREADS_WITH_XNNPACK, threads), ortSpinning = false)
            } else {
                Threading(xnnpackThreads = 0, ortThreads = threads, ortSpinning = null)
            }

        /** The session options [open] uses (for tests: what's asked of ORT). */
        internal fun configEntries(threading: Threading): Map<String, String> = buildMap {
            threading.ortSpinning?.let { put("session.intra_op.allow_spinning", if (it) "1" else "0") }
            // Weights outside the arena: they'd pin its first regions against the shrink.
            put("session.use_device_allocator_for_initializers", "1")
        }

        /** [cpuArena] false: ORT's CPU allocator without an arena (tests: a shrink ORT refuses). */
        fun open(
            model: File,
            threads: Int,
            execution: SingExecution = SingExecution.Xnnpack,
            cpuArena: Boolean = true,
        ): OrtSeparationModel {
            val environment = OrtEnvironment.getEnvironment()
            // Yoin sends nothing anywhere it isn't asked to; the Android package's telemetry provider is
            // removed from the manifest too (AndroidManifest.xml).
            runCatching { environment.setTelemetry(false) }
            val xnnpack = execution == SingExecution.Xnnpack && OrtProvider.XNNPACK in OrtEnvironment.getAvailableProviders()
            val options = sessionOptions(threading(threads, xnnpack)).apply { if (!cpuArena) setCPUArenaAllocator(false) }
            val session = options.use { environment.createSession(model.absolutePath, it) }
            return try {
                OrtSeparationModel(environment, session, xnnpack)
            } catch (e: Exception) {
                session.close()
                throw e
            }
        }

        private fun sessionOptions(threading: Threading): OrtSession.SessionOptions =
            OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(threading.ortThreads)
                configEntries(threading).forEach { (k, v) -> addConfigEntry(k, v) }
                if (threading.xnnpackThreads > 0) addXnnpack(mapOf("intra_op_num_threads" to threading.xnnpackThreads.toString()))
            }

        private fun shrinkOptions(environment: OrtEnvironment): OrtSession.RunOptions =
            OrtSession.RunOptions().apply { addRunConfigEntry(ARENA_SHRINK_KEY, ARENA_SHRINK) }

        /**
         * Hands the XNNPACK provider's process-wide arena back to the system once no session needs it:
         * a one-node XNNPACK session's CPU allocator is that same arena, and a run of it with
         * [ARENA_SHRINK] frees every region nothing holds. Where the arena was the session's own (it
         * went with the session) or there is none, ORT refuses the request: nothing to do. True if
         * the shrink went through.
         */
        internal fun trimSharedArena(environment: OrtEnvironment, xnnpack: Boolean = true): Boolean = try {
            val options = sessionOptions(Threading(xnnpackThreads = if (xnnpack) 1 else 0, ortThreads = 1, ortSpinning = false))
            options.use { environment.createSession(TRIM_MODEL, it) }.use { trim ->
                val x = OnnxTensor.createTensor(environment, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1))
                x.use { input -> shrinkOptions(environment).use { trim.run(mapOf("x" to input), it).close() } }
            }
            Log.d(TAG, "native arena trimmed")
            true
        } catch (e: OrtException) {
            Log.d(TAG, "native arena not trimmed: ${e.message}")
            false
        }

        /** ModelProto, opset 17: y = Identity(x), float [1] (onnx.helper, checked by onnx.checker). */
        private val TRIM_MODEL: ByteArray = byteArrayOf(
            8, 8, 18, 0, 58, 55, 10, 16, 10, 1, 120, 18, 1, 121, 34, 8, 73, 100, 101, 110, 116, 105, 116, 121, 18, 1, 116, 90, 15, 10, 1, 120, 18,
            10, 10, 8, 8, 1, 18, 4, 10, 2, 8, 1, 98, 15, 10, 1, 121, 18, 10, 10, 8, 8, 1, 18, 4, 10, 2, 8, 1, 66, 4, 10, 0, 16, 17,
        )

        private fun directFloats(count: Int): FloatBuffer =
            ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}
