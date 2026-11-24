package com.gpo.yoin.player.sing

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.gpo.yoin.player.sing.dsp.LyraSingSpec
import com.gpo.yoin.player.sing.dsp.SeparationModel
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One LiteRT CompiledModel of Lyra-Sing-Lite on one accelerator, the same contract as
 * [OrtSeparationModel]: a [1, 4, 512, 256] float chunk in, the vocal chunk out.
 *
 * The input and output TensorBuffers are made once per session and reused for every chunk; LiteRT plans
 * its own activation memory (no arena to shrink). Its Kotlin API only reads an output back as a new
 * FloatArray, so each chunk leaves one 2 MB array for the GC (LiteRT 2.4.0 has no read-into).
 *
 * Every LiteRT call — open, run, close — happens on one worker thread ([LiteRtRuntime.worker]): the GPU
 * backend's context belongs to the thread that made it.
 */
class LiteRtSeparationModel private constructor(
    private val model: CompiledModel,
    private val inputs: List<TensorBuffer>,
    private val outputs: List<TensorBuffer>,
    val accelerator: SingAccelerator,
) : SeparationModel {
    @Volatile private var closed = false

    override fun run(input: FloatArray, output: FloatArray) {
        require(input.size == LyraSingSpec.CHUNK_FLOATS && output.size == LyraSingSpec.CHUNK_FLOATS)
        check(!closed) { "session closed" }
        LiteRtRuntime.call {
            inputs[0].writeFloat(input)
            model.run(inputs, outputs)
            val out = outputs[0].readFloat()
            check(out.size == output.size) { "output has ${out.size} floats" }
            System.arraycopy(out, 0, output, 0, out.size)
        }
    }

    /** Doesn't wait: Sing off closes from the UI thread, and the worker may still be busy. */
    override fun close() {
        if (closed) return
        closed = true
        LiteRtRuntime.post {
            inputs.forEach { runCatching { it.close() } }
            outputs.forEach { runCatching { it.close() } }
            runCatching { model.close() }
        }
    }

    companion object {
        /**
         * Opens [file] on [accelerator]. The NPU takes only an AOT build for this SoC (CPU allowed for any
         * op the compiler left out); the GPU runs fp16 kernels with no silent fallback to the CPU, so a
         * GPU session is one; the CPU runs XNNPACK on [threads] threads.
         */
        internal fun open(
            context: Context,
            file: File,
            accelerator: SingAccelerator,
            threads: Int,
            gpuCacheKey: String,
            npuEnvironment: Boolean,
        ): LiteRtSeparationModel = LiteRtRuntime.call {
            val env = LiteRtRuntime.environment(context, npuEnvironment)
            val options = when (accelerator) {
                SingAccelerator.NPU -> CompiledModel.Options(Accelerator.NPU, Accelerator.CPU)
                SingAccelerator.GPU -> CompiledModel.Options(Accelerator.GPU).disableAutoFallbackToCpu().apply {
                    gpuOptions = CompiledModel.GpuOptions(
                        precision = CompiledModel.GpuOptions.Precision.FP16,
                        // Compiled kernels kept between sessions: the next song's GPU session opens faster.
                        serializationDir = File(context.noBackupFilesDir, GPU_CACHE_DIR).apply { mkdirs() }.absolutePath,
                        modelCacheKey = gpuCacheKey,
                        serializeProgramCache = true,
                    )
                }
                SingAccelerator.CPU -> CompiledModel.Options(Accelerator.CPU).apply {
                    cpuOptions = CompiledModel.CpuOptions(numThreads = threads)
                }
            }
            val model = CompiledModel.create(file.absolutePath, options, env)
            try {
                val inputs = model.createInputBuffers()
                val outputs = model.createOutputBuffers()
                check(inputs.size == 1 && outputs.size == 1) { "expected one input and one output, got ${inputs.size}/${outputs.size}" }
                LiteRtSeparationModel(model, inputs, outputs, accelerator)
            } catch (e: Throwable) {
                model.close()
                throw e
            }
        }

        private const val GPU_CACHE_DIR = "sing-litert-gpu"
    }
}

/**
 * LiteRT's process-wide pieces: the one worker thread every call runs on, and the Environment (made on
 * first use; with the Google Tensor dispatch library when an NPU session is wanted).
 */
