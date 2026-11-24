package com.gpo.yoin.player.sing

import android.util.Log
import com.gpo.yoin.player.sing.dsp.SeparationModel
import java.io.File

/**
 * Where on-device Sing's model comes from and what runs it, for [OnDeviceSingStreamProvider]: the model
 * file(s) made ready ([ensure]), and a session opened on them ([open], one [SeparationModel] per
 * session, kept warm by [SingSessionPool]). Two today: LiteRT (Tensor TPU → GPU → CPU, the default,
 * [LiteRtSingBackend]) and ONNX Runtime on the CPU ([OrtSingBackend], behind `-PyoinSingOrt=true`).
 */
interface SingSeparationBackend {
    /** Names what the separation's output comes from: part of the vocal cache key. */
    val modelId: String

    /** Tags the device gate's stored timings: the backend, its builds and threads. */
    val gateTag: String

    /** The model to open, if it is already here ([ensure] makes it so). */
    val readyModel: File?

    /** The model, verified and ready to open, fetching it first if need be. Throws IOException on failure. */
    suspend fun ensure(onProgress: (Float) -> Unit = {}): File

    /** A session on [model] (from [ensure]). Blocking; can take seconds. */
    fun open(model: File): SeparationModel
}

/** Lyra-Sing-Lite through ONNX Runtime on the CPU/XNNPACK, the model downloaded on first use. */
class OrtSingBackend(
    private val store: SingModelStore,
    private val threads: Int,
) : SingSeparationBackend {
    // The model's SHA alone, as before the backends: vocal tracks cached by the ORT engine stay valid.
    override val modelId: String get() = store.config.sha256

    override val gateTag: String
        get() = "${store.config.sha256}|$threads|${store.config.execution}|${OrtSeparationModel.CONFIG_TAG}"

    override val readyModel: File? get() = store.file.takeIf { it.isFile }

    override suspend fun ensure(onProgress: (Float) -> Unit): File = store.ensure(onProgress)

    override fun open(model: File): SeparationModel {
        val t0 = System.nanoTime()
        return OrtSeparationModel.open(model, threads, store.config.execution).also {
            val t = OrtSeparationModel.threading(threads, it.usesXnnpack)
            val how = if (it.usesXnnpack) "XNNPACK ${t.xnnpackThreads} threads + ORT ${t.ortThreads}" else "CPU, ${t.ortThreads} threads"
            Log.d(TAG, "session open ${(System.nanoTime() - t0) / 1_000_000} ms (ONNX Runtime, $how)")
        }
    }

    private companion object {
        const val TAG = "YoinSing"
    }
}