internal object LiteRtRuntime {
    private const val TAG = "YoinSing"
    private const val DISPATCH_LIB = "libLiteRtDispatch_GoogleTensor.so"
    private const val DISPATCH_ABI = "arm64-v8a"
    private const val DISPATCH_DIR = "sing-litert-npu"

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "YoinSing-LiteRT").apply { isDaemon = true }
    }

    // Worker thread only.
    private var env: Environment? = null

    /** Runs [block] on the worker and waits; its exception (or error) comes back as it was thrown. */
    fun <T> call(block: () -> T): T = try {
        worker.submit<T> { block() }.get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    fun post(block: () -> Unit) {
        worker.execute { runCatching(block).onFailure { Log.w(TAG, "LiteRT close failed", it) } }
    }

    /**
     * Worker thread only. Made once per process: with the Tensor dispatch library when [npu] (the device's
     * plan has an NPU step, so the first session asks for it), else plain.
     */
    fun environment(context: Context, npu: Boolean): Environment {
        env?.let { return it }
        val dispatchDir = if (npu) dispatchLibraryDir(context) else null
        val made = if (dispatchDir != null) {
            runCatching { Environment.create(mapOf(Environment.Option.DispatchLibraryDir to dispatchDir)) }
                .onFailure { Log.w(TAG, "LiteRT environment with the Tensor dispatch library failed", it) }
                .getOrNull()
        } else {
            null
        }
        val result = made ?: Environment.create()
        env = result
        Log.d(TAG, "LiteRT environment: accelerators ${runCatching { result.getAvailableAccelerators() }.getOrNull()}, dispatch ${dispatchDir ?: "none"}")
        return result
    }

    /**
     * Where the Google Tensor dispatch library is as a file: the app's native library directory when the
     * installer extracted it, else copied out of the APK (native libraries stay in the APK by default)
     * into no-backup storage, checked against the APK entry's CRC. Null where it isn't packaged.
     */
    private fun dispatchLibraryDir(context: Context): String? {
        val info = context.applicationInfo
        info.nativeLibraryDir?.let { if (File(it, DISPATCH_LIB).isFile) return it }
        val apks = listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()
        val entryName = "lib/$DISPATCH_ABI/$DISPATCH_LIB"
        val dir = File(context.noBackupFilesDir, DISPATCH_DIR)
        val target = File(dir, DISPATCH_LIB)
        for (apk in apks) {
            try {
                ZipFile(apk).use { zip ->
                    val entry = zip.getEntry(entryName) ?: return@use
                    if (target.isFile && target.length() == entry.size && crc(target) == entry.crc) return dir.path
                    dir.mkdirs()
                    target.setWritable(true)
                    val part = File(dir, "$DISPATCH_LIB.part")
                    zip.getInputStream(entry).use { input -> part.outputStream().use { input.copyTo(it) } }
                    if (crc(part) != entry.crc || !part.renameTo(target)) {
                        part.delete()
                        throw IOException("dispatch library copy failed")
                    }
                    target.setReadOnly()
                    Log.d(TAG, "Tensor dispatch library copied out of ${File(apk).name}")
                    return dir.path
                }
            } catch (e: IOException) {
                Log.w(TAG, "Tensor dispatch library not read from $apk", e)
            }
        }
        return null
    }

    private fun crc(file: File): Long {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
            }
        }
        return crc.value
    }
}

/**
 * On-device Sing through LiteRT: the builds the meta lists for this device ([SingLiteRtPlan] — the
 * Tensor TPU with this SoC's AOT build, then the GPU, then the CPU with the fp16 build), copied out of the
 * APK's assets into no-backup storage on first use and SHA-256-checked against [SingLiteRtPlan.TRUSTED_SHA256]
 * on every open. A session is a [SingRoutedModel] over that plan.
 */
class LiteRtSingBackend(
    private val context: Context,
    private val catalog: LiteRtCatalog,
    private val threads: Int,
    socManufacturer: String? = socManufacturer(),
    socModel: String? = socModel(),
    npuAllowed: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
) : SingSeparationBackend {
    val plan: List<LiteRtCandidate> = SingLiteRtPlan.plan(catalog, socManufacturer, socModel, npuAllowed)

    private val dir = File(context.noBackupFilesDir, MODEL_DIR)
    private val mutex = Mutex()

    /** "Google Tensor G6" (Build.SOC_MANUFACTURER, Build.SOC_MODEL), for the Labs row and logs. */
    private val soc: String? = listOfNotNull(socManufacturer, socModel).joinToString(" ").ifEmpty { null }

    init {
        require(plan.isNotEmpty()) { "no LiteRT build for this device" }
        Log.d(TAG, "LiteRT plan for SoC $socManufacturer/$socModel: ${plan.joinToString(" → ")}")
    }

    private val models: List<LiteRtModelMeta> = plan.map { it.model }.distinct()

    override val modelId: String = "litert:" + models.joinToString("+") { it.sha256.take(12) }

    override val gateTag: String = "litert-$LITERT_VERSION|$threads|" + plan.joinToString(",") { "${it.model.sha256.take(12)}@${it.accelerator}" }

    override val readyModel: File?
        get() = models.all { fileFor(it).length() == it.bytes }.let { ready -> if (ready) fileFor(plan.first().model) else null }

    override suspend fun ensure(onProgress: (Float) -> Unit): File = mutex.withLock {
        withContext(Dispatchers.IO) {
            val total = models.sumOf { it.bytes }.toFloat()
            var done = 0L
            for (meta in models) {
                ensureActive()
                val target = fileFor(meta)
                if (target.length() != meta.bytes || sha256(target) != meta.sha256) copyFromAssets(meta, target)
                done += meta.bytes
                onProgress(done / total)
            }
            fileFor(plan.first().model)
        }
    }

    override fun open(model: File): SeparationModel {
        val t0 = System.nanoTime()
        val report = SingAcceleratorReport.get(context)
        // Each backend left this session and why: one that wouldn't open is unavailable, one whose chunk
        // threw or overflowed failed.
        var opening: LiteRtCandidate? = null
        val left = mutableListOf<Pair<SingAccelerator, SingFallbackReason>>()
        return SingRoutedModel(
            candidates = plan,
            open = { candidate ->
                opening = candidate
                openCandidate(candidate).also { opening = null }
            },
            onRunning = { candidate ->
                val run = SingBackendRun(
                    accelerator = candidate.accelerator,
                    model = buildName(candidate),
                    fallback = SingBackendLabel.fallback(candidate.accelerator, left),
                    soc = soc,
                )
                Log.i(TAG, "Sing backend: LiteRT ${label(candidate)} — ${SingBackendLabel.summary(run)}, SoC ${soc ?: "unknown"}")
                report.record(run)
            },
            onFailure = { candidate, e ->
                val reason = if (opening == candidate) SingFallbackReason.UNAVAILABLE else SingFallbackReason.FAILED
                opening = null
                left += candidate.accelerator to reason
                Log.w(TAG, "LiteRT ${label(candidate)} ${reason.name.lowercase()}; trying the next backend", e)
            },
        ).also {
            Log.d(TAG, "session open ${(System.nanoTime() - t0) / 1_000_000} ms (LiteRT ${it.active?.let(::label)})")
        }
    }

    private fun openCandidate(candidate: LiteRtCandidate): SeparationModel {
        val meta = candidate.model
        val file = fileFor(meta)
        if (!file.isFile) throw FileNotFoundException(file.path)
        // Checked on every open: only a build this code trusts runs, whatever is on disk.
        val sha = sha256(file)
        if (sha != meta.sha256.lowercase() || sha !in SingLiteRtPlan.TRUSTED_SHA256) {
            throw IOException("${meta.file}: SHA-256 $sha is not the trusted ${meta.sha256}")
        }
        val t0 = System.nanoTime()
        return LiteRtSeparationModel.open(
            context = context,
            file = file,
            accelerator = candidate.accelerator,
            threads = threads,
            gpuCacheKey = "lyra-${sha.take(16)}",
            npuEnvironment = plan.any { it.accelerator == SingAccelerator.NPU },
        ).also {
            Log.d(TAG, "LiteRT ${label(candidate)} compiled in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    private fun copyFromAssets(meta: LiteRtModelMeta, target: File) {
        dir.mkdirs()
        val part = File(dir, target.name + ".part")
        context.assets.open("${SingLiteRtPlan.ASSET_DIR}/${meta.file}").use { input -> part.outputStream().use { input.copyTo(it, 64 * 1024) } }
        val sha = sha256(part)
        if (sha != meta.sha256.lowercase() || sha !in SingLiteRtPlan.TRUSTED_SHA256) {
            part.delete()
            throw IOException("${meta.file}: SHA-256 $sha does not match ${meta.sha256}")
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("${meta.file}: could not move into place")
        }
    }

    private fun fileFor(meta: LiteRtModelMeta) = File(dir, meta.file)

    companion object {
        private const val TAG = "YoinSing"
        const val LITERT_VERSION = "2.4.0"
        const val MODEL_DIR = "sing-model-litert"

        /** The bundled meta, or null when this APK has no LiteRT builds. */
        fun bundledCatalog(context: Context): LiteRtCatalog? = try {
            context.assets.open(SingLiteRtPlan.META_ASSET).use { SingLiteRtPlan.parse(it.readBytes().decodeToString()) }
        } catch (e: FileNotFoundException) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "LiteRT model meta unreadable", e)
            null
        }

        fun socManufacturer(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null

        fun socModel(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null

        /** "Tensor G5 AOT on NPU (Tensor TPU)", "fp16 on GPU", … */
        fun label(candidate: LiteRtCandidate): String {
            val build = buildName(candidate)
            val tpu = if (candidate.accelerator == SingAccelerator.NPU) " (Tensor TPU)" else ""
            return "$build on ${candidate.accelerator}$tpu"
        }

        /** "Tensor G6 AOT", "fp16" (the trusted build's name), else the file name. */
        fun buildName(candidate: LiteRtCandidate): String =
            SingLiteRtPlan.TRUSTED_SHA256[candidate.model.sha256.lowercase()] ?: candidate.model.file

        private fun sha256(file: File): String {
            if (!file.isFile) return ""
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
